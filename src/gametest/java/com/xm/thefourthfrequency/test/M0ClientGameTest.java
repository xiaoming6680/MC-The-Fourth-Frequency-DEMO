package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import com.xm.thefourthfrequency.state.NavigationState;
import com.xm.thefourthfrequency.terminal.TerminalControlPolicy;
import com.xm.thefourthfrequency.terminal.TerminalPage;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.terminal.TerminalUiLayout;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import com.xm.thefourthfrequency.world.TerminalActivityTracker;
import com.xm.thefourthfrequency.world.FragmentInvestigationService;
import com.xm.thefourthfrequency.client_ui.TerminalScreen;
import com.xm.thefourthfrequency.client_ui.EmptySegmentClient;
import com.xm.thefourthfrequency.client_ui.EmptySegmentOverlayScreen;
import com.xm.thefourthfrequency.client_ui.PrivateAnomalyClient;
import com.xm.thefourthfrequency.client_ui.TerminalClientAudio;
import com.xm.thefourthfrequency.client_ui.TerminalHandheldAnimator;
import com.xm.thefourthfrequency.client_ui.DebugPanelScreen;
import com.xm.thefourthfrequency.client_ui.FirstRunNoticeController;
import com.xm.thefourthfrequency.client_ui.FirstRunNoticeScreen;
import com.xm.thefourthfrequency.client_ui.AlphaLoadSessionController;
import com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline;
import com.xm.thefourthfrequency.client_ui.AlphaResourcePackPlan;
import com.xm.thefourthfrequency.client_ui.DimensionViewDistanceController;
import com.xm.thefourthfrequency.client_ui.DimensionViewDistancePolicy;
import com.xm.thefourthfrequency.terminal.AnomalyCatalog;
import com.xm.thefourthfrequency.terminal.DebugNames;
import com.xm.thefourthfrequency.terminal.TerminalAnomalyLogService;
import com.xm.thefourthfrequency.terminal.TerminalSignalService;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.correction.EmptySegmentService;
import com.xm.thefourthfrequency.world.DebugPanelService;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import com.xm.thefourthfrequency.world.ZeroStationLayout;
import com.xm.thefourthfrequency.meta_api.MetaController;
import com.xm.thefourthfrequency.meta_api.MetaEvent;
import com.xm.thefourthfrequency.meta_api.MockMetaPlatformAdapter;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.client.gui.screens.packs.PackSelectionModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;

import java.nio.file.Files;
import java.util.Set;
import java.util.List;
import java.util.stream.Stream;

