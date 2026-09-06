package com.xm.thefourthfrequency.terminal;

public enum TerminalTool {
	HOME(0, "home"),
	MINERALS(1, "minerals"),
	PORTAL(2, "portal"),
	WEATHER(3, "weather"),
	NAVIGATION(4, "navigation"),
	STRONGHOLD(5, "stronghold");

	private final int slot;
	private final String id;

	TerminalTool(int slot, String id) {
		this.slot = slot;
		this.id = id;
	}

	public int slot() {
		return slot;
	}

	public String id() {
		return id;
	}

	public static TerminalTool fromSlot(int slot) {
		for (TerminalTool tool : values()) if (tool.slot == slot) return tool;
		return null;
	}

	/**
	 * Whether this tool guides off the single shared {@code NavigationState} in the terminal record.
	 *
	 * <p>Two of the six do, and they do not know about each other: the mineral survey writes it and
	 * the structure navigation writes it, over the same {@code TARGET_KIND}, {@code TARGET_POSITION}
	 * and {@code TARGET_LOCATED} fields. The passive mineral survey used to be gated on "the mineral
	 * tool is not guiding", which is the right idea applied to half the problem - a player walking to
	 * a village with the navigation tool would pass a coal seam, the survey would fire (it is allowed
	 * to, because the terminal is shut, which is exactly when someone is walking somewhere), and it
	 * would overwrite the village with the coal. {@code TerminalStructureTarget.fromId("coal")}
	 * resolves to {@code NONE}, so the navigation simply stopped, mid-route, with nothing but a
	 * "mineral nearby" notice to explain it.
	 *
	 * <p>Home, portal and stronghold keep their targets in their own fields and are unaffected;
	 * they are listed here as false rather than omitted so that the answer is stated for all six.
	 */
	public boolean usesSharedNavigationState() {
		return this == MINERALS || this == NAVIGATION;
	}
}
