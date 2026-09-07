"""Original sound materials and edits of the first project opening cues.

Dependencies: numpy, scipy. Voices are filtered pulse trains (no intelligible speech),
foley uses short resonant contacts without a continuous noise floor.
"""
from __future__ import annotations
import hashlib
from pathlib import Path
from functools import lru_cache
import numpy as np
from scipy.signal import butter, sosfilt, iirpeak, lfilter

RATE = 44100


@lru_cache(maxsize=2)
def opening_source(cue):
    import soundfile as sf
    path=Path(__file__).resolve().parents[1]/'docs/art/audio/opening/originals'/(cue+'.ogg')
    x,rate=sf.read(path)
    assert rate==RATE and x.ndim==1
    return x


def opening_feedback(name,duration,variant):
    """Re-edit the first shipped opening cues; preserve their tone instead of synthesizing a new one."""
    n=round(duration*RATE)
    if name.endswith('warning'):
        source=opening_source('warning')
        start=round((.25+(variant-1)*.02)*RATE)
        x=source[start:start+n].copy()
        return (x*env(n,.015,.025)).astype(np.float32)
    source=opening_source('collapse')
    frame=round(.074*RATE)
    start=round(.52*RATE)+(variant-1)*frame
    held=source[start:start+frame].copy()
    held-=held.mean()
    edge=round(.001*RATE)
    held[:edge]*=np.linspace(0,1,edge); held[-edge:]*=np.linspace(1,0,edge)
    x=np.tile(held,int(np.ceil(n/frame)))[:n]
    # Keep a piece of the original onset before the buffer catches.
    lead=round(.28*RATE); transition=round(.016*RATE)
    original=source[:lead].copy()
    blend=np.linspace(0,1,transition)
    original[-transition:]=original[-transition:]*(1-blend)+x[lead-transition:lead]*blend
    x[:lead]=original
    return (x*env(n,.008,.005)).astype(np.float32)


def rng_for(name, variant):
    return np.random.default_rng(int.from_bytes(hashlib.sha256(
        f"soundscape-v2:{name}:{variant}".encode()).digest()[:8], "big"))


def band(x, low, high):
    return sosfilt(butter(2, [low, high], btype="bandpass", fs=RATE, output="sos"), x)


def env(n, attack=.008, release=.12):
    t = np.arange(n) / RATE
    return np.minimum(t / max(.001, attack), 1) * np.minimum((n / RATE - t) / max(.001, release), 1)


def grain(n, rng, low=180, high=5000, density=22):
    """Discrete resonant contacts. Silence between grains, no white-noise excitation."""
    x = np.zeros(n)
    for start in rng.integers(0, max(1, n), max(2, int(n / RATE * density))):
        size = min(n - start, int(RATE * rng.uniform(.007, .075)))
        t = np.arange(size) / RATE
        frequency = rng.uniform(low, min(high, low * 5))
        contact = sum(np.sin(2*np.pi*frequency*ratio*t) / ratio
                      for ratio in (1, 1.47, 2.16))
        x[start:start + size] += rng.uniform(.25, 1) * np.hanning(size) * contact
    return x


def cavity(n, rng, base=57):
    t = np.arange(n) / RATE
    f = base * (1 + .028 * np.sin(t * 7.3) + .013 * np.sin(t * 17.9))
    phase = np.cumsum(f) * (2 * np.pi / RATE)
    pulse = np.tanh(2.7 * (np.sin(phase) + .27 * np.sin(2 * phase)))
    out = .12 * pulse
    for center, q, gain in [(280, 3, .9), (720, 5, .55), (1370, 7, .18)]:
        b, a = iirpeak(center * rng.uniform(.92, 1.08), q, RATE)
        out += gain * lfilter(b, a, pulse)
    return out * (.66 + .24 * np.sin(t * 3.1 + .4) + .1 * np.sin(t * 11.7))


def strike(n, rng, mass=1):
    t = np.arange(n) / RATE
    sub = np.sin(2*np.pi*(39*t + (62*mass)*.055*(1-np.exp(-t/.055))))
    body = np.zeros(n)
    for ratio in (1, 1.47, 2.16, 3.91, 5.43):
        body += np.sin(2*np.pi*(110/mass)*ratio*t + rng.uniform(-.3,.3)) * np.exp(-t*(2.8+ratio)/mass) / ratio
    crack = np.sin(2*np.pi*(3200*t+1800*.009*(1-np.exp(-t/.009)))) * np.exp(-t*110)
    debris = grain(n, rng, 230, 5300, 30) * np.exp(-t*3.4)
    return .75*sub*np.exp(-t*3.3/mass) + .34*body + .36*crack + .5*debris


