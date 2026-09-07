# Background music

`MusicDirector` takes over vanilla's two seams through `MinecraftMusicMixin`: `getSituationalMusic` decides what plays, `getMusicVolume` decides the fade target gain. Scheduling, fading and the "now playing" toast all stay vanilla.

This document covers music only; the mix headroom for sound effects is in [The World Interface finale](world-interface.md), client lifecycle in [Architecture](architecture.md), and trade-offs in [Design notes](design-notes.md#background-music).

## Situation table

**Read top to bottom; the first match wins.**

| Situation | Result |
| --- | --- |
| Pursuit mirror world (already started under the blackout and loading screen) | `music_pursuit` (1 track, looping) |
| Every other pursuit phase (warning, capture, escape resolution, return) | Silence |
| After stepping into the finale portal (including the End Poem, credits and post-credits) | `music_ending` / `music_ending_failure` (1 track each, looping) |
| World Interface summon and phase 1 | `music_encounter_phase_1` (1 track, looping), **from the first tick of the summon**. Summon and form 1 share one track with no swap between them |
| World Interface phase 2 | `music_encounter_phase_2` (1 track, looping) |
| World Interface phase 3 | `music_encounter_final` (1 track, looping) |
| Both resolutions and portal opening | Silence |
| Loading and transition screens (`LevelLoadingScreen` / `ProgressScreen` / `GenericMessageScreen` / `ConnectScreen` / any Overlay) | Silence |
| Main menu / no player | `music_menu` (4 tracks), but only after the safety notice releases it |
| Safety notice not yet released | Silence |
| Any other boss bar flagged as music (Ender Dragon, Wither) | Silence |
| The End with the World Interface not yet summoned (`UNPREPARED` / `ARENA_READY` / `WAITING_TERMINALS`, or no snapshot yet) | `music_end` (1 track, looping) |
| Ordinary gameplay | `music_game` (9 tracks, interval 4800 ticks / 4 minutes, still subject to the music-frequency option) |
| The unrendered layer, once the Bacteria has appeared this session (`UnrenderedLayerClient.hunted()`, latched once true) | `music_unrendered` (1 track, looping), with the fade target scaled by `UNRENDERED_MUSIC_TRIM = 0.45` |
| The unrendered layer before it appears | Silence: the first minute down there is the place establishing that it is empty, and a track arriving with the player says it is not |

**"Released" is the instant the player presses "I understand"**, not `FirstRunNoticeController.acknowledge()` (which waits out a 28-tick exit animation). The same entry also zeroes `nextSongDelay`.

**The End track keys off the dimension, not the stage**: the World Interface snapshot arrives with the world, and the player is already standing in the End before it lands — so "no stage" also counts as not summoned. `COMPLETE` is excluded.

## Master volume

`meta.peakVolume` is this mod's **master volume**, and music is inside it: `musicVolume` multiplies vanilla's fade target by it before returning, and the cached `fadeTarget` stores the multiplied value.

There are two entry points: `config/thefourthfrequency.json`, and the **audio calibration page** of the first-run flow. Dragging the slider only changes the in-process config, so the neighbouring "Preview" is immediately at the new level. Preview plays `signal/tuning_sweep` — this mod's own 3-second signal sweep.

## Four fade rules

### 1. The menu-track fade on world entry

**The only fade that is racing something else**, so it does not use vanilla's curve: it is a **10-tick (0.5 s) linear ramp**.

- The ramp is **recomputed each tick from the recorded start point** (rather than multiplied down tick by tick), so vanilla's own easing on the same value in the same tick cannot pull it off course.
- Finishing the ramp actively calls `stopPlaying()`: it is inaudible by then, but the track has to actually be handed back.
- The end-of-loading edge keeps one `stopPlaying()` backstop, for loads too short to fit even a 0.5-second ramp.
- **Pursuits are exempt from both**, because what the blackout is covering is the mirror dimension switch, and the pursuit track is fading in right then.

### 2. Quitting to the title after a win: the score follows the player

**The only time music has to survive world unload.** The state machine is the pure class `client_ui.EndingScoreHandoff` (main, directly unit-testable):

| State | Entered when | Effect |
|---|---|---|
| `OFF` | Default | Everything normal: menu music owns the title screen, loading screens are silent |
| `ARMED` | Ending confirmed **and** the player is back in the world **and** not loading | Loading gaps score as ordinary gameplay music; the world-entry fade and loading backstop are skipped; the music channel survives disconnect |
| `HOLDING` | No world and no loading screen (settled on the title screen) | The title screen keeps scoring from `music_game`; menu music does not take over |
| Back to `OFF` | Entering any loading screen or world again | Normal service resumes; the world-entry fade applies again |

Three constraints, none optional:

1. **It cannot arm at poem confirmation.** Win cleanup restores resource packs, which is a full reload and raises a loading overlay.
2. **It must arm before the quit.** `Minecraft#disconnect` tears the world down internally and stops sound in the same call.
3. **It must bypass the engine-wide stop.** The `soundManager.stop()` inside `updateLevelInEngines` clears every channel.

**The failure ending does not participate**: its `scoredOutcome` latch is never released anyway.

### 3. Combat hand-off (about 3 seconds)

Every other track change has a deliberate silence between it, which vanilla's curves cover (target gain to 0, ~15 s out, ~10 s in). The boss fight is the sole exception; `MusicDirector` drives the same two curves far more steeply:

| Step | How |
|---|---|
| Fade out | `situationalMusic` returns null during the hand-off, and each tick multiplies an extra **0.80** on top of vanilla's 0.97 decay — the channel clears in about 1.5 s |
| Start | Zero `nextSongDelay` (every stop adds 100 ticks to it, and the "constant" option pins the interval at 100 ticks too) |
| Fade in | Run vanilla's fade-in step **7 extra times** per tick — back to target gain in about 1.2 s |

> **`replaceCurrentMusic` must be false on all three `music_encounter*` events.** The flag is evaluated inside the music manager's own tick, before this class sees the frame; leaving it true cuts the old track off before the hand-off even begins. Every other track stays 0-delay + immediate replace.

### 4. An entry must start from silence

`MusicManagerGainAccessor` pushes the gain to 0 on the edge where "nothing is playing but something is about to". That write is conditional on `currentMusic == null`, so a single frame of situation jitter cannot cut off a track that is fading out.

`silenceGain` zeroes **both** the gain and the category volume.

**There are exactly two hard cuts**: the pursuit blackout and capture — where the fiction is that the signal was severed.

## Rotation: no repeat until a full pass

Minecraft has no playlist. Multiple `sounds` on one event in `sounds.json` is a **weighted random pool**, drawn independently every time.

| Item | Rule |
|---|---|
| Events that rotate | Only `music_game` (9) and `music_menu` (4). Single-track events and attack-sound variants are excluded automatically |
| Declared in | `audio/MusicRotationPolicy` |
| Enforced at | `AbstractSoundInstanceRotationMixin`, hooked on `AbstractSoundInstance#resolve` |
| End of a pass | `MusicRotationPolicy.passComplete(event, poolSize)` **counts** the tracks used this pass; it is not inferred from failed redraws |
| Pool size source | `WeighedSoundEventsPoolAccessor` reads `WeighedSoundEvents`' private `list` (`getWeight` is the sum of weights and cannot be used as a count) |
| On a repeat | **Redraw** rather than picking by index, so vanilla weights are not flattened. Capped at 32 redraws per track (320 for a ten-track pool, 32 at a seam) |
| Pass seam | The first track of a new pass cannot be the last track of the previous one |

> **The rotation cannot hang off `WeighedSoundEvents`** — it does not know its own event id, so hooking there cannot tell music apart from the eight attack sounds that are supposed to draw freely.

`MusicRotationPolicyTest` cross-checks the declared list against the real pool sizes in `sounds.json`, so adding a track and forgetting it is a test failure.

## The ordinary-gameplay interval

`music_game` uses `Music(holder, 4800, 7200, false)` rather than vanilla's `Musics.createGameMusic` (10–20 minutes).

**Only the lower bound really matters**: the manager redraws a random number in that range every tick and takes the minimum with the current value — including the thousands of ticks while a track plays — so the upper bound is essentially a statement of intent.

The current cycle is about 6 m 10 s, roughly a third audible (nine tracks averaging 2 m 10 s).

The player's music-frequency option still applies: "frequent" caps at 12000 ticks, above 4800, so it has no effect; "constant" is hard-coded to 100 ticks in the manager and overrides any track's own pacing.

## Four sounds in the unrendered layer

All four are played and stopped by the client off the dimension alone, **with no protocol at all**.

| Source | Channel | Volume / radius | Note |
|---|---|---|---|
| `music/unrendered/nice_boys.ogg` | `MUSIC` | **−23 LUFS** at import, played at **0.45** | **The layer used to be deliberately unscored** (`select` returned null), on the reasoning that somewhere never authored for anybody should not sound authored. What plays now is not a piece written for the layer but one lifted out of the ordinary-gameplay rotation: the player recognises it, and this is not where they should be recognising it. **It scores the Bacteria rather than the room**, so it waits for the entity, and it sits under both the bed and the heartbeat because those are what the player navigates by |
| `layer_ambience.ogg` (20 s loop) | `AMBIENT` | Playback 0.11 | Original procedural room bed; decoded measurements in soundscape_manifest.json |
| `heartbeat.ogg` (1 s) | `HOSTILE` | Playback 0.35→1.0 by distance | Positional at the bacteria; 64-block radius, 30→7 ticks between pulses; near-field foot friction is separate |
| `capture_scream.ogg` | UI | Capture transition | Personal one-shot survives the return teleport; decoded measurements in soundscape_manifest.json |

## Thunder in the End

Thunder borrows vanilla's `LIGHTNING_BOLT_THUNDER`, scheduled by the client off the world clock and played locally (scheduling rules in [The World Interface finale](world-interface.md#rain-in-the-end)).

- **It uses `SoundSource.WEATHER`**, not `AMBIENT` and not `HOSTILE`. `silent_world` silences MUSIC / AMBIENT / HOSTILE, so thunder cuts through that anomaly — **deliberately consistent with vanilla rain**.
- **Vanilla's cue declares no attenuation distance of its own**, so it dies out at 16 blocks, while thunder is emitted 64 blocks above and 96 blocks out from the player. It goes through the same conversion as `AudioService.playWithReach`.
- **Volume is still bounded by `peakVolume`**; at 0 it is silent. It does **not** go through `ENCOUNTER_MIX_TRIM` — that trim is for the encounter's authored cues, and this is weather.

## Ingest: align loudness first, attenuate second

Assets ship as 44.1 kHz stereo Ogg Vorbis (q4). Playback level is **baked into the file** by `tools/import_music.py` at import time rather than written into `sounds.json`. **Two steps, and the order cannot be reversed:**

1. **Align loudness.** Measure each master's integrated loudness with ffmpeg's `loudnorm`, then apply one **purely linear** gain shift to **−24 LUFS**. Masters span −3.6 to −16.4 LUFS.
2. **Then attenuate.** Multiply the aligned level by a ratio — **0.8 (−20%)** by default, overridable per source folder (`GAIN_BY_DIRECTORY`).

**The three encounter tracks take a target of their own: `ENCOUNTER_LOUDNESS_TARGET_LUFS = −17.0`, with no attenuation (a fraction of 1.0).** The −24 reasoning above is about a score heard against silence, and the encounter is the one stretch that is not: the interface is throwing eight authored attack cues, a shockwave and vanilla explosions over the top of it, and the score is what tells the player which body they are fighting. Matched to the same target as everything else, it was reported as simply missing during the fight. The source folder used to carry an annotation asking for the opposite ("reduce volume by 10%"), and that was followed; the user removed the annotation and asked for these three to be louder on 2026-08-29, so the folder is now plain `BOSS战` and this constant replaced the note.

**The number took three passes**: −20 was too quiet, −15 overshot, and −17 is where the user called a halt. Headroom is not the constraint: the three masters measure −3.56 / −7.00 / −11.26 LUFS with true peaks near +0.5 dBFS, so at −17 they land at −13.0 / −9.5 / −5.4 dBFS.

**The unrendered layer's single track works the same way, at `UNRENDERED_LOUDNESS_TARGET_LUFS = −23.0` with a fraction of 1.0.** It is the one place in the project where the score competes with the mod's own audio rather than with vanilla: everywhere else the opposition is vanilla ambience and the signal beds (around −24 dBFS peak by construction), while down there the only other source is `unrendered/layer_ambience.ogg`, shipped at −20.1 LUFS because it has to carry six minutes on its own. At the default target the track lands six decibels under that drone and never comes out from behind it — the failure the encounter already hit once. Three decibels under the bed rather than level with it: the room still belongs to the bed, and the score is the thing that should not be in it. **On top of the ingest level there is a playback-side `UNRENDERED_MUSIC_TRIM = 0.45`**, kept beside the playback rather than baked into the file, the same way the ambience bed is mixed.

The shift is gain only — **no compression, no limiting** — so no track's dynamics are touched. It can run unattended because every master is louder than the target, making every gain negative.

Measured results: default group −26.10 to −25.80 LUFS; the unrendered layer track −23 LUFS (true peak −12.5 dBFS); **the boss group now sits at −17 LUFS, about 8.9 dB above the rest of the set**.

### Why the target is −24 rather than vanilla's own tier

**Those 4 dB of headroom pay for the dynamic range this score lacks, not for it being too loud.** Vanilla's tracks peak **4 to 6 dB** above their own integrated loudness, because they spend most of their length in the quiet passages between. This set does not: its short-term maximum sits only **0.8 to 1.9 dB** above, `game/hi` has a loudness range of just **1.9 LU**, `game/tenshi` 2.9, `encounter/especially_you` 3.3 — all below the lowest of vanilla's 60 tracks, `broken_clocks` (4.2).

**A track with no quiet passages is heard at its integrated loudness the whole time it plays**, so matching that number to vanilla's median (−16.4 LUFS) would make it the loudest thing in the mix in practice. At −24 the set sits alongside the C418 tracks players know best (−26.6 to −27.8 LUFS).

The ratio is always relative to the lossless master, and so is the loudness target, **so re-importing does not compound**. See [Art and asset pipeline](art-pipeline.md).

### RC.3 soundscape overhaul

All 96 non-music events and 242 Ogg Vorbis files are generated by `tools/generate_soundscape.py`. `tools/audio_materials.py` supplies original resonant cavities, granular friction, structural impacts, stalled buffers and broadcast textures. Seven obsolete audio writers were removed. No third-party recordings were imported.

Music tracks, event definitions and existing BGM gains are unchanged. Decoded sample-peak tiers retain the background hierarchy: signal beds approximately −24 dBFS, dead air −32, terminal carrier −28, bacteria heartbeat −14, ordinary interaction −4 and boss actions −3. Peak level alone does not describe perceived loudness; playback retains category gains and the mod master control.

Every encoded file is decoded again to check Vorbis, 44.1 kHz, duration, finite samples, DC offset, 4× oversampled true peak and loop seams. Measurements and SHA-256 hashes live in the [full manifest](../art/audio/soundscape_manifest.json). Historical recording LUFS values no longer describe these assets.

- The 90-tick laser charge and 40-tick discharge follow the animated primary mouth. Cancellation, removal, death, world replacement and disconnect stop the voice. Multiple beams share the firing body's voice, with separate spatial impact accents.
- Tendril tension, impact and recovery use authoritative attack landmarks. Decorative impacts have a per-event/spatial-cell cooldown and a 16-cue per-world tick cap; critical warnings and landing beats bypass it.
- Rework/bacteria contacts use the renderer's walk phase. At most 16 nearby decorative bodies and six foley cues per client tick are processed. Bacteria patter is near-field (18 blocks); its existing 64-block heartbeat retains distant navigation.
- Watcher departures and sparse unseen HIM cloth movement are sent only to their observer. HIM still disappears silently. Terminal gestures and personal targeting warnings remain local.
- Terminal raise/lower/boot-line/completion each have their own recordings. Reversals stop the previous gesture; variant selection avoids immediate repetition. Boss ambience ducks during warnings without changing the score gain.

```text
python -m pip install --target build/tff-audio-tooling -r tools/audio-requirements.txt
python tools/generate_soundscape.py
python tools/generate_soundscape.py --verify-only
gradlew.bat runClientGameTest -PtffClientTestSuite=audio
```

Automated checks establish resource and runtime behavior, not subjective AAA quality or multi-machine listening approval. See [audio QA](../qa/audio_overhaul/README.md).
