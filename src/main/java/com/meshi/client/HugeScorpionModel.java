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

    /**
     * The "emerge" animation slides the root bone forward 20 units. While emerging, the scorpion
     * itself walks out of the wall instead (so its hitbox comes with it), so that slide is cancelled
     * here. Without this the model would travel twice as far as the entity and snap back at the end.
     * The little side-to-side wobble in the animation is kept.
     */
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