def room(x, large=False):
    y = x.copy()
    for delay, gain in ((.031,.16),(.071,.12),(.127,.1),(.223,.075),(.389,.055)):
        offset = int(RATE * delay * (1.7 if large else 1))
        if offset < len(x):
            y[offset:] += band(x[:-offset], 90, 4100) * gain
    return y


def circular(x):
    """Smooth the internal join and match the boundary; retain the authored frame count."""
    n = min(int(.25*RATE), len(x)//8)
    # Wrap the signal at its middle, fade the original endpoint seam there.
    y = np.roll(x, len(x)//2)
    center = len(x)//2
    bridge = np.linspace(y[center-n], y[center+n], 2*n)
    w = np.hanning(2*n)
    y[center-n:center+n] = y[center-n:center+n]*(1-w) + bridge*w
    # Match the endpoint slope across the playback boundary with a short crossfade.
    edge = min(256, len(x)//16)
    shared = (y[:edge] + y[-edge:])*.5
    y[:edge] = shared
    y[-edge:] = shared
    y[-1] = y[0]
    return y


def terminal_feedback(name, duration, variant, loop):
    """Dry device feedback: no noise, distortion, sub hits or room tail."""
    n = round(duration * RATE)
    t = np.arange(n) / RATE
    x = np.zeros(n)
    if loop:
        # Integer cycles preserve the loop join; this is only audible during dial input.
        frequency = round(420 * duration) / duration
        return (.8*np.sin(2*np.pi*frequency*t) + .12*np.sin(4*np.pi*frequency*t)).astype(np.float32)
    def note(start, length, frequency, gain=1):
        a = round(start*RATE); size = min(round(length*RATE), n-a)
        if size <= 0: return
        u = np.arange(size)/RATE
        envelope = np.sin(np.pi*np.arange(size)/max(1,size-1))**2 * np.exp(-u*12)
        x[a:a+size] += gain*envelope*(np.sin(2*np.pi*frequency*u)+.08*np.sin(4*np.pi*frequency*u))
    frequencies = {'keypress':750,'click':610,'detent':480,'boot_line':820,
                   'raise':460,'lower':390,'lock':700,'fault':350,'anomaly':520,'boot_complete':660}
    key = name.removeprefix('terminal_')
    frequency = frequencies.get(key,600)*(1+(variant-2)*.012)
    note(0, min(duration,.11), frequency)
    if key in ('boot_complete','lock'): note(duration*.48,min(.12,duration*.48),frequency*1.25,.65)
    if key in ('fault','anomaly'): note(duration*.52,min(.12,duration*.46),frequency*.85,.55)
    return x.astype(np.float32)


def make(name, duration, variant=1, loop=False, channel=0):
    if name.startswith('terminal_'):
        return terminal_feedback(name,duration,variant,loop)
    if name.startswith('alpha_corruption_'):
        return opening_feedback(name,duration,variant)
    rng = rng_for(name, variant * 7 + channel)
    n = round(duration * RATE)
    t = np.arange(n) / RATE
    boss = name.startswith("world_interface_")
    key = name.removeprefix("world_interface_")
    tone = 1 + rng.uniform(-.045, .045)
    if loop:
        stage = int(key[-1]) if key[-1:].isdigit() else 1
        slow = .5 + .3*np.sin(t*.71 + channel*.7) + .2*np.sin(t*1.13)
        if "laser" in key:
            x = .45*cavity(n,rng,84) + .24*np.sin(2*np.pi*1450*t + 1.2*np.sin(2*np.pi*73*t))
        elif "ambient_form" in key:
            x = .46*cavity(n,rng,63-stage*8)*slow + .12*grain(n,rng,80,1600,7+stage*3)
            x += .15*np.sin(2*np.pi*(31+stage*3)*t)*(1+.2*np.sin(t*.37))
        else:
            low,high = (320,3800) if "tune" in name or "static" in name else (65,1500)
            x = .16*np.sin(2*np.pi*low*t)*slow
            x += .065*np.sin(2*np.pi*50*t) + .045*np.sin(2*np.pi*100.13*t)
            if "unrendered" in name: x += .12*cavity(n,rng,42)*(.6+.4*np.sin(t*.43))
        x = circular(x)
    elif key == "summon":
        # Absolute 5.5 s downbeat shared with the authoritative summon timeline.
        rise = np.clip(t/5.5,0,1)**1.8
        x = (.3*cavity(n,rng,46)+.22*grain(n,rng,100,3200,18))*rise
        at = round(5.5*RATE)
        x[at:] += 2.3*strike(n-at,rng,2.2)
    elif key in {"laser", "lance", "orb", "tendril", "weapon", "hotbar", "expulsion"}:
        # Distinct warning contours; no impact transient before an actual hit.
        rise = np.clip(t/duration,0,1)
        freq = {"laser":180,"lance":420,"orb":93,"tendril":62,"weapon":810,"hotbar":630,"expulsion":130}[key]
        x = .2*np.sin(2*np.pi*(freq*t+freq*.8*t*t/duration))*rise**.6
        x += .44*cavity(n,rng,48 if key == "tendril" else 67)*rise
        x += .5*grain(n,rng,100,4200,14)*rise**1.4
    elif name == "signal_alert":
        x = .45*np.sin(2*np.pi*853*t) + .45*np.sin(2*np.pi*960*t)
        x *= .78+.22*np.sin(t*7.1)**2
        x += .09*grain(n,rng,350,3300,28)
    elif "lock_search" in name or "dispossess" in name:
        freq = 880 if "search" in name else 310
        x = .75*np.sin(2*np.pi*freq*t) + .12*np.sin(2*np.pi*freq*1.501*t)
    elif "heartbeat" in name:
        x = .75*strike(n,rng,1.3)*np.exp(-t*14)
        at = int(.23*RATE)
        x[at:] += .48*strike(n-at,rng,1.1)*np.exp(-t[:n-at]*15)
        x = band(x,28,700)
    elif key.startswith("roar_"):
        stage = int(key[-1])
        x = .82*cavity(n,rng,73-stage*12) * np.sin(np.pi*t/duration)**.6
        x += .24*grain(n,rng,90,1800+stage*800,8+stage*5)
        x += .16*np.sin(2*np.pi*(39-stage*3)*t)*np.sin(np.pi*t/duration)
    elif "rework" in name:
        stage = int(name[-1]) if name[-1:].isdigit() else 1
        x = .48*strike(n,rng,.65+stage*.2) + .55*grain(n,rng,100,4800,10+stage*12)*np.exp(-t*2)
        if "breath" in name: x = .55*cavity(n,rng,79-stage*11)*np.sin(np.pi*t/duration)**2 + .3*grain(n,rng,200,1800,12)
    elif "bacteria" in name:
        x = .72*grain(n,rng,500,8200,60)*np.exp(-t*8) + .15*strike(n,rng,.22)
    elif "him" in name or "watcher" in name:
        x = .6*grain(n,rng,160,2100,11)*np.sin(np.pi*t/duration)**2 + .2*cavity(n,rng,43)
        if "watcher" in name: x = x[::-1].copy()
    elif any(k in key for k in ("impact","blast","throw","grab","hurt","shockwave","strike","laser_fire")):
        x = strike(n,rng,1.6 if boss else .75)
        x += .45*cavity(n,rng,51)*np.exp(-t*1.8)
        if "laser_fire" in key: x += .28*np.sin(2*np.pi*1850*t+2*np.sin(2*np.pi*113*t))*np.exp(-t*5)
    elif any(k in key for k in ("death","morph","shift","collapse","scream","failure","combat_start")):
        x = .9*cavity(n,rng,45 if boss else 105) + .7*grain(n,rng,120,6200,36)
        x *= (.7+.3*np.sin(t*8.7 + .5))
        x += .9*strike(n,rng,2)
    elif any(k in key for k in ("success","anchor","altar","lock","fourth_band")):
        x = .24*strike(n,rng,.6)
        for freq,amp in ((196,.35),(293,.22),(401,.13),(811,.07)):
            x += amp*np.sin(2*np.pi*freq*t*tone)*np.exp(-t*2.4)
        x += .15*grain(n,rng,300,2100,12)
    else:
        x = .6*grain(n,rng,120,4700,23)*np.sin(np.pi*t/duration)**2
        x += .3*cavity(n,rng,72)*np.exp(-t*1.3) + .25*strike(n,rng,.8)
    if not loop:
        x = room(x,boss) * env(n,.005,min(.18,duration*.22))
    x = band(x,26,14000)
    x = np.tanh(x*1.4)
    if loop: x = circular(x)
    else: x *= env(n,.002,min(.025,duration*.1))
    x -= np.mean(x)
    return x.astype(np.float32)
