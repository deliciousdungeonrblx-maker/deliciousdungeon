package com.meshi.entity;

import com.meshi.crop.GolemCrop;
import com.meshi.item.GolemSoilItem;
import com.meshi.registry.ModEntities;
import com.meshi.util.Fx;
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

/**
 * The soil mound. It is built like a decoration, not a mob:
 *  - nothing can hurt it or fight it (players cannot even target it), and it never moves;
 *  - right-click with a plantable item to add a seed (up to 4, one crop type per mound);
 *  - right-click with an empty hand to pick it up as a Golem Soil item;
 *  - one Minecraft day (24000 ticks) after it was placed it rises as an earth golem.
 *
 * Handy test command (a tiny timer so you do not wait a day):
 *   /summon meshi:soil_mound ~ ~ ~ {Seeds:2,Crop:"cabbage",GrowTicks:200}
 * Only /kill or falling out of the world removes it.
 */
public class SoilMoundEntity extends Entity implements GeoEntity {
    public static final int MAX_SEEDS = 4;
    /** One full Minecraft day. */
    public static final long GROW_TICKS = 24000L;

    private static final EntityDataAccessor<Integer> SEEDS =
            SynchedEntityData.defineId(SoilMoundEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CROP =
            SynchedEntityData.defineId(SoilMoundEntity.class, EntityDataSerializers.INT);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Game time at which this mound rises. -1 = timer not started yet. */
    private long readyAt = -1L;
    /** Only used by the GrowTicks test tag. */
    private long growTicksOverride = -1L;

    public SoilMoundEntity(EntityType<? extends SoilMoundEntity> type, Level level) {
        super(type, level);
    }

    // ---------------------------------------------------------------- state

    public int getSeeds() {
        return this.entityData.get(SEEDS);
    }

    public GolemCrop getCrop() {
        return GolemCrop.byIndex(this.entityData.get(CROP));
    }

    public void setContents(int seeds, GolemCrop crop) {
        int clamped = Math.max(0, Math.min(MAX_SEEDS, seeds));
        this.entityData.set(SEEDS, clamped);
        this.entityData.set(CROP, clamped > 0 && crop != null ? crop.ordinal() : -1);
    }

    /** Starts (or restarts) the one-day countdown. */
    public void startTimer(long gameTime) {
        this.readyAt = gameTime + GROW_TICKS;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SEEDS, 0);
        builder.define(CROP, -1);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        GolemCrop crop = GolemCrop.byId(tag.getString("Crop"));
        setContents(tag.getInt("Seeds"), crop);
        if (tag.contains("ReadyAt")) {
            this.readyAt = tag.getLong("ReadyAt");
        }
        if (tag.contains("GrowTicks")) {
            this.growTicksOverride = tag.getLong("GrowTicks");
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Seeds", getSeeds());
        GolemCrop crop = getCrop();
        tag.putString("Crop", crop == null ? "" : crop.id());
        if (this.readyAt >= 0) {
            tag.putLong("ReadyAt", this.readyAt);
        }
    }

    // ---------------------------------------------------------------- untouchable and frozen

    /** Players cannot target or punch it at all. */
    @Override
    public boolean isAttackable() {
        return false;
    }

    /** It can still be right-clicked. */
    @Override
    public boolean isPickable() {
        return true;
    }

    /** Explosions, projectiles, lava and everything else that tries to damage it does nothing. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean fireImmune() {
        return true;
    }

    // ---------------------------------------------------------------- right click

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        GolemCrop crop = GolemCrop.fromItem(held);
        if (crop != null) {
            return plant(player, held, crop);
        }
        // Empty hand picks it up. Both hands must be empty so a seed in the off hand still plants.
        if (held.isEmpty() && hand == InteractionHand.MAIN_HAND && player.getOffhandItem().isEmpty()) {
            return pickUp(player);
        }
        return InteractionResult.PASS;
    }

    private InteractionResult plant(Player player, ItemStack held, GolemCrop crop) {
        GolemCrop current = getCrop();
        if (getSeeds() >= MAX_SEEDS || (current != null && current != crop)) {
            return InteractionResult.FAIL;
        }
        if (this.level() instanceof ServerLevel server) {
            setContents(getSeeds() + 1, crop);
            if (!player.getAbilities().instabuild) {
                held.shrink(1);
            }
            server.sendParticles(ParticleTypes.COMPOSTER, getX(), getY() + 0.4D, getZ(), 10, 0.6D, 0.2D, 0.6D, 0.0D);
            Fx.dirtBurst(server, getX(), getY() + 0.3D, getZ(), 6, 0.5D, 0.03D);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.CROP_PLANTED, 1.0F, 1.0F);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    private InteractionResult pickUp(Player player) {
        if (this.level() instanceof ServerLevel server) {
            ItemStack stack = GolemSoilItem.create(getSeeds(), getCrop());
            if (!player.addItem(stack)) {
                player.drop(stack, false);
            }
            Fx.dirtBurst(server, getX(), getY() + 0.2D, getZ(), 14, 0.7D, 0.05D);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_BREAK, 1.0F, 1.1F);
            this.discard();
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    // ---------------------------------------------------------------- timer

    @Override
    public void tick() {
        super.tick();
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        long now = server.getGameTime();
        if (this.readyAt < 0) {
            this.readyAt = now + (this.growTicksOverride >= 0 ? this.growTicksOverride : GROW_TICKS);
        }
        // If the block it was sitting on disappears, it pops off as an item instead of floating.
        if (this.tickCount % 10 == 0 && !hasSupport()) {
            popOff(server);
            return;
        }
        if (now >= this.readyAt) {
            hatch(server);
        }
    }

    private boolean hasSupport() {
        return !this.level().noCollision(this, this.getBoundingBox().move(0.0D, -0.1D, 0.0D));
    }

    private void popOff(ServerLevel server) {
        ItemStack stack = GolemSoilItem.create(getSeeds(), getCrop());
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
        // No room to stand up (low ceiling, blocks on top)? Try again in 2 seconds.
        if (!server.noCollision(golem)) {
            this.readyAt = server.getGameTime() + 40L;
            return;
        }
        golem.setSoil(getSeeds(), getCrop());
        golem.startRising();
        golem.setPersistenceRequired();
        server.addFreshEntity(golem);

        Fx.dirtBurst(server, getX(), getY() + 0.2D, getZ(), 90, 1.1D, 0.12D);
        Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_BREAK, 1.5F, 0.5F);
        Fx.sound(server, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_BREAK, 1.2F, 0.6F);
        this.discard();
    }

    // ---------------------------------------------------------------- GeckoLib (no animations)

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // The mound is a still model.
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
