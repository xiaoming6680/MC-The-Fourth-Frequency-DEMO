# The Fourth Frequency world bible

The current narrative boundaries of `1.0.0-rc.1`. Numbers are owned by [The World Interface finale](world-interface.md) and [Testing and acceptance](testing.md).

## The core premise

The world is not suddenly invaded by an outside monster. It has always been holding up a comprehensible surface out of blocks, menus, sounds and rules the player knows — and "the fourth frequency" is the transmission noise that surface cannot quite suppress.

The terminal is not a wrapper around a quest list. It is the interface through which the world explains itself on limited bandwidth. It learns to record the player first and to answer second; as the answers get more accurate, the world finds it harder to keep pretending nothing is wrong.

## Facts that must not move

- Station Zero and the terminal are the way in, and **the terminal is the player's only stable interpretive tool**.
- The survival mainline still rests on vanilla actions: wood, iron, the Nether, blaze rods, Eyes of Ender, the stronghold and the End.
- The four damaged files come from investigating **vanilla world structures**, not from four extra facilities generated for the purpose.
- Anomalies are not a bag of random easter eggs; they walk from environmental error into the interface and rule layers across tiers 1–5.
- The Corrector's five personal forms are evidence of the World Interface learning *how to use a body* — **not a low-health-bar version of the final enemy**.
- Real pursuits happen in a private correction layer isolated from other players. **The loneliness comes from the observer being lifted out of shared reality**, not from other players pretending not to see.
- The finale is a collective act of surrendering interpretation: every participant hands over their own bound terminal.

## The experience curve

### 1. A believable older version

On first world entry the client completes an Alpha-style downgrade; the loading screen and version strings pull the familiar game back into a not-entirely-real "Minecraft 1.0.0".

**It should first make you doubt the version and your memory, not announce a horror theme.**

### 2. Repeatable error

Anomalies begin as small deviations in sound, light, blocks and records. The server decides only *what happened*; the client decides *how the targeted player perceives it*.

Multiplayer may share discoveries but **need not share the same hallucination** — texture decay is presented per **player**: a newcomer should not walk into someone else's tier 5, and the world should not heal itself when that person logs off.

The terminal's voice stays short, calm, like an instrument working to keep noise down. **It may be incomplete, but it must never draw the conclusion for the player as an omniscient narrator.**

### 3. The world starts recording the player

The six tools gradually turn from scanners into testimony. Three real Eye of Ender throws matter most: the World Interface does not hand over coordinates out of nowhere — it is **acknowledging that the player has already proved a direction**.

The weather tool is the earliest and most easily missed notch on this curve: it is there from the start and normally just reports weather and time, but it also monitors four channels of the sky — so when the sky starts being rewritten it is the first thing to know, and the first thing to start losing signal.

The hidden files in `FILES` connect scattered phenomena into one body/interface mapping while leaving room to interpret. The player should gradually realise that "anomaly" may just be the moment the world can no longer describe itself in vanilla vocabulary.

### 4. The private correction layer

Ordinary anomalies do demonstrate the next Corrector form's rule safely, but **that is not a precondition** — a form may enter a real pursuit having never been demonstrated.

What survivors get is not an explanation but a single line: "The anomalous signal is gone...", followed in red by "Next time will not be the same...". **The terminal does not say what that was.** Naming a form's rule would hand over what the next form is about to break, and would turn Records into a walkthrough — the one thing that page must never become.

The mirror is not another world to mine. It copies the reality near the player and keeps unfolding along the escape route, but containers, loot, other players and real progress are all stripped out.

> The world is not real, but the damage is.

The five forms are not simply "faster" — they are five ways of **interpreting**:

| Form | What it reads as what |
|---|---|
| Soundseeker | Repeated action as position |
| Router | A habitual route as intent |
| Interceptor | Movement trend as a future exit |
| Boundary-crosser | Stops accepting walls and ceilings as boundaries |
| Interface Corrector | Begins forging coordinates, text and direction; the heartbeat only says where it is and how close — **never where the exit is** |

Rushing the mainline is never punished with back-to-back pursuits, and the world holds only the next correction opportunity. But **no form has to be seen before it may arrive** — the first encounter can be the real pursuit, with the explanation arriving afterwards.

**Die and not even that line is written.** A captured player returns to a silent Records page; the line is filed only on a real escape, and it is never backfilled the next time that form appears. **Being told nothing is what dying costs.**

