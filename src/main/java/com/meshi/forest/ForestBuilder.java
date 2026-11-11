package com.meshi.forest;

import com.meshi.forest.ForestDefs.Def;
import com.meshi.forest.ForestDefs.Door;
import com.meshi.forest.ForestPlan.Placement;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

// one build job: first clears some boxes, then stamps parts (saved version or generated look, turned to fit).
// works on a time budget per tick so a huge map doesnt freeze the server. jobs run one after another (ForestEvents)
public final class ForestBuilder {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEAR_FLAGS = FLAGS | Block.UPDATE_SUPPRESS_DROPS;
    private static final long BUDGET_NANOS = 30_000_000L;
    private static final Rotation[] ROT = {Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90};

    private final ServerLevel level;
    private final PartStore store;
    private final List<int[]> clearBoxes;
    private final List<Placement> stamps;
    private final boolean useSaved;
    public final Runnable onDone;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private int ci = 0;
    private long cc = 0;
    private int si = 0;
    private long cell = 0;
    private ForestStamp.Source cur = null;

    public ForestBuilder(ServerLevel level, List<int[]> clearBoxes, List<Placement> stamps, boolean useSaved, Runnable onDone) {
        this.level = level;
        this.store = PartStore.get(level);
        this.clearBoxes = clearBoxes;
        this.stamps = stamps;
        this.useSaved = useSaved;
        this.onDone = onDone;
    }

    public int remainingParts() {
        return this.stamps.size() - this.si;
    }

    public int totalParts() {
        return this.stamps.size();
    }

    public int remainingBoxes() {
        return this.clearBoxes.size() - this.ci;
    }

    // true when finished
    public boolean tick() {
        long end = System.nanoTime() + BUDGET_NANOS;
        int ops = 0;
        while (this.ci < this.clearBoxes.size()) {
            int[] b = this.clearBoxes.get(this.ci);
            long h = b[4] - b[1] + 1, d = b[5] - b[2] + 1;
            long total = (b[3] - b[0] + 1) * h * d;
            while (this.cc < total) {
                long i = this.cc++;
                int x = b[0] + (int) (i / (h * d));
                long rem = i % (h * d);
                this.pos.set(x, b[1] + (int) (rem / d), b[2] + (int) (rem % d));
                if (!this.level.getBlockState(this.pos).isAir()) {
                    this.level.setBlock(this.pos, Blocks.AIR.defaultBlockState(), CLEAR_FLAGS);
                }
                if ((++ops & 1023) == 0 && System.nanoTime() > end) {
                    return false;
                }
            }
            this.ci++;
            this.cc = 0;
        }
        while (this.si < this.stamps.size()) {
            Placement p = this.stamps.get(this.si);
            Def def = p.def;
            if (this.cur == null) {
                this.cur = ForestStamp.source(def, p.seed, this.store, this.useSaved);
                this.cell = 0;
            }
            long yz = (long) def.sy * def.sz;
            long total = def.sx * yz;
            while (this.cell < total) {
                long i = this.cell++;
                int x = (int) (i / yz);
                int rem = (int) (i % yz);
                int y = rem / def.sz, z = rem % def.sz;
                BlockState st = this.cur.at(x, y, z);
                if (st != null) {
                    int[] r = ForestDefs.rotate(x - def.ax, z - def.az, p.rot);
                    this.pos.set(p.x + r[0], p.y + (y - def.ay), p.z + r[1]);
                    this.level.setBlock(this.pos, p.rot == 0 ? st : st.rotate(ROT[p.rot & 3]), FLAGS);
                }
                if ((++ops & 1023) == 0 && System.nanoTime() > end) {
                    return false;
                }
            }
            seal(p);
            this.cur = null;
            this.si++;
        }
        return true;
    }

    // fills the doorways / railing gaps that no bridge or log goes through, with the block found next to them
    private void seal(Placement p) {
        for (int di : p.sealed) {
            Door d = p.def.doors.get(di);
            BlockState sample = this.level.getBlockState(world(p, ForestDefs.dx(d.face, d.sampleR, d.sampleL), d.sampleY(),
                    ForestDefs.dz(d.face, d.sampleR, d.sampleL)));
            if (sample.isAir()) {
                sample = ForestStamp.state(d.fallback);
            }
            boolean rail = d.fallback == com.meshi.forest.PartGrid.Mat.RAIL;
            for (int l = -(d.w / 2); l <= d.w / 2; l++) {
                for (int r = d.r0; r <= d.r1; r++) {
                    for (int y = d.floorY(); y < d.floorY() + d.h; y++) {
                        if (rail && y > d.floorY() + 1) {
                            continue;
                        }
                        this.level.setBlock(world(p, ForestDefs.dx(d.face, r, l), y, ForestDefs.dz(d.face, r, l)), sample, Block.UPDATE_CLIENTS);
                    }
                }
            }
            // fences / walls next to the filled spot need to connect to it
            for (int l = -(d.w / 2) - 1; l <= d.w / 2 + 1; l++) {
                for (int r = d.r0 - 1; r <= d.r1 + 1; r++) {
                    for (int y = d.floorY(); y < d.floorY() + d.h; y++) {
                        BlockPos bp = world(p, ForestDefs.dx(d.face, r, l), y, ForestDefs.dz(d.face, r, l));
                        BlockState s = this.level.getBlockState(bp);
                        if (s.getBlock() instanceof CrossCollisionBlock || s.getBlock() instanceof WallBlock) {
                            BlockState n = Block.updateFromNeighbourShapes(s, this.level, bp);
                            if (n != s) {
                                this.level.setBlock(bp, n, FLAGS);
                            }
                        }
                    }
                }
            }
        }
    }

    // local cell (anchor relative x / z, y relative to the base) -> world pos, turned like the placement
    private BlockPos world(Placement p, int lx, int ry, int lz) {
        int[] r = ForestDefs.rotate(lx, lz, p.rot);
        return new BlockPos(p.x + r[0], p.y + ry, p.z + r[1]);
    }
}
