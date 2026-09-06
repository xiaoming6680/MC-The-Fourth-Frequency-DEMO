"""Generates the bacteria entity texture.

The mass has no face, no front and no readable feature, so the texture is not a map of anything -
it is only there to keep the silhouette from reading as a flat-shaded solid. Mottled wet tissue at
low contrast: enough variation that the lobes separate under the layer's flat overhead lighting,
never enough that any patch of it looks like a marking a player could learn.

Deliberately reproducible. Re-running this writes byte-identical output, so the texture is a build
artifact of this script rather than a file somebody has to keep.

    python tools/generate_bacteria_texture.py

Writes src/main/resources/assets/thefourthfrequency/textures/entity/bacteria.png (64x64).
"""

from __future__ import annotations

import argparse
import math
import random
from pathlib import Path

from PIL import Image

WIDTH = 64
HEIGHT = 64
SEED = 0x8AC7E121

# Wet tissue, kept dark. Against the layer's bright yellow walls anything lighter than this stops
# reading as a mass and starts reading as furniture.
BASE = (74, 34, 33)
HIGHLIGHT = (122, 60, 54)
SHADOW = (38, 16, 18)


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def _value_noise(rng: random.Random, width: int, height: int, cells: int) -> list[list[float]]:
    """Bilinearly interpolated value noise on a coarse lattice, wrapping on both axes."""
    lattice = [[rng.random() for _ in range(cells)] for _ in range(cells)]
    field: list[list[float]] = []
    for y in range(height):
        row = []
        for x in range(width):
            fx = x / width * cells
            fy = y / height * cells
            x0, y0 = int(fx) % cells, int(fy) % cells
            x1, y1 = (x0 + 1) % cells, (y0 + 1) % cells
            tx, ty = fx - int(fx), fy - int(fy)
            # Smoothstep, so the lattice does not show as a grid of diamonds.
            tx = tx * tx * (3 - 2 * tx)
            ty = ty * ty * (3 - 2 * ty)
            top = lattice[y0][x0] * (1 - tx) + lattice[y0][x1] * tx
            bottom = lattice[y1][x0] * (1 - tx) + lattice[y1][x1] * tx
            row.append(top * (1 - ty) + bottom * ty)
        field.append(row)
    return field


def build() -> Image.Image:
    rng = random.Random(SEED)
    coarse = _value_noise(rng, WIDTH, HEIGHT, 6)
    medium = _value_noise(rng, WIDTH, HEIGHT, 13)
    fine = _value_noise(rng, WIDTH, HEIGHT, 27)

    image = Image.new("RGBA", (WIDTH, HEIGHT))
    pixels = image.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            value = coarse[y][x] * 0.56 + medium[y][x] * 0.30 + fine[y][x] * 0.14
            # Narrow band around the base colour. Wide contrast would let a player pick out the same
            # patch twice and start treating it as an orientation cue.
            if value >= 0.5:
                colour = _lerp(BASE, HIGHLIGHT, (value - 0.5) * 1.55)
            else:
                colour = _lerp(BASE, SHADOW, (0.5 - value) * 1.35)
            # A few darker capillaries, thin enough to read as texture rather than as pattern.
            vein = math.sin(x * 0.62 + medium[y][x] * 5.1) * math.cos(y * 0.51 + coarse[y][x] * 4.3)
            if vein > 0.86:
                colour = _lerp(colour, SHADOW, 0.55)
            pixels[x, y] = (colour[0], colour[1], colour[2], 255)
    return image


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("src/main/resources/assets/thefourthfrequency/textures/entity/bacteria.png"),
    )
    arguments = parser.parse_args()
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    build().save(arguments.output, optimize=True)
    print(f"wrote {arguments.output} ({WIDTH}x{HEIGHT})")


if __name__ == "__main__":
    main()
