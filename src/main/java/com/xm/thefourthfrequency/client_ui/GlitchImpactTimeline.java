package com.xm.thefourthfrequency.client_ui;

/**
 * The shape of a corruption impact: the burst that ends peripheral_residue, and the one that
 * answers a strike on the fracture opened by phantom_echo.
 *
 * <p>The burst this replaces was a single envelope - one flat white fill, fourteen randomly
 * coloured bars and seven rows of obfuscated glyphs, every layer fading on the same linear ramp.
 * Because nothing decayed at a different rate to anything else the whole thing read as a fade
 * rather than as a failure, and neon bars over enchantment-table glyphs belonged to no other
 * surface in the mod.</p>
 *
 * <p>A signal that actually breaks does not fade. It is hit, it is lost, it comes back wrong,
 * and then it closes. Those four beats are all this file describes. It decides what a given tick
 * of the burst looks like - never when the burst happens, never how to draw it - so the whole
 * shape stays verifiable without a running client, the same way {@link AlphaLoadTimeline} keeps
 * the loading-screen sequence verifiable.</p>
 */
public final class GlitchImpactTimeline {
	/** Nine tenths of a second: long enough to hold four beats, short enough to still be a shock. */
	public static final int IMPACT_TICKS = 18;
	/** Beat one ends. The hit: the picture tears, and anything drawn over it tears with it. */
	public static final int HIT_END_TICK = 3;
	/** The signal is struck a second time on the way down, so the decay is never a straight line. */
	public static final int SECOND_HIT_TICK = 5;
	/** Beat two ends. Carrier lost: the tear collapses into dark, lit only by its own residue. */
	public static final int LOSS_END_TICK = 7;
	/** Beat three ends. The picture is back, rolling and mistracked. */
	public static final int GHOST_END_TICK = 13;
	/** How long torn remnants of an overlay survive inside the tear before it finishes eating them. */
	public static final int DEBRIS_TICKS = 3;
	/** Horizontal bands the picture is torn into. */
	public static final int SLICES = 18;

	private static final int PEAK_BLEACH_ALPHA = 208;
	private static final int PEAK_DROPOUT_ALPHA = 202;
	private static final int DROPOUT_START_TICK = 2;
	private static final int DROPOUT_END_TICK = 9;
	private static final int PEAK_SCANLINE_ALPHA = 54;
	private static final float PEAK_CHROMA_OFFSET = 7.0F;
	private static final float MAX_SLICE_SHIFT_FRACTION = 0.30F;
	private static final int PEAK_COLLAPSE_ALPHA = 224;
	private static final float COLLAPSE_PULL_START = 0.35F;

	private GlitchImpactTimeline() {
	}

	public static boolean active(int tick) {
		return tick >= 0 && tick < IMPACT_TICKS;
	}

	/**
	 * One frame of overexposure on the hit, effectively gone by the third tick.
	 *
	 * <p>Squared falloff rather than linear: the flash has to land as a single struck frame the
	 * player cannot quite replay, not as a white screen they get to sit and look at.</p>
	 */
	public static int bleachAlpha(int tick) {
		if (tick < 0 || tick >= HIT_END_TICK) return 0;
		float remaining = 1.0F - tick / (float) HIT_END_TICK;
		return Math.round(PEAK_BLEACH_ALPHA * remaining * remaining);
	}

	/**
	 * The screen losing the picture outright, between the hit and the ghost.
	 *
	 * <p>This is the beat the old burst had no equivalent of, and the reason it never frightened
	 * anyone: brightness alone is an effect, but brightness followed by nothing at all is a
	 * failure. The dark is what the torn residue is legible against.</p>
	 */
	public static int dropoutAlpha(int tick) {
		if (tick < DROPOUT_START_TICK || tick >= DROPOUT_END_TICK) return 0;
		if (tick < 4) return Math.round(PEAK_DROPOUT_ALPHA * (tick - DROPOUT_START_TICK) / 2.0F);
		if (tick <= 6) return PEAK_DROPOUT_ALPHA;
		return Math.round(PEAK_DROPOUT_ALPHA * (1.0F - (tick - 6) / 3.0F));
	}

