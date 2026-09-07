# Manual acceptance checklist

For the `1.0.0-rc.6` candidate build. It lists **only what automation cannot cover**. Each item is written as "what to do → what passes"; the reasoning is not here — rules live in the owning topic document, trade-offs in [Design notes](design-notes.md).

Automated results and commands are in [Testing and acceptance](testing.md).

## Before you start

- Minecraft 1.21.11 / Fabric Loader 0.19.3 / Fabric API 0.141.4+1.21.11 / Java 21.
- Install `thefourthfrequency-1.0.0-rc.6.jar`; prefer a fresh save for the first pass.
- **Back up the save first**: the End ending writes a local isolation marker for the current save.
- Keep developer acceleration off: `pacing.developerAcceleration=false`.
- To recover from an interrupted ending transaction, launch once with `-Dthefourthfrequency.safeMode=true`.

## First run (two pages: audio calibration → safety notice)

The notice version is now v4, so players who acknowledged v3 will see both pages once more.

| # | Do this | Passes when |
|---:|---|---|
| 1 | Delete the local safety-notice version file and open the title screen | The boot scan lights up the **audio calibration page**: the "Master volume" heading, the slider, "Preview" on the right, one line of explanation under the slider, one footer line and "Next" at the bottom. **There are no paragraphs of explanatory prose**; all three controls wait for the boot animation |
| 2 | Drag the slider / press ←→ | The readout steps in 5% increments; the far left reads "Muted"; the keyboard moves exactly one step |
| 3 | Press Preview | A short `terminal_boot_complete` cue at the current volume; audibly quieter after turning it down; silent when muted; **pressing repeatedly interrupts rather than stacking louder** |
| 4 | Inspect the config file | Only `meta.peakVolume` is written; `bedVolume`, `clientState` and `presentation` are untouched |
| 5 | Press Next | The v4 safety notice appears (flicker/high contrast, volume, irreversible save presentation); **the acknowledgement is not yet written** |
| 6 | Press "I understand" | The same notice version shows only once; the state is written to `config/thefourthfrequency-safety-notice.version` |
| 7 | Back on the title screen | Menu music volume follows step 2; at master volume 0 the menu track is completely silent |
| 8 | Press F8 with no ending yet | No meta toggle, no ending-reset dialog |

## First corruption loading screen

Preserve the first corruption structure without inserting a new loading animation. The presentation lasts at least 250 ticks, with bounded additional reload waiting.

1. Ordinary loading text fails, followed by “DO NOT ANSWER.” and “IT CAN SEE YOU.” Position text steadily inside the damaged medium.
2. At tick 132 the wall fills the screen in one frame with “败” only, overscanning all edges. No embedded phrases or lower-left timecode may remain.
3. A short impact marks the wall, followed by the original 74 ms crash-buffer repeat. Freeze occurs at 160 and blackout/audio cutoff at 172. Reducing MOD volume must reduce the impact; mute remains silent.
4. Legacy loading returns without exposing a modern page during reload. Disconnects or kicks stop the voices, and later loading does not replay the first corruption.

## New world and the terminal

