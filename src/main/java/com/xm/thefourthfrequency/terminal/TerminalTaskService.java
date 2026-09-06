package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.world.FrequencyWorldData;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Server-authoritative task order, completion checks, and reward delivery. */
public final class TerminalTaskService {
	public static final int PAGE_COUNT = 4;
	public static final int ALL_PAGES_MASK = (1 << PAGE_COUNT) - 1;

	/**
	 * The objective list, and which of them a team clears once rather than once each.
	 *
	 * <p>The gathering objectives cost every player their own trip: eight people bring eight lots of
	 * iron, so eight lots of torches is the same trade the first player made. The six marked
	 * {@code shared} are not like that — one nether portal, one fortress, one stronghold, one boss.
	 * The party walks through the same door and every one of them completes the objective, so paying
	 * each of them in full multiplies a single piece of work by the headcount. The boss makes the
	 * mismatch plainest: {@link WorldInterfacePolicy#maxHealth} scales it by
	 * {@code 1 + 0.5 × (n − 1)} — 4.5× at a full roster — while eight untouched payouts would be 8×.
	 * Those six therefore split one payout between the players present; see
	 * {@link #sharedRewardCount}.</p>
	 */
	private static final List<TaskDefinition> TASKS = List.of(
			new TaskDefinition("learn_terminal", PAGE_COUNT, Items.BREAD, 6, false),
			new TaskDefinition("mine_logs", SurvivalProgressService.REQUIRED_WOOD, Items.STONE_AXE, 1, false),
			new TaskDefinition("bring_iron", SurvivalProgressService.REQUIRED_IRON, Items.TORCH, 24, false),
			new TaskDefinition("enter_nether", 1, Items.COOKED_BEEF, 8, true),
			// Between arriving and the rods, because the rods are inside the thing this one asks for.
			// A player told to collect blaze rods with nothing in between has to already know that
			// blazes only spawn in fortresses; the terminal is supposed to be the reason they know it.
			new TaskDefinition("find_fortress", 1, Items.GOLDEN_CARROT, 4, true),
			new TaskDefinition("collect_blaze_rods", SurvivalProgressService.REQUIRED_BLAZE_RODS,
					Items.GOLDEN_CARROT, 8, false),
			new TaskDefinition("return_from_nether", 1, Items.ENDER_PEARL, 4, true),
			// Nothing between the Nether and the stronghold. Two objectives used to sit here - craft
			// four eyes, then throw three - and both were the same mistake: a job the party does once,
			// written as a target each player has to hit separately. A portal frame takes twelve eyes
			// between everybody, so asking eight players for four apiece is thirty-two; there is one
			// stronghold, so asking each of them to triangulate it is twenty-four pearls spent
			// establishing a fact they were all standing next to each other for. The vanilla item
			// teaches both steps on its own. Neither count is gone - SurvivalProgressService still
			// records them, StoryProgressService still gates the stronghold tool on the throws, and
			// TerminalToolService shares a completed fix with the rest of the party.
			new TaskDefinition("find_stronghold", 1, Items.GOLDEN_APPLE, 2, true),
			new TaskDefinition("enter_end", 1, Items.ARROW, 32, true),
			new TaskDefinition("defeat_boss", 1, Items.DIAMOND, 4, true));

	private TerminalTaskService() {
	}

	/**
	 * The nominal objective, priced for a single player.
	 *
	 * <p>Its {@code rewardCount} is the solo figure. Anything the player will actually see or be
	 * handed must go through {@link #current(CompoundTag, int)} instead, or a shared objective would
	 * be advertised at one number and paid at another — exactly the "actionable information that
	 * silently goes wrong" the world bible rules out. Safe here for {@code claimable} and
	 * {@code index}, which do not depend on headcount.</p>
	 */
	public static TaskSnapshot current(CompoundTag tag) {
		return current(tag, 1);
	}

