package com.meshi.util;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;

/** Small helpers for the dirt particles, sparkles and sounds used by the golem and the mound. */
public final class Fx {
    private Fx() {
    }

    /** The brown "dirt chip" particle (the same one you see when breaking a dirt block). */
    public static BlockParticleOption dirt() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.DIRT.defaultBlockState());
    }

    /** Server-side burst of dirt chips that every nearby player sees. */
    public static void dirtBurst(ServerLevel level, double x, double y, double z, int count, double spread, double speed) {
        level.sendParticles(dirt(), x, y, z, count, spread, spread * 0.4D, spread, speed);
    }

    /** Green magic sparkles (the "happy villager" stars), for growth and crops. */
    public static void sparkle(ServerLevel level, double x, double y, double z, int count, double spread) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, count, spread, spread * 0.6D, spread, 0.0D);
    }

    /** A soft dust cloud. */
    public static void dust(ServerLevel level, double x, double y, double z, int count, double spread) {
        level.sendParticles(ParticleTypes.POOF, x, y, z, count, spread, spread * 0.3D, spread, 0.03D);
    }

    /**
     * A ring of particles that spreads outward along the ground, like a shockwave.
     * Particles are sent with count 0 so that the offsets act as a direction, which makes them fly outward.
     */
    public static void shockwave(ServerLevel level, ParticleOptions particle, double x, double y, double z, double radius) {
        int points = Math.max(16, (int) (radius * 14.0D));
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            level.sendParticles(particle, x + dx * 0.4D, y, z + dz * 0.4D, 0, dx, 0.15D, dz, radius * 0.12D);
        }
    }

    public static void sound(ServerLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, volume, pitch);
    }

    /** Same as sound() but the pitch wobbles a little so repeated sounds do not feel robotic. */
    public static void soundVaried(ServerLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        float wobble = 0.9F + level.random.nextFloat() * 0.2F;
        level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, volume, pitch * wobble);
    }
}
