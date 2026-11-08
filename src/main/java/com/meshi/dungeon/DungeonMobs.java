package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// mob caps per floor, safezone rules, and removes immersive melodies instruments from hostile mobs
final class DungeonMobs {
    private DungeonMobs() {
    }

    // mod id of immersive melodies, every item of it lives in this namespace
    private static final String INSTRUMENT_NAMESPACE = "immersive_melodies";
    // chance that a natural spawn goes through, per floor (floor 1, 2, 3)
    private static final double[] SPAWN_CHANCE = {0.35D, 0.65D, 1.0D};

    // loaded zombies + skeletons of the dungeon
    private static final Map<UUID, Mob> tracked = new HashMap<>();

    private record Pending(Mob mob, long due) {
    }

    private static final List<Pending> pending = new ArrayList<>();

    static boolean isCapped(Entity e) {
        return e instanceof Zombie || e instanceof AbstractSkeleton;
    }

    // ---- join / leave

    static void onJoin(ServerLevel level, Entity entity, boolean inDungeon) {
        if (entity instanceof Monster mob) {
            // instruments can be given a moment after the spawn, so look twice
            long now = level.getServer().getTickCount();
            pending.add(new Pending(mob, now + 2));
            pending.add(new Pending(mob, now + 40));
        }
        if (inDungeon && isCapped(entity) && entity instanceof Mob mob) {
            tracked.put(entity.getUUID(), mob);
            // nothing in the dungeon picks up dropped instruments (or anything else)
            mob.setCanPickUpLoot(false);
        }
    }

    static void onLeave(Entity entity) {
        tracked.remove(entity.getUUID());
    }

    static void clear() {
        tracked.clear();
        pending.clear();
    }

    // ---- spawn rules (natural spawns only), true = let it spawn

    static boolean allowSpawn(ServerLevel level, Mob mob, DungeonData data) {
        if (data.inSafezone(mob.getX(), mob.getY(), mob.getZ(), 4)) {
            return false;
        }
        int floor = DungeonBuilder.floorOfY(mob.getBlockY());
        if (level.random.nextDouble() > SPAWN_CHANCE[floor]) {
            return false;
        }
        int count = 0;
        for (Mob m : tracked.values()) {
            if (m.isAlive() && DungeonBuilder.floorOfY(m.getBlockY()) == floor) {
                count++;
            }
        }
        return count < data.mobCaps[floor];
    }

    // ---- ticking

    static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (!pending.isEmpty()) {
            Iterator<Pending> it = pending.iterator();
            while (it.hasNext()) {
                Pending p = it.next();
                if (p.due() <= now) {
                    if (!p.mob().isRemoved()) {
                        stripInstruments(p.mob());
                    }
                    it.remove();
                }
            }
        }
    }

    // every 5 ticks, only the dungeon ones
    static void sweep(ServerLevel level, DungeonData data) {
        Iterator<Mob> it = tracked.values().iterator();
        while (it.hasNext()) {
            Mob mob = it.next();
            if (mob.isRemoved()) {
                it.remove();
                continue;
            }
            stripInstruments(mob);
            LivingEntity target = mob.getTarget();
            if (target instanceof Player p && data.inSafezone(p.getX(), p.getY(), p.getZ(), 1)) {
                mob.setTarget(null);
                mob.getNavigation().stop();
            }
        }
    }

    // takes immersive melodies instruments out of the hands (no drop)
    static void stripInstruments(Mob mob) {
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack s = mob.getItemBySlot(slot);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals(INSTRUMENT_NAMESPACE)) {
                mob.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }
}
