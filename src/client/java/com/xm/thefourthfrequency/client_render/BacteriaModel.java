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
 * A large arachnid: two body masses, eight jointed legs, and no face.
 *
 * <p>The silhouette is the whole design, and the one line it has to draw is the knee. Legs whose
 * middle joint rises <em>above</em> the body is the single feature that makes a shape read as a
 * spider rather than as a low quadruped, at any distance and in one frame - which is why the femur
 * angles up and the tibia comes back down past it, rather than the legs simply splaying outward.
 * Everything else here is in service of that outline holding up from behind, from the side, and
 * while it is moving.
 *
 * <p><b>No face, deliberately, and it costs nothing.</b> A spider is already frightening from its
 * gait and its proportions; adding eyes would give the player something to look at and therefore
 * something to reason about, and the front of this body is just where the legs are densest. It keeps
 * the property the earlier amorphous version was built for - there is nothing on it to read - while
 * giving up that version's problem, which was that a mass with no features also had no threat.
 *
 * <p>The gait runs on {@code walkAnimationPos}, the same distance-walked clock vanilla drives its
 * own limbs from, so the legs are tied to ground actually covered rather than to elapsed time: an
 * entity that has stopped stops moving its legs. Over the top of it every leg carries a slow
 * per-leg drift on a period that does not divide into the step cycle, so a spider standing still is
 * never quite still and never twice the same.
 */
public final class BacteriaModel extends EntityModel<BacteriaRenderState> {
	private static final int LEGS_PER_SIDE = 4;
	/**
	 * Femur and tibia, solved rather than eyeballed.
	 *
	 * <p>These four numbers are one system with two hard requirements: the knee has to clear the top
	 * of the body, and the foot has to reach the floor. Chosen by hand they miss - the first pass
	 * left the feet nine units in the air, which no amount of looking at the code would have caught
	 * because each constant was individually reasonable.
	 *
	 * <p>With the leg pivot 10.7 units above the feet: the femur rises {@code 8 * sin(0.945)} = 6.5
	 * to put the knee at 17.2, comfortably over the body's 13.5; the tibia then drops
	 * {@code 17.5 * sin(2.17 - 0.945)} = 16.5 to land the foot at 0.7. Horizontal reach comes out at
	 * 14.0 units, so the legs stand slightly wider than the 12.8-unit hitbox half-width - which is
	 * correct for a spider and is how vanilla's own is built.
	 */
	private static final float FEMUR_LENGTH = 8.0F;
	private static final float TIBIA_LENGTH = 17.5F;
	/** How far the femur lifts. Negative is upward for a limb built along +X. */
	private static final float FEMUR_LIFT = -0.945F;
	/** Relative bend at the knee. Larger than the lift, so the foot ends up below the body. */
	private static final float KNEE_BEND = 2.17F;
	/**
	 * Step cycle rate against distance walked.
	 *
	 * <p>Vanilla's own limb constant is 0.6662, tuned for a biped's stride: one full cycle per about
	 * 2.4 blocks of travel. On something this fast with eight short legs that reads as sliding - the
	 * feet are plainly not covering the ground the body is. Roughly doubled, so a cycle is about 1.1
	 * blocks and the patter matches the pace.
	 *
	 * <p>Tied to {@code walkAnimationPos} rather than to elapsed time, so this stays correct if the
	 * speed attribute moves again: the legs are counting ground, not seconds.
	 */
	private static final float STEP_RATE = 1.45F;
	/** Idle drift period, deliberately not a divisor of the step cycle. */
	private static final float DRIFT_PERIOD = 0.037F;

	private final ModelPart body;
	private final ModelPart abdomen;
	private final ModelPart[] legs = new ModelPart[LEGS_PER_SIDE * 2];
	private final ModelPart[] shins = new ModelPart[LEGS_PER_SIDE * 2];

	public BacteriaModel(ModelPart root) {
		super(root);
		body = root.getChild("body");
		abdomen = body.getChild("abdomen");
		for (int index = 0; index < legs.length; index++) {
			legs[index] = body.getChild(legName(index));
			shins[index] = legs[index].getChild("shin");
		}
	}

