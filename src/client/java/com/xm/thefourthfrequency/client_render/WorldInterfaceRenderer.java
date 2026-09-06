package com.xm.thefourthfrequency.client_render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.client_ui.WorldInterfaceClientState;
import com.xm.thefourthfrequency.client_ui.WorldInterfacePresentationController;
import com.xm.thefourthfrequency.entity.WorldInterfaceRig;
import com.xm.thefourthfrequency.entity.WorldInterfaceEntity;
import com.xm.thefourthfrequency.networking.WorldInterfaceSnapshotS2C;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.phys.AABB;

public final class WorldInterfaceRenderer extends MobRenderer<WorldInterfaceEntity,
		WorldInterfaceRenderState, WorldInterfaceModel> {
	/** Base, emissive and headroom for additional passes; density is no longer capped at two. */
	public static final int MAX_RENDER_LAYERS = 6;
	public static final ModelLayerLocation MODEL_LAYER = new ModelLayerLocation(
			Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "world_interface"), "main");
	private static final Identifier[] BASE = textures("");
	private static final Identifier[] EMISSIVE = textures("_emissive");
	private static final Identifier[] HIT = textures("_hit");
	private static final Identifier BLACK = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID,
			"textures/entity/world_interface_form_3_black.png");

	public WorldInterfaceRenderer(EntityRendererProvider.Context context) {
		super(context, new WorldInterfaceModel(context.bakeLayer(MODEL_LAYER)), 4.0F);
		addLayer(new EyeGlowLayer(this));
	}

	private static Identifier[] textures(String suffix) {
		Identifier[] result = new Identifier[3];
		for (int index = 0; index < result.length; index++) {
			result[index] = Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID,
					"textures/entity/world_interface_form_" + (index + 1) + suffix + ".png");
		}
		return result;
	}

	@Override
	public WorldInterfaceRenderState createRenderState() {
		return new WorldInterfaceRenderState();
	}

	@Override
	protected int getBlockLightLevel(WorldInterfaceEntity entity, net.minecraft.core.BlockPos position) {
		// Light escaping the storm reveals its own surface even beneath the End's rain treatment.
		return Math.max(9, super.getBlockLightLevel(entity, position));
	}

	@Override
	public void extractRenderState(WorldInterfaceEntity entity, WorldInterfaceRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.ageInTicks = entity.poseClock() + partialTick;
		state.form = Math.clamp(entity.form(), 0, 2);
		state.actionId = entity.actionId();
		long now = entity.level().getGameTime();
		state.actionAgeMillis = (long) (Math.max(0.0D,
				now - entity.actionStartTick() + partialTick) * 50.0D);
		state.blackened = state.actionId == 14;
		state.hasRedOverlay |= WorldInterfacePresentationController.isDamageFlashActive(entity.getUUID(), now);
		WorldInterfaceSnapshotS2C encounter = WorldInterfaceClientState.snapshot().encounter();
		state.paletteBand = WorldInterfacePalette.band(encounter == null ? null : encounter.stage());
		// Off the entity, not the HUD snapshot. The pose the hit boxes stand on is driven by this
		// number, and the HUD snapshot arrives on its own cadence - two clocks would mean the sag a
		// player can see and the sag they can hit were computed from different health.
		state.healthFraction = entity.healthFraction();
		state.actionCharge = WorldInterfaceRig.actionCharge(state.actionId, state.actionAgeMillis);
		// Same rule as the health fraction: the server poses the head boxes from these, so they are
		// read off the entity rather than aimed at the local camera. Taken through the interpolating
		// accessors, because a synchronised value drawn raw steps once a tick - which on a skull this
		// size reads as the head blinking rather than turning.
		state.gazeYaw = entity.renderGazeYaw(partialTick);
		state.gazePitch = entity.renderGazePitch(partialTick);
	}

	/**
	 * The form scale, with the morph pinch that hides the one-tick form swap.
	 *
	 * <p>Read off {@link WorldInterfaceRig} rather than computed here. The pinch takes up to
	 * seventy-two percent off the drawn body for four seconds, and the hit boxes have to shrink with
	 * it or they stand in air around a body no longer filling them - which they can only do if the
	 * scale is one function rather than two.
	 */
	@Override
	protected void scale(WorldInterfaceRenderState state, PoseStack poseStack) {
		float scale = WorldInterfaceRig.renderScale(state.form, state.actionId, state.actionAgeMillis);
		poseStack.scale(scale, scale, scale);
	}

	@Override
	public Identifier getTextureLocation(WorldInterfaceRenderState state) {
		return state.blackened ? BLACK : BASE[Math.clamp(state.form, 0, 2)];
	}

	@Override
	protected AABB getBoundingBoxForCulling(WorldInterfaceEntity entity) {
		// Sized off the built silhouette rather than the hitbox: mass, halo and tentacles all reach
		// far outside the entity box. The vertical term was a flat twelve against a third form that
		// stands some forty blocks tall, so framing the tentacles used to cull the whole storm.
		int form = Math.clamp(entity.form(), 0, 2);
		return super.getBoundingBoxForCulling(entity)
				.inflate(16.0 + form * 14.0, 14.0 + form * 12.0, 16.0 + form * 8.0);
	}

	private static final class EyeGlowLayer extends RenderLayer<WorldInterfaceRenderState, WorldInterfaceModel> {
		private EyeGlowLayer(WorldInterfaceRenderer renderer) {
			super(renderer);
		}

		@Override
		public void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
				WorldInterfaceRenderState state, float yRot, float xRot) {
			if (state.isInvisible || state.blackened) return;
			int band = Math.clamp(state.paletteBand, 0, WorldInterfacePalette.PHASE_BAND_COUNT - 1);
			// Both the hue and the breath rate escalate with the band, so a player who never looks
			// at the HUD still sees the interface turn from patient purple to agitated red.
			float wave = ((float) Math.sin(state.ageInTicks * WorldInterfacePalette.breathSpeed(band))
					+ 1.0F) * 0.5F;
			int form = Math.clamp(state.form, 0, 2);
			// The glow used to breathe at a constant depth, which said nothing about what the
			// interface was doing. Winding an action up now visibly overdrives it, so the tell is
			// on the body itself rather than only in the HUD label.
			float charge = state.actionCharge < 0.0F ? 0.0F
					: state.actionCharge * state.actionCharge;
			if (state.hasRedOverlay) {
				// The damage flash has to reach plating and debris the glow never touches, so this
				// one still costs a whole second model submission. It lasts a few ticks.
				collector.order(1).submitModel(getParentModel(), state, poseStack,
						RenderTypes.entityTranslucentEmissive(HIT[form]),
						LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
						ARGB.colorFromFloat(1.0F, 1.0F, 1.0F, 1.0F), null, state.outlineColor, null);
				return;
			}
			int color = ARGB.colorFromFloat(Math.min(1.0F, 0.78F + wave * 0.18F + charge * 0.34F),
					WorldInterfacePalette.red(band),
					WorldInterfacePalette.green(band), WorldInterfacePalette.blue(band));
			// Only the bones that carry glow. The emissive sheet is under half a percent painted,
			// and the third form has several hundred parts to walk past to reach them.
			getParentModel().submitEmissive(poseStack, collector.order(1),
					RenderTypes.entityTranslucentEmissive(EMISSIVE[form]), color,
					state.outlineColor, form);
		}
	}
}
