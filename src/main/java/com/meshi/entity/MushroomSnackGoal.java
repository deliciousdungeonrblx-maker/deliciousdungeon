package com.meshi.entity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;

// dropped mushrooms / fungi / bone meal are irresistible: runs over, eats one, heals abit n hops happily. ignores a chasing player unless hes right next to it. full for 30s after
public class MushroomSnackGoal extends Goal {
    private static final double RANGE = 10.0D;
    private static final double RUN_SPEED = 1.2D;
    private static final int EAT_TICKS = 44;
    private static final int GIVE_UP_TICKS = 20 * 12;

    private final WalkingMushroomEntity mushroom;
    private ItemEntity item;
    private int eating;
    private int ticks;
    private int repath;

    public MushroomSnackGoal(WalkingMushroomEntity mushroom) {
        this.mushroom = mushroom;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (mushroom.isBusy() || mushroom.getSnackCooldown() > 0 || mushroom.getRandom().nextInt(8) != 0) {
            return false;
        }
        LivingEntity target = mushroom.getTarget();
        if (target != null && target.distanceToSqr(mushroom) < 9.0D) {
            return false;  // player in its face comes first
        }
        List<ItemEntity> items = mushroom.level().getEntitiesOfClass(ItemEntity.class,
                mushroom.getBoundingBox().inflate(RANGE, 3.0D, RANGE),
                e -> e.isAlive() && WalkingMushroomEntity.isSnack(e.getItem()));
        items.sort(Comparator.comparingDouble(mushroom::distanceToSqr));
        for (ItemEntity candidate : items) {
            Path path = mushroom.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                this.item = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return item != null && item.isAlive() && ticks < GIVE_UP_TICKS && !mushroom.isBusy();
    }

    @Override
    public void start() {
        ticks = 0;
        eating = 0;
        repath = 0;
        mushroom.getNavigation().moveTo(item, RUN_SPEED);
    }

    @Override
    public void stop() {
        item = null;
        eating = 0;
        if (!mushroom.isBusy()) {
            mushroom.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        ticks++;
        mushroom.getLookControl().setLookAt(item, 30.0F, 30.0F);

        if (eating > 0) {
            eating--;
            mushroom.getNavigation().stop();
            if (eating % 8 == 0 && eating > 0) {
                mushroom.chew(item.getItem());
            }
            if (eating == 0) {
                finish();
            }
            return;
        }

        if (mushroom.getBoundingBox().inflate(0.4D, 0.5D, 0.4D).intersects(item.getBoundingBox())) {
            eating = EAT_TICKS;
            mushroom.getNavigation().stop();
            return;
        }
        if (--repath <= 0) {
            repath = 5;
            mushroom.getNavigation().moveTo(item, RUN_SPEED);
        }
    }

    private void finish() {
        ItemStack rest = item.getItem().copy();
        rest.shrink(1);
        if (rest.isEmpty()) {
            item.discard();
        } else {
            item.setItem(rest);
        }
        mushroom.chew(item.getItem());
        mushroom.finishSnack();
        item = null;
    }
}
