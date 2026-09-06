package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.content.ModItems;
import com.xm.thefourthfrequency.networking.TerminalControlPayload;
import com.xm.thefourthfrequency.networking.TerminalSnapshotPayload;
import com.xm.thefourthfrequency.terminal.TerminalHandheldPose;
import com.xm.thefourthfrequency.terminal.TerminalUiLayout;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.List;

/**
 * The held terminal's open and close performance, and its resting idle.
 *
 * <p>The device is one rigid block and never deforms. What plays is the <em>camera</em> closing on
 * its CRT: the terminal rises from a natural carried tilt to square-on and filling the frame, the
 * field of view narrows slightly, and only once the screen has effectively taken over the frame
 * does {@link TerminalScreen} appear. Closing runs the same path backwards - the UI goes first, so
 * the player sees the device up against the lens before it settles back into the hand.</p>
 *
 * <p>Client-only and entirely presentational. It never touches the stack the server owns, and
 * holding the screen back for half a second changes nothing about when the server considers the
 * terminal open. Nothing here writes to the item either, which is what keeps vanilla's equip
 * animation out of it: {@code ItemInHandRenderer.tick} replays the equip swing whenever the
 * visible stack changes, so a per-frame component write would have re-equipped the terminal
 * several times a second for the length of the performance.</p>
 *
 * <p>The player's position in the world is untouched, and so is the third-person camera - the
 * whole effect is a transform wrapped around this one item's first-person rendering.</p>
 *
 * <p>Static because there is exactly one local player holding exactly one terminal, matching how
 * {@code TerminalClientAudio} is arranged.</p>
 */
public final class TerminalHandheldAnimator {
	public enum State { IDLE, OPENING, OPEN, CLOSING }

	private static State state = State.IDLE;
	private static long phaseStartedAtMillis;
	private static TerminalSnapshotPayload pendingSnapshot;
	/**
	 * Set when the terminal was opened without the two-handed performance.
	 *
	 * <p>The raise, the scale-up and the lens closing in are one gesture belonging to the device
	 * being brought up in both hands. With something in the off hand there is no such gesture -
	 * vanilla is drawing the item on one arm and {@code ItemInHandRendererTerminalMixin} only tilts
	 * it - so playing the timings anyway bought a delay before the screen and nothing to look at
	 * during it, and pulled the field of view in around an object that had not moved.
	 *
	 * <p>While set, {@link #openness} reports the device at rest however the state machine has
	 * advanced, which is what keeps the FOV at exactly 1 rather than snapping to the closed-in value
	 * on the frame the screen appears.
	 */
	private static boolean performanceSkipped;

	private TerminalHandheldAnimator() {
	}

	public static State state() {
		return state;
	}

	public static boolean isAnimating() {
		return state == State.OPENING || state == State.CLOSING;
	}

	/**
	 * Holds the snapshot and starts raising the terminal. The screen opens once the CRT has the
	 * frame.
	 *
	 * <p>If a screen is already up this is a refresh rather than an opening, and the caller keeps
	 * handling it directly.</p>
	 */
	public static void requestOpen(TerminalSnapshotPayload payload) {
		requestOpen(payload, twoHandedNow());
	}

	/**
	 * The same, with the carry mode supplied rather than looked up.
	 *
	 * <p>The state machine takes it as an input instead of asking {@code Minecraft} for it, so every
	 * transition here stays reachable from a plain unit test - which is the whole basis on which
	 * {@code TerminalHandheldAnimatorTest} exists.
	 */
	static void requestOpen(TerminalSnapshotPayload payload, boolean twoHanded) {
		pendingSnapshot = payload;
		// The server keeps pushing snapshots while the terminal is open - once a second at rest, far
		// more often while the dial is settling. Restarting the phase on each of them would hold the
		// device mid-travel forever and the screen would never arrive.
		if (state == State.OPENING || state == State.OPEN) return;
		// Nothing to watch on one arm, so nothing to wait for: the page is the whole event.
		if (!twoHanded) {
			performanceSkipped = true;
			state = State.OPEN;
			phaseStartedAtMillis = now();
			if (!presentScreen()) abandonOpening();
			return;
		}
		performanceSkipped = false;
		// Reversing mid-close resumes from where the device actually is rather than from the hand,
		// so a player who closes and immediately reopens does not see it snap back down first.
		phaseStartedAtMillis = state == State.CLOSING ? reversedPhaseStart(true) : now();
		state = State.OPENING;
	}

