package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.pursuit.PursuitActivityProof;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import com.xm.thefourthfrequency.world.SurvivalMilestone;

public final class AnomalyIntensity {
	public static final long HEAT_RAMP_TICKS = 20L * 60L * 20L;
	public static final long LEGACY_TIER_RAMP_TICKS = 10L * 60L * 20L;
	public static final long MIN_STAGE_EXPOSURE_TICKS = 20L * 60L * 20L;
	public static final int REQUIRED_STAGE_SUCCESSES = 2;
	public static final long LOGIN_GRACE_TICKS = 3L * 60L * 20L;
	/**
	 * No longer used by the anomaly director; the pursuit cooldowns still build on it.
	 *
	 * <p>It used to be the ambient scheduler's per-crossing floor, and {@link
	 * AnomalyDimensionPolicy} explains why that had to stop being a rule about crossings. The
	 * constant stays because a pursuit's own post-transit cooldown is a different question - it is
	 * asked once at the end of a chase, not every time a player walks through a portal.
	 */
	public static final long DIMENSION_GRACE_TICKS = 90L * 20L;
	public static final long SIGNATURE_LEAD_TICKS = 30L * 20L;
	/**
	 * The wait after a draw where nothing could be started.
	 *
	 * <p>The scheduler used to charge a full interval for it. Every candidate can be refused by its
	 * own preflight - no wall to fracture, no light to take, no closed door, no safe path, no free
	 * slot in the layer - and a player in a boat, a tunnel or open water can refuse most of a stage
	 * pool at once. Spending ten to sixteen minutes on an event that did not happen meant the
	 * environments that satisfy the fewest conditions were also the ones charged the most for it.
	 *
	 * <p>Thirty seconds, the same figure {@code HimService} already uses for a failed placement and
	 * for the same reason: a refusal says nothing about the next attempt, because the player has
	 * moved since, and where they are standing is the entire input.
	 */
	public static final long FAILED_TRIGGER_RETRY_TICKS = 30L * 20L;

	private AnomalyIntensity() { }

	public static int progressionCeiling(boolean bound, int bandStage, int milestoneMask, int eyeSamples,
			int activityProofMask, long effectiveActivityTicks, boolean endingActive) {
		if (!bound) return 0;
		if (endingActive || eyeSamples > 0 || SurvivalMilestone.FOUND_STRONGHOLD.present(milestoneMask)) return 5;
		if (SurvivalMilestone.RETURNED_NETHER.present(milestoneMask)
				&& SurvivalMilestone.COLLECTED_BLAZE_RODS.present(milestoneMask)) return 4;
		if (SurvivalMilestone.IRON.present(milestoneMask)
				|| SurvivalMilestone.PREPARED_NETHER.present(milestoneMask)
				|| SurvivalMilestone.ENTERED_NETHER.present(milestoneMask)) return 3;
		if (bandStage > 0 || PursuitActivityProof.any(activityProofMask)
				|| effectiveActivityTicks >= PursuitProgressPolicy.FORM_ONE_ACTIVITY_FALLBACK_TICKS) return 2;
		return 1;
	}

	public static int progressedStage(int currentStage, int ceiling, long stageExposureTicks,
			int successfulAnomalies) {
		return progressedStage(currentStage, ceiling, stageExposureTicks, successfulAnomalies, false);
	}

	public static int progressedStage(int currentStage, int ceiling, long stageExposureTicks,
			int successfulAnomalies, boolean declaredSpeedrun) {
		int current = Math.clamp(currentStage, 0, 5);
		int limit = Math.clamp(ceiling, 0, 5);
		if (limit == 0) return 0;
		if (current == 0) return 1;
		if (current >= limit) return current;
		boolean ready = stageExposureTicks >= requiredExposureTicks(current, limit, declaredSpeedrun)
				&& successfulAnomalies >= REQUIRED_STAGE_SUCCESSES;
		return ready ? current + 1 : current;
	}

	/**
	 * A stage lagging two or more levels behind the mainline ceiling would otherwise pace the whole
	 * catalogue out of a normal playthrough, so the exposure requirement is halved until it catches up.
	 */
	public static long requiredExposureTicks(int currentStage, int ceiling) {
		return requiredExposureTicks(currentStage, ceiling, false);
	}

	/**
	 * The same rule, with the catch-up allowed to start one level earlier for a declared speedrunner.
	 *
	 * <p>This is a pacing correction, not a difficulty setting, and the difference matters because the
	 * answer behind it was given in the first minute and can never be changed. It does not add
	 * anomalies, remove them, change how hard any of them hit, or open a stage the mainline has not
	 * already unlocked - the ceiling is still the ceiling. All it does is stop a player who is about
	 * to reach the End in six hours from being metered out of the back half of a catalogue that was
	 * paced for someone taking thirty.
	 *
	 * <p>The system already infers this, from a lag of two. Reading the declared answer just means it
	 * does not have to wait for the evidence to accumulate before believing someone who said so.
	 */
	public static long requiredExposureTicks(int currentStage, int ceiling, boolean declaredSpeedrun) {
		int lagForCatchUp = declaredSpeedrun ? 1 : 2;
		return ceiling - currentStage >= lagForCatchUp
				? MIN_STAGE_EXPOSURE_TICKS / 2 : MIN_STAGE_EXPOSURE_TICKS;
	}

