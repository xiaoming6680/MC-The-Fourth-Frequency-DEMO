package com.xm.thefourthfrequency.client_ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam between a post-effect chain and the fragment shader it drives.
 *
 * <p>This seam has no compiler and no error message. A chain's {@code uniforms} block is written
 * into a std140 buffer in the order the json lists it; the shader reads that buffer in the order it
 * declares its fields. Nothing checks that the two agree. Rename a uniform, reorder two of them, or
 * change a {@code float} to a {@code vec4} on one side only, and the shader compiles perfectly, the
 * chain loads perfectly, and every value past the first mismatch is read out of the wrong offset -
 * which is to say the screen effect is wrong in a way that looks like a design decision.
 *
 * <p>This mod has already paid for that once. {@code world_interface_edge.fsh} declared vanilla's
 * {@code SamplerInfo} block because vanilla's own {@code invert.fsh} does; it is declared for every
 * post pass but only <em>filled</em> for some, so it read as zeroes, a division collapsed the
 * horizontal term of a radius, and the treatment washed the entire screen violet instead of its
 * border. Nothing was logged. So both halves of that are refused here: names and order have to
 * match, and no shader of ours may trust {@code SamplerInfo}.
 */
final class PostFilterContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static final Path CHAINS = ASSETS.resolve("post_effect");
	private static final Path SHADERS = ASSETS.resolve("shaders/post");
	private static final String NAMESPACE = "thefourthfrequency:";

	/** {@code layout(std140) uniform Name { ... };} with everything between the braces captured. */
	private static final Pattern BLOCK = Pattern.compile(
			"layout\\(std140\\)\\s+uniform\\s+(\\w+)\\s*\\{([^}]*)}");
	private static final Pattern FIELD = Pattern.compile("(\\w+)\\s+(\\w+)\\s*;");

	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
	}

	private static List<Path> chains() throws IOException {
		try (Stream<Path> files = Files.list(CHAINS)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
					.toList();
		}
	}

	/** Field name to glsl type, in declaration order, for one shader's config block. */
	private static Map<String, String> declaredUniforms(Path shader, String blockName)
			throws IOException {
		String source = stripComments(Files.readString(shader, StandardCharsets.UTF_8));
		Matcher blocks = BLOCK.matcher(source);
		while (blocks.find()) {
			if (!blocks.group(1).equals(blockName)) continue;
			Map<String, String> fields = new LinkedHashMap<>();
			Matcher field = FIELD.matcher(blocks.group(2));
			while (field.find()) fields.put(field.group(2), field.group(1));
			return fields;
		}
		return Map.of();
	}

	private static Path shaderFile(String fragmentShaderId) {
		return SHADERS.resolve(fragmentShaderId.substring((NAMESPACE + "post/").length()) + ".fsh");
	}

	/**
	 * Every uniform a chain writes lands on the field the shader reads it from.
	 *
	 * <p>Order is asserted as strictly as names, because std140 packing is positional: the json
	 * builder walks its list appending values, and the shader reads its fields at the offsets their
	 * declaration order gives them. Two uniforms of the same type swapped in one file and not the
	 * other is invisible to every other check in this build.
	 */
	@Test
	void everyChainWritesTheUniformsItsShaderDeclares() throws Exception {
		int checked = 0;
		for (Path chainPath : chains()) {
			JsonObject chain = JsonParser.parseString(
					Files.readString(chainPath, StandardCharsets.UTF_8)).getAsJsonObject();
			for (var passElement : chain.getAsJsonArray("passes")) {
				JsonObject pass = passElement.getAsJsonObject();
				String fragment = pass.get("fragment_shader").getAsString();
				if (!fragment.startsWith(NAMESPACE)) continue;
				Path shader = shaderFile(fragment);
				assertTrue(Files.isRegularFile(shader),
						chainPath.getFileName() + " names a shader that does not exist: " + fragment);
				JsonObject uniforms = pass.getAsJsonObject("uniforms");
				assertTrue(uniforms != null && uniforms.size() == 1,
						chainPath.getFileName() + " must declare exactly one config block");
				String blockName = uniforms.keySet().iterator().next();
				Map<String, String> declared = declaredUniforms(shader, blockName);
				assertFalse(declared.isEmpty(),
						shader.getFileName() + " declares no std140 block called " + blockName);

				List<String> written = new ArrayList<>();
				JsonArray values = uniforms.getAsJsonArray(blockName);
				for (var value : values) {
					JsonObject entry = value.getAsJsonObject();
					String name = entry.get("name").getAsString();
					written.add(name);
					assertEquals(declared.get(name), entry.get("type").getAsString(),
							chainPath.getFileName() + " writes " + name + " as a different type than "
									+ shader.getFileName() + " declares");
				}
				assertEquals(List.copyOf(declared.keySet()), written,
						chainPath.getFileName() + " writes " + blockName
								+ " in a different order than " + shader.getFileName()
								+ " reads it; std140 packing is positional");
				checked++;
			}
		}
		assertTrue(checked >= 15, "expected every mod chain to be covered, saw " + checked);
	}

	/**
	 * Sizes come from {@code Globals.ScreenSize} and never from {@code SamplerInfo}.
	 *
	 * <p>{@code SamplerInfo} is declared for every post pass and filled for only some of them, and an
	 * unfilled std140 block reads as zeroes with nothing logged anywhere. {@code Globals} is filled
	 * unconditionally - {@code GlProgram} keeps its own built-in set and binds it whether the
	 * pipeline declared it or not - so it is the one that can be trusted.
	 */
	@Test
	void noShaderOfOursTrustsTheBlockThatIsNotAlwaysFilled() throws Exception {
		try (Stream<Path> files = Files.list(SHADERS)) {
			for (Path shader : files.toList()) {
				String source = Files.readString(shader, StandardCharsets.UTF_8);
				assertTrue(source.startsWith("#version 330"),
						shader.getFileName() + " must match vanilla's post shader version");
				assertTrue(source.contains("uniform sampler2D InSampler"),
						shader.getFileName() + ": vanilla names a post pass's input InSampler");
				assertFalse(stripComments(source).contains("uniform SamplerInfo"),
						shader.getFileName() + " must not read SamplerInfo; it is not always filled");
				if (stripComments(source).contains("ScreenSize")
						|| stripComments(source).contains("GameTime")) {
					assertTrue(source.contains("#moj_import <minecraft:globals.glsl>"),
							shader.getFileName() + " uses a Globals field without importing the block");
				}
			}
		}
	}

	/**
	 * Nothing coherent in a screen filter changes faster than three hertz.
	 *
	 * <p>The world bible's ceiling, asserted where it is actually decided. {@code HoldTicks} is the
	 * period of every discrete thing the corruption filter does, and 3 Hz is 6.67 ticks, so seven is
	 * the floor. The analog filter has no equivalent field on purpose - everything it does is
	 * continuous - except its grain, which is zero-mean per-pixel noise and therefore not a flash.
	 */
	@Test
	void everyFilterHoldsUnderTheFlickerCeiling() throws Exception {
		int checked = 0;
		for (Path chainPath : chains()) {
			JsonObject chain = JsonParser.parseString(
					Files.readString(chainPath, StandardCharsets.UTF_8)).getAsJsonObject();
			for (var passElement : chain.getAsJsonArray("passes")) {
				JsonObject uniforms = passElement.getAsJsonObject().getAsJsonObject("uniforms");
				if (uniforms == null) continue;
				for (String block : uniforms.keySet()) {
					for (var value : uniforms.getAsJsonArray(block)) {
						JsonObject entry = value.getAsJsonObject();
						String name = entry.get("name").getAsString();
						// Every hold in either shader, whatever it is holding.
						if (!name.contains("Hold")) continue;
						float hold = entry.get("value").getAsFloat();
						assertTrue(hold >= 7.0F, chainPath.getFileName() + " re-rolls every " + hold
								+ " ticks, which is faster than the 3 Hz ceiling");
						checked++;
					}
				}
			}
		}
		assertTrue(checked >= 15, "expected every chain to state its holds, saw " + checked);
	}

	/**
	 * Every chain the client can ask for by name is on disk, and both askers ask for the same four.
	 *
	 * <p>The signal chains are shared between the anomaly burst and the loading screens on purpose:
	 * a player who meets tape damage in the world and then meets it again on a loading screen is
	 * meeting one fault, and two sets of chains would drift into two.
	 */
	@Test
	void everyChainTheClientNamesExists() throws Exception {
		String anomaly = Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
				+ "/client_ui/AnomalyPresentationController.java"), StandardCharsets.UTF_8);
		String loading = Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
				+ "/client_ui/AlphaCorruptionRenderer.java"), StandardCharsets.UTF_8);
		for (int step = 1; step <= 4; step++) {
			assertTrue(anomaly.contains("\"signal_" + step + "\""),
					"the burst must name signal_" + step + " for its signal step");
			assertTrue(loading.contains("\"signal_still_" + step + "\""),
					"the loading screens must name signal_still_" + step + " for their signal step");
			for (String name : new String[]{"signal_" + step, "signal_still_" + step}) {
				assertTrue(Files.isRegularFile(CHAINS.resolve(name + ".json")), "missing chain: " + name);
			}
		}
		// The two families answer to one rule, and it points one way: the surface a player has to
		// READ may never be treated harder, term for term, than the surface they do not.
		//
		// It used to demand they be equal apart from the two motion terms, and that was wrong for the
		// reason the loading screen showed: the moving family is tuned for eighteen ticks of a world
		// nobody is reading, and its Desaturate runs to 0.70 - most of the way to grey. On a red wall
		// of text that is not damage, it is the colour being taken away. So the still family is tuned
		// on its own terms, and what is held is only that it never exceeds the moving one.
		for (int step = 1; step <= 4; step++) {
			Map<String, Float> moving = signalConfig("signal_" + step);
			Map<String, Float> still = signalConfig("signal_still_" + step);
			// Two things a surface that has to be read may never do: bend its own rows every frame,
			// and tear itself into displaced bands. A slow bar sweeping up the picture is neither -
			// it is the mistracking the retired GUI tracking band used to draw, moved into the shader
			// with the rest of the medium - so the roll is allowed and only these two are refused.
			assertEquals(0.0F, still.get("Wobble"), "signal_still_" + step + " must not bend rows");
			assertEquals(0.0F, still.get("TearBands"), "signal_still_" + step + " must not tear");
			for (String term : new String[]{"Strength", "Chroma", "ScanDepth", "Grain", "Halation",
					"Vignette", "Desaturate"}) {
				assertTrue(still.get(term) <= moving.get(term) + 1.0E-6F,
						"signal_still_" + step + " treats " + term + " harder (" + still.get(term)
								+ ") than the burst does (" + moving.get(term) + ")");
			}
			// The two that attack colour rather than the picture. A red wall has to stay red: the
			// medium is allowed to be filthy, it is not allowed to be monochrome.
			assertTrue(still.get("Desaturate") <= 0.12F,
					"signal_still_" + step + " pulls the colour out of a surface meant to be read");
			assertTrue(signalTintAmount("signal_still_" + step) <= 0.12F,
					"signal_still_" + step + " casts hard enough to replace the colour underneath");
			// And it still has to be visibly the same medium, not a clean screen.
			assertTrue(still.get("Grain") > 0.0F && still.get("ScanDepth") > 0.0F
							&& still.get("Chroma") > 0.0F,
					"signal_still_" + step + " stopped being a failing medium at all");
		}
		for (String name : new String[]{"pursuit_low_res", "pursuit_low_res_distant",
				"pursuit_low_res_close", "pursuit_low_res_contact", "world_interface_lock",
				"world_interface_lock_peak", "world_interface_expulsion",
				"world_interface_dispersion", "world_interface_dispersion_far"}) {
			assertTrue(Files.isRegularFile(CHAINS.resolve(name + ".json")), "missing chain: " + name);
		}
		String controller = Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
				+ "/client_ui/WorldInterfacePostEffectController.java"), StandardCharsets.UTF_8);
		for (String name : new String[]{"world_interface_lock", "world_interface_lock_peak",
				"world_interface_expulsion", "world_interface_dispersion",
				"world_interface_dispersion_far"}) {
			assertTrue(controller.contains('"' + name + '"'),
					"the encounter never names its own chain: " + name);
		}
	}

	/**
	 * The dispersion chains stay a lens and never become one of the other two languages.
	 *
	 * <p>Three screen vocabularies is one more than this mod used to have, and the only thing making
	 * them worth having is that a player can tell them apart without being told which is which:
	 * tape damage means the recording is failing, pipeline damage means the rules are, and this one
	 * means something is being fired. The moment the beam treatment starts tearing bands or pulling
	 * the colour out of the frame it has stopped saying its own sentence and started saying one of
	 * the other two, and nothing else in the build would notice - a chain that reads wrong looks
	 * exactly like a chain that was tuned that way on purpose.
	 *
	 * <p>Asserted on the shader as well as on the chains: the refusal is that these terms do not
	 * exist here at all, not that they happen to be set to zero today.
	 */
	@Test
	void theBeamTreatmentStaysOptical() throws Exception {
		String shader = stripComments(Files.readString(
				SHADERS.resolve("chromatic_dispersion.fsh"), StandardCharsets.UTF_8));
		for (String borrowed : new String[]{"TearBands", "BandHeight", "BlockSize", "Levels",
				"Grain", "RollHeight", "Desaturate"}) {
			assertFalse(shader.contains(borrowed),
					"chromatic_dispersion.fsh declares " + borrowed + ", which belongs to one of the"
							+ " two damage languages; a beam is optics, not corruption");
		}
		for (String name : new String[]{"world_interface_dispersion", "world_interface_dispersion_far"}) {
			Map<String, Float> config = dispersionConfig(name);
			// The middle of the screen is where the beam is dodged from. A treatment that reaches it
			// is one the player has to play through rather than one they can read the arena past.
			assertTrue(config.get("CenterClear") >= 0.18F,
					name + " treats the middle of the screen, which is where the shot is dodged");
			assertTrue(config.get("EdgeRadius") > config.get("CenterClear"),
					name + " has no radial ramp at all, so its mask is the whole frame");
			// Colour is separated, never removed. See the shader's Saturate.
			assertTrue(config.get("Saturate") > 0.0F, name + " stopped being a prism");
		}
		// And being the one it is aimed at has to be legible as *more* than watching it happen,
		// term for term, or the two chains are saying the same thing at two strengths by accident.
		Map<String, Float> aimed = dispersionConfig("world_interface_dispersion");
		Map<String, Float> witness = dispersionConfig("world_interface_dispersion_far");
		for (String term : new String[]{"Dispersion", "Barrel", "Bloom", "Jitter", "Vignette"}) {
			assertTrue(witness.get(term) < aimed.get(term),
					"the far chain treats " + term + " at least as hard as the aimed one, so being"
							+ " singled out is not legible from the screen");
		}
		assertTrue(witness.get("CenterClear") > aimed.get("CenterClear"),
				"the far chain reaches at least as far into the middle of the screen as the aimed one");
	}

	private static Map<String, Float> dispersionConfig(String chainName) throws Exception {
		JsonObject chain = JsonParser.parseString(Files.readString(
				CHAINS.resolve(chainName + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
		Map<String, Float> values = new LinkedHashMap<>();
		for (var uniform : chain.getAsJsonArray("passes").get(0).getAsJsonObject()
				.getAsJsonObject("uniforms").getAsJsonArray("DispersionConfig")) {
			JsonObject entry = uniform.getAsJsonObject();
			if (entry.get("value").isJsonPrimitive()) {
				values.put(entry.get("name").getAsString(), entry.get("value").getAsFloat());
			}
		}
		return values;
	}

	private static float signalTintAmount(String chainName) throws Exception {
		JsonObject chain = JsonParser.parseString(Files.readString(
				CHAINS.resolve(chainName + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
		for (var uniform : chain.getAsJsonArray("passes").get(0).getAsJsonObject()
				.getAsJsonObject("uniforms").getAsJsonArray("SignalConfig")) {
			JsonObject entry = uniform.getAsJsonObject();
			if ("Tint".equals(entry.get("name").getAsString())) {
				return entry.getAsJsonArray("value").get(3).getAsFloat();
			}
		}
		return Float.NaN;
	}

	private static Map<String, Float> signalConfig(String chainName) throws Exception {
		JsonObject chain = JsonParser.parseString(Files.readString(
				CHAINS.resolve(chainName + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
		Map<String, Float> values = new LinkedHashMap<>();
		for (var uniform : chain.getAsJsonArray("passes").get(0).getAsJsonObject()
				.getAsJsonObject("uniforms").getAsJsonArray("SignalConfig")) {
			JsonObject entry = uniform.getAsJsonObject();
			if (entry.get("value").isJsonPrimitive()) {
				values.put(entry.get("name").getAsString(), entry.get("value").getAsFloat());
			}
		}
		return values;
	}

	/**
	 * The GUI filter's plates are on disk and are the ones the generator produced.
	 *
	 * <p>They are noise and a gradient - nothing about a wrong one looks wrong in a directory
	 * listing, and the loading screen they belong to is the first thing a new player ever sees.
	 */
	@Test
	void theAnalogPlatesMatchTheirGeneratedManifest() throws Exception {
		JsonObject manifest = JsonParser.parseString(Files.readString(
				Path.of("docs/art/analog_filter/filter_manifest.json"), StandardCharsets.UTF_8))
				.getAsJsonObject();
		assertTrue(manifest.get("note").getAsString().contains("GENERATED"),
				"the manifest must announce that it is generated, or someone will edit it");
		JsonObject plates = manifest.getAsJsonObject("plates");
		assertEquals(plates.size(), manifest.get("plateCount").getAsInt());
		assertFalse(plates.isEmpty(), "the generator recorded no plates");
		for (String name : plates.keySet()) {
			Path plate = ASSETS.resolve("textures/gui/filter").resolve(name + ".png");
			assertTrue(Files.isRegularFile(plate), "missing plate: " + name);
			assertEquals(plates.getAsJsonObject(name).get("bytes").getAsLong(), Files.size(plate),
					name + " on disk is not the file the generator recorded");
		}
		String filter = Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
				+ "/client_ui/AnalogFilter.java"), StandardCharsets.UTF_8);
		for (String name : plates.keySet()) {
			assertTrue(filter.contains('"' + name + '"'), "AnalogFilter never draws " + name);
		}
	}
}
