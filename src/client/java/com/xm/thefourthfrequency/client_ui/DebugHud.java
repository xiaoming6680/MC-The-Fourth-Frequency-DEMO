package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import com.xm.thefourthfrequency.terminal.AnomalyDimensionPolicy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The always-on developer readout, top left.
 *
 * <p>It exists because the panel answered the wrong half of the question: a screen that has to be
 * opened cannot show what the world is doing while the world is being played, and the two things a
 * developer wants to watch - when the next anomaly is due and what it is choosing between - only
 * mean anything in motion.</p>
 *
 * <p>Every countdown on it is carried forward locally between the server pushes. The push is the
 * truth and arrives twice a second; the local subtraction only fills the gap, so the number never
 * sits still for half a second and never drifts past the next push.</p>
 */
public final class DebugHud {
	private static final int MARGIN = 4;
	private static final int PADDING = 4;
	private static final int LINE_HEIGHT = 10;
	private static final int BACKGROUND = 0xB405090B;
	private static final int BORDER = 0xFF31575A;
	private static final int TEXT = 0xFFD6ECEC;
	private static final int HEADING = 0xFF91E5E5;
	private static final int STALE = 0xFFFF6A63;
	/** No push for this long and the readout says so rather than showing numbers that stopped moving. */
	private static final int STALE_TICKS = 40;

	private static DebugStatusPayload status;
	private static int ticksSinceStatus;
	private static boolean initialized;
	private static List<String> cachedLines = List.of();
	private static DebugStatusPayload cachedFor;
	private static int cachedSeconds = -1;
	private static int cachedGroupMask = -1;
	private static String cachedDimension = "";

	private DebugHud() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> ticksSinceStatus++);
		// Cleared on disconnect rather than left to go stale: the next world may not have debug on at
		// all, and a readout describing the previous save is worse than no readout.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> render(graphics));
	}

	static void accept(DebugStatusPayload payload) {
		status = payload.allowed() ? payload : null;
		ticksSinceStatus = 0;
	}

	static void clear() {
		status = null;
		ticksSinceStatus = 0;
		cachedFor = null;
		cachedLines = List.of();
	}

	/**
	 * The rendered lines, rebuilt only when something they depend on has moved.
	 *
	 * <p>This is a render callback, so it runs once per frame rather than once per tick, and the
	 * uncached version walked the anomaly catalogue and allocated a dozen strings and several lists
	 * every one of them. Nothing on this readout changes faster than once a second: the payload
	 * arrives twice a second, the countdowns step in whole seconds, and the group mask only moves
	 * when someone clicks a checkbox. Those four are the whole cache key.</p>
	 */
	private static List<String> lines(DebugStatusPayload current, String dimensionMode, int groupMask,
			int elapsedSeconds) {
		if (current == cachedFor && elapsedSeconds == cachedSeconds && groupMask == cachedGroupMask
				&& dimensionMode.equals(cachedDimension)) {
			return cachedLines;
		}
		cachedFor = current;
		cachedSeconds = elapsedSeconds;
		cachedGroupMask = groupMask;
		cachedDimension = dimensionMode;
		cachedLines = DebugReadout.hudLines(current, dimensionMode, groupMask, elapsedSeconds);
		return cachedLines;
	}

	private static void render(GuiGraphics graphics) {
		DebugStatusPayload current = status;
		if (current == null || !DebugHudState.visible()) return;
		Minecraft client = Minecraft.getInstance();
		// The vanilla debug overlay owns this corner when it is up, and the chat screen is where a
		// developer types the command that turns this on, so neither is a place to draw over.
		if (client.player == null || client.getDebugOverlay().showDebugScreen()) return;
		List<String> lines = lines(current, dimensionMode(client), DebugHudState.groupMask(),
				ticksSinceStatus / 20);
		if (lines.isEmpty()) return;

		boolean stale = ticksSinceStatus > STALE_TICKS;
		String heading = "第四频段 · 调试" + (stale ? "（同步等待）" : "");
		int width = client.font.width(heading);
		for (String line : lines) width = Math.max(width, client.font.width(line));
		int boxWidth = width + PADDING * 2;
		int boxHeight = PADDING * 2 + LINE_HEIGHT * (lines.size() + 1);
		graphics.fill(MARGIN, MARGIN, MARGIN + boxWidth, MARGIN + boxHeight, BACKGROUND);
		graphics.renderOutline(MARGIN, MARGIN, boxWidth, boxHeight, stale ? STALE : BORDER);

		int x = MARGIN + PADDING;
		int y = MARGIN + PADDING;
		graphics.drawString(client.font, Component.literal(heading), x, y, stale ? STALE : HEADING, false);
		y += LINE_HEIGHT;
		for (String line : lines) {
			graphics.drawString(client.font, Component.literal(line), x, y, TEXT, false);
			y += LINE_HEIGHT;
		}
	}

	/**
	 * Whether this dimension runs a schedule at all, read locally.
	 *
	 * <p>Same reason the panel asks locally: the dimension is something the client already knows for
	 * certain and the rule is a pure common class, so this costs no protocol field and cannot
	 * disagree with the answer the server would give.</p>
	 */
	private static String dimensionMode(Minecraft client) {
		if (client.level == null) return "维度未知";
		return switch (AnomalyDimensionPolicy.mode(client.level.dimension().identifier().toString())) {
			case NORMAL -> "常规";
			case PRESSURE -> "加压";
			case EXCLUDED -> "冻结";
		};
	}

	public static void resetForTesting() {
		clear();
	}

	/** Whether the server has told this client that debug is on for it. The permission, not a preference. */
	public static boolean active() {
		return status != null;
	}
}
