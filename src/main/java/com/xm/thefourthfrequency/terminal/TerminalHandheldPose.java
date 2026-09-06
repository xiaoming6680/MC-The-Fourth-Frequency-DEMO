package com.xm.thefourthfrequency.terminal;

/**
 * Where the held terminal sits, how big it is and what the camera's field of view does, as a
 * function of how far open it is.
 *
 * <p>The device itself never deforms - it is one rigid block with no hinge. It is also heavy and
 * two-handed: the player carries it in both hands, low and tipped away, and opening it is the
 * <em>camera</em> closing on the CRT as they raise it to read. The field of view narrows a little
 * at the same time, which is what sells the eye moving rather than the arm.</p>
 *
 * <p>Lives in the common source set, like {@link TerminalMotion} and {@link TerminalUiLayout}, for
 * the same reason: no Minecraft dependency, so JUnit can assert "at this openness the device is
 * here" instead of inferring the curve from a screenshot. The client-side
 * {@code TerminalHandheldAnimator} owns the state machine and the clock; every number the renderer
 * needs comes from here.</p>
 *
 * <h2>Two paths, and why</h2>
 *
 * <ul>
 * <li>{@link #presentation} is the normal one: both hands on the device, drawn in place of
 * vanilla's single-hand pass. Every position is absolute camera space, so the device is never at
 * the mercy of where an arm happens to be, and it is sized to stay inside the frame at full open
 * rather than punching through it.</li>
 * <li>{@link #carried} is the fallback for a player holding something in the off hand, where two
 * hands on the terminal would mean silently hiding the other item. Vanilla draws the item and this
 * only adjusts it - a slight tilt and the idle breath.</li>
 * </ul>
 *
 * <h2>Hitting things while holding it</h2>
 *
 * <p>Because {@link #presentation} replaces vanilla's whole first-person pass, nothing that pass
 * did survives unless it is restated here - and vanilla's swing was one of those things. A player
 * mining, punching or opening a chest with the terminal in hand saw a device welded to the lens
 * while the world reacted, which reads as the terminal not really being held. The jab term is that
 * pass's missing half, rebuilt for a grip that has no free arm to rotate: see
 * {@link #swingEnvelope}.</p>
 */
public final class TerminalHandheldPose {
	/** How long the device takes to come up. Long enough to read as travel, short enough to obey. */
	public static final long OPEN_MILLIS = 460L;
	/** Closing is quicker: the player has already decided to leave and is waiting on the world. */
	public static final long CLOSE_MILLIS = 340L;

	/**
	 * Idle breathing period.
	 *
	 * <p>0.28 Hz, an order of magnitude under the 3 Hz flicker ceiling, and it modulates position
	 * rather than brightness - but it is on screen for the entire game, so it is held to the same
	 * standard anyway.</p>
	 */
	public static final double BREATH_PERIOD_MILLIS = 3_600.0D;
	private static final float BREATH_AMPLITUDE = 0.011F;

	// --- two-handed presentation ----------------------------------------------------------------
	// Camera space. Vanilla holds a first-person item at z = -0.72; the device stays near that
	// plane and closes only a little of the gap, because the whole effect is scale and field of
	// view. Driving it forward instead is what puts a 14-pixel-wide box through the near plane.
	/**
	 * Depth the device is drawn at.
	 *
	 * <p>Much nearer than vanilla's -0.72. First-person hands are drawn against the world's depth
	 * buffer, so anything close to the camera eats them - a fact players know from watching their
	 * own hand vanish into a wall. Vanilla gets away with it because a held item is small and off
	 * in one corner; this device is large and centred, so the same occlusion removes the whole
	 * thing at once. Halving the distance roughly halves how close a surface has to be.</p>
	 *
	 * <p>Everything visual is held constant across the move: apparent size and screen position are
	 * both ratios over depth, so {@link #REST_SCALE}, {@link #OPEN_SCALE}, {@link #REST_Y} and
	 * {@link #OPEN_Y} were all scaled by the same factor the depth was.</p>
	 */
	private static final float BASE_Z = -0.45F;
	private static final float OPEN_Z_GAIN = 0.03F;
	/**
	 * Height of the device's centre while it is just being carried.
	 *
	 * <p>Low, but not below the frame. An earlier value put the entire device off the bottom edge -
	 * carried so low the player never saw they were holding anything.</p>
	 */
	private static final float REST_Y = -0.21F;
	private static final float OPEN_Y = -0.05F;

