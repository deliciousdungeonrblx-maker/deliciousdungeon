package com.meshi.item;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/** A bowl of hotpot. Eating it gives the food and effects, and always hands the empty bowl back. */
public class HotpotServingItem extends Item {
    public HotpotServingItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity consumer) {
        ItemStack bowl = new ItemStack(Items.BOWL);

        // Vanilla eating: applies hunger and effects and shrinks the stack (not in creative).
        super.finishUsingItem(stack, level, consumer);

        if (stack.isEmpty()) {
            return bowl;
        }
        if (consumer instanceof Player player && !player.getAbilities().instabuild) {
            if (!player.getInventory().add(bowl)) {
                player.drop(bowl, false);
            }
        }
        return stack;
    }
}
