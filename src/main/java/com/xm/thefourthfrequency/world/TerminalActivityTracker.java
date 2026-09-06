package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.TerminalData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class TerminalActivityTracker {
	private static final Map<MinecraftServer, List<PendingPlacement>> PENDING_PLACEMENTS = new IdentityHashMap<>();

	private TerminalActivityTracker() {
	}

	public static void initialize() {
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer serverPlayer && !PrivateDimensions.isPrivate(level)) {
				record(serverPlayer, TerminalData.MINED_BLOCKS, "mined");
			}
		});

		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
				return InteractionResult.PASS;
			}
			if (PrivateDimensions.isPrivate(level)) return InteractionResult.PASS;
			if (level.getBlockEntity(hit.getBlockPos()) != null) {
				record(serverPlayer, TerminalData.DEVICE_INTERACTIONS, "device");
			}
			ItemStack held = player.getItemInHand(hand);
			if (held.getItem() instanceof BlockItem blockItem) {
				BlockPos hitPos = hit.getBlockPos().immutable();
				BlockPos adjacent = hitPos.relative(hit.getDirection()).immutable();
				PENDING_PLACEMENTS.computeIfAbsent(serverPlayer.level().getServer(), ignored -> new ArrayList<>())
						.add(new PendingPlacement(serverPlayer, blockItem.getBlock(), hitPos, adjacent,
								level.getBlockState(hitPos), level.getBlockState(adjacent)));
			}
			return InteractionResult.PASS;
		});

		ServerTickEvents.END_SERVER_TICK.register(TerminalActivityTracker::verifyPlacements);
		ServerLifecycleEvents.SERVER_STOPPED.register(PENDING_PLACEMENTS::remove);
	}

	private static void verifyPlacements(MinecraftServer server) {
		List<PendingPlacement> pending = PENDING_PLACEMENTS.remove(server);
		if (pending == null) {
			return;
		}
		for (PendingPlacement placement : pending) {
			if (PrivateDimensions.isPrivate(placement.player.level())) continue;
			BlockState hitNow = placement.player.level().getBlockState(placement.hitPos);
			BlockState adjacentNow = placement.player.level().getBlockState(placement.adjacentPos);
			boolean hitChanged = !hitNow.equals(placement.hitBefore) && hitNow.is(placement.block);
			boolean adjacentChanged = !adjacentNow.equals(placement.adjacentBefore) && adjacentNow.is(placement.block);
			if (hitChanged || adjacentChanged) {
				record(placement.player, TerminalData.PLACED_BLOCKS, "placed");
			}
		}
	}

	public static void record(ServerPlayer player, String counterKey, String activityKey) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) {
			return;
		}
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putInt(counterKey, tag.getIntOr(counterKey, 0) + 1);
			tag.putString(TerminalData.LAST_ACTIVITY, activityKey);
			tag.putLong(TerminalData.LAST_ACTIVITY_GAME_TIME, player.level().getGameTime());
		});
	}

	public static void recordCrafted(ServerPlayer player, ItemStack crafted) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) {
			return;
		}
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putInt(TerminalData.CRAFTED_ITEMS,
					tag.getIntOr(TerminalData.CRAFTED_ITEMS, 0) + crafted.getCount());
			tag.putString(TerminalData.LAST_CRAFTED_ITEM,
					BuiltInRegistries.ITEM.getKey(crafted.getItem()).toString());
			tag.putString(TerminalData.LAST_ACTIVITY, "crafted");
			tag.putLong(TerminalData.LAST_ACTIVITY_GAME_TIME, player.level().getGameTime());
		});
	}

	private record PendingPlacement(ServerPlayer player, Block block, BlockPos hitPos, BlockPos adjacentPos,
			BlockState hitBefore, BlockState adjacentBefore) {
	}
}
