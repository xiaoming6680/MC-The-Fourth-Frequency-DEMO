package com.xm.thefourthfrequency.ending;

/**
 * How long the summon plate has to be held down before it commits.
 *
 * <p>The summon is the least reversible thing in the mod. It closes a roster of up to eight
 * people, takes their surrendered terminals out of the safe-return queue, and starts a fight in
 * the real End that leaves marks on the world either way. Up to that press everything is
 * withdrawable; after it nothing is.
 *
 * <p>It was one click. One click is what a page tab costs, and a control that ends a party's
 * playthrough should not cost the same gesture as changing page - not because a player might press
 * it by accident, though they might, but because the gesture should feel like what it does. So the
 * plate is pushed to the bottom of its travel and held there: the press is acknowledged
 * immediately, the plate fills while the button is down, and the commit lands when it is full.
 *
 * <p>Letting go before then cancels, and cancelling sends nothing at all. There is no confirmation
 * dialog anywhere in this: a dialog asks the player to agree with a sentence, and a held control
 * asks them to keep doing the thing, which is the same guard without a second screen.
 *
 * <h2>The server never learns any of this</h2>
 *
 * <p>A completed hold sends exactly the request one click used to send, and the server validates it
 * exactly as before - roster membership, stage, deposit, the atomic commit. This is presentation.
 * A client that skips the hold entirely gains nothing it did not already have.
 */
public final class SummonHoldPolicy {
	/**
	 * The travel, in milliseconds.
	 *
	 * <p>Long enough that it cannot be produced by a click - nobody holds a mouse button for a
	 * second by accident - and short enough that a player who means it is not being made to wait
	 * out a progress bar. Anything past about two seconds stops reading as a heavy switch and
	 * starts reading as the game asking whether they are sure.
	 */
	public static final long HOLD_MILLIS = 1_200L;

	private SummonHoldPolicy() {
	}

	/** How far the plate has travelled, 0 to 1. */
	public static float progress(long heldMillis) {
		if (heldMillis <= 0L) return 0.0F;
		return (float) Math.clamp(heldMillis / (double) HOLD_MILLIS, 0.0D, 1.0D);
	}

	public static boolean complete(long heldMillis) {
		return heldMillis >= HOLD_MILLIS;
	}

	/**
	 * Whether letting go early is a commit.
	 *
	 * <p>It is not, and this exists so that the answer has somewhere to be asserted rather than
	 * living only as the absence of a call in a release handler. "Release commits" is the shape a
	 * plain button has, and it is the shape this would quietly return to if the hold were ever
	 * refactored back into {@code onPress}.
	 */
	public static boolean releaseCommits() {
		return false;
	}
}
