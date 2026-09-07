package com.xm.thefourthfrequency.test;

import com.mojang.authlib.GameProfile;
import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.audio.ModSounds;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.sounds.SoundSource;
import java.util.UUID;

public final class SoundscapeGameTests {
	@GameTest public void privateSightingSoundReachesOnlyItsObserver(GameTestHelper helper) {
		Peer alice = peer(helper,"audio-alice");
		Peer bob = peer(helper,"audio-bob");
		try {
			drain(alice.channel); drain(bob.channel);
			AudioService.playForPlayer(alice.player,alice.player.position(),ModSounds.WATCHER_VANISH,SoundSource.AMBIENT,.6F);
			alice.channel.runPendingTasks(); bob.channel.runPendingTasks();
			if (drain(alice.channel)!=1 || drain(bob.channel)!=0)
				throw new AssertionError("Private sound leaked or was never sent to observer");
			helper.succeed();
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(alice.player);
			helper.getLevel().getServer().getPlayerList().remove(bob.player);
			alice.channel.finishAndReleaseAll(); bob.channel.finishAndReleaseAll();
		}
	}
	@GameTest public void authoredBlastRangeDoesNotRequireExcessiveGain(GameTestHelper helper) {
		if (ModSounds.WORLD_INTERFACE_BLAST.getRange(.15F)!=72F
				|| ModSounds.WORLD_INTERFACE_BLAST.getRange(.95F)!=72F
				|| ModSounds.WORLD_INTERFACE_LASER_LOOP.getRange(.5F)!=96F)
			throw new AssertionError("Authored spatial cues lost their fixed range");
		helper.succeed();
	}
	private static Peer peer(GameTestHelper helper,String name) {
		var server=helper.getLevel().getServer();
		var profile=new GameProfile(UUID.randomUUID(),name);
		var cookie=CommonListenerCookie.createInitial(profile,false);
		var player=new ServerPlayer(server,helper.getLevel(),profile,cookie.clientInformation());
		var connection=new Connection(PacketFlow.SERVERBOUND);
		var channel=new EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection,player,cookie);
		return new Peer(player,channel);
	}
	private static int drain(EmbeddedChannel channel) {
		int count=0; Object packet;
		while((packet=channel.readOutbound())!=null) {
			if(packet instanceof ClientboundSoundPacket) count++;
			io.netty.util.ReferenceCountUtil.release(packet);
		}
		return count;
	}
	private record Peer(ServerPlayer player,EmbeddedChannel channel) { }
}
