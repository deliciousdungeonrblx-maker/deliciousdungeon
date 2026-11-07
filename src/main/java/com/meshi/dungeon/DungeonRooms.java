package com.meshi.dungeon;

import com.meshi.dungeon.DungeonLayout.Tile;
import com.meshi.registry.ModBlocks;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

// what goes inside each tile type. all coords are local to the tile (0..10)
final class DungeonRooms {
    private DungeonRooms() {
    }

    private static final Direction[] HORIZ = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    // ---- shared bits

    private static void cornerTorches(DungeonBuilder b) {
        b.torch(1, 3, 3, Direction.EAST);
        b.torch(9, 3, 7, Direction.WEST);
        b.torch(7, 3, 1, Direction.SOUTH);
        b.torch(3, 3, 9, Direction.NORTH);
    }

    private static void pillar(DungeonBuilder b, int lx, int lz) {
        for (int rel = 1; rel <= 4; rel++) {
            b.setL(lx, rel, lz, Blocks.MUD_BRICK_WALL.defaultBlockState());
        }
    }

    private static void roomSpots(DungeonBuilder b) {
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 9; z++) {
                if ((x + z) % 4 == 0 && b.open[x][z]) {
                    b.spot(x, z);
                }
            }
        }
    }

    // plain mud brick floor so the hidden plates cant be told apart
    private static void flatFloor(DungeonBuilder b) {
        for (int x = 0; x < DungeonBuilder.TILE; x++) {
            for (int z = 0; z < DungeonBuilder.TILE; z++) {
                if (b.open[x][z]) {
                    b.setL(x, 0, z, Blocks.MUD_BRICKS.defaultBlockState());
                }
            }
        }
    }

    private static int connectedSide(Tile t) {
        for (int side : new int[]{DungeonLayout.N, DungeonLayout.E, DungeonLayout.S, DungeonLayout.W}) {
            if (t.has(side)) {
                return side;
            }
        }
        return DungeonLayout.N;
    }

    // ---- hallways (4 variants)

    static void hall(DungeonBuilder b) {
        Tile t = b.tile;
        b.spot(5, 5);
        switch (t.variant) {
            case 1 -> { // arched ribs
                ribs(b, 4, 5, Direction.EAST);
                ribs(b, 6, 5, Direction.WEST);
                ribs(b, 5, 4, Direction.SOUTH);
                ribs(b, 5, 6, Direction.NORTH);
            }
            case 2 -> { // little niches in the walls
                for (int[] c : new int[][]{{3, 5}, {7, 5}, {5, 3}, {5, 7}}) {
                    if (!b.open[c[0]][c[1]]) {
                        for (int rel = 1; rel <= 3; rel++) {
                            b.setL(c[0], rel, c[1], Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
            case 3 -> { // rubble, still walkable
                for (int x = 4; x <= 6; x++) {
                    for (int z = 4; z <= 6; z++) {
                        if ((x != 5 || z != 5) && b.open[x][z] && b.rng.nextInt(3) == 0) {
                            BlockState s = b.rng.nextBoolean()
                                    ? Blocks.MUD_BRICK_SLAB.defaultBlockState()
                                    : Blocks.PACKED_MUD.defaultBlockState();
                            b.setL(x, 1, z, s);
                        }
                    }
                }
            }
            default -> {
            }
        }
        if (t.plate) {
            b.setL(5, 0, 5, ModBlocks.SLIME_TRAP_PLATE.get().defaultBlockState());
        }
        // one torch, light only
        if (!(b.torch(4, 3, 5, Direction.EAST) || b.torch(6, 3, 5, Direction.WEST)
                || b.torch(5, 3, 4, Direction.SOUTH) || b.torch(5, 3, 6, Direction.NORTH))) {
            b.torch(5, 3, 5, Direction.NORTH);
        }
        if (b.rng.nextInt(3) == 0) {
            burrowAround(b);
        }
    }

    private static void ribs(DungeonBuilder b, int lx, int lz, Direction facing) {
        if (b.open[lx][lz]) {
            b.setL(lx, 5, lz, b.stairs(facing, Half.TOP));
        }
    }

    // burrow low on a hall wall, tries the 4 sides in random order
    private static void burrowAround(DungeonBuilder b) {
        List<Direction> order = new ArrayList<>(List.of(HORIZ));
        Collections.shuffle(order, b.rng);
        for (Direction d : order) {
            boolean placed = switch (d) {
                case EAST -> b.burrow(4, 5, Direction.EAST);
                case WEST -> b.burrow(6, 5, Direction.WEST);
                case SOUTH -> b.burrow(5, 4, Direction.SOUTH);
                default -> b.burrow(5, 6, Direction.NORTH);
            };
            if (placed) {
                return;
            }
        }
    }

    // ---- dead end

    static void deadEnd(DungeonBuilder b) {
        Tile t = b.tile;
        b.spot(5, 5);
        int side = connectedSide(t);
        if (t.variant != 2) {
            switch (side) {
                case DungeonLayout.N -> b.chest(5, 6, Direction.NORTH);
                case DungeonLayout.S -> b.chest(5, 4, Direction.SOUTH);
                case DungeonLayout.E -> b.chest(4, 5, Direction.EAST);
                default -> b.chest(6, 5, Direction.WEST);
            }
        }
        if (t.variant == 1) {
            burrowAround(b);
        }
        b.torch(4, 3, 5, Direction.EAST);
        b.torch(6, 3, 5, Direction.WEST);
    }

    // ---- spawn + golem

    static void spawn(DungeonBuilder b) {
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                b.setL(x, 0, z, Blocks.BRICKS.defaultBlockState());
            }
        }
        pillar(b, 2, 2);
        pillar(b, 8, 2);
        pillar(b, 2, 8);
        pillar(b, 8, 8);
        cornerTorches(b);
        b.data.spawn = b.world(5, 1, 5);
        b.data.setDirty();
    }

    static void golem(DungeonBuilder b) {
        pillar(b, 1, 1);
        pillar(b, 9, 1);
        pillar(b, 1, 9);
        pillar(b, 9, 9);
        cornerTorches(b);
        int side = connectedSide(b.tile);
        int lx = side == DungeonLayout.E ? 10 : side == DungeonLayout.W ? 0 : 5;
        int lz = side == DungeonLayout.S ? 10 : side == DungeonLayout.N ? 0 : 5;
        b.sprucedoor(lx, lz, DungeonBuilder.dirOf(side));
        b.golemAt(3, 4);
        b.golemAt(7, 4);
        b.golemAt(5, 7);
    }

    // ---- stairs. top part is a hole in the floor, the steps themselves are in the tile below

    static void stairsTop(DungeonBuilder b) {
        cornerTorches(b);
        for (int x = 4; x <= 6; x++) {
            b.setL(x, 0, 1, b.stairs(Direction.NORTH, Half.BOTTOM));
            for (int k = 1; k <= 7; k++) {
                b.setL(x, 0, 1 + k, Blocks.AIR.defaultBlockState());
            }
        }
    }

    static void arrival(DungeonBuilder b) {
        cornerTorches(b);
        roomSpots(b);
    }

    static void stairsBottom(DungeonBuilder b) {
        for (int k = 1; k <= 7; k++) {
            int rel = 8 - k;
            for (int x = 4; x <= 6; x++) {
                b.setL(x, rel, 1 + k, b.stairs(Direction.NORTH, Half.BOTTOM));
                for (int r = rel + 1; r <= Math.min(rel + 4, 7); r++) {
                    b.setL(x, r, 1 + k, Blocks.AIR.defaultBlockState());
                }
                if (k >= 3) {
                    for (int r = 1; r < rel; r++) {
                        b.setL(x, r, 1 + k, b.wallBlock(r));
                    }
                }
            }
        }
    }

    // ---- trap rooms

    static void slimeRoom(DungeonBuilder b) {
        flatFloor(b);
        BlockState plate = ModBlocks.SLIME_TRAP_PLATE.get().defaultBlockState();
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 9; z++) {
                boolean lane = (x >= 4 && x <= 6 && (z <= 2 || z >= 8)) || (z >= 4 && z <= 6 && (x <= 2 || x >= 8));
                if (!lane && b.rng.nextInt(100) < 35) {
                    b.setL(x, 0, z, plate);
                }
            }
        }
        cornerTorches(b);
        roomSpots(b);
    }

    static void arrowGallery(DungeonBuilder b) {
        flatFloor(b);
        BlockState plate = ModBlocks.ARROW_TRAP_PLATE.get().defaultBlockState();
        for (int z : new int[]{3, 5, 7}) {
            for (int x = 1; x <= 9; x++) {
                b.setL(x, 0, z, plate);
                if (x == 1 && !b.open[0][z]) {
                    b.dispenser(0, 2, z, Direction.EAST);
                }
                if (x == 9 && !b.open[10][z]) {
                    b.dispenser(10, 2, z, Direction.WEST);
                }
            }
        }
        b.torch(3, 3, 1, Direction.SOUTH);
        b.torch(7, 3, 9, Direction.NORTH);
        roomSpots(b);
    }

    // looks like a treasure room. the burrows in the walls wake up when you walk in
    static void scorpionRoom(DungeonBuilder b) {
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) {
                b.setL(x, 0, z, Blocks.BRICKS.defaultBlockState());
            }
        }
        b.chest(5, 5, Direction.SOUTH);
        b.burrow(3, 1, Direction.SOUTH);
        b.burrow(7, 1, Direction.SOUTH);
        b.burrow(3, 9, Direction.NORTH);
        b.burrow(7, 9, Direction.NORTH);
        b.burrow(1, 3, Direction.EAST);
        b.burrow(9, 7, Direction.WEST);
        b.torch(1, 3, 7, Direction.EAST);
        b.torch(9, 3, 3, Direction.WEST);
        roomSpots(b);
    }

    // round room, plates everywhere except right at the doorways
    static void roundRoom(DungeonBuilder b) {
        BlockState plate = ModBlocks.RANDOM_TRAP_PLATE.get().defaultBlockState();
        List<int[]> ring = new ArrayList<>();
        for (int x = 0; x < DungeonBuilder.TILE; x++) {
            for (int z = 0; z < DungeonBuilder.TILE; z++) {
                if (!b.open[x][z]) {
                    continue;
                }
                boolean lane = (x >= 4 && x <= 6 && (z <= 1 || z >= 9)) || (z >= 4 && z <= 6 && (x <= 1 || x >= 9));
                if (lane) {
                    b.setL(x, 0, z, Blocks.MUD_BRICKS.defaultBlockState());
                } else {
                    b.setL(x, 0, z, plate);
                }
                int r2 = (x - 5) * (x - 5) + (z - 5) * (z - 5);
                if (r2 >= 14) {
                    ring.add(new int[]{x, z});
                }
            }
        }
        Collections.shuffle(ring, b.rng);
        int torches = 0;
        for (int[] c : ring) {
            for (Direction d : HORIZ) {
                if (torches < 5 && b.torch(c[0], 3, c[1], d)) {
                    torches++;
                    break;
                }
            }
        }
    }
}
