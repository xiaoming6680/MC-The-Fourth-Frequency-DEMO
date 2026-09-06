package com.xm.thefourthfrequency.client_render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xm.thefourthfrequency.entity.StabilityAnchorGeometry;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

/**
 * A four-way clamp gripping a single bedrock cap, carrying a bare relay core on a thin axle.
 *
 * <p>Three things about the shape are load-bearing.
 *
 * <p><b>It is a clamp, not a figure.</b> Four identical claws at ninety degrees, each a five-link
 * chain - support, pivot, tie rod, wrist, foot - that reaches up and out and then comes down onto
 * the cap and folds under its edge. Read from directly above it is unmistakably a four-jaw chuck;
 * two arms on a torso would have read as a small person standing on the pillar, and there is no head
 * and no face anywhere on it precisely so that it cannot.
 *
 * <p><b>The emitter is open in every direction.</b> The relay core stands clear on a single thin
 * axle with nothing above, beside or around it: the interface's tether leaves it toward wherever the
 * boss actually is, which over one fight is the entire upper hemisphere. Anything ring-shaped,
 * cage-shaped or barrel-shaped up there would have committed the anchor to one fixed aim, and the
 * reference's diagonal beam is one instant of a live connection, not a mounting. The four
 * calibration petals sit strictly below the core's equator and touch neither each other nor it.
 *
 * <p><b>Only the cores glow.</b> The emissive pass carries the chest core, the relay core and four
 * hairline gold seams through the claw pivots, and nothing else. The structure is obsidian, bone ash
 * and dim violet, and it has to stay legible as a machine standing in the dark rather than as a
 * lantern - which is also what leaves the destruction anywhere to go.
 */
public final class StabilityAnchorModel extends EntityModel<StabilityAnchorRenderState> {
	public static final int CLAW_COUNT = 4;
	/** body, torso, chest core, emitter, spindle, relay core, 4 petals, 4x5 claw links and 4 seams. */
	public static final int ANIMATED_BONE_COUNT = 32;

	private static final float TORSO_TOP = -24.0F;
	private static final float SHOULDER_Y = -18.5F;
	private static final float SHOULDER_RADIUS = 4.2F;
	private static final float UPPER_ARM_LENGTH = 8.2F;
	private static final float UPPER_ARM_PITCH = -0.55F;
	private static final float FOREARM_LENGTH = 22.0F;
	private static final float FOREARM_PITCH = 2.05F;
	private static final float FOOT_LENGTH = 10.6F;
	private static final float FOOT_PITCH = 0.48F;
	/** Emitter-local Y of the relay core; the emitter bone itself sits on the torso cap. */
	private static final float RELAY_LOCAL_Y = StabilityAnchorGeometry.RELAY_CORE_MODEL_Y - TORSO_TOP;
	/** From the torso cap up to the underside of the relay core; the core's half-extent is the 2. */
	private static final float SPINDLE_LENGTH = -RELAY_LOCAL_Y - 2.0F;
	private static final float PETAL_LOCAL_Y = -5.0F;
	private static final float PETAL_PITCH = 0.28F;

	private final ModelPart body;
	private final ModelPart torso;
	private final ModelPart chestCore;
	private final ModelPart emitter;
	private final ModelPart relayCore;
	private final ModelPart[] petals = new ModelPart[CLAW_COUNT];
	private final ModelPart[] clawRoots = new ModelPart[CLAW_COUNT];
	private final ModelPart[] upperArms = new ModelPart[CLAW_COUNT];
	private final ModelPart[] pivots = new ModelPart[CLAW_COUNT];
	private final ModelPart[] seams = new ModelPart[CLAW_COUNT];
	private final ModelPart[] forearms = new ModelPart[CLAW_COUNT];
	private final ModelPart[] wrists = new ModelPart[CLAW_COUNT];
	private final ModelPart[] feet = new ModelPart[CLAW_COUNT];

