package com.xm.thefourthfrequency.unrendered;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The single unrendered-layer dimension, and the generator codec its dimension file names.
 *
 * <p>One dimension, not one per player. Separate copies would have to be created at runtime, which
 * Fabric supports poorly and which costs a full level's worth of chunk storage each; the layer does
 * not need them because it is endless. Two players a million blocks apart in the same level are
 * already as isolated as two players in different levels - they cannot see, hear or reach each
 * other, and because the maze is hashed from position they are not even in the same-looking place.
 * {@code UnrenderedAnchorManager} is what hands out those distances.
 */
public final class UnrenderedDimensions {
	public static final ResourceKey<Level> UNRENDERED_LAYER = ResourceKey.create(Registries.DIMENSION,
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "unrendered_layer"));

	private static boolean initialized;

	private UnrenderedDimensions() {
	}

	/**
	 * Registers the generator codec.
	 *
	 * <p>Must run during mod initialisation, before any level is loaded. The dimension file names
	 * this codec by id, and a datapack that cannot resolve a generator type does not fail loudly -
	 * it drops the dimension, and the first teleport into it fails with nothing to explain why.
	 */
	public static void initialize() {
		if (initialized) return;
		initialized = true;
		Registry.register(BuiltInRegistries.CHUNK_GENERATOR,
				Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "unrendered_layer"),
				UnrenderedLayerGenerator.CODEC);
		TheFourthFrequency.LOGGER.info("Registered the unrendered layer chunk generator");
	}

	public static boolean isUnrendered(Level level) {
		return level != null && UNRENDERED_LAYER.equals(level.dimension());
	}

	public static boolean isUnrendered(ResourceKey<Level> dimension) {
		return UNRENDERED_LAYER.equals(dimension);
	}
}
