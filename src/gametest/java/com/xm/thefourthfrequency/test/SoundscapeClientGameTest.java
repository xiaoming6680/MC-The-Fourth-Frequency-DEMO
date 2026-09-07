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
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			EntityVisualFixture.finishFirstBoot(context);
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
			});
			UUID id = world.getServer().computeOnServer(server -> {
				var player = server.getPlayerList().getPlayers().getFirst();
				var level = player.level();
				var boss = ModEntities.WORLD_INTERFACE.create(level, EntitySpawnReason.EVENT);
				if (boss == null) throw new AssertionError("Missing boss");
				boss.setNoAi(true); boss.setNoGravity(true);
				boss.snapTo(player.getX()+8,player.getY()+3,player.getZ(),0,0);
				boss.showAction(WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(),level.getGameTime(),130);
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
				boss.showAction(WorldInterfaceProtocol.BossAction.LASER_SWEEP.wireId(),boss.level().getGameTime()-90,130);
			});
			context.waitTicks(6); assertPlaying(context);
			world.getServer().runOnServer(server -> ((WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id)).showAction(0,server.overworld().getGameTime(),0));
			context.waitTicks(4);
			context.runOnClient(client -> { if (EntitySoundscape.activeMouthsForTesting()!=0 || EntitySoundscape.playingMouthsForTesting(client)!=0) throw new AssertionError("Cancelled laser still playing"); });
			world.getServer().runOnServer(server -> {
				var boss=(WorldInterfaceEntity)server.overworld().getEntityInAnyDimension(id);
				boss.showAction(1,boss.level().getGameTime()-90,130);
			});
			context.waitTicks(6); assertPlaying(context);
			context.takeScreenshot("soundscape-live-laser");
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
