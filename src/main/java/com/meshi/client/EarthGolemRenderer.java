package com.meshi.client;

import com.meshi.entity.EarthGolemEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

public class EarthGolemRenderer extends GeoEntityRenderer<EarthGolemEntity> {
    public EarthGolemRenderer(EntityRendererProvider.Context context) {
        super(context, new EarthGolemModel());
        this.shadowRadius = 0.9F;
        // Glowing eyes: draws <texture>_glowmask.png at full brightness over the normal texture.
        addRenderLayer(new AutoGlowingGeoLayer<>(this));
    }
}
