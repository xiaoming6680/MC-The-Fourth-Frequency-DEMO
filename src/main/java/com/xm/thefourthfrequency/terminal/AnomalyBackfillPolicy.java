package com.xm.thefourthfrequency.terminal;

/**
 * When the terminal stops pretending the anomalies did not happen.
 *
 * <p>Every completed anomaly is already written to the quarantined {@code ANOMALY_LOGS} store the
 * moment it ends. Nothing reads that store until this latch is set; from then on the records page
 * lists all of it at once, oldest first, and keeps listing it.
 *
 * <p>The trigger is the first recorded eye-of-ender bearing rather than reaching anomaly stage 5.
 * Both land in the same part of the curve, but the bearing is a mainline event with an exact tick,
 * so it can be asserted by a GameTest and reached on demand; stage 5 arrives out of a pacing
 * calculation that depends on how long the player happened to stay logged in. This moment should be
 * the same moment for everyone who gets there.
 *
 * <p>Pure and on the common side: the latch is authoritative server state, and the client is only
 * ever told whether it is set.
 */
public final class AnomalyBackfillPolicy {
	/**
	 * Entries the quarantined store keeps.
	 *
	 * <p>Raised from the 32 the store shipped with. Thirty-two was sized for a rolling recent-events
	 * buffer nobody read; the backfill turns the same list into a full account of one playthrough,
	 * and a player who reaches the End has usually cleared eighty anomalies. Losing the early ones -
	 * the quiet, deniable ones from stage 1 - would cut exactly the half that makes the list land.
	 */
	public static final int MAX_ENTRIES = 160;

	private AnomalyBackfillPolicy() {
	}

	/**
	 * Whether a completed anomaly should be appended to the quarantined store.
	 *
	 * <p>Always. Stated as a method rather than inlined at the call site because the write happens
	 * long before anything can read it, which is exactly the shape of code that gets "optimised" out
	 * by someone who checks whether the value is used.
	 */
	public static boolean retained(String anomalyId) {
		return anomalyId != null && !anomalyId.isBlank() && AnomalyCatalog.contains(anomalyId);
	}

	/** Whether the records page merges the quarantined store into its list. */
	public static boolean released(boolean latchSet) {
		return latchSet;
	}

	/**
	 * Whether this mainline moment sets the latch.
	 *
	 * @param recordedBearings eye-of-ender bearings the player has actually recorded
	 */
	public static boolean shouldRelease(boolean latchAlreadySet, int recordedBearings) {
		return !latchAlreadySet && recordedBearings >= 1;
	}
}
