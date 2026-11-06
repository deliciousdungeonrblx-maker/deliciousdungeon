package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.entity.EarthGolemEntity;
import com.meshi.entity.HugeScorpionEntity;
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

    // Hitbox is 1.4 wide x 2.9 tall (the model is hunched, so a bit under its 3 block T-pose height).
    public static final DeferredHolder<EntityType<?>, EntityType<EarthGolemEntity>> EARTH_GOLEM =
            ENTITY_TYPES.register("earth_golem", () ->
                    EntityType.Builder.<EarthGolemEntity>of(EarthGolemEntity::new, MobCategory.MONSTER)
                            .sized(1.4F, 2.9F)
                            .clientTrackingRange(10)
                            .build("earth_golem"));

    // The model is about 1.1 x 1.5 x 0.56 blocks and is drawn at 2x (HugeScorpionEntity.MODEL_SCALE), so
    // about 2.2 wide, 3 long and 1.1 tall. The hitbox is a square: 2.4 wide x 1.2 tall.
    public static final DeferredHolder<EntityType<?>, EntityType<HugeScorpionEntity>> HUGE_SCORPION =
            ENTITY_TYPES.register("huge_scorpion", () ->
                    EntityType.Builder.<HugeScorpionEntity>of(HugeScorpionEntity::new, MobCategory.MONSTER)
                            .sized(2.4F, 1.2F)
                            .clientTrackingRange(10)
                            .build("huge_scorpion"));

    // The model is 0.5 wide and about 1.1 tall (17.75 pixels), so the hitbox is 0.5 x 1.1.
    public static final DeferredHolder<EntityType<?>, EntityType<WalkingMushroomEntity>> WALKING_MUSHROOM =
            ENTITY_TYPES.register("walking_mushroom", () ->
                    EntityType.Builder.<WalkingMushroomEntity>of(WalkingMushroomEntity::new, MobCategory.MONSTER)
                            .sized(0.5F, 1.1F)
                            .clientTrackingRange(8)
                            .build("walking_mushroom"));

    // The mound model lies flat, about 3 blocks wide and under half a block tall. This is the
    // clickable area in its middle: 2.0 wide, 0.6 tall.
    public static final DeferredHolder<EntityType<?>, EntityType<SoilMoundEntity>> SOIL_MOUND =
            ENTITY_TYPES.register("soil_mound", () ->
                    EntityType.Builder.<SoilMoundEntity>of(SoilMoundEntity::new, MobCategory.MISC)
                            .sized(2.0F, 0.6F)
                            .clientTrackingRange(8)
                            .updateInterval(20)
                            .fireImmune()
                            .build("soil_mound"));
}