| # | Check | Passes when |
|---:|---|---|
| 1 | Station Zero lands | No floating floor edge, no wall half-buried in a hillside, no tree trunk through the roof, and a porch you can walk straight off |
| 2 | Spend a night inside | Neither the interior nor the roof spawns hostiles; the east gap can be seen through but not walked through, and the door is the only way in |
| 3 | Restart the same save | The built station is not rebuilt; blocks the player removed stay removed |
| 4 | Terminal pages | Only `HOME / TOOLS / RECORDS / FILES`; the wire protocol still uses `SIGNAL / FILES` |
| 4a | **The terminal opens itself** | First entry into a new save: about a second after you are standing in the world the terminal **comes up on its own** and goes straight into the self test, with no key pressed. It must never flash up under a loading screen. **Quitting and rejoining the same save must not replay it**; join with the terminal out of the main hand (dropped or swapped away) and it must neither open within 30 s nor pop up at some later moment |
| 4b | **It may not cover a screen you opened** | First entry into a new save: **open your inventory immediately** on landing (or the pause menu, or video settings) and stay there. The terminal **must not** evict your screen a second later. It gives that greeting up quietly, the device returns to the hand, and a right-click still opens it any time. The same during the half-second opening animation of a manual right-click - the rise finishing must not eat the inventory you opened inside it |
| 4d | **First-boot graphics and ordinary direct entry** | First boot shows memory lamps, a scope and six states; ordinary opens show the page directly. Startup uses a period test card and raster page recovery. After confirmation the MC menu appears inside the terminal glass, the camera approaches smoothly, the frame leaves view and filters clear. Menu controls then work normally |
| 5 | First boot on a new save | ~3 s of six-line self test → profile questionnaire → four-step walkthrough (Tools → Records → Files → Home). Esc and clicks elsewhere do nothing during it, and the bottom right reads **"cannot exit right now"**, not "press Esc to exit". The last step lands on Home, where the bar fills to 4/4 and the reward arrives on screen |
| 5a | The one-line brief per step | Describes **the page currently shown** (not the one an arrow points at), opening with that page's tab name, e.g. "Current page 'Home': …". The four steps describe Home → Tools → Records → Files in order |
| 6 | **Walkthrough safety valve** | Let a zombie hit you mid-walkthrough: the lock releases immediately, Esc works at once, progress is kept and the next open resumes at the current step |
| 7 | The handheld terminal | See "Handheld appearance and presentation" below |
| 8 | **A bound terminal can be moved** | Dragging, shift-clicking and number-key swapping all move it freely inside the inventory, with no prompt and no "terminal recovered" message; picking it up on the cursor and putting it down returns the same device (the recovery count does not increase) |
| 9 | First task | "Learn the terminal", completed only after actually selecting all four tabs; the next task starts immediately and the reward is paid, dropping at your feet if the inventory is full |
| 9a | **Completion is visible** | After the fourth tab lands, the task card holds **the item just completed** for about 3 s: the bar fills to 4/4, card and reward frame turn to the completed palette, the objective line reads "Learn the terminal: click the four top tabs 4/4", and "task complete" sits bottom-right. **The card must not add a line restating what was granted** |
| 9b | Completing a task with the terminal closed | The green notice above the hotbar reports **both which task** and **what was granted** ("Task complete: Collect wood · Stone axe ×1"). Reward without cause is a fail; folding it onto two or three lines is a fail |
| 10 | Six tools | Available in order: shelter, minerals, portal, weather, navigation, stronghold |
| 11 | Mainline order | `12 logs/planks → 6 iron → Nether → fortress → blaze rods (3 solo, 2 multiplayer) → return → approach the stronghold → enter the End → defeat the World Interface`. **"Craft Eyes of Ender" is not an objective** |
| 11a | The "fortress" test | Walking inside the fortress completes it within a second; standing on ground outside it, in the lava sea under a bridge, or on neighbouring terrain does not |
| 12 | Stronghold tool | Opens after three Eyes; with fewer than three real throws it must not give a complete fix early |
| 13 | The full journal | Resident in FILES from the start with a fully garbled title; each damaged file found restores about 25% |
| 14 | The four damaged files | Appear only once found; the title reveals the real name but keeps 2–3 characters garbled; the body opens with small italics reading "file contents damaged, restoration attempted". Each first open advances 25%, and all four must be read to unlock the journal |
| 14a | **Device manual pages** | Four pages arrive with the tools they document: the sky monitor arrives when the terminal binds (just after the first-boot questionnaire), the mineral probe and structure navigator arrive with 12 logs, the stronghold estimate with 3 Eyes of Ender. Each page ends with "This page applies to protocol 09" while the status bar prints a higher protocol number. Each carries one line the device no longer honours, all checkable on the spot: the sky monitor page lists three channels (the instrument draws four), the probe page claims it samples while the terminal is open (it only samples while shut), the navigator page calls all six destinations always selectable (they open by mainline position), and the stronghold page says three throws from one spot will do (the tool's own hint asks for a second vantage). **Manual pages do not count toward the four damaged files' discovery count or read percentage, and do not affect the journal unlock** |
| 15 | Multiplayer files | Discovery is shared; reading and journal access are independent; unlocking generates no extra files or world structures. With a terminal, really travel Overworld↔Nether: `RECORDS` records continuity and the journal continues itself at the end |
| 16 | Unread badge | A new file makes the `FILES` tab show unread; clicking clears it once, but the journal stays locked until all four have been opened individually |
| 16a | **Visible unread clears itself** | Stay on `RECORDS` and let the world produce a new record: the new row appears at the top, and the tab badge and the handheld's amber lamp go out **by themselves** within about a second, with no second click. Then scroll the list down and produce another — **that one must not clear itself** |
| 17 | Typography | All 7 file types use a legible size and high-contrast palette with paragraph spacing and wrapping at the column width; long documents show a scroll position on the right that never covers text |
| 18 | Four independent scrolls | `FILES`, `RECORDS`, tool detail and navigation candidates each scroll and clip independently and never borrow each other's positions; the `RECORDS` unread count only counts currently listable entries |
| 18a | **The unread badge never promises what it cannot deliver** | Discover a fragment candidate with no navigation tool: the `RECORDS` tab must **not** show unread. After unlocking navigation, the candidate row appears in the list (no badge is retro-added — that is expected). Any unread badge you do click must always lead to a matching new entry |
| 19 | A forced page is not the new default | After a pursuit warning force-opens `RECORDS`, closing and reopening returns to the page the player remembered |
| 20 | Receiver tuning | Reach a side-signal site without opening the navigation tool; the receiver is directly tunable. Correct tuning must hold 20 ticks before locking, and progress refreshes smoothly rather than jumping to full |

### Fragment signals and leads (19a / 19b)

**Walking into that structure type at the marked site should give signal.** Pick an **abandoned mineshaft** candidate (a desert temple, where you arrive the moment you step in, cannot reproduce this): go to the recorded coordinates, dig down, enter any mineshaft corridor — the receiver should be available. There are only two conditions: **standing inside a piece of that structure**, and **the structure being within 320 blocks of the marked position**.

**Leads arrive during exploration (needs a new world).** An old save allocated its four candidates at world start and cannot reproduce this. Open a new world, advance the band past 1, and explore normally:

- After walking a while, candidate rows should appear in `RECORDS` **gradually**, not four at once at the start (a random scan every 20–45 s while there is no lead, slowing to 1–3 min once there is).
- The reported site must be **near you** (on the order of two hundred blocks, not thousands). It may be something you have not seen yet.
- **A lead you can read in Records must be selectable in navigation.** As soon as a candidate row appears — no Nether required, no five minutes of being stuck — its trailing `[open navigation]` must retarget navigation at **that row's** site, the navigator must show `[optional file investigation]` alongside it, and once the target is selected the **"start guidance" button must be there** — not a readout with no button under it. The converse too: a row with no followable lead must not keep a shortcut that does nothing.
- Follow it with navigation: **the coordinates should land on the structure itself**, not on the corner of its starting chunk, and the HUD elevation should be real (negative for an underground mineshaft).
- **It must not stall**: where your region simply has none of the four types (open ocean, superflat), after several more scans that fragment should fall back to accepting **any** nearby structure. All four hidden files must always be obtainable.
- The server must not drop frames: at most one player is scanned every 10 ticks, loaded chunks only, and it must **never** generate a new chunk.

### The guidance readout on the HUD (20)

Select the minerals tool, let it lock an iron vein, close the terminal, and confirm a resident line above the hotbar reading **word for word** the same as the terminal's home page. Then:

- **The distance changes live as you walk**, with no need to reopen the terminal; climb one block and the elevation follows immediately (this is computed client-side and is the easiest thing to get wrong).
- **Put the terminal in the inventory (not in hand) and the readout stays.**
- **It is one line and does not spam**: after walking a while the notice stack is not full of navigation. Trigger a task completion meanwhile — that notice appears normally, **above** the readout, and does not push it out.
- **Clearing the target makes it disappear within about a second**, with no sound and no extra message. Handing the terminal away or dying likewise.
- **The readout is hidden during a pursuit.**
- Try all six navigation-class tools. The stronghold gives bearing without distance, matching the terminal.

### Handheld appearance and presentation

Model proportions, animation feel, FOV magnitude and photosensitivity safety cannot be replaced by automation.

| # | Check | Passes when |
|---:|---|---|
| 1 | One-piece shell | Black cast-iron body, worn brass strips, four corner screws; a large **landscape** CRT on the left; on the right, top to bottom, oscilloscope, circular compass, tuning slider, two-line LCD and close-hint strip; a small amber lamp to the right of the oscilloscope |
| 2 | Held in both hands | Two hands support it from the lower corners of the frame. **Hands must not sink into the body** — wrists and palms stay outside the side walls and in front of the near face |
| 3 | Idle | Tilted back with the screen up below the line of sight, glanceable by looking down; breathing sway small enough not to distract |
| 4 | Opening | Raised, enlarged and squared to the camera, with the field of view narrowing slightly (no more than about a tenth); the UI opens only after the screen fills the frame |
| 5 | Size | **All four edges of the body stay on screen throughout.** Re-check once in a narrow window (4:3 or 16:10) — the wide edge crosses first |
| 6 | Hands track the device | During the raise the device nearly doubles in size and the hands do not; both hands must stay against the lower edge and sides, **never sliding inward with the scale** and never floating off |
| 7 | Never occluded | Check indoors, against a wall, looking at the ceiling and looking at the floor: the device must never vanish entirely or be cut in half |
| 8 | Turning | Turning quickly should give the same **slight lag** as a vanilla held item — never rigid, never jittery, never misaligned with the hands |
| 8a | The thump when hitting things | Left-clicking blocks/mobs and right-clicking chests or crafting tables should thump the whole assembly plus both hands **forward and down and back**, with the far end dipping; never a vanilla arm swing. Peak travel arrives very early |
| 8b | Opening does not swing | Right-clicking to open shows **only the raise**, never 8a's thump at the same time. The same on close |
| 9 | Closing | The terminal is seen held up first, then settles smoothly back to the carried position |
| 10 | Boundary | The presentation **never moves the player in the world**; third person shows no camera movement or FOV change, and observers' view of the terminal is unaffected |
| 11 | Switching in / out | Selecting it raises it into frame; switching away lowers it and the new item rises normally |
| 12 | **State updates must not impersonate a swap** | With the terminal **open**, receiving a signal, finding a file or completing a task must **not** drop and re-raise it, nor replay the equip animation. The easiest item to regress |
| 12a | **Right-click must not slam it down** | It should **begin rising immediately**, never dipping first. Open and close ten times in a row; every start must be clean |
| 12b | The device is still while the lamp blinks | Hold it for thirty seconds with unread pending: body, hands and view show no periodic jitter or rise/fall |
| 13 | Smoothness | With the terminal open the server keeps pushing snapshots; that must not restart or stall the raise. Pressing close halfway open, or right-clicking again halfway closed, must reverse **continuously from the current position** |
| 14 | Off hand occupied | Falls back to one-handed, and **the off-hand item must still render**, never quietly hidden |
| 15 | Other contexts | The inventory slot keeps the flat icon; dropped items, item frames and head slot show the 3D shell facing correctly |
| 16 | Reset | Switching item, dying, changing dimension, disconnecting or a server-forced close mid-animation must all reset to idle instantly with the field of view restored |
| 17 | Six forms | 0/1 green, 2/3 cyan, 4/5 red; **1, 3 and 5 must differ from their even neighbour only by the lamp being lit, with no other change to the shell** |
| 18 | The lamp in the UI | A separate amber lamp right of the oscilloscope, never covering the oscilloscope, compass, slider, LCD or close hint. With nothing unread it is a dark lens in a metal bezel |

## Anomalies

| # | Check | Passes when |
|---:|---|---|
| 1 | Catalogue size | 17 triggerable (including the two sustained ones, silent world and metric drift), tiers 1–5. `surface_fracture`, `temporal_drift` and `luminance_fault` are merged and retired, readable as history only |
| 2 | Intervals | First 5–8 min (regardless of dimension); Overworld per tier 10–16 / 10–15 / 9–14 / 8–13 / 7–12 min, Nether 6–10 / 6–9 / 5–9 / 5–8 / 5–8. **Relogging must not shorten a longer pending interval** |
| 2a | Portals do not slow it | Cross back and forth between two Nether portals for 10+ minutes; anomalies must still arrive |
| 2b | The End fires nothing and freezes the clock | Spend 15+ minutes in the End: no anomaly at all. Back in the Overworld, the remaining wait should be what was left on entry — neither firing on landing nor a fresh full interval. The "next" line in the panel and the HUD states the current dimension's scheduling mode (ordinary / pressed / frozen), and the countdown shown in the End is the frozen remainder |
| 2c | A failed draw defers only 30 s | Idle in a boat on open water or an empty plain; the real interval must not stretch past half an hour, and the debug panel's "next" countdown should reschedule to about 30 s after one failure |
| 3 | No tier skipping | Getting iron early must not skip tier 2; each level-up requires 20 minutes of qualifying online time in the current tier plus 2 successful anomalies |
| 4 | Anti-repetition | The three most recently completed anomalies must not be drawn again; after a level-up, new-tier content is seen first |
| 5 | Strong-interface cooldown | After window pulse, channel override or desktop presence, no strong-interface anomaly for 20–30 minutes |
| 6 | Multiplayer isolation | Personal visual/audio/input effects apply only to the target client; shared server-side effects may be observed by nearby players |
| 7 | Nothing written to RECORDS | Completing an anomaly must add no entry, unread badge or dedicated sound; anomaly entries in old saves are cleaned on sync |
| 8 | Debug panel | M opens it by default: one page, two columns. The left column switches between the anomaly, file and survival-milestone lists; the right writes the state once and groups every action beneath it. The server pushes status every 10 ticks and the client counts the seconds down between pushes. The server still validates permission and context for debug actions |
| 8b | Debug HUD | Persistent in the top left once debug is on, toggled with N. Which groups it draws comes from the four checkboxes in the panel and is written to the config. This mod's own entities (correctors, the dark watcher, HIM, the world interface and its parts, stability anchors) are outlined through terrain; vanilla mobs are not |
| 8c | A pool, not a prediction | The HUD and the panel list the **pool the next draw is choosing between** (★ marks one this player has never met), not "which anomaly is next". The seed is mixed with the current tick and candidates are tried against their own preflight in turn, so any single prediction would be a lie |

**Local rule collapse (`local_rule_collapse`)** — trigger from the debug panel:

- The 7×3×7 sections around the player go black instantly, with a few scattered missing-texture fragments in the same region; **those fragments remain after the lighting recovers**.
- **There is no sound at all on trigger.**
- Break or place a block: that block's section recovers lighting alone while neighbouring sections stay black — **recovery is per section, not all at once**.
- Check with F3 throughout that real brightness values are unchanged; server spawning, light block states and other players' screens are unaffected (multiplayer needs a second player present).
- Doing nothing, it must fully recover after 30–40 s.
- Immediately changing dimension, dying or reconnecting must leave no black region on return.

### The weather tool's sky monitoring

1. **With no anomaly**: all four channels (zenith, horizon, magnitude, celestial phase) give stable readouts, the horizon trend line is flat, and the page is **completely clean**. The day/night countdown never collapses to `——`.
2. **`red_horizon`**: the horizon channel starts climbing **before** the sky is visibly red, readable as a ramp rather than a jump. With intensity come scanlines → rolling bands and tear rows → fault rows flooding up from the bottom of the card → short bursts about every two seconds at the peak.
3. **At the peak of the bursts**, the four top tabs, the tool back button and the bottom-right close hint stay **readable and clickable**, and tear rows and static must not overflow the card. Verify once at minimum and once at maximum window size.
4. **`metric_drift`**: the day/night countdown **must always stay correct**, with only the celestial-phase channel reporting drift; the terminal's reported distance and coordinates drift while navigation and arrival tests stay accurate. The presentation must not exceed the lowest tier (scanlines and the occasional full `——` row) and must never show tear rows.
5. **Pinned to `HOME`**: the home card says the same thing (including collapsing to `——`) but must show no scanlines, flooding or static.
6. The other five tools and `HOME`/`RECORDS`/`FILES` show **no presentation at all** during an anomaly.
7. Fault rows must be instrument language (no carrier, sample rejected, phase reference lost) and **must never forge an exception name or stack trace**.

## The unrendered layer and the Bacteria

Trigger from the debug panel (the last row of the anomaly list). A refusal names the exact condition that refused it.

| Check | Passes when |
|---|---|
| Entry | The order is **watching yourself sink for about half a second** (the view passes through the floor, the body does not move, takes no damage and is not stuck in a block), **then** about 1 s of blackout. **You must not see chunks assembling in front of you.** On the frame the blackout clears, the view is still stuck inside the ceiling |
| Ambience | The original 20-second loop fades in within 2 s of entry; music stays silent until the bacteria appears, then `music_unrendered` plays below the ambience and heartbeat. The bed fades out within 2 s of leaving. Enter and leave repeatedly without stacking two layers; footsteps and heartbeat must remain audible |
| View distance | Locked to 6 chunks with the video-settings slider disabled. Walk a trunk corridor: the end should vanish into fog, not be visible all the way |
| Nothing breaks | A diamond pickaxe on walls, floor and ceiling drops **not one block**; placing, water and flint and steel are all inert |
| Finding the exit | The exit is a 15×15 **false wall wrapped around false floor**, differing only by about 20% in colour. **This colour delta is the one parameter you must judge by eye** (`LIGHTNESS_SHIFT` in `tools/generate_unrendered_textures.py`): it should fool you at a distance and be recognisable head-on. Walking into the false wall **passes straight through** with no collision and no prompt; one more step and the floor lets go. The false wall must still **block light and sight** |
| Terminal prompts | The moment the Bacteria lands, one "anomalous signal detected" appears — **only that line**; about 3 s later the same line becomes the resident "Overworld signal detected: [bearing]", eight bearings, **no flicker**. **Following it must actually lead to the exit.** Within 20 blocks of the exit it stops refreshing and fades naturally |
| Coming back from the sky | Through the wall → blackout → on clearing, you are already about 200 blocks directly above your entry point, falling. **The blackout clearing must not show chunks loading in**; view distance is 16 chunks throughout the fall and reverts on landing; **landing must not cause damage**. A timeout (the full six minutes) must be a **ground return** |
| Interruption paths | In the layer, separately: quit and rejoin, `/kill`, admin `/tp` yourself away, stop and restart the server. None may leave anyone in the layer, and none may teleport twice on return |

**The Bacteria (bring a stopwatch)**: placed one full minute after entry, more than 100 blocks away, **where you cannot see it spawn**.

- **Feel** (the numbers are verified every `runGameTest` pass; what you are judging here is feel): sprinting down a trunk corridor, it should close **slowly**; sprint-jumping should open a gap.
- **Heartbeat**: it lands 100 blocks away, beyond the 64-block audible range, so "anomalous signal detected" is followed by silence; as it approaches, the heartbeat fades in from far away and grows, and **your ears should be able to tell which direction it is in**. Walk a circle round a pillar to check the stereo image.
- Hitting it, pushing it and shield-bashing it must all produce **no reaction whatsoever**.
- On contact: screen black → scream → waking at your own spawn with health and hunger full. **No death screen, no death broadcast, no drops.**
- **Ambience volume**: there is now exactly one knob - `UnrenderedLayerClient.AMBIENCE_VOLUME` (currently 0.11, with the file fixed at the -20 LUFS reference). **Do not lower both the file and the constant**; that is precisely what made it vanish last round.
- **The entry blackout must cover the loading screen**: about half a second of visible falling, then black, then the teleport. The "loading terrain" progress screen and chunks assembling must **never** be visible. The cover's clock pauses while a loading screen is up, so however long the load takes there is still about a second of real black after arrival.
- **Being caught holds the black until the scream ends**: reached by the entity, screen goes black, and the scream (about 3.8 s) plays **to completion** before the world comes back. The black lifting mid-scream is a regression. The same requirement applies to an ordinary pursuit capture.
- **Both screams now clear the collapse**: the unrendered layer's at -5.0 LUFS (the loudest cue in the mod) and the pursuit's at -7.8, against `alpha_corruption_collapse` at -9.2. Each should stand clearly out of the corruption sound it plays over; if they now bury too much, change the asset level rather than the playback volume.
- **The bacteria's shape**: now a core wrapped in twelve lobes at three scales, each rotated off every axis and each breathing on its own phase. Two things to accept - the **silhouette at distance must not read as a stack of boxes**, and **standing still watching it for ten seconds**, the outline should keep changing with no pattern you can pick out.
- **The terminal notice panel**: the in-layer bearing now uses the mod's notice panel (not vanilla's action bar) and has its own cold slate background. It must be readable at a glance against a wall of yellow.

