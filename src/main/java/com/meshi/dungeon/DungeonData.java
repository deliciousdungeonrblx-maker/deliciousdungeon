package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

// saved per dungeon: built?, spawn, mushroom spots + uuids, loot containers, safezones, mob caps
public class DungeonData extends SavedData {
    public static final String NAME = "meshi_dungeon";
    public static final int MUSHROOM_CAP = 20;
    public static final int MAX_SPOTS = 3000;

    public boolean generated = false;
    public BlockPos spawn = BlockPos.ZERO;
    public final List<BlockPos> mushroomSpots = new ArrayList<>();
    public final Set<UUID> mushrooms = new HashSet<>();
    // block pos (as long) -> loot pool name
    public final Map<Long, String> lootSpots = new LinkedHashMap<>();
    // min corner (as long) of every safezone tile
    public final List<Long> safezones = new ArrayList<>();
    // zombie + skeleton cap per floor, deeper = more. only counts loaded ones
    public int[] mobCaps = {6, 10, 14};

    public static DungeonData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(DungeonData::new, DungeonData::load, null), NAME);
    }

    public static DungeonData load(CompoundTag tag, HolderLookup.Provider provider) {
        DungeonData data = new DungeonData();
        data.generated = tag.getBoolean("Generated");
        data.spawn = BlockPos.of(tag.getLong("Spawn"));
        for (Tag t : tag.getList("Spots", Tag.TAG_LONG)) {
            data.mushroomSpots.add(BlockPos.of(((LongTag) t).getAsLong()));
        }
        for (Tag t : tag.getList("Mushrooms", Tag.TAG_INT_ARRAY)) {
            data.mushrooms.add(NbtUtils.loadUUID(t));
        }
        for (Tag t : tag.getList("Loot", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            data.lootSpots.put(c.getLong("Pos"), c.getString("Pool"));
        }
        for (Tag t : tag.getList("Safezones", Tag.TAG_LONG)) {
            data.safezones.add(((LongTag) t).getAsLong());
        }
        int[] caps = tag.getIntArray("MobCaps");
        if (caps.length == 3) {
            data.mobCaps = caps;
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("Generated", this.generated);
        tag.putLong("Spawn", this.spawn.asLong());
        ListTag spots = new ListTag();
        for (BlockPos p : this.mushroomSpots) {
            spots.add(LongTag.valueOf(p.asLong()));
        }
        tag.put("Spots", spots);
        ListTag ids = new ListTag();
        for (UUID id : this.mushrooms) {
            ids.add(NbtUtils.createUUID(id));
        }
        tag.put("Mushrooms", ids);
        ListTag loot = new ListTag();
        for (Map.Entry<Long, String> e : this.lootSpots.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putLong("Pos", e.getKey());
            c.putString("Pool", e.getValue());
            loot.add(c);
        }
        tag.put("Loot", loot);
        ListTag safe = new ListTag();
        for (long l : this.safezones) {
            safe.add(LongTag.valueOf(l));
        }
        tag.put("Safezones", safe);
        tag.putIntArray("MobCaps", this.mobCaps);
        return tag;
    }

    // is this spot inside a safezone tile (+ margin blocks around it)
    public boolean inSafezone(double x, double y, double z, int margin) {
        for (long l : this.safezones) {
            BlockPos o = BlockPos.of(l);
            if (x >= o.getX() - margin && x < o.getX() + DungeonBuilder.TILE + margin
                    && z >= o.getZ() - margin && z < o.getZ() + DungeonBuilder.TILE + margin
                    && y >= o.getY() - 2 && y < o.getY() + DungeonBuilder.FLOOR_H + 2) {
                return true;
            }
        }
        return false;
    }

    public void reset() {
        this.generated = false;
        this.mushroomSpots.clear();
        this.mushrooms.clear();
        this.lootSpots.clear();
        this.safezones.clear();
        setDirty();
    }
}
