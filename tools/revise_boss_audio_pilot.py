#!/usr/bin/env python3
"""Second audition: high-energy laser and massive physical limb, from CC0 sources."""
import json
from pathlib import Path
import zipfile
import build_audio_pilot as base
import numpy as np
from scipy.signal import resample_poly
import soundfile as sf

OUT = base.DELIVERY / 'revision_02'
SCIFI = ('Kenney', 'https://kenney.nl/assets/sci-fi-sounds',
         'https://kenney.nl/media/pages/assets/sci-fi-sounds/6b296f9ecf-1677589334/kenney_sci-fi-sounds.zip')
base.PACKS['kenney_scifi'] = SCIFI


def shape(event, points):
    x, record = base.LAYERS[event][-1]
    envelope = np.interp(np.arange(len(x))/base.RATE, [p[0] for p in points], [p[1] for p in points])
    record['envelope_seconds_gain'] = points
    base.LAYERS[event][-1] = (x*envelope, record)


def recipes():
    L = base.layer
    e='world_interface_laser'
    L(e,'capacitor','kenney_scifi','Audio/engineCircular_002.ogg',gain=.8,hp=450,lp=7800)
    x,record=base.LAYERS[e][-1]
    n=round(4.5*base.RATE)
    rate=np.linspace(.55,1.55,n)
    cursor=np.cumsum(rate)
    x=np.interp(cursor,np.arange(len(x)),x,left=0,right=0)
    record['progressive_playback_rate']=[.55,1.55]
    base.LAYERS[e][-1]=(x,record)
    shape(e,[(0,.03),(1,.13),(2.8,.38),(4,.8),(4.46,1),(4.5,.35)])
    L(e,'power_bank','kenney_scifi','Audio/spaceEngineLow_002.ogg',length=4.6,gain=.4,hp=60,lp=900)
    shape(e,[(0,.07),(2.5,.22),(4.4,.75),(4.5,.1)])
    L(e,'final_lock','kenney_scifi','Audio/forceField_001.ogg',speed=1.8,gain=.2,offset=3.95,hp=1200,lp=8500)
    e='world_interface_laser_fire'
    L(e,'arc_release','kenney_scifi','Audio/laserLarge_000.ogg',speed=.82,gain=.95,hp=230,lp=10500)
    L(e,'pressure_front','kenney_scifi','Audio/lowFrequency_explosion_001.ogg',speed=.85,gain=.54,hp=35,lp=250)
    L(e,'ion_tail','kenney_scifi','Audio/thrusterFire_001.ogg',length=1.5,gain=.3,offset=.02,hp=900,lp=9000)
    shape(e,[(0,1),(.1,.6),(.6,.15),(1.4,0)])
    e='world_interface_laser_loop'
    L(e,'cutting_plasma','kenney_scifi','Audio/thrusterFire_002.ogg',start=.5,length=3.4,gain=.7,hp=600,lp=10500)
    L(e,'focused_carrier','kenney_scifi','Audio/engineCircular_001.ogg',start=.4,length=3.3,gain=.48,hp=180,lp=4300)
    L(e,'power_core','kenney_scifi','Audio/spaceEngineLow_001.ogg',start=.6,length=3.3,gain=.23,hp=45,lp=300)
    e='world_interface_laser_impact'
    L(e,'fracture','kenney_scifi','Audio/explosionCrunch_000.ogg',gain=.72,hp=180,lp=8500)
    L(e,'hot_shards','kenney','Audio/impactMining_003.ogg',gain=.35,offset=.025,hp=1600)
    e='world_interface_laser_release'
    L(e,'power_tail','kenney_scifi','Audio/laserLarge_003.ogg',speed=.8,gain=.65,hp=350,lp=7000)
    shape(e,[(0,.7),(.15,.45),(.65,0)])
    L(e,'spent_air','kenney_scifi','Audio/thrusterFire_002.ogg',start=2,length=.7,gain=.25,hp=1000,lp=6000)
    shape(e,[(0,.7),(.15,.5),(.65,0)])

    e='world_interface_tendril'
    L(e,'structural_load','owlish','Impacts/scrape2.wav',speed=.64,gain=.22,hp=90,lp=2400)
    shape(e,[(0,.4),(.6,.7),(1,0)])
    L(e,'displaced_air','kenney_scifi','Audio/thrusterFire_000.ogg',start=1,length=.85,speed=.75,
      gain=.9,offset=.48,hp=65,lp=2100)
    shape(e,[(0,0),(.25,.08),(.65,.65),(.93,1),(1.12,.2)])
    L(e,'edge_whoosh','owlish','Cloth, Rustle/320138__owlstorm__blanket-movement-2.wav',start=.35,length=.55,
      speed=.95,gain=.26,offset=.95,hp=650,lp=6500)
    e='world_interface_tendril_strike'
    L(e,'solid_contact','kenney','Audio/impactWood_heavy_001.ogg',speed=.52,gain=.95,hp=40,lp=4200)
    L(e,'ground_mass','kenney_scifi','Audio/lowFrequency_explosion_000.ogg',length=1.15,
      gain=.68,hp=28,lp=180)
    shape(e,[(0,.85),(.07,1),(.4,.45),(1.1,0)])
    L(e,'stone_break','kenney_scifi','Audio/explosionCrunch_003.ogg',length=.85,gain=.42,offset=.025,hp=350,lp=6500)
    L(e,'falling_grit','kenney','Audio/impactMining_004.ogg',gain=.22,offset=.16,hp=1200,lp=9500)
    e='world_interface_tendril_recover'
    L(e,'weight_drag','owlish','Impacts/scrape2.wav',length=.85,speed=1.05,gain=.9,hp=70,lp=4200)
    shape(e,[(0,.5),(.15,1),(.5,.45),(.65,0)])
    L(e,'rubble_drag','kenney','Audio/impactMining_002.ogg',speed=.85,gain=.22,hp=450,lp=6000)


