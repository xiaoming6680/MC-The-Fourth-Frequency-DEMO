package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.correction.EmptySegmentService;
import com.xm.thefourthfrequency.ending.FinaleRuntimePolicy;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

public final class PursuitSafetyPolicy {
	private PursuitSafetyPolicy() {
	}

	public static boolean canBegin(ServerPlayer player, CompoundTag record, FrequencyWorldData data) {
		if (!hardInvariantsMet(player, record)) return false;
		float safeHealthFloor = Math.max(6.0F, player.getMaxHealth() * 0.4F);
		if (player.isSleeping() || player.getAbilities().flying || player.isFallFlying()
				|| player.isPassenger() || player.isOnFire() || player.isInLava()
				|| player.isUnderWater() || player.getAirSupply() < player.getMaxAirSupply()
				|| player.containerMenu != player.inventoryMenu
				|| player.fallDistance > 3.0F || player.getHealth() <= safeHealthFloor) return false;
		if (!player.level().getEntitiesOfClass(Monster.class, player.getBoundingBox().inflate(12.0D),
				monster -> monster.isAlive() && monster.getTarget() == player).isEmpty()) return false;
		return FinaleRuntimePolicy.ambientPressureAllowed(data, player)
				&& !TerminalRuntimeService.isOpen(player)
				&& AnomalyRuntimeService.active(player) == null;
	}

	/**
	 * The gate the {@code pursuit_test} debug button uses.
	 *
	 * <p>Everything {@link #canBegin} adds on top of this is about not ambushing a player who is
	 * busy, hurt, or mid-fall - protection an unannounced chase needs and a deliberately requested
	 * test does not. Those conditions were rejecting the button constantly during testing, most
	 * obviously creative flight, which is how anyone testing a chase gets to the place they want to
	 * test it. What stays enforced is the set that would actually break the session: a chase cannot
	 * start from a mirror, from a dimension with no mirror family, from the End, or on top of one
	 * that is already running.</p>
	 */
	public static boolean canBeginForDebug(ServerPlayer player, CompoundTag record) {
		return hardInvariantsMet(player, record);
	}

	private static boolean hardInvariantsMet(ServerPlayer player, CompoundTag record) {
		if (!player.isAlive() || player.isSpectator()) return false;
		// The finale is the end of the mainline, so nothing may chase anyone after it. Enforced here
		// rather than in canBegin so the debug button cannot conjure an epilogue chase either.
		if (FinaleRuntimePolicy.concluded(FrequencyWorldData.get(player.level().getServer()))) return false;
		if (PursuitDimensions.isMirror(player.level())
				|| PursuitDimensions.sourceFamily(player.level().dimension()).isEmpty()) return false;
		// The End mirror is registered for recovery symmetry; dragon/finale pursuit remains disabled in v1.
		if (player.level().dimension().equals(Level.END)) return false;
		return !EmptySegmentService.isActive(player)
				&& !record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false)
				&& !record.getBooleanOr(TerminalData.EMPTY_SEGMENT_ACTIVE, false);
	}
}
