# ohd — 移动端真机能力工具箱

把「操作真机 / 取证 / 性能观测」收敛为一个跨平台 CLI：默认给人读的紧凑输出，`--json` 给 agent 消费，产物统一归档并带 manifest。

平台：**OHOS**（hdc）/ **Android**（adb）/ **iOS**（simctl 模拟器、devicectl 真机、idb 触摸注入）。`--platform` 可显式指定，默认自动探测。

设计文档：[`DESIGN.md`](DESIGN.md)（含能力清单、证据包规范、跨端细则、里程碑）。
给 AI 承接用的精简上下文：[`LLM_CONTEXT.md`](LLM_CONTEXT.md)（新会话先读这份，按需再查 DESIGN）。

## 前置

| 依赖 | 说明 |
|---|---|
| JDK 17+ | 测试机/开发机已确认具备 |
| Xcode 15+（可选） | iOS：`simctl`（模拟器）、`devicectl`（真机），仅 macOS |
| idb（可选） | iOS 的触摸注入与 UI 层级：`brew tap facebook/fb && brew install idb-companion` + `pip3 install fb-idb` |
| hdc | OpenHarmony SDK / DevEco Studio 自带；也可 `--hdc` 或 `HDC` 环境变量指定 |

## 构建（显式执行，工具本身不会自动构建）

```bash
./build.sh                 # 仓库根入口：构建（等价 ./dist/build.sh）
./build.sh install         # 构建 + 装进 PATH，之后任意目录直接 ohd
./build.sh package         # 打自包含分发包（自带精简 JRE，同事解压即用）
./build.sh smoke           # 冒烟（需真机）
./build.sh all             # build + install + package
./build.sh uninstall       # 移除装好的启动器

./dist/build.sh            # 只构建（macOS / Linux）
dist\build.bat             # 只构建（Windows）
```

`install` 默认装到 `~/.local/bin/ohd`，用 `--prefix <dir>` 改目录，`--no-build` 复用已有产物。装的是包装脚本而不是软链——`dist/ohd` 靠自身所在目录定位 jar，软链会指错层。

产物：`ohd-cli/build/install/ohd/bin/ohd`。若机器上已有 `~/.gradle/wrapper/dists` 下的 Gradle 发行版，`build.sh` 会直接复用，无需另装。

## 快速验证

```bash
./dist/ohd selftest        # 离线自检：不碰设备，先证明这台机器能跑
./dist/ohd doctor          # 环境自检：hdc / 设备 / uitest / faultlog / llvm / 磁盘
./dist/ohd env-dump --json # 设备指纹（证据包 env 来源）
```

同事在 Windows/Linux 上第一次用，先跑 `selftest`；失败项原样贴回即可定位。

每次操作的证据落在当前目录的 `ohd-evidence/device/<时间戳>-<能力>/`（含 `manifest.json`）。打好的证据包在 `ohd-evidence/cases/`。换位置用 `--session-root`。

## 当前能力

| 分组 | 能力 |
|---|---|
| 环境 | `doctor`（hdc/设备/uitest/faultlog/llvm/磁盘）、`selftest`（离线自检）、`env-dump`（设备指纹） |
| 观测 | `tree`（节点树+中心点）、`shot`（截图+网格/十字/节点框）、`video`（录屏：Android/iOS 模拟器 mp4、OHOS 截帧合成 gif）、`mem`（Pss/GL/Graph 分类）、`cpu`（占用/进程/逐核频率）、`log`（start/stop/snap/count/save）、`watch`（CSV+斜率/阶梯/平台期）、`dmabuf`（DMA 快照与增量） |
| 动作 | `tap`（点击/双击/长按）、`input`（文本/滑动/拖拽/按键）、`replay`（回放 .ohd 脚本）、`record`（录到 `ohd-evidence/record/record-<设备>-<时间>.ohd`，回放前冷启动入口） |
| 对比与崩溃 | `diff`（截图差异+热力图）、`crash`（故障列表/解析）、`sym`（llvm-addr2line+buildId 校验） |
| 验收益 | `install`（覆盖安装）、`ab`（AB 对照） |
| 扩展 | `ext`（列出/管理脚本扩展；`ohd <命令>` 直接命中 `.ohd/extensions/<命令>.{sh,py,java,bat}`，见下节） |
| 交付 | `bundle`（ohd-case/1；默认近 2h；`--report`/`--zip`/`--latest-only`；缺件 exit 4）、`analyze`、`triage`（automation REPORT→调查赛道；`--reverify` 调 dmabuf/mem）、`path`（hilog→.ohd）、`report`、`repro`、`soak`、`monkey`、`monitor`、`salvage`、`summary` |

