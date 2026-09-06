#!/usr/bin/env python3
"""Scaffold the world interface's Blockbench model onto the runtime skeleton.

One-shot generator: it reads the skeleton the server poses out of
``world_interface_runtime.bbmodel`` (exported from the Java model by ``gradlew
exportWorldInterfaceBbmodel``), keeps every animated bone with its pivot and bind rotation exactly
as the rig has them, and authors new geometry under those bones. The result is written to
``world_interface.bbmodel``, which is the editing source from then on - re-running this script
overwrites hand edits, so it is for starting over, not for iterating.

Coordinates are Blockbench's: Y up, the boss faces -Z, one unit is a sixteenth of a block before
the form scale. Every helper takes positions local to the bone it builds under.
"""

from __future__ import annotations

import math
import random
from pathlib import Path

from world_interface_model import ART_DIR, Cube, Group, Model, load, pack, save

RUNTIME = ART_DIR / "world_interface_runtime.bbmodel"
OUT = ART_DIR / "world_interface.bbmodel"
RESOLUTION = (512, 256)

# Bones the rig poses or the renderer addresses. Everything else in the runtime export is
# bake-time clutter and is rebuilt here.
ANIMATED = {"hover", "storm_body", "shell_base", "phase_2_accretion", "phase_3_accretion",
            "interface_kernel", "kernel_glow", "weapon"}
for _head in ("center", "left", "right"):
    ANIMATED.update(f"{_head}_{link}" for link in ("head_mount", "neck_a", "neck_b", "skull", "jaw", "eye_0"))
for _limb in range(10):
    ANIMATED.update(f"tendril_{_limb}{suffix}" for suffix in ("", "_mid", "_tip", "_glow"))
    ANIMATED.update(f"tendril_{_limb}{suffix}_flex_{joint}"
                    for suffix in ("", "_mid", "_tip") for joint in range(1, 6))

# Half-width, top and bottom of the mass per form, local to storm_body, from WorldInterfaceAnatomy
# (MASS_HALF_WIDTH_UNITS, MASS_TOP_UNITS, MASS_BOTTOM_UNITS with the hover lift taken off).
MASS_HALF = (9.0, 13.0, 16.5)
MASS_TOP = (22.6, 27.0, 30.3)
MASS_BOTTOM = (-10.4, -10.4, -12.4)
KERNEL = (0.0, 13.0, -2.0)

rng = random.Random(0x4649)


def jitter(spread: float) -> float:
    return rng.uniform(-spread, spread)


# ------------------------------------------------------------------------------------------------
# Authoring helpers. `at` is the cube's minimum corner, `centre` its centre, both local to the bone.
# ------------------------------------------------------------------------------------------------

# Rock sizes are snapped to this grid so cubes of one material share islands exactly.
ROCK = {"endstone", "obsidian", "swallowed", "plating", "root"}
SNAP = 0.5


def box(group: Group, name: str, at, size, rot=None, pivot=None, mirror=False, inflate=0.0) -> Cube:
    if name.split(".", 1)[0] in ROCK:
        size = [max(SNAP, round(v / SNAP) * SNAP) for v in size]
    o = group.origin
    frm = [o[0] + at[0], o[1] + at[1], o[2] + at[2]]
    to = [frm[0] + size[0], frm[1] + size[1], frm[2] + size[2]]
    if pivot is None:
        origin = [(frm[i] + to[i]) / 2 for i in range(3)]
    else:
        origin = [o[0] + pivot[0], o[1] + pivot[1], o[2] + pivot[2]]
    cube = Cube(name, frm, to, origin, list(rot) if rot else [0.0, 0.0, 0.0], [0.0, 0.0], mirror, inflate)
    group.children.append(cube)
    return cube


def cbox(group: Group, name: str, centre, size, rot=None, pivot=None, mirror=False) -> Cube:
    at = [centre[i] - size[i] / 2 for i in range(3)]
    return box(group, name, at, size, rot, pivot, mirror)


def subgroup(parent: Group, name: str, local_pivot, rot=None) -> Group:
    o = parent.origin
    group = Group(name, [o[0] + local_pivot[0], o[1] + local_pivot[1], o[2] + local_pivot[2]],
                  list(rot) if rot else [0.0, 0.0, 0.0])
    parent.children.append(group)
    return group


