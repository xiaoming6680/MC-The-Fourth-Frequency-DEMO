package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.ending.WorldInterfacePolicy;
import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.UUID;

/**
 * The shared, server-authoritative altar screen: who has given up their terminal, how long the
 * ritual has left to start, and the one button that starts it.
 *
 * <p>Rebuilt for the v3 ritual, which changed what this screen is <em>about</em>. Under the old
 * rules the roster was every player online, the screen could only report a list it had no part in,
 * and the fight began by itself the moment the last name ticked over. All three of those were
 * things happening to the player. Now the roster is the people who walked up and handed something
 * over, the window is a deadline they can watch, and starting is a deliberate press by one of
 * them - so the screen is built around the three questions those raise: who is in, how long is
 * left, and is it time.</p>
 *
 * <p>Insertion itself is still a held-item interaction at the block and deliberately has no button.
 * Surrendering the terminal has to stay an act performed on the world rather than a confirmation
 * dialog; what belongs here is everything that happens around that act.</p>
 */
public final class ResonanceAltarScreen extends Screen {
	private static final int BACKDROP = 0xE60A0611;
	private static final int PANEL = 0xF2140E20;
	private static final int PANEL_INNER = 0x2AFFFFFF;
	private static final int BORDER = 0xFF7C47A8;
	private static final int ACCENT = 0xFFD38BFF;
	private static final int GOLD = 0xFFFFD878;
	private static final int WARN = 0xFFFFA24C;
	private static final int URGENT = 0xFFFF6B6B;
	private static final int MUTED = 0xFF9186A2;
	private static final int TEXT = 0xFFE9E3EE;
	private static final int CARD = 0xC01B1329;
	private static final int CARD_IN = 0xC0231A38;
	private static final int TRACK = 0xFF221A2C;
	private static final int HINT_PANEL = 0x8C231338;
	private static final int PLATE = 0xE0241338;
	private static final int PLATE_HOT = 0xF03D1E56;
	private static final int PLATE_OFF = 0x80161020;

	private static final int MAX_ROSTER = WorldInterfaceProtocol.MAX_PARTICIPANTS;
	/**
	 * How long an action's own answer stays on screen before the steady refresh may replace it.
	 *
	 * <p>The altar pushes a snapshot every second so the countdown and the summon button cannot go
	 * stale. Those pushes carry {@code WAITING}, which would otherwise wipe "the altar is full" or
	 * "you are not holding your terminal" within a second of it appearing - and those two sentences
	 * are the entire explanation for a refusal the player is going to repeat otherwise.
	 */
	private static final long STATUS_HOLD_MILLIS = 6_000L;
	/** Below this the countdown reads as running out rather than running. */
	private static final int URGENT_TICKS = 30 * 20;

	/**
	 * The summon plate's own size, in the panel rather than in a row of buttons.
	 *
	 * <p>Summoning is the one irreversible press on this screen and the only thing a player who has
	 * already inserted still has to do, so it is not a third button of the same size as "close". It
	 * sits alone, centred, at the width the panel can give it.</p>
	 */
	private static final int SUMMON_HEIGHT = 36;
	private static final int SUMMON_MAX_WIDTH = 260;
	private static final float SUMMON_LABEL_SCALE = 1.6F;
	/** One pulse every 1.6 seconds - well under the 3 Hz ceiling the mod holds itself to. */
	private static final float SUMMON_PULSE_PERIOD_MILLIS = 1_600.0F;
	private static final float TAU = (float) (Math.PI * 2.0);

	private AltarSnapshotS2C snapshot;
	private WorldInterfaceProtocol.AltarStatus heldStatus;
	private long heldStatusAt;
	private SummonPlate summonButton;
	private Button withdrawButton;
	/** Top of the summon plate, which is the floor every wrapped line above it has to respect. */
	private int footerTop;

	public ResonanceAltarScreen(AltarSnapshotS2C snapshot) {
		super(Component.translatable("screen.thefourthfrequency.resonance_altar.title"));
		this.snapshot = snapshot;
		latchStatus(snapshot);
	}

