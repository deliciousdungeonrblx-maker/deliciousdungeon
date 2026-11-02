package com.meshi.crop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The crops a soil mound can grow. To add another crop, add one line here:
 * (id, item you right-click the mound with, item the golem drops, display name).
 *
 * The Farmer's Delight item names below are from memory. If one is wrong, fix it here; check a name in
 * game with /give @s farmersdelight:cabbage_seeds
 */
public enum GolemCrop {
    CABBAGE("cabbage", "farmersdelight:cabbage_seeds", "farmersdelight:cabbage", "Cabbage"),
    TOMATO("tomato", "farmersdelight:tomato_seeds", "farmersdelight:tomato", "Tomato"),
    ONION("onion", "farmersdelight:onion", "farmersdelight:onion", "Onion"),
    CARROT("carrot", "minecraft:carrot", "minecraft:carrot", "Carrot");

    /** How many crops a ripe golem drops for every seed that was planted. */
    public static final int CROPS_PER_SEED = 2;

    private final String id;
    private final ResourceLocation plantItem;
    private final ResourceLocation dropItem;
    private final String displayName;

    GolemCrop(String id, String plantItem, String dropItem, String displayName) {
        this.id = id;
        this.plantItem = ResourceLocation.parse(plantItem);
        this.dropItem = ResourceLocation.parse(dropItem);
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** The item the golem drops, or null if that item does not exist (for example Farmer's Delight is missing). */
    public Item dropItem() {
        return BuiltInRegistries.ITEM.getOptional(dropItem).orElse(null);
    }

    /** Which crop does this item plant? Null if it is not a plantable item. */
    public static GolemCrop fromItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        for (GolemCrop crop : values()) {
            if (crop.plantItem.equals(key)) {
                return crop;
            }
        }
        return null;
    }

    public static GolemCrop byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (GolemCrop crop : values()) {
            if (crop.id.equals(id)) {
                return crop;
            }
        }
        return null;
    }

    /** Index used for syncing to clients; -1 means "no crop yet". */
    public static GolemCrop byIndex(int index) {
        GolemCrop[] all = values();
        return index >= 0 && index < all.length ? all[index] : null;
    }
}
