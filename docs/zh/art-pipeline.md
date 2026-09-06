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
| `world_interface_uv.py` | 世界接口 UV 布局的唯一事实源；`--emit-java` 同时写出模型侧偏移表 |
| `prepare_world_interface_textures.py` | 三形态的底图、自发光与受击叠加，外加失败结局的全黑底图 |
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
| `pixelize_terminal_icons.py` · `remaster_terminal_audio.py` | 终端图标与音频的后处理 |
| `generate_resonance_core_textures.py` | 末地共振核心的像素贴图组 |
| `generate_analog_filter_textures.py` | 模拟信号滤镜在着色器够不到处使用的噪声图层 |
| `generate_terminal_audio.py` · `generate_entity_audio.py` | 终端与实体音效 |
| `generate_signal_bed_audio.py` | 载波、静电、嘶声、黑场与提示音组成的信号床 |
| `generate_alpha_corruption_audio.py` | 首次加载与卡死用的模拟恐怖提示音（每种至少 3 个变体） |
| `generate_unrendered_textures.py` · `generate_unrendered_audio.py` | 未渲染层的四张表面贴图（实心／假面同一配方，只差 `LIGHTNESS_SHIFT`）与细菌的 1 秒循环心跳 |
| `import_music.py` | 从无损母带导入 BGM：先实测对齐到 −24 LUFS，再按比例衰减，烘焙成 Ogg Vorbis |

## 世界接口

模型 `WorldInterfaceModel.createLayer()` 声明 **256×128** 画布，PNG 是 **1024×512**，因此密度 **4×**——第三形态放大 16 倍后仍是一格四分之一一个纹素。画布宽而扁，因为打包器实际只填到 89 行；方形画布会让每张 PNG 都为三分之二的空白付钱。

模型由程序化生成器烘出几百个立方体，手写岛屿表会在任何一个生成参数变动时立刻过期。所以 `world_interface_uv.py` **重新推导模型将要构建的每一个立方体**（逐字镜像生成器及其散布哈希），按尺寸分桶、每桶打一个岛，并输出模型的偏移表；模型与绘制脚本读的是同一个模块。

- **69 个岛，画布利用率 59.4%。**
- **三个形态共用一套布局。** 各阶段的部件是同一批材质，变的只是配色，而三张 PNG 已经承载了配色。
- **按尺寸分桶。** 一个成员尺寸相差 6 倍的类别（本体从 5 单位到近 28 单位）会分到多个岛，每个立方体用生成器分桶时的同一个标量挑岛。共用一个岛会让小成员采样到大成员材质的一角。
- **桶必须连续。** 出现空桶时脚本直接拒绝运行，否则 Java 侧的表会短于它的边界数组，模型会索引越界。

两半必须一起重新生成——偏移表与画好的图不一致等于把部件指向没有画过东西的矩形：

```bash
python tools/world_interface_uv.py --emit-java && python tools/prepare_world_interface_textures.py
```

### 材质与浮雕

参考的是原版末影龙：近黑的皮、几乎没有色相、靠斑驳而不是线条、整张图上唯一饱和的东西是那只发光的眼。早先有一版走了反方向——网格状的方块缝、拉丝金属、骨头描边、强烘焙光照——在 BOSS 尺度上把身体变成了一张技术制图。**材质之间靠明度和斑驳的形状区分，永远不靠画出来的线。**

| 材质 | 用于 | 处理 |
|---|---|---|
| `swallowed` | 本体、碎块 | 皮质斑驳加稀疏的单纹素矿物斑点，几乎不离基准明度——它吞掉的地形只剩这些 |
| `plating` | 装甲、光环、闸片、武器、核心插座 | 只有斑驳，比本体深一档 |
| `bone` | 颅骨、眉骨、颚、角、椎骨、肋、颌、牙 | 整张图上最浅的材质，一道柔和顶部高光，无描边 |
| `root` | 根须 | 斑驳单元沿股向拉长三比一，纤维顺着长度走 |
| `flesh` | 触手节 | 斑驳加偶发的短暗条纹 |
| `socket` | 骷髅眼窝 | 眉骨下最深，向眼眶下沿开口 |
| `core` | 瞳、裂隙、内环、触手结节 | 底图上只有冷色外壳，是自发光图让它燃起来 |
| `sclera` | 眼球 | 浅色，淡淡的斜向血丝 |

浮雕加在材质之上。末影龙几乎不烘焙方向明度，但那么平在这里活不下来——龙是十几个大部件，这里是几百个小部件，没有区分就会并成一整块剪影。所以做法是把范围压狠，而不是取消：

