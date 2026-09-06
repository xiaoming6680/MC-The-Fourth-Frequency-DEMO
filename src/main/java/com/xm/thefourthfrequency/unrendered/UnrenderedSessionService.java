package com.xm.thefourthfrequency.unrendered;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.entity.BacteriaEntity;
import com.xm.thefourthfrequency.networking.UnrenderedPhasePayload;
import com.xm.thefourthfrequency.pursuit.PursuitDimensions;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import com.xm.thefourthfrequency.pursuit.PursuitReturnLocator;
import com.xm.thefourthfrequency.terminal.AnomalyCompletionStatus;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.MaximumHealthAdjustment;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every way into and out of the unrendered layer, including the ones nobody chooses.
 *
 * <p>The layer takes a player out of the world they were standing in, so the only state that
 * genuinely matters here is the return address, and it is written to the persistent record
 * <em>before</em> the teleport rather than after. Everything else in this class exists to make sure
 * that address is eventually used: the way out on foot, the timeout behind it, the entity, a
 * disconnect, a death, an operator's teleport, and a server that stops while somebody is still down
 * there.
 *
 * <p>Nothing about the layer is negotiated with the client. The way out is the player walking into a
 * hole in the floor, which the server sees as a y coordinate below the build limit; the client is
 * never asked and cannot claim it.
 */
public final class UnrenderedSessionService {
	/**
	 * The black screen between the floor giving way and standing in the layer.
	 *
	 * <p>One second, and it is doing real work rather than dressing. A cross-dimension teleport makes
	 * the client load and light a chunk it has never seen; without cover the player watches that
	 * happen, and a floor plan assembling itself in front of them explains exactly how the place is
	 * made. It also gives the destination chunk a tick to finish generating before anyone stands in
	 * it.
	 */
	public static final int ENTRY_BLACKOUT_TICKS = 20;

	/**
	 * Black held on past the arrival, on top of the twenty spent getting there.
	 *
	 * <p>Without it the cover was sized to exactly the gap between raising it and the teleport, so it
	 * expired on the frame the destination appeared and the player's first sight of the layer was it
	 * finishing loading. The hold has to outlast the transition, not match it.
	 *
	 * <p>Worth a second on its own, because {@code UnrenderedLayerClient} stops this clock while a
	 * loading screen is up: these twenty ticks are twenty ticks of black the player actually sees,
	 * however long the chunk load took to get there.
	 */
	public static final int ENTRY_ARRIVAL_HOLD_TICKS = 20;

	/**
	 * How long the player watches themselves go through the floor before the screen covers it.
	 *
	 * <p>Half a second, and it is the half second the whole entry was missing. The blackout used to
	 * be raised on the same tick the anomaly fired, so what the player experienced was standing still
	 * and then being somewhere else - the floor giving way, which is the fiction the layer is built
	 * on and the thing every part of it refers back to, was never shown at all.
	 *
	 * <p>Ten ticks is enough to read as a fall and short enough not to become one: the camera sinks
	 * through the floor into the dark under it and the cover comes up while it is still moving, so
	 * the last thing seen is a descent rather than a stop. It is drawn by the client and moves
	 * nothing - see {@code UnrenderedLayerClient.cameraOffset}. The player's body, position and
	 * collisions are exactly where they were, which is what keeps a presentation from becoming a
	 * teleport into the ground with suffocation damage attached.
	 */
	public static final int ENTRY_FALL_TICKS = 10;

	/**
	 * How long the capture holds the screen, and when the return happens inside it.
	 *
	 * <p>The hold covers the scream, which is a little under four seconds. The teleport goes at
	 * thirty ticks so the player is already back in the world when the sound ends: the alternative -
	 * returning them after it - means a second and a half of black silence, which reads as the game
	 * having hung rather than as something having happened to them.
	 */
	/**
	 * Pause before another entity is placed once the first has left the world.
	 *
	 * <p>Twenty seconds. Long enough that the player gets to believe they lost it, which is the only
	 * reason its disappearance is worth anything, and short enough that the rest of the session is
	 * not empty.
	 */
	private static final int STALKER_REPLACE_DELAY_TICKS = 20 * 20;

	private static final int CAPTURE_HOLD_TICKS = 80;
	private static final int CAPTURE_RETURN_TICKS = 30;

	/**
	 * The blackout over the way out, and when the sky teleport happens inside it.
	 *
	 * <p>Longer than the entry cover, because it is hiding more. Arriving two hundred blocks above
	 * the overworld means the client has to stream and light every chunk between the player and the
	 * ground, and that is the one load in this whole feature the player must not watch happen - the
	 * moment is supposed to be the world appearing underneath them all at once, not assembling
	 * itself. The teleport goes early in the window so the rest of it is spent loading.
	 */
	private static final int EXIT_HOLD_TICKS = 40;
	private static final int EXIT_TELEPORT_TICKS = 8;

	/**
	 * How high above the return point the player is handed back.
	 *
	 * <p>Far enough that the ground reads as a map rather than as a floor, and that the fall lasts
	 * long enough to be a moment rather than a stumble - about six seconds. Clamped to the level's
	 * own ceiling, because a return point on a mountain plus a fixed offset is a teleport into the
	 * build limit.
	 */
	private static final int SKY_RETURN_HEIGHT = 200;
	private static final int SKY_RETURN_HEADROOM = 8;

	/** Give-up point for the descent bookkeeping, if a player somehow never lands. */
	private static final int DESCENT_TIMEOUT_TICKS = 20 * 60;

