package com.meshi.registry;

import com.meshi.Meshi;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Meshi.MODID);

    public static final DeferredItem<DeferredSpawnEggItem> EARTH_GOLEM_SPAWN_EGG =
            ITEMS.register("earth_golem_spawn_egg", () ->
                    new DeferredSpawnEggItem(ModEntities.EARTH_GOLEM, 0x6B4A2F, 0x4E8F2F, new Item.Properties()));
}