	public static void requestClose() {
		if (state == State.IDLE) return;
		pendingSnapshot = null;
		// A close that was never opened with a performance does not get one on the way down either.
		if (performanceSkipped) {
			performanceSkipped = false;
			state = State.IDLE;
			phaseStartedAtMillis = now();
			return;
		}
		phaseStartedAtMillis = state == State.OPENING ? reversedPhaseStart(false) : now();
		state = State.CLOSING;
	}

	/** Drops everything and returns the item to rest. For deaths, dimension changes and disconnects. */
	public static void abort() {
		pendingSnapshot = null;
		TerminalClientNetworking.discardPending();
		state = State.IDLE;
		performanceSkipped = false;
		phaseStartedAtMillis = now();
	}

	/**
	 * Whether the device is being carried in both hands, which is the only case with a performance.
	 *
	 * <p>Deliberately the same condition {@code ItemInHandRendererTerminalMixin} uses to decide
	 * whether to take over vanilla's pass at all. If the two ever disagreed, one of them would be
	 * animating something the other is not drawing.
	 *
	 * <p>Answers true with no client at all, so the performance is the default outside a running
	 * game rather than something that depends on a null check.
	 */
	private static boolean twoHandedNow() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || client.player == null) return true;
		return client.player.getMainHandItem().is(ModItems.OLD_TERMINAL)
				&& client.player.getOffhandItem().isEmpty();
	}

	/**
	 * Hands the waiting snapshot to a new screen. Shared by both routes into {@link State#OPEN}.
	 *
	 * @return {@code false} only when something the player put up is in front of them, which is the
	 *         one case the opening has to be given up rather than pushed through. Having no client
	 *         or no snapshot to place is not a refusal - there is simply nothing to do, and the
	 *         headless state-machine tests drive every transition in exactly that condition.
	 */
	private static boolean presentScreen() {
		Minecraft client = Minecraft.getInstance();
		if (client == null || pendingSnapshot == null) return true;
		if (client.screen instanceof TerminalScreen) return true;
		// The terminal coming up is a performance this mod raises over the world, and it may not
		// evict a screen the player put there themselves. There is always a window for that: the
		// server accepts the open, the device spends half a second rising, and the inventory or the
		// pause menu can go up inside it. The auto-open widens the window by a whole round trip and
		// removes the one thing that excused it - with a right-click the player asked half a second
		// ago, and with the greeting nobody asked at all.
		if (client.screen != null) return false;
		TerminalSnapshotPayload payload = pendingSnapshot;
		pendingSnapshot = null;
		TerminalScreen screen = new TerminalScreen(payload);
		client.setScreen(screen);
		// The navigation and tool snapshots the server sent alongside this one arrived while there
		// was no screen to take them. Hand them over now rather than opening blank and waiting for
		// the next periodic resend.
		TerminalClientNetworking.deliverPending(screen);
		return true;
	}

	/**
	 * Gives up an opening the player has covered, and tells the server so.
	 *
	 * <p>The server has considered the terminal open since it sent the snapshot, so dropping the
	 * screen quietly would leave it holding a view nobody is looking at - stale unread state, a
	 * projection kept in sync for no one, a close that never arrives, and a periodic snapshot that
	 * would start the whole opening again on the next sync.
	 *
	 * <p>Sent as {@link TerminalControlPayload#CLOSE_NEVER_SHOWN} rather than as an ordinary close,
	 * because an ordinary close ends an unfinished profile - the player stopped answering. Nothing
	 * was ever asked here, and latching it would take the one-shot questionnaire away from somebody
	 * whose only mistake was opening their inventory in the first second of the run.
	 */
	private static void abandonOpening() {
		abort();
		if (ClientPlayNetworking.canSend(TerminalControlPayload.TYPE)) {
			ClientPlayNetworking.send(new TerminalControlPayload(TerminalControlPayload.CLOSE,
					TerminalControlPayload.CLOSE_NEVER_SHOWN));
		}
	}

	/**
	 * Advances the performance and opens the screen once the terminal has finished coming up.
	 *
	 * <p>Every way the world can pull the item out from under the animation is checked here, because
	 * each of them leaves the animator mid-phase with nothing to animate: the player dying or
	 * disconnecting, and the terminal leaving their hand. Changing dimension replaces the level and
	 * is caught by the same null check on the way through.</p>
	 */
	public static void clientTick() {
		Minecraft client = Minecraft.getInstance();
		if (state == State.IDLE) return;
		if (client.player == null || client.level == null) {
			abort();
			return;
		}
		if (!holdingTerminal(client)) {
			if (client.screen instanceof TerminalScreen terminal) terminal.onClose();
			abort();
			return;
		}
		// Covered mid-rise. Stopping here rather than at the end of the performance means the device
		// goes back to rest instead of finishing an opening whose last act would be to take a screen
		// it is not allowed to have.
		if (state == State.OPENING && client.screen != null
				&& !(client.screen instanceof TerminalScreen)) {
			abandonOpening();
			return;
		}
		long elapsed = now() - phaseStartedAtMillis;
		if (state == State.OPENING && elapsed >= TerminalHandheldPose.OPEN_MILLIS) {
			state = State.OPEN;
			if (!presentScreen()) {
				abandonOpening();
				return;
			}
		} else if (state == State.CLOSING && elapsed >= TerminalHandheldPose.CLOSE_MILLIS) {
			state = State.IDLE;
		} else if (state == State.OPEN && !(client.screen instanceof TerminalScreen)) {
			// The screen went away by a route that does not run onClose - a death screen, a level
			// change. Bring the terminal back down rather than leaving it welded to the lens.
			requestClose();
		}
	}

	private static boolean holdingTerminal(Minecraft client) {
		return client.player.getMainHandItem().is(ModItems.OLD_TERMINAL)
				|| client.player.getOffhandItem().is(ModItems.OLD_TERMINAL);
	}

	/** Where the two-handed device sits this instant, and where the hands holding it go. */
	public static TerminalHandheldPose.Presentation presentation() {
		return presentation(0.0F);
	}

	/**
	 * The same, for a hand that is mid-swing.
	 *
	 * @param swing what {@link #swingShown} returned this frame, not vanilla's raw progress
	 */
	public static TerminalHandheldPose.Presentation presentation(float swing) {
		return TerminalHandheldPose.presentation(openness(), now(), swing);
	}

	/**
	 * How much of vanilla's swing the two-handed pose is allowed to show.
	 *
	 * <p>Opening the terminal is a swing as far as the client is concerned. {@code UseItemCallback}
	 * answers {@code InteractionResult.SUCCESS} so the open request reaches the server, that result
	 * carries {@code SwingSource.CLIENT}, and {@code Minecraft.startUseItem} therefore calls
	 * {@code LocalPlayer.swing} on the same frame the raise begins. Left alone, every open would
	 * punch the device forward and down for six ticks while it was travelling up - two gestures
	 * describing opposite motions, played at once, on the one object the player is looking at.
	 *
	 * <p>So the performance wins: while the device is doing anything of its own, no swing is drawn.
	 * The window covers the whole of vanilla's swing several times over - opening alone runs
	 * {@value TerminalHandheldPose#OPEN_MILLIS} ms against a six-tick swing - and the open is the
	 * only thing that starts one during it, because there is a page over the screen for the rest.
	 *
	 * <p>Every other swing is the player's own and is passed straight through: mining, punching, and
	 * right-clicking a block all leave the animator idle, and all three are motions the terminal
	 * should visibly take part in.
	 *
	 * @param attackAnim vanilla's 0..1 swing progress for the main hand
	 */
	public static float swingShown(float attackAnim) {
		return state == State.IDLE ? Math.clamp(attackAnim, 0.0F, 1.0F) : 0.0F;
	}

	/**
	 * The stack to <em>draw</em> this frame: the held one, or its lamp-off twin while the blink is
	 * dark.
	 *
	 * <p>The unread lamp on the device is not a colour that can be dimmed - it is baked into the
	 * atlas, and the odd form is the even one with the lamp lit. So blinking it means drawing the
	 * even form for part of the cycle, which means the model index has to change.
	 *
	 * <p><b>On a copy, never on the player's stack.</b> Writing {@code custom_model_data} at the blink
	 * rate would be a component write several times a second, and vanilla replays the equip animation
	 * whenever the visible stack changes - the device would drop out of the hands and climb back for
	 * as long as anything was unread. The copy is handed straight to {@code renderItem} and dropped;
	 * the inventory, the server and vanilla's swap detection never see it.
	 *
	 * <p>Cached on the source form so a steady lamp costs no allocation per frame, and rebuilt when
	 * the real form moves - a stage change, or the lamp going out for good.
	 */
	public static ItemStack displayedStack(ItemStack held) {
		CustomModelData data = held.get(DataComponents.CUSTOM_MODEL_DATA);
		if (data == null || data.floats().isEmpty()) return held;
		int form = Math.round(data.floats().getFirst());
		// Even forms have no lit lamp, so there is nothing to blink and nothing to copy.
		if (form % 2 == 0 || TerminalUiLayout.unreadLampBlinkOn(now() / 50.0D)) return held;
		if (darkForm != form || darkStack == null || !ItemStack.isSameItem(darkStack, held)) {
			darkForm = form;
			darkStack = held.copy();
			darkStack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
					List.of((float) (form - 1)), List.of(), List.of(), List.of()));
		}
		return darkStack;
	}

	private static ItemStack darkStack;
	private static int darkForm = -1;

	/** The adjustment for a one-handed carry, used when the off hand is not free. */
	public static TerminalHandheldPose.Carried carried(boolean rightHand) {
		return TerminalHandheldPose.carried(now(), rightHand);
	}

	/** Multiplier the camera's field of view is scaled by. Exactly 1 whenever the terminal is down. */
	public static float fovScale() {
		if (state == State.IDLE) return 1.0F;
		return TerminalHandheldPose.presentation(openness(), now()).fovScale();
	}

	/**
	 * Multiplier for vanilla's walk bob while the terminal is in the main hand.
	 *
	 * <p>Exactly 1 for every other item, so this only ever damps the one object it is about. Read
	 * per frame by {@code GameRendererTerminalBobMixin}.
	 */
	public static float viewBobScale() {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || !client.player.getMainHandItem().is(ModItems.OLD_TERMINAL)) {
			return 1.0F;
		}
		return TerminalHandheldPose.viewBobScale(openness());
	}

	/** 0 at rest, 1 filling the frame, eased so the travel settles rather than stopping dead. */
	public static double openness() {
		// A one-handed open never raises the device, so it never leaves rest however far the state
		// machine has gone. Without this the FOV would step to its closed-in value on the frame the
		// page appears, which is the one jump the eased travel exists to avoid.
		if (performanceSkipped) return 0.0D;
		long elapsed = now() - phaseStartedAtMillis;
		return switch (state) {
			case IDLE -> 0.0D;
			case OPEN -> 1.0D;
			case OPENING -> TerminalHandheldPose.openness(true, elapsed);
			case CLOSING -> TerminalHandheldPose.openness(false, elapsed);
		};
	}

	/**
	 * A phase start that makes a reversal continue from the current openness instead of restarting.
	 *
	 * <p>Inverts the eased curve: the new phase is backdated by however long it would have taken to
	 * reach the openness the device is already at.</p>
	 */
	private static long reversedPhaseStart(boolean opening) {
		double current = Math.clamp(openness(), 0.0D, 1.0D);
		double reached = opening ? current : 1.0D - current;
		double linear = 1.0D - Math.sqrt(1.0D - reached);
		long duration = opening ? TerminalHandheldPose.OPEN_MILLIS : TerminalHandheldPose.CLOSE_MILLIS;
		return now() - Math.round(linear * duration);
	}

	private static long now() {
		return System.nanoTime() / 1_000_000L;
	}

	static long phaseStartedAtMillisForTesting() { return phaseStartedAtMillis; }

	/** Puts the animator back the way a fresh client starts, so tests do not leak into each other. */
	static void resetForTesting() {
		pendingSnapshot = null;
		state = State.IDLE;
		performanceSkipped = false;
		phaseStartedAtMillis = 0L;
		darkStack = null;
		darkForm = -1;
	}
}
