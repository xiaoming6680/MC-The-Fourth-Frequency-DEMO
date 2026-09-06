package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.client_ui.PostEffectArbiter;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.List;
import java.util.Set;

/**
 * Proof that the mod's screen filters actually compile, and a plate of what each one looks like.
 *
 * <p>This exists because of how a post-effect chain fails. A fragment shader that will not compile,
 * a chain that names a shader that is not there, a uniform block whose name does not match: none of
 * them crash, none of them stop a world loading, and none of them are visible in any log a player
 * would think to open. The chain simply never installs and the treatment is silently absent - which,
 * for a horror mod whose entire language is "the picture is wrong", is the one failure that looks
 * exactly like nothing being wrong.
 *
 * <p>{@code PostFilterContractTest} pins the seam between a chain and its shader without a client.
 * What it cannot do is compile GLSL. This can, so this is where that is checked - every chain the
 * mod ships, loaded through the same {@code ShaderManager} the game uses, before any of them is
 * asked to draw.
 */
public final class ScreenFilterClientGameTest implements FabricClientGameTest {
	/** Every chain the mod ships, in the order a playthrough meets them. */
	private static final List<String> CHAINS = List.of(
			"signal_1", "signal_2", "signal_3", "signal_4",
			"signal_still_1", "signal_still_2", "signal_still_3", "signal_still_4",
			"pursuit_low_res_distant", "pursuit_low_res", "pursuit_low_res_close",
			"pursuit_low_res_contact",
			"world_interface_lock", "world_interface_lock_peak", "world_interface_expulsion",
			"world_interface_dispersion", "world_interface_dispersion_far");

	/** One from each family, photographed against a real frame rather than against a flat colour. */
	private static final List<String> PHOTOGRAPHED = List.of(
			"signal_2", "signal_4", "signal_still_4", "pursuit_low_res_distant",
			"pursuit_low_res_contact", "world_interface_lock", "world_interface_lock_peak",
			"world_interface_expulsion", "world_interface_dispersion");

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsScreenFilters()) return;
		context.waitForScreen(TitleScreen.class);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getServer().runOnServer(ScreenFilterClientGameTest::stageViewpoint);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);

			// Compilation first, and all of it, so one broken shader reports itself by name rather
			// than by whichever screenshot happens to come out looking ordinary.
			context.runOnClient(ScreenFilterClientGameTest::assertEveryChainCompiles);

			context.runOnClient(client -> client.options.hideGui = true);
			try {
				context.takeScreenshot("screen-filter-none");
				for (String chain : PHOTOGRAPHED) {
					// Photographed through the level slot whichever family the chain belongs to. The
					// screen-filter driver's requests last one frame by design and are made from
					// render paths, so it cannot be held open from here - and it does not need to be:
					// the loading-screen capture this same run takes is the driver running end to end.
					context.runOnClient(client -> PostEffectArbiter.claim(client,
							PostEffectArbiter.Owner.WORLD_INTERFACE, effect(chain)));
					// Long enough for several of the corruption filter's held slots to have turned
					// over, so a still that happens to land between them is not what gets kept.
					context.waitTicks(12);
					context.takeScreenshot("screen-filter-" + chain.replace('_', '-'));
				}
			} finally {
				context.runOnClient(client -> {
					PostEffectArbiter.releaseAll(client);
					client.options.hideGui = false;
				});
			}
			// The arbiter has to actually let go: a treatment that outlives its owner is worse than
			// one that never arrived, because nothing left on screen explains it.
			context.waitTicks(5);
			context.runOnClient(client -> {
				if (PostEffectArbiter.active() != null || client.gameRenderer.currentPostEffect() != null) {
					throw new AssertionError("a screen filter outlived its claim: "
							+ client.gameRenderer.currentPostEffect());
				}
			});
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static void assertEveryChainCompiles(Minecraft client) {
		StringBuilder broken = new StringBuilder();
		for (String chain : CHAINS) {
			PostChain compiled = client.getShaderManager()
					.getPostChain(effect(chain), Set.of(PostChain.MAIN_TARGET_ID));
			if (compiled == null) broken.append(broken.isEmpty() ? "" : ", ").append(chain);
		}
		if (!broken.isEmpty()) {
			throw new AssertionError("post-effect chains failed to load: " + broken
					+ " (see the log above for the compiler's own message)");
		}
	}

	/**
	 * Spectator on the surface at midday, looking at the horizon.
	 *
	 * <p>The filters are all about what they do to edges, gradients and bright areas, so they need a
	 * frame that has some. A screenshot of the inside of a hill proves the chain installed and
	 * nothing else.
	 */
	private static void stageViewpoint(MinecraftServer server) {
		server.overworld().setDayTime(6000L);
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
		player.snapTo(player.getX(), server.overworld().getHeight(
				net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				(int) player.getX(), (int) player.getZ()) + 24.0D, player.getZ(), 0.0F, 8.0F);
	}

	private static Identifier effect(String path) {
		return Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, path);
	}
}
