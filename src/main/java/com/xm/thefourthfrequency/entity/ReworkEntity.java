package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.correction.ReworkCollisionProfile;
import com.xm.thefourthfrequency.correction.ReworkFormStage;
import com.xm.thefourthfrequency.pursuit.PursuitFormController;
import com.xm.thefourthfrequency.terminal.AnomalyConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.UUID;

public final class ReworkEntity extends Monster {
	public static final int MIN_FORM_STAGE = ReworkFormStage.MIN_STAGE;
	public static final int MAX_FORM_STAGE = ReworkFormStage.MAX_STAGE;
	public static final int MORPH_DURATION_TICKS = 40;
	public static final int MORPH_SWITCH_TICK = 20;
	private static final int BREACH_STUCK_TICKS = 8;
	private static final int BREACH_RETRY_TICKS = 4;
	private static final int BREACH_PROGRESS_SAMPLE_TICKS = 8;
	private static final double BREACH_MIN_PROGRESS_SQR = 0.18 * 0.18;
	private static final double BREACH_PROBE_REACH = 0.75;
	private static final int PURSUIT_BREACH_STUCK_TICKS = 3;
	private static final int PURSUIT_BREACH_RETRY_TICKS = 1;
	private static final double PURSUIT_BREACH_PROBE_REACH = 1.25;
	// Path speed is the product of these two. 0.31 * 1.32 is tuned against felt pace rather than the
	// raw figure: a mob loses speed to node-to-node steering, corner slowdown and repaths that a
	// player running a straight line does not, so matching a sprint on open ground takes a nominal
	// value somewhat above it. The band is deliberate - at a sprint the gap holds, and sprint-jumping
	// still opens it. The original 0.32 * 1.42 outran every form of player movement, so distance never
	// opened at all and the chase's two escape routes could not be played toward.
	private static final double PURSUIT_MOVEMENT_SPEED = 0.31;
	private static final double PURSUIT_NAVIGATION_SPEED = 1.32;
	private static final double PURSUIT_CAVE_MOVEMENT_SPEED = 0.25;
	private static final double PURSUIT_CAVE_NAVIGATION_SPEED = 1.04;
	private static final double PURSUIT_HIGH_GROUND_GAP = 3.0;
	private static final double PURSUIT_HIGH_GROUND_RANGE_SQR = 100.0;
	private static final int PURSUIT_HIGH_GROUND_WINDUP_TICKS = 12;
	private static final int PURSUIT_TOWER_BREAK_INTERVAL_TICKS = 3;
	private static final int PURSUIT_TOWER_SCAN_DEPTH = 12;
	private static final EntityDataAccessor<Integer> FORM_STAGE = SynchedEntityData.defineId(
			ReworkEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MORPH_TARGET_STAGE = SynchedEntityData.defineId(
			ReworkEntity.class, EntityDataSerializers.INT);
	private static final EntityDataAccessor<Integer> MORPH_TICKS = SynchedEntityData.defineId(
			ReworkEntity.class, EntityDataSerializers.INT);
	private BlockPos obstaclePosition;
	private int obstacleTicks;
	private UUID hostilePlayer;
	private ServerPlayer hostilePlayerEntity;
	private int hostileTicks;
	private Vec3 breachSamplePosition;
	private int breachSampleTicks;
	private int blockedTravelTicks;
	private int breachRetryTicks;
	private boolean pursuitMode;
	private UUID pursuitOwner;
	private String pursuitSessionId = "";
	private boolean pursuitTracking = true;
	private boolean pursuitCaveSlowdown;
	private int pursuitHighGroundTicks;
	private int pursuitTowerBreakCooldown;

	public ReworkEntity(EntityType<? extends ReworkEntity> type, Level level) {
		super(type, level);
		xpReward = 0;
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Monster.createMonsterAttributes()
				.add(Attributes.MAX_HEALTH, 36.0)
				.add(Attributes.MOVEMENT_SPEED, 0.27)
				.add(Attributes.FOLLOW_RANGE, 64.0)
				.add(Attributes.ATTACK_DAMAGE, 5.0)
				.add(Attributes.KNOCKBACK_RESISTANCE, 0.65)
				.add(Attributes.STEP_HEIGHT, 1.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(FORM_STAGE, MIN_FORM_STAGE)
				.define(MORPH_TARGET_STAGE, MIN_FORM_STAGE)
				.define(MORPH_TICKS, 0);
	}

	public int formStage() {
		return entityData.get(FORM_STAGE);
	}

	public int morphTargetStage() {
		return entityData.get(MORPH_TARGET_STAGE);
	}

	public int morphTicks() {
		return entityData.get(MORPH_TICKS);
	}

	public boolean isMorphing() {
		return morphTicks() > 0;
	}

	public void configurePursuit(UUID ownerId, String sessionId, int form) {
		pursuitMode = true;
		pursuitOwner = ownerId;
		pursuitSessionId = sessionId == null ? "" : sessionId;
		pursuitTracking = true;
		int stage = Math.clamp(form, MIN_FORM_STAGE, MAX_FORM_STAGE);
		entityData.set(FORM_STAGE, stage);
		entityData.set(MORPH_TARGET_STAGE, stage);
		entityData.set(MORPH_TICKS, 0);
		var movementSpeed = getAttribute(Attributes.MOVEMENT_SPEED);
		if (movementSpeed != null) movementSpeed.setBaseValue(PURSUIT_MOVEMENT_SPEED);
		refreshDimensions();
	}

	public boolean pursuitMode() {
		return pursuitMode;
	}

	public UUID pursuitOwner() {
		return pursuitOwner;
	}

	public String pursuitSessionId() {
		return pursuitSessionId;
	}

	public void setPursuitTracking(boolean tracking) {
		pursuitTracking = tracking;
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		ReworkCollisionProfile profile = ReworkCollisionProfile.forStage(formStage());
		return EntityDimensions.fixed(profile.width(), profile.height()).withEyeHeight(profile.eyeHeight());
	}

	@Override
	public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (FORM_STAGE.equals(key)) refreshDimensions();
	}

	@Override
	protected void registerGoals() {
		// This body has a purpose-built state machine and never uses predator goals.
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		if (hostileTicks > 0) hostileTicks--;
		openAdjacentDoor(level);
		ServerPlayer hostile = hostileTarget(level);
		if (hostile != null) {
			setTarget(hostile);
			if (pursuitMode) updatePursuitCaveSlowdown(level, hostile);
			boolean pathStarted = getNavigation().moveTo(hostile,
					pursuitMode
							? (pursuitCaveSlowdown ? PURSUIT_CAVE_NAVIGATION_SPEED : PURSUIT_NAVIGATION_SPEED)
							: 1.05);
			tickObstacleBreach(level, hostile.position(), pathStarted);
			if (pursuitMode) tickPursuitHighGroundCounter(level, hostile);
			if (!pursuitMode && distanceToSqr(hostile) <= 3.0 && tickCount % 20 == 0) {
				doHurtTarget(level, hostile);
			}
			return;
		}
		if (pursuitMode) {
			pursuitHighGroundTicks = 0;
			pursuitTowerBreakCooldown = 0;
			setTarget(null);
			getNavigation().stop();
			resetObstacleBreach();
			return;
		}
		setTarget(null);
		getNavigation().stop();
		resetObstacleBreach();
	}

	private void updatePursuitCaveSlowdown(ServerLevel level, ServerPlayer target) {
		boolean caveSlowdown = AnomalyConditions.caveLike(level, target.blockPosition());
		if (caveSlowdown == pursuitCaveSlowdown) return;
		pursuitCaveSlowdown = caveSlowdown;
		var movementSpeed = getAttribute(Attributes.MOVEMENT_SPEED);
		if (movementSpeed != null) {
			movementSpeed.setBaseValue(caveSlowdown ? PURSUIT_CAVE_MOVEMENT_SPEED : PURSUIT_MOVEMENT_SPEED);
		}
	}

	private void tickObstacleBreach(ServerLevel level, Vec3 destination, boolean pathStarted) {
		if (breachRetryTicks > 0) breachRetryTicks--;
		int stuckTicks = pursuitMode ? PURSUIT_BREACH_STUCK_TICKS : BREACH_STUCK_TICKS;
		Vec3 horizontalTarget = destination.subtract(position()).multiply(1.0, 0.0, 1.0);
		if (horizontalTarget.lengthSqr() <= 2.25 || !level.getGameRules().get(GameRules.MOB_GRIEFING)) {
			resetObstacleBreach();
			return;
		}

		Vec3 current = position();
		if (breachSamplePosition == null) {
			breachSamplePosition = current;
			breachSampleTicks = 0;
			blockedTravelTicks = 0;
			return;
		}

		breachSampleTicks++;
		boolean hardBlocked = horizontalCollision || !pathStarted || getNavigation().isDone();
		if (hardBlocked) blockedTravelTicks++;
		if (breachSampleTicks >= BREACH_PROGRESS_SAMPLE_TICKS) {
			double movedX = current.x - breachSamplePosition.x;
			double movedZ = current.z - breachSamplePosition.z;
			if (movedX * movedX + movedZ * movedZ < BREACH_MIN_PROGRESS_SQR) {
				blockedTravelTicks = Math.max(blockedTravelTicks, stuckTicks);
			} else if (!hardBlocked) {
				blockedTravelTicks = 0;
			}
			breachSamplePosition = current;
			breachSampleTicks = 0;
		}

		if (blockedTravelTicks < stuckTicks || breachRetryTicks > 0) return;
		if (tryBreakBlockingObstacle(level, horizontalTarget.normalize())) {
			getNavigation().stop();
			blockedTravelTicks = 0;
		}
		breachRetryTicks = pursuitMode ? PURSUIT_BREACH_RETRY_TICKS : BREACH_RETRY_TICKS;
	}

	private boolean tryBreakBlockingObstacle(ServerLevel level, Vec3 direction) {
		double probeReach = pursuitMode ? PURSUIT_BREACH_PROBE_REACH : BREACH_PROBE_REACH;
		AABB body = getBoundingBox();
		AABB probe = body.expandTowards(direction.x * probeReach, 0.0,
				direction.z * probeReach).inflate(0.04, 0.0, 0.04);
		BlockPos minimum = BlockPos.containing(probe.minX + 1.0e-5, probe.minY + 1.0e-5,
				probe.minZ + 1.0e-5);
		BlockPos maximum = BlockPos.containing(probe.maxX - 1.0e-5, probe.maxY - 1.0e-5,
				probe.maxZ - 1.0e-5);
		Vec3 focus = position().add(direction.scale(probeReach * 0.6))
				.add(0.0, getBbHeight() * 0.58, 0.0);
		CollisionContext collisionContext = CollisionContext.of(this);
		BlockPos selected = null;
		double selectedDistance = Double.MAX_VALUE;

		for (BlockPos mutable : BlockPos.betweenClosed(minimum, maximum)) {
			BlockPos candidate = mutable.immutable();
			if (!canBreakObstacle(level, candidate)) continue;
			BlockState state = level.getBlockState(candidate);
			VoxelShape collision = state.getCollisionShape(level, candidate, collisionContext);
			if (collision.isEmpty() || !collision.bounds().move(candidate).intersects(probe)) continue;
			double distance = candidate.getCenter().distanceToSqr(focus);
			if (distance < selectedDistance) {
				selected = candidate;
				selectedDistance = distance;
			}
		}

		return selected != null && level.destroyBlock(selected, false, this, 512);
	}

	private void tickPursuitHighGroundCounter(ServerLevel level, ServerPlayer target) {
		if (pursuitTowerBreakCooldown > 0) pursuitTowerBreakCooldown--;
		double deltaX = target.getX() - getX();
		double deltaZ = target.getZ() - getZ();
		double horizontalDistanceSqr = deltaX * deltaX + deltaZ * deltaZ;
		double verticalGap = target.getY() - getY();
		if (verticalGap < PURSUIT_HIGH_GROUND_GAP
				|| horizontalDistanceSqr > PURSUIT_HIGH_GROUND_RANGE_SQR) {
			pursuitHighGroundTicks = 0;
			return;
		}
		pursuitHighGroundTicks++;
		getLookControl().setLookAt(target, 55.0F, 55.0F);
		if (onGround() && pursuitHighGroundTicks % 10 == 0) {
			Vec3 horizontal = new Vec3(deltaX, 0.0D, deltaZ);
			if (horizontal.lengthSqr() > 1.0e-4D) horizontal = horizontal.normalize().scale(0.36D);
			double launch = Math.clamp(0.66D + verticalGap * 0.065D, 0.76D, 1.18D);
			setDeltaMovement(horizontal.x, launch, horizontal.z);
			resetFallDistance();
		}
		if (pursuitHighGroundTicks < PURSUIT_HIGH_GROUND_WINDUP_TICKS
				|| pursuitTowerBreakCooldown > 0
				|| !level.getGameRules().get(GameRules.MOB_GRIEFING)) return;
		BlockPos belowTarget = target.blockPosition().below();
		for (int depth = 0; depth < PURSUIT_TOWER_SCAN_DEPTH; depth++) {
			BlockPos candidate = belowTarget.below(depth);
			if (level.getBlockState(candidate).isAir()) continue;
			if (!canBreakObstacle(level, candidate)) continue;
			if (level.destroyBlock(candidate, false, this, 512)) {
				getNavigation().stop();
				pursuitTowerBreakCooldown = PURSUIT_TOWER_BREAK_INTERVAL_TICKS;
			}
			return;
		}
	}

	private boolean canBreakObstacle(ServerLevel level, BlockPos position) {
		if (!level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) return false;
		BlockState state = level.getBlockState(position);
		return !state.isAir()
				&& !(state.getBlock() instanceof DoorBlock)
				&& !state.hasBlockEntity()
				&& level.getBlockEntity(position) == null
				&& !state.is(BlockTags.WITHER_IMMUNE)
				&& state.getDestroySpeed(level, position) >= 0.0F;
	}

	private void resetObstacleBreach() {
		breachSamplePosition = null;
		breachSampleTicks = 0;
		blockedTravelTicks = 0;
		breachRetryTicks = 0;
	}

	private void openAdjacentDoor(ServerLevel level) {
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos position = blockPosition().relative(direction);
			BlockState state = level.getBlockState(position);
			if (state.getBlock() instanceof DoorBlock door && !door.isOpen(state)) {
				door.setOpen(this, level, state, position, true);
				return;
			}
		}
	}

