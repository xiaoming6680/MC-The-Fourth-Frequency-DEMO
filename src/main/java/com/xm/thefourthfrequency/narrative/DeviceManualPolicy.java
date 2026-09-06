package com.xm.thefourthfrequency.narrative;

import com.xm.thefourthfrequency.terminal.TerminalTool;

import java.util.ArrayList;
import java.util.List;

/**
 * The device's own manual pages, and which tool each one documents.
 *
 * <p>A page arrives with the tool it describes: it is the operating instructions for something the
 * player has just been handed, so most of what it says is true and immediately useful - how many
 * charges the probe holds, why the compass does not point until a destination is chosen, what a
 * distance band means.
 *
 * <p>Each page also carries a line the device no longer honours, and the revision line that
 * explains why. The manual is not lying and the terminal is not faking an error: the page is simply
 * older than the machine it came with, and its own footer says so - {@link #DOCUMENT_PROTOCOL},
 * against the protocol number the status bar has been printing since the first boot. Everything
 * that has drifted is checkable on the device in front of the player:
 *
 * <ul>
 *   <li>The sky monitor page describes three channels. The instrument draws four.</li>
 *   <li>The probe page says it samples continuously, including while the terminal is open. The
 *       passive survey only ever runs while the terminal is shut.</li>
 *   <li>The navigator page lists six destinations as always selectable. They open by mainline
 *       position, so early on the list is short.</li>
 *   <li>The stronghold page says any three records will do. The tool's own hint asks for a second
 *       vantage, because records taken from one spot do not cross.</li>
 * </ul>
 *
 * <p>None of this is narration. The pages state old behaviour flatly and leave the noticing to the
 * player, which is the only version of this that stays inside "the terminal may be incomplete, it
 * may not be omniscient".
 *
 * <p>Two of the six tools have no page. Home and the weather line are one sentence each and a
 * manual for them would be filler; the sky monitor page covers the instrument that sits under the
 * weather tool, which is the part with anything to explain.
 */
public final class DeviceManualPolicy {
	/**
	 * The protocol revision every manual page declares in its footer.
	 *
	 * <p>Held here rather than only inside the translated strings so that a test can assert the one
	 * property that makes the family work: the documents are older than the device. The status bar
	 * prints {@code TerminalSnapshotPayload.CURRENT_PROTOCOL_VERSION}, and the day this number is
	 * not below that one, four files start claiming to be current while contradicting the machine.
	 */
	public static final int DOCUMENT_PROTOCOL = 9;

	/**
	 * A list rather than a map, because the order is part of the contract - the pages arrive in the
	 * order their tools unlock - and {@code Map.copyOf} does not promise to keep one.
	 */
	private static final List<Page> PAGES = List.of(
			new Page(TerminalTool.WEATHER, "manual_sky_monitor"),
			new Page(TerminalTool.MINERALS, "manual_mineral_probe"),
			new Page(TerminalTool.NAVIGATION, "manual_structure_navigator"),
			new Page(TerminalTool.STRONGHOLD, "manual_stronghold_estimate"));

	private DeviceManualPolicy() {
	}

	/** The page documenting {@code tool}, or {@code null} for the two tools that have none. */
	public static String pageFor(TerminalTool tool) {
		if (tool == null) return null;
		for (Page page : PAGES) if (page.tool() == tool) return page.id();
		return null;
	}

	public static boolean isManual(String id) {
		if (id == null) return false;
		for (Page page : PAGES) if (page.id().equals(id)) return true;
		return false;
	}

	/** Every manual page id, in the order their tools unlock. */
	public static List<String> ids() {
		return PAGES.stream().map(Page::id).toList();
	}

	/**
	 * The pages a holder with this tool mask is entitled to.
	 *
	 * <p>Keyed off the same mask the tool grid draws from, so a page cannot arrive for a tool the
	 * player cannot open yet and cannot be missing for one they can. The mask is the only input:
	 * there is no separate schedule to fall out of step with the unlocks.
	 *
	 * @param availableToolsMask {@code TerminalToolService.availableToolsMask}
	 */
	public static List<String> earned(int availableToolsMask) {
		List<String> earned = new ArrayList<>(PAGES.size());
		for (Page page : PAGES) {
			if ((availableToolsMask & 1 << page.tool().slot()) != 0) earned.add(page.id());
		}
		return List.copyOf(earned);
	}

	private record Page(TerminalTool tool, String id) { }
}
