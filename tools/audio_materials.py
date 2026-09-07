"""Original, deterministic physical/spectral sound materials. No sampled or licensed audio.

Dependencies: numpy, scipy. Voices are filtered pulse trains (no intelligible speech),
foley is granular resonant noise. Durations/impact landmarks belong to the calling score.
"""
from __future__ import annotations
import hashlib
import numpy as np
from scipy.signal import butter, sosfilt, iirpeak, lfilter

RATE = 44100


def rng_for(name, variant):
    return np.random.default_rng(int.from_bytes(hashlib.sha256(
        f"soundscape-v2:{name}:{variant}".encode()).digest()[:8], "big"))


def band(x, low, high):
    return sosfilt(butter(2, [low, high], btype="bandpass", fs=RATE, output="sos"), x)


def env(n, attack=.008, release=.12):
    t = np.arange(n) / RATE
    return np.minimum(t / max(.001, attack), 1) * np.minimum((n / RATE - t) / max(.001, release), 1)


def grain(n, rng, low=180, high=5000, density=22):
    """Asymmetric wet friction/cable fibres, with individually spaced micro-impacts."""
    x = band(rng.normal(size=n), low, high)
    gate = np.zeros(n)
    for start in rng.integers(0, max(1, n), max(2, int(n / RATE * density))):
        size = min(n - start, int(RATE * rng.uniform(.007, .075)))
        gate[start:start + size] += rng.uniform(.25, 1) * np.hanning(size)
    return x * (.08 + gate)


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
    crack = band(rng.normal(size=n), 1000, 10500) * np.exp(-t*70)
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


def make(name, duration, variant=1, loop=False, channel=0):
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
            x = .45*cavity(n,rng,84) + .5*band(rng.normal(size=n),350,6200)*(.7+.3*np.sin(t*73)**2)
        elif "ambient_form" in key:
            x = .46*cavity(n,rng,63-stage*8)*slow + .12*grain(n,rng,80,1600,7+stage*3)
            x += .15*np.sin(2*np.pi*(31+stage*3)*t)*(1+.2*np.sin(t*.37))
        else:
            low,high = (320,3800) if "tune" in name or "static" in name else (65,1500)
            x = .23*band(rng.normal(size=n),low,high)*slow
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
    elif name.startswith("alpha_corruption_"):
        x = .5*cavity(n,rng,79) + .45*grain(n,rng,180,6500,34)
        if "collapse" in name:
            length = int(RATE*(.021 + variant*.003))
            buffer = x[:length]*np.hanning(length)
            jam = np.tile(buffer,int(np.ceil(n/length)))[:n]
            cross = np.clip((t-.24)/.18,0,1)
            x = x*(1-cross)+jam*cross
        elif variant%3==1: x *= (.25+.75*(np.sin(t*47)>0))
        elif variant%3==2: x *= 1-.8*np.exp(-((t-duration*.52)/.1)**2)
        else: x += .2*np.sin(2*np.pi*211*t)*np.exp(-t*3)
    elif "lock_search" in name or "dispossess" in name:
        freq = 880 if "search" in name else 310
        x = .75*np.sin(2*np.pi*freq*t) + .12*np.sin(2*np.pi*freq*1.501*t)
    elif name.startswith("terminal_"):
        if any(k in name for k in ("fault","anomaly")):
            x = .42*grain(n,rng,300,7000,55) + .18*cavity(n,rng,93)
        elif any(k in name for k in ("raise","lower","boot_complete")):
            x = .55*grain(n,rng,120,3000,20)*np.sin(np.pi*t/duration)**2
            at = int(n*.72)
            x[at:] += .6*strike(n-at,rng,.35)
            if "complete" in name: x += .16*np.sin(2*np.pi*(650*t+180*t*t))*np.exp(-t*4)
        else:
            mass = .24 if "keypress" in name else .4
            x = .5*strike(n,rng,mass)*np.exp(-t*18)
            at = min(n-1,int(.026*RATE))
            x[at:] += .22*strike(n-at,rng,mass)*np.exp(-t[:n-at]*28)
            if "boot_line" in name: x += .12*np.sin(2*np.pi*1170*t)*np.exp(-t*30)
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
        if "laser_fire" in key: x += .38*band(rng.normal(size=n),550,8100)*np.exp(-t*1.4)
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
