package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

// chase n bite. attack1 is 0.4167s and the lid is wide open at 0.2083s so the hit lands 4 ticks in, snaps shut at the end
// doesnt need line of sight and falls back to walking straight at the target if theres no path
public class MimicBiteGoal extends Goal {
    private static final int SWING_TICKS = 9;
    private static final int HIT_DELAY = 4;
    private static final int ATTACK_COOLDOWN = 24;

    private final MimicEntity mimic;
    private final double speed;

    private int swing = 0;
    private int hitIn = -1;  // -1 = nothing pending
    private int cooldown = 0;
    private int repath = 0;

    public MimicBiteGoal(MimicEntity mimic, double speed) {
        this.mimic = mimic;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mimic.getTarget();
        return target != null && target.isAlive() && mimic.isAwake();
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
        mimic.setAggressive(true);
        repath = 0;
    }

    @Override
    public void stop() {
        mimic.setAggressive(false);
        mimic.getNavigation().stop();
        swing = 0;
        hitIn = -1;
    }

    @Override
    public void tick() {
        LivingEntity target = mimic.getTarget();
        if (target == null) {
            return;
        }

        mimic.getLookControl().setLookAt(target, 40.0F, 40.0F);

        if (cooldown > 0) {
            cooldown--;
        }

        if (hitIn >= 0) {
            if (hitIn == 0) {
                if (target.isAlive() && mimic.inBiteRange(target, 0.5D)) {
                    mimic.doHurtTarget(target);
                }
                mimic.biteSnapEffects();
                hitIn = -1;
            } else {
                hitIn--;
            }
        }

        if (swing > 0) {
            swing--;
            mimic.getNavigation().stop();
            return;
        }

        if (--repath <= 0) {
            repath = 4 + mimic.getRandom().nextInt(5);
            if (!mimic.getNavigation().moveTo(target, speed)) {
                mimic.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }

        if (cooldown <= 0 && mimic.inBiteRange(target, 0.0D)) {
            swing = SWING_TICKS;
            cooldown = ATTACK_COOLDOWN;
            hitIn = HIT_DELAY;
            mimic.getNavigation().stop();
            mimic.triggerAnim("attack", "attack");
            mimic.biteStartEffects();
        }
    }
}
