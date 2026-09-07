# The Fourth Frequency · 第四频段

**English** · [中文](README.md)

A Fabric survival-horror narrative mod for Minecraft 1.21.11.

You wake at Station Zero — the only one there is — holding an old terminal that gives you real survival information. Nothing is added to frighten you. Instead the blocks, menus, sounds and rules you already know gradually stop meaning what they used to. The terminal is your one stable instrument of explanation, and it is subject to the same thing.

> Current build: **1.0.0-rc.6**, with period-style calibration, a camera move through the terminal into the menu, first-boot instruments and a full-screen “败” corruption wall, louder foreground effects and separately trimmed ambience. Use the same build on clients and servers; this remains a release candidate.

MOD effects can now stand out from vanilla sounds without raising the game master volume. The terminal slider keeps quiet notch feedback; terminal and environmental beds use their original assets. See [Audio](docs/en/audio.md) for the mixing rules.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.19.3 or a compatible newer release |
| Fabric API | 0.141.4+1.21.11 |
| Java | 21 |

Both client and server need this mod and Fabric API. Licence: [All Rights Reserved](LICENSE).

## Install

1. Build `thefourthfrequency-1.0.0-rc.6.jar` using the steps below. Published attachments are listed on [Releases](https://github.com/xiaoming6680/MC-The-Fourth-Frequency-DEMO/releases).
2. Drop it, together with the matching Fabric API, into the `mods` folder of the client and the server.
3. Launch Fabric 1.21.11 on Java 21 and **create a new world**, or join a server.

Build from source (`gradlew.bat` on Windows, `./gradlew` on Linux/macOS; JDK 21 is the only prerequisite, Gradle fetches the rest):

```powershell
.\gradlew.bat clean build --no-daemon
```

Artefacts land in `build/libs/`. The last step of `build` can copy the JAR into a local Minecraft instance; this is **off by default**. Set `tffDeployDir=<path>` in the user-wide `~/.gradle/gradle.properties` to enable it, or pass `-PtffDeployDir=<path>` for one invocation. More commands in [Testing and acceptance](docs/en/testing.md).

## What you play

### Mainline and the terminal

On first world entry Station Zero is built on a fixed per-tick budget near the spawn point: the site is chosen for flat, non-flooded ground with no block entities in the footprint, then foundation, floor, walls and roof go up from the bottom. The station lights itself, spawns no hostiles indoors, and holds no loot. The world spawn point is moved to its centre at the same time.

Every player receives exactly one valid personal terminal. **It opens itself once, on the join that hands it to you**; after that, right-click it to open a private screen that does not pause the game and never writes into chat. It is a sealed two-handed instrument — cast-iron body, brass trim, a large CRT on the left, oscilloscope and compass on the right. Opening it moves the camera up to the screen; the device itself never folds open.

| Page | Contents |
|---|---|
| `HOME` | Current server-authoritative objective, tool suggestion, latest record |
| `TOOLS` | Shelter, mineral, portal, weather, navigation, stronghold — unlocked along the mainline |
| `RECORDS` | Story, milestones, file investigation and pursuit warnings. Ordinary anomalies are withheld until the first eye-of-ender bearing is recorded; the terminal then releases the whole run's anomaly log at once and keeps logging from then on |
| `FILES` | Four damaged files plus a complete journal that unlocks only once all four are read, and a fragment left by the previous holder if this machine carries a finished run |

The mainline follows vanilla survival: learn the terminal → wood → iron → the Nether → a fortress → blaze rods → return → eyes of ender → three real throws → stronghold → the End → defeat the World Interface. Task rewards are granted the moment a task completes; there is nothing to claim.

On a new save, after the power-on self test and before the four-tab walkthrough, the terminal takes a **user profile**: five multiple-choice questions answered on the receiver slider rather than on buttons. Each option sits at a frequency; the question is legible from the start and the options are not, so the player has to tune onto an answer to read it, then hold the lock for about 1.75 seconds (35 ticks) to commit. After the fifth, the LCD replies with nothing but *user preferences recorded*.

The profile is one-shot. The damage failsafe, a forced close and a dropped connection all seal it where it stands; unanswered questions stay unanswered rather than being filled with defaults, and the terminal says the profile is incomplete. The answers only change how the terminal presents things and which of several equally intense things arrives - never how many, how often, how hard or how long. It is asked in the first minute and can never be revisited, so it is not allowed to become a hidden difficulty setting.

See [Terminal interface and handheld form](docs/en/terminal-ui.md).

### Anomalies and personal pursuits

Eighteen anomalies advance through five sliding stages, moving from environmental error into the interface and rule layers. Two of them are low-intensity *sustained* anomalies that hang on for minutes rather than firing for seconds. The mainline only raises the ceiling; the actual stage rises at most one level at a time.

Anomalies happen in the Overworld and the Nether only. The Nether runs a shorter interval — the trip the mainline sends you on is a bounded errand, and at the overworld cadence you could finish the whole thing without the world once misbehaving around you. The End fires nothing at all: it is the finale's own stage. Entering it freezes the wait, and returning resumes it from where it stopped, so you neither land to an immediate anomaly nor lose a whole stretch for free.

Stage five's **unrendered layer** is the only anomaly that moves you out of the world you are standing in: the floor gives way, the screen goes black for a second, and you land in a procedurally generated endless interior plane. A minute in, something is placed beyond your view distance - slightly faster than your sprint, slower than your sprint-jump - and from then on it does exactly one thing. The way out is a wall that is very slightly the wrong colour and does not stop you, and going through it puts you back in the sky of your own world.

The Corrector has five personal forms: Sound-Seeker, Router, Interceptor, Trespasser and Interface Corrector. An anomaly may demonstrate a form's rule safely, but that is not a precondition — the first encounter can be the real pursuit, with the explanation arriving afterwards. A real pursuit moves the player into a **private mirror** of their current dimension: terrain is streamed in around the player's chunk while other players, entities, items and the refund ledger stay fully isolated. Only one pursuit runs server-wide at a time — being taken is something that happens to you, not something your shift comes up for.

> The world is not real. The damage is.

See [Anomalies, terminal forms and personal pursuits](docs/en/anomalies-and-pursuits.md).

### The End finale: World Interface

The moment the last eye of ender drops into the stronghold frame, the finale is ready — there is no personal prerequisite of any kind. The fight happens on the **native End main island**; only an 11×11 resonance altar and ten stability anchors are placed, both flush with the terrain.

> **Operators, read this one first.** The twelfth eye permanently replaces the vanilla End ending: no exit portal, no dragon egg and no End gateway are generated, and the dragon cannot be resurrected. Without a gateway the outer End islands are unreachable, so elytra, shulker boxes and End cities are gone for everyone on the server. Single-player storytelling is unaffected — the return is carried by this mod's own exit — but a shared survival server should kill the vanilla dragon and take its gateway first, and only then assemble the twelfth eye.

**The roster is whoever hands a terminal over**, up to 8: you join by holding your own bound terminal and pushing it into the resonance core yourself, and other people logging in or out has nothing to do with this ritual. The first terminal into the core starts a 3-minute window; you can withdraw at any point inside it, and if nobody presses **Summon** within the three minutes every terminal goes back the way it came and nothing happened. Summon can only be pressed by somebody who has already handed one over, and pressing it commits atomically. The boss has `600 × (1 + 0.5 × (roster - 1))` maximum health across three forms that only ever advance, against a 12000-tick (10-minute) collapse timer. Stability anchors heal it and slow its attacks while projecting zones that protect both players and terrain — breaking them is a genuine trade, not a "towers first" checklist.

**The End rains now, thunders now and then, and still has no clouds.** It is pure client presentation: the save's weather record is shared by every dimension, so writing it would make the Overworld rain forever, and not a byte of it is touched. Vanilla never considers the End to be raining either — `isRaining()` stays false there — so the rain can be seen and heard while changing no rule in the game. **Winning clears it within ten seconds**: that sky came with the thing in it, and once the thing is gone the sky does not have to stay that way.

See [The World Interface finale](docs/en/world-interface.md).

### Endings, F8 and saves

Success and failure both return through the vanilla End-poem path, replacing only that run's poem, credits and post-credits text, and both write a local ending lock: from then on the title screen disables Singleplayer, Multiplayer and Realms, while Options and Quit always stay available.

Failure replaces the desktop wallpaper and opens a Notepad file whose text tells the player to press `F8` to undo every local change. `F8` is not an always-on switch — it opens a reset confirmation only when an ending has been recorded. After the restart, that save is isolated by a lossless marker (success reads "sealed", failure reads "corrupted"); `level.dat`, region files and player data are never rewritten. Replaying means a new world.

If a sequence is interrupted, launch once with `-Dthefourthfrequency.safeMode=true` for a safe recovery.

## Safety boundaries

- Horror comes from familiar systems being reinterpreted — **never** from silent save deletion, unrecoverable dispossession or desktop changes that cannot be undone.
- An instrument may hand you a wrong but plausible readout, but **every lie leaves a trace in Records that you can catch afterwards**.
- A forged error must be literally true; things may genuinely be taken from you, but they **always come back**.
- Page tabs, back, close, the pause menu and safe recovery are never permanently taken over by a sequence.
- Flicker stays under 3 Hz; high contrast and peak volume are bounded by the first-run safety notice and by config.
- Client-side hallucination never contaminates server-authoritative state, and a LAN host's local sequence never affects their guests.

The full account is in the [World bible](docs/en/world-bible.md).

## Config and debugging

First launch writes `config/thefourthfrequency.json`. The mod volume can also be set straight from the first page of the opening screen, without editing the file.

| Key | Default | Purpose |
|---|---:|---|
| `meta.enabled` | `true` | Allow the fixed allow-list of local meta sequences |
| `meta.peakVolume` | `0.8` | Mod master volume: every sound and every track this mod plays is multiplied by it, validated to 0–1; this is what the opening screen's slider writes |
| `pacing.developerAcceleration` | `false` | Shorten development waits without skipping real interaction |
| `clientState.alphaDowngradeComplete` | `false` | Whether the one-off Alpha downgrade has played |
| `clientState.viewDistanceUnlocked` | `false` | Whether the successful return permanently unlocked view distance |
| `clientState.debugHudGroups` | absent means all four on | Bitmask of the debug HUD groups to draw; read only while personal debug is enabled |

An operator running `/tff debug true` on themselves can press the rebindable `M` to open the test bench: the left column switches between the anomaly, file and survival-milestone lists, and the right column writes the whole state once and groups every action beneath it. Enabling debug also puts a persistent HUD in the top left (next-anomaly countdown, candidate pool, tier and cooldowns, mainline and files, chase and finale) and outlines this mod's own entities. The rebindable `N` hides or shows the HUD; which groups it draws is chosen with the checkboxes in the panel. Every button is re-checked server-side against authorisation, action id, target and current context. `/tff debug false` revokes it.

## Documentation

| Document | Purpose |
|---|---|
| [Documentation index](docs/README.md) | Bilingual index and the maintenance rules |
| [World bible](docs/en/world-bible.md) | Narrative facts and safety boundaries that must not be rewritten |
| [Architecture](docs/en/architecture.md) | Authoritative data flow, persistence, protocol, budgets |
| [Background music](docs/en/audio.md) | Situation table, fade seams, track rotation |
| [Terminal interface and handheld form](docs/en/terminal-ui.md) | Coordinate space, layout, palette, animation, 3D model |
| [Anomalies, terminal forms and personal pursuits](docs/en/anomalies-and-pursuits.md) | Five anomaly stages, three pursuit forms, the private mirror |
| [The World Interface finale](docs/en/world-interface.md) | Ritual, state machine, eight actions, endings, F8 |
| [Art and asset pipeline](docs/en/art-pipeline.md) | Deterministic generators, UV and emissive contracts |
| [Testing and acceptance](docs/en/testing.md) | Gradle entry points, layered coverage, current evidence |
| [Manual acceptance checklist](docs/en/acceptance.md) | Item-by-item pre-release verification |
| [Repository maintenance](docs/en/maintenance.md) | Directory layout, doc sync rules, release process |
| [Design notes](docs/en/design-notes.md) | The trade-off behind each number, rejected alternatives and fixed bugs |
