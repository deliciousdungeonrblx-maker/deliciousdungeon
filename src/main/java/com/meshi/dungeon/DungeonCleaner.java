package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

// the dungeon cleaners (just a system, no mob):
// - blocks a player places in the dungeon crack away over 1 min, starting 5 min after they were placed
// - dungeon blocks that get broken are rebuilt one by one, 2s apart (lowest first)
// edit plots (x >= EDIT_MIN_X) are left alone so room editing still works
public final class DungeonCleaner {
    public static final long CLEAN_DELAY = 6000L; // 5 min
    public static final long BREAK_TIME = 1200L; // 1 min
    public static final int REBUILD_GAP = 40; // 2s
    private static final int CRACK_ID = 0x4D455348;
    private static final int QUIET = 2 | 16; // no neighbour updates, so nothing pops off and drops

    private DungeonCleaner() {
    }

    // ---- saved data

    private static final class Placed {
        final long time;
        int stage = -1;

        Placed(long time) {
            this.time = time;
        }
    }

    private record Check(long due, BlockPos pos, BlockState state) {
    }

    private static final class Data extends SavedData {
        static final String NAME = "meshi_dungeon_cleaner";

        // player blocks: pos (as long) -> when it was placed
        final Map<Long, Placed> placed = new HashMap<>();
        // broken dungeon blocks waiting to be rebuilt, in the order they broke
        final LinkedHashMap<Long, BlockState> missing = new LinkedHashMap<>();
        // neighbours of a broken block, looked at again 2 ticks later (torches etc that popped off)
        final List<Check> checks = new ArrayList<>();
        long nextRebuild = 0L;

