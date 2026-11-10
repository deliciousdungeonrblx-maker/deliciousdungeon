package com.meshi.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// pure layout, no minecraft stuff. a maze per floor, each cell is a tile (TILE blocks wide).
// tile type decides if its a hallway piece or a room. DungeonBuilder turns it into blocks
public final class DungeonLayout {
    public static final int FLOORS = 3;
    public static final int DEFAULT_GRID = 10;
    public static final int MIN_GRID = 6;
    public static final int MAX_GRID = 16;

    // bit per side
    public static final int N = 1, E = 2, S = 4, W = 8;

    public enum Type { SPAWN, HALL, DEAD_END, GOLEM, STAIRS, ARRIVAL, SLIME_ROOM, ARROW_GALLERY, SCORPION_ROOM, ROUND_ROOM, SAFEZONE,
            // TRAP_PART = one cell of a big 2x2 trap room (kind = which trap), SIDE_ROOM = small empty room next to it,
            // SPECIAL = the one empty room on the last floor
            TRAP_PART, SIDE_ROOM, SPECIAL }

    public static final class Tile {
        public final int floor, x, z;
        public int mask;
        public Type type = Type.HALL;
        public int variant;
        public boolean plate;
        // big trap room only: which trap it is, which quarter of the 2x2 (0 = west / north), and the sides that lead OUT of it
        public Type kind;
        public int qx, qz, ext;

        Tile(int floor, int x, int z) {
            this.floor = floor;
            this.x = x;
            this.z = z;
        }

        public int degree() {
            return Integer.bitCount(this.mask);
        }

        public boolean has(int side) {
            return (this.mask & side) != 0;
        }
    }

    public final int grid;
    public final Tile[][][] tiles;
    public final int startX, startZ;

    public DungeonLayout(long seed, int grid) {
        this.grid = Math.max(MIN_GRID, Math.min(MAX_GRID, grid));
        this.tiles = new Tile[FLOORS][this.grid][this.grid];
        Random rng = new Random(seed);
        int sx = 1 + rng.nextInt(this.grid - 2);
        int sz = 1 + rng.nextInt(this.grid - 2);
        this.startX = sx;
        this.startZ = sz;
        for (int f = 0; f < FLOORS; f++) {
            for (int x = 0; x < this.grid; x++) {
                for (int z = 0; z < this.grid; z++) {
                    this.tiles[f][x][z] = new Tile(f, x, z);
                }
            }
            int[] stairs = buildFloor(f, sx, sz, rng);
            sx = stairs[0];
            sz = stairs[1];
        }
    }

    private static int dx(int side) {
        return side == E ? 1 : side == W ? -1 : 0;
    }

    private static int dz(int side) {
        return side == S ? 1 : side == N ? -1 : 0;
    }

    private static int opposite(int side) {
        return side == N ? S : side == S ? N : side == E ? W : E;
    }

    private void link(int f, int x, int z, int side) {
        Tile a = this.tiles[f][x][z];
        Tile b = this.tiles[f][x + dx(side)][z + dz(side)];
        a.mask |= side;
        b.mask |= opposite(side);
    }

    private static int sideTo(int dx, int dz) {
        return dx == 1 ? E : dx == -1 ? W : dz == 1 ? S : N;
    }

    // one trap complex: 2x2 trap room + 3 small side rooms + 1 entrance cell. cells are absolute grid coords
    private static final class Complex {
        final Type kind;
        final int[][] parts = new int[4][];
        // {annex x, annex z, parent x, parent z}
        final int[][] annex = new int[3][];
        // {entrance x, entrance z, parent x, parent z}
        int[] ent;

        Complex(Type kind) {
            this.kind = kind;
        }
    }

