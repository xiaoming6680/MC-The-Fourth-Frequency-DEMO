package com.xm.thefourthfrequency.terminal;

public final class TerminalUiLayout {
	public static final Bounds DISPLAY = new Bounds(36, 40, 356, 216);
	public static final Bounds HOME_TAB = new Bounds(42, 45, 116, 62);
	public static final Bounds TOOLS_TAB = new Bounds(119, 45, 193, 62);
	public static final Bounds RECORDS_TAB = new Bounds(196, 45, 270, 62);
	public static final Bounds FILES_TAB = new Bounds(273, 45, 350, 62);
	public static final Bounds PAGE_BODY = new Bounds(42, 70, 350, 199);
	public static final Bounds HOME_TASK = new Bounds(44, 72, 348, 119);
	public static final Bounds HOME_QUICK_PRIMARY = new Bounds(44, 123, 194, 164);
	public static final Bounds HOME_QUICK_SECONDARY = new Bounds(198, 123, 348, 164);
	public static final Bounds HOME_TOOL_DETAIL = new Bounds(44, 123, 348, 164);
	public static final Bounds HOME_TOOL_CLOSE = new Bounds(302, 129, 342, 158);
	public static final Bounds HOME_RECENT = new Bounds(44, 168, 348, 196);
	public static final Bounds TOOLS_GRID = new Bounds(44, 72, 348, 190);
	public static final Bounds TOOL_HEADER = new Bounds(44, 72, 348, 92);
	public static final Bounds TOOL_DETAIL = new Bounds(44, 96, 348, 196);
	public static final Bounds RECORDS_BODY = new Bounds(42, 70, 350, 199);
	public static final Bounds FILE_BODY = new Bounds(42, 70, 350, 199);
	public static final Bounds FILE_LIST = new Bounds(44, 72, 133, 196);
	public static final Bounds FILE_DIVIDER = new Bounds(134, 72, 136, 196);
	public static final Bounds FILE_CONTENT = new Bounds(137, 72, 348, 196);
	/**
	 * The always-on status strip: holder, world clock, signal band, protocol.
	 *
	 * <p>It sits below the page rather than above it. The eight pixels between the tab strip and
	 * {@link #PAGE_BODY} cannot hold a row of text, and buying the room by pushing the page down
	 * would move {@code HOME_TASK}, {@code TOOLS_GRID}, {@code TOOL_HEADER}, the three file regions
	 * and everything derived from them. This band was already reserved and drawing nothing.</p>
	 */
	public static final Bounds STATUS_BAR = new Bounds(42, 202, 350, 215);
	/** @deprecated Use {@link #STATUS_BAR}. Kept so tests that list this region by name still compile. */
	@Deprecated
	public static final Bounds FOOTER = STATUS_BAR;
	public static final Bounds STATUS_HOLDER = new Bounds(44, 203, 160, 214);
	public static final Bounds STATUS_CLOCK = new Bounds(162, 203, 236, 214);
	public static final Bounds STATUS_LINK = new Bounds(238, 203, 300, 214);
	public static final Bounds STATUS_PROTOCOL = new Bounds(302, 203, 348, 214);
	/**
	 * The one-line brief shown under the first-boot walkthrough's pointer.
	 *
	 * <p>Inside the page body, which the walkthrough has already dimmed, and low enough that the
	 * card it covers is the least informative part of whichever page happens to be showing. It names
	 * the page <em>currently on screen</em> and says what it holds - the pointer only ever said
	 * which tab to click next, which tells a new player where to put the cursor and nothing about
	 * what is in front of them. See {@code TerminalOnboardingPolicy#briefSubject}.</p>
	 *
	 * <p>Only drawn while the walkthrough is holding the terminal, so it costs no room the rest of
	 * the time and cannot collide with a page's own content in the general case.</p>
	 */
	/**
	 * The walkthrough's explanation panel, and the button that leaves it.
	 *
	 * <p>Grown from a single 24-pixel strip. The walkthrough used to say one line about the page and
	 * then wait for the player to find the right tab; it now explains each page properly and moves on
	 * when the player presses a button, so it needs room for a heading, a few lines and a control.
	 * The panel stops short of the status bar, which keeps its own step counter.
	 */
	public static final Bounds ONBOARD_BRIEF = new Bounds(46, 150, 346, 196);
	/**
	 * The advance button, moved out of the panel and into the status strip.
	 *
	 * <p>It costs the panel nothing to be here - the walkthrough already owns this band, which stands
	 * down for it rather than being covered over - and it buys the page underneath twenty-odd pixels
	 * back. That matters most on Home, where the panel's own second line points at a row of cards the
	 * panel was sitting on top of; a walkthrough that hides the thing it is describing is explaining
	 * a blank rectangle.
	 *
	 * <p>Thirteen pixels tall rather than sixteen, which is the strip's full height. The label is nine
	 * and the outline needs the other four.
	 */
	public static final Bounds ONBOARD_NEXT = new Bounds(252, 202, 348, 215);
	public static final Bounds TOOL_BACK = new Bounds(44, 74, 92, 90);
	public static final Bounds TOOL_OPTION_ONE = new Bounds(50, 139, 142, 157);
	public static final Bounds TOOL_OPTION_TWO = new Bounds(147, 139, 239, 157);
	public static final Bounds TOOL_OPTION_THREE = new Bounds(244, 139, 342, 157);
	/**
	 * The band the three option buttons occupy, derived rather than restated. The navigation tool
	 * gates its clicks on this row, and a hand-written copy of these extents silently stops matching
	 * the buttons the moment one of the three moves.
	 */
	public static final Bounds TOOL_OPTION_ROW = new Bounds(TOOL_OPTION_ONE.left(), TOOL_OPTION_ONE.top(),
			TOOL_OPTION_THREE.right(), TOOL_OPTION_THREE.bottom());
	/** The whole option area, used for the centred "no targets" message when the row is empty. */
	public static final Bounds TOOL_LIST_AREA = new Bounds(TOOL_OPTION_ONE.left(), TOOL_OPTION_ONE.top() - 15,
			TOOL_OPTION_THREE.right(), TOOL_OPTION_THREE.bottom() + 5);
	public static final Bounds TOOL_ACTION_PRIMARY = new Bounds(50, 166, 190, 188);
	public static final Bounds TOOL_ACTION_SECONDARY = new Bounds(202, 166, 342, 188);
	public static final Bounds TOOL_ACTION_FULL = new Bounds(50, 166, 342, 188);
	public static final Bounds KEYPAD = new Bounds(149, 123, 336, 184);
	public static final Bounds HARDWARE_SAFE = new Bounds(389, 24, 495, 230);
	/**
	 * The oscilloscope.
	 *
	 * <p>Sixteen pixels narrower than it used to be, to open the strip {@link #UNREAD_LAMP} sits
	 * in. The trace is drawn from the width of this rectangle rather than a fixed sample pitch, so
	 * nothing about the waveform had to move with it.</p>
	 */
	public static final Bounds SCOPE = new Bounds(400, 46, 468, 88);
	/**
	 * The unread lamp: a small amber indicator to the right of the oscilloscope.
	 *
	 * <p>Its own region rather than a corner borrowed from a neighbour. Every other instrument on
	 * this column already answers a question - where north is, how strong the carrier is, what the
	 * receiver thinks - and overlaying a second meaning on one of them would make that instrument
	 * ambiguous exactly when it matters. The four instruments and the close hint keep every pixel
	 * they had; only the scope gave ground, and it gave it sideways.</p>
	 *
	 * <p>It is not a stage light. Amber at every stage, including the cyan and red ones, because it
	 * reports whether something is waiting - a question the stage does not change the answer to.</p>
	 */
	public static final Bounds UNREAD_LAMP = new Bounds(472, 46, 488, 62);
	public static final Bounds COMPASS = new Bounds(421, 94, 463, 136);
	public static final Bounds RECEIVER_SLIDER = new Bounds(400, 141, 484, 163);
	public static final Bounds RECEIVER_LCD = new Bounds(400, 176, 484, 204);
	public static final Bounds LCD_LINE_ONE = new Bounds(404, 180, 480, 190);
	public static final Bounds LCD_LINE_TWO = new Bounds(404, 191, 480, 201);
	public static final Bounds CLOSE_HINT = new Bounds(400, 209, 484, 226);
	public static final int TOOL_COLUMNS = 3;
	public static final int TOOL_ROWS = 2;
	public static final int TOOL_CELL_WIDTH = 98;
	public static final int TOOL_CELL_HEIGHT = 55;
	public static final int TOOL_CELL_GAP_X = 4;
	public static final int TOOL_CELL_GAP_Y = 6;
	public static final int FILE_LIST_ROW_HEIGHT = 18;
	public static final int FILE_LIST_VISIBLE_ROWS = 6;
	public static final int HINT_HOLD_TICKS = 40;
	public static final int HINT_END_TICKS = 60;
	public static final int UNREAD_FLASH_HALF_PERIOD_TICKS = 10;
	public static final int UNREAD_FLASH_DURATION_TICKS = 40;
	/**
	 * Blink period of the unread lamp, in ticks. 1.2 seconds, so 0.83 Hz.
	 *
	 * <p>Still comfortably under the project's 3 Hz ceiling, and both the lit and the dark plateau
	 * are longer than the seven-tick minimum hold. This lamp is on screen for as long as anything is
	 * unread, which can be the whole session, so its period is deliberately long: the eye should
	 * catch it in the corner of the frame, not be held by it.</p>
	 */
	public static final int UNREAD_LAMP_PERIOD_TICKS = 24;
	/** The dark half of a blink. Fully out - the lens and its brass housing are still drawn. */
	public static final double UNREAD_LAMP_MIN_INTENSITY = 0.0D;

