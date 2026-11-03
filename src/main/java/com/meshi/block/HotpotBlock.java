package com.meshi.block;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The shared hotpot. Placed on the ground; each player right-clicks it with a bowl to take a serving.
 * It has 4 servings. When they run out an empty pot stays; right-click it to pick it up (drops a bowl).
 *
 * Works like Farmer's Delight's feast blocks (roast chicken, shepherd's pie), but has no code dependency on
 * Farmer's Delight, so the mod still builds and loads without it.
 */
@SuppressWarnings("deprecation")
public class HotpotBlock extends Block {
    public static final int MAX_SERVINGS = 4;
    public static final IntegerProperty SERVINGS = IntegerProperty.create("servings", 0, MAX_SERVINGS);

    // Roughly the pot plus its two handles: x 0..16, z 1..15, up to 9/16 high.
    private static final VoxelShape SHAPE = Block.box(0.0D, 0.0D, 1.0D, 16.0D, 9.0D, 15.0D);

    private final Supplier<Item> servingItem;

    public HotpotBlock(Properties properties, Supplier<Item> servingItem) {
        super(properties);
        this.servingItem = servingItem;
        this.registerDefaultState(this.stateDefinition.any().setValue(SERVINGS, MAX_SERVINGS));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SERVINGS);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public ItemInteractionResult useItemOn(ItemStack heldStack, BlockState state, Level level, BlockPos pos,
                                           Player player, InteractionHand hand, BlockHitResult hit) {
        int servings = state.getValue(SERVINGS);

        // Empty pot: any right-click clears it away (the loot table gives back a bowl).
        if (servings == 0) {
            if (!level.isClientSide) {
                level.playSound(null, pos, SoundEvents.WOOD_BREAK, SoundSource.PLAYERS, 0.8F, 0.8F);
                level.destroyBlock(pos, true);
            }
            return ItemInteractionResult.SUCCESS;
        }

        // Serving needs a normal bowl.
        if (!heldStack.is(Items.BOWL)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.translatable("message.meshi.hotpot.need_bowl"), true);
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(SERVINGS, servings - 1), 3);
            player.awardStat(Stats.ITEM_USED.get(heldStack.getItem()));

            ItemStack serving = new ItemStack(this.servingItem.get());
            if (!player.getAbilities().instabuild) {
                heldStack.shrink(1);
            }
            if (!player.getInventory().add(serving)) {
                player.drop(serving, false);
            }

            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.8F, 1.2F);
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                        pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 3, 0.1, 0.1, 0.1, 0.001D);
            }
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState facingState, LevelAccessor level,
                                  BlockPos currentPos, BlockPos facingPos) {
        return facing == Direction.DOWN && !state.canSurvive(level, currentPos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, facing, facingState, level, currentPos, facingPos);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canSupportRigidBlock(level, pos.below());
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return state.getValue(SERVINGS);
    }

    @Override
    public boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
