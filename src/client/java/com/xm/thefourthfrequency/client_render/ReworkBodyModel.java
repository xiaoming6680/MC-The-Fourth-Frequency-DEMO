package com.xm.thefourthfrequency.client_render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/** Three independently baked silhouettes sharing one animation and bone naming contract. */
public final class ReworkBodyModel extends EntityModel<ReworkBodyRenderState> {
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
	private final int stage;
	private final HorrorDigits digits;
	private final ModelPart torso;
	private final ModelPart neck;
	private final ModelPart head;
	private final ModelPart jaw;
	private final ModelPart innerJaw;
	private final ModelPart leftArm;
	private final ModelPart leftForearm;
	private final ModelPart leftClaw;
	private final ModelPart rightArm;
	private final ModelPart rightForearm;
	private final ModelPart rightClaw;
	private final ModelPart leftLeg;
	private final ModelPart leftLowerLeg;
	private final ModelPart rightLeg;
	private final ModelPart rightLowerLeg;
	private final List<BackArm> backArms;

	public ReworkBodyModel(ModelPart root, int stage) {
		super(root);
		digits = new HorrorDigits(root);
		this.stage = Math.clamp(stage, 1, 3) * 2 - 1;
		torso = root.getChild("torso");
		neck = torso.getChild("neck");
		head = neck.getChild("head");
		jaw = head.getChild("jaw");
		innerJaw = head.getChild("inner_jaw");
		leftArm = torso.getChild("left_arm");
		leftForearm = leftArm.getChild("forearm");
		leftClaw = leftForearm.getChild("claw");
		rightArm = torso.getChild("right_arm");
		rightForearm = rightArm.getChild("forearm");
		rightClaw = rightForearm.getChild("claw");
		leftLeg = root.getChild("left_leg");
		leftLowerLeg = leftLeg.getChild("lower_leg");
		rightLeg = root.getChild("right_leg");
		rightLowerLeg = rightLeg.getChild("lower_leg");
		List<BackArm> found = new ArrayList<>();
		// Must stay in lockstep with addBackArms(), which builds exactly backArmCount() parts.
		// These two counts used to be written independently (stage * 2 here against the profile
		// field there); they happened to agree only because the old table set backArmCount to
		// stage * 2, so any change to that column would have crashed here on a missing child.
		for (int index = 0; index < FormProfile.forStage(stage).backArmCount(); index++) {
			ModelPart upper = torso.getChild("back_arm_" + index);
			ModelPart forearm = upper.getChild("forearm");
			found.add(new BackArm(index, index % 2 == 0 ? 1.0F : -1.0F,
					upper, forearm, forearm.getChild("claw")));
		}
		backArms = List.copyOf(found);
	}

	public static LayerDefinition createStage1Layer() { return WorldInterfaceGeometry.loadEntity("rework_body_stage_1").layer(); }
	public static LayerDefinition createStage2Layer() { return WorldInterfaceGeometry.loadEntity("rework_body_stage_2").layer(); }
	public static LayerDefinition createStage3Layer() { return WorldInterfaceGeometry.loadEntity("rework_body_stage_3").layer(); }

	public static LayerDefinition createAuthoringLayer(int stage) { return createLayer(FormProfile.forStage(stage)); }

	private static LayerDefinition createLayer(FormProfile profile) {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		float pelvisY = 24.0F - profile.legLength();
		float halfTorso = profile.torsoWidth() * 0.5F;
		float halfDepth = profile.torsoDepth() * 0.5F;
		float limb = profile.limbThickness();

		root.addOrReplaceChild("pelvis", CubeListBuilder.create().texOffs(28, 0)
				.addBox(-halfTorso * 0.72F, -2.0F, -halfDepth * 0.85F,
						halfTorso * 1.44F, 4.0F, halfDepth * 1.70F), PartPose.offset(0.0F, pelvisY, 0.0F));
		PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create().texOffs(0, 0)
				.addBox(-halfTorso, -profile.torsoHeight(), -halfDepth,
						profile.torsoWidth(), profile.torsoHeight(), profile.torsoDepth()),
				PartPose.offsetAndRotation(0.0F, pelvisY, 0.0F, 0.0F, 0.0F, profile.torsoTwist()));

