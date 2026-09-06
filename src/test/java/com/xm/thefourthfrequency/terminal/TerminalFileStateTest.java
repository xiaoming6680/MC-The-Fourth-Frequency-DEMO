package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.narrative.DeviceManualPolicy;
import com.xm.thefourthfrequency.narrative.NarrativeFileCatalog;
import com.xm.thefourthfrequency.narrative.HiddenFilePolicy;
import com.xm.thefourthfrequency.narrative.TerminalFileState;
import com.xm.thefourthfrequency.content.TerminalData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TerminalFileStateTest {
	@Test
	void catalogHasSevenConsolidatedFilesInStoryOrderPlusTheRecoveredFragment() {
		// Seven belong to this world, in story order. The eighth is the fragment a previous
		// playthrough left on this machine and sits after all of them, because it is not part of the
		// story this world tells - it is the previous one leaking into it.
		assertEquals(12, NarrativeFileCatalog.definitions().size());
		assertEquals("maintenance_handoff", NarrativeFileCatalog.definitions().getFirst().id());
		assertEquals("encrypted_witness_file", NarrativeFileCatalog.definitions().get(5).id());
		assertEquals("body_mapping_warning", NarrativeFileCatalog.definitions().get(6).id());
		assertEquals("recovered_predecessor_record", NarrativeFileCatalog.definitions().getLast().id());
		// The manual pages sit between the narrative files and the recovered fragment: the story
		// order is untouched, and the fragment stays last, which is where it is meant to be.
		assertEquals(DeviceManualPolicy.ids(),
				NarrativeFileCatalog.definitions().subList(7, 11).stream()
						.map(NarrativeFileCatalog.Definition::id).toList());
		assertFalse(NarrativeFileCatalog.definitions().stream().anyMatch(definition ->
				definition.id().equals("recovered_fragment")
						|| definition.id().equals("correction_response_record")
						|| definition.id().equals("world_interface_entry_record")));
	}

	@Test
	void fileAttentionClearsWithoutReadingOrUnlockingStoryFiles() {
		CompoundTag record = emptyRecord();
		String hidden = HiddenFilePolicy.fileId(0);
		assertTrue(TerminalFileState.discover(record, hidden, 10L, 20L, true));
		assertEquals(1, TerminalFileState.unreadCount(record));
		assertTrue(TerminalFileState.markAllSeen(record));
		assertEquals(0, TerminalFileState.unreadCount(record));
		assertFalse(TerminalFileState.read(record, hidden));
		assertFalse(HiddenFilePolicy.allRead(record));

		assertTrue(TerminalFileState.discover(record, HiddenFilePolicy.COMPLETE_FILE_ID, 30L, 40L, false));
		assertEquals(0, TerminalFileState.unreadCount(record));
		assertTrue(TerminalFileState.discover(record, HiddenFilePolicy.COMPLETE_FILE_ID, 50L, 60L, true));
		assertEquals(1, TerminalFileState.unreadCount(record));
	}

	@Test
	void hiddenFileReadIsIdempotentAndKeepsItsFirstReadTime() {
		CompoundTag record = emptyRecord();
		String id = HiddenFilePolicy.fileId(0);
		assertTrue(TerminalFileState.discover(record, id, 10L, 20L, true));
		assertTrue(TerminalFileState.markRead(record, id, 30L, 40L));
		assertFalse(TerminalFileState.markRead(record, id, 50L, 60L));
		TerminalFileState.State state = TerminalFileState.states(record).getFirst();
		assertTrue(state.read());
		assertEquals(30L, state.readGameTime());
		assertEquals(40L, state.readDayTime());
	}

	@Test
	void discoveryControlsTitleStageWhileReadsControlPercentage() {
		CompoundTag record = emptyRecord();
		assertEquals(0, HiddenFilePolicy.titleStage(record));
		assertEquals(0, HiddenFilePolicy.readPercent(record));
		for (int index = 0; index < HiddenFilePolicy.FILE_COUNT; index++) {
			String id = HiddenFilePolicy.fileId(index);
			TerminalFileState.discover(record, id, 10L + index, 20L + index, true);
			assertEquals(index + 1, HiddenFilePolicy.titleStage(record));
			assertEquals(index * 25, HiddenFilePolicy.readPercent(record));
			TerminalFileState.markRead(record, id, 30L + index, 40L + index);
			assertEquals((index + 1) * 25, HiddenFilePolicy.readPercent(record));
		}
		assertTrue(HiddenFilePolicy.allDiscovered(record));
		assertTrue(HiddenFilePolicy.allRead(record));
	}

	@Test
	void migrationLeavesPartialOldFilesUnreadButGrandfathersUnlockedDiaries() {
		CompoundTag partial = emptyRecord();
		TerminalFileState.discover(partial, HiddenFilePolicy.fileId(0), 10L, 20L, true);
		removeReadFields(partial);
		TerminalFileState.migrateReadState(partial, false);
		assertFalse(TerminalFileState.read(partial, HiddenFilePolicy.fileId(0)));

		CompoundTag unlocked = emptyRecord();
		TerminalFileState.discover(unlocked, HiddenFilePolicy.fileId(0), 10L, 20L, true);
		removeReadFields(unlocked);
		TerminalFileState.migrateReadState(unlocked, true);
		assertTrue(TerminalFileState.read(unlocked, HiddenFilePolicy.fileId(0)));
		assertEquals(10L, TerminalFileState.states(unlocked).getFirst().readGameTime());
	}

	private static CompoundTag emptyRecord() {
		CompoundTag record = new CompoundTag();
		record.put(TerminalData.FILE_STATES, new ListTag());
		return record;
	}

	private static void removeReadFields(CompoundTag record) {
		ListTag states = record.getListOrEmpty(TerminalData.FILE_STATES).copy();
		for (int index = 0; index < states.size(); index++) {
			CompoundTag state = states.getCompoundOrEmpty(index);
			state.remove("read");
			state.remove("read_game_time");
			state.remove("read_day_time");
			states.set(index, state);
		}
		record.put(TerminalData.FILE_STATES, states);
	}
}
