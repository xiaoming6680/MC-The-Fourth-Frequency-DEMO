package com.xm.thefourthfrequency.content;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.correction.ReworkCollisionProfile;
import com.xm.thefourthfrequency.entity.ReworkEntity;
import com.xm.thefourthfrequency.entity.BacteriaEntity;
import com.xm.thefourthfrequency.entity.HimEntity;
import com.xm.thefourthfrequency.entity.StabilityAnchorEntity;
import com.xm.thefourthfrequency.entity.StabilityAnchorGeometry;
import com.xm.thefourthfrequency.entity.WatcherEntity;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.entity.WorldInterfaceEnergyOrbEntity;
import com.xm.thefourthfrequency.entity.WorldInterfacePartEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntities {
	private static final ReworkCollisionProfile REWORK_BASE_COLLISION = ReworkCollisionProfile.forStage(1);
	private static final ResourceKey<EntityType<?>> REWORK_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "rework_body"));
	private static final ResourceKey<EntityType<?>> WATCHER_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "watcher"));
	private static final ResourceKey<EntityType<?>> BACTERIA_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "bacteria"));
	private static final ResourceKey<EntityType<?>> HIM_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "him"));
	private static final ResourceKey<EntityType<?>> WORLD_INTERFACE_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "world_interface"));
	private static final ResourceKey<EntityType<?>> WORLD_INTERFACE_PART_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "world_interface_part"));
	private static final ResourceKey<EntityType<?>> WORLD_INTERFACE_ENERGY_ORB_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, WorldInterfaceEnergyOrbEntity.TYPE_ID);
	private static final ResourceKey<EntityType<?>> STABILITY_ANCHOR_KEY = ResourceKey.create(
			Registries.ENTITY_TYPE, StabilityAnchorEntity.TYPE_ID);

	public static final EntityType<ReworkEntity> REWORK_BODY = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			REWORK_KEY,
			EntityType.Builder.of(ReworkEntity::new, MobCategory.MONSTER)
					.sized(REWORK_BASE_COLLISION.width(), REWORK_BASE_COLLISION.height())
					.eyeHeight(REWORK_BASE_COLLISION.eyeHeight())
					.clientTrackingRange(8)
					.updateInterval(2)
					.build(REWORK_KEY));

	/**
	 * Wider than it is tall, and short enough to clear the layer's four-block headroom with room to
	 * spare - a large arachnid, low and broad.
	 *
	 * <p>Sized to the legs rather than to the body. The model's feet stand about 0.88 blocks either
	 * side of centre, so 1.7 is the span of the thing as the player sees it; the body itself is
	 * barely half that. Height follows the body rather than the knees, because the knees are the part
	 * that rises above it and a box drawn to them would be mostly empty air.
	 *
	 * <p>None of it is collision - {@code canBeCollidedWith} is false and nothing can push it - so
	 * this box exists for render culling and for looking right in a debug overlay, not for physics.
	 * The capture check is a distance test that does not read it at all.
	 */
	public static final EntityType<BacteriaEntity> BACTERIA = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			BACTERIA_KEY,
			EntityType.Builder.of(BacteriaEntity::new, MobCategory.MONSTER)
					.sized(1.7F, 1.0F)
					.eyeHeight(0.7F)
					.clientTrackingRange(10)
					.updateInterval(2)
					.build(BACTERIA_KEY));

	public static final EntityType<WatcherEntity> WATCHER = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			WATCHER_KEY,
			EntityType.Builder.of(WatcherEntity::new, MobCategory.MONSTER)
					.sized(0.62F, 2.9F)
					.eyeHeight(2.62F)
					.clientTrackingRange(10)
					.updateInterval(2)
					.build(WATCHER_KEY));

	/**
	 * Player-proportioned on purpose: the whole read is "that was a person standing there", and any
	 * silhouette that is not exactly Steve-shaped gives it away as something else before the fifth
	 * of a second is up. Tracked far out because it is placed twenty to forty blocks away and has
	 * to already be on the client when the player turns round.
	 */
	public static final EntityType<HimEntity> HIM = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			HIM_KEY,
			EntityType.Builder.of(HimEntity::new, MobCategory.MONSTER)
					.sized(0.6F, 1.8F)
					.eyeHeight(1.62F)
					.clientTrackingRange(6)
					.updateInterval(2)
					.noSummon()
					.build(HIM_KEY));

	public static final EntityType<WorldInterfaceEntity> WORLD_INTERFACE = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			WORLD_INTERFACE_KEY,
			EntityType.Builder.of(WorldInterfaceEntity::new, MobCategory.MONSTER)
					.sized(7.0F, 16.0F)
					.eyeHeight(11.0F)
					.fireImmune()
					.clientTrackingRange(32)
					.updateInterval(1)
					.build(WORLD_INTERFACE_KEY));

	public static final EntityType<WorldInterfacePartEntity> WORLD_INTERFACE_PART = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			WORLD_INTERFACE_PART_KEY,
			EntityType.Builder.of(WorldInterfacePartEntity::new, MobCategory.MISC)
					.sized(3.5F, 7.0F)
					.noSave()
					.noSummon()
					.fireImmune()
					.clientTrackingRange(32)
					.updateInterval(1)
					.build(WORLD_INTERFACE_PART_KEY));

	public static final EntityType<WorldInterfaceEnergyOrbEntity> WORLD_INTERFACE_ENERGY_ORB = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			WORLD_INTERFACE_ENERGY_ORB_KEY,
			EntityType.Builder.of(WorldInterfaceEnergyOrbEntity::new, MobCategory.MISC)
					.sized(1.0F, 1.0F)
					.fireImmune()
					.noSummon()
					.clientTrackingRange(24)
					.updateInterval(1)
					.build(WORLD_INTERFACE_ENERGY_ORB_KEY));

	/**
	 * The ten arena anchors. Saved, because the encounter reconciles the entities it finds against
	 * the persisted anchor record rather than rebuilding all ten from scratch on every load, and
	 * tracked as far as the interface itself so the tethers never end at an anchor the client has
	 * not been told about.
	 */
	public static final EntityType<StabilityAnchorEntity> STABILITY_ANCHOR = Registry.register(
			BuiltInRegistries.ENTITY_TYPE,
			STABILITY_ANCHOR_KEY,
			EntityType.Builder.of(StabilityAnchorEntity::new, MobCategory.MISC)
					.sized(StabilityAnchorGeometry.WIDTH, StabilityAnchorGeometry.HEIGHT)
					.eyeHeight(StabilityAnchorGeometry.EYE_HEIGHT)
					.fireImmune()
					.noSummon()
					.clientTrackingRange(32)
					.updateInterval(1)
					.build(STABILITY_ANCHOR_KEY));

	private ModEntities() {
	}

	public static void initialize() {
		FabricDefaultAttributeRegistry.register(REWORK_BODY, ReworkEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(WATCHER, WatcherEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(BACTERIA, BacteriaEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(HIM, HimEntity.createAttributes());
		FabricDefaultAttributeRegistry.register(WORLD_INTERFACE, WorldInterfaceEntity.createAttributes());
		TheFourthFrequency.LOGGER.info(
				"Registered rework, watcher, stability-anchor and world-interface entities");
	}
}
