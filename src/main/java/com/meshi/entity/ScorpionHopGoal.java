package com.meshi.entity;

import com.meshi.block.ScorpionBurrowBlock;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/**
 * Run for the nearest burrow the scorpion can reach, then dive into the wall (the dive itself, the wait
 * inside the wall and coming out of another burrow are handled by HugeScorpionEntity).
 *
 * Once this goal has started the scorpion is "fleeing": it cannot be interrupted, cannot sting and cannot
 * pick a new target until it is out of the next burrow. It dives in as soon as it is in front of the hole
 * (it does not have to stand exactly on the spot: big mobs stop short of path targets), and if it has not
 * quite got there after 20 seconds it still dives in when it is close.
 *
 * It always finishes: if it is wedged or too slow, after 20 seconds (or 3 seconds without progress) it is
 * moved the last stretch and dives in. It only stays out if there is no burrow within range at all.
 */
public class ScorpionHopGoal extends Goal {
    /** Run speed while heading for the burrow (the scorpion's walk speed times this). */
    private static final double RUN_SPEED = 1.6D;
    private static final int GIVE_UP_TICKS = 20 * 20;

    private final HugeScorpionEntity scorpion;
    private BlockPos burrow;
    private Direction facing = Direction.NORTH;
    private Vec3 front;
    private int ticks;
    private int repath;
    private boolean finished;
    private double lastDist;
    private int stuck;

    public ScorpionHopGoal(HugeScorpionEntity scorpion) {
        this.scorpion = scorpion;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean isInterruptable() {
        return false; // nothing takes over while it runs for its burrow
    }

    @Override
    public boolean canUse() {
        if (!scorpion.isHopRequested() || !(scorpion.level() instanceof ServerLevel server)) {
            return false;
        }
        BlockPos here = scorpion.blockPosition();
        List<BlockPos> burrows = ScorpionBurrowBlock.findBurrows(server, here, HugeScorpionEntity.HOP_RANGE, 6);
        burrows.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(here)));

        // Take the nearest one it can actually walk to. If none has a clean path, still run for a close one
        // in a straight line: a fleeing scorpion must never just give up.
        for (int i = 0; i < Math.min(4, burrows.size()); i++) {
            BlockPos candidate = burrows.get(i);
            Vec3 spot = ScorpionBurrowBlock.frontSpot(server, candidate);
            if (spot == null) {
                continue;
            }
            Path path = scorpion.getNavigation().createPath(spot.x, spot.y, spot.z, 0);
            if (path != null && path.canReach()) {
                return pick(server, candidate, spot);
            }
        }
        for (BlockPos candidate : burrows) {
            Vec3 spot = ScorpionBurrowBlock.frontSpot(server, candidate);
            if (spot != null && candidate.distSqr(here) <= 10.0D * 10.0D) {
                return pick(server, candidate, spot);
            }
        }
        // Nothing to run to: stay and fight (and eat the stolen food on the spot if it has some).
        scorpion.noteNoBurrow();
        scorpion.eatCarried();
        scorpion.clearHopRequest(20 * 8);
        return false;
    }

    private boolean pick(ServerLevel server, BlockPos candidate, Vec3 spot) {
        this.burrow = candidate;
        this.facing = ScorpionBurrowBlock.facingOf(server, candidate, Direction.NORTH);
        this.front = spot;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished && !scorpion.isHidden() && !scorpion.isEmerging();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        ticks = 0;
        repath = 0;
        finished = false;
        stuck = 0;
        lastDist = 999.0D;
        scorpion.setFleeing(true); // from now on nothing distracts it
        scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
    }

    @Override
    public void stop() {
        if (!scorpion.isHidden()) {
            // Never made it into the burrow: back to normal, and not again for a few seconds.
            scorpion.clearHopRequest(20 * 6);
            scorpion.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        ticks++;
        ServerLevel server = (ServerLevel) scorpion.level();

        // The burrow was broken on the way: run for another one, or give up only if there truly is none.
        if (!(server.getBlockState(burrow).getBlock() instanceof ScorpionBurrowBlock)) {
            List<BlockPos> others = ScorpionBurrowBlock.findBurrows(server, scorpion.blockPosition(), HugeScorpionEntity.HOP_RANGE, 6);
            BlockPos here = scorpion.blockPosition();
            others.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(here)));
            for (BlockPos candidate : others) {
                Vec3 spot = ScorpionBurrowBlock.frontSpot(server, candidate);
                if (spot != null) {
                    pick(server, candidate, spot);
                    ticks = 0;
                    return;
                }
            }
            finished = true;
            return;
        }

        Vec3 plane = ScorpionBurrowBlock.planeOf(burrow, facing);
        double dx = scorpion.getX() - plane.x;
        double dz = scorpion.getZ() - plane.z;
        double along = dx * facing.getStepX() + dz * facing.getStepZ(); // > 0 = on the open side of the wall
        double dist = Math.sqrt(dx * dx + dz * dz);

        // In front of the hole and close enough: dive. (The scorpion is wide and path following stops it a
        // little short of its target, so "close enough" has to be generous.)
        if (along > 0.2D && dist <= 3.2D) {
            scorpion.beginDive(burrow);
            return;
        }

        // Guaranteed: if it has not made it after 20 seconds (or it is wedged), it is carried the last stretch.
        if (ticks >= GIVE_UP_TICKS || stuck >= 60) {
            scorpion.getNavigation().stop();
            scorpion.setPos(front.x, scorpion.getY(), front.z);
            scorpion.beginDive(burrow);
            return;
        }

        // Stuck check: no progress towards the hole for 3 seconds.
        if (ticks % 20 == 0) {
            stuck = (lastDist - dist < 0.3D) ? stuck + 20 : 0;
            lastDist = dist;
        }

        if (--repath <= 0) {
            repath = 5;
            scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
        }
        // No path (or it ran out short of the hole): just run straight at it.
        if (scorpion.getNavigation().isDone()) {
            scorpion.getMoveControl().setWantedPosition(front.x, front.y, front.z, RUN_SPEED);
        }
    }
}
