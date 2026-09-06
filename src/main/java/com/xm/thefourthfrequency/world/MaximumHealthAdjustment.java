package com.xm.thefourthfrequency.world;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * The one place this mod moves a player's maximum health, and the bounds it may never cross.
 *
 * <p>Two events pay in hearts now - surviving a pursuit and getting out of the unrendered layer -
 * and both use {@code PursuitProgressPolicy.resolutionMaxHealthDelta} to decide how much. This holds
 * the other half: how a delta is actually applied, and what it is clamped to. Written once because
 * the clamp is the part that matters and the part that is easy to get subtly different: a second
 * copy with a floor of one heart instead of six would be invisible in review and would grind the
 * players who are already losing.
 *
 * <p>Sets the base value rather than stacking attribute modifiers. Modifiers would be tidier, but
 * the base value is what vanilla persists with the player without any help, and a bonus that
 * survives a relog is the difference between a reward and a decoration.
 */
public final class MaximumHealthAdjustment {
	/**
	 * Floor and ceiling on a player's maximum health, whatever it is being paid for.
	 *
	 * <p>One heart is the floor because zero is a death that nothing in the mod chose, and twenty
	 * hearts of headroom above vanilla is the ceiling because a run that has been repeatedly
	 * rewarded should still be able to die.
	 */
	public static final double MIN_PLAYER_MAX_HEALTH = 2.0D;
	public static final double MAX_PLAYER_MAX_HEALTH = 40.0D;

	private MaximumHealthAdjustment() {
	}

	/**
	 * Moves the player's maximum health by {@code delta}, clamped, and trims current health to fit.
	 *
	 * <p>The trim is not optional. Lowering the maximum below the current value leaves a player
	 * standing at more health than they can have, which vanilla resolves at some later arbitrary
	 * moment - so the loss would land on a frame that has nothing to do with what caused it.
	 */
	public static void apply(ServerPlayer player, double delta) {
		if (player == null || delta == 0.0D) return;
		var maximumHealth = player.getAttribute(Attributes.MAX_HEALTH);
		if (maximumHealth == null) return;
		double adjusted = Math.clamp(maximumHealth.getBaseValue() + delta,
				MIN_PLAYER_MAX_HEALTH, MAX_PLAYER_MAX_HEALTH);
		maximumHealth.setBaseValue(adjusted);
		if (player.getHealth() > adjusted) player.setHealth((float) adjusted);
	}

	/** Current maximum health base, or the ceiling when the attribute is somehow absent. */
	public static double currentBase(ServerPlayer player) {
		var maximumHealth = player == null ? null : player.getAttribute(Attributes.MAX_HEALTH);
		return maximumHealth == null ? MAX_PLAYER_MAX_HEALTH : maximumHealth.getBaseValue();
	}
}