def strip(group: Group) -> None:
    """Drop cubes and non-animated groups, keeping the skeleton."""
    group.children = [child for child in group.children if isinstance(child, Group) and child.name in ANIMATED]
    for child in group.children:
        strip(child)


# ------------------------------------------------------------------------------------------------
# Body: an accreted chunk of End island. Strata of end stone and obsidian, torn chunks embedded
# at angles, a crown of spires, roots trailing under, and the kernel set in a well on the front.
# ------------------------------------------------------------------------------------------------

def half_width(y: float, form: int) -> float:
    """Silhouette profile: widest a little above the middle, tapering to the underside."""
    top, bottom = MASS_TOP[form], MASS_BOTTOM[form]
    t = (y - bottom) / (top - bottom)
    swell = math.sin(min(1.0, max(0.0, t)) ** 0.8 * math.pi) ** 0.55
    return MASS_HALF[form] * (0.42 + 0.58 * swell)


def strata(shell: Group, form: int, y_lo: float, y_hi: float, thickness: float, gap: float,
           outer_scale: float, name: str, well: bool) -> None:
    """Slabs of swallowed ground, shoved off axis and turned so no two silhouettes agree.

    Mostly dark: the terrain it ate is charcoal by now, and the pale end stone survives only as
    chunks embedded in it (see ``chunks``). A regular stack of pale slabs read as a layer cake.
    """
    y = y_hi
    index = 0
    while y - thickness * 0.6 > y_lo:
        hw = half_width(y - thickness / 2, form) * outer_scale * rng.uniform(0.86, 1.14)
        material = ("swallowed", "obsidian", "swallowed", "endstone", "obsidian")[index % 5]
        thick = thickness * rng.uniform(0.7, 1.45)
        depth = hw * rng.uniform(0.72, 0.95)
        yaw = jitter(22.0)
        tilt = (jitter(7.0), jitter(7.0))
        dx, dz = jitter(hw * 0.22), jitter(hw * 0.18)
        # In the well band the front of the stratum is split so the kernel recess stays open.
        if well and -5.2 <= (y - thick / 2) - KERNEL[1] <= 5.2:
            for side in (-1, 1):
                w = hw * 0.55
                cbox(shell, f"{material}.{name}_{index}_{'l' if side < 0 else 'r'}",
                     (dx + side * (hw - w / 2 + 0.3), y - thick / 2, dz),
                     (w, thick, depth * 2), (tilt[0], yaw * 0.4, tilt[1]))
            cbox(shell, f"{material}.{name}_{index}_back", (dx, y - thick / 2, dz + depth * 0.5 + 1.0),
                 (hw * 1.9, thick, depth * 0.95), (tilt[0], yaw * 0.3, tilt[1]))
        else:
            cbox(shell, f"{material}.{name}_{index}", (dx, y - thick / 2, dz),
                 (hw * 2, thick, depth * 2), (tilt[0], yaw, tilt[1]))
        # A shard of the same slab that stuck out further than the rest.
        if rng.random() < 0.8:
            angle = rng.uniform(0, math.tau)
            bw = hw * rng.uniform(0.5, 0.8)
            cbox(shell, f"{material}.{name}_{index}_shard",
                 (dx + math.cos(angle) * hw * 0.66, y - thick / 2 + jitter(0.8), dz + math.sin(angle) * depth * 0.66),
                 (bw, thick * rng.uniform(0.6, 1.1), bw * rng.uniform(0.6, 1.0)),
                 (jitter(14), math.degrees(-angle) + jitter(25), jitter(14)))
        y -= thick + gap
        index += 1


