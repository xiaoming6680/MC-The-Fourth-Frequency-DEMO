# 激光与触手：第二轮试听

按用户反馈调整为高能武器和巨型肢体。此目录仍是待试听样板；没有替换游戏内 BOSS 音频。终端与开屏音效另已接入 RC.4。

| 组别 | 新版 | 上版 A / 新版 B | 编排 |
| --- | --- | --- | --- |
| 激光 | [试听](mouth_laser_new.ogg) | [同响度对照](mouth_laser_AB.ogg) | 4.5 秒蓄力，猛烈起射，2 秒锐利束流，收束 |
| 触手 | [试听](tendril_strike_new.ogg) | [同响度对照](tendril_strike_AB.ogg) | 1.6 秒承重和破风，重物触地，0.25 秒后拖拽回收 |

使用 CC0 素材剪辑、变速、滤波与分层；没有调用 AI，也没有添加独立的随机底噪。激光排气和触手破风属于动作层。Kenney 的 Sci-fi Sounds、Impact Sounds，以及 OwlishMedia 的 Sound Effects Pack，其发布页、下载地址、素材切片、分层参数和 SHA-256 均见 [manifest.json](manifest.json)。有损源文件没有被标称为无损原录音。

复现：先按上级目录 brief.md 准备基础来源，再运行 `python tools/revise_boss_audio_pilot.py`。新来源保存在忽略的 `build/audio-pilot/sources/`，32-bit float 分层位于 `build/audio-pilot/revision_02/stems/`。

8 个 48 kHz 单声道 OGG 完整解码、时长、真峰值、有限数值与循环端点检查通过。两组 A/B 按综合响度匹配；四个试听文件真峰值介于 −6.04 与 −4.19 dBTP。循环端点修正使用 5 ms 局部过渡，没有放宽接缝阈值。

这是近场编辑试听，尚未经过人工听感认可或游戏混音验收，不能据此宣称 AAA 品质。
