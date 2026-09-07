package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.client_ui.EntitySoundscape;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.entity.*;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntitySpawnReason;
import java.util.UUID;

/** Real SoundManager and tracked entity data: loading, following, late action entry, cancellation. */
public final class SoundscapeClientGameTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsAudio()) return;
		context.waitForScreen(TitleScreen.class);
		OutputGainClientCheck.run(context);
		context.runOnClient(client -> {
			com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.stopAll();
			com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.tick(client,
					com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline.GLITCH_START_TICK);
		});
		context.waitTicks(4);
		context.runOnClient(client -> {
			if (com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.playingForTesting(client)!=1)
				throw new AssertionError("Opening warning failed to play");
			com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.tick(client,
					com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline.FLOOD_START_TICK);
		});
		context.waitTicks(4);
		context.runOnClient(client -> {
			if (com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.playingForTesting(client)!=1)
				throw new AssertionError("Frozen buffer did not replace the warning");
			com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.tick(client,
					com.xm.thefourthfrequency.client_ui.AlphaLoadTimeline.BLACKOUT_START_TICK);
		});
		context.waitTicks(4);
		context.runOnClient(client -> {
			if (com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.playingForTesting(client)!=0)
				throw new AssertionError("Blackout retained frozen audio");
			com.xm.thefourthfrequency.client_ui.AlphaCorruptionAudio.stopAll();
		});
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			EntityVisualFixture.finishFirstBoot(context);
			// The boot-complete notice owns a 300 ms feedback cooldown. Test the warning after it.
			context.waitTicks(8);
			context.runOnClient(client -> {
				int resolved = 0;
				for (var id : BuiltInRegistries.SOUND_EVENT.keySet()) {
					if (!id.getNamespace().equals("thefourthfrequency") || id.getPath().startsWith("music_")) continue;
					var event = BuiltInRegistries.SOUND_EVENT.getValue(id);
					var sound = SimpleSoundInstance.forUI(event, 1.0F, .25F);
					var pool = sound.resolve(client.getSoundManager());
					if (pool == null || pool.getWeight() <= 0) throw new AssertionError("Unresolved sound: " + id);
					resolved++;
				}
				if (resolved < 96) throw new AssertionError("Incomplete audio registry: " + resolved);
				var engine = ((com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor)client.getSoundManager()).thefourthfrequency$soundEngine();
				var sounds = ((com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor)engine).thefourthfrequency$instanceToChannel();
				int before = sounds.size();
				int notices = com.xm.thefourthfrequency.client_ui.TerminalClientAudio.attentionPlaysForTesting();
				com.xm.thefourthfrequency.client_ui.TerminalClientAudio.updatePanelStage(5);
				for (int repeat = 0; repeat < 10; repeat++) {
					for (int tone : new int[]{0,1,5,7,8})
						com.xm.thefourthfrequency.client_ui.TerminalClientAudio.attention(tone);
				}
				if (sounds.size()!=before || notices!=com.xm.thefourthfrequency.client_ui.TerminalClientAudio.attentionPlaysForTesting())
					throw new AssertionError("Passive guidance created audio playback");
				if (sounds.keySet().stream().filter(s -> s.getIdentifier().getPath().equals("terminal_carrier")).count() > 1)
					throw new AssertionError("Terminal stacked duplicate original carrier loops");
				if (!com.xm.thefourthfrequency.client_ui.TerminalClientAudio.audibleNotice(3)
						|| !com.xm.thefourthfrequency.client_ui.TerminalClientAudio.audibleNotice(4))
					throw new AssertionError("Actionable warnings were muted with passive guidance");
				com.xm.thefourthfrequency.client_ui.TerminalClientAudio.attention(3);
			});
			context.waitTicks(2);
			context.runOnClient(client -> {
				var engine = ((com.xm.thefourthfrequency.mixin.SoundManagerEngineAccessor)client.getSoundManager()).thefourthfrequency$soundEngine();
				var sounds = ((com.xm.thefourthfrequency.mixin.SoundEngineStateAccessor)engine).thefourthfrequency$instanceToChannel();
				if (sounds.keySet().stream().noneMatch(s -> s.getIdentifier().getPath().equals("terminal_anomaly")))
					throw new AssertionError("Original terminal vibration warning did not start playback");
			});
			UUID id = world.getServer().computeOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var level = player.level();
				var boss = ModEntities.WORLD_INTERFACE.create(level, EntitySpawnReason.EVENT);
				if (boss == null) throw new AssertionError("Missing boss");
				boss.setNoAi(true); boss.setNoGravity(true);
				boss.snapTo(player.getX()+8,player.getY()+3,player.getZ(),0,0);
				boss.showAction(WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(),level.getGameTime(),WorldInterfaceAttackMotion.LASER_DURATION_TICKS);
				if (!level.addFreshEntity(boss)) throw new AssertionError("Boss spawn failed");
				return boss.getUUID();
			});
			context.waitTicks(15);
			assertPlaying(context);
			world.getServer().runOnServer(server -> {
				var boss = (WorldInterfaceEntity) server.overworld().getEntityInAnyDimension(id);
				boss.teleportTo(boss.getX()+4,boss.getY()+2,boss.getZ()+3);
			});
			context.waitTicks(6);
			context.runOnClient(client -> {
				WorldInterfaceEntity boss = null;
				for (var e : client.level.entitiesForRendering()) if (e.getUUID().equals(id)) boss=(WorldInterfaceEntity)e;
				if (boss==null || EntitySoundscape.mouthPositionForTesting().distanceTo(WorldInterfaceAnatomy.mouthOrigin(boss,0))>.8)
					throw new AssertionError("Sound detached from animated mouth");
			});
			world.getServer().runOnServer(server -> {
				var boss=(WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id);
				boss.showAction(1,boss.level().getGameTime()-60,WorldInterfaceAttackMotion.LASER_DURATION_TICKS);
			});
			context.waitTicks(6); assertPlaying(context);
			context.runOnClient(client -> {
				float offset = EntitySoundscape.alignedMouthOffsetForTesting();
				if (offset < 3.0F || offset >= 4.5F)
					throw new AssertionError("Late warning restarted audio instead of seeking: " + offset);
			});
			world.getServer().runOnServer(server -> {
				var boss=(WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id);
				boss.setForm(2);
				boss.showAction(WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(),boss.level().getGameTime()-90,WorldInterfaceAttackMotion.LASER_DURATION_TICKS);
			});
			context.waitTicks(6); assertPlaying(context);
			context.runOnClient(client -> {
				for (var entity : client.level.entitiesForRendering()) {
					if (!entity.getUUID().equals(id)) continue;
					var boss = (WorldInterfaceEntity) entity;
					if (EntitySoundscape.mouthPositionForTesting().distanceTo(WorldInterfaceAnatomy.mouthOrigin(boss,1))>.8)
						throw new AssertionError("Third-form beam sound used the inactive center mouth");
				}
			});
			world.getServer().runOnServer(server -> ((WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id)).showAction(0,server.overworld().getGameTime(),0));
			context.waitTicks(4);
			context.runOnClient(client -> { if (EntitySoundscape.activeMouthsForTesting()!=0 || EntitySoundscape.playingMouthsForTesting(client)!=0) throw new AssertionError("Cancelled laser still playing"); });
			world.getServer().runOnServer(server -> {
				var boss=(WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id);
				boss.showAction(1,boss.level().getGameTime()-90,WorldInterfaceAttackMotion.LASER_DURATION_TICKS);
			});
			context.waitTicks(6); assertPlaying(context);
			context.takeScreenshot("soundscape-live-laser");
			world.getServer().runOnServer(server -> {
				var boss=(WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id);
				boss.showAction(1,boss.level().getGameTime()-WorldInterfaceAttackMotion.LASER_END_TICK,WorldInterfaceAttackMotion.LASER_DURATION_TICKS);
			});
			context.waitTicks(4);
			context.runOnClient(client -> {
				if (EntitySoundscape.activeMouthsForTesting()!=0)
					throw new AssertionError("Recovering jaw retained a live beam loop");
			});
		}
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(client -> { if (EntitySoundscape.activeMouthsForTesting()!=0) throw new AssertionError("Disconnected world retained sound"); });
	}
	private static void assertPlaying(ClientGameTestContext context) {
		context.runOnClient(client -> {
			if (EntitySoundscape.activeMouthsForTesting()!=1 || EntitySoundscape.playingMouthsForTesting(client)!=1)
				throw new AssertionError("Expected exactly one real, playing mouth sound; tracked="+EntitySoundscape.activeMouthsForTesting()+", playing="+EntitySoundscape.playingMouthsForTesting(client));
		});
	}
}
