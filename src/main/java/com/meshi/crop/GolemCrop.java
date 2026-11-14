package com.meshi.crop;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// crops the mound can grow. new crop = add one line here
// the farmers delight names are from memory, if one is wrong fix it here (check w/ /give @s farmersdelight:cabbage_seeds)
public enum GolemCrop {
    CABBAGE("cabbage", "farmersdelight:cabbage_seeds", "farmersdelight:cabbage", "Cabbage"),
    TOMATO("tomato", "farmersdelight:tomato_seeds", "farmersdelight:tomato", "Tomato"),
    ONION("onion", "farmersdelight:onion", "farmersdelight:onion", "Onion"),
    CARROT("carrot", "minecraft:carrot", "minecraft:carrot", "Carrot");

    // crops dropped per seed (4 seeds = 16 crops)
    public static final int CROPS_PER_SEED = 4;

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

    public Item dropItem() {
        return BuiltInRegistries.ITEM.getOptional(dropItem).orElse(null);
    }

    // the item the player planted, shown on the mound
    public ItemStack plantStack() {
        Item item = BuiltInRegistries.ITEM.getOptional(plantItem).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    // the grown crop (cabbage etc), falls back to the planted item if the crop item isnt there
    public ItemStack finalStack() {
        Item item = dropItem();
        return item == null ? plantStack() : new ItemStack(item);
    }

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

    // reads the planted crops from a tag, old saves w/ Seeds + Crop still load
    public static List<GolemCrop> readList(CompoundTag tag, int max) {
        List<GolemCrop> list = new ArrayList<>();
        if (tag.contains("Crops", Tag.TAG_LIST)) {
            ListTag ids = tag.getList("Crops", Tag.TAG_STRING);
            for (int i = 0; i < ids.size() && list.size() < max; i++) {
                GolemCrop crop = byId(ids.getString(i));
                if (crop != null) {
                    list.add(crop);
                }
            }
        } else {
            GolemCrop legacy = byId(tag.getString("Crop"));
            int seeds = Math.min(max, tag.getInt("Seeds"));
            for (int i = 0; legacy != null && i < seeds; i++) {
                list.add(legacy);
            }
        }
        return list;
    }

    public static void writeList(CompoundTag tag, List<GolemCrop> crops) {
        ListTag ids = new ListTag();
        for (GolemCrop crop : crops) {
            ids.add(StringTag.valueOf(crop.id()));
        }
        tag.put("Crops", ids);
    }
}
