package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.DebugNames;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wording and the arithmetic of the developer readout.
 *
 * <p>Worth a plain test rather than only a look at a running client, because the two failures this
 * readout can have are both silent: a countdown that keeps counting in a dimension where nothing is
 * scheduled, and a pool line that claims to know which anomaly is next. Neither looks wrong on
 * screen - they look authoritative - and both send whoever reads them somewhere else entirely.</p>
 */
class DebugReadoutTest {
	private static final int ALL_GROUPS = 0b1111;

	@Test
	void thePanelBlockAlwaysFillsTheRowsItReserved() {
		assertEquals(DebugReadout.PANEL_LINE_COUNT,
				DebugReadout.panelLines(status().build(), "常规", 0).size());
		assertEquals(DebugReadout.PANEL_LINE_COUNT,
				DebugReadout.panelLines(status().suspended().build(), "冻结", 999).size());
	}

	/**
	 * The push is the truth; the local subtraction only fills the half second until the next one.
	 */
	@Test
	void countdownsCarryForwardBetweenPushesAndStopAtZero() {
		DebugStatusPayload payload = status().next(87).active("light_dropout", 12).cooldowns(30, 45).build();
		List<String> fresh = DebugReadout.hudLines(payload, "常规", ALL_GROUPS, 0);
		List<String> later = DebugReadout.hudLines(payload, "常规", ALL_GROUPS, 5);
		assertTrue(fresh.contains("下次 87s · 常规"));
		assertTrue(later.contains("下次 82s · 常规"));
		assertTrue(later.contains("冷却 强 25s · 复合 40s"));
		// Past the deadline is a real state - the director checks on its own interval and every
		// candidate can refuse - so it reads as zero rather than as a negative clock.
		List<String> overdue = DebugReadout.hudLines(payload, "常规", ALL_GROUPS, 400);
		assertTrue(overdue.contains("下次 0s · 常规"));
		assertTrue(overdue.contains("冷却 强 0s · 复合 0s"));
	}

	@Test
	void aSuspendedDirectorReplacesTheCountdownInsteadOfPrintingOne() {
		List<String> lines = DebugReadout.hudLines(status().next(87).suspended().build(), "常规", ALL_GROUPS, 0);
		assertTrue(lines.contains("下次 自动触发已停止"));
		assertFalse(lines.stream().anyMatch(line -> line.contains("87s")));
	}

	/**
	 * The pool stands in for the name that cannot be shown, so it has to name the pool.
	 */
	@Test
	void theCandidateBlockNamesThePoolAndStarsWhatHasNeverBeenMet() {
		long candidates = 0b111L;
		long unseen = 0b010L;
		List<String> lines = DebugReadout.candidateLines(
				status().pool(candidates, unseen).build());
		assertEquals("候选 3 条 · 未见过 1 条", lines.get(0));
		assertTrue(lines.contains("  ★" + catalogueName(1)));
		assertTrue(lines.contains("  •" + catalogueName(0)));
		assertTrue(lines.contains("  •" + catalogueName(2)));
	}

	@Test
	void anEmptyPoolSaysSoRatherThanShowingNothing() {
		List<String> lines = DebugReadout.candidateLines(status().pool(0L, 0L).build());
		assertEquals(1, lines.size());
		assertTrue(lines.getFirst().startsWith("候选 无"));
	}

	/**
	 * Only entries actually in the pool count as unseen here.
	 *
	 * <p>The unseen mask covers the whole catalogue, including everything this stage cannot draw yet.
	 * Counting it whole would report "12 unseen" beside a pool of three and read as a pool that is
	 * about to produce something new when it cannot.</p>
	 */
	@Test
	void theUnseenCountIsScopedToThePool() {
		DebugStatusPayload payload = status().pool(0b0011L, 0b1111_1110L).build();
		assertEquals(2, DebugReadout.candidateCount(payload));
		assertEquals(1, DebugReadout.unseenCandidateCount(payload));
	}

	@Test
	void groupsTurnOffWithoutMovingTheLinesAboveThem() {
		DebugStatusPayload payload = status().next(60).build();
		List<String> all = DebugReadout.hudLines(payload, "常规", ALL_GROUPS, 0);
		List<String> rhythmOnly = DebugReadout.hudLines(payload, "常规",
				DebugHudState.Group.RHYTHM.bit(), 0);
		assertEquals(rhythmOnly, all.subList(0, rhythmOnly.size()));
		assertTrue(DebugReadout.hudLines(payload, "常规", 0, 0).isEmpty());

		List<String> pursuitOnly = DebugReadout.hudLines(payload, "常规",
				DebugHudState.Group.PURSUIT.bit(), 0);
		assertEquals(2, pursuitOnly.size());
		assertTrue(pursuitOnly.getFirst().startsWith("追逐 无"));
	}

	@Test
	void anActiveChaseAndLayerAreNamedRatherThanCounted() {
		List<String> lines = DebugReadout.hudLines(
				status().pursuit(5).inLayer().build(), "冻结", DebugHudState.Group.PURSUIT.bit(), 0);
		assertTrue(lines.getFirst().contains("第 5 形态"));
		assertTrue(lines.get(1).contains("在层内"));
	}

	private static String catalogueName(int index) {
		return DebugNames.anomaly(AnomalyCatalog.definitions().get(index).id());
	}

	private static Builder status() {
		return new Builder();
	}

	/** Keeps each test naming only the handful of fields it is actually about. */
	private static final class Builder {
		private int plotStage = 3;
		private boolean bound = true;
		private String activeAnomaly = "none";
		private int activeSeconds;
		private int nextSeconds = 60;
		private int strongCooldown;
		private int compositeCooldown;
		private boolean suspended;
		private long candidateMask = 0b1L;
		private long unseenMask;
		private boolean pursuitActive;
		private int pursuitForm;
		private boolean inUnrenderedLayer;

		private Builder next(int seconds) {
			nextSeconds = seconds;
			return this;
		}

		private Builder active(String id, int seconds) {
			activeAnomaly = id;
			activeSeconds = seconds;
			return this;
		}

		private Builder cooldowns(int strong, int composite) {
			strongCooldown = strong;
			compositeCooldown = composite;
			return this;
		}

		private Builder suspended() {
			suspended = true;
			return this;
		}

		private Builder pool(long candidates, long unseen) {
			candidateMask = candidates;
			unseenMask = unseen;
			return this;
		}

		private Builder pursuit(int form) {
			pursuitActive = true;
			pursuitForm = form;
			return this;
		}

		private Builder inLayer() {
			inUnrenderedLayer = true;
			return this;
		}

		private DebugStatusPayload build() {
			return new DebugStatusPayload(DebugStatusPayload.CURRENT_PROTOCOL_VERSION, true, "tester",
					plotStage, 1, bound, 7, 5, 0b1111111, 0b11111, 0b111, 2, 2, true,
					2, 4, 40, activeAnomaly, activeSeconds, nextSeconds,
					strongCooldown, compositeCooldown, suspended,
					candidateMask, unseenMask, 2, false,
					pursuitActive, pursuitForm, pursuitActive ? 1 : 0,
					inUnrenderedLayer, inUnrenderedLayer ? 1 : 0, 0, 0b101, "");
		}
	}
}
