#!/usr/bin/env python3
"""Blockbench-side model library for the world interface.

The ``.bbmodel`` under ``docs/art/world_interface`` is the editing source for the boss's geometry.
This module knows how to read and write that file with Blockbench's own semantics, pack box-UV
islands for it, and translate it into the Java-space geometry the runtime bakes:

* ``Model`` / ``Group`` / ``Cube`` mirror the outliner. Cube ``from``/``to`` and group ``origin``
  are absolute Blockbench coordinates (Y up, the boss faces -Z), unrotated by any ancestor, which
  is exactly how Blockbench stores them.
* ``box_faces`` is Blockbench's ``updateUV`` for box-UV cubes, copied number for number, so the
  face rectangles painted here are the ones the editor shows.
* ``to_runtime`` applies the Modded Entity codec's convention in reverse of
  ``WorldInterfaceBbmodelExport``: Blockbench pivot ``p`` relative to its parent becomes a Java
  offset of ``(-dx, -dy, dz)`` (root-level bones subtract the 24-unit lift), rotations become
  ``(-rx, -ry, rz)`` in radians, and a cube spanning ``from..to`` becomes ``addBox(pivot.x - to.x,
  pivot.y - to.y, from.z - pivot.z, size)``. A cube with its own rotation is wrapped in a synthetic
  bone, the way Blockbench's exporter emits a rotation subgroup, because a ``ModelPart`` cube
  cannot rotate on its own.

Cubes name their material with a ``material.label`` prefix; the painter reads it, and the packer
shares one island between cubes of one material and one size.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ART_DIR = ROOT / "docs/art/world_interface"
ROOT_LIFT = 24.0
FACES = ("east", "west", "up", "down", "south", "north")
# Size quantum per material for island sharing; see Cube.island_key.
ISLAND_QUANTUM = {"endstone": 0.5, "obsidian": 0.5, "swallowed": 0.5, "plating": 0.5, "root": 0.5,
                  "flesh": 0.5, "horn": 0.5, "glow": 0.5}
UPRIGHT_FACES = ("north", "south", "west", "east")


@dataclass
class Cube:
    name: str
    from_: list[float]
    to: list[float]
    origin: list[float]
    rotation: list[float] = field(default_factory=lambda: [0.0, 0.0, 0.0])
    uv: list[float] = field(default_factory=lambda: [0.0, 0.0])
    mirror: bool = False
    inflate: float = 0.0
    uuid: str = ""

    @property
    def material(self) -> str:
        return self.name.split(".", 1)[0] if "." in self.name else "plating"

    @property
    def size(self) -> tuple[float, float, float]:
        return (self.to[0] - self.from_[0], self.to[1] - self.from_[1], self.to[2] - self.from_[2])

    @property
    def rotated(self) -> bool:
        return any(abs(r) > 1e-6 for r in self.rotation)

    def island_key(self) -> tuple:
        """Cubes of one material whose sizes round up to the same quantum share an island.

        Rock is quantised to half units - a hundred torn chunks of slightly different size would
        otherwise each want a sheet of their own - and bone to a fifth, so a skull's parts keep
        their own painting. Rounding up means the island is always at least the cube's size, so
        the cube's box-UV faces never spill past the island the painter painted. The scaffold
        snaps rock sizes to the same half unit, so in practice a rock cube and its island agree
        exactly and no face samples the border of its neighbour.
        """
        quantum = ISLAND_QUANTUM.get(self.material, 0.2)
        w, h, d = (math.ceil(round(v / quantum, 6)) * quantum for v in self.size)
        return (self.material, round(w, 4), round(h, 4), round(d, 4), self.mirror)


@dataclass
class Group:
    name: str
    origin: list[float]
    rotation: list[float] = field(default_factory=lambda: [0.0, 0.0, 0.0])
    children: list = field(default_factory=list)  # Group | Cube, in outliner order
    visibility: bool = True
    uuid: str = ""

    def groups(self):
        for child in self.children:
            if isinstance(child, Group):
                yield child

    def cubes(self):
        for child in self.children:
            if isinstance(child, Cube):
                yield child

    def walk(self):
        yield self
        for child in self.groups():
            yield from child.walk()

    def all_cubes(self):
        for group in self.walk():
            yield from group.cubes()

    def find(self, name: str):
        for group in self.walk():
            if group.name == name:
                return group
        return None


@dataclass
class Model:
    outliner: list[Group]
    resolution: tuple[int, int]
    textures: list[dict] = field(default_factory=list)
    name: str = "world_interface"

    def walk(self):
        for group in self.outliner:
            yield from group.walk()

    def all_cubes(self):
        for group in self.walk():
            yield from group.cubes()

    def find(self, name: str):
        for group in self.outliner:
            found = group.find(name)
            if found is not None:
                return found
        return None


# ------------------------------------------------------------------------------------------------
# Blockbench file IO
# ------------------------------------------------------------------------------------------------

def load(path: Path) -> Model:
    data = json.loads(path.read_text(encoding="utf-8"))
    elements = {e["uuid"]: e for e in data["elements"]}

    def read_cube(e: dict) -> Cube:
        return Cube(e["name"], list(e["from"]), list(e["to"]), list(e.get("origin", [0, 0, 0])),
                    list(e.get("rotation", [0, 0, 0])), list(e.get("uv_offset", [0, 0])),
                    bool(e.get("mirror_uv", False)), float(e.get("inflate", 0.0)), e["uuid"])

    def read_group(node: dict) -> Group:
        group = Group(node["name"], list(node.get("origin", [0, 0, 0])),
                      list(node.get("rotation", [0, 0, 0])), [], node.get("visibility", True),
                      node.get("uuid", ""))
        for child in node.get("children", []):
            if isinstance(child, str):
                if child in elements:
                    group.children.append(read_cube(elements[child]))
            else:
                group.children.append(read_group(child))
        return group

    outliner = [read_group(node) for node in data["outliner"] if not isinstance(node, str)]
    resolution = (data["resolution"]["width"], data["resolution"]["height"])
    return Model(outliner, resolution, data.get("textures", []), data.get("name", "world_interface"))


def _uuid(label: str, sequence: list[int]) -> str:
    import hashlib
    digest = hashlib.md5(f"world_interface:{label}:{sequence[0]}".encode()).hexdigest()
    sequence[0] += 1
    return f"{digest[:8]}-{digest[8:12]}-4{digest[13:16]}-8{digest[17:20]}-{digest[20:]}"


def box_faces(uv: list[float], size: tuple[float, float, float], mirror: bool) -> dict[str, list[float]]:
    """Blockbench's box-UV unwrap, from ``Cube.preview_controller.updateUV``."""
    w, h, d = size
    faces = {
        "east": ([0.0, d], [d, h]),
        "west": ([d + w, d], [d, h]),
        "up": ([d + w, d], [-w, -d]),
        "down": ([d + w * 2, 0.0], [-w, d]),
        "south": ([d * 2 + w, d], [w, h]),
        "north": ([d, d], [w, h]),
    }
    if mirror:
        for face in faces.values():
            face[0][0] += face[1][0]
            face[1][0] = -face[1][0]
        faces["east"], faces["west"] = faces["west"], faces["east"]
    return {name: [f[0] + uv[0], f[1] + uv[1], f[0] + s[0] + uv[0], f[1] + s[1] + uv[1]]
            for name, (f, s) in faces.items()}