    // tries to find room for one complex. reserved = cells the maze must leave alone
    private Complex place(Type kind, boolean[][] reserved, boolean[][] entrance, int sx, int sz, Random rng) {
        for (int attempt = 0; attempt < 250; attempt++) {
            // canonical shape, entrance on the south. cell lists are {x, z, parent x, parent z}
            int a = rng.nextInt(2), b = rng.nextInt(2), c = rng.nextInt(2), e = rng.nextInt(2);
            int[][] raw = {
                    {0, 0, -1, -1}, {1, 0, -1, -1}, {0, 1, -1, -1}, {1, 1, -1, -1},
                    {a, -1, a, 0}, {-1, b, 0, b}, {2, c, 1, c}, {e, 2, e, 1}};
            int turns = rng.nextInt(4);
            for (int ri = 0; ri < raw.length; ri++) {
                int[] r = raw[ri];
                for (int i = 0; i < turns; i++) {
                    int nx = -r[1];
                    r[1] = r[0];
                    r[0] = nx;
                    // parts (first 4) have no parent to turn
                    if (ri >= 4) {
                        int px = -r[3];
                        r[3] = r[2];
                        r[2] = px;
                    }
                }
            }
            int minX = 99, maxX = -99, minZ = 99, maxZ = -99;
            for (int[] r : raw) {
                minX = Math.min(minX, r[0]);
                maxX = Math.max(maxX, r[0]);
                minZ = Math.min(minZ, r[1]);
                maxZ = Math.max(maxZ, r[1]);
            }
            int spanX = this.grid - (maxX - minX);
            int spanZ = this.grid - (maxZ - minZ);
            if (spanX <= 0 || spanZ <= 0) {
                continue;
            }
            int ox = rng.nextInt(spanX) - minX;
            int oz = rng.nextInt(spanZ) - minZ;
            boolean ok = true;
            for (int i = 0; i < raw.length && ok; i++) {
                int[] r = raw[i];
                int x = r[0] + ox, z = r[1] + oz;
                if (x < 0 || z < 0 || x >= this.grid || z >= this.grid || (x == sx && z == sz)) {
                    ok = false;
                } else {
                    ok = !reserved[x][z] && !entrance[x][z];
                }
            }
            if (!ok) {
                continue;
            }
            // mark, then make sure the rest of the grid is still one piece
            Complex cx = new Complex(kind);
            for (int i = 0; i < 7; i++) {
                int x = raw[i][0] + ox, z = raw[i][1] + oz;
                reserved[x][z] = true;
                if (i < 4) {
                    cx.parts[i] = new int[]{x, z};
                } else {
                    cx.annex[i - 4] = new int[]{x, z, raw[i][2] + ox, raw[i][3] + oz};
                }
            }
            cx.ent = new int[]{raw[7][0] + ox, raw[7][1] + oz, raw[7][2] + ox, raw[7][3] + oz};
            entrance[cx.ent[0]][cx.ent[1]] = true;
            if (freeIsConnected(reserved, sx, sz)) {
                return cx;
            }
            for (int[] p : cx.parts) {
                reserved[p[0]][p[1]] = false;
            }
            for (int[] p : cx.annex) {
                reserved[p[0]][p[1]] = false;
            }
            entrance[cx.ent[0]][cx.ent[1]] = false;
        }
        return null;
    }