	private TerminalUiLayout() { }

	public static int hintAlpha(int ageTicks) {
		if (ageTicks <= HINT_HOLD_TICKS) return 255;
		if (ageTicks >= HINT_END_TICKS) return 0;
		return (int) Math.round(255.0D * (HINT_END_TICKS - ageTicks)
				/ (HINT_END_TICKS - HINT_HOLD_TICKS));
	}

	/**
	 * How much of each period the lamp is lit for.
	 *
	 * <p>A minority of it. A blink that is on longer than it is off reads as a lamp with a fault;
	 * a short pulse against a long dark reads as a lamp reporting something, which is the job.</p>
	 */
	public static final int UNREAD_LAMP_ON_TICKS = 8;
	/**
	 * The rise and fall at each edge.
	 *
	 * <p>Not zero. A single-frame edge is the exact shape the 3 Hz rule is about, and at 60 fps it
	 * also aliases into a hard click of light that no amount of period length softens. Two ticks is
	 * still unmistakably a blink - the eye reads the plateau, not the edge - while keeping the
	 * per-frame delta bounded and the whole curve continuous.</p>
	 */
	public static final int UNREAD_LAMP_EDGE_TICKS = 2;

	/**
	 * The unread lamp's brightness, {@link #UNREAD_LAMP_MIN_INTENSITY}..1, as a blink.
	 *
	 * <p>A short lit plateau with a long dark one between, and a two-tick ramp at each edge so the
	 * transition is continuous rather than a single-frame jump. Both plateaus are longer than the
	 * seven-tick minimum hold and the whole cycle runs at {@code 20 / UNREAD_LAMP_PERIOD_TICKS} Hz,
	 * well under the 3 Hz ceiling - the two limits this has to answer to.</p>
	 *
	 * <p>This was a raised cosine that never went dark, on the argument that a lamp which can be lit
	 * for a whole session should not pull at the eye. The blink is a deliberate reversal of that
	 * call: breathing at 0.55 minimum never actually read as <em>changing</em> on the panel, so the
	 * indicator was doing its job only for a player who already knew to look at it.</p>
	 *
	 * <p>Callers pass the render age, so this must accept fractional ticks and stay smooth across
	 * them; a version that floored to whole ticks would be a 20 Hz staircase.</p>
	 */
	public static double unreadLampIntensity(double elapsedTicks) {
		double phase = Math.floorMod((long) Math.floor(elapsedTicks), (long) UNREAD_LAMP_PERIOD_TICKS)
				+ (elapsedTicks - Math.floor(elapsedTicks));
		double level;
		if (phase < UNREAD_LAMP_EDGE_TICKS) {
			level = smoothstep(phase / UNREAD_LAMP_EDGE_TICKS);
		} else if (phase < UNREAD_LAMP_EDGE_TICKS + UNREAD_LAMP_ON_TICKS) {
			level = 1.0D;
		} else if (phase < UNREAD_LAMP_EDGE_TICKS * 2 + UNREAD_LAMP_ON_TICKS) {
			level = 1.0D - smoothstep(
					(phase - UNREAD_LAMP_EDGE_TICKS - UNREAD_LAMP_ON_TICKS) / UNREAD_LAMP_EDGE_TICKS);
		} else {
			level = 0.0D;
		}
		return UNREAD_LAMP_MIN_INTENSITY + (1.0D - UNREAD_LAMP_MIN_INTENSITY) * level;
	}

