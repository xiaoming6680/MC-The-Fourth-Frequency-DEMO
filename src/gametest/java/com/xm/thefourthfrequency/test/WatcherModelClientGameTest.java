package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;

import java.util.Set;
import java.util.UUID;

/** Four-shot studio contract for the native Watcher model, UVs, culling, and eye emission. */
public final class WatcherModelClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsWatcherModel()) return;
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(client -> client.options.hideGui = true);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			EntityVisualFixture.finishFirstBoot(context);
			StudioFixture fixture = singleplayer.getServer().computeOnServer(
					WatcherModelClientGameTest::createStudio);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			capture(context, singleplayer, fixture, View.FRONT);
			capture(context, singleplayer, fixture, View.THREE_QUARTER);
			capture(context, singleplayer, fixture, View.BACK);
			capture(context, singleplayer, fixture, View.DARK_CLOSEUP);
		} finally {
			context.runOnClient(client -> client.options.hideGui = false);
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static void capture(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			StudioFixture fixture, View view) {
		singleplayer.getServer().runOnServer(server -> configureShot(server, fixture, view));
		context.waitTicks(25);
		String suffix = switch (view) {
			case FRONT -> "front";
			case THREE_QUARTER -> "three-quarter";
			case BACK -> "back";
			case DARK_CLOSEUP -> "dark-closeup";
		};
		EntityVisualFixture.assertVisible(context, fixture.watcherId());
		context.takeScreenshot("watcher-model-" + suffix);
	}

	private static StudioFixture createStudio(MinecraftServer server) {
		server.setDifficulty(Difficulty.NORMAL, true);
		var level = server.overworld();
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
		BlockPos center = player.blockPosition().offset(0, 0, 32);
		for (int x = -10; x <= 10; x++) for (int z = -11; z <= 11; z++) {
			level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
			for (int y = 0; y <= 7; y++) level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
		}
		WatcherEntity watcher = ModEntities.WATCHER.create(level, EntitySpawnReason.EVENT);
		if (watcher == null) throw new AssertionError("Watcher visual fixture creation failed");
		watcher.snapTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 0.0F, 0.0F);
		watcher.observe(player, 400);
		watcher.setNoAi(true);
		watcher.setYBodyRot(0.0F);
		watcher.setYHeadRot(0.0F);
		if (!level.addFreshEntity(watcher)) throw new AssertionError("Watcher visual fixture spawn failed");
		if (Math.abs(watcher.getBbWidth() - 0.62F) > 0.001F
				|| Math.abs(watcher.getBbHeight() - 2.9F) > 0.001F
				|| Math.abs(watcher.getEyeHeight() - 2.62F) > 0.001F) {
			throw new AssertionError("Watcher visual fixture changed the frozen physical dimensions");
		}
		return new StudioFixture(center, watcher.getUUID());
	}

	private static void configureShot(MinecraftServer server, StudioFixture fixture, View view) {
		var level = server.overworld();
		Entity entity = level.getEntityInAnyDimension(fixture.watcherId());
		if (!(entity instanceof WatcherEntity watcher)) {
			throw new AssertionError("Watcher disappeared from the visual fixture");
		}
		watcher.setNoAi(true);
		watcher.snapTo(fixture.center().getX() + 0.5, fixture.center().getY(),
				fixture.center().getZ() + 0.5, 0.0F, 0.0F);
		watcher.setYBodyRot(0.0F);
		watcher.setYHeadRot(0.0F);
		boolean dark = view == View.DARK_CLOSEUP;
		setStudioLighting(level, fixture.center(), !dark);
		setBackdrop(level, fixture.center(), view, dark);
		level.setDayTime(dark ? 18_000L : 6_000L);

		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		double centerX = fixture.center().getX() + 0.5D;
		double centerY = fixture.center().getY();
		double centerZ = fixture.center().getZ() + 0.5D;
		switch (view) {
			case FRONT -> player.teleportTo(level, centerX, centerY, centerZ + 4.4D,
					Set.of(), 180.0F, 0.0F, true);
			case THREE_QUARTER -> player.teleportTo(level, centerX + 3.1D, centerY, centerZ + 3.1D,
					Set.of(), 135.0F, 0.0F, true);
			case BACK -> player.teleportTo(level, centerX, centerY, centerZ - 4.4D,
					Set.of(), 0.0F, 0.0F, true);
			case DARK_CLOSEUP -> player.teleportTo(level, centerX, centerY + 0.75D, centerZ + 2.5D,
					Set.of(), 180.0F, -6.0F, true);
		}
	}

	private static void setStudioLighting(net.minecraft.server.level.ServerLevel level,
			BlockPos center, boolean lit) {
		Block floorLight = lit ? Blocks.SEA_LANTERN : Blocks.DEEPSLATE_TILES;
		for (BlockPos offset : new BlockPos[] {
			new BlockPos(-4, -1, -2), new BlockPos(4, -1, -2),
			new BlockPos(-4, -1, 2), new BlockPos(4, -1, 2)
		}) level.setBlockAndUpdate(center.offset(offset), floorLight.defaultBlockState());
		for (BlockPos offset : new BlockPos[] {
			new BlockPos(-2, 2, 2), new BlockPos(0, 3, 2), new BlockPos(2, 2, 2)
		}) level.setBlockAndUpdate(center.offset(offset), Blocks.LIGHT.defaultBlockState()
				.setValue(LightBlock.LEVEL, lit ? 11 : 6));
		level.setBlockAndUpdate(center.above(2), lit ? Blocks.AIR.defaultBlockState()
				: Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9));
	}

	private static void setBackdrop(net.minecraft.server.level.ServerLevel level,
			BlockPos center, View view, boolean dark) {
		for (int x = -8; x <= 8; x++) for (int y = 0; y <= 7; y++) {
			level.setBlockAndUpdate(center.offset(x, y, -5), Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(center.offset(x, y, 5), Blocks.AIR.defaultBlockState());
		}
		int z = view == View.BACK ? 5 : -5;
		Block backdrop = dark ? Blocks.GRAY_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE;
		for (int x = -8; x <= 8; x++) for (int y = 0; y <= 7; y++) {
			level.setBlockAndUpdate(center.offset(x, y, z), backdrop.defaultBlockState());
		}
	}

	private enum View { FRONT, THREE_QUARTER, BACK, DARK_CLOSEUP }

	private record StudioFixture(BlockPos center, UUID watcherId) { }
}
