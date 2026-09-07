# Terminal interface and handheld form

The single detailed description of the terminal's **appearance layer**: coordinate space, layout constants, palette, animation timings, the first-boot walkthrough, and the item's own 3D model and handheld animation.

This document states **what the rules are**. Trade-offs and rejected alternatives are in [Design notes](design-notes.md#terminal-interface).

Gameplay truth is elsewhere: page semantics and tool unlocks in [Anomalies, terminal forms and personal pursuits](anomalies-and-pursuits.md), the mainline chain in [The World Interface finale](world-interface.md), protocol and persistence in [Architecture](architecture.md).

## Coordinate space

The terminal draws on a virtual **512×256** canvas. `TerminalScreen#render` blits the panel backdrop in screen space, then `pushMatrix` → `translate(left, top)` → `scale(scale, scale)`; everything after that is canvas coordinates. `TerminalUiLayout.panelScale()` fits both window dimensions with 16 pixels of margin, up to 2×; the old 0.55 minimum no longer pushes small windows off screen. `local(mouseX, mouseY)` converts screen → canvas, and all hit testing happens in canvas coordinates.

> **The scissor trap.** `GuiGraphics#enableScissor` **runs the current pose itself**, so the clip rect must be given in canvas coordinates and never pre-converted to screen pixels — the transform would be applied twice and clip the whole panel away. The clip must also be opened **before** pushing the content offset, and `disableScissor` called **before** the outer `popMatrix`. Vanilla's `ScissorStack#push` intersects with the parent rect, so nesting is safe.

## Layout

All `Bounds` constants live in `terminal/TerminalUiLayout` (**main source set**, so JUnit tests it directly). `TerminalUiLayoutTest` pins: text and interactive regions must lie inside `DISPLAY`; the hardware column must lie inside `HARDWARE_SAFE` and never enter `DISPLAY`; the three structural bands never intersect.

| Band | y range | Contents |
|---|---|---|
| Tab strip | 45–62 | HOME / TOOLS / RECORDS / FILES |
| `PAGE_BODY` | 70–199 | Current page content |
| `STATUS_BAR` | 202–215 | Holder / world time / link / protocol |

Hardware column (x ≥ 389, not part of `DISPLAY`): oscilloscope, compass, tuning slider, LCD, close hint, unread lamp.

- `FOOTER` is a historical alias of `STATUS_BAR`, kept only so tests that reference it by name still compile.
- The status cell is called **"link", not "band"**: it reads `bandStage` (how far this device is authorised to connect), unrelated to the dial on the right.
- Each cell is a **dim fixed label plus a bright adaptive readout**: if the readout does not fit it is scaled down first, then truncated with an ellipsis at the pixel font's legibility floor. **The label never scales.**

**The line under the tool grid serves two states**: hovering an unlocked tool shows that tool's `.summary` in green (the same sentence as the detail page, with no second copy key); a locked one still shows its dim unlock condition. **No cursor-following tooltip** (see the scissor trap).

### No backing plates

**Hierarchy comes from lines, whitespace and value — never from painting an opaque block on top.** The display area is already one sheet of glass (`GLASS_BACKDROP`) plus a CRT layer; filling an opaque rect on top of it reads as a sticker on the device rather than the device's own screen.

Only three kinds of fill remain permitted: **the translucent card body** (`CARD_BODY`), **instrument recesses** (oscilloscope / compass / slider / progress track), and **the walkthrough dim** (`ONBOARD_DIM`, which darkens existing content rather than replacing it).

When an area needs to be "cleared", the correct approach is **not to draw that area's content**. The power-on self test does exactly this: during `BOOT` the whole page is not rendered. Likewise, when the walkthrough writes guidance into the status bar, the resident readout **yields** instead of being covered.

## Palette and the CRT layer

Everything lives in `client_ui/TerminalVisualTheme`. `ResourceContractTest` pins that nine core colours are defined in that class and referenced in the terminal screen as `TerminalVisualTheme.X`.

> **The 17 `import static` lines at the top of `TerminalScreen` must not be deleted.** The contract asserts on source text containing substrings like `TerminalVisualTheme.GREEN`, and the import lines are the only place they match. An IDE's "optimize imports" breaks it silently.

The resident overlay is drawn by `client_ui/TerminalChrome` and covers only `DISPLAY`:

| Element | Parameters | Hard constraint |
|---|---|---|
| Scanlines | pitch 3, alpha 14, pure black darkening | **No tinting, no scrolling**; pitch 2 halves the pixel font's apparent brightness at this alpha |
| Vignette | 6 rings of decreasing alpha | |

Per-frame cost: the CRT layer plus structural decoration is about **105 quads/frame** (59 scanlines + 24 vignette + ~20 corner marks and title bar), still within one GUI batch. Check against that number before adding a layer.

## Everyday opening and first-boot graphics

RC.6 removes the four-line check on every terminal open, including its policy, renderer and obsolete tests. Ordinary opens show the selected page immediately; clicks and Escape need no self-test skip.

The opening shows a simple CRT-green hexagonal logo containing a broken signal trace and terminal cursor. It briefly lights up and fades into the audio page without the graphical test card. The mod-list icon and opening share the same transparent asset. The audio-to-disclosure page transition retains its frequency sweep and raster recovery. Terminal first boot keeps its six banks of memory lamps and persistent scope trace in `AnalogBootGraphics`.

The first terminal boot still follows `TerminalOnboardingPolicy`: six checks over roughly 3.12 seconds before fading into the profile. Text stays silent with one quiet completion cue. Each animation adds about 160–180 basic primitives per frame only while that short presentation runs.

`FirstRunNoticeScreen` owns game audio calibration and disclosure. The logo intro lasts 30 ticks (about 1.5 seconds), with a simple fade-in, brief hold and fade-out; pages use a 20-tick (one-second) frequency-sweep transition, swapping at the midpoint with disabled controls. Resource reload pauses the clock; rebuilding widgets or changing language cannot bypass acknowledgement.

Confirmation starts a 64-tick (3.2-second) camera move into Minecraft running inside the terminal. Disclosure fades, the menu appears behind the glass and grows as the solid frame moves out of view. Five gentle focus/glass filter steps, scanlines and reflections then recede. Motion starts and ends at zero speed, with the last menu frame matching the real title screen. Filter requests expire each frame.

First-world corruption retains its structure and timing, without these loading animations. Its wall contains only densely repeated “败”; embedded wording and the lower-left timecode are removed. Brief warnings precede the wall; the original crash onset gains impact and cuts at blackout.

## The sound of a press: six grades

What a press *sounds* like is as much an appearance contract as what it looks like. The table lives in `terminal/TerminalContactVoice` (**main source set**, a plain enum, tested directly by `TerminalContactVoiceTest`); playback is `client_ui/TerminalClientAudio#contact`.

Everything except "move the highlight" used to share one `click()` at one weight - changing page, opening a tool, backing out of one, ordering the server about, clearing an unread marker. That is a device with one button on it: the player could hear that *something* was pressed and never hear *what kind* of thing had happened.

| Voice | Sample family | Base pitch | Relative volume | When |
|---|---|---|---|---|
| `MOVE` | `terminal_keypress` (key) | 1.00 | 0.34 | Moving a highlight through a list |
| `TAB` | `click` (contact) | 0.68 | 0.48 | A page actually changed |
| `OPEN` | `click` | 1.18 | 0.44 | One level in: a tool detail, a file body |
| `BACK` | `click` | 0.84 | 0.36 | One level out; un-pinning a tool |
| `COMMIT` | `lock` (bolt) | 1.14 | 0.52 | **The server was told to do something**: rescan, start/stop guidance, choose a destination |
| `ACKNOWLEDGE` | `terminal_detent` (detent) | 0.60 | 0.22 | The unread lamp going out - the machine finishing, not the player starting |

Three audible rules, each asserted by `TerminalContactVoiceTest`:

- **Weight rises with consequence.** `MOVE` is the lightest, `COMMIT` the heaviest, everything else in between.
- **Direction is audible.** `OPEN` sits above `BACK` in both pitch and volume, so going in and coming out are not one click with different pixels.
- **The bolt is reserved.** `COMMIT` is the only contact using the `lock` sample, and it deliberately does **not** route through `lock()` - that one is the receiver finding a band, and the first-run notice client GameTest asserts it plays zero times there.

No new recordings are needed: the four existing sample families carry most of the distinction on texture alone, and two voices sharing a family are held at least **0.10 apart in pitch**.

### The panel ages with the stage

Contact pitch drops with the **terminal's visual stage**, `WEAR_PER_STAGE = 0.03` per step - 6% end to end, about a semitone, over three steps.

**That is 0-2, not 0-5.** What the client is handed is `TerminalSnapshot.visualStage`, i.e. `PursuitProgressPolicy.terminalVisualStage` - a three-step tier computed from resolved chases, the allowed form and the anomaly stage, and clamped to 0-2 on the way in. There are five anomaly stages, but the panel is never told which one it is in; writing the bound as five would leave two thirds of the range unreachable and the wear per step three times smaller than it reads.

Contact feedback stays light and automatic guidance is silent. The original terminal carrier runs once while the screen is open and stops on close or world exit.

Ordinary opens no longer show a check; the stage still affects contact pitch.

The stage is latched by `updatePanelStage(stage)`, which updates state without starting a noise bed. Closing the screen does not reset it.

## Tuning feedback (RC.6)

Dragging, scrolling and arrow keys share quiet notch feedback, limited to once per 2 ticks, with no sweep loop. A successful lock replaces that notch; releasing adds no sound. Automatic guidance text is silent, while important results retain light feedback. See [Audio](audio.md) for output gain.

“The terminal is shaking violently” uses the original `terminal_anomaly` asset at the existing warning pitch, delivered only to the affected player. It is separate from the original terminal carrier. Source and hash: `docs/art/audio/original_cues.json`.

## Animation

Timing constants and easing live in `terminal/TerminalMotion` (main, pure, tested directly by `TerminalMotionTest`); runtime state in `client_ui/TerminalMotionState`.

| Purpose | Clock |
|---|---|
| Page transitions, tab indicator, press feedback, staggered card fade-in, boot typewriter | Linear on a millisecond timestamp |
| Smooth scrolling, task progress bar | Frame-delta exponential `1 - exp(-dt/τ)` |
| Pulsing breath | `renderAge` (ticks) |

> **Do not use `renderAge` for durations in new code.** Its integer part is pinned to the tick rate, so server lag directly changes animation speed. Use the millisecond clock already computed in `render()`.

Frame delta is clamped to 100 ms, so returning from alt-tab does not run the whole animation in one frame.

**Smooth scrolling**: the four scroll positions (records / tool detail / file list / file body) keep their **integer fields authoritative** — those are simultaneously the hit test, the clamp bound and the contract assertion point; smoothing is only a display layer. **Hit testing does not follow the animation.** Page changes, file-view resets and snapshot-driven cache clears must snap directly into place.

## First-boot walkthrough

Appears only the first time a terminal is opened on a new save; **one-off and not replayable**. Phase policy is in `terminal/TerminalOnboardingPolicy` (main, pure).

### The terminal opens itself, once

**On the join that hands the device out, it comes up on its own** rather than waiting for the player to find it in the hotbar and right-click it. The first thing that happens in this mod is waking up in the station holding a device that has already bound itself to you; making the player open an inventory first puts an inventory in front of that.

The split is "the server decides whether, the client decides when":

- **Whether**: only the branch in `ZeroStationService.onPlayerJoin` where `issueTerminalIfNeeded` returns true sends `terminal_auto_open`. It is bounded by the same ledger the grant is, so it happens once per player per save and never again on a rejoin.
- **When**: the rules are `terminal/TerminalAutoOpenPolicy` (main, pure), fed real state by `client_ui/TerminalAutoOpenController`. **Only the client knows whether the player is looking at the world or at a loading screen** — a screen opened under one of those is closed again by vanilla when the load finishes, and the player would have seen nothing.
- **How**: what the client sends is the same `TerminalOpenPayload` a right-click sends, validated by the server the same way. This path never calls `setScreen` itself.

The clock counts only ticks where the world was actually in front of the player: a screen, an overlay, or a safety notice that has not been dismissed neither counts nor advances the timeout.

| Condition | Result |
|---|---|
| A terminal screen is already up, or the handheld performance is already running | **Give up**: the player's own right-click is the better version of this event |
| The world is not being shown yet (loading screen, overlay, notice not dismissed) | Wait, and **spend none of the budget** — a first world generation can take a while |
| 600 shown ticks (30 s) and it still could not open | **Give up**: the terminal is not in hand (dropped, or swapped away). A screen that opens itself minutes into play is taking the controls, not saying hello |
| The main hand holds a terminal bound to this player, and 20 shown ticks have passed | **Open** |

Those 20 ticks are not a load guard — ticks where nothing was shown were never counted. They are there so the device rising into frame reads as the device doing something rather than as part of the loading screen.

| Phase | Advances on | Permitted input |
|---|---|---|
| `BOOT` | Self test finishing (~3.1 s) | Wait for the first graphical check to complete; no typed lines |
| `STEP_1`–`STEP_4` | Pressing Next (Finish tutorial on the last step), Enter or Space | All share the button's reading delay; the tab strip and number keys are swallowed |
| `RELEASED` | The damage safety valve firing | Everything restored |
| `DONE` | 40 ticks after step four lands | Everything |

Step order is **TOOLS → RECORDS → FILES → HOME**.

- **Each step is an explanation**: the panel gives the page's name plus three lines of description, typed in line by line; Next stays dim until they finish.
- **Nothing at the top points anywhere**: the dim is uniform across the whole display, tab strip included. The status bar reads "Next page: Tools" rather than "Select the Tools tab". `dimBands` is retained and still tested — there is simply nothing live behind this scene to cut a hole for.
- **The button advances nothing by itself**: it sends **exactly the same** page request as clicking a tab (the same `selectPage`), and `learn_terminal` still fills naturally from page changes. **The client has no path of its own to advance a task.**
- **The tab strip is not a control during the walkthrough**, and number keys 1–4 are swallowed with it. There is one control, in one place.
- **Next is drawn inside the status bar** (`ONBOARD_NEXT`, 13 px tall), not in the explanation panel; the step counter `1/4` sits to its left.

### The one-line brief

A single line at the bottom of the page describing **the page currently displayed**, opening with that page's tab name. Position `TerminalUiLayout.ONBOARD_BRIEF`, inside the already-dimmed page area, **drawn only during the walkthrough**.

| Page | Brief |
|---|---|
| Home | The current objective, recommended tools and the latest record |
| Tools | Six field tools, unlocked one at a time along the mainline |
| Records | Events the terminal noted, newest first |
| Files | Recovered files, and that incomplete journal |

The brief body (`onboarding.brief.*`) and its wrapper (`onboarding.brief.current`) are separate keys, and the tab name is taken from `tab.*` itself. Which page is described is decided by the pure function `TerminalOnboardingPolicy.briefSubject(phase, currentPage)`.

### The exit lock

The terminal cannot be closed during the walkthrough. This is the **only exception** to the "exit paths" boundary in the [World bible](world-bible.md), with four testable conditions:

- **One-off**: only on the first open of a new save.
- **Definite end**: the close hint changes to "cannot exit right now". **A screen that says "press Esc to exit" while Esc does nothing is absolutely forbidden.**
- **Safe release**: any damage taken unlocks immediately, with progress kept.
- **Server override**: a forced close always wins.

Disconnect, rejoin and server restart resume from the current step; once any tab has been clicked, the self test never replays.

### How the highlight works

The complement is dimmed; there is no full-screen mask. The outline pulse is a continuous raised cosine with a 2-second period (0.5 Hz), far under the 3 Hz limit; the test asserts a bounded delta between adjacent ticks.

### Completion hold (`COMPLETION_HOLD_TICKS`)

The trigger is `objectiveIndex` increasing — that index only moves forward, and only **after** the reward has actually been paid. The client then holds the **previous snapshot**'s objective line (rewritten as n/n), reward item and count on the card for **60 ticks (3 s)**:

- Card outline, progress bar and reward frame all move to the `CLAIMABLE` palette;
- the progress bar **completes its last segment rather than jumping**;
- a "task complete" label sits in the bottom-right;
- after the hold, the bar **climbs from 0 toward the new task** instead of falling from full.

Three seconds is longer than the walkthrough's own 40-tick closing prompt, so "walkthrough complete" and the card's completed state appear on the same screen.

**The card does not add a line reading "granted: bread ×6".** The card has already said the same thing four times. With the terminal **closed**, the notice stack carries it instead: `message.thefourthfrequency.task.completed_reward_claimed` announces the task name along with it. Task names are a separate key group `terminal.thefourthfrequency.task.name.*`, and `TerminalTaskService.taskName` writes them out case by case rather than assembling keys from ids. The notice stack is **frozen** while any screen is open.

## First-boot profile questionnaire

After the self test and before the four-step tour, once per new save. Rules in `terminal/TerminalProfileQuestionnaire` (main, pure); drawn by `client_ui/TerminalOnboardingOverlay#drawProfile`.

**Answers are not buttons — they are lock points on the band.** Each question has 2–3 options, each sitting at a frequency on the 0–100 tuning axis. **Both the question and the options are always legible**; tuning reports which option you are on (the needle rises, the colour warms) but does not decide whether the text can be read.

| Parameter | Value |
|---|---|
| `LOCK_RADIUS` | 6 |
| `COMMIT_HOLD_TICKS` | 35 (about 1.75 s) |
| `ASSIST_SWEEP_MILLIS` | 15000 (15 s on one question with no lock ever achieved, and the terminal sweeps to the nearest lock point itself) |
| `REVEAL_RANGE` | 32 (text reveal range, much wider than the needle's strength curve) |
| Option spacing | ≥ 29 (asserted by test) |

**The needle takes the maximum of "a sharp station" and "a gentle carrier floor"**, so it is non-zero and has a gradient everywhere on the band — with the exit locked, the needle is the player's only guide.

**One-off and server-held.** `ANSWER_PROFILE` carries the displayed question and option (`question * 16 + option`). The server requires an exact question match before advancing, so duplicate or delayed answers cannot consume the next question. Another player's terminal cannot submit answers. Update clients and servers together. Closing the terminal and disconnecting both seal in place; unanswered questions stay **unanswered** and are **never filled with a default**.

The keyboard path is required, not optional: `←/→` step by 1, `Shift+←/→` jump one lock radius, `Enter/Space` commits.

### The sixth question, and how much gets explained

Six questions, each with exactly one consumer. The sixth, `prior_contact` ("Have you handled this model of terminal before?" / no · yes · unclear), is consumed by `TerminalGuidanceVerbosity`, whose only use is deciding **whether the unread reminder also says "hold the terminal and right-click to open"**.

The terminal is opened by **holding the bound one and right-clicking**, which the mod says nowhere outside the first-boot walkthrough; the unread reminder is also the one line that fires precisely when somebody has not been opening it, so it is the only place that half-sentence belongs.

This falls on the permitted side of `ProfilePreference` rather than the forbidden one: it changes **how much the terminal says**, and touches nothing about anomaly count, interval, intensity, timers or rewards. Two saves differing only here are identical everywhere a player could measure. That is also why an unrevisitable answer is not dangerous here — the worst case is a first-time player who answered "yes" and gets half a line less on one prompt, which is exactly the experience every player had before this question existed.

| Answer | Verbosity |
|---|---|
| No / unclear | `VERBOSE` (explains) |
| Yes | `TERSE` (does not) |
| Unanswered, profile taken | `TERSE` — an old save belongs to somebody already playing, and starting to teach them how to open it reads as the terminal having forgotten who they are |
| Unanswered, profile not taken | `VERBOSE` — the damage failsafe can release the walkthrough early, and a player who never reached the question must not be punished for the interruption |

## The item: 3D model

The terminal is **a fixed, one-piece, heavy handheld device**. It does not flip, fold, unfold, slide, or deform mechanically at all — this is a product boundary: the device is a sealed instrument, and the moment it opens and closes, "is this machine working?" becomes something the player reads off a hinge instead of off the screen.

| Asset | Path |
|---|---|
| Six 128×128 UV atlases | `textures/item/old_terminal_shell_0.png` … `_5.png` |
| Six item models (each with all eight `display` transforms) | `models/item/old_terminal_held_0.json` … `_5.json` |
| Blockbench editable source | `docs/art/terminal/old_terminal_shell.bbmodel` |
| Generator | `tools/generate_terminal_3d_assets.py` |

The geometry is **5 elements**: a 14×7×2.5 body carrying the whole face, plus four protruding brass frame strips; total size with the frame is **15×8×3**. **All six forms have byte-identical geometry** — only the atlas differs, and the contract test compares the serialised `elements` directly.

The face is **2:1**, matching the 512×256 panel backdrop, and the CRT occupies about **62%** of the width — the same fraction `TerminalUiLayout.DISPLAY` occupies on the panel, guarded by a contract test.

The face follows the UI and the concept art `docs/art/terminal/terminal_six_forms_full_controls_concept.png` exactly: a large inset CRT on the left; on the right, top to bottom, the oscilloscope, circular compass, tuning slider, two-line LCD and close-hint strip; **plus a separate small red unread lamp to the right of the oscilloscope**. Black cast iron, worn brass corners, four corner screws; the cyan and red stages add oxidation cracks creeping along the frame.

The item definition forks on `minecraft:display_context`: the **inventory slot** uses the flat icons `old_terminal_0`–`_5`, every other context uses the 3D shell. Both branches take the same 0–5 from **slot 0** of `custom_model_data`.

### The six forms

| Form | Stage | Unread lamp |
| ---: | --- | --- |
| 0 | Green, ordinary | Off |
| 1 | Green, ordinary | **On** |
| 2 | Cyan, active | Off |
| 3 | Cyan, active | **On** |
| 4 | Red, anomalous | Off |
| 5 | Red, anomalous | **On** |

**An odd form is its even neighbour with the indicator lit, and not one other pixel may change.** The contract test compares pixel by pixel: every difference between 0 and 1 must fall inside the lamp's 6×6 window, and must be non-empty.

The mapping is `visual stage × 2 + (attentionActive ? 1 : 0)`, written to `custom_model_data` slot 0 by `TerminalData.applyAttentionProjection`.

## Handheld animation

The state machine is `client_ui/TerminalHandheldAnimator`; all numbers live in the pure class `terminal/TerminalHandheldPose` (common, directly testable).

The device body produces no opening animation at all. What is staged is **the camera moving in on the screen**:

1. **Idle**: held in both hands below the line of sight, tilted back about 25° so the screen faces up; low-amplitude breathing sway.
2. **Open**: right-click → raised, enlarged and squared up parallel to the camera, with the FOV narrowing at the same time → `TerminalScreen` opens only after the screen fills the frame.
3. **Close**: the reverse — the UI disappears first, then it settles smoothly back to the carried position.

**The player's position in the world does not move, and neither does the third-person camera.** The FOV factor is multiplied in at `getFov`'s return by `GameRendererTerminalFovMixin`, applies in first person only, deviates from 1 only during the animation, and narrows by at most **12%**.

The presentation takes over the whole first-person path of `ItemInHandRenderer.renderHandsWithItems`, and every position is written as **absolute camera-space coordinates** — the device hangs off no arm.

> **Taking over that method means flushing it yourself.** The `renderAllFeatures()` and `bufferSource().endBatch()` at the end of the method are where 1.21's two-stage rendering actually draws; cancelling the whole method cancels the flush too, and the submitted terminal is left over to the **next frame** and drawn with that frame's matrices — **permanently one frame late**, drifting further the faster you turn. The four-angle assertions in the `terminal-3d` suite exist for exactly this regression.

### Key numbers

| Item | Value / rule |
|---|---|
| `BASE_Z` | **-0.45** (vanilla held items sit at -0.72) — the device is large and centred, and the cost of being eaten by the world depth buffer is the whole thing vanishing |
| Apparent size | About 92% of a 4:3 frame's width and 66% of its height; the upper bound is pinned jointly by the **narrowed** FOV (about 15% magnification) and the **narrowest window's width** |
| Arm depth | Fixed at vanilla's -0.72, **not following the device**; hand positions are derived by `TerminalHandheldPose.screenAligned` from the device edges' **screen angles** |
| Turn inertia | Vanilla's two `(getViewXRot(pt) - xBob) * 0.1F` lines must be reproduced, or the device is welded rigidly to the camera |

The three hand axes: Y takes the device's lower edge's screen angle converted to a world height at arm depth, plus vanilla's own drop; Z is fixed; X takes the side wall's screen angle minus vanilla's hand separation. **It must also scale**, or the hands slide inward along the body.

### The swing thump

Two hands holding something that hits it do a **push**: the whole assembly (body plus both hands) thumps forward-and-down once and recovers, with the far end of the body dipping at the same time.

- The envelope reuses vanilla's own `sin(sqrt(p) × π)` (`swingEnvelope`): **maximum travel by the quarter mark**, the remaining three quarters recovery; both ends are 0, so "not swinging" and "finished swinging" are the same pose.
- The hands follow, because their positions are derived from the body's y/z anyway; arm depth is unchanged.
- **The thump fades out with openness**, reaching 0 when fully deployed.

> **The open/close animation never layers a swing on top.** Right-clicking to open looks like a swing to the client (`UseItemCallback` returns `SUCCESS`, which carries `SwingSource.CLIENT`). The rule is **presentation wins**: `swingShown` returns 0 whenever the animator is not `IDLE`. The player's own swings all happen in `IDLE` and pass through unchanged.

### Three suppressions of the equip animation

Equip height still reads vanilla's `mainHandHeight`, so selecting the terminal still raises it into frame. But three drivers unrelated to swapping must be blocked:

1. **`shouldInstantlyReplaceVisibleItem`**: neither `custom_data` nor `custom_model_data` declares `ignoreSwapAnimation`, and the terminal writes both. The mixin hooks it — **terminal-to-terminal replaces instantly with no animation**, everything else stays vanilla.
2. **`ItemInHandRenderer.itemUsed(hand)` slams the height to 0**, and `Minecraft.startUseItem` calls it on every successful right-click. The mixin cancels it at HEAD for the terminal.
3. **Any frame where the cached stack loses reference identity** triggers a full equip animation. So **the consumer side latches too**: set when the terminal is held and `mainHandHeight` reaches the top, cleared when the main hand changes to something else; while latched, the two-hand path does not read `mainHandHeight` at all.

### When it does not take over

- **Something in the off hand**: fall back to the one-handed path (the same rule vanilla uses for maps), with only the `renderItem` hook adding a little tilt and breathing.
- **An anomaly is hiding the hands**: `ItemInHandRendererAnomalyMixin` has already cancelled the method.
- Third person and every other display context pass straight through.

### The stack-write boundary

**Not one byte is written to the stack in the player's hand.** The only place components may be changed is a **draw-only, discard-this-frame copy** (the dark-phase form of the blinking unread lamp): it never enters the inventory, never goes on the wire, and never takes part in vanilla's swap test. The contract test guards two things — the animator must never call `set(` on the held stack, and the dark-phase form must come from `copy()`.

**The abort paths must be complete**: switching held item mid-animation, death, dimension change, disconnect or a server-forced close must all reset immediately and discard the staged snapshot. The server pushes a snapshot about once a second while the terminal is open — **a duplicate snapshot may only update the staged content and must never restart the opening animation**. The reverse must be continuous too: pressing close halfway open, or reopening halfway closed, back-computes the new phase's start time from the current openness.

## The guidance readout on the HUD

With any navigation-class quick tool selected (minerals, navigation, shelter, portal, stronghold), the terminal's home-page line ("Iron ore: south-west, about 5 blocks, elevation −6") is **also resident below the notice stack**, updating every frame.

- **Server**: `streamClosedTerminalNavigation` keeps sending `TerminalNavigationPayload` to players whose terminal is closed, on the same **4-tick** cadence as an open screen. The decision is centralised in `closedNavigationFor`: an open screen takes the existing faster channel; a player with no usable terminal gets nothing (**holding it is enough** — it need not be in hand); a quick tool with no target resolves to `NONE` and also sends nothing. On close, `navigationSnapshot` takes the **persisted** `guidanceTool`, not whichever tab the screen stopped on.
- **Client**: `TerminalNavigationReadout` accepts every navigation packet, and `TerminalNoticeHud` recomputes the sentence every frame from the latest payload plus **the player's current Y**. Elevation is computed client-side, so climbing one block changes the number immediately.

**It is a resident line, not a notice**: it does not queue, does not count toward `MAX_VISIBLE`, has no dwell timer of its own, and is drawn **below** the stack, pushing the stack up.

**The only way to turn it off is for the server to stop sending.** The client fades out on its own after `STALE_MILLIS = 1200` with no new packet, so clearing the target, escrowing the terminal and player death all need no "stop" message. That window must be substantially longer than the 4-tick send interval; `TerminalNavigationReadoutTest` pins the ratio.

The readout is hidden while the pursuit HUD is in charge.

## The unread lamp

**Position** `TerminalUiLayout.UNREAD_LAMP = (472, 46) – (488, 62)`, in the strip freed up to the right of the oscilloscope (whose right edge moved from 484 to 468). `COMPASS`, `RECEIVER_SLIDER`, `RECEIVER_LCD` and `CLOSE_HINT` did not move by a pixel. The test asserts the lamp is disjoint from all five (including both LCD text lines).

**State**: the lamp and the item's six forms use **the same** `attentionActive`, with four sources — unread signals, unread files, a completed-but-unacknowledged navigation, and a claimable task reward.

The pure rule is `TerminalAttentionPolicy.attentionActive(int, int, boolean, boolean)`; the only entry point that reads the fields out of a record is `TerminalData.attentionActive(CompoundTag)`, used by both the item projection and the snapshot. **The UI does not re-derive an approximation** — "navigation complete, unacknowledged" is not on the wire at all. The boolean ships with the main snapshot (v13).

**Clearing**: only two acknowledgements clear it, `MARK_RECORDS_READ` and `MARK_FILES_SEEN`. Besides clicking the tab, **staying on that page acknowledges by itself** — provided the new item really falls inside the scroll viewport, not merely that the player is on the page. The decision lives in `TerminalUnreadPolicy` (common, pure):

- `rowVisible(row, scrollRow, visibleRows)`: the viewport test shared by both pages.
- `unreadFileRows(unlockedGameTimes, unreadCount)`: on the wire, file unreads are only a counter — but it increments only when a file is **first unlocked**, and the unlock time ships with each file, so "the most recently unlocked N" is exactly what it is talking about.

The client checks once per tick in `tickUnreadAcknowledgement()` and sends **exactly the same** packet as clicking the tab; the server has no second clearing path.

**Appearance**:

| State | Appearance |
|---|---|
| `false` | Dark lens plus brass bezel (the lens is dead, but the housing is still there) |
| `true` | **Blinking**: the amber core lights for a short stretch, then goes fully dark for a longer one |

- **The lamp's colour does not change when the stage turns cyan or red** — it reports "is something waiting for you".
- **It is red**: all three surfaces (inventory icon, panel, 3D shell) take the icon's exact value `0xFFFF3722`.
- Period **24 ticks** (1.2 s, 0.83 Hz): 8 ticks on, 12 off, with 2-tick ramps at each end. **Both plateaus exceed the 7-tick minimum hold, and the whole period is far under 3 Hz.** The curve is `TerminalUiLayout.unreadLampIntensity(double)`; the test samples at 0.05 ticks and asserts a bounded delta.
- The filament interpolates from `LAMP_DARK` rather than the lit colour, or a genuine extinguish leaves "the lens is out but the filament is still glowing".
- Drawn by `client_ui/TerminalLampRenderer` (a separate file — see below).

**How the one in your hand blinks**: the lamp on the device is **baked into the atlas**, so it blinks by switching the form used for drawing — `unreadLampBlinkOn(double)` takes the plateau part of the same curve (≥0.5) and draws the even form during the dark phase. **A copy is switched, not the stack in the player's hand** (`displayedStack` copies once and changes only the copy's model index), so the inventory, the server and vanilla's swap test never see it; the copy is cached per source form, so a settled lamp allocates nothing per frame. Only first-person handheld takes this path.

The contract test asserts two things: the animator must never call `set(` on the held stack, and the device and the panel must read **the same** `unreadLampBlinkOn`.

## Known technical debt

`ResourceContractTest` asserts on **source text** of `TerminalScreen.java` (about 18 asserted strings exist only inside drawing method bodies), which effectively pins it as a two-thousand-line file that cannot be refactored — any attempt to move drawing into a new file turns the contract red.

The real fix is replacing those assertions with behavioural tests (the class already has about 50 `*ForTesting` hooks), not moving code to dodge them. **Until then, new drawing code goes into new files and existing drawing is not touched.**

## Multiplayer and navigation update, 2026-09-07

Profile submissions include the question revision. Multiple controls from one player in a tick are processed in order, with full archive feedback coalesced and the final state sent on the following tick. Each player has an independent window.

Long directory titles use an ellipsis. Files, records and tool details support PageUp / PageDown / Home / End; tool details support Backspace. Enter / Space advances the tutorial after the same reading delay as the button. The panel fits both viewport dimensions.

Structure queries share a 30-second cache by dimension, target and chunk, including misses. The cache holds at most 128 entries and clears on server stop; player navigation progress stays separate. Uncached lookups still use vanilla synchronous structure search, so world-generation stalls remain possible.
