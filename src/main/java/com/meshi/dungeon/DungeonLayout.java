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

    // TRAP_PART = one cell of the 3x3 trap room, SIDE_ROOM = small empty room off the middle of a side of it,
    // SPECIAL = the one empty room on the last floor
    public enum Type { SPAWN, HALL, DEAD_END, GOLEM, STAIRS, ARRIVAL, SAFEZONE, TRAP_PART, SIDE_ROOM, SPECIAL }

    public static final class Tile {
        public final int floor, x, z;
        public int mask;
        public Type type = Type.HALL;
        public int variant;
        public boolean plate;
        // trap room only: which cell of the 3x3 this is (0..2), and the sides that lead OUT of the room
        public int qx, qz, ext;
        // trap room only: which floor blocks of this cell are trap plates [x][z] (cut out of the 33x33 pattern)
        public boolean[][] trap;

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

    // one trap complex: 3x3 trap room, a side room off the middle of 3 sides and the entrance off the 4th.
    // cells are absolute grid coords
    private static final class Complex {
        // {x, z, qx, qz}
        final int[][] parts = new int[9][];
        // {annex x, annex z, parent x, parent z}
        final int[][] annex = new int[3][];
        // {entrance x, entrance z, parent x, parent z}
        int[] ent;
    }

    // tries to find room for one complex. reserved = cells the maze must leave alone
    private Complex place(boolean[][] reserved, boolean[][] entrance, int sx, int sz, Random rng) {
        if (this.grid < 5) {
            return null;
        }
        for (int attempt = 0; attempt < 250; attempt++) {
            // 5x5 box, the room is the middle 3x3 and the 4 arms stick out of the middle of each side
            int ox = rng.nextInt(this.grid - 4);
            int oz = rng.nextInt(this.grid - 4);
            int[][] arms = {{ox + 2, oz, ox + 2, oz + 1}, {ox + 4, oz + 2, ox + 3, oz + 2},
                    {ox + 2, oz + 4, ox + 2, oz + 3}, {ox, oz + 2, ox + 1, oz + 2}};
            int entArm = rng.nextInt(4);
            boolean ok = true;
            for (int i = 0; i < 9 && ok; i++) {
                int x = ox + 1 + i / 3, z = oz + 1 + i % 3;
                ok = !reserved[x][z] && !entrance[x][z] && !(x == sx && z == sz);
            }
            for (int[] a : arms) {
                ok = ok && !reserved[a[0]][a[1]] && !entrance[a[0]][a[1]] && !(a[0] == sx && a[1] == sz);
            }
            if (!ok) {
                continue;
            }
            // mark, then make sure the rest of the grid is still one piece
            Complex cx = new Complex();
            for (int i = 0; i < 9; i++) {
                int x = ox + 1 + i / 3, z = oz + 1 + i % 3;
                reserved[x][z] = true;
                cx.parts[i] = new int[]{x, z, i / 3, i % 3};
            }
            int n = 0;
            for (int k = 0; k < 4; k++) {
                if (k == entArm) {
                    cx.ent = arms[k];
                    entrance[arms[k][0]][arms[k][1]] = true;
                } else {
                    reserved[arms[k][0]][arms[k][1]] = true;
                    cx.annex[n++] = arms[k];
                }
            }
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

    // turns the reserved cells into the finished trap room (cells joined, side rooms, one entrance)
    private void buildComplex(int f, Complex cx, Random rng) {
        // new trap pattern for every trap room
        boolean[][] pattern = DungeonTrapPattern.generate(rng);
        for (int[] p : cx.parts) {
            Tile t = this.tiles[f][p[0]][p[1]];
            t.type = Type.TRAP_PART;
            t.qx = p[2];
            t.qz = p[3];
            t.trap = new boolean[11][11];
            for (int x = 0; x < 11; x++) {
                for (int z = 0; z < 11; z++) {
                    t.trap[x][z] = pattern[p[2] * 11 + x][p[3] * 11 + z];
                }
            }
            if (p[2] < 2) {
                link(f, p[0], p[1], E);
            }
            if (p[3] < 2) {
                link(f, p[0], p[1], S);
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

        // trap rooms, 13 cells each (3x3 room + 3 side rooms + entrance). dont let them eat more than 2/5 of the floor
        int count = this.grid >= 14 ? 3 : 2;
        while (count > 0 && count * 13 > this.grid * this.grid * 2 / 5) {
            count--;
        }
        boolean[][] reserved = new boolean[this.grid][this.grid];
        boolean[][] entrance = new boolean[this.grid][this.grid];
        List<Complex> complexes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Complex cx = place(reserved, entrance, sx, sz, rng);
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
            buildComplex(f, cx, rng);
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
