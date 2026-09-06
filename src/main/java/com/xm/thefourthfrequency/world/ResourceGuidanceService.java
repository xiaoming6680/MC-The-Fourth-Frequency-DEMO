package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.state.PlayerPatternState;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns both mineral readings: the passive proximity survey, and the requested probe.
 *
 * <p>The probe listens to the ground the player is standing in rather than being told what to look
 * for. That is what makes it un-farmable: the old flow rolled a category first and then went
 * looking for it at unlimited range, so a free retry was a re-roll and enough retries produced a
 * diamond anywhere. Here the sweep reports whatever is genuinely within each ore's own radius, and
 * pressing costs a charge, so the answer depends on where the player is rather than how many times
 * they clicked.</p>
 */
public final class ResourceGuidanceService {
	/** Kind written to {@link NavigationState} while a requested probe is still resolving. */
	public static final String PROBE_KIND = "mineral_probe";
	public static final int READING_NONE = 0;
	public static final int READING_EXACT = 1;
	public static final int READING_BEARING = 2;
	/**
	 * A probe that completed and heard nothing.
	 *
	 * <p>Distinct from {@link #READING_NONE} because "there is no ore within range of you" is an
	 * answer the player paid a charge for, and showing it as an idle probe would read as the button
	 * having done nothing at all.</p>
	 */
	public static final int READING_EMPTY = 3;

	private static final int MAX_PLAYERS_PER_SERVER_TICK = 4;
	/**
	 * Blocks a single in-flight probe tests per tick.
	 *
	 * <p>Sized so the widest sweep - the full coal radius, which is only reached when nothing rarer
	 * is anywhere nearby - still lands inside the reveal window. A probe is now a charge-gated
	 * event a few times per ten minutes rather than a background scan, so a burst budget is the
	 * right shape.</p>
	 */
	private static final int PROBE_BLOCKS_PER_TICK = 3_072;
	/** Backstop for a sweep held up by an unloaded region or by many players sharing the budget. */
	private static final long PROBE_GRACE_TICKS = 300L;
	private static final long AUTO_SCAN_RESET_TICKS = 100L;
	private static final int MAX_PROBE_RADIUS = 32;
	/** Offsets are biased into an unsigned field; the widest radius must stay inside seven bits. */
	private static final int OFFSET_BIAS = 64;
	private static final int OFFSET_MASK = 0x7F;
	private static final int DISTANCE_SHIFT = 21;
	/**
	 * Every offset inside the widest probe radius, ordered nearest first.
	 *
	 * <p>Packed into longs as {@code distanceSquared << 21 | x | y | z} rather than held as
	 * objects: a radius-32 sphere is a hundred and thirty thousand entries, which as records would
	 * be several megabytes resident for the whole session. Putting the squared distance in the
	 * high bits also means the natural {@code long} ordering is already the outward ordering, so
	 * the sort needs no comparator and the distance is readable without recomputing it.</p>
	 */
	private static final long[] PROBE_OFFSETS = createProbeOffsets(MAX_PROBE_RADIUS);
	private static final Map<MinecraftServer, Map<UUID, ProbeState>> ACTIVE_PROBES = new IdentityHashMap<>();
	private static final Map<MinecraftServer, Map<UUID, AutoScanState>> AUTO_SCANS = new IdentityHashMap<>();
	private static final Map<MinecraftServer, Integer> PLAYER_CURSORS = new IdentityHashMap<>();

	private ResourceGuidanceService() {
	}

