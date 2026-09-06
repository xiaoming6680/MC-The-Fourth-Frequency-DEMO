# 实体编辑入口

此目录中的 `.bbmodel` 已通过 Blockbench MCP 编辑、回存。它们包含几何、贴图和从真实运行时代码采样的动作，均可继续编辑。

| 实体 | 工程 | 动作 |
|---|---|---|
| Bacteria | `bacteria.bbmodel` | 甲壳呼吸、八足支撑/摆动步态 |
| Watcher | `watcher.bbmodel` | 注视、锁定、颈部与手指张力 |
| HIM | `him.bbmodel` | 先转头、长时间歪头停顿 |
| 返工体 | `rework_body_stage_1/2/3.bbmodel` | 各阶段监听、追逐、变形 |
| 稳定锚 | `stability_anchor.bbmodel` | 机械张力、折叠崩解 |
| 能量球 | `world_interface_energy_orb.bbmodel` | 核心旋转、外壳错向漂移 |
| 世界接口 | `../world_interface/world_interface_runtime_animated.bbmodel` | 三形态待机与十三类动作 |
| 世界接口命中代理 | 无可见模型 | 跟随服务端骨架，保留不可见 |

`python tools/export_entity_models.py` 将本目录的实体几何写入游戏 JSON。不要运行 `exportEntityBbmodels` 覆盖当前手工细化成果；该任务用于从初始 Java 骨架重新起稿。

游戏动画由 Java 驱动。`gradlew exportEntityMotion` 输出可再生的 `motion/*.json`；通过 MCP 执行 `tools/blockbench_entity_motion.js` 可将其导入对应模型。编辑器中的动作是 10 Hz 的运行时动作样本，使用单次播放，避免把非周期曲线强行拼成循环；游戏使用连续曲线。修改编辑器关键帧不会自动改写 Java 攻击时序。

HIM 的最终 256×256 材质与眼部遮罩保存在 `textures/him_atlas*.png`。`python tools/prepare_him_textures.py` 验证所有身体 UV 面不透明、发光仅位于眼部，然后安装到游戏资源。AI 绘制母图 `textures/him_horror_master.png` 经 `tools/blockbench_him_uv.js` 在 MCP 内重新排成 64 单位的标准人形 UV。

专属丝状、余烬、符文粒子的原生材质工程为 `textures/storm_particles.bbmodel`，生成脚本为 `tools/blockbench_storm_particles.js`。
