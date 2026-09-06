package com.xm.thefourthfrequency.ending;

/** Non-overlapping columns, measured in GUI pixels after font measurement. */
public final class WorldInterfaceHudLayout {
	public static final int FORM_PIPS_WIDTH = 22;
	public static final int ANCHOR_STRIP_WIDTH = 68;
	private WorldInterfaceHudLayout() {}
	public record Area(int left, int width) { public int right() { return left + width; } }
	public record Row(Area text, Area readout, Area lamps) {}

	public static Row header(int width, int percentWidth) {
		int pipsLeft = width - FORM_PIPS_WIDTH;
		int numberWidth = Math.min(Math.max(0, percentWidth), Math.max(0, (width - 36) / 2));
		int numberLeft = pipsLeft - 7 - numberWidth;
		return new Row(new Area(7, Math.max(0, numberLeft - 15)), new Area(numberLeft, numberWidth),
				new Area(pipsLeft, FORM_PIPS_WIDTH));
	}

	public static Row footer(int width, int labelWidth) {
		int lampsLeft = width - ANCHOR_STRIP_WIDTH;
		int numberWidth = Math.min(Math.max(0, labelWidth), Math.max(0, (lampsLeft - 14) / 2));
		int numberLeft = lampsLeft - 6 - numberWidth;
		return new Row(new Area(0, Math.max(0, numberLeft - 8)), new Area(numberLeft, numberWidth),
				new Area(lampsLeft, ANCHOR_STRIP_WIDTH));
	}
}
