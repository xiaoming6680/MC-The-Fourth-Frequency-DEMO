package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.DebugActionPayload;
import com.xm.thefourthfrequency.networking.DebugStatusPayload;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import com.xm.thefourthfrequency.pursuit.PursuitSlotManager;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.AnomalyDimensionPolicy;
import com.xm.thefourthfrequency.terminal.DebugNames;
import com.xm.thefourthfrequency.unrendered.UnrenderedAnchorPolicy;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The developer test bench: one screen, no pages.
 *
 * <p>The previous layout carried a read-only overview page beside three working pages, which meant
 * every number appeared twice and the two most frequent jobs - firing a named anomaly and reading
 * the current state - were never on screen together. Here the state block is written once, on the
 * right, and the left column is whichever list is being worked in. The tab strip above that list is
 * the only navigation left, and it exists because files and milestones are rare enough that giving
 * them permanent screen area would cost the anomaly list its no-scroll height.</p>
 */
public final class DebugPanelScreen extends Screen {
	private static final int SCREEN_MARGIN = 12;
	private static final int MAX_PANEL_WIDTH = 900;
	private static final int MAX_PANEL_HEIGHT = 520;
	private static final int HEADER_HEIGHT = 30;
	private static final int FOOTER_HEIGHT = 28;
	private static final int PAD = 10;
	private static final int MIN_RIGHT_WIDTH = 250;
	/** Below this the left list stops being a list, so the right column gives width back instead. */
	private static final int MIN_LIST_WIDTH = 150;
	private static final int MAX_RIGHT_WIDTH = 336;
	private static final int TAB_HEIGHT = 18;
	private static final int ROW_HEIGHT = 22;
	private static final int BUTTON_HEIGHT = 18;
	private static final int GAP = 4;
	private static final int LINE = 10;
	private static final int GROUP_LABEL = 10;
	private static final int GROUP_GAP = 4;
	private static final int RIGHT_SCROLLBAR_WIDTH = 6;
	/** No push for this long and the header stops claiming the numbers are current. */
	private static final int LIVE_TIMEOUT_TICKS = 40;

	private static final int OVERLAY = 0xCC05090B;
	private static final int PANEL = 0xFF10181B;
	private static final int CHROME = 0xFF131F22;
	private static final int WELL = 0xFF0B1113;
	private static final int ROW = 0xFF172225;
	private static final int ROW_ACTIVE = 0xFF1C3033;
	private static final int BORDER = 0xFF31575A;
	private static final int ACCENT = 0xFF6AC8C9;
	private static final int ACCENT_BRIGHT = 0xFF91E5E5;
	private static final int TEXT = 0xFFE6F0F0;
	private static final int MUTED = 0xFF91A7A8;
	private static final int DANGER = 0xFFFF6A63;

	private static final List<String> ANOMALIES = AnomalyCatalog.definitions().stream()
			.map(value -> value.id()).toList();
	private static final List<String> FILES = NarrativeFileCatalog.definitions().stream()
			.map(value -> value.id()).toList();

	/**
	 * The controls above the anomaly list.
	 *
	 * <p>HIM and the dark watcher sit here rather than in the list because the catalogue does not
	 * carry them: they have no tier, no duration and never occupy the active-anomaly slot, so there
	 * is no row for them to have. They are placed by their own services on their own schedules, and
	 * this is the only way to ask for one now instead of waiting out the interval.</p>
	 *
	 * <p>Not marked as needing confirmation: each places one figure out of view that removes itself,
	 * writes nothing to the record, and is among the least destructive entries on this screen.</p>
	 */
	private static final List<ActionSpec> ANOMALY_TOOLBAR = List.of(
			new ActionSpec("停止", "anomaly_stop", "", 0, false),
			new ActionSpec("恢复自动", "anomaly_resume", "", 0, false),
			new ActionSpec("HIM", "him_spawn", "", 0, false),
			new ActionSpec("暗处人影", "watcher_spawn", "", 0, false));

	/**
	 * The three that keep a confirmation, and the reason the rest lost theirs.
	 *
	 * <p>Everything else on this screen either undoes itself or has a cleanup path of its own: an
	 * anomaly can be stopped, a chase resolves and returns the player, a file relocks. These three
	 * do not. Two erase progress with no record of what was there, and the third commits the one
	 * finale state a world is allowed to have.</p>
	 */
	private static final List<String> CONFIRMED_ACTIONS = List.of("progress_reset", "files_lock", "boss_test");

	private static final List<ActionSpec> MAINLINE = List.of(
			new ActionSpec("完成前期准备", "prelude_ready", "", 0, false),
			new ActionSpec("前进一步", "progress_next", "", 0, false),
			new ActionSpec("重置主线", "progress_reset", "", 0, true));

