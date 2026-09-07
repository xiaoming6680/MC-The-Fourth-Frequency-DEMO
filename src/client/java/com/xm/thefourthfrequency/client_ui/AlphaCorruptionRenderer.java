package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * The analog-horror layer drawn over the first-entry loading screen.
 *
 * <p>The failure text underneath this is the message; everything here is the medium carrying it.
 * Scanlines, chroma bleed, a tracking band, a running timecode and dead air are what make the
 * sequence read as a recording of a failure rather than as a mod drawing red words - and a
 * recording is far harder to dismiss, because it implies the failure already happened to someone
 * else before it happened to you.</p>
 *
 * <p>Timing lives entirely in {@link AlphaLoadTimeline}, which is plain arithmetic and unit
 * tested. Nothing here decides <em>when</em>; it only decides what a given tick looks like.</p>
 */
public final class AlphaCorruptionRenderer {
	/**
	 * The four analog-signal chains, weakest first, indexed by {@link AlphaLoadTimeline#signalStep}.
	 *
	 * <p>The <em>still</em> family: the same medium as the anomaly burst's - grain, scanlines, radial
	 * chroma, halation, the tube - minus the two terms that move the picture sideways, the row wobble
	 * and the mistracked bar.
	 *
	 * <p>That is not a preference, it is what the two surfaces are for. The burst wants the picture
	 * bent, because for eighteen ticks nothing on it has to be read. This screen is a wall of text
	 * the player is meant to read for half a minute, and text that will not hold still stops being a
	 * fault and becomes a headache. What is left is still one fault seen twice, which is the point of
	 * sharing the shader at all.
	 */
	private static final Identifier[] SIGNAL_CHAINS = {
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_still_1"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_still_2"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_still_3"),
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "signal_still_4")
	};
	private static final int CHROMA_RED = 0xFF2A2A;
	private static final int CHROMA_CYAN = 0x22E0FF;
	private static final int MAX_CHROMA_ALPHA = 150;
	private static final int COLLAPSE_CORE_COLOR = 0xE6E2D8;
	/** What {@link AlphaLoadTimeline#deadAirNoiseAlpha} peaks at, turned back into a chain step. */
	private static final int MAX_DEAD_AIR_ALPHA = 21;
	/** Chroma lags to one side on real tape; a mirrored pair is the giveaway that it is drawn. */
	private static final float CHROMA_TRAIL_RATIO = 1.6F;

	private AlphaCorruptionRenderer() {
	}

	/**
	 * Every layer that belongs on top of a picture that still exists.
	 *
	 * <p>Called after the failure contents so the damage sits over them, never under.</p>
	 */
	public static void drawMediumLayers(GuiGraphics graphics, int screenTicks) {
		// Ordered by where each layer physically lives, outward from the picture: damage on the
		// tape, then the deck's own overlay, then the display drawing all of it.
		//
		// The last of those three is no longer drawn at all. Scanlines, grain, halation, chroma
		// separation and the tube's own falloff are not marks on a picture - they are what the
		// picture is arriving through - and every one of them was being approximated with
		// rectangles because a screen cannot reach GameRenderer's post-effect slot. It can reach
		// ScreenFilterDriver's, which runs over the finished frame, so the display layer is a real
		// filter now and the timecode below is inside it rather than under it.
		// The tracking band went with them: signal_still_* carries a slow roll bar of its own, so the
		// mistracking is in the picture rather than painted across it. drawTrackingBand is kept below
		// and no longer called.
		requestSignalFilter(screenTicks);
	}

	/**
	 * Asks for this tick's medium over the whole finished frame.
	 *
	 * <p>Asked from the render path on purpose: {@link ScreenFilterDriver}'s requests last exactly
	 * one frame, so the treatment cannot survive the screen that wanted it - not through a close, a
	 * disconnect, a resource reload, or an exception on the way out.
	 */
	public static void requestSignalFilter(int screenTicks) {
		int step = AlphaLoadTimeline.signalStep(screenTicks);
		if (step <= 0) return;
		ScreenFilterDriver.request(ScreenFilterDriver.Owner.LOADING,
				SIGNAL_CHAINS[Math.min(step, SIGNAL_CHAINS.length) - 1]);
	}

	/**
	 * A horizontal band of lost tracking, scrolling slowly upward.
	 *
	 * <p>Drawn as displaced streaks rather than as a re-render of the shifted picture: the streaks
	 * carry the read at a fraction of the cost, and at this band height the difference is not
	 * visible.</p>
	 *
	 * <p><b>Retired.</b> The shader's own roll bar carries the mistracking now, so this is not called
	 * from anywhere. Kept rather than deleted: it is the reference for what that term is meant to
	 * look like, and the fallback if a driver ever turns out not to compile the chain.</p>
	 */
	@SuppressWarnings("unused")
	public static void drawTrackingBand(GuiGraphics graphics, int screenTicks) {
		int top = AlphaLoadTimeline.trackingBandTop(screenTicks, graphics.guiHeight());
		if (top == Integer.MIN_VALUE) return;
		int width = graphics.guiWidth();
		int height = AlphaLoadTimeline.trackingBandHeight(screenTicks);
		int shift = AlphaLoadTimeline.trackingBandShift(screenTicks);
		int bottom = Math.min(graphics.guiHeight(), top + height);
		if (bottom <= 0 || top >= graphics.guiHeight() || height <= 0) return;

		AnalogFilter.rollBar(graphics, top, height, 1.0F);
		for (int y = Math.max(0, top); y < bottom; y++) {
			// Seeded by the row's offset *within* the band, so the streak pattern is fixed to the
			// band and travels with it. Seeding by screen row instead made every streak
			// re-randomise each tick, which reads as flicker laid over the picture rather than as
			// one piece of damage passing through it.
			int seed = AlphaLoadTimeline.noise((y - top) * 0x9E3779B9);
			int streakLeft = Math.floorMod(seed, Math.max(1, width)) + shift;
			int streakWidth = 8 + Math.floorMod(seed >>> 11, 46);
			float centred = 1.0F - Math.abs((y - top) * 2.0F / height - 1.0F);
			int streakAlpha = Math.round((22 + Math.floorMod(seed >>> 19, 44)) * centred * centred);
			if (streakAlpha <= 0) continue;
			graphics.fill(Math.max(0, streakLeft), y,
					Math.min(width, streakLeft + streakWidth), y + 1,
					streakAlpha << 24 | 0xC8C4BC);
		}
	}

	/**
	 * Dead air: a collapsing picture, then noise that is never quite black.
	 *
	 * <p>Two seconds of true black reads as a bug. Two seconds of a screen that is still clearly
	 * powered on and receiving nothing does not.</p>
	 */
	public static void drawDeadAir(GuiGraphics graphics, int screenTicks) {
		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		float collapse = AlphaLoadTimeline.blackoutCollapseProgress(screenTicks);
		if (collapse > 0.0F) {
			int centerY = height / 2;
			int lineHeight = Math.max(1, Math.round(height * 0.5F * collapse));
			int inset = Math.round(width * 0.5F * (1.0F - collapse));
			int alpha = Math.round(Math.clamp(collapse, 0.0F, 1.0F) * 235.0F);
			graphics.fill(inset, centerY - lineHeight, width - inset, centerY + lineHeight,
					alpha << 24 | COLLAPSE_CORE_COLOR);
		}
		// The snow is the filter's grain over a frame that has almost nothing left in it, and it is
		// asked for on its own ramp rather than through requestSignalFilter: the scanline envelope
		// that drives every other beat deliberately collapses to nothing across dead air, so
		// deriving from it here would leave the one stretch that is *made of* noise with none.
		//
		// What this replaces was three hundred and twenty one-pixel rectangles, and the comment above
		// them conceded the problem it had already lost to: a full screen of dead air is on the order
		// of a hundred thousand pixels, so three hundred of them is a handful of dots however dense
		// you call it.
		int noiseAlpha = AlphaLoadTimeline.deadAirNoiseAlpha(screenTicks);
		if (noiseAlpha <= 0) return;
		int step = Math.clamp(Math.round(noiseAlpha * (float) SIGNAL_CHAINS.length
				/ MAX_DEAD_AIR_ALPHA), 1, SIGNAL_CHAINS.length);
		ScreenFilterDriver.request(ScreenFilterDriver.Owner.LOADING, SIGNAL_CHAINS[step - 1]);
	}

	/**
	 * The picture coming back, but not cleanly.
	 *
	 * <p>Cutting straight from dead air to a stable Alpha loading screen would hand the player
	 * the reassurance that the worst is over. Instead the picture arrives still rolling and
	 * settles over a second or so, and the scanlines it settles onto never leave - the recovery
	 * is real, but it is a recovery to a worse baseline than the one the session started from.</p>
	 */
	public static void drawRecoveryLock(GuiGraphics graphics, int screenTicks) {
		float strength = AlphaLoadTimeline.recoveryLockStrength(screenTicks);
		if (strength > 0.0F) {
			int width = graphics.guiWidth();
			int height = graphics.guiHeight();
			int rollHeight = Math.max(2, Math.round(26 * strength));
			int rollTop = height - Math.floorMod(screenTicks * 7, height + rollHeight);
			int alpha = Math.round(120 * strength);
			AnalogFilter.rollBar(graphics, Math.max(0, rollTop), rollHeight, strength);
		}
		requestSignalFilter(screenTicks);
	}

	/** Text with the red and cyan ghosts a worn tape leaves either side of every edge. */
	public static void drawChromaString(GuiGraphics graphics, Font font, String text, int x, int y,
			int color, int screenTicks) {
		drawChromaStringAt(graphics, font, text, x, y, color,
				AlphaLoadTimeline.chromaOffset(screenTicks));
	}

	/**
	 * The same ghosts, driven by a caller that owns its own envelope.
	 *
	 * <p>Split out for the terminal's sky monitor, whose separation follows an anomaly's stage
	 * rather than the loading screen's timeline. Sharing the drawing rather than the schedule
	 * keeps the asymmetry below identical everywhere chroma bleed appears in this mod - two
	 * hand-written copies would drift, and mismatched bleed reads as two different faults.</p>
	 */
	public static void drawChromaStringAt(GuiGraphics graphics, Font font, String text, int x, int y,
			int color, float rawOffset) {
		int offset = Math.round(rawOffset);
		if (offset > 0) {
			int ghostAlpha = chromaGhostAlpha(rawOffset);
			// Asymmetric: the red edge sits close, the cyan trails further out and fainter.
			int trail = Math.max(offset + 1, Math.round(rawOffset * CHROMA_TRAIL_RATIO));
			graphics.drawString(font, text, x - offset, y, ghostAlpha << 24 | CHROMA_RED, false);
			graphics.drawString(font, text, x + trail, y,
					ghostAlpha * 3 / 4 << 24 | CHROMA_CYAN, false);
		}
		graphics.drawString(font, text, x, y, color, false);
	}

	public static void drawChromaCenteredString(GuiGraphics graphics, Font font, String text,
			int centerX, int y, int color, int screenTicks) {
		drawChromaString(graphics, font, text, centerX - font.width(text) / 2, y, color, screenTicks);
	}

	private static int chromaGhostAlpha(float rawOffset) {
		float strength = Math.clamp(rawOffset / 3.0F, 0.0F, 1.0F);
		return Math.round(strength * MAX_CHROMA_ALPHA);
	}
}
