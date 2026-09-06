package com.xm.thefourthfrequency.ending;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xm.thefourthfrequency.entity.WorldInterfaceAnatomy;
import com.xm.thefourthfrequency.entity.WorldInterfaceRig;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exported Blockbench geometry carries the skeleton the server poses, exactly.
 *
 * <p>The hit boxes stand on {@link WorldInterfaceRig}'s bones. The drawn model is baked from
 * {@code world_interface.json}, which is exported from a Blockbench file an artist edits. Nothing
 * at runtime checks that the two agree: a bone the artist nudged, renamed or dropped would compile,
 * pass every other test, and put a skull somewhere the player cannot hit it - or throw on the
 * first render. So the agreement is asserted here, bone by bone, against the rig's bind pose.
 *
 * <p>Read straight from the JSON rather than through the client loader, because this source set
 * cannot construct client classes; the loader's own parse is exercised by the client GameTests.
 */
final class WorldInterfaceGeometryContractTest {
	static final Path GEOMETRY = Path.of(
			"src/main/resources/assets/thefourthfrequency/models/entity/world_interface.json");
	static final Path BBMODEL = Path.of("docs/art/world_interface/world_interface.bbmodel");
	private static final Path MODEL = Path.of(
			"src/client/java/com/xm/thefourthfrequency/client_render/WorldInterfaceModel.java");
	/** Bones the renderer resolves by name in the model constructor, beyond the rig's. */
	private static final String[] RENDERER_BONES = {"shell_base", "phase_2_accretion",
			"phase_3_accretion", "kernel_glow"};
	private static final float POSE_TOLERANCE = 0.002F;

	record Bone(String name, String parent, float[] pose, int cubes) {
	}

	static Map<String, Bone> bones() throws Exception {
		JsonObject root = JsonParser.parseString(Files.readString(GEOMETRY, StandardCharsets.UTF_8))
				.getAsJsonObject();
		Map<String, Bone> bones = new LinkedHashMap<>();
		for (JsonElement element : root.getAsJsonArray("bones")) {
			JsonObject bone = element.getAsJsonObject();
			JsonArray pose = bone.getAsJsonArray("pose");
			float[] values = new float[pose.size()];
			for (int index = 0; index < values.length; index++) values[index] = pose.get(index).getAsFloat();
			JsonElement parent = bone.get("parent");
			String name = bone.get("name").getAsString();
			assertTrue(bones.put(name, new Bone(name,
					parent == null || parent.isJsonNull() ? null : parent.getAsString(), values,
					bone.getAsJsonArray("cubes").size())) == null, "duplicate bone " + name);
		}
		return bones;
	}

