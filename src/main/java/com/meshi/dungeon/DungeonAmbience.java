package com.meshi.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

// fake sounds for paranoia, only that player hears them. vanilla sounds only, quiet in safezones
final class DungeonAmbience {
    private DungeonAmbience() {
    }

    private record Queued(long due, UUID player, Holder<SoundEvent> sound, SoundSource source,
                          double x, double y, double z, float volume, float pitch) {
    }

    private static final List<Queued> queue = new ArrayList<>();
    private static final Map<UUID, Long> next = new HashMap<>();

    static void clear() {
        queue.clear();
        next.clear();
    }

    private static Holder<SoundEvent> snd(String path) {
        return BuiltInRegistries.SOUND_EVENT.getHolder(ResourceLocation.withDefaultNamespace(path))
                .<Holder<SoundEvent>>map(h -> h).orElse(null);
    }

    static void tick(ServerLevel level, DungeonData data) {
        long now = level.getServer().getTickCount();

        Iterator<Queued> it = queue.iterator();
        while (it.hasNext()) {
            Queued q = it.next();
            if (q.due() > now) {
                continue;
            }
            it.remove();
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(q.player());
            if (p != null && p.level() == level) {
                p.connection.send(new ClientboundSoundPacket(q.sound(), q.source(), q.x(), q.y(), q.z(),
                        q.volume(), q.pitch(), level.random.nextLong()));
            }
        }

        if (now % 20 != 0) {
            return;
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.getX() >= DungeonBuilder.EDIT_MIN_X || data.inSafezone(p.getX(), p.getY(), p.getZ(), 0)) {
                continue;
            }
            long due = next.computeIfAbsent(p.getUUID(), k -> now + 600 + level.random.nextInt(1200));
            if (now < due) {
                continue;
            }
            int depth = DungeonBuilder.floorOfY(p.getBlockY());
            double scale = 1.0D - 0.2D * depth;
            next.put(p.getUUID(), now + (long) ((500 + level.random.nextInt(1300)) * scale));
            scare(level, p, now);
        }
    }

    private static void add(long due, ServerPlayer p, String sound, SoundSource source, Vec3 pos, float vol, float pitch) {
        Holder<SoundEvent> h = snd(sound);
        if (h != null) {
            queue.add(new Queued(due, p.getUUID(), h, source, pos.x, pos.y, pos.z, vol, pitch));
        }
    }

    private static void scare(ServerLevel level, ServerPlayer p, long now) {
        var rnd = level.random;
        // mostly behind the player, 9 to 16 blocks away
        Vec3 fwd = Vec3.directionFromRotation(0.0F, p.getYRot());
        double angle = Math.atan2(-fwd.z, -fwd.x) + (rnd.nextDouble() - 0.5D) * Math.toRadians(100.0D);
        double dist = 9.0D + rnd.nextDouble() * 7.0D;
        Vec3 dir = new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
        Vec3 base = p.position().add(dir.scale(dist));

        int roll = rnd.nextInt(10);
        if (roll <= 3) {
            // footsteps coming closer
            String step = rnd.nextBoolean() ? "entity.zombie.step" : "entity.skeleton.step";
            int steps = 4 + rnd.nextInt(4);
            long t = now;
            for (int i = 0; i < steps; i++) {
                Vec3 at = p.position().add(dir.scale(Math.max(4.0D, dist - i * 1.5D)));
                add(t, p, step, SoundSource.HOSTILE, at, 0.8F, 0.9F + rnd.nextFloat() * 0.2F);
                t += 9 + rnd.nextInt(3);
            }
        } else if (roll <= 5) {
            // a door opens, sometimes it shuts again
            add(now, p, "block.wooden_door.open", SoundSource.BLOCKS, base, 0.9F, 0.9F + rnd.nextFloat() * 0.2F);
            if (rnd.nextInt(10) < 6) {
                add(now + 25 + rnd.nextInt(45), p, "block.wooden_door.close", SoundSource.BLOCKS, base, 0.8F, 0.9F + rnd.nextFloat() * 0.2F);
            }
        } else if (roll <= 7) {
            add(now, p, "entity.zombie.ambient", SoundSource.HOSTILE, base, 0.9F, 0.7F + rnd.nextFloat() * 0.3F);
        } else if (roll == 8) {
            // something scratching at a door
            add(now, p, "entity.zombie.attack_wooden_door", SoundSource.HOSTILE, base, 0.8F, 0.8F + rnd.nextFloat() * 0.2F);
            add(now + 12, p, "entity.zombie.attack_wooden_door", SoundSource.HOSTILE, base, 0.8F, 0.8F + rnd.nextFloat() * 0.2F);
        } else {
            add(now, p, rnd.nextBoolean() ? "entity.skeleton.ambient" : "ambient.cave", SoundSource.AMBIENT, base, 0.9F, 0.8F + rnd.nextFloat() * 0.2F);
        }
    }
}
