# 仓库维护指南

本文写给后来维护这个仓库的人（包括未来的自己）：文件放在哪、一个事实归谁管、改了什么要同步什么、怎么发版。

## 目录结构

```
MC-The-Fourth-Frequency/
├── README.md / README.en.md      玩家可见总览（双语）
├── LICENSE                       All Rights Reserved
├── .gitattributes                行尾与二进制归类 —— 见下文《行尾与文件模式》
├── build.gradle                  Loom、source set、unitTest、客户端套件参数
├── gradle.properties             版本与依赖的唯一事实源
├── settings.gradle
├── docs/
│   ├── README.md                 双语文档导航与事实归属表
│   ├── zh/                       中文文档（事实源）
│   │   └── design-notes.md       取舍与被否决方案（专题文档只写规则）
│   ├── en/                       英文文档（同步翻译）
│   └── art/                      参考图与清单文件 —— 路径被测试引用，勿动
├── src/
│   ├── main/java/…               common：权威玩法、持久化、协议、策略
│   ├── main/resources/           fabric.mod.json、mixin 清单、资源、语言、数据、着色器
│   ├── client/java/…             渲染、UI、声音调度、mixin、本地演出
│   ├── test/java/…               纯 JUnit、资源与跨文件契约
│   └── gametest/java/…           服务端与客户端 Minecraft 运行时验收
├── tools/                        可重复资产管线（Python + Pillow）
│   └── assets/                   母板素材
└── archive/                      本地归档，不进 Git
```

`bin/`、`build/`、`run/`、`logs/`、`.gradle/`、`.planning/`、`.claude/` 全部由 `.gitignore` 排除，属于本机产物或代理状态。

### 行尾与文件模式

这两条只在别人的机器上、或第一次把仓库推上 GitHub 时才会现形，所以写在这里：

- `.gitattributes` 用 `* text=auto eol=lf` 把所有文本 blob 钉成 LF，`*.bat` 与 `*.cmd` 例外为 CRLF；音频、图片与 JAR 显式声明 `binary`，不做行尾转换也不做文本合并。没有这份文件时行尾取决于每个人的 `core.autocrlf`，克隆到 Linux/macOS 的 `gradlew` 可能带着 CR，直接 `bad interpreter`。
- `gradlew` 在索引里的模式必须是 `100755`。Windows 的文件系统不带执行位，所以它只能靠 `git update-index --chmod=+x gradlew` 记进索引；模式掉回 `100644` 时，Windows 上一切正常，Linux/macOS 与 CI 上 `./gradlew` 直接 permission denied。

## 硬约束

改动前先确认这几条，它们的违反方式都是"编译通过但运行时/构建时炸"：

| 约束 | 违反后果 |
|---|---|
| `docs/art/**` 的路径与文件名 | `ResourceContractTest`、`PostFilterContractTest`、`WorldInterfaceAudioManifestTest`、`WorldInterfaceSummonTimelineTest` 与多个 `tools/*.py` 直接按路径读取，改名即测试变红 |
| `src/main/resources/thefourthfrequency.mixins.json` 使用 `defaultRequire: 1` | 新增/删除/重命名 mixin 必须同步清单，否则加载期直接崩 |
| mixin 的 `@At` target 必须带 owner，`@Shadow` 字段必须声明在目标类自身 | 四层测试全在 named 环境下会一起绿，只有真实 remap JAR 会在 bootstrap 崩 |
| `zh_cn.json` 与 `en_us.json` 键集合完全对称 | 契约测试断言双向对称，少一个键即失败 |
| `TerminalScreen.java` 顶部的 `import static` 行 | `ResourceContractTest` 用源码文本断言配色引用，IDE 的 optimize imports 会静默打断它 |
| 协议载荷只能在**末尾追加**字段 | 解码按位置进行，中间插一个布尔会让其后每个 varint 静默错位 |
| 「玩家不在他所居住的世界里」只能问 `PrivateDimensions.isPrivate` | 直接写 `PursuitDimensions.isMirror` 会漏掉未渲染层，且不会有任何测试失败或日志——十七处环境系统曾经全是这样。`MultiplayerIsolationContractTest` 从源码守住 |
| `AnomalyCatalog.MASK_ORDER` 只能在**末尾追加** | 它是 `ANOMALY_SEEN_MASK` 的位序，重排会静默给旧存档一份它从未见过的异象历史 |
| 新增异象要同步六处 | 目录、`AnomalyTiming`、`AnomalyConditions`、`AnomalyServerEffects`、`DebugNames`、双语 `terminal.thefourthfrequency.log.type.<id>`；漏任何一处都由现有契约测试报出来，但报的是断言而不是原因 |

