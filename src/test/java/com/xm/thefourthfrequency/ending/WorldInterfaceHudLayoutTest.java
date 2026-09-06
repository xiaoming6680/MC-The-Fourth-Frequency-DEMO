package com.xm.thefourthfrequency.ending;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldInterfaceHudLayoutTest {
	@Test void longTranslationsCannotOverlapEitherReadoutOrEscapeThePanel() {
		for (int width : new int[]{80, 160, 240, 288, 290}) {
			for (int textWidth : new int[]{0, 24, 72, 140, 4096}) {
				for (var row : new WorldInterfaceHudLayout.Row[]{WorldInterfaceHudLayout.header(width, textWidth),
						WorldInterfaceHudLayout.footer(width, textWidth)}) {
					for (var area : new WorldInterfaceHudLayout.Area[]{row.text(), row.readout(), row.lamps()}) {
						assertTrue(area.left() >= 0 && area.width() >= 0 && area.right() <= width);
					}
					assertTrue(row.text().right() <= row.readout().left());
					assertTrue(row.readout().right() < row.lamps().left());
				}
			}
		}
	}
}
