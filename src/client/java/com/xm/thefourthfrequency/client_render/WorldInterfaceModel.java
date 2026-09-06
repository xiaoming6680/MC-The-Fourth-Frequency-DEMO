package com.xm.thefourthfrequency.client_render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.xm.thefourthfrequency.entity.WorldInterfaceAnatomy;
import com.xm.thefourthfrequency.entity.WorldInterfaceRig;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;

import java.util.HashMap;
import java.util.Map;

/**
 * One storm, grown twice: a continuous mass of swallowed terrain carrying three long-necked block
 * skulls, with tentacles trailing from the underside.
 *
 * <p>The geometry is authored in Blockbench and baked from {@link WorldInterfaceGeometry}; this
 * class owns what the geometry cannot say - which bones the rig poses, which layers a form
 * reveals, and which bones carry light.
 *
 * <p>Two things about the shape are load-bearing.
 *
 * <p><b>The heads are the face.</b> There is no eye in the middle of the body and no ring around it.
 * The only eyes on this thing are the three apertures in its three skulls; the interface's own
 * kernel is still in there, buried in the mass as a secondary detail, and is deliberately never the
 * brightest thing on screen.
 *
 * <p><b>The forms accumulate rather than replace.</b> {@code shell_base} is built once and is
 * visible for the whole fight; a morph reveals {@code phase_2_accretion} and then
 * {@code phase_3_accretion} on top of it. What the player watches is the same animal taking on more
 * world. The heads and limbs are likewise one shared bone chain across all three forms: the necks
 * lengthen, they are not exchanged.
 */
public final class WorldInterfaceModel extends EntityModel<WorldInterfaceRenderState> {
	public static final int FORM_COUNT = 3;
	/**
	 * Every bone a clip or the renderer may address.
	 *
	 * <p>root, hover, storm_body, three shell layers, the kernel and its lattice, the weapon, three
	 * six-bone head chains (mount, two necks, skull, eye, jaw) and ten four-link limbs (root, mid,
	 * tip, glow). The two leaves past the bare skeleton - the kernel lattice and the per-limb glow
	 * node - exist because the emissive pass carries a single colour for the whole model, so
	 * "this limb is brighter than that one" can only be said with geometry.
	 */
	public static final int ANIMATED_BONE_COUNT = 217;
	/**
	 * Ceiling on {@code ModelPart}s drawn at once, per form, enforced by the geometry contract test
	 * against the exported model rather than by clamping generators at bake time. Every rotated cube
	 * in Blockbench costs a part of its own, which is where most of this goes.
	 */
	public static final int MAX_VISIBLE_PARTS = 1200;
	private static final int HEADS = WorldInterfaceAnatomy.HEAD_COUNT;
	private static final int TENDRILS = 10;
	/**
	 * One eye per skull: a single large lit aperture set into the face. Two small eyes per head
	 * read as a creature at this size; one reads as an aperture, which is what these are - and it
	 * is the only lit thing on the head, so it wins the silhouette outright.
	 */
	private static final int EYES_PER_HEAD = 1;
	/**
	 * Bone names are unique across the whole tree, not just within a parent.
	 *
	 * <p>A clip addresses a bone by name alone, so three heads each owning a bone called "skull"
	 * would leave two of them permanently unanimatable - and silently, because the lookup would
	 * simply resolve to whichever one it found first. Every head and limb bone is therefore
	 * prefixed with the chain it belongs to.
	 */
	private static final String[] HEAD_PREFIX = {"center", "left", "right"};

	private final ModelPart root;
	private final ModelPart hover;
	private final ModelPart stormBody;
	private final ModelPart shellBase;
	private final ModelPart[] accretions = new ModelPart[FORM_COUNT - 1];
	private final ModelPart interfaceKernel;
	private final ModelPart kernelGlow;
	private final ModelPart weapon;
	private final ModelPart[] headMounts = new ModelPart[HEADS];
	private final ModelPart[] neckA = new ModelPart[HEADS];
	private final ModelPart[] neckB = new ModelPart[HEADS];
	private final ModelPart[] skulls = new ModelPart[HEADS];
	private final ModelPart[] jaws = new ModelPart[HEADS];
	private final ModelPart[][] eyes = new ModelPart[HEADS][EYES_PER_HEAD];
	private final ModelPart[] tendrils = new ModelPart[TENDRILS];
	private final ModelPart[] tendrilMids = new ModelPart[TENDRILS];
	private final ModelPart[] tendrilTips = new ModelPart[TENDRILS];
	private final ModelPart[] tendrilGlows = new ModelPart[TENDRILS];
	/** Every bone the shared rig poses, indexed the way the rig names them. */
	private final Map<String, ModelPart> posedBones = new HashMap<>(64);