	public static int heatPercent(long tierOnlineTicks) {
		if (tierOnlineTicks <= 0L) return 0;
		if (tierOnlineTicks >= HEAT_RAMP_TICKS) return 100;
		return (int) (tierOnlineTicks * 100L / HEAT_RAMP_TICKS);
	}

	/**
	 * How long until the next ordinary anomaly, measured from the moment this one <em>starts</em>.
	 *
	 * <p>That measurement is why the bounds here are not the quiet the player actually gets. A
	 * sustained anomaly is minutes long and is subtracted from the interval it was drawn in, so a
	 * five-minute {@code metric_drift} inside a nine-minute roll leaves four minutes of world.
	 *
	 * <p>The stages do not lose the same amount to that. Stages 1-4 carry the sustained entries;
	 * stage 5's pool is {@code tier >= 4} plus two short ones, so <b>nothing</b> in it runs longer
	 * than the sixty seconds of {@code red_horizon}. The late game was therefore tightening
	 * twice over - a shorter nominal interval and almost none of it spent inside an anomaly - and the
	 * ramp read far steeper in play than these numbers do on the page.
	 *
	 * <p>Raised roughly two minutes across the board, with the last two stages taking the larger share
	 * so real quiet drifts from about twelve minutes down to about nine rather than falling off a
	 * cliff. The escalation is kept, deliberately: late anomalies should arrive more often than early
	 * ones. It just should not also be the point where each one occupies the least of the gap.
	 *
	 * @param heatPercent unused. Kept because the value is computed, stored and displayed per player,
	 *     and a signature that dropped it would read as though the pacing had never considered it.
	 */
	public static long intervalTicks(int tier, int heatPercent, int randomValue, boolean first) {
		return intervalTicks(tier, heatPercent, randomValue, first, false);
	}

	/**
	 * @param pressureDimension whether the player is somewhere the director runs hot - currently the
	 *     Nether, see {@link AnomalyDimensionPolicy}. A Nether trip is a bounded errand measured in
	 *     minutes, and the overworld cadence spends most of one waiting: a player who goes in for
	 *     rods, gets them and leaves could complete the whole mainline beat without the world once
	 *     misbehaving around them. The shorter interval keeps the stage ramp - it is the same table
	 *     moved down, not a flat number - so the Nether is louder at every stage rather than being
	 *     an exception to the progression.
	 */
	public static long intervalTicks(int tier, int heatPercent, int randomValue, boolean first,
			boolean pressureDimension) {
		if (tier <= 0) return Long.MAX_VALUE;
		if (first) return randomSeconds(5 * 60, 8 * 60, randomValue);
		int[] minimum = pressureDimension
				? new int[] {0, 6 * 60, 6 * 60, 5 * 60, 5 * 60, 5 * 60}
				: new int[] {0, 10 * 60, 10 * 60, 9 * 60, 8 * 60, 7 * 60};
		int[] maximum = pressureDimension
				? new int[] {0, 10 * 60, 9 * 60, 9 * 60, 8 * 60, 8 * 60}
				: new int[] {0, 16 * 60, 15 * 60, 14 * 60, 13 * 60, 12 * 60};
		int clampedTier = Math.clamp(tier, 1, 5);
		return randomSeconds(minimum[clampedTier], maximum[clampedTier], randomValue);
	}

	/**
	 * Applies one of the grace periods above as a floor rather than as a reschedule.
	 *
	 * <p>Both callers used to assign their grace outright, which is the opposite of what the word
	 * means: a player eight minutes into an eleven-minute interval who relogged came back with three
	 * minutes on the clock. It was a plain assignment where the intent was a minimum.
	 *
	 * <p>Only the login grace remains. Making the floor honest was not enough for the dimension one,
	 * because a floor reapplied on every portal crossing still starves a traveller - the schedule
	 * cannot run out while something keeps pushing it forward. That rule is now
	 * {@link AnomalyDimensionPolicy} instead, which asks what a dimension <em>is</em> rather than
	 * how recently the player entered it.
	 *
	 * <p>A grace may push the next anomaly away from a login. It may never pull one closer.
	 *
	 * <p>An unscheduled record stays unscheduled. Zero means "this player has never been scheduled",
	 * and converting it into a grace here would spend the opening-interval branch in {@code
	 * AmbientAnomalyService.updatePlayer} - the only path that uses the shorter first-anomaly
	 * window - on a player who has not had their first anomaly yet.
	 *
	 * @param scheduledTick the tick already on the record, or 0 if there is none
	 * @param earliestTick the soonest this grace is willing to let an anomaly arrive
	 */
	public static long graced(long scheduledTick, long earliestTick) {
		return scheduledTick <= 0L ? 0L : Math.max(scheduledTick, earliestTick);
	}

	public static long strongCooldownTicks(int tier, int randomValue) {
		return tier < 4 ? 0L : randomSeconds(20 * 60, 30 * 60, randomValue);
	}

	private static long randomSeconds(int minimum, int maximum, int randomValue) {
		int seconds = minimum + Math.floorMod(randomValue, maximum - minimum + 1);
		return seconds * 20L;
	}
}
