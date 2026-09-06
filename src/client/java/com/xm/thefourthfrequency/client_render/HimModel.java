package com.xm.thefourthfrequency.client_render;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import com.xm.thefourthfrequency.entity.HorrorMotion;
import net.minecraft.util.Mth;

/**
 * Familiar human proportions with a delayed head turn and an unnaturally held listening tilt.
 *
 * <p>Built on vanilla's own humanoid mesh rather than a hand-authored one, because the entire point
 * of the figure is that it is Steve-shaped. Anything with its own proportions reads as a custom mob
 * inside the fifth of a second it is on screen, and the sighting turns into an identification.
 */
public final class HimModel extends HumanoidModel<HimRenderState> {
	public HimModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition createBodyLayer() {
		return WorldInterfaceGeometry.loadEntity("him").layer();
	}

	public static LayerDefinition createAuthoringLayer() {
		return LayerDefinition.create(HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F), 64, 64);
	}

	@Override
	public void setupAnim(HimRenderState state) {
		super.setupAnim(state);
		// A head-first turn, followed by an unnaturally long held tilt. No walking sway.
		float settle = HorrorMotion.ease(state.ageInTicks / 18.0F);
		float listen = HorrorMotion.envelope(state.ageInTicks % 197, 38, 19, 72, 42);
		head.xRot = -0.075F * settle;
		head.yRot = Mth.clamp(state.yRot * Mth.DEG_TO_RAD, -.6F, .6F);
		head.zRot = -0.12F * listen;
		hat.xRot = head.xRot;
		hat.yRot = head.yRot;
		hat.zRot = head.zRot;
		rightArm.xRot = 0.0F;
		rightArm.yRot = 0.0F;
		rightArm.zRot = 0.0F;
		leftArm.xRot = 0.0F;
		leftArm.yRot = 0.0F;
		leftArm.zRot = 0.0F;
		rightLeg.xRot = 0.0F;
		rightLeg.yRot = 0.0F;
		rightLeg.zRot = 0.0F;
		leftLeg.xRot = 0.0F;
		leftLeg.yRot = 0.0F;
		leftLeg.zRot = 0.0F;
	}
}
