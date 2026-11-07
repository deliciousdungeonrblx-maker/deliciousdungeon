package com.meshi.dungeon;

import com.meshi.block.ScorpionBurrowBlock;
import com.meshi.dungeon.DungeonLayout.Tile;
import com.meshi.dungeon.DungeonLayout.Type;
import com.meshi.entity.EarthGolemEntity;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModBlocks;
import com.meshi.registry.ModEntities;
import java.util.ArrayDeque;
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

    // current tile
    Tile tile;
    Random rng;
    int bx, bz, yb;
    boolean[][] open;

    public DungeonBuilder(ServerLevel level, DungeonData data, long seed) {
        this.level = level;
        this.data = data;
        this.seed = seed;
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
