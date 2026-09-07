"""Build a labelled, near-field preview montage from the shipped cues; no music or new assets."""
from pathlib import Path
import json
import sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'build/tff-audio-tooling'))
import numpy as np
import soundfile as sf
import imageio_ffmpeg
from generate_world_interface_audio import encode_via_ffmpeg, RATE

SEQUENCE=[
 ('终端抬起','device/terminal/raise/01',.55),
 ('终端启动行','device/terminal/boot_line/01',.14),
 ('终端启动完成','device/terminal/boot_complete/01',.58),
 ('返工体一阶段','entity/rework_step_1/01',.48),
 ('返工体二阶段','entity/rework_step_2/01',.58),
 ('返工体三阶段','entity/rework_breath_3/01',2.4),
 ('细菌足音','entity/bacteria_skitter/01',.22),
 ('HIM 衣料','entity/him_presence/01',1.15),
 ('Watcher 消失','entity/watcher_vanish/01',1.5),
 ('第一形态腔体','world_interface/roar_1/01',2.8),
 ('第二形态咆哮','world_interface/roar_2/01',3.3),
 ('第三形态共振','world_interface/roar_3/01',4.1),
 ('口部激光蓄力','world_interface/laser/01',4.5),
 ('激光释放','world_interface/laser_fire/01',2),
 ('激光命中','world_interface/laser_impact/01',.8),
 ('激光收束','world_interface/laser_release/01',.7),
 ('触手绷紧','world_interface/tendril/01',1.5),
 ('触手击地','world_interface/tendril_strike/01',1.1),
 ('触手回收','world_interface/tendril_recover/01',.65),
 ('规则崩塌','anomaly/rule_collapse/01',1.5),
]

def main():
    output=ROOT/'docs/qa/audio_overhaul'
    output.mkdir(parents=True,exist_ok=True)
    parts=[];marks=[];time=0
    for label,path,seconds in SEQUENCE:
        data,rate=sf.read(ROOT/f'src/main/resources/assets/thefourthfrequency/sounds/{path}.ogg',always_2d=True)
        assert rate==RATE
        data=data[:round(seconds*RATE)]*.65
        fade=min(512,len(data)//4)
        data[-fade:]*=np.linspace(1,0,fade)[:,None]
        if data.shape[1]==1:data=np.repeat(data,2,axis=1)
        marks.append({'seconds':round(time,2),'label':label,'source':path})
        parts.extend((data,np.zeros((int(.45*RATE),2))))
        time+=len(data)/RATE+.45
    encode_via_ffmpeg(Path(imageio_ffmpeg.get_ffmpeg_exe()),np.concatenate(parts),output/'preview.ogg')
    (output/'preview-cues.json').write_text(json.dumps(marks,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(f'Preview: {time:.1f}s, {len(marks)} cues')

if __name__=='__main__':main()
