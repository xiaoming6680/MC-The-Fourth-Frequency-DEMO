package com.xm.thefourthfrequency.client_ui;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packing rule the debug panel's right column runs on.
 *
 * <p>These assertions exist because the column previously had no rule at all: it was a run of fixed
 * offsets summing to roughly the height of a full-size panel, which silently drew its last groups
 * outside the frame on any window where the panel came out shorter. The property that matters is not
 * "the numbers add up" but "whatever the band height, every row is either whole or absent, and the
 * last row is reachable".</p>
 */
class DebugPanelLayoutTest {
	/** Roughly the shape of the real column: a heading, ten state lines, then labelled button rows. */
	private static final List<Integer> COLUMN = buildColumn();

	@Test
	void everythingFitsInAFullSizePanelAndNothingCanBeScrolled() {
		// 520-tall panel: 30 header, 28 footer, 10 padding each side.
		int body = 520 - 30 - 28 - 20;
		assertEquals(COLUMN.size(), DebugPanelLayout.visibleRowCount(body, COLUMN, 0));
		assertEquals(0, DebugPanelLayout.maxScrollRow(body, COLUMN));
	}

	/**
	 * The case the fixed layout got wrong, and the reason this class exists.
	 */
	@Test
	void aShortPanelScrollsInsteadOfOverflowing() {
		// 640x360 is the virtual screen at GUI scale 3 on a 1080p display; the panel is 336 tall there.
		int body = 336 - 30 - 28 - 20;
		assertTrue(totalHeight() > body, "this test is only meaningful while the column overflows");
		int visible = DebugPanelLayout.visibleRowCount(body, COLUMN, 0);
		assertTrue(visible > 0 && visible < COLUMN.size());
		assertTrue(heightOf(0, visible) <= body, "the visible rows must fit inside the band");
		assertTrue(DebugPanelLayout.maxScrollRow(body, COLUMN) > 0);
	}

	/**
	 * Scrolled to the end, the last row is on screen. A column that can scroll but cannot reach its
	 * own bottom hides exactly what scrolling was added to reveal.
	 */
	@Test
	void theLastRowIsReachableAtEveryBandHeight() {
		for (int body = 20; body <= totalHeight() + 40; body += 7) {
			int max = DebugPanelLayout.maxScrollRow(body, COLUMN);
			int visible = DebugPanelLayout.visibleRowCount(body, COLUMN, max);
			assertEquals(COLUMN.size(), max + visible,
					"scrolled to the end, rows " + max + "+" + visible + " must reach " + COLUMN.size()
							+ " at band height " + body);
		}
	}

	/** No partial rows, at any offset or band height. */
	@Test
	void visibleRowsNeverExceedTheBand() {
		for (int body = 0; body <= totalHeight() + 40; body += 5) {
			for (int from = 0; from < COLUMN.size(); from++) {
				int visible = DebugPanelLayout.visibleRowCount(body, COLUMN, from);
				assertTrue(heightOf(from, visible) <= Math.max(0, body),
						"rows from " + from + " overflowed a band of " + body);
			}
		}
	}

	@Test
	void aBandTooShortForEvenOneRowShowsNothingRatherThanACutRow() {
		assertEquals(0, DebugPanelLayout.visibleRowCount(3, COLUMN, 0));
		assertEquals(0, DebugPanelLayout.visibleRowCount(0, COLUMN, 0));
		assertEquals(0, DebugPanelLayout.visibleRowCount(-40, COLUMN, 0));
	}

	@Test
	void anEmptyColumnHasNothingToScroll() {
		assertEquals(0, DebugPanelLayout.maxScrollRow(100, List.of()));
		assertEquals(0, DebugPanelLayout.visibleRowCount(100, List.of(), 0));
	}

	private static int totalHeight() {
		return COLUMN.stream().mapToInt(Integer::intValue).sum();
	}

	private static int heightOf(int from, int count) {
		return IntStream.range(from, from + count).map(COLUMN::get).sum();
	}

	private static List<Integer> buildColumn() {
		List<Integer> rows = new java.util.ArrayList<>();
		rows.add(11);
		for (int line = 0; line < DebugReadout.PANEL_LINE_COUNT; line++) rows.add(10);
		for (int group = 0; group < 5; group++) {
			rows.add(10);
			rows.add(22);
		}
		rows.add(10);
		rows.add(22);
		rows.add(22);
		return List.copyOf(rows);
	}
}
