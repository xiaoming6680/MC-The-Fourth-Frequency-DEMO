package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.networking.AnomalyCompleteC2S;
import com.xm.thefourthfrequency.networking.AnomalyPhaseS2C;
import com.xm.thefourthfrequency.networking.AnomalyStartS2C;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owns the one-active-anomaly-per-player invariant and all completion validation. */
public final class AnomalyRuntimeService {
	private static final int TIMEOUT_GRACE_TICKS = 200;
	private static final Map<ServerPlayer, RuntimeEntry> ACTIVE = new HashMap<>();
	private static boolean initialized;

	private AnomalyRuntimeService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(AnomalyRuntimeService::tick);
		ServerPlayerEvents.LEAVE.register(player -> interrupt(player, false));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> interrupt(oldPlayer, false));
		ServerPlayerEvents.JOIN.register(AnomalyRuntimeService::clearStaleProjection);
		ServerLifecycleEvents.SERVER_STOPPING.register(AnomalyRuntimeService::interruptAll);
	}

	public static boolean start(ServerPlayer player, AnomalyDefinition definition, int variant, long seed,
			int durationTicks, Anchor anchor, Runnable cleanup) {
		if (ACTIVE.containsKey(player) || durationTicks < 1) return false;
		long now = player.level().getGameTime();
		ActiveAnomaly anomaly = new ActiveAnomaly(UUID.randomUUID(), player.getUUID(), definition.id(),
				definition.tier(), variant, seed, now, durationTicks, TIMEOUT_GRACE_TICKS);
		ACTIVE.put(player, new RuntimeEntry(anomaly, cleanup == null ? () -> { } : cleanup));
		FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.ACTIVE_ANOMALY_ID, definition.id());
			tag.putLong(TerminalData.ACTIVE_ANOMALY_UNTIL, now + durationTicks);
		});
		ServerPlayNetworking.send(player, new AnomalyStartS2C(anomaly.instanceId(), anomaly.anomalyId(),
				anomaly.tier(), anomaly.variant(), anomaly.seed(), now, durationTicks, anchor != null,
				anchor == null ? "" : anchor.dimension(), anchor == null ? 0L : anchor.position().asLong()));
		anomaly.markRunning();
		recordSkyShift(player, definition);
		return true;
	}

	/**
	 * Leaves a RECORDS line when the sky turns.
	 *
	 * <p>Ordinary anomalies deliberately never reach that page - {@code TerminalSignalService.record}
	 * refuses any type that is an anomaly id, which is what keeps the page a log of the story rather
	 * than a list of everything that has glitched. The red horizon is the exception worth making: it
	 * is the one anomaly the terminal has an instrument for, so a player who reads the weather tool
	 * and then finds nothing in RECORDS has been shown a reading the device claims not to have taken.
	 *
	 * <p>Filed under its own type rather than the anomaly id, so the guard above stays intact and no
	 * other anomaly can slip through with it.
	 */
	private static void recordSkyShift(ServerPlayer player, AnomalyDefinition definition) {
		if (!definition.id().equals("red_horizon")) return;
		TerminalSignalService.record(player, SignalBand.WEATHER, "sky_red_shift", 0, 2, true);
	}

	public static boolean phase(ServerPlayer player, String phase, boolean blackout, int remainingTicks, Anchor anchor) {
		RuntimeEntry entry = ACTIVE.get(player);
		if (entry == null || entry.anomaly.stage() == ActiveAnomaly.Stage.COMPLETED) return false;
		ServerPlayNetworking.send(player, new AnomalyPhaseS2C(entry.anomaly.instanceId(),
				entry.anomaly.nextPhaseSequence(), phase, blackout, Math.max(0, remainingTicks), anchor != null,
				anchor == null ? "" : anchor.dimension(), anchor == null ? 0L : anchor.position().asLong()));
		return true;
	}

	public static boolean complete(ServerPlayer player, AnomalyCompleteC2S payload) {
		RuntimeEntry entry = ACTIVE.get(player);
		if (entry == null) return false;
		if (!entry.anomaly.targetPlayerId().equals(player.getUUID())
				|| !entry.anomaly.instanceId().equals(payload.instanceId())) return false;
		long now = player.level().getGameTime();
		if (now < entry.anomaly.earliestCompletionTick()) return false;
		cleanup(entry);
		if (!entry.anomaly.acceptCompletion(player.getUUID(), payload.instanceId(), now, payload.status())) return false;
		finalizeEntry(player, entry);
		return true;
	}

	/**
	 * Ends {@code dark_watcher} the moment its figure is found.
	 *
	 * <p>Every other anomaly is completed by the client, because every other anomaly's end condition
	 * is something the client can see: a timer running out, a screen being dismissed. This one ends
	 * when the server decides the player has looked at the entity long enough, and the client is
	 * never told - so left to the ordinary path the instance sat there until it timed out, counted
	 * as interrupted, with nothing in the world to show for it.
	 *
	 * <p>Silently does nothing unless the running anomaly is actually this one, so a watcher that
	 * belongs to the ambient sighting rather than to an anomaly cannot end an unrelated instance.
	 */
	public static void completeWatcherSighting(ServerPlayer player) {
		RuntimeEntry entry = ACTIVE.get(player);
		if (entry == null || !entry.anomaly.anomalyId().equals("dark_watcher")) return;
		long now = player.level().getGameTime();
		cleanup(entry);
		if (!entry.anomaly.acceptCompletion(player.getUUID(), entry.anomaly.instanceId(), now,
				AnomalyCompletionStatus.COMPLETED)) return;
		finalizeEntry(player, entry);
	}

	/**
	 * Ends the named anomaly because the server decided it is over, and records it as a success.
	 *
	 * <p>The ordinary path has the client report completion when its own copy of the clock runs out,
	 * which works for everything whose end condition is visible on screen. It does not work for an
	 * anomaly that ends when the player reaches somewhere: the client cannot be trusted to say so and
	 * is not told, so left alone the instance would sit until it timed out and be filed as
	 * interrupted - which means the seen mask never learns the player met it and the stage never
	 * advances.
	 *
	 * <p>Silently does nothing unless the running anomaly is the one named, so a caller cannot end
	 * somebody else's unrelated instance.
	 */
	public static boolean completeServerSide(ServerPlayer player, String anomalyId,
			AnomalyCompletionStatus status) {
		RuntimeEntry entry = ACTIVE.get(player);
		if (entry == null || !entry.anomaly.anomalyId().equals(anomalyId)) return false;
		cleanup(entry);
		if (!entry.anomaly.acceptServerCompletion(player.getUUID(), status)) return false;
		finalizeEntry(player, entry);
		return true;
	}

	/**
	 * Phase name that tells the client to tear its presentation down now.
	 *
	 * <p>The client otherwise only ends an anomaly when its own copy of the clock runs out, and
	 * {@link #phase} takes the <em>larger</em> of the two remaining counts, so a server-side
	 * interruption could not shorten it. Everything the server does here - suspending anomalies for
	 * the finale, a respawn, a leave - therefore left the client running the presentation to full
	 * length against an instance that no longer existed.
	 *
	 * <p><b>This is what silenced the boss fight.</b> {@code silent_world} mutes MUSIC, AMBIENT and
	 * HOSTILE for as long as the client believes it is running, and it is a sustained anomaly - up to
	 * five minutes. Starting the finale interrupts it server-side, the client never hears about it,
	 * and the encounter plays out with no attack cues and no score, the ending track included.
	 */
	public static final String INTERRUPTED_PHASE = "interrupted";

	public static void interrupt(ServerPlayer player, boolean clearSuspension) {
		RuntimeEntry entry = ACTIVE.remove(player);
		if (entry != null) {
			cleanup(entry);
			entry.anomaly.interrupt();
			notifyInterrupted(player, entry);
		}
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.ACTIVE_ANOMALY_ID, "none");
			tag.putLong(TerminalData.ACTIVE_ANOMALY_UNTIL, 0L);
			if (clearSuspension) tag.putBoolean(TerminalData.ANOMALIES_SUSPENDED, true);
		});
	}

	public static void interruptAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) interrupt(player, false);
		ACTIVE.clear();
	}

	public static ActiveAnomaly active(ServerPlayer player) {
		RuntimeEntry entry = ACTIVE.get(player);
		return entry == null ? null : entry.anomaly;
	}

	private static void tick(MinecraftServer server) {
		// Not just "stop scheduling": a sustained anomaly runs for minutes, so the one that matters is
		// always the one that was already running when the summon began. silent_world is two to three
		// minutes of muted MUSIC/AMBIENT/HOSTILE, which is most of a fight.
		// Asked per player: the reason a fight silences anomalies is that they would be noise across
		// the one unambiguous stretch of the run, and that is an argument about the people in the
		// fight. Somebody who never joined it keeps their own.
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (var mapEntry : Map.copyOf(ACTIVE).entrySet()) {
			ServerPlayer player = mapEntry.getKey();
			RuntimeEntry entry = mapEntry.getValue();
			if (!player.isRemoved() && !FinaleRuntimePolicy.ambientPressureAllowed(data, player)) {
				interrupt(player, false);
				continue;
			}
			if (player.isRemoved()) {
				cleanup(entry);
				entry.anomaly.interrupt();
				ACTIVE.remove(player);
				continue;
			}
			long now = player.level().getGameTime();
			if (now >= entry.anomaly.earliestCompletionTick()) cleanup(entry);
			if (now >= entry.anomaly.timeoutTick()) {
				entry.anomaly.interrupt();
				ACTIVE.remove(player);
				// The timeout is reached precisely when the client did not report a completion, so it
				// is also the case where the client may still be running the presentation.
				notifyInterrupted(player, entry);
				clearProjection(player);
			}
		}
	}

	/**
	 * Sends the teardown, if there is still anyone to send it to.
	 *
	 * <p>The disconnect check is not just defensive. {@code interrupt} is called from the leave event,
	 * where the client is already going away and has nothing left to tear down - and writing to a
	 * closing connection there is exactly what the client GameTest network synchronizer refuses.
	 */
	private static void notifyInterrupted(ServerPlayer player, RuntimeEntry entry) {
		if (player.hasDisconnected() || !ServerPlayNetworking.canSend(player, AnomalyPhaseS2C.TYPE)) return;
		ServerPlayNetworking.send(player, new AnomalyPhaseS2C(entry.anomaly.instanceId(),
				entry.anomaly.nextPhaseSequence(), INTERRUPTED_PHASE, false, 0, false, "", 0L));
	}

	private static void cleanup(RuntimeEntry entry) {
		if (!entry.anomaly.beginCleanup()) return;
		entry.cleanup.run();
	}

	private static void finalizeEntry(ServerPlayer player, RuntimeEntry entry) {
		ACTIVE.remove(player);
		clearProjection(player);
		if (entry.anomaly.markTerminalRecorded()) {
			FrequencyWorldData.get(player.level().getServer()).updateTerminalRecord(player.getUUID(),
					tag -> AnomalyHistory.recordSuccess(tag, entry.anomaly.anomalyId()));
			TerminalAnomalyLogService.recordCompleted(player, entry.anomaly);
		}
	}

	/**
	 * Blanks the record's copy of "an anomaly is running", for players who have a record.
	 *
	 * <p>Not everyone online does. {@code ZeroStationService.issueTerminalIfNeeded} deliberately
	 * declines to write a grant ledger entry once the finale has concluded, so somebody joining a
	 * finished server is a player this mod holds no state for at all - and {@link
	 * FrequencyWorldData#updateTerminalRecord} throws rather than creating one, which is the right
	 * behaviour for a writer that has no business inventing a record.
	 *
	 * <p>Both callers reach this from a lifecycle event, which is what makes the throw expensive
	 * rather than merely wrong. Fabric fires {@code ServerPlayerEvents.LEAVE} from the <em>head</em>
	 * of {@code PlayerList#remove}: an exception there skips the rest of the removal, so the player
	 * is never saved and never leaves the online list. The join side is milder - the event fires at
	 * the return of {@code placeNewPlayer} - but still ends as a disconnect with an internal error.
	 *
	 * <p>Every other per-player service already asks this question before writing; see {@code
	 * AmbientAnomalyService}'s join hook and {@code EmptySegmentService.recoverOnJoin}.
	 */
	private static void clearProjection(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.ACTIVE_ANOMALY_ID, "none");
			tag.putLong(TerminalData.ACTIVE_ANOMALY_UNTIL, 0L);
		});
	}

	private static void clearStaleProjection(ServerPlayer player) {
		if (!ACTIVE.containsKey(player)) clearProjection(player);
	}

	public record Anchor(String dimension, BlockPos position) {
		public Anchor {
			if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("anchor dimension");
			if (position == null) throw new IllegalArgumentException("anchor position");
		}
	}

	private static final class RuntimeEntry {
		private final ActiveAnomaly anomaly;
		private final Runnable cleanup;
		private RuntimeEntry(ActiveAnomaly anomaly, Runnable cleanup) {
			this.anomaly = anomaly;
			this.cleanup = cleanup;
		}
	}
}
