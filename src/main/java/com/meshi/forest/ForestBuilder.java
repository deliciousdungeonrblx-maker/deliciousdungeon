package com.meshi.forest;

import com.meshi.forest.ForestGen.Mat;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

// places the generated forest a few thousand blocks per tick (clears the old one first when regenerating)
public final class ForestBuilder {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int CLEAR_FLAGS = FLAGS | Block.UPDATE_SUPPRESS_DROPS;

    private final ServerLevel level;
    private final ForestGen gen;
    private final long[] clearKeys;
    private final long[] keys;
    private final Map<Mat, BlockState> states = new EnumMap<>(Mat.class);
    private final int perTick;
    private int ci = 0;
    private int ki = 0;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public ForestBuilder(ServerLevel level, ForestGen clearGen, ForestGen gen, ForestSettings s) {
        this.level = level;
        this.gen = gen;
        this.clearKeys = clearGen == null ? new long[0] : clearGen.sortedKeys();
        this.keys = gen.sortedKeys();
        this.perTick = Math.max(500, Math.min(100000, s.getInt("blocks_per_tick")));
        fill(s);
    }

    public int remaining() {
        return (this.clearKeys.length - this.ci) + (this.keys.length - this.ki);
    }

    public int total() {
        return this.clearKeys.length + this.keys.length;
    }

    // true when finished
    public boolean tick() {
        int budget = this.perTick;
        while (budget > 0 && this.ci < this.clearKeys.length) {
            long k = this.clearKeys[this.ci++];
            this.pos.set(ForestGen.ux(k), ForestGen.uy(k), ForestGen.uz(k));
            if (!this.level.getBlockState(this.pos).isAir()) {
                this.level.setBlock(this.pos, Blocks.AIR.defaultBlockState(), CLEAR_FLAGS);
            }
            budget--;
        }
        while (budget > 0 && this.ci >= this.clearKeys.length && this.ki < this.keys.length) {
            long k = this.keys[this.ki++];
            Mat m = this.gen.at(k);
            if (m == null || m == Mat.AIR) {
                continue;
            }
            int x = ForestGen.ux(k), y = ForestGen.uy(k), z = ForestGen.uz(k);
            this.pos.set(x, y, z);
            BlockState st = this.states.get(m);
            if (m == Mat.RAIL && st.hasProperty(BlockStateProperties.NORTH)) {
                st = st.setValue(BlockStateProperties.NORTH, this.gen.at(x, y, z - 1) == Mat.RAIL)
                        .setValue(BlockStateProperties.SOUTH, this.gen.at(x, y, z + 1) == Mat.RAIL)
                        .setValue(BlockStateProperties.EAST, this.gen.at(x + 1, y, z) == Mat.RAIL)
                        .setValue(BlockStateProperties.WEST, this.gen.at(x - 1, y, z) == Mat.RAIL);
            }
            this.level.setBlock(this.pos, st, FLAGS);
            budget--;
        }
        return this.ci >= this.clearKeys.length && this.ki >= this.keys.length;
    }

    // ---- materials from settings
    private static Block block(String id, Block fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id.trim());
        if (rl == null) {
            return fallback;
        }
        return BuiltInRegistries.BLOCK.getOptional(rl).orElse(fallback);
    }

    private static BlockState withAxis(BlockState st, Direction.Axis axis) {
        return st.hasProperty(BlockStateProperties.AXIS) ? st.setValue(BlockStateProperties.AXIS, axis) : st;
    }

    private static BlockState leaves(Block b) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(LeavesBlock.PERSISTENT) ? st.setValue(LeavesBlock.PERSISTENT, true) : st;
    }

    private static BlockState ladder(Block b, Direction facing) {
        BlockState st = b.defaultBlockState();
        return st.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? st.setValue(BlockStateProperties.HORIZONTAL_FACING, facing) : st;
    }

    private void fill(ForestSettings s) {
        BlockState trunk = withAxis(block(s.get("block_trunk"), Blocks.DARK_OAK_LOG).defaultBlockState(), Direction.Axis.Y);
        Block logB = block(s.get("block_log"), Blocks.DARK_OAK_LOG);
        Block ladderB = block(s.get("block_ladder"), Blocks.LADDER);
        this.states.put(Mat.AIR, Blocks.AIR.defaultBlockState());
        this.states.put(Mat.TRUNK, trunk);
        this.states.put(Mat.LOG_X, withAxis(logB.defaultBlockState(), Direction.Axis.X));
        this.states.put(Mat.LOG_Z, withAxis(logB.defaultBlockState(), Direction.Axis.Z));
        this.states.put(Mat.BEAM, withAxis(block(s.get("block_beam"), Blocks.STRIPPED_DARK_OAK_LOG).defaultBlockState(), Direction.Axis.Y));
        this.states.put(Mat.STONE, block(s.get("block_stone"), Blocks.STONE_BRICKS).defaultBlockState());
        this.states.put(Mat.MSTONE, block(s.get("block_mossy"), Blocks.MOSSY_STONE_BRICKS).defaultBlockState());
        this.states.put(Mat.BAND, block(s.get("block_band"), Blocks.POLISHED_DIORITE).defaultBlockState());
        this.states.put(Mat.PLANK, block(s.get("block_plank"), Blocks.SPRUCE_PLANKS).defaultBlockState());
        this.states.put(Mat.RAIL, block(s.get("block_rail"), Blocks.SPRUCE_FENCE).defaultBlockState());
        this.states.put(Mat.MOSS, block(s.get("block_moss"), Blocks.MOSS_BLOCK).defaultBlockState());
        this.states.put(Mat.LEAF, leaves(block(s.get("block_leaf"), Blocks.OAK_LEAVES)));
        this.states.put(Mat.LEAF2, leaves(block(s.get("block_leaf2"), Blocks.SPRUCE_LEAVES)));
        this.states.put(Mat.LEAF3, leaves(block(s.get("block_leaf3"), Blocks.AZALEA_LEAVES)));
        this.states.put(Mat.VINE, block(s.get("block_vine"), Blocks.CAVE_VINES_PLANT).defaultBlockState());
        this.states.put(Mat.NEST, block(s.get("block_nest"), Blocks.HAY_BLOCK).defaultBlockState());
        this.states.put(Mat.EGG, block(s.get("block_egg"), Blocks.CALCITE).defaultBlockState());
        this.states.put(Mat.LANTERN, block(s.get("block_lantern"), Blocks.SOUL_LANTERN).defaultBlockState());
        this.states.put(Mat.LADDER_N, ladder(ladderB, Direction.NORTH));
        this.states.put(Mat.LADDER_S, ladder(ladderB, Direction.SOUTH));
        this.states.put(Mat.LADDER_E, ladder(ladderB, Direction.EAST));
        this.states.put(Mat.LADDER_W, ladder(ladderB, Direction.WEST));
    }
}
