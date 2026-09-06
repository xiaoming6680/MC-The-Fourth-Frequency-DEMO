package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import com.xm.thefourthfrequency.world.StoryProgressService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class WatcherEntity extends Monster {
	/** Aims the gaze test at the eye, 2.62 of 2.9 blocks up, rather than at the chest. */
	public static final double GAZE_TARGET_HEIGHT_FRACTION = 0.90;
	/** Far past a human neck, so the body can face away while the head stays on the player. */
	private static final int MAX_HEAD_YAW = 145;
	/** Reaching it counts the same as seeing it. Either way, it is not there. Five blocks. */
	private static final double VANISH_RANGE_SQR = 25.0;
	/**
	 * One second of being looked at. Was two.
	 *
	 * <p>Two seconds is long enough to walk closer, line the shot up and be certain - and certainty
	 * is the one thing this is built not to give. A second is long enough to register a shape and
	 * short enough that what the player is left with is the question.
	 */
	private static final int GAZE_TICKS_TO_VANISH = 20;

	private UUID observedPlayer;
	private int gazeTicks;
	private int maximumLifetime = 400;

	public WatcherEntity(EntityType<? extends WatcherEntity> type, Level level) {
		super(type, level);
		xpReward = 0;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, 0.0)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
	}

	/** Shared by the server gaze counter and the client eye reveal so the two cannot drift apart. */
	public static double gazeAlignmentThreshold(double distanceSqr) {
		return distanceSqr < 64.0 ? 0.72 : 0.93;
	}

	public void observe(ServerPlayer player, int lifetimeTicks) {
		observedPlayer = player.getUUID();
		maximumLifetime = Math.min(900, Math.max(20, lifetimeTicks));
		setInvulnerable(true);
		setPersistenceRequired();
		setNoGravity(true);
		noPhysics = true;
		setDeltaMovement(Vec3.ZERO);
	}

	public boolean observes(UUID playerId) {
		return playerId.equals(observedPlayer);
	}

	/**
	 * Tracked by the player it was placed for, and by nobody else.
	 *
	 * <p>The figure is an anomaly aimed at one person, and it was being sent to every client in
	 * range. On a shared server that produced the three worst possible tells at once: somebody else
	 * could walk up and inspect it at leisure while the target was fifty blocks away, a bystander
	 * swinging at it got no reaction whatsoever because {@code hurtServer} only answers the observer,
	 * and four players standing together at night got four of them. All three said the same thing -
	 * that it is a spawned mob with rules - which is the one reading the whole effect exists to
	 * avoid.
	 *
	 * <p>This is the entity-tracker's own per-viewer question, so it costs nothing and needs no
	 * mixin: a client that is never sent the entity has nothing to render, nothing to hit, and no
	 * packet to notice. The spawn-side de-duplication in {@code WatcherService} still keeps one per
	 * observer, so a crowd produces a crowd of figures that each exist for exactly one person.
	 */
	@Override
	public boolean broadcastToPlayer(ServerPlayer player) {
		return observedPlayer == null || observes(player.getUUID());
	}

	@Override
	protected void registerGoals() {
		// It watches. Pathfinding would make it feel like an ordinary predator.
	}

	@Override
	protected BodyRotationControl createBodyControl() {
		// The body must never chase the head. A torso facing away with the skull turned back is the
		// whole pose, and vanilla's body control would quietly straighten it out within seconds.
		return new BodyRotationControl(this) {
			@Override
			public void clientTick() {
			}
		};
	}

	@Override public int getMaxHeadYRot() { return MAX_HEAD_YAW; }

	@Override protected float getMaxHeadRotationRelativeToBody() { return MAX_HEAD_YAW; }

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (observedPlayer == null) {
			discard();
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(observedPlayer);
		if (player == null || player.level() != level || !player.isAlive() || distanceToSqr(player) > 4096.0
				|| tickCount >= maximumLifetime) {
			discard();
			return;
		}
		getNavigation().stop();
		setNoGravity(true);
		setDeltaMovement(Vec3.ZERO);
		// Only the head tracks. The body keeps whatever yaw it spawned with.
		getLookControl().setLookAt(player, 360.0F, 360.0F);

		if (tickCount > 15 && distanceToSqr(player) < VANISH_RANGE_SQR) {
			vanish(level, player);
			return;
		}
		if (tickCount > 15 && playerCanSee(player)) gazeTicks++; else gazeTicks = 0;
		if (gazeTicks >= GAZE_TICKS_TO_VANISH) vanish(level, player);
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// Swinging at it must not land, flash, or knock back. The blade passes through an absence.
		if (source.getEntity() instanceof ServerPlayer player && observes(player.getUUID())) {
			vanish(level, player);
		}
		return false;
	}

	/**
	 * The single exit for every route out: gaze, reach, or a swing. All three are "you found it",
	 * so none of them costs the player story progress; the horror is that it is already gone.
	 */
	private void vanish(ServerLevel level, ServerPlayer player) {
		if (isRemoved()) return;
		level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMBIENT_CAVE,
				SoundSource.AMBIENT, 1.0F, 0.72F);
		StoryProgressService.recordWatcher(player);
		discard();
		// Being found is the end of the anomaly, not just the end of the entity. Without this the
		// figure went and the instance ran on to its own timeout - twenty seconds of an anomaly with
		// nothing left in it, which reads as one that failed to finish.
		AnomalyRuntimeService.completeWatcherSighting(player);
	}

	private boolean playerCanSee(ServerPlayer player) {
		Vec3 towardWatcher = position().add(0.0, getBbHeight() * GAZE_TARGET_HEIGHT_FRACTION, 0.0)
				.subtract(player.getEyePosition()).normalize();
		double alignment = player.getViewVector(1.0F).dot(towardWatcher);
		return alignment > gazeAlignmentThreshold(distanceToSqr(player)) && player.hasLineOfSight(this);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override public boolean isPushable() { return false; }

	/** Pickable so an attack ray can reach it; hurtServer then refuses to let the hit land. */
	@Override public boolean isPickable() { return true; }
}
