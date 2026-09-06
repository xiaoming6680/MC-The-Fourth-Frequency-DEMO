package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.pursuit.PursuitBlockPolicy;
import com.xm.thefourthfrequency.pursuit.PursuitRecoveryLedger;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The chase's server-side runtime, for the parts a pure test cannot reach.
 *
 * <p>The chase had ten unit tests and one game test, and the game test only checked that the six
 * mirror dimension files were packaged. Everything with a consequence a player can feel - the refund
 * ledger that owes them the blocks they spent, and the rule that decides what may exist in the copy
 * of their world at all - was covered by neither, because both need a loaded server: one needs a
 * player with an inventory and a persistent record, the other needs the block registry.
 *
 * <p>The mirror dimensions themselves are deliberately not exercised here. They are not registered
 * on the game-test server, so anything that tries to enter one asserts the fixture rather than the
 * feature; entry and return stay manual-acceptance items.
 */
public final class PursuitRuntimeGameTests implements CustomTestMethodInvoker {
	/**
	 * A refund gives back the stack that was spent, not something that shares its id.
	 *
	 * <p>The ledger used to store a registry id and a number, so a block a player had named on an
	 * anvil came back as an ordinary one and two differently named stacks of the same block were
	 * merged into whichever was written first. That is the mirror taking something and handing back a
	 * substitute, which is the one thing the refund exists to prevent.
	 */
	@GameTest
	public void refundsReturnTheExactStackThatWasPlaced(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = requireRecord(helper, player);
		clearInventory(player);

		ItemStack named = new ItemStack(Items.STONE, 2);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Marked"));
		PursuitRecoveryLedger.recordPlacement(player, named);
		PursuitRecoveryLedger.recordPlacement(player, new ItemStack(Items.STONE, 3));
		PursuitRecoveryLedger.settleAndDeliver(player);

		int plain = 0;
		int marked = 0;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(Items.STONE)) continue;
			if (stack.has(DataComponents.CUSTOM_NAME)) marked += stack.getCount();
			else plain += stack.getCount();
		}
		helper.assertValueEqual(2, marked, "Named stone returned");
		helper.assertValueEqual(3, plain, "Plain stone returned");
		helper.assertTrue(ledger(data, player, TerminalData.PURSUIT_REFUND_LEDGER).isEmpty(),
				"A settled ledger must be empty");
		helper.succeed();
	}

	/**
	 * A settle that cannot fit everything owes the rest, and owes it exactly once.
	 *
	 * <p>Both halves matter and they fail in opposite directions: an overflow that is not queued
	 * loses the items outright, and a ledger that is not cleared as it is paid hands them out again
	 * on the next disconnect, death or restart - which is a duplication bug reachable by dying.
	 */
	@GameTest
	public void overflowIsQueuedOnceAndPaidOnce(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = requireRecord(helper, player);
		fillInventory(player);

		PursuitRecoveryLedger.recordPlacement(player, new ItemStack(Items.COBBLESTONE, 7));
		PursuitRecoveryLedger.settleAndDeliver(player);
		helper.assertValueEqual(0, totalCount(player, Items.COBBLESTONE),
				"Cobblestone accepted by a full inventory");
		helper.assertValueEqual(7, countIn(ledger(data, player, TerminalData.PURSUIT_RECOVERY_QUEUE)),
				"Cobblestone still owed after a failed delivery");

		clearInventory(player);
		PursuitRecoveryLedger.settleAndDeliver(player);
		helper.assertValueEqual(7, totalCount(player, Items.COBBLESTONE), "Cobblestone paid on retry");
		helper.assertTrue(ledger(data, player, TerminalData.PURSUIT_RECOVERY_QUEUE).isEmpty(),
				"A paid queue must be empty");

		PursuitRecoveryLedger.settleAndDeliver(player);
		helper.assertValueEqual(7, totalCount(player, Items.COBBLESTONE),
				"A second settle must not pay the same debt twice");
		helper.succeed();
	}

	/**
	 * One line that cannot be rebuilt does not take the rest of the ledger with it.
	 *
	 * <p>An id that no longer resolves used to be skipped in silence, with the entry already cleared -
	 * the player was simply short. It is now dropped loudly (a warning, and a notice on their own
	 * terminal), and what this pins is the part that would still be a bug either way: the entries
	 * around it are still paid.
	 */
	@GameTest
	public void anUnrebuildableLineIsDroppedWithoutLosingTheOnesBesideIt(GameTestHelper helper) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		FrequencyWorldData data = requireRecord(helper, player);
		clearInventory(player);

		data.updateTerminalRecord(player.getUUID(), record -> {
			ListTag ledger = new ListTag();
			ledger.add(entryTag("thefourthfrequency:a_block_that_was_removed", 4));
			ledger.add(entryTag("minecraft:cobblestone", 5));
			record.put(TerminalData.PURSUIT_REFUND_LEDGER, ledger);
		});
		PursuitRecoveryLedger.settleAndDeliver(player);

		helper.assertValueEqual(5, totalCount(player, Items.COBBLESTONE),
				"The resolvable line beside a broken one");
		helper.assertTrue(ledger(data, player, TerminalData.PURSUIT_REFUND_LEDGER).isEmpty(),
				"The broken line must not be retried forever");
		helper.assertTrue(ledger(data, player, TerminalData.PURSUIT_RECOVERY_QUEUE).isEmpty(),
				"A line that cannot be rebuilt must not be re-queued as owed");
		helper.succeed();
	}

	/**
	 * What may exist in the mirror, asked of the block rather than of its name.
	 *
	 * <p>Every id in the refused list below is one the old substring rule got wrong, and the respawn
	 * anchor is the one that mattered: it has no block entity, it was copied into the Nether mirror
	 * as itself, and an empty hand can use it - so a player could set their spawn point inside a
	 * private dimension that stops existing when their session does.
	 *
	 * <p>The allowed list is the other half of the same rule. A cauldron and a composter both report
	 * an analog output and neither is redstone hardware; refusing them would have been the easy
	 * over-correction, and it would turn parts of a player's own base to stone in the copy of it.
	 */
	@GameTest
	public void theMirrorRefusesHardwareAndKeepsFurniture(GameTestHelper helper) {
		Block[] refused = {
			Blocks.RESPAWN_ANCHOR, Blocks.TARGET, Blocks.LIGHTNING_ROD, Blocks.NOTE_BLOCK,
			Blocks.COPPER_BULB, Blocks.OXIDIZED_COPPER_BULB, Blocks.REDSTONE_BLOCK,
			Blocks.REDSTONE_TORCH, Blocks.REDSTONE_LAMP, Blocks.REDSTONE_ORE, Blocks.REPEATER,
			Blocks.COMPARATOR, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.OBSERVER, Blocks.RAIL,
			Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.TRIPWIRE_HOOK, Blocks.STONE_BUTTON,
			Blocks.LEVER, Blocks.OAK_PRESSURE_PLATE, Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
			Blocks.SCULK_SENSOR, Blocks.CALIBRATED_SCULK_SENSOR, Blocks.RED_BED, Blocks.TNT,
			Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.END_PORTAL_FRAME,
			Blocks.FIRE, Blocks.SOUL_FIRE,
		};
		for (Block block : refused) {
			helper.assertFalse(PursuitBlockPolicy.simplePlacement(block),
					"The mirror must refuse " + block);
		}

		Block[] allowed = {
			Blocks.STONE, Blocks.COBBLESTONE, Blocks.OAK_PLANKS, Blocks.GLOWSTONE, Blocks.DIRT,
			Blocks.CAULDRON, Blocks.COMPOSTER, Blocks.OAK_FENCE, Blocks.GLASS,
		};
		for (Block block : allowed) {
			helper.assertTrue(PursuitBlockPolicy.simplePlacement(block),
					"The mirror must still allow " + block);
		}

		// The snapshot side of the same rule: refusing to place one is not enough if the copy of the
		// player's world hands them a working one that was already there.
		BlockPos position = helper.absolutePos(BlockPos.ZERO);
		BlockState anchor = PursuitBlockPolicy.sanitizeSnapshotState(helper.getLevel(), position,
				Blocks.RESPAWN_ANCHOR.defaultBlockState());
		helper.assertFalse(anchor.is(Blocks.RESPAWN_ANCHOR),
				"A respawn anchor must never be copied into a mirror as itself");
		BlockState stone = PursuitBlockPolicy.sanitizeSnapshotState(helper.getLevel(), position,
				Blocks.STONE.defaultBlockState());
		helper.assertTrue(stone.is(Blocks.STONE), "Ordinary terrain must survive the snapshot unchanged");
		helper.succeed();
	}

	private static CompoundTag entryTag(String itemId, int count) {
		CompoundTag entry = new CompoundTag();
		entry.putString("item", itemId);
		entry.putInt("count", count);
		return entry;
	}

	/**
	 * The fixture every ledger test needs, and the one thing about it that is easy to get wrong.
	 *
	 * <p>A mock server player is created in creative, and {@code recordPlacement} deliberately
	 * refuses to bill a creative placement - so left as they arrive, every test here would record
	 * nothing, deliver nothing, and pass for the wrong reason the moment the ledger stopped working.
	 * Survival is the mode the feature exists for.
	 */
	private static FrequencyWorldData requireRecord(GameTestHelper helper, ServerPlayer player) {
		player.setGameMode(GameType.SURVIVAL);
		helper.assertFalse(player.getAbilities().instabuild,
				"Fixture: refunds are only ever recorded for a survival placement");
		FrequencyWorldData data = FrequencyWorldData.get(helper.getLevel().getServer());
		helper.assertTrue(data.terminalRecord(player.getUUID()).isPresent(),
				"Fixture: a mock player must already own a terminal record");
		return data;
	}

	private static ListTag ledger(FrequencyWorldData data, ServerPlayer player, String key) {
		return data.terminalRecord(player.getUUID()).orElseThrow().getListOrEmpty(key);
	}

	private static int countIn(ListTag list) {
		int total = 0;
		for (int index = 0; index < list.size(); index++) {
			total += list.getCompoundOrEmpty(index).getIntOr("count", 0);
		}
		return total;
	}

	private static int totalCount(ServerPlayer player, net.minecraft.world.item.Item item) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static void clearInventory(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			inventory.setItem(slot, ItemStack.EMPTY);
		}
	}

	/** Every slot occupied by a stack that is already full and cannot merge with anything. */
	private static void fillInventory(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.BEDROCK, Items.BEDROCK.getDefaultMaxStackSize()));
		}
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		helper.setBlock(0, 0, 0, Blocks.AIR);
		try {
			method.invoke(this, helper);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof AssertionError error) throw error;
			if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
			throw exception;
		}
	}
}
