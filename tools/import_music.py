#!/usr/bin/env python3
"""Import the authored BGM set from lossless masters into the mod's Ogg Vorbis music assets.

The masters are 24-bit/96 kHz FLAC, which Minecraft cannot decode at all: the client only reads
Ogg Vorbis. Resampling to 44.1 kHz stereo and encoding at q4 keeps every track well under the
size a mod jar should carry while staying transparent for streamed background music.

Playback level is baked in here rather than declared in sounds.json so the shipped files are
already at the intended level no matter which sound event, resource pack or category volume
later references them.

Level is decided in two steps, and the order matters:

1. **Match loudness.** Every master is measured with ffmpeg's ``loudnorm`` and shifted by a pure
   linear gain onto :data:`LOUDNESS_TARGET_LUFS`. The masters run from -3.6 to -16.4 LUFS - they
   were mastered for different streaming platforms, not for one score - and a fixed fraction of
   each one carries that spread straight into the game, which is what left some tracks buried
   under the signal beds while others sat on top of the sound effects.
2. **Then attenuate.** The matched level is scaled by :data:`GAIN`, or by a per-folder override
   (:data:`GAIN_BY_DIRECTORY`). Two contexts take a louder target of their own instead
   (:data:`TARGET_BY_DIRECTORY`) and no attenuation at all, because in both of them the score is
   competing with something the mod itself is playing rather than with vanilla: the encounter with
   the fight's own cues (:data:`ENCOUNTER_LOUDNESS_TARGET_LUFS`), and the unrendered layer with its
   own ambience bed (:data:`UNRENDERED_LOUDNESS_TARGET_LUFS`).

The shift is a gain and nothing else - no compression, no limiting - so nothing about how a track
breathes changes. It is safe to apply unattended because every master is louder than the target:
the gains are all negative, and the loudest true peak in the set - one of the encounter tracks,
which take the highest target - lands around -3 dBFS afterwards.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import re
import subprocess
from pathlib import Path

RATE = 44_100
QUALITY = "4"

# Integrated loudness every track is matched to before attenuation, in LUFS.
#
# This is 4 dB below where the set first landed, and the reason is dynamic range rather than level.
# Measured against the vanilla music this score replaces: vanilla's own tracks peak about 4 to 6 dB
# above their integrated loudness, because they spend most of their length in the quiet passages
# between. This set does not - its short-term maximum sits 0.8 to 1.9 dB above its integrated
# loudness, and several tracks have a loudness range under 4 LU (hi.ogg is 1.9), which is lower than
# anything vanilla ships. A track with no quiet passages is heard at its integrated loudness the
# whole time it plays, so matching that number to vanilla's makes it the loudest thing in the mix in
# practice. The headroom here is what pays for the missing range: at this target the set lands near
# the C418 tracks players know best, which measure -26.6 to -27.8 LUFS.
LOUDNESS_TARGET_LUFS = -24.0

# The encounter's own target, five decibels above everything else.
#
# The reasoning above is about a score heard against silence. The encounter is the one stretch that
# is not: the interface is throwing eight authored attack cues, a shockwave, and vanilla explosions
# over the top of it, and the score is what tells the player which of the three bodies they are
# fighting. Matched to the same -24 as the rest, it was reported as simply missing during the fight.
#
# The source folder used to carry an annotation asking for the opposite ("音量降低10%"), and it was
# followed. The user removed that annotation and asked for these three to be louder on 2026-08-29,
# so the folder is now plain "BOSS战" and this constant is what replaced the note.
#
# Settled at -17 after three passes. -20 was too quiet, -15 was overshoot, and this is the user's
# own "nudge it back down" between them: about 9 dB above the rest of the set.
#
# Headroom is still not the constraint: the three masters run -3.6 to -11.3 LUFS with true peaks
# around +0.5 dBFS, so at this target they land at true peaks of roughly -11, -8 and -3 dBFS. The
# quietest master is the one with the least room left, and it still clears digital full scale by
# three decibels without any limiting.
ENCOUNTER_LOUDNESS_TARGET_LUFS = -17.0

# The unrendered layer's own target, three decibels above the default set.
#
# The layer is the one context where the mod is competing with itself. Everywhere else the score
# plays over vanilla ambience and the signal beds, which sit around -24 dBFS peak by construction;
# in the layer the only other sound is `unrendered/layer_ambience.ogg`, a continuous drone shipped
# at -20.1 LUFS because for six minutes it is the sole source. A track matched to the default lands
# six decibels under that drone and never comes out from behind it, which is the failure the
# encounter already ran into once.
#
# Three decibels under the ambience rather than level with it: the bed still owns the room, and the
# track is what should not be there. No attenuation on top, for the same reason as the encounter -
# the fraction exists to pull the ordinary playlist back from vanilla's level, and this target was
# chosen against a measured bed rather than against vanilla.
UNRENDERED_LOUDNESS_TARGET_LUFS = -23.0

# Default playback fraction applied on top of the matched level: -20%, about -1.94 dB.
GAIN = 0.8
# Per-source-folder overrides for both halves. The encounter folders take the target above and no
# attenuation at all; everything else takes the defaults.
GAIN_BY_DIRECTORY: dict[str, float] = {}
TARGET_BY_DIRECTORY: dict[str, float] = {}

MENU_DIRECTORY = "主菜单BGM"
GAME_DIRECTORY = "游戏内BGM"
PURSUIT_DIRECTORY = "追逐战"
# No closing bracket in the folder name; it is reproduced here exactly as it is on disk.
END_DIRECTORY = "进入末地（未召唤BOSS前"
ENCOUNTER_DIRECTORY = "BOSS战"
UNRENDERED_DIRECTORY = "未渲染层/BGM"
ENCOUNTER_PHASE_1_DIRECTORY = f"{ENCOUNTER_DIRECTORY}/阶段1"
ENCOUNTER_PHASE_2_DIRECTORY = f"{ENCOUNTER_DIRECTORY}/阶段2"
ENCOUNTER_PHASE_3_DIRECTORY = f"{ENCOUNTER_DIRECTORY}/阶段3"
ENDING_DIRECTORY = "击败BOSS-终末之诗BGM"
FAILURE_ENDING_DIRECTORY = "BOSS战失败-终末之诗BGM"

for _encounter in (ENCOUNTER_PHASE_1_DIRECTORY, ENCOUNTER_PHASE_2_DIRECTORY,
                   ENCOUNTER_PHASE_3_DIRECTORY):
    GAIN_BY_DIRECTORY[_encounter] = 1.0
    TARGET_BY_DIRECTORY[_encounter] = ENCOUNTER_LOUDNESS_TARGET_LUFS

GAIN_BY_DIRECTORY[UNRENDERED_DIRECTORY] = 1.0
TARGET_BY_DIRECTORY[UNRENDERED_DIRECTORY] = UNRENDERED_LOUDNESS_TARGET_LUFS

# (category, slug, source directory, source file). The slug becomes the asset path, so it stays
# ASCII: the sound id derived from it is also the "now playing" translation key.
#
# The encounter folder's own root track is deliberately absent: the summon is scored by the first
# body's track rather than by one of its own, so the piece the player hears while the interface
# descends is the piece they are still hearing when it starts fighting.
#
# Comfort Chain is listed twice on purpose - it sits in both the menu and the gameplay folder - and
# each context gets its own file, because the now-playing key is derived from the file path.
TRACKS = (
    ("menu", "green_to_blue", MENU_DIRECTORY, "Aurenth - green to blue (Sped Up).flac"),
    ("menu", "hi_piano", MENU_DIRECTORY, "弹琴老周 - Hi（纯钢琴梦核）.flac"),
    ("menu", "nop", MENU_DIRECTORY, "陈越龙 - nop.flac"),
    ("menu", "comfort_chain", MENU_DIRECTORY, "instupendo - Comfort Chain.flac"),
    ("game", "millennium_dream", GAME_DIRECTORY, "LUSTN - 千禧梦（中式梦核）.flac"),
    ("game", "are_you_lost", GAME_DIRECTORY, "Park Bird - Are You Lost.flac"),
    ("game", "hi", GAME_DIRECTORY, "TEMPOREX - Hi.flac"),
    ("game", "tenshi", GAME_DIRECTORY,
     "NEEDY GIRL OVERDOSE; Aiobahn +81 - 天使は感動する (feat. Aiobahn +81).flac"),
    ("game", "school_rooftop", GAME_DIRECTORY, "hisohkah; WMD - School Rooftop.flac"),
    ("game", "comfort_chain", GAME_DIRECTORY, "instupendo - Comfort Chain.flac"),
    ("game", "snowfall", GAME_DIRECTORY, "Øneheart; reidenshi - snowfall.flac"),
    ("game", "six_forty_seven", GAME_DIRECTORY, "instupendo - Six Forty Seven.flac"),
    ("game", "on_top", GAME_DIRECTORY, "the girl next door; WMD - ON TOP.flac"),
    ("pursuit", "level", PURSUIT_DIRECTORY, "niqizhuo,Dapper Husky - level ！.flac"),
    ("end", "except_you", END_DIRECTORY, "Cloudrift - Anyone can find love (except you).flac"),
    ("encounter", "especially_you", ENCOUNTER_PHASE_1_DIRECTORY,
     "ZXK - Anyone can find love (especially you.).flac"),
    ("encounter", "lovve", ENCOUNTER_PHASE_2_DIRECTORY,
     "skayanskiy - Lovve (Hikkamorg Remix).flac"),
    ("encounter", "wake_up", ENCOUNTER_PHASE_3_DIRECTORY, "MoonDeity - WAKE UP! (Sped Up).flac"),
    ("ending", "me_and_you", ENDING_DIRECTORY, "VIKI - _ME & YOU_.flac"),
    ("ending", "fallen_down", FAILURE_ENDING_DIRECTORY,
     "The Versions - Fallen Down (Electric Piano Version).flac"),
    ("unrendered", "nice_boys", UNRENDERED_DIRECTORY, "TEMPOREX - Nice Boys（好男孩）.flac"),
)

_LOUDNORM_JSON = re.compile(r"\{[^{}]*\"input_i\".*?\}", re.S)


def measure(ffmpeg: Path, source: Path) -> tuple[float, float]:
    """Integrated loudness and true peak of a master, in LUFS and dBFS."""
    completed = subprocess.run([
        str(ffmpeg), "-hide_banner", "-nostats", "-i", str(source), "-vn",
        "-af", "loudnorm=print_format=json", "-f", "null", "-",
    ], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if completed.returncode != 0:
        raise SystemExit(f"could not measure {source}:\n{completed.stderr}")
    matches = _LOUDNORM_JSON.findall(completed.stderr)
    if not matches:
        raise SystemExit(f"loudnorm printed no measurement for {source}:\n{completed.stderr}")
    measured = json.loads(matches[-1])
    return float(measured["input_i"]), float(measured["input_tp"])


def encode(ffmpeg: Path, source: Path, destination: Path, gain_db: float) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        str(ffmpeg), "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(source), "-vn", "-map_metadata", "-1",
        "-filter:a", f"volume={gain_db:.2f}dB", "-ac", "2", "-ar", str(RATE),
        "-c:a", "libvorbis", "-q:a", QUALITY, str(destination),
    ], check=True)


def convert(ffmpeg: Path, category: str, slug: str, directory: str, filename: str,
            source_root: Path, output_root: Path) -> str:
    source = source_root / directory / filename
    if not source.is_file():
        raise SystemExit(f"missing master: {source}")
    fraction = GAIN_BY_DIRECTORY.get(directory, GAIN)
    target = TARGET_BY_DIRECTORY.get(directory, LOUDNESS_TARGET_LUFS)
    loudness, peak = measure(ffmpeg, source)
    gain_db = target + 20.0 * math.log10(fraction) - loudness
    destination = output_root / category / f"{slug}.ogg"
    encode(ffmpeg, source, destination, gain_db)
    return (f"{category}/{slug}  master {loudness:+.2f} LUFS / peak {peak:+.2f} dBFS"
            f"  ->  {gain_db:+.2f} dB (target {target:g} LUFS x{fraction:g}),"
            f" peak now {peak + gain_db:+.2f} dBFS")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ffmpeg", required=True, type=Path)
    parser.add_argument("--source", required=True, type=Path,
                        help="Directory holding the per-context master folders.")
    parser.add_argument("--output", required=True, type=Path,
                        help="assets/thefourthfrequency/sounds/music")
    parser.add_argument("--only", action="append", metavar="SLUG",
                        help="Re-encode just these slugs. Repeatable. Omit for the whole set.")
    parser.add_argument("--jobs", type=int, default=4,
                        help="Tracks to measure and encode at once.")
    args = parser.parse_args()
    wanted = set(args.only or ())
    unknown = wanted - {slug for _, slug, _, _ in TRACKS}
    if unknown:
        raise SystemExit(f"unknown slug(s): {', '.join(sorted(unknown))}")
    todo = [track for track in TRACKS if not wanted or track[1] in wanted]
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
        futures = [pool.submit(convert, args.ffmpeg, *track, args.source, args.output)
                   for track in todo]
        for future in futures:
            print(future.result())


if __name__ == "__main__":
    main()
