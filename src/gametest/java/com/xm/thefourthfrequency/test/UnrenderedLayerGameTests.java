package com.xm.thefourthfrequency.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.entity.BacteriaEntity;
import com.xm.thefourthfrequency.unrendered.UnrenderedAnomaly;
import com.xm.thefourthfrequency.unrendered.UnrenderedDimensions;
import com.xm.thefourthfrequency.unrendered.UnrenderedLayerGenerator;
import com.xm.thefourthfrequency.unrendered.UnrenderedLayerLayout;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * The unrendered layer's runtime assertions: everything about it that only a loaded game can answer.
 *
 * <p>Its geometry and its placement rules are pure and covered by unit tests, which is where that
 * kind of thing belongs. What those cannot see is whether the pieces were ever wired together - a
 * generator codec that was never registered, a dimension file naming a type that is not packaged, an
 * entity whose attributes were declared and never attached. Every one of those compiles, passes
 * every unit test, and then fails at the moment a player is standing in the dark waiting for a
 * teleport that will not arrive.
 */
public final class UnrenderedLayerGameTests implements CustomTestMethodInvoker {
	private static final String NAMESPACE = TheFourthFrequency.MOD_ID;
	private static final String PATH = "unrendered_layer";

	/**
	 * The datapack files exist, and the three of them agree about each other.
	 *
	 * <p>A dimension whose {@code type} or {@code generator} cannot be resolved is not an error at
	 * load: the dimension is dropped, and the first thing anybody hears about it is a teleport that
	 * silently does nothing.
	 */
	@GameTest
	public void theLayerIsPackagedAsThreeAgreeingFiles(GameTestHelper helper) {
		JsonObject dimension = readJson("/data/" + NAMESPACE + "/dimension/" + PATH + ".json");
		String expected = NAMESPACE + ":" + PATH;
		if (!expected.equals(dimension.get("type").getAsString())) {
			throw new AssertionError("The layer names a dimension type it does not ship: "
					+ dimension.get("type"));
		}
		JsonObject generator = dimension.getAsJsonObject("generator");
		if (!expected.equals(generator.get("type").getAsString())) {
			throw new AssertionError("The layer names a generator that is not this mod's: "
					+ generator.get("type"));
		}
		String biome = generator.getAsJsonObject("biome_source").get("biome").getAsString();
		if (!expected.equals(biome)) throw new AssertionError("Unexpected layer biome: " + biome);

		JsonObject type = readJson("/data/" + NAMESPACE + "/dimension_type/" + PATH + ".json");
		// The vertical geometry is stated in two places that cannot see each other - the datapack and
		// UnrenderedLayerLayout - and the generator answers getMinY from the second. If they disagree
		// the level rejects every chunk the generator hands it.
		if (type.get("min_y").getAsInt() != UnrenderedLayerLayout.MIN_Y
				|| type.get("height").getAsInt() != UnrenderedLayerLayout.WORLD_HEIGHT) {
			throw new AssertionError("The layer's declared height no longer matches its layout");
		}
		if (type.get("has_skylight").getAsBoolean() || !type.get("has_ceiling").getAsBoolean()) {
			throw new AssertionError("The layer must have a ceiling and no sky");
		}
		// Nothing spawns here on its own. The entity is placed by the session service, and anything
		// else arriving would be the layer generating content, which it must never do.
		if (type.get("monster_spawn_light_level").getAsInt() != 0) {
			throw new AssertionError("The layer must not permit natural monster spawns");
		}
		readJson("/data/" + NAMESPACE + "/worldgen/biome/" + PATH + ".json");
		helper.succeed();
	}

