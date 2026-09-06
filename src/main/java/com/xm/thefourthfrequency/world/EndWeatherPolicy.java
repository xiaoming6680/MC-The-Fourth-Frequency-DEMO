package com.xm.thefourthfrequency.world;

import net.minecraft.util.Mth;

/**
 * The End rains, and this decides how hard and how often it thunders.
 *
 * <p>Pure: no level, no client, no state. The rain curve is a function of how long the player has
 * been in the dimension and of the world clock, and the thunder schedule is a function of the world
 * clock alone - so the server and every client agree about the weather without a packet, a restart
 * resumes it rather than restarting it, and both halves are directly testable.
 *
 * <p><b>Why the End can rain at all.</b> Vanilla already carries a rain level for it: since the
 * dimension type gained {@code has_skylight}, the End tracks the same rain value the Overworld
 * does. What stops the drops appearing is the biome, which declares no precipitation, so the
 * renderer resolves every column to {@code NONE} and returns before it draws anything. Neither of
 * those is a rule the fiction depends on - they are two switches, and this feature turns them the
 * other way round on the client that is looking at it.
 *
 * <p><b>It is presentation, and only presentation.</b> Nothing here reaches the server's weather
 * state, and it must not: rain, thunder and their timers live in {@code ServerLevelData}, which is
 * one record shared by every dimension in the save. Making the End rain by writing that would be
 * making the <em>Overworld</em> rain, permanently, as a side effect of the End's atmosphere - which
 * is exactly the class of change the world bible refuses. The client sets its own level's rain
 * level in the End and nowhere else.
 *
 * <p>The thunder is client-side too, and still lands on the same tick for everybody, because the
 * schedule below is a function of the world clock and every client already has that clock. A packet
 * saying "thunder now" would be a second, less reliable copy of a clock that works - the same
 * reasoning the encounter uses for every beat it derives rather than sends. It is also the only
 * shape that plays each clap once: a server-side emitter would have to place the sound per listener
 * to give it a bearing, and a placed sound is heard by everyone near it, so eight players standing
 * together would hear eight claps.
 */
public final class EndWeatherPolicy {
	/**
	 * How hard it rains at the middle of the breath, as vanilla's 0..1 rain level.
	 *
	 * <p>Under the 1.0 a vanilla thunderstorm reaches. The End is where the finale is fought, and
	 * the fight is read through a screen already carrying a HUD, a lock treatment and, on the frames
	 * a beam lands, a dispersion filter. Rain is atmosphere here; it is not allowed to become one
	 * more thing between the player and the arena.
	 */
	public static final float BASE_RAIN_LEVEL = 0.55F;
	/** Half the peak-to-peak swing of the breath. */
	public static final float RAIN_SWING = 0.10F;
	/** Ticks one full breath takes. Slow on purpose: weather that pulses is a strobe, not weather. */
	public static final int RAIN_BREATH_TICKS = 1100;
	/**
	 * Ticks the rain takes to arrive after entering the End.
	 *
	 * <p>There is no matching fade out, and there is nowhere to put one: leaving the dimension
	 * destroys the {@code ClientLevel} the rain level was written to, so there is nothing left to
	 * fade. Three seconds in is the whole of the curve, and it exists so that arriving through a
	 * portal is weather starting rather than weather being switched on.
	 */
	public static final int FADE_IN_TICKS = 60;
	/**
	 * Ticks the storm takes to leave once the interface has been beaten.
	 *
	 * <p>Ten seconds, and it is the one fade out this curve has. The storm belongs to the thing that
	 * was in the sky: it arrived with the End that had a finale waiting in it, and once the interface
	 * is dead the weather it was atmosphere for has nothing left to be atmosphere for. Leaving it
	 * running over an open exit portal makes the win read as an intermission.
	 *
	 * <p>Long enough to be watched rather than switched off, and short enough to be finished before a
	 * player who beat the fight has walked to the portal. A defeat is deliberately not covered: the
	 * world is collapsing on that path and its sky is not something the player has earned.
	 */
	public static final int CLEAR_FADE_OUT_TICKS = 200;