public final class M0ClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientGameTestSelection selection = ClientGameTestSelection.current();
		if (selection.runsAlphaRelaunch()) {
			runAlphaRelaunch(context);
			return;
		}
		if (!selection.runsMainline()) {
			if (selection.runsNoticeEntry()) {
				assertAndAcknowledgeFirstRunNotice(context);
			} else {
				acknowledgeFirstRunNoticeForFocusedSuite(context);
			}
			context.waitForScreen(TitleScreen.class);
			if (selection.runsNoticeEntry()) {
				context.waitTicks(10);
				context.takeScreenshot("m1-first-run-title-after-entry");
			}
			return;
		}
		assertAndAcknowledgeFirstRunNotice(context);
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(client -> {
			if (!client.getLanguageManager().getSelected().equals("zh_cn")) {
				throw new AssertionError("Terminal UI screenshots must run with the Chinese language selected");
			}
		});
		context.runOnClient(DimensionViewDistanceController::resetForTesting);
		assertRenderDistanceIsUntouchedOutsideAModWorld(context);
		if (Boolean.getBoolean("thefourthfrequency.realMetaSmoke")) {
			context.runOnClient(client -> {
				var awakened = MetaController.dispatchForTesting(MetaEvent.FINAL_BODY_AWAKENED);
				if (!awakened.externalEffects() || awakened.degraded() || awakened.artifacts().size() != 3) {
					throw new AssertionError("M9 real Windows Meta awakening degraded or missed owned artifacts");
				}
			});
			context.waitTicks(10);
			context.runOnClient(client -> {
				var terminated = MetaController.dispatchForTesting(MetaEvent.FOURTH_BAND_TERMINATED);
				if (!terminated.externalEffects() || terminated.degraded() || terminated.artifacts().isEmpty()) {
					throw new AssertionError("M9 real Windows Meta termination degraded or missed its owned rename");
				}
				MetaController.restore();
			});
		}
		MockMetaPlatformAdapter metaAdapter = new MockMetaPlatformAdapter();
		MetaController.useAdapterForTesting(metaAdapter);
		assertAlphaBasePacksHidden(context);
		context.waitTicks(45);
		context.takeScreenshot("m0-title-screen");

		if (RuntimeServices.config() == null) {
			throw new AssertionError("Common runtime services must be initialized on the client");
		}

		TestWorldSave save;
		BlockPos stationPosition;
		BlockPos removedWall;
		TerminalPersistenceProof terminalProof;
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.runOnClient(client -> {
				if (client.screen instanceof FirstRunNoticeScreen || FirstRunNoticeController.pendingForTesting())
					throw new AssertionError("Acknowledged title-screen notice appeared again during first world entry");
			});
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(30);
			assertAlphaSessionLoaded(context);
			// Inside a world the lock is this mod's to apply, and everything the title-screen check
			// just refused - the pinned value, the rejected setter, the greyed-out slider - is now
			// required. Same assertions, opposite verdict, which is the whole scoping change.
			assertInitialOverworldRenderDistance(context);
			save = singleplayer.getWorldSave();
			stationPosition = singleplayer.getServer().computeOnServer(server -> {
				FrequencyWorldData data = FrequencyWorldData.get(server);
				if (!data.stationComplete()) {
					throw new AssertionError("Relay Station Zero did not finish its bounded build");
				}
				return data.stationPosition().orElseThrow();
			});

			assertSingleOwnedTerminal(context);
			assertPlayerInsideStation(context, stationPosition);
			context.takeScreenshot("m1-relay-station-zero");
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-first-boot-calibration");
			// The profile comes first now: it holds the exit and points at no tab, so a step capture
			// placed ahead of it would be polling for a pointer that cannot appear yet.
			completeFirstBootProfile(context);
			captureOnboardingStep(context);
			completeFirstBootWalkthrough(context);
			assertFirstTaskCompletionIsShown(context);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!"HOME".equals(terminal.pageForTesting())) {
					throw new AssertionError("A terminal opened on a page other than HOME");
				}
				terminal.selectPageForTesting(1);
			});
			context.takeScreenshot("r-terminal-tools-grid");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(0);
				terminal.openToolForTesting(TerminalTool.HOME.slot());
				if (!"TOOLS".equals(terminal.pageForTesting()) || !terminal.toolReturnsHomeForTesting()) {
					throw new AssertionError("A HOME shortcut must open the full detail page with a HOME back target");
				}
			});
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-tool-home-empty");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.backFromToolForTesting();
				if (!"HOME".equals(terminal.pageForTesting())) {
					throw new AssertionError("The detail back control did not return a HOME-opened tool to HOME");
				}
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.WEATHER.slot());
			});
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-tool-weather-current");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.activateSelectedToolForTesting();
				if (!"HOME".equals(terminal.pageForTesting())
						|| terminal.homeLiveToolForTesting() != TerminalTool.WEATHER.slot()) {
					throw new AssertionError("Pinning an information tool must replace the HOME middle cards");
				}
			});
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-home-weather-live-info");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.closeHomeLiveToolForTesting();
				if (terminal.homeLiveToolForTesting() != TerminalToolService.NO_TOOL) {
					throw new AssertionError("The live information card close control did not restore HOME recommendations");
				}
			});
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.NAVIGATION.slot());
				if (terminal.selectedToolForTesting() != TerminalToolService.NO_TOOL) {
					throw new AssertionError("A locked tool opened its detail page");
				}
			});
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-locked-tool-stays-closed");
			context.runOnClient(client -> ((TerminalScreen) client.screen).selectPageForTesting(2));
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-records-page");
			context.runOnClient(client -> ((TerminalScreen) client.screen).selectPageForTesting(0));
			context.takeScreenshot("r-terminal-home-page");
			setTerminalView(context, TerminalControlPolicy.Mode.FILES.ordinal(),
					TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.waitTicks(2);
			var expectedInitialFiles = singleplayer.getServer().computeOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var record = FrequencyWorldData.get(server).terminalRecord(player.getUUID()).orElseThrow();
				var expected = new java.util.HashSet<>(java.util.Set.of("maintenance_handoff", "encrypted_witness_file"));
				expected.addAll(com.xm.thefourthfrequency.narrative.DeviceManualPolicy.earned(
						TerminalToolService.availableToolsMask(player, record)));
				return expected;
			});
			context.waitFor(client -> client.screen instanceof TerminalScreen terminal
					&& terminal.fileCountForTesting() >= expectedInitialFiles.size(), 60);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openLogDirectoryForTesting();
				// Binding exposes the handoff and locked diary; every unlocked tool also contributes
				// its manual. Compare the complete set so missing manuals and premature files both fail.
				var actualFiles = java.util.stream.IntStream.range(0, terminal.fileCountForTesting())
						.mapToObj(terminal::fileIdForTesting).collect(java.util.stream.Collectors.toSet());
				if (!actualFiles.equals(expectedInitialFiles) || terminal.selectedFileForTesting() != -1
						|| !"FILES".equals(terminal.pageForTesting())) {
					throw new AssertionError("A freshly bound terminal must expose the handoff and the locked diary: "
							+ java.util.stream.IntStream.range(0, terminal.fileCountForTesting()).mapToObj(terminal::fileIdForTesting).toList()
							+ " page=" + terminal.pageForTesting() + " selected=" + terminal.selectedFileForTesting());
				}
			});
			context.takeScreenshot("r86-file-directory-initial-locked-diary");
			setTerminalView(context, TerminalControlPolicy.Mode.SIGNAL.ordinal(),
					TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.waitTicks(2);
			context.waitTicks(65);
			context.takeScreenshot("m2-terminal-home-receiver-standby");
			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var data = FrequencyWorldData.get(server);
				player.getInventory().add(new ItemStack(Items.CRIMSON_PLANKS, SurvivalProgressService.REQUIRED_WOOD));
				SurvivalProgressService.updatePlayer(player, data);
				TerminalRuntimeService.refresh(player);
			});
			context.waitTicks(4);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.NAVIGATION.slot());
				terminal.setTuningForTesting(18);
				if (terminal.tuningForTesting() != 18) {
					throw new AssertionError("The receiver dial did not provide local mechanical feedback");
				}
			});
			context.waitTicks(5);
			context.takeScreenshot("m2-signal-tool-no-nearby-receiver");
			context.runOnClient(client -> client.screen.onClose());
			context.waitTicks(4);
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			assertHandheldPerformanceRaised(context);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.modeForTesting() != TerminalControlPolicy.Mode.SIGNAL.ordinal()
						|| terminal.tuningForTesting() != TerminalControlPolicy.DEFAULT_TUNING) {
					throw new AssertionError("Terminal did not reopen with the contextual receiver at rest");
				}
				terminal.onClose();
			});
			// Immediately, before the device has come down: the UI is gone and the terminal is still
			// up in both hands. The one frame in the suite where the whole presentation is visible.
			context.takeScreenshot("r-terminal-handheld-raised");
			context.waitTicks(4);
			assertHandheldPerformanceReturnedToRest(context);
			// And the rigid shell as the player actually carries it. The state assertions above
			// cannot see whether the model came out right, or whether it fits in the frame.
			context.takeScreenshot("r-terminal-handheld-idle");
			captureHandheldAtViewExtremes(context);

			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var data = FrequencyWorldData.get(server);
				BlockPos orePosition = player.blockPosition().below(4);
				server.overworld().setBlockAndUpdate(orePosition, Blocks.IRON_ORE.defaultBlockState());
				// Let the real scan write the target. Hand-seeding the MINERAL_SURVEY_* episode keys
				// used to be enough because the navigation snapshot fell back to them; it does not any
				// more - a survey hit now writes the navigation state directly, and that is the only
				// place the mineral target comes from. Seeding half of what production writes left
				// this asserting against a target that no longer existed.
				if (!ResourceGuidanceService.probeForTesting(player)) {
					throw new AssertionError("The deterministic mineral probe was refused before it ran");
				}
				// Split from the client assertion below so a failure says which half broke: the survey
				// not hearing the ore that was just placed, or the result not reaching the screen.
				var probed = data.terminalRecord(player.getUUID()).orElseThrow();
				if (!probed.getBooleanOr(TerminalData.TARGET_LOCATED, false)) {
					throw new AssertionError("The survey did not locate the iron placed under the player"
							+ " (resource=" + probed.getIntOr(TerminalData.SELECTED_RESOURCE, -1)
							+ ", unlocked=" + TerminalToolService.availableResourcesMask(probed)
							+ ", milestones=" + probed.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0) + ")");
				}
			});
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(4);
			context.runOnClient(client -> ((TerminalScreen) client.screen)
					.openToolForTesting(TerminalTool.MINERALS.slot()));
			context.waitTicks(4);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				// Three separate promises. Reporting them as one said the survey detail was wrong
				// without saying which half - whether the target was missing, or the needle had been
				// taken over - and those have nothing to do with each other.
				if (!terminal.navigationTargetLocatedForTesting()) {
					throw new AssertionError("Automatic survey detail must show its located target");
				}
				if (terminal.navigationActiveForTesting()) {
					throw new AssertionError("Automatic survey detail must not start navigation on its own");
				}
				if (terminal.guidanceToolForTesting() != TerminalToolService.NO_TOOL) {
					throw new AssertionError("Automatic survey detail must not activate the compass needle");
				}
			});
			context.takeScreenshot("r-terminal-tool-minerals-auto-survey");
			context.runOnClient(client -> ((TerminalScreen) client.screen).activateSelectedToolForTesting());
			context.waitTicks(5);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.guidanceToolForTesting() != TerminalTool.MINERALS.slot()
						|| terminal.homeLiveToolForTesting() != TerminalTool.MINERALS.slot()) {
					throw new AssertionError("Automatic survey navigation button did not start mineral guidance");
				}
				terminal.closeHomeLiveToolForTesting();
			});
			context.waitTicks(3);
			context.runOnClient(client -> client.screen.onClose());
			context.waitTicks(4);

			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var data = FrequencyWorldData.get(server);
				BlockPos orePosition = player.blockPosition().below(4);
				if (!ResourceGuidanceService.probeForTesting(player)) {
					throw new AssertionError("The deterministic mineral probe was refused before it ran");
				}
				com.xm.thefourthfrequency.terminal.TerminalToolService.startGuidance(
						player, TerminalTool.MINERALS.slot());
				if (!data.terminalRecord(player.getUUID()).orElseThrow()
						.getBooleanOr(TerminalData.TARGET_LOCATED, false)) {
					throw new AssertionError("Client fixture did not locate the real iron ore");
				}
			});
			context.waitTicks(20);
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			setTerminalView(context, 0, TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.waitTicks(6);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openToolForTesting(TerminalTool.MINERALS.slot());
				if (!terminal.navigationActiveForTesting()
						|| terminal.selectedResourceForTesting() != TerminalResource.IRON.wireId()
						|| terminal.guidanceToolForTesting() != TerminalTool.MINERALS.slot()) {
					throw new AssertionError("Located mining target did not activate the authoritative navigation DTO");
				}
			});
			context.takeScreenshot("r-terminal-tool-minerals-real-scan");
			context.runOnClient(client -> ((TerminalScreen) client.screen).activateSelectedToolForTesting());
			context.waitTicks(5);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.guidanceToolForTesting() != TerminalToolService.NO_TOOL) {
					throw new AssertionError("The active navigation button did not stop mineral guidance");
				}
				terminal.activateSelectedToolForTesting();
				if (!"HOME".equals(terminal.pageForTesting())
						|| terminal.homeLiveToolForTesting() != TerminalTool.MINERALS.slot()) {
					throw new AssertionError("Restarting navigation did not create the HOME live information card");
				}
			});
			context.waitTicks(5);
			context.runOnClient(client -> {
				if (((TerminalScreen) client.screen).guidanceToolForTesting() != TerminalTool.MINERALS.slot()) {
					throw new AssertionError("The shared navigation button did not restart authoritative guidance");
				}
			});
			context.takeScreenshot("r-terminal-home-minerals-live-info");
			if (selection.runsToolsUi()) {
				singleplayer.getServer().runOnServer(server -> {
					var player = server.getPlayerList().getPlayers().getFirst();
					TerminalSignalService.record(player, com.xm.thefourthfrequency.terminal.SignalBand.UNKNOWN,
							"pursuit_return_instability", 0, 1, true);
				});
				context.waitTicks(2);
				context.runOnClient(client -> {
					TerminalScreen terminal = (TerminalScreen) client.screen;
					if (terminal.unreadCountForTesting() < 1 || !terminal.unreadFlashOnForTesting()) {
						throw new AssertionError("A new record did not activate the top-bar unread alert");
					}
				});
				context.takeScreenshot("r-terminal-unread-top-bar-alert");
				context.waitTicks(45);
				context.runOnClient(client -> {
					TerminalScreen terminal = (TerminalScreen) client.screen;
					if (terminal.unreadCountForTesting() < 1 || terminal.unreadFlashOnForTesting()) {
						throw new AssertionError("The unread marker must remain after its short flash has stopped");
					}
				});
				context.takeScreenshot("r-terminal-unread-top-bar-settled");

				// Everything above happened while the terminal sat on HOME, which is what proves an
				// unread marker survives when the player is not looking at the page. On RECORDS the
				// opposite has to hold: a record whose text is on screen acknowledges itself, without
				// asking for a click on the tab the player is already on.
				context.runOnClient(client ->
						((TerminalScreen) client.screen).selectPageForTesting(TerminalPage.RECORDS.ordinal()));
				context.waitTicks(5);
				recordSignal(singleplayer, "pursuit_return_instability");
				context.waitTicks(10);
				context.runOnClient(client -> {
					if (((TerminalScreen) client.screen).unreadCountForTesting() != 0) {
						throw new AssertionError(
								"A record arriving on the open RECORDS page must acknowledge itself without a tab click");
					}
				});

				// The newest record is row 0, so a list scrolled to its bottom is the state where the
				// player demonstrably is not being shown what just arrived.
				for (int filler = 0; filler < 24; filler++) recordSignal(singleplayer, "pursuit_return_instability");
				context.waitTicks(10);
				context.runOnClient(client -> {
					TerminalScreen terminal = (TerminalScreen) client.screen;
					terminal.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_END, 0, 0));
					if (terminal.recordsScrollRowForTesting() <= 0) {
						throw new AssertionError("Fixture must build a RECORDS list long enough to scroll");
					}
					if (terminal.unreadCountForTesting() != 0) {
						throw new AssertionError("Records read at the top of the list must already be acknowledged");
					}
				});
				recordSignal(singleplayer, "continuity");
				context.waitTicks(10);
				context.runOnClient(client -> {
					TerminalScreen terminal = (TerminalScreen) client.screen;
					if (terminal.unreadCountForTesting() < 1) {
						throw new AssertionError(
								"A record must stay unread while the list is scrolled away from it");
					}
					terminal.keyPressed(new net.minecraft.client.input.KeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_HOME, 0, 0));
				});
				context.waitTicks(10);
				context.runOnClient(client -> {
					if (((TerminalScreen) client.screen).unreadCountForTesting() != 0) {
						throw new AssertionError("Scrolling the new record back into view must acknowledge it");
					}
				});
				context.takeScreenshot("r-terminal-records-self-acknowledged");
				// Leave the remembered page as it was found, or the pursuit-warning redirect checks
				// below would start from RECORDS and stop proving anything.
				context.runOnClient(client ->
						((TerminalScreen) client.screen).selectPageForTesting(TerminalPage.HOME.ordinal()));
				context.waitTicks(5);
				closeTerminal(context);
				singleplayer.getServer().runOnServer(server -> {
					var player = server.getPlayerList().getPlayers().getFirst();
					FrequencyWorldData.get(server).updateTerminalRecord(player.getUUID(), record ->
							record.putBoolean(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, true));
				});
				openTerminalThroughClientCallback(context);
				context.waitForScreen(TerminalScreen.class);
				context.waitTicks(2);
				context.runOnClient(client -> {
					if (!"RECORDS".equals(((TerminalScreen) client.screen).pageForTesting())) {
						throw new AssertionError("A pursuit warning did not redirect the next terminal open to RECORDS");
					}
				});
				closeTerminal(context);
				openTerminalThroughClientCallback(context);
				context.waitForScreen(TerminalScreen.class);
				context.waitTicks(2);
				context.runOnClient(client -> {
					if (!"HOME".equals(((TerminalScreen) client.screen).pageForTesting())) {
						throw new AssertionError("The pursuit warning RECORDS redirect was not consumed after one open");
					}
				});
				closeTerminal(context);
				if (selection.stopsAfterToolsUi()) return;
			}
			closeTerminal(context);
			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				for (int count = 0; count < 32; count++)
					TerminalActivityTracker.record(player, TerminalData.MINED_BLOCKS, "mined");
				// The whole sample count, not one ingot: the fourth band is revealed off the IRON
				// milestone, and that needs REQUIRED_IRON of them. Written against the constant so the
				// next time the requirement moves, this moves with it instead of going quietly stale.
				player.getInventory().add(new ItemStack(Items.RAW_IRON, SurvivalProgressService.REQUIRED_IRON));
				ResourceGuidanceService.updatePlayer(player);
			});
			context.waitTicks(120);
			context.runOnClient(client -> client.getToastManager().clear());
			assertMiningCompletionState(context);
			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				if (!DebugPanelService.setEnabled(player, true)) throw new AssertionError("Debug permission was not enabled");
				DebugPanelService.open(player);
			});
			context.waitForScreen(DebugPanelScreen.class);
			context.runOnClient(client -> {
				DebugPanelScreen debug = (DebugPanelScreen) client.screen;
				// Counted against the catalogues rather than against literals. What this test is for is
				// that the workbench lists the whole of both and refreshes on its own; how many entries
				// they hold is pinned by AnomalyCatalogTest and TerminalFileStateTest, which is where a
				// change to either belongs. The literal 16 here had been stale since the catalogue
				// reached nineteen, so this assertion was failing for a reason it was not about.
				if (debug.tabCountForTesting() != 3
						|| debug.anomalyCountForTesting() != AnomalyCatalog.definitions().size()
						|| debug.fileCountForTesting() != NarrativeFileCatalog.definitions().size()
						|| debug.milestoneCountForTesting() != SurvivalMilestone.values().length)
					throw new AssertionError("M debug workbench must list every anomaly, file and milestone across its three tabs");
				// Nothing may be built outside the frame. The right column used to run off the bottom
				// on any window where the panel came out shorter than its 520-pixel maximum, and no
				// screenshot taken at one resolution would ever have shown it.
				String stray = debug.firstWidgetOutsidePanelForTesting();
				if (!stray.isEmpty()) throw new AssertionError("Debug panel widget outside its frame: " + stray);
				debug.triggerAnomalyForTesting("local_rule_collapse");
			});
			context.waitFor(client -> client.screen == null, 100);
			singleplayer.getServer().runOnServer(server -> DebugPanelService.open(server.getPlayerList().getPlayers().getFirst()));
			context.waitForScreen(DebugPanelScreen.class);
			context.runOnClient(client -> ((DebugPanelScreen) client.screen).triggerAnomalyForTesting("red_horizon"));
			context.waitTicks(4);
			context.runOnClient(client -> {
				if (!(client.screen instanceof DebugPanelScreen debug)
						|| !debug.statusMessageForTesting().contains(
								"已有异象正在发生：" + DebugNames.anomaly("local_rule_collapse")))
					throw new AssertionError("Rejected anomaly request must keep the menu open and explain the active conflict");
			});
			context.takeScreenshot("r74-debug-panel-specific-anomaly-failure");
			// The anomaly tab carries the toolbar HIM and the dark watcher live on. Nothing here can
			// assert that the row looks right - this is the frame a human checks.
			context.runOnClient(client -> ((DebugPanelScreen) client.screen)
					.selectTabForTesting(DebugPanelScreen.anomalyTabIndexForTesting()));
			context.waitTicks(4);
			context.runOnClient(client -> {
				DebugPanelScreen debug = (DebugPanelScreen) client.screen;
				if (!debug.toolbarActionsForTesting().contains("him_spawn")) {
					throw new AssertionError("The anomaly toolbar lost its HIM entry: "
							+ debug.toolbarActionsForTesting());
				}
			});
			context.takeScreenshot("r-debug-panel-anomaly-toolbar-him");
			context.runOnClient(client -> client.screen.onClose());
			context.waitTicks(4);
			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				com.xm.thefourthfrequency.terminal.AnomalyRuntimeService.interrupt(player, false);
				FrequencyWorldData.get(server).updateTerminalRecord(player.getUUID(), record ->
						record.putBoolean(TerminalData.ANOMALIES_SUSPENDED, true));
			});
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			context.runOnClient(client -> ((TerminalScreen) client.screen).selectPageForTesting(0));
			context.takeScreenshot("r-terminal-survival-objective-prepare-nether");
			context.runOnClient(client -> ((TerminalScreen) client.screen).selectPageForTesting(2));
			context.waitTicks(2);
			context.takeScreenshot("r-terminal-records-after-mining");
			setTerminalView(context, 0, TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.NAVIGATION.slot());
			});
			context.waitTicks(5);
			context.takeScreenshot("m3-abnormal-signal-layer");
			context.waitTicks(20);
			context.takeScreenshot("m3-abnormal-signal-layer-confirm");
			context.waitTicks(20);
			context.runOnClient(client -> ((TerminalScreen) client.screen).openLogDirectoryForTesting());
			context.waitTicks(5);
			context.takeScreenshot("m3-file-directory-bound-normal");
			context.waitTicks(20);
			context.takeScreenshot("m3-file-directory-bound-normal-confirm");
			context.runOnClient(client -> ((TerminalScreen) client.screen).openLogEntryForTesting(1));
			context.waitTicks(5);
			context.takeScreenshot("m3-second-predecessor-detail");
			context.waitTicks(20);
			context.takeScreenshot("m3-second-predecessor-detail-confirm");
			context.waitTicks(20);
			closeTerminal(context);

			M4ClientFixture m4Fixture = singleplayer.getServer().computeOnServer(
					M0ClientGameTest::prepareM4Fixture);
			context.waitTicks(20);
			context.takeScreenshot("m4-vanilla-structure-source");
			context.takeScreenshot("m4-unread-red-light");
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.selectedToolForTesting() != TerminalToolService.NO_TOOL
						|| !terminal.receiverGameplayActiveForTesting()) {
					throw new AssertionError(
							"Arriving at a side-route source must enable tuning before navigation is opened");
				}
				terminal.setTuningForTesting(Math.max(0, m4Fixture.tuning() - 4));
			});
			context.waitTicks(3);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.NAVIGATION.slot());
			});
			context.waitTicks(5);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.unreadCountForTesting() < 1) {
					throw new AssertionError("Expected unread vanilla-structure candidates");
				}
			});
			context.takeScreenshot("m4-candidate-cards-collapsed");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.backFromToolForTesting();
				if (terminal.selectedToolForTesting() != TerminalToolService.NO_TOOL
						|| !terminal.receiverGameplayActiveForTesting()) {
					throw new AssertionError("Nearby tuning incorrectly depends on the navigation detail page");
				}
			});
			verifyReceiverAudioLifecycle(context, m4Fixture.tuning());
			context.takeScreenshot("m4-lcd-nearby-unrecorded-signal");
			context.waitTicks(25);
			context.runOnClient(client -> {
				if (!PrivateAnomalyClient.anomalyId().equals("fragment_2")) {
					throw new AssertionError("Nearby tuning did not preserve the discoverer's private fragment state");
				}
			});
			context.takeScreenshot("m4-private-fragment-signal-state");
			context.runOnClient(client -> ((TerminalScreen) client.screen).openLogDirectoryForTesting());
			context.waitTicks(5);
			context.takeScreenshot("m4-file-directory-fragment-parent");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openWitnessFragmentsForTesting();
				if (!"LOCKED_DIARY".equals(terminal.logViewForTesting())
						|| terminal.hiddenFileReadPercentForTesting() != 0
						|| terminal.discoveredHiddenFileCountForTesting() != 2) {
					throw new AssertionError("Locked diary did not show 0% while its title recovered two authored segments");
				}
			});
			context.takeScreenshot("m4-diary-half-title-zero-read");
			singleplayer.getServer().runOnServer(M0ClientGameTest::completeM4Fragments);
			context.waitTicks(12);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!terminal.selectedFileIdForTesting().equals("encrypted_witness_file")
						|| terminal.discoveredHiddenFileCountForTesting() != 4) {
					throw new AssertionError("New hidden files displaced the selected stable diary id");
				}
				for (int fragment = 0; fragment < 3; fragment++) terminal.openFragmentForTesting(fragment);
			});
			context.waitTicks(12);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openWitnessFragmentsForTesting();
				if (!"LOCKED_DIARY".equals(terminal.logViewForTesting())
						|| terminal.hiddenFileReadPercentForTesting() != 75) {
					throw new AssertionError("Complete recovered title must remain locked at three personal reads");
				}
			});
			context.takeScreenshot("m4-diary-complete-title-75-percent-locked");
			context.runOnClient(client -> ((TerminalScreen) client.screen).openFragmentForTesting(3));
			context.waitFor(client -> client.screen instanceof TerminalScreen terminal
					&& terminal.hiddenFileReadPercentForTesting() == 100, 80);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!"DETAIL".equals(terminal.logViewForTesting())
						|| !terminal.selectedFileIdForTesting().equals("abandoned_warehouse_record")
						|| !terminal.diaryUnlockFadeActiveForTesting()) {
					throw new AssertionError("The fourth read did not preserve the open damaged file while fading the diary title");
				}
			});
			assertArchiveUnlocked(context);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openWitnessFragmentsForTesting();
				if (!"DETAIL".equals(terminal.logViewForTesting())) {
					throw new AssertionError("An unlocked witness file did not open its complete text in the right pane");
				}
			});
			context.takeScreenshot("m4-hidden-files-read-diary-unlocked");
			context.waitTicks(20);
			context.runOnClient(client -> ((TerminalScreen) client.screen).openCompleteFileForTesting());
			context.takeScreenshot("m4-complete-witness-file");
			closeTerminal(context);
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			setTerminalView(context, TerminalControlPolicy.Mode.SIGNAL.ordinal(),
					TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.selectPageForTesting(1);
				terminal.openToolForTesting(TerminalTool.NAVIGATION.slot());
			});
			context.waitTicks(5);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				// Two statements, because they fail for unrelated reasons: the server not putting the
				// panel at stage one, and the stage-one panel not mixing the ECG into the carrier.
				if (terminal.visualStageForTesting() != 1) {
					throw new AssertionError("The formal fourth band did not reach the client as the stage-one panel: stage="
							+ terminal.visualStageForTesting());
				}
				if (Math.abs(terminal.waveformMorphTargetForTesting()
						- TerminalScreen.STAGE_ONE_WAVEFORM_MORPH) > 0.0001D) {
					throw new AssertionError("Stage-one carrier did not mix the ECG waveform into the receiver trace: "
							+ terminal.waveformMorphTargetForTesting());
				}
			});
			context.takeScreenshot("m4-formal-fourth-band-cyan");
			context.waitTicks(40);
			context.takeScreenshot("m4-formal-fourth-band-cyan-confirm");
			context.runOnClient(client -> ((TerminalScreen) client.screen).openLogDirectoryForTesting());
			context.waitTicks(5);
			context.takeScreenshot("m4-file-directory-witness-unlocked");
			context.waitTicks(20);
			context.takeScreenshot("m4-file-directory-witness-unlocked-confirm");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openWitnessFragmentsForTesting();
				terminal.openCompleteFileForTesting();
			});
			context.waitTicks(2);
			context.takeScreenshot("m4-immutable-local-file");
			context.waitTicks(20);
			context.takeScreenshot("m4-immutable-local-file-confirm");
			context.waitTicks(20);
			context.runOnClient(client -> {
				if (client.screen == null) {
					throw new AssertionError("Archive screen closed before scroll verification");
				}
				for (int row = 0; row < 20; row++) {
					client.screen.mouseScrolled(0.0, 0.0, 0.0, -1.0);
				}
			});
			context.waitTicks(2);
			context.takeScreenshot("m4-immutable-local-file-end");
			context.waitTicks(20);
			context.takeScreenshot("m4-immutable-local-file-end-confirm");
			context.waitTicks(20);
			closeTerminal(context);

			singleplayer.getServer().runOnServer(server -> triggerEmptySegment(
					server, EmptySegmentService.EventType.VIEWPOINT_SEPARATION, 50));
			context.waitTicks(12);
			context.takeScreenshot("m5-empty-viewpoint-separation");
			context.waitTicks(50);
			singleplayer.getServer().runOnServer(server -> triggerEmptySegment(
					server, EmptySegmentService.EventType.EXPERIENCE_GAP, 50));
			context.waitForScreen(EmptySegmentOverlayScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("m5-empty-experience-gap");
			context.waitTicks(55);
			context.runOnClient(client -> {
				if (!EmptySegmentClient.activeEvent().equals("none")
						|| client.screen instanceof EmptySegmentOverlayScreen) {
					throw new AssertionError("Empty-segment client presentation did not force recovery");
				}
			});

			singleplayer.getServer().runOnServer(M0ClientGameTest::beginM6NetherCrossing);
			assertLockedRenderDistance(context, Level.NETHER,
					DimensionViewDistancePolicy.NETHER_CHUNKS);
			context.waitTicks(8);
			context.runOnClient(client -> {
				client.getToastManager().clear();
				client.gui.getChat().clearMessages(true);
				if (!PrivateAnomalyClient.anomalyId().equals("continuity") || client.level == null
						|| client.level.dimension() != Level.NETHER) {
					throw new AssertionError("M6 private continuity presentation was not visible after crossing");
				}
			});
			context.takeScreenshot("m6-nether-continuity-private-anomaly");
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			setTerminalView(context, 0, TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.waitTicks(5);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!terminal.toolAvailableForTesting(TerminalTool.PORTAL.slot()))
					throw new AssertionError("A real Nether arrival did not unlock the portal tool");
				terminal.openToolForTesting(TerminalTool.PORTAL.slot());
				terminal.activateSelectedToolForTesting();
				if (!"HOME".equals(terminal.pageForTesting())
						|| terminal.homeLiveToolForTesting() != TerminalTool.PORTAL.slot()) {
					throw new AssertionError("Starting portal navigation did not create the HOME live information card");
				}
			});
			context.waitFor(client -> client.screen instanceof TerminalScreen terminal
					&& terminal.navigationActiveForTesting()
					&& terminal.guidanceToolForTesting() == TerminalTool.PORTAL.slot(), 40);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!terminal.navigationActiveForTesting()
						|| terminal.guidanceToolForTesting() != TerminalTool.PORTAL.slot())
					throw new AssertionError("The portal tool did not guide to the real arrival point");
			});
			context.takeScreenshot("r-terminal-tool-portal-real-arrival");
			context.runOnClient(client -> ((TerminalScreen) client.screen).selectPageForTesting(0));
			context.takeScreenshot("m6-terminal-capability-model");
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openToolForTesting(TerminalTool.PORTAL.slot());
				terminal.activateSelectedToolForTesting();
			});
			context.waitFor(client -> client.screen instanceof TerminalScreen terminal
					&& terminal.guidanceToolForTesting() == TerminalToolService.NO_TOOL
					&& terminal.homeLiveToolForTesting() == TerminalToolService.NO_TOOL, 40);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.guidanceToolForTesting() != TerminalToolService.NO_TOOL
						|| terminal.homeLiveToolForTesting() != TerminalToolService.NO_TOOL) {
					throw new AssertionError("The same portal navigation button did not stop active navigation");
				}
			});
			closeTerminal(context);
			singleplayer.getServer().runOnServer(M0ClientGameTest::finishM6ReturnCrossing);
			context.waitTicks(8);
			assertLockedRenderDistance(context, Level.OVERWORLD,
					DimensionViewDistancePolicy.OVERWORLD_CHUNKS);
			context.runOnClient(client -> {
				client.getToastManager().clear();
				client.gui.getChat().clearMessages(true);
			});
			context.takeScreenshot("m6-private-return-continuity");
			singleplayer.getServer().runOnServer(M0ClientGameTest::prepareStrongholdEstimateFixture);
			context.waitTicks(4);
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			context.waitTicks(2);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!terminal.toolAvailableForTesting(TerminalTool.STRONGHOLD.slot()))
					throw new AssertionError("The verified Eye of Ender sample fixture did not unlock the stronghold tool");
				terminal.openToolForTesting(TerminalTool.STRONGHOLD.slot());
				terminal.activateSelectedToolForTesting();
				if (!"HOME".equals(terminal.pageForTesting())
						|| terminal.homeLiveToolForTesting() != TerminalTool.STRONGHOLD.slot()) {
					throw new AssertionError("Starting stronghold navigation did not create the HOME live information card");
				}
			});
			context.waitFor(client -> client.screen instanceof TerminalScreen terminal
					&& terminal.navigationActiveForTesting()
					&& terminal.guidanceToolForTesting() == TerminalTool.STRONGHOLD.slot(), 40);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (!terminal.navigationActiveForTesting()
						|| terminal.guidanceToolForTesting() != TerminalTool.STRONGHOLD.slot())
					throw new AssertionError("The stronghold estimate did not control the compass");
			});
			context.takeScreenshot("r-terminal-tool-stronghold-estimate");
			closeTerminal(context);

			singleplayer.getServer().runOnServer(M0ClientGameTest::discoverAllTerminalFiles);
			openTerminalThroughClientCallback(context);
			context.waitForScreen(TerminalScreen.class);
			setTerminalView(context, 0, TerminalControlPolicy.DEFAULT_TUNING, 0);
			context.waitTicks(5);
			context.runOnClient(client -> {
				if (!(client.screen instanceof TerminalScreen terminal)) {
					throw new AssertionError("Current mainline terminal page closed");
				}
				terminal.scrollRowsForTesting(20);
			});
			context.waitTicks(2);
			// The recovered fragment is the one catalogue entry that is served conditionally - it only
			// exists for a machine that has finished a run before. Asking for it here keeps "every file
			// there is" true, and makes this the longest list the column ever has to lay out.
			context.runOnClient(client -> {
				((TerminalScreen) client.screen).reportPreviousRunForTesting(true);
			});
			context.waitTicks(4);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				terminal.openLogDirectoryForTesting();
				// Counted against the catalogue and the layout rather than against literals: the
				// fixture above discovers every file there is, so what is being claimed is that the
				// list shows all of them, that the highlight reaches the last one, and that the
				// column scrolled exactly as far as it had to.
				int files = NarrativeFileCatalog.definitions().size();
				terminal.moveFileSelectionForTesting(1);
				terminal.moveFileSelectionForTesting(files);
				if (terminal.fileCountForTesting() != files
						|| terminal.selectedFileForTesting() != files - 1
						|| terminal.fileScrollRowForTesting() != TerminalUiLayout.fileMaxScrollRow(files)) {
					throw new AssertionError("The FILES list did not expose all " + files
							+ " consolidated file records in one column: count=" + terminal.fileCountForTesting()
							+ ", selected=" + terminal.selectedFileForTesting()
							+ ", scrollRow=" + terminal.fileScrollRowForTesting());
				}
				int fragmentFiles = 0;
				for (int index = 0; index < terminal.fileCountForTesting(); index++) {
					String id = terminal.fileIdForTesting(index);
					if (id.equals("surface_shelter_record") || id.equals("field_observation_record")
							|| id.equals("underground_mine_record") || id.equals("abandoned_warehouse_record")) {
						fragmentFiles++;
					}
				}
				if (fragmentFiles != 4) throw new AssertionError("The FILES list did not include all four damaged files");
			});
			context.waitTicks(2);

			// The Records rule again, on the file directory: being on the page is not enough, the file
			// the badge counts has to be on screen.
			//
			// Which file that is gets recovered from unlock times, and in this fixture it is the last
			// row - the one row the six-row window cannot show from the top of the list. So the badge
			// must survive at the top and clear itself once scrolled down. The first assertion below
			// also guards that premise: if the catalogue ever reorders so the newest unlock starts in
			// view, it fails there rather than quietly testing nothing.
			context.runOnClient(client -> ((TerminalScreen) client.screen)
					.moveFileSelectionForTesting(-NarrativeFileCatalog.definitions().size()));
			context.waitTicks(2);
			singleplayer.getServer().runOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				FrequencyWorldData.get(server).updateTerminalRecord(player.getUUID(),
						TerminalFileState::notifyUnread);
				TerminalRuntimeService.refresh(player);
			});
			context.waitTicks(10);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.fileScrollRowForTesting() != 0) {
					throw new AssertionError("Fixture must start from the top of the file directory");
				}
				if (terminal.unreadFileCountForTesting() < 1) {
					throw new AssertionError("A file below the fold must stay unread; if the acknowledgement"
							+ " is otherwise healthy, check whether the catalogue moved the newest unlock into view");
				}
				terminal.moveFileSelectionForTesting(NarrativeFileCatalog.definitions().size());
			});
			context.waitTicks(10);
			context.runOnClient(client -> {
				TerminalScreen terminal = (TerminalScreen) client.screen;
				if (terminal.fileScrollRowForTesting() <= 0) {
					throw new AssertionError("Fixture must scroll the file directory down to its last row");
				}
				if (terminal.unreadFileCountForTesting() != 0) {
					throw new AssertionError("Scrolling the unread file into view must acknowledge it");
				}
			});
			context.takeScreenshot("m4-file-directory-self-acknowledged");
			context.takeScreenshot("m7-file-directory-grid-current");
			closeTerminal(context);

			terminalProof = singleplayer.getServer().computeOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var worldData = FrequencyWorldData.get(server);
				var record = worldData.terminalRecord(player.getUUID()).orElseThrow();
				return new TerminalPersistenceProof(
						record.getStringOr(TerminalData.WORLD_ID, ""),
						record.getStringOr(TerminalData.TERMINAL_ID, ""),
						record.getStringOr(TerminalData.PERSONALITY_TEMPLATE, ""),
						record.getIntOr(TerminalData.BAND_STAGE, 0),
						record.getIntOr(TerminalData.MINED_BLOCKS, 0),
						record.getIntOr(TerminalData.PLACED_BLOCKS, 0),
						record.getIntOr(TerminalData.CRAFTED_ITEMS, 0),
						record.getStringOr(TerminalData.ACCEPTED_ADVICE, ""),
						record.getStringOr(TerminalData.LOCAL_FILE_HASH, ""),
						record.getIntOr(TerminalData.EMPTY_SEGMENT_COUNT, 0),
						record.getIntOr(TerminalData.PORTAL_TRANSITIONS, 0));
			});

			removedWall = ZeroStationLayout.solidWallSample(stationPosition);
			singleplayer.getServer().runOnServer(server -> {
				if (server.overworld().getBlockState(removedWall).isAir()) {
					throw new AssertionError("Expected station wall before persistence mutation");
				}
				server.overworld().setBlockAndUpdate(removedWall, Blocks.AIR.defaultBlockState());
			});
		}

		context.waitForScreen(TitleScreen.class);
		// The world that justified the lock is closed, so the option goes back to the number the
		// player had before it. Without the hand-back nothing here would fail either - the locked
		// six would simply stay in options.txt and be written to disk on the next quit, which is the
		// lock outliving its world by the least visible route available.
		context.waitFor(client -> client.options.renderDistance().get()
				.equals(TITLE_SCREEN_RENDER_DISTANCE), 100);
		context.waitFor(client -> !AlphaLoadSessionController.activeForTesting(), 100);
		int[] legacyScreensBeforeReopen = {-1};
		context.runOnClient(client -> {
			if (AlphaLoadSessionController.activeForTesting()) {
				throw new AssertionError("Alpha resource controller stayed active on the main menu");
			}
			client.updateTitle();
			if (!AlphaLoadSessionController.appliedWindowTitleForTesting().contains("Minecraft 1.0.0")) {
				throw new AssertionError("Vanilla title refresh replaced the final Minecraft 1.0.0 window title");
			}
			if (!"Minecraft 1.0.0".equals(AlphaLoadSessionController
					.menuVersionText("Minecraft 1.21.11"))) {
				throw new AssertionError("Main-menu version text did not stay downgraded after leaving the world");
			}
			if (AlphaLoadSessionController.corruptionPlayCountForTesting() != 1
					|| !AlphaLoadSessionController.javaIconAppliedForTesting()) {
				throw new AssertionError("First world entry did not finish exactly one corruption and Java icon change");
			}
			List<String> selected = client.getResourcePackRepository().getSelectedPacks().stream()
					.map(net.minecraft.server.packs.repository.Pack::getId).toList();
			if (!selected.containsAll(AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH)) {
				throw new AssertionError("Alpha base packs were unloaded when returning to the main menu: "
						+ selected);
			}
			legacyScreensBeforeReopen[0] = AlphaLoadSessionController
					.legacyLoadingScreensRenderedForTesting();
		});
		context.takeScreenshot("alpha-main-menu-version");
		if (metaAdapter.restoreCount() < 1 || !metaAdapter.ownedProcesses().isEmpty()) {
			throw new AssertionError("M8 world leave did not restore Mock-owned external state");
		}
		try (TestSingleplayerContext reopened = save.open()) {
			reopened.getClientWorld().waitForChunksDownload();
			context.waitTicks(5);
			context.runOnClient(client -> {
				if (client.screen instanceof FirstRunNoticeScreen || FirstRunNoticeController.pendingForTesting())
					throw new AssertionError("Acknowledged first-run notice appeared again after reopening a world");
				if (AlphaLoadSessionController.corruptionPlayCountForTesting() != 1
						|| AlphaLoadSessionController.legacyLoadingScreensRenderedForTesting()
						<= legacyScreensBeforeReopen[0]) {
					throw new AssertionError("Reopened world replayed corruption instead of the legacy loading screen");
				}
			});
			assertSingleOwnedTerminal(context);
			assertBoundTerminal(context);
			BlockPos loadedStation = reopened.getServer().computeOnServer(server -> {
				FrequencyWorldData data = FrequencyWorldData.get(server);
				if (!data.stationComplete()) {
					throw new AssertionError("Station completion flag did not survive restart");
				}
				if (!server.overworld().getBlockState(removedWall).isAir()) {
					throw new AssertionError("Completed station regenerated after restart");
				}
				var player = server.getPlayerList().getPlayers().getFirst();
				var record = data.terminalRecord(player.getUUID()).orElseThrow();
				if (!record.getStringOr(TerminalData.WORLD_ID, "").equals(terminalProof.worldId())
						|| !record.getStringOr(TerminalData.TERMINAL_ID, "").equals(terminalProof.terminalId())
						|| !record.getStringOr(TerminalData.PERSONALITY_TEMPLATE, "").equals(terminalProof.personality())
						|| !record.getBooleanOr(TerminalData.BOUND, false)
						// Read back against the stage this run actually reached, not a literal. The
						// literal was 3, which nothing has written since the old four-phase ending was
						// removed - the only band stage this walkthrough reaches is the one the M4
						// fixture sets. What is under test here is that the stage survives a restart,
						// and that survives the ladder changing shape again.
						|| record.getIntOr(TerminalData.BAND_STAGE, 0) != terminalProof.bandStage()
						|| terminalProof.bandStage() <= 0
						|| !record.getBooleanOr(TerminalData.SECOND_CACHE_UNLOCKED, false)
						|| record.getIntOr(TerminalData.MINED_BLOCKS, 0) != terminalProof.mined()
						|| record.getIntOr(TerminalData.PLACED_BLOCKS, 0) != terminalProof.placed()
						|| record.getIntOr(TerminalData.CRAFTED_ITEMS, 0) != terminalProof.crafted()
						|| !record.getStringOr(TerminalData.ACCEPTED_ADVICE, "").equals(terminalProof.acceptedAdvice())
						|| !terminalProof.acceptedAdvice().contains("iron")
						|| !record.getBooleanOr(TerminalData.LOCAL_FILE_UNLOCKED, false)
						|| !record.getStringOr(TerminalData.LOCAL_FILE_HASH, "").equals(terminalProof.localFileHash())
						|| record.getIntOr(TerminalData.EMPTY_SEGMENT_COUNT, 0) != terminalProof.emptySegmentCount()
						|| record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false)
						|| record.getIntOr(TerminalData.PORTAL_TRANSITIONS, 0) != terminalProof.portalTransitions()
						|| !record.getBooleanOr(TerminalData.CONTINUITY_LEARNED, false)) {
					throw new AssertionError("Terminal identity, personality, binding, or Fourth Frequency state changed after restart");
				}
				return data.stationPosition().orElseThrow();
			});
		if (!stationPosition.equals(loadedStation)) {
				throw new AssertionError("Station position changed after restart");
			}
		}

		context.waitForScreen(TitleScreen.class);
	}

	/**
	 * Focused visual suites still clear the mandatory notice, but leave its full localization and
	 * layout audit to the mainline and notice-entry suites. This keeps an unrelated notice-layout
	 * change from preventing the selected renderer suite from reaching its own fixtures.
	 */
	private static void acknowledgeFirstRunNoticeForFocusedSuite(ClientGameTestContext context) {
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen, 120);
		// The audio page comes first and is audited by the notice-entry suite; here it is one press.
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.advanceAvailableForTesting(), 160);
		context.runOnClient(client -> ((FirstRunNoticeScreen) client.screen).advanceForTesting());
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.acknowledgementAvailableForTesting(), 160);
		context.runOnClient(client -> ((FirstRunNoticeScreen) client.screen).acknowledgeForTesting());
	}

	private static void assertInitialOverworldRenderDistance(ClientGameTestContext context) {
		context.runOnClient(client -> {
			int fixed = DimensionViewDistancePolicy.OVERWORLD_CHUNKS;
			int stored = client.options.renderDistance().get();
			int effective = client.options.getEffectiveRenderDistance();
			if (stored != fixed || effective != fixed) {
				throw new AssertionError("Render distance was not initialized to the locked Overworld value"
						+ " (stored=" + stored + ", effective=" + effective + ")");
			}
			client.options.renderDistance().set(fixed + 8);
			if (client.options.getEffectiveRenderDistance() != fixed) {
				throw new AssertionError("A runtime option change bypassed the effective locked limit");
			}
			client.options.save();
			if (!client.options.renderDistance().get().equals(fixed)) {
				throw new AssertionError("Saving did not restore the stored locked option");
			}
			client.setScreen(new VideoSettingsScreen(client.screen, client, client.options));
		});
		context.waitForScreen(VideoSettingsScreen.class);
		context.waitTicks(2);
		context.runOnClient(client -> {
			OptionsList optionsList = client.screen.children().stream()
					.filter(OptionsList.class::isInstance)
					.map(OptionsList.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("Video settings did not expose its option list;"
							+ " screen=" + client.screen.getClass().getName() + ", children="
							+ client.screen.children().stream().map(child -> child.getClass().getSimpleName())
									.toList()));
			AbstractWidget renderDistance = optionsList.findOption(client.options.renderDistance());
			if (renderDistance == null) {
				throw new AssertionError("Video settings did not contain the render-distance control");
			}
			String label = renderDistance.getMessage().getString();
			String expected = "渲染距离：" + DimensionViewDistancePolicy.OVERWORLD_CHUNKS + " 个区块（已锁定）";
			if (renderDistance.active || !label.equals(expected)) {
				throw new AssertionError("Render-distance control was not visibly locked: active="
						+ renderDistance.active + ", label=" + label);
			}
			double centered = optionsList.scrollAmount() + renderDistance.getY()
					- (optionsList.getY() + optionsList.getHeight() / 2.0D - renderDistance.getHeight() / 2.0D);
			optionsList.setScrollAmount(Math.max(0.0D, Math.min(optionsList.maxScrollAmount(), centered)));
		});
		context.waitTicks(2);
		context.takeScreenshot("render-distance-locked-overworld-chunks");
		context.runOnClient(client -> client.screen.onClose());
		// Runs inside the world now rather than on the title screen, so closing the settings returns
		// to the game rather than to a menu. See assertRenderDistanceIsUntouchedOutsideAModWorld for
		// why it moved: the lock is scoped to worlds this mod is running in, and the title screen is
		// not one of them.
		context.waitFor(client -> client.screen == null, 100);
	}

	/**
	 * On the title screen the render distance is the player's again, and visibly so.
	 *
	 * <p>This assertion is the other half of the one above, and it exists because for a long time
	 * there was no other half: {@code enforce} ran on every client tick with no world test at all, so
	 * installing this mod pinned the option to six chunks on the menu, in unrelated single-player
	 * saves and on other people's servers, with the slider greyed out and nothing anywhere to explain
	 * it. The value set here is deliberately left in place - the world entry that follows locks it,
	 * and the hand-back is checked against this number once that world closes.
	 */
	private static void assertRenderDistanceIsUntouchedOutsideAModWorld(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (DimensionViewDistanceController.isLocked()) {
				throw new AssertionError("The render-distance lock reached the title screen, where "
						+ "there is no world for it to be about");
			}
			client.options.renderDistance().set(TITLE_SCREEN_RENDER_DISTANCE);
			if (!client.options.renderDistance().get().equals(TITLE_SCREEN_RENDER_DISTANCE)) {
				throw new AssertionError("A render-distance change outside a mod world was overwritten");
			}
			client.setScreen(new VideoSettingsScreen(client.screen, client, client.options));
		});
		context.waitForScreen(VideoSettingsScreen.class);
		context.waitTicks(2);
		context.runOnClient(client -> {
			OptionsList optionsList = client.screen.children().stream()
					.filter(OptionsList.class::isInstance)
					.map(OptionsList.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("Video settings did not expose its option list"));
			AbstractWidget renderDistance = optionsList.findOption(client.options.renderDistance());
			if (renderDistance == null || !renderDistance.active) {
				throw new AssertionError("The render-distance control must stay usable outside a mod world");
			}
			client.screen.onClose();
		});
		context.waitForScreen(TitleScreen.class);
	}

	/** Comfortably above every locked value, so "unchanged" cannot be confused with "locked". */
	private static final int TITLE_SCREEN_RENDER_DISTANCE = DimensionViewDistancePolicy.END_CHUNKS + 4;

	private static void assertLockedRenderDistance(ClientGameTestContext context,
			net.minecraft.resources.ResourceKey<Level> dimension, int expected) {
		context.waitFor(client -> client.level != null && client.level.dimension() == dimension
				&& client.getOverlay() == null
				&& !(client.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen)
				&& client.options.renderDistance().get().equals(expected)
				&& client.options.getEffectiveRenderDistance() == expected, 300);
		context.runOnClient(client -> {
			int rejected = expected == 12 ? 2 : 12;
			client.options.renderDistance().set(rejected);
			if (!client.options.renderDistance().get().equals(expected)
					|| client.options.getEffectiveRenderDistance() != expected) {
				throw new AssertionError("Render-distance setter bypassed the dimension lock for "
						+ dimension.identifier() + " (expected=" + expected + ")");
			}
		});
	}

	/**
	 * The audio page: it is the first thing the player meets, it owns the mod's master volume, and
	 * the only way off it is the button at the bottom.
	 *
	 * <p>The volume is put back before leaving. The page writes the config file on the way out, and
	 * a suite that left the mod at forty percent would be quietly changing what every later run of
	 * every other client test hears.</p>
	 */
	private static void assertAndLeaveFirstRunAudioPage(ClientGameTestContext context) {
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.presentationPhaseForTesting() == FirstRunNoticeScreen.PresentationPhase.NOTICE
				&& notice.advanceAvailableForTesting()
				&& client.getLanguageManager().getSelected().equals("zh_cn"), 160);
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			if (notice.pageForTesting() != FirstRunNoticeScreen.Page.AUDIO)
				throw new AssertionError("First-run flow did not open on the audio page");
			if (notice.acknowledgementAvailableForTesting())
				throw new AssertionError("The disclosure's acknowledgement must not exist on the audio page");
			if (!notice.advanceLabelForTesting().getString().equals("下一步"))
				throw new AssertionError("Audio page advance label changed: "
						+ notice.advanceLabelForTesting().getString());
			if (!notice.previewLabelForTesting().getString().equals("试听"))
				throw new AssertionError("Audio page audition label changed: "
						+ notice.previewLabelForTesting().getString());
			if (!notice.audioPageLayoutFitsForTesting() || !notice.allTextInsideGlassForTesting())
				throw new AssertionError("Audio page does not fit inside the terminal glass");

			double original = notice.volumeForTesting();
			int previewsBefore = notice.volumePreviewPlayCountForTesting();
			notice.previewVolumeForTesting();
			if (notice.volumePreviewPlayCountForTesting() != previewsBefore + 1)
				throw new AssertionError("The audition button did not play the mod's own cue");

			notice.setVolumeForTesting(0.4D);
			if (Math.abs(notice.volumeForTesting() - 0.4D) > 1.0E-6D)
				throw new AssertionError("Moving the slider did not move the live mod volume");
			if (!notice.volumeLabelForTesting().getString().equals("MOD 总音量 40%"))
				throw new AssertionError("Slider readout disagrees with the stored volume: "
						+ notice.volumeLabelForTesting().getString());
			notice.setVolumeForTesting(0.0D);
			if (!notice.volumeLabelForTesting().getString().equals("MOD 总音量 静音"))
				throw new AssertionError("Silence is not named on the slider: "
						+ notice.volumeLabelForTesting().getString());
			notice.setVolumeForTesting(original);
			if (Math.abs(notice.volumeForTesting() - original) > 1.0E-6D)
				throw new AssertionError("Audio page could not be restored to the volume it opened with");
		});
		context.takeScreenshot("m1-first-run-audio-page-zh-cn");
		switchFirstRunNoticeLanguage(context, "en_us");
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& client.getLanguageManager().getSelected().equals("en_us")
				&& notice.advanceLabelForTesting().getString().equals("Next"), 160);
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			if (!notice.audioPageLayoutFitsForTesting() || !notice.allTextInsideGlassForTesting())
				throw new AssertionError("English audio page does not fit the compact window");
			if (!notice.previewLabelForTesting().getString().equals("Test"))
				throw new AssertionError("English audition label changed");
		});
		context.takeScreenshot("m1-first-run-audio-page-en-us");
		switchFirstRunNoticeLanguage(context, "zh_cn");
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& client.getLanguageManager().getSelected().equals("zh_cn")
				&& notice.advanceAvailableForTesting(), 160);
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			notice.advanceForTesting();
			if (!notice.pageTransitionActiveForTesting() || notice.advanceAvailableForTesting()
					|| notice.acknowledgementAvailableForTesting())
				throw new AssertionError("Page transition must disable both pages' actions");
			notice.advanceForTesting();
			notice.acknowledgeForTesting();
			notice.reinitializeForTesting();
			if (notice.advanceAvailableForTesting() || notice.acknowledgementAvailableForTesting())
				throw new AssertionError("Rebuilding widgets bypassed the transition gate");
			if (FirstRunNoticeController.acknowledgedForTesting())
				throw new AssertionError("Leaving the audio page spent the acknowledgement it does not own");
		});
		context.waitTicks(7);
		context.takeScreenshot("m1-first-run-page-transition");
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.pageForTesting() == FirstRunNoticeScreen.Page.NOTICE
				&& notice.acknowledgementAvailableForTesting(), 80);
	}

	private static void assertAndAcknowledgeFirstRunNotice(ClientGameTestContext context) {
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen, 120);
		context.runOnClient(client -> {
			if (!(client.screen instanceof FirstRunNoticeScreen notice))
				throw new AssertionError("Opening the game to the title page did not open the safety notice");
			if (client.level != null || client.player != null)
				throw new AssertionError("First-run notice waited until a world existed instead of opening on the title page");
			if (notice.openingSoundPlayCountForTesting() != 1 || TerminalClientAudio.lockPlaysForTesting() != 0)
				throw new AssertionError("First-run startup sound did not remain isolated from terminal lock statistics");
			if (notice.shouldCloseOnEsc() || !notice.isPauseScreen())
				throw new AssertionError("First-run notice title-page close contract changed");
			notice.onClose();
			if (client.screen != notice || FirstRunNoticeController.acknowledgedForTesting())
				throw new AssertionError("First-run notice was bypassed through its ordinary close path");
			if (notice.presentationPhaseForTesting() != FirstRunNoticeScreen.PresentationPhase.POWER_ON
					|| notice.acknowledgementAvailableForTesting()
					|| notice.advanceAvailableForTesting())
				throw new AssertionError("First-run notice did not begin with a non-bypassable power-on screen");
			if (notice.pageForTesting() != FirstRunNoticeScreen.Page.AUDIO)
				throw new AssertionError("The tube must light up on the audio page, not on the disclosure");
		});
		context.waitTicks(16);
		context.takeScreenshot("m1-first-run-crt-power-on");
		switchFirstRunNoticeLanguage(context, "zh_cn");
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			if (notice.openingSoundPlayCountForTesting() != 1)
				throw new AssertionError("First-run notice replayed startup audio when its widgets were rebuilt");
		});
		assertAndLeaveFirstRunAudioPage(context);
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.presentationPhaseForTesting() == FirstRunNoticeScreen.PresentationPhase.NOTICE
				&& notice.acknowledgementAvailableForTesting()
				&& client.getLanguageManager().getSelected().equals("zh_cn")
				&& notice.getTitle().getString().equals("继续前，请阅读"), 160);
		context.takeScreenshot("m1-first-run-safety-notice-zh-cn");
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			if (!client.getLanguageManager().getSelected().equals("zh_cn"))
				throw new AssertionError("First-run notice resource reload did not select Chinese");
			if (!notice.getTitle().getString().equals("继续前，请阅读"))
				throw new AssertionError("First-run notice title was not localized in Chinese");
			if (!notice.acknowledgementLabelForTesting().getString().equals("我已了解"))
				throw new AssertionError("First-run notice acknowledgement label changed");
			boolean bottomButton = notice.acknowledgementButtonIsAtBottomForTesting();
			boolean layoutFits = notice.dedicatedLayoutFitsForTesting();
			boolean aligned = notice.allElementsAlignedForTesting();
			boolean insideGlass = notice.allTextInsideGlassForTesting();
			boolean punctuation = notice.avoidsOrphanPunctuationForTesting();
			if (!bottomButton || !layoutFits || !aligned || !insideGlass || !punctuation) {
				throw new AssertionError("Localized first-run notice layout changed: bottomButton=" + bottomButton
						+ ", layoutFits=" + layoutFits + ", aligned=" + aligned
						+ ", insideGlass=" + insideGlass + ", punctuation=" + punctuation);
			}
			if (!notice.acknowledgementAvailableForTesting()
					|| notice.openingSoundPlayCountForTesting() != 1
					|| notice.stableSoundPlayCountForTesting() != 1
					|| TerminalClientAudio.lockPlaysForTesting() != 0)
				throw new AssertionError("First-run notice did not appear after one complete tube power-on");
			notice.reinitializeForTesting();
			if (!notice.acknowledgementAvailableForTesting()
					|| notice.openingSoundPlayCountForTesting() != 1
					|| notice.stableSoundPlayCountForTesting() != 1)
				throw new AssertionError("First-run notice replayed audio or disabled itself after reinitialization");
		});
		switchFirstRunNoticeLanguage(context, "en_us");
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& client.getLanguageManager().getSelected().equals("en_us")
				&& notice.getTitle().getString().equals("READ BEFORE CONTINUING")
				&& notice.acknowledgementLabelForTesting().getString().equals("I Understand"), 160);
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			if (!notice.dedicatedLayoutFitsForTesting() || !notice.allElementsAlignedForTesting()
					|| !notice.allTextInsideGlassForTesting() || !notice.avoidsOrphanPunctuationForTesting())
				throw new AssertionError("English first-run notice layout does not fit the compact window");
		});
		context.takeScreenshot("m1-first-run-safety-notice-en-us");
		switchFirstRunNoticeLanguage(context, "zh_cn");
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& client.getLanguageManager().getSelected().equals("zh_cn")
				&& notice.getTitle().getString().equals("继续前，请阅读"), 160);
		context.runOnClient(client -> ((FirstRunNoticeScreen) client.screen).acknowledgeForTesting());
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.presentationPhaseForTesting() == FirstRunNoticeScreen.PresentationPhase.TRANSITION
				&& notice.zoomProgressForTesting() >= 0.30F, 60);
		context.runOnClient(client -> {
			if (FirstRunNoticeController.acknowledgedForTesting())
				throw new AssertionError("First-run acknowledgement persisted before the entry transition finished");
		});
		context.takeScreenshot("m1-first-run-terminal-entry-transition");
		context.runOnClient(client -> {
			var chain = com.xm.thefourthfrequency.client_ui.ScreenFilterDriver.lastApplied();
			if (chain == null || !chain.getPath().startsWith("terminal_entry_"))
				throw new AssertionError("The entry lens shader did not render on the actual frame");
		});
		context.waitFor(client -> client.screen instanceof FirstRunNoticeScreen notice
				&& notice.zoomProgressForTesting() >= .85F, 80);
		context.takeScreenshot("m1-first-run-terminal-entry-near-glass");
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(2);
		context.runOnClient(client -> {
			if (com.xm.thefourthfrequency.client_ui.ScreenFilterDriver.lastApplied() != null)
				throw new AssertionError("The entry lens filter outlived the terminal");
		});
		context.runOnClient(client -> {
			if (client.level != null || client.player != null)
				throw new AssertionError("Acknowledging the notice did not return to the pre-world title page");
			// Round-trip through the disk rather than checking that some file exists. The
			// acknowledgement is kept in its own marker beside the unified config, not inside it -
			// ModConfig.ClientState carries no first-run field - so asserting on thefourthfrequency.json
			// was testing an unrelated file. It only ever passed because the run directory was reused
			// between runs and already had one; on a genuinely clean run directory it failed.
			FirstRunNoticeController.reloadFromDiskForTesting();
			if (!FirstRunNoticeController.acknowledgedForTesting())
				throw new AssertionError("First-run acknowledgement did not survive a reload from disk");
		});
	}

	private static void switchFirstRunNoticeLanguage(ClientGameTestContext context, String languageCode) {
		java.util.concurrent.atomic.AtomicBoolean reloaded = new java.util.concurrent.atomic.AtomicBoolean();
		context.runOnClient(client -> {
			FirstRunNoticeScreen notice = (FirstRunNoticeScreen) client.screen;
			client.options.languageCode = languageCode;
			client.getLanguageManager().setSelected(languageCode);
			client.reloadResourcePacks().thenRun(() -> reloaded.set(true));
			notice.reinitializeForTesting();
		});
		context.waitFor(client -> reloaded.get(), 240);
		context.waitFor(client -> client.getOverlay() == null, 160);
		context.waitTicks(20);
	}

	private static void openTerminalThroughClientCallback(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.player == null || client.level == null) {
				throw new AssertionError("Client world is not ready");
			}
			InteractionResult result = UseItemCallback.EVENT.invoker()
					.interact(client.player, client.level, InteractionHand.MAIN_HAND);
			if (result != InteractionResult.SUCCESS) {
				throw new AssertionError("Using the held terminal did not send its private open request");
			}
		});
	}

	/** Appends one unread record to the single player's log, the way the game itself would. */
	private static void recordSignal(TestSingleplayerContext singleplayer, String type) {
		singleplayer.getServer().runOnServer(server -> TerminalSignalService.record(
				server.getPlayerList().getPlayers().getFirst(),
				com.xm.thefourthfrequency.terminal.SignalBand.UNKNOWN, type, 0, 1, true));
	}

	private static void closeTerminal(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.screen instanceof TerminalScreen terminal) terminal.onClose();
			else client.setScreen(null);
		});
		context.waitTicks(2);
	}

	/**
	 * Walks the first-boot walkthrough to its end.
	 *
	 * <p>A brand new save owes it, and while it runs the terminal refuses every page change except
	 * the one it is pointing at - so without this, every later page assertion in this file would be
	 * looking at the home page instead. It also serves as this suite's coverage of the walkthrough:
	 * that it hands the terminal back, and that it does so by being completed rather than by timing
	 * out.</p>
	 *
	 * <p>Polls instead of waiting a fixed number of ticks, because the self test is timed in real
	 * milliseconds and a tick count is not a reliable way to wait it out.</p>
	 */
	/**
	 * Waits out the self test and captures the first step that points at a tab.
	 *
	 * <p>Covers the brief - the one line saying what the tab being pointed at is actually for. The
	 * walkthrough is the only place it is ever drawn, and {@link #completeFirstBootWalkthrough}
	 * clicks through the four steps as fast as it can poll, so without stopping here no frame of it
	 * would ever be recorded.</p>
	 */
	private static void captureOnboardingStep(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 100; attempt++) {
			boolean[] pointing = {false};
			context.runOnClient(client -> {
				if (client.screen instanceof TerminalScreen terminal) {
					pointing[0] = terminal.onboardingLocksExitForTesting()
							&& terminal.onboardingTargetPageForTesting() >= 0;
				}
			});
			if (pointing[0]) {
				// Let the step actually arrive before recording it. The phase flips a frame before
				// anything is drawn - drawStep returns early while the scene transition is still at
				// zero - and the explanation then types itself in over about a second and a quarter.
				// Screenshotting on the flip captured a bare terminal every run, so the suite's only
				// picture of the walkthrough showed none of it. Long enough here for the panel, all
				// three lines and the advance button to have settled.
				context.waitTicks(45);
				context.takeScreenshot("r-terminal-onboarding-step-brief");
				return;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("The walkthrough never reached a step that points at a tab");
	}

	/**
	 * The finished task is still on the card when the walkthrough lets go.
	 *
	 * <p>This is the one moment in the whole game where a task completes with the terminal already
	 * open, and it is the moment the reward arrives without the player pressing anything. The
	 * snapshot carrying the delivery already names the next task, so without the hold the card
	 * showed an empty bar for an objective the player had not read while six bread appeared in their
	 * inventory unexplained.</p>
	 *
	 * <p>Polled rather than checked once: the fourth visit goes to the server and the reward comes
	 * back, so the hold starts a round trip after the walkthrough releases.</p>
	 */
	private static void assertFirstTaskCompletionIsShown(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 40; attempt++) {
			boolean[] held = {false};
			context.runOnClient(client -> {
				if (client.screen instanceof TerminalScreen terminal) {
					held[0] = terminal.taskCompletionHeldForTesting();
				}
			});
			if (held[0]) {
				context.takeScreenshot("r-terminal-first-task-completion-hold");
				return;
			}
			context.waitTicks(1);
		}
		throw new AssertionError("The finished first task was never shown on the home card");
	}

	/**
	 * Answers the first-boot profile, the way a player does.
	 *
	 * <p>The profile sits between the self test and the tab tour and holds the exit while it is up, so
	 * without this every later assertion in this file waits out a screen it does not know how to
	 * leave. It is also this suite's only coverage of the profile actually working end to end.
	 *
	 * <p>It parks the receiver on an option and then waits, rather than sending an answer. Everything
	 * between those two points is the part worth testing: the lock, the one-second hold, the packet,
	 * and the server advancing its own question. A hook that posted the answer directly would prove
	 * only that the server can count.
	 *
	 * <p>The terminal would in fact finish this unaided - fifteen seconds without a lock and it sweeps
	 * to the nearest option itself, which then commits. That path is deliberately not relied on here:
	 * five questions of it is well over a minute, and a test that waited it out would be asserting the
	 * failsafe rather than the interaction.
	 */
	private static void completeFirstBootProfile(ClientGameTestContext context) {
		int lastQuestion = -1;
		for (int attempt = 0; attempt < 200; attempt++) {
			// Three numbers, because "no question on screen" has three different meanings here and
			// only one of them means the profile is behind us. It is also -1 through the self test,
			// which has not started the profile yet, and through the two-second acknowledgement, which
			// has finished the questions but not the scene. Leaving on either of those would drop the
			// caller into a walkthrough that still has a form up.
			int[] state = {-1, 0, -1};
			context.runOnClient(client -> {
				if (client.screen instanceof TerminalScreen terminal) {
					state[0] = terminal.profileQuestionForTesting();
					state[1] = terminal.onboardingLocksExitForTesting() ? 1 : 0;
					state[2] = terminal.onboardingTargetPageForTesting();
				}
			});
			// A tab is being pointed at, or the terminal has been handed back: the profile is done.
			if (state[2] >= 0 || state[1] == 0) return;
			if (state[0] >= 0 && state[0] != lastQuestion) {
				lastQuestion = state[0];
				if (state[0] == 0) {
					// Same reason as the walkthrough step: the question is caught the frame it becomes
					// current, and the scene takes about six hundred milliseconds to arrive - blank,
					// then rows settling in one after another. Screenshotting on the flip recorded the
					// settle rather than the screen, so the suite's picture of the profile showed a
					// question nobody could read and no answer band at all. Wait for it to land.
					context.waitTicks(20);
					context.takeScreenshot("r-terminal-profile-question");
				}
				context.runOnClient(client -> {
					if (client.screen instanceof TerminalScreen terminal) {
						terminal.tuneToProfileOptionForTesting(0);
					}
				});
			}
			context.waitTicks(4);
		}
		throw new AssertionError("The first-boot profile never finished");
	}

	private static void completeFirstBootWalkthrough(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 80; attempt++) {
			boolean[] held = {false};
			context.runOnClient(client -> {
				if (!(client.screen instanceof TerminalScreen terminal)) return;
				held[0] = terminal.onboardingLocksExitForTesting();
				int target = terminal.onboardingTargetPageForTesting();
				// -1 during the self test, which takes no input at all - just wait it out.
				if (target >= 0) terminal.selectPageForTesting(target);
			});
			if (!held[0]) return;
			context.waitTicks(4);
		}
		throw new AssertionError("The first-boot walkthrough never released the terminal");
	}

	/**
	 * The device is up against the lens and the camera has leaned in.
	 *
	 * <p>The screen being open is not evidence that the performance ran: the animator is what
	 * creates the screen, so a broken transform or a mis-registered mixin would still produce a
	 * terminal UI over an item that never moved. This asserts the two things the player would
	 * actually notice going missing.</p>
	 */
	private static void assertHandheldPerformanceRaised(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (TerminalHandheldAnimator.state() != TerminalHandheldAnimator.State.OPEN) {
				throw new AssertionError("The terminal screen opened without the device finishing its rise: "
						+ TerminalHandheldAnimator.state());
			}
			if (TerminalHandheldAnimator.openness() < 1.0D) {
				throw new AssertionError("An open terminal must be fully raised");
			}
			if (TerminalHandheldAnimator.fovScale() >= 1.0F) {
				throw new AssertionError("The camera never leaned in toward the CRT");
			}
		});
	}

	/**
	 * Everything the performance added is gone.
	 *
	 * <p>Polls rather than waiting a fixed count: the travel is timed in milliseconds, so how many
	 * ticks it spans depends on the frame rate the runner happens to get. A stuck field of view is
	 * the failure that matters most here - it would follow the player around the world.</p>
	 */
	private static void assertHandheldPerformanceReturnedToRest(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 40; attempt++) {
			boolean[] settled = {false};
			context.runOnClient(client -> settled[0] =
					TerminalHandheldAnimator.state() == TerminalHandheldAnimator.State.IDLE);
			if (settled[0]) {
				context.runOnClient(client -> {
					if (TerminalHandheldAnimator.fovScale() != 1.0F) {
						throw new AssertionError("A closed terminal left the camera zoomed: "
								+ TerminalHandheldAnimator.fovScale());
					}
					if (TerminalHandheldAnimator.openness() != 0.0D) {
						throw new AssertionError("A closed terminal stayed raised");
					}
				});
				return;
			}
			context.waitTicks(2);
		}
		throw new AssertionError("The terminal never came back down after its screen closed");
	}

	/**
	 * Screenshots of the carried device at the extremes of the view angle.
	 *
	 * <p>Evidence for manual review, not an assertion. First-person hands are drawn in camera
	 * space, so turning should not move the terminal - but they are also drawn against the world's
	 * depth buffer, and this device is far larger and far more central than the item vanilla
	 * expects there. Straight up and straight down are where that would show.</p>
	 *
	 * <p>Deliberately not asserted: the angles are set client-side and the server corrects them
	 * back within a tick or two, so what these frames caught is not reliable enough to fail a
	 * build on. They exist so a human can look.</p>
	 */
	private static void captureHandheldAtViewExtremes(ClientGameTestContext context) {
		for (var view : new float[][]{{-90.0F, 0.0F}, {90.0F, 0.0F}, {0.0F, 90.0F}}) {
			context.runOnClient(client -> {
				client.player.setXRot(view[0]);
				client.player.setYRot(view[1]);
				client.player.xRotO = view[0];
				client.player.yRotO = view[1];
			});
			context.waitTicks(3);
			context.takeScreenshot("r-terminal-handheld-view-"
					+ (int) view[0] + "-" + (int) view[1]);
		}
		context.runOnClient(client -> {
			client.player.setXRot(0.0F);
			client.player.setYRot(0.0F);
			client.player.xRotO = 0.0F;
			client.player.yRotO = 0.0F;
		});
		context.waitTicks(3);
	}

	private static void setTerminalView(ClientGameTestContext context, int mode, int tuning, int cache) {
		context.runOnClient(client -> {
			if (!(client.screen instanceof TerminalScreen terminal)) {
				throw new AssertionError("Terminal screen was not open for control input");
			}
			terminal.selectModeForTesting(mode);
			terminal.setTuningForTesting(tuning);
			if (mode == TerminalControlPolicy.Mode.FILES.ordinal()) terminal.openLogEntryForTesting(cache);
		});
	}

	private static void verifyReceiverAudioLifecycle(ClientGameTestContext context, int target) {
        context.runOnClient(client -> {
            TerminalScreen terminal = (TerminalScreen) client.screen;
            int locks = TerminalClientAudio.lockPlaysForTesting();
            int detents = TerminalClientAudio.detentPlaysForTesting();
            for (int value = Math.max(0, target - 4); value <= target; value++) {
                terminal.setTuningForTesting(value);
            }
            if (TerminalClientAudio.lockPlaysForTesting() != locks + 1
                    || TerminalClientAudio.detentPlaysForTesting() <= detents) {
                throw new AssertionError("Receiver lost its notch or successful lock feedback");
            }
            var engine = ((com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor)
                    client.getSoundManager()).thefourthfrequency$soundEngine();
            var sounds = ((com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor)
                    engine).thefourthfrequency$instanceToChannel();
            if (sounds.keySet().stream().anyMatch(sound -> sound.getIdentifier().getPath().equals("terminal_tune"))) {
                throw new AssertionError("Dragging the receiver layered a sweep over its notch feedback");
            }
        });
        double[] savedVolume = new double[1];
        context.runOnClient(client -> savedVolume[0] = com.xm.thefourthfrequency.client_ui.ModVolumeControl.current());
        try {
            context.runOnClient(client -> {
                com.xm.thefourthfrequency.client_ui.ModVolumeControl.apply(0);
                TerminalClientAudio.carrierOn();
            });
            // Channel stop is scheduled on the sound thread; allow the engine's deletion grace.
            context.waitTicks(25);
            context.runOnClient(client -> {
                var engine = ((com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor)
                        client.getSoundManager()).thefourthfrequency$soundEngine();
                var sounds = ((com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor)
                        engine).thefourthfrequency$instanceToChannel();
                if (sounds.keySet().stream().anyMatch(sound -> sound.getIdentifier().getPath().equals("terminal_carrier")))
                    throw new AssertionError("Muted terminal retained its carrier channel");
                com.xm.thefourthfrequency.client_ui.ModVolumeControl.apply(.8);
                TerminalClientAudio.carrierOn();
                TerminalClientAudio.carrierOn();
            });
            context.waitTicks(3);
            context.runOnClient(client -> {
                var engine = ((com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor)
                        client.getSoundManager()).thefourthfrequency$soundEngine();
                var sounds = ((com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor)
                        engine).thefourthfrequency$instanceToChannel();
                if (sounds.keySet().stream().filter(sound -> sound.getIdentifier().getPath().equals("terminal_carrier")).count() != 1)
                    throw new AssertionError("Original carrier did not resume exactly once after unmute");
            });
        } finally {
            context.runOnClient(client -> com.xm.thefourthfrequency.client_ui.ModVolumeControl.apply(savedVolume[0]));
        }
    }

	private static void assertSingleOwnedTerminal(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.player == null) {
				throw new AssertionError("Client player was not present");
			}
			int owned = 0;
			for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
				var stack = client.player.getInventory().getItem(slot);
				if (stack.is(ModItems.OLD_TERMINAL)) {
					if (!TerminalData.belongsTo(stack, client.player.getUUID())) {
						throw new AssertionError("Terminal owner data did not match the player");
					}
					owned++;
				}
			}
			if (owned != 1) {
				throw new AssertionError("Expected exactly one personal terminal, got " + owned);
			}
		});
	}

	private static void assertBoundTerminal(ClientGameTestContext context) {
		assertBoundTerminalState(context);
	}

	private static void assertMiningCompletionState(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.player == null) throw new AssertionError("Client player was not present");
			for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
				var stack = client.player.getInventory().getItem(slot);
				if (!stack.is(ModItems.OLD_TERMINAL)) continue;
				if (!TerminalData.isBound(stack) || !TerminalData.secondCacheUnlocked(stack))
					throw new AssertionError("Mining completion did not preserve the bound terminal state");
				if (TerminalData.bandStage(stack) != 1)
					throw new AssertionError("Mining completion did not synchronize the fourth-band reveal");
				return;
			}
			throw new AssertionError("Client had no terminal to verify");
		});
	}

	private static void assertBoundTerminalState(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.player == null) {
				throw new AssertionError("Client player was not present");
			}
			for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
				var stack = client.player.getInventory().getItem(slot);
				if (stack.is(ModItems.OLD_TERMINAL)) {
					if (!TerminalData.isBound(stack)
							|| !TerminalData.secondCacheUnlocked(stack)) {
						throw new AssertionError("Bound terminal state was not synchronized to the client item");
					}
					return;
				}
			}
			throw new AssertionError("Client had no terminal to verify");
		});
	}

	private static void assertArchiveUnlocked(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (client.player == null) {
				throw new AssertionError("Client player was not present");
			}
			for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
				var stack = client.player.getInventory().getItem(slot);
				if (stack.is(ModItems.OLD_TERMINAL)) {
					var tag = TerminalData.copyTag(stack);
					if (!tag.getBooleanOr(TerminalData.LOCAL_FILE_UNLOCKED, false)
							|| !"TFF-WF-02-CONTINUITY".equals(
									tag.getStringOr(TerminalData.LOCAL_FILE_HASH, ""))) {
						throw new AssertionError("Server-authoritative archive result did not synchronize to the client terminal");
					}
					return;
				}
			}
			throw new AssertionError("Client had no terminal for archive verification");
		});
	}

	private static M4ClientFixture prepareM4Fixture(MinecraftServer server) {
		var player = server.getPlayerList().getPlayers().getFirst();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.BAND_STAGE, Math.max(2, record.getIntOr(TerminalData.BAND_STAGE, 0)));
			record.putInt(TerminalData.PLOT_STAGE, Math.max(3, record.getIntOr(TerminalData.PLOT_STAGE, 1)));
			record.putBoolean(TerminalData.BOUND, true);
			// M4 is meant to be looked at through the stage-one panel: the cyan chrome and the
			// carrier trace with the ECG mixed in. That used to follow from PLOT_STAGE above, and
			// PLOT_STAGE is why this line reads as it does; the panel stage moved onto the pursuit
			// record and this fixture stayed where it was, so every M4 frame below was being taken
			// at stage zero. Written in the key the authority actually reads, and checked against
			// the policy right after so the next time the gate moves it fails here, at the setup,
			// instead of two hundred lines later as an unexplained waveform number.
			record.putInt(TerminalData.PURSUIT_RESOLVED_CHASES, 1);
			record.putInt(TerminalData.PURSUIT_ENCOUNTERED_CHASES, 1);
			int stage = PursuitProgressPolicy.terminalVisualStage(
					record.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, 0),
					record.getIntOr(TerminalData.PURSUIT_ALLOWED_FORM, 0),
					record.getIntOr(TerminalData.ANOMALY_TIER, 0));
			if (stage != 1) {
				throw new AssertionError("M4 fixture no longer produces the stage-one terminal panel: stage=" + stage);
			}
		});
		BlockPos origin = player.blockPosition();
		List<FragmentInvestigationService.Candidate> candidates = List.of(
				m4Candidate(0, FragmentInvestigationService.Group.MINESHAFT, origin.offset(120, -20, 40)),
				m4Candidate(0, FragmentInvestigationService.Group.SHIPWRECK, origin.offset(-180, 0, 90)),
				m4Candidate(0, FragmentInvestigationService.Group.TRAIL_RUINS, origin.offset(75, -8, -210)),
				m4Candidate(1, FragmentInvestigationService.Group.WOODLAND_MANSION, origin.offset(300, 0, 130)),
				m4Candidate(1, FragmentInvestigationService.Group.DESERT_PYRAMID, origin.offset(-260, 0, -150)),
				m4Candidate(1, FragmentInvestigationService.Group.IGLOO, origin.offset(90, 0, 340)),
				m4Candidate(2, FragmentInvestigationService.Group.TRIAL_CHAMBERS, origin.offset(-330, -25, 70)),
				m4Candidate(2, FragmentInvestigationService.Group.PILLAGER_OUTPOST, origin.offset(410, 0, -80)),
				m4Candidate(2, FragmentInvestigationService.Group.OCEAN_MONUMENT, origin.offset(40, 0, -460)),
				m4Candidate(3, FragmentInvestigationService.Group.ANCIENT_CITY, origin.offset(-420, -40, -220)),
				m4Candidate(3, FragmentInvestigationService.Group.OCEAN_RUINS, origin.offset(480, 0, 210)),
				m4Candidate(3, FragmentInvestigationService.Group.RUINED_PORTAL, origin.offset(-110, 0, 520)));
		FragmentInvestigationService.setCandidatesForTesting(data, candidates);
		TerminalSignalService.updatePlayerForTesting(player);
		FragmentInvestigationService.discoverForTesting(player, candidates.getFirst());
		FragmentInvestigationService.setNearbyForTesting(player, candidates.get(3));
		TerminalAnomalyLogService.record(player, "phantom_echo", 0, 1, 20, false);
		TerminalAnomalyLogService.record(player, "light_dropout", 1, 1, 20, false);
		// Deliberately a retired id: this is what a save written before the merge holds, and the
		// records page still has to render it as a line rather than as a raw identifier.
		TerminalAnomalyLogService.record(player, "surface_fracture", 2, 1, 20, false);
		TerminalAnomalyLogService.record(player, "watcher_alignment", 3, 1, 20, false);
		return new M4ClientFixture(FragmentInvestigationService.receiverTuning(candidates.get(3)));
	}

	private static void completeM4Fragments(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		BlockPos origin = player.blockPosition();
		FragmentInvestigationService.discoverForTesting(player,
				m4Candidate(2, FragmentInvestigationService.Group.TRIAL_CHAMBERS, origin.offset(-330, -25, 70)));
		FragmentInvestigationService.discoverForTesting(player,
				m4Candidate(3, FragmentInvestigationService.Group.ANCIENT_CITY, origin.offset(-420, -40, -220)));
	}

	private static FragmentInvestigationService.Candidate m4Candidate(int fragment,
			FragmentInvestigationService.Group group, BlockPos position) {
		return new FragmentInvestigationService.Candidate(fragment, group, position, "minecraft:overworld");
	}

	private static void triggerEmptySegment(MinecraftServer server, EmptySegmentService.EventType type, int duration) {
		if (!EmptySegmentService.trigger(server.getPlayerList().getPlayers().getFirst(), type, duration)) {
			throw new AssertionError("Could not trigger client empty-segment fixture " + type);
		}
	}

	private static void beginM6NetherCrossing(MinecraftServer server) {
		var player = server.getPlayerList().getPlayers().getFirst();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		data.updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
					record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0)
							| SurvivalMilestone.PREPARED_NETHER.mask());
		});
		var nether = server.getLevel(Level.NETHER);
		if (nether == null) throw new AssertionError("Nether fixture dimension missing");
		// The navigation test needs a survival player, but an arbitrary seed may put (0,64,0)
		// inside lava. Prepare a sealed landing so the test exercises portal state, not drowning.
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -1; y <= 4; y++) {
			boolean shell = y == -1 || y == 4 || Math.abs(x) == 3 || Math.abs(z) == 3;
			nether.setBlockAndUpdate(new BlockPos(x, 64 + y, z),
					(shell ? Blocks.POLISHED_BLACKSTONE : Blocks.AIR).defaultBlockState());
		}
		player.clearFire();
		player.setHealth(player.getMaxHealth());
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		if (!player.teleportTo(nether, 0.5, 64.0, 0.5,
				Set.of(), 0.0F, 0.0F, true)) {
			throw new AssertionError("M6 client fixture could not cross into the Nether");
		}
	}

	private static void finishM6ReturnCrossing(MinecraftServer server) {
		var player = server.getPlayerList().getPlayers().getFirst();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		BlockPos gallery = data.stationPosition().orElse(player.blockPosition());
		BlockPos landing = gallery.offset(-9, 0, -9);
		for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
			server.overworld().setBlock(landing.offset(x, -1, z), Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
			for (int y = 0; y <= 3; y++)
				server.overworld().setBlock(landing.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
		}
		player.setHealth(player.getMaxHealth());
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		if (!player.teleportTo(server.overworld(), landing.getX() + 0.5, landing.getY() + 0.1,
				landing.getZ() + 0.5, Set.of(), -45.0F, 12.0F, true)) {
			throw new AssertionError("M6 client fixture could not return to the Overworld");
		}
		player.resetFallDistance();
	}

	private static void prepareStrongholdEstimateFixture(MinecraftServer server) {
		var player = server.getPlayerList().getPlayers().getFirst();
		BlockPos estimatedStronghold = player.blockPosition().offset(1_600, -24, 800);
		FrequencyWorldData.get(server).updateTerminalRecord(player.getUUID(), record -> {
			record.putInt(TerminalData.CRAFTED_EYE_COUNT,
					SurvivalProgressService.REQUIRED_STRONGHOLD_UNLOCK_EYES);
			record.putInt(TerminalData.EYE_SAMPLE_COUNT, SurvivalProgressService.REQUIRED_EYE_SAMPLES);
			record.putLong(TerminalData.STRONGHOLD_POSITION, estimatedStronghold.asLong());
			record.putString(TerminalData.STRONGHOLD_DIMENSION,
					player.level().dimension().identifier().toString());
		});
	}

	private static void discoverAllTerminalFiles(MinecraftServer server) {
		var player = server.getPlayerList().getPlayers().getFirst();
		FrequencyWorldData data = FrequencyWorldData.get(server);
		long gameTime = server.overworld().getGameTime();
		long dayTime = server.overworld().getDayTime();
		data.updateTerminalRecord(player.getUUID(), record -> {
			for (NarrativeFileCatalog.Definition definition : NarrativeFileCatalog.definitions()) {
				TerminalFileState.discover(record, definition.id(), gameTime, dayTime, true);
			}
		});
		TerminalRuntimeService.refresh(player);
	}

	private static void runAlphaRelaunch(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitFor(client -> AlphaLoadSessionController.persistentStartupAppliedForTesting()
				&& AlphaLoadSessionController.resourceReloadFinishedForTesting(), 1_000);
		assertAlphaBasePacksHidden(context);
		int[] legacyScreensBeforeWorld = {-1};
		context.runOnClient(client -> {
			if (!ConfigManager.loadClientState().alphaDowngradeComplete()
					|| !AlphaLoadSessionController.corruptionEverPlayedForTesting()) {
				throw new AssertionError("Relaunch did not restore the persisted Alpha downgrade marker");
			}
			if (AlphaLoadSessionController.persistentAlphaLoadingOverlaysForTesting() < 1
					|| AlphaLoadSessionController.persistentAlphaLoadingFirstFramesForTesting() < 1) {
				throw new AssertionError(
						"Relaunch did not use the persistent Alpha loading style from its first rendered frame");
			}
			if (!AlphaLoadSessionController.persistentInitialPackSelectionPreparedForTesting()
					|| AlphaLoadSessionController.suppressedResourceReloadAnimationsForTesting() != 0) {
				throw new AssertionError(
						"Relaunch did not prepare Alpha packs before the initial reload and risked a vanilla title frame");
			}
			if (AlphaLoadSessionController.activeForTesting()
					|| AlphaLoadSessionController.corruptionPlayCountForTesting() != 0) {
				throw new AssertionError("Relaunch replayed corruption before entering a world");
			}
			if (AlphaLoadSessionController.resourceReloadFailedForTesting()
					|| !AlphaLoadSessionController.javaIconAppliedForTesting()) {
				throw new AssertionError("Relaunch did not restore its hidden resource stack and Java icon");
			}
			if (!"Minecraft 1.0.0".equals(
					AlphaLoadSessionController.appliedWindowTitleForTesting())
					|| !"Minecraft 1.0.0".equals(AlphaLoadSessionController
					.menuVersionText("Minecraft 1.21.11"))) {
				throw new AssertionError("Relaunch title screen did not retain the Minecraft 1.0.0 identity");
			}
			List<String> selected = client.getResourcePackRepository().getSelectedPacks().stream()
					.map(net.minecraft.server.packs.repository.Pack::getId).toList();
			int programmer = selected.indexOf(AlphaResourcePackPlan.PROGRAMMER_ART_PACK_ID);
			int base = selected.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_BASE_PACK_ID);
			int alpha = selected.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_ALPHA_PACK_ID);
			if (!(programmer >= 0 && programmer < base && base < alpha)) {
				throw new AssertionError("Relaunch resource priority is not Programmer Art < Base < Alpha: "
						+ selected);
			}
			String grassSource = client.getResourceManager().getResource(Identifier.fromNamespaceAndPath(
					"minecraft", "textures/block/grass_block_top.png")).orElseThrow().sourcePackId();
			String stoneSource = client.getResourceManager().getResource(Identifier.fromNamespaceAndPath(
					"minecraft", "textures/block/stone.png")).orElseThrow().sourcePackId();
			if (!AlphaResourcePackPlan.GOLDEN_DAYS_ALPHA_PACK_ID.equals(grassSource)
					|| !AlphaResourcePackPlan.GOLDEN_DAYS_BASE_PACK_ID.equals(stoneSource)) {
				throw new AssertionError("Relaunch resources did not resolve through Alpha then Base: grass="
						+ grassSource + ", stone=" + stoneSource);
			}
			legacyScreensBeforeWorld[0] = AlphaLoadSessionController
					.legacyLoadingScreensRenderedForTesting();
		});
		context.waitTicks(45);
		context.takeScreenshot("alpha-relaunch-title");

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			context.runOnClient(client -> {
				if (!AlphaLoadSessionController.activeForTesting()
						|| AlphaLoadSessionController.corruptionPlayCountForTesting() != 0
						|| AlphaLoadSessionController.legacyLoadingScreensRenderedForTesting()
						<= legacyScreensBeforeWorld[0]) {
					throw new AssertionError(
							"First world after relaunch replayed corruption instead of normal legacy loading");
				}
			});
		}

		context.waitForScreen(TitleScreen.class);
		context.runOnClient(client -> {
			client.updateTitle();
			if (!"Minecraft 1.0.0".equals(
					AlphaLoadSessionController.appliedWindowTitleForTesting())
					|| AlphaLoadSessionController.corruptionPlayCountForTesting() != 0) {
				throw new AssertionError("Leaving the relaunch world lost the persistent Alpha identity");
			}
		});
	}

	private static void assertAlphaBasePacksHidden(ClientGameTestContext context) {
		context.runOnClient(client -> {
			var repository = client.getResourcePackRepository();
			repository.reload();
			for (String packId : AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH) {
				if (!repository.isAvailable(packId)) {
					throw new AssertionError("Required hidden Alpha base pack is unavailable: " + packId);
				}
			}
			PackSelectionModel model = new PackSelectionModel(entry -> { },
					pack -> Identifier.fromNamespaceAndPath("minecraft", "textures/gui/resource_pack.png"),
					repository, ignored -> { });
			List<String> visible = Stream.concat(model.getSelected(), model.getUnselected())
					.map(PackSelectionModel.Entry::getId).toList();
			for (String packId : AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH) {
				if (visible.contains(packId)) {
					throw new AssertionError("Internal Alpha base leaked into resource pack list: " + packId);
				}
			}
			List<String> selectedBeforeCommit = repository.getSelectedPacks().stream()
					.map(net.minecraft.server.packs.repository.Pack::getId).toList();
			model.commit();
			List<String> selectedAfterCommit = repository.getSelectedPacks().stream()
					.map(net.minecraft.server.packs.repository.Pack::getId).toList();
			if (!selectedBeforeCommit.equals(selectedAfterCommit)) {
				throw new AssertionError("Completing the resource pack screen removed hidden Alpha bases: before="
						+ selectedBeforeCommit + ", after=" + selectedAfterCommit);
			}
		});
	}

	private static void assertAlphaSessionLoaded(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (!AlphaLoadSessionController.activeForTesting()
					|| !AlphaLoadSessionController.resourceReloadFinishedForTesting()
					|| AlphaLoadSessionController.resourceReloadFailedForTesting()) {
				throw new AssertionError("Singleplayer Alpha resource session did not finish cleanly");
			}
			if (!"SINGLEPLAYER".equals(AlphaLoadSessionController.sessionKindForTesting())) {
				throw new AssertionError("Integrated world was not classified as singleplayer");
			}
			if (!AlphaLoadSessionController.corruptionEverPlayedForTesting()
					|| AlphaLoadSessionController.corruptionPlayCountForTesting() != 1
					|| !AlphaLoadSessionController.javaIconAppliedForTesting()) {
				throw new AssertionError("Initial world entry did not claim one client-lifetime corruption and Java icon");
			}
			if (!ConfigManager.loadClientState().alphaDowngradeComplete()
					|| !Files.isRegularFile(ConfigManager.configPath())) {
				throw new AssertionError("Initial corruption did not atomically persist its Alpha downgrade marker");
			}
			if (AlphaLoadSessionController.javaIconAppliedAtScreenTickForTesting()
					< AlphaLoadTimeline.GLITCH_START_TICK
					|| AlphaLoadSessionController.javaIconAppliedAtScreenTickForTesting()
					>= AlphaLoadTimeline.LEGACY_RECOVERY_START_TICK) {
				throw new AssertionError("Java icon was not switched during the corruption phase: tick="
						+ AlphaLoadSessionController.javaIconAppliedAtScreenTickForTesting());
			}
			if (AlphaLoadSessionController.versionStageForTesting()
					!= AlphaLoadTimeline.finalVersionStage()
					|| !AlphaLoadSessionController.appliedWindowTitleForTesting().contains("Minecraft 1.0.0")) {
				throw new AssertionError("Window title did not finish its visible downgrade at Minecraft 1.0.0: "
						+ AlphaLoadSessionController.appliedWindowTitleForTesting());
			}
			if (AlphaLoadSessionController.lastLoadingScreenTicksForTesting()
					< AlphaLoadTimeline.MIN_LOADING_SCREEN_TICKS
					|| AlphaLoadSessionController.lastFailureCopiesForTesting()
					!= AlphaLoadTimeline.MAX_FAILURE_COPIES
					|| !AlphaLoadSessionController.lastViewportFloodedForTesting()) {
				throw new AssertionError("Real loading-terrain screen skipped the bounded failure cascade");
			}
			if (AlphaLoadSessionController.resourceReloadRequestedForTesting()
					&& AlphaLoadSessionController.suppressedResourceReloadAnimationsForTesting() < 1) {
				throw new AssertionError("Alpha resource reload exposed its LoadingOverlay animation");
			}
			List<String> requested = AlphaLoadSessionController.activePackOrderForTesting();
			int suffix = requested.size() - AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH.size();
			if (suffix < 0 || !requested.subList(suffix, requested.size())
					.equals(AlphaResourcePackPlan.SESSION_BASES_LOW_TO_HIGH)) {
				throw new AssertionError("Requested Alpha pack priority was not Programmer Art < Base < Alpha: "
						+ requested);
			}
			List<String> selected = client.getResourcePackRepository().getSelectedPacks().stream()
					.map(net.minecraft.server.packs.repository.Pack::getId).toList();
			int programmer = selected.indexOf(AlphaResourcePackPlan.PROGRAMMER_ART_PACK_ID);
			int base = selected.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_BASE_PACK_ID);
			int alpha = selected.indexOf(AlphaResourcePackPlan.GOLDEN_DAYS_ALPHA_PACK_ID);
			if (!(programmer >= 0 && programmer < base && base < alpha)) {
				throw new AssertionError("Applied resource stack order is wrong: " + selected);
			}
			String grassSource = client.getResourceManager().getResource(Identifier.fromNamespaceAndPath(
					"minecraft", "textures/block/grass_block_top.png")).orElseThrow().sourcePackId();
			String stoneSource = client.getResourceManager().getResource(Identifier.fromNamespaceAndPath(
					"minecraft", "textures/block/stone.png")).orElseThrow().sourcePackId();
			if (!AlphaResourcePackPlan.GOLDEN_DAYS_ALPHA_PACK_ID.equals(grassSource)
					|| !AlphaResourcePackPlan.GOLDEN_DAYS_BASE_PACK_ID.equals(stoneSource)) {
				throw new AssertionError("Live resources did not resolve through Alpha then Base: grass="
						+ grassSource + ", stone=" + stoneSource);
			}
		});
	}

	private static void assertPlayerInsideStation(ClientGameTestContext context, BlockPos station) {
		context.runOnClient(client -> {
			if (client.player == null
					|| Math.abs(client.player.getX() - (station.getX() + 0.5)) > 1.0
					|| Math.abs(client.player.getZ() - (station.getZ() + 0.5)) > 1.0) {
				throw new AssertionError("First join did not place the player inside Relay Station Zero");
			}
		});
	}

	private record TerminalPersistenceProof(
			String worldId,
			String terminalId,
			String personality,
			int bandStage,
			int mined,
			int placed,
			int crafted,
			String acceptedAdvice,
			String localFileHash,
			int emptySegmentCount,
			int portalTransitions) {
	}

	private record M4ClientFixture(int tuning) {
	}
}
