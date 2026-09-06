package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Installs the encounter's screen-space treatments for the actions that claim the player's senses
 * rather than only their hit points.
 *
 * <p>This decides <em>which</em> treatment the encounter wants and nothing else. Whether it actually
 * reaches the screen is {@link PostEffectArbiter}'s call - a pursuit outranks a lock, and there is
 * only one post-effect slot to go round.</p>
 *
 * <p>Three things can want it, and they are asked in that order: a beam weapon landing, the forced
 * expulsion, and then the target lock. The beam is asked <em>first and above the target test</em>,
 * which is the whole reason the order is written down - it is the one treatment here that is not
 * private, and moving it under that test would quietly make it private again while still working
 * perfectly for whoever was being shot at.</p>
 */
public final class WorldInterfacePostEffectController {
	/** Worn while an action has this player locked but has not yet resolved. */
	public static final Identifier LOCK = effect("world_interface_lock");
	/**
	 * The heavier chain. Nothing installs it any more - every lock now wears the plain treatment,
	 * because the actions that escalated to this are the ones the player most needs to see through.
	 * It stays declared so {@link #clearOwned} still recognises and removes it from a client that
	 * was wearing it when the encounter changed underneath them.
	 */
	public static final Identifier LOCK_PEAK = effect("world_interface_lock_peak");
	public static final Identifier EXPULSION = effect("world_interface_expulsion");
	/**
	 * Worn by the player a beam weapon just landed on, for {@link #IMPACT_TICKS} after it lands.
	 *
	 * <p>The one treatment in the encounter that is not about a rule being bent. See
	 * {@code chromatic_dispersion.fsh}: a beam does not damage the picture, it lights the air the
	 * picture arrives through, and the tape and pipeline languages both say the wrong thing about
	 * it. This is optics, and it is the only screen treatment here with no corruption in it at all.
	 */
	public static final Identifier DISPERSION = effect("world_interface_dispersion");
	/**
	 * The same event seen from anywhere else on the island.
	 *
	 * <p>Every other treatment in this class is private, because every other thing that installs one
	 * is something happening to one person. A beam crossing the arena is not: it is the single most
	 * visible event the encounter produces, and a table where seven people see nothing while the
	 * eighth's screen comes apart has told those seven that the fight is somewhere else. The far
	 * chain is deliberately much weaker - it says "that happened", not "that is happening to you",
	 * and the difference between the two chains is what keeps being singled out legible.
	 */
	public static final Identifier DISPERSION_FAR = effect("world_interface_dispersion_far");
	/**
	 * How far from the arena centre a player still wears the far chain, in blocks.
	 *
	 * <p>Sized on the island rather than on the beam. Working out whether this particular client can
	 * actually see this particular shaft would mean reproducing the server's aim trail and its
	 * ground trace here, and getting that subtly wrong means a treatment that switches on and off
	 * while the player turns around. Being on the island while the interface fires is the honest
	 * predicate, and it is the one the audio for these attacks already uses.
	 */
	private static final double WITNESS_RADIUS_BLOCKS = 128.0D;
	private static final double WITNESS_RADIUS_SQR = WITNESS_RADIUS_BLOCKS * WITNESS_RADIUS_BLOCKS;
	/**
	 * How long the beam treatment is worn after a landing, in ticks.
	 *
	 * <p>Six tenths of a second. Long enough to be read as a distortion rather than as a single
	 * dropped frame, short enough that it is still an event: a post chain's uniforms are fixed when
	 * it loads, so this cannot fade out, and a treatment that cannot fade has to be brief or it ends
	 * on a hard cut that reads as the shader crashing.
	 *
	 * <p>Deliberately longer than the hit flash (5 ticks) and shorter than a lock window. It sits
	 * with the shake and the freeze that land on the same tick, and outlasts both, so the impact has
	 * a tail rather than three simultaneous instants.
	 */
	private static final int IMPACT_TICKS = 12;
	/** Fraction of a lock window that passes before any screen treatment is worn at all. */
	private static final float PEAK_FRACTION = 0.7F;
	/**
	 * Where the warning treatment hands over to the committed one.
	 *
	 * <p>Two thirds in: past this the attack is effectively locked to its target and the remaining
	 * window is for getting clear rather than for deciding whether you have to. The colour change
	 * from violet to red is what says which of the two the player is in.
	 */
	private static final float LOCK_COMMIT_FRACTION = 0.66F;
	private static final List<Identifier> OWNED =
			List.of(LOCK, LOCK_PEAK, EXPULSION, DISPERSION, DISPERSION_FAR);

