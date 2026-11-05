package com.meshi.block;

import com.meshi.entity.HugeScorpionEntity;
import com.meshi.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A scorpion burrow: a flat hole stuck on the SIDE of a block, like a painting or a button. Click the
 * side of a wall with the item and the hole appears there; the side it faces is where the scorpion
 * comes out. The hole is drawn at the bottom middle of the block face, so the scorpion crawls out at
 * floor level.
 *
 * When a player comes within TRIGGER_RANGE (and is on the open side of the wall) a Huge Scorpion
 * climbs out of the wall, as long as there is room for it. The burrow then rests for REARM_TICKS
 * before it can spawn another one (set REARM_TICKS to 0 for one scorpion per hole, ever).
 *
 * The scorpion starts INSIDE the wall, so walls should be at least 2 blocks thick behind the hole.
 */
@SuppressWarnings("deprecation")
public class ScorpionBurrowBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    /** True while the burrow is ready to spawn a scorpion. */
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");

    /** How close a player has to get, in blocks. */
    public static final double TRIGGER_RANGE = 5.0D;
    /** Ticks before the hole can spawn another scorpion (20 ticks = 1 second). 0 = never again. */
    public static final int REARM_TICKS = 20 * 60 * 10;
    /** Ticks between two checks for a player nearby. */
    private static final int CHECK_INTERVAL = 10;

    // A thin plate (2/16) against the wall, for each side the hole can face.
    private static final VoxelShape SHAPE_NORTH = Block.box(0.0D, 0.0D, 14.0D, 16.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_SOUTH = Block.box(0.0D, 0.0D, 0.0D, 16.0D, 16.0D, 2.0D);
    private static final VoxelShape SHAPE_EAST = Block.box(0.0D, 0.0D, 0.0D, 2.0D, 16.0D, 16.0D);
    private static final VoxelShape SHAPE_WEST = Block.box(14.0D, 0.0D, 0.0D, 16.0D, 16.0D, 16.0D);

    public ScorpionBurrowBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(ARMED, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ARMED);
    }

    // ---------------------------------------------------------------- placing it on a wall

    /** The side you click is the side the hole faces. Only the four sides of a block work (not top or bottom). */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis() == Direction.Axis.Y) {
            return null;
        }
        BlockState state = this.defaultBlockState().setValue(FACING, face).setValue(ARMED, true);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    /** The block behind the hole must have a solid face to stick to. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
        if (direction == state.getValue(FACING).getOpposite() && !state.canSurvive(level, currentPos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SHAPE_SOUTH;
            case EAST -> SHAPE_EAST;
            case WEST -> SHAPE_WEST;
            default -> SHAPE_NORTH;
        };
    }

    // ---------------------------------------------------------------- the ambush

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(this)) {
            level.scheduleTick(pos, this, CHECK_INTERVAL);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ARMED)) {
            // Rested long enough: ready again.
            level.setBlock(pos, state.setValue(ARMED, true), 3);
            level.scheduleTick(pos, this, CHECK_INTERVAL);
            return;
        }

        Direction dir = state.getValue(FACING);
        Player player = level.getNearestPlayer(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, TRIGGER_RANGE,
                (Entity e) -> EntitySelector.NO_SPECTATORS.test(e) && isOnOpenSide(pos, dir, e));

        if (player != null && spawnScorpion(level, pos, dir)) {
            level.setBlock(pos, state.setValue(ARMED, false), 3);
            if (REARM_TICKS > 0) {
                level.scheduleTick(pos, this, REARM_TICKS);
            }
        } else {
            level.scheduleTick(pos, this, CHECK_INTERVAL);
        }
    }

    /** A player standing behind the wall does not count: only the open side. */
    private static boolean isOnOpenSide(BlockPos pos, Direction dir, Entity entity) {
        double dx = entity.getX() - (pos.getX() + 0.5D);
        double dz = entity.getZ() - (pos.getZ() + 0.5D);
        return dx * dir.getStepX() + dz * dir.getStepZ() > -0.5D;
    }

    /**
     * Creates a scorpion inside the wall behind the hole and lets it climb out. Returns false (and
     * spawns nothing) if there is no room for it in front of the wall.
     */
    private boolean spawnScorpion(ServerLevel level, BlockPos pos, Direction dir) {
        HugeScorpionEntity scorpion = ModEntities.HUGE_SCORPION.get().create(level);
        if (scorpion == null) {
            return false;
        }
        if (!placeEmerging(level, pos, level.getBlockState(pos), scorpion, true)) {
            scorpion.discard();
            return false;
        }
        level.addFreshEntity(scorpion);
        return true;
    }

    // ---------------------------------------------------------------- helpers (also used by the scorpion itself)

    /** The middle of the hole on the wall face, at floor level. */
    public static Vec3 planeOf(BlockPos pos, Direction dir) {
        return new Vec3(pos.getX() + 0.5D - dir.getStepX() * 0.5D, pos.getY(), pos.getZ() + 0.5D - dir.getStepZ() * 0.5D);
    }

    /** Which way the burrow at pos faces, or the fallback if there is no burrow there any more. */
    public static Direction facingOf(ServerLevel level, BlockPos pos, Direction fallback) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof ScorpionBurrowBlock ? state.getValue(FACING) : fallback;
    }

    /** Where a scorpion stands in front of the hole, or null if there is no burrow at pos. */
    public static Vec3 frontSpot(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScorpionBurrowBlock)) {
            return null;
        }
        Direction dir = state.getValue(FACING);
        Vec3 plane = planeOf(pos, dir);
        return plane.add(dir.getStepX() * HugeScorpionEntity.EMERGE_END_OUTSIDE, 0.0D,
                dir.getStepZ() * HugeScorpionEntity.EMERGE_END_OUTSIDE);
    }

    /** Wall chips for the dust that comes off a burrow. */
    public static ParticleOptions chipsFor(ServerLevel level, BlockPos pos, Direction dir) {
        return new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(pos.relative(dir.getOpposite())));
    }

    /** Every loaded burrow within rangeXZ blocks sideways and rangeY blocks up or down of center. */
    public static List<BlockPos> findBurrows(ServerLevel level, BlockPos center, double rangeXZ, int rangeY) {
        int r = (int) Math.ceil(rangeXZ);
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-r, -rangeY, -r), center.offset(r, rangeY, r))) {
            if (level.isLoaded(p) && level.getBlockState(p).getBlock() instanceof ScorpionBurrowBlock
                    && p.distSqr(center) <= rangeXZ * rangeXZ + (double) rangeY * rangeY) {
                found.add(p.immutable());
            }
        }
        return found;
    }

    /** A random burrow near "from" other than "from" itself, or null if there is none. */
    public static BlockPos pickOtherBurrow(ServerLevel level, BlockPos from, double range, RandomSource random) {
        List<BlockPos> burrows = findBurrows(level, from, range, 12);
        burrows.remove(from);
        return burrows.isEmpty() ? null : burrows.get(random.nextInt(burrows.size()));
    }

    /**
     * Puts the scorpion inside the wall behind the burrow at pos, facing out, and starts its climb out.
     * With checkRoom it first makes sure there is space in front of the wall, and if not it returns false
     * and leaves the scorpion exactly where it was.
     */
    public static boolean placeEmerging(ServerLevel level, BlockPos pos, BlockState state,
                                        HugeScorpionEntity scorpion, boolean checkRoom) {
        if (!(state.getBlock() instanceof ScorpionBurrowBlock)) {
            return false;
        }
        Direction dir = state.getValue(FACING);
        Vec3 plane = planeOf(pos, dir);
        float yaw = dir.toYRot();

        if (checkRoom) {
            double ox = scorpion.getX();
            double oy = scorpion.getY();
            double oz = scorpion.getZ();
            float oyaw = scorpion.getYRot();
            float opitch = scorpion.getXRot();
            scorpion.moveTo(plane.x + dir.getStepX() * HugeScorpionEntity.EMERGE_END_OUTSIDE, plane.y,
                    plane.z + dir.getStepZ() * HugeScorpionEntity.EMERGE_END_OUTSIDE, yaw, 0.0F);
            boolean free = level.noCollision(scorpion);
            if (!free) {
                scorpion.moveTo(ox, oy, oz, oyaw, opitch);
                return false;
            }
        }

        // It starts inside the wall, facing out.
        scorpion.moveTo(plane.x - dir.getStepX() * HugeScorpionEntity.EMERGE_START_INSIDE, plane.y,
                plane.z - dir.getStepZ() * HugeScorpionEntity.EMERGE_START_INSIDE, yaw, 0.0F);
        scorpion.setYBodyRot(yaw);
        scorpion.setYHeadRot(yaw);
        scorpion.yBodyRotO = yaw;
        scorpion.yHeadRotO = yaw;
        scorpion.startEmerging(plane, chipsFor(level, pos, dir));
        return true;
    }
}
