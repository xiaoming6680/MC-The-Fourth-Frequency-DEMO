package com.xm.thefourthfrequency.audio;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class SoundscapeManifestTest {
	private static final Path ASSETS = Path.of("src/main/resources/assets/thefourthfrequency");
	private static JsonObject json(Path path) throws Exception { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
	@Test void everyNonMusicFileIsMeasuredReferencedAndUnchangedSinceValidation() throws Exception {
		var sounds = json(ASSETS.resolve("sounds.json"));
		var report = json(Path.of("docs/art/audio/soundscape_manifest.json"));
		var files = report.getAsJsonObject("files");
		Set<String> referenced = new HashSet<>();
		for (String event : sounds.keySet()) {
			if (event.startsWith("music_")) continue;
			for (var sample : sounds.getAsJsonObject(event).getAsJsonArray("sounds")) {
				assertFalse(sample.isJsonObject() && sample.getAsJsonObject().has("type"), "Borrowed event: " + event);
				String path = (sample.isJsonObject() ? sample.getAsJsonObject().get("name").getAsString() : sample.getAsString()).split(":",2)[1];
				referenced.add(path);
				assertTrue(files.has(path), "Unmeasured: " + path);
			}
		}
		assertEquals(referenced, files.keySet());
		for (String path : files.keySet()) {
			var entry = files.getAsJsonObject(path);
			byte[] bytes = Files.readAllBytes(ASSETS.resolve("sounds/"+path+".ogg"));
			assertEquals(entry.get("sha256").getAsString(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), path);
			assertTrue(entry.get("truePeakDbfs").getAsDouble() < -.5, path);
			if (entry.get("loop").getAsBoolean()) assertTrue(entry.get("seam").getAsDouble() < .035, path);
			if (!path.contains("ambient_form_") && !path.startsWith("signal/")) assertEquals(1,entry.get("channels").getAsInt(),path);
		}
		assertEquals(files.size(), report.get("fileCount").getAsInt());
	}
	@Test void terminalBootHasItsOwnRecordings() throws Exception {
		var sounds=json(ASSETS.resolve("sounds.json"));
		assertNotEquals(sounds.getAsJsonObject("terminal_boot_line").get("sounds"), sounds.getAsJsonObject("terminal_click").get("sounds"));
		assertNotEquals(sounds.getAsJsonObject("terminal_boot_complete").get("sounds"), sounds.getAsJsonObject("terminal_lock").get("sounds"));
	}
	@Test void footContactsDoNotCatchUpAfterTrackingJumps() {
		assertTrue(com.xm.thefourthfrequency.client_ui.EntitySoundscape.crossed(3,3.2F,(float)Math.PI));
		assertFalse(com.xm.thefourthfrequency.client_ui.EntitySoundscape.crossed(3,3,(float)Math.PI));
		assertFalse(com.xm.thefourthfrequency.client_ui.EntitySoundscape.crossed(3,30,(float)Math.PI));
		assertFalse(com.xm.thefourthfrequency.client_ui.EntitySoundscape.crossed(3,1,(float)Math.PI));
	}
}