	/**
	 * Depth the hands are drawn at, fixed at vanilla's own.
	 *
	 * <p>The arms are life-sized geometry and must stay that way; pulling them in with the device
	 * would inflate them. That means the two sit at different depths, and a world-space offset that
	 * looks right on one is wrong on the other - which is the mismatch {@link #screenAligned}
	 * exists to correct.</p>
	 */
	private static final float HAND_Z = -0.72F;

	/**
	 * How large the device is drawn, as a multiplier on its 15/16-block width.
	 *
	 * <p>The open value is bounded by the frame, not by taste, and the bound has two terms that are
	 * easy to forget:</p>
	 *
	 * <ul>
	 * <li><b>The lean magnifies everything.</b> {@link #OPEN_FOV_SCALE} narrows the field of view,
	 * which shrinks the visible world at the item's depth by the same factor. A size computed
	 * against the un-narrowed frame is about 15% too large by the time it is drawn.</li>
	 * <li><b>The narrowest aspect ratio wins.</b> The device is twice as wide as it is tall, so
	 * width is the binding constraint, and it has to fit a 4:3 window - not the 16:9 one the
	 * screenshots happen to be taken at.</li>
	 * </ul>
	 *
	 * <p>At 1.05 and z = -0.67 the device covers about 92% of the width of a 4:3 frame and 66% of
	 * its height. Going further does not make the CRT more readable, it pushes the brass rim off
	 * the edges and through the hands.</p>
	 */
	private static final float REST_SCALE = 0.39F;
	private static final float OPEN_SCALE = 0.66F;

	/**
	 * Tipped at rest - carried, not read. Square on by the time the screen arrives.
	 *
	 * <p>Negative, which lies the device back so its face turns upward. The carried position is
	 * below the eye line, so a player looking down at it sees the screen at a glance; the positive
	 * sign tips it the other way and shows them the top edge of a closed box.</p>
	 */
	private static final float REST_PITCH_DEGREES = -25.0F;

	// --- where the hands go ---------------------------------------------------------------------
	//
	// The hands are placed against the *edges of the device*, not at a fixed offset from its
	// centre, because the device changes size as it comes up and the hands do not. A constant
	// offset looks correct at exactly one openness and drifts everywhere else: the hands appear to
	// slide inward across the casing as it grows, which reads as the grip slipping.
	//
	// So each of the three placements below is the device's own half-extent at the current scale,
	// plus a clearance.

	/**
	 * Half-extents of the shell in blocks: 15 x 8 sixteenths wide and tall, from the generated
	 * model. Depth is not needed - the hands keep their own, and never follow the device's.
	 */
	private static final float HALF_WIDTH = 15.0F / 32.0F;
	private static final float HALF_HEIGHT = 8.0F / 32.0F;

	/**
	 * Correction for vanilla's map-hand pose, which drops about 0.6 below the point it is given.
	 *
	 * <p>That drop was chosen for a map drawn at scale 2. Handing it the device's own position put
	 * both arms off the bottom of the screen; this cancels it so the offsets above mean what they
	 * say.</p>
	 */
	private static final float HAND_DROP_COMPENSATION = 0.60F;

	/**
	 * How far the two arms sit apart in vanilla's own map pose, per side.
	 *
	 * <p>Subtracted rather than assumed away: the device is about as wide as this, so at the
	 * default spacing each wrist ended up inside the casing instead of holding it.</p>
	 */
	private static final float VANILLA_HAND_HALF_SPAN = 0.30F;

	/** Daylight between the hands and the casing, on each of the three axes. */
	private static final float HAND_CLEARANCE = 0.06F;

	// --- attack and use -------------------------------------------------------------------------
	//
	// Vanilla's swing rotates an arm about a shoulder and takes whatever is in the hand with it.
	// This device is braced in both hands in front of the chest, so the same rotation would sweep a
	// frame-wide CRT out of view and back. What a two-handed grip actually does when it hits
	// something is shove: the whole rig - device and both arms - punches forward and down and
	// recovers. So the jab is a translation plus a tip, applied to the terminal's own position, and
	// the hands follow it for free because their placement is derived from that position.

	/** Peak forward travel of the jab, blocks. Negative is away from the camera. */
	private static final float SWING_PUSH_Z = -0.05F;
	/** Peak downward travel of the jab, blocks. */
	private static final float SWING_DROP_Y = -0.07F;
	/**
	 * Peak nose-down tip, degrees.
	 *
	 * <p>Positive, which is the opposite sign to {@link #REST_PITCH_DEGREES}: the carry lays the
	 * device back so its face turns up, and the jab drives the far edge down into the blow.</p>
	 */
	private static final float SWING_PITCH_DEGREES = 13.0F;