	public boolean matches(UUID encounterId) {
		return snapshot.encounterId().equals(encounterId);
	}

	public void update(AltarSnapshotS2C update) {
		if (!matches(update.encounterId()) || update.revision() < snapshot.revision()) return;
		snapshot = update;
		latchStatus(update);
		if (minecraft != null) rebuildWidgets();
	}

	private void latchStatus(AltarSnapshotS2C update) {
		if (update.status() == WorldInterfaceProtocol.AltarStatus.WAITING) return;
		heldStatus = update.status();
		heldStatusAt = System.currentTimeMillis();
	}

	private WorldInterfaceProtocol.AltarStatus visibleStatus() {
		if (heldStatus != null && System.currentTimeMillis() - heldStatusAt < STATUS_HOLD_MILLIS) {
			return heldStatus;
		}
		return snapshot.status();
	}

	public void closeFromServer() {
		if (minecraft != null && minecraft.screen == this) minecraft.setScreen(null);
	}

	@Override
	protected void init() {
		Layout layout = layout();
		int row = layout.bottom() - 26;
		int narrow = 84;
		int summonWidth = Math.min(SUMMON_MAX_WIDTH, layout.width() - 80);
		footerTop = row - 12 - SUMMON_HEIGHT;
		summonButton = addRenderableWidget(new SummonPlate(
				layout.left() + (layout.width() - summonWidth) / 2, footerTop, summonWidth, SUMMON_HEIGHT,
				Component.translatable("button.thefourthfrequency.resonance_altar.summon"),
				() -> send(WorldInterfaceProtocol.AltarAction.SUMMON)));
		withdrawButton = addRenderableWidget(Button.builder(Component.translatable(
				"button.thefourthfrequency.resonance_altar.withdraw"), ignored -> send(
				WorldInterfaceProtocol.AltarAction.WITHDRAW))
				.bounds(layout.left() + 20, row, narrow + 24, 20).build());
		addRenderableWidget(Button.builder(Component.translatable(
				"button.thefourthfrequency.resonance_altar.close"), ignored -> onClose())
				.bounds(layout.right() - 20 - narrow, row, narrow, 20).build());
		refreshButtons();
	}

	private void refreshButtons() {
		if (summonButton == null || withdrawButton == null) return;
		boolean waiting = snapshot.stage() == WorldInterfaceProtocol.Stage.WAITING_TERMINALS;
		// Shown to everyone rather than hidden from those who cannot press it. A summon button that
		// only exists for some players makes "why can he start it and I cannot" invisible; a disabled
		// one with the roster right above it answers itself.
		summonButton.active = waiting && snapshot.localCanSummon();
		withdrawButton.visible = localDeposited();
		withdrawButton.active = waiting && withdrawButton.visible;
	}

	private void send(WorldInterfaceProtocol.AltarAction action) {
		if (WorldInterfaceClientNetworking.sendAltarAction(snapshot, action)) {
			summonButton.active = false;
			withdrawButton.active = false;
		}
	}

	private boolean localDeposited() {
		int index = localRosterIndex();
		return index >= 0 && snapshot.deposited(index);
	}

