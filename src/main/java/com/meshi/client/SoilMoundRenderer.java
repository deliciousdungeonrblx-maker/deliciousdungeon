package com.meshi.client;

import com.meshi.crop.GolemCrop;
import com.meshi.entity.SoilMoundEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

public class SoilMoundRenderer extends GeoEntityRenderer<SoilMoundEntity> {
    private static final int FULL_BRIGHT = 15728880;
    private final ItemStack clock = new ItemStack(Items.CLOCK);

    // where the planted items lie, in blocks from the mound origin (geckolib turns this model 180 deg so z is flipped vs blockbench)
    // 2x2 spots on the grass patch on top of the mound, if they look off in game only change these numbers
    // slot i = the i-th thing planted. x = left/right, z = front/back
    private static final float[] SLOT_X = {-0.30F, 0.30F, -0.30F, 0.30F};
    private static final float[] SLOT_Z = {0.54F, 0.54F, 0.93F, 0.93F};
    // height of the grass patch above the ground + a little so the item doesnt sink into it
    private static final float ITEM_Y = 0.50F;
    private static final float ITEM_SCALE = 0.55F;

    public SoilMoundRenderer(EntityRendererProvider.Context context) {
        super(context, new SoilMoundModel());
        this.shadowRadius = 0.0F;
    }

    @Override
    public void render(SoilMoundEntity mound, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight) {
        super.render(mound, entityYaw, partialTick, poseStack, bufferSource, packedLight);
        drawPlantedItems(mound, poseStack, bufferSource, packedLight);
        drawLabel(mound, partialTick, poseStack, bufferSource);
    }

    // the planted items lying flat on the mound (like item frames), the painted flowers stay under them
    private void drawPlantedItems(SoilMoundEntity mound, PoseStack poseStack, MultiBufferSource bufferSource,
                                  int packedLight) {
        List<GolemCrop> crops = mound.getPlantedClient();
        if (crops.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        for (int i = 0; i < crops.size() && i < SLOT_X.length; i++) {
            ItemStack stack = crops.get(i).plantStack();
            if (stack.isEmpty()) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(SLOT_X[i], ITEM_Y, SLOT_Z[i]);
            // item faces +z by default, tip it so the front faces up
            poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
            poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);
            mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.GROUND, packedLight, OverlayTexture.NO_OVERLAY,
                    poseStack, bufferSource, mc.level, mound.getId() + i);
            poseStack.popPose();
        }
    }

    // small clock w/ [m:ss] above it (+ the golems name above that if it has one), always faces the camera
    private void drawLabel(SoilMoundEntity mound, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource) {
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

        String time = String.format("[%d:%02d]", secs / 60, secs % 60);
        drawText(mound, Component.literal(time), 1.55D, partialTick, poseStack, bufferSource);
        if (mound.hasCustomName()) {
            drawText(mound, mound.getCustomName(), 1.85D, partialTick, poseStack, bufferSource);
        }
    }

    // uses vanilla's own name tag drawing (right facing, size, see-through pass) so it cant end up mirrored or culled
    // renderNameTag puts the text at the entitys name tag point + 0.5, so move the stack by the difference to land at y
    private void drawText(SoilMoundEntity mound, Component text, double y, float partialTick, PoseStack poseStack,
                          MultiBufferSource bufferSource) {
        Vec3 tag = mound.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, mound.getViewYRot(partialTick));
        if (tag == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(0.0D, y - (tag.y + 0.5D), 0.0D);
        renderNameTag(mound, text, poseStack, bufferSource, FULL_BRIGHT, partialTick);
        poseStack.popPose();
    }
}