	public StabilityAnchorModel(ModelPart root) {
		super(root);
		body = root.getChild("body");
		torso = body.getChild("torso");
		chestCore = torso.getChild("chest_core");
		emitter = body.getChild("emitter");
		relayCore = emitter.getChild("relay_core");
		for (int index = 0; index < CLAW_COUNT; index++) {
			petals[index] = emitter.getChild("petal_" + index);
			clawRoots[index] = body.getChild("claw_" + index);
			upperArms[index] = clawRoots[index].getChild("claw_" + index + "_upper_arm");
			pivots[index] = upperArms[index].getChild("claw_" + index + "_pivot");
			seams[index] = pivots[index].getChild("claw_" + index + "_seam");
			forearms[index] = pivots[index].getChild("claw_" + index + "_forearm");
			wrists[index] = forearms[index].getChild("claw_" + index + "_wrist");
			feet[index] = wrists[index].getChild("claw_" + index + "_foot");
		}
	}

	/**
	 * Model space is the usual entity convention: positive Y is downward, and {@code y = 0} is the
	 * entity origin, which is the top face of the spike's single bedrock cap. The claws are therefore
	 * the only geometry with positive Y - they are what reaches down over the cap's edge.
	 */
	public static LayerDefinition createLayer() {
		return WorldInterfaceGeometry.loadEntity("stability_anchor").layer();
	}

