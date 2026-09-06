package com.xm.thefourthfrequency.client_ui;

import com.xm.thefourthfrequency.networking.TerminalToolSnapshotPayload;
import com.xm.thefourthfrequency.terminal.NavigationConvergencePolicy;
import com.xm.thefourthfrequency.terminal.TerminalNavigationMath;
import com.xm.thefourthfrequency.terminal.TerminalResource;
import com.xm.thefourthfrequency.terminal.TerminalTool;
import com.xm.thefourthfrequency.terminal.TerminalToolService;
import com.xm.thefourthfrequency.terminal.TerminalStructureTarget;
import com.xm.thefourthfrequency.world.MineralSurveyPolicy;
import com.xm.thefourthfrequency.world.ResourceGuidanceService;
import com.xm.thefourthfrequency.world.SurvivalProgressService;
import net.minecraft.network.chat.Component;

public record TerminalToolSnapshot(TerminalToolSnapshotPayload payload) {
	public TerminalToolSnapshot {
		if (payload.protocolVersion() != TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION) {
			throw new IllegalStateException("Terminal tool protocol mismatch: server=" + payload.protocolVersion()
					+ ", client=" + TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION);
		}
	}

	public static TerminalToolSnapshot empty() {
		return new TerminalToolSnapshot(new TerminalToolSnapshotPayload(
				TerminalToolSnapshotPayload.CURRENT_PROTOCOL_VERSION,
				bit(TerminalTool.HOME) | bit(TerminalTool.WEATHER),
				TerminalToolService.NO_TOOL, TerminalToolService.NO_TOOL,
				TerminalTool.WEATHER.slot(), TerminalTool.HOME.slot(), 0, 0,
				TerminalStructureTarget.NONE.wireId(), false,
				false, 0, TerminalResource.NONE.wireId(), 0,
				MineralSurveyPolicy.MAX_PROBE_CHARGES, 0, 0, 0, 0, 0, 0, false, false, 0,
				0, 0L, 13_000, 0,
				false, false, false, 0, 0, 0, "",
				false, false, 0, 0, 0, "",
				0, false, 0, 0, 0,
				0, 0, 0, false, false, 0, 0, "", 0, 0));
	}

	public boolean available(TerminalTool tool) {
		return (payload.availableToolsMask() & bit(tool)) != 0;
	}

	public TerminalTool selectedTool() {
		return TerminalTool.fromSlot(payload.selectedTool());
	}

	public TerminalTool guidanceTool() {
		return TerminalTool.fromSlot(payload.guidanceTool());
	}

	public TerminalTool recommendedPrimaryTool() {
		return TerminalTool.fromSlot(payload.recommendedPrimaryTool());
	}

	public TerminalTool recommendedSecondaryTool() {
		return TerminalTool.fromSlot(payload.recommendedSecondaryTool());
	}

	public boolean resourceAvailable(TerminalResource resource) {
		return resource != TerminalResource.NONE
				&& (payload.availableResourcesMask() & 1 << resource.wireId()) != 0;
	}

	public boolean navigationTargetAvailable(TerminalStructureTarget target) {
		return target != TerminalStructureTarget.NONE
				&& (payload.navigationTargetsMask() & TerminalStructureTarget.bit(target)) != 0;
	}

	public TerminalStructureTarget selectedNavigationTarget() {
		return TerminalStructureTarget.fromWire(payload.selectedNavigationTarget());
	}

	public boolean unstableSignalAvailable() {
		return payload.unstableSignalAvailable();
	}

	public boolean toolsDisabled() {
		return payload.toolsDisabled();
	}

	public TerminalResource selectedResource() {
		return TerminalResource.fromWire(payload.selectedResource());
	}

	public boolean mineralScanning() {
		return payload.mineralScanTicks() > 0;
	}

	public int mineralScanTicks() {
		return Math.clamp(payload.mineralScanTicks(), 0, (int) MineralSurveyPolicy.PROBE_REVEAL_TICKS);
	}

	public int mineralProbeCharges() {
		return Math.clamp(payload.mineralProbeCharges(), 0, MineralSurveyPolicy.MAX_PROBE_CHARGES);
	}

	public boolean mineralProbeReady() {
		return mineralProbeCharges() > 0;
	}

