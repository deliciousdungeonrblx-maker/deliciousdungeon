package com.meshi.forest;

import static com.meshi.forest.ForestDefs.BASE_Y;
import static com.meshi.forest.ForestDefs.DECKS;
import static com.meshi.forest.ForestDefs.LEVELS;
import static com.meshi.forest.ForestDefs.SPACING;

import com.meshi.forest.ForestDefs.Def;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

// where everything of floor 2 goes: a list of "put this part here, turned this way". pure java.
// same radius + seed = same plan, so regenerate and "part apply" can work out what was built before
public final class ForestPlan {
    public static final int MIN_RADIUS = 80;
    public static final int MAX_RADIUS = 1500;
    public static final int DEFAULT_RADIUS = 600;

    public static final class Placement {
        public final String part;
        public final Def def;
        public final int x, y, z, rot;
        public final long seed;
        // indexes into def.doors of the doorways / gaps to fill in (no bridge or log goes through them)
        public final int[] sealed;

        Placement(Def def, int x, int y, int z, int rot, long seed, int[] sealed) {
            this.part = def.key;
            this.def = def;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rot = rot;
            this.seed = seed;
            this.sealed = sealed;
        }

        // x0 y0 z0 x1 y1 z1 in the world
        public int[] box() {
            return this.def.worldBox(this.x, this.y, this.z, this.rot);
        }
    }

    public final int radius;
    public final long seed;
    public final List<Placement> placements = new ArrayList<>();
    public int castles, trees, nests, bats, bridges, logs, fillers;
    public int[] spawn = new int[3];
    public int[] exit = new int[3];

    private static final int[] FX = {1, 0, -1, 0};
    private static final int[] FZ = {0, 1, 0, -1};

    private ForestPlan(int radius, long seed) {
        this.radius = radius;
        this.seed = seed;
    }

    static boolean isCastle(int i, int j) {
        return Math.floorMod(i + 2 * (j & 1), 4) < 2;
    }

    private static long key(int i, int j) {
        return ((long) i << 32) ^ (j & 0xFFFFFFFFL);
    }

    private static long mix(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L + b;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h;
    }

    private static int find(int[] p, int a) {
        while (p[a] != a) {
            p[a] = p[p[a]];
            a = p[a];
        }
        return a;
    }

    private static boolean join(int[] p, int[] e) {
        int a = find(p, e[0]), b = find(p, e[1]);
        if (a == b) {
            return false;
        }
        p[a] = b;
        return true;
    }

