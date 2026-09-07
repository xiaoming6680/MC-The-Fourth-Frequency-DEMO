# Testing and acceptance

The test entry points, layered coverage, key invariants and **the evidence actually produced this round** for `1.0.0-rc.6`. Only results that really finished are listed as current evidence; a successful compile is not acceptance.

Release steps and sync rules are in [Repository maintenance](maintenance.md).

## Fixed environment

| Item | Current value |
| --- | --- |
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.141.4+1.21.11 |
| Loom | 1.17.14 |
| Gradle Wrapper | 9.5.1 |
| Java toolchain | 21 |

## Common commands

```powershell
# Compile and process resources
.\gradlew.bat compileJava compileClientJava processResources --no-daemon

# Aggregate pure JUnit / resource contracts. The classpath reuses the remapped Minecraft runtime
# Loom configures for the test source set, and every compiled test class is enumerated with
# --select-class rather than --scan-class-path, so a test class whose method signatures reference
# Minecraft types can no longer be silently skipped. XML goes to build/test-results/unit
.\gradlew.bat unitTest --no-daemon

# check depends on unitTest and the server runGameTest, so it works as an all-green gate; the client suites still run separately
.\gradlew.bat check --no-daemon

# Clean build; `check` depends on both unitTest and runGameTest, and the JAR is produced and deployed only after both pass
.\gradlew.bat clean build --no-daemon

# Server GameTests
.\gradlew.bat runGameTest --no-daemon

# The currently publishable remapped JAR
.\gradlew.bat remapJar --no-daemon

# Client GameTests, default all
.\gradlew.bat runClientGameTest --no-daemon

# Targeted World Interface suite
.\gradlew.bat runClientGameTest -PtffClientTestSuite=world-interface --no-daemon
```

