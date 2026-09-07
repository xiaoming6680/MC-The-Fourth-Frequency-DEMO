package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.mixin.ChannelSourceAccessor;
import com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor;
import com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.lwjgl.openal.AL10;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Compares actual OpenAL source gain for initial, tickable and refreshed playback. */
final class OutputGainClientCheck {
	static void run(ClientGameTestContext context) {
		var loop = new ProbeLoop();
		SoundInstance[] voices = {
				SimpleSoundInstance.forUI(ModSounds.WORLD_INTERFACE_LASER, 1, .2F),
				SimpleSoundInstance.forUI(SoundEvents.AMBIENT_CAVE.value(), 1, .2F), loop};
		var measured = List.of(new AtomicReference<Float>(), new AtomicReference<Float>(), new AtomicReference<Float>());
		context.runOnClient(client -> { for (var voice : voices) client.getSoundManager().play(voice); });
		try {
			context.waitTicks(4);
			capture(context, voices, measured);
			context.waitTicks(2);
			assertGains(context, voices, measured);
			context.runOnClient(client -> {
				var engine = engine(client);
				for (var voice : voices) {
					// Repeated refreshes must never multiply the already-amplified result.
					engine.refreshCategoryVolume(voice.getSource());
					engine.refreshCategoryVolume(voice.getSource());
				}
				loop.quieter();
			});
			context.waitTicks(4);
			capture(context, voices, measured);
			context.waitTicks(2);
			assertGains(context, voices, measured);
			context.runOnClient(client -> {
				var engine = engine(client);
				var source = voices[0].getSource();
				var gains = ((SoundEngineStateAccessor)engine).thefourthfrequency$gainBySource();
				float saved = gains.getFloat(source);
				try {
					gains.put(source, 0);
					if (updated(engine, voices[0]) != 0) throw new AssertionError("Amplification bypassed category mute");
				} finally { gains.put(source, saved); }
				if (Math.abs(voices[0].getVolume() - .2F) > 1e-5)
					throw new AssertionError("Output amplification changed raw volume and spatial reach");
			});
		} finally {
			context.runOnClient(client -> { for (var voice : voices) client.getSoundManager().stop(voice); });
		}
	}

	private static SoundEngine engine(Minecraft client) {
		return ((SoundManagerEngineAccessor)client.getSoundManager()).thefourthfrequency$soundEngine();
	}

	private static void capture(ClientGameTestContext context, SoundInstance[] voices,
			List<AtomicReference<Float>> measured) {
		context.runOnClient(client -> {
			var channels = ((SoundEngineStateAccessor)engine(client)).thefourthfrequency$instanceToChannel();
			for (int i=0; i<voices.length; i++) {
				var result = measured.get(i); result.set(null);
				var handle = channels.get(voices[i]);
				if (handle == null) throw new AssertionError("Missing output channel: " + voices[i].getIdentifier());
				handle.execute(channel -> result.set(AL10.alGetSourcef(
						((ChannelSourceAccessor)channel).thefourthfrequency$source(), AL10.AL_GAIN)));
			}
		});
	}

	private static void assertGains(ClientGameTestContext context, SoundInstance[] voices,
			List<AtomicReference<Float>> measured) {
		context.runOnClient(client -> {
			try {
				var engine = engine(client);
				var raw = SoundEngine.class.getDeclaredMethod("calculateVolume", float.class, SoundSource.class);
				raw.setAccessible(true);
				for (int i=0; i<voices.length; i++) {
					float base = (float)raw.invoke(engine, voices[i].getVolume(), voices[i].getSource());
					float expected = Math.min(1, base * (i == 1 ? 1 : 1.8F));
					Float actual = measured.get(i).get();
					if (actual == null || Math.abs(expected-actual) > 1e-4)
						throw new AssertionError("Native gain mismatch " + voices[i].getIdentifier() + ": " + actual + " vs " + expected);
					if (Math.abs(updated(engine, voices[i])-expected) > 1e-4)
						throw new AssertionError("Initial and refreshed gain disagree");
				}
			} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
		});
	}

	private static float updated(SoundEngine engine, SoundInstance voice) {
		try {
			var method = SoundEngine.class.getDeclaredMethod("calculateVolume", SoundInstance.class);
			method.setAccessible(true);
			return (float)method.invoke(engine, voice);
		} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
	}

	private static final class ProbeLoop extends AbstractTickableSoundInstance {
		ProbeLoop() {
			super(ModSounds.WORLD_INTERFACE_LASER_LOOP, SoundSource.UI, RandomSource.create());
			volume=.2F; pitch=1; looping=true; relative=true; attenuation=Attenuation.NONE;
		}
		void quieter() { volume=.1F; }
		@Override public void tick() {}
	}
}
