package com.meshi.entity;

import com.meshi.util.Fx;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.EnumSet;
import javax.annotation.Nullable;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

// mimic. sits as a normal looking chest (idle_hidden): cant move, cant be hurt, arrows just thunk off it (MimicEvents)
// right click it = popup anim (rattle, lid bursts open) then it chases whoever opened it, never gives up
// bite hits hard n chews up all 4 armor pieces on top of the normal armor wear
// vanilla sounds + particles only
// test: /summon meshi:mimic ~ ~ ~   (survival, creative players dont get chased)   or the spawn egg
public class MimicEntity extends Monster implements GeoEntity {
    // ---- tweak these
    // dmg per bite (10 = 5 hearts before armor)
    public static final double BITE_DAMAGE = 10.0D;
    // extra durability every worn armor piece loses per bite (on top of vanilla wear)
    public static final int ARMOR_WEAR = 12;
    public static final double MAX_HEALTH = 50.0D;
    public static final double MOVE_SPEED = 0.29D;
    // goal speed multiplier while chasing
    public static final double CHASE_SPEED = 1.1D;

    // popup anim is 1.125s
    private static final int POP_TICKS = 23;
    private static final int STATE_HIDDEN = 0;
    private static final int STATE_POPPING = 1;
    private static final int STATE_AWAKE = 2;

    private static final EquipmentSlot[] ARMOR_SLOTS =
            {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private static final EntityDataAccessor<Integer> STATE =
            SynchedEntityData.defineId(MimicEntity.class, EntityDataSerializers.INT);

    private static final RawAnimation HIDDEN = RawAnimation.begin().thenLoop("idle_hidden");
    private static final RawAnimation POPUP = RawAnimation.begin().thenPlay("popup");
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation CRAWL = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation BITE = RawAnimation.begin().thenPlay("attack1");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int popTicks = 0;
    private UUID opener = null;
    private int chompTimer = 0;

    public MimicEntity(EntityType<? extends MimicEntity> type, Level level) {
        super(type, level);
        this.xpReward = 20;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, MOVE_SPEED)
                .add(Attributes.ATTACK_DAMAGE, BITE_DAMAGE)
                .add(Attributes.ATTACK_KNOCKBACK, 0.5D)
                .add(Attributes.ARMOR, 6.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5D)
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    protected void registerGoals() {
        // hidden or popping = does nothing at all
        this.goalSelector.addGoal(0, new Goal() {
            {
                this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
            }

            @Override
            public boolean canUse() {
                return !MimicEntity.this.isAwake();
            }

            @Override
            public boolean canContinueToUse() {
                return canUse();
            }
        });
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new MimicBiteGoal(this, CHASE_SPEED));
        this.goalSelector.addGoal(3, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        this.goalSelector.addGoal(4, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(5, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        // mustSee = false, once it has u it doesnt need line of sight (relentless)
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, false, false, null) {
            @Override
            public boolean canUse() {
                return MimicEntity.this.isAwake() && super.canUse();
            }
        });
    }

    // ---- state

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(STATE, STATE_HIDDEN);
    }

    private int getState() {
        return this.entityData.get(STATE);
    }

    public boolean isHidden() {
        return getState() == STATE_HIDDEN;
    }

    public boolean isPopping() {
        return getState() == STATE_POPPING;
    }

    public boolean isAwake() {
        return getState() == STATE_AWAKE;
    }

    // starts the popup. only works while hidden
    public void wake(@Nullable LivingEntity by) {
        if (!isHidden() || !(this.level() instanceof ServerLevel)) {
            return;
        }
        this.entityData.set(STATE, STATE_POPPING);
        this.popTicks = POP_TICKS;
        this.opener = by == null ? null : by.getUUID();
        this.getNavigation().stop();
    }

    // right click = tries to open it
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (isHidden()) {
            if (!this.level().isClientSide && hand == InteractionHand.MAIN_HAND) {
                wake(player);
            }
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        return super.mobInteract(player, hand);
    }

    // ---- chest behaviour while hidden

    // solid like a chest, cant be shoved around
    @Override
    public boolean canBeCollidedWith() {
        return isHidden() || super.canBeCollidedWith();
    }

    @Override
    public boolean isPushable() {
        return !isHidden() && !isPopping() && super.isPushable();
    }

    @Override
    public boolean isPushedByFluid() {
        return isAwake() && super.isPushedByFluid();
    }

    // hidden ones stay put even on peaceful / far from players
    @Override
    public boolean removeWhenFarAway(double distance) {
        return isAwake();
    }

