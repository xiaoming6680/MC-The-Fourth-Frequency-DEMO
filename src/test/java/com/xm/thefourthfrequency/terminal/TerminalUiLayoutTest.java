package com.xm.thefourthfrequency.terminal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalUiLayoutTest {
	@Test
	void everyTextAndInteractionRegionStaysInsideItsPanel() {
		var display = TerminalUiLayout.DISPLAY;
		for (var bounds : List.of(TerminalUiLayout.HOME_TAB, TerminalUiLayout.TOOLS_TAB,
				TerminalUiLayout.RECORDS_TAB, TerminalUiLayout.FILES_TAB, TerminalUiLayout.PAGE_BODY,
				TerminalUiLayout.HOME_TASK, TerminalUiLayout.HOME_QUICK_PRIMARY,
				TerminalUiLayout.HOME_QUICK_SECONDARY, TerminalUiLayout.HOME_TOOL_DETAIL,
				TerminalUiLayout.HOME_TOOL_CLOSE, TerminalUiLayout.HOME_RECENT,
				TerminalUiLayout.TOOLS_GRID, TerminalUiLayout.TOOL_HEADER, TerminalUiLayout.TOOL_DETAIL,
				TerminalUiLayout.RECORDS_BODY, TerminalUiLayout.FILE_BODY, TerminalUiLayout.FILE_LIST,
				TerminalUiLayout.FILE_DIVIDER, TerminalUiLayout.FILE_CONTENT, TerminalUiLayout.FOOTER,
				TerminalUiLayout.TOOL_BACK, TerminalUiLayout.KEYPAD)) {
			assertTrue(display.contains(bounds), () -> bounds + " escaped " + display);
		}
		for (var bounds : List.of(TerminalUiLayout.TOOL_OPTION_ONE, TerminalUiLayout.TOOL_OPTION_TWO,
				TerminalUiLayout.TOOL_OPTION_THREE, TerminalUiLayout.TOOL_ACTION_PRIMARY,
				TerminalUiLayout.TOOL_ACTION_SECONDARY, TerminalUiLayout.TOOL_ACTION_FULL)) {
			assertTrue(TerminalUiLayout.TOOL_DETAIL.contains(bounds));
		}
		for (var hardware : List.of(TerminalUiLayout.SCOPE, TerminalUiLayout.UNREAD_LAMP,
				TerminalUiLayout.COMPASS, TerminalUiLayout.RECEIVER_SLIDER,
				TerminalUiLayout.RECEIVER_LCD, TerminalUiLayout.CLOSE_HINT)) {
			assertTrue(TerminalUiLayout.HARDWARE_SAFE.contains(hardware),
					() -> hardware + " escaped " + TerminalUiLayout.HARDWARE_SAFE);
			assertFalse(display.contains(hardware));
		}
		assertTrue(TerminalUiLayout.RECEIVER_LCD.contains(TerminalUiLayout.LCD_LINE_ONE));
		assertTrue(TerminalUiLayout.RECEIVER_LCD.contains(TerminalUiLayout.LCD_LINE_TWO));
	}

	@Test
	void statusStripSitsBelowThePageAndSplitsIntoFourDisjointCells() {
		var bar = TerminalUiLayout.STATUS_BAR;
		assertTrue(TerminalUiLayout.DISPLAY.contains(bar));
		assertEquals(bar, TerminalUiLayout.FOOTER, "the old name must keep pointing at the same band");
		assertTrue(bar.top() >= TerminalUiLayout.PAGE_BODY.bottom(),
				() -> "status strip " + bar + " overlapped the page body " + TerminalUiLayout.PAGE_BODY);

		var cells = List.of(TerminalUiLayout.STATUS_HOLDER, TerminalUiLayout.STATUS_CLOCK,
				TerminalUiLayout.STATUS_LINK, TerminalUiLayout.STATUS_PROTOCOL);
		for (var cell : cells) {
			assertTrue(bar.contains(cell), () -> cell + " escaped the status strip " + bar);
		}
		for (int first = 0; first < cells.size(); first++) {
			for (int second = first + 1; second < cells.size(); second++) {
				var left = cells.get(first);
				var right = cells.get(second);
				assertTrue(disjoint(left, right), () -> left + " overlapped " + right);
			}
		}
	}

	/**
	 * The three horizontal bands the display is divided into may never share a pixel. Nothing
	 * asserted this before, so the status strip could have been placed on top of the page body and
	 * only a screenshot would have caught it.
	 */
	@Test
	void tabStripPageBodyAndStatusStripNeverOverlap() {
		var tabs = new TerminalUiLayout.Bounds(TerminalUiLayout.HOME_TAB.left(),
				TerminalUiLayout.HOME_TAB.top(), TerminalUiLayout.FILES_TAB.right(),
				TerminalUiLayout.FILES_TAB.bottom());
		var layers = List.of(tabs, TerminalUiLayout.PAGE_BODY, TerminalUiLayout.STATUS_BAR);
		for (int first = 0; first < layers.size(); first++) {
			for (int second = first + 1; second < layers.size(); second++) {
				var upper = layers.get(first);
				var lower = layers.get(second);
				assertTrue(disjoint(upper, lower), () -> upper + " overlapped " + lower);
			}
		}
		for (var layer : layers) assertTrue(TerminalUiLayout.DISPLAY.contains(layer));
	}

	private static boolean disjoint(TerminalUiLayout.Bounds first, TerminalUiLayout.Bounds second) {
		return first.right() <= second.left() || second.right() <= first.left()
				|| first.bottom() <= second.top() || second.bottom() <= first.top();
	}

	@Test
	void expandedCompassRemainsCenteredAndSeparatedFromAdjacentHardware() {
		var compass = TerminalUiLayout.COMPASS;
		assertEquals(42, compass.width());
		assertEquals(42, compass.height());
		// Centred on the instruments it sits between rather than on the scope alone. The scope gave
		// up its right-hand strip to the unread lamp and is no longer the widest thing on the column.
		assertEquals((TerminalUiLayout.RECEIVER_SLIDER.left() + TerminalUiLayout.RECEIVER_SLIDER.right()) / 2,
				(compass.left() + compass.right()) / 2);
		assertEquals((TerminalUiLayout.RECEIVER_LCD.left() + TerminalUiLayout.RECEIVER_LCD.right()) / 2,
				(compass.left() + compass.right()) / 2);
		assertTrue(compass.top() - TerminalUiLayout.SCOPE.bottom() >= 5);
		assertTrue(TerminalUiLayout.RECEIVER_SLIDER.top() - compass.bottom() >= 5);
	}

	/**
	 * The unread lamp gets its own pixels.
	 *
	 * <p>The four instruments and the close hint each already answer a question of their own, and a
	 * lamp overlaid on any of them would make that instrument ambiguous exactly when the player
	 * most needs to trust it. So this asserts the strong form: the lamp shares no pixel with any of
	 * them, and it lives in the strip the oscilloscope gave up rather than outside the column.</p>
	 */
	@Test
	void unreadLampOwnsItsOwnRegionBesideTheScopeAndOverlapsNoInstrument() {
		var lamp = TerminalUiLayout.UNREAD_LAMP;
		assertTrue(TerminalUiLayout.HARDWARE_SAFE.contains(lamp),
				() -> lamp + " escaped " + TerminalUiLayout.HARDWARE_SAFE);
		for (var instrument : List.of(TerminalUiLayout.SCOPE, TerminalUiLayout.COMPASS,
				TerminalUiLayout.RECEIVER_SLIDER, TerminalUiLayout.RECEIVER_LCD,
				TerminalUiLayout.LCD_LINE_ONE, TerminalUiLayout.LCD_LINE_TWO,
				TerminalUiLayout.CLOSE_HINT)) {
			assertTrue(disjoint(lamp, instrument), () -> lamp + " overlapped " + instrument);
		}
		assertTrue(lamp.left() >= TerminalUiLayout.SCOPE.right(),
				"the lamp belongs to the right of the scope, not on top of it");
		assertTrue(lamp.width() >= 12 && lamp.height() >= 12,
				() -> "an indicator this small cannot be seen at panel scale: " + lamp);
	}

	/**
	 * The lamp blinks, and the blink stays inside the two limits a blink has to answer to.
	 *
	 * <p>It can be lit for an entire session, so the cycle is held well under 3 Hz and both plateaus
	 * are longer than the seven-tick minimum hold. The edges ramp rather than jump, which is what
	 * keeps it a blink rather than a strobe.</p>
	 */
	@Test
	void unreadLampBlinksWithinTheFlickerCeilingAndTheMinimumHold() {
		double period = TerminalUiLayout.UNREAD_LAMP_PERIOD_TICKS / 20.0D;
		assertTrue(1.0D / period <= 3.0D,
				() -> "lamp frequency " + 1.0D / period + " Hz is over the 3 Hz ceiling");

		// Both plateaus outlast the minimum hold, so neither state is a flash.
		assertTrue(TerminalUiLayout.UNREAD_LAMP_ON_TICKS >= 7,
				"the lit plateau is shorter than the minimum hold");
		int dark = TerminalUiLayout.UNREAD_LAMP_PERIOD_TICKS
				- TerminalUiLayout.UNREAD_LAMP_ON_TICKS - TerminalUiLayout.UNREAD_LAMP_EDGE_TICKS * 2;
		assertTrue(dark >= 7, () -> "the dark plateau is only " + dark + " ticks");

		// It really does reach both ends - a "blink" that never goes out is the thing this replaced.
		assertEquals(TerminalUiLayout.UNREAD_LAMP_MIN_INTENSITY,
				TerminalUiLayout.unreadLampIntensity(0.0D), 1.0E-9D);
		assertEquals(1.0D, TerminalUiLayout.unreadLampIntensity(
				TerminalUiLayout.UNREAD_LAMP_EDGE_TICKS + 1.0D), 1.0E-9D);
		assertEquals(TerminalUiLayout.UNREAD_LAMP_MIN_INTENSITY,
				TerminalUiLayout.unreadLampIntensity(
						TerminalUiLayout.UNREAD_LAMP_PERIOD_TICKS - 1.0D), 1.0E-9D);
		assertEquals(TerminalUiLayout.unreadLampIntensity(3.5D),
				TerminalUiLayout.unreadLampIntensity(3.5D + TerminalUiLayout.UNREAD_LAMP_PERIOD_TICKS),
				1.0E-9D);

		// Sampled at render rates, not tick rates: the callers pass a fractional render age, and an
		// edge that lands entirely inside one frame is the shape the ceiling exists to forbid.
		double previous = TerminalUiLayout.unreadLampIntensity(0.0D);
		for (int step = 0; step <= 4000; step++) {
			final double age = step * 0.05D;
			final double value = TerminalUiLayout.unreadLampIntensity(age);
			final double last = previous;
			assertTrue(value >= TerminalUiLayout.UNREAD_LAMP_MIN_INTENSITY - 1.0E-9D && value <= 1.0D,
					() -> "lamp intensity left its band at " + age + ": " + value);
			assertTrue(Math.abs(value - last) < 0.08D,
					() -> "lamp jumped rather than ramped at " + age);
			previous = value;
		}
	}

	@Test
	void hintAndScrollClamp() {
		assertEquals(255, TerminalUiLayout.hintAlpha(40));
		assertEquals(128, TerminalUiLayout.hintAlpha(50));
		assertEquals(0, TerminalUiLayout.hintAlpha(60));
		assertEquals(0, TerminalUiLayout.scroll(0, -3, 8));
		assertEquals(8, TerminalUiLayout.scroll(7, 9, 8));
	}

	@Test
	void unreadAlertStartsLitAndStopsFlashingAfterTwoSeconds() {
		assertTrue(TerminalUiLayout.unreadFlashOn(0.0D));
		assertTrue(TerminalUiLayout.unreadFlashOn(9.99D));
		assertFalse(TerminalUiLayout.unreadFlashOn(10.0D));
		assertFalse(TerminalUiLayout.unreadFlashOn(19.99D));
		assertTrue(TerminalUiLayout.unreadFlashOn(20.0D));
		assertFalse(TerminalUiLayout.unreadFlashOn(30.0D));
		assertFalse(TerminalUiLayout.unreadFlashOn(40.0D));
	}

	@Test
	void horizontalSliderMapsAndClampsBothEndpoints() {
		assertEquals(0, TerminalUiLayout.sliderTuning(400));
		assertEquals(100, TerminalUiLayout.sliderTuning(484));
		assertEquals(0, TerminalUiLayout.sliderTuning(-200));
		assertEquals(100, TerminalUiLayout.sliderTuning(900));
		assertEquals(400, TerminalUiLayout.sliderX(0));
		assertEquals(484, TerminalUiLayout.sliderX(100));
		assertEquals(442, TerminalUiLayout.sliderX(50));
	}

	@Test
	void toolGridKeepsSixFixedSlotsWhenSomeToolsAreHidden() {
		assertEquals(3, TerminalUiLayout.TOOL_COLUMNS);
		assertEquals(2, TerminalUiLayout.TOOL_ROWS);
		for (int slot = 0; slot < 6; slot++) {
			var cell = TerminalUiLayout.toolCell(slot);
			assertTrue(TerminalUiLayout.TOOLS_GRID.contains(cell));
			assertEquals(slot, TerminalUiLayout.toolSlotAt(cell.left() + 1, cell.top() + 1));
			assertEquals(slot, TerminalTool.fromSlot(slot).slot());
		}
		assertEquals(TerminalUiLayout.toolCell(5), TerminalUiLayout.toolCell(99));
	}

	@Test
	void fourClientPagesKeepTheTwoExistingWireModes() {
		assertEquals(4, TerminalPage.values().length);
		assertEquals(TerminalPage.HOME, TerminalPage.values()[0]);
		assertEquals(TerminalPage.TOOLS, TerminalPage.values()[1]);
		assertEquals(TerminalPage.RECORDS, TerminalPage.values()[2]);
		assertEquals(TerminalPage.FILES, TerminalPage.values()[3]);
		assertEquals(TerminalControlPolicy.Mode.SIGNAL.ordinal(), TerminalPage.HOME.wireMode());
		assertEquals(TerminalControlPolicy.Mode.SIGNAL.ordinal(), TerminalPage.TOOLS.wireMode());
		assertEquals(TerminalControlPolicy.Mode.SIGNAL.ordinal(), TerminalPage.RECORDS.wireMode());
		assertEquals(TerminalControlPolicy.Mode.FILES.ordinal(), TerminalPage.FILES.wireMode());
		assertEquals(TerminalPage.HOME, TerminalPage.initialPage(TerminalControlPolicy.Mode.SIGNAL.ordinal()));
		assertEquals(TerminalPage.FILES, TerminalPage.initialPage(TerminalControlPolicy.Mode.FILES.ordinal()));
	}

	@Test
	void navigationOptionHitRowTracksTheButtonsItGates() {
		assertEquals(3, TerminalUiLayout.TOOL_OPTION_SLOTS);
		var row = TerminalUiLayout.TOOL_OPTION_ROW;
		for (int index = 0; index < TerminalUiLayout.TOOL_OPTION_SLOTS; index++) {
			var option = TerminalUiLayout.navigationOptionBounds(index);
			assertTrue(row.contains(option), () -> option + " escaped the click row " + row);
			assertTrue(TerminalUiLayout.TOOL_LIST_AREA.contains(option));
		}
		assertEquals(TerminalUiLayout.TOOL_OPTION_ONE, TerminalUiLayout.navigationOptionBounds(0));
		assertEquals(TerminalUiLayout.TOOL_OPTION_THREE, TerminalUiLayout.navigationOptionBounds(2));
		assertEquals(TerminalUiLayout.TOOL_OPTION_THREE, TerminalUiLayout.navigationOptionBounds(99));
		// The row is the union of the buttons, so a click just inside either end still counts and a
		// click past them does not - that is the property a hand-written copy of these numbers lost.
		assertEquals(TerminalUiLayout.TOOL_OPTION_ONE.left(), row.left());
		assertEquals(TerminalUiLayout.TOOL_OPTION_THREE.right(), row.right());
		assertFalse(row.contains(row.left() - 1, row.top() + 1));
		assertFalse(row.contains(row.right(), row.top() + 1));
		assertTrue(TerminalUiLayout.TOOL_DETAIL.contains(TerminalUiLayout.TOOL_LIST_AREA));
	}

	@Test
	void fileListAndContentUseIndependentThirtySeventyRegions() {
		assertTrue(TerminalUiLayout.FILE_BODY.contains(TerminalUiLayout.FILE_LIST));
		assertTrue(TerminalUiLayout.FILE_BODY.contains(TerminalUiLayout.FILE_CONTENT));
		assertTrue(TerminalUiLayout.FILE_LIST.right() <= TerminalUiLayout.FILE_DIVIDER.left());
		assertTrue(TerminalUiLayout.FILE_DIVIDER.right() <= TerminalUiLayout.FILE_CONTENT.left());
		double listShare = TerminalUiLayout.FILE_LIST.width()
				/ (double) (TerminalUiLayout.FILE_LIST.width() + TerminalUiLayout.FILE_CONTENT.width());
		assertTrue(listShare >= 0.28D && listShare <= 0.32D, () -> "list share=" + listShare);
		assertEquals(6, TerminalUiLayout.FILE_LIST_VISIBLE_ROWS);
		assertEquals(6, TerminalUiLayout.fileMaxScrollRow(12));
		assertEquals(0, TerminalUiLayout.fileMaxScrollRow(6));
		for (int row = 0; row < TerminalUiLayout.FILE_LIST_VISIBLE_ROWS; row++) {
			var bounds = TerminalUiLayout.fileListRow(row);
			assertTrue(TerminalUiLayout.FILE_LIST.contains(bounds));
			assertEquals(row, TerminalUiLayout.fileIndexAt(bounds.left() + 1, bounds.top() + 1, 0, 12));
		}
		var last = TerminalUiLayout.fileListRow(5);
		assertEquals(11, TerminalUiLayout.fileIndexAt(last.left() + 1, last.top() + 1, 6, 12));
		assertEquals(-1, TerminalUiLayout.fileIndexAt(TerminalUiLayout.FILE_CONTENT.left() + 1,
				TerminalUiLayout.FILE_CONTENT.top() + 1, 0, 12));
	}
}
