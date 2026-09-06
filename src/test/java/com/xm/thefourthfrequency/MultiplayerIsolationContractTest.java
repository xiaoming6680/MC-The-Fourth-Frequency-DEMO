package com.xm.thefourthfrequency;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boundaries that only ever break when more than one person is playing.
 *
 * <p>Every rule here was found by reading the shared-world data flow rather than by a test failing,
 * and each one had the same shape: a decision that is correct for one player, applied to everyone.
 * They are asserted from source because the alternative is a running server with eight clients on
 * it - the {@code runGameTest} and client suites cover the ones that can be staged, and this covers
 * the rest so a refactor cannot quietly put them back.
 */
class MultiplayerIsolationContractTest {
	/**
	 * No ambient system may ask about the mirror alone.
	 *
	 * <p>Two unrelated features now take a player out of shared reality - a pursuit copies them into
	 * a private mirror, the unrendered layer drops them somewhere that is not a copy of anything -
	 * and every system that decorates or measures the world has to stand down for both. Each of the
	 * call sites below was written against the mirror, correctly, before the layer existed. Adding
	 * the second private dimension made all of them silently half-right, and not one would have
	 * failed a test or logged a line: HIM would have been placed in the layer, survival milestones
	 * would have fired for walking around in it, pattern learning would have counted its corridors
	 * as somewhere the player chose to go, and the anomaly scheduler would have kept scheduling.
	 *
	 * <p>{@code PrivateDimensions.isPrivate} is the whole question in one place. This test is what
	 * stops the next person from reaching for the more obvious {@code PursuitDimensions.isMirror}
	 * and getting a green build for it.
	 */
	@Test
	void ambientSystemsStandDownForEveryPrivateDimensionNotJustTheMirror() throws IOException {
		List<String> ambientSystems = List.of(
				"terminal/AmbientAnomalyService", "terminal/TerminalRelayService",
				"world/FragmentInvestigationService", "world/GuidanceArrivalService",
				"world/HimService", "world/WatcherService", "world/PlayerPatternService",
				"world/ResourceGuidanceService",
				"world/StructureNavigationService", "world/SurvivalProgressService",
				"world/TerminalActivityTracker", "world/TerminalLifecycleService");
		for (String service : ambientSystems) {
			String source = read("src/main/java/com/xm/thefourthfrequency/" + service + ".java");
			assertFalse(source.contains("PursuitDimensions.isMirror("),
					service + " asks about the pursuit mirror alone; it must ask "
							+ "PrivateDimensions.isPrivate so the unrendered layer counts too");
			assertTrue(source.contains("PrivateDimensions.isPrivate("),
					service + " must stand down inside private dimensions");
		}
		String shared = read("src/main/java/com/xm/thefourthfrequency/world/PrivateDimensions.java");
		assertTrue(shared.contains("PursuitDimensions.isMirror")
						&& shared.contains("UnrenderedDimensions.isUnrendered"),
				"The shared predicate must cover both private dimensions");
	}

