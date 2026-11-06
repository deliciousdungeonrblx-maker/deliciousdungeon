package com.meshi.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

// venom drains hp like poison but stops at half a heart, it never kills
// sting also gives weakness + slowness (see HugeScorpionEntity)
public class ScorpionVenomEffect extends MobEffect {
    // ticks between bites (10 = twice a sec)
    private static final int INTERVAL = 10;
    private static final float DAMAGE = 1.0F;

    public ScorpionVenomEffect() {
        super(MobEffectCategory.HARMFUL, 0x8BCF3F);
    }

    @Override
    public boolean applyEffectTick(LivingEntity entity, int amplifier) {
        // never below 1 HP
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
