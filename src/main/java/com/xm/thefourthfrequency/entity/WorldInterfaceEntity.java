package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.ending.EndBossEncounterService;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Persistent visual and collision root for the final encounter.
 *
 * <p>The entity's vanilla health is deliberately not authoritative. Minecraft's
 * max-health attribute is capped below the encounter's eight-player 4800 HP
 * requirement, so every accepted hit is routed to the saved virtual pool.</p>
 */
public final class WorldInterfaceEntity extends Monster {
	public static final int FORM_LISTENING = 0;
	public static final int FORM_CONSUMING = 1;
	public static final int FORM_INTERFACE = 2;
	private static final EntityDataAccessor<Integer> FORM = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> ACTION = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Long> ACTION_START_TICK = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Integer> ACTION_DURATION = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.INT);
	/**
	 * The server's own tick count, so both sides pose the rig on one clock.
	 *
	 * <p>Everything else {@code WorldInterfaceRig.pose} reads - form, health, action id, action age,
	 * gaze - is synchronised. The tick count was not, and an entity's {@code tickCount} starts when it
	 * enters <em>that side's</em> world: the client begins counting when the body comes into tracking
	 * range, the server when it was summoned. Measured in a client game test the two were five ticks
	 * apart, which posed the same skeleton at two different points in its hover drift and left the
	 * drawn head 0.46 blocks from the box a swing tests - 0.446 of it along z, which is the axis the
	 * player is swinging down. That is the "I can see the head and I swing through it" report.
	 *
	 * <p>Sent every {@link #POSE_SYNC_INTERVAL_TICKS} rather than every tick. Both clocks advance at
	 * the same rate, so one sample is enough to fix the offset and the rest only has to correct drift;
	 * a boss with eight people watching does not need a packet per tick to say "it is now later".
	 */
	private static final EntityDataAccessor<Long> POSE_TICK = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.LONG);
	/** How often the pose clock is re-sent. One second. */
	private static final int POSE_SYNC_INTERVAL_TICKS = 20;
	/**
	 * How much of the virtual pool is left, 1 down to 0.
	 *
	 * <p>Synchronised rather than looked up per side. The structural sag in {@code WorldInterfaceRig}
	 * is driven by it - by the end of a fight it is putting a third of a radian into every neck - so
	 * the client reading it off the HUD snapshot while the server read it off the saved state would
	 * be two different poses, which is precisely what binding the boxes to the bones is meant to
	 * stop. The renderer reads this too, so the drawn wear and the hittable wear are one number.
	 */
	private static final EntityDataAccessor<Float> HEALTH_FRACTION = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.FLOAT);
	/**
	 * Where the three heads are looking, in radians, relative to the body's own facing.
	 *
	 * <p>Synchronised for the same reason {@link #HEALTH_FRACTION} is: {@code WorldInterfaceRig} turns
	 * it into a pose, the server stands the head hit boxes on that pose and the client draws the same
	 * one, so both sides have to be answering with the same number. Resolving it independently -
	 * the server against its chosen target, the client against whoever is nearest the camera - would
	 * be two different poses, and a head drawn somewhere other than where it can be hit is exactly
	 * what binding the boxes to the bones exists to prevent.
	 *
	 * <p>Relative rather than absolute so it survives the body turning underneath it: the yaw the
	 * heads add is on top of {@code yBodyRot}, which is already interpolated on the client.
	 */
	private static final EntityDataAccessor<Float> GAZE_YAW = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> GAZE_PITCH = SynchedEntityData.defineId(
			WorldInterfaceEntity.class, EntityDataSerializers.FLOAT);
	/**
	 * Radians of change worth a tracker update.
	 *
	 * <p>Half a degree. The gaze is recomputed every tick and a raw float assignment would mark the
	 * entity dirty every one of them, for a rotation nobody can see move.
	 */
	private static final float GAZE_EPSILON = 0.009F;

	private UUID encounterId;
	/** Client-side: server tick minus local tick, latched from {@link #POSE_TICK}. */
	private int poseTickOffset;
	private WorldInterfaceRig.Pose cachedPose;
	private long cachedPoseTick = Long.MIN_VALUE;
	/** Last tick's gaze, for the renderer to interpolate from. See {@link #renderGazeYaw}. */
	private float gazeYawO;
	private float gazePitchO;
	public final AnimationState idleAnimationState = new AnimationState();
	public final AnimationState actionAnimationState = new AnimationState();
	private int animatedAction = Integer.MIN_VALUE;

	public WorldInterfaceEntity(EntityType<? extends WorldInterfaceEntity> type, Level level) {
		super(type, level);
		xpReward = 0;
		setNoGravity(true);
		// The box is seven to sixteen blocks across and now rides low enough over a hunted player
		// for the island's own relief to reach into it. The encounter service owns the position
		// outright, so terrain collision can only fight it: a storm does not get stopped by a hill.
		noPhysics = true;
		setPersistenceRequired();
	}

	/**
	 * Without these the boss took 1024 points of damage in total silence, so a landed hit was
	 * indistinguishable from a missed one at any range where the health bar is the only feedback.
	 */
	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return ModSounds.WORLD_INTERFACE_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return ModSounds.WORLD_INTERFACE_DEATH;
	}

	@Override
	protected float getSoundVolume() {
		return 1.0F;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 1024.0)
				.add(Attributes.MOVEMENT_SPEED, 0.55)
				.add(Attributes.FOLLOW_RANGE, 256.0)
				.add(Attributes.ATTACK_DAMAGE, 12.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.FLYING_SPEED, 0.55);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FORM, FORM_LISTENING)
				.define(ACTION, 0)
				.define(ACTION_START_TICK, 0L)
				.define(ACTION_DURATION, 0)
				.define(HEALTH_FRACTION, 1.0F)
				.define(GAZE_YAW, 0.0F)
				.define(GAZE_PITCH, 0.0F)
				.define(POSE_TICK, 0L);
	}

	@Override
	protected void registerGoals() {
		// The encounter service owns movement and attacks deterministically.
	}

	@Override
	public void tick() {
		// Captured before the tick, the way vanilla captures yRotO: on the client the synchronised
		// gaze is rewritten by packets between ticks, so this pair is "where the heads were looking"
		// and "where they are looking now", which is exactly what the renderer interpolates across.
		gazeYawO = gazeYaw();
		gazePitchO = gazePitch();
		super.tick();
		if (!level().isClientSide() && tickCount % POSE_SYNC_INTERVAL_TICKS == 0) {
			entityData.set(POSE_TICK, (long) tickCount);
		}
		idleAnimationState.startIfStopped(tickCount);
		int currentAction = actionId();
		if (currentAction != animatedAction) {
			actionAnimationState.stop();
			if (currentAction != 0) actionAnimationState.start(tickCount);
			animatedAction = currentAction;
		}
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		setNoGravity(true);
		noPhysics = true;
		fallDistance = 0.0F;
		EndBossEncounterService.tickBossEntity(level, this);
	}

	public void bindEncounter(UUID id) {
		encounterId = id;
	}

	public UUID encounterId() {
		return encounterId;
	}

	public int form() {
		return entityData.get(FORM);
	}

	public void setForm(int form) {
		int clamped = Math.clamp(form, FORM_LISTENING, FORM_INTERFACE);
		if (clamped == form()) return;
		entityData.set(FORM, clamped);
		cachedPoseTick = Long.MIN_VALUE;
		refreshDimensions();
	}

	public int actionId() {
		return entityData.get(ACTION);
	}

	public long actionStartTick() {
		return entityData.get(ACTION_START_TICK);
	}

	public int actionDuration() {
		return entityData.get(ACTION_DURATION);
	}

	public void showAction(int actionId, long startTick, int duration) {
		cachedPoseTick = Long.MIN_VALUE;
		entityData.set(ACTION, Math.max(0, actionId));
		entityData.set(ACTION_START_TICK, Math.max(0L, startTick));
		entityData.set(ACTION_DURATION, Math.max(0, duration));
	}

	public void clearAction() {
		showAction(0, 0L, 0);
	}

	public float healthFraction() {
		return Math.clamp(entityData.get(HEALTH_FRACTION), 0.0F, 1.0F);
	}

	/** Server-only: publish the pool fraction the pose and the presentation both read. */
	public void setHealthFraction(float fraction) {
		float clamped = Math.clamp(fraction, 0.0F, 1.0F);
		if (Math.abs(clamped - entityData.get(HEALTH_FRACTION)) < 1.0E-4F) return;
		entityData.set(HEALTH_FRACTION, clamped);
	}

	public float gazeYaw() {
		return entityData.get(GAZE_YAW);
	}

	public float gazePitch() {
		return entityData.get(GAZE_PITCH);
	}

	/**
	 * The gaze as the renderer should draw it, interpolated across the frame.
	 *
	 * <p><b>Render-only, and the reason it has to exist.</b> The gaze is a synchronised value, so it
	 * changes in whole-tick steps - at the rate the server turns the heads that is nearly six degrees
	 * at a time, applied to a skull seven blocks across. Drawn straight, the head sat still for three
	 * frames and then jumped, which reads as the head blinking from one place to another rather than
	 * as it turning. Interpolating between the last two values is the same treatment vanilla gives
	 * every rotation it synchronises, for the same reason.
	 *
	 * <p>The hit boxes deliberately do <em>not</em> use this: {@link #rigPose()} poses from the raw
	 * value on both sides, so the box a player swings at and the box the server tests are still one
	 * pose. What the renderer adds is up to one tick of lead on where that pose is heading, and since
	 * almost all of the look-at is rotation about the chain's own axis, the positional difference it
	 * can introduce is a fraction of the slack the head box already carries.
	 */
	public float renderGazeYaw(float partialTick) {
		return Mth.lerp(partialTick, gazeYawO, gazeYaw());
	}

	public float renderGazePitch(float partialTick) {
		return Mth.lerp(partialTick, gazePitchO, gazePitch());
	}

	/**
	 * Server-only: publish where the heads are looking, relative to the body's facing.
	 *
	 * <p>Eased rather than assigned. The chosen target changes the moment somebody else becomes the
	 * nearest participant, and a raw assignment snapped three heads across the arena on that tick;
	 * approaching the wanted bearing at a fixed rate makes a change of mind read as the storm looking
	 * away from one player and over at another.
	 */
	public void setGaze(float yawRadians, float pitchRadians) {
		if (!Float.isFinite(yawRadians) || !Float.isFinite(pitchRadians)) return;
		float yaw = approachAngle(gazeYaw(), yawRadians);
		float pitch = approachAngle(gazePitch(), pitchRadians);
		if (Math.abs(yaw - gazeYaw()) >= GAZE_EPSILON) entityData.set(GAZE_YAW, yaw);
		if (Math.abs(pitch - gazePitch()) >= GAZE_EPSILON) entityData.set(GAZE_PITCH, pitch);
	}

	/**
	 * Radians per tick the gaze may travel: about nine degrees.
	 *
	 * <p>Faster than the body, which turns at 2.2 degrees a tick, because that is the whole division
	 * of labour - the storm comes round slowly and the heads lead it. Slow enough that a change of
	 * target still reads as looking away from one player and over at another rather than as a cut.
	 */
	private static final float GAZE_TURN_PER_TICK = 0.16F;

	private static float approachAngle(float current, float wanted) {
		float delta = wanted - current;
		if (Math.abs(delta) <= GAZE_TURN_PER_TICK) return wanted;
		return current + Math.copySign(GAZE_TURN_PER_TICK, delta);
	}

	/** Milliseconds into the published action, as both sides measure it. */
	public long actionAgeMillis() {
		return (long) (Math.max(0L, level().getGameTime() - actionStartTick()) * 50L);
	}

	/**
	 * The posed skeleton for this tick, computed once and shared by every hit proxy.
	 *
	 * <p>Twenty proxies asking the rig independently would pose the whole storm twenty times a tick
	 * for an identical answer. This is also what guarantees they agree with each other: one pose, one
	 * clock, one set of boxes.
	 */
	public WorldInterfaceRig.Pose rigPose() {
		if (cachedPose == null || cachedPoseTick != tickCount) {
			cachedPose = WorldInterfaceRig.pose(form(), poseClock(), healthFraction(), actionId(),
					actionAgeMillis(), gazeYaw(), gazePitch());
			cachedPoseTick = tickCount;
		}
		return cachedPose;
	}

	/**
	 * The tick the rig is posed on, which both sides have to agree about.
	 *
	 * <p>See {@link #POSE_TICK}. The server is the clock; the client carries the offset it was last
	 * told and keeps counting, so the two stay in step between samples instead of each starting from
	 * whenever the body happened to enter their own world.
	 */
	public int poseClock() {
		return level().isClientSide() ? tickCount + poseTickOffset : tickCount;
	}

	/**
	 * The box is derived from the drawn mass rather than restated, so a form can never again be
	 * visibly wider than the thing a player is allowed to hit. The eye sits at the glowing core,
	 * which is where the interface reads as looking from.
	 */
	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		int form = form();
		return EntityDimensions
				.fixed(WorldInterfaceAnatomy.hitboxWidth(form), WorldInterfaceAnatomy.hitboxHeight(form))
				.withEyeHeight((float) WorldInterfaceAnatomy.coreLift(form));
	}

	/**
	 * The one entity here whose box does not stand on its own position.
	 *
	 * <p>Vanilla boxes grow upward from the feet, which is right for something that walks. This
	 * floats: the model puts the mass {@link WorldInterfaceAnatomy#massBottomLift} blocks overhead
	 * and hangs its limbs the other way, so a box anchored at the position spent its bottom eight
	 * and a half blocks - at third form - covering air nobody can see anything in, while arrows that
	 * visibly passed under the body registered as hits. Lifting the box by that offset makes the
	 * hittable volume and the silhouette the same volume.</p>
	 */
	@Override
	protected AABB makeBoundingBox(Vec3 position) {
		int form = form();
		double half = WorldInterfaceAnatomy.massRadius(form);
		double bottom = position.y + WorldInterfaceAnatomy.massBottomLift(form);
		return new AABB(position.x - half, bottom, position.z - half,
				position.x + half, bottom + half * 2.0D, position.z + half);
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (FORM.equals(key)) refreshDimensions();
		// Latched as an offset rather than used directly: between samples the two clocks advance
		// together, so the difference is what has to be carried, not the value.
		if (POSE_TICK.equals(key) && level().isClientSide()) {
			poseTickOffset = (int) (entityData.get(POSE_TICK) - tickCount);
			cachedPoseTick = Long.MIN_VALUE;
		}
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (amount <= 0.0F || encounterId == null) return false;
		return EndBossEncounterService.applyVirtualDamage(this, source, amount);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override public boolean isPushable() { return false; }
	@Override public boolean isPickable() { return true; }
	@Override public boolean canBeHitByProjectile() { return true; }

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		if (encounterId != null) output.putString("encounter_id", encounterId.toString());
		output.putInt("form", form());
		output.putInt("action", actionId());
		output.putLong("action_start_tick", actionStartTick());
		output.putInt("action_duration", actionDuration());
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		String encoded = input.getStringOr("encounter_id", "");
		try {
			encounterId = encoded.isBlank() ? null : UUID.fromString(encoded);
		} catch (IllegalArgumentException ignored) {
			encounterId = null;
		}
		entityData.set(FORM, Math.clamp(input.getIntOr("form", FORM_LISTENING),
				FORM_LISTENING, FORM_INTERFACE));
		entityData.set(ACTION, Math.max(0, input.getIntOr("action", 0)));
		entityData.set(ACTION_START_TICK, Math.max(0L, input.getLongOr("action_start_tick", 0L)));
		entityData.set(ACTION_DURATION, Math.max(0, input.getIntOr("action_duration", 0)));
		setNoGravity(true);
		setDeltaMovement(Vec3.ZERO);
		refreshDimensions();
	}
}