	// --- one-handed fallback --------------------------------------------------------------------
	private static final float CARRIED_PITCH_DEGREES = 18.0F;
	private static final float CARRIED_ROLL_DEGREES = 6.0F;

	/**
	 * Field-of-view multiplier at full open.
	 *
	 * <p>Deliberately mild. A hard zoom on a screen the player is about to read makes the world
	 * behind it swim, and the terminal is opened dozens of times per session.</p>
	 */
	private static final float OPEN_FOV_SCALE = 0.88F;

	private TerminalHandheldPose() {
	}

	/**
	 * Eased 0..1 openness from a phase's elapsed time.
	 *
	 * @param opening true while raising, false while lowering
	 */
	public static double openness(boolean opening, long elapsedMillis) {
		double linear = TerminalMotion.elapsedProgress(elapsedMillis,
				opening ? OPEN_MILLIS : CLOSE_MILLIS);
		double eased = TerminalMotion.easeOutQuad(linear);
		return opening ? eased : 1.0D - eased;
	}

	/**
	 * The two-handed presentation at a given openness, with nothing being hit.
	 *
	 * @param openness  0 carried low, 1 raised and filling the frame. Clamped.
	 * @param nowMillis monotonic clock, for the idle breath only
	 */
	public static Presentation presentation(double openness, long nowMillis) {
		return presentation(openness, nowMillis, 0.0D);
	}

	/**
	 * The two-handed presentation at a given openness, mid-swing.
	 *
	 * @param openness  0 carried low, 1 raised and filling the frame. Clamped.
	 * @param nowMillis monotonic clock, for the idle breath only
	 * @param swing     vanilla's own 0..1 swing progress. Clamped. 0 for a hand doing nothing
	 */
	public static Presentation presentation(double openness, long nowMillis, double swing) {
		float open = (float) Math.clamp(openness, 0.0D, 1.0D);
		float rest = 1.0F - open;
		// The breath fades out as the device comes up. A terminal pressed against the lens that is
		// still bobbing reads as a camera fault rather than as a pair of hands.
		float breath = breathOffset(nowMillis) * rest;
		// So does the jab, and for a stronger reason than the breath: at full open the CRT is the
		// frame, and a jolt this size would throw the page the player is reading off the edge of it
		// and back. It also means the two things that raise the device - the opening performance and
		// a swing - can never add up into a motion neither of them describes.
		float jab = swingEnvelope(swing) * rest;
		float y = REST_Y + (OPEN_Y - REST_Y) * open + breath + SWING_DROP_Y * jab;
		float z = BASE_Z + OPEN_Z_GAIN * open + SWING_PUSH_Z * jab;
		float scale = REST_SCALE + (OPEN_SCALE - REST_SCALE) * open;
		// Where the device's bottom edge and side wall actually land on screen, converted into
		// world offsets at the hands' own depth. Handing the arms the device's world coordinates
		// instead put them at the right distance but the wrong place in frame, which is the grip
		// looking detached from the casing. Derived from the jabbed y and z, so the arms travel with
		// the shove instead of the casing sliding out of them.
		float grip = screenAligned(y - HALF_HEIGHT * scale - HAND_CLEARANCE, z);
		float side = screenAligned(HALF_WIDTH * scale + HAND_CLEARANCE, z);
		return new Presentation(
				y,
				z,
				REST_PITCH_DEGREES * rest + SWING_PITCH_DEGREES * jab,
				scale,
				grip + HAND_DROP_COMPENSATION,
				HAND_Z,
				Math.max(0.0F, side - VANILLA_HAND_HALF_SPAN),
				1.0F + (OPEN_FOV_SCALE - 1.0F) * open);
	}

	/**
	 * How far into the shove the rig is, from vanilla's linear swing progress.
	 *
	 * <p>{@code sin(sqrt(p) * PI)}, which is the curve vanilla's own attack transform uses and the
	 * reason a hit reads as impact rather than as a wave: it is already at full travel a quarter of
	 * the way through the swing and spends the remaining three quarters coming back. A symmetric
	 * curve on the same six ticks looks like the player is stirring something.</p>
	 *
	 * <p>Zero at both ends, so a hand that is not swinging and a hand that has just finished are the
	 * same pose, and nothing has to reset it.</p>
	 *
	 * @param swing vanilla's 0..1 swing progress. Clamped
	 */
	public static float swingEnvelope(double swing) {
		return (float) Math.sin(Math.sqrt(Math.clamp(swing, 0.0D, 1.0D)) * Math.PI);
	}

