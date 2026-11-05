package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.effect.ScorpionVenomEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEffects {
    public static final DeferredRegister<MobEffect> MOB_EFFECTS =
            DeferredRegister.create(Registries.MOB_EFFECT, Meshi.MODID);

    /** Test it with: /effect give @s meshi:scorpion_venom 20 0 */
    public static final DeferredHolder<MobEffect, ScorpionVenomEffect> SCORPION_VENOM =
            MOB_EFFECTS.register("scorpion_venom", ScorpionVenomEffect::new);
}
