package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.ending.WorldInterfaceRitualService;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TerminalLifecycleService {
	private static final Map<UUID, ItemStack> PENDING_RECOVERY = new HashMap<>();
	private static final Set<UUID> RECOVERY_NOTIFIED = new HashSet<>();

	private TerminalLifecycleService() {
	}

	public static void initialize() {
		ServerPlayerEvents.JOIN.register(player -> reconcileOnJoin(player));
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			FrequencyWorldData data = FrequencyWorldData.get(newPlayer.level().getServer());
			if (data.terminalRecord(newPlayer.getUUID())
					.map(record -> record.getBooleanOr(TerminalData.BOUND, false)).orElse(false)) {
				ensureCarried(newPlayer, true);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(TerminalLifecycleService::onServerTick);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			PENDING_RECOVERY.clear();
			RECOVERY_NOTIFIED.clear();
		});
	}

	private static void reconcileOnJoin(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (!data.hasTerminalIssued(player.getUUID())) {
			return;
		}
		data.ensureTerminalRecord(player);
		recordDimension(player, data);
		ensureCarried(player, true);
	}

	public static void recordCurrentDimension(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isPresent()) {
			recordDimension(player, data);
		}
	}

	private static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % 10 != 0) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FrequencyWorldData data = FrequencyWorldData.get(server);
			if (data.terminalRecord(player.getUUID()).isEmpty()) {
				continue;
			}
			recordDimension(player, data);
			boolean bound = data.terminalRecord(player.getUUID()).orElseThrow()
					.getBooleanOr(TerminalData.BOUND, false);
			if (bound || PENDING_RECOVERY.containsKey(player.getUUID())) {
				ensureCarried(player, false);
			} else {
				synchronizeValidCopies(player, data);
			}
		}
	}

	private static void recordDimension(ServerPlayer player, FrequencyWorldData data) {
		if (PrivateDimensions.isPrivate(player.level())) return;
		String dimension = player.level().dimension().identifier().toString();
		CompoundTag current = data.terminalRecord(player.getUUID()).orElseThrow();
		String visited = current.getStringOr(TerminalData.VISITED_DIMENSIONS, "");
		if (containsEntry(visited, dimension)) {
			return;
		}
		data.updateTerminalRecord(player.getUUID(), record -> record.putString(
				TerminalData.VISITED_DIMENSIONS, appendEntry(visited, dimension)));
		synchronizeValidCopies(player, data);
	}

	public static boolean ensureCarried(ServerPlayer player, boolean allowUnboundRecovery) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null) {
			return false;
		}
		if (WorldInterfaceRitualService.terminalRecoverySuppressed(
				player.level().getServer(), player.getUUID())) {
			clearTransientRecovery(player.getUUID());
			return false;
		}
		if (record.getBooleanOr(TerminalData.TERMINAL_CAPTURED, false)) {
			clearTransientRecovery(player.getUUID());
			removeOwnedTerminalCopies(player);
			return false;
		}
		int validCopies = synchronizeValidCopies(player, data);
		if (validCopies > 0) {
			PENDING_RECOVERY.remove(player.getUUID());
			RECOVERY_NOTIFIED.remove(player.getUUID());
			return true;
		}
		boolean bound = record.getBooleanOr(TerminalData.BOUND, false);
		if (!bound && !allowUnboundRecovery && !PENDING_RECOVERY.containsKey(player.getUUID())) {
			return false;
		}

		ItemStack recovery = PENDING_RECOVERY.computeIfAbsent(player.getUUID(), ignored ->
				data.recoverTerminal(player.getUUID()));
		if (insertIntoSafeSlot(player, recovery)) {
			PENDING_RECOVERY.remove(player.getUUID());
			RECOVERY_NOTIFIED.remove(player.getUUID());
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.send(player,
					Component.translatable("message.thefourthfrequency.terminal.recovered"));
			return true;
		}
		if (RECOVERY_NOTIFIED.add(player.getUUID())) {
			com.xm.thefourthfrequency.terminal.TerminalNoticeService.denied(player,
					"message.thefourthfrequency.terminal.recovery_waiting");
		}
		return false;
	}

	public static boolean adminRepair(ServerPlayer player) {
		if (WorldInterfaceRitualService.terminalRecoverySuppressed(
				player.level().getServer(), player.getUUID())) return false;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
		if (record == null || record.getBooleanOr(TerminalData.TERMINAL_CAPTURED, false)) {
			return false;
		}
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (TerminalData.belongsTo(stack, player.getUUID())) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
			}
		}
		PENDING_RECOVERY.remove(player.getUUID());
		RECOVERY_NOTIFIED.remove(player.getUUID());
		ItemStack repaired = data.recoverTerminal(player.getUUID());
		if (insertIntoSafeSlot(player, repaired)) {
		} else {
			PENDING_RECOVERY.put(player.getUUID(), repaired);
		}
		return true;
	}

	private static boolean insertIntoSafeSlot(ServerPlayer player, ItemStack terminal) {
		int freeSlot = player.getInventory().getFreeSlot();
		if (freeSlot < 0) {
			return false;
		}
		player.getInventory().setItem(freeSlot, terminal.copy());
		player.getInventory().setChanged();
		return true;
	}

	/** Inserts a journal-backed return without incrementing the copy generation. */
	public static boolean insertAuthoritativeReturn(ServerPlayer player, ItemStack terminal) {
		if (terminal.isEmpty() || !TerminalData.belongsTo(terminal, player.getUUID())) return false;
		boolean inserted = insertIntoSafeSlot(player, terminal);
		if (inserted) clearTransientRecovery(player.getUUID());
		return inserted;
	}

	/** Drops only the old in-memory recovery attempt; authoritative ledgers live in SavedData. */
	public static void clearTransientRecovery(UUID playerId) {
		PENDING_RECOVERY.remove(playerId);
		RECOVERY_NOTIFIED.remove(playerId);
	}

	/**
	 * The stack on the player's cursor, which is where a terminal lives for as long as the player is
	 * dragging it from one inventory slot to another. It is not an inventory slot, so a scan that only
	 * walks the player's inventory reads a terminal mid-move as gone.
	 */
	private static ItemStack carriedStack(ServerPlayer player) {
		return player.containerMenu == null ? ItemStack.EMPTY : player.containerMenu.getCarried();
	}

	private static int synchronizeValidCopies(ServerPlayer player, FrequencyWorldData data) {
		int validCopies = 0;
		boolean inventoryChanged = false;
		// Counted ahead of the slot scan, and deliberately never cleared here: a terminal on the
		// cursor is being moved, not lost. Without it, reconciliation reads zero copies and issues a
		// recovery mid-drag, which bumps the copy generation and voids the very stack in hand - so
		// the terminal appears to refuse being moved within the inventory at all.
		if (data.isValidTerminal(carriedStack(player), player.getUUID())) validCopies++;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.is(ModItems.OLD_TERMINAL)) {
				continue;
			}
			if (data.isValidTerminal(stack, player.getUUID())) {
				if (validCopies++ > 0) {
					player.getInventory().setItem(slot, ItemStack.EMPTY);
					inventoryChanged = true;
				}
			} else if (TerminalData.belongsTo(stack, player.getUUID())) {
				player.getInventory().setItem(slot, ItemStack.EMPTY);
				inventoryChanged = true;
			}
		}
		if (inventoryChanged) player.getInventory().setChanged();
		return Math.min(validCopies, 1);
	}

	private static void removeOwnedTerminalCopies(ServerPlayer player) {
		boolean changed = false;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (!TerminalData.belongsTo(player.getInventory().getItem(slot), player.getUUID())) continue;
			player.getInventory().setItem(slot, ItemStack.EMPTY);
			changed = true;
		}
		if (changed) player.getInventory().setChanged();
	}

	private static boolean containsEntry(String entries, String value) {
		for (String entry : entries.split(";")) {
			if (entry.equals(value)) {
				return true;
			}
		}
		return false;
	}

	private static String appendEntry(String entries, String value) {
		return entries.isBlank() ? value : entries + ";" + value;
	}
}
