package com.meshi.block;

import com.meshi.dungeon.DungeonBuilder;
import com.meshi.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// looks like mud bricks, goes off once then turns into plain brick
// SLIME = slime from the roof, ARROWS = arrows from the roof, RANDOM = arrows / fire arrows / burning plate (no mobs)
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
        int roll = this.kind == Kind.RANDOM ? server.random.nextInt(3) : -1;
        server.playSound(null, pos, SoundEvents.STONE_PRESSURE_PLATE_CLICK_ON, SoundSource.BLOCKS, 0.8F, 0.6F);

        // plate that catches fire stays a (fire) plate, everything else turns into normal brick first so it cant fire twice
        if (roll == 2) {
            FireTrapPlateBlock.ignite(server, pos);
            return;
        }
        server.setBlock(pos, Blocks.MUD_BRICKS.defaultBlockState(), 3);
        switch (this.kind) {
            case SLIME -> slimeDrop(server, pos, 1);
            case ARROWS -> arrowVolley(server, pos, player, false);
            default -> arrowVolley(server, pos, player, roll == 1);
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

    // arrows fall from the roof onto the player, 4 / 6 / 8 by floor
    private static void arrowVolley(ServerLevel level, BlockPos pos, ServerPlayer player, boolean fire) {
        int depth = DungeonBuilder.floorOfY(pos.getY());
        int count = 4 + depth * 2;
        double top = roofY(level, pos) - 0.5D;
        Vec3 lead = player.getDeltaMovement();
        for (int i = 0; i < count; i++) {
            Entity e = EntityType.ARROW.create(level);
            if (!(e instanceof AbstractArrow arrow)) {
                continue;
            }
            double x = player.getX() + lead.x * 4.0D + (level.random.nextDouble() - 0.5D) * 1.4D;
            double z = player.getZ() + lead.z * 4.0D + (level.random.nextDouble() - 0.5D) * 1.4D;
            arrow.setPos(x, top - i * 0.9D, z);
            arrow.setDeltaMovement(0.0D, -1.3D, 0.0D);
            arrow.setBaseDamage(1.5D + depth * 0.25D);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            if (fire) {
                arrow.setRemainingFireTicks(400);
            }
            level.addFreshEntity(arrow);
        }
        level.playSound(null, player.getX(), top, player.getZ(), SoundEvents.DISPENSER_LAUNCH, SoundSource.BLOCKS, 1.2F, 0.8F);
        if (fire) {
            level.playSound(null, player.getX(), top, player.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, 0.9F);
        }
    }
}
