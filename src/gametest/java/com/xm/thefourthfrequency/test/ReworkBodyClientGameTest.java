package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.entity.ReworkEntity;
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

/** Nine views of the three native Rework Body model layers. */
public final class ReworkBodyClientGameTest implements FabricClientGameTest {
	private static final double CAMERA_DISTANCE = 5.0D;
	private static final double DARK_CAMERA_DISTANCE = 3.25D;
	private static final UUID VISUAL_OWNER = new UUID(0L, 0L);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsReworkForms()) return;
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(client -> client.options.hideGui = true);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			EntityVisualFixture.finishFirstBoot(context);
			StudioFixture fixture = singleplayer.getServer().computeOnServer(
					ReworkBodyClientGameTest::createStudio);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			for (int stage = 1; stage <= 3; stage++) {
				capture(context, singleplayer, fixture, stage, View.FRONT);
				capture(context, singleplayer, fixture, stage, View.BACK);
				capture(context, singleplayer, fixture, stage, View.DARK);
			}
		} finally {
			context.runOnClient(client -> client.options.hideGui = false);
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static void capture(ClientGameTestContext context, TestSingleplayerContext singleplayer,
			StudioFixture fixture, int stage, View view) {
		singleplayer.getServer().runOnServer(server -> configureShot(server, fixture, stage, view));
		context.waitTicks(25);
		String suffix = switch (view) {
			case FRONT -> "front";
			case BACK -> "back";
			case DARK -> "dark";
		};
		EntityVisualFixture.assertVisible(context, fixture.bodyId());
		context.takeScreenshot("rework-body-stage-" + stage + "-" + suffix);
	}

	private static StudioFixture createStudio(MinecraftServer server) {
		server.setDifficulty(Difficulty.NORMAL, true);
		var level = server.overworld();
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
		BlockPos center = player.blockPosition().offset(0, 0, 32);
		for (int x = -10; x <= 10; x++) for (int z = -11; z <= 11; z++) {
			level.setBlockAndUpdate(center.offset(x, -1, z), Blocks.DEEPSLATE_TILES.defaultBlockState());
			for (int y = 0; y <= 7; y++) {
				level.setBlockAndUpdate(center.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		ReworkEntity body = ModEntities.REWORK_BODY.create(level, EntitySpawnReason.EVENT);
		if (body == null) throw new AssertionError("Registered rework body factory returned null");
		body.configurePursuit(VISUAL_OWNER, "visual-fixture", 1);
		body.setInvulnerable(true);
		body.setNoAi(true);
		body.snapTo(center.getX() + 0.5, center.getY(), center.getZ() + 0.5, 0.0F, 0.0F);
		body.setYBodyRot(0.0F);
		body.setYHeadRot(0.0F);
		if (!level.addFreshEntity(body)) {
			throw new AssertionError("Could not add the visual Rework Body fixture");
		}
		return new StudioFixture(center, body.getUUID());
	}

	private static void configureShot(MinecraftServer server, StudioFixture fixture, int stage, View view) {
		var level = server.overworld();
		Entity entity = level.getEntityInAnyDimension(fixture.bodyId());
		if (!(entity instanceof ReworkEntity body)) {
			throw new AssertionError("Rework Body disappeared from the visual fixture");
		}
		body.configurePursuit(VISUAL_OWNER, "visual-fixture", stage);
		body.setNoAi(true);
		body.snapTo(fixture.center().getX() + 0.5, fixture.center().getY(),
				fixture.center().getZ() + 0.5, 0.0F, 0.0F);
		body.setYBodyRot(0.0F);
		body.setYHeadRot(0.0F);
		if (body.formStage() != stage || body.morphTicks() != 0) {
			throw new AssertionError("Visual fixture did not adopt stage " + stage + " without a morph");
		}

		boolean dark = view == View.DARK;
		setStudioLighting(level, fixture.center(), !dark);
		setBackdrop(level, fixture.center(), view == View.BACK, dark);
		level.setDayTime(dark ? 18_000L : 6_000L);
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		int direction = view == View.BACK ? -1 : 1;
		float yaw = view == View.BACK ? 0.0F : 180.0F;
		double cameraDistance = dark ? DARK_CAMERA_DISTANCE : CAMERA_DISTANCE;
		player.teleportTo(level, fixture.center().getX() + 0.5, fixture.center().getY(),
				fixture.center().getZ() + 0.5 + direction * cameraDistance,
				Set.of(), yaw, 0.0F, true);
	}

	private static void setStudioLighting(net.minecraft.server.level.ServerLevel level,
			BlockPos center, boolean lit) {
		Block floor = lit ? Blocks.SEA_LANTERN : Blocks.DEEPSLATE_TILES;
		for (BlockPos offset : new BlockPos[] {
				new BlockPos(-4, -1, -2), new BlockPos(4, -1, -2),
				new BlockPos(-4, -1, 2), new BlockPos(4, -1, 2)
		}) level.setBlockAndUpdate(center.offset(offset), floor.defaultBlockState());
		for (BlockPos offset : new BlockPos[] {
				new BlockPos(-2, 2, 2), new BlockPos(0, 3, 2), new BlockPos(2, 2, 2)
		}) level.setBlockAndUpdate(center.offset(offset), lit ? Blocks.AIR.defaultBlockState()
				: Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 7));
		for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++)
			level.setBlockAndUpdate(center.offset(x, 6, z), Blocks.AIR.defaultBlockState());
	}

	private static void setBackdrop(net.minecraft.server.level.ServerLevel level,
			BlockPos center, boolean behindNorthCamera, boolean dark) {
		for (int x = -7; x <= 7; x++) for (int y = 0; y <= 5; y++) {
			level.setBlockAndUpdate(center.offset(x, y, -5), Blocks.AIR.defaultBlockState());
			level.setBlockAndUpdate(center.offset(x, y, 5), Blocks.AIR.defaultBlockState());
		}
		int z = behindNorthCamera ? 5 : -5;
		Block backdrop = dark ? Blocks.BLACK_CONCRETE : Blocks.LIGHT_GRAY_CONCRETE;
		for (int x = -7; x <= 7; x++) for (int y = 0; y <= 5; y++) {
			level.setBlockAndUpdate(center.offset(x, y, z), backdrop.defaultBlockState());
		}
	}

	private enum View { FRONT, BACK, DARK }

	private record StudioFixture(BlockPos center, UUID bodyId) { }
}
