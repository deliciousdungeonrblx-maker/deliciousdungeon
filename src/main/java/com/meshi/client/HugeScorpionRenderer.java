package com.meshi.client;

import com.meshi.entity.HugeScorpionEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class HugeScorpionRenderer extends GeoEntityRenderer<HugeScorpionEntity> {
    public HugeScorpionRenderer(EntityRendererProvider.Context context) {
        super(context, new HugeScorpionModel());
        // The model is about 1.1 blocks wide and 1.5 long, so it is drawn bigger to make it "huge".
        // The size lives in HugeScorpionEntity.MODEL_SCALE. If you change it, change the hitbox in
        // ModEntities.HUGE_SCORPION and the emerge distances in HugeScorpionEntity to match.
        this.withScale(HugeScorpionEntity.MODEL_SCALE);
        this.shadowRadius = 1.0F;
    }
}
