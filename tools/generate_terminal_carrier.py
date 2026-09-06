#!/usr/bin/env python3
"""Generate the terminal's carrier hum: one seamless loop, encoded as Ogg Vorbis.

One file, not one per stage. The sound engine pitches samples for free, so the anomaly stage is
carried by the pitch the client asks for rather than by five near-identical assets. That is not
only cheaper: the whole point of this sound is the moment it stops, and a single source has exactly
one stop to get right.

Seamlessness is arithmetic, not a crossfade. Every component completes a whole number of cycles
inside the loop length, so the last sample joins the first with no discontinuity to click on.
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
LOOP_SECONDS = 4.0
FRAMES = int(RATE * LOOP_SECONDS)

# Deliberately far below everything else the mod plays. This is a noise floor: the player should
# never consciously notice it while it is there, which means it only has to survive being listened
# for, not compete for attention.
PEAK = 10 ** (-28.0 / 20.0)

# 120 Hz rather than a mains-frequency 50 or 60. Those are more literal, and they are also below what
# a laptop speaker will reproduce - a hum nobody's hardware can play is a hum whose absence cannot
# register, which would cost the effect the only thing it is for.
FUNDAMENTAL = 120.0
HARMONICS = ((1.0, 1.00), (2.0, 0.34), (3.0, 0.17), (4.0, 0.07))
# One full breath per loop, so the envelope is seamless for the same reason the tone is.
BREATH_HZ = 1.0 / LOOP_SECONDS


def carrier() -> list[float]:
    rng = random.Random(0x4F4653)
    values = []
    hiss = 0.0
    for index in range(FRAMES):
        t = index / RATE
        tone = 0.0
        for multiple, gain in HARMONICS:
            cycles = FUNDAMENTAL * multiple * LOOP_SECONDS
            assert abs(cycles - round(cycles)) < 1e-9, "component must close the loop"
            tone += math.sin(2 * math.pi * FUNDAMENTAL * multiple * t) * gain
        # A one-pole low pass on white noise: the faint air an amplifier makes with nothing on its
        # input. Unfiltered noise reads as tape, and this is not tape - it is a device that is on.
        hiss += ((rng.random() * 2 - 1) - hiss) * 0.03
        breath = 1.0 + 0.06 * math.sin(2 * math.pi * BREATH_HZ * t)
        values.append((tone * 0.22 + hiss * 0.55) * breath)
    return values


def normalise(values: list[float]) -> list[float]:
    loudest = max(abs(value) for value in values) or 1.0
    scale = PEAK / loudest
    return [value * scale for value in values]


def write_wave(path: Path, values: list[float]) -> None:
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(RATE)
        handle.writeframes(b"".join(
            struct.pack("<h", int(max(-1.0, min(1.0, value)) * 32767)) for value in values))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out.parent.mkdir(parents=True, exist_ok=True)
    values = normalise(carrier())
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / "carrier.wav"
        write_wave(raw, values)
        subprocess.run([
            str(args.ffmpeg), "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(raw), "-c:a", "libvorbis", "-q:a", "6", str(args.out)], check=True)
    print(f"wrote {args.out} ({args.out.stat().st_size} bytes, {LOOP_SECONDS:g}s loop)")


if __name__ == "__main__":
    main()