		PartDefinition ribs = torso.addOrReplaceChild("ribs", ribGeometry(profile), PartPose.ZERO);
		ribs.addOrReplaceChild("sternum", profile.stage() <= 3
				? CubeListBuilder.create().texOffs(100, 64).addBox(-0.45F, -profile.torsoHeight() + 1.5F,
						-halfDepth - 0.45F, 0.9F, profile.torsoHeight() * 0.62F, 0.9F)
				: CubeListBuilder.create(), PartPose.ZERO);
		torso.addOrReplaceChild("spine", spineGeometry(profile), PartPose.ZERO);

		PartDefinition neck = torso.addOrReplaceChild("neck", CubeListBuilder.create().texOffs(56, 0)
				.addBox(-profile.neckWidth() * 0.5F, -profile.neckHeight(), -profile.neckDepth() * 0.5F,
						profile.neckWidth(), profile.neckHeight(), profile.neckDepth()),
				PartPose.offsetAndRotation(0.0F, -profile.torsoHeight(), -0.15F,
						0.04F + (6 - profile.stage()) * 0.008F, 0.0F, -profile.torsoTwist() * 0.55F));
		// The head sits crooked on the early necks and straightens as the guess improves; by the
		// last form it is centred, which is precisely what makes it read as wrong.
		boolean crookedHead = profile.stage() <= 2;
		PartDefinition head = neck.addOrReplaceChild("head", CubeListBuilder.create().texOffs(70, 0)
				.addBox(-profile.headWidth() * 0.5F, -profile.headHeight(), -profile.headDepth() * 0.56F,
						profile.headWidth(), profile.headHeight(), profile.headDepth()),
				PartPose.offsetAndRotation(crookedHead ? 0.35F : 0.0F, -profile.neckHeight(), -0.25F,
						0.0F, 0.0F, crookedHead ? -0.045F * (4 - profile.stage()) : 0.0F));
		boolean splitFace = profile.stage() == 1;
		head.addOrReplaceChild("face_left", facePlate(profile, true), PartPose.offsetAndRotation(
				splitFace ? -0.55F : 0.0F, 0.0F, 0.0F, 0.0F,
				splitFace ? -0.14F : 0.0F, splitFace ? -0.08F : 0.0F));
		head.addOrReplaceChild("face_right", facePlate(profile, false), PartPose.offsetAndRotation(
				splitFace ? 0.55F : 0.0F, 0.0F, 0.0F, 0.0F,
				splitFace ? 0.14F : 0.0F, splitFace ? 0.08F : 0.0F));
		PartDefinition jaw = head.addOrReplaceChild("jaw", CubeListBuilder.create().texOffs(96, 0)
				.addBox(-profile.headWidth() * 0.47F, -0.15F, -profile.headDepth() * 0.62F,
						profile.headWidth() * 0.94F, profile.jawHeight(), profile.headDepth() * 0.82F),
				PartPose.offset(crookedHead ? 0.45F : 0.0F, -profile.jawHeight() - 0.35F, -0.15F));
		jaw.addOrReplaceChild("teeth", teethGeometry(profile, false), PartPose.ZERO);
		PartDefinition innerJaw = head.addOrReplaceChild("inner_jaw",
				profile.stage() <= 2 ? CubeListBuilder.create().texOffs(96, 12)
						.addBox(-profile.headWidth() * 0.33F, 0.0F, -profile.headDepth() * 0.68F,
								profile.headWidth() * 0.66F, profile.jawHeight() * 0.72F,
								profile.headDepth() * 0.55F) : CubeListBuilder.create(),
				PartPose.offset(0.0F, -profile.jawHeight() - 1.2F, -0.3F));
		innerJaw.addOrReplaceChild("teeth", teethGeometry(profile, true), PartPose.ZERO);

