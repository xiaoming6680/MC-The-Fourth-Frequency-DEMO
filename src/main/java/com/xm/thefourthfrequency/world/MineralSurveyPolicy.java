package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.terminal.TerminalResource;

/**
 * Pure rules for both mineral readings: the passive proximity survey and the requested probe.
 *
 * <p>The probe used to roll a category and then a sixty-percent failure, and cost nothing to
 * repeat. That made it a slot machine: a free re-roll meant a player who wanted diamond simply
 * pressed until diamond came up, and an unbounded search then handed over an exact block anywhere
 * in the world. The rules here remove the three things that made that work - the probe reports
 * what the ground around the player actually contains rather than what a die said, it only hears
 * as far as the ore's own radius, and pressing costs a charge that takes minutes to come back.</p>
 */
public final class MineralSurveyPolicy {
	/** Passive survey: how close the player must be for the terminal to notice an ore at all. */
	public static final int RANGE = 5;
	public static final int ARRIVAL_RADIUS = 1;

	/** Requested probe: charges held at once, and how long one takes to come back. */
	public static final int MAX_PROBE_CHARGES = 3;
	public static final long CHARGE_RECHARGE_TICKS = 3_600L;
	/** Ticks between pressing the probe and the reading resolving. */
	public static final long PROBE_REVEAL_TICKS = 60L;
	/**
	 * The floor on the exact-reading radius, and what every ore used to get.
	 *
	 * <p>An exact hit is what the player would have found within a few seconds of digging anyway,
	 * so handing it over reads as the instrument confirming something rather than doing the work.
	 * Past that the reading stays honest about being a reading.</p>
	 */
	public static final int MINIMUM_EXACT_READING_RADIUS = 12;
	/** Share of an ore's own hearing range within which the probe is willing to name the block. */
	public static final int EXACT_READING_PERCENT = 60;
	/** Half-width of the reported distance band, as a percentage of the true distance. */
	public static final int DISTANCE_BAND_PERCENT = 25;

	private MineralSurveyPolicy() {
	}

	public static boolean withinRange(int dx, int dy, int dz) {
		long distanceSquared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
		return distanceSquared <= (long) RANGE * RANGE;
	}

	/**
	 * What the passive survey is allowed to notice at all.
	 *
	 * <p>It used to notice every ore and then throw a thirty-percent die, which underground meant a
	 * banner every few seconds about coal the player was already standing in. Restricting it to the
	 * two ores worth stopping for makes the roll unnecessary: a survey hit is now rare enough to be
	 * worth reporting every single time, and rare enough that reporting it is not noise.</p>
	 */
	public static boolean surveyable(TerminalResource resource) {
		return resource == TerminalResource.DIAMOND || resource == TerminalResource.EMERALD;
	}

	public static boolean arrived(int dx, int dy, int dz) {
		long distanceSquared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
		return distanceSquared <= (long) ARRIVAL_RADIUS * ARRIVAL_RADIUS;
	}

	/**
	 * How far the probe can hear each ore.
	 *
	 * <p>Scaled against rarity so the tool cannot substitute for being in the right place: a
	 * diamond reading is only possible for a player who is already deep enough to be standing
	 * within sixteen blocks of diamond.</p>
	 */
	public static int probeRadius(TerminalResource resource) {
		return switch (resource) {
			case COAL -> 32;
			case IRON -> 28;
			case GOLD -> 20;
			case DIAMOND, EMERALD -> 16;
			case NONE -> 0;
		};
	}

	/** Which reading the terminal prefers to report when several are in range at once. */
	public static int reportPriority(TerminalResource resource) {
		return switch (resource) {
			case EMERALD -> 5;
			case DIAMOND -> 4;
			case GOLD -> 3;
			case IRON -> 2;
			case COAL -> 1;
			case NONE -> 0;
		};
	}

	/**
	 * The distance past which nothing rarer than {@code best} can still turn up, which is where an
	 * outward sweep may stop.
	 *
	 * <p>Passing {@link TerminalResource#NONE} yields the overall ceiling, because every unlocked
	 * ore outranks "nothing found". That makes one rule cover both the opening sweep and every
	 * later narrowing, and it is what keeps the scan cheap: a diamond hit ends it at sixteen
	 * blocks, and the full thirty-two-block sweep only happens when the ground is genuinely
	 * uninteresting.</p>
	 */
	public static int rarerCeiling(int unlockedResourceMask, TerminalResource best) {
		int bestPriority = reportPriority(best);
		int ceiling = 0;
		for (TerminalResource candidate : TerminalResource.values()) {
			if (candidate == TerminalResource.NONE || !unlocked(unlockedResourceMask, candidate)) continue;
			if (reportPriority(candidate) <= bestPriority) continue;
			ceiling = Math.max(ceiling, probeRadius(candidate));
		}
		return ceiling;
	}

	public static boolean unlocked(int unlockedResourceMask, TerminalResource resource) {
		return resource != TerminalResource.NONE
				&& (unlockedResourceMask & 1 << resource.wireId()) != 0;
	}

	/** True when a hit at this offset is close enough for the named ore to count as in range. */
	public static boolean withinProbeRadius(TerminalResource resource, int dx, int dy, int dz) {
		int radius = probeRadius(resource);
		long distanceSquared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
		return radius > 0 && distanceSquared <= (long) radius * radius;
	}

