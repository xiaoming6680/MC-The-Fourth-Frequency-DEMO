package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.client_render.WorldInterfacePalette;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.Level;

import java.util.Locale;
import java.util.UUID;

/**
 * Virtual-health, collapse-clock and anchor readout for the encounter.
 *
 * <p>Drawn as an instrument panel in the terminal's own language - corner brackets rather than a
 * boxed outline, a segmented gauge rather than a solid bar, and a CRT scanline wash - so the fight's
 * only persistent piece of UI reads as part of the same machine the rest of the mod presents.</p>
 */
public final class WorldInterfaceHud {
	private static final int ANCHOR_COUNT = Integer.bitCount(WorldInterfaceProtocol.ANCHOR_MASK);

	/** Per-tick convergence of the drawn gauge onto the authoritative ratio. */
	private static final float HEALTH_EASE_RATE = 0.42F;
	/** Slower, so the lost segment stays legible after the gauge itself has settled. */
	private static final float GHOST_EASE_RATE = 0.11F;
	private static final float GHOST_HOLD_TICKS = 9.0F;
	private static final float DAMAGE_FLASH_TICKS = 7.0F;
	private static final float BAND_FLASH_TICKS = 26.0F;
	/** How long a felled anchor's light takes to cross the gauge. */
	private static final float ANCHOR_SWEEP_TICKS = 60.0F;
	/** Half-width of the travelling band, as a fraction of the gauge. */
	private static final float ANCHOR_SWEEP_REACH = 0.18F;
	/** Pixels per drawn slice of the band. Fine enough to read as a gradient, coarse enough to be cheap. */
	private static final int ANCHOR_SWEEP_SLICE = 4;

	private static final int PANEL_PAD_X = 8;
	private static final int PANEL_PAD_TOP = 4;
	private static final int PANEL_PAD_BOTTOM = 3;
	private static final int BRACKET_LENGTH = 9;
	private static final int GAUGE_HEIGHT = 5;
	private static final int RAIL_HEIGHT = 2;
	private static final int ANCHOR_CELL = 5;
	private static final int ANCHOR_GAP = 2;
	/** Target width of one gauge cell before the count is fitted to the panel. */
	private static final int GAUGE_CELL_TARGET = 7;
	private static final int MIN_GAUGE_SEGMENTS = 18;
	private static final int MAX_GAUGE_SEGMENTS = 44;

	private static final int PANEL_BACKDROP = 0x9C070410;
	private static final int PANEL_INNER = 0x64100A1E;
	private static final int SCANLINE = 0x1A000000;
	private static final int GAUGE_TRACK = 0xFF1B1024;
	private static final int GAUGE_CELL_SHADOW = 0xFF2A1935;
	private static final int GHOST = 0xC8F2DEFF;
	private static final int RAIL_TRACK = 0xFF1A1219;
	private static final int RAIL_WARM = 0xFFCE6B36;
	private static final int RAIL_HOT = 0xFFE02C38;
	private static final int LABEL = 0xFFE8D7F2;
	private static final int LABEL_DIM = 0xFF8E7C9C;
	private static final int READOUT = 0xFFF2E8FA;
	private static final int ANCHOR_HUSK = 0xFF2A2130;
	private static final int FAILURE_ACCENT = 0xFFD33B54;
	/** Gold, because that is the colour the arena's own gateways take when the interface gives ground. */
	private static final int ANCHOR_SWEEP_LIGHT = 0x00FFE0A0;

	private static boolean initialized;
	private static UUID trackedEncounterId;
	private static int trackedBand = -1;
	private static int trackedAnchorMask;
	private static float displayedRatio = Float.NaN;
	private static float lastTargetRatio = Float.NaN;
	private static float ghostRatio = Float.NaN;
	private static float ghostHold;
	private static float damageFlash;
	private static float bandFlash;
	private static float anchorSweep;
	private static double lastRenderTime = Double.NaN;