	/** Label for the probe button: the charge count while it can fire, the wait while it cannot. */
	public Component mineralProbeLine() {
		if (mineralProbeReady()) return Component.translatable(
				"terminal.thefourthfrequency.tool.minerals.charges",
				mineralProbeCharges(), MineralSurveyPolicy.MAX_PROBE_CHARGES);
		return Component.translatable("terminal.thefourthfrequency.tool.minerals.recharging",
				clockText(payload.mineralRechargeTicks()));
	}

	public int mineralReadingKind() {
		return payload.mineralReadingKind();
	}

	public boolean mineralBearingReading() {
		return payload.mineralReadingKind() == ResourceGuidanceService.READING_BEARING;
	}

	/** A probe that resolved with nothing in range, as opposed to a probe never taken. */
	public boolean mineralProbeHeardNothing() {
		return payload.mineralReadingKind() == ResourceGuidanceService.READING_EMPTY;
	}

	/**
	 * The bearing reading: what was heard, which way, and roughly how far.
	 *
	 * <p>Deliberately anchored to where the probe was taken rather than tracking the player, and
	 * deliberately without a Y: it is a measurement the terminal made once, not a waypoint.</p>
	 */
	public Component mineralBearingLine() {
		return Component.translatable("terminal.thefourthfrequency.tool.minerals.bearing",
				Component.translatable("terminal.thefourthfrequency.resource." + selectedResource().id()),
				Component.translatable("terminal.thefourthfrequency.direction."
						+ TerminalNavigationMath.direction(payload.mineralReadingDx(), payload.mineralReadingDz())),
				Math.max(0, payload.mineralReadingMinDistance()),
				Math.max(0, payload.mineralReadingMaxDistance()));
	}

	private static String clockText(int ticks) {
		int seconds = Math.max(0, (Math.max(0, ticks) + 19) / 20);
		return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
	}

	public boolean mineralSurveyNearby() {
		return payload.mineralSurveyNearby();
	}

	public Component disabledLine() {
		int seconds = Math.max(1, (payload.toolsDisabledTicks() + 19) / 20);
		return Component.translatable("terminal.thefourthfrequency.tool.disabled", seconds);
	}

	public Component weatherLine() {
		return weatherLine(false);
	}

	/**
	 * The tool's own reading, or dashes while a sky anomaly is drowning the instrument.
	 *
	 * <p>{@code lost} collapses the whole line rather than skewing the minutes inside it. The
	 * countdown to nightfall is something players walk home on; a plausible wrong number is a lie
	 * they can act on, while a line that visibly refuses to resolve is the instrument admitting it
	 * cannot see. Only the second one is honest, and it comes back correct on its own.</p>
	 */
	public Component weatherLine(boolean lost) {
		if (lost) return Component.translatable("terminal.thefourthfrequency.tool.weather.lost");
		String weather = switch (Math.clamp(payload.weather(), 0, 2)) {
			case 1 -> "rain";
			case 2 -> "thunder";
			default -> "clear";
		};
		long dayTime = Math.floorMod(payload.dayTime(), 24_000L);
		boolean day = dayTime < 13_000L || dayTime >= 23_000L;
		Component current = Component.translatable("terminal.thefourthfrequency.tool.weather.current",
				Component.translatable("terminal.thefourthfrequency.environment.weather." + weather),
				Component.translatable("terminal.thefourthfrequency.tool.weather." + (day ? "day" : "night")));
		int minutes = Math.max(1, (payload.ticksUntilLightChange() + 1_199) / 1_200);
		return current.copy().append(Component.literal(" ")).append(Component.translatable(
				"terminal.thefourthfrequency.tool.weather.until_" + (day ? "dark" : "light"), minutes));
	}

	public Component homeLine() {
		if (!payload.homeKnown()) return Component.translatable("terminal.thefourthfrequency.tool.home.none");
		Component prefix = Component.translatable(payload.homeUsesBed()
				? "terminal.thefourthfrequency.tool.home.bed" : "terminal.thefourthfrequency.tool.home.saved");
		return prefix.copy().append(Component.literal(" ")).append(locationLine(payload.homeSameDimension(),
				payload.homeDx(), payload.homeDz(), payload.homeY(), payload.homeDimension()));
	}

	public Component portalLine() {
		if (!payload.portalKnown()) return Component.translatable("terminal.thefourthfrequency.tool.portal.none");
		return locationLine(payload.portalSameDimension(), payload.portalDx(), payload.portalDz(),
				payload.portalY(), payload.portalDimension());
	}

