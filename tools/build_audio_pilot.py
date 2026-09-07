#!/usr/bin/env python3
"""Edit CC0 recordings into audition pilots; never writes the game resource tree.

Uses recorded performances/foley and supplied designed effects, not oscillators or
generated noise. AI candidates are a separate, currently unavailable source route.
Requires tools/audio-requirements.txt installed in build/tff-audio-tooling.
"""
from __future__ import annotations
import hashlib
import io
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'build/tff-audio-tooling'))
import numpy as np
import soundfile as sf
import imageio_ffmpeg
from scipy.signal import butter, resample_poly, sosfiltfilt

RATE = 48000
WORK = ROOT / 'build/audio-pilot'
DELIVERY = ROOT / 'docs/art/audio/pilot'
FFMPEG = imageio_ffmpeg.get_ffmpeg_exe()
PACKS = {
    'owlish': ('OwlishMedia', 'https://opengameart.org/content/sound-effects-pack',
               'https://opengameart.org/sites/default/files/Owlish%20Media%20Sound%20Effects.zip'),
    'starninjas': ('StarNinjas', 'https://opengameart.org/content/16-monster-growls',
                   'https://opengameart.org/sites/default/files/monster_-_starninjas.zip'),
    'kenney': ('Kenney', 'https://kenney.nl/assets/impact-sounds',
               'https://kenney.nl/media/pages/assets/impact-sounds/87b4ddecda-1677589768/kenney_impact-sounds.zip'),
}
CACHE = {}
LAYERS = {}
OUTPUTS = {}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def source(pack, member):
    key = (pack, member)
    if key not in CACHE:
        archive = WORK / 'sources' / (pack + '.zip')
        with zipfile.ZipFile(archive) as z:
            raw = z.read(member)
        x, sr = sf.read(io.BytesIO(raw), always_2d=True, dtype='float64')
        info = sf.info(io.BytesIO(raw))
        metadata = dict(pack=pack, member=member, sha256=digest(raw),
                        source_rate=sr, source_channels=x.shape[1], source_subtype=info.subtype,
                        source_peak=float(np.max(np.abs(x))))
        # No hard clipping of decoded Vorbis overshoot; floating point is retained.
        x = x.mean(axis=1)
        x = resample_poly(x, RATE // math.gcd(sr, RATE), sr // math.gcd(sr, RATE))
        CACHE[key] = (x, metadata)
    return CACHE[key]


def fade(x, attack=.006, release=.06):
    x = x.copy()
    a, b = min(len(x)//2, round(attack*RATE)), min(len(x)//2, round(release*RATE))
    if a: x[:a] *= np.linspace(0, 1, a)**1.5
    if b: x[-b:] *= np.linspace(1, 0, b)**1.5
    return x


def layer(event, label, pack, member, *, start=0, length=None, speed=1, offset=0,
          gain=1, hp=45, lp=14000, reverse=False, rise=False):
    x, meta = source(pack, member)
    a = round(start*RATE)
    b = len(x) if length is None else min(len(x), a+round(length*RATE))
    if b <= a: raise ValueError(f'Empty source slice: {member}')
    x = x[a:b].copy()
    x = sosfiltfilt(butter(2, [hp, lp], btype='bandpass', fs=RATE, output='sos'), x)
    if reverse: x = x[::-1]
    if speed != 1:
        den = round(speed*1000)
        x = resample_poly(x, 1000//math.gcd(1000, den), den//math.gcd(1000, den))
    # Trim leading inactivity for one-shots, keep natural dynamics inside the take.
    active = np.flatnonzero(np.abs(x) > np.max(np.abs(x))*.025)
    if not len(active): raise ValueError(f'Silent source: {member}')
    x = x[max(0, active[0]-round(.005*RATE)):]
    x = fade(x)
    x /= max(float(np.max(np.abs(x))), 1e-10)
    if rise: x *= np.linspace(.08, 1, len(x))**1.4
    record = dict(meta, label=label, slice_start_seconds=start, slice_seconds=length,
                  speed=speed, offset_seconds=offset, gain=gain, highpass_hz=hp,
                  lowpass_hz=lp, reversed=reverse, pressure_rise=rise)
    LAYERS.setdefault(event, []).append((x, record))


def recipes():
    cloth = 'Cloth, Rustle/320144__owlstorm__blanket-movement-6.wav'
    # Same underlying human performance family, different cavity and strain layers.
    for form, performance, speed in [(1, 8, .64), (2, 11, .57), (3, 11, .46)]:
        e = f'world_interface_roar_{form}'
        layer(e, 'living_throat', 'starninjas', f'monster.{performance}.ogg', speed=speed, gain=.90, lp=7200)
        layer(e, 'chest', 'starninjas', f'monster.{performance}.ogg', speed=speed*.71, gain=.34, hp=28, lp=450, offset=.035)
        layer(e, 'strained_structure', 'owlish', 'Impacts/scrape3.wav', length=1.0, speed=.68, gain=.16, offset=.15, lp=2600)
        layer(e, 'exhalation', 'owlish', 'Human/freakedbreath.wav', speed=.8, gain=.13, offset=1.2+.45*form, lp=5000)
        if form > 1:
            layer(e, 'upper_strain', 'owlish', 'Human/SCREAM.wav', speed=.83 if form==2 else .65,
                  gain=.16 if form==2 else .23, offset=.28, hp=550, lp=4800)

    e = 'world_interface_laser'
    layer(e, 'mouth_intake', 'owlish', 'Human/scared-breathing.wav', start=2, length=3.2, speed=.72, gain=.48, rise=True, lp=5000)
    layer(e, 'energy_pressure', 'owlish', 'Scifi/blackhole2.wav', start=4, length=4.5, gain=.62, rise=True, hp=100, lp=8000)
    layer(e, 'tension', 'owlish', 'Impacts/scrape3.wav', length=2.5, speed=.7, gain=.18, offset=.6, rise=True)
    e = 'world_interface_laser_fire'
    layer(e, 'pressure_contact', 'kenney', 'Audio/impactPunch_heavy_000.ogg', speed=.62, gain=.85, hp=30, lp=5500)
    layer(e, 'tear', 'owlish', 'Impacts/scrape2.wav', speed=1.3, gain=.5, hp=650)
    layer(e, 'electrical_release', 'owlish', 'Scifi/robotics1.wav', speed=.75, gain=.36, offset=.01, hp=180)
    e = 'world_interface_laser_loop'
    layer(e, 'beam_body', 'owlish', 'Scifi/blackhole2.wav', start=8, length=5, gain=.7, hp=100, lp=9000)
    layer(e, 'cavity_air', 'owlish', 'Water/kettle-boil.wav', start=3, length=4, speed=.82, gain=.24, hp=250, lp=6500)
    e = 'world_interface_laser_impact'
    layer(e, 'mineral_contact', 'kenney', 'Audio/impactMining_000.ogg', speed=.9, gain=.9)
    layer(e, 'heat_fizz', 'owlish', 'Water/spray-bottle.wav', length=.5, gain=.24, offset=.04, hp=1600)
    e = 'world_interface_laser_release'
    layer(e, 'spent_breath', 'owlish', 'Human/freakedbreath.wav', length=.65, gain=.7, lp=4500)
    layer(e, 'residual_sputter', 'owlish', 'Scifi/robotics2.wav', speed=1.3, gain=.35, hp=400)

    e = 'world_interface_tendril'
    layer(e, 'fibrous_tension', 'owlish', 'Impacts/scrape3.wav', length=1.1, speed=.72, gain=.82, rise=True, lp=5200)
    layer(e, 'internal_movement', 'owlish', 'Impacts/gulp1.wav', speed=.55, gain=.23, offset=.45, lp=2000)
    e = 'world_interface_tendril_strike'
    layer(e, 'limb_mass', 'kenney', 'Audio/impactPunch_heavy_000.ogg', speed=.6, gain=.95, hp=28, lp=3000)
    layer(e, 'leathery_contact', 'owlish', 'Impacts/slap2.wav', speed=.72, gain=.44, lp=6000)
    layer(e, 'stone_debris', 'kenney', 'Audio/impactMining_000.ogg', speed=1.05, gain=.3, offset=.035, hp=700)
    e = 'world_interface_tendril_recover'
    layer(e, 'drag', 'owlish', 'Impacts/scrape1.wav', gain=.8, lp=5500)
    layer(e, 'release', 'owlish', 'Impacts/fruit1.wav', gain=.16, offset=.12, hp=200)
    e = 'him_presence'
    layer(e, 'cotton', 'owlish', cloth, gain=.8, hp=180, lp=6500)
    layer(e, 'barely_breath', 'owlish', 'Human/breath-male.wav', start=3, length=.8, gain=.07, offset=.2, hp=250, lp=3000)
    layer(e, 'sole_shift', 'kenney', 'Audio/footstep_carpet_000.ogg', gain=.06, offset=.35, lp=2500)
    e = 'terminal_raise'
    layer(e, 'cloth_pickup', 'owlish', cloth, length=.42, speed=1.15, gain=.28)
    layer(e, 'hinge', 'owlish', 'Technology/plugpull.wav', speed=1.05, gain=.6, offset=.1, hp=170)
    layer(e, 'stop', 'kenney', 'Audio/impactWood_light_000.ogg', gain=.28, offset=.3, lp=5000)
    e = 'terminal_lower'
    layer(e, 'latch', 'owlish', 'Technology/plugpull.wav', speed=1.25, gain=.7, hp=180)
    layer(e, 'cloth_settle', 'owlish', cloth, start=.35, length=.4, gain=.3, offset=.1, lp=7000)


def encode(path, x):
    wav = WORK / 'render' / (path.stem + '.wav')
    wav.parent.mkdir(parents=True, exist_ok=True)
    sf.write(wav, x, RATE, subtype='PCM_24')
    subprocess.run([FFMPEG, '-v', 'error', '-y', '-i', str(wav), '-map_metadata', '-1',
                    '-fflags', '+bitexact', '-flags:a', '+bitexact', '-c:a', 'libvorbis',
                    '-q:a', '7', str(path)], check=True)


def lufs(x):
    result = subprocess.run([FFMPEG, '-hide_banner', '-f', 'f64le', '-ar', str(RATE),
                             '-ac', '1', '-i', 'pipe:0', '-af', 'loudnorm=print_format=json',
                             '-f', 'null', '-'], input=x.astype('<f8').tobytes(), capture_output=True, check=True)
    match = re.search(r'\{\s*"input_i".*?\}', result.stderr.decode(), re.S)
    if not match: raise RuntimeError('LUFS measurement missing')
    value = float(json.loads(match.group())['input_i'])
    if not math.isfinite(value): raise ValueError('Silent audition scene')
    return value


def scene(events, offsets, length, old=False):
    mix = np.zeros(round(length*RATE))
    for event, offset in zip(events, offsets):
        if old:
            definitions = json.loads((ROOT/'src/main/resources/assets/thefourthfrequency/sounds.json').read_text(encoding='utf-8'))
            sample = definitions[event]['sounds'][0]
            name = sample['name'] if isinstance(sample, dict) else sample
            x, sr = sf.read(ROOT/'src/main/resources/assets/thefourthfrequency/sounds'/(name.split(':')[1]+'.ogg'), always_2d=True)
            x = resample_poly(x.mean(axis=1), RATE//math.gcd(RATE,sr), sr//math.gcd(RATE,sr))
        else: x = OUTPUTS[event].copy()
        if event == 'world_interface_laser_loop': x = fade(x[:round(2*RATE)], release=.02)*.66
        if event == 'world_interface_laser_fire': x *= .8
        if event == 'world_interface_laser_impact': x *= .4
        a = round(offset*RATE); b = min(len(mix),a+len(x))
        mix[a:b] += x[:b-a]
    return mix


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK/'sources').mkdir(exist_ok=True)
    sources = {}
    for pack, (author, page, url) in PACKS.items():
        path = WORK/'sources'/(pack+'.zip')
        if not path.exists():
            with urllib.request.urlopen(url, timeout=60) as response:
                path.write_bytes(response.read())
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None: raise ValueError('Damaged source archive')
        sources[pack] = dict(author=author, page=page, download=url, license='CC0-1.0', sha256=digest(path.read_bytes()))
    specs = json.loads((DELIVERY/'cues.json').read_text(encoding='utf-8'))['cues']
    recipes()
    if {c['event'] for c in specs} != set(LAYERS): raise ValueError('Recipe coverage mismatch')
    (DELIVERY/'samples').mkdir(exist_ok=True)
    report = dict(method='CC0 recorded and predesigned material editing; no AI generation performed',
                  sample_rate=RATE, subjective_review='pending', sources=sources, events={})
    rpp = ['<REAPER_PROJECT 0.1 7.0 1', '  SAMPLERATE 48000', '  TEMPO 120']
    cursor = 0
    for cue in specs:
        event, seconds = cue['event'], cue['target_seconds']
        n = round(seconds*RATE)
        loop = cue['loop']
        overlap = round(.08*RATE) if loop else 0
        aligned = []
        records = []
        for x, record in LAYERS[event]:
            track = np.zeros(n+overlap)
            start = round(record['offset_seconds']*RATE)
            end = min(len(track), start+len(x))
            track[start:end] = x[:end-start]*record['gain']
            if loop:
                ramp = np.linspace(0, 1, overlap)
                track[:overlap] = track[n:n+overlap]*(1-ramp) + track[:overlap]*ramp
                track = track[:n]
            else: track = fade(track, attack=.001, release=.035)
            aligned.append(track)
            records.append(record)
        mix = np.sum(aligned, axis=0)
        peak = np.max(np.abs(resample_poly(mix, 4, 1)))
        target = -7 if loop else -4 if event.startswith('world_interface') else -8 if event=='him_presence' else -6
        scale = 10**(target/20)/max(peak,1e-10)
        mix *= scale
        OUTPUTS[event] = mix
        path = DELIVERY/'samples'/(event+'.ogg')
        encode(path, mix)
        decoded, sr = sf.read(path)
        tp = 20*np.log10(max(1e-12, np.max(np.abs(resample_poly(decoded,4,1)))))
        if sr != RATE or decoded.ndim != 1 or abs(len(decoded)/sr-seconds)>.001 or not np.isfinite(decoded).all() or tp > -1:
            raise ValueError(f'Invalid export {event}: {tp}')
        seam = float(abs(decoded[0]-decoded[-1])) if loop else None
        if loop and seam > .02: raise ValueError(f'Loop seam exceeds limit: {seam}')
        report['events'][event] = dict(seconds=seconds, true_peak_dbfs=float(tp), loop_seam=seam,
                                        sha256=digest(path.read_bytes()), layers=records)
        rpp.append(f'  MARKER {len(report["events"])} {cursor:.6f} "{event}"')
        for i,(track,record) in enumerate(zip(aligned,records)):
            stem = WORK/'stems'/event/(record['label']+'.wav')
            stem.parent.mkdir(parents=True,exist_ok=True)
            sf.write(stem, track*scale, RATE, subtype='FLOAT')
            relative = stem.relative_to(WORK).as_posix()
            rpp.extend(['  <TRACK', f'    NAME "{event}/{record["label"]}"', '    VOLPAN 1 0',
                        '    <ITEM', f'      POSITION {cursor:.6f}', f'      LENGTH {seconds:.6f}',
                        '      VOLPAN 1 0 1 -1', '      FADEIN 1 0 0', '      FADEOUT 1 0 0',
                        '      <SOURCE WAVE', f'        FILE "{relative}"', '      >', '    >', '  >'])
        cursor += seconds+1
    rpp.append('>')
    (WORK/'pilots.rpp').write_text('\n'.join(rpp)+'\n',encoding='utf-8')
    scenes = [
        ('boss_roar', ['world_interface_roar_1','world_interface_roar_2','world_interface_roar_3'], [0,3.4,7.3], 11.8),
        ('mouth_laser', ['world_interface_laser','world_interface_laser_fire','world_interface_laser_loop','world_interface_laser_impact','world_interface_laser_release'], [0,4.5,4.5,5.1,6.5], 7.4),
        ('tendril_strike',['world_interface_tendril','world_interface_tendril_strike','world_interface_tendril_recover'],[0,1.6,1.85],2.8),
        ('him_presence',['him_presence'],[0],1.6),
        ('terminal_handling',['terminal_raise','terminal_lower'],[0,1.1],1.9),
    ]
    comparison=[]; markers=[]; pos=0
    for name,events,offsets,length in scenes:
        versions=[scene(events, offsets, length, old=True),scene(events, offsets,length)]
        levels=[lufs(x) for x in versions]
        # Equal loudness; lower both together if either version needs headroom.
        gains=[10**((-22-v)/20) for v in levels]
        peak=max(np.max(np.abs(resample_poly(x*g,4,1))) for x,g in zip(versions,gains))
        common=min(1,10**(-3/20)/max(peak,1e-10))
        pair=[]
        for label,x,g in zip(('A_RC3','B_recorded_material'),versions,gains):
            x=x*g*common
            comparison.extend([x,np.zeros(round(.65*RATE))])
            pair.extend([x,np.zeros(round(.65*RATE))])
            markers.append(dict(group=name,version=label,start_seconds=pos,seconds=length,lufs=lufs(x)))
            pos+=length+.65
        encode(DELIVERY/(name+'_AB.ogg'),np.concatenate(pair))
    encode(DELIVERY/'comparison.ogg',np.concatenate(comparison))
    report['comparison']=markers
    report['comparison_note']='A then B per group, matched integrated loudness; studio assembly, not a game capture.'
    write_json(DELIVERY/'recorded-material-manifest.json',report)
    print(f'14 sample exports verified; A/B {pos:.1f}s; {len(CACHE)} source recordings; REAPER stems ready.')


if __name__ == '__main__':
    main()