	private static String legName(int index) {
		return "leg_" + index;
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		// Slung low between the legs rather than perched on them. A body carried high reads as an
		// insect on stilts; a spider's mass hangs inside the span of its own limbs.
		PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
						.texOffs(0, 0).addBox(-4.0F, -4.0F, -6.5F, 8.0F, 6.5F, 8.0F),
				PartPose.offset(0.0F, 14.5F, 0.0F));
		// Bigger than the front and lifted slightly, which is the other half of the arachnid read:
		// two masses of unequal size, not one trunk.
		body.addOrReplaceChild("abdomen", CubeListBuilder.create()
						.texOffs(0, 16).addBox(-5.5F, -5.0F, 0.0F, 11.0F, 8.5F, 12.0F),
				PartPose.offsetAndRotation(0.0F, -1.4F, 1.0F, -0.18F, 0.0F, 0.0F));
		for (int index = 0; index < LEGS_PER_SIDE * 2; index++) addLeg(body, index);
		return LayerDefinition.create(mesh, 64, 64);
	}

	/**
	 * One leg, as a femur that rises and a tibia that comes back down.
	 *
	 * <p>Even index is the right side, odd the left, so the two sides are built by the same code and
	 * cannot drift apart. Fore-and-aft yaw fans the four pairs from pointing forward at the front to
	 * pointing back at the rear; a set of legs all square to the body reads as a table.
	 */
	private static void addLeg(PartDefinition body, int index) {
		boolean right = index % 2 == 0;
		int pair = index / 2;
		float side = right ? 1.0F : -1.0F;
		// Front pair reaches forward, rear pair reaches back, and the two middle pairs sit between.
		float yaw = (0.80F - pair * 0.52F) * side;
		float pivotZ = -4.2F + pair * 3.4F;
		int u = (pair % 2) * 22;
		int v = 40 + (index % 2) * 8;

		PartDefinition femur = body.addOrReplaceChild(legName(index), CubeListBuilder.create()
						.texOffs(u, v).addBox(right ? 0.0F : -FEMUR_LENGTH, -1.1F, -1.1F,
								FEMUR_LENGTH, 2.2F, 2.2F),
				PartPose.offsetAndRotation(side * 3.4F, -1.2F, pivotZ, 0.0F, yaw, FEMUR_LIFT * side));
		// Hung off the far end of the femur, so the knee is wherever the femur put it and the two
		// segments can never separate.
		femur.addOrReplaceChild("shin", CubeListBuilder.create()
						.texOffs(u, v + 4).addBox(right ? 0.0F : -TIBIA_LENGTH, -0.85F, -0.85F,
								TIBIA_LENGTH, 1.7F, 1.7F),
				PartPose.offsetAndRotation(side * FEMUR_LENGTH, 0.0F, 0.0F,
						0.0F, 0.0F, KNEE_BEND * side));
	}

	@Override
	public void setupAnim(BacteriaRenderState state) {
		super.setupAnim(state);
		float walk = state.walkAnimationPos;
		float amount = Math.min(state.walkAnimationSpeed, 1.0F);
		float age = state.ageInTicks;

		// The body drops slightly and pitches forward as it drives, which is what stops a fast spider
		// from looking like a model being slid along the floor.
		body.xRot = 0.04F + state.walkSurge * 0.10F;
		body.y = 14.5F - Mth.abs(Mth.sin(walk * STEP_RATE * 2.0F)) * amount * 0.8F;
		abdomen.xRot = -0.18F + Mth.sin(walk * STEP_RATE * 2.0F) * amount * 0.06F;

		for (int index = 0; index < legs.length; index++) {
			boolean right = index % 2 == 0;
			int pair = index / 2;
			float side = right ? 1.0F : -1.0F;
			float yaw = (0.80F - pair * 0.52F) * side;
			// Diagonal gait: the four legs that are down are never all on one side, which is what
			// makes eight limbs read as one animal rather than as two sets of four.
			float phase = ((pair + (right ? 0 : 2)) % 4) * Mth.HALF_PI;
			float swing = Mth.cos(walk * STEP_RATE + phase) * 0.42F * amount;
			float lift = Mth.abs(Mth.sin(walk * STEP_RATE + phase)) * 0.38F * amount;
			// A slow wander that never lines up with the step, so a stationary spider still moves.
			float drift = Mth.sin(age * DRIFT_PERIOD + index * 1.31F) * 0.05F;

			ModelPart femur = legs[index];
			femur.yRot = yaw + swing * side + drift;
			femur.zRot = (FEMUR_LIFT - lift) * side;
			// The knee closes as the leg lifts and opens as it plants, which is the difference
			// between a leg that is walking and a leg being waved.
			shins[index].zRot = (KNEE_BEND + lift * 0.75F) * side;
			shins[index].yRot = drift * 0.6F;
		}
	}
}
