package com.xm.thefourthfrequency.bootstrap;

import com.xm.thefourthfrequency.config.ConfigManager;
import com.xm.thefourthfrequency.config.ModConfig;
import com.xm.thefourthfrequency.audio.ModSounds;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.ModBlocks;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.WorldInterfaceBlockEntities;
import com.xm.thefourthfrequency.correction.EmptySegmentService;
import com.xm.thefourthfrequency.ending.EndBossEncounterService;
import com.xm.thefourthfrequency.ending.EndBossArenaService;
import com.xm.thefourthfrequency.ending.FriendlyDragonService;
import com.xm.thefourthfrequency.ending.WorldInterfaceAttackService;
import com.xm.thefourthfrequency.ending.WorldInterfaceBlastService;
import com.xm.thefourthfrequency.ending.WorldInterfaceDropBeaconService;
import com.xm.thefourthfrequency.ending.WorldInterfaceRitualService;
import com.xm.thefourthfrequency.world.EndPopulationService;
import com.xm.thefourthfrequency.world.PlayerPatternService;
import com.xm.thefourthfrequency.world.ZeroStationService;
import com.xm.thefourthfrequency.world.TerminalActivityTracker;
import com.xm.thefourthfrequency.world.TerminalLifecycleService;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.GuidanceArrivalService;
import com.xm.thefourthfrequency.world.StructureNavigationService;
import com.xm.thefourthfrequency.world.TerminalCommands;
import com.xm.thefourthfrequency.world.StoryProgressService;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import com.xm.thefourthfrequency.world.FragmentInvestigationService;
import com.xm.thefourthfrequency.world.HimService;
import com.xm.thefourthfrequency.world.WatcherService;
import com.xm.thefourthfrequency.world.WorldDecayService;
import com.xm.thefourthfrequency.world.PortalContinuityService;
import com.xm.thefourthfrequency.networking.M5Networking;
import com.xm.thefourthfrequency.networking.M6Networking;
import com.xm.thefourthfrequency.networking.M8Networking;
import com.xm.thefourthfrequency.networking.TerminalNetworking;
import com.xm.thefourthfrequency.networking.DebugNetworking;
import com.xm.thefourthfrequency.networking.WorldInterfaceNetworking;
import com.xm.thefourthfrequency.networking.PursuitNetworking;
import com.xm.thefourthfrequency.networking.UnrenderedNetworking;
import com.xm.thefourthfrequency.terminal.TerminalRelayService;
import com.xm.thefourthfrequency.terminal.TerminalRuntimeService;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.terminal.AmbientAnomalyService;
import com.xm.thefourthfrequency.terminal.AnomalyRuntimeService;
import com.xm.thefourthfrequency.terminal.AnomalyServerEffects;
import com.xm.thefourthfrequency.terminal.TerminalSignalService;
import com.xm.thefourthfrequency.pursuit.PursuitSessionService;
import com.xm.thefourthfrequency.pursuit.PursuitSlotManager;
import com.xm.thefourthfrequency.pursuit.PursuitSnapshotBuilder;
import com.xm.thefourthfrequency.pursuit.PursuitBlockPolicy;
import com.xm.thefourthfrequency.pursuit.PursuitActivityTracker;
import com.xm.thefourthfrequency.pursuit.PursuitDirector;
import com.xm.thefourthfrequency.unrendered.UnrenderedDimensions;
import com.xm.thefourthfrequency.unrendered.UnrenderedBlockPolicy;
import com.xm.thefourthfrequency.unrendered.UnrenderedSessionService;
import com.xm.thefourthfrequency.pursuit.PursuitFormController;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TheFourthFrequency implements ModInitializer {
	public static final String MOD_ID = "thefourthfrequency";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModConfig config = ConfigManager.load();
		RuntimeServices.initialize(config);
		ModSounds.initialize();
		ModBlocks.initialize();
		WorldInterfaceBlockEntities.initialize();
		ModItems.initialize();
		ModEntities.initialize();
		// Before any level is loaded: the layer's dimension file names this codec by id, and a
		// generator type a datapack cannot resolve drops the dimension silently.
		UnrenderedDimensions.initialize();
		M5Networking.initialize();
		M6Networking.initialize();
		M8Networking.initialize();
		TerminalNetworking.initialize();
		WorldInterfaceNetworking.initialize();
		PursuitNetworking.initialize();
		UnrenderedNetworking.initialize();
		DebugNetworking.initialize();
		ZeroStationService.initialize();
		TerminalLifecycleService.initialize();
		FragmentInvestigationService.initialize();
		TerminalRuntimeService.initialize();
		TerminalToolService.initialize();
		TerminalSignalService.initialize();
		StoryProgressService.initialize();
		SurvivalProgressService.initialize();
		WatcherService.initialize();
		HimService.initialize();
		WorldDecayService.initialize();
		AnomalyRuntimeService.initialize();
		AnomalyServerEffects.initialize();
		AmbientAnomalyService.initialize();
		TerminalRelayService.initialize();
		PursuitSlotManager.initialize();
		PursuitSnapshotBuilder.initialize();
		PursuitBlockPolicy.initialize();
		PursuitActivityTracker.initialize();
		PursuitFormController.initialize();
		PursuitSessionService.initialize();
		UnrenderedSessionService.initialize();
		UnrenderedBlockPolicy.initialize();
		PursuitDirector.initialize();
		TerminalActivityTracker.initialize();
		ResourceGuidanceService.initialize();
		StructureNavigationService.initialize();
		GuidanceArrivalService.initialize();
		EmptySegmentService.initialize();
		PortalContinuityService.initialize();
		EndPopulationService.initialize();
		PlayerPatternService.initialize();
		EndBossArenaService.initialize();
		// Purely to register a teardown: the ending's dragon indexes hold live entity references, and
		// an entity holds the server it belongs to. Nothing here starts a tick loop.
		FriendlyDragonService.initialize();
		// Before the attack service and the encounter service: both gate audio and camera events on
		// its per-source cooldowns, and a cooldown map whose lifecycle hook was never registered
		// outlives the server it belongs to.
		WorldInterfaceBlastService.initialize();
		// Before the attack service, which marks the stacks its hotbar purge throws.
		WorldInterfaceDropBeaconService.initialize();
		WorldInterfaceAttackService.initialize();
		WorldInterfaceRitualService.initialize();
		EndBossEncounterService.initialize();
		TerminalCommands.initialize();
		LOGGER.info("The Fourth Frequency common bootstrap is ready (schema {}, accelerated={})",
				RuntimeServices.PERSISTENCE_SCHEMA_VERSION, config.pacing().developerAcceleration());
	}
}
