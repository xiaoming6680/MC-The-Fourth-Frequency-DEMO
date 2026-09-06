package com.xm.thefourthfrequency.client_ui;

/** Shared pixel-terminal palette. Values stay centralized so terminal-adjacent screens cannot visually drift. */
final class TerminalVisualTheme {
	static final int GREEN = 0xFF91C58D;
	static final int CYAN = 0xFF7FD2D8;
	static final int DIM = 0xFF58725B;
	static final int AMBER = 0xFFD3B56F;
	static final int HOT = 0xFFE06565;
	static final int CLAIMABLE = 0xFF66FF66;
	static final int MUTED = 0xFF405244;
	static final int MUTED_DARK = 0xFF243229;
	static final int READING_TITLE = 0xFFE8C978;
	static final int READING_TEXT = 0xFFC7DBC3;
	static final int READING_META = 0xFF8FA99B;
	static final int GLASS = 0xD907110B;
	static final int SELECTED = 0xFF243A29;
	static final int ALERT_BACKGROUND = 0xFF481B20;
	static final int DARK_BORDER = 0xFF304535;
	static final int LCD_BACKGROUND = 0xFF0A160C;
	static final int LCD_BORDER = 0xFF6F7543;

	// Surfaces, previously written as hex literals at each call site in TerminalScreen. Naming them
	// is what makes it visible that three different instruments were already sharing one brass and
	// one recess colour - repainting the terminal meant finding every copy of the number.
	static final int GLASS_BACKDROP = 0x6507110B;
	static final int CARD_BODY = 0x650C1710;
	static final int CARD_BODY_FOCUSED = 0xAA172A1D;
	static final int REWARD_SLOT = 0xA006100A;
	static final int PROGRESS_TRACK = 0xFF08100B;
	/** Worn brass. Shared by the scope bezel, the compass outer ring and the tuning slider frame. */
	static final int BRASS_BEZEL = 0xFF716B43;
	/** The recess an instrument sits in. Shared by the compass ring, its hub and the slider track. */
	static final int INSTRUMENT_WELL = 0xFF17180F;
	static final int SCOPE_BACKGROUND = 0xFF050B08;
	static final int SCOPE_GRID = 0x182D6A35;
	static final int SCOPE_HIGHLIGHT = 0x226FFFFF;
	static final int COMPASS_FACE = 0xFF080D09;

	// The unread lamp. Its own three colours rather than reuse of AMBER, because this is a physical
	// bulb behind a lens and the rest of the amber in this palette is text. It stays these colours
	// at every visual stage: the lamp reports whether something is waiting, and going cyan or red
	// with the stage would make it look like it had started reporting something else.
	/** Unlit: a dead bulb in its housing. Dark enough to read as glass rather than as a dim light. */
	/**
	 * The unread lamp, unlit, lit, and its filament.
	 *
	 * <p>Named for the job rather than the colour. These were {@code LAMP_AMBER} until the indicator
	 * went red, and a constant called amber holding a red is the kind of thing that survives for
	 * years because renaming it is never the task anybody is on.
	 *
	 * <p>{@code LAMP_DARK} is tinted toward the lit colour rather than being neutral black: the
	 * filament lerps out of it, and an unlit lens that is the wrong temperature reads as a lamp that
	 * has failed instead of one that is off.
	 *
	 * <p>The item textures bake these same three values - see {@code tools/generate_terminal_3d_assets.py}
	 * - so changing one without regenerating the other leaves the terminal in the player's hand
	 * disagreeing with the terminal on their screen.
	 *
	 * <p>{@code LAMP_LIT} is the flat inventory icon's lamp pixel exactly, from
	 * {@code tools/pixelize_terminal_icons.py}. That icon has always drawn this indicator red while
	 * the panel and the 3D shell drew it amber - three surfaces for one lamp, and one of them
	 * disagreeing. Matching the number rather than picking a new red is what makes them one lamp
	 * again; the core is paler because a filament at panel scale can be, and a three-pixel icon
	 * cannot.
	 */
	static final int LAMP_DARK = 0xFF1E1210;
	static final int LAMP_LIT = 0xFFFF3722;
	static final int LAMP_LIT_CORE = 0xFFFF9A88;

	/**
	 * Base colour for every neutral darkening pass - scanlines, vignette, dimmed regions.
	 *
	 * <p>Carries no alpha of its own; each caller supplies one through {@link #withAlpha}, so a
	 * layer's opacity lives next to the loop that draws it rather than being split between a constant
	 * here and a magic number there.</p>
	 *
	 * <p>It is also deliberately colourless. An earlier CRT layer was a flat green wash, which
	 * tinted every glyph seen through it and cost more contrast than it bought atmosphere.</p>
	 */
	static final int SHADOW = 0x00000000;

	// Interaction states.
	static final int HOVER = 0xFF2C4433;
	static final int PRESSED = 0xFF3A5C41;

	// First-boot walkthrough.
	/**
	 * The wash the walkthrough lays over everything except the tab it is pointing at.
	 *
	 * <p>The only fill the walkthrough owns. It dims what is already there rather than replacing it,
	 * which is why it is the one that survived: an opaque plate is a sticker, a dim is the device
	 * turning its own attention elsewhere.</p>
	 */
	static final int ONBOARD_DIM = 0xA0050A07;
	static final int ONBOARD_ACCENT = CLAIMABLE;

	private TerminalVisualTheme() {
	}

	static int withAlpha(int color, int alpha) {
		return Math.max(0, Math.min(255, alpha)) << 24 | color & 0x00FFFFFF;
	}
}