- **方向性部件**（本体、肋、根须、骷髅、眼、颌、武器）：每面系数上 1.16 / 北 1.00 / 东西 0.90 / 南 0.82 / 下 0.74，四个直立面另有 1.05 → 0.92 的自上而下衰减。
- **各向同性部件**（装甲、碎块、光环、触手结节）：统一 0.94。模型会在三个轴上翻滚它们，烘一个"顶部更亮"在盒子倒过来之后比不加还糟。
- **所有面**：一像素 ×0.74 的环境光遮蔽描边。这是把上百个相邻盒子分成上百个可读盒子的最便宜手段，对各向同性部件来说也是它们唯一的浮雕。

### 自发光契约

只有这些岛可以带光，`validate()` 在别处出现亮像素时直接让构建失败：`eye_{1,2,3}_pupil`、`eye_3_slit`、`socket`、`tendril_glow`、`ring_inner`。

- 每张自发光图 **2,220 个非透明像素——占画布 0.42%**，全部落在打包好的岛行里。
- **朝前的部件只在北面发光。** 从各个角度都会被看到的部件（眼窝、触手结节、内环）在四个直立面发光，但绝不在上下面——一个六面都亮的盒子只是在宣告自己是个盒子。
- 眼窝沿下沿是一汪浅浅的光而不是填满的矩形，于是骷髅读成"眼窝后面有东西"，而不是"装了灯的立方体"。
- 内环带是唯一在自发光岛上的环。第三形态的光环已经大到把整圈点亮会淹掉它本该框住的核心。

`WorldInterfaceRenderer` **只提交带光的骨骼**——核心、光环、六个骷髅眼窝与激活的触手结节——而不是把整个模型再走一遍。0.42% 的覆盖率下，遍历第三形态的几百个部件意味着几乎每个顶点都进了一次半透明通道去画一片空白。受击叠加是例外：它必须够到光从不触及的装甲与碎块，所以那一下仍然付一次完整提交，持续几个 Tick。

### 配色

三个形态靠明度、以及有多少冷紫渗进灰里来区分，而不是一路变得更紫。皮在每个阶段都是炭黑；升级由核心和 `WorldInterfacePalette` 里的自发光色带承担，那才是玩家真正读出阶段的地方。

| 形态 | 读法 | 皮色 |
|---|---|---|
| 1 · 初生 | 大部分还是它吞下的世界。石灰色，骨头还没变暗 | `(52, 50, 55)` |
| 2 · 长成 | 灰变冷了，骨头跟着一起 | `(44, 41, 50)` |
| 3 · 终末 | 皮黑到核心是它身上唯一有颜色的东西 | `(34, 31, 40)` |
| 全黑 | 失败结局：同一副几何，光全灭了 | `(15, 14, 17)` |

受击叠加在所有形态都是品红 `(255, 42, 88)`。

## 观察者

`WatcherModel.createBodyLayer()` 使用 128 单位虚拟画布，两张 256×256 的运行时 PNG 以精确 2× 密度采样该布局。`prepare_watcher_textures.py` 镜像每一个 `texOffs` 与立方体尺寸，导出编号引导图，并在自发光掩码越出眼部 UV 时直接失败。

- `watcher.png`：256×256 RGBA，全部像素 Alpha 255。
- `watcher_emissive.png`：256×256 RGBA，33 个非透明像素（0.05%），最大 Alpha 118。
- 非透明像素只允许落在两块巩膜与四块虹膜环立方体的**北（正面）**上，且全部在冻结窗口 x ∈ [160, 240)、y < 16 内。瞳孔 UV 在自发光图上透明、在底图上近黑。早先的版本给每只眼立方体的每个面都描了边，在暗处读成一个发光矩形，等于把盒子交代了；现在巩膜只沿下沿带一道浅 U，虹膜条彼此留缝，让瞳孔四角落回巩膜而不是闭合成一个亮盒子。
- 底图靠贴图打光：每面按上 1.42 / 北 1.00 / 东西 0.74 / 南 0.58 / 下 0.40 相乘，四个直立面另有 1.15 → 0.62 的自上而下衰减，尺寸大于 2×2 的面再加一圈一像素 ×0.55 的环境光遮蔽描边。
- 渲染层把自发光 Alpha 乘上 `WatcherRenderer` 的客户端注视爬升值，所以没被看着的观察者根本没有亮眼，读成一个无面的剪影。

