#!/usr/bin/env python3
"""Paint the world interface's base, emissive and impact sheets from its Blockbench model.

Every cube in ``world_interface.bbmodel`` names its material (``bone.center_cranium``,
``endstone.chunk_3``) and carries the box-UV island the exporter packed for it. This walks those
islands and paints each face of each island with its material, at ``SCALE`` texels per UV unit,
then lays the glow and the damage flash over the same rectangles. Nothing is hand-painted and
nothing is placed by coordinate: change the model, re-export, re-run, and the sheets follow.

Run ``export_world_interface_model.py`` first; the offsets it wrote into the bbmodel are the
offsets painted here, and the two are checked against each other by ``ResourceContractTest``.

Materials follow the reference the art notes describe - the ender dragon: near-black hide, almost
no hue, mottling rather than linework, and the only saturated thing on the sheet is what glows.
Materials separate by value and by the shape of their blotching.
"""

from __future__ import annotations

import hashlib
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from world_interface_model import ART_DIR, ROOT, UPRIGHT_FACES, Island, face_rects, islands_of, load

BBMODEL = ART_DIR / "world_interface.bbmodel"
ENTITY = ROOT / "src/main/resources/assets/thefourthfrequency/textures/entity"
SEED = 0x574F524C44494E54
# Texels per UV unit. The third form is scaled twelve times, so at one texel per unit a texel
# covers most of a block; four keeps the surface alive when a player stands under it.
SCALE = 4
# The glow and the flash are read at arena distance and carry no fine detail, so they ship at
# half the base density: the same UV canvas, a quarter of the memory.
OVERLAY_SCALE = 2

# Directional relief: faces lit from above, upright faces darkening toward the bottom, and a
# one-texel ambient-occlusion border that is what separates a hundred touching boxes into a
# hundred readable boxes.
FACE_SHADE = {"up": 1.16, "north": 1.00, "west": 0.90, "east": 0.90, "south": 0.82, "down": 0.74}
GRADIENT_TOP = 1.05
GRADIENT_BOTTOM = 0.92
EDGE_AO = 0.74
# Bone is built from many small cubes; the full border on each one turned the skulls into crates.
EDGE_AO_BONE = 0.86
# Faces narrower than this many texels get no border at all - it would be most of the face.
MIN_BORDERED = 6
ISOTROPIC_SHADE = 0.94
PATCH_CELL = 3
PATCH_DEPTH = 0.26
# Parts the model tumbles freely, painted without a baked "top is bright".
ISOTROPIC = {"plating", "endstone", "obsidian", "swallowed", "glow", "horn"}
# Materials whose islands the glow may touch, and nothing else.
EMISSIVE = {"eye", "core", "glow", "socket"}

FORM_PALETTES = [
    {  # Nascent: still mostly the world it ate. Stone grey, end stone still pale, bone still warm.
        "swallowed": (48, 46, 52), "endstone": (100, 98, 80), "obsidian": (24, 20, 34),
        "plating": (78, 76, 86), "bone": (128, 120, 100), "tooth": (150, 142, 122),
        "horn": (92, 84, 70), "root": (30, 28, 34), "flesh": (52, 44, 54), "socket": (14, 11, 18),
        "eye": (232, 196, 120), "core": (226, 172, 84), "glow": (214, 150, 96),
        "seam": (23, 22, 26), "ore": (66, 62, 76), "vein": (26, 24, 44),
    },
    {  # Grown: the grey has gone cold and the bone is going with it.
        "swallowed": (40, 38, 48), "endstone": (84, 82, 74), "obsidian": (20, 16, 30),
        "plating": (66, 62, 80), "bone": (110, 102, 92), "tooth": (134, 126, 114),
        "horn": (80, 72, 66), "root": (26, 24, 31), "flesh": (48, 38, 52), "socket": (12, 9, 17),
        "eye": (238, 158, 250), "core": (222, 108, 236), "glow": (196, 108, 224),
        "seam": (19, 18, 23), "ore": (62, 55, 76), "vein": (24, 20, 46),
    },
    {  # Terminal: hide black enough that the apertures are the only colour on it.
        "swallowed": (31, 29, 38), "endstone": (76, 74, 68), "obsidian": (16, 12, 24),
        "plating": (54, 50, 68), "bone": (92, 84, 78), "tooth": (118, 110, 100),
        "horn": (68, 60, 58), "root": (21, 19, 26), "flesh": (40, 30, 44), "socket": (10, 8, 14),
        "eye": (240, 140, 252), "core": (214, 76, 238), "glow": (186, 84, 226),
        "seam": (15, 14, 18), "ore": (54, 46, 68), "vein": (22, 17, 48),
    },
]
# The failure ending swaps the base sheet for this: same geometry, all the light gone out of it.
BLACK_PALETTE = {
    "swallowed": (15, 14, 17), "endstone": (26, 25, 22), "obsidian": (8, 7, 11), "plating": (24, 23, 28),
    "bone": (34, 32, 29), "tooth": (40, 38, 34), "horn": (26, 24, 22), "root": (10, 9, 12),
    "flesh": (16, 13, 17), "socket": (6, 5, 8), "eye": (62, 8, 26), "core": (56, 6, 22),
    "glow": (48, 8, 24), "seam": (7, 7, 9), "ore": (24, 21, 28), "vein": (8, 7, 14),
}
EMISSIVE_ALPHA = 196
EMISSIVE_CORE_ALPHA = 236
HIT_COLOR = (255, 42, 88)