	/**
	 * The layer is carried by its own ambience, and by nothing else the client would otherwise add.
	 *
	 * <p>Two systems run permanently and key off the player's own run rather than off where they are
	 * standing, so both followed them in. World decay paints corruption onto the blocks around them,
	 * which on a stage-five save eats the layer's walls - and the layer's whole effect is that every
	 * room looks exactly like every other one. The signal beds belong to the transmission, which is a
	 * thing in the player's world; hearing it from down there places the layer inside that world too.
	 *
	 * <p>Asserted from source because neither is reachable from a unit test: one resolves inside a
	 * texture-manager mixin on the render thread, the other inside a sound controller that needs a
	 * live {@code Minecraft}.
	 */
	@Test
	void theUnrenderedLayerIsNotDecoratedByTheSystemsThatFollowThePlayer() throws IOException {
		String decay = read("src/client/java/com/xm/thefourthfrequency/client_ui/WorldDecayClient.java");
		assertTrue(decay.contains("if (UnrenderedLayerClient.inLayer()) return 0;"),
				"World decay must read as stage zero inside the layer, or stage-five corruption "
						+ "eats the one surface whose sameness the place runs on");
		String beds = read("src/client/java/com/xm/thefourthfrequency/client_ui/SignalBedController.java");
		assertTrue(beds.contains("if (UnrenderedLayerClient.inLayer()) return EnumSet.noneOf(Layer.class);"),
				"The signal beds must not play in the layer; its own ambience is the only bed");
		// The layer used to be scored by silence and now has a track of its own, which is a change of
		// answer rather than of principle: what must not happen is the ordinary game score following
		// the player down there. Asserted as "the layer decides its own music", which is true of both.
		String music = read("src/client/java/com/xm/thefourthfrequency/client_ui/MusicDirector.java");
		assertTrue(music.contains("UnrenderedLayerClient.hunted() ? UNRENDERED : null"),
				"The layer must select its own score rather than inheriting the overworld's, and it "
						+ "must wait for the entity - the score is the thing's, not the room's");
		assertTrue(music.contains("if (UnrenderedLayerClient.inLayer()) fadeTarget *= UNRENDERED_MUSIC_TRIM;"),
				"The layer's track sits under its own ambience; at full level it buries the heartbeat "
						+ "and the footsteps, which are the two sounds down there that carry information");
	}

