package com.meshi.client;

import com.meshi.entity.EarthGolemEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class EarthGolemRenderer extends GeoEntityRenderer<EarthGolemEntity> {
    public EarthGolemRenderer(EntityRendererProvider.Context context) {
        super(context, new EarthGolemModel());
        this.shadowRadius = 0.9F;
        // glowing eyes, uses <texture>_glowmask.png
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
        // the grown crops it carries on its back
        addRenderLayer(new GolemCropsLayer(this));
    }
}
