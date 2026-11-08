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

    public enum Type { SPAWN, HALL, DEAD_END, GOLEM, STAIRS, ARRIVAL, SLIME_ROOM, ARROW_GALLERY, SCORPION_ROOM, ROUND_ROOM, SAFEZONE }

    public static final class Tile {
        public final int floor, x, z;
        public int mask;
        public Type type = Type.HALL;
        public int variant;
        public boolean plate;

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

    // returns coords of the stairs tile (= arrival tile of the next floor)
    private int[] buildFloor(int f, int sx, int sz, Random rng) {
        int[] sides = {N, E, S, W};

        // dfs maze
        boolean[][] seen = new boolean[this.grid][this.grid];
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
            if (nx >= 0 && nz >= 0 && nx < this.grid && nz < this.grid) {
                link(f, x, z, side);
            }
        }

        // distance from start, farthest tile gets the stairs
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
            if (dist[c[0]][c[1]] > dist[far[0]][far[1]]) {
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
                if (t == start || (hasStairs && t == stairs)) {
                    continue;
                }
                (t.degree() == 1 ? leaves : middles).add(t);
            }
        }
        Collections.shuffle(leaves, rng);
        Collections.shuffle(middles, rng);

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

        // trap rooms, first floor is the gentle one. bigger floors get a second set
        List<Type> traps = new ArrayList<>();
        if (f == 0) {
            traps.addAll(List.of(Type.SLIME_ROOM, Type.ARROW_GALLERY));
        } else {
            traps.addAll(List.of(Type.ROUND_ROOM, Type.SCORPION_ROOM, Type.SLIME_ROOM, Type.ARROW_GALLERY));
        }
        if (this.grid >= 9) {
            traps.addAll(new ArrayList<>(traps));
        }
        int ti = 0;
        for (Tile t : middles) {
            if (t.type == Type.SAFEZONE) {
                continue;
            }
            if (ti < traps.size() && t.degree() >= 2) {
                t.type = traps.get(ti++);
            } else {
                t.type = Type.HALL;
                t.variant = rng.nextInt(4);
                t.plate = rng.nextInt(5) == 0;
            }
        }
        for (Tile t : leaves) {
            t.variant = rng.nextInt(4);
        }
        return new int[]{far[0], far[1]};
    }
}