	/**
	 * The debug HUD can see the entity through the layer's walls.
	 *
	 * <p>Asked for explicitly, and worth pinning because the mechanism is indirect: the outline comes
	 * from {@code isCurrentlyGlowing}, which a mixin overrides for this mod's own entities, and the
	 * list it checks is named types rather than a namespace - so a new entity is a deliberate
	 * decision and an entity dropped from that list fails silently, with the only symptom being a
	 * developer unable to find the thing they are debugging.
	 */
	@Test
	void theDebugHudOutlinesTheLayerEntityThroughWalls() throws IOException {
		String highlight = read(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DebugEntityHighlight.java");
		assertTrue(highlight.contains("entity instanceof BacteriaEntity"),
				"The debug HUD must outline the unrendered layer's entity");
		String mixin = read(
				"src/client/java/com/xm/thefourthfrequency/mixin/EntityDebugHighlightMixin.java");
		assertTrue(mixin.contains("isCurrentlyGlowing"),
				"The outline rides on the glowing flag, which is what makes it visible through blocks");
		assertTrue(mixin.contains("DebugEntityHighlight.shouldHighlight"),
				"The mixin must ask the shared predicate rather than keeping its own list");
	}

	private static String read(String path) throws IOException {
		return Files.readString(Path.of(path), StandardCharsets.UTF_8);
	}

	/**
	 * The drop refusal has to sit on the method drops actually reach.
	 *
	 * <p>{@code Player} declares only {@code drop(ItemStack, boolean)}; the implementation is
	 * {@code LivingEntity#drop(ItemStack, boolean, boolean)}, and {@code Inventory#dropAll} plus
	 * {@code EntityEquipment#dropAll} - which together are death - call it directly. An injection on
	 * the two-argument overload therefore covers the drop key and nothing else, so dying with
	 * {@code keepInventory} off scattered a bound terminal anyone could pick up and a custody
	 * placeholder that {@code ConfiscationService.clearPlaceholder} could then never find, because it
	 * hands items back by scanning the owner's own inventory.
	 */
	@Test
	void undroppableStacksAreRefusedOnTheOverloadDeathActuallyCalls() throws IOException {
		String mixin = read("src/main/java/com/xm/thefourthfrequency/mixin/LivingEntityDropMixin.java");
		assertTrue(mixin.contains("@Mixin(LivingEntity.class)"),
				"The refusal must target the class that declares the implementation");
		assertTrue(mixin.contains(
				"method = \"drop(Lnet/minecraft/world/item/ItemStack;ZZ)"
						+ "Lnet/minecraft/world/entity/item/ItemEntity;\""),
				"The refusal must name the three-argument descriptor; the two-argument one misses "
						+ "Inventory#dropAll and EntityEquipment#dropAll, which is every death drop");
		assertTrue(mixin.contains("TerminalData.isBound(stack)")
						&& mixin.contains("ConfiscationService.isPlaceholder(stack)"),
				"Both custody markers must still be refused");
		assertTrue(mixin.contains("serverPlayer.isAlive()"),
				"A dead player did not choose the drop, so the refusal notice must not reach the "
						+ "death screen - the cancel still has to happen");
		assertFalse(Files.exists(Path.of(
				"src/main/java/com/xm/thefourthfrequency/mixin/PlayerDropMixin.java")),
				"The old Player-targeted mixin must be gone rather than left beside its replacement");
		assertTrue(read("src/main/resources/thefourthfrequency.mixins.json")
						.contains("LivingEntityDropMixin"),
				"defaultRequire is 1, so an unlisted mixin is a guard that silently never applies");
	}

	/**
	 * Not everyone online has a terminal record, and the writers on lifecycle paths have to know it.
	 *
	 * <p>{@code ZeroStationService.issueTerminalIfNeeded} deliberately writes no grant ledger entry
	 * once the finale has concluded, so a player joining a finished server is someone this mod holds
	 * no state for - and {@code FrequencyWorldData.updateTerminalRecord} throws rather than inventing
	 * one. Reached from {@code ServerPlayerEvents.LEAVE}, which Fabric fires from the head of
	 * {@code PlayerList#remove}, that throw skips the rest of the removal: the player is never saved
	 * and never leaves the online list.
	 */
	@Test
	void perPlayerWritersOnLifecyclePathsCheckTheRecordExists() throws IOException {
		String anomalyRuntime = read(
				"src/main/java/com/xm/thefourthfrequency/terminal/AnomalyRuntimeService.java");
		assertTrue(anomalyRuntime.contains("ServerPlayerEvents.LEAVE")
						&& anomalyRuntime.contains("ServerPlayerEvents.JOIN"),
				"This service is only at risk because it writes from the lifecycle events");
		// Both writers take the same shape: resolve the data, ask, then write.
		assertTrue(anomalyRuntime.contains("if (data.terminalRecord(player.getUUID()).isEmpty()) return;"),
				"interrupt() and clearProjection() must both refuse to write a record that does not "
						+ "exist instead of throwing out of a lifecycle event");
		assertTrue(read("src/main/java/com/xm/thefourthfrequency/world/ZeroStationService.java")
						.contains("FinaleRuntimePolicy.concluded(data)"),
				"The state this guards against is a real, supported one: joining a finished server");
	}

	/**
	 * The client half only takes over the player's own settings inside a world that earns it.
	 *
	 * <p>The render distance lives in {@code options.txt} and the resource pack selection is global,
	 * so an unscoped lock followed the player onto the title screen, into unrelated single-player
	 * saves and onto other people's servers - with the slider greyed out and no world anywhere to
	 * explain why.
	 */
	@Test
	void clientTakeoverIsScopedToWorldsThisModRunsIn() throws IOException {
		String presence = read(
				"src/client/java/com/xm/thefourthfrequency/client_ui/ModWorldPresence.java");
		assertTrue(presence.contains("ClientPlayNetworking.canSend(TerminalOpenPayload.TYPE)"),
				"The signal must be one of this mod's own channels, not a new protocol surface");
		assertTrue(presence.contains("hasSingleplayerServer()"),
				"canSend cannot answer before the player exists, and the integrated server is the "
						+ "case that covers that window exactly rather than approximately");

		String viewDistance = read(
				"src/client/java/com/xm/thefourthfrequency/client_ui/DimensionViewDistanceController.java");
		assertTrue(viewDistance.contains("!unlocked && ModWorldPresence.currentWorldRunsThisMod()"),
				"Scoping belongs in isLocked(), so the per-tick write, the option's set() rejection, "
						+ "the greyed-out slider and the fog range all inherit it from one place");
		assertTrue(viewDistance.contains("releaseOverride(client)"),
				"Leaving the world must hand the option back; otherwise the last locked value is "
						+ "simply saved to disk on the next quit");
		assertTrue(viewDistance.contains("if (playerChoice == null) playerChoice ="),
				"The player's own value is captured once - re-reading it on a dimension change would "
						+ "record this mod's own lock as their preference");

		String alpha = read(
				"src/client/java/com/xm/thefourthfrequency/client_ui/AlphaLoadSessionController.java");
		assertTrue(alpha.contains("if (client.hasSingleplayerServer()) begin(client);"),
				"INIT is too early for the channel test, so it covers the integrated server only");
		assertTrue(alpha.contains("if (ModWorldPresence.currentWorldRunsThisMod()) begin(client);"),
				"A remote server is decided at JOIN, which still lands while the world is coming up");
	}

	/**
	 * Three decisions that read as facts about the world but are facts about a person.
	 */
	@Test
	void encounterAndAmbientPressureAreAskedAboutAPlayer() throws IOException {
		String encounter = read(
				"src/main/java/com/xm/thefourthfrequency/ending/EndBossEncounterService.java");
		assertTrue(encounter.contains(
				"for (ServerPlayer player : encounterRecipients(server, WorldInterfaceState.snapshot(server)))"),
				"Encounter narration must reach the roster and the End, not everyone online: a "
						+ "player mining in the Overworld was receiving every anchor and every phase");

		String decay = read("src/main/java/com/xm/thefourthfrequency/world/WorldDecayService.java");
		assertTrue(decay.contains("FinaleRuntimePolicy.insideRunningEncounter(data, player)"),
				"Decay is documented as personal, so its encounter term must be too - a summon in "
						+ "the End must not jump every client on the server to full corruption");
		assertFalse(decay.contains("FinaleRuntimePolicy.pressureActive(data)"),
				"The world-level pressure read is what made decay shared again");

		String policy = read("src/main/java/com/xm/thefourthfrequency/ending/FinaleRuntimePolicy.java");
		assertTrue(policy.contains("public static boolean insideRunningEncounter("),
				"One definition of 'inside the fight', shared by both callers that need it");
	}

	/**
	 * The two ambient services that reach into shared space, and who they are allowed to reach.
	 */
	@Test
	void ambientSightingsRespectTheMirrorTheFinaleAndTheBystander() throws IOException {
		String him = read("src/main/java/com/xm/thefourthfrequency/world/HimService.java");
		assertTrue(him.contains("if (PrivateDimensions.isPrivate(level)) return false;"),
				"A figure nothing here placed is a private dimension leaking, and the debug entry "
						+ "point is not an exception to that. Widened from the pursuit mirror to every "
						+ "private dimension when the unrendered layer was added - HIM standing in a "
						+ "corridor of the layer would be the one thing down there that means "
						+ "something, in a place whose whole effect is that nothing does");
		assertTrue(him.contains("data.terminalRecord(player.getUUID()).isEmpty()")
						&& him.contains("FinaleRuntimePolicy.ambientPressureAllowed(data, player)"),
				"A sighting for someone the story holds no record of, or after the story it belongs "
						+ "to has been answered, is a figure with nothing behind it");

		String ambient = read(
				"src/main/java/com/xm/thefourthfrequency/terminal/AmbientAnomalyService.java");
		assertTrue(ambient.contains("if (other.distanceToSqr(player) <= SOLITUDE_RADIUS * SOLITUDE_RADIUS) return false;"),
				"Any nearby player is company: their doors are still their doors, and an unbound "
						+ "witness is the one person nothing in the mod can explain the lights to");
		assertFalse(ambient.contains("TerminalData.BOUND, false)).orElse(false)) return false;"),
				"Solitude must not be decided by whether the bystander is in the story");
	}
}
