#!/usr/bin/env python3
"""Export MCP-edited native Blockbench entity assets, retaining local animation names and UVs."""
import json
from pathlib import Path
from world_interface_model import ROOT, Group, Model, Cube, to_runtime

ART = ROOT / 'docs/art/entities'
OUT = ROOT / 'src/main/resources/assets/thefourthfrequency/models/entity'

def export(path):
    data = json.loads(path.read_text(encoding='utf-8'))
    elements = {e['uuid']: e for e in data['elements']}
    groups = {g['uuid']: g for g in data.get('groups', [])}
    local_names = {}
    def group(node, parent=''):
        raw = groups.get(node['uuid'], node)
        name = parent + '/' + raw['name'] if parent else raw['name']
        local_names[name] = raw['name']
        result = Group(name, raw.get('origin', [0,0,0]), raw.get('rotation', [0,0,0]))
        for child in node.get('children', []):
            if isinstance(child, dict): result.children.append(group(child, name))
            else:
                e = elements[child]
                assert e.get('box_uv', True), (path, e['name'], 'requires box UV')
                result.children.append(Cube(e['name'],e['from'],e['to'],e.get('origin',[0,0,0]),
                    e.get('rotation',[0,0,0]),e.get('uv_offset',[0,0]),e.get('mirror_uv',False),e.get('inflate',0),e['uuid']))
        return result
    model = Model([group(n) for n in data['outliner'] if isinstance(n,dict)],
                  (data['resolution']['width'],data['resolution']['height']))
    runtime = to_runtime(model)
    for bone in runtime['bones']:
        bone['localName'] = local_names.get(bone['name'],bone['name'].split('/')[-1])
        assert all(v == v for v in bone['pose']), bone['name']
    names = [b['name'] for b in runtime['bones']]
    assert len(names) == len(set(names)), path
    OUT.mkdir(exist_ok=True,parents=True)
    (OUT/(path.stem+'.json')).write_text(json.dumps(runtime,separators=(',',':'))+'\n',encoding='utf-8')
    print(path.stem, len(names), 'bones',sum(len(b['cubes']) for b in runtime['bones']),'cubes')

if __name__ == '__main__':
    for path in sorted(ART.glob('*.bbmodel')): export(path)
