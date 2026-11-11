package com.meshi.forest;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

// saved per world: is floor 2 built, with which radius + seed. the plan is the same for the same radius + seed
// so regenerate can work out exactly what has to be cleared
public class ForestData extends SavedData {
    public static final String NAME = "meshi_forest";

    public boolean generated = false;
    public long seed = 0L;
    public int radius = ForestPlan.DEFAULT_RADIUS;
    public BlockPos spawn = BlockPos.ZERO;
    public BlockPos exit = BlockPos.ZERO;
    // every (radius, seed) that may have blocks in the world. more than one when a build got interrupted
    public final List<long[]> plans = new ArrayList<>();

    public static ForestData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ForestData::new, ForestData::load, null), NAME);
    }

    public static ForestData load(CompoundTag tag, HolderLookup.Provider provider) {
        ForestData d = new ForestData();
        d.generated = tag.getBoolean("Generated");
        d.seed = tag.getLong("Seed");
        if (tag.contains("Radius")) {
            d.radius = tag.getInt("Radius");
        }
        d.spawn = BlockPos.of(tag.getLong("Spawn"));
        d.exit = BlockPos.of(tag.getLong("Exit"));
        for (Tag t : tag.getList("Plans", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            d.plans.add(new long[] {c.getInt("Radius"), c.getLong("Seed")});
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        tag.putBoolean("Generated", this.generated);
        tag.putLong("Seed", this.seed);
        tag.putInt("Radius", this.radius);
        tag.putLong("Spawn", this.spawn.asLong());
        tag.putLong("Exit", this.exit.asLong());
        ListTag list = new ListTag();
        for (long[] p : this.plans) {
            CompoundTag c = new CompoundTag();
            c.putInt("Radius", (int) p[0]);
            c.putLong("Seed", p[1]);
            list.add(c);
        }
        tag.put("Plans", list);
        return tag;
    }
}
