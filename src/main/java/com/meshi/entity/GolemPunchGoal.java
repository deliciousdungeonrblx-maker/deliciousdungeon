package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

// chase n punch. damage + knockback land the moment the anim starts, dust + thud at 0.5s when the arms meet
// want the dmg on impact instead? move the hit to the impact branch
public class GolemPunchGoal extends Goal {
    private static final int SWING_TICKS = 20;
    // Ticks between punches (1.5 s)
    private static final int ATTACK_COOLDOWN = 30;
    // ticks til teh arms meet (0.5 s)
    private static final int IMPACT_DELAY = 10;

    private final EarthGolemEntity golem;
    private final double speed;

    private int swing = 0;
    private int impact = -1;  // -1 = nothing pending
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
        return target != null && target.isAlive() && !golem.isRising() && golem.canChase(target);
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

        if (impact >= 0) {
            if (impact == 0) {
                golem.impactEffects();
                impact = -1;
            } else {
                impact--;
            }
        }

        if (swing > 0) {
            swing--;
            golem.getNavigation().stop();
            return;
        }

        if (--repath <= 0) {
            repath = 4 + golem.getRandom().nextInt(7);
            golem.getNavigation().moveTo(target, speed);
        }

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
