package com.meshi.dungeon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

// pure, no minecraft stuff. picks which floor blocks of the 33x33 trap room are trap plates.
// a random maze of safe blocks links all 4 doorways (so every side room can be reached, but only by a winding way),
// everything off the path starts as trap. then traps are removed again, but only ones that dont make a shortcut
public final class DungeonTrapPattern {
    public static final int SIZE = 33;
    // how many floor blocks (share of the 31x31 inside) are allowed to stay trap plates
    public static final double TRAP_SHARE = 0.30D;
    // a thinned out route may not get shorter than this part of the maze route
    public static final double KEEP = 0.9D;
    // maze growing: chance to continue from the newest cell (long twisty corridors) instead of a random one (more branches)
    public static final double TWISTY = 0.8D;

    // middle of the 4 lanes in front of the doorways, N E S W
    private static final int[][] LANE = {{16, 1}, {31, 16}, {16, 31}, {1, 16}};

    private DungeonTrapPattern() {
    }

    // [x][z], true = trap plate. the outer ring (walls + doorways) is always false
    public static boolean[][] generate(Random rng) {
        int n = (SIZE - 1) / 2;
        boolean[][] open = new boolean[SIZE][SIZE];
        boolean[][] seen = new boolean[n][n];
        List<int[]> active = new ArrayList<>();
        int sx = rng.nextInt(n);
        int sz = rng.nextInt(n);
        seen[sx][sz] = true;
        open[2 * sx + 1][2 * sz + 1] = true;
        active.add(new int[]{sx, sz});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!active.isEmpty()) {
            int i = rng.nextDouble() < TWISTY ? active.size() - 1 : rng.nextInt(active.size());
            int[] c = active.get(i);
            List<int[]> free = new ArrayList<>();
            for (int[] d : dirs) {
                int nx = c[0] + d[0];
                int nz = c[1] + d[1];
                if (nx >= 0 && nz >= 0 && nx < n && nz < n && !seen[nx][nz]) {
                    free.add(d);
                }
            }
            if (free.isEmpty()) {
                active.remove(i);
                continue;
            }
            int[] d = free.get(rng.nextInt(free.size()));
            int nx = c[0] + d[0];
            int nz = c[1] + d[1];
            seen[nx][nz] = true;
            open[2 * nx + 1][2 * nz + 1] = true;
            open[2 * c[0] + 1 + d[0]][2 * c[1] + 1 + d[1]] = true;
            active.add(new int[]{nx, nz});
        }
        // lanes in front of the 4 doorways (middle of every side) are always safe
        for (int a = 15; a <= 17; a++) {
            for (int k = 1; k <= 2; k++) {
                open[a][k] = true;
                open[a][SIZE - 1 - k] = true;
                open[k][a] = true;
                open[SIZE - 1 - k][a] = true;
            }
        }

        // thin out the traps. a block only turns safe if every route between two doorways stays long
        int[] base = routes(open);
        List<int[]> cand = new ArrayList<>();
        int traps = 0;
        for (int x = 1; x < SIZE - 1; x++) {
            for (int z = 1; z < SIZE - 1; z++) {
                if (!open[x][z]) {
                    cand.add(new int[]{x, z});
                    traps++;
                }
            }
        }
        Collections.shuffle(cand, rng);
        int target = (int) (TRAP_SHARE * (SIZE - 2) * (SIZE - 2));
        for (int[] c : cand) {
            if (traps <= target) {
                break;
            }
            open[c[0]][c[1]] = true;
            int[] now = routes(open);
            boolean ok = true;
            for (int k = 0; k < now.length; k++) {
                if (now[k] < Math.ceil(base[k] * KEEP)) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                traps--;
            } else {
                open[c[0]][c[1]] = false;
            }
        }

        boolean[][] trap = new boolean[SIZE][SIZE];
        for (int x = 1; x < SIZE - 1; x++) {
            for (int z = 1; z < SIZE - 1; z++) {
                trap[x][z] = !open[x][z];
            }
        }
        return trap;
    }

    // walking distance over safe blocks between the 4 lanes: N-E, N-S, N-W, E-S, E-W, S-W
    static int[] routes(boolean[][] open) {
        int[] out = new int[6];
        int k = 0;
        for (int a = 0; a < 3; a++) {
            int[][] d = distances(open, LANE[a][0], LANE[a][1]);
            for (int b = a + 1; b < 4; b++) {
                int v = d[LANE[b][0]][LANE[b][1]];
                out[k++] = v < 0 ? Integer.MAX_VALUE : v;
            }
        }
        return out;
    }

    public static int[][] distances(boolean[][] open, int sx, int sz) {
        int[][] d = new int[SIZE][SIZE];
        for (int[] row : d) {
            java.util.Arrays.fill(row, -1);
        }
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{sx, sz});
        d[sx][sz] = 0;
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] m : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c[0] + m[0];
                int z = c[1] + m[1];
                if (x < 1 || z < 1 || x >= SIZE - 1 || z >= SIZE - 1 || !open[x][z] || d[x][z] >= 0) {
                    continue;
                }
                d[x][z] = d[c[0]][c[1]] + 1;
                q.add(new int[]{x, z});
            }
        }
        return d;
    }
}
