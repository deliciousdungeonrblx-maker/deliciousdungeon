package com.meshi.registry;

import com.meshi.Meshi;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The walking mushroom's sounds. The .ogg files live in assets/meshi/sounds/mushroom/ and are wired up in
 * assets/meshi/sounds.json: swap a file there (same name) to change how something sounds.
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Meshi.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_SQUEAK = register("mushroom_squeak");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_HURT = register("mushroom_hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_POP = register("mushroom_pop");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_SNORE = register("mushroom_snore");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_YAWN = register("mushroom_yawn");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_HAPPY = register("mushroom_happy");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_STEP = register("mushroom_step");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_SLICE = register("mushroom_slice");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_ALARM = register("mushroom_alarm");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_TRIP = register("mushroom_trip");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_BONK = register("mushroom_bonk");
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSHROOM_ATTACK = register("mushroom_attack");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name, () ->
                SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Meshi.MODID, name)));
    }
}
