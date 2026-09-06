package com.xm.thefourthfrequency.correction;

import com.xm.thefourthfrequency.content.TerminalData;
import com.xm.thefourthfrequency.pursuit.PursuitProgressPolicy;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ReworkMigrationTest {
	@org.junit.jupiter.api.BeforeAll
	static void bootstrapRegistries() {
		net.minecraft.SharedConstants.tryDetectVersion();
		net.minecraft.server.Bootstrap.bootStrap();
	}
	@Test
	void legacyEntityStagesRetainTheirRelativeAnatomyAndNewStagesDoNotRemap() {
		int[] expected = {1, 1, 2, 2, 3};
		for (int old = 1; old <= 5; old++) {
			assertEquals(expected[old - 1], ReworkFormStage.readStage(old, 1));
		}
		for (int stage = 1; stage <= 3; stage++) assertEquals(stage, ReworkFormStage.readStage(stage, 2));
		assertEquals(1, ReworkFormStage.readStage(Integer.MIN_VALUE, 2));
		assertEquals(3, ReworkFormStage.readStage(Integer.MAX_VALUE, 2));
	}

	@Test
	void allLegacyProgressRecordsMigrateOnceWithoutLosingEncounterHistoryOrCooldown() {
		int[] expectedResolved = {0, 1, 1, 2, 2, 3};
		for (int old = 0; old <= 5; old++) {
			CompoundTag record = new CompoundTag();
			record.putInt(TerminalData.PURSUIT_RESOLVED_CHASES, old);
			record.putInt(TerminalData.PURSUIT_ALLOWED_FORM, 5);
			record.putInt(TerminalData.PURSUIT_SESSION_FORM, old);
			record.putInt(TerminalData.PURSUIT_TUTORIAL_DEMO_MASK, 0b11111);
			record.putLong(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK, 96000L);
			CompoundTag migrated = TerminalData.migrateRecord(record);
			assertEquals(expectedResolved[old], migrated.getIntOr(TerminalData.PURSUIT_RESOLVED_CHASES, -1));
			assertEquals(old, migrated.getIntOr(TerminalData.PURSUIT_ENCOUNTERED_CHASES, -1));
			assertEquals(3, migrated.getIntOr(TerminalData.PURSUIT_ALLOWED_FORM, -1));
			assertEquals(0b111, migrated.getIntOr(TerminalData.PURSUIT_TUTORIAL_DEMO_MASK, -1));
			assertEquals(96000L, migrated.getLongOr(TerminalData.PURSUIT_NEXT_ELIGIBLE_TICK, -1));
			assertEquals(migrated, TerminalData.migrateRecord(migrated));
			assertEquals(old == 5, PursuitProgressPolicy.complete(expectedResolved[old]));
		}
	}

	@Test
	void partialTutorialMasksAndNoSessionSentinelsSurvive() {
		assertEquals(0b001, ReworkFormStage.legacyMask(0b00010));
		assertEquals(0b010, ReworkFormStage.legacyMask(0b01100));
		assertEquals(0b100, ReworkFormStage.legacyMask(0b10000));
		assertEquals(0, ReworkFormStage.legacyPermission(0));
		assertEquals(0, ReworkFormStage.legacyResolved(-1));
	}
}