	private static final List<ActionSpec> TESTS = List.of(
			new ActionSpec("追逐 第3形态", "pursuit_test", "", 3, false),
			new ActionSpec("BOSS 战", "boss_test", "", 0, true));

	private static final List<ActionSpec> FILE_ACTIONS = List.of(
			new ActionSpec("重置文件进度", "files_lock", "", 0, true));

	/**
	 * The two per-row file controls, and the six decay stages, as named specs.
	 *
	 * <p>Written out rather than built inline from a ternary or a loop counter so that the action ids
	 * are literals in the source. {@code DebugPanelContractTest} reads this file to prove every button
	 * reaches a branch the service handles, and an id assembled at runtime is one it cannot see.</p>
	 */
	private static final ActionSpec FILE_UNLOCK = new ActionSpec("解锁", "file_unlock", "", 0, false);
	private static final ActionSpec FILE_LOCK = new ActionSpec("锁定", "file_lock", "", 0, false);
	private static final List<ActionSpec> DECAY_STAGES = List.of(
			new ActionSpec("0", "decay", "", 0, false),
			new ActionSpec("1", "decay", "", 1, false),
			new ActionSpec("2", "decay", "", 2, false),
			new ActionSpec("3", "decay", "", 3, false),
			new ActionSpec("4", "decay", "", 4, false),
			new ActionSpec("5", "decay", "", 5, false),
			new ActionSpec("自动", "decay_auto", "", 0, false));
	private static final ActionSpec MILESTONE_COMPLETE = new ActionSpec("完成", "milestone", "", 0, false);

	private static PageMemory pageMemory = new PageMemory(0, 0);
	private DebugStatusPayload status;
	private String statusMessage;
	private Tab tab;
	private int scrollRow;
	private int maxScrollRow;
	private int rightScrollRow;
	private int maxRightScrollRow;
	private List<RightRow> cachedRightRows;
	private DebugStatusPayload cachedRowsFor;
	private int cachedRowsSeconds = -1;
	private int cachedRowsGroupMask = -1;
	private int ticksSinceStatus;
	private Pending pending;

	public DebugPanelScreen(DebugStatusPayload status) {
		super(Component.literal("第四频段 · 测试台"));
		this.status = status;
		this.statusMessage = status.message();
		this.tab = Tab.values()[Math.clamp(pageMemory.tabIndex, 0, Tab.values().length - 1)];
		this.scrollRow = Math.max(0, pageMemory.scrollRow);
	}

	public void update(DebugStatusPayload payload) {
		// The list rows carry per-entry buttons whose action flips with state - lock against unlock,
		// complete against already complete - so a push that moves one of those has to rebuild them.
		// Everything else is drawn straight from the payload and needs no widget work.
		boolean rowActionsChanged = status.unlockedFileMask() != payload.unlockedFileMask()
				|| status.milestoneMask() != payload.milestoneMask();
		this.status = payload;
		if (!payload.message().isEmpty()) this.statusMessage = payload.message();
		this.ticksSinceStatus = 0;
		if (rowActionsChanged && pending == null) rebuildWidgets();
	}

	@Override
	protected void init() {
		Layout layout = layout();
		calculateScrollLimits(layout);
		if (pending != null) {
			buildConfirmation(layout);
			return;
		}
		buildTabs(layout);
		buildRows(layout);
		buildRightColumn(layout);
		buildFooter(layout);
	}

	private void calculateScrollLimits(Layout layout) {
		maxScrollRow = Math.max(0, rowCount() - visibleRows(layout));
		scrollRow = Math.clamp(scrollRow, 0, maxScrollRow);
		// Computed before anything reads rightContentWidth, which narrows the buttons by the width of
		// a scrollbar only when this comes out above zero.
		maxRightScrollRow = 0;
		maxRightScrollRow = maxRightScrollRow(layout, rightRows());
		rightScrollRow = Math.clamp(rightScrollRow, 0, maxRightScrollRow);
	}

	private int maxRightScrollRow(Layout layout, List<RightRow> rows) {
		return DebugPanelLayout.maxScrollRow(layout.bodyBottom - layout.bodyTop,
				rows.stream().map(RightRow::height).toList());
	}

	private int rowCount() {
		return switch (tab) {
			case ANOMALIES -> ANOMALIES.size();
			case FILES -> FILES.size();
			case MILESTONES -> SurvivalMilestone.values().length;
		};
	}

	private int visibleRows(Layout layout) {
		return Math.max(1, (layout.bodyBottom - layout.listTop()) / ROW_HEIGHT);
	}

