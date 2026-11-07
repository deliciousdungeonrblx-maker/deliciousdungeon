package com.meshi.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// looks EXACTLY like mud bricks. step on it (stepOn) and the trap goes off once, then its plain mud bricks
// SLIME = one slime drops from the roof, RANDOM = picks a random trap (used in the round rooms)
public class TrapFloorBlock extends Block {
    public enum Kind { SLIME, RANDOM, ARROWS }

    private final Kind kind;

    public TrapFloorBlock(Properties properties, Kind kind) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        if (level.isClientSide || !(level instanceof ServerLevel server) || !(entity instanceof ServerPlayer player)) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        // turn into normal brick first so it cant fire twice
        server.setBlock(pos, Blocks.MUD_BRICKS.defaultBlockState(), 3);
        server.playSound(null, pos, SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON, SoundSource.BLOCKS, 0.8F, 0.6F);

        if (this.kind == Kind.ARROWS) {
            dispenserVolley(server, pos);
            return;
        }
        int roll = this.kind == Kind.SLIME ? 0 : server.random.nextInt(4);
        switch (roll) {
            case 0 -> slimeDrop(server, pos, 1);
            case 1 -> slimeDrop(server, pos, 3);
            case 2 -> arrowRain(server, pos);
            default -> ambush(server, pos);
        }
    }

    // y of the first solid block above the plate (the roof), max 12 up
    private static int roofY(ServerLevel level, BlockPos pos) {
        for (int dy = 2; dy <= 12; dy++) {
            if (!level.getBlockState(pos.above(dy)).getCollisionShape(level, pos.above(dy)).isEmpty()) {
                return pos.getY() + dy;
            }
        }
        return pos.getY() + 6;
    }

    private static void slimeDrop(ServerLevel level, BlockPos pos, int count) {
        int roof = roofY(level, pos);
        for (int i = 0; i < count; i++) {
            Slime slime = EntityType.SLIME.create(level);
            if (slime == null) {
                continue;
            }
            double ox = count == 1 ? 0.0D : (level.random.nextDouble() - 0.5D) * 2.0D;
            double oz = count == 1 ? 0.0D : (level.random.nextDouble() - 0.5D) * 2.0D;
            slime.setSize(2 + level.random.nextInt(2), true);
            // hangs just under the ceiling then falls
            slime.moveTo(pos.getX() + 0.5D + ox, roof - slime.getBbHeight() - 0.05D, pos.getZ() + 0.5D + oz,
                    level.random.nextFloat() * 360.0F, 0.0F);
            slime.setPersistenceRequired();
            level.addFreshEntity(slime);
        }
        level.sendParticles(ParticleTypes.ITEM_SLIME, pos.getX() + 0.5D, roof - 0.5D, pos.getZ() + 0.5D, 25, 0.5D, 0.2D, 0.5D, 0.05D);
        level.playSound(null, pos.getX() + 0.5D, roof - 0.5D, pos.getZ() + 0.5D, SoundEvents.SLIME_SQUISH, SoundSource.HOSTILE, 1.2F, 0.7F);
    }

    private static void arrowRain(ServerLevel level, BlockPos pos) {
        int roof = roofY(level, pos);
        for (int i = 0; i < 8; i++) {
            Entity e = EntityType.ARROW.create(level);
            if (!(e instanceof AbstractArrow arrow)) {
                continue;
            }
            arrow.setPos(pos.getX() + 0.5D + (level.random.nextDouble() - 0.5D) * 3.0D, roof - 0.6D,
                    pos.getZ() + 0.5D + (level.random.nextDouble() - 0.5D) * 3.0D);
            arrow.setDeltaMovement(0.0D, -1.1D, 0.0D);
            arrow.setBaseDamage(2.0D);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            level.addFreshEntity(arrow);
        }
        level.playSound(null, pos, SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 1.0F, 0.8F);
    }

    // arrow gallery: every dispenser in line with the plate (4 directions, 10 blocks) shoots an arrow at it
    private static void dispenserVolley(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            for (int dy = 1; dy <= 2; dy++) {
                for (int d = 1; d <= 10; d++) {
                    BlockPos p = pos.relative(dir, d).above(dy);
                    BlockState st = level.getBlockState(p);
                    if (st.is(Blocks.DISPENSER)) {
                        Entity e = EntityType.ARROW.create(level);
                        if (e instanceof AbstractArrow arrow) {
                            net.minecraft.core.Direction out = dir.getOpposite();
                            arrow.setPos(p.getX() + 0.5D + out.getStepX() * 0.7D, p.getY() + 0.5D, p.getZ() + 0.5D + out.getStepZ() * 0.7D);
                            arrow.setDeltaMovement(out.getStepX() * 1.6D, 0.05D, out.getStepZ() * 1.6D);
                            arrow.setBaseDamage(2.0D);
                            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
                            level.addFreshEntity(arrow);
                        }
                        level.playSound(null, p, SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 1.0F, 1.0F);
                        break;
                    }
                    if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                        break;
                    }
                }
            }
        }
    }

    // 2 zombies or skeletons pop around the plate
    private static void ambush(ServerLevel level, BlockPos pos) {
        EntityType<? extends Mob> type = level.random.nextBoolean() ? EntityType.ZOMBIE : EntityType.SKELETON;
        for (int i = 0; i < 2; i++) {
            Mob mob = type.create(level);
            if (mob == null) {
                continue;
            }
            double ang = level.random.nextDouble() * Math.PI * 2.0D;
            mob.moveTo(pos.getX() + 0.5D + Math.cos(ang) * 2.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D + Math.sin(ang) * 2.5D,
                    level.random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(mob)) {
                mob.discard();
                continue;
            }
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.TRIGGERED, null);
            level.addFreshEntity(mob);
        }
        level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 20, 1.2D, 0.3D, 1.2D, 0.02D);
    }
}
