package com.meshi.util;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;

/** Small helpers for the brown dirt particles and sounds used by the golem and the mound. */
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

    public static void sound(ServerLevel level, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, x, y, z, sound, SoundSource.NEUTRAL, volume, pitch);
    }
}
