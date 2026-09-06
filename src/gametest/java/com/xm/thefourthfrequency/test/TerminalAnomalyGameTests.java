package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.AnomalyCompletionStatus;
import com.xm.thefourthfrequency.terminal.AnomalyConditions;
import com.xm.thefourthfrequency.terminal.AnomalyGameTestBridge;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import com.xm.thefourthfrequency.terminal.AnomalyServerEffects;
import com.xm.thefourthfrequency.terminal.AmbientAnomalyService;
import com.xm.thefourthfrequency.terminal.TerminalAnomalyLogService;
import com.xm.thefourthfrequency.terminal.SignalBand;
import com.xm.thefourthfrequency.terminal.TerminalSignalLog;
import com.xm.thefourthfrequency.terminal.TerminalSignalService;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.networking.AnomalyCompleteC2S;
import com.xm.thefourthfrequency.networking.DebugActionPayload;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.persistence.PersistenceSchema;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.StoryProgressService;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.DebugPanelService;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import com.xm.thefourthfrequency.world.WorldDecayService;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.lang.reflect.Method;

public final class TerminalAnomalyGameTests implements CustomTestMethodInvoker {
	@GameTest
	public void bindingRevealsTheMaintenanceHandoffWithoutCalibration(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		CompoundTag initial = data.terminalRecord(player.getUUID()).orElseThrow();
		var initialFiles = TerminalRuntimeService.visibleFiles(initial);
		helper.assertValueEqual(initialFiles.size(), 1,
				"A new terminal starts with exactly one locked complete diary");
		helper.assertValueEqual(initialFiles.getFirst().id(), "encrypted_witness_file",
				"The complete diary is the initial stable FILES entry");
		helper.assertFalse(initialFiles.getFirst().unlocked(),
				"The initial complete diary remains locked");

		TerminalSignalService.updatePlayerForTesting(player);
		helper.assertFalse(TerminalRuntimeService.visibleFiles(data.terminalRecord(player.getUUID()).orElseThrow())
				.stream().anyMatch(file -> file.id().equals("maintenance_handoff")),
				"The maintenance handoff stays absent before personal binding");
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalSignalService.updatePlayerForTesting(player);
		var boundFiles = TerminalRuntimeService.visibleFiles(data.terminalRecord(player.getUUID()).orElseThrow());
		var handoff = boundFiles.stream().filter(file -> file.id().equals("maintenance_handoff"))
				.findFirst().orElseThrow(() -> new AssertionError("Binding did not reveal the maintenance handoff"));
		helper.assertTrue(handoff.unlocked(), "The handoff is immediately readable");
		helper.assertTrue(boundFiles.stream().allMatch(file -> file.discovered()
				&& TerminalFileState.discovered(data.terminalRecord(player.getUUID()).orElseThrow(), file.id())),
				"Every visible file has a real discovery record instead of a locked placeholder");
		helper.succeed();
	}

	@GameTest
	public void bodyMappingWarningRequiresThreeRecordedEyeThrows(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(),
				record -> record.putInt(TerminalData.EYE_SAMPLE_COUNT, 2));
		TerminalSignalService.updatePlayerForTesting(player);
		helper.assertFalse(TerminalFileState.discovered(
						data.terminalRecord(player.getUUID()).orElseThrow(), "body_mapping_warning"),
				"Two recorded throws must not reveal the altar warning");

