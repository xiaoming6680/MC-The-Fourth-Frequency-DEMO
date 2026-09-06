package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.networking.WorldDecayPayload;
import com.xm.thefourthfrequency.networking.MenuErosionStageS2C;
import com.xm.thefourthfrequency.terminal.WorldDecayResiduePolicy;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class WorldDecayService {
	private static boolean initialized;
	private WorldDecayService() { }

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(WorldDecayService::tick);
	}

	/**
	 * Sends each player the decay their own run has earned.
	 *
	 * <p>This used to take the maximum stage across everyone online and broadcast that one number.
	 * The result was that somebody joining a mature server saw the most advanced player's stage-five
	 * textures before anything had happened to them - and that the world visibly healed when that
	 * player logged off, because the maximum dropped. It also contradicted the two rules beside it:
	 * the menu erosion computed three lines below has always been personal, and the terminal's own
	 * appearance is documented as "personal state that does not follow the furthest-along player".
	 *
	 * <p>The world bible's line for this is that a party may share discoveries without having to
	 * share one hallucination. Decay is a render-time replacement on each client, so nothing
	 * authoritative diverges by making it personal.
	 */
	private static void tick(MinecraftServer server) {
		if (server.getTickCount() % 40 != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
			ServerPlayNetworking.send(player, new WorldDecayPayload(record == null ? 0
					: stage(data, record, FinaleRuntimePolicy.insideRunningEncounter(data, player))));
			int ceiling = record == null ? 1 : record.getIntOr(TerminalData.ANOMALY_STORY_CEILING,
					record.getIntOr(TerminalData.ANOMALY_TIER, 1));
			int menuStage = MenuErosionStageS2C.stageFor(ceiling, FinaleRuntimePolicy.succeeded(data));
			ServerPlayNetworking.send(player, new MenuErosionStageS2C(menuStage));
		}
	}

	/** The stage for a viewer the encounter is not happening to; see the three-argument form. */
	public static int stage(FrequencyWorldData data, CompoundTag record) {
		return stage(data, record, false);
	}

	/**
	 * @param insideRunningEncounter whether this viewer is in the fight, which pins the stage at five
	 *        for its duration. Asked about a person rather than about the world, for the reason given
	 *        on {@link #tick}: a summon in the End is not a thing that happens to somebody's base in
	 *        the Overworld, and taking their textures away for ten minutes because eight other people
	 *        walked through a portal is the furthest-along-player bug wearing a different hat.
	 */
	public static int stage(FrequencyWorldData data, CompoundTag record, boolean insideRunningEncounter) {
		if (FinaleRuntimePolicy.succeeded(data)) return 0;
		if (data.narrativeState().contains("decay_stage_override"))
			return Math.clamp(data.narrativeState().getIntOr("decay_stage_override", 0), 0, 5);
		int anomaly = Math.clamp(record.getIntOr(TerminalData.ANOMALY_TIER, 0), 0, 5);
		int milestones = record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		int survival = SurvivalMilestone.FOUND_STRONGHOLD.present(milestones) ? 5
				: SurvivalMilestone.RETURNED_NETHER.present(milestones) ? 4
				: SurvivalMilestone.IRON.present(milestones) ? 3 : 0;
		if (insideRunningEncounter) return 5;
		// The tier and milestone terms both describe where the player currently is, and both can
		// sit still for a long time. The residue term describes what they have already been
		// through and only ever climbs, so surviving anomalies leaves the world permanently worse
		// even when nothing else has advanced.
		int residue = WorldDecayResiduePolicy.residueStage(
				record.getIntOr(TerminalData.ANOMALY_RESIDUE_COUNT, 0));
		return Math.max(Math.max(anomaly, survival), residue);
	}
}
