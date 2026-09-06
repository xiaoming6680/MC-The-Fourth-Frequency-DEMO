package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.networking.TerminalNoticePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Routes mod feedback into the client-side bounded notice stack. */
public final class TerminalNoticeService {
	/**
	 * How long one refusal stays spent, in milliseconds.
	 *
	 * <p>Refusals are the only channel a player can fire deliberately and repeatedly: the drop key
	 * repeats while held, and throwing a bound terminal out of the inventory screen is a click
	 * somebody makes several times in a row when the item keeps coming back. Every attempt used to
	 * push its own line, so the stack filled with the same sentence and read as two or three
	 * different things having gone wrong.
	 *
	 * <p>A second and a half is longer than any repeat and shorter than any two deliberate tries.
	 */
	private static final long REFUSAL_COOLDOWN_MILLIS = 1_500L;

	/** Last time each player was told a given thing was refused. */
	private static final java.util.Map<java.util.UUID, java.util.Map<String, Long>> REFUSED =
			new java.util.concurrent.ConcurrentHashMap<>();

	private TerminalNoticeService() {
	}

	/** Drops a player's refusal history. Called when they leave, so the map cannot grow forever. */
	public static void forget(java.util.UUID playerId) {
		if (playerId != null) REFUSED.remove(playerId);
	}

	public static void send(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_NONE);
	}

	/**
	 * Standing somewhere the near-field receiver can be tuned in.
	 *
	 * <p>Shares the unread tone deliberately. It is the same statement - the terminal has something
	 * for you - and giving it a sound of its own would make a quiet optional thread announce itself
	 * more loudly than the mainline does.
	 */
	public static void tunableSignal(ServerPlayer player) {
		send(player, Component.translatable("message.thefourthfrequency.fragment.tune_here"),
				TerminalNoticePayload.TONE_UNREAD);
	}

	/**
	 * The nudge that something is waiting, and - for a player being explained to - how to go and read
	 * it.
	 *
	 * <p>"You have 2 unread records" names a place the player may not know how to reach. The terminal
	 * is opened by holding the bound one and right-clicking, which is discoverable but is not
	 * something the mod says anywhere outside the walkthrough, and this reminder is the one line that
	 * fires precisely when somebody has not been opening it.
	 *
	 * <p>Gated on verbosity rather than on a once-only latch, because this is not a definition that
	 * stops being news. A player who keeps not opening the terminal keeps needing the second half; a
	 * player who said they know this device already never does.
	 */
	public static void unreadReminder(ServerPlayer player, int unreadCount,
			TerminalGuidanceVerbosity verbosity) {
		send(player, Component.translatable(verbosity != null && verbosity.explains()
						? "message.thefourthfrequency.terminal.unread_reminder.verbose"
						: "message.thefourthfrequency.terminal.unread_reminder", unreadCount),
				TerminalNoticePayload.TONE_UNREAD);
	}

	/**
	 * Completion and its reward are one moment, so they are one line. A separate "task complete"
	 * notice only ever preceded this one and carried nothing the reward text does not already imply.
	 *
	 * <p>The completion half names the task that was finished, because the reward is delivered
	 * without the player pressing anything: "claimed bread ×6" on its own is an effect with no
	 * stated cause, and the player who has just been walked through four tabs has no reason to
	 * connect the two. The name only - {@code TerminalTaskService#taskName}, not the objective line -
	 * so the whole thing stays one line above the hotbar. A catch-up payout on an old save keeps the
	 * short form: there is no single moment it belongs to, so naming one task would be picking one
	 * arbitrarily.</p>
	 */
	/**
	 * @param sharedBetween how many players this objective's single payout was divided between, or
	 *                      zero when it was not divided. A shrunk stack with no explanation reads as
	 *                      the reward being broken, so the split says so on the one line that
	 *                      announces it.
	 */
	public static void rewardClaimed(ServerPlayer player, Component taskName, Component rewardName,
			int rewardCount, boolean completedNow, int sharedBetween) {
		boolean split = sharedBetween > 1;
		send(player, completedNow
				? (split
						? Component.translatable("message.thefourthfrequency.task.completed_reward_shared",
								taskName, rewardName, rewardCount, sharedBetween)
						: Component.translatable("message.thefourthfrequency.task.completed_reward_claimed",
								taskName, rewardName, rewardCount))
				: (split
						? Component.translatable("message.thefourthfrequency.task.reward_shared",
								rewardName, rewardCount, sharedBetween)
						: Component.translatable("message.thefourthfrequency.task.reward_claimed",
								rewardName, rewardCount)),
				TerminalNoticePayload.TONE_TASK_COMPLETE);
	}

	/** Feedback for an action the mod refused; presented and sounded apart from progress notices. */
	public static void denied(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_DENIED);
	}

	/**
	 * A refusal, at most once per {@link #REFUSAL_COOLDOWN_MILLIS} for the same reason.
	 *
	 * <p>Keyed on the message rather than on the player alone, so two different refusals arriving
	 * together still both get said. What is suppressed is the same sentence twice - which is not
	 * information, it is the stack repeating itself.
	 */
	public static void denied(ServerPlayer player, String messageKey) {
		if (player == null || messageKey == null) return;
		long now = System.currentTimeMillis();
		Long last = REFUSED.computeIfAbsent(player.getUUID(),
				ignored -> new java.util.concurrent.ConcurrentHashMap<>()).put(messageKey, now);
		if (last != null && now - last < REFUSAL_COOLDOWN_MILLIS) return;
		denied(player, Component.translatable(messageKey));
	}

	/** Encounter narration: what the fight itself is doing. */
	public static void encounter(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_ENCOUNTER);
	}

	/** Anchor pressure, which is the one channel the table can act on directly. */
	public static void anchor(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_ANCHOR);
	}

	public static void dragon(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_DRAGON);
	}

	/**
	 * The shake, and nothing else.
	 *
	 * <p>Deliberately identical for all five forms, and it must stay that way. What the corrector is
	 * and how this one tracks belongs on the records page, which the terminal force-opens on the next
	 * time it is raised - see {@code PURSUIT_WARNING_RECORDS_REDIRECT}. That ordering is the world
	 * bible's rule that the explanation arrives after the event, and splitting this line by form would
	 * quietly move the explanation in front of it.
	 *
	 * <p>{@code ResourceContractTest} asserts that no per-form variant of this key exists.
	 */
	public static void pursuitWarning(ServerPlayer player) {
		pursuit(player, Component.translatable("message.thefourthfrequency.pursuit.warning"));
	}

	public static void pursuit(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_PURSUIT_WARNING);
	}

	/** The one readout the terminal offers inside the unrendered layer. */
	public static void unrendered(ServerPlayer player, Component message) {
		send(player, message, TerminalNoticePayload.TONE_UNRENDERED);
	}

	private static void send(ServerPlayer player, Component message, int tone) {
		if (ServerPlayNetworking.canSend(player, TerminalNoticePayload.TYPE)) {
			ServerPlayNetworking.send(player, new TerminalNoticePayload(message, tone));
		} else {
			// Dedicated GameTests and non-modded diagnostic connections retain readable fallback feedback.
			player.displayClientMessage(message, true);
		}
	}
}
