# Art and asset pipeline

The single description of **how every runtime texture and sound is produced**: generators, UV contracts, emissive contracts and frozen assets.

The terminal's appearance layer — layout and palette — is in [Terminal interface](terminal-ui.md), music scheduling in [Background music](audio.md), and trade-offs in [Design notes](design-notes.md).

## Three hard rules

1. **Every runtime asset is generated deterministically by a script.** Nothing is hand-painted, and no reference image is scaled and pasted in. Re-running with the same input produces byte-identical output.
2. **Reference art never enters the game.** The concept sheets and material boards under `docs/art/**` are evidence for humans; the game never loads them.
3. **Textures carry their own lighting.** A cuboid lit only by the world collapses into one flat silhouette, so every face gets a directional factor plus a one-texel ambient-occlusion border.

## Environment

Run from the repository root with a Python that has Pillow:

```powershell
python tools/<script>.py
```

Paths under `docs/art/**` are referenced directly by tests and scripts (`ResourceContractTest`, `PostFilterContractTest`, `WorldInterfaceAudioManifestTest`, `WorldInterfaceSummonTimelineTest`, `tools/*.py`). **Renaming or moving them turns `unitTest` red.**

## The generators

| Script | Output |
|---|---|
| `world_interface_uv.py` | The single source of truth for the World Interface UV layout; `--emit-java` also writes the model-side offset table |
| `prepare_world_interface_textures.py` | Base, emissive and impact sheets for all three forms, plus the blackened base for the failure ending |
| `generate_world_interface_textures.py` | Block textures used by the encounter |
| `prepare_world_interface_wallpaper.py` | The failure-ending wallpaper, exact 16:10 |
| `generate_world_interface_audio.py` | The World Interface sound library (44.1 kHz), validated against `docs/art/world_interface/audio_manifest.json` |
| `prepare_stability_anchor_textures.py` | Stability anchor entity sheets and sparse emissive mask |
| `prepare_rework_body_art.py` | Rework Body's five stage bases and two emissive sheets |
| `prepare_watcher_textures.py` | The Watcher's base, eye mask and numbered UV export |
| `prepare_him_textures.py` | HIM's 64×64 skin and eyes-only emissive mask |
| `prepare_entity_textures.py` | The general pipeline from high-resolution material masters to entity textures |
| `prepare_anomaly_art.py` | Anomaly GUI assets |
| `generate_terminal_3d_assets.py` | The handheld terminal: six UV atlases, six item models, one Blockbench source |
| `compose_flat_terminal_panels.py` | Terminal panel backdrops, all stages from one canonical control-bay geometry |
| `pixelize_terminal_icons.py` · `remaster_terminal_audio.py` | Post-processing for terminal icons and audio |
| `generate_resonance_core_textures.py` | The pixel texture set for the End resonance core |
| `generate_analog_filter_textures.py` | The noise plate the analog filter falls back to where a shader cannot reach |
| `generate_terminal_audio.py` · `generate_entity_audio.py` | Terminal and entity sound sets |
| `generate_signal_bed_audio.py` | The signal bed: carrier, static, hiss, dead air and cues |
| `generate_alpha_corruption_audio.py` | Analogue-horror cues for the first load and for hangs (at least 3 variants each) |
| `generate_unrendered_textures.py` · `generate_unrendered_audio.py` | The unrendered layer's four surfaces (solid and false from one recipe, `LIGHTNESS_SHIFT` apart) and the bacteria's one-second heartbeat loop |
| `import_music.py` | Imports BGM from lossless masters: measures and matches to -24 LUFS, then attenuates, baking the result into Ogg Vorbis |

## World Interface

`WorldInterfaceModel.createLayer()` declares a **256×128** canvas and the PNGs are **1024×512**, so density is **4×** — one texel to a quarter block even at the third form's 16× scale. The canvas is wide and short because that is the shape the packer actually fills: the islands shelf out to 89 rows, and a square canvas would have been two thirds dead sheet paid for in every PNG.

The model bakes several hundred cuboids from procedural generators, so a hand-written island table would go stale the moment a generator parameter moved. Instead `world_interface_uv.py` **re-derives every cuboid the model will build** — mirroring the generators and their scatter hash exactly — buckets them by size, packs one island per bucket, and emits the model's offset table. Both the model and the painter read that one module.

- **69 islands, 59.4% canvas utilisation.**
- **All three forms share one layout.** The parts are the same materials at every stage; what changes between forms is the palette, and the three separate PNGs already carry that.
- **Size bucketing.** A class whose members span 6× in size (mass runs from 5 units across to nearly 28) gets several islands, and each cuboid picks one by the same scalar the generator bucketed on. Sharing one island across the class would leave the small members sampling a corner of the large members' material.
- **Buckets must be contiguous.** The script refuses to run if a bucket band is empty, because the Java table would then be shorter than its bounds array and the model would index past the end.

Regenerate both halves together — an offset table that disagrees with the painted sheet points parts at rectangles nothing was drawn into:

```bash
python tools/world_interface_uv.py --emit-java && python tools/prepare_world_interface_textures.py
```

