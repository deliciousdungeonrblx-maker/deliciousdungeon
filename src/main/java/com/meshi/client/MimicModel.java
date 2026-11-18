package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.entity.MimicEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class MimicModel extends GeoModel<MimicEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "geo/entity/mimic.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "animations/entity/mimic.animation.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "textures/entity/mimic.png");

    @Override
    public ResourceLocation getModelResource(MimicEntity mimic) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(MimicEntity mimic) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(MimicEntity mimic) {
        return ANIMATION;
    }
}