解析器全部基于真机取样（fixture 在 `ohd-core/src/test/resources/fixtures/`），不是按文档推测的格式。

### 平台覆盖

| 能力 | OHOS | Android | iOS 模拟器 | iPhone 真机 |
|---|---|---|---|---|
| 设备枚举 / `doctor` | ✓ | ✓ | ✓ | ✓ |
| 安装 / 卸载 | ✓ | ✓ | ✓ | ✓（需已签名包 + 开发者模式） |
| 启动 / 终止 / 文件拷贝 | ✓ | ✓ | — | ✓ |
| 截图 | ✓ | ✓ | ✓ | ✗ |
| 日志 | ✓ | ✓ | ✓ | ✗ |
| 内存 | ✓ | ✓ | ✓（footprint） | ✗ |
| CPU | ✓ | ✓ | ✗ | ✗ |
| 崩溃 | ✓ | ✓ | ✓ | ✗（目录无样本） |
| **触摸注入 / monkey / replay** | ✓ | ✓ | **✓（idb）** | 代码已通，真机未验证 |
| UI 树 | ✓ | ✓ | 数据源已就绪（idb `describe-all`），`tree` 未放开 | 同左 |

拿不到的维度直接跳过或明确拒绝，不做占位编造。iOS 内部差异见 `DESIGN.md` §94–§97。

## 常用命令

### 装包与压测

```bash
ohd install /path/app-signed.hap                       # 覆盖安装；--keep-data 保留应用数据
ohd monkey --bundle com.tencent.compose --duration 10h --keep-going \
  --interval 0 --batch 10 --sample-every 20 --layout-ttl 20 \
  --tag Konan_main --exclude '(?i)vsync'

# OHOS 默认已 keep-a11y（uitest daemon + dump ping，对齐 DevEco）
ohd monkey --bundle com.tencent.compose --duration 2h --keep-going \
  --a11y-ping-every 10 --tag ComposeA11y
# 对照排噪才关：--no-keep-a11y
```

| 参数 | 说明 |
|---|---|
| （默认） | **全程抓 hilog**，落 `00x-hilog.txt` 并登记进 manifest；`--no-hilog` 关（只在崩溃时留 tail 2000） |
| `--tag Konan_main` | 设备端 `hilog -T` 过滤，`a,b` 支持多 tag；不指定抓全部 |
| `--match RE` / `--exclude RE` | 宿主端正则：先白名单后黑名单；`--exclude '(?i)vsync'` 即丢弃全部含 vsync 的行 |
| `--full` | 保留完整字段；不加时代码默认 **compat**（只留 `日期 时间 消息`） |
| `--max-size` | 日志上限，默认 `0` 不限；超限由 LogWatchdog 截断回收 |
| `--keep-going` | 崩溃后继续跑满时长（不加以首次崩溃结束，10h 长跑必加） |
| （默认）keep-a11y | OHOS **默认开**：保活 uitest + accessibility dump；`--no-keep-a11y` 关掉 |
| `--layout-ttl 20` | 布局抓取间隔上限，产物减半（约 250 MB / 10h） |

注意：10 h 必须脱管启动（`Popen(..., start_new_session=True)`），否则终端/IDE 退出会带走压测。
并行内存采样可用扩展：`ohd --ext-timeout 0 --ext-mem 0 ohos_automation_scene_capture --bundle … --out-dir …`。

### 日志

```bash
ohd log                                      # 录制：终端实时看 + 同时压缩落盘，Ctrl-C 结束
ohd log --tag Konan_main --exclude '(?i)vsync'   # 录制时只留关心的行
ohd log ls                                   # 看录过哪些（id / 时间 / 大小）
ohd log --open latest --tail 200              # 回放最近一次录制
ohd log --file 003-hilog.log.gz --match 'LEAK|timeout'   # 回放并正则过滤
ohd log count --file 003-hilog.log.gz --match 'FATAL'    # 统计关键行
ohd log start --max-size 4G                   # 后台录制（命令立刻返回，配 stop 收尾）
ohd log stop
```

