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
import net.minecraft.world.level.block.Blocks;
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
            "golem", "slime_room", "arrow_gallery", "scorpion_room", "round_room",
            // NEW ONES GO AT THE END ONLY. the plot position of a room is its index in this list
            "safezone", "side_room", "special_room",
            // the 3x3 trap room, saved as one 33 x 8 x 33 grid
            "trap_room");

    public static final class Room {
        final List<BlockState> palette;
        final int[] cells;
        // cell index -> loot pool name, for chests / barrels you bound to a pool
        final Map<Integer, String> pools;
        // blocks wide and deep (11, or 22 for the big trap rooms)
        final int size;

        Room(List<BlockState> palette, int[] cells, Map<Integer, String> pools, int size) {
            this.palette = palette;
            this.cells = cells;
            this.pools = pools;
            this.size = size;
        }

        String poolAt(int x, int rel, int z) {
            return this.pools.get((x * DungeonBuilder.FLOOR_H + rel) * this.size + z);
        }

        BlockState get(int x, int rel, int z) {
            return this.palette.get(this.cells[(x * DungeonBuilder.FLOOR_H + rel) * this.size + z]);
        }
    }

    private final Map<String, Room> rooms = new HashMap<>();
    private final Set<String> builtPlots = new HashSet<>();
    // chests / barrels in edit plots that you bound to a loot pool (block pos as long -> pool)
    private final Map<Long, String> plotPools = new HashMap<>();

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
        if (type == Type.SAFEZONE) return "safezone";
        if (type == Type.SIDE_ROOM) return "side_room";
        if (type == Type.SPECIAL) return "special_room";
        if (type == Type.TRAP_PART) return "trap_room";
        return null;
    }

    // one entrance rooms are saved with the door on the north side and get turned to fit
    public static boolean singleEntrance(String key) {
        return key.equals("golem") || key.startsWith("dead_end") || key.equals("side_room") || key.equals("special_room");
    }

    public static boolean isBig(String key) {
        return key.equals("trap_room");
    }

    // width / depth of a saved room in blocks
    public static int sizeOf(String key) {
        return isBig(key) ? 3 * DungeonBuilder.TILE : DungeonBuilder.TILE;
    }

    // the 4 old trap rooms are gone. they stay in KEYS (the plot spot of every room is its index) but cant be edited
    // or generated, their saves just stay in the world data
    public static boolean unused(String key) {
        return key.equals("slime_room") || key.equals("arrow_gallery") || key.equals("scorpion_room") || key.equals("round_room");
    }

    public static List<String> editable() {
        return KEYS.stream().filter(k -> !unused(k)).toList();
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

    // ---- loot bindings inside edit plots

    public void bindPlot(BlockPos pos, String pool) {
        this.plotPools.put(pos.asLong(), pool);
        setDirty();
    }

    public boolean unbindPlot(BlockPos pos) {
        boolean had = this.plotPools.remove(pos.asLong()) != null;
        if (had) {
            setDirty();
        }
        return had;
    }

    public String plotPool(BlockPos pos) {
        return this.plotPools.get(pos.asLong());
    }

    // forget the bindings of one plot (before it is rebuilt)
    public void clearPlotBindings(BlockPos origin, int size) {
        this.plotPools.keySet().removeIf(l -> {
            BlockPos p = BlockPos.of(l);
            return p.getX() >= origin.getX() && p.getX() < origin.getX() + size
                    && p.getZ() >= origin.getZ() && p.getZ() < origin.getZ() + size;
        });
        setDirty();
    }

    // reads the 11 x 8 x 11 block of the plot from the world
    public void capture(String key, ServerLevel level, BlockPos origin) {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int size = sizeOf(key);
        int[] cells = new int[size * DungeonBuilder.FLOOR_H * size];
        for (int x = 0; x < size; x++) {
            for (int rel = 0; rel < DungeonBuilder.FLOOR_H; rel++) {
                for (int z = 0; z < size; z++) {
                    BlockState s = level.getBlockState(origin.offset(x, rel, z));
                    Integer i = index.get(s);
                    if (i == null) {
                        i = palette.size();
                        palette.add(s);
                        index.put(s, i);
                    }
                    cells[(x * DungeonBuilder.FLOOR_H + rel) * size + z] = i;
                }
            }
        }
        Map<Integer, String> pools = new HashMap<>();
        for (int x = 0; x < size; x++) {
            for (int rel = 0; rel < DungeonBuilder.FLOOR_H; rel++) {
                for (int z = 0; z < size; z++) {
                    String pool = this.plotPools.get(origin.offset(x, rel, z).asLong());
                    BlockState here = palette.get(cells[(x * DungeonBuilder.FLOOR_H + rel) * size + z]);
                    boolean container = here.is(Blocks.CHEST) || here.is(Blocks.BARREL) || here.is(Blocks.TRAPPED_CHEST);
                    if (pool != null && container) {
                        pools.put((x * DungeonBuilder.FLOOR_H + rel) * size + z, pool);
                    }
                }
            }
        }
        this.rooms.put(key, new Room(palette, cells, pools, size));
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
            int size = sizeOf(key);
            if (palette.isEmpty() || cells.length != size * DungeonBuilder.FLOOR_H * size) {
                continue;
            }
            Map<Integer, String> pools = new HashMap<>();
            for (Tag t : r.getList("Pools", Tag.TAG_COMPOUND)) {
                CompoundTag pc = (CompoundTag) t;
                pools.put(pc.getInt("Cell"), pc.getString("Pool"));
            }
            store.rooms.put(key, new Room(palette, cells, pools, size));
        }
        CompoundTag pp = tag.getCompound("PlotPools");
        for (String k : pp.getAllKeys()) {
            try {
                store.plotPools.put(Long.parseLong(k), pp.getString(k));
            } catch (NumberFormatException ignored) {
            }
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
            ListTag pools = new ListTag();
            for (Map.Entry<Integer, String> pe : e.getValue().pools.entrySet()) {
                CompoundTag pc = new CompoundTag();
                pc.putInt("Cell", pe.getKey());
                pc.putString("Pool", pe.getValue());
                pools.add(pc);
            }
            r.put("Pools", pools);
            all.put(e.getKey(), r);
        }
        tag.put("Rooms", all);
        CompoundTag pp = new CompoundTag();
        for (Map.Entry<Long, String> e : this.plotPools.entrySet()) {
            pp.putString(Long.toString(e.getKey()), e.getValue());
        }
        tag.put("PlotPools", pp);
        ListTag plots = new ListTag();
        for (String p : this.builtPlots) {
            plots.add(StringTag.valueOf(p));
        }
        tag.put("Plots", plots);
        return tag;
    }
}
