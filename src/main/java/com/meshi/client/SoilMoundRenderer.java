package com.meshi.client;

import com.meshi.entity.SoilMoundEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SoilMoundRenderer extends GeoEntityRenderer<SoilMoundEntity> {
    private static final int FULL_BRIGHT = 15728880;
    private final ItemStack clock = new ItemStack(Items.CLOCK);

    public SoilMoundRenderer(EntityRendererProvider.Context context) {
        super(context, new SoilMoundModel());
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(SoilMoundEntity mound, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(mound, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        drawLabel(mound, poseStack, bufferSource);
    }

    // small clock w/ [m:ss] above it (+ the golems name above that if it has one), always faces the camera
    private void drawLabel(SoilMoundEntity mound, PoseStack poseStack, MultiBufferSource bufferSource) {
        int secs = mound.getTimerSeconds();
        if (secs < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        poseStack.pushPose();
        poseStack.translate(0.0D, 1.0D, 0.0D);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(0.6F, 0.6F, 0.6F);
        mc.getItemRenderer().renderStatic(this.clock, ItemDisplayContext.GROUND, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                poseStack, bufferSource, mc.level, mound.getId());
        poseStack.popPose();

        Font font = this.getFont();
        String time = String.format("[%d:%02d]", secs / 60, secs % 60);
        drawText(font, Component.literal(time), 1.55D, poseStack, bufferSource);
        if (mound.hasCustomName()) {
            drawText(font, mound.getCustomName(), 1.85D, poseStack, bufferSource);
        }
    }

    private void drawText(Font font, Component text, double y, PoseStack poseStack, MultiBufferSource bufferSource) {
        poseStack.pushPose();
        poseStack.translate(0.0D, y, 0.0D);
        poseStack.mulPose(this.entityRenderDispatcher.cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();
        float x = -font.width(text) / 2.0F;
        font.drawInBatch(text, x, 0.0F, 0xFFFFFFFF, false, matrix, bufferSource, Font.DisplayMode.NORMAL,
                0x40000000, FULL_BRIGHT);
        poseStack.popPose();
    }
}
