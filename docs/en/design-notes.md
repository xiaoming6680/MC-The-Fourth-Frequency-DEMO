# Design notes

The topic documents state **what the rules are**. This one states **why that number**: the alternatives that were rejected, the bugs that were fixed, and the holes that reopen the moment a threshold gets "tidied up".

Check here before changing a number. These reasons are not history — they are constraints, and most of them were bought with a real failure.

- [The World Interface finale](#the-world-interface-finale)
- [Anomalies and personal pursuits](#anomalies-and-personal-pursuits)
- [Terminal interface](#terminal-interface)
- [Background music](#background-music)
- [Architecture and lifecycle](#architecture-and-lifecycle)

---

## The World Interface finale

Rules in [The World Interface finale](world-interface.md).

### Why the roster is not "everyone online at the time"

The old rule defined the roster as **every non-spectator online at freeze time**, which compressed three unrelated things into one number: who is playing, who is participating, and how big this fight should be. The whole cost landed on multiplayer:

- With 9 online, every insertion was rejected as `invalid_roster_size` and the finale **could never start** — and the extra person might be idling in the Overworld.
- Anyone logging in or out fired `roster_changed` and threw the whole escrowed team back.
- Someone who had just joined and never touched the mainline still had to walk to the End and hand over a terminal, or nobody could start.

The roster is now built from **the only meaningful evidence**: a terminal actually placed in the core. The three-minute window and the explicit summon are the two debts that change incurred — once the roster can keep growing, something has to answer "when is it complete", and that answer has to come from the people fighting.

Format 1 still loads; a save caught mid-ritual is recognised as "has a roster but no window" and handled by the existing indeterminate boundary — roll back and refund.

### Why personal pursuits are no longer a finale gate

It hung a per-player condition on an action whose consequences land on the whole world: one person at the table could place the last Eye and another could not, and neither could read why from where they stood. What it bought did not exist either — the first pursuit only requires "bound + one anomaly completed + any activity proof", which is satisfied long before twelve Eyes are assembled.

### Why rain in the End has to be pure client-side

Vanilla did not build the End as "cannot have weather"; it built two switches. The dimension type has carried `has_skylight` all along, so the End tracks a rain level exactly like the Overworld; what actually keeps rain from appearing is the biome declaring no precipitation. This feature inverts both switches — `EndWeatherClient` writes the rain level, `WeatherEffectRendererEndRainMixin` answers the column's question — so the End rains using **vanilla's own** renderer, particles and rain audio.

**There is a shorter path, and it ruins saves.** Calling `setWeatherParameters` on the End's `ServerLevel` looks like it works, but rain, thunder and their timers all live in one `ServerLevelData` **shared by every dimension in the save**. It would make the **Overworld** rain permanently, and keep raining after the player leaves the End. That is a save-level change bought with atmosphere — exactly the category the world bible rejects.

**And it does not leak at all.** `Level.canHaveWeather()` names the End explicitly, so `isRaining()` is always false and every "is it raining" rule in the game still gets the answer it always had; meanwhile everything that draws rain only checks `getRainLevel() > 0`. The End gets rain that is **visible, audible, and unacknowledged by the world**.

The rain level does not reach vanilla's storm value of 1.0 because the End is the finale's arena and the screen already carries the HUD, lock presentation and impact-frame dispersion — rain is atmosphere and must not become another layer between the player and the ground. Clouds are excluded by assertion: a cloud layer over the End would put a ceiling on an arena whose entire point is that there is nothing overhead.

### Clearance was adjusted three times

First, forms 2 and 3 went up 4 blocks each and form 1 by 2. The reason was not "make it higher" but **the head**: the central skull is 3.3 / 5.8 / 6.9 blocks wide across the three forms, a jaw hangs below the skull, and clearance is expressed against the **body's lower edge**. At the old numbers, form 3's jaw reached about 1 block below ground at the low point of the animation. Raising everything would lift the necks and skulls with it, so `NECK_STRETCH` changed to `1.91 / 2.17 / 2.53` in step (**longer**, not shorter).

Then everything came down 2 blocks, spending exactly what the previous step had bought: with the head off the ground it had also retreated to the edge of a swing and could not be reached in a real fight. The present 1.69 / 1.12 / 0.72 blocks is **the promise itself** rather than slack; **pressing it further requires measuring first**.

`massBottomLift` must measure the model's true lowest shell and cannot be estimated as "core height minus body half-width" — that overestimates clearance by 5–6.5 blocks, burying part of the body underground for the whole fight.

### Hitboxes used to be in empty air

They used to stand on the static bind pose while clips flung the central skull around — 96° on a gaze lock, 58° down on a lance, nearly 20 blocks in form 3. The same period also had a coordinate error: the living model is drawn through `scale(-1,-1,1)` and a half rotation, mapping the model's own -Z to the entity's forward, while the old formula flipped only Y and not Z — so every head and neck hitbox landed **directly behind** the storm.

**The hitbox aims at the jaw, not the skull.** `SKULL_CENTRE_Y_UNITS` was `-0.2` (the skull cube's centre), but the skull is not the head: a jaw hangs from the same bone and the drawn head reaches +6.2, while a skull-centred box stopped at +5.55. **The whole misalignment was on the lower edge** — exactly the end a player standing under the storm is swinging at.

**The pose clock has to be synced.** Of `rig.pose`'s seven inputs only `tickCount` is not a synced field — it counts from the moment the entity entered **this side's** world, which is "when it came into tracking range" on the client and "when it was summoned" on the server. Measured, the two sides differ by 5 ticks, putting the drawn head and the hitbox 0.464 blocks apart, of which 0.446 is on z — exactly the axis a player swings along. That is "I can see the head above me and I swing through it".

**Tendril hit columns are no longer stretched to the ground.** Drawn tips stop 4–7 blocks below the body, and the old hitbox pinned the column to the floor, putting a hittable volume in air where nothing is drawn. Making tendrils a melee target again means **lengthening the model**, not letting the hitbox keep lying for it.

### Why the three heads do not knot

Model space has Y pointing down and the model faces its own -Z, so for a drooping, forward-leaning neck chain **both positive yaw and positive roll push it toward -X**. The old code wrote `side × angle`, which twists the head hanging at +X across the centreline: both flanking necks pass through the central one. In form 2 the two flanking skulls are only 0.63 model units apart while being 7.2 units wide themselves.

Gaze does not bring them closer because 68% of the yaw lands on the skull itself and 32% on `neck_b`'s yaw — both rotations **about the chain's own axis**, and rotating about a bone's hanging direction moves no joint below it. The only thing that really moves a skull is the 0.20-weighted roll on `neck_b`. Pitch is applied to the skull only and never to the neck: pitch on the neck pushes the head down with it, and the head has only just been lifted out of the floor.

### The pitch cap used to disable the whole feature

It was 30°, and this fight's geometry does not fit inside 30°: the body is only "body radius + 3 blocks" away horizontally while hovering 8–18 blocks up, putting the player at roughly 50° or lower relative to the head — so every frame clamped, the head permanently pointed at empty air some distance in front of the player, and coming closer changed nothing.

**The head also used to "teleport"**: gaze is a synced value that steps once per tick, and the server turns at most 9° per tick, so drawing it directly reads as "still for three frames, then a jump" — which on a seven-block skull reads as teleporting. Rendering therefore interpolates on partialTick; **hitboxes deliberately do not** — one pose, unchanged, and at most one tick of render lead, far smaller than the head hitbox's own slack.

### Why the standoff is solved from the head

The old formula was "body radius + one swing", which put the body's **leading edge exactly 3 blocks from the player in all three forms** — a twenty-five-block-wide mass hovering eighteen blocks up whose front edge starts three blocks in front of you reads as standing on your head. But the player never swings at the body; they swing at the central head.

Changing the standoff formula alone was not enough: the old neck lean of -0.16 / -0.12 put the central skull only 2.8 / 6.2 / 9.3 blocks forward while the body half-width was 3.3 / 8.1 / 12.4 — **the head did not reach past the body's leading edge at all**. Pushing the body back pushed the head back with it. So the neck lean increased to -0.34 / -0.29.

Before → after (three forms in order):

| Measure | Before | After |
|---|---|---|
| Body leading edge from the player | 3.00 / 3.00 / 3.00 | 4.77 / 6.77 / 7.65 |
| Head hit face from the player | 1.09 / 0.76 / 1.12 | 1.00 / 1.00 / 1.00 (by construction) |
| Head hitbox lower edge above ground | 1.44 / 0.78 / 0.43 | 2.16 / 2.12 / 2.25 |

**The standoff is a "no closer than", not a set point**: in the old version the standoff retreated in the same instant the player ran toward the storm, so how long closing the distance took was entirely up to the storm.

**The summon descent stops higher**: the thirteen-second arrival used to land exactly on form 1's combat standoff, making the last thing that happens in the entrance "nothing" — the arrival frame and the combat frame were the same picture.

### The form change is a deliberate inversion

The argument for morphing in place was that for 4 seconds there is nothing in the arena to hit, i.e. combat stalls. That cost is still paid, and what it buys is different: a 25-to-33-block-wide mass cannot turn into another mass in front of the player. The shrink can cover the frame where the model swaps, but it cannot cover "the silhouette I have been fighting for two minutes was replaced in place". Flying out and coming back makes the next form an **arrival** rather than a replacement.

### The skyhold cap is design, not a comment

`WorldInterfaceSkyholdPolicyTest` measures the fraction tick by tick rather than dividing two constants, so changing the window's shape (two climbs per cycle, say) is caught too.

The only action excluded while lifted is **grab-and-throw**: it closes tendrils within 26 blocks, and up at the ceiling the body is forty-odd blocks from the arena, so its 2.5-second telegraph would inevitably whiff every time — that is not an attack the player dodged, it is the scheduler spending a slot on nothing.

Vertical and horizontal speeds are budgeted separately because the climb has to finish inside its own 50 ticks while the chase has to be slow enough to be outmanoeuvred; one shared speed would slide a 33-block-wide body across the island at 11 blocks per second.

### Why breaking anchors changed from a penalty into a trade

At the old numbers, "the more you break the tankier it gets" was a perverse incentive. Now the gain is **draining the pool faster** and the cost is denser attacks and less safe ground. The vanilla-trained "towers first" instinct still has a price here, but the interaction no longer lies about it.

**The destruction bursts outward before it is drawn back in.** The old presentation was inward throughout, on the reasoning that "the structure is being recovered, not blown up" — that reading is still its **ending**, but it turned the single thing the player most wants to make happen all fight into a lamp going out.

**The camera answers too.** The old implementation inferred it client-side from "the alive mask changed", used the lightest shake tier, took the **arena centre** as origin and fell off over 96 blocks — so a player standing beside the anchor they just broke felt it *less* than a bystander at the island's centre.

### 2.5× on arrows is not a ranged bonus

The arena deliberately puts the body 8 / 14 / 18 blocks up, and all a ground player can swing at is the central head hanging down. But ranged was previously paid at melee's unit price: a fully enchanted bow does about 9 damage per second, while an eight-player fight with every anchor standing heals 5.4 per second — most of a whole session of shooting was cancelling regeneration. At 2.5× an arrow is about 20, clearly beating the heal without turning the health pool into a formality.

The multiplier applies only to arrows and tridents (`AbstractArrow`), never to splash potions, fireworks and other projectiles that are neither aimed at the body nor short on damage.

### Scheduling used to freeze the boss for 30 seconds

The old code committed to a candidate first and only then asked who it could target; when the answer was "nobody" it simply returned. But the candidate is a pure function of `(phase, seed, index, previous action)`, and none of those four change while no attack is running — so the next tick made exactly the same choice and returned again, and the fight stood still until whatever made targets unavailable expired on its own.

Three of the six actions unlocked in phase 2 are exclusive controls, and every exclusive control grants its target 600 ticks of immunity — while a solo fight's "whole roster" is that one person. Phase 1 has no exclusive controls at all, which is why the bug only appeared from phase 2 onward — exactly where players reported it.

### The three phases are a steep staircase

Phase 1 is deliberately slow (150–200 ticks): it is the player's first sight of these eight attacks, and a telegraph only teaches once there is quiet after it — at five to seven seconds the next lock lands before the last one has been read.

**Salvos only deploy the two actions the server draws itself** (bolt and tendril lash), because the laser's and the lance's geometry is solved client-side from the single action envelope, and a second copy would land where nobody can see it.

**Interval and salvo size tighten with party size** because one attack names one person: in an eight-player fight each player is locked one eighth as often, while the health pool grows only half a share per player — together, the more people, the less each of them does. Widening salvos is safer than compressing the interval because it shortens no telegraph; compressing the interval means everyone is hit sooner, so its factor is deliberately conservative. **Solo, both factors are exactly 1.0 and 3** — the configuration every other number in this fight was tuned against.

### Why the form damage step is smaller than the radius step

This hit already goes through armour, resistance and Protection, and the gap between an unarmoured player and a fully enchanted one is already close to an order of magnitude; multiplying again per phase would only make the later forms look arbitrary.

The only two that do not scale are the laser's burn radius and the tendril's lash radius: the former must stay smaller than the distance covered by the 10-tick tracking delay, the latter smaller than the distance a sprint covers inside the telegraph. Widening them would not make the fight harder, only unreasonable in a way the player cannot see.

### Why the gaze sweep is rationed per player

Nine slots on the ground is worth taking once and not worth taking repeatedly — and the 600-tick immunity shared by all exclusive controls is nowhere near enough to keep it in the first category: a ten-minute fight has room to do it to the same person ten times. The cooldown starts when the first slot actually drops, so a target who disconnected during the telegraph and lost nothing does not spend those three minutes for nothing.

The filter lives in **target selection** rather than the scan layer, so in multiplayer the action still lands as long as somebody is off cooldown.

### The attention ledger

The old `(seed ^ index) mod players` was in fact a perfect rotation when the roster was stable and the count was a power of two — but the roster is rebuilt every time against exclusive-control immunity, death and arena radius, and one person entering or leaving shifts every index. Measured over 600 attacks, the old rule produced runs of up to 10 consecutive picks on the same player and a spread of nearly 50 between most and least.

**The cost has to be stated**: under ideal conditions the share is no longer exactly even — over 600 attacks the most and least named differ by about a seventh to a fifth. That is deliberate — **a fight whose next target can be computed is a fight you can line up and wait for**.

**The ledger is transient.** Persisting it would mean a schema version, a migration and a validation rule, out of proportion to the benefit; and a restart already cancels the current attack and grants a recovery grace period.

**Whole-party actions take no slot**: the tendril lash names everyone by construction, and counting it would raise everyone's weight at once and dilute the ledger's memory of who is actually being singled out.

**Attacks only land on the roster** because previously a player who logged in after the sacrifice and wandered over to watch could still be locked, confiscated and forcibly evicted (a real disconnect) — by a fight they never entered and cannot end. Likewise, **respawn override belongs to the sacrifice, not to the portal**: the ledger caps at 8, and real participants queued behind eight passers-by were silently rejected.

**The body's standoff follows the whole table**: a hard "nearest player" parked a body up to 33 blocks wide over one person indefinitely, blocking their view and burying the anchors and the core in its silhouette — and since the lance and the lash both strike the ground under the body, standing closest actually meant standing where most attacks would land. The safest play for a four-player table became "everyone stay away and let one person carry aggro they never asked for".

The three heads' **gaze** still follows the nearest player — the one piece of cheap, targeted menace kept through this change.

### The five causes of the animation transitions

Players reported "the transitions between actions are too fast, like teleporting". Five mutually independent causes were located, all the same class of error: transition durations written for a normal-sized model on a body up to 33 blocks wide. None of them is in the render layer, so comparing screenshots frame by frame would never find them.

1. **The `actionCharge` cliff.** The value ramps 0 → 1 over the first 2000 ms, and the frame after crossing it **returns the -1 sentinel**, which consumers read as 0 — so at exactly two seconds into every attack, the entire forward lean of all three heads zeroes in one frame. It now decays linearly over `ACTION_CHARGE_RELEASE_MILLIS = 900`.
2. **Hold recoveries compressed into 0.12 s.** All eight hold clips ended at `seconds - 0.12F`, withdrawing their full amplitude within 2.4 ticks whether that was 26° or 180°. Duration is now derived from amplitude (`releaseAt`) at `SETTLE_DEGREES_PER_SECOND = 90` (half the gaze rate), with a 0.36 s floor and a cap of 45% of the remaining time — the hold *is* the telegraph and must not be eaten by the recovery.
3. **The grab-throw recovery lasted 1 tick.** The peak was written at `seconds - 0.05F`: ±86° was reached on the second-to-last frame and zeroed on the next.
4. **`*_RECOVER` impact accents had keyframes less than 1 tick apart.** Confiscation's `storm_body` accent ran 0.08 / 0.06 / 0.04 s across three segments, and `storm_body` is the parent of every neck and limb. The shapes and overshoot values are all preserved; only the segments were spread to at least 0.26 s. The jaw's accent is untouched — the jaw is supposed to snap shut.
5. **The idle clip's phase was computed wrong.** `idleHeads()` used `wrap()` on the keyframes' **timestamps**, so heads two and three received a table out of chronological order — while `Mth.binarySearch` and Catmull-Rom both assume it is sorted. The amplitude was small (about 1.3 blocks) but it happened three times every 6 seconds, never stopped, and happened in the **idle** pose. The phase now rotates **values** rather than timestamps.

**The regression test asserts discontinuity, not speed.** The first version read "no bone may move more than N blocks per tick" and immediately failed the grab — which really does drag the skull across half a second at 2.5 blocks per tick, but that is a lunge. What players read as "teleporting" is **one frame not belonging to the frames around it**. The neighbouring tests sample at 250 ms and would catch none of the five causes above.

**Still unfixed**: cancelling an action mid-clip still leaves that frame a hard cut. Fixing it means the entity additionally syncing "the previous action" and its end tick and the pose function cross-fading — an entity-data and protocol change that must also pass through the server hitboxes.

### Why the lock tone is only for locking actions

A missile lock tone promises "you still have time to get out". Confiscation and the gaze sweep take something from you; there is nowhere to dodge and the window is only a notification. Spending the lock tone on something nobody can escape only teaches players to stop trusting it when it matters.

**The value of this shape is that the rhythm itself carries the remaining time**: no attention needs to be allocated before it changes, and the moment it becomes a solid tone cannot be misheard. It solves a real problem — being locked used to be **silent**, and the converging reticle and countdown bar are both in the centre of the screen, which in a fight played by moving is exactly where players are not looking.

Deliberately a UI sound rather than a positional one: being locked is not an event at a place in the world, it is a fact about **this player**.

**A line on the progress bar has to be learned before it means anything; a sentence does not.**

### Why camera shake moved to server notification

The rule is: **derive the beats, notify the blasts.** Beats the client can compute from the action envelope (a form change's shell crack, the summon roar) stay client-derived. Blasts are not in that class, and the old implementation treated them as if they were — while getting three things wrong:

- **The sky lance** shook on "lock + `SKY_LANCE_FALL_TICKS`", but that is a pure render constant describing the column's final 3 ticks of descent; the real impact is **27 ticks (1.35 s) later**. The camera therefore fired a second and a half before the crater.
- **The tendril lash** shook every 30 ticks, while the three impacts actually land every 45 after "rear-up + wind-up"; the two rhythms never lined up.
- **The phase-3 salvo channel** is not written into the action envelope at all, so an entire attack channel was silent on the camera — not mistuned, structurally impossible.

Shockwave rings cap at HEAVY because the summon fires three rings within a second while the client independently runs its own CATACLYSM-tier hit-stop and release near their impacts. The shake budget is a comfort limit, not a matter of taste.

### Distant particles

`ServerLevel.sendParticles` only reaches players within 32 blocks. That default is right for almost every particle in the game and wrong for most of this fight's emitters: the arena is 160 blocks across, the body hovers tens of blocks up, is up to 33 blocks wide, and two beam weapons reach the whole island.

**Nothing catches this difference**: the particles really were sent, the server really did run the code, and the only symptom is that seven of eight players never mentioned seeing it.

**The arena used to say nothing between attacks**: every particle in this fight belonged to some attack, so the scheduler's gaps — seven to ten seconds in phase 1 — left the body a static shape hanging in a clean sky. `emitBossPresence` fills that layer and makes health visible in the sky rather than only on the bar.

### Three causes of ability sounds disappearing

**The third one is what actually silenced the fight.** `silent_world` mutes MUSIC, AMBIENT and **HOSTILE**, and every cue in this fight runs on HOSTILE; it is also a sustained anomaly lasting 2–3 minutes. The gate at the time, `backgroundSystemsAllowed`, did not close until **resolution**, so the summon and all three forms could roll it. The client's `gainBySource[HOSTILE]` watchdog was added against that symptom without knowing the cause: it pushed the gain back to 1 every frame while the anomaly pushed it back down every frame.

The first two rounds fixed real things that were not the main cause:

- **Server**: the scheduler could stall on an exclusive-control action with no target (see "Scheduling used to freeze the boss for 30 seconds").
- **Client**: World Interface damage goes through a virtual health transaction and never calls `LivingEntity#hurtServer`'s vanilla hurt branch, so declaring `getHurtSound()` alone plays nothing. Every transaction that successfully deducts virtual health now explicitly emits `world_interface_hurt` at the hit point.

The watchdog is kept — it also handles the separate problem of OpenAL sources stuck in `AL_PAUSED`, and only unpauses sources genuinely stuck there.

### The 0.72 mix trim was set at an older level

Nothing in the pipeline had ever heard the two together: cues were tuned one at a time against silence, while music was baked at a fraction of its master's gain before ever reaching the game. The result was not "combat is loud" but **"the music is gone"**.

**This number needs re-checking by ear.** Music used to be scaled by a fixed fraction of its master, and the phase-3 track was individually raised to 0.55 to avoid being buried by its own fight, ingesting at about −8.8 LUFS. Music is now aligned to −24 LUFS first and then attenuated, and all three encounter tracks measure about −25 LUFS — roughly **16 dB lower** than the old phase-3 track. The relative relationships still hold, but this headroom was measured at a different level. See [Background music](audio.md).

Taking one factor rather than editing forty call sites is deliberate: the cues' volumes **relative to each other** are authored and correct; what was wrong was the position of the whole group, and that is one number.

**The vanilla-borrowed family cannot reach that factor**: `playWithReach` converts the desired radius into the volume field, and any radius over 16 blocks needs volume > 1, which the engine clamps to exactly 1. They are already at full gain, so multiplying only shrinks the radius. The roar keeps 96 blocks: a roar only the arena can hear *is* the original bug.

### Why the roar is two samples layered

The dragon growl and the wither voice are both sounds the game has already taught the player, but neither says the right thing alone: a dragon growl means "the End", except it says "*that* dragon", and this is not that dragon; the wither is vanilla's other boss throat and carries a coarseness the growl completely lacks. Layered and **pitched apart** (about a fourth), neither is itself any more — two samples at the same pitch would only be heard as one with a chorus effect.

### Grab-and-throw used to name two people

It returned two targets, but only the first was actually grabbed, and the envelope was re-sorted by UUID before being sent. Three consequences stacked: in any pair, the one with the larger UUID could never be grabbed; the second name spent 600 ticks of exclusive-control immunity for free; and the client's lock presentation is driven entirely by that same target list, so that person received a complete "you are locked" warning and then nothing happened. It was residue from the retired grab-and-slam.

### The success resolution order was changed three times

**First**: originally the dragon spawned at tick 80 and thanked you at tick 170, while the body was not removed until tick 260 — the thing you just killed was still hanging in the air collapsing while its replacement flew in to thank you for it; and the exit rose from the altar 90 ticks after the last line, unrelated to the dragon.

**Second (the summon)**: the dragon was added to the world 20 ticks after the body vanished, with nothing in between and nothing announcing it — one tick of empty sky, the next a dragon already flying, seventy-two blocks away. Now six seconds are spent drawing the orbit it is about to fly in front of everyone, which also answers "where should I look".

**Third (the death itself, in two rounds)**: round one's problem was that "death" existed only in the model — the world said **nothing** beyond the two opening sounds, and a thirty-three-block-wide body lay silently in the sky for 6 seconds and then ceased to exist between two frames.

Round two changed what the death says. The body used to **topple** — rotating over eighty-two degrees. Toppling reads as "knocked over". But this ending is not about it being beaten, it is about it being ended; what it does as it leaves is **let go**. So it now ascends and turns to ash, the ash falling while the body rises — it is climbing, and what it is made of is not coming with it.

### The dragon's four bugs

1. **It spawns at the arena centre**, not somewhere on the orbit. The summon had just spent six seconds pulling everyone's eyes to the altar, and the dragon appeared seventy-two blocks away.
2. **The orbit angle became an accumulator.** The old form was `frac(gameTime / period)`, and `period` sweeps continuously from 600 to 190 during the descent. At world times on the order of a hundred thousand, `gameTime / period` sweeps from about 167 turns to about 526 across the descent's 160 ticks — **the fractional part cycles about 360 times**. That is not a speed problem; that expression is simply discontinuous in `period`.
3. **Vanilla's flight integration was detached.** The server writes the dragon onto the orbit at `START_SERVER_TICK`, but `EnderDragon#aiStep` ends with an unconditional `move(SELF, getDeltaMovement())` — **so every step was taken twice**: with `d` the orbital displacement in one tick and `e` the deviation, `e = d − e_previous`, oscillating between `d`, `0`, `d`, `0`. `d` is about 0.75 blocks on the high orbit and over 1.5 during the spiral. The facing convulsed with it, because it reads "the displacement actually travelled this tick", and every other tick that displacement is the difference of two nearly equal numbers. **It cannot be fixed with `setNoAi(true)`**: in 1.21 that switch only stops the wing beat, leaving a dragon frozen with its wings out.
4. **Descent progress no longer derives from the stage.** `dragonApproach` used to require the encoding still to be `SUCCESS_RESOLUTION`, and the tick the exit opens is exactly the tick that stage ends — so approach fell from 1 to 0 between two ticks and the body was thrown **sixty-odd blocks in one step** back to the high orbit. That is the "it teleports after it spawns" teleport: not its entrance, its exit.

Facing takes real displacement rather than a circular tangent because the path is no longer a pure circle. Vanilla's own turning cannot interfere: `aiStep` only changes yaw when the flight target differs horizontally by at least a hundred-thousandth of a block, and `configure` re-pins that target onto the body's own position every tick.

### The collapse rewind keys off the outcome, not the stage

The test was originally written as `stage == SUCCESS_RESOLUTION`, and the repair duration is exactly 500 ticks while the exit also opens at resolution tick 500 — so the moment the bar reached zero the stage advanced to `PORTAL_OPEN`, the projection fell back to the real timer, and **the whole collapse bar snapped back to full in one frame**. The player sees the damage erased and immediately reapplied, on the one screen that is supposed to say "it is over".

It keys off the outcome rather than only the stage because failure also reaches `PORTAL_OPEN`.

### Why combat suspension is decided per player

`FinaleRuntimePolicy` used to be purely world-level: one team starts a ritual and every player on the server has anomalies, gap pressure and pursuits stop, even someone who just started in the Overworld. Its two halves are not the same claim at all — **after resolution** the world is over, for anyone, forever; whereas the reason **during combat** (combat is the one unambiguous stretch of the whole flow, and unrelated pressure crossing it is noise) is true only of the people in it. Silencing a player mining in the Overworld for ten minutes because eight people walked into the End borrows an argument that was never about them.

### `PORTAL_OPEN` used to be held open by an offline player

The end condition used to be "every member of the frozen roster has read the poem and had their spawn restored". Someone who finished and never came back therefore pinned the whole world in this stage permanently — and not only as unfinished state: `createPortalTransition` hijacks the End portal precisely on "not COMPLETE", so everyone's End portal kept sending them into the arena.

### The altar used to eat chests

The altar is fixed at the native main island's origin, which is exactly where multiplayer saves park their return portal, chests and dragon-fighting camp. Construction uses `EDIT_FLAGS` without `UPDATE_NEIGHBORS`, so containers **do not even drop** — the block entity and everything inside it simply cease to exist.

### The ending lock's two deadlines are opposites

The lock file holds two things: **which run ended and where** must hit disk the instant the outcome is decided, because the client may not get another chance; while **the window snapshot to restore to** must be taken late, after the presentation window is withdrawn.

The failure path never had to make this choice — it withdraws the window and locks in the same tick. The success path did, and chose wrong: it put the whole `lock(...)` inside the resource-reload callback, which takes several seconds and additionally requires the client to survive long enough to run that queued task. A player who quit inside that window had beaten the game and landed on a title screen with nothing sealed — precisely the outcome this lock exists to prevent.

**The lock seals only the world this run happened in.** It used to be read as a global boolean: beating it once on a friend's server would take away all of that player's unrelated singleplayer saves and every other server they play on. The identity needed was in the lock file all along; the remote half has now been filled in.

### Why no terminals are issued after resolution

A player joining then used to receive a terminal — a device that will never speak again and will never explain why.

---

## Anomalies and personal pursuits

Rules in [Anomalies, terminal forms and personal pursuits](anomalies-and-pursuits.md).

### Why only 4 anomalies have an opening cue

Those four can all be **missed entirely** (a herd turning at once, an action you just performed, an item wearing the wrong face, a hand reaching into frame), and each has a concrete location, so a bearing is real information. The ones that fill the whole screen or change a rule do not need pointing at — **pointing at them turns "something is wrong here" into a notification**.

Using vanilla's cave ambience `ambient.cave` is deliberate: a synthesised cue announces "the mod is doing something", while the cave sound announces "there is something here you did not put here" — and it does that before the player consciously identifies what the sound is. Playing it positionally rather than in the ear gives it a bearing: the anomaly came from somewhere, and turning to look is something the player can do.

Red horizon's original tuning-sweep cue was removed: two opening cues in the same frame is one too many.

### Why the three anomalies were merged

The reason is the same each time: **an idea split into two four-or-five-second halves is weaker than one event that does both.**

- Surface fracture + phantom echo: one was 10–16 seconds of sound only, the other 5 seconds of crack only, and neither half was enough for the player to do the thing they were jointly describing — follow the sound to that wall. The merge then walked into a second problem: both halves opened at once and each burst picked one at random, so the parts were all present and the order was gone. It is now split 2/5 into an approach and a digging act, the crack is opened by the first blow, and the length went from 10–16 to 14–20 seconds — the extra four are in front of the digging, which still runs for about as long as the whole anomaly used to.
- Temporal drift + metric drift: two sustained anomalies each bending one dial, each saying it once, and never at the same time.
- Lighting solve failure + local rule collapse: after merging, what comes back and what does not *is* the anomaly.

**The cost is that tier 1 has only 3 entries left.** With a pool of 3, the last-three exclusion rule falls to the fallback branch nearly every time, and light dropout, being shared, is additionally deprioritised when another bound player is nearby. Tier 1 is short, but this is the slot a new anomaly should fill first.

### Why light dropout does not remove glowstone

A hole in the wall is not a light going out; it is a block being stolen, and this mod does not do the latter. So only solid light sources with a non-emitting relative in the same family are swapped; anything without one stays exactly as it is and keeps burning.

### Why shared entries only deploy when the target is alone

This is both harm reduction (lights out, doors open and being relocated all land on bystanders who never rolled for it) and the narrative itself: the whole escalation is about **being singled out**, and what reads most as being singled out is precisely what others cannot see.

### Dark watcher and HIM used to expose three tells

They were previously pushed to every client in range, so: a bystander could walk up and study it at leisure (the despawn check only looks at the target); a bystander hitting it got no reaction (`hurtServer` only answers the observer); and four people spending a night together produced four of them. All three say the same thing — **this is a mob with spawn rules** — which is exactly the reading the whole effect exists to avoid.

### Why HIM must always face the player

Aiming it once at spawn is not enough: the moment the player circles to the side it reads as a statue somebody left behind. The placement rule *is* the anomaly — it just stands there and then disappears, and **what decides whether that reads as a sighting or as a spawn is entirely where it is standing when the player turns around**.

Distance ×1.6 by day, because at twenty-two blocks in daylight a crisply rendered humanoid reads as a mob that spawned rather than something that was already there.

### The unrendered exit's colour delta went from 9% to 20%

9% came from "two just-noticeable-different values under large-area uniform light", and measured it fell **below** the threshold rather than on it: the exit was not deniable, it was invisible, and the terminal bearing became the only way to find anything.

The derivation missed three things: these surfaces are lit by sparse ceiling lamps, not uniform light; a false wall is usually first seen down a corridor at a very oblique angle; and the texture's own ±26-per-pixel grain is already enough to swallow an offset that small.

**15 blocks square is for the same reason**: a one-block patch of colour reads as lighting noise; a wall panel three cells wide does not.

### Why the exit is not a door

A door, a hole, or anything with an explicit function would be the one object in this place "prepared for the player" — and its entire effect rests on there being no such thing. The false wall is **the same trick performed a second time**: coming in, the floor decides it is no longer solid; going out, the wall does the same.

### Coming back from the sky

It is not just the teleport that has to be hidden: appearing 200 blocks up means the client must stream and light every chunk between the player and the ground, and that is **the one load in this whole feature the player must not see** — that moment should be the world appearing beneath them all at once, not being assembled in front of them.

**Fall damage must be waived throughout**: 200 blocks is enough to die several times over, and "the person who found the way out is killed by the way out" is the worst outcome this feature can produce.

**A timeout returns you on the ground**: six minutes without finding a false wall is the layer letting go, not the player getting out. The difference between the two endings is the entire reward for finding the exit.

### Why the layer's two lines are in that order

The first minute gives nothing, then a reason to run, then a direction to run in. The last two are now hung on **the same event** (the Bacteria landing) rather than on separate stopwatches — so re-timing either one can never make them drift apart again.

The first line carries no bearing and no distance because the bearing is the heartbeat's job; **a readout that simply says where the thing is does the listening for the player, and this layer is made of listening.**

**This readout never points wrong, even though lying is now permitted.** The rule that banned "occasionally, quietly inaccurate" has been rewritten to "a wrong value is allowed, at the price of a contradiction trace the player can catch afterwards" (see presentation hard rule 1 in the [World bible](world-bible.md)) - and this layer is precisely where that price cannot be paid: the player is being hunted and has no chance to open Records and reconcile anything, so the trace cannot land in time. The one forged reading is spent on the mineral probe, where the cost is forty wasted blocks rather than a life. Within 20 blocks it says nothing, because pointing at your own feet reads as a broken instrument.

### Why building is forbidden in the layer

This is the inverse of the private mirror's rule: the mirror is a copy of the real world, so mining there vanishes with the session and the ledger gives the blocks back. Here **the geometry is the event** — a pickaxe can open an exit anywhere, two blocks in a doorway permanently ends the chase, and a hole in the ceiling reveals the void, answering the one question this place exists to leave open.

**World decay and the signal bed must stop too**: decay is about the player's *own* world rotting, and this is not their world; besides, a mouldy yellow wall would destroy the one property this place depends on — every room looking exactly like every other room.

**The 6-chunk view distance is not a performance budget**: the 12 chunks given to other custom dimensions would let you see straight down a trunk corridor, making the eight-cell grid readable from any junction, and downgrading the place from "endless" to "large".

### The Bacteria's speed is measured, not derived

**The attribute is not the speed, and the relationship is quadratic.** `Mob.setSpeed` sets the forward input to the same value as the speed field, so acceleration carries the attribute twice, and terminal velocity is then set by ground friction and drag. Two samples pin `blocks/s ≈ 44.05 × attribute²`: 0.335 gives 4.94, 0.400 gives 7.05.

**0.335 is a hole that was really fallen into** — it was derived from a neighbouring vanilla value, and it is **slower than sprinting**; shipping that would have made the Bacteria scenery, and nothing but measurement could have found it.

It was lowered once from 0.377 (6.26 blocks/s), after the heartbeat was added: the heartbeat turns a chase that can only be lost on a stopwatch into something you can act on, and **the room to act is exactly how much faster than sprinting it is** — at 6.26, a straight corridor was a loss no matter how you ran it.

**Reaching the player is a capture rather than a kill**: it is the only way to get this presentation without simultaneously getting the death screen, the chat death broadcast, drops and a statistics entry — every one of which would have to be suppressed separately, and every one of which failing means somebody stuck in a dimension with no floor.

**It has no emissive**: this layer is lit everywhere, it is always perfectly visible, and what makes it frightening is that **you can see it clearly and it is still coming**.

### Every shape of the RECORDS backfill

- **The latch keys off a mainline event rather than anomaly tier 5**, because the former has an exact tick a GameTest can reach, while the latter comes out of pacing computation and differs per player.
- **An empty list is sent before the latch flips**, rather than letting the client hide it — the entire value of that list is that the player was never shown it, and it should not rest on one client-side condition.
- **Merged by world time rather than appended**: these events happened between the entries the player has been reading all along; appended they read as an appendix, interleaved they read as the ledger the terminal has been keeping.
- **`log.type.*` rather than `log.summary.*`**: the former is the terminal's voice ("it suddenly got darker nearby"), the latter is a specification description containing the words "player" and "server".
- **That one ordinary record line must be written**: the isolated store's own unread count has no consumer, and without it the page silently grows dozens of entries.
- **The 160 cap**: a player who finishes the game typically experiences 80+ anomalies, and the entire claim of this list is that not one was missed; a 32-entry sliding window would drop the quiet, deniable tier-1 entries first — exactly the half that makes it work.

### The two "do not spend the whole interval" rules

**Login applies a floor rather than rescheduling**: it used to assign directly, so one relog compressed an eight-minute wait to three.

**Candidates all refused by their preconditions defer only 30 seconds**: every entry can be vetoed by its own precondition (no wall to crack, no light to extinguish, no closed door, no safe route, no free slot in the layer), and a player in a boat, in a tunnel or on open water can veto half a tier's pool in one draw. **Paying ten to sixteen minutes for something that did not happen punishes the hardest environments the hardest.** 30 seconds is the same number as a HIM placement failure, for the same reason: one refusal says nothing about the next, because the player has moved, and where they stand is the entire input.

### Why no anomaly at all during combat

See [the World Interface design note](#three-causes-of-ability-sounds-disappearing). Beyond audio there is an independent reason: **combat is the one stretch of the whole flow where the world stops being ambiguous, and letting unrelated pressure through it means laying noise over the one unambiguous thing there is.**

### Why the crash sound is a variant pool

It replays every 5 ticks through the freeze and again for 3 seconds at capture resolution; a player might hear it a dozen times in one evening. **With one sample it gets recognised, and a recognised sound has already been filed as "a presentation".** The three collapse variants are three different ways of crashing, not randomisations of one. Level-matching by RMS rather than peak means whichever is drawn, the hit is equally loud.

There was once a fourth collapse variant, "clipping against the rails and grinding down"; every measurement passed, and it was cut after listening: it sounded like a signal being destroyed rather than a device locking up, and this cue is about the latter. **A technical contract test cannot catch "it sounds wrong"**, so any future clipping/square-wave variant must be auditioned first.

**The capture scream** is the only sound in the whole mod outside the "the device is breaking" register, which is why it plays in that one phase — the blackout that opens a pursuit has no scream, because that is the mod taking the player somewhere, and this is the mod telling them it caught someone. It was first aligned to `signal_alert`'s −12 LUFS tier, on the reasoning that it must be the loudest thing in this moment without being the loudest thing in the mod; at that level the corruption sound it sits over (−9.2 LUFS) was covering it. It now sits at −7.8 LUFS, above the collapse. **The only cue still above it is the unrendered layer's own scream (−5.0 LUFS), and that is a different ending.**

The source was delivered as FLAC with an `.ogg` extension — a file that passes every "is this Ogg" check and then plays nothing at runtime — which is why the contract test asserts a Vorbis stream.

### Why the blackout condition went from 2 ticks to 7×7 chunks + 8 ticks

It used to wait for the loading screen to disappear plus 2 ticks, and the loading screen is removed when vanilla considers the world entered — **far earlier than the surrounding terrain arrives**. So the blackout lifted a hundred milliseconds later and the player watched the world assemble itself, which is the exact thing this transition exists to prevent.

Chunks in hand is not the same as rendering finished, so stable ticks went from 2 to 8 as a backstop for meshing. The 200-tick hard timeout exists because **a brief pop-in is a blemish; a blackout you cannot get out of is a broken save.**

### Why only one pursuit runs server-wide

Two in parallel used to be allowed, and it quietly dismantled the claim the thing is making: a mirror means "the observer has been lifted out of shared reality", and once two people can each be "the only one being watched", being taken becomes something that happens **on a rota** rather than something that happens to you — and two people comparing timestamps afterwards puncture it in one sentence.

The cost is a slower queue, covered by "ordered by waiting time" and "waiting says something". The old implementation was first-come-first-served with a stable iteration order, so the same person could keep winning while another waited indefinitely.

### Tuning the Corrector's speed

The nominal value cannot be aligned directly against player speed: an entity has to turn between path nodes, slow into corners and re-path, while a player running in a straight line does not — so the nominal value corresponding to "matches a flat sprint in the open" has to be somewhat above sprinting itself.

The previous 0.32 × 1.42 was faster than any player movement, making **distance escape and line-of-sight escape operationally impossible**, and outlasting the timer the only real solution.

### Why the spawn ring is no longer restricted to behind the player

Something that only ever appears behind you can be reasoned about by turning around, and the old rule effectively implied "the direction you are facing is safe". The first attempted position is now equally likely to be in front or behind.

Near-to-far also has an implementation reason: the initial mirror window is the 5×5 chunks around the player's chunk, and a player standing on a chunk edge only guarantees a 32-block copy radius in the worst case, so the outer two rings may probe uncopied chunks. The inner two rings fall inside the guaranteed range from anywhere in a chunk and already provide 24 candidate columns.

### The capture penalty's soft floor

At or below 6 hearts, a capture stops deducting — **a repeatedly failing player is already losing the pursuit itself**, and grinding them down to 1 heart only makes the next pursuit and the finale harder for whoever needs help most. The +1 on success is not subject to the floor.

### The bystander's line

It turns the bystander's experience from "did he disconnect?" into "it took him", while keeping the loneliness boundary intact — unnamed, revealing nothing about the pursuit, and not sent to the target.

### Why night vision during a pursuit

A mirror may be entered from a cave or a night Overworld, and a black-and-white, low-colour-depth corruption filter over an image with no contrast to begin with erases the ground too — **the player should lose to the thing behind them, not to the dark.**

Renewed every 600 ticks rather than infinite: if any cleanup path ever misses it, the worst case is half a minute of residue.

### Why the return lands on the mirror's coordinates

The mirror is copied block-for-block at the same coordinates as the source world, so **the distance you ran is real, and erasing it means the escape did not happen.**

**Rotation must share the position's clock**: position comes from where they are in the mirror, and if rotation still came from the entry record the two would be a whole pursuit apart — a player who ran two hundred blocks and turned to watch their own escape route would wake up facing the direction they had when the warning fired. A blackout can cover a teleport; it cannot cover looking in two different directions on the way down and the way up.

### Why dispersion is not a third kind of "broken"

The first two languages both say "something has malfunctioned", and a beam weapon is neither: **it did not damage the image, it lit up the air the image passes through**, and a lens pointed at something overbright cannot keep its colours separate anyway.

Conversely, a player who has learned "tear bands = the rules are slipping" should never see tear bands because somebody fired a gun: the fear the corruption language carries is not the fear this attack wants.

**Why there are two tiers**: being aimed at is private, and that is what the lock presentation says; a beam crossing the arena happens in front of everyone — seven people seeing nothing while the eighth's screen explodes tells those seven the fight is happening somewhere else.

**Why it hangs on the arrival frame rather than while firing**: the first version wore it for as long as the weapon fired, which for the laser is a full two-second sweep — turning it from an impact into a state. A sustained screen treatment says "you are currently in some condition"; a flash says "something just happened to you". After two seconds the eye has adapted to the distortion anyway.

**The ordering is not a priority question**: dispersion must be asked before "is this aimed at you", or it quietly reverts to target-only — and **that regression is invisible in single-player testing**, because for the person being hit everything looks correct.

### Why the still variant is tuned separately

The two families serve completely different purposes: an impact lasts 18 ticks and nothing in those 18 ticks needs to be read; the corruption loading screen is a full page of **red text** to be read for half a minute. The moving family's `Desaturate` peaks at 0.70 — seventy per cent toward grey, plus a near-neutral `Tint` and heavy bloom — and on red text over black, that is not "broken", that is "colourless".

The mistrack band is carried by the loading screen's own slow sweep on the screen clock, so there is exactly one mistrack on the image rather than two running on separate clocks.

### The `SamplerInfo` trap

Every post pass declares it, but it is filled for only some chains, and when unfilled the whole block reads as 0 with no error at all — which is what the old `world_interface_edge.fsh` fell into (dividing by `OutSize.y` flattened the radius's horizontal term and painted the whole screen violet).

### The post-process arbiter used to be two contradictory rules

Pursuits pre-empted unconditionally and the World Interface refused to be pre-empted, so whichever ticked last won. `PostEffectArbiter` now holds a **claim** rather than a result.

---

## Terminal interface

Rules in [Terminal interface](terminal-ui.md).

### Why the status bar scales each cell separately

It used to scale the label and value as one unit, so a sixteen-character player name dragged "Holder" down with it, and past the scaling floor it simply overflowed into the neighbouring cell.

### Why the tool grid has no floating tooltip

The terminal draws inside a display area with a scissor and a pose transform, and a box that follows the mouse is exactly the kind of thing that gets clipped away or drawn at the wrong origin. Both answers (an unlocked tool's purpose, a locked one's condition) therefore go in the same place, using the colour language the cell already has.

### Backing plates were rolled back from how it looked on real hardware

The self test, the walkthrough brief line and the status bar had each filled an opaque rectangle at some point. Filling opaque colour on top of a sheet of glass plus a CRT layer reads as a sticker on the device rather than the device's own screen.

### Where the CRT layer's three constraints come from

1. **No tinting.** There used to be an alpha-7 flat green overlay; it tinted every glyph read through it and crushed contrast, and it is deleted and blocked by contract test. Darkening is neutral; tinting is not.
2. **No scrolling.** A moving high-contrast edge across a whole readable area is the most likely thing to draw a photosensitivity complaint. Rolling bands belong to the weather tool's sky-monitor "damage language", not to the resident shell.
3. **Pitch 3, not 2.** At this alpha, pitch 2 halves the pixel font's apparent brightness.

### Why the walkthrough is no longer "find the tab that is not dimmed"

The old approach dimmed the entire interface except the target tab and waited for the player to find the one bright tab — for a new player that is a puzzle, not teaching, and a one-line summary can only say what the page is called, not what it is for.

**Nothing at the top points any more**, because the tab strip does not accept clicks during the walkthrough. The target tab used to keep its own pixels, wear a breathing outline and carry a floating arrow — all three mean "click here". **Pointing at a control that does nothing is worse than not pointing**: the player clicks, nothing happens, and the first thing the terminal teaches them is that its own indications cannot be trusted.

**The tab strip is not a control during the walkthrough** because keeping "clicking a tab also advances" after the button was added would make three of four tabs dead and one secretly alive — worse than either extreme.

**The button is drawn in the status bar** rather than the explanation panel because the walkthrough already owns that strip, so the button costs nothing there while returning 20-odd pixels to the page below it. That matters most on Home: the second line of the explanation is about the recommended-tool cards it was covering, and **a tutorial that hides the thing it is explaining is explaining a blank space**.

**Step order became TOOLS → RECORDS → FILES → HOME** because the terminal opens on HOME: leading with HOME would make step one a same-page click — correct data, nothing on screen. Reordered, every step is a real transition, and the last one returns the player to HOME where the task card lives, so they see the bar fill and the reward arrive on the same screen.

**The button must not advance the task itself**: the moment the client has an "I finished playing" opening, it is a reward-farming entry point. The button saves finding the tab, not the click.

### Why the brief describes the current page rather than the one the arrow points at

The early version described the page the arrow pointed at, producing two contradictory lines on one screen: the status bar said "select the Tools tab" while the line below described Tools as if the player were already there — and the Home page actually in front of them had nobody explaining it.

Switching to the current page did not reduce coverage, it improved it: the terminal opens on Home and the order is Tools → Records → Files → Home, so the pages standing behind the four steps are Home, Tools, Records, Files — **each page described exactly once**.

Keeping it to one line is deliberate: this is a walkthrough the player sees once in their life, and four things are already competing for attention on that screen.

### What the completion hold is solving

The reward and the completion are the same server tick, and the snapshot that carries the message **is already talking about the next task**. So before the hold existed, the task card never drew a completed task at all: the bar the player was watching was replaced by an empty bar for a new objective they had not read, while the reward had already gone into the inventory, with nothing on screen accountable for it. The first task shows it worst — it completes inside the walkthrough, and the player clicked four tabs and suddenly has 6 bread.

The bar climbs from 0 after the hold because those two numbers belong to different objectives, and falling from full would draw a regression that never happened.

**No extra line of text on the card**: the card has already said the same thing four times (objective line n/n, bar filled, whole card in the completed palette, reward frame showing the item and count). Task names are a separate key group rather than reusing the objective line, because the objective line carries an instruction and a count and is too long for the strip above the hotbar; `taskName` writes the keys out case by case rather than assembling them from ids, because **assembled keys slip past contract tests**.

The notice stack is frozen while a screen is open, so the notice during the walkthrough only surfaces after the player closes the terminal — which is exactly why the card hold has to exist, not a reason the notice could replace it.

### Why the questionnaire's text no longer converges with tuning

Options used to resolve out of noise according to how accurately you were tuned, so the one you were not on could not be read. The effect itself works, but it asks the player to answer five questions **about themselves** with the words scrambled — **the one place in the whole mod where "cannot be read" is not atmosphere but the interface withholding the very thing it is asking about**.

The reasoning behind a few parameters:

- `LOCK_RADIUS` is 6 rather than the receiver tool's 2: 2 on an 84-pixel track is a window about 3.4 pixels wide, which is a fine-motor test rather than a narrative beat.
- `COMMIT_HOLD_TICKS`: it is a radio, and you hold it on the station. It also removes the entire class of mis-commits from sweeping across an option, and the confirm button the panel has no room for.
- Option spacing ≥ 29: the strength curve reaches zero at Δ=25, and two closer stations blur into one wide lobe — while "the needle rises three times" is the receiver's only teaching.
- The needle takes the maximum of two curves: two lock points at a ±24 coverage radius mathematically cannot cover 0–100, and moving the lock points cannot fix it.

Unanswered questions stay unanswered and are never given a default — that is precisely the "wrong but credible" the safety rules name.

### Two proportion contracts on the 3D model

An early version made the body nearly square, turning the landscape CRT into a portrait screen, and the thing in your hand immediately stopped looking like the thing that opens. Hence the 2:1 face and the 62% CRT width are both held by contract tests.

The atlas is 128 rather than 64 because at 64 the right-hand hardware column is only 11 pixels wide and the oscilloscope and compass are indistinguishable.

The flat icon staying in the inventory is deliberate: it carries two pieces of information (stage and unread lamp), and a tilted box in an item slot is harder to identify than a drawn icon.

### The handheld's size is pinned by the frame, not tuned

An early version let the device follow the vanilla item transform toward the near clip plane, scaling it to 2.5× while the brass frame punched out of all four edges of the screen and through the arms. That is why every position is now absolute camera-space and the device hangs off no arm.

`OPEN_SCALE`'s upper bound has two easily missed terms: **narrowing the FOV magnifies everything on screen** (a size computed against the un-narrowed field overshoots by about 15% when drawn), and **the constraint is on the wide edge** (the device is 2:1, so what pins it is the narrowest window's width, not the widest window's height).

### Why the device is drawn nearer than the hands

A first-person hand is compared against the world depth buffer — any surface nearer than it eats it, which is why hands sink into walls in vanilla. Vanilla gets away with it because the held item is small and in the corner; this device is large and centred, and the same occlusion erases **all** of it, which looking up at a ceiling is enough to do.

Nothing changes visually: apparent size and screen position are both "offset ÷ depth", so `REST_SCALE`, `OPEN_SCALE`, `REST_Y` and `OPEN_Y` were all scaled by the same factor as the depth was brought in.

### Why the hands cannot just use the device's coordinates

Arms are human-sized geometry and must stay at vanilla's own depth; pulled in with the device they become giant hands. So the two are at different depths, and under perspective **the same world offset lands at two different screen positions at two depths**. Handing the device's coordinates to the arms gives arms at a sensible distance but in the wrong place on screen — which reads exactly as **the hands are not holding the machine**.

They must also scale: the device nearly doubles between idle and open and the arms do not, so a fixed offset only looks right at one openness.

### Why the swing does not use vanilla's

Vanilla's swing rotates the arm about the shoulder, carrying the item with it; this device is held in both hands in front of the chest with **no free arm to rotate**, and the same rotation would fling a screen-filling CRT out of frame and back.

It was added because previously, mining, fighting and right-clicking chests produced a reaction in the world while the device did not move at all — it was the only held item in the game that does not move when you hit something, which reads as a machine that is not really being held.

The envelope uses vanilla's own `sin(sqrt(p) × π)` rather than a symmetric curve: a symmetric curve looks like stirring, not like impact.

### What each of the three equip suppressions fixes

1. **`shouldInstantlyReplaceVisibleItem`**: the terminal rewrites `custom_data` on every sync and `custom_model_data` when the lamp or stage changes, and neither component declares `ignoreSwapAnimation`. Left alone, an open terminal falls out of your hands and climbs back several times a second, and receiving one signal looks like the player swapped items.
2. **`itemUsed`**: for an ordinary item, slamming `mainHandHeight` to 0 and letting it spring back *is* the "you used it" feedback; but right-clicking the terminal is not a use, it is the start of raising it to the camera — two actions in the same frame in opposite directions, and the player sees the device drop before it comes up. **Nothing wants that counter driven, and leaving it driven means requiring every reader to know to ignore it.**
3. **The consumer-side latch**: any frame where the cached stack loses reference identity drops the target to 0 and the height dives and climbs back — a complete equip animation — and the terminal rewriting its own components is routine.

### Turn inertia cannot be skipped

Vanilla has a passage in `renderHandsWithItems` that adds back a tenth of "how much more the head turned than the hands". Taking over the whole method skips it, and it must be reproduced — otherwise the device is welded rigidly to the camera, and **it is the first thing a player notices when turning, because it is large and centred**.

### Why the guidance readout has to be resident

Following a bearing means walking, and walking means the screen is closed. But navigation packets were previously **only sent while the terminal was open**, so the player's loop became: open → read "south-west, 5 blocks" → close → walk → discover you drifted → open again. **The information was live all along and the player could only ever see a still of it.**

**It is a resident line rather than a notice**: the bearing changes with every step, and one notice per step would fill the stack within seconds and push out real events like task completions and lock warnings.

**Fading rather than sending a "stop" message**: one fewer message that has to stay in sync with the first is one fewer place they can fall out of sync.

### Why the unread lamp needs its own space

Every instrument in the hardware column already answers a question (where is north, how strong is the carrier, what does the receiver think), and layering a second meaning onto any of them makes that instrument ambiguous **exactly when it most needs to be trusted**.

**Why the test is "inside the viewport" rather than "on this page"**: RECORDS can hold 128 entries, and the file directory's six-row window cannot show seven files. A player sitting at the top of RECORDS has the new row in front of them, and asking them to click the tab they are already looking at makes no sense; a player scrolled deep into the log has not seen the new entry at the top, and the lamp is still doing its job — it says "there is something here you have not seen", not "you have not opened this page".

**The blink is a deliberate inversion.** It used to be a raised cosine that never went out (0.55–1.0, 0.42 Hz), on the reasoning that this lamp may be lit for a whole session and blinking would keep tugging at the eye. In practice it simply could not be read as *changing* on the panel, making it useful only to players who already knew to look at it.

**It is red, and always should have been**: the six flat inventory icons have always drawn this lamp red, and it was the panel and the 3D shell drawing amber — three surfaces of one lamp, one of them inconsistent.

**Both lamps must read the same function**: the one on the device and the one on the panel are the same sentence said twice, and the moment they compute separately they start contradicting each other.

---

## Background music

Rules in [Background music](audio.md).

### Why loading screens are silent

A loading screen is not a *scene*; it is the gap between two scenes, and putting menu music under the progress bar carries whichever track the menu happened to roll into wherever the player just went.

Silence before the notice is released is because that screen is still prose the player must read, and music competes with it. Waiting for `acknowledge()` would put the music a second and a half behind the player's decision, with the fade-in after that — landing the music behind the main menu it was supposed to underscore.

The quiet after beating the World Interface is the same idea: the ending track waits until the player actually steps into the portal.

### Why music must multiply by `peakVolume`

Every track `MusicDirector` can choose is one of this mod's own; vanilla's playlist is discarded wholesale, and the music channel's only volume control is the category gain. Without the multiply, "mod master volume" would not reach the layer of sound the player hears for longest.

Preview uses a self-recorded 3-second sweep rather than a pitched vanilla sample for three reasons: it is what this mod actually sounds like; 3 seconds is long enough to set a level by ear; and the previous 0.18-second cue was too short to hear a level difference in.

### Why the world-entry fade is a 10-tick linear ramp

Entering a world kills playing sound **twice**, and neither can be lengthened: `Minecraft#updateLevelInEngines` calls `soundManager.stop()` outright, and the Alpha resource-pack switch makes `SoundManager#apply` call `SoundEngine#reload()` and rebuild the whole engine.

Worse, the window available for the fade is neither fixed nor continuous — the main thread is blocked while save data is read, and not a single tick passes then. **A fade measured in seconds is guaranteed to lose**, cut off halfway and audibly indistinguishable from simply vanishing; that is why "steepen it to 1.5 seconds" was still not enough.

Linear rather than eased, because easing packs most of the attenuation into the first few ticks and leaves a long tail — compressed to 10 ticks that sounds like being cut short rather than fading out.

### Why the End gets its own track

The ordinary playlist's interval (4 minutes) is tuned for "there is a world running underneath and silence is the norm", whereas **the End is where this run stops being a survival game**: the player is there to place a terminal and to summon. Left on the ordinary playlist, a four-minute gap reads as the music having finished rather than as space.

`music_end` is an immediate track (0 delay + immediate replace) rather than a hand-off track: vanilla's `handleRespawn` calls `stopPlaying()` unconditionally on a dimension change, so there is no old track to take over at the moment you enter the End.

### Why the pursuit track waits for the mirror world

The test is not "the blackout started" but "the client has the mirror world". `ClientPacketListener#handleRespawn` contains an unconditional `getMusicManager().stopPlaying()` that runs on every dimension change — a track started **before** the teleport is cut off mid-fade-in, and with `music_pursuit`'s `minDelay` of 0 the manager immediately replays it from the first bar.

Starting **after** the teleport costs nothing: the blackout covers both the teleport and the loading screen, and the fade-in still happens underneath it.

### Where the start-of-track pop came from

Volume gain is only pushed into the audio engine inside `MusicManager`'s own fade (`updateCategoryVolume`), and the fade only runs **while a track is already playing**. So on the path "previous track hard-stopped → next track starts", the engine still holds the previous track's category volume: the new track's first tick plays at that volume for a full 50 ms before the next tick drags it back to the fade-in start.

### The rotation rule was upgraded from "no back-to-back"

It used to forbid only immediate repeats, on the reasoning that a full rotation makes the order at the end of each pass predictable — a valid trade for a pool drawn hundreds of times an hour, and **not valid for music**: ten tracks in one session are not a distribution the player computes, they are a list they will notice not having finished.

**When a pass ends has to be counted, not guessed.** Inferring "the pool is exhausted" from N consecutive already-heard draws is a probability judgement: about 0.7% false positives on a seven-track pool, about one in thirty passes on a ten-track pool — and every false positive skips a track the player has not heard, which is the only reason this mechanism exists.

With counting, exhausting the redraws no longer means "the pass is over": it degrades that one draw into a repeat while the pass stands. Measured on a ten-track pool, the old implementation dropped one or two tracks in 29 passes out of a thousand; the new one did not in three thousand.

### Why the interval went from 10 minutes to 4

Vanilla's pacing is designed for one playlist shared by a whole game. Ten tracks averaging 2 m 15 s on a 10-minute interval leaves music audible less than a fifth of the time and takes two hours to complete one pass — a player could finish a whole stretch of the mainline without hearing half of it.

After the playlist grew from seven tracks to ten, a pass takes about an hour and no longer fits inside one ordinary session; **that is the cost of expanding the playlist, not a problem with the interval** — shortening the interval only completes passes faster, and what is heard within each pass is still a track not yet heard.

Pressing further would start to contradict the work: silence is the norm here, and music starting tells the player this moment is authored and therefore safe. The gaps do not sound like a disconnection because the signal bed is underneath them.

### Why the summon shares form 1's track

The boss-fight folder's root originally held another track; it was removed: **the arrival and the form that lands out of that arrival are the same thing, and swapping tracks in between tells the player they are two things.** Sharing one also means `SUMMONING` and `PHASE_1` return the same `Music`, so the hand-off happens only on entry and the ritual's last beat is not a swap point at all.

The summon used to be silent until the ritual's `MUSIC_HANDOVER` beat, on the reasoning that the thirteen-second arrival is already carried by the rising cue and ten anchor chains, and a track underneath would flatten both. The actual effect was that **the biggest entrance in the whole mod had no music**, and the track arrived after the thing had already landed.

### Why the unrendered bed is AMBIENT at 0.2

**The opposite of the signal bed**: the bed is "transmission" and is deliberately built to survive the silence anomaly; this ambience **is** the world the player is standing in, and an anomaly that takes the ambience away should rightly take it too.

Volume dropped from 1.0 to 0.2: this bed is the layer's only resident source and plays for six minutes straight, which is a reason to sit **low** in the mix, not high — it had been ingested at a one-shot cue's loudness. The test is **that it no longer covers footsteps and the heartbeat**.

### Why SFX cannot use the BGM ratio rule

Both of those assets are quiet to begin with. Applying the "bake a percentage gain" instinct only makes them less audible — the ambience bed scaled to 25% is −43 LUFS, which is nothing. **SFX must be measured and aligned to repository reference values.**

---

## Architecture and lifecycle

Rules in [Architecture](architecture.md).

### Station Zero must fetch chunks before sampling

`Level.getHeight()` loads nothing — with the chunk unloaded it **does not error**, it returns the world bottom. Read across a whole footprint that way, a bedrock column scores as "perfectly flat ground", and the station gets built there.

**The layout must be a pure function of the station centre**: construction is batched and resumes from a persisted cursor after a restart, so the plan has to be byte-for-byte reproducible — no world reads, no random source, and all weathering variation from a hash of local coordinates.

**The order carries meaning**: the floor is written first so a player joining mid-build always has somewhere to stand; the envelope is cleared two layers above the roof so no tree trunk is left hanging; beds and doors are written last, in pairs.

**The one copper lamp in the old version was placed in its default (unpowered) state and did not emit**; that regression is held by a unit test.

### Why fragment leads became "listening" rather than "searching"

The old start-of-game pre-allocation searched 256 chunks from Station Zero with `findNearestMapStructure`: the player received four coordinates before leaving the door, some of them four thousand blocks away, and each of them **the corner of a starting chunk**.

Recording the true centre of the real `StructureStart` (with a true Y) is the whole point of reading real structures: navigation points at the thing itself, and the HUD's elevation becomes meaningful.

**The pool is a preference, not a requirement**: where a region simply has none of the types (open ocean, superflat), the nearest structure of any kind carries the fragment once patience runs out — the four hidden files must always be obtainable.

**The 320-block test should be loosened rather than tightened**: the marked position is the corner of a starting chunk rather than its centre, and tightening it locks the full journal away.

### Where `PrivateDimensions.isPrivate` came from

Seventeen environment systems each checked `PursuitDimensions.isMirror`. Every one was correct while the mirror was the only case, and every one **missed the second private dimension** (the unrendered layer) once it existed — with **no failing test and no log line**. That is precisely the most dangerous class of regression. `MultiplayerIsolationContractTest` guards it from the source.

### `forceSolidOn()` is not optional

Without it, a collisionless block counts as non-solid and both occlusion and lighting leak — the player can see through the false wall or light spills out, while this exit's entire effect rests on it looking exactly like an ordinary wall.

### Why config entries must be boxed types

Gson fills a missing primitive with `0` / `false`. Shipping `double` / `boolean` directly hands **every player whose config predates the field** a muted, switched-off presentation — no error, no log, just "this mod seems to have no sound".

### The world test for the view-distance lock and Alpha downgrade

Both change things that belong to the **player** rather than the **save** (render distance in `options.txt`, global resource-pack selection, the window title), so they must first answer "is this world mine?".

The test uses `ClientPlayNetworking.canSend(TerminalOpenPayload.TYPE)` and adds no protocol. **But `canSend` reads `Minecraft#getConnection()`, which is null until `Minecraft#player` exists**, and the player is created inside `handleLogin` — so throughout `ClientPlayConnectionEvents.INIT` it can only answer false.

Once the downgrade has happened, the Alpha packs and the "Minecraft 1.0.0" title deliberately persist on the main menu and into later worlds; what changed is only that the identity **can now be earned nowhere but in this mod's own world**.

### Why the drop rejection is injected at `LivingEntity#drop`

That is the layer `Inventory#dropAll` and `EntityEquipment#dropAll` (i.e. death drops) actually reach. Injecting at the higher-level `Player` misses the equipment path.

### Not every online player has a terminal record

After the finale resolves, `ZeroStationService.issueTerminalIfNeeded` deliberately does not write the grant ledger, so a newly joining player is one this mod holds **no state at all** for. Every per-player writer must tolerate that before writing, or it is a null dereference or a record appearing out of nowhere.

### What each of the relay's four boundaries prevents

- **Never attributed**: a record line saying who it came from is a chat channel, and what this mod builds on loneliness does not survive a chat channel.
- **Never actionable**: a relay line you could walk to would turn "standing next to another player" into a way to farm investigation progress.
- **Never about pursuits**: the private correction layer is the one place the player is genuinely alone, and the existing "a nearby terminal's signal cannot be resolved right now" already says the maximum anyone is allowed to know.
- **Consent both ways**: unanswered never sends — **someone who was never asked has never consented to anything**.

`SELF_ECHO` returns a line the receiver recorded themselves long ago as if it came from elsewhere: it passes nothing between two terminals, so there is no boundary to cross and nothing to leak.

### Notify the blasts, derive the beats

Beats the client can compute from the action envelope (number, start tick, duration, target) stay client-derived — sending a "shake now" packet only adds a less reliable clock. Blasts are not in that class; see [the World Interface note](#why-camera-shake-moved-to-server-notification).

Per-source throttling is simultaneously **protection for the client mixer**: every one of those blast points makes sound at the same time, and one unthrottled laser sweep stacks up dozens of overlapping samples and shake impulses.
