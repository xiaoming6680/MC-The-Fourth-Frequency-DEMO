package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.client_ui.FailureMenuLockState;
import com.xm.thefourthfrequency.client_ui.ResonanceAltarScreen;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceClientState;
import com.xm.thefourthfrequency.client_ui.DimensionViewDistanceController;
import com.xm.thefourthfrequency.client_ui.DimensionViewDistancePolicy;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceResourcePackLease;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceVanillaPoemClient;
import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.AltarShape;
import com.xm.thefourthfrequency.ending.EndBossArenaService;
import com.xm.thefourthfrequency.ending.WorldInterfaceGatewayState;
import com.xm.thefourthfrequency.ending.WorldInterfacePolicy;
import com.xm.thefourthfrequency.ending.WorldInterfaceStage;
import com.xm.thefourthfrequency.ending.WorldInterfaceState;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.WinScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Executable visual and protocol evidence for the World Interface client finale.
 *
 * <p>The live half sends the real versioned payloads over the integrated connection and captures
 * every authored form, morph, combat action, gateway color, altar, and poem. The local half
 * exercises strict cross-payload sequencing plus the pure recovery selector; source contracts are
 * reserved for the intentionally destructive failure shutdown path.</p>
 */
public final class WorldInterfaceClientGameTest implements FabricClientGameTest {
	private static final UUID NIL_UUID = new UUID(0L, 0L);
	private static final List<WorldInterfaceProtocol.BossAction> COMBAT_ACTIONS = List.of(
			WorldInterfaceProtocol.BossAction.LASER_SWEEP,
			WorldInterfaceProtocol.BossAction.ENERGY_ORB,
			WorldInterfaceProtocol.BossAction.SKY_LANCE,
			WorldInterfaceProtocol.BossAction.WEAPON_CHARGE,
			WorldInterfaceProtocol.BossAction.GRAB_THROW,
			WorldInterfaceProtocol.BossAction.HOTBAR_PURGE,
			WorldInterfaceProtocol.BossAction.TENDRIL_LASH,
			WorldInterfaceProtocol.BossAction.FORCED_EXPULSION);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsWorldInterface()) return;
		assertWireContract();
		assertEndingSourceContracts();
		assertLaserRecoveryGeometry();
		context.waitForScreen(TitleScreen.class);
		selectChineseLanguage(context);
		context.runOnClient(client -> {
			DimensionViewDistanceController.resetForTesting(client);
			client.options.hideGui = false;
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			EntityVisualFixture.finishFirstBoot(context);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			assertLockedRenderDistance(context, Level.OVERWORLD,
					DimensionViewDistancePolicy.OVERWORLD_CHUNKS);
			singleplayer.getServer().runOnServer(server ->
					teleportForViewDistance(server, Level.NETHER));
			assertLockedRenderDistance(context, Level.NETHER,
					DimensionViewDistancePolicy.NETHER_CHUNKS);
			singleplayer.getServer().runOnServer(server ->
					teleportForViewDistance(server, Level.OVERWORLD));
			assertLockedRenderDistance(context, Level.OVERWORLD,
					DimensionViewDistancePolicy.OVERWORLD_CHUNKS);

			Fixture fixture = singleplayer.getServer().computeOnServer(WorldInterfaceClientGameTest::createFixture);
			assertLockedRenderDistance(context, Level.END, DimensionViewDistancePolicy.END_CHUNKS);
			context.waitTicks(20);
			context.runOnClient(client -> {
				assertStrictClientSequence(fixture);
				assertLocalRecoveryContract();
			});

			assertHeadHitboxMatchesTheDrawnHead(context, singleplayer, fixture);
			assertStormParticleTransport(context, singleplayer);

			captureForm(context, singleplayer, fixture, WorldInterfaceProtocol.Stage.PHASE_1,
					WorldInterfaceProtocol.Form.LISTENING_EMBRYO, 0.92F, "world-interface-form-listening-embryo");
			captureMorph(context, singleplayer, fixture, WorldInterfaceProtocol.BossAction.MORPH_TO_SECOND,
					WorldInterfaceProtocol.Form.FREQUENCY_DEVOURER, "world-interface-morph-second");
			captureForm(context, singleplayer, fixture, WorldInterfaceProtocol.Stage.PHASE_2,
					WorldInterfaceProtocol.Form.FREQUENCY_DEVOURER, 0.62F,
					"world-interface-form-frequency-devourer");
			captureMorph(context, singleplayer, fixture, WorldInterfaceProtocol.BossAction.MORPH_TO_THIRD,
					WorldInterfaceProtocol.Form.WORLD_INTERFACE, "world-interface-morph-third");
			captureForm(context, singleplayer, fixture, WorldInterfaceProtocol.Stage.PHASE_3,
					WorldInterfaceProtocol.Form.WORLD_INTERFACE, 0.30F,
					"world-interface-form-world-interface");
			captureResolutionCoverage(context);

			for (WorldInterfaceProtocol.BossAction action : COMBAT_ACTIONS) {
				captureCombatAction(context, singleplayer, fixture, action);
			}

			captureGateway(context, singleplayer, fixture, WorldInterfaceProtocol.GatewayState.PURPLE);
			captureGateway(context, singleplayer, fixture, WorldInterfaceProtocol.GatewayState.GOLD);
			captureGateway(context, singleplayer, fixture, WorldInterfaceProtocol.GatewayState.RED);
			captureAnchorLossLeavesTheClock(context, singleplayer, fixture);
			captureAltar(context, singleplayer, fixture);
			capturePoems(context, singleplayer, fixture);
			captureRealPortalRoundTrip(context, singleplayer);

			context.runOnClient(client -> {
				client.setScreen(null);
				WorldInterfaceClientState.clearSession();
			});
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static void selectChineseLanguage(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.languageCode = "zh_cn";
			client.getLanguageManager().setSelected("zh_cn");
			client.reloadResourcePacks();
		});
		context.waitFor(client -> client.getOverlay() == null
				&& client.getLanguageManager().getSelected().equals("zh_cn")
				&& client.options.languageCode.equals("zh_cn"), 200);
	}

	private static void assertLockedRenderDistance(ClientGameTestContext context,
			net.minecraft.resources.ResourceKey<Level> dimension, int expected) {
		context.waitFor(client -> client.level != null && client.level.dimension() == dimension
				&& client.options.renderDistance().get().equals(expected)
				&& client.options.getEffectiveRenderDistance() == expected, 100);
		context.runOnClient(client -> {
			int rejected = expected == 12 ? 2 : 12;
			client.options.renderDistance().set(rejected);
			require(client.options.renderDistance().get().equals(expected)
					&& client.options.getEffectiveRenderDistance() == expected,
					"Render-distance setter bypassed the dimension lock for " + dimension.identifier());
		});
	}

	private static void assertUnlockedRenderDistance(ClientGameTestContext context,
			net.minecraft.resources.ResourceKey<Level> dimension) {
		context.waitFor(client -> client.level != null && client.level.dimension() == dimension
				&& !DimensionViewDistanceController.isLocked()
				&& client.options.renderDistance().get().equals(
						DimensionViewDistancePolicy.SUCCESS_RETURN_CHUNKS)
				&& client.options.getEffectiveRenderDistance()
						== DimensionViewDistancePolicy.SUCCESS_RETURN_CHUNKS, 100);
		context.runOnClient(client -> {
			require(ConfigManager.loadClientState().viewDistanceUnlocked(),
					"Successful return did not persist the view-distance unlock");
			DimensionViewDistanceController.reloadFromDiskForTesting();
			require(!DimensionViewDistanceController.isLocked(),
					"Reloading client state restored the completed view-distance lock");
			var widget = client.options.renderDistance().createButton(
					client.options, 0, 0, 150, ignored -> { });
			require(widget.active, "Render-distance control remained disabled after successful return");
			client.options.renderDistance().set(10);
			require(client.options.renderDistance().get().equals(10)
					&& client.options.getEffectiveRenderDistance() == 10,
					"Render-distance setter remained locked after successful return");
			client.options.renderDistance().set(DimensionViewDistancePolicy.SUCCESS_RETURN_CHUNKS);
		});
	}

	private static void teleportForViewDistance(MinecraftServer server,
			net.minecraft.resources.ResourceKey<Level> dimension) {
		ServerLevel destination = server.getLevel(dimension);
		if (destination == null) throw new AssertionError("View-distance test dimension was unavailable: " + dimension);
		ServerPlayer player = firstPlayer(server);
		player.setInvulnerable(true);
		player.setNoGravity(dimension == Level.NETHER);
		BlockPos position = dimension == Level.OVERWORLD
				? server.overworld().getRespawnData().pos() : new BlockPos(0, 80, 0);
		if (!player.teleportTo(destination, position.getX() + 0.5D, position.getY() + 1.0D,
				position.getZ() + 0.5D, Set.of(), 0.0F, 0.0F, true)) {
			throw new AssertionError("Could not enter view-distance test dimension: " + dimension.identifier());
		}
	}

	private static void captureForm(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture, WorldInterfaceProtocol.Stage stage, WorldInterfaceProtocol.Form form,
			float healthRatio, String screenshot) {
		long sequence = singleplayer.getServer().computeOnServer(server -> {
			WorldInterfaceEntity body = body(requireEnd(server), fixture.bodyId());
			body.clearAction();
			body.setForm(form.wireId() - 1);
			return sendEncounter(server, fixture, stage, form, healthRatio,
					WorldInterfaceProtocol.ANCHOR_MASK, 1_200L, false,
					WorldInterfaceProtocol.GatewayState.PURPLE, WorldInterfaceProtocol.Outcome.NONE, 0.0F);
		});
		waitForEncounter(context, fixture, sequence, stage, form);
		context.waitTicks(8);
		captureFormAngles(context, singleplayer, fixture, screenshot);
	}

	private static void assertStormParticleTransport(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
		var original = context.computeOnClient(client -> client.options.particles().get());
		context.runOnClient(client -> {
			client.options.particles().set(net.minecraft.server.level.ParticleStatus.ALL);
			com.xm.thefourthfrequency.client_render.StormParticleReceiver.clear();
		});
		singleplayer.getServer().runOnServer(server -> {
			var player = firstPlayer(server);
			var center = player.getEyePosition().add(player.getLookAngle().scale(5));
			com.xm.thefourthfrequency.ending.WorldInterfaceVfx.orientedRing((ServerLevel) player.level(),
					com.xm.thefourthfrequency.ending.WorldInterfaceVfx.sigil(), center,
					player.getLookAngle(), 2, 128, 0, 0.01);
		});
		context.waitFor(client -> com.xm.thefourthfrequency.client_render.StormParticleReceiver
				.diagnostics().samples() >= 128, 60);
		context.runOnClient(client -> {
			var stats = com.xm.thefourthfrequency.client_render.StormParticleReceiver.diagnostics();
			if (stats.batches() >= 16 || stats.spawned() < 128)
				throw new AssertionError("Storm ring did not arrive in compact batches: " + stats);
			System.out.println("Storm transport verified: " + stats);
			var wrongWorld = new com.xm.thefourthfrequency.networking.StormParticleBatchS2C(
					Level.OVERWORLD.identifier(), BlockPos.ZERO, List.of(
						new com.xm.thefourthfrequency.networking.StormParticleBatchS2C.Sample(0, 0, 0, 0, 0, 0, 0, 0, 0)));
			com.xm.thefourthfrequency.client_render.StormParticleReceiver.accept(client, wrongWorld);
			if (!stats.equals(com.xm.thefourthfrequency.client_render.StormParticleReceiver.diagnostics()))
				throw new AssertionError("A stale effect crossed dimensions");
		});
		context.waitTicks(3); // Newly constructed particles start transparent and fade in over two ticks.
		context.takeScreenshot("world-interface-batched-storm-ring");
		context.runOnClient(client -> {
			client.options.particles().set(net.minecraft.server.level.ParticleStatus.MINIMAL);
			com.xm.thefourthfrequency.client_render.StormParticleReceiver.clear();
			var flood = new com.xm.thefourthfrequency.networking.StormParticleBatchS2C(
					client.level.dimension().identifier(), client.player.blockPosition(), java.util.Collections.nCopies(256,
					new com.xm.thefourthfrequency.networking.StormParticleBatchS2C.Sample(0, 0, 0, 0, 64, 0, 0, 0, 0)));
			com.xm.thefourthfrequency.client_render.StormParticleReceiver.accept(client, flood);
			com.xm.thefourthfrequency.client_render.StormParticleReceiver.accept(client, flood);
			if (com.xm.thefourthfrequency.client_render.StormParticleReceiver.diagnostics().spawned() != 512)
				throw new AssertionError("Minimal graphics or aggregate per-tick particle budget was ignored");
		});
		context.runOnClient(client -> client.options.particles().set(original));
	}

	private static void captureFormAngles(ClientGameTestContext context,
			TestSingleplayerContext singleplayer, Fixture fixture, String screenshot) {
		String[] labels = {"front", "side", "rear"};
		context.runOnClient(client -> client.options.hideGui = true);
		int[][] offsets = {{0, 38}, {38, 0}, {0, -38}};
		float[] yaws = {180.0F, 90.0F, 0.0F};
		for (int index = 0; index < labels.length; index++) {
			int view = index;
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel end = requireEnd(server);
				BlockPos requested = fixture.center().offset(offsets[view][0], 0, offsets[view][1]);
				BlockPos camera = surfaceAir(end, requested.getX(), requested.getZ());
				firstPlayer(server).teleportTo(end, camera.getX() + 0.5D, camera.getY(), camera.getZ() + 0.5D,
						Set.of(), yaws[view], -24.0F, true);
			});
			context.waitTicks(6);
			EntityVisualFixture.assertVisible(context, fixture.bodyId());
			context.takeScreenshot(screenshot + "-" + labels[index]);
		}
		context.runOnClient(client -> client.options.hideGui = false);
	}

	private static void captureResolutionCoverage(ClientGameTestContext context) {
		context.runOnClient(client -> {
			try {
				var fit = com.xm.thefourthfrequency.client_ui.WorldInterfaceHud.class.getDeclaredMethod(
						"fit", net.minecraft.client.gui.Font.class, String.class, int.class);
				fit.setAccessible(true);
				for (String text : List.of("承伤增加 · 出手更密", "腐化正在自我修复...", "第四频段 · 世界接口",
						"ANCHORS DESTROYED: DAMAGE AND ATTACK FREQUENCY INCREASED", "100%")) {
					for (int width : new int[]{0, 1, 5, 12, 36, 72, 140, 210}) {
						String clipped = (String) fit.invoke(null, client.font, text, width);
						if (client.font.width(clipped) > width) throw new AssertionError("HUD text exceeds its measured column");
					}
				}
			} catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
		});
		FramebufferSize original = context.computeOnClient(client -> new FramebufferSize(
				client.getWindow().getWidth(), client.getWindow().getHeight()));
		captureAtResolution(context, 854, 480, "world-interface-resolution-854x480");
		captureAtResolution(context, 640, 360, "world-interface-resolution-640x360");
		captureAtResolution(context, 1920, 1080, "world-interface-resolution-1920x1080");
		context.runOnClient(client -> {
			client.getWindow().setWidth(original.width());
			client.getWindow().setHeight(original.height());
			client.resizeDisplay();
		});
		context.waitTicks(3);
	}

	private static void captureAtResolution(ClientGameTestContext context,
			int width, int height, String screenshot) {
		context.runOnClient(client -> {
			client.getWindow().setWidth(width);
			client.getWindow().setHeight(height);
			client.resizeDisplay();
		});
		context.waitFor(client -> client.getWindow().getWidth() == width
				&& client.getWindow().getHeight() == height, 40);
		context.waitTicks(3);
		Path path = context.takeScreenshot(screenshot);
		try {
			byte[] header = Files.readAllBytes(path);
			if (header.length < 24 || header[0] != (byte) 0x89 || header[1] != 'P'
					|| header[2] != 'N' || header[3] != 'G') {
				throw new AssertionError("Resolution evidence is not a PNG: " + path);
			}
			ByteBuffer dimensions = ByteBuffer.wrap(header, 16, 8);
			int actualWidth = dimensions.getInt();
			int actualHeight = dimensions.getInt();
			if (actualWidth != width || actualHeight != height) {
				throw new AssertionError("Resolution evidence mismatch for " + screenshot + ": expected "
						+ width + "x" + height + ", got " + actualWidth + "x" + actualHeight);
			}
		} catch (IOException exception) {
			throw new AssertionError("Unable to verify resolution evidence " + path, exception);
		}
	}

	/**
	 * The drawn head and the box a swing tests are the same pose on both sides.
	 *
	 * <p>Every input {@code WorldInterfaceRig.pose} reads is synchronised - except, until this test
	 * existed, the tick count. An entity's {@code tickCount} starts when it enters <em>that side's</em>
	 * world, so the client began counting when the body came into tracking range and the server when
	 * it was summoned. Measured here before the fix: five ticks apart, posing the same skeleton at two
	 * points in its hover drift and leaving the drawn head <b>0.464 blocks</b> from the hit box, of
	 * which <b>0.446 lay along z</b> - the axis a player swings down. That is the "I can see the head
	 * and I swing straight through it" report, and it got worse with animation amplitude: the sample
	 * was first form, seconds in, with nothing running but the drift.
	 *
	 * <p>Asserted on the offset rather than on the clocks, because agreeing about the clock is only
	 * the means. What has to be true is that the two agree about where the head <em>is</em>.
	 */
	private static void assertHeadHitboxMatchesTheDrawnHead(ClientGameTestContext context,
			TestSingleplayerContext singleplayer, Fixture fixture) {
		context.waitTicks(25);
		long[] server = singleplayer.getServer().computeOnServer(serverInstance -> {
			WorldInterfaceEntity body = body(requireEnd(serverInstance), fixture.bodyId());
			var offset = body.rigPose().headOffset(0);
			return new long[]{Math.round(offset.x * 1000.0D), Math.round(offset.y * 1000.0D),
					Math.round(offset.z * 1000.0D)};
		});
		context.runOnClient(client -> {
			if (client.level == null) throw new AssertionError("No client level for the head probe");
			WorldInterfaceEntity body = null;
			for (var entity : client.level.entitiesForRendering()) {
				if (entity instanceof WorldInterfaceEntity candidate
						&& candidate.getUUID().equals(fixture.bodyId())) {
					body = candidate;
					break;
				}
			}
			if (body == null) throw new AssertionError("The client has no body entity to probe");
			var offset = body.rigPose().headOffset(0);
			double dx = offset.x - server[0] / 1000.0D;
			double dy = offset.y - server[1] / 1000.0D;
			double dz = offset.z - server[2] / 1000.0D;
			double separation = Math.sqrt(dx * dx + dy * dy + dz * dz);
			// Not zero, and it cannot be: the two reads happen on different threads a tick or two
			// apart, and the hover drift alone carries the head about 0.09 blocks per tick. The bound
			// is what a couple of ticks of read skew can produce - a third of the 0.464 this caught
			// when the clocks themselves disagreed, and still nowhere near a missed swing.
			if (separation > 0.20D) {
				throw new AssertionError(String.format(
						"The head a player sees and the head they can hit are %.3f blocks apart "
								+ "(dx=%.3f dy=%.3f dz=%.3f); the rig is being posed on two clocks",
						separation, dx, dy, dz));
			}
		});
	}

	private static void captureMorph(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture, WorldInterfaceProtocol.BossAction action, WorldInterfaceProtocol.Form targetForm,
			String screenshot) {
		long sequence = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel end = requireEnd(server);
			WorldInterfaceEntity body = body(end, fixture.bodyId());
			body.setForm(targetForm.wireId() - 1);
			return sendAction(server, fixture, action, 100);
		});
		waitForAction(context, fixture, sequence, action);
		for (int tick : new int[]{10, 35, 65}) {
			context.waitFor(client -> client.level != null && java.util.stream.StreamSupport
					.stream(client.level.entitiesForRendering().spliterator(), false)
					.anyMatch(e -> e.getUUID().equals(fixture.bodyId()) && e instanceof WorldInterfaceEntity body
							&& body.actionAgeMillis() >= tick * 50L), 100);
			EntityVisualFixture.assertVisible(context, fixture.bodyId());
			context.takeScreenshot(screenshot + "-tick-" + tick);
		}
	}

	private static void captureCombatAction(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture, WorldInterfaceProtocol.BossAction action) {
		int duration = 0;
		for (var clip : com.xm.thefourthfrequency.entity.WorldInterfaceClips.clipsForAction(action.wireId()))
			duration = Math.max(duration, Math.round(clip.lengthSeconds() * 20));
		final int ticks = duration;
		long sequence = singleplayer.getServer().computeOnServer(server -> {
			var end = requireEnd(server);
			BlockPos camera = surfaceAir(end, fixture.center().getX(), fixture.center().getZ() + 38);
			firstPlayer(server).teleportTo(end,camera.getX()+.5,camera.getY(),camera.getZ()+.5,Set.of(),180,-24,true);
			WorldInterfaceEntity body = body(end, fixture.bodyId());
			body.setForm(formForAction(action).wireId() - 1);
			return sendAction(server, fixture, action, ticks);
		});
		waitForAction(context, fixture, sequence, action);
		int[] samples = switch (action) {
			case LASER_SWEEP -> new int[]{60,86,94,112,125};
			case ENERGY_ORB -> new int[]{25,38,43,55};
			case SKY_LANCE -> new int[]{48,82,92,103};
			case TENDRIL_LASH -> new int[]{40,65,77,84,111,122,167,174};
			default -> new int[]{Math.max(1,ticks/3),Math.max(2,ticks*2/3),Math.max(3,ticks-5)};
		};
		for(int targetAge:samples){
			context.waitFor(client -> {
				if(client.level==null)return false;
				for(var e:client.level.entitiesForRendering())
					if(e.getUUID().equals(fixture.bodyId()) && e instanceof WorldInterfaceEntity b)
						return b.actionAgeMillis() >= targetAge*50L;
				return false;
			},ticks+40);
			EntityVisualFixture.assertVisible(context,fixture.bodyId());
			context.takeScreenshot("world-interface-action-"+action.name().toLowerCase(Locale.ROOT)+"-t"+targetAge);
		}
	}

	private static void captureGateway(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture, WorldInterfaceProtocol.GatewayState gatewayState) {
		long sequence = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel end = requireEnd(server);
			WorldInterfaceEntity body = body(end, fixture.bodyId());
			body.setForm(WorldInterfaceEntity.FORM_INTERFACE);
			WorldInterfaceProtocol.Stage stage;
			WorldInterfaceProtocol.Outcome outcome;
			float failureProgress;
			switch (gatewayState) {
				case PURPLE -> {
					stage = WorldInterfaceProtocol.Stage.PHASE_3;
					outcome = WorldInterfaceProtocol.Outcome.NONE;
					failureProgress = 0.0F;
					body.clearAction();
				}
				case GOLD -> {
					stage = WorldInterfaceProtocol.Stage.PORTAL_OPEN;
					outcome = WorldInterfaceProtocol.Outcome.SUCCESS;
					failureProgress = 0.0F;
					body.showAction(WorldInterfaceProtocol.BossAction.SUCCESS_DEATH.wireId(),
							end.getGameTime(), 120);
				}
				case RED -> {
					stage = WorldInterfaceProtocol.Stage.FAILURE_RESOLUTION;
					outcome = WorldInterfaceProtocol.Outcome.FAILURE;
					failureProgress = 0.78F;
					body.showAction(WorldInterfaceProtocol.BossAction.FAILURE_ESCAPE.wireId(),
							end.getGameTime(), 120);
				}
				default -> throw new AssertionError("The visual gateway test requires an active color");
			}
			return sendEncounter(server, fixture, stage, WorldInterfaceProtocol.Form.WORLD_INTERFACE,
					0.25F, WorldInterfaceProtocol.ANCHOR_MASK, 3_000L, false,
					gatewayState, outcome, failureProgress);
		});
		context.waitFor(client -> {
			WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
			return fixture.encounterId().equals(projection.encounterId())
					&& projection.lastSequence() >= sequence && projection.encounter() != null
					&& projection.encounter().gatewayState() == gatewayState;
		}, 160);
		context.waitTicks(12);
		context.takeScreenshot("world-interface-gateway-" + gatewayState.name().toLowerCase(Locale.ROOT));
	}

	/**
	 * Cutting an anchor must leave the collapse clock exactly where it was. It used to spend ten
	 * percent of the fight, which made the encounter's central action cost the player the time they
	 * needed to use it in; the gauge's gold sweep reports what the anchor bought instead.
	 */
	private static void captureAnchorLossLeavesTheClock(ClientGameTestContext context,
			TestSingleplayerContext singleplayer, Fixture fixture) {
		// A quarter of the way through the clock, derived rather than written out: what this test is
		// about is that cutting an anchor does not move the collapse projection, and the particular
		// moment it samples at is incidental. A literal tick count silently starts describing a
		// different fraction the moment the encounter's length changes.
		long quarterTicks = WorldInterfaceProtocol.COLLAPSE_DURATION_TICKS / 4L;
		long baselineSequence = singleplayer.getServer().computeOnServer(server -> {
			body(requireEnd(server), fixture.bodyId()).clearAction();
			return sendEncounter(server, fixture, WorldInterfaceProtocol.Stage.PHASE_3,
					WorldInterfaceProtocol.Form.WORLD_INTERFACE, 0.28F,
					WorldInterfaceProtocol.ANCHOR_MASK, quarterTicks, true,
					WorldInterfaceProtocol.GatewayState.PURPLE, WorldInterfaceProtocol.Outcome.NONE, 0.0F);
		});
		waitForEncounter(context, fixture, baselineSequence, WorldInterfaceProtocol.Stage.PHASE_3,
				WorldInterfaceProtocol.Form.WORLD_INTERFACE);
		double baseline = context.computeOnClient(client -> WorldInterfaceClientState.snapshot()
				.collapseProgress(client.level.getGameTime()));

		long cutSequence = singleplayer.getServer().computeOnServer(server -> sendEncounter(server, fixture,
				WorldInterfaceProtocol.Stage.PHASE_3, WorldInterfaceProtocol.Form.WORLD_INTERFACE, 0.28F,
				WorldInterfaceProtocol.ANCHOR_MASK & ~1, quarterTicks, true,
				WorldInterfaceProtocol.GatewayState.PURPLE, WorldInterfaceProtocol.Outcome.NONE, 0.0F));
		waitForEncounter(context, fixture, cutSequence, WorldInterfaceProtocol.Stage.PHASE_3,
				WorldInterfaceProtocol.Form.WORLD_INTERFACE);
		double afterCut = context.computeOnClient(client -> WorldInterfaceClientState.snapshot()
				.collapseProgress(client.level.getGameTime()));
		require(Math.abs(baseline - 0.25D) < 0.000_001D,
				"The baseline collapse projection was not 25%");
		require(Math.abs(afterCut - baseline) < 0.000_001D,
				"Destroying an authoritative anchor moved the collapse clock");
		context.runOnClient(client -> require(Integer.bitCount(WorldInterfaceClientState.snapshot()
				.encounter().anchorAliveMask()) == 9, "The client did not project exactly nine live anchors"));
		context.waitTicks(4);
		context.takeScreenshot("world-interface-collapse-anchor-cut");
	}

	private static void captureAltar(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture) {
		long sequence = singleplayer.getServer().computeOnServer(server -> {
			ServerPlayer player = firstPlayer(server);
			List<UUID> roster = List.of(player.getUUID(), UUID.nameUUIDFromBytes("altar-two".getBytes(StandardCharsets.UTF_8)),
					UUID.nameUUIDFromBytes("altar-three".getBytes(StandardCharsets.UTF_8)));
			List<String> names = List.of(player.getGameProfile().name(), "Echo-02", "Echo-03");
			long next = fixture.nextSequence();
			ServerPlayNetworking.send(player, new AltarSnapshotS2C(WorldInterfaceProtocol.VERSION,
					fixture.encounterId(), next, 42L, WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(),
					fixture.center(), roster, names, 0b101, true,
					WorldInterfaceProtocol.AltarStatus.READY.wireId(),
					WorldInterfacePolicy.RITUAL_WINDOW_TICKS, false));
			return next;
		});
		context.waitForScreen(ResonanceAltarScreen.class);
		context.runOnClient(client -> {
			WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
			require(projection.lastSequence() == sequence && projection.altar() != null,
					"The altar packet did not enter the shared strict sequence");
			require(projection.altar().deposited(0) && !projection.altar().deposited(1)
						&& projection.altar().deposited(2), "The altar deposited mask changed on the client");
		});
		context.takeScreenshot("world-interface-resonance-altar");

		long closeSequence = singleplayer.getServer().computeOnServer(server -> sendEncounter(server, fixture,
				WorldInterfaceProtocol.Stage.SUMMONING, WorldInterfaceProtocol.Form.LISTENING_EMBRYO, 1.0F,
				WorldInterfaceProtocol.ANCHOR_MASK, 0L, true,
				WorldInterfaceProtocol.GatewayState.PURPLE, WorldInterfaceProtocol.Outcome.NONE, 0.0F));
		waitForEncounter(context, fixture, closeSequence, WorldInterfaceProtocol.Stage.SUMMONING,
				WorldInterfaceProtocol.Form.LISTENING_EMBRYO);
		context.waitFor(client -> !(client.screen instanceof ResonanceAltarScreen), 100);
	}

	private static void capturePoems(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			Fixture fixture) {
		for (int destroyed : List.of(0, 5, WorldInterfaceProtocol.MAX_ANCHORS)) {
			long successSequence = singleplayer.getServer().computeOnServer(server -> sendPoem(server, fixture,
					WorldInterfaceProtocol.Outcome.SUCCESS, destroyed));
			waitForPoem(context, fixture, successSequence, WorldInterfaceProtocol.Outcome.SUCCESS, destroyed);
			openVanillaPoem(context);
			String branch = destroyed == 0 ? "preserved"
					: destroyed == WorldInterfaceProtocol.MAX_ANCHORS ? "all-destroyed" : "partial";
			context.takeScreenshot("world-interface-poem-success-" + branch);
		}

		long failureSequence = singleplayer.getServer().computeOnServer(server -> sendPoem(server, fixture,
				WorldInterfaceProtocol.Outcome.FAILURE, 0));
		waitForPoem(context, fixture, failureSequence, WorldInterfaceProtocol.Outcome.FAILURE, 0);
		openVanillaPoem(context);
		context.takeScreenshot("world-interface-poem-failure");
	}

	private static void openVanillaPoem(ClientGameTestContext context) {
		context.runOnClient(client -> {
			WinScreen screen = new WinScreen(true, () -> { });
			client.setScreen(screen);
		});
		context.waitForScreen(WinScreen.class);
		positionVanillaPoem(context);
	}

	private static void captureRealPortalRoundTrip(ClientGameTestContext context,
			TestSingleplayerContext singleplayer) {
		context.runOnClient(client -> {
			client.setScreen(null);
			WorldInterfaceClientState.clearSession();
			WorldInterfaceVanillaPoemClient.clearPending();
		});
		context.waitTicks(2);

		LiveExit liveExit = singleplayer.getServer().computeOnServer(WorldInterfaceClientGameTest::prepareLiveExit);
		context.waitForScreen(WinScreen.class);
		positionVanillaPoem(context);
		context.takeScreenshot("world-interface-poem-real-exit-portal");

		singleplayer.getServer().computeOnServer(server -> {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			ServerPlayer player = firstPlayer(server);
			WorldInterfaceState.PoemLedgerEntry poem = snapshot.poemLedger().get(liveExit.playerId());
			require(snapshot.encounterId().filter(liveExit.encounterId()::equals).isPresent(),
					"The physical exit portal changed the active encounter");
			require(snapshot.stage() == WorldInterfaceStage.PORTAL_OPEN,
					"The encounter completed before the real End poem was acknowledged");
			require(poem != null && poem.started() && !poem.acked(),
					"The physical exit portal did not persist an unacknowledged poem");
			require(player.wonGame && player.seenCredits,
					"The physical exit portal did not enter vanilla ServerPlayer.showEndCredits()");
			require(player.getRespawnConfig() == null,
					"The temporary End respawn remained active during vanilla credits");
			require(requireEnd(server).getBlockState(liveExit.position()).is(ModBlocks.WORLD_INTERFACE_EXIT_PORTAL),
					"The tested block was not the World Interface exit portal");
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					if (!AltarShape.isExitFrame(x, z)) continue;
					BlockPos frame = liveExit.position().offset(x, 0, z);
					require(requireEnd(server).getBlockState(frame).equals(AltarShape.exitFrameState()),
							"The exit portal was missing its same-height altar frame at " + frame);
					require(!requireEnd(server).getBlockState(frame.below()).isAir(),
							"The exit portal frame was floating at " + frame);
				}
			}
			return true;
		});

		context.runOnClient(client -> {
			require(client.screen instanceof WinScreen,
					"The physical portal did not open the real vanilla WinScreen");
			require(DimensionViewDistanceController.isLocked()
					&& client.options.getEffectiveRenderDistance() == DimensionViewDistancePolicy.END_CHUNKS,
					"View distance unlocked before the successful poem returned to the Overworld");
			client.screen.onClose();
		});
		context.waitFor(client -> client.level != null && client.level.dimension() == Level.OVERWORLD
				&& !(client.screen instanceof WinScreen), 400);
		context.waitTicks(10);
		assertUnlockedRenderDistance(context, Level.OVERWORLD);

		singleplayer.getServer().computeOnServer(server -> {
			WorldInterfaceState.Snapshot snapshot = WorldInterfaceState.snapshot(server);
			ServerPlayer player = firstPlayer(server);
			WorldInterfaceState.PoemLedgerEntry poem = snapshot.poemLedger().get(liveExit.playerId());
			WorldInterfaceState.RespawnLedgerEntry respawn = snapshot.respawnLedger().get(liveExit.playerId());
			require(snapshot.stage() == WorldInterfaceStage.COMPLETE,
					"The real vanilla poem/respawn round trip did not complete the encounter");
			require(poem != null && poem.acked() && poem.metaComplete(),
					"The real vanilla poem skip was not acknowledged durably");
			require(respawn != null && respawn.restored(),
					"The player's original respawn configuration was not marked restored");
			require(player.level().dimension() == Level.OVERWORLD,
					"Vanilla PERFORM_RESPAWN did not return the player to the Overworld");
			require(player.getRespawnConfig() == null,
					"A player with no original respawn point did not regain that exact configuration");
			return true;
		});

		singleplayer.getServer().runOnServer(server -> teleportForViewDistance(server, Level.NETHER));
		assertUnlockedRenderDistance(context, Level.NETHER);
		singleplayer.getServer().runOnServer(server -> teleportForViewDistance(server, Level.OVERWORLD));
		assertUnlockedRenderDistance(context, Level.OVERWORLD);
	}

	private static void positionVanillaPoem(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (!(client.screen instanceof WinScreen screen)) {
				throw new AssertionError("The real vanilla End poem screen was not open");
			}
			try {
				var scroll = WinScreen.class.getDeclaredField("scroll");
				scroll.setAccessible(true);
				scroll.setFloat(screen, client.getWindow().getGuiScaledHeight() + 20.0F);
			} catch (ReflectiveOperationException exception) {
				throw new AssertionError("Could not position the vanilla End poem for visual evidence", exception);
			}
		});
		context.waitTicks(2);
	}

	private static LiveExit prepareLiveExit(MinecraftServer server) {
		ServerLevel end = requireEnd(server);
		ServerPlayer player = firstPlayer(server);
		EndBossArenaService.PreparedArena arena = EndBossArenaService.prepare(end);
		UUID encounterId = UUID.randomUUID();
		WorldInterfaceState.Snapshot snapshot = requireApplied(WorldInterfaceState.initialize(server,
				encounterId, stateLayout(arena), 0x56414E494C4C41L), "initialize live exit");
		snapshot = requireApplied(WorldInterfaceState.transition(server, encounterId, snapshot.revision(),
				WorldInterfaceStage.ARENA_READY, WorldInterfaceStage.WAITING_TERMINALS),
				"prepare live exit ritual");

		String terminalId = UUID.randomUUID().toString();
		CompoundTag terminal = new CompoundTag();
		terminal.putString(TerminalData.OWNER_ID, player.getUUID().toString());
		terminal.putString(TerminalData.TERMINAL_ID, terminalId);
		terminal.putInt(TerminalData.COPY_GENERATION, 0);
		WorldInterfaceState.TerminalTransaction transaction = new WorldInterfaceState.TerminalTransaction(
				player.getUUID(), terminalId, 0, WorldInterfaceState.TerminalTransactionState.REMOVED,
				end.getGameTime(), terminal);
		WorldInterfaceState.RespawnLedgerEntry respawn = new WorldInterfaceState.RespawnLedgerEntry(
				player.getUUID(), false, "", BlockPos.ZERO, 0.0F, 0.0F, false, false);
		snapshot = requireApplied(WorldInterfaceState.mutate(server, encounterId, snapshot.revision(), state -> {
			state.joinRoster(player.getUUID());
			state.putTerminalTransaction(transaction);
			state.putRespawn(respawn);
			state.commitSacrifice(600.0D);
		}), "commit live exit participant");

		for (WorldInterfaceStage next : List.of(WorldInterfaceStage.PHASE_1, WorldInterfaceStage.PHASE_2,
				WorldInterfaceStage.PHASE_3, WorldInterfaceStage.SUCCESS_RESOLUTION)) {
			WorldInterfaceStage current = snapshot.stage();
			snapshot = requireApplied(WorldInterfaceState.transition(server, encounterId, snapshot.revision(),
					current, next), "advance live exit to " + next.serializedName());
		}
		// The state ledger names the former altar core; the actual exit replaces the three-by-three
		// platform directly below it. The production shape owns that offset so this fixture cannot
		// drift back to the short-lived raised-terrace layout.
		BlockPos exit = arena.altar();
		BlockPos portal = AltarShape.exitPortalCenter(exit);
		snapshot = requireApplied(WorldInterfaceState.mutate(server, encounterId, snapshot.revision(), state -> {
			state.setExit(exit, true);
			state.transitionTo(WorldInterfaceStage.PORTAL_OPEN);
		}), "open live exit portal");

		player.setRespawnPosition(new ServerPlayer.RespawnConfig(
				LevelData.RespawnData.of(Level.END, arena.safeSpawn(), 180.0F, 0.0F), true), false);
		player.seenCredits = false;
		player.wonGame = false;
		int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				end.setBlock(portal.offset(x, 0, z), ModBlocks.WORLD_INTERFACE_EXIT_PORTAL.defaultBlockState(), flags);
			}
		}
		for (int x = -2; x <= 2; x++) {
			for (int z = -2; z <= 2; z++) {
				if (AltarShape.isExitFrame(x, z)) {
					end.setBlock(portal.offset(x, 0, z), AltarShape.exitFrameState(), flags);
				}
			}
		}
		player.teleportTo(end, portal.getX() + 0.5D, portal.getY() + 0.1D, portal.getZ() + 0.5D,
				Set.of(), player.getYRot(), player.getXRot(), true);
		return new LiveExit(encounterId, player.getUUID(), portal);
	}

	private static Fixture createFixture(MinecraftServer server) {
		ServerLevel end = requireEnd(server);
		for (var dragon : List.copyOf(end.getDragons())) dragon.discard();
		EndBossArenaService.suppressVanillaFight(end);
		ServerPlayer player = firstPlayer(server);
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		player.setInvulnerable(true);
		player.setNoGravity(false);
		EndBossArenaService.PreparedArena arena = EndBossArenaService.prepare(end);
		BlockPos center = arena.center();
		List<BlockPos> gateways = arena.gatewayCorePositions();
		List<BlockPos> anchors = arena.anchors().stream()
				.map(EndBossArenaService.AnchorSlot::position).toList();
		BlockPos camera = surfaceAir(end, center.getX(), center.getZ() + 38);

		UUID encounterId = UUID.randomUUID();
		WorldInterfaceEntity body = ModEntities.WORLD_INTERFACE.create(end, EntitySpawnReason.EVENT);
		if (body == null) throw new AssertionError("World Interface visual fixture creation failed");
		body.bindEncounter(encounterId);
		body.setForm(WorldInterfaceEntity.FORM_LISTENING);
		body.setNoAi(true);
		body.setInvulnerable(true);
		body.setSilent(true);
		body.setPersistenceRequired();
		body.snapTo(center.getX() + 0.5D, center.getY() + 18.0D,
				center.getZ() + 0.5D, 0.0F, 0.0F);
		if (!end.addFreshEntity(body)) throw new AssertionError("World Interface visual fixture spawn failed");

		player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
		player.getInventory().setItem(4, new ItemStack(Items.BOW));
		player.getInventory().setItem(8, new ItemStack(ModItems.OLD_TERMINAL));
		player.getInventory().setSelectedSlot(0);
		player.inventoryMenu.broadcastChanges();
		player.teleportTo(end, camera.getX() + 0.5D, camera.getY(), camera.getZ() + 0.5D,
				Set.of(), 180.0F, -24.0F, true);
		return new Fixture(center, body.getUUID(), encounterId, List.copyOf(gateways), anchors,
				new AtomicLong());
	}

	private static BlockPos surfaceAir(ServerLevel level, int x, int z) {
		level.getChunkAt(new BlockPos(x, 64, z));
		return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
	}

	private static long sendEncounter(MinecraftServer server, Fixture fixture,
			WorldInterfaceProtocol.Stage stage, WorldInterfaceProtocol.Form form, float healthRatio,
			int anchorAliveMask, long elapsedTicks, boolean timerPaused,
			WorldInterfaceProtocol.GatewayState gatewayState, WorldInterfaceProtocol.Outcome outcome,
			float failureProgress) {
		ServerLevel end = requireEnd(server);
		long sequence = fixture.nextSequence();
		ServerPlayNetworking.send(firstPlayer(server), new WorldInterfaceSnapshotS2C(
				WorldInterfaceProtocol.VERSION, fixture.encounterId(), sequence, stage.wireId(), form.wireId(),
				fixture.bodyId(), fixture.center(), 600.0F, 600.0F * healthRatio, anchorAliveMask,
				elapsedTicks, timerPaused, Math.max(0L, end.getGameTime()), gatewayState.wireId(),
				fixture.gateways(), fixture.anchors(), outcome.wireId(), failureProgress));
		return sequence;
	}

	private static long sendAction(MinecraftServer server, Fixture fixture,
			WorldInterfaceProtocol.BossAction action, int duration) {
		ServerLevel end = requireEnd(server);
		ServerPlayer player = firstPlayer(server);
		WorldInterfaceEntity body = body(end, fixture.bodyId());
		long startTick = Math.max(0L, end.getGameTime());
		body.showAction(action.wireId(), startTick, duration);
		long sequence = fixture.nextSequence();
		ServerPlayNetworking.send(player, new BossActionS2C(fixture.encounterId(), sequence,
				action.wireId(), startTick, duration, List.of(player.getUUID()),
				0x574F524C44494E54L ^ action.wireId(), 0));
		return sequence;
	}

	private static long sendPoem(MinecraftServer server, Fixture fixture,
			WorldInterfaceProtocol.Outcome outcome, int destroyedAnchors) {
		long sequence = fixture.nextSequence();
		ServerPlayNetworking.send(firstPlayer(server), new PoemStartS2C(
				fixture.encounterId(), sequence, outcome.wireId(), "", destroyedAnchors));
		return sequence;
	}

	private static void waitForEncounter(ClientGameTestContext context, Fixture fixture, long sequence,
			WorldInterfaceProtocol.Stage stage, WorldInterfaceProtocol.Form form) {
		context.waitFor(client -> {
			WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
			return fixture.encounterId().equals(projection.encounterId())
					&& projection.lastSequence() >= sequence && projection.encounter() != null
					&& projection.encounter().sequence() == sequence
					&& projection.encounter().stage() == stage && projection.encounter().form() == form;
		}, 160);
	}

	private static void waitForAction(ClientGameTestContext context, Fixture fixture, long sequence,
			WorldInterfaceProtocol.BossAction action) {
		context.waitFor(client -> {
			WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
			return fixture.encounterId().equals(projection.encounterId())
					&& projection.lastSequence() >= sequence && projection.action() != null
					&& projection.action().sequence() == sequence && projection.action().action() == action;
		}, 160);
	}

	private static void waitForPoem(ClientGameTestContext context, Fixture fixture, long sequence,
			WorldInterfaceProtocol.Outcome outcome, int destroyedAnchors) {
		context.waitFor(client -> {
			WorldInterfaceClientState.Projection projection = WorldInterfaceClientState.snapshot();
			return fixture.encounterId().equals(projection.encounterId())
					&& projection.lastSequence() >= sequence && projection.poem() != null
					&& projection.poem().sequence() == sequence && projection.poem().outcome() == outcome
					&& projection.poem().destroyedAnchors() == destroyedAnchors;
		}, 160);
	}

	private static void assertLaserRecoveryGeometry() {
		try {
			var renderer = com.xm.thefourthfrequency.client_render.WorldInterfaceBeamBatchRenderer.class;
			var method = renderer.getDeclaredMethod("extractLaserBarrel", WorldInterfaceEntity.class,
					net.minecraft.world.phys.Vec3.class, net.minecraft.world.phys.Vec3.class,
					BossActionS2C.class, float.class, double.class, float.class,
					int.class, int.class, int.class, List.class, List.class);
			method.setAccessible(true);
			var beams = new ArrayList<>();
			var halos = new ArrayList<>();
			var origin = new net.minecraft.world.phys.Vec3(0, 15, 0);
			var impact = new net.minecraft.world.phys.Vec3(20, 0, 0);
			float cutoff = com.xm.thefourthfrequency.entity.WorldInterfaceAttackMotion.LASER_END_TICK;
			method.invoke(null, null, origin, impact, null, cutoff - .01F, 2.0D, 1.0F,
					130, 60, 220, beams, halos);
			if (beams.isEmpty()) throw new AssertionError("Live damage window lost its visible shaft");
			beams.clear(); halos.clear();
			method.invoke(null, null, origin, impact, null, cutoff, 2.0D, 1.0F,
					130, 60, 220, beams, halos);
			if (!beams.isEmpty() || halos.isEmpty())
				throw new AssertionError("Recovery must retain ground heat without a live mouth shaft");
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("Could not exercise the actual beam extractor", exception);
		}
	}

	private static void assertWireContract() {
		// Not a contiguous range. Wire id 3 belonged to the retired grab-slam and is deliberately
		// left unassigned so an old save's recorded action can never be silently decoded as a
		// different one - see the comment on BossAction. This used to assert wireId == index + 1,
		// which stopped describing the protocol the moment that id was vacated.
		int previous = 0;
		for (WorldInterfaceProtocol.BossAction action : COMBAT_ACTIONS) {
			require(action.wireId() > previous && action.wireId() <= 9,
					"Combat action wire IDs must stay unique, ascending and inside 1..9");
			require(action.wireId() != 3, "Wire id 3 is retired and must never be reassigned");
			require(WorldInterfaceProtocol.BossAction.fromWireId(action.wireId()) == action,
					"Combat action wire decoding changed for " + action);
			previous = action.wireId();
		}
		require(WorldInterfaceProtocol.BossAction.MORPH_TO_SECOND.wireId() == 11
				&& WorldInterfaceProtocol.BossAction.MORPH_TO_THIRD.wireId() == 12,
				"The two morph wire IDs changed");
		require(WorldInterfaceProtocol.Form.LISTENING_EMBRYO.wireId() == 1
				&& WorldInterfaceProtocol.Form.FREQUENCY_DEVOURER.wireId() == 2
				&& WorldInterfaceProtocol.Form.WORLD_INTERFACE.wireId() == 3,
				"The three World Interface form wire IDs changed");
		require(WorldInterfaceProtocol.GatewayState.PURPLE.wireId() == 1
				&& WorldInterfaceProtocol.GatewayState.GOLD.wireId() == 2
				&& WorldInterfaceProtocol.GatewayState.RED.wireId() == 3,
				"The three active gateway wire IDs changed");
	}

	private static void assertStrictClientSequence(Fixture fixture) {
		UUID first = UUID.nameUUIDFromBytes("strict-first".getBytes(StandardCharsets.UTF_8));
		UUID second = UUID.nameUUIDFromBytes("strict-second".getBytes(StandardCharsets.UTF_8));
		WorldInterfaceClientState.clearSession();
		require(WorldInterfaceClientState.accept(contractSnapshot(first, 10,
				WorldInterfaceProtocol.Stage.PHASE_1)), "The first encounter payload was rejected");
		require(WorldInterfaceClientState.accept(new BossActionS2C(first, 11,
				WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(), 0L, 40, List.of(), 1L, 0)),
				"A newer cross-type action payload was rejected");
		require(!WorldInterfaceClientState.accept(new AltarSnapshotS2C(WorldInterfaceProtocol.VERSION,
				first, 10, 1L, WorldInterfaceProtocol.Stage.WAITING_TERMINALS.wireId(), fixture.center(),
				List.of(), List.of(), 0, false, WorldInterfaceProtocol.AltarStatus.WAITING.wireId(), 0, false)),
				"A stale altar payload crossed the global sequence");
		require(!WorldInterfaceClientState.accept(new PoemStartS2C(first, 11,
				WorldInterfaceProtocol.Outcome.SUCCESS.wireId())), "A duplicate poem sequence was accepted");
		require(!WorldInterfaceClientState.accept(contractSnapshot(second, 12,
				WorldInterfaceProtocol.Stage.PHASE_1)), "A foreign encounter replaced a live encounter");
		require(WorldInterfaceClientState.accept(contractSnapshot(first, 12,
				WorldInterfaceProtocol.Stage.COMPLETE)), "The terminal encounter snapshot was rejected");
		require(WorldInterfaceClientState.accept(contractSnapshot(second, 0,
				WorldInterfaceProtocol.Stage.PHASE_1)), "A new encounter did not replace a terminal encounter");
		require(second.equals(WorldInterfaceClientState.snapshot().encounterId())
				&& WorldInterfaceClientState.snapshot().lastSequence() == 0,
				"The replacement encounter sequence was not reset independently");
		WorldInterfaceClientState.clearSession();
	}

	private static WorldInterfaceSnapshotS2C contractSnapshot(UUID encounterId, long sequence,
			WorldInterfaceProtocol.Stage stage) {
		boolean complete = stage == WorldInterfaceProtocol.Stage.COMPLETE;
		return new WorldInterfaceSnapshotS2C(WorldInterfaceProtocol.VERSION, encounterId, sequence,
				stage.wireId(), (complete ? WorldInterfaceProtocol.Form.NONE
						: WorldInterfaceProtocol.Form.LISTENING_EMBRYO).wireId(), NIL_UUID, BlockPos.ZERO,
				complete ? 0.0F : 600.0F, complete ? 0.0F : 600.0F,
				WorldInterfaceProtocol.ANCHOR_MASK, 0L, true, 0L,
				WorldInterfaceProtocol.GatewayState.DORMANT.wireId(), List.of(), anchorPositions(),
				(complete ? WorldInterfaceProtocol.Outcome.SUCCESS
						: WorldInterfaceProtocol.Outcome.NONE).wireId(), 0.0F);
	}

	private static void assertLocalRecoveryContract() {
		Path lockPath = FailureMenuLockState.lockPathForTesting();
		require(lockPath.isAbsolute() && lockPath.getFileName().toString().equals("failure-menu.lock"),
				"The failure lock is not an absolute mod-owned file");
		require(lockPath.getParent() != null
				&& lockPath.getParent().getFileName().toString().equals("thefourthfrequency-ending"),
				"The failure lock escaped its mod-owned directory");
		List<String> original = List.of("vanilla", "file/user-pack", "programmer_art");
		List<String> restored = WorldInterfaceResourcePackLease.restoredSelection(original,
				List.of("golden-days", "programmer_art"),
				List.of("vanilla", "file/user-pack", "programmer_art", "golden-days"));
		require(restored.equals(original),
				"Resource restoration did not preserve the exact pre-presentation order");
		require(!restored.contains("golden-days"),
				"Resource restoration retained a pack owned only by the presentation lease");
	}

	private static void assertEndingSourceContracts() {
		try {
			String ending = source("src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceEndingClient.java");
			String lock = source("src/client/java/com/xm/thefourthfrequency/client_ui/FailureMenuLockState.java");
			String packs = source("src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceResourcePackLease.java");
			String poem = source("src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceVanillaPoemClient.java");
			String winScreenMixin = source("src/client/java/com/xm/thefourthfrequency/mixin/WinScreenPoemMixin.java");
			String exitPortal = source("src/main/java/com/xm/thefourthfrequency/content/WorldInterfaceExitPortalBlock.java");
			// The shutdown is queued rather than called inline - stop() tears down the window and the
			// render system, which cannot happen partway through the tick that asked for it - so this
			// looks for the queued form. The literal "client.stop()" it used to look for stopped
			// existing when that queueing went in.
			require(ending.contains("FailureMenuLockState.lock")
					&& ending.contains("WorldInterfaceResourcePackLease.restoreAsync")
					&& ending.contains("disconnectWithSavingScreen")
					&& ending.contains("client.execute(client::stop)"),
					"The failure lock, resource restoration, or normal shutdown wiring disappeared");
			require(!ending.contains("System.exit"), "The ending client introduced a hard process exit");
			require(lock.contains("StandardCopyOption.ATOMIC_MOVE")
					&& lock.contains("unlockAfterLocalRecovery"),
					"The durable failure lock lost atomic persistence or local recovery");
			require(packs.contains("originalOrder") && packs.contains("autoAddedIds")
					&& packs.contains("client.reloadResourcePacks()"),
					"The resource-pack ownership lease lost its ordered reload contract");
			require(poem.contains("end_success_") && poem.contains("end_failure_")
					&& poem.contains("vanillaFinish.run()"),
					"The outcome-specific vanilla poem binding or original completion callback disappeared");
			require(winScreenMixin.contains("@Mixin(WinScreen.class)")
					&& winScreenMixin.contains("wrapCreditsIO")
					&& winScreenMixin.contains("PoemCompletion.SKIPPED"),
					"The real WinScreen poem replacement or explicit skip acknowledgement disappeared");
			require(exitPortal.contains("implements Portal") && exitPortal.contains("showEndCredits()")
					&& exitPortal.contains("Blocks.END_PORTAL"),
					"The encounter exit stopped using the vanilla End credits and portal destination protocol");
		} catch (IOException exception) {
			throw new AssertionError("Could not read the World Interface client contracts", exception);
		}
	}

	private static String source(String relativePath) throws IOException {
		Path projectDirectory = Path.of(System.getProperty("thefourthfrequency.projectDir",
				System.getProperty("user.dir"))).toAbsolutePath().normalize();
		return Files.readString(projectDirectory.resolve(relativePath).normalize(), StandardCharsets.UTF_8);
	}

	private static WorldInterfaceProtocol.Form formForAction(WorldInterfaceProtocol.BossAction action) {
		return switch (action) {
			case LASER_SWEEP, ENERGY_ORB, SKY_LANCE ->
					WorldInterfaceProtocol.Form.LISTENING_EMBRYO;
			case WEAPON_CHARGE, GRAB_THROW, HOTBAR_PURGE ->
					WorldInterfaceProtocol.Form.FREQUENCY_DEVOURER;
			case TENDRIL_LASH, FORCED_EXPULSION -> WorldInterfaceProtocol.Form.WORLD_INTERFACE;
			default -> throw new AssertionError("Not one of the nine combat actions: " + action);
		};
	}

	private static ServerLevel requireEnd(MinecraftServer server) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) throw new AssertionError("The End World Interface fixture could not load");
		return end;
	}

	private static ServerPlayer firstPlayer(MinecraftServer server) {
		if (server.getPlayerList().getPlayers().isEmpty()) {
			throw new AssertionError("The World Interface client fixture has no player");
		}
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static WorldInterfaceEntity body(ServerLevel level, UUID id) {
		Entity entity = level.getEntityInAnyDimension(id);
		if (!(entity instanceof WorldInterfaceEntity body)) {
			throw new AssertionError("The World Interface visual fixture disappeared");
		}
		return body;
	}

	private static WorldInterfaceState.Snapshot requireApplied(WorldInterfaceState.MutationResult result,
			String operation) {
		if (!result.applied()) {
			throw new AssertionError("World-interface state operation failed (" + operation + "): "
					+ result.reason());
		}
		return result.snapshot();
	}

	private static WorldInterfaceState.ArenaLayout stateLayout(EndBossArenaService.PreparedArena arena) {
		List<WorldInterfaceState.Gate> gates = new ArrayList<>(20);
		for (int index = 0; index < arena.gatewayCorePositions().size(); index++) {
			gates.add(new WorldInterfaceState.Gate(index, arena.gatewayCorePositions().get(index),
					WorldInterfaceGatewayState.DORMANT));
		}
		List<WorldInterfaceState.Anchor> anchors = arena.anchors().stream()
				.map(anchor -> new WorldInterfaceState.Anchor(anchor.index(), anchor.position(),
						Optional.of(anchor.anchorEntityUuid()), false)).toList();
		return new WorldInterfaceState.ArenaLayout(1, Level.END.identifier().toString(), arena.center(),
				arena.altar(), arena.safeSpawn(), gates, anchors);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static List<BlockPos> anchorPositions() {
		return java.util.stream.IntStream.range(0, WorldInterfaceProtocol.MAX_ANCHORS)
				.mapToObj(index -> new BlockPos(index * 12, 80, index * 7)).toList();
	}

	private record Fixture(BlockPos center, UUID bodyId, UUID encounterId, List<BlockPos> gateways,
			List<BlockPos> anchors, AtomicLong sequences) {
		private Fixture {
			gateways = List.copyOf(gateways);
			anchors = List.copyOf(anchors);
		}

		long nextSequence() {
			return sequences.incrementAndGet();
		}
	}

	private record LiveExit(UUID encounterId, UUID playerId, BlockPos position) {
	}

	private record FramebufferSize(int width, int height) {
	}

}
