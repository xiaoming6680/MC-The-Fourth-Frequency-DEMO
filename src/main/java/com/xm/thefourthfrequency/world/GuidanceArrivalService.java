package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Stops guidance for the tools that point at a fixed place once the player is standing in it.
 *
 * <p>The mineral tool and the structure navigator each already ended their own guidance on
 * arrival, because each owns a state machine that had to be torn down anyway. Home, portal and
 * stronghold had no such machine and so had no arrival at all: the needle simply kept pointing at
 * a bed the player was already standing next to until they remembered to switch it off by hand.
 * From the player's side that is indistinguishable from the tool being broken.</p>
 */
public final class GuidanceArrivalService {
	/**
	 * Home and portal point at one block the player themselves established, so arrival is close
	 * and fully three-dimensional - a bed on the surface is not reached from a cave beneath it.
	 */
	public static final int POINT_ARRIVAL_RADIUS = 6;
	private static final int CHECK_TICKS = 20;
	private static boolean initialized;

	private GuidanceArrivalService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(GuidanceArrivalService::onServerTick);
	}

	private static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_TICKS != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) updatePlayer(player);
	}

	public static void updatePlayer(ServerPlayer player) {
		if (PrivateDimensions.isPrivate(player.level())) return;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag tag = data.terminalRecord(player.getUUID()).orElse(null);
		if (tag == null) return;
		TerminalTool tool = TerminalTool.fromSlot(TerminalToolService.guidanceTool(tag));
		if (tool == null || !arrived(player, tag, tool)) return;
		data.updateTerminalRecord(player.getUUID(), record ->
				record.putInt(TerminalData.ACTIVE_GUIDANCE_TOOL, TerminalToolService.NO_TOOL));
		TerminalNoticeService.send(player, Component.translatable(
				"message.thefourthfrequency.navigation.arrived",
				Component.translatable("terminal.thefourthfrequency.tool." + tool.id())));
		TerminalRuntimeService.synchronizeProjection(player);
		TerminalRuntimeService.refresh(player);
	}

	private static boolean arrived(ServerPlayer player, CompoundTag tag, TerminalTool tool) {
		return switch (tool) {
			case HOME, PORTAL -> atPoint(player, tag, tool);
			// The stronghold reading is an estimate with a wide error band, so there is no point to
			// stand on. Reaching it is already recorded as a milestone, and that is the honest
			// moment the tool has nothing further to say.
			case STRONGHOLD -> SurvivalMilestone.FOUND_STRONGHOLD.present(
					tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0));
			// Owned by ResourceGuidanceService and StructureNavigationService respectively.
			case MINERALS, NAVIGATION, WEATHER -> false;
		};
	}

	private static boolean atPoint(ServerPlayer player, CompoundTag tag, TerminalTool tool) {
		TerminalToolService.Location target = TerminalToolService.guidanceLocation(player, tag, tool);
		if (!target.known()
				|| !target.dimension().equals(player.level().dimension().identifier().toString())) return false;
		return player.blockPosition().distSqr(target.position())
				<= (long) POINT_ARRIVAL_RADIUS * POINT_ARRIVAL_RADIUS;
	}
}
