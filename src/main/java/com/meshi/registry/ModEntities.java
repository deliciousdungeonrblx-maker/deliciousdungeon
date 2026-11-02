package com.meshi.registry;

import com.meshi.Meshi;
import com.meshi.entity.EarthGolemEntity;
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
}
