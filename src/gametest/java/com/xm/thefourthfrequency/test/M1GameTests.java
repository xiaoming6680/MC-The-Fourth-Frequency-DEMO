package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.ConfiscationService;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.networking.TerminalNavigationPayload;
import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.terminal.TerminalControlPolicy;
import com.xm.thefourthfrequency.terminal.TerminalPage;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.terminal.TerminalStructureTarget;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.TerminalActivityTracker;
import com.xm.thefourthfrequency.world.ZeroStationLayout;
import com.xm.thefourthfrequency.world.ZeroStationService;
import com.xm.thefourthfrequency.world.MineralSurveyPolicy;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.StoryProgressService;
import com.xm.thefourthfrequency.world.StructureNavigationService;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class M1GameTests implements CustomTestMethodInvoker {
	@GameTest
	public void worldOwnsExactlyOneBoundedStationPlan(GameTestHelper helper) {
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		var station = data.stationPosition().orElseThrow(() ->
				new AssertionError("Relay Station Zero was not allocated"));
		// The shape contract itself lives in ZeroStationLayoutTest, which does not need a world.
		// What only a live world can prove is that the allocated centre still yields a plan that
		// fits the tick budget the build service pays out of.
		int placements = ZeroStationLayout.create(station).size();
		helper.assertTrue(placements > 512, "Station must require multiple bounded tick batches");
		helper.assertTrue(placements < 4_096, "Station plan must remain compact");

		// Regression guard: siting once read surface heights without requesting the chunk first, and
		// an unloaded column answers with the world floor rather than failing. That scored bedrock as
		// perfectly level ground and buried the whole station in solid rock. The station's own mast
		// tops out eight above the centre, so anything solid at twelve means it is not on a surface.
		// An absolute Y bound cannot express this: a flat test world sits four blocks off the floor.
		ServerLevel level = helper.getLevel();
		for (int dx = -ZeroStationLayout.HALF_WIDTH; dx <= ZeroStationLayout.HALF_WIDTH;
				dx += ZeroStationLayout.HALF_WIDTH) {
			for (int dz = -ZeroStationLayout.HALF_DEPTH; dz <= ZeroStationLayout.HALF_DEPTH;
					dz += ZeroStationLayout.HALF_DEPTH) {
				BlockPos overhead = station.offset(dx, 12, dz);
				helper.assertTrue(level.getBlockState(overhead).isAir(),
						"Relay Station Zero was sited underground: " + station + " is roofed by "
								+ level.getBlockState(overhead) + " at " + overhead);
			}
		}
		helper.succeed();
	}

	/**
	 * Dying must not put custody stacks on the ground where anybody can pick them up.
	 *
	 * <p>Goes through {@code Inventory#dropAll} rather than through {@code Player#drop} on purpose:
	 * that is the method a death calls, and it is the one the refusal used to miss. The two are
	 * different overloads - {@code dropAll} reaches {@code LivingEntity#drop(ItemStack, boolean,
	 * boolean)} directly - so a test that only exercises the drop key passes while every death on the
	 * server scatters a bound terminal and a barrier placeholder across the floor. The placeholder is
	 * the worse half: custody hands the real weapon back by scanning the <em>owner's</em> inventory,
	 * so one picked up by somebody else never resolves and never leaves theirs.
	 *
	 * <p>Asserted on the return value rather than by sweeping the level for {@code ItemEntity}: the
	 * dispatch is what is under test, and a spatial query around a mock player answers questions about
	 * the fixture instead. The diamonds are the control - without a stack that is <em>supposed</em> to
	 * drop, this would pass just as happily against a build where nothing drops at all.
	 *
	 * <p>{@code drop(stack, true, false)} is not an approximation of the death path, it is that path's
	 * exact call: {@code Inventory#dropAll} passes those two arguments, and the override chain
	 * {@code ServerPlayer.drop -> super -> LivingEntity.drop} is the same one it goes through. Which
	 * method death reaches is pinned separately by {@code MultiplayerIsolationContractTest}.
	 */
	@GameTest
	public void dyingDoesNotScatterTheTerminalOrACustodyPlaceholder(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), tag -> tag.putBoolean(TerminalData.BOUND, true));
		ItemStack boundTerminal = TerminalData.stackFromRecord(
				data.terminalRecord(player.getUUID()).orElseThrow());
		helper.assertTrue(TerminalData.isBound(boundTerminal), "Fixture terminal must be bound");

		ItemEntity control = player.drop(new ItemStack(Items.DIAMOND, 3), true, false);
		helper.assertTrue(control != null,
				"Control: an ordinary stack must still drop, or this test proves nothing");
		control.discard();

		helper.assertTrue(player.drop(boundTerminal, true, false) == null,
				"A bound terminal must never become a pickup for whoever walks past the corpse");
		ItemEntity placeholder = player.drop(ConfiscationService.placeholder(UUID.randomUUID()),
				true, false);
		helper.assertTrue(placeholder == null,
				"A custody placeholder must never leave its owner's inventory: clearPlaceholder only "
						+ "ever scans the owner, so one dropped here resolves for nobody");

		// The other half of a death: whatever the refusal kept out of the world, vanilla still clears
		// from the inventory, and terminal recovery is what puts it back on respawn.
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			inventory.setItem(slot, ItemStack.EMPTY);
		}
		inventory.setItem(0, boundTerminal.copy());
		inventory.dropAll();
		helper.assertTrue(inventory.getItem(0).isEmpty(),
				"dropAll must still empty the slot; the refusal only stops the world from receiving it");
		helper.succeed();
	}

	@GameTest
	public void terminalLedgerIsIdempotentForMultiplePlayers(GameTestHelper helper) {
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		int grantsBefore = data.issuedPlayerCount();

		ServerPlayer first = helper.makeMockServerPlayerInLevel();
		helper.assertTrue(data.hasTerminalIssued(first.getUUID()),
				"JOIN event must issue the first player's terminal");
		helper.assertFalse(ZeroStationService.issueTerminalIfNeeded(first),
				"Repeated join must not issue another terminal");
		helper.assertValueEqual(countTerminals(first), 1, "First player's terminal count");

		ServerPlayer second = helper.makeMockServerPlayerInLevel();
		helper.assertTrue(!first.getUUID().equals(second.getUUID()), "Mock players must have distinct identities");
		helper.assertTrue(data.hasTerminalIssued(second.getUUID()),
				"JOIN event must issue the second player's independent terminal");
		helper.assertFalse(ZeroStationService.issueTerminalIfNeeded(second),
				"Second player's repeated join must also be protected");
		helper.assertValueEqual(countTerminals(second), 1, "Second player's terminal count");
		helper.assertValueEqual(data.issuedPlayerCount(), grantsBefore + 2, "Persistent grant ledger size");
		ItemStack firstTerminal = findTerminal(first);
		ItemStack secondTerminal = findTerminal(second);
		helper.assertTrue(TerminalData.cacheVariant(firstTerminal) != TerminalData.cacheVariant(secondTerminal),
				"Adjacent multiplayer grants must receive different stable handover records");
		helper.assertFalse(TerminalData.secondCacheUnlocked(firstTerminal),
				"The single-player second cache must remain sealed before Fourth Frequency stabilization");
		helper.assertTrue(TerminalData.cacheVariant(firstTerminal) != TerminalData.secondCacheVariant(firstTerminal),
				"The sealed follow-up cache must preserve a distinct handover record");
		helper.assertTrue(TerminalData.unlockSecondCache(firstTerminal),
				"Fourth Frequency stabilization must be able to unlock the single-player follow-up cache");
		helper.assertTrue(TerminalData.secondCacheUnlocked(firstTerminal),
				"The follow-up cache unlock must persist on the terminal item");
		helper.assertFalse(TerminalData.unlockSecondCache(firstTerminal),
				"The follow-up cache unlock must be idempotent");

		TerminalActivityTracker.record(first, TerminalData.MINED_BLOCKS, "mined");
		TerminalActivityTracker.record(first, TerminalData.PLACED_BLOCKS, "placed");
		TerminalActivityTracker.recordCrafted(first, new ItemStack(Items.CRAFTING_TABLE, 2));
		var activity = data.terminalRecord(first.getUUID()).orElseThrow();
		helper.assertValueEqual(activity.getIntOr(TerminalData.MINED_BLOCKS, 0), 1, "Recorded mining events");
		helper.assertValueEqual(activity.getIntOr(TerminalData.PLACED_BLOCKS, 0), 1, "Recorded placement events");
		helper.assertValueEqual(activity.getIntOr(TerminalData.CRAFTED_ITEMS, 0), 2, "Recorded crafted output");
		helper.assertValueEqual(activity.getStringOr(TerminalData.LAST_CRAFTED_ITEM, ""), "minecraft:crafting_table",
				"Last crafted item identity");
		helper.assertValueEqual(activity.getStringOr(TerminalData.LAST_ACTIVITY, ""), "crafted",
				"Last behavior trace");
		helper.succeed();
	}

	@GameTest
	public void terminalRuntimeRemembersPagesAndRejectsGlobalTuning(GameTestHelper helper) {
		ServerPlayer first = helper.makeMockServerPlayerInLevel();
		ServerPlayer second = helper.makeMockServerPlayerInLevel();
		first.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(first));
		second.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(second));

		TerminalRuntimeService.open(first, 0);
		TerminalRuntimeService.open(second, 0);
		helper.assertTrue(TerminalRuntimeService.isOpen(first), "First valid terminal view must open");
		helper.assertTrue(TerminalRuntimeService.isOpen(second), "Second valid terminal view must open independently");

		TerminalRuntimeService.control(first, TerminalControlPayload.VISIT_PAGE, TerminalPage.FILES.ordinal());
		TerminalRuntimeService.control(first, TerminalControlPayload.MODE,
				TerminalControlPolicy.Mode.FILES.ordinal());
		TerminalRuntimeService.control(first, TerminalControlPayload.TUNE, 65);
		TerminalRuntimeService.control(second, TerminalControlPayload.VISIT_PAGE, TerminalPage.TOOLS.ordinal());
		TerminalRuntimeService.control(second, TerminalControlPayload.MODE,
				TerminalControlPolicy.Mode.SIGNAL.ordinal());
		TerminalRuntimeService.control(second, TerminalControlPayload.TUNE, 44);
		TerminalRuntimeService.control(first, TerminalControlPayload.CLOSE, 0);
		TerminalRuntimeService.control(second, TerminalControlPayload.CLOSE, 0);

		helper.assertFalse(TerminalRuntimeService.isOpen(first), "Close must clear the first transient view");
		helper.assertValueEqual(TerminalRuntimeService.rememberedMode(first.getUUID()),
				TerminalControlPolicy.Mode.FILES.ordinal(), "First remembered page");
		helper.assertValueEqual(TerminalRuntimeService.rememberedPage(first.getUUID()),
				TerminalPage.FILES.ordinal(), "First remembered tab");
		helper.assertValueEqual(TerminalRuntimeService.rememberedTuning(first.getUUID()),
				TerminalControlPolicy.DEFAULT_TUNING, "A non-signal page cannot alter the receiver");
		helper.assertValueEqual(TerminalRuntimeService.rememberedMode(second.getUUID()),
				TerminalControlPolicy.Mode.SIGNAL.ordinal(), "Second remembered page");
		// The wire mode collapses home, tools and records into one value, so only the page memory can
		// tell the reopened terminal that this player left the tools grid rather than the home card.
		helper.assertValueEqual(TerminalRuntimeService.rememberedPage(second.getUUID()),
				TerminalPage.TOOLS.ordinal(), "Second remembered tab");
		helper.assertValueEqual(TerminalRuntimeService.rememberedTuning(second.getUUID()),
				TerminalControlPolicy.DEFAULT_TUNING, "The tools grid cannot alter the receiver");

		TerminalRuntimeService.open(first, 0);
		helper.assertTrue(TerminalRuntimeService.isOpen(first), "Reopening must restore the remembered view");
		helper.assertValueEqual(TerminalRuntimeService.rememberedPage(first.getUUID()),
				TerminalPage.FILES.ordinal(), "Reopening must land on the remembered tab");
		TerminalRuntimeService.control(first, TerminalControlPayload.CLOSE, 0);
		helper.succeed();
	}

	@GameTest
	public void optionalResourceGuidancePrecedesButNeverReplacesNarrativeBinding(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ItemStack beforeBinding = findTerminal(player).copy();
		var orePosition = player.blockPosition().below(2);
		helper.getLevel().setBlockAndUpdate(orePosition, Blocks.IRON_ORE.defaultBlockState());
		data.updateTerminalRecord(player.getUUID(), record ->
				record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK, SurvivalMilestone.MINED_LOGS.mask()));
		// The probe is told nothing; it has to hear the iron that was just placed two blocks down.
		helper.assertTrue(ResourceGuidanceService.probeForTesting(player),
				"An unlocked mineral tool with charges must accept a probe");

		var guided = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(guided.getIntOr(TerminalData.SELECTED_RESOURCE, -1),
				TerminalResource.IRON.wireId(), "The probe must name what it actually heard");
		helper.assertValueEqual(guided.getIntOr(TerminalData.MINERAL_PROBE_CHARGES, -1),
				MineralSurveyPolicy.MAX_PROBE_CHARGES - 1, "A probe must cost exactly one charge");
		helper.assertTrue(guided.getBooleanOr(TerminalData.TARGET_LOCATED, false),
				"Optional guidance may locate a real resource before the fourth band is revealed");
		helper.assertValueEqual(guided.getLongOr(TerminalData.TARGET_POSITION, 0L), orePosition.asLong(),
				"Authoritative real resource position");
		helper.assertValueEqual(guided.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Optional resource guidance never changes the fourth-band reveal stage");
		helper.assertFalse(guided.getBooleanOr(TerminalData.BOUND, false),
				"Locating a resource never binds the terminal");
		helper.getLevel().setBlockAndUpdate(orePosition, Blocks.AIR.defaultBlockState());
		ResourceGuidanceService.updatePlayer(player);
		var invalidated = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(!invalidated.getBooleanOr(TerminalData.TARGET_LOCATED, false)
				|| invalidated.getLongOr(TerminalData.TARGET_POSITION, 0L) != orePosition.asLong(),
				"A removed ore block must invalidate the stale target");
		helper.getLevel().setBlockAndUpdate(orePosition, Blocks.IRON_ORE.defaultBlockState());
		helper.assertTrue(ResourceGuidanceService.probeForTesting(player),
				"A second charge must still be available for the re-probe");
		helper.assertTrue(TerminalToolService.startGuidance(player, TerminalTool.MINERALS.slot()),
				"The explicit mineral navigation toggle must accept the located iron target");
		var navigation = TerminalRuntimeService.navigationSnapshot(player);
		helper.assertValueEqual(navigation.protocolVersion(), 6, "Navigation protocol version");
		helper.assertValueEqual(TerminalRuntimeService.navigationSyncTicks(), 4, "Navigation cadence");
		helper.assertValueEqual(navigation.targetKind(), 1, "Iron navigation kind");
		helper.assertTrue(navigation.located() && navigation.navigable(),
				"Located same-dimension resource must activate navigation");
		helper.assertValueEqual(navigation.targetY(), orePosition.getY(), "Navigation target Y");
		helper.assertTrue(player.teleportTo(helper.getLevel(), orePosition.getX() + 0.5,
				orePosition.getY() + 2.0, orePosition.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true),
				"Two-block mineral arrival boundary fixture");
		ResourceGuidanceService.updatePlayer(player);
		helper.assertValueEqual(data.terminalRecord(player.getUUID()).orElseThrow()
						.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1),
				TerminalTool.MINERALS.slot(), "Two blocks away must keep mineral navigation active");

		var netherBeforeBinding = helper.getLevel().getServer().getLevel(Level.NETHER);
		helper.assertTrue(netherBeforeBinding != null, "Nether level for navigation gating");
		helper.assertTrue(player.teleportTo(netherBeforeBinding, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true),
				"Cross-dimension navigation fixture");
		var crossDimensionNavigation = TerminalRuntimeService.navigationSnapshot(player);
		helper.assertTrue(crossDimensionNavigation.located(), "Cross-dimension target remains located");
		helper.assertFalse(crossDimensionNavigation.navigable(), "Cross-dimension target needle must be disabled");
		helper.assertTrue(player.teleportTo(helper.getLevel(), orePosition.getX() + 0.5, orePosition.getY() + 1.0,
				orePosition.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true), "Return to resource dimension");

		player.getInventory().add(new ItemStack(Items.RAW_IRON));
		ResourceGuidanceService.updatePlayer(player);
		var accepted = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertFalse(accepted.getBooleanOr(TerminalData.BOUND, false),
				"Following optional advice does not itself bind the terminal");
		helper.assertValueEqual(accepted.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Following optional advice leaves narrative reveal state unchanged");
		helper.assertTrue(accepted.getStringOr(TerminalData.ACCEPTED_ADVICE, "").contains("iron"),
				"Accurate advice still becomes part of the terminal's long-term player model");
		helper.assertValueEqual(accepted.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1),
				TerminalToolService.NO_TOOL, "Reaching the mineral within one block must stop navigation");
		helper.assertValueEqual(accepted.getIntOr(TerminalData.SELECTED_RESOURCE, -1),
				TerminalResource.NONE.wireId(), "Mineral arrival must clear the current resource target");
		helper.assertFalse(NavigationState.read(accepted).located(),
				"Mineral arrival must clear the concrete target position");
		helper.assertTrue(accepted.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false)
						&& !accepted.getBooleanOr(TerminalData.MINERAL_SURVEY_NEARBY, false),
				"The reached ore must stay silently suppressed until the player leaves its survey episode");

		SurvivalProgressService.updatePlayer(player, data);
		StoryProgressService.update(player, data);
		var bound = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(bound.getBooleanOr(TerminalData.BOUND, false),
				"A real home or iron milestone completes narrative binding without calibration");
		helper.assertValueEqual(bound.getIntOr(TerminalData.BAND_STAGE, 0), 0,
				"Personal binding still precedes the hidden fourth-band prelude");
		helper.assertTrue(bound.getBooleanOr(TerminalData.SECOND_CACHE_UNLOCKED, false),
				"Narrative binding unlocks the next predecessor note");
		helper.assertTrue(TerminalData.isBound(findTerminal(player)),
				"Authoritative binding must be synchronized to the carried item");
		var completedNavigation = TerminalRuntimeService.navigationSnapshot(player);
		helper.assertFalse(completedNavigation.navigable(),
				"Binding must not restore a mineral navigation that already completed");
		helper.assertValueEqual(completedNavigation.targetKind(), TerminalNavigationPayload.NONE,
				"Completed mineral navigation must no longer expose a compass target");
		helper.assertTrue(player.drop(findTerminal(player).copy(), false) == null,
				"Bound terminal drop must be rejected before an ItemEntity is created");
		ChestMenu chest = ChestMenu.oneRow(91, player.getInventory());
		chest.setCarried(findTerminal(player).copy());
		chest.clicked(0, 0, ClickType.PICKUP, player);
		helper.assertTrue(chest.getContainer().getItem(0).isEmpty(),
				"Bound terminal must not enter an external container");
		helper.assertTrue(TerminalData.isBound(chest.getCarried()),
				"Rejected container transfer must leave the terminal on the cursor");
		chest.setCarried(ItemStack.EMPTY);

		var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
		helper.assertTrue(nether != null, "Nether level must exist for cross-dimension verification");
		helper.assertTrue(player.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true),
				"Cross-dimension teleport");
		TerminalLifecycleService.recordCurrentDimension(player);
		var crossDimension = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(crossDimension.getStringOr(TerminalData.VISITED_DIMENSIONS, "")
				.contains("minecraft:the_nether"), "Cross-dimension history must persist");
		helper.assertTrue(data.isValidTerminal(findTerminal(player), player.getUUID()),
				"Terminal must stay valid across dimensions");

		int previousGeneration = TerminalData.copyGeneration(findTerminal(player));
		removeTerminals(player);
		ServerPlayer respawned = helper.getLevel().getServer().getPlayerList()
				.respawn(player, false, Entity.RemovalReason.KILLED);
		helper.assertTrue(respawned != player, "Vanilla respawn must replace the server player instance");
		helper.assertTrue(respawned.getUUID().equals(player.getUUID()), "Respawn must retain player identity");
		ItemStack recovered = findTerminal(respawned);
		helper.assertValueEqual(TerminalData.copyGeneration(recovered), previousGeneration + 1,
				"Bound terminal must be restored by the real respawn event chain");
		helper.assertFalse(data.isValidTerminal(beforeBinding, player.getUUID()),
				"Pre-binding copy must no longer be an exploitable valid terminal");
		helper.succeed();
	}

	@GameTest
	public void mineralRefreshIsWeightedServerOnlyAndHasAThreeSecondCooldown(GameTestHelper helper) {
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ServerPlayer miner = helper.makeMockServerPlayerInLevel();
		data.updateTerminalRecord(miner.getUUID(), record -> record.putInt(
				TerminalData.SURVIVAL_MILESTONE_MASK,
				SurvivalMilestone.MINED_LOGS.mask() | SurvivalMilestone.RETURNED_NETHER.mask()));
		miner.getInventory().add(new ItemStack(Items.IRON_INGOT));
		miner.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
		helper.assertFalse(TerminalToolService.selectResource(miner, TerminalResource.DIAMOND.wireId()),
				"Clients can no longer force a mineral category");
		helper.assertTrue(TerminalToolService.requestRescan(miner),
				"The unlocked mineral tool accepts one authoritative refresh");
		var refreshed = data.terminalRecord(miner.getUUID()).orElseThrow();
		TerminalResource selected = TerminalResource.fromWire(
				refreshed.getIntOr(TerminalData.SELECTED_RESOURCE, TerminalResource.NONE.wireId()));
		// The category is no longer drawn up front and then hunted for. The probe reports whatever it
		// actually finds, so a refresh must leave the category unresolved until the scan commits.
		helper.assertValueEqual(selected, TerminalResource.NONE,
				"Refresh must leave the mineral category unresolved until the probe commits");
		helper.assertValueEqual(refreshed.getLongOr(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L)
				- miner.level().getGameTime(), 60L, "Refresh must create an exact three-second probe window");
		helper.assertFalse(refreshed.getBooleanOr(TerminalData.TARGET_LOCATED, false),
				"The previous concrete ore target must be hidden during the probe window");
		data.updateTerminalRecord(miner.getUUID(), record -> record.putLong(
				TerminalData.MINERAL_SCAN_READY_GAME_TIME, Math.max(1L, miner.level().getGameTime())));
		helper.assertValueEqual(TerminalToolService.snapshot(miner, TerminalTool.MINERALS.slot())
						.mineralScanTicks(), 1,
				"The UI must remain in its scanning state at the reveal boundary until the scan result commits");
		helper.assertFalse(TerminalToolService.requestRescan(miner),
				"The server rejects another refresh until the pending scan result commits");
		helper.assertFalse(TerminalToolService.startGuidance(miner, TerminalTool.MINERALS.slot()),
				"Mineral guidance must stay unavailable until a concrete ore block is located");
		helper.succeed();
	}

	@GameTest
	public void automaticSurveyReportsOnlyHighValueOreAndItsReadingOutlivesTheEpisode(GameTestHelper helper) {
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos common = player.blockPosition().below(2);
		BlockPos valuable = player.blockPosition().below(3);
		// Every mock player spawns on the world origin, so the blocks the survey will sweep are
		// shared with the rest of the batch. Clearing them is what makes "nothing worth reporting"
		// a real starting state instead of whatever the previous test happened to leave behind.
		clearSurveyRange(helper.getLevel(), player.blockPosition());
		helper.getLevel().setBlockAndUpdate(common, Blocks.IRON_ORE.defaultBlockState());
		data.updateTerminalRecord(player.getUUID(), record -> record.putInt(
				TerminalData.SURVIVAL_MILESTONE_MASK, SurvivalMilestone.MINED_LOGS.mask()));
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));

		TerminalRuntimeService.open(player, 0);
		helper.assertTrue(TerminalRuntimeService.isOpen(player), "Fixture terminal must be open");
		ResourceGuidanceService.updatePlayer(player);
		helper.assertFalse(data.terminalRecord(player.getUUID()).orElseThrow()
						.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false),
				"An open terminal must pause automatic mineral scanning");
		TerminalRuntimeService.control(player, TerminalControlPayload.CLOSE, 0);

		ResourceGuidanceService.updatePlayer(player);
		var ordinary = data.terminalRecord(player.getUUID()).orElseThrow();
		BlockPos reported = BlockPos.of(ordinary.getLongOr(TerminalData.MINERAL_SURVEY_POSITION, 0L));
		helper.assertFalse(ordinary.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false),
				"Iron beneath the player's feet is not worth a survey and must not open an episode;"
						+ " the survey instead reported " + helper.getLevel().getBlockState(reported)
						+ " at " + reported + " while the player stood at " + player.blockPosition());
		helper.assertValueEqual(ordinary.getIntOr(TerminalData.MINERAL_READING_KIND,
				ResourceGuidanceService.READING_NONE), ResourceGuidanceService.READING_NONE,
				"Ordinary ore must leave the mineral tool empty");

		helper.getLevel().setBlockAndUpdate(valuable, Blocks.DIAMOND_ORE.defaultBlockState());
		// The sweep that just found nothing parks itself for five seconds; a single-tick test has to
		// stand in for that wait.
		ResourceGuidanceService.forgetAutoScanForTesting(player);
		ResourceGuidanceService.updatePlayer(player);
		var surveyed = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertTrue(surveyed.getBooleanOr(TerminalData.MINERAL_SURVEY_NEARBY, false),
				"Diamond inside the survey range must be reported without any roll");
		helper.assertValueEqual(surveyed.getIntOr(TerminalData.MINERAL_READING_KIND, -1),
				ResourceGuidanceService.READING_EXACT, "A survey hit must become an exact reading");
		var located = NavigationState.read(surveyed);
		helper.assertTrue(located.located(), "A survey hit must be immediately navigable");
		helper.assertValueEqual(located.position(), valuable.asLong(), "Surveyed ore position");
		helper.assertValueEqual(located.kind(), TerminalResource.DIAMOND.id(), "Surveyed ore kind");
		var snapshot = TerminalToolService.snapshot(player, TerminalToolService.NO_TOOL);
		helper.assertValueEqual(snapshot.protocolVersion(),
				TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION, "Nearby mineral tool snapshot protocol");
		helper.assertValueEqual(snapshot.recommendedPrimaryTool(), TerminalTool.MINERALS.slot(),
				"The nearby mineral state must promote the mineral shortcut");

		// The regression this guards: the reveal used to live only while the player stood inside
		// the five-block episode, so walking on - which takes about a second - emptied the tool
		// before they could open it and act on what they had just been told about.
		helper.assertTrue(player.teleportTo(helper.getLevel(), valuable.getX() + 12.5,
				valuable.getY() + 2.0, valuable.getZ() + 0.5, Set.of(), 0.0F, 0.0F, true),
				"Walk out of the survey episode");
		ResourceGuidanceService.updatePlayer(player);
		var afterLeaving = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertFalse(afterLeaving.getBooleanOr(TerminalData.MINERAL_SURVEY_PROXIMITY, false),
				"Leaving the range must end the episode so a later ore can be reported");
		helper.assertValueEqual(afterLeaving.getIntOr(TerminalData.MINERAL_READING_KIND, -1),
				ResourceGuidanceService.READING_EXACT, "The reading itself must survive leaving the episode");
		// The needle only describes a target while the tool is open or already guiding, so the
		// preview is read the way a player reaches it: open the terminal on the mineral page.
		TerminalRuntimeService.open(player, 0);
		TerminalRuntimeService.control(player, TerminalControlPayload.SELECT_TOOL, TerminalTool.MINERALS.slot());
		var preview = TerminalRuntimeService.navigationSnapshot(player);
		helper.assertValueEqual(preview.targetKind(), TerminalNavigationPayload.DIAMOND,
				"The surveyed mineral must still describe itself after the episode ended");
		helper.assertTrue(preview.located() && preview.navigable(),
				"The surveyed target must still be navigable after the episode ended");
		helper.assertTrue(TerminalToolService.startGuidance(player, TerminalTool.MINERALS.slot()),
				"Guidance must accept a survey reading taken before the player walked on");
		TerminalRuntimeService.control(player, TerminalControlPayload.CLOSE, 0);

		helper.getLevel().setBlockAndUpdate(valuable, Blocks.AIR.defaultBlockState());
		ResourceGuidanceService.updatePlayer(player);
		helper.assertValueEqual(data.terminalRecord(player.getUUID()).orElseThrow()
						.getIntOr(TerminalData.MINERAL_READING_KIND, -1),
				ResourceGuidanceService.READING_NONE, "Mining the surveyed ore out must clear the reading");
		helper.succeed();
	}

	/**
	 * Pins the three layers of structure arrival against a real, registered structure.
	 *
	 * <p>The located target cannot carry height - vanilla reports every structure at y=0 - so the
	 * vertical half of the test has to come from the generated structure instead. That is not
	 * something a pure policy test can reach, hence a GameTest: it plants a genuine
	 * {@code StructureStart} in the world and then drives the same {@code StructureManager} calls
	 * production uses.</p>
	 */
	@GameTest
	public void structureArrivalUsesTheRealVerticalExtentWhenTheWorldCanSupplyIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// Arrival is a pure function of two positions, so this drives it with synthetic ones rather
		// than a mock player: every mock player spawns on the world origin, which the other tests in
		// the batch share, and the test world's floor leaves no room to bury anything beneath one.
		BlockPos anchor = helper.absolutePos(BlockPos.ZERO);
		// Built the way selectTarget builds it: true horizontal position, placeholder height.
		BlockPos target = new BlockPos(anchor.getX() + 8, 0, anchor.getZ() + 8);
		int boxBottom = level.getMinY() + 10;
		int boxTop = boxBottom + 20;
		BlockPos surface = new BlockPos(anchor.getX(), boxTop + 60, anchor.getZ());

		helper.assertTrue(StructureNavigationService.arrived(level, surface, target,
						TerminalStructureTarget.FORTRESS),
				"With nothing generated to measure, the horizontal radius must still decide alone");

		int reachEast = 60;
		plantStructure(level, target, TerminalStructureTarget.FORTRESS, boxBottom, boxTop, reachEast);
		// A miss is StructureStart.INVALID_START rather than null, so validity is the real test.
		helper.assertFalse(level.structureManager().getStructureWithPieceAt(surface,
						TerminalStructureTarget.FORTRESS.structureTag()).isValid(),
				"Fixture failed: the surface position already sits inside a structure of this tag");
		helper.assertTrue(level.structureManager().getStructureWithPieceAt(
						new BlockPos(target.getX(), (boxBottom + boxTop) / 2, target.getZ()),
						TerminalStructureTarget.FORTRESS.structureTag()).isValid(),
				"Fixture failed: the planted structure is not visible to StructureManager at all");
		helper.assertTrue(!level.structureManager()
						.startsForStructure(new ChunkPos(target), candidate -> true).isEmpty(),
				"Fixture failed: no structure start is reachable from the target chunk at all");
		helper.assertTrue(!level.structureManager()
						.startsForStructure(new ChunkPos(target), taggedStructures(level,
								TerminalStructureTarget.FORTRESS)::contains).isEmpty(),
				"Fixture failed: the planted start is not matched by the target's own structure tag");

		helper.assertFalse(StructureNavigationService.arrived(level, surface, target,
						TerminalStructureTarget.FORTRESS),
				"A structure " + (surface.getY() - boxTop) + " blocks down must not count as reached from"
						+ " the surface above it");
		helper.assertTrue(StructureNavigationService.arrived(level,
						new BlockPos(target.getX(), boxTop + StructureNavigationService.VERTICAL_ARRIVAL_TOLERANCE,
								target.getZ()), target, TerminalStructureTarget.FORTRESS),
				"The tolerance band directly above the structure still counts as reaching it");
		helper.assertFalse(StructureNavigationService.arrived(level,
						new BlockPos(target.getX(), boxTop + StructureNavigationService.VERTICAL_ARRIVAL_TOLERANCE + 1,
								target.getZ()), target, TerminalStructureTarget.FORTRESS),
				"One block past the tolerance band must not count");

		// Far outside the horizontal radius but inside a corridor: the piece test has to win, or a
		// player standing in the mineshaft would be told they had not found it yet.
		BlockPos deepInside = new BlockPos(target.getX() + reachEast - 2,
				(boxBottom + boxTop) / 2, target.getZ());
		helper.assertTrue(deepInside.getX() - target.getX() > TerminalStructureTarget.FORTRESS.arrivalRadius(),
				"Fixture must place the player beyond the horizontal radius");
		helper.assertTrue(StructureNavigationService.arrived(level, deepInside, target,
						TerminalStructureTarget.FORTRESS),
				"Standing inside one of the structure's own pieces is arrival outright");
		helper.succeed();
	}

	/** Empties the passive survey's whole sweep radius so a test starts from "nothing to report". */
	private static void clearSurveyRange(ServerLevel level, BlockPos centre) {
		int reach = MineralSurveyPolicy.RANGE + 1;
		for (BlockPos position : BlockPos.betweenClosed(centre.offset(-reach, -reach, -reach),
				centre.offset(reach, reach, reach))) {
			if (!level.isOutsideBuildHeight(position)) {
				level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
			}
		}
	}

	private static Set<Structure> taggedStructures(ServerLevel level, TerminalStructureTarget which) {
		Registry<Structure> registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Set<Structure> tagged = new java.util.HashSet<>();
		for (Holder<Structure> holder : registry.getTagOrEmpty(which.structureTag())) tagged.add(holder.value());
		return tagged;
	}

	/** Registers a real StructureStart with a known box so arrival has genuine geometry to read. */
	private static void plantStructure(ServerLevel level, BlockPos target, TerminalStructureTarget which,
			int minY, int maxY, int reachEast) {
		Structure structure = null;
		for (Structure candidate : taggedStructures(level, which)) {
			structure = candidate;
			break;
		}
		if (structure == null) throw new AssertionError("No structure is registered under " + which.id());
		BoundingBox box = new BoundingBox(target.getX() - 4, minY, target.getZ() - 4,
				target.getX() + reachEast, maxY, target.getZ() + 4);
		ChunkPos startChunk = new ChunkPos(target);
		StructureStart start = new StructureStart(structure, startChunk, 0,
				new PiecesContainer(List.of(new StubPiece(box))));
		StructureManager manager = level.structureManager();
		ChunkAccess startChunkAccess = level.getChunk(startChunk.x, startChunk.z);
		manager.setStartForStructure(SectionPos.bottomOf(startChunkAccess), structure, start, startChunkAccess);
		// References are what getAllStructuresAt reads, so every chunk the box spans needs one.
		for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
			for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
				ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
				manager.addReferenceForStructure(SectionPos.bottomOf(chunk), structure,
						startChunk.toLong(), chunk);
			}
		}
	}

	/** A piece that exists only to carry a bounding box; it is never generated or serialized. */
	private static final class StubPiece extends StructurePiece {
		private StubPiece(BoundingBox box) {
			super(StructurePieceType.MINE_SHAFT_CORRIDOR, 0, box);
		}

		@Override
		protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
		}

		@Override
		public void postProcess(WorldGenLevel level, net.minecraft.world.level.StructureManager manager,
				ChunkGenerator generator, RandomSource random, BoundingBox box, ChunkPos chunkPos,
				BlockPos origin) {
		}
	}

	@GameTest
	public void structureNavigationCompletesInsideThePerStructureRadiusAndBecomesAPersistentPrompt(
			GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		String dimension = player.level().dimension().identifier().toString();
		// Fifty blocks out: inside the single radius every structure used to share, outside the one a
		// village now gets. Nothing is generated up here, so the horizontal test decides alone.
		BlockPos tooFar = player.blockPosition().offset(30, 200, 40);
		BlockPos destination = player.blockPosition().offset(28, 200, 36);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK, SurvivalMilestone.MINED_LOGS.mask());
			record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalTool.NAVIGATION.slot());
			new NavigationState(TerminalStructureTarget.VILLAGE.id(), "", true,
					TerminalStructureTarget.VILLAGE.id(), tooFar.asLong(), dimension,
					player.level().getGameTime()).writeTo(record);
		});
		StructureNavigationService.updatePlayer(player);
		helper.assertValueEqual(data.terminalRecord(player.getUUID()).orElseThrow()
						.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1), TerminalTool.NAVIGATION.slot(),
				"Fifty blocks is past a village's own arrival radius and must not complete");

		data.updateTerminalRecord(player.getUUID(), record ->
				new NavigationState(TerminalStructureTarget.VILLAGE.id(), "", true,
						TerminalStructureTarget.VILLAGE.id(), destination.asLong(), dimension,
						player.level().getGameTime()).writeTo(record));

		StructureNavigationService.updatePlayer(player);
		var completed = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(completed.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1),
				TerminalToolService.NO_TOOL, "Arrival must stop structure guidance");
		helper.assertFalse(NavigationState.read(completed).located(),
				"The completed destination must leave the active navigation state");
		// Arrival is announced once through the notice stack and leaves nothing to dismiss; a card
		// waiting to be closed by hand made a finished event look like an outstanding one.
		helper.assertFalse(completed.getBooleanOr(TerminalData.NAVIGATION_COMPLETION_ACTIVE, false),
				"Arrival must not leave a card the player has to close");
		helper.assertFalse(completed.getBooleanOr(TerminalData.NAVIGATION_COMPLETION_UNREAD, false),
				"Arrival must not put the carried terminal into its unread prompt style");
		helper.assertTrue((completed.getIntOr(TerminalData.COMPLETED_STRUCTURE_TARGETS_MASK, 0)
				& TerminalStructureTarget.bit(TerminalStructureTarget.VILLAGE)) != 0,
				"The arrived structure type must be persisted as completed");
		helper.assertTrue((StructureNavigationService.availableTargetsMask(player, completed)
				& TerminalStructureTarget.bit(TerminalStructureTarget.VILLAGE)) == 0,
				"A completed destination must not reappear in navigation candidates");
		helper.assertFalse(TerminalToolService.snapshot(player, TerminalTool.NAVIGATION.slot())
						.navigationCompletionAvailable(),
				"The retired completion card must never be advertised to the client again");
		var nether = helper.getLevel().getServer().getLevel(Level.NETHER);
		helper.assertTrue(nether != null, "Nether level must exist for dimension-specific targets");
		helper.assertTrue(player.teleportTo(nether, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true),
				"Dimension-specific navigation fixture");
		var netherRecord = data.terminalRecord(player.getUUID()).orElseThrow();
		int netherTargets = StructureNavigationService.availableTargetsMask(player, netherRecord);
		helper.assertTrue((netherTargets & TerminalStructureTarget.bit(TerminalStructureTarget.FORTRESS)) != 0,
				"Entering the Nether must immediately offer a fortress");
		helper.assertTrue((netherTargets & TerminalStructureTarget.bit(TerminalStructureTarget.VILLAGE)) == 0,
				"Overworld structures must not leak into the Nether target list");
		var end = helper.getLevel().getServer().getLevel(Level.END);
		helper.assertTrue(end != null, "End level must exist for exclusion verification");
		helper.assertTrue(player.teleportTo(end, 0.5, 64.0, 0.5, Set.of(), 0.0F, 0.0F, true),
				"End exclusion fixture");
		helper.assertValueEqual(StructureNavigationService.availableTargetsMask(
				player, data.terminalRecord(player.getUUID()).orElseThrow()), 0,
				"The End must not expose structure-navigation candidates");
		helper.succeed();
	}

	@GameTest
	public void toolSnapshotUsesRealStateAndGuidanceReplacementKeepsSavedPlaces(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		var home = player.blockPosition().offset(7, 0, -5);
		var portal = player.blockPosition().offset(-12, 3, 9);
		var stronghold = player.blockPosition().offset(1_200, -20, 400);
		String dimension = player.level().dimension().identifier().toString();
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putLong(TerminalData.HOME_POSITION, home.asLong());
			record.putString(TerminalData.HOME_DIMENSION, dimension);
			record.putLong(TerminalData.LAST_PORTAL_POSITION, portal.asLong());
			record.putString(TerminalData.LAST_PORTAL_DIMENSION, dimension);
			record.putInt(TerminalData.CRAFTED_EYE_COUNT,
					SurvivalProgressService.REQUIRED_STRONGHOLD_UNLOCK_EYES);
			record.putInt(TerminalData.EYE_SAMPLE_COUNT, SurvivalProgressService.REQUIRED_EYE_SAMPLES);
			record.putLong(TerminalData.STRONGHOLD_POSITION, stronghold.asLong());
			record.putString(TerminalData.STRONGHOLD_DIMENSION, dimension);
		});
		var snapshot = TerminalToolService.snapshot(player, TerminalTool.HOME.slot());
		helper.assertValueEqual(snapshot.protocolVersion(),
				TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION, "Independent tool snapshot protocol");
		helper.assertFalse(snapshot.homeKnown(),
				"Legacy manual home coordinates must not replace the player's real respawn point");
		helper.assertTrue(snapshot.portalKnown() && snapshot.portalSameDimension(), "Portal arrival must be real and local");
		helper.assertValueEqual(snapshot.weather(), player.level().isThundering() ? 2
				: player.level().isRaining() ? 1 : 0, "Tool weather must match the server level");
		helper.assertValueEqual(snapshot.eyeSampleCount(), SurvivalProgressService.REQUIRED_EYE_SAMPLES,
				"Eye sample count");
		helper.assertTrue(snapshot.strongholdKnown() && snapshot.strongholdMaxDistance()
				> snapshot.strongholdMinDistance(), "Three samples must yield a bounded, non-exact range");
		helper.assertTrue(Math.hypot(snapshot.strongholdDx(), snapshot.strongholdDz()) < 150.0D,
				"The client bearing must be a direction vector, not the exact stronghold coordinate delta");

		helper.assertTrue(TerminalToolService.startGuidance(player, TerminalTool.PORTAL.slot()),
				"A real portal arrival can control the compass");
		var replaced = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(replaced.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1),
				TerminalTool.PORTAL.slot(), "Exactly one compass owner remains");
		helper.assertValueEqual(replaced.getLongOr(TerminalData.HOME_POSITION, 0L), home.asLong(),
				"Replacing guidance must preserve home");
		helper.assertValueEqual(replaced.getLongOr(TerminalData.LAST_PORTAL_POSITION, 0L), portal.asLong(),
				"Replacing guidance must preserve portal arrival");
		helper.assertTrue(TerminalToolService.stopGuidance(player, 0), "Guidance can stop cleanly");
		helper.assertValueEqual(data.terminalRecord(player.getUUID()).orElseThrow()
				.getIntOr(TerminalData.ACTIVE_GUIDANCE_TOOL, -1), TerminalToolService.NO_TOOL,
				"Stopping guidance clears only the active pointer");
		helper.succeed();
	}

	@GameTest
	public void legacyAutomaticTuningControlIsReservedAndIgnored(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.CALIBRATED_BANDS_MASK, 0b111);
			record.putBoolean(TerminalData.AUTO_TUNING, false);
		});
		player.setItemInHand(InteractionHand.MAIN_HAND, findTerminal(player));
		TerminalRuntimeService.open(player, 0);
		TerminalRuntimeService.control(player, TerminalControlPayload.SET_AUTO_TUNING, 1);
		var record = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(record.getIntOr(TerminalData.CALIBRATED_BANDS_MASK, 0), 0b111,
				"Legacy calibration data remains readable for old saves");
		helper.assertFalse(record.getBooleanOr(TerminalData.AUTO_TUNING, true),
				"Reserved automatic-tuning input cannot reactivate the retired system");
		TerminalRuntimeService.control(player, TerminalControlPayload.CLOSE, 0);
		helper.succeed();
	}

	@GameTest
	public void boundRecoveryWaitsForSafeInventorySpace(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalLifecycleService.ensureCarried(player, false);
		int generation = TerminalData.copyGeneration(findTerminal(player));
		removeTerminals(player);
		for (int slot = 0; slot < player.getInventory().getNonEquipmentItems().size(); slot++) {
			player.getInventory().getNonEquipmentItems().set(slot, new ItemStack(Items.STONE, 64));
		}
		player.getInventory().setChanged();
		helper.assertValueEqual(player.getInventory().getFreeSlot(), -1, "Fixture must fill every recovery-safe slot");
		int stoneBefore = countItem(player, Items.STONE);

		helper.assertFalse(TerminalLifecycleService.ensureCarried(player, false),
				"Bound recovery must wait when no inventory slot is safe");
		helper.assertValueEqual(countTerminals(player), 0, "No terminal may overwrite a full slot");
		helper.assertValueEqual(countItem(player, Items.STONE), stoneBefore,
				"Failed recovery must preserve every existing item");

		player.getInventory().getNonEquipmentItems().set(0, ItemStack.EMPTY);
		helper.assertTrue(TerminalLifecycleService.ensureCarried(player, false),
				"Pending recovery must complete after a slot becomes available");
		helper.assertValueEqual(countTerminals(player), 1, "Exactly one pending recovery terminal");
		helper.assertValueEqual(TerminalData.copyGeneration(findTerminal(player)), generation + 1,
				"Waiting retries must not create additional terminal generations");
		helper.assertValueEqual(countItem(player, Items.STONE), stoneBefore - 64,
				"Only the explicitly cleared stack may be replaced");
		helper.succeed();
	}

	@GameTest
	public void unboundRecoveryInvalidatesDuplicatesWithoutForcingStorageReturn(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		ItemStack original = findTerminal(player).copy();
		player.getInventory().add(original.copy());
		TerminalLifecycleService.ensureCarried(player, true);
		helper.assertValueEqual(countTerminals(player), 1, "Duplicate valid terminals after reconciliation");

		int generation = TerminalData.copyGeneration(findTerminal(player));
		removeTerminals(player);
		helper.assertFalse(TerminalLifecycleService.ensureCarried(player, false),
				"An unbound terminal may remain dropped or stored during the active session");
		helper.assertValueEqual(countTerminals(player), 0, "Unbound storage state");
		helper.assertTrue(TerminalLifecycleService.ensureCarried(player, true),
				"Reconnect or explicit recovery must restore an unbound terminal");
		ItemStack recovered = findTerminal(player);
		helper.assertValueEqual(TerminalData.copyGeneration(recovered), generation + 1,
				"Unbound recovery copy generation");
		helper.assertFalse(data.isValidTerminal(original, player.getUUID()),
				"Previous physical copies must be invalid after recovery");
		helper.assertTrue(data.isValidTerminal(recovered, player.getUUID()),
				"Exactly one recovered copy must remain authoritative");
		int recoveredGeneration = TerminalData.copyGeneration(recovered);
		helper.assertTrue(TerminalLifecycleService.adminRepair(player),
				"Administrator repair must work from the authoritative record");
		helper.assertValueEqual(countTerminals(player), 1, "Administrator repair terminal count");
		helper.assertValueEqual(TerminalData.copyGeneration(findTerminal(player)), recoveredGeneration + 1,
				"Administrator repair copy generation");
		helper.succeed();
	}

	@GameTest
	public void boundTerminalShiftClicksBetweenOwnInventorySlots(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalRuntimeService.synchronizeProjection(player, data);
		ItemStack terminal = findTerminal(player).copy();
		helper.assertTrue(TerminalData.isBound(terminal), "Fixture must hold a bound terminal");
		removeTerminals(player);
		player.getInventory().setItem(9, terminal);

		// Menu slot 9 is inventory slot 9, the first backpack row. InventoryMenu#quickMoveStack sends
		// it to the hotbar and can reach nothing but the player's own slots, so the container guard
		// has no business refusing it - refusing it left a bound terminal unable to be rearranged.
		player.inventoryMenu.clicked(9, 0, ClickType.QUICK_MOVE, player);

		helper.assertValueEqual(countTerminals(player), 1,
				"Rearranging inside the backpack must not consume the terminal");
		helper.assertFalse(player.getInventory().getItem(9).is(ModItems.OLD_TERMINAL),
				"Bound terminal must leave the slot it was shift-clicked out of");
		int hotbarSlot = -1;
		for (int slot = 0; slot < 9; slot++) {
			if (player.getInventory().getItem(slot).is(ModItems.OLD_TERMINAL)) hotbarSlot = slot;
		}
		helper.assertTrue(hotbarSlot >= 0, "Bound terminal must arrive in the hotbar");

		// The rule this must still honour: the same click into an open chest stays refused.
		ChestMenu chest = ChestMenu.oneRow(92, player.getInventory());
		chest.clicked(chest.slots.size() - 9 + hotbarSlot, 0, ClickType.QUICK_MOVE, player);
		helper.assertTrue(chest.getContainer().isEmpty(),
				"Bound terminal must still refuse transfer into an external container");
		helper.assertValueEqual(countTerminals(player), 1,
				"A refused container transfer must leave the terminal with the player");
		helper.succeed();
	}

	@GameTest
	public void boundTerminalCannotBeDragDistributedIntoContainer(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalRuntimeService.synchronizeProjection(player, data);
		ItemStack terminal = findTerminal(player).copy();
		helper.assertTrue(TerminalData.isBound(terminal), "Fixture must hold a bound terminal");
		removeTerminals(player);

		// Drag-distributing onto a single slot is the route a plain click into a container cannot
		// take: vanilla collapses it back into an ordinary PICKUP, which must be refused just the same.
		ChestMenu chest = ChestMenu.oneRow(93, player.getInventory());
		chest.setCarried(terminal);
		dragDistribute(chest, player, 0);

		helper.assertTrue(chest.getContainer().getItem(0).isEmpty(),
				"Drag-distribute must not smuggle a bound terminal into an external container");
		helper.assertTrue(TerminalData.isBound(chest.getCarried()),
				"A refused drag must leave the terminal on the cursor");

		// The refusal cancels clicks in the middle of vanilla's three-stage drag protocol, so prove it
		// does not wedge that state machine: the very next drag in the same menu must behave normally.
		chest.setCarried(new ItemStack(Items.STONE, 2));
		dragDistribute(chest, player, 1, 2);
		helper.assertValueEqual(chest.getContainer().getItem(1).getCount(), 1,
				"A refused terminal drag must leave the menu's drag state usable");
		helper.assertValueEqual(chest.getContainer().getItem(2).getCount(), 1,
				"A refused terminal drag must leave the menu's drag state usable");
		chest.setCarried(ItemStack.EMPTY);
		helper.succeed();
	}

	/**
	 * A bound terminal can be dragged around the player's own inventory.
	 *
	 * <p>Vanilla overloads slot id {@code -999}: it means "clicked outside the window", and it is also
	 * the id the drag protocol sends for its own start and end headers. The container guard read the
	 * id alone, so every drag gesture that had a bound terminal on the cursor was refused on its first
	 * click - including one that never left the player's own backpack. Click-to-pick-up and
	 * shift-click both had their own tests and both worked, which is why this survived: dragging is
	 * the gesture nothing covered.
	 */
	@GameTest
	public void boundTerminalDragsBetweenOwnInventorySlots(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalRuntimeService.synchronizeProjection(player, data);
		ItemStack terminal = findTerminal(player).copy();
		helper.assertTrue(TerminalData.isBound(terminal), "Fixture must hold a bound terminal");
		removeTerminals(player);

		// Menu slot 9 of InventoryMenu is inventory slot 9, the first backpack row - a slot the player
		// already owns, reachable by no other party, and the destination of an entirely ordinary drag.
		player.inventoryMenu.setCarried(terminal);
		dragDistribute(player.inventoryMenu, player, 9);

		helper.assertTrue(player.getInventory().getItem(9).is(ModItems.OLD_TERMINAL),
				"Dragging a bound terminal onto the player's own backpack slot must place it there");
		helper.assertTrue(player.inventoryMenu.getCarried().isEmpty(),
				"A completed drag must clear the cursor");
		helper.assertValueEqual(countTerminals(player), 1, "The drag must not duplicate the terminal");
		helper.succeed();
	}

	/**
	 * And the plain pick-up-then-place gesture, inside the player's own inventory and inside the
	 * player's half of an open chest.
	 *
	 * <p>The second half is the one worth stating: with a chest open, the guard has an external menu
	 * in front of it, and the rule it enforces there is about the chest's slots - not about the
	 * player rearranging their own backpack while a chest happens to be on screen.
	 */
	@GameTest
	public void boundTerminalPicksUpAndPlacesInsideThePlayersOwnSlots(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalRuntimeService.synchronizeProjection(player, data);
		ItemStack terminal = findTerminal(player).copy();
		helper.assertTrue(TerminalData.isBound(terminal), "Fixture must hold a bound terminal");
		removeTerminals(player);
		player.getInventory().setItem(9, terminal);

		player.inventoryMenu.clicked(9, 0, ClickType.PICKUP, player);
		helper.assertTrue(TerminalData.isBound(player.inventoryMenu.getCarried()),
				"Picking a bound terminal up off the player's own slot must be allowed");
		player.inventoryMenu.clicked(20, 0, ClickType.PICKUP, player);
		helper.assertTrue(player.getInventory().getItem(20).is(ModItems.OLD_TERMINAL),
				"Placing it back down on another of the player's own slots must be allowed");
		helper.assertTrue(player.inventoryMenu.getCarried().isEmpty(), "The cursor must end empty");

		// Same gesture with a chest open. The player's own half of that menu is still their own.
		ChestMenu chest = ChestMenu.oneRow(94, player.getInventory());
		int playerHalf = chest.slots.size() - 36;
		chest.clicked(playerHalf + 11, 0, ClickType.PICKUP, player);
		helper.assertTrue(TerminalData.isBound(chest.getCarried()),
				"An open chest must not stop the player rearranging their own backpack");
		chest.clicked(playerHalf + 12, 0, ClickType.PICKUP, player);
		helper.assertTrue(chest.getCarried().isEmpty(),
				"Placing it into another of the player's own slots must be allowed with a chest open");
		helper.assertTrue(chest.getContainer().isEmpty(), "Nothing may have entered the chest");
		helper.assertValueEqual(countTerminals(player), 1, "The terminal must survive exactly once");
		helper.succeed();
	}

	/**
	 * The guidance bearing keeps being resolved once the terminal screen is shut.
	 *
	 * <p>Navigation used to be sent only from the loop over open views, so closing the terminal
	 * stopped the bearing updating - which is backwards for a tool whose whole use is walking
	 * somewhere. What is asserted here is the decision the tick loop makes, not the packet: an open
	 * screen has its own stream, a player with no usable terminal has no device to speak for them,
	 * and a guidance tool with nothing selected has nothing to say.
	 */
	@GameTest
	public void closedTerminalKeepsResolvingTheGuidanceBearing(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		BlockPos target = player.blockPosition().offset(-40, -6, -40);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalTool.MINERALS.slot());
			record.putString(TerminalData.TARGET_KIND, "iron");
			record.putBoolean(TerminalData.TARGET_LOCATED, true);
			record.putLong(TerminalData.TARGET_POSITION, target.asLong());
			record.putString(TerminalData.TARGET_DIMENSION,
					player.level().dimension().identifier().toString());
		});
		TerminalRuntimeService.synchronizeProjection(player, data);

		// The terminal is never opened in this test - this is exactly the state the readout is for.
		helper.assertFalse(TerminalRuntimeService.isOpen(player), "Fixture must keep the terminal shut");
		var streamed = TerminalRuntimeService.closedNavigationFor(player, data);
		helper.assertTrue(streamed != null, "A shut terminal with a guidance target must still stream");
		helper.assertValueEqual(streamed.targetKind(), TerminalNavigationPayload.IRON,
				"The shut terminal resolves the persisted quick tool, not an on-screen tab");
		helper.assertTrue(streamed.located() && streamed.navigable(),
				"A located same-dimension target must arrive navigable");
		helper.assertTrue(streamed.targetDx() < 0 && streamed.targetDz() < 0,
				"The bearing must point back at the target the fixture placed");

		// Moving changes the answer without the player touching anything, which is the live part.
		int before = streamed.targetDx();
		player.teleportTo(player.getX() - 10.0D, player.getY(), player.getZ());
		helper.assertTrue(TerminalRuntimeService.closedNavigationFor(player, data).targetDx() > before,
				"Walking toward the target must shorten the streamed bearing");

		// No terminal, no voice.
		removeTerminals(player);
		helper.assertTrue(TerminalRuntimeService.closedNavigationFor(player, data) == null,
				"A player without a usable terminal must be streamed nothing at all");
		helper.succeed();
	}

	/**
	 * The offhand key is a swap key too.
	 *
	 * <p>Vanilla's swap click accepts buttons 0-8 for the hotbar <em>and</em> 40 for the offhand; the
	 * guard only knew about the first nine. Hovering a container slot and pressing F would therefore
	 * put a bound terminal into that container, past a rule written specifically to prevent it.
	 * Found while fixing the drag refusal, in the same expression.
	 */
	@GameTest
	public void boundTerminalCannotBeOffhandSwappedIntoContainer(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalRuntimeService.synchronizeProjection(player, data);
		ItemStack terminal = findTerminal(player).copy();
		helper.assertTrue(TerminalData.isBound(terminal), "Fixture must hold a bound terminal");
		removeTerminals(player);
		player.getInventory().setItem(Inventory.SLOT_OFFHAND, terminal);

		ChestMenu chest = ChestMenu.oneRow(95, player.getInventory());
		chest.clicked(0, Inventory.SLOT_OFFHAND, ClickType.SWAP, player);
		helper.assertTrue(chest.getContainer().getItem(0).isEmpty(),
				"The offhand swap key must not carry a bound terminal into a container");
		helper.assertTrue(player.getInventory().getItem(Inventory.SLOT_OFFHAND).is(ModItems.OLD_TERMINAL),
				"A refused offhand swap must leave the terminal where it was");

		// The same key against the player's own slots is ordinary rearranging and stays allowed.
		int playerHalf = chest.slots.size() - 36;
		chest.clicked(playerHalf + 11, Inventory.SLOT_OFFHAND, ClickType.SWAP, player);
		helper.assertTrue(player.getInventory().getItem(20).is(ModItems.OLD_TERMINAL),
				"Swapping a bound terminal between the player's own slots must stay allowed");
		helper.assertValueEqual(countTerminals(player), 1, "The swap must not duplicate the terminal");
		helper.succeed();
	}

	@GameTest
	public void ordinaryItemsStillDragDistributeIntoContainers(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		removeTerminals(player);
		ChestMenu chest = ChestMenu.oneRow(94, player.getInventory());

		// One slot, which vanilla collapses into a PICKUP - the same path the terminal is refused on.
		chest.setCarried(new ItemStack(Items.STONE, 1));
		dragDistribute(chest, player, 0);
		helper.assertValueEqual(chest.getContainer().getItem(0).getCount(), 1,
				"A single-slot drag must still deposit an ordinary item");
		helper.assertTrue(chest.getCarried().isEmpty(), "A completed single-slot drag empties the cursor");

		// Several slots, which runs vanilla's real distribution loop rather than the PICKUP shortcut.
		chest.setCarried(new ItemStack(Items.STONE, 3));
		dragDistribute(chest, player, 1, 2, 3);
		for (int slot = 1; slot <= 3; slot++) {
			helper.assertValueEqual(chest.getContainer().getItem(slot).getCount(), 1,
					"A multi-slot drag must still spread an ordinary item across every dragged slot");
		}
		helper.assertTrue(chest.getCarried().isEmpty(), "A completed multi-slot drag empties the cursor");
		chest.setCarried(ItemStack.EMPTY);
		helper.succeed();
	}

	/** The three-click sequence a client sends for a drag-distribute: start, one per slot, then end. */
	private static void dragDistribute(AbstractContainerMenu menu, ServerPlayer player, int... slotIds) {
		int type = AbstractContainerMenu.QUICKCRAFT_TYPE_GREEDY;
		menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
				AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, type),
				ClickType.QUICK_CRAFT, player);
		for (int slotId : slotIds) {
			menu.clicked(slotId,
					AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, type),
					ClickType.QUICK_CRAFT, player);
		}
		menu.clicked(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE,
				AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, type),
				ClickType.QUICK_CRAFT, player);
	}

	@GameTest
	public void cursorHeldTerminalIsNotReissuedAsLost(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		data.updateTerminalRecord(player.getUUID(), record -> record.putBoolean(TerminalData.BOUND, true));
		TerminalLifecycleService.ensureCarried(player, false);
		ItemStack terminal = findTerminal(player).copy();
		int generation = TerminalData.copyGeneration(terminal);
		int recoveries = data.terminalRecord(player.getUUID()).orElseThrow()
				.getIntOr(TerminalData.RECOVERY_COUNT, 0);

		// What a player halfway through dragging the terminal to another slot looks like server-side:
		// the stack is on the cursor, which is not part of the inventory the reconciliation scans.
		removeTerminals(player);
		player.containerMenu.setCarried(terminal);

		helper.assertTrue(TerminalLifecycleService.ensureCarried(player, false),
				"A terminal on the cursor is being moved, not lost");
		helper.assertValueEqual(countTerminals(player), 0,
				"Reconciliation must not issue a second terminal while one is mid-move");
		helper.assertTrue(data.isValidTerminal(player.containerMenu.getCarried(), player.getUUID()),
				"The stack being moved must stay the authoritative copy");
		CompoundTag midMove = data.terminalRecord(player.getUUID()).orElseThrow();
		helper.assertValueEqual(midMove.getIntOr(TerminalData.COPY_GENERATION, 0), generation,
				"Moving a terminal must not bump the copy generation");
		helper.assertValueEqual(midMove.getIntOr(TerminalData.RECOVERY_COUNT, 0), recoveries,
				"Moving a terminal must not be recorded as a recovery");

		// Completing the move leaves exactly the stack the player picked up.
		player.getInventory().setItem(9, player.containerMenu.getCarried());
		player.containerMenu.setCarried(ItemStack.EMPTY);
		helper.assertTrue(TerminalLifecycleService.ensureCarried(player, false),
				"The dropped-off terminal must reconcile as the valid copy");
		helper.assertValueEqual(countTerminals(player), 1, "Exactly one terminal after the move");
		helper.assertValueEqual(TerminalData.copyGeneration(findTerminal(player)), generation,
				"The moved terminal is the same copy the player picked up");
		helper.succeed();
	}

	private static ItemStack findTerminal(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(ModItems.OLD_TERMINAL)) {
				return stack;
			}
		}
		throw new AssertionError("Player has no terminal");
	}

	private static int countTerminals(ServerPlayer player) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (player.getInventory().getItem(slot).is(ModItems.OLD_TERMINAL)) {
				count++;
			}
		}
		return count;
	}

	private static int countItem(ServerPlayer player, net.minecraft.world.item.Item item) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static void removeTerminals(ServerPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (player.getInventory().getItem(slot).is(ModItems.OLD_TERMINAL)) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
		player.getInventory().setChanged();
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		method.invoke(this, helper);
	}
}
