package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = Meshi.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class MeshiClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.EARTH_GOLEM.get(), EarthGolemRenderer::new);
        event.registerEntityRenderer(ModEntities.SOIL_MOUND.get(), SoilMoundRenderer::new);
    }
}
