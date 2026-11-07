package com.meshi.dungeon;

import com.meshi.block.ScorpionBurrowBlock;
import com.meshi.dungeon.DungeonLayout.Tile;
import com.meshi.dungeon.DungeonLayout.Type;
import com.meshi.entity.EarthGolemEntity;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModBlocks;
import com.meshi.registry.ModEntities;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

// builds the dungeon tile by tile (a few per tick so the server dosnt freeze)
// every tile = solid block of wall, then we carve the hall / room out of it
// y layout per floor: rel 0 = floor, 1..5 = air, 6..7 = ceiling
public class DungeonBuilder {
    public static final int TILE = 11;
    public static final int FLOOR_H = 8;
    public static final int TOP_Y = 60;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final ServerLevel level;
    final DungeonData data;
    public final DungeonLayout layout;
    private final long seed;
    private final ArrayDeque<Tile> queue = new ArrayDeque<>();
    private final RoomStore store;
    // plot = one tile built on its own for editing, no mobs, no saved data touched
    private final boolean plotMode;
    private boolean useSaved = true;
    private final List<int[]> golemSpots = new ArrayList<>();

    // current tile
    Tile tile;
    Random rng;
    int bx, bz, yb;
    boolean[][] open;

    // edit plots live far away from the dungeon (x = 3300+, z = 3300)
    public static final int PLOT_TILE_X = 300;
    public static final int PLOT_TILE_Z = 300;
    public static final int EDIT_MIN_X = PLOT_TILE_X * TILE - 60;

    private DungeonBuilder(ServerLevel level, long seed) {
        this.level = level;
        this.data = new DungeonData();
        this.seed = seed;
        this.layout = null;
        this.store = RoomStore.get(level);
        this.plotMode = true;
    }

    public DungeonBuilder(ServerLevel level, DungeonData data, long seed) {
        this.level = level;
        this.data = data;
        this.seed = seed;
        this.store = RoomStore.get(level);
        this.plotMode = false;
        this.layout = new DungeonLayout(seed);
        for (int f = 0; f < DungeonLayout.FLOORS; f++) {
            for (int x = 0; x < DungeonLayout.GRID; x++) {
                for (int z = 0; z < DungeonLayout.GRID; z++) {
                    this.queue.add(this.layout.tiles[f][x][z]);
                }
            }
        }
    }

    public int remaining() {
        return this.queue.size();
    }

    // true when finished
    public boolean tick(int tilesPerTick) {
        for (int i = 0; i < tilesPerTick && !this.queue.isEmpty(); i++) {
            buildTile(this.queue.poll());
        }
        if (this.queue.isEmpty()) {
            finish();
            return true;
        }
        return false;
    }

    // ---- materials (gradient: mud bricks on top -> bricks + granite deeper)

    BlockState wallBlock(int rel) {
        double t = (TOP_Y + 7 - (this.yb + rel)) / (double) (DungeonLayout.FLOORS * FLOOR_H);
        t = Math.max(0.0D, Math.min(1.0D, t));
        double roll = this.rng.nextDouble();
        double granite = 0.32D * t * t;
        double brick = 0.04D + 0.22D * t;
        if (roll < granite) {
            return (this.rng.nextInt(3) == 0 ? Blocks.POLISHED_GRANITE : Blocks.GRANITE).defaultBlockState();
        }
        if (roll < granite + brick) {
            return Blocks.BRICKS.defaultBlockState();
        }
        if (roll < granite + brick + 0.10D) {
            return Blocks.PACKED_MUD.defaultBlockState();
        }
        return Blocks.MUD_BRICKS.defaultBlockState();
    }

    BlockState floorBlock() {
        double roll = this.rng.nextDouble();
        if (roll < 0.25D) {
            return Blocks.PACKED_MUD.defaultBlockState();
        }
        return Blocks.MUD_BRICKS.defaultBlockState();
    }

    // ---- block helpers (local coords in the tile)

    BlockPos world(int lx, int rel, int lz) {
        return new BlockPos(this.bx + lx, this.yb + rel, this.bz + lz);
    }

