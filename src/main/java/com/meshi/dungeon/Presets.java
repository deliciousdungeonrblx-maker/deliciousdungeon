package com.meshi.dungeon;

import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.slf4j.Logger;

// rooms + loot pools shipped inside the jar. just drop the .dat files from a world's dungeon data folder into
// src/main/resources/data/meshi/presets/  (meshi_rooms.dat, meshi_loot.dat). either file is optional
// worlds use these when they have no version of their own
final class Presets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String DIR = "/data/meshi/presets/";

    private static boolean loaded = false;
    private static HolderLookup.Provider provider = null;
    private static RoomStore rooms = null;
    private static CompoundTag loot = new CompoundTag();

    private Presets() {
    }

    // reads the files once
    static synchronized void init(HolderLookup.Provider p) {
        if (loaded) {
            return;
        }
        loaded = true;
        provider = p;
        CompoundTag r = read("meshi_rooms.dat");
        if (r != null) {
            rooms = RoomStore.load(r, p);
        }
        CompoundTag l = read("meshi_loot.dat");
        if (l != null) {
            loot = l;
        }
    }

    static RoomStore.Room room(String key) {
        return rooms == null ? null : rooms.own(key);
    }

    // fresh copies every call, pools get edited
    static Map<String, LootStore.Pool> lootPools() {
        Map<String, LootStore.Pool> out = new LinkedHashMap<>();
        if (provider == null) {
            return out;
        }
        CompoundTag all = loot.getCompound("Pools");
        for (String key : all.getAllKeys()) {
            out.put(key, LootStore.readPool(all.getCompound(key), provider));
        }
        return out;
    }

    private static CompoundTag read(String name) {
        try (InputStream in = Presets.class.getResourceAsStream(DIR + name)) {
            if (in == null) {
                return null;
            }
            CompoundTag root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
            return root.contains("data", Tag.TAG_COMPOUND) ? root.getCompound("data") : root;
        } catch (Exception e) {
            LOGGER.error("could not read preset {}", name, e);
            return null;
        }
    }
}
