package com.xm.thefourthfrequency.ending;

import com.xm.thefourthfrequency.entity.WorldInterfaceRig;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bone an animation clip addresses must exist in the model, and no two bones may share a name.
 *
 * <p>This closes the one failure in the encounter that nothing else can catch. A clip binds to its
 * bones by name, at runtime, inside {@code KeyframeAnimation.bake} - which the model constructor
 * calls the first time the entity is rendered. A misspelled or renamed bone therefore compiles
 * cleanly, passes every other test in this suite, and throws the instant a player walks into the
 * End. There is no earlier signal.
 *
 * <p>It matters here specifically because the whole skeleton was renamed at once: three heads and
 * ten limbs previously carried duplicate child names ({@code skull}, {@code mid}, {@code tip}), so
 * every one of those bones had to be prefixed. Duplicates are the subtler half of the same problem -
 * a name lookup resolves to whichever bone it finds first, so two heads called {@code skull} would
 * leave one of them silently unanimatable rather than crashing.
 *
 * <p>Both files are parsed rather than being listed by hand, so this cannot drift out of date with
 * the thing it is checking.
 */
final class WorldInterfaceBoneBindingTest {
	private static final Path CLIENT = Path.of("src/client/java/com/xm/thefourthfrequency/client_render");
	/**
	 * Where the clips live now.
	 *
	 * <p>They moved out of {@code client_render} when the hit boxes stopped standing on the bind pose
	 * and started standing on the animated skeleton: the server has to evaluate the same clips the
	 * client draws, and the server cannot see {@code net.minecraft.client.animation}. The failure
	 * this file guards against is unchanged - a clip binds to its bones by name at bake time - and it
	 * now also guards {@code WorldInterfaceRig}, which poses those same bones for the boxes.
	 */
	private static final Path COMMON = Path.of("src/main/java/com/xm/thefourthfrequency/entity");
	private static final String[] HEADS = {"center", "left", "right"};
	private static final int TENDRILS = 10;

	/**
	 * Bone names in the exported geometry.
	 *
	 * <p>The model used to declare its bones in Java, and this parsed {@code createLayer} for them.
	 * They are authored in Blockbench now and exported to {@code world_interface.json}, which is
	 * what the client bakes - so it is the JSON, not any source file, that has to carry every bone
	 * a clip addresses. Synthetic bones the exporter makes for rotated cubes are named
	 * {@code <bone>/<cube>} and can never collide with an authored name.
	 */
	private static List<String> modelBones() throws Exception {
		return new ArrayList<>(WorldInterfaceGeometryContractTest.bones().keySet());
	}

	/** Bone names addressed by clips in {@code WorldInterfaceClips}. */
	private static Set<String> animatedBones() throws Exception {
		String animations = Files.readString(COMMON.resolve("WorldInterfaceClips.java"),
				StandardCharsets.UTF_8);
		Matcher matcher = Pattern.compile(
				"addAnimation\\(\\s*(.*?),\\s*(?:rotation|scale|translation)\\(", Pattern.DOTALL)
				.matcher(animations);
		Set<String> bones = new LinkedHashSet<>();
		while (matcher.find()) {
			bones.addAll(expand(matcher.group(1).replaceAll("\\s+", " ").trim()));
		}
		assertTrue(bones.size() >= 10, "parsed too few animated bones: " + bones.size());
		return bones;
	}

	/**
	 * The links of a head chain, spelled out rather than pattern-matched.
	 *
	 * <p>A whitelist because the alternative is ambiguous: the procedural clutter generators take a
	 * parameter that is also called {@code prefix}, so {@code prefix + "_" + index} (a mass slab)
	 * and {@code prefix + "_neck_a"} (a head bone) are indistinguishable to a regex that accepts any
	 * suffix. Listing the five links that actually exist removes the guesswork.
	 */
	private static final String[] HEAD_LINKS = {"_head_mount", "_neck_a", "_neck_b", "_skull", "_jaw"};
	/** Limb chain links. The empty string is the limb root itself. */
	private static final String[] LIMB_LINKS = {"", "_mid", "_tip", "_glow"};

	/**
	 * Turns one bone-name expression into the names it actually produces.
	 *
	 * <p>The expressions are deliberately simple - a literal, or a loop variable concatenated with
	 * literals - so expanding them needs no Java parser, only the loop bounds, which are the same
	 * three heads and ten limbs on both sides.
	 *
	 * <p>Bake-time clutter - mass slabs, plating, ribs, teeth, horns, kernel frames, storm knots -
	 * expands to nothing on purpose. No clip addresses any of it, and it cannot collide: every
	 * generator is handed a prefix unique to its shell layer ({@code base_}, {@code p2_},
	 * {@code p3_}) precisely so that it cannot.
	 */
	private static Set<String> expand(String expression) {
		Set<String> names = new LinkedHashSet<>();
		// A plain literal: "storm_body", "center_jaw".
		Matcher literal = Pattern.compile("^\"([a-z0-9_]+)\"$").matcher(expression);
		if (literal.matches()) {
			names.add(literal.group(1));
			return names;
		}
		// A head variable concatenated with one of the known links.
		Matcher head = Pattern.compile(
				"^(?:HEAD_PREFIX\\[head\\]|HEADS\\[head\\]|prefix|head)\\s*\\+\\s*\"([a-z0-9_]+)\"$")
				.matcher(expression);
		if (head.matches()) {
			String suffix = head.group(1);
			for (String link : HEAD_LINKS) {
				if (link.equals(suffix)) {
					for (String prefix : HEADS) names.add(prefix + link);
					return names;
				}
			}
			return names;
		}
		// The one indexed head bone a clip can reach: the aperture. EYES_PER_HEAD is 1.
		if (expression.matches("^(?:prefix|head|HEAD_PREFIX\\[head\\]|HEADS\\[head\\])"
				+ "\\s*\\+\\s*\"_eye_\"\\s*\\+\\s*\\w+$")) {
			for (String prefix : HEADS) names.add(prefix + "_eye_0");
			return names;
		}
		// Limb-prefixed: "tendril_" + index, "tendril_" + index + "_mid".
		Matcher limb = Pattern.compile(
				"^\"tendril_\"\\s*\\+\\s*index(?:\\s*\\+\\s*\"([a-z0-9_]+)\")?$").matcher(expression);
		if (limb.matches()) {
			String suffix = limb.group(1) == null ? "" : limb.group(1);
			for (String link : LIMB_LINKS) {
				if (link.equals(suffix)) {
					for (int index = 0; index < TENDRILS; index++) names.add("tendril_" + index + link);
					return names;
				}
			}
			return names;
		}
		return names;
	}