## 事实归属

一个事实只有一个主人。摘要可以出现在别处，但**数字只在主人那里维护**；冲突时按这个顺序裁决：

1. 当前源码中的权威策略、状态机、schema、协议与资源契约；
2. 与当前工作区对应、**实际运行完成**的测试证据；
3. 负责该领域的专题文档（`docs/zh/*`）；
4. README 中的摘要；
5. 历史计划、`archive/` 与旧构建产物——只提供线索，不能覆盖当前事实。

不要把 `build/libs` 里较旧的 JAR、旧测试数字或文档里的历史记录当作当前实现证据。

| 主题 | 主人 |
|---|---|
| 版本、依赖、产物名 | `gradle.properties` · `src/main/resources/fabric.mod.json` |
| schema / 协议号 | `PersistenceSchema.CURRENT_VERSION`、各 `*Payload.CURRENT_PROTOCOL_VERSION`、`WorldInterfaceState.FORMAT_VERSION`、`WorldInterfaceProtocol.VERSION` |
| 世界级主线、文件、发现、兼容迁移 | `FrequencyWorldData` |
| 终局状态 | 独立的 `world_interface` 持久化根（`WorldInterfaceState.FORMAT_VERSION`，当前 v2） |
| 异象、校正者、镜像规则 | `docs/zh/anomalies-and-pursuits.md` |
| 终端外观、布局、动画、开机引导 | `docs/zh/terminal-ui.md` |
| 终局数值、行动、结局契约 | `docs/zh/world-interface.md` |
| 音效输出增益、配乐情境与接缝 | `docs/zh/audio.md` |
| 资产生成与 UV/自发光契约 | `docs/zh/art-pipeline.md` |
| 测试结果与发布物 | `docs/zh/testing.md` |
| 数值背后的取舍与历史故障 | `docs/zh/design-notes.md` |

## 改动同步矩阵

每次 Git 提交前，按改动影响核对中英文 README、专题文档、配置说明和实际测试证据；发现不一致时与代码一起修正。历史记录保留原版本与验证范围，不能冒充当前证据。**提交标题和正文统一使用中文。**

| 改动类型 | 至少同步 |
|---|---|
| 玩家可见规则/数值 | 权威策略、运行时消费者、HUD/终端、单测、GameTest、README（中英）、验收、对应专题文档 |
| 终端 / UI / 文案 | 服务端快照、客户端布局与退出路径、`zh_cn.json`、`en_us.json`、资源键、UI 测试、`terminal-ui.md` |
| schema / 协议 / 载荷 | 版本常量、编解码、双方消费者、迁移与旧版拒绝、契约测试、`architecture.md` |
| 异象 / 追逐 | 许可与实际阶段、个人隔离、镜像维度排除、加载遮蔽、物品退款、断线/死亡/重启清理、多人并发、`anomalies-and-pursuits.md` |
| 世界接口 / 结局 | 名单事务、状态机、Tick 裁定顺序、场地保护、客户端音画、诗篇与资源包、LAN 分流、F8 恢复、`world-interface.md` |
| 客户端生命周期 / 音频 | 进入与离开世界、换维度、overlay、资源重载、`MusicDirector`、相关 mixin、停止与恢复路径、`audio.md` |
| 资产 / 资源 | 注册表或 `sounds.json`、双语键、实际文件、生成脚本、`docs/art/` 清单、资源契约测试、`art-pipeline.md` |

