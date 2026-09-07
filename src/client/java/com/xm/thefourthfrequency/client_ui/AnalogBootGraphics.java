package com.xm.thefourthfrequency.client_ui;

import net.minecraft.client.gui.GuiGraphics;

/** Quiet period instruments: a television test card, bank lamps, and raster recovery. */
public final class AnalogBootGraphics {
	private AnalogBootGraphics() {}

	public static void drawCrtCalibration(GuiGraphics g, int x, int y, int w, int h,
			double seconds, double progress, float opacity) {
		if (w < 8 || h < 8 || opacity <= 0) return;
		g.enableScissor(x, y, x + w, y + h);
		int dim = tint(.23F * opacity), ink = tint(.8F * opacity);
		int cx = x + w / 2, cy = y + h / 2, radius = Math.min(w / 3, h * 2 / 5);
		for (int i = 0; i <= 12; i++) g.fill(x + w * i / 12, y, x + w * i / 12 + 1, y + h, dim);
		for (int i = 0; i <= 8; i++) g.fill(x, y + h * i / 8, x + w, y + h * i / 8 + 1, dim);
		for (int i = 0; i < 96; i++) {
			double a = Math.PI * i / 48, b = Math.PI * (i + 1) / 48;
			line(g, cx + (float)Math.cos(a) * radius, cy + (float)Math.sin(a) * radius,
					cx + (float)Math.cos(b) * radius, cy + (float)Math.sin(b) * radius, ink);
		}
		for (int half : new int[]{-1, 1}) for (int i = 0; i < 12; i++) {
			int yy = cy + half * (9 + i * Math.max(2, radius / 16));
			int span = Math.max(2, radius - i * 3);
			g.fill(cx - span, yy, cx + span, yy + 1, ink);
		}
		for (int i = 0; i < 6; i++) g.fill(cx - radius + radius * 2 * i / 6, cy - 5,
				cx - radius + radius * 2 * (i + 1) / 6, cy + 5, tint(opacity * (i + 1) / 6));
		int beam = y + (int)(Math.clamp(progress, 0, 1) * (h - 1));
		for (int i = 0; i < 6; i++) g.fill(x, beam - i, x + w, beam - i + 1, tint(opacity * (6 - i) / 24));
		for (int side : new int[]{-1, 1}) {
			int xx = cx + side * w * 2 / 5;
			line(g, xx - 5, cy, xx + 5, cy, ink);
			line(g, xx, cy - 6, xx, cy + 6, ink);
		}
		g.disableScissor();
	}

	public static void drawMemoryCheck(GuiGraphics g, int x, int y, int w, int h,
			double seconds, double progress, float opacity) {
		if (w < 8 || h < 8 || opacity <= 0) return;
		g.enableScissor(x, y, x + w, y + h);
		int dx = Math.max(2, w / 16), dy = Math.max(3, h * 2 / 3 / 6);
		for (int row = 0; row < 6; row++) for (int col = 0; col < 16; col++) {
			float level = row * 16 + col < progress * 96 ? .8F : .12F;
			g.fill(x + col * dx, y + row * dy, x + col * dx + Math.max(1, dx - 2),
					y + row * dy + Math.max(1, dy - 3), tint(opacity * level));
		}
		float cy = y + h * .84F, sweep = (float)(seconds * .65 % 1);
		line(g, x, cy, x + w, cy, tint(opacity * .2F));
		for (int i = 1; i <= 80; i++) {
			float a = (i - 1) / 80F, b = i / 80F, trail = (sweep - b + 1) % 1;
			line(g, x + a * w, cy + wave(a, seconds) * h * .10F,
					x + b * w, cy + wave(b, seconds) * h * .10F,
					tint(opacity * (.12F + .88F * (float)Math.exp(-trail * 5))));
		}
		g.disableScissor();
	}

	public static void drawRetune(GuiGraphics g, int x, int y, int w, int h, double progress, float opacity) {
		if (w < 8 || h < 8 || opacity <= 0) return;
		g.enableScissor(x, y, x + w, y + h);
		int beam = y + (int)(Math.clamp(progress, 0, 1) * (h - 1));
		for (int row = y; row < y + h; row += 3) {
			float strength = (float)Math.exp(-Math.abs(row - beam) / Math.max(1F, h * .1F));
			g.fill(x, row, x + w, row + 1, tint(opacity * (.08F + strength * .48F)));
		}
		g.fill(x + 8, beam, x + w - 8, beam + 1, tint(opacity * .7F));
		g.disableScissor();
	}

	private static float wave(float p, double time) { return (float)(Math.sin(p * 25 + time * .4) * .7 + Math.sin(p * 50 + time * .8) * .18); }
	private static int tint(float opacity) { return Math.round(Math.clamp(opacity, 0, 1) * 255) << 24 | 0xC2CCAA; }
	private static void line(GuiGraphics g, float x1, float y1, float x2, float y2, int color) {
		float length = (float)Math.hypot(x2 - x1, y2 - y1);
		if (length < .01F) return;
		g.pose().pushMatrix();
		g.pose().translate(x1, y1);
		g.pose().rotate((float)Math.atan2(y2 - y1, x2 - x1));
		g.fill(0, 0, Math.max(1, Math.round(length)), 1, color);
		g.pose().popMatrix();
	}
}
