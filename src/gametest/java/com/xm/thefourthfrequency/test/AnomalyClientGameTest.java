package com.xm.thefourthfrequency.test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.xm.thefourthfrequency.client_ui.AnomalyPresentationController;
import com.xm.thefourthfrequency.correction.ViewpointOrientationPolicy;
import com.xm.thefourthfrequency.client_ui.AnomalyTestSnapshot;
import com.xm.thefourthfrequency.client_ui.ChannelOverrideScreen;
import com.xm.thefourthfrequency.client_ui.FirstRunNoticeController;
import com.xm.thefourthfrequency.client_ui.FirstRunNoticeScreen;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import com.xm.thefourthfrequency.meta_api.MetaController;
import com.xm.thefourthfrequency.meta_api.MockMetaPlatformAdapter;
import com.xm.thefourthfrequency.terminal.AnomalyGameTestBridge;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.AnomalyConditions;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Isolated, continue-on-failure client runner for every catalogued anomaly. */
public final class AnomalyClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientGameTestSelection selection = ClientGameTestSelection.current();
		if (selection.runsMetaSmoke()) {
			runWindowsMetaSmoke(context);
			return;
		}
		if (!selection.runsAnomalies()) return;

		AnomalyClientScenario.assertCatalogCoverage();
		List<AnomalyClientScenario> scenarios = selection.anomalyId()
				.map(id -> List.of(AnomalyClientScenario.require(id)))
				.orElseGet(AnomalyClientScenario::definitions);
		// Counted from the catalog rather than written down. As a literal this said sixteen while the
		// catalog had grown to nineteen, so the full suite refused to start over a number that only
		// described an older version of the mod - and assertCatalogCoverage above already proves the
		// scenario list and the catalog agree.
		int expected = AnomalyClientScenario.definitions().size();
		if (selection.anomalyId().isEmpty() && scenarios.size() != expected)
			throw new AssertionError("Full anomaly suite must cover every catalog scenario: "
					+ scenarios.size() + " of " + expected);

		acknowledgeFirstRunNoticeIfNeeded(context);
		context.waitForScreen(TitleScreen.class);
		MetaController.useAdapterForTesting(new MockMetaPlatformAdapter());
		resetReportDirectory();
		List<Result> results = new ArrayList<>();
		for (AnomalyClientScenario scenario : scenarios) results.add(runScenario(context, scenario));
		writeSummary(results, selection);
		List<Result> failed = results.stream().filter(result -> !result.passed()).toList();
		if (!failed.isEmpty()) {
			String reasons = failed.stream().map(result -> result.id() + ": " + result.failureReason())
					.collect(java.util.stream.Collectors.joining("; "));
			throw new AssertionError("Anomaly client suite failed after complete cleanup/reporting: " + reasons);
		}
	}

	private static Result runScenario(ClientGameTestContext context, AnomalyClientScenario scenario) {
		long started = System.nanoTime();
		String screenshotBase = "anomaly-%02d-%s-peak".formatted(scenario.catalogNumber(), scenario.id());
		String screenshotName = screenshotBase + ".png";
		String failureReason = "";
		boolean passed = false;
		TestSingleplayerContext singleplayer = null;
		FixtureState fixture = null;
		try {
			singleplayer = context.worldBuilder().create();
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(30);
			TestSingleplayerContext activeWorld = singleplayer;
			fixture = singleplayer.getServer().computeOnServer(server -> prepareFixture(server, scenario));
			// Let light propagation, chunk rebuilds, entity tracking, and inventory synchronization settle.
			context.waitTicks(5);
			FixtureState preparedFixture = fixture;
			singleplayer.getServer().runOnServer(server -> refreshStableBaseline(server, scenario, preparedFixture));
			int traceCountBefore = context.computeOnClient(client -> AnomalyPresentationController.testSnapshot().persistentTraceCount());

			if (scenario.fixture() == AnomalyClientScenario.Fixture.ACTION_HISTORY) recordActionHistory(context);
			int logBefore = fixture.anomalyLogCountBefore;
			boolean startedAnomaly = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				if (AnomalyConditions.prepare(player, AnomalyCatalog.require(scenario.id()), scenario.seed()) == null)
					throw new AssertionError("Formal precondition rejected prepared fixture " + scenario.fixture());
				boolean first = AnomalyGameTestBridge.start(player, scenario.id(), scenario.seed(),
						scenario.timeline().acceleratedTicks());
				boolean duplicate = AnomalyGameTestBridge.start(player, scenario.id(), scenario.seed() + 1,
						scenario.timeline().acceleratedTicks());
				if (duplicate) throw new AssertionError("A second active anomaly instance was accepted");
				return first;
			});
			if (!startedAnomaly) throw new AssertionError("Formal anomaly precondition/fixture did not start");

			context.waitTicks(2);
			assertNotLogged(activeWorld, logBefore, scenario.id());
			if (scenario.id().equals("channel_override")) {
				context.waitTicks(Math.max(2, scenario.timeline().peakTick() / 2));
				context.runOnClient(client -> {
					if (!(client.screen instanceof ChannelOverrideScreen))
						throw new AssertionError("Channel override did not open its formal screen");
					client.screen.onClose();
				});
				context.waitTicks(2);
				context.waitTicks(Math.max(0, scenario.timeline().peakTick() - 4 - scenario.timeline().peakTick() / 2));
			} else context.waitTicks(Math.max(0, scenario.timeline().peakTick() - 2));

			prepareVisualEvidence(context, scenario, fixture);
			AnomalyTestSnapshot peak = context.computeOnClient(client -> AnomalyPresentationController.testSnapshot());
			assertClientPeak(context, scenario, peak, fixture);
			assertServerPeak(singleplayer, scenario, fixture, logBefore);
			Path sourceScreenshot = context.takeScreenshot(screenshotBase);
			screenshotName = copyScreenshot(sourceScreenshot, screenshotName);
			restoreVisualEvidence(context, scenario, fixture);

			context.waitFor(client -> !AnomalyPresentationController.testSnapshot().active(),
					scenario.timeline().acceleratedTicks() + 80);
			context.waitTicks(4);
			assertClientCleanup(context, scenario, traceCountBefore);
			assertServerCleanup(singleplayer, scenario, fixture, logBefore);
			passed = true;
		} catch (Throwable failure) {
			failureReason = conciseFailure(failure);
			try {
				Path sourceScreenshot = context.takeScreenshot(screenshotBase);
				screenshotName = copyScreenshot(sourceScreenshot, screenshotName);
			} catch (Throwable ignored) { }
		} finally {
			try { context.runOnClient(client -> client.options.keyUp.setDown(false)); }
			catch (Throwable ignored) { }
			if (singleplayer != null) {
				try { singleplayer.getServer().runOnServer(server -> AnomalyGameTestBridge.cleanup(player(server))); }
				catch (Throwable ignored) { }
				try { context.runOnClient(client -> AnomalyPresentationController.restore(client, false,
						com.xm.thefourthfrequency.terminal.AnomalyCompletionStatus.INTERRUPTED)); }
				catch (Throwable ignored) { }
				try { singleplayer.close(); } catch (Throwable ignored) { }
			}
		}
		long millis = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
		return new Result(scenario.catalogNumber(), scenario.id(), scenario.tier(), scenario.seed(),
				scenario.timeline().acceleratedTicks(), millis, passed, screenshotName, failureReason);
	}

	private static void acknowledgeFirstRunNoticeIfNeeded(ClientGameTestContext context) {
		// Two pages now: the audio calibration, then the disclosure. This suite is not auditing
		// either of them - it only has to get past both to reach the title screen its fixtures need.
		context.waitFor(client -> FirstRunNoticeController.acknowledgedForTesting()
				|| client.screen instanceof FirstRunNoticeScreen notice
				&& notice.advanceAvailableForTesting(), 160);
		context.runOnClient(client -> {
			if (client.screen instanceof FirstRunNoticeScreen notice) notice.advanceForTesting();
		});
		context.waitFor(client -> FirstRunNoticeController.acknowledgedForTesting()
				|| client.screen instanceof FirstRunNoticeScreen notice
				&& notice.acknowledgementAvailableForTesting(), 160);
		context.runOnClient(client -> {
			if (client.screen instanceof FirstRunNoticeScreen notice) notice.acknowledgeForTesting();
		});
		context.waitFor(client -> FirstRunNoticeController.acknowledgedForTesting(), 100);
		context.runOnClient(client -> {
			if (!FirstRunNoticeController.acknowledgedForTesting())
				throw new AssertionError("First-run notice transition did not finish before anomaly fixture setup");
		});
	}

	private static FixtureState prepareFixture(MinecraftServer server, AnomalyClientScenario scenario) {
		ServerPlayer player = player(server);
		ServerLevel level = (ServerLevel) player.level();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putBoolean(TerminalData.ANOMALIES_SUSPENDED, true);
			tag.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, Long.MAX_VALUE);
		});
		AnomalyGameTestBridge.cleanup(player);
		FixtureState state = new FixtureState(player, AnomalyGameTestBridge.anomalyLogCount(player, scenario.id()));
		switch (scenario.fixture()) {
			case CAVE -> prepareCave(level, player, state);
			case LIGHTS -> {
				// The dropout is gated to night, so the fixture has to supply one.
				level.setDayTime(18_000L);
				// Clear of Relay Station Zero. The dropout refuses to touch blocks within six columns
				// of the station, and the test player stands on it - a light four blocks away was
				// inside that shield, so the precondition correctly found nothing to extinguish and
				// the scenario could never start. Still well inside the sixteen-block scan radius.
				// A torch rather than glowstone. The dropout leaves solid lights with no unlit relative
				// exactly where they are - punching a hole in a wall is not a light going out - so a
				// glowstone-only room now has nothing extinguishable in it and the precondition
				// correctly refuses to start. A fitting is what the effect is actually about.
				state.light = player.blockPosition().offset(12, 1, 0);
				level.setBlockAndUpdate(state.light.below(), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(state.light, Blocks.TORCH.defaultBlockState());
				state.rememberBlock(level, state.light);
			}
			case WALL -> {
				// Put the fractured face at eye height so a real attack ray can hit the affected block.
				state.wall = player.blockPosition().relative(player.getDirection()).above();
				level.setBlockAndUpdate(state.wall, Blocks.STONE.defaultBlockState());
				state.rememberBlock(level, state.wall);
			}
			case MOBS -> prepareMobs(level, player, state);
			case WATCHER_CONE -> prepareWatcherCone(level, player, state);
			case ACTION_HISTORY -> prepareActionHistory(level, player);
			case DOORS -> prepareDoors(level, player, data, state);
			case INVENTORY -> prepareInventory(player, state);
			case SAFE_PATH -> prepareSafePath(level, player, state);
			case WORLD_INVARIANTS -> prepareWorldInvariants(level, player, state);
			case CAMERA -> prepareCameraStage(level, player, state);
			case HORIZON -> prepareHorizonStage(level, player);
			case EMPTY, META_FALLBACK, CHANNEL -> { }
		}
		return state;
	}

	private static void prepareCave(ServerLevel level, ServerPlayer player, FixtureState state) {
		BlockPos origin = player.blockPosition();
		// The face that fractures, at eye height so a real attack ray can reach it. The cave shell
		// below stands two blocks out, which is one further than the anomaly's preflight scan looks,
		// so without this the merged phantom_echo has no surface to open and never starts.
		state.wall = origin.relative(player.getDirection()).above();
		level.setBlockAndUpdate(state.wall, Blocks.STONE.defaultBlockState());
		state.rememberBlock(level, state.wall);
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			level.setBlockAndUpdate(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(origin.offset(x, 2, z), Blocks.STONE.defaultBlockState());
			if (Math.abs(x) == 2 || Math.abs(z) == 2) {
				level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState());
				level.setBlockAndUpdate(origin.offset(x, 1, z), Blocks.STONE.defaultBlockState());
			}
		}
	}

	private static void prepareMobs(ServerLevel level, ServerPlayer player, FixtureState state) {
		Mob near = EntityType.COW.create(level, EntitySpawnReason.EVENT);
		// A second one in range, because the anomaly's precondition now asks for two. One animal
		// facing the player is what animals do; the effect is a group turning at once, and the
		// preflight refuses anything that could not read as one. The far sheep still guards the
		// other end of the rule - out of range means untouched - so the fixture states both halves.
		Mob second = EntityType.PIG.create(level, EntitySpawnReason.EVENT);
		Mob far = EntityType.SHEEP.create(level, EntitySpawnReason.EVENT);
		if (near == null || second == null || far == null) throw new AssertionError("Mob fixture creation failed");
		near.snapTo(player.getX() + 6, player.getY(), player.getZ(), 37.0F, 0.0F);
		second.snapTo(player.getX() - 6, player.getY(), player.getZ(), 37.0F, 0.0F);
		far.snapTo(player.getX() + 36, player.getY(), player.getZ(), 37.0F, 0.0F);
		near.setNoAi(true); second.setNoAi(true); far.setNoAi(true);
		near.setYHeadRot(37.0F); second.setYHeadRot(37.0F); far.setYHeadRot(37.0F);
		if (!level.addFreshEntity(near) || !level.addFreshEntity(second) || !level.addFreshEntity(far))
			throw new AssertionError("Mob fixture spawn failed");
		state.nearMob = near.getUUID(); state.farMob = far.getUUID(); state.farHeadRotation = far.getYHeadRot();
	}

	private static void prepareWatcherCone(ServerLevel level, ServerPlayer player, FixtureState state) {
		level.setDayTime(18_000L);
		BlockPos origin = player.blockPosition();
		for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, 2, z), Blocks.AIR.defaultBlockState(), 2);
		}
		// A watcher only takes a spot it can be seen against something. On a bare platform every
		// candidate had open air behind it, so the spawn found nowhere to stand and the scenario
		// could not start at all - the fixture was building the clearing but not its far wall.
		for (int x = -18; x <= 18; x++) for (int y = 0; y <= 3; y++) {
			level.setBlock(origin.offset(x, y, -18), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, y, 18), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(origin.offset(-18, y, x), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(origin.offset(18, y, x), Blocks.STONE.defaultBlockState(), 2);
		}
		state.serverWatcherCountBefore = watcherCount(level, player);
	}

	private static void prepareActionHistory(ServerLevel level, ServerPlayer player) {
		BlockPos origin = player.blockPosition().offset(32, 0, 0);
		for (int x = -4; x <= 4; x++) for (int z = -20; z <= 20; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			for (int y = 0; y <= 3; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
		}
		player.teleportTo(level, origin.getX() + 0.5D, origin.getY(), origin.getZ() - 8.5D,
				Set.of(), 0.0F, 0.0F, true);
		player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
		player.getInventory().setItem(1, new ItemStack(Items.BOW));
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
	}

	private static void prepareCameraStage(ServerLevel level, ServerPlayer player, FixtureState state) {
		BlockPos origin = player.blockPosition().offset(-32, 0, 0);
		for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			for (int y = 0; y <= 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
		}
		state.separationForwardYaw = 73.0F;
		// Well inside ViewpointOrientationPolicy.MAX_PITCH_DEGREES, so the separated view is expected
		// to inherit this angle untouched rather than have it clamped.
		state.separationForwardPitch = -31.0F;
		player.teleportTo(level, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
				Set.of(), state.separationForwardYaw, state.separationForwardPitch, true);
	}

	private static void prepareHorizonStage(ServerLevel level, ServerPlayer player) {
		BlockPos origin = player.blockPosition().offset(96, 0, 96);
		level.setDayTime(6_000L);
		for (int x = -18; x <= 18; x++) for (int z = -18; z <= 18; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			for (int y = 0; y <= 16; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
		}
		player.getInventory().clearContent();
		player.teleportTo(level, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
				Set.of(), 0.0F, -28.0F, true);
	}

	private static void prepareDoors(ServerLevel level, ServerPlayer player, FrequencyWorldData data, FixtureState state) {
		BlockPos station = data.stationPosition().orElseThrow(() -> new AssertionError("Door fixture requires station anchor"));
		BlockPos playerAnchor = station.offset(8, 0, 0);
		player.teleportTo(level, playerAnchor.getX() + 0.5D, playerAnchor.getY(), playerAnchor.getZ() + 0.5D,
				Set.of(), 0.0F, 0.0F, true);
		BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
		BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		for (int distance : new int[] { 20, 15, 10, 5 }) {
			BlockPos door = player.blockPosition().offset(distance, 0, 0);
			level.setBlockAndUpdate(door.below(), Blocks.STONE.defaultBlockState());
			level.setBlockAndUpdate(door, Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(door.above(), Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(door, lower); level.setBlockAndUpdate(door.above(), upper);
			state.ordinaryDoors.add(door);
		}
		state.protectedDoor = station.offset(2, 0, 0);
		level.setBlockAndUpdate(state.protectedDoor.below(), Blocks.STONE.defaultBlockState());
		level.setBlockAndUpdate(state.protectedDoor, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(state.protectedDoor.above(), Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(state.protectedDoor, lower); level.setBlockAndUpdate(state.protectedDoor.above(), upper);
	}

	private static void prepareInventory(ServerPlayer player, FixtureState state) {
		player.getInventory().clearContent();
		int[] slots = {0, 2, 5, 8, 9, 11, 14, 17, 18, 20, 23, 26, 27, 29, 32, 35};
		List<ItemStack> stacks = List.of(
				new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.BREAD, 17),
				new ItemStack(Items.TORCH, 32), new ItemStack(Items.COMPASS),
				new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COOKED_BEEF, 9),
				new ItemStack(Items.WATER_BUCKET), new ItemStack(Items.COBBLESTONE, 48),
				new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 24),
				new ItemStack(Items.ENDER_PEARL, 5), new ItemStack(Items.SHIELD),
				new ItemStack(Items.GOLDEN_APPLE, 3), new ItemStack(Items.CLOCK),
				new ItemStack(Items.SPYGLASS), new ItemStack(Items.SHEARS));
		for (int index = 0; index < slots.length; index++)
			player.getInventory().setItem(slots[index], stacks.get(index).copy());
		List<ItemStack> inventoryBefore = new ArrayList<>(36);
		for (int slot = 0; slot < 36; slot++) inventoryBefore.add(player.getInventory().getItem(slot).copy());
		state.inventoryBefore = List.copyOf(inventoryBefore);
	}

	private static void prepareSafePath(ServerLevel level, ServerPlayer player, FixtureState state) {
		BlockPos origin = player.blockPosition();
		for (int x = -28; x <= 28; x++) for (int z = -28; z <= 28; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
			level.setBlock(origin.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 2);
		}
	}

	private static void prepareWorldInvariants(ServerLevel level, ServerPlayer player, FixtureState state) {
		BlockPos origin = player.blockPosition().offset(48, 0, 0);
		for (int x = -7; x <= 7; x++) for (int z = -3; z <= 8; z++) {
			level.setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
			for (int y = 0; y <= 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
		}
		for (int x = -4; x <= 4; x++) for (int y = 0; y <= 3; y++)
			level.setBlock(origin.offset(x, y, 5), Blocks.STONE_BRICKS.defaultBlockState(), 2);
		player.teleportTo(level, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
				Set.of(), 0.0F, 0.0F, true);
		state.invariantPositions = List.of(origin.offset(3, 0, 0), origin.offset(-3, 0, 5), origin.offset(5, 0, -5));
		level.setBlockAndUpdate(state.invariantPositions.get(0), Blocks.REDSTONE_BLOCK.defaultBlockState());
		level.setBlockAndUpdate(state.invariantPositions.get(1), Blocks.PISTON.defaultBlockState());
		level.setBlockAndUpdate(state.invariantPositions.get(2), Blocks.CHEST.defaultBlockState());
		Container chest = (Container) level.getBlockEntity(state.invariantPositions.get(2));
		chest.setItem(0, new ItemStack(ModItems.OLD_TERMINAL)); chest.setItem(1, new ItemStack(Items.DIAMOND, 20));
		player.getInventory().setItem(8, new ItemStack(Items.IRON_INGOT, 7));
		state.invariantBlocks = state.invariantPositions.stream().collect(java.util.stream.Collectors.toMap(
				pos -> pos, level::getBlockState, (a, b) -> a, LinkedHashMap::new));
	}

	private static void refreshStableBaseline(MinecraftServer server, AnomalyClientScenario scenario,
			FixtureState state) {
		if (scenario.fixture() != AnomalyClientScenario.Fixture.WORLD_INVARIANTS) return;
		ServerLevel level = (ServerLevel) player(server).level();
		state.invariantBlocks = state.invariantPositions.stream().collect(java.util.stream.Collectors.toMap(
				pos -> pos, level::getBlockState, (a, b) -> a, LinkedHashMap::new));
	}

	private static void recordActionHistory(ClientGameTestContext context) {
		context.runOnClient(client -> {
			client.options.keyUp.setDown(true); client.options.keyAttack.setDown(true);
		});
		context.waitTicks(6);
		context.runOnClient(client -> client.options.keyAttack.setDown(false));
		context.waitTicks(56);
		context.runOnClient(client -> client.options.keyUp.setDown(false));
	}

	private static void prepareVisualEvidence(ClientGameTestContext context, AnomalyClientScenario scenario,
			FixtureState fixture) {
		if (scenario.id().equals("phantom_echo")) {
			context.runOnClient(client -> {
				if (client.player == null || fixture.wall == null)
					throw new AssertionError("Surface fracture evidence requires its affected wall");
				client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
						Vec3.atCenterOf(fixture.wall));
			});
			context.waitTicks(2);
			context.runOnClient(client -> client.options.keyAttack.setDown(true));
			context.waitTicks(2);
			context.runOnClient(client -> client.options.keyAttack.setDown(false));
		}
		if (scenario.id().equals("action_echo")) {
			context.runOnClient(client -> {
				if (client.player == null) throw new AssertionError("Action echo evidence requires a player");
				float turned = client.player.getYRot() + 180.0F;
				client.player.setYRot(turned);
				client.player.setYHeadRot(turned);
				client.player.setYBodyRot(turned);
			});
			context.waitTicks(2);
		}
		if (scenario.id().equals("organ_misread")) {
			context.runOnClient(client -> {
				if (client.player == null) throw new AssertionError("Misread evidence requires a player");
				client.setScreen(new InventoryScreen(client.player));
			});
			context.waitTicks(2);
		}
		if (scenario.id().equals("viewpoint_separation")) {
			context.runOnClient(client -> {
				if (client.player == null) throw new AssertionError("Viewpoint movement evidence requires a player");
				fixture.cameraMovementStart = client.player.position();
				client.options.keyUp.setDown(true);
			});
			context.waitTicks(4);
		}
		if (scenario.id().equals("dark_watcher")) {
			context.runOnClient(client -> {
				if (client.player == null || client.level == null)
					throw new AssertionError("Watcher evidence requires a player and level");
				WatcherEntity watcher = client.level.getEntitiesOfClass(WatcherEntity.class,
						client.player.getBoundingBox().inflate(64.0D), entity -> true).stream()
						.min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(client.player)))
						.orElseThrow(() -> new AssertionError("Real watcher was not tracked on the client"));
				fixture.watcherViewYaw = client.player.getYRot();
				fixture.watcherViewPitch = client.player.getXRot();
				client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
						new Vec3(watcher.getX(), watcher.getY() + watcher.getBbHeight() * 0.92D, watcher.getZ()));
			});
			context.waitTicks(2);
		}
	}

	private static void restoreVisualEvidence(ClientGameTestContext context, AnomalyClientScenario scenario,
			FixtureState fixture) {
		if (!scenario.id().equals("dark_watcher")) return;
		context.runOnClient(client -> {
			if (client.player == null) return;
			client.player.setYRot(fixture.watcherViewYaw);
			client.player.setYHeadRot(fixture.watcherViewYaw);
			client.player.setYBodyRot(fixture.watcherViewYaw);
			client.player.setXRot(fixture.watcherViewPitch);
		});
	}

	private static void assertClientPeak(ClientGameTestContext context, AnomalyClientScenario scenario,
			AnomalyTestSnapshot peak, FixtureState fixture) {
		if (!peak.active() || !peak.anomalyId().equals(scenario.id()))
			throw new AssertionError("Peak snapshot is not the expected active anomaly: " + peak);
		if (!peak.overlays().containsAll(scenario.peakOverlays()))
			throw new AssertionError("Missing peak overlays " + scenario.peakOverlays() + " in " + peak.overlays());
		if (peak.dedicatedSoundCount() < scenario.minimumDedicatedSounds()
				|| peak.ambientSoundCount() < scenario.minimumAmbientSounds())
			throw new AssertionError("Formal/ambient sound counters did not reach the peak contract");
		if (!scenario.hiddenLights().contains(peak.hiddenLightCount()))
			throw new AssertionError("Hidden light count outside scenario contract: " + peak.hiddenLightCount());
		if (!scenario.misreadItems().contains(peak.misreadItemCount()))
			throw new AssertionError("Misread item count outside scenario contract: " + peak.misreadItemCount());
		if (scenario.id().equals("organ_misread")) {
			Set<Integer> selected = context.computeOnClient(client ->
					AnomalyPresentationController.misreadSlotsForTesting());
			assertMisreadDistribution(selected);
			context.runOnClient(client -> {
				for (int slot : selected) {
					String name = client.player.getInventory().getItem(slot).getHoverName().getString();
					if (!name.equals("I SEE YOU...."))
						throw new AssertionError("Misread slot " + slot + " retained item name " + name);
				}
			});
			// Picking a misread item up empties its slot, and every empty slot in the game holds the
			// same ItemStack.EMPTY singleton - so an identity match against the live inventory used
			// to answer "yes" for every vacant square on the screen the moment one item was dragged.
			// Emptied here on the client only, and put straight back: the real-inventory assertions
			// further down compare against the server's copy.
			context.runOnClient(client -> {
				int probe = selected.iterator().next();
				ItemStack original = client.player.getInventory().getItem(probe);
				client.player.getInventory().setItem(probe, ItemStack.EMPTY);
				try {
					if (AnomalyPresentationController.isMisread(ItemStack.EMPTY))
						throw new AssertionError("An emptied misread slot made every empty stack misread");
					if (ItemStack.EMPTY.getHoverName().getString().equals("I SEE YOU...."))
						throw new AssertionError("An emptied misread slot renamed every empty stack");
				} finally {
					client.player.getInventory().setItem(probe, original);
				}
			});
		}
		if (scenario.id().equals("viewpoint_separation")) {
			float fixedYaw = AnomalyPresentationController.fixedCameraYawForTesting();
			float fixedPitch = AnomalyPresentationController.fixedCameraPitchForTesting();
			// The separated view inherits the player's pitch instead of levelling it. That was a
			// deliberate reversal - see ViewpointOrientationPolicy: a flat horizon is a cut, and the
			// one moment a player is certain to notice a cut is the moment their view leaves their
			// body. The original worry about losing sight of the body is answered by the policy's
			// clamp rather than by discarding the angle, so what is asserted here is the clamp.
			float expectedPitch = net.minecraft.util.Mth.clamp(fixture.separationForwardPitch,
					-ViewpointOrientationPolicy.MAX_PITCH_DEGREES,
					ViewpointOrientationPolicy.MAX_PITCH_DEGREES);
			if (Math.abs(net.minecraft.util.Mth.wrapDegrees(fixedYaw - fixture.separationForwardYaw)) > 0.01F
					|| Math.abs(fixedPitch - expectedPitch) > 0.01F)
				throw new AssertionError("Separated camera did not face the trigger-time forward heading: yaw="
						+ fixedYaw + ", pitch=" + fixedPitch);
			double moved = context.computeOnClient(client -> client.player.position()
					.distanceTo(fixture.cameraMovementStart));
			if (moved < 0.05D) throw new AssertionError("Player could not walk while the camera stayed behind");
		}
		if (peak.temporaryEntityCount() != scenario.temporaryEntities())
			throw new AssertionError("Temporary client entity count was " + peak.temporaryEntityCount()
					+ " instead of " + scenario.temporaryEntities());
		if (peak.cameraSeparated() != scenario.cameraSeparated() || peak.inputLocked() != scenario.inputLocked()
				|| peak.audioLocked() != scenario.audioLocked() || peak.metaDegraded() != scenario.metaDegraded())
			throw new AssertionError("Peak lock/camera/Meta snapshot differs from scenario contract: " + peak);
		if (scenario.id().equals("channel_override")) context.runOnClient(client -> {
			if (!(client.screen instanceof ChannelOverrideScreen))
				throw new AssertionError("Closed channel override screen was not reopened");
		});
		if (scenario.id().equals("local_rule_collapse")) {
			Set<BlockPos> fragments = context.computeOnClient(client ->
					AnomalyPresentationController.currentRuleFragmentsForTesting());
			if (fragments.isEmpty() || fragments.size() > 24)
				throw new AssertionError("Local-rule fragments were not sparse and bounded: " + fragments.size());
			List<BlockPos> positions = List.copyOf(fragments);
			for (int first = 0; first < positions.size(); first++) {
				for (int second = first + 1; second < positions.size(); second++) {
					if (positions.get(first).distSqr(positions.get(second)) < 9.0D)
						throw new AssertionError("Local-rule collapse selected a contiguous block patch");
				}
			}
		}
	}

	private static void assertMisreadDistribution(Set<Integer> selected) {
		int[][] rows = {{9, 18}, {18, 27}, {27, 36}, {0, 9}};
		for (int row = 0; row < rows.length; row++) {
			int rowStart = rows[row][0];
			int rowEnd = rows[row][1];
			List<Integer> rowSlots = selected.stream()
					.filter(slot -> slot >= rowStart && slot < rowEnd)
					.sorted().toList();
			if (rowSlots.size() != 2)
				throw new AssertionError("Misread row " + row + " selected " + rowSlots + " instead of two slots");
			if (rowSlots.get(1) - rowSlots.get(0) <= 1)
				throw new AssertionError("Misread row " + row + " selected adjacent slots " + rowSlots);
		}
	}

	private static void assertServerPeak(TestSingleplayerContext singleplayer, AnomalyClientScenario scenario,
			FixtureState fixture, int logBefore) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = player(server); ServerLevel level = (ServerLevel) player.level();
			var active = AnomalyGameTestBridge.active(player);
			if (active == null || !active.id().equals(scenario.id())) throw new AssertionError("Server active instance missing at peak");
			if (AnomalyGameTestBridge.anomalyLogCount(player, scenario.id()) != logBefore)
				throw new AssertionError("Anomaly logged before completion");
			switch (scenario.id()) {
				case "light_dropout" -> {
					if (!level.getBlockState(fixture.light).isAir())
						throw new AssertionError("Nearby light source was not extinguished on the server");
				}
				case "phantom_echo" -> assertBlock(level, fixture.wall, fixture.blocksBefore.get(fixture.wall), "real wall changed");
				case "watcher_alignment" -> {
					Mob near = (Mob) level.getEntity(fixture.nearMob); Mob far = (Mob) level.getEntity(fixture.farMob);
					if (near == null || Math.abs(near.getYHeadRot() - 37.0F) < 1.0F)
						throw new AssertionError("Near mob did not face the player");
					if (far == null || Math.abs(far.getYHeadRot() - fixture.farHeadRotation) > 0.01F)
						throw new AssertionError("Out-of-range mob orientation changed");
				}
				case "dark_watcher" -> {
					if (watcherCount(level, player) != fixture.serverWatcherCountBefore + 1)
						throw new AssertionError("Real watcher did not spawn in the visible cone");
				}
				case "door_cascade" -> {
					// Forced open, not removed. The cascade used to delete both halves and discard the
					// drop, which is a swallow rather than a haunting - and on a shared server it did
					// that to doors the target had never touched.
					if (fixture.ordinaryDoors.stream().anyMatch(pos ->
							!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
									|| !level.getBlockState(pos).getValue(BlockStateProperties.OPEN)
									|| !level.getBlockState(pos.above()).getValue(BlockStateProperties.OPEN)))
						throw new AssertionError("Ordinary door halves were not forced open in place");
					if (!(level.getBlockState(fixture.protectedDoor).getBlock() instanceof net.minecraft.world.level.block.DoorBlock))
						throw new AssertionError("Protected door was changed");
					if (!level.getEntitiesOfClass(ItemEntity.class, new AABB(player.blockPosition()).inflate(16),
							item -> item.getItem().is(Items.OAK_DOOR)).isEmpty())
						throw new AssertionError("Door cascade produced a forbidden drop");
				}
				case "experience_gap" -> {
					// Only liveness here, never a distance. The walk is driven one waypoint per SERVER
					// tick, while the scenario counts CLIENT ticks, and under a loaded full suite the
					// integrated server falls behind the client - at the same client tick the walk had
					// covered anywhere from a third to all of its ground. How far the player got is
					// asserted at cleanup instead, where the lease is provably finished.
					if (AnomalyGameTestBridge.activeLeaseCount() != 1)
						throw new AssertionError("Safe movement lease was not driving the player at peak");
				}
				case "local_rule_collapse" -> assertWorldInvariants(level, player, fixture);
				default -> { }
			}
		});
	}

	private static void assertClientCleanup(ClientGameTestContext context, AnomalyClientScenario scenario,
			int traceBefore) {
		context.runOnClient(client -> {
			AnomalyTestSnapshot clean = AnomalyPresentationController.testSnapshot();
			if (clean.active() || !clean.overlays().isEmpty() || clean.hiddenLightCount() != 0
					|| clean.misreadItemCount() != 0 || clean.temporaryEntityCount() != 0 || clean.cameraSeparated()
					|| clean.inputLocked() || clean.audioLocked() || clean.metaDegraded())
				throw new AssertionError("Client anomaly state was not fully restored: " + clean);
			if (client.getCameraEntity() != client.player) throw new AssertionError("Original camera entity was not restored exactly");
			if (scenario.completion() == AnomalyClientScenario.Completion.CLIENT_RESTORE_WITH_PERSISTENT_TRACE) {
				if (clean.persistentTraceCount() <= traceBefore) throw new AssertionError("Persistent session trace was not retained");
			} else if (clean.persistentTraceCount() != traceBefore)
				throw new AssertionError("Unrelated scenario changed persistent trace count");
		});
	}

	private static void assertServerCleanup(TestSingleplayerContext singleplayer, AnomalyClientScenario scenario,
			FixtureState fixture, int logBefore) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = player(server); ServerLevel level = (ServerLevel) player.level();
			if (AnomalyGameTestBridge.active(player) != null || AnomalyGameTestBridge.activeLeaseCount() != 0)
				throw new AssertionError("Active anomaly or temporary server lease remained after cleanup");
			if (!AnomalyGameTestBridge.projectedActiveId(player).equals("none"))
				throw new AssertionError("Persistent active anomaly projection was not cleared");
			if (AnomalyGameTestBridge.anomalyLogCount(player, scenario.id()) != logBefore)
				throw new AssertionError("Completed anomaly unexpectedly wrote a terminal log entry");
			switch (scenario.id()) {
				case "light_dropout" -> assertBlock(level, fixture.light, fixture.blocksBefore.get(fixture.light), "light source did not restore");
				case "phantom_echo" -> assertBlock(level, fixture.wall, fixture.blocksBefore.get(fixture.wall), "wall did not remain real");
				case "dark_watcher" -> {
					if (watcherCount(level, player) != fixture.serverWatcherCountBefore)
						throw new AssertionError("Real anomaly watcher remained after completion");
				}
				case "door_cascade" -> {
					// Still open after cleanup. The cascade is permanent in the sense that matters -
					// nobody closes your doors for you afterwards - without anything having been taken.
					if (fixture.ordinaryDoors.stream().anyMatch(pos ->
							!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
									|| !level.getBlockState(pos).getValue(BlockStateProperties.OPEN)))
						throw new AssertionError("Forced doors were closed again by cleanup");
					if (!(level.getBlockState(fixture.protectedDoor).getBlock() instanceof net.minecraft.world.level.block.DoorBlock))
						throw new AssertionError("Protected door changed during cleanup");
				}
				case "experience_gap" -> {
					// The lease is asserted gone above, so the walk has run every one of its ticks and
					// this displacement is whatever the anomaly actually achieved - not a sample taken
					// mid-stride. The gap is that the player wakes up somewhere else, so nothing
					// carries them back; a nudge would still read well under 8 blocks here.
					double distance = player.position().distanceTo(fixture.playerPosition);
					if (distance < 8.0D)
						throw new AssertionError("Experience gap did not perform real safe movement (distance="
								+ distance + ")");
				}
				case "organ_misread" -> {
					for (int slot = 0; slot < fixture.inventoryBefore.size(); slot++)
						if (!ItemStack.isSameItemSameComponents(player.getInventory().getItem(slot), fixture.inventoryBefore.get(slot))
								|| player.getInventory().getItem(slot).getCount() != fixture.inventoryBefore.get(slot).getCount())
							throw new AssertionError("Misread changed real inventory identity/count in slot " + slot);
				}
				case "local_rule_collapse" -> assertWorldInvariants(level, player, fixture);
				default -> { }
			}
		});
	}

	private static void assertNotLogged(TestSingleplayerContext singleplayer, int before, String id) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			if (AnomalyGameTestBridge.anomalyLogCount(player, id) != before)
				throw new AssertionError(id + " wrote its terminal log before completion");
		});
	}

	private static void assertWorldInvariants(ServerLevel level, ServerPlayer player, FixtureState fixture) {
		for (var entry : fixture.invariantBlocks.entrySet()) assertBlock(level, entry.getKey(), entry.getValue(),
				"local rule collapse changed a real block");
		Container chest = (Container) level.getBlockEntity(fixture.invariantPositions.get(2));
		if (chest == null || !chest.getItem(0).is(ModItems.OLD_TERMINAL) || chest.getItem(1).getCount() != 20
				|| player.getInventory().getItem(8).getCount() != 7)
			throw new AssertionError("Local rule collapse changed container/redstone/inventory state");
	}

	private static void assertBlock(ServerLevel level, BlockPos pos, BlockState expected, String message) {
		if (!level.getBlockState(pos).equals(expected)) throw new AssertionError(message + " at " + pos);
	}

	private static int watcherCount(ServerLevel level, ServerPlayer player) {
		return level.getEntitiesOfClass(WatcherEntity.class, player.getBoundingBox().inflate(64), entity -> true).size();
	}

	private static ServerPlayer player(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.size() != 1) throw new AssertionError("Anomaly client fixture requires exactly one server player");
		return players.getFirst();
	}

	private static Path reportRoot() {
		return Path.of(System.getProperty("thefourthfrequency.projectDir", System.getProperty("user.dir")))
				.resolve("build/reports/client-anomalies");
	}

	private static void resetReportDirectory() {
		try {
			Path root = reportRoot();
			Files.deleteIfExists(root.resolve("summary.json"));
			Path screenshots = root.resolve("screenshots");
			if (Files.isDirectory(screenshots)) {
				try (var paths = Files.list(screenshots)) {
					for (Path path : paths.filter(value -> value.getFileName().toString().endsWith(".png")).toList())
						Files.deleteIfExists(path);
				}
			}
		} catch (java.io.IOException failure) {
			throw new AssertionError("Could not reset anomaly report artifacts", failure);
		}
	}

	private static String copyScreenshot(Path source, String fallbackName) throws java.io.IOException {
		Files.createDirectories(reportRoot().resolve("screenshots"));
		String name = fallbackName;
		if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) name += ".png";
		Files.copy(source, reportRoot().resolve("screenshots").resolve(name), StandardCopyOption.REPLACE_EXISTING);
		return name;
	}

	private static void writeSummary(List<Result> results, ClientGameTestSelection selection) {
		try {
			Files.createDirectories(reportRoot());
			JsonObject root = new JsonObject();
			root.addProperty("suite", selection.anomalyId().isPresent() ? "anomalies-single" : "anomalies");
			root.addProperty("scenarioCount", results.size());
			root.addProperty("passed", results.stream().filter(Result::passed).count());
			root.addProperty("failed", results.stream().filter(result -> !result.passed()).count());
			JsonArray scenarios = new JsonArray();
			for (Result result : results) {
				JsonObject value = new JsonObject();
				value.addProperty("catalogNumber", result.catalogNumber()); value.addProperty("id", result.id());
				value.addProperty("tier", result.tier()); value.addProperty("seed", result.seed());
				value.addProperty("acceleratedTicks", result.acceleratedTicks());
				value.addProperty("elapsedMillis", result.elapsedMillis());
				value.addProperty("status", result.passed() ? "PASS" : "FAIL");
				value.addProperty("screenshot", result.screenshot()); value.addProperty("failureReason", result.failureReason());
				scenarios.add(value);
			}
			root.add("scenarios", scenarios);
			String json = new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator();
			Files.writeString(reportRoot().resolve("summary.json"), json, StandardCharsets.UTF_8);
		} catch (Exception failure) {
			throw new AssertionError("Could not write complete anomaly client report", failure);
		}
	}

	private static String conciseFailure(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null && current.getMessage() == null) current = current.getCause();
		String message = current.getMessage();
		return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
	}

	private static void runWindowsMetaSmoke(ClientGameTestContext context) {
		if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"))
			throw new AssertionError("anomaly-meta-smoke is Windows-only");
		context.waitForScreen(TitleScreen.class);
		int[] originalWindow = new int[4]; long[] controlledNotepadPid = {-1L};
		try {
			context.runOnClient(client -> {
				long handle = client.getWindow().handle(); int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
				GLFW.glfwGetWindowPos(handle, x, y); GLFW.glfwGetWindowSize(handle, w, h);
				originalWindow[0] = x[0]; originalWindow[1] = y[0]; originalWindow[2] = w[0]; originalWindow[3] = h[0];
				if (!MetaController.startWindowPulse()) throw new AssertionError("Real Windows window pulse did not start");
			});
			context.waitTicks(90);
			context.runOnClient(client -> {
				long handle = client.getWindow().handle(); int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
				GLFW.glfwGetWindowPos(handle, x, y); GLFW.glfwGetWindowSize(handle, w, h);
				if (Math.abs(x[0] - originalWindow[0]) > 2 || Math.abs(y[0] - originalWindow[1]) > 2
						|| Math.abs(w[0] - originalWindow[2]) > 2 || Math.abs(h[0] - originalWindow[3]) > 2)
					throw new AssertionError("Window position/size snapshot was not restored");
				if (!MetaController.windowPulseLifecycleVerifiedForTesting())
					throw new AssertionError("Window title/icon mutation and restoration lifecycle was incomplete");
				if (!MetaController.startDesktopPresence()) throw new AssertionError("Controlled Notepad did not start");
				controlledNotepadPid[0] = MetaController.controlledNotepadPidForTesting();
				if (controlledNotepadPid[0] <= 0L) throw new AssertionError("Controlled Notepad PID was not captured");
			});
			context.waitFor(client -> MetaController.unicodeTyperFinishedForTesting(), 900);
			context.runOnClient(client -> {
				if (!MetaController.unicodeTyperSucceededForTesting())
					throw new AssertionError("Owned Unicode input failed with helper status "
							+ MetaController.unicodeTyperExitCodeForTesting());
			});
		} finally {
			context.runOnClient(client -> MetaController.restore());
			context.waitTicks(20);
		}
		if (controlledNotepadPid[0] > 0L
				&& ProcessHandle.of(controlledNotepadPid[0]).map(ProcessHandle::isAlive).orElse(false))
			throw new AssertionError("Meta smoke left its controlled Notepad process alive");
	}

	private static final class FixtureState {
		private final Vec3 playerPosition; private final net.minecraft.resources.ResourceKey<Level> playerDimension;
		private final float playerYaw; private final float playerPitch; private final int anomalyLogCountBefore;
		private final Map<BlockPos, BlockState> blocksBefore = new LinkedHashMap<>();
		private BlockPos light; private BlockPos wall; private UUID nearMob; private UUID farMob; private float farHeadRotation;
		private final List<BlockPos> ordinaryDoors = new ArrayList<>(); private BlockPos protectedDoor;
		private List<ItemStack> inventoryBefore = List.of(); private int serverWatcherCountBefore;
		private float watcherViewYaw; private float watcherViewPitch;
		private float separationForwardYaw;
		private float separationForwardPitch;
		private Vec3 cameraMovementStart;
		private final List<BlockPos> facilityAnchors = new ArrayList<>(); private List<BlockPos> invariantPositions = List.of();
		private Map<BlockPos, BlockState> invariantBlocks = Map.of();
		private FixtureState(ServerPlayer player, int anomalyLogCountBefore) {
			this.playerPosition = player.position(); this.playerDimension = player.level().dimension();
			this.playerYaw = player.getYRot(); this.playerPitch = player.getXRot();
			this.anomalyLogCountBefore = anomalyLogCountBefore;
		}
		private void rememberBlock(ServerLevel level, BlockPos pos) { blocksBefore.put(pos, level.getBlockState(pos)); }
	}

	private record Result(int catalogNumber, String id, int tier, long seed, int acceleratedTicks,
			long elapsedMillis, boolean passed, String screenshot, String failureReason) { }
}
