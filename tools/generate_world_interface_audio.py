#!/usr/bin/env python3
"""World-interface score timings, variants and encoding. Materials live in audio_materials.py.

Full rebuild: python tools/generate_soundscape.py
Dependencies: numpy, scipy, soundfile, imageio-ffmpeg in build/tff-audio-tooling.
The 5.5-second summon downbeat is an authoritative animation landmark.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import tempfile
import time
from pathlib import Path

TOOLING = Path(__file__).resolve().parents[1] / "build" / "tff-audio-tooling"
sys.path.insert(0, str(TOOLING))

import numpy as np  # type: ignore  # installed only into build tooling
import soundfile as sf  # type: ignore
from audio_materials import make


RATE = 44_100
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "src/main/resources/assets/thefourthfrequency/sounds/world_interface"
MANIFEST = ROOT / "docs/art/world_interface/audio_manifest.json"

# The phase beds: non-positional, so they may be stereo, and long-running, so they must be
# long. Every other group keeps its mono contract because the arena has to place it.
AMBIENT_SECONDS: dict[str, float] = {
    "ambient_form_1": 18.7,
    "ambient_form_2": 22.9,
    "ambient_form_3": 26.3,
}

# ---------------------------------------------------------------------------------------
# The summon ceremony's clock.
#
# WorldInterfaceSummonTimeline drives a 260-tick entrance on the server. The rise cue is 6.5s
# long and its fifth layer is a single downbeat, so where that downbeat falls decides whether
# the ceremony reads as scored or as two things happening near each other. It is stated here
# in absolute seconds rather than as a fraction of the duration, because the tick it has to
# land on is fixed by the timeline and the duration is not.
#
# Both sides are asserted equal by WorldInterfaceSummonTimelineTest, which parses this file.
# Changing either number alone is a test failure rather than a silent drift.
#
# Every variant in the `summon` group shares this duration and this downbeat, and that is a
# hard requirement rather than a convenience: Minecraft picks a variant at random from
# sounds.json, so the server cannot know which one it started. Variants may differ in timbre;
# they may not differ in where the beat lands.
# ---------------------------------------------------------------------------------------
SUMMON_SECONDS = 6.5
SUMMON_DOWNBEAT_SECONDS = 5.5  # == WorldInterfaceSummonTimeline.GROUND_BREAK / 20.0

# Group -> (kind, variant count). This table is the single source of truth for the library:
# the manifest, the file-count check and ResourceContractTest all derive from it rather than
# restating a total. Variant counts must match sounds.json exactly.
GROUPS: dict[str, tuple[str, int]] = {
    "altar": ("pulse", 4),
    "terminal": ("device", 4),
    "anchor": ("chime", 3),
    "gateway_purple": ("loop", 1),
    "gateway_gold": ("loop", 1),
    "gateway_red": ("loop", 1),
    "summon": ("rise", 3),
    "ambient_form_1": ("loop", 1),
    "ambient_form_2": ("loop", 1),
    "ambient_form_3": ("loop", 1),
    "morph": ("tear", 4),
    "laser": ("warning", 3),
    "orb": ("rise", 3),
    "grab": ("impact", 3),
    "mental": ("mental", 3),
    "weapon": ("device", 3),
    "throw": ("impact", 3),
    "hotbar": ("device", 3),
    "arrow": ("warning", 3),
    "expulsion": ("mental", 3),
    "success": ("chime", 3),
    "failure": ("tear", 4),
    # The five groups below shipped as orphan assets: files on disk that no generator could
    # reproduce, and - not coincidentally - the five that carry the most force in the fight.
    "hurt": ("hurt", 3),
    "death": ("death", 1),
    "laser_fire": ("discharge", 3),
    "impact": ("impact", 3),
    "form_shift": ("shift", 2),
    # Beats that previously played nothing at all. See ModSounds.
    "shockwave": ("impact", 3),
    "combat_start": ("rise", 1),
    "eviction": ("mental", 2),
    "lance": ("warning", 3),
    "lance_impact": ("impact", 3),
    "flight": ("shift", 2),
    "laser_loop": ("loop", 1),
    "laser_release": ("shift", 3),
    "laser_impact": ("impact", 4),
    "blast": ("impact", 4),
    "tendril": ("warning", 3),
    "tendril_strike": ("impact", 4),
    "tendril_recover": ("shift", 3),
    "roar_1": ("hurt", 3),
    "roar_2": ("hurt", 3),
    "roar_3": ("hurt", 3),
}

# Fallback length per kind, overridden per group below. Stating it per group is what lets
# `arrow` and `laser` share a synth while staying different lengths.
KIND_SECONDS: dict[str, float] = {
    "pulse": 1.60, "device": 1.30, "chime": 2.40, "rise": 2.20, "tear": 4.00,
    "warning": 1.40, "impact": 1.60, "mental": 2.60, "hurt": 0.55, "death": 7.00,
    "discharge": 2.20, "shift": 2.40, "loop": 6.00,
}
GROUP_SECONDS: dict[str, float] = {
    "laser": 4.5,
    "terminal": 1.40,
    "success": 4.50,
    "failure": 5.00,
    "arrow": 1.10,
    "hotbar": 1.40,
    "grab": 1.60,
    "expulsion": 3.00,
    "eviction": 3.00,
    "shockwave": 2.20,
    "lance_impact": 1.90,
    "combat_start": 2.80,
    "flight": 2.00,
    "summon": SUMMON_SECONDS,
    "laser_loop": 2.8,
    "laser_release": 0.7,
    "laser_impact": 0.8,
    "blast": 1.7,
    "tendril": 1.5,
    "tendril_strike": 1.1,
    "tendril_recover": 0.65,
    "roar_1": 2.8,
    "roar_2": 3.3,
    "roar_3": 4.1,
}

# Loops stay at -7 dBFS because they run under everything else for an entire phase. One-shot
# combat cues go to -3 dBFS: they are the events, and they were being mixed as if they were
# background. The validation branch reads this same table.
PEAK_BY_KIND: dict[str, float] = {kind: 10.0 ** (-3.0 / 20.0) for kind in KIND_SECONDS}
PEAK_BY_KIND["loop"] = 10.0 ** (-7.0 / 20.0)

# A technical lower bound for the body layer; this does not certify perceived impact quality.
MIN_RMS_DBFS: dict[str, float] = {"impact": -18.0, "discharge": -18.0, "death": -18.0}

# Vorbis is lossy, and a lossy codec does not preserve peak: the decoded signal overshoots
# what was encoded, by one to two decibels here and most on the steepest transients - so the
# rise cues, whose whole point is a hard downbeat, overshoot worst. PEAK_BY_KIND is a
# statement about what the player hears, so it is enforced against the *decoded* file and the
# encoder is driven to hit it (see `encode_to_peak`) rather than being given an allowance to
# drift inside. This remains only as the tolerance on that convergence.
# Half a decibel: well under the ~1 dB a listener can pick out, and tight enough that the
# library stays level with itself. Overshoot is not quite proportional - the encoder's bit
# allocation shifts with level, so a pure ratio correction can oscillate on the steepest
# transients - hence the damping factor and the best-of-attempts fallback in `encode_to_peak`.
PEAK_TOLERANCE_DB = 0.5
ENCODE_ATTEMPTS = 6
ENCODE_DAMPING = 0.8

def seconds_for(name: str, kind: str) -> float:
    if kind == "loop":
        return AMBIENT_SECONDS.get(name, GROUP_SECONDS.get(name, KIND_SECONDS["loop"]))
    return GROUP_SECONDS.get(name, KIND_SECONDS[kind])


# =======================================================================================
# Shared material renderer and deterministic encoder.
# =======================================================================================


def render(kind: str, name: str, variant: int) -> np.ndarray:
    """Saturate, normalise, and stack into stereo for the groups allowed to be wide.

    Both channels share one scale factor: normalising each to its own peak would quietly
    re-balance the image towards whichever side happened to hold the loudest sample.
    """
    channels = 2 if name in AMBIENT_SECONDS else 1
    peak_target = PEAK_BY_KIND[kind]
    rendered = [make("world_interface_" + name, seconds_for(name, kind), variant,
                     kind == "loop", channel) for channel in range(channels)]
    maximum = max(1.0e-9, max(float(np.max(np.abs(signal))) for signal in rendered))
    scaled = [(signal * (peak_target / maximum)).astype(np.float32) for signal in rendered]
    return scaled[0] if channels == 1 else np.stack(scaled, axis=-1)


# The phase beds are the only files here long enough to trip it, but libsndfile's bundled
# Vorbis encoder aborts the process outright - no exception, just a native crash - when asked
# to write a stereo OGG past roughly 300k frames. Those go out through ffmpeg instead, which
# also means their bitrate can be stated rather than left to the library default.
AMBIENT_QUALITY = 6


def encode_via_ffmpeg(ffmpeg: Path, samples: np.ndarray, destination: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="tff-world-interface-") as temporary:
        intermediate = Path(temporary) / "bed.wav"
        sf.write(intermediate, samples, RATE, subtype="PCM_16")
        subprocess.run([
            str(ffmpeg), "-y", "-hide_banner", "-loglevel", "error",
            "-i", str(intermediate), "-ar", str(RATE),
            "-c:a", "libvorbis", "-q:a", str(AMBIENT_QUALITY),
            "-fflags", "+bitexact", "-flags:a", "+bitexact", "-map_metadata", "-1", str(destination),
        ], check=True)


def dbfs(value: float) -> float:
    return 20.0 * math.log10(max(1e-9, value))


def retry_io(action, attempts: int = 5):
    """Run a filesystem action, retrying the transient failures Windows produces here.

    Writing eighty-odd small files in a tight loop, several times each, reliably trips either
    a bare LibsndfileError "System error" or an OSError EINVAL on this platform - a handle
    from the previous pass, or an on-access scanner, still holding the path. Both clear within
    a few hundred milliseconds. A real fault still surfaces once the attempts are spent.
    """
    for attempt_index in range(attempts):
        try:
            return action()
        except (OSError, sf.LibsndfileError):
            if attempt_index == attempts - 1:
                raise
            time.sleep(0.25 * (attempt_index + 1))
    raise AssertionError("unreachable")


def encode_to_peak(samples: np.ndarray, destination: Path, stereo: bool,
                   peak_target: float, ffmpeg: Path) -> tuple[float, float, object]:
    """Encode until the decoded peak lands on target, and report what the file measures.

    Normalising the buffer and encoding once does not produce a file that peaks where it was
    normalised to; every measurement that matters is taken after the codec has had its say, so
    that is what gets driven. Overshoot is close to proportional, which makes this converge in
    two passes - the loop only exists so an unusually steep transient cannot ship off-target.
    """
    # Every pass writes to a scratch file rather than to `destination`. Repeatedly reopening
    # one path for write on Windows intermittently fails with a bare "System error" while a
    # previous handle is still being released, and a half-written OGG left at the real path
    # would be indistinguishable from a good one on the next run.
    def attempt(scratch: Path, scale: float):
        scaled = (samples * scale).astype(np.float32)
        encode_via_ffmpeg(ffmpeg, scaled, scratch)
        info = sf.info(scratch)
        decoded, sample_rate = sf.read(scratch, dtype="float32", always_2d=True)
        if sample_rate != RATE:
            raise RuntimeError(f"rate changed on encode: {scratch} {sample_rate}")
        peak = float(np.max(np.abs(decoded)))
        rms = float(np.sqrt(np.mean(np.square(decoded.astype(np.float64)))))
        return peak, rms, info

    # Scratch files live beside the destination rather than in the system temp directory, so
    # the final move is a same-volume rename: atomic, and never a cross-drive copy that an
    # on-access virus scanner can interrupt halfway.
    scratch_dir = destination.parent
    written = []
    try:
        scale = 1.0
        best = None
        for index in range(ENCODE_ATTEMPTS):
            scratch = scratch_dir / f".{destination.stem}.pass{index}.ogg"
            written.append(scratch)
            peak, rms, info = retry_io(lambda: attempt(scratch, scale))
            error = abs(dbfs(peak) - dbfs(peak_target))
            if best is None or error < best[0]:
                best = (error, scratch, peak, rms, info)
            if error <= PEAK_TOLERANCE_DB:
                best = (error, scratch, peak, rms, info)
                break
            correction = peak_target / max(1e-9, peak)
            scale *= 1.0 + (correction - 1.0) * ENCODE_DAMPING
        # Keep the closest attempt, not whichever one the loop happened to end on.
        _, scratch, peak, rms, info = best
        retry_io(lambda: os.replace(scratch, destination))
        written.remove(scratch)
    finally:
        for leftover in written:
            leftover.unlink(missing_ok=True)
    return peak, rms, info


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", type=Path,
                        help="path to ffmpeg, required to encode the stereo phase beds")
    parser.add_argument("--only", help="regenerate a single group, for iterating on one recipe")
    args = parser.parse_args()
    if args.ffmpeg is None:
        parser.error("--ffmpeg is required: the stereo phase beds cannot go through libsndfile")

    OUTPUT.mkdir(parents=True, exist_ok=True)
    selected = {args.only: GROUPS[args.only]} if args.only else GROUPS
    expected = sum(count for _, count in selected.values())
    generated = 0
    manifest: dict[str, object] = {
        "note": "GENERATED by tools/generate_world_interface_audio.py. Do not edit by hand.",
        "summonSeconds": SUMMON_SECONDS,
        "summonDownbeatSeconds": SUMMON_DOWNBEAT_SECONDS,
        "groups": {},
    }
    for name, (kind, variants) in selected.items():
        group = OUTPUT / name
        group.mkdir(parents=True, exist_ok=True)
        entries = []
        for variant in range(1, variants + 1):
            destination = group / f"{variant:02d}.ogg"
            expected_channels = 2 if name in AMBIENT_SECONDS else 1
            rendered = render(kind, name, variant)
            peak, rms, info = encode_to_peak(rendered, destination, expected_channels == 2,
                                             PEAK_BY_KIND[kind], args.ffmpeg)
            if destination.read_bytes()[:4] != b"OggS" or info.channels != expected_channels:
                raise RuntimeError(f"invalid OGG contract: {destination} {info}")
            if abs(dbfs(peak) - dbfs(PEAK_BY_KIND[kind])) > PEAK_TOLERANCE_DB:
                raise RuntimeError(
                    f"peak off target: {destination} {dbfs(peak):+.2f} dBFS, wanted "
                    f"{dbfs(PEAK_BY_KIND[kind]):+.1f} dBFS "
                    f"(+/-{PEAK_TOLERANCE_DB}) after {ENCODE_ATTEMPTS} encode passes")
            if peak >= 1.0:
                raise RuntimeError(f"clipped: {destination} reached full scale")
            floor = MIN_RMS_DBFS.get(kind)
            if floor is not None and dbfs(rms) < floor:
                raise RuntimeError(
                    f"not enough weight: {destination} rms={dbfs(rms):.2f} dBFS, "
                    f"kind '{kind}' requires >= {floor:.1f}. A cue that measures this thin has "
                    f"drifted back towards a bare sine.")
            entries.append({"variant": variant, "seconds": round(info.duration, 3),
                            "channels": expected_channels,
                            "peakDbfs": round(dbfs(peak), 2), "rmsDbfs": round(dbfs(rms), 2)})
            generated += 1
            print(f"{destination.relative_to(ROOT)} rate={RATE} "
                  f"channels={expected_channels} peak={dbfs(peak):+.2f}dBFS "
                  f"rms={dbfs(rms):+.2f}dBFS seconds={info.duration:.2f}")
        manifest["groups"][name] = {  # type: ignore[index]
            "kind": kind, "variants": variants,
            "seconds": round(seconds_for(name, kind), 3), "files": entries,
        }
    if generated != expected:
        raise RuntimeError(f"expected {expected} sounds, generated {generated}")
    if not args.only:
        manifest["fileCount"] = generated
        manifest["groupCount"] = len(GROUPS)
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(json.dumps(manifest, indent=2, sort_keys=False) + "\n",
                            encoding="utf-8")
        print(f"\n{len(GROUPS)} groups, {generated} files -> {MANIFEST.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