	@Test
	void everyAnimatedBoneExistsInTheModel() throws Exception {
		Set<String> declared = new LinkedHashSet<>(modelBones());
		Set<String> animated = animatedBones();
		Set<String> missing = new TreeSet<>(animated);
		missing.removeAll(declared);
		assertTrue(missing.isEmpty(),
				"clips address bones the model never builds - this would throw on the first render "
						+ "of the boss, not here: " + missing);
	}

	/**
	 * No two bones share a name.
	 *
	 * <p>Duplicates do not crash; they resolve to whichever bone the lookup reaches first, leaving
	 * the others permanently frozen. Before the rename this file exists to protect, three heads each
	 * owned a bone called {@code skull} and ten limbs each owned {@code mid} and {@code tip}.
	 */
	@Test
	void noTwoBonesShareAName() throws Exception {
		List<String> bones = modelBones();
		Set<String> seen = new LinkedHashSet<>();
		Set<String> duplicates = new TreeSet<>();
		for (String bone : bones) {
			if (!seen.add(bone)) duplicates.add(bone);
		}
		assertTrue(duplicates.isEmpty(),
				"duplicate bone names leave all but the first silently unanimatable: " + duplicates);
	}

	/**
	 * The three head chains are complete and symmetric.
	 *
	 * <p>A clip that leans the centre head has counterparts for the flanks; if one head were missing
	 * a link the storm would animate lopsidedly rather than fail, which is exactly the kind of thing
	 * that survives a play test.
	 */
	@Test
	void allThreeHeadChainsAreBuiltIdentically() throws Exception {
		Set<String> declared = new LinkedHashSet<>(modelBones());
		for (String head : HEADS) {
			for (String link : new String[]{"_head_mount", "_neck_a", "_neck_b", "_skull", "_jaw"}) {
				assertTrue(declared.contains(head + link), "missing bone: " + head + link);
			}
			assertTrue(declared.contains(head + "_eye_0"), "every head carries one aperture");
		}
		// Every limb is a four-link chain, and all ten exist even though only four are drawn at
		// first form: visibility is decided per frame, but the bones have to be there to be hidden.
		for (int index = 0; index < TENDRILS; index++) {
			for (String link : new String[]{"", "_mid", "_tip", "_glow"}) {
				assertTrue(declared.contains("tendril_" + index + link),
						"missing bone: tendril_" + index + link);
			}
		}
		for (String structural : new String[]{"hover", "storm_body", "shell_base",
				"phase_2_accretion", "phase_3_accretion", "interface_kernel", "kernel_glow", "weapon"}) {
			assertTrue(declared.contains(structural), "missing bone: " + structural);
		}
	}

	/**
	 * The bone count the model advertises is the one it actually builds.
	 *
	 * <p>{@code ANIMATED_BONE_COUNT} is documentation that other code and other people read; a
	 * stated count that drifts from the real skeleton is worse than no count at all.
	 */
	@Test
	void theAdvertisedBoneCountMatchesTheSkeleton() throws Exception {
		Set<String> declared = new LinkedHashSet<>(modelBones());
		// The addressable skeleton: structural bones, the three six-link head chains and the ten
		// four-link limbs, plus the layer root, which createLayer is handed rather than declaring.
		Set<String> addressable = new LinkedHashSet<>(List.of("hover", "storm_body", "shell_base",
				"phase_2_accretion", "phase_3_accretion", "interface_kernel", "kernel_glow", "weapon"));
		for (String head : HEADS) {
			for (String link : HEAD_LINKS) addressable.add(head + link);
			addressable.add(head + "_eye_0");
		}
		for (int index = 0; index < TENDRILS; index++) {
			for (String link : LIMB_LINKS) addressable.add("tendril_" + index + link);
			for (String link : List.of("", "_mid", "_tip")) {
				for (int joint = 1; joint <= WorldInterfaceRig.FLEX_JOINTS_PER_LINK; joint++) {
					addressable.add("tendril_" + index + link + "_flex_" + joint);
				}
			}
		}
		for (String bone : addressable) assertTrue(declared.contains(bone), "missing bone: " + bone);
		String model = Files.readString(CLIENT.resolve("WorldInterfaceModel.java"), StandardCharsets.UTF_8);
		Matcher stated = Pattern.compile("ANIMATED_BONE_COUNT = (\\d+)").matcher(model);
		assertTrue(stated.find(), "the model no longer states its bone count");
		assertEquals(Integer.parseInt(stated.group(1)), addressable.size() + 1,
				"ANIMATED_BONE_COUNT has drifted from the skeleton the model actually addresses");
	}
}
