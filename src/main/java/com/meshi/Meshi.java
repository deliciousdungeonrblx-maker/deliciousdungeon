package com.meshi;

import com.meshi.entity.EarthGolemEntity;
import com.meshi.entity.HugeScorpionEntity;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModBlocks;
import com.meshi.registry.ModEffects;
import com.meshi.registry.ModEntities;
import com.meshi.registry.ModItems;
import com.meshi.registry.ModParticles;
import com.meshi.registry.ModSounds;
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
        ModEffects.MOB_EFFECTS.register(modEventBus);
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModSounds.SOUND_EVENTS.register(modEventBus);
        ModParticles.PARTICLE_TYPES.register(modEventBus);

        modEventBus.addListener(this::registerAttributes);
        modEventBus.addListener(this::addCreativeTabItems);
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.EARTH_GOLEM.get(), EarthGolemEntity.createAttributes().build());
        event.put(ModEntities.HUGE_SCORPION.get(), HugeScorpionEntity.createAttributes().build());
        event.put(ModEntities.WALKING_MUSHROOM.get(), WalkingMushroomEntity.createAttributes().build());
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
            event.accept(ModItems.EARTH_GOLEM_SPAWN_EGG);
            event.accept(ModItems.HUGE_SCORPION_SPAWN_EGG);
            event.accept(ModItems.WALKING_MUSHROOM_SPAWN_EGG);
        }
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModItems.SCORPION_BURROW);
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
