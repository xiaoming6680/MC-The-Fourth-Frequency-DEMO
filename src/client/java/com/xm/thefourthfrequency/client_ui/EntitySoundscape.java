package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.*;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.entity.*;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Local sound follows the same tracked body and walk clock that the player actually sees. */
public final class EntitySoundscape {
	private static final Map<UUID, Frame> FRAMES = new HashMap<>();
	private static final Map<UUID, MouthLoop> MOUTHS = new HashMap<>();
	private static final SoundDetailBudget DETAILS = new SoundDetailBudget(6);
	private static ClientLevel world;
	private static TerminalHandheldAnimator.State terminalState = TerminalHandheldAnimator.State.IDLE;
	private static SimpleSoundInstance handling;
	private static int foleyPlays;
	private EntitySoundscape() { }

	public static void initialize() {
		ClientTickEvents.END_CLIENT_TICK.register(EntitySoundscape::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(client));
	}

	public static void reset(Minecraft client) {
		for (var loop : MOUTHS.values()) client.getSoundManager().stop(loop);
		if (handling != null) client.getSoundManager().stop(handling);
		handling = null;
		MOUTHS.clear(); FRAMES.clear(); DETAILS.clear(); world = null;
		SoundVariation.clearSession();
		terminalState = TerminalHandheldAnimator.State.IDLE;
	}

	private static float master() {
		return (float) Math.clamp(RuntimeServices.config().meta().peakVolume(), 0.0D, 1.0D);
	}

	private static void tick(Minecraft client) {
		if (world != client.level) { reset(client); world = client.level; }
		if (world == null || client.player == null) return;
		if (!client.player.isAlive()) { reset(client); return; }
		if (client.isPaused()) return;
		tickTerminal(client);
		long now = world.getGameTime();
		List<LivingEntity> bodies = world.getEntitiesOfClass(LivingEntity.class,
				client.player.getBoundingBox().inflate(128),
				e -> e instanceof ReworkEntity || e instanceof BacteriaEntity || e instanceof WorldInterfaceEntity);
		bodies.sort(Comparator.comparingDouble(client.player::distanceToSqr));
		Set<UUID> seen = new HashSet<>();
		int decorative = 0;
		for (LivingEntity body : bodies) {
			if (!body.isAlive()) continue;
			if (body instanceof WorldInterfaceEntity boss) {
				if (MOUTHS.size() < 2 || MOUTHS.containsKey(boss.getUUID())) tickMouth(client, boss, now);
				continue;
			}
			if (++decorative > 16) continue;
			seen.add(body.getUUID());
			float walk = body.walkAnimation.position();
			Frame last = FRAMES.get(body.getUUID());
			if (last != null && body.onGround() && body.walkAnimation.speed() > .035F) {
				float rate = body instanceof BacteriaEntity ? HorrorMotion.BACTERIA_STEP_RATE : HorrorMotion.REWORK_STEP_RATE;
				float spacing = body instanceof BacteriaEntity ? (float) Math.PI / 2 : (float) Math.PI;
				if (crossed(last.walk * rate, walk * rate, spacing)) {
					SoundEvent cue = body instanceof ReworkEntity rework ? step(rework.formStage()) : ModSounds.BACTERIA_SKITTER;
					// Near-field patter only; the bacteria's existing heartbeat owns distant navigation.
					if (!(body instanceof BacteriaEntity) || body.distanceToSqr(client.player) < 18 * 18)
						foley(client, body, cue, body instanceof BacteriaEntity ? .22F : .48F, now, 3);
				}
			}
			if (body instanceof ReworkEntity rework && last != null) {
				float breath = HorrorMotion.reworkBreathPhase(body.tickCount, rework.formStage());
				if (crossed(last.breath, breath, (float) Math.PI * 2)
						&& (body.tickCount / 39 + body.getId()) % 3 == 0)
					foley(client, body, breath(rework.formStage()), .23F, now, 80);
			}
			FRAMES.put(body.getUUID(), new Frame(walk, body instanceof ReworkEntity r
					? HorrorMotion.reworkBreathPhase(body.tickCount, r.formStage()) : 0));
		}
		FRAMES.keySet().retainAll(seen);
		MOUTHS.entrySet().removeIf(entry -> {
			if (!entry.getValue().isStopped()) return false;
			return true;
		});
	}

	/** No catch-up burst after a teleport, reverse clock, or first tracking packet. */
	public static boolean crossed(float previous, float current, float spacing) {
		return Float.isFinite(previous) && Float.isFinite(current) && current > previous
				&& current - previous < spacing && Math.floor(previous / spacing) != Math.floor(current / spacing);
	}

