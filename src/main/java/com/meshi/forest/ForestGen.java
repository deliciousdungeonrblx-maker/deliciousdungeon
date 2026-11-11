package com.meshi.forest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

// floor 2 geometry. pure java (no minecraft classes) so it can be tested on its own.
// writes materials into a compact map, ForestBuilder turns them into blocks.
// castles and the main trees are the same height, nothing touches the ground (it floats in the void)
public final class ForestGen {
    public enum Mat {
        AIR, TRUNK, LOG_X, LOG_Z, BEAM, STONE, MSTONE, BAND, PLANK, RAIL, MOSS, LEAF, LEAF2, LEAF3, VINE, NEST, EGG,
        LANTERN, LADDER_N, LADDER_S, LADDER_E, LADDER_W
    }

    static final Mat[] MATS = Mat.values();
    static final double CASTLE_RO = 6.5D;
    static final double CASTLE_RI = 4.5D;
    static final double TREE_R = 4.5D;
    static final double RING_R = 10.5D;
    static final int BASE_H = 6;

    // ---- compact long -> material map
    static final class LongByteMap {
        long[] k = new long[1 << 16];
        byte[] v = new byte[1 << 16];
        int size = 0;

        static int hash(long key) {
            key ^= key >>> 33;
            key *= 0xff51afd7ed558ccdL;
            key ^= key >>> 33;
            return (int) key;
        }

        Mat get(long key) {
            int mask = this.k.length - 1;
            int i = hash(key) & mask;
            while (this.v[i] != 0) {
                if (this.k[i] == key) {
                    return MATS[this.v[i] - 1];
                }
                i = (i + 1) & mask;
            }
            return null;
        }

        void put(long key, Mat m) {
            if (this.size * 2 >= this.k.length) {
                grow();
            }
            int mask = this.k.length - 1;
            int i = hash(key) & mask;
            while (this.v[i] != 0) {
                if (this.k[i] == key) {
                    this.v[i] = (byte) (m.ordinal() + 1);
                    return;
                }
                i = (i + 1) & mask;
            }
            this.k[i] = key;
            this.v[i] = (byte) (m.ordinal() + 1);
            this.size++;
        }

