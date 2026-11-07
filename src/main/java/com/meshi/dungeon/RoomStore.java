package com.meshi.dungeon;

import com.meshi.dungeon.DungeonLayout.Tile;
import com.meshi.dungeon.DungeonLayout.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

// rooms the user edited ingame. one saved block grid (11 x 8 x 11) per room key.
// the builder stamps these over the generated tile, so what you built is what you get
public class RoomStore extends SavedData {
    public static final String NAME = "meshi_rooms";

    // every room that can be edited. stairs / arrival are not in here (the steps cross 2 floors)
    public static final List<String> KEYS = List.of(
            "spawn", "hall_0", "hall_1", "hall_2", "hall_3",
            "dead_end_0", "dead_end_1", "dead_end_2", "dead_end_3",
            "golem", "slime_room", "arrow_gallery", "scorpion_room", "round_room");

    public static final class Room {
        final List<BlockState> palette;
        final int[] cells;

        Room(List<BlockState> palette, int[] cells) {
            this.palette = palette;
            this.cells = cells;
        }

        BlockState get(int x, int rel, int z) {
            return this.palette.get(this.cells[(x * DungeonBuilder.FLOOR_H + rel) * DungeonBuilder.TILE + z]);
        }
    }

    private final Map<String, Room> rooms = new HashMap<>();
    private final Set<String> builtPlots = new HashSet<>();

    public static RoomStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RoomStore::new, RoomStore::load, null), NAME);
    }

    // key for a tile, null = not editable
    public static String keyOf(Tile t) {
        Type type = t.type;
        if (type == Type.SPAWN) return "spawn";
        if (type == Type.HALL) return "hall_" + t.variant;
        if (type == Type.DEAD_END) return "dead_end_" + t.variant;
        if (type == Type.GOLEM) return "golem";
        if (type == Type.SLIME_ROOM) return "slime_room";
        if (type == Type.ARROW_GALLERY) return "arrow_gallery";
        if (type == Type.SCORPION_ROOM) return "scorpion_room";
        if (type == Type.ROUND_ROOM) return "round_room";
        return null;
    }

    // one entrance rooms are saved with the door on the north side and get turned to fit
    public static boolean singleEntrance(String key) {
        return key.equals("golem") || key.startsWith("dead_end");
    }

    public boolean has(String key) {
        return this.rooms.containsKey(key);
    }

    public Room get(String key) {
        return this.rooms.get(key);
    }

    public void remove(String key) {
        this.rooms.remove(key);
        setDirty();
    }

    public boolean isPlotBuilt(String key) {
        return this.builtPlots.contains(key);
    }

    public void markPlot(String key) {
        this.builtPlots.add(key);
        setDirty();
    }

    // reads the 11 x 8 x 11 block of the plot from the world
    public void capture(String key, ServerLevel level, BlockPos origin) {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[DungeonBuilder.TILE * DungeonBuilder.FLOOR_H * DungeonBuilder.TILE];
        for (int x = 0; x < DungeonBuilder.TILE; x++) {
            for (int rel = 0; rel < DungeonBuilder.FLOOR_H; rel++) {
                for (int z = 0; z < DungeonBuilder.TILE; z++) {
                    BlockState s = level.getBlockState(origin.offset(x, rel, z));
                    Integer i = index.get(s);
                    if (i == null) {
                        i = palette.size();
                        palette.add(s);
                        index.put(s, i);
                    }
                    cells[(x * DungeonBuilder.FLOOR_H + rel) * DungeonBuilder.TILE + z] = i;
                }
            }
        }
        this.rooms.put(key, new Room(palette, cells));
        setDirty();
    }

    public static RoomStore load(CompoundTag tag, HolderLookup.Provider provider) {
        RoomStore store = new RoomStore();
        CompoundTag all = tag.getCompound("Rooms");
        for (String key : all.getAllKeys()) {
            CompoundTag r = all.getCompound(key);
            List<BlockState> palette = new ArrayList<>();
            for (Tag t : r.getList("Palette", Tag.TAG_COMPOUND)) {
                palette.add(NbtUtils.readBlockState(provider.lookupOrThrow(Registries.BLOCK), (CompoundTag) t));
            }
            int[] cells = r.getIntArray("Cells");
            if (palette.isEmpty() || cells.length != DungeonBuilder.TILE * DungeonBuilder.FLOOR_H * DungeonBuilder.TILE) {
                continue;
            }
            store.rooms.put(key, new Room(palette, cells));
        }
        for (Tag t : tag.getList("Plots", Tag.TAG_STRING)) {
            store.builtPlots.add(t.getAsString());
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<String, Room> e : this.rooms.entrySet()) {
            CompoundTag r = new CompoundTag();
            ListTag palette = new ListTag();
            for (BlockState s : e.getValue().palette) {
                palette.add(NbtUtils.writeBlockState(s));
            }
            r.put("Palette", palette);
            r.putIntArray("Cells", e.getValue().cells);
            all.put(e.getKey(), r);
        }
        tag.put("Rooms", all);
        ListTag plots = new ListTag();
        for (String p : this.builtPlots) {
            plots.add(StringTag.valueOf(p));
        }
        tag.put("Plots", plots);
        return tag;
    }
}
