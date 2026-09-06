# 文档导航 / Documentation index

《第四频段》RC 1.0.0（`1.0.0-rc.1`）的全部文档。中文是**事实源**，英文是同步翻译；两边冲突时以中文与源码为准。

All documentation for The Fourth Frequency RC 1.0.0 (`1.0.0-rc.1`). Chinese is the **source of truth**; English is a synchronised translation. On any disagreement, the Chinese text and the source code win.

## 目录结构 / Layout

```
docs/
├── README.md          ← 你在这里 / you are here
├── zh/                中文文档 / Chinese documents
├── en/                English documents
└── art/               美术参考图与生成清单（不是文档）
                       art references and generation manifests (not documents)
```

`docs/art/` 下的文件路径被测试与生成脚本直接引用（`ResourceContractTest`、`PostFilterContractTest`、`WorldInterfaceAudioManifestTest`、`tools/*.py`），**改名或移动会让构建失败**。

Paths under `docs/art/` are referenced directly by tests and generators (`ResourceContractTest`, `PostFilterContractTest`, `WorldInterfaceAudioManifestTest`, `tools/*.py`). **Renaming or moving them breaks the build.**

## 文档一览 / The documents

| 中文 | English | 用途 / Purpose | 读者 / Audience |
|---|---|---|---|
| [世界观圣经](zh/world-bible.md) | [World bible](en/world-bible.md) | 不可改写的叙事事实、语言与 Meta 原则 / Narrative facts, voice and meta principles that must not be rewritten | 剧情、文案、美术 / Writing, art |
| [架构与安全边界](zh/architecture.md) | [Architecture](en/architecture.md) | 权威数据流、持久化、协议、预算与保护 / Authoritative data flow, persistence, protocol, budgets | 开发、审核 / Developers, reviewers |
| [背景音乐](zh/audio.md) | [Background music](en/audio.md) | 情境判定表、淡变接缝、曲目轮换与混音余量 / Situation table, fade seams, rotation, mix headroom | 开发、音频 / Developers, audio |
| [终端界面与手持形态](zh/terminal-ui.md) | [Terminal interface](en/terminal-ui.md) | 坐标系、布局常量、配色、动画、首次引导、3D 模型 / Coordinate space, layout, palette, animation, onboarding, 3D model | UI、美术、QA |
| [异象、终端形态与个人追逐](zh/anomalies-and-pursuits.md) | [Anomalies and pursuits](en/anomalies-and-pursuits.md) | 五阶段异象、三形态校正者、私人镜像、滤镜语言 / Five anomaly stages, three pursuit forms, the private mirror, filter languages | 设计、开发、QA |
| [世界接口终局](zh/world-interface.md) | [The World Interface finale](en/world-interface.md) | 仪式、状态机、数值、八类行动、结局与 F8 / Ritual, state machine, numbers, eight actions, endings, F8 | 设计、QA、服主 / Design, QA, server ops |
| [美术与资产管线](zh/art-pipeline.md) | [Art and asset pipeline](en/art-pipeline.md) | 生成脚本、UV 契约、自发光契约、冻结资产 / Generators, UV and emissive contracts, frozen assets | 美术、渲染 / Art, rendering |
| [测试与验收](zh/testing.md) | [Testing and acceptance](en/testing.md) | Gradle 入口、分层覆盖、关键不变量、当前证据 / Entry points, layered coverage, invariants, current evidence | 开发、发布 / Developers, release |
| [人工验收清单](zh/acceptance.md) | [Manual acceptance](en/acceptance.md) | 自动化覆盖不到的逐项人工核对 / What automation cannot cover, item by item | QA、测试玩家 / QA, playtesters |
| [仓库维护指南](zh/maintenance.md) | [Repository maintenance](en/maintenance.md) | 目录结构、事实归属、文档同步规则、发版流程 / Layout, fact ownership, sync rules, release process | 全体 / Everyone |
| [设计札记](zh/design-notes.md) | [Design notes](en/design-notes.md) | 每个数值背后的取舍、被否决的方案与修掉的 BUG / The trade-off behind each number, rejected alternatives, fixed bugs | 开发、设计 / Developers, design |

## 事实归属 / Where each fact lives

一个事实只有一个主人。摘要可以出现在别处，但**数字只在主人那里维护**。

Each fact has exactly one owner. Summaries may appear elsewhere, but **numbers are maintained only at the owner**.

| 主题 / Topic | 主人 / Owner |
|---|---|
| 版本、依赖、产物名 / Versions, dependencies, artefact names | `gradle.properties` · `fabric.mod.json` |
| schema / 协议号 / schema and protocol versions | `PersistenceSchema.CURRENT_VERSION` 等源码常量 / source constants |
| 异象、校正者、镜像规则 / Anomaly, corrector and mirror rules | `zh/anomalies-and-pursuits.md` |
| 终端外观、布局、动画、引导 / Terminal appearance, layout, animation, onboarding | `zh/terminal-ui.md` |
| 终局数值、行动、结局契约 / Finale numbers, actions, ending contracts | `zh/world-interface.md` |
| 配乐情境与接缝 / Music situations and seams | `zh/audio.md` |
| 测试结果与发布物 / Test results and artefacts | `zh/testing.md` |

改动流程与同步清单见[仓库维护指南](zh/maintenance.md) / see [Repository maintenance](en/maintenance.md).
