package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.entity.WalkingMushroomEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class WalkingMushroomModel extends GeoModel<WalkingMushroomEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "geo/entity/walking_mushroom.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "animations/entity/walking_mushroom.animation.json");
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "textures/entity/walking_mushroom.png");

    @Override
    public ResourceLocation getModelResource(WalkingMushroomEntity mushroom) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(WalkingMushroomEntity mushroom) {
        return TEXTURE;
    }

    @Override
    public ResourceLocation getAnimationResource(WalkingMushroomEntity mushroom) {
        return ANIMATION;
    }
}
