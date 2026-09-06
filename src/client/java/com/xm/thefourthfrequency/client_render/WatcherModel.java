package com.xm.thefourthfrequency.client_render;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * Native 2.9-block Watcher model. The eye and iris are geometry, never camera-facing quads.
 * The eyeball is a back-wide dome sunk into a real orbit: the brow and cheek ridges are the only
 * parts of the face allowed to sit proud of the face plane, so the eye reads as recessed bone
 * rather than an object glued onto a box.
 */
public final class WatcherModel extends EntityModel<WatcherRenderState> {
	private static final float DEG_TO_RAD = (float) (Math.PI / 180.0);
	private static final float FULL_TURN = (float) (Math.PI * 2.0);
	/** Far past a human neck. The body may face away while the head stays on the player. */
	private static final float MAX_NECK_YAW = 2.5F;
	private final HorrorDigits digits;
	private final ModelPart upperLid;
	private final ModelPart torso;
	private final ModelPart neck;
	private final ModelPart head;
	private final ModelPart leftArm;
	private final ModelPart leftForearm;
	private final ModelPart leftHand;
	private final ModelPart rightArm;
	private final ModelPart rightForearm;
	private final ModelPart rightHand;
	private final ModelPart leftLeg;
	private final ModelPart rightLeg;
	private final ModelPart eye;
	private final ModelPart iris;

	public WatcherModel(ModelPart root) {
		super(root);
		digits = new HorrorDigits(root);
		torso = root.getChild("torso");
		neck = torso.getChild("neck");
		head = neck.getChild("head");
		upperLid = head.getChild("upper_lid");
		eye = head.getChild("eye");
		iris = eye.getChild("iris");
		leftArm = torso.getChild("left_arm");
		leftForearm = leftArm.getChild("forearm");
		leftHand = leftForearm.getChild("hand");
		rightArm = torso.getChild("right_arm");
		rightForearm = rightArm.getChild("forearm");
		rightHand = rightForearm.getChild("hand");
		leftLeg = root.getChild("left_leg");
		rightLeg = root.getChild("right_leg");
	}

	/**
	 * Uses a 128-unit virtual UV canvas sampled by the 256px runtime textures at 2x density.
	 * The matching exported guide is docs/art/watcher/watcher_uv_template.png. Every eye island
	 * stays inside u [80,120) and v [0,8) so the emissive mask can never leave the eye.
	 */
	public static LayerDefinition createBodyLayer() {
		return WorldInterfaceGeometry.loadEntity("watcher").layer();
	}

	public static LayerDefinition createAuthoringLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		float pelvisY = 4.0F;

		root.addOrReplaceChild("pelvis", CubeListBuilder.create().texOffs(75, 66)
				.addBox(-2.4F, -2.0F, -1.45F, 4.8F, 4.0F, 2.9F),
				PartPose.offsetAndRotation(0.0F, pelvisY, 0.0F, 0.02F, 0.0F, 0.0F));
		PartDefinition torso = root.addOrReplaceChild("torso", torsoGeometry(),
				PartPose.offsetAndRotation(0.0F, pelvisY, 0.0F, 0.10F, 0.0F, 0.0F));
		torso.addOrReplaceChild("chest_fascia", CubeListBuilder.create().texOffs(48, 66)
				.addBox(-2.35F, -13.0F, -2.10F, 4.7F, 5.4F, 0.34F), PartPose.ZERO);
		torso.addOrReplaceChild("spine", spineGeometry(), PartPose.ZERO);
		torso.addOrReplaceChild("left_scapula", CubeListBuilder.create().texOffs(0, 66)
				.addBox(-2.7F, -13.2F, 1.77F, 2.4F, 5.4F, 0.52F),
				PartPose.rotation(-0.04F, -0.05F, -0.16F));
		torso.addOrReplaceChild("right_scapula", CubeListBuilder.create().texOffs(6, 66)
				.addBox(0.3F, -13.2F, 1.77F, 2.4F, 5.4F, 0.52F),
				PartPose.rotation(-0.04F, 0.05F, 0.16F));