	private void buildTabs(Layout layout) {
		int width = (layout.listWidth() - GAP * (Tab.values().length - 1)) / Tab.values().length;
		for (int index = 0; index < Tab.values().length; index++) {
			Tab value = Tab.values()[index];
			Button button = Button.builder(Component.literal(value.label + " " + tabCount(value)), ignored -> {
				tab = value;
				scrollRow = 0;
				pending = null;
				rememberPage();
				rebuildWidgets();
			}).bounds(layout.listLeft + index * (width + GAP), layout.bodyTop, width, TAB_HEIGHT).build();
			button.active = value != tab;
			addRenderableWidget(button);
		}
	}

	private static int tabCount(Tab value) {
		return switch (value) {
			case ANOMALIES -> ANOMALIES.size();
			case FILES -> FILES.size();
			case MILESTONES -> SurvivalMilestone.values().length;
		};
	}

	private void buildRows(Layout layout) {
		int listTop = layout.listTop();
		int rows = visibleRows(layout);
		int buttonWidth = rowButtonWidth(layout);
		for (int row = 0; row < rows; row++) {
			int index = scrollRow + row;
			if (index >= rowCount()) break;
			int y = listTop + row * ROW_HEIGHT + 2;
			int rightEdge = layout.listRight - 8;
			switch (tab) {
				case ANOMALIES -> {
					String id = ANOMALIES.get(index);
					actionButton(rightEdge - buttonWidth * 2 - GAP, y, buttonWidth,
							new ActionSpec("触发", "anomaly", id, 0, false));
					actionButton(rightEdge - buttonWidth, y, buttonWidth,
							new ActionSpec("最强", "anomaly", id, 1, false));
				}
				case FILES -> {
					boolean unlocked = flag(status.unlockedFileMask(), index);
					actionButton(rightEdge - buttonWidth, y, buttonWidth,
							(unlocked ? FILE_LOCK : FILE_UNLOCK).forTarget(FILES.get(index)));
				}
				case MILESTONES -> {
					// The service only ever ORs a bit in, so a completed milestone has no button: an
					// "uncheck" that silently did nothing would be worse than no control at all.
					if (flag(status.milestoneMask(), index)) break;
					actionButton(rightEdge - buttonWidth, y, buttonWidth,
							MILESTONE_COMPLETE.forValue(index));
				}
			}
		}
	}

	/**
	 * Fills the right column from the top and stops at the first row that would not fit whole.
	 *
	 * <p>Written as a row list walked the same way the anomaly list is, rather than as a run of fixed
	 * offsets, because the panel is only 520 tall when the screen has room for it: at GUI scale 3 the
	 * body is about 250 pixels and the column wants around 330, and the fixed version drew the last
	 * groups outside the panel where nothing clipped them. Only whole rows are built, so a button is
	 * never half-drawn and never clickable outside the frame.</p>
	 */
	private void buildRightColumn(Layout layout) {
		List<RightRow> rows = rightRows();
		int contentWidth = rightContentWidth(layout);
		int y = layout.bodyTop;
		int limit = rightScrollRow + DebugPanelLayout.visibleRowCount(
				layout.bodyBottom - layout.bodyTop, rows.stream().map(RightRow::height).toList(), rightScrollRow);
		for (int index = rightScrollRow; index < limit; index++) {
			RightRow row = rows.get(index);
			List<Cell> cells = row.cells();
			if (!cells.isEmpty()) {
				int width = (contentWidth - GAP * (cells.size() - 1)) / cells.size();
				for (int column = 0; column < cells.size(); column++) {
					Cell cell = cells.get(column);
					addRenderableWidget(Button.builder(Component.literal(cell.label()),
							ignored -> cell.press().run())
							.bounds(layout.rightLeft + column * (width + GAP), y, width, BUTTON_HEIGHT).build());
				}
			}
			y += row.height();
		}
	}