	/**
	 * Length of one thunder slot, in ticks.
	 *
	 * <p>The schedule is slotted rather than sampled per tick so it can be stateless and still be
	 * irregular: each slot independently decides whether it thunders and, if it does, on which of
	 * its own ticks. A per-tick probability would give the same average with no way to ask "when is
	 * the next one" and no way to test the spacing.
	 */
	public static final int THUNDER_SLOT_TICKS = 600;
	/** Share of slots that actually thunder, out of 256. */
	private static final int THUNDER_SLOT_SHARE = 179;
	/**
	 * How much of a slot's tail is left free of thunder.
	 *
	 * <p>Without it two adjacent slots can land their strikes back to back across the boundary - the
	 * end of one and the start of the next - and a double clap reads as a bug rather than as
	 * weather. Reserving the last eighth of every slot puts a floor under the gap.
	 */
	private static final int THUNDER_TAIL_GUARD = THUNDER_SLOT_TICKS / 8;

	private EndWeatherPolicy() {
	}

	/**
	 * The rain level a client should be showing, given how long it has been in the End.
	 *
	 * @param ticksInDimension ticks since the player entered the End
	 * @param gameTime         the world clock, which drives the breath
	 */
	public static float rainLevel(long ticksInDimension, long gameTime) {
		return rainLevel(ticksInDimension, gameTime, -1L);
	}

	/**
	 * The same curve, with the storm's departure folded in.
	 *
	 * @param ticksSinceCleared ticks since the interface was beaten, or negative while it has not
	 *                          been. Passing {@link #CLEAR_FADE_OUT_TICKS} or more is a sky that has
	 *                          already finished clearing, which is what a player re-entering the End
	 *                          after the win must see rather than a storm that fades out again.
	 */
	public static float rainLevel(long ticksInDimension, long gameTime, long ticksSinceCleared) {
		return breathing(gameTime)
				* Mth.clamp(ticksInDimension / (float) FADE_IN_TICKS, 0.0F, 1.0F)
				* (1.0F - clearedFraction(ticksSinceCleared));
	}

	/** How far through its departure the storm is: 0 while it is still the fight's, 1 once gone. */
	public static float clearedFraction(long ticksSinceCleared) {
		if (ticksSinceCleared < 0L) return 0.0F;
		return Mth.clamp(ticksSinceCleared / (float) CLEAR_FADE_OUT_TICKS, 0.0F, 1.0F);
	}

	/** The slow swell the rain sits on, with no fade applied. Always inside {@code (0, 1]}. */
	public static float breathing(long gameTime) {
		double phase = (gameTime % RAIN_BREATH_TICKS) / (double) RAIN_BREATH_TICKS * Math.PI * 2.0D;
		return Mth.clamp(BASE_RAIN_LEVEL + RAIN_SWING * (float) Math.sin(phase), 0.05F, 1.0F);
	}

	/** Whether a clap of thunder starts on this exact world tick. */
	public static boolean isThunderTick(long gameTime) {
		if (gameTime < 0L) return false;
		long slot = Math.floorDiv(gameTime, THUNDER_SLOT_TICKS);
		long mixed = mix(slot);
		if ((mixed & 0xFFL) >= THUNDER_SLOT_SHARE) return false;
		long offset = ((mixed >>> 8) & 0x7FFFFFFFL) % (THUNDER_SLOT_TICKS - THUNDER_TAIL_GUARD);
		return Math.floorMod(gameTime, (long) THUNDER_SLOT_TICKS) == offset;
	}

	/**
	 * Pitch for the clap on this tick, in vanilla's 0.5..2.0 range.
	 *
	 * <p>Scattered per slot rather than fixed. Thunder is one sample, and one sample played at one
	 * pitch fifteen times over a ten-minute fight stops being weather and becomes a loop the player
	 * can hear repeating. Kept low: the End's sky is a long way up.
	 */
	public static float thunderPitch(long gameTime) {
		long slot = Math.floorDiv(gameTime, THUNDER_SLOT_TICKS);
		return 0.62F + ((mix(slot + 0x51ED2701L) >>> 24) & 0xFF) / 255.0F * 0.28F;
	}

	/** Bearing, in radians, that a clap is heard from. Scattered so the sky is not one loudspeaker. */
	public static float thunderBearing(long gameTime) {
		long slot = Math.floorDiv(gameTime, THUNDER_SLOT_TICKS);
		return ((mix(slot + 0x2F1B3C4DL) >>> 16) & 0xFFFF) / 65535.0F * (float) (Math.PI * 2.0D);
	}

	private static long mix(long value) {
		long mixed = value * 0x9E3779B97F4A7C15L;
		mixed ^= mixed >>> 29;
		mixed *= 0xBF58476D1CE4E5B9L;
		mixed ^= mixed >>> 32;
		return mixed & Long.MAX_VALUE;
	}
}
