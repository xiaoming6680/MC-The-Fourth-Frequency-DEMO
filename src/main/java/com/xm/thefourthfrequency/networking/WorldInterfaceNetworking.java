package com.xm.thefourthfrequency.networking;

import com.xm.thefourthfrequency.ending.EndBossEncounterService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Registers the versioned altar, encounter, action and per-player poem protocol. */
public final class WorldInterfaceNetworking {
	private static boolean initialized;

	private WorldInterfaceNetworking() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PayloadTypeRegistry.playS2C().register(AltarSnapshotS2C.TYPE, AltarSnapshotS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(AltarActionC2S.TYPE, AltarActionC2S.CODEC);
		PayloadTypeRegistry.playS2C().register(WorldInterfaceSnapshotS2C.TYPE, WorldInterfaceSnapshotS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(BossActionS2C.TYPE, BossActionS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(StormParticleBatchS2C.TYPE, StormParticleBatchS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(WorldInterfaceBlastS2C.TYPE, WorldInterfaceBlastS2C.CODEC);
		PayloadTypeRegistry.playS2C().register(PoemStartS2C.TYPE, PoemStartS2C.CODEC);
		PayloadTypeRegistry.playC2S().register(PoemCompleteC2S.TYPE, PoemCompleteC2S.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(AltarActionC2S.TYPE,
				(payload, context) -> EndBossEncounterService.handleAltarAction(context.player(), payload));
		ServerPlayNetworking.registerGlobalReceiver(PoemCompleteC2S.TYPE,
				(payload, context) -> EndBossEncounterService.handlePoemComplete(context.player(), payload));
	}
}