	/** How hard the picture is torn. Deliberately not monotonic - see {@link #SECOND_HIT_TICK}. */
	public static float tearStrength(int tick) {
		if (tick < 0 || tick >= GHOST_END_TICK) return 0.0F;
		if (tick < HIT_END_TICK) return 1.0F - 0.18F * (tick / (float) HIT_END_TICK);
		if (tick < SECOND_HIT_TICK) return 0.34F;
		if (tick < LOSS_END_TICK) return 0.62F - 0.16F * (tick - SECOND_HIT_TICK);
		return 0.26F * (1.0F - (tick - LOSS_END_TICK) / (float) (GHOST_END_TICK - LOSS_END_TICK));
	}

	/**
	 * Which of the analog-signal post chains the world picture wears on this tick, 1 to 4, or 0 for
	 * none.
	 *
	 * <p>The burst used to exist entirely in the overlay, which meant it could only ever paint
	 * <em>over</em> the world - and paint is the one thing a torn signal never does. The picture
	 * itself has to bend, and bending it needs a shader, and a post chain's uniforms are fixed when
	 * it loads. So the strength is quantised into four chains and this says which one.
	 *
	 * <p>Derived from {@link #tearStrength} rather than from the tick, so the filter follows the same
	 * four beats the overlay does - including the second hit, where it steps back <em>up</em>. Two
	 * treatments on independent envelopes would read as two things going wrong near each other.
	 */
	public static int signalStep(int tick) {
		if (!active(tick)) return 0;
		// The closing beat keeps the weakest step rather than dropping to nothing: the medium is
		// still there after the picture has gone, and it is what the collapse closes on.
		if (tick >= GHOST_END_TICK) return 1;
		float tear = tearStrength(tick);
		if (tear >= 0.75F) return 4;
		if (tear >= 0.45F) return 3;
		if (tear >= 0.20F) return 2;
		return 1;
	}

	/** How far the red and cyan ghosts sit either side of everything the tear touches. */
	public static float chromaOffset(int tick) {
		if (tick < 0 || tick >= GHOST_END_TICK) return 0.0F;
		return PEAK_CHROMA_OFFSET * (1.0F - tick / (float) GHOST_END_TICK);
	}

	/**
	 * Scanlines arrive after the hit rather than during it.
	 *
	 * <p>They are the medium showing through once the picture is too weak to hide it, so putting
	 * them on the brightest frames - which is what a single shared envelope does - throws away
	 * the one moment they mean something.</p>
	 */
	public static int scanlineAlpha(int tick) {
		if (tick < HIT_END_TICK || tick >= IMPACT_TICKS) return 0;
		if (tick < LOSS_END_TICK) return Math.round(PEAK_SCANLINE_ALPHA
				* (tick - HIT_END_TICK) / (float) (LOSS_END_TICK - HIT_END_TICK));
		if (tick < GHOST_END_TICK) return PEAK_SCANLINE_ALPHA;
		return Math.round(PEAK_SCANLINE_ALPHA
				* (1.0F - (tick - GHOST_END_TICK) / (float) (IMPACT_TICKS - GHOST_END_TICK)));
	}

	public static int rollBandHeight(int viewportHeight) {
		return Math.max(3, viewportHeight / 22);
	}

	/**
	 * Top of the mistracked band sweeping up the recovered picture, or {@link Integer#MIN_VALUE}
	 * while there is no band to draw.
	 */
	public static int rollBandTop(int tick, int viewportHeight) {
		if (tick < LOSS_END_TICK || tick >= IMPACT_TICKS || viewportHeight <= 0) return Integer.MIN_VALUE;
		int span = viewportHeight + rollBandHeight(viewportHeight);
		int speed = Math.max(4, viewportHeight / 5);
		return viewportHeight - Math.floorMod((tick - LOSS_END_TICK) * speed, span);
	}

	public static boolean collapsing(int tick) {
		return tick >= GHOST_END_TICK && tick < IMPACT_TICKS;
	}

