#!/usr/bin/env python3
"""Synthesizes the unrendered layer's heartbeat loop and encodes it as Ogg Vorbis.

The layer has exactly one thing hunting the player and, until now, no way to tell where it was: the
entity is silent by design (footsteps would give its range away for free) and the ambience bed is
non-positional. That left "which corridor is it in" unanswerable, which is a different feeling from
being hunted - it is being told you are hunted and given nothing to do about it.

A heartbeat answers it without answering anything else. It carries direction and rough distance
through the engine's own attenuation, it belongs to a body rather than to the building, and it is
the one sound a player will never mistake for the room.

    lub  - 47 Hz, the chest hit
    dub  - 41 Hz, softer and a fifth of a second later
    then silence to the end of the bar, so the loop reads as a pulse rather than as a drone

One second per bar, so it loops at sixty beats a minute at pitch 1.0. The client plays it slightly
faster as the entity closes, which is why the file itself is deliberately calm.

    python tools/generate_unrendered_audio.py --ffmpeg <path to ffmpeg> \\
        --output src/main/resources/assets/thefourthfrequency/sounds

Writes unrendered/heartbeat.ogg.
"""

from __future__ import annotations

import argparse
import math
import random
import struct
import subprocess
import tempfile
import wave
from pathlib import Path


RATE = 44_100
# -14 dBFS true peak. The heartbeat is a locator that plays for minutes at a time under an ambience
# bed mastered to -20 LUFS; a cue that has to be lived with is mixed under the thing it sits on.
PEAK = 10 ** (-14.0 / 20.0)
BAR_SECONDS = 1.0
DUB_OFFSET = 0.21


def _thump(t: float, frequency: float, decay: float) -> float:
    """One chamber closing: a low sine that drops in pitch as it dies, which is what gives a
    heartbeat its thud instead of a beep."""
    if t < 0.0:
        return 0.0
    envelope = math.exp(-t * decay)
    # The downward pitch sweep is the whole character. A fixed frequency reads as a test tone.
    phase = 2.0 * math.pi * (frequency * t - 6.0 * t * t)
    return math.sin(phase) * envelope


def heartbeat() -> list[float]:
    rng = random.Random(0x48_45_41_52)
    samples: list[float] = []
    rumble = 0.0
    for index in range(round(RATE * BAR_SECONDS)):
        t = index / RATE
        lub = _thump(t, 47.0, 13.0) * 1.0
        dub = _thump(t - DUB_OFFSET, 41.0, 16.0) * 0.62
        # A little filtered noise under each hit, gated to the two thumps so the gap stays silent.
        rumble = rumble * 0.995 + (rng.random() * 2.0 - 1.0) * 0.005
        body = rumble * (math.exp(-t * 22.0) + math.exp(-max(0.0, t - DUB_OFFSET) * 26.0) * 0.5)
        samples.append(lub + dub + body * 0.5)
    # Both ends taper to zero, or the loop point is an audible click every second for six minutes.
    edge = round(RATE * 0.004)
    for index in range(edge):
        ramp = index / edge
        samples[index] *= ramp
        samples[-1 - index] *= ramp
    return samples


def write_wave(path: Path, samples: list[float]) -> None:
    maximum = max(1.0e-9, max(abs(value) for value in samples))
    scale = PEAK / maximum
    pcm = b"".join(struct.pack("<h", round(max(-1.0, min(1.0, value * scale)) * 32767.0))
                   for value in samples)
    with wave.open(str(path), "wb") as stream:
        stream.setnchannels(1)
        stream.setsampwidth(2)
        stream.setframerate(RATE)
        stream.writeframes(pcm)


def encode(ffmpeg: Path, destination: Path, samples: list[float], temporary: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    wave_path = temporary / (destination.stem + ".wav")
    write_wave(wave_path, samples)
    subprocess.run([str(ffmpeg), "-y", "-hide_banner", "-loglevel", "error", "-i", str(wave_path),
                    "-ac", "1", "-ar", str(RATE), "-c:a", "libvorbis", "-q:a", "5", str(destination)],
                   check=True)
    if destination.read_bytes()[:4] != b"OggS":
        raise RuntimeError(f"invalid OGG header: {destination}")
    print(f"{destination}\t{destination.stat().st_size} bytes")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="tff-unrendered-audio-") as temporary_name:
        encode(arguments.ffmpeg, arguments.output / "unrendered/heartbeat.ogg", heartbeat(),
               Path(temporary_name))


if __name__ == "__main__":
    main()