	public WorldInterfaceModel(ModelPart root) {
		super(root);
		this.root = root;
		hover = root.getChild("hover");
		stormBody = hover.getChild("storm_body");
		shellBase = stormBody.getChild("shell_base");
		accretions[0] = stormBody.getChild("phase_2_accretion");
		accretions[1] = stormBody.getChild("phase_3_accretion");
		interfaceKernel = stormBody.getChild("interface_kernel");
		kernelGlow = interfaceKernel.getChild("kernel_glow");
		weapon = stormBody.getChild("weapon");
		for (int head = 0; head < HEADS; head++) {
			String prefix = HEAD_PREFIX[head];
			headMounts[head] = stormBody.getChild(prefix + "_head_mount");
			neckA[head] = headMounts[head].getChild(prefix + "_neck_a");
			neckB[head] = neckA[head].getChild(prefix + "_neck_b");
			skulls[head] = neckB[head].getChild(prefix + "_skull");
			jaws[head] = skulls[head].getChild(prefix + "_jaw");
			for (int eye = 0; eye < EYES_PER_HEAD; eye++) {
				eyes[head][eye] = skulls[head].getChild(prefix + "_eye_" + eye);
			}
		}
		for (int index = 0; index < TENDRILS; index++) {
			tendrils[index] = stormBody.getChild("tendril_" + index);
			tendrilMids[index] = flexEnd(tendrils[index], "tendril_" + index)
					.getChild("tendril_" + index + "_mid");
			tendrilTips[index] = flexEnd(tendrilMids[index], "tendril_" + index + "_mid")
					.getChild("tendril_" + index + "_tip");
			tendrilGlows[index] = flexEnd(tendrilTips[index], "tendril_" + index + "_tip")
					.getChild("tendril_" + index + "_glow");
		}
		// The bones the rig poses, resolved once. A missing entry here would be a bone the server is
		// posing and the client is not drawing, which is the exact divergence the rig exists to end,
		// so it is a hard failure rather than a skipped bone.
		posedBones.put(WorldInterfaceRig.HOVER, hover);
		posedBones.put(WorldInterfaceRig.STORM_BODY, stormBody);
		posedBones.put(WorldInterfaceRig.KERNEL, interfaceKernel);
		posedBones.put(WorldInterfaceRig.WEAPON, weapon);
		for (int head = 0; head < HEADS; head++) {
			String prefix = HEAD_PREFIX[head];
			posedBones.put(prefix + "_head_mount", headMounts[head]);
			posedBones.put(prefix + "_neck_a", neckA[head]);
			posedBones.put(prefix + "_neck_b", neckB[head]);
			posedBones.put(prefix + "_skull", skulls[head]);
			posedBones.put(prefix + "_jaw", jaws[head]);
		}
		for (int index = 0; index < TENDRILS; index++) {
			posedBones.put("tendril_" + index, tendrils[index]);
			posedBones.put("tendril_" + index + "_mid", tendrilMids[index]);
			posedBones.put("tendril_" + index + "_tip", tendrilTips[index]);
			ModelPart[] links = {tendrils[index], tendrilMids[index], tendrilTips[index]};
			for (int link = 0; link < links.length; link++) {
				String prefix = "tendril_" + index + WorldInterfaceRig.TENDRIL_LINK_SUFFIXES[link];
				ModelPart parent = links[link];
				for (int joint = 1; joint <= WorldInterfaceRig.FLEX_JOINTS_PER_LINK; joint++) {
					String name = prefix + "_flex_" + joint;
					parent = parent.getChild(name);
					posedBones.put(name, parent);
				}
			}
		}
	}

	private static ModelPart flexEnd(ModelPart root, String prefix) {
		for (int joint = 1; joint <= WorldInterfaceRig.FLEX_JOINTS_PER_LINK; joint++) root = root.getChild(prefix + "_flex_" + joint);
		return root;
	}

	/** The Blockbench-authored geometry, baked. */
	public static LayerDefinition createLayer() {
		return WorldInterfaceGeometry.load().layer();
	}

