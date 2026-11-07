package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

// saved per dungeon dimension: is it built, where the spawn room is, floor spots for mushrooms,
// and the uuids of every walking mushroom that is alive (so unloaded ones still count toward 20)
public class DungeonData extends SavedData {
    public static final String NAME = "meshi_dungeon";
    public static final int MUSHROOM_CAP = 20;

    public boolean generated = false;
    public BlockPos spawn = BlockPos.ZERO;
    public final List<BlockPos> mushroomSpots = new ArrayList<>();
    public final Set<UUID> mushrooms = new HashSet<>();

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
        return tag;
    }

    public void reset() {
        this.generated = false;
        this.mushroomSpots.clear();
        this.mushrooms.clear();
        setDirty();
    }
}
