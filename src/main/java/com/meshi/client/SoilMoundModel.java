package com.meshi.client;

import com.meshi.Meshi;
import com.meshi.entity.SoilMoundEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class SoilMoundModel extends GeoModel<SoilMoundEntity> {
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "geo/entity/soil_mound.geo.json");
    private static final ResourceLocation ANIMATION =
            ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "animations/entity/soil_mound.animation.json");

    private static final ResourceLocation[] TEXTURES = new ResourceLocation[SoilMoundEntity.MAX_SEEDS + 1];

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            TEXTURES[i] = ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "textures/entity/soil_mound_" + i + ".png");
        }
    }

    @Override
    public ResourceLocation getModelResource(SoilMoundEntity mound) {
        return MODEL;
    }

    @Override
    public ResourceLocation getTextureResource(SoilMoundEntity mound) {
        int seeds = Math.max(0, Math.min(SoilMoundEntity.MAX_SEEDS, mound.getSeeds()));
        return TEXTURES[seeds];
    }

    @Override
    public ResourceLocation getAnimationResource(SoilMoundEntity mound) {
        return ANIMATION;
    }
}
