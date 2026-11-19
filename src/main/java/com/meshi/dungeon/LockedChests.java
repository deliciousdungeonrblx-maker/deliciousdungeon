package com.meshi.dungeon;

import com.meshi.registry.ModItems;
import com.meshi.util.Fx;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

// locked chests: look like normal chests, need a lockpick. every use is one try (like taming a wolf, just harder)
// DungeonBuilder decides which chests are locked, positions live in DungeonData.lockedChests
public class LockedChests {
    // skill level = chests picked / 10, capped. higher level = better odds + fewer tries before it opens for sure
    public static final int PICKS_PER_LEVEL = 10;
    public static final int MAX_SKILL = 5;
    // a chest always opens by try 20 at level 0, 5 at max level (never a one click open)
    private static final int BASE_TRIES = 20;
    private static final int TRIES_PER_LEVEL = 3;
    // chance per try: 6% at level 0 up to 23.5% at max level
    private static final double BASE_CHANCE = 0.06D;
    private static final double CHANCE_PER_LEVEL = 0.035D;
    private static final int COOLDOWN_TICKS = 12;

    public static int skillOf(DungeonData data, UUID id) {
        return Math.min(data.picked.getOrDefault(id, 0) / PICKS_PER_LEVEL, MAX_SKILL);
    }

    private static boolean isDungeon(ServerLevel level) {
        return level.dimension().equals(DungeonEvents.DIMENSION);
    }

    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)
                || event.getHand() != InteractionHand.MAIN_HAND || !isDungeon(level)) {
            return;
        }
        BlockPos pos = event.getPos();
        DungeonData data = DungeonData.get(level);
        long key = pos.asLong();
        if (!data.lockedChests.contains(key)) {
            return;
        }
        if (!level.getBlockState(pos).is(Blocks.CHEST)) {
            // chest is gone (blown up / removed), forget the lock
            data.lockedChests.remove(key);
            data.lockAttempts.remove(key);
            data.setDirty();
            return;
        }
        // we handle it from here, chest never opens by itself
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        boolean hasPick = player.getMainHandItem().is(ModItems.LOCKPICK.get())
                || player.getOffhandItem().is(ModItems.LOCKPICK.get());
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.6D;
        double z = pos.getZ() + 0.5D;
        if (!hasPick) {
            player.displayClientMessage(Component.translatable("message.meshi.chest.locked"), true);
            Fx.soundVaried(level, x, y, z, SoundEvents.CHEST_LOCKED, 1.0F, 1.0F);
            return;
        }
        if (player.getCooldowns().isOnCooldown(ModItems.LOCKPICK.get())) {
            return;
        }
        player.getCooldowns().addCooldown(ModItems.LOCKPICK.get(), COOLDOWN_TICKS);

        int skill = skillOf(data, player.getUUID());
        int tries = data.lockAttempts.merge(key, 1, Integer::sum);
        boolean open = tries >= BASE_TRIES - TRIES_PER_LEVEL * skill
                || level.random.nextDouble() < BASE_CHANCE + CHANCE_PER_LEVEL * skill;

        // every try: pins clicking
        Fx.soundVaried(level, x, y, z, SoundEvents.TRIPWIRE_CLICK_ON, 0.7F, 1.7F);
        Fx.soundVaried(level, x, y, z, SoundEvents.CHAIN_HIT, 0.5F, 1.9F);

        if (!open) {
            Fx.soundVaried(level, x, y, z, SoundEvents.CHEST_LOCKED, 0.5F, 1.3F);
            level.sendParticles(ParticleTypes.SMOKE, x, y + 0.2D, z, 4, 0.2D, 0.1D, 0.2D, 0.01D);
            player.displayClientMessage(Component.translatable("message.meshi.lockpick.fail" + (1 + level.random.nextInt(3))), true);
            data.setDirty();
            return;
        }

        data.lockedChests.remove(key);
        data.lockAttempts.remove(key);
        int count = data.picked.merge(player.getUUID(), 1, Integer::sum);
        data.setDirty();

        Fx.sound(level, x, y, z, SoundEvents.IRON_TRAPDOOR_OPEN, 0.8F, 1.6F);
        Fx.sound(level, x, y, z, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8F, 1.2F);
        Fx.sparkle(level, x, y + 0.3D, z, 10, 0.3D);
        level.sendParticles(ParticleTypes.CRIT, x, y + 0.3D, z, 8, 0.25D, 0.15D, 0.25D, 0.1D);

        int newSkill = count / PICKS_PER_LEVEL;
        if (count % PICKS_PER_LEVEL == 0 && newSkill <= MAX_SKILL) {
            Fx.sound(level, player.getX(), player.getY(), player.getZ(), SoundEvents.PLAYER_LEVELUP, 0.7F, 1.4F);
            player.displayClientMessage(Component.translatable(
                    newSkill == MAX_SKILL ? "message.meshi.lockpick.maxed" : "message.meshi.lockpick.levelup", newSkill), false);
        } else {
            player.displayClientMessage(Component.translatable("message.meshi.lockpick.success"), true);
        }
    }

    // cant just punch a locked chest open (creative still can, for editing)
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isDungeon(level)) {
            return;
        }
        DungeonData data = DungeonData.get(level);
        long key = event.getPos().asLong();
        if (!data.lockedChests.contains(key)) {
            return;
        }
        Player p = event.getPlayer();
        if (p.isCreative()) {
            data.lockedChests.remove(key);
            data.lockAttempts.remove(key);
            data.setDirty();
            return;
        }
        event.setCanceled(true);
        p.displayClientMessage(Component.translatable("message.meshi.chest.locked"), true);
        BlockPos pos = event.getPos();
        Fx.soundVaried(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, SoundEvents.CHEST_LOCKED, 1.0F, 0.8F);
    }

    // explosions leave locked chests alone
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isDungeon(level)) {
            return;
        }
        DungeonData data = DungeonData.get(level);
        if (data.lockedChests.isEmpty()) {
            return;
        }
        event.getAffectedBlocks().removeIf(p -> data.lockedChests.contains(p.asLong()));
    }
}