	/** The objective as it stands for a party of {@code participants}, rewards already divided. */
	public static TaskSnapshot current(CompoundTag tag, int participants) {
		int claimed = tag.getIntOr(TerminalData.TASK_REWARD_CLAIMED_MASK, 0);
		for (int index = 0; index < TASKS.size(); index++) {
			if ((claimed & 1 << index) != 0) continue;
			TaskDefinition definition = TASKS.get(index);
			int target = targetFor(definition, tag);
			int progress = Math.clamp(progress(definition.id(), tag), 0, target);
			return new TaskSnapshot(index, definition.id(), progress, target,
					BuiltInRegistries.ITEM.getKey(definition.reward()).toString(),
					payoutCount(index, participants), progress >= target);
		}
		return new TaskSnapshot(TASKS.size(), "complete", 1, 1, "minecraft:air", 0, false);
	}

	/**
	 * What one player is actually handed for {@code taskIndex} with {@code participants} on the
	 * server. Identical to the authored figure for the gathering objectives, and for everyone alone.
	 */
	public static int payoutCount(int taskIndex, int participants) {
		if (taskIndex < 0 || taskIndex >= TASKS.size()) return 0;
		TaskDefinition task = TASKS.get(taskIndex);
		return task.shared() ? sharedRewardCount(task.rewardCount(), participants) : task.rewardCount();
	}

	/**
	 * One payout divided between the players who cleared the objective together.
	 *
	 * <p>Rounded rather than floored, and never below one: an objective that completes and hands over
	 * nothing reads as a bug, and the terminal saying "task complete" next to an empty reward frame
	 * is worse than a little inflation. So a full roster still costs slightly more than a solo run —
	 * eight diamonds instead of four, rather than the thirty-two eight untouched payouts would be.</p>
	 */
	public static int sharedRewardCount(int baseCount, int participants) {
		if (baseCount <= 0) return 0;
		return Math.max(1, Math.round(baseCount / (float) Math.max(1, participants)));
	}

