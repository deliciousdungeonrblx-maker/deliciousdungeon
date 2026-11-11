package com.meshi.forest;

import com.meshi.forest.ForestDefs.Def;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

// parts of floor 2 you edited ingame. one saved block grid per part key, the generator stamps these
// instead of the generated look, so what you built is what you get
public class PartStore extends SavedData {
    public static final String NAME = "meshi_forest_parts";

    public static final class Template {
        final Def def;
        final List<BlockState> palette;
        final int[] cells;

        Template(Def def, List<BlockState> palette, int[] cells) {
            this.def = def;
            this.palette = palette;
            this.cells = cells;
        }

        // null = air
        BlockState at(int x, int y, int z) {
            BlockState s = this.palette.get(this.cells[(x * this.def.sy + y) * this.def.sz + z]);
            return s.isAir() ? null : s;
        }
    }

    private final Map<String, Template> parts = new HashMap<>();
    private final Set<String> builtPlots = new HashSet<>();

    public static PartStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PartStore::new, PartStore::load, null), NAME);
    }

    public boolean has(String key) {
        return this.parts.containsKey(key);
    }

    public Template template(String key) {
        return this.parts.get(key);
    }

    public void remove(String key) {
        this.parts.remove(key);
        this.builtPlots.remove(key);
        setDirty();
    }

    public boolean isPlotBuilt(String key) {
        return this.builtPlots.contains(key);
    }

    public void markPlot(String key) {
        this.builtPlots.add(key);
        setDirty();
    }

    // reads the whole plot box from the world
    public void capture(Def def, ServerLevel level, net.minecraft.core.BlockPos origin) {
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> index = new HashMap<>();
        int[] cells = new int[def.sx * def.sy * def.sz];
        net.minecraft.core.BlockPos.MutableBlockPos pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int x = 0; x < def.sx; x++) {
            for (int y = 0; y < def.sy; y++) {
                for (int z = 0; z < def.sz; z++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    BlockState s = level.getBlockState(pos);
                    Integer i = index.get(s);
                    if (i == null) {
                        i = palette.size();
                        palette.add(s);
                        index.put(s, i);
                    }
                    cells[(x * def.sy + y) * def.sz + z] = i;
                }
            }
        }
        this.parts.put(def.key, new Template(def, palette, cells));
        setDirty();
    }

    public static PartStore load(CompoundTag tag, HolderLookup.Provider provider) {
        PartStore store = new PartStore();
        CompoundTag all = tag.getCompound("Parts");
        for (String key : all.getAllKeys()) {
            Def def = ForestDefs.byKey(key);
            if (def == null) {
                continue;
            }
            CompoundTag r = all.getCompound(key);
            List<BlockState> palette = new ArrayList<>();
            for (Tag t : r.getList("Palette", Tag.TAG_COMPOUND)) {
                palette.add(NbtUtils.readBlockState(provider.lookupOrThrow(Registries.BLOCK), (CompoundTag) t));
            }
            int n = def.sx * def.sy * def.sz;
            int[] cells;
            if (r.contains("Bytes", Tag.TAG_BYTE_ARRAY)) {
                byte[] b = r.getByteArray("Bytes");
                cells = new int[b.length];
                for (int i = 0; i < b.length; i++) {
                    cells[i] = b[i] & 0xFF;
                }
            } else {
                cells = r.getIntArray("Cells");
            }
            if (palette.isEmpty() || cells.length != n) {
                continue;
            }
            store.parts.put(key, new Template(def, palette, cells));
        }
        for (Tag t : tag.getList("Plots", Tag.TAG_STRING)) {
            store.builtPlots.add(t.getAsString());
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<String, Template> e : this.parts.entrySet()) {
            CompoundTag r = new CompoundTag();
            ListTag palette = new ListTag();
            for (BlockState s : e.getValue().palette) {
                palette.add(NbtUtils.writeBlockState(s));
            }
            r.put("Palette", palette);
            int[] cells = e.getValue().cells;
            if (e.getValue().palette.size() <= 256) {
                byte[] b = new byte[cells.length];
                for (int i = 0; i < b.length; i++) {
                    b[i] = (byte) cells[i];
                }
                r.putByteArray("Bytes", b);
            } else {
                r.putIntArray("Cells", cells);
            }
            all.put(e.getKey(), r);
        }
        tag.put("Parts", all);
        ListTag plots = new ListTag();
        for (String p : this.builtPlots) {
            plots.add(StringTag.valueOf(p));
        }
        tag.put("Plots", plots);
        return tag;
    }
}
