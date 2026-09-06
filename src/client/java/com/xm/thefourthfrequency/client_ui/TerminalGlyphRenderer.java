package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.terminal.TerminalGlyphSettle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws text arriving out of noise, without letting it move while it arrives.
 *
 * <p>Two surfaces settle text in and they must look like the same phenomenon: the records backfill,
 * where the world is doing it, and the first-boot profile, where the player is doing it with the
 * receiver slider. Sharing the drawing is what makes them the same effect rather than two effects
 * that happen to use the same word.
 *
 * <p>The whole reason this is not a one-line {@code drawString} is the cursor. This font is not
 * monospace - {@code i} is two pixels and {@code #} is six - so a line that substituted junk in
 * place and let the font lay it out would stretch and contract as it resolved. Stepping the cursor
 * by the width of the character that <em>belongs</em> at each position instead puts every junk glyph
 * exactly where the real one will land, and nothing moves at all.
 */
final class TerminalGlyphRenderer {
	private TerminalGlyphRenderer() {
	}

	/**
	 * @param seed     fixed for the life of one line, so it resolves the same way every frame
	 * @param rowIndex distinguishes lines that would otherwise share a noise pattern
	 * @param progress 0 for pure noise, 1 for clean
	 * @param bucket   from {@link TerminalGlyphSettle#bucket}, which is what holds the reroll to 3 Hz
	 */
	static void drawSettling(GuiGraphics graphics, Font font, String text, int x, int y, int color,
			long seed, int rowIndex, double progress, long bucket) {
		if (text == null || text.isEmpty()) return;
		if (progress >= 1.0D) {
			graphics.drawString(font, text, x, y, color, false);
			return;
		}
		int cursor = x;
		for (int index = 0; index < text.length(); index++) {
			char original = text.charAt(index);
			char shown = TerminalGlyphSettle.glyphAt(original, seed, rowIndex, index, progress, bucket);
			if (shown != ' ') graphics.drawString(font, String.valueOf(shown), cursor, y, color, false);
			cursor += font.width(String.valueOf(original));
		}
	}
}
