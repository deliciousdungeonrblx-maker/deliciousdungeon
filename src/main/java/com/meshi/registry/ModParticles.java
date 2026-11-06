package com.meshi.registry;

import com.meshi.Meshi;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Meshi.MODID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> ZZZ =
            PARTICLE_TYPES.register("zzz", () -> new SimpleParticleType(false));
}
