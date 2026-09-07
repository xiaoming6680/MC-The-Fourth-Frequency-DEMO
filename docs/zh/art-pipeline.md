# 美术与资产管线

本文是所有**运行时贴图与音频如何产生**的唯一说明：生成脚本、UV 契约、自发光契约与冻结资产。

终端外观层的布局与配色见[终端界面与手持形态](terminal-ui.md)，配乐调度见[背景音乐](audio.md)，取舍见[设计札记](design-notes.md)。

## 三条硬规则

1. **运行时资产全部由脚本确定性生成。** 没有手绘、没有把参考图缩放粘贴进去。同一份输入重跑一次，输出逐字节相同。
2. **参考图不进游戏。** `docs/art/**` 下的概念图与材质板是给人看的依据，游戏从不加载它们。
3. **贴图自己带光照。** 立方体在游戏里只靠世界光照会塌成一整块剪影，所以每个面另有一个方向系数，再加一圈一像素的环境光遮蔽描边。

## 环境

从仓库根目录运行，Python 需要 Pillow：

```powershell
python tools/<script>.py
```

`docs/art/**` 的文件路径被测试与脚本直接引用（`ResourceContractTest`、`PostFilterContractTest`、`WorldInterfaceAudioManifestTest`、`WorldInterfaceSummonTimelineTest`、`tools/*.py`）。**改名或移动会让 `unitTest` 变红。**

## 生成脚本

| 脚本 | 产出 |
|---|---|
| `export_world_interface_model.py` | 从 `world_interface.bbmodel` 重排 UV 岛并导出运行时几何 JSON 与 `layout.txt` |
| `paint_world_interface_textures.py` | 按 bbmodel 的材质与 UV 岛绘制三形态底图、自发光与受击叠加，外加失败结局的全黑底图 |
| `build_world_interface_model.py` | 一次性脚手架：在运行时骨架上生成初版 bbmodel；重跑会覆盖手工编辑 |
| `generate_world_interface_textures.py` | 遭遇战用到的方块贴图 |
| `prepare_world_interface_wallpaper.py` | 失败结局壁纸，精确 16:10 |
| `generate_world_interface_audio.py` | 世界接口音效库（44.1 kHz），并校验 `docs/art/world_interface/audio_manifest.json` |
| `prepare_stability_anchor_textures.py` | 稳定锚实体贴图与稀疏自发光掩码 |
| `prepare_rework_body_art.py` | 返工体五阶段底图与两张自发光 |
| `prepare_watcher_textures.py` | 观察者底图、眼部掩码与编号 UV 导出图 |
| `prepare_him_textures.py` | HIM 的 64×64 皮肤与仅眼部的自发光掩码 |
| `prepare_entity_textures.py` | 由高分辨率材质母版生成实体贴图的通用管线 |
| `prepare_anomaly_art.py` | 异象 GUI 素材 |
| `generate_terminal_3d_assets.py` | 手持终端：六张 UV 图集、六个物品模型、一份 Blockbench 源 |
| `compose_flat_terminal_panels.py` | 终端面板底图（由同一份控制台几何生成全部阶段） |
| `pixelize_terminal_icons.py` · `generate_soundscape.py` | 终端图标与音频的后处理 |
| `generate_resonance_core_textures.py` | 末地共振核心的像素贴图组 |
| `generate_analog_filter_textures.py` | 模拟信号滤镜在着色器够不到处使用的噪声图层 |
| `generate_soundscape.py` · `audio_materials.py` | 终端与实体音效 |
| `generate_soundscape.py` | 载波、静电、嘶声、黑场与提示音组成的信号床 |
| `generate_soundscape.py` | 首次加载与卡死用的模拟恐怖提示音（每种至少 3 个变体） |
| `generate_unrendered_textures.py` · `generate_soundscape.py` | 未渲染层的四张表面贴图（实心／假面同一配方，只差 `LIGHTNESS_SHIFT`）与细菌的 1 秒循环心跳 |
| `import_music.py` | 从无损母带导入 BGM：先实测对齐到 −24 LUFS，再按比例衰减，烘焙成 Ogg Vorbis |

## 世界接口

BOSS 的几何在 **Blockbench** 里编辑，运行时不再由 Java 生成器烘立方体。编辑源是 `docs/art/world_interface/world_interface.bbmodel`（Modded Entity 格式、box UV），三段管线把它送进游戏：

| 步骤 | 命令 | 产出 |
|---|---|---|
| 导出几何 | `python tools/export_world_interface_model.py` | 重排 UV 岛并写回 bbmodel；输出 `assets/thefourthfrequency/models/entity/world_interface.json`（Java 模型空间的骨骼与立方体）与 `docs/art/world_interface/layout.txt` |
| 绘制贴图 | `python tools/paint_world_interface_textures.py` | 三形态底图、自发光与受击叠加，外加失败结局的全黑底图，以及 `world_interface_uv_template.png` 参考图 |
| 回环校验 | `gradlew exportWorldInterfaceBbmodel` | 把客户端实际烘出的模型再写成 `world_interface_runtime.bbmodel`，用于核对转换链 |