	private static double smoothstep(double linear) {
		double t = Math.clamp(linear, 0.0D, 1.0D);
		return t * t * (3.0D - 2.0D * t);
	}

	/**
	 * The same blink as a yes/no, for the lamp baked into the item's texture.
	 *
	 * <p>The device in the hand cannot dim: its lamp is two of the six atlases, lit or not. So it
	 * takes the plateau and ignores the ramps, and reads from {@link #unreadLampIntensity} rather
	 * than from a second copy of the timing - the lamp on the panel and the lamp on the machine are
	 * one statement shown twice, and the moment they are derived separately they can disagree.</p>
	 */
	public static boolean unreadLampBlinkOn(double elapsedTicks) {
		return unreadLampIntensity(elapsedTicks) >= 0.5D;
	}

	public static boolean unreadFlashOn(double elapsedTicks) {
		double elapsed = Math.max(0.0D, elapsedTicks);
		if (elapsed >= UNREAD_FLASH_DURATION_TICKS) return false;
		long tick = (long) Math.floor(elapsed);
		return Math.floorMod(tick, UNREAD_FLASH_HALF_PERIOD_TICKS * 2L)
				< UNREAD_FLASH_HALF_PERIOD_TICKS;
	}

	public static int scroll(int current, int delta, int maximum) {
		return Math.clamp(current + delta, 0, Math.max(0, maximum));
	}

