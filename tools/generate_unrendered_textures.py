"""Generates the unrendered layer's four surface textures.

The layer owns its wall and floor rather than borrowing vanilla blocks, for one reason: the way out
of the place is a panel that is *slightly the wrong shade*, and "slightly" has to be a number
somebody chose. Matching a hand-authored vanilla texture closely enough to make a few percent of
colour the only difference is a guess that breaks whenever the base texture changes; generating both
halves from one recipe makes the delta exact and keeps the mottling identical, so colour is the only
thing that differs.

    solid wall  vs  false wall   - same pattern, LIGHTNESS_SHIFT apart
    solid floor vs  false floor  - same pattern, LIGHTNESS_SHIFT apart

The shift is deliberately small. At a glance down a corridor the false panel is deniable; looked at
directly, next to the real wall it abuts, it is unmistakable. That band is the whole design: findable
by paying attention, never findable by not paying attention.

    python tools/generate_unrendered_textures.py

Writes four 16x16 PNGs into src/main/resources/assets/thefourthfrequency/textures/block/.
"""

from __future__ import annotations

import argparse
import random
from pathlib import Path

from PIL import Image

SIZE = 16

# One seed per surface, so wall and floor do not share a pattern - but the solid and false variants
# of each surface DO share theirs, which is what makes the colour the only tell.
WALL_SEED = 0x1F4A_2C08
FLOOR_SEED = 0x77B1_9E35

# The layer's palette. Muted, warm, and low contrast: bright enough to read as a lit interior,
# flat enough that the eye finds nothing to fix on.
# Pulled toward each other from (176, 142, 68) and (144, 139, 128). The wall was strongly warm and
# the floor strongly neutral, and the blue channel alone differed by sixty - which read as two
# unrelated materials meeting at a hard line rather than as one interior. Each moved about 40% of the
# way to their midpoint: the blue gap drops from 60 to 36 and the red from 32 to 20. The wall is
# still the warmer surface and the floor still the greyer one; they just now look like they were
# specified by the same person.
WALL_BASE = (170, 141, 80)
WALL_GRAIN = 26
FLOOR_BASE = (150, 140, 116)
FLOOR_GRAIN = 16

# What "slightly the wrong shade" means, per channel, added to the false variant.
#
# Raised from (18, 14, 6). That first pass was reasoned to about a 9% lift - two just-noticeable
# differences for a large flat field - and in the layer itself it turned out to be under the
# threshold rather than at it: the panels were not deniable, they were invisible, and the terminal
# bearing became the only way anyone ever found the way out. Three things the reasoning did not
# account for: the surfaces are lit by sparse ceiling panels rather than evenly, the false panel is
# usually first seen down a corridor at a shallow angle, and the maze's own grain is already noisy
# at +/-26 per pixel, which swallows a shift of that size.
#
# +40 on red and +30 on green against a base near 176 is about a 20% lift - a panel you notice
# without looking for it, and still short of a marked door, because the pattern is pixel-for-pixel
# identical to the wall beside it and nothing outlines it. The warm bias is kept and kept
# proportional: a purely brighter patch reads as a light source, a warmer one reads as different
# material, and that distinction is what stops the exit from looking like a lamp.
LIGHTNESS_SHIFT = (40, 30, 12)


def _clamp(value: int) -> int:
    return max(0, min(255, value))


def _surface(seed: int, base: tuple[int, int, int], grain: int,
             shift: tuple[int, int, int] | None) -> Image.Image:
    rng = random.Random(seed)
    image = Image.new("RGBA", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        for x in range(SIZE):
            # Per-pixel grain plus a faint horizontal banding, which is what stops a flat colour
            # from reading as an untextured debug block at close range.
            noise = rng.randint(-grain, grain)
            band = -4 if y % 4 == 0 else 0
            colour = [base[i] + noise + band for i in range(3)]
            if shift is not None:
                colour = [colour[i] + shift[i] for i in range(3)]
            pixels[x, y] = (_clamp(colour[0]), _clamp(colour[1]), _clamp(colour[2]), 255)
    return image


def build() -> dict[str, Image.Image]:
    return {
        "unrendered_wall": _surface(WALL_SEED, WALL_BASE, WALL_GRAIN, None),
        "unrendered_false_wall": _surface(WALL_SEED, WALL_BASE, WALL_GRAIN, LIGHTNESS_SHIFT),
        "unrendered_floor": _surface(FLOOR_SEED, FLOOR_BASE, FLOOR_GRAIN, None),
        "unrendered_false_floor": _surface(FLOOR_SEED, FLOOR_BASE, FLOOR_GRAIN, LIGHTNESS_SHIFT),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("src/main/resources/assets/thefourthfrequency/textures/block"),
    )
    arguments = parser.parse_args()
    arguments.output.mkdir(parents=True, exist_ok=True)
    for name, image in build().items():
        path = arguments.output / f"{name}.png"
        image.save(path, optimize=True)
        print(f"wrote {path} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
