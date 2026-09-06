package com.xm.thefourthfrequency.ending;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class WorldInterfaceStatePersistenceTest {
	private static final UUID ENCOUNTER_ID = named("encounter");
	private static final UUID FIRST_PLAYER = named("player-one");
	private static final UUID SECOND_PLAYER = named("player-two");
	private static final UUID FRIENDLY_DRAGON_ID = named("friendly-dragon");

	@Test
	void resolvedStateRoundTripsEveryDurableLedgerWithoutLoss() {
		WorldInterfaceState.Snapshot before = resolvedSnapshot(WorldInterfaceStage.PORTAL_OPEN, Optional.empty());
		CompoundTag encoded = encode(before);
		WorldInterfaceState.Snapshot after = decode(encoded);

		assertEquals(before, after);
		assertEquals(WorldInterfaceState.FORMAT_VERSION, encoded.getIntOr("format_version", -1));
		assertEquals(Optional.of(FRIENDLY_DRAGON_ID), after.friendlyDragonUuid());
		assertTrue(after.poemLedger().values().stream().allMatch(WorldInterfaceState.PoemLedgerEntry::acked));
		assertTrue(after.respawnLedger().values().stream().allMatch(WorldInterfaceState.RespawnLedgerEntry::restored));
	}

	@Test
	void unknownVersionAndCorruptedKnownVersionAreRejectedAsWholePayloads() {
		CompoundTag encoded = encode(resolvedSnapshot(WorldInterfaceStage.PORTAL_OPEN, Optional.empty()));
		// Below the first format and above the current one. Everything in between is readable now;
		// see readsFormatOneAsARitualWithNoWindow.
		for (int version : new int[] {0, WorldInterfaceState.FORMAT_VERSION + 1}) {
			CompoundTag unsupported = encoded.copy();
			unsupported.putInt("format_version", version);
			assertEquals("unsupported_version",
					assertThrows(IllegalArgumentException.class, () -> decode(unsupported)).getMessage());
		}

		CompoundTag corrupted = encoded.copy();
		corrupted.putInt("resolution_step", 65);
		assertEquals("resolution_step",
				assertThrows(IllegalArgumentException.class, () -> decode(corrupted)).getMessage());
	}

	/**
	 * A format-1 document has no {@code ritual_deadline_tick}, and its absence has to decode as "no
	 * window running" rather than as a zero deadline - a zero would read as a window that expired at
	 * the beginning of the world, which is a different and much louder wrong answer.
	 */
	@Test
	void readsFormatOneAsARitualWithNoWindow() {
		CompoundTag legacy = encode(resolvedSnapshot(WorldInterfaceStage.PORTAL_OPEN, Optional.empty()));
		legacy.putInt("format_version", 1);
		legacy.remove("ritual_deadline_tick");
		WorldInterfaceState.Snapshot decoded = decode(legacy);
		assertEquals(-1L, decoded.ritualDeadlineTick());
		assertFalse(decoded.ritualWindowRunning());
	}

	@Test
	void completeStateAllowsPersistedFriendlyDragonButForbidsBossIdentity() {
		WorldInterfaceState.Snapshot complete = resolvedSnapshot(WorldInterfaceStage.COMPLETE, Optional.empty());
		assertEquals(Optional.of(FRIENDLY_DRAGON_ID), decode(encode(complete)).friendlyDragonUuid());

		WorldInterfaceState.Snapshot contaminated = resolvedSnapshot(WorldInterfaceStage.COMPLETE,
				Optional.of(named("stale-boss")));
		assertEquals("finished_boss_identity",
				assertThrows(IllegalArgumentException.class, () -> encode(contaminated)).getMessage());
	}

	@Test
	void completionGuardRequiresPoemAckAndRestoredRespawnForEveryFrozenPlayer() throws Exception {
		String source = Files.readString(projectRoot().resolve(
				"src/main/java/com/xm/thefourthfrequency/ending/EndBossEncounterService.java"),
				StandardCharsets.UTF_8);
		int start = source.indexOf("private static void completeIfAllPoemsAcknowledged");
		int end = source.indexOf("private static void ensureExitOpen", start);
		assertTrue(start >= 0 && end > start, "The authoritative completion guard must remain identifiable");
		String guard = source.substring(start, end).replaceAll("\\s+", "");
		assertTrue(guard.contains(".map(WorldInterfaceState.PoemLedgerEntry::acked).orElse(false)"
				+ "&&Optional.ofNullable(snapshot.respawnLedger().get(id))"
				+ ".map(WorldInterfaceState.RespawnLedgerEntry::restored).orElse(false)"),
				"Completion must conjunctively require both the poem ACK and restored respawn ledger");
		assertTrue(guard.indexOf("PoemLedgerEntry::acked") < guard.indexOf("WorldInterfaceState.transition"));
		assertTrue(guard.indexOf("RespawnLedgerEntry::restored") < guard.indexOf("WorldInterfaceState.transition"));
	}

	/**
	 * The window may only exist where it means something.
	 *
	 * <p>Both halves matter. A deadline surviving past the commit would leave a restart mid-summon
	 * counting down against a fight that has already started, and a deadline on any later stage is a
	 * clock nothing reads - which is exactly the kind of field that quietly comes back to life two
	 * refactors later.</p>
	 */
	@Test
	void theRitualWindowOnlyExistsWhileTheAltarIsStillCollecting() {
		WorldInterfaceState.Snapshot committed = withDeadline(
				resolvedSnapshot(WorldInterfaceStage.PORTAL_OPEN, Optional.empty()), 9_000L);
		assertEquals("ritual_window_stage",
				assertThrows(IllegalArgumentException.class, () -> encode(committed)).getMessage());

		CompoundTag negative = encode(resolvedSnapshot(WorldInterfaceStage.PORTAL_OPEN, Optional.empty()));
		negative.putLong("ritual_deadline_tick", -2L);
		assertEquals("ritual_deadline_tick",
				assertThrows(IllegalArgumentException.class, () -> decode(negative)).getMessage());
	}

	private static WorldInterfaceState.Snapshot withDeadline(WorldInterfaceState.Snapshot source,
			long deadline) {
		return new WorldInterfaceState.Snapshot(source.present(), source.valid(), source.encounterId(),
				source.revision(), source.stage(), source.outcome(), source.arenaVersion(),
				source.arenaDimension(), source.arenaCenter(), source.altarCenter(), source.safeSpawn(),
				source.arenaBuildCursor(), source.gates(), source.anchors(), source.frozenRoster(),
				source.terminalTransactions(), source.sacrificeCommitted(), source.bossUuid(),
				source.maxVirtualHealth(), source.virtualHealth(), source.activeTicks(),
				source.runningSinceGameTime(), source.deterministicSeed(), source.currentAttack(),
				source.stageStartedActiveTick(), source.actionSequence(), source.lastActionWireId(),
				source.nextActionActiveTick(), source.lastForcedEvictionTick(), source.controlCooldowns(),
				source.recoveryGraceTicks(), source.terrainEditsUsed(), source.respawnLedger(),
				source.poemLedger(), source.recoveryLedger(), source.friendlyDragonUuid(),
				source.exitPosition(), source.exitOpen(), source.resolutionStep(), source.resolutionTick(),
				deadline);
	}

	private static WorldInterfaceState.Snapshot resolvedSnapshot(WorldInterfaceStage stage,
			Optional<UUID> bossUuid) {
		Set<UUID> roster = new LinkedHashSet<>(List.of(FIRST_PLAYER, SECOND_PLAYER));
		Map<UUID, WorldInterfaceState.TerminalTransaction> transactions = new LinkedHashMap<>();
		transactions.put(FIRST_PLAYER, transaction(FIRST_PLAYER, "terminal-one"));
		transactions.put(SECOND_PLAYER, transaction(SECOND_PLAYER, "terminal-two"));
		Map<UUID, WorldInterfaceState.RespawnLedgerEntry> respawns = Map.of(
				FIRST_PLAYER, new WorldInterfaceState.RespawnLedgerEntry(FIRST_PLAYER, true,
						"minecraft:overworld", new BlockPos(12, 72, -8), 90.0F, 0.0F, false, true),
				SECOND_PLAYER, new WorldInterfaceState.RespawnLedgerEntry(SECOND_PLAYER, false,
						"", BlockPos.ZERO, 0.0F, 0.0F, false, true));
		Map<UUID, WorldInterfaceState.PoemLedgerEntry> poems = Map.of(
				FIRST_PLAYER, new WorldInterfaceState.PoemLedgerEntry(FIRST_PLAYER, 2_147_483_648L,
						true, true, true),
				SECOND_PLAYER, new WorldInterfaceState.PoemLedgerEntry(SECOND_PLAYER, 2_147_483_649L,
						true, true, true));
		CompoundTag recoveryPayload = new CompoundTag();
		recoveryPayload.putString("weapon_kind", "sword");
		WorldInterfaceState.RecoveryEntry recovery = new WorldInterfaceState.RecoveryEntry(named("recovery"),
				FIRST_PLAYER, "weapon", recoveryPayload, true);

		return new WorldInterfaceState.Snapshot(true, true, Optional.of(ENCOUNTER_ID), 42L,
				stage, WorldInterfaceState.Outcome.SUCCESS, 1, "minecraft:the_end",
				BlockPos.ZERO, new BlockPos(0, 65, 0), new BlockPos(0, 65, 8), 20,
				gates(), anchors(), roster, transactions, true, bossUuid,
				// Validation refuses any committed state whose pool disagrees with its roster, so this
				// is asked for rather than written out: the fixture is about persistence, not balance.
				WorldInterfacePolicy.maxHealth(roster.size()), 0.0D, 4_800L, -1L, 0x574F524C44494E54L,
				Optional.empty(), 4_700L, 9L, 9, 5_000L, 4_000L,
				Map.of(FIRST_PLAYER, 5_200L), 0, 321, respawns, poems, List.of(recovery),
				Optional.of(FRIENDLY_DRAGON_ID), new BlockPos(0, 65, 0), true, 3, 9_000L, -1L);
	}

	private static WorldInterfaceState.TerminalTransaction transaction(UUID playerId, String terminalId) {
		CompoundTag terminal = new CompoundTag();
		terminal.putString("owner_id", playerId.toString());
		terminal.putString("terminal_id", terminalId);
		terminal.putInt("copy_generation", 0);
		return new WorldInterfaceState.TerminalTransaction(playerId, terminalId, 0,
				WorldInterfaceState.TerminalTransactionState.COMMITTED, 100L, terminal);
	}

	private static List<WorldInterfaceState.Gate> gates() {
		List<WorldInterfaceState.Gate> result = new ArrayList<>();
		for (int index = 0; index < WorldInterfaceState.GATE_COUNT; index++) {
			result.add(new WorldInterfaceState.Gate(index, new BlockPos(index * 2, 70, 100 + index),
					WorldInterfaceGatewayState.GOLD));
		}
		return List.copyOf(result);
	}

	private static List<WorldInterfaceState.Anchor> anchors() {
		List<WorldInterfaceState.Anchor> result = new ArrayList<>();
		for (int index = 0; index < WorldInterfaceState.ANCHOR_COUNT; index++) {
			result.add(new WorldInterfaceState.Anchor(index, new BlockPos(index * 3, 80 + index, -100),
					Optional.of(named("anchor-" + index)), index == 0));
		}
		return List.copyOf(result);
	}

	private static CompoundTag encode(WorldInterfaceState.Snapshot snapshot) {
		return (CompoundTag) invoke("encode", new Class<?>[]{WorldInterfaceState.Snapshot.class}, snapshot);
	}

	private static WorldInterfaceState.Snapshot decode(CompoundTag tag) {
		return (WorldInterfaceState.Snapshot) invoke("decode", new Class<?>[]{CompoundTag.class}, tag);
	}

	private static Object invoke(String name, Class<?>[] parameterTypes, Object argument) {
		try {
			Method method = WorldInterfaceState.class.getDeclaredMethod(name, parameterTypes);
			method.setAccessible(true);
			return method.invoke(null, argument);
		} catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
			throw new AssertionError("World-interface persistence invocation failed", exception.getCause());
		} catch (ReflectiveOperationException exception) {
			throw new AssertionError("World-interface persistence method is unavailable", exception);
		}
	}

	private static UUID named(String value) {
		return UUID.nameUUIDFromBytes(("world-interface-test:" + value).getBytes(StandardCharsets.UTF_8));
	}

	private static Path projectRoot() {
		return Path.of(System.getProperty("thefourthfrequency.projectDir", System.getProperty("user.dir")))
				.toAbsolutePath().normalize();
	}
}
