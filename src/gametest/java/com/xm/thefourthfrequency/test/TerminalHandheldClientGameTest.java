package com.xm.thefourthfrequency.test;

import com.xm.thefourthfrequency.client_ui.TerminalHandheldAnimator;
import com.xm.thefourthfrequency.client_ui.TerminalAutoOpenController;
import com.xm.thefourthfrequency.client_ui.TerminalScreen;
import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.terminal.TerminalHandheldPose;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The handheld terminal, on its own: the rigid shell, the two-handed carry, and the raise.
 *
 * <p>Separate from {@code M0ClientGameTest} because that suite is about the mainline and only ever
 * sees the device incidentally, on one page of one save, facing one direction. Everything that has
 * actually gone wrong with this device went wrong somewhere that suite never looks - at the
 * extremes of the view angle, with something in the off hand, or one frame behind the camera.</p>
 *
 * <p>Run with {@code -PtffClientTestSuite=terminal-3d}.</p>
 *
 * <h2>What is asserted, and what is only photographed</h2>
 *
 * <p>Nothing here can read the framebuffer, so "the device is in the middle of the screen" is not
 * available as an assertion. What <em>is</em> available is the state the renderer is driven from,
 * and that is what the assertions cover; the screenshots exist so a human can check the half that
 * arithmetic cannot.</p>
 *
 * <p>The one exception is the turn test, which is a real regression guard rather than a picture:
 * the device is drawn from a pose that does not depend on the view angle at all, so if turning ever
 * moves it again, it will be because something outside that pose broke - and the frames taken
 * either side of a 180 degree turn are how a reviewer sees that in one glance.</p>
 */