	/**
	 * Whether a bone is drawn at a form, before any per-frame state: the accretion layers reveal
	 * one per morph, and only the first {@code tentacleCount(form)} limbs hang off the body. The
	 * geometry contract test counts parts under the bones this admits against
	 * {@link #MAX_VISIBLE_PARTS}.
	 */
	public static boolean drawnAtForm(String bone, int form) {
		int clamped = Math.clamp(form, 0, FORM_COUNT - 1);
		if (bone.equals("phase_2_accretion")) return clamped >= 1;
		if (bone.equals("phase_3_accretion")) return clamped >= 2;
		if (bone.startsWith("tendril_")) {
			int end = bone.indexOf('_', "tendril_".length());
			String index = end < 0 ? bone.substring("tendril_".length()) : bone.substring("tendril_".length(), end);
			try {
				return Integer.parseInt(index) < WorldInterfaceAnatomy.tentacleCount(clamped);
			} catch (NumberFormatException ignored) {
				return true;
			}
		}
		return true;
	}

	/**
	 * Submit only the bones that actually carry glow, rather than the whole model a second time.
	 *
	 * <p>The lit parts are the three apertures and the limb tips, plus the kernel buried in the
	 * mass. That is a small fraction of several hundred parts, and submitting all of them for an
	 * emissive sheet that is mostly transparent meant nearly every vertex went through a translucent
	 * pass to draw nothing, paying for the sort on the way.
	 *
	 * <p>Bones are submitted individually, so the parent transforms they would normally inherit have
	 * to be walked by hand; {@code submitModelPart} copies the pose, which is what makes the
	 * push/pop around it safe. Visibility is gated here too, because a bone submitted directly never
	 * consults the parent whose {@code visible} flag would otherwise have hidden it.
	 */
	void submitEmissive(PoseStack poseStack, OrderedSubmitNodeCollector collector, RenderType renderType,
			int color, int outlineColor, int form) {
		poseStack.pushPose();
		root.translateAndRotate(poseStack);
		hover.translateAndRotate(poseStack);
		stormBody.translateAndRotate(poseStack);

		poseStack.pushPose();
		interfaceKernel.translateAndRotate(poseStack);
		submitPart(collector, kernelGlow, poseStack, renderType, color, outlineColor);
		poseStack.popPose();

		for (int head = 0; head < HEADS; head++) {
			poseStack.pushPose();
			headMounts[head].translateAndRotate(poseStack);
			neckA[head].translateAndRotate(poseStack);
			neckB[head].translateAndRotate(poseStack);
			skulls[head].translateAndRotate(poseStack);
			for (ModelPart eye : eyes[head]) {
				submitPart(collector, eye, poseStack, renderType, color, outlineColor);
			}
			poseStack.popPose();
		}

		int limbs = WorldInterfaceAnatomy.tentacleCount(Math.clamp(form, 0, FORM_COUNT - 1));
		for (int index = 0; index < limbs && index < TENDRILS; index++) {
			poseStack.pushPose();
			tendrils[index].translateAndRotate(poseStack);
			translateFlex(poseStack, tendrils[index], "tendril_" + index);
			tendrilMids[index].translateAndRotate(poseStack);
			translateFlex(poseStack, tendrilMids[index], "tendril_" + index + "_mid");
			tendrilTips[index].translateAndRotate(poseStack);
			translateFlex(poseStack, tendrilTips[index], "tendril_" + index + "_tip");
			submitPart(collector, tendrilGlows[index], poseStack, renderType, color, outlineColor);
			poseStack.popPose();
		}
		poseStack.popPose();
	}

	private static void translateFlex(PoseStack stack, ModelPart root, String prefix) {
		for (int joint = 1; joint <= WorldInterfaceRig.FLEX_JOINTS_PER_LINK; joint++) {
			root = root.getChild(prefix + "_flex_" + joint);
			root.translateAndRotate(stack);
		}
	}

	private static void submitPart(OrderedSubmitNodeCollector collector, ModelPart part,
			PoseStack poseStack, RenderType renderType, int color, int outlineColor) {
		collector.submitModelPart(part, poseStack, renderType, LightTexture.FULL_BRIGHT,
				OverlayTexture.NO_OVERLAY, null, false, false, color, null, outlineColor);
	}

