package com.xm.thefourthfrequency.narrative;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NarrativeFileCatalog {
	private static final String RESOURCE = "/data/thefourthfrequency/archive/terminal_files.json";
	private static final List<Definition> DEFINITIONS = load();
	private static final Map<String, Definition> BY_ID = index(DEFINITIONS);

	private NarrativeFileCatalog() { }

	public static List<Definition> definitions() { return DEFINITIONS; }
	public static Definition require(String id) {
		Definition definition = BY_ID.get(id);
		if (definition == null) throw new IllegalArgumentException("Unknown terminal file " + id);
		return definition;
	}

	private static List<Definition> load() {
		try (InputStream stream = NarrativeFileCatalog.class.getResourceAsStream(RESOURCE)) {
			if (stream == null) throw new IllegalStateException("Missing terminal file catalog " + RESOURCE);
			List<Definition> values = new Gson().fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8),
					new TypeToken<List<Definition>>() { }.getType());
			// Twelve: eight narrative entries plus the four manual pages at the end. The recovered
			// predecessor fragment is a catalogue entry like any other - the terminal serves it, owns
			// whether it has been found and whether it has been read - but alone among them its body is
			// composed on the client, because what it says is a fact about the player's own previous
			// playthrough rather than about this world.
			if (values == null || values.size() != 12) throw new IllegalStateException("Expected 12 terminal files");
			return List.copyOf(values);
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to load terminal file catalog", exception);
		}
	}

	private static Map<String, Definition> index(List<Definition> definitions) {
		Map<String, Definition> result = new LinkedHashMap<>();
		for (int index = 0; index < definitions.size(); index++) {
			Definition definition = definitions.get(index);
			if (definition == null || definition.id() == null || !definition.id().matches("[a-z0-9_]{3,48}"))
				throw new IllegalStateException("Invalid terminal file at index " + index);
			if (definition.titleKey() == null || !definition.titleKey().startsWith("terminal.thefourthfrequency.file."))
				throw new IllegalStateException("Invalid terminal file title " + definition.id());
			if (result.put(definition.id(), definition) != null)
				throw new IllegalStateException("Duplicate terminal file " + definition.id());
		}
		return Map.copyOf(result);
	}

	public record Definition(String id, String titleKey, List<String> lineKeys) {
		public Definition {
			lineKeys = lineKeys == null ? List.of() : List.copyOf(lineKeys);
		}
	}
}
