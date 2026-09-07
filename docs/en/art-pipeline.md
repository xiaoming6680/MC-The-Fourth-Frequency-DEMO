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
| `export_world_interface_model.py` | Re-packs the UV islands of `world_interface.bbmodel` and exports the runtime geometry JSON and `layout.txt` |
| `paint_world_interface_textures.py` | Paints the three forms' base, emissive and impact sheets from the bbmodel's materials and islands, plus the blackened base for the failure ending |
| `build_world_interface_model.py` | One-shot scaffold: generates a first bbmodel on the runtime skeleton; re-running overwrites hand edits |
| `generate_world_interface_textures.py` | Block textures used by the encounter |
| `prepare_world_interface_wallpaper.py` | The failure-ending wallpaper, exact 16:10 |
| `generate_world_interface_audio.py` | The World Interface sound library (44.1 kHz), validated against `docs/art/world_interface/audio_manifest.json` |
| `prepare_stability_anchor_textures.py` | Stability anchor entity sheets and sparse emissive mask |
| `prepare_rework_body_art.py` | Rework Body's three stage bases and two emissive sheets |
| `prepare_watcher_textures.py` | The Watcher's base, eye mask and numbered UV export |
| `prepare_him_textures.py` | HIM's 64×64 skin and eyes-only emissive mask |
| `prepare_entity_textures.py` | The general pipeline from high-resolution material masters to entity textures |
| `prepare_anomaly_art.py` | Anomaly GUI assets |
| `generate_terminal_3d_assets.py` | The handheld terminal: six UV atlases, six item models, one Blockbench source |
| `compose_flat_terminal_panels.py` | Terminal panel backdrops, all stages from one canonical control-bay geometry |
| `pixelize_terminal_icons.py` · `generate_soundscape.py` | Post-processing for terminal icons and audio |
| `generate_resonance_core_textures.py` | The pixel texture set for the End resonance core |
| `generate_analog_filter_textures.py` | The noise plate the analog filter falls back to where a shader cannot reach |
| `generate_soundscape.py` · `audio_materials.py` | Terminal and entity sound sets |
| `generate_soundscape.py` | The signal bed: carrier, static, hiss, dead air and cues |
| `generate_soundscape.py` | Analogue-horror cues for the first load and for hangs (at least 3 variants each) |
| `generate_unrendered_textures.py` · `generate_soundscape.py` | The unrendered layer's four surfaces (solid and false from one recipe, `LIGHTNESS_SHIFT` apart) and the bacteria's one-second heartbeat loop |
| `import_music.py` | Imports BGM from lossless masters: measures and matches to -24 LUFS, then attenuates, baking the result into Ogg Vorbis |

## World Interface

The boss's geometry is edited in **Blockbench**; the runtime no longer bakes cuboids from Java generators. The editing source is `docs/art/world_interface/world_interface.bbmodel` (Modded Entity format, box UV), and three steps carry it into the game:

| Step | Command | Output |
|---|---|---|
| Export geometry | `python tools/export_world_interface_model.py` | Re-packs the UV islands and writes them back into the bbmodel; emits `assets/thefourthfrequency/models/entity/world_interface.json` (bones and cubes in Java model space) and `docs/art/world_interface/layout.txt` |
| Paint sheets | `python tools/paint_world_interface_textures.py` | Base, emissive and impact sheets for all three forms, the blackened base for the failure ending, and the `world_interface_uv_template.png` guide |
| Round-trip check | `gradlew exportWorldInterfaceBbmodel` | Writes the model the client actually bakes back out as `world_interface_runtime.bbmodel`, for checking the conversion chain |

`WorldInterfaceGeometry` reads the JSON on the client and bakes a `LayerDefinition`; `WorldInterfaceModel` only owns which bones the rig poses, which layer a form reveals, and which bones carry light.

### Bone contract

