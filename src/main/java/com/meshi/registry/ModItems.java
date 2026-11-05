package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.item.GolemSoilItem;
import com.meshi.item.HotpotServingItem;
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

    /** Click the side of a wall to stick a scorpion hole on it. */
    public static final DeferredItem<BlockItem> SCORPION_BURROW =
            ITEMS.register("scorpion_burrow", () ->
                    new BlockItem(ModBlocks.SCORPION_BURROW.get(), new Item.Properties()));

    /** The item you get from picking up a soil mound. */
    public static final DeferredItem<GolemSoilItem> GOLEM_SOIL =
            ITEMS.register("golem_soil", () -> new GolemSoilItem(new Item.Properties().stacksTo(16)));

    // ---- Hotpot ingredients -------------------------------------------------------------------------------
    // Dropped by the mobs later; for now get them with /give. Dice them on a Farmer's Delight cutting board.

    /** Dropped by the huge scorpion. Diced into Sliced Scorpion. */
    public static final DeferredItem<Item> RAW_SCORPION_MEAT =
            ITEMS.register("raw_scorpion_meat", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> SLICED_SCORPION =
            ITEMS.register("sliced_scorpion", () -> new Item(new Item.Properties()));

    /** Dropped by the walking mushroom. Diced into Mush Body and Mush Feet. */
    public static final DeferredItem<Item> MUSHCORPSE =
            ITEMS.register("mushcorpse", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MUSHBODY =
            ITEMS.register("mushbody", () -> new Item(new Item.Properties()));

    public static final DeferredItem<Item> MUSHFEET =
            ITEMS.register("mushfeet", () -> new Item(new Item.Properties()));

    // ---- The dish -----------------------------------------------------------------------------------------

    /** Stew-sized meal with a 1 minute Regeneration buff. Tweak here. */
    private static final FoodProperties HOTPOT_FOOD = new FoodProperties.Builder()
            .nutrition(10)
            .saturationModifier(0.8F)
            .effect(() -> new MobEffectInstance(MobEffects.REGENERATION, 1200, 0), 1.0F)
            .build();

    /** One bowl of hotpot, taken from the placed pot. Eating it returns the bowl. */
    public static final DeferredItem<HotpotServingItem> HOTPOT_SERVING =
            ITEMS.register("hotpot_serving", () ->
                    new HotpotServingItem(new Item.Properties()
                            .food(HOTPOT_FOOD)
                            .craftRemainder(Items.BOWL)
                            .stacksTo(16)));

    /** What the Cooking Pot produces: the placeable shared pot. */
    public static final DeferredItem<BlockItem> HOTPOT =
            ITEMS.register("hotpot", () ->
                    new BlockItem(ModBlocks.HOTPOT.get(), new Item.Properties().stacksTo(1)));
}