	public static LayerDefinition createAuthoringLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		// One bone between root and everything else, so the breathing lift has somewhere to live that
		// is not the torso: the anchor never translates, and the torso has to stay the fixed reference
		// the claws and the emitter are placed from.
		PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create(), PartPose.ZERO);

		buildTorso(body);
		buildEmitter(body);
		for (int index = 0; index < CLAW_COUNT; index++) buildClaw(body, index);
		return LayerDefinition.create(mesh, StabilityAnchorUv.SHEET_WIDTH, StabilityAnchorUv.SHEET_HEIGHT);
	}

	/**
	 * A narrow column with no head and no face, built as four corner posts around an open middle so
	 * the chest core is visible from every side rather than being a decal on one face.
	 */
	private static void buildTorso(PartDefinition body) {
		CubeListBuilder column = CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.OBSIDIAN_U, StabilityAnchorUv.OBSIDIAN_V)
				.addBox(-4.5F, -4.0F, -4.5F, 9.0F, 4.0F, 9.0F)
				.texOffs(StabilityAnchorUv.OBSIDIAN_U, StabilityAnchorUv.OBSIDIAN_V)
				.addBox(-4.0F, TORSO_TOP, -4.0F, 8.0F, 2.4F, 8.0F);
		for (int corner = 0; corner < 4; corner++) {
			float x = (corner & 1) == 0 ? 3.2F : -3.2F;
			float z = (corner & 2) == 0 ? 3.2F : -3.2F;
			column = column.texOffs(StabilityAnchorUv.OBSIDIAN_U, StabilityAnchorUv.OBSIDIAN_V)
					.addBox(x - 1.3F, -21.6F, z - 1.3F, 2.6F, 17.6F, 2.6F)
					// A hairline of gold down the outer face of each post. Unlit here; the pivots carry
					// the only seams the emissive pass draws.
					.texOffs(StabilityAnchorUv.GOLD_U, StabilityAnchorUv.GOLD_V)
					.addBox(x - 0.35F, -20.4F, z + (z > 0.0F ? 1.3F : -1.7F), 0.7F, 15.2F, 0.4F);
		}
		PartDefinition torso = body.addOrReplaceChild("torso", column, PartPose.ZERO);

		torso.addOrReplaceChild("chest_core", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.CORE_U, StabilityAnchorUv.CORE_V)
				.addBox(-3.0F, -3.0F, -3.0F, 6.0F, 6.0F, 6.0F),
				PartPose.offset(0.0F, StabilityAnchorGeometry.CHEST_CORE_MODEL_Y, 0.0F));

	}

	/**
	 * The omnidirectional emitter: a bare core on one hairline axle, with four petals hung below it.
	 *
	 * <p>Nothing is placed level with or above the core. That is the whole point of the assembly -
	 * the tether has to be able to leave it through a full horizontal circle and at a steep pitch,
	 * because the interface spends the fight moving.
	 */
	private static void buildEmitter(PartDefinition body) {
		PartDefinition emitter = body.addOrReplaceChild("emitter", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.SPINDLE_U, StabilityAnchorUv.SPINDLE_V)
				.addBox(-0.7F, -SPINDLE_LENGTH, -0.7F, 1.4F, SPINDLE_LENGTH, 1.4F),
				PartPose.offset(0.0F, TORSO_TOP, 0.0F));

		emitter.addOrReplaceChild("relay_core", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.CORE_U, StabilityAnchorUv.CORE_V)
				.addBox(-2.0F, -2.0F, -2.0F, 4.0F, 4.0F, 4.0F),
				PartPose.offset(0.0F, RELAY_LOCAL_Y, 0.0F));

		for (int index = 0; index < CLAW_COUNT; index++) {
			float angle = Mth.HALF_PI * index + Mth.PI * 0.25F;
			emitter.addOrReplaceChild("petal_" + index, CubeListBuilder.create()
					.texOffs(StabilityAnchorUv.SPINDLE_U, StabilityAnchorUv.SPINDLE_V)
					.addBox(1.0F, -0.35F, -0.35F, 5.0F, 0.7F, 0.7F)
					.texOffs(StabilityAnchorUv.PETAL_U, StabilityAnchorUv.PETAL_V)
					.addBox(6.0F, -1.2F, -1.2F, 2.4F, 2.4F, 2.4F),
					PartPose.offsetAndRotation(0.0F, PETAL_LOCAL_Y, 0.0F, 0.0F, -angle, PETAL_PITCH));
		}
	}

	/**
	 * One of four identical clamps. Local {@code +X} is radially outward, local {@code +Y} is
	 * downward, so every link is authored along {@code +X} and turned by its own pitch; the chain is
	 * what carries the arm up and out, then down past the cap, then in under its edge.
	 */
	private static void buildClaw(PartDefinition body, int index) {
		float angle = Mth.HALF_PI * index + Mth.PI * 0.25F;
		String name = "claw_" + index;
		PartDefinition claw = body.addOrReplaceChild(name, CubeListBuilder.create(),
				PartPose.offsetAndRotation(Mth.cos(angle) * SHOULDER_RADIUS, SHOULDER_Y,
						Mth.sin(angle) * SHOULDER_RADIUS, 0.0F, -angle, 0.0F));

		PartDefinition upperArm = claw.addOrReplaceChild(name + "_upper_arm", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.CLAW_U, StabilityAnchorUv.CLAW_V)
				.addBox(0.0F, -1.7F, -1.9F, UPPER_ARM_LENGTH, 3.4F, 3.8F),
				PartPose.rotation(0.0F, 0.0F, UPPER_ARM_PITCH));

		PartDefinition pivot = upperArm.addOrReplaceChild(name + "_pivot", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.JOINT_U, StabilityAnchorUv.JOINT_V)
				.addBox(-2.1F, -2.1F, -2.1F, 4.2F, 4.2F, 4.2F),
				PartPose.offset(UPPER_ARM_LENGTH, 0.0F, 0.0F));

		// The only lit thing outside the two cores: a gold cross-seam through the pivot, on its own
		// bone so the emissive pass can reach it without submitting the whole claw.
		pivot.addOrReplaceChild(name + "_seam", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.GOLD_U, StabilityAnchorUv.GOLD_V)
				.addBox(-2.3F, -0.5F, -0.5F, 4.6F, 1.0F, 1.0F)
				.texOffs(StabilityAnchorUv.GOLD_U, StabilityAnchorUv.GOLD_V)
				.addBox(-0.5F, -0.5F, -2.3F, 1.0F, 1.0F, 4.6F),
				PartPose.ZERO);

		PartDefinition forearm = pivot.addOrReplaceChild(name + "_forearm", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.CLAW_U, StabilityAnchorUv.CLAW_V)
				.addBox(0.0F, -1.5F, -1.6F, FOREARM_LENGTH, 3.0F, 3.2F),
				PartPose.rotation(0.0F, 0.0F, FOREARM_PITCH));

		PartDefinition wrist = forearm.addOrReplaceChild(name + "_wrist", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.JOINT_U, StabilityAnchorUv.JOINT_V)
				.addBox(-1.7F, -1.7F, -1.7F, 3.4F, 3.4F, 3.4F),
				PartPose.offset(FOREARM_LENGTH, 0.0F, 0.0F));

		wrist.addOrReplaceChild(name + "_foot", CubeListBuilder.create()
				.texOffs(StabilityAnchorUv.FOOT_U, StabilityAnchorUv.FOOT_V)
				.addBox(0.0F, -2.4F, -2.8F, FOOT_LENGTH, 4.8F, 5.6F),
				PartPose.rotation(0.0F, 0.0F, FOOT_PITCH));
	}

	/**
	 * Submits only the bones that carry glow.
	 *
	 * <p>Six bones out of thirty-two, and the emissive sheet is painted on two islands out of eight.
	 * Submitting the whole model a second time would push every claw box through a translucent pass
	 * to draw nothing at all. Parent transforms are walked by hand because a bone submitted directly
	 * does not inherit them.
	 */
	void submitEmissive(PoseStack poseStack, OrderedSubmitNodeCollector collector, RenderType renderType,
			int color, int outlineColor) {
		if (!body.visible) return;
		poseStack.pushPose();
		root().translateAndRotate(poseStack);
		body.translateAndRotate(poseStack);

		poseStack.pushPose();
		torso.translateAndRotate(poseStack);
		submitPart(collector, chestCore, poseStack, renderType, color, outlineColor);
		poseStack.popPose();

		poseStack.pushPose();
		emitter.translateAndRotate(poseStack);
		submitPart(collector, relayCore, poseStack, renderType, color, outlineColor);
		poseStack.popPose();

		for (int index = 0; index < CLAW_COUNT; index++) {
			if (!clawRoots[index].visible) continue;
			poseStack.pushPose();
			clawRoots[index].translateAndRotate(poseStack);
			upperArms[index].translateAndRotate(poseStack);
			pivots[index].translateAndRotate(poseStack);
			submitPart(collector, seams[index], poseStack, renderType, color, outlineColor);
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	private static void submitPart(OrderedSubmitNodeCollector collector, ModelPart part,
			PoseStack poseStack, RenderType renderType, int color, int outlineColor) {
		if (!part.visible) return;
		collector.submitModelPart(part, poseStack, renderType, LightTexture.FULL_BRIGHT,
				OverlayTexture.NO_OVERLAY, null, false, false, color, null, outlineColor);
	}

	@Override
	public void setupAnim(StabilityAnchorRenderState state) {
		super.setupAnim(state);
		float collapse = state.collapseAge;
		float presence = collapse < 0.0F ? 1.0F : StabilityAnchorGeometry.collapsePresence(collapse);
		float fold = collapse < 0.0F ? 0.0F : StabilityAnchorGeometry.collapseFold(collapse);
		boolean drawn = presence > 0.001F;

		body.visible = drawn;
		if (!drawn) return;

		applyIdle(state);
		applyCollapse(collapse, fold, presence);
	}

	/**
	 * The living-anchor motion, and all of it: brightness is the renderer's business, the relay core
	 * floats by about a millimetre and the petals trim themselves. The structure itself never moves -
	 * an anchor that swayed would look like it was about to fall over, which is the opposite of what
	 * ten of these are in the arena to say.
	 */
	private void applyIdle(StabilityAnchorRenderState state) {
		float time = state.ageInTicks;
		relayCore.y = RELAY_LOCAL_Y + Mth.sin(time * 0.06F) * 0.25F;
		relayCore.xRot = 0.0F;
		relayCore.zRot = 0.0F;
		relayCore.yRot = time * 0.018F;
		torso.xScale = torso.yScale = torso.zScale = 1.0F;
		emitter.xScale = emitter.yScale = emitter.zScale = 1.0F;
		for (int index = 0; index < CLAW_COUNT; index++) {
			petals[index].zRot = PETAL_PITCH + Mth.sin(time * 0.045F + index * 1.57F) * 0.024F;
			petals[index].visible = true;
			clawRoots[index].visible = true;
			clawRoots[index].xScale = 1.0F;
			clawRoots[index].yScale = 1.0F;
			clawRoots[index].zScale = 1.0F;
			float tension = Mth.sin(time * .034F + index * 1.57F) * .018F;
			upperArms[index].zRot = UPPER_ARM_PITCH + tension;
			forearms[index].zRot = FOREARM_PITCH - tension * 1.25F;
			feet[index].zRot = FOOT_PITCH + tension * .25F;
			seams[index].visible = true;
			seams[index].xScale = 1.0F;
			seams[index].yScale = 1.0F;
			seams[index].zScale = 1.0F;
		}
		chestCore.visible = true;
		relayCore.visible = true;
		chestCore.xScale = chestCore.yScale = chestCore.zScale = 1.0F;
		relayCore.xScale = relayCore.yScale = relayCore.zScale = 1.0F;
	}

	/**
	 * The sixteen ticks after the anchor is already gone from the fight.
	 *
	 * <p>Fracture is light only; the tether snap kicks the petals outward; the implosion folds the
	 * claws in against the cap and shrinks both cores to nothing while the whole assembly pixelates
	 * away. Nothing here decides anything - it runs off a synched start tick and the server has
	 * already updated the alive mask, the healing and the hit box.
	 */
	private void applyCollapse(float age, float fold, float presence) {
		if (age < 0.0F) return;
		StabilityAnchorGeometry.CollapsePhase phase = StabilityAnchorGeometry.collapsePhase(age);
		// The overexposed instant, then a hard shrink. The core is the first thing to go and the
		// first thing the eye is on, so it carries the whole "this just failed" beat.
		float flash = phase == StabilityAnchorGeometry.CollapsePhase.FRACTURE
				? 1.0F + (1.0F - age / StabilityAnchorGeometry.COLLAPSE_FRACTURE_END) * 0.35F
				: 1.0F;
		float coreScale = flash * presence * presence;
		chestCore.xScale = chestCore.yScale = chestCore.zScale = coreScale;
		relayCore.xScale = relayCore.yScale = relayCore.zScale = coreScale;
		chestCore.visible = coreScale > 0.01F;
		relayCore.visible = coreScale > 0.01F;

		float petalKick = phase == StabilityAnchorGeometry.CollapsePhase.FRACTURE ? 0.0F
				: Math.min(0.35F, (age - StabilityAnchorGeometry.COLLAPSE_FRACTURE_END) * 0.12F);
		for (int index = 0; index < CLAW_COUNT; index++) {
			petals[index].zRot = PETAL_PITCH - petalKick;
			// Depressurised: the arms lose their tension and the chain folds inward over the cap.
			upperArms[index].zRot = UPPER_ARM_PITCH + fold * 0.85F;
			forearms[index].zRot = FOREARM_PITCH - fold * 0.62F;
			feet[index].zRot = FOOT_PITCH + fold * 0.55F;
			clawRoots[index].xScale = presence;
			clawRoots[index].yScale = presence;
			clawRoots[index].zScale = presence;
			clawRoots[index].visible = presence > 0.02F;
			// The gold seams destabilise first and are gone well before the arms they run through.
			float seam = Math.clamp(1.0F - age / StabilityAnchorGeometry.COLLAPSE_TETHER_END, 0.0F, 1.0F);
			seams[index].visible = seam > 0.05F;
			seams[index].xScale = seams[index].yScale = seams[index].zScale =
					seam * (1.0F + Mth.sin(age * 2.2F + index) * 0.28F);
		}
		torso.xScale = torso.zScale = presence;
		torso.yScale = presence;
		emitter.xScale = emitter.yScale = emitter.zScale = presence;
	}
}
