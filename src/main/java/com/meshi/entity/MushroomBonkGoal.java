package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

// chase n headbutt. anim is 0.83s and the head lands at ~0.3s so dmg comes 6 ticks in. dizzy aftr the swing (free opening)
public class MushroomBonkGoal extends Goal {
    private static final int SWING_TICKS = 17;
    private static final int HIT_DELAY = 6;
    private static final int ATTACK_COOLDOWN = 50;
    private static final int DIZZY_TICKS = 24;

    private final WalkingMushroomEntity mushroom;
    private final double speed;

    private int swing = 0;
    private int hitIn = -1;
    private int dizzy = 0;
    private int cooldown = 0;
    private int repath = 0;

    public MushroomBonkGoal(WalkingMushroomEntity mushroom, double speed) {
        this.mushroom = mushroom;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mushroom.getTarget();
        return target != null && target.isAlive() && !mushroom.isBusy();
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
        mushroom.setAggressive(true);
        repath = 0;
    }

    @Override
    public void stop() {
        mushroom.setAggressive(false);
        mushroom.getNavigation().stop();
        swing = 0;
        hitIn = -1;
        dizzy = 0;
    }

    @Override
    public void tick() {
        LivingEntity target = mushroom.getTarget();
        if (target == null) {
            return;
        }

        mushroom.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (cooldown > 0) {
            cooldown--;
        }

        if (hitIn >= 0) {
            if (hitIn == 0) {
                if (target.isAlive() && mushroom.inBonkRange(target, 0.5D)) {
                    mushroom.bonk(target);
                }
                hitIn = -1;
            } else {
                hitIn--;
            }
        }

        if (swing > 0) {
            swing--;
            mushroom.getNavigation().stop();
            if (swing == 0) {
                dizzy = DIZZY_TICKS;
            }
            return;
        }
        if (dizzy > 0) {
            dizzy--;
            mushroom.getNavigation().stop();
            if (dizzy % 6 == 0) {
                mushroom.dizzyFx();
            }
            return;
        }

        if (--repath <= 0) {
            repath = 4 + mushroom.getRandom().nextInt(7);
            mushroom.getNavigation().moveTo(target, speed);
        }

        if (cooldown <= 0
                && mushroom.inBonkRange(target, 0.0D)
                && mushroom.getSensing().hasLineOfSight(target)) {
            swing = SWING_TICKS;
            cooldown = ATTACK_COOLDOWN;
            hitIn = HIT_DELAY;
            mushroom.getNavigation().stop();
            mushroom.attackStartEffects();
        }
    }
}