	@Override
	public void setupAnim(WorldInterfaceRenderState state) {
		super.setupAnim(state);
		int form = Math.clamp(state.form, 0, FORM_COUNT - 1);
		// Accretion, not replacement: the base shell is always drawn, and each morph turns on one
		// more layer over it. Nothing is ever hidden that the player has already seen.
		shellBase.visible = true;
		for (int layer = 0; layer < accretions.length; layer++) {
			accretions[layer].visible = form > layer;
		}
		weapon.visible = form >= 1 && state.actionId == 5;
		// Published by the anatomy rather than restated here: the server stands one hit proxy on
		// each drawn limb, so the two counts have to be the same number in one place.
		int activeTendrils = WorldInterfaceAnatomy.tentacleCount(form);
		for (int index = 0; index < TENDRILS; index++) {
			tendrils[index].visible = index < activeTendrils;
		}
		// The whole pose - clips, hover, neck growth, head tracking, limb follow, structural sag -
		// comes off the shared rig, which is the same call the server places the hit boxes from.
		// Anything moved back in here is a bone the player can see somewhere they cannot hit.
		applyPose(WorldInterfaceRig.pose(form, state.ageInTicks, state.healthFraction,
				state.actionId, state.actionAgeMillis, state.gazeYaw, state.gazePitch));
		// Purely optical, and deliberately still local: neither changes where a bone is.
		applyTendrilGlow(state, form);
		applyKernelCharge(state, form);
	}

	/** Copies a posed skeleton onto the {@code ModelPart}s that draw it. */
	private void applyPose(WorldInterfaceRig.Pose pose) {
		for (WorldInterfaceRig.Bone bone : pose.bones()) {
			ModelPart part = posedBones.get(bone.name);
			if (part == null) continue;
			part.x = bone.x;
			part.y = bone.y;
			part.z = bone.z;
			part.xRot = bone.xRot;
			part.yRot = bone.yRot;
			part.zRot = bone.zRot;
			part.xScale = bone.xScale;
			part.yScale = bone.yScale;
			part.zScale = bone.zScale;
		}
	}

	/**
	 * Every limb glows on its own clock. The emissive pass can only carry one colour for the whole
	 * model, so per-limb intensity has to come from geometry instead: scaling each limb's node bone
	 * changes how much glyph it puts on screen, which reads as that tentacle brightening. Slightly
	 * different rates per index mean they never pulse in unison, and winding up an action drives all
	 * of them at once - the limbs light before the thing they light for.
	 */
	private void applyTendrilGlow(WorldInterfaceRenderState state, int form) {
		float time = state.ageInTicks;
		float charge = state.actionCharge < 0.0F ? 0.0F : Math.min(1.0F, state.actionCharge);
		float wear = 1.0F - Math.clamp(state.healthFraction, 0.0F, 1.0F);
		for (int index = 0; index < tendrilGlows.length; index++) {
			ModelPart glow = tendrilGlows[index];
			if (!tendrils[index].visible) continue;
			float pulse = 0.5F + 0.5F * Mth.sin(time * (0.085F + index * 0.012F) + index * 1.93F);
			// Guttering as the pool drains: the limbs stop reaching full brightness.
			float ceiling = 1.0F - wear * 0.34F;
			glow.xScale = glow.yScale = glow.zScale =
					(0.42F + pulse * 0.68F * ceiling + charge * charge * 0.95F);
		}
	}

	/**
	 * The kernel charges with the attack, and at third form it saws up to every volley.
	 *
	 * <p>Deliberately a modest, buried light rather than a beacon. It says the interface is doing
	 * something; the three apertures say what and where.
	 */
	private void applyKernelCharge(WorldInterfaceRenderState state, int form) {
		float charge = state.actionCharge < 0.0F ? 0.0F : Math.min(1.0F, state.actionCharge);
		float pulse = 0.5F + 0.5F * Mth.sin(state.ageInTicks * 0.09F);
		float berserk = form >= 2 ? WorldInterfacePalette.volleyRamp(state.ageInTicks) * 0.45F : 0.0F;
		float scale = 0.86F + pulse * 0.14F + charge * charge * 0.55F + berserk;
		kernelGlow.xScale = kernelGlow.yScale = kernelGlow.zScale = scale;
		interfaceKernel.zRot = Mth.sin(state.ageInTicks * 0.021F) * 0.12F;
	}
}