def lobes(shell: Group, form: int, count: int, name: str, outer_scale: float) -> None:
    """Rounded bulk swelling off the flanks: three stacked, offset blocks each, so the mass has
    lumps rather than a profile."""
    for index in range(count):
        angle = index * math.tau / count + math.pi / count + jitter(0.5)
        y = MASS_BOTTOM[form] + (MASS_TOP[form] - MASS_BOTTOM[form]) * rng.uniform(0.3, 0.72)
        hw = half_width(y, form) * outer_scale
        r = hw * rng.uniform(0.55, 0.7)
        base_size = hw * rng.uniform(0.7, 0.95)
        cx, cz = math.cos(angle) * hw * 0.55, math.sin(angle) * hw * 0.5
        material = rng.choice(("swallowed", "swallowed", "obsidian"))
        for tier, (scale, lift) in enumerate(((1.0, 0.0), (0.82, base_size * 0.42), (0.6, base_size * 0.72))):
            cbox(shell, f"{material}.{name}_{index}_{tier}",
                 (cx + jitter(0.8), y + lift - base_size * 0.2, cz + jitter(0.8)),
                 (base_size * scale, base_size * 0.5, base_size * scale * rng.uniform(0.8, 1.0)),
                 (jitter(8), math.degrees(-angle) + jitter(25), jitter(8)))


def wedges(shell: Group, form: int, count: int, name: str, outer_scale: float) -> None:
    """Long obsidian shards driven through the mass at steep angles, both ends showing."""
    for index in range(count):
        angle = index * math.tau / count + jitter(0.6)
        y = MASS_BOTTOM[form] + (MASS_TOP[form] - MASS_BOTTOM[form]) * rng.uniform(0.25, 0.8)
        hw = half_width(y, form) * outer_scale
        length = hw * rng.uniform(1.6, 2.3)
        thick = rng.uniform(1.6, 2.8)
        cbox(shell, f"obsidian.{name}_{index}", (math.cos(angle) * hw * 0.35, y, math.sin(angle) * hw * 0.3),
             (thick, length, thick * rng.uniform(0.8, 1.4)),
             (rng.uniform(25, 50) * rng.choice((-1, 1)), math.degrees(-angle) + jitter(30), rng.uniform(15, 40) * rng.choice((-1, 1))))


def chunks(shell: Group, form: int, count: int, name: str, size_lo: float, size_hi: float,
           y_lo: float, y_hi: float, outer_scale: float) -> None:
    """Torn blocks of terrain embedded in the surface at angles the ground never had."""
    for index in range(count):
        y = rng.uniform(y_lo, y_hi)
        hw = half_width(y, form) * outer_scale
        angle = rng.uniform(0, math.tau)
        s = rng.uniform(size_lo, size_hi)
        material = rng.choice(("endstone", "endstone", "endstone", "obsidian"))
        centre = (math.cos(angle) * hw * 0.74, y, math.sin(angle) * hw * 0.66)
        cbox(shell, f"{material}.{name}_{index}", centre,
             (s, s * rng.uniform(0.55, 0.9), s * rng.uniform(0.7, 1.1)),
             (jitter(28), math.degrees(-angle) + jitter(30), jitter(28)))


def plates(shell: Group, form: int, count: int, name: str, y_lo: float, y_hi: float, outer_scale: float) -> None:
    """Thin dark plates lifted off the shell: the storm armouring itself with what it ate."""
    for index in range(count):
        y = rng.uniform(y_lo, y_hi)
        hw = half_width(y, form) * outer_scale
        angle = rng.uniform(0, math.tau)
        s = rng.uniform(2.2, 4.6)
        centre = (math.cos(angle) * hw * 1.02, y, math.sin(angle) * hw * 0.9)
        cbox(shell, f"plating.{name}_{index}", centre, (s, s * 0.7, 0.7),
             (jitter(14), math.degrees(-angle) - 90 + jitter(20), jitter(10)))