    @Override
    public boolean shouldDespawnInPeaceful() {
        return isAwake();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel server)) {
            return;
        }

        if (isHidden() || isPopping()) {
            // nothing hurts it til its out (creative players / /kill still can, that bypasses invulnerable)
            if (!this.isInvulnerable()) {
                this.setInvulnerable(true);
            }
            this.getNavigation().stop();
            Vec3 motion = getDeltaMovement();
            this.setDeltaMovement(0.0D, Math.min(motion.y, 0.0D), 0.0D);
        }

        if (isHidden()) {
            snapRotation();
        } else if (isPopping()) {
            tickPopup(server);
        } else {
            tickAwake(server);
        }
    }

    // chest faces one of the 4 directions, like a real one
    private void snapRotation() {
        float snapped = Math.round(this.getYRot() / 90.0F) * 90.0F;
        if (this.getYRot() != snapped || this.yBodyRot != snapped || this.yHeadRot != snapped) {
            this.setYRot(snapped);
            this.setYBodyRot(snapped);
            this.setYHeadRot(snapped);
            this.yRotO = snapped;
            this.yBodyRotO = snapped;
            this.yHeadRotO = snapped;
        }
    }

    // ---- popup

    private static ParticleOptions planks() {
        return new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState());
    }

    // popup anim: rattles til 0.58s, lid bursts open at ~0.83s (tick 17), mimic is up at 0.92s
    private void tickPopup(ServerLevel server) {
        int e = POP_TICKS - this.popTicks;
        double x = getX();
        double y = getY();
        double z = getZ();

        if (e == 1 || e == 8) {
            Fx.sound(server, x, y, z, SoundEvents.CHEST_LOCKED, 1.1F, e == 1 ? 0.7F : 0.5F);
            Fx.sound(server, x, y, z, SoundEvents.WOOD_HIT, 1.0F, 0.6F);
            server.sendParticles(planks(), x, y + 0.8D, z, 10, 0.35D, 0.1D, 0.35D, 0.04D);
        }
        if (e >= 2 && e < 17 && e % 2 == 0) {
            server.sendParticles(ParticleTypes.SMOKE, x, y + 0.85D, z, 2, 0.3D, 0.05D, 0.3D, 0.01D);
        }
        if (e == 12) {
            Fx.sound(server, x, y, z, SoundEvents.SPIDER_AMBIENT, 1.0F, 0.5F);
            Fx.sound(server, x, y, z, SoundEvents.CHEST_LOCKED, 1.3F, 0.4F);
            server.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 0.9D, z, 6, 0.3D, 0.1D, 0.3D, 0.02D);
        }
        if (e == 17) {
            burstEffects(server);
        }

        this.popTicks--;
        if (this.popTicks <= 0) {
            finishPopup();
        }
    }

    // lid blows open
    private void burstEffects(ServerLevel server) {
        double x = getX();
        double y = getY();
        double z = getZ();
        Fx.sound(server, x, y, z, SoundEvents.CHEST_OPEN, 1.5F, 0.5F);
        Fx.sound(server, x, y, z, SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR, 1.3F, 0.7F);
        Fx.sound(server, x, y, z, SoundEvents.RAVAGER_ROAR, 1.4F, 1.0F);
        server.sendParticles(planks(), x, y + 0.9D, z, 70, 0.5D, 0.4D, 0.5D, 0.15D);
        Fx.shockwave(server, planks(), x, y + 0.1D, z, 2.5D);
        Fx.dust(server, x, y + 0.6D, z, 16, 0.7D);
        server.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.0D, z, 14, 0.4D, 0.3D, 0.4D, 0.04D);
        server.sendParticles(ParticleTypes.SOUL, x, y + 0.9D, z, 14, 0.4D, 0.4D, 0.4D, 0.04D);
        server.sendParticles(ParticleTypes.ANGRY_VILLAGER, x, y + 1.2D, z, 5, 0.4D, 0.3D, 0.4D, 0.0D);
    }

    private void finishPopup() {
        this.entityData.set(STATE, STATE_AWAKE);
        this.popTicks = 0;
        this.setInvulnerable(false);
        if (this.opener != null) {
            Player player = this.level().getPlayerByUUID(this.opener);
            if (player != null && player.isAlive() && this.canAttack(player)) {
                this.setTarget(player);
            }
        }
        this.opener = null;
    }

    // ---- awake fx

    private void tickAwake(ServerLevel server) {
        double x = getX();
        double y = getY();
        double z = getZ();

        // splinters shaking off the lid
        if (this.tickCount % 10 == 0) {
            server.sendParticles(planks(), x, y + 1.0D, z, 3, 0.3D, 0.1D, 0.3D, 0.02D);
        }
        // dust kicked up while running
        Vec3 motion = getDeltaMovement();
        if (this.tickCount % 3 == 0 && motion.x * motion.x + motion.z * motion.z > 0.003D) {
            Fx.dust(server, x, y + 0.1D, z, 2, 0.35D);
        }
        // lid snapping at the target while it closes in
        LivingEntity target = getTarget();
        if (target != null && this.distanceToSqr(target) < 100.0D) {
            if (--this.chompTimer <= 0) {
                this.chompTimer = 22 + this.random.nextInt(8);
                Fx.soundVaried(server, x, y, z, SoundEvents.CHEST_CLOSE, 0.9F, 0.6F);
            }
        }
    }

    // ---- combat

    // gap between hitboxes, not centers, so it works with the big model
    public boolean inBiteRange(LivingEntity target, double slack) {
        return this.getBoundingBox().inflate(0.6D + slack, 0.3D, 0.6D + slack).intersects(target.getBoundingBox());
    }

    @Override
    public boolean doHurtTarget(Entity target) {
        boolean hit = super.doHurtTarget(target);
        if (hit && target instanceof LivingEntity victim && this.level() instanceof ServerLevel server) {
            boolean worn = chewArmor(victim);
            double vy = victim.getY() + victim.getBbHeight() * 0.6D;
            server.sendParticles(ParticleTypes.CRIT, victim.getX(), vy, victim.getZ(), 18, 0.35D, 0.3D, 0.35D, 0.3D);
            server.sendParticles(ParticleTypes.DAMAGE_INDICATOR, victim.getX(), vy, victim.getZ(), 5, 0.3D, 0.2D, 0.3D, 0.1D);
            server.sendParticles(planks(), victim.getX(), vy, victim.getZ(), 12, 0.3D, 0.3D, 0.3D, 0.08D);
            Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.PLAYER_ATTACK_CRIT, 1.0F, 0.7F);
            if (worn) {
                server.sendParticles(ParticleTypes.ELECTRIC_SPARK, victim.getX(), vy, victim.getZ(), 14, 0.35D, 0.4D, 0.35D, 0.2D);
                Fx.sound(server, victim.getX(), victim.getY(), victim.getZ(), SoundEvents.ANVIL_LAND, 0.5F, 1.5F);
            }
        }
        return hit;
    }

    // extra durability off every armor piece, returns true if anything was worn
    private boolean chewArmor(LivingEntity victim) {
        boolean any = false;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack stack = victim.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.isDamageableItem()) {
                stack.hurtAndBreak(ARMOR_WEAR, victim, slot);
                any = true;
            }
        }
        return any;
    }

    // goal calls this when the bite starts (lid opens wide) and when it snaps shut
    public void biteStartEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 0.5D, getZ(), SoundEvents.RAVAGER_ATTACK, 0.9F, 1.1F);
        }
    }

    public void biteSnapEffects() {
        if (this.level() instanceof ServerLevel server) {
            Fx.sound(server, getX(), getY() + 0.5D, getZ(), SoundEvents.CHEST_CLOSE, 1.3F, 0.5F);
        }
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        if (this.level() instanceof ServerLevel server) {
            double x = getX();
            double y = getY();
            double z = getZ();
            server.sendParticles(planks(), x, y + 0.8D, z, 90, 0.6D, 0.5D, 0.6D, 0.15D);
            Fx.dust(server, x, y + 0.6D, z, 14, 0.7D);
            server.sendParticles(ParticleTypes.SOUL, x, y + 0.9D, z, 10, 0.4D, 0.4D, 0.4D, 0.05D);
            Fx.sound(server, x, y, z, SoundEvents.WOOD_BREAK, 1.4F, 0.7F);
            Fx.sound(server, x, y, z, SoundEvents.CHEST_CLOSE, 1.2F, 0.6F);
        }
    }

    // ---- sounds

    @Override
    @Nullable
    protected SoundEvent getAmbientSound() {
        return isAwake() ? SoundEvents.RAVAGER_AMBIENT : null;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.RAVAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.RAVAGER_DEATH;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(SoundEvents.WOOD_STEP, 0.6F, 0.7F);
        this.playSound(SoundEvents.SPIDER_STEP, 0.4F, 0.9F);
    }

    // ---- save

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("MimicState", getState());
        tag.putInt("PopTicks", this.popTicks);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        int state = tag.getInt("MimicState");
        this.entityData.set(STATE, state);
        this.popTicks = tag.getInt("PopTicks");
        if (state == STATE_POPPING && this.popTicks <= 0) {
            this.popTicks = POP_TICKS;
        }
        this.setInvulnerable(state != STATE_AWAKE);
    }

    // ---- animation

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 3, this::mainPredicate));
        // bite plays over crawl / idle
        controllers.add(new AnimationController<>(this, "attack", 0, state -> PlayState.STOP)
                .triggerableAnim("attack", BITE));
    }

    private PlayState mainPredicate(AnimationState<MimicEntity> state) {
        if (isHidden()) {
            return state.setAndContinue(HIDDEN);
        }
        if (isPopping()) {
            return state.setAndContinue(POPUP);
        }
        return state.setAndContinue(state.isMoving() ? CRAWL : IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
