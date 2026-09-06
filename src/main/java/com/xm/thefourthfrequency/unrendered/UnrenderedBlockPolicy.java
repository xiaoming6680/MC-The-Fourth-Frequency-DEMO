package com.xm.thefourthfrequency.unrendered;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Nothing in the unrendered layer can be broken, placed, filled or set alight.
 *
 * <p>Flatly refused, which is a different rule from the one a pursuit mirror follows. A mirror is a
 * copy of a real world and digging in it is fine - it vanishes with the session and the ledger hands
 * the blocks back. This place is not a copy of anything, and its geometry <em>is</em> the event:
 *
 * <ul>
 * <li>The way out is a hole in the floor, placed once per forty-eight cells squared and found by
 *     walking. A player with a pickaxe makes one wherever they are standing, which does not shorten
 *     the layer so much as delete it.</li>
 * <li>The entity is escaped by reading the floor plan, not by outpacing it. Two blocks placed in a
 *     doorway end the chase permanently, and a tunnel through a wall wins every corner.</li>
 * <li>A hole in the ceiling opens onto nothing at all - a void above a room that is meant to have
 *     no outside - and that answers the one question the place exists to leave open.</li>
 * </ul>
 *
 * <p>The refusal is silent and total: the swing plays, the block does not break, and no message
 * explains it. There is nothing here that says a tool should work.
 */
public final class UnrenderedBlockPolicy {
	private static boolean initialized;

	private UnrenderedBlockPolicy() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PlayerBlockBreakEvents.BEFORE.register(
				(level, player, pos, state, blockEntity) -> !UnrenderedDimensions.isUnrendered(level));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!UnrenderedDimensions.isUnrendered(level)) return InteractionResult.PASS;
			// Only placement is refused. Anything else a right click does here - eating, reading the
			// terminal - is the player using their own belongings, which is not this rule's business.
			return player.getItemInHand(hand).getItem() instanceof BlockItem
					? InteractionResult.FAIL : InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register(UnrenderedBlockPolicy::refuseWorldChangingItems);
	}

	/**
	 * Buckets, flint and steel, and fire charges.
	 *
	 * <p>Not covered by the placement refusal above, because none of them is a {@link BlockItem} -
	 * they change the world through their own use path. Water in particular would be worth
	 * discovering: a source block in a four-block-high corridor is a way down a hole the player
	 * chooses, and lava is a wall the entity cannot path around.
	 */
	private static InteractionResult refuseWorldChangingItems(Player player,
			net.minecraft.world.level.Level level, net.minecraft.world.InteractionHand hand) {
		if (!UnrenderedDimensions.isUnrendered(level)) return InteractionResult.PASS;
		ItemStack held = player.getItemInHand(hand);
		return held.getItem() instanceof BucketItem || held.is(Items.FLINT_AND_STEEL)
				|| held.is(Items.FIRE_CHARGE) ? InteractionResult.FAIL : InteractionResult.PASS;
	}
}
