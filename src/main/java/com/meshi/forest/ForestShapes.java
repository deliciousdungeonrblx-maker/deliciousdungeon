package com.meshi.forest;

import static com.meshi.forest.ForestDefs.DECKS;
import static com.meshi.forest.ForestDefs.HEIGHT;

import com.meshi.forest.ForestDefs.Def;
import com.meshi.forest.PartGrid.Mat;
import java.util.ArrayDeque;
import java.util.Random;

// the generated (not edited) look of every part. pure java. coords are relative to the part anchor:
// x / z = 0 is the middle block, y = 0 is the base. an edited part saved ingame replaces all of this
final class ForestShapes {
    static final double CASTLE_RO = 6.5D;
    static final double CASTLE_RI = 4.5D;
    static final double TREE_R = 4.5D;
    static final double RING_R = 10.5D;
    static final int BASE_H = 6;

    private final PartGrid g;
    private final Random rnd;
    private final long seed;

    private ForestShapes(Def d, long seed) {
        this.g = new PartGrid(d);
        this.seed = seed;
        this.rnd = new Random(seed);
    }

    static PartGrid generate(Def d, long seed) {
        ForestShapes s = new ForestShapes(d, seed);
        switch (d.kind) {
            case CASTLE -> s.castle(false);
            case CASTLE_NEST -> s.castle(true);
            case TREE -> s.tree(false);
            case TREE_BAT -> s.tree(true);
            case BRIDGE -> s.bridge();
            case LOG -> s.log();
            default -> s.filler(d.filler, d.height);
        }
        s.pruneLeaves();
        return s.g;
    }