        void grow() {
            long[] ok = this.k;
            byte[] ov = this.v;
            this.k = new long[ok.length * 2];
            this.v = new byte[ok.length * 2];
            this.size = 0;
            for (int i = 0; i < ok.length; i++) {
                if (ov[i] != 0) {
                    put(ok[i], MATS[ov[i] - 1]);
                }
            }
        }
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x + (1 << 25)) << 38) | ((long) (z + (1 << 25)) << 12) | (long) (y + 2048);
    }

    public static int ux(long p) {
        return (int) (p >>> 38) - (1 << 25);
    }

    public static int uz(long p) {
        return (int) ((p >>> 12) & 0x3FFFFFFL) - (1 << 25);
    }

    public static int uy(long p) {
        return (int) (p & 0xFFFL) - 2048;
    }

    // ---- results
    public static final class Link {
        public final int ai, aj, bi, bj, level;
        public final boolean log;

        Link(int ai, int aj, int bi, int bj, int level, boolean log) {
            this.ai = ai;
            this.aj = aj;
            this.bi = bi;
            this.bj = bj;
            this.level = level;
            this.log = log;
        }
    }

    public int[] spawn = new int[3];
    public int[] exit = new int[3];
    public final List<Link> links = new ArrayList<>();
    public int castles, trees, fillers, nests, batTrees;
    public int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    private final ForestSettings s;
    private final long seed;
    private final Random rnd;
    private final LongByteMap map = new LongByteMap();
    private final int cols, rows, sp, levels, gap, height, yb;
    private final int[] decks;

    public ForestGen(ForestSettings settings, long seed) {
        this.s = settings;
        this.seed = seed;
        this.rnd = new Random(seed);
        this.cols = clamp(settings.getInt("grid_cols"), 2, 16);
        this.rows = clamp(settings.getInt("grid_rows"), 2, 16);
        this.sp = clamp(settings.getInt("spacing"), 26, 80);
        this.gap = clamp(settings.getInt("level_gap"), 14, 60);
        this.height = clamp(settings.getInt("height"), 40, 220);
        this.yb = clamp(settings.getInt("base_y"), -40, 120);
        int maxLevels = Math.max(1, (this.height - 30 - 10) / this.gap + 1);
        this.levels = clamp(settings.getInt("levels"), 1, Math.min(4, maxLevels));
        this.decks = new int[this.levels];
        for (int k = 0; k < this.levels; k++) {
            this.decks[k] = this.yb + this.height - 30 - k * this.gap;
        }
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- buffer access
    public int blockCount() {
        return this.map.size;
    }

    public long[] sortedKeys() {
        long[] out = new long[this.map.size];
        int n = 0;
        for (int i = 0; i < this.map.k.length; i++) {
            if (this.map.v[i] != 0) {
                out[n++] = this.map.k[i];
            }
        }
        Arrays.sort(out);
        return out;
    }

    public Mat at(long key) {
        return this.map.get(key);
    }

    public Mat at(int x, int y, int z) {
        return this.map.get(pack(x, y, z));
    }

    private void put(int x, int y, int z, Mat m) {
        this.map.put(pack(x, y, z), m);
        if (x < this.minX) this.minX = x;
        if (x > this.maxX) this.maxX = x;
        if (z < this.minZ) this.minZ = z;
        if (z > this.maxZ) this.maxZ = z;
    }

    private void putAir(int x, int y, int z, Mat m) {
        if (this.map.get(pack(x, y, z)) == null) {
            put(x, y, z, m);
        }
    }

    private void carve(int x, int y, int z) {
        if (this.map.get(pack(x, y, z)) != null) {
            this.map.put(pack(x, y, z), Mat.AIR);
        }
    }

    float noise(int x, int y, int z) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ this.seed * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (h >>> 40) / (float) (1 << 24);
    }

    private void disk(int nx, int nz, double r, int y0, int y1, Mat m, double r0, boolean onlyAir) {
        double cx = nx + 0.5D, cz = nz + 0.5D;
        int ri = (int) Math.ceil(r) + 2;
        for (int x = nx - ri; x <= nx + ri; x++) {
            for (int z = nz - ri; z <= nz + ri; z++) {
                double d2 = (x + 0.5D - cx) * (x + 0.5D - cx) + (z + 0.5D - cz) * (z + 0.5D - cz);
                if (d2 <= r * r && (r0 < 0 || d2 > r0 * r0)) {
                    for (int y = y0; y <= y1; y++) {
                        if (onlyAir) {
                            putAir(x, y, z, m);
                        } else {
                            put(x, y, z, m);
                        }
                    }
                }
            }
        }
    }

    private void ellipsoid(double cx, double cy, double cz, double rx, double ry, double rz, Mat m, double keep) {
        for (int x = (int) Math.floor(cx - rx) - 1; x <= (int) Math.ceil(cx + rx) + 1; x++) {
            for (int y = (int) Math.floor(cy - ry) - 1; y <= (int) Math.ceil(cy + ry) + 1; y++) {
                for (int z = (int) Math.floor(cz - rz) - 1; z <= (int) Math.ceil(cz + rz) + 1; z++) {
                    double a = (x + 0.5D - cx) / rx, b = (y + 0.5D - cy) / ry, c = (z + 0.5D - cz) / rz;
                    if (a * a + b * b + c * c <= 1.0D && this.rnd.nextDouble() < keep) {
                        putAir(x, y, z, m);
                    }
                }
            }
        }
    }

    static int sag(double t) {
        return -(int) Math.round(2 * 4 * t * (1 - t));
    }

    // ---- layout
    static boolean isCastle(int i, int j) {
        return ((i + 2 * (j & 1)) % 4) < 2;
    }

    private int nodeX(int i) {
        return i * this.sp;
    }

    private int nodeZ(int j) {
        return j * this.sp;
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    // one spanning tree + a few loops per level. castle-castle = log, castle-tree = bridge. trees never link to trees
    private List<Link> levelGraph(int level) {
        List<int[]> logs = new ArrayList<>();
        List<int[]> bridges = new ArrayList<>();
        List<int[]> treeTree = new ArrayList<>();
        for (int j = 0; j < this.rows; j++) {
            for (int i = 0; i < this.cols; i++) {
                for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                    int bi = i + d[0], bj = j + d[1];
                    if (bi >= this.cols || bj >= this.rows) {
                        continue;
                    }
                    boolean ca = isCastle(i, j), cb = isCastle(bi, bj);
                    int[] e = {i, j, bi, bj};
                    if (ca && cb) {
                        logs.add(e);
                    } else if (ca || cb) {
                        bridges.add(e);
                    } else {
                        treeTree.add(e);
                    }
                }
            }
        }
        int n = this.cols * this.rows;
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<int[]> chosen = new ArrayList<>();
        double logChance = this.s.getDouble("log_chance");
        for (int[] e : logs) {
            if (this.rnd.nextDouble() < logChance) {
                join(parent, e, chosen);
            }
        }
        List<int[]> rest = new ArrayList<>(bridges);
        for (int[] e : logs) {
            if (!chosen.contains(e)) {
                rest.add(e);
            }
        }
        java.util.Collections.shuffle(rest, this.rnd);
        for (int[] e : rest) {
            join(parent, e, chosen);
        }
        // last resort if the grid size made the castle graph fall apart
        java.util.Collections.shuffle(treeTree, this.rnd);
        for (int[] e : treeTree) {
            join(parent, e, chosen);
        }
        int extras = this.s.getInt("extra_links");
        List<int[]> pool = new ArrayList<>(rest);
        java.util.Collections.shuffle(pool, this.rnd);
        for (int[] e : pool) {
            if (extras <= 0) {
                break;
            }
            if (!chosen.contains(e)) {
                chosen.add(e);
                extras--;
            }
        }
        List<Link> out = new ArrayList<>();
        for (int[] e : chosen) {
            out.add(new Link(e[0], e[1], e[2], e[3], level, isCastle(e[0], e[1]) && isCastle(e[2], e[3])));
        }
        return out;
    }

    private int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    private void join(int[] parent, int[] e, List<int[]> chosen) {
        int a = find(parent, e[1] * this.cols + e[0]);
        int b = find(parent, e[3] * this.cols + e[2]);
        if (a != b) {
            parent[a] = b;
            chosen.add(e);
        }
    }

    // ---- main build
    public void build() {
        for (int k = 0; k < this.levels; k++) {
            this.links.addAll(levelGraph(k));
        }
        // directions used per node per level: [i][j][level] -> list of {dx,dz,kind}  kind 0 bridge, 1 log
        List<int[]>[][][] use = new List[this.cols][this.rows][this.levels];
        for (Link l : this.links) {
            int dx = Integer.signum(l.bi - l.ai), dz = Integer.signum(l.bj - l.aj);
            add(use, l.ai, l.aj, l.level, dx, dz, l.log ? 1 : 0);
            add(use, l.bi, l.bj, l.level, -dx, -dz, l.log ? 1 : 0);
        }
        // roles
        List<int[]> castleNodes = new ArrayList<>();
        List<int[]> treeNodes = new ArrayList<>();
        for (int j = 0; j < this.rows; j++) {
            for (int i = 0; i < this.cols; i++) {
                (isCastle(i, j) ? castleNodes : treeNodes).add(new int[] {i, j});
            }
        }
        int[] exitNode = castleNodes.get(0);
        int best = -1;
        for (int[] c : castleNodes) {
            int dist = c[0] + c[1];
            if (dist > best) {
                best = dist;
                exitNode = c;
            }
        }
        Set<Integer> nestSet = new HashSet<>();
        List<int[]> cand = new ArrayList<>(castleNodes);
        java.util.Collections.shuffle(cand, this.rnd);
        int nestWant = this.s.getInt("nest_castles");
        for (int[] c : cand) {
            if (nestSet.size() >= nestWant) {
                break;
            }
            if ((c[0] == 0 && c[1] == 0) || (c[0] == exitNode[0] && c[1] == exitNode[1])) {
                continue;
            }
            nestSet.add(c[1] * this.cols + c[0]);
        }
        Set<Integer> batSet = new HashSet<>();
        List<int[]> tc = new ArrayList<>(treeNodes);
        java.util.Collections.shuffle(tc, this.rnd);
        for (int[] t : tc) {
            if (batSet.size() >= this.s.getInt("bat_trees")) {
                break;
            }
            batSet.add(t[1] * this.cols + t[0]);
        }
        int nestLevel = clamp(this.s.getInt("nest_level"), 1, this.levels) - 1;
        // nodes
        for (int[] c : castleNodes) {
            boolean nest = nestSet.contains(c[1] * this.cols + c[0]);
            castle(c[0], c[1], use[c[0]][c[1]], nest, nestLevel, c[0] * 7 + c[1] * 3);
            this.castles++;
        }
        for (int[] t : treeNodes) {
            boolean bat = batSet.contains(t[1] * this.cols + t[0]);
            tree(t[0], t[1], use[t[0]][t[1]], bat);
            this.trees++;
        }
        for (Link l : this.links) {
            link(l);
        }
        fillers();
        this.spawn = new int[] {nodeX(0), this.decks[0] + 1, nodeZ(0)};
        this.exit = new int[] {nodeX(exitNode[0]), this.decks[this.levels - 1] + 1, nodeZ(exitNode[1])};
    }

    private void add(List<int[]>[][][] use, int i, int j, int level, int dx, int dz, int kind) {
        if (use[i][j][level] == null) {
            use[i][j][level] = new ArrayList<>();
        }
        use[i][j][level].add(new int[] {dx, dz, kind});
    }

    private boolean used(List<int[]>[] perLevel, int level, int dx, int dz) {
        if (perLevel[level] == null) {
            return false;
        }
        for (int[] u : perLevel[level]) {
            if (u[0] == dx && u[1] == dz) {
                return true;
            }
        }
        return false;
    }

    // ---- castle
    private void castle(int ni, int nj, List<int[]>[] use, boolean nest, int nestLevel, int variant) {
        int nx = nodeX(ni), nz = nodeZ(nj);
        double cx = nx + 0.5D, cz = nz + 0.5D;
        int top = this.yb + this.height;
        int bh = BASE_H;
        for (int y = this.yb; y <= top; y++) {
            if (y < this.yb + bh) {
                double r = 2.5D + (y - this.yb) * (4.0D / bh);
                disk(nx, nz, r, y, y, Mat.STONE, -1, false);
            } else {
                disk(nx, nz, CASTLE_RO, y, y, Mat.STONE, -1, false);
                disk(nx, nz, CASTLE_RI, y, y, Mat.AIR, -1, false);
            }
        }
        for (int d : this.decks) {
            disk(nx, nz, CASTLE_RI, d, d, Mat.PLANK, -1, false);
        }
        disk(nx, nz, CASTLE_RI, this.yb + bh, this.yb + bh, Mat.PLANK, -1, false);
        // moss patches on the outside
        for (int x = nx - 8; x <= nx + 8; x++) {
            for (int z = nz - 8; z <= nz + 8; z++) {
                double d2 = (x + 0.5D - cx) * (x + 0.5D - cx) + (z + 0.5D - cz) * (z + 0.5D - cz);
                if (d2 > 5.2D * 5.2D && d2 <= CASTLE_RO * CASTLE_RO) {
                    double stripe = (Math.sin(x * 1.7D + z * 2.3D) + 1) / 2;
                    for (int y = this.yb; y <= top; y++) {
                        if (this.map.get(pack(x, y, z)) == Mat.STONE
                                && noise(x, y, z) < 0.10D + 0.20D * stripe * (1 - (y - this.yb) / (double) this.height * 0.6D)) {
                            put(x, y, z, Mat.MSTONE);
                        }
                    }
                }
            }
        }
        // battlements
        for (int x = nx - 8; x <= nx + 8; x++) {
            for (int z = nz - 8; z <= nz + 8; z++) {
                double d2 = (x + 0.5D - cx) * (x + 0.5D - cx) + (z + 0.5D - cz) * (z + 0.5D - cz);
                if (d2 > 5.4D * 5.4D && d2 <= CASTLE_RO * CASTLE_RO) {
                    double ang = Math.atan2(z + 0.5D - cz, x + 0.5D - cx);
                    if ((int) ((ang + Math.PI) / (2 * Math.PI) * 12) % 2 == 0) {
                        put(x, top + 1, z, Mat.STONE);
                        put(x, top + 2, z, Mat.STONE);
                    }
                }
            }
        }
        // light ledge under every level
        for (int d : this.decks) {
            disk(nx, nz, 7.5D, d - 2, d - 1, Mat.BAND, 6.4D, true);
        }
        // long vines on the walls
        int vines = this.s.getInt("castle_vines");
        for (int n = 0; n < vines; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI;
            int x = (int) Math.round(cx - 0.5D + 7.0D * Math.cos(a));
            int z = (int) Math.round(cz - 0.5D + 7.0D * Math.sin(a));
            int y0 = this.yb + 4 + this.rnd.nextInt(Math.max(1, top - 30 - this.yb - 4));
            int len = 14 + this.rnd.nextInt(25);
            for (int y = y0; y < Math.min(top, y0 + len); y++) {
                putAir(x, y, z, Mat.VINE);
            }
        }
        for (int n = 0; n < 4; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI, r = this.rnd.nextDouble() * 2.0D;
            int x = (int) Math.round(cx - 0.5D + r * Math.cos(a));
            int z = (int) Math.round(cz - 0.5D + r * Math.sin(a));
            int len = 4 + this.rnd.nextInt(7);
            for (int y = this.yb - len; y < this.yb; y++) {
                putAir(x, y, z, Mat.VINE);
            }
        }
        // doorways for every bridge / log (log doorway is wider)
        boolean[] faceHasDoor = new boolean[4]; // +x -x +z -z
        for (int lv = 0; lv < this.levels; lv++) {
            if (use[lv] == null) {
                continue;
            }
            for (int[] u : use[lv]) {
                doorway(nx, nz, u[0], u[1], this.decks[lv], u[2] == 1 ? 5 : 3, u[2] == 1 ? 5 : 4, false);
                faceHasDoor[u[0] == 1 ? 0 : u[0] == -1 ? 1 : u[1] == 1 ? 2 : 3] = true;
            }
        }
        if (nest) {
            for (int[] d : DIRS) {
                if (!used(use, nestLevel, d[0], d[1])) {
                    doorway(nx, nz, d[0], d[1], this.decks[nestLevel], 7, 7, true);
                    faceHasDoor[d[0] == 1 ? 0 : d[0] == -1 ? 1 : d[1] == 1 ? 2 : 3] = true;
                    this.nests++;
                    break;
                }
            }
        }
        // windows on the front and back faces (z) and optionally the sides (x)
        if (this.s.getBool("windows")) {
            int faces = this.s.getInt("window_faces");
            int rowsPer = clamp(this.s.getInt("window_rows_per_level"), 1, 3);
            for (int lv = 0; lv < this.levels; lv++) {
                for (int rrow = 0; rrow < rowsPer; rrow++) {
                    int y0 = this.decks[lv] + 9 + rrow * (14 / rowsPer + 1) * 1;
                    if (y0 + 4 >= this.decks[lv] + this.gap - 2 && lv > 0) {
                        // too close to the deck above, skip
                    }
                    if (y0 + 4 > top - 2) {
                        continue;
                    }
                    window(nx, nz, 0, 1, y0);
                    window(nx, nz, 0, -1, y0);
                    if (faces >= 4) {
                        if (!faceHasDoor[0]) {
                            window(nx, nz, 1, 0, y0);
                        }
                        if (!faceHasDoor[1]) {
                            window(nx, nz, -1, 0, y0);
                        }
                    }
                }
            }
        }
        // ladder shaft in one diagonal corner, goes through every deck
        int q = variant & 3;
        int sx = (q & 1) == 0 ? 1 : -1, sz = (q & 2) == 0 ? 1 : -1;
        int lx = sx > 0 ? nx + 2 : nx - 3;
        int lz = sz > 0 ? nz + 3 : nz - 4;
        int wallX = sx > 0 ? lx + 1 : lx - 1;
        int lowest = this.decks[this.levels - 1], highest = this.decks[0];
        for (int y = lowest; y <= highest + 1; y++) {
            put(wallX, y, lz, Mat.STONE);
            put(lx, y, lz, sx > 0 ? Mat.LADDER_W : Mat.LADDER_E);
        }
        // lanterns (opposite corner)
        if (this.s.getBool("lanterns")) {
            int ox = sx > 0 ? nx - 3 : nx + 2, oz = sz > 0 ? nz - 4 : nz + 3;
            for (int d : this.decks) {
                put(ox, d + 1, oz, Mat.LANTERN);
            }
        }
    }

    // dx,dz = direction of the doorway, deck = floor y, w = width, h = height, nest = also fills a straw nest
    private void doorway(int nx, int nz, int dx, int dz, int deck, int w, int h, boolean nest) {
        double cx = nx + 0.5D, cz = nz + 0.5D;
        int px = -dz, pz = dx;
        for (int l = -(w / 2); l <= w / 2; l++) {
            for (int r = 3; r < 9; r++) {
                int x = (int) Math.floor(cx + dx * (r + 0.5D) + px * l);
                int z = (int) Math.floor(cz + dz * (r + 0.5D) + pz * l);
                for (int yy = deck + 1; yy <= deck + h; yy++) {
                    if (w > 3 && Math.abs(l) == w / 2 && yy == deck + h) {
                        continue;
                    }
                    carve(x, yy, z);
                }
            }
        }
        if (nest) {
            for (int l = -3; l <= 3; l++) {
                for (int r = 0; r < 8; r++) {
                    int x = (int) Math.floor(cx + dx * (r + 0.5D) + px * l);
                    int z = (int) Math.floor(cz + dz * (r + 0.5D) + pz * l);
                    if (l * l + (r - 2.5D) * (r - 2.5D) <= 9) {
                        put(x, deck, z, Mat.NEST);
                        put(x, deck + 1, z, (Math.abs(l) == 3 || r == 0 || r == 5) ? Mat.NEST : Mat.AIR);
                    }
                }
            }
            int[][] eggs = {{-1, 2}, {1, 2}, {0, 3}};
            for (int[] e : eggs) {
                int x = (int) Math.floor(cx + dx * (e[1] + 0.5D) + px * e[0]);
                int z = (int) Math.floor(cz + dz * (e[1] + 0.5D) + pz * e[0]);
                put(x, deck + 1, z, Mat.EGG);
                put(x, deck + 2, z, Mat.EGG);
            }
        }
    }

    // 2 wide, 4 tall hole straight through the wall on one face
    private void window(int nx, int nz, int dx, int dz, int y0) {
        for (int along = 3; along <= 8; along++) {
            for (int lat = -1; lat <= 0; lat++) {
                int x, z;
                if (dz != 0) {
                    x = nx + lat;
                    z = dz > 0 ? nz + along : nz - 1 - along;
                } else {
                    z = nz + lat;
                    x = dx > 0 ? nx + along : nx - 1 - along;
                }
                for (int y = y0; y < y0 + 4; y++) {
                    if (along >= 4 || y > y0) {
                        carve(x, y, z);
                    }
                }
            }
        }
    }

    // ---- tree
    private void trunk(int nx, int nz, int y0, int y1, double rBase, double rTop, int flare) {
        for (int y = y0; y <= y1; y++) {
            double r = y >= y0 + flare ? rTop : 1.5D + (y - y0) * (rBase - 1.5D) / Math.max(1, flare);
            if (y >= y0 + flare) {
                r = rTop;
            }
            disk(nx, nz, r, y, y, Mat.TRUNK, -1, false);
        }
    }

    private void roots(int nx, int nz) {
        for (int n = 0; n < 5; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI, rr = this.rnd.nextDouble() * 2.5D;
            int x = (int) Math.round(nx + rr * Math.cos(a));
            int z = (int) Math.round(nz + rr * Math.sin(a));
            int len = 5 + this.rnd.nextInt(7);
            for (int y = this.yb - len; y < this.yb; y++) {
                putAir(x, y, z, this.rnd.nextDouble() < 0.6D ? Mat.BEAM : Mat.VINE);
            }
        }
    }

    private void tree(int ni, int nj, List<int[]>[] use, boolean bat) {
        int nx = nodeX(ni), nz = nodeZ(nj);
        double cx = nx + 0.5D, cz = nz + 0.5D;
        int top = this.yb + this.height;
        trunk(nx, nz, this.yb, top, TREE_R, TREE_R, 8);
        roots(nx, nz);
        for (int lv = 0; lv < this.levels; lv++) {
            int d = this.decks[lv];
            disk(nx, nz, RING_R, d, d, Mat.PLANK, TREE_R, false);
            disk(nx, nz, 7.5D, d - 1, d - 1, Mat.BEAM, 5.4D, false);
            for (int x = nx - 12; x <= nx + 12; x++) {
                for (int z = nz - 12; z <= nz + 12; z++) {
                    double d2 = (x + 0.5D - cx) * (x + 0.5D - cx) + (z + 0.5D - cz) * (z + 0.5D - cz);
                    if (d2 >= 9.6D * 9.6D && d2 <= RING_R * RING_R && !gapAt(use, lv, x, z, cx, cz)) {
                        put(x, d + 1, z, Mat.RAIL);
                        put(x, d + 2, z, Mat.RAIL);
                    }
                }
            }
        }
        // big bat hole in the trunk, on a side with no link at the top level
        int[] holeDir = null;
        if (bat) {
            for (int[] dir : DIRS) {
                if (!used(use, 0, dir[0], dir[1])) {
                    holeDir = dir;
                    break;
                }
            }
            if (holeDir != null) {
                int hy = this.decks[0] + 9;
                int dx = holeDir[0], dz = holeDir[1], px = -dz, pz = dx;
                for (int l = -3; l <= 3; l++) {
                    for (int yy = -4; yy <= 4; yy++) {
                        if ((l / 3.5D) * (l / 3.5D) + (yy / 4.7D) * (yy / 4.7D) <= 1) {
                            for (int r = 1; r < 6; r++) {
                                int x = (int) Math.floor(cx + dx * (r + 0.5D) + px * l);
                                int z = (int) Math.floor(cz + dz * (r + 0.5D) + pz * l);
                                carve(x, hy + yy, z);
                            }
                        }
                    }
                }
                this.batTrees++;
            }
        }
        // ladder up the trunk (diagonal, opposite the hole if there is one)
        int sx = holeDir == null ? 1 : (holeDir[0] != 0 ? -holeDir[0] : 1);
        int sz = holeDir == null ? 1 : (holeDir[1] != 0 ? -holeDir[1] : 1);
        int lx = sx > 0 ? nx + 3 : nx - 4, lz = sz > 0 ? nz + 3 : nz - 4;
        int topLadder = this.decks[0] + 1;
        if (holeDir != null) {
            topLadder = this.decks[0] + 9;
        }
        for (int y = this.decks[this.levels - 1]; y <= topLadder; y++) {
            put(lx, y, lz, sx > 0 ? Mat.LADDER_E : Mat.LADDER_W);
        }
        // canopy
        ellipsoid(cx, top + 3, cz, 13, 7, 13, Mat.LEAF, 0.93D);
        for (int n = 0; n < 3; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI;
            int y = this.yb + 22 + this.rnd.nextInt(Math.max(1, top - 18 - this.yb - 22));
            ellipsoid(cx + 6.5D * Math.cos(a), y, cz + 6.5D * Math.sin(a), 6.5D, 4.5D, 6.5D, Mat.LEAF, 0.92D);
        }
    }

    private boolean gapAt(List<int[]>[] use, int lv, int x, int z, double cx, double cz) {
        if (use[lv] == null) {
            return false;
        }
        for (int[] u : use[lv]) {
            double along = (x + 0.5D - cx) * u[0] + (z + 0.5D - cz) * u[1];
            double lat = Math.abs(-(x + 0.5D - cx) * u[1] + (z + 0.5D - cz) * u[0]);
            if (along > 0 && lat <= 2.6D) {
                return true;
            }
        }
        return false;
    }

    // ---- links
    private void link(Link l) {
        int ax = nodeX(l.ai), az = nodeZ(l.aj), bx = nodeX(l.bi), bz = nodeZ(l.bj);
        boolean alongX = az == bz;
        int deck = this.decks[l.level];
        boolean aCastle = isCastle(l.ai, l.aj);
        if (l.log) {
            // castle to castle: the log lies from wall to wall, its top is the deck level
            int a0 = alongX ? ax : az, a1 = alongX ? bx : bz, lat = alongX ? az : ax;
            int sgn = a1 > a0 ? 1 : -1;
            int from = a0 + sgn * 4, to = a1 - sgn * 4;
            int n = Math.abs(to - from);
            int spacingL = Math.max(4, this.s.getInt("lantern_spacing"));
            for (int i = 0; i <= n; i++) {
                int a = from + sgn * i;
                for (int dl = -3; dl <= 3; dl++) {
                    for (int dy = -6; dy <= 0; dy++) {
                        double cy = dy + 3.0D;
                        if (dl * dl + cy * cy <= 12.75D) {
                            int x = alongX ? a : lat + dl, z = alongX ? lat + dl : a;
                            int y = deck + dy;
                            boolean topLayer = dy == 0 || (dy == -1 && Math.abs(dl) >= 2 && noise(x, y, z) < 0.35F);
                            put(x, y, z, topLayer ? Mat.MOSS : (alongX ? Mat.LOG_X : Mat.LOG_Z));
                        }
                    }
                }
                if (this.s.getBool("lanterns") && i % spacingL == spacingL / 2) {
                    int x = alongX ? a : lat + 2, z = alongX ? lat + 2 : a;
                    put(x, deck + 1, z, Mat.LANTERN);
                }
            }
            return;
        }
        // bridge: from the castle to the edge of the tree ring
        int cI = aCastle ? l.ai : l.bi, cJ = aCastle ? l.aj : l.bj;
        int tI = aCastle ? l.bi : l.ai, tJ = aCastle ? l.bj : l.aj;
        int cxn = nodeX(cI), czn = nodeZ(cJ), txn = nodeX(tI), tzn = nodeZ(tJ);
        int a0, a1, lat;
        int sgn;
        if (alongX) {
            sgn = txn > cxn ? 1 : -1;
            a0 = cxn + sgn * 3;
            a1 = txn - sgn * 10;
            lat = czn;
        } else {
            sgn = tzn > czn ? 1 : -1;
            a0 = czn + sgn * 3;
            a1 = tzn - sgn * 10;
            lat = cxn;
        }
        int n = Math.abs(a1 - a0);
        int spacingL = Math.max(4, this.s.getInt("lantern_spacing"));
        for (int i = 0; i <= n; i++) {
            int a = a0 + sgn * i;
            double t = i / (double) Math.max(n, 1);
            int y = deck + sag(t);
            for (int dl = -1; dl <= 1; dl++) {
                int x = alongX ? a : lat + dl, z = alongX ? lat + dl : a;
                put(x, y, z, Mat.PLANK);
            }
            for (int dl = -2; dl <= 2; dl += 4) {
                int x = alongX ? a : lat + dl, z = alongX ? lat + dl : a;
                put(x, y + 1, z, Mat.RAIL);
                if (i % 6 == 0 || i == 0 || i == n) {
                    put(x, y + 2, z, Mat.RAIL);
                    if (i % 6 == 0) {
                        put(x, y - 1, z, Mat.BEAM);
                    }
                }
            }
            if (this.s.getBool("lanterns") && i % spacingL == spacingL / 2) {
                int x = alongX ? a : lat + 2, z = alongX ? lat + 2 : a;
                put(x, y + 2, z, Mat.LANTERN);
            }
        }
    }

    // ---- filler trees in the empty gaps (not connected to anything)
    private void fillers() {
        double density = this.s.getDouble("filler_density");
        if (density <= 0) {
            return;
        }
        String[] vars = this.s.get("filler_variants").toLowerCase().split("[,\\s]+");
        List<String> ok = new ArrayList<>();
        for (String v : vars) {
            if (List.of("giant", "slender", "twin", "spruce", "mushroom", "dead", "willow", "stump").contains(v)) {
                ok.add(v);
            }
        }
        if (ok.isEmpty()) {
            return;
        }
        int rings = clamp(this.s.getInt("filler_rings"), 0, 3);
        int minH = clamp(this.s.getInt("filler_min_height"), 10, 200);
        int maxH = clamp(this.s.getInt("filler_max_height"), minH, 220);
        int range = clamp(this.s.getInt("filler_float_range"), 0, 120);
        for (int cj = -rings; cj <= this.rows - 2 + rings; cj++) {
            for (int ci = -rings; ci <= this.cols - 2 + rings; ci++) {
                if (this.rnd.nextDouble() >= density) {
                    continue;
                }
                int x = (int) Math.round((ci + 0.5D) * this.sp) + this.rnd.nextInt(5) - 2;
                int z = (int) Math.round((cj + 0.5D) * this.sp) + this.rnd.nextInt(5) - 2;
                int h = minH + this.rnd.nextInt(maxH - minH + 1);
                int y0 = this.yb + this.rnd.nextInt(range * 2 + 1) - range;
                String v = ok.get(this.rnd.nextInt(ok.size()));
                filler(v, x, z, y0, h);
                this.fillers++;
            }
        }
    }

    private void filler(String v, int nx, int nz, int y0, int h) {
        double cx = nx + 0.5D, cz = nz + 0.5D;
        int top = y0 + h;
        switch (v) {
            case "giant": {
                double r = 3.0D + this.rnd.nextDouble() * 1.5D;
                trunk(nx, nz, y0, top, r, r, 8);
                double cr = Math.min(8.5D, r * 2.0D + 2.5D);
                ellipsoid(cx, top + 2, cz, cr, 5, cr, Mat.LEAF, 0.94D);
                for (int n = 0; n < 2; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI;
                    int y = y0 + h / 2 + this.rnd.nextInt(Math.max(1, h / 3));
                    ellipsoid(cx + 5 * Math.cos(a), y, cz + 5 * Math.sin(a), 4.5D, 3.5D, 4.5D, Mat.LEAF, 0.92D);
                }
                break;
            }
            case "slender": {
                double r = 1.4D + this.rnd.nextDouble() * 1.0D;
                trunk(nx, nz, y0, top, r, r, 6);
                ellipsoid(cx, top + 2, cz, 4.5D, 6, 4.5D, Mat.LEAF3, 0.94D);
                break;
            }
            case "twin": {
                boolean xAxis = this.rnd.nextBoolean();
                int o = 3;
                trunk(nx - (xAxis ? o : 0), nz - (xAxis ? 0 : o), y0, top, 2.3D, 2.3D, 6);
                trunk(nx + (xAxis ? o : 0), nz + (xAxis ? 0 : o), y0 + 4, top - 6, 2.3D, 2.3D, 5);
                ellipsoid(cx, top + 1, cz, xAxis ? 8.5D : 5.5D, 5, xAxis ? 5.5D : 8.5D, Mat.LEAF, 0.93D);
                break;
            }
            case "spruce": {
                trunk(nx, nz, y0, top + 3, 1.8D, 1.8D, 6);
                int start = y0 + Math.max(8, h / 4);
                int tiers = Math.max(3, (top + 4 - start) / 4);
                for (int t = 0; t < tiers; t++) {
                    double r = 7.5D * (1.0D - t / (double) tiers) + 1.0D;
                    int y = start + t * 4;
                    disk(nx, nz, r, y, y + 1, Mat.LEAF2, -1, true);
                    disk(nx, nz, Math.max(1, r - 2.5D), y + 2, y + 2, Mat.LEAF2, -1, true);
                }
                break;
            }
            case "mushroom": {
                double r = 2.0D + this.rnd.nextDouble();
                int th = Math.max(12, h * 6 / 10);
                trunk(nx, nz, y0, y0 + th, r, r, 6);
                ellipsoid(cx, y0 + th + 1, cz, 9, 3.5D, 9, Mat.LEAF3, 0.97D);
                for (int n = 0; n < 6; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI;
                    int x = (int) Math.round(cx - 0.5D + 6 * Math.cos(a)), z = (int) Math.round(cz - 0.5D + 6 * Math.sin(a));
                    int len = 3 + this.rnd.nextInt(6);
                    for (int y = y0 + th - len; y < y0 + th - 1; y++) {
                        putAir(x, y, z, Mat.VINE);
                    }
                }
                break;
            }
            case "dead": {
                trunk(nx, nz, y0, top, 2.5D, 2.5D, 6);
                int branches = 4 + this.rnd.nextInt(3);
                for (int b = 0; b < branches; b++) {
                    int[] d = DIRS[this.rnd.nextInt(4)];
                    int y = y0 + h / 3 + this.rnd.nextInt(Math.max(1, h * 2 / 3));
                    int len = 4 + this.rnd.nextInt(5);
                    for (int i = 3; i < 3 + len; i++) {
                        int x = nx + d[0] * i, z = nz + d[1] * i;
                        put(x, y + i / 3, z, d[0] != 0 ? Mat.LOG_X : Mat.LOG_Z);
                    }
                }
                break;
            }
            case "willow": {
                trunk(nx, nz, y0, top, 2.6D, 2.6D, 7);
                ellipsoid(cx, top + 1, cz, 7.5D, 4, 7.5D, Mat.LEAF, 0.94D);
                for (int n = 0; n < 26; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI, rr = 3 + this.rnd.nextDouble() * 4.5D;
                    int x = (int) Math.round(cx - 0.5D + rr * Math.cos(a)), z = (int) Math.round(cz - 0.5D + rr * Math.sin(a));
                    int len = 8 + this.rnd.nextInt(12);
                    for (int y = top - len; y < top; y++) {
                        putAir(x, y, z, Mat.VINE);
                    }
                }
                break;
            }
            default: { // stump: short wide trunk with a railed platform on top
                double r = 5.0D + this.rnd.nextDouble() * 2.0D;
                int sh = 8 + this.rnd.nextInt(9);
                trunk(nx, nz, y0, y0 + sh, r, r, 6);
                disk(nx, nz, r + 3, y0 + sh, y0 + sh, Mat.PLANK, r, false);
                for (int x = nx - 12; x <= nx + 12; x++) {
                    for (int z = nz - 12; z <= nz + 12; z++) {
                        double d2 = (x + 0.5D - cx) * (x + 0.5D - cx) + (z + 0.5D - cz) * (z + 0.5D - cz);
                        if (d2 >= (r + 2.1D) * (r + 2.1D) && d2 <= (r + 3) * (r + 3)) {
                            put(x, y0 + sh + 1, z, Mat.RAIL);
                        }
                    }
                }
                ellipsoid(cx, y0 + sh + 3, cz, 2.5D, 2, 2.5D, Mat.LEAF3, 0.9D);
                break;
            }
        }
        roots2(nx, nz, y0);
    }

    private void roots2(int nx, int nz, int y0) {
        for (int n = 0; n < 3; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI, rr = this.rnd.nextDouble() * 1.8D;
            int x = (int) Math.round(nx + rr * Math.cos(a)), z = (int) Math.round(nz + rr * Math.sin(a));
            int len = 4 + this.rnd.nextInt(7);
            for (int y = y0 - len; y < y0; y++) {
                putAir(x, y, z, this.rnd.nextDouble() < 0.6D ? Mat.BEAM : Mat.VINE);
            }
        }
    }

    public int levels() {
        return this.levels;
    }

    public int[] decks() {
        return this.decks.clone();
    }

    public int cols() {
        return this.cols;
    }

    public int rows() {
        return this.rows;
    }
}