## Personal Corrector pursuits

| # | Check | Passes when |
|---:|---|---|
| 1 | Form-1 activity proof | Mining, 128 blocks of cumulative exploration, block-entity/loot interaction, building and trading all work; **none may require a fixed home** |
| 2 | The warning | Once the safety window and a slot are confirmed, the terminal writes green "anomalous signal fluctuation approaching.." then red "prepare yourself..." 10 seconds ahead; the action bar shows only "the terminal is vibrating violently". Opening the terminal jumps to RECORDS |
| 3 | The five permission thresholds | Early activity, entering the Nether, blaze rods and return, 3 real Eye bearings, finding the stronghold |
| 4 | No form skipping | Crossing several thresholds early still faces only the next actual form; no skipping and no back-to-back make-up pursuits |
| 5 | Durations | 60 / 75 / 85 / 95 / 110 s |
| 6 | Intervals | 20–30 minutes after a success; a capture causes no death and no drops, keeps the pending state and retries after 5 minutes |
| 7 | Return | Capture, success and a deliberate disconnect all return to a safe position in the source dimension; an occupied source position must not overwrite real blocks |
| 7a | **Changing dimension during the warning does not drag you back** | Walk into a nether portal (or teleport across dimensions by command) right after the 10-second warning. The prelude must be voided, the terminal notice cleared and the slot released, and the player must **stay in the dimension they just arrived in**. **Being teleported back to the entry point is a failure** - through those phases they were never taken anywhere, so there is nothing to give back |
| 8 | No real progress written | During a pursuit, ordinary anomalies, mainline sampling, navigation, world decay and World Interface services must not write real progress from the mirror |
| 9 | The 10-second lead-in | 4 s of reading → 4 s of frame-rate decay only → 2 s of crash sound. **No filter, vignette, signal bar or pure-black cover may appear during WARNING**; afterwards the blackout seamlessly covers the mirror loading page and the resource loading overlay. The blackout waits at least for the 7×7 chunks around the player in the target dimension |
| 10 | HUD | Only a red "try to escape" is resident at the bottom; other prompts and sounds wait until the blackout return fully ends. **No boss bar** |
| 11 | Corrector behaviour | Sprint across flat ground and pillar up or wall yourself in: it should visibly close, breach quickly, remove the support under you and leap vertically, while still being attackable and killable. Entering an enclosed cave with no skylight slows it dynamically, and open ground restores it |
| 12 | Capture | Last frame freezes + crash sound for 3 s → max health −1 heart → blackout return. **Technical interruption must never reduce max health** |
| 12a | Crash sound variants | Trigger 4–5 freezes in a row; you should be able to tell apart buffer lock-up, high-frequency whine and decay into a square wave. **Variants must be level-matched** — none should suddenly be louder or quieter |
| 13 | Success | "Try to escape" is withdrawn first → green "you have escaped it, for now..." for 3 s → max health +1 heart → blackout return. Back at the original location, the old warning record is deleted and the terminal adds "the magnetic field around the user is very unstable..." |
| 14 | The exit lock | Press ESC any time from the warning to the end of the return load: "Save and Quit / Disconnect" is disabled and reads "you can't just walk away from this..."; restored once it fully ends |
| 14a | **Exit is held in exactly two situations** | A real pursuit in progress, and a World Interface fight in progress. At **any other moment** (late-stage menu erosion, an anomaly running, the terminal open, the ending poem and post-credits), quitting must work |

