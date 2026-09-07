# RC.4：动作同步与安静终端

## 实际接入

- 激光共用 90 Tick 蓄力、40 Tick 扫射、18 Tick 收势。嘴部与头颈在扫射期间保持发射姿势；第 130 Tick 起不再伤害或显示嘴部束流。残余光与碎屑留在最后落点，不继续追踪玩家。
- 触手三次落点仍在第 77、122、167 Tick。柔性关节由根部提前发力，末端弯曲峰值与打击时点一致；最后补足 8 Tick 收势，总长 188 Tick，避免末击回位突跳。这是时间同步修复，未加入触手末端精确追踪落点的 IK。
- 中途进入激光动作的客户端按服务器动作年龄定位声音进度；第三形态使用实际发射的侧嘴。取消、收势、退出世界清理持续声。服务端口部瞬态声保留 Vec3 坐标，不再先取整到方块中心。
- 终端开机文字逐行出现、引导步骤切换、普通引导、叙事和未读通知均安静显示。完成、拒绝、追击警告及锚点警告保留轻提示。打开面板不再启动常驻底噪，调谐声仅随实际拨动出现。
- 28 个终端文件改为短促、干净的电子反馈，去掉噪声、低频撞击、失真与混响。文件真峰值最高 −13.88 dBTP，终端播放另乘 0.55；快速接触反馈间隔至少 2 Tick。旧载波与逐行事件 ID 保留兼容，但自动播放代码已移除。
- 开屏以 Beta 0.2.1 首版 OGG 素材为底，保留原始起音后重复其中 74 ms 片段，接近 Windows 死机卡音；没有添加新提示音或噪声层。[原始素材及 Git 来源](../../art/audio/opening/source.json) 保留了来源提交与 SHA-256。第 132 Tick 进入卡音，第 172 Tick 黑屏切断；退出画面也立即清理。去掉持续磁带嘶声、静电、死空气和载波丢失声，按 MOD 总音量播放。警告与崩溃事件同时用于追击表现，其音色随这次资源替换一起更新。

[终端主动操作试听](terminal-clean-preview.ogg) · [开屏卡机试听](opening-crash-preview.ogg)

试听是素材近场预览，不是游戏录音。终端试听依次为打开、按键、切页、完成、拒绝和收起；自动出现的引导文字没有声音。开屏试听压缩了两段之间的等待时间。

激光和触手的新音色仍在 [第二轮样板](../../art/audio/pilot/revision_02/README.md)，等待听感反馈；RC.4 安装包包含动态修复、终端和开屏音效，BOSS 音色维持已接入版本。

## 验证

自动检查不能替代耳听验收。终端试听 3.86 秒 / −17.59 dBTP；开屏试听 4 秒。运行时仅 28 个终端与 6 个开屏文件哈希改变，其他非音乐文件与音乐文件保持原样。

2026-09-07 最终结果：

- 862 项单元测试通过，包括全骨架逐 Tick 跳变检查、发射期持口、收势边界及三击接触时点。
- 99 项真实服务端 GameTests 通过；验证第 130 Tick 停止伤害而动作保留至第 148 Tick。
- `audio` 客户端通过：真实声音解析/播放、迟到蓄力 seek、第三形态侧嘴、取消与退出清理、普通引导不创建声音、开屏卡音替换警告及黑屏停止。
- `world-interface` 客户端通过：三形态/攻击/终局流程与实际束流提取边界；收势只留落点余热。
- `tools-ui` 客户端通过：终端、音量预览、完整首次引导和工具流程。
- 242 个非音乐 OGG 完整解码与清单校验通过。初版底材 SHA-256 验证通过，未编码卡音区逐样本验证 74 ms 周期；新开屏试听真峰值 −15.23 dBTP。
- 最终 remapped JAR 验证通过：59 个 mixin 类、21 个 Minecraft 注入目标；构建包和 PCL 安装包 SHA-256 相同。

本地日志：`build/rc4-audio-final.log`、`build/rc4-world-interface-final.log`、`build/rc4-terminal-final.log`、`build/rc4-assets-final.log`。

已安装到 `E:/PCL2/.minecraft/versions/1.21.11-Fabric 0.19.3/mods/thefourthfrequency-1.0.0-rc.4.jar`。旧 RC.3 校验后移至忽略的 `build/retired-deployments/rc3-before-rc4/`，未删除用户存档或其他模组。PCL 本身未再次启动；验证来自真实 Fabric 测试客户端、服务端和打包检查。

安装包 SHA-256：`E86FC243C46A7D86C976A02FB487660ABCF4E8A19D46D1E0A5500EEE613A0305`。

构建命令：`gradlew.bat unitTest build -PtffDeployDir=`；客户端分套运行 `runClientGameTest -PtffClientTestSuite=audio`、`world-interface`、`tools-ui`。资源检查：`python tools/generate_soundscape.py --verify-only`。

`python tools/generate_soundscape.py --terminal-only` 或 `--opening-only` 可单独重建对应资源，不改写其余音效。`python tools/preview_soundscape.py --terminal-only` 或 `--opening-only` 生成试听。BGM 文件、声音事件和增益未改动。