	private WorldInterfacePostEffectController() {
	}

	private static Identifier effect(String path) {
		return Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
	}

	public static void tick(Minecraft client, WorldInterfaceClientState.Projection projection) {
		PostEffectArbiter.claim(client, PostEffectArbiter.Owner.WORLD_INTERFACE,
				wantedEffect(client, projection));
	}

	/** Called from every encounter teardown path so a shader can never outlive the fight. */
	public static void clearOwned(Minecraft client) {
		PostEffectArbiter.release(client, PostEffectArbiter.Owner.WORLD_INTERFACE);
	}

	public static boolean isOwned(Identifier active) {
		return active != null && OWNED.contains(active);
	}

	/**
	 * Whether one of the two lock chains is currently installed.
	 *
	 * <p>Asked by the HUD vignette, which exists as the fallback for a driver that will not compile
	 * the custom shader. Both were drawing at once, and two edge treatments stacked is not a warning
	 * - it is a wall around the screen. Only one of them may be on at a time.
	 *
	 * <p>Asked of the arbiter rather than of the game renderer: the claim can be live while a
	 * higher-priority owner holds the screen, and in that case the lock is <em>not</em> what the
	 * player is looking at, so the HUD fallback is exactly what should be drawing.
	 */
	public static boolean isLockChainActive(Minecraft client) {
		return PostEffectArbiter.isInstalled(LOCK) || PostEffectArbiter.isInstalled(LOCK_PEAK);
	}

	// There is deliberately no isDispersionActive() to pair with the above. The HUD band that query
	// exists for is drawn only inside a lock window, and neither beam lands inside one: the laser
	// arrives on the tick its warning ends, and the lance on the tick its charge does. A second
	// accessor with no caller would be a claim that the two can overlap.

	private static Identifier wantedEffect(Minecraft client, WorldInterfaceClientState.Projection projection) {
		if (client.level == null || client.player == null) return null;
		long now = client.level.getGameTime();
		if (!projection.actionActive(now)) return null;
		BossActionS2C action = projection.action();
		long elapsed = now - action.startTick();
		if (elapsed < 0L) return null;
		// Asked before the target test, and that is not a nicety: this is the one treatment in the
		// encounter that is worn by players it is not aimed at, so it cannot live underneath a gate
		// whose whole job is to ask whether it is aimed at you.
		Identifier dispersion = wantedDispersion(client, projection, action, elapsed);
		if (dispersion != null) return dispersion;
		if (!projection.actionTargets(client.player.getUUID())) return null;
		if (action.action() == WorldInterfaceProtocol.BossAction.FORCED_EXPULSION) return EXPULSION;
		// Every locking action shares one treatment, on its own warning clock.
		int warning = WorldInterfaceProtocol.lockWarningTicks(action.action());
		if (warning <= 0) return null;
		// The sky lance keeps its treatment through the charge, which is the stretch where the
		// impact is already fixed and running is the only answer left.
		boolean lance = action.action() == WorldInterfaceProtocol.BossAction.SKY_LANCE;
		int worn = lance ? warning + WorldInterfaceProtocol.SKY_LANCE_CHARGE_TICKS : warning;
		if (elapsed < 0L || elapsed >= worn) return null;
		// The lock wears a screen treatment again, but only at the edges.
		//
		// The chain this replaces was a box blur plus a full-screen violet wash, and switching it
		// off was the right call: a lock is the moment the player most needs to read the arena and
		// move through it, and blurring or tinting the whole frame fought the dodge the warning
		// existed to enable. Turning it off, though, left the treatment as dead code and the lock
		// with nothing but a reticle.
		//
		// Both chains now run digital_corrupt.fsh with its radial mask open: the middle of the
		// screen is untouched - literally, the mask is zero there - and the interference ramps in
		// only towards the corners. The edge carries the warning; the centre stays for playing in.
		// There is no blur anywhere in either chain.
		//
		// The language is deliberate. Being locked is the interface reaching into the rules that
		// decide where the player is allowed to be, so the border comes apart the way a render
		// pipeline does - bands sliding, channels parting, blocks giving out - and not the way a
		// tape does. The tape is the anomalies' voice, and it is a different shader.
		//
		// Two variants rather than one that ramps, because a post chain's own uniforms are fixed
		// when it loads. Same pattern the pursuit uses with its DISTANT/CLOSE/CONTACT trio. What is
		// *not* static is the treatment's motion: the shader reads GameTime out of the Globals
		// block, so a held chain still corrupts differently every few ticks.
		return elapsed >= warning * LOCK_COMMIT_FRACTION ? LOCK_PEAK : LOCK;
	}

