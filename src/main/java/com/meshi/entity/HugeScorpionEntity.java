package com.meshi.entity;

import com.meshi.registry.ModEffects;
import com.meshi.util.Fx;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
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
 * The huge scorpion: a fast, hostile ambusher. Its sting hurts a little, but the venom is the real
 * danger: a stung creature is drained down to 1 HP (Scorpion Venom never kills by itself) and is
 * left Weak and Slow while it lasts. Milk cures all three.
 *
 * It usually comes out of a Scorpion Burrow (a hole on the side of a wall, see ScorpionBurrowBlock).
 * While it climbs out ("emerge", 0.5 s) it is invulnerable, has no collision and walks out of the wall
 * on its own, so the model and the hitbox stay together. The emerge animation's own forward slide is
 * ignored on purpose (see HugeScorpionModel), the entity does the moving.
 *
 * It drops Raw Scorpion Meat (see data/meshi/loot_table/entities/huge_scorpion.json).
 *
 * Test it with:  /summon meshi:huge_scorpion ~ ~ ~
 * Or in the wall (the open side is the way it faces):  /give @s meshi:scorpion_burrow
 */
public class HugeScorpionEntity extends Monster implements GeoEntity {
    // ---- Venom: tweak these --------------------------------------------------------------------
    /** How long the venom lasts, in ticks (20 ticks = 1 second). */
    public static final int VENOM_TICKS = 20 * 20;
    /** Weakness level minus one: 0 = Weakness I, 1 = Weakness II. */
    private static final int WEAKNESS_AMPLIFIER = 0;
    /** Slowness level minus one: 1 = Slowness II. */
    private static final int SLOWNESS_AMPLIFIER = 1;

    // ---- Size and emerging -----------------------------------------------------------------------
    /** The Blockbench model is drawn this many times bigger. Used by HugeScorpionRenderer. */
    public static final float MODEL_SCALE = 2.0F;
    /** Length of the "emerge" animation (0.5 s). */
    public static final int EMERGE_DURATION = 10;
    /** Blocks behind the wall face where the scorpion's origin starts (hides its claws inside the wall). */
    public static final double EMERGE_START_INSIDE = 1.7D;
    /** Blocks in front of the wall face where its origin ends up (so its hitbox is clear of the wall). */
    public static final double EMERGE_END_OUTSIDE = 1.25D;