    public static ForestPlan build(int radius, long seed) {
        radius = Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
        ForestPlan plan = new ForestPlan(radius, seed);
        // ---- nodes: every grid point inside the circle
        int span = radius / SPACING + 1;
        List<int[]> all = new ArrayList<>();
        Map<Long, Integer> idx = new HashMap<>();
        for (int j = -span; j <= span; j++) {
            for (int i = -span; i <= span; i++) {
                double x = i * (double) SPACING, z = j * (double) SPACING;
                if (x * x + z * z <= (double) radius * radius) {
                    idx.put(key(i, j), all.size());
                    all.add(new int[] {i, j});
                }
            }
        }
        // ---- edges: node a, node b, face from a to b, kind (0 log = castle-castle, 1 bridge = castle-tree)
        List<int[]> edges = new ArrayList<>();
        for (int a = 0; a < all.size(); a++) {
            int i = all.get(a)[0], j = all.get(a)[1];
            for (int f = 0; f < 2; f++) {
                Integer b = idx.get(key(i + FX[f], j + FZ[f]));
                if (b == null) {
                    continue;
                }
                boolean ca = isCastle(i, j), cb = isCastle(i + FX[f], j + FZ[f]);
                if (!ca && !cb) {
                    continue; // trees never link to trees
                }
                edges.add(new int[] {a, b, f, ca && cb ? 0 : 1});
            }
        }
        // ---- only the part connected to the middle castle gets built
        int n = all.size();
        int[] comp = new int[n];
        for (int i = 0; i < n; i++) {
            comp[i] = i;
        }
        for (int[] e : edges) {
            join(comp, e);
        }
        int origin = idx.get(key(0, 0));
        int root = find(comp, origin);
        boolean[] in = new boolean[n];
        int included = 0;
        for (int i = 0; i < n; i++) {
            in[i] = find(comp, i) == root;
            if (in[i]) {
                included++;
            }
        }
        // ---- one spanning tree + a few loops per level
        int[][] used = new int[LEVELS][n];
        List<int[]>[] chosen = new List[LEVELS];
        for (int lv = 0; lv < LEVELS; lv++) {
            Random r = new Random(mix(seed, 1000 + lv));
            int[] p = new int[n];
            for (int i = 0; i < n; i++) {
                p[i] = i;
            }
            List<int[]> logs = new ArrayList<>(), bridges = new ArrayList<>();
            for (int[] e : edges) {
                if (in[e[0]] && in[e[1]]) {
                    (e[3] == 0 ? logs : bridges).add(e);
                }
            }
            List<int[]> pick = new ArrayList<>();
            List<int[]> rest = new ArrayList<>(bridges);
            Collections.shuffle(logs, r);
            for (int[] e : logs) {
                if (r.nextDouble() < 0.7D && join(p, e)) {
                    pick.add(e);
                } else {
                    rest.add(e);
                }
            }
            Collections.shuffle(rest, r);
            List<int[]> loops = new ArrayList<>();
            for (int[] e : rest) {
                if (join(p, e)) {
                    pick.add(e);
                } else {
                    loops.add(e);
                }
            }
            int extras = Math.max(2, included / 12);
            for (int k = 0; k < loops.size() && k < extras; k++) {
                pick.add(loops.get(k));
            }
            chosen[lv] = pick;
            for (int[] e : pick) {
                used[lv][e[0]] |= 1 << e[2];
                used[lv][e[1]] |= 1 << ((e[2] + 2) & 3);
            }
        }
        // ---- roles: spawn castle in the middle, exit = the castle farthest away, nests / bat trees
        Random pr = new Random(mix(seed, 7));
        int exitNode = origin;
        double best = -1;
        List<Integer> castleNodes = new ArrayList<>(), treeNodes = new ArrayList<>();
        for (int a = 0; a < n; a++) {
            if (!in[a]) {
                continue;
            }
            int i = all.get(a)[0], j = all.get(a)[1];
            if (isCastle(i, j)) {
                castleNodes.add(a);
                double d = (double) i * i + (double) j * j;
                if (d > best) {
                    best = d;
                    exitNode = a;
                }
            } else {
                treeNodes.add(a);
            }
        }
        int nestWant = Math.max(2, castleNodes.size() / 8);
        int batWant = Math.max(2, treeNodes.size() / 8);
        java.util.Set<Integer> nestSet = new java.util.HashSet<>();
        List<Integer> cand = new ArrayList<>(castleNodes);
        Collections.shuffle(cand, pr);
        for (int a : cand) {
            if (nestSet.size() >= nestWant) {
                break;
            }
            int i = all.get(a)[0], j = all.get(a)[1];
            int rot = isCastle(i + 1, j) ? 0 : 2;
            // the nest opening (north in the saved part) must not be used by a bridge on its level
            boolean free = (used[ForestDefs.NEST_LEVEL][a] & (1 << ((3 + rot) & 3))) == 0;
            if (a != origin && a != exitNode && free) {
                nestSet.add(a);
            }
        }
        java.util.Map<Integer, Integer> batRot = new HashMap<>();
        List<Integer> tc = new ArrayList<>(treeNodes);
        Collections.shuffle(tc, pr);
        for (int a : tc) {
            if (batRot.size() >= batWant) {
                break;
            }
            for (int f = 0; f < 4; f++) {
                if ((used[0][a] & (1 << f)) == 0) {
                    batRot.put(a, f); // the hole is on face 0 of the part, turned to face f
                    break;
                }
            }
        }
        // ---- castles, trees
        Def castle = ForestDefs.byKey("castle"), castleNest = ForestDefs.byKey("castle_nest");
        Def tree = ForestDefs.byKey("tree"), treeBat = ForestDefs.byKey("tree_bat");
        for (int a : castleNodes) {
            int i = all.get(a)[0], j = all.get(a)[1];
            int rot = isCastle(i + 1, j) ? 0 : 2;
            Def d = nestSet.contains(a) ? castleNest : castle;
            plan.add(d, i * SPACING, BASE_Y, j * SPACING, rot, mix(seed, key(i, j)), sealed(d, rot, used, a));
            plan.castles++;
            if (d == castleNest) {
                plan.nests++;
            }
        }
        for (int a : treeNodes) {
            int i = all.get(a)[0], j = all.get(a)[1];
            long ps = mix(seed, key(i, j));
            if (batRot.containsKey(a)) {
                plan.add(treeBat, i * SPACING, BASE_Y, j * SPACING, batRot.get(a), ps, sealed(treeBat, batRot.get(a), used, a));
                plan.bats++;
            } else {
                int rot = (int) ((ps >>> 7) & 3);
                plan.add(tree, i * SPACING, BASE_Y, j * SPACING, rot, ps, sealed(tree, rot, used, a));
            }
            plan.trees++;
        }
        // ---- links: bridges start 3 blocks from a castle, logs 4 blocks from the first castle
        Def bridge = ForestDefs.byKey("bridge"), log = ForestDefs.byKey("log");
        for (int lv = 0; lv < LEVELS; lv++) {
            for (int[] e : chosen[lv]) {
                int[] pa = all.get(e[0]), pb = all.get(e[1]);
                int f = e[2];
                int y = BASE_Y + DECKS[lv];
                if (e[3] == 0) {
                    plan.add(log, pa[0] * SPACING + FX[f] * 4, y, pa[1] * SPACING + FZ[f] * 4, f, mix(seed, 31 + lv), new int[0]);
                    plan.logs++;
                } else {
                    boolean aCastle = isCastle(pa[0], pa[1]);
                    int[] c = aCastle ? pa : pb;
                    int cf = aCastle ? f : (f + 2) & 3; // face from the castle towards the tree
                    plan.add(bridge, c[0] * SPACING + FX[cf] * 3, y, c[1] * SPACING + FZ[cf] * 3, cf, mix(seed, 57 + lv), new int[0]);
                    plan.bridges++;
                }
            }
        }
        // ---- filler trees in the gaps between the nodes, kept off the castles and trees
        Random fr = new Random(mix(seed, 99));
        int gspan = radius / SPACING + 2;
        for (int cj = -gspan; cj <= gspan; cj++) {
            for (int ci = -gspan; ci <= gspan; ci++) {
                double gx = (ci + 0.5D) * SPACING, gz = (cj + 0.5D) * SPACING;
                if (Math.hypot(gx, gz) > radius + SPACING) {
                    continue;
                }
                if (fr.nextDouble() >= 0.9D) {
                    continue;
                }
                int x = (int) Math.round(gx) + fr.nextInt(5) - 2;
                int z = (int) Math.round(gz) + fr.nextInt(5) - 2;
                int y0 = BASE_Y + fr.nextInt(91) - 45;
                for (int attempt = 0; attempt < 4; attempt++) {
                    int k = fr.nextInt(ForestDefs.FILLER_KINDS.length);
                    int s = fr.nextInt(3);
                    if (!fits(x, z, ForestDefs.FILLER_R[k], all, idx, in)) {
                        continue;
                    }
                    Def d = ForestDefs.byKey(ForestDefs.FILLER_KINDS[k] + "_" + ForestDefs.SIZE_NAMES[s]);
                    plan.add(d, x, y0, z, fr.nextInt(4), mix(seed, key(ci, cj) + 5), new int[0]);
                    plan.fillers++;
                    break;
                }
            }
        }
        int[] oc = all.get(origin), ec = all.get(exitNode);
        plan.spawn = new int[] {oc[0] * SPACING, BASE_Y + DECKS[0] + 1, oc[1] * SPACING};
        plan.exit = new int[] {ec[0] * SPACING, BASE_Y + DECKS[LEVELS - 1] + 1, ec[1] * SPACING};
        return plan;
    }

