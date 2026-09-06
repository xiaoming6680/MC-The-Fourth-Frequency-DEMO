package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalNoticePayload;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudStatusBarHeightRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;

/**
 * Throttled priority notice stack. Incoming feedback waits in a bounded queue, then enters from the
 * bottom at a readable cadence, and the oldest line at the top always leaves first. Important
 * notices retain priority without bypassing that cadence.
 *
 * <p>Every deadline is suspended while the stack cannot be read - hidden HUD, an open screen, a
 * paused single player session, or a pursuit holding the queue - so a notice is never spent behind
 * something else and the stack never snaps into place when play resumes.
 */
public final class TerminalNoticeHud {
	static final int MAX_VISIBLE = 3;
	static final int MAX_PENDING = 12;
	static final long HOLD_MILLIS = 2_800L;
	static final long HOLD_CEILING_MILLIS = 6_000L;
	static final long HOLD_FLOOR_MILLIS = 1_600L;
	static final long ENTER_MILLIS = 180L;
	static final long EXIT_MILLIS = 260L;
	static final long MIN_APPEAR_INTERVAL_MILLIS = 900L;
	static final long MIN_APPEAR_FLOOR_MILLIS = 360L;
	static final long DUPLICATE_WINDOW_MILLIS = 5_000L;
	/** A notice that waited this long has outlived the moment it described, so it is dropped. */
	static final long PENDING_TTL_MILLIS = 20_000L;
	static final long ATTENTION_INTERVAL_MILLIS = 1_100L;
	private static final int MAX_RECENT = 64;
	private static final int MAX_LINES = 3;
	private static final int MAX_LINE_WIDTH = 220;
	private static final int PADDING_X = 7;
	private static final int PADDING_Y = 3;
	private static final int ENTRY_GAP = 2;
	private static final int SLIDE_IN_PIXELS = 11;
	private static final int EXIT_LIFT_PIXELS = 6;
	/**
	 * Clearance between the tallest vanilla status bar and the bottom of the stack. It keeps the
	 * panel above both the held item name and the vanilla action bar line, which share that strip.
	 */
	private static final int BASE_CLEARANCE = 21;
	private static final int FALLBACK_STATUS_HEIGHT = 49;
	private static final int DEFAULT_BACKGROUND = 0x101A14;
	private static final int DEFAULT_BORDER = 0x6FA77A;
	private static final int DEFAULT_TEXT = 0xD8F4DD;
	private static final int TASK_BACKGROUND = 0x185C32;
	private static final int TASK_BORDER = 0x72E595;
	private static final int PURSUIT_BACKGROUND = 0x59151B;
	private static final int PURSUIT_BORDER = 0xF05B65;
	private static final int PURSUIT_TEXT = 0xFFE2E4;
	private static final int DENIED_BACKGROUND = 0x3A2412;
	private static final int DENIED_BORDER = 0xC98A3C;
	private static final int DENIED_TEXT = 0xF5DDBA;
	// The finale's three voices. Each is far enough from the others - and from the four above - to
	// be told apart at a glance while a fight is happening, which is the whole point of moving these
	// lines out of the chat log.
	private static final int ENCOUNTER_BACKGROUND = 0x2A1044;
	private static final int ENCOUNTER_BORDER = 0xB565F0;
	private static final int ENCOUNTER_TEXT = 0xEAD6FF;
	private static final int ANCHOR_BACKGROUND = 0x4A3505;
	private static final int ANCHOR_BORDER = 0xFFC53D;
	private static final int ANCHOR_TEXT = 0xFFEFC2;
	private static final int DRAGON_BACKGROUND = 0x0B3D3A;
	private static final int DRAGON_BORDER = 0x4FD8C8;
	private static final int DRAGON_TEXT = 0xD3FBF5;
	private static final NoticeEntry PURSUIT_STATUS = new NoticeEntry(
			Component.translatable("message.thefourthfrequency.pursuit.try_escape"),
			TerminalNoticePayload.TONE_PURSUIT_WARNING);
	private static final NoticeEntry ESCAPE_STATUS = new NoticeEntry(
			Component.translatable("message.thefourthfrequency.pursuit.escaped_temporary"),
			TerminalNoticePayload.TONE_TASK_COMPLETE);
	private static final List<NoticeEntry> ENTRIES = new ArrayList<>();
	private static final List<NoticeEntry> PENDING = new ArrayList<>();
	private static final List<RecentNotice> RECENT = new ArrayList<>();
	private static long lastActivatedAt;
	private static long lastAttentionAt;
	private static long lastFrameAt;
	private static NoticeEntry exiting;
	/** The guidance readout's entry, rebuilt whenever its sentence changes. Never queued. */
	private static NoticeEntry navigationEntry;
	/** The unrendered layer's bearing, on the same terms: permanent, never queued, never expiring. */
	private static NoticeEntry unrenderedEntry;
	private static boolean initialized;
	private static boolean statusHeightUnavailable;

