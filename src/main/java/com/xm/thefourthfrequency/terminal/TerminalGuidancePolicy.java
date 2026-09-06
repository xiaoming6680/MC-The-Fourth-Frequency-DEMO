package com.xm.thefourthfrequency.terminal;

import com.xm.thefourthfrequency.world.SurvivalMilestone;
import com.xm.thefourthfrequency.world.SurvivalProgressService;

/** Pure unlock and recommendation policy shared by the authoritative tool service and tests. */
public final class TerminalGuidancePolicy {
	private TerminalGuidancePolicy() {
	}

	public static int availableToolsMask(int milestones, boolean portalKnown, int obtainedEyeCount) {
		int mask = bit(TerminalTool.HOME) | bit(TerminalTool.WEATHER);
		if (SurvivalMilestone.MINED_LOGS.present(milestones)) {
			mask |= bit(TerminalTool.MINERALS) | bit(TerminalTool.NAVIGATION);
		}
		if (portalKnown || SurvivalMilestone.ENTERED_NETHER.present(milestones)) {
			mask |= bit(TerminalTool.PORTAL);
		}
		if (obtainedEyeCount >= SurvivalProgressService.REQUIRED_STRONGHOLD_UNLOCK_EYES) {
			mask |= bit(TerminalTool.STRONGHOLD);
		}
		return mask;
	}

	/**
	 * Which ores the probe is allowed to hear, by mainline position.
	 *
	 * <p>All four used to open at once the moment the player had twelve logs, which meant a
	 * stone-age player could stand still and be handed diamond. The tool's actual job at that
	 * point is coal and iron - it exists to carry the {@code bring_iron} objective - so the rarer
	 * two now arrive when the mainline has already asked for them. {@code hintTier} is deliberately
	 * not consulted: it measures how long the player has been stalled, and being stuck is not the
	 * same as having earned a deeper instrument.</p>
	 */
	public static int availableResourcesMask(int milestones, int hintTier) {
		if (!SurvivalMilestone.MINED_LOGS.present(milestones)) return 0;
		int mask = bit(TerminalResource.IRON) | bit(TerminalResource.COAL);
		if (SurvivalMilestone.IRON.present(milestones)) mask |= bit(TerminalResource.GOLD);
		if (SurvivalMilestone.ENTERED_NETHER.present(milestones)) {
			mask |= bit(TerminalResource.DIAMOND) | bit(TerminalResource.EMERALD);
		}
		return mask;
	}

	public static Recommendations recommendations(String objectiveId, int availableToolsMask,
			int activeGuidanceTool, boolean homeKnown, long dayTime, boolean toolsDisabled, int hintTier) {
		if (toolsDisabled && available(availableToolsMask, TerminalTool.WEATHER)) {
			return fill(availableToolsMask, TerminalTool.WEATHER, TerminalTool.HOME, hintTier >= 1);
		}
		TerminalTool primary = validAvailable(activeGuidanceTool, availableToolsMask);
		TerminalTool secondary = null;
		TerminalTool[] desired = switch (objectiveId) {
			case "mine_logs" -> pair(TerminalTool.WEATHER, TerminalTool.HOME);
			case "bring_iron" -> pair(TerminalTool.MINERALS, TerminalTool.HOME);
			case "enter_nether" -> pair(TerminalTool.NAVIGATION, TerminalTool.MINERALS);
			case "collect_blaze_rods" -> pair(TerminalTool.NAVIGATION, TerminalTool.PORTAL);
			case "return_from_nether" -> pair(TerminalTool.PORTAL, TerminalTool.NAVIGATION);
			case "record_eye", "find_stronghold", "enter_end" ->
					pair(TerminalTool.STRONGHOLD, TerminalTool.HOME);
			case "defeat_boss" -> pair(TerminalTool.STRONGHOLD, TerminalTool.WEATHER);
			default -> pair(TerminalTool.HOME, TerminalTool.WEATHER);
		};
		for (TerminalTool tool : desired) {
			if (!available(availableToolsMask, tool) || tool == primary) continue;
			if (primary == null) primary = tool;
			else if (secondary == null) secondary = tool;
		}
		long time = Math.floorMod(dayTime, 24_000L);
		boolean shelterSoonUseful = !homeKnown && time >= 10_000L && time < 23_000L;
		if (shelterSoonUseful && available(availableToolsMask, TerminalTool.HOME)
				&& primary != TerminalTool.HOME) secondary = TerminalTool.HOME;
		return fill(availableToolsMask, primary, secondary, hintTier >= 1 || shelterSoonUseful);
	}

	public static boolean available(int mask, TerminalTool tool) {
		return (mask & bit(tool)) != 0;
	}

	public static boolean resourceAvailable(int mask, TerminalResource resource) {
		return resource != TerminalResource.NONE && (mask & bit(resource)) != 0;
	}

	private static Recommendations fill(int mask, TerminalTool primary, TerminalTool secondary,
			boolean revealSecondary) {
		TerminalTool first = primary != null && available(mask, primary) ? primary : null;
		TerminalTool second = revealSecondary && secondary != null && secondary != first
				&& available(mask, secondary) ? secondary : null;
		for (TerminalTool fallback : new TerminalTool[]{TerminalTool.HOME, TerminalTool.WEATHER,
				TerminalTool.MINERALS, TerminalTool.NAVIGATION, TerminalTool.PORTAL, TerminalTool.STRONGHOLD}) {
			if (!available(mask, fallback) || fallback == first || fallback == second) continue;
			if (first == null) first = fallback;
			else if (revealSecondary && second == null) second = fallback;
			if (first != null && (!revealSecondary || second != null)) break;
		}
		return new Recommendations(wire(first), wire(second));
	}

	private static TerminalTool validAvailable(int wire, int mask) {
		TerminalTool tool = TerminalTool.fromSlot(wire);
		return tool != null && available(mask, tool) ? tool : null;
	}

	private static TerminalTool[] pair(TerminalTool first, TerminalTool second) {
		return new TerminalTool[]{first, second};
	}

	private static int wire(TerminalTool tool) {
		return tool == null ? TerminalToolService.NO_TOOL : tool.slot();
	}

	private static int bit(TerminalTool tool) {
		return 1 << tool.slot();
	}

	private static int bit(TerminalResource resource) {
		return 1 << resource.wireId();
	}

	public record Recommendations(int primary, int secondary) {
	}
}
