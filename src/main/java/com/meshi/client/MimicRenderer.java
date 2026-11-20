package com.meshi.client;

import com.meshi.entity.MimicEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class MimicRenderer extends GeoEntityRenderer<MimicEntity> {
    public MimicRenderer(EntityRendererProvider.Context context) {
        super(context, new MimicModel());
        this.shadowRadius = 0.0F;
    }

    // no shadow while its still posing as a chest, real chests dont have one
    @Override
    public void render(MimicEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        this.shadowRadius = entity.isAwake() ? 0.5F : 0.0F;
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }
}
