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

// earth golem, slow heavy hostile w/ an iron golem style punch
// HasCrops = texture (true = crops on its back). RiseTicks > 0 = plays rise anim n stays put (the mound sets it)
// Seeds/Crop = what was planted, drops 2 crops per seed on death then leaves a fresh empty mound
// GrowTicks = how long that mound takes to rise again (24000 = 1 day)
// test: /summon meshi:earth_golem ~ ~ ~ {RiseTicks:40}
public class EarthGolemEntity extends Monster implements GeoEntity {
    private static final EntityDataAccessor<Boolean> HAS_CROPS =
            SynchedEntityData.defineId(EarthGolemEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> RISE_TICKS =
            SynchedEntityData.defineId(EarthGolemEntity.class, EntityDataSerializers.INT);

    public static final int RISE_DURATION = 40;

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation RISE = RawAnimation.begin().thenPlay("rise");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private final List<GolemCrop> crops = new ArrayList<>();
    private long growTicks = SoilMoundEntity.GROW_TICKS;
    private boolean deathHandled = false;

    public EarthGolemEntity(EntityType<? extends EarthGolemEntity> type, Level level) {
        super(type, level);
        this.xpReward = 20;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)  // same as iron golem
                .add(Attributes.MOVEMENT_SPEED, 0.18D)  // slow (iron golem is 0.25)
                .add(Attributes.ATTACK_DAMAGE, 7.0D)  // 3.5 hearts
                .add(Attributes.ATTACK_KNOCKBACK, 1.5D)  // sideways shove on top of the toss
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0D)  // like iron golem
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

    public void startRising() {
        setRiseTicks(RISE_DURATION);
    }

    public void setSoil(List<GolemCrop> planted) {
        this.crops.clear();
        this.crops.addAll(planted);
        setHasCrops(!this.crops.isEmpty());
    }

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
                if (this.tickCount % 2 == 0) {
                    Fx.dirtBurst(server, getX(), getY() + 0.1D, getZ(), 8, 0.7D, 0.04D);
                }
                if (this.tickCount % 8 == 0) {
                    this.playSound(SoundEvents.GRAVEL_STEP, 1.0F, 0.5F);
                }
                if (!this.crops.isEmpty() && this.tickCount % 4 == 0) {
                    Fx.sparkle(server, getX(), getY() + 1.8D, getZ(), 2 + this.crops.size(), 0.5D);
                }
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

    // ---- combat

    // fixed 7 dmg + knockback attr, then teh iron golem upward toss (less if target has knockback resist)
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

    public void attackStartEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 1.0D, getZ(), SoundEvents.IRON_GOLEM_ATTACK, 1.0F, 0.6F);
        }
    }

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
                Fx.sparkle(server, getX(), getY() + 1.8D, getZ(), 3, 0.4D);
            }
        }
        return hurt;
    }

    // ---- death

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (this.level() instanceof ServerLevel server) {
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

    // when teh fall over anim ends: drops its crops n leaves a fresh mound (no seeds, timer already running)
    @Override
    protected void tickDeath() {
        super.tickDeath();
        if (this.isRemoved() && !this.deathHandled && this.level() instanceof ServerLevel server) {
            this.deathHandled = true;
            finishDeath(server);
        }
    }

    // every seed gives back its own crop (2 per seed), same crops get stacked, each stack pops out w/ its own sparkle
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
        if (this.level() instanceof ServerLevel server) {
            Fx.soundVaried(server, getX(), getY(), getZ(), SoundEvents.DEEPSLATE_STEP, 0.7F, 0.5F);
            Fx.dirtBurst(server, getX(), getY() + 0.1D, getZ(), 5, 0.5D, 0.03D);
        }
    }


    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::mainPredicate));
        // punch: own controller so it plays over walk/idle, triggered by name from the punch goal
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
