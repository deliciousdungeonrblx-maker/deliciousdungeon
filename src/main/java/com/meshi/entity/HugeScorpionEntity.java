package com.meshi.entity;

import com.meshi.registry.ModEffects;
import com.meshi.util.Fx;
import com.meshi.block.ScorpionBurrowBlock;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
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
 * The huge scorpion: a fast, hostile ambusher. Its sting hurts a little, but the venom is the real
 * danger: a stung creature is drained down to 1 HP (Scorpion Venom never kills by itself) and is
 * left Weak and Slow while it lasts. Milk cures all three.
 *
 * It usually comes out of a Scorpion Burrow (a hole on the side of a wall, see ScorpionBurrowBlock).
 * While it climbs out ("emerge", 0.5 s) it is invulnerable, has no collision and walks out of the wall
 * on its own, so the model and the hitbox stay together. The emerge animation's own forward slide is
 * ignored on purpose (see HugeScorpionModel), the entity does the moving.
 *
 * It also fights other hostile mobs that come near, and it likes to move around between burrows: now
 * and then (after a sting, when it is badly hurt, or just at random while idle) it runs to a burrow
 * nearby, dives into the wall, waits a few seconds out of sight, and climbs out of a different burrow.
 *
 * It drops Raw Scorpion Meat (see data/meshi/loot_table/entities/huge_scorpion.json).
 *
 * Test it with:  /summon meshi:huge_scorpion ~ ~ ~
 * Or in the wall (the open side is the way it faces):  /give @s meshi:scorpion_burrow
 */
public class HugeScorpionEntity extends Monster implements GeoEntity {
    // ---- Venom: tweak these --------------------------------------------------------------------
    /** How long the venom lasts, in ticks (20 ticks = 1 second). */
    public static final int VENOM_TICKS = 20 * 8;
    /** Weakness level minus one: 0 = Weakness I, 1 = Weakness II. */
    private static final int WEAKNESS_AMPLIFIER = 0;
    /** Slowness level minus one: 0 = Slowness I, 1 = Slowness II. */
    private static final int SLOWNESS_AMPLIFIER = 0;

    // ---- Size and emerging -----------------------------------------------------------------------
    /** The Blockbench model is drawn this many times bigger. Used by HugeScorpionRenderer. */
    public static final float MODEL_SCALE = 2.0F;
    /** Length of the "emerge" animation (0.5 s). */
    public static final int EMERGE_DURATION = 10;
    /** Blocks behind the wall face where the scorpion's origin starts (hides its claws inside the wall). */
    public static final double EMERGE_START_INSIDE = 1.7D;
    /** Blocks in front of the wall face where its origin ends up (so its hitbox is clear of the wall). */
    public static final double EMERGE_END_OUTSIDE = 1.25D;

    // ---- Fighting other monsters and hopping between burrows: tweak these ------------------------
    /** It attacks other hostile mobs (not its own kind) closer than this many blocks. */
    private static final double HOSTILE_RANGE = 12.0D;
    /** How far it looks for a burrow to run to. */
    public static final double HOP_RANGE = 14.0D;
    /** How far from that burrow it may come out again. */
    public static final double HOP_DEST_RANGE = 24.0D;
    /** Ticks it will not hop again after coming out of a burrow (8 s). */
    private static final int HOP_COOLDOWN = 20 * 8;
    /** Chance to hop away after landing a sting (hit and run). */
    private static final float HOP_CHANCE_AFTER_STING = 0.4F;
    /** It always runs for a burrow when its health drops below this share of its maximum. */
    private static final float HOP_FLEE_HEALTH = 0.4F;
    /** While idle it hops about once per this many seconds on average. */
    private static final int HOP_IDLE_ODDS = 45;
    /** Blocks per tick while it dives into the wall. */
    private static final double DIVE_SPEED = 0.28D;
    private static final int HOP_STATE_NONE = 0;
    private static final int HOP_STATE_DIVING = 2;
    private static final int HOP_STATE_HIDDEN = 3;

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