	public static int sliderTuning(double x) {
		return Math.clamp((int) Math.round((x - RECEIVER_SLIDER.left()) * 100.0D / RECEIVER_SLIDER.width()), 0, 100);
	}

	public static int sliderX(int tuning) {
		return RECEIVER_SLIDER.left() + (int) Math.round(RECEIVER_SLIDER.width() * Math.clamp(tuning, 0, 100) / 100.0D);
	}

	public static Bounds toolCell(int slot) {
		int safe = Math.clamp(slot, 0, TOOL_COLUMNS * TOOL_ROWS - 1);
		int column = safe % TOOL_COLUMNS;
		int row = safe / TOOL_COLUMNS;
		int left = TOOLS_GRID.left() + column * (TOOL_CELL_WIDTH + TOOL_CELL_GAP_X);
		int top = TOOLS_GRID.top() + row * (TOOL_CELL_HEIGHT + TOOL_CELL_GAP_Y);
		return new Bounds(left, top, left + TOOL_CELL_WIDTH, top + TOOL_CELL_HEIGHT);
	}

	public static final int TOOL_OPTION_SLOTS = 3;

	public static Bounds navigationOptionBounds(int index) {
		return switch (Math.clamp(index, 0, TOOL_OPTION_SLOTS - 1)) {
			case 0 -> TOOL_OPTION_ONE;
			case 1 -> TOOL_OPTION_TWO;
			default -> TOOL_OPTION_THREE;
		};
	}

	public static int toolSlotAt(double x, double y) {
		for (int slot = 0; slot < TOOL_COLUMNS * TOOL_ROWS; slot++) if (toolCell(slot).contains(x, y)) return slot;
		return -1;
	}

	public static int fileMaxScrollRow(int fileCount) {
		return Math.max(0, Math.max(0, fileCount) - FILE_LIST_VISIBLE_ROWS);
	}

	public static Bounds fileListRow(int visibleRow) {
		int safe = Math.clamp(visibleRow, 0, FILE_LIST_VISIBLE_ROWS - 1);
		int top = FILE_LIST.top() + safe * FILE_LIST_ROW_HEIGHT;
		return new Bounds(FILE_LIST.left(), top, FILE_LIST.right(), top + FILE_LIST_ROW_HEIGHT);
	}

	public static int fileIndexAt(double x, double y, int scrollRow, int fileCount) {
		if (!FILE_LIST.contains(x, y)) return -1;
		int visibleRow = Math.clamp((int) ((y - FILE_LIST.top()) / FILE_LIST_ROW_HEIGHT), 0, FILE_LIST_VISIBLE_ROWS - 1);
		int index = Math.max(0, scrollRow) + visibleRow;
		return index < Math.max(0, fileCount) ? index : -1;
	}

	public record Bounds(int left, int top, int right, int bottom) {
		public int width() { return right - left; }
		public int height() { return bottom - top; }
		public boolean contains(Bounds child) {
			return child.left >= left && child.top >= top && child.right <= right && child.bottom <= bottom;
		}
		public boolean contains(double x, double y) {
			return x >= left && x < right && y >= top && y < bottom;
		}
	}
}
