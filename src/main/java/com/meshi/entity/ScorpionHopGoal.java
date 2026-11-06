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

// run for the nearest reachable burrow then dive in (the dive, waiting inside n coming out of another one is in HugeScorpionEntity)
// once started its 'fleeing': cant be interrupted, cant sting, no new targets til its out of teh next burrow
// dives as soon as its infront of teh hole (big mobs stop short of path targets so close enough has to be generous)
// always finishes: wedged or too slow after 20s (or 3s w/o progress) it gets moved the last stretch n dives. only stays out if no burrow in range
public class ScorpionHopGoal extends Goal {
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
        return false;  // nothing takes over while it runs
    }

    @Override
    public boolean canUse() {
        if (!scorpion.isHopRequested() || !(scorpion.level() instanceof ServerLevel server)) {
            return false;
        }
        BlockPos here = scorpion.blockPosition();
        List<BlockPos> burrows = ScorpionBurrowBlock.findBurrows(server, here, HugeScorpionEntity.HOP_RANGE, 6);
        burrows.sort(Comparator.comparingDouble((BlockPos p) -> p.distSqr(here)));
        // heading home to hide: its own burrow goes first
        BlockPos homePos = scorpion.getHome();
        if (scorpion.isRetreating() && homePos != null && burrows.remove(homePos)) {
            burrows.add(0, homePos);
        }

        // nearest one it can walk to, if no clean path still runs straight at a close one. a fleeing scorpion never gives up
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
        // nothing to run to: stays n fights (eats stolen food on the spot if it has some)
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
        scorpion.setFleeing(true);
        scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
    }

    @Override
    public void stop() {
        if (!scorpion.isHidden()) {
            scorpion.clearHopRequest(20 * 6);
            scorpion.getNavigation().stop();
        }
    }

    @Override
    public void tick() {
        ticks++;
        ServerLevel server = (ServerLevel) scorpion.level();

        // Burrow broke on the way: run to another, give up only if theres none
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
        double along = dx * facing.getStepX() + dz * facing.getStepZ();  // > 0 = open side of the wall
        double dist = Math.sqrt(dx * dx + dz * dz);

        // infront of the hole + close enough = dive (generous, big mobs stop short)
        if (along > 0.2D && dist <= 3.2D) {
            scorpion.beginDive(burrow);
            return;
        }

        // guaranteed: aftr 20s or if wedged it gets carried the last stretch
        if (ticks >= GIVE_UP_TICKS || stuck >= 60) {
            scorpion.getNavigation().stop();
            scorpion.setPos(front.x, scorpion.getY(), front.z);
            scorpion.beginDive(burrow);
            return;
        }

        if (ticks % 20 == 0) {
            stuck = (lastDist - dist < 0.3D) ? stuck + 20 : 0;
            lastDist = dist;
        }

        if (--repath <= 0) {
            repath = 5;
            scorpion.getNavigation().moveTo(front.x, front.y, front.z, RUN_SPEED);
        }
        // no path or ran out short: just run straight at it
        if (scorpion.getNavigation().isDone()) {
            scorpion.getMoveControl().setWantedPosition(front.x, front.y, front.z, RUN_SPEED);
        }
    }
}