def face_rects(cube_or_key, scale: int) -> dict[str, tuple[int, int, int, int]]:
    """Pixel rectangles of each face on a sheet painted at ``scale`` texels per UV unit."""
    if isinstance(cube_or_key, Cube):
        uv, size, mirror = cube_or_key.uv, cube_or_key.size, cube_or_key.mirror
    else:
        uv, size, mirror = cube_or_key
    rects = {}
    for face, (x0, y0, x1, y1) in box_faces(uv, size, mirror).items():
        left, right = sorted((x0, x1))
        top, bottom = sorted((y0, y1))
        rects[face] = (int(round(left * scale)), int(round(top * scale)),
                       int(round(right * scale)), int(round(bottom * scale)))
    return rects


def save(model: Model, path: Path, texture_png: Path | None = None) -> None:
    sequence = [0]
    elements = []

    def write_group(group: Group) -> dict:
        children = []
        for child in group.children:
            if isinstance(child, Cube):
                cube_id = child.uuid or _uuid(child.name, sequence)
                child.uuid = cube_id
                faces = {name: {"uv": [round(v, 4) for v in rect], "texture": 0}
                         for name, rect in box_faces(child.uv, child.size, child.mirror).items()}
                elements.append({
                    "name": child.name, "box_uv": True, "rescale": False, "locked": False,
                    "render_order": "default", "allow_mirror_modeling": True,
                    "from": [round(v, 4) for v in child.from_], "to": [round(v, 4) for v in child.to],
                    "autouv": 0, "color": 0, "inflate": child.inflate,
                    "origin": [round(v, 4) for v in child.origin],
                    "rotation": [round(v, 4) for v in child.rotation],
                    "uv_offset": [round(v, 4) for v in child.uv], "mirror_uv": child.mirror,
                    "faces": faces, "type": "cube", "uuid": cube_id,
                })
                children.append(cube_id)
            else:
                children.append(write_group(child))
        group_id = group.uuid or _uuid(group.name, sequence)
        group.uuid = group_id
        return {
            "name": group.name, "origin": [round(v, 4) for v in group.origin],
            "rotation": [round(v, 4) for v in group.rotation], "color": 0, "uuid": group_id,
            "export": True, "mirror_uv": False, "isOpen": False, "locked": False,
            "visibility": group.visibility, "autouv": 0, "children": children,
        }

    outliner = [write_group(group) for group in model.outliner]
    textures = list(model.textures)
    if texture_png is not None and texture_png.exists():
        import base64
        from PIL import Image
        with Image.open(texture_png) as image:
            width, height = image.size
        textures = [{
            "path": str(texture_png.resolve()), "name": texture_png.name, "folder": "entity",
            "namespace": "thefourthfrequency", "id": "0", "width": width, "height": height,
            "uv_width": model.resolution[0], "uv_height": model.resolution[1],
            "particle": False, "render_mode": "default", "render_sides": "auto", "visible": True,
            "mode": "bitmap", "saved": True, "uuid": _uuid("texture", sequence),
            "relative_path": "../../../" + texture_png.relative_to(ROOT).as_posix(),
            "source": "data:image/png;base64," + base64.b64encode(texture_png.read_bytes()).decode(),
        }]
    data = {
        "meta": {"format_version": "4.10", "model_format": "modded_entity", "box_uv": True},
        "name": model.name, "model_identifier": model.name, "modded_entity_version": "1.17",
        "modded_entity_flip_y": True,
        "resolution": {"width": model.resolution[0], "height": model.resolution[1]},
        "elements": elements, "outliner": outliner, "textures": textures,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


# ------------------------------------------------------------------------------------------------
# UV packing
# ------------------------------------------------------------------------------------------------

@dataclass
class Island:
    key: tuple
    uv: list[float]
    cubes: list[Cube]

    @property
    def material(self) -> str:
        return self.key[0]

    @property
    def size(self) -> tuple[float, float, float]:
        return self.key[1], self.key[2], self.key[3]

    @property
    def mirror(self) -> bool:
        return self.key[4]

    @property
    def footprint(self) -> tuple[float, float]:
        w, h, d = self.size
        return 2.0 * (w + d), h + d

    @property
    def name(self) -> str:
        w, h, d = self.size
        return f"{self.material}_{w:g}x{h:g}x{d:g}{'m' if self.mirror else ''}"


def islands_of(model: Model) -> list[Island]:
    """Cubes grouped by (material, size, mirror), each group sharing the island it was packed into."""
    by_key: dict[tuple, Island] = {}
    for cube in model.all_cubes():
        key = cube.island_key()
        island = by_key.get(key)
        if island is None:
            island = by_key[key] = Island(key, list(cube.uv), [])
        island.cubes.append(cube)
    return list(by_key.values())


def pack(model: Model, padding: float = 1.0) -> list[Island]:
    """Shelf-pack every island onto the model's canvas and write the offsets back onto the cubes.

    Islands are packed tallest first, in whole UV units, with one unit of padding so that a face
    thinner than a texel never shares a column with a neighbouring island. Refuses to run past the
    canvas rather than wrapping: an island off the sheet is a part painted with nothing.
    """
    islands = islands_of(model)
    islands.sort(key=lambda island: (-island.footprint[1], -island.footprint[0], island.name))
    width, height = model.resolution
    x = 0.0
    y = 0.0
    shelf = 0.0
    for island in islands:
        fw = math.ceil(island.footprint[0]) + padding
        fh = math.ceil(island.footprint[1]) + padding
        if x + fw > width:
            x = 0.0
            y += shelf
            shelf = 0.0
        if y + fh > height:
            raise SystemExit(f"UV canvas {width}x{height} overflowed packing {island.name} at row {y}")
        island.uv = [x, y]
        for cube in island.cubes:
            cube.uv = [x, y]
        x += fw
        shelf = max(shelf, fh)
    return islands


# ------------------------------------------------------------------------------------------------
# Runtime geometry
# ------------------------------------------------------------------------------------------------

def to_runtime(model: Model) -> dict:
    """The Java-space geometry the client bakes into a ``LayerDefinition``.

    Bones are emitted parent-first with the pose relative to their parent, exactly as
    ``PartDefinition.addOrReplaceChild`` wants them. Rotated cubes get a synthetic child bone named
    ``<bone>/<cube>`` so their names can never collide with an authored bone.
    """
    bones: list[dict] = []

    def emit(group: Group, parent: Group | None, parent_name: str | None) -> None:
        if parent is None:
            pose = [-group.origin[0], ROOT_LIFT - group.origin[1], group.origin[2]]
        else:
            pose = [parent.origin[0] - group.origin[0], parent.origin[1] - group.origin[1],
                    group.origin[2] - parent.origin[2]]
        pose += [math.radians(-group.rotation[0]), math.radians(-group.rotation[1]),
                 math.radians(group.rotation[2])]
        bone = {"name": group.name, "parent": parent_name, "pose": [round(v, 5) for v in pose],
                "cubes": []}
        bones.append(bone)
        for cube in group.cubes():
            if cube.rotated:
                pivot = cube.origin
                sub = {"name": f"{group.name}/{cube.name}", "parent": group.name,
                       "pose": [round(v, 5) for v in [
                           group.origin[0] - pivot[0], group.origin[1] - pivot[1],
                           pivot[2] - group.origin[2],
                           math.radians(-cube.rotation[0]), math.radians(-cube.rotation[1]),
                           math.radians(cube.rotation[2])]],
                       "cubes": [runtime_cube(cube, pivot)]}
                bones.append(sub)
            else:
                bone["cubes"].append(runtime_cube(cube, group.origin))
        for child in group.groups():
            emit(child, group, group.name)

    for group in model.outliner:
        emit(group, None, None)
    return {"texture": [model.resolution[0], model.resolution[1]], "bones": bones}


def runtime_cube(cube: Cube, pivot: list[float]) -> dict:
    w, h, d = cube.size
    return {
        "name": cube.name,
        "origin": [round(pivot[0] - cube.to[0], 4), round(pivot[1] - cube.to[1], 4),
                   round(cube.from_[2] - pivot[2], 4)],
        "size": [round(w, 4), round(h, 4), round(d, 4)],
        "uv": [round(cube.uv[0], 4), round(cube.uv[1], 4)],
        "mirror": cube.mirror,
        "inflate": cube.inflate,
    }


def bone_names(model: Model) -> list[str]:
    return [group.name for group in model.walk()]