`WorldInterfaceGeometry` 在客户端读 JSON 并烘成 `LayerDefinition`；`WorldInterfaceModel` 只负责哪些骨骼由骨架驱动、哪一形态显示哪一层、哪些骨骼带光。

### 骨骼契约

- **由服务端摆姿势的骨骼必须原样保留。** `hover`、`storm_body`、`interface_kernel`、`weapon`、三条 `{center,left,right}_head_mount → _neck_a → _neck_b → _skull → _jaw` 与十条 `tendril_N → _mid → _tip` 的枢轴和绑定旋转必须与 `WorldInterfaceRig.bindPose()` 一致；`WorldInterfaceGeometryContractTest` 逐骨骼、逐轴比对（容差 0.002）。改动这些枢轴等于把判定框从看得见的部件上挪走。
- 渲染器按名字解析 `shell_base`、`phase_2_accretion`、`phase_3_accretion`、`kernel_glow`、每颗头的 `_eye_0` 与每条触手的 `_glow`；自发光通道只提交这些骨骼，所以要发光的立方体必须挂在它们之下。
- 立方体在 Blockbench 里可以自带旋转；导出时会包成名为 `<骨骼>/<立方体>` 的合成骨骼，因为 `ModelPart` 的立方体本身不能转。每个旋转立方体因此多占一个部件；每形态绘制的部件数受 `WorldInterfaceModel.MAX_VISIBLE_PARTS` 约束，由契约测试按 JSON 计数。
- 坐标约定沿用 Blockbench 自带的 Modded Entity 导出：Blockbench 枢轴相对父级取 `(-dx, -dy, dz)`（根级再减 24 单位抬升），旋转取 `(-rx, -ry, rz)`，立方体 `from..to` 变为 `addBox(pivot.x - to.x, pivot.y - to.y, from.z - pivot.z, size)`。`tools/world_interface_model.py` 与 `WorldInterfaceBbmodelExport` 是这套约定的两个方向。

### 材质与 UV

每个立方体以 `材质.名称` 命名（`bone.center_cranium`、`endstone.chunk_3`）。同材质、同尺寸的立方体共用一个 box UV 岛，导出脚本按整 UV 单位做货架打包。画布 **512×256**，底图 **2048×1024**（密度 4×），自发光与受击叠加 **1024×512**（密度 2×，同一画布）。参考仍是原版末影龙：近黑的皮、几乎没有色相、靠斑驳而不是线条，整张图上唯一饱和的东西是发光的部件。

| 材质 | 用于 | 处理 |
|---|---|---|
| `swallowed` | 吞噬的地表、核心团块 | 皮质斑驳加稀疏矿物斑点 |
| `endstone` | 嵌入的末地石碎块、地层 | 最浅的岩石，细胞更小的斑驳 |
| `obsidian` | 深色地层、导管 | 最深的岩石，偶发冷色裂纹 |
| `plating` | 装甲片、核心框、武器 | 只有斑驳，比本体深一档 |
| `bone` / `tooth` | 颅骨、颚、椎骨、肋 / 牙 | 整张图最浅，一道顶部高光，大面上偶有发丝裂纹，无描边 |
| `horn` | 角、冠、触手倒刺 | 沿长度的生长环 |
| `root` | 根须 | 斑驳沿股向拉长三比一 |
| `flesh` | 颈核、触手节 | 斑驳加短暗条纹 |
| `socket` | 眼窝、鼻腔 | 最深的凹陷 |
| `eye` / `core` / `glow` | 眼孔 / 内核格栅、虹膜 / 触手结节 | 底图只有冷色外壳，自发光图让它燃起来 |

翻滚的部件（`plating`、`endstone`、`obsidian`、`swallowed`、`glow`、`horn`）各向同性上色，其余按面方向上色；所有面都有一像素 ×0.74 的环境光遮蔽描边。

### 自发光契约

只有 `eye`、`core`、`glow`、`socket` 材质的岛可以带光，`validate()` 在别处出现亮像素时直接让脚本失败。眼孔只在北面发光并在中心更亮；眼窝只在下沿留一汪浅光；内核是格栅；触手结节在四个直立面各留一条带，绝不在上下面。受击叠加覆盖全部岛，是唯一仍需整模第二次提交的通道。

### 配色

三个形态靠明度与冷紫渗入的程度区分。皮在每个阶段都是炭黑，末地石从灰黄褪到冷灰，骨头随之变冷；升级由眼孔、内核与 `WorldInterfacePalette` 的自发光色带承担。失败结局换成同一几何的全黑底图；受击叠加在所有形态都是品红 `(255, 42, 88)`。

### 预览