- **Bones the server poses must be kept as they are.** `hover`, `storm_body`, `interface_kernel`, `weapon`, the three `{center,left,right}_head_mount → _neck_a → _neck_b → _skull → _jaw` chains and the ten `tendril_N → _mid → _tip` chains must carry the pivot and bind rotation of `WorldInterfaceRig.bindPose()`; `WorldInterfaceGeometryContractTest` compares them bone by bone and axis by axis (tolerance 0.002). Moving one of these pivots moves the hit box off the part a player can see.
- The renderer resolves `shell_base`, `phase_2_accretion`, `phase_3_accretion`, `kernel_glow`, each head's `_eye_0` and each limb's `_glow` by name; the emissive pass submits only those bones, so anything meant to glow has to hang under them.
- Cubes may carry their own rotation in Blockbench; the exporter wraps each one in a synthetic bone named `<bone>/<cube>`, because a `ModelPart` cube cannot rotate. Every rotated cube therefore costs a part of its own; parts drawn per form are bounded by `WorldInterfaceModel.MAX_VISIBLE_PARTS`, which the contract test counts against the JSON.
- The coordinate convention is Blockbench's own Modded Entity export: a Blockbench pivot relative to its parent becomes `(-dx, -dy, dz)` (root-level bones also drop the 24-unit lift), rotations become `(-rx, -ry, rz)`, and a cube's `from..to` becomes `addBox(pivot.x - to.x, pivot.y - to.y, from.z - pivot.z, size)`. `tools/world_interface_model.py` and `WorldInterfaceBbmodelExport` are the two directions of that one convention.

### Materials and UV

Every cube is named `material.label` (`bone.center_cranium`, `endstone.chunk_3`). Cubes of one material and one size share a box-UV island, and the exporter shelf-packs the islands on whole UV units. The canvas is **512×256**; base sheets are **2048×1024** (4× density), emissive and impact overlays **1024×512** (2×, same canvas). The reference is still the vanilla ender dragon: near-black hide carrying almost no hue, blotching rather than linework, and the only saturated thing on the sheet is what glows.

| Material | Used by | Treatment |
|---|---|---|
| `swallowed` | swallowed ground, core mass | Hide blotching plus sparse mineral flecks |
| `endstone` | embedded end-stone chunks and strata | Palest rock, finer blotch cells |
| `obsidian` | dark strata, conduits | Darkest rock, occasional cold fracture veins |
| `plating` | plates, kernel frame, weapon | Blotching only, a shade darker than the mass |
| `bone` / `tooth` | crania, jaws, vertebrae, ribs / teeth | Palest material on the sheet, one top highlight, an occasional hairline crack on large faces, no outline |
| `horn` | horns, crests, tentacle barbs | Growth rings along the length |
| `root` | roots | Blotch cells stretched three-to-one along the strand |
| `flesh` | neck cores, tentacle links | Blotching with short darker striations |
| `socket` | eye sockets, nasal cavities | The deepest recess |
| `eye` / `core` / `glow` | apertures / kernel lattice, irises / tentacle nodes | Cool shell on the base sheet; the emissive sheet makes them burn |

Tumbled parts (`plating`, `endstone`, `obsidian`, `swallowed`, `glow`, `horn`) are painted isotropically; everything else gets directional face shading. Every face gets the one-texel ×0.74 ambient-occlusion border.

### Emissive contract

Only islands of the `eye`, `core`, `glow` and `socket` materials may carry glow, and `validate()` fails the script if a lit pixel lands anywhere else. Apertures glow on their north face only and brighten toward the centre; sockets keep a shallow pool along the lower rim; the kernel is a lattice; tentacle nodes get one band on each of the four upright faces and never on up or down. The impact overlay covers every island and is the only pass that still submits the whole model a second time.

### Palettes

The forms separate by value and by how much cold violet has crept in. The hide stays charcoal at every stage, end stone fades from grey-yellow to cold grey and the bone goes cold with it; escalation is carried by the apertures, the kernel and the emissive bands in `WorldInterfacePalette`. The failure ending swaps in a blackened base over the same geometry; impact overlays are magenta `(255, 42, 88)` on every form.

### Preview

`tools/preview/bbmodel_viewer.html` renders any bbmodel with Blockbench semantics in three.js (six views, form switch, a player-scale reference, `?texture=` to override the sheet). Serve the repository root statically, e.g. `python -m http.server 8765`, then open `tools/preview/bbmodel_viewer.html?model=../../docs/art/world_interface/world_interface.bbmodel&form=2`.

## The Watcher

`WatcherModel.createBodyLayer()` uses a 128-unit virtual texture canvas, and the two 256×256 runtime PNGs sample that layout at exactly 2× density. `prepare_watcher_textures.py` mirrors every `texOffs` and cuboid dimension, exports the numbered guide, and fails if the emissive mask leaves the eye UV.

