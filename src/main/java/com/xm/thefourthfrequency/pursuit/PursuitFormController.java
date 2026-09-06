package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.entity.ReworkEntity;
import com.xm.thefourthfrequency.networking.PursuitPresentationPayload;
import com.xm.thefourthfrequency.terminal.AnomalyIntensity;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.MaximumHealthAdjustment;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side three-form chase mechanics and authoritative resolution. */
public final class PursuitFormController {
	private static final long RESOLUTION_TICKS = 60L;
	private static final double MIN_PLAYER_MAX_HEALTH = 2.0D;
	private static final double MAX_PLAYER_MAX_HEALTH = 40.0D;
	private static final Map<UUID, Runtime> ACTIVE = new HashMap<>();
	private static final Set<DefeatKey> DEFEATED = new HashSet<>();
	private static boolean initialized;

	private PursuitFormController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(PursuitFormController::tick);
		// ReworkEntity.setPersistenceRequired() means any pursuit mob still alive when the world
		// saves gets written into the mirror dimension's region file and never removes itself on
		// its own (losing its owner only makes it stop, see ReworkEntity.customServerAiStep). Left
		// unhandled, every session still in flight at shutdown becomes a permanent ghost entity
		// that accumulates across restarts. SERVER_STOPPING fires before the world save that
		// happens during shutdown, so discarding here actually keeps them out of the save file.
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Runtime runtime : ACTIVE.values()) discardMirrorEntity(server, runtime);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			ACTIVE.clear();
			DEFEATED.clear();
		});
	}

	/**
	 * Best-effort removal of a session's mirror-dimension mob; the lease may already be gone.
	 *
	 * @return whether the mirror level was resolved at all, so a caller holding another level can
	 *         fall back to it rather than assume the mob is handled
	 */
	private static boolean discardMirrorEntity(MinecraftServer server, Runtime runtime) {
		ServerLevel level = PursuitSlotManager.lease(runtime.playerId)
				.map(lease -> server.getLevel(lease.dimension()))
				.orElse(null);
		if (level == null) return false;
		Entity entity = level.getEntity(runtime.entityId);
		if (entity != null) entity.discard();
		return true;
	}

	public static boolean begin(ServerPlayer player, String sessionId, int form, boolean debugSession) {
		if (!(player.level() instanceof ServerLevel level) || !PursuitDimensions.isMirror(level)
				|| ACTIVE.containsKey(player.getUUID())) return false;
		ReworkEntity entity = spawn(level, player, sessionId, form);
		if (entity == null) return false;
		long now = level.getGameTime();
		int normalizedForm = Math.clamp(form, 1, PursuitProgressPolicy.FORM_COUNT);
		ACTIVE.put(player.getUUID(), new Runtime(player.getUUID(), sessionId, Math.clamp(form, 1, PursuitProgressPolicy.FORM_COUNT),
				entity.getUUID(), now + PursuitFormPolicy.forForm(normalizedForm).durationTicks(),
				player.position(), debugSession));
		PursuitVisibilityService.isolate(player);
		PursuitVisionService.apply(player);
		return true;
	}

	public static void interrupt(ServerPlayer player) {
		Runtime runtime = ACTIVE.remove(player.getUUID());
		if (runtime == null) return;
		PursuitVisionService.clear(player);
		DEFEATED.remove(new DefeatKey(runtime.playerId, runtime.sessionId));
		// The mob lives in the mirror, which is not necessarily where the player is standing by the
		// time this runs: a mirror death that outran the capture threshold respawns them in the
		// source world first, and an operator teleport lands them anywhere. Resolving the level
		// through the slot lease finds the mob in either case, where player.level() only ever found
		// it while the player was still inside the mirror and otherwise left a
		// setPersistenceRequired() mob behind to be written into the mirror's region file. The lease
		// outlives this call on every path (release() runs after it), so the fallback below is only
		// for a caller that released first.
		MinecraftServer server = player.level().getServer();
		if (server != null && discardMirrorEntity(server, runtime)) return;
		if (player.level() instanceof ServerLevel level) {
			Entity entity = level.getEntity(runtime.entityId);
			if (entity != null) entity.discard();
		}
	}

	public static void recordPursuitDefeat(ReworkEntity entity, DamageSource source) {
		if (!entity.pursuitMode() || entity.pursuitOwner() == null
				|| !(source.getEntity() instanceof ServerPlayer player)
				|| !player.getUUID().equals(entity.pursuitOwner())) return;
		DEFEATED.add(new DefeatKey(entity.pursuitOwner(), entity.pursuitSessionId()));
	}

	private static void tick(MinecraftServer server) {
		for (Runtime runtime : Map.copyOf(ACTIVE).values()) {
			ServerPlayer player = server.getPlayerList().getPlayer(runtime.playerId);
			if (player == null) {
				// A normal disconnect is already handled by ServerPlayerEvents.LEAVE
				// (PursuitSessionService.deferDisconnectedSession), which runs while the
				// ServerPlayer is still reachable and calls interrupt()/cancel()/release() itself.
				// This branch is the defensive fallback for a player vanishing from the player
				// list some other way (e.g. a forced removal that skips LEAVE): it used to only
				// drop the ACTIVE entry, leaving the mirror mob to wander (and get saved to disk),
				// the streaming snapshot session running, and - worse - the slot lease held
				// forever, permanently starving one of only MAX_ACTIVE_PURSUITS=2 server-wide
				// slots. interrupt(player) can't be reused here: it needs a live ServerPlayer to
				// read player.level() from. All three calls below are idempotent, so this is safe
				// to run even when deferDisconnectedSession already did the same cleanup.
				ACTIVE.remove(runtime.playerId);
				DEFEATED.remove(new DefeatKey(runtime.playerId, runtime.sessionId));
				discardMirrorEntity(server, runtime);
				PursuitSnapshotBuilder.cancel(runtime.playerId);
				PursuitSlotManager.release(runtime.playerId);
				continue;
			}
			if (!(player.level() instanceof ServerLevel level) || !PursuitDimensions.isMirror(level)) {
				// The player left the mirror without going through returnToSource - a death in the
				// mirror that outran the capture threshold below and respawned them in the source
				// world, or an operator teleport. They are already where they belong, so the session
				// is booked closed where they stand instead of being returned; abandonSession is the
				// no-teleport close for exactly this. Dropping the runtime is not enough on its own:
				// the slot lease is one of only MAX_ACTIVE_PURSUITS=2 server-wide and would be held
				// until they next disconnect, and a record left with PURSUIT_ACTIVE set makes
				// PursuitDirector.update skip that player for good, so they would never get another
				// chase either.
				PursuitSessionService.abandonSession(player, "mirror_exit");
				continue;
			}
			// Tops up ahead of the resolution branch as well: the capture freeze holds the client on
			// its last live frame, and that frame should not be the one where the lights went out.
			PursuitVisionService.maintain(player);
			if (runtime.resolution != Resolution.NONE) {
				tickResolution(player, runtime, level.getGameTime());
				continue;
			}
			if (!PursuitSnapshotBuilder.active(player.getUUID())) {
				capture(player, runtime, "snapshot_stream_lost");
				continue;
			}
			if (DEFEATED.remove(new DefeatKey(runtime.playerId, runtime.sessionId))) {
				succeed(player, runtime, SuccessKind.COUNTERATTACK);
				continue;
			}
			Entity raw = level.getEntity(runtime.entityId);
			ReworkEntity rework = raw instanceof ReworkEntity body && body.isAlive() ? body : null;
			if (rework == null) {
				rework = spawn(level, player, runtime.sessionId, runtime.form);
				if (rework == null) {
					capture(player, runtime, "entity_lost");
					continue;
				}
				runtime.entityId = rework.getUUID();
				runtime.captureGrace = 30;
			}
			if (runtime.captureGrace > 0) runtime.captureGrace--;
			if (!maintainStreamingWindow(level, player, rework, runtime)) {
				if (server.getTickCount() % 40 == 0) removeAmbientHostiles(level, player, rework);
				continue;
			}
			tickForm(level, player, rework, runtime);
			if (server.getTickCount() % 40 == 0) removeAmbientHostiles(level, player, rework);
			runtime.escapeCounters = PursuitEscapePolicy.advance(runtime.escapeCounters,
					Math.sqrt(rework.distanceToSqr(player)), rework.hasLineOfSight(player));
			if (PursuitEscapePolicy.escaped(runtime.escapeCounters)) {
				succeed(player, runtime, SuccessKind.ESCAPE);
				continue;
			}
			if (player.getHealth() <= 2.0F
					|| runtime.captureGrace <= 0 && rework.distanceToSqr(player) <= 2.25D) {
				capture(player, runtime, "caught");
				continue;
			}
			long now = level.getGameTime();
			if (now >= runtime.deadline) {
				succeed(player, runtime, SuccessKind.SURVIVED);
			}
		}
	}

	private static boolean maintainStreamingWindow(ServerLevel level, ServerPlayer player, ReworkEntity rework,
			Runtime runtime) {
		PursuitSnapshotBuilder.requestWindow(player.getUUID(), player.blockPosition());
		if (PursuitSnapshotBuilder.isChunkReady(player.getUUID(), player.blockPosition())) {
			runtime.lastSafePosition = player.position();
			return true;
		}
		Vec3 safe = runtime.lastSafePosition;
		level.sendParticles(ParticleTypes.CRIMSON_SPORE, player.getX(), player.getY() + 1.0D, player.getZ(),
				32, 1.0D, 1.0D, 1.0D, 0.02D);
		player.teleportTo(level, safe.x, safe.y, safe.z, java.util.Set.of(),
				player.getYRot(), player.getXRot(), true);
		rework.setPursuitTracking(false);
		runtime.deadline++;
		runtime.captureGrace = Math.max(runtime.captureGrace, 10);
		runtime.lastPlayerPosition = player.position();
		return false;
	}

	private static void tickForm(ServerLevel level, ServerPlayer player, ReworkEntity rework, Runtime runtime) {
		Vec3 movement = player.position().subtract(runtime.lastPlayerPosition);
		runtime.lastPlayerPosition = player.position();
		switch (runtime.form) {
			case 1 -> {
				boolean noisy = !player.isCrouching() && movement.horizontalDistanceSqr() > 0.0025D;
				rework.setPursuitTracking(noisy || rework.distanceToSqr(player) < 25.0D);
			}
			case 2 -> {
				rework.setPursuitTracking(true);
				if (level.getGameTime() % 100L == 0L && movement.horizontalDistanceSqr() > 0.01D) {
					Vec3 direction = movement.multiply(1.0D, 0.0D, 1.0D).normalize();
					reposition(level, rework, player.position().add(direction.scale(10.0D)), runtime);
				}
			}
			case 3 -> {
				rework.setPursuitTracking(true);
			}
			default -> rework.setPursuitTracking(true);
		}
	}

	private static void reposition(ServerLevel level, ReworkEntity entity, Vec3 desired, Runtime runtime) {
		BlockPos origin = BlockPos.containing(desired);
		for (int dy = 3; dy >= -3; dy--) {
			BlockPos candidate = origin.offset(0, dy, 0);
			if (level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
					&& level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
					&& !level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty()) {
				entity.teleportTo(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
				runtime.captureGrace = 20;
				return;
			}
		}
	}

	private static ReworkEntity spawn(ServerLevel level, ServerPlayer player, String sessionId, int form) {
		BlockPos spawn = findSpawn(level, player);
		if (spawn == null) return null;
		ReworkEntity body = ModEntities.REWORK_BODY.create(level, EntitySpawnReason.EVENT);
		if (body == null) return null;
		body.configurePursuit(player.getUUID(), sessionId, form);
		body.snapTo(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
				player.getYRot(), 0.0F);
		body.setPersistenceRequired();
		body.addTag("thefourthfrequency:pursuit");
		return level.addFreshEntity(body) ? body : null;
	}

	/**
	 * How far above and below the player a probe column is searched for a floor.
	 *
	 * <p>The old five was only ever enough on flat ground. A player on a hillside, a shoreline or
	 * anywhere underground has terrain behind them that sits well outside a ten-block band, so
	 * every probe missed and the chase aborted before it started. The mirror copies 48 blocks of
	 * vertical either side of the arrival point, so this stays inside terrain that actually got
	 * streamed.</p>
	 */
	private static final int SPAWN_VERTICAL_REACH = 32;

	private static BlockPos findSpawn(ServerLevel level, ServerPlayer player) {
		BlockPos target = player.blockPosition();
		// Where the player is looking no longer selects anything: the ring is drawn around them, not
		// behind them, so a corrector may well arrive in plain sight.
		for (PursuitSpawnPolicy.Offset offset : PursuitSpawnPolicy.spawnOffsets(
				player.getRandom().nextLong())) {
			BlockPos origin = target.offset(offset.x(), 0, offset.z());
			// Walked outward from the player's own elevation, below before above, so the corrector
			// arrives on the floor nearest their level rather than on whatever the scan hit first.
			for (int distance = 0; distance <= SPAWN_VERTICAL_REACH; distance++) {
				BlockPos below = origin.below(distance);
				if (standable(level, below)) return below;
				if (distance == 0) continue;
				BlockPos above = origin.above(distance);
				if (standable(level, above)) return above;
			}
		}
		return null;
	}

	private static boolean standable(ServerLevel level, BlockPos candidate) {
		if (level.isOutsideBuildHeight(candidate) || level.isOutsideBuildHeight(candidate.above())) return false;
		return level.getBlockState(candidate).getCollisionShape(level, candidate).isEmpty()
				&& level.getBlockState(candidate.above()).getCollisionShape(level, candidate.above()).isEmpty()
				&& !level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty();
	}

	private static void removeAmbientHostiles(ServerLevel level, ServerPlayer player, ReworkEntity rework) {
		for (Monster monster : level.getEntitiesOfClass(Monster.class,
				player.getBoundingBox().inflate(64.0D), value -> value != rework)) {
			monster.discard();
		}
	}

	private static void capture(ServerPlayer player, Runtime runtime, String reason) {
		if (!"caught".equals(reason)) {
			PursuitSessionService.returnToSource(player, reason);
			return;
		}
		DEFEATED.remove(new DefeatKey(runtime.playerId, runtime.sessionId));
		if (player.level() instanceof ServerLevel level) {
			Entity entity = level.getEntity(runtime.entityId);
			if (entity != null) entity.discard();
			runtime.resolutionEndsAt = level.getGameTime() + RESOLUTION_TICKS;
		}
		if (player.getHealth() < 6.0F) player.setHealth(6.0F);
		runtime.resolution = Resolution.CAPTURED;
		runtime.resolutionReason = reason;
		PursuitSessionService.presentResolution(player, PursuitPresentationPayload.CAPTURE_FREEZE);
	}

	private static void succeed(ServerPlayer player, Runtime runtime, SuccessKind kind) {
		DEFEATED.remove(new DefeatKey(runtime.playerId, runtime.sessionId));
		if (player.level() instanceof ServerLevel level) {
			Entity entity = level.getEntity(runtime.entityId);
			if (entity != null) entity.discard();
			runtime.resolutionEndsAt = level.getGameTime() + RESOLUTION_TICKS;
		}
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		long now = player.level().getGameTime();
		long cooldown = PursuitProgressPolicy.MIN_CHASE_GAP_TICKS
				+ Math.floorMod(player.getRandom().nextLong(),
						PursuitProgressPolicy.MAX_CHASE_GAP_TICKS - PursuitProgressPolicy.MIN_CHASE_GAP_TICKS + 1);
		if (!runtime.debugSession) {
			data.updateTerminalRecord(player.getUUID(), record -> {
				int resolved = PursuitProgressPolicy.resolvedAfterSuccess(
						record.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, 0));
				record.putInt(TerminalData.PURSUIT_RESOLVED_CHASES, resolved);
				record.putBoolean(TerminalData.PURSUIT_PENDING,
						PursuitProgressPolicy.pendingAfterSuccess(resolved,
								record.getIntOr(TerminalData.PURSUIT_ALLOWED_FORM, 0)));
				record.putLong(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK, now + cooldown);
				record.putInt(TerminalData.PURSUIT_TUTORIAL_ARCHIVE_MASK, PursuitTutorialPolicy.mark(
						record.getIntOr(TerminalData.PURSUIT_TUTORIAL_ARCHIVE_MASK, 0), runtime.form));
				record.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK,
						now + AnomalyIntensity.DIMENSION_GRACE_TICKS + 5L * 60L * 20L);
			});
		}
		runtime.resolution = Resolution.ESCAPED;
		runtime.resolutionReason = runtime.debugSession ? "debug_complete" : "success";
		PursuitSessionService.presentResolution(player, PursuitPresentationPayload.ESCAPE_RESOLUTION);
	}

	private static void tickResolution(ServerPlayer player, Runtime runtime, long now) {
		if (now < runtime.resolutionEndsAt) return;
		double delta = PursuitProgressPolicy.resolutionMaxHealthDelta(
				runtime.resolution == Resolution.CAPTURED,
				MaximumHealthAdjustment.currentBase(player));
		MaximumHealthAdjustment.apply(player, delta);
		// Both CAPTURED and ESCAPED land here, and only they do: a technically interrupted chase
		// returns straight from capture() without ever setting a resolution. That makes this the
		// one point that means "the player actually went through a chase and it concluded", which
		// is exactly what the final Eye checks. Debug sessions stay excluded, matching succeed().
		if (!runtime.debugSession) {
			FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
			if (data.terminalRecord(player.getUUID()).isPresent()) {
				data.updateTerminalRecord(player.getUUID(), record ->
						record.putInt(TerminalData.PURSUIT_ENCOUNTERED_CHASES,
								PursuitProgressPolicy.encounteredAfterResolution(
										record.getIntOr(TerminalData.PURSUIT_ENCOUNTERED_CHASES, 0))));
			}
		}
		String reason = runtime.resolutionReason;
		PursuitSessionService.returnToSource(player, reason);
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	private static final class Runtime {
		private final UUID playerId;
		private final String sessionId;
		private final int form;
		private UUID entityId;
		private long deadline;
		private Vec3 lastPlayerPosition;
		private Vec3 lastSafePosition;
		private PursuitEscapePolicy.Counters escapeCounters = PursuitEscapePolicy.Counters.empty();
		private int captureGrace = 40;
		private final boolean debugSession;
		private Resolution resolution = Resolution.NONE;
		private long resolutionEndsAt;
		private String resolutionReason = "";

		private Runtime(UUID playerId, String sessionId, int form, UUID entityId,
				long deadline, Vec3 lastPlayerPosition, boolean debugSession) {
			this.playerId = playerId;
			this.sessionId = sessionId;
			this.form = form;
			this.entityId = entityId;
			this.deadline = deadline;
			this.lastPlayerPosition = lastPlayerPosition;
			this.lastSafePosition = lastPlayerPosition;
			this.debugSession = debugSession;
		}
	}

	private record DefeatKey(UUID playerId, String sessionId) {
	}

	private enum SuccessKind {
		SURVIVED,
		ESCAPE,
		COUNTERATTACK
	}

	private enum Resolution {
		NONE,
		CAPTURED,
		ESCAPED
	}
}
