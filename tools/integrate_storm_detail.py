#!/usr/bin/env python3
"""Retarget the detailed MCP storm sculpt onto the shared gameplay rig and its 18-link limbs.

Keeps calibrated head/neck pivots and the server's physical reach. The sculpt supplies the
accreted terrain, facial anatomy and articulated limb shells; UVs are repacked by the exporter.
"""
import copy,json,math
from world_interface_model import ART_DIR,Group,Cube,load,save

def main():
    model=load(ART_DIR/'world_interface.bbmodel')
    source=json.loads((ART_DIR/'world_interface_storm_form3_animation_v3.bbmodel').read_text(encoding='utf-8'))
    elements={e['uuid']:e for e in source['elements']}
    definitions={g['uuid']:g for g in source['groups']}
    materials=['obsidian','root','plating','swallowed','bone','endstone','glow','eye','flesh','core','socket','horn']
    def read(node):
        raw=definitions[node['uuid']]
        group=Group(raw['name'],raw['origin'],raw.get('rotation',[0,0,0]))
        for child in node.get('children',[]):
            if isinstance(child,dict):group.children.append(read(child))
            else:
                e=elements[child];tile=int(e['faces']['north']['uv'][0]//32)
                group.children.append(Cube(materials[tile]+'.'+e['name'],e['from'],e['to'],e.get('origin',[0,0,0]),e.get('rotation',[0,0,0])))
        return group
    sculpt=read(source['outliner'][0]);serial=[0]
    def transplant(src,dest,scale,pivot=None):
        pivot=pivot or src.origin
        def point(p):return [dest.origin[i]+(p[i]-pivot[i])*scale[i] for i in range(3)]
        def child(c):
            if isinstance(c,Cube):
                return Cube(c.name,point(c.from_),point(c.to),point(c.origin),c.rotation.copy())
            serial[0]+=1
            g=Group('sculpt_'+str(serial[0])+'_'+c.name,point(c.origin),c.rotation.copy())
            g.children=[child(k) for k in c.children]
            return g
        dest.children.extend(child(c) for c in src.children)
    for srcName,dstName in [('body_mass','shell_base'),('phase_2_accretion','phase_2_accretion'),('phase_3_rupture','phase_3_accretion')]:
        dst=model.find(dstName);dst.children=[]
        # Preserve the mass's anchoring while accumulating the same authored terrain in every phase.
        transplant(sculpt.find(srcName),dst,[.19,.19,.19],[0,100,0])
    for name in ['center','left','right']:
        src=sculpt.find('head_'+name);skull=model.find(name+'_skull');jaw=model.find(name+'_jaw');eye=model.find(name+'_eye_0')
        skull.children=[jaw,eye];jaw.children=[];eye.children=[]
        headSrc=copy.deepcopy(src);headSrc.children=[c for c in headSrc.children if isinstance(c,Cube)]
        transplant(headSrc,skull,[.40,.40,.40])
        transplant(src.find('jaw'),jaw,[.40,.40,.40])
        for c in list(skull.cubes()):
            if 'eye_slit' in c.name or 'iris_glow' in c.name:
                skull.children.remove(c);eye.children.append(c)
    limbs=list(sculpt.find('main_tentacles').groups())
    for limb in range(10):
        roots=[model.find(f'tendril_{limb}{suffix}') for suffix in ('','_mid','_tip')]
        glow=model.find(f'tendril_{limb}_glow')
        for link,root in enumerate(roots):
            root.children=[];parent=root
            length=(11+limb//2*1.2)*[1,.88,.76][link]
            bones=[root]
            for j in range(1,6):
                group=Group(root.name+f'_flex_{j}',[root.origin[0],root.origin[1]-length*j/6,root.origin[2]])
                parent.children.append(group);parent=group;bones.append(group)
            if link<2:parent.children.append(roots[link+1])
            else:parent.children.append(glow)
            segments=[g for g in limbs[limb].walk() if g.name.startswith('segment_')]
            for j,bone in enumerate(bones):
                segment=segments[link*6+j];shell=next(segment.groups())
                # Shell geometry is authored along local -Y; bend belongs to the shared skeleton.
                cs=list(shell.cubes());span=max(c.to[1] for c in cs)-min(c.from_[1] for c in cs)
                thickness=max(c.to[0] for c in cs)-min(c.from_[0] for c in cs)
                targetThick=1.8*(1-(link*6+j)/22)**.8
                transplant(shell,bone,[targetThick/thickness,length/6/max(.01,span),targetThick/thickness])
    save(model,ART_DIR/'world_interface.bbmodel')
    print('Integrated storm sculpt:',sum(1 for _ in model.all_cubes()),'cubes;',len(list(model.walk())),'bones; 18 links per limb')

if __name__=='__main__':main()
