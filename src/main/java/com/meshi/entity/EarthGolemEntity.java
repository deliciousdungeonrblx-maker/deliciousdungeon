package com.meshi.entity;

import com.meshi.crop.GolemCrop;
import com.meshi.registry.ModEntities;
import com.meshi.util.Fx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The earth golem: a slow, heavy, hostile golem with an iron-golem style punch.
 *
 * HasCrops  - which texture to wear (false = plain golem, true = crops on its back).
 * RiseTicks - while above 0 the golem plays its "rise" animation and stays put. The soil mound
 *             sets this when it turns into a golem.
 * Seeds/Crop - what was planted in the mound it came from. Drops 2 crops per seed when it dies,
 *             then leaves a fresh empty mound where it fell.
 *
 * GrowTicks - how long the mound it leaves behind takes to rise again (default 24000 = one day).
 *
 * Test the rise animation with:  /summon meshi:earth_golem ~ ~ ~ {RiseTicks:40}
 * A golem whose mound only takes 5 minutes after it dies:  /summon meshi:earth_golem ~ ~ ~ {GrowTicks:6000}
 */
public class EarthGolemEntity extends Monster implements GeoEntity {
    private static final EntityDataAccessor<Boolean> HAS_CROPS =
            SynchedEntityData.defineId(EarthGolemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> RISE_TICKS =
            SynchedEntityData.defineId(EarthGolemEntity.class, EntityDataSerializers.INT);

    /** Length of the "rise" animation (2 seconds). */
    public static final int RISE_DURATION = 40;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RISE = RawAnimation.begin().thenPlay("rise");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** What was planted in the mound this golem grew from, one entry per seed. Server side only. */
    private final List<GolemCrop> crops = new ArrayList<>();
    /** How long the mound it leaves behind takes to rise again. One day unless set with GrowTicks. */
    private long growTicks = SoilMoundEntity.GROW_TICKS;
    private boolean deathHandled = false;

    public EarthGolemEntity(EntityType<? extends EarthGolemEntity> type, Level level) {
        super(type, level);
        this.xpReward = 20;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)          // same as an iron golem
                .add(Attributes.MOVEMENT_SPEED, 0.18D)       // slow (iron golem is 0.25)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)         // 3.5 hearts, fixed
                .add(Attributes.ATTACK_KNOCKBACK, 1.5D)      // horizontal shove on top of the upward toss
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)  // like an iron golem
                .add(Attributes.FOLLOW_RANGE, 32.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new GolemPunchGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- synced state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(HAS_CROPS, false);
        builder.define(RISE_TICKS, 0);
    }

    public boolean hasCrops() {
        return this.entityData.get(HAS_CROPS);
    }

    public void setHasCrops(boolean value) {
        this.entityData.set(HAS_CROPS, value);
    }

    public int getRiseTicks() {
        return this.entityData.get(RISE_TICKS);
    }

    public void setRiseTicks(int ticks) {
        this.entityData.set(RISE_TICKS, Math.max(0, ticks));
    }

    public boolean isRising() {
        return getRiseTicks() > 0;
    }

    /** Call this right after spawning a golem that should climb out of the ground. */
    public void startRising() {
        setRiseTicks(RISE_DURATION);
    }

    /** What was planted in the mound this golem grew from. Any seeds put the crops on its back. */
    public void setSoil(List<GolemCrop> planted) {
        this.crops.clear();
        this.crops.addAll(planted);
        setHasCrops(!this.crops.isEmpty());
    }

