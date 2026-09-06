package com.xm.thefourthfrequency.ending;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stability anchor's cross-layer contract: registration, model, textures, tether endpoint,
 * migration and direct first-hit destruction.
 *
 * <p>Everything that can be asserted against real objects is. The client-side pieces are checked by
 * reading their source, which is how the rest of this suite reaches split-environment classes - the
 * unit-test source set cannot load {@code net.minecraft.client}.
 */
final class StabilityAnchorContractTest {
	private static final Path COMMON = Path.of("src/main/java/com/xm/thefourthfrequency");
	private static final Path CLIENT = Path.of("src/client/java/com/xm/thefourthfrequency");
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static final UUID ENCOUNTER_ID = UUID.nameUUIDFromBytes(
			"anchor-encounter".getBytes(StandardCharsets.UTF_8));

	@Test
	void theAnchorIsRegisteredAsAFixedMiscEntitySizedFromTheSharedGeometry() throws Exception {
		String entities = read(COMMON.resolve("content/ModEntities.java"));
		// Scoped to the anchor's own builder chain. This file registers six entity types, so a
		// whole-file `contains` would be satisfied by any one of the other five - which is exactly
		// how the tracking-range check first passed while reading the rework body's range of 8.
		Matcher registration = Pattern.compile(
				"STABILITY_ANCHOR = Registry\\.register\\([\\s\\S]*?\\.build\\(").matcher(entities);
		assertTrue(registration.find(), "The stability anchor must be registered");
		String builder = registration.group();
		assertTrue(builder.contains("StabilityAnchorEntity::new"));
		assertTrue(builder.contains("MobCategory.MISC"));
		// Sized from the shared constants rather than from literals, so the collision box cannot
		// drift away from the geometry the model and the tether are built against.
		assertTrue(builder.contains("StabilityAnchorGeometry.WIDTH")
						&& builder.contains("StabilityAnchorGeometry.HEIGHT"),
				"The anchor's collision box must come from StabilityAnchorGeometry");
		assertTrue(builder.contains(".fireImmune()") && builder.contains(".noSummon()"));
		assertTrue(builder.contains(".updateInterval(1)"),
				"An anchor carrying a live tether has to be updated every tick");
		Matcher tracking = Pattern.compile("clientTrackingRange\\((\\d+)\\)").matcher(builder);
		assertTrue(tracking.find(), "The anchor must declare a client tracking range");
		assertTrue(Integer.parseInt(tracking.group(1)) >= 32,
				"Anchors must be tracked at least as far as the interface they are tethered to");

		String entity = read(COMMON.resolve("entity/StabilityAnchorEntity.java"));
		assertTrue(entity.contains("setNoGravity(true)") && entity.contains("noPhysics = true"));
		assertTrue(entity.contains("public boolean isPushable()") && entity.contains("return false"));
		assertTrue(entity.contains("setDeltaMovement(Vec3.ZERO)"),
				"A fixed anchor must not be able to be pushed off the cap its claws grip");
		// The performance is a live-session effect. A save taken mid-collapse must clean up, never
		// resume sixteen ticks of animation against a fresh game clock.
		assertTrue(entity.contains("loadedCollapsing") && entity.contains("discard()"));
		assertTrue(entity.contains("output.putInt(\"anchor_index\""),
				"The slot index has to survive a reload; it is the anchor's identity in the arena");
	}

	@Test
	void theClientRegistersOneModelLayerAndOneRendererForTheAnchor() throws Exception {
		String client = read(CLIENT.resolve("client_ui/TheFourthFrequencyClient.java"));
		assertTrue(client.contains("EntityModelLayerRegistry.registerModelLayer(StabilityAnchorRenderer.MODEL_LAYER")
				&& client.contains("StabilityAnchorModel::createLayer"));
		assertTrue(client.contains("EntityRendererRegistry.register(ModEntities.STABILITY_ANCHOR"));
		assertTrue(Files.isRegularFile(CLIENT.resolve("client_render/StabilityAnchorModel.java")));
		assertTrue(Files.isRegularFile(CLIENT.resolve("client_render/StabilityAnchorRenderer.java")));
		assertTrue(Files.isRegularFile(CLIENT.resolve("client_render/StabilityAnchorRenderState.java")));
	}