def crown(shell: Group, form: int, count: int, name: str, height_lo: float, height_hi: float) -> None:
    top = MASS_TOP[form]
    # The torn surface of the island it was: flat pale plates lifted and tilted off the top.
    for index in range(count + 1):
        angle = index * math.tau / (count + 1) + jitter(0.4)
        r = MASS_HALF[form] * rng.uniform(0.25, 0.55)
        w = MASS_HALF[form] * rng.uniform(0.55, 0.9)
        cbox(shell, f"endstone.{name}_cap_{index}", (math.cos(angle) * r, top - 1.6 + jitter(1.0), math.sin(angle) * r * 0.8),
             (w, 1.4, w * rng.uniform(0.6, 0.9)), (math.sin(angle) * 16 + jitter(8), jitter(40), -math.cos(angle) * 16 + jitter(8)))
    for index in range(count):
        angle = index * math.tau / count + jitter(0.5)
        r = MASS_HALF[form] * rng.uniform(0.15, 0.6)
        h = rng.uniform(height_lo, height_hi)
        w = rng.uniform(2.4, 4.8)
        centre = (math.cos(angle) * r, top - 2.0 + h / 2, math.sin(angle) * r * 0.8)
        cbox(shell, f"{'endstone' if index % 2 == 0 else 'obsidian'}.{name}_{index}", centre, (w, h, w * rng.uniform(0.7, 1.0)),
             (jitter(22) + math.sin(angle) * 14, jitter(45), jitter(22) - math.cos(angle) * 14))
        cbox(shell, f"obsidian.{name}_{index}_cap", (centre[0] + jitter(0.6), centre[1] + h / 2 - 0.4, centre[2] + jitter(0.6)),
             (w * 0.7, 1.2, w * 0.6), (jitter(12), jitter(45), jitter(12)))


def roots(shell: Group, form: int, count: int, name: str, length: float, outer_scale: float) -> None:
    bottom = MASS_BOTTOM[form]
    for index in range(count):
        angle = index * math.tau / count + jitter(0.4)
        r = half_width(bottom + 3.0, form) * outer_scale * rng.uniform(0.35, 0.85)
        x, z = math.cos(angle) * r, math.sin(angle) * r * 0.8
        lean = (math.sin(angle) * 22 + jitter(8), 0.0, -math.cos(angle) * 22 + jitter(8))
        thick = rng.uniform(1.0, 1.8)
        seg = length * rng.uniform(0.8, 1.2)
        root = subgroup(shell, f"{name}_{index}", (x, bottom + 2.5, z), lean)
        box(root, f"root.{name}_{index}_a", (-thick / 2, -seg, -thick / 2), (thick, seg + 2.0, thick))
        tip = subgroup(root, f"{name}_{index}_b", (0.0, -seg, 0.0), (jitter(14) + 8, 0.0, jitter(14)))
        box(tip, f"root.{name}_{index}_b", (-thick * 0.32, -seg * 0.7, -thick * 0.32), (thick * 0.64, seg * 0.7, thick * 0.64))


