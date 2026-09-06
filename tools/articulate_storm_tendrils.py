"""Split each runtime tentacle link into six hinged sections without changing its bind pose."""
from world_interface_model import Group, Cube, load, save, ART_DIR


def articulate(model):
    for limb in range(10):
        length = 11.0 + (limb // 2) * 1.2
        for suffix, ratio, following in (("", 1.0, "_mid"), ("_mid", .88, "_tip"), ("_tip", .76, None)):
            name = f"tendril_{limb}{suffix}"
            root = model.find(name)
            span = length * ratio
            links = [root]
            for joint in range(1, 6):
                bone = model.find(f"{name}_flex_{joint}")
                if bone is None:
                    bone = Group(f"{name}_flex_{joint}",
                                 [root.origin[0], root.origin[1] - span * joint / 6, root.origin[2]])
                    links[-1].children.append(bone)
                links.append(bone)
            for cube in list(root.cubes()):
                distance = root.origin[1] - (cube.from_[1] + cube.to[1]) / 2
                section = max(0, min(5, int(distance / (span / 6))))
                if section:
                    root.children.remove(cube)
                    links[section].children.append(cube)
            if following:
                child = model.find(f"tendril_{limb}{following}")
                if child in root.children:
                    root.children.remove(child)
                    links[-1].children.append(child)


if __name__ == '__main__':
    file = ART_DIR / 'world_interface.bbmodel'
    model = load(file)
    articulate(model)
    save(model, file)
    print('Articulated 10 tentacles, 18 moving sections each; bind coordinates preserved.')
