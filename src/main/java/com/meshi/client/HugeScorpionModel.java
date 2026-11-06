package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.entity.HugeScorpionEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;

public class HugeScorpionModel extends GeoModel<HugeScorpionEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "geo/entity/huge_scorpion.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "animations/entity/huge_scorpion.animation.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "textures/entity/huge_scorpion.png");

    @Override
    public ResourceLocation getModelResource(HugeScorpionEntity scorpion) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(HugeScorpionEntity scorpion) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(HugeScorpionEntity scorpion) {
        return ANIMATION;
    }

    // emerge anim slides teh root bone fwd 20 units but the entity walks out by itself, so the slide is cancelled here
    // otherwise the model goes twice as far n snaps back at the end. the side wobble stays
    @Override
    public void setCustomAnimations(HugeScorpionEntity scorpion, long instanceId, AnimationState<HugeScorpionEntity> state) {
        super.setCustomAnimations(scorpion, instanceId, state);

        if (scorpion.isEmerging()) {
            var root = getAnimationProcessor().getBone("root");
            if (root != null) {
                root.setPosZ(0.0F);
            }
        }
    }
}
