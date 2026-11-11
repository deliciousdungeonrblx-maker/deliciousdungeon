package com.meshi.forest;

import com.meshi.forest.PartGrid.Mat;
import java.util.ArrayList;
import java.util.List;

// every editable part of floor 2. one entry = one edit plot ingame. NEW PARTS GO AT THE END ONLY,
// the plot position of a part is its index in ALL. pure java.
public final class ForestDefs {
    private ForestDefs() {
    }

    // ---- the fixed numbers of the map (the old config file is gone, only the radius is adjustable)
    public static final int SPACING = 34;
    public static final int LEVELS = 3;
    public static final int HEIGHT = 96;
    public static final int BASE_Y = 40;
    public static final int BELOW = 12;
    // deck heights relative to BASE_Y, level 0 is the top one
    public static final int[] DECKS = {66, 42, 18};
    public static final int NEST_LEVEL = 1;
    public static final int BRIDGE_LEN = SPACING - 13;
    public static final int LOG_LEN = SPACING - 8;

    public enum Kind { CASTLE, CASTLE_NEST, TREE, TREE_BAT, BRIDGE, LOG, FILLER }

    // faces in the part's own frame: 0 = +x (east), 1 = +z (south), 2 = -x (west), 3 = -z (north).
    // turning a part clockwise once moves face n to n + 1
    public static int dx(int face, int r, int l) {
        return switch (face) {
            case 0 -> r;
            case 1 -> -l;
            case 2 -> -r;
            default -> l;
        };
    }

    public static int dz(int face, int r, int l) {
        return switch (face) {
            case 0 -> l;
            case 1 -> r;
            case 2 -> -l;
            default -> -r;
        };
    }

    public static final String[] FACE_NAME = {"east", "south", "west", "north"};

    // a doorway (castle) or railing gap (tree) that is open in the saved part. the generator fills the ones
    // that no bridge / log uses with the block it finds next to them
    public static final class Door {
        public final int face, level, w, h, r0, r1, sampleR, sampleL;
        public final Mat fallback;

        Door(int face, int level, int w, int h, int r0, int r1, int sampleR, int sampleL, Mat fallback) {
            this.face = face;
            this.level = level;
            this.w = w;
            this.h = h;
            this.r0 = r0;
            this.r1 = r1;
            this.sampleR = sampleR;
            this.sampleL = sampleL;
            this.fallback = fallback;
        }

        public int floorY() {
            return DECKS[this.level] + 1;
        }

        // y where we look for the block to copy
        public int sampleY() {
            return DECKS[this.level] + 1 + (this.fallback == Mat.RAIL ? 0 : this.h / 2);
        }
    }

    public static final class Def {
        public final String key;
        public final Kind kind;
        public final String filler; // tree kind for fillers
        public final int height; // filler trunk height
        public final int sx, sy, sz, ax, ay, az;
        public final List<Door> doors;
        public final String help;

        Def(String key, Kind kind, String filler, int height, int sx, int sy, int sz, int ax, int ay, int az,
                List<Door> doors, String help) {
            this.key = key;
            this.kind = kind;
            this.filler = filler;
            this.height = height;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.doors = doors;
            this.help = help;
        }

        public boolean isFiller() {
            return this.kind == Kind.FILLER;
        }