	/**
	 * The whole right column, in order: the state block, then the action groups.
	 *
	 * <p>Rebuilt on every pass rather than cached because half of it is the live state block, and a
	 * cache that had to be invalidated on every push would be the same work with somewhere to go
	 * wrong. It is deterministic given the payload, so the build and render walks agree.</p>
	 */
	private List<RightRow> rightRows() {
		// Same argument as the HUD cache: render runs per frame, this screen does not pause the world,
		// and none of these rows can change faster than the once-a-second countdown inside them.
		int seconds = ticksSinceStatus / 20;
		int groupMask = DebugHudState.groupMask();
		if (cachedRightRows != null && cachedRowsFor == status && cachedRowsSeconds == seconds
				&& cachedRowsGroupMask == groupMask) {
			return cachedRightRows;
		}
		List<RightRow> rows = new java.util.ArrayList<>();
		rows.add(RightRow.heading("状态"));
		for (String line : statusLines()) rows.add(RightRow.line(line));
		rows.add(RightRow.label("主线"));
		rows.add(RightRow.buttons(cells(MAINLINE)));
		rows.add(RightRow.label("异象"));
		rows.add(RightRow.buttons(cells(ANOMALY_TOOLBAR)));
		rows.add(RightRow.label("崩坏等级  " + status.decayStage()
				+ (status.decayAuto() ? "（自动）" : "（手动）")));
		rows.add(RightRow.buttons(cells(DECAY_STAGES)));
		rows.add(RightRow.label("测试"));
		rows.add(RightRow.buttons(cells(TESTS)));
		rows.add(RightRow.label("文件"));
		rows.add(RightRow.buttons(cells(FILE_ACTIONS)));
		rows.add(RightRow.label("HUD 显示分组"));
		DebugHudState.Group[] groups = DebugHudState.Group.values();
		for (int index = 0; index < groups.length; index += 2) {
			rows.add(RightRow.buttons(List.of(toggleCell(groups[index]), toggleCell(groups[index + 1]))));
		}
		cachedRowsFor = status;
		cachedRowsSeconds = seconds;
		cachedRowsGroupMask = groupMask;
		cachedRightRows = List.copyOf(rows);
		return cachedRightRows;
	}

	private List<Cell> cells(List<ActionSpec> actions) {
		return actions.stream().map(this::cell).toList();
	}

	private Cell cell(ActionSpec action) {
		return new Cell(action.label, () -> {
			if (action.confirm) {
				pending = new Pending(action.label, action.action, action.target, action.value);
				rebuildWidgets();
			} else send(action.action, action.target, action.value);
		});
	}

	private Cell toggleCell(DebugHudState.Group group) {
		return new Cell((DebugHudState.enabled(group) ? "☑ " : "☐ ") + group.label(), () -> {
			DebugHudState.toggle(group);
			rebuildWidgets();
		});
	}

	/** The scrollbar takes its width out of the buttons only when there is something to scroll. */
	private int rightContentWidth(Layout layout) {
		return layout.rightWidth() - (maxRightScrollRow > 0 ? RIGHT_SCROLLBAR_WIDTH : 0);
	}

	private void buildFooter(Layout layout) {
		int y = layout.footerTop + 5;
		addRenderableWidget(Button.builder(Component.literal("刷新"), ignored -> send("refresh", "", 0))
				.bounds(layout.right() - 128, y, 56, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("关闭"), ignored -> onClose())
				.bounds(layout.right() - 66, y, 56, BUTTON_HEIGHT).build());
	}

	private void actionButton(int x, int y, int width, ActionSpec action) {
		addRenderableWidget(Button.builder(Component.literal(action.label), ignored -> {
			if (action.confirm) {
				pending = new Pending(action.label, action.action, action.target, action.value);
				rebuildWidgets();
			} else send(action.action, action.target, action.value);
		}).bounds(x, y, width, BUTTON_HEIGHT).build());
	}

	private void buildConfirmation(Layout layout) {
		Modal modal = modal(layout);
		int buttonY = modal.bottom() - 28;
		int half = (modal.width - 42) / 2;
		addRenderableWidget(Button.builder(Component.literal("确认执行"), ignored -> {
			Pending action = pending;
			pending = null;
			rebuildWidgets();
			send(action.action, action.target, action.value);
		}).bounds(modal.left + 18, buttonY, half, BUTTON_HEIGHT).build());
		addRenderableWidget(Button.builder(Component.literal("取消"), ignored -> cancelConfirmation())
				.bounds(modal.left + 24 + half, buttonY, half, BUTTON_HEIGHT).build());
	}

	private void cancelConfirmation() {
		pending = null;
		rebuildWidgets();
	}

	private void send(String action, String target, int value) {
		if (ClientPlayNetworking.canSend(DebugActionPayload.TYPE)) {
			if (action.equals("anomaly")) DebugPanelClient.expectAnomalyResponse(target);
			if (action.equals("pursuit_test")) DebugPanelClient.expectPursuitResponse();
			if (action.equals("boss_test")) DebugPanelClient.expectBossResponse();
			ClientPlayNetworking.send(new DebugActionPayload(action, target, value));
		}
	}

