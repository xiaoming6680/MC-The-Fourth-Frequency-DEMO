from __future__ import annotations

import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / "docs/art/rework_body/rework_body_material_reference.png"
OUTPUT = ROOT / "src/main/resources/assets/thefourthfrequency/textures/entity"
GUIDE = ROOT / "docs/art/rework_body/rework_body_uv_guide.png"
SIZE = 256

# ReworkBodyModel uses a 128x128 virtual canvas over a 256x256 resource.
# These rectangles are the doubled, physical-pixel envelopes of its UV islands.
UV_REGIONS = {
    "torso": (0, 0, 54, 48),
    "pelvis": (56, 0, 110, 28),
    "neck": (112, 0, 140, 30),
    "head": (140, 0, 190, 34),
    "jaw": (192, 0, 246, 22),
    "inner jaw": (192, 24, 246, 48),
    "face plates": (140, 40, 246, 66),
    "normal arms": (0, 64, 98, 110),
    "legs": (0, 112, 100, 166),
    "ribs": (128, 128, 198, 222),
    "spine": (198, 128, 252, 202),
    "back arms": (0, 168, 126, 254),
}

PROFILES = {
    1: dict(head_width=4.5, head_depth=4.2, head_height=6.2, jaw_height=2.02),
    2: dict(head_width=5.0, head_depth=4.5, head_height=6.2, jaw_height=2.24),
    3: dict(head_width=5.2, head_depth=4.8, head_height=6.2, jaw_height=2.46),
    4: dict(head_width=5.8, head_depth=5.2, head_height=7.0, jaw_height=2.68),
    5: dict(head_width=6.2, head_depth=5.6, head_height=7.8, jaw_height=2.90),
}

PALETTES = {
    1: ((24, 20, 22), (158, 154, 151), (216, 207, 191)),
    2: ((27, 17, 18), (143, 126, 124), (205, 186, 168)),
    3: ((18, 16, 17), (113, 100, 98), (191, 174, 147)),
    4: ((15, 12, 13), (106, 84, 79), (184, 164, 139)),
    5: ((12, 10, 11), (89, 70, 68), (172, 151, 125)),
}


def material_panel(reference: Image.Image, stage: int) -> Image.Image:
    left = round(reference.width * (stage - 1) / 5)
    right = round(reference.width * stage / 5)
    return reference.crop((left, 0, right, reference.height))


