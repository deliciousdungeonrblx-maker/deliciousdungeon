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
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
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

// huge scorpion, fast hostile ambusher. sting is weak but the venom drains u to 1 HP + weak + slow (milk cures it)
// comes out of a ScorpionBurrowBlock. while emerging (0.5s) its invulnerable, no collision n walks out on its own so the hitbox stays w/ the model (anim slide ignored, see HugeScorpionModel)
// lives near its burrow: stays hidden inside it til a player / hostile mob / cow, sheep or pig comes near, then pops out to attack
// never chases or wanders further than LEASH_RADIUS from its home burrow. no target for a while = runs back home n hides again
// hurt badly / grabbed food = runs for a burrow n hides. hit n run after a sting hops to a different burrow for a few secs
// drops raw scorpion meat (loot_table/entities/huge_scorpion.json)
// test: /summon meshi:huge_scorpion ~ ~ ~   or   /give @s meshi:scorpion_burrow
public class HugeScorpionEntity extends Monster implements GeoEntity {
    // ---- venom, tweak these
    public static final int VENOM_TICKS = 20 * 8;
    // 0 = weakness I, 1 = weakness II
    private static final int WEAKNESS_AMPLIFIER = 0;
    // same for slowness
    private static final int SLOWNESS_AMPLIFIER = 0;

    // ---- size n emerging
    // model is drawn this many x bigger (renderer uses it)
    public static final float MODEL_SCALE = 2.0F;
    public static final int EMERGE_DURATION = 10;
    // blocks behind teh wall face where its origin starts (claws hidden in the wall)
    public static final double EMERGE_START_INSIDE = 1.7D;
    // Blocks infront of the wall where its origin ends up
    public static final double EMERGE_END_OUTSIDE = 1.25D;

    // ---- fighting n hopping, tweak these
    // attacks other hostile mobs closer than this
    private static final double HOSTILE_RANGE = 12.0D;
    public static final double HOP_RANGE = 14.0D;
    public static final double HOP_DEST_RANGE = 24.0D;
    // no hopping for this long after coming out of a burrow (8s)
    private static final int HOP_COOLDOWN = 20 * 8;
    private static final float HOP_CHANCE_AFTER_STING = 0.4F;
    // always runs for a burrow below this share of max hp
    private static final float HOP_FLEE_HEALTH = 0.4F;
    private static final double DIVE_SPEED = 0.28D;
    private static final int HOP_STATE_NONE = 0;
    private static final int HOP_STATE_DIVING = 2;
    private static final int HOP_STATE_HIDDEN = 3;

    // ---- staying near the burrows, tweak these
    // wont chase or grab food further than this from its home burrow
    public static final double LEASH_RADIUS = 14.0D;
    // no target this long = goes back home n hides (6s)
    private static final int IDLE_RETURN_TICKS = 20 * 6;
    // hidden one pops out when prey gets this close to its burrow
    private static final double LURK_TRIGGER_RANGE = 6.0D;
    // wont pop out for prey til its healed up to this share of max hp
    private static final float LURK_WAKE_HEALTH = 0.7F;