	/**
	 * How close the probe has to be to name the block, for this ore.
	 *
	 * <p>Scaled against the ore's own hearing range rather than fixed, because a single number
	 * cannot mean the same thing to coal and to diamond. Twelve blocks out of coal's thirty-two is
	 * about five percent of the volume the probe can hear, so pressing it on coal almost always
	 * returned a bearing - which is the "precise readings are hard to get" complaint, and it was
	 * loudest for exactly the ores a player probes most often.
	 *
	 * <p><b>The floor is not decoration.</b> Sixty percent of diamond's sixteen is under twelve, so
	 * a plain scaling would have made the rarest ores <em>worse</em> than the flat radius they have
	 * today - roughly forty percent of diamond hits are exact now, and scaling alone would have
	 * halved that. Taking the larger of the two raises the common ores without paying for it with
	 * the one reading the tool exists to give.
	 */
	public static int exactReadingRadius(TerminalResource resource) {
		int radius = probeRadius(resource);
		if (radius <= 0) return 0;
		return Math.max(MINIMUM_EXACT_READING_RADIUS, radius * EXACT_READING_PERCENT / 100);
	}

	public static boolean exactReading(TerminalResource resource, int dx, int dy, int dz) {
		int radius = exactReadingRadius(resource);
		long distanceSquared = (long) dx * dx + (long) dy * dy + (long) dz * dz;
		return radius > 0 && distanceSquared <= (long) radius * radius;
	}

	public static int bandMinimum(int distance) {
		int safe = Math.max(0, distance);
		return Math.max(1, safe - safe * DISTANCE_BAND_PERCENT / 100);
	}

	public static int bandMaximum(int distance) {
		int safe = Math.max(0, distance);
		return Math.max(bandMinimum(safe) + 1, safe + safe * DISTANCE_BAND_PERCENT / 100 + 1);
	}

	/**
	 * Snaps a true offset onto the nearest of the eight compass points the terminal can name.
	 *
	 * <p>Returned as a unit-ish vector rather than an angle so it feeds the same
	 * {@code TerminalNavigationMath.direction} the stronghold estimate already uses; the terminal
	 * has one vocabulary for bearings and this reading speaks it.</p>
	 */
	public static Bearing quantizeBearing(int dx, int dz) {
		if (dx == 0 && dz == 0) return new Bearing(0, 0);
		double step = Math.PI / 4.0D;
		double snapped = Math.round(Math.atan2(dz, dx) / step) * step;
		return new Bearing((int) Math.round(Math.cos(snapped) * 100.0D),
				(int) Math.round(Math.sin(snapped) * 100.0D));
	}

	/**
	 * Rolls a stored charge count forward to now.
	 *
	 * <p>Recharging is resolved on read instead of on a ticking service: a probe budget that only
	 * advances while something is watching it would stall for offline players and for anyone who
	 * puts the terminal away, which is the opposite of what a cooldown is for.</p>
	 */
	public static ChargeState charges(int storedCharges, long storedNextRechargeTick, long now) {
		int charges = Math.clamp(storedCharges, 0, MAX_PROBE_CHARGES);
		if (charges >= MAX_PROBE_CHARGES) return new ChargeState(MAX_PROBE_CHARGES, 0L);
		long safeNow = Math.max(0L, now);
		// A timer in the future by more than one full interval cannot have been written by this
		// world's clock - a restored backup or a shared record - so it is restarted rather than
		// trusted, which would otherwise strand the player without charges indefinitely.
		if (storedNextRechargeTick <= 0L || storedNextRechargeTick > safeNow + CHARGE_RECHARGE_TICKS) {
			return new ChargeState(charges, safeNow + CHARGE_RECHARGE_TICKS);
		}
		if (safeNow < storedNextRechargeTick) return new ChargeState(charges, storedNextRechargeTick);
		long gained = 1L + (safeNow - storedNextRechargeTick) / CHARGE_RECHARGE_TICKS;
		int restored = (int) Math.min(MAX_PROBE_CHARGES, charges + gained);
		return new ChargeState(restored, restored >= MAX_PROBE_CHARGES ? 0L
				: storedNextRechargeTick + gained * CHARGE_RECHARGE_TICKS);
	}

	public static ChargeState spend(ChargeState current, long now) {
		if (current.charges() <= 0) return current;
		int remaining = current.charges() - 1;
		// Spending from a full bank is what starts the clock; spending from a partial one leaves
		// the charge already in flight alone, so pressing twice cannot reset progress toward it.
		long next = current.nextRechargeTick() > 0L
				? current.nextRechargeTick() : Math.max(0L, now) + CHARGE_RECHARGE_TICKS;
		return new ChargeState(remaining, next);
	}

	public static int rechargeTicksRemaining(ChargeState state, long now) {
		if (state.charges() >= MAX_PROBE_CHARGES || state.nextRechargeTick() <= 0L) return 0;
		return (int) Math.clamp(state.nextRechargeTick() - Math.max(0L, now), 0L, CHARGE_RECHARGE_TICKS);
	}

	public record Bearing(int dx, int dz) {
	}

	/** {@code nextRechargeTick} is zero when the bank is full and no charge is in flight. */
	public record ChargeState(int charges, long nextRechargeTick) {
	}
}
