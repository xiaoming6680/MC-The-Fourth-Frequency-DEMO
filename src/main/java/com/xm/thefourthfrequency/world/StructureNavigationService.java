package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.terminal.TerminalStructureTarget;
import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashSet;
import java.util.Set;

/** On-demand, cached structure location for the low-view-distance terminal navigator. */
public final class StructureNavigationService {
	public static final int ARRIVAL_RADIUS = 50;
	/**
	 * How far above or below a structure's real box still counts as having reached it.
	 *
	 * <p>Wide enough that standing on the roof of a village or on the surface directly over a
	 * shallow ruined portal still resolves, and narrow enough that a mineshaft sixty blocks down
	 * does not resolve from daylight.</p>
	 */
	public static final int VERTICAL_ARRIVAL_TOLERANCE = 16;
	private static boolean initialized;
	private static final StructureSearchCache SEARCHES = new StructureSearchCache();

	private StructureNavigationService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(StructureNavigationService::onServerTick);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> SEARCHES.clear());
	}

	public static int availableTargetsMask(ServerPlayer player, CompoundTag tag) {
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		if (!SurvivalMilestone.MINED_LOGS.present(milestones)) return 0;
		int hintTier = StoryProgressService.guidanceHintTier(tag);
		String objective = StoryProgressService.objective(tag,
				FrequencyWorldData.get(player.level().getServer())).id();
		int mask;
		if (player.level().dimension() == Level.NETHER) {
			mask = progressiveMask(TerminalStructureTarget.FORTRESS,
					TerminalStructureTarget.RUINED_PORTAL, TerminalStructureTarget.BASTION, hintTier);
			return withoutCompleted(mask, tag);
		}
		if (player.level().dimension() != Level.OVERWORLD) return 0;
		mask = switch (objective) {
			case "bring_iron" -> progressiveMask(TerminalStructureTarget.VILLAGE,
					TerminalStructureTarget.MINESHAFT, TerminalStructureTarget.RUINED_PORTAL, hintTier);
			// find_fortress belongs with these: the objective is in the Nether, so the only thing the
			// overworld navigator can usefully offer is the way back down.
			case "enter_nether", "find_fortress", "collect_blaze_rods", "return_from_nether" -> progressiveMask(
					TerminalStructureTarget.RUINED_PORTAL, TerminalStructureTarget.VILLAGE,
					TerminalStructureTarget.MINESHAFT, hintTier);
			case "record_eye", "find_stronghold", "enter_end", "defeat_boss", "complete" -> progressiveMask(
					TerminalStructureTarget.NONE, TerminalStructureTarget.VILLAGE,
					TerminalStructureTarget.TRIAL_CHAMBERS, hintTier);
			default -> progressiveMask(TerminalStructureTarget.VILLAGE,
					TerminalStructureTarget.MINESHAFT, TerminalStructureTarget.TRIAL_CHAMBERS, hintTier);
		};
		return withoutCompleted(mask, tag);
	}

	public static boolean selectTarget(ServerPlayer player, int wireId) {
		TerminalStructureTarget target = TerminalStructureTarget.fromWire(wireId);
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag tag = data.terminalRecord(player.getUUID()).orElse(null);
		if (target == TerminalStructureTarget.NONE || tag == null
				|| TerminalToolService.toolsDisabled(tag, player.level().getGameTime())
				|| (availableTargetsMask(player, tag) & bit(target)) == 0) return false;
		ServerLevel level = player.level();
		BlockPos origin = player.blockPosition();
		BlockPos found = SEARCHES.find(new StructureSearchCache.Key(level.dimension().identifier().toString(),
				target.id(), origin.getX() >> 4, origin.getZ() >> 4), level.getServer().getTickCount(),
				() -> level.findNearestMapStructure(target.structureTag(), origin, target.searchRadiusChunks(), false));
		long now = level.getGameTime();
		String dimension = level.dimension().identifier().toString();
		data.updateTerminalRecord(player.getUUID(), record -> {
			clearCompletion(record);
			NavigationState state = new NavigationState(target.id(), "", found != null, target.id(),
					found == null ? 0L : found.asLong(), found == null ? "" : dimension, now);
			state.writeTo(record);
		});
		return true;
	}

	public static TerminalStructureTarget selectedTarget(CompoundTag tag) {
		return TerminalStructureTarget.fromId(NavigationState.read(tag).kind());
	}

	public static boolean hasLocatedTarget(CompoundTag tag) {
		return selectedTarget(tag) != TerminalStructureTarget.NONE && NavigationState.read(tag).located();
	}

	public static void clearStructureTargetOutsideDimension(CompoundTag tag, String dimension, long now) {
		NavigationState navigation = NavigationState.read(tag);
		if (TerminalStructureTarget.fromId(navigation.kind()) == TerminalStructureTarget.NONE
				|| navigation.dimension().equals(dimension)) return;
		if (TerminalToolService.guidanceTool(tag) == TerminalTool.NAVIGATION.slot())
			tag.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalToolService.NO_TOOL);
		new NavigationState("unresolved", "", false, "", 0L, "", now).writeTo(tag);
	}


	/** Wipes the retired completion card, so saves written before it was removed do not keep one. */
	public static void clearCompletion(CompoundTag tag) {
		tag.putBoolean(TerminalData.NAVIGATION_COMPLETION_ACTIVE, false);
		tag.putBoolean(TerminalData.NAVIGATION_COMPLETION_UNREAD, false);
		tag.putLong(TerminalData.NAVIGATION_COMPLETION_POSITION, 0L);
		tag.putString(TerminalData.NAVIGATION_COMPLETION_DIMENSION, "");
		tag.putInt(TerminalData.NAVIGATION_COMPLETION_DIRECTION, 0);
	}

	/** Pure horizontal test, for callers that have no level to resolve real geometry against. */
	public static boolean arrived(BlockPos player, BlockPos target, TerminalStructureTarget structure) {
		int radius = structure == TerminalStructureTarget.NONE ? ARRIVAL_RADIUS : structure.arrivalRadius();
		return TerminalNavigationMath.withinHorizontalRadius(
				player.getX(), player.getZ(), target.getX(), target.getZ(), radius);
	}

	/**
	 * Arrival, using the structure's real geometry when the world can still supply it.
	 *
	 * <p>The located position cannot carry height - {@code StructurePlacement.getLocatePos} reports
	 * every structure at y=0 - so a horizontal-only test was the only thing the target itself
	 * supported, and it declared a mineshaft reached while the player stood on the surface eighty
	 * blocks above it. The generated structure does know where it is, though, and by the time
	 * arrival is being considered the player is close enough that the chunk holding it is usually
	 * loaded. So the check asks the world instead of the target:</p>
	 *
	 * <ol>
	 * <li>standing inside one of the structure's own pieces is arrival outright, no radius needed;</li>
	 * <li>otherwise the horizontal radius still applies, and the real bounding box adds the
	 * vertical half of the test;</li>
	 * <li>if the box cannot be resolved without forcing chunks to load, the horizontal radius
	 * decides alone, exactly as before.</li>
	 * </ol>
	 */
	public static boolean arrived(ServerLevel level, BlockPos player, BlockPos target,
			TerminalStructureTarget structure) {
		// getStructureWithPieceAt never returns null: a miss comes back as StructureStart.INVALID_START,
		// a pieceless singleton. Testing the reference would make this branch unconditionally true and
		// hand out arrival the moment guidance started, anywhere in the world.
		if (structure != TerminalStructureTarget.NONE && structure.structureTag() != null
				&& level.structureManager().getStructureWithPieceAt(player, structure.structureTag()).isValid()) {
			return true;
		}
		if (!arrived(player, target, structure)) return false;
		BoundingBox box = resolveStructureBox(level, target, structure);
		if (box == null) return true;
		return player.getY() >= box.minY() - VERTICAL_ARRIVAL_TOLERANCE
				&& player.getY() <= box.maxY() + VERTICAL_ARRIVAL_TOLERANCE;
	}

	/** @return the generated structure's box, or null when reading it would force a chunk load */
	private static BoundingBox resolveStructureBox(ServerLevel level, BlockPos target,
			TerminalStructureTarget structure) {
		if (structure == TerminalStructureTarget.NONE || structure.structureTag() == null) return null;
		if (!level.hasChunkAt(target)) return null;
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<Structure> tagged = new HashSet<>();
		for (Holder<Structure> holder : registry.getTagOrEmpty(structure.structureTag())) tagged.add(holder.value());
		if (tagged.isEmpty()) return null;
		BoundingBox box = null;
		for (StructureStart start : level.structureManager()
				.startsForStructure(new ChunkPos(target), tagged::contains)) {
			if (!start.isValid()) continue;
			box = box == null ? start.getBoundingBox() : encompass(box, start.getBoundingBox());
		}
		return box;
	}

	private static BoundingBox encompass(BoundingBox first, BoundingBox second) {
		return new BoundingBox(Math.min(first.minX(), second.minX()), Math.min(first.minY(), second.minY()),
				Math.min(first.minZ(), second.minZ()), Math.max(first.maxX(), second.maxX()),
				Math.max(first.maxY(), second.maxY()), Math.max(first.maxZ(), second.maxZ()));
	}

	private static void onServerTick(MinecraftServer server) {
		// Every other passive per-player polling service in this package (StoryProgressService,
		// SurvivalProgressService, WorldDecayService, ...) throttles its own END_SERVER_TICK
		// handler; this one previously ran a full terminalRecord() deep copy and structure search
		// setup for every online player on every single tick regardless of whether navigation was
		// even open.
		if (server.getTickCount() % 20 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) updatePlayer(player);
	}

	public static void updatePlayer(ServerPlayer player) {
		if (PrivateDimensions.isPrivate(player.level())) return;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag tag = data.terminalRecord(player.getUUID()).orElse(null);
		if (tag == null || TerminalToolService.guidanceTool(tag) != TerminalTool.NAVIGATION.slot()) return;
		NavigationState navigation = NavigationState.read(tag);
		TerminalStructureTarget target = TerminalStructureTarget.fromId(navigation.kind());
		if (target == TerminalStructureTarget.NONE || !navigation.located()
				|| !navigation.dimension().equals(player.level().dimension().identifier().toString())) return;
		BlockPos destination = BlockPos.of(navigation.position());
		if (!arrived(player.level(), player.blockPosition(), destination, target)) return;
		int direction = TerminalNavigationMath.relativeDirection(
				destination.getX() - player.getBlockX(), destination.getZ() - player.getBlockZ(), player.getYRot());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalToolService.NO_TOOL);
			record.putInt(TerminalData.COMPLETED_STRUCTURE_TARGETS_MASK,
					record.getIntOr(TerminalData.COMPLETED_STRUCTURE_TARGETS_MASK, 0)
							| TerminalStructureTarget.bit(target));
			clearCompletion(record);
			new NavigationState("unresolved", "", false, "", 0L, "", player.level().getGameTime()).writeTo(record);
		});
		// Arrival used to leave a card on the home page that the player had to close by hand, which
		// made an event that is already over sit there looking like an outstanding task. The bearing
		// it carried is the only thing worth keeping, so it moves into the notice and the whole
		// acknowledge-and-dismiss round trip goes away.
		com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
				Component.translatable("message.thefourthfrequency.navigation.structure_nearby",
						Component.translatable("terminal.thefourthfrequency.navigation.target." + target.id()),
						Component.translatable("terminal.thefourthfrequency.relative_direction."
								+ TerminalNavigationMath.relativeDirectionId(direction))));
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	private static int bit(TerminalStructureTarget target) {
		return TerminalStructureTarget.bit(target);
	}

	private static int withoutCompleted(int mask, CompoundTag tag) {
		return mask & ~tag.getIntOr(TerminalData.COMPLETED_STRUCTURE_TARGETS_MASK, 0);
	}

	private static int progressiveMask(TerminalStructureTarget primary, TerminalStructureTarget secondary,
			TerminalStructureTarget tertiary, int hintTier) {
		int mask = primary == TerminalStructureTarget.NONE ? 0 : bit(primary);
		if (hintTier >= 1 && secondary != TerminalStructureTarget.NONE) mask |= bit(secondary);
		if (hintTier >= 2 && tertiary != TerminalStructureTarget.NONE) mask |= bit(tertiary);
		return mask;
	}
}
