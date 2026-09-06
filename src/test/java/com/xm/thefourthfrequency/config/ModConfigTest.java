package com.xm.thefourthfrequency.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModConfigTest {
	@Test
	void defaultsContainOnlyLiveSettings() {
		ModConfig defaults = ModConfig.defaults();
		assertTrue(defaults.meta().enabled());
		assertEquals(0.8D, defaults.meta().peakVolume());
		assertEquals(1.0D, defaults.meta().bedVolume());
		assertEquals(0.8D, defaults.meta().effectiveBedVolume());
		assertFalse(defaults.pacing().developerAcceleration());
		assertFalse(defaults.clientState().alphaDowngradeComplete());
		assertFalse(defaults.clientState().viewDistanceUnlocked());
		// The three impact effects default on, and each is its own setting.
		assertEquals(1.0D, defaults.presentation().cameraShake());
		assertEquals(1.0D, defaults.presentation().effectiveCameraShake());
		assertTrue(defaults.presentation().hitStopEnabled());
		assertTrue(defaults.presentation().impactFlashEnabled());
	}

	@Test
	void validationClampsUnsafeVolume() {
		ModConfig validated = new ModConfig(
				new ModConfig.Meta(true, Double.NaN, Double.NaN, null),
				new ModConfig.Pacing(true),
				new ModConfig.ClientState(false, false, null, null),
				new ModConfig.Presentation(Double.NaN, true, true)
		).validated();
		assertEquals(1.0D, validated.meta().peakVolume());
		assertEquals(1.0D, validated.meta().bedVolume());
		assertTrue(validated.pacing().developerAcceleration());

		assertEquals(1.0D, validated.presentation().effectiveCameraShake());

		ModConfig muted = new ModConfig(
				new ModConfig.Meta(true, -2.0D, -2.0D, false),
				new ModConfig.Pacing(false),
				new ModConfig.ClientState(false, false, null, null),
				new ModConfig.Presentation(-2.0D, false, false)
		).validated();
		assertEquals(0.0D, muted.meta().peakVolume());
		assertEquals(0.0D, muted.meta().bedVolume());
		// Absent means "not configured" and has to resolve to on, or every config file written before
		// the switch existed would silently lose an encounter action. An explicit false stays false.
		assertTrue(validated.meta().forcedEvictionEnabled());
		assertFalse(muted.meta().forcedEvictionEnabled());
		assertEquals(0.0D, muted.presentation().effectiveCameraShake());
	}

	/**
	 * The first-run audio page writes only the one number it owns.
	 *
	 * <p>Everything else in the file is a setting a player may have edited by hand, and a slider that
	 * quietly reset the bed trim, the Meta switch or a recovered previous run every time it was moved
	 * would be a far worse bug than the one it was added to fix.
	 */
	@Test
	void settingTheModVolumeLeavesEverySurroundingSettingAlone() {
		ModConfig original = new ModConfig(
				new ModConfig.Meta(false, 0.8D, 0.25D, false),
				new ModConfig.Pacing(true),
				new ModConfig.ClientState(true, true,
						new ModConfig.PreviousRun("success", 7, 42L, java.util.List.of(1, 2)), 0b0101),
				new ModConfig.Presentation(0.5D, false, false)
		).validated();

		ModConfig quieter = original.withMeta(original.meta().withPeakVolume(0.35D));

		assertEquals(0.35D, quieter.meta().peakVolume());
		assertFalse(quieter.meta().enabled());
		assertEquals(0.25D, quieter.meta().bedVolume());
		assertFalse(quieter.meta().forcedEvictionEnabled());
		assertTrue(quieter.pacing().developerAcceleration());
		assertTrue(quieter.clientState().alphaDowngradeComplete());
		assertEquals("success", quieter.clientState().previousRun().outcome());
		assertEquals(0b0101, quieter.clientState().debugHudGroupMask());
		assertEquals(0.5D, quieter.presentation().effectiveCameraShake());
		// The bed trim is expressed as a factor of the peak, so it has to follow the new peak down.
		assertEquals(0.35D * 0.25D, quieter.meta().effectiveBedVolume(), 1.0E-9D);
		// And the value still passes through validation rather than around it.
		assertEquals(1.0D, original.withMeta(original.meta().withPeakVolume(4.0D)).meta().peakVolume());
		assertEquals(0.0D, original.withMeta(original.meta().withPeakVolume(-1.0D)).meta().peakVolume());
	}

	/**
	 * An absent HUD mask reads back as all groups on.
	 *
	 * <p>Same boxing argument as {@code bedVolume}: Gson fills an absent primitive with 0, and 0 here
	 * means "draw nothing", so a config written before this field existed would hand a developer a
	 * blank readout and no clue why. Boxed and defaulted, an old file and a new one behave alike.</p>
	 */
	@Test
	void anAbsentDebugHudMaskMeansEveryGroup() {
		assertEquals(ModConfig.ClientState.ALL_DEBUG_HUD_GROUPS,
				new ModConfig.ClientState(false, false, null, null).debugHudGroupMask());
		assertEquals(ModConfig.ClientState.ALL_DEBUG_HUD_GROUPS,
				ModConfig.defaults().clientState().debugHudGroupMask());
		// A hand-edited value with bits nothing owns is trimmed rather than drawn.
		assertEquals(0b0011, new ModConfig.ClientState(false, false, null, 0b1110011).debugHudGroupMask());
	}

	/**
	 * The three effects are independent settings, and a config file written before they existed must
	 * read back as "unset" rather than as all three switched off.
	 *
	 * <p>Same boxing argument as {@code bedVolume}: Gson fills an absent primitive with 0/false, so
	 * unboxed fields here would have silently disabled shake, hit-stop and flash for every existing
	 * player the moment the feature shipped - and the symptom would have been "the update did
	 * nothing", which nobody would report as a bug.
	 */
	@Test
	void presentationTogglesAreIndependentAndDefaultOnForLegacyFiles() {
		Gson gson = new Gson();
		ModConfig legacy = gson.fromJson("{\"meta\": {\"enabled\": true}}", ModConfig.class).validated();
		assertEquals(1.0D, legacy.presentation().effectiveCameraShake());
		assertTrue(legacy.presentation().hitStopEnabled());
		assertTrue(legacy.presentation().impactFlashEnabled());

		// Turning the shake off must leave the other two alone: a player with motion sickness and a
		// player with photosensitivity need different things switched off.
		ModConfig noShake = gson.fromJson(
				"{\"presentation\": {\"cameraShake\": 0.0}}", ModConfig.class).validated();
		assertEquals(0.0D, noShake.presentation().effectiveCameraShake());
		assertTrue(noShake.presentation().hitStopEnabled());
		assertTrue(noShake.presentation().impactFlashEnabled());

		ModConfig noFlash = gson.fromJson(
				"{\"presentation\": {\"impactFlash\": false}}", ModConfig.class).validated();
		assertFalse(noFlash.presentation().impactFlashEnabled());
		assertEquals(1.0D, noFlash.presentation().effectiveCameraShake());
		assertTrue(noFlash.presentation().hitStopEnabled());

		// A scale, not a switch: shake can be turned down rather than only off.
		ModConfig half = gson.fromJson(
				"{\"presentation\": {\"cameraShake\": 0.4}}", ModConfig.class).validated();
		assertEquals(0.4D, half.presentation().effectiveCameraShake(), 1.0E-9D);
	}

	/**
	 * The beds get their own trim, and an explicit zero has to survive validation - it is the
	 * only way a player can silence a permanent hiss without turning the rest of the mod down
	 * with it. That is also why the field is boxed: a config written before it existed must read
	 * back as "unset" and default to full, not as the 0.0 Gson would otherwise supply.
	 */
	@Test
	void bedVolumeDistinguishesUnsetFromDeliberateSilence() {
		Gson gson = new Gson();
		ModConfig legacy = gson.fromJson(
				"{\"meta\": {\"enabled\": true, \"peakVolume\": 0.5}}", ModConfig.class).validated();
		assertEquals(1.0D, legacy.meta().bedVolume());
		assertEquals(0.5D, legacy.meta().effectiveBedVolume());

		ModConfig silenced = gson.fromJson(
				"{\"meta\": {\"enabled\": true, \"peakVolume\": 0.5, \"bedVolume\": 0.0}}",
				ModConfig.class).validated();
		assertEquals(0.0D, silenced.meta().effectiveBedVolume());
		assertEquals(0.5D, silenced.meta().peakVolume(), "trimming the beds must not touch the rest");
	}

	@Test
	void jsonUsesMinimalGroupedConfigurationContractAndIgnoresRetiredFields() {
		Gson gson = new Gson();
		String encoded = gson.toJson(ModConfig.defaults());
		assertTrue(encoded.contains("\"meta\":{\"enabled\":true"));
		assertTrue(encoded.contains("\"pacing\":{"));
		assertTrue(encoded.contains("\"clientState\":{"));
		assertTrue(encoded.contains("\"presentation\":{"));
		assertFalse(encoded.contains("subtitlesEnabled"));
		assertFalse(encoded.contains("productionHours"));
		assertFalse(encoded.contains("acceleratedMinutes"));
		assertFalse(encoded.contains("ambientAnomalyMinMinutes"));
		assertFalse(encoded.contains("ambientAnomalyMaxMinutes"));
		assertFalse(encoded.contains("correctionWorkBudgetPerTick"));
		assertFalse(encoded.contains("\"limits\""));
		assertFalse(encoded.contains("safetyNoticeAcknowledged"));

		ModConfig decoded = gson.fromJson("""
				{
				  "meta": {"enabled": false, "subtitlesEnabled": true, "peakVolume": 0.5},
				  "pacing": {
				    "developerAcceleration": true,
				    "productionHours": 9,
				    "acceleratedMinutes": 2,
				    "ambientAnomalyMinMinutes": 6,
				    "ambientAnomalyMaxMinutes": 12
				  },
				  "limits": {"correctionWorkBudgetPerTick": 96},
				  "clientState": {
				    "safetyNoticeAcknowledged": true,
				    "alphaDowngradeComplete": false
				  }
				}
				""", ModConfig.class).validated();
		assertFalse(decoded.meta().enabled());
		assertEquals(0.5D, decoded.meta().peakVolume());
		assertTrue(decoded.pacing().developerAcceleration());
		assertFalse(decoded.clientState().viewDistanceUnlocked());

		String normalized = gson.toJson(decoded);
		assertFalse(normalized.contains("subtitlesEnabled"));
		assertFalse(normalized.contains("productionHours"));
		assertFalse(normalized.contains("correctionWorkBudgetPerTick"));
		assertFalse(normalized.contains("\"limits\""));
		assertFalse(normalized.contains("safetyNoticeAcknowledged"));
	}

	@Test
	void clientStateTransitionsPreserveTheOtherFlag() {
		ModConfig.ClientState completed = ModConfig.defaults().clientState().completeAlphaDowngrade();
		assertTrue(completed.alphaDowngradeComplete());
		assertFalse(completed.viewDistanceUnlocked());

		ModConfig.ClientState unlocked = completed.unlockViewDistance();
		assertTrue(unlocked.alphaDowngradeComplete());
		assertTrue(unlocked.viewDistanceUnlocked());
	}

	/**
	 * A previous run is narrative, and a hand-edited config file is untrusted input.
	 *
	 * <p>Nothing here is read by any rule, which is exactly why it must be clamped rather than
	 * trusted: the values end up interpolated into a file the player reads, and a hostile or simply
	 * corrupted config should produce a boring fragment rather than a broken one. {@code null}
	 * resolving to "no previous run" is the same statement - the correct reading of missing data is
	 * that there was no previous run, never a fabricated one.
	 */
	@Test
	void aPreviousRunIsClampedRatherThanTrusted() {
		ModConfig.PreviousRun rubbish = new ModConfig.PreviousRun(
				"not-an-outcome", 999, -5L, java.util.List.of(9, 9, 9));
		assertEquals("none", rubbish.outcome());
		assertEquals(10, rubbish.destroyedAnchors());
		assertEquals(0L, rubbish.day());
		assertFalse(rubbish.present());

		ModConfig.PreviousRun real = new ModConfig.PreviousRun("success", 3, 214L,
				java.util.List.of(0, 1, 2, 0, 1));
		assertTrue(real.present());
		assertEquals(3, real.destroyedAnchors());

		// A missing block is "no previous run", not a default one.
		assertFalse(new ModConfig.ClientState(false, false, null, null).previousRun().present());
		assertEquals("none", ModConfig.PreviousRun.none().outcome());
	}
}
