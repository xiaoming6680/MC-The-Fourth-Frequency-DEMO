package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.networking.TerminalClosedPayload;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import com.xm.thefourthfrequency.networking.TerminalLogEntryPayload;
import com.xm.thefourthfrequency.networking.TerminalFilePayload;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.narrative.ArchiveUnlockService;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.FragmentInvestigationService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TerminalRuntimeService {
	private static final int LIVE_SYNC_TICKS = 20;
	private static final int NAVIGATION_SYNC_TICKS = 4;
	/** How long the dial must be held inside the window before the hidden file is granted. */
	static final int RECEIVER_LOCK_TICKS = 20;
	/** Snapshot cadence while a lock is counting up, so the readout can actually be watched. */
	private static final int RECEIVER_LOCK_SYNC_TICKS = 2;
	private static final Map<UUID, ViewState> OPEN_VIEWS = new LinkedHashMap<>();
	/**
	 * How many players' remembered views are kept.
	 *
	 * <p>These three maps are convenience, not state: they restore the tab and dial a player left the
	 * screen on. They are keyed per player and were only ever cleared on {@code SERVER_STOPPED},
	 * which on a public server that never restarts means one entry per person who has ever opened a
	 * terminal, forever. Clearing them on disconnect was the other option and was rejected - it
	 * throws the convenience away for exactly the player most likely to want it, somebody who
	 * relogged - so the answer is a bound instead. Evicting the least recently used entry costs the
	 * two-hundred-and-fifty-seventh least active player their remembered tab and nobody else
	 * anything.
	 */
	private static final int REMEMBERED_VIEW_LIMIT = 256;
	private static final Map<UUID, Integer> REMEMBERED_MODES = rememberedViews();
	/**
	 * The tab the player was last looking at. The wire mode only distinguishes signal from files, so
	 * it cannot tell home from tools from records; reopening on the remembered tab needs the page.
	 */
	private static final Map<UUID, Integer> REMEMBERED_PAGES = rememberedViews();
	private static final Map<UUID, Integer> REMEMBERED_TUNING = rememberedViews();

	/** Access-ordered and bounded, so reading a player's remembered view also keeps it alive. */
	private static Map<UUID, Integer> rememberedViews() {
		return new LinkedHashMap<>(16, 0.75F, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
				return size() > REMEMBERED_VIEW_LIMIT;
			}
		};
	}
	private static boolean initialized;

	private TerminalRuntimeService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(TerminalRuntimeService::tick);
		// Dropping the connection ends an unfinished profile, exactly as closing the terminal does.
		// The walkthrough is one-shot, and without this the one path that skips CLOSE would be the one
		// path that replays it: a player who pulled the plug on question two would be asked all five
		// again on the next login, which is the whole property the latch exists to hold.
		//
		// The cost is deliberate and was chosen over the alternative. Latching on entry instead would
		// be airtight, but it would throw away all five answers for a disconnect during the first
		// question rather than keeping the ones already given.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			latchProfileIfUnfinished(handler.player);
			// The refusal cooldown is keyed per player and would otherwise keep an entry for everyone
			// who has ever tried to drop a bound terminal on this server.
			TerminalNoticeService.forget(handler.player.getUUID());
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			OPEN_VIEWS.clear();
			REMEMBERED_MODES.clear();
			REMEMBERED_PAGES.clear();
			REMEMBERED_TUNING.clear();
		});
	}

	public static void open(ServerPlayer player, int handId) {
		InteractionHand hand = decodeHand(handId);
		if (hand == null || !validHeldTerminal(player, hand)) {
			ServerPlayNetworking.send(player, new TerminalClosedPayload(TerminalClosedPayload.INVALID_ITEM));
			return;
		}
		int mode = REMEMBERED_MODES.getOrDefault(player.getUUID(), TerminalControlPolicy.Mode.SIGNAL.ordinal());
		int tuning = REMEMBERED_TUNING.getOrDefault(player.getUUID(), TerminalControlPolicy.DEFAULT_TUNING);
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		boolean openRecords = record != null
				&& record.getBooleanOr(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, false);
		if (openRecords) data.updateTerminalRecord(player.getUUID(),
				tag -> tag.putBoolean(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, false));
		TerminalPage remembered = REMEMBERED_PAGES.containsKey(player.getUUID())
				? TerminalPage.fromIndex(REMEMBERED_PAGES.get(player.getUUID()))
				: TerminalPage.initialPage(mode);
		TerminalPage opening = openRecords ? TerminalPage.RECORDS : remembered;
		int initialPage = opening.ordinal();
		int serverTick = player.level().getServer().getTickCount();
		// The mode follows the tab rather than its own memory, so a restored view never opens on one
		// page while the wire still claims the other.
		ViewState view = new ViewState(hand, TerminalControlPolicy.mode(opening.wireMode()), initialPage,
				TerminalControlPolicy.tuning(tuning), 0L, 0L, "", serverTick, TerminalToolService.NO_TOOL);
		view.forcedPageUntouched = openRecords;
		view.pageBeforeForcedOpen = remembered.ordinal();
		OPEN_VIEWS.put(player.getUUID(), view);
		sendSnapshot(player, view);
		sendNavigation(player);
	}

	public static void control(ServerPlayer player, int action, int value) {
		ViewState view = OPEN_VIEWS.get(player.getUUID());
		if (view == null || !validHeldTerminal(player, view.hand)) return;
		switch (action) {
			case TerminalControlPayload.MODE -> {
				if (!TerminalControlPolicy.validMode(value)) return;
				view.mode = value;
				REMEMBERED_MODES.put(player.getUUID(), view.mode);
			}
			case TerminalControlPayload.TUNE -> {
				if (!TerminalControlPolicy.validTuning(value)
						|| !receiverAvailable(player)) return;
				applyTuning(player, view, value);
			}
			case TerminalControlPayload.REFRESH -> { if (value != 0) return; }
			// No client sends this any more - the expandable signal-card feed that offered "set as
			// bearing" was removed once it turned out nothing ever drew it. The handler stays so an
			// older client on a newer server is still answered rather than silently ignored.
			case TerminalControlPayload.SELECT_FRAGMENT_TARGET -> {
				if (value < 0 || value >= 12) return;
				if (!FragmentInvestigationService.selectCandidate(player, value)) return;
			}
			case TerminalControlPayload.SELECT_TOOL -> {
				if (!TerminalToolService.selectTool(player, value)) return;
				view.selectedTool = value;
			}
			case TerminalControlPayload.SELECT_STRUCTURE_TARGET -> {
				if (!com.xm.thefourthfrequency.world.StructureNavigationService.selectTarget(player, value)) return;
			}
			case TerminalControlPayload.SELECT_NEAREST_UNSTABLE -> {
				if (value != 0 || !FragmentInvestigationService.selectNearestCandidate(player)) return;
			}
			case TerminalControlPayload.START_GUIDANCE -> {
				if (!TerminalToolService.startGuidance(player, value)) {
					sendSnapshot(player, view);
					return;
				}
			}
			case TerminalControlPayload.STOP_GUIDANCE -> {
				if (!TerminalToolService.stopGuidance(player, value)) return;
			}
			case TerminalControlPayload.SELECT_RESOURCE -> { return; }
			case TerminalControlPayload.REQUEST_RESCAN -> {
				if (value != 0 || !TerminalToolService.requestRescan(player)) return;
			}
			case TerminalControlPayload.SET_HOME -> { return; }
			case TerminalControlPayload.DISMISS_NAVIGATION_COMPLETION -> { return; }
			case TerminalControlPayload.READ_TRUTH_FILE -> {
				if (value != 0 || !markTruthRead(player)) return;
			}
			case TerminalControlPayload.MARK_RECORDS_READ -> {
				if (value != 0 || !markRecordsRead(player)) return;
			}
			case TerminalControlPayload.MARK_FILES_SEEN -> {
				if (value != 0 || !markFilesSeen(player)) return;
			}
			case TerminalControlPayload.READ_HIDDEN_FILE -> {
				if (!markHiddenFileRead(player, value)) return;
			}
			case TerminalControlPayload.VISIT_PAGE -> {
				if (!TerminalTaskService.visitPage(player, value)) return;
				view.page = TerminalPage.fromIndex(value).ordinal();
				// The client announces the forced RECORDS page as soon as it opens, to mark the log
				// read. That announcement is the warning's, not the player's, so it does not count as
				// leaving the page the terminal should reopen on.
				if (view.page != TerminalPage.RECORDS.ordinal()) view.forcedPageUntouched = false;
				REMEMBERED_PAGES.put(player.getUUID(), view.page);
			}
			// Kept for clients built before rewards became automatic. It delivers nothing on its own -
			// claim() only runs the catch-up pass - so there is no outcome here worth a notice: the
			// reward, if one was still owed, announces itself through the usual completion line.
			case TerminalControlPayload.CLAIM_TASK_REWARD -> TerminalTaskService.claim(player, value);
			case TerminalControlPayload.ANSWER_PROFILE -> {
				if (!recordProfileAnswer(player, value)) return;
			}
			case TerminalControlPayload.REPORT_PREVIOUS_RUN -> {
				if (!recordPreviousRun(player, value != 0)) return;
			}
			case TerminalControlPayload.CLOSE -> {
				if (value != TerminalControlPayload.CLOSE_SHOWN
						&& value != TerminalControlPayload.CLOSE_NEVER_SHOWN) return;
				// Closing part-way through the profile ends it. The exit is held while the profile is
				// on screen, so the only ways to get here are the damage failsafe and a forced close -
				// both of which mean the player stopped answering, and the walkthrough does not ask
				// twice. The gaps stay gaps; the terminal is able to say the file is incomplete.
				//
				// CLOSE_NEVER_SHOWN is the one case where that reasoning does not hold: the client
				// abandoned an opening that never became a screen, because something the player
				// opened covered it while it was rising. Latching there would burn a one-shot record
				// for somebody who opened their inventory in the first second of the run.
				if (value == TerminalControlPayload.CLOSE_SHOWN) latchProfileIfUnfinished(player);
				remember(player.getUUID(), view);
				OPEN_VIEWS.remove(player.getUUID());
				return;
			}
			default -> { return; }
		}
		sendSnapshot(player, view);
		sendNavigation(player);
	}

	/**
	 * Records one profile answer and moves to the next question.
	 *
	 * <p>The question index is the server's, never the client's. The packet carries only which option
	 * was chosen, so there is no way to answer ahead, answer the same question twice, or reach back
	 * and rewrite one that is already down. Any legal option is a legal answer, so none of this is
	 * defending against a cheat - it is defending against a one-shot record that cannot be corrected.
	 */
	private static boolean recordProfileAnswer(ServerPlayer player, int optionIndex) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		if (TerminalData.profileTaken(record)) return false;
		if (record.getBooleanOr(TerminalData.ONBOARDING_DONE, false)) return false;
		int question = record.getIntOr(TerminalData.PROFILE_QUESTION, 0);
		if (!TerminalProfileQuestionnaire.validAnswer(question, optionIndex)) return false;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			int[] answers = TerminalData.profileAnswers(tag);
			answers[question] = optionIndex;
			tag.putIntArray(TerminalData.PROFILE_ANSWERS, answers);
			int next = question + 1;
			tag.putInt(TerminalData.PROFILE_QUESTION, next);
			if (next >= TerminalProfileQuestionnaire.questionCount()) {
				tag.putBoolean(TerminalData.PROFILE_TAKEN, true);
			}
		});
		synchronizeProjection(player, data);
		return true;
	}

	/**
	 * Ends an unfinished profile without filling in what was never answered.
	 *
	 * <p>Deliberately does not supply defaults. An unanswered question stays
	 * {@code TerminalProfileQuestionnaire.UNANSWERED}, because a default here would be exactly the
	 * failure the safety rules name: a value that is wrong but entirely plausible, written into a
	 * record the player has no way to revisit.
	 */
	private static void latchProfileIfUnfinished(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || TerminalData.profileTaken(record)) return;
		if (record.getBooleanOr(TerminalData.ONBOARDING_DONE, false)) return;
		data.updateTerminalRecord(player.getUUID(), tag -> tag.putBoolean(TerminalData.PROFILE_TAKEN, true));
		synchronizeProjection(player, data);
	}

	/**
	 * Files, or removes, the fragment a previous playthrough left behind.
	 *
	 * <p>Follows what the client says rather than latching once. A player who pressed {@code F8} has
	 * had that record erased from their machine on purpose, and leaving the file standing in a world
	 * they carry on playing would be the mod holding on to something the reset was supposed to have
	 * let go of.
	 *
	 * <p>Discovered and unlocked in the same breath, because there is no investigation to do: it was
	 * in the terminal when they were handed it. Only the existence is stored - the body is composed on
	 * the client from its own config, and nothing here is counted by
	 * {@code HiddenFilePolicy.FILE_IDS}.
	 */
	private static boolean recordPreviousRun(ServerPlayer player, boolean present) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		boolean known = TerminalFileState.discovered(record, HiddenFilePolicy.RECOVERED_FILE_ID);
		if (known == present) return false;
		long now = player.level().getGameTime();
		long dayTime = player.level().getDayTime() % 24_000L;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			if (present) {
				TerminalFileState.discover(tag, HiddenFilePolicy.RECOVERED_FILE_ID, now, dayTime, true);
			} else {
				TerminalFileState.remove(tag, HiddenFilePolicy.RECOVERED_FILE_ID);
			}
		});
		synchronizeProjection(player, data);
		return true;
	}

	private static boolean markTruthRead(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		boolean readable = TerminalFileState.states(record).stream().anyMatch(file ->
				file.id().equals("encrypted_witness_file") && file.unlocked());
		if (!readable) return false;
		if (!record.getBooleanOr(TerminalData.TRUTH_READ, false)) {
			data.updateTerminalRecord(player.getUUID(), tag -> tag.putBoolean(TerminalData.TRUTH_READ, true));
			synchronizeProjection(player, data);
		}
		return true;
	}

	private static boolean markRecordsRead(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		if (TerminalSignalLog.unreadCount(record) == 0) return true;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			TerminalSignalLog.markAllRead(tag);
			tag.putBoolean(TerminalData.UNREAD_ALERT_ACTIVE,
					TerminalFileState.unreadCount(tag) > 0
							|| tag.getBooleanOr(TerminalData.NAVIGATION_COMPLETION_UNREAD, false));
		});
		synchronizeAttentionProjection(player, data);
		return true;
	}

	private static boolean markFilesSeen(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		if (TerminalFileState.unreadCount(record) == 0) return true;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			TerminalFileState.markAllSeen(tag);
			tag.putBoolean(TerminalData.UNREAD_ALERT_ACTIVE,
					TerminalSignalLog.unreadCount(tag) > 0
							|| tag.getBooleanOr(TerminalData.NAVIGATION_COMPLETION_UNREAD, false));
		});
		synchronizeAttentionProjection(player, data);
		return true;
	}

	private static boolean markHiddenFileRead(ServerPlayer player, int index) {
		String id = HiddenFilePolicy.fileId(index);
		if (id.isEmpty()) return false;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !TerminalFileState.discovered(record, id)
				|| !TerminalFileState.unlocked(record, id)) return false;
		long now = player.level().getGameTime();
		long dayTime = player.level().getDayTime();
		boolean[] completed = {false};
		data.updateTerminalRecord(player.getUUID(), tag -> {
			TerminalFileState.markRead(tag, id, now, dayTime);
			completed[0] = HiddenFilePolicy.allDiscovered(tag) && HiddenFilePolicy.allRead(tag)
					&& !TerminalFileState.unlocked(tag, HiddenFilePolicy.COMPLETE_FILE_ID);
		});
		if (completed[0]) ArchiveUnlockService.unlockFromHiddenFiles(player);
		synchronizeProjection(player, data);
		return true;
	}

	private static void tick(MinecraftServer server) {
		long now = server.getTickCount();
		var iterator = OPEN_VIEWS.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			ViewState view = entry.getValue();
			if (player == null) {
				remember(entry.getKey(), view);
				iterator.remove();
				continue;
			}
			if (!player.isAlive() || !validHeldTerminal(player, view.hand)) {
				remember(entry.getKey(), view);
				iterator.remove();
				ServerPlayNetworking.send(player, new TerminalClosedPayload(TerminalClosedPayload.UNAVAILABLE));
				continue;
			}
			advanceNearbyReceiver(player, view, now);
			// The hold is only RECEIVER_LOCK_TICKS long, so at the idle cadence the whole count would
			// pass between two snapshots and the panel would jump from nothing to a granted file. The
			// faster rate applies only while the dial is actually inside the window.
			if (now >= view.nextSyncTick) {
				view.nextSyncTick = now + (receiverLockTicks(player, view) > 0
						? RECEIVER_LOCK_SYNC_TICKS : LIVE_SYNC_TICKS);
				sendSnapshot(player, view);
			}
			if (now >= view.nextNavigationSyncTick) {
				view.nextNavigationSyncTick = now + NAVIGATION_SYNC_TICKS;
				sendNavigation(player);
			}
		}
		streamClosedTerminalNavigation(server, now);
	}

	/**
	 * Keeps the guidance bearing flowing to players whose terminal is shut.
	 *
	 * <p>Navigation used to be sent only from the loop above, which iterates the <em>open</em> views -
	 * so the moment the screen closed the bearing stopped updating. That is exactly backwards for
	 * what the tool is for: following a bearing means walking, and walking means the screen is shut.
	 * The player had to reopen the terminal at every correction to find out they had drifted, and the
	 * information was live the whole time.
	 *
	 * <p>With the screen closed {@code navigationSnapshot} resolves the tool from
	 * {@code TerminalToolService.guidanceTool} - the quick tool the player actually committed to,
	 * rather than whichever tab happened to be on screen - which is the one this should follow.
	 *
	 * <p>Cheap enough to run at the same four-tick cadence the open screen uses: the snapshot reads
	 * the player's terminal record and does arithmetic on stored coordinates. Nothing here searches
	 * for a structure. Players with no target resolve to {@code NONE} and are skipped before anything
	 * is sent, and a player without a usable terminal is skipped before the record is even read.
	 */
	private static void streamClosedTerminalNavigation(MinecraftServer server, long now) {
		if (now % NAVIGATION_SYNC_TICKS != 0L) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			TerminalNavigationPayload navigation = closedNavigationFor(player, data);
			if (navigation != null) ServerPlayNetworking.send(player, navigation);
		}
	}

	/**
	 * What a closed terminal should be streaming to this player, or null when it should stream
	 * nothing.
	 *
	 * <p>The whole decision in one place so it can be asserted without a client on the other end of a
	 * packet: an open screen already has its own faster stream, a player without a usable terminal
	 * has no device to speak for them, and a guidance tool with nothing selected resolves to
	 * {@code NONE} and has nothing to say.
	 */
	public static TerminalNavigationPayload closedNavigationFor(ServerPlayer player, FrequencyWorldData data) {
		if (OPEN_VIEWS.containsKey(player.getUUID())) return null;
		if (!ownsUsableTerminal(player, data)) return null;
		TerminalNavigationPayload navigation = navigationSnapshot(player);
		return navigation.targetKind() == TerminalNavigationPayload.NONE ? null : navigation;
	}

	/**
	 * Whether the player still has a terminal this readout may speak for.
	 *
	 * <p>Possession rather than holding: the point of the readout is to be legible while a pickaxe is
	 * in hand. But it is still the terminal's voice, so a player whose terminal is in the finale's
	 * custody - or who never had one - gets nothing, rather than a bearing from a device they do not
	 * have.
	 */
	private static boolean ownsUsableTerminal(ServerPlayer player, FrequencyWorldData data) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (data.isValidTerminal(player.getInventory().getItem(slot), player.getUUID())) return true;
		}
		return false;
	}

	private static boolean validHeldTerminal(ServerPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(ModItems.OLD_TERMINAL)) return false;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		return record != null
				&& !record.getBooleanOr(TerminalData.TERMINAL_CAPTURED, false)
				&& data.isValidTerminal(stack, player.getUUID());
	}

	private static void sendSnapshot(ServerPlayer player, ViewState view) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag tag = data.terminalRecord(player.getUUID()).orElse(null);
		if (tag == null) return;
		ServerLevel level = player.level();
		boolean bound = tag.getBooleanOr(TerminalData.BOUND, false);
		java.util.List<TerminalLogEntryPayload> logs = TerminalSignalLog.entries(tag).stream()
				// Relayed lines live in the same store as everything else - they are ordinary records
				// once they arrive - but they are tagged here so the page can settle them in rather than
				// simply drawing them, and so the navigation shortcut will not aim at them.
				.map(entry -> new TerminalLogEntryPayload(entry.sequence(), entry.band().wireId(),
						entry.type(), entry.gameTime(), entry.dayTime(), entry.dimension(), entry.position(),
						entry.variant(), entry.severity(), entry.unread(),
						(TerminalRelayPolicy.Shape.isRelayType(entry.type())
								? TerminalRecordPolicy.Source.RELAY
								: TerminalRecordPolicy.Source.STORY).wireId()))
				.toList();
		// The quarantined anomaly store, and only once the terminal has stopped withholding it. Sending
		// it early would put it one client-side conditional away from being visible, and that is not a
		// place to keep the one list whose whole point is that the player was never shown it.
		java.util.List<TerminalLogEntryPayload> anomalyLogs = TerminalData.anomalyBackfillReleased(tag)
				? TerminalAnomalyLog.entries(tag).stream()
						.map(entry -> new TerminalLogEntryPayload(entry.sequence(), SignalBand.UNKNOWN.wireId(),
								entry.type(), entry.gameTime(), entry.gameTime() % 24_000L, entry.dimension(),
								entry.position(), entry.variant(), entry.severity(), entry.unread(),
								TerminalRecordPolicy.Source.ANOMALY_BACKFILL.wireId()))
						.toList()
				: java.util.List.of();
		java.util.List<TerminalFilePayload> files = visibleFiles(tag);
		// Priced for the party actually on the server, so the reward frame shows the stack the player
		// will be handed rather than the solo figure.
		TerminalTaskService.TaskSnapshot objective = TerminalTaskService.current(tag,
				TerminalTaskService.rewardParticipants(level.getServer(),
						TerminalTaskService.current(tag).index()));
		long now = level.getGameTime();
		TerminalSnapshotPayload payload = new TerminalSnapshotPayload(
				TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				0,
				view.mode,
				view.initialPage,
				view.tuning,
				TerminalControlPolicy.pursuitVisualStage(
						tag.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, 0),
						tag.getIntOr(TerminalData.PURSUIT_ALLOWED_FORM, 0),
						tag.getIntOr(TerminalData.ANOMALY_TIER, 0)),
				Math.clamp(tag.getIntOr(TerminalData.BAND_STAGE, 0), 0, 3),
				Math.floorMod(tag.getIntOr(TerminalData.CACHE_VARIANT, 0), 4),
				tag.getBooleanOr(TerminalData.SECOND_CACHE_UNLOCKED, false),
				Math.floorMod(tag.getIntOr(TerminalData.SECOND_CACHE_VARIANT, 1), 4),
				personality(tag.getStringOr(TerminalData.PERSONALITY_TEMPLATE, "clinical")),
				tag.getBooleanOr(TerminalData.CONTINUITY_LEARNED, false),
				Math.clamp(tag.getIntOr(TerminalData.CONTINUITY_CONFIDENCE, 0), 0, 100),
				nonNegative(tag.getIntOr(TerminalData.PORTAL_TRANSITIONS, 0)),
				tag.getBooleanOr(TerminalData.LOCAL_FILE_UNLOCKED, false),
				tag.getBooleanOr(TerminalData.TERMINAL_CAPTURED, false),
				now,
				TerminalSignalLog.unreadCount(tag),
				TerminalFileState.unreadCount(tag),
				logs,
				tag.getStringOr(TerminalData.ACTIVE_ANOMALY_ID, "none"),
				(int) Math.clamp(tag.getLongOr(TerminalData.ACTIVE_ANOMALY_UNTIL, 0L) - now, 0L, 1200L),
				files, -1, objective.id(), objective.progress(), objective.target(),
				objective.index(), objective.claimable(), objective.rewardItemId(), objective.rewardCount(),
				!tag.getBooleanOr(TerminalData.ONBOARDING_DONE, false),
				// The same call that decides which of the six item forms this player is holding, so
				// the lamp on the panel and the lamp on the device are one statement, not two.
				TerminalData.attentionActive(tag),
				anomalyLogs,
				profileQuestion(tag),
				profileAnswers(tag),
				// Unlabelled, coarse, and allowed to be wrong when the scheduler defers. See the policy.
				OscilloscopeWaveformPolicy.approach(
						tag.getLongOr(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, 0L), now),
				com.xm.thefourthfrequency.world.FragmentInvestigationService.discoveredFragmentMask(player));
		ServerPlayNetworking.send(player, payload);
		ServerPlayNetworking.send(player, TerminalToolService.snapshot(player, view.selectedTool,
				view.tuning, receiverLockTicks(player, view)));
	}

	/**
	 * The profile question on screen, or {@code -1}.
	 *
	 * <p>Gated on the taken latch rather than on how many answers are filled in, because a profile
	 * released early by the damage failsafe is finished even though it has gaps. Asking again later
	 * would be a replay, and the walkthrough is one-shot by contract.
	 */
	private static int profileQuestion(CompoundTag tag) {
		if (TerminalData.profileTaken(tag)) return -1;
		int question = tag.getIntOr(TerminalData.PROFILE_QUESTION, 0);
		return TerminalProfileQuestionnaire.valid(question) ? question : -1;
	}

	private static java.util.List<Integer> profileAnswers(CompoundTag tag) {
		int[] answers = TerminalData.profileAnswers(tag);
		java.util.ArrayList<Integer> boxed = new java.util.ArrayList<>(answers.length);
		for (int answer : answers) boxed.add(answer);
		return java.util.List.copyOf(boxed);
	}

	public static java.util.List<TerminalFilePayload> visibleFiles(CompoundTag tag) {
		Map<String, TerminalFileState.State> knownFiles = TerminalFileState.states(tag).stream()
				.collect(java.util.stream.Collectors.toMap(TerminalFileState.State::id, state -> state));
		return NarrativeFileCatalog.definitions().stream().filter(definition ->
				knownFiles.containsKey(definition.id())).map(definition -> {
			TerminalFileState.State file = knownFiles.get(definition.id());
			return new TerminalFilePayload(definition.id(), true, file.unlocked(),
					file.discoveredGameTime(), file.discoveredDayTime(),
					file.unlockedGameTime(), file.unlockedDayTime(),
					file.read(), file.readGameTime(), file.readDayTime(),
					0);
		}).toList();
	}

	private static void sendNavigation(ServerPlayer player) {
		ServerPlayNetworking.send(player, navigationSnapshot(player));
	}

	public static TerminalNavigationPayload navigationSnapshot(ServerPlayer player) {
		CompoundTag tag = FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID()).orElse(null);
		if (tag == null) return new TerminalNavigationPayload(TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION,
				0, false, false, 0, 0, 0, player.getYRot());
		NavigationState navigation = NavigationState.read(tag);
		BlockPos playerPos = player.blockPosition();
		int guidance = TerminalToolService.guidanceTool(tag);
		int kind = TerminalNavigationPayload.NONE;
		boolean located = false;
		boolean sameDimension = false;
		int dx = 0;
		int dz = 0;
		int targetY = 0;
		ViewState view = OPEN_VIEWS.get(player.getUUID());
		TerminalTool preview = view == null ? null : TerminalTool.fromSlot(view.selectedTool);
		TerminalTool tool = preview != null ? preview : TerminalTool.fromSlot(guidance);
		if (tool != null) {
			switch (tool) {
				case MINERALS -> {
					kind = targetKind(navigation.kind());
					located = TerminalNavigationPayload.isMineral(kind) && navigation.located();
					// No survey fallback: a survey hit is now written straight into the navigation
					// state, so there is only ever one place the mineral target comes from.
					BlockPos target = BlockPos.of(navigation.position());
					sameDimension = player.level().dimension().identifier().toString()
							.equals(navigation.dimension());
					dx = boundedDelta(target.getX() - playerPos.getX());
					dz = boundedDelta(target.getZ() - playerPos.getZ());
					targetY = target.getY();
				}
				case NAVIGATION -> {
					TerminalStructureTarget structure = TerminalStructureTarget.fromId(navigation.kind());
					kind = navigation.kind().equals("structure_fragment")
							? TerminalNavigationPayload.UNSTABLE_SIGNAL : structureNavigationKind(structure);
					located = (navigation.kind().equals("structure_fragment")
							|| structure != TerminalStructureTarget.NONE) && navigation.located();
					sameDimension = player.level().dimension().identifier().toString().equals(navigation.dimension());
					BlockPos target = BlockPos.of(navigation.position());
					dx = boundedDelta(target.getX() - playerPos.getX());
					dz = boundedDelta(target.getZ() - playerPos.getZ());
					targetY = target.getY();
				}
				case HOME, PORTAL -> {
					kind = tool == TerminalTool.HOME ? TerminalNavigationPayload.HOME : TerminalNavigationPayload.PORTAL;
					TerminalToolService.Location target = TerminalToolService.guidanceLocation(player, tag, tool);
					located = target.known();
					sameDimension = target.dimension().equals(player.level().dimension().identifier().toString());
					dx = boundedDelta(target.position().getX() - playerPos.getX());
					dz = boundedDelta(target.position().getZ() - playerPos.getZ());
					targetY = target.position().getY();
				}
				case STRONGHOLD -> {
					kind = TerminalNavigationPayload.STRONGHOLD;
					TerminalToolService.StrongholdEstimate estimate = TerminalToolService.strongholdEstimate(player, tag);
					located = estimate.known();
					sameDimension = estimate.sameDimension();
					dx = estimate.dx();
					dz = estimate.dz();
				}
				case WEATHER -> { }
			}
		}
		boolean disabled = TerminalToolService.toolsDisabled(tag, player.level().getGameTime());
		boolean navigable = TerminalNavigationMath.navigable(kind, disabled, located, sameDimension);
		return new TerminalNavigationPayload(
				TerminalNavigationPayload.CURRENT_PROTOCOL_VERSION,
				kind,
				located,
				navigable,
				navigable ? dx : 0,
				navigable ? dz : 0,
				located ? targetY : 0,
				player.getYRot());
	}

	public static int navigationSyncTicks() {
		return NAVIGATION_SYNC_TICKS;
	}

	private static InteractionHand decodeHand(int hand) {
		return switch (hand) {
			case 0 -> InteractionHand.MAIN_HAND;
			case 1 -> InteractionHand.OFF_HAND;
			default -> null;
		};
	}

	private static void remember(UUID id, ViewState view) {
		REMEMBERED_MODES.put(id, view.mode);
		// A pursuit warning forces one open onto RECORDS. That page is the warning's choice, not the
		// player's, so it must not become what the terminal reopens on: the redirect is consumed
		// after a single open, but remembering it made every later open land on RECORDS anyway - the
		// flag was spent and the effect stayed. Once the player navigates anywhere themselves the
		// override is dropped and the normal memory takes over.
		REMEMBERED_PAGES.put(id, view.forcedPageUntouched ? view.pageBeforeForcedOpen : view.page);
		REMEMBERED_TUNING.put(id, view.tuning);
	}

	private static int nonNegative(int value) {
		return Math.max(0, value);
	}

	private static int boundedDelta(int value) {
		return Math.clamp(value, -30_000_000, 30_000_000);
	}

	private static int targetKind(String value) {
		return switch (value) {
			case "iron" -> TerminalNavigationPayload.IRON;
			case "coal" -> TerminalNavigationPayload.COAL;
			case "gold" -> TerminalNavigationPayload.GOLD;
			case "diamond" -> TerminalNavigationPayload.DIAMOND;
			case "emerald" -> TerminalNavigationPayload.EMERALD;
			case "structure_fragment" -> TerminalNavigationPayload.UNSTABLE_SIGNAL;
			default -> TerminalNavigationPayload.NONE;
		};
	}

	private static int structureNavigationKind(TerminalStructureTarget target) {
		return switch (target) {
			case VILLAGE -> TerminalNavigationPayload.VILLAGE;
			case RUINED_PORTAL -> TerminalNavigationPayload.RUINED_PORTAL;
			case MINESHAFT -> TerminalNavigationPayload.MINESHAFT;
			case TRIAL_CHAMBERS -> TerminalNavigationPayload.TRIAL_CHAMBERS;
			case FORTRESS -> TerminalNavigationPayload.FORTRESS;
			case BASTION -> TerminalNavigationPayload.BASTION;
			case NONE -> TerminalNavigationPayload.NONE;
		};
	}

	private static int personality(String value) {
		return switch (value) {
			case "cautious" -> 0;
			case "direct" -> 1;
			case "wry" -> 2;
			default -> 3;
		};
	}

	public static boolean isOpen(ServerPlayer player) {
		return OPEN_VIEWS.containsKey(player.getUUID());
	}

	public static void refresh(ServerPlayer player) {
		ViewState view = OPEN_VIEWS.get(player.getUUID());
		if (view != null) {
			sendSnapshot(player, view);
			sendNavigation(player);
		}
	}

	public static void synchronizeProjection(ServerPlayer player) {
		synchronizeProjection(player, FrequencyWorldData.get(player.level().getServer()));
	}

	public static void synchronizeProjection(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag authoritative = data.terminalRecord(player.getUUID()).orElse(null);
		if (authoritative != null) {
			boolean changed = false;
			for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
				ItemStack stack = player.getInventory().getItem(slot);
				if (data.isValidTerminal(stack, player.getUUID())) changed |= TerminalData.applyProjection(stack, authoritative);
			}
			if (changed) player.getInventory().setChanged();
		}
	}

	public static void synchronizeAttentionProjection(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag authoritative = data.terminalRecord(player.getUUID()).orElse(null);
		if (authoritative == null) return;
		boolean changed = false;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (data.isValidTerminal(stack, player.getUUID())) {
				changed |= TerminalData.applyAttentionProjection(stack, authoritative);
			}
		}
		if (changed) player.getInventory().setChanged();
	}

	public static int rememberedMode(UUID playerId) {
		return REMEMBERED_MODES.getOrDefault(playerId, TerminalControlPolicy.Mode.SIGNAL.ordinal());
	}

	public static int rememberedPage(UUID playerId) {
		return REMEMBERED_PAGES.containsKey(playerId)
				? TerminalPage.fromIndex(REMEMBERED_PAGES.get(playerId)).ordinal()
				: TerminalPage.initialPage(rememberedMode(playerId)).ordinal();
	}

	public static boolean viewOpenForTesting(ServerPlayer player) {
		return OPEN_VIEWS.containsKey(player.getUUID());
	}

	public static int rememberedTuning(UUID playerId) {
		return REMEMBERED_TUNING.getOrDefault(playerId, TerminalControlPolicy.DEFAULT_TUNING);
	}

	private static void applyTuning(ServerPlayer player, ViewState view, int value) {
		view.tuning = value;
		REMEMBERED_TUNING.put(player.getUUID(), view.tuning);
	}

	private static boolean receiverAvailable(ServerPlayer player) {
		CompoundTag record = FrequencyWorldData.get(player.level().getServer())
				.terminalRecord(player.getUUID()).orElse(null);
		return record != null && record.getIntOr(TerminalData.BAND_STAGE, 0) > 0
				&& !TerminalToolService.toolsDisabled(record, player.level().getGameTime())
				&& FragmentInvestigationService.nearby(player).isPresent();
	}

	private static void advanceNearbyReceiver(ServerPlayer player, ViewState view, long now) {
		var nearby = FragmentInvestigationService.nearby(player).orElse(null);
		if (nearby == null || !receiverAvailable(player)
				|| !TerminalControlPolicy.receiverLocked(view.tuning, nearby.tuning())) {
			resetReceiver(view, now);
			return;
		}
		if (!nearby.key().equals(view.fragmentCandidateKey)) {
			view.fragmentCandidateKey = nearby.key();
			view.fragmentLockedSinceTick = now;
			return;
		}
		if (now - view.fragmentLockedSinceTick >= RECEIVER_LOCK_TICKS
				&& FragmentInvestigationService.completeNearby(player, view.tuning)) resetReceiver(view, now);
	}

	/**
	 * Progress towards the {@value #RECEIVER_LOCK_TICKS}-tick hold that grants a hidden file.
	 *
	 * <p>Reads the server tick count itself rather than taking a clock from the caller. It used to be
	 * handed {@code level.getGameTime()} while {@link ViewState#fragmentLockedSinceTick} is written
	 * from {@code server.getTickCount()} - two clocks that only agree on a world that has never been
	 * reloaded. On every other world the game time is far ahead of this session's tick count, so the
	 * difference clamped straight to the maximum: the panel announced a lock the moment the dial
	 * entered the window, a second before the file was actually granted, and never counted up. A
	 * player who trusted the readout and moved on took nothing with them.</p>
	 */
	private static int receiverLockTicks(ServerPlayer player, ViewState view) {
		var nearby = FragmentInvestigationService.nearby(player).orElse(null);
		if (nearby == null || !nearby.key().equals(view.fragmentCandidateKey)
				|| !TerminalControlPolicy.receiverLocked(view.tuning, nearby.tuning())) return 0;
		long now = player.level().getServer().getTickCount();
		return (int) Math.clamp(now - view.fragmentLockedSinceTick, 0L, RECEIVER_LOCK_TICKS);
	}

	private static void resetReceiver(ViewState view, long now) {
		view.fragmentCandidateKey = "";
		view.fragmentLockedSinceTick = now;
	}

	private static final class ViewState {
		private final InteractionHand hand;
		private int mode;
		private final int initialPage;
		/** Where the view is now, as opposed to where it opened. */
		private int page;
		private int tuning;
		private long nextSyncTick;
		private long nextNavigationSyncTick;
		private String fragmentCandidateKey;
		private long fragmentLockedSinceTick;
		private int selectedTool;
		/** True while this view is still sitting on a page a pursuit warning chose for it. */
		private boolean forcedPageUntouched;
		/** The page the player had left the terminal on before that warning took over. */
		private int pageBeforeForcedOpen;

		private ViewState(InteractionHand hand, int mode, int initialPage, int tuning, long nextSyncTick,
				long nextNavigationSyncTick, String fragmentCandidateKey, long fragmentLockedSinceTick,
				int selectedTool) {
			this.hand = hand;
			this.mode = mode;
			this.initialPage = initialPage;
			this.page = initialPage;
			this.tuning = tuning;
			this.nextSyncTick = nextSyncTick;
			this.nextNavigationSyncTick = nextNavigationSyncTick;
			this.fragmentCandidateKey = fragmentCandidateKey;
			this.fragmentLockedSinceTick = fragmentLockedSinceTick;
			this.selectedTool = selectedTool;
		}
	}
}
