package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.persistence.PersistenceSchema;
import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalTaskServiceTest {
	@BeforeAll
	static void bootstrapRegistries() {
		// Items.BREAD / Items.STONE_AXE below trigger BuiltInRegistries's <clinit>, which asserts
		// that the vanilla bootstrap has run, and that in turn needs the game version detected
		// (DataFixers reads it). Outside a running game or GameTest environment nothing does this
		// for us, so the plain unit-test JVM needs it done explicitly once.
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void fourthTabVisitLatchesTheWalkthroughClosed() {
		CompoundTag record = new CompoundTag();
		TerminalTaskService.latchOnboarding(record, 0b0111);
		assertFalse(record.getBooleanOr(TerminalData.ONBOARDING_DONE, false),
				"three tabs is not the end of the walkthrough");

		TerminalTaskService.latchOnboarding(record, TerminalTaskService.ALL_PAGES_MASK);
		assertTrue(record.getBooleanOr(TerminalData.ONBOARDING_DONE, false));

		// One-way: a mask that somehow narrows again must not reopen it. The walkthrough is a thing
		// that happened to this player, not a view of the current task progress.
		TerminalTaskService.latchOnboarding(record, 0b0001);
		assertTrue(record.getBooleanOr(TerminalData.ONBOARDING_DONE, false));
	}

	@Test
	void savesThatAlreadyUsedTheTerminalDoNotReplayTheWalkthrough() {
		// migrateRecord copies rather than mutating, so each case reads the value it returns.
		CompoundTag untouched = TerminalData.migrateRecord(new CompoundTag());
		assertFalse(untouched.getBooleanOr(TerminalData.ONBOARDING_DONE, true),
				"a record with no history at all still owes the walkthrough");

		CompoundTag visitedSource = new CompoundTag();
		visitedSource.putInt(TerminalData.TERMINAL_PAGE_VISIT_MASK, 0b0011);
		assertTrue(TerminalData.migrateRecord(visitedSource)
						.getBooleanOr(TerminalData.ONBOARDING_DONE, false),
				"a player who already opened tabs must not be walked through them again");

		CompoundTag claimedSource = new CompoundTag();
		claimedSource.putInt(TerminalData.TASK_REWARD_CLAIMED_MASK, 0b1);
		assertTrue(TerminalData.migrateRecord(claimedSource)
						.getBooleanOr(TerminalData.ONBOARDING_DONE, false),
				"a player who already earned a task reward is well past the walkthrough");
	}

	@Test
	void firstTaskRequiresAllFourExplicitTabVisits() {
		CompoundTag record = new CompoundTag();
		assertEquals("learn_terminal", TerminalTaskService.current(record).id());
		assertEquals(0, TerminalTaskService.current(record).progress());
		assertFalse(TerminalTaskService.current(record).claimable());

		record.putInt(TerminalData.TERMINAL_PAGE_VISIT_MASK, 0b0111);
		assertEquals(3, TerminalTaskService.current(record).progress());
		assertFalse(TerminalTaskService.current(record).claimable());

		record.putInt(TerminalData.TERMINAL_PAGE_VISIT_MASK, TerminalTaskService.ALL_PAGES_MASK);
		assertEquals(4, TerminalTaskService.current(record).progress());
		assertTrue(TerminalTaskService.current(record).claimable());
		assertTrue(TerminalTaskService.hasClaimableReward(record));
		assertTrue(TerminalTaskService.rewardStack(0).is(Items.BREAD));
		assertEquals(6, TerminalTaskService.rewardStack(0).getCount());
	}

	@Test
	void claimingMaskAdvancesToRaisedWoodThreshold() {
		CompoundTag record = new CompoundTag();
		record.putInt(TerminalData.TERMINAL_PAGE_VISIT_MASK, TerminalTaskService.ALL_PAGES_MASK);
		record.putInt(TerminalData.TASK_REWARD_CLAIMED_MASK, 1);
		record.putInt(TerminalData.WOOD_MINED_COUNT, SurvivalProgressService.REQUIRED_WOOD - 1);

		var wood = TerminalTaskService.current(record);
		assertEquals("mine_logs", wood.id());
		assertEquals(12, wood.target());
		assertFalse(wood.claimable());

		record.putInt(TerminalData.WOOD_MINED_COUNT, SurvivalProgressService.REQUIRED_WOOD);
		assertTrue(TerminalTaskService.current(record).claimable());
		assertTrue(TerminalTaskService.rewardStack(1).is(Items.STONE_AXE));
	}

	@Test
	void quantitativeTargetsUseTheRaisedCompletionRequirements() {
		assertEquals(12, SurvivalProgressService.REQUIRED_WOOD);
		assertEquals(6, SurvivalProgressService.REQUIRED_IRON);
		// Lowered from eight: rods are the one target counted per player rather than from a shared
		// pile, so a group multiplied the work instead of dividing it. See REQUIRED_BLAZE_RODS.
		assertEquals(3, SurvivalProgressService.REQUIRED_BLAZE_RODS);
		assertEquals(3, SurvivalProgressService.REQUIRED_STRONGHOLD_UNLOCK_EYES);
		// Still counted and still needed for the portal, but no longer an objective the terminal
		// names: the vanilla recipe already teaches what an eye is for.
		assertEquals(4, SurvivalProgressService.REQUIRED_CRAFTED_EYES);
		// Still three, and still required - the stronghold tool will not give a full fix below it.
		// It is simply no longer an objective the terminal asks for; holding eyes of ender says it.
		assertEquals(3, SurvivalProgressService.REQUIRED_EYE_SAMPLES);
		assertEquals(10, TerminalTaskService.taskCount());
	}

	/**
	 * The rods are the one target a party makes easier, and they may only ever get easier.
	 *
	 * <p>Wood and iron stay flat because they are early, cheap and passed through incidentally. Rods
	 * are late, specific and counted per player, so a fixed three meant a table of four needed twelve
	 * between them from a mob that spawns in exactly one structure. The floor is what keeps the
	 * objective meaning "go into a fortress" rather than "be handed one lucky drop".
	 */
	@Test
	void onlyTheRodsScaleWithThePartyAndNeverBelowTheFloor() {
		assertEquals(3, SurvivalProgressService.requiredBlazeRods(1));
		assertEquals(2, SurvivalProgressService.requiredBlazeRods(2));
		assertEquals(2, SurvivalProgressService.requiredBlazeRods(3));
		assertEquals(SurvivalProgressService.MINIMUM_BLAZE_RODS,
				SurvivalProgressService.requiredBlazeRods(8));
		// Degenerate headcounts must not produce a target nobody can reach or one that is free.
		assertEquals(3, SurvivalProgressService.requiredBlazeRods(0));
		assertEquals(3, SurvivalProgressService.requiredBlazeRods(-4));
	}

	/**
	 * The claim mask stores one bit per task <em>index</em>, so inserting {@code find_fortress} in the
	 * middle re-points every bit above it. A save left mid-Nether has to come forward pointing at the
	 * same tasks it went in pointing at, or the terminal pays rewards out a second time.
	 */
	@Test
	void insertingTheFortressTaskRepointsClaimedBitsInsteadOfShiftingThem() {
		int fortress = 1 << TerminalTaskService.FORTRESS_TASK_INDEX;

		// Nothing claimed stays nothing claimed, and the new task is genuinely outstanding.
		assertEquals(0, TerminalTaskService.migrateMaskForFortressInsert(0));

		// Claimed through enter_nether (indices 0-3): untouched, and the fortress is what comes next.
		assertEquals(0b1111, TerminalTaskService.migrateMaskForFortressInsert(0b1111));
		assertEquals(0, TerminalTaskService.migrateMaskForFortressInsert(0b1111) & fortress,
				"a player who has only just arrived in the Nether has not been in a fortress");

		// Claimed through collect_blaze_rods (old index 4): the rods move up to 5, and the fortress
		// bit is filled in behind them, because blazes only spawn inside one.
		int throughRods = TerminalTaskService.migrateMaskForFortressInsert(0b11111);
		assertEquals(0b111111, throughRods);

		// Every later task keeps its identity: old defeat_boss at 10 becomes 11, and nothing is lost.
		int everything = (1 << 11) - 1;
		assertEquals((1 << 12) - 1, TerminalTaskService.migrateMaskForFortressInsert(everything),
				"a finished save must still read as finished");
	}

	/** The same shift, applied to a real record by the migrator that ships it. */
	@Test
	void aMidNetherSaveIsNotAskedToCollectTheBlazeRodsTwice() {
		CompoundTag legacy = new CompoundTag();
		legacy.putInt(TerminalData.SCHEMA_VERSION, 10);
		// Claimed through the old collect_blaze_rods, which lived at index 4.
		legacy.putInt(TerminalData.TASK_REWARD_CLAIMED_MASK, 0b11111);
		legacy.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
				SurvivalMilestone.ENTERED_NETHER.mask() | SurvivalMilestone.COLLECTED_BLAZE_RODS.mask());

		CompoundTag migrated = TerminalData.migrateRecord(legacy);

		assertEquals(0b111111, migrated.getIntOr(TerminalData.TASK_REWARD_CLAIMED_MASK, 0));
		assertTrue(SurvivalMilestone.FOUND_FORTRESS.present(
						migrated.getIntOr(TerminalData.SURVIVAL_MILESTONE_MASK, 0)),
				"rods in hand are proof of a fortress visited");
		// The next thing asked of them is what it was before the insert: come back from the Nether.
		assertEquals("return_from_nether", TerminalTaskService.current(migrated).id());
	}

	@Test
	void legacyRecordMigrationAddsTaskStateAndPreservesCompletedSamples() {
		CompoundTag legacy = new CompoundTag();
		legacy.putInt(TerminalData.SCHEMA_VERSION, 7);
		legacy.putInt(TerminalData.SURVIVAL_MILESTONE_MASK,
				SurvivalMilestone.IRON.mask() | SurvivalMilestone.CRAFTED_EYE.mask());

		CompoundTag migrated = TerminalData.migrateRecord(legacy);
		assertEquals(PersistenceSchema.CURRENT_VERSION,
				migrated.getIntOr(TerminalData.SCHEMA_VERSION, 0));
		assertEquals(SurvivalProgressService.REQUIRED_IRON,
				migrated.getIntOr(TerminalData.IRON_SAMPLE_COUNT, 0));
		assertEquals(SurvivalProgressService.REQUIRED_CRAFTED_EYES,
				migrated.getIntOr(TerminalData.CRAFTED_EYE_COUNT, 0));
		assertEquals(0, migrated.getIntOr(TerminalData.TERMINAL_PAGE_VISIT_MASK, -1));
		assertEquals(0, migrated.getIntOr(TerminalData.TASK_REWARD_CLAIMED_MASK, -1));
		assertEquals(0, migrated.getIntOr(TerminalData.TASK_COMPLETION_NOTIFIED_MASK, -1));
		assertFalse(migrated.getBooleanOr(TerminalData.UNREAD_ALERT_ACTIVE, true));
	}

	@Test
	void migrationSeedsEncounteredChasesFromResolved() {
		// Saves written before the encountered counter existed only tracked successes, so the new
		// counter starts at least at the old resolved count rather than claiming the player has been
		// through nothing.
		CompoundTag escaped = new CompoundTag();
		escaped.putInt(TerminalData.SCHEMA_VERSION, 9);
		escaped.putInt(TerminalData.PURSUIT_RESOLVED_CHASES, 2);
		CompoundTag migratedEscaped = TerminalData.migrateRecord(escaped);
		assertEquals(2, migratedEscaped.getIntOr(TerminalData.PURSUIT_ENCOUNTERED_CHASES, -1));

		CompoundTag untouched = new CompoundTag();
		untouched.putInt(TerminalData.SCHEMA_VERSION, 9);
		CompoundTag migratedUntouched = TerminalData.migrateRecord(untouched);
		assertEquals(0, migratedUntouched.getIntOr(TerminalData.PURSUIT_ENCOUNTERED_CHASES, -1));
	}

	@Test
	void aSoloRunIsPaidExactlyWhatTheObjectiveSays() {
		// The whole split has to be invisible to one player, or it is a stealth nerf to single-player.
		for (int index = 0; index < 11; index++) {
			assertEquals(TerminalTaskService.rewardStack(index).getCount(),
					TerminalTaskService.payoutCount(index, 1),
					"Task " + index + " must pay its authored figure to a lone player");
		}
	}

	@Test
	void gatheringObjectivesAreNeverSplit() {
		// Eight players bring eight lots of iron, so eight lots of torches is the same trade the
		// first player made. Only the objectives a party clears once are divided.
		int[] gathering = {0, 1, 2, 5};
		for (int index : gathering) {
			assertEquals(TerminalTaskService.rewardStack(index).getCount(),
					TerminalTaskService.payoutCount(index, 8),
					"Gathering task " + index + " costs every player their own trip, so it is not split");
		}
	}

	@Test
	void sharedObjectivesDivideOnePayoutBetweenThePartyPresent() {
		// One portal, one fortress, one stronghold, one boss - the work does not grow with the party.
		int bossIndex = 9;
		assertEquals(4, TerminalTaskService.payoutCount(bossIndex, 1));
		assertEquals(2, TerminalTaskService.payoutCount(bossIndex, 2));
		assertEquals(1, TerminalTaskService.payoutCount(bossIndex, 4));
		// A full roster is 8 diamonds in total rather than the 32 that eight untouched payouts
		// would be, and the boss's own health only scales 4.5x across the same range.
		assertEquals(1, TerminalTaskService.payoutCount(bossIndex, 8));
	}

	@Test
	void aCompletedObjectiveNeverHandsOverNothing() {
		// "Task complete" beside an empty reward frame reads as a bug, so the split floors at one
		// however large the party gets.
		assertEquals(1, TerminalTaskService.sharedRewardCount(4, 64));
		assertEquals(1, TerminalTaskService.sharedRewardCount(1, 8));
		assertEquals(0, TerminalTaskService.sharedRewardCount(0, 8),
				"An objective with no reward stays with no reward");
	}

	@Test
	void retiringTheEyeObjectiveSlidesEveryTaskAboveItDown() {
		// One bit per task index, so dropping index 8 moves find_stronghold, enter_end and defeat_boss
		// down one. A save that had claimed through the stronghold must not read as having claimed the
		// End - that player would never be paid for it.
		int claimedThroughStronghold = 0b11_1111_1111; // old indices 0..9, up to and including find_stronghold
		assertEquals(0b1_1111_1111, TerminalTaskService.migrateMaskForEyeRemoval(claimedThroughStronghold),
				"Everything up to the stronghold stays claimed, one index lower");

		int onlyTheEnd = 1 << 10;
		assertEquals(1 << 9, TerminalTaskService.migrateMaskForEyeRemoval(onlyTheEnd),
				"enter_end slides from 10 to 9");

		int onlyTheRetiredTask = 1 << 8;
		assertEquals(0, TerminalTaskService.migrateMaskForEyeRemoval(onlyTheRetiredTask),
				"The retired objective's own bit is dropped; there is nothing left for it to mean");

		int untouchedLowTasks = 0b1111_1111;
		assertEquals(untouchedLowTasks, TerminalTaskService.migrateMaskForEyeRemoval(untouchedLowTasks),
				"Tasks below the retired index are not moved");
	}

	@Test
	void aSaveMidwayThroughTheEyesLandsOnTheStrongholdRatherThanRepeatingAnything() {
		// The migration runs on records, not just on bare ints: a player who had claimed through
		// craft_eye must come out facing find_stronghold, with no objective replayed and none skipped.
		CompoundTag record = new CompoundTag();
		record.putInt(TerminalData.SCHEMA_VERSION, 11);
		record.putInt(TerminalData.TASK_REWARD_CLAIMED_MASK, 0b1111_1111); // old 0..7, through craft_eye
		CompoundTag migrated = TerminalData.migrateRecord(record);
		// Two retirements apply in order: schema 12 drops record_eye at 8 (nothing above it was
		// claimed, so the mask is unchanged), then schema 13 drops craft_eye at 7 - and that bit goes
		// with it, because the task it stood for no longer exists to have been claimed.
		assertEquals(0b0111_1111, migrated.getIntOr(TerminalData.TASK_REWARD_CLAIMED_MASK, -1));
		assertEquals("find_stronghold", TerminalTaskService.current(migrated).id(),
				"The objective after craft_eye is now the stronghold, not the retired throw count");
	}

	@Test
	void anImpossiblePartySizeCannotDivideByNothing() {
		assertEquals(4, TerminalTaskService.sharedRewardCount(4, 0));
		assertEquals(4, TerminalTaskService.sharedRewardCount(4, -3));
		assertEquals(0, TerminalTaskService.payoutCount(-1, 4), "An unknown task pays nothing");
		assertEquals(0, TerminalTaskService.payoutCount(99, 4), "An unknown task pays nothing");
	}
}