	/**
	 * The model has to be readable as a four-way clamp from every side, which is a geometry
	 * question, not a taste one: four identical claws, five links each, and nothing camera-facing.
	 */
	@Test
	void theModelIsFourIdenticalBoxClawsAndAnOpenEmitter() throws Exception {
		String model = read(CLIENT.resolve("client_render/StabilityAnchorModel.java"));
		assertTrue(model.contains("CLAW_COUNT = 4"));
		for (String link : new String[]{"_upper_arm", "_pivot", "_forearm", "_wrist", "_foot"}) {
			assertTrue(model.contains("name + \"" + link + "\""), "missing claw link: " + link);
		}
		assertTrue(model.contains("\"chest_core\"") && model.contains("\"relay_core\"")
				&& model.contains("\"petal_\" + index"));
		// No round meshes, no billboards, no fake camera-facing geometry anywhere in the body.
		assertFalse(model.contains("Billboard") || model.contains("cameraFacing")
						|| model.contains("lookAt"),
				"Every visible part must be a real CubeListBuilder box");
		// The relay core's model Y is the same constant the tether and the collapse effects use.
		assertTrue(model.contains("StabilityAnchorGeometry.RELAY_CORE_MODEL_Y")
				&& model.contains("StabilityAnchorGeometry.CHEST_CORE_MODEL_Y"));
		// The emitter must stay open: nothing is allowed to grow into a ring, a cage or a barrel
		// around the core, because the tether leaves it at whatever angle the boss happens to be at.
		assertFalse(model.contains("\"emitter_ring\"") || model.contains("\"emitter_cage\"")
				|| model.contains("\"muzzle\""));

		String renderer = read(CLIENT.resolve("client_render/StabilityAnchorRenderer.java"));
		assertTrue(renderer.contains("extends EntityRenderer<StabilityAnchorEntity"));
		assertTrue(renderer.contains("entityCutoutNoCull(TEXTURE)")
				&& renderer.contains("entityTranslucentEmissive(EMISSIVE_TEXTURE)"));
		assertTrue(renderer.contains("scale(-1.0F, -1.0F, 1.0F)"),
				"The model is authored in the usual Y-down entity space and must be flipped once");
	}

	@Test
	void bothEntitySheetsExistAndTheEmissiveMaskStaysSparse() throws Exception {
		Path base = ASSETS.resolve("textures/entity/stability_anchor.png");
		Path emissive = ASSETS.resolve("textures/entity/stability_anchor_emissive.png");
		assertTrue(Files.isRegularFile(base), base.toString());
		assertTrue(Files.isRegularFile(emissive), emissive.toString());

		var baseImage = ImageIO.read(base.toFile());
		assertEquals(128, baseImage.getWidth());
		assertEquals(128, baseImage.getHeight());
		for (int y = 0; y < baseImage.getHeight(); y++) for (int x = 0; x < baseImage.getWidth(); x++) {
			assertEquals(255, baseImage.getRGB(x, y) >>> 24,
					"the base sheet must be fully opaque at " + x + "," + y);
		}

		var emissiveImage = ImageIO.read(emissive.toFile());
		assertEquals(128, emissiveImage.getWidth());
		assertEquals(128, emissiveImage.getHeight());
		assertTrue(emissiveImage.getColorModel().hasAlpha());
		int lit = 0;
		for (int y = 0; y < emissiveImage.getHeight(); y++) {
			for (int x = 0; x < emissiveImage.getWidth(); x++) {
				if ((emissiveImage.getRGB(x, y) >>> 24) > 8) lit++;
			}
		}
		int total = emissiveImage.getWidth() * emissiveImage.getHeight();
		assertTrue(lit > 0, "the cores have to glow");
		// Only the cores and a seam strip. If this ever passes a tenth of the sheet, something on
		// the structure other than the two cores has started glowing and the anchor is a lantern.
		assertTrue(lit < total / 10,
				"the emissive mask must stay sparse; " + lit + " of " + total + " texels are lit");
	}