	private static final String EXIT_FOUND = "exit_found";
	private static final String CAPTURED = "captured";
	private static final String EXPIRED = "expired";

	/**
	 * When the standing bearing starts, and how often it is rewritten.
	 *
	 * <p>Tied to the entity rather than to a clock of its own. The order is still what it always
	 * was - the first minute of the layer gives the player nothing at all, then a reason to run, then
	 * a direction to run in - but the reason and the direction now come from the same event, so the
	 * two can no longer drift apart if either is ever retimed.
	 *
	 * <p>It is a standing readout rather than an occasional notice: the action bar holds a line for
	 * sixty ticks and then fades it, so rewriting it every forty keeps it up continuously without it
	 * ever visibly blinking. That is the difference between an instrument and an alert, and after the
	 * entity is placed this is the only instrument the player has.
	 */
	private static final int BEARING_AFTER_ALERT_TICKS = 20 * 3;
	private static final int BEARING_REFRESH_TICKS = 40;

	private static final Map<UUID, PendingEntry> PENDING = new HashMap<>();
	private static final Map<UUID, PendingCapture> CAPTURES = new HashMap<>();
	private static final Map<UUID, PendingExit> EXITS = new HashMap<>();
	/**
	 * Players falling out of the sky on their way back, and the tick they started.
	 *
	 * <p>Deliberately outlives the session it came from. The session is over the moment they are put
	 * back in their own world; the fall is the presentation of that, and it still has to be survived.
	 */
	private static final Map<UUID, Integer> DESCENDING = new HashMap<>();
	private static final Map<UUID, Runtime> RUNTIME = new HashMap<>();
	private static boolean initialized;