	static int[] textureSize() throws Exception {
		JsonArray texture = JsonParser.parseString(Files.readString(GEOMETRY, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonArray("texture");
		return new int[]{texture.get(0).getAsInt(), texture.get(1).getAsInt()};
	}

	@Test
	void everyRigBoneIsExportedWithItsBindPose() throws Exception {
		Map<String, Bone> bones = bones();
		WorldInterfaceRig.Pose bind = WorldInterfaceRig.bindPose();
		List<String> mismatches = new ArrayList<>();
		for (WorldInterfaceRig.Bone rigBone : bind.bones()) {
			if (rigBone.name.equals(WorldInterfaceRig.ROOT)) continue;
			Bone bone = bones.get(rigBone.name);
			assertNotNull(bone, "rig bone missing from the exported geometry: " + rigBone.name);
			float[] expected = {rigBone.x, rigBone.y, rigBone.z, rigBone.xRot, rigBone.yRot, rigBone.zRot};
			for (int axis = 0; axis < 6; axis++) {
				if (Math.abs(expected[axis] - bone.pose()[axis]) > POSE_TOLERANCE) {
					mismatches.add(rigBone.name + "[" + axis + "] rig=" + expected[axis]
							+ " model=" + bone.pose()[axis]);
				}
			}
			String expectedParent = rigBone.name.equals(WorldInterfaceRig.HOVER) ? null
					: rigBone.parentName();
			assertEquals(expectedParent, bone.parent(), "parent of " + rigBone.name);
		}
		assertTrue(mismatches.isEmpty(), "bones drawn away from where the server boxes them:\n"
				+ String.join("\n", mismatches));
	}

	private static String parentOf(String name, WorldInterfaceRig.Pose pose) {
		// The rig exposes parents only through the composed transform, so recover the parent by
		// the naming scheme the rig and the model share.
		if (name.equals(WorldInterfaceRig.STORM_BODY)) return WorldInterfaceRig.HOVER;
		if (name.equals(WorldInterfaceRig.KERNEL) || name.equals(WorldInterfaceRig.WEAPON)) {
			return WorldInterfaceRig.STORM_BODY;
		}
		for (String head : WorldInterfaceRig.HEAD_PREFIX) {
			if (name.equals(head + "_head_mount")) return WorldInterfaceRig.STORM_BODY;
			if (name.equals(head + "_neck_a")) return head + "_head_mount";
			if (name.equals(head + "_neck_b")) return head + "_neck_a";
			if (name.equals(head + "_skull")) return head + "_neck_b";
			if (name.equals(head + "_jaw")) return head + "_skull";
		}
		for (int index = 0; index < WorldInterfaceRig.TENDRIL_COUNT; index++) {
			if (name.equals("tendril_" + index)) return WorldInterfaceRig.STORM_BODY;
			if (name.equals("tendril_" + index + "_mid")) return "tendril_" + index;
			if (name.equals("tendril_" + index + "_tip")) return "tendril_" + index + "_mid";
		}
		throw new AssertionError("unknown rig bone " + name);
	}

	@Test
	void everyBoneTheRendererResolvesExists() throws Exception {
		Map<String, Bone> bones = bones();
		for (String name : RENDERER_BONES) assertTrue(bones.containsKey(name), "missing bone " + name);
		for (String head : WorldInterfaceRig.HEAD_PREFIX) {
			Bone eye = bones.get(head + "_eye_0");
			assertNotNull(eye, "every head carries one aperture bone");
			assertEquals(head + "_skull", eye.parent(), "the aperture hangs off the skull");
			assertTrue(eye.cubes() > 0 || !bones.values().stream()
							.filter(bone -> (head + "_eye_0").equals(bone.parent())).toList().isEmpty(),
					"the aperture bone must draw something for the emissive pass to light");
		}
		for (int index = 0; index < WorldInterfaceRig.TENDRIL_COUNT; index++) {
			Bone glow = bones.get("tendril_" + index + "_glow");
			assertNotNull(glow, "limb " + index + " has no glow bone");
			assertEquals("tendril_" + index + "_tip_flex_" + WorldInterfaceRig.FLEX_JOINTS_PER_LINK,
					glow.parent(), "The luminous tip must follow the last articulated joint");
		}
		assertEquals("interface_kernel", bones.get("kernel_glow").parent());
		// Bone names carry the chain they belong to; a bare "skull" would be ambiguous to a clip.
		for (String bare : new String[]{"skull", "jaw", "mid", "tip", "neck_a", "neck_b"}) {
			assertFalse(bones.containsKey(bare), "unprefixed bone " + bare);
		}
	}

	/** Parents precede children, so the loader can bake in one pass. */
	@Test
	void bonesAreOrderedParentFirst() throws Exception {
		Set<String> seen = new HashSet<>();
		for (Bone bone : bones().values()) {
			assertTrue(bone.parent() == null || seen.contains(bone.parent()),
					bone.name() + " precedes its parent " + bone.parent());
			seen.add(bone.name());
		}
	}

	/**
	 * Parts drawn per form stay under the model's ceiling.
	 *
	 * <p>Every rotated Blockbench cube exports as its own part, so a detail pass in the editor can
	 * double the part count without anyone noticing until the frame time does. The ceiling is the
	 * model's constant, read from source so the two cannot drift.
	 */
	@Test
	void drawnPartsPerFormStayWithinTheModelBudget() throws Exception {
		Map<String, Bone> bones = bones();
		String model = Files.readString(MODEL, StandardCharsets.UTF_8);
		var stated = java.util.regex.Pattern.compile("MAX_VISIBLE_PARTS = (\\d+)").matcher(model);
		assertTrue(stated.find(), "the model no longer states its part ceiling");
		int budget = Integer.parseInt(stated.group(1));
		Map<String, List<String>> children = new HashMap<>();
		for (Bone bone : bones.values()) {
			children.computeIfAbsent(bone.parent(), key -> new ArrayList<>()).add(bone.name());
		}
		int[] counts = new int[WorldInterfaceAnatomy.FORM_COUNT];
		for (int form = 0; form < counts.length; form++) {
			counts[form] = drawn(children, null, form);
			assertTrue(counts[form] <= budget, "form " + (form + 1) + " draws " + counts[form]
					+ " parts against a ceiling of " + budget);
		}
		assertTrue(counts[0] < counts[1] && counts[1] < counts[2],
				"each morph must reveal more geometry, not less: " + counts[0] + " / " + counts[1] + " / " + counts[2]);
		// Enough to be a model rather than a placeholder.
		assertTrue(counts[0] >= 200, "first form draws only " + counts[0] + " parts");
	}

	private static int drawn(Map<String, List<String>> children, String bone, int form) {
		int total = 0;
		for (String child : children.getOrDefault(bone, List.of())) {
			if (!drawnAtForm(child, form)) continue;
			total += 1 + drawn(children, child, form);
		}
		return total;
	}

	/** Mirror of {@code WorldInterfaceModel.drawnAtForm}, which this source set cannot call. */
	private static boolean drawnAtForm(String bone, int form) {
		if (bone.equals("phase_2_accretion")) return form >= 1;
		if (bone.equals("phase_3_accretion")) return form >= 2;
		if (bone.startsWith("tendril_")) {
			String rest = bone.substring("tendril_".length());
			int end = rest.indexOf('_');
			String index = end < 0 ? rest : rest.substring(0, end);
			try {
				return Integer.parseInt(index) < WorldInterfaceAnatomy.tentacleCount(form);
			} catch (NumberFormatException ignored) {
				return true;
			}
		}
		return true;
	}

	/** The Blockbench source and the exported geometry were written by the same export. */
	@Test
	void exportedGeometryMatchesTheBlockbenchSource() throws Exception {
		JsonObject bbmodel = JsonParser.parseString(Files.readString(BBMODEL, StandardCharsets.UTF_8))
				.getAsJsonObject();
		assertEquals("modded_entity", bbmodel.getAsJsonObject("meta").get("model_format").getAsString());
		assertTrue(bbmodel.getAsJsonObject("meta").get("box_uv").getAsBoolean(),
				"the Java model bakes box UV only");
		int[] texture = textureSize();
		assertEquals(bbmodel.getAsJsonObject("resolution").get("width").getAsInt(), texture[0]);
		assertEquals(bbmodel.getAsJsonObject("resolution").get("height").getAsInt(), texture[1]);
		int cubes = bbmodel.getAsJsonArray("elements").size();
		int exported = bones().values().stream().mapToInt(Bone::cubes).sum();
		assertEquals(cubes, exported, "cube count differs between the bbmodel and the export");
		// Every cube declares a material the painter knows, or it ships painted as nothing.
		Set<String> materials = new TreeSet<>();
		for (JsonElement element : bbmodel.getAsJsonArray("elements")) {
			String name = element.getAsJsonObject().get("name").getAsString();
			assertTrue(name.contains("."), "cube without a material prefix: " + name);
			materials.add(name.substring(0, name.indexOf('.')));
		}
		Set<String> known = Set.of("endstone", "obsidian", "swallowed", "plating", "bone", "root",
				"flesh", "socket", "eye", "core", "glow", "horn", "tooth");
		for (String material : materials) assertTrue(known.contains(material), "unknown material " + material);
	}
}
