package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.config.ModConfig;

/**
 * What the developer HUD is currently showing, and which groups it is allowed to show.
 *
 * <p>Visibility is per session and lives only here: it is toggled with a key while playing, and a
 * developer who hid the HUD to take a screenshot does not want that decision to outlive the game.
 * The group mask is the opposite - it is a layout preference someone sets once - so it goes through
 * {@link ConfigManager} and is read back on first use rather than every frame.</p>
 */
public final class DebugHudState {
	private static boolean visible = true;
	private static int groupMask = ModConfig.ClientState.ALL_DEBUG_HUD_GROUPS;
	private static boolean loaded;

	private DebugHudState() { }

	public static boolean visible() {
		return visible;
	}

	public static void toggleVisible() {
		visible = !visible;
	}

	public static int groupMask() {
		if (!loaded) {
			loaded = true;
			groupMask = ConfigManager.loadClientState().debugHudGroupMask();
		}
		return groupMask;
	}

	public static boolean enabled(Group group) {
		return group.enabledIn(groupMask());
	}

	/**
	 * Flips one group and writes the mask back.
	 *
	 * <p>Written on the click rather than on world exit: this is a four-bit field behind a button
	 * nobody presses in a loop, and the alternative is a preference that a crash quietly discards.</p>
	 */
	public static void toggle(Group group) {
		int updated = groupMask() ^ group.bit();
		groupMask = updated;
		ConfigManager.updateClientState(state -> state.withDebugHudGroups(updated));
	}

	/** Reset hook for tests, which must not inherit a mask from whatever ran before them. */
	public static void resetForTesting() {
		visible = true;
		loaded = true;
		groupMask = ModConfig.ClientState.ALL_DEBUG_HUD_GROUPS;
	}

	public enum Group {
		RHYTHM("异象节奏"),
		NUMBERS("异象数值"),
		STORY("主线与文件"),
		PURSUIT("追逐与终局");

		private final String label;

		Group(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public int bit() {
			return 1 << ordinal();
		}

		public boolean enabledIn(int mask) {
			return (mask & bit()) != 0;
		}
	}
}