	private TerminalNoticeHud() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		// Kept on the tail callback rather than a hud element: the pursuit and anomaly overlays draw
		// from the same callback, and the stack has to stay readable on top of them.
		HudRenderCallback.EVENT.register((graphics, tickCounter) -> render(graphics));
	}

	public static void enqueue(Component message) {
		enqueue(message, TerminalNoticePayload.TONE_NONE);
	}

	public static void enqueue(Component message, int tone) {
		long now = Util.getMillis();
		String key = noticeKey(message, tone);
		if (mergeDuplicate(key, now)) return;
		if (duplicateRecently(key, now)) return;
		remember(key, now);
		NoticeEntry entry = new NoticeEntry(message, tone);
		entry.queuedAt = now;
		int priority = priority(tone);
		int insertion = PENDING.size();
		for (int index = 0; index < PENDING.size(); index++) {
			if (priority > priority(PENDING.get(index).tone)) {
				insertion = index;
				break;
			}
		}
		PENDING.add(insertion, entry);
		trimPending();
	}

	/** Advances the queue by one frame; only reached while the stack is actually readable. */
	private static void advance(long now) {
		if (exiting != null && now - exiting.exitStartedAt >= EXIT_MILLIS) finishExit();
		PENDING.removeIf(entry -> now - entry.queuedAt >= PENDING_TTL_MILLIS);
		promotePending(now);
		if (exiting != null || ENTRIES.isEmpty()) return;
		if (now < ENTRIES.getFirst().expiresAt) return;
		// The oldest line at the top leaves first, so the entries below it never move underneath it.
		exiting = ENTRIES.getFirst();
		exiting.exitStartedAt = now;
	}

	private static void promotePending(long now) {
		if (PENDING.isEmpty() || visibleCount() >= MAX_VISIBLE) return;
		if (!ENTRIES.isEmpty() && now - lastActivatedAt < appearInterval()) return;
		// While the encounter is running, only its own voice gets through. Everything else stays in
		// the queue on its ordinary TTL rather than being dropped here, so a fight that ends inside
		// twenty seconds of a notice still delivers it; a long one lets it lapse, and the record it
		// was announcing is waiting in the terminal either way. Held rather than filtered at the
		// source because this is a presentation decision and the server has no business knowing which
		// frame the player is looking at.
		int index = encounterRunning() ? indexOfEncounterNotice() : 0;
		if (index < 0) return;
		NoticeEntry pending = PENDING.remove(index);
		pending.promotedAt = now;
		pending.expiresAt = now + holdMillis(pending);
		pending.currentOffset = -SLIDE_IN_PIXELS;
		ENTRIES.add(pending);
		lastActivatedAt = now;
		if (pending.tone != TerminalNoticePayload.TONE_NONE
				&& now - lastAttentionAt >= ATTENTION_INTERVAL_MILLIS) {
			lastAttentionAt = now;
			TerminalClientAudio.attention(pending.tone);
		}
	}

	/**
	 * Whether the local player is inside a running World Interface encounter.
	 *
	 * <p>The same projection the boss HUD draws from, so the two agree about when the fight is on
	 * without a second definition to keep in step.</p>
	 */
	private static boolean encounterRunning() {
		return WorldInterfaceClientState.snapshot().combatVisible();
	}

	/** First queued notice the encounter allows through, or -1 while it allows none. */
	private static int indexOfEncounterNotice() {
		for (int index = 0; index < PENDING.size(); index++) {
			if (TerminalNoticePayload.surfacesDuringEncounter(PENDING.get(index).tone)) return index;
		}
		return -1;
	}

	/** A backlog tightens the cadence so late notices still land near the event that caused them. */
	private static long appearInterval() {
		return Math.max(MIN_APPEAR_FLOOR_MILLIS, MIN_APPEAR_INTERVAL_MILLIS - PENDING.size() * 60L);
	}

	/** Longer lines hold longer, and a deep backlog shortens every hold within the same bounds. */
	private static long holdMillis(NoticeEntry entry) {
		int width = Minecraft.getInstance().font.width(entry.display());
		long readable = HOLD_MILLIS + width * 6L;
		long pressure = Math.min(1_200L, PENDING.size() * 120L);
		return Math.clamp(readable - pressure, HOLD_FLOOR_MILLIS, HOLD_CEILING_MILLIS);
	}

	private static int visibleCount() {
		return exiting == null ? ENTRIES.size() : ENTRIES.size() - 1;
	}

	private static void trimPending() {
		if (PENDING.size() <= MAX_PENDING) return;
		int lowestPriority = priority(PENDING.getLast().tone);
		for (int index = 0; index < PENDING.size(); index++) {
			if (priority(PENDING.get(index).tone) == lowestPriority) {
				PENDING.remove(index);
				return;
			}
		}
	}

	/** A repeat of something still on screen or still queued becomes a counter instead of a drop. */
	private static boolean mergeDuplicate(String key, long now) {
		for (NoticeEntry entry : PENDING) {
			if (!entry.key.equals(key)) continue;
			entry.repeat++;
			entry.queuedAt = now;
			return true;
		}
		for (NoticeEntry entry : ENTRIES) {
			if (entry == exiting || !entry.key.equals(key)) continue;
			entry.repeat++;
			entry.expiresAt = Math.max(entry.expiresAt, now + HOLD_MILLIS);
			return true;
		}
		return false;
	}

	private static boolean duplicateRecently(String key, long now) {
		RECENT.removeIf(entry -> now - entry.createdAt >= DUPLICATE_WINDOW_MILLIS);
		for (RecentNotice entry : RECENT) if (entry.key.equals(key)) return true;
		return false;
	}

	private static void remember(String key, long now) {
		if (RECENT.size() >= MAX_RECENT) RECENT.removeFirst();
		RECENT.add(new RecentNotice(key, now));
	}

	private static String noticeKey(Component message, int tone) {
		return tone + "\u0000" + message.getString();
	}

	private static int priority(int tone) {
		return switch (tone) {
			case TerminalNoticePayload.TONE_PURSUIT_WARNING -> 3;
			// An anchor falling changes what the fight is doing to everyone, so it rides at the top
			// with the pursuit rather than queueing behind ordinary progress lines.
			case TerminalNoticePayload.TONE_ANCHOR -> 3;
			// A refused action is direct feedback on what the player just did, so it outranks narration.
			case TerminalNoticePayload.TONE_DENIED -> 2;
			case TerminalNoticePayload.TONE_TASK_COMPLETE -> 2;
			case TerminalNoticePayload.TONE_ENCOUNTER -> 2;
			case TerminalNoticePayload.TONE_DRAGON -> 1;
			case TerminalNoticePayload.TONE_UNREAD -> 1;
			default -> 0;
		};
	}

	private static void render(GuiGraphics graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.level == null) {
			clear();
			return;
		}
		long now = Util.getMillis();
		long delta = lastFrameAt == 0L ? 0L : Math.max(0L, now - lastFrameAt);
		lastFrameAt = now;
		if (client.options.hideGui) {
			suspend(delta);
			return;
		}
		Font font = client.font;
		int anchor = anchorY(graphics);
		if (PursuitPresentationClient.pursuitHudActive()) {
			suspend(delta);
			drawEntry(graphics, font, PURSUIT_STATUS, anchor, 0.0D, 230);
			return;
		}
		if (PursuitPresentationClient.escapeHudActive()) {
			suspend(delta);
			drawEntry(graphics, font, ESCAPE_STATUS, anchor, 0.0D, 230);
			return;
		}
		if (PursuitPresentationClient.holdsNoticeQueue()) {
			suspend(delta);
			return;
		}
		// The guidance readout sits below the stack and lifts it, rather than taking the HUD over the
		// way a pursuit does. It is a state the player is reading continuously, not an event: it must
		// not silence a task completion or a lock warning, and none of those may push it off screen.
		anchor = drawNavigationReadout(graphics, font, anchor);
		anchor = drawUnrenderedReadout(graphics, font, anchor);
		boolean frozen = client.isPaused() || client.screen != null;
		if (frozen) suspend(delta);
		else advance(now);
		if (ENTRIES.isEmpty()) return;
		int wrapWidth = wrapWidth(graphics);
		layout(font, wrapWidth, frozen ? 0L : delta);
		for (NoticeEntry entry : ENTRIES) {
			double exitProgress = entry == exiting
					? Math.clamp((now - entry.exitStartedAt) / (double) EXIT_MILLIS, 0.0D, 1.0D) : 0.0D;
			double enterProgress = Math.clamp(
					(now - entry.promotedAt) / (double) ENTER_MILLIS, 0.0D, 1.0D);
			int alpha = (int) Math.round(230.0D * enterProgress * (1.0D - exitProgress));
			if (alpha <= 0) continue;
			drawEntry(graphics, font, entry, anchor,
					entry.currentOffset + exitProgress * EXIT_LIFT_PIXELS, alpha);
		}
	}

	/**
	 * Draws the guidance readout, and returns the anchor the notice stack should sit on top of.
	 *
	 * <p>Never queued and never expiring on its own clock: the sentence is rebuilt from the newest
	 * navigation payload every frame, and it disappears when {@link TerminalNavigationReadout} judges
	 * that stream to have stopped. That is the whole reason it is not an ordinary notice - a bearing
	 * that changes every step would spend the entire stack on itself within seconds.
	 */
	private static int drawNavigationReadout(GuiGraphics graphics, Font font, int anchor) {
		Component line = TerminalNavigationReadout.line();
		if (line == null) {
			navigationEntry = null;
			return anchor;
		}
		// Rebuilt only when the sentence actually changes, so the entry's wrapped-line cache survives
		// the frames where the player has not moved far enough to change a number.
		if (navigationEntry == null || !navigationEntry.message.equals(line)) {
			navigationEntry = new NoticeEntry(line, TerminalNoticePayload.TONE_NONE);
		}
		drawEntry(graphics, font, navigationEntry, anchor, 0.0D, 230);
		return anchor - navigationEntry.height(font, wrapWidth(graphics)) - ENTRY_GAP;
	}

	/**
	 * Draws the unrendered layer's bearing, and returns the anchor the stack sits on top of.
	 *
	 * <p>Sits with the guidance readout rather than in the notice stack, and for the same reason: it
	 * is a state the player reads continuously, not an event. As a notice it spent a stack slot every
	 * time it repeated and was absent in between, which made a permanent fact behave like news.
	 */
	private static int drawUnrenderedReadout(GuiGraphics graphics, Font font, int anchor) {
		Component line = UnrenderedLayerClient.bearingLine();
		if (line == null) {
			unrenderedEntry = null;
			return anchor;
		}
		if (unrenderedEntry == null || !unrenderedEntry.message.equals(line)) {
			unrenderedEntry = new NoticeEntry(line, TerminalNoticePayload.TONE_UNRENDERED);
		}
		drawEntry(graphics, font, unrenderedEntry, anchor, 0.0D, 230);
		return anchor - unrenderedEntry.height(font, wrapWidth(graphics)) - ENTRY_GAP;
	}

	/**
	 * Shifts every deadline by the frames the stack was not readable for, so a hold costs no display
	 * time and nothing is already expired when the stack comes back.
	 */
	private static void suspend(long delta) {
		if (delta <= 0L) return;
		for (NoticeEntry entry : ENTRIES) {
			entry.promotedAt += delta;
			entry.expiresAt += delta;
			entry.exitStartedAt += delta;
		}
		for (NoticeEntry entry : PENDING) entry.queuedAt += delta;
		lastActivatedAt += delta;
		lastAttentionAt += delta;
	}

	private static void layout(Font font, int wrapWidth, long delta) {
		double offset = 0.0D;
		for (int index = ENTRIES.size() - 1; index >= 0; index--) {
			NoticeEntry entry = ENTRIES.get(index);
			entry.targetOffset = offset;
			offset += entry.height(font, wrapWidth) + ENTRY_GAP;
		}
		if (delta <= 0L) return;
		double movement = 1.0D - Math.exp(-Math.clamp(delta, 0L, 100L) / 70.0D);
		for (NoticeEntry entry : ENTRIES) {
			entry.currentOffset += (entry.targetOffset - entry.currentOffset) * movement;
		}
	}

	private static void drawEntry(GuiGraphics graphics, Font font, NoticeEntry entry, int anchor,
			double lift, int alpha) {
		int wrapWidth = wrapWidth(graphics);
		List<FormattedCharSequence> lines = entry.lines(font, wrapWidth);
		if (lines.isEmpty()) return;
		int textWidth = 0;
		for (FormattedCharSequence line : lines) textWidth = Math.max(textWidth, font.width(line));
		int panelWidth = textWidth + PADDING_X * 2;
		int panelHeight = lines.size() * font.lineHeight + PADDING_Y * 2;
		int centerX = graphics.guiWidth() / 2;
		int left = centerX - panelWidth / 2;
		int bottom = anchor - (int) Math.round(lift);
		int top = bottom - panelHeight;
		int backgroundColor = switch (entry.tone) {
			case TerminalNoticePayload.TONE_PURSUIT_WARNING -> PURSUIT_BACKGROUND;
			case TerminalNoticePayload.TONE_DENIED -> DENIED_BACKGROUND;
			case TerminalNoticePayload.TONE_TASK_COMPLETE -> TASK_BACKGROUND;
			case TerminalNoticePayload.TONE_ENCOUNTER -> ENCOUNTER_BACKGROUND;
			case TerminalNoticePayload.TONE_ANCHOR -> ANCHOR_BACKGROUND;
			case TerminalNoticePayload.TONE_DRAGON -> DRAGON_BACKGROUND;
			default -> DEFAULT_BACKGROUND;
		};
		int borderColor = switch (entry.tone) {
			case TerminalNoticePayload.TONE_PURSUIT_WARNING -> PURSUIT_BORDER;
			case TerminalNoticePayload.TONE_DENIED -> DENIED_BORDER;
			case TerminalNoticePayload.TONE_TASK_COMPLETE -> TASK_BORDER;
			case TerminalNoticePayload.TONE_ENCOUNTER -> ENCOUNTER_BORDER;
			case TerminalNoticePayload.TONE_ANCHOR -> ANCHOR_BORDER;
			case TerminalNoticePayload.TONE_DRAGON -> DRAGON_BORDER;
			default -> DEFAULT_BORDER;
		};
		int textColor = switch (entry.tone) {
			case TerminalNoticePayload.TONE_PURSUIT_WARNING -> PURSUIT_TEXT;
			case TerminalNoticePayload.TONE_DENIED -> DENIED_TEXT;
			case TerminalNoticePayload.TONE_ENCOUNTER -> ENCOUNTER_TEXT;
			case TerminalNoticePayload.TONE_ANCHOR -> ANCHOR_TEXT;
			case TerminalNoticePayload.TONE_DRAGON -> DRAGON_TEXT;
			default -> DEFAULT_TEXT;
		};
		graphics.fill(left, top, left + panelWidth, bottom, alpha << 24 | backgroundColor);
		graphics.renderOutline(left, top, panelWidth, panelHeight,
				Math.min(255, alpha + 20) << 24 | borderColor);
		int lineY = top + PADDING_Y;
		for (FormattedCharSequence line : lines) {
			graphics.drawString(font, line, centerX - font.width(line) / 2, lineY,
					alpha << 24 | textColor, true);
			lineY += font.lineHeight;
		}
	}

	private static int wrapWidth(GuiGraphics graphics) {
		return Math.max(60, Math.min(MAX_LINE_WIDTH, graphics.guiWidth() - 40));
	}

	/**
	 * Follows the status bars instead of a fixed inset, so a mount health bar or another mod's bar
	 * pushes the stack up exactly as it pushes the vanilla lines up.
	 */
	private static int anchorY(GuiGraphics graphics) {
		return graphics.guiHeight() - (statusBarHeight() + BASE_CLEARANCE);
	}

	private static int statusBarHeight() {
		if (statusHeightUnavailable) return FALLBACK_STATUS_HEIGHT;
		try {
			return Math.max(HudStatusBarHeightRegistry.getHeight(VanillaHudElements.ARMOR_BAR),
					HudStatusBarHeightRegistry.getHeight(VanillaHudElements.AIR_BAR));
		} catch (RuntimeException unavailable) {
			// A removed or replaced vanilla bar leaves the registry unable to answer; vanilla spacing
			// is the right fallback and retrying every frame would only repeat the failure.
			statusHeightUnavailable = true;
			return FALLBACK_STATUS_HEIGHT;
		}
	}

	private static void finishExit() {
		if (exiting == null) return;
		ENTRIES.remove(exiting);
		exiting = null;
	}

	private static void clear() {
		ENTRIES.clear();
		PENDING.clear();
		RECENT.clear();
		exiting = null;
		navigationEntry = null;
		unrenderedEntry = null;
		// Leaving the world drops the readout with everything else, so a bearing from the last save
		// cannot survive into the next one for the second it would take the stream to correct it.
		TerminalNavigationReadout.clear();
		lastActivatedAt = 0L;
		lastAttentionAt = 0L;
		lastFrameAt = 0L;
	}

	static int queuedForTesting() {
		return ENTRIES.size() + PENDING.size();
	}

	static void clearForTesting() {
		clear();
	}

	private static final class NoticeEntry {
		private final Component message;
		private final String key;
		private final int tone;
		private int repeat = 1;
		private long queuedAt;
		private long promotedAt;
		private long expiresAt;
		private long exitStartedAt;
		private double currentOffset;
		private double targetOffset;
		private Component cachedDisplay;
		private int cachedDisplayRepeat;
		private List<FormattedCharSequence> cachedLines = List.of();
		private int cachedLineRepeat;
		private int cachedWrapWidth = -1;

		private NoticeEntry(Component message, int tone) {
			this.message = message;
			this.key = noticeKey(message, tone);
			this.tone = tone;
		}

		private Component display() {
			if (repeat <= 1) return message;
			if (cachedDisplay == null || cachedDisplayRepeat != repeat) {
				cachedDisplayRepeat = repeat;
				cachedDisplay = Component.empty().append(message).append(" ×" + repeat);
			}
			return cachedDisplay;
		}

		private List<FormattedCharSequence> lines(Font font, int wrapWidth) {
			if (cachedWrapWidth != wrapWidth || cachedLineRepeat != repeat) {
				cachedWrapWidth = wrapWidth;
				cachedLineRepeat = repeat;
				List<FormattedCharSequence> split = font.split(display(), wrapWidth);
				cachedLines = split.size() <= MAX_LINES ? split : List.copyOf(split.subList(0, MAX_LINES));
			}
			return cachedLines;
		}

		private int height(Font font, int wrapWidth) {
			return lines(font, wrapWidth).size() * font.lineHeight + PADDING_Y * 2;
		}
	}

	private record RecentNotice(String key, long createdAt) {
	}
}
