package com.meshi.forest;

import com.meshi.forest.ForestDefs.Def;
import com.meshi.forest.PartGrid.Mat;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

// where the blocks of a part come from: your saved version if there is one, else the generated look
final class ForestStamp {
    private ForestStamp() {
    }

    abstract static class Source {
        final Def def;

        Source(Def def) {
            this.def = def;
        }

        // grid coords, null = nothing / air
        abstract BlockState at(int x, int y, int z);
    }

    private static final class Generated extends Source {
        private final PartGrid grid;

        Generated(Def def, long seed) {
            super(def);
            this.grid = ForestShapes.generate(def, seed);
        }

        @Override
        BlockState at(int x, int y, int z) {
            Mat m = this.grid.at(x, y, z);
            if (m == null || m == Mat.AIR) {
                return null;
            }
            BlockState st = state(m);
            if (m == Mat.RAIL && st.hasProperty(BlockStateProperties.NORTH)) {
                st = st.setValue(BlockStateProperties.NORTH, this.grid.at(x, y, z - 1) == Mat.RAIL)
                        .setValue(BlockStateProperties.SOUTH, this.grid.at(x, y, z + 1) == Mat.RAIL)
                        .setValue(BlockStateProperties.EAST, this.grid.at(x + 1, y, z) == Mat.RAIL)
                        .setValue(BlockStateProperties.WEST, this.grid.at(x - 1, y, z) == Mat.RAIL);
            }
            return st;
        }
    }

    private static final class Saved extends Source {
        private final PartStore.Template t;

        Saved(Def def, PartStore.Template t) {
            super(def);
            this.t = t;
        }

        @Override
        BlockState at(int x, int y, int z) {
            return this.t.at(x, y, z);
        }
    }

    // saved part wins, else the generated one (its own seed per placement, so unedited parts vary)
    static Source source(Def def, long seed, PartStore store, boolean useSaved) {
        if (useSaved && store.has(def.key)) {
            return new Saved(def, store.template(def.key));
        }
        return new Generated(def, seed);
    }

    // ---- default materials
    private static final Map<Mat, BlockState> TABLE = new EnumMap<>(Mat.class);

    private static BlockState axis(Block b, Direction.Axis a) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(BlockStateProperties.AXIS) ? st.setValue(BlockStateProperties.AXIS, a) : st;
    }

    private static BlockState leaves(Block b) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(LeavesBlock.PERSISTENT) ? st.setValue(LeavesBlock.PERSISTENT, true) : st;
    }

    private static BlockState ladder(Direction facing) {
        BlockState st = Blocks.LADDER.defaultBlockState();
        return st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing) : st;
    }

    static {
        TABLE.put(Mat.AIR, Blocks.AIR.defaultBlockState());
        TABLE.put(Mat.TRUNK, axis(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
        TABLE.put(Mat.LOG_X, axis(Blocks.DARK_OAK_LOG, Direction.Axis.X));
        TABLE.put(Mat.LOG_Z, axis(Blocks.DARK_OAK_LOG, Direction.Axis.Z));
        TABLE.put(Mat.BEAM, axis(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y));
        TABLE.put(Mat.STONE, Blocks.STONE_BRICKS.defaultBlockState());
        TABLE.put(Mat.MSTONE, Blocks.MOSSY_STONE_BRICKS.defaultBlockState());
        TABLE.put(Mat.BAND, Blocks.POLISHED_DIORITE.defaultBlockState());
        TABLE.put(Mat.PLANK, Blocks.SPRUCE_PLANKS.defaultBlockState());
        TABLE.put(Mat.RAIL, Blocks.SPRUCE_FENCE.defaultBlockState());
        TABLE.put(Mat.MOSS, Blocks.MOSS_BLOCK.defaultBlockState());
        TABLE.put(Mat.LEAF, leaves(Blocks.OAK_LEAVES));
        TABLE.put(Mat.LEAF2, leaves(Blocks.SPRUCE_LEAVES));
        TABLE.put(Mat.LEAF3, leaves(Blocks.AZALEA_LEAVES));
        TABLE.put(Mat.VINE, Blocks.CAVE_VINES_PLANT.defaultBlockState());
        TABLE.put(Mat.NEST, Blocks.HAY_BLOCK.defaultBlockState());
        TABLE.put(Mat.EGG, Blocks.CALCITE.defaultBlockState());
        TABLE.put(Mat.LANTERN, Blocks.SOUL_LANTERN.defaultBlockState());
        TABLE.put(Mat.LADDER_N, ladder(Direction.NORTH));
        TABLE.put(Mat.LADDER_S, ladder(Direction.SOUTH));
        TABLE.put(Mat.LADDER_E, ladder(Direction.EAST));
        TABLE.put(Mat.LADDER_W, ladder(Direction.WEST));
    }

    static BlockState state(Mat m) {
        return TABLE.get(m);
    }
}
