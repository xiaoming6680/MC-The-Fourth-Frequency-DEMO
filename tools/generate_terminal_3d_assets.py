"""Builds the handheld terminal's 3D shell: six UV atlases, six item models, one Blockbench source.

The device is one rigid, heavy machined block. It has no lid, no hinge and no moving part of any
kind: the geometry is byte-identical across all six forms and only the atlas underneath it changes.
That is a product rule, not an implementation detail - see docs/zh/terminal-ui.md.

The front face is laid out to match what the player already reads on the open screen, so the item
in hand and the panel on screen are recognisably the same machine:

  textures/gui/terminal/panel_*.png                              - bezel, screen greens, hardware column
  docs/art/terminal/terminal_six_forms_full_controls_concept.png - the authored reference

  left  : one large recessed CRT
  right : oscilloscope, round compass, tuning slider, two-line LCD, close hint
  right of the oscilloscope: the small red unread lamp, dark on even forms and lit on odd ones

The six forms differ only in materials:

  0 / 1  green working stage     2 / 3  cyan active stage     4 / 5  red anomalous stage

An odd form is its even neighbour with the unread lamp lit, and nothing else.

Run from the repository root:  python tools/generate_terminal_3d_assets.py
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "src/main/resources/assets/thefourthfrequency"
TEXTURE_DIR = ASSETS / "textures/item"
MODEL_DIR = ASSETS / "models/item"
BLOCKBENCH_OUT = ROOT / "docs/art/terminal/old_terminal_shell.bbmodel"

# 128 rather than 64. The front face has to carry a wide CRT and a column of five instruments at
# the same proportions the open panel uses (512x256), and at 64 the hardware column came out about
# eleven pixels wide - not enough to tell an oscilloscope from a compass.
ATLAS = 128
FORMS = 6

# --- palette --------------------------------------------------------------------------------
# Sampled from the two authored references and kept as literals so a regenerated atlas is
# byte-stable across machines.
IRON = (36, 34, 24)
IRON_DARK = (23, 22, 19)
IRON_DEEP = (12, 13, 8)
IRON_WORN = (52, 49, 38)
BRASS = (107, 86, 62)
BRASS_HI = (141, 120, 88)
BRASS_LOW = (74, 60, 40)
SCREW = (156, 138, 102)
GLASS_EDGE = (14, 16, 12)

# Per-stage screen and instrument colours. The index is the stage, not the form: forms 0/1 share
# the first entry, 2/3 the second, 4/5 the third.
SCREEN_BASE = [(16, 40, 22), (14, 46, 50), (48, 14, 16)]
# Barely above the base. A scanline that reads as a stripe from across the room turns a 29-pixel
# screen into a venetian blind; it only has to break the flat fill.
SCREEN_SCAN = [(19, 46, 26), (17, 52, 56), (55, 17, 19)]
TRACE = [(96, 196, 118), (104, 208, 200), (226, 78, 74)]
LCD_TEXT = [(120, 208, 136), (118, 214, 206), (232, 96, 90)]
# Oxide creeping over the casing. Stage 0 has none; the cyan and red stages crack outward from the
# rim, which is the same progression the panel art uses.
OXIDE = [None, (58, 116, 112), (112, 40, 38)]

# Kept in step with TerminalVisualTheme.LAMP_DARK / LAMP_LIT / LAMP_LIT_CORE by hand. The panel
# draws the lamp and these bake it into the item, so the two have to agree or the terminal in the
# player's hand contradicts the one on their screen.
LAMP_DARK = (44, 30, 26)
LAMP_DARK_RIM = (86, 58, 48)
LAMP_LIT = (255, 55, 34)
LAMP_LIT_CORE = (255, 154, 136)

# --- atlas regions --------------------------------------------------------------------------
# Pixel rectangles in the 128x128 atlas. Model UVs are these divided by eight (128 / 16).
FACE_PX = (0, 0, 112, 56)
BACK_PX = (0, 56, 112, 112)
EDGE_PX = (0, 112, 112, 128)
BRASS_PX = (112, 0, 128, 16)
BRASS_DARK_PX = (112, 16, 128, 32)

FACE_UV = [0, 0, 14, 7]
BACK_UV = [0, 7, 14, 14]
EDGE_UV = [0, 14, 14, 16]
BRASS_UV = [14, 0, 16, 2]
BRASS_DARK_UV = [14, 2, 16, 4]

# --- front-face layout ----------------------------------------------------------------------
# All coordinates are pixels inside FACE_PX and inclusive on both ends. They mirror the
# proportions of TerminalUiLayout so the device in hand and the open panel read as one object:
# the face is 2:1 like the 512x256 panel, and the CRT takes the same ~62% of the width that
# TerminalUiLayout.DISPLAY does. An earlier pass made the face nearly square, which turned the
# wide CRT into a portrait screen the open panel never has.
CRT = (5, 5, 73, 48)
SCOPE = (78, 5, 98, 17)
LAMP = (101, 5, 107, 11)
COMPASS_CENTRE = (89, 28)
COMPASS_RADIUS = 9
SLIDER = (78, 40, 107, 43)
LCD = (78, 45, 107, 51)
CLOSE = (78, 52, 107, 53)
SCREWS = ((2, 2), (109, 2), (2, 53), (109, 53))


def _fill(pixels, box, colour):
    left, top, right, bottom = box
    for x in range(left, right + 1):
        for y in range(top, bottom + 1):
            pixels[x, y] = colour + (255,)


def _outline(pixels, box, colour):
    left, top, right, bottom = box
    for x in range(left, right + 1):
        pixels[x, top] = colour + (255,)
        pixels[x, bottom] = colour + (255,)
    for y in range(top, bottom + 1):
        pixels[left, y] = colour + (255,)
        pixels[right, y] = colour + (255,)


def _casing(pixels, width, height, stage):
    """Cast-iron plate, brass rim, corner screws and - past stage 0 - creeping oxide."""
    for x in range(width):
        for y in range(height):
            if x <= 1 or y <= 1 or x >= width - 2 or y >= height - 2:
                pixels[x, y] = (BRASS if (x + y) % 5 else BRASS_HI) + (255,)
            elif x == 2 or y == 2 or x == width - 3 or y == height - 3:
                pixels[x, y] = BRASS_LOW + (255,)
            elif (x * 3 + y * 5) % 23 == 0:
                pixels[x, y] = IRON_WORN + (255,)
            elif (x + y) % 7 == 0:
                pixels[x, y] = IRON_DARK + (255,)
            else:
                pixels[x, y] = IRON + (255,)
    for sx, sy in SCREWS:
        # A two-by-two screw: bright head, dark slot. One pixel disappears at item scale.
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            x = min(width - 1, max(0, sx + dx * (1 if sx < width // 2 else -1)))
            y = min(height - 1, max(0, sy + dy * (1 if sy < height // 2 else -1)))
            pixels[x, y] = (SCREW if dx == dy else IRON_DEEP) + (255,)
    oxide = OXIDE[stage]
    if oxide is None:
        return
    # Hairline cracks running in from the rim. Deterministic rather than random so the atlas is
    # reproducible, sparse so it reads as corrosion instead of a dashed border, and confined to
    # the rim so it never eats a readable instrument.
    for step in range(4, height - 5, 3 - stage):
        pixels[1 + step % 2, step] = oxide + (255,)
        pixels[width - 2 - step % 2, height - 1 - step] = oxide + (255,)
    for step in range(5, width - 8, 9 - stage * 2):
        pixels[step, step % 2] = oxide + (255,)
        pixels[width - 1 - step, height - 1 - step % 2] = oxide + (255,)


def _crt(pixels, stage):
    left, top, right, bottom = CRT
    _fill(pixels, CRT, SCREEN_BASE[stage])
    for y in range(top + 1, bottom, 3):
        for x in range(left + 1, right):
            pixels[x, y] = SCREEN_SCAN[stage] + (255,)
    _outline(pixels, CRT, GLASS_EDGE)
    _outline(pixels, (left - 1, top - 1, right + 1, bottom + 1), BRASS_LOW)
    # A short glare streak in the upper-left corner of the glass, the same one the panel art
    # carries. Across the full width it stopped reading as a reflection and started reading as
    # a row of text the screen does not have.
    for x in range(left + 4, left + 22):
        pixels[x, top + 2] = TRACE[stage] + (255,)
    for x in range(left + 4, left + 13):
        pixels[x, top + 3] = SCREEN_SCAN[stage] + (255,)


def _scope(pixels, stage):
    left, top, right, bottom = SCOPE
    _fill(pixels, SCOPE, (6, 8, 6))
    _outline(pixels, SCOPE, BRASS_LOW)
    centre = (top + bottom) // 2
    # A fixed trace rather than a waveform sampled from anything: this is a texture, and the
    # live heartbeat belongs to the real screen. Nineteen samples so a twenty-pixel scope shows
    # one full pass rather than a repeating tile.
    heights = (0, -1, 1, 0, -2, 3, -4, 2, 0, 1, -1, 0, 2, -3, 1, 0, -1, 1, 0)
    for index, x in enumerate(range(left + 1, right)):
        y = min(bottom - 1, max(top + 1, centre + heights[index % len(heights)]))
        pixels[x, y] = TRACE[stage] + (255,)


def _lamp(pixels, lit):
    left, top, right, bottom = LAMP
    _fill(pixels, LAMP, LAMP_LIT if lit else LAMP_DARK)
    _outline(pixels, LAMP, LAMP_LIT_CORE if lit else LAMP_DARK_RIM)
    if lit:
        for x in range(left + 2, right - 1):
            for y in range(top + 2, bottom - 1):
                pixels[x, y] = LAMP_LIT_CORE + (255,)


def _compass(pixels, stage):
    cx, cy = COMPASS_CENTRE
    for x in range(cx - COMPASS_RADIUS, cx + COMPASS_RADIUS + 1):
        for y in range(cy - COMPASS_RADIUS, cy + COMPASS_RADIUS + 1):
            distance = (x - cx) ** 2 + (y - cy) ** 2
            if distance > COMPASS_RADIUS ** 2:
                continue
            if distance > (COMPASS_RADIUS - 1) ** 2:
                pixels[x, y] = BRASS_HI + (255,)
            elif distance > (COMPASS_RADIUS - 2) ** 2:
                pixels[x, y] = BRASS_LOW + (255,)
            else:
                pixels[x, y] = (10, 12, 9) + (255,)
    for offset in range(-COMPASS_RADIUS + 2, 1):
        pixels[cx, cy + offset] = TRACE[stage] + (255,)
    pixels[cx, cy] = LAMP_LIT + (255,)


def _slider(pixels):
    left, top, right, bottom = SLIDER
    _fill(pixels, SLIDER, (24, 22, 14))
    _outline(pixels, SLIDER, BRASS_LOW)
    for x in range(left + 3, right - 1, 3):
        pixels[x, top + 1] = BRASS + (255,)
    thumb = left + (right - left) // 2
    for y in range(top, bottom + 1):
        pixels[thumb, y] = BRASS_HI + (255,)
        pixels[thumb + 1, y] = BRASS + (255,)


def _lcd(pixels, stage):
    left, top, right, bottom = LCD
    _fill(pixels, LCD, (8, 14, 10))
    _outline(pixels, LCD, BRASS_LOW)
    # Two rows of characters rather than legible words: at this scale glyphs would be noise, and
    # the panel is where the receiver actually says anything.
    for row, y in enumerate((top + 2, top + 4)):
        for x in range(left + 2, right - 1):
            if (x + row * 2) % 4:
                pixels[x, y] = LCD_TEXT[stage] + (255,)


def _close(pixels):
    left, top, right, bottom = CLOSE
    for x in range(left + 1, right, 2):
        pixels[x, top] = BRASS_LOW + (255,)
    for x in range(left + 2, right - 1, 3):
        pixels[x, bottom] = IRON_DEEP + (255,)


def build_face(stage: int, lit: bool) -> Image.Image:
    width = FACE_PX[2] - FACE_PX[0]
    height = FACE_PX[3] - FACE_PX[1]
    cell = Image.new("RGBA", (width, height), IRON + (255,))
    pixels = cell.load()
    _casing(pixels, width, height, stage)
    _crt(pixels, stage)
    _scope(pixels, stage)
    _lamp(pixels, lit)
    _compass(pixels, stage)
    _slider(pixels)
    _lcd(pixels, stage)
    _close(pixels)
    return cell


def build_back(stage: int) -> Image.Image:
    width = BACK_PX[2] - BACK_PX[0]
    height = BACK_PX[3] - BACK_PX[1]
    cell = Image.new("RGBA", (width, height), IRON_DARK + (255,))
    pixels = cell.load()
    _casing(pixels, width, height, stage)
    # A battery hatch and a ventilation grille, so the back is not a blank plate.
    _fill(pixels, (10, 12, 46, 40), IRON_DEEP)
    _outline(pixels, (10, 12, 46, 40), BRASS_LOW)
    for x in range(58, 102, 4):
        for y in range(14, 42):
            pixels[x, y] = IRON_DEEP + (255,)
    return cell


def build_edge(stage: int) -> Image.Image:
    width = EDGE_PX[2] - EDGE_PX[0]
    height = EDGE_PX[3] - EDGE_PX[1]
    cell = Image.new("RGBA", (width, height), IRON + (255,))
    pixels = cell.load()
    for x in range(width):
        for y in range(height):
            if y == 0:
                pixels[x, y] = BRASS + (255,)
            elif y == height - 1:
                pixels[x, y] = IRON_DEEP + (255,)
            elif (x * 5 + y * 3) % 17 == 0:
                pixels[x, y] = IRON_WORN + (255,)
            else:
                pixels[x, y] = IRON_DARK + (255,)
        if x % 11 == 5:
            pixels[x, height // 2] = BRASS_HI + (255,)
    oxide = OXIDE[stage]
    if oxide is not None:
        for x in range(2, width, 9):
            pixels[x, 1] = oxide + (255,)
            pixels[x, height - 2] = oxide + (255,)
    return cell


def build_brass(bright: bool) -> Image.Image:
    width = BRASS_PX[2] - BRASS_PX[0]
    height = BRASS_PX[3] - BRASS_PX[1]
    base = BRASS if bright else BRASS_LOW
    cell = Image.new("RGBA", (width, height), base + (255,))
    pixels = cell.load()
    for x in range(width):
        for y in range(height):
            if (x + y) % 4 == 0:
                pixels[x, y] = (BRASS_HI if bright else BRASS) + (255,)
            elif (x * 3 + y) % 7 == 0:
                pixels[x, y] = IRON_DARK + (255,)
    return cell


def build_atlas(form: int) -> Image.Image:
    stage = form // 2
    lit = form % 2 == 1
    atlas = Image.new("RGBA", (ATLAS, ATLAS), (0, 0, 0, 0))
    atlas.alpha_composite(build_face(stage, lit), FACE_PX[:2])
    atlas.alpha_composite(build_back(stage), BACK_PX[:2])
    atlas.alpha_composite(build_edge(stage), EDGE_PX[:2])
    atlas.alpha_composite(build_brass(True), BRASS_PX[:2])
    atlas.alpha_composite(build_brass(False), BRASS_DARK_PX[:2])
    return atlas


# --- geometry -------------------------------------------------------------------------------
# One rigid block: a chassis carrying the whole front face, plus four proud brass members that
# form the raised rim. Nothing rotates and nothing ever moves relative to anything else.
#
# 14 x 7 x 2.5, centred on the model box so the FIXED display context - which the two-handed
# presentation renders through - puts the device exactly on the origin. The 2:1 face is what
# makes the wide CRT read as the same screen the open panel shows.
CHASSIS = [1.0, 4.5, 6.75, 15.0, 11.5, 9.25]
RIM_TOP = [0.5, 11.5, 6.5, 15.5, 12.0, 9.5]
RIM_BOTTOM = [0.5, 4.0, 6.5, 15.5, 4.5, 9.5]
CORNER_LEFT = [0.5, 4.0, 6.5, 1.0, 12.0, 9.5]
CORNER_RIGHT = [15.0, 4.0, 6.5, 15.5, 12.0, 9.5]

# The display poses. The screen is the north face, so every view meant to show it turns the model
# around.
#
# The two first-person poses only cover the case the two-handed presentation does not take over:
# a terminal carried with something in the off hand. They are deliberately small - this is a
# heavy two-handed instrument being carried one-handed, not being read.
DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, -90, 25], "translation": [0, 4, 2], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_lefthand": {"rotation": [0, 90, -25], "translation": [0, 4, 2], "scale": [0.45, 0.45, 0.45]},
    "firstperson_righthand": {"rotation": [0, -80, 0], "translation": [1.0, 2.0, 1.5], "scale": [0.42, 0.42, 0.42]},
    "firstperson_lefthand": {"rotation": [0, 80, 0], "translation": [-1.0, 2.0, 1.5], "scale": [0.42, 0.42, 0.42]},
    "gui": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
    "ground": {"rotation": [90, 180, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
}


def faces(north, south, edge, under=None):
    return {
        "north": {"uv": north, "texture": "#shell"},
        "south": {"uv": south, "texture": "#shell"},
        "east": {"uv": edge, "texture": "#shell"},
        "west": {"uv": edge, "texture": "#shell"},
        "up": {"uv": edge, "texture": "#shell"},
        "down": {"uv": under or edge, "texture": "#shell"},
    }


ELEMENTS = [
    ("chassis", CHASSIS, faces(FACE_UV, BACK_UV, EDGE_UV)),
    ("rim_top", RIM_TOP, faces(BRASS_UV, BRASS_DARK_UV, BRASS_UV, BRASS_DARK_UV)),
    ("rim_bottom", RIM_BOTTOM, faces(BRASS_UV, BRASS_DARK_UV, BRASS_UV, BRASS_DARK_UV)),
    ("corner_left", CORNER_LEFT, faces(BRASS_UV, BRASS_DARK_UV, BRASS_UV, BRASS_DARK_UV)),
    ("corner_right", CORNER_RIGHT, faces(BRASS_UV, BRASS_DARK_UV, BRASS_UV, BRASS_DARK_UV)),
]


def detail_shell():
    """Machined relief around the original screen plane; all six states retain its exact pose."""
    def add(name, box, uv=BRASS_DARK_UV):
        ELEMENTS.append((name, box, faces(uv, uv, uv)))

    # Deep CRT hood. Its inner opening lies outside the painted glass, so the final zoom
    # still meets the same screen rectangle before the UI takes over.
    x0, y0, x1, y1 = CRT
    # The north face reverses U relative to model X.
    left, right = 15 - (x1 + 1) / 8, 15 - x0 / 8
    top, bottom = 11.5 - y0 / 8, 11.5 - (y1 + 1) / 8
    add('crt_hood_upper', [left-.16, top, 6.28, right+.16, top+.17, 6.77])
    add('crt_hood_lower', [left-.16, bottom-.17, 6.38, right+.16, bottom, 6.77])
    add('crt_hood_left', [left-.17, bottom, 6.28, left, top, 6.77])
    add('crt_hood_right', [right, bottom, 6.28, right+.17, top, 6.77])
    # A recessed seam between screen casting and instrument housing.
    add('control_partition', [5.42, 4.73, 6.48, 5.57, 11.25, 6.78], EDGE_UV)
    for index, (px, py) in enumerate(SCREWS):
        x, y = 15 - (px+.5)/8, 11.5-(py+.5)/8
        add(f'fastener_{index}', [x-.13,y-.13,6.31,x+.13,y+.13,6.55], BRASS_UV)
        add(f'fastener_slot_{index}', [x-.08,y-.022,6.29,x+.08,y+.022,6.32])
    # Stepped shoulder bumpers protect the brass rim, with a narrower raised centre.
    for side, x in (('left', .24), ('right', 15.1)):
        for row, y in enumerate((4.08, 10.93)):
            add(f'bumper_{side}_{row}', [x,y,6.37,x+.66,y+.96,9.55], EDGE_UV)
            add(f'bumper_ridge_{side}_{row}', [x+.12,y+.1,6.19,x+.54,y+.86,9.65], BRASS_DARK_UV)
    # Thick rear battery bay, retaining ribs and physical recessed ventilation channels.
    add('battery_pan', [2.1,5.25,9.24,10.45,10.75,9.6], EDGE_UV)
    add('battery_latch', [5.25,5.1,9.58,7.3,5.7,9.78], BRASS_DARK_UV)
    for i in range(8):
        x=2.5+i*.9
        add(f'battery_fin_{i}', [x,5.9,9.59,x+.35,10.1,9.77], EDGE_UV)
    for i in range(7):
        y=5.6+i*.68
        add(f'rear_vent_{i}', [11.1,y,9.26,14.1,y+.3,9.61], BRASS_DARK_UV)
    # Knurled edge grips are outside the CRT and outside the state-dependent unread lamp.
    for side, x in (('left',.37),('right',15.0)):
        for i in range(9):
            y=5.1+i*.59
            add(f'grip_{side}_{i}', [x,y,7.0,x+.6,y+.27,9.2], EDGE_UV)
    add('serial_plate', [5.0,10.15,9.6,9.6,10.55,9.76], BRASS_DARK_UV)


detail_shell()


def build_model(form: int) -> dict:
    texture = f"thefourthfrequency:item/old_terminal_shell_{form}"
    return {
        "credit": "Generated by tools/generate_terminal_3d_assets.py - do not edit by hand"
                  f" (form {form}); Blockbench source: docs/art/terminal/old_terminal_shell.bbmodel",
        "textures": {"shell": texture, "particle": texture},
        "display": DISPLAY,
        "elements": [
            {"name": name, "from": box[:3], "to": box[3:], "faces": face_map}
            for name, box, face_map in ELEMENTS
        ],
    }


def build_blockbench() -> dict:
    """A Blockbench-editable source carrying the same geometry and the form-0 atlas.

    Blockbench face UVs are texture pixels rather than the item format's sixteenths, so every UV
    is scaled by ATLAS / 16 on the way out. Editing this file and re-exporting a Java block model
    reproduces the generated geometry; the six per-form atlases stay this script's job.
    """
    scale = ATLAS / 16.0  # 8 at a 128px atlas
    elements = []
    for index, (name, box, face_map) in enumerate(ELEMENTS):
        elements.append({
            "name": name,
            "box_uv": False,
            "rescale": False,
            "locked": False,
            "from": box[:3],
            "to": box[3:],
            "autouv": 0,
            "color": index,
            "origin": [8, 8, 8],
            "faces": {
                side: {"uv": [round(value * scale, 2) for value in face["uv"]], "texture": 0}
                for side, face in face_map.items()
            },
            "uuid": f"00000000-0000-4000-8000-{index:012d}",
        })
    atlas = TEXTURE_DIR / "old_terminal_shell_0.png"
    encoded = base64.b64encode(atlas.read_bytes()).decode("ascii")
    return {
        "meta": {"format_version": "4.5", "model_format": "java_block", "box_uv": False},
        "name": "old_terminal_shell",
        "model_identifier": "old_terminal_shell",
        "resolution": {"width": ATLAS, "height": ATLAS},
        "elements": elements,
        "outliner": [element["uuid"] for element in elements],
        "textures": [{
            "path": "",
            "name": "old_terminal_shell_0.png",
            "folder": "item",
            "namespace": "thefourthfrequency",
            "id": "0",
            "particle": True,
            "render_mode": "normal",
            "uuid": "00000000-0000-4000-8000-0000000000ff",
            "source": "data:image/png;base64," + encoded,
        }],
        "display": DISPLAY,
    }


def main() -> int:
    TEXTURE_DIR.mkdir(parents=True, exist_ok=True)
    MODEL_DIR.mkdir(parents=True, exist_ok=True)
    for form in range(FORMS):
        build_atlas(form).save(TEXTURE_DIR / f"old_terminal_shell_{form}.png", format="PNG", optimize=True)
        path = MODEL_DIR / f"old_terminal_held_{form}.json"
        path.write_text(json.dumps(build_model(form), indent=2) + "\n", encoding="utf-8")
    BLOCKBENCH_OUT.parent.mkdir(parents=True, exist_ok=True)
    BLOCKBENCH_OUT.write_text(json.dumps(build_blockbench(), indent=2) + "\n", encoding="utf-8")

    # Retired with the fold: the single shared atlas and the six hinge frames it fed.
    for stale in [TEXTURE_DIR / "old_terminal_shell.png"] + [
            MODEL_DIR / f"old_terminal_fold_{frame}.json" for frame in range(6)]:
        if stale.exists():
            stale.unlink()
            print(f"removed stale {stale.relative_to(ROOT)}")

    print(f"wrote {FORMS} atlases, {FORMS} item models and {BLOCKBENCH_OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
