package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The continuous signal layer underneath everything else.
 *
 * <p>Nearly every other sound this mod owns is an event - something fired, something locked,
 * something broke. Analog horror is carried by the opposite: the sound of the medium itself,
 * which is present long enough to stop being noticed. These beds are deliberately mixed low
 * enough to be deniable; the intent is that a player registers them mainly when one stops, or
 * when a second layer joins.</p>
 *
 * <p>Layers accumulate with world decay, so a save that has lived through many anomalies is
 * audibly noisier than a fresh one and never quietens again - the decay floor those anomalies
 * establish is irreversible.</p>
 */
public final class SignalBedController {
	private static final int EVALUATE_INTERVAL_TICKS = 20;
	private static final int FADE_IN_TICKS = 60;
	private static final int FADE_OUT_TICKS = 40;

	private static final Map<Layer, BedLoop> ACTIVE = new EnumMap<>(Layer.class);
	private static int evaluateCooldown;
	private static boolean initialized;

	private SignalBedController() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(SignalBedController::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopAll());
	}

	/** Visible for the client GameTests: which beds are currently sounding. */
	public static Set<Layer> activeLayersForTesting() {
		return EnumSet.copyOf(ACTIVE.isEmpty() ? EnumSet.noneOf(Layer.class) : ACTIVE.keySet());
	}

	private static void tick(Minecraft client) {
		if (client.level == null || client.player == null) {
			if (!ACTIVE.isEmpty()) stopAll();
			return;
		}
		if (evaluateCooldown-- > 0) return;
		evaluateCooldown = EVALUATE_INTERVAL_TICKS;
		reconcile(client, desiredLayers());
	}

	private static Set<Layer> desiredLayers() {
		// The World Interface runs its own scored ambience; stacking beds under it only muddies
		// a mix that is already carrying the whole finale.
		if (WorldInterfaceClientState.snapshot().encounter() != null) return EnumSet.noneOf(Layer.class);

		// The unrendered layer is carried by its own twenty-second ambience and nothing else. The
		// beds belong to the transmission, and the transmission is a thing in the player's world -
		// hearing it from inside the layer would place the layer inside that world too. Decayed
		// stage already reads as zero down there, so this mostly agrees with the line below; it is
		// stated outright because the anomaly-driven layers further down do not go through it.
		if (UnrenderedLayerClient.inLayer()) return EnumSet.noneOf(Layer.class);

		// silent_world takes the world's voice away. Dead air replaces it alone rather than
		// joining the others: the point is that everything stopped except the one thing that
		// was never part of the world to begin with.
		if (AnomalyPresentationController.isSilentWorldActive()) return EnumSet.of(Layer.DEAD_AIR);

		Set<Layer> wanted = EnumSet.noneOf(Layer.class);
		int decay = WorldDecayClient.stage();
		if (decay >= 1) wanted.add(Layer.TAPE_HISS);
		if (decay >= 3) wanted.add(Layer.STATIC);
		if (decay >= 5) wanted.add(Layer.CARRIER);

		if (AnomalyPresentationController.isTemporalDriftActive()) wanted.add(Layer.CARRIER);
		if (AnomalyPresentationController.isMetricDriftActive()) wanted.add(Layer.STATIC);
		// red_horizon ran completely silent, which is what let a forty-second tier-four anomaly
		// be dismissed as a shader. The bed's own sixty-tick fade lines up with the anomaly's
		// arrival, so the sound is already there by the time the colour is worth noticing.
		if (AnomalyPresentationController.isRedHorizonActive()) wanted.add(Layer.CARRIER);

		// The sky monitor is a receiver, and a receiver losing its subject gets louder rather than
		// quieter. These stack onto whatever the anomaly already asked for, so opening the weather
		// tool during one is audibly worse than standing under it - which is the point of a tool
		// that claims to be listening to the sky.
		int monitorStage = openWeatherToolStage();
		if (monitorStage >= 1) wanted.add(Layer.STATIC);
		if (monitorStage >= 2) wanted.add(Layer.CARRIER);
		return wanted;
	}

	/** Stage of the weather tool's sky monitor, or 0 whenever that page is not the one on screen. */
	private static int openWeatherToolStage() {
		if (!(Minecraft.getInstance().screen instanceof TerminalScreen terminal)) return 0;
		return terminal.skyMonitorStage();
	}

	private static void reconcile(Minecraft client, Set<Layer> wanted) {
		for (Layer layer : Layer.values()) {
			BedLoop loop = ACTIVE.get(layer);
			boolean shouldSound = wanted.contains(layer);
			if (shouldSound && (loop == null || loop.isStopped())) {
				BedLoop started = new BedLoop(layer.cue(), layer.relativeVolume());
				ACTIVE.put(layer, started);
				client.getSoundManager().play(started);
			} else if (!shouldSound && loop != null) {
				loop.fadeOut();
				ACTIVE.remove(layer);
			}
		}
	}

	private static void stopAll() {
		for (BedLoop loop : ACTIVE.values()) loop.forceStop();
		ACTIVE.clear();
		evaluateCooldown = 0;
	}

	public enum Layer {
		/** Faintest layer, present from the first decay stage onward. */
		TAPE_HISS(0.34F),
		STATIC(0.30F),
		CARRIER(0.26F),
		/** Already mastered far quieter than the others; the relative gain compensates. */
		DEAD_AIR(0.60F);

		/**
		 * Trim applied to every layer, on top of the mix balance each one declares.
		 *
		 * <p>These beds are meant to be deniable - noticed when one stops, not while it runs - and
		 * at the levels below they were plainly audible instead, which turns a noise floor into a
		 * sound effect. Cutting the whole family by the same factor keeps the balance between the
		 * layers intact, so a save deep enough to be running three of them still reads as noisier
		 * than one running a single hiss.</p>
		 */
		private static final float NOISE_FLOOR_TRIM = 0.20F;

		private final float relativeVolume;

		Layer(float relativeVolume) {
			this.relativeVolume = relativeVolume;
		}

		float relativeVolume() {
			return relativeVolume * NOISE_FLOOR_TRIM;
		}

		SoundEvent cue() {
			return switch (this) {
				case TAPE_HISS -> ModSounds.SIGNAL_TAPE_HISS;
				case STATIC -> ModSounds.SIGNAL_STATIC;
				case CARRIER -> ModSounds.SIGNAL_CARRIER;
				case DEAD_AIR -> ModSounds.SIGNAL_DEAD_AIR;
			};
		}
	}

	private static final class BedLoop extends AbstractTickableSoundInstance {
		/**
		 * Cycles, in ticks, of the two modulators that keep a bed from repeating identically.
		 *
		 * <p>Even a long bed is a fixed recording, and a fixed recording played for the length of
		 * a session eventually announces its own loop point. Real tape and real transmitters
		 * drift, so drifting the playback is not a workaround here - it is the thing being
		 * imitated. The two periods are deliberately not multiples of each other, so their
		 * combination takes far longer to come back around than either one alone.</p>
		 */
		private static final float PITCH_DRIFT_TICKS = 617.0F;
		private static final float BREATH_TICKS = 431.0F;
		private static final float PITCH_DRIFT_AMOUNT = 0.006F;
		private static final float BREATH_AMOUNT = 0.075F;

		private final float relativeVolume;
		/** Keeps each layer's drift out of phase with the others, so they never swell together. */
		private final float phase;
		private int age;
		private int fadeInAge;
		private int fadeOutAge = -1;

		private BedLoop(SoundEvent cue, float relativeVolume) {
			// MASTER rather than AMBIENT on purpose. silent_world mutes the ambient/weather/
			// creature sources to strip the world of its voice, and these beds must survive that:
			// the signal does not belong to the world, so it has no reason to go quiet with it.
			super(cue, SoundSource.MASTER, RandomSource.create());
			this.relativeVolume = Math.clamp(relativeVolume, 0.0F, 1.0F);
			this.phase = this.random.nextFloat() * (float) (Math.PI * 2.0D);
			this.volume = 0.0F;
			this.pitch = 1.0F;
			this.looping = true;
			this.relative = true;
			this.attenuation = Attenuation.NONE;
		}

		private void fadeOut() {
			if (fadeOutAge < 0) fadeOutAge = 0;
		}

		private void forceStop() {
			stop();
		}

		@Override
		public boolean canStartSilent() {
			return true;
		}

		@Override
		public void tick() {
			age++;
			float envelope;
			if (fadeOutAge >= 0) {
				fadeOutAge++;
				envelope = Math.clamp(1.0F - fadeOutAge / (float) FADE_OUT_TICKS, 0.0F, 1.0F);
				if (fadeOutAge >= FADE_OUT_TICKS) {
					stop();
					return;
				}
			} else {
				// Long fades keep a layer from announcing itself as it arrives, which is what
				// would give the whole effect away.
				fadeInAge++;
				envelope = Math.clamp(fadeInAge / (float) FADE_IN_TICKS, 0.0F, 1.0F);
			}
			pitch = 1.0F + PITCH_DRIFT_AMOUNT
					* (float) Math.sin(phase + age * (Math.PI * 2.0D) / PITCH_DRIFT_TICKS);
			float breath = 1.0F + BREATH_AMOUNT
					* (float) Math.sin(phase * 1.7D + age * (Math.PI * 2.0D) / BREATH_TICKS);
			volume = (float) Math.clamp(RuntimeServices.config().meta().effectiveBedVolume()
					* relativeVolume * envelope * breath, 0.0D, 1.0D);
		}
	}
}
