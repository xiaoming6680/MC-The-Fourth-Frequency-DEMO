package com.xm.thefourthfrequency.client_ui;

import java.util.List;

/**
 * How many variable-height rows fit in a fixed band, and how far they can be scrolled.
 *
 * <p>Pure, and separate from the screen, because this is the rule that was wrong: the right column
 * was laid out from fixed offsets that added up to about 330 pixels, which fits the 520-tall panel
 * and does not fit the 336-tall one the same panel becomes at GUI scale 3. Nothing failed - the last
 * groups were simply drawn past the bottom of the frame, where no clipping and no test could see
 * them. Expressed here it is arithmetic a unit test can hold, instead of a sum only a screenshot at
 * the right resolution would have caught.</p>
 *
 * <p>Rows are whole. A row that does not fit is not drawn at all rather than cut, so a button is
 * never half-visible and never clickable outside the panel.</p>
 */
public final class DebugPanelLayout {
	private DebugPanelLayout() { }

	/**
	 * How many rows starting at {@code from} fit whole inside {@code availableHeight}.
	 *
	 * <p>Stops at the first row that does not fit rather than skipping it: the column is a sequence,
	 * and a layout that stepped over a tall row to fit a later short one would reorder the readout
	 * depending on the window size.</p>
	 */
	public static int visibleRowCount(int availableHeight, List<Integer> rowHeights, int from) {
		if (rowHeights == null || availableHeight <= 0) return 0;
		int used = 0;
		int count = 0;
		for (int index = Math.max(0, from); index < rowHeights.size(); index++) {
			int height = rowHeights.get(index);
			if (used + height > availableHeight) break;
			used += height;
			count++;
		}
		return count;
	}

	/**
	 * The furthest row the column can be scrolled to, found by filling it from the bottom.
	 *
	 * <p>Zero when everything already fits, which is also what tells the screen not to reserve a
	 * scrollbar. The last row is always reachable: scrolling that cannot reach the end of the content
	 * is the same bug as not scrolling at all, only harder to notice.</p>
	 */
	public static int maxScrollRow(int availableHeight, List<Integer> rowHeights) {
		if (rowHeights == null || rowHeights.isEmpty()) return 0;
		int used = 0;
		for (int index = rowHeights.size() - 1; index >= 0; index--) {
			used += rowHeights.get(index);
			if (used > availableHeight) return index + 1;
		}
		return 0;
	}
}
