package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.correction.EmptySegmentService;
import com.xm.thefourthfrequency.entity.ReworkEntity;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Method;

public final class M5GameTests implements CustomTestMethodInvoker {
	@GameTest(maxTicks = 100)
	public void pursuitReworkRapidlyCollapsesPlayerTower(GameTestHelper helper) {
		var level = helper.getLevel();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
		BlockPos base = helper.absolutePos(new BlockPos(4, 3, 4));
		for (int x = -4; x <= 4; x++) {
			for (int z = -4; z <= 4; z++) {
				level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE.defaultBlockState());
				for (int y = 0; y <= 7; y++) {
					level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
				}
			}
		}
		for (int y = 0; y < 5; y++) {
			level.setBlockAndUpdate(base.above(y), Blocks.STONE.defaultBlockState());
		}
		player.snapTo(base.getX() + 0.5D, base.getY() + 5.0D, base.getZ() + 0.5D,
				0.0F, 0.0F);
		ReworkEntity body = ModEntities.REWORK_BODY.create(level, EntitySpawnReason.EVENT);
		if (body == null) throw new AssertionError("Registered rework body factory returned null");
		body.configurePursuit(player.getUUID(), "tower-gametest", 3);
		body.snapTo(base.getX() + 3.5D, base.getY(), base.getZ() + 0.5D, 90.0F, 0.0F);
		body.setInvulnerable(true);
		helper.assertTrue(level.addFreshEntity(body), "Pursuit body must enter the tower fixture");

		helper.runAfterDelay(45, () -> {
			int remaining = 0;
			for (int y = 0; y < 5; y++) {
				if (level.getBlockState(base.above(y)).is(Blocks.STONE)) remaining++;
			}
			helper.assertTrue(remaining < 5 || body.getY() >= base.getY() + 2.0D,
					"The pursuit body must break tower support or climb into the elevated player's reach");
			body.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 220)
	public void emptySegmentApexEventsRecoverWithoutItemOrChunkLoss(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		int itemCount = inventoryCount(player);

		helper.assertTrue(EmptySegmentService.trigger(player,
				EmptySegmentService.EventType.VIEWPOINT_SEPARATION, 30),
				"Viewpoint/body separation apex event must start under server control");
		helper.runAfterDelay(40, () -> {
			var record = data.terminalRecord(player.getUUID()).orElseThrow();
			helper.assertFalse(record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false),
					"Viewpoint separation must always self-recover");
			helper.assertValueEqual(inventoryCount(player), itemCount,
					"Viewpoint separation must not delete inventory content");
			helper.assertTrue(EmptySegmentService.trigger(player,
					EmptySegmentService.EventType.EXPERIENCE_GAP, 40),
					"Experience continuity gap apex event must start");
		});
		helper.runAfterDelay(95, () -> {
			var record = data.terminalRecord(player.getUUID()).orElseThrow();
			helper.assertFalse(record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false),
					"Experience gap must self-recover");
			helper.assertTrue(record.contains(TerminalData.EMPTY_SEGMENT_GAP_FROM)
					&& record.contains(TerminalData.EMPTY_SEGMENT_GAP_TO),
					"World-authoritative before/after positions must prove action during the missing interval");
			helper.assertValueEqual(inventoryCount(player), itemCount,
					"No empty-segment apex event may permanently delete an item");
			helper.succeed();
		});
	}

	private static int inventoryCount(ServerPlayer player) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			count += stack.getCount();
		}
		return count;
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		method.invoke(this, helper);
	}
}