    private static final EntityDataAccessor<Integer> EMERGE_TICKS =
            SynchedEntityData.defineId(HugeScorpionEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation EMERGE = RawAnimation.begin().thenPlay("emerge");
    private static final RawAnimation STING = RawAnimation.begin().thenPlay("sting");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private Vec3 emergePlane = null;
    private ParticleOptions emergeChips = null;

    private boolean hopRequested = false;
    private int hopCooldown = 0;
    private int hopState = HOP_STATE_NONE;
    private int hopTimer = 0;
    private int hopRetries = 0;
    private BlockPos hopBurrow = null;
    private BlockPos hopDest = null;
    // From deciding to run til its out of the next burrow, nothing can distract it
    private boolean fleeing = false;
    private int noBurrowUntil = 0;
    // burrow it last came out of, its "home"
    private BlockPos home = null;
    private int idleTicks = 0;
    // heading for a burrow to hide in (set by requestRetreat / forceHop), becomes lurking once it dives
    private boolean retreating = false;
    // hidden in its burrow til prey comes near
    private boolean lurking = false;

    public HugeScorpionEntity(EntityType<? extends HugeScorpionEntity> type, Level level) {
        super(type, level);
        this.xpReward = 10;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 16.0D)  // 8 hearts
                .add(Attributes.MOVEMENT_SPEED, 0.27D)  // zombie is 0.23
                .add(Attributes.ATTACK_DAMAGE, 2.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    @Override
    protected void registerGoals() {
        // while climbing out it does nothing else
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
        // only wanders freely if it has no burrow to live at
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.7D) {
            @Override
            public boolean canUse() {
                return HugeScorpionEntity.this.getHome() == null && super.canUse();
            }
        });
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                target -> isWithinLeash(target)));
        // other hostile mobs are fair game too but players come first
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, Mob.class, 10, true, false,
                target -> target instanceof Enemy
                        && !(target instanceof HugeScorpionEntity)
                        && !(target instanceof WalkingMushroomEntity)  // they only bounce off anyway
                        && isWithinLeash(target)
                        && target.distanceToSqr(this) <= HOSTILE_RANGE * HOSTILE_RANGE));
        // food animals too
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, Animal.class, 10, true, false,
                target -> (target instanceof Cow || target instanceof Sheep || target instanceof Pig)
                        && isWithinLeash(target)));
    }

    // ---- emerging

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

    // call right after placing it in the wall facing out. plane = middle of the hole, chips = wall particle
    public void startEmerging(Vec3 plane, ParticleOptions chips) {
        this.emergePlane = plane;
        this.emergeChips = chips;
        setEmergeTicks(EMERGE_DURATION);
        beginUndergroundState();
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.DEEPSLATE_BREAK, 1.4F, 0.5F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.GRAVEL_BREAK, 1.2F, 0.6F);
            Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.SPIDER_AMBIENT, 1.0F, 0.5F);
        }
    }

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
            // walks straight out, trip split evenly over the anim
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
            } else {
                tickHome(server);
            }
        }
    }

    // ---- staying near home

    public BlockPos getHome() {
        return this.home;
    }

    public void setHome(BlockPos pos) {
        this.home = pos;
    }

    public boolean isRetreating() {
        return this.retreating;
    }

    // the burrow its hiding in right now, null if its not hidden
    public BlockPos getHiddenBurrow() {
        return isHidden() ? this.hopBurrow : null;
    }

    // targets n food further than LEASH_RADIUS from home are ignored
    public boolean isWithinLeash(Entity entity) {
        return this.home == null
                || entity.distanceToSqr(this.home.getX() + 0.5D, this.home.getY() + 0.5D, this.home.getZ() + 0.5D)
                <= LEASH_RADIUS * LEASH_RADIUS;
    }

    // goals call this while theyre busy so it doesnt run home mid task
    public void noteBusy() {
        this.idleTicks = 0;
    }

    private void tickHome(ServerLevel server) {
        if (isEmerging()) {
            this.idleTicks = 0;
            return;
        }
        if (this.tickCount % 60 == 0) {
            refreshHome(server);
        }
        LivingEntity target = getTarget();
        if (target != null && !isWithinLeash(target)) {
            setTarget(null);  // too far from home, let it go
        }
        if (this.home != null && getTarget() == null && !this.fleeing && !this.hopRequested) {
            if (++this.idleTicks >= IDLE_RETURN_TICKS) {
                this.idleTicks = 0;
                requestRetreat();
            }
        } else {
            this.idleTicks = 0;
        }
    }

    // home burrow got broken (or it was summoned w/o one): adopt the nearest burrow, if theres any
    private void refreshHome(ServerLevel server) {
        if (this.home != null && server.getBlockState(this.home).getBlock() instanceof ScorpionBurrowBlock) {
            return;
        }
        BlockPos here = this.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : ScorpionBurrowBlock.findBurrows(server, here, 16.0D, 6)) {
            double d = pos.distSqr(here);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        this.home = best;
    }

    // go back to the home burrow n hide in it til prey shows up. ignores the hop cooldown
    private void requestRetreat() {
        if (this.hopState == HOP_STATE_NONE && !isEmerging() && this.tickCount >= this.noBurrowUntil) {
            this.hopRequested = true;
            this.retreating = true;
            this.hopCooldown = 0;
        }
    }

    // hidden n waiting. pops out when prey (or dropped food) gets near the burrow
    private void tickLurk(ServerLevel server) {
        if (this.hopBurrow == null) {
            this.hopBurrow = this.home;
        }
        if (this.hopBurrow == null || !(server.getBlockState(this.hopBurrow).getBlock() instanceof ScorpionBurrowBlock)) {
            // burrow broke while it was hiding: try another one
            this.hopDest = this.hopBurrow == null ? null : ScorpionBurrowBlock.pickOtherBurrow(server, this.hopBurrow, HOP_DEST_RANGE, this.random);
            this.lurking = false;
            this.hopTimer = 10;
            return;
        }
        if (this.tickCount % 20 == 0 && getHealth() < getMaxHealth()) {
            this.heal(1.0F);
        }
        if (this.tickCount % 10 != 0) {
            return;
        }
        boolean healthy = getHealth() >= getMaxHealth() * LURK_WAKE_HEALTH;
        if ((healthy && ScorpionBurrowBlock.preyNear(server, this.hopBurrow, LURK_TRIGGER_RANGE))
                || ScorpionBurrowBlock.foodNearby(server, this.hopBurrow, 9.0D)) {
            this.lurking = false;
            this.hopDest = null;
            this.hopTimer = 5;
        }
    }

    // ---- hopping

    public void requestHop() {
        if (this.hopState == HOP_STATE_NONE && !isEmerging() && this.hopCooldown <= 0) {
            this.hopRequested = true;
        }
    }

    public boolean isHopRequested() {
        return this.hopRequested && this.hopCooldown <= 0 && this.hopState == HOP_STATE_NONE && !isEmerging();
    }

    public void clearHopRequest(int cooldownTicks) {
        this.hopRequested = false;
        this.retreating = false;
        this.fleeing = false;
        this.hopCooldown = Math.max(this.hopCooldown, cooldownTicks);
    }

    // run for a burrow NOW, ignores cooldown (badly hurt or grabbed food). cant be talked out of it, see isFleeing()
    public void forceHop() {
        if (this.hopState == HOP_STATE_NONE && !isEmerging() && this.tickCount >= this.noBurrowUntil) {
            this.hopRequested = true;
            this.retreating = true;
            this.hopCooldown = 0;
            setFleeing(true);
        }
    }

    public void noteNoBurrow() {
        this.noBurrowUntil = this.tickCount + 60;
    }

    // while true it ignores everything but getting into a burrow, no sting n no new targets
    public boolean isFleeing() {
        return this.fleeing;
    }

    public void setFleeing(boolean value) {
        this.fleeing = value;
        if (value) {
            super.setTarget(null);
        }
    }

    @Override
    public void setTarget(LivingEntity target) {
        if (this.fleeing && target != null) {
            return;
        }
        super.setTarget(target);
    }


    // grabs a dropped food stack n sprints for a burrwo, drops it again if killed on the way
    public void stealFood(ItemEntity item) {
        if (!this.getMainHandItem().isEmpty() || !item.isAlive()) {
            return;
        }
        this.setItemSlot(EquipmentSlot.MAINHAND, item.getItem().copy());
        this.setDropChance(EquipmentSlot.MAINHAND, 2.0F);  // above 1.0 = always drops
        item.discard();
        this.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 0.8F);
        this.playSound(SoundEvents.SPIDER_AMBIENT, 1.0F, 0.6F);
        forceHop();
    }

    public void eatCarried() {
        ItemStack carried = this.getMainHandItem();
        if (!carried.isEmpty()) {
            this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            this.heal(4.0F);
            this.playSound(SoundEvents.GENERIC_EAT, 1.0F, 0.7F);
        }
    }

    public boolean isHidden() {
        return this.hopState != HOP_STATE_NONE;
    }

    public void beginDive(BlockPos burrow) {
        if (!(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.hopRequested = false;
        this.hopBurrow = burrow;
        // retreating = hides here n waits for prey, hit n run = comes out of another burrow after a few secs
        this.lurking = this.retreating;
        this.retreating = false;
        this.hopDest = this.lurking ? null : ScorpionBurrowBlock.pickOtherBurrow(server, burrow, HOP_DEST_RANGE, this.random);
        this.hopState = HOP_STATE_DIVING;
        this.hopTimer = 45;  // safety net
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
            this.discard();  // no burrow remembered
            return;
        }
        this.setDeltaMovement(Vec3.ZERO);

        if (this.hopState == HOP_STATE_DIVING) {
            if (!(server.getBlockState(this.hopBurrow).getBlock() instanceof ScorpionBurrowBlock)) {
                this.discard();  // burrow broke mid dive
                return;
            }
            Direction dir = ScorpionBurrowBlock.facingOf(server, this.hopBurrow, Direction.NORTH);
            Vec3 plane = ScorpionBurrowBlock.planeOf(this.hopBurrow, dir);
            double insideX = plane.x - dir.getStepX() * EMERGE_START_INSIDE;
            double insideZ = plane.z - dir.getStepZ() * EMERGE_START_INSIDE;

            float yaw = Mth.approachDegrees(this.getYRot(), dir.getOpposite().toYRot(), 40.0F);
            this.setYRot(yaw);
            this.setYBodyRot(yaw);
            this.setYHeadRot(yaw);

            // arrived at an angle: scuttle sideways til its infront of the hole
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
                this.setPos(insideX, getY(), insideZ);
                this.setInvisible(true);
                this.setSilent(true);
                eatCarried();
                this.heal(3.0F);  // rest in the burrow
                this.hopState = HOP_STATE_HIDDEN;
                this.hopTimer = 60 + this.random.nextInt(80);
                Fx.dust(server, plane.x, plane.y + 0.3D, plane.z, 10, 0.6D);
                Fx.sound(server, plane.x, plane.y, plane.z, SoundEvents.DEEPSLATE_BREAK, 1.0F, 0.6F);
            }
        } else if (this.hopState == HOP_STATE_HIDDEN) {
            if (this.lurking) {
                tickLurk(server);
                return;
            }
            // food dropped near teh burrow its hiding in: comes out right there
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
            this.discard();  // both burrows gone
            return;
        }
        this.hopRetries++;
        this.hopTimer = 40;  // no room, try again in 2s
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("EmergeTicks", getEmergeTicks());
        tag.putInt("HopState", this.hopState);
        tag.putInt("HopTimer", this.hopTimer);
        putPos(tag, "HopBurrow", this.hopBurrow);
        putPos(tag, "HopDest", this.hopDest);
        putPos(tag, "Home", this.home);
        tag.putBoolean("Lurking", this.lurking);
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
        this.home = getPos(tag, "Home");
        this.lurking = tag.getBoolean("Lurking");
        if (tag.getInt("HopState") >= HOP_STATE_DIVING) {
            // saved while diving/hiding: comes back hidden n pops out soon
            this.hopState = HOP_STATE_HIDDEN;
            this.hopTimer = Math.min(Math.max(tag.getInt("HopTimer"), 20), 60);
            beginUndergroundState();
            this.setInvisible(true);
            this.setSilent(true);
        }
    }

    // ---- combat

    // Close enough to sting? checks the gap between the hitboxes (scorpion is wide), slack widens it for when the sting lands
    public boolean inStingRange(LivingEntity target, double slack) {
        return this.getBoundingBox().inflate(0.9D + slack, 0.4D, 0.9D + slack).intersects(target.getBoundingBox());
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(ModEffects.SCORPION_VENOM, VENOM_TICKS, 0), this);
            living.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, VENOM_TICKS, WEAKNESS_AMPLIFIER), this);
            living.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, VENOM_TICKS, SLOWNESS_AMPLIFIER), this);
            stingEffects(living);
            if (this.random.nextFloat() < HOP_CHANCE_AFTER_STING) {
                requestHop();  // hit and run
            }
        }
        return hit;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean hurt = super.hurt(source, amount);
        if (hurt && !this.level().isClientSide && this.getHealth() < this.getMaxHealth() * HOP_FLEE_HEALTH) {
            forceHop();  // guaranteed, no cooldown
        }
        return hurt;
    }

    public void attackStartEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 0.5D, getZ(), SoundEvents.SPIDER_HURT, 1.0F, 0.6F);
        }
    }

    private void stingEffects(LivingEntity victim) {
        if (this.level() instanceof ServerLevel server) {
            Fx.venom(server, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6D, victim.getZ(), 22, 0.35D);
            Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.BEE_STING, 1.0F, 0.5F);
            Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.SLIME_SQUISH, 0.8F, 0.7F);
        }
    }

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

    // ---- sounds

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

    // ---- animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 5, this::mainPredicate));
        // emerge: own controller, no blending
        controllers.add(new AnimationController<>(this, "emerge", 0, this::emergePredicate));
        // sting: own controller so it plays over walk/idle
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