## Private mirrors and multiplayer isolation

| # | Check | Passes when |
|---:|---|---|
| 0a | **Only one player is pursued server-wide** | Three players on one server, all eligible: only one is ever in a mirror; the other two each get **one** "something is already watching you, but it is busy elsewhere" record, not spammed |
| 0b | Someone logs in during a pursuit | With A in a mirror, log C in: C's tab list must not show A, and A's must not show C |
| 0c | Shared anomalies only when alone | Two bound players within 32 blocks for long enough: everything that lands is a personal anomaly; separate beyond 32 blocks and shared ones appear |
| 0d | Dark watcher and HIM are target-only | B standing in the same place must **see nothing** and be unable to attack it. Four players spending a night together must not produce four |
| 0d1 | Anomalies that cannot idle | With no living mob within 30 blocks, **watcher alignment** must not be drawn; with a single occupied inventory slot, **organ misread** must not be drawn; standing completely still for 10+ seconds (no walking, mining, placing or crafting), **action echo** must not be drawn. Triggering each manually from the debug panel must produce a **refusal**, not a "success" that shows nothing |
| 0d2 | Dragging an organ-misread item | Pick a rewritten item onto the cursor: every other empty slot stays empty, **the whole screen must not turn into eyes**; putting it back restores the eye to its slot. Hovering an empty slot must never show `I SEE YOU....` |
| 0e | **Light dropout does not punch holes** | Embed glowstone and a sea lantern in a wall and add a torch: the torch disappears, the sea lantern becomes prismarine bricks, **the glowstone stays exactly as it is and keeps burning**; all three are restored afterwards and the wall never has a hole |
| 0f | **Doors are opened, not removed** | Cracks, break sounds and shatter particles all still appear, but afterwards the door is **still there, open**; no door drops on the ground, and the anomaly ending must not close it again |
| 0g | **Reward splitting** | At least three players: walk the same Nether portal together — everyone completes "enter the Nether" but the reward is **one share split by headcount** (minimum 1); mining iron separately pays **each of them in full**. The number on the task card must equal the number actually received |
| 1 | Two mirrors | Two players in different pursuit forms occupy different slots and cannot see each other's players, Correctors, block changes or drops |
| 2 | Slots full | A third player only keeps a pending pursuit; no pre-emption and no failed teleport |
| 3 | Initial terrain | Matches the source's 5×5 chunks and ±48 blocks; running past the initial range keeps loading newly copied chunks, **never turning back at 30 blocks** |
| 4 | Outrunning the copy | Only a brief pause at the nearest safe position with the countdown paused; it resumes once chunks are ready |
| 5 | Mining in the mirror | No blocks, experience or path-clearing durability loss; redstone, portals, containers, TNT, beds and fluid interactions are refused or sanitised |
| 5a | **No respawn anchor reaches the mirror** | Put a respawn anchor in a Nether base and then get chased: that position in the mirror is **not a respawn anchor** (the test is on the block's type, not its registry name). In the same pass, confirm furniture like cauldrons and composters is still copied as itself |
| 6 | Refunds | Place ordinary building blocks and end the pursuit: the item is refunded **exactly once**; a full inventory, a disconnect and a restart all still deliver it through the ledger. A block renamed on an anvil **comes back with its name**, and an unnamed one of the same type is refunded separately rather than merged into it |
| 7 | Allowed source dimensions | The Overworld and Nether only; the End and modded dimensions must not trigger a real pursuit |
| 8 | Visibility ordering | On a cross-dimension return the server **restores visibility before teleporting**. Using death or an admin teleport to remove the player from the mirror: the session releases the slot, settles refunds and restores visibility, and **must not drag the player back to the entry point** |

## The altar and the collective ritual

| # | Check | Passes when |
|---:|---|---|
| 1 | **The last Eye has no prerequisite** | With a character that has never experienced a pursuit, place the last Eye in a frame that already has 11: it must **activate normally**, never be refused, and never show any prompt about pursuits |
| 1a | The "before the altar" file | The collapse is written as **ten minutes** (matching the real countdown); the voice is a predecessor's account, not a system manual |
| 2 | The arena | A ground-hugging 11×11 altar and 10 stability anchors on the native main island; **no large artificial platform and no gateway structure** |
| 2a | The vanilla ending is replaced | After placement there is no vanilla exit portal, no dragon egg and no End gateway on the island, and end crystals cannot revive the dragon |
| 3 | The roster is only those who hand over | At least 3 players; keep the third out entirely, idling in the Overworld: they must not appear on the roster and must not affect the ritual |
| 3a | A ninth player is refused individually | They receive "the altar is already holding eight terminals", and **not one of the first eight is returned** |
| 4 | The three-minute window | The bar starts when the first terminal enters the core, shows mm:ss remaining and turns red inside 30 s. **Inserting another mid-way does not extend it.** Withdrawal and cancellation work inside the window |
| 4a | Window expiry = full refund | Every escrowed terminal returns to its owner's inventory, the altar goes dormant, the status line explains why, and a new ritual can start **immediately** |
| 5 | Summon is manual | With everyone escrowed, combat **must not start automatically**; "Summon" is available only to those who handed one over (others see it but cannot press it), and pressing it commits atomically |
| 5a | The summon button is the large plate | A **custom-drawn plate, clearly larger than every other button**, centred at the bottom of the screen — not a vanilla button. It pulses slowly while live (about one cycle every 1.6 s, and **must never be fast enough to read as flicker**) and whitens on hover; when it cannot be pressed it goes flat grey and stops pulsing. The "Cancel Ritual" button **must not exist**; "Withdraw Terminal" is still there |
| 5b | Insertion opens the screen by itself | Right-click the core holding your terminal: **without a second right-click**, the altar screen must appear, with the status line describing what just happened ("Terminal deposited. Someone has to press summon - the altar will not start on its own.") |
| 5c | Everyone else in the End is told | With at least 2 players in the End, have one insert: **the other one - even away from the altar, even spectating - must receive a notice** naming who handed one over, how many the altar holds, and that somebody still has to press summon. The depositor does not get this line on top of their own |
| 5d | **The summon is a hold** | The plate reads "Hold to Summon". A click must **not** start it: hold for about 1.2 s and the plate fills left to right before it commits. Letting go partway zeroes it immediately and sends nothing; pressing again starts over. Moving the pointer off it or releasing the key counts as letting go. A completed hold commits exactly once - the server still receives the request it always did, validated unchanged |

## Weather in the End

- **It must be raining**: entering the End through a portal, rain should **fade in** over about 3 s rather than being at full strength instantly; looking around shows visible rain columns, splash particles on the ground, and vanilla rain audio.
- **No clouds**: looking up anywhere in the End must show none. Set the game's cloud setting to "fancy" and check again — still none.
- **Occasional thunder**: waiting 3–5 minutes should give several thunderclaps, averaging about one every 40 s, **with bearing** (a difference between the ears); never two within 4 s of each other, and never a stretch with none at all.
- **Rain must not become a wall**: run a full World Interface fight and confirm the rain stays within "you can still read the arena", especially during laser sweeps and lance impacts where the dispersion filter is layered on.
- **It must change no rules**: light a fire or place a campfire in the End and confirm it is **not extinguished by rain**; crops, copper oxidation, mob spawning and everything else weather-related must behave exactly as before.
- **The Overworld must not be affected**: spend a few minutes in the End and return; the Overworld's weather must be what it was (clear stays clear). Quit and reload the save and check again. **This is the single most important boundary of this feature.**
- **Leaving the End stops it immediately**: from the frame you teleport back to the Overworld or Nether, there must be no rain, rain audio or thunder.
- **Winning stops the rain**: once the body's death presentation is over, the rain must **fade to nothing over about 10 s** with no new thunder in the meantime; afterwards the sky above the exit portal is clear. **The fade must not be a single-frame cut.**
- **It stays clear on re-entry**: after winning, return to the Overworld and come back to the End — it must **not rain again and fade out a second time**; it is already clear on arrival.
- **Losing does not stop it**: on the failure path the rain continues, deliberately.

## The World Interface fight

### Numbers and structure

- Max health `600 × (1 + 0.5 × (players − 1))`: 600 solo, 2700 at eight.
- Forms advance one way by health: `>70%` / `>35%` / `≤35%`.
- Collapse timer 12000 ticks (10 minutes); it continues while any frozen member is online and pauses when all are offline.
- Each living anchor heals 0.02% of max health per second (0.20% for all ten) and projects a visible 8-block stability zone; players inside take 20% less damage, and terrain inside shows no combat scarring or missing textures.
- The fight shows one custom HUD only; the permanent scar budget is 8192 blocks total and ≤32 per tick, protecting the return portal, obsidian pillars, block entities, key mod blocks and immunity tags.

### Stability anchors (client manual acceptance)

- The pillar top holds a **four-way clamp**, not an end crystal: consistent from front, side and back, clearly reading as four claws gripping from above, with the four feet turned inward around the single bedrock cap.
- **The tether's endpoint** starts at the relay core (2 blocks above the anchor) and connects live to the moving boss core — not from inside the anchor body, and not a fixed vertical column. The beam angle should follow as the boss rises, falls and circles.
- **The destruction presentation** bursts outward first (relay core detonation, three detonation waves, a vertical column of light, a 26-block shockwave ring) before rejoining the inward implosion and violet embers; the camera shakes hardest **beside the anchor you broke**.
- **Legacy save migration**: enter the End on a save whose arena was prepared by Beta 0.4.0 or earlier; all 10 anchors appear as the new entity. Anchors persisted as broken must not revive and no end crystal remains on any pillar top. **Ordinary end crystals outside the arena must behave exactly as vanilla.**
- Complete a success ending with 0, 1–9 and 10 anchors broken; the End Poem should use the all-preserved, partial and all-broken passages respectively.

### Body geometry (client manual acceptance)

- All three forms are clearly airborne, and **no part may ever sink into the ground**; the summon descent must stop in the air. Standing directly beneath and looking up, the lower edge is about 8 / 14 / 18 blocks.
- **The head must not clip below ground**: at low health (structural sag is worst then), stand directly beside the central skull — the whole skull including **the jaw and teeth** must be above ground.
- **The summon descent stops higher**: at the end of the thirteen-second arrival the body sits above the combat standoff and hovers, then presses slowly down to the standoff over the first seconds of combat.
- **The skyhold window**: about every 45 s it climbs 26 blocks above the standoff over about 2.5 s, holds about 8 s, then descends over about 2.5 s. **The climb should be preceded by a flight sound and a low roar.**
- **A form change is a departure**: it gathers, climbs rapidly out of sight, changes form up there and falls back — **never morphing in place in front of the player**. A shockwave ring fires on departure and on landing.
- **The three heads look at the target**: circling the boss, all three should turn toward you — the central head most, the flanking heads less; the body turns slowly, so **the heads should turn before it does**.
- **The heads must not "teleport"**: they should turn **continuously**, never holding still for a few frames and jumping. Directly beneath should also be reachable (pitch cap 60°).
- **You should be able to catch the boss**: running straight at it, it should not retreat at the same speed; it approaches only when further than the standoff and stops horizontally once inside.
- **Hitboxes follow the animation**: while it is performing an action (gaze lock, lance, tendril lash, forced eviction, form change), swinging at the visible skull must still connect, and swinging where the skull just was must miss.
- **The head hitbox matches the visuals**: with hitbox display on, the head box's lower edge should press on the **jaw** rather than hovering above it, while the top still covers the brow; the horns on top are outside the box.
- **The three heads must not clip each other**: across all three forms and every action's full presentation, there is visible clearance between the flanking heads and between a flanking head and the central neck; flanking necks splay outward and never cross the centreline.
- Attack **the central head and its neck** with melee from the ground: all three forms must be hittable, with hit feedback landing on the visible skull/neck. The flanking heads are higher and smaller and may be out of reach.
- Tendril tips **do not touch the ground** (drawn tips stop 4–7 blocks below the body), so ground melee cannot reach them — this is the current design. Verify with ranged attacks or from higher ground that the hitbox covers only the last drawn segment.

### Audio

- **Phase 2 and 3 ability sounds must be as clearly audible as phase 1.** Confirm the laser, bolt, lance, tendril, confiscation and sweep one by one.
- **Nothing may go silent after a long phase**: keep attacking in one phase for **3+ minutes**, especially triggering grab, confiscation and sweep repeatedly in a solo phase 2. **Key reproduction path**: deliberately trigger `silent_world` before the summon and confirm it is interrupted in place and the whole fight stays audible.
- **The roar should be two layers**: an Ender Dragon growl with a lower Wither voice underneath, both dropping with each form; it must not read as a plain dragon call.
- **Music must stay audible**: play a stretch of each phase, especially the densest moments (bolt salvos, lance landings, laser sweeps) — boss sounds may cover the music for an instant, but a whole track must never vanish.

### Cadence and actions

- **Phase 1 is clearly slower**: 7.5–10 s between attacks, with a genuinely quiet stretch long enough to think the last telegraph through. Phases 2 and 3 are noticeably faster in turn.
- **It keeps attacking while lifted**: you should still be hit during the ten-odd seconds (laser, bolt, lance, tendril), only never by grab-and-throw.
- All eight actions should appear in a targeted pass.
- **Gaze sweep must be limited**: playing solo for ten minutes, the same player must not be swept more than three times, with at least three minutes between. In multiplayer, confirm the cooldown is **per player**.
- Only confiscated weapons enter the recovery ledger; swept hotbar items are ordinary world drops and must never be duplicated or returned by the ledger.
- **The centre of the screen must stay clean while locked**: the violet/red interference appears only at the edges (the radial mask `CenterClear` starts at radius 0.55 / 0.42), and the middle must stay readable.
- **Beam weapons must disperse on impact**: **on the arrival frame** (beam touching ground / column landing, the same instant as the shake and hit-stop), the screen edges show optical dispersion — not tear bands, not macroblocks. Players who are not the target should also see a lighter tier.
- **The body must have something to watch between attacks**: in the seven-to-ten seconds between phase-1 attacks, the body must not be a completely static shape with nothing around it — a rotating corona outside the core, a field on the silhouette, ash falling below, and arcs between the heads from form 2.
- **All three "muzzle frames" must exist**: ① during the laser's 90-tick telegraph, **players who are not the target** must also see the core charging, and the firing instant gives a white flash and recoil; ② the lance's column fills top-down through the charge's second half; ③ the tendril throws a full ring of particles on landing at the radius the knockback actually uses.
- **Blasts must shake the camera**, and **the shake must be in the same instant as the blast**, never early — a laser impact sweeping past you, a lance landing, a bolt detonation, a tendril landing, an anchor destruction.
- **No anomaly may trigger during the fight**, and **no unrelated notice may appear** (have a teammate find a file or complete a task; nothing may pop above the hotbar).

### What the three bodies do differently (needs all three phases)

- **The lance widens twice**: note the ground mark across three lances in one fight. The second form must be visibly larger than the first (about 1.8x) and the third larger again (about 2.9x, roughly 21 blocks across). **The third form's circle must still be escapable** - start at the centre and run; you must be out before the 90-tick telegraph ends. If you cannot, that is a failure.
- **The beam hits harder from the second form**: during a sweep, the scars and the damage ticks must be visibly denser than the first form's (5→3 and 2→1 ticks).
- **The beam cannot be outrun from the second form**: at the first form, running in a straight line leaves the beam behind; at the second and third it **no longer does** and you have to turn. This nerf is deliberate.
- **Every form has the crosshair readout**: locked by a lance or a beam at the third form, the corner ticks, the bar and the attack name **must all still appear**. (It was briefly hidden there; that was reverted on request.)
- **The beam thickens each form**: visibly thicker at the second (x1.45) and thicker again at the third (x1.9). **The visible edge is the burning edge** - standing just outside the drawn outline must take nothing, standing inside must take damage.
- **The third form fires two beams**: a sweep at the third form must show **two** beams opening from the core in a V, with a gap between them you can stand in without taking damage. **It is still one attack** - both start and end together.
- **The drawn beam is the burning beam**: while running at the second or third form, the contact must track you at the same 6-tick lag; a beam drawn in one place while damage lands in another is a failure.
- **The third form runs two at once**: lances and beams must be seen alongside orbs and lashes, and several lance circles may open at the same time. **But never two separate sweeps running at once** - not to be confused with the previous item, where one sweep is drawn as two beams.

### Readability (everything here is misread-it-and-die)

- **The footprint is not a sigil circle**: directly under the body is a **white hexagon** (outer, inner, and a centre cross); a lance mark is a **violet round sigil circle**. They must be separable at a glance - two similar round figures on the floor is a failure.
- **Each attack locks with its own shape**: laser = ring plus spokes pointing back at the core; lance = ring plus a ring descending onto it; tendril = **triangle**; weapon custody = **square** plus inward chevrons; hotbar purge = **nine beads** plus a square; grab = hard inward chevrons only. Looking down while locked must identify the attack without reading any text.
- **No particles at eye level**: while locked or carried, **no particle ring may orbit the player at eye height**. Feet, and 3+ blocks overhead, only. Anything that blocks the view of the arena is a failure.
- **The tendril has a visible source**: during its telegraph there must be a wound trace and an arc running from the body's core down to the ground circle. A circle appearing and something invisible throwing you is a failure.
- **Custody leaves a placeholder**: the slot must hold a red-named barrier reading "HELD - RETURNED AFTER THE FIGHT". It **cannot be dropped or used** (try dropping it; it must be refused with a notice), and it is swapped back for the real weapon when the fight ends.

### Particle presentation (client manual acceptance)

- **Not potion swirls**: the violet must be **large rune-shaped motes** (`WITCH`), not small dots, and the hot parts must be **trailing flame** (`SOUL_FIRE_FLAME`), so rings look like they have turned and helices look like they are travelling. A haze of small dots is a failure.
- **Sigil circles on the floor**: the body's footprint, the lance's ground mark and both ends of a morph must be a **three-layer sigil circle** (outer runes, inner light, sigils written inward, spokes), not a lone ring.
- **Big detonations carry a sonic ring**: a lance impact, the morph's halfway frame and an orb hit must throw a **single enormous white ring** (`SONIC_BOOM`) plus a flash, several explosion emitters and firework trails. **Only one sonic ring at a time** - two overlapping is one white smear.
- **The body is worth looking at while it is doing nothing**: watch it idle for 30 s from the edge of the arena. There must be **three rings on axes that do not share a plane, counter-rotating in pairs**, and a shell leaving the core on a steady pulse that **quickens as its health drops**.
- **It has a footprint**: when you cannot find the body by looking up, look down — there must be a ring plus spokes on the floor directly beneath it, tracking it as it moves. It must still be visible from the far side of the island (sent at 512 blocks, not vanilla's 32).
- **Hits register on the body**: on a melee or ranged hit, besides your own crit particles, there must be **a ring lying against the surface at the point struck** and a bolt running from there back to the core.
- **The morph is three beats**: the floor rings at the start close **inward** (not outward) → a helical wake follows the climb all the way back down to the launch point → the halfway frame bursts three shells and four leaning rings at once → touchdown throws three outward shock rings and spokes.
- **The laser has a direction**: on the firing frame there are **three rings across the line of fire** in front of the core; during the sweep two threads travel **down the beam toward the ground** (not a static dotted line), with side arcs every few ticks; the contact point has an outward ring and spokes.
- **Being locked on is visible without looking down**: besides the flat ring at the feet there must be two upright hoops through the body, tightening as the lock resolves.
- **The ground mark says how long is left**: the outer circle holds while an **inner circle closes on the centre**.
- **The orb can be tracked**: in flight it trails a helix and wears a ring across its path; on impact there is a shell plus three shock rings and spokes.
- **Flicker safety**: none of the pulses, contractions or rotations above may reach or approach 3 per second. If any of it looks like it is blinking, that is a failure.
- **Drop beacons**: take one hotbar purge. **Every one of the nine stacks must have a column of light standing over it** (an end-rod shaft, a violet cap, a violet ring at its foot). Picking one up removes its column **immediately**; a stack left to despawn must not leave an orphaned column behind.

### Multiplayer target allocation (one full fight at 2 and at 4 players)

1. **Nobody is named twice in a row**: while any other target is available, the same player must not be named twice consecutively.
2. **Shares are roughly even**: across a whole fight, most and least named should differ by no more than a third. Shares are deliberately not exactly even.
3. **Phase 3 must not stack four telegraphs on one player**: the player the scheduler is locking must not simultaneously be targeted by a salvo bolt.
4. **The tendril's three-hit combo hits three people**: with three players spread out (>10 blocks apart), the three impacts must not all land on the nearest one.
5. **Forced eviction does not repeat a player**: trigger it at least twice in a 4-player fight; the second must pick someone the first did not, and an integrated-server host is never selected.
6. **The body no longer parks over one player**: with four players spread out it should sit between the team; solo, the standoff, hover height and turn rate must be exactly as before.
7. **Is the cadence right (the one open question at RC)**: record clear times, deaths per player and "was anyone idle" at 2 and 4 players.
8. **Grab-and-throw sends no false warning**: whoever gets the full-screen lock border and the lock tone must be the one the tendril then takes.

### The weight of the animation (client manual acceptance)

Verify on **form 3** (widest body, longest necks):

1. **The three heads must not snap back at about two seconds into an attack**: they should lean forward gradually over two seconds, then withdraw gradually over about one.
2. **A hold ends by turning back, not cutting back**: forced eviction (180° neck) and gaze sweep (96°) are the largest and easiest to see.
3. **After a grab-throw release, the tendrils fling out and fall**, rather than the whole group snapping home at the moment of release.
4. **The recovery accent is still there**, just stretched. If you cannot feel it at all, it has been stretched too far.
5. **Watch the idle for 30+ seconds: the three heads must not twitch periodically.**
6. **Known and unfixed**: a phase change, form change or restart cancels an action mid-clip and that frame is still a hard cut. Observing it is a known item, not a regression.

## Success and failure

### Success

| # | Check | Passes when |
|---:|---|---|
| 1 | Trigger | Deal the fatal blow before the collapse reaches 100% |
| 2 | Five non-overlapping beats | **9 s death presentation** (the body **ascends** rather than falling — no toppling; tendrils detach one by one, each with a burst and a sound; ash pours off the whole body and falls downward) → empty arena → summon presentation → the dragon appears → the exit opens |
| 2a | **The dragon's flight is continuous throughout** | From appearing, spiralling out, descending and opening the exit to climbing back, no segment may show per-frame jitter or teleporting |
| 3 | The collapse bar rewinds | After the fatal blow, the HUD collapse bar retreats smoothly to zero over about 25 s while end-stone erosion lifts and the ground sweeps back from the altar outward. **It must not snap back to full when the exit opens** |
| 4 | Line placement | The first line comes while the dragon is still opening; the second lands in the same instant as the exit, **never before it** |
| 5 | Restarting mid-way | Saving and restarting during the summon presentation resumes at the current progress rather than replaying or skipping; **no second dragon may appear** |
| 6 | Exit | A 3×3 return exit opens after resolution |
| 7 | Vanilla flow | `showEndCredits → WinScreen → PERFORM_RESPAWN`, replacing only this run's poem, credits and post-credits; an ordinary vanilla ending still uses vanilla assets |
| 8 | View distance | After the poem is confirmed and the player really returns to the Overworld, it unlocks permanently to 16 |
| 9 | **The lock seals only this run** | The successful local save shows "sealed" and cannot be entered, but **other singleplayer saves** and **other servers** on the same client must remain enterable |
| 10 | Background pressure closed | From resolution, ordinary anomalies, gap pressure, decay and personal pursuits stop permanently; **the success score is released before the resource packs are restored** |
| 11 | Music survives quitting to the title | Back in the Overworld, wait for `music_game` to start, then Save and Quit: the music must not break or switch to the menu track across the save screen and title screen |
| 12 | End Poem duration | Watching the Chinese success poem through without touching the keyboard, from entering the exit to the world returning takes about 3 m 20 s (failure about 3 m 05 s, English about 3 m 45 s) |

### Failure

1. Let the collapse reach 100% first; a fatal blow in the same tick must **resolve as failure**.
2. Singleplayer and remote clients **must not close the game** after the failure presentation: they return to the Overworld, the world renders as missing textures, and the bottom of the screen repeats "the run has ended, please return to the main menu".
3. A published LAN host **must not close the server**; after returning to the Overworld only their own client shows missing textures, and **LAN guests and the server world are unaffected**.
4. Failure likewise writes an ending lock; the local failed save shows "corrupted" and cannot be entered.
5. Return to the main menu from the pause menu and restart the client: quitting always works, and the Alpha presentation persists until F8 recovery completes; a pending Windows recovery transaction must not retire Alpha early.

## F8 reset and save isolation

0. **With an ending lock present, Singleplayer, Multiplayer and Realms on the title screen are all greyed out**, with a hover tooltip matching the outcome (success: "True ending reached. Press F8 to reset the mod and play it through again."; failure: "Failure ending reached. Press F8 to experience the mod again."). All three must become usable again the moment recovery completes. **Keep these apart**: the save and server quarantines are still scoped per save and per address (finishing on somebody else's server does not seal your own saves), while the title-screen lock is a different thing - the ending refusing to let the game continue until recovery has run.
1. With an ending lock present, F8 (**which must also work on the title screen**, since the recovery shortcut does not depend on any screen) shows the recovery/replay confirmation matching the success/failure result.
2. Confirming restores the desktop, notepad, window, resource packs and presentation state this mod holds, then closes normally.
3. After the restart, the exactly-matching local save cannot be entered because of the `.thefourthfrequency-corrupted` marker; success shows "sealed", failure shows "corrupted".
4. Verify `level.dat`, region files and player data have **not been rewritten by the mod**; a new game must use a new save.

## Acceptance record template

| Item | Result | Evidence / notes |
| --- | --- | --- |
| First run: audio calibration + safety notice | ☐ | |
| New world and terminal mainline | ☐ | |
| 18 anomalies, tier pacing and anti-repetition | ☐ | |
| Probe: with coal underfoot and diamond further off, the readout names the coal block | ☐ | |
| Anomalies: within one save, unseen anomalies arrive before ones already seen | ☐ | |
| HIM and the Watcher: a failure from daylight or no placement does not spend the whole interval | ☐ | |
| Compass: facing north points the needle up; the target needle overlapping the facing needle means dead ahead | ☐ | |
| Walkthrough: three lines type in per step and Next stays dim until they finish | ☐ | |
| Walkthrough: completable with the button alone, and `learn_terminal` still reaches 4/4 | ☐ | |
| Mineral probe: the readout names the exact block and count | ☐ | |
| Mineral probe: past tier 4, one empty scan answers with a bearing and there is nothing there; Records holds the matching line with **no unread badge**, its "displayed" half glitched while the figures stay readable; it happens once per save | ☐ | |
| Tools page: hovering an unlocked tool shows its `.summary` | ☐ | |
| BGM: nine consecutive gameplay tracks with no repeat | ☐ | |
| BGM: stepping into the End switches to `music_end` | ☐ | |
| BGM: the second and third morphs each swap the track | ☐ | |
| BGM: every track sits at the same loudness (the unrendered layer and the encounter each take their own tier) | ☐ | |
| BGM: falling into the unrendered layer is silent at first; `music_unrendered` starts when the Bacteria appears and stays under the ambience bed and the heartbeat | ☐ | |
| When RECORDS says a signal can be investigated, navigation can get there | ☐ | |
| Entering a tunable structure shows the action-bar prompt | ☐ | |
| The tick the third Eye is held, the stronghold tool opens | ☐ | |
| With too few samples the stronghold readout states that throwing an Eye records a bearing | ☐ | |
| No RECORDS line renders a raw `terminal.thefourthfrequency.*` key | ☐ | |
| Success ending: quit immediately after the poem; the title screen is already sealed | ☐ | |
| Local rule collapse: silent trigger, per-section recovery, texture fragments that do not return, 30–40 s backstop | ☐ | |
| Phantom echo: **footsteps only at first, closing on an intact wall**, then the steps stop and the first blow lands **and only then does the crack appear**; after that the digging comes from the crack, the crack deepens, hitting it flashes, the block never changes | ☐ | |
| Pursuit capture: one scream the instant the screen goes black, layered over the corruption sound | ☐ | |
| Weather sky monitoring and the two sky anomalies | ☐ | |
| Five-form teaching, pursuit and terminal appearance | ☐ | |
| Two-player mirror, streaming edges and refund recovery | ☐ | |
| Altar escrow/rollback transaction | ☐ | |
| Three forms and eight actions | ☐ | |
| Multiplayer target allocation (2 and 4 players) | ☐ | |
| Success poem, return and view distance | ☐ | |
| Ordinary failure and the LAN host branch | ☐ | |
| F8 recovery and save isolation | ☐ | |
