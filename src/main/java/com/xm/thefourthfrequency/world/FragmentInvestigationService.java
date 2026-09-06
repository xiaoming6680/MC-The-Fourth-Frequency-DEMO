package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.networking.PrivateAnomalyPayload;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.terminal.TerminalRecordPolicy;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalControlPolicy;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** World-shared vanilla-structure investigations. Blocks and loot never participate in completion. */
public final class FragmentInvestigationService {
	private static final String STATE = "fragment_investigation";
	private static final String CANDIDATES = "candidates";
	private static final String DISCOVERIES = "discoveries";
	/**
	 * Per-fragment count of scans that found nothing in that fragment's own pool.
	 *
	 * <p>The keys the retired station-origin allocation wrote - {@code allocation_cursor},
	 * {@code allocation_complete}, {@code allocation_rescued} - are left untouched on old saves. They
	 * are never read now, and rewriting them would be a change to persisted data in exchange for
	 * nothing.</p>
	 */
	private static final String POOL_MISSES = "pool_misses";
	private static final String NEAR_KEY = "fragment_near_candidate";
	private static final int STATE_VERSION = 1;
	private static final int FRAGMENT_COUNT = HiddenFilePolicy.FILE_COUNT;
	/**
	 * Stride for the wire encoding of "fragment N's candidate S", and the ceiling old saves were
	 * allocated against. Exploration gives a fragment one lead and never a second, but saves written
	 * by the retired allocator can hold up to this many, so the encoding still has to span them.
	 */
	private static final int MAX_CANDIDATES_PER_FRAGMENT = TerminalRecordPolicy.MAX_CANDIDATES_PER_FRAGMENT;
	private static final int ENTER_CHECK_INTERVAL = 10;
	private static final SignalBand[] SIGNAL_BANDS = {
			SignalBand.WEATHER, SignalBand.MINING, SignalBand.PUBLIC, SignalBand.UNKNOWN
	};
	private static final Group[][] POOLS = {
			{Group.MINESHAFT, Group.SHIPWRECK, Group.TRAIL_RUINS, Group.STRONGHOLD},
			{Group.MINESHAFT, Group.WOODLAND_MANSION, Group.DESERT_PYRAMID, Group.IGLOO},
			{Group.TRIAL_CHAMBERS, Group.PILLAGER_OUTPOST, Group.JUNGLE_TEMPLE, Group.OCEAN_MONUMENT},
			{Group.ANCIENT_CITY, Group.OCEAN_RUINS, Group.RUINED_PORTAL, Group.WOODLAND_MANSION}
	};
	private static final Map<UUID, Nearby> NEARBY = new LinkedHashMap<>();
	private static final Map<UUID, Nearby> FORCED_NEARBY_FOR_TESTS = new LinkedHashMap<>();
	/**
	 * When each player is next due a scan.
	 *
	 * <p>In memory rather than persisted: a lead is opportunistic, so a restart redrawing the timer
	 * costs at most one interval and saves a per-player key that would have to be migrated.</p>
	 */
	private static final Map<UUID, Long> NEXT_SCAN_TICK = new LinkedHashMap<>();
	private static boolean initialized;

