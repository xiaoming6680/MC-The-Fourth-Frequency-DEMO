# RC.3 音效重置验收

96 个非音乐事件、242 个 Ogg Vorbis 文件均已重做或新增。10 个音乐事件、21 首音乐文件及原有 BGM 音量保持不变。14 个借用原版的 MOD 声音事件已替换，正常世界的原版声音和友好龙的声音保留。

[40 秒试听合集](preview.ogg) · [片段时间与来源](preview-cues.json) · [完整测量清单](../../art/audio/soundscape_manifest.json)

试听是 20 段近场素材依次播放，统一乘 0.65，片段之间留 0.45 秒；没有游戏内距离衰减或音乐，不能当作最终游戏混音的录屏。

## 接入

- 激光使用已同步的实体动作时钟，90 Tick 蓄力、40 Tick 发射。持续声跟随模型主嘴挂点；双束共用一次发射主体，实际落点独立发声。取消、动作切换、死亡、移除、退出/切换世界时停止。
- 触手伸展、击地和回收分别落在真实攻击阶段；不再在落地时重放预警声。新三形态咆哮、能量爆裂均有明确半径，音量无需超过 1。
- 返工体三阶段脚步/呼吸、细菌近场足音按行走/呼吸周期播放；HIM 保留稀疏、仅观察者听见的衣料声，消失仍安静。Watcher 的消失声仅发给观察者。
- 终端抬起/放下、启动行/完成、按键/旋钮、成功/拒绝反馈区分；关闭/反向操作清理旧机械声。个人锁定提示遵循 MOD 总音量。
- 装饰声预算每世界每 Tick 16 个，按事件和 8 格空间单元去重；客户端每 Tick 6 个足音/呼吸，最多处理最近 16 个装饰实体。关键预警与触手落地不占装饰预算。非音乐变体避免连续同一录音。
- BOSS 常驻底噪在预警期间缓降，再缓慢恢复；BGM 增益不变。

## 验证方法

音频清单逐文件记录完整解码后的采样率、时长、声道、RMS、峰值、4 倍过采样真峰值、循环接缝和 SHA-256。最高实测真峰值 −1.15 dBFS；定位音效为单声道，形态/信号底噪等非定位床允许立体声。重复编码的 Vorbis 字节一致性抽查通过。

新增客户端 `audio` 样例使用真实 SoundManager：解析全部非音乐事件、启动嘴部声源、移动实体检查挂点、进入已开始的发射段、取消动作、断线清理。服务端样例用同一服务器中两名连接玩家检查私人声包只抵达目标，并检查独立于增益的传播半径。单元测试验证资源/hash、循环/峰值、声音预算与步态跨周期行为。

首次引导样例原先以 480 个模拟 Tick 等待实际时间的阅读门槛，英文最后一页可能尚未读完就尝试关闭。已改为有界的 90 秒实际时间等待，并在关闭前明确断言引导完成，保留所有阅读和作答条件。

最终验证：858 个单元测试、99 个必需服务端 GameTests 全部通过；`audio`、`world-interface`、`tools-ui` 三套真实客户端回归全部通过。242 个非音乐文件完整解码与测量校验通过，最终 JAR 包含 263 个音频文件。构建同时验证了 58 个 Mixin 类、21 个 Minecraft 注入目标。

速度回归样例的 20 格跑道原先越过默认测试模板边界，可能碰到相邻样例；已将跑道移到隔离高度，保留原有实体速度和 6.6–7.1 格/秒断言。

RC.3 已部署到 PCL 的 `1.21.11-Fabric 0.19.3/mods`，构建文件与部署文件 SHA-256 相同；旧 RC.2 移出 mods，保存在忽略的 `build/retired-deployment` 中，避免重复加载。

```text
thefourthfrequency-1.0.0-rc.3.jar
SHA-256: 4be0a9b942df13804d2eb03cfafca796eef6f9d21a89742a6e5635f2040fc3d9
```

```text
python -m pip install --target build/tff-audio-tooling -r tools/audio-requirements.txt
python tools/generate_soundscape.py
python tools/generate_soundscape.py --verify-only
python tools/preview_soundscape.py
gradlew.bat runClientGameTest -PtffClientTestSuite=audio
gradlew.bat runClientGameTest -PtffClientTestSuite=world-interface
gradlew.bat runClientGameTest -PtffClientTestSuite=tools-ui
gradlew.bat build
```

游戏 runner 共用运行目录，须串行执行。音效为原创程序合成，未将自动化检查称为耳机/音箱试听通过或 AAA 音质认证；尚未进行多台电脑公网延迟/丢包实战、多人混音主观评审。