    /** How long the mound this golem leaves behind takes to rise again (20 ticks = 1 second). */
    public void setGrowTicks(long ticks) {
        this.growTicks = ticks > 0 ? ticks : SoilMoundEntity.GROW_TICKS;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("HasCrops", hasCrops());
        tag.putInt("RiseTicks", getRiseTicks());
        tag.putLong("GrowTicks", this.growTicks);
        GolemCrop.writeList(tag, this.crops);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setHasCrops(tag.getBoolean("HasCrops"));
        setRiseTicks(tag.getInt("RiseTicks"));
        if (tag.contains("GrowTicks")) {
            setGrowTicks(tag.getLong("GrowTicks"));
        }
        this.crops.clear();
        this.crops.addAll(GolemCrop.readList(tag, SoilMoundEntity.MAX_SEEDS));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && isRising()) {
            this.getNavigation().stop();
            if (this.level() instanceof ServerLevel server) {
                // Dirt keeps crumbling off it while it climbs out of the ground.
                if (this.tickCount % 2 == 0) {
                    Fx.dirtBurst(server, getX(), getY() + 0.1D, getZ(), 8, 0.7D, 0.04D);
                }
                if (this.tickCount % 8 == 0) {
                    this.playSound(SoundEvents.GRAVEL_STEP, 1.0F, 0.5F);
                }
                // Sparkles shake loose from the crops on its back while it climbs out.
                if (!this.crops.isEmpty() && this.tickCount % 4 == 0) {
                    Fx.sparkle(server, getX(), getY() + 1.8D, getZ(), 2 + this.crops.size(), 0.5D);
                }
                // Standing up: one last thud, a shockwave and a cloud of dust.
                if (getRiseTicks() == 1) {
                    Fx.shockwave(server, Fx.dirt(), getX(), getY() + 0.1D, getZ(), 2.5D);
                    Fx.dust(server, getX(), getY() + 0.3D, getZ(), 16, 1.0D);
                    if (!this.crops.isEmpty()) {
                        Fx.sparkle(server, getX(), getY() + 1.6D, getZ(), 14, 0.8D);
                    }
                    Fx.sound(server, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_BREAK, 1.4F, 0.5F);
                    Fx.sound(server, getX(), getY(), getZ(), SoundEvents.IRON_GOLEM_DAMAGE, 1.0F, 0.5F);
                }
            }
            setRiseTicks(getRiseTicks() - 1);
        }
    }

    /** Dirt kicked up under its feet while it walks (client side, only the people looking see it). */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide && this.onGround() && !isRising()
                && this.getDeltaMovement().horizontalDistanceSqr() > 2.5E-4D && this.tickCount % 4 == 0) {
            for (int i = 0; i < 2; i++) {
                double ox = (this.random.nextDouble() - 0.5D) * 1.0D;
                double oz = (this.random.nextDouble() - 0.5D) * 1.0D;
                this.level().addParticle(Fx.dirt(), getX() + ox, getY() + 0.05D, getZ() + oz, 0.0D, 0.05D, 0.0D);
            }
        }
    }

    // ---------------------------------------------------------------- combat

    /**
     * Fixed 7 damage (from the attribute) plus the knockback attribute, then the iron golem's
     * signature upward toss, reduced by the target's knockback resistance.
     */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            double resistance = living.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
            double lift = 0.4D * Math.max(0.0D, 1.0D - resistance);
            target.setDeltaMovement(target.getDeltaMovement().add(0.0D, lift, 0.0D));
            target.hurtMarked = true;
        }
        return hit;
    }

    /** Called when the punch starts: a heavy whoosh and a little dust at the fists. */
    public void attackStartEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 1.0D, getZ(), SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 0.6F);
        }
    }

    /** Called when the arms meet: dirt flies from the point of impact in front of the golem. */
    public void impactEffects() {
        if (this.level() instanceof ServerLevel server) {
            Vec3 front = this.position()
                    .add(Vec3.directionFromRotation(0.0F, this.yBodyRot).scale(1.5D))
                    .add(0.0D, 0.15D, 0.0D);
            Fx.dirtBurst(server, front.x, front.y, front.z, 28, 0.5D, 0.09D);
            Fx.shockwave(server, Fx.dirt(), front.x, front.y, front.z, 1.6D);
            Fx.dust(server, front.x, front.y + 0.1D, front.z, 6, 0.4D);
            Fx.sound(server, front.x, front.y, front.z, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
            Fx.sound(server, front.x, front.y, front.z, SoundEvents.ROOTED_DIRT_BREAK, 1.0F, 0.8F);
            Fx.sound(server, front.x, front.y, front.z, SoundEvents.DEEPSLATE_BREAK, 0.8F, 0.6F);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && this.level() instanceof ServerLevel server) {
            Fx.dirtBurst(server, getX(), getY() + 1.6D, getZ(), 14, 0.6D, 0.06D);
            if (!this.crops.isEmpty() && this.random.nextInt(3) == 0) {
                // Now and then a little sparkle shakes off its crops.
                Fx.sparkle(server, getX(), getY() + 1.8D, getZ(), 3, 0.4D);
            }
        }
        return hurt;
    }

    // ---------------------------------------------------------------- death

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (this.level() instanceof ServerLevel server) {
            // It crumbles apart as it falls.
            Fx.dirtBurst(server, getX(), getY() + 1.2D, getZ(), 110, 1.0D, 0.12D);
            Fx.dust(server, getX(), getY() + 1.0D, getZ(), 14, 0.8D);
            Fx.shockwave(server, Fx.dirt(), getX(), getY() + 0.1D, getZ(), 2.0D);
            if (!this.crops.isEmpty()) {
                Fx.sparkle(server, getX(), getY() + 1.6D, getZ(), 12, 0.8D);
            }
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_BREAK, 1.5F, 0.5F);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_BREAK, 1.3F, 0.5F);
        }
    }

    /**
     * When the fall-over animation finishes and the golem is removed, drop its crops and leave a
     * fresh mound (no seeds, one-day timer already running) where it fell.
     */
    @Override
    protected void tickDeath() {
        super.tickDeath();
        if (this.isRemoved() && !this.deathHandled && this.level() instanceof ServerLevel server) {
            this.deathHandled = true;
            finishDeath(server);
        }
    }

    /**
     * Every seed that was planted gives back its own crop (2 per seed). Seeds of the same crop are
     * added up into one stack, and each stack pops out of the pile with its own sparkle.
     */
    private void finishDeath(ServerLevel server) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (GolemCrop crop : this.crops) {
            Item item = crop.dropItem();
            if (item != null) {
                totals.merge(item, GolemCrop.CROPS_PER_SEED, Integer::sum);
            }
        }
        for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
            ItemStack stack = new ItemStack(entry.getKey(), entry.getValue());
            ItemEntity drop = new ItemEntity(server, getX(), getY() + 0.6D, getZ(), stack);
            drop.setDeltaMovement(
                    (this.random.nextDouble() - 0.5D) * 0.3D,
                    0.3D + this.random.nextDouble() * 0.15D,
                    (this.random.nextDouble() - 0.5D) * 0.3D);
            server.addFreshEntity(drop);
        }
        if (!totals.isEmpty()) {
            Fx.sparkle(server, getX(), getY() + 0.8D, getZ(), 12 + 4 * totals.size(), 0.7D);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.4F);
            Fx.sound(server, getX(), getY(), getZ(), SoundEvents.COMPOSTER_READY, 1.0F, 1.0F);
        }

        SoilMoundEntity mound = ModEntities.SOIL_MOUND.get().create(server);
        if (mound != null) {
            float yaw = getYRot();
            mound.setPos(getX(), getY(), getZ());
            mound.setYRot(yaw);
            mound.yRotO = yaw;
            mound.setGrowTicks(this.growTicks);
            mound.startTimer(server.getGameTime());
            server.addFreshEntity(mound);
        }

        Fx.dirtBurst(server, getX(), getY() + 0.3D, getZ(), 70, 1.3D, 0.1D);
        Fx.shockwave(server, Fx.dirt(), getX(), getY() + 0.1D, getZ(), 2.5D);
        Fx.sound(server, getX(), getY(), getZ(), SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
        Fx.sound(server, getX(), getY(), getZ(), SoundEvents.ROOTED_DIRT_PLACE, 1.2F, 0.6F);
    }

    // ---------------------------------------------------------------- sounds

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.GRAVEL_HIT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.GRAVEL_BREAK;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.GRAVEL_STEP, 0.8F, 0.6F);
        // Every heavy footstep thuds and kicks up a little dirt.
        if (this.level() instanceof ServerLevel server) {
            Fx.soundVaried(server, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_STEP, 0.7F, 0.5F);
            Fx.dirtBurst(server, getX(), getY() + 0.1D, getZ(), 5, 0.5D, 0.03D);
        }
    }

    // ---------------------------------------------------------------- animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Body: rise / walk / idle.
        controllers.add(new AnimationController<>(this, "main", 5, this::mainPredicate));
        // Punch: runs on its own controller so it plays over walk/idle. Triggered by name from the punch goal.
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", ATTACK));
    }

    private PlayState mainPredicate(AnimationState<EarthGolemEntity> state) {
        if (isRising()) {
            return state.setAndContinue(RISE);
        }
        if (state.isMoving()) {
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
