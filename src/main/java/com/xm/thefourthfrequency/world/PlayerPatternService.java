package com.xm.thefourthfrequency.world;

import com.xm.thefourthfrequency.bootstrap.RuntimeServices;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.state.PlayerPatternState;
import com.xm.thefourthfrequency.state.StoryState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

public final class PlayerPatternService {
	private static boolean initialized;

	private PlayerPatternService() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		ServerTickEvents.END_SERVER_TICK.register(PlayerPatternService::updateServer);
	}

	private static void updateServer(MinecraftServer server) {
		int interval = RuntimeServices.config().pacing().developerAcceleration() ? 20 : 100;
		if (server.getTickCount() % interval != 0) return;
		FrequencyWorldData data = FrequencyWorldData.get(server);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (PrivateDimensions.isPrivate(player.level())) continue;
			CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
			if (record != null && SurvivalMilestone.RETURNED_NETHER.present(
					record.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0))) {
				sample(player, data);
			}
		}
	}

	public static void sample(ServerPlayer player, FrequencyWorldData data) {
		if (PrivateDimensions.isPrivate(player.level())) return;
		CompoundTag before = data.terminalRecord(player.getUUID()).orElse(null);
		if (before == null) return;
		PlayerPatternState pattern = PlayerPatternState.read(before);
		String held = itemId(player.getMainHandItem());
		String oldWeapon = pattern.preferredWeapon();
		int oldSamples = pattern.weaponSamples();
		String weapon = oldWeapon;
		int samples = oldSamples;
		if (!held.isBlank() && !player.getMainHandItem().is(ModItems.OLD_TERMINAL)) {
			if (held.equals(oldWeapon)) samples = Math.min(10_000, oldSamples + 1);
			else if (oldSamples <= 2) {
				weapon = held;
				samples = 1;
			}
		}

		BlockPos current = player.blockPosition();
		BlockPos previous = BlockPos.of(pattern.lastPosition() == 0L ? current.asLong() : pattern.lastPosition());
		int dx = current.getX() - previous.getX();
		int dz = current.getZ() - previous.getZ();
		String axis = pattern.escapeAxis();
		if (Math.abs(dx) + Math.abs(dz) >= 3) {
			axis = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? "east" : "west") : (dz >= 0 ? "south" : "north");
		}
		int foodPhase = pattern.foodUsePhase();
		if (player.isUsingItem() && player.getUseItem().has(DataComponents.FOOD)) {
			foodPhase = Math.floorMod((int) player.level().getGameTime(), 100);
		}
		String armor = armorSignature(player);
		String oldArmor = pattern.armorSignature();
		int armorChanges = pattern.armorChanges();
		if (!oldArmor.isBlank() && !oldArmor.equals(armor)) armorChanges++;
		String role = pattern.inferredRole();
		PlayerPatternState updated = new PlayerPatternState(pattern.mined(), pattern.placed(), pattern.crafted(),
				pattern.deviceInteractions(), weapon, samples, axis, current.asLong(), foodPhase, armor,
				armorChanges, role, pattern.acceptedAdvice());
		if (updated.equals(pattern)) return;
		data.updateTerminalRecord(player.getUUID(), updated::writeTo);
		TerminalLifecycleService.ensureCarried(player, false);
	}

	private static String armorSignature(ServerPlayer player) {
		return itemId(player.getItemBySlot(EquipmentSlot.HEAD)) + ";"
				+ itemId(player.getItemBySlot(EquipmentSlot.CHEST)) + ";"
				+ itemId(player.getItemBySlot(EquipmentSlot.LEGS)) + ";"
				+ itemId(player.getItemBySlot(EquipmentSlot.FEET));
	}

	private static String itemId(ItemStack stack) {
		return stack.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
