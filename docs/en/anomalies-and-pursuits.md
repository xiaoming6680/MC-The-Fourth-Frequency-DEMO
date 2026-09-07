# Anomalies, terminal forms and personal pursuits

The anomaly tiers, personal Corrector pursuits, mirror dimensions and terminal appearance rules in `1.0.0-rc.1`. Numbers are owned by `AnomalyIntensity`, `AnomalyCatalog`, `PursuitProgressPolicy`, `PursuitFormPolicy` and `PursuitSnapshotBuilder`.

This document states **what the rules are**. Trade-offs and rejected alternatives are in [Design notes](design-notes.md#anomalies-and-personal-pursuits).

## Overall structure

Three personal progressions that constrain each other without substituting for each other:

- **Mainline permission** decides how far anomalies and the Corrector may advance.
- **Anomaly tier** rises through ordinary online exposure and successfully completed anomalies.
- **Resolved pursuits** decide which Corrector form is actually faced next.

Clearing several mainline thresholds early keeps only the next pending pursuit — never a queue to be repaid; the anomaly tier rises at most one level at a time.

Pursuits also carry a separate **encountered** count (both capture and escape write to it; only technical interruption does not). Its sole use is unlocking the last Eye of Ender for the End finale; form progression still recognises only resolved pursuits.

**After the finale resolves, all background pressure closes permanently.** Once the World Interface enters success/failure resolution (including the later portal and complete stages), ordinary anomalies, gap pressure, decay and pursuits never reopen; any pending pursuit is cleared in place and the terminal projection synced. The `pursuit_test` debug button goes through the same gate.

## Five anomaly tiers

The "highest tier threshold" only raises the ceiling; it never jumps a level immediately.

| Tier | Highest-tier threshold | Actual candidate pool | Overworld interval | Nether interval |
| ---: | --- | --- | --- | --- |
| 1 | Terminal bound | Tier 1 | First 5–8 min; then 10–16 min | 6–10 min |
| 2 | Terminal band advanced, any activity proof, or 20 minutes of cumulative activity | Tiers 1 + 2 | 10–15 min | 6–9 min |
| 3 | Iron obtained, preparing for or entering the Nether | Tiers 2 + 3 | 9–14 min | 5–9 min |
| 4 | Blaze rods obtained and returned to the Overworld | Tiers 3 + 4 | 8–13 min | 5–8 min |
| 5 | Any recorded Eye of Ender bearing, stronghold found, or finale pressure started | Tiers 4 + 5, plus experience gap and local rule collapse | 7–12 min | 5–8 min |

**The dimension decides whether there is scheduling at all** (`AnomalyDimensionPolicy`): the Overworld runs the ordinary cadence; the Nether runs a pressed cadence (the right-hand column — the same table shifted down, with tier progression preserved); **the End and every other dimension (third-party dimensions, the mirror layer, the unrendered layer) never trigger**. The Nether is pressed on its own because the mainline sends the player there on a short, purposeful errand that could otherwise pass without a single lapse; the End is the finale's own stage; third-party dimensions are excluded because this mod cannot declare what counts as normal there. The first interval (5–8 min) ignores the dimension.

**Non-triggering dimensions freeze the timer rather than spend it** (`anomaly_frozen_remaining`): the remaining wait is stored on entry and resumes from that point on return to the Overworld or Nether. Twenty minutes in the End is neither charged to the player nor banked into an anomaly that fires the moment they land; a record that was never scheduled (timer 0) is not frozen. The mirror layer and the unrendered layer freeze too — a pursuit lasts at most 110 seconds and an unrendered layer at most six minutes, and spending that normally would hand the player an anomaly the instant they come out (while falling out of the sky in their own world). **Thawing can only push later, never earlier**: a finished pursuit schedules the next one about 6 m 30 s out while the player still holds tens of frozen seconds from the mirror, and the thaw takes whichever is later.

There used to be a 90-second **dimension-change grace** here; it is deleted. As "don't throw something at me the moment I step out of a portal" it was reasonable, but as a rule applied **per crossing** it was a starvation path: a player running a portal network crosses every minute or two, each crossing pushed the next anomaly to "now plus 90 seconds", and the timer never reached the end.

**Level-up conditions**: except for tier 0 → 1, each level-up also requires at least **20 minutes of qualifying online time** in the current tier (`MIN_STAGE_EXPOSURE_TICKS`) and at least **2 successful anomalies** (`REQUIRED_STAGE_SUCCESSES`). When the actual tier is two or more levels behind the ceiling, the exposure requirement halves to **10 minutes** (`LEGACY_TIER_RAMP_TICKS`).

Qualifying online time requires the player to be alive, non-spectator, not sleeping, not in the terminal, and not in an empty segment or a real pursuit. **It does not require owning a fixed home.**

**Signature anomalies**: one is scheduled at the first Nether entry and one at the first recorded Eye bearing, pulling the next anomaly in to about 30 seconds (`SIGNATURE_LEAD_TICKS`) and drawing from the mainline ceiling (not the current actual tier), preferring content this player has never seen. A signature survives a failed trigger and is spent by the next successful one; it does not change the actual tier, success count or strong-interface cooldown.

Candidate counts per tier: **3 / 9 / 10 / 7 / 7**.

- Tier 5's pool is `tier >= 4` plus experience gap and local rule collapse, so metric drift never appears in the final stretch of the mainline.
- Local rule collapse is named separately at tier 4: it inherits the availability range of both pre-merge entries (tier 2 and tier 3).

### The eighteen anomalies

| First tier | Name | Scope | Behaviour |
| ---: | --- | --- | --- |
| 1 | Phantom echo | Personal | **Two acts, in that order**. The first two fifths are the approach: footsteps that are not there walk a straight line in from beyond the anchor, positioned from elapsed rather than integrated from a velocity so the last step provably lands at the wall - and the wall is still intact, with nothing in it yet. The walking is cut off on the tick the approach ends, one short beat of nothing, then the first blow lands and **that is what opens the crack**, which then deepens across the digging act. The digging comes from the crack. Attacking the crack triggers one flash impact (only once the crack exists), and **the block itself never changes**. Lasts 14–20 s. It used to pick digging or walking with `random.nextBoolean()` per burst while a crack that had been in the wall since tick zero deepened on the whole instance's clock: every part was present and the sequence they describe was not - a hole that predates the person, blows that stop for a walk and resume, and no moment where anything begins. The precondition probes in order: the blocks in front and to either side at **foot and eye height**, then failing those the **ground one block beside the player's feet**, and refuses only when all three are empty. The ground pass was added later - `player.blockPosition()` is the air block the feet are in, so probing horizontally alone never finds a target on flat terrain and this tier-1 anomaly was simply unavailable in the open |
| 1 | Light dropout | Shared | Light sources within 16 blocks go out from far to near and return afterwards; night only (13000–23000), unrestricted in dimensions with no day cycle. **Different answers per light type**: anything with `LIT` flips to unlit; torches, lanterns and end rods are removed; solid light blocks swap to their non-emitting relative (sea lantern → prismarine bricks, jack o'lantern → carved pumpkin, shroomlight → warped wart block, crying obsidian → obsidian); **anything with no relative (glowstone) stays exactly as it is and keeps burning** |
| 1 | Silent world | Personal, sustained | Ambience, weather and every creature's sound vanish at once; only the player's own actions still answer. Lasts 2–3 min. The signal bed runs on MASTER and is not silenced |
| 2 | Peripheral residue | Personal | Cold hands reach further in from both sides of the frame; both vanish on the flash |
| 2 | Watcher alignment | Shared | Nearby animals all turn their heads toward the player. **Precondition: at least 2 living mobs within 30 blocks** (`ALIGNMENT_MINIMUM_MOBS`) — a whole field turning at once is the anomaly; one sheep facing you is what sheep do. It previously had no precondition at all, so a player alone in a tunnel could still draw it and pay a whole interval plus an anti-repeat slot |
| 2 | Dark watcher | Shared | Briefly existing glowing eyes in the dark |
| 2 | Action echo | Personal | The player's actions from a few seconds ago reappear. **Precondition: the client's three-second history buffer is full and the player really did something in the last 5 s** (`ACTIVITY_WINDOW_TICKS`) — a movement sample, or a recorded dig/place/craft/interact. A replay recorded from a motionless player is a copy of a statue |
| 2 | Organ misread | Personal | The terminal renders inventory items as eyes and rewrites their names. **Precondition: at least 4 occupied slots across the main inventory and hotbar** (`MISREAD_MINIMUM_ITEMS`) — selection takes at most two slots per visual row, and a nearly empty inventory only leaves one eye in the corner of a screen the player may not open. The test runs on stack identity, and **an empty stack never participates**: `ItemStack.EMPTY` is a global singleton, so picking a rewritten item onto the cursor emptied that slot and used to paint every empty slot on screen as an eye |
| 2 | Local rule collapse | Personal | The 7×3×7 sections around the player (about 112×48×112 blocks) stop solving lighting and render fully black; a few scattered exposed blocks in the same region simultaneously show as missing textures. Light sources, real brightness and spawn checks are unchanged. **There is no sound at all on trigger.** Each section recovers its lighting when any block update touches it; the missing-texture fragments **do not** recover and stay until disconnect. 30–40 s backstop. Available across tiers 2–5 |
| 3 | Viewpoint separation | Personal | The camera stays where it is while the body can still move |
| 3 | Door cascade | Shared | Several closed doors within about 20 blocks are **forced open** from far to near. The crack progress bar, break particles, zombie door-break sound and block-break sound are all kept — only, afterwards the door is still there, open |
| 3 | Experience gap | Shared | The player is moved along a server-validated safe route during a blackout |
| 3 | Metric drift | Personal, sustained | The celestial position decouples from the local clock, and the terminal's reported distance and coordinates drift persistently at the same time. Lighting, spawning, rules, navigation and arrival tests all still run on real time and real position. The weather tool's celestial-phase channel reports the drift, while the day/night countdown stays correct. Lasts 3–5 min |
| 4 | Red horizon | Personal | The sky, horizon and fog turn red. The weather tool's horizon channel climbs before the colour is obvious |
| 4 | Window pulse | Personal, strong interface | The game window scales and flickers rapidly |
| 5 | Channel override | Personal, strong interface | Vanilla's chat bar types by itself in the first person |
| 5 | Desktop presence | Personal, strong interface | The game minimises and a driven Notepad types built-in text |
| 5 | Unrendered layer | Personal, strong interface | The player is moved into a procedurally generated endless interior plane for up to six minutes. See the section below |

"Shared" means the effect changes entities, blocks or positions in the server world, so nearby players may observe the result; tier, history, cooldown and per-client presentation are still saved per player.

**Shared entries are only deployed when the target is alone**: with another bound player within 32 blocks, selection prefers "personal"; it falls back to a shared entry only when no personal one can be drawn.

**Dark watcher and HIM are only sent to the target** (per-player entity tracking, `broadcastToPlayer`); a client that never received the entity can neither draw it nor hit it.

**Only 4 anomalies have an opening cue** (`CUED_ANOMALIES`): watcher alignment, action echo, organ misread, peripheral residue. They use vanilla's cave ambience `ambient.cave`, played **positionally** in the world: anchored anomalies sound from the anchor, unanchored ones derive a bearing from their own seed and play 9 blocks from the player. **The rest have no opening cue, and that is a rule rather than an omission.**

### Three retired entries

| Retired | Merged into | What the merge became |
| --- | --- | --- |
| Surface fracture | Phantom echo | The digging is no longer only a sound: somebody walks up, starts digging, and only then does that wall crack and keep deepening - hitting it still triggers the flash |
| Temporal drift | Metric drift | One reference-frame drift, with both the sky and the terminal's numbers off it |
| Lighting solve failure | Local rule collapse | Lighting and textures stop being solved in the same region, and the two end differently |

**Retired ids are read but never run.** They keep their original slot in `AnomalyCatalog.MASK_ORDER` — that is the bit index of `ANOMALY_SEEN_MASK`, and deleting one silently renumbers everything after it. `containsHistorical()` recognises them and the bilingual `log.type.*` keys are kept; `contains()`, `require()`, `durationTicks()` and the candidate pool all refuse them.

## The unrendered layer

**The eighteenth anomaly, and the only one that moves the player out of the world they are in.**

Entry has three beats: **watch yourself sink for half a second** (10 ticks — the view passes through the floor and underground while the body does not move) → one second of blackout → **stuck in the ceiling, falling**, into an endless interior plane: one-block-thick yellow walls, grey carpet, a quartz drop ceiling, one lamp per cell either still lit or already broken. No sky, no weather, no natural generation and no music — only a 20-second ambience loop.

### Rank and stake

**The unrendered layer is ranked with the personal pursuit, not with the anomalies it is catalogued beside.** An ordinary stage-five anomaly may fire every few minutes - right for four seconds of a window flickering, absurd for six minutes of being somewhere else. Drawn from the ordinary pool it competed with `window_pulse` for the same slot on the same terms.

It now has a cooldown of its own, taken **verbatim from the pursuit's `MIN/MAX_CHASE_GAP_TICKS` (20-30 minutes)** rather than restated, so the two ranks cannot drift apart into "about the same". The cooldown is written on **every close**, not only the ones that settle: an entry that failed or was interrupted still means this person was very recently taken, and the gap is about how often this may happen to somebody rather than about how it ended.

**Getting out is worth a heart of maximum health; not getting out costs one.** It uses the pursuit's existing rule, `PursuitProgressPolicy.resolutionMaxHealthDelta` - the same formula, the same six-heart floor (below which a failure costs nothing further, while an escape still pays at any health, so somebody who bottomed out can climb back), and the same shared clamp in `MaximumHealthAdjustment`. Restating it here with its own numbers would have been the quickest way to end up with two subtly different definitions of what losing costs.

**Timing out counts as failing.** Six minutes without finding a way out is not getting out, whatever it was worth as an experience. That is a different judgement from the one the anomaly history makes - it files a timeout as a completion so the entry is not immediately re-drawn - and the two are answering different questions.

**A session that never really happened settles nothing**: a disconnect, an operator's teleport, a server shutdown or a failed entry all close without a stake. Charging a heart for the game being restarted is exactly the kind of unrecoverable deprivation the layer is not allowed to be.

### Generation

It is not a hand-built map but a `ChunkGenerator`:

| Item | Rule |
|---|---|
| Cells | One per 5×5 blocks; the north and west edges each raise a wall at **35%** probability |
| Trunk corridors | One full row and one full column forced open every 8 cells |
| Multiplayer isolation | **Needs no second dimension**: the 16 slot entry points are 1.5 million blocks apart, and the maze hashes on coordinates |
| Entry variants | 64 per slot, rotating by visit count |
| View distance | Locked to **6 chunks (96 blocks)** — a design requirement, not a performance budget |
| Duration cap | 6 minutes |

The trunk corridors do two things at once: they give the place its signature endless hallways, and they **guarantee the plane is connected without any global connectivity check** (purely random walls seal off about one cell in seventy).

### The exit

**The way out is a false wall.** Exactly one per 32×32 cells, positioned by hash, occupying a **15×15** square: a ring of "false wall" that renders, occludes and blocks light exactly like an ordinary wall **but has no collision**, wrapped around a solid patch of "false floor" that looks like floor but holds nothing up.

**It is found by colour, not by shape.** False and solid surfaces are generated by the same procedural recipe with **pixel-identical patterning**, differing only by about **20%** in colour (`LIGHTNESS_SHIFT` in `tools/generate_unrendered_textures.py`, **warmer rather than merely brighter** — merely brighter reads as a light source). No outline, no icon, no silhouette.

Passing through is two beats: step into the false wall (the view fills with wall, the floor still real), take one more step, and the floor lets go.

**The false wall blocks nothing**, so the exit region cannot seal off any part of the map — which spares both the connectivity proof and the Bacteria's pathfinding a special case.

**The exit is not only that.** Besides the large false-wall regions, the plane carries **four-by-four patches of floor that do not hold weight**, about one per forty thousand floor blocks - roughly a two-hundred-block square, and so noticeably rarer than the exit regions at one per hundred and sixty. That ordering is deliberate: the reliable way out is the one you can learn to spot, and this is luck layered on top of it.

They began as **single blocks** at eight times this density, which was wrong twice over: single blocks were both too visible - off-colour speckle across every floor in the layer - and too easy to step over, because at six blocks a second the gap between footfalls is wider than a one-block hole. Four by four is something you fall into.

These are for the player who cannot afford to look. Finding a region is observation; falling through one of these is luck: somebody being chased picks whichever corridor is open and is not reading the floor, and one of the blocks under that route simply is not there. They use the same false-floor block and so carry the same off colour - visible to somebody crossing slowly, invisible to somebody running, and that asymmetry is the whole design. They **cannot** replace finding a region, and they **can** save somebody who was about to be caught.

Two hard constraints: never on a wall line (a wall stands on its own floor block, and cutting it away leaves the wall over nothing), and never on a trunk intersection cell - those are where the entry point and the entity are placed, both without checking anything.

**The bearing is relative to where the player is looking, not to the compass.** Eight sectors: ahead, ahead-and-right, right, behind-and-right, behind, behind-and-left, left, ahead-and-left.

A cardinal bearing is a fact about the world that has to be converted before it can be acted on, and down here there is nothing to convert it against: no sun, no landmark, no map, and a floor plan with no north. "Ahead and to the left" is something a player can simply walk; "north-west" is something they have to work out first, in a place whose whole design is that working things out is hard.

That is why the server sends an **absolute angle** rather than a sector: the sector has to be recomputed against the player's facing every frame, and bucketing on the server would round twice and visibly lag the mouse.

### Coming back

| Route | Landing |
|---|---|
| Through the wall (dimension with sky) | **About 200 blocks directly above the entry point**, then falling |
| Through the wall (dimension with a ceiling, e.g. the Nether) | Ground return |
| Six-minute timeout | **Exactly where they were taken from** |

- The teleport is hidden entirely behind a **40-tick blackout**, scheduled at tick **8** of that window, leaving the rest for loading.
- During the fall, **view distance is forced up to 16 chunks** (the inverse of the layer's 6-chunk lock); the client restores it itself on the frame the local player lands.
- **Fall damage is waived throughout**: fall distance is reset every tick during the descent rather than cancelling one instance on landing — which also covers landing in a ravine, a second bounce, and anything else met on the way.

### The two things the terminal says down there

1. **The moment the Bacteria lands**, one line pops above the hotbar: "anomalous signal detected". Only that — no bearing, no distance.
2. **Three seconds later** the same line becomes the resident readout "Overworld signal detected: [bearing]" — eight bearings, direction only, no distance, **rewritten every 40 ticks** (vanilla's line starts fading after 60 ticks, so a 40-tick rewrite keeps it up without flicker).

**It never points wrong.** The vagueness comes entirely from **resolution**: eight bearings at a 160-block spacing is a fan about 60 blocks wide at the far end. Within 20 blocks it says nothing at all.

### Prohibitions in the layer

- **No mining, no placing, no water, no flint and steel** (the opposite of the private mirror's rules).
- **World decay, the signal bed and music are all off.**

### The Bacteria

One minute after entry, an entity is placed **more than 100 blocks away** (beyond view distance, so it is never seen spawning) and from then on does exactly one thing: walk toward that player.

| Item | Value |
|---|---|
| Movement speed attribute | **0.370** |
| Resulting speed | Slightly faster than sprinting (5.612 blocks/s), clearly slower than sprint-jumping (about 7.1) |
| Conversion | `blocks/s ≈ 44.05 × attribute²` (measured on default 0.6-friction ground) |
| Heartbeat | `unrendered/heartbeat.ogg`, 1 s loop, `HOSTILE`, registered radius **64 blocks** |

- **It cannot be hit, pushed, knocked aside, damaged, and it deals no damage.** Reaching the player is a **capture**: the screen goes black, the scream plays, and they wake at their own spawn point with health and hunger restored. **The player does not actually die** — no death screen, no death broadcast, no drops, no statistics entry.
- **The heartbeat owns distant navigation; quiet foot friction appears inside 18 blocks.** The heartbeat retains its 64-block radius and distance-dependent cadence; foot contacts follow the rendered gait. Leaving client tracking stops the sounds.
- **A large spider, and it has no face.** Two body masses, eight jointed legs, and knees that rise above the back — that last line is the single feature that makes a shape read as a spider at any distance and in one frame, which is why the femur angles up and the tibia comes back down past it rather than the legs simply splaying outward. Leaving the face off is not a saving: a spider is already frightening from its gait and its proportions, and eyes would give the player something to look at and therefore something to reason about. The front of the body is just where the legs are densest. **No emissive at all.**
- The gait runs on `walkAnimationPos`, the same distance-walked clock vanilla drives its own limbs from, so the legs are tied to ground actually covered rather than to elapsed time — a thing that has stopped stops moving its legs. The four pairs walk a diagonal sequence, so the four feet down at any moment are never all on one side. Over the top of that each leg carries a slow drift on a period that does not divide into the step cycle, so a spider standing still is never quite still.

> **Tune the speed from what the GameTest measures, never from arithmetic.** `UnrenderedLayerGameTests.theBacteriaSpeedLandsBetweenSprintingAndSprintJumping` runs in every server GameTest pass.

## The RECORDS backfill

Anomalies are written to the isolated store `ANOMALY_LOGS` (type, world time, dimension, coordinates) from the **very first one**, capped at **160**. Nothing reads it until the latch flips — RECORDS reads `SIGNAL_EVENTS`, and `pruneOperationalTelemetry` strips every anomaly type from that one.

**The latch flips on the first recorded Eye of Ender bearing** (thrown yourself, or shared to you by a teammate). After it flips:

- Only then does the server start sending the list. **Before that it sends an empty one**, rather than letting the client hide it.
- RECORDS **merges by world time** rather than appending at the end.
- Backfilled lines **resolve out of noise character by character**, staggered per screen row: the resolution order is fixed and monotonic with progress, and only unresolved positions are re-rolled in 350 ms (7-tick) buckets, with zero reflow throughout.
- Backfilled lines are **never navigable** (the RECORDS shortcut sends `SELECT_NEAREST_UNSTABLE` and does not aim per row).
- They use `log.type.*` rather than `log.summary.*` — the former is the terminal's voice, the latter a specification description.
- **One** ordinary record line with an unread badge is written at release.

## Scheduling and anti-repetition

- At most one ordinary anomaly runs per player at a time.
- The **3** most recently completed anomalies are excluded from candidates, and what is left then **prefers** entries this player has never seen. Both rules decide which anomaly arrives, never whether one does: **when every narrowed candidate refuses its own precondition, the draw falls back to the full pool without either preference and walks it once more**, skipping ids already refused on the same tick. The strong-interface cooldown and the "send a personal anomaly when somebody is within 32 blocks" rule still apply to the fallback - neither is a freshness rule.
  - Before the fix this starved. Tier 1 holds three entries; phantom echo needs a surface to crack and light dropout is night-only, so above ground in daylight the only one that can start is silent world. One success then occupies both the recent-3 list and the seen mask, the remaining two cannot start where the player is, and the 30-second retry rebuilt the identical impossible pool. No anomalies also meant `ANOMALY_STAGE_SUCCESSES` never reached 2, so the tier stayed at 1 and the pool never grew.
- An anomaly newly added at the current tier is weighted **3×** compared to retained ones.
- Window pulse, channel override and desktop presence use a **20–30 minute** strong-interface cooldown.
- **Login applies a floor, not a reschedule**: no earlier than 3 minutes out, and an existing later schedule is kept. Dimension changes apply no grace.
- If the moment arrives while the player is sleeping, has the terminal open, is in an empty segment or in another anomaly, the next check is deferred **60 seconds**.
- **If a draw happens but every candidate is refused by its own precondition, the deferral is only 30 seconds**, not a whole interval.
- Ordinary anomalies pause during real pursuits and in mirror dimensions; after a successful pursuit the next one is scheduled about **6 m 30 s** later.
- **No anomaly triggers for the entire World Interface fight**, from the summon's first tick to resolution, and **anything already running is interrupted in place** (`interruptAll` sends an `interrupted` phase). Gap pressure is likewise off.
- With `pacing.developerAcceleration=true` the first/subsequent intervals shorten to 5/10 seconds, for regression testing only.

## Two resident presentations outside the catalogue

They occupy no anomaly slot, write no anomaly history and are not bound by the strong-interface cooldown, so they are not among the eighteen.

| | Watcher | HIM |
|---|---|---|
| Conditions | Terminal band not yet advanced (`BAND_STAGE == 0`), and either night (`dayTime >= 12500` or `<= 1000`) or underground (no sky access and local light ≤ 7) | 95°–180° off the line of sight, 22–44 blocks (×1.6 by day, i.e. 35–70), and either uneven terrain nearby (≥ 5 blocks of height range within the sample ring) or an enclosed spot |
| First attempt | 2400 ticks after the terminal is issued | — |
| Interval | 2800–6400 ticks | 6000–15000 ticks |
| Lifetime | 900 ticks | 600 ticks |
| Placement | 18–32 blocks out, torso 115° off with the head already turned back | A direction **the player has not looked at yet** |
| Other | Only one near a given player at a time | **Always faces the player** (only the facing moves); despawns 4 ticks after being seen, and also if the player comes within 4 blocks |

The "anomalies" group in the debug panel's right column is their only manual entry, with a button each for `him_spawn` and `watcher_spawn`. Manual spawning goes through the same `HimService.debugSpawn` / `WatcherService.debugSpawn` as a natural trigger, so **the placement rules are not bypassed**. The debug HUD's entity outlines tell you whether one spawned and where, but they are no part of the server's "has been seen" judgement: an outline visible through a wall is not a figure that will still be there when you reach it.

## The three-form personal pursuit

| Form | Permission threshold | Duration | Core counterplay |
| ---: | --- | ---: | --- |
| 1 Soundseeker | Terminal bound and at least one anomaly completed successfully; plus either any mining/exploration/loot/building/trading proof, or 20 minutes of cumulative activity | 60 s | Stop forming a rhythm; sneak and break line of sight |
| 2 Interceptor | Entered the Nether | 85 s | Recognise the predicted route; double back, change direction or change elevation |
| 3 Interface Corrector | Three Eye of Ender bearings recorded or stronghold found | 110 s | Ignore conflicting text, coordinates and bearings; use the heartbeat only for distance |

`allowedForm` is the highest form the mainline permits; `actualForm` always equals "resolved pursuits + 1", capped at 3. A real trigger requires `actualForm <= allowedForm`.

The teaching chain:

```text
Safe demonstration inside a successful anomaly
→ Wait for a safe environment and a free mirror slot
→ The terminal writes an anomalous-signal warning 10 seconds ahead
→ Terminal vibration, progressive frame-rate decay and a 2-second freeze
→ A blackout hides the loading and the real pursuit begins
→ Archived on success / pending kept and retried after a capture
```

A success advances the actual form one step, and the next real pursuit waits **20–30 minutes**. Capture, disconnect and technical interruption do not increase the resolved count; the pending state is kept and retried **5 minutes** after returning to reality.

### The entry presentation

Once the safety window is confirmed and a mirror slot taken, the server immediately appends an unread record, still in two colours: **the green half now differs per form**, the red half is always "prepare yourself...". The action bar shows only "the terminal is vibrating violently". Opening the terminal then jumps straight to RECORDS.

The three forms distinguish silence, breaking predicted routes, and reading a continuous waveform. Historical warning keys remain for compatibility. Legacy forms migrate as 1/2→1, 3/4→2 and 5→3; schema 2 makes the conversion idempotent.

**The action bar line is not split by form, and must not be.** During the event there is only the shake; what this is and how it hunts belongs to the records page, which the terminal force-opens the next time it is raised (`PURSUIT_WARNING_RECORDS_REDIRECT`). That ordering is the [world bible](world-bible.md)'s rule that explanation arrives after the event, and splitting the action bar by form would move the explanation in front of it. `ResourceContractTest` asserts no per-form variant of that key exists.

**The full lead-in is a fixed 10 seconds:**

| Segment | Duration | Content |
|---|---|---|
| Reading | 4 s | Purely for the player to read the terminal record |
| Frame decay | 4 s | Presentation frame rate is programmatically reduced from about 60 FPS to about 4 FPS, **with no filter, vignette, signal bar or other screen overlay layered on** |
| Freeze | 2 s | The frame freezes, movement input locks, and the crash sound replays |

- **The crash sound is a variant pool**: `alpha_corruption_collapse` and `alpha_corruption_warning` have 3 variants each, generated procedurally by `tools/generate_soundscape.py` and drawn randomly by the sound engine each time. It replays every 5 ticks during the freeze, and again for 3 seconds at capture resolution. The three collapse variants are three different ways of crashing (audio buffer lock-up, a driver-deadlock high-frequency whine, bit depth decaying into a square wave). Variants of the same event are level-matched by **RMS rather than peak**.
- **Capture has one extra scream**: `pursuit_capture_scream` plays once when the `CAPTURE_FREEZE` phase arrives, **layered over the corruption sound rather than replacing it**. Master: mono 44.1 kHz Ogg Vorbis, about 3.8 s, ingested at −7.8 LUFS / peak −0.1 dBFS, played at 0.85. `ResourceContractTest` specifically asserts this file is a **Vorbis stream**, not merely an Ogg container.

**Conditions for lifting the blackout**: first the loading screen must be gone and the dimension correct; then the client must hold the 7×7 chunks (radius 3) around the player; only then does the count of consecutive stable ticks begin (**8 ticks**). There is a **200-tick (10 s) hard timeout** — a stalled chunk stream must never leave a player in a permanent blackout.

The 10-second WARNING itself does not raise the pure-black loading cover; the cross-dimension `LevelLoadingScreen` and the resource `LoadingOverlay` always are covered.

### During a pursuit

- A **black-and-white, low-colour-depth digital corruption post-process** (`thefourthfrequency:post/digital_corrupt`); scanlines and vignette are terms in that chain (`ScanDepth` / `Vignette`), no longer rectangles drawn on the HUD.
- **No boss bar**; only a red "try to escape" is resident at the bottom. Other terminal notices queue without display or sound until the pursuit resolves, the blackout return finishes and the source world has loaded.
- The ESC pause menu still opens, but "Save and Quit / Disconnect" is disabled and reads "you can't just walk away from this..."; it is restored once the session fully ends.
- **The heartbeat is played positionally at the Corrector's coordinates on the HOSTILE channel** (volume 1.75 ≈ 49-block audible radius), carrying its own panning and linear falloff; only the pitch tightens with proximity.
- **Night vision is applied throughout**: ambient, no particles, no HUD icon, renewed every 600 ticks (topped up below 300) rather than infinite. Resolution, return, disconnect and recovery login all remove it actively.
- At the moment a pursuit really begins, other players holding bound terminals within 64 blocks of the source dimension receive one line: "a nearby terminal's signal cannot be resolved right now" — unnamed, revealing nothing, and not sent to the target.

### Corrector behaviour

| Environment | Base speed | Pathfinding multiplier |
|---|---:|---:|
| Open | 0.31 | 1.32 |
| Cave (no direct skylight, low sky light, enclosed on at least four sides) | 0.25 | 1.04 |

The intended feel: **holding a sprint keeps the distance roughly constant; only sprint-jumping actually opens a gap.** Leaving a cave restores immediately. Pursuit-specific breaching starts breaking block by block after a brief stall; when a player pillars up it prioritises removing the support under them and periodically leaps vertically. Correctors in the ordinary world are unaffected by this package.

**Initial spawns and respawns take a point from the full 25–42 block ring around the player, not restricted to behind them** — the probe may land directly in front. It advances through five rings at 26/30/34/38/41 blocks, near to far, 12 bearings each with a randomised start angle; when a column has no footing, it advances to the neighbouring bearing on the ring rather than jumping to the player's other side.

### Escape and counter-kill

Outlasting the form's duration is still the fallback condition, but no longer the only solution:

- Staying at least **42 blocks** away for a cumulative **5 seconds** severs tracking and ends it early;
- breaking line of sight beyond **18 blocks** for a cumulative **8 seconds** likewise;
- both progresses **decay quickly** once the condition lapses;
- the Corrector keeps **36 health** and a real hit box, and killing it yourself resolves as a counter-kill success.

**Resolution:**

| Outcome | Presentation | Max health |
|---|---|---|
| Captured | The last frame holds and the crash sound plays for 3 s | **−1 heart**, with a soft floor at 6 hearts (no further loss at or below 6) |
| Success (outlast / distance / line of sight / counter-kill) | "Try to escape" is withdrawn first, replaced by a green "you have escaped it, for now..." for 3 s | **+1 heart** (not subject to the soft floor) |

Max health is always bounded to 1–20 hearts; technical interruption triggers no health penalty. On success, the temporary warning record is deleted after the return and a "the magnetic field around the user is very unstable..." record is added; a capture likewise records the field anomaly but **keeps the warning for the next retry**.

### The safety window

A real pursuit begins only when **all** of the following hold:

- The player is alive, non-spectator, not sleeping, not flying/gliding/riding;
- not on fire, not in lava, fall distance no more than 3 blocks;
- health above `max(6, 40% of max health)`;
- no ordinary hostile within 12 blocks attacking that player;
- the terminal, ordinary anomalies, empty segments and the World Interface finale are all unoccupied;
- the current dimension is a supported vanilla source dimension and a free mirror slot exists.

The Overworld and Nether currently allow pursuits to start. **End mirrors are registered** (for topological and recovery symmetry), but the v1 safety policy forbids starting a real pursuit in the End; modded dimensions do not trigger in the first version either.

## The private correction layer

A real pursuit is not client-side invisibility: it moves the target into that dimension's private mirror slot. The Overworld, Nether and End pre-register two slots each, but **only one pursuit runs server-wide at a time**.

### Streamed chunk snapshots

| Item | Value |
|---|---|
| Copied before entry | 5×5 chunks around the player, ±48 blocks vertically |
| Per session per tick | 8192 blocks |
| While running | Continuously requests the 5×5 window around the player's current chunk; crossing one chunk adds only the 5 columns in the direction of travel |

- Chunks **already queued or copied in the same session are never overwritten**, so the player's tunnels and temporary placements survive.
- There is **no fixed 30-block horizontal boundary**, and no repeated return to the opening anchor.
- If an extreme teleport or high speed outruns the copy, the player only pauses briefly at the nearest safe position and **the pursuit timer pauses with them**.
- The vertical range is fixed at ±48 blocks from the entry height — a v1 boundary still needing real-machine verification.

### Blocks and items

- Natural blocks in the mirror can be broken but **drop no items or experience**, and consume no durability on tools used to open a path.
- Containers and other block entities are replaced with air or stone; redstone, portals, beds, explosives and hazardous interactions are not preserved.
- **The test asks what a block is, not what it is called.** `PursuitBlockPolicy.safeSnapshotBlock` matches base types — `Portal`, `BaseFireBlock`, `PistonBaseBlock`, `DiodeBlock`, `BaseRailBlock`, `BasePressurePlateBlock`, `SculkSensorBlock` and the rest — with `isSignalSource()` underneath as a backstop, so modded subclasses are covered too. **The respawn anchor is refused along with them**: it has no block entity and an empty hand can use it, so leaving one in the mirror lets a player set their spawn point inside a private dimension that stops existing when their session does. Furniture that reports a comparator output without being redstone hardware — cauldrons, composters — is kept, because the mirror is supposed to look like the player's own base.
- Only simple building blocks may be temporarily placed; a successful placement writes the persistent refund ledger.
- On session success, failure, disconnect or restart recovery, **each placement is refunded exactly once**; with a full inventory the ledger keeps holding it.
- **What comes back is the stack that was spent**: the ledger carries the data components (custom name, lore) alongside the id, so a renamed block and an ordinary one of the same type are billed and returned separately. A line that genuinely cannot be rebuilt — its item has left the game — is dropped and the player is told so on their terminal; every other line is still paid.
- Health, hunger, potions, food, ammunition and combat durability stay real; **capture triggers no vanilla death and no drops**.

### Multiplayer and recovery

- Pursuit progress, form, anomaly history, terminal appearance, mirror session and refund ledger are all saved **by player UUID**.
- A player in a pursuit and players in reality cannot see each other, and share no entities, routes, block modifications or Corrector.
- Slot occupancy is one of the few world-shared states; with none free, a pending pursuit is kept and **no other player is pre-empted**.
- **The queue is ordered by waiting time**, not by player list order.
- **Waiting says something**: a player who already meets every condition and is simply behind someone else receives one rate-limited record line (written once per pending pursuit).
- Disconnect and server restart cancel the copy queue, release the slot, settle refunds, and return the player safely to the source dimension on login.
- If the source landing spot is no longer safe, the return locator searches nearby safe positions and **never overwrites real-world blocks**.
- Visibility is restored before a cross-dimension return; if death or an admin teleport has already moved the player out of the mirror, the session only cleans up the copy queue, slot, refunds and visibility state and **does not force the player back to the entry point**.
- **Abandoning the warning or copying phase does not teleport either** (`PursuitReturnPolicy`). Through both of those the player has been standing where they were; they were never taken anywhere, so there is nothing to give back at the end. The most common way into that path is precisely the player changing dimension - they walked into a nether portal, which voids the prelude - and returning them to the source dimension would undo the move they had just made. A player who *is* in a mirror is always teleported, however stale the phase field looks: an extra teleport is the safe direction to be wrong in, leaving somebody in a slot dimension about to be recycled is not.

### The return landing

After a pursuit the player returns to **the same coordinates in the source world as where they stood in the mirror**, not to where they started. If those coordinates are solid in the source world, it falls back to a nearby safe spot, then the entry point, then the spawn point.

**Rotation shares the position's clock: the return does not change the view at all.** The teleport passes `Relative.ROTATION` as 0 ("add 0 to the current facing"), so not even the one tick of mouse movement between server and client is swallowed. Only a return that never entered the mirror but still has to teleport (recovery on login) applies the pair from the entry record - an aborted warning phase does not teleport at all, see above.

## Three filter languages

The mod's screen presentation splits into three **never-mixed** languages:

| Language | Shader | Chain | Used for |
| --- | --- | --- | --- |
| **Analog signal** (the medium is breaking) | `post/analog_signal.fsh` | `signal_1..4` | Anomaly impacts |
| Same, still variant | Same | `signal_still_1..4` | First-run loading screen, world-loading screen corruption |
| **Digital corruption** (the rules are breaking) | `post/digital_corrupt.fsh` | `pursuit_low_res*` | Private pursuits (4 proximity tiers) |
| Same, edge mask | Same | `world_interface_lock{,_peak}` / `_expulsion` | World Interface lock / forced eviction |
| **Dispersion** (light is being bent) | `post/chromatic_dispersion.fsh` | `world_interface_dispersion{,_far}` | The **instant** a World Interface beam weapon lands (12 ticks) |

The three languages are built from disjoint vocabularies:

- **Analog signal** is entirely **continuous**: radial chromatic aberration, sinusoidal row wobble (two non-integer-ratio periods multiplied), a cosine scanline, highlight bloom, an upward-crawling mistrack band, vignette, grain.
- **Digital corruption** is entirely **discrete**: whole bands flung sideways, per-band RGB channel offsets, a lost band filling an entire row, hash-selected macroblocks collapsing into mosaic, dithering then quantisation to a low bit depth.
- **Dispersion** is entirely **optical**: radial R/B separation (`Spectrum` decides ghosting versus a continuous spectrum), outward barrel stretching, highlight bleed smeared along the radius, and a `Saturate` that pushes colour **away** from luminance — the other two filters take colour away, while a prism only separates it.

**The dispersion chain contains no tear bands, macroblocks, quantisation, desaturation or anything that jumps per tick.** `PostFilterContractTest#theBeamTreatmentStaysOptical` asserts those uniforms **do not exist**, not that they happen to be 0.

**Dispersion is the only chain worn by people who are not the target**: `world_interface_dispersion` for whoever is being hit, `world_interface_dispersion_far` for everyone else on the island (within 128 blocks of the arena centre), with every term of the far tier noticeably lighter. The test asserts term by term that the far tier is never heavier.

**It hangs on the arrival frame**: the laser's beam touching ground, the lance's column landing — **the same tick the server settles damage and sends the shake packet**. The lance must never be shifted earlier by `SKY_LANCE_FALL_TICKS`, and the contract test asserts that constant **does not appear** in the class.

**Order: `wantedEffect` asks dispersion first, then eviction, then the lock.** Dispersion must be asked **before** the "is this aimed at you" test. `WorldInterfaceClientFidelityContractTest` pins this ordering on the source shape.

**It follows the `impactFlash` option**: turning it off does not cancel the presentation but drops everyone uniformly to the far tier.

### The still variant's one-way rule

`PostFilterContractTest` guards: **on the side that has to be read, no term may be heavier than on the side that does not.** The still family zeroes `Wobble` and `RollHeight`, holds `Desaturate` ≤ 0.05 and `Tint` alpha ≤ 0.07, and lowers bloom and overall strength — while **keeping** grain, scanlines, radial aberration and vignette; it must still be the same medium breaking.

### Two post-process slots

| Slot | Driven by | Scope | Users |
| --- | --- | --- | --- |
| Level slot (vanilla's) | `PostEffectArbiter` | The world image only; the HUD and terminal notices stay legible | Pursuits, World Interface lock/eviction/dispersion |
| Whole-frame slot (ours) | `ScreenFilterDriver` + `MinecraftScreenFilterMixin` | World + HUD + current screen, all inside the filter | Anomaly impacts, both loading screens |

**A presentation you are still meant to play through** must keep the instruments readable and stops at the "glass" layer; **a presentation where the whole screen is supposed to be broken** gets the whole-frame slot.

The whole-frame injection point is the `blitToScreen` call inside `Minecraft.runTick` (which sits neatly inside vanilla's own `if (!window.isMinimized())`). **Its claim is per frame**: consumed and cleared every frame, so the entire class of "the shader got stuck" bugs is structurally impossible.

**There is only one level slot** and three subsystems want it. `PostEffectArbiter` holds a **claim** rather than a result: priority `PURSUIT > WORLD_INTERFACE > ANOMALY`, whichever live claim ranks highest gets installed; a chain this mod did not install is neither overwritten nor cleared.

### The flicker ceiling

It lands on `HoldTicks` — the re-roll period for every discrete term in digital corruption. 3 Hz is 6.67 ticks, so every chain is **≥ 7 ticks**, asserted by test. The analog side has no equivalent field (everything it does is continuous); the sole exception is grain, which is zero-mean per-pixel noise and by definition does not change mean brightness.

### Two shader implementation constraints

- **Strength can vary per frame**: 1.21.11 bakes post-process uniforms at chain load with no per-frame write API — but the `Globals` block from `#moj_import <minecraft:globals.glsl>` carries `GameTime` and `ScreenSize`, and `GlProgram` maintains its own `BUILT_IN_UNIFORMS` that bind whenever a shader declares them. So only a few strength tiers need baking; the motion is computed in the shader.
- **Never read `SamplerInfo`**: every post pass declares it, but it is **filled for only some chains**, and when unfilled the whole block reads as 0 with no error at all. This mod's shaders always take dimensions from `Globals.ScreenSize`, and `PostFilterContractTest` asserts it.

### Retired but retained band overlays

Three horizontal band overlays — the pursuit interference band (`renderInterference`), the impact tear and mistrack bands (`renderTornPicture` / `renderMistrackedBand`) and the loading screen's tracking band (`drawTrackingBand`) — are now carried by shader terms. All four methods **remain in the source and are never called**: they are the reference for what that shader term should look like, and the fallback if some GPU cannot compile the chain. The contract test asserts **nothing calls them any more**, not that they do not exist.

**The only thing still drawn with the GUI is the terminal's weather-tool card** (via `AnalogFilter`): it is one rectangle inside a page and must not cover the neighbouring tabs and close hint, while a post-process chain's uniforms are baked at load and the card's screen position moves with the window and GUI scale.

## The terminal's three appearances

Terminal appearance is **personal state** and does not follow the server's fastest player:

| Stage | Condition | Meaning |
| ---: | --- | --- |
| 0 | No pursuit resolved yet | An old device that can only receive and record |
| 1 | At least 1 pursuit resolved | The device has been in contact with a Corrector and is beginning to erode |
| 2 | At least 3 resolved, allowed form ≥ 4 and anomaly tier ≥ 4 | Mainline, anomalies and pursuits converge; the terminal becomes a correction interface |

Unread prompts still use each appearance's own alert model; **no fourth terminal form is added**. A uniform CRT shell (scanlines and vignette) sits on top of all three and does not change with the stage — see [Terminal interface](terminal-ui.md).

## Current tuning boundaries

Not hidden design, but numbers the current implementation still needs long multiplayer verification for:

- How the queue feels under a single slot (how long an eight-player table waits);
- TPS, disk growth and chunk catch-up while two players stream simultaneously;
- Whether a fixed ±48 blocks vertically covers long shafts or fast ascents and descents.
