package com.xm.thefourthfrequency;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ResourceContractTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");

	/**
	 * Every GameTest class on disk is actually listed in the entrypoint that runs it.
	 *
	 * <p>A GameTest is discovered by name, not by scanning: a class missing from
	 * {@code src/gametest/resources/fabric.mod.json} compiles, is packaged, and never runs, and the
	 * run reports success for whatever else it did find. That is how six tests covering the anomaly
	 * backfill, the terminal profile and the recovered fragment file sat green-by-absence - the class
	 * existed and looked covered from the source tree, and the only visible symptom was a total that
	 * nobody had a reason to count.
	 *
	 * <p>Checked in one direction only. A name listed here that no longer exists on disk fails the
	 * run loudly at startup, so the silent half is the one worth a test.
	 */
	@Test
	void everyGameTestClassIsRegisteredInTheEntrypointThatRunsIt() throws Exception {
		Path metadata = Path.of("src/gametest/resources/fabric.mod.json");
		JsonObject entrypoints = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject("entrypoints");
		Set<String> server = registeredEntrypoint(entrypoints, "fabric-gametest");
		Set<String> client = registeredEntrypoint(entrypoints, "fabric-client-gametest");
		Path sources = Path.of("src/gametest/java/com/xm/thefourthfrequency/test");
		for (Path file : Files.list(sources).filter(path -> path.toString().endsWith(".java")).toList()) {
			String source = Files.readString(file, StandardCharsets.UTF_8);
			String name = file.getFileName().toString().replace(".java", "");
			String qualified = "com.xm.thefourthfrequency.test." + name;
			if (source.contains("@GameTest")) {
				assertTrue(server.contains(qualified),
						qualified + " declares @GameTest methods that no entrypoint ever runs");
			}
			if (source.contains("implements FabricClientGameTest")) {
				assertTrue(client.contains(qualified),
						qualified + " is a client GameTest that no entrypoint ever runs");
			}
		}
	}

	private static Set<String> registeredEntrypoint(JsonObject entrypoints, String key) {
		Set<String> names = new HashSet<>();
		for (var entry : entrypoints.getAsJsonArray(key)) names.add(entry.getAsString());
		return names;
	}

	@Test
	void bootSplashCatalogRemainsPopulatedAndUsesVanillaYellow() throws Exception {
		String state = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/MenuErosionState.java"),
				StandardCharsets.UTF_8);
		String titleMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/TitleScreenErosionMixin.java"),
				StandardCharsets.UTF_8);
		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject zh = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		Matcher declared = Pattern.compile("(BOOT|EARLY|MID|LATE|RESTORED)\\((\\d+)\\)").matcher(state);
		int stages = 0;
		while (declared.find()) {
			stages++;
			String stage = declared.group(1).toLowerCase(java.util.Locale.ROOT);
			int count = Integer.parseInt(declared.group(2));
			if ("boot".equals(stage)) {
				assertTrue(count >= 4, "Boot splash catalog must retain at least four choices");
			}
			// Every declared line must be translated in both languages, and the count must not drift.
			for (int index = 0; index < count; index++) {
				String key = "splash.thefourthfrequency." + stage + "." + index;
				assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "missing English " + key);
				assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "missing Chinese " + key);
			}
			assertFalse(zh.has("splash.thefourthfrequency." + stage + "." + count),
					"Untracked splash copy for stage " + stage + "; raise its declared count");
		}
		assertEquals(5, stages, "Every erosion stage must declare a splash count");
		assertTrue(titleMixin.contains("splash = new SplashRenderer"));
		assertTrue(titleMixin.contains("VANILLA_SPLASH_YELLOW = 0xFFFF00"));
		assertTrue(titleMixin.contains("withColor(VANILLA_SPLASH_YELLOW)"));
		assertTrue(titleMixin.contains("MenuErosionState.sessionSplashKey()"));
		assertTrue(titleMixin.contains("REALMS_BUTTON_KEY = \"menu.online\""),
				"Realms must be matched by translation key so it stays disabled in every language");
		assertFalse(titleMixin.contains("toLowerCase"),
				"Title-screen buttons must not be matched by localized label text");
		assertFalse(titleMixin.contains("renderBackground"),
				"Returning to the title screen must keep the normal menu background");
	}

	@Test
	void sixItemIconsAndThreePanelsHaveExactPixelDimensions() throws Exception {
		for (int index = 0; index < 6; index++) {
			var image = ImageIO.read(ASSETS.resolve("textures/item/old_terminal_" + index + ".png").toFile());
			assertEquals(32, image.getWidth());
			assertEquals(32, image.getHeight());
			assertTrue(image.getColorModel().hasAlpha());
			for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
				int alpha = image.getRGB(x, y) >>> 24;
				assertTrue(alpha == 0 || alpha == 255, "non-binary icon alpha at " + index + ":" + x + "," + y);
			}
		}
		for (int stage = 0; stage < 3; stage++) {
			byte[] normal = Files.readAllBytes(ASSETS.resolve("textures/item/old_terminal_" + stage + ".png"));
			byte[] unread = Files.readAllBytes(ASSETS.resolve("textures/item/old_terminal_" + (stage + 3) + ".png"));
			assertNotEquals(java.util.Arrays.hashCode(normal), java.util.Arrays.hashCode(unread));
		}
		for (int stage = 0; stage < 3; stage++) {
			var image = ImageIO.read(ASSETS.resolve("textures/gui/terminal/panel_" + stage + ".png").toFile());
			assertEquals(512, image.getWidth());
			assertEquals(256, image.getHeight());
		}
	}

	@Test
	void reworkBodyUsesThreeDistinctOpaqueBasesAndTwoSparseEmissiveMasks() throws Exception {
		Path entityTextures = ASSETS.resolve("textures/entity");
		Set<Integer> baseHashes = new HashSet<>();
		for (int stage = 1; stage <= 3; stage++) {
			Path path = entityTextures.resolve("rework_body_stage_" + stage + ".png");
			assertTrue(Files.isRegularFile(path), path.toString());
			var image = ImageIO.read(path.toFile());
			assertEquals(256, image.getWidth());
			assertEquals(256, image.getHeight());
			for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
				assertEquals(255, image.getRGB(x, y) >>> 24,
						"base texture must be opaque at stage " + stage + ":" + x + "," + y);
			}
			assertTrue(baseHashes.add(java.util.Arrays.hashCode(Files.readAllBytes(path))),
					"duplicate base texture at stage " + stage);
		}
		assertEquals(3, baseHashes.size());

		Set<Integer> emissiveHashes = new HashSet<>();
		for (int stage = 2; stage <= 3; stage++) {
			Path path = entityTextures.resolve("rework_body_stage_" + stage + "_emissive.png");
			assertTrue(Files.isRegularFile(path), path.toString());
			var image = ImageIO.read(path.toFile());
			assertEquals(256, image.getWidth());
			assertEquals(256, image.getHeight());
			assertTrue(image.getColorModel().hasAlpha());
			int transparent = 0;
			int visible = 0;
			int maxAlpha = 0;
			for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
				int alpha = image.getRGB(x, y) >>> 24;
				if (alpha == 0) {
					transparent++;
					continue;
				}
				visible++;
				maxAlpha = Math.max(maxAlpha, alpha);
				boolean faceOrMouth = x >= 140 && x < 246 && y >= 0 && y < 66;
				boolean torsoCrack = stage == 3 && x >= 0 && x < 54 && y >= 0 && y < 48;
				boolean exposedSpine = stage == 3 && x >= 198 && x < 252 && y >= 128 && y < 202;
				assertTrue(faceOrMouth || torsoCrack || exposedSpine,
						"emissive escaped approved UV islands at stage " + stage + ":" + x + "," + y);
			}
			assertTrue(transparent > 64_000, "emissive background must remain overwhelmingly transparent");
			assertTrue(visible > 0 && visible < 1_500, "emissive coverage must remain sparse");
			assertTrue(maxAlpha >= 64 && maxAlpha <= 128, "emissive alpha must remain dim");
			assertTrue(emissiveHashes.add(java.util.Arrays.hashCode(Files.readAllBytes(path))),
					"duplicate emissive texture at stage " + stage);
		}
		assertEquals(2, emissiveHashes.size());
		assertFalse(Files.exists(entityTextures.resolve("rework_body.png")),
				"legacy single-form texture must not remain in the runtime pack");
	}

	@Test
	void customModelDispatchCoversEveryProjectionValue() throws Exception {
		JsonObject root = JsonParser.parseString(Files.readString(ASSETS.resolve("items/old_terminal.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		// The flat projection is no longer the whole definition. Every view except the inventory slot
		// now draws the 3D shell, so the six stage-and-unread icons sit under the display-context
		// case that keeps them. Both branches dispatch on the same six values from the same slot,
		// which is what lets the item in hand and the icon in the bag never disagree about the form.
		JsonObject flat = root.getAsJsonObject("model").getAsJsonArray("cases").get(0)
				.getAsJsonObject().getAsJsonObject("model");
		JsonObject shell = root.getAsJsonObject("model").getAsJsonObject("fallback");
		for (var branch : new JsonObject[]{flat, shell}) {
			assertEquals(0, branch.get("index").getAsInt(),
					"both projections must stay in custom model data slot 0");
			assertEquals(5, branch.getAsJsonArray("entries").size());
		}
		var entries = flat.getAsJsonArray("entries");
		var shellEntries = shell.getAsJsonArray("entries");
		for (int index = 0; index < 5; index++) {
			assertEquals(index + 1.0F, entries.get(index).getAsJsonObject().get("threshold").getAsFloat());
			assertEquals(index + 1.0F, shellEntries.get(index).getAsJsonObject().get("threshold").getAsFloat());
			assertTrue(Files.isRegularFile(ASSETS.resolve("models/item/old_terminal_" + (index + 1) + ".json")));
			assertTrue(Files.isRegularFile(ASSETS.resolve("models/item/old_terminal_held_" + (index + 1) + ".json")));
		}
	}

	@Test
	void everyTerminalSoundIsAProjectOwnedOgg() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		for (String event : new String[]{"terminal_click", "terminal_tune", "terminal_lock",
				"terminal_anomaly"}) {
			assertTrue(sounds.has(event), event);
		}
		for (Path path : Files.walk(ASSETS.resolve("sounds/device/terminal")).filter(Files::isRegularFile).toList()) {
			byte[] header = Files.readAllBytes(path);
			assertTrue(header.length > 4 && header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S', path.toString());
		}
		for (String event : new String[]{"terminal_click", "terminal_tune", "terminal_lock",
				"terminal_fault", "terminal_anomaly"}) {
			for (var sound : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
				String name = sound.isJsonPrimitive() ? sound.getAsString()
						: sound.getAsJsonObject().get("name").getAsString();
				String localName = name.substring(name.indexOf(':') + 1);
				assertTrue(Files.isRegularFile(ASSETS.resolve("sounds/" + localName + ".ogg")), name);
			}
		}
	}

	@Test
	void alphaLoadingCorruptionUsesDedicatedNonLoopingOggCues() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		String generator = Files.readString(Path.of("tools/generate_alpha_corruption_audio.py"),
				StandardCharsets.UTF_8);
		assertTrue(generator.contains("79.0 * time"));
		assertTrue(generator.contains("211.0 * time"));
		assertTrue(generator.contains("COLLAPSE_PEAK = 10 ** (-1.5 / 20.0)"));
		assertTrue(generator.contains("311.0 * index / buffer_samples"));
		assertFalse(generator.contains("+ time * 170.0"),
				"The full-screen failure cue must not rise in pitch before its stuck buffer");
		// Each variant is a different *kind* of hang rather than the same one re-rolled, which
		// is the whole reason a set exists: the pursuit freeze replays these every five ticks
		// and a repeated sample stops being a machine failing and becomes a recognisable effect.
		for (String recipe : new String[]{"def warning_relay_chatter(", "def warning_tape_dip(",
				"def collapse_driver_stall(", "def collapse_bit_decay("}) {
			assertTrue(generator.contains(recipe), recipe);
		}
		for (String event : new String[]{"alpha_corruption_warning", "alpha_corruption_collapse"}) {
			assertTrue(sounds.has(event), event);
			// These two are events, not ambience: one warns that the downgrade is coming apart
			// and the other is the failure itself. Withholding their subtitles did not protect
			// any atmosphere, it just meant a player reading captions got no warning at all.
			assertTrue(sounds.getAsJsonObject(event).has("subtitle"), event);
			var variants = sounds.getAsJsonObject(event).getAsJsonArray("sounds");
			assertTrue(variants.size() >= 3,
					event + " must keep at least three variants; one sample replayed through a"
							+ " freeze reads as a sound effect, not as a failure");
			Set<String> distinct = new HashSet<>();
			for (var sound : variants) {
				String name = sound.getAsString();
				assertTrue(distinct.add(name), name + " is listed twice");
				Path path = ASSETS.resolve("sounds/"
						+ name.substring(name.indexOf(':') + 1) + ".ogg");
				byte[] header = Files.readAllBytes(path);
				assertTrue(header.length > 16_000, path.toString());
				assertEquals('O', header[0]);
				assertEquals('g', header[1]);
				assertEquals('g', header[2]);
				assertEquals('S', header[3]);
			}
		}
	}

	@Test
	void analogHorrorSignalBedsArePresentLoopableAndUnsubtitled() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		String generator = Files.readString(Path.of("tools/generate_signal_bed_audio.py"),
				StandardCharsets.UTF_8);
		// The two-tone attention signal borrows its authority from the real Emergency Alert
		// System frequencies; retuning them would quietly discard that association.
		assertTrue(generator.contains("853.0 * time"));
		assertTrue(generator.contains("960.0 * time"));
		// Beds must stay far below the event sounds or they stop being deniable.
		assertTrue(generator.contains("BED_PEAK = 10 ** (-24.0 / 20.0)"));
		assertTrue(generator.contains("def seamless("),
				"loop beds depend on the head/tail cross-fade to wrap without a click");

		// Only the continuous beds stay uncaptioned. Deniability is a property of something that
		// is always there - a subtitle would confirm the hiss is real, which is the one thing it
		// must never do. The three one-shots are the opposite: they fire because something
		// specific just happened, and signal_alert in particular is a pursuit warning, so
		// withholding their captions denied deaf players information rather than atmosphere.
		for (String event : new String[]{"signal_carrier", "signal_static", "signal_tape_hiss",
				"signal_dead_air"}) {
			assertTrue(sounds.has(event), event);
			assertFalse(sounds.getAsJsonObject(event).has("subtitle"),
					event + " is an environmental bed and must not explain itself through subtitles");
			for (var sound : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
				String name = sound.isJsonPrimitive() ? sound.getAsString()
						: sound.getAsJsonObject().get("name").getAsString();
				Path path = ASSETS.resolve("sounds/" + name.substring(name.indexOf(':') + 1) + ".ogg");
				byte[] header = Files.readAllBytes(path);
				assertTrue(header.length > 8_000, path.toString());
				assertEquals('O', header[0]);
				assertEquals('g', header[1]);
				assertEquals('g', header[2]);
				assertEquals('S', header[3]);
			}
		}

		for (String event : new String[]{"signal_alert", "signal_carrier_lost", "signal_tuning_sweep"}) {
			assertTrue(sounds.has(event), event);
			assertTrue(sounds.getAsJsonObject(event).has("subtitle"),
					event + " is a one-shot event and must be captioned");
		}

		// The beds run on MASTER specifically so silent_world, which mutes the ambient family,
		// cannot silence them: the signal is not part of the world it is transmitted over.
		String bed = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/SignalBedController.java"),
				StandardCharsets.UTF_8);
		assertTrue(bed.contains("SoundSource.MASTER"));
		assertTrue(bed.contains("canStartSilent"));
		// MASTER leaves the game's own sliders unable to reach the beds without taking everything
		// else down too, so the mod has to supply the trim itself.
		assertTrue(bed.contains("effectiveBedVolume"),
				"the beds must read their own volume trim, not the shared peak volume");
	}

	/**
	 * The sky monitor's corruption is confined to the weather tool's own card.
	 *
	 * <p>Three separate promises are pinned here because all three are invisible in review and
	 * expensive to rediscover: the presentation cannot reach the rest of the screen, it cannot
	 * pretend to be a crash, and every fault string it can draw actually exists in both languages.
	 * The first is the one that keeps the page tabs - the player's way out - legible during a
	 * burst.</p>
	 */
	@Test
	void theSkyMonitorStaysInsideTheWeatherToolAndSpeaksAsAnInstrument() throws Exception {
		String renderer = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/SkyInstrumentRenderer.java"),
				StandardCharsets.UTF_8);
		// Full-viewport helpers would put tearing and snow over the tabs and the close hint.
		assertFalse(renderer.contains("guiWidth"),
				"the sky monitor must not reach outside the weather tool's card");
		assertFalse(renderer.contains("guiHeight"),
				"the sky monitor must not reach outside the weather tool's card");
		assertTrue(renderer.contains("enableScissor"),
				"torn rows are displaced horizontally and must be clipped to the card");
		assertTrue(renderer.contains("disableScissor"));

		JsonObject zh = JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/zh_cn.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject en = JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		String prefix = "terminal.thefourthfrequency.tool.weather.";
		for (String fault : new String[]{"no_carrier", "rejected", "saturated", "phase_lost",
				"resync", "dome_timeout", "star_underflow", "clock_mismatch"}) {
			assertTrue(renderer.contains('"' + fault + '"'),
					fault + " is translated but the flood can never draw it");
			assertTrue(zh.has(prefix + "fault." + fault), fault);
			assertTrue(en.has(prefix + "fault." + fault), fault);
		}
		for (String channel : new String[]{"zenith", "horizon", "stars", "phase", "saturated"}) {
			assertTrue(zh.has(prefix + "channel." + channel), channel);
			assertTrue(en.has(prefix + "channel." + channel), channel);
		}
		// The line that carries "N minutes until dark" may fail visibly, but never silently.
		assertTrue(zh.has(prefix + "lost"));
		assertTrue(en.has(prefix + "lost"));

		// A fabricated stack trace would both stand in for a rule prompt and convince players the
		// game had crashed. The faults are instrument language and have to stay that way.
		for (String forbidden : new String[]{"Exception", "at com.", "Caused by", "Traceback"}) {
			assertFalse(en.get(prefix + "fault.no_carrier").getAsString().contains(forbidden));
			assertFalse(renderer.contains('"' + forbidden), forbidden);
		}
	}

	@Test
	void allJsonParsesAndLanguageKeySetsMatch() throws Exception {
		for (Path path : Files.walk(Path.of("src/main/resources")).filter(value -> value.toString().endsWith(".json")).toList()) {
			JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
		}
		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject zh = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(new HashSet<>(en.keySet()), new HashSet<>(zh.keySet()));
		assertEquals("可选调查：【%s】处发现可疑信号",
				zh.get("terminal.thefourthfrequency.signal.event.fragment_candidate").getAsString());
		assertEquals("终端传来剧烈震动",
				zh.get("message.thefourthfrequency.pursuit.warning").getAsString());
		assertFalse(zh.has("message.thefourthfrequency.pursuit.warning.1"));
		for (int index = 0; index < 14; index++) {
			assertFalse(zh.get("terminal.thefourthfrequency.structure." + index).getAsString().isBlank());
			assertFalse(en.get("terminal.thefourthfrequency.structure." + index).getAsString().isBlank());
		}
		JsonObject terminologyCopy = zh.deepCopy();
		terminologyCopy.remove("screen.thefourthfrequency.first_run_notice.body.control");
		// The unrendered layer's entity alert. 异象 is the mod's word for the anomaly system, and this
		// line is not about that system: it is an instrument reporting a signal it cannot resolve, in
		// the same register as the rest of the terminal's readouts, and it is what the copy was asked
		// to say. Naming it 异象 would also tell the player which system the thing hunting them belongs
		// to, which is exactly the fact the layer withholds.
		terminologyCopy.remove("message.thefourthfrequency.unrendered.anomalous_signal");
		terminologyCopy.remove("terminal.thefourthfrequency.signal.event.pursuit_warning.approaching");
		for (int form = 1; form <= 5; form++) {
			terminologyCopy.remove("terminal.thefourthfrequency.signal.event.pursuit_warning_" + form);
		}
		// The closing half of that same pair, in the same register and exempt for the same reason: the
		// instrument is reporting a signal it could not resolve, arriving and then gone. Calling it
		// 异象 here would name the system the thing hunting the player belongs to, which is the fact
		// the whole sequence withholds.
		terminologyCopy.remove("terminal.thefourthfrequency.signal.event.pursuit_survived");
		terminologyCopy.remove("terminal.thefourthfrequency.signal.event.pursuit_survived.cleared");
		assertFalse(terminologyCopy.toString().contains("异常"),
				"Chinese gameplay terminology must consistently use 异象 outside explicitly requested system copy");
		assertFalse(zh.toString().contains("缓存"), "The player-facing FILES system must not retain the old cache wording");
		assertFalse(zh.toString().contains("补页"), "The removed supplementary document must not remain in Chinese copy");
		assertFalse(en.toString().contains("Loose Page"),
				"The removed supplementary document must not remain in English copy");
		assertEquals("终端备忘",
				zh.get("terminal.thefourthfrequency.file.maintenance_handoff.title").getAsString());
		// Naming the four pages is the walkthrough's job, not the memo's. The memo used to repeat that
		// list verbatim, which put it in front of the player twice - once while the walkthrough was
		// pointing at each page as it named it, and again in a file that only appears at binding, long
		// after the walkthrough is over. The contract follows the copy to its one remaining home
		// rather than loosening: the four-page terminal must still be explained somewhere, and it must
		// still not be explained as the obsolete tuning device.
		for (String page : new String[]{"home", "tools", "records", "files"}) {
			assertFalse(zh.get("terminal.thefourthfrequency.onboarding.brief." + page).getAsString().isBlank(),
					"Every page of the four-page terminal must introduce itself during onboarding");
			assertFalse(en.get("terminal.thefourthfrequency.onboarding.brief." + page).getAsString().isBlank(),
					"Every page of the four-page terminal must introduce itself during onboarding");
		}
		assertTrue(zh.get("terminal.thefourthfrequency.onboarding.brief.home").getAsString()
				.contains("当前目标"),
				"The walkthrough must explain the current four-page terminal instead of obsolete tuning");
		assertFalse(zh.has("terminal.thefourthfrequency.file.maintenance_handoff.4"),
				"The memo is three lines; a fourth key would be an orphan the catalogue never renders");
		assertFalse(en.has("terminal.thefourthfrequency.file.maintenance_handoff.4"),
				"The memo is three lines; a fourth key would be an orphan the catalogue never renders");
		assertEquals("接收到新文件：%s",
				zh.get("message.thefourthfrequency.file.discovered").getAsString());
		assertEquals("接收到来自【%s】共享的第 %s 份破损文件",
				zh.get("message.thefourthfrequency.fragment.received").getAsString(),
				"Both senders pass the fragment number, so the copy must actually show it");
		assertEquals("已发现并共享第 %s 份破损文件",
				zh.get("message.thefourthfrequency.fragment.shared").getAsString());
		assertEquals("接收到 %s 份共享的破损文件",
				zh.get("message.thefourthfrequency.fragment.received_batch").getAsString(),
				"A late owner catching up on old shares must get one counted line, not a replay");
		assertEquals("接收到 %s 份新文件",
				zh.get("message.thefourthfrequency.file.discovered_batch").getAsString());
		assertEquals(List.of("避难所日记", "观测点日记", "矿站日记", "仓库日记"),
				List.of("surface_shelter_record", "field_observation_record",
								"underground_mine_record", "abandoned_warehouse_record").stream()
						.map(id -> zh.get("terminal.thefourthfrequency.file." + id + ".title").getAsString())
						.toList());
		assertEquals("文件内容损坏，已尝试还原",
				zh.get("terminal.thefourthfrequency.file.damaged.notice").getAsString());
		assertFalse(zh.toString().contains("碎片1"));
		assertEquals(List.of("", "加", "加密", "加密日", "加密日记"),
				java.util.stream.IntStream.rangeClosed(0, 4)
						.mapToObj(stage -> zh.get("terminal.thefourthfrequency.file.encrypted_witness_file.revealed."
								+ stage).getAsString()).toList());
		assertEquals(List.of("", "Encrypted ", "Encrypted Witness ",
						"Encrypted Witness Journal ", "Encrypted Witness Journal File"),
				java.util.stream.IntStream.rangeClosed(0, 4)
						.mapToObj(stage -> en.get("terminal.thefourthfrequency.file.encrypted_witness_file.revealed."
								+ stage).getAsString()).toList());
		assertEquals("近场接收器",
				zh.get("terminal.thefourthfrequency.receiver.label").getAsString());
		assertEquals("待机",
				zh.get("terminal.thefourthfrequency.receiver.standby").getAsString());
		assertFalse(zh.has("message.thefourthfrequency.guidance.accepted"),
				"The terminal is the record; a notice that only says it recorded something is noise");
		for (String retired : List.of("terminal.thefourthfrequency.band.weather",
				"terminal.thefourthfrequency.band.mining", "terminal.thefourthfrequency.band.public",
				"terminal.thefourthfrequency.band.unknown", "terminal.thefourthfrequency.objective.calibrate",
				"terminal.thefourthfrequency.tuning.auto", "terminal.thefourthfrequency.tuning.manual",
				"terminal.thefourthfrequency.signal.feed.empty",
				"terminal.thefourthfrequency.signal.navigation_prefix",
				"terminal.thefourthfrequency.signal.marker.unrecorded")) {
			assertFalse(zh.has(retired), "Retired fixed-band copy must stay absent: " + retired);
		}
		assertEquals("选取文件来查看",
				zh.get("terminal.thefourthfrequency.file.select_prompt").getAsString());
		assertEquals("文件", zh.get("terminal.thefourthfrequency.tab.files").getAsString());
		assertEquals("文件", zh.get("terminal.thefourthfrequency.tab.log").getAsString());
		assertEquals("在合适时机自动探测最近的结构",
				zh.get("terminal.thefourthfrequency.tool.navigation.summary").getAsString());
		assertEquals("自动记录你的重生点",
				zh.get("terminal.thefourthfrequency.tool.home.summary").getAsString());
		assertEquals("只报告真实听见的矿，越稀有听得越近。",
				zh.get("terminal.thefourthfrequency.tool.minerals.summary").getAsString());
		assertEquals("探测中",
				zh.get("terminal.thefourthfrequency.tool.minerals.scanning").getAsString());
		assertEquals("范围内无读数",
				zh.get("terminal.thefourthfrequency.tool.minerals.not_found").getAsString());
		assertEquals("勘测到高价值矿物",
				zh.get("terminal.thefourthfrequency.tool.minerals.nearby").getAsString());
		assertEquals("勘测到高价值矿物",
				zh.get("terminal.thefourthfrequency.tool.minerals.nearby_short").getAsString());
		assertEquals("探测",
				zh.get("terminal.thefourthfrequency.tool.minerals.refresh").getAsString());
		assertEquals("探测失败",
				zh.get("message.thefourthfrequency.guidance.not_found").getAsString());
		assertEquals("勘测到高价值矿物",
				zh.get("message.thefourthfrequency.guidance.nearby").getAsString());
		assertEquals("已到达矿物附近，导航已结束",
				zh.get("message.thefourthfrequency.navigation.mineral_arrived").getAsString());
		assertEquals("已接近%s（在你%s侧），导航结束",
				zh.get("message.thefourthfrequency.navigation.structure_nearby").getAsString());
		assertFalse(zh.has("message.thefourthfrequency.terminal.unread"));
		assertEquals("你有【%s】条未读记录",
				zh.get("message.thefourthfrequency.terminal.unread_reminder").getAsString());
		assertFalse(zh.has("message.thefourthfrequency.task.completed"),
				"Completion folded into the reward line it always preceded");
		// Names the task, because nothing was pressed to earn it: the first one pays out while the
		// player is still inside the first-boot walkthrough, and "claimed bread ×6" on its own is an
		// effect with no stated cause. The name only, not the objective line - this has to fit on one
		// line above the hotbar.
		assertEquals("任务完成：%s · %s ×%s",
				zh.get("message.thefourthfrequency.task.completed_reward_claimed").getAsString());
		assertEquals("认识终端", zh.get("terminal.thefourthfrequency.task.name.learn_terminal").getAsString());
		assertFalse(zh.has("message.thefourthfrequency.terminal.stock_zero"),
				"The empty rack folded into the single dispense line it always followed");
		assertEquals("零号站给了你一台个人终端；这里已经没有备用的了。",
				zh.get("message.thefourthfrequency.terminal.dispensed").getAsString());
		for (var entry : zh.entrySet()) {
			if (!entry.getKey().startsWith("message.thefourthfrequency.")
					&& !entry.getKey().startsWith("terminal.thefourthfrequency.navigation.")) continue;
			assertFalse(entry.getValue().getAsString().contains("您"),
					"Player-facing copy stays on 你: " + entry.getKey());
		}
		assertEquals("目的地在你%s侧，本次导航结束",
				zh.get("terminal.thefourthfrequency.navigation.completed").getAsString());
		assertEquals("开始导航", zh.get("terminal.thefourthfrequency.tool.guide").getAsString());
		assertEquals("停止导航", zh.get("terminal.thefourthfrequency.tool.stop").getAsString());
		// The signal-card feed and its copy are gone along with the code that never drew them.
		for (String retiredPrefix : List.of("terminal.thefourthfrequency.signal.card.",
				"terminal.thefourthfrequency.structure.location.",
				"terminal.thefourthfrequency.dimension.")) {
			assertTrue(zh.keySet().stream().noneMatch(key -> key.startsWith(retiredPrefix)),
					"Copy for the removed signal-card feed must stay absent: " + retiredPrefix);
			assertTrue(en.keySet().stream().noneMatch(key -> key.startsWith(retiredPrefix)), retiredPrefix);
		}
		for (String abstractTerm : new String[]{"经历连续性", "身份连续性", "身体映射", "关系异常",
				"跨维度连续性", "关系证据", "关系层", "连续性样本", "关系触点",
				"身体生成", "环境连续性", "结构修订", "防线层数", "权限层级"}) {
			assertFalse(zh.entrySet().stream().anyMatch(entry -> entry.getValue().isJsonPrimitive()
					&& entry.getValue().getAsString().contains(abstractTerm)), abstractTerm);
		}
		for (var entry : zh.entrySet()) {
			if (!entry.getKey().startsWith("terminal.thefourthfrequency.file.")
					|| !entry.getKey().endsWith(".title")) continue;
			assertTrue(entry.getValue().getAsString().codePointCount(0, entry.getValue().getAsString().length()) <= 8,
					() -> "File title is too long: " + entry.getKey() + "=" + entry.getValue().getAsString());
		}
		assertEquals("我已了解",
				zh.get("button.thefourthfrequency.first_run_notice.acknowledge").getAsString());
		assertEquals("正在校验信号",
				zh.get("screen.thefourthfrequency.first_run_notice.status.checking").getAsString());
		assertEquals("信号已稳定",
				zh.get("screen.thefourthfrequency.first_run_notice.status.stable").getAsString());
		String noticeCopy = zh.get("screen.thefourthfrequency.first_run_notice.body.control").getAsString()
				+ zh.get("screen.thefourthfrequency.first_run_notice.body.safety").getAsString()
				+ zh.get("screen.thefourthfrequency.first_run_notice.body.f8").getAsString();
		assertTrue(noticeCopy.contains("不是病毒"));
		assertTrue(noticeCopy.contains("不会损坏你的电脑、系统或个人文件"));
		String saveNotice = zh.get("screen.thefourthfrequency.first_run_notice.body.safety_v2").getAsString();
		assertTrue(saveNotice.contains("不可逆") && saveNotice.contains("备份"));
		assertEquals("存档提示",
				zh.get("screen.thefourthfrequency.first_run_notice.section.effects").getAsString());
		assertFalse(zh.has("screen.thefourthfrequency.first_run_notice.recovery_hint"));
		assertFalse(en.has("screen.thefourthfrequency.first_run_notice.recovery_hint"));
		for (String undisclosedOperation : new String[]{"窗口", "记事本", "摄像头", "壁纸", "视频"})
			assertFalse(noticeCopy.contains(undisclosedOperation), undisclosedOperation);
		String displayedNotice = noticeCopy + saveNotice
				+ zh.get("screen.thefourthfrequency.first_run_notice.body.recovery_v3").getAsString();
		for (String spoiler : new String[]{"最终战", "成功或失败", "结局", "末地", "Notepad",
				"壁纸", "已损坏", "封锁", "mobGriefing"})
			assertFalse(displayedNotice.contains(spoiler), spoiler);
		assertTrue(zh.get("screen.thefourthfrequency.first_run_notice.body.f8").getAsString().contains("F8"));
		for (var entry : zh.entrySet()) {
			if (entry.getKey().startsWith("terminal.thefourthfrequency.file.")
					|| entry.getKey().startsWith("terminal.thefourthfrequency.cache.")
					|| entry.getKey().startsWith("text.thefourthfrequency.archive.line.")) {
				assertFalse(entry.getValue().getAsString().stripLeading().startsWith("|"),
						() -> "File prose retained a left-side pipe: " + entry.getKey());
			}
		}
	}

	@Test
	void terminalReworkKeepsFourClientPagesAndIndependentFileScrollState() throws Exception {
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"), StandardCharsets.UTF_8);
		String page = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalPage.java"), StandardCharsets.UTF_8);
		String tool = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalTool.java"), StandardCharsets.UTF_8);
		String runtime = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalRuntimeService.java"),
				StandardCharsets.UTF_8);
		String terminalSnapshot = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalSnapshot.java"),
				StandardCharsets.UTF_8);
		for (String name : new String[]{"HOME", "TOOLS", "RECORDS", "FILES"}) assertTrue(page.contains(name));
		for (String name : new String[]{"HOME", "MINERALS", "PORTAL", "WEATHER", "NAVIGATION", "STRONGHOLD"}) {
			assertTrue(tool.contains(name));
		}
		assertTrue(screen.contains("switch (page)"));
		assertTrue(screen.contains("fileListScroll"));
		assertTrue(screen.contains("fileContentScroll"));
		assertTrue(screen.contains("recordsScrollRow"));
		assertFalse(screen.contains("automaticTuning"));
		assertTrue(screen.contains("updateTools(TerminalToolSnapshotPayload"));
		assertFalse(screen.contains("TerminalControlPayload.SET_AUTO_TUNING"));
		assertTrue(screen.contains("receiverMechanicalInteractive()"));
		assertTrue(screen.contains("receiverGameplayActive()"));
		assertTrue(screen.contains("return tools.receiverAvailable() && !tools.toolsDisabled();"),
				"Nearby side-route tuning must not require opening the navigation detail page");
		assertFalse(runtime.contains("view.selectedTool != TerminalTool.NAVIGATION.slot()"),
				"Server tuning and lock progress must not depend on the selected tool");
		// fragmentLockedSinceTick is written from server.getTickCount(). Measuring it against
		// level.getGameTime() only agrees on a world that has never been reloaded; everywhere else the
		// difference clamps to the maximum, so the panel claimed a lock a full second before the file
		// was granted and never counted up.
		assertTrue(runtime.contains("private static int receiverLockTicks(ServerPlayer player, ViewState view) {"),
				"Lock progress must read its own clock instead of accepting one from the caller");
		assertTrue(runtime.contains("long now = player.level().getServer().getTickCount();"));
		assertFalse(runtime.contains("receiverLockTicks(player, view, now)"),
				"Lock progress must never be measured against the level's game time");
		assertTrue(screen.contains("TerminalUiLayout.RECEIVER_SLIDER"));
		assertTrue(screen.contains("displayedObjectiveFraction"));
		// The bar used to advance by a fixed fraction of the remaining distance once per client tick,
		// which is twenty visible steps a second on something that gains a pixel or two per step. It
		// follows the frame clock now; an exponential follower keeps the rate machine-independent,
		// which is the part a per-tick fraction cannot offer.
		assertTrue(screen.contains("displayedObjectiveFraction = TerminalMotion.catchUp"),
				"The objective bar must follow the frame clock rather than stepping once per tick");
		assertFalse(screen.contains("displayedObjectiveFraction +="),
				"The objective bar must not go back to a per-tick fraction of the remaining distance");
		// GuiGraphics#enableScissor runs the current pose itself, so the page transition hands it the
		// terminal's own 512x256 coordinates. Converting to screen pixels first applies the panel
		// transform twice and clips the entire display away.
		assertTrue(screen.contains("graphics.enableScissor(body.left(), body.top(), body.right(), body.bottom())"),
				"The page transition must clip in panel space, not screen space");
		// Hit testing follows the page the instant it changes; only the drawing lags. Otherwise a
		// control could be clicked at a position it merely appears to occupy mid-slide.
		assertTrue(screen.contains("private void enterPage(TerminalPage next)"));
		assertTrue(screen.contains("motion.beginPageTransition(page, next, nowMillis())"));
		assertTrue(screen.contains("private TerminalMotionState.Control controlAt(double x, double y)"),
				"Hover and click must resolve a control through one shared walk of the layout");

		// The first-boot walkthrough is the single sanctioned exception to "never take the exit
		// away", and it only holds while all four of its conditions hold with it.
		assertTrue(screen.contains("snapshot.onboardingRequired()"),
				"Whether the walkthrough runs is the server's answer, not the client's guess");
		assertTrue(screen.contains("public boolean shouldCloseOnEsc()"),
				"The walkthrough must refuse Escape through vanilla's own gate, not only by swallowing keys");
		assertTrue(screen.contains("!closedByServer && onboardingLocksExit()"),
				"A server-side close must outrank the walkthrough's hold on the exit");
		assertTrue(screen.contains("player.hurtTime > 0"),
				"The walkthrough must release the exit as soon as the player takes damage");
		// hurtTime counts down over ten ticks, so a per-frame sample reads one hit as many. Twenty
		// samples a second has ten times the margin it needs and cannot double-count.
		assertTrue(screen.contains("\t\ttickOnboarding();"),
				"The damage failsafe must be driven from tick(), not from render()");
		assertEquals(1, screen.split("player\\.hurtTime", -1).length - 1,
				"hurtTime must be sampled in exactly one place");
		// The client walks the player to the tabs; it never reports that it finished. Anything that
		// could write task state from here would be a way to claim the reward without the visits.
		assertFalse(screen.contains("TerminalData.ONBOARDING_DONE"),
				"The client must not latch the walkthrough closed; that is the server's to record");
		assertFalse(screen.contains("TerminalData.TERMINAL_PAGE_VISIT_MASK"),
				"The client must not write the task's own progress mask");
		assertTrue(screen.contains("drawTaskReward(graphics, settled ? completedTask.reward()"));
		assertTrue(screen.contains("graphics.renderItem(reward"));
		// The card has to survive the instant its task is paid for. The snapshot that reports the
		// delivery already names the next task, so without the hold the completed one is never drawn
		// at all - the bar the player was watching is replaced by an empty one for an objective they
		// have not read, while the reward lands in their inventory unaccounted for.
		assertTrue(screen.contains("next.objectiveIndex() > snapshot.objectiveIndex()"),
				"The home card must notice the task it was showing being completed and paid for");
		// The hold is what says it; the card must not also spell it out in words. The objective at
		// n/n, the filled bar, the completion colour and the reward box already carry the whole
		// message, and a fifth line beside them was the card still talking after it had finished.
		assertFalse(screen.contains("terminal.thefourthfrequency.home.reward_delivered"),
				"A held completion must not restate the reward the card is already drawing");
		// Rewards are delivered the moment a task completes, so the card is a readout: still drawn,
		// no longer hit-tested. A claim control could only ask for something the player already has,
		// and the packet behind it also paid out every other finished task at once. The server still
		// answers the action for older clients.
		assertTrue(screen.contains("TerminalUiLayout.HOME_TASK"));
		assertFalse(screen.contains("TerminalUiLayout.HOME_TASK.contains"),
				"The task card must not be a click target once nothing on it is claimable");
		assertFalse(screen.contains("send(TerminalControlPayload.CLAIM_TASK_REWARD"),
				"The task card must not offer a claim the automatic delivery has already made");
		assertTrue(screen.contains("TerminalControlPayload.VISIT_PAGE"));
		assertTrue(screen.contains("recommendedPrimaryTool()"));
		assertTrue(screen.contains("tools.mineralSurveyNearby()"));
		assertTrue(screen.contains("terminal.thefourthfrequency.tool.minerals.nearby_short"),
				"The mineral shortcut must use copy that fits its compact card");
		assertTrue(terminalSnapshot.contains("withObfuscated(false)"),
				"Readable damaged-file fragments must explicitly override the surrounding obfuscation style");
		assertTrue(terminalSnapshot.contains("int readableCodePoints = resolved.codePointCount")
						&& terminalSnapshot.contains("int suffixMask = readableCodePoints - prefixMask"),
				"Damaged files must balance each scattered readable fragment with an equal masked character count");
		// The collapse itself moved into TerminalRecordPolicy.listedInRecords, where the unread badge
		// and the write path can read the same rule - the page keeping it to itself is what let the
		// badge promise entries Records was never going to show. TerminalRecordPolicyTest owns the
		// behaviour now; this only pins that the page has not gone back to its own private copy.
		assertTrue(terminalSnapshot.contains("TerminalRecordPolicy.listedInRecords(entry.type(), entry.variant()"),
				"Records must collapse repeated optional investigations through the shared listing rule");
		assertTrue(terminalSnapshot.contains("fragmentLocationName(entry)"));
		assertTrue(terminalSnapshot.contains("withObfuscated(true)"));
		assertTrue(terminalSnapshot.contains("codePoints + 1"),
				"The small glitch must be inserted without replacing any part of the location name");
		assertTrue(screen.contains("HOME_TOOL_DETAIL"));
		assertTrue(screen.contains("HOME_TOOL_CLOSE"));
		assertTrue(screen.contains("returnHomeAfterToolActivation"));
		assertTrue(screen.contains("tool == null || !tools.available(tool)"),
				"Locked tools must be rejected by the shared detail-opening boundary");
		assertFalse(screen.contains("localLockedTool"),
				"Locked tools must not retain a local-only detail state");
		assertTrue(screen.contains("send(TerminalControlPayload.REQUEST_RESCAN"),
				"Mineral refresh must be an explicit server-authoritative button");
		assertFalse(screen.contains("send(TerminalControlPayload.SELECT_RESOURCE"),
				"The client must not offer manual mineral selection");
		assertFalse(screen.contains("send(TerminalControlPayload.SET_HOME"),
				"The client must not offer manual home storage");
		assertTrue(screen.contains("terminal.thefourthfrequency.tool.minerals.refresh"));
		assertFalse(screen.contains("terminal.thefourthfrequency.navigation.side_route"));
		assertFalse(screen.contains("targets.add(selected)"),
				"Selecting a destination must not move it into the first option slot");
		assertTrue(screen.contains("target.sideRoute()"));
		assertTrue(screen.contains("sideRouteGlitchActive(renderAge)"));
		assertTrue(screen.contains("navigationNeedleFlashStartedAt = renderAge"));
		assertTrue(screen.contains("tools.guidanceTool() != null"),
				"The compass target needle must stay hidden until navigation is explicitly started");
		assertTrue(screen.contains("mineralTargetLocated()"));
		assertTrue(screen.contains("\".\".repeat(dots)"));
		assertTrue(screen.contains("drawFittedLine(graphics, lineTwo"));
		assertTrue(screen.contains("navigationOptionBounds"));
		// Structures no longer take all three option slots. The unstable signal is story content and
		// used to vanish without a mark exactly when three structures were available; structures are
		// the ones that can be counted in words instead.
		assertTrue(screen.contains("TOOL_OPTION_SLOTS - (unstable ? 1 : 0)"),
				"The unstable signal must keep a reserved option slot");
		assertTrue(screen.contains("index >= structureSlots"));
		assertTrue(screen.contains("omittedNavigationTargets()"),
				"Destinations the option row could not show must be counted, not dropped in silence");
		assertFalse(screen.contains("targets.size() >= 3"));
		assertTrue(screen.contains("TerminalControlPayload.MARK_RECORDS_READ"));
		assertTrue(screen.contains("snapshot.unreadFileCount() > 0"));
		assertTrue(screen.contains("TerminalControlPayload.MARK_FILES_SEEN"));
		assertTrue(runtime.contains("TerminalFileState.markAllSeen(tag)"));
		assertTrue(screen.contains("FILE_BODY_SCALE = 0.78F"));
		assertTrue(screen.contains("READING_TITLE"));
		assertTrue(screen.contains("READING_TEXT"));
		assertTrue(screen.contains("READING_META"));
		assertTrue(screen.contains("FILE_TEXT_INSET"));
		assertTrue(screen.contains("drawFileScrollbar(graphics"));
		assertTrue(screen.contains("rows.add(FileRow.gap(FILE_PARAGRAPH_GAP))"),
				"Document paragraphs must retain visible spacing after wrapping");
		assertTrue(screen.contains("maxFileScroll(fileDetailRows(), fileViewportHeight())"),
				"File scrolling must stay clamped against the built rows and the viewport height");
		assertTrue(screen.contains("TerminalUiLayout.unreadFlashOn"));
		assertTrue(screen.contains("Component.literal(\" [!]\")"));
		// Both readers of the log take the same navigator-gated list. Filtering in one and not the
		// other is what let the home card advertise a lead the Records page did not list.
		assertTrue(screen.contains("snapshot.recordEntries(navigator)"));
		assertTrue(screen.contains("snapshot.latestSignalEvent(tools.available(TerminalTool.NAVIGATION))"));
		assertFalse(screen.contains("advanceAutomaticTuning"));
		assertTrue(screen.contains("TerminalUiLayout.FILE_LIST.contains"));
		assertTrue(screen.contains("TerminalUiLayout.FILE_CONTENT.contains"));
		assertFalse(screen.contains("FILE_GRID_COLUMNS"));
		int markReadStart = runtime.indexOf("private static boolean markRecordsRead");
		int markReadEnd = runtime.indexOf("private static boolean markHiddenFileRead", markReadStart);
		assertTrue(markReadStart >= 0 && markReadEnd > markReadStart);
		assertFalse(runtime.substring(markReadStart, markReadEnd).contains("synchronizeProjection"),
				"Opening RECORDS must not rewrite the held terminal and restart its equip animation");
	}

	/** How many times {@code needle} appears in {@code source}. */
	private static int countOccurrences(String source, String needle) {
		int count = 0;
		for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) count++;
		return count;
	}

	/**
	 * Whether {@code call} appears in the handful of lines right after {@code site} - close enough
	 * that it is answering that statement rather than merely existing somewhere in the same file.
	 */
	private static boolean answers(String source, String site, String call) {
		int at = source.indexOf(site);
		if (at < 0) return false;
		int end = Math.min(source.length(), at + site.length() + 200);
		return source.substring(at, end).contains(call);
	}

	/**
	 * The summon is the one press in the mod that nothing can take back, and it is now a hold.
	 *
	 * <p>{@code SummonHoldPolicyTest} pins the travel; what it cannot see is that the plate is
	 * still wired to it. Collapsing this back into an ordinary button is a two-line edit that
	 * compiles, runs, and silently returns a party's point of no return to the cost of a page tab -
	 * and every test but this one would stay green.
	 */
	@Test
	void theSummonPlateCommitsOnlyAtTheEndOfItsTravel() throws Exception {
		String altar = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/ResonanceAltarScreen.java"),
				StandardCharsets.UTF_8);
		// The press starts the travel and does nothing else. If the vanilla handler ever runs the
		// commit again, the hold becomes decoration in front of a button that still fires on click.
		assertTrue(altar.contains("pressed -> ((SummonPlate) pressed).startHold()"),
				"The press must start the travel, not perform the summon");
		assertTrue(answers(altar, "if (SummonHoldPolicy.complete(held))", "commit.run()"),
				"The request may only be sent once the plate is at the bottom of its stroke");
		// Exactly one call site. A second one is how "held" quietly becomes "clicked" again.
		assertEquals(1, countOccurrences(altar, "commit.run()"),
				"The summon must be sent from one place and that place is the completed hold");
		// Letting go anywhere lets it go. A release does not always reach the widget the press
		// started on, and a plate that kept filling after the button came up would commit on an
		// input the player had already ended.
		assertTrue(answers(altar, "public boolean mouseReleased(MouseButtonEvent event)",
				"summonButton.releaseHold()"));
		assertTrue(answers(altar, "public boolean keyReleased(KeyEvent event)",
				"summonButton.releaseHold()"));
		assertTrue(altar.contains("if (committed || !active) return;"),
				"A plate that has gone inactive mid-hold must not be able to start again");
		// The label has to say what the control wants, or the first press reads as a dead button.
		JsonObject zh = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		String key = "button.thefourthfrequency.resonance_altar.summon";
		assertTrue(zh.get(key).getAsString().contains("按住"),
				"A hold that looks like a click is a button that appears broken");
		assertTrue(en.get(key).getAsString().toLowerCase().contains("hold"),
				"A hold that looks like a click is a button that appears broken");
	}

	/**
	 * The short power-on check runs on every open for the rest of the run, which makes one property
	 * load-bearing in a way the pure policy cannot express: it must never take anything away.
	 *
	 * <p>The world bible allows exactly one thing in this mod to hold the exit, and that is the
	 * one-shot first-boot walkthrough. Half a second is a trivial cost once and an intolerable one
	 * four hundred times, so the ways out have to keep working through it - and "keep working" is a
	 * statement about which lines exist in the screen, not about any number in a policy class.
	 */
	@Test
	void theEveryOpenSelfTestNeverHoldsTheWayOut() throws Exception {
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"),
				StandardCharsets.UTF_8);
		String overlay = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalSelfTestOverlay.java"),
				StandardCharsets.UTF_8);
		int escStart = screen.indexOf("public boolean shouldCloseOnEsc()");
		assertTrue(escStart >= 0);
		assertFalse(screen.substring(escStart, escStart + 400).contains("selfTest"),
				"Only the one-shot walkthrough may hold the exit; a per-open check never can");
		// Any key ends it and is then handled normally, Escape included.
		int keyStart = screen.indexOf("public boolean keyPressed(KeyEvent event) {");
		int firstBranch = screen.indexOf("if (profileActive())", keyStart);
		assertTrue(keyStart >= 0 && firstBranch > keyStart);
		assertTrue(screen.substring(keyStart, firstBranch).contains("selfTestSkipped = true;"),
				"The first key must end the check before anything else in keyPressed looks at it");
		// The page area is the only thing it owns, and the only clicks it eats are the ones aimed
		// at that empty area.
		assertTrue(answers(screen, "selfTestSkipped = true;",
				"TerminalUiLayout.PAGE_BODY.contains(local[0], local[1])"),
				"A click outside the hidden page area must still reach the control it was aimed at");
		assertTrue(screen.contains("if (selfTestRunning()) return;"),
				"The page body must stay unrendered while the machine is still reporting");
		// The clock starts when the screen is first drawn, not when it is constructed.
		assertTrue(screen.contains("if (selfTestArmed && selfTestStartedAtMillis < 0L) selfTestStartedAtMillis = renderNowMillis;"));
		// The baseline line is read off the pitch table rather than restated.
		assertTrue(overlay.contains("TerminalSelfTest.baselineDriftPercent(stage)"),
				"The printed drift must come from the constant that pitches the presses");
		assertFalse(overlay.contains("graphics.fill(body"),
				"The check draws on the display glass, never on a plate laid over the device");
	}

	/**
	 * The panel answers different kinds of press differently, and the only thing holding that
	 * together is which method each call site reaches for. {@link
	 * com.xm.thefourthfrequency.terminal.TerminalContactVoice} pins the table apart; this pins the
	 * wiring, because collapsing six voices back into one generic click is a one-word edit at each
	 * site that compiles, runs, and simply undoes the feature.
	 */
	@Test
	void everyKindOfPressOnTheTerminalHasItsOwnVoice() throws Exception {
		String audio = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalClientAudio.java"),
				StandardCharsets.UTF_8);
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"),
				StandardCharsets.UTF_8);
		// The generic click is gone on purpose. Anything reaching for it again is a site that has
		// not decided what kind of event it is.
		assertFalse(screen.contains("TerminalClientAudio.click()"),
				"Every press must name the kind of event it is, not fall back to one click");
		assertFalse(audio.contains("public static void click()"));
		for (String voice : new String[] {"tab", "openLevel", "backLevel", "commit", "acknowledge"}) {
			assertTrue(audio.contains("public static void " + voice + "()"),
					"TerminalClientAudio lost the " + voice + " voice");
			assertTrue(screen.contains("TerminalClientAudio." + voice + "()"),
					"Nothing plays the " + voice + " voice any more");
		}
		// The three that carry the meaning, checked at the site rather than by name alone.
		assertTrue(answers(screen, "TerminalControlPayload.REQUEST_RESCAN", "TerminalClientAudio.commit()"),
				"Ordering a rescan is a committed order and must sound like one");
		assertTrue(answers(screen, "recordsScrollRow = next == TerminalPage.RECORDS", "TerminalClientAudio.tab()"),
				"A page actually changing must use the page voice");
		assertTrue(answers(screen, "next == TerminalPage.RECORDS && snapshot.unreadCount() > 0",
				"TerminalClientAudio.acknowledge()"),
				"Clearing an unread marker without navigating anywhere is the lamp, not a page turn");
		// The bolt sample is shared with the receiver lock, which the first-run notice tests count
		// and require to be zero. Routing a commit through lock() would make that count wrong.
		assertFalse(answers(audio, "public static void commit()", "lock();"),
				"A committed order must not be counted as a receiver lock");
		// The panel wears with the holder's stage, and it learns the stage from the one call that
		// is already fed it every tick.
		assertTrue(audio.contains("panelStage = Math.clamp(stage, 0, TerminalContactVoice.MAX_STAGE)"));
		assertTrue(audio.contains("voice.pitchAt(panelStage)"),
				"The press voices must age with the holder instead of staying factory-fresh");
	}

	@Test
	void terminalFeedbackUsesABoundedOldestFirstNoticeStackAndClearAttentionTone() throws Exception {
		String hud = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalNoticeHud.java"),
				StandardCharsets.UTF_8);
		String networking = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalClientNetworking.java"),
				StandardCharsets.UTF_8);
		String commonNetworking = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalNetworking.java"),
				StandardCharsets.UTF_8);
		String audio = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalClientAudio.java"),
				StandardCharsets.UTF_8);
		String targets = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalStructureTarget.java"),
				StandardCharsets.UTF_8);
		String taskService = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalTaskService.java"),
				StandardCharsets.UTF_8);
		String signalService = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalSignalService.java"),
				StandardCharsets.UTF_8);
		String noticeService = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalNoticeService.java"),
				StandardCharsets.UTF_8);
		String pursuit = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitDirector.java"),
				StandardCharsets.UTF_8);
		String pursuitSession = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitSessionService.java"),
				StandardCharsets.UTF_8);
		String pursuitPolicy = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/pursuit/PursuitProgressPolicy.java"),
				StandardCharsets.UTF_8);
		String noticePayload = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalNoticePayload.java"),
				StandardCharsets.UTF_8);
		String terminalRuntime = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalRuntimeService.java"),
				StandardCharsets.UTF_8);
		String terminalScreen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"),
				StandardCharsets.UTF_8);
		String survivalProgress = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/SurvivalProgressService.java"),
				StandardCharsets.UTF_8);
		String metaFallback = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/meta_api/InGameMetaPlatformAdapter.java"),
				StandardCharsets.UTF_8);
		assertTrue(hud.contains("MAX_VISIBLE = 3"));
		assertTrue(hud.contains("MAX_PENDING = 12"));
		assertTrue(hud.contains("MIN_APPEAR_INTERVAL_MILLIS = 900L"));
		assertTrue(hud.contains("DUPLICATE_WINDOW_MILLIS = 5_000L"));
		assertTrue(hud.contains("PENDING.add(insertion, entry)"));
		assertTrue(hud.contains("promotePending(now)"));
		assertTrue(hud.contains("priority(PENDING.get(index).tone)"),
				"Unread and task notices must keep priority inside the throttled queue");
		assertTrue(hud.contains("ENTRIES.add(pending)"),
				"The queued entry itself must be promoted so a merged repeat count survives the wait");
		assertTrue(hud.contains("offset += entry.height(font, wrapWidth) + ENTRY_GAP"),
				"A new bottom entry must push existing entries upward by their measured height");
		assertTrue(hud.contains("exiting = ENTRIES.getFirst()"),
				"The oldest entry at the top must be the first one to leave");
		assertTrue(hud.contains("PENDING.size() <= MAX_PENDING"),
				"The waiting queue must stay bounded during notification bursts");
		assertTrue(hud.contains("PENDING.removeIf(entry -> now - entry.queuedAt >= PENDING_TTL_MILLIS)"),
				"A notice that outlived the moment it described must be dropped, not replayed late");
		assertTrue(hud.contains("if (mergeDuplicate(key, now)) return;"),
				"A repeat of a live notice must become a counter instead of a silent drop");
		assertTrue(hud.contains("now - lastAttentionAt >= ATTENTION_INTERVAL_MILLIS"),
				"Attention audio must not machine-gun during a burst");
		assertTrue(hud.contains("font.split(display(), wrapWidth)"),
				"Long notices must wrap instead of shrinking below a readable size");
		assertTrue(hud.contains("client.isPaused() || client.screen != null"),
				"A notice must not spend its display time behind a screen or a paused game");
		assertTrue(hud.contains("suspend(delta)"),
				"Every hold path must rebase the deadlines it skipped");
		assertTrue(hud.contains("HudStatusBarHeightRegistry.getHeight"),
				"The stack must follow the status bars instead of a fixed inset above the hotbar");
		assertTrue(networking.contains("TerminalNoticeHud.enqueue(payload.message(), payload.tone())"));
		assertFalse(networking.contains("TerminalClientAudio.attention(payload.tone())"),
				"Attention audio must wait until the queued notice is actually visible");
		assertTrue(hud.contains("TerminalClientAudio.attention(pending.tone)"));
		assertTrue(metaFallback.contains("TerminalNoticeHud.enqueue("));
		assertFalse(metaFallback.contains("displayClientMessage("));
		assertTrue(commonNetworking.contains("TerminalNoticePayload.TYPE"));
		assertTrue(audio.contains("UI_TOAST_CHALLENGE_COMPLETE"));
		assertTrue(audio.contains("NOTE_BLOCK_CHIME"));
		assertTrue(hud.contains("case TerminalNoticePayload.TONE_TASK_COMPLETE -> TASK_BACKGROUND"),
				"Task completion notices must use the dedicated green background");
		assertTrue(hud.contains("case TerminalNoticePayload.TONE_DENIED -> DENIED_BACKGROUND"),
				"A refused action must not be dressed as another progress line");
		assertTrue(hud.contains("case TerminalNoticePayload.TONE_DENIED -> 2"),
				"Direct feedback on a refused action outranks narration in the queue");
		assertTrue(audio.contains("tone == TerminalNoticePayload.TONE_DENIED"),
				"A refusal must sound like a fault, never like the progress chime");
		assertTrue(noticePayload.contains("TONE_DENIED = 4"));
		// The clamp has to track the highest declared tone, or every tone above the bound silently
		// degrades into whichever one the bound names.
		assertTrue(noticePayload.contains("TONE_DRAGON = 7"));
		assertTrue(noticePayload.contains("TONE_UNRENDERED = 8"));
		// Names the highest declared tone, whatever that currently is. Updated from TONE_DRAGON when
		// the unrendered layer added TONE_UNRENDERED above it - which is this assertion working
		// rather than this assertion being loosened: a tone above the bound does not fail, it
		// silently arrives as whichever one the bound names.
		assertTrue(noticePayload.contains("Math.clamp(value.tone, TONE_NONE, TONE_UNRENDERED)"),
				"The write clamp must admit the highest declared tone or everything above it degrades");
		// Both clamps, because only one of them was updated last time. A tone written as 8 and read
		// back as 7 arrived as the dragon - its teal panel and its roar - on a line about the
		// unrendered layer, and nothing failed: the packet was valid, it just meant something else.
		assertTrue(noticePayload.contains("Math.clamp(buf.readVarInt(), TONE_NONE, TONE_UNRENDERED)"),
				"The read clamp must admit the highest declared tone; it is the half that was missed");
		// The finale's narration is the mod's own channel now, not chat.
		for (String tone : new String[]{"TONE_ENCOUNTER", "TONE_ANCHOR", "TONE_DRAGON"}) {
			assertTrue(hud.contains("case TerminalNoticePayload." + tone + " -> "),
					"Every encounter tone must carry its own background: " + tone);
			assertTrue(audio.contains("tone == TerminalNoticePayload." + tone),
					"Every encounter tone must be distinguishable without looking: " + tone);
		}
		assertFalse(Files.readString(Path.of(
						"src/main/java/com/xm/thefourthfrequency/ending/EndBossEncounterService.java"),
						StandardCharsets.UTF_8).contains("displayClientMessage"),
				"Boss-fight narration must go to the mod's notice stack rather than the chat log");
		assertTrue(noticePayload.contains("TONE_PURSUIT_WARNING = 3"));
		assertTrue(hud.contains("PURSUIT_BACKGROUND = 0x59151B"));
		assertTrue(hud.contains("PURSUIT_BORDER = 0xF05B65"));
		assertTrue(hud.contains("case TerminalNoticePayload.TONE_PURSUIT_WARNING -> 3"));
		assertTrue(audio.contains("tone == TerminalNoticePayload.TONE_PURSUIT_WARNING"));
		assertTrue(audio.contains("ModSounds.TERMINAL_ANOMALY"));
		assertTrue(pursuitPolicy.contains("WARNING_LEAD_TICKS = 10L * 20L"));
		assertTrue(pursuitSession.contains(
				"record.putBoolean(TerminalData.PURSUIT_WARNING_RECORDS_REDIRECT, true)"));
		assertTrue(pursuitSession.contains("TerminalSignalLog.append(record, SignalBand.UNKNOWN"));
		assertTrue(pursuitSession.contains("TerminalNoticeService.pursuitWarning(player)"));
		assertFalse(pursuit.contains("message.thefourthfrequency.pursuit.warning.\" + form"));
		assertTrue(noticeService.contains("message.thefourthfrequency.pursuit.warning"));
		assertTrue(noticeService.contains("TerminalNoticePayload.TONE_PURSUIT_WARNING"));
		// The redirect has to outrank the remembered tab, or a player who left the terminal on files
		// gets the warning without the records it points at.
		assertTrue(terminalRuntime.contains("openRecords ? TerminalPage.RECORDS : remembered"));
		assertTrue(terminalRuntime.contains("PURSUIT_WARNING_RECORDS_REDIRECT, false"));
		assertTrue(terminalScreen.contains("TerminalPage.fromIndex(snapshot.initialPage())"));
		assertTrue(terminalScreen.contains("TerminalControlPayload.MARK_RECORDS_READ"));
		assertTrue(taskService.contains("consumeCompletionAlert"));
		assertFalse(taskService.contains("TerminalNoticeService.taskComplete(player)"),
				"Completion and its reward are one moment and must not take two stack slots");
		assertTrue(taskService.contains(
				"TerminalNoticeService.rewardClaimed(player, taskName(task), rewardName,"),
				"Automatic and manual reward delivery must share the single merged notice");
		// A reward that arrives without the player pressing anything has to say what it is for, or
		// the first task pays out mid-walkthrough and reads as an unexplained handout.
		assertTrue(noticeService.contains("taskName, rewardName, rewardCount)"),
				"A completion notice must name the task it is paying for");
		// A shared payout arrives as a smaller stack than the objective advertises to a lone player.
		// Unexplained, that reads as the reward being broken, so the same line has to say it was split.
		assertTrue(noticeService.contains("taskName, rewardName, rewardCount, sharedBetween)"),
				"A divided payout must say how many players it was divided between");
		// Every task's short name has to exist as a literal, or a missing one silently degrades to
		// the full objective line in the middle of a notice sized for a name.
		for (String task : new String[]{"learn_terminal", "mine_logs", "bring_iron", "enter_nether",
				"find_fortress", "collect_blaze_rods", "return_from_nether",
				"find_stronghold", "enter_end", "defeat_boss"}) {
			assertTrue(taskService.contains("terminal.thefourthfrequency.task.name." + task),
					"A task with a reward must have a short name for its completion notice: " + task);
		}
		assertTrue(noticeService.contains("message.thefourthfrequency.task.completed_reward_claimed"));
		assertTrue(noticeService.contains("message.thefourthfrequency.task.reward_claimed"));
		assertTrue(noticeService.contains("TerminalNoticePayload.TONE_TASK_COMPLETE"),
				"Reward notices must retain the task-completion tone and green presentation");
		assertFalse(signalService.contains("TerminalNoticeService.unread(player)"),
				"New files must not also raise the old generic unread notice");
		assertTrue(signalService.contains("message.thefourthfrequency.file.discovered_batch"),
				"A multi-file sync must collapse into one counted line instead of naming each file");
		assertTrue(signalService.contains("message.thefourthfrequency.fragment.received_batch"),
				"A late owner's backlog of shares must collapse into one counted line");
		assertTrue(noticeService.contains("TerminalNoticePayload.TONE_DENIED"),
				"Refused actions must route through the dedicated denial tone");
		for (String refusal : new String[]{
				"src/main/java/com/xm/thefourthfrequency/mixin/LivingEntityDropMixin.java",
				"src/main/java/com/xm/thefourthfrequency/mixin/BoundTerminalContainerMixin.java"}) {
			assertTrue(Files.readString(Path.of(refusal), StandardCharsets.UTF_8)
					.contains("TerminalNoticeService.denied("), refusal);
		}
		// The final Eye is not on that list any more, and must not come back onto it: a per-player
		// refusal on an action whose consequences land on the whole world is unreadable from where
		// either half of a party is standing.
		String enderEye = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/mixin/EnderEyeItemMixin.java"), StandardCharsets.UTF_8);
		assertFalse(enderEye.contains("TerminalNoticeService.denied(") || enderEye.contains("finalEyeReady"),
				"placing the twelfth Eye must carry no personal prerequisite");
		assertTrue(signalService.contains("TerminalNoticeService.unreadReminder(player, unreadCount[0],"));
		assertTrue(signalService.contains("totalUnreadCount(tag)"));
		// The reminder is the one line that fires exactly when somebody has not been opening the
		// terminal, so for a player still being explained to it has to carry how to open it. Both
		// halves are asserted: losing the verbose key silently drops the only place the mod says how,
		// and losing the terse one would start repeating the instruction at players who never needed it.
		assertTrue(noticeService.contains(
				"message.thefourthfrequency.terminal.unread_reminder.verbose\""));
		assertTrue(noticeService.contains(
				"message.thefourthfrequency.terminal.unread_reminder\", unreadCount"));
		assertTrue(survivalProgress.contains("public static final int REQUIRED_IRON = 6;"));
		// The trailing false is the point as much as the 24: eight players bring eight lots of iron,
		// so this payout is per player and must never join the shared split.
		assertTrue(taskService.contains(
				"new TaskDefinition(\"bring_iron\", SurvivalProgressService.REQUIRED_IRON, Items.TORCH, 24, false)"));
		// Completion and its reward are one moment, so delivery hangs off progress changing rather
		// than off opening a page. Anywhere else and a finished task waits, which is what made the
		// manual claim button feel necessary and made the two paths contradict each other.
		assertTrue(survivalProgress.contains("TerminalTaskService.notifyIfCompleted(player)"),
				"Reward delivery must trigger where progress changes");
		assertFalse(taskService.contains("notifyIfCompleted(player);\n\t\treturn ClaimResult.CLAIMED;"),
				"A claim packet must not chain the catch-up loop into a burst of rewards");
		assertTrue(targets.contains("MINESHAFT(2, \"mineshaft\", true"));
		assertTrue(targets.contains("TRIAL_CHAMBERS(3, \"trial_chambers\", true"));
		assertTrue(targets.contains("BASTION(5, \"bastion\", true"));
	}

	@Test
	void openingWoodTaskAcceptsEveryLogFamilyAndPlanks() throws Exception {
		String survival = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/SurvivalProgressService.java"),
				StandardCharsets.UTF_8);
		assertTrue(survival.contains("collectedWood(player)"));
		assertTrue(survival.contains("BlockTags.LOGS"));
		assertTrue(survival.contains("BlockTags.PLANKS"));
		assertTrue(survival.contains("instanceof BlockItem"));
	}

	@Test
	void toolProtocolIsIndependentAndAllToolControlsAreServerValidated() throws Exception {
		String snapshot = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalSnapshotPayload.java"), StandardCharsets.UTF_8);
		String navigation = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalNavigationPayload.java"), StandardCharsets.UTF_8);
		String toolSnapshot = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalToolSnapshotPayload.java"), StandardCharsets.UTF_8);
		String control = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalControlPayload.java"), StandardCharsets.UTF_8);
		String runtime = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalRuntimeService.java"), StandardCharsets.UTF_8);
		String resourceGuidance = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/ResourceGuidanceService.java"), StandardCharsets.UTF_8);
		String toolService = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/TerminalToolService.java"), StandardCharsets.UTF_8);
		String structureNavigation = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/StructureNavigationService.java"),
				StandardCharsets.UTF_8);
		// 16 since the discovered-fragment mask joined the snapshot, 15 for the oscilloscope's approach
		// reading and 14 for the anomaly backfill and first-boot profile before it. The three protocols
		// are versioned separately on purpose, which is the property this line exists to keep honest:
		// adding a field to one must not silently pass for the others.
		assertTrue(snapshot.contains("CURRENT_PROTOCOL_VERSION = 16"));
		assertTrue(navigation.contains("CURRENT_PROTOCOL_VERSION = 6"));
		assertTrue(toolSnapshot.contains("CURRENT_PROTOCOL_VERSION = 6"));
		assertTrue(resourceGuidance.contains("TerminalRuntimeService.isOpen(player)"),
				"Automatic mineral surveys must pause while the terminal is open");
		assertFalse(resourceGuidance.contains(
				"Component.translatable(\"message.thefourthfrequency.guidance.not_found\")"),
				"A failed manual probe must not create a separate bottom notice");
		assertFalse(resourceGuidance.contains("message.thefourthfrequency.guidance.ready"),
				"Manual probe completion must stay inside the terminal instead of producing a bottom tool notice");
		assertFalse(toolService.contains("TerminalNoticeService"),
				"Selecting, starting, or stopping tools must not produce bottom tool-action notices");
		int selectTargetStart = structureNavigation.indexOf("public static boolean selectTarget");
		int selectTargetEnd = structureNavigation.indexOf("public static TerminalStructureTarget selectedTarget",
				selectTargetStart);
		assertTrue(selectTargetStart >= 0 && selectTargetEnd > selectTargetStart);
		assertFalse(structureNavigation.substring(selectTargetStart, selectTargetEnd)
				.contains("TerminalNoticeService"),
				"Switching navigation targets must not produce a bottom notice");
		assertTrue(resourceGuidance.contains(
				"record.putLong(TerminalData.MINERAL_SCAN_READY_GAME_TIME, 0L)"),
				"A mineral probe must clear its active marker only when its result commits");
		for (String action : List.of("SELECT_TOOL", "START_GUIDANCE", "STOP_GUIDANCE",
				"REQUEST_RESCAN", "MARK_RECORDS_READ", "MARK_FILES_SEEN", "READ_HIDDEN_FILE", "SELECT_STRUCTURE_TARGET",
				"SELECT_NEAREST_UNSTABLE", "SELECT_FRAGMENT_TARGET", "DISMISS_NAVIGATION_COMPLETION", "VISIT_PAGE",
				"CLAIM_TASK_REWARD")) {
			assertTrue(control.contains(action));
			assertTrue(runtime.contains("TerminalControlPayload." + action));
		}
		for (String retired : List.of("SELECT_RESOURCE", "SET_HOME")) {
			assertTrue(control.contains(retired));
			assertTrue(runtime.contains("case TerminalControlPayload." + retired + " -> { return; }"));
		}
		assertTrue(control.contains("SET_AUTO_TUNING"), "Wire id 11 remains reserved for old clients");
		assertFalse(runtime.contains("TerminalControlPayload.SET_AUTO_TUNING"));
		assertTrue(runtime.contains("TerminalControlPolicy.validMode(value)"));
		assertTrue(runtime.contains("TerminalControlPolicy.validTuning(value)"));
	}

	@Test
	void strongholdPortalHandsTheEndToThePersistedWorldInterfaceEncounter() throws Exception {
		String encounter = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/EndBossEncounterService.java"),
				StandardCharsets.UTF_8);
		String attackService = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceAttackService.java"),
				StandardCharsets.UTF_8);
		String state = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceState.java"),
				StandardCharsets.UTF_8);
		String ritual = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceRitualService.java"),
				StandardCharsets.UTF_8);
		String policy = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfacePolicy.java"),
				StandardCharsets.UTF_8);
		String stages = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceStage.java"),
				StandardCharsets.UTF_8);
		String actions = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/WorldInterfaceAction.java"),
				StandardCharsets.UTF_8);
		String arena = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/EndBossArenaService.java"),
				StandardCharsets.UTF_8);
		String protocol = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/WorldInterfaceProtocol.java"),
				StandardCharsets.UTF_8);
		String blocks = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/content/ModBlocks.java"),
				StandardCharsets.UTF_8);
		String mixins = Files.readString(Path.of("src/main/resources/thefourthfrequency.mixins.json"),
				StandardCharsets.UTF_8);
		JsonObject immunityTag = JsonParser.parseString(Files.readString(Path.of(
				"src/main/resources/data/thefourthfrequency/tags/block/world_interface_immune.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		assertTrue(state.contains("ROOT_KEY = \"world_interface\"")
				&& state.contains("FORMAT_VERSION = 2")
				&& state.contains("GATE_COUNT = 20")
				&& state.contains("ANCHOR_COUNT = 10")
				&& state.contains("MAX_ROSTER_SIZE = 8"));
		assertFalse(state.contains("ensureEndBossV3") || state.contains("ROOT_KEY = \"ending\""));
		for (String transactionState : List.of("PREPARED", "REMOVED", "RETURN_PENDING", "COMMITTED")) {
			assertTrue(state.contains(transactionState));
			assertTrue(ritual.contains("TerminalTransactionState." + transactionState));
		}
		assertTrue(ritual.contains("RitualResult deposit(") && ritual.contains("RitualResult withdraw(")
				&& ritual.contains("RitualResult cancel("));
		assertTrue(policy.contains("COLLAPSE_DURATION_TICKS = 12_000")
				&& policy.contains("MAX_PERMANENT_TERRAIN_EDITS = 8_192")
				&& policy.contains("MAX_TERRAIN_EDITS_PER_TICK = 32"));
		assertTrue(stages.contains("SUCCESS_RESOLUTION") && stages.contains("FAILURE_RESOLUTION")
				&& stages.contains("PHASE_1") && stages.contains("PHASE_2") && stages.contains("PHASE_3"));
		for (String action : List.of("LASER_SWEEP", "ENERGY_ORB", "SKY_LANCE",
				"CHARGE_WEAPON_STEAL", "GRAB_THROW", "GAZE_HOTBAR_CLEAR", "TENDRIL_LASH",
				"FORCED_EVICTION")) {
			assertTrue(actions.contains(action));
			assertTrue(attackService.contains("case " + action));
		}
		assertTrue(encounter.contains("WorldInterfaceAttackService.begin(")
				&& encounter.contains("WorldInterfaceAttackService.tick(")
				&& encounter.contains("SUCCESS_RESOLUTION")
				&& encounter.contains("FAILURE_RESOLUTION"));
		assertTrue(arena.contains("GATEWAY_COUNT = 20") && arena.contains("ANCHOR_COUNT = 10")
				&& arena.contains("MAX_PERMANENT_EDITS") && arena.contains("MAX_EDITS_PER_TICK"));
		assertTrue(blocks.contains("RESONANCE_CORE") && blocks.contains("WORLD_INTERFACE_EXIT_PORTAL"));
		// The anchor cage and the gate core are gone rather than retextured: the gate structures
		// stopped being built long ago, and the cage wrapped a bright band of custom texture around
		// the one thing in the arena a player is meant to be looking at.
		assertFalse(blocks.contains("WARP_GATE_CORE") || blocks.contains("STABILITY_ANCHOR_CAGE"));
		assertTrue(protocol.contains("VERSION = 3") && protocol.contains("MAX_PARTICIPANTS = 8")
				&& protocol.contains("MAX_GATEWAYS = 20") && protocol.contains("ANCHOR_MASK = 0x03FF"));
		assertFalse(immunityTag.get("replace").getAsBoolean());
		String immuneValues = immunityTag.getAsJsonArray("values").toString();
		for (String criticalBlock : List.of("resonance_core", "world_interface_exit_portal")) {
			assertTrue(immuneValues.contains("thefourthfrequency:" + criticalBlock));
		}
		// A tag entry naming an unregistered block fails the whole tag on data-pack load.
		assertFalse(immuneValues.contains("warp_gate_core") || immuneValues.contains("stability_anchor_cage"));
		assertTrue(mixins.contains("EndPortalBlockMixin")
				&& mixins.contains("EnderEyeItemMixin") && mixins.contains("EnderDragonMixin")
				&& !mixins.contains("ServerPlayerDropMixin"));
		// The anchors are a bespoke entity now, so nothing has to reach into EndCrystal at all.
		// A leftover injection there would put mod damage rules back on every vanilla crystal.
		assertFalse(mixins.contains("EndCrystalMixin"));
		assertFalse(Files.exists(Path.of(
				"src/main/java/com/xm/thefourthfrequency/mixin/EndCrystalMixin.java")));
	}

	@Test
	void worldInterfaceExitUsesTheRealVanillaEndPoemAndRespawnProtocol() throws Exception {
		String exit = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/content/WorldInterfaceExitPortalBlock.java"),
				StandardCharsets.UTF_8);
		String encounter = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/ending/EndBossEncounterService.java"),
				StandardCharsets.UTF_8);
		String networking = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceClientNetworking.java"),
				StandardCharsets.UTF_8);
		String binding = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfaceVanillaPoemClient.java"),
				StandardCharsets.UTF_8);
		String winScreenMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/WinScreenPoemMixin.java"),
				StandardCharsets.UTF_8);
		String mixins = Files.readString(Path.of("src/main/resources/thefourthfrequency.mixins.json"),
				StandardCharsets.UTF_8);

		assertTrue(exit.contains("implements Portal") && exit.contains("player.showEndCredits()"));
		assertTrue(exit.contains("((Portal) Blocks.END_PORTAL).getPortalDestination"));
		assertFalse(exit.contains("setPortalCooldown") || exit.contains("restoreRespawnAndReturn"));
		assertTrue(networking.contains("acceptPoem(payload)") && networking.contains("VanillaPoemClient.arm"));
		assertFalse(networking.contains("new WorldInterfacePoemScreen"));
		assertFalse(Files.exists(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfacePoemScreen.java")));
		assertTrue(mixins.contains("WinScreenPoemMixin"));
		assertTrue(winScreenMixin.contains("@Mixin(WinScreen.class)")
				&& winScreenMixin.contains("wrapCreditsIO")
				&& winScreenMixin.contains("PoemCompletion.SKIPPED"));
		int acknowledgement = binding.indexOf("sendPoemComplete(poem, completion)");
		int unlockArming = binding.indexOf("armUnlockAfterSuccessfulReturn()");
		int vanillaFinish = binding.indexOf("vanillaFinish.run()");
		assertTrue(acknowledgement >= 0 && unlockArming > acknowledgement
				&& vanillaFinish > unlockArming,
				"The durable success ACK must arm the return unlock before vanilla PERFORM_RESPAWN");
		assertTrue(encounter.contains("prepareVanillaEndReturn(player, result.snapshot())")
				&& encounter.contains("restoreRespawnAfterVanillaReturn(player, snapshot)"));

		for (String resource : List.of("end_success_zh_cn.txt", "end_success_partial_zh_cn.txt",
				"end_success_preserved_zh_cn.txt", "end_failure_zh_cn.txt",
				"end_success_en_us.txt", "end_success_partial_en_us.txt",
				"end_success_preserved_en_us.txt", "end_failure_en_us.txt")) {
			Path poem = ASSETS.resolve("texts").resolve(resource);
			assertTrue(Files.isRegularFile(poem), resource);
			long authoredLines = Files.readAllLines(poem, StandardCharsets.UTF_8).stream()
					.filter(line -> !line.isBlank()).count();
			long expectedParagraphs = resource.contains("failure") ? 62L : 65L;
			assertEquals(expectedParagraphs, authoredLines,
					resource + " must retain all authored paragraphs");
		}

		// The roll now owns the closing quote too, so ordinal 2 has somewhere to read from.
		for (String resource : List.of("postcredits_zh_cn.txt", "postcredits_en_us.txt")) {
			Path quote = ASSETS.resolve("texts").resolve(resource);
			assertTrue(Files.isRegularFile(quote), resource);
			assertFalse(Files.readString(quote, StandardCharsets.UTF_8).contains("§7"),
					resource + " must stay unattributed");
		}
		assertTrue(winScreenMixin.contains("postcreditsResource()"),
				"The closing quote must not fall back to the vanilla sailing quote");
		// Vanilla assigns unmodifiedScrollSpeed straight to the field in the constructor and only
		// recomputes it from key events, so the multiplier applied to calculateScrollSpeed does
		// nothing at all until the player happens to press something. Seeding the field is what makes
		// the authored pace apply to a poem watched in silence, which is every poem that is read.
		assertTrue(winScreenMixin.contains("scrollSpeed *= BASE_SCROLL_SCALE"),
				"The roll's speed must be seeded in the constructor, not left to the first key event");
	}

	@Test
	void recoveryKeyIsPolledSoItSurvivesTheLockedEndingScreen() throws Exception {
		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/meta_api/MetaController.java"),
				StandardCharsets.UTF_8);
		assertTrue(controller.contains("GLFW.GLFW_KEY_F8"));
		// KeyboardHandler only feeds KeyMapping.click while no screen is open, and every ending
		// parks the player on the locked title screen, so consumeClick alone can never fire there.
		assertTrue(controller.contains("InputConstants.isKeyDown"),
				"The recovery key must be polled, not taken from the screen-gated click queue");
		assertTrue(controller.contains("held && !toggleKeyHeld"),
				"Polling must be edge-triggered so one press opens exactly one confirmation");
		assertTrue(controller.contains("KeyBindingHelper.getBoundKeyOf(toggleKey)"),
				"Polling must follow the player's rebind instead of the hardcoded default");
		assertTrue(controller.contains("WorldInterfaceEndingClient.replayResetAvailable()"));
		assertTrue(controller.contains("WorldInterfaceEndingClient.requestRecoveryConfirmation"));
	}

	@Test
	void firstRunNoticeUsesClientLocalVersionMarkerAndCannotBeBypassed() throws Exception {
		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeController.java"),
				StandardCharsets.UTF_8);
		assertFalse(controller.contains("ConfigManager.loadClientState()"));
		assertFalse(controller.contains("ConfigManager.updateClientState"));
		assertTrue(controller.contains("thefourthfrequency-safety-notice.version"));
		assertTrue(controller.contains("CURRENT_NOTICE_VERSION"));
		assertTrue(controller.contains("Files.writeString"));
		assertFalse(controller.contains("thefourthfrequency-client-state.json"));
		assertTrue(controller.contains("ClientTickEvents.END_CLIENT_TICK"));
		assertTrue(controller.contains("client.screen instanceof TitleScreen"));
		assertTrue(controller.contains("new FirstRunNoticeScreen(titleScreen)"));
		assertTrue(controller.contains("client.getOverlay() != null"),
				"Minecraft ticks screens under the loading overlay, so the notice must wait it out");
		assertFalse(controller.contains("ClientPlayConnectionEvents.JOIN"));
		assertFalse(controller.contains("TerminalData"));
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeScreen.java"),
				StandardCharsets.UTF_8);
		assertTrue(screen.contains("shouldCloseOnEsc() { return false; }"));
		assertTrue(screen.contains("isPauseScreen() { return true; }"));
		assertTrue(screen.contains("button.thefourthfrequency.first_run_notice.acknowledge"));
		assertTrue(screen.contains("graphics.fill(0, 0, width, height, SHELL_BACKDROP)"));
		assertFalse(screen.contains("0xFF080D0A") || screen.contains("0xFF020A06"),
				"Notice shell and glass colors belong to FirstRunNoticePalette");
		assertTrue(screen.contains("setInitialFocus(acknowledgementButton)"),
				"The only exit from the mandatory notice must be reachable by keyboard");
		assertTrue(screen.contains("returnScreen.resize(width, height)"),
				"The title screen behind the zoom transition only learns about resizes through here");
		assertFalse(screen.contains("renderBlurredBackground"));
		assertFalse(screen.contains("drawBackdropWaveform"));
		assertFalse(screen.contains("drawRollingBeam"));
		assertFalse(screen.contains("drawEdgeGlitches"));
		assertFalse(screen.contains("drawDesyncedScanRows"));
		assertFalse(screen.contains("renderTransientTextGhosts"));
		assertTrue(screen.contains("enum EntrancePhase"));
		assertTrue(screen.contains("enum PresentationPhase"));
		assertTrue(screen.contains("TUBE_LIT_TICK = IGNITION_TICKS + UNFOLD_TICKS"));
		assertTrue(screen.contains("POWER_ON_END_TICK = TUBE_LIT_TICK + BLOOM_TICKS"));
		assertTrue(screen.contains("renderPowerOn"));
		assertTrue(screen.contains("drawGlassSurface"),
				"A flat fill inside a painted bezel is what read as cheap; the glass keeps its surface");
		// Painting the surface to the copy-safe rect stopped it short of the bezel and left a visible
		// rectangle of different green. Anything that paints the tube must use the measured opening.
		assertTrue(screen.contains("GlassBounds glass = glassOpening("),
				"the tube surface must cover the lit opening, not the smaller copy-safe rect");
		assertFalse(screen.contains("drawGlassSurface(graphics, glassBounds("),
				"no paint pass may fall back to the copy-safe rect");
		for (String surface : new String[]{"SCANLINE_PITCH", "SCANLINE_ALPHA",
				"PHOSPHOR_FLOOR_ALPHA", "GLASS_OPENING_LEFT_ASSET"}) {
			assertTrue(screen.contains(surface), surface);
		}
		assertTrue(screen.contains("NOTICE_READY_TICK = POWER_ON_END_TICK"),
				"The raster sweep is the reveal, so no separate fade may follow the power-on");
		assertFalse(screen.contains("NOTICE_REVEAL_TICKS"),
				"A post-power-on fade would light the tube on an empty screen again");
		assertTrue(screen.contains("minecraft.getOverlay() != null) return"),
				"A reload overlay must hold the entrance clock instead of running it out of sight");
		assertFalse(screen.contains("Calibration") || screen.contains("MHz"),
				"The band-sweep entrance is replaced by a copy-free tube power-on");
		assertFalse(screen.contains("drawHeaderScope"));
		assertTrue(screen.contains("TerminalClientAudio.noticeOpening()"));
		assertTrue(screen.contains("TerminalClientAudio.noticeStable()"));
		assertTrue(screen.contains("arm(acknowledgementButton, ready)"),
				"The way out stays hidden and dead until the entrance has finished playing");
		assertTrue(screen.contains("transitionAge = 0"));
		assertTrue(screen.contains("TEXT_FADE_TICKS = 4"));
		assertTrue(screen.contains("ZOOM_TICKS = 24"));
		assertTrue(screen.contains("transitionAge >= TEXT_FADE_TICKS) return"));
		assertTrue(screen.contains("returnScreen.render(graphics"));
		assertTrue(screen.contains("graphics.enableScissor"));
		assertTrue(screen.contains("renderTransitionFrame"));
		assertTrue(screen.contains("zoomProgress * 2.0F"));
		assertTrue(screen.contains("255.0F * (1.0F - zoomProgress)"));
		assertTrue(screen.contains("renderTransitionFrame(graphics, zoomed, terminalAlpha)"));
		assertTrue(screen.contains("targetZoomScale"));
		assertTrue(screen.contains("(targetZoomScale(base) - 1.0F) * zoomProgress"));
		assertTrue(screen.contains("FirstRunNoticePalette"));
		assertTrue(screen.contains("LATIN_BASELINE_Y_OFFSET = 1"));
		assertTrue(screen.contains("drawBaselineAlignedString"));
		assertTrue(screen.contains("usesLatinPixelBaseline"));
		assertTrue(screen.contains("allTextInsideGlassForTesting"));
		assertTrue(screen.contains("GLASS_SAFE_BOTTOM_ASSET"));
		assertFalse(screen.contains("TerminalVisualTheme"));
		assertTrue(screen.contains("renderGeneratedNoticeUi"));
		assertTrue(screen.contains("textures/gui/notice/first_run_notice_terminal_shell.png"));
		assertFalse(screen.contains("first_run_notice_background.png"));
		assertFalse(screen.contains("panel_0.png"));
		assertFalse(screen.contains("panel_1.png"));
		assertFalse(screen.contains("panel_2.png"));
		assertFalse(screen.contains("SAFETY_FILL"));
		assertFalse(screen.contains("BUTTON_FILL"));
		assertFalse(screen.contains("SCOPE_FILL"));
		assertTrue(screen.contains("one continuous CRT glass grid"));
		assertTrue(screen.contains("class NoticeButton extends Button"));
		var noticeUi = ImageIO.read(ASSETS.resolve(
				"textures/gui/notice/first_run_notice_terminal_shell.png").toFile());
		assertEquals(1620, noticeUi.getWidth());
		assertEquals(971, noticeUi.getHeight());
		assertFalse(Files.exists(ASSETS.resolve("textures/gui/notice/first_run_notice_background.png")));
		String noticePalette = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticePalette.java"),
				StandardCharsets.UTF_8);
		assertTrue(noticePalette.contains("without importing terminal screen assets or constants"));

		String theme = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalVisualTheme.java"),
				StandardCharsets.UTF_8);
		String terminal = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"),
				StandardCharsets.UTF_8);
		for (String color : new String[]{"GREEN", "CYAN", "DIM", "AMBER", "HOT", "GLASS",
				"LCD_BACKGROUND", "LCD_BORDER", "DARK_BORDER"}) {
			assertTrue(theme.contains(" " + color + " ="), color);
			assertTrue(terminal.contains("TerminalVisualTheme." + color), color);
		}
	}

	/**
	 * The audio page in front of the disclosure, and the one number it is allowed to write.
	 *
	 * <p>Three things have to stay true together or the slider is a lie. It must reach
	 * {@code meta.peakVolume} rather than a setting of its own, because that is the number every
	 * audio path in the mod already multiplies by. The score has to be scaled by the same number,
	 * because a "mod volume" that silences every cue while the mod's own soundtrack keeps playing is
	 * not one. And the notice version marker has to have moved, or the page is invisible to everyone
	 * who already acknowledged the previous flow.</p>
	 */
	@Test
	void firstRunAudioPageTrimsEveryModSoundAndIsReachableByPlayersWhoAlreadyAcknowledged()
			throws Exception {
		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeController.java"),
				StandardCharsets.UTF_8);
		assertTrue(controller.contains("CURRENT_NOTICE_VERSION = 4"),
				"A new page in the first-run flow has to raise the marker or nobody sees it twice");

		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/FirstRunNoticeScreen.java"),
				StandardCharsets.UTF_8);
		assertTrue(screen.contains("public enum Page"));
		assertTrue(screen.contains("private Page page = Page.AUDIO;"),
				"The audio page must be the one the power-on sweep reveals");
		assertTrue(screen.contains("class VolumeSlider extends AbstractSliderButton"));
		assertTrue(screen.contains("button.thefourthfrequency.first_run_notice.next"));
		assertTrue(screen.contains("button.thefourthfrequency.first_run_notice.preview"));
		assertTrue(screen.contains("TerminalClientAudio::volumePreview"));
		assertTrue(screen.contains("ModVolumeControl.apply(value)"));
		assertTrue(screen.contains("ModVolumeControl.commit()"));
		assertTrue(screen.contains("ModVolumePolicy.snap"));
		// The disclosure is still mandatory: the audio page adds a step in front of it, never a way
		// around it, so acknowledging remains the only thing that writes the marker.
		assertTrue(screen.contains("page = Page.NOTICE;"));
		assertEquals(1, screen.split("FirstRunNoticeController\\.acknowledge\\(", -1).length - 1,
				"Leaving the audio page must turn the page, never spend the acknowledgement");

		String control = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/ModVolumeControl.java"),
				StandardCharsets.UTF_8);
		assertTrue(control.contains("withPeakVolume"));
		assertTrue(control.contains("RuntimeServices.updateConfig"),
				"The live value has to move, or the audition plays at the level before the drag");
		assertTrue(control.contains("ConfigManager.updateMeta"),
				"Persistence must go through the Meta updater so the rest of the file survives");

		String music = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/MusicDirector.java"),
				StandardCharsets.UTF_8);
		assertTrue(music.contains("fadeTarget = vanilla * (float) RuntimeServices.config().meta().peakVolume()"),
				"The authored score is a mod sound and has to answer to the mod volume");

		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject zh = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		for (String key : new String[]{
				"button.thefourthfrequency.first_run_notice.next",
				"button.thefourthfrequency.first_run_notice.preview",
				"screen.thefourthfrequency.first_run_notice.volume.title",
				"screen.thefourthfrequency.first_run_notice.volume.eyebrow",
				"screen.thefourthfrequency.first_run_notice.volume.section",
				"screen.thefourthfrequency.first_run_notice.volume.hint",
				"screen.thefourthfrequency.first_run_notice.volume.footer",
				"screen.thefourthfrequency.first_run_notice.volume.level",
				"screen.thefourthfrequency.first_run_notice.volume.muted"}) {
			assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "missing English " + key);
			assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "missing Chinese " + key);
		}
		String levelKey = "screen.thefourthfrequency.first_run_notice.volume.level";
		assertTrue(en.get(levelKey).getAsString().contains("%s"), "The readout needs its one slot");
		assertTrue(zh.get(levelKey).getAsString().contains("%s"), "The readout needs its one slot");
		// The page labels its control and says what the button does; it does not explain itself in
		// prose. The body paragraph that used to sit above the slider said what the eyebrow already
		// says, on the one page a player has to get past to reach the disclosure.
		String retired = "screen.thefourthfrequency.first_run_notice.volume.body";
		assertFalse(en.has(retired) || zh.has(retired) || screen.contains(retired),
				"The audio page must stay a control with labels, not a page of copy");

		// The audition has to be one of the mod's own recordings and long enough to judge a level
		// against; a fifth-of-a-second lock tick was neither.
		String audio = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalClientAudio.java"),
				StandardCharsets.UTF_8);
		assertTrue(audio.contains("volumePreviewInstance = SimpleSoundInstance.forUI(ModSounds.SIGNAL_TUNING_SWEEP"));
		assertTrue(audio.contains("if (volumePreviewInstance != null) manager.stop(volumePreviewInstance)"),
				"Repeated auditions must replace each other rather than stack into a peak");
		assertTrue(Files.size(ASSETS.resolve("sounds/signal/tuning_sweep.ogg")) > 0L);
	}

	/**
	 * The held shell is six materials on one unmoving body.
	 *
	 * <p>The rigidity is the product rule here, not a modelling convenience: the terminal is a
	 * sealed instrument, and a lid that opened would have made "the device is working" something
	 * the player reads off a hinge instead of off the screen. So the geometry must be byte-identical
	 * across all six forms, and no element may carry a rotation at all - the previous shell had a
	 * lid on a hinge and six hand-placed frames to swing it through, and both are gone.</p>
	 */
	@Test
	void heldShellIsOneRigidBodyAcrossSixFormsDrivenWithoutTouchingTheServerStack() throws Exception {
		String geometry = null;
		for (int form = 0; form < 6; form++) {
			var atlas = ImageIO.read(ASSETS.resolve("textures/item/old_terminal_shell_" + form + ".png").toFile());
			assertEquals(128, atlas.getWidth());
			assertEquals(128, atlas.getHeight());

			Path model = ASSETS.resolve("models/item/old_terminal_held_" + form + ".json");
			assertTrue(Files.exists(model), () -> "missing held form: " + model);
			JsonObject json = JsonParser.parseString(Files.readString(model, StandardCharsets.UTF_8))
					.getAsJsonObject();
			var elements = json.getAsJsonArray("elements");
			assertTrue(elements.size() >= 2, "the shell needs a chassis and its raised rim");
			for (var element : elements) {
				assertFalse(element.getAsJsonObject().has("rotation"),
						() -> "form " + json + " carries a rotated element; the device does not fold");
			}
			// Same body, different material. A form that moved a vertex would be a mechanical change
			// dressed as a palette change.
			if (geometry == null) geometry = elements.toString();
			else assertEquals(geometry, elements.toString(),
					"every form must share one geometry and differ only in its atlas");

			assertEquals("thefourthfrequency:item/old_terminal_shell_" + form,
					json.getAsJsonObject("textures").get("shell").getAsString());
			JsonObject display = json.getAsJsonObject("display");
			for (String view : new String[]{"thirdperson_righthand", "thirdperson_lefthand",
					"firstperson_righthand", "firstperson_lefthand", "gui", "head", "ground", "fixed"}) {
				assertTrue(display.has(view), () -> "held form " + model + " has no " + view + " pose");
			}
			// FIXED is the pose the two-handed presentation renders through, and every position in
			// TerminalHandheldPose is written as an absolute point in the frame on the assumption
			// that it leaves the model centred at unit scale. A translation or scale here would
			// silently shift the whole performance off centre.
			JsonObject fixed = display.getAsJsonObject("fixed");
			for (int axis = 0; axis < 3; axis++) {
				assertEquals(0, fixed.getAsJsonArray("translation").get(axis).getAsDouble());
				assertEquals(1, fixed.getAsJsonArray("scale").get(axis).getAsDouble());
			}
		}

		// The face is as wide relative to its height as the open panel is, so the CRT reads as the
		// same screen in hand and on screen. An earlier pass made the body nearly square, which
		// turned a landscape monitor into a portrait one.
		JsonObject chassis = JsonParser.parseString(Files.readString(
				ASSETS.resolve("models/item/old_terminal_held_0.json"), StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonArray("elements").get(0).getAsJsonObject();
		double width = chassis.getAsJsonArray("to").get(0).getAsDouble()
				- chassis.getAsJsonArray("from").get(0).getAsDouble();
		double height = chassis.getAsJsonArray("to").get(1).getAsDouble()
				- chassis.getAsJsonArray("from").get(1).getAsDouble();
		assertTrue(width / height >= 1.7D && width / height <= 2.3D,
				() -> "the device is not a landscape panel: " + width + "x" + height);

		// Nothing may still point at the folding shell, in either direction: a leftover model would
		// be shipped dead weight, and a leftover reference would be a missing-texture cube in hand.
		assertFalse(Files.exists(ASSETS.resolve("textures/item/old_terminal_shell.png")),
				"the shared fold atlas must not remain in the runtime pack");
		for (int frame = 0; frame < 6; frame++) {
			Path stale = ASSETS.resolve("models/item/old_terminal_fold_" + frame + ".json");
			assertFalse(Files.exists(stale), () -> "retired fold frame still present: " + stale);
		}

		String definition = Files.readString(ASSETS.resolve("items/old_terminal.json"), StandardCharsets.UTF_8);
		assertTrue(definition.contains("minecraft:display_context"),
				"The inventory icon keeps the flat art while every other view gets the shell");
		assertTrue(definition.contains("old_terminal_held_5"));
		assertFalse(definition.contains("old_terminal_fold"),
				"the item definition must not reference the retired fold frames");
		assertTrue(definition.contains("\"index\": 0"),
				"Slot 0 carries the visual stage and unread lamp, exactly as before");
		assertFalse(definition.contains("\"index\": 1"),
				"The second animation index is retired; the shell no longer has frames to select");

		String animator = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalHandheldAnimator.java"),
				StandardCharsets.UTF_8);
		// The rule is about the stack the *player* is holding, not about naming the component type.
		// Vanilla replays the equip swing whenever the visible stack changes, so writing components on
		// the held stack at animation or blink rate would re-equip the device several times a second.
		// Drawing a throwaway copy with a different model index does not touch it and is how the
		// unread lamp blinks on the device at all.
		assertFalse(animator.contains("held.set("),
				"the performance must never write components onto the stack the player is holding");
		assertTrue(animator.contains("held.copy()"),
				"the lamp-off form has to be drawn from a copy, or the blink re-equips the terminal");
		assertTrue(animator.contains("TerminalUiLayout.unreadLampBlinkOn"),
				"the device's lamp and the panel's lamp must blink off one clock, not two");
		// The server pushes a snapshot roughly once a second while the terminal is open. Restarting
		// the phase on each would hold the device mid-travel and the screen would never arrive.
		assertTrue(animator.contains("if (state == State.OPENING || state == State.OPEN) return;"),
				"A repeated snapshot must not restart the opening");
		assertTrue(animator.contains("!holdingTerminal(client)") && animator.contains("client.player == null"),
				"The animation must abort when the item or the player goes away");

		String mixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/ItemInHandRendererTerminalMixin.java"),
				StandardCharsets.UTF_8);
		assertTrue(mixin.contains("FIRST_PERSON_RIGHT_HAND") && mixin.contains("FIRST_PERSON_LEFT_HAND"),
				"The performance is the holder's own first-person view and nobody else's");
		assertTrue(mixin.contains("HumanoidArm.RIGHT") && mixin.contains("HumanoidArm.LEFT"),
				"The terminal is a two-handed instrument and is carried in both hands");
		assertTrue(mixin.contains("this.offHandItem.isEmpty()"),
				"Taking both hands must never silently hide whatever is in the off hand");
		assertTrue(mixin.contains("AnomalyPresentationController.isFirstPersonHandHidden()"),
				"A detached second-person camera must not get the hands back through this path");
		// Vanilla replays the equip animation whenever the visible stack changes, and decides that
		// by comparing components. The terminal rewrites custom_data every sync and
		// custom_model_data whenever the lamp moves, and neither type is exempt - so an open
		// terminal would drop out of the hands and climb back several times a second.
		assertTrue(mixin.contains("shouldInstantlyReplaceVisibleItem"),
				"The terminal's own state updates must not be mistaken for a change of item");
		assertTrue(mixin.contains("from.is(ModItems.OLD_TERMINAL) && to.is(ModItems.OLD_TERMINAL)"),
				"Only terminal-to-terminal is instant; real swaps keep vanilla's equip animation");
		assertTrue(mixin.contains("this.oMainHandHeight, this.mainHandHeight"),
				"The equip height must still be honoured, so selecting the terminal raises it");
		// Cancelling the method also skips vanilla's turning lag. Without it the device is welded
		// rigidly to the camera, and on something this large and this central that is the first
		// thing a player notices when they turn their head.
		assertTrue(mixin.contains("player.xBobO, player.xBob")
						&& mixin.contains("player.yBobO, player.yBob"),
				"The hands must keep trailing the view the way vanilla's do");
		// The failure this one guards is the worst the device has had. renderItem only submits
		// nodes; the flush that draws them is the last thing the cancelled method does. Without it
		// the terminal waited for the next frame's flush and was drawn against that frame's
		// matrices - permanently one frame stale, so turning the head threw it out of view.
		assertTrue(mixin.contains("getFeatureRenderDispatcher().renderAllFeatures()")
						&& mixin.contains("renderBuffers().bufferSource().endBatch()"),
				"Cancelling renderHandsWithItems must not skip the flush that actually draws");
		String fov = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/GameRendererTerminalFovMixin.java"),
				StandardCharsets.UTF_8);
		assertTrue(fov.contains("getCameraType().isFirstPerson()"),
				"The field-of-view lean must not follow the player into third person");

		String mixins = Files.readString(Path.of("src/main/resources/thefourthfrequency.mixins.json"),
				StandardCharsets.UTF_8);
		for (String registered : new String[]{"ItemInHandRendererTerminalMixin", "GameRendererTerminalFovMixin"}) {
			assertTrue(mixins.contains(registered), "An unregistered mixin silently does nothing");
		}
	}

	/**
	 * The odd forms are their even neighbour with the lamp lit, and nothing else.
	 *
	 * <p>Asserted on the pixels rather than on the generator, because the whole point of the pairing
	 * is that a player who sees the amber light knows it means "something is waiting" and not "the
	 * device changed". Any second difference between 0 and 1 would make the lamp ambiguous.</p>
	 */
	@Test
	void unreadFormsDifferFromTheirStageOnlyInsideTheLampWindow() throws Exception {
		// Matches LAMP in tools/generate_terminal_3d_assets.py, inclusive on both ends.
		int lampLeft = 101;
		int lampTop = 5;
		int lampRight = 107;
		int lampBottom = 11;
		for (int index = 0; index < 3; index++) {
			final int stage = index;
			var dark = ImageIO.read(ASSETS.resolve(
					"textures/item/old_terminal_shell_" + stage * 2 + ".png").toFile());
			var lit = ImageIO.read(ASSETS.resolve(
					"textures/item/old_terminal_shell_" + (stage * 2 + 1) + ".png").toFile());
			int differences = 0;
			for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) {
				if (dark.getRGB(x, y) == lit.getRGB(x, y)) continue;
				differences++;
				boolean insideLamp = x >= lampLeft && x <= lampRight && y >= lampTop && y <= lampBottom;
				final int fx = x;
				final int fy = y;
				assertTrue(insideLamp, () -> "stage " + stage + " forms differ outside the lamp at "
						+ fx + "," + fy);
			}
			final int found = differences;
			assertTrue(found > 0, () -> "stage " + stage + " lamp never lights up");
		}
	}

	@Test
	void currentFragmentMainlineUsesVanillaStructuresWithoutAllocatingFacilities() throws Exception {
		String fragments = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/FragmentInvestigationService.java"),
				StandardCharsets.UTF_8);
		for (String structure : new String[]{"MINESHAFT", "SHIPWRECK", "TRAIL_RUINS", "STRONGHOLD",
				"WOODLAND_MANSION", "DESERT_PYRAMID", "IGLOO", "TRIAL_CHAMBERS",
				"PILLAGER_OUTPOST", "JUNGLE_TEMPLE", "OCEAN_MONUMENT", "ANCIENT_CITY",
				"OCEAN_RUIN_COLD", "RUINED_PORTAL"})
			assertTrue(fragments.contains("BuiltinStructures." + structure), structure);
		assertTrue(fragments.contains("getStructureWithPieceAt"));
		// Leads are overheard, not searched for. `findNearestMapStructure` walked outward from Relay
		// Station Zero over 256 chunks and handed the player four coordinates before they had left the
		// building - none of them related to where they went, and each one the corner of a starting
		// chunk rather than the structure. The replacement reads the structure references already
		// recorded in loaded chunks, which is a map lookup rather than a search, and yields the real
		// StructureStart. Pinned in both directions so the old search cannot quietly come back.
		assertFalse(fragments.contains("findNearestMapStructure"),
				"Fragment leads must not search outward from the station again");
		assertTrue(fragments.contains("startsForStructure"),
				"Fragment leads come from the structure references in already-loaded chunks");
		assertTrue(fragments.contains("hasChunk"),
				"A lead scan must never generate the chunk it is asking about");
		assertTrue(fragments.contains("PrivateDimensions.isPrivate"),
				"A lead taken inside a private dimension would point at a place that stops existing - "
						+ "a mirror that is torn down, or the unrendered layer, whose coordinates mean "
						+ "nothing anywhere else");
		String terminalScreen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java"),
				StandardCharsets.UTF_8);
		assertFalse(terminalScreen.contains("0x0700FF70"),
				"The terminal display must not restore the persistent green scanline overlay");
		// The standing CRT layer lives in TerminalChrome now. What the rule above was protecting was
		// never "no scanlines" - it was the flat green wash, which tinted every glyph read through it
		// and cost more contrast than it bought. So the layer is allowed back on three conditions:
		// neutral (no hue), still (no rolling band across the whole readable area), and confined to
		// the display. Rolling distortion stays the sky monitor's vocabulary, where it means the
		// instrument is failing and lasts only as long as the fault.
		String chrome = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalChrome.java"),
				StandardCharsets.UTF_8);
		assertTrue(chrome.contains("SCANLINE_PITCH") && chrome.contains("SCANLINE_ALPHA"),
				"The CRT layer must state its pitch and opacity as named constants");
		assertFalse(chrome.contains("00FF70"),
				"The CRT layer must darken neutrally rather than tint the display green");
		assertFalse(chrome.contains("renderAge") || chrome.contains("ageTicks"),
				"The CRT shell must be static; rolling distortion belongs to the sky monitor");
		assertTrue(chrome.contains("TerminalUiLayout.DISPLAY"),
				"The CRT layer must be confined to the display rather than the whole panel");
		assertFalse(terminalScreen.contains("log.top() + 19"),
				"The signal header must not restore a full-width horizontal divider");
		assertFalse(terminalScreen.contains("y + ROW_HEIGHT - 1, 0x551C3A25"),
				"Signal selection must use text color instead of a full-row horizontal band");
		assertFalse(terminalScreen.contains("footer.top() + 1, DARK_BORDER"),
				"The footer must not restore a full-width horizontal divider");
		assertFalse(terminalScreen.contains("drawFragmentPulse"),
				"Private fragment state must not restore moving horizontal glitch lines");
		assertFalse(terminalScreen.contains("signal.objective_prefix"),
				"Hidden story gates must not be rendered as a persistent task checklist");
		// The expandable signal-card feed was removed: drawSignalToolList had no caller, so none of it
		// ever reached a frame. The assertions that used to pin its folded-card visual language went
		// with it; what replaced them is the guarantee that it does not come back unnoticed.
		assertFalse(terminalScreen.contains("drawSignalToolList"),
				"The orphaned signal-card feed must not return without a render path");
		// Was the reverse of this: for a long time nothing on screen could send SELECT_FRAGMENT_TARGET,
		// so the assertion guarded against a dead action. The records shortcut now sends it, which is
		// what makes that shortcut aim at its own row instead of at the nearest lead - so the property
		// worth pinning is that it still does, and that it no longer settles for the nearest one.
		assertTrue(terminalScreen.contains("TerminalControlPayload.SELECT_FRAGMENT_TARGET"),
				"The records shortcut must aim at the lead on its own row");
		int recordShortcut = terminalScreen.indexOf("private void drawRecordNavigationShortcut");
		int recordClickEnd = terminalScreen.indexOf("private void drawCard", recordShortcut);
		assertTrue(recordShortcut >= 0 && recordClickEnd > recordShortcut);
		assertFalse(terminalScreen.substring(recordShortcut, recordClickEnd).contains("SELECT_NEAREST_UNSTABLE"),
				"A per-row shortcut must not fall back to the nearest lead");
		// The shortcut and the navigator's own option have to be the same offer. They were decided
		// apart once already: the log announced a lead on day one and the navigator refused to route
		// to it until the Nether, so the page drew a control that did nothing when pressed.
		assertTrue(terminalScreen.contains("boolean investigationOffered = tools.unstableSignalAvailable();"),
				"The records shortcut must be drawn from the same offer the navigation page draws");

		String fragmentInvestigation = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/world/FragmentInvestigationService.java"),
				StandardCharsets.UTF_8);
		assertTrue(fragmentInvestigation.contains("SignalBand.WEATHER, SignalBand.MINING, SignalBand.PUBLIC, SignalBand.UNKNOWN"),
				"Legacy log wire ids remain stable while the client aggregates every fragment event");
		assertTrue(fragmentInvestigation.contains("if (record.getIntOr(TerminalData.BAND_STAGE, 0) == 0) return false;"),
				"Structure coordinates stay out of the signal log before the investigation gate");

		assertFalse(fragmentInvestigation.contains("unlockArchiveFromFragments"),
				"Discovering all hidden files must not unlock the complete diary before they are read");
	}

	@Test
	void everyStaticTranslatableKeyExistsAndCoreClientScreensContainNoHardcodedChinese() throws Exception {
		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		Set<String> keys = new HashSet<>(en.keySet());
		Pattern translatable = Pattern.compile("Component\\.translatable\\(\\s*\"([^\"]+)\"\\s*(?:,|\\))");
		for (Path root : new Path[]{Path.of("src/main/java"), Path.of("src/client/java")}) {
			for (Path path : Files.walk(root).filter(value -> value.toString().endsWith(".java")).toList()) {
				Matcher matcher = translatable.matcher(Files.readString(path, StandardCharsets.UTF_8));
				while (matcher.find()) assertTrue(keys.contains(matcher.group(1)), path + " -> " + matcher.group(1));
			}
		}
		Pattern chineseLiteral = Pattern.compile("\"[^\"\\r\\n]*[\\x{3400}-\\x{9FFF}][^\"\\r\\n]*\"");
		for (String relative : new String[]{
				"src/client/java/com/xm/thefourthfrequency/client_ui/TerminalScreen.java",
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldDecayClient.java"}) {
			String source = Files.readString(Path.of(relative), StandardCharsets.UTF_8);
			assertFalse(chineseLiteral.matcher(source).find(), relative);
		}
	}

	@Test
	void debugPanelKeepsItsKeysAndDrivesEveryListFromItsCatalogue() throws Exception {
		String client = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DebugPanelClient.java"), StandardCharsets.UTF_8);
		String screen = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DebugPanelScreen.java"), StandardCharsets.UTF_8);
		String hud = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DebugHud.java"), StandardCharsets.UTF_8);
		assertTrue(client.contains("GLFW.GLFW_KEY_M"));
		assertTrue(client.contains("GLFW.GLFW_KEY_N"));
		assertFalse(client.contains("GLFW.GLFW_KEY_F7"));
		// The tab strip is the only navigation the rewrite left. The overview page it replaced is
		// named here so that reintroducing it has to be a deliberate edit to this assertion: it is
		// what made every number on this screen appear twice.
		for (String tab : new String[]{"异象", "文件", "节点"})
			assertTrue(screen.contains(tab), tab);
		assertFalse(screen.contains("OVERVIEW"), "the overview page is back");
		assertFalse(screen.contains("ENDING(\"终局\")"));
		for (String removed : new String[]{"local_file_prev", "local_facility_prev", "local_anomaly_prev",
				"显示设施坐标", "解锁当前文件"})
			assertFalse(screen.contains(removed), removed);
		for (String internalTerm : new String[]{"停止/恢复租约", "满复合", "恢复异象导演", "BOSS：", "造身：",
				"最终实体", "肉身映射", "剧情上限"})
			assertFalse(screen.contains(internalTerm), internalTerm);
		assertTrue(screen.contains("AnomalyCatalog.definitions()"));
		assertTrue(screen.contains("NarrativeFileCatalog.definitions()"));
		assertTrue(screen.contains("SurvivalMilestone.values()"));
		// The panel stopped polling when the server started pushing, and the HUD is the reason: a
		// readout that only updates while a screen is open is not a readout.
		assertFalse(screen.contains("send(\"poll\""), "the panel is polling again beside the server push");
		assertTrue(hud.contains("HudRenderCallback.EVENT.register"));
		assertTrue(screen.contains("file_unlock"));
		assertTrue(screen.contains("file_lock"));
		assertTrue(screen.contains("tabCountForTesting()"));
		assertTrue(screen.contains("mouseScrolled"));
	}

	/**
	 * The capture scream, and the one thing about it that a container check cannot see.
	 *
	 * <p>Minecraft decodes Ogg <em>Vorbis</em> only. The file this shipped from arrived as a {@code
	 * .ogg} carrying a FLAC stream, which passes every "is it an Ogg" check in this class - the
	 * capital-S {@code OggS} page header is identical - and then fails at runtime by playing nothing
	 * at all, on a cue whose whole job is to be heard once. So this asserts the codec rather than the
	 * wrapper, by looking for the Vorbis identification packet that opens the first page.
	 */
	@Test
	void theCaptureScreamIsAVorbisStreamWithSubtitlesInBothLanguages() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		var definition = sounds.getAsJsonObject("pursuit_capture_scream");
		assertTrue(definition != null, "pursuit_capture_scream is missing from sounds.json");
		assertEquals("subtitles.thefourthfrequency.pursuit_capture_scream",
				definition.get("subtitle").getAsString());
		assertEquals(1, definition.getAsJsonArray("sounds").size(),
				"one scream, not a randomised set - it is the same event every time");

		String name = definition.getAsJsonArray("sounds").get(0).getAsString();
		Path ogg = ASSETS.resolve("sounds/" + name.substring(name.indexOf(':') + 1) + ".ogg");
		assertTrue(Files.isRegularFile(ogg), ogg.toString());
		byte[] header = Files.readAllBytes(ogg);
		assertTrue(header.length > 4 && header[0] == 'O' && header[1] == 'g'
				&& header[2] == 'g' && header[3] == 'S', ogg.toString());
		byte[] vorbis = {0x01, 'v', 'o', 'r', 'b', 'i', 's'};
		boolean identified = false;
		for (int index = 0; index + vorbis.length <= Math.min(header.length, 256) && !identified; index++) {
			identified = true;
			for (int offset = 0; offset < vorbis.length; offset++) {
				if (header[index + offset] != vorbis[offset]) {
					identified = false;
					break;
				}
			}
		}
		assertTrue(identified, ogg + " is an Ogg container but not a Vorbis stream");

		for (String language : new String[]{"zh_cn", "en_us"}) {
			JsonObject lang = JsonParser.parseString(Files.readString(
					ASSETS.resolve("lang/" + language + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
			String key = "subtitles.thefourthfrequency.pursuit_capture_scream";
			assertTrue(lang.has(key), "missing " + language + ": " + key);
			assertFalse(lang.get(key).getAsString().isBlank(), "blank " + language + ": " + key);
		}
	}

	@Test
	void semanticAnomalySoundsHaveMatchingSubtitles() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		for (String event : new String[]{"anomaly_echo", "window_glitch", "door_cascade", "rule_collapse"}) {
			assertTrue(sounds.has(event), event);
			assertTrue(sounds.getAsJsonObject(event).has("subtitle"), event);
		}
		assertFalse(sounds.has("hostile_echo"));
		assertFalse(sounds.has("composite_breach"));
		for (String event : new String[]{"rework_joint"}) {
			var definition = sounds.getAsJsonObject(event);
			assertTrue(definition.has("subtitle"), event);
			for (var sound : definition.getAsJsonArray("sounds")) {
				String name = sound.getAsString();
				assertTrue(name.startsWith("thefourthfrequency:entity/"), name);
				Path ogg = ASSETS.resolve("sounds/" + name.substring(name.indexOf(':') + 1) + ".ogg");
				assertTrue(Files.isRegularFile(ogg), ogg.toString());
				byte[] header = Files.readAllBytes(ogg);
				assertTrue(header.length > 4 && header[0] == 'O' && header[1] == 'g'
						&& header[2] == 'g' && header[3] == 'S', ogg.toString());
			}
		}
	}

	@Test
	void anomalyArtHasRequiredDimensionsAlphaAndSourceMasters() throws Exception {
		var hand = ImageIO.read(ASSETS.resolve("textures/gui/anomaly/peripheral_hand.png").toFile());
		assertEquals(512, hand.getWidth());
		assertEquals(256, hand.getHeight());
		assertTrue(hand.getColorModel().hasAlpha());
		boolean transparent = false;
		boolean opaque = false;
		for (int y = 0; y < hand.getHeight(); y += 8) for (int x = 0; x < hand.getWidth(); x += 8) {
			int alpha = hand.getRGB(x, y) >>> 24;
			transparent |= alpha == 0;
			opaque |= alpha > 220;
		}
		assertTrue(transparent && opaque, "Single hand texture must contain transparent background and visible hand");
		var eye = ImageIO.read(ASSETS.resolve("textures/gui/anomaly/eye_item.png").toFile());
		assertEquals(128, eye.getWidth());
		assertEquals(128, eye.getHeight());
		assertTrue(eye.getColorModel().hasAlpha());
		assertEquals(0, eye.getRGB(0, 0) >>> 24);
		assertEquals(0, eye.getRGB(eye.getWidth() - 1, eye.getHeight() - 1) >>> 24);
		var windowEye = ImageIO.read(ASSETS.resolve("textures/gui/anomaly/eye_window.png").toFile());
		assertEquals(64, windowEye.getWidth());
		assertTrue(windowEye.getColorModel().hasAlpha());
		assertEquals(0, windowEye.getRGB(0, 0) >>> 24);
		Path watcherPath = ASSETS.resolve("textures/entity/watcher.png");
		Path watcherEmissivePath = ASSETS.resolve("textures/entity/watcher_emissive.png");
		assertTrue(Files.isRegularFile(watcherPath));
		assertTrue(Files.isRegularFile(watcherEmissivePath));
		var watcher = ImageIO.read(watcherPath.toFile());
		var watcherEmissive = ImageIO.read(watcherEmissivePath.toFile());
		assertEquals(256, watcher.getWidth()); assertEquals(256, watcher.getHeight());
		assertEquals(256, watcherEmissive.getWidth()); assertEquals(256, watcherEmissive.getHeight());
		assertTrue(watcher.getColorModel().hasAlpha());
		assertTrue(watcherEmissive.getColorModel().hasAlpha());
		int nonTransparent = 0;
		int maximumAlpha = 0;
		for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
			assertEquals(255, watcher.getRGB(x, y) >>> 24, "base alpha at " + x + "," + y);
			int alpha = watcherEmissive.getRGB(x, y) >>> 24;
			if (alpha == 0) continue;
			nonTransparent++;
			maximumAlpha = Math.max(maximumAlpha, alpha);
			assertTrue(x >= 160 && x < 240 && y < 16,
					"emissive pixel escaped the eye UV at " + x + "," + y);
		}
		assertTrue(nonTransparent > 0 && nonTransparent <= 256 * 256 * 0.08,
				"emissive coverage=" + nonTransparent);
		assertTrue(maximumAlpha >= 112 && maximumAlpha <= 120, "emissive max alpha=" + maximumAlpha);
		assertNotEquals(java.util.Arrays.hashCode(Files.readAllBytes(watcherPath)),
				java.util.Arrays.hashCode(Files.readAllBytes(watcherEmissivePath)));
		assertFalse(Files.exists(ASSETS.resolve("textures/entity/watcher_eyes.png")));
		assertFalse(Files.exists(ASSETS.resolve("textures/entity/orbiter.png")));
		assertEquals(32, ImageIO.read(ASSETS.resolve("textures/block/missing_texture.png").toFile()).getWidth());
		assertTrue(Files.isRegularFile(Path.of("tools/assets/anomaly/eye_master.png")));
		assertTrue(Files.isRegularFile(Path.of("tools/assets/anomaly/hand_palm_long_master.png")));
		assertFalse(Files.exists(Path.of("tools/assets/anomaly/peripheral_hands.gif")));
	}

	@Test
	void worldInterfaceSheetsAreIslandPaintedAtUniformDensityWithContainedGlow() throws Exception {
		// The model is authored in Blockbench and exported to JSON; the sheets are painted from the
		// same file. What has to hold is that the two were generated together: every UV offset the
		// geometry samples is an island the painter painted, at one texel density on both axes.
		var geometry = com.google.gson.JsonParser.parseString(Files.readString(Path.of(
				"src/main/resources/assets/thefourthfrequency/models/entity/world_interface.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		int uvWidth = geometry.getAsJsonArray("texture").get(0).getAsInt();
		int uvHeight = geometry.getAsJsonArray("texture").get(1).getAsInt();

		String[] bases = {"world_interface_form_1", "world_interface_form_2",
				"world_interface_form_3", "world_interface_form_3_black"};
		String[] overlays = {"world_interface_form_1_emissive", "world_interface_form_2_emissive",
				"world_interface_form_3_emissive", "world_interface_form_1_hit",
				"world_interface_form_2_hit", "world_interface_form_3_hit"};
		int sheetWidth = -1;
		int sheetHeight = -1;
		for (String name : bases) {
			Path path = ASSETS.resolve("textures/entity/" + name + ".png");
			assertTrue(Files.isRegularFile(path), name);
			var image = ImageIO.read(path.toFile());
			if (sheetWidth < 0) {
				sheetWidth = image.getWidth();
				sheetHeight = image.getHeight();
			}
			assertEquals(sheetWidth, image.getWidth(), name);
			assertEquals(sheetHeight, image.getHeight(), name);
			for (int y = 0; y < sheetHeight; y += 3) for (int x = 0; x < sheetWidth; x += 3) {
				assertEquals(255, image.getRGB(x, y) >>> 24, name + " alpha at " + x + "," + y);
			}
		}
		// A non-square texel would stretch every island along one axis; the painter scales both
		// canvas dimensions by one factor, and this is what keeps a hand-resized PNG from shipping.
		assertEquals(sheetWidth / uvWidth, sheetHeight / uvHeight, "texel density must match on both axes");
		assertEquals(0, sheetWidth % uvWidth);
		assertEquals(0, sheetHeight % uvHeight);
		// The third form is scaled twelve times; at one texel per unit a texel covered most of a
		// block, which is what made the body read as untextured from anywhere close to it.
		assertTrue(sheetWidth / uvWidth >= 4, "texel density=" + sheetWidth / uvWidth);

		// The glow and the flash carry no fine detail and are read at arena distance, so they ship
		// at a lower density than the base - but on the same canvas, at one density of their own.
		int overlayWidth = -1;
		int overlayHeight = -1;
		for (String name : overlays) {
			Path path = ASSETS.resolve("textures/entity/" + name + ".png");
			assertTrue(Files.isRegularFile(path), name);
			var image = ImageIO.read(path.toFile());
			if (overlayWidth < 0) {
				overlayWidth = image.getWidth();
				overlayHeight = image.getHeight();
				assertEquals(overlayWidth / uvWidth, overlayHeight / uvHeight, "overlay texel density");
				assertEquals(0, overlayWidth % uvWidth);
				assertTrue(overlayWidth / uvWidth >= 2, "overlay density=" + overlayWidth / uvWidth);
			}
			assertEquals(overlayWidth, image.getWidth(), name);
			assertEquals(overlayHeight, image.getHeight(), name);
			assertTrue(image.getColorModel().hasAlpha(), name);
			boolean glow = name.endsWith("_emissive");
			int nonTransparent = 0;
			for (int y = 0; y < overlayHeight; y++) for (int x = 0; x < overlayWidth; x++) {
				if ((image.getRGB(x, y) >>> 24) != 0) nonTransparent++;
			}
			assertTrue(nonTransparent > 0, name + " is entirely transparent");
			double coverage = nonTransparent / (double) (overlayWidth * overlayHeight);
			// Glow is confined by the painter to the eye, kernel, node and socket islands - a few
			// shared islands lighting every aperture and node on the body - so it is a sliver of
			// the canvas. The flash covers every island. Both bounded so neither regresses to the
			// sprayed specks or the invisible scribble this replaced.
			assertTrue(coverage <= (glow ? 0.04 : 0.60), name + " coverage=" + coverage);
			if (glow) assertTrue(coverage >= 0.0005,
					name + " coverage=" + coverage + " is too faint to read in the arena");
			// Atlas padding grows with fine geometry. Measure flash on occupied UV islands,
			// including one-pixel faces, rather than requiring unused atlas space to light up.
			if (!glow) for (String line : Files.readAllLines(Path.of("docs/art/world_interface/layout.txt"))) {
				if (line.startsWith("#") || line.isBlank()) continue;
				String[] fields = line.split(" ");
				int x = Integer.parseInt(fields[3]) * overlayWidth / sheetWidth;
				int y = Integer.parseInt(fields[4]) * overlayHeight / sheetHeight;
				assertTrue((image.getRGB(x, y) >>> 24) >= 108,
						name + " has no damage flash on " + fields[0] + " at " + x + "," + y);
			}
		}

		// The exported geometry and the painted sheets come from one bbmodel but are checked in
		// separately, so regenerating either alone leaves parts sampling rectangles nothing was
		// painted into. layout.txt is written by the exporter with a probe texel per island.
		var manifest = Files.readAllLines(Path.of("docs/art/world_interface/layout.txt"),
				StandardCharsets.UTF_8).stream().filter(line -> !line.startsWith("#")).toList();
		assertFalse(manifest.isEmpty());
		var manifestOffsets = new java.util.HashSet<String>();
		var baseSheet = ImageIO.read(
				ASSETS.resolve("textures/entity/world_interface_form_3.png").toFile());
		for (String line : manifest) {
			String[] fields = line.split(" ");
			assertEquals(5, fields.length, line);
			manifestOffsets.add(fields[1] + "," + fields[2]);
			int probeX = Integer.parseInt(fields[3]);
			int probeY = Integer.parseInt(fields[4]);
			// Unpainted sheet is a per-column constant, so the bottom row is the dead value for
			// this column. A probe that matches it means the island was never painted there.
			assertNotEquals(baseSheet.getRGB(probeX, sheetHeight - 1),
					baseSheet.getRGB(probeX, probeY),
					fields[0] + " samples unpainted sheet at " + probeX + "," + probeY);
		}
		int checked = 0;
		var sampled = new java.util.HashSet<String>();
		for (var bone : geometry.getAsJsonArray("bones")) {
			for (var cube : bone.getAsJsonObject().getAsJsonArray("cubes")) {
				var uv = cube.getAsJsonObject().getAsJsonArray("uv");
				String offset = uv.get(0).getAsInt() + "," + uv.get(1).getAsInt();
				assertTrue(manifestOffsets.contains(offset),
						cube.getAsJsonObject().get("name").getAsString() + " samples " + offset
								+ ", which is not an island in layout.txt");
				sampled.add(offset);
				checked++;
			}
		}
		assertTrue(checked >= 300, "the model samples only " + checked + " cubes");
		assertEquals(manifestOffsets, sampled, "every island must be reachable from the model");

		String model = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WorldInterfaceModel.java"),
				StandardCharsets.UTF_8);
		assertTrue(model.contains("WorldInterfaceGeometry.load().layer()"));
		assertFalse(model.contains("texOffs("), "WorldInterfaceModel must not carry UV offsets of its own");
		assertTrue(model.contains("void submitEmissive("));

		String renderer = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WorldInterfaceRenderer.java"),
				StandardCharsets.UTF_8);
		// The steady-state glow must not walk the whole body; only the damage flash still does.
		assertTrue(renderer.contains("getParentModel().submitEmissive("));
		assertEquals(1, count(renderer, "submitModel(getParentModel()"),
				"only the damage flash may submit the entire model a second time");
		assertTrue(renderer.contains("RenderTypes.entityTranslucentEmissive(HIT[form])"));

		String beams = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WorldInterfaceBeamBatchRenderer.java"),
				StandardCharsets.UTF_8);
		// The beam scatter stays in its one shared class rather than being copied into the renderer.
		assertTrue(beams.contains("WorldInterfaceScatter.hash(seed)"));
		assertFalse(beams.contains("374761393"), "beam renderer must not carry its own scatter");
		assertTrue(Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WorldInterfaceScatter.java"),
				StandardCharsets.UTF_8).contains("374761393"));
	}

	private static int count(String haystack, String needle) {
		int total = 0;
		for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) total++;
		return total;
	}

	@Test
	void anomalyPresentationUsesV3ControllerWithoutPotionOrPromptDependencies() throws Exception {
		String networking = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/networking/TerminalNetworking.java"), StandardCharsets.UTF_8);
		assertTrue(networking.contains("AnomalyStartS2C.TYPE"));
		assertTrue(networking.contains("AnomalyPhaseS2C.TYPE"));
		assertTrue(networking.contains("AnomalyCompleteC2S.TYPE"));
		assertFalse(networking.contains("AmbientAnomalyPayload"));
		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AnomalyPresentationController.java"),
				StandardCharsets.UTF_8);
		for (String forbidden : new String[]{"MobEffect", "MobEffects", "displayClientMessage",
				"setOverlayMessage", "setActionBarText"}) assertFalse(controller.contains(forbidden), forbidden);
		String runtime = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/AnomalyRuntimeService.java"), StandardCharsets.UTF_8);
		assertTrue(runtime.contains("ACTIVE.containsKey(player)"));
		assertTrue(runtime.contains("earliestCompletionTick"));
		assertTrue(runtime.contains("recordCompleted"));
	}

	@Test
	void desktopPresenceTypesIntoOwnedForegroundVerifiedNotepad() throws Exception {
		String source = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/meta_windows/WindowsAnomalyController.java"),
				StandardCharsets.UTF_8);
		assertTrue(source.contains("meta/desktop_presence.txt"));
		assertTrue(source.contains("Files.createTempDirectory"));
		assertTrue(source.contains("Files.createFile"));
		assertFalse(source.contains("Files.copy"));
		assertTrue(source.contains("GetForegroundWindow"));
		assertTrue(source.contains("GetWindowThreadProcessId"));
		assertTrue(source.contains("AttachThreadInput"));
		assertTrue(source.contains("SendInput"));
		assertTrue(source.contains("MOUSEINPUT"));
		assertTrue(source.contains("powershell.exe"));
		assertTrue(source.contains("OwnedTree"));
		assertTrue(source.contains("ZoomToMaximum"));
		assertTrue(source.contains("VK_OEM_PLUS"));
		assertTrue(source.contains("step < 64"));
		assertTrue(source.contains("descendants()"));
		assertTrue(source.contains("notepad.exe"));
		assertTrue(Files.isRegularFile(ASSETS.resolve("meta/desktop_presence.txt")));
	}

	@Test
	void revisedAnomaliesUseTransparentWatcherDenseFogAndFixedTriggerView() throws Exception {
		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AnomalyPresentationController.java"),
				StandardCharsets.UTF_8);
		String watcher = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WatcherRenderer.java"),
				StandardCharsets.UTF_8);
		String watcherModel = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WatcherModel.java"),
				StandardCharsets.UTF_8);
		String watcherState = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_render/WatcherRenderState.java"),
				StandardCharsets.UTF_8);
		String clientInitializer = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/TheFourthFrequencyClient.java"),
				StandardCharsets.UTF_8);
		String skyMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/SkyRendererAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String fogMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/FogRendererAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String optionsMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/OptionsAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String renderDistanceOptionMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/OptionInstanceRenderDistanceMixin.java"),
				StandardCharsets.UTF_8);
		String viewDistancePolicy = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/client_ui/DimensionViewDistancePolicy.java"),
				StandardCharsets.UTF_8);
		String viewDistanceController = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DimensionViewDistanceController.java"),
				StandardCharsets.UTF_8);
		String modConfig = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/config/ModConfig.java"),
				StandardCharsets.UTF_8);
		String inputMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/KeyboardInputAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String handMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/ItemInHandRendererAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String entityRendererMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/AvatarRendererAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String renderRegionMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/RenderSectionRegionAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String itemNameMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/ItemStackAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String localPlayerMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/LocalPlayerAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		String mixinConfig = Files.readString(Path.of("src/main/resources/thefourthfrequency.mixins.json"),
				StandardCharsets.UTF_8);
		String channel = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/ChannelOverrideScreen.java"),
				StandardCharsets.UTF_8);
		assertFalse(controller.contains("ModEntities.ORBITER"));
		assertTrue(controller.contains("redSkyShaderColor"));
		assertTrue(controller.contains("-HAND_TEXTURE_WIDTH"));
		assertFalse(controller.contains("scale(-1.0F"));
		assertFalse(controller.contains("alpha << 24 | 0x00A01018"));
		assertTrue(controller.contains("CameraType.FIRST_PERSON"));
		assertTrue(controller.contains("trigger_view_camera_fixed"));
		assertTrue(controller.contains("SECOND_PERSON_BODY_ID"));
		assertTrue(controller.contains("second_person_body_proxy"));
		assertTrue(controller.contains("secondPersonBody.noPhysics = true"));
		assertTrue(controller.contains("cameraAnchor.noPhysics = true"));
		assertTrue(controller.contains("shouldControlSeparatedPlayer"));
		assertTrue(controller.contains("action_echo_animation"));
		assertTrue(controller.contains("levelRenderer.allChanged()"));
		assertTrue(controller.contains("PERIPHERAL_HAND_ENTER_FRACTION = 0.42F"));
		assertTrue(controller.contains("width * 0.58F"));
		assertTrue(controller.contains("drawWidth * 0.78F"));
		assertTrue(controller.contains("width * 0.42F"));
		assertTrue(controller.contains("anomalyId.equals(\"peripheral_residue\") && !glitchTriggered"));
		assertTrue(controller.contains("PERIPHERAL_HAND_CREEP_FRACTION"),
				"The palms must keep reaching after the slide rather than freezing into a decal");
		// The corruption burst owns none of its own numbers, and it must never go back to being a
		// white fill under neon bars and enchantment-table glyphs.
		assertTrue(controller.contains("GlitchImpactTimeline.IMPACT_TICKS"));
		assertTrue(controller.contains("renderGlitchDebris"),
				"The hands have to be torn apart by the burst, not switched off before it");
		assertFalse(controller.contains("ChatFormatting.OBFUSCATED"));
		assertFalse(controller.contains("0x00C000E8"));
		assertFalse(controller.contains("0x00F4F4F4"));
		assertTrue(controller.contains("LOCAL_RULE_FRAGMENT_LIMIT = 24"));
		assertTrue(controller.contains("LOCAL_RULE_MIN_SPACING_SQR = 9.0D"));
		assertTrue(controller.contains("separatedFromExistingTraces"));
		assertFalse(controller.contains("LIGHT_DROPOUT_SCAN_RADIUS"));
		assertFalse(controller.contains("HIDDEN_LIGHTS"));
		assertFalse(controller.contains("lightDropoutCenter"));
		assertFalse(controller.contains("lightVisibleFrom"));
		assertTrue(controller.contains("missing_texture_proxies_rendered"));
		assertTrue(controller.contains("isInViewCone"));
		String itemMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/GuiGraphicsAnomalyMixin.java"),
				StandardCharsets.UTF_8);
		assertTrue(itemMixin.contains("ItemStack;III)V"));
		assertTrue(itemMixin.contains("LivingEntity;Lnet/minecraft/world/item/ItemStack;III)V"));
		assertTrue(watcher.contains("extends MobRenderer<WatcherEntity, WatcherRenderState, WatcherModel>"));
		assertFalse(watcher.contains("submitCustomGeometry"));
		assertFalse(watcher.contains("RenderTypes.eyes"));
		assertFalse(watcher.contains("textures/gui/anomaly/eye_item.png"));
		assertFalse(watcher.contains("getBlockLightLevel"));
		assertTrue(watcher.contains("textures/entity/watcher.png"));
		assertTrue(watcher.contains("textures/entity/watcher_emissive.png"));
		assertTrue(watcher.contains("entityTranslucentEmissive"));
		assertTrue(watcher.contains("0.22F"));
		assertTrue(watcher.contains("shadowStrength = 0.25F"));
		assertTrue(watcher.contains("physical.minX - 0.35D"));
		assertTrue(watcher.contains("physical.minY - 0.15D"));
		assertTrue(watcher.contains("onWatcherVisible"));
		assertTrue(watcherModel.contains("extends EntityModel<WatcherRenderState>"));
		for (String part : new String[]{"torso", "neck", "head", "left_arm", "right_arm", "left_leg",
				"right_leg", "hand", "spine", "left_scapula", "right_scapula", "eye", "iris", "pupil"}) {
			assertTrue(watcherModel.contains("\"" + part + "\""), part);
		}
		assertTrue(watcherModel.contains("LayerDefinition.create(mesh, 128, 128)"));
		assertTrue(watcherModel.contains("FULL_TURN / 120.0F"));
		assertTrue(watcherModel.contains("Mth.sin(irisPhase) * 0.03F"));
		assertTrue(watcherModel.contains("iris.xScale = irisScale"));
		assertFalse(watcherModel.toLowerCase(java.util.Locale.ROOT).contains("eyelid"));
		assertTrue(watcherState.contains("extends LivingEntityRenderState"));
		assertTrue(clientInitializer.contains("registerModelLayer(WatcherRenderer.MODEL_LAYER"));
		assertTrue(clientInitializer.contains("DimensionViewDistanceController.initialize()"));
		assertTrue(skyMixin.contains("state.skyColor"));
		assertTrue(skyMixin.contains("state.sunriseAndSunsetColor"));
		assertTrue(fogMixin.contains("setupFog"));
		assertTrue(fogMixin.contains("index = 5"));
		assertTrue(fogMixin.contains("index = 6"));
		assertTrue(fogMixin.contains("28.0F"));
		assertTrue(fogMixin.contains("AtmosphericFogProfile.sample"));
		assertTrue(fogMixin.contains("level.dimension().identifier().toString()"));
		assertTrue(fogMixin.contains("DimensionViewDistanceController.atmosphericChunks"));
		assertTrue(fogMixin.contains("clampRenderStart"));
		assertTrue(fogMixin.contains("clampRenderEnd"));
		assertTrue(viewDistancePolicy.contains("OVERWORLD_CHUNKS = 6")
				&& viewDistancePolicy.contains("NETHER_CHUNKS = 12")
				&& viewDistancePolicy.contains("END_CHUNKS = 16")
				&& viewDistancePolicy.contains("OTHER_CHUNKS = 12")
				&& viewDistancePolicy.contains("SUCCESS_RETURN_CHUNKS = 16"));
		assertTrue(viewDistanceController.contains("ClientTickEvents.END_CLIENT_TICK")
				&& viewDistanceController.contains("renderDistance().set(locked)")
				&& viewDistanceController.contains("successfulReturnPending")
				&& viewDistanceController.contains("Level.OVERWORLD.equals(client.level.dimension())")
				&& viewDistanceController.contains("ModConfig.ClientState::unlockViewDistance")
				&& viewDistanceController.contains("SUCCESS_RETURN_CHUNKS")
				&& viewDistanceController.contains("client.options.save()"));
		assertTrue(modConfig.contains("boolean viewDistanceUnlocked")
				&& modConfig.contains("unlockViewDistance()"));
		assertTrue(optionsMixin.contains("DimensionViewDistanceController.lockedChunks")
				&& optionsMixin.contains("DimensionViewDistanceController.isLocked()"));
		assertFalse(optionsMixin.contains("FIXED_RENDER_DISTANCE_CHUNKS"));
		assertFalse(optionsMixin.contains("setReturnValue(2)"));
		assertTrue(renderDistanceOptionMixin.contains("widget.active = false"));
		assertTrue(renderDistanceOptionMixin.contains("rejectRenderDistanceChanges"));
		assertTrue(renderDistanceOptionMixin.contains("@ModifyVariable"));
		assertTrue(renderDistanceOptionMixin.contains("DimensionViewDistanceController.lockedChunks")
				&& renderDistanceOptionMixin.contains("DimensionViewDistanceController.isLocked()"));
		assertTrue(renderDistanceOptionMixin.contains("render_distance_locked"));
		assertTrue(mixinConfig.contains("OptionInstanceRenderDistanceMixin"));
		assertTrue(inputMixin.contains("keyPresses = Input.EMPTY"));
		assertTrue(handMixin.contains("renderHandsWithItems"));
		assertTrue(entityRendererMixin.contains("isAnonymousProxy"));
		assertTrue(renderRegionMixin.contains("visualReplacement"));
		// Air has no hardness, no drops and nothing to mine, so a proxy painted over it is a solid
		// face the player can never remove. Both substitution points must refuse air on their own.
		for (String substitutionSource : new String[]{
				"src/client/java/com/xm/thefourthfrequency/client_ui/AnomalyPresentationController.java",
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldInterfacePresentationController.java"}) {
			assertTrue(Files.readString(Path.of(substitutionSource), StandardCharsets.UTF_8)
					.contains("original.isAir()) return original"), substitutionSource);
		}
		assertTrue(renderRegionMixin.contains("markTraceRendered"));
		assertFalse(renderRegionMixin.contains("isLightSourceHidden"));
		assertFalse(mixinConfig.contains("LevelRendererAnomalyMixin"));
		assertTrue(itemNameMixin.contains("getHoverName"));
		assertTrue(itemNameMixin.contains("I SEE YOU...."));
		assertTrue(localPlayerMixin.contains("isControlledCamera"));
		assertTrue(localPlayerMixin.contains("shouldControlSeparatedPlayer"));
		assertTrue(mixinConfig.contains("ItemStackAnomalyMixin"));
		assertTrue(mixinConfig.contains("LocalPlayerAnomalyMixin"));
		assertTrue(channel.contains("extends ChatScreen"));
		assertTrue(channel.contains("input.setEditable(false)"));
	}

	@Test
	void alphaSessionEmbedsAndHidesThreeOrderedBasePacks() throws Exception {
		Path packs = Path.of("src/main/resources/resourcepacks");
		Path alpha = packs.resolve("golden_days_alpha");
		Path base = packs.resolve("golden_days_base");
		for (Path pack : List.of(alpha, base)) {
			assertTrue(Files.isRegularFile(pack.resolve("pack.mcmeta")), pack.toString());
			assertTrue(Files.isRegularFile(pack.resolve("pack.png")), pack.toString());
			JsonObject metadata = JsonParser.parseString(Files.readString(pack.resolve("pack.mcmeta"),
					StandardCharsets.UTF_8)).getAsJsonObject();
			assertTrue(metadata.has("pack"));
			assertTrue(Files.isDirectory(pack.resolve("assets/minecraft")));
		}
		assertTrue(Files.isDirectory(base.resolve("patch_21_11")));
		assertTrue(Files.isRegularFile(base.resolve("credits.txt")));
		assertTrue(Files.walk(alpha).filter(Files::isRegularFile).count() > 500);
		assertTrue(Files.walk(base).filter(Files::isRegularFile).count() > 4_000);

		String controller = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AlphaLoadSessionController.java"),
				StandardCharsets.UTF_8);
		String configManager = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/config/ConfigManager.java"),
				StandardCharsets.UTF_8);
		String config = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/config/ModConfig.java"),
				StandardCharsets.UTF_8);
		String plan = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/client_ui/AlphaResourcePackPlan.java"),
				StandardCharsets.UTF_8);
		String packMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/PackSelectionModelHiddenPacksMixin.java"),
				StandardCharsets.UTF_8);
		String loadingMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/LevelLoadingScreenCorruptionMixin.java"),
				StandardCharsets.UTF_8);
		String overlayMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/LoadingOverlaySuppressionMixin.java"),
				StandardCharsets.UTF_8);
		String persistentLoadingStyle = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/PersistentAlphaLoadingStyle.java"),
				StandardCharsets.UTF_8);
		String titleMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/TitleScreenErosionMixin.java"),
				StandardCharsets.UTF_8);
		String worldDecay = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/WorldDecayClient.java"),
				StandardCharsets.UTF_8);
		String mixinConfig = Files.readString(Path.of("src/main/resources/thefourthfrequency.mixins.json"),
				StandardCharsets.UTF_8);
		String startupMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/MinecraftAlphaStartupMixin.java"),
				StandardCharsets.UTF_8);
		JsonObject enLang = JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject zhLang = JsonParser.parseString(Files.readString(
				ASSETS.resolve("lang/zh_cn.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertTrue(controller.contains("PackActivationType.NORMAL"));
		assertTrue(controller.contains("ClientPlayConnectionEvents.INIT"));
		assertTrue(controller.contains("repository.setSelected(activePackOrder)"));
		assertFalse(controller.contains("repository.setSelected(restore)"));
		assertFalse(controller.contains("originalPackOrder"));
		assertTrue(controller.contains("setTitle("));
		assertFalse(controller.contains("updateTitle("));
		assertTrue(controller.contains("AlphaLoadTimeline.versionStage(screenTicks)"));
		assertTrue(controller.contains("claimInitialCorruptionScreen"));
		assertTrue(controller.contains("shouldPrepareInitialCorruptionScreen"));
		assertTrue(controller.contains("corruptionEverPlayed"));
		assertTrue(controller.contains("applyJavaIcon"));
		assertTrue(controller.contains("screenTicks >= AlphaLoadTimeline.GLITCH_START_TICK"));
		assertTrue(controller.contains("retainFinalWindowTitle"));
		assertTrue(controller.contains("MENU_VERSION_TEXT = \"Minecraft 1.0.0\""));
		assertTrue(controller.contains("MENU_WINDOW_TITLE = MENU_VERSION_TEXT"),
				"The window bar and the in-game version stamp are one identity, not two");
		assertEquals("%s - Singleplayer World", enLang.get(
				"window.thefourthfrequency.alpha_load.singleplayer").getAsString());
		assertEquals("%s - Multiplayer", enLang.get(
				"window.thefourthfrequency.alpha_load.multiplayer").getAsString());
		assertEquals("%s - 单人世界", zhLang.get(
				"window.thefourthfrequency.alpha_load.singleplayer").getAsString());
		assertEquals("%s - 多人游戏", zhLang.get(
				"window.thefourthfrequency.alpha_load.multiplayer").getAsString());
		assertTrue(controller.contains("ConfigManager.loadClientState().alphaDowngradeComplete()"));
		assertTrue(controller.contains("ConfigManager.updateClientState"));
		assertTrue(controller.contains("client.screen instanceof TitleScreen"));
		assertTrue(controller.contains("client.getOverlay() != null"));
		assertTrue(controller.contains("ensureAlphaResourceStack(client, false)"));
		assertTrue(controller.contains("preparePersistentPackSelectionBeforeInitialReload"));
		assertTrue(controller.contains("primePersistentIdentity(client)"));
		assertTrue(controller.contains("client.options.resourcePacks.addAll"));
		assertFalse(controller.contains("client.options.save()"));
		assertTrue(controller.contains("containsOrderedAlphaBases"));
		assertTrue(controller.contains("shouldUsePersistentAlphaLoadingStyle"));
		assertTrue(controller.contains("AlphaLoadingPresentationPolicy.usePersistentLegacyPresentation"));
		assertTrue(controller.contains("recordPersistentAlphaLoadingOverlayCreated"));
		assertTrue(controller.contains("recordPersistentAlphaLoadingFirstFrame"));
		assertTrue(startupMixin.contains("Minecraft;options:Lnet/minecraft/client/Options;"));
		assertTrue(startupMixin.contains("shift = At.Shift.AFTER"));
		assertFalse(controller.contains("thefourthfrequency-alpha-state.json"));
		assertTrue(config.contains("alphaDowngradeComplete"));
		assertTrue(configManager.contains("StandardCopyOption.ATOMIC_MOVE"));
		assertFalse(configManager.contains("TerminalData"));
		assertTrue(titleMixin.contains("AlphaLoadSessionController.menuVersionText"));
		assertFalse(worldDecay.contains("setTitle("));
		assertFalse(worldDecay.contains("applyCorruptedIcon"));
		assertFalse(worldDecay.contains("setIcon("));
		assertFalse(worldDecay.contains("toggleFullScreen"));
		assertFalse(worldDecay.contains("glfwMaximizeWindow"));
		assertFalse(worldDecay.contains("WindowSnapshot"));
		assertTrue(mixinConfig.contains("MinecraftTitleRetentionMixin"));
		assertTrue(mixinConfig.contains("MinecraftAlphaStartupMixin"));
		var javaIcon = ImageIO.read(ASSETS.resolve("textures/gui/alpha_java_icon.png").toFile());
		assertEquals(32, javaIcon.getWidth());
		assertEquals(32, javaIcon.getHeight());
		assertFalse(controller.contains("Downloads"));
		assertTrue(plan.indexOf("PROGRAMMER_ART_PACK_ID") < plan.indexOf("GOLDEN_DAYS_BASE_PACK_ID"));
		assertTrue(plan.indexOf("GOLDEN_DAYS_BASE_PACK_ID") < plan.indexOf("GOLDEN_DAYS_ALPHA_PACK_ID"));
		assertTrue(packMixin.contains("selected.removeIf"));
		assertTrue(packMixin.contains("unselected.removeIf"));
		assertTrue(packMixin.contains("method = \"updateRepoSelectedList\""));
		assertTrue(packMixin.contains("repository.getSelectedPacks()"));
		assertTrue(loadingMixin.contains("ModSounds.ALPHA_CORRUPTION_WARNING"));
		assertTrue(loadingMixin.contains("ModSounds.ALPHA_CORRUPTION_COLLAPSE"));
		assertFalse(loadingMixin.contains("SimpleSoundInstance.forUI(ModSounds.TERMINAL_FAULT"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.copiedFailureLines"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.observerMessageVisible"));
		assertTrue(loadingMixin.contains("renderSignalDropouts"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.fullScreenFailureWall"));
		assertTrue(loadingMixin.contains("renderFullScreenFailureWall"));
		assertTrue(loadingMixin.contains("String wallLine = word.repeat(repetitions)"));
		assertFalse(loadingMixin.contains("frameSeed"));
		assertFalse(loadingMixin.contains("lockX"));
		assertFalse(loadingMixin.contains("lockY"));
		assertTrue(loadingMixin.contains("reason == LevelLoadingScreen.Reason.OTHER"));
		assertFalse(loadingMixin.contains("AlphaLoadTimeline.smallFailureCopies"));
		assertFalse(loadingMixin.contains("AlphaLoadTimeline.largeFailureCopies"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.failureMotionTick"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.legacyRecoveryFrame"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.initialNormalFrame"));
		assertTrue(loadingMixin.contains("holdVanillaProgressAtHalf"));
		assertTrue(loadingMixin.contains("renderFailureOverVanillaPage"));
		assertTrue(loadingMixin.contains("smoothedProgress = Math.min(smoothedProgress, 0.5F)"));
		assertTrue(loadingMixin.contains("renderStableFirstEntryBackground"));
		assertTrue(loadingMixin.contains("coverHalfProgressHandoffBeforeVanillaRender"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.blackoutFrame"));
		assertTrue(loadingMixin.contains(
				"graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), 0xFF000000)"));
		assertTrue(loadingMixin.contains(
				"PersistentAlphaLoadingStyle.drawWorldLoadingBackground(graphics)"));
		assertTrue(loadingMixin.contains("Keep the final loading frame covered"));
		assertTrue(loadingMixin.contains("thefourthfrequency$chaos"));
		assertFalse(loadingMixin.contains("columnSpacing"));
		assertFalse(loadingMixin.contains("rowSpacing"));
		assertFalse(loadingMixin.contains("driftX"));
		assertFalse(loadingMixin.contains("originX"));
		assertFalse(loadingMixin.contains("translate(targetX, targetY)"));
		assertTrue(loadingMixin.contains("graphics.pose().scale(scale, scale)"));
		assertFalse(loadingMixin.contains("+ growth"));
		assertFalse(loadingMixin.contains("barLeft - 1"));
		assertTrue(loadingMixin.contains("barY + 2"));
		assertFalse(loadingMixin.contains("barY + 3"));
		assertFalse(loadingMixin.contains("int scanY ="),
				"The first-entry loading corruption must not render the red scanline");
		assertFalse(loadingMixin.contains("0x90FF1010"),
				"The first-entry loading corruption must not restore the red scanline color");
		assertTrue(loadingMixin.contains("isModLoaded(\"thefourthfrequency-test\")"));
		assertTrue(loadingMixin.contains("alpha-loading-corruption.png"));
		assertTrue(loadingMixin.contains("legacy-loading-normal.png"));
		assertTrue(loadingMixin.contains(
				"\"screen.thefourthfrequency.legacy_loading.generating_world\""));
		assertTrue(loadingMixin.contains(
				"\"screen.thefourthfrequency.legacy_loading.generating_terrain\""));
		assertFalse(loadingMixin.contains("Identifier.withDefaultNamespace(\"textures/block/dirt.png\")"));
		assertTrue(loadingMixin.contains("shouldRenderLegacyLoadingScreen"));
		assertTrue(loadingMixin.contains("hideVanillaLoadingText"));
		assertFalse(loadingMixin.contains("0xE0080507"));

		String corruptionRenderer = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AlphaCorruptionRenderer.java"),
				StandardCharsets.UTF_8);
		String corruptionAudio = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AlphaCorruptionAudio.java"),
				StandardCharsets.UTF_8);
		// The analog-horror layers live outside the mixin so they stay readable and so the timing
		// they obey can be unit tested without a client.
		assertTrue(loadingMixin.contains("AlphaCorruptionRenderer.drawMediumLayers"));
		assertTrue(loadingMixin.contains("AlphaCorruptionRenderer.drawDeadAir"));
		assertTrue(loadingMixin.contains("AlphaCorruptionRenderer.drawRecoveryLock"));
		assertTrue(loadingMixin.contains("AlphaCorruptionRenderer.drawChromaString"));
		// The wall arrives whole rather than wiping outward: a wipe is a transition, and a
		// transition is something a piece of software plays. A failing signal cuts.
		assertFalse(loadingMixin.contains("floodWipeProgress") || loadingMixin.contains("wipeTop"),
				"the failure wall must not fade or wipe itself in");
		assertFalse(loadingMixin.contains("deadAirFlashbackFrame"),
				"Dead air must stay dead; the lost picture does not come back in single frames");
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.noise"),
				"One seed must drive every layer, or a frozen frame freezes unevenly");
		for (String layer : new String[]{"requestSignalFilter", "drawTimecode",
				"drawDeadAir", "drawRecoveryLock", "drawChromaCenteredString"}) {
			assertTrue(corruptionRenderer.contains(layer), "missing medium layer " + layer);
		}
		// The display layer is not drawn any more, it is a filter over the finished frame - so it is
		// asked for first and everything below composites *inside* it, the timecode included. What
		// used to be here instead was an ordering assertion over drawScanlines and drawVignette,
		// which were rectangles standing in for a raster and a tube. Both are shader terms now.
		assertFalse(corruptionRenderer.contains("drawScanlines")
						|| corruptionRenderer.contains("drawVignette"),
				"scanlines and the tube are the filter's job; rectangles must not come back");
		int filterAt = corruptionRenderer.indexOf("requestSignalFilter(screenTicks);");
		int timecodeAt = corruptionRenderer.indexOf("drawTimecode(graphics, screenTicks)");
		assertTrue(filterAt >= 0 && filterAt < timecodeAt,
				"the medium is asked for before anything composites inside it");
		// The corruption screen holds still: it is a wall of text a player reads for half a minute,
		// and text that will not stay in one place stops being a fault and becomes a headache. Its
		// filter family is the one with the per-frame row wobble and the tearing zeroed.
		assertTrue(corruptionRenderer.contains("signal_still_1"),
				"the loading screens must use the still filter family");
		// The colour-band overlays are retired everywhere, not just here. They are kept as source -
		// they are the reference for what the shader terms replacing them are meant to look like -
		// but nothing may call them, or the picture ends up wearing the effect twice.
		assertFalse(corruptionRenderer.contains("drawTrackingBand(graphics, screenTicks)"),
				"the tracking band is the shader's roll bar now");
		for (String retired : new String[]{"renderTornPicture(graphics", "renderMistrackedBand(graphics"}) {
			assertFalse(Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
							+ "/client_ui/AnomalyPresentationController.java"), StandardCharsets.UTF_8)
					.contains("		" + retired), "the burst must not draw " + retired + " any more");
		}
		assertFalse(Files.readString(Path.of("src/client/java/com/xm/thefourthfrequency"
						+ "/client_ui/PursuitPresentationClient.java"), StandardCharsets.UTF_8)
				.contains("		renderInterference(graphics"),
				"the pursuit's interference bands are the shader's band displacement now");
		// Phase boundaries belong to the timeline alone; the renderer only draws a given tick.
		for (String phase : new String[]{"GLITCH_START_TICK", "FAILURE_TICK", "FLOOD_START_TICK",
				"BLACKOUT_START_TICK", "LEGACY_RECOVERY_START_TICK"}) {
			assertFalse(corruptionRenderer.contains(phase),
					"AlphaCorruptionRenderer must not own timing: " + phase);
		}
		for (String key : new String[]{"screen.thefourthfrequency.alpha_loading.timecode",
				"screen.thefourthfrequency.alpha_loading.wall_intrusion",
				"window.thefourthfrequency.alpha_load.dead_air"}) {
			assertTrue(enLang.has(key), "missing English " + key);
			assertTrue(zhLang.has(key), "missing Chinese " + key);
		}
		// One line in the failure wall contradicts the wall; the observer returns on a frame that
		// has stopped; the recovered progress bar takes its own reassurance back once.
		assertTrue(loadingMixin.contains("alpha_loading.wall_intrusion"));
		assertTrue(loadingMixin.contains("INTRUSION_COLOR = 0xFFFF5C57"));
		// Woven into the wall, not laid on top of it: it stands in the flow of repeated text at
		// the wall's own scale, with nothing framing it, so it has to be found rather than read.
		assertTrue(loadingMixin.contains("int intrusionRow ="));
		assertTrue(loadingMixin.contains("int intrusionOffsetX = font.width(intrusionHead)"));
		assertTrue(loadingMixin.indexOf("x + intrusionOffsetX")
						< loadingMixin.indexOf("graphics.pose().popMatrix()"),
				"The contradicting line must be drawn inside the wall's own scale");
		assertFalse(loadingMixin.contains("0xD9060000"),
				"Nothing may frame the contradicting line; a backing box makes it a label");
		// The wipe travels on its own; lit edges read as a transition effect laid over the screen.
		assertFalse(loadingMixin.contains("0xB3E8DCD4"),
				"The flood wipe must not draw leading edge lines");
		// Text holds still: the damage is carried by chroma, scanlines and tracking, all of which
		// sit on top of a stable layout. Jittering glyphs read as an effect, not as a failure.
		assertFalse(loadingMixin.contains("tremorX"));
		assertFalse(loadingMixin.contains("tremorY"));
		assertTrue(loadingMixin.contains("int placement = thefourthfrequency$chaos(copy * 31)"),
				"Failure copies must keep the offset they were born with");
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.frozenObserverVisible"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.recoveryProgressFault"));
		assertTrue(loadingMixin.contains("AlphaLoadTimeline.initialNormalProgress"),
				"The prelude bar must lose ground before anything visibly corrupts");
		assertTrue(controller.contains("AlphaLoadTimeline.deadAirWindowTitle(screenTicks)"));
		assertTrue(controller.contains("window.thefourthfrequency.alpha_load.dead_air"));

		// Every bed the corruption starts must be stoppable from both routes out of the loading
		// screen. Multiplayer can lose the screen to a kick or timeout without onClose ever
		// running, and a bed that survives that follows the player into the world.
		assertTrue(corruptionAudio.contains("looping = true"));
		assertTrue(corruptionAudio.contains("SoundSource.MASTER"));
		assertTrue(corruptionAudio.contains("ModSounds.SIGNAL_TAPE_HISS"));
		assertTrue(corruptionAudio.contains("ModSounds.SIGNAL_STATIC"));
		assertTrue(corruptionAudio.contains("ModSounds.SIGNAL_DEAD_AIR"));
		assertTrue(corruptionAudio.contains("ModSounds.SIGNAL_CARRIER_LOST"));
		assertTrue(corruptionAudio.contains("private void fadeOut()"));
		// Released with a tail, never cut: a hard edge marks the frame the sequence ended on.
		assertTrue(loadingMixin.contains("AlphaCorruptionAudio.fadeOutAll()"));
		assertFalse(loadingMixin.contains("AlphaCorruptionAudio.stopAll()"),
				"Entering the world must fade the beds out, not cut them");
		assertTrue(controller.substring(controller.indexOf("private static void end(Minecraft"))
						.contains("AlphaCorruptionAudio.fadeOutAll()"),
				"The multiplayer disconnect path must silence the corruption beds");
		// The window title is asked for every tick but changes six times; setting it every tick
		// is twenty GLFW calls a second to write a string the window already has.
		assertTrue(controller.contains("if (!force && title.equals(appliedWindowTitle)) return;"));
		assertTrue(controller.contains("private static String titleForStage(int stage)"));

		assertTrue(overlayMixin.contains("consumeResourceReloadAnimationSuppression"));
		assertTrue(overlayMixin.contains("screen.render(graphics"));
		assertTrue(overlayMixin.contains(
				"PersistentAlphaLoadingStyle.drawWorldLoadingBackground(graphics)"));
		assertTrue(overlayMixin.contains("graphics.enableScissor(0, 0, 0, 0)"));
		assertTrue(overlayMixin.contains("graphics.disableScissor()"));
		assertTrue(overlayMixin.contains("keepUnderlyingScreen"));
		assertFalse(overlayMixin.contains("method = \"render\", at = @At(\"HEAD\"), cancellable = true"));
		assertTrue(overlayMixin.contains("deferTitleScreenUntilViewportSettles"));
		assertTrue(overlayMixin.contains("screen instanceof TitleScreen"));
		assertTrue(overlayMixin.contains("renderWithTooltipAndSubtitles"));
		assertTrue(overlayMixin.contains("registerPersistentAlphaLogo"));
		assertTrue(overlayMixin.contains("usePersistentAlphaBackground"));
		assertTrue(overlayMixin.contains("usePersistentAlphaLogo"));
		assertTrue(overlayMixin.contains("drawPersistentAlphaProgress"));
		assertTrue(overlayMixin.contains("persistentAlphaFirstFrameRecorded"));
		assertTrue(persistentLoadingStyle.contains("registerAndLoad"));
		assertTrue(persistentLoadingStyle.contains(
				"/resourcepacks/golden_days_base/assets/minecraft/textures/gui/menu_background.png"));
		assertTrue(persistentLoadingStyle.contains(
				"new DynamicTexture(id::toString, image)"));
		assertTrue(persistentLoadingStyle.contains(
				"textureManager.register(id"));
		assertTrue(persistentLoadingStyle.contains("registerWorldLoadingBackgroundOnce"));
		assertTrue(persistentLoadingStyle.contains(
				"worldLoadingBackgroundManager == textureManager"));
		assertTrue(persistentLoadingStyle.contains("Screen.renderMenuBackgroundTexture"));
		assertTrue(persistentLoadingStyle.contains(
				"/resourcepacks/golden_days_base/assets/minecraft/textures/gui/title/mojangstudios.png"));
		assertTrue(persistentLoadingStyle.contains("BACKGROUND_COLOR = 0xFF373363"));
		assertTrue(persistentLoadingStyle.contains("PROGRESS_COLOR = 0xFF8E84FF"));
		assertTrue(Files.isRegularFile(base.resolve(
				"assets/minecraft/textures/gui/title/mojangstudios.png")));
		assertTrue(mixinConfig.contains("LevelLoadingScreenCorruptionMixin"));
		assertTrue(mixinConfig.contains("LoadingOverlaySuppressionMixin"));
		assertTrue(mixinConfig.contains("PackSelectionModelHiddenPacksMixin"));
	}

	@Test
	void experienceGapUsesContinuousCollisionMovementWithoutTeleport() throws Exception {
		String serverEffects = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/AnomalyServerEffects.java"),
				StandardCharsets.UTF_8);
		int start = serverEffects.indexOf("private static final class MovementTask");
		int end = serverEffects.indexOf("public static boolean protectedPosition", start);
		String movement = serverEffects.substring(start, end);
		assertTrue(movement.contains("player.setDeltaMovement"));
		assertTrue(movement.contains("player.hurtMarked = true"));
		assertFalse(movement.contains("teleportTo"));
		assertTrue(serverEffects.contains("distance <= 24"));
	}

	@Test
	void lightDropoutUsesRestorableServerBlockChanges() throws Exception {
		String serverEffects = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/terminal/AnomalyServerEffects.java"),
				StandardCharsets.UTF_8);
		assertTrue(serverEffects.contains("case \"light_dropout\" -> lightDropout(player, durationTicks)"));
		assertTrue(serverEffects.contains("BlockStateProperties.LIT"));
		assertTrue(serverEffects.contains("LightBlock.LEVEL"));
		assertTrue(serverEffects.contains("level.getBlockState(pos).equals(extinguishedState)"),
				"Restoration must still refuse to overwrite a block the world changed meanwhile");
		assertTrue(serverEffects.contains("Block.UPDATE_CLIENTS"));
		// The dark arrives from the outside in and lifts from the inside out. Losing the ordering
		// or the pacing turns the anomaly back into the two-frame switch it used to be.
		assertTrue(serverEffects.contains("LightDropoutSequence.extinguishedBy"));
		assertTrue(serverEffects.contains("LightDropoutSequence.restoredBy"));
		assertTrue(serverEffects.contains("LightDropoutSequence.restoreIndex"));
		assertTrue(serverEffects.contains("value.position().distSqr(origin)).reversed()"));
		assertTrue(serverEffects.contains("SoundEvents.FIRE_EXTINGUISH"),
				"A light going out has to be audible where it happens");
	}

	@Test
	void experimentalWorldWarningRemainsRecognizableToClientGameTest() throws Exception {
		String warningMixin = Files.readString(Path.of(
				"src/client/java/com/xm/thefourthfrequency/mixin/WorldCreationWarningMixin.java"),
				StandardCharsets.UTF_8);
		String build = Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8);
		assertTrue(warningMixin.contains(
				"VANILLA_EXPERIMENTAL_QUESTION.equals(translationKey)"));
		assertTrue(warningMixin.contains(
				"screen.thefourthfrequency.world_creation_warning.question"));
		assertFalse(warningMixin.contains("VANILLA_EXPERIMENTAL_TITLE"),
				"The vanilla experimental title key must reach Fabric Client GameTest unchanged");
		assertFalse(warningMixin.contains("world_creation_warning.title"),
				"A custom title key prevents Fabric Client GameTest from recognizing the warning");
		assertTrue(build.contains("-Doshi.util.wmi.timeout=5000"),
				"Windows hardware-report queries must not hang client tests indefinitely");
	}

	/**
	 * The whole score, checked end to end: files on disk, {@code sounds.json}, both lang files and
	 * the credits roll all describing the same set of tracks.
	 *
	 * <p>Every one of those four lives somewhere different and none of them fails loudly on its own.
	 * A track dropped from the source folders leaves an orphaned Ogg in the jar, a "now playing" key
	 * for a file nothing can select, and - the one a player actually sees - a credit for a piece that
	 * is no longer in the game. That last one is not hypothetical: the roll went on thanking two
	 * artists whose tracks had already been replaced, because nothing here was watching.</p>
	 *
	 * <p>The credits are matched by count rather than by name. The roll is written for a reader - the
	 * titles carry their Chinese subtitles, the artists are spelled the way the artists spell them -
	 * so tying it to the lang strings would only force the roll to read like a manifest. What has to
	 * hold is that each context credits exactly as many pieces as it ships.</p>
	 */
	@Test
	void theScoreIsTheSameSetOnDiskInSoundsJsonInBothLangsAndInTheCredits() throws Exception {
		JsonObject sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject en = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();
		JsonObject zh = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/zh_cn.json"),
				StandardCharsets.UTF_8)).getAsJsonObject();

		Map<String, Integer> tracksPerEvent = new LinkedHashMap<>();
		Set<Path> referenced = new HashSet<>();
		for (String event : sounds.keySet()) {
			if (!event.startsWith("music_")) continue;
			JsonArray files = sounds.getAsJsonObject(event).getAsJsonArray("sounds");
			tracksPerEvent.put(event, files.size());
			for (var entry : files) {
				String name = entry.isJsonPrimitive() ? entry.getAsString()
						: entry.getAsJsonObject().get("name").getAsString();
				String local = name.substring(name.indexOf(':') + 1);
				Path file = ASSETS.resolve("sounds/" + local + ".ogg");
				assertTrue(Files.isRegularFile(file), event + " points at a missing file: " + name);
				assertTrue(referenced.add(file.normalize()), name + " is listed by more than one event");
				// Vanilla derives the "now playing" toast from the *file* path, not from the event, so
				// the same piece used in two contexts needs two files and two keys.
				String key = "thefourthfrequency." + local.replace('/', '.');
				assertTrue(en.has(key) && !en.get(key).getAsString().isBlank(), "missing English " + key);
				assertTrue(zh.has(key) && !zh.get(key).getAsString().isBlank(), "missing Chinese " + key);
			}
		}
		assertFalse(tracksPerEvent.isEmpty(), "parsed no score events out of sounds.json");
		for (Path file : Files.walk(ASSETS.resolve("sounds/music")).filter(Files::isRegularFile).toList()) {
			assertTrue(referenced.contains(file.normalize()),
					file + " ships in the jar but no sound event can select it");
		}
		for (String key : en.keySet()) {
			if (!key.startsWith("thefourthfrequency.music.")) continue;
			Path file = ASSETS.resolve("sounds/"
					+ key.substring("thefourthfrequency.".length()).replace('.', '/') + ".ogg");
			assertTrue(referenced.contains(file.normalize()),
					key + " names a track the score no longer ships");
		}

		assertCredits("texts/credits_zh_cn.json", "使用曲目", Map.of(
				"游戏内 BGM", List.of("music_game"),
				"主菜单 BGM", List.of("music_menu"),
				"未渲染层", List.of("music_unrendered"),
				"个人追逐", List.of("music_pursuit"),
				"末地 · 召唤之前", List.of("music_end"),
				"世界接口战斗", List.of("music_encounter_phase_1", "music_encounter_phase_2",
						"music_encounter_final"),
				"终末之诗 · 胜利", List.of("music_ending"),
				"终末之诗 · 失败", List.of("music_ending_failure")), tracksPerEvent);
		assertCredits("texts/credits_en_us.json", "Tracks Used", Map.of(
				"In-Game BGM", List.of("music_game"),
				"Main Menu BGM", List.of("music_menu"),
				"The Unrendered Layer", List.of("music_unrendered"),
				"Personal Pursuit", List.of("music_pursuit"),
				"The End, Before the Summon", List.of("music_end"),
				"World Interface Encounter", List.of("music_encounter_phase_1",
						"music_encounter_phase_2", "music_encounter_final"),
				"End Poem - Victory", List.of("music_ending"),
				"End Poem - Defeat", List.of("music_ending_failure")), tracksPerEvent);
	}

	/**
	 * Asserts that one credits roll lists every score event exactly once and credits as many pieces
	 * per heading as that heading's events ship.
	 */
	private static void assertCredits(String resource, String discipline,
			Map<String, List<String>> headings, Map<String, Integer> tracksPerEvent) throws Exception {
		JsonArray credits = JsonParser.parseString(Files.readString(ASSETS.resolve(resource),
				StandardCharsets.UTF_8)).getAsJsonArray();
		Map<String, Integer> credited = new LinkedHashMap<>();
		for (var section : credits) {
			for (var entry : section.getAsJsonObject().getAsJsonArray("disciplines")) {
				JsonObject block = entry.getAsJsonObject();
				if (!discipline.equals(block.get("discipline").getAsString())) continue;
				for (var titled : block.getAsJsonArray("titles")) {
					JsonObject title = titled.getAsJsonObject();
					credited.put(title.get("title").getAsString(),
							title.getAsJsonArray("names").size());
				}
			}
		}
		assertEquals(headings.keySet(), credited.keySet(),
				resource + " credits a different set of contexts than the score has");
		List<String> uncredited = new ArrayList<>(tracksPerEvent.keySet());
		for (var heading : headings.entrySet()) {
			int shipped = 0;
			for (String event : heading.getValue()) {
				Integer count = tracksPerEvent.get(event);
				assertTrue(count != null, resource + " credits " + heading.getKey()
						+ ", which no longer maps to a sound event");
				shipped += count;
				uncredited.remove(event);
			}
			assertEquals(shipped, credited.get(heading.getKey()).intValue(),
					resource + " credits the wrong number of tracks under " + heading.getKey());
		}
		assertTrue(uncredited.isEmpty(), resource + " never credits " + uncredited);
	}
}
