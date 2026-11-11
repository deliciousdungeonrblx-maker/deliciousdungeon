package com.meshi.forest;

import com.meshi.forest.ForestDefs.Def;
import com.meshi.forest.ForestDefs.Door;
import com.meshi.forest.ForestPlan.Placement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// edit plots of floor 2: one per part, far away from the map (x / z 20000+), laid out in rows of 6.
// you build inside the box (gold blocks mark its corners), /meshi forest part save keeps it
public final class ForestPlots {
    private ForestPlots() {
    }

    public static final int PLOT_X = 20000;
    public static final int PLOT_Z = 20000;
    public static final int PLOT_Y = 20;
    public static final int STEP = 64;
    public static final int COLS = 6;
    private static final long PLOT_SEED = 12345L;

    // lowest corner of the box
    public static BlockPos origin(Def def) {
        int i = ForestDefs.indexOf(def.key);
        return new BlockPos(PLOT_X + (i % COLS) * STEP, PLOT_Y, PLOT_Z + (i / COLS) * STEP);
    }

    // where the player lands, on a little platform next to the box
    public static BlockPos spawn(Def def) {
        return origin(def).offset(-4, 1, -4);
    }

    // which plot is this position in (null = none)
    public static Def at(BlockPos p) {
        for (Def d : ForestDefs.ALL) {
            BlockPos o = origin(d);
            if (p.getX() >= o.getX() - 8 && p.getX() <= o.getX() + d.sx + 7
                    && p.getZ() >= o.getZ() - 8 && p.getZ() <= o.getZ() + d.sz + 7
                    && p.getY() >= o.getY() - 3 && p.getY() <= o.getY() + d.sy + 8) {
                return d;
            }
        }
        return null;
    }

    // little platform to stand on + gold blocks on the 8 corners of the box (just outside it)
    public static void decorate(ServerLevel level, Def def) {
        BlockPos o = origin(def);
        BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
        for (int x = -5; x <= -3; x++) {
            for (int z = -5; z <= -3; z++) {
                level.setBlock(o.offset(x, 0, z), floor, Block.UPDATE_CLIENTS);
            }
        }
        BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
        for (int cx : new int[] {-1, def.sx}) {
            for (int cz : new int[] {-1, def.sz}) {
                for (int cy : new int[] {-1, def.sy}) {
                    level.setBlock(o.offset(cx, cy, cz), gold, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // builds the plot job: clears the box, then puts the saved part (or the generated look) in it
    public static ForestBuilder job(ServerLevel level, Def def, boolean useSaved) {
        BlockPos o = origin(def);
        List<int[]> box = new ArrayList<>();
        box.add(new int[] {o.getX(), o.getY(), o.getZ(), o.getX() + def.sx - 1, o.getY() + def.sy - 1, o.getZ() + def.sz - 1});
        List<Placement> one = new ArrayList<>();
        one.add(new Placement(def, o.getX() + def.ax, o.getY() + def.ay, o.getZ() + def.az, 0, PLOT_SEED, new int[0]));
        return new ForestBuilder(level, box, one, useSaved, null);
    }

    // doorways / gaps you closed (center of every door of the part, looked at with the part facing east)
    public static List<String> blockedDoors(ServerLevel level, Def def) {
        List<String> out = new ArrayList<>();
        BlockPos o = origin(def);
        for (Door d : def.doors) {
            int x = def.ax + ForestDefs.dx(d.face, d.sampleR, 0);
            int z = def.az + ForestDefs.dz(d.face, d.sampleR, 0);
            int y = def.ay + d.floorY() + (d.fallback == com.meshi.forest.PartGrid.Mat.RAIL ? 0 : 1);
            BlockPos c = o.offset(x, y, z);
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) {
                out.add(ForestDefs.FACE_NAME[d.face] + " side, level " + (d.level + 1));
            }
        }
        return out;
    }
}
