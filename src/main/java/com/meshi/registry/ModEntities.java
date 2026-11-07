package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.entity.EarthGolemEntity;
import com.meshi.entity.HugeScorpionEntity;
import com.meshi.entity.MushroomPotionEntity;
import com.meshi.entity.SoilMoundEntity;
import com.meshi.entity.WalkingMushroomEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Meshi.MODID);

    // hitbox 1.4 x 2.9 (moedl is hunched)
    public static final DeferredHolder<EntityType<?>, EntityType<EarthGolemEntity>> EARTH_GOLEM =
            ENTITY_TYPES.register("earth_golem", () ->
                    EntityType.Builder.<EarthGolemEntity>of(EarthGolemEntity::new, MobCategory.MONSTER)
                            .sized(1.4F, 2.9F)
                            .clientTrackingRange(10)
                            .build("earth_golem"));

    // model is drawn at 2x (MODEL_SCALE), hitbox is a square 2.4 wide 1.2 tall
    public static final DeferredHolder<EntityType<?>, EntityType<HugeScorpionEntity>> HUGE_SCORPION =
            ENTITY_TYPES.register("huge_scorpion", () ->
                    EntityType.Builder.<HugeScorpionEntity>of(HugeScorpionEntity::new, MobCategory.MONSTER)
                            .sized(2.4F, 1.2F)
                            .clientTrackingRange(10)
                            .build("huge_scorpion"));

    // hitbox 0.5 x 1.1
    public static final DeferredHolder<EntityType<?>, EntityType<WalkingMushroomEntity>> WALKING_MUSHROOM =
            ENTITY_TYPES.register("walking_mushroom", () ->
                    EntityType.Builder.<WalkingMushroomEntity>of(WalkingMushroomEntity::new, MobCategory.MONSTER)
                            .sized(0.5F, 1.1F)
                            .clientTrackingRange(8)
                            .build("walking_mushroom"));

    // mound is flat, this is just the clickable area in the middle, 2.0 x 0.6
    public static final DeferredHolder<EntityType<?>, EntityType<SoilMoundEntity>> SOIL_MOUND =
            ENTITY_TYPES.register("soil_mound", () ->
                    EntityType.Builder.<SoilMoundEntity>of(SoilMoundEntity::new, MobCategory.MISC)
                            .sized(2.0F, 0.6F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .fireImmune()
                            .build("soil_mound"));

    // thrown potion, small like the vanilla one
    public static final DeferredHolder<EntityType<?>, EntityType<MushroomPotionEntity>> MUSHROOM_POTION =
            ENTITY_TYPES.register("mushroom_potion", () ->
                    EntityType.Builder.<MushroomPotionEntity>of(MushroomPotionEntity::new, MobCategory.MISC)
                            .sized(0.25F, 0.25F)
                            .clientTrackingRange(4)
                            .updateInterval(10)
                            .build("mushroom_potion"));
}
