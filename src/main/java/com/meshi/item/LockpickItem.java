package com.meshi.item;

import com.meshi.dungeon.DungeonData;
import com.meshi.dungeon.DungeonEvents;
import com.meshi.dungeon.LockedChests;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

// the lockpick. the picking itself is in LockedChests, this just shows ur skill (sneak + right click in the air)
public class LockpickItem extends Item {
    public LockpickItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player.isShiftKeyDown() && player instanceof ServerPlayer sp) {
            ServerLevel dungeon = sp.getServer().getLevel(DungeonEvents.DIMENSION);
            if (dungeon != null) {
                int picked = DungeonData.get(dungeon).picked.getOrDefault(sp.getUUID(), 0);
                int skill = Math.min(picked / LockedChests.PICKS_PER_LEVEL, LockedChests.MAX_SKILL);
                sp.displayClientMessage(Component.translatable("message.meshi.lockpick.skill",
                        skill, LockedChests.MAX_SKILL, picked), true);
            }
        }
        return InteractionResultHolder.pass(stack);
    }
}