public final class TerminalHandheldClientGameTest implements FabricClientGameTest {
	/** Somewhere with sky on all sides, so the device is the only thing in the lower frame. */
	private static final BlockPos PLATFORM = new BlockPos(0, 100, 0);

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientGameTestSelection.current().runsTerminalHandheld()) return;
		context.waitForScreen(TitleScreen.class);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			// The terminal has to be the one Relay Station Zero issued, not a fresh ItemStack: the
			// server checks binding, owner and world id before it will open one, and a bare stack is
			// refused. So the stage is built around the real item instead of replacing it.
			awaitIssuedTerminal(context);
			// Spent here, before the stage is built, and that order is not cosmetic. The walkthrough
			// owns the first open of a new save and refuses to close, and since the terminal now
			// brings itself up on the join that issues it, that open arrives on its own about a
			// second in - whether or not this test asks for it. Measuring the resting carry first
			// used to work only because nothing opened the device unprompted; it now fails with "a
			// terminal nobody opened is not at rest", which is the greeting, correctly, being at
			// work. Spending it first also settles the offer before the stage clears the inventory
			// out from under an open screen.
			completeWalkthrough(context);
			context.waitFor(client -> !TerminalAutoOpenController.armed(), 200);
			singleplayer.getServer().runOnServer(TerminalHandheldClientGameTest::buildStage);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);

			assertCarriedInBothHands(context);
			assertSurvivesLookingAround(context);
			assertRaiseAndReturn(context);
			assertHittingSomethingShovesTheDevice(context);
			assertOffHandFallsBackToOneHand(context);
			assertEveryFormDraws(context);
		}
		context.waitForScreen(TitleScreen.class);
	}

	/**
	 * A platform in open sky.
	 *
	 * <p>Indoors the device is against walls and a ceiling, which is exactly the occlusion these
	 * shots are meant to isolate from. Creative mode keeps the player from falling out of frame
	 * mid-capture.</p>
	 */
	private static void buildStage(MinecraftServer server) {
		var level = server.overworld();
		for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
			level.setBlockAndUpdate(PLATFORM.offset(x, -1, z), Blocks.SMOOTH_STONE.defaultBlockState());
		}
		var player = server.getPlayerList().getPlayers().getFirst();
		player.setGameMode(GameType.CREATIVE);
		player.teleportTo(PLATFORM.getX() + 0.5D, PLATFORM.getY(), PLATFORM.getZ() + 0.5D);
		// Move the issued terminal into the first slot rather than replacing it. Everything else in
		// the bag goes, so the off-hand test starts from a known state.
		var inventory = player.getInventory();
		ItemStack terminal = ItemStack.EMPTY;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			if (inventory.getItem(slot).is(ModItems.OLD_TERMINAL)) {
				terminal = inventory.getItem(slot).copy();
				break;
			}
		}
		if (terminal.isEmpty()) throw new AssertionError("No issued terminal to stage");
		inventory.clearContent();
		inventory.setItem(0, terminal);
		inventory.setSelectedSlot(0);
	}

	/** Waits for Relay Station Zero to hand this player their bound terminal. */
	static void awaitIssuedTerminal(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 120; attempt++) {
			boolean[] issued = {false};
			context.runOnClient(client -> {
				if (client.player == null) return;
				var inventory = client.player.getInventory();
				for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
					if (inventory.getItem(slot).is(ModItems.OLD_TERMINAL)) issued[0] = true;
				}
			});
			if (issued[0]) return;
			context.waitTicks(5);
		}
		throw new AssertionError("The station never issued a personal terminal");
	}

	/**
	 * The resting carry: both hands under the device, nothing pending, camera untouched.
	 *
	 * <p>Runs after the walkthrough, because the first open of a new save is no longer something
	 * this test chooses to trigger - the terminal brings itself up on the join that issues it.
	 */
	private static void assertCarriedInBothHands(ClientGameTestContext context) {
		context.waitTicks(10);
		context.runOnClient(client -> {
			if (!client.player.getMainHandItem().is(ModItems.OLD_TERMINAL)) {
				throw new AssertionError("The staged player is not holding the terminal");
			}
			if (!client.player.getOffhandItem().isEmpty()) {
				throw new AssertionError("The off hand must be empty for the two-handed carry");
			}
			if (TerminalHandheldAnimator.state() != TerminalHandheldAnimator.State.IDLE) {
				throw new AssertionError("The device did not return to rest after the first open");
			}
			if (TerminalHandheldAnimator.fovScale() != 1.0F) {
				throw new AssertionError("A carried terminal must not touch the field of view");
			}
			var pose = TerminalHandheldAnimator.presentation();
			if (pose.handZ() == pose.z()) {
				throw new AssertionError("Hands and device must be drawn at their own depths");
			}
		});
		context.takeScreenshot("terminal-3d-carried");
	}

	/**
	 * Turning must not move the device.
	 *
	 * <p>This is the regression this suite was built around. The pose is a pure function of how far
	 * open the terminal is, so it cannot depend on the view angle - but the device is drawn through
	 * a cancelled vanilla method, and the first version of that forgot to flush the render
	 * dispatcher. The submitted geometry then waited for the next frame's flush and was drawn
	 * against that frame's matrices, one frame stale: turning the head threw it out of view and
	 * stopping brought it back.</p>
	 *
	 * <p>So the assertion is that the pose is identical either side of a half turn, and the frames
	 * are what show a reviewer that the device is still in front of the camera.</p>
	 */
	private static void assertSurvivesLookingAround(ClientGameTestContext context) {
		float[][] views = {{0.0F, 0.0F}, {-90.0F, 0.0F}, {90.0F, 0.0F}, {0.0F, 180.0F}};
		TerminalHandheldPose.Presentation[] seen = new TerminalHandheldPose.Presentation[views.length];
		for (int index = 0; index < views.length; index++) {
			final int slot = index;
			context.runOnClient(client -> {
				client.player.setXRot(views[slot][0]);
				client.player.setYRot(views[slot][1]);
				client.player.xRotO = views[slot][0];
				client.player.yRotO = views[slot][1];
			});
			context.waitTicks(4);
			context.runOnClient(client -> seen[slot] = TerminalHandheldAnimator.presentation());
			context.takeScreenshot("terminal-3d-view-" + (int) views[slot][0]
					+ "-" + (int) views[slot][1]);
		}
		for (int index = 1; index < seen.length; index++) {
			// Breathing moves y a little between samples, so compare the terms that must not move.
			if (seen[index].z() != seen[0].z() || seen[index].scale() != seen[0].scale()
					|| seen[index].handSpread() != seen[0].handSpread()) {
				throw new AssertionError("The carried pose changed with the view angle: "
						+ seen[0] + " -> " + seen[index]);
			}
		}
		context.runOnClient(client -> {
			client.player.setXRot(0.0F);
			client.player.setYRot(0.0F);
			client.player.xRotO = 0.0F;
			client.player.yRotO = 0.0F;
		});
		context.waitTicks(4);
	}

	/** Spends the one-time first-boot walkthrough, which holds the terminal open until it is done. */
	static void completeWalkthrough(ClientGameTestContext context) {
		rightClickTerminal(context);
		context.waitForScreen(TerminalScreen.class);
		// The input gate is real time and depends on translated paragraph lengths.
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(90);
		while (System.nanoTime() < deadline) {
			boolean[] held = {false};
			context.runOnClient(client -> {
				if (!(client.screen instanceof TerminalScreen terminal)) return;
				held[0] = terminal.onboardingLocksExitForTesting();
				// The profile comes between the self test and the tabs, holds the exit, and points at
				// no tab - so it has to be answered here or the loop below spends its whole budget
				// waiting for a pointer that cannot appear. Parking the receiver on an option is
				// enough: the commit comes from the real one-second hold, and re-parking on the same
				// frequency every pass is a no-op that lets that hold run.
				int question = terminal.profileQuestionForTesting();
				if (question >= 0) {
					terminal.tuneToProfileOptionForTesting(0);
					return;
				}
				int target = terminal.onboardingTargetPageForTesting();
				// -1 during the self test, which takes no input at all - just wait it out.
				if (target >= 0) terminal.keyPressed(new net.minecraft.client.input.KeyEvent(
						org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
			});
			if (!held[0]) break;
			context.waitTicks(4);
		}
		context.runOnClient(client -> {
			if (client.screen instanceof TerminalScreen terminal) {
				if (terminal.onboardingLocksExitForTesting()) throw new AssertionError(
						"Walkthrough still locked: question=" + terminal.profileQuestionForTesting()
						+ ", targetPage=" + terminal.onboardingTargetPageForTesting());
				terminal.onClose();
			}
		});
		awaitRest(context);
	}

	private static void rightClickTerminal(ClientGameTestContext context) {
		context.runOnClient(client -> {
			InteractionResult result = UseItemCallback.EVENT.invoker()
					.interact(client.player, client.level, InteractionHand.MAIN_HAND);
			if (result != InteractionResult.SUCCESS) {
				throw new AssertionError("Using the held terminal did not send its open request");
			}
		});
	}

	/** Right-click raises it, the screen only appears once it is up, and closing puts it back. */
	private static void assertRaiseAndReturn(ClientGameTestContext context) {
		rightClickTerminal(context);
		context.waitForScreen(TerminalScreen.class);
		context.waitTicks(4);
		context.runOnClient(client -> {
			if (TerminalHandheldAnimator.state() != TerminalHandheldAnimator.State.OPEN) {
				throw new AssertionError("The screen opened before the device finished rising");
			}
			if (TerminalHandheldAnimator.fovScale() >= 1.0F) {
				throw new AssertionError("The camera never leaned in toward the CRT");
			}
			var open = TerminalHandheldAnimator.presentation();
			if (open.pitch() != 0.0F) {
				throw new AssertionError("An open terminal must be square to the camera: " + open.pitch());
			}
		});
		context.takeScreenshot("terminal-3d-open");

		context.runOnClient(client -> ((TerminalScreen) client.screen).onClose());
		context.takeScreenshot("terminal-3d-lowering");
		awaitRest(context);
		context.runOnClient(client -> {
			if (TerminalHandheldAnimator.fovScale() != 1.0F) {
				throw new AssertionError("A closed terminal left the camera zoomed");
			}
		});
	}

	/**
	 * Mining, punching and using things move the device; the terminal's own raise does not.
	 *
	 * <p>Half of this is only checkable in a running client. {@code TerminalHandheldPoseTest} owns the
	 * shape of the shove and {@code TerminalHandheldAnimatorTest} owns the rule about the raise, but
	 * both start from a swing progress handed to them as a number. What neither can see is whether
	 * {@code LocalPlayer.swing} actually produces one - and a gate in front of a value that is always
	 * zero passes every unit test there is while the device sits welded to the lens.</p>
	 *
	 * <p>So the assertion is that a real swing on a real client raises {@code attackAnim} above zero,
	 * that the pose drawn from it moves forward and down, and that the same non-zero progress is
	 * refused while the terminal is performing - which is exactly the collision the real open causes,
	 * because the open request answers {@code SUCCESS} and {@code Minecraft.startUseItem} swings the
	 * hand on the strength of it.</p>
	 */
	private static void assertHittingSomethingShovesTheDevice(ClientGameTestContext context) {
		float[] rest = new float[2];
		context.runOnClient(client -> {
			var pose = TerminalHandheldAnimator.presentation();
			rest[0] = pose.y();
			rest[1] = pose.z();
			client.player.swing(InteractionHand.MAIN_HAND);
		});
		// Two ticks in the swing counter has moved off zero and the shove is near its peak, which is
		// a quarter of the way through - the same early peak vanilla's own attack transform has.
		context.waitTicks(2);
		context.runOnClient(client -> {
			float progress = client.player.getAttackAnim(1.0F);
			if (progress <= 0.0F) {
				throw new AssertionError("A swung hand reported no swing progress: " + progress);
			}
			float swing = TerminalHandheldAnimator.swingShown(progress);
			if (swing != progress) {
				throw new AssertionError("A carried terminal refused the player's own swing");
			}
			var pose = TerminalHandheldAnimator.presentation(swing);
			if (pose.y() >= rest[0]) {
				throw new AssertionError("The shove did not carry the device down: "
						+ rest[0] + " -> " + pose.y());
			}
			if (Math.abs(pose.z()) <= Math.abs(rest[1])) {
				throw new AssertionError("The shove did not carry the device forward: "
						+ rest[1] + " -> " + pose.z());
			}
		});
		context.takeScreenshot("terminal-3d-swing");

		// It recovers on its own: the envelope is zero at both ends of the swing.
		context.waitTicks(12);
		context.runOnClient(client -> {
			var pose = TerminalHandheldAnimator.presentation(
					TerminalHandheldAnimator.swingShown(client.player.getAttackAnim(1.0F)));
			if (Math.abs(pose.z() - rest[1]) > 1.0E-4F) {
				throw new AssertionError("The device never came back from the shove: " + pose.z());
			}
		});

		// And the raise refuses the swing it causes itself. The open is a round trip through the
		// integrated server, so the collision is staged only once the animator has actually taken it -
		// until then the device is still idle and would rightly take a swing.
		rightClickTerminal(context);
		for (int attempt = 0; attempt < 40; attempt++) {
			boolean[] performing = {false};
			context.runOnClient(client -> performing[0] =
					TerminalHandheldAnimator.state() != TerminalHandheldAnimator.State.IDLE);
			if (performing[0]) break;
			context.waitTicks(1);
		}
		context.runOnClient(client -> {
			if (TerminalHandheldAnimator.state() == TerminalHandheldAnimator.State.IDLE) {
				throw new AssertionError("The open request never reached the animator");
			}
			client.player.swing(InteractionHand.MAIN_HAND);
		});
		context.waitTicks(2);
		context.runOnClient(client -> {
			float progress = client.player.getAttackAnim(1.0F);
			if (progress <= 0.0F) throw new AssertionError("The staged swing never started");
			if (TerminalHandheldAnimator.swingShown(progress) != 0.0F) {
				throw new AssertionError("The opening device took a swing on top of its own travel");
			}
		});
		context.waitForScreen(TerminalScreen.class);
		context.runOnClient(client -> ((TerminalScreen) client.screen).onClose());
		awaitRest(context);
	}

	/** Waits for the device to finish travelling back down into the hands. */
	private static void awaitRest(ClientGameTestContext context) {
		for (int attempt = 0; attempt < 40; attempt++) {
			boolean[] settled = {false};
			context.runOnClient(client -> settled[0] =
					TerminalHandheldAnimator.state() == TerminalHandheldAnimator.State.IDLE);
			if (settled[0]) return;
			context.waitTicks(2);
		}
		throw new AssertionError("The terminal never came back down after its screen closed");
	}

	/**
	 * With the off hand full, the two-handed carry stands down.
	 *
	 * <p>Taking both hands would mean silently not drawing the other item, which is the rule
	 * vanilla applies to maps. The screenshot is the only way to see that the torch is still there.
	 * </p>
	 */
	private static void assertOffHandFallsBackToOneHand(ClientGameTestContext context) {
		context.runOnClient(client -> client.player.getInventory()
				.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
						new ItemStack(Items.TORCH)));
		context.waitTicks(10);
		context.runOnClient(client -> {
			if (client.player.getOffhandItem().isEmpty()) {
				throw new AssertionError("The off-hand item never reached the client");
			}
		});
		context.takeScreenshot("terminal-3d-offhand-occupied");
		context.runOnClient(client -> client.player.getInventory()
				.setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND, ItemStack.EMPTY));
		context.waitTicks(6);
	}

	/**
	 * All six forms, photographed in order.
	 *
	 * <p>Set locally rather than by driving the story to each stage: the mapping from state to form
	 * is already covered by unit tests, and what these frames are for is the half that is not
	 * testable - that odd forms differ from their neighbour by a lit lamp and nothing else.</p>
	 */
	private static void assertEveryFormDraws(ClientGameTestContext context) {
		ItemStack[] issued = new ItemStack[1];
		context.runOnClient(client -> issued[0] = client.player.getInventory().getItem(0).copy());
		for (int form = 0; form < 6; form++) {
			final int value = form;
			context.runOnClient(client -> {
				// A copy of the real bound item, so nothing but the form changes. Building a fresh
				// stack would throw away the binding the server checks before it opens anything.
				ItemStack stack = issued[0].copy();
				stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
						List.of((float) value), List.of(), List.of(), List.of()));
				client.player.getInventory().setItem(0, stack);
			});
			context.waitTicks(6);
			context.takeScreenshot("terminal-3d-form-" + form);
		}
		context.runOnClient(client -> client.player.getInventory().setItem(0, issued[0]));
		context.waitTicks(4);
	}
}