	/**
	 * The one-handed fallback pose, applied on top of whatever vanilla already did with the item.
	 *
	 * @param nowMillis monotonic clock, for the idle breath
	 * @param rightHand which hand holds it; the tilt is mirrored for the left
	 */
	public static Carried carried(long nowMillis, boolean rightHand) {
		float side = rightHand ? 1.0F : -1.0F;
		return new Carried(breathOffset(nowMillis), CARRIED_PITCH_DEGREES,
				side * CARRIED_ROLL_DEGREES);
	}

	/**
	 * Re-expresses an offset measured at the device's depth as the offset that lands on the same
	 * screen position at the hands' depth.
	 *
	 * <p>Under perspective, a world offset covers a screen angle proportional to offset / depth.
	 * Two things at different depths therefore need different world offsets to appear in the same
	 * place, and the device now sits much nearer the camera than the arms do. Passing the device's
	 * own numbers straight to the hands is what made the grip look detached: the arms were at a
	 * plausible distance and the wrong point in the frame.</p>
	 */
	private static float screenAligned(float offsetAtDevice, float deviceZ) {
		return offsetAtDevice * Math.abs(HAND_Z) / Math.abs(deviceZ);
	}

	/**
	 * What fraction of vanilla's walk bob the held terminal keeps, at a given openness.
	 *
	 * <p>Vanilla's bob is written for a small object on the end of an arm, where a hard swing at
	 * running pace reads as effort. The terminal is neither: it is large, centred, and the thing the
	 * player is trying to read. At full bob a sprinting player could not hold a line of the display
	 * still enough to follow, and the device - which is supposed to be braced in both hands - moved
	 * like something being swung.
	 *
	 * <p>Damped rather than removed. A terminal welded to the lens while the world bobs underneath
	 * it reads as a HUD overlay rather than as an object being carried, which is the whole illusion
	 * this class exists to protect. It damps further as the device comes up, on the same reasoning
	 * as the breath: what is pressed against the lens to be read should be steadier than what is
	 * being carried at the hip.
	 *
	 * @param openness 0 carried low, 1 raised and filling the frame. Clamped.
	 */
	public static float viewBobScale(double openness) {
		float open = (float) Math.clamp(openness, 0.0D, 1.0D);
		return CARRIED_BOB_SCALE + (OPEN_BOB_SCALE - CARRIED_BOB_SCALE) * open;
	}

	/** Bob kept while the terminal is held but down. */
	public static final float CARRIED_BOB_SCALE = 0.45F;
	/** Bob kept once it is raised and being read. */
	public static final float OPEN_BOB_SCALE = 0.18F;

	/** The standing breath, in blocks. Public so a test can bound it without re-deriving it. */
	public static float breathOffset(long nowMillis) {
		double phase = Math.floorMod(nowMillis, (long) BREATH_PERIOD_MILLIS) / BREATH_PERIOD_MILLIS;
		return (float) (Math.sin(phase * 2.0D * Math.PI) * BREATH_AMPLITUDE);
	}

	/**
	 * One frame of the two-handed presentation, in camera space.
	 *
	 * @param y          height of the device's centre, blocks
	 * @param z          depth of the device's centre, blocks; negative is in front of the camera
	 * @param pitch      degrees the face is tipped; negative lays it back, face up
	 * @param scale      uniform multiplier on the model
	 * @param handY      height the hands are drawn at, blocks
	 * @param handZ      depth the hands are drawn at; in front of the device's near face
	 * @param handSpread how far each hand is pushed outward from the centre line, blocks
	 * @param fovScale   multiplier on the camera's field of view, exactly 1 at rest
	 */
	public record Presentation(float y, float z, float pitch, float scale, float handY,
			float handZ, float handSpread, float fovScale) {
	}

	/**
	 * The adjustment applied to a one-handed carry.
	 *
	 * @param lift  vertical offset, blocks - the idle breath and nothing else
	 * @param pitch degrees the face is tipped away
	 * @param roll  degrees of tilt, so the device does not look mounted on a rail
	 */
	public record Carried(float lift, float pitch, float roll) {
	}
}
