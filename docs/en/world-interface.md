# The World Interface finale

The rule book for the End finale in `1.0.0-rc.5`: entry conditions, the ritual, the state machine, numbers, the eight actions, both endings and F8.

This document states **what the rules are**. The trade-off behind each one, the bugs it fixed and the alternatives that were rejected are in [Design notes](design-notes.md#the-world-interface-finale).

> The old "seven minutes, 360 base health, hotbar shuffle, 768-block erosion" and the old Overworld altar are retired and no longer apply.

## At a glance

| Item | Value | Constant |
| --- | --- | --- |
| Roster | 1–8, built from whoever hands over a terminal | `MIN/MAX_ROSTER_SIZE` |
| Ritual window | 3 minutes (3600 ticks) | `RITUAL_WINDOW_TICKS` |
| Max health | `600 × (1 + 0.5 × (players − 1))` — 600 solo, 2700 at eight | `BASE_HEALTH` |
| Collapse limit | 12000 ticks (10 minutes) | `COLLAPSE_DURATION_TICKS` |
| Stability anchors | 10, zone radius 8 blocks | `TOTAL_ANCHORS` · `STABILITY_FIELD_RADIUS` |
| Permanent scars | 8192 blocks total, ≤ 32 per tick | `MAX_PERMANENT_TERRAIN_EDITS` |
| Failure erosion | Radius 160, depth 6, **end stone only** | `EROSION_RADIUS_BLOCKS` |
| Arrows / tridents | 2.5× damage | `arrowDamageMultiplier` |
| Save format / protocol | `WorldInterfaceState.FORMAT_VERSION = 2` / `WorldInterfaceProtocol.VERSION = 3` | |

## Before the finale

The terminal mainline advances on server objectives; rewards pay out on completion with nothing to claim:

| # | Objective | Notes |
| ---: | --- | --- |
| 1 | Learn the terminal | Visit all four tabs; on a new save the walkthrough walks you through (Tools → Records → Files → Home) along **exactly the same server path** as clicking manually |
| 2 | 12 logs/planks + 6 iron | Does not scale with party size |
| 3 | Nether → fortress → blaze rods → return | **Blaze rods scale down**: 3 solo, 2 for two or more. "Find the fortress" tests whether the block under you is a fortress structure piece — the same test the navigation tool uses; reaching the rod count also backfills that milestone |
| 4 | 3 recorded Eye of Ender throws | **The fix is shared team-wide**: once anyone reaches three, the coordinates and triangulation vertices are copied to every other bound player online. Personal throw counts are still tracked |
| 5 | Find the stronghold and enter the End | |
| 6 | Defeat the World Interface | |

**Personal pursuits are not a gate.** An unfinished pursuit neither blocks the finale nor is re-issued in the End.

**From the summon's first tick to resolution, ordinary anomalies, gap pressure and real pursuits are all suspended**, and anything already running is interrupted in place. The suspension is decided per player: roster members and anyone in the End are suspended, everyone else carries on. Rules in [Anomalies and pursuits](anomalies-and-pursuits.md).

**During combat, only combat's own sounds reach the notice stack.** Ordinary progress notices stay queued and expire on their original TTL; rejected actions (`TONE_DENIED`) still answer normally. The decision is the pure function `TerminalNoticePayload.surfacesDuringEncounter`.

Back up the save before placing the last Eye: the finale leaves permanent End scars, and the ending also writes a local isolation marker for the current save.

## The arena

- The fight happens on the **native End main island**; no artificial platform is generated. A ground-hugging 11×11 altar and 10 stability anchors are placed.
- The 20 gateway slots remain in the protocol and state (`MAX_GATEWAYS = 20`) but **place no blocks any more**; bedrock frames from old saves are removed.
- **The twelfth Eye permanently replaces the vanilla End ending**: no exit portal, no dragon egg, no End gateways, and the dragon cannot be revived. On a shared server the outer islands, elytra and End cities become permanently unreachable. Operator guidance is in the [World bible](world-bible.md).
- The altar centre and vanilla's return area have protection radii; bedrock, obsidian pillars, End teleport structures, block entities, key mod blocks and immunity tags are never rewritten by combat.
- **Erosion only affects end stone**, on the same rule server-side and client-side. The pillars keep their own textures — players navigate by them.
- The laser warns 90 ticks ahead; a single sweep queues at most 160 blocks of scarring.
- Altar construction **skips any cell that already holds a block entity** and logs it: the altar would rather be missing a wall than eat someone's chest.

### Rain in the End

**It rains in the End, thunders occasionally, and has no clouds.** This is pure client presentation and applies to the whole End, not just this fight.

| Item | Value |
| --- | --- |
| Endermen | **None** (`EndPopulationService`). The End is this mod's last act and the only thing out there that should be moving is the encounter: endermen are the one mob that teleports (so it arrives without being watched arriving), the one mob that reacts to being looked at (so the island becomes a place you must avoid eye contact in), and during the finale they are unrelated purple particles in an arena whose whole visual language is purple particles. Removed on load rather than prevented from spawning - entities also arrive by portal, by command, by chunk load from an older save and by the dragon fight's own gateway, and one rule covers all of it while staying exactly reversible. Scoped to `minecraft:the_end` only; mirrors and the unrendered layer are untouched. **The cost**: no ender pearls drop in the End |
| Rain level | Drifts between 0.45 and 0.65 on an 1100-tick period; adjacent ticks change by < 0.005 |
| Thunder | The world clock is cut into 600-tick slots, each deciding independently; averages about once every 40 s, minimum gap ≥ 80 ticks, pitch spread across slots, bearing shared server-wide |
| Clouds | Permanently none (asserted on `EnvironmentAttributes.CLOUD_COLOR`'s alpha being 0) |
| Save impact | **Zero.** `ServerLevelData` is never written; `EndWeatherContractTest` scans the whole source to hold this |
| Rule impact | **Zero.** `isRaining()` is always false in the End; a client GameTest asserts it in reverse |
| After the win | **The rain fades to zero over 200 ticks and the thunder stops** (`CLEAR_FADE_OUT_TICKS`). Success only (`Outcome.SUCCESS`): on the losing path the world is collapsing and that sky is not something the player has earned. A player who wins, leaves and comes back finds it already clear rather than watching it fade a second time |

## The terminal escrow ritual

A server-authoritative collective transaction:

1. Right-clicking the resonance core **while holding** your own valid bound terminal joins the roster, capped at 8. Having it in the inventory but not in hand does not count; an empty-handed right-click still opens the screen to view the roster.
2. The first terminal into the core starts the 3-minute window. Latecomers join **the same** window and do not extend it.
3. A successful insertion **opens the altar screen in front of whoever performed it**, carrying that insertion's own status line. Summoning is a manual step and insertion is the one moment the player is guaranteed to be standing at the core - without this they are left facing a block that visibly took their terminal and then did nothing.
4. At the same moment **everyone else in the End, spectators included, is told**: who handed one over, how many the altar now holds, and that starting still needs somebody to press summon. The roster exists only inside a screen, and that screen is open for one person at a time.
5. Withdrawing your own terminal is allowed inside the window; it returns via the ledger. **The "Cancel Ritual" button is gone from the screen**: withdrawing your own is still there, and tearing down everybody else's escrow with one click is no longer a button on a screen. The server-side `cancel()` transaction and the `CANCEL` protocol value remain, for rollback and for older clients.
6. **Summon is an explicit hold**, available only to those who have already handed one over, and only once every joined player present has finished escrowing. On the screen it is a **large, centred, custom-drawn plate** rather than a vanilla button: it pulses while it is live (a 1.6-second cycle, far under the 3 Hz ceiling), whitens on hover, and goes flat grey when it is not. **Pressing it only pushes the plate to the start of its travel** - it fills left to right over about 1.2 seconds (`ending/SummonHoldPolicy`, a pure class tested directly by `SummonHoldPolicyTest`) and commits atomically when it is full; letting go partway zeroes it and **sends nothing at all**. The travel is presentation only: a completed hold sends the request it always sent, and the server's roster, stage, escrow and atomicity checks are unchanged.
7. If nobody summons within three minutes the window lapses: every terminal returns by the same route, the altar goes dormant, it can be restarted immediately, and there is no penalty.
8. An indeterminate state after a server reload, or any failed validation, likewise rolls back and refunds.

A ninth player is rejected individually (`roster_full`) without affecting the other eight places; other people logging in and out is irrelevant to the ritual. Terminals are held by the system during combat, and success, failure and safe recovery all go through the recovery ledger.

## What the three bodies do differently

The forms used to differ in health, size, damage and how often they acted, and **in nothing about the weapons themselves** - the same lance landed in the same circle at the same rate whichever body threw it. That made the escalation arithmetic, and the third phase read as the first phase with a shorter bar. The two weapons a player spends the whole fight reading now change shape between forms (`WorldInterfacePhasePressure`, a pure class):

| | First | Second | Third |
| --- | ---: | ---: | ---: |
| Lance radius | 3.6 | 6.48 | 10.44 |
| Laser damage interval | 5 ticks | 3 ticks | 3 ticks |
| Laser scar interval | 2 ticks | 1 tick | 1 tick |
| Laser tracking lag | 10 ticks | 6 ticks | 6 ticks |
| Beam width and burn radius | x1.0 (2.2) | x1.45 (3.19) | x1.9 (4.18) |
| Beams fired at once | 1 | 1 | **2**, swung 6 degrees each side of the aim |

**The first form is untouched** - it is the one configuration the fight was tuned against and the one that teaches both weapons, and every value in that column returns the old constant.

**The tracking lag is the only number here that is a nerf rather than a widening.** Ten ticks of running covers about 2.5 blocks, comfortably more than the beam's own 2.2-block burn radius, and that margin is the whole reason running works. Six ticks covers about 1.5, which is under it: from the second form on, running in a straight line no longer breaks the lock and the player has to turn.

**The beam is drawn and burns on the same coefficient.** The renderer multiplies every shell width by `beamScale` and the server multiplies its burn radius by the same number - the visible edge has to be the dangerous edge, or a player stands in what looks like the beam and takes nothing, or the reverse. The client's tracking lag is now per-form too; hard-coding the protocol constant there meant that from the second form on, the drawn shaft trailed the burning one by four ticks of running, which was a real bug.

**The third form's two beams open about 12 degrees apart around the aim.** The gap between them is a place to stand, which is exactly why splitting is an escalation rather than a wider unavoidable bar. The lance radius has a unit test over it: even the widest circle must still be escapable inside its own 90-tick telegraph.

**The lock readout was never taken away.** It was, briefly, for the third form's two aimed weapons; that was reverted at the user's request on 2026-08-29. At the point in the fight where two beams and a twenty-block lance circle are on screen at once, the crosshair readout is the only thing telling a player which of them is theirs.

**The third form's second lane can now open both weapons.** `VOLLEY_ACTIONS` used to hold only the orb and the lash, so the extra lane added pressure and never added a second thing to *read*. Both aimed weapons are in the pool now: lances stack, and are meant to - several marked circles opening across the island at once is the third form's signature, and each one is still a place you can simply not be standing. **Beams do not stack.** One sweeping beam already reaches the whole island and is drawn across everybody's screen; two is not twice the threat, it is an unreadable frame with damage in it, so the lane swaps a second beam for an orb whenever one is already running (the scheduled lane included).

## Combat presentation

Particles are **deliberately not metered** here (the user asked for it on 2026-08-29), and `WorldInterfaceVfx` says so in its class doc.

**They are not potion swirls.** The first pass drew every violet as a tinted `ENTITY_EFFECT`, which is the smallest, flattest particle in the game and the one every player has spent ten years training themselves to ignore - sixty rings of it read as haze. Violet is now `WITCH` (the same colour several times the size, with a shape that survives being seen from fifty blocks), the hot core is `SOUL_FIRE_FLAME` (it trails, so a ring of it has visibly *turned* and a helix is visibly *travelling*), and sigils are `ENCHANT`. Three higher-level primitives compose them: **the sigil circle** (`runeCircle`: an outer rune ring, an inner ring of light, sigils written inward, and spokes tying them into one figure rather than three that share a middle), **the detonation** (`detonation`: `FLASH` + `SONIC_BOOM` + several `EXPLOSION_EMITTER` + a firework shell + a flame shell + stepped shock rings + spokes, layered by how far each part carries), and **the beacon** (`beacon`: a flame core, two counter-wound helices and a collar at each end). `SONIC_BOOM` is emitted one at a time and never more - it is a single enormous ring, and two overlapping is one unreadable white smear. It provides shapes and nothing else - no state, no randomness, no authority: rings, rings around an arbitrary axis, outward shock rings, helices, jagged arcs, shells, columns and spokes, with every phase term taken from the world clock so each client draws the same frame and a restart resumes rather than restarts. Everything goes out through `ArenaParticles` (512 blocks), because the arena is 160 blocks across and vanilla's own 32-block limiter would hide most of it.

| Where | What was added |
| --- | --- |
| Body idle (`emitBossPresence`, now **every tick**) | Three gyroscope rings on axes that do not share a plane, counter-rotating in pairs; a core shell pulse that quickens as the pool drains; a **footprint on the floor** (two counter-rotating rings plus spokes), because a hovering thing has no shadow and eight players on a 160-block island had nothing telling them where it was; head-to-core bolts and a collar around each head |
| Hitting the body | A ring lying flat against the surface at the point of contact plus a shell, and a bolt running from the wound back to the core |
| Morph | A 12-tick **intake** (floor rings closing inward, a column running up); a helical wake and ring for the whole climb; three shells and four leaning rings on the `MORPH_REVEAL_TICKS` frame; three outward shock rings, spokes and a column on touchdown |
| Laser | Three rings across the line of fire at the muzzle, plus a shell and an already-wound helix; two threads travelling down the shaft during the sweep with three side arcs every 3 ticks; an outward ring and spokes where it meets the floor |
| Sky lance | The column wound by three threads with a collar riding the leading edge; three shock rings, ten spokes and a shell at the impact |
| Tendril lash | Spokes, a shock ring and a shell at the landing |
| Energy orb | A helix wound around each tick's step and a ring across the flight marking where it has reached; a shell on detonation, plus three shock rings and spokes when it actually went off |
| Lock-on tell | **One geometric figure per attack, all of it flat on the floor** (see below) |
| Tendril descent | A wound trace plus an arc from the core to the mark for the whole telegraph. This attack throws nothing - what hits you is one of the interface's own limbs, and at the third form it comes down from eighteen blocks up off a body thirty-three across, so from the floor the player experienced a circle appearing and then being thrown out of it by nothing |
| Ground mark | An inner ring closing as the telegraph runs out, and spokes tying it to the outer one: the circle said where, and never said when |

### The footprint is deliberately not a sigil circle

The body hovers, so a marker is drawn on the floor beneath it; without one, eight players on a 160-block island cannot find it by looking up. It used to be a sigil circle, and that was a mistake: **the lance's ground mark is a sigil circle too**, so the arena floor carried two violet round figures meaning opposite things - one "it is above you", one "this is about to be hit" - and a player had to tell them apart while running.

The footprint now speaks a different language: **white, straight-edged, hexagonal, no runes at all** (an outer hexagon, an inner one, and a short cross through the middle), where every attack telegraph is round. It is separable at a glance, and it scales with the body, so it also says which form is overhead.

### The lock tell: one shape per attack

Six attacks used to share one closing ring. A player who is busy fighting does not stop to read the label - they see "a lock" and have to guess whether the answer is to run, to look up, or to do nothing. The previous pass answered that with **upright hoops through the player's own body**, which was worse: eye-level particles bound to the player cover the part of the screen they read the arena out of, and a telegraph that hides the thing it warns about has cost more than it gave.

So the shape carries the meaning, **and all of it lies flat under the player's feet**:

| Attack | Figure on the floor | Why |
| --- | --- | --- |
| Laser sweep | Ring plus four spokes pointing back at the core | It arrives along a line from the core, so its mark has a direction |
| Sky lance | Ring plus a second ring descending onto it | The only attack that comes from straight overhead, so the only one that reaches up |
| Tendril lash | **A turning triangle** | Three limbs, three sides |
| Weapon custody | **A square** plus inward chevrons | Nothing is coming, something is being taken; a square because what it is reaching for is an inventory |
| Hotbar purge | **Nine beads** plus a square | Nine slots |
| Grab and throw | Hard inward chevrons and nothing at the rim | It reaches in and lifts |

Only the last 40% of the countdown adds one more ring, **3.1 blocks overhead** - above the sightline, out of the way of the arena. The carry cage follows the same rule: one ring under the feet, one over the head, none across the face.

### The hotbar purge's drops

`GAZE_HOTBAR_CLEAR` throws nine stacks out on nine bearings, onto end stone, mid-fight, after its own knockback has moved the player - recoverable in principle and unfindable in practice. `WorldInterfaceDropBeaconService` **stands a column of light** over each drop: a 3.6-block end-rod shaft, a violet cap, and a violet ring at its foot, redrawn every 2 ticks and sent at the same 512 blocks.

Pickup delay, despawn and the drop rules are untouched - what is added is only being able to see where a stack went. A mark is dropped the moment the item entity is gone (picked up, burned, despawned); at most 256 are kept, none for longer than 6100 ticks, and the whole registry goes with the server. Nothing is persisted.

## One-way lifecycle

`UNPREPARED` is the sentinel before the real finale; the following ten stages advance one way by wire id:

| wire | Stage | Meaning |
| ---: | --- | --- |
| 0 | `UNPREPARED` | Finale not prepared |
| 1 | `ARENA_READY` | Arena ready |
| 2 | `WAITING_TERMINALS` | Collecting terminals, awaiting a summon |
| 3 | `SUMMONING` | Escrow committed |
| 4 | `PHASE_1` | Health `>70%` |
| 5 | `PHASE_2` | Health `>35%` |
| 6 | `PHASE_3` | Health `≤35%` |
| 7 | `SUCCESS_RESOLUTION` | Fatal blow before the timeout |
| 8 | `FAILURE_RESOLUTION` | Collapse reached 100% first |
| 9 | `PORTAL_OPEN` | 3×3 exit open |
| 10 | `COMPLETE` | Poem / return confirmed |

Healing cannot walk a form back. **The collapse is adjudicated before the fatal blow in the same tick**, so an edge case resolves as failure.

## Body geometry

**The body flies; the head comes down for the player to hit.** What you can reach from the ground is the part it hangs down.

| Measure (forms 1 / 2 / 3) | Value |
| --- | --- |
| Drawn body's lower edge above ground | 8 / 14 / 18 blocks |
| Jaw clearance across the whole animation | 1.69 / 1.12 / 0.72 blocks |
| Central head hitbox's lower edge above ground | 2.16 / 2.12 / 2.25 blocks (well inside a 4.5-block swing) |
| Body's leading edge from the player | 4.77 / 6.77 / 7.65 blocks |
| Head hit face from the player | 1.00 blocks (by construction) |
| Collision box height | About 12 / 23 / 32 blocks |
| Attackable parts | 14 / 16 / 20 (body, three heads, 2 segments per neck, 1 per drawn tendril) |

- Clearance is expressed as "player's feet to the **drawn** body's lower edge"; the entity origin is derived as `combatHoverHeight = clearance − massBottomLift`, and `massBottomLift` measures the model's true lowest shell.
- **The model is edited in Blockbench.** The source is `docs/art/world_interface/world_interface.bbmodel`; `tools/export_world_interface_model.py` exports it as the geometry JSON the client bakes. Rig bones must keep the pivot and bind rotation of `WorldInterfaceRig.bindPose()`, which `WorldInterfaceGeometryContractTest` compares axis by axis. Pipeline details are in the [art pipeline](art-pipeline.md).
- **Hitboxes bind to the post-animation rig.** `WorldInterfaceRig` poses the skeleton once per tick (bind pose + clips + procedural drift + structural sag); the server places boxes from it and the client drives `ModelPart` from **the same evaluation**. Clip data lives in common's `WorldInterfaceClips`.
- Head hitboxes anchor on the **jaw** (`+1.45`), not the skull centre, with 45% slack (`HEAD_HIT_SLACK`). The pair of horns on top is deliberately excluded.
- The pose clock runs on a synced `POSE_TICK` (resent every 20 ticks; the client latches the offset and counts itself), or the two sides differ by 5 ticks and head-vs-hitbox by 0.464 blocks. `WorldInterfaceClientGameTest` caps it at 0.20 blocks.
- **Head gaze**: the server writes bearing and pitch relative to the body's facing each tick (`GAZE_YAW / GAZE_PITCH`); the flank heads follow at weight 0.72, and yaw and pitch are each capped at **60°**, clamped rather than wrapped. Rendering interpolates on partialTick; hitboxes do not.
- **Tendril** hit columns cover only the last drawn segment, whose tip stops 4–7 blocks below the body; tendrils are not a ground melee target.
- The form-change shrink (down to 28%) goes through `renderScale`, and the hitboxes shrink with it.

### The skyhold window

`WorldInterfaceSkyholdPolicy` is the single source of truth — a pure class with no persistence.

| Item | Value |
| --- | --- |
| Period | 900 ticks (45 s) |
| Lift segment | The last 260 ticks (13 s), 26 blocks above the combat standoff |
| Climb / descent | 50 ticks each on a smooth curve |
| Applies to | Forms 2 and 3; form 1 **never lifts** |
| Lift fraction | 28.9% (17.8% fully out of reach), hard cap 40% |
| Body's lower edge while lifted | 40 / 44 blocks — only bows, crossbows and tridents reach |
| Speeds | `CLIMB_SPEED` 0.70, `CHASE_SPEED` 0.11 blocks/tick |

- It keeps attacking while lifted; **the only excluded action is grab-and-throw** (`canStartWhileAloft`).
- The climb's first tick plays `world_interface_flight` with a low roar.
- **Tick 0 of any phase is always at the standoff.**
- **The standoff is a "no closer than this", not a set point**: it only approaches while further away, and stays put horizontally once inside.
- The standoff is solved from the head: `standoff = head reach + head hit radius + 1` (`WorldInterfaceAnatomy.combatStandoff`). Neck forward lean is `-0.34 / -0.29`, head reach 4.6 / 9.7 / 14.0 blocks.
- **The summon descent stops 12 blocks above the standoff** (`SUMMON_ARRIVAL_LIFT`) and hovers; the chase brings it down.

### Form change

The morph for forms 2 and 3 lasts 80 ticks (4 s) and the body **leaves the arena**: 12 ticks crouching in place → accelerating climb to 110 blocks above the arena floor → form swapped at the 40-tick apex → 40 ticks decelerating back to the new form's standoff (including whatever skyhold lift applies then). Position is written by `snapTo` rather than given as velocity, so both ends are consistent and a save can resume mid-morph. A shockwave ring fires on departure and again on landing, with a roar added at landing.

## Stability anchors

Living anchors heal and rebuild the shell, lengthen attack intervals, and project a height-unbounded 8-block stability zone at the pillar foot. Inside it, players take 20% less damage and neither combat scarring nor failure erosion may modify terrain. The World Interface **cannot** destroy an anchor.

| Broken | Damage taken | Cooldown factor | Total healing | Zones |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 60% | 1.15 | 0.20% max health/s | 10 |
| 5 | 80% | 1.00 | 0.10% max health/s | 5 |
| 10 | 100% | 0.85 | 0 | 0 |

Each break: +4 percentage points damage taken, one share of healing stopped, cooldown factor −0.03, that zone extinguished. Base move speed is fixed at 0.11 and does not change with anchors. **Breaking anchors does not affect the collapse timer.**

**Breaking anchors is a trade-off, not "towers first"**: the gain is a faster health drain, the cost is denser attacks and less safe ground. The first positive-damage player attack on an anchor during combat destroys it; zero damage, non-player, spectator and non-combat sources cannot. Each break plays about 60 ticks of gold sweep on the HUD and swaps the anchor label to "TAKES MORE · HITS FASTER" — the line states the consequence outright, because it is the endgame's one piece of actionable tactical information and a player reaching it for the first time has no room to decode a pair of arrows.

On entering the End Poem the server submits the final broken count, and the client picks among "all preserved / partly broken / all broken".

### Shape and destruction

The anchor is this mod's own entity (`StabilityAnchorEntity`): a roughly 1.75×2.75 four-way clamp with no head and no face. Four identical claws at 90° run upper arm → square pivot → forearm → wrist → down-turned foot, splaying up and out then closing down to grip the single bedrock cap on the pillar top. A platinum thoracic core sits in the torso; the top carries a bare relay core with clearance all round so the tether can leave through 360° of yaw and a wide pitch range. Four calibration petals sit below the relay core's equator, disconnected from each other. Glow is limited to the two cores and thin gold joint seams.

Breaking is an authoritative event: on the hit tick it leaves the alive mask, stops healing and field projection, and loses collision; the following 16 ticks are only geometry coming apart:

1. The relay core detonates in place (`EXPLOSION_EMITTER` + close-range blast particles);
2. three detonation waves step outward along the bedrock cap;
3. the beam it had been holding is released as a vertical column of light (the same shape as the ten anchors firing in sequence during the summon);
4. a 26-block shockwave ring leaves the pillar foot;
5. two audio layers: vanilla explosion over `world_interface_anchor`'s own voice at pitch 0.62;
6. the client's first two beats throw debris **outward** before rejoining the original inward implosion and violet embers. Per-anchor particle cap 100, per-tick 24; all ten stay under the 1024 total.
7. The camera is driven by a server-sent `WorldInterfaceBlastS2C` at the relay core, HEAVY tier, 80-block falloff — **whoever broke it feels it most**.

The whole presentation calls no world explosion, rewrites no blocks, spawns no fire and no drops; leftover visuals are cleared on disconnect or restart.

## The eight actions

| Action | Telegraph / rate | Effect |
| --- | --- | --- |
| Laser sweep | 90-tick lock + 40-tick sweep | The beam tracks the target's position 10 ticks ago; 3 damage every 5 ticks within 2.2 blocks. Impact explodes and smokes every tick, adds scarring every 2 ticks; **sound and shake throttle separately at 6 ticks**. Ground contact adds 12 ticks of dispersion post-processing |
| Dragon-breath bolt | 40-tick charge + up to 120 ticks of flight | A 0.95 blocks/tick straight-line bolt, direction locked at launch. Explodes on hit or ground contact: 12 damage within 4 blocks plus knock-up, leaving a 220-tick breath cloud and a shallow crater. Can be shot down (it just breaks apart, no explosion) |
| Sky lance | 60-tick lock + 30-tick charge + 20-tick embers | The impact point follows the target during the lock and fixes at the end; 1.5 s later it drops for 15 damage within 3.6 blocks. The column fills top-down over the charge's second half; landing adds 12 ticks of dispersion |
| Weapon confiscation | 45-tick deprivation telegraph, up to 160 ticks of escrow | 10 damage and takes the held tool, leaving a "temporarily disabled" barrier in its slot (movable, not droppable, not usable). The original is returned exactly when escrow ends |
| Grab-and-throw | 50 + 14 + 16 + 16 ticks | Lifted below the body's lower edge, then flung clear of the silhouette (radius + 5) before release. Release restores gravity, deals 10 damage, and throws about 20 blocks out and 30 up; a water bucket landing works. **Singles out one player only** |
| Gaze sweep | 60-tick telegraph; **3600-tick cooldown per player** | Drops one slot every 8 ticks, clearing nine from left to right. Drops behave as vanilla and anyone can pick them up |
| Tendril lash | 45-tick rear-up, then once every 45 ticks, 3 times (each with its own 32-tick wind-up) | Each targets **the nearest player not yet hit this round**: 8 damage and knockback within 5 blocks, scarring the ground at radius 3, cap 12 |
| Forced eviction | 120-tick warning | With ≥3 players, selects `ceil(30%)`; never an integrated-server host; 3600-tick cooldown; prefers players not yet evicted this fight. Can be turned off with `meta.forcedEviction` |

**Form scaling**: damage `formDamage = 1 + form × 0.15` (1.00 / 1.15 / 1.30); sky lance radius `formRadius = 1 + form × 0.22` (1.00 / 1.22 / 1.44); impact scarring radius +1 and cap +50%. **The laser's burn radius and the tendril's lash radius do not scale.**

**Scheduling cadence**: phase 1 150–200 ticks, phase 2 75–105, phase 3 35–60, strictly monotonic. Phase 3 opens an additional **salvo** channel adding several actions every 40 ticks, restricted to dragon-breath bolts and tendril lashes. Salvos are not written to the save envelope.

**Tightening with party size**: the interval multiplies by `1 / (1 + 0.18 × (players − 1))`, floor 0.45; a salvo opens `1 + 0..1 + (players−1)/3` at a time, concurrency cap `3 + (players−1)/2`. **Solo, both factors are exactly 1.0 and 3.** The interval never drops below `MIN_SCALED_INTERVAL_TICKS = 20`.

**The scheduler must exclude candidates with no available target before choosing an action** (`continue`, not select-then-abandon). `WorldInterfaceActionSchedulerTest.everyPhaseKeepsAnActionThatNeedsNoExclusiveControl` holds "no phase may unlock only exclusive controls".

**Exclusive controls**: grab-and-throw, confiscation, gaze sweep and forced eviction share one mutually exclusive channel; a player has 600 ticks of protection after one ends. The retired "grab-and-slam" keeps wire id 3 permanently vacant rather than reassigned.

## Multiplayer target allocation

- **Attacks only land on the frozen roster.** Vertical following and the three heads' gaze likewise track only roster members. Area damage still hits anyone standing in the blast.
- **Respawn override is written from the frozen roster** (after `commitSacrifice`), backfilled per tick for members who log in later.
- The scheduler and the salvo channel share one **transient attention ledger**:
  - the previously named player is hard-excluded from the next selection while anyone else is available;
  - weight is `1 / (1 + times named inside a 600-tick window)`;
  - salvos avoid whoever the scheduler is locking and whoever the same salvo already assigned, allowing repeats only when candidates run out;
  - **whole-party actions take no slot** (the test is `singlesSomebodyOut`: the target count is strictly less than the number of players present).
- **The body's standoff follows the whole table**: a `1 / (1 + distance)` weighted centroid (`attentionCentroid`), which converges exactly to the player's own position when solo. Vertical following still tracks only the nearest player, and so does the three heads' **gaze**.

## Lock telegraphs

Except for forced eviction, every locking action gives three layers at once during the lock: a full-screen border and countdown bar, a converging particle ring at the feet, and the lock tone. **The lock window itself deals no damage.**

- **Only true locking actions use the lock tone** (`isTargetingLock`: laser, bolt, lance, grab, lash) — all five are things flying toward your coordinates.
- **Confiscation and gaze sweep are not locks** and use the opposite shape: a `NOTE_BLOCK_BASS` low tone at a fixed 9-tick interval, pitch falling 0.95 → 0.55, never accelerating and never converging.
- The lock tone uses `NOTE_BLOCK_BIT`: the search phase spaces 11 ticks apart, tightening to 3 as the window advances with pitch 1.32 → 1.74 and volume 0.26 → 0.42; past `LOCK_COMMIT_FRACTION` (70%) it becomes a fixed tone every 2 ticks (pitch 2.0, volume 0.52). Deliberately a UI sound, not a positional one.
- **The bar carries progress only** — no tick marks. Past 70%, the text under the reticle becomes **`! LOCKED !`** in the accent colour; that line appears only for locking actions.

## Camera shake

The server sends `WorldInterfaceBlastS2C` (position + radius + tier) at the blast, and **the falloff is computed on the client**; players outside the radius get no packet. Throttling is the pure function `WorldInterfaceBlastService.permits`.

| Source | Tier | Radius |
| --- | --- | --- |
| Laser impact (every 6 ticks) | MEDIUM | 42 |
| Sky lance landing | HEAVY | 72 |
| Dragon-breath detonation | MEDIUM in form 1, HEAVY in 2 and 3 | Damage radius × 7 |
| Tendril landing | MEDIUM | 34 |
| Stability anchor destruction | HEAVY | 80 |
| Shockwave ring | HEAVY at ≥42, otherwise MEDIUM | Ring radius × 1.35 |

Shockwave rings are **capped at HEAVY**. The sky lance's hit-stop on the targeted player stays client-side, on the same tick as the server's impact.

## Particles and audio

- **The body's own weather** (`emitBossPresence`): a corona outside the core, a field scaled to the silhouette, ash falling below, and arcs between the three heads from form 2. Derived as a pure function of the world clock — **no randomness, no state** — and the only information it carries is health. Budget is about three emissions per tick.
- **Every action has a "muzzle" frame**: the laser runs `chargeCore` through its 90-tick telegraph and gives a muzzle event on the firing frame; the lance fills its column top-down through the charge's second half; the tendril throws a full ring of particles on landing at **the radius the knockback actually uses**.
- **Beam weapons add dispersion post-processing on impact** (`world_interface_dispersion` / `_far`, 12 ticks), hung on the **arrival frame**, the same tick as the damage transaction and the shake packet. Filter languages are in [Anomalies and pursuits](anomalies-and-pursuits.md#three-filter-languages).
- **Distant particles must widen the send radius explicitly**: `sendParticles` defaults to 32 blocks only. The body storm, charging, muzzle, beam motes and the lance column go through `ArenaParticles` (512 blocks); ground markers, impact bursts and lock rings stay on the default.
- **Mix headroom**: `AudioService.ENCOUNTER_MIX_TRIM = 0.72`, applied to the server's `playBounded`, the client's `playBoundedLocal` and the per-tick gain of the form ambience `AmbientLoop`; `EncounterMixTrimTest` asserts all three. The vanilla-borrowed family (`playWithReach`: dragon growl, wither voice, vanilla explosion) is pulled down by **reach** instead: `BLAST_REACH_BLOCKS` from 96 to 72, with the roar kept at 96.
- **The roar is layered**: `ENDER_DRAGON_GROWL` (pitch 0.82 / 0.68 / 0.56) and `WITHER_AMBIENT` (0.68 / 0.56 / 0.46, 62% volume) emitted at the same point on the same tick, pitched apart by about a fourth.
- **Throttles**: hurt sounds 4 ticks, laser impact sound and shake 6 ticks, tendril wind-up ticks 8 ticks. Terrain scarring and particles are not throttled.

> Automation can pin scheduling invariants, damage transactions and sound resource contracts, but "every action is still audible after three straight minutes" remains real-client manual acceptance.

## Success resolution order

Every beat waits for the previous one to **visibly end**; they never overlap. The clock starts at `SUCCESS_RESOLUTION`:

| Tick | Event |
| ---: | --- |
| 0 | `SUCCESS_COLLAPSE` + `SUCCESS_FADE` begin (9 s each); the same tick fires the death sound, the success sound and the first dragon growl, and a shockwave ring (radius = half-width × 2.5) bursts from the core |
| 0–180 | **Death presentation**: the body **ascends** (quadratic ease-in to 0.42 blocks/tick within 70 ticks), turning to ash (`ASH` + `WHITE_ASH` rising from 6 to 36 per tick, trailing `LARGE_SMOKE`), tendrils detaching one by one at even intervals across the first 62% of the window, with a roar every 38 ticks falling in both volume and pitch |
| 20 | Surviving anchors point at the sky |
| 180 | **The body projection is removed** and turns fully to ash: `EXPLOSION_EMITTER` ×3 + 400 `ASH` + 260 `WHITE_ASH` + 240 `END_ROD` + a shockwave ring (radius = half-width × 3) |
| 220–340 | **Summon presentation** (6 s): a point of light runs the full orbit the dragon is about to fly (radius 72, height 48), a column rises from the altar, and the portal-tone pulses tighten from 20 ticks to 5 |
| 340 | **The Ender Dragon appears inside the altar's column** (not on the ring), then spirals out to the ring over 70 ticks |
| 340–500 | The dragon takes 8 s to open the exit: orbit radius 72 → 17, height 48 → 15, period 600 → 190 ticks |
| 410 | First line of dialogue |
| 500 | The exit lands, `PORTAL_OPEN` begins, and the second line is spoken **on the same tick** |
| 500–700 | The dragon takes 10 s to climb back to the high orbit (`RETURN_TICKS = 200`) |

Death clip division of labour: `SUCCESS_COLLAPSE` handles the body and ten tendrils (the body only stops holding level — final −22° pitch, 54° yaw, swelling to 1.14 then collapsing to 0.02; tendrils detach at `1.9 + index × 0.36` seconds, **on the same schedule as the server's `emitLimbFailure`**). `SUCCESS_FADE` handles the light and the ring, and the order is the meaning: the eye flares once and dies → the jaw falls open → the ring goes last, leaving the body, rising 52 units and rotating 2160° before dispersing.

The summon presentation **writes no save state** and is derived entirely from the resolution clock, so a restart resumes and a late-joining client picks it up.

Failure resolution is unaffected and remains a full 260-tick escape presentation.

**The dragon's flight**: the orbit angle is an accumulator rather than derived from the world clock; vanilla's flight integration is detached from this dragon by `EnderDragonMixin` (the server writes position at `START_SERVER_TICK`, and the `move` at the end of `aiStep` is redirected); descent progress reads only the dragon's own age, never the stage; facing takes **the displacement actually travelled this tick** rather than a circular tangent. `FriendlyDragonServiceTest` asserts adjacent-tick displacement is always under 4 blocks.

### The collapse bar rewinds after a win

From resolution, `repairProjectedElapsedTicks` winds the collapse timer sent to clients backward each tick by `repairFraction`, over `REPAIR_DURATION_TICKS = 500` (25 s). The same fraction drives three things: the HUD bar rewinding, end-stone texture erosion lifting, and ground repair sweeping outward from the altar.

**The authoritative timer does not move**; only the projection does. The test is `repairsCollapseReadout(stage, succeeded)` — every stage from resolution onward keeps rewinding, with `repairFraction` capped at 1.

## Exit and poem

After either resolution, a 3×3 exit opens at the altar centre. Entering it follows vanilla:

`showEndCredits → WinScreen → custom branch poem / credits / post-credits → PERFORM_RESPAWN`

The mod does not forge a second respawn system; the original spawn point is saved and restored by the ledger. Only after the success poem is confirmed and the player really returns to the Overworld does view distance unlock permanently from the pre-ending per-dimension lock (6/12/16, 12 elsewhere) to 16.

- **`PORTAL_OPEN` only waits for players currently online.** Offline members' poem and respawn entries stay in the ledger; on their next login `reconcilePlayer` returns them to the Overworld and restores their spawn, and `startPoem` delivers the poem they are owed. There is also a backstop: an unconditional wrap-up 20 minutes after resolution.
- **The twelfth Eye notifies everyone**: every online player holding a bound terminal gets one unnamed record and notice saying only that something moved in the End — no coordinates, no guide.
- **No terminals are issued after resolution**: past `FinaleRuntimePolicy.concluded`, Station Zero issues an explanatory line instead; existing holders are unaffected.

## The two endings and the local lock

### Success

- The World Interface is defeated before the collapse reaches 100%.
- Terminal escrow, resource packs and window state are restored; the success poem, credits, post-credits and vanilla return run. **The success score is released before the resource packs are restored.**
- A persistent success lock is written, and view distance unlocks after actually returning to the Overworld.
- The local successful save shows *sealed* and cannot be entered.

### Failure

- The collapse reaches 100% first and the World Interface enters an **escape presentation**: it hovers 30 ticks over the ruins it made, then climbs vertically (accelerating to 3.4 blocks/tick within 55 ticks) with volume falling and pitch rising with altitude; by the end of the 170-tick resolution window it is hundreds of blocks up. It was not driven off — it simply stopped looking here.
- Singleplayer and remote clients **no longer close the game** after the Windows/fallback presentation: vanilla's return sends the player to the Overworld, and missing-texture rendering plus the action bar take over.
- A LAN host keeps the server running and returns to the Overworld; **only the host's own client** shows missing textures, and LAN guests are unaffected.
- Losers can return to the main menu from the pause menu; after a restart the Alpha presentation persists until F8 recovery completes.
- The local failed save shows *corrupted* and cannot be entered.

### The ending lock

**Identity is written immediately; the window snapshot is filled in afterwards.** The success path first writes an identity-only lock, then writes a snapshot-bearing one from the resource-pack restore callback; the idempotency test additionally requires "if a snapshot is demanded, a snapshot must already exist". The failure path drops the window and locks in the same tick.

**The lock seals only the world this run happened in** (lock format v3 → v4, adding `serverAddress`; v1–v3 still load and simply seal less):

| Where the run happened | What is sealed | Unaffected |
| --- | --- | --- |
| A local save | That save (save list + open flow, showing "sealed" / "corrupted") | Other singleplayer saves, all servers |
| A remote server | That server address (blocked at connect with an explanation) | Other servers, all singleplayer saves |
| Desktop meta not yet rolled back | All three title-screen entries, with the prompt saying to press F8 | — |

Settings, accessibility and Quit are always available. From resolution onward, ordinary anomalies, gap pressure, decay and personal pursuits close permanently and do not reappear in `COMPLETE`.

## F8 recovery and replay

- **With no ending lock, F8 does nothing** and is not a meta toggle.
- With one, it opens the confirmation matching the success/failure result; confirming restores the desktop, notepad, window, resource packs and presentation state this mod holds, then closes normally.
- The notepad written by the failure presentation explains this in its second paragraph; that window opens at ordinary size, not maximised, so it does not cover the failure wallpaper it is written on top of.
- If Wallpaper Engine is detected (`wallpaper32.exe` / `wallpaper64.exe` only), it is paused with `-control stop`, its path is written into the transaction manifest, and F8 resumes it with `-control play`. The mod never terminates the process and never starts a Wallpaper Engine the player closed themselves; a failure here degrades to "the desktop stays as it is".
- After a restart, only a `.thefourthfrequency-corrupted` lossless marker is added to the exactly-matching local save.
- The mod **does not modify** `level.dat`, region files or player data; replaying requires a new world.
- If the ending transaction is interrupted, launch once with `-Dthefourthfrequency.safeMode=true` for a safe recovery.

## Multiplayer effects and HUD (RC.2)

The three custom storm particle types are batched by spatial cell and tick for nearby players in the same dimension. Both server and client have independent budgets, and lower particle settings reduce decoration. Principal laser geometry and damage do not depend on decorative particles. See the [multiplayer verification report](../qa/multiplayer_experience/README.md) for limits and evidence.

The compact HUD measures font widths to allocate separate title, percentage, collapse-clock, anchor-label and lamp regions. Long translations use an ellipsis and the panel retains bottom padding. Anchor-break text cannot overlap the clock; damage flashes trigger on new damage rather than every frame of health interpolation.