`build`'s last step runs only after every preceding compile and test succeeds: it copies the runnable JAR produced by `remapJar` into a local instance's `mods` folder. **It is off by default** - the destination belongs to one machine and is not committed. Set `tffDeployDir=<path>` in the user-wide `~/.gradle/gradle.properties` to enable it for good, or use `-PtffDeployDir=<path>` for one invocation and `-PtffDeployDir=` to turn it off. With nothing configured it prints a notice, and a missing drive is skipped; neither fails the build (see [Repository maintenance](maintenance.md#local-deployment)). It does not copy the sources JAR and does not delete other mods; running `remapJar` alone only produces `build/libs/` artefacts and triggers no deployment.

Permitted client suite IDs: `all`, `default`, `mainline`, `tools-ui`, `notice-entry`, `alpha-relaunch`, `anomalies`, `anomaly-meta-smoke`, `rework-forms`, `watcher-model`, `horror-entities`, `world-interface`, `terminal-3d`, `screen-filters`, `audio`. `all` covers the whole mainline, tools UI, anomalies, the Corrector, the Watcher model, the World Interface (including the End's weather), the handheld terminal and the screen filters; the notice/relaunch suites still run separately. Only the `anomalies` suite accepts an additional `-PtffAnomaly=<id>`.

**`all` must be a true superset of `mainline`.** For a while it was not: the early `return` in the mainline test was guarded by `runsToolsUi()`, and that predicate is true for both `all` and `tools-ui` — so the full suite returned as soon as the tools-UI checks were done, silently dropping the second half of the mainline (band progression, the four damaged files, the diary unlock, Nether round-trip continuity, the terminal capability model, the Alpha main-menu stamp) **and still reporting green**. `ClientGameTestSelection.stopsAfterToolsUi()` now stops only `tools-ui` there, and `AnomalyClientAutomationContractTest` asserts the predicate in both directions across three suites. A coverage gap that reports green is worse than not having the suite.

### `terminal-3d`: the handheld terminal

`TerminalHandheldClientGameTest`, split out of `M0ClientGameTest` because that suite is a mainline test and only ever glimpses the device once, in one save, on one page, at one facing — and **every problem this device has ever had was outside that view**: both extremes of the view angle, an occupied off-hand, and a camera one frame behind.

It builds a small platform under open sky (indoor walls and ceilings are exactly the occlusion to isolate away), then:

| Check | Form |
|---|---|
| Idle two-handed carry, hand and device at their own depths | Assertion + screenshot |
| **Turning does not move the device** | Assertion (pose z / scale / handSpread must be bit-identical across four view angles) + four screenshots |
| Lift, FOV narrowing, squaring up, settling back, field of view restored | Assertion + screenshot |
| **The swing shove**: a real `swing()` moves the device forward and down and it recovers on its own; the same progress is refused while the open/close performance is running | Assertion + screenshot |
| Off-hand occupied falls back to one hand, off-hand item still drawn | Screenshot |
| The six forms one by one | Screenshot |

The turning check is the reason this suite exists and its only real regression barrier. Most of the rest is "evidence for a human" — the frame buffer cannot be read, so "the device is centred" cannot be written as an assertion.

The device must be **the one Station Zero actually issued**: the server validates binding, owner and world id before opening, and a freshly constructed `ItemStack` is rejected outright. The six-form step also copies the real item and edits `custom_model_data` rather than building another one.

`alpha-relaunch` writes a minimal "the first launch already completed" persistence fixture into the cleared test run directory, then starts the client to verify a second launch. It neither reads nor modifies the player's normal `run/client` config.

## Verification layers

| Layer | Coverage focus |
| --- | --- |
| Filtered pure-logic tests | Anomaly pool/pacing, three-form policy, terminal appearance, dynamic chunk window, no-form-skipping |
| Aggregate JUnit / resource contracts | Schema, payload versions, resource keys, data tables, migration, policy formulas, recovery rules |
| Server GameTests | World events, objective advancement, multiplayer authoritative state, block/entity interaction, mirror topology, persistence (including the single server-wide pursuit slot, roster-filtered attack targeting, and doors being forced open rather than destroyed) |
| Client GameTests | Terminal UI, notice/relaunch, anomaly presentation, models, the World Interface, the poem and view distance |
| Manual acceptance | Audiovisual safety, multiplayer feedback, window/desktop sequences, the LAN host experience, the replay flow |

## Current evidence

The current simplified RC.6 startup revision uses `7810377` as its baseline and passed `build` (861 unit tests, 99 server GameTests and JAR verification) and the real `notice-entry` client suite in isolation. Startup now uses a one-second brightness fade and a 0.6-second page fade; the existing camera entry is retained. The suite checks bilingual layout, volume preview, transition gates, acknowledgement persistence and actual GPU filter application/cleanup. Terminal first-boot graphics and audio assets are unchanged.

Only the affected startup client suite was rerun. The latest full `all` client and 242-file audio-decode passes belong to baseline `7810377`; see the [RC.6 historical record](../qa/audio_overhaul/rc6.md). This revision's screenshots and limits are in the [simplified-startup record](../qa/audio_overhaul/entry-simplified.md). PCL has the revised package with a verified hash. Other tasks' unfinished model, anomaly and desktop changes remain in the shared workspace and are excluded from this tested package. Manual listening in the PCL instance and real two-client acceptance remain outstanding.

## How the sharp tests are shaped

This section records **why a handful of tests look the way they do**. Every one of them was forced by a real defect; rewriting them the intuitive way reopens the same hole.

### `all` must be a true superset of `mainline`

It once was not (see "Common commands" above). A coverage gap that reports green is worse than not having the suite at all, so `ClientGameTestSelection.stopsAfterToolsUi()` now lets only `tools-ui` return early, and `AnomalyClientAutomationContractTest` asserts that predicate in both directions across three suites.

### Gate the end state, not the peak

The `experience_gap` displacement gate used to fail intermittently, and both failures read exactly the same distance (4.0498077...). The cause was not unsteady walking but **measuring at the wrong moment**: `AnomalyServerEffects.MovementTask` advances the player on **server ticks** while the scenario counted **client ticks** to the peak before measuring. Under a loaded `all` run the integrated server falls behind the client, so displacement became a clean function of the server tick count - losing the race stopped it at the same spot every time.

The peak now only asserts that the movement lease is still driving the player (`activeLeaseCount() == 1`), and the >=8.0-block gate moved verbatim into the cleanup assertion, whose first check is already `activeLeaseCount() == 0` - i.e. all 36 server ticks provably ran. **The threshold was not lowered**: its purpose is to prove this was real pathfound movement rather than a nudge.

### Discontinuity is the defect; speed is not

The first version of the rig assertion read "no bone may move more than N blocks per tick" and produced 425 violations - most of them **deliberate fast motion** (a grab drags the skull across half a second at 2.5 blocks per tick). Rewritten as "a frame whose displacement exceeds 4x the mean of the 4 frames on either side and is over 1 block in absolute terms", it dropped to 56, every one pointing at a real cause; fixing them one by one took it to 0.

The test then found two previously unknown defects: `action 0` (pure idle) also jumped on fixed ticks, traced to `idleHeads()` using a wrapped timestamp as phase and producing an out-of-order keyframe table; and the grab-throw recovery lasted only 1 tick. Neither is a position player feedback could have located.

### Auto-opening the terminal: the test is on the client, so the assertions are too

The hard part of "the join that hands the terminal over opens it" is not whether but **when**. The server cannot see whether the player is looking at the world or at a loading screen, and a screen put up under one of those is closed again by vanilla when the load finishes - the player sees nothing and the log says nothing.

So the whole test lives in `TerminalAutoOpenPolicy` (main, pure), and `TerminalAutoOpenPolicyTest` asserts the decision table: a world not yet shown **neither opens nor expires** (a first world generation can outlast the entire budget); a terminal that never reaches the hand **expires** rather than popping up minutes later; the player's own right-click wins; and the 20-tick settle beat is shorter than the 600-tick budget.

The same class pins three pieces of wiring it cannot see from inside: the offer is sent only from the branch where `issueTerminalIfNeeded` returned true (otherwise every rejoin would re-open the terminal), the payload type is registered on `playS2C` (an unregistered type disconnects the client that receives it), and the client **never calls `setScreen` itself** but sends the right-click request instead (otherwise the server's validation is bypassed).

**The decision was right and the landing was not - and only the client suite could catch that.** The decision table governs when the request goes out; after it does there is still a server round trip plus half a second of opening animation, and `TerminalHandheldAnimator.presentScreen` used to call `setScreen` unconditionally. Anything the player opened inside that window - the inventory, the pause menu, video settings - was evicted when the animation finished. The auto-open widens the window by a whole round trip and removes the one thing that excused it: a right-click is at least something the player pressed half a second ago, while nobody asked for the greeting.

What caught it was M0's locked-render-distance assertion: it opens video settings to read the option list and got a `TerminalScreen` with `children=[]` instead. The animator now takes the screen only when it found it empty; covered, it abandons the opening outright and sends a `CLOSE`, because otherwise the server goes on holding a view nobody is looking at. That assertion is therefore the regression barrier for this rule - it is the one place in the suite that deliberately opens another screen inside the opening window.

### With no framebuffer to read, pin the pure function

The guidance readout is composed per frame on the client; the server sends a twenty-byte payload. So the two things worth pinning are that the fade window must be substantially longer than the send interval (or one dropped packet makes it flicker), and that the HUD and the terminal home page must call **the same method** (or two renderers of the same bearing will eventually disagree). Both are pure functions and were written as unit tests; how it looks is a manual check.

For the same reason the only real regression barrier in `terminal-3d` is the "turning must not move the device" assertion (z / scale / handSpread must be bit-identical across four view angles). Most of the other screenshots are evidence for a human: with no framebuffer to read, "the device is centred" cannot be written as an assertion.

### The unrendered layer: four layers and one explicit exemption

- **Unit**: `UnrenderedMazePolicyTest` asserts product endpoints rather than the hash formula - every entrance is a four-way junction, an exit is **reachable** from any entrance (chunk-level flood fill that treats false walls as passable, because in game they are), an exit is a "false-wall shell around a false-floor core", false and solid walls are mutually exclusive everywhere, there is exactly one exit per 32x32 cells fully inside its own partition square, and the trunk corridors run 4000 blocks unobstructed. `UnrenderedBearingPolicyTest` guards the terminal readout: the eight bearings are sectors centred on themselves (a half-sector-off version passes every due-north/due-south assertion and is wrong over most of the circle), -Z is north and +X is east in Minecraft (inverting it consistently sends every player the opposite way), and no bearing is given when too close. `UnrenderedPlacementTest` asserts that 16 slots x 64 visit variants are all at least 100,000 blocks apart, inside the world border and standable, plus **the pairing between view distance and generation distance** (generation must exceed visibility; the two constants live in different files with nothing else watching them).
- **GameTest**: `UnrenderedLayerGameTests` asserts only what runtime can answer - the three datapack files agree with each other and their `min_y`/`height` match `UnrenderedLayerLayout`, the generator codec really is registered in `BuiltInRegistries.CHUNK_GENERATOR` under the id the dimension file names, the entity type is registered and its default attributes really are attached (`FabricDefaultAttributeRegistry` is a separate call; missing it compiles fine and throws on first spawn), and both sound events and their files exist.
- **Contract**: `MultiplayerIsolationContractTest` asserts from the source that all twelve environment systems ask `PrivateDimensions.isPrivate` rather than recognising only the mirror.
- **Deliberately absent**: a client automation scenario. `AnomalyClientScenario.UNCOVERED` exempts this one by name with the reason written down - that suite drives `AnomalyPresentationController`, while this anomaly's presentation belongs to `UnrenderedLayerClient`, with no fixture, no overlay and no assertable "restored" state. The exemption is listed explicitly, so a new anomaly still cannot quietly ship without client coverage.
- **Speed is measured, not derived**: `theBacteriaSpeedLandsBetweenSprintingAndSprintJumping` lays out flat ground under real physics, runs it, and converts peak displacement over the steady stretch into blocks per second. It caught a real error once - a derived 0.335 measured only 4.94 blocks/s, **slower than sprinting**, which would have shipped the Bacteria as scenery.

### What the contract tests have actually caught

Their value is not in re-running things known to be correct but in that each one corresponds to a failure that only shows up at real runtime. Recent examples: `unrendered_heartbeat` is a fixed-radius event yet was written in `sounds.json` as a bare string (which cannot carry `attenuation_distance`); a sentence in a comment was read as a `@Shadow` field by the regex in `ShadowFieldOwnershipTest`; one terminal string tripped the "Chinese copy must use 异象" check (added to the allowlist as an instrument readout rather than an anomaly system, with the reason recorded); and a source `.ogg` was really FLAC-in-Ogg, which passes every container check while the game silently plays nothing.

## Key personal-pursuit invariants

- The mainline only raises `allowedForm`; `actualForm` advances at most one step per success, and pending pursuits never form a queue.
- The three form durations are 60/85/110 seconds, the success interval is 20–30 minutes, and the retry after capture/interruption is 5 minutes.
- Every form must first complete a safe demonstration. Once safety conditions pass, a fixed 200-tick lead-in runs: 80 ticks of terminal reading, 80 ticks of progressive frame-rate decay only, 40 ticks of input lock and hang audio. The lead-in draws no filter or interference overlay.
- The hang audio is a random variant pool rather than a single file: `alpha_corruption_collapse` and `alpha_corruption_warning` have at least 3 each, and `ResourceContractTest` asserts the minimum count, no duplicate entries, that all are real Ogg files and that each is over 16 KB. How the variants *sound* is manual acceptance only; a test cannot stop "it sounds wrong".
- The black screen is switched only by server timing. Entry waits for 7×7 chunks around the player in the destination dimension to be ready and stable for 8 consecutive ticks, up to 200 ticks. From mirror copying through the return to the source world and the loading screen disappearing, loading screens must remain covered.
- One pursuit at a time server-wide; a second player is safely deferred and receives an ordered, readable explanation of the wait rather than a silent refusal. All six mirror dimensions stay registered, because recovery has to be able to find a player left in any of them by an older save.
- The initial snapshot is 5×5 chunks, ±48 blocks vertically, 8192 blocks per session per tick; it streams horizontally with the player's chunk, with no fixed 30-block turnback.
- Copied chunks are never overwritten; when copying falls behind, the player only pauses at the nearest safe position with the pursuit timer paused.
- The initial spawn probe covers the full ring 25–42 blocks around the player, including directly ahead. 42 blocks for 5 seconds, breaking line of sight beyond 18 blocks for 8 seconds, and the player killing it personally are all authoritative successes.
- A real pursuit has no boss bar; only a red "attempt to escape" is pinned, other prompts are locked until the full return, the client uses a black-and-white low-bit-depth mosaic filter, the heartbeat plays positionally at the Corrector's coordinates (with stereo bearing and distance falloff), and the player has icon-free night vision throughout.
- During a pursuit session, the pause menu's save-and-quit / disconnect buttons must be disabled with replacement copy, restored once the session is fully cleared.
- The capture freeze/fault audio and the green success resolution are both fixed at 60 ticks; capture removes 2 points of maximum health, success adds 2, and a technical interruption penalises nothing.
- After surviving, escaping, killing it or completing a debug pursuit, the temporary warning is deleted; the "the magnetic field around the user is very unstable..." record is written when the return completes.
- The pursuing Corrector uses fast breaching, support demolition and vertical leaps; the player can still kill it normally. Meeting the cave test drops it to 0.25 base speed and a 1.04 path multiplier, restoring to 0.31 and 1.32 on leaving.
- Mirror destruction drops nothing; temporary-placement refunds are idempotent; disconnect/restart never leaves a player in the mirror or swallows items. The cross-dimension return restores visibility first; if death or an admin teleport already removed the player from the mirror, the session is only cleaned up and the player is not dragged back to the entry point.
- Mirror dimensions must never contaminate the mainline, anomalies, navigation, Nether round trips, End entry or finale state.

## Key World Interface invariants

- The roster is built from players who hand a terminal over (1–8); other people connecting or leaving is irrelevant to it. Withdrawal works inside the window, a lapsed window or a failed submission must return terminals, and starting the fight takes an explicit summon.
- Health is `600 × (1 + 0.5 × (roster - 1))` — 600 for one, 2700 for eight; the three forms only advance.
- Collapse is 12000 ticks (10 minutes); it pauses when everyone is offline; a same-tick timeout outranks lethal damage.
- The ten stability anchors affect healing, damage taken, cooldown and the radius-8 stability zone by the current formulas, but affect neither boss movement nor collapse progress; the zone protects player damage and terrain together. The first positive-damage player attack breaks an anchor immediately, and each break plays about 60 ticks of golden wash on the HUD and swaps the anchor label to "TAKES MORE · HITS FASTER".
- Anchors are carried by `StabilityAnchorEntity`. `StabilityAnchorGeometryTest` locks the pure geometry and timing rules (model height 44 units = 2.75-block collision height, width 28 units = 1.75 blocks and never over 2, the relay core uniformly 2 blocks above the origin, four seamless irreversible collapse phases, per-tick and whole-fight particle caps); `StabilityAnchorContractTest` locks the cross-layer contract (entity/model layer/renderer registration, four five-stage claw chains with an open emitter end, texture size and sparse emissive mask, bilingual keys, beam endpoints from shared constants that follow the boss's 3D position, migratable legacy tagged crystals, and a collapse effect free of explosions, block writes and drops). `ResourceContractTest` additionally confirms `EndCrystalMixin` is gone from both the manifest and the source, so ordinary end crystals are back to vanilla behaviour.
- Server runtime is covered by `WorldInterfaceGameTests`: the arena creates exactly 10 anchors with unique indices and deterministic UUIDs, a missing live anchor is recoverable, a destroyed anchor never revives, zero-damage / non-player / spectator / non-combat-phase sources cannot break one, and breaking one updates damage taken and the stability zone on the same tick without changing the fixed collapse timer.
- The eight actions' telegraphs, exact damage, caps and exclusive control stay stable. Only the laser, breath bolt, sky lance, grab-and-throw and tendril lash are targeting locks; weapon impound and the hotbar sweep use unavoidable deprivation notices. Only impounded weapons enter the recovery ledger; hotbar items stay ordinary world drops.
- The drawn body's lower edge hangs at a fixed 8 / 14 / 18 blocks, and the entity origin must always be above ground; clearance is measured against the model's real lowest shell and must not be derived from core height and half-width.
- The central skull must clear the floor **throughout the animation**: `theCentreHeadClearsTheFloorThroughTheWholeAnimation` sweeps 21 health steps × 4 gaze steps × 241 ticks, measuring the **underside of the jaw** rather than the skull hitbox — the jaw hangs below the box, so measuring only the box would miss the part that really enters the ground. The current margin is 1.69 / 1.12 / 0.72 blocks, deliberately what remains after spending the raise, and lowering it requires measuring first.
- The skyhold window for forms 2 and 3 must be **strictly under 40%**: `WorldInterfaceSkyholdPolicyTest` measures the raised proportion tick by tick over the whole collapse timer (rather than dividing two constants), and asserts form 1 never rises, a phase's tick 0 is always at the station, the lift curve is continuous (single-tick displacement < 1.5 blocks), and there is exactly one climb cue per cycle.
- Head gaze must never knot the three neck chains: `theHeadsNeverKnotHoweverTheyAreLooking` asserts the same non-intersection contract as the rest pose across three forms × six actions × 13 yaw steps × 9 pitch steps × 4 moments. Gaze yaw lands mainly on the skull's and `neck_b`'s **own-axis** rotations (rotating about the hanging direction moves no downstream joint); only the 0.20-coefficient roll on `neck_b` really moves a skull.
- Explosion camera shake is broadcast by the server as `WorldInterfaceBlastS2C` with falloff computed on the client. The per-source throttle rule is the pure function `WorldInterfaceBlastService.permits`, covered by `WorldInterfaceBlastServiceTest` (event cap for one laser sweep, a backwards clock must not mute a source permanently, radius and tier envelope boundaries).
- Attackable parts number 14 / 16 / 20: the body, three heads, two segments per neck, one segment per drawn tendril.
- Hitboxes are bound to the **post-animation** rig: `WorldInterfaceRig` poses the skeleton once per tick (bind pose + clips + procedural drift + structural sag), the server places boxes from it and the client drives `ModelPart` from the same evaluation, with clip data in common's `WorldInterfaceClips`. The central skull's hitbox bottom sits about 1.44 / 0.78 / 0.43 blocks above the ground, well inside one swing (4.5 blocks); the measurement uses `headHitRadius` (including `HEAD_HIT_SLACK`'s 45% margin), i.e. the volume a player can really hit, not the bare skull. The box is anchored on the **jaw** rather than the cranium cuboid's centre, or the bottom edge sits nearly a block above the visible jaw. Heads must land **directly in front of** the storm (model -Z maps to entity forward).
- The three neck chains must not intersect in any form or action sequence: `WorldInterfaceRigTest` asserts a strict form for the rest shape (the sum of the two skull radii) and a loose form across the whole animation (never inside each other), with yaw and roll always signed "outward is positive" by `WorldInterfaceAnatomy`.
- Geometry comes from the Blockbench export `models/entity/world_interface.json`, not from Java: `WorldInterfaceGeometryContractTest` compares every rig bone's pivot and bind rotation against `WorldInterfaceRig.bindPose()` axis by axis (tolerance 0.002), checks that every bone the renderer resolves by name exists, that bones are ordered parent-first, that parts drawn per form stay under `WorldInterfaceModel.MAX_VISIBLE_PARTS`, and that the bbmodel and the export agree on cube count with every cube carrying a known material prefix. `WorldInterfaceBoneBindingTest` and the UV contract in `ResourceContractTest` read the same JSON and `layout.txt`.
- Arrows and tridents resolve at 2.5×, with the multiplier applied before the anchor damage coefficient; the two-argument `adjustedIncomingDamage` remains melee semantics and must not quietly gain the bonus.
- A fixed-range sound's registered radius and its `attenuation_distance` in `sounds.json` must match; variable-range events must not declare `attenuation_distance`.
- Multi-track music events (`music_game`, `music_menu`) must play a whole pass of their pool before repeating, and must not repeat across the seam between passes; `MusicRotationPolicy.rotatingEvents()` and the `music_*` events with a pool size over 1 in `sounds.json` must agree in both directions, and single-track events must not rotate.
- A pass must end on `passComplete(event, poolSize)` counting the pool, never on the re-draws running out: 400 passes from a genuinely random source at the shipped pool sizes (4 and 9) must each use the entire pool. Exhausting the re-draws may cost one repeated track and must not reset the pass or discard anything it has not played yet.
- The score's four inventories must describe the same set of tracks: the files under `sounds/music`, the `music_*` events in `sounds.json`, the "now playing" keys in both lang files, and the per-context track blocks in the End Poem credits. `ResourceContractTest` lines them up (every file present, none claimed by two events, every file named in both languages, no key left pointing at a deleted track) and matches the credits by **count** rather than by name — the roll is written for a reader, not as a manifest. It went on crediting two tracks that had already been replaced, because nothing was watching.
- The permanent scar budget is 8192 blocks total and 32 per tick, and must not destroy protected structures or block entities.
- Success timing is fixed at: body 0–180 ticks, empty field 180–220, summon 220–340, dragon appears at 340, exit opening 340–500; the first line at 410, the second line and the exit both at 500.
- Resolution opens a 3×3 exit and returns through the vanilla WinScreen/respawn path; only the World Interface branch replaces the poem, credits and `postcredits_*.txt`.
- View distance unlocks permanently to 16 only after the success poem is confirmed and the player is really back in the Overworld.
- A successful ending releases the ending score before restoring resource packs; both endings keep the pause-menu exit path.
- Failing players each see missing textures on their own client, and the LAN host branch must not contaminate the server or its guests; a failure relaunch keeps the Alpha presentation until F8 recovery completes.
- After resolution, ordinary anomalies, gap pressure, decay and pursuits close permanently.
- F8 only handles an ending lock that already exists; save isolation uses a lossless marker only, reading "sealed" on success and "corrupted" on failure.

## Documentation and resource static checks

- Both language JSONs parse, with 955 keys each and fully symmetric key sets.
- All relative links in the READMEs (both languages) and `docs/**` resolve.
- Scans for stale test numbers, the old health formula and retired finale semantics find no residue.
- `sounds.json` holds 106 events and 263 references; the 263 OGGs in the repository match its references one for one, the difference is empty in both directions, and every one of them measures as Ogg Vorbis.

The manual flow for a candidate build is in the [Manual acceptance checklist](acceptance.md).

## Release artefacts

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `thefourthfrequency-1.0.0-rc.6-sources.jar` | 52,424,151 | `0522C15369704538AEF60AE80879B862A906F07B776D8741D111A87A806636EE` |
| `thefourthfrequency-1.0.0-rc.6.jar` | 52,986,041 | `33058A75680C71E09CF8FB16FE7715542ED8213B9AD9679B60F50EEE29D114C4` |

## Still outstanding before release

- Verify pursuit queueing (the second player is deferred and can tell why), disconnect/reconnect, full-inventory refunds and dynamic chunk catch-up with two real clients.
- Check mixing, strong flicker, multi-monitor/DPI, the LAN host branch and long-session TPS on the target hardware.
- Complete every manual item in the [Manual acceptance checklist](acceptance.md), especially one full multiplayer World Interface fight each at 2 and 4 players.
