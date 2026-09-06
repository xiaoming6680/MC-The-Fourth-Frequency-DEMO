package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.terminal.TerminalAutoOpenPolicy.Decision;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalAutoOpenPolicyTest {
	private static Decision decide(boolean worldShown, boolean holding, boolean engaged, int ticks) {
		return TerminalAutoOpenPolicy.decide(worldShown, holding, engaged, ticks);
	}

	@Test
	void itOpensOnceTheWorldHasBeenInFrontOfThePlayerForTheSettleBeat() {
		assertEquals(Decision.WAIT, decide(true, true, false, 0));
		assertEquals(Decision.WAIT, decide(true, true, false, TerminalAutoOpenPolicy.SETTLE_TICKS - 1));
		assertEquals(Decision.OPEN, decide(true, true, false, TerminalAutoOpenPolicy.SETTLE_TICKS));
	}

	/**
	 * The load is the whole reason this is not decided on the server.
	 *
	 * <p>A screen opened while the client is still receiving the level is closed again by vanilla
	 * when the load finishes, so the player would see nothing. Waiting is the answer, and it must
	 * not be a wait that expires - a first world generation can take longer than the whole budget.
	 */
	@Test
	void aWorldNotYetShownNeitherOpensNorExpires() {
		assertEquals(Decision.WAIT, decide(false, true, false, 0));
		assertEquals(Decision.WAIT, decide(false, true, false, TerminalAutoOpenPolicy.SETTLE_TICKS));
		assertEquals(Decision.WAIT, decide(false, true, false, TerminalAutoOpenPolicy.GIVE_UP_TICKS));
		assertEquals(Decision.WAIT, decide(false, false, false, TerminalAutoOpenPolicy.GIVE_UP_TICKS * 10));
	}

	/**
	 * A terminal that never reaches the hand is not waited for indefinitely.
	 *
	 * <p>Dropped because the inventory was full, or swapped away in the first seconds. The offer
	 * expires quietly rather than firing minutes later over whatever the player is doing by then.
	 */
	@Test
	void anOfferThatCannotBeTakenExpiresInsteadOfLurking() {
		assertEquals(Decision.WAIT, decide(true, false, false, 0));
		assertEquals(Decision.WAIT, decide(true, false, false, TerminalAutoOpenPolicy.GIVE_UP_TICKS - 1));
		assertEquals(Decision.GIVE_UP, decide(true, false, false, TerminalAutoOpenPolicy.GIVE_UP_TICKS));
		// Holding it again after the budget is spent does not revive the offer.
		assertEquals(Decision.GIVE_UP, decide(true, true, false, TerminalAutoOpenPolicy.GIVE_UP_TICKS));
	}

	/** The player's own right-click wins, at every point in the offer's life. */
	@Test
	void aTerminalAlreadyComingUpCancelsTheOffer() {
		for (int ticks : new int[]{0, TerminalAutoOpenPolicy.SETTLE_TICKS,
				TerminalAutoOpenPolicy.GIVE_UP_TICKS}) {
			assertEquals(Decision.GIVE_UP, decide(true, true, true, ticks));
			assertEquals(Decision.GIVE_UP, decide(false, false, true, ticks));
		}
	}

	@Test
	void theSettleBeatIsShorterThanTheBudgetAndBothAreSaneLengths() {
		assertTrue(TerminalAutoOpenPolicy.SETTLE_TICKS > 0);
		assertTrue(TerminalAutoOpenPolicy.SETTLE_TICKS < TerminalAutoOpenPolicy.GIVE_UP_TICKS);
		assertTrue(TerminalAutoOpenPolicy.GIVE_UP_TICKS <= 1_200,
				"an offer that can still fire a minute into play is no longer a greeting");
	}

	/**
	 * The wiring the rules above are worthless without.
	 *
	 * <p>Two properties, and neither is visible from the policy itself: the offer is sent from the
	 * one branch that issues a terminal (so it is bounded by the grant ledger and cannot repeat on
	 * later joins), and it is answered with the ordinary open request rather than by putting a
	 * screen up client-side (so the server validates it exactly as it validates a right-click).
	 */
	@Test
	void theOfferIsBoundToTheIssuingJoinAndAnsweredWithTheOrdinaryRequest() throws Exception {
		String station = read("src/main/java/com/xm/thefourthfrequency/world/ZeroStationService.java");
		String controller = read(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalAutoOpenController.java");
		String networking = read(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalNetworking.java");

		assertEquals(1, count(station, "new TerminalAutoOpenPayload()"),
				"the offer must be sent from exactly one place, and that place is the grant");
		int issued = station.indexOf("if (!issueTerminalIfNeeded(player)) {");
		assertTrue(issued >= 0 && issued < station.indexOf("new TerminalAutoOpenPayload()"),
				"the offer must sit behind the grant check, or every rejoin would re-open the terminal");
		assertTrue(networking.contains(
				"PayloadTypeRegistry.playS2C().register(TerminalAutoOpenPayload.TYPE"),
				"an unregistered payload type disconnects the client that receives it");

		assertTrue(controller.contains("ClientPlayNetworking.send(new TerminalOpenPayload(0))"),
				"the auto-open must go through the request the server already validates");
		assertFalse(controller.contains("setScreen"),
				"nothing here may put a screen up on its own");
		assertTrue(controller.contains("FirstRunNoticeController.released()"),
				"the safety notice owns the screen until it is dismissed");
		assertTrue(controller.contains("client.getOverlay() == null"),
				"a resource reload overlay hides the world just as a loading screen does");
		assertTrue(controller.contains("ClientPlayConnectionEvents.DISCONNECT"),
				"an outstanding offer must not survive into the next world");
	}

	private static int count(String haystack, String needle) {
		return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
	}

	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
