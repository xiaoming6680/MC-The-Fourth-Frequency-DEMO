package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.networking.TerminalAutoOpenPayload;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allocates, sites and builds Relay Station Zero, and hands each player the one terminal they get.
 *
 * <p>Siting happens exactly once and is then persisted, because {@link ZeroStationLayout} derives
 * the whole plan from the centre alone. Everything terrain-dependent therefore has to be decided
 * before the first block is placed: once building starts, the world under the station is no longer
 * evidence about the world before it.</p>
 */
public final class ZeroStationService {
	/** Placements per tick. The full plan lands in roughly a second, well inside the join fade. */
	private static final int BLOCKS_PER_TICK = 64;
	/** How far the station may walk away from the vanilla spawn to find ground worth standing on. */
	private static final int SITE_SEARCH_RADIUS = 12;
	private static final int SITE_SEARCH_STEP = 4;
	/** Weight on flatness relative to distance: a level site is worth a few blocks of walking. */
	private static final int RELIEF_WEIGHT = 8;
	/** Clear of the mast tip, so anything solid at this height above the centre means "underground". */
	private static final int BURIAL_PROBE_HEIGHT = 12;

	private static final Map<MinecraftServer, BuildState> ACTIVE_BUILDS = new IdentityHashMap<>();

	private ZeroStationService() {
	}

	public static void initialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(ZeroStationService::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(ACTIVE_BUILDS::remove);
		ServerTickEvents.END_SERVER_TICK.register(ZeroStationService::onServerTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onPlayerJoin(handler.player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			if (!alive) returnRespawnToTheStationFloor(newPlayer);
		});
	}

	/**
	 * Puts a bedless respawn back on the station floor instead of on its roof.
	 *
	 * <p>Pinning {@code respawnRadius} to 0 stops the scatter, but not this: vanilla still resolves
	 * the world spawn's <em>height</em> through {@code PlayerSpawnFinder}, which reads the surface
	 * heightmap at that column. The station has a roof, so the surface at the station's own centre
	 * is the roof - and a player with no bed respawns on top of the building they are supposed to
	 * wake up inside. The join path never saw this because it teleports explicitly.
	 *
	 * <p>Deliberately narrow. Overworld only, only within the station's own footprint - which is
	 * where a player is if and only if the world spawn is what placed them - and only when they are
	 * above the floor. A player who has a bed respawns at it and never comes near this; a player who
	 * walked onto the roof under their own power is standing on it, not arriving on it.
	 *
	 * <p>Shared by the respawn hook and the join hook. Both have the same failure: vanilla resolves
	 * the world spawn's height through the surface heightmap, and the surface of the station's own
	 * column is its roof.
	 */
	private static void returnRespawnToTheStationFloor(ServerPlayer player) {
		if (player.level().dimension() != Level.OVERWORLD) return;
		BlockPos station = FrequencyWorldData.get(player.level().getServer())
				.stationPosition().orElse(null);
		if (station == null) return;
		int reach = ZeroStationLayout.HALF_WIDTH + 2;
		if (Math.abs(player.getBlockX() - station.getX()) > reach
				|| Math.abs(player.getBlockZ() - station.getZ()) > reach
				|| player.getBlockY() <= station.getY()) return;
		player.teleportTo(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D);
	}

