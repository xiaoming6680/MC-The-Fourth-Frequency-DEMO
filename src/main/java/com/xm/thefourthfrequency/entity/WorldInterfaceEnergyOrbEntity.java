package com.xm.thefourthfrequency.entity;

import com.xm.thefourthfrequency.audio.AudioService;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.ending.EndBossArenaService;
import com.xm.thefourthfrequency.ending.WorldInterfaceBlastService;
import com.xm.thefourthfrequency.ending.WorldInterfaceDamageService;
import com.xm.thefourthfrequency.ending.WorldInterfaceVfx;
import com.xm.thefourthfrequency.networking.WorldInterfaceProtocol;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The interface's breath weapon: a fast, flat bolt rather than the slow swelling ball it replaced.
 *
 * <p>The old orb grew for three seconds while drifting toward one player, which made it a timer to
 * walk away from rather than a shot to dodge. This is aimed once at spawn and then travels in a
 * straight line at fireball speed, so reading the launch is the defence. Where it lands it leaves a
 * dragon-breath pool and scars the floor - small edits, paced through the arena's own scar queue,
 * so a fight full of bolts still cannot spend the encounter's permanent-edit budget.</p>
 */
public final class WorldInterfaceEnergyOrbEntity extends Entity implements ItemSupplier {
	public static final Identifier TYPE_ID = Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "world_interface_energy_orb");
	/**
	 * Blocks per tick.
	 *
	 * <p>Was fireball-fast, on the reasoning that the launch rather than the approach should be the
	 * readable moment. It still is - the core gathers for two seconds before anything leaves it -
	 * but at nearly two blocks a tick the flight itself crossed the gap in well under a second,
	 * which left the telegraph carrying the entire dodge and the bolt contributing nothing a player
	 * could react to. Halved, the approach is legible too without the shot becoming something you
	 * can stroll away from.</p>
	 */
	public static final double SPEED = 0.95D;
	/**
	 * Doubled alongside the halved speed so the weapon keeps its hundred-and-fourteen block reach.
	 * This is a ceiling, not a duration: {@code tickOrb} ends the action the moment the bolt lands.
	 */
	public static final int MAX_FLIGHT_TICKS = 120;
	public static final float MIN_SCALE = 1.0F;
	public static final float MAX_SCALE = 2.2F;
	public static final double IMPACT_RADIUS = 4.0D;
	/**
	 * Lowered from twelve. The bolt is the encounter's most frequent attack and the widest, and at
	 * the top of the form curve it was taking most of an unarmoured player's bar in one landing -
	 * which turns a telegraphed area weapon into something that has to be avoided perfectly rather
	 * than read and moved away from. The blast keeps its reach and its new weight on the camera; what
	 * it stops doing is deciding the fight on a single connection.
	 */
	public static final float IMPACT_DAMAGE = 9.0F;
	/**
	 * Damage multiplier per form.
	 *
	 * <p>Spelled out rather than derived from the power level, because the top of the curve is the
	 * only part that needed moving and a linear step drags the middle with it. The bolt does not go
	 * through {@code WorldInterfaceAttackService.formDamage}, so it kept its own steeper escalation
	 * when the shared one was brought down, and at the third form it was landing harder than the sky
	 * lance while covering nearly seven blocks of ground. A wide blast with a two-second tell should
	 * not out-hit the single-target shot that has to be aimed.</p>
	 */
	private static final float[] IMPACT_DAMAGE_BY_FORM = {1.0F, 1.4F, 1.55F};
	/** Deliberately shallow: the pool and the crater are the point, not excavating the arena. */
	public static final int BREATH_SCAR_RADIUS = 3;
	public static final int BREATH_SCAR_EDITS = 14;
	public static final int BREATH_CLOUD_TICKS = 220;
	/** Dragon breath is a powered particle now, so the option carries the drift rather than the type. */
	private static final PowerParticleOption BREATH_PARTICLE =
			PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 0.6F);

	private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(
			WorldInterfaceEnergyOrbEntity.class, EntityDataSerializers.FLOAT);

	private UUID encounterId;
	private UUID ownerId;
	private UUID targetId;
	/**
	 * The form that fired this, 0 to 2. Server-only and deliberately unsaved: a bolt reloaded from
	 * disk is discarded on its first tick, so it never gets far enough to need its own power back.
	 */
	private int power;
	private int ageTicks;
	private boolean loadedFromDisk;

	public WorldInterfaceEnergyOrbEntity(EntityType<? extends WorldInterfaceEnergyOrbEntity> type, Level level) {
		super(type, level);
		noPhysics = true;
		setNoGravity(true);
	}

	/** Resolves the separately registered type without coupling this class to ModEntities initialization order. */
	public static WorldInterfaceEnergyOrbEntity create(ServerLevel level, WorldInterfaceEntity owner,
			UUID encounterId, ServerPlayer target) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(TYPE_ID);
		if (type == null) throw new IllegalStateException("World-interface energy orb entity type is not registered");
		Entity created = type.create(level, EntitySpawnReason.EVENT);
		if (!(created instanceof WorldInterfaceEnergyOrbEntity orb)) {
			throw new IllegalStateException("Registered energy orb type has the wrong entity factory");
		}
		orb.bind(encounterId, owner.getUUID(), target.getUUID());
		orb.power = Math.clamp(owner.form(), 0, WorldInterfaceAnatomy.FORM_COUNT - 1);
		// Spawn and launch flash follow the same mouth socket as the charged laser.
		Vec3 core = WorldInterfaceAnatomy.mouthOrigin(owner, 0);
		orb.setPos(core.x, core.y, core.z);
		// Aimed once, at the launch. Everything after this is the player's to read and step out of.
		orb.setDeltaMovement(target.getEyePosition().subtract(core).normalize().scale(SPEED));
		orb.entityData.set(SCALE, MAX_SCALE);
		if (!level.addFreshEntity(orb)) throw new IllegalStateException("Unable to spawn energy orb");
		level.sendParticles(ParticleTypes.EXPLOSION, core.x, core.y, core.z, 6,
				1.1D, 1.1D, 1.1D, 0.05D);
		level.sendParticles(BREATH_PARTICLE, core.x, core.y, core.z,
				40, 0.9D, 0.9D, 0.9D, 0.22D);
		return orb;
	}

	public void bind(UUID encounterId, UUID ownerId, UUID targetId) {
		this.encounterId = java.util.Objects.requireNonNull(encounterId, "encounterId");
		this.ownerId = java.util.Objects.requireNonNull(ownerId, "ownerId");
		this.targetId = java.util.Objects.requireNonNull(targetId, "targetId");
	}

	public UUID encounterId() {
		return encounterId;
	}

	public UUID ownerId() {
		return ownerId;
	}

	public UUID targetId() {
		return targetId;
	}

	public float orbScale() {
		return entityData.get(SCALE);
	}

	/**
	 * The sprite the orb is drawn as. Only the texture - nothing about the projectile's behaviour,
	 * damage or trail is taken from the item.
	 *
	 * <p>The dragon-breath bottle was the wrong read at speed: a glass flask with a stopper, drawn
	 * at up to six blocks across and spinning, which at a glance is an item that got thrown rather
	 * than a shot that was fired. The fire charge is the sprite vanilla already uses for a blaze's
	 * projectile, so it is the shape players have been taught means incoming.</p>
	 */
	@Override
	public ItemStack getItem() {
		return Items.FIRE_CHARGE.getDefaultInstance();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(SCALE, MAX_SCALE);
	}

	/** Samples in the wake wound behind the bolt each tick. */
	private static final int TRAIL_SAMPLES = 10;

	@Override
	public void tick() {
		super.tick();
		if (level().isClientSide()) return;
		ServerLevel level = (ServerLevel) level();
		if (loadedFromDisk) {
			discard();
			return;
		}
		ageTicks++;
		Vec3 from = position();
		Vec3 to = from.add(getDeltaMovement());

		// At nearly two blocks a tick the bolt would tunnel through both terrain and players if the
		// step were only tested at its endpoints, so the whole step is swept.
		BlockHitResult block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, this));
		Vec3 stop = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
		Entity struck = sweptEntity(level, from, stop);
		if (struck != null) {
			detonate(level, struck.position(), true);
			return;
		}
		if (block.getType() != HitResult.Type.MISS) {
			detonate(level, stop, true);
			return;
		}

		setPos(to.x, to.y, to.z);
		level.sendParticles(WorldInterfaceVfx.core(), getX(), getY(), getZ(),
				3, 0.16D, 0.16D, 0.16D, 0.01D);
		// The bolt crosses most of the arena in a couple of seconds, so what a player has to read is
		// not the ball - it is the line the ball is on. The wake is wound around the step it just
		// took and a collar is left standing across it: a helix has a direction and a ring across
		// the flight says where it has reached, and together they are the whole warning.
		double scale = orbScale();
		WorldInterfaceVfx.helix(level, WorldInterfaceVfx.core(), from, to,
				scale * 0.55D, TRAIL_SAMPLES, 2, 1.6D, ageTicks * 0.55D);
		WorldInterfaceVfx.orientedRing(level, WorldInterfaceVfx.violet(), position(),
				getDeltaMovement(), scale * 0.85D, 14, ageTicks * 0.35D, 0.0D);
		if (ageTicks % 2 == 0) {
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.violet(), position(), scale * 0.45D,
					26, 0.08D);
		}
		if (ageTicks >= MAX_FLIGHT_TICKS) detonate(level, position(), true);
	}

	/** First player the step passes close enough to; the bolt is wide, so the tolerance is wide. */
	private Entity sweptEntity(ServerLevel level, Vec3 from, Vec3 to) {
		AABB sweep = new AABB(from, to).inflate(orbScale() * 0.5D + 0.4D);
		Entity nearest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer candidate : level.getEntitiesOfClass(ServerPlayer.class, sweep,
				player -> player.isAlive() && !player.isSpectator())) {
			double distance = candidate.position().distanceToSqr(from);
			if (distance < best) {
				best = distance;
				nearest = candidate;
			}
		}
		return nearest;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		if (amount <= 0.0F || !isPlayerSource(source)) return false;
		// Shot down in flight: the bolt is spent where it was hit and leaves nothing behind.
		detonate(level, position(), false);
		return true;
	}

	public void detonate(ServerLevel level, Vec3 impact, boolean damaging) {
		if (isRemoved()) return;
		double radius = impactRadius();
		level.sendParticles(BREATH_PARTICLE, impact.x, impact.y, impact.z, 120,
				radius * 0.3D, 0.4D, radius * 0.3D, 0.14D);
		// The bolt coming apart, as a surface rather than as a cloud - one shell for the skin and,
		// when it actually went off, three fronts leaving the ground at three speeds.
		if (damaging) {
			WorldInterfaceVfx.detonation(level, impact, radius, 3);
		} else {
			// Shot down in flight: it comes apart, it does not go off - so the shell without any of
			// the front that follows one.
			WorldInterfaceVfx.shell(level, WorldInterfaceVfx.core(), impact, radius * 0.5D, 140, 0.8D);
		}
		if (!damaging) {
			// Shot down in flight: it comes apart, it does not go off.
			level.sendParticles(ParticleTypes.EXPLOSION, impact.x, impact.y, impact.z, 8,
					1.4D, 1.4D, 1.4D, 0.08D);
			discard();
			return;
		}
		blastPresentation(level, impact, radius);
		Entity owner = ownerId == null ? null : level.getEntity(ownerId);
		// The encounter's own type rather than vanilla magic: everything the interface throws has to
		// land for the same figure on every world difficulty, or the finale is unloseable on Peaceful.
		DamageSource source = WorldInterfaceDamageService.source(level,
				owner instanceof WorldInterfaceEntity boss ? boss : this);
		float damage = IMPACT_DAMAGE
				* IMPACT_DAMAGE_BY_FORM[Math.clamp(power, 0, IMPACT_DAMAGE_BY_FORM.length - 1)];
		for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
				new AABB(impact, impact).inflate(radius),
				candidate -> candidate.isAlive() && !candidate.isSpectator())) {
			if (player.position().distanceToSqr(impact) > radius * radius) continue;
			WorldInterfaceDamageService.apply(level, source, player, damage);
			// Thrown, not just burned. A blast that leaves everyone standing exactly where they
			// were reads as a damage tick with particles on it; being knocked off your feet is
			// what makes it a detonation, and it is also what makes it dangerous near an edge.
			//
			// Sized against TNT rather than against a nudge: a player's horizontal motion decays by
			// about nine percent a tick, so the old 1.35 was worth fifteen blocks of travel from a
			// blast the fiction calls a dragon's breath landing at your feet.
			Vec3 away = player.position().subtract(impact);
			double flat = Math.sqrt(away.x * away.x + away.z * away.z);
			double falloff = 1.0D - Math.min(1.0D, Math.sqrt(player.position().distanceToSqr(impact))
					/ radius) * 0.55D;
			double kick = (1.9D + power * 0.45D) * falloff;
			if (flat < 1.0E-4D) {
				player.push(0.0D, kick * 0.85D, 0.0D);
			} else {
				player.push(away.x / flat * kick, (0.95D + power * 0.15D) * falloff,
						away.z / flat * kick);
			}
			player.hurtMarked = true;
		}
		spawnBreathPool(level, impact, owner instanceof LivingEntity living ? living : null, radius);
		EndBossArenaService.queueExplosionScar(level, BlockPos.containing(impact),
				BREATH_SCAR_RADIUS + power, BREATH_SCAR_EDITS + power * 10,
				getUUID().getLeastSignificantBits() ^ BlockPos.containing(impact).asLong());
		discard();
	}

	/**
	 * What landing looks and sounds like: vanilla's own detonation, because that is the vocabulary
	 * every player already reads as "something just blew a hole in the floor".
	 *
	 * <p>The terrain damage itself still goes through the arena's scar queue rather than through a
	 * real {@code Level#explode}. The queue is what keeps a fight full of bolts inside the
	 * encounter's permanent-edit budget and away from the altar, the anchors and bedrock; a genuine
	 * explosion here would answer to none of that and would also happily damage the interface that
	 * fired it. So the blast is vanilla in everything a player can perceive and bounded in what it
	 * is allowed to destroy.</p>
	 */
	private void blastPresentation(ServerLevel level, Vec3 impact, double radius) {
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 0.4D, impact.z,
				1, 0.0D, 0.0D, 0.0D, 0.0D);
		int rings = 2 + power;
		for (int ring = 1; ring <= rings; ring++) {
			double ringRadius = radius * 0.75D * ring;
			int samples = 6 + ring * 4;
			for (int index = 0; index < samples; index++) {
				double angle = Math.PI * 2.0D * index / samples + ring * 0.4D;
				double x = impact.x + Math.cos(angle) * ringRadius;
				double z = impact.z + Math.sin(angle) * ringRadius;
				level.sendParticles(ParticleTypes.EXPLOSION, x, impact.y + 0.4D, z,
						2, 0.45D, 0.3D, 0.45D, 0.02D);
				// Velocity pointed outward, so the front keeps expanding after it is drawn.
				level.sendParticles(ParticleTypes.LARGE_SMOKE, x, impact.y + 0.5D, z,
						0, Math.cos(angle), 0.16D, Math.sin(angle), 0.3D + ring * 0.06D);
			}
		}
		AudioService.playWithReach(level, BlockPos.containing(impact), SoundEvents.GENERIC_EXPLODE.value(),
				SoundSource.HOSTILE, 1.0F, 0.94F - power * 0.09F, AudioService.BLAST_REACH_BLOCKS);
		// And the camera. The bolt is an entity: it detonates wherever it happens to meet terrain or a
		// player, on a tick nothing else can predict, and in the third phase it is thrown from the
		// volley lane which the action envelope does not describe at all. There is no clock to derive
		// this from, which is the whole reason WorldInterfaceBlastS2C exists.
		if (encounterId != null) {
			WorldInterfaceBlastService.emit(level, encounterId, impact, radius * BLAST_SHAKE_REACH,
					power >= 1 ? WorldInterfaceProtocol.BlastGrade.HEAVY
							: WorldInterfaceProtocol.BlastGrade.MEDIUM);
		}
	}

	/**
	 * How far past its own blast the detonation is felt, as a multiple of the damage radius.
	 *
	 * <p>Deliberately much wider than the radius that hurts. A blast you can feel from outside the
	 * part that kills you is what tells the rest of the table it happened; one that stops exactly
	 * where the damage stops is a private event.
	 */
	private static final double BLAST_SHAKE_REACH = 7.0D;

	/** Grows with the form that fired it; the third one is not throwing the same shot as the first. */
	private double impactRadius() {
		return IMPACT_RADIUS * (1.0D + power * 0.35D);
	}

	/** The lingering half of the hit: the place the bolt landed stays dangerous for a while. */
	private static void spawnBreathPool(ServerLevel level, Vec3 impact, LivingEntity owner,
			double impactRadius) {
		AreaEffectCloud cloud = new AreaEffectCloud(level, impact.x, impact.y, impact.z);
		if (owner != null) cloud.setOwner(owner);
		cloud.setCustomParticle(BREATH_PARTICLE);
		float radius = (float) impactRadius * 0.75F;
		cloud.setRadius(radius);
		cloud.setDuration(BREATH_CLOUD_TICKS);
		cloud.setRadiusPerTick(-radius / BREATH_CLOUD_TICKS);
		cloud.addEffect(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1, 0));
		level.addFreshEntity(cloud);
	}

	private static boolean isPlayerSource(DamageSource source) {
		if (source.getEntity() instanceof Player || source.getDirectEntity() instanceof Player) return true;
		return source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() instanceof Player;
	}

	@Override
	public EntityDimensions getDimensions(Pose pose) {
		return EntityDimensions.scalable(orbScale(), orbScale());
	}

	@Override public boolean isPickable() { return true; }
	@Override public boolean canBeHitByProjectile() { return true; }
	@Override public boolean isPushable() { return false; }
	@Override public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256.0D * 256.0D; }

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		if (encounterId != null) output.putString("encounter_id", encounterId.toString());
		if (ownerId != null) output.putString("owner_id", ownerId.toString());
		if (targetId != null) output.putString("target_id", targetId.toString());
		output.putInt("age_ticks", ageTicks);
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		loadedFromDisk = true;
		encounterId = parseUuid(input.getStringOr("encounter_id", ""));
		ownerId = parseUuid(input.getStringOr("owner_id", ""));
		targetId = parseUuid(input.getStringOr("target_id", ""));
		ageTicks = Math.clamp(input.getIntOr("age_ticks", 0), 0, MAX_FLIGHT_TICKS);
		entityData.set(SCALE, MAX_SCALE);
		setNoGravity(true);
		noPhysics = true;
		refreshDimensions();
	}

	private static UUID parseUuid(String encoded) {
		try {
			return encoded.isBlank() ? null : UUID.fromString(encoded);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}
}