	private FragmentInvestigationService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(FragmentInvestigationService::tick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			NEARBY.clear();
			FORCED_NEARBY_FOR_TESTS.clear();
			NEXT_SCAN_TICK.clear();
		});
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % ENTER_CHECK_INTERVAL != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) updateNearby(player, data);
		scanOneDuePlayer(server, data, server.getTickCount());
	}

	/**
	 * Gives at most one player a lead scan per check, however many are online.
	 *
	 * <p>The scan is cheap - a map read per loaded chunk, no search - but it is not free, and the
	 * budget that matters on a full server is the one nobody notices until there are eight players.
	 * One per check, with each player's own randomised interval on top, means the cost does not grow
	 * with the player count at all: it grows the wait.</p>
	 */
	private static void scanOneDuePlayer(MinecraftServer server, FrequencyWorldData data, long now) {
		if (!investigationsActive(data)) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			Long due = NEXT_SCAN_TICK.get(player.getUUID());
			if (due == null) {
				// First seen: wait a full interval rather than scanning on the join tick, so logging in
				// is never itself the thing that hands out a lead.
				NEXT_SCAN_TICK.put(player.getUUID(), now + nextInterval(player));
				continue;
			}
			if (now < due) continue;
			NEXT_SCAN_TICK.put(player.getUUID(), now + nextInterval(player));
			scanForLead(player, data);
			return;
		}
		NEXT_SCAN_TICK.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/**
	 * Scans faster for a player the terminal is not currently helping.
	 *
	 * <p>"Has an open lead" is read as a candidate already filed and not yet resolved, which is the
	 * same thing the navigation page offers. A player before the Nether has none of those, gets the
	 * short interval, and stops being the one the device has nothing to say to.
	 */
	private static int nextInterval(ServerPlayer player) {
		return FragmentLeadPolicy.scanIntervalTicks(player.getRandom().nextDouble(),
				hasUndiscoveredCandidate(player));
	}

	/**
	 * One scan: what is loaded around this player, and does any of it answer a fragment still waiting.
	 *
	 * <p>Reads structure references out of chunks that are already loaded and never asks for one that
	 * is not. That single rule is what keeps this from being a terrain generator: an unloaded chunk is
	 * simply not part of the world as far as a receiver is concerned.</p>
	 *
	 * <p>Mirror dimensions are excluded with everything else that reads real progress. A structure
	 * copied into a pursuit mirror is a copy, and a lead taken from one would point at a place that
	 * stops existing when the chase ends.</p>
	 */
	private static void scanForLead(ServerPlayer player, FrequencyWorldData data) {
		if (!(player.level() instanceof ServerLevel level) || PrivateDimensions.isPrivate(level)) return;
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		Set<Integer> discovered = discoveredFragments(data);
		List<Candidate> current = candidates(data);
		int waiting = 0;
		for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
			if (discovered.contains(fragment)) continue;
			final int index = fragment;
			// One lead per fragment, and it is never moved once given. A lead that quietly relocates
			// because the player wandered somewhere closer is a coordinate they were told and can no
			// longer trust - and the log line announcing it was written once, at the old place.
			if (current.stream().anyMatch(candidate -> candidate.fragment() == index)) continue;
			waiting |= 1 << fragment;
		}
		if (waiting == 0) return;
		List<Found> found = nearbyStructures(player, level);
		int[] misses = poolMisses(data);
		// Nearest first, and the pool decides which fragment it can serve. Walking the structures on
		// the outside rather than the fragments is what makes "nearest" mean nearest: the other order
		// hands fragment 1 something across the valley while fragment 2's mineshaft is underfoot.
		for (Found entry : found) {
			for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
				if ((waiting & 1 << fragment) == 0 || !pooled(fragment, entry.group())) continue;
				if (recordLead(data, fragment, entry, level)) return;
			}
		}
		// Patience spent: this fragment has watched enough loaded chunks go by without one of its own
		// four kinds of place, so the nearest anything carries it instead.
		for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
			if ((waiting & 1 << fragment) == 0 || FragmentLeadPolicy.poolStillBinding(misses[fragment])) continue;
			for (Found entry : found) if (recordLead(data, fragment, entry, level)) return;
		}
		for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
			if ((waiting & 1 << fragment) != 0) misses[fragment]++;
		}
		storePoolMisses(data, misses);
	}

	/** Every group structure whose footprint reaches a loaded chunk near the player, nearest first. */
	private static List<Found> nearbyStructures(ServerPlayer player, ServerLevel level) {
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Map<ResourceKey<Structure>, Group> byKey = new java.util.HashMap<>();
		for (Group group : Group.values()) for (ResourceKey<Structure> key : group.keys) byKey.put(key, group);
		BlockPos here = player.blockPosition();
		ChunkPos centre = player.chunkPosition();
		Set<Long> seen = new HashSet<>();
		List<Found> found = new ArrayList<>();
		int radius = FragmentLeadPolicy.SCAN_CHUNK_RADIUS;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int chunkX = centre.x + dx;
				int chunkZ = centre.z + dz;
				// Never generate. startsForStructure would happily create the chunk to answer.
				if (!level.hasChunk(chunkX, chunkZ)) continue;
				for (StructureStart start : level.structureManager().startsForStructure(
						new ChunkPos(chunkX, chunkZ),
						structure -> byKey.containsKey(registry.getResourceKey(structure).orElse(null)))) {
					if (!start.isValid()) continue;
					Group group = byKey.get(registry.getResourceKey(start.getStructure()).orElse(null));
					// One entry per structure however many of its chunks were walked.
					if (group == null || !seen.add(start.getChunkPos().toLong() * 31L + group.code)) continue;
					BlockPos position = start.getBoundingBox().getCenter();
					found.add(new Found(group, position, horizontalDistanceSquared(here, position)));
				}
			}
		}
		found.sort(Comparator.comparingLong(Found::distanceSquared));
		return List.copyOf(found);
	}

	/**
	 * Writes the lead, unless that exact place is already somebody's.
	 *
	 * <p>The position stored is the structure's own centre, with its real height - not
	 * {@code getLocatePos}, which is the corner of the starting chunk with Y pinned to 0. That is the
	 * whole reason this reads the real {@code StructureStart}: the navigator now points at the thing,
	 * and the height difference on the readout means something.</p>
	 */
	private static boolean recordLead(FrequencyWorldData data, int fragment, Found found, ServerLevel level) {
		List<Candidate> current = candidates(data);
		String dimension = level.dimension().identifier().toString();
		if (current.stream().anyMatch(candidate -> candidate.dimension().equals(dimension)
				&& candidate.position().equals(found.position()))) return false;
		List<Candidate> next = new ArrayList<>(current);
		next.add(new Candidate(fragment, found.group(), found.position(), dimension));
		storeCandidates(data, next);
		int[] misses = poolMisses(data);
		misses[fragment] = 0;
		storePoolMisses(data, misses);
		return true;
	}

	private static boolean pooled(int fragment, Group group) {
		for (Group candidate : POOLS[fragment]) if (candidate == group) return true;
		return false;
	}

	private static int[] poolMisses(FrequencyWorldData data) {
		int[] stored = state(data).getIntArray(POOL_MISSES).orElse(new int[0]);
		int[] misses = new int[FRAGMENT_COUNT];
		System.arraycopy(stored, 0, misses, 0, Math.min(stored.length, FRAGMENT_COUNT));
		return misses;
	}

	private static void storePoolMisses(FrequencyWorldData data, int[] misses) {
		data.updateNarrativeState(root -> {
			CompoundTag state = root.getCompoundOrEmpty(STATE).copy();
			state.putInt("version", STATE_VERSION);
			state.putIntArray(POOL_MISSES, misses.clone());
			root.put(STATE, state);
		});
	}

	/** One structure the scan can see, and how far away it is horizontally. */
	private record Found(Group group, BlockPos position, long distanceSquared) { }

	private static boolean investigationsActive(FrequencyWorldData data) {
		for (UUID owner : data.terminalOwnerIds()) {
			CompoundTag record = data.terminalRecord(owner).orElse(null);
			if (record != null && record.getIntOr(TerminalData.BAND_STAGE, 0) > 0) return true;
		}
		return false;
	}

	private static void updateNearby(ServerPlayer player, FrequencyWorldData data) {
		if (data.terminalRecord(player.getUUID()).isEmpty()) {
			NEARBY.remove(player.getUUID());
			return;
		}
		Nearby found = detect(player, data).orElse(null);
		Nearby previous = NEARBY.get(player.getUUID());
		if (found == null) NEARBY.remove(player.getUUID());
		else NEARBY.put(player.getUUID(), found);
		String nextKey = found == null ? "" : found.key();
		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		String storedKey = record.getStringOr(NEAR_KEY, "");
		if (storedKey.equals(nextKey)) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(NEAR_KEY, nextKey);
			if (found != null) {
				String type = "fragment_near_" + (found.candidate().fragment() + 1);
				if (!TerminalSignalLog.containsType(tag, type)) TerminalSignalLog.append(tag,
						bandForFragment(found.candidate().fragment()), type, player.level().getGameTime(),
						player.level().getDayTime(), found.candidate().dimension(),
						found.candidate().position().asLong(), found.candidate().group().code, 1, true);
			}
		});
		// Says, where the player is standing, that this is a place the receiver can be tuned.
		//
		// The records line above is written once per fragment for the life of the save, which is
		// right for a log and useless as a prompt: a player who walks in, reads nothing, leaves, and
		// comes back a week later is in exactly the spot the mechanic exists for and is told nothing
		// at all. Worse, the log entry only ever said a lead was "nearby" - never that tuning the
		// near-field receiver is the thing that opens it - so the one hint a player did get named no
		// action. Both halves are why the tuning mechanic could go a whole run unused.
		//
		// Fires on entering the signal, not on a timer: updateNearby has already returned above
		// unless the key changed, so standing still cannot repeat it and walking back in re-arms it.
		if (found != null) {
			TerminalNoticeService.tunableSignal(player);
		}
		if (found != null || previous != null) {
			TerminalLifecycleService.ensureCarried(player, false);
			TerminalRuntimeService.synchronizeProjection(player);
			TerminalRuntimeService.refresh(player);
		}
	}

	public static Optional<Nearby> nearby(ServerPlayer player) {
		Nearby cached = NEARBY.get(player.getUUID());
		if (cached != null) return Optional.of(cached);
		return detect(player, FrequencyWorldData.get(player.level().getServer()));
	}

	private static Optional<Nearby> detect(ServerPlayer player, FrequencyWorldData data) {
		Nearby forced = FORCED_NEARBY_FOR_TESTS.get(player.getUUID());
		if (forced != null) return Optional.of(forced);
		String dimension = player.level().dimension().identifier().toString();
		Set<Integer> discovered = discoveredFragments(data);
		for (Candidate candidate : candidates(data)) {
			if (discovered.contains(candidate.fragment()) || !candidate.dimension().equals(dimension)) continue;
			BlockPos here = player.blockPosition();
			BlockPos mark = candidate.position();
			if (!FragmentSignalPolicy.withinSignalRange(here.getX(), here.getZ(), mark.getX(), mark.getZ())) continue;
			StructureStart start = player.level().structureManager().getStructureWithPieceAt(
					here, holders(player.level(), candidate.group()));
			if (!start.isValid() || !matchesCandidate(start, mark)) continue;
			return Optional.of(new Nearby(candidate, receiverTuning(candidate)));
		}
		return Optional.empty();
	}

	/**
	 * Whether the structure the player is standing in is the one this candidate marked.
	 *
	 * <p>The whole rule lives in {@link FragmentSignalPolicy}, which is where the reasoning about what
	 * the marked coordinate actually is - and why 96 blocks was not enough of it - is written down.
	 * This only unpacks the bounding box for it.</p>
	 */
	private static boolean matchesCandidate(StructureStart start, BlockPos mark) {
		var box = start.getBoundingBox();
		return FragmentSignalPolicy.answersCandidate(mark.getX(), mark.getZ(),
				box.minX(), box.minZ(), box.maxX(), box.maxZ());
	}

	public static boolean insideSupportedStructure(ServerPlayer player) {
		for (Group group : Group.values()) {
			if (player.level().structureManager().getStructureWithPieceAt(
					player.blockPosition(), holders(player.level(), group)).isValid()) return true;
		}
		return false;
	}

	/**
	 * Writes the per-fragment candidate lines, marking unread only what Records will actually list.
	 *
	 * <p>The unread badge and the amber lamp both count entries the page passes through
	 * {@link TerminalRecordPolicy#visibleInRecords}, which lets every {@code _0} candidate through.
	 * The page then applies a <em>second</em> filter the count never knew about: without the
	 * navigator the whole class is hidden, and with it, only the first entry per location is listed.
	 * So the tab could say something was new, the player could open Records on the strength of that,
	 * and find the list exactly as they left it - which teaches them the marker is noise.
	 *
	 * <p>Decided here rather than in the count because "does this player have the navigator" is not a
	 * pure read of the record - it needs the player - while the count is called from a dozen places
	 * that only have the tag.
	 *
	 * <p><b>The cost, stated:</b> a candidate written before the navigator exists stays read. When the
	 * player later unlocks the tool the line is simply there in the list, without a badge announcing
	 * it. That is the acceptable direction of the two - a marker that under-promises is a marker that
	 * still means something.</p>
	 */
	public static boolean appendCandidateLogs(CompoundTag record, ServerPlayer player, FrequencyWorldData data) {
		if (record.getIntOr(TerminalData.BAND_STAGE, 0) == 0) return false;
		boolean changed = false;
		int[] slots = new int[FRAGMENT_COUNT];
		boolean navigator = (TerminalToolService.availableToolsMask(player, record)
				& 1 << TerminalTool.NAVIGATION.ordinal()) != 0;
		// The page keeps one entry per location, so a second candidate sharing a group is listed only
		// once however many fragments point at it. Seeded with what the log already holds, or a later
		// call would hand out a badge for a line the page has been collapsing since the first one.
		Set<Integer> announcedLocations = listedCandidateLocations(record);
		for (Candidate candidate : candidates(data)) {
			int slot = slots[candidate.fragment()]++;
			String type = "fragment_candidate_" + (candidate.fragment() + 1) + "_" + slot;
			if (TerminalSignalLog.containsType(record, type)) continue;
			boolean listed = TerminalRecordPolicy.listedInRecords(type, candidate.group().code,
					navigator, announcedLocations);
			TerminalSignalLog.append(record, bandForFragment(candidate.fragment()), type, player.level().getGameTime(),
					player.level().getDayTime(), candidate.dimension(), candidate.position().asLong(),
					candidate.group().code, candidate.group().location.code, listed);
			changed = true;
		}
		return changed;
	}

	/** Group codes already carried by a listed candidate line in the log. */
	private static Set<Integer> listedCandidateLocations(CompoundTag record) {
		Set<Integer> locations = new HashSet<>();
		for (TerminalSignalLog.Entry entry : TerminalSignalLog.entries(record)) {
			if (TerminalRecordPolicy.visibleInRecords(entry.type())
					&& TerminalRecordPolicy.isCandidate(entry.type())) {
				locations.add(entry.variant());
			}
		}
		return locations;
	}

	public static boolean ensureSignalMarkers(CompoundTag record, ServerPlayer player) {
		boolean changed = normalizeSignalBands(record);
		for (int fragment = 0; fragment < FRAGMENT_COUNT; fragment++) {
			if (TerminalFileState.discovered(record, HiddenFilePolicy.fileId(fragment))) continue;
			String type = "fragment_marker_" + (fragment + 1);
			if (TerminalSignalLog.containsType(record, type)) continue;
			TerminalSignalLog.append(record, bandForFragment(fragment), type, player.level().getGameTime(),
					player.level().getDayTime(), "", 0L, fragment, 0, false);
			changed = true;
		}
		return changed;
	}

	public static SignalBand bandForFragment(int fragment) {
		return SIGNAL_BANDS[Math.clamp(fragment, 0, SIGNAL_BANDS.length - 1)];
	}

	private static boolean normalizeSignalBands(CompoundTag record) {
		ListTag events = record.getListOrEmpty(TerminalData.SIGNAL_EVENTS).copy();
		boolean changed = false;
		for (int index = 0; index < events.size(); index++) {
			CompoundTag entry = events.getCompoundOrEmpty(index);
			int fragment = signalFragment(entry.getStringOr("type", ""));
			if (fragment < 0) continue;
			int expected = bandForFragment(fragment).wireId();
			if (entry.getIntOr("band", SignalBand.UNKNOWN.wireId()) == expected) continue;
			entry.putInt("band", expected);
			events.set(index, entry);
			changed = true;
		}
		if (changed) record.put(TerminalData.SIGNAL_EVENTS, events);
		return changed;
	}

	private static int signalFragment(String type) {
		String value = null;
		if (type.startsWith("fragment_candidate_")) {
			String remainder = type.substring("fragment_candidate_".length());
			int separator = remainder.indexOf('_');
			value = separator < 0 ? remainder : remainder.substring(0, separator);
		} else {
			for (String prefix : new String[]{"fragment_marker_", "fragment_near_", "fragment_shared_",
					"fragment_received_", "fragment_action_"}) {
				if (type.startsWith(prefix)) {
					value = type.substring(prefix.length());
					break;
				}
			}
		}
		if (value == null) return -1;
		try {
			int fragment = Integer.parseInt(value) - 1;
			return fragment >= 0 && fragment < FRAGMENT_COUNT ? fragment : -1;
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	public static boolean synchronizeSharedFiles(CompoundTag record, ServerPlayer player, FrequencyWorldData data,
			List<SharedReceipt> receipts) {
		boolean changed = false;
		ListTag discoveries = state(data).getListOrEmpty(DISCOVERIES);
		for (int index = 0; index < discoveries.size(); index++) {
			CompoundTag discovery = discoveries.getCompoundOrEmpty(index);
			int fragment = discovery.getIntOr("fragment", -1);
			if (fragment < 0 || fragment >= FRAGMENT_COUNT
					|| TerminalFileState.discovered(record, HiddenFilePolicy.fileId(fragment))) continue;
			long gameTime = discovery.getLongOr("game_time", player.level().getGameTime());
			long dayTime = discovery.getLongOr("day_time", player.level().getDayTime());
			String discovererName = discovery.getStringOr("discoverer_name", "?");
			String discovererId = discovery.getStringOr("discoverer_id", "");
			boolean own = player.getUUID().toString().equals(discovererId);
			TerminalFileState.discover(record, HiddenFilePolicy.fileId(fragment), gameTime, dayTime, true);
			TerminalSignalLog.append(record, bandForFragment(fragment),
					(own ? "fragment_shared_" : "fragment_received_") + (fragment + 1), gameTime, dayTime,
					discovererName, discovery.getLongOr("position", 0L), fragment, 1, true);
			receipts.add(new SharedReceipt(fragment + 1, discovererName, own));
			changed = true;
		}
		return changed;
	}

	public static boolean selectCandidate(ServerPlayer player, int encoded) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !investigationOffered(player)) return false;
		int fragment = Math.floorDiv(encoded, MAX_CANDIDATES_PER_FRAGMENT);
		int slot = Math.floorMod(encoded, MAX_CANDIDATES_PER_FRAGMENT);
		if (fragment < 0 || fragment >= FRAGMENT_COUNT || discoveredFragments(data).contains(fragment)) return false;
		List<Candidate> values = candidates(data).stream()
				.filter(candidate -> candidate.fragment() == fragment).toList();
		if (slot >= values.size()) return false;
		Candidate selected = values.get(slot);
		data.updateTerminalRecord(player.getUUID(), tag -> new NavigationState(
				"structure_fragment", "fragment_" + (fragment + 1) + "_" + slot, true,
				selected.group().id, selected.position().asLong(), selected.dimension(), player.level().getGameTime()).writeTo(tag));
		TerminalRuntimeService.refresh(player);
		return true;
	}

	/**
	 * Whether any lead the player has not resolved yet still exists, in any world.
	 *
	 * <p>Deliberately not filtered to the dimension the player is standing in. A lead in the
	 * Overworld does not stop existing because its owner walked through a nether portal, and the
	 * records line announcing it certainly does not - so filtering here produced a terminal that
	 * disagreed with its own log about whether there was anything to investigate.
	 */
	public static boolean hasUndiscoveredCandidate(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || record.getIntOr(TerminalData.BAND_STAGE, 0) == 0) return false;
		Set<Integer> discovered = discoveredFragments(data);
		return candidates(data).stream()
				.anyMatch(candidate -> !discovered.contains(candidate.fragment()));
	}

	/**
	 * The one answer to "is the terminal offering the unstable investigation right now".
	 *
	 * <p><b>Two callers had grown separate answers, and they disagreed.</b> The navigation page asked
	 * {@code TerminalToolService.unstableSignalAvailable} whether to draw the option; the click asked
	 * {@code unstableNavigationUnlocked} whether to honour it. Neither matched the records page,
	 * which announces a lead and then keeps that line for the rest of the run.
	 *
	 * <p><b>The hint tier is gone from this gate, and that was the worse of the two faults.</b>
	 * {@code guidanceHintTier} measures how long the player has been <em>stalled</em>, and
	 * {@code StoryProgressService} zeroes it on any objective progress at all. Requiring tier 2 meant
	 * the investigation appeared only after five minutes of being stuck and vanished the moment the
	 * player mined the next iron ore - an option that blinks in and out on a timer nobody can see,
	 * while its records line sits there permanently saying it is available. Being stuck is a
	 * reasonable trigger for <em>offering help</em>; it is not a precondition for an optional thread
	 * the terminal has already told the player about.
	 *
	 * <p><b>The Nether milestone is gone from it too, for the other half of the same fault.</b> The
	 * records line is written the moment a lead is filed - band stage above zero and the navigator
	 * unlocked - which on a normal run is the first day, long before any portal. So the log announced
	 * an optional investigation with an "open navigation" shortcut, and both the shortcut and the
	 * navigator's own option were refused until the player reached the Nether: a location the terminal
	 * named and would not take them to.
	 *
	 * <p>What replaces both is monotone: once the receiver has resolved a band and a lead exists, the
	 * offer stands until the lead is resolved. An offer that can be withdrawn without the player
	 * doing anything is the thing this class must not produce again, and one that was never honoured
	 * in the first place is worse.
	 */
	/**
	 * One bit per fragment the player has already found, for the records page.
	 *
	 * <p>The page keeps a candidate line after its fragment is discovered - the log is a record of
	 * what happened, not a to-do list, and deleting the row would rewrite history. What it must drop
	 * is the row's navigation shortcut: {@link #selectCandidate} refuses a discovered fragment, so a
	 * shortcut left on that row would be a control that silently does nothing. Marking something the
	 * player cannot follow is exactly what the records page is not allowed to do.
	 */
	public static int discoveredFragmentMask(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return 0;
		int mask = 0;
		for (int fragment : discoveredFragments(FrequencyWorldData.get(server))) mask |= 1 << fragment;
		return mask;
	}

	public static boolean investigationOffered(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || record.getIntOr(TerminalData.BAND_STAGE, 0) == 0) return false;
		NavigationState navigation = NavigationState.read(record);
		boolean fragmentSelected = navigation.kind().equals("structure_fragment") && navigation.located();
		// Short-circuits the candidate scan when the cheaper answer already decides it.
		return FragmentInvestigationPolicy.offered(record.getIntOr(TerminalData.BAND_STAGE, 0),
				fragmentSelected, fragmentSelected || hasUndiscoveredCandidate(player));
	}

	public static boolean selectNearestCandidate(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !investigationOffered(player)) return false;
		String dimension = player.level().dimension().identifier().toString();
		Set<Integer> discovered = discoveredFragments(data);
		List<Candidate> open = candidates(data).stream()
				.filter(candidate -> !discovered.contains(candidate.fragment())).toList();
		// This world first, because a lead you can walk to beats one you have to build a portal for.
		// But "none here" is a reason to point somewhere else, not a reason to do nothing: the click
		// used to return false in that case, so a player in the Nether tapped the option the terminal
		// was drawing for them and got silence.
		Candidate nearest = open.stream()
				.filter(candidate -> candidate.dimension().equals(dimension))
				.min(Comparator.comparingLong(candidate -> horizontalDistanceSquared(
						player.blockPosition(), candidate.position())))
				.orElseGet(() -> open.stream().min(Comparator.comparingLong(candidate ->
						horizontalDistanceSquared(player.blockPosition(), candidate.position())))
						.orElse(null));
		if (nearest == null) return false;
		List<Candidate> fragmentCandidates = candidates(data).stream()
				.filter(candidate -> candidate.fragment() == nearest.fragment()).toList();
		int slot = fragmentCandidates.indexOf(nearest);
		return slot >= 0 && selectCandidate(player, nearest.fragment() * MAX_CANDIDATES_PER_FRAGMENT + slot);
	}

	public static boolean completeNearby(ServerPlayer discoverer, int tuning) {
		FrequencyWorldData data = FrequencyWorldData.get(discoverer.level().getServer());
		Nearby nearby = detect(discoverer, data).orElse(null);
		if (nearby == null || !TerminalControlPolicy.receiverLocked(tuning, nearby.tuning())) return false;
		return completeCandidate(discoverer, nearby.candidate());
	}

	private static boolean completeCandidate(ServerPlayer discoverer, Candidate candidate) {
		FrequencyWorldData data = FrequencyWorldData.get(discoverer.level().getServer());
		if (discoveredFragments(data).contains(candidate.fragment())) return false;
		long now = discoverer.level().getGameTime();
		long dayTime = discoverer.level().getDayTime();
		String discovererName = discoverer.getGameProfile().name();
		CompoundTag discovery = new CompoundTag();
		discovery.putInt("fragment", candidate.fragment());
		discovery.putInt("group", candidate.group().code);
		discovery.putLong("position", candidate.position().asLong());
		discovery.putString("dimension", candidate.dimension());
		discovery.putString("discoverer_id", discoverer.getUUID().toString());
		discovery.putString("discoverer_name", discovererName);
		discovery.putLong("game_time", now);
		discovery.putLong("day_time", dayTime);
		data.updateNarrativeState(root -> {
			CompoundTag state = root.getCompoundOrEmpty(STATE).copy();
			ListTag discoveries = state.getListOrEmpty(DISCOVERIES).copy();
			discoveries.add(discovery);
			state.put(DISCOVERIES, discoveries);
			root.put(STATE, state);
		});
		NEARBY.entrySet().removeIf(entry -> entry.getValue().candidate().fragment() == candidate.fragment());
		FORCED_NEARBY_FOR_TESTS.entrySet().removeIf(entry -> entry.getValue().candidate().fragment() == candidate.fragment());
		for (UUID owner : data.terminalOwnerIds()) {
			boolean isDiscoverer = owner.equals(discoverer.getUUID());
			data.updateTerminalRecord(owner, tag -> {
				TerminalFileState.discover(tag, HiddenFilePolicy.fileId(candidate.fragment()), now, dayTime, true);
				TerminalSignalLog.append(tag, bandForFragment(candidate.fragment()),
						(isDiscoverer ? "fragment_shared_" : "fragment_received_") + (candidate.fragment() + 1),
						now, dayTime, discovererName, candidate.position().asLong(), candidate.fragment(), 1, true);
				if (isDiscoverer) TerminalSignalLog.append(tag, bandForFragment(candidate.fragment()),
						"fragment_action_" + (candidate.fragment() + 1), now, dayTime,
						candidate.dimension(), discoverer.blockPosition().asLong(), candidate.group().code, 2, true);
				NavigationState navigation = NavigationState.read(tag);
				if (navigation.kind().equals("structure_fragment")
						&& navigation.itemId().startsWith("fragment_" + (candidate.fragment() + 1) + "_")) {
					navigation.clearLocation().writeTo(tag);
				}
			});
		}
		ServerPlayNetworking.send(discoverer, new PrivateAnomalyPayload(
				"fragment_" + (candidate.fragment() + 1), candidate.group().code));
		AudioService.play(discoverer.level(), discoverer.blockPosition(), AudioService.Cue.FOURTH_BAND);
		for (ServerPlayer online : discoverer.level().getServer().getPlayerList().getPlayers()) {
			if (data.terminalRecord(online.getUUID()).isEmpty()) continue;
			Component message = online.getUUID().equals(discoverer.getUUID())
					? Component.translatable("message.thefourthfrequency.fragment.shared",
							candidate.fragment() + 1)
					: Component.translatable("message.thefourthfrequency.fragment.received",
							discovererName, candidate.fragment() + 1);
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(online, message);
			TerminalLifecycleService.ensureCarried(online, false);
			TerminalRuntimeService.synchronizeProjection(online);
			TerminalRuntimeService.refresh(online);
		}
		return true;
	}

	public static void setCandidatesForTesting(FrequencyWorldData data, List<Candidate> values) {
		storeCandidates(data, values);
		data.updateNarrativeState(root -> {
			CompoundTag state = root.getCompoundOrEmpty(STATE).copy();
			state.remove(DISCOVERIES);
			root.put(STATE, state);
		});
		// Only the derived cache is dropped. Forced entries belong to whichever test installed them,
		// and game tests share one server: clearing them here let one test silently pull the
		// candidate out from under another that was mid-hold.
		NEARBY.clear();
	}

	public static List<Candidate> candidatesForTesting(FrequencyWorldData data) {
		return candidates(data);
	}

	/** Runs one lead scan immediately, bypassing the randomised timer. */
	public static void scanForLeadForTesting(ServerPlayer player) {
		scanForLead(player, FrequencyWorldData.get(player.level().getServer()));
	}

	/** How many scans this fragment has spent without finding one of its own four kinds of place. */
	public static int poolMissesForTesting(FrequencyWorldData data, int fragment) {
		return poolMisses(data)[Math.clamp(fragment, 0, FRAGMENT_COUNT - 1)];
	}

	/**
	 * Sets how much patience a fragment has already spent.
	 *
	 * <p>Game tests share one server whose tick loop keeps scanning between them, so a test that
	 * needs a known starting count has to write one rather than assume zero.</p>
	 */
	public static void setPoolPatienceForTesting(FrequencyWorldData data, int fragment, int scans) {
		int[] misses = poolMisses(data);
		misses[Math.clamp(fragment, 0, FRAGMENT_COUNT - 1)] = Math.max(0, scans);
		storePoolMisses(data, misses);
	}

	public static void setNearbyForTesting(ServerPlayer player, Candidate candidate) {
		Nearby nearby = new Nearby(candidate, receiverTuning(candidate));
		NEARBY.put(player.getUUID(), nearby);
		FORCED_NEARBY_FOR_TESTS.put(player.getUUID(), nearby);
	}

	public static boolean discoverForTesting(ServerPlayer player, Candidate candidate) {
		return completeCandidate(player, candidate);
	}

	public static int completedCount(FrequencyWorldData data) {
		return discoveredFragments(data).size();
	}

	public static boolean isFragmentFile(String id) {
		return HiddenFilePolicy.isHiddenFile(id);
	}

	public static int fragmentForFile(String id) {
		return HiddenFilePolicy.indexOf(id);
	}

	public static int receiverTuning(Candidate candidate) {
		int hash = 17;
		hash = 31 * hash + candidate.fragment();
		hash = 31 * hash + candidate.group().code;
		hash = 31 * hash + candidate.dimension().hashCode();
		hash = 31 * hash + Long.hashCode(candidate.position().asLong());
		return 12 + Math.floorMod(hash, 77);
	}

	private static Set<Integer> discoveredFragments(FrequencyWorldData data) {
		return state(data).getListOrEmpty(DISCOVERIES).stream()
				.map(tag -> ((CompoundTag) tag).getIntOr("fragment", -1))
				.filter(value -> value >= 0 && value < FRAGMENT_COUNT)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static List<Candidate> candidates(FrequencyWorldData data) {
		List<Candidate> values = new ArrayList<>();
		ListTag list = state(data).getListOrEmpty(CANDIDATES);
		for (int index = 0; index < list.size(); index++) {
			CompoundTag tag = list.getCompoundOrEmpty(index);
			int fragment = tag.getIntOr("fragment", -1);
			Group group = Group.fromCode(tag.getIntOr("group", -1));
			if (fragment < 0 || fragment >= FRAGMENT_COUNT || group == null) continue;
			values.add(new Candidate(fragment, group, BlockPos.of(tag.getLongOr("position", 0L)),
					tag.getStringOr("dimension", "minecraft:overworld")));
		}
		values.sort(Comparator.comparingInt(Candidate::fragment));
		return List.copyOf(values);
	}

	private static CompoundTag state(FrequencyWorldData data) {
		CompoundTag state = data.narrativeState().getCompoundOrEmpty(STATE).copy();
		if (!state.contains("version")) state.putInt("version", STATE_VERSION);
		return state;
	}

	private static void storeCandidates(FrequencyWorldData data, List<Candidate> candidates) {
		ListTag encoded = new ListTag();
		for (Candidate candidate : candidates) {
			CompoundTag tag = new CompoundTag();
			tag.putInt("fragment", candidate.fragment());
			tag.putInt("group", candidate.group().code);
			tag.putLong("position", candidate.position().asLong());
			tag.putString("dimension", candidate.dimension());
			encoded.add(tag);
		}
		data.updateNarrativeState(root -> {
			CompoundTag state = root.getCompoundOrEmpty(STATE).copy();
			state.putInt("version", STATE_VERSION);
			state.put(CANDIDATES, encoded);
			root.put(STATE, state);
		});
	}

	private static HolderSet<Structure> holders(ServerLevel level, Group group) {
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		List<Holder<Structure>> holders = group.keys.stream().map(registry::getOrThrow).map(value -> (Holder<Structure>) value).toList();
		return HolderSet.direct(holders);
	}

	private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
		long dx = first.getX() - (long) second.getX();
		long dz = first.getZ() - (long) second.getZ();
		return dx * dx + dz * dz;
	}

	public record Candidate(int fragment, Group group, BlockPos position, String dimension) { }
	public record Nearby(Candidate candidate, int tuning) {
		public String key() {
			return candidate.fragment() + ":" + candidate.group().code + ":" + candidate.position().asLong();
		}
	}
	public record SharedReceipt(int fragment, String discovererName, boolean own) { }

	public enum Location {
		UNDERGROUND(0), SURFACE(1), WATER(2);
		private final int code;
		Location(int code) { this.code = code; }
	}

	public enum Group {
		MINESHAFT(0, "mineshaft", Location.UNDERGROUND, BuiltinStructures.MINESHAFT, BuiltinStructures.MINESHAFT_MESA),
		SHIPWRECK(1, "shipwreck", Location.WATER, BuiltinStructures.SHIPWRECK, BuiltinStructures.SHIPWRECK_BEACHED),
		TRAIL_RUINS(2, "trail_ruins", Location.UNDERGROUND, BuiltinStructures.TRAIL_RUINS),
		STRONGHOLD(3, "stronghold", Location.UNDERGROUND, BuiltinStructures.STRONGHOLD),
		WOODLAND_MANSION(4, "woodland_mansion", Location.SURFACE, BuiltinStructures.WOODLAND_MANSION),
		DESERT_PYRAMID(5, "desert_pyramid", Location.SURFACE, BuiltinStructures.DESERT_PYRAMID),
		IGLOO(6, "igloo", Location.SURFACE, BuiltinStructures.IGLOO),
		TRIAL_CHAMBERS(7, "trial_chambers", Location.UNDERGROUND, BuiltinStructures.TRIAL_CHAMBERS),
		PILLAGER_OUTPOST(8, "pillager_outpost", Location.SURFACE, BuiltinStructures.PILLAGER_OUTPOST),
		JUNGLE_TEMPLE(9, "jungle_temple", Location.SURFACE, BuiltinStructures.JUNGLE_TEMPLE),
		OCEAN_MONUMENT(10, "ocean_monument", Location.WATER, BuiltinStructures.OCEAN_MONUMENT),
		ANCIENT_CITY(11, "ancient_city", Location.UNDERGROUND, BuiltinStructures.ANCIENT_CITY),
		OCEAN_RUINS(12, "ocean_ruins", Location.WATER, BuiltinStructures.OCEAN_RUIN_COLD, BuiltinStructures.OCEAN_RUIN_WARM),
		RUINED_PORTAL(13, "ruined_portal", Location.SURFACE, BuiltinStructures.RUINED_PORTAL_STANDARD,
				BuiltinStructures.RUINED_PORTAL_DESERT, BuiltinStructures.RUINED_PORTAL_JUNGLE,
				BuiltinStructures.RUINED_PORTAL_SWAMP, BuiltinStructures.RUINED_PORTAL_MOUNTAIN,
				BuiltinStructures.RUINED_PORTAL_OCEAN, BuiltinStructures.RUINED_PORTAL_NETHER);

		private final int code;
		private final String id;
		private final Location location;
		private final List<ResourceKey<Structure>> keys;

		@SafeVarargs
		Group(int code, String id, Location location, ResourceKey<Structure>... keys) {
			this.code = code;
			this.id = id;
			this.location = location;
			this.keys = List.of(keys);
		}

		private static Group fromCode(int code) {
			for (Group group : values()) if (group.code == code) return group;
			return null;
		}
	}
}
