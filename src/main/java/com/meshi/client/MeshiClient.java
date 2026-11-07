package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.registry.ModEntities;
import com.meshi.registry.ModParticles;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid = Meshi.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MeshiClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.EARTH_GOLEM.get(), EarthGolemRenderer::new);
        event.registerEntityRenderer(ModEntities.SOIL_MOUND.get(), SoilMoundRenderer::new);
        event.registerEntityRenderer(ModEntities.HUGE_SCORPION.get(), HugeScorpionRenderer::new);
        event.registerEntityRenderer(ModEntities.WALKING_MUSHROOM.get(), WalkingMushroomRenderer::new);
        event.registerEntityRenderer(ModEntities.MUSHROOM_POTION.get(), ThrownItemRenderer::new);
    }

    @SubscribeEvent
    public static void registerParticles(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.ZZZ.get(), ZzzParticle.Provider::new);
    }
}