	private ServerPlayer hostileTarget(ServerLevel level) {
		if (pursuitMode) {
			if (!pursuitTracking || pursuitOwner == null) return null;
			return level.players().stream()
					.filter(player -> pursuitOwner.equals(player.getUUID()) && player.isAlive())
					.findFirst().orElse(null);
		}
		if (hostilePlayerEntity != null && hostileTicks > 0 && hostilePlayerEntity.level() == level
				&& hostilePlayerEntity.isAlive() && !hostilePlayerEntity.isRemoved()) return hostilePlayerEntity;
		if (hostilePlayer != null && hostileTicks > 0) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(hostilePlayer);
			if (player != null && player.level() == level && player.isAlive()) return player;
		}
		return null;
	}

	private void becomeHostile(ServerPlayer player) {
		hostilePlayer = player.getUUID();
		hostilePlayerEntity = player;
		hostileTicks = 20 * 20;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (source.getEntity() instanceof ServerPlayer player) becomeHostile(player);
		return super.hurtServer(level, source, amount);
	}

	@Override protected net.minecraft.sounds.SoundEvent getHurtSound(DamageSource source) {
		return com.xm.thefourthfrequency.audio.ModSounds.REWORK_HURT;
	}
	@Override protected net.minecraft.sounds.SoundEvent getDeathSound() {
		return com.xm.thefourthfrequency.audio.ModSounds.REWORK_DEATH;
	}
	@Override protected float getSoundVolume() {
		return (float) Math.clamp(com.xm.thefourthfrequency.bootstrap.RuntimeServices.config().meta().peakVolume() * .62, 0, 1);
	}
	@Override protected void playStepSound(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		// The client emits contacts on the renderer's walk clock, once per planted foot.
	}

	@Override
	public void die(DamageSource source) {
		if (pursuitMode) {
			PursuitFormController.recordPursuitDefeat(this, source);
		}
		super.die(source);
	}

	@Override
	public boolean removeWhenFarAway(double distanceToClosestPlayer) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putInt("form_stage", formStage());
		output.putInt("rework_form_schema", ReworkFormStage.SCHEMA);
		output.putInt("morph_target_stage", morphTargetStage());
		output.putInt("morph_ticks", morphTicks());
		output.putBoolean("pursuit_mode", pursuitMode);
		output.putString("pursuit_owner", pursuitOwner == null ? "" : pursuitOwner.toString());
		output.putString("pursuit_session", pursuitSessionId);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		int savedStage = ReworkFormStage.readStage(input.getIntOr("form_stage", MIN_FORM_STAGE),
				input.getIntOr("rework_form_schema", 1));
		entityData.set(FORM_STAGE, savedStage);
		entityData.set(MORPH_TARGET_STAGE, savedStage);
		// Reloads retain the saved pursuit form and never replay a retired construction morph.
		entityData.set(MORPH_TICKS, 0);
		refreshDimensions();
		pursuitMode = input.getBooleanOr("pursuit_mode", false);
		pursuitSessionId = input.getStringOr("pursuit_session", "");
		try {
			pursuitOwner = UUID.fromString(input.getStringOr("pursuit_owner", ""));
		} catch (IllegalArgumentException exception) {
			pursuitOwner = null;
		}
	}

}
