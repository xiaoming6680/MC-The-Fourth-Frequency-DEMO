package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.AltarActionC2S;
import com.xm.thefourthfrequency.networking.AltarSnapshotS2C;
import com.xm.thefourthfrequency.networking.BossActionS2C;
import com.xm.thefourthfrequency.networking.PoemCompleteC2S;
import com.xm.thefourthfrequency.networking.PoemStartS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceBlastS2C;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/** Client receiver and the only C2S send surface for the world-interface encounter. */
public final class WorldInterfaceClientNetworking {
	private static boolean initialized;

	private WorldInterfaceClientNetworking() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ClientPlayNetworking.registerGlobalReceiver(com.xm.thefourthfrequency.networking.StormParticleBatchS2C.TYPE,
				(payload, context) -> context.client().execute(() ->
						com.xm.thefourthfrequency.client_render.StormParticleReceiver.accept(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(AltarSnapshotS2C.TYPE, (payload, context) ->
				context.client().execute(() -> acceptAltar(payload)));
		ClientPlayNetworking.registerGlobalReceiver(WorldInterfaceSnapshotS2C.TYPE, (payload, context) ->
				context.client().execute(() -> acceptEncounter(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(BossActionS2C.TYPE, (payload, context) ->
				context.client().execute(() -> WorldInterfaceClientState.accept(payload)));
		ClientPlayNetworking.registerGlobalReceiver(WorldInterfaceBlastS2C.TYPE, (payload, context) ->
				context.client().execute(() -> acceptBlast(payload)));
		// This receiver only mutates synchronized state. Keeping it inline preserves wire order with
		// the vanilla WIN_GAME packet that immediately follows and constructs WinScreen.
		ClientPlayNetworking.registerGlobalReceiver(PoemStartS2C.TYPE, (payload, context) ->
				acceptPoem(payload));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				clearClientSession());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
				clearClientSession());
	}

	public static boolean sendAltarAction(AltarSnapshotS2C snapshot,
			WorldInterfaceProtocol.AltarAction action) {
		if (!ClientPlayNetworking.canSend(AltarActionC2S.TYPE)) return false;
		ClientPlayNetworking.send(new AltarActionC2S(snapshot.encounterId(), snapshot.revision(), action));
		return true;
	}

	public static boolean sendPoemComplete(PoemStartS2C poem,
			WorldInterfaceProtocol.PoemCompletion completion) {
		if (!ClientPlayNetworking.canSend(PoemCompleteC2S.TYPE)) return false;
		ClientPlayNetworking.send(new PoemCompleteC2S(poem.encounterId(), poem.sequence(), completion));
		return true;
	}

	private static void acceptAltar(AltarSnapshotS2C payload) {
		if (!WorldInterfaceClientState.accept(payload)) return;
		Minecraft client = Minecraft.getInstance();
		if (client.screen instanceof ResonanceAltarScreen altar && altar.matches(payload.encounterId())) {
			altar.update(payload);
			return;
		}
		// A steady WAITING is a refresh, not an invitation. The altar now pushes to every viewer on a
		// timer so the countdown and the summon button stay honest without anyone acting - and a
		// viewer who closed the screen is still on that list until they walk away from the core, so
		// opening on one of these would make the screen impossible to dismiss while standing at it.
		// Every push that is a reply to something - the open itself, and every action's outcome -
		// carries its own status and still opens.
		if (payload.status() == WorldInterfaceProtocol.AltarStatus.WAITING) return;
		client.setScreen(new ResonanceAltarScreen(payload));
	}

	private static void acceptEncounter(Minecraft client, WorldInterfaceSnapshotS2C payload) {
		if (!WorldInterfaceClientState.accept(payload)) return;
		WorldInterfaceEndingClient.observeEncounter(client, payload);
		if (client.screen instanceof ResonanceAltarScreen altar
				&& payload.stage() != WorldInterfaceProtocol.Stage.WAITING_TERMINALS) {
			altar.closeFromServer();
		}
	}

	/**
	 * A detonation the server saw, turned into the shake this client should feel.
	 *
	 * <p>The falloff is applied here rather than on the server: the server knows where the explosion
	 * was and how big it was, the client knows where its own camera is, and splitting it that way is
	 * what lets one packet serve eight players standing in eight different places.
	 *
	 * <p>Gated on the encounter the client is actually watching, so a stale packet arriving after the
	 * fight has resolved - or one aimed at a different encounter entirely - cannot shake a camera that
	 * has already moved on.
	 */
	private static void acceptBlast(WorldInterfaceBlastS2C payload) {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.player == null) return;
		WorldInterfaceSnapshotS2C encounter = WorldInterfaceClientState.snapshot().encounter();
		if (encounter == null || !encounter.encounterId().equals(payload.encounterId())) return;
		ScreenShakeController.impulseAt(new Vec3(payload.x(), payload.y(), payload.z()),
				payload.radius(), grade(payload.grade()));
	}

	private static ScreenShakeController.Grade grade(WorldInterfaceProtocol.BlastGrade grade) {
		return switch (grade) {
			case LIGHT -> ScreenShakeController.Grade.LIGHT;
			case MEDIUM -> ScreenShakeController.Grade.MEDIUM;
			case HEAVY -> ScreenShakeController.Grade.HEAVY;
			case CATACLYSM -> ScreenShakeController.Grade.CATACLYSM;
		};
	}

	private static void acceptPoem(PoemStartS2C payload) {
		if (!WorldInterfaceClientState.accept(payload)) return;
		WorldInterfaceVanillaPoemClient.arm(payload);
	}

	private static void clearClientSession() {
		com.xm.thefourthfrequency.client_render.StormParticleReceiver.clear();
		WorldInterfaceClientState.clearSession();
		WorldInterfaceVanillaPoemClient.clearPending();
	}
}
