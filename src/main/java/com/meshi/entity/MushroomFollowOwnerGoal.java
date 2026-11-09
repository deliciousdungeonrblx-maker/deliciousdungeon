package com.meshi.entity;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

// tamed mushroom walks after its owner. teleports next to them if they get too far. does nothing while on a lead
public class MushroomFollowOwnerGoal extends Goal {
    private static final double START_DIST = 6.0D;
    private static final double STOP_DIST = 3.0D;
    private static final double TELEPORT_DIST = 24.0D;

    private final WalkingMushroomEntity mushroom;
    private final double speed;
    private Player owner;
    private int repath;

    public MushroomFollowOwnerGoal(WalkingMushroomEntity mushroom, double speed) {
        this.mushroom = mushroom;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!mushroom.isTamed() || mushroom.isBusy() || mushroom.isLeashed() || mushroom.getTarget() != null) {
            return false;
        }
        Player found = mushroom.getOwnerPlayer();
        if (found == null || found.isSpectator() || mushroom.distanceToSqr(found) < START_DIST * START_DIST) {
            return false;
        }
        this.owner = found;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return owner != null && owner.isAlive() && !mushroom.isBusy() && !mushroom.isLeashed()
                && mushroom.getTarget() == null && mushroom.distanceToSqr(owner) > STOP_DIST * STOP_DIST;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        mushroom.getNavigation().stop();
    }

    @Override
    public void tick() {
        mushroom.getLookControl().setLookAt(owner, 30.0F, 30.0F);
        if (mushroom.distanceToSqr(owner) > TELEPORT_DIST * TELEPORT_DIST) {
            teleportNearOwner();
            return;
        }
        if (--repath <= 0) {
            repath = 10;
            mushroom.getNavigation().moveTo(owner, speed);
        }
    }

    // tries a few spots around the owner, takes the first one w/ room n a floor
    private void teleportNearOwner() {
        for (int i = 0; i < 12; i++) {
            double x = owner.getX() + (mushroom.getRandom().nextInt(7) - 3);
            double y = owner.getY();
            double z = owner.getZ() + (mushroom.getRandom().nextInt(7) - 3);
            BlockPos below = BlockPos.containing(x, y - 1.0D, z);
            if (mushroom.level().getBlockState(below).getCollisionShape(mushroom.level(), below).isEmpty()) {
                continue;
            }
            var box = mushroom.getBoundingBox().move(x - mushroom.getX(), y - mushroom.getY(), z - mushroom.getZ());
            if (mushroom.level().noCollision(mushroom, box)) {
                mushroom.moveTo(x, y, z, mushroom.getYRot(), mushroom.getXRot());
                mushroom.getNavigation().stop();
                return;
            }
        }
    }
}