    // ---- helpers
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
                            this.g.putAir(x, y, z, m);
                        } else {
                            this.g.put(x, y, z, m);
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
                        this.g.putAir(x, y, z, m);
                    }
                }
            }
        }
    }

    static int sag(double t) {
        return -(int) Math.round(2 * 4 * t * (1 - t));
    }

    private void trunk(int nx, int nz, int y0, int y1, double rBase, double rTop, int flare) {
        for (int y = y0; y <= y1; y++) {
            double r = y >= y0 + flare ? rTop : 1.5D + (y - y0) * (rBase - 1.5D) / Math.max(1, flare);
            disk(nx, nz, r, y, y, Mat.TRUNK, -1, false);
        }
    }

    // hanging roots under the bottom of a tree
    private void roots(int n, double reach, int y0, int minLen, int lenRange) {
        for (int i = 0; i < n; i++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI, rr = this.rnd.nextDouble() * reach;
            int x = (int) Math.round(rr * Math.cos(a));
            int z = (int) Math.round(rr * Math.sin(a));
            int len = minLen + this.rnd.nextInt(lenRange);
            for (int y = y0 - len; y < y0; y++) {
                this.g.putAir(x, y, z, this.rnd.nextDouble() < 0.6D ? Mat.BEAM : Mat.VINE);
            }
        }
    }

    // ---- castle
    private void doorway(int face, int deck, int w, int h) {
        for (int l = -(w / 2); l <= w / 2; l++) {
            for (int r = 3; r <= 8; r++) {
                int x = ForestDefs.dx(face, r, l), z = ForestDefs.dz(face, r, l);
                for (int yy = deck + 1; yy <= deck + h; yy++) {
                    if (w > 3 && Math.abs(l) == w / 2 && yy == deck + h) {
                        continue;
                    }
                    this.g.carve(x, yy, z);
                }
            }
        }
    }

    // straw nest in the big opening, 3 eggs
    private void nest(int face, int deck) {
        for (int l = -3; l <= 3; l++) {
            for (int r = 2; r <= 8; r++) {
                int d = l * l + (r - 5) * (r - 5);
                if (d <= 9) {
                    int x = ForestDefs.dx(face, r, l), z = ForestDefs.dz(face, r, l);
                    this.g.put(x, deck, z, Mat.NEST);
                    this.g.put(x, deck + 1, z, d >= 6 ? Mat.NEST : Mat.AIR);
                }
            }
        }
        int[][] eggs = {{-1, 4}, {1, 4}, {0, 5}};
        for (int[] e : eggs) {
            int x = ForestDefs.dx(face, e[1], e[0]), z = ForestDefs.dz(face, e[1], e[0]);
            this.g.put(x, deck + 1, z, Mat.EGG);
            this.g.put(x, deck + 2, z, Mat.EGG);
        }
    }

    // 2 wide, 4 tall hole straight through the wall on the north + south face
    private void window(int sgn, int y0) {
        for (int along = 3; along <= 8; along++) {
            for (int x = -1; x <= 0; x++) {
                for (int y = y0; y < y0 + 4; y++) {
                    if (along >= 4 || y > y0) {
                        this.g.carve(x, y, sgn * along);
                    }
                }
            }
        }
    }

    private void castle(boolean nest) {
        int top = HEIGHT;
        for (int y = 0; y <= top; y++) {
            if (y < BASE_H) {
                disk(0, 0, 2.5D + y * (4.0D / BASE_H), y, y, Mat.STONE, -1, false);
            } else {
                disk(0, 0, CASTLE_RO, y, y, Mat.STONE, -1, false);
                disk(0, 0, CASTLE_RI, y, y, Mat.AIR, -1, false);
            }
        }
        for (int d : DECKS) {
            disk(0, 0, CASTLE_RI, d, d, Mat.PLANK, -1, false);
        }
        disk(0, 0, CASTLE_RI, BASE_H, BASE_H, Mat.PLANK, -1, false);
        // moss patches on the outside
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double d2 = x * x + z * z;
                if (d2 > 5.2D * 5.2D && d2 <= CASTLE_RO * CASTLE_RO) {
                    double stripe = (Math.sin(x * 1.7D + z * 2.3D) + 1) / 2;
                    for (int y = 0; y <= top; y++) {
                        if (this.g.get(x, y, z) == Mat.STONE
                                && noise(x, y, z) < 0.10D + 0.20D * stripe * (1 - y / (double) HEIGHT * 0.6D)) {
                            this.g.put(x, y, z, Mat.MSTONE);
                        }
                    }
                }
            }
        }
        // battlements
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                double d2 = x * x + z * z;
                if (d2 > 5.4D * 5.4D && d2 <= CASTLE_RO * CASTLE_RO) {
                    double ang = Math.atan2(z, x);
                    if ((int) ((ang + Math.PI) / (2 * Math.PI) * 12) % 2 == 0) {
                        this.g.put(x, top + 1, z, Mat.STONE);
                        this.g.put(x, top + 2, z, Mat.STONE);
                    }
                }
            }
        }
        // light ledge under every level
        for (int d : DECKS) {
            disk(0, 0, 7.5D, d - 2, d - 1, Mat.BAND, 6.4D, true);
        }
        // long vines on the walls + a few hanging under the bottom
        for (int n = 0; n < 7; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI;
            int x = (int) Math.round(7.0D * Math.cos(a));
            int z = (int) Math.round(7.0D * Math.sin(a));
            int y0 = 4 + this.rnd.nextInt(Math.max(1, top - 30 - 4));
            int len = 14 + this.rnd.nextInt(25);
            for (int y = y0; y < Math.min(top, y0 + len); y++) {
                this.g.putAir(x, y, z, Mat.VINE);
            }
        }
        roots(4, 2.0D, 0, 4, 7);
        // doorways on all 4 sides of every level. east = the wide log door
        for (int lv = 0; lv < ForestDefs.LEVELS; lv++) {
            for (int f = 0; f < 4; f++) {
                if (nest && f == 3 && lv == ForestDefs.NEST_LEVEL) {
                    doorway(f, DECKS[lv], 7, 7);
                    nest(f, DECKS[lv]);
                } else if (f == 0) {
                    doorway(f, DECKS[lv], 5, 5);
                } else {
                    doorway(f, DECKS[lv], 3, 4);
                }
            }
        }
        // windows on the front and back faces
        for (int lv = 0; lv < ForestDefs.LEVELS; lv++) {
            for (int row = 0; row < 2; row++) {
                int y0 = DECKS[lv] + 9 + row * 8;
                if (y0 + 4 > top - 2) {
                    continue;
                }
                window(1, y0);
                window(-1, y0);
            }
        }
        // ladder shaft in one diagonal corner, goes through every deck
        int q = this.rnd.nextInt(4);
        int sx = (q & 1) == 0 ? 1 : -1, sz = (q & 2) == 0 ? 1 : -1;
        int lx = sx > 0 ? 2 : -3;
        int lz = sz > 0 ? 3 : -4;
        int wallX = sx > 0 ? lx + 1 : lx - 1;
        for (int y = DECKS[ForestDefs.LEVELS - 1]; y <= DECKS[0] + 1; y++) {
            this.g.put(wallX, y, lz, Mat.STONE);
            this.g.put(lx, y, lz, sx > 0 ? Mat.LADDER_W : Mat.LADDER_E);
        }
        // lanterns in the opposite corner
        int ox = sx > 0 ? -3 : 2, oz = sz > 0 ? -4 : 3;
        for (int d : DECKS) {
            this.g.put(ox, d + 1, oz, Mat.LANTERN);
        }
    }

    // ---- main tree
    private void tree(boolean bat) {
        int top = HEIGHT;
        trunk(0, 0, 0, top, TREE_R, TREE_R, 8);
        roots(5, 2.5D, 0, 5, 7);
        for (int d : DECKS) {
            disk(0, 0, RING_R, d, d, Mat.PLANK, TREE_R, false);
            disk(0, 0, 7.5D, d - 1, d - 1, Mat.BEAM, 5.4D, false);
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    double d2 = x * x + z * z;
                    // gap in the railing on all 4 sides
                    boolean gap = (Math.abs(z) <= 2.6D && x != 0) || (Math.abs(x) <= 2.6D && z != 0);
                    if (d2 >= 9.6D * 9.6D && d2 <= RING_R * RING_R && !gap) {
                        this.g.put(x, d + 1, z, Mat.RAIL);
                        this.g.put(x, d + 2, z, Mat.RAIL);
                    }
                }
            }
        }
        // big bat hole on the east side of the top platform
        if (bat) {
            int hy = DECKS[0] + 9;
            for (int l = -3; l <= 3; l++) {
                for (int yy = -4; yy <= 4; yy++) {
                    if ((l / 3.5D) * (l / 3.5D) + (yy / 4.7D) * (yy / 4.7D) <= 1) {
                        for (int r = 2; r <= 6; r++) {
                            this.g.carve(r, hy + yy, l);
                        }
                    }
                }
            }
        }
        // ladder up the trunk, opposite the hole when there is one
        int sx = bat ? -1 : 1;
        int lx = sx > 0 ? 3 : -4, lz = 3;
        int topLadder = bat ? DECKS[0] + 9 : DECKS[0] + 1;
        for (int y = DECKS[ForestDefs.LEVELS - 1]; y <= topLadder; y++) {
            this.g.put(lx, y, lz, sx > 0 ? Mat.LADDER_E : Mat.LADDER_W);
        }
        // canopy
        ellipsoid(0.5D, top + 3, 0.5D, 13, 7, 13, Mat.LEAF, 0.93D);
        for (int n = 0; n < 3; n++) {
            double a = this.rnd.nextDouble() * 2 * Math.PI;
            int y = 22 + this.rnd.nextInt(Math.max(1, top - 18 - 22));
            ellipsoid(0.5D + 6.5D * Math.cos(a), y, 0.5D + 6.5D * Math.sin(a), 6.5D, 4.5D, 6.5D, Mat.LEAF, 0.92D);
        }
        // no leaves on the platforms (they used to block the railing gaps and the bridge landings)
        for (int d : DECKS) {
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    if (x * x + z * z <= 12 * 12) {
                        for (int y = d + 1; y <= d + 4; y++) {
                            if (isLeaf(this.g.get(x, y, z))) {
                                this.g.setAt(x + this.g.ax, y + this.g.ay, z + this.g.az, null);
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- bridge: castle (x = 0) to tree ring, y 0 = the deck
    private void bridge() {
        int n = ForestDefs.BRIDGE_LEN;
        for (int i = 0; i <= n; i++) {
            double t = i / (double) Math.max(n, 1);
            int y = sag(t);
            for (int dl = -1; dl <= 1; dl++) {
                this.g.put(i, y, dl, Mat.PLANK);
            }
            for (int dl = -2; dl <= 2; dl += 4) {
                this.g.put(i, y + 1, dl, Mat.RAIL);
                if (i % 6 == 0 || i == 0 || i == n) {
                    this.g.put(i, y + 2, dl, Mat.RAIL);
                    if (i % 6 == 0) {
                        this.g.put(i, y - 1, dl, Mat.BEAM);
                    }
                }
            }
            if (i % 12 == 6) {
                this.g.put(i, y + 2, 2, Mat.LANTERN);
            }
        }
    }

    // ---- mossy log: castle wall to castle wall, top of the log = deck level
    private void log() {
        int n = ForestDefs.LOG_LEN;
        for (int i = 0; i <= n; i++) {
            for (int dl = -3; dl <= 3; dl++) {
                for (int dy = -6; dy <= 0; dy++) {
                    double cy = dy + 3.0D;
                    if (dl * dl + cy * cy <= 12.75D) {
                        boolean topLayer = dy == 0 || (dy == -1 && Math.abs(dl) >= 2 && noise(i, dy, dl) < 0.35F);
                        this.g.put(i, dy, dl, topLayer ? Mat.MOSS : Mat.LOG_X);
                    }
                }
            }
            if (i % 12 == 6) {
                this.g.put(i, 1, 2, Mat.LANTERN);
            }
        }
    }

    // ---- filler trees (float on their own in the gaps)
    private void filler(String v, int h) {
        int top = h;
        switch (v) {
            case "giant" -> {
                double r = 3.0D + this.rnd.nextDouble() * 1.5D;
                trunk(0, 0, 0, top, r, r, 8);
                double cr = Math.min(8.5D, r * 2.0D + 2.5D);
                ellipsoid(0.5D, top + 2, 0.5D, cr, 5, cr, Mat.LEAF, 0.94D);
                for (int n = 0; n < 2; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI;
                    int y = h / 2 + this.rnd.nextInt(Math.max(1, h / 3));
                    ellipsoid(0.5D + 5 * Math.cos(a), y, 0.5D + 5 * Math.sin(a), 4.5D, 3.5D, 4.5D, Mat.LEAF, 0.92D);
                }
            }
            case "slender" -> {
                double r = 1.4D + this.rnd.nextDouble();
                trunk(0, 0, 0, top, r, r, 6);
                ellipsoid(0.5D, top + 2, 0.5D, 4.5D, 6, 4.5D, Mat.LEAF3, 0.94D);
            }
            case "twin" -> {
                // the two trunks touch each other (they used to float apart)
                boolean xAxis = this.rnd.nextBoolean();
                int o = 2;
                trunk(xAxis ? -o : 0, xAxis ? 0 : -o, 0, top, 2.3D, 2.3D, 6);
                trunk(xAxis ? o : 0, xAxis ? 0 : o, 4, top - 6, 2.3D, 2.3D, 5);
                ellipsoid(0.5D, top + 1, 0.5D, xAxis ? 8.5D : 5.5D, 5, xAxis ? 5.5D : 8.5D, Mat.LEAF, 0.93D);
            }
            case "spruce" -> {
                trunk(0, 0, 0, top + 3, 1.8D, 1.8D, 6);
                int start = Math.max(8, h / 4);
                int tiers = Math.max(3, (top + 4 - start) / 4);
                for (int t = 0; t < tiers; t++) {
                    double r = 7.5D * (1.0D - t / (double) tiers) + 1.0D;
                    int y = start + t * 4;
                    disk(0, 0, r, y, y + 1, Mat.LEAF2, -1, true);
                    disk(0, 0, Math.max(1, r - 2.5D), y + 2, y + 2, Mat.LEAF2, -1, true);
                }
            }
            case "mushroom" -> {
                double r = 2.0D + this.rnd.nextDouble();
                int th = Math.max(12, h * 6 / 10);
                trunk(0, 0, 0, th, r, r, 6);
                ellipsoid(0.5D, th + 1, 0.5D, 9, 3.5D, 9, Mat.LEAF3, 0.97D);
                for (int n = 0; n < 6; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI;
                    int x = (int) Math.round(6 * Math.cos(a)), z = (int) Math.round(6 * Math.sin(a));
                    int len = 3 + this.rnd.nextInt(6);
                    for (int y = th - len; y < th - 1; y++) {
                        this.g.putAir(x, y, z, Mat.VINE);
                    }
                }
            }
            case "dead" -> {
                trunk(0, 0, 0, top, 2.5D, 2.5D, 6);
                int branches = 4 + this.rnd.nextInt(3);
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                for (int b = 0; b < branches; b++) {
                    int[] d = dirs[this.rnd.nextInt(4)];
                    int y = h / 3 + this.rnd.nextInt(Math.max(1, h * 2 / 3));
                    int len = 4 + this.rnd.nextInt(5);
                    for (int i = 3; i < 3 + len; i++) {
                        this.g.put(d[0] * i, y + i / 3, d[1] * i, d[0] != 0 ? Mat.LOG_X : Mat.LOG_Z);
                    }
                }
            }
            case "willow" -> {
                trunk(0, 0, 0, top, 2.6D, 2.6D, 7);
                ellipsoid(0.5D, top + 1, 0.5D, 7.5D, 4, 7.5D, Mat.LEAF, 0.94D);
                for (int n = 0; n < 26; n++) {
                    double a = this.rnd.nextDouble() * 2 * Math.PI, rr = 3 + this.rnd.nextDouble() * 4.5D;
                    int x = (int) Math.round(rr * Math.cos(a)), z = (int) Math.round(rr * Math.sin(a));
                    int len = 8 + this.rnd.nextInt(12);
                    for (int y = top - len; y < top; y++) {
                        this.g.putAir(x, y, z, Mat.VINE);
                    }
                }
            }
            default -> { // stump: short wide trunk with a railed platform on top
                double r = 5.0D + this.rnd.nextDouble() * 2.0D;
                int sh = 6 + h / 12 + this.rnd.nextInt(7);
                trunk(0, 0, 0, sh, r, r, 6);
                disk(0, 0, r + 3, sh, sh, Mat.PLANK, r, false);
                for (int x = -12; x <= 12; x++) {
                    for (int z = -12; z <= 12; z++) {
                        double d2 = x * x + z * z;
                        if (d2 >= (r + 2.1D) * (r + 2.1D) && d2 <= (r + 3) * (r + 3)) {
                            this.g.put(x, sh + 1, z, Mat.RAIL);
                        }
                    }
                }
                ellipsoid(0.5D, sh + 3, 0.5D, 2.5D, 2, 2.5D, Mat.LEAF3, 0.9D);
            }
        }
        roots(3, 1.8D, 0, 4, 7);
    }

    // leaves that are more than 6 blocks of leaves away from wood would float on their own, remove them
    private void pruneLeaves() {
        int sx = this.g.sx, sy = this.g.sy, sz = this.g.sz;
        int[] dist = new int[sx * sy * sz];
        java.util.Arrays.fill(dist, -1);
        ArrayDeque<int[]> q = new ArrayDeque<>();
        boolean anyLeaf = false;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    Mat m = this.g.at(x, y, z);
                    if (m == Mat.TRUNK || m == Mat.LOG_X || m == Mat.LOG_Z) {
                        dist[(x * sy + y) * sz + z] = 0;
                        q.add(new int[] {x, y, z});
                    } else if (isLeaf(m)) {
                        anyLeaf = true;
                    }
                }
            }
        }
        if (!anyLeaf) {
            return;
        }
        int[][] n6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int dc = dist[(c[0] * sy + c[1]) * sz + c[2]];
            if (dc >= 6) {
                continue;
            }
            for (int[] d : n6) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) {
                    continue;
                }
                int i = (x * sy + y) * sz + z;
                if (dist[i] == -1 && isLeaf(this.g.at(x, y, z))) {
                    dist[i] = dc + 1;
                    q.add(new int[] {x, y, z});
                }
            }
        }
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    if (isLeaf(this.g.at(x, y, z)) && dist[(x * sy + y) * sz + z] == -1) {
                        this.g.setAt(x, y, z, null);
                    }
                }
            }
        }
    }

    private static boolean isLeaf(Mat m) {
        return m == Mat.LEAF || m == Mat.LEAF2 || m == Mat.LEAF3;
    }
}
