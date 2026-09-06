package com.xm.thefourthfrequency.pursuit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level guards for two mirror rules that are only wrong in ways nothing else notices.
 *
 * <p>Both of the properties pinned here were regressions that four green test layers had no opinion
 * about, because both are about <em>how</em> an answer is reached rather than about the answer to
 * any case somebody thought to write down. Behaviour is asserted where it belongs, in
 * {@code PursuitRuntimeGameTests}, which needs a loaded server for the block registry and a player.
 * What that cannot say is "and it must not go back to matching names", which is exactly the shape
 * the bug had.
 */
final class PursuitMirrorContractTest {
	private static final Path BLOCK_POLICY =
			Path.of("src/main/java/com/xm/thefourthfrequency/pursuit/PursuitBlockPolicy.java");
	private static final Path RECOVERY_LEDGER =
			Path.of("src/main/java/com/xm/thefourthfrequency/pursuit/PursuitRecoveryLedger.java");

	/**
	 * What may exist in the mirror is decided by type, never by registry name.
	 *
	 * <p>The substring rule this replaced rejected any block whose path contained "rail" and accepted
	 * {@code target}, {@code lightning_rod}, {@code note_block}, {@code copper_bulb} and - the one
	 * that mattered - {@code respawn_anchor}, which let a player set their spawn point inside a
	 * private dimension that stops existing when their session does. A name test reads as a perfectly
	 * reasonable line of code, so the only thing that keeps it from being written again is this.
	 */
	@Test
	void mirrorEligibilityIsDecidedByBlockTypeRatherThanByRegistryName() throws IOException {
		String source = Files.readString(BLOCK_POLICY, StandardCharsets.UTF_8);
		assertFalse(source.contains("path.contains("),
				"Mirror eligibility must not match substrings of a block's registry path");
		assertFalse(source.contains("path.endsWith("),
				"Mirror eligibility must not match suffixes of a block's registry path");
		assertFalse(source.contains("getKey(block).getPath()"),
				"Mirror eligibility must not read a block's registry name at all");
		for (String type : new String[]{
			"instanceof Portal", "instanceof BaseFireBlock", "instanceof PistonBaseBlock",
			"instanceof DiodeBlock", "instanceof BaseRailBlock", "instanceof BasePressurePlateBlock",
			"instanceof SculkSensorBlock", "instanceof ButtonBlock", "instanceof LeverBlock",
			"instanceof ObserverBlock", "instanceof TripWireBlock", "instanceof BedBlock",
			"instanceof TntBlock", "instanceof RespawnAnchorBlock",
		}) {
			assertTrue(source.contains(type), "Mirror eligibility must still test for " + type);
		}
		assertTrue(source.contains("isSignalSource()"),
				"A redstone source the class list does not name must still be refused");
	}

	/**
	 * The refund gives back the stack that was spent, and says so when it cannot.
	 *
	 * <p>Two separate regressions live here. Dropping the encoded stack turns a renamed block into an
	 * ordinary one and merges two differently named stacks into whichever was written first; dropping
	 * the notice turns an unrecoverable entry into a player who is quietly short with no record of
	 * why. Both look like simplifications when read in isolation.
	 */
	@Test
	void refundsCarryTheirComponentsAndAnnounceWhatTheyCannotReturn() throws IOException {
		String source = Files.readString(RECOVERY_LEDGER, StandardCharsets.UTF_8);
		assertTrue(source.contains("ItemStack.CODEC.encodeStart"),
				"A recorded placement must store the stack, not only its id");
		assertTrue(source.contains("ItemStack.CODEC.parse"),
				"A refund must rebuild the stack it stored");
		assertTrue(source.contains("mergeKey"),
				"Two placements may only merge when they would refund the same item");
		assertTrue(source.contains("TerminalNoticeService.denied"),
				"An entry that cannot be rebuilt must be reported, not silently dropped");
		assertTrue(source.contains("message.thefourthfrequency.pursuit.refund_lost"),
				"The loss notice must use the pursuit's own key rather than the encounter's");
		assertFalse(source.contains("player.getInventory().add(stack)"),
				"Delivery must hand back stack-sized portions of the stored prototype");
	}
}