	private UnrenderedSessionService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(UnrenderedSessionService::tick);
		ServerPlayerEvents.JOIN.register(UnrenderedSessionService::recoverOnJoin);
		ServerPlayerEvents.LEAVE.register(UnrenderedSessionService::deferOnLeave);
		// A player who died in the layer has already been placed at their respawn point by vanilla.
		// The session still has to be booked closed, but moving them again would undo that.
		ServerPlayerEvents.AFTER_RESPAWN.register(
				(oldPlayer, newPlayer, alive) -> abandon(newPlayer, "respawned"));
		ServerLifecycleEvents.SERVER_STOPPING.register(UnrenderedSessionService::returnEveryone);
	}

	/** How many layer sessions are running right now, against {@link UnrenderedAnchorPolicy#MAX_CONCURRENT}. */
	public static int activeCount() {
		return RUNTIME.size();
	}

	public static boolean inLayer(ServerPlayer player) {
		return player != null && UnrenderedDimensions.isUnrendered(player.level());
	}

	/**
	 * Whether this player could be taken now, asked before the anomaly is drawn.
	 *
	 * <p>Every clause is a place the layer would be destructive rather than unsettling. Somebody
	 * already in a private mirror has a chase to be returned from and one return address; somebody in
	 * the End is either at the finale or on their way to it, and a six-minute detour there is not a
	 * scare, it is a loss.
	 */
	public static boolean available(ServerPlayer player) {
		return unavailableReason(player) == Unavailable.NONE;
	}

	/**
	 * Why this player cannot be taken, or {@link Unavailable#NONE} if they can.
	 *
	 * <p>Named rather than merely counted so the debug panel can say which clause refused. A
	 * developer who presses trigger and is told "the environment does not meet the conditions" has
	 * been given the same message for a full layer, a running chase and a missing dimension - three
	 * problems with nothing in common and only one of which is worth investigating.
	 */
	public static Unavailable unavailableReason(ServerPlayer player) {
		if (player == null || player.isRemoved()) return Unavailable.NO_PLAYER;
		if (!(player.level() instanceof ServerLevel level)) return Unavailable.NO_PLAYER;
		if (UnrenderedDimensions.isUnrendered(level)) return Unavailable.ALREADY_IN_LAYER;
		if (PursuitDimensions.isMirror(level)) return Unavailable.IN_MIRROR;
		if (level.dimension().equals(Level.END)) return Unavailable.IN_THE_END;
		if (PENDING.containsKey(player.getUUID()) || CAPTURES.containsKey(player.getUUID())) {
			return Unavailable.TRANSITION_IN_FLIGHT;
		}
		FrequencyWorldData data = FrequencyWorldData.get(level.getServer());
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return Unavailable.NO_TERMINAL_RECORD;
		if (record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)) return Unavailable.PURSUIT_ACTIVE;
		if (record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false)) return Unavailable.SESSION_ACTIVE;
		// Ranked with the pursuit, not with the anomalies it is catalogued beside: twenty to thirty
		// minutes between events rather than the few minutes an ordinary stage-five entry may take.
		if (level.getGameTime() < record.getLongOr(TerminalData.UNRENDERED_NEXT_ELIGIBLE_TICK, 0L)) {
			return Unavailable.COOLING_DOWN;
		}
		if (level.getServer().getLevel(UnrenderedDimensions.UNRENDERED_LAYER) == null) {
			return Unavailable.DIMENSION_MISSING;
		}
		if (!UnrenderedAnchorManager.hasCapacity()) return Unavailable.NO_FREE_SLOT;
		return Unavailable.NONE;
	}

	/** The reasons {@link #unavailableReason} can give. Surfaced by the debug panel. */
	public enum Unavailable {
		NONE, NO_PLAYER, ALREADY_IN_LAYER, IN_MIRROR, IN_THE_END, TRANSITION_IN_FLIGHT,
		NO_TERMINAL_RECORD, PURSUIT_ACTIVE, SESSION_ACTIVE, DIMENSION_MISSING, NO_FREE_SLOT,
		COOLING_DOWN
	}

	/**
	 * Books the return address and raises the blackout. The teleport happens in {@link #tick} once
	 * the cover is up.
	 */
	public static boolean begin(ServerPlayer player, long durationTicks) {
		if (!available(player)) return false;
		ServerLevel source = (ServerLevel) player.level();
		FrequencyWorldData data = FrequencyWorldData.get(source.getServer());
		int visit = data.terminalRecord(player.getUUID())
				.map(record -> record.getIntOr(TerminalData.UNRENDERED_VISITS, 0)).orElse(0);
		var lease = UnrenderedAnchorManager.acquire(player.getUUID(), visit).orElse(null);
		if (lease == null) return false;

		BlockPos origin = player.blockPosition();
		String sessionId = UUID.randomUUID().toString();
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putBoolean(TerminalData.UNRENDERED_ACTIVE, true);
			record.putString(TerminalData.UNRENDERED_SESSION_ID, sessionId);
			record.putString(TerminalData.UNRENDERED_SOURCE_DIMENSION,
					source.dimension().identifier().toString());
			record.putLong(TerminalData.UNRENDERED_SOURCE_POSITION, origin.asLong());
			record.putDouble(TerminalData.UNRENDERED_SOURCE_YAW, player.getYRot());
			record.putDouble(TerminalData.UNRENDERED_SOURCE_PITCH, player.getXRot());
			record.putInt(TerminalData.UNRENDERED_SLOT, lease.slot());
			record.putLong(TerminalData.UNRENDERED_STARTED_TICK, source.getGameTime());
			record.putLong(TerminalData.UNRENDERED_DURATION_TICKS, durationTicks);
			record.putInt(TerminalData.UNRENDERED_VISITS, visit + 1);
		});
		int now = source.getServer().getTickCount();
		PENDING.put(player.getUUID(), new PendingEntry(player.getUUID(), sessionId, lease,
				now + ENTRY_FALL_TICKS, now + ENTRY_FALL_TICKS + ENTRY_BLACKOUT_TICKS));
		RUNTIME.put(player.getUUID(), new Runtime(sessionId));
		sendPhase(player, UnrenderedPhasePayload.FALL, ENTRY_FALL_TICKS);
		return true;
	}

	/**
	 * Reached by the entity.
	 *
	 * <p><b>The player does not die.</b> What the player is shown is a death - the screen goes black,
	 * they hear it reach them, and they wake up at their respawn point - but no damage is dealt and
	 * no death is recorded, which is the only way to get that presentation without also getting the
	 * death screen, the death message in chat, a dropped inventory and a statistic. Every one of
	 * those would have to be suppressed individually otherwise, and each is a place where a bug
	 * leaves somebody stranded in a dimension with no floor.
	 */
	public static boolean capture(ServerPlayer player) {
		if (!inLayer(player) || CAPTURES.containsKey(player.getUUID())) return false;
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		if (!data.terminalRecord(player.getUUID())
				.map(record -> record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false))
				.orElse(false)) {
			return false;
		}
		sendPhase(player, UnrenderedPhasePayload.CAPTURE, CAPTURE_HOLD_TICKS);
		CAPTURES.put(player.getUUID(),
				new PendingCapture(player.getUUID(), server.getTickCount() + CAPTURE_RETURN_TICKS));
		despawnStalker(server, player.getUUID());
		return true;
	}

	/**
	 * Puts the player back where they were taken from and closes the session.
	 *
	 * <p>The return point is the entry point, not wherever they were standing in the layer. That is
	 * the opposite of the rule a pursuit follows, and for the opposite reason: a mirror is a copy of
	 * a real world, so a position in it means something outside, while a position in the layer means
	 * nothing at all - two hundred blocks walked here would put somebody two hundred blocks into
	 * terrain they never crossed.
	 */
	public static boolean returnToSource(ServerPlayer player, String resolution) {
		if (player == null || player.isRemoved()) return false;
		PENDING.remove(player.getUUID());
		EXITS.remove(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) {
			UnrenderedAnchorManager.release(player.getUUID());
			return false;
		}
		// Idempotent by the flag rather than by the caller. The anomaly's own cleanup runnable calls
		// this too, immediately after finish() has already returned the player - at which point the
		// return address has been blanked, so a second teleport would read the cleared fields and
		// drop them at world spawn instead of where they were taken from.
		if (!record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false)) return false;
		ServerLevel source = sourceLevel(server,
				record.getStringOr(TerminalData.UNRENDERED_SOURCE_DIMENSION, ""));
		BlockPos entry = sourceEntry(record, source);
		BlockPos safe = PursuitReturnLocator.find(source, entry);
		float yaw = (float) record.getDoubleOr(TerminalData.UNRENDERED_SOURCE_YAW, player.getYRot());
		float pitch = (float) record.getDoubleOr(TerminalData.UNRENDERED_SOURCE_PITCH, player.getXRot());
		place(player, source, safe, yaw, pitch);
		clearSession(data, player, resolution);
		return true;
	}

	/**
	 * Closes a session for a player who is already out of the layer, without moving them.
	 *
	 * <p>{@link #returnToSource} is wrong for them in the same way it is wrong for a pursuit that
	 * ended outside its mirror: it would pull somebody off the respawn point vanilla just gave them,
	 * or silently undo an operator's teleport a tick after it landed. The bookkeeping still has to
	 * run, because a leaked slot is one of sixteen and a record left active refuses every future
	 * entry for that player.
	 */
	public static boolean abandon(ServerPlayer player, String resolution) {
		if (player == null) return false;
		PENDING.remove(player.getUUID());
		CAPTURES.remove(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		if (data.terminalRecord(player.getUUID()).isEmpty()) {
			despawnStalker(server, player.getUUID());
			UnrenderedAnchorManager.release(player.getUUID());
			RUNTIME.remove(player.getUUID());
			return false;
		}
		if (!data.terminalRecord(player.getUUID())
				.map(record -> record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false))
				.orElse(false)) {
			return false;
		}
		clearSession(data, player, resolution);
		return true;
	}

	private static void tick(MinecraftServer server) {
		completePendingEntries(server);
		completeCaptures(server);
		completeSkyReturns(server);
		tickDescents(server);
		if (RUNTIME.isEmpty()) return;
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			UUID playerId = player.getUUID();
			if (!RUNTIME.containsKey(playerId)) continue;
			if (PENDING.containsKey(playerId) || CAPTURES.containsKey(playerId)
					|| EXITS.containsKey(playerId)) continue;
			if (!inLayer(player)) {
				// Out of the layer without going through any exit: an operator teleport, or a respawn
				// whose event has not run yet. Close the books, leave the player alone.
				abandon(player, "left_layer");
				continue;
			}
			if (player.getY() < UnrenderedLayerLayout.voidThreshold()) {
				finish(player, EXIT_FOUND);
				continue;
			}
			if (expired(server, player)) {
				finish(player, EXPIRED);
				continue;
			}
			maybeSpawnStalker(server, player);
			maybeSendBearing(server, player);
		}
	}

	/**
	 * Places the entity once the player has had the layer to themselves for long enough.
	 *
	 * <p>Deliberately keyed off elapsed session time rather than distance walked. Standing still is a
	 * legitimate response to arriving here and it must not be the one that keeps you safe.
	 */
	private static void maybeSpawnStalker(MinecraftServer server, ServerPlayer player) {
		Runtime runtime = RUNTIME.get(player.getUUID());
		if (runtime == null) return;
		if (runtime.stalker != null && !stillPresent(server, runtime.stalker)) {
			// It followed the player over the edge of a floor opening and left the world. Forgetting
			// the reference is what lets the block below place another, after the same delay - so the
			// player gets the pause they would get on arrival rather than an instant replacement
			// beside them.
			runtime.stalker = null;
			runtime.replaceAfterTick = server.getTickCount() + STALKER_REPLACE_DELAY_TICKS;
		}
		if (runtime.stalker != null || server.getTickCount() < runtime.replaceAfterTick) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return;
		long started = record.getLongOr(TerminalData.UNRENDERED_STARTED_TICK, 0L);
		if (player.level().getGameTime() - started < UnrenderedAnomaly.ENTITY_DELAY_TICKS) return;
		if (!(player.level() instanceof ServerLevel layer)) return;

		int[] spot = null;
		for (int attempt = 0; attempt < UnrenderedStalkerPolicy.directionCount(); attempt++) {
			int[] candidate = UnrenderedStalkerPolicy.spawnPosition(
					player.getBlockX(), player.getBlockZ(), runtime.sessionId.hashCode() + attempt);
			double dx = candidate[0] - player.getX();
			double dz = candidate[1] - player.getZ();
			if (dx * dx + dz * dz >= UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE
					* (double) UnrenderedStalkerPolicy.MIN_SPAWN_DISTANCE) {
				spot = candidate;
				break;
			}
		}
		if (spot == null) return;

		BacteriaEntity stalker = ModEntities.BACTERIA.create(layer, EntitySpawnReason.EVENT);
		if (stalker == null) return;
		stalker.snapTo(spot[0] + 0.5D, UnrenderedAnchorPolicy.entryY(), spot[1] + 0.5D, 0.0F, 0.0F);
		stalker.bind(player);
		if (!layer.addFreshEntity(stalker)) return;
		runtime.stalker = stalker.getUUID();
		// The one thing the terminal is willing to say about the entity, and it says it once. It
		// names no direction and no distance: the heartbeat carries both, and a readout that told
		// the player where the thing was would replace the listening that the layer is made of.
		// The mod's notice panel, not vanilla's action bar - which is bare text with no backdrop, and
		// this is a wall of bright yellow. Same route and same slate panel the bearing uses, because
		// they are the same voice saying two halves of one thing.
		TerminalNoticeService.unrendered(player,
				Component.translatable("message.thefourthfrequency.unrendered.anomalous_signal"));
		// The bearing follows, a beat later rather than on the same tick. Two lines arriving in the
		// same frame on one line of screen means the first was never shown.
		runtime.nextBearingTick = server.getTickCount() + BEARING_AFTER_ALERT_TICKS;
		TheFourthFrequency.LOGGER.debug("Unrendered layer stalker placed {} blocks from {}",
				Math.round(Math.sqrt(stalker.distanceToSqr(player))), player.getGameProfile().name());
	}

	/**
	 * The one thing the terminal is willing to say down here.
	 *
	 * <p>A compass point towards the nearest way out, no distance, never wrong - see
	 * {@link UnrenderedBearingPolicy} for why the vagueness is resolution rather than unreliability.
	 * It goes to the mod's own notice line rather than to chat, which is where every other piece of
	 * terminal-voiced information in this mod appears.
	 */
	private static void maybeSendBearing(MinecraftServer server, ServerPlayer player) {
		Runtime runtime = RUNTIME.get(player.getUUID());
		// Nothing to steer by until there is something to run from. The layer's first minute is
		// deliberately empty in both senses: no entity, and no instrument either.
		if (runtime == null || runtime.stalker == null) return;
		int now = server.getTickCount();
		// Recomputed on a slow tick because the answer only changes when the player crosses into a
		// different wedge, but *sent* only when it actually changes - the readout on the other end is
		// permanent and redraws itself from the last value every frame.
		if (now < runtime.nextBearingTick) return;
		runtime.nextBearingTick = now + BEARING_REFRESH_TICKS;

		int[] centre = UnrenderedMazePolicy.nearestExitCentre(
				UnrenderedLayerLayout.DEFAULT_SEED, player.getBlockX(), player.getBlockZ());
		// An absolute angle, not a sector. The player's facing is what turns it into "ahead and to
		// the left", and their facing changes every frame - so the sector belongs on the client and
		// bucketing here would round twice and visibly lag the mouse.
		int bearing = UnrenderedBearingPolicy.absoluteDegrees(
				centre[0] - player.getBlockX(), centre[1] - player.getBlockZ());
		// Standing on top of it is not a bearing. Sending -1 takes the line down rather than pointing
		// at the player's own feet, which would read as the instrument having failed.
		if (bearing == runtime.lastBearing) return;
		runtime.lastBearing = bearing;
		sendPhase(player, UnrenderedPhasePayload.BEARING, bearing);
	}

	private static boolean expired(MinecraftServer server, ServerPlayer player) {
		var record = FrequencyWorldData.get(server).terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return true;
		long started = record.getLongOr(TerminalData.UNRENDERED_STARTED_TICK, 0L);
		long limit = record.getLongOr(TerminalData.UNRENDERED_DURATION_TICKS, 0L);
		return limit > 0L && player.level().getGameTime() - started >= limit;
	}

	/**
	 * Both ways out of the layer on foot, and both count as having met the anomaly.
	 *
	 * <p>The timeout is not a failure. Somebody who walked for the full six minutes without finding a
	 * hole in the floor has had the entire experience the event is offering; filing that as
	 * interrupted would leave it eligible to be drawn again immediately and would never advance the
	 * stage, so the player would be taken back down by the anomaly they just endured.
	 */
	private static void finish(ServerPlayer player, String resolution) {
		// Walking out through a false wall is the one ending that earns the sky. Timing out is the
		// layer letting go of somebody rather than somebody getting out of it, so it hands them back
		// on the ground where it took them - the difference between the two endings is the whole
		// reward for having found the way.
		if (EXIT_FOUND.equals(resolution) && skyReturnPossible(player)) {
			beginSkyReturn(player);
			return;
		}
		returnToSource(player, resolution);
		AnomalyRuntimeService.completeServerSide(player, UnrenderedAnomaly.ID,
				AnomalyCompletionStatus.COMPLETED);
	}

	/**
	 * Whether the world they came from has a sky to be dropped out of.
	 *
	 * <p>The Nether does not: two hundred blocks over a return point there is solid bedrock ceiling,
	 * and the spectacle would be a teleport into stone. A dimension with a ceiling gets the ordinary
	 * ground return, which is a quieter ending but a real one.
	 */
	private static boolean skyReturnPossible(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return false;
		var record = FrequencyWorldData.get(server).terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return false;
		ServerLevel source = sourceLevel(server,
				record.getStringOr(TerminalData.UNRENDERED_SOURCE_DIMENSION, ""));
		return !source.dimensionType().hasCeiling();
	}

	/**
	 * Raises the cover, then hands the player back in mid-air once it is up.
	 *
	 * <p>Two phases for the same reason entry is: the screen has to be black <em>before</em> the
	 * teleport. Between the two the player is still falling through the void under the layer, which
	 * is harmless - the layer floor is the bottom of that world and its void damage starts sixty-four
	 * blocks lower than eight ticks of falling can reach.
	 */
	private static void beginSkyReturn(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		sendPhase(player, UnrenderedPhasePayload.EXIT, EXIT_HOLD_TICKS);
		EXITS.put(player.getUUID(),
				new PendingExit(player.getUUID(), server.getTickCount() + EXIT_TELEPORT_TICKS));
	}

	private static void completeSkyReturns(MinecraftServer server) {
		if (EXITS.isEmpty()) return;
		int now = server.getTickCount();
		for (PendingExit exit : List.copyOf(EXITS.values())) {
			if (now < exit.dueTick()) continue;
			EXITS.remove(exit.playerId());
			ServerPlayer player = server.getPlayerList().getPlayer(exit.playerId());
			if (player == null) continue;
			if (!skyReturn(server, player)) returnToSource(player, EXIT_FOUND);
			AnomalyRuntimeService.completeServerSide(player, UnrenderedAnomaly.ID,
					AnomalyCompletionStatus.COMPLETED);
		}
	}

	/** Puts the player back above the place they were taken from, falling. */
	private static boolean skyReturn(MinecraftServer server, ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		var record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || !record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false)) return false;
		ServerLevel source = sourceLevel(server,
				record.getStringOr(TerminalData.UNRENDERED_SOURCE_DIMENSION, ""));
		if (source.dimensionType().hasCeiling()) return false;
		BlockPos entry = sourceEntry(record, source);
		int ceiling = source.getMinY() + source.getHeight() - SKY_RETURN_HEADROOM;
		int y = Math.min(ceiling, entry.getY() + SKY_RETURN_HEIGHT);
		BlockPos target = new BlockPos(entry.getX(), y, entry.getZ());
		// Forces the destination column to exist while the screen is still covered. Everything
		// between here and the ground streams in during the rest of the hold.
		source.getChunk(target);
		place(player, source, target,
				(float) record.getDoubleOr(TerminalData.UNRENDERED_SOURCE_YAW, player.getYRot()),
				(float) record.getDoubleOr(TerminalData.UNRENDERED_SOURCE_PITCH, player.getXRot()));
		DESCENDING.put(player.getUUID(), server.getTickCount());
		clearSession(data, player, EXIT_FOUND);
		return true;
	}

	/**
	 * Keeps the fall from being the thing that kills them.
	 *
	 * <p>Two hundred blocks is fatal several times over, and a player who found the way out being
	 * killed by the way out is the single worst outcome this feature has available. Resetting the
	 * distance every tick rather than cancelling the damage once means it also covers a landing in a
	 * ravine, a second bounce, and anything else the descent finds on the way down.
	 *
	 * <p>Ends on touching ground, and independently on a timeout, because a player who logs out
	 * mid-air or is caught by something else must not stay fall-immune for the rest of the session.
	 */
	private static void tickDescents(MinecraftServer server) {
		if (DESCENDING.isEmpty()) return;
		int now = server.getTickCount();
		for (var entry : Map.copyOf(DESCENDING).entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null || player.isRemoved() || !player.isAlive()
					|| now - entry.getValue() > DESCENT_TIMEOUT_TICKS) {
				DESCENDING.remove(entry.getKey());
				continue;
			}
			player.resetFallDistance();
			if (player.onGround()) DESCENDING.remove(entry.getKey());
		}
	}

	private static void completePendingEntries(MinecraftServer server) {
		if (PENDING.isEmpty()) return;
		int now = server.getTickCount();
		for (PendingEntry entry : List.copyOf(PENDING.values())) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.playerId());
			// The cover goes up while the fall is still on screen, not after it: the teleport below
			// must never be the first frame of black, or the destination is what raises it.
			if (now >= entry.coverTick() && !entry.coverSent) {
				entry.coverSent = true;
				if (player != null) sendPhase(player, UnrenderedPhasePayload.ENTER,
						ENTRY_BLACKOUT_TICKS + ENTRY_ARRIVAL_HOLD_TICKS);
			}
			if (now < entry.dueTick()) continue;
			PENDING.remove(entry.playerId());
			if (player == null) continue;
			if (!arrive(server, player, entry)) abandon(player, "entry_failed");
		}
	}

	private static void completeCaptures(MinecraftServer server) {
		if (CAPTURES.isEmpty()) return;
		int now = server.getTickCount();
		for (PendingCapture capture : List.copyOf(CAPTURES.values())) {
			if (now < capture.dueTick()) continue;
			CAPTURES.remove(capture.playerId());
			ServerPlayer player = server.getPlayerList().getPlayer(capture.playerId());
			if (player == null) continue;
			respawnAfterCapture(server, player);
		}
	}

	/**
	 * The half of the capture that looks like respawning.
	 *
	 * <p>Health and hunger are restored rather than left as they were: the player is being told they
	 * died, and arriving on half a heart contradicts that in the one way they can check.
	 */
	private static void respawnAfterCapture(MinecraftServer server, ServerPlayer player) {
		LevelData.RespawnData respawn = respawnData(server, player);
		ServerLevel level = server.getLevel(respawn.dimension());
		if (level == null) level = server.overworld();
		BlockPos safe = PursuitReturnLocator.find(level, respawn.pos());
		place(player, level, safe, respawn.yaw(), respawn.pitch());
		player.setHealth(player.getMaxHealth());
		player.getFoodData().eat(20, 1.0F);
		player.clearFire();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		if (data.terminalRecord(player.getUUID()).isPresent()) clearSession(data, player, CAPTURED);
		AnomalyRuntimeService.completeServerSide(player, UnrenderedAnomaly.ID,
				AnomalyCompletionStatus.COMPLETED);
	}

	private static LevelData.RespawnData respawnData(MinecraftServer server, ServerPlayer player) {
		ServerPlayer.RespawnConfig config = player.getRespawnConfig();
		if (config != null && config.respawnData() != null
				&& server.getLevel(config.respawnData().dimension()) != null) {
			return config.respawnData();
		}
		return server.overworld().getRespawnData();
	}

	private static boolean arrive(MinecraftServer server, ServerPlayer player, PendingEntry entry) {
		ServerLevel layer = server.getLevel(UnrenderedDimensions.UNRENDERED_LAYER);
		if (layer == null) {
			TheFourthFrequency.LOGGER.warn("The unrendered layer dimension is missing; entry cancelled");
			return false;
		}
		var record = FrequencyWorldData.get(server).terminalRecord(player.getUUID()).orElse(null);
		if (record == null
				|| !record.getStringOr(TerminalData.UNRENDERED_SESSION_ID, "").equals(entry.sessionId())) {
			return false;
		}
		BlockPos target = entry.lease().entry();
		// Forces the destination chunk to finish generating while the screen is still covered. The
		// teleport below would load it anyway, but on the client's clock rather than behind the
		// blackout.
		layer.getChunk(target);
		// Two blocks up, under the ceiling rather than on the floor. The player arrives falling, and
		// the client holds the camera in the ceiling slab for the first few frames, so the cover
		// lifts on somebody coming through the roof of the room instead of standing neatly in it.
		// The drop is a shade under three blocks, which is exactly the height vanilla does not
		// charge fall damage for - the entry costs nothing, which is the rule the whole feature has
		// kept since it was written.
		place(player, layer, target.above(UnrenderedAnchorPolicy.ENTRY_DROP_BLOCKS),
				player.getYRot(), player.getXRot());
		return true;
	}

	/**
	 * Every teleport this service performs, with the velocity reset that all of them need.
	 *
	 * <p>Falling through the floor leaves real downward velocity behind. Carried across a teleport it
	 * becomes fall damage on arrival, for a fall the player never took - and the way out of this
	 * dimension is a fall, so this is the common case rather than an edge one.
	 */
	private static void place(ServerPlayer player, ServerLevel level, BlockPos target, float yaw,
			float pitch) {
		player.setDeltaMovement(0.0D, 0.0D, 0.0D);
		player.resetFallDistance();
		player.teleportTo(level, target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D,
				Set.<Relative>of(), yaw, pitch, true);
		player.resetFallDistance();
	}

	private static void deferOnLeave(ServerPlayer player) {
		PENDING.remove(player.getUUID());
		CAPTURES.remove(player.getUUID());
		EXITS.remove(player.getUUID());
		DESCENDING.remove(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (server != null) despawnStalker(server, player.getUUID());
		RUNTIME.remove(player.getUUID());
		// Cannot teleport a player who is already leaving, and must not throw from this event -
		// Fabric fires it from the head of PlayerList#remove, so an exception here skips the rest of
		// the removal and the player is never saved. The record keeps UNRENDERED_ACTIVE set, and
		// recoverOnJoin does the return when they come back.
		UnrenderedAnchorManager.release(player.getUUID());
	}

	private static void recoverOnJoin(ServerPlayer player) {
		var record = FrequencyWorldData.get(player.level().getServer())
				.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) return;
		boolean active = record.getBooleanOr(TerminalData.UNRENDERED_ACTIVE, false);
		if (!active && !inLayer(player)) return;
		if (inLayer(player)) {
			// Standing in the layer is the whole condition, and the flag is not consulted.
			//
			// This used to call returnToSource, whose first act is to refuse any record that is not
			// marked active - and a comment here claimed the flag was re-marked first, which it never
			// was. It happened to work because the only way to be in the layer across a login is a
			// disconnect, and deferOnLeave deliberately leaves the flag set. But a record that is
			// somehow in the layer with the session already booked closed is exactly the case that
			// needs rescuing, and it was the one case that got nothing: RUNTIME was cleared by the
			// disconnect, so the tick loop skips them too, and the player is left standing in a
			// dimension where neither the exit nor the timeout is being watched for them.
			//
			// The address is read straight off the record instead, and forceReturn falls back to
			// world spawn if even that is gone. Being put somewhere is always recoverable; being left
			// here is not.
			forceReturn(player, "recovered");
			return;
		}
		abandon(player, "recovered");
	}

	/**
	 * The return that cannot be refused, for a player found in the layer with no session to close.
	 *
	 * <p>Re-marks the record active only so the ordinary path can run: it wants the resolution
	 * bookkeeping, the anchor release and the entity teardown that {@link #clearSession} does, and
	 * all of that lives behind the same guard. If the return address was cleared too,
	 * {@link #returnToSource} already falls through to the world's respawn point.
	 */
	private static void forceReturn(ServerPlayer player, String resolution) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		data.updateTerminalRecord(player.getUUID(),
				record -> record.putBoolean(TerminalData.UNRENDERED_ACTIVE, true));
		returnToSource(player, resolution);
	}

	private static void returnEveryone(MinecraftServer server) {
		for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
			if (inLayer(player)) returnToSource(player, "server_stopping");
		}
		PENDING.clear();
		CAPTURES.clear();
		EXITS.clear();
		DESCENDING.clear();
		RUNTIME.clear();
		UnrenderedAnchorManager.clear();
	}

	private static void despawnStalker(MinecraftServer server, UUID playerId) {
		Runtime runtime = RUNTIME.get(playerId);
		if (runtime == null || runtime.stalker == null) return;
		ServerLevel layer = server.getLevel(UnrenderedDimensions.UNRENDERED_LAYER);
		if (layer != null && layer.getEntity(runtime.stalker) instanceof BacteriaEntity stalker) {
			stalker.discard();
		}
		runtime.stalker = null;
	}

	/**
	 * The address the player was taken from, or the world's own respawn point if there is not one.
	 *
	 * <p>A missing key and a cleared key are the same answer and must be treated as such.
	 * {@link #clearSession} writes {@code 0L} rather than removing the field, so reading it with a
	 * default only covers the first of those - and the second is exactly the state the rescue path
	 * in {@link #forceReturn} can find. Packed zero is the world origin, which as a return address
	 * means "nobody wrote one".
	 */
	private static BlockPos sourceEntry(CompoundTag record, ServerLevel source) {
		long packed = record.getLongOr(TerminalData.UNRENDERED_SOURCE_POSITION, 0L);
		return packed == 0L ? source.getRespawnData().pos() : BlockPos.of(packed);
	}

	private static ServerLevel sourceLevel(MinecraftServer server, String dimensionId) {
		return PursuitDimensions.sourceLevel(server, dimensionId)
				.filter(level -> !UnrenderedDimensions.isUnrendered(level))
				.orElse(server.overworld());
	}

	private static void sendPhase(ServerPlayer player, String phase, int holdTicks) {
		if (player.hasDisconnected()
				|| !ServerPlayNetworking.canSend(player, UnrenderedPhasePayload.TYPE)) return;
		ServerPlayNetworking.send(player, new UnrenderedPhasePayload(phase, holdTicks));
	}

	/**
	 * Pays out the session, in the same currency a pursuit pays in.
	 *
	 * <p>Getting out is worth a heart of maximum health and not getting out costs one, on exactly
	 * {@code PursuitProgressPolicy.resolutionMaxHealthDelta} - the same rule, the same floor at six
	 * hearts below which a capture costs nothing further, and the same shared clamp. Restating it
	 * here with its own numbers would have been the quickest way to end up with two subtly different
	 * definitions of what losing costs.
	 *
	 * <p>Escaping is walking out through a false wall. <b>Timing out counts as failing</b>: six
	 * minutes without finding a way out is not getting out, whatever it was worth as an experience.
	 * That is a different judgement from the one the anomaly history makes, which files a timeout as
	 * a completion so the entry is not immediately re-drawn - the two are answering different
	 * questions and are allowed to disagree.
	 *
	 * <p>Nothing is paid for a session that never really happened: a disconnect, an operator's
	 * teleport, a server shutdown or a failed entry all close without a stake, because charging a
	 * heart for the game being restarted is exactly the kind of unrecoverable deprivation the layer
	 * is not allowed to be.
	 */
	private static void settleStake(ServerPlayer player, String resolution) {
		boolean escaped = EXIT_FOUND.equals(resolution);
		boolean failed = CAPTURED.equals(resolution) || EXPIRED.equals(resolution);
		if (!escaped && !failed) return;
		MaximumHealthAdjustment.apply(player, PursuitProgressPolicy.resolutionMaxHealthDelta(
				failed, MaximumHealthAdjustment.currentBase(player)));
	}

	private static void clearSession(FrequencyWorldData data, ServerPlayer player, String resolution) {
		settleStake(player, resolution);
		MinecraftServer server = player.level().getServer();
		if (server != null) despawnStalker(server, player.getUUID());
		RUNTIME.remove(player.getUUID());
		UnrenderedAnchorManager.release(player.getUUID());
		// A capture keeps its cover. This runs from the return teleport, which happens a second and a
		// half into a four-second hold, and clearing here dropped the black screen while the scream
		// was still playing - so the player was handed back to their own world mid-shriek, which
		// reads as a bug rather than as having been taken. The client's own hold ends it instead.
		if (!CAPTURED.equals(resolution)) sendPhase(player, UnrenderedPhasePayload.CLEAR, 0);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putBoolean(TerminalData.UNRENDERED_ACTIVE, false);
			record.putString(TerminalData.UNRENDERED_SESSION_ID, "");
			record.putString(TerminalData.UNRENDERED_SOURCE_DIMENSION, "");
			record.putLong(TerminalData.UNRENDERED_SOURCE_POSITION, 0L);
			record.putInt(TerminalData.UNRENDERED_SLOT, -1);
			record.putLong(TerminalData.UNRENDERED_STARTED_TICK, 0L);
			record.putLong(TerminalData.UNRENDERED_DURATION_TICKS, 0L);
			// Written on every close, not only the ones that paid out. An entry that failed or was
			// interrupted still means the player was very recently taken, and the gap is about how
			// often this may happen to somebody rather than about how it ended.
			record.putLong(TerminalData.UNRENDERED_NEXT_ELIGIBLE_TICK,
					player.level().getGameTime() + UnrenderedAnomaly.gapTicks(
							player.getUUID().getLeastSignificantBits() ^ player.level().getGameTime()));
		});
		TheFourthFrequency.LOGGER.debug("Unrendered layer session closed for {} ({})",
				player.getGameProfile().name(), resolution);
	}

	/**
	 * A pending entry now has two deadlines - raise the cover, then teleport - and has to remember
	 * crossing the first, so it is a small mutable holder rather than a record. Re-putting an
	 * immutable copy to flip one flag would leave the map key as the only thing keeping two versions
	 * of one entry apart.
	 */
	private static final class PendingEntry {
		private final UUID playerId;
		private final String sessionId;
		private final UnrenderedAnchorManager.Lease lease;
		private final int coverTick;
		private final int dueTick;
		private boolean coverSent;

		private PendingEntry(UUID playerId, String sessionId, UnrenderedAnchorManager.Lease lease,
				int coverTick, int dueTick) {
			this.playerId = playerId;
			this.sessionId = sessionId;
			this.lease = lease;
			this.coverTick = coverTick;
			this.dueTick = dueTick;
		}

		private UUID playerId() { return playerId; }
		private String sessionId() { return sessionId; }
		private UnrenderedAnchorManager.Lease lease() { return lease; }
		private int coverTick() { return coverTick; }
		private int dueTick() { return dueTick; }
	}

	private record PendingCapture(UUID playerId, int dueTick) {
	}

	private record PendingExit(UUID playerId, int dueTick) {
	}

	private static boolean stillPresent(MinecraftServer server, UUID stalkerId) {
		ServerLevel layer = server.getLevel(UnrenderedDimensions.UNRENDERED_LAYER);
		return layer != null && layer.getEntity(stalkerId) instanceof BacteriaEntity stalker
				&& !stalker.isRemoved();
	}

	private static final class Runtime {
		private final String sessionId;
		private UUID stalker;
		private int replaceAfterTick;
		private int nextBearingTick;
		/** Last compass index sent, so an unchanged bearing costs no packet. Starts "not yet sent". */
		private int lastBearing = Integer.MIN_VALUE;

		private Runtime(String sessionId) {
			this.sessionId = sessionId;
		}
	}
}
