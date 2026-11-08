package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.item.GolemSoilItem;
import com.meshi.item.HotpotServingItem;
import com.meshi.item.MushroomPotionItem;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Meshi.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> EARTH_GOLEM_SPAWN_EGG =
            ITEMS.register("earth_golem_spawn_egg", () ->
                    new DeferredSpawnEggItem(ModEntities.EARTH_GOLEM, 0x6B4A2F, 0x4E8F2F, new Item.Properties()));

    public static final DeferredItem<DeferredSpawnEggItem> HUGE_SCORPION_SPAWN_EGG =
            ITEMS.register("huge_scorpion_spawn_egg", () ->
                    new DeferredSpawnEggItem(ModEntities.HUGE_SCORPION, 0x8E2F5A, 0x7DC43A, new Item.Properties()));

    public static final DeferredItem<DeferredSpawnEggItem> WALKING_MUSHROOM_SPAWN_EGG =
            ITEMS.register("walking_mushroom_spawn_egg", () ->
                    new DeferredSpawnEggItem(ModEntities.WALKING_MUSHROOM, 0xC8302F, 0xEBDDB8, new Item.Properties()));

    public static final DeferredItem<BlockItem> SCORPION_BURROW =
            ITEMS.register("scorpion_burrow", () ->
                    new BlockItem(ModBlocks.SCORPION_BURROW.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> SLIME_TRAP_PLATE =
            ITEMS.register("slime_trap_plate", () ->
                    new BlockItem(ModBlocks.SLIME_TRAP_PLATE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> RANDOM_TRAP_PLATE =
            ITEMS.register("random_trap_plate", () ->
                    new BlockItem(ModBlocks.RANDOM_TRAP_PLATE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> ARROW_TRAP_PLATE =
            ITEMS.register("arrow_trap_plate", () ->
                    new BlockItem(ModBlocks.ARROW_TRAP_PLATE.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> FIRE_TRAP_PLATE =
            ITEMS.register("fire_trap_plate", () ->
                    new BlockItem(ModBlocks.FIRE_TRAP_PLATE.get(), new Item.Properties()));

    public static final DeferredItem<GolemSoilItem> GOLEM_SOIL =
            ITEMS.register("golem_soil", () -> new GolemSoilItem(new Item.Properties().stacksTo(16)));

    // Hotpot stuff, dice it on a FD cutting board

    public static final DeferredItem<Item> RAW_SCORPION_MEAT =
            ITEMS.register("raw_scorpion_meat", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> SLICED_SCORPION =
            ITEMS.register("sliced_scorpion", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MUSHCORPSE =
            ITEMS.register("mushcorpse", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MUSHBODY =
            ITEMS.register("mushbody", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MUSHFEET =
            ITEMS.register("mushfeet", () -> new Item(new Item.Properties()));


    private static final FoodProperties HOTPOT_FOOD = new FoodProperties.Builder()
            .nutrition(10)
            .saturationModifier(0.8F)
            .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 1200, 0), 1.0F)
            .build();

    public static final DeferredItem<HotpotServingItem> HOTPOT_SERVING =
            ITEMS.register("hotpot_serving", () ->
                    new HotpotServingItem(new Item.Properties()
                            .food(HOTPOT_FOOD)
                            .craftRemainder(Items.BOWL)
                            .stacksTo(16)));

    public static final DeferredItem<BlockItem> HOTPOT =
            ITEMS.register("hotpot", () ->
                    new BlockItem(ModBlocks.HOTPOT.get(), new Item.Properties().stacksTo(1)));

    public static final DeferredItem<MushroomPotionItem> MUSHROOM_POTION =
            ITEMS.register("mushroom_potion", () -> new MushroomPotionItem(new Item.Properties().stacksTo(16)));
}
