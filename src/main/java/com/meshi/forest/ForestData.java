package com.meshi.forest;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

// saved per world: is floor 2 built, its seed + the settings it was built with (so regenerate can clear exactly what was there)
public class ForestData extends SavedData {
    public static final String NAME = "meshi_forest";

    public boolean generated = false;
    public long seed = 0L;
    public BlockPos spawn = BlockPos.ZERO;
    public BlockPos exit = BlockPos.ZERO;
    public final Map<String, String> builtWith = new LinkedHashMap<>();

    public static ForestData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ForestData::new, ForestData::load, null), NAME);
    }

    public static ForestData load(CompoundTag tag, HolderLookup.Provider provider) {
        ForestData d = new ForestData();
        d.generated = tag.getBoolean("Generated");
        d.seed = tag.getLong("Seed");
        d.spawn = BlockPos.of(tag.getLong("Spawn"));
        d.exit = BlockPos.of(tag.getLong("Exit"));
        CompoundTag s = tag.getCompound("Settings");
        for (String k : s.getAllKeys()) {
            d.builtWith.put(k, s.getString(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("Generated", this.generated);
        tag.putLong("Seed", this.seed);
        tag.putLong("Spawn", this.spawn.asLong());
        tag.putLong("Exit", this.exit.asLong());
        CompoundTag s = new CompoundTag();
        for (Map.Entry<String, String> e : this.builtWith.entrySet()) {
            s.putString(e.getKey(), e.getValue());
        }
        tag.put("Settings", s);
        return tag;
    }
}
