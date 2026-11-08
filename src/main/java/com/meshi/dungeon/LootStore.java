package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;

// loot pools u edit ingame. one pool = one 6 row chest, row 1 common -> row 6 rare
// stack size = max amount per roll. floor_1/2/3 are used by that floors chests
public class LootStore extends SavedData {
    public static final String NAME = "meshi_loot";
    public static final int SLOTS = 54;
    // chance weight of row 1..6
    private static final int[] ROW_WEIGHTS = {40, 28, 18, 9, 4, 1};

    public static final class Pool {
        public final ItemStack[] slots = new ItemStack[SLOTS];
        public int minRolls = 3;
        public int maxRolls = 5;

        Pool() {
            java.util.Arrays.fill(this.slots, ItemStack.EMPTY);
        }
    }

    private final Map<String, Pool> pools = new LinkedHashMap<>();

    public LootStore() {
        ensureDefaults();
    }

    public static LootStore get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(LootStore::new, LootStore::load, null), NAME);
    }

    // ---- pools

    public List<String> names() {
        return new ArrayList<>(this.pools.keySet());
    }

    public boolean has(String name) {
        return this.pools.containsKey(name);
    }

    public Pool getOrCreate(String name) {
        Pool p = this.pools.get(name);
        if (p == null) {
            p = new Pool();
            this.pools.put(name, p);
            setDirty();
        }
        return p;
    }

    public Pool get(String name) {
        return this.pools.get(name);
    }

    public void setRolls(String name, int min, int max) {
        Pool p = getOrCreate(name);
        p.minRolls = Math.max(0, Math.min(min, max));
        p.maxRolls = Math.max(p.minRolls, Math.min(54, max));
        setDirty();
    }

    // back to the built in version, or gone if it was a pool you made yourself
    public boolean resetPool(String name) {
        Pool def = defaultPools().get(name);
        if (def == null) {
            return this.pools.remove(name) != null && markDirty();
        }
        this.pools.put(name, def);
        return markDirty();
    }

    private boolean markDirty() {
        setDirty();
        return true;
    }

    private void ensureDefaults() {
        for (Map.Entry<String, Pool> e : defaultPools().entrySet()) {
            this.pools.putIfAbsent(e.getKey(), e.getValue());
        }
    }

    // ---- filling a container

    // fills a chest / barrel from a pool. false if there is no container there or no pool
    public boolean fill(ServerLevel level, BlockPos pos, String poolName, Random rng) {
        Pool pool = this.pools.get(poolName);
        if (pool == null || !(level.getBlockEntity(pos) instanceof Container c)) {
            return false;
        }
        c.clearContent();
        List<List<ItemStack>> rows = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            List<ItemStack> row = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ItemStack s = pool.slots[r * 9 + i];
                if (!s.isEmpty()) {
                    row.add(s);
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
                weights.add(ROW_WEIGHTS[r]);
            }
        }
        if (rows.isEmpty()) {
            c.setChanged();
            return true;
        }
        int total = 0;
        for (int w : weights) {
            total += w;
        }
        int rolls = pool.minRolls + rng.nextInt(Math.max(1, pool.maxRolls - pool.minRolls + 1));
        for (int n = 0; n < rolls; n++) {
            int pick = rng.nextInt(total);
            int ri = 0;
            while (pick >= weights.get(ri)) {
                pick -= weights.get(ri);
                ri++;
            }
            List<ItemStack> row = rows.get(ri);
            ItemStack src = row.get(rng.nextInt(row.size()));
            ItemStack give = src.copy();
            give.setCount(1 + rng.nextInt(Math.max(1, src.getCount())));
            int size = c.getContainerSize();
            int slot = rng.nextInt(size);
            for (int tries = 0; tries < size && !c.getItem(slot).isEmpty(); tries++) {
                slot = (slot + 1) % size;
            }
            if (c.getItem(slot).isEmpty()) {
                c.setItem(slot, give);
            }
        }
        c.setChanged();
        return true;
    }

    // ---- the editor window

    public void openEditor(ServerPlayer player, String name) {
        Pool pool = getOrCreate(name);
        LootStore store = this;
        SimpleContainer box = new SimpleContainer(SLOTS) {
            @Override
            public void stopOpen(Player p) {
                super.stopOpen(p);
                for (int i = 0; i < SLOTS; i++) {
                    pool.slots[i] = getItem(i).copy();
                }
                store.setDirty();
            }
        };
        for (int i = 0; i < SLOTS; i++) {
            box.setItem(i, pool.slots[i].copy());
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.sixRows(id, inv, box),
                Component.literal("loot: " + name + "  (row 1 common ... row 6 rare)")));
    }

    // ---- save / load

    public static LootStore load(CompoundTag tag, HolderLookup.Provider provider) {
        LootStore store = new LootStore();
        CompoundTag all = tag.getCompound("Pools");
        for (String key : all.getAllKeys()) {
            CompoundTag c = all.getCompound(key);
            Pool pool = new Pool();
            pool.minRolls = c.getInt("Min");
            pool.maxRolls = c.getInt("Max");
            for (Tag t : c.getList("Slots", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) t;
                int slot = s.getInt("Slot");
                if (slot >= 0 && slot < SLOTS) {
                    pool.slots[slot] = ItemStack.parseOptional(provider, s.getCompound("Item"));
                }
            }
            store.pools.put(key, pool);
        }
        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        CompoundTag all = new CompoundTag();
        for (Map.Entry<String, Pool> e : this.pools.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putInt("Min", e.getValue().minRolls);
            c.putInt("Max", e.getValue().maxRolls);
            ListTag slots = new ListTag();
            for (int i = 0; i < SLOTS; i++) {
                ItemStack s = e.getValue().slots[i];
                if (s.isEmpty()) {
                    continue;
                }
                CompoundTag sc = new CompoundTag();
                sc.putInt("Slot", i);
                sc.put("Item", s.save(provider));
                slots.add(sc);
            }
            c.put("Slots", slots);
            all.put(e.getKey(), c);
        }
        tag.put("Pools", all);
        return tag;
    }

    // ---- built in pools (you will edit these ingame, this is just a start)

    private static void row(Pool p, int row, ItemStack... items) {
        for (int i = 0; i < items.length && i < 9; i++) {
            p.slots[row * 9 + i] = items[i];
        }
    }

    private static ItemStack s(net.minecraft.world.item.Item item, int max) {
        return new ItemStack(item, max);
    }

    private static Map<String, Pool> defaultPools() {
        Map<String, Pool> m = new LinkedHashMap<>();

        Pool f1 = new Pool();
        f1.minRolls = 2;
        f1.maxRolls = 4;
        row(f1, 0, s(Items.BREAD, 3), s(Items.TORCH, 8), s(Items.ARROW, 8), s(Items.COOKED_POTATO, 3),
                s(Items.BONE, 3), s(Items.STRING, 4), s(Items.COAL, 4), s(Items.APPLE, 2));
        row(f1, 1, s(Items.IRON_NUGGET, 6), s(Items.GOLD_NUGGET, 6), s(Items.LEATHER, 3),
                s(Items.COOKED_CHICKEN, 2), s(Items.COOKED_BEEF, 2));
        row(f1, 2, s(Items.IRON_INGOT, 2), s(Items.GOLD_INGOT, 1), s(Items.BOW, 1), s(Items.EXPERIENCE_BOTTLE, 2));
        row(f1, 3, s(Items.IRON_SWORD, 1), s(Items.IRON_HELMET, 1), s(Items.SHIELD, 1));
        row(f1, 4, s(Items.GOLDEN_APPLE, 1));
        m.put("floor_1", f1);

        Pool f2 = new Pool();
        f2.minRolls = 3;
        f2.maxRolls = 5;
        row(f2, 0, s(Items.COOKED_BEEF, 4), s(Items.ARROW, 16), s(Items.IRON_INGOT, 3), s(Items.GOLD_INGOT, 2),
                s(Items.GOLDEN_CARROT, 3), s(Items.EXPERIENCE_BOTTLE, 3), s(Items.LAPIS_LAZULI, 6));
        row(f2, 1, s(Items.IRON_INGOT, 5), s(Items.GOLD_INGOT, 4), s(Items.REDSTONE, 8), s(Items.COOKED_PORKCHOP, 4),
                s(Items.IRON_BOOTS, 1), s(Items.IRON_LEGGINGS, 1));
        row(f2, 2, s(Items.IRON_CHESTPLATE, 1), s(Items.IRON_AXE, 1), s(Items.CROSSBOW, 1), s(Items.GOLDEN_APPLE, 1),
                s(Items.EMERALD, 3));
        row(f2, 3, s(Items.DIAMOND, 1), s(Items.IRON_BLOCK, 1), s(Items.EXPERIENCE_BOTTLE, 6));
        row(f2, 4, s(Items.DIAMOND, 2), s(Items.ENCHANTED_GOLDEN_APPLE, 1));
        m.put("floor_2", f2);

        Pool f3 = new Pool();
        f3.minRolls = 4;
        f3.maxRolls = 7;
        row(f3, 0, s(Items.COOKED_BEEF, 6), s(Items.GOLDEN_CARROT, 5), s(Items.IRON_INGOT, 6), s(Items.GOLD_INGOT, 5),
                s(Items.EXPERIENCE_BOTTLE, 6), s(Items.EMERALD, 4), s(Items.ARROW, 24));
        row(f3, 1, s(Items.DIAMOND, 2), s(Items.GOLDEN_APPLE, 2), s(Items.IRON_BLOCK, 1), s(Items.EMERALD, 8),
                s(Items.ENDER_PEARL, 2));
        row(f3, 2, s(Items.DIAMOND_SWORD, 1), s(Items.DIAMOND_PICKAXE, 1), s(Items.DIAMOND_HELMET, 1),
                s(Items.DIAMOND_BOOTS, 1), s(Items.DIAMOND, 4));
        row(f3, 3, s(Items.DIAMOND_CHESTPLATE, 1), s(Items.DIAMOND_LEGGINGS, 1), s(Items.DIAMOND_AXE, 1),
                s(Items.ENCHANTED_GOLDEN_APPLE, 1));
        row(f3, 4, s(Items.NETHERITE_SCRAP, 2), s(Items.DIAMOND_BLOCK, 1), s(Items.TOTEM_OF_UNDYING, 1));
        row(f3, 5, s(Items.NETHERITE_INGOT, 1), s(Items.ELYTRA, 1));
        m.put("floor_3", f3);

        Pool sz = new Pool();
        sz.minRolls = 3;
        sz.maxRolls = 5;
        row(sz, 0, s(Items.BREAD, 4), s(Items.COOKED_BEEF, 4), s(Items.TORCH, 12), s(Items.ARROW, 12), s(Items.APPLE, 3));
        row(sz, 1, s(Items.COOKED_CHICKEN, 3), s(Items.GOLDEN_CARROT, 2), s(Items.EXPERIENCE_BOTTLE, 2));
        row(sz, 2, s(Items.GOLDEN_APPLE, 1));
        m.put("safezone", sz);
        return m;
    }
}
