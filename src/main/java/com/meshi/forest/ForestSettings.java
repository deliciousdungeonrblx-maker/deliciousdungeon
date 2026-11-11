package com.meshi.forest;

import java.util.LinkedHashMap;
import java.util.Map;

// every number / block of floor 2 lives in here. defaults below, overridden by meshi_forest.json in the world folder
// (edit the file and /meshi forest settings reload, or /meshi forest settings set <key> <value>)
public final class ForestSettings {
    public static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    public static final Map<String, String> HELP = new LinkedHashMap<>();

    private static void d(String key, String value, String help) {
        DEFAULTS.put(key, value);
        HELP.put(key, help);
    }

    static {
        // layout
        d("grid_cols", "6", "nodes (castles + trees) across, x direction");
        d("grid_rows", "4", "nodes along z");
        d("spacing", "34", "blocks between two neighbouring nodes (min 26)");
        d("levels", "3", "stacked levels (decks) per floor, 1 to 4. limited by height / level_gap");
        d("level_gap", "24", "blocks between two levels");
        d("height", "96", "height of every castle AND every main tree (they are always the same)");
        d("base_y", "40", "y of the bottom of the castles / trees");
        d("extra_links", "2", "extra bridges / logs per level on top of the spanning tree (loops)");
        d("log_chance", "0.7", "chance that two neighbouring castles get a mossy log link (castle to castle only)");
        d("nest_castles", "2", "castles with a basilisk nest opening (structure only, no mobs yet)");
        d("nest_level", "2", "which level the nest opens on (1 = top)");
        d("bat_trees", "2", "trees with a big bat hole (structure only, no mobs yet)");
        // castles
        d("windows", "true", "windows in the castle walls");
        d("window_faces", "2", "2 = front and back faces only, 4 = all four sides");
        d("window_rows_per_level", "2", "window rows between two decks");
        d("castle_vines", "7", "long vines hanging down each castle");
        d("lanterns", "true", "soul lanterns in castles and along bridges");
        d("lantern_spacing", "12", "blocks between lanterns on a bridge / log");
        // filler trees
        d("filler_density", "0.9", "chance for a filler tree in each empty gap (0 = none, 1 = every gap)");
        d("filler_rings", "1", "extra rings of gaps around the outside that also get filler trees");
        d("filler_variants", "giant,slender,twin,spruce,mushroom,dead,willow,stump", "which tree kinds can be used");
        d("filler_min_height", "30", "shortest filler tree");
        d("filler_max_height", "110", "tallest filler tree");
        d("filler_float_range", "45", "how far up / down filler trees float from base_y");
        // build speed
        d("blocks_per_tick", "12000", "how many blocks are placed per server tick while building");
        // materials (any block id)
        d("block_stone", "minecraft:stone_bricks", "castle wall");
        d("block_mossy", "minecraft:mossy_stone_bricks", "castle wall moss patches");
        d("block_band", "minecraft:polished_diorite", "light ledge under every castle level");
        d("block_trunk", "minecraft:dark_oak_log", "tree trunks");
        d("block_beam", "minecraft:stripped_dark_oak_log", "supports under platforms");
        d("block_log", "minecraft:dark_oak_log", "horizontal log bridges");
        d("block_moss", "minecraft:moss_block", "top of log bridges");
        d("block_plank", "minecraft:spruce_planks", "decks, bridges, rings");
        d("block_rail", "minecraft:spruce_fence", "railings");
        d("block_leaf", "minecraft:oak_leaves", "leaves");
        d("block_leaf2", "minecraft:spruce_leaves", "leaves (spruce trees)");
        d("block_leaf3", "minecraft:azalea_leaves", "leaves (mushroom and slender trees)");
        d("block_vine", "minecraft:cave_vines_plant", "hanging vines");
        d("block_nest", "minecraft:hay_block", "basilisk nest");
        d("block_egg", "minecraft:calcite", "basilisk eggs");
        d("block_lantern", "minecraft:soul_lantern", "lights");
        d("block_ladder", "minecraft:ladder", "ladders between levels");
    }

    private final Map<String, String> values = new LinkedHashMap<>(DEFAULTS);

    public ForestSettings() {
    }

    public ForestSettings(Map<String, String> overrides) {
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (DEFAULTS.containsKey(e.getKey())) {
                this.values.put(e.getKey(), e.getValue());
            }
        }
    }

    public Map<String, String> all() {
        return this.values;
    }

    public String get(String key) {
        return this.values.getOrDefault(key, DEFAULTS.get(key));
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULTS.get(key));
        }
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(get(key).trim());
        } catch (NumberFormatException e) {
            return Double.parseDouble(DEFAULTS.get(key));
        }
    }

    public boolean getBool(String key) {
        return get(key).trim().equalsIgnoreCase("true");
    }

    // null = ok, otherwise a message
    public static String validate(String key, String value) {
        String def = DEFAULTS.get(key);
        if (def == null) {
            return "no setting called " + key;
        }
        try {
            if (def.equals("true") || def.equals("false")) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                    return key + " is true or false";
                }
            } else if (key.startsWith("block_") || key.equals("filler_variants")) {
                if (value.isBlank()) {
                    return "empty";
                }
            } else if (def.contains(".")) {
                Double.parseDouble(value);
            } else {
                Integer.parseInt(value);
            }
        } catch (NumberFormatException e) {
            return key + " needs a number";
        }
        return null;
    }
}