		data.updateTerminalRecord(player.getUUID(),
				record -> record.putInt(TerminalData.EYE_SAMPLE_COUNT, 3));
		TerminalSignalService.updatePlayerForTesting(player);
		helper.assertTrue(TerminalFileState.discovered(
						data.terminalRecord(player.getUUID()).orElseThrow(), "body_mapping_warning"),
				"The third recorded throw reveals the altar warning");
		helper.succeed();
	}

	@GameTest
	public void earlyResourceGuidanceDoesNotSkipNarrativeBinding(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		BlockPos ore = player.blockPosition().below(2);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putLong(TerminalData.ISSUED_GAME_TIME, player.level().getGameTime() - 1_201L);
			record.putInt(TerminalData.BAND_STAGE, 0);
			record.putBoolean(TerminalData.BOUND, false);
			record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK, SurvivalMilestone.MINED_LOGS.mask());
		});

		ResourceGuidanceService.probeForTesting(player);
		CompoundTag located = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(located.getBooleanOr(TerminalData.TARGET_LOCATED, false),
				"Early terminal observation can locate a real resource before the fourth band appears");
		helper.assertValueEqual(located.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Locating an optional resource never reveals the fourth band");

		player.getInventory().add(new ItemStack(Items.RAW_IRON));
		ResourceGuidanceService.updatePlayer(player);
		CompoundTag accepted = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertFalse(accepted.getBooleanOr(TerminalData.BOUND, false),
				"Accepting optional resource help does not itself bind the terminal");
		helper.assertValueEqual(accepted.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Accepting optional resource help leaves narrative reveal state unchanged");
		helper.assertTrue(accepted.getStringOr(TerminalData.ACCEPTED_ADVICE, "").contains("iron"),
				"The terminal still remembers that its accurate advice was followed");
		helper.succeed();
	}

	@GameTest
	public void heldTerminalRefreshesForNotificationsButNotLifecyclePolling(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ItemStack terminal = findTerminal(player);
		long projectedOnlineTicks = TerminalData.copyTag(terminal)
				.getLongOr(TerminalData.ONLINE_SURVIVAL_TICKS, 0L);
		data.updateTerminalRecord(player.getUUID(), record ->
				record.putLong(TerminalData.ONLINE_SURVIVAL_TICKS, projectedOnlineTicks + 1_000L));
		TerminalLifecycleService.ensureCarried(player, false);
		helper.assertValueEqual(TerminalData.copyTag(findTerminal(player))
				.getLongOr(TerminalData.ONLINE_SURVIVAL_TICKS, 0L), projectedOnlineTicks,
				"Routine carried-terminal polling does not rewrite the held item");

		TerminalSignalService.record(player, SignalBand.PUBLIC, "test_notification", 0, 1, true);
		CompoundTag notified = TerminalData.copyTag(findTerminal(player));
		helper.assertValueEqual(notified.getLongOr(TerminalData.ONLINE_SURVIVAL_TICKS, 0L),
				projectedOnlineTicks + 1_000L, "A real notification synchronizes the held item once");
		helper.assertTrue(notified.getIntOr(TerminalData.UNREAD_SIGNAL_COUNT, 0) > 0,
				"The notification projects the unread hand-state marker");
		helper.succeed();
	}

	@GameTest
	public void unknownBandRequiresNarrativePreludeInsteadOfTimeOrFirstAnomaly(GameTestHelper helper) {
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ServerPlayer timed = helper.makeMockServerPlayerInLevel();
		data.updateTerminalRecord(timed.getUUID(), record -> {
			record.putInt(TerminalData.BAND_STAGE, 0);
			record.putLong(TerminalData.ONLINE_SURVIVAL_TICKS, 120_000L);
			record.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, Long.MAX_VALUE);
		});
		TerminalSignalService.updatePlayerForTesting(timed);
		helper.assertValueEqual(data.terminalRecord(timed.getUUID()).orElseThrow()
				.getIntOr(TerminalData.BAND_STAGE, 0), 0, "Online time alone never reveals the unknown band");

		ServerPlayer anomalous = helper.makeMockServerPlayerInLevel();
		data.updateTerminalRecord(anomalous.getUUID(), record ->
				record.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, Long.MAX_VALUE));
		TerminalAnomalyLogService.record(anomalous, "phantom_echo", 0, 1, 40, false);
		var anomalyRecord = data.terminalRecord(anomalous.getUUID()).orElseThrow();
		helper.assertValueEqual(anomalyRecord.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"A first anomaly alone does not reveal the unknown band");
		TerminalAnomalyLogService.record(anomalous, "light_dropout", 1, 1, 40, false);
		StoryProgressService.update(anomalous, data);
		helper.assertValueEqual(data.terminalRecord(anomalous.getUUID()).orElseThrow()
				.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Repeated ambient anomalies do not replace the mining milestone");
		data.updateTerminalRecord(anomalous.getUUID(), record -> {
			record.putBoolean(TerminalData.BOUND, true);
			record.putBoolean(TerminalData.NIGHT_WITNESSED, true);
			record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
					record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0)
							| SurvivalMilestone.IRON.mask());
		});
		StoryProgressService.update(anomalous, data);
		anomalyRecord = data.terminalRecord(anomalous.getUUID()).orElseThrow();
		helper.assertValueEqual(anomalyRecord.getIntOr(TerminalData.BAND_STAGE, 0), 1,
				"Unknown reveals from mining progression without a forced correction scene");
		helper.assertValueEqual(TerminalSignalLog.entries(anomalyRecord, SignalBand.UNKNOWN).size(), 0,
				"Witnessed anomalies advance the prelude without entering terminal history");
		helper.succeed();
	}

	@GameTest
	public void openingRecordsClearsUnreadEventsAcrossLegacyBands(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		TerminalSignalService.record(player, SignalBand.UNKNOWN, "continuity", 0, 2, true);
		TerminalSignalService.record(player, SignalBand.WEATHER, "weather_changed", 1, 1, true);
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));
		TerminalRuntimeService.open(player, 0);
		TerminalRuntimeService.control(player, TerminalControlPayload.MARK_RECORDS_READ, 0);
		var record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertFalse(TerminalSignalLog.entries(record).stream().anyMatch(TerminalSignalLog.Entry::unread),
				"The unified records page marks every event read regardless of its legacy wire band");
		helper.assertValueEqual(TerminalSignalLog.unreadCount(record), 0, "Unread count is cleared atomically");
		TerminalRuntimeService.control(player, TerminalControlPayload.CLOSE, 0);
		helper.succeed();
	}

	@GameTest
	public void wireChannelsStayBoundedWhileReadStateIsUnified(GameTestHelper helper) {
		CompoundTag record = new CompoundTag();
		for (int index = 0; index < 40; index++) TerminalSignalLog.append(record, SignalBand.UNKNOWN,
				"unknown_" + index, index, index, "minecraft:overworld", 0L, 0, 1, index == 39);
		for (int index = 0; index < 7; index++) TerminalSignalLog.append(record, SignalBand.WEATHER,
				"weather_" + index, index, index, "minecraft:overworld", 0L, 0, 1, index == 6);
		var unknown = TerminalSignalLog.entries(record, SignalBand.UNKNOWN);
		helper.assertValueEqual(unknown.size(), 32, "Unknown channel keeps 32 entries");
		helper.assertValueEqual(unknown.getFirst().type(), "unknown_39", "Newest unknown event first");
		helper.assertValueEqual(unknown.getLast().type(), "unknown_8", "Old unknown events trimmed");
		helper.assertValueEqual(TerminalSignalLog.entries(record, SignalBand.WEATHER).size(), 7,
				"Weather channel has an independent cap");
		helper.assertValueEqual(TerminalSignalLog.unreadCount(record), 2,
				"Unread events remain unified even when legacy wire ids differ");
		helper.assertTrue(TerminalSignalLog.markAllRead(record), "The records page clears all unread events");
		helper.assertValueEqual(TerminalSignalLog.unreadCount(record), 0, "No unread events remain");
		helper.succeed();
	}

	@GameTest
	public void operationalTelemetryIsPrunedWithoutRemovingNavigationCandidates(GameTestHelper helper) {
		CompoundTag record = new CompoundTag();
		TerminalSignalLog.append(record, SignalBand.WEATHER, "weather_changed",
				1L, 1L, "minecraft:overworld", 0L, 1, 1, true);
		TerminalSignalLog.append(record, SignalBand.MINING, "resource_target_located",
				2L, 2L, "minecraft:overworld", 0L, 1, 1, true);
		TerminalSignalLog.append(record, SignalBand.UNKNOWN, "phantom_echo",
				3L, 3L, "minecraft:overworld", 0L, 0, 1, true);
		TerminalSignalLog.append(record, SignalBand.UNKNOWN, "fragment_candidate_2_1",
				4L, 4L, "minecraft:overworld", 0L, 1, 1, false);
		TerminalSignalLog.append(record, SignalBand.PUBLIC, "fragment_shared_0",
				5L, 5L, "minecraft:overworld", 0L, 0, 1, true);

		helper.assertTrue(TerminalSignalLog.pruneOperationalTelemetry(record),
				"Existing operational telemetry and anomaly history are compacted once");
		var types = TerminalSignalLog.entries(record).stream().map(TerminalSignalLog.Entry::type).toList();
		helper.assertValueEqual(types.size(), 2, "Only useful and tool-owned entries remain");
		helper.assertTrue(types.contains("fragment_candidate_2_1"),
				"Candidate coordinates remain available to the navigation tool");
		helper.assertTrue(types.contains("fragment_shared_0"), "Current story records remain visible");
		helper.assertValueEqual(TerminalSignalLog.unreadCount(record), 1,
				"Pruning also removes unread attention created only by telemetry");
		helper.succeed();
	}

	@GameTest
	public void discoveredLockedFileUnlocksInPlaceAndSortsByCatalog(GameTestHelper helper) {
		CompoundTag record = new CompoundTag();
		helper.assertTrue(TerminalFileState.discover(record, "encrypted_witness_file", 30, 40, false),
				"Witness file discovered locked");
		helper.assertTrue(TerminalFileState.discover(record, "maintenance_handoff", 10, 20, true),
				"Maintenance file discovered");
		helper.assertFalse(TerminalFileState.unlocked(record, "encrypted_witness_file"), "Witness remains locked");
		helper.assertValueEqual(TerminalFileState.states(record).getFirst().id(), "maintenance_handoff",
				"Files sort in catalog order");
		helper.assertTrue(TerminalFileState.discover(record, "encrypted_witness_file", 50, 60, true),
				"Witness unlock changes existing entry");
		helper.assertFalse(TerminalFileState.discover(record, "encrypted_witness_file", 70, 80, true),
				"Repeated unlock is idempotent");
		var witness = TerminalFileState.states(record).get(1);
		helper.assertTrue(witness.unlocked(), "Witness now unlocked");
		helper.assertValueEqual(witness.discoveredGameTime(), 30L, "Discovery time retained");
		helper.assertValueEqual(witness.unlockedGameTime(), 50L, "Unlock time recorded");
		helper.succeed();
	}

	@GameTest
	public void directedLightAnomaliesArePrivateAndLeaveServerWorldUntouched(GameTestHelper helper) {
		ServerPlayer first = helper.makeMockServerPlayerInLevel();
		ServerPlayer second = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(first.getUUID(), record -> record.putInt(TerminalData.BAND_STAGE, 1));
		data.updateTerminalRecord(second.getUUID(), record -> record.putInt(TerminalData.BAND_STAGE, 1));

		BlockPos chestPos = first.blockPosition().offset(2, 0, 2);
		helper.getLevel().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
		Container chest = (Container) helper.getLevel().getBlockEntity(chestPos);
		chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
		BlockPos solidPos = chestPos.below();
		helper.getLevel().setBlockAndUpdate(solidPos, Blocks.STONE.defaultBlockState());
		var chestState = helper.getLevel().getBlockState(chestPos);
		var solidState = helper.getLevel().getBlockState(solidPos);
		int signal = helper.getLevel().getBestNeighborSignal(chestPos);
		first.getInventory().setItem(8, new ItemStack(Items.IRON_INGOT, 7));

		for (int variant = 0; variant < AmbientAnomalyService.TYPES.length; variant++) {
			TerminalAnomalyLogService.record(first, AmbientAnomalyService.TYPES[variant], variant, 1, 80, true);
		}

		helper.assertValueEqual(TerminalSignalLog.entries(
				data.terminalRecord(first.getUUID()).orElseThrow(), SignalBand.UNKNOWN).size(),
				0, "Formal catalog anomalies do not enter the target player's terminal history");
		helper.assertValueEqual(TerminalSignalLog.entries(
				data.terminalRecord(second.getUUID()).orElseThrow(), SignalBand.UNKNOWN).size(), 0,
				"A second player receives no shared anomaly record");
		helper.assertValueEqual(helper.getLevel().getBlockState(chestPos), chestState, "Container block state unchanged");
		helper.assertValueEqual(helper.getLevel().getBlockState(solidPos), solidState, "Solid block state unchanged");
		helper.assertValueEqual(helper.getLevel().getBestNeighborSignal(chestPos), signal, "Redstone state unchanged");
		helper.assertValueEqual(chest.getItem(0).getCount(), 3, "Container contents unchanged");
		helper.assertTrue(chest.getItem(0).is(Items.DIAMOND), "Container item identity unchanged");
		helper.assertValueEqual(first.getInventory().getItem(8).getCount(), 7, "Unrelated player items unchanged");
		helper.assertTrue(first.getInventory().getItem(8).is(Items.IRON_INGOT), "Unrelated player item identity unchanged");
		helper.succeed();
	}

	@GameTest
	public void strongAndWeakAnomaliesStayOutOfTheTerminalLog(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.BAND_STAGE, 1);
			record.putLong(TerminalData.NEXT_AMBIENT_ANOMALY_TICK, Long.MAX_VALUE);
			record.put(TerminalData.SIGNAL_EVENTS, new ListTag());
			record.putInt(TerminalData.UNREAD_SIGNAL_COUNT, 0);
		});
		TerminalAnomalyLogService.record(player, "phantom_echo", 0, 1, 80, false);
		TerminalAnomalyLogService.record(player, "experience_gap", 2, 2, 120, false);
		var record = data.terminalRecord(player.getUUID()).orElseThrow();
		var entries = TerminalSignalLog.entries(record, SignalBand.UNKNOWN);
		helper.assertValueEqual(entries.size(), 0, "Neither weak nor strong anomalies enter the signal log");
		helper.assertValueEqual(TerminalSignalLog.unreadCount(record), 0,
				"Anomalies do not create unread record attention");
		helper.assertValueEqual(record.getStringOr(TerminalData.ACTIVE_ANOMALY_ID, ""), "experience_gap",
				"Removing terminal history does not remove active anomaly bookkeeping");
		helper.succeed();
	}

	/**
	 * The cascade forces doors open; it no longer deletes them.
	 *
	 * <p>This test used to assert the opposite, and what it was guarding was a genuine swallow: both
	 * halves were replaced with air and the resulting item was explicitly discarded, so a door simply
	 * ceased to exist with nothing left to read it by. On a shared server it was doing that to doors
	 * the target had never touched. Every part of the performance is unchanged - the crack overlay,
	 * the break particles, the zombie's forcing sound - only the outcome is now a door standing open.
	 */
	@GameTest(maxTicks = 80)
	public void doorCascadeForcesDoorsOpenWithoutDestroyingThem(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.setNoGravity(true);
		AnomalyRuntimeService.interrupt(player, false);
		BlockPos origin = player.blockPosition();
		helper.getLevel().setBlockAndUpdate(origin.below(), Blocks.STONE.defaultBlockState());
		player.snapTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
		var lowerState = Blocks.OAK_DOOR.defaultBlockState()
				.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
		FrequencyWorldData worldData = FrequencyWorldData.get(helper.getLevel().getServer());
		Direction selectedDirection = null;
		for (Direction candidate : new Direction[] {Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH}) {
			boolean clear = true;
			for (int distance : new int[] {8, 12, 16, 20}) {
				BlockPos lower = origin.relative(candidate, distance);
				if (AnomalyServerEffects.protectedPosition(helper.getLevel(), worldData, lower)
						|| AnomalyServerEffects.protectedPosition(helper.getLevel(), worldData, lower.above())) {
					clear = false;
					break;
				}
			}
			if (clear) {
				selectedDirection = candidate;
				break;
			}
		}
		helper.assertTrue(selectedDirection != null, "Door fixture finds one unprotected radial lane");
		Direction cascadeDirection = selectedDirection;
		var doors = new java.util.ArrayList<BlockPos>();
		for (int distance : new int[] { 8, 12, 16, 20 }) {
			BlockPos lower = origin.relative(cascadeDirection, distance);
			helper.getLevel().setBlockAndUpdate(lower.below(), Blocks.STONE.defaultBlockState());
			helper.getLevel().setBlock(lower, lowerState, 3);
			Blocks.OAK_DOOR.setPlacedBy(helper.getLevel(), lower, lowerState, player, new ItemStack(Items.OAK_DOOR));
			doors.add(lower);
		}
		helper.runAfterDelay(3, () -> {
			player.snapTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
			helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
					player.getBoundingBox().inflate(22.0)).stream()
					.filter(item -> item.getItem().is(Items.OAK_DOOR)).forEach(net.minecraft.world.entity.Entity::discard);
			helper.assertTrue(AmbientAnomalyService.trigger(player, "door_cascade", false),
					"Multiple ordinary doors within twenty blocks start the cascade");
			helper.runAfterDelay(14, () -> {
				helper.assertTrue(helper.getLevel().getBlockState(doors.getLast())
								.getValue(BlockStateProperties.OPEN),
						"The farthest twenty-block door is forced first");
				helper.assertFalse(helper.getLevel().getBlockState(doors.getFirst())
								.getValue(BlockStateProperties.OPEN),
						"A nearer door is still shut while the cascade advances inward");
			});
			helper.runAfterDelay(55, () -> {
				for (int index = 0; index < doors.size(); index++) {
					BlockPos lower = doors.get(index);
					var forcedLower = helper.getLevel().getBlockState(lower);
					var forcedUpper = helper.getLevel().getBlockState(lower.above());
					helper.assertTrue(forcedLower.getBlock() instanceof net.minecraft.world.level.block.DoorBlock,
							"Lower half " + index + " still exists at " + lower);
					helper.assertTrue(forcedUpper.getBlock() instanceof net.minecraft.world.level.block.DoorBlock,
							"Upper half " + index + " still exists at " + lower.above());
					// Both halves, because a pair whose halves disagree renders as a broken door.
					helper.assertTrue(forcedLower.getValue(BlockStateProperties.OPEN),
							"Lower half " + index + " is forced open at " + lower);
					helper.assertTrue(forcedUpper.getValue(BlockStateProperties.OPEN),
							"Upper half " + index + " is forced open at " + lower.above());
				}
				helper.assertFalse(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
						player.getBoundingBox().inflate(22.0)).stream()
						.anyMatch(item -> item.getItem().is(Items.OAK_DOOR)),
						"Nothing is destroyed, so nothing drops either");
				AnomalyRuntimeService.interrupt(player, false);
				helper.succeed();
			});
		});
	}

	/**
	 * A stage-1 player in the open in daylight keeps getting anomalies after their first one.
	 *
	 * <p>This is the "you see nothing early unless you go mining" regression, reproduced exactly.
	 * Stage 1 offers three entries and two of them are conditional - {@code phantom_echo} needs a
	 * surface, {@code light_dropout} needs night - so above ground at noon the only one that can
	 * start is {@code silent_world}. One success then puts it in the recent-ids list and the seen
	 * mask, both of which narrow the next draw down to the two that cannot happen here, and the
	 * thirty-second retry rebuilt the identical impossible pool for as long as the player stayed
	 * out of a cave.
	 *
	 * <p>Asserted on the product endpoint rather than on the pools: what the player is owed is an
	 * anomaly, and the freshness rules are allowed to decide which one only while one of them can
	 * actually be delivered.
	 */
	@GameTest
	public void daylightSurfaceDrawFallsBackWhenEveryFreshCandidateRefuses(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		AnomalyRuntimeService.interrupt(player, false);
		// Noon, so light_dropout's night precondition refuses regardless of what is lit nearby.
		helper.getLevel().setDayTime(6_000L);
		// A one-block platform in open air: nothing beside the player at foot height, eye height or
		// underfoot, which is what refuses phantom_echo. Lifted clear of neighbouring parallel
		// structures for the same reason the dropout test is.
		BlockPos origin = player.blockPosition().above(40);
		player.snapTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
		helper.getLevel().setBlockAndUpdate(origin.below(), Blocks.STONE.defaultBlockState());
		helper.assertTrue(AnomalyConditions.surfaceTarget(helper.getLevel(), player) == null,
				"The scenario has no surface for the fracture to open in");

		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ListTag recent = new ListTag();
		recent.add(net.minecraft.nbt.StringTag.valueOf("silent_world"));
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.put(TerminalData.ANOMALY_RECENT_IDS, recent);
			tag.putLong(TerminalData.ANOMALY_SEEN_MASK, 1L << AnomalyCatalog.indexOf("silent_world"));
			tag.putLong(TerminalData.NEXT_STRONG_ANOMALY_TICK, 0L);
		});

		helper.assertTrue(AnomalyGameTestBridge.draw(player, 1),
				"A draw whose fresh candidates all refuse still starts an anomaly");
		var active = AnomalyGameTestBridge.active(player);
		helper.assertTrue(active != null, "The fallback draw produced a running instance");
		helper.assertValueEqual(active.id(), "silent_world",
				"The only stage-1 entry this place allows is the one that starts");
		AnomalyGameTestBridge.cleanup(player);
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void lightDropoutExtinguishesAllNearbyLightsAndRestoresThem(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		AnomalyRuntimeService.interrupt(player, false);
		// The dropout only starts at night; without this the precondition rejects the whole test.
		helper.getLevel().setDayTime(18_000L);
		// Isolate this real-light test vertically from protected stations and neighboring parallel structures.
		BlockPos origin = player.blockPosition().above(40);
		player.snapTo(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D, 0.0F, 0.0F);
		// Three kinds, because the dropout now answers each of them differently. Glowstone is the
		// kind with no unlit relative and is deliberately left alone; a sea lantern is the kind that
		// has one and is swapped for it; a torch is a fitting and is cleared.
		BlockPos glowstone = origin.offset(4, 1, 0);
		BlockPos lantern = origin.offset(6, 1, 0);
		BlockPos torch = origin.offset(-4, 1, 0);
		BlockPos campfire = origin.offset(0, 1, 5);
		helper.getLevel().setBlockAndUpdate(torch.below(), Blocks.STONE.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(campfire.below(), Blocks.STONE.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(glowstone, Blocks.GLOWSTONE.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(lantern, Blocks.SEA_LANTERN.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(campfire, Blocks.CAMPFIRE.defaultBlockState()
				.setValue(BlockStateProperties.LIT, true));

		helper.assertTrue(AnomalyGameTestBridge.start(player, "light_dropout", 0x2200B22L, 12),
				"Light dropout starts when any nearby extinguishable light exists");
		helper.assertTrue(helper.getLevel().getBlockState(torch).is(Blocks.TORCH),
				"Lights go out over the extinguish window rather than on the starting frame");

		// The lights go out farthest first across LightDropoutSequence.extinguishWindow, which is
		// four ticks at this duration; every one of them is out well before the held dark.
		helper.runAfterDelay(6, () -> {
			// A hole in a wall is not a light going out. Glowstone has no unlit relative, so it keeps
			// standing and keeps shining rather than being deleted out of somebody's ceiling.
			helper.assertTrue(helper.getLevel().getBlockState(glowstone).is(Blocks.GLOWSTONE),
					"A solid light with no dark twin is left alone rather than removed");
			helper.assertTrue(helper.getLevel().getBlockState(lantern).is(Blocks.PRISMARINE_BRICKS),
					"A solid light with a dark twin becomes it, so the structure survives");
			helper.assertTrue(helper.getLevel().getBlockState(torch).isAir(),
					"Nearby torches are temporarily extinguished");
			helper.assertFalse(helper.getLevel().getBlockState(campfire).getValue(BlockStateProperties.LIT),
					"Lit blocks use their real unlit state");
		});

		helper.runAfterDelay(13, () -> {
			helper.assertTrue(helper.getLevel().getBlockState(lantern).is(Blocks.SEA_LANTERN),
					"A swapped solid light returns to itself after the anomaly");
			helper.assertTrue(helper.getLevel().getBlockState(torch).is(Blocks.TORCH),
					"Torches return after the anomaly");
			helper.assertTrue(helper.getLevel().getBlockState(campfire).getValue(BlockStateProperties.LIT),
					"Lit blocks return to their original state after the anomaly");
			AnomalyRuntimeService.interrupt(player, false);
			for (BlockPos pos : java.util.List.of(glowstone, lantern, torch, torch.below(), campfire, campfire.below())) {
				helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 40)
	public void localRuleCollapseIsClientOnlyAndLeavesServerStateUntouched(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		AnomalyRuntimeService.interrupt(player, false);
		player.getInventory().setItem(8, new ItemStack(Items.IRON_INGOT, 7));
		// Keep the fixture in this test structure's center. Player-relative edge positions can be
		// overwritten by an adjacent parallel GameTest's bounded world construction.
		BlockPos chestPos = helper.absolutePos(new BlockPos(5, 2, 5));
		player.snapTo(chestPos.getX() + 0.5D, chestPos.getY(), chestPos.getZ() + 3.5D, 180.0F, 0.0F);
		helper.getLevel().setBlockAndUpdate(chestPos, Blocks.BARREL.defaultBlockState());
		Container chest = (Container) helper.getLevel().getBlockEntity(chestPos);
		chest.setItem(0, new ItemStack(ModItems.OLD_TERMINAL));
		chest.setItem(1, new ItemStack(Items.DIAMOND, 20));
		helper.assertTrue(AmbientAnomalyService.trigger(player, "local_rule_collapse", false),
				"Client-only trace anomaly starts without a server lease");
		helper.runAfterDelay(8, () -> {
			Container currentChest = (Container) helper.getLevel().getBlockEntity(chestPos);
			helper.assertTrue(helper.getLevel().getBlockState(chestPos).is(Blocks.BARREL), "Container block remains real");
			helper.assertTrue(currentChest.getItem(0).is(ModItems.OLD_TERMINAL), "Story item remains untouched");
			helper.assertValueEqual(currentChest.getItem(1).getCount(), 20, "Container contents remain untouched");
			helper.assertValueEqual(player.getInventory().getItem(8).getCount(), 7, "Inventory remains untouched");
			AnomalyRuntimeService.interrupt(player, false);
			helper.succeed();
		});
	}

	@GameTest
	public void surfacePreconditionRejectsEmptyCandidatesAndSelectsExactNeighbor(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos origin = player.blockPosition();
		Direction[] horizontals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		// The floor beside the player is a target too, so emptying it is part of "there is nothing
		// here to fracture". Clearing only the walls used to be enough because the floor was never
		// probed at all, which is exactly the bug that made phantom_echo unavailable above ground.
		for (Direction direction : horizontals) {
			helper.getLevel().setBlockAndUpdate(origin.relative(direction), Blocks.AIR.defaultBlockState());
			helper.getLevel().setBlockAndUpdate(origin.relative(direction).below(), Blocks.AIR.defaultBlockState());
		}
		player.setYRot(0.0F);
		helper.assertTrue(AnomalyConditions.surfaceTarget(helper.getLevel(), player) == null,
				"No wall and no ground beside the player cancels surface fracture");
		BlockPos floorTarget = origin.relative(player.getDirection()).below();
		helper.getLevel().setBlockAndUpdate(floorTarget, Blocks.STONE.defaultBlockState());
		helper.assertValueEqual(AnomalyConditions.surfaceTarget(helper.getLevel(), player), floorTarget,
				"Open flat ground beside the player is a valid fracture surface");
		BlockPos target = origin.relative(player.getDirection());
		helper.getLevel().setBlockAndUpdate(target, Blocks.STONE.defaultBlockState());
		helper.assertValueEqual(AnomalyConditions.surfaceTarget(helper.getLevel(), player), target,
				"A wall still outranks the ground it stands on");
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void anomalyLogsOnlyAfterValidatedCleanupAndOnlyOnce(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		AnomalyRuntimeService.interrupt(player, false);
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		int before = data.terminalRecord(player.getUUID()).orElseThrow()
				.getIntOr(TerminalData.SIGNAL_EVENT_SEQUENCE, 0);
		helper.assertTrue(AnomalyGameTestBridge.start(player, "local_rule_collapse", 0xDD070DDL, 4),
				"Accelerated anomaly starts");
		var active = AnomalyRuntimeService.active(player);
		helper.assertValueEqual(data.terminalRecord(player.getUUID()).orElseThrow()
				.getIntOr(TerminalData.SIGNAL_EVENT_SEQUENCE, 0), before, "Starting creates no terminal log sequence");
		helper.assertFalse(AnomalyRuntimeService.complete(player,
				new AnomalyCompleteC2S(active.instanceId(), AnomalyCompletionStatus.COMPLETED)),
				"Completion before the earliest tick is rejected");
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(AnomalyRuntimeService.complete(player,
					new AnomalyCompleteC2S(active.instanceId(), AnomalyCompletionStatus.COMPLETED)),
					"Completion after cleanup is accepted");
			helper.assertTrue(active.terminalRecorded(), "Completed instance marks its terminal record exactly once");
			helper.assertFalse(AnomalyRuntimeService.complete(player,
					new AnomalyCompleteC2S(active.instanceId(), AnomalyCompletionStatus.COMPLETED)),
					"Duplicate completion is rejected");
			helper.assertTrue(active.terminalRecorded(), "Duplicate completion cannot clear or repeat the one-time marker");
			helper.succeed();
		});
	}

	@GameTest
	public void debugPanelRejectsArbitraryAnomalyIds(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		helper.assertTrue(DebugPanelService.setEnabled(player, true), "Debug permission enabled for this player");
		int before = TerminalSignalLog.entries(data.terminalRecord(player.getUUID()).orElseThrow(), SignalBand.UNKNOWN).size();
		DebugPanelService.handle(player, new DebugActionPayload("anomaly", "minecraft:kill", 1));
		int after = TerminalSignalLog.entries(data.terminalRecord(player.getUUID()).orElseThrow(), SignalBand.UNKNOWN).size();
		helper.assertValueEqual(after, before, "Arbitrary anomaly IDs never reach the server dispatcher");
		helper.succeed();
	}

	@GameTest
	public void worldDecayUsesAnomalyTiersAndSupportsStageFiveOverride(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putBoolean(TerminalData.BOUND, true);
			record.putInt(TerminalData.ANOMALY_TIER, 1);
		});
		CompoundTag record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(WorldDecayService.stage(data, record), 1, "Binding-level anomaly starts decay stage one");
		data.updateNarrativeState(tag -> tag.putInt("decay_stage_override", 5));
		helper.assertValueEqual(WorldDecayService.stage(data, record), 5, "Debug override reaches decay stage five");
		data.updateNarrativeState(tag -> tag.remove("decay_stage_override"));
		helper.succeed();
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		method.invoke(this, helper);
	}

	private static ItemStack findTerminal(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(ModItems.OLD_TERMINAL)) return stack;
		}
		throw new AssertionError("Mock player had no issued terminal");
	}
}