def clamp(value: float) -> int:
    return max(0, min(255, int(value)))


def patch(x: int, y: int, seed: int, cell_x: int, cell_y: int) -> float:
    """Blocky low-frequency value noise in [0, 1]: the blotching dragon hide has instead of lines."""
    value = ((x // cell_x) * 73856093) ^ ((y // cell_y) * 19349663) ^ seed
    value = (value * 2654435761) & 0xFFFFFFFF
    return ((value >> 16) & 0xFF) / 255.0


def paint_material(tile: Image.Image, material: str, palette: dict, rng: random.Random, face: str) -> None:
    """One face at full value in tile coordinates; relief is applied afterwards."""
    width, height = tile.size
    base = palette[material]
    seed = (int(hashlib.md5(material.encode()).hexdigest()[:6], 16) ^ (hash(face) << 3)) & 0xFFFFFF
    cell_x, cell_y = PATCH_CELL, PATCH_CELL
    depth = PATCH_DEPTH
    if material == "root":
        cell_x, cell_y = 1, PATCH_CELL * 3
    elif material == "endstone":
        cell_x, cell_y = 2, 2
        depth = 0.34
    elif material in ("eye", "core", "glow"):
        depth = 0.10
    elif material == "obsidian":
        cell_x, cell_y = 5, 3
        depth = 0.18
    pixels = tile.load()
    for y in range(height):
        for x in range(width):
            factor = 1.0 - depth * 0.5 + depth * patch(x, y, seed, cell_x, cell_y)
            if material in ("eye", "core", "glow"):
                factor *= 0.55 + 0.45 * (1.0 - abs(y / max(1, height - 1) - 0.5) * 2.0)
            grain = rng.randint(-3, 3)
            pixels[x, y] = (clamp(base[0] * factor + grain), clamp(base[1] * factor + grain),
                            clamp(base[2] * factor + grain), 255)
    draw = ImageDraw.Draw(tile)
    if material in ("swallowed", "endstone"):
        # Sparse mineral flecks are all that is left of the terrain it ate. Single texels, barely
        # off the base value: at boss scale anything stronger tiles visibly.
        ore = palette["ore"]
        for _ in range(max(1, width * height // 70)):
            draw.point((rng.randrange(width), rng.randrange(height)),
                       fill=tuple(clamp(c + rng.randint(-8, 8)) for c in ore) + (255,))
    elif material == "obsidian":
        # Faint cold veins, the way obsidian catches light along a fracture.
        vein = palette["vein"]
        for _ in range(max(1, width * height // 160)):
            sx, sy = rng.randrange(width), rng.randrange(height)
            draw.line((sx, sy, sx + rng.randint(-2, 2), sy + rng.randint(1, 3)),
                      fill=tuple(clamp(c * 1.6) for c in vein) + (255,))
    elif material in ("bone", "tooth"):
        # One soft highlight along the top, no outline. An outline made the skulls read as boxes.
        if height >= 4:
            draw.line((1, 1, width - 2, 1), fill=tuple(clamp(c * 1.14) for c in base) + (255,))
        if material == "bone" and width >= 6 and height >= 6:
            # A hairline crack or two across the larger bone faces.
            for _ in range(rng.randint(0, 2)):
                sx, sy = rng.randrange(1, width - 1), rng.randrange(1, height - 1)
                points = [(sx, sy)]
                for _ in range(rng.randint(2, 4)):
                    sx = max(1, min(width - 2, sx + rng.randint(-2, 2)))
                    sy = max(1, min(height - 2, sy + rng.randint(1, 3)))
                    points.append((sx, sy))
                draw.line(points, fill=tuple(clamp(c * 0.72) for c in base) + (255,))
    elif material == "flesh":
        for _ in range(max(1, width * height // 110)):
            sx, sy = rng.randrange(width), rng.randrange(height)
            draw.line((sx, sy, sx, min(height - 1, sy + rng.randint(1, 2))),
                      fill=tuple(clamp(c * 0.82) for c in base) + (255,))
    elif material == "horn":
        # Growth rings across the horn's length.
        for y in range(0, height, 3):
            draw.line((0, y, width - 1, y), fill=tuple(clamp(c * 0.86) for c in base) + (255,))


def apply_relief(tile: Image.Image, face: str, directional: bool, material: str) -> None:
    width, height = tile.size
    shade = FACE_SHADE[face] if directional else ISOTROPIC_SHADE
    vertical = directional and face in UPRIGHT_FACES
    bordered = width >= MIN_BORDERED and height >= MIN_BORDERED
    edge = EDGE_AO_BONE if material in ("bone", "tooth", "horn") else EDGE_AO
    pixels = tile.load()
    for y in range(height):
        gradient = 1.0
        if vertical and height > 1:
            gradient = GRADIENT_TOP + (GRADIENT_BOTTOM - GRADIENT_TOP) * (y / (height - 1))
        for x in range(width):
            factor = shade * gradient
            if bordered and (x == 0 or y == 0 or x == width - 1 or y == height - 1):
                factor *= edge
            red, green, blue, _ = pixels[x, y]
            pixels[x, y] = (clamp(red * factor), clamp(green * factor), clamp(blue * factor), 255)


def rects(island: Island, scale: int) -> dict[str, tuple[int, int, int, int]]:
    return face_rects((island.uv, island.size, island.mirror), scale)


def build_base(islands: list[Island], palette: dict, seed: int, size: tuple[int, int]) -> Image.Image:
    width, height = size
    dead = palette["seam"]
    row = bytes()
    for x in range(width):
        noise = ((x * 17) % 5) - 2
        row += bytes((clamp(dead[0] + noise), clamp(dead[1] + noise), clamp(dead[2] + noise), 255))
    image = Image.frombytes("RGBA", (width, height), row * height)
    for island in islands:
        island_rng = random.Random(seed ^ int(hashlib.md5(island.name.encode()).hexdigest()[:8], 16))
        directional = island.material not in ISOTROPIC
        for face, (x0, y0, x1, y1) in rects(island, SCALE).items():
            if x1 <= x0 or y1 <= y0:
                continue
            tile = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 255))
            paint_material(tile, island.material, palette, island_rng, face)
            apply_relief(tile, face, directional, island.material)
            image.paste(tile, (x0, y0))
    return image


def emissive_rects(islands: list[Island]) -> list[tuple[str, tuple[int, int, int, int]]]:
    """Which rectangles the glow may touch. Apertures light their north face only; nodes and the
    kernel light the four upright faces but never up or down - a box lit on all six sides just
    advertises that it is a box."""
    allowed = []
    for island in islands:
        if island.material not in EMISSIVE:
            continue
        island_rects = rects(island, OVERLAY_SCALE)
        faces = ("north",) if island.material in ("eye", "socket") else UPRIGHT_FACES
        for face in faces:
            allowed.append((island.material, island_rects[face]))
    return allowed


def build_emissive(islands: list[Island], colour: tuple[int, int, int], seed: int,
                   size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    rng = random.Random(seed)
    for material, (x0, y0, x1, y1) in emissive_rects(islands):
        if x1 <= x0 or y1 <= y0:
            continue
        if material == "eye":
            # The aperture: filled, brightest thing on the sheet, and the only thing on the model
            # that is allowed to be.
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=colour + (EMISSIVE_CORE_ALPHA,))
            # Brighter still at the centre, so the aperture reads as depth rather than a decal.
            inset = min(max(1, (y1 - y0) // 4), (min(x1-x0, y1-y0)-1)//2)
            draw.rectangle((x0 + inset, y0 + inset, x1 - 1 - inset, y1 - 1 - inset),
                           fill=tuple(clamp(c * 1.12) for c in colour) + (255,))
        elif material == "socket":
            # A shallow pool of light along the socket's lower rim: something behind the eye.
            rim = max(1, (y1 - y0) // 3)
            draw.rectangle((x0, y1 - rim, x1 - 1, y1 - 1), fill=colour + (EMISSIVE_ALPHA // 3,))
        elif material == "core":
            # The buried interface. A lattice, not a lamp - a readout, not a face.
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=colour + (EMISSIVE_ALPHA // 2,))
            for offset in range(0, max(1, x1 - x0), max(2, OVERLAY_SCALE)):
                draw.line((x0 + offset, y0, x0 + offset, y1 - 1), fill=colour + (EMISSIVE_ALPHA,))
            for offset in range(0, max(1, y1 - y0), max(2, OVERLAY_SCALE)):
                draw.line((x0, y0 + offset, x1 - 1, y0 + offset), fill=colour + (EMISSIVE_ALPHA,))
        else:
            # Tendril nodes: a band across the middle of the upright faces, so the limb reads as
            # lit from inside rather than as a glowing cube on a stick.
            mid = (y0 + y1) // 2
            band = max(1, (y1 - y0) // 3)
            draw.rectangle((x0, max(y0, mid - band), x1 - 1, min(y1 - 1, mid + band)),
                           fill=colour + (EMISSIVE_ALPHA,))
    return image


def build_hit(islands: list[Island], seed: int, size: tuple[int, int]) -> Image.Image:
    """Damage flash: a rim plus an interior wash on every face, so a hit registers as the whole
    silhouette flaring at sixty blocks rather than as speckle."""
    rng = random.Random(seed)
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    for island in islands:
        for x0, y0, x1, y1 in rects(island, OVERLAY_SCALE).values():
            if x1 <= x0 or y1 <= y0:
                continue
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=HIT_COLOR + (108,))
            if x1 - x0 < 2 or y1 - y0 < 2:
                # Pillow's rectangle outline crosses a collapsed edge on a one-pixel face.
                continue
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=HIT_COLOR + (238,), width=1)
            for _ in range(max(1, (x1 - x0) * (y1 - y0) // 600)):
                cx, cy = rng.randrange(x0, x1), rng.randrange(y0, y1)
                points = [(cx, cy)]
                for _ in range(rng.randrange(2, 5)):
                    cx = max(x0, min(x1 - 1, cx + rng.randrange(-3, 4)))
                    cy = max(y0, min(y1 - 1, cy + rng.randrange(-2, 5)))
                    points.append((cx, cy))
                draw.line(points, fill=HIT_COLOR + (rng.randrange(200, 256),), width=1)
    for material, (x0, y0, x1, y1) in emissive_rects(islands):
        if material == "eye" and x1 > x0 and y1 > y0:
            draw.rectangle((x0, y0, x1 - 1, y1 - 1), fill=HIT_COLOR + (208,))
    return image


def build_guide(islands: list[Island], size: tuple[int, int]) -> Image.Image:
    guide = Image.new("RGBA", size, (12, 13, 15, 255))
    draw = ImageDraw.Draw(guide)
    font = ImageFont.load_default()
    colours = {
        "swallowed": (128, 108, 150, 255), "endstone": (210, 208, 150, 255), "obsidian": (90, 70, 140, 255),
        "plating": (150, 132, 178, 255), "bone": (206, 194, 160, 255), "tooth": (240, 232, 210, 255),
        "horn": (170, 150, 120, 255), "root": (128, 82, 110, 255), "flesh": (176, 96, 148, 255),
        "socket": (80, 60, 100, 255), "eye": (255, 214, 128, 255), "core": (248, 196, 96, 255),
        "glow": (230, 150, 240, 255),
    }
    for grid in range(0, size[0], 16 * SCALE):
        draw.line((grid, 0, grid, size[1] - 1), fill=(26, 29, 34, 255))
    for grid in range(0, size[1], 16 * SCALE):
        draw.line((0, grid, size[0] - 1, grid), fill=(26, 29, 34, 255))
    for island in islands:
        for x0, y0, x1, y1 in rects(island, SCALE).values():
            if x1 > x0 and y1 > y0:
                draw.rectangle((x0, y0, x1 - 1, y1 - 1), outline=colours.get(island.material, (200, 200, 200, 255)), width=1)
        north = rects(island, SCALE)["north"]
        draw.text((north[0] + 1, north[1] + 1), island.material[:4], fill=(238, 238, 238, 255), font=font)
    return guide


def validate(islands: list[Island], base: Image.Image, emissive: Image.Image, hit: Image.Image) -> int:
    assert base.getchannel("A").getextrema() == (255, 255), "base texture must be fully opaque"
    allowed = [rect for _, rect in emissive_rects(islands)]
    pixels = emissive.load()
    lit = 0
    for y in range(emissive.size[1]):
        for x in range(emissive.size[0]):
            if pixels[x, y][3] == 0:
                continue
            lit += 1
            assert any(x0 <= x < x1 and y0 <= y < y1 for x0, y0, x1, y1 in allowed), \
                f"emissive pixel outside an emissive island at {(x, y)}"
    assert lit > 0
    island_rects = [rect for island in islands for rect in rects(island, OVERLAY_SCALE).values()]
    hit_pixels = hit.load()
    for y in range(0, hit.size[1], 3):
        for x in range(0, hit.size[0], 3):
            if hit_pixels[x, y][3] == 0:
                continue
            assert any(x0 <= x < x1 and y0 <= y < y1 for x0, y0, x1, y1 in island_rects), \
                f"impact pixel on dead sheet at {(x, y)}"
    return lit


def assert_islands_disjoint(islands: list[Island]) -> None:
    seen: dict[tuple[int, int], str] = {}
    for island in islands:
        for x0, y0, x1, y1 in rects(island, 1).values():
            for y in range(y0, y1):
                for x in range(x0, x1):
                    owner = seen.get((x, y))
                    assert owner is None or owner == island.name, \
                        f"UV overlap at {(x, y)}: {island.name} collides with {owner}"
                    seen[(x, y)] = island.name


def save(image: Image.Image, path: Path) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()[:16]
    return f"{path.relative_to(ROOT)} {image.size[0]}x{image.size[1]} {path.stat().st_size:>8} {digest}"


def main() -> None:
    model = load(BBMODEL)
    islands = islands_of(model)
    assert_islands_disjoint(islands)
    base_size = (model.resolution[0] * SCALE, model.resolution[1] * SCALE)
    overlay_size = (model.resolution[0] * OVERLAY_SCALE, model.resolution[1] * OVERLAY_SCALE)
    reports = []
    for form, palette in enumerate(FORM_PALETTES, start=1):
        base = build_base(islands, palette, SEED + form, base_size)
        emissive = build_emissive(islands, palette["core"], SEED + 200 + form, overlay_size)
        hit = build_hit(islands, SEED + 100 + form, overlay_size)
        lit = validate(islands, base, emissive, hit)
        reports.append(save(base, ENTITY / f"world_interface_form_{form}.png"))
        reports.append(save(emissive, ENTITY / f"world_interface_form_{form}_emissive.png"))
        reports.append(save(hit, ENTITY / f"world_interface_form_{form}_hit.png"))
        print(f"form {form}: emissive lit={lit}px canvas={lit / (overlay_size[0] * overlay_size[1]) * 100:.2f}%")
    reports.append(save(build_base(islands, BLACK_PALETTE, SEED + 999, base_size),
                        ENTITY / "world_interface_form_3_black.png"))
    reports.append(save(build_guide(islands, base_size), ART_DIR / "world_interface_uv_template.png"))
    print(f"islands={len(islands)} uv={model.resolution[0]}x{model.resolution[1]} base={base_size} overlay={overlay_size}")
    for report in reports:
        print(report)


if __name__ == "__main__":
    main()