### Materials and relief

The reference is the vanilla ender dragon: near-black hide carrying almost no hue, soft blotching rather than linework, and one lit eye as the only saturated thing on the sheet. An earlier pass went the other way — block seams on a grid, brushed metal on the plating, outlines around the bone, strong baked lighting — and at boss scale that turned the body into a technical drawing. **Materials separate by value and by the shape of their blotching, never by drawn lines.**

| Material | Used by | Treatment |
|---|---|---|
| `swallowed` | mass, debris | Hide blotching plus sparse single-texel mineral flecks, barely off the base value — all that is left of the terrain it ate |
| `plating` | plating, halo, shutters, weapon, core socket | Blotching only, a shade darker than the mass |
| `bone` | cranium, brow, maw, horns, vertebrae, ribs, jaws, teeth | Palest material on the sheet, one soft top highlight, no outline |
| `root` | roots | Blotch cells stretched three-to-one along the strand, so the fibre runs its length |
| `flesh` | tentacle links | Blotching with occasional short darker striations |
| `socket` | skull eye sockets | Darkest under the brow, opening toward the bottom of the orbit |
| `core` | pupils, slit, inner halo, tentacle nodes | Cool shell only on the base sheet; the emissive sheet is what makes it burn |
| `sclera` | eyeballs | Pale, faint diagonal veins |

Relief is applied on top of the material. The dragon bakes almost no directional value at all, but going that flat does not survive here — the dragon is a dozen large parts and this is several hundred small ones, which merge into one silhouette without some split. So the range is compressed hard rather than removed:

- **Directional parts** (mass, ribs, roots, skulls, eyes, jaws, weapon) get a per-face factor — up 1.16, north 1.00, west/east 0.90, south 0.82, down 0.74 — and a 1.05 → 0.92 top-to-bottom falloff on the four upright faces.
- **Isotropic parts** (plating, debris, halo, tentacle nodes) get a flat 0.94 instead. The model tumbles these on all three axes, and a baked "top is bright" is worse than no shading at all once the box is upside down.
- **Every face** gets a one-texel ×0.74 ambient-occlusion border. This is the cheapest thing that separates a hundred touching boxes into a hundred readable boxes, and for the isotropic parts it is the only relief they get.

### Emissive contract

Only these islands may carry glow, and `validate()` fails the build if a lit pixel lands anywhere else: `eye_{1,2,3}_pupil`, `eye_3_slit`, `socket`, `tendril_glow`, `ring_inner`.

- Each emissive sheet is **2,220 non-transparent pixels — 0.42%** of the canvas, confined to the packed island rows.
- **Front-facing parts glow on their north face only.** Parts seen from every angle (sockets, tentacle nodes, inner halo) glow on the four upright faces but never on up/down, because a box lit on all six sides just advertises that it is a box.
- Sockets get a shallow pool along the lower rim rather than a filled rectangle, so the skulls read as sockets with something behind them instead of cubes with lamps in them.
- The inner halo band is the only ring on an emissive island. At the third form the halo is large enough that lighting all of it would drown the core it exists to frame.

`WorldInterfaceRenderer` submits **only the bones that carry glow** — the core, the halo, the six skull sockets and the active tentacle nodes — rather than the whole model a second time. At 0.42% coverage, walking the third form's several hundred parts meant nearly every vertex went through a translucent pass to draw nothing and paid for the sort on the way. The impact overlay is the exception: it has to reach plating and debris the glow never touches, so the damage flash still costs a full second submission for a few ticks.

### Palettes

The forms separate by value and by how much cold violet has crept into the grey — not by turning progressively more purple. The hide stays charcoal at every stage; escalation is carried by the core and by the emissive palette bands in `WorldInterfacePalette`, which is where a player actually reads phase from.

| Form | Reading | Hide value |
|---|---|---|
| 1 — Nascent | Still mostly the world it ate. Stone grey, bone not yet gone dark | `(52, 50, 55)` |
| 2 — Grown | The grey has gone cold and the bone is going with it | `(44, 41, 50)` |
| 3 — Terminal | Hide black enough that the core is the only thing on it with a colour | `(34, 31, 40)` |
| Black | Failure ending: same geometry, all the light gone out of it | `(15, 14, 17)` |

Impact overlays are magenta `(255, 42, 88)` on every form.

## The Watcher

`WatcherModel.createBodyLayer()` uses a 128-unit virtual texture canvas, and the two 256×256 runtime PNGs sample that layout at exactly 2× density. `prepare_watcher_textures.py` mirrors every `texOffs` and cuboid dimension, exports the numbered guide, and fails if the emissive mask leaves the eye UV.