## HIM

标准 64×64 玩家布局，原版人形网格因此不加改动就能贴上去，`HimModel` 不需要任何自定义几何。**剪影必须严格等于 Steve**：这东西在屏幕上只有五分之一秒，任何有自己比例的形体都会读成一只自定义生物而不是一个人。

无法走捷径的原因写在这里：经典造型就是把默认玩家皮肤的眼睛涂掉，但那张默认皮肤是 Mojang 自己的资产，而粉丝改的各种版本是第三方作品，两者都不能随模组分发。所以这张皮肤和本项目其他实体一样从零确定性生成，读起来是那个传说，却不是任何人的文件。

| 部位 | UV | 材质 |
|---|---|---|
| 头 | `(0, 0)` 8×8×8 | 直立面上两行是头发，其下是皮肤 |
| 躯干 | `(16, 16)` 8×12×4 | 上衣 |
| 手臂 | `(40, 16)` / `(32, 48)` 4×12×4 | 袖子到第 8 行，其下是皮肤 |
| 腿 | `(0, 16)` / `(16, 48)` 4×12×4 | 裤子到第 10 行，其下是鞋 |

**自发光只有八个像素**：头部北面两块 2×2 的空矩形，别处一个都不许有，`validate()` 会让构建失败。它们**故意是空的**——画个瞳孔就变成"一个角色在看你"，两个什么都没有的亮槽才是"一个没有眼睛却正对着你的东西"，那才是这个传说真正的读法。

配色刻意压低。在它被放置的距离上，饱和的衣服会读成一个穿着戏服的玩家，而戏服会招来第二眼——那正是绝不能发生的事，因为第二眼什么都找不到。

与观察者的眼不同，这一处不以"被注视"为门槛：观察者用两秒的凝视爬升自己的辉光，而这东西一共只有五分之一秒，摊在那个窗口上的渐显永远到不了。

## 返工体

`docs/art/rework_body/` 下的两张母板是生产参考，不是运行时贴图。`prepare_rework_body_art.py` 把材质板采样进模型的语义 UV 岛，加上各阶段的淤伤、筋膜、坏死、骨骼与面部细节，写出七张 256×256 的运行时 PNG。

- 五张底图是 RGB 不透明 256×256。
- 第四、五阶段的自发光是稀疏 RGBA 256×256，背景透明。
- 模型使用 128×128 虚拟 UV 画布，所以一个模型纹素对应 2×2 资源像素。
- `rework_body_uv_guide.png` 记录了绘制脚本使用的语义岛包络。

## 冻结资产

以下资产**不由任何管线重新生成**，替换它们需要单独决定：

- `textures/gui/anomaly/eye_item.png`
- `textures/gui/anomaly/eye_window.png`
- `tools/assets/anomaly/eye_master.png`

`docs/art/` 下的清单文件同样被视为契约输入：

| 文件 | 谁在读 |
|---|---|
| `world_interface/audio_manifest.json` | `WorldInterfaceAudioManifestTest` · `WorldInterfaceSummonTimelineTest` · `generate_world_interface_audio.py` |
| `world_interface/layout.txt` | `ResourceContractTest` · `world_interface_uv.py` |
| `analog_filter/filter_manifest.json` | `PostFilterContractTest` · `generate_analog_filter_textures.py` |
| `terminal/old_terminal_shell.bbmodel` | `generate_terminal_3d_assets.py`（Blockbench 可编辑源） |

## 音频

音频素材以 44.1 kHz 立体声 Ogg Vorbis（q4）随模组分发。BGM 的播放电平在 `tools/import_music.py` 导入时烘焙进文件，而不是写在 `sounds.json` 里：先用 `loudnorm` 实测每首母带、用纯线性增益平移对齐到 −24 LUFS，再乘一个衰减比例（默认 0.8）。两者都按源文件夹可覆盖：BOSS 战三首走 −17 LUFS、比例 1.0，未渲染层那一首走 −23 LUFS、比例 1.0。两者都相对无损母带的实测值，重新导入不会叠加。调度、分档理由与混音规则见[背景音乐](audio.md)。

卡死故障音是变体池而不是单文件：`alpha_corruption_collapse` 与 `alpha_corruption_warning` 各至少 3 个，同一事件的各变体按 RMS 而非峰值对齐响度。`ResourceContractTest` 断言数量下限、无重复条目、全部为真实 Ogg 且每个大于 16 KB。**技术契约挡不住"不好听"**——加变体前必须先试听。
