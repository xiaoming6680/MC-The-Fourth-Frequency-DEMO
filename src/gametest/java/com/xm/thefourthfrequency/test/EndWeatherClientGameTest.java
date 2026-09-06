package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.client_ui.EndWeatherClient;
import com.xm.thefourthfrequency.world.EndWeatherPolicy;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.WeatherRenderState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Set;

/**
 * The End rains, thunders, and still has no clouds - proved in a real client, not in a string.
 *
 * <p>This suite exists because the unit tests structurally cannot reach the thing most likely to
 * break. The rain arrives through a mixin on a private renderer method, and a mixin whose target or
 * descriptor is wrong compiles, remaps and passes every source-level assertion in the build; it
 * fails when the class is actually loaded. {@code WeatherEffectRenderer} is loaded the moment a
 * world is rendered, so simply getting here is already the first assertion.
 *
 * <p>The second is stronger: rather than trusting that the renderer would have drawn something, this
 * runs the real extraction and looks at what came out. {@code extractRenderState} is the method that
 * asks every column in the weather radius what falls on it - which is the exact call the mixin
 * widens - so a non-empty column list is end-to-end evidence that the End is raining, taken from
 * vanilla's own renderer.
 */
public final class EndWeatherClientGameTest implements FabricClientGameTest {
	/** Comfortably past the fade in, so the rain is at its ceiling rather than on its way there. */
	private static final int SETTLE_TICKS = EndWeatherPolicy.FADE_IN_TICKS + 40;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsWorldInterface()) return;
		context.waitForScreen(TitleScreen.class);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(EndWeatherClientGameTest::sendToTheEnd);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(SETTLE_TICKS);

			context.runOnClient(EndWeatherClientGameTest::assertItIsRaining);
			context.runOnClient(EndWeatherClientGameTest::assertTheRendererProducesRain);
			context.runOnClient(EndWeatherClientGameTest::assertThereAreNoClouds);

			context.runOnClient(client -> client.options.hideGui = true);
			try {
				context.takeScreenshot("end-weather-rain");
			} finally {
				context.runOnClient(client -> client.options.hideGui = false);
			}
		}
		context.waitForScreen(TitleScreen.class);
	}

	/** Spectator on the main island, high enough that the weather radius has columns to fill. */
	private static void sendToTheEnd(MinecraftServer server) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) throw new AssertionError("this save has no End to rain on");
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		player.teleportTo(end, 0.5D, 80.0D, 0.5D, Set.<Relative>of(), 0.0F, 0.0F, false);
		player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
		int surface = end.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 0, 0);
		player.snapTo(0.5D, surface + 6.0D, 0.5D, 0.0F, 0.0F);
	}

	private static void assertItIsRaining(Minecraft client) {
		if (client.level == null) throw new AssertionError("no client level after entering the End");
		if (client.level.dimension() != Level.END) {
			throw new AssertionError("the client is not in the End: " + client.level.dimension());
		}
		float level = client.level.getRainLevel(1.0F);
		if (level <= 0.0F) {
			throw new AssertionError("the End is not raining; the rain level is " + level);
		}
		// The fade is over, so the level should have reached the swell rather than be climbing to it.
		float expected = EndWeatherPolicy.breathing(client.level.getGameTime());
		if (Math.abs(level - expected) > 0.05F) {
			throw new AssertionError("the rain level settled at " + level + " rather than " + expected);
		}
		// And the rules still say it is not raining, which is the containment this feature relies on.
		//
		// canHaveWeather() names the End explicitly and answers false for it whatever the rain level
		// is, so every predicate built on it - isRaining, isRainingAt, and everything that asks them
		// - keeps the answer it has always had. The drops are drawn from getRainLevel alone. If this
		// ever flips to true, the feature has stopped being presentation and has started changing
		// what the game thinks the weather is.
		if (client.level.isRaining()) {
			throw new AssertionError("the End now believes it is raining; this feature is supposed to "
					+ "be visible and audible without being true");
		}
		if (!EndWeatherClient.rainsAt(client.level, client.player.blockPosition())) {
			throw new AssertionError("the precipitation predicate refuses the column the player is in");
		}
	}

	/**
	 * The mixin's own path, run for real.
	 *
	 * <p>Every column inside the weather radius is resolved through {@code getPrecipitationAt}, which
	 * is the method the mixin widens from NONE to RAIN. If that injection did not apply - wrong
	 * descriptor, wrong remap, dropped from the manifest - this list comes back empty while
	 * everything above still passes, because the rain level would be set and simply never drawn.
	 */
	private static void assertTheRendererProducesRain(Minecraft client) {
		WeatherRenderState state = new WeatherRenderState();
		new WeatherEffectRenderer().extractRenderState(client.level, (int) client.level.getGameTime(),
				1.0F, client.gameRenderer.getMainCamera().position(), state);
		if (state.intensity <= 0.0F) {
			throw new AssertionError("the weather extraction saw intensity " + state.intensity);
		}
		if (state.rainColumns.isEmpty()) {
			throw new AssertionError("the End's rain level is set but no column resolved to rain: "
					+ "the precipitation mixin is not applying");
		}
	}

	/**
	 * Rain, and no clouds with it.
	 *
	 * <p>Vanilla's End has no cloud layer, and adding weather must not quietly bring one back: a
	 * cloud plane over the End would be a ceiling over the arena the finale is fought in, and the
	 * whole point of that arena is that there is nothing above it. Asserted at the value the
	 * renderer actually gates on - a fully transparent cloud colour is what makes {@code
	 * addCloudsPass} skip - rather than on this feature happening not to have touched it.
	 */
	private static void assertThereAreNoClouds(Minecraft client) {
		int cloudColor = client.gameRenderer.getMainCamera().attributeProbe()
				.getValue(EnvironmentAttributes.CLOUD_COLOR, 1.0F);
		if (ARGB.alpha(cloudColor) > 0) {
			throw new AssertionError("the End grew a cloud layer: cloud colour alpha is "
					+ ARGB.alpha(cloudColor));
		}
	}
}