- `watcher.png`: 256×256 RGBA, every pixel alpha 255.
- `watcher_emissive.png`: 256×256 RGBA, 33 non-transparent pixels (0.05%), maximum alpha 118.
- Non-transparent emissive pixels are restricted to the **north (front) faces** of the two sclera cuboids and the four iris annulus cuboids, and all of them fall inside the frozen window x ∈ [160, 240), y < 16. The pupil UV is transparent in the emissive image and near-black in the base image. Earlier revisions outlined every face of each eye cuboid, which read in the dark as a glowing rectangle and gave the box away; the sclera now carries only a shallow U along its lower rim, and the iris bars stop short of one another so the aperture's four corners fall back to the sclera instead of closing into a lit box.
- The base texture is lit by the texture rather than by the world: each face is multiplied by up 1.42, north 1.00, west/east 0.74, south 0.58, down 0.40, with a 1.15 → 0.62 top-to-bottom falloff on the four upright faces and a one-texel ×0.55 ambient-occlusion border on any face larger than 2×2. Without that split every cuboid collapsed into one flat silhouette and the modelled spine, scapulae and orbit were invisible in game.
- The render layer scales the emissive alpha by the client-side gaze ramp in `WatcherRenderer`, so an unobserved Watcher has no lit eye at all and reads as a faceless silhouette.

## HIM

Standard 64×64 player layout, so vanilla's humanoid mesh maps onto it unchanged and `HimModel` needs no custom geometry at all. **The silhouette being exactly Steve's is the point**: inside the fifth of a second the figure is on screen, anything with its own proportions resolves as a custom mob rather than as a person.

The obvious shortcut is not available. The classic look is the default player skin with the eyes painted out — but that default skin is Mojang's own asset, and the fan-made variants of it are third-party work. Neither can be shipped in a mod. So this skin is generated from scratch in the same deterministic way as every other entity in the project, and reads as the legend without being anybody's file.

| Part | UV | Materials |
|---|---|---|
| head | `(0, 0)` 8×8×8 | hair on the top two rows of the upright faces, skin below |
| body | `(16, 16)` 8×12×4 | shirt |
| arms | `(40, 16)` / `(32, 48)` 4×12×4 | sleeve down to row 8, skin below |
| legs | `(0, 16)` / `(16, 48)` 4×12×4 | trousers down to row 10, shoe below |

**Emissive is eight pixels**: two 2×2 blank rectangles on the head's north face and nothing else; `validate()` fails the build if a lit pixel lands anywhere outside them. They are **empty on purpose** — a pupil makes it a character looking at you; two lit slots with nothing in them make it something that has no eyes and is facing you anyway, which is the read the legend actually has.

The palette is deliberately muted. At the distance this thing is placed, saturated clothing reads as a player in a costume, and a costume invites a second look — which is exactly what must not happen, because a second look is going to find nothing there.

Unlike the Watcher's eye, this one is not gated on being looked at. That figure ramps its glow in over a two-second stare; this one is given a fifth of a second in total, and a reveal spread over that window would never finish arriving.

## Rework Body

The two source boards under `docs/art/rework_body/` are production references, not runtime textures. `prepare_rework_body_art.py` samples the material board into the model's semantic UV islands, adds stage-specific bruising, fascia, necrosis, bone and facial details, and writes the seven 256×256 runtime PNGs.

- Five base maps are RGB/opaque 256×256 PNGs.
- Stage 4 and 5 emissive maps are sparse RGBA 256×256 PNGs with a transparent background.
- The model has a 128×128 virtual UV canvas, so each model texel maps to 2×2 resource pixels.
- `rework_body_uv_guide.png` records the semantic island envelopes used by the finishing script.

## Frozen assets

These are **not regenerated by any pipeline**, and replacing them is a separate decision:

- `textures/gui/anomaly/eye_item.png`
- `textures/gui/anomaly/eye_window.png`
- `tools/assets/anomaly/eye_master.png`

Manifest files under `docs/art/` are contract inputs in the same way:

| File | Read by |
|---|---|
| `world_interface/audio_manifest.json` | `WorldInterfaceAudioManifestTest` · `WorldInterfaceSummonTimelineTest` · `generate_world_interface_audio.py` |
| `world_interface/layout.txt` | `ResourceContractTest` · `world_interface_uv.py` |
| `analog_filter/filter_manifest.json` | `PostFilterContractTest` · `generate_analog_filter_textures.py` |
| `terminal/old_terminal_shell.bbmodel` | `generate_terminal_3d_assets.py` (Blockbench-editable source) |

## Audio

Audio ships as 44.1 kHz stereo Ogg Vorbis (q4). BGM playback level is baked into the files by `tools/import_music.py` at import time rather than written into `sounds.json`: every master is measured with `loudnorm` and shifted by a pure linear gain onto -24 LUFS, then scaled by an attenuation ratio (0.8 by default). Both halves are overridable per source folder: the three encounter tracks take -17 LUFS at a ratio of 1.0, and the unrendered layer's single track takes -23 LUFS at 1.0. Both are relative to the lossless master's measured value, so re-importing does not compound them. Scheduling, the reasoning behind each tier, and mixing rules are in [Background music](audio.md).

Hang cues are variant pools, not single files: `alpha_corruption_collapse` and `alpha_corruption_warning` have at least 3 each, and variants of one event are level-matched by RMS rather than by peak. `ResourceContractTest` asserts the minimum count, the absence of duplicate entries, that every entry is a real Ogg, and that each is over 16 KB. **A technical contract cannot stop "it sounds wrong"** — audition before adding a variant.