默认就是「边看边存」：不需要 start/stop，Ctrl-C 结束时会打印文件路径、大小、行数与回放命令。
`start/stop` 只在需要「命令立刻返回、流继续跑」的后台长跑场景才用。

`--tag/--match/--exclude/--full/--max-size` 与 `monkey` 同语义（共用 `HilogStream`）。
tag 名取 hilog 的 `domain/tag` 段，如 `Konan_main`；`ArkUIViewController` 只是消息内容，不是 tag。

**落盘默认压缩**：日志写成 `<会话>/00N-hilog.log.gz`，直接 `gunzip -c` / `zcat` 就能读，
不需要先解密或找工具。实现是「多成员 gzip」——每 2s 或 8MB 闭合一个 gzip 成员再追加下一个，
所以长跑中被 Ctrl-C、OOM、崩溃杀掉时，**已写完的成员仍可完整解压**（只有最后 2s 可能丢）。
想要明文就加 `--log-compress none`（得到 `.txt`）。

`--max-size` 在压缩模式下按**压缩后文件大小**算（日志文本通常压到 1/6~1/10，等于放宽了上限）。

### 内存与卡顿

```bash
ohd mem --bundle com.example.app          # Pss / GL / Graph 分类明细
ohd watch --bundle com.example.app --duration 60s      # CSV + 斜率/阶梯/平台期
ohd dmabuf --bundle com.example.app       # DMA 快照与增量
ohd cpu --cpufreq                         # 逐核频率（看降频/温控）
```

`monkey` 采样列含 `gl_kb / graph_kb / swap_pss_kb`；判泄漏看**可回落下限**是否逐轮抬高，别只看峰值。
驻留 = `pss - swap_pss`。

### 观察与操作

```bash
ohd tree --grep 设置 --clickable      # 找可点节点，命中唯一时直接给出点击坐标
ohd tree --grep 登录 --json           # 给 agent 消费
ohd go --grep 多实例测试 --expect 返回 --bundle com.tencent.compose  # 写入世界地图边
ohd go --grep 1500 Text --expect 'Compose1500Text Item' --light     # 轻量验标（≤2 dump）
ohd back --expect '1500 Text' --light                                # Back + 验标 + 弹栈/地图
ohd go --grep GPU Texture --scroll-find --scene home  # 学 item 滑次；下次批量滑
ohd map show / path --to "GPU Texture" / export       # 看图·问路·存档
ohd map go --to "GPU Texture"                         # 按 Back+滑+点 执行
ohd wait --grep 返回 --timeout 2s                     # 当前页缓存优先
ohd monkey --bundle …                                 # 默认读+写世界地图导航（--no-worldmap 关）
ohd shot --grid 100                   # 截图叠网格，目视读坐标
ohd shot --cross 640,1200             # 确认某个点落在哪个控件上
ohd shot --boxes                      # 节点框 + 中心点
ohd video --duration 15s --fps 15 --out /tmp/demo.gif   # 三端同一条命令，格式看 --out 后缀（mp4/gif）
ohd video --duration 20s --fps 15 --out /tmp/demo.webp  # 动画 WebP：同内容比 gif 小 ~90 倍（需 libwebp）
ohd video --duration 30s               # 不给 --out 用平台原生格式：Android/iOS=mp4、OHOS=gif
ohd tap 640 1200                      # 裸坐标；有文本地标请用 go
ohd record                            # 录成 .ohd
ohd replay <file.ohd>
```

### 问题回溯

```bash
ohd path <hilog.txt> --package com.example.app --out replay.ohd   # hilog 页面事件 → 路径骨架
ohd crash                             # 故障列表
ohd crash --latest --details          # 最新一条原文+解析
ohd summary                           # 历次会话一张表（哪轮崩、内存怎么走、证据齐不齐）
ohd bundle --redact --report --zip    # 近 2h 合并 + 缺陷单 + zip（缺件 exit 4）
ohd analyze                           # 离线复核证据包缺件/完备性（不碰设备）
ohd triage <automation-captures/YYYYMMDD-…>              # REPORT → 调查赛道
ohd triage CAPTURE --reverify --bundle com.tencent.compose   # 按赛道短跑复验水位
ohd ab --a before.hap --b after.hap --bundle com.tencent.compose --soak 2m
ohd diff --baseline a.png --current b.png
ohd repro                             # 按证据包步骤重放，看是否再崩
```

