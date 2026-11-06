package com.meshi.entity;

import com.meshi.block.ScorpionBurrowBlock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

// food dropped nearby + nothing else to do = runs out, grabs it n sprints for a burrow (stealFood). kill it first and it drops the food
public class ScorpionScavengeGoal extends Goal {
    private static final double RANGE = 12.0D;
    private static final double RUN_SPEED = 1.4D;
    private static final int GIVE_UP_TICKS = 20 * 10;

    private final HugeScorpionEntity scorpion;
    private ItemEntity item;
    private int ticks;
    private int repath;

    public ScorpionScavengeGoal(HugeScorpionEntity scorpion) {
        this.scorpion = scorpion;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (scorpion.isHidden() || scorpion.isEmerging() || scorpion.isFleeing()
                || scorpion.getTarget() != null || !scorpion.getMainHandItem().isEmpty()) {
            return false;
        }
        if (scorpion.getRandom().nextInt(10) != 0) {
            return false;  // about twice a sec, not every tick
        }
        if (!(scorpion.level() instanceof net.minecraft.server.level.ServerLevel server)
                || ScorpionBurrowBlock.findBurrows(server, scorpion.blockPosition(), HugeScorpionEntity.HOP_RANGE, 6).isEmpty()) {
            return false;  // no burrow to run to
        }
        List<ItemEntity> items = scorpion.level().getEntitiesOfClass(ItemEntity.class,
                scorpion.getBoundingBox().inflate(RANGE, 4.0D, RANGE),
                e -> ScorpionBurrowBlock.isFood(e) && scorpion.isWithinLeash(e));
        items.sort(Comparator.comparingDouble(scorpion::distanceToSqr));
        for (ItemEntity candidate : items) {
            Path path = scorpion.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                this.item = candidate;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return item != null && item.isAlive() && ticks < GIVE_UP_TICKS
                && !scorpion.isFleeing() && !scorpion.isHidden() && scorpion.getTarget() == null;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        scorpion.getNavigation().moveTo(item, RUN_SPEED);
    }

    @Override
    public void stop() {
        item = null;
        if (!scorpion.isFleeing() && !scorpion.isHidden()) {
            scorpion.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        ticks++;
        scorpion.noteBusy();
        scorpion.getLookControl().setLookAt(item, 30.0F, 30.0F);
        if (scorpion.getBoundingBox().inflate(0.5D, 0.3D, 0.5D).intersects(item.getBoundingBox())) {
            scorpion.stealFood(item);
            return;
        }
        if (--repath <= 0) {
            repath = 5;
            scorpion.getNavigation().moveTo(item, RUN_SPEED);
        }
    }
}