		PartDefinition neck = torso.addOrReplaceChild("neck", CubeListBuilder.create().texOffs(51, 0)
				.addBox(-1.1F, -5.8F, -1.1F, 2.2F, 5.8F, 2.2F),
				PartPose.offsetAndRotation(0.0F, -14.0F, -0.25F, -0.045F, 0.0F, 0.0F));
		// Narrower than the chest and deeper than it is wide: a skull in profile, not a bobblehead.
		PartDefinition head = neck.addOrReplaceChild("head", CubeListBuilder.create().texOffs(60, 0)
				.addBox(-2.6F, -6.6F, -2.0F, 5.2F, 6.6F, 4.4F),
				PartPose.offsetAndRotation(0.0F, -5.8F, -0.20F, 0.045F, 0.0F, 0.0F));
		addOrbit(head);
		// Sunk 0.45 behind the face plane so the brow above it can overhang the whole assembly.
		// It still spans ~75% of the face: recession, not shrinkage, is what stops a single huge
		// eye from reading as a cartoon, and a small one just reads as an indicator lamp.
		PartDefinition eye = head.addOrReplaceChild("eye", CubeListBuilder.create()
				.texOffs(80, 0).addBox(-1.95F, -1.95F, 0.00F, 3.90F, 3.90F, 0.60F)
				.texOffs(90, 0).addBox(-1.65F, -1.65F, -0.46F, 3.30F, 3.30F, 0.48F),
				PartPose.offset(0.0F, -2.15F, -1.55F));
		// The dome's front cap is the iris itself, built as a square annulus so the pupil can sit
		// 0.26 units back inside it and be shadowed by the overhanging rim.
		PartDefinition iris = eye.addOrReplaceChild("iris", irisGeometry(), PartPose.ZERO);
		iris.addOrReplaceChild("pupil", CubeListBuilder.create().texOffs(108, 0)
				.addBox(-0.87F, -0.87F, -0.52F, 1.74F, 1.74F, 0.24F), PartPose.ZERO);

