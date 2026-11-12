package com.meshi.dungeon;

import com.meshi.block.FireTrapPlateBlock;
import com.meshi.block.TrapFloorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;

// lets a locksmith see which floor blocks are trap plates. a player with the tag "locksmith" gets little sparks over
// every trap plate close to them (only that player sees them). /meshi dungeon trapsense does the same once, so a class
// skill can call it
final class DungeonSense {
    static final String TAG = "locksmith";
    private static final int RADIUS = 7;
    private static final int INTERVAL = 10;

    private DungeonSense() {
    }

    static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL != 0) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.getTags().contains(TAG)) {
                pulse(level, p, RADIUS);
            }
        }
    }

    // marks every trap plate in the radius, returns how many it found
    static int pulse(ServerLevel level, ServerPlayer player, int radius) {
        BlockPos c = player.blockPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                for (int dy = -2; dy <= 1; dy++) {
                    pos.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    Block b = level.getBlockState(pos).getBlock();
                    if (b instanceof TrapFloorBlock || b instanceof FireTrapPlateBlock) {
                        level.sendParticles(player, ParticleTypes.ELECTRIC_SPARK, false,
                                pos.getX() + 0.5D, pos.getY() + 1.05D, pos.getZ() + 0.5D, 3, 0.25D, 0.0D, 0.25D, 0.0D);
                        found++;
                    }
                }
            }
        }
        return found;
    }
}