- `watcher.png`: 256×256 RGBA, every pixel alpha 255.
- `watcher_emissive.png`: 256×256 RGBA, 33 non-transparent pixels (0.05%), maximum alpha 118.
- Non-transparent emissive pixels are restricted to the **north (front) faces** of the two sclera cuboids and the four iris annulus cuboids, and all of them fall inside the frozen window x ∈ [160, 240), y < 16. The pupil UV is transparent in the emissive image and near-black in the base image. Earlier revisions outlined every face of each eye cuboid, which read in the dark as a glowing rectangle and gave the box away; the sclera now carries only a shallow U along its lower rim, and the iris bars stop short of one another so the aperture's four corners fall back to the sclera instead of closing into a lit box.
- The base texture is lit by the texture rather than by the world: each face is multiplied by up 1.42, north 1.00, west/east 0.74, south 0.58, down 0.40, with a 1.15 → 0.62 top-to-bottom falloff on the four upright faces and a one-texel ×0.55 ambient-occlusion border on any face larger than 2×2. Without that split every cuboid collapsed into one flat silhouette and the modelled spine, scapulae and orbit were invisible in game.
- The render layer scales the emissive alpha by the client-side gaze ramp in `WatcherRenderer`, so an unobserved Watcher has no lit eye at all and reads as a faceless silhouette.

## HIM

The final atlas is 256×256 with the standard 64-unit humanoid UV layout. A sunken pale face, stained clothing and small eye highlights replace the former flat skin. The source artwork was repacked through Blockbench MCP; the final atlas and mask are docs/art/entities/textures/him_atlas.png and him_atlas_emissive.png.

prepare_him_textures.py validates opaque body faces and eye-only emission before installing the files unchanged. The current mask contains five nontransparent pixels. Animation keeps the limbs rigid while adding a head-first turn and a prolonged listening tilt.

## Rework Body

There are three stages, with three opaque 256×256 base maps and sparse emissive maps for stages 2 and 3. The logical UV canvas remains 128×128. Obsolete stage 4/5 runtime maps have been removed.

prepare_rework_body_art.py samples the boards in docs/art/rework_body/. See the [editable entity projects](../art/entities/README.md) for the detailed geometry and runtime motion studies.

## Frozen assets

These are **not regenerated by any pipeline**, and replacing them is a separate decision:

- `textures/gui/anomaly/eye_item.png`
- `textures/gui/anomaly/eye_window.png`
- `tools/assets/anomaly/eye_master.png`

Manifest files under `docs/art/` are contract inputs in the same way:

| File | Read by |
|---|---|
| `world_interface/audio_manifest.json` | `WorldInterfaceAudioManifestTest` · `WorldInterfaceSummonTimelineTest` · `generate_world_interface_audio.py` |
| `world_interface/layout.txt` | `ResourceContractTest` · `export_world_interface_model.py` |
| `world_interface/world_interface.bbmodel` | `WorldInterfaceGeometryContractTest` · `export_world_interface_model.py` · `paint_world_interface_textures.py` (editable Blockbench source) |
| `analog_filter/filter_manifest.json` | `PostFilterContractTest` · `generate_analog_filter_textures.py` |
| `terminal/old_terminal_shell.bbmodel` | `generate_terminal_3d_assets.py` (Blockbench-editable source) |

## Audio

Audio ships as 44.1 kHz stereo Ogg Vorbis (q4). BGM playback level is baked into the files by `tools/import_music.py` at import time rather than written into `sounds.json`: every master is measured with `loudnorm` and shifted by a pure linear gain onto -24 LUFS, then scaled by an attenuation ratio (0.8 by default). Both halves are overridable per source folder: the three encounter tracks take -17 LUFS at a ratio of 1.0, and the unrendered layer's single track takes -23 LUFS at 1.0. Both are relative to the lossless master's measured value, so re-importing does not compound them. Scheduling, the reasoning behind each tier, and mixing rules are in [Background music](audio.md).

Hang cues are variant pools, not single files: `alpha_corruption_collapse` and `alpha_corruption_warning` have at least 3 each, and variants of one event are level-matched by RMS rather than by peak. `ResourceContractTest` asserts the minimum count, the absence of duplicate entries, that every entry is a real Ogg, and that each is over 16 KB. **A technical contract cannot stop "it sounds wrong"** — audition before adding a variant.
