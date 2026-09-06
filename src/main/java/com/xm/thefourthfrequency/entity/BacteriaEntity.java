package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.unrendered.UnrenderedDimensions;
import com.xm.thefourthfrequency.unrendered.UnrenderedSessionService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * The thing that shares the unrendered layer with you, once you have been there long enough to be
 * sure nothing does.
 *
 * <p>It is not a fight and cannot be made into one. It has no attack, deals no damage and cannot be
 * hurt, killed, pushed or blocked in; reaching the player is the only thing it does, and what
 * happens then is a capture rather than a death - see
 * {@link UnrenderedSessionService#capture(ServerPlayer)} for why that distinction is load-bearing
 * rather than cosmetic.
 *
 * <p>Bound to exactly one player for its whole life. Two sessions are a million blocks apart, so it
 * could never reach the wrong one by accident, but binding it makes that a property of the entity
 * instead of a property of the geography.
 */
public final class BacteriaEntity extends Monster {
	/**
	 * Movement speed attribute, measured to sit in the gap between sprinting and sprint-jumping.
	 *
	 * <p>The band this has to land in is narrow and the whole encounter rests on it. A sprinting
	 * player moves 5.612 blocks per second and a sprint-jumping one roughly 7.1, so anything at or
	 * below the first makes the entity scenery, and anything at or above the second makes it
	 * unescapable and the layer a corridor with a fixed timer. Between them the player is losing
	 * ground while running and gaining it while jumping, which turns a chase into something they are
	 * being asked to do correctly. 0.396 measures close to <b>6.9 blocks per second</b> on flat ground.
	 *
	 * <p><b>The attribute is not the speed, and the relationship is quadratic.</b> {@code
	 * Mob.setSpeed} sets the forward movement input to the same value it sets the speed field to, so
	 * the acceleration carries the attribute twice; terminal velocity then follows from ground
	 * friction and drag. Measured on default 0.6-friction ground the law is
	 *
	 * <pre>  blocks per second ~= 44.05 * attribute^2</pre>
	 *
	 * <p>which two samples pin exactly: 0.335 gives 4.94 and 0.400 gives 7.05, for coefficients of
	 * 44.0 and 44.1. Both of those were shipped-and-caught mistakes rather than hypotheticals - 0.335
	 * was inferred from neighbouring vanilla values and is <em>slower than a sprinting player</em>,
	 * which would have made the entity scenery, and nothing but a measurement would have found that.
	 * {@code UnrenderedLayerGameTests.theBacteriaSpeedLandsBetweenSprintingAndSprintJumping} is that
	 * measurement, and it now runs on every server GameTest pass. Retune from its reported figure
	 * rather than by reasoning about this number.
	 *
	 * <p>The number has been argued in both directions and the record is worth keeping. It was eased
	 * down to 0.370 (6.03 blocks/s) once the entity could be heard coming, on the reasoning that the
	 * heartbeat turns a chase the player is losing on a stopwatch into one they can steer, and that
	 * the margin over a sprint is what they steer with - at 6.26 a straight corridor was a losing
	 * race whatever they did, and the encounter collapsed back onto sprint-jumping.
	 *
	 * <p>It now sits at 0.396, close to <b>6.9 blocks/s</b>, on an explicit call to put it just under
	 * a sprint-jump rather than mid-band. That accepts the cost the paragraph above names: a straight
	 * corridor <em>is</em> a losing race again. What the player steers with instead is the floor plan
	 * and the heartbeat - corners, trunk junctions and knowing which side it is on - and the jump is
	 * back to being a decision that has to be made well rather than a habit with room to spare.
	 */
	public static final double MOVEMENT_SPEED = 0.396;

	/** Close enough to be reached. Generous rather than exact: nobody should escape on a rounding. */
	private static final double CAPTURE_RANGE_SQR = 1.8 * 1.8;
	/** Repathing every half second. The maze is static, so more often buys nothing. */
	private static final int REPATH_INTERVAL_TICKS = 10;

	private UUID boundPlayer;
	private int repathCooldown;

	public BacteriaEntity(EntityType<? extends BacteriaEntity> type, Level level) {
		super(type, level);
		xpReward = 0;
		setPersistenceRequired();
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 20.0)
				.add(Attributes.MOVEMENT_SPEED, MOVEMENT_SPEED)
				// It always knows. Losing it is a question of distance and walls, never of whether it
				// noticed - a Backrooms entity that can be broken line of sight with is a stealth
				// puzzle, and this is not one.
				.add(Attributes.FOLLOW_RANGE, 512.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
				.add(Attributes.ATTACK_DAMAGE, 0.0);
	}

	/** No goals. Its entire behaviour is {@link #customServerAiStep}, which is four lines long. */
	@Override
	protected void registerGoals() {
	}

	public void bind(ServerPlayer player) {
		boundPlayer = player.getUUID();
		// The default node budget is sized for mobs that give up on anything far away. This one is
		// placed a hundred blocks out precisely so it is not seen arriving, so it has to be able to
		// solve the path that distance implies.
		getNavigation().setMaxVisitedNodesMultiplier(4.0F);
		setInvulnerable(true);
		setPersistenceRequired();
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (!UnrenderedDimensions.isUnrendered(level)) {
			// Should be unreachable - nothing teleports it - but an entity of this kind loose in the
			// overworld is the single worst outcome available, so it is checked rather than assumed.
			discard();
			return;
		}
		ServerPlayer target = boundPlayer == null ? null : level.getServer().getPlayerList().getPlayer(boundPlayer);
		if (target == null || target.isRemoved() || !UnrenderedDimensions.isUnrendered(target.level())) {
			discard();
			return;
		}
		getLookControl().setLookAt(target, 30.0F, 30.0F);
		if (--repathCooldown <= 0) {
			repathCooldown = REPATH_INTERVAL_TICKS;
			// Pathfinding first, and a fallback for when it fails - which at a hundred blocks through
			// a maze it frequently does. moveTo simply returns false then, and the previous version
			// did nothing with that: the entity was placed outside view distance, never found a path,
			// and stood exactly where it spawned for the rest of the session. The player heard it
			// coming and never saw it, because it never came.
			if (!getNavigation().moveTo(target, 1.0D)) {
				// Drive it straight at them instead. The floor plan is open enough that a direct
				// heading makes progress even when it grazes walls, and every repath from closer in
				// is far more likely to solve - so this is the thing that gets it near enough for
				// proper pathing to take over, not a permanent substitute for it.
				getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), 1.0D);
			}
		}
		if (distanceToSqr(target) <= CAPTURE_RANGE_SQR && UnrenderedSessionService.capture(target)) {
			discard();
		}
	}

	/** Never despawns on distance. The session that made it is the only thing that removes it. */
	@Override
	public void checkDespawn() {
	}

	/**
	 * Leaves the world instead of falling through it forever.
	 *
	 * <p>{@code LivingEntity} handles being below the world by applying void damage and nothing else -
	 * it never discards, on the assumption that four damage a tick will shortly do it. This one is
	 * immune to damage, so inherited behaviour is an entity accelerating downward for the rest of the
	 * session, ticking the whole way, with the encounter silently over because the thing hunting the
	 * player is now several thousand blocks beneath the floor.
	 *
	 * <p>It gets down there the same way the player does: the way out is a hole, and a path towards
	 * somebody standing across one goes over the edge. {@code UnrenderedSessionService} notices the
	 * removal and places another, which is the right outcome rather than a fallback - what the player
	 * saw was it following them into the gap.
	 */
	@Override
	protected void onBelowWorld() {
		discard();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void doPush(net.minecraft.world.entity.Entity other) {
	}

	@Override
	public void push(double x, double y, double z) {
	}

	@Override
	public boolean canBeCollidedWith(net.minecraft.world.entity.Entity other) {
		return false;
	}

	/** Silent by design: the layer's ambience is the only bed, and footsteps would give range away. */
	@Override
	public boolean isSilent() {
		return true;
	}
}