def measure(x):
    return float(20*np.log10(max(1e-12,np.max(abs(resample_poly(x,4,1))))))


def main():
    archive=base.WORK/'sources/kenney_scifi.zip'
    if not archive.exists():
        with base.urllib.request.urlopen(SCIFI[2],timeout=60) as response: archive.write_bytes(response.read())
    with zipfile.ZipFile(archive) as z:
        if z.testzip() is not None: raise ValueError('Corrupt sci-fi archive')
    specs=[c for c in json.loads((base.DELIVERY/'cues.json').read_text())['cues']
           if c['group'] in ('mouth_laser','tendril_strike')]
    recipes()
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'samples').mkdir(exist_ok=True)
    report=dict(status='awaiting_listening_feedback',method='CC0 sample editing; no AI generation',sources={},events={},comparisons={})
    for pack in {record['pack'] for layers in base.LAYERS.values() for _,record in layers}:
        author,page,url=base.PACKS[pack]
        report['sources'][pack]=dict(author=author,page=page,download=url,license='CC0-1.0',
                                    sha256=base.digest((base.WORK/'sources'/(pack+'.zip')).read_bytes()))
    for cue in specs:
        event=cue['event']; duration=1.6 if event=='world_interface_tendril' else cue['target_seconds']
        n=round(duration*base.RATE); overlap=round(.12*base.RATE) if cue['loop'] else 0
        stems=[]
        for x,record in base.LAYERS[event]:
            track=np.zeros(n+overlap); a=round(record['offset_seconds']*base.RATE); b=min(len(track),a+len(x))
            track[a:b]=x[:b-a]*record['gain']
            if overlap:
                t=np.linspace(0,1,overlap)
                track[:overlap]=track[n:n+overlap]*(1-t)+track[:overlap]*t
                track=track[:n]
                # Correct the residual endpoint step over 5 ms without fading the loop to silence.
                width=round(.005*base.RATE); blend=np.linspace(0,1,width)**2
                midpoint=(track[0]+track[-1])*.5
                track[:width]+=(midpoint-track[0])*blend[::-1]
                track[-width:]+=(midpoint-track[-1])*blend
            else: track=base.fade(track,attack=.001,release=.02)
            stems.append(track)
        mix=np.sum(stems,axis=0)
        target=-7 if cue['loop'] else -4
        gain=10**((target-measure(mix))/20)
        mix*=gain; base.OUTPUTS[event]=mix
        base.encode(OUT/'samples'/(event+'.ogg'),mix)
        x,sr=sf.read(OUT/'samples'/(event+'.ogg'))
        assert x.ndim==1 and sr==base.RATE and len(x)==n and np.isfinite(x).all() and measure(x)<-1
        seam=float(abs(x[0]-x[-1])) if overlap else None
        if overlap: assert seam<.02,seam
        report['events'][event]=dict(duration_seconds=duration,true_peak_dbfs=measure(x),loop_seam=seam,
                                     sha256=base.digest((OUT/'samples'/(event+'.ogg')).read_bytes()),layers=[r for _,r in base.LAYERS[event]])
        for track,(_,record) in zip(stems,base.LAYERS[event]):
            p=base.WORK/'revision_02/stems'/event/(record['label']+'.wav');p.parent.mkdir(parents=True,exist_ok=True)
            sf.write(p,track*gain,base.RATE,subtype='FLOAT')
    scenes=[('mouth_laser',['world_interface_laser','world_interface_laser_fire','world_interface_laser_loop','world_interface_laser_impact','world_interface_laser_release'],[0,4.5,4.5,5.1,6.5],7.4),
            ('tendril_strike',['world_interface_tendril','world_interface_tendril_strike','world_interface_tendril_recover'],[0,1.6,1.85],2.8)]
    for name,events,times,duration in scenes:
        revised=base.scene(events,times,duration)
        old_outputs=base.OUTPUTS.copy()
        for e in events: base.OUTPUTS[e]=sf.read(base.DELIVERY/'samples'/(e+'.ogg'))[0]
        previous=base.scene(events,times,duration)
        base.OUTPUTS.update(old_outputs)
        versions=[previous,revised]; gains=[10**((-22-base.lufs(x))/20) for x in versions]
        peak=max(measure(x*g) for x,g in zip(versions,gains)); common=min(1,10**((-3-peak)/20))
        pair=[]; levels=[]
        for x,g in zip(versions,gains):
            x=x*g*common; levels.append(base.lufs(x)); pair.extend([x,np.zeros(round(.65*base.RATE))])
        base.encode(OUT/(name+'_AB.ogg'),np.concatenate(pair))
        base.encode(OUT/(name+'_new.ogg'),revised*gains[1]*common)
        assert abs(levels[0]-levels[1])<.1,levels
        report['comparisons'][name]=dict(order=['previous_pilot','revision_02'],integrated_lufs=levels,
                                          note='Equal-loudness near-field edit, not a game capture.')
    base.write_json(OUT/'manifest.json',report)
    print('Revision 02: 8 cue exports, 2 matched-loudness pairs; finite/peak/duration/loop checks passed.')


if __name__=='__main__': main()
