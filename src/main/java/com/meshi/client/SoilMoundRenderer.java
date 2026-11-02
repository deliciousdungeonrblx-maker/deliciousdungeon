package com.meshi.client;

import com.meshi.entity.SoilMoundEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SoilMoundRenderer extends GeoEntityRenderer<SoilMoundEntity> {
    public SoilMoundRenderer(EntityRendererProvider.Context context) {
        super(context, new SoilMoundModel());
        this.shadowRadius = 0.0F;
    }
}