	public Component lockedLine(TerminalTool tool) {
		return Component.translatable("terminal.thefourthfrequency.tool." + tool.id() + ".locked");
	}

	/**
	 * One line saying what a tool the player already has actually does.
	 *
	 * <p>The grid was readable and uninformative: six glyphs and six nouns, with no way to find out
	 * what "矿物" or "导航" would report until you opened it and interpreted the readout. The padlock
	 * hint had solved exactly this problem for locked tools and left the unlocked ones alone, which
	 * is backwards - a player who cannot open a tool at least knows why, while a player who can open
	 * six has to open six.
	 *
	 * <p>Reuses the {@code .summary} line the detail page already shows rather than adding a second
	 * key per tool. The two would say the same thing in two places and drift, and the detail page is
	 * where a player checks after opening a tool - which is precisely the trip this is meant to save
	 * them, so the sentence that answers it there is the sentence that belongs here.
	 */
	public Component hintLine(TerminalTool tool) {
		return Component.translatable("terminal.thefourthfrequency.tool." + tool.id() + ".summary");
	}

	public boolean receiverAvailable() {
		return payload.receiverAvailable();
	}

	public int receiverTarget() {
		return Math.clamp(payload.receiverTarget(), 0, 100);
	}

	public int receiverStrength() {
		return Math.clamp(payload.receiverStrength(), 0, 100);
	}

	public int receiverLockTicks() {
		return Math.clamp(payload.receiverLockTicks(), 0, 20);
	}

	public Component strongholdLine() {
		int samples = Math.max(0, payload.eyeSampleCount());
		// Says how to get a sample, not just how many are missing.
		//
		// The count alone was the whole readout before three of them existed, which is the state
		// every player meets this tool in - and "0/3 Eye of Ender samples recorded" describes a
		// scoreboard without ever mentioning that throwing one is what scores. Nothing else in the
		// terminal says so either, so a player who had not already learned the vanilla mechanic
		// elsewhere could unlock the instrument built for this job and still be stuck.
		if (samples < SurvivalProgressService.REQUIRED_EYE_SAMPLES || !payload.strongholdKnown()) {
			return Component.empty()
					.append(Component.translatable("terminal.thefourthfrequency.tool.stronghold.samples",
							samples, SurvivalProgressService.REQUIRED_EYE_SAMPLES))
					.append(" ")
					.append(Component.translatable(
							"terminal.thefourthfrequency.tool.stronghold.hint.throw_to_sample"));
		}
		if (!payload.strongholdSameDimension()) return Component.translatable(
				"terminal.thefourthfrequency.tool.stronghold.other_dimension", payload.strongholdDimension());
		int minimum = Math.max(0, payload.strongholdMinDistance());
		int maximum = Math.max(0, payload.strongholdMaxDistance());
		Component estimate = Component.translatable("terminal.thefourthfrequency.tool.stronghold.estimate",
				Component.translatable("terminal.thefourthfrequency.direction."
						+ TerminalNavigationMath.direction(payload.strongholdDx(), payload.strongholdDz())),
				minimum, maximum, samples);
		// What to do about it, if there is anything worth doing.
		//
		// The level is read back out of the band the server already sent rather than carried as its
		// own field: the width of that band *is* the precision, so deriving it needs no protocol
		// change and cannot fall out of step with the number printed beside it. Without this the
		// estimate narrows silently and a player standing in one doorway throwing eye after eye has
		// no way to learn that where they are standing is the problem.
		String hint = NavigationConvergencePolicy.hintId(
				NavigationConvergencePolicy.levelForUncertainty(Math.max(0, (maximum - minimum) / 2)));
		if (hint == null) return estimate;
		return Component.empty().append(estimate).append(" ").append(Component.translatable(
				"terminal.thefourthfrequency.tool.stronghold.hint." + hint));
	}

	public int playerY() {
		return payload.playerY();
	}

	private static Component locationLine(boolean sameDimension, int dx, int dz, int y, String dimension) {
		if (!sameDimension) return Component.translatable(
				"terminal.thefourthfrequency.tool.location.other_dimension", dimension);
		return Component.translatable("terminal.thefourthfrequency.tool.location.same_dimension",
				Component.translatable("terminal.thefourthfrequency.direction."
						+ TerminalNavigationMath.direction(dx, dz)),
				TerminalNavigationMath.distance(dx, dz), y);
	}

	private static int bit(TerminalTool tool) {
		return 1 << tool.slot();
	}
}