	/**
	 * Who a shared payout is divided between: online, not spectating, and holding a bound terminal.
	 *
	 * <p>Read at the moment of delivery rather than stored. Nothing here is frozen, so a player who
	 * has left stops diluting the split, and the count can never reach zero and divide by nothing.</p>
	 */
	public static int rewardParticipants(MinecraftServer server) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		int present = 0;
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (online.isSpectator()) continue;
			if (data.terminalRecord(online.getUUID())
					.map(record -> record.getBooleanOr(TerminalData.BOUND, false)).orElse(false)) present++;
		}
		return Math.max(1, present);
	}

	/**
	 * Who a shared payout for <em>this</em> objective is divided between: the party members who are
	 * actually on it, or already past it.
	 *
	 * <p>The split used to be taken over everyone bound and online, which made it wrong in the one
	 * direction that costs something. A player three objectives behind - somebody who just joined,
	 * somebody idling at the station - was counted as sharing a Nether trip they had not taken, so
	 * the person who did take it received a third of the payout and the other two thirds went to
	 * nobody. The reward for a shared objective got smaller the more people were <em>not</em> doing
	 * it.
	 *
	 * <p>Counting only players at or past the objective is what "the players who cleared it together"
	 * actually means. A table walking through one portal is all at that index and splits it evenly; a
	 * lone player whose friends are still gathering iron is a party of one and is paid in full.
	 *
	 * <p>A persisted per-task pot was the alternative and does not work: conserving a total requires
	 * knowing how many people will eventually complete, and the reward has to arrive at the instant
	 * the objective does - "completion and its reward are one moment". Anything that holds the payout
	 * back to find out is a worse trade than the small overshoot when a straggler completes later.
	 */
	public static int rewardParticipants(MinecraftServer server, int taskIndex) {
		FrequencyWorldData data = FrequencyWorldData.get(server);
		int present = 0;
		for (ServerPlayer online : server.getPlayerList().getPlayers()) {
			if (online.isSpectator()) continue;
			CompoundTag record = data.terminalRecord(online.getUUID()).orElse(null);
			if (record == null || !record.getBooleanOr(TerminalData.BOUND, false)) continue;
			if (current(record).index() >= taskIndex) present++;
		}
		return Math.max(1, present);
	}

	public static boolean hasClaimableReward(CompoundTag tag) {
		return current(tag).claimable();
	}

	public static int consumeCompletionAlert(CompoundTag tag) {
		TaskSnapshot task = current(tag);
		int mask = tag.getIntOr(TerminalData.TASK_COMPLETION_NOTIFIED_MASK, 0);
		int completed = TerminalAttentionPolicy.completionToNotify(
				task.index(), task.claimable(), TASKS.size(), mask);
		if (completed < 0) return -1;
		tag.putInt(TerminalData.TASK_COMPLETION_NOTIFIED_MASK,
				TerminalAttentionPolicy.markCompletionNotified(mask, completed));
		return completed;
	}

	/**
	 * Delivers every reward the player has completed but not yet been given, newest task last.
	 *
	 * <p>This is the only path that hands out a task reward. It used to share the job with a manual
	 * "claim" button on the home card, and the two could not agree on when a reward was owed: rewards
	 * sat undelivered until the player happened to open a page or trip a signal event, so pressing
	 * the card was sometimes the only way to get them and sometimes did nothing at all. Completion
	 * and its reward are one moment - so the trigger belongs wherever progress changes, which is what
	 * {@code SurvivalProgressService} now calls.</p>
	 *
	 * <p>The loop is for catching up (an old save, or several milestones crossed in one tick), not for
	 * chaining off a player action.</p>
	 */
	public static boolean notifyIfCompleted(ServerPlayer player) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return false;
		boolean delivered = false;
		while (true) {
			CompoundTag record = data.terminalRecord(player.getUUID()).orElse(null);
			if (record == null) break;
			// Per objective, inside the loop: a catch-up run pays several tasks in one pass and the
			// party that cleared each of them is not the same set. The index does not depend on the
			// headcount, so it is safe to read it first and price the snapshot afterwards.
			int participants = rewardParticipants(player.level().getServer(), current(record).index());
			TaskSnapshot task = current(record, participants);
			if (!task.claimable() || task.rewardCount() <= 0 || task.index() >= TASKS.size()) break;
			ItemStack reward = new ItemStack(TASKS.get(task.index()).reward(), task.rewardCount());
			int sharedBetween = TASKS.get(task.index()).shared() ? participants : 0;
			Component rewardName = reward.getHoverName();
			int rewardCount = reward.getCount();
			deliverReward(player, reward);
			int[] completed = {-1};
			data.updateTerminalRecord(player.getUUID(), tag -> {
				int taskBit = 1 << task.index();
				completed[0] = consumeCompletionAlert(tag);
				tag.putInt(TerminalData.TASK_REWARD_CLAIMED_MASK,
						tag.getIntOr(TerminalData.TASK_REWARD_CLAIMED_MASK, 0) | taskBit);
			});
			TerminalNoticeService.rewardClaimed(player, taskName(task), rewardName,
					rewardCount, completed[0] >= 0, sharedBetween);
			delivered = true;
		}
		if (!delivered) return false;
		TerminalRuntimeService.synchronizeProjection(player, data);
		TerminalRuntimeService.refresh(player);
		return true;
	}

	public static boolean visitPage(ServerPlayer player, int pageIndex) {
		if (pageIndex < 0 || pageIndex >= PAGE_COUNT) return false;
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		CompoundTag before = data.terminalRecord(player.getUUID()).orElse(null);
		if (before == null) return false;
		int oldMask = before.getIntOr(TerminalData.TERMINAL_PAGE_VISIT_MASK, 0) & ALL_PAGES_MASK;
		int newMask = oldMask | 1 << pageIndex;
		if (newMask == oldMask) return true;
		boolean attentionBefore = hasClaimableReward(before);
		data.updateTerminalRecord(player.getUUID(), tag -> {
			tag.putInt(TerminalData.TERMINAL_PAGE_VISIT_MASK, newMask);
			latchOnboarding(tag, newMask);
		});
		CompoundTag after = data.terminalRecord(player.getUUID()).orElse(before);
		if (attentionBefore != hasClaimableReward(after)) {
			TerminalRuntimeService.synchronizeAttentionProjection(player, data);
		}
		notifyIfCompleted(player);
		return true;
	}

	/**
	 * Closes the first-boot walkthrough the moment all four tabs have been visited.
	 *
	 * <p>The walkthrough and {@code learn_terminal} finish at the same instant by construction: it
	 * has no completion signal of its own, it simply walks the player through the four visits the
	 * task already counts. So the client never reports "I finished" - it only ever sends the same
	 * page visits a player clicking the tabs themselves would send, and this latch closes behind
	 * them on the server.</p>
	 *
	 * <p>Static and tag-level so {@code TerminalTaskServiceTest} can pin it without a server.</p>
	 */
	static void latchOnboarding(CompoundTag tag, int visitMask) {
		if ((visitMask & ALL_PAGES_MASK) == ALL_PAGES_MASK) {
			tag.putBoolean(TerminalData.ONBOARDING_DONE, true);
		}
	}

	/**
	 * Compatibility entry for the {@code CLAIM_TASK_REWARD} packet, which current clients no longer
	 * send. Rewards are delivered the moment a task completes, so by the time any claim arrives the
	 * task it names is already paid for and the honest answer is {@link ClaimResult#STALE}.
	 *
	 * <p>It deliberately delivers nothing. When it did, a single press paid out the pressed task and
	 * then ran the catch-up loop, dumping every other finished task's reward at once and stacking a
	 * notice for each - and an older client that retried the packet could make that happen twice.</p>
	 */
	public static ClaimResult claim(ServerPlayer player, int expectedTaskIndex) {
		FrequencyWorldData data = FrequencyWorldData.get(player.level().getServer());
		if (data.terminalRecord(player.getUUID()).isEmpty()) return ClaimResult.INVALID;
		// Anything still owed is owed because a trigger was missed, not because this packet arrived.
		notifyIfCompleted(player);
		CompoundTag settled = data.terminalRecord(player.getUUID()).orElse(null);
		if (settled == null) return ClaimResult.INVALID;
		// Nothing is claimable once the pass above has run, so the current task is simply unfinished.
		// A different index means the named task was already paid for before this packet arrived.
		return current(settled).index() == expectedTaskIndex ? ClaimResult.NOT_READY : ClaimResult.STALE;
	}

	public static int taskCount() {
		return TASKS.size();
	}

	/** Index {@code find_fortress} was inserted at. Everything from here on shifted up by one. */
	public static final int FORTRESS_TASK_INDEX = 4;

	/**
	 * Rewrites a per-task bit mask written before {@code find_fortress} existed.
	 *
	 * <p>{@code TASK_REWARD_CLAIMED_MASK} and {@code TASK_COMPLETION_NOTIFIED_MASK} store one bit per
	 * task <em>index</em>, so inserting a task in the middle silently re-points every bit above it at
	 * the wrong task. Left alone, a save that had claimed through {@code collect_blaze_rods} would
	 * read as having claimed {@code find_fortress} instead and be asked to collect the rods a second
	 * time - which pays their reward out again, because the rod count that satisfies the task is
	 * still in the record.
	 *
	 * <p>The new bit is filled from the old {@code collect_blaze_rods} bit rather than left clear:
	 * blazes only spawn in fortresses, so a player who claimed the rods has been inside one, and
	 * sending them back to the Nether to look for a building they have already looted would be the
	 * same mistake pointed the other way. A player who had <em>not</em> got that far keeps an unset
	 * bit and simply picks the new objective up where they are.
	 *
	 * <p>Pure and public so {@code TerminalTaskServiceTest} can pin the shift against the real task
	 * list rather than against a copy of these numbers.
	 */
	public static int migrateMaskForFortressInsert(int legacyMask) {
		int low = legacyMask & ((1 << FORTRESS_TASK_INDEX) - 1);
		int high = (legacyMask & ~((1 << FORTRESS_TASK_INDEX) - 1)) << 1;
		boolean claimedRods = (legacyMask & 1 << FORTRESS_TASK_INDEX) != 0;
		return low | high | (claimedRods ? 1 << FORTRESS_TASK_INDEX : 0);
	}

	/** Index {@code record_eye} occupied before schema 12 retired it. */
	public static final int RETIRED_EYE_TASK_INDEX = 8;

	/**
	 * Closes the gap schema 12 left by retiring {@code record_eye}.
	 *
	 * <p>The inverse of the shift above, and needed for the same reason: these masks store one bit per
	 * task index, so removing one in the middle slides every task above it down by one. Left alone, a
	 * save that had claimed through {@code find_stronghold} would read as having claimed
	 * {@code enter_end} instead and never be paid for the End. The retired task's own bit is dropped
	 * rather than carried - there is nothing left for it to mean.</p>
	 */
	public static int migrateMaskForEyeRemoval(int legacyMask) {
		return closeTaskGap(legacyMask, RETIRED_EYE_TASK_INDEX);
	}

	/** Index {@code craft_eye} occupied before schema 13 retired it. */
	public static final int RETIRED_CRAFT_EYE_TASK_INDEX = 7;

	/**
	 * Closes the gap schema 13 left by retiring {@code craft_eye}.
	 *
	 * <p>The third task to leave this list and the third time the same hazard applies, so the shift
	 * itself now lives in {@link #closeTaskGap}. Crafting eyes was the last objective that asked a
	 * party to each do the same shared-pile job separately: eight players were told to make four eyes
	 * apiece for one portal that needs twelve. The eyes are still needed, of course - the portal and
	 * the stronghold fix both consume them - but needing something is not the same as the terminal
	 * setting it as a target, and this one was only ever a restatement of what the vanilla item
	 * already tells you.</p>
	 */
	public static int migrateMaskForCraftEyeRemoval(int legacyMask) {
		return closeTaskGap(legacyMask, RETIRED_CRAFT_EYE_TASK_INDEX);
	}

	/** Drops one task's bit and slides every higher bit down into the hole it left. */
	private static int closeTaskGap(int legacyMask, int retiredIndex) {
		int low = legacyMask & ((1 << retiredIndex) - 1);
		int high = (legacyMask >>> (retiredIndex + 1)) << retiredIndex;
		return low | high;
	}

	/**
	 * The objective line as it reads at the instant the task is finished, progress included.
	 *
	 * <p>The key is assembled from the task id, the same way {@code TerminalSnapshot#objectiveLine}
	 * builds the card's own line - the eleven of them are the one family where a per-case switch
	 * would be eleven copies of the same string.</p>
	 */
	public static Component completedObjectiveLine(String id, int target) {
		return Component.translatable("terminal.thefourthfrequency.objective." + id, target, target);
	}

	/**
	 * The task's name on its own - no instruction, no counter.
	 *
	 * <p>It exists for the completion notice, which is what answers "why did I just get this" when
	 * the terminal is closed. A reward arriving unprompted names an item and nothing else, and the
	 * first task pays out while the player is still inside the first-boot walkthrough, so six bread
	 * appeared for what looked from their side like clicking four tabs to dismiss an animation.</p>
	 *
	 * <p>A separate string rather than the objective line, because the objective line carries the
	 * instruction and the progress with it - "认识终端：点击四个顶部标签 4/4" is a fine thing to read
	 * on a card you are already looking at, and far too long for a line that has to fit above the
	 * hotbar next to the item and its count.</p>
	 *
	 * <p>Written out per case rather than assembled from the id, so the contract test that checks
	 * every translation key exists can see them; a key built at runtime would slip past it and could
	 * go missing without anything failing. Falls back to the full objective line for the terminal
	 * task, which has no reward and therefore never reaches a notice.</p>
	 */
	public static Component taskName(TaskSnapshot task) {
		String key = switch (task.id()) {
			case "learn_terminal" -> "terminal.thefourthfrequency.task.name.learn_terminal";
			case "mine_logs" -> "terminal.thefourthfrequency.task.name.mine_logs";
			case "bring_iron" -> "terminal.thefourthfrequency.task.name.bring_iron";
			case "enter_nether" -> "terminal.thefourthfrequency.task.name.enter_nether";
			case "find_fortress" -> "terminal.thefourthfrequency.task.name.find_fortress";
			case "collect_blaze_rods" -> "terminal.thefourthfrequency.task.name.collect_blaze_rods";
			case "return_from_nether" -> "terminal.thefourthfrequency.task.name.return_from_nether";
			case "find_stronghold" -> "terminal.thefourthfrequency.task.name.find_stronghold";
			case "enter_end" -> "terminal.thefourthfrequency.task.name.enter_end";
			case "defeat_boss" -> "terminal.thefourthfrequency.task.name.defeat_boss";
			default -> null;
		};
		return key == null ? completedObjectiveLine(task.id(), task.target()) : Component.translatable(key);
	}

	public static ItemStack rewardStack(int taskIndex) {
		if (taskIndex < 0 || taskIndex >= TASKS.size()) return ItemStack.EMPTY;
		TaskDefinition task = TASKS.get(taskIndex);
		return new ItemStack(task.reward(), task.rewardCount());
	}

	private static int progress(String id, CompoundTag tag) {
		int milestones = tag.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0);
		return switch (id) {
			case "learn_terminal" -> Integer.bitCount(
					tag.getIntOr(TerminalData.TERMINAL_PAGE_VISIT_MASK, 0) & ALL_PAGES_MASK);
			case "mine_logs" -> tag.getIntOr(TerminalData.WOOD_MINED_COUNT, 0);
			case "bring_iron" -> tag.getIntOr(TerminalData.IRON_SAMPLE_COUNT, 0);
			case "enter_nether" -> completed(milestones, SurvivalMilestone.ENTERED_NETHER);
			case "find_fortress" -> completed(milestones, SurvivalMilestone.FOUND_FORTRESS);
			case "collect_blaze_rods" -> tag.getIntOr(TerminalData.BLAZE_ROD_SAMPLE_COUNT, 0);
			case "return_from_nether" -> completed(milestones, SurvivalMilestone.RETURNED_NETHER);
			case "craft_eye" -> tag.getIntOr(TerminalData.CRAFTED_EYE_COUNT, 0);
			case "find_stronghold" -> completed(milestones, SurvivalMilestone.FOUND_STRONGHOLD);
			case "enter_end" -> completed(milestones, SurvivalMilestone.ENTERED_END);
			case "defeat_boss" -> completed(milestones, SurvivalMilestone.DEFEATED_BOSS);
			default -> 0;
		};
	}

	/**
	 * The target this record is actually held to, which for the rods is a function of the party.
	 *
	 * <p>Read off the record rather than passed in, so every consumer of a snapshot - the card, the
	 * claimable check, the completion alert - lands on one number without having to be handed a
	 * headcount. {@code SurvivalProgressService} owns writing it.
	 */
	private static int targetFor(TaskDefinition definition, CompoundTag tag) {
		if (!"collect_blaze_rods".equals(definition.id())) return definition.target();
		return Math.clamp(tag.getIntOr(TerminalData.BLAZE_ROD_REQUIRED,
						SurvivalProgressService.REQUIRED_BLAZE_RODS),
				SurvivalProgressService.MINIMUM_BLAZE_RODS, SurvivalProgressService.REQUIRED_BLAZE_RODS);
	}

	private static int completed(int milestones, SurvivalMilestone milestone) {
		return milestone.present(milestones) ? 1 : 0;
	}

	private static void deliverReward(ServerPlayer player, ItemStack reward) {
		player.getInventory().add(reward);
		if (!reward.isEmpty()) player.drop(reward, false);
	}

	private record TaskDefinition(String id, int target, Item reward, int rewardCount, boolean shared) {
	}

	public record TaskSnapshot(
			int index,
			String id,
			int progress,
			int target,
			String rewardItemId,
			int rewardCount,
			boolean claimable
	) {
		public double fraction() {
			return target <= 0 ? 0.0D : Math.clamp(progress / (double) target, 0.0D, 1.0D);
		}
	}

	public enum ClaimResult {
		CLAIMED,
		NOT_READY,
		INVENTORY_FULL,
		STALE,
		INVALID
	}
}
