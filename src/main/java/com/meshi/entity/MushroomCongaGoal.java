package com.meshi.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.ai.goal.Goal;

// conga line: idle mushroom follows another idle one nearby. only follows a lower entity id so theres always a leader n never a circle. follows for up to 30s
public class MushroomCongaGoal extends Goal {
    private static final double RANGE = 10.0D;
    private static final int MAX_TICKS = 20 * 30;

    private final WalkingMushroomEntity mushroom;
    private WalkingMushroomEntity leader;
    private int ticks;
    private int repath;

    public MushroomCongaGoal(WalkingMushroomEntity mushroom) {
        this.mushroom = mushroom;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mushroom.isBusy() || mushroom.getTarget() != null || mushroom.getRandom().nextInt(40) != 0) {
            return false;
        }
        List<WalkingMushroomEntity> others = mushroom.level().getEntitiesOfClass(WalkingMushroomEntity.class,
                mushroom.getBoundingBox().inflate(RANGE, 3.0D, RANGE),
                m -> m != mushroom && m.isAlive() && !m.isBusy() && m.getId() < mushroom.getId());
        if (others.isEmpty()) {
            return false;
        }
        others.sort(Comparator.comparingDouble(mushroom::distanceToSqr));
        leader = others.get(0);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return leader != null && leader.isAlive() && !leader.isBusy() && !mushroom.isBusy()
                && mushroom.getTarget() == null && ticks < MAX_TICKS
                && mushroom.distanceToSqr(leader) < 18.0D * 18.0D;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
    }

    @Override
    public void stop() {
        leader = null;
        mushroom.getNavigation().stop();
    }

    @Override
    public void tick() {
        ticks++;
        mushroom.getLookControl().setLookAt(leader, 30.0F, 30.0F);
        if (mushroom.distanceToSqr(leader) > 2.25D) {
            if (--repath <= 0) {
                repath = 8;
                mushroom.getNavigation().moveTo(leader, 0.95D);
            }
        } else {
            mushroom.getNavigation().stop();
        }
    }
}