	/** 0 to 1 across the closing beat. */
	public static float collapseProgress(int tick) {
		if (!collapsing(tick)) return 0.0F;
		return (tick - GHOST_END_TICK) / (float) (IMPACT_TICKS - GHOST_END_TICK);
	}

	/**
	 * Half-height of the closing line: the picture squeezed into a slot, then into nothing.
	 *
	 * <p>The burst has to end on a definite frame. Letting the last layer fade out means the
	 * player never sees it stop, only notices later that it has, and the anomaly loses the one
	 * instant that tells them the transmission is over.</p>
	 */
	public static int collapseHalfHeight(int tick, int viewportHeight) {
		if (!collapsing(tick)) return 0;
		return Math.max(1, Math.round(viewportHeight * 0.05F * (1.0F - collapseProgress(tick))));
	}

	/**
	 * Depth of the dark pinching in above and below the closing line.
	 *
	 * <p>A bright bar on its own is just a bright bar. What sells a picture collapsing is the
	 * dark arriving from both edges to meet it - but only enough of it to be read as pressure on
	 * the line, never enough to take the player's view away a second time.</p>
	 */
	public static int collapsePinchHeight(int tick, int viewportHeight) {
		if (!collapsing(tick)) return 0;
		return Math.max(1, Math.round(viewportHeight * 0.055F * collapseProgress(tick)));
	}

	/** How far the closing line has pulled in from either edge. */
	public static int collapseInset(int tick, int viewportWidth) {
		if (!collapsing(tick)) return 0;
		float pull = Math.max(0.0F, (collapseProgress(tick) - COLLAPSE_PULL_START)
				/ (1.0F - COLLAPSE_PULL_START));
		return Math.round(viewportWidth * 0.5F * pull * pull);
	}

	public static int collapseAlpha(int tick) {
		if (!collapsing(tick)) return 0;
		return Math.round(PEAK_COLLAPSE_ALPHA * (1.0F - collapseProgress(tick) * 0.45F));
	}

	/** What is left of an overlay that happened to be on screen when the tear hit. */
	public static float debrisStrength(int tick) {
		if (tick < 0 || tick >= DEBRIS_TICKS) return 0.0F;
		return 1.0F - tick / (float) DEBRIS_TICKS;
	}

	/** Top edge of a torn band. Slice {@code SLICES} returns the bottom of the viewport. */
	public static int sliceTop(int slice, int viewportHeight) {
		return Math.clamp(slice, 0, SLICES) * viewportHeight / SLICES;
	}

	/** How far one band is dragged sideways, signed. */
	public static int sliceShift(int slice, int tick, int viewportWidth, float strength) {
		int span = Math.max(2, Math.round(viewportWidth * MAX_SLICE_SHIFT_FRACTION
				* Math.clamp(strength, 0.0F, 1.0F)));
		int seed = AlphaLoadTimeline.noise(slice * 0x9E3779B9 + tick * 0x85EBCA6B);
		return Math.floorMod(seed, span * 2 + 1) - span;
	}

	/** Bands that carry no picture at all this tick, only the dark behind it. */
	public static boolean sliceLost(int slice, int tick, float strength) {
		int seed = AlphaLoadTimeline.noise(slice * 0x27D4EB2F + tick * 0x165667B1 + 0x2545F491);
		return Math.floorMod(seed >>> 6, 100) < Math.round(Math.clamp(strength, 0.0F, 1.0F) * 42.0F);
	}

	public static int sliceStreakLeft(int slice, int tick, int viewportWidth) {
		int seed = AlphaLoadTimeline.noise(slice * 0x1B873593 + tick * 0xCC9E2D51);
		return Math.floorMod(seed, Math.max(1, viewportWidth)) - viewportWidth / 3;
	}

	public static int sliceStreakWidth(int slice, int tick, int viewportWidth) {
		int seed = AlphaLoadTimeline.noise(slice * 0x6C078965 + tick * 0x9E3779B1);
		return viewportWidth / 5 + Math.floorMod(seed >>> 3, Math.max(1, viewportWidth * 2 / 3));
	}
}