	private static void onServerStarted(MinecraftServer server) {
		ServerLevel level = server.overworld();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		boolean newlyAllocated = data.stationPosition().isEmpty();

		if (newlyAllocated) {
			BlockPos stationCenter = chooseSite(level, level.getRespawnData().pos());
			data.allocateStation(stationCenter);
			prepareSafeCenter(level, stationCenter);
			pinRespawnToTheStation(server, level);
			TheFourthFrequency.LOGGER.info("Allocated Relay Station Zero at {}", stationCenter);
		}

		BlockPos stationCenter = data.stationPosition().orElseThrow();
		// Nothing the station builds reaches this high, so solid ground above the centre means the
		// site is not a surface at all. An absolute Y bound cannot say this: a superflat world's
		// surface sits four blocks off the world floor. Re-siting would move a spawn a player may
		// already have built around, so this only says it out loud - such a save needs a new world.
		if (!level.getBlockState(stationCenter.above(BURIAL_PROBE_HEIGHT)).isAir()) {
			TheFourthFrequency.LOGGER.warn("Relay Station Zero at {} is buried: {} sits above it. "
							+ "This station is unreachable and the save needs to be recreated.",
					stationCenter, level.getBlockState(stationCenter.above(BURIAL_PROBE_HEIGHT)));
		}
		// Yaw 180 faces north, at the equipment wall. Waking up already looking at the empty rack is
		// the only introduction the station gets.
		level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, stationCenter, 180.0F, 0.0F));
		if (!data.stationComplete()) {
			List<ZeroStationLayout.Placement> plan = ZeroStationLayout.create(stationCenter);
			int cursor = Math.min(data.stationBuildCursor(), plan.size());
			ACTIVE_BUILDS.put(server, new BuildState(plan, cursor));
		}
	}

	/**
	 * Makes the world spawn mean the station centre rather than somewhere near it.
	 *
	 * <p>Setting the respawn position is not on its own enough. {@code PlayerSpawnFinder} scatters
	 * every arrival that has no bed of its own across a disc of {@code respawnRadius} blocks around
	 * that point, and the vanilla default is ten - wider than the station is. So the host, who is
	 * placed once at world creation, wakes up inside; and everyone joining afterwards, plus everyone
	 * respawning without a bed, is dropped somewhere in the surrounding terrain. From their side the
	 * station is a building they have to find rather than the room the story starts in.
	 *
	 * <p>Written once, on the tick the station is first allocated, and never again: it is the
	 * opening of the story rather than a permanent claim on the rule, so a table that later sets its
	 * own spawn radius keeps it.
	 *
	 * <p>The per-player teleport in {@link #onPlayerJoin} stays as it was. It covers the first
	 * arrival, which is also the one the terminal is handed out on; this covers every later one, and
	 * every death without a bed.
	 */
	private static void pinRespawnToTheStation(MinecraftServer server, ServerLevel level) {
		level.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, server);
	}

	/**
	 * Picks the station centre near the vanilla spawn: level ground, no liquid in the footprint,
	 * and as little walking from the original spawn as the first two conditions allow.
	 *
	 * <p>The search is deliberately short-ranged. Including the footprint it spans at most a 3x3
	 * block of chunks around spawn, all of which level preparation has already generated - but see
	 * {@link #surfaceHeight} for why "already generated" is not the same as "safe to ask".</p>
	 */
	private static BlockPos chooseSite(ServerLevel level, BlockPos spawn) {
		BlockPos best = null;
		long bestScore = Long.MAX_VALUE;
		for (int dx = -SITE_SEARCH_RADIUS; dx <= SITE_SEARCH_RADIUS; dx += SITE_SEARCH_STEP) {
			for (int dz = -SITE_SEARCH_RADIUS; dz <= SITE_SEARCH_RADIUS; dz += SITE_SEARCH_STEP) {
				int[] heights = sampleFootprint(level, spawn.getX() + dx, spawn.getZ() + dz);
				if (heights == null) {
					continue;
				}
				int relief = heights[heights.length - 1] - heights[0];
				long score = (long) relief * RELIEF_WEIGHT + Math.abs(dx) + Math.abs(dz);
				if (score < bestScore) {
					bestScore = score;
					best = new BlockPos(spawn.getX() + dx, heights[heights.length / 2], spawn.getZ() + dz);
				}
			}
		}
		if (best != null) {
			return best;
		}
		// Every candidate was rejected. A station on bad ground still beats no station, and the
		// foundation courses are deep enough to give the platform something to stand on regardless.
		return new BlockPos(spawn.getX(), surfaceHeight(level, spawn.getX(), spawn.getZ()), spawn.getZ());
	}

	/**
	 * Surface heights across the station footprint, sorted ascending, or {@code null} if any sample
	 * stands in liquid or could not be read. Corners and edge midpoints are enough: the layout has
	 * no interior column that can be supported while a corner is not.
	 */
	private static int[] sampleFootprint(ServerLevel level, int centerX, int centerZ) {
		int[] heights = new int[9];
		int index = 0;
		for (int dx = -ZeroStationLayout.HALF_WIDTH; dx <= ZeroStationLayout.HALF_WIDTH;
				dx += ZeroStationLayout.HALF_WIDTH) {
			for (int dz = -ZeroStationLayout.HALF_DEPTH; dz <= ZeroStationLayout.HALF_DEPTH;
					dz += ZeroStationLayout.HALF_DEPTH) {
				int x = centerX + dx;
				int z = centerZ + dz;
				int height = surfaceHeight(level, x, z);
				if (height <= level.getMinY()
						|| !level.getFluidState(new BlockPos(x, height - 1, z)).isEmpty()) {
					return null;
				}
				heights[index++] = height;
			}
		}
		Arrays.sort(heights);
		if (holdsBlockEntities(level, centerX, centerZ, heights[heights.length / 2])) return null;
		return heights;
	}

	/**
	 * Whether anything with a block entity stands where the station would be built.
	 *
	 * <p>Rejecting those sites is the only way to keep a buried treasure or a shipwreck chest out of
	 * the station's footprint, and there is no version of overwriting one that is acceptable. The
	 * clearing pass sets air with {@code UPDATE_NEIGHBORS}, and {@code LevelChunk#setBlockState} calls
	 * {@code affectNeighborsAfterRemoval} whenever that bit is set - which is where a container spills
	 * its inventory. So the loot ends up strewn across the beach: not lost, but scattered by something
	 * the player never did, with no way to tell what it was. Dropping the bit instead would keep the
	 * items in the chest but leave sand and gravel unsettled; deleting the contents outright would be
	 * exactly the silent swallowing this mod promises never to do.
	 *
	 * <p>Read per chunk rather than per block: the map a chunk already keeps is a handful of entries,
	 * where probing every column of the footprint would be several hundred lookups per candidate.
	 *
	 * @param surfaceY the footprint's median surface, which the vertical envelope is measured from
	 */
	private static boolean holdsBlockEntities(ServerLevel level, int centerX, int centerZ, int surfaceY) {
		int minX = centerX - ZeroStationLayout.HALF_WIDTH;
		int maxX = centerX + ZeroStationLayout.HALF_WIDTH;
		// The porch reaches two blocks past the hull on the near side; take that on both, cheaply.
		int minZ = centerZ - ZeroStationLayout.HALF_DEPTH - 2;
		int maxZ = centerZ + ZeroStationLayout.HALF_DEPTH + 2;
		int minY = surfaceY + ZeroStationLayout.FOUNDATION_BOTTOM;
		int maxY = surfaceY + ZeroStationLayout.WALL_TOP + 2;
		for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
				if (!level.hasChunk(chunkX, chunkZ)) continue;
				for (BlockPos occupied : level.getChunk(chunkX, chunkZ).getBlockEntities().keySet()) {
					if (occupied.getX() >= minX && occupied.getX() <= maxX
							&& occupied.getZ() >= minZ && occupied.getZ() <= maxZ
							&& occupied.getY() >= minY && occupied.getY() <= maxY) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * The surface height of one column, with its chunk requested first.
	 *
	 * <p>{@link ServerLevel#getHeight} does not load anything: for a chunk that is not currently
	 * loaded it silently answers with the world floor instead of failing. Reading a whole footprint
	 * that way scores a column of bedrock as perfectly level ground, which is how the station once
	 * ended up assembled at Y = -64 inside solid rock.</p>
	 */
	private static int surfaceHeight(ServerLevel level, int x, int z) {
		level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/**
	 * The centre column, made safe before the first batched placement. A player can connect during
	 * the very tick the station is allocated, and they land here.
	 */
	private static void prepareSafeCenter(ServerLevel level, BlockPos center) {
		level.setBlockAndUpdate(center.below(), Blocks.STONE_BRICKS.defaultBlockState());
		level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(center.above(), Blocks.AIR.defaultBlockState());
	}

	private static void onServerTick(MinecraftServer server) {
		BuildState build = ACTIVE_BUILDS.get(server);
		if (build == null) {
			return;
		}

		ServerLevel level = server.overworld();
		if (build.sweepTicksLeft >= 0) {
			if (build.sweepTicksLeft % DROP_SWEEP_INTERVAL == 0) {
				sweepConstructionDrops(level, build.plan);
			}
			if (--build.sweepTicksLeft < 0) ACTIVE_BUILDS.remove(server);
			return;
		}
		int end = Math.min(build.cursor + BLOCKS_PER_TICK, build.plan.size());
		for (int index = build.cursor; index < end; index++) {
			ZeroStationLayout.Placement placement = build.plan.get(index);
			// Most of the plan is the cleared envelope, and most of that is already air. Skipping the
			// write keeps the batch honest: the budget counts placements, not block updates.
			if (level.getBlockState(placement.position()).equals(placement.state())) {
				continue;
			}
			// Never write over a block entity. Siting already rejects footprints that hold one, so
			// this is the case where every candidate held one and the fallback site was used anyway -
			// and a chest is worth more than a complete wall. Clearing it would call
			// affectNeighborsAfterRemoval and empty its inventory onto the ground; leaving it means
			// the station has a gap with somebody's loot still in it, which the player can at least
			// read as a thing that was already here.
			if (level.getBlockEntity(placement.position()) != null) continue;
			// SUPPRESS_DROPS is belt and braces: setBlock does not run loot tables the way
			// destroyBlock does, so ordinary terrain does not drop either way. It costs nothing and
			// says what is intended if that ever stops being true.
			level.setBlock(placement.position(), placement.state(),
					Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
		}
		build.cursor = end;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		data.advanceStationBuildCursor(build.cursor, build.plan.size());

		if (build.cursor == build.plan.size() && build.sweepTicksLeft < 0) {
			build.sweepTicksLeft = DROP_SWEEP_TICKS;
			TheFourthFrequency.LOGGER.info("Relay Station Zero initialization completed in {} bounded placements",
					build.plan.size());
		}
	}

	/**
	 * How long the drop sweep keeps running after the last block is placed.
	 *
	 * <p>One pass on the completion tick is not enough, which is what the first attempt at this did.
	 * Suppressing drops at the placement covers what the plan itself removes, but the aftermath
	 * arrives late and on its own schedule: sand and gravel above the envelope have to fall, and
	 * leaves left without a log decay on the random tick - which can be most of a minute later, and
	 * lands wherever the drops scatter to. A player who walks out of the station and finds sand on
	 * the beach is looking at that.
	 */
	private static final int DROP_SWEEP_TICKS = 20 * 90;
	/** How often the sweep actually looks. Every tick would be an entity query for nothing. */
	private static final int DROP_SWEEP_INTERVAL = 10;
	/** Slack past the plan's own volume, for drops that scattered on the way down. */
	private static final double DROP_SWEEP_SLACK = 6.0D;

	/**
	 * Clears the items the build left behind, once, on the tick it finishes.
	 *
	 * <p>Suppressing drops at the placement covers what the plan itself removes, and that is most of
	 * it - but not all. Clearing a block updates its neighbours, so sand and gravel above the
	 * envelope fall, floating trees shed their leaves, and anything that was attached to a wall the
	 * station replaced breaks on its own a tick later. None of that goes through
	 * {@code setBlock}'s flags, and all of it lands on the floor of a room whose entire point is
	 * that there is nothing in it.
	 *
	 * <p>Two things bound it. The plan's own volume plus {@link #DROP_SWEEP_SLACK}, so it can only
	 * reach where the construction reached; and {@code getOwner() == null}, which is the difference
	 * between an item a block turned into and an item a player threw. A player who empties their
	 * inventory onto the station floor during the sweep window keeps every bit of it - their drops
	 * carry a thrower, and terrain's never do.
	 */
	private static void sweepConstructionDrops(ServerLevel level, List<ZeroStationLayout.Placement> plan) {
		if (plan.isEmpty()) return;
		BlockPos first = plan.getFirst().position();
		int minX = first.getX();
		int minY = first.getY();
		int minZ = first.getZ();
		int maxX = minX;
		int maxY = minY;
		int maxZ = minZ;
		for (ZeroStationLayout.Placement placement : plan) {
			BlockPos position = placement.position();
			minX = Math.min(minX, position.getX());
			minY = Math.min(minY, position.getY());
			minZ = Math.min(minZ, position.getZ());
			maxX = Math.max(maxX, position.getX());
			maxY = Math.max(maxY, position.getY());
			maxZ = Math.max(maxZ, position.getZ());
		}
		AABB volume = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)
				.inflate(DROP_SWEEP_SLACK);
		int removed = 0;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, volume,
				item -> item.getOwner() == null)) {
			item.discard();
			removed++;
		}
		if (removed > 0) {
			TheFourthFrequency.LOGGER.info("Cleared {} item(s) left by Relay Station Zero construction",
					removed);
		}
	}

	/**
	 * Hands out the one terminal a player gets, unless this world has already finished with them.
	 *
	 * <p>Past the resolution there is nothing for a new terminal to do. Anomalies, empty segments and
	 * pursuits are closed for good by {@link FinaleRuntimePolicy}, the mainline it tracks has been
	 * answered, and the encounter it exists to reach cannot happen twice - so someone joining a
	 * finished server used to be handed a device that would never speak again and never say why. That
	 * is the one thing the terminal is not allowed to be: a piece of operable information that is
	 * quietly wrong rather than explicitly unavailable.
	 *
	 * <p>The grant ledger is deliberately left untouched here rather than marked and skipped. Not
	 * being on it is what keeps every per-player service from building state for someone the story
	 * has no more of, and it is also what would let a later save that reopens the frequency issue this
	 * player their first terminal properly.
	 *
	 * <p>Nothing about arrival changes: the world spawn is the station either way, so a newcomer still
	 * wakes up in the room. They wake up in it with the rack empty, which is true.
	 */
	public static boolean issueTerminalIfNeeded(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		// Asked before the finale gate, not after it. Someone who already holds a terminal is not
		// being issued one, and telling them the frequency is closed on every single login for the
		// rest of the save is not information, it is a countdown they cannot stop.
		if (data.hasTerminalIssued(player.getUUID())) {
			return false;
		}
		if (FinaleRuntimePolicy.concluded(data)) {
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
					Component.translatable("message.thefourthfrequency.terminal.frequency_closed"));
			return false;
		}
		if (!data.markTerminalIssued(player.getUUID())) {
			return false;
		}

		ItemStack terminal = TerminalData.stackFromRecord(data.ensureTerminalRecord(player));
		if (!player.addItem(terminal)) {
			player.drop(terminal, false);
		}
		// One dispense is one moment; the empty rack was never a separate event worth its own line.
		com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
				Component.translatable("message.thefourthfrequency.terminal.dispensed"));
		return true;
	}

	private static void onPlayerJoin(ServerPlayer player) {
		// Before the terminal check, and independent of it. Vanilla places an arriving player at the
		// world spawn's *column surface*, which over the station is its roof - and the teleport below
		// only runs for someone being handed a terminal, so on a server every guest who had already
		// been issued one, and every rejoin, kept arriving on top of the building.
		returnRespawnToTheStationFloor(player);
		if (!issueTerminalIfNeeded(player)) {
			return;
		}
		FrequencyWorldData.get(player.level().getServer()).stationPosition().ifPresent(position ->
				player.teleportTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5));
		// The device brings itself up, once, on the join that hands it over. Waking in the station
		// and having to find the thing in a hotbar first puts an inventory in front of the moment
		// the story opens on; the terminal binding itself to someone is the moment.
		//
		// Only ever sent from here, so it is bounded by the same ledger the grant is: a player who
		// already has a terminal is not being issued one and does not get this either. The client
		// decides when, and answers with the ordinary open request - see TerminalAutoOpenPolicy.
		if (ServerPlayNetworking.canSend(player, TerminalAutoOpenPayload.TYPE)) {
			ServerPlayNetworking.send(player, new TerminalAutoOpenPayload());
		}
	}

	private static final class BuildState {
		private final List<ZeroStationLayout.Placement> plan;
		private int cursor;
		/** Ticks of drop sweeping left once the placements are done. Negative while still building. */
		private int sweepTicksLeft = -1;

		private BuildState(List<ZeroStationLayout.Placement> plan, int cursor) {
			this.plan = plan;
			this.cursor = cursor;
		}
	}
}