    void setL(int lx, int rel, int lz, BlockState state) {
        this.level.setBlock(world(lx, rel, lz), state, 18);
    }

    void box(int x0, int x1, int z0, int z1) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                this.open[x][z] = true;
            }
        }
    }

    // only places if the spot is air and the wall behind is solid
    boolean onWall(int lx, int rel, int lz, Direction facing, BlockState state) {
        BlockPos pos = world(lx, rel, lz);
        if (!this.level.getBlockState(pos).isAir()) {
            return false;
        }
        BlockPos support = pos.relative(facing.getOpposite());
        if (!this.level.getBlockState(support).isFaceSturdy(this.level, support, facing)) {
            return false;
        }
        this.level.setBlock(pos, state, 18);
        return true;
    }

    boolean torch(int lx, int rel, int lz, Direction facing) {
        return onWall(lx, rel, lz, facing, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, facing));
    }

    boolean burrow(int lx, int lz, Direction facing) {
        return onWall(lx, 1, lz, facing,
                ModBlocks.SCORPION_BURROW.get().defaultBlockState().setValue(ScorpionBurrowBlock.FACING, facing));
    }

    void chest(int lx, int lz, Direction facing) {
        BlockPos pos = world(lx, 1, lz);
        this.level.setBlock(pos, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 18);
        if (this.level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
            c.setLootTableSeed(this.rng.nextLong());
        }
    }

    void dispenser(int lx, int rel, int lz, Direction facing) {
        BlockPos pos = world(lx, rel, lz);
        this.level.setBlock(pos, Blocks.DISPENSER.defaultBlockState().setValue(DispenserBlock.FACING, facing), 18);
        if (this.level.getBlockEntity(pos) instanceof DispenserBlockEntity d) {
            d.setItem(0, new ItemStack(Items.ARROW, 64));
        }
    }

    // spruce door in the wall. rel 3..5 above it gets walled so its a normal door frame
    void sprucedoor(int lx, int lz, Direction facing) {
        BlockState lower = Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        setL(lx, 1, lz, lower);
        setL(lx, 2, lz, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        for (int rel = 3; rel <= 5; rel++) {
            setL(lx, rel, lz, wallBlock(rel));
        }
    }

    void spot(int lx, int lz) {
        if (this.data.mushroomSpots.size() < 600) {
            this.data.mushroomSpots.add(world(lx, 1, lz));
        }
    }

    static Direction dirOf(int side) {
        return side == DungeonLayout.N ? Direction.NORTH : side == DungeonLayout.E ? Direction.EAST
                : side == DungeonLayout.S ? Direction.SOUTH : Direction.WEST;
    }

    // ---- one tile

    void buildTile(Tile t) {
        this.tile = t;
        this.bx = t.x * TILE;
        this.bz = t.z * TILE;
        this.yb = TOP_Y - t.floor * FLOOR_H;
        this.rng = new Random(this.seed ^ (t.floor * 1000003L + t.x * 1009L + t.z * 31L));
        this.open = new boolean[TILE][TILE];
        this.golemSpots.clear();

        for (int lx = 0; lx < TILE; lx++) {
            for (int lz = 0; lz < TILE; lz++) {
                for (int rel = 0; rel < FLOOR_H; rel++) {
                    setL(lx, rel, lz, wallBlock(rel));
                }
            }
        }

        switch (t.type) {
            case HALL, DEAD_END -> corridorMask(t);
            case ROUND_ROOM -> roundMask(t);
            case GOLEM -> golemMask(t);
            default -> roomMask(t);
        }

        for (int lx = 0; lx < TILE; lx++) {
            for (int lz = 0; lz < TILE; lz++) {
                if (this.open[lx][lz]) {
                    for (int rel = 1; rel <= 5; rel++) {
                        setL(lx, rel, lz, AIR);
                    }
                    setL(lx, 0, lz, floorBlock());
                }
            }
        }

        switch (t.type) {
            case HALL -> DungeonRooms.hall(this);
            case DEAD_END -> DungeonRooms.deadEnd(this);
            case SPAWN -> DungeonRooms.spawn(this);
            case GOLEM -> DungeonRooms.golem(this);
            case STAIRS -> DungeonRooms.stairsTop(this);
            case ARRIVAL -> DungeonRooms.arrival(this);
            case SLIME_ROOM -> DungeonRooms.slimeRoom(this);
            case ARROW_GALLERY -> DungeonRooms.arrowGallery(this);
            case SCORPION_ROOM -> DungeonRooms.scorpionRoom(this);
            case ROUND_ROOM -> DungeonRooms.roundRoom(this);
        }
        // lower floor of a stairs tile has the steps in it
        if (t.type == Type.ARRIVAL && t.floor > 0) {
            DungeonRooms.stairsBottom(this);
        }

        // user edited version of this room? stamp it over the generated one
        String key = RoomStore.keyOf(t);
        if (key != null && this.useSaved && this.store.has(key)) {
            stamp(this.store.get(key), key, t);
        }
        if (!this.plotMode) {
            for (int[] g : this.golemSpots) {
                spawnGolem(g[0], g[1]);
            }
        }
    }

    // ---- edited rooms

    // clockwise quarter turns that bring the north door to the given side
    private static int stepsFor(int side) {
        return side == DungeonLayout.E ? 1 : side == DungeonLayout.S ? 2 : side == DungeonLayout.W ? 3 : 0;
    }

    // turn a local x,z a number of 90 degree steps clockwise around the tile centre
    private static int[] turn(int x, int z, int steps) {
        for (int i = 0; i < steps; i++) {
            int nx = TILE - 1 - z;
            z = x;
            x = nx;
        }
        return new int[]{x, z};
    }

    private static BlockState turnState(BlockState s, Rotation rotation, int steps) {
        if (steps == 0) {
            return s;
        }
        BlockState r = s.rotate(rotation);
        // our own blocks (burrow) dont rotate by themselves
        if (r == s && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            r = s.setValue(BlockStateProperties.HORIZONTAL_FACING,
                    rotation.rotate(s.getValue(BlockStateProperties.HORIZONTAL_FACING)));
        }
        return r;
    }

    // x0, x1, z0, z1 of the doorway cells on a side (the cells that stick out of the room / the hall arm)
    private static int[] doorBox(Type type, int side) {
        if (type == Type.HALL) {
            return side == DungeonLayout.N ? new int[]{4, 6, 0, 3} : side == DungeonLayout.E ? new int[]{7, 10, 4, 6}
                    : side == DungeonLayout.S ? new int[]{4, 6, 7, 10} : new int[]{0, 3, 4, 6};
        }
        return side == DungeonLayout.N ? new int[]{4, 6, 0, 0} : side == DungeonLayout.E ? new int[]{10, 10, 4, 6}
                : side == DungeonLayout.S ? new int[]{4, 6, 10, 10} : new int[]{0, 0, 4, 6};
    }

    private void stamp(RoomStore.Room room, String key, Tile t) {
        boolean single = RoomStore.singleEntrance(key);
        int steps = 0;
        if (single && !this.plotMode) {
            for (int side : new int[]{DungeonLayout.N, DungeonLayout.E, DungeonLayout.S, DungeonLayout.W}) {
                if (t.has(side)) {
                    steps = stepsFor(side);
                    break;
                }
            }
        }
        Rotation rotation = Rotation.values()[steps];
        for (int x = 0; x < TILE; x++) {
            for (int z = 0; z < TILE; z++) {
                int[] p = turn(x, z, steps);
                for (int rel = 0; rel < FLOOR_H; rel++) {
                    BlockState s = turnState(room.get(x, rel, z), rotation, steps);
                    setL(p[0], rel, p[1], s);
                    if (s.is(Blocks.CHEST)) {
                        lootChest(world(p[0], rel, p[1]));
                    } else if (s.is(Blocks.DISPENSER)
                            && this.level.getBlockEntity(world(p[0], rel, p[1])) instanceof DispenserBlockEntity d) {
                        d.setItem(0, new ItemStack(Items.ARROW, 64));
                    }
                }
            }
        }
        if (single) {
            // golem room keeps its 3 golems, turned with the room
            if (key.equals("golem")) {
                this.golemSpots.clear();
                for (int[] c : new int[][]{{3, 4}, {7, 4}, {5, 7}}) {
                    this.golemSpots.add(turn(c[0], c[1], steps));
                }
            }
        } else {
            // saved with every doorway open, wall up the ones this tile doesnt connect to
            for (int side : new int[]{DungeonLayout.N, DungeonLayout.E, DungeonLayout.S, DungeonLayout.W}) {
                if (t.has(side)) {
                    continue;
                }
                int[] b = doorBox(t.type, side);
                for (int x = b[0]; x <= b[1]; x++) {
                    for (int z = b[2]; z <= b[3]; z++) {
                        for (int rel = 1; rel <= 5; rel++) {
                            setL(x, rel, z, wallBlock(rel));
                        }
                    }
                }
            }
            if (t.type == Type.HALL && t.plate) {
                setL(5, 0, 5, ModBlocks.SLIME_TRAP_PLATE.get().defaultBlockState());
            }
        }
    }

    private void lootChest(BlockPos pos) {
        if (this.level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.withDefaultNamespace("chests/simple_dungeon")));
            c.setLootTableSeed(this.rng.nextLong());
        }
    }

    // ---- edit plots (one per room key, spaced 2 tiles apart)

    public static BlockPos plotOrigin(String key) {
        int i = RoomStore.KEYS.indexOf(key);
        return new BlockPos((PLOT_TILE_X + 2 * i) * TILE, TOP_Y, PLOT_TILE_Z * TILE);
    }

    // where the player lands, on a clear spot by the entrance
    public static BlockPos plotSpawn(String key) {
        return plotOrigin(key).offset(5, 1, 1);
    }

    // which plot is this position in (null = none)
    public static String plotKeyAt(BlockPos p) {
        for (String key : RoomStore.KEYS) {
            BlockPos o = plotOrigin(key);
            if (p.getX() >= o.getX() - 4 && p.getX() <= o.getX() + TILE + 3
                    && p.getZ() >= o.getZ() - 4 && p.getZ() <= o.getZ() + TILE + 3
                    && p.getY() >= o.getY() - 3 && p.getY() <= o.getY() + FLOOR_H + 6) {
                return key;
            }
        }
        return null;
    }

    private static Tile plotTile(String key) {
        int i = RoomStore.KEYS.indexOf(key);
        Tile t = new Tile(0, PLOT_TILE_X + 2 * i, PLOT_TILE_Z);
        t.mask = RoomStore.singleEntrance(key) ? DungeonLayout.N
                : DungeonLayout.N | DungeonLayout.E | DungeonLayout.S | DungeonLayout.W;
        if (key.equals("spawn")) t.type = Type.SPAWN;
        else if (key.startsWith("hall_")) t.type = Type.HALL;
        else if (key.startsWith("dead_end_")) t.type = Type.DEAD_END;
        else if (key.equals("golem")) t.type = Type.GOLEM;
        else if (key.equals("slime_room")) t.type = Type.SLIME_ROOM;
        else if (key.equals("arrow_gallery")) t.type = Type.ARROW_GALLERY;
        else if (key.equals("scorpion_room")) t.type = Type.SCORPION_ROOM;
        else t.type = Type.ROUND_ROOM;
        if (key.contains("_") && Character.isDigit(key.charAt(key.length() - 1))) {
            t.variant = key.charAt(key.length() - 1) - '0';
        }
        return t;
    }

    // builds the plot for a room (the saved version if there is one, else the generated look)
    public static void buildPlot(ServerLevel level, String key, boolean useSaved) {
        DungeonBuilder b = new DungeonBuilder(level, 12345L);
        b.useSaved = useSaved;
        b.buildTile(plotTile(key));
    }

    // doorways that are blocked in a plot (names of the sides), used to warn before saving
    public static List<String> blockedDoorways(ServerLevel level, String key) {
        List<String> out = new ArrayList<>();
        BlockPos o = plotOrigin(key);
        int[] sides = RoomStore.singleEntrance(key) ? new int[]{DungeonLayout.N}
                : new int[]{DungeonLayout.N, DungeonLayout.E, DungeonLayout.S, DungeonLayout.W};
        Type type = plotTile(key).type;
        for (int side : sides) {
            int[] b = doorBox(type, side);
            if (key.equals("golem")) {
                continue;
            }
            BlockPos c = o.offset((b[0] + b[1]) / 2, 1, (b[2] + b[3]) / 2);
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) {
                out.add(side == DungeonLayout.N ? "north" : side == DungeonLayout.E ? "east"
                        : side == DungeonLayout.S ? "south" : "west");
            }
        }
        return out;
    }

    private void corridorMask(Tile t) {
        box(4, 6, 4, 6);
        if (t.has(DungeonLayout.N)) box(4, 6, 0, 3);
        if (t.has(DungeonLayout.E)) box(7, 10, 4, 6);
        if (t.has(DungeonLayout.S)) box(4, 6, 7, 10);
        if (t.has(DungeonLayout.W)) box(0, 3, 4, 6);
    }

    private void roomMask(Tile t) {
        box(1, 9, 1, 9);
        if (t.has(DungeonLayout.N)) box(4, 6, 0, 0);
        if (t.has(DungeonLayout.E)) box(10, 10, 4, 6);
        if (t.has(DungeonLayout.S)) box(4, 6, 10, 10);
        if (t.has(DungeonLayout.W)) box(0, 0, 4, 6);
    }

    // golem room: the one entrance is a single wide door
    private void golemMask(Tile t) {
        box(1, 9, 1, 9);
        if (t.has(DungeonLayout.N)) box(5, 5, 0, 0);
        if (t.has(DungeonLayout.E)) box(10, 10, 5, 5);
        if (t.has(DungeonLayout.S)) box(5, 5, 10, 10);
        if (t.has(DungeonLayout.W)) box(0, 0, 5, 5);
    }

    private void roundMask(Tile t) {
        for (int x = 0; x < TILE; x++) {
            for (int z = 0; z < TILE; z++) {
                if ((x - 5) * (x - 5) + (z - 5) * (z - 5) <= 20) {
                    this.open[x][z] = true;
                }
            }
        }
        if (t.has(DungeonLayout.N)) box(4, 6, 0, 1);
        if (t.has(DungeonLayout.E)) box(9, 10, 4, 6);
        if (t.has(DungeonLayout.S)) box(4, 6, 9, 10);
        if (t.has(DungeonLayout.W)) box(0, 1, 4, 6);
    }

    // ---- end: 20 mushrooms to start with
    private void finish() {
        List<BlockPos> spots = new java.util.ArrayList<>(this.data.mushroomSpots);
        Collections.shuffle(spots, new Random(this.seed));
        for (int i = 0; i < DungeonData.MUSHROOM_CAP && i < spots.size(); i++) {
            WalkingMushroomEntity m = ModEntities.WALKING_MUSHROOM.get().create(this.level);
            if (m == null) {
                continue;
            }
            BlockPos p = spots.get(i);
            m.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, this.rng.nextFloat() * 360.0F, 0.0F);
            if (!this.level.noCollision(m)) {
                m.discard();
                continue;
            }
            m.setPersistenceRequired();
            this.level.addFreshEntity(m);
            this.data.mushrooms.add(m.getUUID());
            if (this.rng.nextInt(10) < 4) {
                m.disguise(this.level);
            }
        }
        this.data.generated = true;
        this.data.setDirty();
    }

    // used by DungeonRooms
    void golemAt(int lx, int lz) {
        this.golemSpots.add(new int[]{lx, lz});
    }

    private void spawnGolem(int lx, int lz) {
        EarthGolemEntity golem = ModEntities.EARTH_GOLEM.get().create(this.level);
        if (golem == null) {
            return;
        }
        BlockPos p = world(lx, 1, lz);
        golem.moveTo(p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, this.rng.nextFloat() * 360.0F, 0.0F);
        golem.setPersistenceRequired();
        golem.finalizeSpawn(this.level, this.level.getCurrentDifficultyAt(p), MobSpawnType.STRUCTURE, null);
        this.level.addFreshEntity(golem);
    }

    BlockState stairs(Direction facing, Half half) {
        return Blocks.MUD_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, half);
    }
}