	@Override
	public void tick() {
		super.tick();
		// No poll. The server pushes on its own cadence while debug is enabled, because the HUD has
		// to keep reading after this screen closes; asking again from here would only duplicate it.
		ticksSinceStatus++;
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		Layout layout = layout();
		graphics.fill(0, 0, width, height, OVERLAY);
		graphics.fill(layout.left, layout.top, layout.right(), layout.bottom(), PANEL);
		graphics.fill(layout.left, layout.top, layout.right(), layout.top + HEADER_HEIGHT, CHROME);
		graphics.fill(layout.left, layout.footerTop, layout.right(), layout.bottom(), CHROME);
		graphics.renderOutline(layout.left, layout.top, layout.panelWidth, layout.panelHeight, ACCENT);
		int divider = layout.rightLeft - PAD / 2;
		graphics.fill(divider, layout.bodyTop, divider + 1, layout.bodyBottom, BORDER);

		renderHeader(graphics, layout);
		renderList(graphics, layout);
		renderRightColumn(graphics, layout);
		renderFooter(graphics, layout);
		if (pending != null) renderConfirmation(graphics, layout);
		super.render(graphics, mouseX, mouseY, partialTick);
	}

	private void renderHeader(GuiGraphics graphics, Layout layout) {
		graphics.drawString(font, title, layout.left + 12, layout.top + 6, ACCENT_BRIGHT, false);
		boolean live = ticksSinceStatus <= LIVE_TIMEOUT_TICKS;
		graphics.drawString(font, Component.literal("仅供开发测试 · " + (live ? "实时同步" : "同步等待")),
				layout.left + 12, layout.top + 18, live ? MUTED : DANGER, false);
		String context = fit("玩家：" + status.playerName(), Math.max(40, layout.rightWidth()));
		graphics.drawString(font, Component.literal(context), layout.right() - 12 - font.width(context),
				layout.top + 11, TEXT, false);
	}

	private void renderList(GuiGraphics graphics, Layout layout) {
		int listTop = layout.listTop();
		int listHeight = Math.max(0, layout.bodyBottom - listTop);
		graphics.fill(layout.listLeft, listTop, layout.listRight, layout.bodyBottom, WELL);
		graphics.renderOutline(layout.listLeft, listTop, layout.listWidth(), listHeight, BORDER);
		int rows = visibleRows(layout);
		int buttonWidth = rowButtonWidth(layout);
		int buttonSpace = (tab == Tab.ANOMALIES ? buttonWidth * 2 + GAP : buttonWidth) + 16;
		for (int row = 0; row < rows; row++) {
			int index = scrollRow + row;
			if (index >= rowCount()) break;
			int y = listTop + row * ROW_HEIGHT;
			boolean highlight = tab == Tab.ANOMALIES && ANOMALIES.get(index).equals(status.activeAnomaly());
			if (highlight) graphics.fill(layout.listLeft + 1, y + 1, layout.listRight - 7,
					y + ROW_HEIGHT - 1, ROW_ACTIVE);
			else if ((index & 1) == 0) graphics.fill(layout.listLeft + 1, y + 1, layout.listRight - 7,
					y + ROW_HEIGHT - 1, ROW);
			switch (tab) {
				case ANOMALIES -> renderAnomalyRow(graphics, layout, index, y, buttonSpace, highlight);
				case FILES -> renderFileRow(graphics, layout, index, y, buttonSpace);
				case MILESTONES -> renderMilestoneRow(graphics, layout, index, y, buttonSpace);
			}
		}
		renderScrollbar(graphics, layout.listRight - 5, listTop + 2, Math.max(0, listHeight - 4),
				rows, rowCount(), scrollRow, maxScrollRow);
	}

	private void renderAnomalyRow(GuiGraphics graphics, Layout layout, int index, int y,
			int buttonSpace, boolean active) {
		String id = ANOMALIES.get(index);
		if (AnomalyCatalog.require(id).destructive())
			graphics.fill(layout.listLeft + 5, y + 7, layout.listLeft + 8, y + 15, DANGER);
		boolean candidate = flag(status.candidateMask(), index);
		boolean unseen = flag(status.unseenMask(), index);
		// The marker is the whole reason the pool is on the wire: without it this list answers "what
		// exists", and the question actually being asked in front of it is "why does it keep picking
		// these". A star is one the player has never met, which outranks the rest on every draw.
		String marker = candidate ? unseen ? "★" : "•" : " ";
		graphics.drawString(font, Component.literal(marker), layout.listLeft + 13, y + 7,
				unseen ? ACCENT_BRIGHT : ACCENT, false);
		int nameWidth = Math.max(20, layout.listWidth() - buttonSpace - 26);
		graphics.drawString(font, Component.literal(fit(DebugNames.anomaly(id), nameWidth)),
				layout.listLeft + 24, y + 7, active ? ACCENT_BRIGHT : TEXT, false);
	}

