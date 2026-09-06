package com.xm.thefourthfrequency.client_render;

import com.xm.thefourthfrequency.bootstrap.TheFourthFrequency;
import com.xm.thefourthfrequency.entity.BacteriaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Draws the mass world-lit, with no emissive pass of any kind.
 *
 * <p>Every other entity this mod ships carries something that lights itself, because each of them
 * needs to be findable in a dark room. This one does not: the layer it lives in is evenly, brightly
 * lit everywhere, so it is always fully visible and never once easier to see than the wall behind
 * it. The dread is that you can see it perfectly well and it is still closing.
 */
public final class BacteriaRenderer
		extends MobRenderer<BacteriaEntity, BacteriaRenderState, BacteriaModel> {
	public static final net.minecraft.client.model.geom.ModelLayerLocation MODEL_LAYER =
			new net.minecraft.client.model.geom.ModelLayerLocation(
					Identifier.fromNamespaceAndPath(TheFourthFrequency.MOD_ID, "bacteria"), "main");
	private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
			TheFourthFrequency.MOD_ID, "textures/entity/bacteria.png");
	/** Blocks per tick at which the lean is fully applied. Its cruise speed, near enough. */
	private static final float FULL_SURGE_SPEED = 0.32F;

	public BacteriaRenderer(EntityRendererProvider.Context context) {
		super(context, new BacteriaModel(context.bakeLayer(MODEL_LAYER)), 0.7F);
		// A hard, wide shadow. It is the one part of the presentation that arrives before the thing
		// itself does, around a corner the player cannot see past yet.
		shadowStrength = 0.9F;
	}

	@Override
	public BacteriaRenderState createRenderState() {
		return new BacteriaRenderState();
	}

	@Override
	public void extractRenderState(BacteriaEntity entity, BacteriaRenderState state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		double dx = entity.getX() - entity.xOld;
		double dz = entity.getZ() - entity.zOld;
		float speed = (float) Math.sqrt(dx * dx + dz * dz);
		state.walkSurge = Mth.clamp(speed / FULL_SURGE_SPEED, 0.0F, 1.0F);
	}

	@Override
	public Identifier getTextureLocation(BacteriaRenderState state) {
		return TEXTURE;
	}
}
