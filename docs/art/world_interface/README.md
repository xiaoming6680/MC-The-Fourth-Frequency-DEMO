# 世界接口最终模型

- `world_interface.bbmodel`：游戏几何编辑源，包含三形态共用骨架与分层细节。
- `world_interface_runtime_animated.bbmodel`：Blockbench 原生动画母版，包含最终材质和从游戏采样的 16 个动作。
- `world_interface_storm_form1/2/3_detail_v2.bbmodel`：保留用于造型重建的三形态雕刻源。
- `world_interface_storm_concept_v2.png`：当前造型参考。早期被替代的重建草图、白模和重复动画输出已清理。

正常迭代使用 `python tools/export_world_interface_model.py` 导出，再运行 `python tools/paint_world_interface_textures.py` 绘制匹配 UV 的材质。运行时加载 JSON，动画与命中位置共同使用 `WorldInterfaceRig`。

每条触手有 18 个连续活动关节。末端发光挂在最后的 `tip_flex_5` 下；激光、能量球与嘴部位置共用骨架取样，触手三次抽击峰值对齐服务端实际伤害时刻。

仅在从雕刻源重建时使用 `node tools/animate_world_interface_storm.cjs` 生成临时动画工程，再执行 `tools/integrate_storm_detail.py`。该过程会覆盖几何编辑源，不应在一般贴图或关键帧调整中运行。`gradlew exportWorldInterfaceBbmodel` 生成的 `world_interface_runtime.bbmodel` 和中间动画文件属于可再生缓存，不提交 Git。

原生动画工程是游戏曲线的编辑器参考；动画行为的权威实现仍为 Java。新改动需重新导出动作并通过 MCP 更新，不能只改工程而遗漏游戏逻辑。
