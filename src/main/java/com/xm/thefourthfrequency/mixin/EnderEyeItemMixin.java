package com.xm.thefourthfrequency.mixin;

import com.xm.thefourthfrequency.ending.EndBossEncounterService;
import com.xm.thefourthfrequency.ending.StrongholdPortalService;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The twelfth Eye prepares the world interface, and nothing here gates it.
 *
 * <p>There used to be a refusal on the final Eye for a player who had not yet lived through a
 * pursuit. It was a per-player condition on a world-level, party-visible action, which is the worst
 * shape a gate can have in a shared world: one member of a table could place it and another could
 * not, with no way to read why from where either of them was standing. It also bought nothing -
 * the first pursuit opens on being bound plus one completed anomaly plus any activity at all, which
 * every route through the early game satisfies long before twelve Eyes exist.</p>
 */
@Mixin(EnderEyeItem.class)
public abstract class EnderEyeItemMixin {
	/**
	 * Runs after vanilla has actually inserted the eye. This avoids preparing the
	 * End for cancelled or failed interactions and makes the twelfth eye the sole
	 * authoritative trigger.
	 */
	@Inject(method = "useOn", at = @At("RETURN"))
	private void thefourthfrequency$prepareWorldInterfaceAfterFinalEye(UseOnContext context,
			CallbackInfoReturnable<InteractionResult> callback) {
		if (!callback.getReturnValue().consumesAction() || context.getLevel().isClientSide()
				|| !(context.getPlayer() instanceof ServerPlayer player)) return;
		var state = context.getLevel().getBlockState(context.getClickedPos());
		if (!(state.getBlock() instanceof EndPortalFrameBlock)
				|| !state.getValue(EndPortalFrameBlock.HAS_EYE)) return;
		StrongholdPortalService.findPortalRingNear(context.getLevel(), context.getClickedPos(), 4)
				.filter(center -> StrongholdPortalService.eyeCount(context.getLevel(), center) == 12)
				.ifPresent(center -> EndBossEncounterService.prepareFromActivatedPortal(
						player.level(), center, player));
	}

	@Inject(method = "use", at = @At("RETURN"))
	private void thefourthfrequency$recordRealEyeThrow(Level level, Player player, InteractionHand hand,
			CallbackInfoReturnable<InteractionResult> callback) {
		if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer
				&& callback.getReturnValue().consumesAction()) {
			TerminalToolService.recordEyeSample(serverPlayer);
		}
	}
}