	private WorldInterfaceHud() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		HudRenderCallback.EVENT.register(WorldInterfaceHud::render);
	}

	private static void render(GuiGraphics graphics, DeltaTracker tickCounter) {
		Minecraft client = Minecraft.getInstance();
		WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
		// The panel is a readout of an arena, so it is drawn only to someone standing in it.
		//
		// Without the dimension test this followed players home. The server sends encounter snapshots
		// to every frozen roster member wherever they are - the respawn and poem ledgers need them
		// off-island - and PORTAL_OPEN is still combatVisible(), so the stage a table spends walking
		// back out through the exit is exactly the stage this drew in. Worse in multiplayer:
		// completeIfAllPoemsAcknowledged only reaches COMPLETE once *every* member has acked their
		// poem and had their respawn restored, so the first person home kept a frozen gauge and a
		// paused collapse clock nailed to the top of their screen until the last teammate finished -
		// indefinitely, if one of them was AFK or never read it.
		if (client.player == null || client.level == null || client.options.hideGui
				|| client.level.dimension() != Level.END || !projection.combatVisible()) {
			resetBarState();
			return;
		}
		WorldInterfaceSnapshotS2C encounter = projection.encounter();
		if (encounter == null) {
			resetBarState();
			return;
		}
		advanceBar(client, tickCounter, projection, encounter);

		Font font = client.font;
		int screenWidth = graphics.guiWidth();
		int barWidth = Math.max(80, Math.min(screenWidth - 32, 290));
		int left = (screenWidth - barWidth) / 2;
		int top = 12;

		int headerY = top;
		int gaugeY = headerY + font.lineHeight + 3;
		int railY = gaugeY + GAUGE_HEIGHT + 3;
		int footerY = railY + RAIL_HEIGHT + 3;
		int panelBottom = footerY + font.lineHeight + PANEL_PAD_BOTTOM;

		int band = WorldInterfacePalette.band(encounter.stage());
		boolean failing = encounter.outcome() == WorldInterfaceProtocol.Outcome.FAILURE;
		int accent = failing ? FAILURE_ACCENT : accentColor(band);
		int frame = accent;
		if (damageFlash > 0.0F) {
			frame = ARGB.srgbLerp(Math.clamp(damageFlash / DAMAGE_FLASH_TICKS, 0.0F, 1.0F) * 0.8F,
					frame, 0xFFFFFFFF);
		}

		drawPanel(graphics, left - PANEL_PAD_X, top - PANEL_PAD_TOP,
				left + barWidth + PANEL_PAD_X, panelBottom, frame);
		drawHeader(graphics, font, encounter, left, barWidth, headerY, accent, failing);
		drawGauge(graphics, left, gaugeY, barWidth, band, accent);
		double collapse = projection.collapseProgress(client.level.getGameTime());
		drawCollapseRail(graphics, left, railY, barWidth, collapse);
		drawFooter(graphics, font, encounter, left, barWidth, footerY, collapse, accent);
	}

	// ---------------------------------------------------------------- panel chrome

	/**
	 * Corner brackets rather than a closed outline, matching the terminal's plates. A boxed rectangle
	 * is what made the old readout look like a debug overlay rather than an instrument.
	 */
	private static void drawPanel(GuiGraphics graphics, int left, int top, int right, int bottom, int frame) {
		graphics.fill(left, top, right, bottom, PANEL_BACKDROP);
		graphics.fill(left + 1, top + 1, right - 1, top + 2, PANEL_INNER);
		graphics.fill(left + 1, bottom - 2, right - 1, bottom - 1, PANEL_INNER);
		// The CRT wash. Two-pixel pitch reads as scanlines without eating the text underneath.
		for (int y = top + 1; y < bottom - 1; y += 2) {
			graphics.fill(left + 1, y, right - 1, y + 1, SCANLINE);
		}
		int corner = BRACKET_LENGTH;
		int dim = ARGB.srgbLerp(0.55F, frame, PANEL_BACKDROP);
		graphics.fill(left, top, left + corner, top + 1, frame);
		graphics.fill(left, top, left + 1, top + corner, frame);
		graphics.fill(right - corner, top, right, top + 1, frame);
		graphics.fill(right - 1, top, right, top + corner, frame);
		graphics.fill(left, bottom - 1, left + corner, bottom, frame);
		graphics.fill(left, bottom - corner, left + 1, bottom, frame);
		graphics.fill(right - corner, bottom - 1, right, bottom, frame);
		graphics.fill(right - 1, bottom - corner, right, bottom, frame);
		// Faint rails between the brackets keep the panel bounded without closing the box.
		graphics.fill(left + corner + 2, top, right - corner - 2, top + 1, dim);
		graphics.fill(left + corner + 2, bottom - 1, right - corner - 2, bottom, dim);
	}

	private static void drawHeader(GuiGraphics graphics, Font font, WorldInterfaceSnapshotS2C encounter,
			int left, int barWidth, int y, int accent, boolean failing) {
		// Leading caret: a three-step wedge, drawn rather than typed so it never depends on a glyph.
		int caretX = left;
		for (int step = 0; step < 3; step++) {
			graphics.fill(caretX + step, y + 2 + step, caretX + step + 1, y + font.lineHeight - 2 - step, accent);
		}
		String title = stageLabel(encounter).getString();
		String percent = Math.round(Math.clamp(displayedRatio, 0F, 1F) * 100) + "%";
		var row = com.xm.thefourthfrequency.ending.WorldInterfaceHudLayout.header(barWidth, font.width(percent));
		graphics.drawString(font, fit(font, title, row.text().width()), left + row.text().left(), y,
				failing ? FAILURE_ACCENT : LABEL, false);
		graphics.drawString(font, fit(font, percent, row.readout().width()), left + row.readout().left(), y, accent, false);
		drawFormPips(graphics, encounter, left + barWidth, y + 2);
	}

	/** Three pips for the three bodies; the live one is lit, the passed ones stay dimly filled. */
	private static void drawFormPips(GuiGraphics graphics, WorldInterfaceSnapshotS2C encounter,
			int rightEdge, int y) {
		int active = Math.clamp(encounter.form().wireId() - 1, 0, 2);
		int pip = 4;
		int gap = 3;
		int startX = rightEdge - (pip * 3 + gap * 2);
		for (int index = 0; index < 3; index++) {
			int x = startX + index * (pip + gap);
			int color = index < active ? LABEL_DIM : index == active ? READOUT : ANCHOR_HUSK;
			graphics.fill(x, y, x + pip, y + pip, color);
		}
	}

	// ---------------------------------------------------------------- gauge

	/**
	 * Segmented gauge. Discrete cells carry the same number as a solid bar but read as a calibrated
	 * instrument, and the partially lit cell at the head gives the value a visible resolution.
	 */
	private static void drawGauge(GuiGraphics graphics, int left, int y, int barWidth, int band, int accent) {
		graphics.fill(left, y, left + barWidth, y + GAUGE_HEIGHT, GAUGE_TRACK);
		int segments = Math.clamp(barWidth / GAUGE_CELL_TARGET, MIN_GAUGE_SEGMENTS, MAX_GAUGE_SEGMENTS);
		float ratio = Math.clamp(displayedRatio, 0.0F, 1.0F);
		float ghost = Math.clamp(ghostRatio, 0.0F, 1.0F);
		int lit = ARGB.srgbLerp(bandFlashAmount() * 0.7F, accent, 0xFFFFFFFF);
		int litLow = ARGB.srgbLerp(0.42F, lit, GAUGE_TRACK);

		for (int index = 0; index < segments; index++) {
			int cellLeft = left + Math.round(barWidth * index / (float) segments);
			int cellRight = left + Math.round(barWidth * (index + 1) / (float) segments) - 1;
			if (cellRight <= cellLeft) continue;
			float cellStart = index / (float) segments;
			float cellEnd = (index + 1) / (float) segments;
			float fill = Math.clamp((ratio - cellStart) / (cellEnd - cellStart), 0.0F, 1.0F);
			float ghostFill = Math.clamp((ghost - cellStart) / (cellEnd - cellStart), 0.0F, 1.0F);
			graphics.fill(cellLeft, y + 1, cellRight, y + GAUGE_HEIGHT - 1, GAUGE_CELL_SHADOW);
			// The residue of the last hit sits under the live cells, so a blow that removes a single
			// percent of a very long gauge is still visible as it decays.
			if (ghostFill > fill) {
				graphics.fill(cellLeft, y + 1, cellLeft + Math.max(1,
						Math.round((cellRight - cellLeft) * ghostFill)), y + GAUGE_HEIGHT - 1, GHOST);
			}
			if (fill <= 0.0F) continue;
			int filledRight = fill >= 1.0F ? cellRight
					: cellLeft + Math.max(1, Math.round((cellRight - cellLeft) * fill));
			graphics.fill(cellLeft, y + 1, filledRight, y + GAUGE_HEIGHT - 1, fill >= 1.0F ? lit : litLow);
			// A one-pixel highlight along the top of each lit cell suggests a curved lens.
			graphics.fill(cellLeft, y + 1, filledRight, y + 2, ARGB.srgbLerp(0.45F, lit, 0xFFFFFFFF));
		}
		drawAnchorSweep(graphics, left, y, barWidth);
		drawPhaseThresholds(graphics, left, y, barWidth, band);
	}

	/**
	 * A band of gold light crossing the gauge, played once for every anchor that falls.
	 *
	 * <p>Cutting an anchor lowers how hard the interface resists damage, which is the whole point of
	 * leaving the fight to go and cut one - and until now it was invisible: the number that changed
	 * lives on the server, and the anchor lamps in the footer only report how many are left, not what
	 * losing one bought. The gauge is where the player is already looking when a hit lands, so that is
	 * where the answer belongs. It reads as light passing over the instrument rather than as a value,
	 * because the exact multiplier is not the point; "that hurt it more than the last one" is.</p>
	 */
	private static void drawAnchorSweep(GuiGraphics graphics, int left, int y, int barWidth) {
		if (anchorSweep <= 0.0F) return;
		// The band enters from off the left edge and leaves past the right one, so the light crosses
		// the gauge instead of appearing and vanishing inside it.
		float travel = 1.0F - anchorSweep / ANCHOR_SWEEP_TICKS;
		float head = -ANCHOR_SWEEP_REACH + travel * (1.0F + 2.0F * ANCHOR_SWEEP_REACH);
		for (int offset = 0; offset < barWidth; offset += ANCHOR_SWEEP_SLICE) {
			int sliceWidth = Math.min(ANCHOR_SWEEP_SLICE, barWidth - offset);
			float position = (offset + sliceWidth * 0.5F) / barWidth;
			float reach = 1.0F - Math.abs(position - head) / ANCHOR_SWEEP_REACH;
			if (reach <= 0.0F) continue;
			int alpha = Math.round(200.0F * reach * reach);
			if (alpha <= 0) continue;
			graphics.fill(left + offset, y + 1, left + offset + sliceWidth, y + GAUGE_HEIGHT - 1,
					alpha << 24 | ANCHOR_SWEEP_LIGHT);
		}
	}

	/**
	 * The two health ratios where the interface changes body. Marking them turns the gauge into
	 * information about what is coming rather than just how much is left.
	 */
	private static void drawPhaseThresholds(GuiGraphics graphics, int left, int y, int barWidth, int band) {
		for (int index = 0; index < 2; index++) {
			float threshold = index == 0 ? 0.70F : 0.35F;
			if (band > index) continue;
			int x = left + Math.round(barWidth * threshold);
			graphics.fill(x, y - 2, x + 1, y, LABEL_DIM);
			graphics.fill(x, y + GAUGE_HEIGHT, x + 1, y + GAUGE_HEIGHT + 2, LABEL_DIM);
		}
	}

	// ---------------------------------------------------------------- readouts

	private static void drawCollapseRail(GuiGraphics graphics, int left, int y, int barWidth, double collapse) {
		graphics.fill(left, y, left + barWidth, y + RAIL_HEIGHT, RAIL_TRACK);
		int filled = (int) Math.round(barWidth * Math.clamp(collapse, 0.0D, 1.0D));
		graphics.fill(left, y, left + filled, y + RAIL_HEIGHT, collapse >= 0.85D ? RAIL_HOT : RAIL_WARM);
		// Quarter ticks give the rail a scale, so a glance reads "past halfway" without the clock.
		for (int index = 1; index < 4; index++) {
			int x = left + barWidth * index / 4;
			graphics.fill(x, y - 1, x + 1, y + RAIL_HEIGHT + 1, PANEL_BACKDROP);
		}
	}

	private static void drawFooter(GuiGraphics graphics, Font font, WorldInterfaceSnapshotS2C encounter,
			int left, int barWidth, int y, double collapse, int accent) {
		long remainingTicks = Math.max(0L, WorldInterfaceProtocol.COLLAPSE_DURATION_TICKS
				- Math.round(collapse * WorldInterfaceProtocol.COLLAPSE_DURATION_TICKS));
		String clock = String.format(Locale.ROOT, "%d:%02d", remainingTicks / 1_200L,
				(remainingTicks / 20L) % 60L);
		// A won encounter is no longer counting down to anything, so it does not get a clock - and it
		// certainly does not get "[paused]", which describes a deadline that is still coming. The rail
		// beside this is running backwards off the same repair fraction the island is healing on, so
		// the line names what the bar is now showing.
		boolean repairing = encounter.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS;
		Component collapseLabel = repairing
				? Component.translatable("hud.thefourthfrequency.world_interface.collapse_repairing")
				: Component.translatable(encounter.timerPaused()
						? "hud.thefourthfrequency.world_interface.collapse_paused"
						: "hud.thefourthfrequency.world_interface.collapse", clock);
		String footer = collapseLabel.getString();
		int alive = Integer.bitCount(encounter.anchorAliveMask() & WorldInterfaceProtocol.ANCHOR_MASK);
		String anchorLabel = (anchorSweep > 0.0F
				? Component.translatable("hud.thefourthfrequency.world_interface.anchor_shift")
				: Component.translatable("hud.thefourthfrequency.world_interface.anchors", alive, ANCHOR_COUNT)).getString();
		var row = com.xm.thefourthfrequency.ending.WorldInterfaceHudLayout.footer(barWidth, font.width(anchorLabel));
		int footerRoom = row.text().width();
		if (font.width(footer) > footerRoom)
			footer = repairing ? footer : clock;
		graphics.drawString(font, fit(font, footer, footerRoom), left, y,
				repairing ? 0xFF9BE6B4 : collapse >= 0.85D ? 0xFFFF8C91 : 0xFFD5A991, false);
		graphics.drawString(font, fit(font, anchorLabel, row.readout().width()), left + row.readout().left(), y,
				anchorSweep > 0.0F ? 0xFFFFE0A0 : LABEL_DIM, false);
		drawAnchors(graphics, font, encounter, left, barWidth, y, accent);
	}

	/** Even the ellipsis must fit; a zero-width column draws nothing. */
	private static String fit(Font font, String text, int width) {
		if (width <= 0) return "";
		if (font.width(text) <= width) return text;
		if (font.width("…") > width) return "";
		return font.plainSubstrByWidth(text, width - font.width("…")) + "…";
	}

	/** Indicator lamps: a seated husk that keeps its slot after the anchor is gone. */
	private static void drawAnchors(GuiGraphics graphics, Font font, WorldInterfaceSnapshotS2C encounter,
			int left, int barWidth, int y, int accent) {
		int stripWidth = ANCHOR_COUNT * ANCHOR_CELL + (ANCHOR_COUNT - 1) * ANCHOR_GAP;
		int stripLeft = left + barWidth - stripWidth;
		int lampTop = y + 1;
		for (int index = 0; index < ANCHOR_COUNT; index++) {
			int x = stripLeft + index * (ANCHOR_CELL + ANCHOR_GAP);
			boolean present = (encounter.anchorAliveMask() & (1 << index)) != 0;
			graphics.fill(x, lampTop, x + ANCHOR_CELL, lampTop + ANCHOR_CELL, ANCHOR_HUSK);
			if (!present) continue;
			graphics.fill(x + 1, lampTop + 1, x + ANCHOR_CELL - 1, lampTop + ANCHOR_CELL - 1, accent);
			graphics.fill(x + 2, lampTop + 2, x + 3, lampTop + 3,
					ARGB.srgbLerp(0.55F, accent, 0xFFFFFFFF));
		}
	}

	// ---------------------------------------------------------------- animation state

	/**
	 * Steps the drawn gauge toward the authoritative ratio on a wall-clock-independent curve, so the
	 * readout behaves the same at 30 and 240 frames per second and does not jump between snapshots.
	 */
	private static void advanceBar(Minecraft client, DeltaTracker tickCounter,
			WorldInterfaceClientState.Projection projection, WorldInterfaceSnapshotS2C encounter) {
		float target = projection.healthRatio();
		int band = WorldInterfacePalette.band(encounter.stage());
		int aliveAnchors = encounter.anchorAliveMask();
		if (!encounter.encounterId().equals(trackedEncounterId)) {
			trackedEncounterId = encounter.encounterId();
			trackedBand = band;
			trackedAnchorMask = aliveAnchors;
			displayedRatio = target;
			lastTargetRatio = target;
			ghostRatio = target;
			ghostHold = 0.0F;
			damageFlash = 0.0F;
			bandFlash = 0.0F;
			anchorSweep = 0.0F;
			lastRenderTime = Double.NaN;
		} else {
			if (band != trackedBand) {
				trackedBand = band;
				bandFlash = BAND_FLASH_TICKS;
			}
			// Bits only ever leave this mask, and each one that does is an anchor the roster cut.
			if ((trackedAnchorMask & ~aliveAnchors) != 0) anchorSweep = ANCHOR_SWEEP_TICKS;
			trackedAnchorMask = aliveAnchors;
		}
		double now = client.level.getGameTime() + tickCounter.getGameTimeDeltaPartialTick(false);
		float delta = Double.isNaN(lastRenderTime) ? 0.0F
				: (float) Math.clamp(now - lastRenderTime, 0.0D, 20.0D);
		lastRenderTime = now;
		if (Float.isNaN(displayedRatio)) {
			displayedRatio = target;
			ghostRatio = target;
		}

		if (target < lastTargetRatio - 1.0E-4F) {
			ghostHold = GHOST_HOLD_TICKS;
			damageFlash = DAMAGE_FLASH_TICKS;
			if (ghostRatio < displayedRatio) ghostRatio = displayedRatio;
		} else if (target > lastTargetRatio + 1.0E-4F) {
			// Healing must not leave a stale ghost floating above the restored gauge.
			ghostRatio = Math.min(ghostRatio, target);
		}
		lastTargetRatio = target;
		displayedRatio += (target - displayedRatio) * approach(HEALTH_EASE_RATE, delta);
		damageFlash = Math.max(0.0F, damageFlash - delta);
		bandFlash = Math.max(0.0F, bandFlash - delta);
		anchorSweep = Math.max(0.0F, anchorSweep - delta);
		if (ghostHold > 0.0F) ghostHold = Math.max(0.0F, ghostHold - delta);
		else ghostRatio += (displayedRatio - ghostRatio) * approach(GHOST_EASE_RATE, delta);
	}

	/** Converts a per-tick rate into the fraction to close over {@code delta} ticks. */
	private static float approach(float ratePerTick, float delta) {
		if (delta <= 0.0F) return 0.0F;
		return 1.0F - (float) Math.pow(1.0F - ratePerTick, delta);
	}

	/** Decaying pulse that washes the gauge white for a moment after the interface changes body. */
	private static float bandFlashAmount() {
		if (bandFlash <= 0.0F) return 0.0F;
		float progress = bandFlash / BAND_FLASH_TICKS;
		return progress * progress;
	}

	private static int accentColor(int band) {
		return ARGB.colorFromFloat(1.0F,
				Math.min(1.0F, WorldInterfacePalette.red(band) * 0.82F + 0.14F),
				Math.min(1.0F, WorldInterfacePalette.green(band) * 0.82F + 0.14F),
				Math.min(1.0F, WorldInterfacePalette.blue(band) * 0.82F + 0.14F));
	}

	private static void resetBarState() {
		trackedEncounterId = null;
		trackedBand = -1;
		trackedAnchorMask = 0;
		displayedRatio = Float.NaN;
		lastTargetRatio = Float.NaN;
		ghostRatio = Float.NaN;
		ghostHold = 0.0F;
		damageFlash = 0.0F;
		bandFlash = 0.0F;
		anchorSweep = 0.0F;
		lastRenderTime = Double.NaN;
	}

	private static Component stageLabel(WorldInterfaceSnapshotS2C encounter) {
		return switch (encounter.stage()) {
			case SUMMONING -> Component.translatable("hud.thefourthfrequency.world_interface.stage.summoning");
			case PHASE_1 -> Component.translatable("hud.thefourthfrequency.world_interface.stage.phase_1");
			case PHASE_2 -> Component.translatable("hud.thefourthfrequency.world_interface.stage.phase_2");
			case PHASE_3 -> Component.translatable("hud.thefourthfrequency.world_interface.stage.phase_3");
			case SUCCESS_RESOLUTION -> Component.translatable(
					"hud.thefourthfrequency.world_interface.stage.success_resolution");
			case FAILURE_RESOLUTION -> Component.translatable(
					"hud.thefourthfrequency.world_interface.stage.failure_resolution");
			case PORTAL_OPEN -> encounter.outcome() == WorldInterfaceProtocol.Outcome.SUCCESS
					? Component.translatable("hud.thefourthfrequency.world_interface.stage.portal_success")
					: Component.translatable("hud.thefourthfrequency.world_interface.stage.portal_failure");
			default -> Component.translatable("hud.thefourthfrequency.world_interface.stage.phase_3");
		};
	}
}