	private void renderFileRow(GuiGraphics graphics, Layout layout, int index, int y, int buttonSpace) {
		String state = fileState(index);
		int stateColor = fileStateColor(index);
		graphics.fill(layout.listLeft + 5, y + 7, layout.listLeft + 8, y + 15, stateColor);
		int stateWidth = 44;
		int nameWidth = Math.max(20, layout.listWidth() - buttonSpace - stateWidth - 22);
		graphics.drawString(font, Component.literal(fit(DebugNames.file(FILES.get(index)), nameWidth)),
				layout.listLeft + 14, y + 7, TEXT, false);
		graphics.drawString(font, Component.literal(state),
				layout.listRight - buttonSpace - stateWidth + 8, y + 7, stateColor, false);
	}

	private void renderMilestoneRow(GuiGraphics graphics, Layout layout, int index, int y, int buttonSpace) {
		boolean done = flag(status.milestoneMask(), index);
		graphics.fill(layout.listLeft + 5, y + 7, layout.listLeft + 8, y + 15, done ? ACCENT : MUTED);
		int nameWidth = Math.max(20, layout.listWidth() - buttonSpace - 22);
		graphics.drawString(font, Component.literal(fit(DebugNames.milestone(index), nameWidth)),
				layout.listLeft + 14, y + 7, done ? TEXT : MUTED, false);
		if (done) graphics.drawString(font, Component.literal("已完成"),
				layout.listRight - buttonSpace + 8, y + 7, ACCENT, false);
	}

	private void renderRightColumn(GuiGraphics graphics, Layout layout) {
		List<RightRow> rows = rightRows();
		int contentWidth = rightContentWidth(layout);
		int y = layout.bodyTop;
		int shown = DebugPanelLayout.visibleRowCount(layout.bodyBottom - layout.bodyTop,
				rows.stream().map(RightRow::height).toList(), rightScrollRow);
		for (int index = rightScrollRow; index < rightScrollRow + shown; index++) {
			RightRow row = rows.get(index);
			if (row.cells().isEmpty()) {
				graphics.drawString(font, Component.literal(fit(row.text(), contentWidth)),
						layout.rightLeft, y, row.color(), false);
			}
			y += row.height();
		}
		renderScrollbar(graphics, layout.rightRight - 4, layout.bodyTop,
				layout.bodyBottom - layout.bodyTop, shown, rows.size(), rightScrollRow, maxRightScrollRow);
	}

	/**
	 * The whole state block, written once.
	 *
	 * <p>Formatted by {@link DebugReadout} rather than here, because the HUD shows the same numbers
	 * and two formatters is two places for a unit or a label to drift apart.</p>
	 */
	private List<String> statusLines() {
		return DebugReadout.panelLines(status, dimensionMode(), ticksSinceStatus / 20);
	}

	private void renderFooter(GuiGraphics graphics, Layout layout) {
		String message = fit(statusMessage, Math.max(20, layout.panelWidth - 150));
		graphics.drawString(font, Component.literal(message), layout.left + 12, layout.footerTop + 10,
				statusColor(statusMessage), false);
	}

	private void renderConfirmation(GuiGraphics graphics, Layout layout) {
		graphics.fill(layout.left + 1, layout.top + HEADER_HEIGHT, layout.right() - 1,
				layout.footerTop, 0xD905090B);
		Modal modal = modal(layout);
		graphics.fill(modal.left, modal.top, modal.right(), modal.bottom(), PANEL);
		graphics.renderOutline(modal.left, modal.top, modal.width, modal.height, DANGER);
		graphics.drawCenteredString(font, Component.literal("不可撤销"),
				modal.left + modal.width / 2, modal.top + 12, DANGER);
		graphics.drawWordWrap(font, Component.literal("“" + pending.label + "”没有恢复路径，执行后无法退回。"),
				modal.left + 16, modal.top + 31, modal.width - 32, TEXT, false);
		graphics.drawCenteredString(font, Component.literal("Esc 取消"),
				modal.left + modal.width / 2, modal.bottom() - 41, MUTED);
	}