	@Test
	void bothLanguagesNameTheEntityAndCarryTheDestructionNotice() throws Exception {
		JsonObject en = json(ASSETS.resolve("lang/en_us.json"));
		JsonObject zh = json(ASSETS.resolve("lang/zh_cn.json"));
		for (String key : new String[]{"entity.thefourthfrequency.stability_anchor",
				"message.thefourthfrequency.world_interface.anchor_destroyed"}) {
			assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "missing English " + key);
			assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "missing Chinese " + key);
		}
	}

	/**
	 * The tether's anchor-side end is the relay core, resolved from the shared constant and from the
	 * server-ordered snapshot positions - not a fixed offset, and never a vertical column.
	 */
	@Test
	void theTetherStartsAtTheRelayCoreAndStillFollowsTheMovingBoss() throws Exception {
		String beams = read(CLIENT.resolve("client_render/WorldInterfaceBeamBatchRenderer.java"));
		assertTrue(beams.contains("StabilityAnchorGeometry.relayCore("),
				"The tether endpoint must come from the shared relay-core offset");
		assertFalse(beams.contains("add(0.0D, 0.7D, 0.0D)"),
				"The old end-crystal offset must be gone, not merely unused");
		assertTrue(beams.contains("encounter.anchorAliveMask()")
						&& beams.contains("encounter.anchorPositions()"),
				"Which anchors are drawn stays a server decision");
		// The far end is the interface's own core, sampled per frame; a fixed skyward column would
		// stop saying "these are holding it up".
		int tethers = beams.indexOf("private static void extractAnchorTethers");
		assertTrue(tethers > 0);
		String body = beams.substring(tethers, beams.indexOf("private static void", tethers + 10));
		assertTrue(body.contains("eye.x, eye.y, eye.z"),
				"Every tether must end on the boss core passed in, not on a constant");
		assertTrue(beams.contains("extractSnappedTethers"),
				"A severed tether has to be drawn retracting; the alive mask drops it immediately");
	}

	@Test
	void damageRoutesThroughTheBespokeEntityAndNoLongerTouchesEndCrystal() throws Exception {
		String encounter = read(COMMON.resolve("ending/EndBossEncounterService.java"));
		assertTrue(encounter.contains("handleAnchorDamage(ServerLevel level, StabilityAnchorEntity"));
		assertTrue(encounter.contains("anchorForEntity(anchorEntity.getUUID())"));
		assertTrue(encounter.contains("player.isSpectator()") && encounter.contains("Float.isFinite(amount)")
						&& encounter.contains("before.stage().isCombat()"),
				"Non-players, spectators, non-finite damage and out-of-combat hits stay rejected");
		assertTrue(encounter.contains("anchorEntity.beginCollapse()"));
		assertFalse(encounter.contains("anchorHitAbsorbed")
					|| encounter.contains("anchor_warning")
					|| encounter.contains("beginAbsorb"),
				"A valid first hit must destroy its anchor instead of being globally absorbed");
		assertFalse(encounter.contains("import net.minecraft.world.entity.boss.enderdragon.EndCrystal;"),
				"Nothing in the encounter service should still know about end crystals");
		// The destruction performance is decoration. It must not blow up terrain, hurt anyone, or
		// leave anything behind that a player can pick up or walk into.
		int collapse = encounter.indexOf("private static void emitAnchorCollapse");
		assertTrue(collapse > 0);
		String effect = encounter.substring(collapse, encounter.indexOf("\n\t}", collapse));
		for (String forbidden : new String[]{"explode", "setBlock", "ItemEntity", "queueTerrainScar",
				"queueExplosionScar", "hurt("}) {
			assertFalse(effect.contains(forbidden),
					"the collapse effect must not " + forbidden + " anything");
		}

		String entity = read(COMMON.resolve("entity/StabilityAnchorEntity.java"));
		assertTrue(entity.contains("EndBossEncounterService.handleAnchorDamage(level, this, source, amount)"));
		assertTrue(entity.contains("isInvulnerableToBase(source)"));
		assertTrue(entity.contains("return !collapsing() && !isRemoved();"),
				"A destroyed anchor must stop being hittable immediately, however long it takes to leave");
	}

	@Test
	void theArenaMigratesLegacyTaggedCrystalsAndLeavesOrdinaryOnesAlone() throws Exception {
		String arena = read(COMMON.resolve("ending/EndBossArenaService.java"));
		assertTrue(arena.contains("StabilityAnchorEntity.create(level,"));
		assertTrue(arena.contains("isLegacyTaggedAnchor(loaded)") && arena.contains("loaded.discard()"),
				"An anchor written as a tagged crystal must be taken down before its replacement "
						+ "is added under the same UUID");
		assertTrue(arena.contains("ANCHOR_TAG"),
				"The legacy tag has to stay readable, or an older save cannot be recognised at all");
		assertTrue(arena.contains("Map<ServerLevel, Map<UUID, StabilityAnchorEntity>> KNOWN_ANCHORS"));
		assertTrue(arena.contains("ANCHOR_COUNT = 10"));
		// A destroyed anchor is never rebuilt, and a live one that went missing over a restart is.
		assertTrue(arena.contains("if (anchor.destroyed())") && arena.contains("Unable to restore authoritative anchor"));
	}

	@Test
	void tenSlotsKeepUniqueIndicesPositionsAndDeterministicIdentities() {
		List<WorldInterfaceState.Anchor> anchors = anchors(false);
		assertEquals(10, anchors.size());
		assertEquals(10, anchors.stream().map(WorldInterfaceState.Anchor::index).distinct().count());
		assertEquals(10, anchors.stream().map(WorldInterfaceState.Anchor::position).distinct().count());
		assertEquals(10, anchors.stream().map(WorldInterfaceState.Anchor::anchorEntityUuid).distinct().count());

		WorldInterfaceState.Anchor anchor = anchors.getFirst();
		assertTrue(anchor.anchorEntityUuid().isPresent());
		assertFalse(anchor.destroyed());
	}

	@Test
	void destroyedAnchorsPersistWithoutAFirstHitLatch() {
		CompoundTag pristine = encode(snapshot(false));
		assertFalse(pristine.contains("anchor_hit_absorbed"));
		assertFalse(decode(pristine).anchors().getFirst().destroyed());

		CompoundTag destroyed = encode(snapshot(true));
		assertFalse(destroyed.contains("anchor_hit_absorbed"));
		assertTrue(decode(destroyed).anchors().getFirst().destroyed());

		CompoundTag legacy = destroyed.copy();
		legacy.putBoolean("anchor_hit_absorbed", false);
		assertTrue(decode(legacy).anchors().getFirst().destroyed(),
				"Obsolete first-hit latch keys must be ignored without invalidating the save");
	}

	private static WorldInterfaceState.Snapshot snapshot(boolean oneDestroyed) {
		List<WorldInterfaceState.Gate> gates = new ArrayList<>();
		for (int index = 0; index < WorldInterfaceState.GATE_COUNT; index++) {
			gates.add(new WorldInterfaceState.Gate(index, new BlockPos(index * 5, 70, 90),
					WorldInterfaceGatewayState.DORMANT));
		}
		return new WorldInterfaceState.Snapshot(true, true, Optional.of(ENCOUNTER_ID), 7L,
				WorldInterfaceStage.ARENA_READY, WorldInterfaceState.Outcome.NONE, 1, "minecraft:the_end",
				BlockPos.ZERO, new BlockPos(0, 65, 0), new BlockPos(0, 65, 8), 0,
				gates, anchors(oneDestroyed), Set.of(), Map.of(), false, Optional.empty(),
				0.0D, 0.0D, 0L, -1L, 0L, Optional.empty(),
				0L, 0L, 0, 0L, -1L, Map.of(), 0, 0, Map.of(), Map.of(), List.of(),
				Optional.empty(), new BlockPos(0, 65, 0), false, 0, -1L, -1L);
	}

	private static List<WorldInterfaceState.Anchor> anchors(boolean oneDestroyed) {
		List<WorldInterfaceState.Anchor> anchors = new ArrayList<>();
		for (int index = 0; index < WorldInterfaceState.ANCHOR_COUNT; index++) {
			anchors.add(new WorldInterfaceState.Anchor(index, new BlockPos(index * 7, 72 + index, -40),
					Optional.of(UUID.nameUUIDFromBytes(
							("stability-anchor-" + index).getBytes(StandardCharsets.UTF_8))),
					oneDestroyed && index == 0));
		}
		return anchors;
	}

	private static CompoundTag encode(WorldInterfaceState.Snapshot snapshot) {
		return (CompoundTag) invoke("encode", WorldInterfaceState.Snapshot.class, snapshot);
	}

	private static WorldInterfaceState.Snapshot decode(CompoundTag tag) {
		return (WorldInterfaceState.Snapshot) invoke("decode", CompoundTag.class, tag);
	}

	private static Object invoke(String name, Class<?> parameterType, Object argument) {
		try {
			Method method = WorldInterfaceState.class.getDeclaredMethod(name, parameterType);
			method.setAccessible(true);
			return method.invoke(null, argument);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError("World-interface persistence invocation failed", exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("World-interface persistence method is unavailable", exception);
		}
	}

	private static JsonObject json(Path path) throws Exception {
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static String read(Path path) throws Exception {
		return Files.readString(path, StandardCharsets.UTF_8);
	}
}
