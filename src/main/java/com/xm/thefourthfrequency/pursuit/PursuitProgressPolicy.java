package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.world.SurvivalMilestone;

/** Pure progression rules shared by the director, terminal and persistence tests. */
public final class PursuitProgressPolicy {
	public static final int FORM_COUNT = 5;
	public static final int REQUIRED_EYE_SAMPLES = 3;
	/** Upper bound for the stored encounter counter, which is now a record rather than a gate. */
	public static final int MAX_TRACKED_ENCOUNTERS = 99;
	public static final long WARNING_LEAD_TICKS = 10L * 20L;
	public static final long FORM_ONE_ACTIVITY_FALLBACK_TICKS = 20L * 60L * 20L;
	public static final long MIN_CHASE_GAP_TICKS = 20L * 60L * 20L;
	public static final long MAX_CHASE_GAP_TICKS = 30L * 60L * 20L;
	public static final double HEART_HEALTH_POINTS = 2.0D;
	/** Captures stop draining maximum health at six hearts; escapes always restore. */
	public static final double CAPTURE_PENALTY_FLOOR_HEALTH = 12.0D;

	private PursuitProgressPolicy() {
	}

	public static boolean earlyFormEligible(boolean bound, int successfulAnomalies,
			int activityProofMask, long effectiveActivityTicks) {
		boolean activityRoute = PursuitActivityProof.any(activityProofMask)
				|| effectiveActivityTicks >= FORM_ONE_ACTIVITY_FALLBACK_TICKS;
		return bound && successfulAnomalies > 0 && activityRoute;
	}

	public static int allowedForm(int milestones, int eyeSamples, boolean earlyFormEligible) {
		if (SurvivalMilestone.FOUND_STRONGHOLD.present(milestones)) return 5;
		if (Math.max(0, eyeSamples) >= REQUIRED_EYE_SAMPLES) return 4;
		if (SurvivalMilestone.RETURNED_NETHER.present(milestones)
				&& SurvivalMilestone.COLLECTED_BLAZE_RODS.present(milestones)) return 3;
		if (SurvivalMilestone.ENTERED_NETHER.present(milestones)) return 2;
		return earlyFormEligible ? 1 : 0;
	}

	public static int actualForm(int resolvedChases) {
		return Math.min(Math.clamp(resolvedChases, 0, FORM_COUNT) + 1, FORM_COUNT);
	}

	public static boolean complete(int resolvedChases) {
		return resolvedChases >= FORM_COUNT;
	}

	/**
	 * Counts a chase the player lived through, whichever way it resolved.
	 *
	 * <p>No longer gates anything. The final Eye used to refuse a player with none of these, which
	 * put a per-player condition on an action whose consequences land on the whole world - see
	 * {@code EnderEyeItemMixin}. The counter is kept because "how many of these has this person been
	 * through" belongs in the record on its own merits, and dropping a persisted field would cost a
	 * migration for nothing.</p>
	 */
	public static int encounteredAfterResolution(int encounteredChases) {
		return Math.min(Math.max(0, encounteredChases) + 1, MAX_TRACKED_ENCOUNTERS);
	}

	/**
	 * A story jump creates at most one pending pursuit. Further pursuits require a later
	 * story-threshold transition, so returning players never receive catch-up debt.
	 */
	public static boolean pendingAfterAllowedFormUpdate(boolean alreadyPending, int previousAllowedForm,
			int newAllowedForm, int resolvedChases) {
		if (alreadyPending) return true;
		int previous = Math.clamp(previousAllowedForm, 0, FORM_COUNT);
		int current = Math.clamp(newAllowedForm, 0, FORM_COUNT);
		return current > previous && !complete(resolvedChases) && actualForm(resolvedChases) <= current;
	}

	/**
	 * Permission, progress and cooldown. Deliberately no longer a demonstration gate.
	 *
	 * <p>A {@code tutorialReady} leg used to sit here, and it meant "at least one anomaly has fired
	 * since this form became the current one" rather than anything scripted. Its effect was that a
	 * form could never arrive un-previewed, which is exactly the mercy the mod no longer wants: the
	 * first encounter with a form is allowed to be the real pursuit. Anomalies still demonstrate what
	 * is coming, they just do not hold the door.
	 *
	 * <p>The cooldown leg stays. It is a pacing floor, not a preview, and removing it would let two
	 * pursuits stack back to back.
	 */
	public static boolean canStart(boolean pending, int allowedForm, int resolvedChases,
			long now, long nextEligibleTick) {
		return pending
				&& !complete(resolvedChases)
				&& actualForm(resolvedChases) <= Math.clamp(allowedForm, 0, FORM_COUNT)
				&& now >= Math.max(0L, nextEligibleTick);
	}

	public static int resolvedAfterSuccess(int resolvedChases) {
		return Math.min(Math.max(0, resolvedChases) + 1, FORM_COUNT);
	}

	/**
	 * A player who outran several story gates keeps only the immediately next pursuit.
	 * The long cooldown and that form's tutorial still apply, so this is not a catch-up queue.
	 */
	public static boolean pendingAfterSuccess(int resolvedChases, int allowedForm) {
		return !complete(resolvedChases)
				&& actualForm(resolvedChases) <= Math.clamp(allowedForm, 0, FORM_COUNT);
	}

	/**
	 * A struggling player already loses the chase itself; grinding their maximum health toward one
	 * heart only made the next chase and the finale harder for whoever needed help most. Below the
	 * floor a capture costs nothing further, while escapes keep restoring hearts as before.
	 */
	public static double resolutionMaxHealthDelta(boolean captured, double currentMaxHealth) {
		if (!captured) return HEART_HEALTH_POINTS;
		return currentMaxHealth <= CAPTURE_PENALTY_FLOOR_HEALTH ? 0.0D : -HEART_HEALTH_POINTS;
	}

	public static int terminalVisualStage(int resolvedChases, int allowedForm, int anomalyStage) {
		if (resolvedChases >= 3 && allowedForm >= 4 && anomalyStage >= 4) return 2;
		return resolvedChases >= 1 ? 1 : 0;
	}
}
