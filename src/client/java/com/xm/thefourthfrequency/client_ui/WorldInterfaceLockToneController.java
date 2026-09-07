package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import net.minecraft.util.Mth;

import java.util.UUID;

/**
 * The lock tone: a search cadence that closes into a solid tone at the moment the lock commits.
 *
 * <p>Being targeted used to be silent. The reticle closed and the bar drained, both of which are on
 * screen and therefore only work if the player happens to be looking at the middle of it - which,
 * during a fight fought by moving, is exactly when they are not. A player reading the arena had no
 * way to learn they had been singled out except by being hit.</p>
 *
 * <p>So it is borrowed from the one place this problem has already been solved for real: a missile
 * lock warning. Slow, spaced tones while the lock is still searching, closing up as the window runs
 * down, and then a continuous tone once the lock is committed. The value of that shape is that its
 * <em>rate</em> carries the time remaining, so it needs no attention at all until it changes - and
 * the change to solid is unmistakable without being loud.</p>
 *
 * <p>Deliberately a UI sound rather than a positioned one. The lock is not an event happening at a
 * place in the world, it is a fact about this player, and it should not attenuate with distance or
 * pan with where they happen to be facing.</p>
 *
 * <p>Purely client-side presentation, driven off the same {@link
 * WorldInterfaceProtocol#lockWarningTicks} clock the reticle and the bar are drawn from, so the
 * three cannot drift. It affects nothing authoritative and is safe to lose entirely.</p>
 */
public final class WorldInterfaceLockToneController {
	/** Gap between tones at the start of a window, and just before the lock commits. */
	private static final int SEARCH_INTERVAL_TICKS = 11;
	private static final int TRACK_INTERVAL_TICKS = 3;
	/** Short enough that successive tones fuse into one continuous note rather than a fast beep. */
	private static final int COMMIT_INTERVAL_TICKS = 2;
	private static final float SEARCH_PITCH = 1.32F;
	private static final float TRACK_PITCH = 1.74F;
	private static final float COMMIT_PITCH = 2.0F;
	private static final float SEARCH_VOLUME = 0.26F;
	private static final float TRACK_VOLUME = 0.42F;
	private static final float COMMIT_VOLUME = 0.52F;
	/** Even and unhurried on purpose: nothing about a confiscation gets more urgent as it lands. */
	private static final int DISPOSSESSION_INTERVAL_TICKS = 9;
	private static final float DISPOSSESSION_START_PITCH = 0.95F;
	private static final float DISPOSSESSION_END_PITCH = 0.55F;
	private static final float DISPOSSESSION_VOLUME = 0.40F;

	private static UUID trackedEncounterId;
	private static long trackedStartTick = Long.MIN_VALUE;
	private static int trackedActionId = -1;
	private static long lastToneTick = Long.MIN_VALUE;

	private WorldInterfaceLockToneController() {
	}

	public static void tick(Minecraft client, WorldInterfaceClientState.Projection projection) {
		if (client.level == null || client.player == null) {
			reset();
			return;
		}
		long now = client.level.getGameTime();
		BossActionS2C action = projection.action();
		if (action == null || !projection.actionActive(now)
				|| !projection.actionTargets(client.player.getUUID())) {
			reset();
			return;
		}
		int warning = WorldInterfaceProtocol.lockWarningTicks(action.action());
		long elapsed = now - action.startTick();
		// Actions that do not lock get no tone, and neither does the stretch after the window has
		// run out - by then the thing has either landed or been dodged, and either way it is over.
		if (warning <= 0 || elapsed < 0L || elapsed >= warning) {
			reset();
			return;
		}
		if (!action.encounterId().equals(trackedEncounterId) || action.startTick() != trackedStartTick
				|| action.action().wireId() != trackedActionId) {
			trackedEncounterId = action.encounterId();
			trackedStartTick = action.startTick();
			trackedActionId = action.action().wireId();
			lastToneTick = Long.MIN_VALUE;
		}
		float progress = Math.clamp(elapsed / (float) warning, 0.0F, 1.0F);
		if (!WorldInterfaceProtocol.isTargetingLock(action.action())) {
			playDispossessionCue(client, now, progress);
			return;
		}
		boolean committed = progress >= WorldInterfaceProtocol.LOCK_COMMIT_FRACTION;
		int interval = committed ? COMMIT_INTERVAL_TICKS : Math.round(Mth.lerp(
				progress / WorldInterfaceProtocol.LOCK_COMMIT_FRACTION,
				SEARCH_INTERVAL_TICKS, TRACK_INTERVAL_TICKS));
		if (lastToneTick != Long.MIN_VALUE && now - lastToneTick < interval) return;
		lastToneTick = now;
		float pitch = committed ? COMMIT_PITCH : Mth.lerp(progress, SEARCH_PITCH, TRACK_PITCH);
		float volume = committed ? COMMIT_VOLUME : Mth.lerp(progress, SEARCH_VOLUME, TRACK_VOLUME);
		// NOTE_BLOCK_BIT is a square wave, which is the closest thing vanilla ships to the flat
		// electronic tone this is imitating. Unwrapped from its holder because only the plain
		// SoundEvent overload of forUI takes a volume, and the volume ramp is half the cue.
		client.getSoundManager().play(SimpleSoundInstance.forUI(
				ModSounds.LOCK_SEARCH, pitch, volume * master()));
	}

	/**
	 * The warning for the two actions that take something instead of aiming something.
	 *
	 * <p>Everything the lock tone says would be a lie here. Its cadence carries time-to-impact,
	 * which only matters to someone who can still move; its close into a solid tone says "now",
	 * which is meaningless when the answer was never "dodge". A player who learns the buzz on a
	 * confiscation learns that the buzz sometimes means nothing can be done, and that is exactly
	 * the belief the buzz has to keep on the occasions it does mean something.</p>
	 *
	 * <p>So this is the opposite shape: an even, unhurried pulse on a low sine that falls in pitch
	 * across the window. Nothing accelerates, nothing resolves - it descends, which reads as
	 * something being drawn away rather than something arriving.</p>
	 */
	private static void playDispossessionCue(Minecraft client, long now, float progress) {
		if (lastToneTick != Long.MIN_VALUE && now - lastToneTick < DISPOSSESSION_INTERVAL_TICKS) return;
		lastToneTick = now;
		client.getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.DISPOSSESS,
				Mth.lerp(progress, DISPOSSESSION_START_PITCH, DISPOSSESSION_END_PITCH),
				DISPOSSESSION_VOLUME * master()));
	}

	private static float master() { return (float) Math.clamp(RuntimeServices.config().meta().peakVolume(), 0.0D, 1.0D); }

	public static void reset() {
		trackedEncounterId = null;
		trackedStartTick = Long.MIN_VALUE;
		trackedActionId = -1;
		lastToneTick = Long.MIN_VALUE;
	}
}
