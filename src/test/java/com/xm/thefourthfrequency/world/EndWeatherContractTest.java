package com.xm.thefourthfrequency.world;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-layer seam the End's weather sits on, and the one rule it may never break.
 *
 * <p>Rain, thunder and their timers live in a single {@code ServerLevelData} shared by every
 * dimension in the save. There is a shorter way to make the End rain than the one this feature
 * takes - call {@code setWeatherParameters} on the End's {@code ServerLevel}, or write its rain
 * level server-side - and it would appear to work perfectly: the End would rain. It would also make
 * the <b>Overworld</b> rain, permanently, and go on doing so after the End was left, because the
 * record being written is not the End's. That is a save-visible change made as a side effect of an
 * atmosphere, which is the exact class of thing the world bible refuses.
 *
 * <p>Nothing else in the build would notice. So the refusal is written down here.
 */
final class EndWeatherContractTest {
	private static final Path COMMON = Path.of("src/main/java/com/xm/thefourthfrequency");
	private static final Path CLIENT = Path.of("src/client/java/com/xm/thefourthfrequency");

	private static String read(Path root, String relative) throws Exception {
		return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
	}

	/** No path in the mod writes the save's weather, from either side. */
	@Test
	void theSaveSharedWeatherRecordIsNeverWritten() throws Exception {
		for (Path root : new Path[]{COMMON, CLIENT}) {
			try (var files = Files.walk(root)) {
				for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
					String source = Files.readString(file, StandardCharsets.UTF_8);
					assertFalse(source.contains("setWeatherParameters("),
							file + " sets the save's shared weather; the End's rain is client-side "
									+ "presentation and must stay that way");
					assertFalse(source.contains("setThunderLevel("),
							file + " writes a thunder level; the End's thunder is a scheduled sound, "
									+ "not a weather state");
				}
			}
		}
		// The one write that is allowed, and where it is allowed to be.
		String client = read(CLIENT, "client_ui/EndWeatherClient.java");
		assertTrue(client.contains("level.setRainLevel("),
				"the End's rain has to be written somewhere, and this is the only place for it");
		int guard = client.indexOf("level.dimension() != Level.END");
		int write = client.indexOf("level.setRainLevel(");
		assertTrue(guard >= 0 && guard < write,
				"the rain level must be written behind a dimension guard, or this feature reaches "
						+ "every other dimension the player is in");
	}

	/** Both halves of the feature are registered, or it silently does nothing. */
	@Test
	void bothSwitchesAreWiredIn() throws Exception {
		String manifest = Files.readString(
				Path.of("src/main/resources/thefourthfrequency.mixins.json"), StandardCharsets.UTF_8);
		assertTrue(manifest.contains("\"WeatherEffectRendererEndRainMixin\""),
				"the precipitation switch is not in the mixin manifest, so it never loads");
		assertTrue(Files.isRegularFile(CLIENT.resolve("mixin/WeatherEffectRendererEndRainMixin.java")),
				"the manifest names a mixin that does not exist; defaultRequire 1 makes that a crash");
		String entrypoint = read(CLIENT, "client_ui/TheFourthFrequencyClient.java");
		assertTrue(entrypoint.contains("EndWeatherClient.initialize()"),
				"the rain-level switch is never initialised, so the renderer is asked to draw rain "
						+ "at intensity zero and draws nothing");
	}

	/**
	 * The precipitation override keeps vanilla's loaded-chunk test.
	 *
	 * <p>{@code getPrecipitationAt} answers NONE for a column whose chunk the client has not been
	 * sent. Injecting at HEAD, or widening without re-checking, draws rain over the void the player
	 * is still waiting to receive - which on a first entry into the End is most of the screen.
	 */
	@Test
	void theOverrideDoesNotRainOnChunksTheClientDoesNotHave() throws Exception {
		String mixin = read(CLIENT, "mixin/WeatherEffectRendererEndRainMixin.java");
		assertTrue(mixin.contains("@At(\"RETURN\")"),
				"injecting at HEAD would skip the loaded-chunk test the method opens with");
		assertTrue(mixin.contains("Biome.Precipitation.NONE"),
				"the override must only widen NONE, never rewrite a column vanilla already answered");
		String client = read(CLIENT, "client_ui/EndWeatherClient.java");
		assertTrue(client.contains("hasChunk("),
				"rainsAt must repeat vanilla's loaded-chunk test rather than assume it");
	}
}