	/**
	 * The dispersion chain this client should be wearing, or null if no beam is discharging.
	 *
	 * <p>Derived from the action envelope and the two shared beam clocks, so it never needs a packet
	 * of its own: every client already knows when the laser stops warning and starts burning, and
	 * when the lance stops charging and starts falling. A "flash now" message would be a second
	 * clock alongside one that already works, and the one place this fight cannot afford a second
	 * clock is the frame an attack lands on.</p>
	 */
	private static Identifier wantedDispersion(Minecraft client,
			WorldInterfaceClientState.Projection projection, BossActionS2C action, long elapsed) {
		if (!isLanding(action.action(), elapsed)) return null;
		boolean aimed = projection.actionTargets(client.player.getUUID());
		if (!aimed && !withinWitnessRange(client, projection)) return null;
		// The strong chain is the one with a visible bulge and a hard fringe in it, and impactFlash
		// is the setting a player turns off when that class of thing is a problem for them. Turning
		// it off does not remove the attack's screen presence - losing the shot entirely would be
		// losing information - it drops everyone to the treatment the rest of the arena is wearing.
		if (!aimed || !RuntimeServices.config().presentation().impactFlashEnabled()) return DISPERSION_FAR;
		return DISPERSION;
	}

	/**
	 * Whether a beam weapon has just <em>arrived</em>.
	 *
	 * <p>Keyed on the landing, not on the discharge. The first version of this wore the treatment
	 * for as long as the weapon was firing, and for the laser that is two full seconds of sweep -
	 * which turns an impact into a state. A screen treatment that lasts is a condition the player is
	 * in; a screen treatment that arrives and goes is a thing that happened to them, and this is a
	 * thing that happened to them. It is also the only version that has any force: at two seconds
	 * the eye settles into the distortion and stops reading it as an event at all.</p>
	 *
	 * <p>So each beam has exactly one arrival tick and a short window after it. For the laser that
	 * is the tick the shaft reaches the ground and the contact starts detonating; for the lance it
	 * is the tick the column lands and the crater is cut - the same tick the server damages on and
	 * the same tick the blast packet shakes the camera on, so the flash, the shake and the freeze
	 * all land together instead of arriving as three separate events.</p>
	 *
	 * <p>Only the two attacks that are beams. The energy orb is a projectile with a flight of its
	 * own - and no arrival tick anything on the client can derive, since it is the bolt entity that
	 * decides where it stops - and the tendrils are limbs. Neither is light, and giving them an
	 * optical treatment would make the one screen language in this mod that means "something was
	 * fired at me" mean "something happened" instead.</p>
	 */
	private static boolean isLanding(WorldInterfaceProtocol.BossAction action, long elapsed) {
		long arrival = switch (action) {
			// The sweep's own first contact. The beam is drawn from the core to the ground in one
			// tick, so there is no travel to wait through: firing and arriving are one event.
			case LASER_SWEEP -> WorldInterfaceProtocol.LASER_WARNING_TICKS;
			// Not the start of the descent. The column takes SKY_LANCE_FALL_TICKS to come down and
			// opening the treatment there would put it a fraction of a second ahead of the impact,
			// which is the exact mistake the camera shake used to make on this attack.
			case SKY_LANCE -> (long) WorldInterfaceProtocol.SKY_LANCE_LOCK_TICKS
					+ WorldInterfaceProtocol.SKY_LANCE_CHARGE_TICKS;
			default -> -1L;
		};
		return arrival >= 0L && elapsed >= arrival && elapsed < arrival + IMPACT_TICKS;
	}

	/** Whether this client is close enough to the arena to have watched the shot happen. */
	private static boolean withinWitnessRange(Minecraft client,
			WorldInterfaceClientState.Projection projection) {
		var encounter = projection.encounter();
		if (encounter == null) return false;
		// The dimension first, and not as a formality. The arena's centre is a coordinate, and the
		// same coordinate exists in the Overworld: without this, a player who never went to the End
		// wears the treatment because they happen to be standing above the right patch of ground.
		if (client.level.dimension() != Level.END) return false;
		return client.player.distanceToSqr(encounter.center().getCenter()) <= WITNESS_RADIUS_SQR;
	}
}
