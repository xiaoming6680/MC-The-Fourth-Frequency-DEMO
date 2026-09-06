package com.xm.thefourthfrequency.pursuit;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.EndGatewayBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Mirror-only interaction rules: no extraction, no machinery, temporary simple placement. */
public final class PursuitBlockPolicy {
	private static final Map<MinecraftServer, List<PendingPlacement>> PENDING = new IdentityHashMap<>();
	private static boolean initialized;

	private PursuitBlockPolicy() {
	}

	public static void initialize() {
		if (initialized) return;
		initialized = true;
		PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
			if (!(player instanceof ServerPlayer) || !PursuitDimensions.isMirror(level)) return true;
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
					| Block.UPDATE_SUPPRESS_DROPS);
			return false;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)
					|| !PursuitDimensions.isMirror(level)) return InteractionResult.PASS;
			if (!activeSession(serverPlayer)) return InteractionResult.FAIL;
			if (level.getBlockEntity(hit.getBlockPos()) != null) return InteractionResult.FAIL;
			ItemStack held = player.getItemInHand(hand);
			if (!(held.getItem() instanceof BlockItem blockItem)) return InteractionResult.PASS;
			if (!simplePlacement(blockItem.getBlock())) return InteractionResult.FAIL;
			BlockPos hitPos = hit.getBlockPos().immutable();
			BlockPos adjacent = hitPos.relative(hit.getDirection()).immutable();
			PENDING.computeIfAbsent(serverPlayer.level().getServer(), ignored -> new ArrayList<>())
					.add(new PendingPlacement(serverPlayer, blockItem.getBlock(), held.copyWithCount(1),
							hitPos, adjacent, level.getBlockState(hitPos), level.getBlockState(adjacent)));
			return InteractionResult.PASS;
		});
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (!PursuitDimensions.isMirror(level)) return InteractionResult.PASS;
			ItemStack held = player.getItemInHand(hand);
			return held.getItem() instanceof BucketItem || held.is(Items.FLINT_AND_STEEL)
					|| held.is(Items.FIRE_CHARGE) ? InteractionResult.FAIL : InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(PursuitBlockPolicy::verifyPlacements);
		ServerLifecycleEvents.SERVER_STOPPED.register(PENDING::remove);
	}

	public static BlockState sanitizeSnapshotState(ServerLevel source, BlockPos position, BlockState state) {
		if (source.getBlockEntity(position) != null || state.hasBlockEntity()) {
			return state.getCollisionShape(source, position).isEmpty()
					? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
		}
		Block block = state.getBlock();
		if (!safeSnapshotBlock(block)) {
			return state.getCollisionShape(source, position).isEmpty()
					? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
		}
		return state;
	}

	public static boolean simplePlacement(Block block) {
		// The respawn anchor is covered by safeSnapshotBlock now, which is also what keeps one out of
		// the copied world rather than only out of the player's hands.
		return safeSnapshotBlock(block) && !block.defaultBlockState().hasBlockEntity();
	}

	/**
	 * Whether a block may exist in the mirror as itself.
	 *
	 * <p><b>Asked of the block's type, not of its name.</b> This used to match substrings of the
	 * registry path - {@code "redstone"}, {@code "rail"}, {@code "button"}, {@code "portal"} - which
	 * is wrong in both directions and silently so. It rejected anything a mod happened to call a
	 * railing and it accepted {@code target}, {@code lightning_rod}, {@code note_block} and
	 * {@code copper_bulb}, all of which are redstone hardware whose names contain none of those
	 * words. It also accepted {@code respawn_anchor}, which is the one that mattered: the anchor was
	 * copied into the Nether mirror as itself, has no block entity, and nothing in the interaction
	 * rules stops an empty hand from using it - so a player could set their spawn point <em>inside a
	 * private mirror dimension</em> and be respawned there after the session that owns it is gone.
	 *
	 * <p>Class tests answer the question the names were standing in for, and they answer it for
	 * modded subclasses too. {@code isSignalSource} is the backstop underneath them: anything that
	 * can drive a redstone signal is refused whatever it is called. Deliberately <em>not</em>
	 * {@code hasAnalogOutputSignal}, which would also take cauldrons and composters - those are part
	 * of what a player's base looks like, and the mirror is supposed to look like their base.
	 */
	private static boolean safeSnapshotBlock(Block block) {
		// Ways out of the world, and things that rewrite it.
		if (block instanceof Portal || block instanceof EndGatewayBlock
				|| block instanceof EndPortalFrameBlock || block instanceof BaseFireBlock
				|| block instanceof TntBlock || block instanceof RespawnAnchorBlock
				|| block instanceof BedBlock) return false;
		// Anything that moves blocks.
		if (block instanceof PistonBaseBlock || block instanceof PistonHeadBlock
				|| block instanceof MovingPistonBlock) return false;
		// Redstone: sources, conductors, sensors and loads.
		if (block instanceof RedStoneWireBlock || block instanceof DiodeBlock
				|| block instanceof ButtonBlock || block instanceof LeverBlock
				|| block instanceof BasePressurePlateBlock || block instanceof ObserverBlock
				|| block instanceof BaseRailBlock || block instanceof TripWireBlock
				|| block instanceof TripWireHookBlock || block instanceof SculkSensorBlock
				|| block instanceof RedstoneTorchBlock || block instanceof RedstoneLampBlock
				|| block instanceof RedStoneOreBlock || block instanceof CopperBulbBlock
				|| block instanceof TargetBlock || block instanceof LightningRodBlock
				|| block instanceof NoteBlock) return false;
		return !block.defaultBlockState().isSignalSource();
	}

	private static boolean activeSession(ServerPlayer player) {
		return FrequencyWorldData.get(player.level().getServer()).terminalRecord(player.getUUID())
				.map(record -> record.getBooleanOr(TerminalData.PURSUIT_ACTIVE, false))
				.orElse(false);
	}

	private static void verifyPlacements(MinecraftServer server) {
		List<PendingPlacement> pending = PENDING.remove(server);
		if (pending == null) return;
		for (PendingPlacement placement : pending) {
			if (!placement.player.isAlive() || !PursuitDimensions.isMirror(placement.player.level())
					|| !activeSession(placement.player)) continue;
			BlockState hitNow = placement.player.level().getBlockState(placement.hitPos);
			BlockState adjacentNow = placement.player.level().getBlockState(placement.adjacentPos);
			boolean hitChanged = !hitNow.equals(placement.hitBefore) && hitNow.is(placement.block);
			boolean adjacentChanged = !adjacentNow.equals(placement.adjacentBefore)
					&& adjacentNow.is(placement.block);
			if (hitChanged || adjacentChanged) {
				PursuitRecoveryLedger.recordPlacement(placement.player, placement.refund);
			}
		}
	}

	private record PendingPlacement(ServerPlayer player, Block block, ItemStack refund,
			BlockPos hitPos, BlockPos adjacentPos, BlockState hitBefore, BlockState adjacentBefore) {
	}
}