`tools/preview/bbmodel_viewer.html` 用 three.js 按 Blockbench 语义渲染任意 bbmodel（六视角、形态切换、玩家尺度参照、`?texture=` 覆盖贴图）。用仓库根目录起一个静态服务后打开即可，例如 `python -m http.server 8765` 再访问 `tools/preview/bbmodel_viewer.html?model=../../docs/art/world_interface/world_interface.bbmodel&form=2`。

## 观察者

`WatcherModel.createBodyLayer()` 使用 128 单位虚拟画布，两张 256×256 的运行时 PNG 以精确 2× 密度采样该布局。`prepare_watcher_textures.py` 镜像每一个 `texOffs` 与立方体尺寸，导出编号引导图，并在自发光掩码越出眼部 UV 时直接失败。

- `watcher.png`：256×256 RGBA，全部像素 Alpha 255。
- `watcher_emissive.png`：256×256 RGBA，33 个非透明像素（0.05%），最大 Alpha 118。
- 非透明像素只允许落在两块巩膜与四块虹膜环立方体的**北（正面）**上，且全部在冻结窗口 x ∈ [160, 240)、y < 16 内。瞳孔 UV 在自发光图上透明、在底图上近黑。早先的版本给每只眼立方体的每个面都描了边，在暗处读成一个发光矩形，等于把盒子交代了；现在巩膜只沿下沿带一道浅 U，虹膜条彼此留缝，让瞳孔四角落回巩膜而不是闭合成一个亮盒子。
- 底图靠贴图打光：每面按上 1.42 / 北 1.00 / 东西 0.74 / 南 0.58 / 下 0.40 相乘，四个直立面另有 1.15 → 0.62 的自上而下衰减，尺寸大于 2×2 的面再加一圈一像素 ×0.55 的环境光遮蔽描边。
- 渲染层把自发光 Alpha 乘上 `WatcherRenderer` 的客户端注视爬升值，所以没被看着的观察者根本没有亮眼，读成一个无面的剪影。

## HIM

当前材质为 256×256，沿用 64 单位标准人形 UV。灰白凹陷面孔、发黑衣物与局部细小眼光取代旧版平整皮肤。母图经 Blockbench MCP 重排 UV，最终图集位于 docs/art/entities/textures/him_atlas.png；眼部遮罩为 him_atlas_emissive.png。

prepare_him_textures.py 验证身体所有 UV 面不透明，且发光像素仅位于眼区，再原样安装材质。当前遮罩包含 5 个非透明像素。动作保留僵直肢体，增加先转头和长时间倾头停顿。

## 返工体

返工体现在有三个阶段，材质为三张不透明的 256×256 底图及第 2、3 阶段的稀疏自发光图。128×128 逻辑 UV 保持不变。旧第 4、5 阶段资源已删除。

prepare_rework_body_art.py 从 docs/art/rework_body/ 的母板采样材质；细化模型和动作见 [实体编辑入口](../art/entities/README.md)。

## 冻结资产

以下资产**不由任何管线重新生成**，替换它们需要单独决定：

- `textures/gui/anomaly/eye_item.png`
- `textures/gui/anomaly/eye_window.png`
- `tools/assets/anomaly/eye_master.png`

`docs/art/` 下的清单文件同样被视为契约输入：

| 文件 | 谁在读 |
|---|---|
| `world_interface/audio_manifest.json` | `WorldInterfaceAudioManifestTest` · `WorldInterfaceSummonTimelineTest` · `generate_world_interface_audio.py` |
| `world_interface/layout.txt` | `ResourceContractTest` · `export_world_interface_model.py` |
| `world_interface/world_interface.bbmodel` | `WorldInterfaceGeometryContractTest` · `export_world_interface_model.py` · `paint_world_interface_textures.py`（Blockbench 可编辑源） |
| `analog_filter/filter_manifest.json` | `PostFilterContractTest` · `generate_analog_filter_textures.py` |
| `terminal/old_terminal_shell.bbmodel` | `generate_terminal_3d_assets.py`（Blockbench 可编辑源） |

## 音频

音频素材以 44.1 kHz 立体声 Ogg Vorbis（q4）随模组分发。BGM 的播放电平在 `tools/import_music.py` 导入时烘焙进文件，而不是写在 `sounds.json` 里：先用 `loudnorm` 实测每首母带、用纯线性增益平移对齐到 −24 LUFS，再乘一个衰减比例（默认 0.8）。两者都按源文件夹可覆盖：BOSS 战三首走 −17 LUFS、比例 1.0，未渲染层那一首走 −23 LUFS、比例 1.0。两者都相对无损母带的实测值，重新导入不会叠加。调度、分档理由与混音规则见[背景音乐](audio.md)。

卡死故障音是变体池而不是单文件：`alpha_corruption_collapse` 与 `alpha_corruption_warning` 各至少 3 个，同一事件的各变体按 RMS 而非峰值对齐响度。`ResourceContractTest` 断言数量下限、无重复条目、全部为真实 Ogg 且每个大于 16 KB。**技术契约挡不住"不好听"**——加变体前必须先试听。
