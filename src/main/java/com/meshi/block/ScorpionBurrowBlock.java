package com.meshi.block;

import com.meshi.entity.HugeScorpionEntity;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModEntities;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// flat hole on teh SIDE of a wall (like a button). the side you click = side the scorpion comes out of
// player within TRIGGER_RANGE on the open side = scorpion climbs out if theres room, then burrow rests for REARM_TICKS
// scorpion starts INSIDE the wall so walls need to be 2+ blocks thick behind the hole
// once a scorpion lives here (hiding inside) the burrow wont spawn another one, the scorpion itself pops out for prey
@SuppressWarnings("deprecation")
public class ScorpionBurrowBlock extends Block {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ARMED = BooleanProperty.create("armed");

    public static final double TRIGGER_RANGE = 5.0D;
    // 20 ticks = 1 sec, 0 = never again
    public static final int REARM_TICKS = 20 * 60 * 10;
    public static final double FOOD_LURE_RANGE = 7.0D;
    private static final int CHECK_INTERVAL = 10;

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


    // side you click is the side it faces, only the 4 sides (no top/bottom)
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis() == Direction.Axis.Y) {
            return null;
        }
        BlockState state = this.defaultBlockState().setValue(FACING, face).setValue(ARMED, true);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

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


    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!level.isClientSide && !oldState.is(this)) {
            level.scheduleTick(pos, this, CHECK_INTERVAL);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(ARMED)) {
            level.setBlock(pos, state.setValue(ARMED, true), 3);
            level.scheduleTick(pos, this, CHECK_INTERVAL);
            return;
        }

        Direction dir = state.getValue(FACING);
        Player player = level.getNearestPlayer(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, TRIGGER_RANGE,
                (Entity e) -> EntitySelector.NO_SPECTATORS.test(e) && isOnOpenSide(pos, dir, e));

        boolean lured = player == null && foodNearby(level, pos, FOOD_LURE_RANGE);
        if ((player != null || lured) && !hasResident(level, pos) && spawnScorpion(level, pos, dir)) {
            level.setBlock(pos, state.setValue(ARMED, false), 3);
            if (REARM_TICKS > 0) {
                level.scheduleTick(pos, this, REARM_TICKS);
            }
        } else {
            level.scheduleTick(pos, this, CHECK_INTERVAL);
        }
    }

    // Player BEHIND the wall dosnt count
    private static boolean isOnOpenSide(BlockPos pos, Direction dir, Entity entity) {
        double dx = entity.getX() - (pos.getX() + 0.5D);
        double dz = entity.getZ() - (pos.getZ() + 0.5D);
        return dx * dir.getStepX() + dz * dir.getStepZ() > -0.5D;
    }

    // spawns the scorpion inside the wall n it climbs out, returns false if theres no room
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


    // is a scorpion hiding in this burrow right now?
    private static boolean hasResident(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(HugeScorpionEntity.class, new AABB(pos).inflate(3.0D),
                s -> s.isHidden() && pos.equals(s.getHiddenBurrow())).isEmpty();
    }

    // what makes a hiding scorpion pop out: players (not creative), hostile mobs, cows sheep n pigs
    public static boolean isPrey(LivingEntity entity) {
        if (!entity.isAlive()) {
            return false;
        }
        if (entity instanceof Player player) {
            return !player.isSpectator() && !player.isCreative();
        }
        if (entity instanceof HugeScorpionEntity || entity instanceof WalkingMushroomEntity) {
            return false;
        }
        return entity instanceof Enemy || entity instanceof Cow || entity instanceof Sheep || entity instanceof Pig;
    }

    // prey on the open side of the burrow within range
    public static boolean preyNear(ServerLevel level, BlockPos pos, double range) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScorpionBurrowBlock)) {
            return false;
        }
        Direction dir = state.getValue(FACING);
        AABB box = new AABB(pos).inflate(range, 3.0D, range);
        return !level.getEntitiesOfClass(LivingEntity.class, box, e -> isPrey(e) && isOnOpenSide(pos, dir, e)).isEmpty();
    }

    public static boolean isFood(ItemEntity item) {
        return item.isAlive() && item.getItem().has(DataComponents.FOOD);
    }

    public static boolean foodNearby(ServerLevel level, BlockPos pos, double range) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ScorpionBurrowBlock)) {
            return false;
        }
        Direction dir = state.getValue(FACING);
        AABB box = new AABB(pos).inflate(range, 3.0D, range);
        return !level.getEntitiesOfClass(ItemEntity.class, box, e -> isFood(e) && isOnOpenSide(pos, dir, e)).isEmpty();
    }

    public static Vec3 planeOf(BlockPos pos, Direction dir) {
        return new Vec3(pos.getX() + 0.5D - dir.getStepX() * 0.5D, pos.getY(), pos.getZ() + 0.5D - dir.getStepZ() * 0.5D);
    }

    public static Direction facingOf(ServerLevel level, BlockPos pos, Direction fallback) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof ScorpionBurrowBlock ? state.getValue(FACING) : fallback;
    }

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

    public static ParticleOptions chipsFor(ServerLevel level, BlockPos pos, Direction dir) {
        return new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(pos.relative(dir.getOpposite())));
    }

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

    public static BlockPos pickOtherBurrow(ServerLevel level, BlockPos from, double range, RandomSource random) {
        List<BlockPos> burrows = findBurrows(level, from, range, 12);
        burrows.remove(from);
        return burrows.isEmpty() ? null : burrows.get(random.nextInt(burrows.size()));
    }

    // checkRoom = make sure theres space infront of the wall first, if not returns false and scorpion stays put
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

        scorpion.moveTo(plane.x - dir.getStepX() * HugeScorpionEntity.EMERGE_START_INSIDE, plane.y,
                plane.z - dir.getStepZ() * HugeScorpionEntity.EMERGE_START_INSIDE, yaw, 0.0F);
        scorpion.setYBodyRot(yaw);
        scorpion.setYHeadRot(yaw);
        scorpion.yBodyRotO = yaw;
        scorpion.yHeadRotO = yaw;
        scorpion.setHome(pos);
        scorpion.startEmerging(plane, chipsFor(level, pos, dir));
        return true;
    }
}