    private static final EntityDataAccessor<Integer> EMERGE_TICKS =
            SynchedEntityData.defineId(HugeScorpionEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation EMERGE = RawAnimation.begin().thenPlay("emerge");
    private static final RawAnimation STING = RawAnimation.begin().thenPlay("sting");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** Where the wall face is (the middle of the hole). Server side only, used for the dust. */
    private Vec3 emergePlane = null;
    /** Chips of the wall block it is coming out of. Server side only. */
    private ParticleOptions emergeChips = null;

    public HugeScorpionEntity(EntityType<? extends HugeScorpionEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 40.0D)           // 20 hearts
                .add(Attributes.MOVEMENT_SPEED, 0.3D)        // quick (a zombie is 0.23)
                .add(Attributes.ATTACK_DAMAGE, 4.0D)         // 2 hearts, the venom is the real threat
                .add(Attributes.ARMOR, 4.0D)                 // hard shell
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        // While it climbs out of the wall it does nothing else: this goal holds every movement flag.
        this.goalSelector.addGoal(0, new Goal() {
            {
                this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
            }

            @Override
            public boolean canUse() {
                return HugeScorpionEntity.this.isEmerging();
            }

            @Override
            public boolean canContinueToUse() {
                return HugeScorpionEntity.this.isEmerging();
            }
        });
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new ScorpionStingGoal(this, 1.1D));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- emerging from a wall

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(EMERGE_TICKS, 0);
    }

    public int getEmergeTicks() {
        return this.entityData.get(EMERGE_TICKS);
    }

    public void setEmergeTicks(int ticks) {
        this.entityData.set(EMERGE_TICKS, Math.max(0, ticks));
    }

    public boolean isEmerging() {
        return getEmergeTicks() > 0;
    }

    /**
     * Call this right after placing the scorpion inside the wall, facing out of it (the burrow block
     * does). "plane" is the middle of the hole on the wall face, "chips" the particle for the wall block.
     */
    public void startEmerging(Vec3 plane, ParticleOptions chips) {
        this.emergePlane = plane;
        this.emergeChips = chips;
        setEmergeTicks(EMERGE_DURATION);
        beginEmergeState();
        if (this.level() instanceof ServerLevel server) {
            // A low rumble and a crack as the stone gives way.
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.DEEPSLATE_BREAK, 1.4F, 0.5F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.SPIDER_AMBIENT, 1.0F, 0.5F);
        }
    }

    /** No collision, no gravity and nothing can hurt it while it is inside the wall. */
    private void beginEmergeState() {
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    private void finishEmerging() {
        this.noPhysics = false;
        this.setNoGravity(false);
        this.setInvulnerable(false);
        if (this.level() instanceof ServerLevel server && this.emergePlane != null) {
            ParticleOptions chips = this.emergeChips != null ? this.emergeChips : Fx.dirt();
            Vec3 forward = Vec3.directionFromRotation(0.0F, this.getYRot());
            double x = this.emergePlane.x + forward.x * 0.5D;
            double z = this.emergePlane.z + forward.z * 0.5D;
            // The last shove: a burst of rubble, a shockwave along the floor and a hiss.
            server.sendParticles(chips, x, this.emergePlane.y + 0.5D, z, 50, 0.8D, 0.5D, 0.8D, 0.12D);
            Fx.shockwave(server, chips, x, this.emergePlane.y + 0.1D, z, 2.0D);
            Fx.dust(server, x, this.emergePlane.y + 0.4D, z, 12, 0.8D);
            Fx.sound(server, x, this.emergePlane.y, z, SoundEvents.DEEPSLATE_BREAK, 1.3F, 0.6F);
            Fx.sound(server, x, this.emergePlane.y, z, SoundEvents.SPIDER_HURT, 1.2F, 0.5F);
        }
        this.emergePlane = null;
        this.emergeChips = null;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && isEmerging()) {
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
            // Walk straight out of the wall: the whole trip is split evenly over the animation.
            Vec3 step = Vec3.directionFromRotation(0.0F, this.getYRot())
                    .scale((EMERGE_START_INSIDE + EMERGE_END_OUTSIDE) / EMERGE_DURATION);
            this.setPos(getX() + step.x, getY(), getZ() + step.z);

            if (this.emergePlane != null && getEmergeTicks() % 2 == 0 && this.level() instanceof ServerLevel server) {
                ParticleOptions chips = this.emergeChips != null ? this.emergeChips : Fx.dirt();
                server.sendParticles(chips, this.emergePlane.x, this.emergePlane.y + 0.4D, this.emergePlane.z,
                        12, 0.6D, 0.4D, 0.6D, 0.05D);
            }

            setEmergeTicks(getEmergeTicks() - 1);
            if (getEmergeTicks() == 0) {
                finishEmerging();
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("EmergeTicks", getEmergeTicks());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setEmergeTicks(tag.getInt("EmergeTicks"));
        if (isEmerging()) {
            beginEmergeState();
        }
    }

    // ---------------------------------------------------------------- combat

    /**
     * Is the target close enough to sting? The scorpion is wide, so this looks at the gap between
     * the two hitboxes instead of the distance between their centres. "slack" widens the check a
     * little for the moment the sting lands (the target may have moved since the swing began).
     */
    public boolean inStingRange(LivingEntity target, double slack) {
        return this.getBoundingBox().inflate(0.9D + slack, 0.4D, 0.9D + slack).intersects(target.getBoundingBox());
    }

    /** The sting: normal melee damage, then the venom. */
    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            // Scorpion Venom drains to 1 HP, and the sting also leaves them weak and slow.
            living.addEffect(new MobEffectInstance(ModEffects.SCORPION_VENOM, VENOM_TICKS, 0), this);
            living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, VENOM_TICKS, WEAKNESS_AMPLIFIER), this);
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, VENOM_TICKS, SLOWNESS_AMPLIFIER), this);
            stingEffects(living);
        }
        return hit;
    }

    /** Called when the sting starts: a hiss as the tail snaps back. */
    public void attackStartEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 0.5D, getZ(), SoundEvents.SPIDER_HURT, 1.0F, 0.6F);
        }
    }

    /** Called when the stinger lands: green venom splashes out of the wound. */
    private void stingEffects(LivingEntity victim) {
        if (this.level() instanceof ServerLevel server) {
            Fx.venom(server, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(), 22, 0.35D);
            Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.BEE_STING, 1.0F, 0.5F);
            Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SLIME_SQUISH, 0.8F, 0.7F);
        }
    }

    /** A drip of venom now and then while it is angry (client side, only the people looking see it). */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide && this.isAggressive() && this.tickCount % 6 == 0) {
            Vec3 back = Vec3.directionFromRotation(0.0F, this.yBodyRot).scale(-0.6D);
            this.level().addParticle(ParticleTypes.ITEM_SLIME,
                    getX() + back.x + (this.random.nextDouble() - 0.5D) * 0.4D,
                    getY() + 1.0D,
                    getZ() + back.z + (this.random.nextDouble() - 0.5D) * 0.4D,
                    0.0D, -0.02D, 0.0D);
        }
    }

    // ---------------------------------------------------------------- sounds

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.SPIDER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.SPIDER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.SPIDER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.SPIDER_STEP, 0.6F, 0.7F);
    }

    // ---------------------------------------------------------------- animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Body: walk / idle (the legs scuttle while it climbs out of the wall).
        controllers.add(new AnimationController<>(this, "main", 5, this::mainPredicate));
        // Emerge: its own controller with no blending, so it ends cleanly. Only plays while emerging.
        controllers.add(new AnimationController<>(this, "emerge", 0, this::emergePredicate));
        // Sting: runs on its own controller so it plays over walk/idle. Triggered by name from the sting goal.
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", STING));
    }

    private PlayState mainPredicate(AnimationState<HugeScorpionEntity> state) {
        if (isEmerging() || state.isMoving()) {
            return state.setAndContinue(WALK);
        }
        return state.setAndContinue(IDLE);
    }

    private PlayState emergePredicate(AnimationState<HugeScorpionEntity> state) {
        if (isEmerging()) {
            return state.setAndContinue(EMERGE);
        }
        return PlayState.STOP;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
