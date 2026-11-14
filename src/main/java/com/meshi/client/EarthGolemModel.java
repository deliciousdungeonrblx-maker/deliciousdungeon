package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.entity.EarthGolemEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

public class EarthGolemModel extends GeoModel<EarthGolemEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "geo/entity/earth_golem.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "animations/entity/earth_golem.animation.json");
    private static final ResourceLocation TEXTURE_NORMAL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "textures/entity/earth_golem_normal.png");

    private static final float MAX_HEAD_YAW = 45.0F;
    private static final float MAX_HEAD_PITCH = 30.0F;

    @Override
    public ResourceLocation getModelResource(EarthGolemEntity golem) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(EarthGolemEntity golem) {
        // always the normal texture, the crops are drawn as items on its back (GolemCropsLayer)
        return TEXTURE_NORMAL;
    }

    @Override
    public ResourceLocation getAnimationResource(EarthGolemEntity golem) {
        return ANIMATION;
    }

    @Override
    public void setCustomAnimations(EarthGolemEntity golem, long instanceId, AnimationState<EarthGolemEntity> state) {
        super.setCustomAnimations(golem, instanceId, state);

        var head = getAnimationProcessor().getBone("head");
        if (head == null || golem.isRising()) {
            return;
        }

        EntityModelData data = state.getData(DataTickets.ENTITY_MODEL_DATA);
        float yaw = Mth.clamp(data.netHeadYaw(), -MAX_HEAD_YAW, MAX_HEAD_YAW);
        float pitch = Mth.clamp(data.headPitch(), -MAX_HEAD_PITCH, MAX_HEAD_PITCH);

        head.setRotX(head.getRotX() + pitch * Mth.DEG_TO_RAD);
        head.setRotY(head.getRotY() + yaw * Mth.DEG_TO_RAD);
    }
}