        static Data get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(Data::new, Data::load, null), NAME);
        }

        static Data load(CompoundTag tag, HolderLookup.Provider provider) {
            Data data = new Data();
            for (Tag t : tag.getList("Placed", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                data.placed.put(c.getLong("Pos"), new Placed(c.getLong("Time")));
            }
            var blocks = provider.lookupOrThrow(Registries.BLOCK);
            for (Tag t : tag.getList("Missing", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                data.missing.put(c.getLong("Pos"), NbtUtils.readBlockState(blocks, c.getCompound("State")));
            }
            return data;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
            ListTag p = new ListTag();
            for (Map.Entry<Long, Placed> e : this.placed.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("Pos", e.getKey());
                c.putLong("Time", e.getValue().time);
                p.add(c);
            }
            tag.put("Placed", p);
            ListTag m = new ListTag();
            for (Map.Entry<Long, BlockState> e : this.missing.entrySet()) {
                CompoundTag c = new CompoundTag();
                c.putLong("Pos", e.getKey());
                c.put("State", NbtUtils.writeBlockState(e.getValue()));
                m.add(c);
            }
            tag.put("Missing", m);
            return tag;
        }
    }

    // called when the dungeon is regenerated, old tracking is meaningless then
    public static void clear(ServerLevel level) {
        Data d = Data.get(level);
        d.placed.clear();
        d.missing.clear();
        d.checks.clear();
        d.setDirty();
    }

    // ---- tracking

    private static boolean active(ServerLevel level, BlockPos pos) {
        return level.dimension().equals(DungeonEvents.DIMENSION)
                && pos.getX() < DungeonBuilder.EDIT_MIN_X
                && DungeonData.get(level).generated;
    }

    // key of the player block at pos, or of its other half (doors etc), or null if its not a player block
    private static Long findPlaced(Data d, BlockPos pos, BlockState state) {
        if (d.placed.containsKey(pos.asLong())) {
            return pos.asLong();
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (d.placed.containsKey(other.asLong())) {
                return other.asLong();
            }
        }
        return null;
    }

    private static void addMissing(ServerLevel level, Data d, BlockPos pos, BlockState state) {
        if (d.missing.isEmpty()) {
            d.nextRebuild = Math.max(d.nextRebuild, level.getGameTime() + REBUILD_GAP);
        }
        d.missing.put(pos.asLong(), state);
        d.setDirty();
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player) || !(event.getLevel() instanceof ServerLevel level)
                || !active(level, event.getPos())) {
            return;
        }
        Data d = Data.get(level);
        d.placed.put(event.getPos().asLong(), new Placed(level.getGameTime()));
        d.setDirty();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !active(level, event.getPos())) {
            return;
        }
        Data d = Data.get(level);
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        Long own = findPlaced(d, pos, state);
        if (own != null) {
            crack(level, BlockPos.of(own), -1);
            d.placed.remove(own);
            d.setDirty();
            return;
        }
        if (state.isAir()) {
            return;
        }
        addMissing(level, d, pos, state);
        // stuff that was hanging on this block might fall off, check again in a sec
        for (Direction dir : Direction.values()) {
            BlockPos np = pos.relative(dir);
            BlockState ns = level.getBlockState(np);
            if (!ns.isAir() && np.getX() < DungeonBuilder.EDIT_MIN_X
                    && !d.placed.containsKey(np.asLong()) && !d.missing.containsKey(np.asLong())) {
                d.checks.add(new Check(level.getGameTime() + 2L, np.immutable(), ns));
            }
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(DungeonEvents.DIMENSION)) {
            return;
        }
        Data d = Data.get(level);
        if (!DungeonData.get(level).generated) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (pos.getX() >= DungeonBuilder.EDIT_MIN_X) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            Long own = findPlaced(d, pos, state);
            if (own != null) {
                d.placed.remove(own);
                d.setDirty();
            } else {
                addMissing(level, d, pos.immutable(), state);
            }
        }
    }

    // ---- tick (called from DungeonEvents while the dungeon exists and isnt being built)

    public static void tick(ServerLevel level) {
        Data d = Data.get(level);
        long now = level.getGameTime();
        if (!d.checks.isEmpty()) {
            runChecks(level, d, now);
        }
        if (now % 5L == 0L && !d.placed.isEmpty()) {
            cleanPlaced(level, d, now);
        }
        if (!d.missing.isEmpty()) {
            rebuild(level, d, now);
        }
    }

    private static void runChecks(ServerLevel level, Data d, long now) {
        Iterator<Check> it = d.checks.iterator();
        while (it.hasNext()) {
            Check c = it.next();
            if (c.due() > now) {
                continue;
            }
            it.remove();
            if (!level.isLoaded(c.pos())) {
                continue;
            }
            BlockState cur = level.getBlockState(c.pos());
            if (!cur.is(c.state().getBlock()) && !d.placed.containsKey(c.pos().asLong())
                    && !d.missing.containsKey(c.pos().asLong())) {
                addMissing(level, d, c.pos(), c.state());
            }
        }
    }

    private static void cleanPlaced(ServerLevel level, Data d, long now) {
        boolean changed = false;
        Iterator<Map.Entry<Long, Placed>> it = d.placed.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Placed> e = it.next();
            BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                crack(level, pos, -1);
                it.remove();
                changed = true;
                continue;
            }
            Placed p = e.getValue();
            long into = now - p.time - CLEAN_DELAY;
            if (into < 0L) {
                continue;
            }
            if (into >= BREAK_TIME) {
                crack(level, pos, -1);
                destroy(level, pos, state);
                it.remove();
                changed = true;
            } else {
                int stage = (int) (into * 10L / BREAK_TIME);
                if (stage != p.stage) {
                    p.stage = stage;
                    crack(level, pos, stage);
                }
            }
        }
        if (changed) {
            d.setDirty();
        }
    }

    private static void destroy(ServerLevel level, BlockPos pos, BlockState state) {
        fx(level, pos, state);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            BlockPos other = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (level.getBlockState(other).is(state.getBlock())) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), QUIET);
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), QUIET);
    }

    // one block every REBUILD_GAP ticks, lowest first so walls go up from the floor
    private static void rebuild(ServerLevel level, Data d, long now) {
        if (now < d.nextRebuild) {
            return;
        }
        long pickKey = 0L;
        BlockState pickState = null;
        int bestY = Integer.MAX_VALUE;
        boolean changed = false;
        Iterator<Map.Entry<Long, BlockState>> it = d.missing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, BlockState> e = it.next();
            BlockPos pos = BlockPos.of(e.getKey());
            if (!level.isLoaded(pos)) {
                continue;
            }
            // a player block is in the way, wait for the cleaner to remove it first
            if (d.placed.containsKey(e.getKey())) {
                continue;
            }
            BlockState cur = level.getBlockState(pos);
            if (cur.is(e.getValue().getBlock())) {
                it.remove();
                changed = true;
                continue;
            }
            if (!(cur.isAir() || cur.canBeReplaced()) || !level.isUnobstructed(e.getValue(), pos, CollisionContext.empty())) {
                continue;
            }
            if (pos.getY() < bestY) {
                bestY = pos.getY();
                pickKey = e.getKey();
                pickState = e.getValue();
            }
        }
        if (pickState != null) {
            BlockPos pos = BlockPos.of(pickKey);
            level.setBlock(pos, pickState, 3);
            fx(level, pos, pickState);
            d.missing.remove(pickKey);
            d.nextRebuild = now + REBUILD_GAP;
            changed = true;
        }
        if (changed) {
            d.setDirty();
        }
    }

    // ---- fx

    private static void crack(ServerLevel level, BlockPos pos, int stage) {
        long k = pos.asLong();
        level.destroyBlockProgress(CRACK_ID + (int) (k ^ (k >>> 32)), pos, stage);
    }

    // place sound + white puff, same for breaking and rebuilding
    private static void fx(ServerLevel level, BlockPos pos, BlockState state) {
        SoundType st = state.getSoundType();
        level.playSound(null, pos, st.getPlaceSound(), SoundSource.BLOCKS, (st.getVolume() + 1.0F) / 2.0F, st.getPitch() * 0.8F);
        level.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                6, 0.3D, 0.3D, 0.3D, 0.02D);
    }
}
