package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.entity.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** Actual renderers in a lit studio, plus temporal samples and a real anchor collapse. */
public final class HorrorEntityClientGameTest implements FabricClientGameTest {
	@Override public void runTest(ClientGameTestContext context){
		if(!ClientGameTestSelection.current().runsHorrorEntities())return;
		context.waitForScreen(TitleScreen.class);
		try(TestSingleplayerContext world=context.worldBuilder().create()){
			EntityVisualFixture.finishFirstBoot(context);
			world.getServer().runOnServer(HorrorEntityClientGameTest::studio);
			world.getClientWorld().waitForChunksRender();
			context.runOnClient(client->client.options.hideGui=true);
			for(String name:List.of("bacteria","him","stability_anchor","world_interface_energy_orb")){
				UUID id=world.getServer().computeOnServer(server->spawn(server,name));
				for(int frame=0;frame<3;frame++){
					context.waitTicks(frame==0?12:8);
					EntityVisualFixture.assertVisible(context,id);
					context.takeScreenshot(name+"-motion-"+frame);
				}
				if(name.equals("stability_anchor")){
					world.getServer().runOnServer(server->((StabilityAnchorEntity)server.overworld().getEntityInAnyDimension(id)).beginCollapse());
					context.waitTicks(4);EntityVisualFixture.assertVisible(context,id);context.takeScreenshot("stability-anchor-fracture");
					context.waitTicks(5);context.takeScreenshot("stability-anchor-implosion");
					context.waitTicks(12);
					world.getServer().runOnServer(server->{if(server.overworld().getEntityInAnyDimension(id)!=null)throw new AssertionError("Anchor collapse did not finish");});
				}else world.getServer().runOnServer(server->{var e=server.overworld().getEntityInAnyDimension(id);if(e!=null)e.discard();});
			}
		}finally{context.runOnClient(client->client.options.hideGui=false);}
		context.waitForScreen(TitleScreen.class);
	}
	private static void studio(MinecraftServer server){
		var level=server.overworld();level.setDayTime(6000);
		for(int x=-9;x<=9;x++)for(int z=-9;z<=9;z++){
			level.setBlockAndUpdate(new BlockPos(x,99,z),Blocks.DEEPSLATE_TILES.defaultBlockState());
			for(int y=100;y<107;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
		}
		for(int x=-7;x<=7;x++)for(int y=100;y<=105;y++)level.setBlockAndUpdate(new BlockPos(x,y,-4),Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState());
		for(int x:new int[]{-2,2})level.setBlockAndUpdate(new BlockPos(x,99,2),Blocks.SEA_LANTERN.defaultBlockState());
		var player=server.getPlayerList().getPlayers().getFirst();player.setGameMode(GameType.SPECTATOR);
		player.teleportTo(level,.5,100,6.5,Set.of(),180,0,true);
	}
	private static UUID spawn(MinecraftServer server,String name){
		var level=server.overworld();Entity entity=switch(name){
			case "bacteria"->ModEntities.BACTERIA.create(level,EntitySpawnReason.EVENT);
			case "him"->ModEntities.HIM.create(level,EntitySpawnReason.EVENT);
			case "stability_anchor"->StabilityAnchorEntity.create(level,0,.5,100,.5);
			case "world_interface_energy_orb"->ModEntities.WORLD_INTERFACE_ENERGY_ORB.create(level,EntitySpawnReason.EVENT);
			default->throw new IllegalArgumentException(name);
		};
		if(entity==null)throw new AssertionError("Missing registered entity: "+name);
		if(entity instanceof Mob mob){mob.setNoAi(true);mob.setYBodyRot(0);mob.setYHeadRot(0);}
		entity.setNoGravity(true);entity.setInvulnerable(true);
		entity.snapTo(.5,name.equals("world_interface_energy_orb")?101.8:100,.5,0,0);
		if(!level.addFreshEntity(entity))throw new AssertionError("Unable to stage "+name);
		return entity.getUUID();
	}
}
