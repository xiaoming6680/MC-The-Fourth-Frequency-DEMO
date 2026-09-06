package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.terminal.TerminalSelfTest;
import com.xm.thefourthfrequency.terminal.TerminalUiLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Draws the short power-on check that runs on every open.
 *
 * <p>Holds no authority and owns no clock. {@code TerminalSelfTest} says how long it lasts and how
 * far each line has printed; {@code TerminalScreen} says when it started and when it has been
 * skipped. This decides only what four status lines look like.
 *
 * <p>Deliberately narrower than the first-boot self test: no title, no progress bar, no percentage.
 * That one is a ceremony and is meant to be watched; this is a device answering a question it was
 * not asked, in the half second before the page it was opened for arrives.
 */
final class TerminalSelfTestOverlay {
	private static final int LINE_HEIGHT = 11;
	/** The band stages the status bar counts against; the same 3 it prints. */
	private static final int LINK_STAGES = 3;

	private final String[] full = new String[TerminalSelfTest.LINE_COUNT];
	private final String[] shown = new String[TerminalSelfTest.LINE_COUNT];
	private final int[] shownChars = new int[TerminalSelfTest.LINE_COUNT];
	private boolean ready;

	/**
	 * Resolves the four readings once, off the snapshot that is live when the check starts.
	 *
	 * <p>Once, for two reasons. {@code getString()} walks the language table, so doing it per frame
	 * would resolve four lines sixty times a second to print the same text. And a reading that
	 * changed halfway through printing would be a status line correcting itself mid-character,
	 * which reads as a glitch rather than as a measurement.
	 */
	void prepare(TerminalSnapshot snapshot, int storedItems) {
		if (ready) return;
		int stage = snapshot.visualStage();
		full[TerminalSelfTest.Line.POWER.ordinal()] =
				Component.translatable("terminal.thefourthfrequency.selftest.line.power").getString();
		full[TerminalSelfTest.Line.STORE.ordinal()] =
				Component.translatable("terminal.thefourthfrequency.selftest.line.store",
						Math.max(0, storedItems)).getString();
		full[TerminalSelfTest.Line.LINK.ordinal()] = snapshot.bandStage() <= 0
				? Component.translatable("terminal.thefourthfrequency.selftest.line.link_unauthorised").getString()
				: Component.translatable("terminal.thefourthfrequency.selftest.line.link",
						snapshot.bandStage(), LINK_STAGES).getString();
		// The one line that is not a restatement of something already on the panel. The figure comes
		// off the pitch table itself, so what the machine prints here is exactly what the player has
		// been hearing under every press since the stage last moved.
		full[TerminalSelfTest.Line.BASELINE.ordinal()] = TerminalSelfTest.baselineCalibrated(stage)
				? Component.translatable("terminal.thefourthfrequency.selftest.line.baseline_calibrated").getString()
				: Component.translatable("terminal.thefourthfrequency.selftest.line.baseline_drift",
						TerminalSelfTest.baselineDriftPercent(stage)).getString();
		for (int line = 0; line < TerminalSelfTest.LINE_COUNT; line++) {
			shown[line] = "";
			shownChars[line] = 0;
		}
		ready = true;
	}

	/**
	 * The check itself.
	 *
	 * <p>No panel and no plate: the lines sit on the display glass the way every other line in the
	 * terminal does. The page body is not drawn underneath while this runs, so there is nothing to
	 * hide - and filling a rectangle here would put a sticker over the device, which the palette
	 * rules forbid for exactly that reason.
	 */
	void draw(GuiGraphics graphics, Font font, long elapsedMillis) {
		if (!ready) return;
		var body = TerminalUiLayout.PAGE_BODY;
		int visible = TerminalSelfTest.visibleLines(elapsedMillis);
		int y = body.top() + 10;
		for (int line = 0; line < visible; line++) {
			String text = typedPrefix(line, elapsedMillis);
			graphics.drawString(font, text, body.left() + 9, y, TerminalVisualTheme.GREEN, false);
			if (text.length() < full[line].length()) {
				int caret = body.left() + 9 + font.width(text) + 1;
				graphics.fill(caret, y, caret + 4, y + font.lineHeight - 1, TerminalVisualTheme.GREEN);
			}
			y += LINE_HEIGHT;
		}
	}

	/** Reuses the rendered prefix whenever the visible length has not changed. */
	private String typedPrefix(int line, long elapsedMillis) {
		String text = full[line];
		int total = text.codePointCount(0, text.length());
		int want = TerminalSelfTest.typedCharacters(total, elapsedMillis, line);
		if (want == shownChars[line] && shown[line] != null) return shown[line];
		shownChars[line] = want;
		shown[line] = want >= total ? text : text.substring(0, text.offsetByCodePoints(0, want));
		return shown[line];
	}
}
