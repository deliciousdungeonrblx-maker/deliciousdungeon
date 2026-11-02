package com.meshi.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
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
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
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
 *             will set this when it turns into a golem. Test it with:
 *             /summon meshi:earth_golem ~ ~ ~ {RiseTicks:40}
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

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("HasCrops", hasCrops());
        tag.putInt("RiseTicks", getRiseTicks());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setHasCrops(tag.getBoolean("HasCrops"));
        setRiseTicks(tag.getInt("RiseTicks"));
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && isRising()) {
            this.getNavigation().stop();
            setRiseTicks(getRiseTicks() - 1);
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