`path` 只吃 hilog；缺坐标的行会留 `# TODO`，用 `--coords <file>` 补（`from->to = tap x y`）。

### 崩溃符号化（给研发可读栈）

用**与真机运行 Build-ID 一致**的本地 so / hap 把 faultlog 解析成函数名+文件行。Build-ID 不匹配默认**拒绝出符号**（exit 7），避免错版本误导。

```bash
# 推荐：指向与安装包同批的 debug so（KMP 常见 debugShared / libs/arm64-v8a）
ohd sym --crash /path/faultlog.txt \
  --so-dir /path/composeApp/build/bin/ohosArm64/debugShared:/path/libs/arm64-v8a \
  --limit 40

# 设备上最新一条
ohd sym --latest --so-dir /path/to/debugShared

# 从 hap 抽 so（省手撕包；若与运行中 libkn Build-ID 不一致会拒符号）
ohd sym --crash faultlog.txt --hap /path/app-signed.hap

# 只认 --so-dir、不用自动搜到的 so
ohd sym --crash faultlog.txt --so-dir /path/debugShared --no-auto-so

# 救急：Build-ID 不匹配仍出符号（结果可能错位）
ohd sym --crash faultlog.txt --so-dir /path/libs --allow-mismatch
```

需要 llvm-addr2line：`ohd doctor` 会查；也可用 `--llvm` / `ohd config set OHD_LLVM_ADDR2LINE`。

### 长稳值守

```bash
ohd soak --bundle com.example.app --duration 10h --interval 60s   # 纯观测（趋势导向）
ohd soak --bundle com.example.app --duration 10h --drive <脚本.ohd>

# 动作由别的工具给、自己只记录（另一个窗口跑 tap/replay/uitest，这里盯日志+内存+崩溃）
ohd monitor --bundle com.example.app --duration 30m --interval 5s
```

## 跨平台：Android

同一套命令，平台自动探测；也可以显式指定：

```bash
ohd doctor                      # 同时报告 hdc 与 adb
ohd log start                   # 只有一台设备时直接跑，不论 OHOS/Android
ohd --platform android log start
ohd --device <serial> mem --bundle com.example.app
```

底层只分三层差异：通道（`hdc`/`adb`）、命令（`PlatformCmds`）、输出解析（`AndroidParser`），
能力层代码不分叉。当前 Android 已支持：`log` / `monitor` / `mem` / `tap` / `swipe` / `key` / `doctor`；
`tree` / `shot` / `install` / `crash` / `record` 待迁移（见 DESIGN §87）。

## 多设备并行

```bash
ohd --all monkey --bundle com.tencent.compose --duration 20s
ohd --all replay path/to/script.ohd
```

每台设备一个独立子进程，会话目录 / 日志状态 / 输出各自隔离，某台失败不影响其它台。
多台在线时不加 `--all` 仍会要求 `--device` 指定——不自动猜（唯一例外：用 `ohd config devices` 固定过默认集合）。

## 配置：把工具路径存下来

```bash
ohd config set JAVA_HOME /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home
ohd config set OHD_HDC   ~/Library/OpenHarmony/Sdk/20/toolchains/hdc
ohd config list           # 生效值 + 来源（config / env）
ohd config keys           # 有哪些常用键
ohd config get JAVA_HOME
ohd config unset JAVA_HOME
ohd config env            # 输出 export 行，可 eval 进当前 shell
```

### 默认设备：多台挂着，只跑其中几台

```bash
ohd devices                               # 清单：设备 / 平台 / 连接 / 默认
ohd devices use 63R0226113004249          # 设默认，之后可省略 --device
ohd config devices                        # 列在线设备，✔ 标出当前默认（等价配置视角）
ohd config devices FMR0225A,2B0913F       # 固定默认：之后不必每条命令加 --device
ohd config unset OHD_DEVICES              # 回到「多台必须显式指定」
```

