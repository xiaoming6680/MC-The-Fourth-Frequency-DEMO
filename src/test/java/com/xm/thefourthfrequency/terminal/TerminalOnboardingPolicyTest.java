package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.xm.thefourthfrequency.terminal.TerminalOnboardingPolicy.Phase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalOnboardingPolicyTest {
	@Test
	void aSettledPlayerNeverSeesTheWalkthrough() {
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(false, 0, true));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(false, 4, true));
		// A server that still says "required" while the task has moved past learn_terminal is a state
		// that should not happen; treating it as done beats handing out a walkthrough with no end.
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(true, 4, true));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(true, 9, true));
		// An owed profile does not resurrect a walkthrough that is otherwise over. The profile is part
		// of the first boot, not a thing that can be demanded of a terminal already in service.
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(false, 0, false));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.initial(true, 4, false));
	}

	@Test
	void onlyATrulyFirstOpenPlaysTheSelfTest() {
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.initial(true, 0, true));
		// The self test always comes first, profile owed or not - what follows it is afterBoot's call,
		// and duplicating that fork here would give it two owners that could disagree.
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.initial(true, 0, false));
		// One tab already visited is proof the self test has been seen, so a reconnect resumes at the
		// walking steps instead of replaying it. This is what makes "not repeatable" hold across a
		// disconnect without needing a second flag on the wire.
		assertEquals(Phase.STEP_2, TerminalOnboardingPolicy.initial(true, 1, true));
		assertEquals(Phase.STEP_3, TerminalOnboardingPolicy.initial(true, 2, true));
		assertEquals(Phase.STEP_4, TerminalOnboardingPolicy.initial(true, 3, true));
	}

	@Test
	void bootHoldsUntilItsLinesHaveAllPrinted() {
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.afterBoot(Phase.BOOT, 0L, true));
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.afterBoot(Phase.BOOT,
				TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS - 1L, true));
		assertEquals(Phase.STEP_1, TerminalOnboardingPolicy.afterBoot(Phase.BOOT,
				TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS, true));
		// It only ever moves BOOT along; every other phase is left where it was.
		assertEquals(Phase.STEP_3, TerminalOnboardingPolicy.afterBoot(Phase.STEP_3, 999_999L, true));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.afterBoot(Phase.DONE, 999_999L, true));
	}

	/**
	 * The profile sits between the self test and the tabs, and only on a terminal that owes one.
	 *
	 * <p>Ordering matters both ways. It cannot come before the self test, because the device has not
	 * finished claiming to be awake yet. It cannot come after the tab tour, because the tour ends by
	 * completing {@code learn_terminal} and paying out - the profile would then be a form that
	 * appears after the player has already been told they are finished.
	 */
	@Test
	void theProfileIsAskedOnceBetweenTheSelfTestAndTheTabs() {
		assertEquals(Phase.PROFILE, TerminalOnboardingPolicy.afterBoot(Phase.BOOT,
				TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS, false));
		// Still booting: the profile does not jump the self test.
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.afterBoot(Phase.BOOT, 0L, false));
		// It leaves only when the server says the profile has been taken, and it is the server that
		// says so - an interrupted profile is taken too, gaps and all.
		assertEquals(Phase.PROFILE, TerminalOnboardingPolicy.afterProfile(Phase.PROFILE, false));
		assertEquals(Phase.STEP_1, TerminalOnboardingPolicy.afterProfile(Phase.PROFILE, true));
		// Every other phase is left alone, in both directions.
		assertEquals(Phase.STEP_3, TerminalOnboardingPolicy.afterProfile(Phase.STEP_3, false));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.afterProfile(Phase.DONE, false));
	}

	/**
	 * No tab may be opened while the profile is on screen.
	 *
	 * <p>Falls out of {@link TerminalOnboardingPolicy#target} being null for this phase rather than
	 * from a rule written twice, but it is worth pinning: a player who could reach Tools mid-profile
	 * would be looking at a page the walkthrough has not introduced, with the exit still held.
	 */
	@Test
	void theProfileHoldsTheExitAndOffersNoTabs() {
		assertTrue(TerminalOnboardingPolicy.locksExit(Phase.PROFILE));
		assertNull(TerminalOnboardingPolicy.target(Phase.PROFILE));
		assertEquals(0, TerminalOnboardingPolicy.stepIndex(Phase.PROFILE));
		for (TerminalPage page : TerminalPage.values()) {
			assertFalse(TerminalOnboardingPolicy.allowsPage(Phase.PROFILE, page), page.name());
		}
	}

	@Test
	void walkthroughEndsOnHomeWhereTheTaskCardIs() {
		assertEquals(TerminalPage.TOOLS, TerminalOnboardingPolicy.target(Phase.STEP_1));
		assertEquals(TerminalPage.RECORDS, TerminalOnboardingPolicy.target(Phase.STEP_2));
		assertEquals(TerminalPage.FILES, TerminalOnboardingPolicy.target(Phase.STEP_3));
		// Home last: the terminal already opens there, so leading with it would make the first
		// instructed click a same-page click that visibly does nothing.
		assertEquals(TerminalPage.HOME, TerminalOnboardingPolicy.target(Phase.STEP_4));
		assertNull(TerminalOnboardingPolicy.target(Phase.BOOT));
		assertNull(TerminalOnboardingPolicy.target(Phase.RELEASED));
		assertNull(TerminalOnboardingPolicy.target(Phase.DONE));
	}

	@Test
	void everyTabIsAskedForExactlyOnceAndTheRunEndsDone() {
		List<TerminalPage> asked = new ArrayList<>();
		Phase phase = Phase.STEP_1;
		for (int step = 0; step < TerminalOnboardingPolicy.stepCount(); step++) {
			TerminalPage target = TerminalOnboardingPolicy.target(phase);
			assertNotNull(target, "step " + step + " had nothing to ask for");
			asked.add(target);
			phase = TerminalOnboardingPolicy.advance(phase, target);
		}
		assertEquals(Phase.DONE, phase);
		assertEquals(TerminalPage.values().length, asked.size());
		assertEquals(asked.size(), Set.copyOf(asked).size(), "a tab was asked for twice: " + asked);
	}

	/**
	 * The brief always describes what is on screen, and a full run describes all four pages.
	 *
	 * <p>It used to describe the tab being pointed at, which meant the line under the pointer talked
	 * about a page the player could not see while the one they were looking at went unexplained -
	 * "six field tools" printed over the home card. Both halves matter: naming the current page is
	 * the fix, and the four-of-four count is what says nothing was lost by it.</p>
	 */
	@Test
	void theBriefDescribesThePageOnScreenAndCoversAllFour() {
		// The terminal opens on Home and the walkthrough starts there once the self test is done.
		TerminalPage onScreen = TerminalPage.HOME;
		Phase phase = Phase.STEP_1;
		List<TerminalPage> described = new ArrayList<>();
		for (int step = 0; step < TerminalOnboardingPolicy.stepCount(); step++) {
			TerminalPage subject = TerminalOnboardingPolicy.briefSubject(phase, onScreen);
			assertEquals(onScreen, subject, () -> "the brief described a page the player was not on");
			described.add(subject);
			// The pointer aims somewhere else, which is the whole reason the two used to disagree.
			TerminalPage target = TerminalOnboardingPolicy.target(phase);
			assertNotNull(target);
			assertNotEquals(target, subject, "the brief and the pointer must not name the same page");
			onScreen = target;
			phase = TerminalOnboardingPolicy.advance(phase, target);
		}
		assertEquals(TerminalPage.values().length, described.size());
		assertEquals(described.size(), Set.copyOf(described).size(),
				"a page was described twice and another never was: " + described);

		// Nothing to point at is nothing to describe: no brief during the self test, after the
		// damage failsafe, or once the walkthrough is over.
		for (Phase quiet : new Phase[]{Phase.BOOT, Phase.RELEASED, Phase.DONE}) {
			assertNull(TerminalOnboardingPolicy.briefSubject(quiet, TerminalPage.HOME), quiet.toString());
		}
	}

	@Test
	void aWrongTabDoesNotAdvanceTheWalkthrough() {
		for (TerminalPage page : TerminalPage.values()) {
			Phase advanced = TerminalOnboardingPolicy.advance(Phase.STEP_1, page);
			if (page == TerminalOnboardingPolicy.target(Phase.STEP_1)) assertEquals(Phase.STEP_2, advanced);
			else assertEquals(Phase.STEP_1, advanced, () -> "the walkthrough moved on " + page);
		}
		// Phases with nothing to wait for absorb any visit rather than falling off the end.
		assertEquals(Phase.BOOT, TerminalOnboardingPolicy.advance(Phase.BOOT, TerminalPage.HOME));
		assertEquals(Phase.DONE, TerminalOnboardingPolicy.advance(Phase.DONE, TerminalPage.HOME));
	}

	@Test
	void theExitIsHeldOnlyWhileTheWalkthroughIsRunning() {
		for (Phase phase : new Phase[]{Phase.BOOT, Phase.STEP_1, Phase.STEP_2, Phase.STEP_3, Phase.STEP_4}) {
			assertTrue(TerminalOnboardingPolicy.locksExit(phase), () -> phase + " must hold the exit");
		}
		// The damage failsafe and the finished state must both let go, or the terminal cannot be
		// closed at all - the one outcome the safety rule exists to prevent.
		assertFalse(TerminalOnboardingPolicy.locksExit(Phase.RELEASED));
		assertFalse(TerminalOnboardingPolicy.locksExit(Phase.DONE));
	}

	@Test
	void aHeldWalkthroughAllowsExactlyOnePage() {
		for (Phase phase : new Phase[]{Phase.STEP_1, Phase.STEP_2, Phase.STEP_3, Phase.STEP_4}) {
			int allowed = 0;
			for (TerminalPage page : TerminalPage.values()) {
				if (TerminalOnboardingPolicy.allowsPage(phase, page)) allowed++;
			}
			assertEquals(1, allowed, () -> phase + " allowed " + "a number of pages other than one");
		}
		for (TerminalPage page : TerminalPage.values()) {
			assertFalse(TerminalOnboardingPolicy.allowsPage(Phase.BOOT, page),
					"the self test takes no page input");
			assertTrue(TerminalOnboardingPolicy.allowsPage(Phase.RELEASED, page));
			assertTrue(TerminalOnboardingPolicy.allowsPage(Phase.DONE, page));
		}
	}

	@Test
	void selfTestPrintsItsLinesInOrderAndItsProgressStaysInRange() {
		assertEquals(0, TerminalOnboardingPolicy.visibleBootLines(-5L));
		assertEquals(1, TerminalOnboardingPolicy.visibleBootLines(0L));
		assertEquals(2, TerminalOnboardingPolicy.visibleBootLines(TerminalOnboardingPolicy.BOOT_LINE_MILLIS));
		assertEquals(TerminalOnboardingPolicy.BOOT_LINE_COUNT,
				TerminalOnboardingPolicy.visibleBootLines(TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS));
		assertEquals(TerminalOnboardingPolicy.BOOT_LINE_COUNT,
				TerminalOnboardingPolicy.visibleBootLines(999_999L));

		// A later line has not started while an earlier one is still printing.
		assertEquals(0, TerminalOnboardingPolicy.typedCharacters(20, 100L, 3));
		assertEquals(0, TerminalOnboardingPolicy.typedCharacters(20, 0L, 0));
		assertEquals(20, TerminalOnboardingPolicy.typedCharacters(20, 999_999L, 5));

		int previous = -1;
		for (long elapsed = 0L; elapsed <= TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS + 500L; elapsed += 37L) {
			int percent = TerminalOnboardingPolicy.bootProgressPercent(elapsed);
			assertTrue(percent >= 0 && percent <= 100, "progress left 0..100 at " + elapsed);
			assertTrue(percent >= previous, "progress went backwards at " + elapsed);
			previous = percent;
		}
		assertEquals(0, TerminalOnboardingPolicy.bootProgressPercent(0L));
		assertEquals(100, TerminalOnboardingPolicy.bootProgressPercent(TerminalOnboardingPolicy.BOOT_TOTAL_MILLIS));
	}

	@Test
	void dimmedBandsCoverEverythingExceptTheHighlightedTab() {
		var display = TerminalUiLayout.DISPLAY;
		for (TerminalPage page : TerminalPage.values()) {
			var hole = tabOf(page);
			var bands = TerminalOnboardingPolicy.dimBands(display, hole);

			int covered = 0;
			for (var band : bands) {
				assertTrue(display.contains(band), () -> band + " escaped the display");
				covered += Math.max(0, band.width()) * Math.max(0, band.height());
				// The highlighted tab keeps every pixel it owns: its label, its background and its
				// unread flash all have to stay readable through the walkthrough.
				assertTrue(disjoint(band, hole), () -> band + " overlapped the highlighted tab " + hole);
			}
			for (int first = 0; first < bands.length; first++) {
				for (int second = first + 1; second < bands.length; second++) {
					assertTrue(disjoint(bands[first], bands[second]),
							"dim bands overlapped each other and would double-darken");
				}
			}
			int displayArea = display.width() * display.height();
			int holeArea = hole.width() * hole.height();
			assertEquals(displayArea - holeArea, covered,
					() -> "the dimmed bands plus " + page + "'s tab did not tile the display");
		}
	}

	private static TerminalUiLayout.Bounds tabOf(TerminalPage page) {
		return switch (page) {
			case HOME -> TerminalUiLayout.HOME_TAB;
			case TOOLS -> TerminalUiLayout.TOOLS_TAB;
			case RECORDS -> TerminalUiLayout.RECORDS_TAB;
			case FILES -> TerminalUiLayout.FILES_TAB;
		};
	}

	private static boolean disjoint(TerminalUiLayout.Bounds first, TerminalUiLayout.Bounds second) {
		if (first.width() <= 0 || first.height() <= 0 || second.width() <= 0 || second.height() <= 0) return true;
		return first.right() <= second.left() || second.right() <= first.left()
				|| first.bottom() <= second.top() || second.bottom() <= first.top();
	}

	/**
	 * The button may not open before the explanation has finished arriving.
	 *
	 * <p>It is the only way forward now, so an early button is an invitation to skip the step - and a
	 * player who skips every step has been shown a tutorial that taught them nothing. The gate is the
	 * text finishing rather than a timer of its own, so it can never drift from what is on screen.
	 */
	@Test
	void theAdvanceButtonWaitsForTheExplanationAndThenOpensImmediately() {
		assertFalse(TerminalOnboardingPolicy.advanceReady(0L, 40));
		assertFalse(TerminalOnboardingPolicy.advanceReady(
				TerminalOnboardingPolicy.detailTotalMillis(40) - 1L, 40));
		assertTrue(TerminalOnboardingPolicy.advanceReady(
				TerminalOnboardingPolicy.detailTotalMillis(40), 40));
		assertTrue(TerminalOnboardingPolicy.advanceReady(Long.MAX_VALUE, 40));
		// A longer translation delays the button rather than being cut off by it.
		assertTrue(TerminalOnboardingPolicy.detailTotalMillis(80)
				> TerminalOnboardingPolicy.detailTotalMillis(40));
	}

	/** Lines arrive one at a time and every one of them is fully printed by the time the button opens. */
	@Test
	void everyExplanationLineIsFinishedBeforeTheButtonOpens() {
		assertEquals(0, TerminalOnboardingPolicy.visibleDetailLines(-1L));
		assertEquals(1, TerminalOnboardingPolicy.visibleDetailLines(0L));
		assertEquals(TerminalOnboardingPolicy.DETAIL_LINE_COUNT,
				TerminalOnboardingPolicy.visibleDetailLines(Long.MAX_VALUE));
		long ready = TerminalOnboardingPolicy.detailTotalMillis(40);
		assertEquals(TerminalOnboardingPolicy.DETAIL_LINE_COUNT,
				TerminalOnboardingPolicy.visibleDetailLines(ready));
		for (int line = 0; line < TerminalOnboardingPolicy.DETAIL_LINE_COUNT; line++) {
			assertEquals(40, TerminalOnboardingPolicy.typedDetailCharacters(40, ready, line),
					"line " + line + " was still printing when the button opened");
		}
	}

	/**
	 * Only the fourth step offers to finish, and pressing it still has to go through a real page
	 * visit - which is what {@link TerminalOnboardingPolicy#advance} continues to require.
	 */
	@Test
	void onlyTheLastStepIsTheFinishingOne() {
		assertFalse(TerminalOnboardingPolicy.finalStep(TerminalOnboardingPolicy.Phase.STEP_1));
		assertFalse(TerminalOnboardingPolicy.finalStep(TerminalOnboardingPolicy.Phase.STEP_3));
		assertTrue(TerminalOnboardingPolicy.finalStep(TerminalOnboardingPolicy.Phase.STEP_4));
		assertFalse(TerminalOnboardingPolicy.finalStep(TerminalOnboardingPolicy.Phase.BOOT));
		assertFalse(TerminalOnboardingPolicy.finalStep(TerminalOnboardingPolicy.Phase.DONE));
		// The button is a shortcut to the click, never a substitute for it: the phase still only
		// moves when the page the step asked for is actually reported as visited.
		assertEquals(TerminalOnboardingPolicy.Phase.STEP_4,
				TerminalOnboardingPolicy.advance(TerminalOnboardingPolicy.Phase.STEP_4, TerminalPage.TOOLS));
		assertEquals(TerminalOnboardingPolicy.Phase.DONE,
				TerminalOnboardingPolicy.advance(TerminalOnboardingPolicy.Phase.STEP_4, TerminalPage.HOME));
	}
}