	private static SoundEvent step(int form) {
		return switch (form) { case 1 -> ModSounds.REWORK_STEP_1; case 2 -> ModSounds.REWORK_STEP_2; default -> ModSounds.REWORK_STEP_3; };
	}
	private static SoundEvent breath(int form) {
		return switch (form) { case 1 -> ModSounds.REWORK_BREATH_1; case 2 -> ModSounds.REWORK_BREATH_2; default -> ModSounds.REWORK_BREATH_3; };
	}
	private static void foley(Minecraft client, LivingEntity entity, SoundEvent cue, float gain, long now, int gap) {
		if (master() <= 0 || !DETAILS.admit(now, entity.getUUID() + ":" + cue.location(), gap)) return;
		float material = entity.getBlockStateOn().getSoundType().getPitch();
		client.level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), cue, SoundSource.HOSTILE,
				gain * master(), Math.clamp(material, .85F, 1.15F), false);
		foleyPlays++;
	}

	private static void tickTerminal(Minecraft client) {
		var current = TerminalHandheldAnimator.state();
		if (current == terminalState) return;
		if (handling != null) client.getSoundManager().stop(handling);
		handling = null;
		if (master() > 0 && (current == TerminalHandheldAnimator.State.OPENING || current == TerminalHandheldAnimator.State.CLOSING)) {
			handling = SimpleSoundInstance.forUI(current == TerminalHandheldAnimator.State.OPENING
					? ModSounds.TERMINAL_RAISE : ModSounds.TERMINAL_LOWER, 1.0F, .36F * master());
			client.getSoundManager().play(handling);
		}
		terminalState = current;
	}

	private static int laserPhase(WorldInterfaceEntity boss, long now) {
		if (!boss.isAlive() || boss.isRemoved() || boss.actionId() != WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId()) return 0;
		long elapsed = now - boss.actionStartTick();
		if (elapsed < 0 || elapsed >= boss.actionDuration()) return 0;
		return elapsed < WorldInterfaceProtocol.LASER_WARNING_TICKS ? 1
				: elapsed < WorldInterfaceProtocol.LASER_WARNING_TICKS + WorldInterfaceProtocol.LASER_SWEEP_TICKS ? 2 : 0;
	}

	private static void tickMouth(Minecraft client, WorldInterfaceEntity boss, long now) {
		int phase = laserPhase(boss, now);
		MouthLoop old = MOUTHS.get(boss.getUUID());
		if (old != null && (old.phase != phase || old.started != boss.actionStartTick())) {
			client.getSoundManager().stop(old); MOUTHS.remove(boss.getUUID());
			if (old.phase == 2 && phase == 0 && master() > 0) {
				Vec3 mouth = WorldInterfaceAnatomy.mouthOrigin(boss, 0);
				world.playLocalSound(mouth.x, mouth.y, mouth.z, ModSounds.WORLD_INTERFACE_LASER_RELEASE,
						SoundSource.HOSTILE, .52F * master() * AudioService.ENCOUNTER_MIX_TRIM, 1.0F, false);
			}
			old = null;
		}
		if (phase > 0 && master() > 0 && (old == null || old.isStopped())) {
			MouthLoop loop = new MouthLoop(boss, phase);
			MOUTHS.put(boss.getUUID(), loop); client.getSoundManager().play(loop);
		}
	}

	public static int activeMouthsForTesting() { return (int) MOUTHS.values().stream().filter(x -> !x.isStopped()).count(); }
	public static int playingMouthsForTesting(Minecraft client) { return (int) MOUTHS.values().stream().filter(client.getSoundManager()::isActive).count(); }
	public static Vec3 mouthPositionForTesting() {
		var loop = MOUTHS.values().iterator().next(); return new Vec3(loop.getX(), loop.getY(), loop.getZ());
	}
	public static int foleyPlaysForTesting() { return foleyPlays; }
	private record Frame(float walk, float breath) { }

	private static final class MouthLoop extends AbstractTickableSoundInstance {
		private final WorldInterfaceEntity boss;
		private final int phase;
		private final long started;
		private MouthLoop(WorldInterfaceEntity boss, int phase) {
			super(phase == 1 ? ModSounds.WORLD_INTERFACE_LASER : ModSounds.WORLD_INTERFACE_LASER_LOOP,
					SoundSource.HOSTILE, RandomSource.create());
			this.boss = boss; this.phase = phase; this.started = boss.actionStartTick();
			looping = true; delay = 0; relative = false; attenuation = Attenuation.LINEAR;
			tick();
		}
		@Override public boolean canStartSilent() { return true; }
		@Override public void tick() {
			Minecraft client = Minecraft.getInstance();
			if (client.level != boss.level() || client.player == null || !client.player.isAlive()
					|| boss.isRemoved() || master() <= 0 || boss.distanceToSqr(client.player) > 128 * 128
					|| boss.actionStartTick() != started || laserPhase(boss, boss.level().getGameTime()) != phase) { stop(); return; }
			Vec3 mouth = WorldInterfaceAnatomy.mouthOrigin(boss, 0);
			x = mouth.x; y = mouth.y; z = mouth.z;
			float progress = Math.clamp((boss.level().getGameTime() - started)
					/ (float) WorldInterfaceProtocol.LASER_WARNING_TICKS, 0, 1);
			volume = master() * AudioService.ENCOUNTER_MIX_TRIM * (phase == 1 ? .28F + .42F * progress : .66F);
			pitch = 1.0F;
		}
	}
}
