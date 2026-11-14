package com.meshi.client;

import com.meshi.crop.GolemCrop;
import com.meshi.entity.EarthGolemEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

// the grown crops (cabbage etc) carried on the golems back, they follow the body bone so they move with the rise animation
public class GolemCropsLayer extends GeoRenderLayer<EarthGolemEntity> {
    // spots on the back in model pixels (x/y/z like in blockbench, +z = back, body is x -10.4..10.4, y 19..43, z up to 8.4)
    // 2x2: lower row then upper row. if they look off in game only change these
    private static final float[] SLOT_X = {-5.5F, 5.5F, -5.5F, 5.5F};
    private static final float[] SLOT_Y = {27.0F, 27.0F, 37.0F, 37.0F};
    private static final float BACK_Z = 9.6F;
    private static final float ITEM_SCALE = 1.0F;

    public GolemCropsLayer(GeoRenderer<EarthGolemEntity> renderer) {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, EarthGolemEntity golem, GeoBone bone, RenderType renderType,
                              MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
                              int packedLight, int packedOverlay) {
        if (!"body".equals(bone.getName())) {
            return;
        }
        List<GolemCrop> crops = golem.getCropsClient();
        if (crops.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < crops.size() && i < SLOT_X.length; i++) {
            ItemStack stack = crops.get(i).finalStack();
            if (stack.isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            // the stack is already in the bodys space with its animation applied, geckolib flips x (symmetric spots so no matter)
            poseStack.translate(-SLOT_X[i] / 16.0F, SLOT_Y[i] / 16.0F, BACK_Z / 16.0F);
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            // item front faces +z = out of its back
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.GROUND, packedLight, packedOverlay,
                    poseStack, bufferSource, mc.level, golem.getId() + i);
            poseStack.popPose();
        }
    }
}