def crop_texture(panel: Image.Image, rng: random.Random, width: int, height: int) -> Image.Image:
    crop_width = rng.randint(max(40, panel.width // 3), panel.width)
    crop_height = rng.randint(max(80, panel.height // 7), max(81, panel.height // 2))
    left = rng.randint(0, max(0, panel.width - crop_width))
    top = rng.randint(0, max(0, panel.height - crop_height))
    patch = panel.crop((left, top, left + crop_width, top + crop_height))
    if rng.random() < 0.5:
        patch = ImageOps.mirror(patch)
    if rng.random() < 0.25:
        patch = ImageOps.flip(patch)
    return patch.resize((width, height), Image.Resampling.LANCZOS)


def material_atlas(panel: Image.Image, stage: int) -> Image.Image:
    rng = random.Random(4109 + stage * 997)
    quilt = Image.new("RGB", (SIZE, SIZE))
    tile = 64
    for y in range(0, SIZE, tile):
        for x in range(0, SIZE, tile):
            quilt.paste(crop_texture(panel, rng, tile, tile), (x, y))
    quilt = quilt.filter(ImageFilter.GaussianBlur(0.45))
    gray = ImageOps.grayscale(quilt)
    black, middle, white = PALETTES[stage]
    colored = ImageOps.colorize(gray, black=black, mid=middle, white=white,
                                 blackpoint=7, whitepoint=247, midpoint=122)
    colored = Image.blend(colored, quilt, 0.25 if stage < 3 else 0.18)
    colored = ImageEnhance.Contrast(colored).enhance(1.13)
    colored = ImageEnhance.Sharpness(colored).enhance(1.18)

    # Re-project a distinct source crop into every semantic UV group. This both
    # breaks recognisable source anatomy and keeps neighboring cube faces coherent.
    for index, rect in enumerate(UV_REGIONS.values()):
        x0, y0, x1, y1 = rect
        patch = crop_texture(panel, random.Random(9001 + stage * 131 + index * 17), x1 - x0, y1 - y0)
        patch_gray = ImageOps.grayscale(patch)
        patch = ImageOps.colorize(patch_gray, black=black, mid=middle, white=white,
                                  blackpoint=5, whitepoint=248, midpoint=125)
        colored.paste(Image.blend(colored.crop(rect), patch, 0.42), rect)
    return colored.convert("RGBA")


def jagged_line(draw: ImageDraw.ImageDraw, rng: random.Random, bounds: tuple[int, int, int, int],
                fill: tuple[int, int, int, int], width: int = 1, segments: int = 5) -> None:
    x0, y0, x1, y1 = bounds
    start_x = rng.randint(x0, max(x0, x1 - 1))
    start_y = rng.randint(y0, max(y0, y1 - 1))
    angle = rng.random() * math.tau
    length = max(4.0, min(x1 - x0, y1 - y0) * rng.uniform(0.45, 0.90))
    points = [(start_x, start_y)]
    for index in range(1, segments + 1):
        distance = length * index / segments
        px = start_x + math.cos(angle) * distance + rng.uniform(-2.0, 2.0)
        py = start_y + math.sin(angle) * distance + rng.uniform(-2.0, 2.0)
        points.append((max(x0, min(x1 - 1, int(px))), max(y0, min(y1 - 1, int(py)))))
    draw.line(points, fill=fill, width=width, joint="curve")


def decorate_region(base: Image.Image, stage: int, name: str,
                    rect: tuple[int, int, int, int], seed: int) -> None:
    rng = random.Random(seed)
    x0, y0, x1, y1 = rect
    width, height = x1 - x0, y1 - y0
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer, "RGBA")

    # Cold bruising remains around all articulation islands, strongest early on.
    bruise_count = 2 + (1 if name in {"normal arms", "legs", "back arms"} else 0)
    for _ in range(bruise_count):
        radius_x = rng.randint(3, max(3, min(10, width // 3)))
        radius_y = rng.randint(2, max(2, min(7, height // 3)))
        cx, cy = rng.randrange(width), rng.randrange(height)
        draw.ellipse((cx - radius_x, cy - radius_y, cx + radius_x, cy + radius_y),
                     fill=(53, 32, 65, 28 + max(0, 4 - stage) * 5))

    # Dried blood and torn fascia increase monotonically with the form stage.
    for _ in range(stage + (2 if name in {"torso", "head", "jaw"} else 0)):
        jagged_line(draw, rng, (1, 1, max(2, width - 1), max(2, height - 1)),
                    (70 + stage * 5, 18, 16, 75 + stage * 9), width=1 + stage // 3)
        if stage >= 2:
            jagged_line(draw, rng, (1, 1, max(2, width - 1), max(2, height - 1)),
                        (116, 29, 26, 42 + stage * 8), width=1)

    if stage >= 3:
        for _ in range(stage - 1):
            radius_x = rng.randint(2, max(2, min(8, width // 4)))
            radius_y = rng.randint(2, max(2, min(6, height // 4)))
            cx, cy = rng.randrange(width), rng.randrange(height)
            draw.ellipse((cx - radius_x, cy - radius_y, cx + radius_x, cy + radius_y),
                         fill=(12, 11, 12, 105 + stage * 12))
        if name in {"ribs", "spine", "face plates", "back arms"}:
            for _ in range(stage - 2):
                cx, cy = rng.randrange(width), rng.randrange(height)
                draw.rounded_rectangle((cx - 2, cy - 5, cx + 2, cy + 5), radius=1,
                                       fill=(181, 163, 129, 125), outline=(72, 47, 38, 130))

    layer = layer.filter(ImageFilter.GaussianBlur(0.35))
    base.alpha_composite(layer, (x0, y0))


def face_coordinates(stage: int) -> dict[str, tuple[int, int, int, int]]:
    profile = PROFILES[stage]
    head_depth = profile["head_depth"]
    head_width = profile["head_width"]
    head_height = profile["head_height"]
    jaw_depth = head_depth * 0.82
    jaw_width = head_width
    jaw_height = profile["jaw_height"]
    return {
        "head_front": (
            round((70 + head_depth) * 2), round(head_depth * 2),
            round((70 + head_depth + head_width) * 2), round((head_depth + head_height) * 2),
        ),
        "jaw_front": (
            round((96 + jaw_depth) * 2), round(jaw_depth * 2),
            round((96 + jaw_depth + jaw_width) * 2), round((jaw_depth + jaw_height) * 2),
        ),
        "inner_front": (
            round((96 + head_depth * 0.55) * 2), round((12 + head_depth * 0.55) * 2),
            round((96 + head_depth * 0.55 + head_width * 0.72) * 2),
            round((12 + head_depth * 0.55 + max(1.2, jaw_height * 0.58)) * 2),
        ),
    }


def paint_face(base: Image.Image, stage: int) -> None:
    coords = face_coordinates(stage)
    draw = ImageDraw.Draw(base, "RGBA")
    x0, y0, x1, y1 = coords["head_front"]
    face_width = max(4, x1 - x0)
    face_height = max(6, y1 - y0)
    eye_y = y0 + max(2, face_height // 3)
    eye_rx = max(1, face_width // 8)
    eye_ry = max(1, face_height // 7)
    for eye_x in (x0 + face_width // 3, x0 + face_width * 2 // 3):
        draw.ellipse((eye_x - eye_rx, eye_y - eye_ry, eye_x + eye_rx, eye_y + eye_ry),
                     fill=(9, 8, 10, 225), outline=(61, 29, 38, 210))
    if stage >= 3:
        draw.line((x0 + face_width // 2, y0 + 1, x0 + face_width // 2 - stage % 2,
                   y1 - 1), fill=(31, 13, 14, 205), width=1 + stage // 4)

    jx0, jy0, jx1, jy1 = coords["jaw_front"]
    mouth_y = (jy0 + jy1) // 2
    draw.line((jx0 + 1, mouth_y, jx1 - 1, mouth_y + (stage % 2)),
              fill=(39, 7, 8, 235), width=max(1, stage // 2))
    tooth_count = 3 + stage
    for index in range(tooth_count):
        tooth_x = jx0 + 1 + index * max(1, (jx1 - jx0 - 2) // max(1, tooth_count - 1))
        draw.point((tooth_x, mouth_y), fill=(184, 166, 130, 225))
    if stage >= 4:
        ix0, iy0, ix1, iy1 = coords["inner_front"]
        draw.rectangle((ix0, iy0, ix1, iy1), fill=(28, 7, 8, 185))
        draw.line((ix0 + 1, (iy0 + iy1) // 2, ix1 - 1, (iy0 + iy1) // 2),
                  fill=(171, 152, 118, 185), width=1)


def create_emissive(stage: int) -> Image.Image:
    if stage not in (3, 5):
        raise ValueError("Only middle and final anatomy profiles have emissive masks")
    coords = face_coordinates(stage)
    core = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(core, "RGBA")
    x0, y0, x1, y1 = coords["head_front"]
    width, height = x1 - x0, y1 - y0
    eye_y = y0 + max(2, height // 3)
    for eye_x in (x0 + width // 3, x0 + width * 2 // 3):
        draw.ellipse((eye_x - 1, eye_y - 1, eye_x + 1, eye_y + 1),
                     fill=(93, 17, 14, 82 if stage == 4 else 96))

    jx0, jy0, jx1, jy1 = coords["jaw_front"]
    mouth_y = (jy0 + jy1) // 2
    draw.line((jx0 + 1, mouth_y, (jx0 + jx1) // 2, mouth_y + 1, jx1 - 1, mouth_y),
              fill=(108, 25, 18, 100 if stage == 4 else 116), width=1)
    ix0, iy0, ix1, iy1 = coords["inner_front"]
    draw.line((ix0 + 1, (iy0 + iy1) // 2, ix1 - 1, (iy0 + iy1) // 2),
              fill=(143, 127, 95, 76 if stage == 4 else 92), width=1)

    if stage == 5:
        # Each short mark falls inside one of the exposed-spine cube UV islands.
        for index in range(9):
            sx = 202 + (index % 4) * 10
            sy = 132 + (index // 4) * 12
            draw.line((sx, sy, sx + 3, sy + 5), fill=(139, 124, 92, 78), width=1)
        # Sparse subcutaneous fissures on the torso front, never a solid glow area.
        draw.line((12, 14, 17, 20, 14, 27, 20, 35), fill=(92, 15, 12, 80), width=1)
        draw.line((29, 10, 25, 18, 31, 25), fill=(119, 27, 18, 62), width=1)

    halo = core.filter(ImageFilter.GaussianBlur(1.15))
    halo_alpha = halo.getchannel("A").point(lambda value: min(45, round(value * 0.38)))
    halo.putalpha(halo_alpha)
    return Image.alpha_composite(halo, core)


def merge_emissive_hint(base: Image.Image, emissive: Image.Image) -> Image.Image:
    hint = emissive.copy()
    hint_alpha = hint.getchannel("A").point(lambda value: round(value * 0.48))
    hint.putalpha(hint_alpha)
    return Image.alpha_composite(base, hint)


def create_uv_guide() -> None:
    image = Image.new("RGB", (SIZE, SIZE), (21, 22, 24))
    draw = ImageDraw.Draw(image)
    colors = [
        (119, 167, 188), (171, 124, 167), (147, 178, 118), (194, 139, 99),
        (195, 102, 104), (111, 157, 151), (177, 176, 104), (115, 129, 184),
        (150, 112, 95), (131, 159, 104), (185, 132, 89), (123, 104, 156),
    ]
    for (name, rect), color in zip(UV_REGIONS.items(), colors):
        draw.rectangle((rect[0], rect[1], rect[2] - 1, rect[3] - 1), outline=color, width=2)
        draw.text((rect[0] + 3, rect[1] + 3), name, fill=color)
    draw.text((136, 232), "128 virtual / 256 physical", fill=(210, 210, 206))
    image.save(GUIDE)


def main() -> None:
    if not REFERENCE.is_file():
        raise FileNotFoundError(f"Missing generated material reference: {REFERENCE}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    GUIDE.parent.mkdir(parents=True, exist_ok=True)
    reference = Image.open(REFERENCE).convert("RGB")
    for form, stage in enumerate((1, 3, 5), start=1):
        panel = material_panel(reference, stage)
        texture = material_atlas(panel, stage)
        for index, (name, rect) in enumerate(UV_REGIONS.items()):
            decorate_region(texture, stage, name, rect, 17011 + stage * 571 + index * 43)
        paint_face(texture, stage)
        if stage >= 3:
            emissive = create_emissive(stage)
            texture = merge_emissive_hint(texture, emissive)
            emissive.save(OUTPUT / f"rework_body_stage_{form}_emissive.png", optimize=True)
        # Base textures are intentionally fully opaque; transparency belongs only to emissive masks.
        texture.convert("RGB").save(OUTPUT / f"rework_body_stage_{form}.png", optimize=True)
    create_uv_guide()


if __name__ == "__main__":
    main()
