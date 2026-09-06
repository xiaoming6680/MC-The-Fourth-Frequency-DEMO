package com.xm.thefourthfrequency.pursuit;

import net.minecraft.world.entity.Relative;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PursuitReturnViewTest {
	/**
	 * A mirror return arrives on the view the chase ended on, not the one it started on.
	 *
	 * <p>The return takes its position from wherever the chase left the player, so the facing has to
	 * come off the same clock. Reading it back out of the entry record put the two a whole pursuit
	 * apart, and the recorded pair below is deliberately nothing like the current view - that is the
	 * only arrangement in which the two possible answers differ at all.</p>
	 */
	@Test
	void aMirrorReturnLeavesTheViewAlone() {
		PursuitReturnView view = PursuitReturnView.forReturn(true, -140.0F, 35.0F);

		assertTrue(view.keepsCurrentView(), "a mirror return must not touch the player's view");
		// Relative rather than absolute, because re-applying even the *correct* rotation still snaps
		// the client by however far the mouse moved since the last movement packet.
		assertTrue(view.relative().contains(Relative.Y_ROT), "yaw must be relative");
		assertTrue(view.relative().contains(Relative.X_ROT), "pitch must be relative");
		assertEquals(0.0F, view.yaw(), "a relative yaw of anything but zero would rotate the player");
		assertEquals(0.0F, view.pitch(), "a relative pitch of anything but zero would rotate the player");
	}

	/**
	 * A return that never reached the mirror still applies the recorded pair.
	 *
	 * <p>An aborted warning phase or a recovery login puts the player back at the entry
	 * <em>position</em>, out of the same record - so the entry facing is the consistent answer there,
	 * and keeping the current view would be the mismatched one.</p>
	 */
	@Test
	void aReturnThatNeverEnteredTheMirrorRestoresTheRecordedPair() {
		PursuitReturnView view = PursuitReturnView.forReturn(false, -140.0F, 35.0F);

		assertFalse(view.keepsCurrentView(), "the entry pair has to be applied, not ignored");
		assertTrue(view.relative().isEmpty(), "the recorded pair is absolute, not an offset");
		assertEquals(-140.0F, view.yaw());
		assertEquals(35.0F, view.pitch());
	}
}
