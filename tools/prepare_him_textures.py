#!/usr/bin/env python3
"""Validate and install the 256px HIM atlas authored through Blockbench MCP.

The logical player UV is 64px. Source artwork and native UV assembly are preserved
in docs/art/entities/textures and tools/blockbench_him_uv.js.
"""
from pathlib import Path
from shutil import copyfile
from PIL import Image
ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/art/entities/textures"
TARGET = ROOT / "src/main/resources/assets/thefourthfrequency/textures/entity"
PARTS = [(0,0,8,8,8),(16,16,8,12,4),(40,16,4,12,4),(32,48,4,12,4),(0,16,4,12,4),(16,48,4,12,4)]
def validate():
    skin = Image.open(SOURCE / "him_atlas.png").convert("RGBA")
    glow = Image.open(SOURCE / "him_atlas_emissive.png").convert("RGBA")
    assert skin.size == glow.size == (256,256)
    for u,v,w,h,d in PARTS:
        for x,y,rw,rh in [(u+d,v,w,d),(u+d+w,v,w,d),(u,v+d,d,h),(u+d,v+d,w,h),(u+d+w,v+d,d,h),(u+2*d+w,v+d,w,h)]:
            assert skin.getchannel("A").crop((x*4,y*4,(x+rw)*4,(y+rh)*4)).getextrema() == (255,255), (u,v,x,y)
    lit = 0
    for y in range(256):
        for x in range(256):
            if glow.getpixel((x,y))[3]:
                assert 32 <= x < 64 and 40 <= y < 56, (x,y)
                lit += 1
    assert 4 <= lit <= 80, lit
    return lit
if __name__ == "__main__":
    lit = validate()
    TARGET.mkdir(parents=True,exist_ok=True)
    for src,dst in [("him_atlas.png","him.png"),("him_atlas_emissive.png","him_emissive.png")]:
        copyfile(SOURCE/src,TARGET/dst)
    print(f"Installed HIM 256x256 atlas; {lit} eye-only emissive pixels")
