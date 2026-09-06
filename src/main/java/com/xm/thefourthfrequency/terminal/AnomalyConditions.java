package com.xm.thefourthfrequency.terminal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/** Cheap, bounded preflight checks. A rejected candidate is never started or logged. */
public final class AnomalyConditions {
	private AnomalyConditions() { }

	public static Prepared prepare(ServerPlayer player, AnomalyDefinition definition, long seed) {
		if (!(player.level() instanceof ServerLevel level)) return null;
		return switch (definition.id()) {
			// A surface to fracture, and nothing else. The digging sounds used to demand a dark,
			// enclosed, cave-like spot of their own, which was right while they were only sounds:
			// footsteps with nothing to find read as ambience above ground. Now they arrive with a
			// crack opening in a specific wall, and a wall is the whole requirement - somebody
			// digging into the floor beside a player standing in the open is not a weaker reading of
			// this anomaly, and stage 1 has three entries to draw from and cannot spare one that is
			// unavailable in daylight.
			case "phantom_echo" -> {
				BlockPos target = surfaceTarget(level, player);
				yield target == null ? null : Prepared.at(level, target);
			}
			case "light_dropout" -> nightLike(level)
					&& AnomalyServerEffects.hasExtinguishableLight(level, player.blockPosition())
					? Prepared.NONE : null;
			// Heads turning in unison needs heads. The effect never refused anything, so a player
			// alone in a tunnel could draw it, spend the whole interval on it and its slot in the
			// recent-three list, and be shown nothing at all - a silent failure that reads from the
			// outside exactly like the director having stopped.
			case "watcher_alignment" -> nearbyMobs(level, player) >= AnomalySelectionRules.ALIGNMENT_MINIMUM_MOBS
					? Prepared.NONE : null;
			// Something to misread. Two slots per visual row are chosen from what is actually there,
			// so an almost-empty inventory yields one eye in a corner of a screen the player has no
			// reason to open.
			case "organ_misread" -> filledInventorySlots(player) >= AnomalySelectionRules.MISREAD_MINIMUM_ITEMS
					? Prepared.NONE : null;
			// A replay of somebody standing still is a copy of a statue. tickCount still guards the
			// client's three-second history buffer being full; the tracker answers the separate
			// question of whether those three seconds contain anything.
			case "action_echo" -> player.tickCount >= 60
					&& PlayerMotionTracker.recentlyActive(player, level.getGameTime())
					? Prepared.NONE : null;
			// The only entry whose preflight is about where the player would be put back rather than
			// about what they would see. Asked here so a refusal costs nothing: a candidate rejected
			// at this stage is never started and never logged, which is what keeps a full layer from
			// burning the player's strong-anomaly cooldown on an event that did not happen.
			case "unrendered_layer" -> com.xm.thefourthfrequency.unrendered.UnrenderedSessionService
					.available(player) ? Prepared.NONE : null;
			default -> Prepared.NONE;
		};
	}

	/**
	 * Lights going out is only a loss while the sun is not there to replace them, so the dropout is a
	 * night event. Dimensions without a day-night cycle never get that replacement in the first place
	 * and are never excluded - the shared overworld clock says nothing about the Nether or the End.
	 */
	public static boolean nightLike(ServerLevel level) {
		return level.dimensionType().hasFixedTime() || AnomalySelectionRules.night(level.getDayTime());
	}

	/** Bounded: one query of the same thirty-block box {@code AlignmentTask} works on each tick. */
	private static int nearbyMobs(ServerLevel level, ServerPlayer player) {
		return level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(30.0D), Mob::isAlive).size();
	}

	/** Hotbar and main inventory only, which is what the misread selection draws from. */
	private static int filledInventorySlots(ServerPlayer player) {
		int slots = Math.min(36, player.getInventory().getContainerSize());
		int filled = 0;
		for (int slot = 0; slot < slots; slot++) {
			if (!player.getInventory().getItem(slot).isEmpty()) filled++;
		}
		return filled;
	}

	public static boolean caveLike(ServerLevel level, BlockPos origin) {
		boolean directSky = level.canSeeSky(origin.above());
		int skyLight = level.getBrightness(LightLayer.SKY, origin);
		int enclosed = 0;
		for (Direction direction : Direction.values()) {
			for (int distance = 1; distance <= 2; distance++) {
				BlockPos pos = origin.relative(direction, distance);
				if (level.hasChunkAt(pos) && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
					enclosed++;
					break;
				}
			}
		}
		return AnomalySelectionRules.caveLike(directSky, skyLight, enclosed);
	}

	/**
	 * The block the crack opens in: a wall in front of the player, or failing that the ground beside
	 * their feet.
	 *
	 * <p>The floor pass is not a lenient second reading of the anomaly - it is the one the comment on
	 * the {@code phantom_echo} branch above already describes, and without it the branch could not
	 * keep the promise it makes. {@code player.blockPosition()} is the block the player's feet are
	 * <em>in</em>, which above ground is air, so every horizontal probe at foot and eye height misses
	 * on flat open terrain and the whole anomaly quietly became unavailable anywhere there was not a
	 * wall. Stage 1 has three entries, {@code light_dropout} is night-only, and losing this one in
	 * daylight left {@code silent_world} carrying the entire early game on its own.
	 *
	 * <p>Ordered strictly after the wall probes, so somewhere with a wall still fractures the wall.
	 * Somebody is digging towards the player either way; the difference is only which side they
	 * come through.
	 */
	public static BlockPos surfaceTarget(ServerLevel level, ServerPlayer player) {
		Direction front = player.getDirection();
		Direction[] directions = { front, front.getCounterClockWise(), front.getClockWise() };
		BlockPos feet = player.blockPosition();
		BlockPos eyes = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
		for (Direction direction : directions) {
			BlockPos footTarget = feet.relative(direction);
			if (collidable(level, footTarget)) return footTarget;
			BlockPos eyeTarget = eyes.relative(direction);
			if (collidable(level, eyeTarget)) return eyeTarget;
		}
		for (Direction direction : directions) {
			BlockPos floorTarget = feet.relative(direction).below();
			if (collidable(level, floorTarget)) return floorTarget;
		}
		return null;
	}

	private static boolean collidable(ServerLevel level, BlockPos pos) {
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return !state.isAir() && !state.getCollisionShape(level, pos).isEmpty();
	}

	public record Prepared(AnomalyRuntimeService.Anchor anchor) {
		public static final Prepared NONE = new Prepared(null);
		private static Prepared at(ServerLevel level, BlockPos pos) {
			return new Prepared(new AnomalyRuntimeService.Anchor(
					level.dimension().identifier().toString(), pos.immutable()));
		}
	}
}
