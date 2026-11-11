package com.meshi.forest;

import com.meshi.Meshi;
import com.meshi.forest.ForestDefs.Def;
import com.meshi.forest.ForestPlan.Placement;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayDeque;
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
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// floor 2 (the floating forest) lives in its own dimension meshi:forest.
// /meshi forest generate [radius] [seed]   /meshi forest part ...  (edit every part of the map ingame)
public class ForestEvents {
    public static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Meshi.MODID, "forest"));

    // jobs run one after another (map builds, plot builds, part apply)
    private static final ArrayDeque<ForestBuilder> jobs = new ArrayDeque<>();

    private static final SuggestionProvider<CommandSourceStack> PART_SUGGEST = (ctx, sb) -> {
        List<String> keys = new ArrayList<>();
        for (Def d : ForestDefs.ALL) {
            keys.add(d.key);
        }
        return SharedSuggestionProvider.suggest(keys, sb);
    };

    private static com.mojang.brigadier.arguments.ArgumentType<Integer> radiusArg() {
        return IntegerArgumentType.integer(ForestPlan.MIN_RADIUS, ForestPlan.MAX_RADIUS);
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("meshi")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("forest")
                        .then(Commands.literal("generate")
                                .executes(ctx -> generate(ctx, ForestPlan.DEFAULT_RADIUS, System.nanoTime(), false))
                                .then(Commands.argument("radius", radiusArg())
                                        .executes(ctx -> generate(ctx, IntegerArgumentType.getInteger(ctx, "radius"), System.nanoTime(), false))
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(ctx -> generate(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                                        LongArgumentType.getLong(ctx, "seed"), false)))))
                        .then(Commands.literal("regenerate")
                                .executes(ctx -> generate(ctx, -1, System.nanoTime(), true))
                                .then(Commands.argument("radius", radiusArg())
                                        .executes(ctx -> generate(ctx, IntegerArgumentType.getInteger(ctx, "radius"), System.nanoTime(), true))
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(ctx -> generate(ctx, IntegerArgumentType.getInteger(ctx, "radius"),
                                                        LongArgumentType.getLong(ctx, "seed"), true)))))
                        .then(Commands.literal("tp")
                                .executes(ctx -> teleport(ctx, false))
                                .then(Commands.literal("arrival").executes(ctx -> teleport(ctx, false)))
                                .then(Commands.literal("exit").executes(ctx -> teleport(ctx, true))))
                        .then(Commands.literal("info").executes(ForestEvents::info))
                        .then(Commands.literal("part")
                                .then(Commands.literal("list").executes(ForestEvents::partList))
                                .then(Commands.literal("edit")
                                        .then(Commands.argument("part", StringArgumentType.word()).suggests(PART_SUGGEST)
                                                .executes(ctx -> partEdit(ctx, false))))
                                .then(Commands.literal("reload")
                                        .then(Commands.argument("part", StringArgumentType.word()).suggests(PART_SUGGEST)
                                                .executes(ctx -> partEdit(ctx, true))))
                                .then(Commands.literal("save").executes(ForestEvents::partSave))
                                .then(Commands.literal("reset")
                                        .then(Commands.argument("part", StringArgumentType.word()).suggests(PART_SUGGEST)
                                                .executes(ForestEvents::partReset)))
                                .then(Commands.literal("apply")
                                        .then(Commands.argument("part", StringArgumentType.word()).suggests(PART_SUGGEST)
                                                .executes(ctx -> partApply(ctx, false))
                                                .then(Commands.literal("here").executes(ctx -> partApply(ctx, true))))))));
    }

    private static ServerLevel forest(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = ctx.getSource().getServer().getLevel(DIMENSION);
        if (level == null) {
            ctx.getSource().sendFailure(Component.literal("meshi:forest dimension not found, is the mod data loaded?"));
        }
        return level;
    }

    private static Def partArg(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "part");
        Def d = ForestDefs.byKey(key);
        if (d == null) {
            ctx.getSource().sendFailure(Component.literal("no part called " + key + ", use /meshi forest part list"));
        }
        return d;
    }

    private static boolean busy(CommandSourceStack src) {
        if (!jobs.isEmpty()) {
            ForestBuilder b = jobs.peek();
            src.sendFailure(Component.literal("floor 2 is still building (" + jobs.size() + " job(s) queued, "
                    + b.remainingParts() + " parts + " + b.remainingBoxes() + " areas left in the current one)"));
            return true;
        }
        return false;
    }

    // ---- generate / regenerate
    private static int generate(CommandContext<CommandSourceStack> ctx, int radius, long seed, boolean force) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = forest(ctx);
        if (level == null || busy(src)) {
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (data.generated && !force) {
            src.sendFailure(Component.literal("floor 2 already exists. use /meshi forest regenerate [radius] [seed] to rebuild it"));
            return 0;
        }
        if (radius < 0) {
            radius = data.radius; // regenerate with no radius keeps the old one
        }
        radius = Math.max(ForestPlan.MIN_RADIUS, Math.min(ForestPlan.MAX_RADIUS, radius));
        // clear everything every earlier build may have left (more than one if a build got interrupted)
        List<int[]> clear = new ArrayList<>();
        for (long[] p : data.plans) {
            for (Placement pl : ForestPlan.build((int) p[0], p[1]).placements) {
                clear.add(pl.box());
            }
        }
        ForestPlan plan = ForestPlan.build(radius, seed);
        final int radiusF = radius;
        data.generated = false;
        data.seed = seed;
        data.radius = radiusF;
        data.spawn = new BlockPos(plan.spawn[0], plan.spawn[1], plan.spawn[2]);
        data.exit = new BlockPos(plan.exit[0], plan.exit[1], plan.exit[2]);
        data.plans.add(new long[] {radiusF, seed});
        data.setDirty();
        MinecraftServer server = src.getServer();
        jobs.add(new ForestBuilder(level, clear, plan.placements, true, () -> {
            data.plans.clear();
            data.plans.add(new long[] {radiusF, seed});
            data.generated = true;
            data.setDirty();
            server.getPlayerList().broadcastSystemMessage(Component.literal("floor 2 is ready, /meshi forest tp"), false);
        }));
        String warn = clear.isEmpty() ? "" : " the old map gets cleared first.";
        src.sendSuccess(() -> Component.literal("building floor 2, radius " + radiusF + ", seed " + seed + ": " + plan.castles
                + " castles (" + plan.nests + " with a nest), " + plan.trees + " trees (" + plan.bats + " with a bat hole), "
                + plan.bridges + " bridges, " + plan.logs + " logs, " + plan.fillers + " filler trees." + warn
                + " parts you edited (/meshi forest part list) are used. a big radius takes a while, it opens with /meshi forest tp when done"), true);
        return 1;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        ForestBuilder b = jobs.peek();
        if (b == null) {
            return;
        }
        if (event.getServer().getLevel(DIMENSION) == null) {
            jobs.clear();
            return;
        }
        if (b.tick()) {
            jobs.poll();
            if (b.onDone != null) {
                b.onDone.run();
            }
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        jobs.clear();
    }

    // ---- tp / info
    private static int teleport(CommandContext<CommandSourceStack> ctx, boolean exit) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = forest(ctx);
        if (level == null) {
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
        ServerLevel level = forest(ctx);
        if (level == null) {
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (!data.generated) {
            ForestBuilder b = jobs.peek();
            src.sendFailure(Component.literal(b != null ? "still building, " + b.remainingParts() + " parts left in the current job" : "no floor 2 yet"));
            return 0;
        }
        ForestPlan plan = ForestPlan.build(data.radius, data.seed);
        BlockPos a = data.spawn, e = data.exit;
        src.sendSuccess(() -> Component.literal("floor 2 radius " + data.radius + ", seed " + data.seed + ": " + plan.castles + " castles, "
                + plan.trees + " trees, " + plan.bridges + " bridges, " + plan.logs + " logs, " + plan.fillers + " filler trees"
                + "\narrival (top level, link the portal from floor 1 here): " + a.getX() + " " + a.getY() + " " + a.getZ()
                + "\nexit (bottom level, for the portal to floor 3): " + e.getX() + " " + e.getY() + " " + e.getZ()), false);
        return 1;
    }

    // ---- parts: /meshi forest part list|edit|reload|save|reset|apply
    private static int partList(CommandContext<CommandSourceStack> ctx) {
        ServerLevel level = forest(ctx);
        if (level == null) {
            return 0;
        }
        PartStore store = PartStore.get(level);
        StringBuilder sb = new StringBuilder("floor 2 parts (* = you edited it, the generator uses your version):");
        for (Def d : ForestDefs.ALL) {
            sb.append("\n").append(store.has(d.key) ? "* " : "  ").append(d.key).append("  (").append(d.sx).append("x").append(d.sy)
                    .append("x").append(d.sz).append(")");
        }
        sb.append("\n/meshi forest part edit <part> to build it yourself, then /meshi forest part save");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    // goes to the edit plot of a part (builds it the first time). reload = throw away unsaved changes
    private static int partEdit(CommandContext<CommandSourceStack> ctx, boolean reload) {
        CommandSourceStack src = ctx.getSource();
        Def def = partArg(ctx);
        ServerLevel level = forest(ctx);
        ServerPlayer player = src.getPlayer();
        if (def == null || level == null) {
            return 0;
        }
        if (player == null) {
            src.sendFailure(Component.literal("players only"));
            return 0;
        }
        PartStore store = PartStore.get(level);
        boolean building = false;
        if (reload || !store.isPlotBuilt(def.key)) {
            ForestPlots.decorate(level, def);
            jobs.add(ForestPlots.job(level, def, true));
            store.markPlot(def.key);
            building = true;
        }
        BlockPos at = ForestPlots.spawn(def);
        player.teleportTo(level, at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, -45.0F, 0.0F);
        BlockPos o = ForestPlots.origin(def);
        String saved = store.has(def.key) ? "your saved version" : "the generated look";
        final boolean buildingNow = building;
        src.sendSuccess(() -> Component.literal("editing " + def.key + (jobs.size() > 1 ? " (queued behind other jobs)" : "")
                + ". it shows " + saved + (buildingNow ? ", it is building now (a few seconds for the big ones)" : "") + ".\n"
                + def.help + "\nbox: " + def.sx + "x" + def.sy + "x" + def.sz + " blocks, from " + o.getX() + " " + o.getY() + " " + o.getZ()
                + ", the 8 gold blocks mark its corners. the middle block of the footprint is x " + (o.getX() + def.ax) + " z " + (o.getZ() + def.az)
                + ", base height y " + (o.getY() + def.ay) + ". build inside the box, then stand near it and use /meshi forest part save"), false);
        return 1;
    }

    private static int partSave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = forest(ctx);
        ServerPlayer player = src.getPlayer();
        if (level == null) {
            return 0;
        }
        if (player == null) {
            src.sendFailure(Component.literal("players only"));
            return 0;
        }
        if (!jobs.isEmpty()) {
            src.sendFailure(Component.literal("wait for the plot to finish building first"));
            return 0;
        }
        Def def = ForestPlots.at(player.blockPosition());
        if (def == null) {
            src.sendFailure(Component.literal("stand inside an edit plot first (/meshi forest part edit <part>)"));
            return 0;
        }
        PartStore.get(level).capture(def, level, ForestPlots.origin(def));
        List<String> blocked = ForestPlots.blockedDoors(level, def);
        String warn = blocked.isEmpty() ? "" : "\nwarning: these openings are closed: " + String.join(", ", blocked)
                + ". the generator needs them open (it closes the unused ones itself)";
        src.sendSuccess(() -> Component.literal("saved " + def.key + warn
                + "\nthe next /meshi forest generate / regenerate uses it. to update the map you already have: /meshi forest part apply "
                + def.key + " (or add 'here' to only do the closest one first)"), true);
        return 1;
    }

    private static int partReset(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Def def = partArg(ctx);
        ServerLevel level = forest(ctx);
        if (def == null || level == null) {
            return 0;
        }
        PartStore store = PartStore.get(level);
        if (!store.has(def.key)) {
            src.sendFailure(Component.literal(def.key + " has no edits"));
            return 0;
        }
        store.remove(def.key);
        src.sendSuccess(() -> Component.literal(def.key + " is back to the generated look. /meshi forest part reload " + def.key
                + " redraws its plot, /meshi forest part apply " + def.key + " puts it in the map"), true);
        return 1;
    }

    // puts the current version of a part into the map you already have (all of them, or just the closest one)
    private static int partApply(CommandContext<CommandSourceStack> ctx, boolean here) {
        CommandSourceStack src = ctx.getSource();
        Def def = partArg(ctx);
        ServerLevel level = forest(ctx);
        if (def == null || level == null || busy(src)) {
            return 0;
        }
        ForestData data = ForestData.get(level);
        if (!data.generated) {
            src.sendFailure(Component.literal("no floor 2 yet (or still building)"));
            return 0;
        }
        ForestPlan plan = ForestPlan.build(data.radius, data.seed);
        List<Placement> targets = new ArrayList<>();
        for (Placement p : plan.placements) {
            if (p.part.equals(def.key)) {
                targets.add(p);
            }
        }
        if (targets.isEmpty()) {
            src.sendFailure(Component.literal("this map has no " + def.key + " (the radius is small or none got picked)"));
            return 0;
        }
        if (here) {
            ServerPlayer player = src.getPlayer();
            if (player == null || !player.level().dimension().equals(DIMENSION)) {
                src.sendFailure(Component.literal("stand in floor 2 for 'here'"));
                return 0;
            }
            Placement best = null;
            double bd = Double.MAX_VALUE;
            for (Placement p : targets) {
                double dd = player.distanceToSqr(p.x, p.y, p.z);
                if (dd < bd) {
                    bd = dd;
                    best = p;
                }
            }
            targets = List.of(best);
        }
        // clear the boxes of the targets, then redraw every part that touches those boxes (bridges into a castle etc)
        List<int[]> clear = new ArrayList<>();
        for (Placement p : targets) {
            clear.add(p.box());
        }
        List<Placement> stamps = new ArrayList<>();
        for (Placement p : plan.placements) {
            int[] b = p.box();
            for (int[] c : clear) {
                if (b[0] <= c[3] && b[3] >= c[0] && b[1] <= c[4] && b[4] >= c[1] && b[2] <= c[5] && b[5] >= c[2]) {
                    stamps.add(p);
                    break;
                }
            }
        }
        final int n = targets.size();
        jobs.add(new ForestBuilder(level, clear, stamps, true, () -> src.getServer().getPlayerList()
                .broadcastSystemMessage(Component.literal("floor 2: " + n + " x " + def.key + " redrawn"), false)));
        src.sendSuccess(() -> Component.literal("redrawing " + n + " x " + def.key + " (and the bridges / logs touching them)"), true);
        return 1;
    }
}