**双语同步是同一次改动的一部分。** 中文是事实源，但 `docs/en/` 不允许长期落后——它落后的那一刻，两份文档就开始各自维护出不同的事实。

## 验证

| 层 | 命令 |
|---|---|
| 编译 common、client 与资源 | `.\gradlew.bat compileJava compileClientJava processResources --no-daemon` |
| 聚合 JUnit 与资源/跨文件契约 | `.\gradlew.bat unitTest --no-daemon` |
| 服务端 Minecraft 运行时 | `.\gradlew.bat runGameTest --no-daemon` |
| 客户端套件（默认 `all`） | `.\gradlew.bat runClientGameTest --no-daemon` |
| 定向客户端套件 | `.\gradlew.bat runClientGameTest -PtffClientTestSuite=world-interface --no-daemon` |
| 干净发布构建 | `.\gradlew.bat clean build --no-daemon` |
| 产物校验（真实 remap 后的 JAR） | `.\gradlew.bat verifyRemappedJar --no-daemon` |

- **编译成功不等于验收通过。** GameTest、客户端演出与人工验收各管各的一层。
- **只报告本轮实际完成的验证。** 未运行、超时或被并行构建干扰的项必须明确写出。
- `unitTest` 用 `--select-class` 显式枚举每个编译产物中的测试类，不用 classpath 扫描——扫描会在类加载失败时静默丢掉整个测试类。**不要把它改回扫描。**

## 发版流程

1. 改 `gradle.properties` 的 `mod_version`。这是版本号的唯一事实源；`fabric.mod.json` 用 `${version}` 占位，`processResources` 会填。
2. 全仓库搜一遍旧版本串（README 中英、`docs/zh`、`docs/en`），确认没有残留。
3. `.\gradlew.bat clean build --no-daemon`。任务图会依次跑 `unitTest` 与服务端 GameTest。
4. 跑客户端套件；至少跑一次未筛选的 `all`。
5. 按 [人工验收清单](acceptance.md) 完成自动化覆盖不到的部分。
6. 把本轮实际结果、JAR 字节数与 SHA-256 写进 [测试与验收](testing.md) 的「发布物」与「当前证据」。
7. 提交并打 tag。

### 本地部署

`build` 成功后可以把 remap JAR 复制到一个本地 Minecraft 实例的 `mods` 目录。**这一步默认关闭**：目标路径是某一台机器上的启动器目录，而 `build.gradle` 是要进 Git 的，写死在里面等于把个人环境发布出去。

要长期启用，把它写进 **Git 看不到的**用户级 `~/.gradle/gradle.properties`（Windows 是 `%USERPROFILE%\.gradle\gradle.properties`）：

```properties
tffDeployDir=E:/SomeLauncher/.minecraft/versions/1.21.11-Fabric/mods
```

只想临时用一次，或临时关掉：

```powershell
# 本次构建部署到指定目录
.\gradlew.bat build -PtffDeployDir="D:\SomeLauncher\.minecraft\mods" --no-daemon

# 本次构建不部署（也是没有配置时的默认行为）
.\gradlew.bat build -PtffDeployDir= --no-daemon
```

没有配置时构建只是打印一行说明；配置了但目标盘不存在时会跳过并给出提示，两种情况都不会让构建失败——所以在别人的机器上 clone 之后 `build` 照常可用。**这一步挂在 `build` 而不是 `remapJar` 上**：编译或测试失败绝不能覆盖上一个已知可用的 JAR。

## 归档规则

`archive/` 不进 Git（见 `.gitignore`）。删掉一份文档时，把它移进 `archive/superseded-docs/` 并在 `archive/README.md` 里写清内容去了哪里，而不是直接删——合并时漏掉一段的成本远高于留一份本地副本。

`archive/` 中的任何内容都不是事实源，也不应被 `docs/` 链接。
