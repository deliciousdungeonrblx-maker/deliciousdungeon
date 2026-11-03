package com.meshi;

import com.meshi.entity.EarthGolemEntity;
import com.meshi.registry.ModBlocks;
import com.meshi.registry.ModEntities;
import com.meshi.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@Mod(Meshi.MODID)
public class Meshi {
    public static final String MODID = "meshi";

    public Meshi(IEventBus modEventBus) {
        ModBlocks.BLOCKS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::registerAttributes);
        modEventBus.addListener(this::addCreativeTabItems);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.EARTH_GOLEM.get(), EarthGolemEntity.createAttributes().build());
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.EARTH_GOLEM_SPAWN_EGG);
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(ModItems.GOLEM_SOIL);
            event.accept(ModItems.RAW_SCORPION_MEAT);
            event.accept(ModItems.SLICED_SCORPION);
            event.accept(ModItems.MUSHCORPSE);
            event.accept(ModItems.MUSHBODY);
            event.accept(ModItems.MUSHFEET);
        }
        if (event.getTabKey() == CreativeModeTabs.FOOD_AND_DRINKS) {
            event.accept(ModItems.HOTPOT);
            event.accept(ModItems.HOTPOT_SERVING);
        }
    }
}
