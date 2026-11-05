package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Chase the target and sting it. The attack animation is 0.5 s long and the stinger bottoms out at
 * 0.25 s, so the damage and the venom land 5 ticks after the animation starts.
 */
public class ScorpionStingGoal extends Goal {
    /** The scorpion stands still while the 0.5 second sting animation plays. */
    private static final int SWING_TICKS = 10;
    /** Ticks until the stinger lands (0.25 s into the animation). */
    private static final int HIT_DELAY = 5;
    /** Ticks between the start of one sting and the start of the next (1.25 s). */
    private static final int ATTACK_COOLDOWN = 25;

    private final HugeScorpionEntity scorpion;
    private final double speed;

    private int swing = 0;      // ticks left of standing still during the sting
    private int hitIn = -1;     // ticks until the stinger lands, -1 = nothing pending
    private int cooldown = 0;
    private int repath = 0;

    public ScorpionStingGoal(HugeScorpionEntity scorpion, double speed) {
        this.scorpion = scorpion;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = scorpion.getTarget();
        return target != null && target.isAlive() && !scorpion.isEmerging();
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
        scorpion.setAggressive(true);
        repath = 0;
    }

    @Override
    public void stop() {
        scorpion.setAggressive(false);
        scorpion.getNavigation().stop();
        swing = 0;
        hitIn = -1;
    }

    @Override
    public void tick() {
        LivingEntity target = scorpion.getTarget();
        if (target == null) {
            return;
        }

        scorpion.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (cooldown > 0) {
            cooldown--;
        }

        // The stinger lands: damage and venom, if the target is still within reach.
        if (hitIn >= 0) {
            if (hitIn == 0) {
                if (target.isAlive() && scorpion.inStingRange(target, 0.6D)) {
                    scorpion.doHurtTarget(target);
                }
                hitIn = -1;
            } else {
                hitIn--;
            }
        }

        // Mid-sting: stand still until the animation is over.
        if (swing > 0) {
            swing--;
            scorpion.getNavigation().stop();
            return;
        }

        // Chase.
        if (--repath <= 0) {
            repath = 4 + scorpion.getRandom().nextInt(7);
            scorpion.getNavigation().moveTo(target, speed);
        }

        // Start a sting.
        if (cooldown <= 0
                && scorpion.inStingRange(target, 0.0D)
                && scorpion.getSensing().hasLineOfSight(target)) {
            swing = SWING_TICKS;
            cooldown = ATTACK_COOLDOWN;
            hitIn = HIT_DELAY;
            scorpion.getNavigation().stop();
            scorpion.triggerAnim("attack", "attack");
            scorpion.attackStartEffects();
        }
    }
}
