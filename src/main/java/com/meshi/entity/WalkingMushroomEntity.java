package com.meshi.entity;

import com.meshi.registry.ModParticles;
import com.meshi.registry.ModSounds;
import com.meshi.util.Fx;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
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
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The walking mushroom: a small, common, deliberately weak dungeon mob (8 HP, a half-heart headbutt).
 *
 * <b>It can only be sliced vertically</b> (as in the series). A hit counts as a vertical slice when a
 * blade (sword, axe, Farmer's Delight knife) lands as a falling critical hit, the "jump and chop down" attack.
 * Sweeping hits can never be criticals, so sideways swings are excluded automatically. A proper slice kills
 * it outright. Anything else (a normal hit, a punch, an arrow, a pickaxe...) only makes it go *boing*:
 * half a heart of damage at most, it never dies from those, and it hops with a squeak.
 *
 * <b>Hit it with a stick</b> and it is knocked out for 15 seconds: it tips over, snores and floats little
 * z's. Another stick hit resets the timer; any other hit wakes it up.
 *
 * Other things it does:
 *  - Disguise: it stands still like a plain mushroom (body sunk into the ground, legs gone) and pops up
 *    when a player comes within 4 blocks (2 blocks if the player sneaks). It also hides again by itself
 *    after a while without company. Test with  /summon meshi:walking_mushroom ~ ~ ~ {Disguised:1b}
 *  - Conga: awake mushrooms nearby follow each other in a line.
 *  - Pack alarm: when one is hit, knocked out or popped, the others nearby pop up and go for the player.
 *  - Snack: dropped mushrooms, fungi and bone meal lure it (see MushroomSnackGoal). It eats, heals and hops happily.
 *  - Trip: while running at a player it sometimes face-plants and is stunned for a moment.
 *  - Headbutt: the "attack" animation, then it is dizzy for a second (see MushroomBonkGoal).
 *
 * It drops a Mush Corpse (dice it on a cutting board for Mush Body and Mush Feet), see
 * data/meshi/loot_table/entities/walking_mushroom.json.
 */
public class WalkingMushroomEntity extends Monster implements GeoEntity {
    // ---- Tweak these ---------------------------------------------------------------------------------
    /** How long a stick knockout lasts, in ticks (20 ticks = 1 second). */
    public static final int KNOCKOUT_TICKS = 20 * 15;
    /** Damage from a hit that is not a vertical slice (it never drops below 0.5 HP from these). */
    private static final float WRONG_HIT_DAMAGE = 0.5F;
    /** A disguised mushroom pops up when a player gets this close (halved when the player sneaks). */
    private static final double DETECT_RANGE = 4.0D;
    /** Other walking mushrooms this close hear the alarm. */
    private static final double PACK_RANGE = 12.0D;
    /** A mushroom that has been left alone this long (no target, no player within 12 blocks) may hide again. */
    private static final int CALM_TICKS_TO_HIDE = 20 * 20;
    /** 1 in this many ticks (while chasing at speed) it trips over its own feet. */
    private static final int TRIP_ODDS = 260;
    private static final int TRIP_TICKS = 24;
    private static final int TRIP_COOLDOWN = 160;
    /** Ticks it stands still while the pop-up / wake-up / happy hop animations play. */
    private static final int POP_HOLD = 12;
    private static final int WAKE_HOLD = 14;
    private static final int HAPPY_HOLD = 14;
    /** Ticks before it wants another snack (30 s). */
    private static final int SNACK_COOLDOWN = 20 * 30;

    /** Things that cut: swords, axes and knives (Farmer's Delight knives are in c:tools/knife). */
    private static final List<TagKey<Item>> BLADE_TAGS = List.of(
            itemTag("minecraft", "swords"), itemTag("minecraft", "axes"),
            itemTag("c", "tools/knife"), itemTag("c", "tools/sword"), itemTag("c", "tools/axe"));

    private static final EntityDataAccessor<Boolean> DISGUISED =
            SynchedEntityData.defineId(WalkingMushroomEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> KNOCKED_OUT =
            SynchedEntityData.defineId(WalkingMushroomEntity.class, EntityDataSerializers.BOOLEAN);

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation HIDDEN = RawAnimation.begin().thenLoop("hidden");
    private static final RawAnimation SLEEP = RawAnimation.begin().thenPlay("fall_asleep").thenLoop("sleep");
    private static final RawAnimation ATTACK = RawAnimation.begin().thenPlay("attack");
    private static final RawAnimation POP_UP = RawAnimation.begin().thenPlay("pop_up");
    private static final RawAnimation WAKE_UP = RawAnimation.begin().thenPlay("wake_up");
    private static final RawAnimation TRIP = RawAnimation.begin().thenPlay("trip");
    private static final RawAnimation HAPPY_HOP = RawAnimation.begin().thenPlay("happy_hop");
    private static final RawAnimation STARTLE = RawAnimation.begin().thenPlay("startle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    // Server side only.
    private int knockoutTicks = 0;
    /** Ticks it must stand still for an animation (pop up, wake up, trip, happy hop). */
    private int holdTicks = 0;
    private int tripCooldown = 0;
    private int snackCooldown = 0;
    private int alarmCooldown = 0;
    private int hintCooldown = 0;
    private int calmTicks = 0;

    public WalkingMushroomEntity(EntityType<? extends WalkingMushroomEntity> type, Level level) {
        super(type, level);
        this.xpReward = 3;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 8.0D)            // 4 hearts
                .add(Attributes.MOVEMENT_SPEED, 0.22D)       // about a zombie's pace, easy to outrun
                .add(Attributes.ATTACK_DAMAGE, 1.0D)         // half a heart
                .add(Attributes.ATTACK_KNOCKBACK, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 14.0D);
    }

    private static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    @Override
    protected void registerGoals() {
        // While it is asleep, hidden or playing an animation it does nothing else.
        this.goalSelector.addGoal(0, new Goal() {
            {
                this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
            }

            @Override
            public boolean canUse() {
                return WalkingMushroomEntity.this.isBusy();
            }

            @Override
            public boolean canContinueToUse() {
                return canUse();
            }

            @Override
            public void start() {
                WalkingMushroomEntity.this.getNavigation().stop();
            }
        });
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MushroomSnackGoal(this));
        this.goalSelector.addGoal(3, new MushroomBonkGoal(this, 1.0D));
        this.goalSelector.addGoal(4, new MushroomCongaGoal(this));
        this.goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.6D));
        this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ---------------------------------------------------------------- state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DISGUISED, false);
        builder.define(KNOCKED_OUT, false);
    }

    public boolean isDisguised() {
        return this.entityData.get(DISGUISED);
    }

    public void setDisguised(boolean value) {
        this.entityData.set(DISGUISED, value);
    }

    public boolean isKnockedOut() {
        return this.entityData.get(KNOCKED_OUT);
    }

    public void setKnockedOut(boolean value) {
        this.entityData.set(KNOCKED_OUT, value);
    }

    /** Asleep, hidden or playing an animation: it cannot walk, look around or fight. (Real value on the server only.) */
    public boolean isBusy() {
        return isDisguised() || isKnockedOut() || this.holdTicks > 0;
    }

    public int getSnackCooldown() {
        return this.snackCooldown;
    }

    /** A disguised or sleeping mushroom cannot pick a fight. */
    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && (isDisguised() || isKnockedOut())) {
            return;
        }
        super.setTarget(target);
    }

    /** Players it will chase (the same ones a zombie would: not creative, not spectators). */
    private static boolean isChaseable(Entity entity) {
        return entity instanceof Player player && !player.isCreative() && !player.isSpectator();
    }

    private void sfx(ServerLevel server, DeferredHolder<SoundEvent, SoundEvent> sound, float volume, float pitch) {
        Fx.soundVaried(server, getX(), getY() + 0.4D, getZ(), sound.get(), volume, pitch);
    }

    private void spores(ServerLevel server, int count, double spread) {
        server.sendParticles(ParticleTypes.CRIMSON_SPORE, getX(), getY() + 0.8D, getZ(), count,
                spread, spread * 0.6D, spread, 0.02D);
    }

    // ---------------------------------------------------------------- ticking

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        if (this.alarmCooldown > 0) {
            this.alarmCooldown--;
        }
        if (this.snackCooldown > 0) {
            this.snackCooldown--;
        }
        if (this.tripCooldown > 0) {
            this.tripCooldown--;
        }
        if (this.hintCooldown > 0) {
            this.hintCooldown--;
        }
        if (this.holdTicks > 0) {
            this.holdTicks--;
            this.getNavigation().stop();
        }

        if (isKnockedOut()) {
            tickKnockedOut(server);
        } else if (isDisguised()) {
            tickDisguised(server);
        } else {
            tickAwake(server);
        }
    }

    private void tickKnockedOut(ServerLevel server) {
        this.getNavigation().stop();
        if (--this.knockoutTicks <= 0) {
            wakeUp(server, false);
            return;
        }
        // A "z" and a soft snore every 1.25 seconds.
        if (this.tickCount % 25 == 0) {
            server.sendParticles(ModParticles.ZZZ.get(), getX(), getY() + 0.9D, getZ(), 1, 0.12D, 0.05D, 0.12D, 0.0D);
            sfx(server, ModSounds.MUSHROOM_SNORE, 0.5F, 1.0F);
        }
    }

    private void tickDisguised(ServerLevel server) {
        this.getNavigation().stop();
        if (this.tickCount % 5 != 0) {
            return;
        }
        Player player = server.getNearestPlayer(getX(), getY(), getZ(), DETECT_RANGE, EntitySelector.NO_SPECTATORS);
        // Sneaking up works: a sneaking player has to get within half the distance.
        if (player != null && (!player.isShiftKeyDown() || player.distanceToSqr(this) < 4.0D)) {
            popUp(player, true);
        }
    }

    private void tickAwake(ServerLevel server) {
        LivingEntity target = getTarget();

        // Left alone for a while: squat down and pretend to be a plain mushroom.
        if (target != null) {
            this.calmTicks = 0;
        } else if (this.tickCount % 20 == 0) {
            if (server.getNearestPlayer(getX(), getY(), getZ(), 12.0D, EntitySelector.NO_SPECTATORS) != null) {
                this.calmTicks = 0;
            } else {
                this.calmTicks += 20;
                if (this.calmTicks >= CALM_TICKS_TO_HIDE && this.holdTicks == 0 && this.getNavigation().isDone()
                        && this.random.nextInt(6) == 0) {
                    disguise(server);
                }
            }
        }

        // Chasing at speed: now and then it trips over its own feet.
        if (target != null && this.tripCooldown == 0 && this.holdTicks == 0 && this.onGround() && !this.isInWater()
                && this.random.nextInt(TRIP_ODDS) == 0
                && this.getDeltaMovement().horizontalDistanceSqr() > 0.0025D
                && this.distanceToSqr(target) > 4.0D) {
            trip(server);
        }
    }

    // ---------------------------------------------------------------- the things it can do

    /** Squat down and look like a plain mushroom. */
    public void disguise(ServerLevel server) {
        setDisguised(true);
        setTarget(null);
        setAggressive(false);
        this.getNavigation().stop();
        this.holdTicks = 0;
        this.calmTicks = 0;
        sfx(server, ModSounds.MUSHROOM_SQUEAK, 0.4F, 1.5F);
        server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.1D, getZ(), 4, 0.15D, 0.02D, 0.15D, 0.01D);
    }

    /** Springs out of the ground and (for a player it can chase) goes after them. */
    public void popUp(LivingEntity cause, boolean alertNeighbours) {
        if (!isDisguised() || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        setDisguised(false);
        this.holdTicks = POP_HOLD;
        this.calmTicks = 0;
        triggerAnim("action", "pop_up");
        sfx(server, ModSounds.MUSHROOM_POP, 1.0F, 1.0F);
        spores(server, 18, 0.25D);
        server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.1D, getZ(), 5, 0.2D, 0.02D, 0.2D, 0.02D);
        if (isChaseable(cause)) {
            setTarget(cause);
        }
        if (alertNeighbours) {
            // A patch of mushrooms right next to it pops up together.
            for (WalkingMushroomEntity other : server.getEntitiesOfClass(WalkingMushroomEntity.class,
                    getBoundingBox().inflate(3.0D, 2.0D, 3.0D), m -> m != this && m.isAlive() && m.isDisguised())) {
                other.popUp(cause, false);
            }
        }
    }

    /** Hit with a stick: out cold. Another stick hit while it sleeps starts the timer over. */
    private void knockOut(ServerLevel server, LivingEntity hitter) {
        boolean alreadyOut = isKnockedOut();
        setDisguised(false);
        setKnockedOut(true);
        this.knockoutTicks = KNOCKOUT_TICKS;
        this.holdTicks = 0;
        setTarget(null);
        setAggressive(false);
        this.getNavigation().stop();

        sfx(server, ModSounds.MUSHROOM_BONK, 1.0F, 1.0F);
        // A ring of dizzy stars around its head.
        for (int i = 0; i < 8; i++) {
            double angle = Math.PI * 2.0D * i / 8.0D;
            server.sendParticles(ParticleTypes.CRIT, getX() + Math.cos(angle) * 0.3D, getY() + 1.0D,
                    getZ() + Math.sin(angle) * 0.3D, 0, Math.cos(angle), 0.4D, Math.sin(angle), 0.2D);
        }
        if (!alreadyOut) {
            spores(server, 10, 0.2D);
            alertPack(hitter);
        }
    }

    /** Wakes up (the timer ran out, or something hurt it) with a yawn and a little hop. */
    private void wakeUp(ServerLevel server, boolean hurt) {
        setKnockedOut(false);
        this.knockoutTicks = 0;
        this.holdTicks = WAKE_HOLD;
        triggerAnim("action", "wake_up");
        sfx(server, ModSounds.MUSHROOM_YAWN, 0.8F, 1.0F);
        server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.2D, getZ(), 4, 0.15D, 0.05D, 0.15D, 0.01D);
        if (!hurt) {
            server.sendParticles(ParticleTypes.NOTE, getX(), getY() + 1.3D, getZ(), 1, 0.1D, 0.1D, 0.1D, 1.0D);
        }
    }

    /** Trips over its own feet while running: a face-plant, then it hops back up. */
    private void trip(ServerLevel server) {
        this.holdTicks = TRIP_TICKS;
        this.tripCooldown = TRIP_COOLDOWN;
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
        triggerAnim("action", "trip");
        sfx(server, ModSounds.MUSHROOM_TRIP, 1.0F, 1.0F);
        server.sendParticles(ParticleTypes.POOF, getX(), getY() + 0.1D, getZ(), 8, 0.25D, 0.02D, 0.25D, 0.03D);
        server.sendParticles(ParticleTypes.CRIT, getX(), getY() + 0.5D, getZ(), 6, 0.3D, 0.3D, 0.3D, 0.1D);
    }

    /** Everyone close by hears it: sleepers stay asleep, the hidden pop up, the rest go for the player. */
    private void alertPack(LivingEntity cause) {
        if (this.alarmCooldown > 0 || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.alarmCooldown = 40;
        sfx(server, ModSounds.MUSHROOM_ALARM, 1.0F, 1.0F);
        server.sendParticles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + 1.2D, getZ(), 2, 0.2D, 0.1D, 0.2D, 0.0D);
        for (WalkingMushroomEntity other : server.getEntitiesOfClass(WalkingMushroomEntity.class,
                getBoundingBox().inflate(PACK_RANGE, 4.0D, PACK_RANGE), m -> m != this && m.isAlive())) {
            other.hearAlarm(cause);
        }
    }

    private void hearAlarm(LivingEntity cause) {
        if (isKnockedOut() || !(this.level() instanceof ServerLevel server)) {
            return;
        }
        this.alarmCooldown = 40;
        if (isDisguised()) {
            popUp(cause, false);
            return;
        }
        if (isChaseable(cause) && getTarget() == null) {
            setTarget(cause);
        }
        if (this.holdTicks == 0) {
            triggerAnim("action", "startle");
        }
        server.sendParticles(ParticleTypes.ANGRY_VILLAGER, getX(), getY() + 1.2D, getZ(), 1, 0.15D, 0.1D, 0.15D, 0.0D);
    }

    // ---------------------------------------------------------------- snacks (see MushroomSnackGoal)

    /** Dropped mushrooms, fungi and bone meal. */
    public static boolean isSnack(ItemStack stack) {
        return stack.is(Items.RED_MUSHROOM) || stack.is(Items.BROWN_MUSHROOM) || stack.is(Items.BONE_MEAL)
                || stack.is(Items.CRIMSON_FUNGUS) || stack.is(Items.WARPED_FUNGUS);
    }

    /** A bite: crumbs fly and it munches. */
    public void chew(ItemStack stack) {
        if (this.level() instanceof ServerLevel server) {
            Vec3 forward = Vec3.directionFromRotation(0.0F, this.getYRot()).scale(0.2D);
            server.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, stack),
                    getX() + forward.x, getY() + 0.6D, getZ() + forward.z, 4, 0.08D, 0.08D, 0.08D, 0.03D);
            Fx.soundVaried(server, getX(), getY() + 0.5D, getZ(), SoundEvents.GENERIC_EAT, 0.6F, 1.6F);
        }
    }

    /** Finished eating: a little heal, hearts, a happy trill and two hops. */
    public void finishSnack() {
        this.snackCooldown = SNACK_COOLDOWN;
        this.holdTicks = HAPPY_HOLD;
        this.heal(2.0F);
        triggerAnim("action", "happy_hop");
        if (this.level() instanceof ServerLevel server) {
            sfx(server, ModSounds.MUSHROOM_HAPPY, 1.0F, 1.0F);
            server.sendParticles(ParticleTypes.HEART, getX(), getY() + 1.2D, getZ(), 4, 0.25D, 0.15D, 0.25D, 0.0D);
            server.sendParticles(ParticleTypes.NOTE, getX(), getY() + 1.3D, getZ(), 2, 0.3D, 0.1D, 0.3D, 1.0D);
        }
    }

    // ---------------------------------------------------------------- headbutt (see MushroomBonkGoal)

    /** Close enough to headbutt? (The mushroom is small, so this looks at the gap between the hitboxes.) */
    public boolean inBonkRange(LivingEntity target, double slack) {
        return this.getBoundingBox().inflate(0.45D + slack, 0.3D, 0.45D + slack).intersects(target.getBoundingBox());
    }

    /** Called when the headbutt animation starts. */
    public void attackStartEffects() {
        triggerAnim("action", "attack");
        if (this.level() instanceof ServerLevel server) {
            sfx(server, ModSounds.MUSHROOM_ATTACK, 0.9F, 1.0F);
        }
    }

    /** The headbutt lands: a half-heart of damage and a puff of spores. */
    public void bonk(LivingEntity target) {
        if (doHurtTarget(target) && this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.CRIMSON_SPORE, target.getX(), target.getY() + target.getBbHeight() * 0.6D,
                    target.getZ(), 8, 0.2D, 0.2D, 0.2D, 0.03D);
        }
    }

    /** Dizzy stars over its head after a headbutt (called every few ticks by the goal). */
    public void dizzyFx() {
        if (this.level() instanceof ServerLevel server) {
            double angle = this.tickCount * 0.6D;
            server.sendParticles(ParticleTypes.CRIT, getX() + Math.cos(angle) * 0.3D, getY() + 1.15D,
                    getZ() + Math.sin(angle) * 0.3D, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }

    // ---------------------------------------------------------------- getting hit

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel server)
                || !this.isAlive() || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }

        Entity attacker = source.getEntity();
        if (attacker == null) {
            // Fire, lava, falling, drowning, cactus...: ordinary damage. It wakes up and pops up if it was out of action.
            boolean hurt = super.hurt(source, amount);
            if (hurt) {
                if (isDisguised()) {
                    popUp(null, true);
                }
                if (isKnockedOut()) {
                    wakeUp(server, true);
                }
            }
            return hurt;
        }

        boolean melee = attacker instanceof LivingEntity && source.getDirectEntity() == attacker;
        if (melee) {
            LivingEntity hitter = (LivingEntity) attacker;
            ItemStack weapon = hitter.getMainHandItem();

            // A stick bonk: knocked out cold, no damage.
            if (hitter instanceof Player && weapon.is(Items.STICK)) {
                knockOut(server, hitter);
                return false;
            }
            // The one cut that works: a blade, coming down from above.
            if (isBlade(weapon) && isOverheadChop(hitter)) {
                slice(server, source, hitter);
                return true;
            }
            return bounce(server, source, hitter, isBlade(weapon));
        }
        // Arrows, tridents, fireballs, explosions, potions...: boing.
        return bounce(server, source, attacker, false);
    }

    /** A hit that is not a vertical slice: half a heart at most, a squeaky hop, and everybody nearby is alarmed. */
    private boolean bounce(ServerLevel server, DamageSource source, Entity attacker, boolean wieldingBlade) {
        if (isDisguised()) {
            popUp(attacker instanceof LivingEntity living ? living : null, true);
        }
        // It never dies from these: it stops losing health at half a heart.
        float damage = Math.max(0.0F, Math.min(WRONG_HIT_DAMAGE, getHealth() - 1.0F));
        boolean hurt = super.hurt(source, damage);
        if (hurt) {
            Vec3 motion = getDeltaMovement();
            this.setDeltaMovement(motion.x, Math.max(motion.y, 0.4D), motion.z);
            this.hasImpulse = true;
            spores(server, 10, 0.2D);
            if (isKnockedOut()) {
                wakeUp(server, true);
            }
            if (wieldingBlade && attacker instanceof Player player && this.hintCooldown == 0) {
                this.hintCooldown = 100;
                player.displayClientMessage(Component.translatable("message.meshi.walking_mushroom.slice_hint"), true);
            }
            if (attacker instanceof LivingEntity living) {
                alertPack(living);
            }
        }
        return hurt;
    }

    /** The vertical slice: it is cut clean in two. */
    private void slice(ServerLevel server, DamageSource source, LivingEntity hitter) {
        alertPack(hitter);
        double x = getX();
        double y = getY();
        double z = getZ();
        // A bright line down the middle...
        for (int i = 0; i < 12; i++) {
            server.sendParticles(ParticleTypes.CRIT, x, y + i * 0.1D, z, 1, 0.02D, 0.0D, 0.02D, 0.0D);
        }
        // ...and the two halves of spores fly apart sideways (count 0 = the offsets are a direction).
        Vec3 side = Vec3.directionFromRotation(0.0F, this.getYRot() + 90.0F);
        for (int i = 0; i < 10; i++) {
            server.sendParticles(ParticleTypes.CRIMSON_SPORE, x + side.x * 0.1D, y + 0.4D + i * 0.07D, z + side.z * 0.1D,
                    0, side.x, 0.1D, side.z, 0.2D);
            server.sendParticles(ParticleTypes.CRIMSON_SPORE, x - side.x * 0.1D, y + 0.4D + i * 0.07D, z - side.z * 0.1D,
                    0, -side.x, 0.1D, -side.z, 0.2D);
        }
        server.sendParticles(ParticleTypes.CLOUD, x, y + 0.5D, z, 6, 0.2D, 0.3D, 0.2D, 0.03D);
        Fx.sound(server, x, y + 0.5D, z, SoundEvents.PLAYER_ATTACK_CRIT, 0.8F, 1.3F);
        super.hurt(source, 1000.0F);
    }

    private static boolean isBlade(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem) {
            return true;
        }
        for (TagKey<Item> tag : BLADE_TAGS) {
            if (stack.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * "Came down from above": the same test the game uses for a critical hit (falling, not on the ground,
     * not climbing, not swimming, not sprinting). Sweeping attacks can never be criticals.
     */
    private static boolean isOverheadChop(LivingEntity attacker) {
        return attacker.fallDistance > 0.0F && !attacker.onGround() && !attacker.onClimbable() && !attacker.isInWater()
                && !attacker.hasEffect(MobEffects.BLINDNESS) && !attacker.isPassenger() && !attacker.isSprinting();
    }

    // ---------------------------------------------------------------- save / load

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Disguised", isDisguised());
        tag.putInt("KnockoutTicks", isKnockedOut() ? this.knockoutTicks : 0);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.knockoutTicks = tag.getInt("KnockoutTicks");
        setKnockedOut(this.knockoutTicks > 0);
        setDisguised(tag.getBoolean("Disguised") && this.knockoutTicks <= 0);
    }

    // ---------------------------------------------------------------- sounds and client particles

    @Override
    protected SoundEvent getAmbientSound() {
        return isBusy() ? null : ModSounds.MUSHROOM_SQUEAK.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.MUSHROOM_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.MUSHROOM_SLICE.get();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(ModSounds.MUSHROOM_STEP.get(), 0.4F, 1.0F);
    }

    /** Little puffs of mycelium dust under its feet while it walks (client side, only the people looking see it). */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide && !isKnockedOut() && !isDisguised() && this.onGround()
                && this.getDeltaMovement().horizontalDistanceSqr() > 0.0009D && this.tickCount % 4 == 0) {
            this.level().addParticle(ParticleTypes.MYCELIUM,
                    getX() + (this.random.nextDouble() - 0.5D) * 0.3D, getY() + 0.05D,
                    getZ() + (this.random.nextDouble() - 0.5D) * 0.3D, 0.0D, 0.01D, 0.0D);
        }
    }

    // ---------------------------------------------------------------- animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Body: walk / idle, or hidden / asleep.
        controllers.add(new AnimationController<>(this, "main", 5, this::mainPredicate));
        // One-shots (headbutt, pop up, wake up, trip, happy hop, startle) play on top, triggered by name.
        controllers.add(new AnimationController<>(this, "action", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", ATTACK)
                .triggerableAnim("pop_up", POP_UP)
                .triggerableAnim("wake_up", WAKE_UP)
                .triggerableAnim("trip", TRIP)
                .triggerableAnim("happy_hop", HAPPY_HOP)
                .triggerableAnim("startle", STARTLE));
    }

    private PlayState mainPredicate(AnimationState<WalkingMushroomEntity> state) {
        if (isKnockedOut()) {
            return state.setAndContinue(SLEEP);
        }
        if (isDisguised()) {
            return state.setAndContinue(HIDDEN);
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