	/**
	 * The generator codec reached the registry.
	 *
	 * <p>This is the one that cannot be caught anywhere earlier. The registration lives in the common
	 * initialiser, the dimension file refers to it by string, and nothing in between is typed - so a
	 * renamed id, a reordered bootstrap or a deleted call all compile cleanly and all produce exactly
	 * the same symptom: a dimension that is simply not there.
	 */
	@GameTest
	public void theGeneratorCodecIsRegisteredUnderTheIdTheDimensionFileUses(GameTestHelper helper) {
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, PATH);
		var codec = BuiltInRegistries.CHUNK_GENERATOR.getValue(id);
		if (codec == null) throw new AssertionError("The layer's chunk generator codec is not registered");
		if (codec != UnrenderedLayerGenerator.CODEC) {
			throw new AssertionError("Another codec has claimed the layer's generator id");
		}
		if (!UnrenderedDimensions.UNRENDERED_LAYER.identifier().equals(id)) {
			throw new AssertionError("The dimension key and the generator id have drifted apart");
		}
		helper.succeed();
	}

	/**
	 * The entity is registered, and its attributes were actually attached.
	 *
	 * <p>{@code FabricDefaultAttributeRegistry} is a separate call from the type registration and
	 * failing to make it does not fail the build - it throws when the first one is created, which
	 * here is sixty seconds into somebody's session.
	 */
	@GameTest
	public void theEntityIsRegisteredWithTheSpeedItsEncounterDependsOn(GameTestHelper helper) {
		Identifier id = Identifier.fromNamespaceAndPath(NAMESPACE, "bacteria");
		if (BuiltInRegistries.ENTITY_TYPE.getValue(id) != ModEntities.BACTERIA) {
			throw new AssertionError("The bacteria entity type is not registered under " + id);
		}
		var attributes = net.minecraft.world.entity.ai.attributes.DefaultAttributes
				.getSupplier(ModEntities.BACTERIA);
		double speed = attributes.getValue(Attributes.MOVEMENT_SPEED);
		if (Math.abs(speed - BacteriaEntity.MOVEMENT_SPEED) > 1.0E-6) {
			throw new AssertionError("The registered speed is not the declared one: " + speed);
		}
		// No range check on the attribute here. There was one, expressed in attribute units, and it
		// was wrong - it was written from the same inference that put the constant at 0.335, which
		// measures 4.94 blocks/s and is slower than a sprinting player. An assertion derived from the
		// guess it was meant to catch is worse than none, because it reads as confirmation. The band
		// that matters is in blocks per second and is measured, not inferred; see
		// theBacteriaSpeedLandsBetweenSprintingAndSprintJumping, which owns it alone.
		if (attributes.getValue(Attributes.ATTACK_DAMAGE) != 0.0) {
			throw new AssertionError("The bacteria must deal no damage; being reached is not being hit");
		}
		helper.succeed();
	}

	/** Both cues exist as registered events with shipped files behind them. */
	@GameTest
	public void bothLayerCuesArePackaged(GameTestHelper helper) {
		requireSound(ModSounds.UNRENDERED_LAYER_AMBIENCE, "unrendered/layer_ambience");
		requireSound(ModSounds.UNRENDERED_CAPTURE_SCREAM, "unrendered/capture_scream");
		// The entity arrives inside the session rather than after it. A delay at or past the ceiling
		// would mean it is placed into a layer the player has already been returned from.
		if (UnrenderedAnomaly.ENTITY_DELAY_TICKS >= UnrenderedAnomaly.DURATION_TICKS) {
			throw new AssertionError("The entity would arrive after the session has ended");
		}
		helper.succeed();
	}

	/**
	 * Measures what the speed attribute actually produces, in blocks per second, under real physics.
	 *
	 * <p>The encounter rests on one number sitting inside one narrow band - faster than a sprinting
	 * player (5.612 blocks/s), slower than a sprint-jumping one (about 7.1) - and that number is an
	 * attribute value, not a speed. Minecraft's relationship between the two is neither linear nor
	 * documented: {@code Mob.setSpeed} sets the movement input to the same value it sets the speed
	 * field to, which makes the acceleration quadratic in the attribute, and the terminal velocity
	 * then falls out of the ground friction and drag. Deriving it by reading bytecode was tried and
	 * got the wrong answer. This measures it instead.
	 *
	 * <p>Measured with a vanilla mob as the vehicle rather than with the bacteria itself, for two
	 * reasons. The bacteria removes itself the moment it finds it is outside the unrendered layer,
	 * which is a safety property worth more than this test's convenience, and the layer does not
	 * exist in the GameTest world anyway. It is a fair substitute because the physics belongs to
	 * {@code LivingEntity.travel} and the attribute, not to either class: {@code BacteriaEntity}
	 * overrides nothing on the movement path - no {@code travel}, no {@code getSpeed}, no
	 * {@code getFrictionInfluencedSpeed} - so given the same attribute on the same floor the two
	 * reach the same terminal velocity.
	 *
	 * <p>On failure the message carries the measured figure, which is what to retune the constant
	 * from.
	 */
	@GameTest(maxTicks = 140)
	public void theBacteriaSpeedLandsBetweenSprintingAndSprintJumping(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// This 20-block track exceeds the default empty template. Keep it above the other
		// simultaneously running fixtures, which can otherwise replace its floor or block the path.
		BlockPos origin = helper.absolutePos(new BlockPos(0, 91, 0));
		// A short flat run. Terminal velocity arrives inside twenty ticks, so this needs to be long
		// enough not to end in a wall before the sampling window, not long enough to cross the
		// spacing GameTest leaves between structures.
		for (int step = -2; step <= RUN_LENGTH; step++) {
			for (int side = -1; side <= 1; side++) {
				level.setBlock(origin.offset(step, -1, side), Blocks.STONE.defaultBlockState(), 2);
				for (int y = 0; y <= 2; y++) {
					level.setBlock(origin.offset(step, y, side), Blocks.AIR.defaultBlockState(), 2);
				}
			}
		}

		Zombie vehicle = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(0, 91, 0));
		// removeFreeWill strips the goals but leaves navigation and the move control, which is
		// exactly the path a pathfinding mob uses and the one being measured.
		vehicle.setPersistenceRequired();
		vehicle.setInvulnerable(true);
		var speed = vehicle.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) throw new AssertionError("The vehicle has no movement speed attribute");
		speed.setBaseValue(BacteriaEntity.MOVEMENT_SPEED);

		BlockPos target = origin.offset(RUN_LENGTH, 0, 0);
		double[] peakPerTick = { 0.0 };
		double[] previous = { vehicle.getX() };
		int[] tick = { 0 };
		helper.onEachTick(() -> {
			tick[0]++;
			vehicle.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
			double travelled = vehicle.getX() - previous[0];
			previous[0] = vehicle.getX();
			// Sampled only once the run is up to speed, and taken as a peak rather than an average:
			// a single tick spent turning or clipping a corner drags an average down and would make
			// the measurement read slower than the entity actually is.
			if (tick[0] >= SAMPLE_FROM_TICK && tick[0] <= SAMPLE_TO_TICK) {
				peakPerTick[0] = Math.max(peakPerTick[0], travelled);
			}
		});
		helper.runAtTickTime(SAMPLE_TO_TICK + 2, () -> {
			double blocksPerSecond = peakPerTick[0] * 20.0D;
			if (blocksPerSecond < MIN_BLOCKS_PER_SECOND || blocksPerSecond > MAX_BLOCKS_PER_SECOND) {
				helper.assertTrue(false, String.format(
						"BacteriaEntity.MOVEMENT_SPEED = %.4f measures %.2f blocks/s, outside the "
								+ "%.1f-%.1f band it has to sit in (sprint is 5.61, sprint-jump about "
								+ "7.1). Retune the constant from this figure.",
						BacteriaEntity.MOVEMENT_SPEED, blocksPerSecond,
						MIN_BLOCKS_PER_SECOND, MAX_BLOCKS_PER_SECOND));
			}
			helper.succeed();
		});
	}

	/** Blocks of straight floor built for the speed measurement. */
	private static final int RUN_LENGTH = 20;
	/** Terminal velocity is reached well inside twenty ticks; sampling starts after that. */
	private static final int SAMPLE_FROM_TICK = 30;
	private static final int SAMPLE_TO_TICK = 60;
	/**
	 * Above a sprinting player (5.612 blocks/s) and below a sprint-jumping one (about 7.1).
	 *
	 * <p>The floor moved down with the constant when the heartbeat arrived, and it is still doing the
	 * job it was added for: 5.85 is a clear five percent over a sprint, so a regression that made the
	 * entity scenery - which is exactly what an earlier value silently did - still fails here.
	 */
	/**
	 * Just under a sprint-jump rather than mid-band, on an explicit tuning call.
	 *
	 * <p>A sprinting player is 5.612 blocks/s and a sprint-jumping one about 7.1. This band sits
	 * against the upper bound: the jump still escapes, and it is the only thing that does.
	 */
	private static final double MIN_BLOCKS_PER_SECOND = 6.6;
	private static final double MAX_BLOCKS_PER_SECOND = 7.05;

	private static void requireSound(net.minecraft.sounds.SoundEvent event, String assetPath) {
		if (BuiltInRegistries.SOUND_EVENT.getKey(event) == null) {
			throw new AssertionError("Unregistered layer sound event: " + assetPath);
		}
		String resource = "/assets/" + NAMESPACE + "/sounds/" + assetPath + ".ogg";
		try (var stream = UnrenderedLayerGameTests.class.getResourceAsStream(resource)) {
			if (stream == null) throw new AssertionError("Missing layer sound file " + resource);
		} catch (IOException exception) {
			throw new AssertionError("Unable to read layer sound file " + resource, exception);
		}
	}

	private static JsonObject readJson(String resourcePath) {
		try (var stream = UnrenderedLayerGameTests.class.getResourceAsStream(resourcePath)) {
			if (stream == null) throw new AssertionError("Missing layer resource " + resourcePath);
			return JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
					.getAsJsonObject();
		} catch (IOException exception) {
			throw new AssertionError("Unable to read layer resource " + resourcePath, exception);
		}
	}

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		helper.setBlock(0, 0, 0, Blocks.AIR);
		try {
			method.invoke(this, helper);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof AssertionError error) throw error;
			if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
			throw exception;
		}
	}
}
