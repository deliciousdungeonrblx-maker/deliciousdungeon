package com.meshi.client;

import com.meshi.entity.WalkingMushroomEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class WalkingMushroomRenderer extends GeoEntityRenderer<WalkingMushroomEntity> {
    public WalkingMushroomRenderer(EntityRendererProvider.Context context) {
        super(context, new WalkingMushroomModel());
        this.shadowRadius = 0.3F;
    }
}