    // a filler tree may not reach into a castle (reach 8) or a tree platform (reach 11) next to it
    private static boolean fits(int x, int z, int fr, List<int[]> all, Map<Long, Integer> idx, boolean[] in) {
        int ci = Math.floorDiv(x, SPACING), cj = Math.floorDiv(z, SPACING);
        for (int j = cj - 1; j <= cj + 2; j++) {
            for (int i = ci - 1; i <= ci + 2; i++) {
                Integer a = idx.get(key(i, j));
                if (a == null || !in[a]) {
                    continue;
                }
                int reach = isCastle(i, j) ? 8 : 11;
                double dx = x - i * (double) SPACING, dz = z - j * (double) SPACING;
                if (Math.hypot(dx, dz) < reach + fr + 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int[] sealed(Def d, int rot, int[][] used, int node) {
        List<Integer> out = new ArrayList<>();
        for (int k = 0; k < d.doors.size(); k++) {
            ForestDefs.Door door = d.doors.get(k);
            int wf = (door.face + rot) & 3;
            if ((used[door.level][node] & (1 << wf)) == 0) {
                out.add(k);
            }
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) {
            r[k] = out.get(k);
        }
        return r;
    }

    private void add(Def d, int x, int y, int z, int rot, long seed, int[] sealed) {
        // the part anchor sits at (x, y, z). y of bridges / logs is the deck, the def anchors that already
        this.placements.add(new Placement(d, x, y, z, rot, seed, sealed));
    }
}