    // Hopping between burrows (server side only).
    private boolean hopRequested = false;
    private int hopCooldown = 0;
    private int hopState = HOP_STATE_NONE;
    private int hopTimer = 0;
    private int hopRetries = 0;
    /** The burrow it dives into, and the one it will come out of (null = same one). */
    private BlockPos hopBurrow = null;
    private BlockPos hopDest = null;
    /** True from the moment it decides to run for a burrow until it is out of the next one: nothing can distract it. */
    private boolean fleeing = false;
    /** No burrow was in reach: do not look again before this tick. */
    private int noBurrowUntil = 0;

    public HugeScorpionEntity(EntityType<? extends HugeScorpionEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)           // 8 hearts (a zombie has 10)
                .add(Attributes.MOVEMENT_SPEED, 0.27D)       // a little quicker than a zombie (0.23)
                .add(Attributes.ATTACK_DAMAGE, 2.0D)         // 1 heart
                .add(Attributes.ARMOR, 2.0D)                 // light shell
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
                return HugeScorpionEntity.this.isEmerging() || HugeScorpionEntity.this.isHidden();
            }

            @Override
            public boolean canContinueToUse() {
                return canUse();
            }
        });
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(1, new ScorpionHopGoal(this));
        this.goalSelector.addGoal(2, new ScorpionStingGoal(this, 1.1D));
        this.goalSelector.addGoal(2, new ScorpionScavengeGoal(this));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.7D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        // Other hostile mobs that wander close (zombies, skeletons, ...) are fair game too, but players come first.
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                target -> target instanceof Enemy
                        && !(target instanceof HugeScorpionEntity)
                        && target.distanceToSqr(this) <= HOSTILE_RANGE * HOSTILE_RANGE));
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
        beginUndergroundState();
        if (this.level() instanceof ServerLevel server) {
            // A low rumble and a crack as the stone gives way.
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.DEEPSLATE_BREAK, 1.4F, 0.5F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.SPIDER_AMBIENT, 1.0F, 0.5F);
        }
    }

    /** No collision, no gravity and nothing can hurt it while it is inside the wall. */
    private void beginUndergroundState() {
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
        this.hopCooldown = HOP_COOLDOWN;
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

        if (!this.level().isClientSide && this.level() instanceof ServerLevel server) {
            if (this.hopCooldown > 0) {
                this.hopCooldown--;
            }
            if (this.hopState != HOP_STATE_NONE) {
                tickHop(server);
            } else if (getTarget() == null && this.tickCount % 20 == 0 && this.random.nextInt(HOP_IDLE_ODDS) == 0) {
                requestHop(); // nothing to do: wander off to another burrow
            }
        }
    }

    // ---------------------------------------------------------------- hopping between burrows

    /** Wants to run to a burrow, dive in and come out of another one. Does nothing during the cooldown. */
    public void requestHop() {
        if (this.hopState == HOP_STATE_NONE && !isEmerging() && this.hopCooldown <= 0) {
            this.hopRequested = true;
        }
    }

    /** Is it ready to go looking for a burrow right now? (Used by ScorpionHopGoal.) */
    public boolean isHopRequested() {
        return this.hopRequested && this.hopCooldown <= 0 && this.hopState == HOP_STATE_NONE && !isEmerging();
    }

    /** Forget the wish to hop, and wait a while before wanting it again. */
    public void clearHopRequest(int cooldownTicks) {
        this.hopRequested = false;
        this.fleeing = false;
        this.hopCooldown = Math.max(this.hopCooldown, cooldownTicks);
    }

    /**
     * Run for a burrow NOW, whatever the cooldown (badly hurt, or it just grabbed food). It will not be
     * talked out of it: see isFleeing().
     */
    public void forceHop() {
        if (this.hopState == HOP_STATE_NONE && !isEmerging() && this.tickCount >= this.noBurrowUntil) {
            this.hopRequested = true;
            this.hopCooldown = 0;
            setFleeing(true);
        }
    }

    /** The goal could not find a burrow to run to: do not look again for 3 seconds. */
    public void noteNoBurrow() {
        this.noBurrowUntil = this.tickCount + 60;
    }

    /** While true it ignores everything except getting into a burrow: no stinging, no new targets. */
    public boolean isFleeing() {
        return this.fleeing;
    }

    public void setFleeing(boolean value) {
        this.fleeing = value;
        if (value) {
            super.setTarget(null);
        }
    }

    /** Nobody can talk a fleeing scorpion into a new target (a hit, or a player walking past). */
    @Override
    public void setTarget(LivingEntity target) {
        if (this.fleeing && target != null) {
            return;
        }
        super.setTarget(target);
    }

    // ---- food ---------------------------------------------------------------------------------------

    /** Grabs a dropped food item (the whole stack) and sprints for a burrow. It drops it again if killed on the way. */
    public void stealFood(ItemEntity item) {
        if (!this.getMainHandItem().isEmpty() || !item.isAlive()) {
            return;
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, item.getItem().copy());
        this.setDropChance(EquipmentSlot.MAINHAND, 2.0F); // above 1.0 = always drops, whoever kills it
        item.discard();
        this.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 0.8F);
        this.playSound(SoundEvents.SPIDER_AMBIENT, 1.0F, 0.6F);
        forceHop();
    }

    /** Eats whatever it is carrying (safe in its burrow) and heals a little. */
    public void eatCarried() {
        ItemStack carried = this.getMainHandItem();
        if (!carried.isEmpty()) {
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            this.heal(4.0F);
            this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 0.7F);
        }
    }

    /** True while it is diving into a wall or waiting out of sight inside it. */
    public boolean isHidden() {
        return this.hopState != HOP_STATE_NONE;
    }

    /** It reached the burrow: dive in. It will come out of another burrow nearby if there is one. */
    public void beginDive(BlockPos burrow) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.hopRequested = false;
        this.hopBurrow = burrow;
        this.hopDest = ScorpionBurrowBlock.pickOtherBurrow(server, burrow, HOP_DEST_RANGE, this.random);
        this.hopState = HOP_STATE_DIVING;
        this.hopTimer = 45; // safety net, it normally takes about 12 to 20 ticks
        this.hopRetries = 0;
        setTarget(null);
        this.getNavigation().stop();
        beginUndergroundState();
        Vec3 plane = ScorpionBurrowBlock.planeOf(burrow, ScorpionBurrowBlock.facingOf(server, burrow, this.getDirection().getOpposite()));
        Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
        Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.SPIDER_AMBIENT, 1.0F, 0.5F);
    }

    private void tickHop(ServerLevel server) {
        if (this.hopBurrow == null && this.hopDest == null) {
            this.discard(); // saved while hiding but no burrow was remembered
            return;
        }
        this.setDeltaMovement(Vec3.ZERO);

        if (this.hopState == HOP_STATE_DIVING) {
            if (!(server.getBlockState(this.hopBurrow).getBlock() instanceof ScorpionBurrowBlock)) {
                this.discard(); // the burrow was broken while it was diving into it
                return;
            }
            Direction dir = ScorpionBurrowBlock.facingOf(server, this.hopBurrow, Direction.NORTH);
            Vec3 plane = ScorpionBurrowBlock.planeOf(this.hopBurrow, dir);
            double insideX = plane.x - dir.getStepX() * EMERGE_START_INSIDE;
            double insideZ = plane.z - dir.getStepZ() * EMERGE_START_INSIDE;

            // Turn to face the wall, then scuttle straight into it.
            float yaw = Mth.approachDegrees(this.getYRot(), dir.getOpposite().toYRot(), 40.0F);
            this.setYRot(yaw);
            this.setYBodyRot(yaw);
            this.setYHeadRot(yaw);

            // If it arrived at an angle, first scuttle sideways until it is right in front of the hole.
            double rx = getX() - plane.x;
            double rz = getZ() - plane.z;
            double along = rx * dir.getStepX() + rz * dir.getStepZ();
            double side = -rx * dir.getStepZ() + rz * dir.getStepX();
            boolean aligning = along > 0.2D && Math.abs(side) > 0.2D;
            double targetX = aligning ? plane.x + dir.getStepX() * along : insideX;
            double targetZ = aligning ? plane.z + dir.getStepZ() * along : insideZ;

            double dx = targetX - getX();
            double dz = targetZ - getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > 0.06D && this.hopTimer > 0) {
                double move = Math.min(dist, DIVE_SPEED) / dist;
                this.setPos(getX() + dx * move, getY(), getZ() + dz * move);
                if (this.hopTimer % 2 == 0) {
                    ParticleOptions chips = ScorpionBurrowBlock.chipsFor(server, this.hopBurrow, dir);
                    server.sendParticles(chips, plane.x, plane.y + 0.4D, plane.z, 10, 0.6D, 0.4D, 0.6D, 0.05D);
                }
                this.hopTimer--;
            } else {
                // Gone: out of sight, silent, and waiting in the wall for a few seconds.
                this.setPos(insideX, getY(), insideZ);
                this.setInvisible(true);
                this.setSilent(true);
                eatCarried();
                this.heal(3.0F); // a rest in the burrow
                this.hopState = HOP_STATE_HIDDEN;
                this.hopTimer = 60 + this.random.nextInt(80);
                Fx.dust(server, plane.x, plane.y + 0.3D, plane.z, 10, 0.6D);
                Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.DEEPSLATE_BREAK, 1.0F, 0.6F);
            }
        } else if (this.hopState == HOP_STATE_HIDDEN) {
            // Food dropped near the burrow it is hiding in: out it comes, right there.
            if (this.hopTimer > 10 && this.tickCount % 10 == 0 && this.hopBurrow != null
                    && ScorpionBurrowBlock.foodNearby(server, this.hopBurrow, 9.0D)) {
                this.hopDest = null;
                this.hopTimer = 10;
            }
            if (--this.hopTimer <= 0) {
                reappear(server);
            }
        }
    }

    /** Comes out of the other burrow (or the one it went into if there is no other, or no room). */
    private void reappear(ServerLevel server) {
        BlockPos[] options = {this.hopDest, this.hopBurrow};
        boolean force = this.hopRetries > 6;
        for (BlockPos pos : options) {
            if (pos != null && ScorpionBurrowBlock.placeEmerging(server, pos, server.getBlockState(pos), this, !force)) {
                this.hopState = HOP_STATE_NONE;
                this.hopBurrow = null;
                this.hopDest = null;
                this.hopRetries = 0;
                this.setInvisible(false);
                this.setSilent(false);
                this.fleeing = false;
                return;
            }
        }
        if (force) {
            this.discard(); // both burrows are gone
            return;
        }
        this.hopRetries++;
        this.hopTimer = 40; // no room in front of the burrows right now, try again in 2 seconds
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("EmergeTicks", getEmergeTicks());
        tag.putInt("HopState", this.hopState);
        tag.putInt("HopTimer", this.hopTimer);
        putPos(tag, "HopBurrow", this.hopBurrow);
        putPos(tag, "HopDest", this.hopDest);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setEmergeTicks(tag.getInt("EmergeTicks"));
        if (isEmerging()) {
            beginUndergroundState();
        }
        this.hopBurrow = getPos(tag, "HopBurrow");
        this.hopDest = getPos(tag, "HopDest");
        if (tag.getInt("HopState") >= HOP_STATE_DIVING) {
            // Saved while diving or hiding: pick up as hidden, and come out soon.
            this.hopState = HOP_STATE_HIDDEN;
            this.hopTimer = Math.min(Math.max(tag.getInt("HopTimer"), 20), 60);
            beginUndergroundState();
            this.setInvisible(true);
            this.setSilent(true);
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
            if (this.random.nextFloat() < HOP_CHANCE_AFTER_STING) {
                requestHop(); // hit and run
            }
        }
        return hit;
    }

    /** Badly hurt: run for a burrow. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && this.getHealth() < this.getMaxHealth() * HOP_FLEE_HEALTH) {
            forceHop(); // guaranteed: no cooldown, nothing distracts it on the way
        }
        return hurt;
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

    private static void putPos(CompoundTag tag, String key, BlockPos pos) {
        if (pos != null) {
            tag.putIntArray(key, new int[] {pos.getX(), pos.getY(), pos.getZ()});
        }
    }

    private static BlockPos getPos(CompoundTag tag, String key) {
        int[] a = tag.getIntArray(key);
        return a.length == 3 ? new BlockPos(a[0], a[1], a[2]) : null;
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