def ribs(shell: Group, form: int, count: int, name: str, y_lo: float, y_hi: float, outer_scale: float) -> None:
    """Bone breaking the surface in pairs: whatever this was before it was terrain."""
    for index in range(count):
        side = -1 if index % 2 else 1
        row = index // 2
        y = y_lo + (y_hi - y_lo) * (row + 0.5) / max(1, count // 2)
        hw = half_width(y, form) * outer_scale
        length = hw * rng.uniform(0.9, 1.25)
        thick = rng.uniform(0.9, 1.4)
        rib = subgroup(shell, f"{name}_{index}", (side * hw * 0.55, y, jitter(2.0)),
                       (jitter(10), side * (-70 + jitter(12)), side * (-28 + jitter(10))))
        box(rib, f"bone.{name}_{index}_a", (0.0, -thick / 2, -thick / 2), (length * 0.55, thick, thick))
        outer = subgroup(rib, f"{name}_{index}_b", (length * 0.55, 0.0, 0.0), (0.0, 0.0, side * 38))
        box(outer, f"bone.{name}_{index}_b", (0.0, -thick * 0.4, -thick * 0.4), (length * 0.5, thick * 0.8, thick * 0.8))


def kernel(interface: Group, glow: Group) -> None:
    """The interface itself, at the bottom of a square well cut into the front of the mass."""
    half = 4.4
    depth = -1.4
    for ring in range(3):
        h = half * (1.0 - ring * 0.2)
        bar = 0.9
        z = depth + ring * 1.5
        box(interface, f"plating.kernel_frame_{ring}_top", (-(h + bar), h, z), ((h + bar) * 2, bar, 1.2))
        box(interface, f"plating.kernel_frame_{ring}_bottom", (-(h + bar), -(h + bar), z), ((h + bar) * 2, bar, 1.2))
        box(interface, f"plating.kernel_frame_{ring}_left", (-(h + bar), -h, z), (bar, h * 2, 1.2))
        box(interface, f"plating.kernel_frame_{ring}_right", (h, -h, z), (bar, h * 2, 1.2))
    # Cabling out of the well into the mass.
    for index in range(4):
        angle = index * math.pi / 2 + math.pi / 4
        cbox(interface, f"obsidian.kernel_conduit_{index}",
             (math.cos(angle) * (half + 1.6), math.sin(angle) * (half + 1.6), depth + 2.6),
             (1.4, 1.4, 4.8), (0.0, 0.0, math.degrees(angle)))
    # The lattice: recessed, and the only lit thing inside the body.
    box(glow, "core.kernel_lattice", (-half * 0.62, -half * 0.62, depth + 3.6), (half * 1.24, half * 1.24, 0.6))
    for index in range(4):
        angle = index * 90.0
        cbox(glow, f"core.kernel_trace_{index}", (math.cos(math.radians(angle)) * half * 0.86, math.sin(math.radians(angle)) * half * 0.86, depth + 3.4),
             (0.7, half, 0.4), (0.0, 0.0, angle))
    cbox(glow, "core.kernel_heart", (0.0, 0.0, depth + 3.0), (1.8, 1.8, 1.8), (45.0, 0.0, 45.0))


def build_body(body: Group) -> None:
    base = body.find("shell_base")
    p2 = body.find("phase_2_accretion")
    p3 = body.find("phase_3_accretion")
    # Base shell: the whole first-form mass.
    mid = (MASS_TOP[0] + MASS_BOTTOM[0]) / 2
    cbox(base, "obsidian.core_mass_a", (0.4, mid - 1.0, 0.3),
         (MASS_HALF[0] * 1.5, MASS_TOP[0] - MASS_BOTTOM[0] - 6.0, MASS_HALF[0] * 1.2), (4.0, 17.0, -3.0))
    cbox(base, "swallowed.core_mass_b", (-0.6, mid + 1.5, -0.4),
         (MASS_HALF[0] * 1.25, MASS_TOP[0] - MASS_BOTTOM[0] - 9.0, MASS_HALF[0] * 1.45), (-3.0, -24.0, 5.0))
    strata(base, 0, MASS_BOTTOM[0], MASS_TOP[0] - 1.5, 3.6, 0.9, 1.0, "stratum", True)
    lobes(base, 0, 3, "lobe", 1.0)
    wedges(base, 0, 4, "wedge", 1.0)
    chunks(base, 0, 12, "chunk", 3.4, 6.2, MASS_BOTTOM[0] + 3, MASS_TOP[0] - 3, 1.0)
    plates(base, 0, 12, "plate", MASS_BOTTOM[0] + 4, MASS_TOP[0] - 2, 1.0)
    crown(base, 0, 3, "spire", 3.5, 7.0)
    ribs(base, 0, 6, "rib", 2.0, 14.0, 1.0)
    roots(base, 0, 8, "root", 7.0, 1.0)
    # Second form: an outer shell grown around the first.
    strata(p2, 1, MASS_BOTTOM[1] + 2, MASS_TOP[1] - 1.5, 4.2, 1.1, 1.0, "p2_stratum", True)
    lobes(p2, 1, 4, "p2_lobe", 1.0)
    wedges(p2, 1, 5, "p2_wedge", 1.0)
    chunks(p2, 1, 14, "p2_chunk", 4.4, 7.4, MASS_BOTTOM[1] + 4, MASS_TOP[1] - 3, 1.02)
    plates(p2, 1, 10, "p2_plate", MASS_BOTTOM[1] + 4, MASS_TOP[1] - 2, 1.0)
    crown(p2, 1, 4, "p2_spire", 4.5, 9.0)
    ribs(p2, 1, 4, "p2_rib", 4.0, 18.0, 1.0)
    roots(p2, 1, 6, "p2_root", 9.0, 1.05)
    # Third form: the outermost shell and the two subordinate knots.
    strata(p3, 2, MASS_BOTTOM[2] + 3, MASS_TOP[2] - 1.5, 4.8, 1.3, 1.0, "p3_stratum", True)
    lobes(p3, 2, 5, "p3_lobe", 1.0)
    wedges(p3, 2, 6, "p3_wedge", 1.0)
    chunks(p3, 2, 18, "p3_chunk", 5.2, 9.0, MASS_BOTTOM[2] + 5, MASS_TOP[2] - 3, 1.02)
    plates(p3, 2, 12, "p3_plate", MASS_BOTTOM[2] + 5, MASS_TOP[2] - 2, 1.0)
    crown(p3, 2, 5, "p3_spire", 5.0, 11.0)
    roots(p3, 2, 6, "p3_root", 11.0, 1.05)
    for name, (x, y, z, r) in (("knot_left", (-15.5, 14.0, 4.0, 5.2)), ("knot_right", (15.5, 11.0, 6.5, 4.6))):
        knot = subgroup(p3, name, (x, y, z), (jitter(20), jitter(40), jitter(20)))
        for index in range(5):
            angle = index * math.tau / 5
            s = r * rng.uniform(0.6, 1.0)
            cbox(knot, f"{'obsidian' if index % 2 else 'endstone'}.{name}_{index}",
                 (math.cos(angle) * r * 0.45, math.sin(angle * 1.7) * r * 0.4, math.sin(angle) * r * 0.45),
                 (s, s * 0.8, s * 0.9), (jitter(40), jitter(40), jitter(40)))
    kernel(body.find("interface_kernel"), body.find("kernel_glow"))


# ------------------------------------------------------------------------------------------------
# Heads: block skulls on vertebral necks. All coordinates are local to the bone and scaled by the
# head's size factor; the skull faces -Z.
# ------------------------------------------------------------------------------------------------

def neck(link: Group, prefix: str, s: float, length: float, thick: float, discs: int) -> None:
    core = thick * 0.62
    box(link, f"flesh.{prefix}_core", (-core / 2, -length + 0.4, -core / 2), (core, length + 0.6, core))
    for index in range(discs):
        y = 0.3 - (index + 0.5) * (length / discs)
        d = thick * rng.uniform(0.94, 1.06)
        cbox(link, f"bone.{prefix}_vertebra_{index}", (0.0, y, 0.0), (d, thick * 0.34, d), (0.0, jitter(8), 0.0))
        cbox(link, f"bone.{prefix}_process_{index}", (0.0, y + 0.1, 0.0), (d * 0.78, thick * 0.22, d * 0.78), (0.0, 45.0, 0.0))


def skull(head: Group, jaw: Group, eye: Group, prefix: str, s: float, centre: bool) -> None:
    S = lambda *v: tuple(x * s for x in v)  # noqa: E731
    # Cranium in three tiers, wider than it is tall, with the back of the skull stepped in.
    cbox(head, f"bone.{prefix}_cranium", S(0, 1.2, 0.2), S(9.2, 6.4, 8.6))
    cbox(head, f"bone.{prefix}_dome", S(0, 4.9, 0.4), S(7.4, 1.6, 6.8))
    cbox(head, f"bone.{prefix}_occiput", S(0, 1.0, 4.9), S(7.6, 4.8, 1.6))
    cbox(head, f"bone.{prefix}_brow", S(0, 3.1, -5.3), S(10.6, 2.4, 3.2))
    cbox(head, f"bone.{prefix}_brow_ledge", S(0, 2.1, -6.4), S(8.8, 0.9, 1.4), (12.0, 0.0, 0.0))
    cbox(head, f"obsidian.{prefix}_brow_scar", S(0, 4.3, -6.1), S(6.0, 0.5, 1.2))
    for side in (-1, 1):
        cbox(head, f"bone.{prefix}_brow_horn_{side}", S(side * 5.4, 3.4, -5.0), S(1.4, 1.6, 2.4),
             (0.0, side * -22.0, side * 32.0))
        cbox(head, f"bone.{prefix}_cheek_{side}", S(side * 5.3, -0.6, -2.6), S(1.7, 4.6, 5.2), (0.0, side * 14.0, 0.0))
        cbox(head, f"bone.{prefix}_cheek_ridge_{side}", S(side * 5.9, -0.2, -4.4), S(1.0, 1.4, 2.0), (0.0, side * 20.0, 0.0))
        cbox(head, f"bone.{prefix}_temple_{side}", S(side * 4.9, 3.0, 1.6), S(1.2, 3.2, 4.0))
    # The socket: recessed under the brow, between the cheeks. Dark, so the aperture reads as
    # something looking out of a hole rather than a lamp on a face.
    cbox(head, f"socket.{prefix}_socket", S(0, 0.5, -4.3), S(7.0, 4.6, 1.2))
    cbox(head, f"socket.{prefix}_socket_deep", S(0, 0.1, -4.9), S(5.4, 3.0, 0.6))
    # Muzzle and upper teeth.
    cbox(head, f"bone.{prefix}_maxilla", S(0, -2.9, -2.4), S(8.4, 2.6, 7.4))
    cbox(head, f"bone.{prefix}_snout", S(0, -2.3, -6.4), S(5.8, 2.0, 1.8))
    cbox(head, f"socket.{prefix}_nasal", S(0, -1.6, -5.65), S(1.6, 1.4, 0.6))
    for index in range(5):
        x = (index - 2) * 1.75
        h = 1.6 if index % 2 == 0 else 1.15
        cbox(head, f"tooth.{prefix}_upper_tooth_{index}", S(x, -4.2 - h / 2 + 0.2, -4.6), S(0.9, h, 0.9),
             (jitter(6) - 4, 0.0, (index - 2) * 3.0))
    # Lower jaw. Its pivot hangs 3.4s under the skull's; the mandible reaches forward under the muzzle.
    cbox(jaw, f"bone.{prefix}_mandible", S(0, -1.7, -2.4), S(7.8, 2.2, 7.0))
    cbox(jaw, f"bone.{prefix}_chin", S(0, -2.5, -5.2), S(4.2, 1.5, 2.2))
    for side in (-1, 1):
        cbox(jaw, f"bone.{prefix}_ramus_{side}", S(side * 4.3, -1.0, 0.4), S(1.3, 3.4, 4.6), (0.0, side * 9.0, 0.0))
    for index in range(4):
        x = (index - 1.5) * 1.9
        h = 1.5 if index % 2 == 0 else 1.05
        cbox(jaw, f"tooth.{prefix}_lower_tooth_{index}", S(x, -0.6 + h / 2 - 0.2, -4.8), S(0.85, h, 0.85),
             (jitter(6) + 3, 0.0, (index - 1.5) * -3.0))
    # The aperture, on its own bone so the emissive pass can submit it alone.
    # A slit, wider than tall: an aperture, not an eyeball.
    cbox(eye, f"eye.{prefix}_aperture", S(0, -0.6, 0.35), S(5.0, 2.2, 1.0))
    cbox(eye, f"core.{prefix}_iris", S(0, -0.6, -0.35), S(2.4, 1.1, 0.6))
    if centre:
        for side in (-1, 1):
            tag = 'l' if side < 0 else 'r'
            horn = subgroup(head, f"{prefix}_horn_{tag}", S(side * 3.6, 5.2, 2.2), (-18.0, 0.0, side * 34.0))
            box(horn, f"horn.{prefix}_horn_{tag}_a", S(-0.9, -0.4, -0.9), S(1.8, 3.6, 1.8))
            mid = subgroup(horn, f"{prefix}_horn_{tag}_b", S(0, 3.1, 0), (-26.0, 0.0, side * 18.0))
            box(mid, f"horn.{prefix}_horn_{tag}_b", S(-0.7, -0.3, -0.7), S(1.4, 3.4, 1.4))
            tip = subgroup(mid, f"{prefix}_horn_{tag}_c", S(0, 3.0, 0), (-30.0, 0.0, side * 10.0))
            box(tip, f"horn.{prefix}_horn_{tag}_c", S(-0.5, -0.2, -0.5), S(1.0, 3.2, 1.0))
    else:
        # The flanks carry a bony crest instead of horns, so the three heads read as three.
        for index in range(3):
            cbox(head, f"horn.{prefix}_crest_{index}", S(0, 5.8 + index * 0.3, 2.6 - index * 1.9), S(1.2, 2.2 + index * 0.5, 1.4),
                 (-18.0 - index * 8.0, 0.0, 0.0))


def build_heads(body: Group) -> None:
    for prefix, s, centre in (("center", 1.0, True), ("left", 0.78, False), ("right", 0.78, False)):
        neck(body.find(f"{prefix}_neck_a"), f"{prefix}_neck_a", s, 6.4 * s, 4.6 * s, 3)
        neck(body.find(f"{prefix}_neck_b"), f"{prefix}_neck_b", s, 6.4 * s, 4.0 * s, 3)
        skull(body.find(f"{prefix}_skull"), body.find(f"{prefix}_jaw"), body.find(f"{prefix}_eye_0"), prefix, s, centre)


# ------------------------------------------------------------------------------------------------
# Tentacles: three bones each, every link built from tapering segments with barbs on alternate
# sides and lit nodes on the tip.
# ------------------------------------------------------------------------------------------------

def limb_link(link: Group, prefix: str, length: float, thick_top: float, thick_end: float, segments: int, barbs: bool) -> None:
    for index in range(segments):
        t0 = index / segments
        t1 = (index + 1) / segments
        thick = thick_top + (thick_end - thick_top) * (t0 + t1) / 2
        y0 = -length * t1
        seg_len = length / segments + 0.35
        sway = math.sin((index + 0.5) / segments * math.pi) * thick * 0.35
        cbox(link, f"flesh.{prefix}_seg_{index}", (sway, y0 + seg_len / 2 - 0.2, -sway * 0.6), (thick * 2, seg_len, thick * 2),
             (index * 6.0 - 6.0, index * 22.0, 0.0))
        if barbs and index % 2 == 1:
            side = 1 if (index // 2) % 2 == 0 else -1
            cbox(link, f"horn.{prefix}_barb_{index}", (side * thick * 1.1, y0 + seg_len * 0.5, 0.0), (thick * 1.3, 0.7, 0.7),
                 (0.0, 0.0, side * -35.0))


def build_limbs(body: Group) -> None:
    for index in range(10):
        row = index // 2
        length = 11.0 + row * 1.2
        thick = 1.9
        limb_link(body.find(f"tendril_{index}"), f"tendril_{index}", length, thick, thick * 0.72, 3, True)
        limb_link(body.find(f"tendril_{index}_mid"), f"tendril_{index}_mid", length * 0.88, thick * 0.72, thick * 0.44, 3, True)
        tip = body.find(f"tendril_{index}_tip")
        limb_link(tip, f"tendril_{index}_tip", length * 0.76, thick * 0.44, thick * 0.2, 3, False)
        # A hooked end.
        cbox(tip, f"horn.tendril_{index}_hook", (0.0, -length * 0.76 - 0.6, -0.5), (0.7, 1.8, 0.7), (35.0, 0.0, 0.0))
        glow = body.find(f"tendril_{index}_glow")
        node = thick * 0.5 * 1.3
        for k, frac in enumerate((0.30, 0.55, 0.78)):
            cbox(glow, f"glow.tendril_{index}_node_{k}", (0.0, -length * 0.76 * frac, 0.0),
                 (node * (1.0 - k * 0.15), node * 0.9, node * (1.0 - k * 0.15)), (0.0, 45.0, 0.0))


def build_weapon(weapon: Group) -> None:
    box(weapon, "plating.weapon_haft", (-0.75, -4.0, -0.75), (1.5, 16.0, 1.5))
    box(weapon, "plating.weapon_guard", (-3.0, 9.5, -0.5), (6.0, 2.0, 1.0))
    box(weapon, "bone.weapon_blade", (-1.5, 11.0, -0.5), (3.0, 12.0, 1.0))
    box(weapon, "obsidian.weapon_fuller", (-2.4, 19.8, -0.35), (4.8, 1.2, 0.7))


def main() -> None:
    import sys
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT
    model = load(RUNTIME)
    for group in model.outliner:
        strip(group)
    model.resolution = RESOLUTION
    model.textures = []
    body = model.find("storm_body")
    build_body(body)
    build_heads(body)
    build_limbs(body)
    build_weapon(body.find("weapon"))
    from articulate_storm_tendrils import articulate
    articulate(model)
    islands = pack(model)
    cubes = list(model.all_cubes())
    rotated = sum(1 for cube in cubes if cube.rotated)
    save(model, out)
    print(f"wrote {out}: "
          f"{len(cubes)} cubes ({rotated} rotated), {len(list(model.walk()))} groups, {len(islands)} islands")


if __name__ == "__main__":
    main()
