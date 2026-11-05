package com.meshi.entity;

import com.meshi.block.ScorpionBurrowBlock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Run for the nearest burrow the scorpion can reach, then dive into the wall (the dive itself, the wait
 * inside the wall and coming out of another burrow are handled by HugeScorpionEntity).
 *
 * Starts when the scorpion wants to hop (see HugeScorpionEntity.requestHop). If there is no burrow it can
 * reach, it gives up and carries on with what it was doing.
 */
public class ScorpionHopGoal extends Goal {
    /** Run speed while heading for the burrow (the scorpion's walk speed times this). */
    private static final double RUN_SPEED = 1.5D;
    /** Gives up if it has not arrived after 15 seconds. */
    private static final int GIVE_UP_TICKS = 20 * 15;

    private final HugeScorpionEntity scorpion;
    private BlockPos burrow;
    private Vec3 front;
    private int ticks;
    private int repath;

    public ScorpionHopGoal(HugeScorpionEntity scorpion) {
        this.scorpion = scorpion;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!scorpion.isHopRequested() || !(scorpion.level() instanceof ServerLevel server)) {
            return false;
        }
        BlockPos here = scorpion.blockPosition();
        List<BlockPos> burrows = ScorpionBurrowBlock.findBurrows(server, here, HugeScorpionEntity.HOP_RANGE, 6);
        burrows.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(here)));

        // Take the nearest one it can actually walk to.
        for (int i = 0; i < Math.min(4, burrows.size()); i++) {
            BlockPos candidate = burrows.get(i);
            Vec3 spot = ScorpionBurrowBlock.frontSpot(server, candidate);
            if (spot == null) {
                continue;
            }
            Path path = scorpion.getNavigation().createPath(spot.x, spot.y, spot.z, 0);
            if (path != null && path.canReach()) {
                this.burrow = candidate;
                this.front = spot;
                return true;
            }
        }
        scorpion.clearHopRequest(20 * 8); // nothing to run to, try again later
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !scorpion.isHidden()
                && !scorpion.isEmerging()
                && ticks < GIVE_UP_TICKS
                && scorpion.level().getBlockState(burrow).getBlock() instanceof ScorpionBurrowBlock;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
    }

    @Override
    public void stop() {
        if (!scorpion.isHidden()) {
            // Never made it into the burrow: forget it for a while.
            scorpion.clearHopRequest(20 * 6);
            scorpion.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        ticks++;
        if (--repath <= 0) {
            repath = 10;
            scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
        }
        double dx = scorpion.getX() - front.x;
        double dz = scorpion.getZ() - front.z;
        if (dx * dx + dz * dz < 2.25D && Math.abs(scorpion.getY() - front.y) < 2.0D) {
            scorpion.beginDive(burrow);
        }
    }
}
