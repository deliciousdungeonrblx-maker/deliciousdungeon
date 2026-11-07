package com.meshi.dungeon;

import com.meshi.Meshi;
import com.meshi.entity.WalkingMushroomEntity;
import com.meshi.registry.ModEntities;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
                                .executes(ctx -> generate(ctx, System.nanoTime(), false))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(ctx -> generate(ctx, LongArgumentType.getLong(ctx, "seed"), false))))
                        .then(Commands.literal("regenerate")
                                .executes(ctx -> generate(ctx, System.nanoTime(), true)))
                        .then(Commands.literal("tp").executes(DungeonEvents::teleport))));
    }

    private static int generate(CommandContext<CommandSourceStack> ctx, long seed, boolean force) {
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
        builder = new DungeonBuilder(level, data, seed);
        src.sendSuccess(() -> Component.literal("building the dungeon, seed " + seed + ". it takes a bit"), true);
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

    // ---- build queue + mushroom upkeep

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
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
        if (server.getTickCount() % UPKEEP_INTERVAL == 0) {
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
        if (event.getLevel() instanceof ServerLevel level && isDungeon(level)
                && event.getEntity() instanceof WalkingMushroomEntity m) {
            DungeonData data = DungeonData.get(level);
            if (data.mushrooms.add(m.getUUID())) {
                data.setDirty();
            }
        }
    }

    // only real removals (dead / discarded) free up a slot, unloading a chunk dosnt
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && isDungeon(level)
                && event.getEntity() instanceof WalkingMushroomEntity m
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
        EntityType<?> entity = event.getEntity().getType();
        if (entity != EntityType.ZOMBIE && entity != EntityType.SKELETON) {
            event.setSpawnCancelled(true);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        builder = null;
    }
}