		addNormalArm(torso, profile, true);
		addNormalArm(torso, profile, false);
		addLeg(root, profile, true, pelvisY);
		addLeg(root, profile, false, pelvisY);
		addBackArms(torso, profile, halfTorso, halfDepth);
		return LayerDefinition.create(mesh, 128, 128);
	}

	/**
	 * Exposed ribs belong to the early forms: at that point the body is still a frame with nothing
	 * convincingly wrapped around it. They thin out and vanish as the silhouette learns to close
	 * over itself, which is why this reads as "stage 4 and 5 have none" rather than the reverse.
	 */
	private static CubeListBuilder ribGeometry(FormProfile profile) {
		CubeListBuilder ribs = CubeListBuilder.create();
		if (profile.stage() > 3) return ribs;
		float depth = profile.torsoDepth() * 0.5F;
		int count = 6 - profile.stage();
		for (int index = 0; index < count; index++) {
			float width = profile.torsoWidth() + (profile.stage() - 1) * 0.55F - index * 0.18F;
			float y = -profile.torsoHeight() + 2.2F + index * 1.65F;
			ribs.texOffs(64 + (index % 2) * 16, 64 + index * 4)
					.addBox(-width * 0.5F, y, -depth - 0.30F, width, 0.55F, profile.torsoDepth() + 0.6F);
		}
		return ribs;
	}

	private static CubeListBuilder spineGeometry(FormProfile profile) {
		CubeListBuilder spine = CubeListBuilder.create();
		if (profile.stage() > 3) return spine;
		int vertebrae = 9 - profile.stage() * 2;
		float back = profile.torsoDepth() * 0.5F + 0.25F;
		for (int index = 0; index < vertebrae; index++) {
			float y = -profile.torsoHeight() + 1.0F + index * (profile.torsoHeight() - 2.0F) / (vertebrae - 1);
			float size = profile.stage() == 5 ? 1.25F : 0.9F + index % 2 * 0.12F;
			spine.texOffs(100 + (index % 4) * 5, 64 + (index / 4) * 6)
					.addBox(-size * 0.5F, y, back, size, 0.8F, size);
		}
		return spine;
	}

	/**
	 * Two mismatched plates stapled where a face should go - an early, unconvincing attempt at one.
	 * Stage 1 splits them apart entirely; by stage 3 they are gone and the head is simply a head.
	 */
	private static CubeListBuilder facePlate(FormProfile profile, boolean left) {
		if (profile.stage() > 2) return CubeListBuilder.create();
		float width = profile.headWidth() * (profile.stage() == 1 ? 0.42F : 0.86F);
		float x = profile.stage() == 1 ? (left ? -profile.headWidth() * 0.47F : 0.05F)
				: -profile.headWidth() * 0.43F;
		return CubeListBuilder.create().texOffs(left ? 70 : 96, 20)
				.addBox(x, -profile.headHeight() * 0.86F, -profile.headDepth() * 0.60F - 0.22F,
						width, profile.headHeight() * 0.68F, 0.35F);
	}

	private static CubeListBuilder teethGeometry(FormProfile profile, boolean inner) {
		CubeListBuilder teeth = CubeListBuilder.create();
		// Teeth recede along with the jaw: the late forms keep a mouth that could pass for a
		// player's, and the second row of them only exists while the first guess is still showing.
		int count = inner ? (profile.stage() <= 2 ? 5 : 0) : Math.max(0, 8 - profile.stage() * 2);
		float spread = profile.headWidth() * (inner ? 0.46F : 0.72F);
		for (int index = 0; index < count; index++) {
			float x = count == 1 ? 0.0F : -spread * 0.5F + spread * index / (count - 1);
			float length = (inner ? 0.9F : 1.15F) + (index % 2) * 0.35F;
			teeth.texOffs(inner ? 116 : 108, 24)
					.addBox(x - 0.16F, 0.0F, -profile.headDepth() * 0.44F - 0.16F,
							0.32F, length, 0.40F);
		}
		return teeth;
	}

	private static void addNormalArm(PartDefinition torso, FormProfile profile, boolean left) {
		float side = left ? 1.0F : -1.0F;
		float limb = profile.limbThickness();
		int baseU = left ? 0 : 20;
		PartDefinition upper = torso.addOrReplaceChild(left ? "left_arm" : "right_arm",
				CubeListBuilder.create().texOffs(baseU, 32).mirror(!left)
						.addBox(-limb * 0.5F, 0.0F, -limb * 0.5F,
								limb, profile.armUpperLength(), limb),
				PartPose.offsetAndRotation(side * (profile.torsoWidth() * 0.5F + limb * 0.35F),
						-profile.torsoHeight() + 2.0F, -0.15F, 0.10F, 0.0F, -side * 0.07F));
		PartDefinition forearm = upper.addOrReplaceChild("forearm",
				CubeListBuilder.create().texOffs(baseU + 8, 32).mirror(!left)
						.addBox(-limb * 0.45F, 0.0F, -limb * 0.45F,
								limb * 0.90F, profile.armForeLength(), limb * 0.90F),
				PartPose.offsetAndRotation(0.0F, profile.armUpperLength(), 0.0F, -0.10F, 0.0F, side * 0.035F));
		forearm.addOrReplaceChild("claw", clawGeometry(baseU + 16, 32, profile.clawLength(), limb, !left),
				PartPose.offset(0.0F, profile.armForeLength(), 0.0F));
	}

	private static void addLeg(PartDefinition root, FormProfile profile, boolean left, float pelvisY) {
		float side = left ? 1.0F : -1.0F;
		float width = Math.max(1.35F, profile.limbThickness() * 0.92F);
		float upperLength = profile.legLength() * 0.47F;
		float lowerLength = profile.legLength() - upperLength;
		int baseU = left ? 0 : 20;
		PartDefinition upper = root.addOrReplaceChild(left ? "left_leg" : "right_leg",
				CubeListBuilder.create().texOffs(baseU, 56).mirror(!left)
						.addBox(-width * 0.5F, 0.0F, -width * 0.5F, width, upperLength, width),
				PartPose.offset(side * profile.torsoWidth() * 0.20F, pelvisY, 0.0F));
		PartDefinition lower = upper.addOrReplaceChild("lower_leg",
				CubeListBuilder.create().texOffs(baseU + 8, 56).mirror(!left)
						.addBox(-width * 0.44F, 0.0F, -width * 0.44F,
								width * 0.88F, lowerLength, width * 0.88F),
				PartPose.offset(0.0F, upperLength, 0.0F));
		lower.addOrReplaceChild("foot", CubeListBuilder.create().texOffs(baseU + 16, 56).mirror(!left)
				.addBox(-width * 0.55F, -0.8F, -width * 0.85F, width * 1.10F, 0.8F, width * 1.55F),
				PartPose.offset(0.0F, lowerLength, -0.15F));
	}

	private static void addBackArms(PartDefinition torso, FormProfile profile, float halfTorso, float halfDepth) {
		int pairCount = profile.backArmCount() / 2;
		for (int index = 0; index < profile.backArmCount(); index++) {
			float side = index % 2 == 0 ? 1.0F : -1.0F;
			int pair = index / 2;
			float row = pairCount <= 1 ? 0.5F : pair / (float) (pairCount - 1);
			float attachY = -profile.torsoHeight() + 2.6F + row * (profile.torsoHeight() - 5.2F);
			float upperLength = profile.backUpperLength() * (1.0F + ((pair + profile.stage()) % 3 - 1) * 0.065F);
			float foreLength = profile.backForeLength() * (1.0F + ((pair + 1) % 3 - 1) * 0.075F);
			float thickness = Math.max(1.05F, profile.limbThickness() * 0.78F - row * 0.12F);
			float spread = 1.20F - row * 0.18F + profile.stage() * 0.018F;
			float yaw = side * (0.18F + pair * 0.075F) + (profile.stage() >= 3 && pair % 2 == 1 ? 0.16F : 0.0F);
			PartDefinition upper = torso.addOrReplaceChild("back_arm_" + index,
					CubeListBuilder.create().texOffs(0, 84).mirror(side < 0.0F)
							.addBox(-thickness * 0.5F, 0.0F, -thickness * 0.5F,
									thickness, upperLength, thickness),
					PartPose.offsetAndRotation(side * (halfTorso - 0.35F), attachY, halfDepth - 0.15F,
							-0.12F + row * 0.16F, yaw, -side * spread));
			PartDefinition forearm = upper.addOrReplaceChild("forearm",
					CubeListBuilder.create().texOffs(20, 84).mirror(side < 0.0F)
							.addBox(-thickness * 0.44F, 0.0F, -thickness * 0.44F,
									thickness * 0.88F, foreLength, thickness * 0.88F),
					PartPose.offsetAndRotation(0.0F, upperLength, 0.0F,
							-0.15F + row * 0.12F, side * 0.08F, -side * (0.52F + pair * 0.035F)));
			forearm.addOrReplaceChild("claw", clawGeometry(40, 84,
					profile.clawLength() * 0.82F, thickness, side < 0.0F),
					PartPose.offsetAndRotation(0.0F, foreLength, 0.0F, 0.12F, 0.0F, side * 0.10F));
		}
	}

	private static CubeListBuilder clawGeometry(int u, int v, float length, float thickness, boolean mirror) {
		return CubeListBuilder.create().texOffs(u, v).mirror(mirror)
				.addBox(-thickness * 0.62F, 0.0F, -thickness * 0.50F,
						thickness * 1.24F, Math.max(1.2F, length * 0.38F), thickness)
				.texOffs(u + 8, v).addBox(-thickness * 0.58F, length * 0.28F, -thickness * 0.35F,
						0.30F, length * 0.72F, 0.34F)
				.texOffs(u + 11, v).addBox(thickness * 0.28F, length * 0.24F, -thickness * 0.35F,
						0.30F, length * 0.76F, 0.34F);
	}

	@Override
	public void setupAnim(ReworkBodyRenderState state) {
		super.setupAnim(state);
		float walkPhase = state.walkAnimationPos * com.xm.thefourthfrequency.entity.HorrorMotion.REWORK_STEP_RATE;
		float walkStrength = Math.min(0.72F, state.walkAnimationSpeed * 1.45F);
		float forwardLean = 0.15F + walkStrength * 0.09F + (stage - 1) * 0.012F;
		torso.xRot += forwardLean;
		torso.zRot += Mth.sin(state.ageInTicks * 0.037F + stage) * (0.012F + stage * 0.003F);
		neck.xRot -= forwardLean * 0.58F;

		float twitch = Mth.sin(state.ageInTicks * 0.73F + stage * 1.9F) * 0.018F
				+ Mth.sin(state.ageInTicks * 0.173F) * 0.014F;
		head.yRot += Mth.clamp(state.yRot * DEG_TO_RAD, -0.95F, 0.95F) + twitch;
		head.xRot += Mth.clamp(state.xRot * DEG_TO_RAD, -0.72F, 0.72F)
				+ Mth.sin(state.ageInTicks * 0.91F + stage) * 0.012F;
		head.zRot += twitch * 0.72F;
		jaw.xRot += 0.10F + (Mth.sin(com.xm.thefourthfrequency.entity.HorrorMotion.reworkBreathPhase(
				state.ageInTicks, (stage + 1) / 2)) + 1.0F) * 0.055F
				+ (stage >= 3 ? 0.08F : 0.0F);
		innerJaw.xRot += stage >= 4 ? 0.16F + Mth.sin(state.ageInTicks * 0.11F) * 0.035F : 0.0F;

		leftLeg.xRot += Mth.cos(walkPhase) * walkStrength;
		rightLeg.xRot += Mth.cos(walkPhase + Mth.PI) * walkStrength;
		leftLowerLeg.xRot += Math.max(0.0F, -Mth.cos(walkPhase)) * walkStrength * 0.42F;
		rightLowerLeg.xRot += Math.max(0.0F, -Mth.cos(walkPhase + Mth.PI)) * walkStrength * 0.42F;

		leftArm.xRot += 0.18F - Mth.cos(walkPhase) * walkStrength * 0.28F;
		rightArm.xRot += 0.18F - Mth.cos(walkPhase + Mth.PI) * walkStrength * 0.28F;
		leftArm.zRot -= 0.035F + stage * 0.006F;
		rightArm.zRot += 0.035F + stage * 0.006F;
		leftForearm.xRot += 0.10F + Mth.sin(state.ageInTicks * 0.045F) * 0.035F;
		rightForearm.xRot += 0.10F + Mth.sin(state.ageInTicks * 0.045F + Mth.PI) * 0.035F;
		leftClaw.xRot += 0.10F + Mth.sin(state.ageInTicks * 0.083F) * 0.05F;
		rightClaw.xRot += 0.10F + Mth.sin(state.ageInTicks * 0.083F + 2.1F) * 0.05F;

		animateBackArms(state, 1.0F);
		applyMorphPose(state);
		digits.animate(state.ageInTicks, walkStrength);
	}

	private void animateBackArms(ReworkBodyRenderState state, float amount) {
		for (BackArm arm : backArms) {
			float phase = state.ageInTicks * (0.043F + arm.index() * 0.0013F) + arm.index() * 1.371F;
			arm.upper().xRot += Mth.sin(phase) * (0.12F + stage * 0.014F) * amount;
			arm.upper().yRot += Mth.cos(phase * 0.83F + 0.7F) * 0.16F * amount;
			arm.upper().zRot += Mth.sin(phase * 0.61F + 1.4F) * 0.11F * amount;
			arm.forearm().xRot += Mth.cos(phase * 0.77F + 0.9F) * 0.24F * amount;
			arm.forearm().zRot += Mth.sin(phase * 0.69F) * 0.16F * amount;
			arm.claw().xRot += 0.08F + Mth.sin(phase * 1.21F + 0.35F) * 0.08F * amount;
			arm.claw().yRot += Mth.cos(phase * 0.94F) * 0.06F * amount;
		}
	}

	private void applyMorphPose(ReworkBodyRenderState state) {
		if (state.morphTicks <= 0) return;
		if (state.morphTicks > 20) {
			float contraction = smoothstep((40.0F - state.morphTicks) / 20.0F);
			float spasm = Mth.sin(state.ageInTicks * 1.86F) * (0.13F - contraction * 0.055F);
			torso.yScale = 1.0F - contraction * 0.22F;
			torso.xScale = torso.zScale = 1.0F + contraction * 0.08F;
			torso.y += contraction * 2.7F;
			torso.zRot += spasm * 0.42F;
			neck.xRot += spasm;
			head.zRot += spasm * 0.75F;
			jaw.xRot += Math.abs(spasm) * 1.35F;
			leftArm.zRot += contraction * 0.62F;
			rightArm.zRot -= contraction * 0.62F;
			leftLeg.zRot -= contraction * 0.10F;
			rightLeg.zRot += contraction * 0.10F;
			for (BackArm arm : backArms) {
				float scale = 1.0F - contraction * 0.40F;
				arm.upper().xScale = arm.upper().yScale = arm.upper().zScale = scale;
				arm.upper().zRot += arm.side() * contraction * 0.32F + spasm * 0.25F;
				arm.forearm().xRot -= contraction * 0.46F;
			}
			return;
		}

		float unfold = smoothstep((20.0F - state.morphTicks) / 20.0F);
		float collapsed = 1.0F - unfold;
		torso.yScale = 0.78F + unfold * 0.22F;
		torso.xScale = torso.zScale = 1.08F - unfold * 0.08F;
		torso.y += collapsed * 2.7F;
		head.zRot += Mth.sin(state.ageInTicks * 1.32F) * collapsed * 0.10F;
		jaw.xRot += collapsed * 0.32F;
		leftArm.zRot += collapsed * 0.54F;
		rightArm.zRot -= collapsed * 0.54F;
		for (BackArm arm : backArms) {
			float phase = state.ageInTicks * (0.043F + arm.index() * 0.0013F) + arm.index() * 1.371F;
			float scale = 0.18F + unfold * 0.82F;
			arm.upper().xScale = arm.upper().yScale = arm.upper().zScale = scale;
			PartPose initialUpper = arm.upper().getInitialPose();
			PartPose initialForearm = arm.forearm().getInitialPose();
			arm.upper().xRot = Mth.lerp(unfold, -0.42F, initialUpper.xRot()
					+ Mth.sin(phase) * (0.035F + stage * 0.006F));
			arm.upper().yRot = Mth.lerp(unfold, 0.0F, initialUpper.yRot()
					+ Mth.cos(phase * 0.83F + 0.7F) * 0.055F);
			arm.upper().zRot = Mth.lerp(unfold, -arm.side() * 0.16F, initialUpper.zRot()
					+ Mth.sin(phase * 0.61F + 1.4F) * 0.045F);
			arm.forearm().xRot = Mth.lerp(unfold, -0.82F, initialForearm.xRot()
					+ Mth.cos(phase * 0.77F + 0.9F) * 0.075F);
			arm.forearm().zRot = Mth.lerp(unfold, arm.side() * 0.22F, initialForearm.zRot());
		}
	}

	private static float smoothstep(float value) {
		float clamped = Mth.clamp(value, 0.0F, 1.0F);
		return clamped * clamped * (3.0F - 2.0F * clamped);
	}

	private record BackArm(int index, float side, ModelPart upper, ModelPart forearm, ModelPart claw) { }

	/**
	 * The five silhouettes read as one thing learning to wear a body, so the progression converges
	 * on the vanilla player's proportions instead of growing into a larger monster.
	 *
	 * <p>Stage 1 is the worst guess at a human: stilt legs, a small head, thread-thin limbs and a
	 * surplus of arms, because nothing here yet knows how many a body is supposed to have. Each
	 * stage sheds the surplus and moves toward the player skeleton (legs 12, torso 12x8x4, head
	 * 8x8x8, arm 12 total, limbs 4 thick). Stage 5 lands close enough to pass at a glance but is
	 * deliberately never exact - a slightly long leg, a torso a shade too shallow, a residual
	 * twist - so the last form is uncanny rather than merely large. Horror moves from "obviously
	 * not a person" to "almost a person", which is also what the fiction asks for: the fifth form
	 * is the one that forges coordinates and text.</p>
	 */
	private record FormProfile(int stage, int backArmCount, float legLength, float torsoHeight,
			float neckHeight, float headHeight, float torsoWidth, float torsoDepth,
			float headWidth, float headDepth, float armUpperLength, float armForeLength,
			float backUpperLength, float backForeLength, float limbThickness, float torsoTwist) {
		static FormProfile forStage(int stage) {
			return switch (stage) {
				case 1 -> new FormProfile(1, 10, 21.0F, 9.0F, 5.2F, 5.0F,
						4.2F, 2.2F, 4.0F, 3.8F, 9.5F, 11.5F, 9.5F, 11.0F, 1.30F, 0.160F);
				case 2 -> new FormProfile(3, 6, 16.5F, 11.0F, 3.4F, 6.6F,
						6.3F, 3.2F, 6.2F, 6.0F, 7.6F, 8.8F, 7.5F, 9.0F, 2.50F, 0.080F);
				default -> new FormProfile(5, 2, 12.6F, 12.2F, 1.1F, 7.9F,
						7.8F, 4.3F, 7.9F, 7.7F, 6.1F, 6.2F, 6.5F, 7.5F, 3.80F, 0.015F);
			};
		}

		// These also converge: the neck all but disappears (the player has none), the jaw stops
		// jutting out, and the claws retract toward something closer to a hand.
		float neckWidth() { return 3.6F - stage * 0.28F; }
		float neckDepth() { return 3.3F - stage * 0.26F; }
		float jawHeight() { return 3.0F - stage * 0.35F; }
		float clawLength() { return 4.4F - stage * 0.55F; }
	}
}
