package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.terminal.TerminalNoticeService;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;

/**
 * The visible half of weapon custody.
 *
 * <p>Custody leaves a barrier in the slot, named and coloured, and it cannot be dropped or used.
 *
 * <p>It was silent for a while - the slot simply emptied - on the argument that being told the
 * weapon is coming back spends the fear. In play that argument turned out to be backwards: an item
 * that vanishes mid-fight with nothing left behind reads as <em>lost</em> rather than as
 * <em>taken</em>, and a player who believes their sword is gone for good plays the rest of the
 * encounter as though it were. That is a real cost paid for an effect nobody reported feeling, so
 * the placeholder is back.
 *
 * <p>The placeholder must keep being un-droppable and un-usable until the ledger resolves it: one
 * that escaped into the world would resolve for nobody, since recovery only ever scans its own
 * owner's inventory. Saves written during the silent period simply have no placeholder to clear,
 * and every clear path is a no-op on them.</p>
 */
public final class ConfiscationService {
	/** Present on every legacy placeholder, holding the ledger entry the real stack waits under. */
	public static final String MARKER_KEY = "thefourthfrequency_confiscated";
	private static boolean initialized;

	private ConfiscationService() {
	}

	/**
	 * Blocks every use path a barrier stack has. Dropping is refused in {@code LivingEntityDropMixin},
	 * which is the only path that is not an interaction callback - and which has to sit on
	 * {@code LivingEntity} rather than {@code Player}, or death drops walk straight past it and
	 * scatter placeholders that {@link #clearPlaceholder} can then never find.
	 */
	public static synchronized void initialize() {
		if (initialized) return;
		initialized = true;
		UseBlockCallback.EVENT.register((player, level, hand, hit) ->
				isPlaceholder(player.getItemInHand(hand)) ? refuse(player) : InteractionResult.PASS);
		UseItemCallback.EVENT.register((player, level, hand) ->
				isPlaceholder(player.getItemInHand(hand)) ? refuse(player) : InteractionResult.PASS);
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
				isPlaceholder(player.getItemInHand(hand)) ? refuse(player) : InteractionResult.PASS);
	}

	private static InteractionResult refuse(Player player) {
		if (player instanceof ServerPlayer serverPlayer) {
			TerminalNoticeService.denied(serverPlayer,
					"message.thefourthfrequency.world_interface.confiscated_locked");
		}
		return InteractionResult.FAIL;
	}

	/** The stack that stands in the slot while the interface is holding the real one. */
	public static ItemStack placeholder(UUID recoveryId) {
		ItemStack stack = new ItemStack(Items.BARRIER);
		CompoundTag marker = new CompoundTag();
		marker.putString(MARKER_KEY, recoveryId.toString());
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
				"item.thefourthfrequency.confiscated").withStyle(ChatFormatting.RED));
		return stack;
	}

	public static boolean isPlaceholder(ItemStack stack) {
		return recoveryId(stack) != null;
	}

	public static UUID recoveryId(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.is(Items.BARRIER)) return null;
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return null;
		String encoded = data.copyTag().getStringOr(MARKER_KEY, "");
		if (encoded.isBlank()) return null;
		try {
			return UUID.fromString(encoded);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	/**
	 * Removes the legacy placeholder for one custody, if this save still has one.
	 *
	 * <p>The returned slot is no longer used to place the recovered stack: the weapon comes back
	 * wherever the inventory puts it, so that its return is something the player has to notice.
	 *
	 * @return the freed slot, or -1 if the player was not holding this placeholder
	 */
	public static int clearPlaceholder(ServerPlayer player, UUID recoveryId) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (!recoveryId.equals(recoveryId(player.getInventory().getItem(slot)))) continue;
			player.getInventory().setItem(slot, ItemStack.EMPTY);
			return slot;
		}
		return -1;
	}

	/** Sweeps every placeholder, for restarts and teardowns where no ledger entry will resolve. */
	public static int clearPlaceholders(ServerPlayer player) {
		int cleared = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (!isPlaceholder(player.getInventory().getItem(slot))) continue;
			player.getInventory().setItem(slot, ItemStack.EMPTY);
			cleared++;
		}
		return cleared;
	}
}
