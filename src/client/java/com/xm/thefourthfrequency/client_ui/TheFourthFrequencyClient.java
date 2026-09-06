package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.content.ModEntities;
import com.xm.thefourthfrequency.content.WorldInterfaceBlockEntities;
import com.xm.thefourthfrequency.content.WorldInterfaceExitPortalBlockEntity;
import com.xm.thefourthfrequency.client_render.ReworkBodyModel;
import com.xm.thefourthfrequency.client_render.ReworkBodyRenderer;
import com.xm.thefourthfrequency.client_render.HimModel;
import com.xm.thefourthfrequency.client_render.BacteriaModel;
import com.xm.thefourthfrequency.client_render.BacteriaRenderer;
import com.xm.thefourthfrequency.client_render.HimRenderer;
import com.xm.thefourthfrequency.client_render.StabilityAnchorModel;
import com.xm.thefourthfrequency.client_render.StabilityAnchorRenderer;
import com.xm.thefourthfrequency.client_render.WatcherRenderer;
import com.xm.thefourthfrequency.client_render.WatcherModel;
import com.xm.thefourthfrequency.client_render.WorldInterfaceModel;
import com.xm.thefourthfrequency.client_render.WorldInterfaceEnergyOrbRenderer;
import com.xm.thefourthfrequency.client_render.WorldInterfaceRenderer;
import com.xm.thefourthfrequency.meta_api.MetaController;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.TheEndPortalRenderer;
import net.minecraft.client.renderer.blockentity.state.EndPortalRenderState;
import net.minecraft.world.level.block.entity.TheEndPortalBlockEntity;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.xm.thefourthfrequency.networking.TerminalOpenPayload;
import net.minecraft.client.renderer.entity.NoopRenderer;

public final class TheFourthFrequencyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		com.xm.thefourthfrequency.client_render.StormParticle.initialize();
		WorldInterfaceEndingClient.initialize();
		AlphaLoadSessionController.initialize();
		DimensionViewDistanceController.initialize();
		PursuitPresentationClient.initialize();
		EntityModelLayerRegistry.registerModelLayer(ReworkBodyRenderer.STAGE_1_LAYER,
				ReworkBodyModel::createStage1Layer);
		EntityModelLayerRegistry.registerModelLayer(ReworkBodyRenderer.STAGE_2_LAYER,
				ReworkBodyModel::createStage2Layer);
		EntityModelLayerRegistry.registerModelLayer(ReworkBodyRenderer.STAGE_3_LAYER,
				ReworkBodyModel::createStage3Layer);
		EntityRendererRegistry.register(ModEntities.REWORK_BODY, ReworkBodyRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(WatcherRenderer.MODEL_LAYER, WatcherModel::createBodyLayer);
		EntityRendererRegistry.register(ModEntities.WATCHER, WatcherRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(BacteriaRenderer.MODEL_LAYER,
				BacteriaModel::createBodyLayer);
		EntityRendererRegistry.register(ModEntities.BACTERIA, BacteriaRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(HimRenderer.MODEL_LAYER, HimModel::createBodyLayer);
		EntityRendererRegistry.register(ModEntities.HIM, HimRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(WorldInterfaceRenderer.MODEL_LAYER,
				WorldInterfaceModel::createLayer);
		EntityRendererRegistry.register(ModEntities.WORLD_INTERFACE, WorldInterfaceRenderer::new);
		EntityRendererRegistry.register(ModEntities.WORLD_INTERFACE_PART, NoopRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(WorldInterfaceEnergyOrbRenderer.MODEL_LAYER,
				com.xm.thefourthfrequency.client_render.WorldInterfaceEnergyOrbModel::createLayer);
		EntityRendererRegistry.register(ModEntities.WORLD_INTERFACE_ENERGY_ORB,
				WorldInterfaceEnergyOrbRenderer::new);
		EntityModelLayerRegistry.registerModelLayer(StabilityAnchorRenderer.MODEL_LAYER,
				StabilityAnchorModel::createLayer);
		EntityRendererRegistry.register(ModEntities.STABILITY_ANCHOR, StabilityAnchorRenderer::new);
		// Vanilla's own portal renderer, unmodified. BlockEntityRenderers#register is not public and
		// this mod pulls in no access wideners, so the deprecated Fabric helper is the way in. The
		// provider is bound to the vanilla supertype explicitly: inferring it from the exit's own
		// block entity type gives the wildcard an equality constraint it cannot satisfy.
		BlockEntityRendererProvider<TheEndPortalBlockEntity, EndPortalRenderState> exitPortalRenderer =
				context -> new TheEndPortalRenderer();
		BlockEntityRendererRegistry.<WorldInterfaceExitPortalBlockEntity, EndPortalRenderState>register(
				WorldInterfaceBlockEntities.EXIT_PORTAL, exitPortalRenderer);
		EmptySegmentClient.initialize();
		PrivateAnomalyClient.initialize();
		FirstRunNoticeController.initialize();
		MetaController.initialize();
		TerminalClientNetworking.initialize();
		TerminalAutoOpenController.initialize();
		TerminalNoticeHud.initialize();
		WorldInterfaceClientNetworking.initialize();
		WorldInterfacePresentationController.initialize();
		WorldInterfaceHud.initialize();
		AmbientAnomalyClient.initialize();
		AnomalyPresentationController.initialize();
		UnrenderedLayerClient.initialize();
		MenuErosionState.initialize();
		MusicDirector.initialize();
		DebugPanelClient.initialize();
		WorldDecayClient.initialize();
		SignalBedController.initialize();
		SkyInstrumentSampler.initialize();
		EndWeatherClient.initialize();
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() && player.getItemInHand(hand).is(ModItems.OLD_TERMINAL)
					&& TerminalData.belongsTo(player.getItemInHand(hand), player.getUUID())) {
				if (ClientPlayNetworking.canSend(TerminalOpenPayload.TYPE)) {
					ClientPlayNetworking.send(new TerminalOpenPayload(hand == net.minecraft.world.InteractionHand.MAIN_HAND ? 0 : 1));
				}
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.PASS;
		});
		TheFourthFrequency.LOGGER.info("The Fourth Frequency client presentation layer is ready");
	}
}
