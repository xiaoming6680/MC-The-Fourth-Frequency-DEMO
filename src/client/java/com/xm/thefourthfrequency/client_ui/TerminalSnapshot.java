package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.narrative.WitnessArchive;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.networking.TerminalLogEntryPayload;
import com.xm.thefourthfrequency.networking.TerminalFilePayload;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import com.xm.thefourthfrequency.terminal.OscilloscopeWaveformPolicy;
import com.xm.thefourthfrequency.terminal.TerminalProfileQuestionnaire;
import com.xm.thefourthfrequency.terminal.TerminalRelayPolicy;
import com.xm.thefourthfrequency.terminal.TerminalRecordPolicy;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.terminal.TerminalTaskService;
import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import com.xm.thefourthfrequency.terminal.TerminalNavigationVisualPolicy;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.world.MineralDeceptionPolicy;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record TerminalSnapshot(TerminalSnapshotPayload payload) {

	public TerminalSnapshot {
		if (payload.protocolVersion() != TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION) {
			throw new IllegalStateException("Terminal protocol mismatch: server=" + payload.protocolVersion()
					+ ", client=" + TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION);
		}
	}

	public int mode() { return Math.clamp(payload.mode(), 0, 1); }
	public int initialPage() { return Math.clamp(payload.initialPage(), 0, 3); }
	public int tuning() { return Math.clamp(payload.tuning(), 0, 100); }
	public int visualStage() { return Math.clamp(payload.visualStage(), 0, 2); }
	public int bandStage() { return Math.clamp(payload.bandStage(), 0, 3); }
	/** Whether this player still owes the first-boot walkthrough. */
	public boolean onboardingRequired() { return payload.onboardingRequired(); }
	/** How close the next ambient anomaly is, 0-100. Drives the scope and nothing that is labelled. */
	public int anomalyApproach() {
		return Math.clamp(payload.anomalyApproach(), 0, OscilloscopeWaveformPolicy.MAX_APPROACH);
	}
	/**
	 * Whether a candidate line's fragment has already been found.
	 *
	 * <p>The row stays on the page either way - the log records what happened. This only decides
	 * whether it still carries a navigation shortcut, because the server refuses to retarget at a
	 * fragment that is already in hand and a shortcut that silently does nothing is exactly the kind
	 * of mark the records page may not make.
	 */
	public boolean candidateFragmentFound(String type) {
		int fragment = TerminalRecordPolicy.candidateFragment(type);
		return fragment >= 0 && (payload.discoveredFragmentMask() & 1 << fragment) != 0;
	}
	/** The profile question being asked, or {@code -1} when the profile is over or was never owed. */
	public int profileQuestion() { return payload.profileQuestion(); }
	/**
	 * One recorded answer, or {@code UNANSWERED}.
	 *
	 * <p>Bounds-checked here rather than at every call site: the list is sized by the server's build
	 * of the questionnaire, and a client running a build with more questions than the server would
	 * otherwise index off the end of it while drawing.
	 */
	public int profileAnswer(int questionIndex) {
		java.util.List<Integer> answers = payload.profileAnswers();
		if (questionIndex < 0 || questionIndex >= answers.size()) {
			return TerminalProfileQuestionnaire.UNANSWERED;
		}
		return answers.get(questionIndex);
	}
	/**
	 * Whether the terminal is asking for the player's attention: the amber lamp on the hardware
	 * column. Decided on the server by the same call that chooses the held item's form, so this is
	 * read straight through rather than re-derived from the unread counts that happen to be here.
	 */
	public boolean attentionActive() { return payload.attentionActive(); }
	/** Day number the status strip prints, counting the first day as one rather than zero. */
	public int worldDay() { return (int) Math.max(0L, payload.gameTime() / 24_000L) + 1; }
	public long worldDayTime() { return Math.floorMod(payload.gameTime(), 24_000L); }
	public boolean localFileUnlocked() { return payload.localFileUnlocked(); }
	public int unreadCount() { return Math.max(0, payload.unreadCount()); }
	public int unreadFileCount() { return Math.max(0, payload.unreadFileCount()); }
	public String objectiveId() { return payload.objectiveId(); }
	public int objectiveProgress() { return Math.max(0, payload.objectiveProgress()); }
	public int objectiveTarget() { return Math.max(0, payload.objectiveTarget()); }
	public int objectiveIndex() { return Math.max(0, payload.objectiveIndex()); }
	public boolean objectiveClaimable() { return payload.objectiveClaimable(); }
	public int objectiveRewardCount() { return Math.max(0, payload.objectiveRewardCount()); }
	public double objectiveFraction() {
		return objectiveTarget() <= 0 ? 0.0D : Math.clamp(objectiveProgress() / (double) objectiveTarget(), 0.0D, 1.0D);
	}
	public ItemStack objectiveReward() {
		Identifier id = Identifier.tryParse(payload.objectiveRewardItem());
		Item item = id == null ? null : BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR || objectiveRewardCount() <= 0) return ItemStack.EMPTY;
		return new ItemStack(item, Math.min(objectiveRewardCount(), item.getDefaultMaxStackSize()));
	}
	public int portalTransitions() { return Math.max(0, payload.portalTransitions()); }
	public Component objectiveLine() {
		return Component.translatable("terminal.thefourthfrequency.objective." + payload.objectiveId(),
				payload.objectiveProgress(), payload.objectiveTarget());
	}
	/**
	 * This objective as it reads once finished, whatever progress the snapshot happened to carry.
	 *
	 * <p>Used by the home card's completion hold, which shows the task the player just finished
	 * rather than the one that replaced it. The snapshot it comes from is the last one before the
	 * reward landed, so its own progress is typically one short of the target it just reached.</p>
	 */
	public Component completedObjectiveLine() {
		return TerminalTaskService.completedObjectiveLine(payload.objectiveId(), objectiveTarget());
	}
	/**
	 * The record log, exactly as the Records page lists it.
	 *
	 * @param navigator whether the navigation tool exists yet. An optional investigation the player
	 *                  has no navigator for is not a lead, it is a line of text they cannot do
	 *                  anything with, so it stays out of the log until the tool that can act on it
	 *                  exists.
	 *                  <p>The flag is threaded through here rather than applied by the page, because
	 *                  the home card's "recent" line is defined as the newest entry of <em>this</em>
	 *                  list. While the page filtered and this did not, a player without a navigator
	 *                  was told on the home page that a suspicious signal had been found somewhere,
	 *                  opened Records to look it up, and found it absent - the terminal contradicting
	 *                  itself about its own log.</p>
	 */
	public List<TerminalLogEntryPayload> recordEntries(boolean navigator) {
		Set<Integer> seenCandidateLocations = new HashSet<>();
		List<TerminalLogEntryPayload> story = payload.signalEvents().stream()
				.filter(entry -> TerminalRecordPolicy.listedInRecords(entry.type(), entry.variant(),
						navigator, seenCandidateLocations))
				.toList();
		// Before the backfill latch the server sends nothing here, so this is not a client-side gate
		// on whether to show them - there is simply nothing to show.
		if (payload.anomalyLogs().isEmpty()) return story;
		// Merged by world time rather than concatenated, because the whole claim of the backfilled
		// list is that these things happened between the entries the player has been reading all
		// along. Bolted onto the end they would read as an appendix; interleaved they read as the log
		// the terminal was keeping the entire time.
		//
		// The two stores number their sequences independently, so sequence is only a tie-break for
		// entries that share a tick - it is there to keep the story lines in their existing relative
		// order, not to order across the two.
		java.util.List<TerminalLogEntryPayload> merged =
				new java.util.ArrayList<>(story.size() + payload.anomalyLogs().size());
		merged.addAll(story);
		merged.addAll(payload.anomalyLogs());
		merged.sort(java.util.Comparator.comparingLong(TerminalLogEntryPayload::gameTime)
				.thenComparingInt(TerminalLogEntryPayload::sequence).reversed());
		return List.copyOf(merged);
	}

	/** Whether this line should arrive through the glyph settle instead of simply being drawn. */
	public static boolean settlesIn(TerminalLogEntryPayload entry) {
		return TerminalRecordPolicy.Source.fromWire(entry.source()).settlesIn();
	}

	/** Whether the records page may aim the navigation shortcut at this line. */
	public static boolean navigable(TerminalLogEntryPayload entry) {
		return TerminalRecordPolicy.Source.fromWire(entry.source()).navigable();
	}
	public Component latestSignalEvent(boolean navigator) {
		List<TerminalLogEntryPayload> entries = recordEntries(navigator);
		if (entries.isEmpty()) return Component.translatable("terminal.thefourthfrequency.home.no_recent");
		TerminalLogEntryPayload latest = entries.getFirst();
		return Component.literal("[" + signalTime(latest) + "] ").append(signalEvent(latest));
	}
	public Component weatherLine() {
		int weather = 0;
		for (TerminalLogEntryPayload entry : payload.signalEvents()) {
			if (entry.type().equals("weather_changed")) weather = Math.clamp(entry.variant(), 0, 2);
		}
		String weatherId = switch (weather) {
			case 1 -> "rain";
			case 2 -> "thunder";
			default -> "clear";
		};
		long dayTime = Math.floorMod(payload.gameTime(), 24000L);
		String light = dayTime < 13000L || dayTime >= 23000L ? "day" : "night";
		return Component.translatable("terminal.thefourthfrequency.tool.weather.current",
				Component.translatable("terminal.thefourthfrequency.environment.weather." + weatherId),
				Component.translatable("terminal.thefourthfrequency.tool.weather." + light));
	}
	public List<TerminalFilePayload> files() { return payload.files(); }
	public List<TerminalFilePayload> directoryFiles() {
		return payload.files();
	}
	public TerminalFilePayload fragmentFile(int index) {
		if (index < 0 || index >= HiddenFilePolicy.FILE_COUNT) return null;
		String id = HiddenFilePolicy.fileId(index);
		return payload.files().stream().filter(file -> file.id().equals(id)).findFirst().orElse(null);
	}
	public int discoveredHiddenFileCount() {
		return (int) payload.files().stream().filter(file -> HiddenFilePolicy.isHiddenFile(file.id())).count();
	}
	public int readHiddenFileCount() {
		return (int) payload.files().stream()
				.filter(file -> HiddenFilePolicy.isHiddenFile(file.id()) && file.read()).count();
	}
	public int hiddenFileReadPercent() { return readHiddenFileCount() * 100 / HiddenFilePolicy.FILE_COUNT; }
	/**
	 * The one line the terminal shows for the guidance tool that is currently selected.
	 *
	 * <p>Static, and reads nothing but its two arguments. It used to be an instance method purely by
	 * where it was written; {@link TerminalNavigationReadout} needs the same sentence with no
	 * terminal open and no snapshot to hang it off, and the readout on the HUD and the readout on the
	 * home page have to be the same sentence or they are two facts that can disagree.
	 */
	public static Component navigationLine(TerminalNavigationPayload navigation, int playerY) {
		return navigationLine(navigation, playerY, null);
	}

	/**
	 * The same line, optionally spoken relative to where the player is facing.
	 *
	 * @param viewYaw the player's own yaw when the bearing should read as "ahead-left" rather than
	 *                "south-west", or null for the compass wording the terminal uses. The HUD readout
	 *                passes it and the terminal does not: inside the terminal there is a drawn compass
	 *                right next to the line and absolute bearings agree with it, while a player walking
	 *                with the screen shut has no compass and has to translate "south-west" against
	 *                whichever way they happen to be pointing. It is taken live from the client rather
	 *                than from the payload's own yaw field, which is up to four ticks stale and would
	 *                make the wording lag behind the turn.
	 */
	public static Component navigationLine(TerminalNavigationPayload navigation, int playerY, Float viewYaw) {
		if (navigation.targetKind() == TerminalNavigationPayload.UNSTABLE_SIGNAL) {
			if (!navigation.located()) return Component.translatable("terminal.thefourthfrequency.navigation.fragment.scanning");
			if (!navigation.navigable()) return Component.translatable(
					"terminal.thefourthfrequency.navigation.fragment.unavailable", navigation.targetY());
			return Component.translatable("terminal.thefourthfrequency.navigation.fragment.located",
					bearing(navigation.targetDx(), navigation.targetDz(), viewYaw),
					distance(navigation.targetDx(), navigation.targetDz()));
		}
		if (navigation.targetKind() == TerminalNavigationPayload.HOME
				|| navigation.targetKind() == TerminalNavigationPayload.PORTAL) {
			String id = navigation.targetKind() == TerminalNavigationPayload.HOME ? "home" : "portal";
			if (!navigation.located()) return Component.translatable(
					"terminal.thefourthfrequency.navigation." + id + ".missing");
			if (!navigation.navigable()) return Component.translatable(
					"terminal.thefourthfrequency.navigation." + id + ".other_dimension");
			return Component.translatable("terminal.thefourthfrequency.navigation." + id + ".located",
					bearing(navigation.targetDx(), navigation.targetDz(), viewYaw),
					distance(navigation.targetDx(), navigation.targetDz()));
		}
		if (navigation.targetKind() == TerminalNavigationPayload.STRONGHOLD) {
			if (!navigation.located()) return Component.translatable(
					"terminal.thefourthfrequency.navigation.stronghold.missing");
			if (!navigation.navigable()) return Component.translatable(
					"terminal.thefourthfrequency.navigation.stronghold.other_dimension");
			return Component.translatable("terminal.thefourthfrequency.navigation.stronghold.located",
					bearing(navigation.targetDx(), navigation.targetDz(), viewYaw));
		}
		if (navigation.targetKind() >= TerminalNavigationPayload.VILLAGE
				&& navigation.targetKind() <= TerminalNavigationPayload.BASTION) {
			String id = switch (navigation.targetKind()) {
				case TerminalNavigationPayload.VILLAGE -> "village";
				case TerminalNavigationPayload.RUINED_PORTAL -> "ruined_portal";
				case TerminalNavigationPayload.MINESHAFT -> "mineshaft";
				case TerminalNavigationPayload.TRIAL_CHAMBERS -> "trial_chambers";
				case TerminalNavigationPayload.FORTRESS -> "fortress";
				default -> "bastion";
			};
			Component target = Component.translatable("terminal.thefourthfrequency.navigation.target." + id);
			if (!navigation.located()) return Component.translatable(
					"terminal.thefourthfrequency.navigation.structure.scanning", target);
			if (!navigation.navigable()) return Component.translatable(
					"terminal.thefourthfrequency.navigation.structure.unavailable", target);
			// No height difference for structures, because there is no height to report.
			//
			// A structure target comes from Minecraft's own locate, and that returns
			// `new BlockPos(chunk.getStartX(), 0, chunk.getStartZ())` - the Y is a literal zero, a
			// placeholder for a coordinate the game never computes. Subtracting the player's Y from
			// it produced a number that was always exactly -playerY: "height difference -64" for
			// every structure at sea level, forever. It looked wrong for a village and plausible for
			// a mineshaft, which is why the mineshaft is the one that got followed and reported.
			//
			// Direction and distance are real - they come from the start chunk's own coordinates - so
			// they stay. A missing line is better than a confident wrong one.
			return Component.translatable("terminal.thefourthfrequency.navigation.structure.located", target,
					bearing(navigation.targetDx(), navigation.targetDz(), viewYaw),
					distance(navigation.targetDx(), navigation.targetDz()));
		}
		// Every kind TerminalNavigationPayload.isMineral accepts needs a branch here.
		//
		// Emerald did not have one. It is a mineral by that predicate, so a survey that found emerald
		// resolved as located and navigable and then fell through to "unresolved" - which renders as
		// "还不知道需要什么", a sentence about the player not having a goal yet, printed over a bearing
		// that was pointing straight at one. The name it needed was already in both language files.
		// TerminalNavigationReadoutTest walks isMineral and refuses any kind that lands on the
		// placeholder, so the next mineral added cannot repeat this.
		String target = switch (navigation.targetKind()) {
			case TerminalNavigationPayload.IRON -> "iron";
			case TerminalNavigationPayload.COAL -> "coal";
			case TerminalNavigationPayload.GOLD -> "gold";
			case TerminalNavigationPayload.DIAMOND -> "diamond";
			case TerminalNavigationPayload.EMERALD -> "emerald";
			default -> "unresolved";
		};
		Component mineral = Component.translatable("terminal.thefourthfrequency.resource." + target);
		if (!navigation.located()) return Component.translatable(
				"terminal.thefourthfrequency.navigation.scanning", mineral);
		if (!navigation.navigable()) return Component.translatable(
				"terminal.thefourthfrequency.navigation.unavailable", mineral, navigation.targetY());
		return Component.translatable("terminal.thefourthfrequency.navigation.located", mineral,
				bearing(navigation.targetDx(), navigation.targetDz(), viewYaw),
				distance(navigation.targetDx(), navigation.targetDz()), navigation.targetY() - playerY);
	}

	public String signalTime(TerminalLogEntryPayload entry) { return TerminalSignalLog.clock(entry.dayTime()); }

	public Component signalEvent(TerminalLogEntryPayload entry) {
		// Backfilled anomalies get the short line, never the summary. Both were authored years before
		// anything read them, and only one of them is in the terminal's voice: log.type is "附近突然变暗",
		// while log.summary is a specification blurb that says "the player" and "the server". The
		// records page is the terminal talking, so the summary stays where it is.
		if (TerminalRecordPolicy.Source.fromWire(entry.source())
				== TerminalRecordPolicy.Source.ANOMALY_BACKFILL) {
			return anomalyType(entry);
		}
		// Relayed lines are their own small closed set, and every one of them is deliberately vague.
		// What crossed was the shape of what another terminal recorded, never its content, so there is
		// nothing here to name and nobody to attribute it to.
		if (TerminalRelayPolicy.Shape.isRelayType(entry.type())) {
			return Component.translatable("terminal.thefourthfrequency.signal.event." + entry.type());
		}
		// Two halves in two colours: what the terminal observed, then the part that is only a warning.
		// The green half is per form. The five forms track by five different rules - one corrects on
		// sound, three arrives ahead of where you are going, four keeps returning behind you - and
		// this line used to hand all five the same sentence, so the log could not tell a player which
		// of those they had survived. The entry's variant carries the form; anything outside 1..5,
		// including a line written by an older build, falls back to the shared wording.
		if (entry.type().startsWith("pursuit_warning_")) {
			int form = entry.variant();
			String observed = form >= 1 && form <= PursuitProgressPolicy.FORM_COUNT
					? "terminal.thefourthfrequency.signal.event.pursuit_warning_" + form
					: "terminal.thefourthfrequency.signal.event.pursuit_warning.approaching";
			return Component.empty()
					.append(Component.translatable(observed).withStyle(ChatFormatting.GREEN))
					.append(Component.literal(" "))
					.append(Component.translatable(
							"terminal.thefourthfrequency.signal.event.pursuit_warning.prepare")
							.withStyle(ChatFormatting.RED));
		}
		// The one line that reprints a reading the probe is known to have got wrong. Two halves: what
		// the log holds, then what the tool displayed, and only the second half is corrupted. The
		// corruption is the line's only signal that it is not ordinary - it is filed read, so there is
		// no badge and no tab dot, and a player who never scrolls back never learns it is here.
		if (entry.type().equals("mineral_reading_forged")) {
			int packed = entry.severity();
			// Rebuilt through the same Forgery the server displayed, so the direction word and the
			// band come out of the same code the honest reading uses. Recomputing either of them
			// here by hand would let the trace and the tool disagree about what was on screen.
			MineralDeceptionPolicy.Forgery forgery = new MineralDeceptionPolicy.Forgery(
					TerminalResource.fromWire(entry.variant()),
					MineralDeceptionPolicy.unpackOctant(packed),
					MineralDeceptionPolicy.unpackDistance(packed));
			String shown = Component.translatable(
					"terminal.thefourthfrequency.signal.event.mineral_reading_forged.shown",
					Component.translatable(
							"terminal.thefourthfrequency.resource." + forgery.resource().id()).getString(),
					Component.translatable("terminal.thefourthfrequency.direction."
							+ TerminalNavigationMath.direction(
									forgery.bearing().dx(), forgery.bearing().dz())).getString(),
					forgery.bandMinimum(), forgery.bandMaximum()).getString();
			return Component.empty()
					.append(Component.translatable(
							"terminal.thefourthfrequency.signal.event.mineral_reading_forged.logged"))
					.append(Component.literal(" · "))
					.append(Component.literal(TerminalNavigationVisualPolicy.corruptReadout(
							shown, entry.gameTime() ^ packed)));
		}
		// The counterpart of the warning above, assembled the same way: the instrument's own line in
		// green, the part that is not a readout in red.
		if (entry.type().equals("pursuit_survived")) return Component.empty()
				.append(Component.translatable(
						"terminal.thefourthfrequency.signal.event.pursuit_survived.cleared")
						.withStyle(ChatFormatting.GREEN))
				.append(Component.literal(" "))
				.append(Component.translatable(
						"terminal.thefourthfrequency.signal.event.pursuit_survived.next_time")
						.withStyle(ChatFormatting.RED));
		if (entry.type().startsWith("fragment_candidate_")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event.fragment_candidate", fragmentLocationName(entry));
		if (entry.type().startsWith("fragment_marker_")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event.fragment_marker");
		if (entry.type().startsWith("fragment_action_")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event.fragment_action");
		if (entry.type().startsWith("fragment_shared_") || entry.type().startsWith("fragment_received_")) {
			return Component.translatable("terminal.thefourthfrequency.signal.event.fragment_file");
		}
		if (entry.type().equals("weather_changed")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event.weather_changed",
				Component.translatable("terminal.thefourthfrequency.environment.weather."
						+ switch (Math.clamp(entry.variant(), 0, 2)) {
							case 1 -> "rain";
							case 2 -> "thunder";
							default -> "clear";
						}));
		if (entry.type().equals("dimension_changed")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event.dimension_changed", entry.dimension());
		if (entry.type().startsWith("resource_")) return Component.translatable(
				"terminal.thefourthfrequency.signal.event." + entry.type(),
				Component.translatable("terminal.thefourthfrequency.resource."
						+ switch (entry.variant()) {
							case 0 -> "iron";
							case 4 -> "coal";
							case 5 -> "gold";
							case 2 -> "diamond";
							default -> "unresolved";
						}));
		return Component.translatable("terminal.thefourthfrequency.signal.event." + entry.type(), entry.variant());
	}

	public Component fragmentLocationName(TerminalLogEntryPayload entry) {
		String resolved = Component.translatable("terminal.thefourthfrequency.structure."
				+ Math.clamp(entry.variant(), 0, 13)).getString();
		int codePoints = resolved.codePointCount(0, resolved.length());
		int insertion = Math.floorMod(entry.sequence() * 31 + entry.variant() * 17, codePoints + 1);
		int split = resolved.offsetByCodePoints(0, insertion);
		return Component.empty()
				.append(Component.literal(resolved.substring(0, split)))
				.append(Component.literal("x").withStyle(Style.EMPTY.withObfuscated(true)))
				.append(Component.literal(resolved.substring(split)));
	}

	public Component fileTitle(TerminalFilePayload file) {
		if (HiddenFilePolicy.isHiddenFile(file.id())) {
			return damagedFileTitle(file.id());
		}
		if (file.id().equals(HiddenFilePolicy.COMPLETE_FILE_ID)) {
			int stage = discoveredHiddenFileCount();
			var title = Component.translatable(
					"terminal.thefourthfrequency.file.encrypted_witness_file.revealed." + stage);
			if (stage < HiddenFilePolicy.FILE_COUNT) {
				title.append(Component.translatable(
						"terminal.thefourthfrequency.file.encrypted_witness_file.masked." + stage)
						.withStyle(ChatFormatting.OBFUSCATED));
			}
			return title;
		}
		return Component.translatable(NarrativeFileCatalog.require(file.id()).titleKey());
	}

	public List<Component> fileContent(TerminalFilePayload file) {
		if (!file.unlocked() && !file.id().equals("encrypted_witness_file")) {
			return List.of(Component.translatable("terminal.thefourthfrequency.file.locked"));
		}
		if (HiddenFilePolicy.isHiddenFile(file.id())) return damagedFileContent(file.id());
		if (file.id().equals("encrypted_witness_file")) return file.unlocked()
				? archive() : List.of(Component.translatable("terminal.thefourthfrequency.file.locked"));
		// The one file whose body the server never sends. Its words are about the player's own previous
		// playthrough - a fact about this machine rather than about this world - so the server owns
		// only whether it exists and whether it has been read, and the client composes the rest. The
		// catalogue still lists its keys so the translation contract covers them.
		if (file.id().equals(HiddenFilePolicy.RECOVERED_FILE_ID)) return PreviousRunClient.lines();
		List<Component> lines = new ArrayList<>();
		for (String key : NarrativeFileCatalog.require(file.id()).lineKeys()) lines.add(Component.translatable(key));
		return List.copyOf(lines);
	}

	private static List<Component> damagedFileContent(String fileId) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("terminal.thefourthfrequency.file.damaged.notice")
				.withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC));
		for (int index = 1; index <= 3; index++) {
			Component readable = Component.translatable("terminal.thefourthfrequency.file." + fileId
					+ ".readable." + index).withStyle(Style.EMPTY
					.withColor(ChatFormatting.GRAY).withObfuscated(false));
			String resolved = readable.getString();
			int readableCodePoints = resolved.codePointCount(0, resolved.length());
			int prefixMask = readableCodePoints / 2;
			int suffixMask = readableCodePoints - prefixMask;
			Component line = Component.empty()
					.append(damagedMask(prefixMask))
					.append(Component.literal(" "))
					.append(readable)
					.append(Component.literal(" "))
					.append(damagedMask(suffixMask));
			lines.add(line);
		}
		return List.copyOf(lines);
	}

	private static Component damagedFileTitle(String fileId) {
		int fileIndex = HiddenFilePolicy.indexOf(fileId);
		String title = Component.translatable(NarrativeFileCatalog.require(fileId).titleKey()).getString();
		int codePoints = title.codePointCount(0, title.length());
		int requestedMask = fileIndex % 2 == 0 ? 2 : 3;
		int maskCount = Math.clamp(requestedMask, 1, Math.max(1, codePoints - 2));
		int maskStart = Math.max(0, (codePoints - maskCount) / 2);
		int prefixEnd = title.offsetByCodePoints(0, maskStart);
		int suffixStart = title.offsetByCodePoints(prefixEnd, maskCount);
		return Component.empty()
				.append(Component.literal(title.substring(0, prefixEnd)))
				.append(damagedMask(maskCount))
				.append(Component.literal(title.substring(suffixStart)))
				.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
	}

	private static Component damagedMask(int codePoints) {
		return Component.literal("x".repeat(Math.max(0, codePoints))).withStyle(Style.EMPTY
				.withColor(ChatFormatting.DARK_GRAY).withObfuscated(true));
	}

	// The surface these were authored for finally exists. {@code anomalyType} is what the records page
	// draws for every backfilled entry; {@code anomalySummary} is still on no render path, because it
	// is written as a specification rather than in the terminal's voice. It stays as the only handle
	// on those strings, and as the thing to rewrite if the page ever grows a detail view.
	public Component anomalyType(TerminalLogEntryPayload entry) {
		return Component.translatable("terminal.thefourthfrequency.log.type." + entry.type());
	}

	public Component anomalySummary(TerminalLogEntryPayload entry) {
		return Component.translatable("terminal.thefourthfrequency.log.summary." + entry.type(), entry.variant());
	}

	public List<Component> archive() {
		List<Component> lines = new ArrayList<>();
		for (ArchiveSection section : archiveSections()) lines.addAll(section.lines());
		return lines;
	}

	public List<ArchiveSection> archiveSections() {
		List<ArchiveSection> sections = new ArrayList<>();
		List<Component> localFile = new ArrayList<>();
		if (payload.localFileUnlocked()) {
			WitnessArchive file = WitnessArchive.get();
			localFile.add(Component.translatable("terminal.thefourthfrequency.archive.file_identity", file.version(), file.contentHash()));
			for (String lineKey : file.lineKeys()) localFile.add(Component.translatable(lineKey));
			if (portalTransitions() >= 2) {
				localFile.add(Component.translatable("text.thefourthfrequency.archive.line.continuation.return"));
			} else if (portalTransitions() >= 1) {
				localFile.add(Component.translatable("text.thefourthfrequency.archive.line.continuation.nether"));
			} else {
				localFile.add(Component.translatable("text.thefourthfrequency.archive.line.continuation.pending"));
			}
			sections.add(new ArchiveSection(
					Component.translatable("terminal.thefourthfrequency.ui.archive.section.local_file"), localFile));
		}
		if (sections.isEmpty()) {
			sections.add(new ArchiveSection(Component.empty(),
					List.of(Component.translatable("terminal.thefourthfrequency.archive.empty"))));
		}
		return sections;
	}

	public record ArchiveSection(Component title, List<Component> lines) {
	}

	/** The bearing wording: absolute when there is a compass beside it, relative when there is not. */
	private static Component bearing(int dx, int dz, Float viewYaw) {
		if (viewYaw == null) {
			return Component.translatable("terminal.thefourthfrequency.direction." + direction(dx, dz));
		}
		return Component.translatable("terminal.thefourthfrequency.relative_octant."
				+ TerminalNavigationMath.relativeOctantId(
						TerminalNavigationMath.relativeOctant(dx, dz, viewYaw)));
	}

	private static String direction(int dx, int dz) {
		return TerminalNavigationMath.direction(dx, dz);
	}

	private static int distance(int dx, int dz) {
		return TerminalNavigationMath.distance(dx, dz);
	}

}
