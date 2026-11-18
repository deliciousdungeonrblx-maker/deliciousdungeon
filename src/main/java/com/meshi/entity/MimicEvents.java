package com.meshi.entity;

import com.meshi.util.Fx;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

// arrows (n any other projectile) dont hurt a hidden mimic, they just thunk into it like a chest
public class MimicEvents {
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof MimicEntity mimic)
                || !(mimic.isHidden() || mimic.isPopping())) {
            return;
        }
        event.setCanceled(true);
        if (event.getProjectile().level() instanceof ServerLevel server && !event.getProjectile().isRemoved()) {
            Vec3 pos = hit.getLocation();
            server.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                    pos.x, pos.y, pos.z, 8, 0.1D, 0.1D, 0.1D, 0.03D);
            Fx.sound(server, pos.x, pos.y, pos.z, SoundEvents.ARROW_HIT, 1.0F, 1.0F);
            event.getProjectile().discard();
        }
    }
}
