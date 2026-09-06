package com.xm.thefourthfrequency;

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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A service that keeps mutable static state must say when that state ends.
 *
 * <p>This is a sweep rather than a list of cases, because the same defect has now been found four
 * separate times in four unrelated packages and every instance looked correct in isolation. A static
 * map keyed by player or by level is the obvious way to hold per-player runtime state, nothing about
 * writing one suggests a lifetime, and the symptom never points back at it:
 *
 * <ul>
 *   <li>{@code FriendlyDragonService} held live {@code EnderDragon} references, so finishing the
 *       good ending and returning to the title screen kept that world's whole server reachable.</li>
 *   <li>{@code EndBossArenaService} kept anchors in a {@code WeakHashMap} whose values reference
 *       their own keys, which is a weak map that never collects.</li>
 *   <li>{@code WatcherService} stored {@code getGameTime()} deadlines that outlived their save, so
 *       a second world opened in the same process could not reach them and the figure it schedules
 *       simply never appeared.</li>
 *   <li>{@code WorldInterfaceRitualService} stored {@code getTickCount()} stamps, and that counter
 *       restarts with each server, so the altar stopped explaining itself in the next world.</li>
 * </ul>
 *
 * <p>Two of those are memory, and two are features quietly not happening - which is why a leak
 * checker would not have caught them and why this test asks the structural question instead. It is
 * deliberately crude: it does not know whether a hook is correct, only whether the file admits that
 * its state has an end. Everything it cannot judge is exempted <em>by name, with a reason</em>, so
 * adding a new exemption is a decision somebody writes down rather than a silence.
 */
final class StaticStateCleanupContractTest {
	private static final Path ROOT = Path.of("src/main/java/com/xm/thefourthfrequency");

	/**
	 * A mutable static collection: one that is constructed rather than an immutable catalogue.
	 *
	 * <p>{@code Map.of}, {@code Set.copyOf} and a {@code stream().toList()} are constants and have
	 * no lifetime to manage; anything built with {@code new} - including one wrapped in
	 * {@code Collections.synchronizedMap} - is state.
	 */
	private static final Pattern DECLARATION = Pattern.compile(
			"private static final (?:Map|Set|List)<[^;]*?>\\s+([A-Z_][A-Z0-9_]*)\\s*=\\s*([^;]*);",
			Pattern.DOTALL);

	private static final List<String> CLEANUP_MARKERS = List.of(
			"ServerPlayerEvents.LEAVE", "ServerLifecycleEvents.SERVER_STOPPED",
			"ServerLifecycleEvents.SERVER_STOPPING", "ServerPlayConnectionEvents.DISCONNECT");

	/** File name to the reason its state is allowed to outlive every hook this test knows about. */
	private static final Map<String, String> EXEMPT = Map.of(
			"MusicRotationPolicy.java",
			"Client-side and deliberately session-scoped: the menu score keeps its rotation across "
					+ "worlds, which is the whole point of it being keyed by event rather than by save",
			"AnomalyServerEffects.java",
			"Every entry is held by an EffectLease, and AnomalyRuntimeService owns the leave, respawn "
					+ "and shutdown paths that run them",
			"UnrenderedAnchorManager.java",
			"Leases are released and cleared by UnrenderedSessionService, which owns the session they "
					+ "belong to",
			"PlayerMotionTracker.java",
			"AmbientAnomalyService forgets a player's samples on disconnect",
			"TerminalSignalService.java",
			"Self-pruning: the tick drops every id the player list no longer answers for",
			"EmptySegmentService.java",
			"Self-pruning: an event whose owner is offline is finished and removed on the next tick");

	@Test
	void everyServiceHoldingMutableStaticStateRegistersACleanupHook() throws IOException {
		List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ROOT)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
				String name = file.getFileName().toString();
				String source = Files.readString(file, StandardCharsets.UTF_8);
				List<String> fields = mutableStaticCollections(source);
				if (fields.isEmpty()) continue;
				if (EXEMPT.containsKey(name)) continue;
				if (CLEANUP_MARKERS.stream().anyMatch(source::contains)) continue;
				offenders.add(name + " holds " + String.join(", ", fields)
						+ " with no leave/disconnect/shutdown hook");
			}
		}
		assertTrue(offenders.isEmpty(),
				"Mutable static state must be cleared on a lifecycle boundary, or exempted by name "
						+ "in StaticStateCleanupContractTest with the reason it is safe:\n  "
						+ String.join("\n  ", offenders));
	}

	/**
	 * The exemptions have to stay honest.
	 *
	 * <p>An exemption whose file no longer has any such state, or which has since grown a real hook,
	 * is a licence nobody is using any more - and the next file to need one would be granted it by a
	 * list nobody has read in a year.
	 */
	@Test
	void everyExemptionStillDescribesAFileThatNeedsOne() throws IOException {
		List<String> stale = new ArrayList<>();
		try (Stream<Path> files = Files.walk(ROOT)) {
			Map<String, Path> byName = new LinkedHashMap<>();
			files.filter(path -> path.toString().endsWith(".java"))
					.forEach(path -> byName.put(path.getFileName().toString(), path));
			for (String name : EXEMPT.keySet()) {
				Path file = byName.get(name);
				if (file == null) {
					stale.add(name + " no longer exists");
					continue;
				}
				String source = Files.readString(file, StandardCharsets.UTF_8);
				if (mutableStaticCollections(source).isEmpty()) {
					stale.add(name + " no longer holds mutable static state");
				} else if (CLEANUP_MARKERS.stream().anyMatch(source::contains)) {
					stale.add(name + " now registers a cleanup hook of its own");
				}
			}
		}
		assertTrue(stale.isEmpty(), "Remove these exemptions:\n  " + String.join("\n  ", stale));
	}

	/**
	 * Immutable factories, tested against the <em>start</em> of the initializer.
	 *
	 * <p>Looking anywhere in it is not enough, and the first version of this test proved it by
	 * failing on {@code TASKS = List.of(new TaskDefinition(...))}: an immutable catalogue whose
	 * entries happen to be constructed. What decides the question is what builds the collection, not
	 * what goes into it.
	 */
	private static final List<String> IMMUTABLE_FACTORIES = List.of(
			"Map.of", "Set.of", "List.of", "Map.copyOf", "Set.copyOf", "List.copyOf",
			"Collections.unmodifiable", "Collections.empty");

	private static List<String> mutableStaticCollections(String source) {
		List<String> fields = new ArrayList<>();
		Matcher matcher = DECLARATION.matcher(source);
		while (matcher.find()) {
			String initializer = matcher.group(2).trim();
			if (IMMUTABLE_FACTORIES.stream().anyMatch(initializer::startsWith)) continue;
			if (initializer.contains("new ") || initializer.contains("newKeySet(")
					|| initializer.contains("newSetFromMap(")) {
				fields.add(matcher.group(1));
			}
		}
		return fields;
	}
}
