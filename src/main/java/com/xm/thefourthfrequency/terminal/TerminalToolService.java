package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.world.FragmentInvestigationService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.MineralSurveyPolicy;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.StoryProgressService;
import com.xm.thefourthfrequency.world.StructureNavigationService;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.Level;

public final class TerminalToolService {
	public static final int NO_TOOL = 6;
	private static boolean initialized;

	private TerminalToolService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
				TerminalToolService::recordPortalArrival);
	}

	private static void recordPortalArrival(ServerPlayer player, ServerLevel origin, ServerLevel destination) {
		boolean enteringNether = origin.dimension() == Level.OVERWORLD
				&& destination.dimension() == Level.NETHER;
		boolean returningOverworld = origin.dimension() == Level.NETHER
				&& destination.dimension() == Level.OVERWORLD;
		if (!enteringNether && !returningOverworld) return;
		FrequencyWorldData data = FrequencyWorldData.get(destination.getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		BlockPos arrival = player.blockPosition();
		String dimension = destination.dimension().identifier().toString();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putLong(TerminalData.LAST_PORTAL_POSITION, arrival.asLong());
			tag.putString(TerminalData.LAST_PORTAL_DIMENSION, dimension);
			StructureNavigationService.clearStructureTargetOutsideDimension(
					tag, dimension, destination.getGameTime());
		});
		SurvivalProgressService.recordPortalTransition(player, origin, destination);
		TerminalRuntimeService.refresh(player);
	}

	public static void recordEyeSample(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		BlockPos sample = player.blockPosition();
		BlockPos stronghold = player.level().findNearestMapStructure(
				StructureTags.EYE_OF_ENDER_LOCATED, sample, 100, false);
		if (stronghold == null) return;
		String dimension = player.level().dimension().identifier().toString();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putInt(TerminalData.EYE_SAMPLE_COUNT,
					Math.clamp(tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0) + 1, 0, 64));
			tag.putLong(TerminalData.STRONGHOLD_POSITION, stronghold.asLong());
			tag.putString(TerminalData.STRONGHOLD_DIMENSION, dimension);
			tag.putLongArray(TerminalData.STRONGHOLD_SAMPLE_POSITIONS,
					appendVantage(tag.getLongArray(TerminalData.STRONGHOLD_SAMPLE_POSITIONS).orElse(new long[0]),
							sample.asLong()));
		});
		SurvivalProgressService.mark(player, SurvivalMilestone.THREW_EYE);
		// A terminal that has a bearing has stopped needing to pretend it never recorded anything, so
		// this is where the quarantined anomaly log is handed to the records page. One-way, and it
		// checks its own latch, so calling it on every throw is correct rather than merely harmless.
		TerminalAnomalyLogService.releaseBackfillIfDue(player);
		TerminalRuntimeService.refresh(player);
		shareStrongholdFix(player, data);
	}

	/**
	 * Hands a completed stronghold fix to everyone else who is bound and online.
	 *
	 * <p>There is one stronghold. Triangulating it is a thing the party does once, and the eyes it
	 * costs come out of the same pile as the twelve the portal frame needs - so requiring each player
	 * to throw their own three meant a table of four spent twelve extra pearls establishing a fact
	 * they were all standing next to each other for. The throw-count objective was retired for
	 * exactly this reason; this closes the other half, where the objective was gone but the tool
	 * still would not open.
	 *
	 * <p>Only ever raises. A player who has thrown more than the sharer keeps their own sharper fix,
	 * and a player who has already been given one is not overwritten by a later, coarser share.
	 *
	 * <p>The vantage points come with it, because {@code strongholdPrecision} reads their spread: a
	 * shared fix without them would claim the accuracy of a single throw and the tool would draw a
	 * wider cone than the party has actually earned.
	 */
	private static void shareStrongholdFix(ServerPlayer discoverer, FrequencyWorldData data) {
		CompoundTag source = data.terminalRecord(discoverer.getUUID()).orElse(null);
		if (source == null) return;
		int samples = source.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0);
		if (samples < SurvivalProgressService.REQUIRED_EYE_SAMPLES) return;
		long position = source.getLongOr(TerminalData.STRONGHOLD_POSITION, 0L);
		String dimension = source.getStringOr(TerminalData.STRONGHOLD_DIMENSION, "");
		long[] vantages = source.getLongArray(TerminalData.STRONGHOLD_SAMPLE_POSITIONS).orElse(new long[0]);
		if (dimension.isBlank()) return;
		for (ServerPlayer other : discoverer.level().getServer().getPlayerList().getPlayers()) {
			if (other.getUUID().equals(discoverer.getUUID())) continue;
			CompoundTag record = data.terminalRecord(other.getUUID()).orElse(null);
			if (record == null || !record.getBooleanOr(TerminalData.BOUND, false)) continue;
			if (record.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0) >= samples) continue;
			data.updateTerminalRecord(other.getUUID(), tag -> {
				tag.putInt(TerminalData.EYE_SAMPLE_COUNT, samples);
				tag.putLong(TerminalData.STRONGHOLD_POSITION, position);
				tag.putString(TerminalData.STRONGHOLD_DIMENSION, dimension);
				tag.putLongArray(TerminalData.STRONGHOLD_SAMPLE_POSITIONS, vantages.clone());
			});
			SurvivalProgressService.mark(other, SurvivalMilestone.THREW_EYE);
			// Same statement as the thrower's: this terminal has a bearing now. Which of them paid for
			// the pearls is not what the release is about.
			TerminalAnomalyLogService.releaseBackfillIfDue(other);
			TerminalRuntimeService.refresh(other);
		}
	}

	/** How sharp the stronghold fix currently is, from the throws and where they were taken. */
	public static NavigationConvergencePolicy.Precision strongholdPrecision(CompoundTag tag, int samples) {
		return NavigationConvergencePolicy.precision(samples, NavigationConvergencePolicy.spreadBlocks(
				tag.getLongArray(TerminalData.STRONGHOLD_SAMPLE_POSITIONS).orElse(new long[0])));
	}

	/**
	 * Keeps the vantage points a fix is triangulated from, newest last and bounded.
	 *
	 * <p>Bounded because only the widest gap between any two of them is ever read, and a player who
	 * throws forty eyes does not widen that by keeping all forty. Sixteen is far more than the two
	 * that matter and small enough to be free to scan.
	 */
	private static long[] appendVantage(long[] existing, long sample) {
		long[] source = existing == null ? new long[0] : existing;
		for (long held : source) if (held == sample) return source;
		int size = Math.min(source.length + 1, 16);
		long[] next = new long[size];
		System.arraycopy(source, Math.max(0, source.length - (size - 1)), next, 0, size - 1);
		next[size - 1] = sample;
		return next;
	}

	public static TerminalToolSnapshotPayload snapshot(ServerPlayer player, int selectedTool) {
		return snapshot(player, selectedTool, TerminalControlPolicy.DEFAULT_TUNING, 0);
	}

	public static TerminalToolSnapshotPayload snapshot(ServerPlayer player, int selectedTool,
			int receiverTuning, int receiverLockTicks) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag tag = data.terminalRecord(player.getUUID()).orElse(new CompoundTag());
		long now = player.level().getGameTime();
		int available = availableToolsMask(player, tag);
		int safeSelected = validToolWire(selectedTool) && (available & 1 << selectedTool) != 0
				? selectedTool : NO_TOOL;
		int guidance = guidanceTool(tag);
		if (!validToolWire(guidance) || (available & 1 << guidance) == 0) guidance = NO_TOOL;
		boolean disabled = toolsDisabled(tag, now);
		int disabledTicks = disabled ? (int) Math.clamp(
				tag.getLongOr(TerminalData.TOOLS_DISABLED_UNTIL, 0L) - now, 0L, 72_000L) : 0;
		long dayTime = Math.floorMod(player.level().getDayTime(), 24_000L);
		int untilLightChange = ticksUntilLightChange(dayTime);
		Location home = home(player, tag);
		Location portal = storedLocation(tag, TerminalData.LAST_PORTAL_POSITION, TerminalData.LAST_PORTAL_DIMENSION);
		StrongholdEstimate stronghold = strongholdEstimate(player, tag);
		int weather = player.level().isThundering() ? 2 : player.level().isRaining() ? 1 : 0;
		var nearby = FragmentInvestigationService.nearby(player).orElse(null);
		int nearbySignals = nearby == null ? 0 : 1;
		boolean receiverAvailable = nearby != null && !disabled
				&& tag.getIntOr(TerminalData.BAND_STAGE, 0) > 0;
		int receiverTarget = receiverAvailable ? nearby.tuning() : 0;
		int receiverStrength = receiverAvailable
				? TerminalControlPolicy.receiverStrength(receiverTuning, receiverTarget) : 0;
		int specialFiles = TerminalFileState.states(tag).size();
		int storySignals = (int) TerminalSignalLog.entries(tag).stream()
				.filter(entry -> entry.band() == SignalBand.UNKNOWN
						|| entry.type().startsWith("fragment_"))
				.count();
		BlockPos playerPos = player.blockPosition();
		RelativeLocation homeRelative = relative(player, playerPos, home);
		RelativeLocation portalRelative = relative(player, playerPos, portal);
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		int hintTier = StoryProgressService.guidanceHintTier(tag);
		int resourceMask = TerminalGuidancePolicy.availableResourcesMask(milestones, hintTier);
		int navigationTargets = StructureNavigationService.availableTargetsMask(player, tag);
		TerminalStructureTarget selectedNavigation = StructureNavigationService.selectedTarget(tag);
		NavigationState navigation = NavigationState.read(tag);
		if (selectedNavigation != TerminalStructureTarget.NONE && navigation.located()
				&& navigation.dimension().equals(player.level().dimension().identifier().toString()))
			navigationTargets |= TerminalStructureTarget.bit(selectedNavigation);
		boolean unstableSignal = unstableSignalAvailable(player, tag);
		long mineralScanReady = tag.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L);
		int mineralScanTicks = mineralScanReady == 0L ? 0 : (int) Math.clamp(
				Math.max(1L, mineralScanReady - now), 1L, MineralSurveyPolicy.PROBE_REVEAL_TICKS);
		MineralSurveyPolicy.ChargeState charges = probeCharges(tag, now);
		int mineralReadingKind = tag.getIntOr(TerminalData.MINERAL_READING_KIND,
				ResourceGuidanceService.READING_NONE);
		boolean readingHere = tag.getStringOr(TerminalData.MINERAL_READING_DIMENSION, "")
				.equals(player.level().dimension().identifier().toString());
		if (!readingHere) mineralReadingKind = ResourceGuidanceService.READING_NONE;
		boolean mineralSurveyNearby = tag.getBooleanOr(TerminalData.MINERAL_SURVEY_NEARBY, false)
				&& !disabled && (available & bit(TerminalTool.MINERALS)) != 0;
		// Retired: arrival now speaks once through the notice stack instead of leaving a card.
		boolean navigationCompletion = false;
		int navigationCompletionDirection = 0;
		String objective = StoryProgressService.objective(tag, data).id();
		TerminalGuidancePolicy.Recommendations recommendations = TerminalGuidancePolicy.recommendations(
				objective, available, guidance, home.known(), dayTime, disabled, hintTier);
		int recommendedPrimary = recommendations.primary();
		int recommendedSecondary = recommendations.secondary();
		if (mineralSurveyNearby) {
			if (recommendedPrimary != TerminalTool.MINERALS.slot()) {
				recommendedSecondary = recommendedPrimary;
				recommendedPrimary = TerminalTool.MINERALS.slot();
			}
			if (recommendedSecondary == recommendedPrimary) recommendedSecondary = NO_TOOL;
		}
		return new TerminalToolSnapshotPayload(
				TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				available,
				safeSelected,
				guidance,
				recommendedPrimary,
				recommendedSecondary,
				resourceMask,
				navigationTargets,
				selectedNavigation.wireId(),
				unstableSignal,
				disabled,
				disabledTicks,
				selectedResource(tag).wireId(),
				mineralScanTicks,
				charges.charges(),
				MineralSurveyPolicy.rechargeTicksRemaining(charges, now),
				mineralReadingKind,
				mineralReadingKind == ResourceGuidanceService.READING_BEARING
						? tag.getIntOr(TerminalData.MINERAL_READING_DX, 0) : 0,
				mineralReadingKind == ResourceGuidanceService.READING_BEARING
						? tag.getIntOr(TerminalData.MINERAL_READING_DZ, 0) : 0,
				Math.max(0, tag.getIntOr(TerminalData.MINERAL_READING_MIN_DISTANCE, 0)),
				Math.max(0, tag.getIntOr(TerminalData.MINERAL_READING_MAX_DISTANCE, 0)),
				mineralSurveyNearby,
				navigationCompletion,
				navigationCompletionDirection,
				weather,
				dayTime,
				untilLightChange,
				playerPos.getY(),
				home.known(),
				home.bed(),
				homeRelative.sameDimension(),
				homeRelative.dx(),
				homeRelative.dz(),
				home.position().getY(),
				home.dimension(),
				portal.known(),
				portalRelative.sameDimension(),
				portalRelative.dx(),
				portalRelative.dz(),
				portal.position().getY(),
				portal.dimension(),
				nearbySignals,
				receiverAvailable,
				receiverTarget,
				receiverStrength,
				Math.clamp(receiverLockTicks, 0, 20),
				specialFiles,
				storySignals,
				Math.clamp(tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0), 0, 64),
				stronghold.known(),
				stronghold.sameDimension(),
				stronghold.dx(),
				stronghold.dz(),
				stronghold.dimension(),
				stronghold.minimumDistance(),
				stronghold.maximumDistance());
	}

	public static boolean selectTool(ServerPlayer player, int tool) {
		if (tool == NO_TOOL) return true;
		if (!validToolWire(tool)) return false;
		CompoundTag tag = record(player);
		return tag != null && (availableToolsMask(player, tag) & 1 << tool) != 0;
	}

	public static boolean selectResource(ServerPlayer player, int value) {
		return false;
	}

	/**
	 * Spends one probe charge and arms a reading.
	 *
	 * <p>Nothing is rolled here any more. The terminal no longer decides what the player is looking
	 * for - {@code ResourceGuidanceService} reads it off the surrounding ground - and there is no
	 * failure chance, because a failure that costs nothing is not a difficulty, it is an invitation
	 * to press again. The charge is the whole cost, and it is why the probe cannot be farmed.</p>
	 */
	public static boolean requestRescan(ServerPlayer player) {
		CompoundTag tag = record(player);
		if (tag == null || blockedByCorrection(player, tag)
				|| (availableToolsMask(player, tag) & bit(TerminalTool.MINERALS)) == 0) return false;
		long now = player.level().getGameTime();
		if (tag.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L) != 0L) return false;
		MineralSurveyPolicy.ChargeState charges = probeCharges(tag, now);
		if (charges.charges() <= 0) return false;
		MineralSurveyPolicy.ChargeState spent = MineralSurveyPolicy.spend(charges, now);
		FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), record -> {
			StructureNavigationService.clearCompletion(record);
			ResourceGuidanceService.clearReading(record);
			record.putInt(TerminalData.MINERAL_PROBE_CHARGES, spent.charges());
			record.putLong(TerminalData.MINERAL_PROBE_RECHARGE_TICK, spent.nextRechargeTick());
			record.putLong(TerminalData.MINERAL_SCAN_READY_GAME_TIME,
					now + MineralSurveyPolicy.PROBE_REVEAL_TICKS);
			if (guidanceTool(record) == TerminalTool.MINERALS.slot())
				record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, NO_TOOL);
			new NavigationState(ResourceGuidanceService.PROBE_KIND, "", false, "", 0L, "", now)
					.writeTo(record);
		});
		ResourceGuidanceService.abandonProbe(player);
		TerminalRuntimeService.synchronizeProjection(player);
		return true;
	}

	/** Resolves the stored charge bank forward to {@code now} without writing it back. */
	public static MineralSurveyPolicy.ChargeState probeCharges(CompoundTag tag, long now) {
		return MineralSurveyPolicy.charges(
				tag.getIntOr(TerminalData.MINERAL_PROBE_CHARGES, MineralSurveyPolicy.MAX_PROBE_CHARGES),
				tag.getLongOr(TerminalData.MINERAL_PROBE_RECHARGE_TICK, 0L), now);
	}

	public static boolean setHome(ServerPlayer player) {
		return false;
	}

	public static boolean startGuidance(ServerPlayer player, int toolValue) {
		if (!validToolWire(toolValue) || toolValue == TerminalTool.WEATHER.slot()) return false;
		TerminalTool tool = TerminalTool.fromSlot(toolValue);
		CompoundTag tag = record(player);
		if (tool == null || tag == null || blockedByCorrection(player, tag)
				|| (availableToolsMask(player, tag) & 1 << toolValue) == 0) {
			return false;
		}
		// No survey-target promotion here any more: the passive survey now writes the same exact
		// reading a probe does, so by the time this runs the target is already a located one.
		if (!hasGuidanceTarget(player, tag, tool)) return false;
		FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), record -> {
			StructureNavigationService.clearCompletion(record);
			record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, toolValue);
			if (tool == TerminalTool.MINERALS)
				ResourceGuidanceService.clearAutomaticSurveyState(record);
		});
		TerminalRuntimeService.synchronizeProjection(player);
		return true;
	}

	public static boolean stopGuidance(ServerPlayer player, int value) {
		if (value != 0 || record(player) == null) return false;
		FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), record ->
				record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, NO_TOOL));
		return true;
	}

	public static int guidanceTool(CompoundTag tag) {
		int value = tag.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, NO_TOOL);
		return validToolWire(value) || value == NO_TOOL ? value : NO_TOOL;
	}

	public static boolean toolsDisabled(CompoundTag tag, long now) {
		return tag.getLongOr(TerminalData.TOOLS_DISABLED_UNTIL, 0L) > now;
	}

	private static boolean blockedByCorrection(ServerPlayer player, CompoundTag tag) {
		if (!toolsDisabled(tag, player.level().getGameTime())) return false;
		FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), record ->
				record.putInt(TerminalData.BREACH_MASK, record.getIntOr(TerminalData.BREACH_MASK, 0) | 1));
		return true;
	}

	public static Location guidanceLocation(ServerPlayer player, CompoundTag tag, TerminalTool tool) {
		return switch (tool) {
			case HOME -> home(player, tag);
			case PORTAL -> storedLocation(tag, TerminalData.LAST_PORTAL_POSITION, TerminalData.LAST_PORTAL_DIMENSION);
			default -> Location.unknown();
		};
	}

	public static StrongholdEstimate strongholdEstimate(ServerPlayer player, CompoundTag tag) {
		int samples = Math.clamp(tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0), 0, 64);
		String dimension = tag.getStringOr(TerminalData.STRONGHOLD_DIMENSION, "");
		if (samples < SurvivalProgressService.REQUIRED_EYE_SAMPLES || dimension.isBlank())
			return StrongholdEstimate.unknown();
		BlockPos target = BlockPos.of(tag.getLongOr(TerminalData.STRONGHOLD_POSITION, 0L));
		boolean sameDimension = dimension.equals(player.level().dimension().identifier().toString());
		if (!sameDimension) return new StrongholdEstimate(true, false, 0, 0, dimension, 0, 0);
		BlockPos origin = player.blockPosition();
		int exactDx = target.getX() - origin.getX();
		int exactDz = target.getZ() - origin.getZ();
		double exactDistance = Math.sqrt((double) exactDx * exactDx + (double) exactDz * exactDz);
		// The fix narrows instead of switching.
		//
		// This used to be two states: under three throws you got 512 blocks of slop and 45-degree
		// bearings, at three you got 128 and 22.5, and nothing in between. Both halves were wrong in
		// the same direction - the coarse state was too coarse to walk on, the sharp state arrived as
		// a jump, and neither cared *where* the throws were taken from. A player could stand in one
		// doorway and throw three eyes, which in this game's own fiction is one observation recorded
		// three times, and be handed the tighter answer for it.
		NavigationConvergencePolicy.Precision precision = strongholdPrecision(tag, samples);
		int uncertainty = precision.uncertaintyBlocks();
		int minimum = Math.max(0, (int) Math.floor(exactDistance) - uncertainty);
		int maximum = Math.min(30_000_000, (int) Math.ceil(exactDistance) + uncertainty);
		double step = Math.toRadians(precision.angleStepDegrees());
		double angle = Math.atan2(exactDz, exactDx);
		double estimatedAngle = Math.round(angle / step) * step;
		int dx = (int) Math.round(Math.cos(estimatedAngle) * 100.0D);
		int dz = (int) Math.round(Math.sin(estimatedAngle) * 100.0D);
		return new StrongholdEstimate(true, true, dx, dz, dimension, minimum, maximum);
	}

	public static int availableToolsMask(ServerPlayer player, CompoundTag tag) {
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		boolean portalKnown = !tag.getStringOr(TerminalData.LAST_PORTAL_DIMENSION, "").isBlank();
		int obtainedEyeCount = Math.max(tag.getIntOr(TerminalData.CRAFTED_EYE_COUNT, 0),
				SurvivalProgressService.craftedEyeSamples(player));
		return TerminalGuidancePolicy.availableToolsMask(milestones, portalKnown, obtainedEyeCount);
	}

	public static int availableResourcesMask(CompoundTag tag) {
		return TerminalGuidancePolicy.availableResourcesMask(
				tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0),
				StoryProgressService.guidanceHintTier(tag));
	}

	public static boolean resourceAvailable(CompoundTag tag, TerminalResource resource) {
		return TerminalGuidancePolicy.resourceAvailable(availableResourcesMask(tag), resource);
	}

	/**
	 * Whether the navigation page draws the unstable-signal option.
	 *
	 * <p>Delegates rather than deciding. This had its own copy of the rule and the click handler had
	 * another, and the two drifted far enough apart that the page could draw an option the click
	 * refused - see {@code FragmentInvestigationService.investigationOffered}, which is now the only
	 * place the question is answered.
	 */
	public static boolean unstableSignalAvailable(ServerPlayer player, CompoundTag tag) {
		return FragmentInvestigationService.investigationOffered(player);
	}

	private static boolean hasGuidanceTarget(ServerPlayer player, CompoundTag tag, TerminalTool tool) {
		return switch (tool) {
			case HOME -> home(player, tag).known();
			case MINERALS -> {
				TerminalResource selected = selectedResource(tag);
				NavigationState navigation = NavigationState.read(tag);
				yield selected != TerminalResource.NONE && navigation.kind().equals(selected.id())
						&& navigation.located();
			}
			case PORTAL -> !tag.getStringOr(TerminalData.LAST_PORTAL_DIMENSION, "").isBlank();
			case NAVIGATION -> {
				NavigationState navigation = NavigationState.read(tag);
				yield (navigation.kind().equals("structure_fragment")
						|| TerminalStructureTarget.fromId(navigation.kind()) != TerminalStructureTarget.NONE)
						&& navigation.located();
			}
			case STRONGHOLD -> tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0)
					>= SurvivalProgressService.REQUIRED_EYE_SAMPLES
					&& !tag.getStringOr(TerminalData.STRONGHOLD_DIMENSION, "").isBlank();
			case WEATHER -> false;
		};
	}

	private static Location home(ServerPlayer player, CompoundTag tag) {
		ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
		if (respawn != null) {
			return new Location(true, true, respawn.respawnData().pos(),
					respawn.respawnData().dimension().identifier().toString());
		}
		return Location.unknown();
	}

	private static Location storedLocation(CompoundTag tag, String positionKey, String dimensionKey) {
		String dimension = tag.getStringOr(dimensionKey, "");
		return dimension.isBlank() ? Location.unknown()
				: new Location(true, false, BlockPos.of(tag.getLongOr(positionKey, 0L)), dimension);
	}

	private static RelativeLocation relative(ServerPlayer player, BlockPos origin, Location location) {
		if (!location.known()) return RelativeLocation.unknown();
		boolean same = location.dimension().equals(player.level().dimension().identifier().toString());
		return new RelativeLocation(same,
				same ? boundedDelta(location.position().getX() - origin.getX()) : 0,
				same ? boundedDelta(location.position().getZ() - origin.getZ()) : 0);
	}

	private static TerminalResource selectedResource(CompoundTag tag) {
		TerminalResource explicit = TerminalResource.fromWire(tag.getIntOr(TerminalData.SELECTED_RESOURCE, 3));
		return explicit != TerminalResource.NONE ? explicit
				: TerminalResource.fromId(NavigationState.read(tag).kind());
	}

	private static CompoundTag record(ServerPlayer player) {
		return FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID()).orElse(null);
	}

	private static String resourceItem(TerminalResource resource) {
		return ResourceGuidanceService.resourceItem(resource);
	}

	private static int ticksUntilLightChange(long dayTime) {
		if (dayTime < 13_000L) return (int) (13_000L - dayTime);
		if (dayTime < 23_000L) return (int) (23_000L - dayTime);
		return (int) (37_000L - dayTime);
	}

	private static int bit(TerminalTool tool) {
		return 1 << tool.slot();
	}

	private static boolean validToolWire(int value) {
		return value >= TerminalTool.HOME.slot() && value <= TerminalTool.STRONGHOLD.slot();
	}

	private static int boundedDelta(int value) {
		return Math.clamp(value, -30_000_000, 30_000_000);
	}

	public record Location(boolean known, boolean bed, BlockPos position, String dimension) {
		private static Location unknown() {
			return new Location(false, false, BlockPos.ZERO, "");
		}
	}

	private record RelativeLocation(boolean sameDimension, int dx, int dz) {
		private static RelativeLocation unknown() {
			return new RelativeLocation(false, 0, 0);
		}
	}

	public record StrongholdEstimate(boolean known, boolean sameDimension, int dx, int dz,
			String dimension, int minimumDistance, int maximumDistance) {
		private static StrongholdEstimate unknown() {
			return new StrongholdEstimate(false, false, 0, 0, "", 0, 0);
		}
	}
}
