package com.meshi.client;

import com.meshi.entity.HugeScorpionEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class HugeScorpionRenderer extends GeoEntityRenderer<HugeScorpionEntity> {
    public HugeScorpionRenderer(EntityRendererProvider.Context context) {
        super(context, new HugeScorpionModel());
        // drawn bigger on purpose, size is HugeScorpionEntity.MODEL_SCALE. if u change it update the hitbox in ModEntities n the emerge distances
        this.withScale(HugeScorpionEntity.MODEL_SCALE);
        this.shadowRadius = 1.0F;
    }
}
