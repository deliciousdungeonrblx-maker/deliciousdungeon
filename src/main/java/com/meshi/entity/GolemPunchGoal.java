package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Chase the target, wind up, then punch. The "attack" animation is 1 second long and its arms come
 * down at about 0.5 seconds, so the damage lands HIT_DELAY ticks after the animation starts.
 */
public class GolemPunchGoal extends Goal {
    /** Ticks from the start of the animation to the moment the hit lands (0.5 s). */
    private static final int HIT_DELAY = 10;
    /** Ticks between the start of one punch and the start of the next (1.5 s). */
    private static final int ATTACK_COOLDOWN = 30;

    private final EarthGolemEntity golem;
    private final double speed;

    private int windup = -1;   // -1 = not punching, otherwise ticks left until the hit lands
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
        windup = -1;
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

        // Mid-punch: stand still until the hit lands.
        if (windup >= 0) {
            golem.getNavigation().stop();
            if (windup > 0) {
                windup--;
            } else {
                // Allow a little slack so a player who steps back a bit still gets hit.
                if (golem.distanceToSqr(target) <= reachSqr(target) * 1.5D) {
                    golem.doHurtTarget(target);
                }
                windup = -1;
            }
            return;
        }

        // Chase.
        if (--repath <= 0) {
            repath = 4 + golem.getRandom().nextInt(7);
            golem.getNavigation().moveTo(target, speed);
        }

        // Start a punch.
        if (cooldown <= 0
                && golem.distanceToSqr(target) <= reachSqr(target)
                && golem.getSensing().hasLineOfSight(target)) {
            windup = HIT_DELAY;
            cooldown = ATTACK_COOLDOWN;
            golem.getNavigation().stop();
            golem.triggerAnim("attack", "attack");
        }
    }

    private double reachSqr(LivingEntity target) {
        double reach = golem.getBbWidth() * 2.0D;
        return reach * reach + target.getBbWidth();
    }
}
