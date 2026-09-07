#!/usr/bin/env python3
"""Rebuild every non-music event; verify decoded audio and publish reproducible manifests.

python -m pip install --target build/tff-audio-tooling -r tools/audio-requirements.txt
python tools/generate_soundscape.py [--verify-only]
The ten music events, their files and their mix values are never rewritten.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import sys
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/tff-audio-tooling'))
sys.path.insert(0, str(ROOT / '.gradle/tff-audio-tooling'))
import numpy as np
import soundfile as sf
import imageio_ffmpeg
from scipy.signal import resample_poly
from audio_materials import make, RATE
import generate_world_interface_audio as boss

ASSETS = ROOT / 'src/main/resources/assets/thefourthfrequency'
REPORT = ROOT / 'docs/art/audio/soundscape_manifest.json'
ORIGINAL_BEDS = ROOT / 'docs/art/audio/original_beds.json'
ORIGINAL_CUES = ROOT / 'docs/art/audio/original_cues.json'
# id: variants, duration, radius, Chinese subtitle, English subtitle
ADDITIONS = {
    'terminal_raise': (3,.22,16,'终端抬起','Terminal raised'),
    'terminal_lower': (3,.18,16,'终端收起','Terminal lowered'),
    'lock_search': (1,.075,16,'信号正在锁定','Signal locking'),
    'dispossess': (1,.18,16,'信号正在抽离','Signal draining'),
    'watcher_vanish': (3,1.5,64,'空气短暂塌陷','Air briefly collapses'),
    'him_presence': (3,1.15,32,'细微的衣料摩擦','Faint fabric rustle'),
    'bacteria_skitter': (4,.22,18,'密集的足部摩擦','Many feet scrape'),
    'rework_step_1': (4,.48,32,'僵硬的脚步','Rigid footsteps'),
    'rework_step_2': (4,.58,32,'错位的关节落地','Disjointed footfall'),
    'rework_step_3': (4,.65,40,'沉重的拖拽脚步','Heavy dragging footsteps'),
    'rework_breath_1': (3,1.9,24,'断续的呼吸','Broken breathing'),
    'rework_breath_2': (3,2.1,24,'胸腔摩擦','Chest cavity grinds'),
    'rework_breath_3': (3,2.4,32,'多重呼吸重叠','Layered breathing'),
    'rework_hurt': (3,.75,32,'关节崩裂','Joints fracture'),
    'rework_death': (2,2.5,40,'残躯倒下','Body collapses'),
}
BOSS_ADDITIONS = {
    'laser_loop': ('loop',1,2.8,96,'光束持续撕裂','Beam tears continuously'),
    'laser_release': ('shift',3,.7,96,'口腔能量衰退','Mouth discharge fades'),
    'laser_impact': ('impact',4,.8,72,'光束灼裂地面','Beam sears the ground'),
    'blast': ('impact',4,1.7,72,'能量腔体爆裂','Energy cavity bursts'),
    'tendril': ('warning',3,1.5,64,'触手绷紧','Tendril tightens'),
    'tendril_strike': ('impact',4,1.1,96,'触手击穿地面','Tendril strikes the ground'),
    'tendril_recover': ('shift',3,.65,64,'触手拖拽回收','Tendril recoils'),
    'roar_1': ('hurt',3,2.8,128,'巨大腔体呼吸','Huge cavity breathes'),
    'roar_2': ('hurt',3,3.3,128,'吞噬者低吼','Devourer bellows'),
    'roar_3': ('hurt',3,4.1,128,'风暴与世界共振','Storm resonates through the world'),
}


def read(path): return json.loads(path.read_text(encoding='utf-8'))
def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def configure():
    sounds = read(ASSETS/'sounds.json')
    langs = {code:read(ASSETS/f'lang/{code}.json') for code in ('zh_cn','en_us')}
    additions = dict(ADDITIONS)
    for name,(_,count,duration,radius,zh,en) in BOSS_ADDITIONS.items():
        additions['world_interface_'+name] = (count,duration,radius,zh,en)
    for name,(count,duration,radius,zh,en) in additions.items():
        if name.startswith('world_interface_'):
            path = 'world_interface/'+name.removeprefix('world_interface_')
            sub = 'subtitles.thefourthfrequency.world_interface.'+name.removeprefix('world_interface_')
        else:
            path = 'device/terminal/'+name.removeprefix('terminal_') if name.startswith('terminal_') else 'entity/'+name
            sub = 'subtitles.thefourthfrequency.'+name
        sounds[name] = {'subtitle':sub,'sounds':[{'name':f'thefourthfrequency:{path}/{i:02d}',
                         'attenuation_distance':radius} for i in range(1,count+1)]}
        if radius == 16:
            for sample in sounds[name]['sounds']: sample.pop('attenuation_distance')
        langs['zh_cn'][sub], langs['en_us'][sub] = zh,en
    for name,definition in sounds.items():
        if name.startswith('music_'): continue
        if any(isinstance(s,dict) and s.get('type')=='event' for s in definition['sounds']):
            definition['sounds'] = [{'name':f'thefourthfrequency:anomaly/{name}/{i:02d}',
                                     'attenuation_distance':32} for i in (1,2,3)]
        if name in ('terminal_boot_line','terminal_boot_complete'):
            group = name.removeprefix('terminal_')
            definition['sounds'] = [{'name':f'thefourthfrequency:device/terminal/{group}/{i:02d}'}
                                     for i in range(1,5 if group=='boot_line' else 3)]
    write(ASSETS/'sounds.json',sounds)
    for code,lang in langs.items(): write(ASSETS/f'lang/{code}.json',lang)
    return sounds


def original_asset(path):
    for manifest in (ORIGINAL_BEDS, ORIGINAL_CUES):
        if manifest.exists() and path in read(manifest)['files']:
            return read(manifest)['files'][path]
    return None


def specification(event, path):
    original = original_asset(path)
    if original:
        return original['seconds'], original.get('loop', True), original['channels']
    if event in ADDITIONS: return ADDITIONS[event][1],False,1
    if event.startswith('world_interface_'):
        group = event.removeprefix('world_interface_').replace('ambient_','ambient_form_')
        kind,_ = boss.GROUPS[group]
        return boss.seconds_for(group,kind),kind=='loop',2 if group in boss.AMBIENT_SECONDS else 1
    loop = 'loop' in path or event=='unrendered_layer_ambience'
    if loop:
        beds={'signal_carrier':11.65,'signal_static':14.05,'signal_tape_hiss':18.25,'signal_dead_air':19.6}
        return beds.get(event,20 if event=='unrendered_layer_ambience' else 8),True,2 if event in beds else 1
    durations = {'terminal_click':.15,'terminal_keypress':.09,'terminal_detent':.11,
                 'terminal_boot_line':.14,'terminal_boot_complete':.30,'terminal_lock':.16,
                 'terminal_fault':.22,'terminal_anomaly':.40,'unrendered_heartbeat':1,
                 'signal_tuning_sweep':3,'unrendered_capture_scream':2.4,'pursuit_capture_scream':2.1,
                 'alpha_corruption_warning':1.1,'alpha_corruption_collapse':2.4}
    return durations.get(event,.65 if event.startswith('layer_') else 1.5),False,2 if event in ('signal_carrier_lost','signal_tuning_sweep') else 1


def db(x): return 20*np.log10(max(1e-12,float(x)))


def master_foreground(data, target, ffmpeg):
    """Raise direct effects 4.35 dB with a look-ahead limiter; retain silence and exact timing."""
    channels = data.shape[1] if data.ndim == 2 else 1
    command = [str(ffmpeg), '-v', 'error', '-f', 'f32le', '-ar', str(RATE),
               '-ac', str(channels), '-i', 'pipe:0', '-af',
               'volume=1.65,alimiter=limit=0.86:attack=5:release=80:level=false:latency=true',
               '-f', 'f32le', 'pipe:1']
    output = subprocess.run(command, input=np.asarray(data, dtype='<f4').tobytes(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    mastered = np.frombuffer(output.stdout, dtype='<f4').reshape(data.shape).copy()
    return mastered, min(.86, target * 1.65)


def measure(path, duration, loop, channels):
    info=sf.info(path)
    assert info.format=='OGG' and info.subtype=='VORBIS', f'Unsupported Minecraft codec: {path}: {info}'
    x,rate = sf.read(path,dtype='float32',always_2d=True)
    assert rate==RATE and x.shape[1]==channels, f'format: {path}'
    assert abs(len(x)/rate-duration)<.01 and np.isfinite(x).all(), f'duration/data: {path}'
    peak = float(np.max(np.abs(x)))
    true_peak = float(np.max(np.abs(resample_poly(x,4,1,axis=0))))
    assert true_peak<.94, f'headroom exceeded: {path}: {db(true_peak)}'
    assert peak>.001 and np.max(np.abs(np.mean(x,axis=0)))<.015, f'silence/DC: {path}'
    seam = float(np.max(np.abs(x[0]-x[-1])))
    if loop: assert seam<.035, f'loop discontinuity: {path}: {seam}'
    return {'seconds':round(len(x)/rate,3),'channels':channels,'loop':loop,
            'peakDbfs':round(db(peak),2),'truePeakDbfs':round(db(true_peak),2),
            'rmsDbfs':round(db(np.sqrt(np.mean(x.astype(float)**2))),2),
            'seam':round(seam,6),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only',action='store_true')
    parser.add_argument('--terminal-only',action='store_true',help='Rebuild terminal cues, retaining all other shipped audio')
    parser.add_argument('--opening-only',action='store_true',help='Rebuild the opening warning and frozen-buffer cues')
    parser.add_argument('--foreground-only',action='store_true',help='Master direct effects while preserving terminal feedback and opening cues')
    args=parser.parse_args()
    sounds=read(ASSETS/'sounds.json') if args.verify_only else configure()
    ffmpeg=Path(imageio_ffmpeg.get_ffmpeg_exe())
    partial=args.terminal_only or args.opening_only or args.foreground_only
    if args.verify_only and partial: parser.error('Use --verify-only for the complete manifest')
    entries=read(REPORT)['files'] if partial else {}
    for event,definition in sounds.items():
        if event.startswith('music_'): continue
        if partial and not ((args.terminal_only and event.startswith('terminal_'))
                            or (args.opening_only and event.startswith('alpha_corruption_'))
                            or (args.foreground_only and not event.startswith(('terminal_', 'alpha_corruption_')))): continue
        for i,entry in enumerate(definition['sounds'],1):
            path=(entry if isinstance(entry,str) else entry['name']).split(':',1)[1]
            if path in entries and not partial: continue
            duration,loop,channels=specification(event,path)
            file=ASSETS/'sounds'/f'{path}.ogg'
            preserved = original_asset(path)
            if preserved:
                assert hashlib.sha256(file.read_bytes()).hexdigest() == preserved['sha256'], f'Original audio changed: {path}'
            if not args.verify_only and not preserved:
                file.parent.mkdir(parents=True,exist_ok=True)
                if event.startswith('world_interface_'):
                    group=event.removeprefix('world_interface_').replace('ambient_','ambient_form_')
                    data=boss.render(boss.GROUPS[group][0],group,i)
                    target=boss.PEAK_BY_KIND[boss.GROUPS[group][0]]
                else:
                    data=make(event,duration,i,loop)
                    if channels==2: data=np.stack((data,make(event,duration,i,loop,1)),axis=-1)
                    tier={'terminal_carrier':-28,'signal_carrier':-24,'signal_static':-24,
                          'signal_tape_hiss':-24,'signal_dead_air':-32,'signal_alert':-6,
                          'signal_tuning_sweep':-9,'signal_carrier_lost':-9,
                          'alpha_corruption_warning':-13,'alpha_corruption_collapse':-4.5,
                          'pursuit_capture_scream':-2,'unrendered_capture_scream':-1.5}
                    tier['unrendered_heartbeat']=-14
                    if event.startswith('terminal_'): tier[event] = -30 if loop else -14
                    target=10**(tier.get(event,-9 if loop else -4)/20)
                    data *= target/max(1e-9,float(np.max(np.abs(data))))
                if not event.startswith(('terminal_', 'alpha_corruption_')):
                    data, target = master_foreground(data, target, ffmpeg)
                boss.encode_to_peak(data,file,True,target,ffmpeg)
            entries[path]={'event':event,**measure(file,duration,loop,channels)}
            print(f'{event}: {path}',flush=True)
    actual={p.relative_to(ASSETS/'sounds').as_posix()[:-4] for p in (ASSETS/'sounds').rglob('*.ogg')
            if not p.is_relative_to(ASSETS/'sounds/music')}
    assert actual==set(entries), f'Orphan/missing audio: {actual.symmetric_difference(entries)}'
    if args.verify_only:
        assert read(REPORT)['files']==entries, 'Manifest no longer matches decoded files'
    else:
        write(REPORT,{'note':'GENERATED by tools/generate_soundscape.py; original procedural audio, music untouched.',
              'eventCount':sum(not e.startswith('music_') for e in sounds),'fileCount':len(entries),'sampleRate':RATE,'files':entries})
        groups={}
        for name,(kind,variants) in boss.GROUPS.items():
            files=[{'variant':i,**{k:entries[f'world_interface/{name}/{i:02d}'][k]
                    for k in ('seconds','channels','peakDbfs','rmsDbfs')}} for i in range(1,variants+1)]
            groups[name]={'kind':kind,'variants':variants,'seconds':boss.seconds_for(name,kind),'files':files}
        write(boss.MANIFEST,{'note':'GENERATED by tools/generate_soundscape.py using generate_world_interface_audio.py recipes.',
              'summonSeconds':boss.SUMMON_SECONDS,'summonDownbeatSeconds':boss.SUMMON_DOWNBEAT_SECONDS,
              'groups':groups,'fileCount':sum(v for _,v in boss.GROUPS.values()),'groupCount':len(groups)})
    print(f'Validated {len(entries)} non-music files.',flush=True)


if __name__=='__main__': main()
