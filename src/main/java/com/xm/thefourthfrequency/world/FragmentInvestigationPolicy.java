package com.xm.thefourthfrequency.world;

/**
 * The single rule for whether the terminal is offering the unstable investigation.
 *
 * <p>Pure and on its own so the two properties that actually broke can be asserted.
 *
 * <p><b>The offer may never be withdrawn by the player making progress.</b> It used to be, and not
 * through anything anyone decided - the gate read {@code guidanceHintTier >= 2}, which is a measure
 * of how long the player has been <em>stalled</em> and is reset to zero on every objective step. So
 * the navigation option surfaced after five minutes of being stuck and disappeared the instant the
 * player mined the next iron ore, while the records line announcing the lead stayed on screen for
 * the rest of the run.
 *
 * <p><b>The offer must stand for exactly as long as the records page announces it.</b> The gate then
 * required {@code ENTERED_NETHER}, but the records line is written as soon as a lead is filed - band
 * stage above zero, navigator unlocked, which is day one of a normal run. A player who had not yet
 * built a portal therefore read "optional investigation: suspicious signal at ..." with an
 * {@code [open navigation]} shortcut beside it, opened the navigator, and found no such destination;
 * pressing the shortcut was refused in silence. Announcing a lead the player cannot follow is the
 * one thing the records page is not allowed to do, so the Nether is no longer part of this answer.
 *
 * <p>Every input below is monotone: a band stage does not fall, and a candidate stops being
 * undiscovered only by being discovered - which is the player resolving the lead, the one withdrawal
 * that is honest.
 */
public final class FragmentInvestigationPolicy {
	private FragmentInvestigationPolicy() {
	}

	/**
	 * @param bandStage the terminal's band stage; zero means the receiver has never resolved anything
	 * @param fragmentAlreadySelected a fragment is already the navigation target and located, which
	 *                                keeps the offer standing on its own so an in-progress route
	 *                                cannot be revoked underneath the player
	 * @param hasUndiscoveredCandidate at least one lead anywhere is still unresolved
	 */
	public static boolean offered(int bandStage, boolean fragmentAlreadySelected,
			boolean hasUndiscoveredCandidate) {
		if (bandStage <= 0) return false;
		return fragmentAlreadySelected || hasUndiscoveredCandidate;
	}
}
