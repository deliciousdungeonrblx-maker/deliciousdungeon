package com.meshi.dungeon;

import com.meshi.Meshi;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModEntities;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// everything that happens in the dungeon dimension: command, spawn rules, 20 mushroom cap, build queue
// the dungeon is ONLY made by /meshi dungeon generate, nothing generates on its own
public class DungeonEvents {
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "dungeon"));

    private static final int UPKEEP_INTERVAL = 100;
    private static final double MIN_PLAYER_DIST = 12.0D;

    private static DungeonBuilder builder = null;

    private static final SuggestionProvider<CommandSourceStack> ROOM_SUGGEST =
            (ctx, sb) -> SharedSuggestionProvider.suggest(RoomStore.KEYS, sb);

    private static final SuggestionProvider<CommandSourceStack> POOL_SUGGEST = (ctx, sb) -> {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        return SharedSuggestionProvider.suggest(level == null ? List.<String>of() : LootStore.get(level).names(), sb);
    };

    private static boolean isDungeon(Level level) {
        return level.dimension().equals(DIMENSION);
    }

    // ---- command

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meshi")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("dungeon")
                        .then(Commands.literal("generate")
                                .executes(ctx -> generate(ctx, System.nanoTime(), false, DungeonLayout.DEFAULT_GRID))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(ctx -> generate(ctx, LongArgumentType.getLong(ctx, "seed"), false, DungeonLayout.DEFAULT_GRID))
                                        .then(Commands.argument("size", sizeArg())
                                                .executes(ctx -> generate(ctx, LongArgumentType.getLong(ctx, "seed"), false,
                                                        IntegerArgumentType.getInteger(ctx, "size"))))))
                        .then(Commands.literal("regenerate")
                                .executes(ctx -> generate(ctx, System.nanoTime(), true, DungeonLayout.DEFAULT_GRID))
                                .then(Commands.argument("size", sizeArg())
                                        .executes(ctx -> generate(ctx, System.nanoTime(), true,
                                                IntegerArgumentType.getInteger(ctx, "size")))))
                        .then(Commands.literal("mobcap")
                                .executes(DungeonEvents::mobCapShow)
                                .then(Commands.argument("floor1", IntegerArgumentType.integer(0, 100))
                                        .then(Commands.argument("floor2", IntegerArgumentType.integer(0, 100))
                                                .then(Commands.argument("floor3", IntegerArgumentType.integer(0, 100))
                                                        .executes(DungeonEvents::mobCapSet)))))
                        .then(Commands.literal("loot")
                                .then(Commands.literal("list").executes(DungeonEvents::lootList))
                                .then(Commands.literal("edit")
                                        .then(Commands.argument("pool", StringArgumentType.word()).suggests(POOL_SUGGEST)
                                                .executes(DungeonEvents::lootEdit)))
                                .then(Commands.literal("rolls")
                                        .then(Commands.argument("pool", StringArgumentType.word()).suggests(POOL_SUGGEST)
                                                .then(Commands.argument("min", IntegerArgumentType.integer(0, 54))
                                                        .then(Commands.argument("max", IntegerArgumentType.integer(0, 54))
                                                                .executes(DungeonEvents::lootRolls)))))
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("pool", StringArgumentType.word()).suggests(POOL_SUGGEST)
                                                .executes(DungeonEvents::lootReset)))
                                .then(Commands.literal("bind")
                                        .then(Commands.argument("pool", StringArgumentType.word()).suggests(POOL_SUGGEST)
                                                .executes(DungeonEvents::lootBind)))
                                .then(Commands.literal("unbind").executes(DungeonEvents::lootUnbind))
                                .then(Commands.literal("refill").executes(DungeonEvents::lootRefill)))
                        .then(Commands.literal("tp").executes(DungeonEvents::teleport))
                        .then(Commands.literal("room")
                                .then(Commands.literal("list").executes(DungeonEvents::roomList))
                                .then(Commands.literal("edit")
                                        .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOM_SUGGEST)
                                                .executes(ctx -> roomEdit(ctx, false))))
                                .then(Commands.literal("reload")
                                        .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOM_SUGGEST)
                                                .executes(ctx -> roomEdit(ctx, true))))
                                .then(Commands.literal("save").executes(DungeonEvents::roomSave))
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("room", StringArgumentType.word()).suggests(ROOM_SUGGEST)
                                                .executes(DungeonEvents::roomReset))))));
    }

    private static com.mojang.brigadier.arguments.ArgumentType<Integer> sizeArg() {
        return IntegerArgumentType.integer(DungeonLayout.MIN_GRID, DungeonLayout.MAX_GRID);
    }

    private static int generate(CommandContext<CommandSourceStack> ctx, long seed, boolean force, int size) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:dungeon dimension not found, is the mod data loaded?"));
            return 0;
        }
        if (builder != null) {
            src.sendFailure(Component.literal("the dungeon is still being built (" + builder.remaining() + " tiles left)"));
            return 0;
        }
        DungeonData data = DungeonData.get(level);
        if (data.generated && !force) {
            src.sendFailure(Component.literal("dungeon already exists. use /meshi dungeon regenerate to rebuild it"));
            return 0;
        }
        if (force) {
            // wipe old mobs (not players) so nothing leftover stands in the new walls
            List<Entity> old = new ArrayList<>();
            for (Entity e : level.getAllEntities()) {
                if (!(e instanceof Player)) {
                    old.add(e);
                }
            }
            old.forEach(Entity::discard);
        }
        data.reset();
        builder = new DungeonBuilder(level, data, seed, size);
        src.sendSuccess(() -> Component.literal("building the dungeon, seed " + seed + ", " + size + "x" + size
                + " tiles per floor. your saved rooms get used. it takes a bit"), true);
        return 1;
    }

    private static int teleport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        DungeonData data = DungeonData.get(level);
        if (!data.generated) {
            src.sendFailure(Component.literal("no dungeon yet, use /meshi dungeon generate"));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("players only"));
            return 0;
        }
        BlockPos s = data.spawn;
        player.teleportTo(level, s.getX() + 0.5D, s.getY(), s.getZ() + 0.5D, 0.0F, 0.0F);
        return 1;
    }

    // ---- room editing: /meshi dungeon room list|edit|reload|save|reset

    private static String roomArg(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "room");
        if (!RoomStore.KEYS.contains(key)) {
            ctx.getSource().sendFailure(Component.literal("no room called " + key + ", use /meshi dungeon room list"));
            return null;
        }
        return key;
    }

    private static int roomList(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        RoomStore store = RoomStore.get(level);
        StringBuilder sb = new StringBuilder("rooms (* = you edited it):");
        for (String key : RoomStore.KEYS) {
            sb.append("\n ").append(store.has(key) ? "* " : "  ").append(key)
                    .append(RoomStore.unused(key) ? " (old, not generated any more)" : "");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // goes to the edit plot of a room (builds it the first time). reload = throw away unsaved changes
    private static int roomEdit(CommandContext<CommandSourceStack> ctx, boolean reload) {
        CommandSourceStack src = ctx.getSource();
        String key = roomArg(ctx);
        if (key == null) {
            return 0;
        }
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        ServerPlayer player = src.getPlayer();
        if (level == null || player == null) {
            src.sendFailure(Component.literal("players only, and the dungeon dimension has to exist"));
            return 0;
        }
        RoomStore store = RoomStore.get(level);
        if (reload || !store.isPlotBuilt(key)) {
            DungeonBuilder.buildPlot(level, key, true);
            store.markPlot(key);
        }
        BlockPos at = DungeonBuilder.plotSpawn(key);
        player.teleportTo(level, at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        String doors = RoomStore.isBig(key)
                ? "this room is 22x22 (2x2 cells) with 2 doorways on every side. keep all 8 open, the generator walls up the 4 it doesnt need."
                : RoomStore.singleEntrance(key)
                ? "this room has ONE entrance, keep it on the north side."
                : "keep all 4 doorways open, the generator walls up the ones it doesnt need.";
        src.sendSuccess(() -> Component.literal("editing " + key + ". " + doors
                + " build what you want (creative, watch the trap plates). chests and barrels you place get loot from the floor pool"
                + " (look at one and /meshi dungeon loot bind <pool> to pick another). then stand inside and use /meshi dungeon room save"), false);
        return 1;
    }

    private static int roomSave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        ServerPlayer player = src.getPlayer();
        if (level == null || player == null || player.level() != level) {
            src.sendFailure(Component.literal("stand inside an edit plot first (/meshi dungeon room edit <room>)"));
            return 0;
        }
        String key = DungeonBuilder.plotKeyAt(player.blockPosition());
        if (key == null) {
            src.sendFailure(Component.literal("you are not inside an edit plot"));
            return 0;
        }
        List<String> blocked = DungeonBuilder.blockedDoorways(level, key);
        RoomStore.get(level).capture(key, level, DungeonBuilder.plotOrigin(key));
        String warn = blocked.isEmpty() ? "" : " (careful, these doorways look blocked: " + String.join(", ", blocked) + ")";
        src.sendSuccess(() -> Component.literal("saved " + key + warn
                + ". new dungeons use it, run /meshi dungeon regenerate to rebuild the current one"), true);
        return 1;
    }

    private static int roomReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String key = roomArg(ctx);
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (key == null || level == null) {
            return 0;
        }
        RoomStore store = RoomStore.get(level);
        if (!store.has(key)) {
            src.sendFailure(Component.literal(key + " has no edits"));
            return 0;
        }
        store.remove(key);
        src.sendSuccess(() -> Component.literal(key + " is back to the generated look. /meshi dungeon room reload " + key
                + " redraws its plot"), true);
        return 1;
    }

    // ---- mob caps

    private static int mobCapShow(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        int[] c = DungeonData.get(level).mobCaps;
        ctx.getSource().sendSuccess(() -> Component.literal("zombie + skeleton cap (loaded at once): floor 1 = " + c[0]
                + ", floor 2 = " + c[1] + ", floor 3 = " + c[2] + ". change: /meshi dungeon mobcap <f1> <f2> <f3>"), false);
        return 1;
    }

    private static int mobCapSet(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        DungeonData data = DungeonData.get(level);
        data.mobCaps = new int[]{IntegerArgumentType.getInteger(ctx, "floor1"), IntegerArgumentType.getInteger(ctx, "floor2"),
                IntegerArgumentType.getInteger(ctx, "floor3")};
        data.setDirty();
        return mobCapShow(ctx);
    }

    // ---- loot: /meshi dungeon loot list|edit|rolls|reset|bind|unbind|refill

    private static String poolArg(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "pool");
        if (!name.matches("[a-z0-9_]{1,24}")) {
            ctx.getSource().sendFailure(Component.literal("pool names are lowercase letters, numbers and _ (max 24)"));
            return null;
        }
        return name;
    }

    private static int lootList(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        LootStore store = LootStore.get(level);
        StringBuilder sb = new StringBuilder("loot pools (floor_1/2/3 are used by that floor, deeper = better):");
        for (String name : store.names()) {
            LootStore.Pool p = store.get(name);
            sb.append("\n ").append(name).append("  (").append(p.minRolls).append("-").append(p.maxRolls).append(" rolls)");
        }
        sb.append("\n edit one: /meshi dungeon loot edit <pool>. a new name makes a new pool");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int lootEdit(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String pool = poolArg(ctx);
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        ServerPlayer player = src.getPlayer();
        if (pool == null || level == null || player == null) {
            return 0;
        }
        LootStore.get(level).openEditor(player, pool);
        src.sendSuccess(() -> Component.literal("row 1 = most common, row 6 = rarest. the stack size is the most you can get "
                + "of that item in one roll. close the window to save. /meshi dungeon loot refill puts it in the dungeon"), false);
        return 1;
    }

    private static int lootRolls(CommandContext<CommandSourceStack> ctx) {
        String pool = poolArg(ctx);
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (pool == null || level == null) {
            return 0;
        }
        int min = IntegerArgumentType.getInteger(ctx, "min");
        int max = IntegerArgumentType.getInteger(ctx, "max");
        LootStore.get(level).setRolls(pool, min, max);
        ctx.getSource().sendSuccess(() -> Component.literal(pool + " gives " + Math.min(min, max) + " to " + Math.max(min, max)
                + " items per chest / barrel"), true);
        return 1;
    }

    private static int lootReset(CommandContext<CommandSourceStack> ctx) {
        String pool = poolArg(ctx);
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (pool == null || level == null) {
            return 0;
        }
        if (!LootStore.get(level).resetPool(pool)) {
            ctx.getSource().sendFailure(Component.literal("no pool called " + pool));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(pool + " is back to the built in loot (or removed, if it was one you made)"), true);
        return 1;
    }

    // look at a chest / barrel in an edit plot, then save the room: that container uses this pool in the dungeon
    private static int lootBind(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String pool = poolArg(ctx);
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        ServerPlayer player = src.getPlayer();
        if (pool == null || level == null || player == null || player.level() != level) {
            src.sendFailure(Component.literal("stand in an edit plot and look at a chest or barrel"));
            return 0;
        }
        BlockPos pos = lookedAtContainer(player, level);
        if (pos == null) {
            src.sendFailure(Component.literal("look at a chest or barrel (within 6 blocks)"));
            return 0;
        }
        if (DungeonBuilder.plotKeyAt(pos) == null) {
            src.sendFailure(Component.literal("that one is not inside an edit plot"));
            return 0;
        }
        LootStore store = LootStore.get(level);
        boolean made = !store.has(pool);
        store.getOrCreate(pool);
        RoomStore.get(level).bindPlot(pos, pool);
        src.sendSuccess(() -> Component.literal("that container now uses loot pool " + pool + (made ? " (new, empty pool, fill it with /meshi dungeon loot edit " + pool + ")" : "")
                + ". save the room to keep it. a double chest needs both halves bound"), true);
        return 1;
    }

    private static int lootUnbind(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        ServerPlayer player = src.getPlayer();
        if (level == null || player == null || player.level() != level) {
            src.sendFailure(Component.literal("stand in an edit plot and look at a chest or barrel"));
            return 0;
        }
        BlockPos pos = lookedAtContainer(player, level);
        if (pos == null || !RoomStore.get(level).unbindPlot(pos)) {
            src.sendFailure(Component.literal("that container is not bound to a pool (it uses the floor pool)"));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("back to the normal floor pool. save the room to keep it"), true);
        return 1;
    }

    private static BlockPos lookedAtContainer(ServerPlayer player, ServerLevel level) {
        HitResult hit = player.pick(6.0D, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult bhr)) {
            return null;
        }
        var state = level.getBlockState(bhr.getBlockPos());
        return state.is(Blocks.CHEST) || state.is(Blocks.BARREL) || state.is(Blocks.TRAPPED_CHEST) ? bhr.getBlockPos() : null;
    }

    // rolls the loot of every container the generator placed again (after you edited a pool)
    private static int lootRefill(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:dungeon dimension not found"));
            return 0;
        }
        DungeonData data = DungeonData.get(level);
        LootStore store = LootStore.get(level);
        java.util.Random rng = new java.util.Random();
        int done = 0;
        java.util.Iterator<java.util.Map.Entry<Long, String>> it = data.lootSpots.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, String> e = it.next();
            if (store.fill(level, BlockPos.of(e.getKey()), e.getValue(), rng)) {
                done++;
            } else {
                it.remove();
            }
        }
        data.setDirty();
        int total = done;
        src.sendSuccess(() -> Component.literal("refilled " + total + " chests / barrels from their pools (everything inside was replaced)"), true);
        return 1;
    }

    // ---- build queue + mushroom upkeep

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        DungeonMobs.tick(server);
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) {
            return;
        }
        if (builder != null) {
            if (builder.tick(2)) {
                builder = null;
                server.getPlayerList().broadcastSystemMessage(Component.literal("the dungeon is ready, /meshi dungeon tp"), false);
            }
            return;
        }
        DungeonData data = DungeonData.get(level);
        if (!data.generated) {
            return;
        }
        int tick = server.getTickCount();
        if (tick % 5 == 0) {
            DungeonMobs.sweep(level, data);
        }
        DungeonAmbience.tick(level, data);
        if (tick % UPKEEP_INTERVAL == 0) {
            topUpMushrooms(level);
        }
    }

    // keeps the number of living walking mushrooms at 20 (unloaded ones count too, thats why we track uuids)
    private static void topUpMushrooms(ServerLevel level) {
        DungeonData data = DungeonData.get(level);
        if (!data.generated || data.mushrooms.size() >= DungeonData.MUSHROOM_CAP || data.mushroomSpots.isEmpty()) {
            return;
        }
        List<ServerPlayer> players = level.players();
        if (players.isEmpty()) {
            return;
        }
        // a few random tries, spot has to be loaded and not right next to a player
        for (int i = 0; i < 40; i++) {
            BlockPos spot = data.mushroomSpots.get(level.random.nextInt(data.mushroomSpots.size()));
            if (!level.isLoaded(spot)) {
                continue;
            }
            boolean farEnough = true;
            for (ServerPlayer p : players) {
                if (p.distanceToSqr(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D) < MIN_PLAYER_DIST * MIN_PLAYER_DIST) {
                    farEnough = false;
                    break;
                }
            }
            if (!farEnough) {
                continue;
            }
            WalkingMushroomEntity m = ModEntities.WALKING_MUSHROOM.get().create(level);
            if (m == null) {
                return;
            }
            m.moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
            if (!level.noCollision(m)) {
                m.discard();
                continue;
            }
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            data.mushrooms.add(m.getUUID());
            data.setDirty();
            return;
        }
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        boolean dungeon = isDungeon(level);
        DungeonMobs.onJoin(level, event.getEntity(), dungeon);
        if (dungeon && event.getEntity() instanceof WalkingMushroomEntity m) {
            DungeonData data = DungeonData.get(level);
            if (data.mushrooms.add(m.getUUID())) {
                data.setDirty();
            }
        }
    }

    // only real removals (dead / discarded) free up a mushroom slot, unloading a chunk dosnt
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        DungeonMobs.onLeave(event.getEntity());
        if (isDungeon(level) && event.getEntity() instanceof WalkingMushroomEntity m
                && m.getRemovalReason() != null && m.getRemovalReason().shouldDestroy()) {
            DungeonData data = DungeonData.get(level);
            if (data.mushrooms.remove(m.getUUID())) {
                data.setDirty();
            }
        }
    }

    // ---- spawn rules: only zombies + skeletons spawn on their own in here
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!isDungeon(event.getLevel().getLevel())) {
            return;
        }
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION
                && type != MobSpawnType.PATROL && type != MobSpawnType.REINFORCEMENT) {
            return;
        }
        if (event.getEntity().getX() >= DungeonBuilder.EDIT_MIN_X) {
            event.setSpawnCancelled(true);
            return;
        }
        EntityType<?> entity = event.getEntity().getType();
        if (entity != EntityType.ZOMBIE && entity != EntityType.SKELETON) {
            event.setSpawnCancelled(true);
            return;
        }
        // cap per floor, less spawning near the top, none in safezones
        ServerLevel level = event.getLevel().getLevel();
        if (!DungeonMobs.allowSpawn(level, event.getEntity(), DungeonData.get(level))) {
            event.setSpawnCancelled(true);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        builder = null;
        DungeonMobs.clear();
        DungeonAmbience.clear();
    }
}