- 配 1 台：等价 `--device <key>`，走单设备路径。
- 配多台：等价 `--all` 的子集——按配置顺序并行跑这几台；不在线的会打印「已跳过」，不会静默少跑。
- 优先级：`--device` > `--all` > `OHD_DEVICES` > `OHD_DEVICE`。
- 键名就是 `OHD_DEVICES`（逗号分隔），也可以直接 `ohd config set OHD_DEVICES a,b`。

存在 `~/.ohd/config.json`（`OHD_CONFIG` 可改路径）。覆盖顺序：

**CLI 显式参数（`--hdc` / `--llvm`）> 配置文件 > 进程环境变量 > 平台自动探测**

配置文件里就是一串「环境变量名 → 值」，所以：

- `JAVA_HOME` 一设，扩展里的 `.java` 走这个 JDK，而不是 ohd 自己那个 JVM；
- `OHD_HDC` / `OHD_LLVM_ADDR2LINE` 等价于每次带 `--hdc` / `--llvm`；
- `OHD_PYTHON` 决定 `.py` 扩展的解释器；
- `OHD_EXT` 指扩展目录，团队可指向同一个 git 目录。

## 扩展：用脚本挂新命令

内置能力之外，`ohd <命令>` 会到扩展目录找同名脚本。脚本能用现有的 `ohd` / `hdc` / `uitest` 拼出新命令，不必改 Kotlin、不必重编译。

```bash
mkdir -p .ohd/extensions
cp examples/extensions/uitest.sh .ohd/extensions/
ohd uitest --count 20      # 立刻可用
ohd ext list               # 看当前有哪些扩展
ohd ext docs uitest        # 看脚本实现
ohd ext which uitest       # 看解析到哪个文件
```

搜索目录（优先级从高到低，同名取先命中）：

| 目录 | 用途 |
|---|---|
| `$OHD_EXT` | 显式指定（团队共享目录） |
| `<cwd>/.ohd/extensions` | 跟仓库走，可提交 |
| `~/.ohd/extensions` | 个人常用 |
| `<ohd 安装目录>/extensions` | 跟分发包走 |

支持的写法：

| 后缀 | 怎么跑 | 适合 |
|---|---|---|
| `.sh` | `/bin/sh <file>` | 串 hdc / uitest / ohd 流程 |
| `.py` | `python3 <file>`（`OHD_PYTHON` 可覆盖） | 解析 JSON、算指标 |
| `.java` | `java <file>.java`（JDK 11+ 直跑源码） | 复杂逻辑、复用 JDK 库 |
| `.bat` / `.cmd` | `cmd /c <file>`（仅 Windows） | Windows 同事 |
| 无后缀 | 直接执行（需 `chmod +x`） | 任意语言 |

脚本里可用的注入变量：

| 变量 | 含义 |
|---|---|
| `OHD_SELF` | ohd 启动入口，脚本里可直接 `"$OHD_SELF" tree` |
| `OHD_HDC` / `OHD_LLVM` | 解析后的 hdc / llvm 路径 |
| `OHD_DEVICE` | `--device` 指定的设备 |
| `OHD_SESSION_ROOT` | 产物根目录 |
| `OHD_JSON` | 调用方传了 `--json` 时为 `1` |
| `OHD_EXT` | 当前生效的扩展目录列表 |

约定：参数**原样透传**（ohd 不解析脚本的选项）；stdout/stderr 直通，退出码原样返回；与内置能力重名时**内置优先**；扩展自身出错不影响 ohd 启动。

## 目录

```
ohd/
├── ohd-core/   # Hdc / Device / Session / Output / Caps / Parsers（纯逻辑，可离线单测）
├── ohd-image/  # 图像对比与标注（M0/M1: ImageIO；后续可加 Skiko 引擎）
├── ohd-cli/    # 分发与参数解析（application 插件产出跨平台启动脚本）
├── examples/   # 示例扩展（extensions/uitest.sh、memcheck.py、hello.java）
└── dist/       # ohd / ohd.bat / build.sh / build.bat
```

## 退出码

| 码 | 语义 |
|---|---|
| 0 | 成功 |
| 2 | 用法错误 |
| 3 | 无设备 / 设备未授权 |
| 4 | 断言失败 |
| 5 | 设备能力缺失 |
| 6 | 超时 / 流僵死 |
| 7 | Build-ID 或产物不匹配（拒绝给结论） |
