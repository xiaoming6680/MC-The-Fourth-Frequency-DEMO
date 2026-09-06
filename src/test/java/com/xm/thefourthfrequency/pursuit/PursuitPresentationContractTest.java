package com.xm.thefourthfrequency.pursuit;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PursuitPresentationContractTest {
	@Test
	void pursuitUsesOnePersistentRedStatusAndNoBossBar() throws Exception {
		String controller = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitFormController.java"),
				StandardCharsets.UTF_8);
		assertFalse(controller.contains("ServerBossEvent"));
		assertFalse(controller.contains("BossEvent."));
		assertFalse(controller.contains("displayClientMessage"));
		assertFalse(controller.contains("TerminalNoticeService.pursuit"));

		String noticeHud = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalNoticeHud.java"),
				StandardCharsets.UTF_8);
		assertTrue(noticeHud.contains("PURSUIT_BACKGROUND"));
		assertTrue(noticeHud.contains("TONE_PURSUIT_WARNING"));
		assertTrue(noticeHud.contains("PursuitPresentationClient.pursuitHudActive()"));
		assertTrue(noticeHud.contains("PursuitPresentationClient.holdsNoticeQueue()"));
		assertTrue(noticeHud.contains("message.thefourthfrequency.pursuit.try_escape"));
		assertTrue(noticeHud.contains("message.thefourthfrequency.pursuit.escaped_temporary"));
	}

	@Test
	void lowResolutionPostEffectAndClientMixinsArePackaged() throws Exception {
		Path effectPath = Path.of(
				"src/main/resources/assets/thefourthfrequency/post_effect/pursuit_low_res.json");
		var effect = JsonParser.parseString(Files.readString(effectPath, StandardCharsets.UTF_8))
				.getAsJsonObject();
		String serialized = effect.toString();
		// The chain this replaced was vanilla's color_convolve into vanilla's bits - a flat
		// greyscale and a flat mosaic, applied evenly to every pixel of every frame. Evenness is
		// what made it read as a filter having been switched on rather than as a picture failing,
		// and it is the one thing the mod's own shader is written not to do.
		assertTrue(serialized.contains("thefourthfrequency:post/digital_corrupt"),
				"the pursuit runs the mod's own corruption filter");
		assertFalse(serialized.contains("minecraft:post/bits"),
				"vanilla's flat mosaic must not come back");
		assertTrue(serialized.contains("\"CorruptConfig\""));

		String mixins = Files.readString(Path.of(
				"src/main/resources/thefourthfrequency.mixins.json"), StandardCharsets.UTF_8);
		assertTrue(mixins.contains("GameRendererPursuitMixin"));
		assertTrue(mixins.contains("GameRendererPostEffectInvoker"));
		assertTrue(mixins.contains("PursuitLevelLoadingScreenMixin"));
	}

	@Test
	void proximityBandsDegradeMonotonicallyTowardContact() throws Exception {
		// One chain per ProximityGrade, ordered farthest to nearest. The picture must get worse with
		// every step inward; getting this backwards would hand the player *better* vision the closer
		// the corrector gets, which is the whole point inverted, and nothing else in the build would
		// catch it.
		String[] bands = {"pursuit_low_res_distant", "pursuit_low_res",
				"pursuit_low_res_close", "pursuit_low_res_contact"};
		float previousLevels = Float.MAX_VALUE;
		float previousBlock = 0.0F;
		float previousLoss = -1.0F;
		float previousSplit = -1.0F;
		for (String band : bands) {
			Map<String, Float> config = corruptConfig(band);
			assertTrue(config.get("Levels") < previousLevels,
					band + " must drop colour resolution relative to the band before it");
			assertTrue(config.get("BlockSize") > previousBlock,
					band + " must enlarge the failing block relative to the band before it");
			assertTrue(config.get("BandLoss") > previousLoss,
					band + " must lose more of the picture than the band before it");
			assertTrue(config.get("ChannelSplit") > previousSplit,
					band + " must part its colour channels further than the band before it");
			previousLevels = config.get("Levels");
			previousBlock = config.get("BlockSize");
			previousLoss = config.get("BandLoss");
			previousSplit = config.get("ChannelSplit");
		}
	}

	/**
	 * The corruption never re-rolls faster than the world bible's flicker ceiling.
	 *
	 * <p>{@code HoldTicks} is the period of every coherent thing the filter does - the bands that
	 * move, the blocks that give out, the channels that part. Three hertz is 6.67 ticks, so seven is
	 * the floor. Asserted rather than commented because the pursuit's treatment is worn continuously
	 * for minutes at a time, which is the exact shape of exposure the limit exists for.
	 */
	@Test
	void theCorruptionHoldsLongEnoughToStayUnderTheFlickerCeiling() throws Exception {
		for (String band : new String[]{"pursuit_low_res_distant", "pursuit_low_res",
				"pursuit_low_res_close", "pursuit_low_res_contact"}) {
			float hold = corruptConfig(band).get("HoldTicks");
			assertTrue(hold >= 7.0F,
					band + " re-rolls every " + hold + " ticks, which is faster than 3 Hz");
		}
	}

	private static Map<String, Float> corruptConfig(String chainName) throws Exception {
		Path path = Path.of("src/main/resources/assets/thefourthfrequency/post_effect/"
				+ chainName + ".json");
		assertTrue(Files.exists(path), chainName + " must exist for its proximity grade");
		var chain = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
				.getAsJsonObject();
		Map<String, Float> values = new LinkedHashMap<>();
		for (var pass : chain.getAsJsonArray("passes")) {
			var uniforms = pass.getAsJsonObject().getAsJsonObject("uniforms");
			if (uniforms == null || !uniforms.has("CorruptConfig")) continue;
			for (var uniform : uniforms.getAsJsonArray("CorruptConfig")) {
				var entry = uniform.getAsJsonObject();
				if (!entry.get("value").isJsonPrimitive()) continue;
				values.put(entry.get("name").getAsString(), entry.get("value").getAsFloat());
			}
		}
		assertFalse(values.isEmpty(), chainName + " declares no CorruptConfig");
		return values;
	}

	@Test
	void presentationProtocolIsRegisteredOnBothSides() throws Exception {
		String bootstrap = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/bootstrap/TheFourthFrequency.java"),
				StandardCharsets.UTF_8);
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TheFourthFrequencyClient.java"),
				StandardCharsets.UTF_8);
		assertTrue(bootstrap.contains("PursuitNetworking.initialize()"));
		assertTrue(client.contains("PursuitPresentationClient.initialize()"));
	}

	@Test
	void debugGuiStartsFormFiveWithoutAdvancingFormalProgress() throws Exception {
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DebugPanelScreen.java"),
				StandardCharsets.UTF_8);
		String service = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/DebugPanelService.java"),
				StandardCharsets.UTF_8);
		String director = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitDirector.java"),
				StandardCharsets.UTF_8);
		String controller = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitFormController.java"),
				StandardCharsets.UTF_8);
		String session = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		String slots = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSlotManager.java"),
				StandardCharsets.UTF_8);
				// The confirmation was dropped, not the entry: a debug chase resolves and returns the player
		// on its own, so it was never one of the actions with nothing to undo it.
		assertTrue(screen.contains("追逐 第5形态"));
		assertTrue(screen.contains("\"pursuit_test\", \"\", 5, false"));
		assertTrue(service.contains("case \"pursuit_test\""));
		assertTrue(director.contains("enterEmptyMirror(player, lease, requestedForm, true)"));
		assertTrue(session.contains("TerminalNoticeService.pursuitWarning(player)"));
		assertTrue(session.contains("TerminalSignalLog.append(record, SignalBand.UNKNOWN"));
		assertTrue(session.contains("PURSUIT_WARNING_RECORDS_REDIRECT, true"));
		assertTrue(controller.contains("if (!runtime.debugSession)"));
		assertTrue(session.contains("if (!debugSession && !\"success\".equals(resolution))"));
		assertTrue(slots.contains("if (!debugSession) record.putBoolean(TerminalData.PURSUIT_PENDING, true)"));
	}

	@Test
	void terminalPursuitWarningsUseMixedColorsAndCompleteTheirRecordLifecycle() throws Exception {
		var zh = JsonParser.parseString(Files.readString(Path.of(
				"src/main/resources/assets/thefourthfrequency/lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		String snapshot = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalSnapshot.java"),
				StandardCharsets.UTF_8);
		String session = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		String signalLog = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalSignalLog.java"),
				StandardCharsets.UTF_8);
		String warning = zh.get("message.thefourthfrequency.pursuit.warning").getAsString();
		assertTrue(warning.equals("终端传来剧烈震动"));
		// The five forms deliberately no longer share a line. They track by five different rules, and
		// the log entry is where a player finds out which one they survived; PursuitWarningTextContract
		// owns the distinctness assertion, so all this needs is that the opening half is still there
		// and still recognisable as the same instrument reporting.
		String opening = "检测到异常信号波动正在接近..";
		for (int form = 1; form <= 5; form++) {
			assertTrue(zh.get("terminal.thefourthfrequency.signal.event.pursuit_warning_" + form)
					.getAsString().startsWith(opening));
		}
		assertTrue(zh.get("terminal.thefourthfrequency.signal.event.pursuit_return_instability")
				.getAsString().equals("用户周围的磁场很不稳定..."));
		assertTrue(snapshot.contains("pursuit_warning.approaching"));
		assertTrue(snapshot.contains("withStyle(ChatFormatting.GREEN)"));
		assertTrue(snapshot.contains("pursuit_warning.prepare"));
		assertTrue(snapshot.contains("withStyle(ChatFormatting.RED)"));

		// The closing line, which is the whole of what surviving a pursuit is told. It must stay a
		// warning rather than becoming an explanation: naming what the form did would hand the player
		// rules the next form is about to break, and would make the terminal the author of a
		// walkthrough on the one page where its own voice is allowed.
		assertTrue(zh.get("terminal.thefourthfrequency.signal.event.pursuit_survived.cleared")
				.getAsString().equals("异常信号消失..."));
		assertTrue(zh.get("terminal.thefourthfrequency.signal.event.pursuit_survived.next_time")
				.getAsString().equals("下一次可就不一样了..."));
		assertTrue(snapshot.contains("pursuit_survived.cleared"));
		assertTrue(snapshot.contains("pursuit_survived.next_time"));
		// Only a real escape files it, and only outside debug: a capture must leave the page silent.
		String sessions = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		int successBranch = sessions.indexOf("if (successfulResolution) {");
		assertTrue(successBranch >= 0);
		int filed = sessions.indexOf("\"pursuit_survived\"", successBranch);
		assertTrue(filed > successBranch, "the survived line must be filed on a successful resolution");
		assertTrue(sessions.substring(successBranch, filed).contains("if (!debugSession)"),
				"a debug pursuit must not file the survived line");
		assertTrue(signalLog.contains("public static boolean removeTypesStartingWith"));
		assertTrue(session.contains("removeTypesStartingWith(record, \"pursuit_warning_\")"));
		assertTrue(session.contains("\"pursuit_return_instability\""));
		assertTrue(session.contains("successfulResolution(resolution)"));
		assertTrue(session.contains("completedResolution(resolution)"));
	}

	@Test
	void clientWaitsForServerBlackoutAndCoversEveryPursuitLoadingFrame() throws Exception {
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/PursuitPresentationClient.java"),
				StandardCharsets.UTF_8);
		String levelLoadingMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/LevelLoadingScreenCorruptionMixin.java"),
				StandardCharsets.UTF_8);
		String loadingOverlayMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/LoadingOverlaySuppressionMixin.java"),
				StandardCharsets.UTF_8);
		assertTrue(client.contains("return phase == Phase.BLACKOUT || clearRequested"));
		assertTrue(client.contains("client.getOverlay() != null"));
		assertTrue(client.contains("DESTINATION_FALLBACK_STABLE_TICKS = 12"));
		assertTrue(client.contains("transitionLoadingObserved"));
		assertTrue(client.contains("if (phase == Phase.WARNING) return;"));
		assertFalse(client.contains("PursuitPresentationTimeline.warningProgress(warningTicks)"));
		assertTrue(levelLoadingMixin.contains("PursuitPresentationClient.shouldCoverLoadingScreen()"));
		assertTrue(loadingOverlayMixin.contains("PursuitPresentationClient.shouldCoverLoadingScreen()"));
		assertFalse(client.contains(
				"warningTicks >= PursuitPresentationTimeline.PRELUDE_TICKS) {\n"
						+ "\t\t\t\tphase = Phase.BLACKOUT"));
	}

	@Test
	void pursuitCorrectorBreachesQuicklyCountersTowersAndSlowsInCaves() throws Exception {
		String entity = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/entity/ReworkEntity.java"),
				StandardCharsets.UTF_8);
		assertTrue(entity.contains("PURSUIT_BREACH_STUCK_TICKS = 3"));
		assertTrue(entity.contains("PURSUIT_BREACH_RETRY_TICKS = 1"));
		assertTrue(entity.contains("PURSUIT_MOVEMENT_SPEED = 0.31"));
		assertTrue(entity.contains("PURSUIT_NAVIGATION_SPEED = 1.32"));
		assertTrue(entity.contains("PURSUIT_CAVE_MOVEMENT_SPEED = 0.25"));
		assertTrue(entity.contains("PURSUIT_CAVE_NAVIGATION_SPEED = 1.04"));
		assertTrue(entity.contains("movementSpeed.setBaseValue(PURSUIT_MOVEMENT_SPEED)"));
		assertTrue(entity.contains("AnomalyConditions.caveLike(level, target.blockPosition())"));
		assertTrue(entity.contains("updatePursuitCaveSlowdown(level, hostile)"));
		assertTrue(entity.contains("pursuitCaveSlowdown ? PURSUIT_CAVE_NAVIGATION_SPEED"));
		assertTrue(entity.contains("tickPursuitHighGroundCounter(level, hostile)"));
		assertTrue(entity.contains("PURSUIT_TOWER_SCAN_DEPTH"));
		assertTrue(entity.contains("setDeltaMovement(horizontal.x, launch, horizontal.z)"));
	}

	@Test
	void captureAndEscapeUseThreeSecondResolutionStagesAndHeartLimitChanges() throws Exception {
		String controller = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitFormController.java"),
				StandardCharsets.UTF_8);
		String payload = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/PursuitPresentationPayload.java"),
				StandardCharsets.UTF_8);
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/PursuitPresentationClient.java"),
				StandardCharsets.UTF_8);
		String policy = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitProgressPolicy.java"),
				StandardCharsets.UTF_8);
		assertTrue(controller.contains("RESOLUTION_TICKS = 60L"));
		assertTrue(policy.contains("HEART_HEALTH_POINTS = 2.0D"));
		assertTrue(policy.contains("CAPTURE_PENALTY_FLOOR_HEALTH = 12.0D"));
		assertTrue(controller.contains("PursuitProgressPolicy.resolutionMaxHealthDelta"));
		assertTrue(controller.contains("PursuitPresentationPayload.CAPTURE_FREEZE"));
		assertTrue(controller.contains("PursuitPresentationPayload.ESCAPE_RESOLUTION"));
		assertTrue(controller.contains("if (!\"caught\".equals(reason))"));
		// The write itself moved out of the controller when the unrendered layer began paying in the
		// same currency: one clamp, one place, two callers. Asserted where it now lives, and asserted
		// that the controller reaches it rather than keeping a second copy - a duplicate with a
		// different floor would be invisible here and felt by every player already losing.
		String healthAdjustment = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/MaximumHealthAdjustment.java"),
				StandardCharsets.UTF_8);
		assertTrue(healthAdjustment.contains("maximumHealth.setBaseValue(adjusted)"));
		assertTrue(controller.contains("MaximumHealthAdjustment.apply(player, delta)"));
		assertTrue(payload.contains("CAPTURE_FREEZE = 4"));
		assertTrue(payload.contains("ESCAPE_RESOLUTION = 5"));
		assertTrue(client.contains("phase == Phase.CAPTURE_FREEZE"));
		assertTrue(client.contains("resolutionTicks++ < 60"));
	}

	@Test
	void pursuitHeartbeatIsPositionalAndTheChaseLightsThePlayersOwnEyes() throws Exception {
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/PursuitPresentationClient.java"),
				StandardCharsets.UTF_8);
		// The beat has to arrive from where the corrector actually is. Behind a black-and-white
		// mosaic it is the only channel left that can say "not that way", and forUI() - which is
		// what this used to be - has no position at all, so its absence here is the real contract.
		assertTrue(client.contains(
				"playLocalSound(corrector.getX(), corrector.getEyeY(), corrector.getZ(),"));
		assertTrue(client.contains("SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE"));
		assertFalse(client.contains("play(client, SoundEvents.WARDEN_HEARTBEAT"));
		// Volume is what sets a positional sound's audible radius; drop it back under one and the
		// beat stops reaching across the distance band its own cadence is defined over.
		assertTrue(client.contains("HEARTBEAT_VOLUME = 1.75F"));

		String vision = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitVisionService.java"),
				StandardCharsets.UTF_8);
		String controller = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitFormController.java"),
				StandardCharsets.UTF_8);
		String session = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		assertTrue(vision.contains("MobEffects.NIGHT_VISION"));
		// Bounded and topped up rather than infinite: a teardown path that ever gets missed has to
		// expire on its own instead of leaving the save permanently lit.
		assertTrue(vision.contains("DURATION_TICKS = 600"));
		assertTrue(vision.contains("REFRESH_BELOW_TICKS = 300"));
		assertFalse(vision.contains("INFINITE_DURATION"));
		assertTrue(controller.contains("PursuitVisionService.apply(player)"));
		assertTrue(controller.contains("PursuitVisionService.maintain(player)"));
		assertTrue(controller.contains("PursuitVisionService.clear(player)"));
		assertTrue(session.contains("PursuitVisionService.clear(player)"));
	}

	@Test
	void pursuitDisablesPauseExitAndReplacesItsLabel() throws Exception {
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/PursuitPresentationClient.java"),
				StandardCharsets.UTF_8);
		String pauseMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/PauseScreenErosionMixin.java"),
				StandardCharsets.UTF_8);
		var zh = JsonParser.parseString(Files.readString(Path.of(
				"src/main/resources/assets/thefourthfrequency/lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		assertTrue(client.contains("public static boolean locksPauseExit()"));
		assertTrue(client.contains("return phase != Phase.IDLE || clearRequested"));
		assertTrue(pauseMixin.contains("PursuitPresentationClient.locksPauseExit()"));
		assertTrue(pauseMixin.contains("disconnectButton.active = false"));
		assertTrue(pauseMixin.contains("message.thefourthfrequency.pursuit.exit_locked"));
		assertTrue(pauseMixin.contains("method = \"render\", at = @At(\"HEAD\")"));
		assertTrue(zh.get("message.thefourthfrequency.pursuit.exit_locked").getAsString()
				.equals("你不能就这样逃走..."));

		// The exit is held by a fight the player is inside, and by nothing else.
		//
		// Menu erosion is driven by story progress rather than by anything happening to the player,
		// so its LATE stage used to leave the quit button dead in the overworld, mid-build, at any
		// hour, with nothing going on - and the only way out of the game was killing the process.
		// That also broke the project's own rule that the pause menu may never be permanently taken
		// by a performance. Erosion keeps its label and its grain; it does not keep the door.
		assertTrue(pauseMixin.contains("WorldInterfaceClientState.snapshot().locksPauseExit()"),
				"the finale must hold the exit while it is actually being fought");
		assertTrue(pauseMixin.contains("message.thefourthfrequency.world_interface.exit_locked"));
		assertFalse(pauseMixin.contains("case LATE -> disconnectButton.active = false"),
				"menu erosion must never disable the exit: it is not something the player is inside");
		int disables = pauseMixin.split("disconnectButton.active = false", -1).length - 1;
		assertEquals(2, disables,
				"exactly two states may disable the exit - the pursuit and the live finale");
	}

	/** Reads a source file the way every assertion in this class does. */
	private static String read(String path) throws Exception {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}
}