	private int localRosterIndex() {
		if (minecraft == null || minecraft.player == null) return -1;
		return snapshot.rosterIds().indexOf(minecraft.player.getUUID());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, height, BACKDROP);
		Layout layout = layout();
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), PANEL);
		graphics.renderOutline(layout.left(), layout.top(), layout.width(), layout.height(), BORDER);
		// A hairline inset inside the border. One frame reads as a box; two read as a housing.
		graphics.renderOutline(layout.left() + 3, layout.top() + 3,
				layout.width() - 6, layout.height() - 6, PANEL_INNER);
		graphics.fill(layout.left() + 1, layout.top() + 1, layout.right() - 1, layout.top() + 4, ACCENT);

		graphics.drawCenteredString(font, title, width / 2, layout.top() + 14, ACCENT);
		graphics.drawCenteredString(font, Component.translatable(
				"screen.thefourthfrequency.resonance_altar.subtitle"), width / 2, layout.top() + 27, MUTED);

		int cursor = renderWindow(graphics, layout, layout.top() + 44);
		cursor = renderRoster(graphics, layout, cursor + 8);
		cursor = renderInstruction(graphics, layout, cursor + 6);
		renderStatus(graphics, layout, cursor + 5);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * The deadline, as a bar that drains and a time that counts.
	 *
	 * <p>Two readings of one number on purpose. The bar is what a player takes in without looking at
	 * it, and the digits are what they check when the bar has started to worry them; the colour
	 * carries the same fact a third time for anyone who has the screen open in their peripheral
	 * vision while standing at the core.</p>
	 */
	private int renderWindow(GuiGraphics graphics, Layout layout, int top) {
		int left = layout.left() + 20;
		int right = layout.right() - 20;
		int barHeight = 6;
		int remaining = snapshot.windowRemainingTicks();
		boolean running = snapshot.windowRunning();
		int colour = !running ? MUTED : remaining <= URGENT_TICKS ? URGENT
				: remaining <= URGENT_TICKS * 2 ? WARN : ACCENT;

		graphics.fill(left, top, right, top + barHeight, TRACK);
		if (running) {
			int span = right - left;
			int filled = Math.max(1, (int) ((long) span * remaining / WorldInterfacePolicy.RITUAL_WINDOW_TICKS));
			graphics.fill(left, top, left + filled, top + barHeight, colour);
		}

		Component label = running
				? Component.translatable("screen.thefourthfrequency.resonance_altar.window.remaining",
						clock(remaining))
				: Component.translatable("screen.thefourthfrequency.resonance_altar.window.idle");
		int textY = top + barHeight + 5;
		graphics.drawString(font, label, left, textY, colour, false);

		Component slots = Component.translatable("screen.thefourthfrequency.resonance_altar.slots",
				snapshot.rosterIds().isEmpty() ? 0 : Integer.bitCount(snapshot.depositedMask()), MAX_ROSTER);
		graphics.drawString(font, slots, right - font.width(slots), textY, MUTED, false);
		return textY + font.lineHeight;
	}

	/** mm:ss from ticks, rounded up so the last second is shown rather than skipped. */
	private static String clock(int ticks) {
		int seconds = (ticks + 19) / 20;
		return String.format("%d:%02d", seconds / 60, seconds % 60);
	}

	private int renderRoster(GuiGraphics graphics, Layout layout, int top) {
		int count = Math.min(snapshot.rosterIds().size(), MAX_ROSTER);
		if (count == 0) {
			Component empty = Component.translatable("screen.thefourthfrequency.resonance_altar.roster.empty");
			graphics.drawString(font, empty, layout.left() + 20, top + 4, MUTED, false);
			return top + 4 + font.lineHeight;
		}
		int columns = layout.width() >= 520 && count > 4 ? 2 : 1;
		int rows = Math.max(1, (count + columns - 1) / columns);
		int gap = 8;
		int areaLeft = layout.left() + 20;
		int areaRight = layout.right() - 20;
		int columnWidth = (areaRight - areaLeft - gap * (columns - 1)) / columns;
		int rowHeight = 20;
		int localIndex = localRosterIndex();
		for (int index = 0; index < count; index++) {
			int column = index / rows;
			int row = index % rows;
			int x = areaLeft + column * (columnWidth + gap);
			int y = top + row * rowHeight;
			boolean deposited = snapshot.deposited(index);
			graphics.fill(x, y, x + columnWidth, y + rowHeight - 3, deposited ? CARD_IN : CARD);
			// A solid edge rather than an outline: an outline around your own row reads as a
			// selection you could act on, and this list is not one.
			graphics.fill(x, y, x + 2, y + rowHeight - 3, deposited ? GOLD : MUTED);
			if (index == localIndex) graphics.fill(x + 2, y, x + 3, y + rowHeight - 3, ACCENT);
			graphics.drawString(font, Component.literal(deposited ? "◆" : "◇"), x + 9, y + 4,
					deposited ? GOLD : MUTED, false);
			String name = snapshot.rosterNames().get(index);
			int nameX = x + 23;
			graphics.drawString(font, Component.literal(name), nameX, y + 4, TEXT, false);
			if (index == localIndex) {
				graphics.drawString(font, Component.translatable(
						"screen.thefourthfrequency.resonance_altar.roster.you"),
						nameX + font.width(name) + 4, y + 4, ACCENT, false);
			}
			Component state = Component.translatable(deposited
					? "screen.thefourthfrequency.resonance_altar.roster.deposited"
					: "screen.thefourthfrequency.resonance_altar.roster.waiting");
			graphics.drawString(font, state, x + columnWidth - font.width(state) - 8, y + 4,
					deposited ? GOLD : MUTED, false);
		}
		return top + rows * rowHeight;
	}

	/**
	 * The one thing this player should do next, and nothing else.
	 *
	 * <p>Four states, because there are four positions a player can be standing in: holding their
	 * terminal, having given it up while others have not, being the one who can start it, and
	 * watching without a stake. Each gets the sentence that is true for them rather than a shared
	 * paragraph covering all of it.</p>
	 */
	private int renderInstruction(GuiGraphics graphics, Layout layout, int top) {
		if (snapshot.stage() != WorldInterfaceProtocol.Stage.WAITING_TERMINALS) return top;
		boolean deposited = localDeposited();
		boolean canSummon = snapshot.localCanSummon();
		String key = canSummon ? "screen.thefourthfrequency.resonance_altar.hint.summon_ready"
				: deposited ? "screen.thefourthfrequency.resonance_altar.inserted_hint"
				: "screen.thefourthfrequency.resonance_altar.insert_hint";
		int left = layout.left() + 20;
		int right = layout.right() - 20;
		List<FormattedCharSequence> lines = font.split(Component.translatable(key), right - left - 16);
		int panelHeight = lines.size() * font.lineHeight + 8;
		graphics.fill(left, top, right, top + panelHeight, HINT_PANEL);
		// Pulses only while it is still asking for something, so a satisfied panel goes quiet.
		int marker = canSummon ? GOLD : deposited ? MUTED
				: 0xFF000000 | (Mth.hsvToRgb(0.78F, 0.55F,
						0.75F + 0.25F * Mth.sin(System.currentTimeMillis() / 260.0F)) & 0xFFFFFF);
		graphics.fill(left, top, left + 2, top + panelHeight, marker);
		int lineY = top + 4;
		for (FormattedCharSequence line : lines) {
			graphics.drawString(font, line, left + 10, lineY, canSummon ? GOLD : TEXT, false);
			lineY += font.lineHeight;
		}
		return top + panelHeight;
	}

	/** Wrapped rather than one clipped line: several of these statuses are full sentences. */
	private void renderStatus(GuiGraphics graphics, Layout layout, int top) {
		Component status = Component.translatable(visibleStatus().translationKey());
		int available = layout.width() - 40;
		int ceiling = footerTop - 4;
		for (FormattedCharSequence line : font.split(status, available)) {
			if (top + font.lineHeight > ceiling) return;
			graphics.drawString(font, line, layout.left() + (layout.width() - font.width(line)) / 2, top,
					GOLD, false);
			top += font.lineHeight;
		}
	}

	private Layout layout() {
		int panelWidth = Math.max(340, Math.min(640, width - 28));
		int panelHeight = Math.max(300, Math.min(400, height - 24));
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		return new Layout(left, top, panelWidth, panelHeight);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * The summon, drawn as a plate rather than worn as a vanilla button.
	 *
	 * <p>Everything else on this screen reports; this is the only control that does something the
	 * party cannot take back, and it is the step a player who has just handed over their terminal
	 * still has to be told exists. A stock button in a row of stock buttons says none of that - it
	 * says "one of four things you may click". So it is given the width of the panel, the middle of
	 * it, and a housing of its own that answers whether it is live before the label is read.</p>
	 *
	 * <p>Live, it breathes: one cycle every 1.6 seconds, far below the mod's 3 Hz flicker ceiling,
	 * and only ever between two lit colours - the plate never goes dark and back, because a control
	 * that blinks off reads as broken rather than as waiting.</p>
	 */
	private final class SummonPlate extends Button {
		private SummonPlate(int x, int y, int width, int height, Component message, Runnable action) {
			super(x, y, width, height, message, ignored -> action.run(), DEFAULT_NARRATION);
			// AbstractButton draws its sprite in a final renderWidget and then hands over to
			// renderContents, so the vanilla frame cannot be overridden away - but it is blitted
			// with this alpha, and at zero it is not there at all. Everything below is then the
			// whole widget, and the press, keyboard focus and narration stay vanilla's.
			setAlpha(0.0F);
		}

		@Override
		protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			boolean live = active;
			boolean hot = live && isHoveredOrFocused();
			float pulse = live ? 0.5F + 0.5F * Mth.sin(System.currentTimeMillis()
					% (long) SUMMON_PULSE_PERIOD_MILLIS / SUMMON_PULSE_PERIOD_MILLIS * TAU) : 0.0F;
			int left = getX();
			int top = getY();
			int right = getRight();
			int bottom = getBottom();
			int edge = live ? (hot ? GOLD : blend(ACCENT, GOLD, pulse)) : MUTED;

			// A halo outside the frame rather than a brighter frame: the plate has to be findable
			// without reading it, and only when it is actually pressable.
			if (live) graphics.fill(left - 2, top - 2, right + 2, bottom + 2,
					withAlpha(edge, 40 + Math.round(50.0F * pulse)));
			graphics.fill(left, top, right, bottom, live ? (hot ? PLATE_HOT : PLATE) : PLATE_OFF);
			graphics.renderOutline(left, top, getWidth(), getHeight(), edge);
			graphics.renderOutline(left + 3, top + 3, getWidth() - 6, getHeight() - 6,
					withAlpha(edge, live ? 90 : 40));
			// Corner notches on opposite corners, which is what stops it reading as a plain box.
			int notch = 10;
			graphics.fill(left, top, left + notch, top + 2, edge);
			graphics.fill(left, top, left + 2, top + notch, edge);
			graphics.fill(right - notch, bottom - 2, right, bottom, edge);
			graphics.fill(right - 2, bottom - notch, right, bottom, edge);

			int colour = live ? (hot ? 0xFFFFFFFF : GOLD) : MUTED;
			float width = font.width(getMessage()) * SUMMON_LABEL_SCALE;
			float height = font.lineHeight * SUMMON_LABEL_SCALE;
			graphics.pose().pushMatrix();
			graphics.pose().translate(left + (getWidth() - width) / 2.0F,
					top + (getHeight() - height) / 2.0F);
			graphics.pose().scale(SUMMON_LABEL_SCALE, SUMMON_LABEL_SCALE);
			graphics.drawString(font, getMessage(), 0, 0, colour, live);
			graphics.pose().popMatrix();
		}
	}

	private static int withAlpha(int colour, int alpha) {
		return Math.clamp(alpha, 0, 255) << 24 | colour & 0xFFFFFF;
	}

	private static int blend(int from, int to, float amount) {
		float ratio = Math.clamp(amount, 0.0F, 1.0F);
		int red = Math.round(Mth.lerp(ratio, (from >> 16) & 0xFF, (to >> 16) & 0xFF));
		int green = Math.round(Mth.lerp(ratio, (from >> 8) & 0xFF, (to >> 8) & 0xFF));
		int blue = Math.round(Mth.lerp(ratio, from & 0xFF, to & 0xFF));
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private record Layout(int left, int top, int width, int height) {
		int right() { return left + width; }
		int bottom() { return top + height; }
	}
}
