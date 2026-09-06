package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.terminal.TerminalHandheldPose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-machine transitions for the held terminal's performance.
 *
 * <p>Only the transitions - {@code clientTick} needs a running client and belongs to the client
 * GameTest suite. Everything asserted here is reachable without one, and each case corresponds to
 * a way the real client can enter or leave the animation.</p>
 */
final class TerminalHandheldAnimatorTest {
	@BeforeEach
	void reset() {
		TerminalHandheldAnimator.resetForTesting();
	}

	private static TerminalSnapshotPayload snapshot() {
		return new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0, 0, 0, 50, 0, 0, 0, false, 0, 0, false, 0,
				0, false, false, 100L, 0, 0, List.of(), "none", 0,
				List.of(), -1, "mine_logs", 3, 12,
				1, false, "minecraft:stone_axe", 1, false, false,
				List.of(), -1, List.of(), 0, 0);
	}

	@Test
	void aSnapshotStartsTheDeviceComingUpFromRest() {
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertEquals(0.0D, TerminalHandheldAnimator.openness(), 1.0E-9D);
		assertEquals(1.0F, TerminalHandheldAnimator.fovScale(), 1.0E-6F);
		assertRestingInHand();

		TerminalHandheldAnimator.requestOpen(snapshot());
		assertEquals(TerminalHandheldAnimator.State.OPENING, TerminalHandheldAnimator.state());
		assertTrue(TerminalHandheldAnimator.isAnimating());
	}

	/**
	 * The server pushes a snapshot roughly once a second while the terminal is open - far more
	 * often while the tuning dial is settling. Restarting the phase on each would hold the device
	 * mid-travel forever and the screen would never arrive.
	 */
	@Test
	void repeatedSnapshotsRefreshWithoutRestartingTheTravel() {
		TerminalHandheldAnimator.requestOpen(snapshot());
		long started = TerminalHandheldAnimator.phaseStartedAtMillisForTesting();
		for (int repeat = 0; repeat < 5; repeat++) TerminalHandheldAnimator.requestOpen(snapshot());
		assertEquals(started, TerminalHandheldAnimator.phaseStartedAtMillisForTesting());
		assertEquals(TerminalHandheldAnimator.State.OPENING, TerminalHandheldAnimator.state());
	}

	@Test
	void closingFromRestIsANoOpSoAStrayCloseCannotAnimateAnIdleItem() {
		TerminalHandheldAnimator.requestClose();
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertFalse(TerminalHandheldAnimator.isAnimating());
		assertEquals(0.0D, TerminalHandheldAnimator.openness(), 1.0E-9D);
	}

	/**
	 * Death, a dimension change, a disconnect and a server-side refusal all land here. Each of them
	 * can arrive at any point in the performance, and each has to leave the item back in the hand
	 * with nothing pending.
	 */
	@Test
	void abortReturnsToRestFromEveryPhase() {
		TerminalHandheldAnimator.requestOpen(snapshot());
		TerminalHandheldAnimator.abort();
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertEquals(1.0F, TerminalHandheldAnimator.fovScale(), 1.0E-6F,
				"an aborted performance must give the camera its field of view back");
		assertRestingInHand();

		TerminalHandheldAnimator.requestOpen(snapshot());
		TerminalHandheldAnimator.requestClose();
		assertEquals(TerminalHandheldAnimator.State.CLOSING, TerminalHandheldAnimator.state());
		TerminalHandheldAnimator.abort();
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertRestingInHand();
	}

	/**
	 * The item is back where a carried terminal belongs.
	 *
	 * <p>Not "no transform at all": a resting terminal still carries its hand-held tilt and its
	 * idle breath. What has to be gone is everything the performance added - the travel toward the
	 * centre of the frame, the magnification, and the camera's lean.</p>
	 */
	private static void assertRestingInHand() {
		var pose = TerminalHandheldAnimator.presentation();
		var open = TerminalHandheldPose.presentation(1.0D, 0L);
		assertEquals(1.0F, TerminalHandheldAnimator.fovScale(), 1.0E-6F);
		assertTrue(pose.scale() < open.scale(), "the device must not stay magnified");
		// Compared as screen angles: the raised and resting poses sit at different depths, so the
		// raw heights understate how far it actually came down.
		assertTrue(pose.y() / Math.abs(pose.z()) < open.y() / Math.abs(open.z()) - 0.25D,
				"the device must have come back down");
		assertTrue(pose.pitch() < -15.0F, "the device must be laid back again, not still presented");
	}

	/**
	 * A reversal resumes from where the device actually is.
	 *
	 * <p>Reopening immediately after a close - a misclick, or a second right-click landing before
	 * the first close finished - must not snap the terminal back down to the hand before starting
	 * up again. The phase start is backdated instead, so openness is continuous across the turn.</p>
	 */
	@Test
	void reversingMidTravelIsContinuousRatherThanRestarting() {
		TerminalHandheldAnimator.requestOpen(snapshot());
		double whileOpening = TerminalHandheldAnimator.openness();
		TerminalHandheldAnimator.requestClose();
		assertEquals(whileOpening, TerminalHandheldAnimator.openness(), 0.05D,
				"closing must pick up from the openness the device had");

		TerminalHandheldAnimator.requestOpen(snapshot());
		assertEquals(TerminalHandheldAnimator.State.OPENING, TerminalHandheldAnimator.state());
		assertEquals(whileOpening, TerminalHandheldAnimator.openness(), 0.05D,
				"reopening must pick up from the openness the device had");
	}

	/**
	 * With something in the off hand there is no two-handed performance to play.
	 *
	 * <p>Vanilla draws the item on one arm and the mixin only tilts it, so the raise, the scale-up
	 * and the lens closing in have nothing to animate - playing their timings anyway bought a wait
	 * in front of the page with nothing to look at during it, and pulled the field of view in around
	 * an object that had not moved. The device stays at rest and the page is the whole event.
	 */
	@Test
	void aOneHandedCarryOpensStraightToThePageWithNoTravel() {
		TerminalHandheldAnimator.requestOpen(snapshot(), false);
		assertEquals(TerminalHandheldAnimator.State.OPEN, TerminalHandheldAnimator.state(),
				"there is no travel to wait through");
		assertFalse(TerminalHandheldAnimator.isAnimating());
		assertEquals(0.0D, TerminalHandheldAnimator.openness(), 1.0E-9D,
				"the device never leaves rest, so the pose must not report it raised");
		assertEquals(1.0F, TerminalHandheldAnimator.fovScale(), 1.0E-6F,
				"a lens that closes in around an object that did not move is the jump this avoids");

		// And it comes back down without a performance either.
		TerminalHandheldAnimator.requestClose();
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertEquals(0.0D, TerminalHandheldAnimator.openness(), 1.0E-9D);

		// The two-handed carry still gets its full travel.
		TerminalHandheldAnimator.requestOpen(snapshot(), true);
		assertEquals(TerminalHandheldAnimator.State.OPENING, TerminalHandheldAnimator.state());
	}

	/**
	 * Opening the terminal is a swing, and must not be drawn as one.
	 *
	 * <p>The open request answers {@code InteractionResult.SUCCESS} so it reaches the server; that
	 * result carries {@code SwingSource.CLIENT}, and {@code Minecraft.startUseItem} therefore calls
	 * {@code LocalPlayer.swing} on the same frame the raise begins. Two gestures describing opposite
	 * motions, played at once, on the one object the player is looking at - so while the device is
	 * doing anything of its own, the swing is refused.</p>
	 *
	 * <p>The other direction matters just as much: a swing the player really did make - mining,
	 * punching, right-clicking a block - leaves the animator idle and has to come through untouched,
	 * or the fix for the open would have removed the motion it was added to restore.</p>
	 */
	@Test
	void theTerminalsOwnPerformanceRefusesTheSwingItCauses() {
		assertEquals(TerminalHandheldAnimator.State.IDLE, TerminalHandheldAnimator.state());
		assertEquals(0.6F, TerminalHandheldAnimator.swingShown(0.6F), 1.0E-6F,
				"a carried terminal takes the player's own swing");
		// Vanilla's counter is briefly outside its own range between ticks.
		assertEquals(1.0F, TerminalHandheldAnimator.swingShown(4.0F), 1.0E-6F);
		assertEquals(0.0F, TerminalHandheldAnimator.swingShown(-1.0F), 1.0E-6F);

		TerminalHandheldAnimator.requestOpen(snapshot());
		assertEquals(TerminalHandheldAnimator.State.OPENING, TerminalHandheldAnimator.state());
		assertEquals(0.0F, TerminalHandheldAnimator.swingShown(0.6F), 1.0E-6F,
				"the swing the open itself fires is the raise, not a blow");

		TerminalHandheldAnimator.requestClose();
		assertEquals(TerminalHandheldAnimator.State.CLOSING, TerminalHandheldAnimator.state());
		assertEquals(0.0F, TerminalHandheldAnimator.swingShown(0.6F), 1.0E-6F,
				"nor on the way back down");

		TerminalHandheldAnimator.abort();
		assertEquals(0.6F, TerminalHandheldAnimator.swingShown(0.6F), 1.0E-6F,
				"and the device takes hits again the moment it is back in the hand");

		// A one-handed open never travels, but vanilla is drawing the swing on the arm itself there,
		// so the pose must not add a second one on top of it.
		TerminalHandheldAnimator.requestOpen(snapshot(), false);
		assertEquals(TerminalHandheldAnimator.State.OPEN, TerminalHandheldAnimator.state());
		assertEquals(0.0F, TerminalHandheldAnimator.swingShown(0.6F), 1.0E-6F);
	}

	/**
	 * The opening may not take a screen the player put up, and must say so to the server.
	 *
	 * <p>Asserted against the source because the rule needs a live {@code Minecraft} and a real
	 * screen, which puts the behavioural version of it in the client GameTest suite - M0's
	 * locked-render-distance case, the one place that deliberately opens another screen inside the
	 * opening window. That suite is heavy and is not part of {@code build}, so this is the cheap
	 * barrier that fails in {@code unitTest} if the guard is taken back out.
	 *
	 * <p>What it is guarding: {@code presentScreen} used to call {@code setScreen} unconditionally,
	 * so anything opened during the server round trip plus the half-second rise - inventory, pause
	 * menu, video settings - was evicted when the animation finished. The auto-open made that window
	 * a whole round trip longer and removed the only excuse for it.
	 */
	@Test
	void anOpeningNeverEvictsAScreenThePlayerOpened() throws Exception {
		String animator = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalHandheldAnimator.java"),
				java.nio.charset.StandardCharsets.UTF_8);
		assertTrue(animator.contains("if (client.screen != null) return false;"),
				"presentScreen must refuse a screen it did not find empty");
		assertTrue(animator.contains("if (!presentScreen()) abandonOpening();")
						|| animator.contains("if (!presentScreen()) {"),
				"a refused presentation has to abandon the opening rather than be ignored");
		assertTrue(animator.contains("TerminalControlPayload.CLOSE_NEVER_SHOWN"),
				"abandoning an opening the server already accepted has to tell the server, and as the"
						+ " close that does not latch an unfinished profile");
	}
}
