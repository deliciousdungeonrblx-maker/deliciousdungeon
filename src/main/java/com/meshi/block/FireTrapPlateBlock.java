package com.meshi.block;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.AABB;

// looks like mud bricks. burns (magma look) while something stands on it, goes out when its empty again
public class FireTrapPlateBlock extends Block {
    public static final BooleanProperty LIT = BlockStateProperties.LIT;
    private static final int CHECK_TICKS = 10;

    public FireTrapPlateBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT);
    }

    // used by the random plate too (turns that block into a burning fire plate)
    public static void ignite(ServerLevel level, BlockPos pos) {
        Block block = com.meshi.registry.ModBlocks.FIRE_TRAP_PLATE.get();
        level.setBlock(pos, block.defaultBlockState().setValue(LIT, true), 3);
        level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.9F, 0.7F);
        level.scheduleTick(pos, block, 5);
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (level.isClientSide || !(level instanceof ServerLevel server) || state.getValue(LIT)) {
            return;
        }
        if (!(entity instanceof LivingEntity living) || living.isSpectator() || living.fireImmune()) {
            return;
        }
        ignite(server, pos);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!state.getValue(LIT)) {
            return;
        }
        AABB top = new AABB(pos.getX(), pos.getY() + 1.0D, pos.getZ(), pos.getX() + 1.0D, pos.getY() + 1.2D, pos.getZ() + 1.0D);
        List<LivingEntity> on = level.getEntitiesOfClass(LivingEntity.class, top, e -> !e.isSpectator() && !e.fireImmune());
        if (on.isEmpty()) {
            level.setBlock(pos, state.setValue(LIT, false), 3);
            level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.0F);
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D, 6, 0.3D, 0.1D, 0.3D, 0.01D);
            return;
        }
        for (LivingEntity e : on) {
            e.hurt(level.damageSources().inFire(), 1.0F);
            e.setRemainingFireTicks(Math.max(e.getRemainingFireTicks(), 80));
        }
        level.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, 8, 0.3D, 0.05D, 0.3D, 0.02D);
        level.playSound(null, pos, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 0.7F, 1.0F);
        level.scheduleTick(pos, this, CHECK_TICKS);
    }
}
