package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.state.PlayerPatternState;
import com.xm.thefourthfrequency.state.StoryState;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class StoryProgressService {
	private static boolean initialized;
	private StoryProgressService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(StoryProgressService::tick);
	}

	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) update(player, data);
	}

	public static void update(ServerPlayer player, FrequencyWorldData data) {
		CompoundTag before = data.terminalRecord(player.getUUID()).orElse(null);
		if (before == null) return;
		updateGuidancePacing(player, data, before);
		before = data.terminalRecord(player.getUUID()).orElse(before);
		StoryState story = StoryState.read(before);
		long day = Math.floorMod(player.level().getDayTime(), 24_000L);
		boolean night = day >= 13_000L && day <= 23_000L;
		String route = proofRoute(player, before);
		int milestones = before.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		boolean earlySurvival = SurvivalMilestone.MINED_LOGS.present(milestones)
				|| SurvivalMilestone.IRON.present(milestones);
		// The profile is the binding. It asks five questions, answers with nothing but "user
		// preferences recorded", and the terminal is that player's from then on - which is what the
		// sequence has always looked like it was doing. Binding used to wait for the first logs
		// instead, so the device took a file on you in the first minute and then stayed impersonal
		// through an errand, and the two halves of one ceremony had a wood-chopping trip between them.
		//
		// earlySurvival stays as a floor, not as the trigger. Every ordinary path reaches the profile
		// long before it reaches a tree, so this only fires for a record that somehow got past the
		// first boot without one - and an unbound terminal is a stalled playthrough, not a quiet
		// inconvenience.
		boolean bind = !story.bound()
				&& (before.getBooleanOr(TerminalData.PROFILE_TAKEN, false) || earlySurvival);
		boolean reveal = story.bandStage() == 0 && story.bound()
				&& SurvivalMilestone.IRON.present(milestones);
		boolean nightEntered = story.nightEntered();
		boolean nightCompleted = !night && nightEntered && !story.nightWitnessed();
		if (!bind && !reveal && !(night && !nightEntered) && !nightCompleted && route.equals(story.proofRoute())) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			if (night) tag.putBoolean(TerminalData.NIGHT_ENTERED, true);
			if (nightCompleted) tag.putBoolean(TerminalData.NIGHT_WITNESSED, true);
			if (!route.equals("none")) tag.putString(TerminalData.PROOF_ROUTE, route);
			if (bind) {
				tag.putBoolean(TerminalData.BOUND, true);
				tag.putBoolean(TerminalData.SECOND_CACHE_UNLOCKED, true);
				tag.putInt(TerminalData.PLOT_STAGE, Math.max(2, tag.getIntOr(TerminalData.PLOT_STAGE, 1)));
			}
			if (reveal) tag.putInt(TerminalData.BAND_STAGE, 1);
		});
		if (bind) com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
				Component.translatable("message.thefourthfrequency.terminal.bound"));
		if (reveal) com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
				Component.translatable("message.thefourthfrequency.unknown.revealed"));
		TerminalLifecycleService.ensureCarried(player, false);
		if (bind || reveal) TerminalRuntimeService.synchronizeProjection(player);
	}

	public static void recordAnomaly(ServerPlayer player, String type) {
		// Bit 2 belonged to surface_fracture, which was merged into phantom_echo. Left unused rather
		// than reassigned: the bits are persisted and nothing reads this mask by count.
		int index = switch (type) { case "phantom_echo" -> 0; case "light_dropout" -> 1;
			case "watcher_alignment" -> 3; default -> -1; };
		if (index < 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return;
		data.updateTerminalRecord(player.getUUID(), tag -> tag.putInt(TerminalData.PRELUDE_ANOMALY_MASK,
				tag.getIntOr(TerminalData.PRELUDE_ANOMALY_MASK, 0) | 1 << index));
	}

	public static void recordWatcher(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isPresent())
			data.updateTerminalRecord(player.getUUID(), tag -> tag.putBoolean(TerminalData.WATCHER_WITNESSED, true));
	}

	public static Objective objective(CompoundTag tag, FrequencyWorldData data) {
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		int wood = Math.clamp(tag.getIntOr(TerminalData.WOOD_MINED_COUNT, 0), 0,
				SurvivalProgressService.REQUIRED_WOOD);
		if (!SurvivalMilestone.MINED_LOGS.present(milestones)) return new Objective("mine_logs", wood,
				SurvivalProgressService.REQUIRED_WOOD);
		int iron = Math.clamp(tag.getIntOr(TerminalData.IRON_SAMPLE_COUNT, 0), 0,
				SurvivalProgressService.REQUIRED_IRON);
		if (!SurvivalMilestone.IRON.present(milestones)) return new Objective("bring_iron", iron,
				SurvivalProgressService.REQUIRED_IRON);
		if (!SurvivalMilestone.ENTERED_NETHER.present(milestones)) return new Objective("enter_nether", 0, 1);
		if (!SurvivalMilestone.FOUND_FORTRESS.present(milestones))
			return new Objective("find_fortress", 0, 1);
		// The party's requirement, not the authored ceiling, or the hint pacing would keep counting a
		// player as stalled on an objective their terminal has already marked complete.
		int requiredRods = Math.clamp(tag.getIntOr(TerminalData.BLAZE_ROD_REQUIRED,
						SurvivalProgressService.REQUIRED_BLAZE_RODS),
				SurvivalProgressService.MINIMUM_BLAZE_RODS, SurvivalProgressService.REQUIRED_BLAZE_RODS);
		int blazeRods = Math.clamp(tag.getIntOr(TerminalData.BLAZE_ROD_SAMPLE_COUNT, 0), 0, requiredRods);
		if (!SurvivalMilestone.COLLECTED_BLAZE_RODS.present(milestones)) return new Objective(
				"collect_blaze_rods", blazeRods, requiredRods);
		if (!SurvivalMilestone.RETURNED_NETHER.present(milestones)) return new Objective("return_from_nether", 0, 1);
		// No crafting objective. Retired with the task at schema 13 - the portal needs twelve eyes
		// between everybody and the vanilla recipe already says what an eye is for, so naming it as a
		// per-player target only ever multiplied one job by the size of the table.
		int eyeSamples = Math.clamp(tag.getIntOr(TerminalData.EYE_SAMPLE_COUNT, 0), 0,
				SurvivalProgressService.REQUIRED_EYE_SAMPLES);
		if (!SurvivalMilestone.THREW_EYE.present(milestones)
				|| eyeSamples < SurvivalProgressService.REQUIRED_EYE_SAMPLES)
			return new Objective("record_eye", eyeSamples, SurvivalProgressService.REQUIRED_EYE_SAMPLES);
		if (!SurvivalMilestone.FOUND_STRONGHOLD.present(milestones)) return new Objective("find_stronghold", 0, 1);
		if (!SurvivalMilestone.ENTERED_END.present(milestones)) return new Objective("enter_end", 0, 1);
		if (!SurvivalMilestone.DEFEATED_BOSS.present(milestones)) return new Objective("defeat_boss", 0, 1);
		return new Objective("complete", 1, 1);
	}

	/**
	 * Returns how many extra layers of help should be disclosed for the current objective.
	 * Only online time without objective progress is counted, so logging out never manufactures a stuck state.
	 */
	public static int guidanceHintTier(CompoundTag tag) {
		long stalled = Math.max(0L, tag.getLongOr(TerminalData.GUIDANCE_STALLED_TICKS, 0L));
		long first = RuntimeServices.config().pacing().developerAcceleration() ? 200L : 2_400L;
		long second = RuntimeServices.config().pacing().developerAcceleration() ? 600L : 6_000L;
		if (stalled >= second) return 2;
		return stalled >= first ? 1 : 0;
	}

	private static void updateGuidancePacing(ServerPlayer player, FrequencyWorldData data, CompoundTag before) {
		Objective current = objective(before, data);
		String previousId = before.getStringOr(TerminalData.GUIDANCE_OBJECTIVE_ID, "");
		int previousProgress = before.getIntOr(TerminalData.GUIDANCE_OBJECTIVE_PROGRESS, 0);
		long previousStalled = Math.max(0L, before.getLongOr(TerminalData.GUIDANCE_STALLED_TICKS, 0L));
		boolean progressed = !current.id().equals(previousId) || current.progress() != previousProgress;
		boolean complete = current.id().equals("complete");
		if (!progressed && (complete && previousStalled == 0L || previousStalled >= 72_000L)) return;
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putString(TerminalData.GUIDANCE_OBJECTIVE_ID, current.id());
			tag.putInt(TerminalData.GUIDANCE_OBJECTIVE_PROGRESS, current.progress());
			if (progressed || complete) {
				tag.putLong(TerminalData.GUIDANCE_STALLED_TICKS, 0L);
			} else {
				tag.putLong(TerminalData.GUIDANCE_STALLED_TICKS, Math.min(72_000L,
						tag.getLongOr(TerminalData.GUIDANCE_STALLED_TICKS, 0L) + 20L));
			}
		});
	}

	private static String proofRoute(ServerPlayer player, CompoundTag tag) {
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		if (SurvivalMilestone.IRON.present(milestones)) return "mining";
		if (SurvivalMilestone.MINED_LOGS.present(milestones)) return "building";
		PlayerPatternState pattern = PlayerPatternState.read(tag);
		if (pattern.mined() >= 32) return "mining";
		if (pattern.crafted() >= 8 && pattern.placed() >= 8) return "building";
		return FragmentInvestigationService.insideSupportedStructure(player) ? "exploration" : "none";
	}

	public record Objective(String id, int progress, int target) { }
}