    // all the cells that are not reserved can still reach each other (flood fill from the start)
    private boolean freeIsConnected(boolean[][] reserved, int sx, int sz) {
        int free = 0;
        for (int x = 0; x < this.grid; x++) {
            for (int z = 0; z < this.grid; z++) {
                if (!reserved[x][z]) {
                    free++;
                }
            }
        }
        boolean[][] seen = new boolean[this.grid][this.grid];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sz});
        seen[sx][sz] = true;
        int count = 0;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            count++;
            for (int side : new int[]{N, E, S, W}) {
                int nx = c[0] + dx(side), nz = c[1] + dz(side);
                if (nx < 0 || nz < 0 || nx >= this.grid || nz >= this.grid || seen[nx][nz] || reserved[nx][nz]) {
                    continue;
                }
                seen[nx][nz] = true;
                q.add(new int[]{nx, nz});
            }
        }
        return count == free;
    }

    // turns the reserved cells into the finished trap room (parts linked to each other, side rooms, one entrance)
    private void buildComplex(int f, Complex cx) {
        int minX = 99, minZ = 99;
        for (int[] p : cx.parts) {
            minX = Math.min(minX, p[0]);
            minZ = Math.min(minZ, p[1]);
        }
        for (int[] p : cx.parts) {
            Tile t = this.tiles[f][p[0]][p[1]];
            t.type = Type.TRAP_PART;
            t.kind = cx.kind;
            t.qx = p[0] - minX;
            t.qz = p[1] - minZ;
        }
        // parts next to each other are joined (the shared edge opens up in the builder)
        for (int[] p : cx.parts) {
            for (int[] q : cx.parts) {
                if (q[0] == p[0] + 1 && q[1] == p[1]) {
                    link(f, p[0], p[1], E);
                } else if (q[0] == p[0] && q[1] == p[1] + 1) {
                    link(f, p[0], p[1], S);
                }
            }
        }
        for (int[] a : cx.annex) {
            int side = sideTo(a[0] - a[2], a[1] - a[3]);
            link(f, a[2], a[3], side);
            this.tiles[f][a[2]][a[3]].ext |= side;
            this.tiles[f][a[0]][a[1]].type = Type.SIDE_ROOM;
        }
        int side = sideTo(cx.ent[0] - cx.ent[2], cx.ent[1] - cx.ent[3]);
        link(f, cx.ent[2], cx.ent[3], side);
        this.tiles[f][cx.ent[2]][cx.ent[3]].ext |= side;
    }

    // returns coords of the stairs tile (= arrival tile of the next floor)
    private int[] buildFloor(int f, int sx, int sz, Random rng) {
        int[] sides = {N, E, S, W};

        // trap rooms: first floor is the gentle one. each one is a dead end 2x2 room with 3 side rooms
        List<Type> traps = new ArrayList<>();
        if (f == 0) {
            traps.addAll(List.of(Type.SLIME_ROOM, Type.ARROW_GALLERY));
        } else {
            traps.addAll(List.of(Type.ROUND_ROOM, Type.SCORPION_ROOM, Type.SLIME_ROOM, Type.ARROW_GALLERY));
        }
        if (this.grid >= 12) {
            traps.addAll(new ArrayList<>(traps));
        }
        // 7 cells each, dont let them eat more than a third of the floor
        while (!traps.isEmpty() && traps.size() * 7 > this.grid * this.grid / 3) {
            traps.remove(traps.size() - 1);
        }
        boolean[][] reserved = new boolean[this.grid][this.grid];
        boolean[][] entrance = new boolean[this.grid][this.grid];
        List<Complex> complexes = new ArrayList<>();
        for (Type kind : traps) {
            Complex cx = place(kind, reserved, entrance, sx, sz, rng);
            if (cx != null) {
                complexes.add(cx);
            }
        }

        // dfs maze (reserved cells count as already seen so the maze skips them)
        boolean[][] seen = new boolean[this.grid][this.grid];
        for (int x = 0; x < this.grid; x++) {
            System.arraycopy(reserved[x], 0, seen[x], 0, this.grid);
        }
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{sx, sz});
        seen[sx][sz] = true;
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            List<Integer> order = new ArrayList<>(List.of(N, E, S, W));
            Collections.shuffle(order, rng);
            boolean moved = false;
            for (int side : order) {
                int nx = c[0] + dx(side);
                int nz = c[1] + dz(side);
                if (nx < 0 || nz < 0 || nx >= this.grid || nz >= this.grid || seen[nx][nz]) {
                    continue;
                }
                link(f, c[0], c[1], side);
                seen[nx][nz] = true;
                stack.push(new int[]{nx, nz});
                moved = true;
                break;
            }
            if (!moved) {
                stack.pop();
            }
        }

        // a few loops so its not only one path
        int loops = Math.max(4, this.grid * this.grid / 9);
        for (int i = 0; i < loops; i++) {
            int x = rng.nextInt(this.grid);
            int z = rng.nextInt(this.grid);
            int side = sides[rng.nextInt(4)];
            int nx = x + dx(side);
            int nz = z + dz(side);
            if (nx >= 0 && nz >= 0 && nx < this.grid && nz < this.grid && !reserved[x][z] && !reserved[nx][nz]) {
                link(f, x, z, side);
            }
        }

        // distance from start, farthest tile gets the stairs (not an entrance of a trap room)
        int[][] dist = new int[this.grid][this.grid];
        for (int[] row : dist) {
            java.util.Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sz});
        dist[sx][sz] = 0;
        int[] far = {sx, sz};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (dist[c[0]][c[1]] > dist[far[0]][far[1]] && !entrance[c[0]][c[1]]) {
                far = c;
            }
            for (int side : sides) {
                if (!this.tiles[f][c[0]][c[1]].has(side)) {
                    continue;
                }
                int nx = c[0] + dx(side);
                int nz = c[1] + dz(side);
                if (dist[nx][nz] < 0) {
                    dist[nx][nz] = dist[c[0]][c[1]] + 1;
                    q.add(new int[]{nx, nz});
                }
            }
        }

        // now hook the trap rooms onto their entrance cells
        for (Complex cx : complexes) {
            buildComplex(f, cx);
        }

        Tile start = this.tiles[f][sx][sz];
        start.type = f == 0 ? Type.SPAWN : Type.ARRIVAL;
        boolean hasStairs = f < FLOORS - 1;
        Tile stairs = this.tiles[f][far[0]][far[1]];
        if (hasStairs) {
            stairs.type = Type.STAIRS;
        }

        List<Tile> leaves = new ArrayList<>();
        List<Tile> middles = new ArrayList<>();
        for (int x = 0; x < this.grid; x++) {
            for (int z = 0; z < this.grid; z++) {
                Tile t = this.tiles[f][x][z];
                if (reserved[x][z] || t == start || (hasStairs && t == stairs)) {
                    continue;
                }
                (t.degree() == 1 ? leaves : middles).add(t);
            }
        }
        Collections.shuffle(leaves, rng);
        Collections.shuffle(middles, rng);

        // the one empty special room, last floor only. takes a dead end so it has a single entrance
        if (f == FLOORS - 1) {
            if (!leaves.isEmpty()) {
                leaves.remove(0).type = Type.SPECIAL;
            } else if (!middles.isEmpty()) {
                middles.remove(0).type = Type.SPECIAL;
            }
        }

        // golem rooms only on leaves so they have exactly one (spruce door) entrance
        int golems = Math.min(Math.max(2, this.grid / 3), leaves.size());
        for (int i = 0; i < leaves.size(); i++) {
            leaves.get(i).type = i < golems ? Type.GOLEM : Type.DEAD_END;
        }

        // safezones (editable rest rooms) go on tiles with 2+ exits
        int safe = this.grid >= 12 ? 2 : 1;
        for (Tile t : middles) {
            if (safe > 0 && t.degree() >= 2) {
                t.type = Type.SAFEZONE;
                safe--;
            }
        }

        for (Tile t : middles) {
            if (t.type == Type.SAFEZONE || t.type == Type.SPECIAL) {
                continue;
            }
            t.type = Type.HALL;
            t.variant = rng.nextInt(4);
            t.plate = rng.nextInt(5) == 0;
        }
        for (Tile t : leaves) {
            t.variant = rng.nextInt(4);
        }
        return new int[]{far[0], far[1]};
    }
}