### 5. Handing over the terminals together

The End altar hugs the native main island because **the finale has to happen in the world the player knows**. The 10 stability anchors read as local promises that the world is still refusing to collapse.

Handing terminals over one by one carries the meaning: **no single client can speak for all observers**. The roster is not "everyone online at the time" but the people who really put a terminal into the core — taking part is a step you take, not a name you are called. It can be withdrawn before commit; if nobody presses Summon within three minutes, every terminal goes back and nothing happened.

> ### Server operators: the twelfth Eye is irreversible
>
> **The moment the twelfth Eye lands in the frame, the vanilla End ending is replaced wholesale.** That is not a side effect; it is the definition of this finale — the World Interface takes over exactly the position "the Ender Dragon" occupied.
>
> On placement, `EndBossArenaService.suppressVanillaFight` marks the vanilla fight as already finished — and vanilla generates the exit portal, the dragon egg and the End gateways **only** inside `EndDragonFight#setDragonKilled`. With that path bypassed, none of the three ever appear and the dragon cannot be revived with end crystals.
>
> Singleplayer narrative is unaffected (the return is carried by this mod's own exit and poem), but **on a shared survival server this is a permanent, world-wide progression change triggered by one person's click**: no gateway means no outer End islands, and therefore no elytra, no shulker boxes and no End cities.
>
> If the server still wants vanilla End progression, **kill the vanilla dragon and take a gateway first, then let the team assemble the twelfth Eye**.

The 20 gateway slots still exist in the protocol and state (sediment particles point at them) but **no longer place any blocks**; bedrock frames left by early saves are removed when the arena is prepared.

## "The Fourth Frequency · World Interface"

It is not a body in the traditional sense. The three forms are the same interface **progressively giving up legibility** under pressure; forms only move forward and never retreat on healing.

The stability anchors are not a simple "towers first" either: they heal and rebuild the target's shell, making it harder to hurt and slower to act, while projecting a zone at each pillar's foot that reduces interface damage and refuses terrain erosion. Breaking one exposes the interface and stops a share of the healing — but makes it act more often and puts out a patch of safe ground. **This choice should make players argue, not follow the one guide.**

The eight actions are all about *how the world interprets the player*:

- **Laser, dragon-breath bolt and sky lance** turn space and ballistics into protocol echo: all three can be dodged by moving, at the cost of first reading who it is looking at.
- **Grab-and-throw** temporarily takes away authorship of your position, but must telegraph, and the whole carry must be visible — **being lifted and being teleported are two entirely different fears**. The tendril flings you clear of the body's silhouette before letting go; how you survive the landing is your answer, not an ending it wrote for you.
- **Tendril lash** is the interface's only close-range vocabulary: it does not interpret the player, it just pushes them away.
- **Weapon confiscation and gaze sweep** temporarily take over the item narrative. Things may **genuinely vanish, with nothing said about where they went** — the player is not owed a mid-fight promise that nothing will really be lost. Recoverability is untouched: confiscated gear enters the recovery ledger, disconnect, death, restart and crash all return it idempotently, and everything comes back in full when the session ends. **The fear is loss of control, not actual loss.**
- **Forced eviction** treats the connection itself as an attack, but never targets an integrated-server host, and can never let one client's presentation end a LAN world.

The World Interface may leave permanent scars on the End, but they must be **bounded, explicable** and must avoid vanilla key structures and living anchors' stability zones. It cannot destroy an anchor, and it cannot use "meta" as licence to modify the player's machine without limit.

## The two endings

### Success: cutting the interface before the explanation collapses

The altar opens a return exit, and vanilla's End Poem channel carries this run's poem, credits and post-credits; the poem remembers whether they preserved all, some or none of the anchors. Only after the poem completes and the player really returns to the Overworld does view distance unlock from 6/12/16 (12 elsewhere) to 16.

**This is not a return to before it happened**: the battlefield scars remain and the ending lock acknowledges this world is finished; a successful save is called *sealed*. From resolution onward, ordinary anomalies, gap pressure, decay and pursuits go permanently silent — **no forged epilogue is appended to the story**.

### Failure: the interface crosses the client boundary

When the collapse finishes first, the World Interface enters escape resolution. **The client is not closed**: after the fixed presentation, vanilla's return path sends the player to the Overworld, the whole local world renders as missing textures, and the bottom of the screen says the run is over. If the loser is a LAN host, the server likewise keeps running and guests see unchanged blocks and rules.

**Failure is not a destructive operation.** The horror lives in client presentation and a lossless marker; `level.dat`, region files and player data are never rewritten. A failed save is called *corrupted*. Losers can quit from the pause menu; on restart the Alpha presentation persists until F8 recovery completes.

## The ending lock, F8 and replay

The ending lock seals only **the world this particular run happened in**: a local save is sealed through the save list and open flow, a remote server by address, and every other save and every other server is untouched.

The only case that still disables all three title-screen entries is **a desktop meta transaction that has not been rolled back** — the desktop really is still modified then, and the buttons say so and tell you to press F8. **Settings, accessibility and Quit always exist**; that is the boundary of safe exit and recovery.

F8 opens the matching success/failure confirmation only when a persistent ending lock exists. On the next launch, the exactly-matching local save is isolated by a `.thefourthfrequency-corrupted` marker; **a new story must start in a new world**.

`-Dthefourthfrequency.safeMode=true` is a recovery back door for an interrupted presentation, **not a narrative shortcut**.

## Writing boundaries

- Terminal copy prefers short sentences: **actionable information first, incomplete explanation second**.
- Important controls must telegraph clearly.
- The horror comes from familiar systems being reinterpreted — **not from silent save deletion, unrecoverable dispossession or desktop changes that cannot be undone**.
- High contrast, flicker and peak volume are bounded by the first-run flow and configuration; master volume can be turned down on the very first page.
- **Server-authoritative multiplayer data is never polluted by a client hallucination.**

### `FILES` carries no voice of the terminal

Every document on that page was **recovered** — left by a previous holder, or one before them, written in first person with their own judgement, tone and blind spots. The terminal only stores and presents them: **no byline, no endorsement, and no rewriting into a clause summary**.

Its own voice lives elsewhere: the objective on HOME, the event lines on RECORDS, tool readouts and notices. So a rule read in a file is always "somebody walked it this way" rather than "the system specifies this" — and the doubt built over a whole run is not refuted just before the finale by a perfect guide written by the terminal itself.

A predecessor's account may be incomplete and may give a direction without a coefficient, **but every number in it must match what actually happens**.

**"The previous holder" may now really be you.** After one run completes on a machine, the next run's `FILES` gains a first-person fragment written by that run's player. It obeys every existing rule of the page plus one: **every number it quotes must be true** (which day they lasted to, how many of the ten anchors came down, how they answered the questionnaire). Unanswered questions are reported as unanswered — **it never invents a line for its author**. It does not count toward the four investigation files.

### Records is an evidence board, not a walkthrough

`FILES` carries no voice of the terminal; Records does — which makes Records the place where the terminal is most likely to slide into omniscient narration. It is neither a log nor a walkthrough: it lays out what the terminal noticed and marks which entries appeared together and which ones disagree. **The player draws the conclusion.**

Two guardrails:

- **State co-occurrence, never causation.** "These three appeared together" is allowed; "it came because you were mining" is not.
- **Never mark a correlation the player cannot follow.** If it is marked it must open; if a contradiction is marked, both sides must be reachable. Otherwise the evidence board decays into decoration. This is the same principle as "the unread badge never promises something it cannot produce".

## Meta boundaries

**What is drawn inside the game window is presentation; what leaves the game window is Meta.** A full-screen fake crash rendered in-game is bound only by the presentation rules; a real system window follows this section.

Meta may leave the game window, and may read local machine information. This mod is open source, so anyone can check what it reads and what it writes — "the player cannot verify you are not doing something else" is no longer a reason. Crossing the line must satisfy all of:

| Condition | Meaning |
|---|---|
| **Only touch what it owns** | Sandboxed paths, registered artifacts, a program allowlist; snapshot before changing |
| **It has to come back** | `restore()` reverts everything; both the master switch and F8 recovery trigger it |
| **Failure must degrade** | primary → fallback → in-game presentation. **Never left half-applied.** |
| **Out-of-game remedies need an exit** | Fake antivirus alerts, fake disk failures and anything else that sends the player off to scan, delete or reinstall are beyond the reach of `restore()`. Their recovery is social — telling people beforehand or immediately afterwards — and only holds for an audience the author can actually talk to |

**What limits Meta is not a list, it is a count.** Each device is used once. The horror of crossing a line is the crossing itself; the second time it is just an effect.

"Already used" is recorded per **machine**, not per save: starting a new world does not let the desktop happen for the "first" time twice. F8's full wrap-up clears it along with the ending lock, so the next story really does start from the beginning — the same logic as "a new story must start from a new world".

F8 also restores the desktop cleanly: the sandbox directory is removed entirely, **but its contents move into the mod's own config**, to be read back next run by the cross-run fragment in `FILES`. Nothing is left on the desktop, and the previous holder's trace still exists.

## Hard rules for presentation

### 1. Actionable information may lie, but the lie must leave a trace

Players walk home on "about N minutes until dark". An anomaly **may hand them a wrong but equally credible number** — this is the instrument at its most powerful: a device that lies to you accurately beats a device that is visibly broken by an order of magnitude. A broken instrument stops being used; a lying one gets used right up until it costs something.

The price is a **contradiction trace**. The moment it lies, the true value must be written into Records, or an event line must record that the readout and the log disagree. The player has to be able to catch it afterwards on their own.

A wrong value with no trace is not design, it is a bug. What this rule has always guarded against is "the player thinks the game is broken" — the trace is the real answer to that, and a blanket ban only sidestepped it.

**There is one such reading per save, and it is spent on the mineral probe.** Its own page carries a line promising it reports only ore it genuinely hears, and one lie makes that sentence false; the cost is forty wasted blocks, not a life. **The weather tool never lies**: its blank "cannot resolve the reading" is the best picture this mod has of an instrument losing the sky, and trading it for one scare would spend it. Readouts during a pursuit never lie either — the player has no chance to reconcile anything, so the trace cannot land in time.

### 2. A forged error must be literally true

The "no fake system errors" rule is gone. Software-failure vocabulary is the one register this mod has always lacked, and it is very often **exactly accurate**: chunks that no longer resolve light and materials really are `Failed to load chunk`; a player lifted out of shared reality really is `Disconnected`.

So the rule is not "when may we fake a crash" but **only use an error when it is literally true**. An error that does not match what actually happened, staged purely to startle, is not made — that is a cheap jump scare rather than the same event in a different dictionary.

An instrument's own fault copy is a separate matter: **devices like the weather tool report failure in their own language**, not in the vocabulary of software exceptions. The horror is an instrument losing the sky, not a program erroring out.

### 3. Exit paths are never permanently taken over, and flicker never exceeds 3 Hz

Page tabs, the back button and the close hint are never covered in any ordinary phase; any visible state change holds for at least 7 ticks.

**The one exception** is the first-boot walkthrough on a new save. It may refuse to let the player out before it finishes, but only while satisfying four **testable** conditions:

| Condition | Meaning |
|---|---|
| **One-off** | A one-way server latch that never replays under any circumstance; old saves are backfilled as "has already used a terminal" |
| **Definite end** | Four steps, each advanced by the player's own click; the client can neither advance for the player nor settle the task because it thinks it finished playing |
| **Safe release** | Any damage taken unlocks it immediately with progress kept. The terminal does not pause the world, so "cannot be closed" while being hit means "cannot fight back" |
| **Server override** | A forced close always beats the walkthrough lock |

While locked, the close hint must read "cannot exit right now". **A screen that says "press Esc to exit" while Esc does nothing lands squarely on rule 1.** No lock that is not one-off, not terminated, or not releasable is permitted — and these conditions are asserted by unit tests, not left to discipline.

### 4. Every aggressive act must evidence itself

It must supply its own proof of "this was designed" **within the same session**, rather than being excused by a first-run notice or a difficulty option. The excuse has to land there and then, not on a page read thirty hours earlier.

| Act | The evidence it carries |
|---|---|
| Forged error | It resolves itself within a bounded time, into a screen that is plainly diegetic |
| Items genuinely taken | They genuinely come back |
| An instrument lying | The true value is sitting in Records |
| An undemonstrated pursuit | The terminal explains it once it is over |

This is also why flicker, volume, saves and the desktop **cannot** be relaxed by the same logic: they cannot evidence themselves within a session, and their damage outlives the game being closed.

### 5. The evidence must arrive later than the event

Rule 4 requires the evidence to exist; this one requires it **not to be there at the time**.

What is seen as it happens is a scare. What the player digs up hours later is dread. So a sequence leaves its trace and then waits — do not push the evidence into the player's face, let them find it. **The horror is not the moment it crossed the line, it is the moment they realise it crossed long ago.**
