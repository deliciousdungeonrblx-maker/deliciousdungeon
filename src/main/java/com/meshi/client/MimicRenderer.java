package com.meshi.client;

import com.meshi.entity.MimicEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class MimicRenderer extends GeoEntityRenderer<MimicEntity> {
    public MimicRenderer(EntityRendererProvider.Context context) {
        super(context, new MimicModel());
        this.shadowRadius = 0.5F;
    }
}
