package com.meshi.entity;

import com.meshi.crop.GolemCrop;
import com.meshi.item.GolemSoilItem;
import com.meshi.registry.ModEntities;
import com.meshi.util.Fx;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

// soil mound, acts like decoration not a mob: nothing can hurt or target it, never moves
// right click w/ plantable item = add seed (max 4, any mix), empty hand = pick up as golem soil
// 2400 ticks (2 min) after placing it rises as a golem. sparkles while growing, ground trembles in the last 30s
// the golems custom name is kept here while its a mound n handed back when it hatches
// test: /summon meshi:soil_mound ~ ~ ~ {Crops:["cabbage","tomato"],GrowTicks:200}
public class SoilMoundEntity extends Entity implements GeoEntity {
    public static final int MAX_SEEDS = 4;
    public static final long GROW_TICKS = 2400L;
    // the old 1 day default, old saves w/ this get the new time
    private static final long OLD_GROW_TICKS = 24000L;

    // seed count (texture depends on it) + seconds left (clock label above the mound), -1 = not known yet
    private static final EntityDataAccessor<Integer> SEEDS =
            SynchedEntityData.defineId(SoilMoundEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> TIMER_SECONDS =
            SynchedEntityData.defineId(SoilMoundEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private final List<GolemCrop> planted = new ArrayList<>();

    // -1 = timer not started yet
    private long readyAt = -1L;
    // grow time, GrowTicks tag changes it (gets passed to the golem n back)
    private long growTicks = GROW_TICKS;
    // name tag on the golem (the mounds own name tag is never shown, the renderer draws it in the label)
    private boolean golemNameVisible = false;

    public SoilMoundEntity(EntityType<? extends SoilMoundEntity> type, Level level) {
        super(type, level);
    }


    public int getSeeds() {
        return this.entityData.get(SEEDS);
    }

    public List<GolemCrop> getCrops() {
        return new ArrayList<>(this.planted);
    }

    public void setCrops(List<GolemCrop> crops) {
        this.planted.clear();
        for (GolemCrop crop : crops) {
            if (crop != null && this.planted.size() < MAX_SEEDS) {
                this.planted.add(crop);
            }
        }
        this.entityData.set(SEEDS, this.planted.size());
    }

    public long getGrowTicks() {
        return this.growTicks;
    }

    public void setGrowTicks(long ticks) {
        this.growTicks = ticks > 0 && ticks != OLD_GROW_TICKS ? ticks : GROW_TICKS;
    }

    public void startTimer(long gameTime) {
        this.readyAt = gameTime + this.growTicks;
    }

    public int getTimerSeconds() {
        return this.entityData.get(TIMER_SECONDS);
    }

    // golem name carry over: golem -> mound
    public void copyNameFrom(Entity golem) {
        this.setCustomName(golem.getCustomName());
        this.golemNameVisible = golem.isCustomNameVisible();
    }

    // mound -> golem
    public void applyNameTo(Entity golem) {
        if (this.hasCustomName()) {
            golem.setCustomName(this.getCustomName());
            golem.setCustomNameVisible(this.golemNameVisible);
        }
    }

    public boolean isGolemNameVisible() {
        return this.golemNameVisible;
    }

    public void setGolemNameVisible(boolean visible) {
        this.golemNameVisible = visible;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SEEDS, 0);
        builder.define(TIMER_SECONDS, -1);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setCrops(GolemCrop.readList(tag, MAX_SEEDS));
        if (tag.contains("ReadyAt")) {
            this.readyAt = tag.getLong("ReadyAt");
        }
        if (tag.contains("GrowTicks")) {
            setGrowTicks(tag.getLong("GrowTicks"));
        }
        this.golemNameVisible = tag.getBoolean("GolemNameVisible");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        GolemCrop.writeList(tag, this.planted);
        tag.putLong("GrowTicks", this.growTicks);
        tag.putBoolean("GolemNameVisible", this.golemNameVisible);
        if (this.readyAt >= 0) {
            tag.putLong("ReadyAt", this.readyAt);
        }
    }


    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }


    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        GolemCrop crop = GolemCrop.fromItem(held);
        if (crop != null) {
            return plant(player, held, crop);
        }
        // both hands empty so a seed in the offhand still plants
        if (held.isEmpty() && hand == InteractionHand.MAIN_HAND && player.getOffhandItem().isEmpty()) {
            return pickUp(player);
        }
        return InteractionResult.PASS;
    }

    private InteractionResult plant(Player player, ItemStack held, GolemCrop crop) {
        if (getSeeds() >= MAX_SEEDS) {
            if (this.level() instanceof ServerLevel server) {
                Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_HIT, 1.0F, 0.6F);
            }
            return InteractionResult.FAIL;
        }
        if (this.level() instanceof ServerLevel server) {
            this.planted.add(crop);
            this.entityData.set(SEEDS, this.planted.size());
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            int n = this.planted.size();
            double x = getX();
            double y = getY();
            double z = getZ();

            server.sendParticles(ParticleTypes.COMPOSTER, x, y + 0.4D, z, 12, 0.6D, 0.2D, 0.6D, 0.0D);
            Fx.dirtBurst(server, x, y + 0.3D, z, 8, 0.5D, 0.03D);
            Fx.sparkle(server, x, y + 0.5D, z, 6 + 3 * n, 0.7D);
            Fx.shockwave(server, ParticleTypes.HAPPY_VILLAGER, x, y + 0.15D, z, 1.0D);
            Fx.sound(server, x, y, z, SoundEvents.CROP_PLANTED, 1.0F, 1.0F);
            Fx.sound(server, x, y, z, SoundEvents.ROOTED_DIRT_PLACE, 1.0F, 0.8F);
            Fx.sound(server, x, y, z, SoundEvents.AMETHYST_BLOCK_CHIME, 0.9F, 0.7F + 0.15F * n);

            if (n == MAX_SEEDS) {
                Fx.sparkle(server, x, y + 0.8D, z, 30, 1.2D);
                Fx.shockwave(server, ParticleTypes.HAPPY_VILLAGER, x, y + 0.15D, z, 2.0D);
                Fx.sound(server, x, y, z, SoundEvents.COMPOSTER_READY, 1.0F, 1.0F);
                Fx.sound(server, x, y, z, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 1.2F);
            }
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    private InteractionResult pickUp(Player player) {
        if (this.level() instanceof ServerLevel server) {
            ItemStack stack = GolemSoilItem.create(this.planted, this.growTicks, getCustomName(), this.golemNameVisible);
            if (!player.addItem(stack)) {
                player.drop(stack, false);
            }
            Fx.dirtBurst(server, getX(), getY() + 0.2D, getZ(), 18, 0.7D, 0.05D);
            Fx.dust(server, getX(), getY() + 0.3D, getZ(), 6, 0.6D);
            if (!this.planted.isEmpty()) {
                Fx.sparkle(server, getX(), getY() + 0.6D, getZ(), 8, 0.7D);
            }
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_BREAK, 1.0F, 1.1F);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.ROOTED_DIRT_BREAK, 1.0F, 1.2F);
            this.discard();
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }


    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        long now = server.getGameTime();
        if (this.readyAt < 0 || this.readyAt - now > this.growTicks) {
            // not started, or an old 1 day mound: cap it to the current time
            this.readyAt = now + this.growTicks;
        }
        this.entityData.set(TIMER_SECONDS, (int) Math.max(0L, (this.readyAt - now + 19L) / 20L));
        // block under it gone = pops off as an item
        if (this.tickCount % 10 == 0 && !hasSupport()) {
            popOff(server);
            return;
        }
        long remaining = this.readyAt - now;
        if (remaining > 0) {
            growthEffects(server, remaining);
        }
        if (now >= this.readyAt) {
            hatch(server);
        }
    }

    // sparkles while growing + a tremor that builds up at the end:
    // last 30s trickle + rumble, last 10s stronger, last 2s constant shaking w/ a shockwave 1s before it rises
    private void growthEffects(ServerLevel server, long remaining) {
        int seeds = getSeeds();
        double x = getX();
        double y = getY();
        double z = getZ();

        if (seeds > 0 && this.tickCount % 20 == 0) {
            Fx.sparkle(server, x, y + 0.5D, z, 1 + seeds, 0.8D);
        }

        if (remaining <= 40L) {
            if (this.tickCount % 2 == 0) {
                Fx.dirtBurst(server, x, y + 0.2D, z, 10, 1.0D, 0.06D);
            }
            if (this.tickCount % 5 == 0) {
                Fx.soundVaried(server, x, y, z, SoundEvents.DEEPSLATE_BREAK, 1.2F, 0.5F);
            }
            if (seeds > 0 && this.tickCount % 4 == 0) {
                Fx.sparkle(server, x, y + 0.6D, z, 4 + seeds * 2, 1.0D);
            }
            if (remaining == 20L) {
                Fx.shockwave(server, Fx.dirt(), x, y + 0.1D, z, 2.0D);
                Fx.sound(server, x, y, z, SoundEvents.GRAVEL_BREAK, 1.3F, 0.4F);
            }
        } else if (remaining <= 200L) {
            if (this.tickCount % 10 == 0) {
                Fx.dirtBurst(server, x, y + 0.2D, z, 6, 0.9D, 0.04D);
                Fx.soundVaried(server, x, y, z, SoundEvents.GRAVEL_STEP, 0.9F, 0.5F);
            }
            if (seeds > 0 && this.tickCount % 10 == 5) {
                Fx.sparkle(server, x, y + 0.5D, z, 2 + seeds, 0.9D);
            }
        } else if (remaining <= 600L) {
            if (this.tickCount % 30 == 0) {
                Fx.dirtBurst(server, x, y + 0.2D, z, 3, 0.8D, 0.02D);
                Fx.soundVaried(server, x, y, z, SoundEvents.GRAVEL_STEP, 0.5F, 0.6F);
            }
        }
    }

    private boolean hasSupport() {
        return !this.level().noCollision(this, this.getBoundingBox().move(0.0D, -0.1D, 0.0D));
    }

    private void popOff(ServerLevel server) {
        ItemStack stack = GolemSoilItem.create(this.planted, this.growTicks, getCustomName(), this.golemNameVisible);
        server.addFreshEntity(new ItemEntity(server, getX(), getY() + 0.3D, getZ(), stack));
        this.discard();
    }

    private void hatch(ServerLevel server) {
        EarthGolemEntity golem = ModEntities.EARTH_GOLEM.get().create(server);
        if (golem == null) {
            this.readyAt = server.getGameTime() + 100L;
            return;
        }
        float yaw = getYRot();
        golem.setPos(getX(), getY(), getZ());
        golem.setYRot(yaw);
        golem.yRotO = yaw;
        golem.yBodyRot = yaw;
        golem.yHeadRot = yaw;
        // no room to stand up, try again in 2s
        if (!server.noCollision(golem)) {
            this.readyAt = server.getGameTime() + 40L;
            return;
        }
        golem.setSoil(this.planted);
        golem.setGrowTicks(this.growTicks);
        applyNameTo(golem);
        golem.startRising();
        golem.setPersistenceRequired();
        server.addFreshEntity(golem);

        double x = getX();
        double y = getY();
        double z = getZ();
        Fx.dirtBurst(server, x, y + 0.2D, z, 120, 1.3D, 0.14D);
        Fx.shockwave(server, Fx.dirt(), x, y + 0.1D, z, 3.0D);
        Fx.dust(server, x, y + 0.4D, z, 24, 1.2D);
        if (!this.planted.isEmpty()) {
            Fx.sparkle(server, x, y + 1.0D, z, 20 + 6 * this.planted.size(), 1.2D);
        }
        Fx.sound(server, x, y, z, SoundEvents.GRAVEL_BREAK, 1.5F, 0.5F);
        Fx.sound(server, x, y, z, SoundEvents.DEEPSLATE_BREAK, 1.3F, 0.5F);
        Fx.sound(server, x, y, z, SoundEvents.ROOTED_DIRT_BREAK, 1.2F, 0.5F);
        this.discard();
    }


    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