		addArm(torso, true);
		addArm(torso, false);
		addLeg(root, true, pelvisY);
		addLeg(root, false, pelvisY);
		return LayerDefinition.create(mesh, 128, 128);
	}

	/** Ribcage tapering into a starved waist, plus two hard acromion knobs at the shoulder points. */
	private static CubeListBuilder torsoGeometry() {
		return CubeListBuilder.create()
				.texOffs(0, 0).addBox(-3.1F, -14.0F, -1.85F, 6.2F, 6.4F, 3.7F)
				.texOffs(20, 0).addBox(-2.5F, -7.6F, -1.55F, 5.0F, 3.6F, 3.1F)
				.texOffs(37, 0).addBox(-2.1F, -4.0F, -1.35F, 4.2F, 4.0F, 2.7F)
				.texOffs(60, 66).addBox(-3.7F, -14.15F, -1.0F, 1.15F, 1.15F, 2.0F)
				.texOffs(67, 66).addBox(2.55F, -14.15F, -1.0F, 1.15F, 1.15F, 2.0F);
	}

	/**
	 * Brow shelf and cheek columns; the only face geometry allowed proud of the face plane. These
	 * are charred hide stretched over bone, not exposed bone, so they share the skin material and
	 * let the per-face shading alone carve the ridge: a pale brow reads as a headband.
	 */
	private static void addOrbit(PartDefinition head) {
		head.addOrReplaceChild("brow", CubeListBuilder.create().texOffs(112, 0)
				.addBox(-2.7F, -4.90F, -2.45F, 5.4F, 1.00F, 0.65F), PartPose.ZERO);
		head.addOrReplaceChild("left_cheek", CubeListBuilder.create().texOffs(112, 2)
				.addBox(2.05F, -4.15F, -2.35F, 0.65F, 3.90F, 0.5F), PartPose.ZERO);
		head.addOrReplaceChild("right_cheek", CubeListBuilder.create().texOffs(115, 2)
				.addBox(-2.70F, -4.15F, -2.35F, 0.65F, 3.90F, 0.5F), PartPose.ZERO);
		head.addOrReplaceChild("socket", CubeListBuilder.create().texOffs(118, 2)
				.addBox(-2.15F, -4.15F, -1.75F, 4.30F, 3.90F, 0.35F), PartPose.ZERO);
	}

	/**
	 * The horizontal bars stop short of the vertical ones so the four corners stay open. A closed
	 * ring is a rectangle, and a lit rectangle in the dark announces the cuboid; leaving the
	 * corners to fall back to the sclera turns the emissive mass into an octagonal aperture.
	 */
	private static CubeListBuilder irisGeometry() {
		return CubeListBuilder.create()
				.texOffs(98, 0).addBox(-1.05F, -1.40F, -0.78F, 2.10F, 0.55F, 0.38F)
				.texOffs(98, 1).addBox(-1.05F, 0.85F, -0.78F, 2.10F, 0.55F, 0.38F)
				.texOffs(105, 0).addBox(-1.40F, -0.85F, -0.78F, 0.55F, 1.70F, 0.38F)
				.texOffs(105, 3).addBox(0.85F, -0.85F, -0.78F, 0.55F, 1.70F, 0.38F);
	}

	private static CubeListBuilder spineGeometry() {
		CubeListBuilder spine = CubeListBuilder.create();
		for (int index = 0; index < 9; index++) {
			float width = index == 2 || index == 3 ? 1.20F : 0.92F;
			// Rooted at 1.30 so the column stays anchored inside the narrowing waist instead of
			// floating off the back once the torso tapers.
			spine.texOffs(22 + (index % 3) * 5, 66 + (index / 3) * 4)
					.addBox(-width * 0.5F, -13.1F + index * 1.42F, 1.30F,
							width, 0.86F, 0.82F);
		}
		return spine;
	}

	private static void addArm(PartDefinition torso, boolean left) {
		float side = left ? 1.0F : -1.0F;
		int upperU = left ? 0 : 28;
		int foreU = left ? 7 : 35;
		int handU = left ? 13 : 41;
		int fingerU = left ? 19 : 47;
		PartDefinition upper = torso.addOrReplaceChild(left ? "left_arm" : "right_arm",
				CubeListBuilder.create().texOffs(upperU, 24).mirror(!left)
						.addBox(-0.75F, 0.0F, -0.80F, 1.50F, 12.5F, 1.60F),
				PartPose.offsetAndRotation(side * 2.95F, -12.9F, -0.15F,
						0.055F, 0.0F, -side * 0.055F));
		PartDefinition forearm = upper.addOrReplaceChild("forearm",
				CubeListBuilder.create().texOffs(foreU, 24).mirror(!left)
						.addBox(-0.63F, 0.0F, -0.68F, 1.26F, 13.0F, 1.36F),
				PartPose.offsetAndRotation(0.0F, 12.5F, 0.0F, -0.045F, 0.0F, side * 0.025F));
		// Four long staggered fingers: at silhouette range the finger fan is the strongest
		// non-human cue the model has, so it is worth more atlas space than the vertebrae.
		forearm.addOrReplaceChild("hand", CubeListBuilder.create().texOffs(handU, 24).mirror(!left)
				.addBox(-0.72F, 0.0F, -0.76F, 1.44F, 3.55F, 1.52F)
				.texOffs(fingerU, 24).addBox(-0.70F, 2.65F, -0.62F, 0.26F, 2.50F, 0.34F)
				.texOffs(fingerU + 2, 24).addBox(-0.35F, 2.65F, -0.68F, 0.26F, 2.90F, 0.34F)
				.texOffs(fingerU + 4, 24).addBox(0.00F, 2.65F, -0.66F, 0.26F, 2.30F, 0.34F)
				.texOffs(fingerU + 6, 24).addBox(0.35F, 2.65F, -0.58F, 0.26F, 2.10F, 0.34F),
				PartPose.offsetAndRotation(0.0F, 13.0F, 0.0F, 0.04F, 0.0F, -side * 0.018F));
	}

	private static void addLeg(PartDefinition root, boolean left, float pelvisY) {
		float side = left ? 1.0F : -1.0F;
		int upperU = left ? 0 : 21;
		int lowerU = left ? 7 : 28;
		int footU = left ? 13 : 34;
		PartDefinition upper = root.addOrReplaceChild(left ? "left_leg" : "right_leg",
				CubeListBuilder.create().texOffs(upperU, 46).mirror(!left)
						.addBox(-0.78F, 0.0F, -0.84F, 1.56F, 9.5F, 1.68F),
				PartPose.offsetAndRotation(side * 1.62F, pelvisY, 0.10F, 0.025F, 0.0F, -side * 0.02F));
		PartDefinition lower = upper.addOrReplaceChild("lower_leg",
				CubeListBuilder.create().texOffs(lowerU, 46).mirror(!left)
						.addBox(-0.67F, 0.0F, -0.72F, 1.34F, 10.5F, 1.44F),
				PartPose.offsetAndRotation(0.0F, 9.5F, 0.0F, 0.06F, 0.0F, 0.0F));
		lower.addOrReplaceChild("foot", CubeListBuilder.create().texOffs(footU, 46).mirror(!left)
				.addBox(-0.75F, -0.80F, -1.60F, 1.50F, 0.80F, 2.15F),
				PartPose.offsetAndRotation(0.0F, 10.5F, 0.15F, -0.06F, 0.0F, 0.0F));
	}

	@Override
	public void setupAnim(WatcherRenderState state) {
		super.setupAnim(state);
		float slowWave = Mth.sin(state.ageInTicks * 0.035F);
		float counterWave = Mth.sin(state.ageInTicks * 0.021F + 1.7F);
		torso.xRot += 0.018F + slowWave * 0.012F;
		torso.zRot += slowWave * 0.018F + counterWave * 0.009F;
		neck.xRot -= slowWave * 0.010F;

		float twitch = Mth.sin(state.ageInTicks * 0.071F + 0.9F) * 0.017F
				+ Mth.sin(state.ageInTicks * 0.019F) * 0.011F;
		// state.yRot is already the head yaw net of the body, so a body facing away shows up here
		// as a large value. Spreading it across neck and skull keeps the twist anatomical, and the
		// small roll term makes an extreme turn tip the head over the way a strained neck would.
		float netYaw = Mth.clamp(state.yRot * DEG_TO_RAD, -MAX_NECK_YAW, MAX_NECK_YAW);
		float netPitch = Mth.clamp(state.xRot * DEG_TO_RAD, -0.42F, 0.42F);
		neck.yRot += netYaw * 0.35F;
		neck.xRot += netPitch * 0.30F;
		head.yRot += netYaw * 0.65F + twitch;
		head.xRot += netPitch * 0.70F + Mth.sin(state.ageInTicks * 0.053F + 2.1F) * 0.012F;
		head.zRot += twitch * 0.62F + netYaw * 0.06F;

		leftArm.xRot += 0.035F + slowWave * 0.018F;
		rightArm.xRot += 0.035F - slowWave * 0.018F;
		leftForearm.xRot += 0.045F + counterWave * 0.012F;
		rightForearm.xRot += 0.045F - counterWave * 0.012F;
		leftHand.zRot += slowWave * 0.010F;
		rightHand.zRot -= slowWave * 0.010F;
		leftLeg.xRot += counterWave * 0.004F;
		rightLeg.xRot -= counterWave * 0.004F;

		float irisPhase = state.ageInTicks * FULL_TURN / 120.0F;
		float irisScale = 1.0F + Mth.sin(irisPhase) * 0.03F;
		iris.xScale = irisScale;
		iris.yScale = irisScale;
		iris.zScale = 1.0F;
		eye.xScale = eye.yScale = eye.zScale = 1.0F;
		digits.animate(state.ageInTicks, state.gazeProgress * .25F);
		float browTension = state.gazeProgress * .08F + Mth.sin(state.ageInTicks * .025F) * .025F;
		upperLid.y += browTension;
		upperLid.xRot += browTension * .12F;
	}
}
