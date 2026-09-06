package com.xm.thefourthfrequency.ending;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldInterfaceClientFidelityContractTest {
	private static final Path CLIENT = Path.of("src/client/java/com/xm/thefourthfrequency");
	/**
	 * The clips moved here from {@code client_render}, and had to.
	 *
	 * <p>The hit boxes stand on the animated skeleton now rather than on the bind pose, so the server
	 * has to be able to evaluate the same clips the client draws - and the server cannot see
	 * {@code net.minecraft.client.animation} at all. What is checked below is unchanged; only where
	 * the one copy of it lives has moved.
	 */
	private static final Path COMMON = Path.of("src/main/java/com/xm/thefourthfrequency");

	/**
	 * The three forms accumulate onto one body rather than replacing it.
	 *
	 * <p>This assertion used to require the opposite - three independently baked {@code form_N_*}
	 * trees - and that was the shape the morph problem lived in: swapping the whole model out is
	 * what made a morph read as a model change rather than as growth, and it is why the boss had to
	 * leave the arena to do it. The base shell is now built once and drawn for the whole fight, with
	 * one accretion layer revealed per morph.
	 */
	@Test
	void formsAccumulateOntoOneSharedBodyWithinAnEnforcedBudget() throws Exception {
		String model = read("client_render/WorldInterfaceModel.java");
		var bones = WorldInterfaceGeometryContractTest.bones();
		for (String layer : new String[]{"shell_base", "phase_2_accretion", "phase_3_accretion"}) {
			assertTrue(bones.containsKey(layer), "missing shell layer: " + layer);
			assertEquals("storm_body", bones.get(layer).parent(), layer + " must hang off the body");
		}
		assertFalse(bones.containsKey("form_1_core"),
				"per-form trees are retired: a morph must not swap the model out");
		assertTrue(model.contains("shellBase.visible = true"),
				"the first form's body must survive into the third");
		assertTrue(model.contains("accretions[layer].visible = form > layer"),
				"each morph reveals one more layer over what is already drawn");

		assertTrue(model.contains("ANIMATED_BONE_COUNT = 217"));
		// The geometry is authored in Blockbench and baked from JSON; the part ceiling is enforced
		// against that export by WorldInterfaceGeometryContractTest rather than by clamping
		// generators, and the model must load the export rather than build cubes of its own.
		assertTrue(model.contains("MAX_VISIBLE_PARTS = "));
		assertTrue(model.contains("WorldInterfaceGeometry.load().layer()"),
				"the model must bake the exported Blockbench geometry");
		assertFalse(model.contains("addBox("), "geometry belongs in the bbmodel, not in Java");
		String setup = model.substring(model.indexOf("public void setupAnim"));
		assertFalse(setup.contains("addOrReplaceChild"), "form geometry must remain bake-time static");
	}

	/**
	 * Three heads carry the face, and nothing else on the model competes with them.
	 *
	 * <p>The body used to have a metre-wide eye on its chest inside an orbiting halo. Between them
	 * they took the centre of the silhouette, which left the three skulls reading as ornament on a
	 * machine. The eyes live on the heads now; the interface's own kernel is still present but
	 * buried, and must stay a secondary detail.
	 */
	@Test
	void theOnlyEyesAreOnTheThreeHeadsAndTheKernelStaysBuried() throws Exception {
		String model = read("client_render/WorldInterfaceModel.java");
		var bones = WorldInterfaceGeometryContractTest.bones();
		for (String head : new String[]{"center", "left", "right"}) {
			for (String bone : new String[]{"_head_mount", "_neck_a", "_neck_b", "_skull", "_jaw", "_eye_0"}) {
				assertTrue(bones.containsKey(head + bone), "missing head bone: " + head + bone);
			}
			assertFalse(bones.containsKey(head + "_eye_1"), "one aperture per skull, as in the reference");
		}
		assertTrue(model.contains("EYES_PER_HEAD = 1"), "one aperture per skull, as in the reference");
		// The retired central eye and its halo. Both were explicitly ruled out of the final look.
		for (String retired : new String[]{"buildEyes", "buildRings", "addRingSegments",
				"form_1_eye", "form_3_ring", "EYE_3_BALL_U", "RING_OUTER_U", "addSpires", "addOrbital"}) {
			assertFalse(model.contains(retired), "retired geometry is back: " + retired);
		}
		assertTrue(bones.containsKey("interface_kernel") && bones.containsKey("kernel_glow"),
				"the interface kernel stays, as a buried secondary detail");
		// Head placement is the rig's, not the model's: the server boxes the heads off the same bind
		// pose the exported geometry is checked against, so what a player swings at is what they see.
		assertTrue(readCommon("entity/WorldInterfaceRig.java").contains("WorldInterfaceAnatomy.headLocalUnits("),
				"drawn heads and hittable heads must come from one source");
		// Posing moved to the shared rig, which is what lets the server put the boxes on the bones.
		// The model must read that pose rather than compute a second one.
		assertTrue(model.contains("WorldInterfaceRig.pose(") && model.contains("applyPose("),
				"the drawn pose must be the shared rig's, not the model's own");
		String rig = readCommon("entity/WorldInterfaceRig.java");
		assertTrue(rig.contains("WorldInterfaceAnatomy.neckLengthScale(form)"),
				"neck growth must use an authored positive length, not a signed head coordinate");
		assertFalse(rig.contains("target[1] / Math.max"),
				"negative head coordinates collapse the denominator and explode the model scale");
		assertFalse(rig.contains("_neck_b\").yScale = stretch"),
				"neck_b inherits neck_a's scale; scaling it again squares the growth");
	}

	/** Every limb link is animated, not just its root. */
	@Test
	void limbsBendAtAllThreeLinks() throws Exception {
		String model = read("client_render/WorldInterfaceModel.java");
		String animations = readCommon("entity/WorldInterfaceClips.java");
		String rig = readCommon("entity/WorldInterfaceRig.java");
		assertTrue(model.contains("tendrilMids") && model.contains("tendrilTips"),
				"mid and tip must be real bones, not baked geometry");
		assertTrue(rig.contains("limbFollow"), "links must follow their parent with a delay");
		assertTrue(animations.contains("\"tendril_\" + index + \"_mid\"")
						&& animations.contains("\"tendril_\" + index + \"_tip\""),
				"authored clips must drive the mid and tip links too");
	}

	@Test
	void reusableClipsServeEveryLiveWireAction() throws Exception {
		String animations = readCommon("entity/WorldInterfaceClips.java");
		assertTrue(animations.contains("PROTOCOL_ACTION_COUNT = 13"));
		assertTrue(animations.contains("CLIPS_PER_ACTION = 3"));
		assertTrue(animations.contains("AUTHORED_CLIP_COUNT = 37"));
		assertEquals(37, count(animations, "public static final WorldInterfaceClip "));
		// Wire id 3 held the retired grab-slam. The table is indexed by wire id, so the slot has to
		// stay - collapsing it would silently shift every action after it onto the wrong clips.
		assertTrue(animations.contains("// 3: the grab-slam is retired"),
				"the retired wire id must keep its slot in the clip table");
		// Each attack owns an authored recovery, so the release has somewhere to settle instead of
		// the body simply stopping. The resolution and morph actions keep their original two.
		for (String composition : new String[]{
				"{LASER_AIM, LASER_APERTURE, LASER_RECOVER}", "{ORB_CHARGE, ORB_RELEASE, ORB_RECOVER}",
				"{LANCE_FOCUS, LANCE_DESCENT, LANCE_RECOVER}",
				"{WEAPON_REACH, WEAPON_HOLD, WEAPON_RECOVER}",
				"{THROW_CAPTURE, THROW_RELEASE, THROW_RECOVER}",
				"{HOTBAR_GAZE, HOTBAR_PURGE, HOTBAR_RECOVER}",
				"{TENDRIL_REAR, TENDRIL_LASH, TENDRIL_RECOVER}",
				"{EVICTION_CORRUPTION, FORCED_EXPULSION, EXPULSION_RECOVER}",
				"{SUMMON_CORE, SUMMON_LIMBS}",
				"{MORPH_SECOND_CORE, MORPH_SECOND_LIMBS}",
				"{MORPH_THIRD_CORE, MORPH_THIRD_LIMBS}",
				"{SUCCESS_COLLAPSE, SUCCESS_FADE}", "{FAILURE_BLACKEN, FAILURE_ESCAPE}"}) {
			assertTrue(animations.contains(composition), composition);
		}
	}

	@Test
	void everyWorldSpacePrimitiveSharesOneBoundedBatchAndAmbientAudioIsStoppable() throws Exception {
		String beams = read("client_render/WorldInterfaceBeamBatchRenderer.java");
		String presentation = read("client_ui/WorldInterfacePresentationController.java");
		String renderer = read("client_render/WorldInterfaceRenderer.java");
		assertTrue(beams.contains("RENDER_LAYER_COUNT = 1"));
		// The gate ring is gone: the structures stopped being built, so the shafts were twenty
		// lights standing in empty air. Nothing may put it back.
		assertFalse(beams.contains("extractGateways"));
		assertFalse(beams.contains("MAX_VERTICES_PER_GATE"));
		// The per-family allowances are headroom now, not ceilings to design against; what still
		// matters is that every family is declared and they all resolve to the one batch below.
		assertTrue(beams.contains("MAX_VERTICES_PER_TETHER = 16"));
		assertTrue(beams.contains("FULL_DETAIL_DISTANCE = 96.0D"));
		assertTrue(beams.contains("RENDER_CUTOFF_DISTANCE = 192.0D"));
		assertTrue(beams.contains("WorldRenderEvents.END_EXTRACTION.register"));
		assertTrue(beams.contains("WorldRenderEvents.BEFORE_TRANSLUCENT.register"));
		// The whole point of folding the laser, tethers and orb halos in here: still one layer.
		assertEquals(1, count(beams, "getBuffer(RenderTypes.lightning())"));
		assertTrue(beams.contains("MAX_BATCH_VERTICES = MAX_ANCHOR_TETHERS * MAX_VERTICES_PER_TETHER"),
				"every primitive family must stay inside one declared vertex budget");
		assertTrue(beams.contains("public static void resetSession()"),
				"entity references must be droppable so a disconnect cannot strand a stale level");
		assertFalse(presentation.contains("addParticle("));
		assertTrue(presentation.contains("extends AbstractTickableSoundInstance"));
		assertTrue(presentation.contains("this.looping = true"));
		assertTrue(presentation.contains("canStartSilent()"));
		assertTrue(presentation.contains("stopAmbientLoops()"));
		assertFalse(presentation.contains("AMBIENT_LOOP_TICKS"));
		assertTrue(renderer.contains("MAX_RENDER_LAYERS = 6"));
	}

	/** A lock line may identify the local player, but it must never terminate at their camera. */
	@Test
	void laserWarningBeamStopsAtTheTargetsGroundInsteadOfTheCamera() throws Exception {
		String beams = read("client_render/WorldInterfaceBeamBatchRenderer.java");
		int start = beams.indexOf("private static Vec3 laserEnd(");
		int end = beams.indexOf("private static Player laserTarget(", start);
		assertTrue(start >= 0 && end > start, "laser endpoint resolver is missing");
		String laserEnd = beams.substring(start, end);
		assertTrue(laserEnd.contains("? groundUnder(level, target.getPosition(partialTick))"),
				"the warning shaft must terminate at the target's ground position");
		assertFalse(laserEnd.contains("? target.getEyePosition(partialTick)"),
				"the local player's eye is the render camera; ending a quad there fills the screen");
	}

	@Test
	void erosionRebuildsOnlyTheCylinderItCanTouch() throws Exception {
		String presentation = read("client_ui/WorldInterfacePresentationController.java");
		// allChanged() recreates the whole section render dispatcher; twelve of them during the
		// collapse was a visible hitch at the worst possible moment.
		assertFalse(presentation.contains("levelRenderer.allChanged()"));
		assertTrue(presentation.contains("setSectionRangeDirty("));
		// The radius is the policy's, not the renderer's. Two different numbers meant the render
		// reached four fifths further than the server ever committed, so the island healed most of
		// its damage the instant the encounter cleared.
		assertTrue(presentation.contains("EROSION_RADIUS_BLOCKS = WorldInterfacePolicy.EROSION_RADIUS_BLOCKS"),
				"render and commit must erode the same disc");
	}

	/**
	 * There is one post-effect slot and three subsystems that want it, so exactly one thing may
	 * install into it.
	 *
	 * <p>This used to assert a hand-written "do not install over a chain I do not recognise" guard
	 * inside the encounter's own controller - which was only ever half the contract, because the
	 * pursuit had the opposite rule written into it and simply took the slot. Whoever ticked last
	 * won. {@link com.xm.thefourthfrequency.client_ui.PostEffectArbiter} holds the claims instead of
	 * the outcome, so the same question has one answer; what is pinned here is that the answer puts
	 * the pursuit first, and that nothing outside the mod ever gets installed over or cleared.
	 */
	@Test
	void screenTreatmentsNeverStealAChainTheyDoNotOwn() throws Exception {
		String arbiter = read("client_ui/PostEffectArbiter.java");
		int ownerAt = arbiter.indexOf("public enum Owner {");
		assertTrue(ownerAt > 0, "the arbiter must still declare its owners");
		String owners = arbiter.substring(ownerAt, arbiter.indexOf('}', ownerAt));
		assertTrue(owners.indexOf("PURSUIT") < owners.indexOf("WORLD_INTERFACE"),
				"declaration order is priority order, and a pursuit outranks a lock");
		assertTrue(arbiter.contains("if (installed != null && installed.equals(current))"),
				"only a chain the arbiter installed may be cleared");
		assertTrue(arbiter.contains("if (current != null && (installed == null || !installed.equals(current))) return;"),
				"a chain the arbiter did not install must never be installed over");

		String post = read("client_ui/WorldInterfacePostEffectController.java");
		assertTrue(post.contains("PostEffectArbiter.claim(client, PostEffectArbiter.Owner.WORLD_INTERFACE"),
				"the encounter must ask rather than install");
		String pursuit = read("client_ui/PursuitPresentationClient.java");
		assertTrue(pursuit.contains("PostEffectArbiter.claim(client, PostEffectArbiter.Owner.PURSUIT"),
				"the pursuit must ask rather than install");
		// The burst does not compete for this slot at all any more: it filters the finished frame,
		// HUD included, which is a different slot with its own driver. What must hold is that it
		// never reaches for the level's.
		String anomaly = read("client_ui/AnomalyPresentationController.java");
		assertFalse(anomaly.contains("PostEffectArbiter"),
				"the burst filters the whole frame; it must not also claim the level's slot");
		assertTrue(anomaly.contains("ScreenFilterDriver.request(ScreenFilterDriver.Owner.ANOMALY"),
				"the burst must ask the screen-filter driver");
		assertTrue(post.contains("clearOwned("));
		// One shared lock treatment across every action that telegraphs, rather than a chain owned
		// by a single attack: being singled out has to look the same whoever is doing the singling.
		assertTrue(post.contains("WorldInterfaceProtocol.lockWarningTicks("),
				"the screen treatment must run on the protocol's own lock clock");
		for (String effect : new String[]{"world_interface_lock", "world_interface_lock_peak",
				"world_interface_expulsion"}) {
			assertTrue(Files.exists(Path.of("src/main/resources/assets/thefourthfrequency/post_effect",
					effect + ".json")), "missing post effect: " + effect);
		}
	}

	/**
	 * The lock chain has to actually be installed, and it has to leave the middle of the screen
	 * alone.
	 *
	 * <p>{@code wantedEffect} used to {@code return null} unconditionally, which made the whole lock
	 * chain dead code. The reasoning was sound - the old chain was a box blur plus a full-screen
	 * violet wash, and blurring the frame at the exact moment a player needs to dodge is worse than
	 * showing them nothing - but the fix was to delete the treatment rather than to fix it, and a
	 * lock ended up with no screen presence at all. Both halves of that are pinned here: the chain
	 * must be reachable, and there must be no blur left anywhere in it.
	 */
	@Test
	void theLockTreatmentIsLiveAndStaysOutOfTheCentreOfTheScreen() throws Exception {
		String post = read("client_ui/WorldInterfacePostEffectController.java");
		assertFalse(post.contains("// No screen treatment for a lock"),
				"the lock chain must not be switched off again");
		assertTrue(post.contains("LOCK_PEAK : LOCK"),
				"wantedEffect must resolve to a chain rather than returning null");

		Path effects = Path.of("src/main/resources/assets/thefourthfrequency/post_effect");
		for (String effect : new String[]{"world_interface_lock", "world_interface_lock_peak"}) {
			String chain = Files.readString(effects.resolve(effect + ".json"), StandardCharsets.UTF_8);
			// Blur is what made the previous attempt unplayable. It must not come back.
			assertFalse(chain.contains("box_blur"), effect + " must not blur the arena");
			assertTrue(chain.contains("thefourthfrequency:post/digital_corrupt"),
					effect + " must use the mod's corruption filter");
			assertTrue(chain.contains("CenterClear"),
					effect + " must declare how much of the screen it leaves alone");
			// And it has to leave a real amount of it alone. A radial mask whose clear radius has
			// crept towards zero is a full-screen wash with extra steps, which is the exact failure
			// the whole treatment was rewritten to avoid.
			var config = JsonParser.parseString(chain).getAsJsonObject()
					.getAsJsonArray("passes").get(0).getAsJsonObject()
					.getAsJsonObject("uniforms").getAsJsonArray("CorruptConfig");
			float centerClear = Float.NaN;
			for (var uniform : config) {
				var entry = uniform.getAsJsonObject();
				if ("CenterClear".equals(entry.get("name").getAsString())) {
					centerClear = entry.get("value").getAsFloat();
				}
			}
			assertTrue(centerClear >= 0.4F,
					effect + " leaves only " + centerClear + " of the radius untouched");
		}

		// The HUD vignette is the fallback that cannot fail to compile, so it must exist too.
		String presentation = read("client_ui/WorldInterfacePresentationController.java");
		assertTrue(presentation.contains("renderLockEdge("),
				"the lock needs a HUD-layer warning that survives a shader compile failure");
	}

	/**
	 * The beam treatment is asked for before the lock, and is not private to the target.
	 *
	 * <p>Two things about it are easy to undo by accident and invisible once undone. The first is
	 * order: {@code wantedEffect} used to open with a single early return covering both "no action"
	 * and "not aimed at you", and putting the beam check back underneath that return would silently
	 * make it target-only again - the treatment would still work perfectly for whoever was being
	 * shot at, so nobody testing solo would ever see it missing. The second is precedence: the lance
	 * fires while its own lock window is technically still open, so if the lock is resolved first
	 * the column comes down behind a warning that is still counting.</p>
	 *
	 * <p>Both are pinned on the source rather than on behaviour because the decision they describe
	 * is a decision about the shape of one method, and the shape is what regresses.</p>
	 */
	@Test
	void theBeamTreatmentOutranksTheLockAndIsNotPrivate() throws Exception {
		String post = read("client_ui/WorldInterfacePostEffectController.java");
		int dispersion = post.indexOf("Identifier dispersion = wantedDispersion(");
		int targetGate = post.indexOf("if (!projection.actionTargets(");
		assertTrue(dispersion >= 0, "the beam treatment is never asked for");
		assertTrue(targetGate >= 0, "the lock treatment stopped checking who it is aimed at");
		assertTrue(dispersion < targetGate,
				"the beam treatment is decided after the target gate, so only the player being shot"
						+ " at can see the encounter's most visible event");
		// It has to open on the protocol's own arrival clocks. A literal here would be a second
		// schedule beside the one the server actually lands damage on, and this attack has already
		// paid for exactly that once - the camera shake used to fire 27 ticks before the lance's
		// crater existed because it was derived from a render-only constant.
		for (String clock : new String[]{"LASER_WARNING_TICKS", "SKY_LANCE_LOCK_TICKS",
				"SKY_LANCE_CHARGE_TICKS"}) {
			assertTrue(post.contains("WorldInterfaceProtocol." + clock),
					"the beam treatment must open on the protocol's " + clock);
		}
		// And it must not open on the descent constant. SKY_LANCE_FALL_TICKS describes the last few
		// ticks of the charge, not the impact; subtracting it here would put the flash ahead of the
		// crater, which is the same fraction-of-a-second lie the shake was fixed for.
		//
		// Matched on the qualified reference rather than the bare name, so the comment explaining
		// why the constant is not used does not itself trip the assertion. Naming the mistake in
		// source is how it stays named; only reading the field is refused.
		assertFalse(post.contains("WorldInterfaceProtocol.SKY_LANCE_FALL_TICKS"),
				"the beam treatment must land on the impact tick, not on the render-only fall clock");
		// And the two chains have to be different files, or "aimed at me" and "happening near me"
		// are the same sentence.
		for (String effect : new String[]{"world_interface_dispersion", "world_interface_dispersion_far"}) {
			assertTrue(Files.exists(Path.of("src/main/resources/assets/thefourthfrequency/post_effect",
					effect + ".json")), "missing post effect: " + effect);
		}
	}

	/**
	 * A disarmed flash deadline must never reach a subtraction.
	 *
	 * <p>Both impact overlays are switched off by parking their deadline at {@link Long#MIN_VALUE}.
	 * Subtracting the clock from that does not produce a large negative number meaning "long ago" -
	 * it overflows to a large <em>positive</em> one, which reads as a flash that has only just
	 * started. Both overlays therefore rendered at full strength on every frame from the moment a
	 * world loaded: a permanent red and white wash over the entire screen. It is a one-character
	 * class of mistake with a total-loss symptom, and nothing else in the suite would catch it.
	 */
	@Test
	void aDisarmedImpactFlashCannotOverflowIntoAPermanentWash() throws Exception {
		String presentation = read("client_ui/WorldInterfacePresentationController.java");
		assertTrue(presentation.contains("until == Long.MIN_VALUE || now >= until"),
				"the flash decay must compare the deadline before subtracting from it");
		assertFalse(presentation.contains("decay(hurtFlashUntil - now")
						|| presentation.contains("decay(damageFlashUntil - now"),
				"passing a sentinel deadline through a subtraction overflows it positive");
	}

	/**
	 * The three impact effects are separate settings and the freeze can never fire during a dodge.
	 */
	@Test
	void impactFeedbackIsIndependentlySwitchableAndNeverStealsADodge() throws Exception {
		String hitStop = read("client_ui/WorldInterfaceHitStop.java");
		String shake = read("client_ui/ScreenShakeController.java");
		String arbiter = read("client_ui/FrameHoldArbiter.java");
		String frameHold = read("mixin/MinecraftPursuitFrameHoldMixin.java");

		// Two @Redirects on one instruction is a load-time crash, so both sources go through one.
		assertTrue(arbiter.contains("PursuitPresentationClient.beginFrame(nowNanos) "
						+ "| WorldInterfaceHitStop.beginFrame(nowNanos)"),
				"both frame-hold sources must be evaluated; | is not | |");
		// Matched on the call itself rather than on the file: the note explaining why || is wrong
		// naturally contains the operator it is warning about.
		assertFalse(arbiter.contains("beginFrame(nowNanos) ||"),
				"short-circuiting would strand one source's hold state");
		assertTrue(frameHold.contains("FrameHoldArbiter.beginFrame("),
				"the single redirect must delegate to the arbiter");

		// Holding a frame is two coordinated actions and BOTH must ask the same sources. Skipping
		// the clear without skipping the render draws into a depth buffer that still holds the last
		// frame, so every piece of world geometry fails the depth test and the screen shows sky and
		// HUD with nothing between them. That is what a half-wired hit-stop looked like: a violent
		// full-screen flicker, once per freeze.
		assertTrue(arbiter.contains("public static boolean skipRenderFrame()"),
				"the arbiter must own the render-cancel decision too, not just the clear");
		assertTrue(arbiter.contains("PursuitPresentationClient.skipRenderFrame() "
						+ "| WorldInterfaceHitStop.skipRenderFrame()"),
				"both hold sources must be consulted when cancelling the render");
		String renderCancel = read("mixin/GameRendererPursuitMixin.java");
		assertTrue(renderCancel.contains("FrameHoldArbiter.skipRenderFrame()"),
				"the render cancel must go through the arbiter");
		assertFalse(stripComments(renderCancel).contains("PursuitPresentationClient."),
				"the render cancel must not consult one source directly");

		// Freezing a player mid-dodge trades impact for an unavoidable death.
		assertTrue(hitStop.contains("WorldInterfacePresentationController.isLocalPlayerLocked()"),
				"hit-stop must stand down inside a lock window");
		assertTrue(hitStop.contains("PursuitPresentationClient.isHoldingFrame()"));
		assertTrue(hitStop.contains("client.screen != null"));
		assertTrue(hitStop.contains("MAX_MILLIS = 70L") && hitStop.contains("COOLDOWN_MILLIS = 250L"),
				"the freeze needs a hard ceiling and a cooldown or the third phase is a slideshow");
		assertTrue(hitStop.contains("presentation().hitStopEnabled()"));
		assertTrue(shake.contains("presentation().effectiveCameraShake()"));

		// Aim must be untouched: the mixin moves Camera, never LocalPlayer.
		String camera = read("mixin/CameraShakeMixin.java");
		assertTrue(camera.contains("@Mixin(Camera.class)"));
		assertTrue(camera.contains("method = \"setup\""), "the whole frame must shake consistently");
		// Comments are stripped before matching. The whole point of this file is that it explains
		// why it does not touch the player, so it necessarily says "player" and "LocalPlayer" in
		// prose; matching raw text made the assertion fire on its own documentation.
		String injected = stripComments(
				camera.substring(camera.indexOf("thefourthfrequency$applyEncounterShake")));
		assertFalse(injected.contains("LocalPlayer"),
				"shake must never touch the player's own rotation");
		assertFalse(injected.contains("player"), "aim, ray traces and hit detection stay untouched");
	}

	@Test
	void escalationPaletteIsSharedRatherThanRestatedPerRenderer() throws Exception {
		String palette = read("client_render/WorldInterfacePalette.java");
		String renderer = read("client_render/WorldInterfaceRenderer.java");
		String beams = read("client_render/WorldInterfaceBeamBatchRenderer.java");
		String hud = read("client_ui/WorldInterfaceHud.java");
		assertTrue(palette.contains("PHASE_BAND_COUNT = 3"));
		for (String consumer : new String[]{renderer, beams, hud}) {
			assertTrue(consumer.contains("WorldInterfacePalette."),
					"escalation colour must come from the shared palette");
		}
	}

	@Test
	void customEncounterHudIsTheOnlyBossBar() throws Exception {
		String entity = Files.readString(Path.of(
				"src/main/java/com/xm/thefourthfrequency/entity/WorldInterfaceEntity.java"),
				StandardCharsets.UTF_8);
		String hud = read("client_ui/WorldInterfaceHud.java");
		assertFalse(entity.contains("ServerBossEvent"));
		assertFalse(entity.contains("BossEvent.BossBar"));
		assertFalse(entity.contains("startSeenByPlayer"));
		assertFalse(hud.contains("VANILLA_BOSS_BAR_CLEARANCE"));
		assertTrue(hud.contains("int top = 12"));
	}

	private static String read(String relative) throws Exception {
		return Files.readString(CLIENT.resolve(relative), StandardCharsets.UTF_8);
	}

	/** The clips and the rig they pose are common code now; see {@link #COMMON}. */
	private static String readCommon(String relative) throws Exception {
		return Files.readString(COMMON.resolve(relative), StandardCharsets.UTF_8);
	}

	/**
	 * Drops comments so an assertion cannot match the prose explaining it.
	 *
	 * <p>These checks exist to say "this code does not do X", and the code that does not do X
	 * normally carries a note saying so - which contains X. Three separate assertions here fired on
	 * their own documentation before this existed.
	 */
	private static String stripComments(String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
	}

	private static int count(String text, String needle) {
		int total = 0;
		for (int offset = 0; (offset = text.indexOf(needle, offset)) >= 0; offset += needle.length()) total++;
		return total;
	}
}
