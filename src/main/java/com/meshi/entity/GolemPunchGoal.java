package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Chase the target and punch it. The damage and knockback land the instant the punch animation starts.
 * The dirt puff and thud are timed to the moment the arms meet (0.5 s into the 1 s animation).
 *
 * Want the damage to land when the arms meet instead? Put the hit in the "impact" branch below
 * (where impactEffects() is called) instead of next to triggerAnim().
 */
public class GolemPunchGoal extends Goal {
    /** The golem stands still while the 1 second punch animation plays. */
    private static final int SWING_TICKS = 20;
    /** Ticks between the start of one punch and the start of the next (1.5 s). */
    private static final int ATTACK_COOLDOWN = 30;
    /** Ticks after the animation starts until the arms meet and the dust flies (0.5 s). */
    private static final int IMPACT_DELAY = 10;

    private final EarthGolemEntity golem;
    private final double speed;

    private int swing = 0;      // ticks left of standing still during the punch
    private int impact = -1;    // ticks until the arms meet, -1 = nothing pending
    private int cooldown = 0;
    private int repath = 0;

    public GolemPunchGoal(EarthGolemEntity golem, double speed) {
        this.golem = golem;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = golem.getTarget();
        return target != null && target.isAlive() && !golem.isRising();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        golem.setAggressive(true);
        repath = 0;
    }

    @Override
    public void stop() {
        golem.setAggressive(false);
        golem.getNavigation().stop();
        swing = 0;
        impact = -1;
    }

    @Override
    public void tick() {
        LivingEntity target = golem.getTarget();
        if (target == null) {
            return;
        }

        golem.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (cooldown > 0) {
            cooldown--;
        }

        // Dust and thud when the arms meet.
        if (impact >= 0) {
            if (impact == 0) {
                golem.impactEffects();
                impact = -1;
            } else {
                impact--;
            }
        }

        // Mid-punch: stand still until the animation is over.
        if (swing > 0) {
            swing--;
            golem.getNavigation().stop();
            return;
        }

        // Chase.
        if (--repath <= 0) {
            repath = 4 + golem.getRandom().nextInt(7);
            golem.getNavigation().moveTo(target, speed);
        }

        // Punch: animation, damage and knockback all start now.
        if (cooldown <= 0
                && golem.distanceToSqr(target) <= reachSqr(target)
                && golem.getSensing().hasLineOfSight(target)) {
            swing = SWING_TICKS;
            cooldown = ATTACK_COOLDOWN;
            impact = IMPACT_DELAY;
            golem.getNavigation().stop();
            golem.triggerAnim("attack", "attack");
            golem.attackStartEffects();
            golem.doHurtTarget(target);
        }
    }

    private double reachSqr(LivingEntity target) {
        double reach = golem.getBbWidth() * 2.0D;
        return reach * reach + target.getBbWidth();
    }
}