	private void renderScrollbar(GuiGraphics graphics, int x, int y, int height, int visible, int total,
			int offset, int maxOffset) {
		if (maxOffset <= 0 || height <= 0 || total <= 0) return;
		graphics.fill(x, y, x + 2, y + height, BORDER);
		int thumbHeight = Math.max(12, height * visible / total);
		int thumbY = y + Math.max(0, height - thumbHeight) * offset / maxOffset;
		graphics.fill(x - 1, thumbY, x + 3, thumbY + thumbHeight, ACCENT);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (pending != null || verticalAmount == 0.0D)
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		Layout layout = layout();
		int delta = verticalAmount > 0.0D ? -1 : 1;
		if (inside(mouseX, mouseY, layout.rightLeft, layout.bodyTop, layout.rightRight, layout.bodyBottom)
				&& maxRightScrollRow > 0) {
			rightScrollRow = Math.clamp(rightScrollRow + delta, 0, maxRightScrollRow);
			rebuildWidgets();
			return true;
		}
		if (!inside(mouseX, mouseY, layout.listLeft, layout.listTop(), layout.listRight, layout.bodyBottom)
				|| maxScrollRow <= 0)
			return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
		scrollRow = Math.clamp(scrollRow + delta, 0, maxScrollRow);
		rememberPage();
		rebuildWidgets();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && pending != null) {
			cancelConfirmation();
			return true;
		}
		return super.keyPressed(event);
	}

	private Layout layout() {
		int panelWidth = Math.max(1, Math.min(MAX_PANEL_WIDTH, width - SCREEN_MARGIN * 2));
		int panelHeight = Math.max(1, Math.min(MAX_PANEL_HEIGHT, height - SCREEN_MARGIN * 2));
		int left = (width - panelWidth) / 2;
		int top = (height - panelHeight) / 2;
		int rightWidth = Math.clamp(panelWidth * 2 / 5, MIN_RIGHT_WIDTH, MAX_RIGHT_WIDTH);
		// The minimum is a preference, not a promise: on a window too narrow to honour both, the list
		// keeps its width and the right column ellipsizes, because a 25-pixel list is not a list.
		rightWidth = Math.max(0, Math.min(rightWidth, panelWidth - PAD * 2 - PAD / 2 - MIN_LIST_WIDTH));
		int footerTop = top + panelHeight - FOOTER_HEIGHT;
		int rightRight = left + panelWidth - PAD;
		int rightLeft = rightRight - rightWidth;
		return new Layout(left, top, panelWidth, panelHeight, footerTop,
				left + PAD, rightLeft - PAD - PAD / 2, rightLeft, rightRight,
				top + HEADER_HEIGHT + PAD, footerTop - PAD);
	}

	private static int rowButtonWidth(Layout layout) {
		return Math.clamp((layout.listWidth() - 40) / 5, 40, 54);
	}

	private Modal modal(Layout layout) {
		int modalWidth = Math.min(360, layout.panelWidth - 32);
		int modalHeight = Math.min(120, layout.panelHeight - 48);
		return new Modal(layout.left + (layout.panelWidth - modalWidth) / 2,
				layout.top + (layout.panelHeight - modalHeight) / 2, modalWidth, modalHeight);
	}

	/**
	 * Which schedule this world runs, asked locally rather than over the wire.
	 *
	 * <p>The dimension is something the client already knows for certain, and the rule that reads it
	 * is a pure common class, so reporting it costs no protocol field and cannot disagree with the
	 * answer the server would give. In an excluded dimension the countdown beside this is the frozen
	 * remainder - what will be left when the player is back somewhere that runs a schedule - rather
	 * than a deadline that is quietly never going to arrive.
	 */
	private String dimensionMode() {
		if (minecraft == null || minecraft.level == null) return "维度未知";
		return switch (AnomalyDimensionPolicy.mode(minecraft.level.dimension().identifier().toString())) {
			case NORMAL -> "常规";
			case PRESSURE -> "加压";
			case EXCLUDED -> "冻结";
		};
	}

	private String fileState(int index) {
		if (!flag(status.discoveredFileMask(), index)) return "未发现";
		if (!flag(status.unlockedFileMask(), index)) return "待解锁";
		return flag(status.readFileMask(), index) ? "已读" : "未读";
	}

	private int fileStateColor(int index) {
		if (!flag(status.discoveredFileMask(), index)) return MUTED;
		if (!flag(status.unlockedFileMask(), index)) return DANGER;
		return flag(status.readFileMask(), index) ? TEXT : ACCENT_BRIGHT;
	}

	private static boolean flag(long mask, int index) {
		return index >= 0 && index < Long.SIZE && (mask & 1L << index) != 0L;
	}

	private String fit(String value, int maxWidth) {
		if (maxWidth <= 0) return "";
		if (font.width(value) <= maxWidth) return value;
		String result = value;
		while (!result.isEmpty() && font.width(result + "…") > maxWidth)
			result = result.substring(0, result.length() - 1);
		return result + "…";
	}

	private static int statusColor(String message) {
		return message.contains("拒绝") || message.contains("失败") || message.contains("未知")
				|| message.contains("没有") || message.contains("请先") ? DANGER : MUTED;
	}

	private static boolean inside(double mouseX, double mouseY, int left, int top, int right, int bottom) {
		return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		rememberPage();
		super.onClose();
	}

	private void rememberPage() {
		pageMemory = new PageMemory(tab.ordinal(), scrollRow);
	}

	public int tabCountForTesting() { return Tab.values().length; }
	public int anomalyCountForTesting() { return ANOMALIES.size(); }
	public int fileCountForTesting() { return FILES.size(); }
	public int milestoneCountForTesting() { return SurvivalMilestone.values().length; }
	public int rememberedTabForTesting() { return pageMemory.tabIndex; }
	public int rememberedScrollForTesting() { return pageMemory.scrollRow; }
	public void triggerAnomalyForTesting(String id) { send("anomaly", id, 0); }
	public static int anomalyTabIndexForTesting() { return Tab.ANOMALIES.ordinal(); }
	public static int fileTabIndexForTesting() { return Tab.FILES.ordinal(); }
	public static int milestoneTabIndexForTesting() { return Tab.MILESTONES.ordinal(); }
	public static List<String> confirmedActionsForTesting() { return CONFIRMED_ACTIONS; }
	public static int pursuitSlotsForTesting() { return PursuitSlotManager.MAX_ACTIVE_PURSUITS; }
	public static int unrenderedSlotsForTesting() { return UnrenderedAnchorPolicy.MAX_CONCURRENT; }
	public List<String> toolbarActionsForTesting() {
		return ANOMALY_TOOLBAR.stream().map(ActionSpec::action).toList();
	}
	public List<String> statusLinesForTesting() { return statusLines(); }
	public void selectTabForTesting(int index) {
		tab = Tab.values()[Math.clamp(index, 0, Tab.values().length - 1)];
		scrollRow = 0;
		pending = null;
		rememberPage();
		rebuildWidgets();
	}
	public String statusMessageForTesting() { return statusMessage; }
	/**
	 * The first widget that sits outside the panel frame, or an empty string.
	 *
	 * <p>The invariant a screenshot at one resolution cannot check: nothing this screen builds may be
	 * drawn or clicked outside its own frame, at any window size.</p>
	 */
	public String firstWidgetOutsidePanelForTesting() {
		Layout layout = layout();
		for (var child : children()) {
			if (!(child instanceof net.minecraft.client.gui.components.AbstractWidget widget)) continue;
			if (widget.getX() >= layout.left && widget.getY() >= layout.top
					&& widget.getX() + widget.getWidth() <= layout.right()
					&& widget.getY() + widget.getHeight() <= layout.bottom()) continue;
			return widget.getMessage().getString() + " @ " + widget.getX() + "," + widget.getY()
					+ " " + widget.getWidth() + "x" + widget.getHeight()
					+ " outside " + layout.left + "," + layout.top
					+ " " + layout.panelWidth + "x" + layout.panelHeight;
		}
		return "";
	}

	private enum Tab {
		ANOMALIES("异象"), FILES("文件"), MILESTONES("节点");
		private final String label;
		Tab(String label) { this.label = label; }
	}

	/**
	 * One button in the right column, already bound to what pressing it does.
	 *
	 * <p>It exists so the column can hold action buttons and HUD checkboxes in the same row list: the
	 * checkboxes send nothing over the wire and have no action id, so they are not {@link ActionSpec}
	 * and never should be - the contract test reads those ids out of the source to prove each one
	 * reaches a branch the service handles.</p>
	 */
	private record Cell(String label, Runnable press) { }

	/** A right-column row: a heading, a state line, or a row of buttons. Height includes its gap. */
	private record RightRow(String text, int color, List<Cell> cells, int height) {
		private static RightRow heading(String value) {
			return new RightRow(value, ACCENT_BRIGHT, List.of(), GROUP_LABEL + 1);
		}

		private static RightRow label(String value) {
			return new RightRow(value, MUTED, List.of(), GROUP_LABEL);
		}

		private static RightRow line(String value) {
			return new RightRow(value, TEXT, List.of(), LINE);
		}

		private static RightRow buttons(List<Cell> value) {
			return new RightRow("", 0, value, BUTTON_HEIGHT + GROUP_GAP);
		}
	}

	private record ActionSpec(String label, String action, String target, int value, boolean confirm) {
		private ActionSpec forTarget(String value) {
			return new ActionSpec(label, action, value, this.value, confirm);
		}

		private ActionSpec forValue(int replacement) {
			return new ActionSpec(label, action, target, replacement, confirm);
		}
	}
	private record Pending(String label, String action, String target, int value) { }
	private record PageMemory(int tabIndex, int scrollRow) { }
	private record Modal(int left, int top, int width, int height) {
		private int right() { return left + width; }
		private int bottom() { return top + height; }
	}
	private record Layout(int left, int top, int panelWidth, int panelHeight, int footerTop,
			int listLeft, int listRight, int rightLeft, int rightRight, int bodyTop, int bodyBottom) {
		private int right() { return left + panelWidth; }
		private int bottom() { return top + panelHeight; }
		private int listWidth() { return listRight - listLeft; }
		private int rightWidth() { return rightRight - rightLeft; }
		private int listTop() { return bodyTop + TAB_HEIGHT + 6; }
	}
}
