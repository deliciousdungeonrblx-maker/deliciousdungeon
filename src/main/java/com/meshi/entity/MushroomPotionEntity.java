package com.meshi.entity;

import com.meshi.registry.ModEntities;
import com.meshi.registry.ModItems;
import com.meshi.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// thrown mushroom potion. wherever it lands a walking mushroom pops out (no potion effects)
public class MushroomPotionEntity extends ThrowableItemProjectile {
    public MushroomPotionEntity(EntityType<? extends MushroomPotionEntity> type, Level level) {
        super(type, level);
    }

    public MushroomPotionEntity(Level level, LivingEntity owner) {
        super(ModEntities.MUSHROOM_POTION.get(), owner, level);
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.MUSHROOM_POTION.get();
    }

    // same arc as a vanilla splash potion
    @Override
    protected double getDefaultGravity() {
        return 0.05D;
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        Vec3 at = result.getLocation();
        // splash bubbles in mushroom red (same event as a vanilla potion breaking)
        server.levelEvent(2002, BlockPos.containing(at), 0xC8302F);

        WalkingMushroomEntity m = ModEntities.WALKING_MUSHROOM.get().create(server);
        if (m != null) {
            // if it landed inside a block push it up a bit till theres room
            double y = at.y;
            for (int i = 0; i < 4; i++) {
                m.moveTo(at.x, y, at.z, this.random.nextFloat() * 360.0F, 0.0F);
                if (server.noCollision(m)) {
                    break;
                }
                y += 0.5D;
            }
            m.finalizeSpawn(server, server.getCurrentDifficultyAt(m.blockPosition()), MobSpawnType.MOB_SUMMONED, null);
            server.addFreshEntity(m);
            server.playSound(null, m.getX(), m.getY(), m.getZ(), ModSounds.MUSHROOM_POP.get(), SoundSource.NEUTRAL, 1.0F, 1.0F);
            server.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.1D, m.getZ(), 8, 0.2D, 0.05D, 0.2D, 0.02D);
        }
        this.discard();
    }
}
