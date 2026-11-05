package com.meshi.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * Scorpion Venom: drains health like Poison does, but never finishes you off. It stops at half a
 * heart, so a stung player is left hanging on at 1 HP until the venom wears off (or they drink milk).
 *
 * The sting also gives Weakness and Slowness, see HugeScorpionEntity.
 */
public class ScorpionVenomEffect extends MobEffect {
    /** Ticks between two bites of venom (10 = twice a second). */
    private static final int INTERVAL = 10;
    /** Damage per bite (1.0 = half a heart). */
    private static final float DAMAGE = 1.0F;

    public ScorpionVenomEffect() {
        // The colour of the swirl particles around a stung creature (sickly green).
        super(MobEffectCategory.HARMFUL, 0x8BCF3F);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // Never below 1 HP: the venom only ever leaves you hanging on, it does not kill.
        if (entity.getHealth() > 1.0F) {
            entity.hurt(entity.damageSources().magic(), Math.min(DAMAGE, entity.getHealth() - 1.0F));
        }
        return true;
    }

    @Override
    public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
        return duration % INTERVAL == 0;
    }
}