        // blocks wide / deep after turning (the footprint is the same square or a long thin box)
        public int[] worldBox(int px, int py, int pz, int rot) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int[] c : new int[][] {{0, 0}, {this.sx - 1, 0}, {0, this.sz - 1}, {this.sx - 1, this.sz - 1}}) {
                int[] r = rotate(c[0] - this.ax, c[1] - this.az, rot);
                x0 = Math.min(x0, px + r[0]);
                x1 = Math.max(x1, px + r[0]);
                z0 = Math.min(z0, pz + r[1]);
                z1 = Math.max(z1, pz + r[1]);
            }
            return new int[] {x0, py - this.ay, z0, x1, py - this.ay + this.sy - 1, z1};
        }
    }

    // clockwise quarter turns (minecraft: east -> south)
    public static int[] rotate(int dx, int dz, int rot) {
        return switch (rot & 3) {
            case 1 -> new int[] {-dz, dx};
            case 2 -> new int[] {-dx, -dz};
            case 3 -> new int[] {dz, -dx};
            default -> new int[] {dx, dz};
        };
    }

    public static final String[] FILLER_KINDS = {"giant", "slender", "twin", "spruce", "mushroom", "dead", "willow", "stump"};
    public static final String[] SIZE_NAMES = {"s", "m", "l"};
    public static final int[] SIZE_H = {36, 64, 100};
    // how far each filler tree reaches sideways (used to keep them off the castles and trees)
    public static final int[] FILLER_R = {11, 6, 10, 9, 11, 11, 9, 10};

    public static final List<Def> ALL = new ArrayList<>();

    private static List<Door> castleDoors(boolean nest) {
        List<Door> out = new ArrayList<>();
        for (int lv = 0; lv < LEVELS; lv++) {
            for (int f = 0; f < 4; f++) {
                if (nest && f == 3 && lv == NEST_LEVEL) {
                    continue; // the nest opening is never filled
                }
                boolean log = f == 0;
                int w = log ? 5 : 3, h = log ? 5 : 4;
                out.add(new Door(f, lv, w, h, 3, 8, 5, w / 2 + 1, Mat.STONE));
            }
        }
        return out;
    }

    private static List<Door> treeDoors() {
        List<Door> out = new ArrayList<>();
        for (int lv = 0; lv < LEVELS; lv++) {
            for (int f = 0; f < 4; f++) {
                out.add(new Door(f, lv, 5, 2, 10, 10, 10, 3, Mat.RAIL));
            }
        }
        return out;
    }

    private static final List<Door> NONE = List.of();

    static {
        ALL.add(new Def("castle", Kind.CASTLE, null, 0, 17, 112, 17, 8, BELOW, 8, castleDoors(false),
                "a normal castle. the BIGGER doorway on the east side is the log door (mossy logs link castle to castle), "
                        + "the other 3 sides have bridge doors, on all 3 levels. keep all 12 doorways open, the generator "
                        + "fills the ones that have no bridge / log. windows only on the north + south faces."));
        ALL.add(new Def("castle_nest", Kind.CASTLE_NEST, null, 0, 17, 112, 17, 8, BELOW, 8, castleDoors(true),
                "a castle with a basilisk nest. same doors as the normal castle, but the NORTH side on level 2 is a big nest "
                        + "opening (7 wide). leave the nest opening alone, it is never filled."));
        ALL.add(new Def("tree", Kind.TREE, null, 0, 29, 120, 29, 14, BELOW, 14, treeDoors(),
                "the big tree (as tall as the castles) with 3 circular platforms. every platform has a 5 wide gap in the "
                        + "railing on all 4 sides, keep them open, the generator closes the ones with no bridge."));
        ALL.add(new Def("tree_bat", Kind.TREE_BAT, null, 0, 29, 120, 29, 14, BELOW, 14, treeDoors(),
                "the big tree with a big bat hole on the EAST side of the top platform (level 1). same railing gaps as the tree."));
        ALL.add(new Def("bridge", Kind.BRIDGE, null, 0, BRIDGE_LEN + 1, 6, 5, 0, 3, 2, NONE,
                "rope bridge from a castle to a tree. it is " + (BRIDGE_LEN + 1) + " blocks long, going EAST from the plot "
                        + "corner. the walkway is at the 4th layer from the bottom, don't change the length."));
        ALL.add(new Def("log", Kind.LOG, null, 0, LOG_LEN + 1, 8, 7, 0, 6, 3, NONE,
                "huge mossy log from castle to castle. " + (LOG_LEN + 1) + " blocks long, going EAST from the plot corner, "
                        + "the walking surface is the 7th layer from the bottom."));
        for (int k = 0; k < FILLER_KINDS.length; k++) {
            for (int s = 0; s < 3; s++) {
                int h = SIZE_H[s];
                ALL.add(new Def(FILLER_KINDS[k] + "_" + SIZE_NAMES[s], Kind.FILLER, FILLER_KINDS[k], h, 25, BELOW + h + 14, 25, 12, BELOW, 12,
                        NONE, "filler " + FILLER_KINDS[k] + " tree, size " + SIZE_NAMES[s] + " (about " + h + " blocks of trunk). "
                                + "it floats on its own in the gaps between castles and trees. the roots hang under the bottom."));
            }
        }
    }

    public static Def byKey(String key) {
        for (Def d : ALL) {
            if (d.key.equals(key)) {
                return d;
            }
        }
        return null;
    }

    public static int indexOf(String key) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).key.equals(key)) {
                return i;
            }
        }
        return -1;
    }
}