	public static void initialize() {
		ServerTickEvents.END_SERVER_TICK.register(ResourceGuidanceService::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE_PROBES.remove(server);
			AUTO_SCANS.remove(server);
			PLAYER_CURSORS.remove(server);
		});
	}

	private static void onServerTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) return;
		int start = Math.floorMod(PLAYER_CURSORS.getOrDefault(server, 0), players.size());
		int count = Math.min(MAX_PLAYERS_PER_SERVER_TICK, players.size());
		for (int offset = 0; offset < count; offset++) {
			updatePlayer(players.get((start + offset) % players.size()));
		}
		PLAYER_CURSORS.put(server, (start + count) % players.size());
	}

	public static int maximumBlockChecksPerServerTick() {
		return PROBE_BLOCKS_PER_TICK * MAX_PLAYERS_PER_SERVER_TICK;
	}

	public static void updatePlayer(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		Map<UUID, ProbeState> probes = ACTIVE_PROBES.computeIfAbsent(server, ignored -> new HashMap<>());
		Map<UUID, AutoScanState> autoScans = AUTO_SCANS.computeIfAbsent(server, ignored -> new HashMap<>());
		FrequencyWorldData data = FrequencyWorldData.get(server);
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) {
			probes.remove(player.getUUID());
			autoScans.remove(player.getUUID());
			return;
		}
		boolean autoEligible = autoSurveyEligible(player, record);
		if (!autoEligible) {
			autoScans.remove(player.getUUID());
			clearAutoSurvey(player, data, record);
		}
		if (TerminalToolService.toolsDisabled(record, player.level().getGameTime())) {
			probes.remove(player.getUUID());
			return;
		}

		if (record.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L) != 0L) {
			advanceProbe(player, data, record, probes);
			return;
		}
		probes.remove(player.getUUID());

		recordAcceptedAdvice(player, data, record);
		if (maintainReading(player, data, record, autoScans)) return;

		if (!autoEligible) return;
		boolean hadSurveyState = autoSurveyStatePresent(record);
		if (autoSurveyEpisodeValid(player, data, record)) {
			autoScans.remove(player.getUUID());
			return;
		}
		if (hadSurveyState) autoScans.remove(player.getUUID());
		if (TerminalRuntimeService.isOpen(player)) return;
		updateAutoScan(player, data, autoScans);
	}

	/** Discards any reading and stops an in-flight probe; used when the tool is taken away. */
	public static void clearReading(CompoundTag record) {
		record.putInt(TerminalData.MINERAL_READING_KIND, READING_NONE);
		record.putInt(TerminalData.MINERAL_READING_DX, 0);
		record.putInt(TerminalData.MINERAL_READING_DZ, 0);
		record.putInt(TerminalData.MINERAL_READING_MIN_DISTANCE, 0);
		record.putInt(TerminalData.MINERAL_READING_MAX_DISTANCE, 0);
		record.putString(TerminalData.MINERAL_READING_DIMENSION, "");
		record.putInt(TerminalData.SELECTED_RESOURCE, TerminalResource.NONE.wireId());
		new NavigationState("unresolved", "", false, "", 0L, "",
				record.getLongOr(TerminalData.GUIDANCE_UPDATED_GAME_TIME, 0L)).writeTo(record);
	}

	public static void abandonProbe(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		ACTIVE_PROBES.computeIfAbsent(server, ignored -> new HashMap<>()).remove(player.getUUID());
	}

	/**
	 * Drops the finished passive sweep so the next update re-scans immediately.
	 *
	 * <p>A sweep that found nothing parks itself for {@link #AUTO_SCAN_RESET_TICKS} rather than
	 * re-reading the same five blocks every tick. Tests run inside a single tick and so can never
	 * wait that out; this is the one thing they need that the passage of time would otherwise
	 * provide.</p>
	 */
	public static void forgetAutoScanForTesting(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		AUTO_SCANS.computeIfAbsent(server, ignored -> new HashMap<>()).remove(player.getUUID());
	}

	/**
	 * Arms a probe and drives it to a reading inside one call.
	 *
	 * <p>Only the three-second reveal delay is skipped; the charge is really spent and the sweep is
	 * the real one, so a test still exercises the rules it is checking.</p>
	 */
	public static boolean probeForTesting(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		// An automatic survey may already be in flight. They are paused while the terminal is open, so
		// closing it after a long session releases one on the very next tick - and a rescan request is
		// refused while one is running. This entry point exists to place a deterministic reading, so
		// it clears whatever the world happened to start on its own instead of losing a race to it.
		data.updateTerminalRecord(player.getUUID(), record ->
				record.putLong(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L));
		abandonProbe(player);
		if (!TerminalToolService.requestRescan(player)) return false;
		data.updateTerminalRecord(player.getUUID(), record -> record.putLong(
				TerminalData.MINERAL_SCAN_READY_GAME_TIME, Math.max(1L, player.level().getGameTime())));
		for (int guard = 0; guard < 256; guard++) {
			updatePlayer(player);
			if (data.terminalRecord(player.getUUID())
					.map(record -> record.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L) == 0L)
					.orElse(true)) return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ requested probe

	private static void advanceProbe(ServerPlayer player, FrequencyWorldData data, CompoundTag record,
			Map<UUID, ProbeState> probes) {
		ServerLevel level = player.level();
		long now = level.getGameTime();
		long revealAt = record.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L);
		String dimension = level.dimension().identifier().toString();
		int unlocked = TerminalToolService.availableResourcesMask(record);

		ProbeState probe = probes.get(player.getUUID());
		// The origin is fixed for the whole probe: a reading is taken from where the button was
		// pressed, and re-seeding it as the player walks used to restart the sweep every tick.
		if (probe == null || !probe.dimension.equals(dimension) || probe.unlockedMask != unlocked) {
			probe = new ProbeState(player.blockPosition(), dimension, unlocked);
			probes.put(player.getUUID(), probe);
		}
		if (!probe.swept) sweep(level, probe);
		if (now < revealAt) return;
		if (!probe.swept && now < revealAt + PROBE_GRACE_TICKS) return;
		commitProbe(player, data, probe, now);
		probes.remove(player.getUUID());
	}

	private static void sweep(ServerLevel level, ProbeState probe) {
		for (int checked = 0; checked < PROBE_BLOCKS_PER_TICK; checked++) {
			if (probe.index >= PROBE_OFFSETS.length) {
				probe.swept = true;
				return;
			}
			long packed = PROBE_OFFSETS[probe.index];
			int distanceSquared = (int) (packed >>> DISTANCE_SHIFT);
			// Nothing rarer than what is already in hand can appear past this point, so the sweep
			// is done. With nothing found yet this is the widest unlocked radius, which is why one
			// rule covers both the opening ceiling and every later narrowing.
			int ceiling = MineralSurveyPolicy.rarerCeiling(probe.unlockedMask, probe.found);
			if (distanceSquared > ceiling * ceiling) {
				probe.swept = true;
				return;
			}
			probe.index++;
			BlockPos candidate = probe.mutable.setWithOffset(probe.origin,
					offsetComponent(packed, 14), offsetComponent(packed, 7), offsetComponent(packed, 0));
			if (level.isOutsideBuildHeight(candidate) || !level.hasChunkAt(candidate)) continue;
			Block block = level.getBlockState(candidate).getBlock();
			TerminalResource resource = surveyResource(block);
			if (!MineralSurveyPolicy.unlocked(probe.unlockedMask, resource)
					|| distanceSquared > squared(MineralSurveyPolicy.probeRadius(resource))) continue;
			String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
			// Two running maxima over one walk. The offsets are ordered by distance, so the first hit
			// of any ore is already its nearest instance and neither branch ever needs to look back.
			if (MineralSurveyPolicy.reportPriority(resource)
					> MineralSurveyPolicy.reportPriority(probe.found)) {
				probe.found = resource;
				probe.foundPosition = candidate.immutable();
				probe.foundBlockId = blockId;
			}
			if (MineralSurveyPolicy.exactReading(resource, offsetComponent(packed, 14),
					offsetComponent(packed, 7), offsetComponent(packed, 0))
					&& MineralSurveyPolicy.reportPriority(resource)
							> MineralSurveyPolicy.reportPriority(probe.nameable)) {
				probe.nameable = resource;
				probe.nameablePosition = candidate.immutable();
				probe.nameableBlockId = blockId;
			}
		}
	}

	/**
	 * Reports a hit it can name over a rarer one it can only gesture at.
	 *
	 * <p>The probe used to answer with the rarest ore in range, full stop, and the sweep upgrades to
	 * anything rarer at any distance. So coal three blocks under the player's feet was routinely
	 * thrown away in favour of diamond fifteen blocks off - which is outside diamond's exact radius,
	 * so what came back was a bearing and a distance band. <b>That is why pressing the probe so often
	 * produced only a rough direction: the tool was systematically discarding the one hit it could
	 * have given an address for.</b>
	 *
	 * <p>Rarity still orders everything within each of the two answers, and the rare bearing is still
	 * what comes back when nothing is close enough to name. What changed is that "I can tell you
	 * exactly where this is" now outranks "something rarer is somewhere over there" - which is also
	 * the more honest instrument, since one of those is a reading and the other is a guess with a
	 * percentage attached.
	 */
	private static void commitProbe(ServerPlayer player, FrequencyWorldData data, ProbeState probe, long now) {
		boolean preferNameable = probe.nameable != TerminalResource.NONE && probe.nameablePosition != null;
		TerminalResource resource = preferNameable ? probe.nameable : probe.found;
		BlockPos found = preferNameable ? probe.nameablePosition : probe.foundPosition;
		String blockId = preferNameable ? probe.nameableBlockId : probe.foundBlockId;
		String dimension = probe.dimension;
		BlockPos origin = probe.origin;
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putLong(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L);
			if (resource == TerminalResource.NONE || found == null) {
				clearReading(record);
				if (forgeEmptyResult(player, record, dimension, origin, now)) return;
				record.putInt(TerminalData.MINERAL_READING_KIND, READING_EMPTY);
				record.putString(TerminalData.MINERAL_READING_DIMENSION, dimension);
				return;
			}
			record.putInt(TerminalData.SELECTED_RESOURCE, resource.wireId());
			record.putString(TerminalData.MINERAL_READING_DIMENSION, dimension);
			int dx = found.getX() - origin.getX();
			int dy = found.getY() - origin.getY();
			int dz = found.getZ() - origin.getZ();
			if (MineralSurveyPolicy.exactReading(resource, dx, dy, dz)) {
				record.putInt(TerminalData.MINERAL_READING_KIND, READING_EXACT);
				record.putInt(TerminalData.MINERAL_READING_DX, 0);
				record.putInt(TerminalData.MINERAL_READING_DZ, 0);
				record.putInt(TerminalData.MINERAL_READING_MIN_DISTANCE, 0);
				record.putInt(TerminalData.MINERAL_READING_MAX_DISTANCE, 0);
				new NavigationState(resource.id(), resourceItem(resource), true, blockId,
						found.asLong(), dimension, now).writeTo(record);
				return;
			}
			// Past the exact radius the terminal only reports where it heard something and roughly
			// how far, anchored to the spot the probe was taken from. It never becomes a waypoint.
			MineralSurveyPolicy.Bearing bearing = MineralSurveyPolicy.quantizeBearing(dx, dz);
			int distance = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
			record.putInt(TerminalData.MINERAL_READING_KIND, READING_BEARING);
			record.putInt(TerminalData.MINERAL_READING_DX, bearing.dx());
			record.putInt(TerminalData.MINERAL_READING_DZ, bearing.dz());
			record.putInt(TerminalData.MINERAL_READING_MIN_DISTANCE, MineralSurveyPolicy.bandMinimum(distance));
			record.putInt(TerminalData.MINERAL_READING_MAX_DISTANCE, MineralSurveyPolicy.bandMaximum(distance));
			new NavigationState(resource.id(), resourceItem(resource), false, "", 0L, dimension, now)
					.writeTo(record);
		});
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	/**
	 * The single forged reading, filed together with the line that gives it away.
	 *
	 * <p>Both halves are written inside the same record update. That is the whole safety property:
	 * there is no ordering in which the player can be shown a wrong reading that has no trace behind
	 * it, not even across a crash, because the two are one write.
	 *
	 * <p>The trace is filed unread on purpose. A badge would be the terminal pointing at its own lie,
	 * and being told is not the same experience as finding out - the player is meant to walk to the
	 * bearing, dig, find nothing, and only then go back and look.
	 *
	 * @return whether the empty result was replaced, in which case the caller must not write
	 *         {@code READING_EMPTY} over it
	 */
	private static boolean forgeEmptyResult(ServerPlayer player, CompoundTag record, String dimension,
			BlockPos origin, long now) {
		if (!MineralDeceptionPolicy.eligible(record.getIntOr(TerminalData.ANOMALY_TIER, 0),
				record.getBooleanOr(TerminalData.MINERAL_READING_FORGED, false), true)) return false;
		MineralDeceptionPolicy.Forgery forgery = MineralDeceptionPolicy.forge(
				player.getUUID().getLeastSignificantBits() ^ now);
		MineralSurveyPolicy.Bearing bearing = forgery.bearing();
		record.putBoolean(TerminalData.MINERAL_READING_FORGED, true);
		record.putInt(TerminalData.SELECTED_RESOURCE, forgery.resource().wireId());
		record.putString(TerminalData.MINERAL_READING_DIMENSION, dimension);
		record.putInt(TerminalData.MINERAL_READING_KIND, READING_BEARING);
		record.putInt(TerminalData.MINERAL_READING_DX, bearing.dx());
		record.putInt(TerminalData.MINERAL_READING_DZ, bearing.dz());
		record.putInt(TerminalData.MINERAL_READING_MIN_DISTANCE, forgery.bandMinimum());
		record.putInt(TerminalData.MINERAL_READING_MAX_DISTANCE, forgery.bandMaximum());
		// Exactly the shape an honest out-of-radius answer has, down to never becoming a waypoint.
		new NavigationState(forgery.resource().id(), resourceItem(forgery.resource()), false, "", 0L,
				dimension, now).writeTo(record);
		// variant carries what was displayed; the spare integer carries bearing and distance, which
		// is all the line needs to reprint the reading after it has been cleared from the tool.
		TerminalSignalLog.append(record, SignalBand.UNKNOWN, "mineral_reading_forged",
				now, player.level().getDayTime(), dimension, origin.asLong(),
				forgery.resource().wireId(),
				MineralDeceptionPolicy.packTrace(forgery.octant(), forgery.distance()), false);
		return true;
	}

	// ------------------------------------------------------------------ reading upkeep

	/** @return true when the reading resolved this tick and the rest of the update should stand down */
	private static boolean maintainReading(ServerPlayer player, FrequencyWorldData data, CompoundTag record,
			Map<UUID, AutoScanState> autoScans) {
		int kind = record.getIntOr(TerminalData.MINERAL_READING_KIND, READING_NONE);
		if (kind == READING_NONE) return false;
		String dimension = player.level().dimension().identifier().toString();
		if (!dimension.equals(record.getStringOr(TerminalData.MINERAL_READING_DIMENSION, ""))) {
			// A reading is a measurement of one place. Carrying it across a portal would make it a
			// lie, and re-taking it for free would give back the unlimited tool this replaced.
			discardReading(player, data, record, null);
			return true;
		}
		if (kind != READING_EXACT) return false;
		NavigationState navigation = NavigationState.read(record);
		if (!navigation.located()) return false;
		if (!targetStillPresent(player.level(), navigation)) {
			// The ore can go before the player ever reaches it - they mine into the vein from the
			// side, or someone else gets there first. Arrival is not the only way navigation ends,
			// and leaving it running would point the needle at a block that is no longer there.
			discardReading(player, data, record,
					Component.translatable("message.thefourthfrequency.navigation.mineral_mined"));
			return true;
		}
		return completeMineralNavigationIfArrived(player, data, record, autoScans);
	}

	/**
	 * Drops a reading that has stopped being true, and stops any navigation that was following it.
	 *
	 * @param notice announced only when navigation was actually running; a reading the player was
	 *               not being guided by ends quietly, because nothing visible to them changed
	 */
	private static void discardReading(ServerPlayer player, FrequencyWorldData data, CompoundTag record,
			Component notice) {
		boolean guiding = TerminalToolService.guidanceTool(record) == TerminalTool.MINERALS.slot();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			clearReading(tag);
			if (guiding) tag.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalToolService.NO_TOOL);
		});
		if (guiding && notice != null) {
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player, notice);
		}
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	private static boolean targetStillPresent(ServerLevel level, NavigationState navigation) {
		BlockPos target = BlockPos.of(navigation.position());
		if (!level.hasChunkAt(target)) return true;
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(target).getBlock()).toString()
				.equals(navigation.blockId());
	}

	private static boolean completeMineralNavigationIfArrived(ServerPlayer player, FrequencyWorldData data,
			CompoundTag record, Map<UUID, AutoScanState> autoScans) {
		if (TerminalToolService.guidanceTool(record) != TerminalTool.MINERALS.slot()) return false;
		NavigationState navigation = NavigationState.read(record);
		BlockPos target = BlockPos.of(navigation.position());
		BlockPos origin = player.blockPosition();
		if (!MineralSurveyPolicy.arrived(target.getX() - origin.getX(),
				target.getY() - origin.getY(), target.getZ() - origin.getZ())) return false;
		String dimension = navigation.dimension();
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalToolService.NO_TOOL);
			clearReading(tag);
			// Suppress immediate rediscovery of the ore that was just reached until the player
			// leaves its automatic-survey episode.
			tag.putBoolean(TerminalData.MINERAL_SURVEY_PROXIMITY, true);
			tag.putBoolean(TerminalData.MINERAL_SURVEY_NEARBY, false);
			tag.putLong(TerminalData.MINERAL_SURVEY_POSITION, target.asLong());
			tag.putString(TerminalData.MINERAL_SURVEY_DIMENSION, dimension);
		});
		autoScans.remove(player.getUUID());
		com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
				Component.translatable("message.thefourthfrequency.navigation.mineral_arrived"));
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
		return true;
	}

	private static void recordAcceptedAdvice(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		TerminalResource resource = TerminalResource.fromWire(
				record.getIntOr(TerminalData.SELECTED_RESOURCE, TerminalResource.NONE.wireId()));
		ResourceNeed need = ResourceNeed.byId(resource.id());
		if (need == null || !hasBindingResource(player, need)
				|| containsEntry(PlayerPatternState.read(record).acceptedAdvice(), need.id)) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			PlayerPatternState pattern = PlayerPatternState.read(tag);
			pattern.withAcceptedAdvice(appendEntry(pattern.acceptedAdvice(), need.id)).writeTo(tag);
		});
		TerminalLifecycleService.ensureCarried(player, false);
		// No notice: the terminal is the record, and "the terminal recorded it" says nothing more.
		TerminalRuntimeService.refresh(player);
	}

	// ------------------------------------------------------------------ passive survey

	private static boolean autoSurveyEligible(ServerPlayer player, CompoundTag record) {
		return player.isAlive() && !player.isSpectator() && !PrivateDimensions.isPrivate(player.level())
				&& !TerminalToolService.toolsDisabled(record, player.level().getGameTime())
				&& !guidingOffSharedNavigation(record)
				&& (TerminalToolService.availableToolsMask(player, record) & 1 << TerminalTool.MINERALS.slot()) != 0;
	}

	/**
	 * Whether some tool is currently following the navigation state this survey would overwrite.
	 *
	 * <p>{@link #scanAuto} writes a whole {@code NavigationState} on a hit, and more than one tool
	 * reads that state - see {@link TerminalTool#usesSharedNavigationState}. The survey is only
	 * allowed to run while the terminal is shut, which is precisely when somebody is walking
	 * somewhere, so anything it overwrites it overwrites mid-route.
	 */
	private static boolean guidingOffSharedNavigation(CompoundTag record) {
		TerminalTool guiding = TerminalTool.fromSlot(TerminalToolService.guidanceTool(record));
		return guiding != null && guiding.usesSharedNavigationState();
	}

	private static boolean autoSurveyStatePresent(CompoundTag record) {
		return record.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false)
				|| record.getBooleanOr(TerminalData.MINERAL_SURVEY_NEARBY, false)
				|| record.getLongOr(TerminalData.MINERAL_SURVEY_POSITION, 0L) != 0L
				|| !record.getStringOr(TerminalData.MINERAL_SURVEY_DIMENSION, "").isBlank();
	}

	private static boolean autoSurveyEpisodeValid(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		if (!record.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false)) {
			if (autoSurveyStatePresent(record)) clearAutoSurvey(player, data, record);
			return false;
		}
		String currentDimension = player.level().dimension().identifier().toString();
		String surveyDimension = record.getStringOr(TerminalData.MINERAL_SURVEY_DIMENSION, "");
		BlockPos target = BlockPos.of(record.getLongOr(TerminalData.MINERAL_SURVEY_POSITION, 0L));
		BlockPos origin = player.blockPosition();
		boolean valid = currentDimension.equals(surveyDimension)
				&& MineralSurveyPolicy.withinRange(
						target.getX() - origin.getX(), target.getY() - origin.getY(), target.getZ() - origin.getZ())
				&& surveyTargetStillPresent(player.level(), target);
		if (!valid) clearAutoSurvey(player, data, record);
		return valid;
	}

	private static boolean surveyTargetStillPresent(ServerLevel level, BlockPos target) {
		if (!level.hasChunkAt(target)) return true;
		return surveyResource(level.getBlockState(target).getBlock()) != TerminalResource.NONE;
	}

	private static void clearAutoSurvey(ServerPlayer player, FrequencyWorldData data, CompoundTag record) {
		if (!autoSurveyStatePresent(record)) return;
		boolean visible = record.getBooleanOr(TerminalData.MINERAL_SURVEY_NEARBY, false);
		data.updateTerminalRecord(player.getUUID(), ResourceGuidanceService::clearAutomaticSurveyState);
		if (visible) TerminalRuntimeService.refresh(player);
	}

	public static void clearAutomaticSurveyState(CompoundTag record) {
		record.putBoolean(TerminalData.MINERAL_SURVEY_PROXIMITY, false);
		record.putBoolean(TerminalData.MINERAL_SURVEY_NEARBY, false);
		record.putLong(TerminalData.MINERAL_SURVEY_POSITION, 0L);
		record.putString(TerminalData.MINERAL_SURVEY_DIMENSION, "");
	}

	private static void updateAutoScan(ServerPlayer player, FrequencyWorldData data,
			Map<UUID, AutoScanState> autoScans) {
		long now = player.level().getGameTime();
		String dimension = player.level().dimension().identifier().toString();
		AutoScanState scan = autoScans.get(player.getUUID());
		if (scan == null || !scan.dimension.equals(dimension)
				|| scan.origin.distManhattan(player.blockPosition()) > 4
				|| scan.finished && now - scan.finishedAt >= AUTO_SCAN_RESET_TICKS) {
			scan = new AutoScanState(player.blockPosition(), dimension);
			autoScans.put(player.getUUID(), scan);
		}
		if (scan.finished) return;
		scanAuto(player, data, autoScans, scan);
	}

	private static void scanAuto(ServerPlayer player, FrequencyWorldData data,
			Map<UUID, AutoScanState> autoScans, AutoScanState scan) {
		ServerLevel level = player.level();
		int surveyCeiling = squared(MineralSurveyPolicy.RANGE);
		while (scan.index < PROBE_OFFSETS.length) {
			long packed = PROBE_OFFSETS[scan.index];
			// The survey shares the probe's offset table and simply stops at its own much smaller
			// radius, so there is only one sorted sphere resident instead of two.
			if ((int) (packed >>> DISTANCE_SHIFT) > surveyCeiling) break;
			scan.index++;
			BlockPos candidate = scan.mutable.setWithOffset(scan.origin,
					offsetComponent(packed, 14), offsetComponent(packed, 7), offsetComponent(packed, 0)).immutable();
			if (level.isOutsideBuildHeight(candidate) || !level.hasChunkAt(candidate)
					|| !MineralSurveyPolicy.withinRange(
							candidate.getX() - player.getBlockX(),
							candidate.getY() - player.getBlockY(),
							candidate.getZ() - player.getBlockZ())) continue;
			Block block = level.getBlockState(candidate).getBlock();
			TerminalResource resource = surveyResource(block);
			if (!MineralSurveyPolicy.surveyable(resource)) continue;
			String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
			long now = level.getGameTime();
			// A survey hit becomes an ordinary exact reading rather than a proximity flag. The flag
			// only existed while the player stood within five blocks of the ore, so by the time
			// they had read the notice and opened the terminal they had usually walked out of it
			// and found an empty tool - the survey announced something it then refused to show.
			// As a reading it persists on the same terms as a probe: until the ore is gone, the
			// player changes dimension, or they walk up to it.
			data.updateTerminalRecord(player.getUUID(), record -> {
				record.putInt(TerminalData.SELECTED_RESOURCE, resource.wireId());
				record.putInt(TerminalData.MINERAL_READING_KIND, READING_EXACT);
				record.putInt(TerminalData.MINERAL_READING_DX, 0);
				record.putInt(TerminalData.MINERAL_READING_DZ, 0);
				record.putInt(TerminalData.MINERAL_READING_MIN_DISTANCE, 0);
				record.putInt(TerminalData.MINERAL_READING_MAX_DISTANCE, 0);
				record.putString(TerminalData.MINERAL_READING_DIMENSION, scan.dimension);
				new NavigationState(resource.id(), resourceItem(resource), true, blockId,
						candidate.asLong(), scan.dimension, now).writeTo(record);
				// Episode bookkeeping still exists, but only to stop the same block being
				// re-announced every tick while the player is standing beside it.
				record.putBoolean(TerminalData.MINERAL_SURVEY_PROXIMITY, true);
				record.putBoolean(TerminalData.MINERAL_SURVEY_NEARBY, true);
				record.putLong(TerminalData.MINERAL_SURVEY_POSITION, candidate.asLong());
				record.putString(TerminalData.MINERAL_SURVEY_DIMENSION, scan.dimension);
			});
			autoScans.remove(player.getUUID());
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
					Component.translatable("message.thefourthfrequency.guidance.nearby"));
			TerminalRuntimeService.synchronizeProjection(player);
			TerminalRuntimeService.refresh(player);
			return;
		}
		scan.finished = true;
		scan.finishedAt = level.getGameTime();
	}

	// ------------------------------------------------------------------ shared helpers

	public static TerminalResource surveyResource(Block block) {
		for (ResourceNeed need : ResourceNeed.values()) {
			if (need.blocks.contains(block)) return TerminalResource.fromId(need.id);
		}
		return TerminalResource.NONE;
	}

	public static String resourceItem(TerminalResource resource) {
		return switch (resource) {
			case IRON -> "minecraft:raw_iron";
			case COAL -> "minecraft:coal";
			case GOLD -> "minecraft:raw_gold";
			case DIAMOND -> "minecraft:diamond";
			case EMERALD -> "minecraft:emerald";
			case NONE -> "";
		};
	}

	private static boolean hasBindingResource(ServerPlayer player, ResourceNeed need) {
		for (Item item : need.bindingItems) {
			if (player.getInventory().contains(item.getDefaultInstance())) {
				return true;
			}
		}
		return false;
	}

	private static int squared(int value) {
		return value * value;
	}

	private static CompoundTag record(FrequencyWorldData data, ServerPlayer player) {
		return data.terminalRecord(player.getUUID()).orElseGet(CompoundTag::new);
	}

	private static int offsetComponent(long packed, int shift) {
		return (int) (packed >>> shift & OFFSET_MASK) - OFFSET_BIAS;
	}

	private static long[] createProbeOffsets(int radius) {
		int radiusSquared = radius * radius;
		int count = 0;
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					if (x * x + y * y + z * z <= radiusSquared) count++;
				}
			}
		}
		long[] offsets = new long[count];
		int index = 0;
		for (int x = -radius; x <= radius; x++) {
			for (int y = -radius; y <= radius; y++) {
				for (int z = -radius; z <= radius; z++) {
					int distanceSquared = x * x + y * y + z * z;
					if (distanceSquared > radiusSquared) continue;
					offsets[index++] = (long) distanceSquared << DISTANCE_SHIFT
							| (long) (x + OFFSET_BIAS) << 14
							| (long) (y + OFFSET_BIAS) << 7
							| z + OFFSET_BIAS;
				}
			}
		}
		// Squared distance occupies the high bits, so sorting the raw longs is the outward order.
		Arrays.sort(offsets);
		return offsets;
	}

	private static String appendEntry(String entries, String value) {
		if (entries.isBlank()) {
			return value;
		}
		for (String entry : entries.split(";")) {
			if (entry.equals(value)) {
				return entries;
			}
		}
		return entries + ";" + value;
	}

	private static boolean containsEntry(String entries, String value) {
		for (String entry : entries.split(";")) if (entry.equals(value)) return true;
		return false;
	}

	private enum ResourceNeed {
		IRON("iron", Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE), List.of(Items.RAW_IRON, Items.IRON_INGOT)),
		COAL("coal", Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE), List.of(Items.COAL)),
		GOLD("gold", Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
				List.of(Items.RAW_GOLD, Items.GOLD_INGOT)),
		DIAMOND("diamond", Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE), List.of(Items.DIAMOND)),
		EMERALD("emerald", Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE), List.of(Items.EMERALD));

		private final String id;
		private final Set<Block> blocks;
		private final List<Item> bindingItems;

		ResourceNeed(String id, Set<Block> blocks, List<Item> bindingItems) {
			this.id = id;
			this.blocks = blocks;
			this.bindingItems = bindingItems;
		}

		private static ResourceNeed byId(String id) {
			for (ResourceNeed need : values()) {
				if (need.id.equals(id)) {
					return need;
				}
			}
			return null;
		}
	}

	private static final class ProbeState {
		private final BlockPos origin;
		private final String dimension;
		private final int unlockedMask;
		private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		private int index;
		private boolean swept;
		private TerminalResource found = TerminalResource.NONE;
		private BlockPos foundPosition;
		private String foundBlockId = "";
		/**
		 * The best hit the terminal could actually name, tracked alongside the rarest one.
		 *
		 * <p>Costs nothing extra to keep: the sweep already visits every block the rarity search
		 * needs, so this is a second running maximum over the same walk rather than a second walk.
		 */
		private TerminalResource nameable = TerminalResource.NONE;
		private BlockPos nameablePosition;
		private String nameableBlockId = "";

		private ProbeState(BlockPos origin, String dimension, int unlockedMask) {
			this.origin = origin.immutable();
			this.dimension = dimension;
			this.unlockedMask = unlockedMask;
		}
	}

	private static final class AutoScanState {
		private final BlockPos origin;
		private final String dimension;
		private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
		private int index;
		private boolean finished;
		private long finishedAt;

		private AutoScanState(BlockPos origin, String dimension) {
			this.origin = origin.immutable();
			this.dimension = dimension;
		}
	}

}
