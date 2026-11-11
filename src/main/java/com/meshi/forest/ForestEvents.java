package com.meshi.forest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshi.Meshi;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// floor 2 (the floating forest): /meshi forest ...   lives in its own dimension meshi:forest
// everything is driven by meshi_forest.json in the world folder (edit it + /meshi forest settings reload)
public class ForestEvents {
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "forest"));

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static ForestBuilder builder = null;
    private static ForestGen building = null;
    private static ForestSettings buildingSettings = null;
    private static long buildingSeed = 0L;

    private static final SuggestionProvider<CommandSourceStack> KEY_SUGGEST =
            (ctx, sb) -> SharedSuggestionProvider.suggest(ForestSettings.DEFAULTS.keySet(), sb);

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meshi")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("forest")
                        .then(Commands.literal("generate")
                                .executes(ctx -> generate(ctx, System.nanoTime(), false))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(ctx -> generate(ctx, LongArgumentType.getLong(ctx, "seed"), false))))
                        .then(Commands.literal("regenerate")
                                .executes(ctx -> generate(ctx, System.nanoTime(), true))
                                .then(Commands.argument("seed", LongArgumentType.longArg())
                                        .executes(ctx -> generate(ctx, LongArgumentType.getLong(ctx, "seed"), true))))
                        .then(Commands.literal("tp")
                                .executes(ctx -> teleport(ctx, false))
                                .then(Commands.literal("arrival").executes(ctx -> teleport(ctx, false)))
                                .then(Commands.literal("exit").executes(ctx -> teleport(ctx, true))))
                        .then(Commands.literal("info").executes(ForestEvents::info))
                        .then(Commands.literal("settings")
                                .executes(ctx -> settingsList(ctx, ""))
                                .then(Commands.literal("list")
                                        .executes(ctx -> settingsList(ctx, ""))
                                        .then(Commands.argument("filter", StringArgumentType.word())
                                                .executes(ctx -> settingsList(ctx, StringArgumentType.getString(ctx, "filter")))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("key", StringArgumentType.word()).suggests(KEY_SUGGEST)
                                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                                        .executes(ForestEvents::settingsSet))))
                                .then(Commands.literal("reset")
                                        .executes(ctx -> settingsReset(ctx, null))
                                        .then(Commands.argument("key", StringArgumentType.word()).suggests(KEY_SUGGEST)
                                                .executes(ctx -> settingsReset(ctx, StringArgumentType.getString(ctx, "key")))))
                                .then(Commands.literal("reload").executes(ForestEvents::settingsReload)))));
    }

    // ---- settings file
    private static Path file(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("meshi_forest.json");
    }

    static ForestSettings loadSettings(MinecraftServer server) {
        Path p = file(server);
        Map<String, String> over = new LinkedHashMap<>();
        if (Files.exists(p)) {
            try {
                JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        over.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            } catch (IOException | RuntimeException ex) {
                LOGGER.warn("could not read meshi_forest.json, using defaults: {}", ex.toString());
            }
        }
        ForestSettings s = new ForestSettings(over);
        // bad values in the file fall back to the default instead of breaking the build
        for (Map.Entry<String, String> e : new LinkedHashMap<>(s.all()).entrySet()) {
            if (ForestSettings.validate(e.getKey(), e.getValue()) != null) {
                s.all().put(e.getKey(), ForestSettings.DEFAULTS.get(e.getKey()));
            }
        }
        if (!Files.exists(p)) {
            saveSettings(server, s);
        }
        return s;
    }

    static void saveSettings(MinecraftServer server, ForestSettings s) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, String> e : s.all().entrySet()) {
            String v = e.getValue();
            if (v.equals("true") || v.equals("false")) {
                o.addProperty(e.getKey(), Boolean.parseBoolean(v));
            } else if (!e.getKey().startsWith("block_") && !e.getKey().equals("filler_variants") && v.matches("-?\\d+")) {
                o.addProperty(e.getKey(), Long.parseLong(v));
            } else if (!e.getKey().startsWith("block_") && !e.getKey().equals("filler_variants") && v.matches("-?\\d*\\.\\d+")) {
                o.addProperty(e.getKey(), Double.parseDouble(v));
            } else {
                o.addProperty(e.getKey(), v);
            }
        }
        try {
            Files.writeString(file(server), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            LOGGER.warn("could not write meshi_forest.json: {}", ex.toString());
        }
    }

    private static int settingsList(CommandContext<CommandSourceStack> ctx, String filter) {
        ForestSettings s = loadSettings(ctx.getSource().getServer());
        StringBuilder sb = new StringBuilder("floor 2 settings (file: meshi_forest.json in the world folder)");
        int n = 0;
        for (String k : ForestSettings.DEFAULTS.keySet()) {
            if (!filter.isEmpty() && !k.contains(filter.toLowerCase())) {
                continue;
            }
            String v = s.get(k);
            sb.append("\n").append(k).append(" = ").append(v);
            if (!v.equals(ForestSettings.DEFAULTS.get(k))) {
                sb.append("  (default ").append(ForestSettings.DEFAULTS.get(k)).append(")");
            }
            sb.append("  - ").append(ForestSettings.HELP.get(k));
            n++;
        }
        if (n == 0) {
            sb.append("\nnothing matches '").append(filter).append("'");
        } else {
            sb.append("\n/meshi forest settings set <key> <value>, then /meshi forest regenerate to rebuild");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int settingsSet(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value").trim();
        String err = ForestSettings.validate(key, value);
        if (err == null && key.startsWith("block_")) {
            ResourceLocation rl = ResourceLocation.tryParse(value);
            if (rl == null || BuiltInRegistries.BLOCK.getOptional(rl).isEmpty()) {
                err = "no block called " + value;
            }
        }
        if (err != null) {
            src.sendFailure(Component.literal(err));
            return 0;
        }
        ForestSettings s = loadSettings(src.getServer());
        s.all().put(key, value);
        saveSettings(src.getServer(), s);
        src.sendSuccess(() -> Component.literal(key + " = " + value + ". takes effect on /meshi forest generate or regenerate"), true);
        return 1;
    }

    private static int settingsReset(CommandContext<CommandSourceStack> ctx, String key) {
        CommandSourceStack src = ctx.getSource();
        ForestSettings s = loadSettings(src.getServer());
        if (key == null) {
            s.all().putAll(ForestSettings.DEFAULTS);
        } else if (ForestSettings.DEFAULTS.containsKey(key)) {
            s.all().put(key, ForestSettings.DEFAULTS.get(key));
        } else {
            src.sendFailure(Component.literal("no setting called " + key));
            return 0;
        }
        saveSettings(src.getServer(), s);
        src.sendSuccess(() -> Component.literal((key == null ? "all settings" : key) + " back to default"), true);
        return 1;
    }

    private static int settingsReload(CommandContext<CommandSourceStack> ctx) {
        ForestSettings s = loadSettings(ctx.getSource().getServer());
        saveSettings(ctx.getSource().getServer(), s);
        ctx.getSource().sendSuccess(() -> Component.literal("floor 2 settings reloaded from meshi_forest.json ("
                + s.all().size() + " values, bad ones fell back to default). /meshi forest regenerate to rebuild"), true);
        return 1;
    }

    // ---- generate
    private static int generate(CommandContext<CommandSourceStack> ctx, long seed, boolean force) {
        CommandSourceStack src = ctx.getSource();
        MinecraftServer server = src.getServer();
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:forest dimension not found, is the mod data loaded?"));
            return 0;
        }
        if (builder != null) {
            src.sendFailure(Component.literal("floor 2 is still being built (" + builder.remaining() + " blocks left)"));
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (data.generated && !force) {
            src.sendFailure(Component.literal("floor 2 already exists. use /meshi forest regenerate to rebuild it"));
            return 0;
        }
        ForestSettings settings = loadSettings(server);
        ForestGen old = null;
        if (data.generated && !data.builtWith.isEmpty()) {
            old = new ForestGen(new ForestSettings(data.builtWith), data.seed);
            old.build();
        }
        ForestGen gen = new ForestGen(settings, seed);
        gen.build();
        data.generated = false;
        data.seed = seed;
        data.builtWith.clear();
        data.builtWith.putAll(settings.all());
        data.spawn = new BlockPos(gen.spawn[0], gen.spawn[1], gen.spawn[2]);
        data.exit = new BlockPos(gen.exit[0], gen.exit[1], gen.exit[2]);
        data.setDirty();
        building = gen;
        buildingSettings = settings;
        buildingSeed = seed;
        builder = new ForestBuilder(level, old, gen, settings);
        src.sendSuccess(() -> Component.literal("building floor 2, seed " + seed + ": " + gen.castles + " castles, "
                + gen.trees + " trees, " + gen.links.size() + " links, " + gen.fillers + " filler trees, "
                + builder.total() + " blocks. it opens with /meshi forest tp when done"), true);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (builder == null) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel level = server.getLevel(DIMENSION);
        if (level == null) {
            builder = null;
            return;
        }
        if (builder.tick()) {
            ForestData data = ForestData.get(level);
            data.generated = true;
            data.setDirty();
            builder = null;
            building = null;
            server.getPlayerList().broadcastSystemMessage(Component.literal("floor 2 is ready, /meshi forest tp"), false);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        builder = null;
        building = null;
    }

    private static int teleport(CommandContext<CommandSourceStack> ctx, boolean exit) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:forest dimension not found"));
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (!data.generated) {
            src.sendFailure(Component.literal("no floor 2 yet (or still building), use /meshi forest generate"));
            return 0;
        }
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("players only"));
            return 0;
        }
        BlockPos p = exit ? data.exit : data.spawn;
        player.teleportTo(level, p.getX() + 0.5D, p.getY(), p.getZ() + 0.5D, 0.0F, 0.0F);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getServer().getLevel(DIMENSION);
        if (level == null) {
            src.sendFailure(Component.literal("meshi:forest dimension not found"));
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (!data.generated) {
            src.sendFailure(Component.literal(builder != null ? "still building, " + builder.remaining() + " blocks left" : "no floor 2 yet"));
            return 0;
        }
        BlockPos a = data.spawn, e = data.exit;
        src.sendSuccess(() -> Component.literal("floor 2 seed " + data.seed + "\narrival (top level, link the portal from floor 1 here): "
                + a.getX() + " " + a.getY() + " " + a.getZ() + "\nexit (bottom level, for the portal to floor 3): "
                + e.getX() + " " + e.getY() + " " + e.getZ()), false);
        return 1;
    }
}
