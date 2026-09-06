package com.xm.thefourthfrequency.entity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The figure that is not there by the time you have finished looking at it.
 *
 * <p>Where {@link WatcherEntity} rewards a held stare — two seconds of deliberately keeping it in
 * frame — this one does the opposite and punishes it. It is placed outside the player's view, and
 * the instant it enters view it starts a fifth of a second's countdown to nothing. The intended
 * read is not "a monster appeared" but "there was something in the trees and now there isn't", the
 * doubt being the entire payload. Everything below exists to protect that read:
 *
 * <ul>
 *   <li><b>Once seen, it goes.</b> The countdown latches rather than resetting when the player
 *       looks away, so glancing off and back cannot produce a second sighting. A thing that can be
 *       looked at twice is a thing that is really there.</li>
 *   <li><b>It leaves in silence.</b> The watcher gets a cave ambience on its way out; this one gets
 *       nothing. A sound is confirmation, and confirmation is the one thing it must not give.</li>
 *   <li><b>It cannot be interacted with.</b> Not pickable, not pushable, immune, and a swing at it
 *       simply removes it. There is no encounter here to have.</li>
 *   <li><b>It never travels.</b> No pathfinding, no wandering, no velocity. It does keep itself
 *       square on to the player - see {@link #faceWatchedPlayer} - which is a reversal of what this
 *       list used to say. The reasoning it replaces was that any motion at all reads as an entity;
 *       what that produced in play was a figure showing its shoulder to wherever the player had
 *       walked to, which reads as a statue rather than as something aware of them. Turning is the
 *       one motion that makes it <em>more</em> like a thing that was already watching, not less.</li>
 * </ul>
 */
public final class HimEntity extends Monster {
	/** Four ticks. Long enough to register as a shape, short enough to distrust. */
	public static final int VANISH_DELAY_TICKS = 4;
	/** Aims the sighting test at the head rather than the feet. Player-proportioned, so 1.62 of 1.8. */
	private static final double SIGHT_TARGET_HEIGHT_FRACTION = 0.9;
	/**
	 * How closely the player has to be looking at it to count as having seen it.
	 *
	 * <p>Deliberately wider than the watcher's, and wider still up close. That one needs a
	 * deliberate stare so it must not trigger on a glance; this one is trying to be caught in the
	 * corner of the eye, so anything inside roughly the screen edge counts.
	 */
	private static final double SIGHT_ALIGNMENT_NEAR = 0.42;
	private static final double SIGHT_ALIGNMENT_FAR = 0.72;
	private static final double NEAR_DISTANCE_SQR = 256.0;
	/** Reaching it counts as finding it, the same as seeing it would. */
	private static final double VANISH_RANGE_SQR = 16.0;

	private UUID watchedPlayer;
	private int seenTicks = -1;
	private int maximumLifetime = 600;

	public HimEntity(EntityType<? extends HimEntity> type, Level level) {
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

	/** Alignment needed to count as seen, widening as it gets closer and fills more of the screen. */
	public static double sightAlignmentThreshold(double distanceSqr) {
		return distanceSqr < NEAR_DISTANCE_SQR ? SIGHT_ALIGNMENT_NEAR : SIGHT_ALIGNMENT_FAR;
	}

	public void haunt(ServerPlayer player, int lifetimeTicks) {
		watchedPlayer = player.getUUID();
		maximumLifetime = Math.min(1200, Math.max(40, lifetimeTicks));
		setInvulnerable(true);
		setPersistenceRequired();
		setNoGravity(true);
		noPhysics = true;
		setDeltaMovement(Vec3.ZERO);
	}

	public boolean haunts(UUID playerId) {
		return playerId.equals(watchedPlayer);
	}

	/**
	 * Sent to the player it is haunting, and to nobody else.
	 *
	 * <p>Same reasoning as {@code WatcherEntity#broadcastToPlayer}, and one tell of its own: this one
	 * vanishes on any damage at all, so on a shared server a teammate's stray arrow or a skeleton
	 * shooting past could delete a sighting the target was in the middle of having. A client that
	 * never receives the entity cannot do that, and neither can anything it owns.
	 */
	@Override
	public boolean broadcastToPlayer(ServerPlayer player) {
		return watchedPlayer == null || haunts(player.getUUID());
	}

	/**
	 * Keeps the figure square on to the player, every tick, for as long as it stands there.
	 *
	 * <p>It used to be aimed once at spawn and then left. That is fine while the player holds still,
	 * and wrong the moment they circle it: the thing that is supposed to have been watching them ends
	 * up presenting its shoulder to wherever they walked to, which reads as a statue someone left out
	 * rather than as something aware of them.
	 *
	 * <p>Only the facing moves. Position, gravity and velocity are all still pinned above - this is a
	 * head turning, not a mob tracking. The body is turned with the head deliberately: a head rotated
	 * away from its own torso is vanilla's look-at pose, and the whole point here is that the figure
	 * is not doing anything as ordinary as looking around.
	 */
	private void faceWatchedPlayer(ServerPlayer player) {
		double dx = player.getX() - getX();
		double dz = player.getZ() - getZ();
		if (dx * dx + dz * dz < 1.0E-6D) return;
		float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
		setYRot(yaw);
		setYBodyRot(yaw);
		setYHeadRot(yaw);
		yRotO = yaw;
		yBodyRotO = yaw;
		yHeadRotO = yaw;
	}

	@Override
	protected void registerGoals() {
		// It stands there. Anything else would make it a mob.
	}

	@Override
	protected BodyRotationControl createBodyControl() {
		// Vanilla would quietly rotate the body toward whatever the head is doing. Nothing about
		// this figure may move after it is placed.
		return new BodyRotationControl(this) {
			@Override
			public void clientTick() {
			}
		};
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (watchedPlayer == null) {
			discard();
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(watchedPlayer);
		if (player == null || player.level() != level || !player.isAlive()
				|| distanceToSqr(player) > 16384.0 || tickCount >= maximumLifetime) {
			discard();
			return;
		}
		getNavigation().stop();
		setNoGravity(true);
		setDeltaMovement(Vec3.ZERO);
		faceWatchedPlayer(player);

		if (distanceToSqr(player) < VANISH_RANGE_SQR) {
			discard();
			return;
		}
		// Latched, not sampled: the first frame it is seen starts the clock and nothing stops it.
		// Re-testing every tick would let a player who looked away inside the window find it still
		// standing there, which answers the question the whole thing exists to leave open.
		if (seenTicks >= 0) {
			if (++seenTicks >= VANISH_DELAY_TICKS) discard();
			return;
		}
		if (playerHasInView(player)) seenTicks = 0;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// Nothing lands. A hit that connects is proof, and there is nothing here to be proved.
		discard();
		return false;
	}

	private boolean playerHasInView(ServerPlayer player) {
		Vec3 head = position().add(0.0, getBbHeight() * SIGHT_TARGET_HEIGHT_FRACTION, 0.0);
		Vec3 toward = head.subtract(player.getEyePosition());
		if (toward.lengthSqr() < 1.0E-6) return true;
		double alignment = player.getViewVector(1.0F).dot(toward.normalize());
		return alignment > sightAlignmentThreshold(distanceToSqr(player)) && player.hasLineOfSight(this);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override public boolean isPushable() { return false; }

	/**
	 * Not pickable, unlike the watcher. That one wants the swing to reach it so the miss can be
	 * felt; this one must not even produce a crosshair highlight, because a highlight is the game
	 * confirming there is an entity there.
	 */
	@Override public boolean isPickable() { return false; }
}
