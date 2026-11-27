# `ohd` — OHOS 真机能力工具箱 技术设计（v0.1 草案，待评审）

- 日期：2026-10-17
- 仓库/工作区：`/Users/xuxingwang/KMP`（分支：见 `git -C /Users/xuxingwang/KMP rev-parse --abbrev-ref HEAD`，本文档为新增设计稿，不含已有分析正文）
- 目标落点：`/Users/xuxingwang/KMP/.agents/scripts/dev/`（源码 + 文档），PATH 入口 `~/.local/bin/ohd`
- 状态：**设计稿，未写代码、未编译**（遵循 no-proactive-build）

> **2026-10-19 迁移**：本文档已从 `.agents/scripts/dev/DESIGN.md` 移到 `ohd/DESIGN.md`。
> 下文历史正文里出现的 `.agents/scripts/dev/DESIGN.md` 均为**历史路径**，只作追溯用，现态以本文件为准。

---

## 0. 一句话

把「操作真机 / 取证 / 性能观测」的散装脚本，收敛为**一个原生单二进制 CLI `ohd`**：
每条子命令一行调用，默认给人看的紧凑输出，`--json` 给 agent 结构化契约，产物统一落盘并带 manifest。

---

## 1. 现状盘点（为什么要做）

### 1.1 已有能力（分散、多语言）

| 现有资产 | 语言 | 能力 | 问题 |
|---|---|---|---|
| `~/.agents/scripts/log/hlog.sh` (+`hlog_trim.pl`) | bash+perl | hilog 跟随/过滤/落盘/拉 gz | 无结构化输出；不能与其它步骤组合 |
| `~/.agents/skills/ohos-dmabuf-inspection/dmabuf_snapshot.sh` | bash | `/proc/<pid>/mm_dmabuf_info` 分组 + 两次快照 `-d` | 解析逻辑藏在 awk 里，难以复用与测试 |
| `.agents/scripts/verify/check_reclaim_lifecycle.py` | py 16KB | `layout`/`drive`/`analyze` 回收生命周期验收 | 单场景硬编码；UI 驱动能力无法被别的任务复用 |
| `.agents/scripts/verify/compare_same_position_frames.py` | py+numpy+PIL | 同位置帧对齐 + tile 异常图 | 依赖 numpy/PIL（环境脆）；只做一种对比 |
| `.agents/scripts/verify/check_screenshot_text_presence.py` | py+numpy+PIL | 截图高频能量 HF 判「文字是否还在」 | 同上 |
| `native-crash-symbolization` skill 内嵌单行 python | py | addr2line 批量符号化 | 无 Build-ID 前置校验、无缓存、glob 全盘搜索慢 |
| `ohos-crash-log-export` skill | 文档 | hidumper -s 1201 取证链 | 全靠手拼引号，易错（skill 自己都列为最大坑） |

### 1.2 事实基线（本次实测，2026-10-17）

| 项 | 值 |
|---|---|
| hdc | `/Users/xuxingwang/Library/OpenHarmony/Sdk/20/toolchains/hdc`，`Ver: 3.2.0b` |
| 设备 | `63R0226113004249`，`SGT-AL50`，API `24`，`SGT-AL50 6.1.0.135(SP8C00E120R3P11)` |
| 设备侧 uitest | `screenCap` / `dumpLayout`(+`-p -i -a -b -w -m -d -e`) / `uiRecord record\|read` / `uiInput click,doubleClick,longClick,swipe,drag,fling,dircFling,keyEvent,inputText,text` |
| 设备侧 snapshot | `snapshot_display [-i displayId] [-f file] [-w w] [-h h] [-t type] [-m]` |
| hidumper 可观测域 | `--mem[pid][--prune][--show-ashmem][--show-dmabuf]`、`--mem-smaps pid [-v]`、`--mem-jsheap pid [--gc][--leakobj][--raw][--clean]`、`--cpuusage [pid]`、`--cpufreq`、`-p [pid]`、`--storage [pid]`、`--net [pid]`、`--ipc`、`-e [--list\|--print]`（faultlog）、`--zip` |
| faultlog 通道 | `hidumper -s 1201 -a '-p Faultlogger …'`（Hiview 插件代理，免 root） |
| `--mem` 列 | `Pss(xxx in SwapPss) / Vss / Rss / Uss / GL / Graph / Dma / PurgSum / PurgPin`（**Graph 与 DMA 分列可用**） |
| 本机工具链 | Swift 6.2.3（Xcode 26A434）、clang 19（OHOS）、python3 3.9.6、node 18；**无 rustc / go** |
| 产物目录惯例 | `.dsh/{logs,shots,crash,tools,tmp}` 已存在 ⇒ 新增 `.dsh/device/` |

### 1.3 核心痛点

1. **不可组合**：`截图 → 找坐标 → 点击 → 抓日志 → 判读` 需要人肉粘 4 种工具、3 种语言。
2. **无契约**：输出全是自由文本，agent 每次得重写正则解析；解析器散落在各脚本里、无单测。
3. **无会话**：一次排查的证据散在 `/tmp`、`.dsh/logs`、剪贴板里，事后无法回溯「这结论是哪一轮的产物」。
4. **重复踩坑**：长驻 hilog 僵死、dumpLayout 拿不到 Compose 文本、`hdc file recv` 覆盖 mtime、faultlog 引号错——这些已在 skill 里写成「防御」，但工具没把它们**固化成默认行为**。

---

## 2. 目标 / 非目标

**目标**
- G1 一条命令拿到结构化结果（`--json`），人读默认表格，agent 读 JSON。
- G2 覆盖用户列出的 12 项能力，并补齐闭环所缺的编排能力（录制/回放/自动取证/时序编排）。
- G3 把 skill 里的「防御」变成工具默认行为（每轮独立日志流、端侧先落盘再整目录 recv、Build-ID 前置校验、按内容匹配解析）。
- G4 离线可回归：解析器有 fixture 单测，不依赖设备即可跑。
- G5 零第三方依赖、原生二进制、启动 < 20ms、无 py/numpy/PIL 环境风险。
- G6 产物自动归档到 `.dsh/device/<ts>-<cap>/` + `manifest.json`（含设备/版本/命令/输入/退出码）。

**非目标**
- 不做 GUI / 不做 Appium 式跨平台框架 / 不替代 DevEco 调试器与 Profiler。
- 不触发项目构建（禁止 `gradlew` / `cmp.sh` / `ohos.sh`），仅消费已装 HAP。
- 不做图像识别/OCR（离线无依赖约束下，用 HF + tile diff 这类数值判据替代；需要真正 OCR 时另接外部工具）。

---

## 3. 语言与工程选型

| 候选 | 本机状态 | 结论 |
|---|---|---|
| **Swift 6.2.3 + SwiftPM** | 已装（Xcode 26A434），**实测可编译出原生二进制**（`swiftc -O`，产物 52KB，`ProcessInfo` 正常） | ✅ **采用** |
| Rust | 未安装，需 rustup + 网络 + 首次编译依赖树 | 仅当团队更熟 Rust 时备选 |
| Go | 未安装 | 同上 |
| C/clang | 有 OHOS clang 19 | 图像/JSON 开发成本过高，否决 |
| Node/TS | 有 | 需运行时、启动慢，不符「高效语言」 |
| Python | 有 3.9.6 | 用户明确排除；现有 py 脚本逐步退场 |

**Swift 选型理由**：本机零安装成本（已实测）；`Foundation`（Process/JSON/FileManager/Date）够用；图像对比走 `ImageIO` + `CoreGraphics` + `Accelerate/vImage`（向量化灰度、直方图、差分），比 numpy 方案更可控且无双版本依赖；`swift test` 提供 fixture 单测；单文件二进制便于 `~/.local/bin/ohd` 直连。

**沙箱约束（实测）**：`swiftc` 默认把 module cache 写到 `/var/folders/...`，在 workspace-write 沙箱下被拒（`Operation not permitted`）。必须在包内固定：
`swift build -Xcc -fmodules-cache-path=.build/mc`（或 `-module-cache-path .build/mc`），实测缓存 ~29MB，需进 `.gitignore`。若后续需在 workspace 外写 PATH 软链，需一次沙箱授权。

**包结构（草案）**
```
.agents/scripts/dev/
├── DESIGN.md                 # 本文档
├── Package.swift             # executable target: ohd；library target: OhdKit（便于单测）
├── Sources/OhdKit/
│   ├── Hdc.swift             # 进程封装：参数数组直传、超时、流式、重试、退出码
│   ├── Device.swift          # 设备发现/选择（connectKey、多设备消歧）
│   ├── Session.swift         # .dsh/device/<ts>-<cap>/ + manifest.json
│   ├── Output.swift          # table / json / markdown / 颜色（非 tty 自动关色）
│   ├── Caps/                 # 每个能力一个文件，统一 Capability 协议
│   └── Parsers/              # dumpLayout / hidumper --mem / dmabuf / hilog / faultlog 解析器
├── Sources/ohd/main.swift    # 仅做 CLI 解析与分发
├── Tests/OhdKitTests/
│   └── Fixtures/             # 真实样例（脱敏）：layout.json、mem.txt、dmabuf.txt、hilog.txt、cppcrash.log
└── bin/ohd                   # 构建产物 copy（或 symlink 到 ~/.local/bin/ohd）
```

---

## 4. 架构

```
ohd <cap> [args] [--json] [--device <key>] [--session <dir>] [--dry-run] [--yes]
        │
        ├── Cli        解析/帮助/退出码
        ├── Device     hdc 定位、设备选择、能力探测（doctor 缓存）
        ├── Hdc        shell / file send|recv / 流式 tail / 超时 / 重试
        ├── Session    产物目录 + manifest + 日志复用（用于报告与 A/B）
        └── Caps       每个能力实现同一个 protocol Capability
                        run(ctx) throws -> Artifact
```

**统一约定（所有子命令共享）**

- 输入：一律走参数数组，**禁字符串拼 shell**（hdc `-a '-p Faultlogger'` 那类引号坑由封装层负责）。
- 输出：默认「人读表格/摘要」；`--json` 输出单层 JSON 对象（含 `ok`、`data`、`artifacts[]`、`diagnostics[]`、`elapsedMs`）。
- 落盘：默认归档；`--no-archive` 关闭。产品文件命名 `NNN-<kind>.<ext>`（序列号保证 recv 后 mtime 不用于排序）。
- 超时：所有设备调用带超时（默认 20s，流式 30s 心跳），超时按可重试错误处理（hdc 偶发掉线重试 1 次）。
- 并发：全局信号量限制同时在飞的 `hdc shell` ≤ 3（设备侧 shell 并发高会互相拖慢）。
- 破坏性动作（点击、滑动、输入、`app clear/uninstall`、设备侧 `rm`）默认要求 `--yes`；`--dry-run` 打印将要执行的 hdc 命令。
- 版本记录：每个 manifest 记录 `hdc -v`、设备 model/API/软件版本、目标 bundle、HAP 路径与 Build-ID（可对账「结论属于哪份产物」）。

---

## 5. 能力清单

### 5.1 用户点名的 12 项

| # | 命令 | 能力 | 实现要点 |
|---|---|---|---|
| 1 | `ohd tree` | 读节点树 | `uitest dumpLayout -p /data/local/tmp/ohd/layout.json` → `file recv` → 解析 JSON：窗口合并开关、`--grep <text>`、`--type`、`--id`、`--rich`(字体属性 `-a`)、输出 `bounds/center/clickable`；`--tap <text>` 一步点到节点中心。**Compose 文本可见性差异**：探测不到时明确报「本页无文本节点，请用坐标」，不静默返回空 |
| 2 | `ohd tap` / `ohd input` | 操作 UI | `tap` 支持 `--text/--id/--x/--y`、`--double/--long/--times N/--interval`；`input swipe|drag|fling|dircfling|key|text`；`key` 映射 `Back/Home/Power`；坐标越界先校验屏幕尺寸（来自 `snapshot_display -w/-h` 或 deviceinfo） |
| 3 | `ohd log` | 抓日志 | 子模式：`start`（清缓冲+独立流→文件，写 pid/pidfile）/`stop`（校验行数>0，否则报「流僵死」）/`snap`（非阻塞取尾部 N 行）/`save`/`cp`/`gz`/`count --match <regex>`；**默认禁止长驻复用**（skill 踩过的僵死坑固化为 start/stop 成对 + 心跳校验）；标签/级别/正则/pid 过滤，输出 `--json` 时给 marker 计数直方图 |
| 4 | `ohd crash` | 抓崩溃 | `list [--bundle][--since][--until][-n]` / `get <file|--latest> [--type cppcrash|appcrash|jscrash|appfreeze|sysfreeze]` → `hidumper -s 1201 -a '-p Faultlogger …'`，落盘 + 解析关键字段（Reason/信号/Fault thread/Tid/Page switch history/Process Memory）；同时给「是否真崩」判定（`FaultLogManager/HandleAppDied/CPP_CRASH` 三选一） |
| 5 | `ohd sym` | 分析堆栈 | 输入 crash.log：Build-ID 校验（`llvm-readelf -n`）→ 不匹配直接拒绝并提示重建；`llvm-addr2line -C -f` 批量符号化（建索引缓存 `.so`→符号表，避免每次全盘 glob）；`--disasm <pc>` 出 `llvm-objdump` ±16 条指令窗口；输出 `#NN pc → 函数(文件:行)` 表 + `--json` |
| 6 | `ohd shot` | 截图确认坐标 | `snapshot_display -f` → recv；`--grid 100` 叠加坐标网格、`--cross x,y` 十字标、`--scale`；输出可点开看的 PNG + 节点框叠加（`--boxes`，把 tree 的 bounds 画上去，一眼看出点击落点对不对） |
| 7 | `ohd replay` | 回放操作 | steps 文件（JSON/YAML）：`clickText/clickId/clickBounds/swipe/key/wait/assertText/assertLog/assertPss/shot/log`；执行中失败即停 + 自动截图 + 落盘现场；`--dry-run` 打印计划；与 `record` 输出同格式（可人工微调） |
| 8 | `ohd diff` | UI 截图对比 | 三条判据合一：① `--align N` 二维平移搜索（积分图加速）后 `mean|Δ|/max|Δ|`；② `--tile 64` 异常 tile ASCII 图 + 异常区域像素 bbox；③ `--text-presence` 高频能量 HF（判断「文字是否整体没画」），同页对照用 `--baseline` 比值。灰度/差分走 vImage |
| 9 | `ohd mem` | 看 pss | `hidumper --mem <pid> [--show-dmabuf] [--show-ashmem]`，解析 `Pss(SwapPss)/Vss/Rss/Uss/GL/Graph/Dma/PurgSum/PurgPin`；`--smaps` 走 `--mem-smaps -v` 出分类明细 top N；`--jsheap` 支持 `--gc`/`--leakobj`/`--raw` |
| 10 | `ohd cpu` | 看 cpu | `--cpuusage [pid]` 解析 总/用户/内核/iowait/irq/idle + 进程 Top N；`--cpufreq` 逐核当前/最大频率（识别降频/温控）；`--load` 取 load average（1/5/15） |
| 11 | `ohd watch` | 持续跟踪 pss | 多指标定时采样（`--metrics pss,graph,dma,rss,cpu,fd,thread`、`--interval 1s`、`--duration 60s`/`--count`/`--until-stable 3x`）→ CSV + 汇总：首末差、线性回归斜率、**阶梯检测**（相邻差分超 ε）、平台期判定；`--json` 给时间序列；Ctrl-C 优雅收尾并保留已采数据 |
| 12 | `ohd dmabuf` | 对比 dma buffer | `snap [--bundle]`：`/proc/<pid>/mm_dmabuf_info` 按 `buf_type/buf_name` **按内容匹配**分组（不按列号），并与 `hidumper --mem` 的 `Graph/Dma` 对账；`diff a.txt b.txt` 只列增量标签；`--watch` 与 watch 同源采样；输出「xcomponent id → 是否仍存活（对照 hilog `destroyRender … remaining=`）」关联结论 |

### 5.2 建议补齐的能力（闭环所缺、我认为价值最高）

| # | 命令 | 能力 | 为什么值得 |
|---|---|---|---|
| 13 | `ohd record` | 录制真机操作 → steps.json | `uitest uiRecord record -l` 记录点击/滑动 + 每步 layout，转成 `replay` 可直接吃的步骤文件，实现「手点一次 → 自动重放 N 次」 |
| 14 | `ohd trace` | 时序编排 + 报告（一站式） | 一条命令串起：清缓冲 → 起日志流 → replay → 停流 → 能力自检（被杀?）→ 崩溃取证 → `probe` 计数 → 输出 markdown 报告 + JSON。把「验收一轮」从 6 步手工降到 1 步 |
| 15 | `ohd probe` | marker 计数与 A/B 对拍 | 对日志窗口做 marker 直方图、实例 id 时间线（只增不减=泄漏信号）、两轮 A/B 差值表；把 `check_reclaim_lifecycle.py analyze` 的启发式判读升级为「缺 marker ⇒ 明确作废」的硬门禁 |
| 16 | `ohd fault-watch` | 崩溃自动取证守护 | 后台跟 hilog，命中 `FaultLogManager/HandleAppDied/SIGSEGV` 立即导出最新 faultlog + `sym` 符号化 + 落盘现场报告；解决「崩了但没抓到」 |
| 17 | `ohd doctor` | 环境自检 | hdc 版本/路径、设备列表、SDK、uitest 各子命令可用性、snapshot 可用性、faultlog 通道可用性、磁盘余量；一条命令回答「这台机器现在能不能真机验证」 |
| 18 | `ohd app` | 应用管理 | `pid`/`bundle dump`/`start`/`stop`/`force-stop`/`clear`/`install <hap>`/`uninstall`；`pid` 兼容「该 shell 取不到进程」的已知假象（用 `-p` 与 hilog 交叉判定） |
| 19 | `ohd fs` | 文件传输封装 | `push/pull/ls/rm`；`pull --dir` 走「设备端先拍完 → 整目录 recv」（避免逐帧 recv 5–8s/帧、且 mtime 会被覆盖）；设备侧临时目录固定 `/data/local/tmp/ohd/` |
| 20 | `ohd env-dump` | 设备环境快照 | 一次性收集 model/API/软件版本/ABI/分辨率/刷新率/内存总量/温控/横竖屏/当前前台应用 → manifest 复用；A/B 报告的对账依据 |
| 21 | `ohd net` / `ohd storage` | 其他 hidumper 域 | `--net [pid]`、`--storage [pid]`（`/proc/pid/io`）；排查「卡是不是 IO/网络引起」时省一次手拼 |
| 22 | `ohd skill` | 判据查询（可选） | `ohd skill markers` 打印当前 marker 语义表（从 skill 文档解析），让 CLI 与 skill 知识单一真源；非必须，列为可选 |

---

## 6. 数据契约

### 6.1 目录与 manifest

```
.dsh/device/20261017-073000-trace-r03/
├── manifest.json
├── 000-layout.json            # tree
├── 001-screen.png             # shot
├── 002-hilog.txt              # log
├── 003-steps.json             # replay 使用的步骤（回放可复现）
├── 004-cppcrash.log           # crash 原文
├── 005-symbols.json           # sym 结果
└── report.md                  # trace 生成的人读报告
```

`manifest.json`（草案）
```json
{
  "schema": "ohd/1",
  "startedAt": "2026-10-17T07:30:00+08:00",
  "capability": "trace",
  "hdc": {"path": "...", "version": "3.2.0b"},
  "device": {"connectKey": "63R0226113004249", "model": "SGT-AL50", "api": 24,
             "software": "SGT-AL50 6.1.0.135(SP8C00E120R3P11)"},
  "target": {"bundle": "com.tencent.compose", "pid": 12345,
             "hap": "/path/app.hap", "buildIds": {"libkn.so": "0a1b…"}},
  "commands": [{"argv": ["hdc","shell","uitest","uiInput","click","640","1200"], "exit": 0, "ms": 210}],
  "artifacts": [{"path": "000-layout.json", "kind": "layout", "bytes": 20480}],
  "env": {"python": "3.9.6", "swift": "6.2.3", "cwd": "/Users/xuxingwang/KMP"}
}
```

### 6.2 JSON 输出骨架（所有子命令一致）

```json
{"schema":"ohd/1","ok":true,"capability":"mem",
 "data":{ "...能力自己的字段..." },
 "artifacts":[{"path":".dsh/device/…/000-mem.json","kind":"mem"}],
 "diagnostics":[{"level":"warn","code":"NO_MARKER","message":"日志窗口内无 MEMPROBE 行，本轮作废"}],
 "elapsedMs":842}
```

### 6.3 退出码

| 码 | 语义 |
|---|---|
| 0 | 成功 |
| 2 | 用法错误 |
| 3 | 无设备 / 设备未授权 |
| 4 | 断言失败（replay/probe/watch 的判据不满足） |
| 5 | 设备能力缺失（如 uitest 不可用） |
| 6 | 超时 / 流僵死 |
| 7 | Build-ID 或产物不匹配（拒绝给结论） |

---

## 7. 关键实现要点（难点与对策）

| 难点 | 对策 |
|---|---|
| `dumpLayout` 与 `snapshot_display` 都写设备侧文件，需 recv | `fs` 层统一「设备端先落盘 → 整目录 recv」；命名带序号，**不靠 mtime 排序** |
| dumpLayout 只输出到设备文件（无 stdout 模式） | 固定 `/data/local/tmp/ohd/`，recv 后立即解析；失败时回读设备侧原文行数用于诊断 |
| Compose 二级页可能一个文本节点都没有 | `tree --grep` 为空时给出明确 code（`NO_TEXT_NODE`）+ 提示走坐标；`shot --boxes` 叠框辅助人给坐标，坐标可写回 steps.json |
| 长驻 `hdc shell hilog` 静默僵死 | `log start/stop` 强制成对；stop 校验「行数 > 0 且文件在增长」；超出心跳窗口报 `STREAM_STALLED` |
| hilog `-x` 持久缓冲不含应用日志 | 工具默认用实时流；`--persist` 时显式 warn「app 行可能为 0」 |
| `hdc file recv` 逐帧慢 + 覆盖 mtime | `fs pull --dir` 批量；截图序列先命名后整目录拉 |
| `hidumper --mem` 列含括号 `Pss(xxx in SwapPss)` 且列会错位 | 解析器按「表头定位列区间」而非固定列号；回归靠 fixture |
| `mm_dmabuf_info` 列错位（buf_name 长、buf_type 空） | 按内容匹配（延续既有脚本经验），fixture 覆盖异常行 |
| faultlog 引号地狱 | `-a` 整串由 Hdc 层组装为单参数数组；失败时打印「实际 argv」便于对账 |
| 符号化拿错 `.so` 得到错误函数名 | `sym` 强制 Build-ID 前置校验（`llvm-readelf -n`），不匹配 exit 7；`.so` 路径支持 `--search <dir>` 显式指定，不做全盘 glob |
| 图像 diff 在 4K 截图上慢 | vImage 灰度 + 积分图对齐搜索；tile 统计一次遍历；`--tile` 可调 |
| 采样与设备其它操作互相干扰 | 全局并发闸值 ≤3；`watch` 默认只读、不点击 |
| 时间序列判读的主观性 | 固定量化口径：斜率（Least Squares）、阶梯（差分 > ε 连续 2 点）、稳定（连续 K 次 |Δ| < ε）；口径写进 `--help` 与 JSON 字段，避免「看起来没涨」式结论 |

---

## 8. 安全与红线

1. **默认只读**：`tree/log/mem/cpu/shot/diff/dmabuf/watch/crash/sym` 不动 UI、不写设备。
2. **破坏性动作需显式确认**：`tap/input/app clear|uninstall/fs rm` 需 `--yes`；无 `--yes` 时要求 tty 确认或直接拒绝；`--dry-run` 永远可打印 argv。
3. **不触发项目构建**：工具绝不调用 gradle/cmp.sh/ohos.sh；`app install` 只消费现成 HAP。
4. **设备侧写入**限定 `/data/local/tmp/ohd/`，退出时 `--cleanup`（默认保留，便于取证）。
5. **不提交本地文档/产物**：`.dsh/device/**` 进 `.gitignore`；文档遵循 `engineering-doc-append-only`。
6. **命令日志**含 bundle 与路径，不外传；`--json` 输出不嵌入设备全量 dump（大文件只给路径）。

---

## 9. 与现有 skills / 脚本的关系

| 现有 | 迁移路径 |
|---|---|
| `hlog.sh` | `ohd log` 覆盖其子集；保留 shim 一段并行期，skill 改指向 `ohd log` |
| `dmabuf_snapshot.sh` | `ohd dmabuf` 覆盖；脚本保留至 M2 验收通过 |
| `check_reclaim_lifecycle.py layout/drive` | `ohd tree` + `ohd tap` + `ohd replay` + `ohd trace` |
| `…analyze` | `ohd probe`（判据不变，缺 marker 硬作废） |
| `compare_same_position_frames.py` | `ohd diff --align/--tile` |
| `check_screenshot_text_presence.py` | `ohd diff --text-presence`（HF 口径原样保留） |
| skill 内嵌 python 符号化单行 | `ohd sym`（加 Build-ID 门禁） |
| `ohos-crash-log-export` 手拼命令 | `ohd crash list/get` |

**skills 需要同步改的地方（M3 后）**：`cmp-core-ohos-ui-automation-verify`、`ohos-crash-log-export`、`ohos-dmabuf-inspection`、`native-crash-symbolization`、`cmp-core-ohos-device-verify` 的命令段改为 `ohd …`；**判据/防御表保留**（知识不随工具走）。`INDEX.md` 增补一行。

---

## 10. 里程碑与验收

| 阶段 | 内容 | 交付 | 验收 |
|---|---|---|---|
| M0（0.5d） | Swift 包骨架、CLI 框架、`Hdc`/`Session`/`Output`、`doctor`、`env-dump` | 可执行 `ohd doctor --json` | 真机 smoke：正确报出 hdc/设备/API/能力矩阵 |
| M1（1d） | 观测类：`tree shot log mem cpu watch`（含解析器 + fixture 单测） | 6 子命令 | 单测全绿（离线）；真机各跑一次，`--json` 字段与文档一致 |
| M2（1d） | 取证类：`crash sym dmabuf diff fs` | 5 子命令 | 用已有 crash 样例跑通符号化（Build-ID 命中）；dmabuf 对账 Graph/DMA；diff 复现已知 HF 基线（2.207 / mean 235.3） |
| M3（1d） | 编排类：`tap input replay record trace probe fault-watch app` | 7 子命令 | `record`→`replay` 闭环；`trace` 一条命令产出 markdown 报告；`probe` 对既有 r13/r14 日志给出一致结论 |
| M4（0.5d） | skills 命令段更新、INDEX 增补、PATH 软链、旧 py 脚本退场评估 | 文档 | 5 个 skill 命令段可复制即用；旧脚本标记 deprecated 但保留 |

**总体验收线**：任一新排查任务，能用 `ohd` 在**不读源码**的前提下完成「截图找坐标 → 点击 → 抓日志 → 判读 → 崩了自动符号化」全链路，且证据全部落在 `.dsh/device/<ts>/` 可回溯。

---

## 11. 风险与未决问题

| 风险/未决 | 说明 | 建议 |
|---|---|---|
| Swift 二进制体积/启动 | 单文件约数百 KB~数 MB，启动 ~10ms 级 | 可接受；若嫌大，M1 后评估 `-Osize` |
| 沙箱写权限 | module cache 必须落 workspace（已实测）；`~/.local/bin/ohd` 软链需一次授权 | 一次性授权；构建脚本固化 cache 路径 |
| dumpLayout JSON schema 跨 API 版本漂移 | 当前基于 API 24 实测 | 解析器容错 + fixture 版本化；doctor 探测字段 |
| uitest 是否需 `start-daemon <token>` | help 里有该命令，但 `dumpLayout/uiInput` 实测可直接用 | M0 `doctor` 实测并记录结论 |
| 设备多台并存 | 现有 `hdc list targets` 单台 | `--device` 必填消歧 + 默认单台自动选 |
| 是否保留 py 脚本 | 用户明确不要 py | M4 统一 deprecated，保留只读兜底一个版本 |
| 工具名 `ohd` | 可能与其它命令冲突 | **待用户确认**（备选 `dev`、`ohosdev`、`hdct`） |
| 语言最终选型 | Swift 已实测可编译，Rust/Go 需安装 | **待用户确认**：Swift（推荐）vs 装 Rust |

---

## 12. 附录 A：设备原生能力（实测原文摘要）

```
uitest help
  screenCap -p <savePath> [-d displayId]
  dumpLayout -p <savePath> [-i] [-a] [-b bundleName] [-w windowId] [-m true/false] [-d displayId] [-e attr]
  uiRecord record [-W true/false] [-l] [-c true/false]  |  uiRecord read
  uiInput click|doubleClick|longClick <x> <y>
  uiInput swipe|drag <fx> <fy> <tx> <ty> [velocity]
  uiInput fling <fx> <fy> <tx> <ty> [velocity] [stepLength]
  uiInput dircFling <0..3> [velocity] [stepLength]
  uiInput keyEvent <keyID|Back|Home|Power>
  uiInput inputText <x> <y> <text>  |  uiInput text <text>

snapshot_display [-i displayId] [-f output_file] [-w width] [-h height] [-t type] [-m]

hidumper
  --mem [pid] [--prune] [--show-ashmem] [--show-dmabuf]
  --mem-smaps pid [-v]
  --mem-jsheap pid [-T tid] [--gc] [--leakobj] [--raw] [--clean]
  --cpuusage [pid]      --cpufreq      -p [pid]      --storage [pid]      --net [pid]
  --ipc pid ARG         -e [--list|--print] ...      --zip
  -s 1201 -a '-p Faultlogger -f <类型>-<bundle>-<uid>-<时间戳>.log'    # 崩溃原文（免 root）
/proc/<pid>/mm_dmabuf_info, /proc/<pid>/smaps, /proc/<pid>/io
```

## 13. 附录 B：待用户拍板的 3 件事

1. **语言**：Swift 6（本机已实测可编译、零安装）—— 还是愿意装 Rust？
2. **工具名**：`ohd` / `dev` / `ohosdev` / 其他？
3. **落点与并行策略**：源码放工作区 `.agents/scripts/dev/`（可直接写），PATH 软链到 `~/.local/bin/`（需一次授权）；旧 py 脚本是「M4 统一退场」还是「立即删除」？

---

## 14. 选型修订（2026-10-17 追加，用户质询「跨端」）

> 本节**不修改** §3 原文，仅追加对照与修订结论。§3 中 Swift 的选定理由（本机零安装、实测可编译）**依然成立**，但用户提出的「跨端」是新判据，改变了权重。

### 14.1 先把「跨端」定义清楚（这是判据的关键）

本工具**运行在开发机（宿主）**，通过 `hdc` 与真机通信；**不部署到手机/设备**（设备侧只执行 `hdc shell` 的既有命令）。
所以「跨端」只有三种可能含义，必须区分：

| 含义 | 是否适用 | 说明 |
|---|---|---|
| A. 宿主 OS 跨端（mac / Windows / Linux 开发机都能跑） | **可能适用** | 若团队有 Windows 同事要用，这是硬需求 |
| B. 设备端跨端 | 不适用 | 工具不在设备上运行；OHOS 侧只认 hdc 命令 |
| C. 团队/仓库一致性（同语言、可维护、可搬进 CMP 仓做回归） | **适用** | 「更适合我（owner）」的核心诉求 |

### 14.2 三方案跨端真相矩阵

| 维度 | Swift 6 | Kotlin/JVM | Kotlin/Native (KMP) |
|---|---|---|---|
| macOS | ✅ | ✅ | ✅ |
| Linux | ✅（工具链存在） | ✅ | ✅ |
| Windows | ❌ 官方无可用工具链生态 | ✅ | ⚠️ mingwX64 可编译，但**图像与进程两处要额外实现** |
| 实测本机可编译 | ✅（52KB，ms 启动） | ✅（JDK 17 + ~/.gradle 已有缓存）；需 Gradle 首次拉依赖 | ✅ 工具链已备（`~/.konan` **125GB**，多目标齐全） |
| CLI 启动开销 | ~10ms | ~150–300ms（可 AppCDS 降至 ~80ms，后期 GraalVM native-image ~10ms） | ~10ms |
| 图像 diff 能力 | ⚠️ 仅 Accelerate/vImage ⇒ **反而锁死 Apple**（Linux 上要重写） | ✅ **Skiko（Skia）**，与 CMP 渲染同源，判据可比；或用纯 Kotlin 差分 | ⚠️ skiko native 产物覆盖 iOS/OHOS/mac，**mingw 缺** ⇒ Windows 上无图像库 |
| CLI/基建成熟度 | Foundation 全给（Process/JSON/FileManager） | 生态最全（Clikt/okio/kotlinx-serialization/coroutines） | 需手搓：进程 spawn（`posix_spawn`；Windows 上 `CreateProcess` interop）、路径/终端/时间 |
| 与既有仓库一致性 | 低（新语言栈） | 高（同 Kotlin；可放 CMP-CORE 做真机回归） | 高 |
| 构建/迭代摩擦 | 低（`swift build`） | 中（Gradle；配置一次后可 `--continuous`） | **高**（Gradle + konan 链接慢，首次交叉编译目标下载） |
| 主要风险 | **跨端性最差**（Accelerate 仅 Apple；Windows 无解） | 需 JRE（可 fat-jar 内嵌或 native-image 消除）；启动略慢 | 工时最高、Windows 图像能力缺失、K/N 生态窄 |

**结论修正**：Swift 的「跨端」其实是**伪跨端**（借鉴的 Accelerate 只在 Apple 平台，Windows 直接无解）。若跨端是硬需求，Swift 应降级为「仅 macOS 自用」备选。

### 14.3 修订后的推荐顺序

1. **Kotlin/JVM（首选，若跨端或团队一致性任一成立）**
   - 依赖：Clikt（CLI）、kotlinx-serialization-json（契约）、okio（文件/进程）、kotlinx-coroutines（watch 采样）、**Skiko**（图像 diff，`diff` 的灰度/对齐/tile/HF 全部走 Skia，与 CMP 渲染同源）。
   - 跨端：任意有 JRE 17 的 mac/Win/Linux；分发 = fat jar + `ohd` / `ohd.bat` 包装脚本。
   - 性能补救：AppCDS（`-XX:+AutoCreateSharedArchive`）→ ~80ms；后期 `native-image` → ~10ms（注意 Skiko 在 native-image 下需额外处理，届时可把图像路径换成纯 Kotlin 实现，`diff` 接口不变）。
   - 与仓库一致性最好：可直接与 CMP-CORE 同仓/同 Gradle，未来把真机回归用例（reclaim 生命周期）搬进来零摩擦。
2. **Kotlin/Native（次选，仅当「绝不能有 JVM」）**
   - 优点：真原生、ms 启动、无运行时依赖；本机工具链已齐（125GB konan）。
   - 代价：进程调用、文件遍历、终端着色、超时/重试全部手写；Windows 目标另需 mingw interop；**Windows 上无 skiko native ⇒ 图像对比要么砍掉要么自己写**。
   - 定位：M1–M3 用 JVM 快速把能力做出来，M4 视需要再评估 K/N 移植（接口层已隔离，移植面仅 `Hdc`/`Session`/`Output`）。
3. **Swift（降级为 macOS 单机自用备选）**：本机最快落地，但接受「不出 mac、Linux 需换图像方案、Windows 无解」。

### 14.4 对前文各节的连带修订

| 前文位置 | 修订 |
|---|---|
| §3 选型表 | 保留为「历史决策 + 理由」，以 §14 为准；Swift 降级 |
| §5.1 #8 `diff` | 实现层由「vImage/Accelerate」改为「**Skiko(Skia)**（JVM）或纯 Kotlin 差分」；**判据口径不变**（对齐搜索 + tile 异常 + HF 高频能量 + baseline 比值） |
| §7「图像 diff 慢」对策 | JVM/Skia 下改为：Skia `ImageFilter`/手写 tile 遍历 + 积分图；仍保留 tile 可调 |
| §4 架构 | 分层不变（`Hdc`/`Session`/`Output`/`Caps`）；Kotlin 版包结构：`ohd-cli`（Clikt 分发）+ `ohd-core`（Hdc/Session/Parsers/Caps）+ 单测 fixture 同构 |
| §10 里程碑 | M0 增加「Gradle 骨架 + fat jar + `ohd` 包装脚本」；M0 工时至 0.5–1d（Gradle 首次配置） |
| §11 风险 | 新增：JRE 依赖（fat jar 分发/或 native-image）、Gradle 首次联网拉依赖、启动 0.2s 需接受或 AppCDS |
| §13 待拍板 1 | 由「Swift vs Rust」改为「Kotlin/JVM vs Kotlin/Native」（见 §14.3） |

### 14.5 本节锚定

- 追加日期：2026-10-17
- 触发：用户质询「你选 Swift 是因为 mac 吗？考虑跨端是不是 KMP 更合适」
- 事实依据（本次实测）：JDK 17.0.17（Homebrew）+ JDK 11 在场；`~/.konan` 125GB（多目标工具链已备）；`~/.gradle` 有缓存；brew 6.0.18 可用（装 Rust 亦无成本，但非本次方向）；本机无 rustc/go。
- 未变更：工具名 `ohd`、落点 `.agents/scripts/dev/`、旧脚本「并行期 + M4 退场」、能力清单（§5）、数据契约（§6）、红线（§8）。

---

## 15. 最终选定：Kotlin/JVM + Skiko（2026-10-17 追加，跨端确认为硬需求）

### 15.1 决策与依据

用户确认：**Kotlin/JVM + Skiko**；跨端为硬需求（Windows/Linux 同事也要在本机跑 `ohd`）。
§14 的推荐被采纳；§3 的 Swift 方案正式降级为「仅 macOS 自用」备选，不再作为实施基线。

新增实测依据（本轮，2026-10-17）：

| 项 | 事实 | 影响 |
|---|---|---|
| Maven Central | `repo1.maven.org` 200 / 0.52s；`maven.aliyun.com` 200 / **0.13s** | 依赖可拉；建议配阿里云镜像（同事机器同样受益） |
| Skiko 本地缓存 | `org.jetbrains.skiko:skiko-awt:0.150.1` + `skiko-awt-runtime-macos-arm64:0.150.1` **已在 ~/.gradle** | mac 侧零下载即可用；Windows/Linux 需对应 `*-windows-x64` / `*-linux-x64` runtime（可拉） |
| 依赖缓存缺口 | `clikt` / `kotlinx-serialization-json` / `kotlinx-coroutines-core` 无缓存 | M0 首次 `gradle build` 需联网（0.5s 级，一次即好） |
| 版本控制 | `/Users/xuxingwang/KMP` **非 git 仓库**（`fatal: not a git repository`），`.agents/` 未被跟踪 | 不存在「误提交本地文档」风险；若将来搬入 `CMP-CORE`，必须先把 `.agents/`、`.dsh/` 写进 `.gitignore` |
| JDK | 17.0.17（Homebrew）+ 11 并存，`JAVA_HOME=/opt/homebrew/opt/openjdk@17/` | Gradle toolchain 固定 17；启动时校验 `>=17` |

### 15.2 工程骨架（Gradle multi-module）

```
.agents/scripts/dev/
├── DESIGN.md
├── settings.gradle.kts            # 含 pluginManagement + dependencyResolutionManagement（镜像）
├── build.gradle.kts               # 根：kotlin jvm 2.2.x、jvmToolchain(17)、shadow
├── gradle/libs.versions.toml      # 版本锁定（唯一真源）
├── ohd-core/                      # JVM library（纯逻辑，可离线单测）
│   └── src/main/kotlin/ohd/core/
│       ├── Hdc.kt                 # ProcessBuilder 直传 argv、超时、流式、重试、编码
│       ├── Device.kt              # 发现/选择/能力探测（doctor 缓存）
│       ├── Session.kt             # .dsh/device/<ts>-<cap>/ + manifest.json
│       ├── Output.kt              # table/json/markdown、颜色自适应
│       ├── Caps/                  # tree, tap, input, log, crash, sym, shot, replay,
│       │                          #   diff, mem, cpu, watch, dmabuf, app, fs, probe,
│       │                          #   trace, record, fault-watch, doctor, selftest, env-dump
│       └── Parsers/               # LayoutJson, HidumperMem, DmabufInfo, Hilog, FaultLog, CpuUsage
├── ohd-image/                     # 图像对比双引擎
│   ├── ImageEngine.kt             # 接口：gray/diff/align/tile/hf
│   ├── ImageIOEngine.kt           # 默认（JDK 自带，零 native 依赖，跨端最稳）
│   └── SkikoEngine.kt             # --engine skia（0.150.1，与 CMP 渲染同源）
├── ohd-cli/                       # application：Clikt 分发 + shadowJar + 启动脚本
├── ohd-core/src/test/resources/fixtures/   # 真实脱敏样例，跨端离线单测
└── dist/ohd, dist/ohd.bat          # 包装脚本（Windows 同事双击/命令行可用）
```

依赖（`libs.versions.toml` 锁定；版本以 M0 时仓库既有版本为准，主版本如下）：
`kotlin 2.2.x`、`clikt 5.x`、`kotlinx-serialization-json 1.7+`、`okio 3.x`、`kotlinx-coroutines 1.9+`、`skiko-awt 0.150.1`（与本地缓存一致）、`kotlin-test` + JUnit5。

### 15.3 跨端一致性清单（代码层必须钉死的 10 条）

| # | 坑 | 处理 |
|---|---|---|
| 1 | hdc 路径三平台不同 | 发现顺序：`--hdc` → `$HDC` → `$OHOS_SDK_HOME` → mac `~/Library/OpenHarmony/Sdk/*/toolchains/hdc` → win `%LOCALAPPDATA%\OpenHarmony\Sdk\*\toolchains\hdc.exe` → linux `~/OpenHarmony/Sdk/*/toolchains/hdc` → `PATH`；全不中则 `doctor` 给一行安装提示，不抛栈 |
| 2 | Windows 下不要过 shell | 一律 `ProcessBuilder(argv)` 直传，**绝不** `cmd /c`；设备侧命令串（如 dumpLayout 参数）在工具内拼成单个 argv 元素 |
| 3 | 输出编码 | 设备侧输出为 UTF-8，但 Windows 控制台 CP936 可能干扰 → `ProcessBuilder` 输出统一按 UTF-8 解码（`--encoding` 可覆盖），异常字节替换不抛 |
| 4 | 换行 | `\r\n` 统一 strip 成 `\n` 后再解析（fixture 单测含 CRLF 变体） |
| 5 | 宿主路径 vs 设备路径 | 设备侧恒 POSIX；`hdc file recv` 目标路径在 Windows 上**统一转正斜杠 + 绝对路径**（hdc 对反斜杠敏感） |
| 6 | 颜色/tty | `System.console()!=null` && `NO_COLOR` 未设 && stdout 非重定向 才上色；`--no-color` 兜底；不假设 `TERM` |
| 7 | 文件占用（Windows AV/索引） | recv 后首次读取失败重试 3 次 × 100ms（幂等） |
| 8 | 时间与时区 | manifest 统一 `OffsetDateTime` 本地时区 + 偏移字段；设备 hilog 时间为设备本地，单独标注 `deviceTz` |
| 9 | CSV 给 Excel | 提供 `--csv-bom`（Windows Excel 中文列名必须），默认无 BOM |
| 10 | 路径长度/大小写 | 会话目录名 `<yyyymmdd-HHMMSS>-<cap>` 控制在 32 字符内（Windows MAX_PATH）；不依赖路径大小写 |

### 15.4 图像对比：双引擎（降风险的关键设计）

`diff` 的**判据口径不变**（对齐搜索 + tile 异常 + HF 高频能量 + baseline 比值），但引擎可选：

- **默认 `imageio`**：`javax.imageio` 解码 PNG/JPEG + JIT 优化像素循环。零 native、三平台同源、无 skiko 版本耦合风险。
- **`--engine skia`**：Skiko `Image.makeFromEncoded` + `Bitmap.readPixels` → `IntArray`，与 CMP 渲染同一条 Skia；适用于「判据要与渲染侧严格同源」或大图性能敏感场景。
- **M0 验证点**：Skiko 在 headless JVM（无 GPU、无 awt 窗口）下 `Bitmap`/`Image` 纯 CPU 编解码是否可用；若踩坑则 `skia` 引擎推迟到 M2，`imageio` 先顶上（接口不变，切换零成本）。

### 15.5 分发与协作（Windows/Linux 同事怎么拿到能用）

1. `dist/ohd`（bash）与 `dist/ohd.bat`（cmd）都是 3 行包装：校验 `java -version >= 17` → `java -jar ohd-all.jar "$@"`。
2. 分发形态：M0–M3 = `ohd-all.jar`（shadow fat jar）+ 两个包装脚本，**要求本机 JDK 17**；M4 = `jlink` 出精简 runtime（zip 约 40–60MB，免装 JDK）或评估 `native-image`（注意 Skiko JNI 需额外配置，风险高，仅作可选项）。
3. **新增 `ohd selftest`（跨端必需）**：不碰设备，跑解析器 fixture + 图像 diff 固定用例 + hdc 发现 + 编码/换行处理，输出 PASS/FAIL 清单。同事 30 秒自证环境可用，失败项直接贴回来定位。
4. 镜像：`settings.gradle.kts` 里预置阿里云 Maven 镜像（实测 0.13s vs 0.52s），同事首次构建不卡。
5. 编码约定：源码/文档/输出全 UTF-8；Windows 同事用 GBK 打开 md 会乱码 —— 交付说明里写明用 UTF-8 编辑器打开。

### 15.6 修订后的里程碑

| 阶段 | 增量变化 | 工时 |
|---|---|---|
| M0 | Gradle 多模块骨架 + `libs.versions.toml` + 镜像 + 包装脚本 + `doctor` + `selftest` + `env-dump`；Skiko headless 验证 | 0.5 → **1d** |
| M1 | 观测类 `tree shot log mem cpu watch` + 解析器 fixture（含 CRLF/列错位变体） | 1d |
| M2 | 取证类 `crash sym dmabuf diff fs`（diff 双引擎） | 1 → **1.5d** |
| M3 | 编排类 `tap input replay record trace probe fault-watch app` | 1d |
| M4 | skills 命令段切换、`jlink` runtime 打包、旧脚本退场评估 | 0.5d |

总工时由 4d 调整为 **约 5d**（跨端一致性 + selftest 是实打实的增量，不是虚报）。

### 15.7 新增风险（相对 §11）

| 风险 | 说明 | 对策 |
|---|---|---|
| JRE 依赖 | 同事机器可能无 JDK 17 | 启动即检查并给一行安装指引；M4 jlink 打包消掉 |
| 启动 0.2s | 每次单发命令付一次 | AppCDS（`-XX:+AutoCreateSharedArchive`）→ ~80ms；长任务无感 |
| Skiko headless 未知 | 需实测 | 双引擎，imageio 兜底，接口隔离 |
| Windows 终端能力弱 | conhost 不支持 ANSI/Unicode 框线 | ASCII 表格模式 `--ascii`；颜色自动降级 |
| Gradle 首次构建联网 | 同事机器 | 镜像预置 + `selftest` 检出缺依赖时给精确命令 |
| hdc 版本差异 | 同事可能用旧 hdc | `doctor` 记录并要求 `>= 3.0`，不满足给出「已知行为差异」清单 |

### 15.8 本节锚定

- 追加日期：2026-10-17；触发：用户选定「Kotlin/JVM + Skiko」且确认「Windows/Linux 同事也要跑」
- 实测依据：Maven 可达（aliyun 0.13s）、`skiko-awt(-runtime-macos-arm64) 0.150.1` 已缓存、`clikt/serialization/coroutines` 未缓存、JDK 17.0.17、工作区非 git 仓库
- 不变项：工具名 `ohd`、落点 `.agents/scripts/dev/`、产物 `.dsh/device/`、能力清单 §5（12 项 + 10 项补充）、数据契约 §6、退出码 §6.3、红线 §8、旧脚本并行至 M4

---

## 16. 证据包（Evidence Bundle）与 AI 自举工作流（2026-10-17 追加，目标重构）

### 16.1 目标重构：从「命令集」到「产物契约」

原设计的中心是「12 项能力 → 12 条命令」。用户的真实目标是：
**测试发现问题 → 直接把产物交给开发（或 AI）→ 有足够信息直接定位/修复 → 形成自举工作流。**

因此重心改为：**`ohd` 的价值 = 产出一个「自描述、自包含、可校验、可复现」的证据包**。
命令（tree/log/mem/crash/sym/watch/dmabuf/diff）只是取证手段；**决定成败的是包的结构与完备性**。

### 16.2 证据包结构（`ohd-case/1`）

```
case-20261017-142233-com.tencent.compose-cppcrash/
├── README.md              # ≤40 行：症状一句话 / 触发场景 / 复现命令 / 结论与置信度 / 缺失项
├── context.json           # 机器入口：schema 版本 + 设备指纹 + bundle/build-id + 崩溃字段 + 产物索引(sha256/size/kind)
├── repro/
│   ├── steps.json         # 可直接 `ohd repro`
│   ├── run.sh             # 一键：装包(可选)→启动→回放→取证据
│   └── run.bat            # Windows 同事用
├── crash/
│   ├── raw.log            # faultlog 原文（不加工）
│   ├── symbols.json       # pc → 函数(文件:行)，含 build-id 与 so 路径
│   └── threads.txt        # 崩溃线程 + 关键线程栈（Registers/Backtrace 摘要）
├── logs/
│   ├── app.log            # 应用 tag 窗口（崩溃前 T 分钟，gzip）
│   └── system.log         # 被杀/ANR/OOM/低内存 等系统事件窗口
├── frames/
│   ├── 0001.png …        # 低帧率帧（默认 1fps，崩溃前 T 秒）
│   └── replay.gif        # ImageIO 合成，无需 ffmpeg
├── metrics/
│   └── sample.csv        # pss/graph/dma/rss/cpu 时间序列（列名固定）
├── env/
│   └── device.json       # model/API/软件版本/ABI/分辨率/刷新率/内存/温控/时区
└── COMPLETENESS.json     # 缺哪些必备件、因此哪些结论不可下
```

### 16.3 让 AI 能直接用的四条性质（缺一条就退化成「一堆原始文件」）

| 性质 | 具体做法 | 反面（现在的痛点） |
|---|---|---|
| **自描述** | `README.md` + `context.json` 双入口：人读前 40 行，AI 读 JSON 索引；每个产物带 `kind/sha256/size` | 现在是一堆 `/tmp` 文件，AI 得逐个 `head` 猜格式 |
| **自包含** | 设备指纹 + bundle 名 + HAP 路径 + so 的 Build-ID 全在包内；符号化**在测试机侧完成**（带 debug so） | 现在要回问「这是哪台机器哪个版本」 |
| **可校验** | `ohd case verify` 校验 schema/sha256/必备件；缺件时写 `COMPLETENESS.json` 并**显式禁止**相应结论 | 现在「看着像崩了」就给结论，事后发现没符号 |
| **可复现** | `repro/steps.json` + `run.sh|.bat`，一步重放；重放结果自动生成第二个包可 diff | 现在靠记忆和口述 |

**防幻觉设计（关键）**：`COMPLETENESS.json` 里逐项声明 `ok / missing / degraded`，例如
`{"symbols":"missing","reason":"only stripped libkn.so found","forbiddenConclusions":["无法给出函数名","不得下「内存泄漏」结论"]}`。
这样 AI（或人）拿到的不是「看起来完整的包」，而是**知道自己在哪些维度上瞎**——避免自信地编根因。

### 16.4 新增能力（相对 §5 的能力清单）

| 命令 | 职责 | 说明 |
|---|---|---|
| `ohd soak` | 长跑驱动 + 看护 + 现场冻结 | 时长/循环/随机/`--driver 外部脚本`；hdc 掉线重连、应用被杀检测、设备重启恢复；滚动日志分片；**崩溃即冻结现场并出包** |
| `ohd bundle` | 从会话/现场生成证据包 | 组装 16.2 结构、压缩日志、合成 GIF、写 README/context；输出包路径 + 体积 |
| `ohd analyze <pkg>` | **离线**确定性判读（不碰设备） | 只做可证伪的分析：符号化覆盖率、被杀原因分类（faultlog 有/无）、指标趋势与阶梯、日志 marker 对拍、缺件 → 禁止结论清单。**不做「根因推理」**，根因交给开发/AI 基于包内证据下判 |
| `ohd repro <pkg>` | 按包重放并生成对照包 | 重放 steps，自动出第二包；`--diff-crash` 判断「是否同一崩溃」（比信号+栈顶指纹） |
| `ohd case verify <pkg>` | 包完整性与契约校验 | 给测试人员/开发的第一道门；不通过直接 exit 7 |
| `ohd report <pkg> --format md\|html` | 生成给人/工单的正文 | md 贴工单；html 给测试报告归档 |

**为什么 `analyze` 不假装做根因**：工具做根因只能给「相似度/启发式」，一旦输出「疑似 XX 泄漏」，人和 AI 都会被带偏。正确切分是——
**工具负责「证据是否充分 + 事实抽取」，AI/开发负责「因果判断」**。这条写死，避免自举变成「自信地错」。

### 16.5 闭环工作流（AI 自举）

```
[测试机] ohd soak --driver ./hydump.sh --bundle-on-crash
    └─ 崩溃 → 冻结现场 → ohd bundle → case-xxx/  (≤50MB 核心包)
                 ↓   (人工或自动投递：工单附件 / 共享目录 / IM)
[开发机] ohd case verify → ohd analyze → (把 README+context.json 交给 AI)
    └─ AI 读包：事实清楚的直接结论 + 缺件处声明「无法判定」
    └─ ohd repro 复现 → 拿新包与旧包 diff → 修复后回归
                 ↓
[测试机] ohd soak 回归 → 新包对照旧包（同一 case id 串）
```

自举成立的前提（三个都是硬条件，缺任一则退化）：
1. **符号化必须在测试机侧完成**（测试机上有 debug so 与 HAP 同源）；否则包只有地址，AI 只能猜。
2. **步骤必须由工具驱动**（或 uiRecord 可用）；人手点且无录制 → 无法复现，包的价值腰斩。
3. **包必须带 `COMPLETENESS.json`**；否则 AI 无法区分「没崩干净」与「没抓到」。

### 16.6 体积、隐私与投递

| 项 | 策略 |
|---|---|
| 包体积目标 | **核心包 ≤50MB**（日志 gzip + 帧 1fps×T 秒 + GIF）；全量归档（原始帧/全量日志）另存本地，不随包 |
| 帧策略 | 默认仅崩溃前 `--frames-window 20s`、`--fps 1`；如需更密须显式 `--fps 5` 并接受性能污染 |
| 脱敏 | `--redact`：bundle 名/账号/手机号/UID 路径正则替换；截图可选 `--no-frames`（隐私机型） |
| 跨机 | 包内路径全相对；`context.json` 记录原机绝对路径但标记 `originPath`，AI 不依赖它 |
| 投递 | 默认本地目录；`--upload <dir>` 复制到共享盘；不改外部系统 |

### 16.7 价值排序（时间有限时先做什么）

1. **符号化质量**（没有函数名，包再全也没用）
2. **可复现步骤**（决定能不能验修复）
3. **环境指纹 + Build-ID**（决定「是不是同一问题」）
4. **崩溃前后日志窗口 + 内存/线程快照**（决定根因假设）
5. **指标时间序列**（泄漏/卡顿类必看）
6. **帧/GIF**（最次要，且最贵——有观测干扰）

### 16.8 里程碑增补

| 阶段 | 增补 | 工时 |
|---|---|---|
| M0 | 不变（Gradle 骨架 + doctor/selftest） | 1d |
| M1 | 观测类（+ `case verify`、`bundle` 骨架） | 1.5d |
| M2 | 取证类（+ 符号化在包内完成、`frames`/`metrics` 归档） | 1.5d |
| M3 | 编排类（+ `repro`、`report`） | 1.5d |
| M4 | **`soak` + `analyze` + 闭环演练（用一个真实历史崩溃造包跑通）** | 2d |
| 合计 | — | **约 7.5d** |

### 16.9 风险

| 风险 | 说明 | 对策 |
|---|---|---|
| AI 拿半包下结论 | 最常见的失败模式 | `COMPLETENESS.json` + `forbiddenConclusions` 硬约束；`analyze` 不输出根因 |
| 测试机无 debug so | 符号化退化 | 包内 `symbols.json` 标注 `degraded`，并在 README 首行提示「需补 debug so 重跑」 |
| 长跑磁盘/传输 | 数小时日志 | 分片 + 滚动丢弃 + gzip + 核心包上限 |
| 观测干扰（截图/采样） | 影响稳定性结果本身 | 1fps 默认、报告标注开销、`--no-frames` 可关 |
| 跨机重放环境差异 | 机型/版本不同 | `context.json` 记指纹，`repro` 前做指纹比对，不一致先警告 |
| 包格式演进 | 未来字段变化 | `schema: ohd-case/1` 版本化 + 向后兼容读取 |

### 16.10 本节锚定

- 追加日期：2026-10-17；触发：用户明确「测试产物直接给开发/AI，形成 AI 自举工作流」
- 实测约束：设备侧无 screenRecorder/monkey（仅 uitest）；本机无 ffmpeg（GIF 用 ImageIO）；hdc 3.2.0b
- 不变项：工具名 `ohd`、Kotlin/JVM + Skiko、落点 `.agents/scripts/dev/`、红线 §8
- 新增：`soak` / `bundle` / `analyze` / `repro` / `case verify` / `report` 六个能力（§16.4）

---

## 17. 需求确认与设计调整（2026-10-17 追加）

### 17.1 四条确认及其影响

| # | 用户确认 | 设计调整 |
|---|---|---|
| 1 | 测试机**有 debug so** | 符号化定稿为**测试机侧完成**（§16.5 前提 1 满足）；但**不把 so 打进包**，只放符号化结果 + build-id + so 元信息 |
| 2 | hydump 是 **py 脚本**，后续详谈，先作为规划 | `soak --driver <cmd>` 定稿为「外部进程协议」，ohd 不关心驱动语言；py 依赖（如 numpy）由测试机自己负责，ohd 只注入环境变量 |
| 3 | 测试机**有 JDK 17** | 分发形态定稿 **fat jar + `ohd`/`ohd.bat`**；`jlink` 自包含 runtime 从「必须」降为**可选优化**（M4 视需要） |
| 4 | 投递走**共享盘或远程 OSS** | `--upload <dir>`（挂载目录直拷）+ `--upload-cmd '<模板>'`（调外部 CLI，如 ossutil），**不内置 OSS SDK**（避免凭据管理与依赖膨胀） |

### 17.2 符号化策略定稿

- **在测试机侧完成**：`llvm-addr2line -C -f -e <so>` 交叉 `crash/raw.log` 的 pc 列表 → `symbols.json`。
- **包内不打包 .so**（debug so 常几十 MB，会击穿 ≤50MB 预算）。`symbols.json` 记录：`soPath`(测试机绝对路径) + `buildId` + `soSha256` + `sizeMB`，开发可凭 build-id 在自己的构建产物里找到同名 so 复核。
- 疑难 case 提供 `--include-so`（包体积上限提到 300MB，且明确提示）。
- **降级路径**：若符号表缺失/剥离，`symbols.json` 标 `degraded` 并在 `COMPLETENESS.json` 写 `forbiddenConclusions:["无法给出函数名"]`，仍给地址 + 反汇编窗口（`llvm-objdump -d` 局部）。
- **工具来源**：优先 OHOS SDK 自带 `llvm-addr2line`（mac: `~/Library/OpenHarmony/Sdk/*/toolchains/` 附近 native/llvm/bin；Windows: DevEco SDK 同结构）。`doctor` 探测并记录版本，缺则明确提示装 SDK，不静默降级。

### 17.3 外部驱动协议（`soak --driver`）

```
ohd soak --duration 4h --driver "python3 ./hydump.py" --bundle-on-crash --upload /mnt/share/cases
```

| 约定 | 内容 |
|---|---|
| 注入环境 | `OHD_HDC`(hdc 绝对路径)、`OHD_DEVICE`(序列)、`OHD_SESSION`(本次会话目录)、`OHD_CASE_DIR`、`OHD_STEPS`(回放用), 由 ohd 设置，脚本可直接用，避免脚本里再写死 hdc 路径 |
| 生命周期 | 驱动进程**独立**于看护线程；驱动退出 ≠ 会话结束（`--duration` 未到则重启驱动，`--restart-on-exit`） |
| 输出捕获 | stdout/stderr 落 `logs/driver.log`；退出码记录进 `context.json` |
| 崩溃看护 | 并行线程只读 hilog + 轮询 faultlogger，**不干扰驱动**；命中即冻结现场（先抓证据、后停驱动，顺序不可反） |
| 失败判定 | 驱动非零退出 + 命中崩溃 marker / 应用不在前台 / 设备掉线 → 分别归因，不混为一谈 |

py 脚本的依赖问题（如 numpy 缺失）**不由 ohd 承担**；但 `selftest` 会提示"当前 python 缺 numpy，若驱动脚本用到需自行安装"。

### 17.4 投递协议

| 方式 | 用法 | 说明 |
|---|---|---|
| 共享盘 | `--upload /mnt/share/cases/` | 直接复制 + 保留原始目录名；失败重试 3 次 |
| 外部 CLI（OSS 等） | `--upload-cmd 'ossutil cp {pkg} oss://bucket/cases/{case}/'` | 占位符：`{pkg}` 包目录、`{case}` 包名、`{ts}` 时间戳、`{zip}` 打包后 zip；凭据由测试机环境/CLI 自身配置 |
| 仅本地 | 默认 | 不投递 |

不内置 SDK 的理由：凭据、内网地址、CLI 版本都属环境差异，内置必然成为维护负担；模板方式零耦合。

### 17.5 分发形态定稿

- 产物：`ohd-all.jar`（shadow fat jar，约 15–30MB，含 Skiko 依赖）+ `ohd`（bash）/ `ohd.bat`（cmd）。
- 前置：JDK 17+（测试机已确认有）；启动脚本先校验 `java -version`，不满足给一行安装指引。
- 可选优化（M4）：`jlink` 出精简 runtime（省装 JDK）；`native-image` 不作承诺（Skiko JNI 风险）。
- 国产化/内网：预置阿里云 Maven 镜像（实测 0.13s vs 0.52s），首次构建如无外网需离线仓库（列为风险，见 §17.7）。

### 17.6 仍需确认（不阻塞 M0）

| # | 待定 | 影响 |
|---|---|---|
| a | **测试机 OS**（Windows / Mac / Linux） | 决定 `llvm-addr2line` 取哪个平台版本、`run.bat` 是否需要、换行/编码路径 |
| b | OSS 具体用什么 CLI（ossutil / coscli / obsutil） | 只影响 `--upload-cmd` 示例，实现无关 |
| c | 测试人员使用形态：命令行 / 双击 bat / 需要图形 | 若需图形，M4 增一个最简 TUI（不做 GUI） |
| d | 是否要「自动开单」（对接工单系统 API） | 建议先出 `report --format md`，人工贴工单；自动开单等闭环跑顺再谈 |
| e | hydump.py 的输入输出约定（详谈时定） | 决定 `--driver` 是否需要 `--driver-args` 透传与结果解析 |

### 17.7 新增风险

| 风险 | 对策 |
|---|---|
| 测试机无外网，首次构建拉不到依赖 | M0 交付时同时给出离线依赖包（Gradle offline repo 或 `--offline` 缓存目录）说明 |
| 测试机 llvm 版本与 so 匹配问题 | `doctor` 记录 llvm 版本；符号化前比对 build-id，不匹配即标 degraded |
| py 驱动脚本自身崩/挂 | `--driver` 超时 + 心跳文件机制，看护线程判定"驱动失联"并记录，不当成应用崩溃 |
| OSS 上传失败导致证据丢 | 先本地落盘成功再上传；上传失败仅告警，不阻塞会话（本地包仍在） |

### 17.8 本节锚定

- 追加日期：2026-10-17；触发：用户答复四项（debug so 有 / hydump 为 py 待详谈 / 测试机有 JDK17 / 共享盘或 OSS 投递）
- 状态：**规划阶段，仍未写实现代码**；本轮仅钉死符号化、驱动协议、投递协议、分发形态四处设计细节
- 不变项：工具名 `ohd`、Kotlin/JVM、证据包 `ohd-case/1`、能力清单 §5+§16.4、红线 §8

---

## 18. M0 实现落地记录（2026-10-17 追加）

### 18.1 落点变更（用户指定）

| 项 | 原规划 | 实际 |
|---|---|---|
| 代码位置 | `.agents/scripts/dev/` | **`/Users/xuxingwang/KMP/ohd/`**（用户要求「放在 KMP 下独立目录」） |
| 设计文档 | 同目录 | 仍在 `.agents/scripts/dev/DESIGN.md`，代码仓 README 反向链接 |

### 18.2 M0 交付清单（13 个 Kotlin 文件，约 1400 行）

| 模块 | 文件 | 职责 |
|---|---|---|
| 构建 | `settings.gradle.kts` / `build.gradle.kts` / `gradle/libs.versions.toml` | Gradle 8.13 + Kotlin 2.2.20 + 阿里云镜像；`pluginManagement` 置于首块 |
| `ohd-core` | `Env.kt` | 平台收敛 + hdc/llvm 发现链（参数→环境变量→SDK 版本目录→PATH），SDK 按版本号降序 |
| | `Hdc.kt` | argv 直传、超时 kill、重试、UTF-8 解码、CRLF 归一、设备路径正斜杠 |
| | `Device.kt` | 设备列表/选择（多台强制 --device）、指纹采集 |
| | `Session.kt` | `.dsh/device/<ts>-<cap>/` + 序号产物 + manifest（含 sha256） |
| | `Output.kt` | 表格（CJK 宽度=2）/JSON 骨架/颜色自适应/`Options`/`RunContext`/`Capability` |
| | `Checks.kt` | `Check`/`Status`/`hasFailure` |
| | `caps/DoctorCap.kt` | 9 类自检：jvm/platform/hdc/设备指纹/uitest/snapshot/faultlog/llvm/磁盘 |
| | `caps/SelftestCap.kt` | 离线 7 项：平台/换行/argv/路径/Session/JSON/CJK + 可注入扩展检查 |
| | `caps/EnvDumpCap.kt` | 设备指纹落盘 `device.json` |
| `ohd-image` | `GrayImage.kt` / `ImageIOEngine.kt` | 灰度解码、对齐无关差分（mean/max/changed/tile 异常）、HF 高频能量 |
| `ohd-cli` | `Main.kt` | 手写 argv 解析、能力注册、全局选项、退出码、帮助 |
| 分发 | `dist/ohd` / `dist/ohd.bat` / `dist/build.sh` / `dist/build.bat` | 包装与显式构建入口（复用机器上已有 Gradle 发行版） |
| 测试 | `ohd-core/src/test/.../CoreTest.kt` | 11 个离线用例：argv/换行/路径/会话命名/manifest/宽度/环境发现 |

### 18.3 与设计的四处偏差（及理由）

| 偏差 | 设计原方案 | 实际 | 理由 |
|---|---|---|---|
| CLI 解析 | Clikt 5.x | **手写解析** | 零依赖、启动更快、跨端行为完全可控；M0 只有 3 个能力，收益大于成本。M1 能力变多后如需要再评估引入 |
| 图像引擎 | ImageIO + Skiko 双引擎 | **M0 仅 ImageIO** | Skiko 需按平台引 runtime 分类器（`macos-arm64` 已缓存，win/linux 需下载），M0 不引入以保持跨端零 native；接口已隔离，M2 直接加 `SkikoEngine` |
| 打包 | shadow fat jar | **application 插件 `installDist`** | 避免第三方插件版本风险；产物结构为 launcher + lib 目录，`dist/ohd` 已封装 |
| 序列化 | kotlinx-serialization（含插件） | **仅 runtime + JsonElement API** | 不启用编译器插件，少一个构建面；manifest/JSON 输出用 `buildJsonObject` 拼装即可 |

### 18.4 本轮验证（**未编译**，遵 no-proactive-build）

| 校验 | 方法 | 结果 |
|---|---|---|
| 括号平衡（剔除字符串/注释后） | 自建状态机脚本 | 全部通过 |
| 冗余 import | 符号引用计数 | 0 处（已清理 7 + 1 + 1 处） |
| 编码 | 检查 BOM / CR | 无 |
| 版本可用性 | `~/.gradle` 缓存 | Gradle 8.13 发行版已在本地；Kotlin 2.2.20 需首次联网拉取（Maven 可达，0.52s/aliyun 0.13s） |

**未验证（诚实声明）**：未编译、未在任何设备上运行。以下判定阈值需首次真机运行时校准：`snapshot_display -h` 与 `hidumper -s 1201 -a '-h'` 的输出特征匹配（doctor 用它判可用性）、`/system/bin/uitest` 路径存在性。

### 18.5 下一步（M1）

`tree` / `log` / `shot` / `mem` / `cpu` / `watch` + 对应解析器 fixture（含 CRLF、列错位变体）。其中 `tree` 与 `shot` 决定「坐标确认」闭环，`log` 决定「marker 判读」，三者是后续所有能力的观测基础。

### 18.6 本节锚定

- 日期：2026-10-17；触发：用户「开始写代码 放在 kmp 下的独立的目录下」
- 代码位置：`/Users/xuxingwang/KMP/ohd`（非 git 仓库跟踪；如搬入 CMP-CORE 需先加 `.gitignore`）
- 不变项：能力清单 §5/§16.4、证据包 `ohd-case/1`、红线 §8、跨端细则 §15.3、符号化与投递 §17

---

## 19. M1 落地记录：观测层（tree / shot / mem / cpu / log / watch）

### 19.1 实测格式修正（2026-10-17 取样，SGT-AL50 / API 24）

**解析器不靠猜——以下全部由真机取样驱动，fixture 已入库。**

| 项 | 设计假设 | 实测结果 | 代码影响 |
|---|---|---|---|
| `snapshot_display -f` | 可用 `.png` | **只接受 `.jpeg`**：`error: fileName ... invalid, suffix must be .jpeg` | `ShotCap` 固定 `.jpeg`；标注另存 `.png` |
| 截图分辨率 | 需解码图片 | stdout 直接打印 `width: 1280, height: 2832` | 省一次解码，网格按真实尺寸画 |
| `hidumper --mem <pid>` | 单行 `Pss(xxx in SwapPss)` | **分类表**：`GL`/`Graph`/`ark ts heap`/`native heap`/`.hap`/`AnonPage other`/`stack`/`.db`/`.so`/`dev`/`.ttf`/`FilePage other` + `Total`，共 10 列 | 按「末尾 10 个数值」反推名称（分类名含空格，不能固定列切） |
| `hidumper --cpuusage` | 未知 | `Load average: a / b / c` + `CPU usage from .. to ..` + `Total: x%; User Space: ..%; Kernel Space: ..%; iowait: ..%; irq: ..%; idle: ..%` + 进程表 | 四条正则直解 |
| `hidumper --cpufreq` | 未知 | `cmd is: cat .../cpuN/cpufreq/cpuinfo_cur_freq` + 空行 + 数值；每核 cur 与 max 两段，共 24 段 | 状态机配对解析 |
| `uitest dumpLayout` | 未知结构 | 顶层 `{attributes, children}`；`bounds` = `[l,t][r,b]`；`clickable/enabled/visible` 是**字符串** `"true"`；110 个带 bounds 节点 | 兼容字符串/数字/布尔三种编码 |
| `hilog -x` | 未知 | `MM-DD HH:mm:ss.SSS  pid  tid  L tag: msg`，tag 形如 `I02C01/模块/子模块`（含 `/`） | 非贪婪 `(\S+?):` 定位首个冒号 |

取样命令（可复用）：`snapshot_display -f /data/local/tmp/ohd/x.jpeg`、`uitest dumpLayout -p ...`、`hidumper --mem <pid>`、`hidumper --cpuusage`、`hidumper --cpufreq`、`hilog -x | head`。

### 19.2 新增文件（14 个）

| 文件 | 职责 |
|---|---|
| `parsers/LayoutParser.kt` | `Rect` / `LayoutNode` / `LayoutQuery`；兼容对象/数组顶层与三种属性编码 |
| `parsers/MemParser.kt` | 分类表 + Total；`glKb/graphKb/nativeHeapKb` 便捷项 |
| `parsers/CpuParser.kt` | usage（load/total/user/kernel/iowait/irq/idle/window）+ 进程表 |
| `parsers/HilogParser.kt` | 行解析 + tag 直方图 + 级别过滤 + 正则计数 |
| `DeviceOps.kt` | 设备侧公共动作：建临时目录 / dumpLayout / snapshot / resolvePid |
| `Hdcs.kt` | 能力入口三件套（拿设备、拿会话、记命令）+ `parseDurationMs` + 极简 state 读取 |
| `caps/TreeCap.kt` | 节点查询 + 中心点；无文本节点时提示改用 `shot --grid` |
| `caps/ShotCap.kt` | 截图 + 网格/十字/节点框标注 |
| `caps/MemCap.kt` | 内存分类表 + 占比 |
| `caps/CpuCap.kt` | 占用/进程 Top/逐核频率 |
| `caps/LogCap.kt` | start/stop/snap/count/save，行数校验 |
| `caps/WatchCap.kt` | 采样 + CSV + 斜率/阶梯/平台期 |
| `ohd-image/ImageAnnotate.kt` | 网格、坐标文字、十字、节点框、中心点 |
| `test/resources/fixtures/*` | 真机样本（layout 74KB / mem / cpuusage / cpufreq / hilog） |
| `test/ParsersTest.kt` | 5 组 × 14 个用例，全部基于真实样本 |

### 19.3 设计落实要点

| 设计条目 | 落地方式 |
|---|---|
| 日志流防僵死（skill 里踩过的坑） | `log start` 落 pidfile；`log stop` 校验行数，0 行 → `STREAM_STALLED` + exit 6，并明说「本轮结论不可用」 |
| 工具只给事实、不下结论 | `watch` 输出斜率（最小二乘）/阶梯/平台期；增长只发 `PSS_GROWTH` 提示且注明「是否为泄漏需结合业务与复现脚本判断」 |
| 坐标确认闭环 | `shot --grid` 目视 + `tree` 给中心点 + `shot --cross x,y` 复核 + `shot --boxes` 叠加节点框 |
| 单一命中免心算 | `tree` 命中唯一节点时直接打印 `点击坐标：x y` |

### 19.4 未验证（诚实声明）

1. 全局：**未编译、未在任何设备跑过**（遵 no-proactive-build），仅做括号平衡 + 冗余 import + API 签名 grep 校核。
2. `log start` 的孤儿进程存活依赖 POSIX：JVM 退出后子进程是否随 stdin 关闭而终止，在 Windows 上行为不同，需实机验。
3. `dumpLayout -a`（--rich）的额外属性名未取样，`rich` 目前只是透传参数。
4. `watch` 的 `--until-stable` 语义以 Pss 差值为准，未考虑 GC 周期性抖动，长跑需配合 `soak`（M3）。

### 19.5 下一步（M2）

`diff`（截图对比，复用 `ImageEngine`）、`replay`（步骤回放）、`crash` + `sym`（崩溃与符号化）——其中 `sym` 是证据包价值的最大单点，需先确认测试机上的 debug `.so` 路径约定。

### 19.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」
- 代码位置：`/Users/xuxingwang/KMP/ohd`（27 个 kt 文件，约 3100 行）
- 设备：`63R0226113004249` / SGT-AL50 / API 24 / `6.1.0.135(SP8C00E120R3P11)`
- 不变项：§5 能力清单、§8 红线、§15.3 跨端细则、§16 证据包规范

---

## 20. 编译与真机验证（2026-10-17，M0+M1 首次全量跑通）

### 20.1 结果总览

| 项 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL`，Gradle 8.13 + Kotlin 2.2.20，3 个模块 |
| 单测 | **27/27 通过**（含 14 个真机 fixture 用例） |
| `selftest` | 8/8 OK（平台/换行/argv/路径/会话/JSON/CJK/图像引擎） |
| `doctor` | 11/11 OK（含 llvm-addr2line 在 DevEco 中找到） |
| `tree` | 100 节点 / 5 个可点节点 / 中心点正确 |
| `mem` | Total 421335 KB，17 个分类，GL 12375 / Graph 27364 |
| `cpu` | 总 20.78%，load 14.30/13.30/13.30，进程 Top 正确 |
| `shot` | 1280×2832，网格 + 十字 + 100 个节点框全部绘制正常 |
| `watch` | 6 样本，Pss 400380→400197，斜率 -1613 KB/分，平台期 5 次 |
| `log` | start→stop 8345 行 / 1.1 MB / 首行延迟 200 ms |

### 20.2 编译期真实缺陷（4 个，全部已修）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Gradle could not start`（libnative-platform.dylib） | 沙箱禁写 `~/.gradle/native` | 构建需放开该目录写权限；工具链层面无解 |
| 2 | `dist/build.sh` 找不到 gradle | `find -maxdepth 4`，实际路径深度 5（`dists/<hash>/<ver>/bin/gradle`）；且 `sort -V` 会选到已存在的 gradle-9.5.0（Kotlin 2.2 未适配） | 改 `-maxdepth 6` + 正则优先 8.x |
| 3 | `'val' cannot be reassigned` ×7 | `roots += x` 在 `?.let{}` / `when` 分支内被解析成 `plus` 而非 `plusAssign` | 统一改 `mutableList.add(x)` |
| 4 | `selftest` 少一项（image 检查不显示） | `extraChecks.forEach { ... }` 的返回值被丢弃，未 `add` 进 checks | `checks.add(runCatching{...})` |

另外修了两处**输出质量**缺陷：表格 header 下方误用 `└┴┘`（应为 `├┼┤`）；节点文本含换行会把表格撑断（改为压成单行）。

### 20.3 真机验证发现（2 个，影响使用方式）

**① `hidumper --mem` 分类表存在层级重叠**
`jemalloc heap`(155303) 与 `jemalloc meta`(3985) 已包含在 `native heap`(162166) 内，因此 **Total(422621) < 分类和(558785)**。
→ 结论：`MemReport.pssKb` 必须优先取 Total 行，绝不能用分类求和；`mem` 输出的「占比」按 Total 为分母。

**② Compose 自绘控件在节点树里没有独立节点**
当前台为 Compose 页面时，紫色按钮「原生 +1」「重新加载 bundle」在 `dumpLayout` 结果里**没有单独节点**（其 bounds 只剩父级 RelativeContainer 的整块），但页面上的纯文本（`paragraph` 类型）仍可见。
→ 结论：`tree` 只能覆盖「有 accessibility 节点」的控件；**坐标确认必须依赖 `shot --grid` 目视**，这条设计约束（§5.1 #5 的兜底方案）在实测中被证实是必需的，不是冗余。

### 20.4 遗留风险

1. Windows 侧未验证：`log start` 的孤儿进程存活、`dist/ohd.bat` 转发参数、表头 CJK 宽度。
2. `watch` 的 `--until-stable` 在 GC 周期抖动下可能永远不稳（目前靠 `--duration` 兜底）。
3. `shot --boxes` 在节点重叠时视觉噪声大（100 框叠 1 屏），后续可加 `--boxes-filter`。

### 20.5 本节锚定

- 日期：2026-10-17；触发：用户「编译」
- 代码：`/Users/xuxingwang/KMP/ohd`（27 个 kt + 5 个 fixture，约 3200 行）
- 设备：`63R0226113004249` / SGT-AL50 / API 24
- 构建命令：`./dist/build.sh`（installDist）/ `./dist/build.sh test`

---

## 21. M2 落地：diff / tap / input / replay / crash / sym（2026-10-17）

### 21.1 交付

| 能力 | 命令 | 真机验证 |
|---|---|---|
| `diff` | `ohd diff --baseline a.png [--current b.png]` | 相同图 0.000% → exit 0；不同图 4.656% → exit 7 ✅ |
| `tap` | `ohd tap <x> <y> [--double/--long]` | 命令下发成功 ✅（视觉反馈因设备锁屏未取得） |
| `input` | `ohd input text/swipe/drag/fling/key` | 命令生成与下发 ✅ |
| `replay` | `ohd replay <脚本.ohd> [--dry-run]` | 4 步全成功，脚本随 session 存档 ✅ |
| `crash` | `ohd crash [--list] [--name X] [--details]` | 6 条故障全部列出，appfreeze 解析出 1531 帧 ✅ |
| `sym` | `ohd sym --crash <f> --so-dir <d>` | 自造 arm64 debug so 端到端出符号 ✅ |

新增源文件：`CrashParser.kt`、`UiActions.kt`、`Symbolizer.kt`、`DiffCap.kt`、`ReplayCap.kt`、`CrashCap.kt`、`SymCap.kt`、`TapInputCap.kt`、`ImageDiffMap`（追加到 ImageAnnotate）；测试 `M2Test.kt`（11 用例）；fixture `crash-jscrash.txt`(69 行)、`crash-appfreeze.txt`(181 行，从 8213 行裁出)。

### 21.2 符号化为什么值得信：端到端验证方法

`--so-dir` 里随便放个 so 也能"出符号"，所以必须验证**拒绝机制**。用 SDK 的 clang 现造样本：

```
clang --target=aarch64-linux-ohos --sysroot=<sdk>/sysroot -g -O1 -shared -fPIC \
      -Wl,--build-id=sha1 -o /tmp/libohdsymtest.so /tmp/ohdsymtest.c
```

| 用日志里的 buildId | 结果 |
|---|---|
| 与本地 so **一致** | `#00 0x177c → accumulate at /tmp/ohdsymtest.c:3`，exit 0 |
| 与本地 so **不一致** | `BUILD_ID_MISMATCH`，拒绝出符号，**exit 7** |

关键事实：**崩溃日志自带 buildId**（`#00 pc ... libark_jsruntime.so(...)(0c10ca1f290a450c19e957291d44cbf4)`），长度不固定（实测 32 与 40 hex 都出现）。所以符号化不需要额外问构建机要版本号，只需要拿到对应版本的 debug `.so`。

### 21.3 本轮修掉的 6 个缺陷

| # | 缺陷 | 影响 | 修法 |
|---|---|---|---|
| 1 | **`out.note()` 只收集不打印** | 全局性：`SCREEN_BLACK`/`UI_MISMATCH`/`NO_SO_DIR`/`BUILD_ID_MISMATCH` 等**所有警告对人隐形**，只有 --json 能看见 | 非 json 模式直接 `println("! [CODE] msg")` |
| 2 | `shot` 熄屏时返回全黑图且不报错 | CI 里静默产出无效证据，事后无法分辨"UI 是黑的"还是"屏灭了" | 采样平均亮度，`< 8` 报 `SCREEN_BLACK` |
| 3 | `shot --out x.png` 写出 JPEG 字节 | 文件内容与扩展名不符，下游工具按 png 解析会失败 | 非 jpeg 扩展名走 ImageIO 真转码 |
| 4 | `Files.walk` 不跟随符号链接 | macOS 的 `/tmp`、`/var` 是软链 → `--so-dir /tmp` 报 MISSING_SO | 先 `toRealPath()` 再遍历 |
| 5 | `crash` 时间字段为空、页面轨迹混入 binder 表 | appfreeze 用 `start time:` 而非 `Timestamp:`；页面轨迹只该收 `HH:MM:SS.mmm` 行 | 字段回退链 + 行格式白名单 |
| 6 | `replay` 每步结果不显示 | 回放报告的主体被 `note` 的 info 级别过滤掉 | 改用 `out.info` 直接打印 |

### 21.4 实测数据锚点

| 指标 | 值 |
|---|---|
| 设备故障数 | 6（5×appfreeze + 1×jscrash，均属 `com.tencent.compose`） |
| appfreeze 帧数 | 1531 个 native 帧，带 `Tid:` 线程段与 `Page switch history` |
| jscrash 结构 | `Stacktrace:`(JS) + `HybridStack:`(native, 带 buildId) |
| diff 阈值表现 | 同图 meanAbs 0.00/maxAbs 0；不同图 meanAbs 3.98/maxAbs 149/55 个异常 tile |
| 亮度基准 | 熄屏 0、锁屏壁纸 20、正常页面 231（阈值 8 落在熄屏与锁屏之间，不会误报锁屏） |
| `uitest uiInput` 语法 | `click/doubleClick/longClick/doubleClick`、`swipe/drag x1 y1 x2 y2 [vel]`、`keyEvent Back`、`inputText x y text`、`text text` |

### 21.5 遗留与下一步

1. **真实项目 debug .so 未验证**：`sym` 链路已用自造样本证实，但用户项目的 so 目录结构（`build/default/intermediates/libs/arm64-v8a/` vs `libs/`）需确认真实路径再固化默认值。
2. **`tap` 视觉反馈未取得**：验证时设备进锁屏，只能确认命令下发成功；建议后续在 CI 前置 `keyEvent Power` 唤醒 + `SCREEN_BLACK` 断言。
3. Windows 侧仍未验（`log start` 孤儿进程、`.bat` 转发、CJK 宽度）。
4. M3 未做：`dmabuf`、`soak`、`bundle`、`analyze`、`repro`、`report`。

### 21.6 本节锚定

- 日期：2026-10-17；触发：用户「m2」
- 代码：`/Users/xuxingwang/KMP/ohd`（+8 源文件，测试总数 27 → 38）
- 设备：`63R0226113004249` / SGT-AL50 / API 24 / `6.1.0.135(SP8C00E120R3P11)`
- 目标应用：`com.tencent.compose`（Compose 自绘页面，用于验证"无节点也能操作"）

---

## 22. M3-a：`bundle` 证据包（2026-10-17）

### 22.1 交付

`ohd bundle [--session a,b,c] [--out <dir>] [--name X]`：把一次或多次会话的产物组装成 `ohd-case/1` 结构，并生成 `COMPLETENESS.json`。

实测包结构（7/7 齐全时，8 个产物 / 2.5 MB）：

```
case-<ts>-<capability>/
├── README.md           人读：包含什么、缺什么、因此不能下什么结论
├── context.json        机器读：产物索引 + sha256 + 设备指纹
├── COMPLETENESS.json   约束读：7 类必备件状态 + forbiddenConclusions
├── crash/raw.log       faultlog 原文
├── crash/symbols.json  符号化结果
├── env/device.json
├── frames/0001-shot.jpeg ...
├── layout/001-layout.json
├── logs/app.log
├── metrics/sample.csv
└── repro/{steps.ohd,steps.json,run.sh,run.bat}
```

七类必备件与「缺则不可下的结论」是一一绑定的，不是装饰：

| 必备件 | 缺失时禁止的结论 |
|---|---|
| env | 不得把结论套用到其它机型或系统版本 |
| frames | 无法判断界面表现，也无法核对坐标 |
| logs | 无法判断异常发生前的运行轨迹 |
| crash | 不得断定崩溃原因 |
| symbols | 无法给出函数名与行号，只能给地址与 so 名 |
| metrics | 无法判断内存/CPU 趋势，不得下「内存泄漏」结论 |
| repro | 无法声称该问题可复现 |

### 22.2 `bundle` 暴露的 5 个架构缺陷（比能力本身更重要）

做 bundle 才发现**证据链的元数据层一直是断的**：

| # | 缺陷 | 后果 | 根因与修法 |
|---|---|---|---|
| 1 | `manifest.json` 只有 3 个能力会写（selftest/env-dump/log） | tree/shot/crash/sym/watch/diff/replay 的产物**没有任何元数据落盘**，bundle 无从归类 | 在 CLI 层统一写：`RunContext.sessions` 记录本次创建的会话，能力结束后 `writeManifest(device)` |
| 2 | `Hdcs.session()` 每次调用都新建目录 | 一个命令产出多个会话目录，manifest 落不到产物边上 | 同一能力内按 capability 复用同一个会话 |
| 3 | `log start`/`stop` 从不 `register` hilog | 日志永远进不了证据包（`logs` 恒 missing） | start 登记文件；stop 登记绝对路径 |
| 4 | `register()` 只存 `path.fileName` | 跨会话引用（stop 引用 start 的文件）找不到文件 | 会话内相对路径、会话外绝对路径 |
| 5 | 空日志被判成 `TIMEOUT`(exit 6) | 设备安静 2 秒无输出就报超时，CI 误判 | 空日志是证据缺失（事实）→ 改 `ASSERT_FAILED`(4)，提示改为"延长采集/先触发操作" |

### 22.3 一致性校验：`SYMBOLS_MISMATCH`

把多次会话合并时，可能出现「符号结果来自 A、崩溃原文来自 B」的拼接错误——这种包比没有包更危险，因为 AI 会把不匹配的函数名当成现场。

实测触发（sym 用自造假 crash，bundle 里是真实 appfreeze）：

```
! [SYMBOLS_MISMATCH] 符号结果与崩溃原文不一致，已降级为不可用：
  符号结果对应「com.example.ohdsym / SIGSEGV」，
  本包崩溃原文是「com.tencent.compose / THREAD_BLOCK_6S」，不是同一次现场
```

判定依据是 `symbols.json` 里的 `reason` + `module` 与 `crash/raw.log` 解析结果比对；不一致则 `symbols` 从 ok 降为该缺失项。同现场时（同一份 faultlog 做 sym）实测判定 `OK`，不误报。

### 22.4 使用流程（实测）

```
ohd log start
<触发问题>
ohd crash --limit 2          # 落 faultlog
ohd sym --latest --so-dir <debug so>   # 落 symbols.json
ohd shot                     # 落截图
ohd log stop                 # 落日志
ohd replay case/login.ohd    # 落脚本
ohd bundle                   # 默认打最近会话；多次会话用 --session a,b,c
```

`--session` 支持逗号分隔合并多个会话——真实排查必然是多条命令产出多个目录。

### 22.5 遗留

1. 包内不含 GIF（`frames` 只有离散截图）；`replay.gif` 需要帧合成，属 M3-b。
2. `metrics` 只收 `watch` 的 csv，`mem`/`cpu` 的原始文本归到 `metrics/mem-raw.txt`，尚未做统一序列格式。
3. `--redact`（脱敏）未实现：包内可能含用户路径/包名，外发前需自查。
4. `analyze`/`repro`/`report` 未做——`bundle` 只负责「装包」，不负责「解读」。

### 22.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接 M2 的下一步建议）
- 代码：`/Users/xuxingwang/KMP/ohd`（新增 `BundleCap.kt`，改 `Session.kt`/`Hdcs.kt`/`Output.kt`/`Main.kt`/`LogCap.kt`/`SymCap.kt`）
- 实测包：`.dsh/cases/case-20261017-140849-replay/`
- 设备：`63R0226113004249` / SGT-AL50 / API 24

---

## 23. M3-b：`bundle --redact` 脱敏（2026-10-17）

### 23.1 设计原则：脱敏不能杀死证据

包要发给开发（甚至外部），但包里的价值恰恰是「精确」。所以规则不是「见到像敏感信息的就换」，而是**保留一切排查必需的字段**：

| 处理 | 内容 |
|---|---|
| **替换** | 本机用户路径、设备序列号（留末 4 位）、带端口的 IP、关键词后的 IP、MAC、手机号、邮箱、Bearer token |
| **原样保留** | 包名、`.so` 路径、函数名、buildId、系统版本、时间戳、sha256 |
| **不处理** | 截图位图（无法可靠处理，README 显式声明） |

序列号替换为 `<SERIAL-末4位>` 而非全抹：多台设备仍可区分，又无法反查整机。

### 23.2 实测踩到的过度脱敏（最危险的一类 bug）

第一版用无差别 IPv4 正则，结果把系统版本号一起脱了：

```
softwareVersion: "SGT-AL50 <IP>(SP8C00E120R3P11)"   ← 6.1.0.135 被当成 IP
```

构建版本是崩溃归因的核心指纹，脱掉它等于把包变成废纸。改为**上下文锚定**后：

```
softwareVersion: "SGT-AL50 6.1.0.135(SP8C00E120R3P11)"   ← 完整保留
connectKey: "<SERIAL-4249>"
```

第二版规则：
- `(?<![\w.])(?:d{1,3}\.){3}d{1,3}:\d{2,5}` → `<IP>:端口`（带端口才处理）
- `\b(ip|addr|server|host|gateway|proxy|dns)\b\s*[:=]\s*(IP)` → 保留关键词，只换地址
- **裸 IP 不处理**，README 明确写「正文中孤立出现的裸 IP 未处理，外发前请抽查 logs/」

宁可漏脱几个裸 IP（由人工抽查兜底），也不能误伤版本号——两者的危害不对等。

### 23.3 另一处漏脱：`env/device.json`

它走独立写入路径（`Session.writeText`），没经过 `copyTo` 的脱敏通道，导致序列号原样留在包里。这类「绕过统一出口」的漏洞在代码里是一个模式：**凡是不走 copyTo 的输出点都要单独补**。已统一：`device.json`、`README.md`、`context.json`、`COMPLETENESS.json`、`repro/steps.json` 全部过脱敏。

### 23.4 实测结果

```
完备性    6/7 项齐全
脱敏      serial×2，home×12
残留检查  「xuxingwang」0 个文件，「63R0226113004249」0 个文件
```

新增 7 个回归用例（`RedactTest`），锁住最容易回退的两个边界：
- `versionStringIsNotTreatedAsIp`：`SGT-AL50 6.1.0.135(SP8C00E120R3P11)` 必须原样
- `evidenceKeptIntact`：包名 / so 路径 / 函数名 / buildId 必须原样

### 23.5 遗留

1. 截图未脱敏——若画面里有用户昵称、手机号、聊天内容，包仍可能泄露。可考虑 M3-c 加 `--redact-frames`（区域涂黑，需人工框选）。
2. `logs/app.log` 里的业务字段（如 URL query 里的用户 id）不在规则内，属业务脱敏范畴，工具无法通用。
3. 脱敏后 `sourceSession` 变成 `<HOME>/KMP/ohd/.dsh/device/...`，原机可据此定位，异机不可——这是有意的取舍。

### 23.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接 M2 后建议的脱敏优先级）
- 代码：`/Users/xuxingwang/KMP/ohd`（`BundleCap.kt` 内新增 `Redactor`，`RedactTest.kt` 7 例）
- 实测包：`.dsh/cases/redact-test/`
- 设备：`63R0226113004249`（脱敏后为 `<SERIAL-4249>`）/ SGT-AL50 / API 24

---

## 24. M3-c：`analyze` 事实清单（2026-10-17）

### 24.1 纪律：工具不许给根因

`analyze` 只做三件事：汇总散落的事实、标明哪类证据缺失、指出**下一步补采什么**。根因判断留给开发者或分析 AI——工具一旦给根因，错误结论就会披上「工具说的」这层权威外衣。

实测输出（`full-case` 包，7/7 齐全）：

```
── 崩溃 ──────────────────────────
类型 appfreeze / 原因 THREAD_BLOCK_6S / 模块 com.tencent.compose
进程 10955 / 主线程 Tid:10955, Name:tencent.compose
构建 SGT-AL50 6.1.0.135(SP8C00E120R3P11) / ABI arm64-v8a

── 调用栈（前 5 帧） ─────────────
│ 0 │ ld-musl-aarch64.so.1 │ 0x1d9f3c │ __timedwait_cp+156       │ MISSING_SO+LOG │
│ 1 │ ld-musl-aarch64.so.1 │ 0x1dc014 │ pthread_cond_timedwait…  │ MISSING_SO+LOG │
│ 2 │ libc++.so            │ 0xc4a24  │ std::__h::condition_var… │ LOCAL_SO_NOT_ELF+LOG │
有符号 3/3（本地 0，其余靠日志自带），本地 so 不可用 3

── 指标 ──────────────────────────
采样 4 个 / 2.9s  PSS 411.9 → 411.7 MB  斜率 -0.05 MB/s  走势 震荡  CPU 均值 8.9%

── 下一步方向（不是结论）──────────
1. 3 帧本地 so 不可用：需该构建的 debug so 目录，或先确认 buildId
2. 指标仅 4 个采样：不足以判断趋势，延长 ohd watch 时长
3. appfreeze 盯的是主线程为什么没响应：看栈顶首帧的调用方，而不是栈底框架
```

`--json` 走统一外壳 `ohd/1`，内层 `data.schema = ohd-analysis/1`，含 `crash/stack/metrics/frames/repro/completeness/nextSteps`。

### 24.2 `analyze` 揪出的两个「证据污染」bug

做汇总时才看出：**栈里的信息既会造假，也会丢失**。

| # | 现象 | 性质 | 修法 |
|---|---|---|---|
| 1 | `addr2line` 的 stderr（`LLVMSymbolizer: error reading file: not recognized as a valid object file`）被当成函数名写进栈，状态还是 `ok` | **假信息**：比缺信息更危险，AI 会把报错当符号 | 调用前预检 ELF magic；调用后再拦错误特征，命中即 `LOCAL_SO_NOT_ELF` |
| 2 | 日志里本来自带符号（`__timedwait_cp+156`），`symbols.json` 只存本地符号化结果，导致函数列全空 | **真信息丢失**：系统库符号通常日志就有 | `symbols.json` 增 `logSymbol` 字段；`analyze` 本地为空时回落，状态标 `+LOG` 区分来源 |

第 1 条的根因很典型：`redirectErrorStream(true)` 把 stderr 混进 stdout，而唯一的内容检查是 `contains("??")`——错误信息里没有 `??`，于是被判成功。**凡是把外部工具输出直接当数据用的地方，都要先判「这是不是错误输出」。**

第 2 条的教训：本地符号化失败 ≠ 这帧没有符号。日志格式 `lib.so(func+offset)` 里 `func` 本身就是有价值的信息，丢掉它是纯粹的损失。

### 24.3 顺带修掉的问题

- **表格被 C++ 修饰名撑爆**：`std::__h::condition_variable::__do_timed_wait(std::__h::unique_lock<...>)+104` 两百多字符，整行横穿屏幕。表格列截断到 56 字符，完整值留 `--json`，并打印「函数名已截断」提示。
- **会话重复导致帧翻倍**：`--session a,a` 会把同一会话打两遍。`sessionDirs.distinct()` + `artifacts.distinctBy { it.file }`。

### 24.4 状态语义（写进文档，防止误读）

| 状态 | 含义 |
|---|---|
| `ok` | 本地 so 符号化成功 |
| `MISSING_SO` | 找不到对应 so 文件 |
| `LOCAL_SO_NOT_ELF` | 找到的 so 不是 ELF（如 SDK 里的 linker script），符号化未执行 |
| `NO_SYMBOL` | so 可读但该地址无符号 |
| `BUILD_ID_MISMATCH` | 本地 so 的 buildId 与日志不一致（拿错版本） |
| 后缀 `+LOG` | 本地失败，但日志自带符号可用 |

`analyze` 的「有符号 N/M」把 `+LOG` 计入，同时单列「本地 x」，避免让人以为符号化成功。

### 24.5 遗留

1. `analyze` 无单元测试：核心是文件读取 + 汇总，目前靠实测覆盖（`full-case` 7/7、`redact-test` 缺 crash 两个场景）。`readMetrics` 的统计逻辑值得补一组 csv fixture 测试。
2. 日志未做错误模式挖掘（Top 错误 tag、首个 E 行），只在完备性里判定有无。下一版可加。
3. `repro`（按 steps 重放并与基线比对）与 `report`（生成给开发的 markdown 缺陷单）未做，`analyze` 的 `nextSteps` 已能作为 `report` 输入。

### 24.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接 M3-b 建议的 analyze）
- 代码：`/Users/xuxingwang/KMP/ohd`（新增 `AnalyzeCap.kt`；改 `Symbolizer.kt`、`SymCap.kt`、`BundleCap.kt`、`Main.kt`）
- 测试：45 项全绿（新增 `RedactTest` 7 例属 M3-b）
- 实测包：`.dsh/cases/full-case/`（7/7 齐全，8 产物）、`.dsh/cases/redact-test/`
- 设备：`<SERIAL-4249>` / SGT-AL50 / API 24

---

## 25. M3-d：`dmabuf` DMA buffer 快照与增量（2026-10-17）

### 25.1 数据源

`/proc/<pid>/mm_dmabuf_info`（17 列，设备实测）：

```
Process  pid  fd  size_bytes  ino  exp_pid  exp_task_comm  buf_name  exp_name
can_reclaim  is_reclaim  buf_type  reclaim_info  leak_type  ahost-cpid  ahost-iova  ahost-usage
```

`hidumper --mem <pid>` 的**分类表没有 Dma 行**（只有 GL/Graph/ark heap 等），
只有不带 pid 的**全量表**才有 `Dma` 列与 `DMA(211544 kB)` 汇总行。
所以进程级 dmabuf 只能走 `/proc/<pid>/mm_dmabuf_info`。

### 25.2 四个真实坑（全部有测试锁）

| # | 坑 | 实测证据 | 处理 |
|---|---|---|---|
| 1 | **同一 ino 多行**（dup/多 mmap） | 35 行里 1 行重复，按行求和多算 40 kB（27444 vs 27404 kB） | 按 ino 去重，`duplicateRows` 单独报出 |
| 2 | **buf_name 为空或含空格** → 列错位 | 设计阶段已知；compose 的 xcomponent 就出现过 | 尾部 6 列一律用「从末尾倒数」的相对索引定位（`n-6`/`n-8`/`n-4`），不写死列号 |
| 3 | **纯数字 buf_name** | xcomponent 的 buf_name 就是 `23806`（surface id），同一 id 同时有 `xcomponent` 与 `last_buffer` 两块 | 纯数字标签自动加类型前缀：`xcomponent:23806` / `last_buffer:23806` |
| 4 | **`leak_type` 索引差一位** | 单元测试 `bufNameWithSpaceDoesNotShiftTailColumns` 抓到：`n-3` 取到了 `ahost-cpid` | 改为 `n-4` |

第 4 条值得记：手写列索引时我数错了（把 usage/iova/cpid 记成 3 个而不是 3 个之后的 leak_type 位置），
是**测试**而不是 review 抓到的——这正是给解析器写边界测试的价值。

### 25.3 对账口径（不做「应相等」断言）

`mm_dmabuf_info` 是**本进程映射**的 dmabuf；系统 `DMA(xxx kB)` 含所有进程与内核。
两者不应相等，所以输出只并列、不判定，并明确写「两者不应相等」。
这延续 `bundle`/`analyze` 的纪律：**工具不下它证不了的判断**。

### 25.4 实测

sceneboard（3527）：

```
条目    34 个（原始 35 行，重复映射 1）
总计    26.8 MB
可回收  18 条 / 24.1 MB（can_reclaim=1，属可回收缓存）
系统 DMA 206.6 MB（全系统口径，含其它进程与内核）

#1 WPM_1_ScreenLockThemeManager   17.5 MB  65.5%  1 条  NULL
#2 SaveSnapshot                    3.5 MB  12.9%  1 条  NULL
#3 Adaptive_foreground_com.huawei.hmos.settings  1.0 MB  pixelmap
```

`can_reclaim=1` 的 24.1 MB 属可回收缓存——**不可回收的 2.7 MB 才是真正被占住的**。
这个区分让「dmabuf 涨了」不再等于「泄漏了」。

`diff` 实测（sceneboard vs compose，跨进程对比用于验证增量逻辑）：

```
增长  xcomponent:23806  +13.4 MB  +2
      last_buffer:23806 +12.8 MB  +1
      NULL:23806        +5.6 MB   +3
```

同文件自比 → 「没有超过 4kB 的变化」，符合预期。

### 25.5 命令

```
ohd dmabuf snap --bundle com.ohos.sceneboard --top 10
ohd dmabuf snap --pid 3527 --out /tmp/a.txt
ohd dmabuf diff /tmp/a.txt /tmp/b.txt --min-kb 64
```

`snap` 会把原始文本落盘并登记为会话产物（kind=`dmabuf`），所以可以用 `bundle` 打进证据包。

### 25.6 遗留

1. **xcomponent id → 是否仍存活**未做：设计里要求对照 hilog 的 `destroyRender … remaining=`，需要日志窗口与 dmabuf 快照联动，属 M3-e。
2. `--watch` 未做：`ohd watch` 目前只采 PSS/RSS/CPU，未采 dmabuf 序列。
3. 设备把 `resource://...` 的 buf_name 打码成 `560x232-re*ou*ce*//*20*32*72*3.*ng`（系统隐私保护），
   标签可读性受损但可分组，属设备行为，工具不干预。

### 25.7 本节锚定

- 日期：2026-10-17；触发：用户「继续」（收口原始需求「对比 dma buffer」）
- 代码：`/Users/xuxingwang/KMP/ohd`（新增 `DmabufParser.kt`、`DmabufCap.kt`、`DmabufTest.kt` 7 例）
- fixture：`ohd-core/src/test/resources/fixtures/dmabuf-info.txt`（设备原始输出 36 行）
- 测试：52 项全绿
- 设备：`<SERIAL-4249>` / SGT-AL50 / API 24；sceneboard pid 3527

---

## 26. M3-e：`report` 缺陷单（2026-10-17）

### 26.1 与 `analyze` 的分工

| 命令 | 受众 | 形态 |
|---|---|---|
| `analyze` | 人 / AI 分析 | 宽而全的事实清单 + 补证方向 |
| `report` | issue 系统 / 开发 | 窄而固定的单据：标题、环境、复现、栈、指标、禁用结论、附件 |

两者**共用 `CaseFacts`**——同一份包必须给出同一组数字，否则「工具说的」会自相矛盾。

### 26.2 重构：抽出 `CaseFacts`

原先解析逻辑全在 `AnalyzeCap.run()` 里。加 `report` 时如果复制一份，两处会慢慢分叉
（比如标签归一化、`+LOG` 回落、`missingSo` 口径），最终同一份包给出两套结论。

所以先抽 `ohd/core/analysis/CaseFacts.kt`：
- `load(dir, top)` → `Facts`（crash / stack / metrics / frames / steps / logLines / missing / hints）
- `locate(arg)` → 包目录定位（显式 > `.dsh/cases` 最新）
- `buildStack` / `readMetrics` / `listFrames` / `forbiddenConclusions`

`AnalyzeCap` 随后重写为纯渲染（449 行 → 约 260 行），解析一行都不留。

### 26.3 单据示例

```markdown
# [稳定性][appfreeze] com.tencent.compose 主线程阻塞 6 秒（SGT-AL50 / 6.1.0.135）

> 本单由 `ohd report` 从证据包自动生成，**只含可证伪事实，未判定根因**。
> 证据包：`full-case`（必备件齐全，无禁用结论）

## 摘要
- 进程 com.tencent.compose（pid 10955）在 2026/10/16-18:14:22:073 出现 主线程阻塞 6 秒。
- 构建：`SGT-AL50 6.1.0.135(SP8C00E120R3P11)`，ABI `arm64-v8a`，指纹 `bb788a82...`
- 栈顶：`ld-musl-aarch64.so.1` + `0x1d9f3c` → `__timedwait_cp+156`

## 复现步骤
1. `sleep 200ms` 2. `tree` 3. `waittext 10月` 4. `shot`
回放：`bash repro/run.sh`

## 调用栈
| # | so | 地址 | 函数 | 符号来源 |
| 0 | ld-musl-aarch64.so.1 | 0x1d9f3c | __timedwait_cp+156 | 日志自带 |
| 2 | libc++.so | 0xc4a24 | std::__h::condition_variable::__do_timed_wait(...)+104 | 日志自带 |
```

「符号来源」列是刻意的：本地调试信息 vs 日志自带，决定开发能不能信这行栈。

### 26.4 三处细节（都是实测发现）

1. **标题机型重复**：`softwareVersion` 形如 `SGT-AL50 6.1.0.135(SP8...)`，首段是机型。
   直接 `substringBefore(' ')` 会得到 `SGT-AL50`，与 model 重复成 `（SGT-AL50 / SGT-AL50）`。
   改取第二段并去掉括号 → `6.1.0.135`。
2. **禁用结论为 0 时的悬空引用**：文案写「N 条禁用结论，见文末」，7/7 齐全时 N=0 却仍指向不存在的章节。改为按数量分支。
3. **脱敏包的 path 泄漏**：包是用 `--redact` 生成的，但 `report.md` 里 `证据包目录：/Users/xuxingwang/...` 又把本机用户名带了出去。
   改为：`f.redacted` 为真时，单据内绝对路径一律替换 `user.home` → `<HOME>`。
   这是 `bundle --redact` 之后的**第二个脱敏出口**，说明「脱敏必须覆盖所有写出点」，不能只覆盖主流程。

### 26.5 遗留

1. `repro`（按 steps 重放并与基线比对）未做——目前 `repro/run.sh` 只是调 `ohd replay`，不回比。
2. `soak`（长稳循环 + 自动出包）未做。
3. 未对接 issue 系统（`--ticket` 自动开单）：需要先确定用哪家（工单系统/Jira/GitCode issues），属集成问题而非工具能力。
4. `report` 无单元测试：`render` 是纯函数（Facts → String），可以补一组快照测试，目前靠人工核对两个包（`full-case` 7/7、`redact-test` 缺 crash）。

### 26.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接 dmabuf 后的 report 建议）
- 代码：`/Users/xuxingwang/KMP/ohd`（新增 `analysis/CaseFacts.kt`、`ReportCap.kt`；重写 `AnalyzeCap.kt`）
- 实测：`.dsh/cases/full-case/report.md`
- 测试：52 项全绿（重构后无回归）

---

## 27. M3-f：`repro` / `soak` 与 mac 全量冒烟（2026-10-17）

### 27.1 `repro`：判定复现，而不是比对画面

设计取舍：**不用截图相似度判复现**。界面不同可能来自主题、时间、分辨率，
拿它当标准会制造大量假阳性；而「故障日志里有没有新增同类崩溃」是可证伪的硬证据。

故障名格式：`appfreeze-com.tencent.compose-20020221-20261016181424`（kind-module-uid-timestamp）。
判定 = 重放前后故障列表的**差集** × `kind/module` 匹配：

| 情况 | 判定 | exit |
|---|---|---|
| 新增故障且 kind/module 匹配 | `CRASH_REPRODUCED` | 0 |
| 新增故障但类型/模块不同 | `CRASH_DIFFERENT` | 7 |
| 无新增且包内期望崩溃 | `CRASH_NOT_REPRODUCED` | 7 |
| 包内本就无崩溃 | `NO_CRASH_EXPECTED` | 0（步骤失败则 7） |

崩溃日志不是同步写出的，所以重放后要 `--settle 2s` 等落盘——否则会把「刚写出来」误判成「没复现」。

执行逻辑复用 `ReplayCap.execute`（改为 `internal`），不复制一份——复制就会两处分叉。

### 27.2 `soak`：长稳值守

`soak` 与 `watch` 的分工不是时长，而是**能不能在采样间隙施加操作**：
只在静止状态下测内存，测不出真正的泄漏。所以 `--drive <脚本>` 每轮采样后回放一遍操作。

输出 PSS/CPU/DMA 三联 + csv（`kind=watch-csv`，可直接被 `bundle` 收进包）。
趋势判定只到「持续上升 / 震荡 / 持续下降」，并在窗口过短时主动降级为
`SAMPLE_TOO_SHORT`——5 个样本算出的斜率没有意义。

`MemReport` 只有 `pssKb`，没有 RSS，所以 soak 不采 RSS：需求只关心 PSS，
为凑一个字段多打一次 shell 不划算。

### 27.3 全量冒烟踩出的两个系统性坑

按「用 mac 把坑踩完」做了一轮 18 项冒烟（覆盖全部 19 个能力），失败 3 项，其中 2 个是真 bug：

**坑 A：未知选项被静默吞掉。**
`ohd tree --depth 2` 返回 0 且正常输出——但 `tree` 根本没有 `--depth`（实际叫 `--max`）。
参数写错却「看起来生效了」，是最难自查的一类问题。

修法：`Capability` 增加 `accepts: Set<String>`，CLI 在分发前校验
`(values.keys + flags) - 全局选项 - cap.accepts`，多余即报 `USAGE(2)` 并列出该能力支持的参数。
19 个 cap 的 accepts 由源码扫描 `options.str/bool/int/long("x")` 与 `options["x"]` 自动生成，
避免手写漏项。

**坑 B：数字参数非法值静默回退默认值。**
`Options.int("depth", 3)` 对 `abc` 返回 `3`。
修法：严格解析，非法即 `USAGE(2)` 并提示合法示例。
（`str(...)?.toIntOrNull() ?: N` 的 3 处调用点同步替换为 `int(...)`。）

**坑 C（非 bug）**：`env` 实际叫 `env-dump`（我在冒烟脚本里写错）。
但短名更符合直觉，遂加 `env` 别名，保留 `env-dump`。

冒烟结果（2026-10-17，macOS，SGT-AL50）：

```
01-doctor 0   02-selftest 0  03-env 0      04-tree 0     05-shot 0
06-mem 0      07-cpu 0       08-watch 0    09-tap 0      10-input 0
11-crash 0    12-replay 0    13-dmabuf 0   14-analyze 0  15-report 0
16-repro 7(预期:未复现)      17-badarg 2(预期)           18-nocap 2(预期)
```

### 27.4 mac 已踩实的坑（供跨平台对照）

| 类别 | 坑 | 处理 |
|---|---|---|
| argv | 中文/空格参数必须走 argv 数组，不能拼 shell 串 | ProcessBuilder 数组直传 |
| 编码 | 设备输出 UTF-8 + CRLF | 统一 UTF-8 解码 + CRLF→LF |
| 路径 | `Files.walk` 不跟软链 | `toRealPath()` 后再遍历 |
| 图像 | `snapshot_display -f x.png` 报错，后缀必须 `.jpeg` | 非 jpeg 走 ImageIO 转码 |
| 颜色 | 重定向到文件时仍有 ANSI | 仅 `tty && !NO_COLOR` |
| 退出码 | `cmd \| tail; echo $?` 拿到的是 tail 的码 | 落文件再取 |
| 参数 | 拼错/非法值静默通过 | 本轮修复（accepts + 严格 int） |
| 设备 | `power-shell setmode` 不唤醒屏幕 | `uitest uiInput keyEvent Power` |

### 27.5 跨平台待验清单（mac 无法自证）

1. `dist/ohd.bat` 的 `%*` 透传：含空格/中文的参数是否原样到达 JVM。
2. 控制台代码页：CJK 表格宽度计算（`Out.table` 按 `String.length` 算宽，CJK 占两列）。
3. `build.bat` 的 `gradlew.bat` 调用与 `JAVA_HOME` 探测。
4. `hdc.exe` 路径探测（`Env` 里按平台找 toolchains）。
5. `repro/run.bat` 的 `chcp 65001` 与 CRLF 行尾。
6. 长路径/盘符（Windows `C:\` 与 `Paths.get` 的相对路径登记）。
7. Linux：`hdc` 可执行权限、`~` 展开（`session-root` 默认值）。

### 27.6 遗留

1. `repro` 未比对界面（设计上排除，但可加 `--also-ui` 提示差异，不参与判定）。
2. `soak` 未在真正长时间（小时级）跑过，只在 25s 窗口验证了链路。
3. `bundle` 仍不含 `replay.gif`（需帧合成）。
4. `analyze` 未挖掘日志错误模式（Top error tag / 首条 E 行）。

### 27.7 本节锚定

- 日期：2026-10-17；触发：用户「先完成 mac 的 然后再验证其他平台 用 mac 把坑踩完」
- 代码：`/Users/xuxingwang/KMP/ohd`（新增 `ReproCap.kt`、`SoakCap.kt`、`ReproTest.kt` 5 例；改 `Output.kt`、`Main.kt`、19 个 cap 的 accepts）
- 测试：57 项全绿；冒烟 18 项（16 通过 + 2 预期失败）
- 设备：`<SERIAL-4249>` / SGT-AL50 / API 24

### 27.8 补充：参数一致性（冒烟第三轮才发现）

冒烟把 `cpu` 跑失败，暴露第三个坑：**同一件事在不同能力里参数名不一致**。

| 能力 | 指定进程 | 结果 |
|---|---|---|
| `mem` / `watch` / `soak` | `--pid` 或 `--bundle` | 一致 |
| `cpu` | 只有 `--pid` | **缺 `--bundle`** |

用户记住的是「用包名指定进程」，换到 `cpu` 就报未知参数。
修法：`CpuCap` 改用 `DeviceOps.resolvePid(hdc, pid, bundle)`，与其它能力同构。

这说明 new-feature 的验收不能只测「这个能力对不对」，还要测「换一个能力写一样的话还对不对」。
冒烟脚本因此固定用 `--bundle` 而不是 `--pid`——它顺带是一致性回归。

### 27.9 冒烟脚本沉淀

`dist/smoke.sh`（bash）与 `dist/smoke.bat`（cmd）各一份，覆盖 14–17 项：
- 期望 `0` 为正常
- 期望 `2` 为「拒绝错误输入」（`--dept`、未知能力）
- 期望 `7` 为「未复现」（repro 在别人的机器上大概率未复现）

两脚本都支持 `smoke.sh [包目录]`，传包时追加 analyze/report/repro 三项。
跨平台验证只需：`build.bat` → `smoke.bat <包路径>`，把输出贴回来即可定位差异。

mac 实测：`冒烟通过`（14 项 + 传包时 17 项）。

### 27.10 本节锚定（补）

- 日期：2026-10-17
- 代码：`CpuCap.kt`（加 `--bundle`）、`dist/smoke.sh`、`dist/smoke.bat`
- 验证：`cd ohd/dist && ./smoke.sh` → 冒烟通过

### 27.11 `tree` 参数命名修正（用户指出）

用户问：「`--max` 是我们工具的参数还是系统的？符合直觉的应该是 depth」。

答：**是我们工具的**（`ohd tree` 封装 `dumpLayout` 后的查询参数，系统侧没有这个选项）。
而 `--max` 的真实语义是「最多显示几个节点」（默认 400，防刷屏），**根本不是深度**——
名字里既没说要限制什么，又容易被读成 max depth。这是典型的坏命名。

修正为两个参数：

| 参数 | 语义 | 默认 |
|---|---|---|
| `--depth N` | 只看到第 N 层（`node.depth <= N`），先看整体结构 | 不限 |
| `--limit N` | 最多显示 N 条，防刷屏 | 400 |
| `--max N` | `--limit` 的历史别名，保留兼容 | — |

同时补了截断提示，否则用户不知道为什么少了节点：

```
匹配      110
显示      2（--limit 2 截断，用 --limit 调大）

匹配      7
显示      7，--depth 3 过滤
```

教训：**参数名要表达「限制的是什么」**。`--max`、`--count`、`--num` 这类都不合格；
`--limit`（条数）/`--depth`（层数）/`--top`（排名）各自指向唯一含义。

### 27.12 本节锚定（补）

- 日期：2026-10-17；触发：用户质疑 `--max` 语义
- 代码：`TreeCap.kt`（新增 `--depth`，`--max` → `--limit` 主名）
- 验证：`tree --depth 3` / `tree --limit 2` / `tree --max 5`（兼容）均正常
- `dist/smoke.sh`、`dist/smoke.bat` 同步改用 `--depth 3`

---

## 28. 参数命名审计（2026-10-17）

触发：用户「符合直觉的是 depth」→ 顺势把 19 个能力的参数全部过一遍。

### 28.1 审计方法

写脚本做**双向交叉检查**：把每个 cap 的 `accepts` 集合与其 `usage` 文本里的 `--xxx` 求差集。

- `usage 多`（写了但没声明）→ 通常只剩 `--json`，属全局选项，正常
- `usage 缺`（声明了但没写）→ **用户根本不知道这个参数存在**

一次跑出 7 处 `usage 缺`：

| 能力 | 未文档化的参数 | 影响 |
|---|---|---|
| `bundle` | `--redact` | **最严重**：脱敏是包能不能外发的前提 |
| `log` | `--file` | 指定日志文件而非实时采集 |
| `doctor` | `--device` | 多设备时选一台 |
| `replay` | `--script`、`--wait-timeout` | 脚本路径的另一种给法 / 等待文本超时 |
| `soak` | `--no-dma` | 跳过 dmabuf 采样（省时间） |
| `watch` | `--metrics` | 选采哪些指标（默认 `pss,graph,gl,cpu`） |
| `dmabuf diff` | `--min-kb` | 只看超过阈值的增量 |

全部补进 usage。审计脚本现在是可重跑的（脏数据只剩有意保留的兼容别名）。

### 28.2 改名与不改名

| 参数 | 判定 | 处理 |
|---|---|---|
| `tree --max` | 语义是「节点数上限」，名字极易读成 max depth | → `--limit`（`--max` 兼容，usage 标注旧名） |
| `diff --max-changed` | `max` 没说什么的上限；变量本来就叫 `limit` | → `--allow-ratio`（`--max-changed` 兼容） |
| `env-dump --device` | **我一开始判它是「选字段」，改成了 `--field`——错了** | 回滚：`select(preferred)` 是按序列号选设备，原命名正确 |
| `watch --count` vs `--duration` | 两个终止条件并存，行为没说明 | 不改名，改为输出「停止原因」 |
| `soak --duration/--interval` | 与 `watch` 同名同义 | 保留（一致性优先） |
| `--top`（出现在 5 个能力） | 「排名前 N」 | 保留，含义唯一 |
| `--limit`（tree/crash/sym） | 「最多 N 条」 | 保留，含义唯一 |

### 28.3 `env-dump` 那次改错值得记

我看到 `dm.select(ctx.options.str("device"))`，先入为主判断 `select` 是「选字段」，
把参数改成 `--field`。实测立刻报错：

```
x 指定的设备不在线：model
  -> 当前在线：63R0226113004249
```

`select(preferred)` 的 `preferred` 是序列号（支持前缀匹配）。原命名 `--device` 是对的。

教训：**改名之前必须用一次真实调用验证语义**，光看调用点猜不够。
这次成本很低（编译一次就暴露），但如果是一个静默生效的参数，就会改出一个真 bug。

### 28.4 `watch` 停止原因

`--duration` / `--count` / `--until-stable` 三条件并存，原先只报「样本数 3」，
读者会以为工具没跑够。现在显式输出：

```
样本数      3
停止原因    达到 --count 3

样本数      4
停止原因    达到 --duration 3s
```

### 28.5 验证

- 测试 57 项全绿
- 冒烟 `./dist/smoke.sh` → 冒烟通过
- `tree --depth 3` / `--limit 2` / `--max 5`（兼容）、`env-dump --device 63R0`（前缀匹配）、`watch` 三种停止原因 均实测

### 28.6 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接命名审计提议）
- 代码：7 个 cap 的 usage 补全；`TreeCap`/`DiffCap` 改名；`WatchCap` 加停止原因
- 未改：`env-dump --device`（回滚，原命名正确）

---

## 29. 输出格式一致性审计（2026-10-17）

承接上一节的命名审计，这轮查 `--json` 产物本身：schema 名、信封字段、冗余字段。

### 29.1 两个「同名不同结构」的 schema（真隐患）

| schema | 出现处 | 结构 |
|---|---|---|
| `ohd/1` | `Output.kt` JSON 信封 | `{schema, ok, capability, data, diagnostics, elapsedMs}` |
| `ohd/1` | `Session.kt` manifest.json | `{schema, capability, startedAt, commands, artifacts, env}` |
| `ohd-repro/1` | `ReproCap` 命令输出 | 判定结果（verdict/faults/newFaults） |
| `ohd-repro/1` | `BundleCap` 写 `repro/steps.json` | 步骤列表（source/count/steps） |

同一个名字指向两种结构，消费者没法只靠 schema 判断拿到的是什么。
修正：

- manifest → `ohd-manifest/1`
- steps.json → `ohd-steps/1`
- JSON 信封保持 `ohd/1`（已有外部脚本依赖，不动）

### 29.2 信封加 `toolVersion`

原来 `--json` 产物里没有任何版本信息，包里的数据无法回溯是哪个版本的 ohd 生成的。
现在信封固定带 `toolVersion`：

```json
{"schema":"ohd/1","toolVersion":"0.1.0","ok":true,"capability":"doctor",...}
```

版本常量从 CLI 移到 core（`ohd.core.TOOL_VERSION`），`Main.VERSION` 引用它——
否则两处各写一份，迟早对不上。

### 29.3 删掉只写不读的 `format`

manifest 里同时有 `"schema": "ohd-manifest/1"` 和 `"format": "ohd-manifest/1"`，值完全相同，
且 `format` 全仓库无读取方。删除，保留单一真源。

### 29.4 selftest 抓到了我自己

改完 schema 后冒烟红了一项：

```
│ session  │ FAIL │ manifest 内容异常 │
```

`SelftestCap` 里的断言是 `text.contains("ohd/1")`，新 manifest 写的是 `ohd-manifest/1`，
不匹配。断言跟着改成 `ohd-manifest/1`。

这不是「自检碍事」，而是自检按设计工作：**改了协议就该被自检拦住**，
否则同一个包在旧断言下会假通过。

### 29.5 结论：一致性检查可脚本化

两轮审计都靠脚本而非人工比对：

| 检查 | 脚本方式 |
|---|---|
| 参数声明 vs usage 文本 | `accepts` 集合 △ `usage` 里的 `--xxx` |
| schema 唯一性 | `grep 'put("schema"'` 后按值分组，>1 处即人工确认 |

第二个检查值得固化成 selftest 项：**同名 schema 出现在两个文件里就该报**。
本轮靠 grep 发现，属一次性手工动作。

### 29.6 遗留

1. **时间格式混用**：manifest 用 ISO-8601 字符串（`2026-10-17T15:17:42.194273+08:00`），
   JSON 产物用 `elapsedMs` 数字，metrics csv 用 `epoch_ms`。三种表示并存，
   尚未统一（各自都有合理性，需先定规则再改）。
2. 字段命名已一致（camelCase：`pssKb`/`totalBytes`/`dmaBytes`/`countDelta`），
   csv 内部用 snake_case（`pss_kb`）属格式惯例，不算不一致。
3. 未做「schema 版本升级策略」文档：目前全部 `/1`，没有消费方兼容约定。

### 29.7 本节锚定

- 日期：2026-10-17；触发：用户「继续」（承接输出一致性提议）
- 代码：`Output.kt`（TOOL_VERSION、信封字段）、`Session.kt`（schema、删 format）、
  `BundleCap.kt`（steps schema）、`SelftestCap.kt`（断言）、`Main.kt`（复用常量）
- 验证：`doctor --json` 含 toolVersion；新 manifest 键集无 format；`selftest` exit 0；冒烟通过；测试 57 项全绿

---

## 30. 时间格式统一为 ISO-8601（2026-10-17）

用户决定：「都用 ISO 给人看」。

### 30.1 规则

| 类型 | 格式 | 例 |
|---|---|---|
| **时间点**（timestamp） | ISO-8601 带时区偏移 | `2026-10-17T16:45:44+08:00` |
| **时长**（duration） | 毫秒数字 | `elapsedMs: 1426` |

时长不跟着改：ISO 8601 的 duration（`PT1.426S`）人读起来更糟，而 `elapsedMs` 名字已表明单位。

### 30.2 改动点

| 位置 | 原 | 现 |
|---|---|---|
| `watch` csv | `epoch_ms` 列 | `time` 列（ISO） |
| `watch` json | `epochMs` | `time` |
| `soak` csv | `epoch_ms` 列 | `time` 列 |
| `soak` json `series[]` | `ms` | `time` |
| `env-dump` json | `deviceTime`（值 `Sat Oct 17 16:45:23 CST 2026`） | `deviceTime`（值 ISO） |
| manifest / bundle / log | 已是 ISO | 不变 |

`isoTime(epochMs)` 放在 `ohd.core`（与 `TOOL_VERSION` 同处），避免每个 cap 各写一份格式化。

### 30.3 读取端必须双格式兼容

`CaseFacts.readMetrics` 原来只认 `epoch_ms`。换成 ISO 后，**已生成的证据包会突然「分析不出趋势」**。
所以读取改为：优先 `time`（ISO 解析）→ 回退 `epoch_ms` → 都没有则按样本数估算。

补了 4 个测试锁死这条：`readsIsoTimeColumn` / `readsLegacyEpochColumn` /
`withoutTimeColumnSpanFallsBackToSampleCount` / `missingPssColumnReturnsNull`。
端到端也验证过：新 watch 会话 bundle 后 `analyze` 正确算出 `3 个 / 1.8s`。

**格式变更必须同时改读取端，否则工具自己生成的包自己读不了。**

### 30.4 又一次「看名字猜语义」翻车

我看到 `put("deviceTime", timezone)` 且变量名叫 `timezone`，判定「字段名错、存的是时区」，
改成 `timezone`。实测值立刻暴露真相：

```
timezone: Sat Oct 17 16:45:23 CST 2026
```

这是 `date` 的默认输出，即**设备当前时间**——原字段名 `deviceTime` 完全正确，
错的是变量名 `timezone`。

修正方向反过来：字段名恢复 `deviceTime`，变量名改 `deviceTime`，
并把值改成 ISO（优先 `date +%Y-%m-%dT%H:%M:%S%z`，设备支持；不支持则回退），
`+0800` → `+08:00` 转成 ISO-8601 扩展格式。

这是本轮第二次犯同类错误（第一次是 `env-dump --device`）。
共同点：**只看变量名/调用点就下结论，没有先跑一次看实际值**。
以后动命名前先执行一次命令看输出，成本比回滚低得多。

### 30.5 时间格式现状

所有时间点已统一 ISO：

```
manifest.startedAt       2026-10-17T15:17:42.194273+08:00
env.deviceTime           2026-10-17T16:45:44+08:00
bundle.generatedAt       2026-10-17T16:30:00+08:00
watch csv time 列        2026-10-17T16:43:32.454+08:00
```

例外只剩 `crash.timestamp`（如 `2026/10/16-18:14:22:073`）——那是 **faultlog 原文**，
属证据不可改写；`analyze`/`report` 展示时保持原样，不转换。

### 30.6 本节锚定

- 日期：2026-10-17；触发：用户「都用ISO给人看」
- 代码：`Output.kt`（`isoTime`）、`WatchCap`、`SoakCap`、`EnvDumpCap`、`analysis/CaseFacts.kt`
- 测试：61 项全绿（新增 `MetricsCsvTest` 4 例）；冒烟通过

---

## 31. `monkey` 随机事件注入（2026-10-17）

### 31.1 与普通 monkey 的差别

普通 monkey 的产出是「崩了」。但开发拿到「崩了」仍然要自己找复现路径。
这里的设计目标对齐项目总目标（测试产物直接交给开发/AI）：

**每注入一个事件，同步记一行 `ohd replay` 能直接执行的脚本。**

崩溃时一次性落盘：
- `monkey-*.ohd` —— 完整事件序列（可直接回放）
- `crash-shot.jpeg` —— 崩溃瞬间界面
- `crash-tree.json` —— 崩溃瞬间节点树
- `monkey-samples-*.csv` —— 期间 PSS/DMA 采样
- 故障名（`appfreeze-module-uid-ts`）

所以输出不是「崩了」，而是「这样点就会崩，脚本在这」。

### 31.2 安全约束（不设的话会自己把自己玩死）

| 约束 | 原因 |
|---|---|
| 不注入 Power 键 | 会锁屏，后续所有事件打在锁屏界面上，等于白跑 |
| Back 键限频（10 步冷却） | 否则一路退回桌面，覆盖不到应用内部 |
| 只点 `clickable && enabled && visible` 且面积 > 0 | 天然避开状态栏/导航栏，也不会点到不可见元素 |
| 点击坐标 ±12px 抖动 | 真人不会点在正中心；也避免反复点同一像素导致同一路径 |

### 31.3 性能：layout 必须缓存

第一版每步都 `captureLayout`，实测 **2.8s/步**（25s 只注入 9 步），覆盖率太低。
原因是 `captureLayout` + 每 5 步一次的故障检查（`hidumper -s 1201`）都很重。

改为 layout 缓存 3 步（靠坐标抖动保持多样性）后：**1.5s/步**，约 40 步/分钟。

更快的做法（未做）：`uitest uiInput` 直接发坐标不进 layout，只在需要新目标时抓一次。

### 31.4 故障判定复用 `Faults`

新增 `ohd/core/Faults.kt`，把故障列表读取/解析从 `ReproCap` 抽出，`repro` 与 `monkey` 共用。
理由与之前一致：同一个判定在两处各写一遍，迟早分叉出不同结果。

### 31.5 后台长稳实测

```
ohd monkey --bundle com.ohos.sceneboard --duration 40m \
           --interval 500ms --sample-every 20 --check-every 5
```

选 sceneboard 作为采样目标的原因：它是桌面进程，任何时刻都在，不会因为 monkey 启动/退出应用而消失，
PSS/DMA 曲线连续可比。monkey 期间会打开/关闭各种应用——这本身就在给桌面施加真实负载。

### 31.6 本节锚定

- 日期：2026-10-17；触发：用户「挂个后台实测 后台得让自动化冒烟跑起来吧？你有类似monkey的能力吗」
- 代码：`caps/MonkeyCap.kt`（新增）、`core/Faults.kt`（新增，repro 同改）
- 冒烟脚本尚未加入 monkey（它耗时长，属长稳项而非冒烟项）

---

## 32. PSS 走势判定重写：锯齿 ≠ 泄漏（2026-10-17）

### 32.1 问题来自一次真实长稳

40 分钟 monkey 实测：1438 步、71 个采样、PSS 425.6 → 456.3 MB，
**91%（64/70）的采样在上升**，整体斜率为正。

原判定逻辑：

```kotlin
growing >= (n-1)*0.8 && slope > 0 -> "持续上升"   // → 触发 PSS_GROWTH 告警
```

按这个规则必然报「持续上升」。但把窗口切五段看：

```
段1: 425.6 → 437.8  (谷418.0)
段2: 440.4 → 469.0  (谷439.2)
段3: 422.3 → 450.8  (谷422.3)   ← 回落
段4: 455.0 → 494.6  (谷455.0)
段5: 426.4 → 456.3  (谷426.4)   ← 又回落
```

**段内涨、段间回落**：GC/缓存锯齿，不是单调泄漏。工具给出了误导性结论——
这比覆盖不足危险得多。

### 32.2 新判定：看分段谷值 + 最大回撤

新增 `analysis/Trend.kt`，`soak` 与 `watch` 共用：

| 判据 | 含义 |
|---|---|
| `maxDrawdown`（从运行高点的最大回落） | 有回撤 = 回收机制在工作 |
| `valleys`（分段最小值） | 谷值抬升才是基线抬升 |
| `riseRatio` | 上升步占比（辅助） |

判定顺序**回撤优先**：

```
riseRatio>=0.6 && maxDrawdown>peak*5%  → 锯齿（已回收）
slope>0 && riseRatio>=0.8              → 持续上升
valleyTrendUp && baselineDrift>阈值    → 基线抬升
slope<0 && riseRatio<=0.2              → 持续下降
else                                    → 震荡
```

顺序踩过一次坑：第一版把「基线抬升」放前面，实测数据回撤 68 MB（13.8%）
却因基线涨了 15 MB 被判成泄漏。**大幅回撤优先**才符合物理直觉。

### 32.3 验证方式：不编译也能验逻辑

按项目规则不主动编译，改用 Python 复现同一套算法喂数据：

| 输入 | 判定 |
|---|---|
| 40 分钟实测（71 点，91% 上升） | 锯齿（已回收） |
| 单调上升 | 持续上升 |
| 水平抖动 | 震荡 |
| 持续下降 | 持续下降 |
| 缓慢真泄漏（涨+小幅回撤） | 持续上升 |

五个用例全对后才编译，一次过。

### 32.4 输出变化

```
样本      13 个 / 36.1s
PSS       420.4 MB → 419.0 MB（峰 420.4 / 谷 419.0）
走势      持续下降（0/12 次上升，基线 -1.4 MB，最大回撤 1.4 MB）
```

锯齿时明确说「属正常分配/回收，不是泄漏」；基线抬升才提示需更长窗口。
**依然不下泄漏结论**，只给形状与数值。

## 33. monkey 探索策略修正（2026-10-17）

### 33.1 40 分钟实测暴露的问题

- 「可点」恒为 6：桌面只有 6 个可点节点，随机挑永远走不出新路径
- 事件集中在几个坐标打转（`tap 63x 9xx`、`tap 15x 25xx`）
- 连点几十次 y≈70-110：一路点进通知中心

### 33.2 三项改动

| 改动 | 依据 |
|---|---|
| 按安全区过滤可点节点 | 根节点常是全屏 `[0,0]`，top 取到 0 等于没过滤；改用 `maxOf(rootTop, screenH*5/100)` 兜底（1280x2832 → 141px，盖住 137 状态栏） |
| 15% 盲点安全区内随机坐标 | 可点节点少时只靠节点无法发现新界面 |
| 滑动 20%、Back 降到 4% | 滑动能翻页/滚动，Back 只会退回桌面 |

实测效果：可点从 6 降到 4（状态栏上的 2 个被过滤），`y<141` 的点击消失。

### 33.3 本节锚定

- 日期：2026-10-17；触发：40 分钟后台实测后的结果分析
- 代码：`analysis/Trend.kt`（新增）、`SoakCap`、`WatchCap`、`MonkeyCap`
- 验证：Python 逻辑验证 5 用例 → 编译一次通过 → 61 测试全绿 → 冒烟通过 → soak/monkey 实测

---

## 34. monkey 执行效率优化（2026-10-17）

触发：用户提出「每次 pid 不变的话可以记录下所有节点坐标，后续操作就可以减少时间」。

### 34.1 先量化，别凭感觉优化

实测（SGT-AL50，1280x2832）：

| 操作 | 耗时 | 说明 |
|---|---|---|
| `hdc shell echo hi` | **80 ms** | 纯往返基线 |
| 批量 3 条 echo | 82 ms | **往返几乎免费** |
| `uitest uiInput click` | **391 ms** | 设备端注入 |
| `uitest uiInput keyEvent` | 420 ms | 设备端注入 |
| `uitest uiInput swipe` | **929–7415 ms** | 波动极大，见 34.3 |
| `hidumper --mem` | 727 ms | 采样/故障检查 |
| `uitest dumpLayout`（82 KB） | **1106 ms** | **monkey 每步最大开销** |

两个反直觉的结论：

1. **hdc 往返不是瓶颈**（80ms）。批量下发省不到什么，所以"合并命令"这条路收益很低。
2. **dumpLayout 是固定成本**：试过 `-b <bundle>`（只目标窗口）1204ms、`-i`（不合并）1193ms、
   `-b + -i` 直接 10.8 秒失败——**设备端没有轻量模式**，1.1 秒就是遍历 UI 树的代价。

所以用户方向对，但省的不是"每次发命令"，而是"少抓 layout"。

### 34.2 自适应 TTL（比固定 TTL 两头都占）

固定 TTL 的困境：小了浪费（每步 1.1s），大了点废坐标（界面已跳转）。

改为按**界面指纹**自适应：

```
每次刷新算指纹 = 可点数 × 1000003 + Σ(centerX + centerY)
指纹未变 → effectiveTtl = min(effectiveTtl × 2, base × 4)   // 界面稳定，缓存越用越久
指纹变化 → effectiveTtl = base                              // 一跳转立刻回到保守值
```

指纹只看「有几个可点、大致在哪」，与文本内容无关——避免文本刷新导致过度重抓。

实测步频：

| 配置 | 步/分 | 相对 |
|---|---|---|
| 固定 TTL=3（原始） | 41.0 | 基线 |
| 固定 TTL=5 | 46.7 | +14% |
| 固定 TTL=10 | 54.9 | +34% |
| **自适应 base=5** | **53.5** | **+30%** |

自适应拿到接近 TTL=10 的速度，但界面一变就退回 5，不会像固定 TTL=10 那样盲点 10 步旧坐标。

顺带把 `--check-every` 默认从 5 调到 8（每次故障检查 727ms，摊薄后省 ~0.1s/步）。

### 34.3 swipe 的测量不可靠

同一命令测四次：时长 800 → 2015ms、200 → 7415ms、50 → 2614ms、2000 → 929ms。

原因不是参数语义，而是**滑动会改变界面状态**：滑到边界后再滑无效果（快），
滑进新页面触发加载（慢）。基准自身污染了被测对象，这组数据不能用来调参。

结论：swipe 保持 800，不据此优化。

### 34.4 剩余瓶颈与不做的优化

优化后 1.12 秒/步，拆开是：

```
interval 400ms（人为下限）+ click 293ms（75%×391）+ swipe 300ms（20%×1500）
+ layout 55ms（TTL≈20 摊薄）+ 其他 70ms
```

**click/swipe 是设备端固定成本，无法优化**。剩下的可行项：

| 方案 | 收益 | 决定 |
|---|---|---|
| 批量注入 3 个事件（一次 shell） | ~5%（975ms vs 3×391ms，再乘 click 占比） | 不做：复杂度高，收益低 |
| interval 降到 100ms | ~25% | 交给用户调，默认保留人类节奏 |
| 异步采样（hidumper 727ms 当前串行） | ~5%（按采样步摊薄） | 不做：线程复杂度不值 |
| 常驻 hdc shell | ~7%（省 80ms/往返） | 不做：管道分帧易错，收益低 |

### 34.5 一个前提：replay 本来就不慢

效率问题只存在于需要「决策」的场景（monkey/`tap --by-text`）。
`replay` 执行的是脚本里的确定坐标，**不抓 layout**，每步只有 391ms 的注入成本。
所以「回放操作」这条路本来就是快的，不需要缓存。

### 34.6 本节锚定

- 日期：2026-10-17；触发：用户提出缓存节点坐标减少开销
- 代码：`MonkeyCap.kt`（自适应 TTL、`--layout-ttl`、`--check-every` 8、步频输出）
- 验证：61 测试全绿、冒烟通过；步频实测 41.0 → 53.5 步/分

### 34.7 压测模式（用户：「不像人类操作没关系 压测省时间」）

去掉拟人节奏后重新测，结论和直觉不同：

| 配置 | 步/分 | 秒/步 | 相对 |
|---|---|---|---|
| 最初（TTL=3, interval=400） | 41.0 | 1.46 | 基线 |
| 自适应 TTL（interval=400） | 53.5 | 1.12 | +30% |
| **interval=0** | **105.9** | 0.57 | **+158%** |
| interval=0 + batch=10 | 110.0 | 0.55 | +168% |
| interval=0 + batch=20 | 113.2 | 0.53 | +176% |

**interval 归零的收益（2.1 倍）远大于批量下发（+7%）**。
批量只省「下发启动」那部分（364→304ms/click），设备端每次点击仍有 ~300ms 固定处理成本。

所以压测推荐：

```
ohd monkey --bundle <b> --interval 0 --batch 10 --check-every 30 --sample-every 60
```

`--check-every`/`--sample-every` 同步放大——压测时采样和故障检查的 727ms 会显著拖慢步频。

综合：**41 → 113 步/分（2.8 倍）**，40 分钟可注入约 4500 个事件（原 1438）。

### 34.8 batch 的代价

一批 N 个事件共用**同一份 layout**，批内界面若已跳转，后续事件打在旧坐标上。
对压测可接受（本来就是随机探索），对需要精确回放的场景不要用 batch。

崩溃检测也只在批末进行，理论上可能漏掉「批内崩溃后继续点」的几秒——
但故障列表是差集比对，崩溃记录不会丢，只会多注入几个事件。

### 34.9 本节锚定（补）

- 日期：2026-10-17；触发：用户「不像人类操作没关系 压测省时间」
- 代码：`MonkeyCap.kt`（`--batch`、批量 shell 下发）
- 验证：61 测试全绿、冒烟通过；步频实测见 34.7

---

## 35. 压测实测暴露的两个缺陷（2026-10-17）

25 分钟压测（interval=0, batch=10）结果：**2999 步、119.4 步/分、无崩溃**，
对照首轮 1438 步，速度 2.1 倍、总量 2.1 倍。但暴露两个问题，都比「慢」严重。

### 35.1 停滞：monkey 卡在同一界面 2000+ 步

`可点 3` 从第 663 步一直持续到 2999 步，坐标集中在 `tap 15x 25xx`、`tap 63x 9xx` 几个点上打转。

讽刺的是**自适应 TTL 加剧了它**：指纹不变 → TTL 拉长到 20 步 → 更少刷新 → 更看不到界面已经"死"在那里。

修复：加「停滞脱困」。

```
界面指纹连续 stagnate 步不变（默认 60）
  → 主动注入 Back；连续 3 次无效改注入 Home（可能是应用内页堆栈太深）
  → 脱困事件同样记入可回放脚本
  → 重置 lastChangeStep，避免连续脱困刷屏
```

实测（`--stagnate 20` 强制触发）：6 次脱困，Back/Home 交替正常工作。

### 35.2 采样静默失败：4 个采样点被当成结论

`--sample-every 60` 在 2999 步里应该采 50 次，实际只有 4 个。
原因是高频注入下 `hidumper --mem`（727ms）超时，而失败被 `runCatching` 静默吞掉——
**工具拿着 4 个点输出「PSS 472.0 → 448.9 MB」，等于用噪声下结论**。

修复：

- 采样失败计数 `sampleFail`，输出显式标注「采样 9 个（失败 8 次）」
- JSON 增加 `sampleFail` / `escapes` 字段
- 压测模式建议把 `--sample-every` 放大

原则：**宁可报「采样失败 46 次」，也不能拿 4 个点假装给了结论。**

### 35.3 顺带修掉的一个连带 bug

脱困时 `step += 1`（脱困也是真实注入），把 `step % sampleEvery == 0` 的对齐打乱了——
之后 step 不再是 10 的倍数，采样条件永远不成立。

改为「距上次的步数」判断：

```kotlin
if (step - lastSampleStep >= sampleEvery) { lastSampleStep = step; ... }
if (step - lastCheckStep  >= checkEvery)  { lastCheckStep  = step; ... }
```

顺带发现 `--batch` 模式下 `step` 每批 +10，所以 `--sample-every 1` 实际是「每批一次」
而不是「每步一次」——这是批量模式的固有语义，暂不改，但要知道。

### 35.4 本节锚定

- 日期：2026-10-17；触发：25 分钟压测结果
- 代码：`MonkeyCap.kt`（`--stagnate`、脱困、`sampleFail`、距离判断）
- 验证：61 测试全绿、冒烟通过；`--stagnate 20` 实测 6 次脱困；新 25 分钟压测已挂

---

## 36. 修复后的 25 分钟压测对照（2026-10-17）

### 36.1 三轮数据

| 轮次 | 时长 | 步数 | 步频 | 采样 | 判定 |
|---|---|---|---|---|---|
| 1（原始） | 40m | 1438 | 36 步/分 | 71 | 锯齿（人工分段分析得出） |
| 2（压测，无脱困/无失败计数） | 25m | 2999 | 119 步/分 | **4** | 无法判断（数据不够） |
| 3（脱困+采样修复） | 25m | 2446 | 98 步/分 | **109** | 震荡 |

### 36.2 采样修复的效果

第 2 轮 4 个采样、第 3 轮 109 个（同样 25 分钟，`--sample-every` 从 60 放到 20）。
第 1 轮的 4 个采样撑不起任何结论，第 3 轮 109 个才能看出形状。

代价：步频从 119 降到 98（每次采样 727ms），**采样密度与注入速度是直接竞争关系**。
压测时 `--sample-every 20~30` 是合理折中。

### 36.3 脱困未触发，但探索质量明显改善

第 3 轮 `脱困 未触发`。对比可点节点数：

- 第 2 轮：`可点 3` 从第 663 步卡到 2999 步（2000+ 步不动）
- 第 3 轮：可点数在 **3~42** 之间剧烈波动（27 反复出现 = 真正进入了应用内界面）

说明第 2 轮的停滞有运气成分（卡在某个桌面状态），第 3 轮抽到了会主动跳转的界面。
脱困机制没被触发不代表它没用——它是兜底，不是常规路径。

### 36.4 PSS 判定：震荡

```
109 个采样 | 峰 495.3 谷 424.3 MB | 上升 54%
分段谷值: [424.3, 442.0, 440.0, 438.0, 444.4]   ← 不单调（442→440→438）
基线漂移 +20.1 MB | 最大回撤 55.3 MB (11.2%)
→ 震荡
```

前半 424.3 → 477.2（涨），后半 477.2 → 468.5（跌），整体在 424–495 MB 区间波动。

上升步占比 54%，接近抛硬币——**这才是「震荡」的正确依据**，而不是首末差（+44 MB 看起来像增长）。

新判定逻辑在真实长数据上表现正确：谷值不单调 + 上升 54% → 震荡，未误判为泄漏。

### 36.5 本节锚定

- 日期：2026-10-17；触发：第 3 轮 25 分钟压测结果
- 代码：无改动（本轮只验证 §35 的修复）
- 数据：`.dsh/device/20261017-200803-monkey/250-monkey-samples.csv`（109 行）

---

## 37. bundle 补上 replay.gif（2026-10-17）

### 37.1 为什么用 JDK 而非 ffmpeg

环境里没有 ffmpeg，也没有 PIL/numpy。但 GIF 编码不需要外部依赖——
JDK 自带 `com.sun.imageio.plugins.gif.GIFImageWriter`，通过 `ImageIO.getImageWritersByFormatName("gif")` 就能用。

新增 `ohd-image/GifWriter.kt`（约 100 行），只做三件事：缩放、写帧序列、设循环元数据。

关键细节：**没有 NETSCAPE2.0 应用扩展的 GIF 只播一次**，必须手动塞 `ApplicationExtension`：

```kotlin
IIOMetadataNode("ApplicationExtension").apply {
    setAttribute("applicationID", "NETSCAPE")
    setAttribute("authenticationCode", "2.0")
    userObject = byteArrayOf(0x1, 0x0, 0x0)   // loop=0 → 无限
}
```

延时在 `GraphicControlExtension.delayTime`，单位是百分之一秒（`delayMs / 10`）。

### 37.2 体积控制

1280x2832 的原帧几十张就是几十 MB，所以三重重约束：

| 手段 | 值 | 效果 |
|---|---|---|
| 统一缩放到 400 宽 | 400x885 | 单帧从 ~1.5 MB 降到 ~40 KB |
| 帧数上限 | 300 | 防止长脚本爆体积 |
| 超限时均匀抽帧 | 首尾必保留 | 保持整体节奏，不砍掉开头结尾 |

实测 3 帧 → 112 KB；bundle 里含 3 张截图合成 → 191 KB。

### 37.3 两条数据来源

`replay/replay.gif` 有两个来源，优先级从高到低：

1. **`replay --record` 已生成**（每步截一张，压成 gif 并注册为 `replay-gif` 产物）→ 直接拷进包
2. **会话里有 >=2 张 `shot`** → bundle 时现场合成

monkey 崩溃时只有 1 张现场图，够不上动画，所以这份基本来自 replay 的序列。

`--record` 每步多 ~500ms，只适合脚本量不大的场景（回放脚本通常几十步）。

### 37.4 踩的坑：manifest 的 kind 反查

第一版把生成的 gif 直接 `placed.add(rel to path)`，结果 bundle 崩：

```
x 未预期异常：NoSuchElementException: Collection contains no element matching the predicate.
```

因为 manifest 生成时用 `placed` 反查源 artifact：

```kotlin
put("kind", artifacts.first { it.file == src }.kind)   // gif 是打包时新生成的，没有源 artifact
```

修复：`placed` 的元素从 `Pair<String, Path>` 升级为 `Triple<String, Path, String?>`，
第三项是显式 kind，新生成的文件自己声明（`"replay-gif"`），反查时用 `firstOrNull` 兜底。
`buildReadme` 的签名和 461 行的解构要同步改——漏一个就编译不过。

### 37.5 脱敏与二进制

gif 是二进制，脱敏只处理文本；`--redact` 下 gif 原样保留。实测 `bundle --redact` 不崩、gif 完整。
如果将来要把截图里的人名/号码也脱敏，那得在合成 gif **之前**逐帧处理图像，不在本次范围。

### 37.6 本节锚定

- 日期：2026-10-17；触发：用户指出 bundle 缺 replay.gif
- 代码：`ohd-image/GifWriter.kt`（新增）、`ReplayCap.kt`（`--record`）、`BundleCap.kt`（合成 + Triple）
- 验证：61 测试全绿、冒烟通过；replay --record → bundle 端到端出 142 KB gif；
  无 --record 时由 3 张 shot 合成 191 KB gif；manifest kind=`replay-gif`；`--redact` 不崩

---

## 38. 跨平台验收清单（2026-10-17）

产物：`ohd/CROSS-PLATFORM.md`（204 行，9 节），给 Windows/Linux 同事直接执行。

### 38.1 审计结论：抽象已就位，缺的是「真机验证」

动手写清单前先审计了代码，平台差异确实已收敛：

| 机制 | 位置 | 作用 |
|---|---|---|
| `Platform.isWindows/exeSuffix` | `Env.kt` | 全仓唯一出现 `os.name` 的地方 |
| `Platform.devicePath()` | `Env.kt` | 设备路径一律正斜杠（Windows 对反斜杠敏感） |
| `Platform.displayWidth()` | `Env.kt` | CJK 记 2 列，表格对齐 |
| `Hdc.normalizeNewlines()` | `Hdc.kt` | `\r\n` → `\n`（Windows 上 hdc 输出带 `\r`） |
| `Env.discoverHdc()` | `Env.kt` | 参数 → 环境变量 → 平台 SDK → PATH |
| `dist/*.bat` | `dist/` | `%*` 转发；`smoke.bat` 已含 `chcp 65001` |

所以清单不是「验有没有做」，而是「验这些抽象在真机上是否成立」。

### 38.2 清单结构

1. 前提（JDK 17 / gradle / 设备 / 终端）
2. 五分钟快速验收（build + smoke）
3. env 自检（7 项断言，专打 CRLF 与中文宽度）
4. 真机连通（doctor / env / tree / shot）
5. 参数与路径（**空格+中文参数、盘符空格、反斜杠**）
6. 打包产物（含 GIF）
7. 脱敏
8. Linux 额外项（发现逻辑、`LANG=C` 的影响）
9. 回报模板 + 已知限制

### 38.3 写清单时抓到的错误

初稿写了 `ohd devices`——**这个命令根本不存在**。
实际能力列表里设备信息走 `ohd env`（21 个能力：doctor env env-dump tree shot mem cpu log watch diff tap input replay crash sym bundle analyze dmabuf report repro soak monkey）。

教训同 §28/§30：**不要凭印象写命令**。改为用脚本把清单里所有 `ohd <cmd>` 反查能力列表，才发现 `devices` 是凭空的、`selftest` 因注册格式不同（`SelftestCap(listOf(...))`）被正则漏判——后者实测存在。

### 38.4 已知限制（写进清单，避免误报）

| 限制 | 处理 |
|---|---|
| 老 conhost 的 Unicode 边框 | `--ascii`（实测 ASCII 边框对齐正常） |
| `LANG=C` 下中文输出 `?` | locale 问题，不强行改编码 |
| GIF 在精简 JRE 失败 | 缺 `java.desktop`，装完整 JDK |
| 截图人脸/号码未脱敏 | 仅文本脱敏，图像脱敏不在范围 |

### 38.5 本节锚定

- 日期：2026-10-17；触发：用户同意整理跨平台验收清单
- 代码：无改动（只新增文档 `ohd/CROSS-PLATFORM.md`）
- 实测：`tree --ascii` 对齐正常、`shot --out` 落 1.3 MB PNG、`env` 输出设备信息、`selftest` 全通过
- **未验证项**（需 Windows/Linux 机器）：`.bat` 的 `%*` 转发、盘符空格路径、conhost 编码、gradle 发现

### 38.6 塞进 bundle README 的方式（追加，2026-10-17）

需求是「把跨平台清单塞进 bundle 的 README」。但直接从 CROSS-PLATFORM.md 搬会犯错——
**bundle 的读者是看缺陷包的开发，不是跑工具的同事**，工具自身的平台细节塞多了会淹没缺陷信息。

所以只补两处真正对读者有用的：

```markdown
## 生成环境
- 工具：ohd 0.1.0（macOS / aarch64）
- 生成时间：2026-10-17T20:56:24.923069+08:00
- 平台差异（路径、编码、命令名）见 ohd 仓库 `CROSS-PLATFORM.md`。
```

加在「怎么用」之后。作用：出问题时要能回答「这包是谁在哪生成的」——
Windows 上生成的包带 CRLF、路径写 `\`，有这行就不用猜。

另一处是录像说明，只在真有 gif 时才出现：

```markdown
4. 录像：`replay/replay.gif` 是操作过程动画，图片查看器打开即循环播放。
```

条件判断用 `placed.any { rel.endsWith("replay.gif") }`，没有 gif 的包不会多出这条。

**没有**把「已知限制」表搬进 README——那些是工具的使用约束，放 CROSS-PLATFORM.md 一处即可，
README 里留一行指向它。

验证：61 测试全绿、冒烟通过；生成的 README 里「生成环境」三行与「录像」一条都正确。

---

## 39. 沉淀成 skill：让别的模型会用 ohd（2026-10-17）

产物：`~/.agents/skills/ohd-device-verify/SKILL.md`，并登记进 `~/.agents/skills/INDEX.md`。

### 39.1 为什么不写成长文档

skill 的读者是**别的模型**，不是人。规范要求短、可执行、带触发边界。所以结构是：

```
铁律（只产证据不下根因）
  → 决策树（按「要做什么」选命令，13 行表格）
  → 缺陷包闭环（repro → bundle → 开发侧三份文件）
  → 压测提速（实测参数与倍数）
  → 7 个坑（全部来自实测）
  → 失败排查顺序
  → 平台差异指向 CROSS-PLATFORM.md
```

关键取舍：**命令表按「要解决的问题」组织，不按模块组织**。模型拿到的是症状（"要确认界面"），
不是模块名（"shot 模块"），所以表格左侧写「要做什么」。

### 39.2 触发边界怎么写

description 里必须同时给「是什么」和「WHEN」：

```
用 ohd 在 OHOS 真机上做验证：节点树/点按滑动/截图定位/日志/崩溃/内存 PSS/CPU/DMA/
UI 截图对比/回放/压测，并把结果打成可交给开发的缺陷包。
WHEN 需要操作真机确认现象、复现缺陷、抓崩溃栈、看内存或 CPU、对比改动前后界面、
生成「开发拿到就能动手」的证据包、或对 UI 做长时间 monkey 压测。
```

后半段是决定会不会被自动命中的关键——写具体场景，不写「用于真机验证」这种废话。

### 39.3 写 skill 时又踩了同一个坑

初稿的「点按/滑动」只写了 `input swipe x1 y1 x2 y2`。实际 `ohd input` 有四个子命令：

```
ohd input text <x> <y> <文本>      ohd input text <文本>（已聚焦处）
ohd input swipe <x1> <y1> <x2> <y2> [速度]
ohd input drag  <x1> <y1> <x2> <y2> [速度]
ohd input key   <Back|Home|Power|键值>
```

漏了 `text` 和 `key`——**输入文字和按键是高频操作，漏掉等于 skill 残废一半**。
这和 §38.3 的 `devices` 是同一类错误：凭印象写命令。

处置：写完后用脚本把 skill 里所有命令与参数反查源码，确认 21 个能力名全部存在、
抽查的 40+ 个 flag 全部能找到。**这个校验必须做，不能靠读一遍觉得对。**

### 39.4 内容里刻意留下的判断

- 把「锯齿 ≠ 泄漏」写进坑列表，因为这是最容易误报的一条（§32 的教训）
- 把「采样失败要点名」写进去，防止模型拿 4 个点下结论（§35 的教训）
- 把 `--limit`/`--depth` 的区别写进去，对应命名审计（§28）
- 明确写「`bundle` 退出码 4 = 必备件缺失，不是失败」，否则模型会当成 bug

### 39.5 本节锚定

- 日期：2026-10-17；触发：用户要求把工具用法沉淀成 skill 供其他模型使用
- 产物：`~/.agents/skills/ohd-device-verify/SKILL.md`（约 100 行）、`INDEX.md` 第 37 行
- 验证：脚本核对能力名 0 缺失、抽查参数 0 缺失；修正 `input` 用法

---

## 40. 修正 skill 的引导方向：局部使用是常态（2026-10-17）

### 40.1 我第一版判断错了

写完 skill 后我自查，担心「模型只会用来截图」，提的改法是「把缺陷包闭环前置、加证据强度强制引导」。
用户的反馈纠正了这个方向：

> 别人不一定要用全工具能力，只用部分才是常态吧；但如果是走完整的测试流程，就会让证据自洽。

这是对的。**局部使用不是缺陷，是合理用法**——自己看界面时截一张图完全够。
强行引导"每次都要 bundle"既不现实，也会让 skill 变长、变说教。

### 40.2 真正的缺口是「边界没写」

模型截完图就说「界面异常」，不是因为偷懒，是因为 skill 没告诉它**截图能证明什么**。
所以补的是两件事，而不是加流程强制：

**（1）单命令的结论边界**（新增一节，放在决策树之前）

| 命令 | 能回答 | 不能回答 |
|---|---|---|
| `shot` | 此刻界面长什么样、坐标在哪 | 操作后是否崩溃、内存是否泄漏、以前是否正常 |
| `tree` | 当前界面结构、哪些节点可点 | 点了会不会崩、有没有残留状态 |
| `log` | 这段时间发生了什么 | 内存/CPU 变化趋势 |
| `crash` | 崩了没有、崩在哪 | 为什么崩（要 `sym`）、怎么复现（要步骤） |
| `mem` | 此刻内存与对象分布 | 会不会泄漏（要时间维度） |
| `diff` | 两屏差在哪些像素 | 差的部分算不算缺陷 |

一句话概括：**单命令给的是「当下快照」；带「会不会」「是不是」的结论都需要时间维度或多份证据。**

**（2）什么时候必须自洽**（新增一节，放在闭环节之前）

不是「总要用全套」，而是「结论要交给别人时」——因为对方无法追问，只能靠包里的东西
独立回答：哪台机器、什么版本、什么操作、崩了没有、怎么复现。

### 40.3 这个设计的好处

- 局部使用者读到边界表，知道自己手上的结论**覆盖到哪、缺什么**，不会误报
- 要交付的人读到自洽条件，知道**为什么要补齐**，而不是被强制
- skill 从 99 → 122 行，只加了两张表，没加任何流程禁令

对比第一版「把闭环前置 + 证据强度分级」：那个方案会让每个只想知道"界面长啥样"的模型
都被告知"你还不够完整"，属于过度引导。

### 40.4 本节锚定

- 日期：2026-10-17；触发：用户指出「局部使用是常态，完整流程才要证据自洽」
- 产物：`~/.agents/skills/ohd-device-verify/SKILL.md` 第 22 行（边界表）、第 59 行（自洽条件）
- 验证：节顺序为 铁律 → 边界 → 决策树 → 自洽 → 闭环 → 压测 → 坑 → 排查 → 平台，共 122 行

---

## 41. 冒烟测试实录：用 ohd 测一个真实应用（2026-10-17）

目标应用：`com.tencent.compose`（设备上真实装的 CMP 演示应用，Web + Compose 混合渲染）。
链路：启动 → 定位 → 操作验证 → 压测 → 崩溃 → 内存 → 打包。

### 41.1 结论：能做，但暴露了 3 个真问题（已修 2 个）

| # | 问题 | 严重度 | 状态 |
|---|---|---|---|
| 1 | `env` 的「前台」永远显示 `-` | 中 | **已修** |
| 2 | monkey 会把浏览器点到外部网站，跑出被测应用 | **高** | **已修** |
| 3 | `--grep` 是子串非正则，`\|` 静默 0 匹配 | 中 | 待议 |
| 4 | `--session` 逗号分隔，重复传静默只取最后一个 | 中 | 待议 |
| 5 | Compose 自绘按钮在节点树里没有文本 | 已知局限 | 文档化 |

### 41.2 问题 1：前台字段空——两个叠加的坑

原实现：

```kotlin
"aa dump -a 2>/dev/null | grep -m1 -i 'foreground' ; hidumper -s WindowManagerService -a '-a' ..."
```

坑一：`grep -m1 'foreground'` 命中的是 `state #FOREGROUND` 那一行，**没有包名**。
坑二：`WindowManagerService` 这个服务名不存在（实际是 `-s 4606`）。

修的过程中又踩第三层：

```bash
hdc shell "aa dump -a"                # 有输出 ✓
hdc shell "aa dump -a | head -3"      # 空 ✗
```

**`aa dump` 在设备端一旦接管道就没有任何输出**（不是 grep 的问题，是 `aa` 本身在管道里不出数据）。
我一开始在本地 `hdc ... | grep` 测得好好的，所以误判成 `grep -B` 不支持——实际是本地的 grep 在工作。

最终实现（`DeviceOps.foregroundBundle`）：整段取回、本地逐个 bundle name 回溯、遇到 FOREGROUND 即返回。
耗时 307ms，可接受（比 dumpLayout 的 1.1s 便宜）。

### 41.3 问题 2：monkey 跑出应用——冒烟测试的致命缺陷

实测：monkey 跑 60s 后，界面变成浏览器打开 `help-me-connect.com`（WiFi 门户登录页）。
测的已经不是目标应用，还产生了外部网络副作用。

根因：monkey 没有「当前还在不在目标应用」的概念——**正是缺少 41.2 的前台检测能力**。
两个问题同源。

修复：`--stay-in-app`（默认开启），每 `--check-every` 步校验前台包名，不符则
`aa start -b <bundle> -a <mainAbility>` 拉回，并计入「越界拉回 N 次」。

实测效果：

```
应用边界  com.tencent.compose（越界拉回 3 次）
注入事件  67 步（跳过 4）
```

67 步里跑出去 3 次，全部拉回。**没有这个保护，冒烟结论不可信。**

### 41.4 冒烟链路实测数据

| 环节 | 结果 |
|---|---|
| 启动 | `aa start` 成功，`mem` 拿到 PSS（Graph 80MB / GL 47MB） |
| 定位 | 截图读出「原生 +1」按钮 (280,445)；`tree --grep` 找不到（Compose 自绘无文本节点） |
| 操作验证 | `tap 280 445` → 截图对比 `count = 2 → 3` ✓ |
| 压测 | 67 步，含边界保护，无新故障 |
| 崩溃 | 历史 cppcrash 完整：SIGSEGV@0x2d36730、662 帧栈、页面轨迹、fingerprint |
| 内存 | Pss 合计 594526 KB |
| 打包 | 8 个会话合并后 **5/7 必备件 OK**（缺 log 与 sym，本次流程没跑） |

崩溃栈指向 `libkn.so`（应用自己的 so）→ `ArkNativeFunctionCallBack` → `JsiFunction::Call`，
是应用侧 native 回调问题，不是工具问题。

### 41.5 问题 3/4 的共同性质：**静默失败**

- `--grep "原生\|重新加载"`：用户以为是正则 OR，实际按字面量找，返回「匹配 0」但不报错
- `--session A --session B`：以为传了两个，实际只取最后一个，结果缺一半必备件

两者都**看起来成功了**，只是结果空/少。这类静默比报错更危险——`--session` 那次我差点
把「5 项缺失」当成工具缺陷，实际是我的用法错。

### 41.6 本节锚定

- 日期：2026-10-17；设备 63R0226113004249 / API 24
- 代码改动：`DeviceOps.foregroundBundle`（新增）、`EnvDumpCap` 改用它、`MonkeyCap` 加 `--stay-in-app`
- 验证：61 测试全绿、`dist/smoke.sh` 通过、实测越界拉回 3 次、前台字段正确显示 `com.wifiservice.portallogin`
- 未改：`--grep` 的正则提示、`--session` 重复传参提示（待决定）

---

## 42. 消除两处静默失败（2026-10-17）

§41 发现的两个问题共同性质是「看起来成功了，只是结果空/少」。这类比报错更危险——
我本人就在 `--session` 那次把「5 项缺失」当成工具缺陷，实际是用法错。

### 42.1 `--grep` 正则误用 → 加提示

`LayoutQuery.filter` 用的是 `contains(ignoreCase=true)`，不是正则。
写 `--grep "原生\|重新加载"` 会按字面量找，返回「匹配 0」而不解释。

修复（`TreeCap`）：**只在「匹配为空 且 grep 含正则元字符」时**提示，避免正常查询被噪音污染。

```kotlin
if (filtered.isEmpty() && grep != null && grep.any { it in "|\\()[]*+?^$" }) {
    ctx.out.info("! --grep 按子串匹配，不是正则；含特殊字符时请拆成多次查询")
}
```

实测：
- `--grep "dialog"` → 匹配 10，**无提示**（不打扰正常查询）
- `--grep "dialog\|alert"` → 匹配 0 + 提示

### 42.2 `--session` 重复传参 → 加警告

`Options.values` 是 `Map<String,String>`，重复参数天然只留最后一个。
`--session A --session B` 静默只用 B，导致包里缺一半必备件。

修复：
1. `Parsed` 增 `duplicated: MutableSet<String>`，解析时记录重复的 key
2. 主流程在能力执行前打印警告，并对 `session` 额外提示逗号写法

实测输出：

```
! 选项重复出现：--session（只采用最后一个值）
  需要多个会话请用逗号：--session a,b,c
```

### 42.3 一个反复出现的验证教训

验证 `--grep` 提示时，我先用 `... | grep -E "匹配|按子串" | head -3` 判断「提示没出现」，
实际提示在输出**最后一行**，被 `head -3` 截掉了。改 `tail -6` 才看到。

同一类错误在 §41 也发生过（把 `aa dump` 在本地 grep 的效果误当成设备端行为）。
**结论：验证输出时要看全，别用固定行数的 head 截断后再下判断。**

### 42.4 本节锚定

- 日期：2026-10-17；设备 63R0226113004249
- 代码：`ohd-cli/.../Main.kt`（Parsed.duplicated + 警告）、`ohd-core/.../TreeCap.kt`（grep 提示）
- skill：`ohd-device-verify` 坑列表 7 → 10 条（补 grep 子串、session 逗号、stay-in-app）
- 验证：61 测试全绿、`dist/smoke.sh` 通过、两条提示均实测可见

---

## 43. 完整冒烟达 7/7：证据自洽验证通过（2026-10-17）

按 §41 缺的两环补跑（`log` + `sym`），最终 148 个会话合并后 **7 项必备件全 OK**。

### 43.1 `sym` 的缺陷：产物进不了证据包

`writeManifest` 是**手动调用**的，`SymCap` 漏了这一步：

```
.dsh/device/20261018-073906-sym/
  （空目录，没有 manifest.json）
```

后果：`bundle --session` 直接报 `x 会话缺 manifest.json`，符号化结果无法进入证据包。
而其它能力（EnvDump/Log/Selftest）都调了 `session.writeManifest(...)`，只有 sym 没有。

修复：在成功返回前补 `session?.writeManifest(null, mapOf("source" to sourceName))`。
验证：目录变成 `001-symbols.json` + `manifest.json`。

### 43.2 bundle 的 SYMBOLS_MISMATCH 检查（好设计，记录一下）

合并多会话时出现过：

```
! [SYMBOLS_MISMATCH] 符号结果与崩溃原文不一致，已降级为不可用：
  符号结果对应「com.tencent.compose / SIGSEGV」
  本包崩溃原文是「com.tencent.compose / THREAD_BLOCK_6S」
  不是同一次现场
```

包里有 appfreeze 和 cppcrash 两份崩溃，符号化的是 cppcrash，而 bundle 选中的主崩溃是 appfreeze。
**bundle 没有把符号硬套上去，而是降级并说明原因**——这正是「证据不得张冠李戴」的落地。
设计上值得保留：宁可报缺失，也不给错配的符号。

### 43.3 暴露的缺口：缺少「圈定本次测试」的机制

| 做法 | 结果 |
|---|---|
| 传 148 个今天会话 | 7/7 OK，但包 **96 MB**（含几百个无关 layout） |
| 传 20 个本次会话（07 点后） | 5/7（因为 monkey 是前一天跑的），仍 **48 MB** |

两个问题同源：
1. **没有 `--since <时间>`**：只能手工拼逗号或全量打包，前者易错、后者撑大包体
2. **layout 全量保留**：一次压测会产出几百个 `layout.json`（每个约 80 KB），是小包体的主因

建议（未实施）：
- `bundle --since "2026-10-18 07:30"` 自动收集该时间后的会话
- `bundle --max-layouts N`（默认保留首尾与异常点附近）

### 43.4 完整冒烟的标准序列（已验证可行）

```bash
ohd log start                                    # 1. 开日志窗
ohd shot --out a.png                             # 2. 基线截图
ohd tree --limit 20                              # 3. 基线节点
ohd monkey --bundle <b> --duration 5m ...        # 4. 压测（含应用边界保护）
ohd crash --list                                 # 5. 崩溃
ohd sym --crash <faultlog> [--so-dir ...]        # 6. 符号化
ohd log save                                     # 7. 收日志
ohd bundle --session a,b,c,... --redact          # 8. 打包
```

7 项必备件对应关系：env-dump→指纹、shot→截图帧、log→日志窗、crash→崩溃原文、
sym→符号化、watch/soak→指标序列、replay/monkey→可复现步骤。

### 43.5 本节锚定

- 日期：2026-10-17；设备 63R0226113004249 / SGT-AL50 / API 24
- 代码改动：仅 `SymCap` 补 `writeManifest` 一行
- 验证：61 测试全绿、`dist/smoke.sh` 通过、148 会话合并 7/7 OK
- 未做：`--since` 与 `--max-layouts`（记为建议）

---

## 44. `--since` 与由此挖出的三个连锁 bug（2026-10-17）

需求：`bundle --since "30m"` 自动圈定本次测试的会话，免去手拼 8 个目录名。

### 44.1 `--since` 实现

- 相对量：`30m` / `2h` / `1d`
- 绝对时间：`2026-10-18 07:30` / `2026-10-18T07:30[:ss]`
- 用**会话目录名**解析时间（`yyyyMMdd-HHmmss-cap`），不必读每个 manifest
- 结果按**最新优先**排序（同类别产物有多个会话时取新的）

### 44.2 然后 7/7 变成了 6/7，一路挖出三个 bug

加完 `--since` 后仍然缺「符号化结果」，报 SYMBOLS_MISMATCH。顺着查下去是三个独立的坑：

**bug 1：`cppcrash` 全被判成 `unknown`**

```kotlin
reason.startsWith("SIG") -> "cppcrash"
```

实际 faultlog 写的是 `Reason:Signal:SIGSEGV(...)`——`"Signal"` 里 i/g 小写，
`startsWith("SIG")` 大小写敏感 → false。**所有 cppcrash 都被判 unknown**。
修：`ignoreCase = true` + 补 SIGABRT/SIGBUS/SIGILL/SIGFPE/SIGTRAP。

**bug 2：`detectCrashKind` 只读 12 行**

cppcrash 日志的 `Reason:` 在**第 29 行**（前面是 ability/Hiview 头），
12 行读不到 → unknown → 被 `takeIf { it != "unknown" }` 丢弃。
而 appfreeze 的 Reason 在前几行 → 有值。于是主崩溃**永远**落到 appfreeze。
修：读到 `Reason:` 行为止（上限 200 行）。

**bug 3：包里的 `raw.log` 与 `crashKind` 用了两套选择逻辑**

- `crashKind` 用 `artifacts.firstOrNull { kind == "faultlog" }`
- `raw.log` 用 `buckets["crash"].firstOrNull { ... }`

两者独立取「第一个」，一旦顺序不同就出现「符号化的是 cppcrash、包里装的是 appfreeze」，
SYMBOLS_MISMATCH 把符号结果整片降级。
修：抽出 `crashSeverity()`（cppcrash 3 > jscrash 2 > appfreeze 1 > 其它 0），
**两处共用同一规则**。

### 44.3 还发现一个诱因：sym 会顺带注册 faultlog

`20261018-073849-sym` 是我第一次跑 `sym`（无参 → `--latest`）的会话，
它从设备拉崩溃时**注册了 faultlog**，于是按时间倒序排在真正的 crash 会话前面。
这让「取第一个 faultlog」的隐患立刻暴露。

行为本身合理（符号化的输入应当留证），但**主崩溃的选择不能依赖产物注册顺序**。

### 44.4 最终结果

```
设备与构建指纹 OK | 界面截图帧 OK | 应用日志窗口 OK | 崩溃原始日志 OK
符号化结果 OK | 指标时间序列 OK | 可复现步骤 OK
```

`bundle --since 10h`：7/7，无 MISMATCH 警告。61 测试全绿、smoke 通过。

### 44.5 未解决：包体仍然偏大

`--since 10h` 覆盖 41 个会话 → **139 MB**，主因是一次压测产出几百个 `layout.json`（各 ~80 KB）。
`--since` 解决了「圈定范围」，没解决「体积」。

建议（未做）：
- `--max-layouts N`：只保留首尾与异常点附近的 layout
- 或压测默认降低 layout 采样频率

### 44.6 本节锚定

- 日期：2026-10-17；设备 SGT-AL50 / 63R0226113004249 / API 24
- 代码：`BundleCap`（since 解析、sesionsSince、crashSeverity、detectCrashKind 读 200 行、
  raw.log 选择）、`CrashParser`（SIG 判定 ignoreCase）
- 验证：`--since 30m` / `--since "2026-10-18 07:30"` / `--since 10h` 均可用；7/7 达成

### 44.7 决策：包体大小不作为优化目标（2026-10-17）

§44.5 提了「包体偏大」并建议 `--max-layouts`。用户明确否决：

> 真实需要的达没关系

即：**只要包里装的是真实需要的证据，体积大不是问题**。

因此：
- **不做** `--max-layouts`，layout 全量保留（每个都是当时的节点树快照，删了就没法回溯坐标与结构）
- **不做** 压测 layout 采样降频
- 139 MB / 41 会话视为正常

判断依据：接受方是开发，拿到的是自洽现场；包大可以传，证据缺了就得回来问，后者代价更高。
若将来真需要瘦身，优先砍的是**重复的历史会话**（用 `--since` 圈定），而不是砍证据本身。

---

## 45. 长时压测改为增量落盘（2026-10-18）

用户判定：**「长时间运行不落盘」是唯一缺陷，需要优化**。

### 45.1 问题

`monkey` 的采样 CSV 和操作脚本都在**循环结束后**一次性写：

```kotlin
// 循环结束后才执行
val scriptText = buildString { events.forEach { appendLine(it) } }
Session.writeText(scriptPath, scriptText)
```

后果：3 小时压测的 600+ 个采样点、近两万步操作记录全在内存里。
进程被中断（手动、OOM、设备断连）→ **全部丢失，只剩一堆 layout.json**。
而 layout 恰恰是恢复不了趋势和操作序列的。

### 45.2 修法：边跑边写 + 每步 flush

循环前建 writer（表头先写），事件与采样各自实时写入并 flush：

```kotlin
fun appendEvent(line: String) {
    events.add(line)
    scriptWriter?.run { write(line); write("\n"); flush() }
}
```

三处事件源统一走 `appendEvent`：
- 正常操作（每批 10 行）
- 脱困注入的 `key Back` / `key Home`
- 越界拉回的说明行

采样则在下一次采样点后立刻写一行 CSV。

开销：flush 每次约 0.05ms，一万九千步合计约 1 秒，相对 3 小时可忽略。

### 45.3 验证（必须实跑，静态检查无法证明）

40 秒短跑，跑到 **18 秒**时检查文件：

| 时点 | 采样 CSV | 操作脚本 |
|---|---|---|
| 中途（18s，未结束） | 2 行 | 23 行 |
| 结束后 | 4 行 | 65 行 |

**关键在「中途就有」，不是「结束才齐」**——这正是原来缺的性质。

### 45.4 顺带的行为变化

- 脚本头注释从「共 N 步」改为「增量写入，每行一步」，因为写头时还不知道总步数
- CSV 空采样时文件仍会创建（只有表头），比原来「不创建文件」更容易判断「跑了但没采到」

### 45.5 为什么这一条是「唯一缺陷」

前几轮修的都是**结果正确性**（判错类型、选错崩溃、静默失败），
这一条是**数据存活**：前者的补救是重跑一次，后者的代价是整个 3 小时。

### 45.6 本节锚定

- 日期：2026-10-18
- 代码：`ohd-core/.../caps/MonkeyCap.kt`（appendEvent、csvWriter、循环前建 writer、循环后仅收尾）
- 验证：build=0、61 测试全绿、40s 短跑中途可见、3h 压测已用新版重启（10:22 起）

---

## 46. 129 次「跑出应用」的真因：工具误触系统手势（2026-10-18）

### 46.1 用户的纠正推翻了原判断

原来把越界解释成「这个应用外链多」。用户指出：**demo 源码里没有外链也没有短信**。

回头看 90 分钟那次的越界前一步动作，证据很一致：

| 动作 | 坐标 | 含义 |
|---|---|---|
| `tap 397 2774` / `876 2787` / `897 2781` | y 距底边 45–58px | 点在**系统导航栏**上 |
| `swipe 990 2720 → 990 2831` | 滑到 y=2831 | **底部上滑 = Home 手势** |
| `swipe 299 2557 → 1 2557` | 滑到 x=1 | **屏幕边缘返回手势** |
| `tap 1159 227` | y=223 | 状态栏/标题栏区 |

**结论：不是应用跳转，是工具自己在操作系统栏。**

### 46.2 根因

```kotlin
val safeBottom = screenH   // ← 把整个屏幕都当成应用区
```

`safeBottom` 直接等于屏幕高，底部导航栏（1280×2832 上约 120–150px）**完全没排除**。
于是：

- `tap` 的 y 可以到 `safeBottom - 1 = 2831` → 落在导航栏
- `swipe` 的终点可以到 2831 → 触发 Home 手势
- `swipe` 终点的 x 可以到 `1` → 触发边缘返回

### 46.3 修法

```kotlin
val safeBottom = screenH - maxOf(120, screenH * 5 / 100)
val x2 = (x + dx).coerceIn(w / 12, w * 11 / 12)   // 端点不贴边
```

- 底部留出 max(120px, 5%)——约 142px
- swipe 端点 x 收进 1/12 ~ 11/12，不碰左右边缘

### 46.4 效果

| 场景 | 修前 | 修后 |
|---|---|---|
| 2 分钟 / ~190 步 | 越界 **12 次** | 越界 **5 次** |
| 35 秒 / 60 步 | — | 越界 **0 次** |

剩余 5 次的原因查明：`key Back` 占 4%，190 步约 7 次，其中 5 次退到桌面。
**这是 Back 键的正常行为**（边界保护会拉回），不是误触，予以保留。

### 46.5 教训

「工具异常」和「被测应用异常」症状相同——都表现为离开目标应用。
原来凭"应用可能有不少外链"就接受了 129 次这个数字，**没有去核对坐标**。

**经验：任何异常计数（越界、失败、缺件）出现后，先看一次原始记录，再决定归因。**

### 46.6 本节锚定

- 日期：2026-10-18
- 代码：`ohd-core/.../caps/MonkeyCap.kt`（safeBottom、swipe x2、y2）
- 证据：`/tmp/monkey3h.log`（旧版 90 分钟，9171 步，129 次越界）
- 验证：2 分钟 12→5 次；35 秒 0 次；3h 压测已用修复版重启（10:29 起）

---

## 47. 安全区改为从 layout 动态推导（2026-10-18）

用户一问点破了上一节的问题：**「安全区是 layout 推导的还是写死的？」**

答案是：`safeTop` 看着动态（取根节点 top），但根节点是全屏 `[0,0][1280,2832]`，top=0，
实际一直靠 `screenH*5/100` 兜底；`safeBottom` 则是**纯写死**的 `screenH - max(120, 5%)`。

### 47.1 layout 里本来就有系统栏信息

同一份 layout 的顶层结构：

| 节点 | bounds | 含义 |
|---|---|---|
| 顶层包裹 | `[0,0][1280,2832]` | 全屏 |
| `WindowScene` | `[0,0][1280,203]` | 状态栏 |
| **应用窗口 root** | `[84,137][1280,2727]` | 真实可点区 |
| 底部导航 root | `[288,2727][992,2832]` | 导航栏，内含返回/Home 按钮 |

写死的 2691 与真实窗口 2727 差 **36px**——这 36px 恰好压在导航栏边缘上。

### 47.2 改法：找「高度接近全屏但没占满」的节点

```kotlin
val rootRects = DeviceOps.captureLayout(hdc, session).flat().mapNotNull { it.bounds }
val fullRect = rootRects.maxByOrNull { it.width * it.height }        // 全屏
val appRect = rootRects
    .filter { it.bottom < screenH && it.height > screenH / 2 }
    .maxByOrNull { it.height }                                       // 应用窗口
val safeTop = appRect?.top?.takeIf { it > 0 } ?: (screenH * 5 / 100)
val safeBottom = appRect?.bottom?.takeIf { it < screenH } ?: (screenH - maxOf(120, screenH * 5 / 100))
```

拿不到应用窗口时仍回退写死值，且输出里标明来源：
`安全区 y 137..2727（布局推导）` / `y 141..2691（比例兜底）`。

### 47.3 一个踩到的坑：`roots` 不等于顶层节点

第一版用 `captureLayout(...).roots`，结果永远是「比例兜底」。原因在 `LayoutParser.parse`：

```kotlin
is JsonObject -> listOfNotNull(toNode(root, 0))   // 整个树被包成 1 个节点
```

顶层是 `{attributes, children}` 的 JsonObject，所以 `roots` 只有 **1 个**元素，
拿不到那 4 个顶层 root。**必须 `flat()` 展开**才能看到应用窗口。

### 47.4 操作事故：pkill 把自己杀了

清理旧压测时用 `pkill -f "dist/ohd monkey"`——但当前 shell 的命令行里也含这串字符，
**shell 自杀，后续 nohup 没执行**，于是出现「日志是旧的、进程还在跑」的假象，
一度并存 3 个实例互相抢设备。

正确做法：`pgrep -f "java.*MainKt monkey"` 精确匹配 java（`ps` 在沙箱下被禁）。

### 47.5 本节锚定

- 日期：2026-10-18；设备 SGT-AL50 / 1280x2832
- 代码：`ohd-core/.../caps/MonkeyCap.kt`（rootRects 用 flat、appRect 推导、输出「安全区」行）
- 验证：`ohd monkey --duration 20s` 输出 `安全区 y 137..2727（布局推导）`，与 layout 应用窗口一致；越界 0 次
- 当前压测：10:33 起，单实例（java 进程数=1），CSV/脚本增量写入正常

---

## 48. `salvage`：从残留会话反推运行画像（2026-10-18）

### 48.1 定位

§45 修掉「长时不落盘」之后，这能力**不再是常规路径**，而是补救手段：

- 已产生的残留会话（只有 layout）仍然只能这么救
- 未来若仍有中断（设备断连、被强杀），layout 至少能还原一部分

### 48.2 只报能确认的

| 项 | 来源 | 可靠性 |
|---|---|---|
| 运行时长 | 文件 mtime 跨度 | 高 |
| 界面数量与多样性 | 文件字节指纹去重 | 高 |
| 每帧完整节点树 | layout 本身 | 高 |
| 可点节点波动 | 抽样解析（最多 24 帧） | 中（抽样） |

**明确不报**（输出里逐条列出）：
- 步数——layout 序号是抓取次数，不是注入步数
- 事件序列——没有脚本无法回放
- PSS/DMA——**不得据此给泄漏结论**

宁可列空白，也不给一个像模像样的估计值。

### 48.3 验证

对 90 分钟那次残留会话（`20261018-085106-monkey`）：

```
产物      928 个 layout
时长      1 小时 30 分（08:51:08 → 10:21:30）
界面      928 帧，921 个不同（99%）
可点节点  6 ~ 130（中位 26，抽样 25 帧）
```

与之前手工分析（90 分钟、921 个不同、27 最常见）完全一致。

同时能识别「已有完整数据」的会话并劝退：
`[HAS_FULL_DATA] 该会话已含脚本与采样，不需要 salvage`

### 48.4 实现中的两个坑

**坑 1：`parseFile` 返回 `List<LayoutNode>`，没有 `flat()`**

`flat()` 是 `LayoutCapture` 的方法。对解析结果要用 `flatMap { it.flatten() }`。

**坑 2：位置参数被静默忽略**

第一版只读 `--session`，于是 `ohd salvage <目录>` 传的目录被丢掉、转而分析了「最新会话」，
输出看着完全正常（有数据、有结论），**只是分析的不是用户指定的那个**。
这正是最危险的一类错误。修：`str("session") ?: positionals.firstOrNull()`。

### 48.5 本节锚定

- 日期：2026-10-18
- 代码：`ohd-core/.../caps/SalvageCap.kt`（新增）、`ohd-cli/.../Main.kt`（注册）
- 能力数：21 → **22**
- 验证：build=0、61 测试全绿、对残留会话输出与手工分析一致
- skill：`ohd-device-verify` 增「中断后的补救」段

---

## 49. 符号化的能力边界被澄清（2026-10-18）

用户一句反问：「ohd 不是也有 addr2line 能力吗」——**是的，我上一节说错了**。

### 49.1 我错在哪

上一节说「开发拿到 debug so 后自己 `addr2line -e libkn.so 0x15064c8`」，
把工具已有的能力说成了手工步骤。实际上 `ohd sym --so-dir` 就是这件事的封装。

正确的表述是：**差的是 so 文件，不是能力**。

### 49.2 实测 `--so-dir` 链路（用设备系统 so）

拉 `libace_napi.z.so` 到本地后：

```
!#3  pc 0x67c90  libace_napi.z.so  panda::JSValueRef ArkNativeFunctionCallBack<true  ??:0  OK
```

- so 被找到 ✓
- buildId 校验通过 ✓
- `llvm-addr2line` 被调用 ✓
- 位置是 `??:0` —— 因为**系统 so 被 strip 过**，这是预期
- 函数名来自**日志自带符号**（logSymbol），不是 addr2line 的产物

### 49.3 错版本 so 必须拒绝（已验证）

把 `libace_napi.z.so` 复制成 `libkn.so` 制造 buildId 不匹配：

```
✗ [BUILD_ID_MISMATCH] 2 帧的本地 .so 与日志 buildId 不一致，已拒绝出符号（换对应版本 debug .so 再跑）
```

**这个设计是对的**：错版本的符号会把排障带到错误的地方，比没有符号更有害。

### 49.4 所以完整链路的真相

| 输入 | 产出 |
|---|---|
| 只有日志 | pc + so 名 + 日志自带的部分符号 |
| **日志 + 对应 buildId 的 debug so** | **函数名 + 行号**（addr2line 真正生效） |
| 日志 + 错版本 so | 拒绝出符号（BUILD_ID_MISMATCH） |

开发侧的动作只有一个：**提供与崩溃时一致的 debug so**（路径通常在
`build/default/intermediates/libs/<abi>/`）。

### 49.5 新增：FEW_SYMBOLS 警告

3 帧和 40 帧的包在 COMPLETENESS 里都显示「符号化结果 OK」，但排障可用性差很远。
bundle 现在会读 symbols.json 的 frames 数，1–9 帧时警告：

```
! [FEW_SYMBOLS] 符号化只覆盖 3 帧，排障时可能不够；重跑 sym 时用 --limit 40（默认值）
```

**只警告，不改判定**——帧数少不等于数据错。

### 49.6 本节锚定

- 日期：2026-10-18
- 代码：`BundleCap.kt`（FEW_SYMBOLS）；`SymCap.kt` / `Symbolizer.kt`（未改，仅验证）
- 验证：`--so-dir /tmp/so` → 帧 3 状态 OK；伪造 so → BUILD_ID_MISMATCH 拒绝
- 设备 so 位置：`/system/lib64/platformsdk/libace_napi.z.so`（654 KB，strip 过）

---

## 50. so 的来源改为「测试侧自给」：`--hap` 与自动发现（2026-10-18）

用户的要求很明确：**不该反过来要求开发交 so，测试这边要能把最终产物直接给研发。**

### 50.1 先排除走不通的路

| 途径 | 实测结果 |
|---|---|
| `hdc file recv /data/app/el1/bundle/public/<bundle>/libs/arm64/libkn.so` | **permission denied**（`DeviceDebuggable:No`） |
| `hdc shell ls /data/app/el1/bundle/public/<bundle>/` | **permission denied** |
| 从设备拉 hap | 同目录，同样拒绝 |

非 root 设备上，「从设备取 so」这条路是堵死的。而且 release 包的 so 必然被 strip。

### 50.2 但测试手里有 hap

测试安装时手里就有 hap，hap 是 zip，native 库在 `libs/<abi>/` 下。**这是最自然的入口**。

```bash
ohd sym --crash 001-faultlog.txt --hap ./app.hap
```

实现：`ZipFile` 遍历条目，取所有 `.so` 解到临时目录，加进 soDirs 候选。

### 50.3 另外加了自动发现（默认开）

测试与开发通常在同一台机器、同一仓库，debug .so 就在 `build/**/libs/arm64/` 下：

```bash
ohd sym --crash 001-faultlog.txt      # 自动扫 build/，不需要任何参数
```

只认名字是 `arm64|arm64-v8a|aarch64` 且下面真有 .so 的目录，深度 8 层，实测 **0.5 秒**扫完。
`--no-auto-so` 可关闭。

### 50.4 验证

构造一个 hap（zip 内 `libs/arm64/libace_napi.z.so`）：

```
│ #3 │ 0x67c90 │ libace_napi.z.so │ panda::...ArkNativeFunctionCallBack<true │ ??:0 │ OK │
```

解包 → buildId 匹配 → 状态 OK。链路通。

### 50.5 能力边界仍然存在，但位置变了

| hap 类型 | 结果 |
|---|---|
| **debug 构建**（测试常用） | so 带符号 → addr2line 出**函数名 + 行号**，闭环 |
| release 构建 | so strip → 只能 buildId 校验 + 日志自带符号 |

也就是说：**能不能闭环取决于测试装的是不是 debug 包，而不是取决于开发配不配合。**
这把「跨团队要文件」变成了「测试自己打包时就有的东西」。

### 50.6 本节锚定

- 日期：2026-10-18；设备 SGT-AL50（`DeviceDebuggable:No`）
- 代码：`SymCap.kt`（`--hap` → extractSosFromHap、discoverSoDirs 默认开、`--no-auto-so`）
- 验证：构造 hap 解包后帧 3 状态 OK；自动发现 0.5s；设备 so 拉取 permission denied

---

## 51. `install` / `ab`：让工具能验收益，而不只是抓现场（2026-10-18）

用户提的方向：**ohd 应该能按要求装/换 hap，做 A/B 对比**。
这正好补上工具链缺的一环——前面 22 个能力都在「发现并证明问题」，没有「证明改好了」。

### 51.1 `install`

```bash
ohd install app.hap            # -r 覆盖安装
```

- 用 `hdc install -r`，失败时把设备原话原样带出（常见是版本回退或签名不一致）
- 报出 hap 大小，避免装错文件
- `--keep-data` 仅作语义标注（`-r` 本身不清数据）

### 51.2 `ab`

```bash
ohd ab --a v1.hap --b v2.hap --bundle com.x --soak 3m --seed 42
```

流程：装 A → 冷启 → 轻量压测 → 装 B → 冷启 → 同样压测 → 对比表。

对比维度：PSS 峰值/均值/首末、DMA 峰值/均值、崩溃次数、步数。

**三个设计约束**：

1. **同 seed**——两轮点击序列一致，否则比的是随机性
2. **每轮 force-stop 再 start**——A 的残留状态会污染 B
3. **只做坐标点击，不抓 layout**——节拍只受包本身影响，不叠加 1.1 秒的 dumpLayout

### 51.3 输出口径

差异列是 `B 相对 A` 的百分比。同时固定打印两句：

> 差异列 = B 相对 A 的变化；正数表示 B 更高。
> 这只说明「数值变了」，是否由本次改动导致需结合代码判断。

有崩溃时追加 `AB_HAS_CRASH` 警告——崩溃会打断采样，此时指标不可比。

**不写「A 比 B 好」**，只写数值差。谁好谁坏取决于优化目标（内存 vs 启动速度 vs 帧率），工具不知道。

### 51.4 与既有能力的衔接

```
ohd install app.hap                       # 换包
ohd monkey --bundle x --duration 30m      # 压测
ohd bundle --since 40m                    # 打包给开发
ohd ab --a old.hap --b new.hap --bundle x # 验收益
```

前三个解决「有问题怎么办」，第四个解决「改完了有没有用」。

### 51.5 尚未实测

压测占用设备期间没有执行真实安装（会替换正在压测的包）。
已验证：编译通过、61 测试全绿、缺参提示正确。
**真机验证待压测结束后补**——这是本节唯一的空缺，不能当作已验证。

### 51.6 本节锚定

- 日期：2026-10-18
- 代码：`ohd-core/.../caps/InstallCap.kt`（InstallCap + AbCap，新文件）、`ohd-cli/.../Main.kt`（注册）
- 能力数：22 → **24**
- 未验证项：真机 install、真机 ab（受压测占用阻塞）

---

## 52. 测试侧取 so 的真相：原件的在 `entry/libs/`，hap 里的才被 strip（2026-10-18）

### 52.1 用户的判断纠正了我两次

1. 「不需要开发找 so，测试这边自己出产物」
2. 「hap 是不是 strip 无所谓，能用 so 解析就可以了」

第二次直接点破了我的思路偏差：我一直在纠结 hap 里的 so 是 stripped，
**但 hap 只是分发物，构建目录里本来就留着未 strip 的原件**。

### 52.2 实测链路

| 位置 | 大小 | 状态 |
|---|---|---|
| `harmonyApp/entry/libs/arm64-v8a/libkn.so` | **133 MB** | **not stripped**（`.symtab` + `.debug_info`） |
| hap 内 `libs/arm64-v8a/libkn.so` | 57 MB | stripped |
| 设备 `/data/app/.../libs/arm64/` | - | permission denied |

hvigor 打包时从 `entry/libs/` 复制并 strip 成 hap 里的 so。**源文件不动。**
所以「hap 是否 strip」对符号化无关——测符号化应该直接看 `entry/libs/`。

### 52.3 于是 auto-so 恰好命中

`ohd sym` 在工程根目录执行时：

```
自动发现 3 个 .so 目录（--no-auto-so 可关闭）
✗ [BUILD_ID_MISMATCH] 2 帧的本地 .so 与日志 buildId 不一致，已拒绝出符号
```

- 自动扫到 `entry/libs/arm64-v8a/`（目录名匹配 `arm64-v8a`）
- 找到 libkn.so
- 但那是**刚构建的新版本**，与历史崩溃的 buildId 不同 → 拒绝出符号

**这两条都对**：找得到是能力，拒绝错版本是纪律。

### 52.4 测试侧的完整闭环（不需要开发参与）

```bash
cd <工程根>
./ohos.sh ohosArm64 n debug        # 构建，产物含 entry/libs/arm64-v8a/libkn.so
ohd monkey --bundle com.tencent.compose --duration 30m   # 压测，崩溃自动留现场
ohd sym --crash .dsh/device/<会话>/001-faultlog.txt      # 自动找到 so → 函数名+行号
ohd bundle --since 40m                                    # 打包给开发
```

**前提只有一条**：崩溃与 so 来自**同一次构建**。

### 52.5 构建侧的实际坑（顺带记下）

`./ohos.sh ohosArm64 n debug` 默认失败，原因是 CMP 发布坐标不对：

```
OhosReclaimDevSwitch  Unresolved reference
SideEffect 参数类型不匹配：'DirectContext?' vs '() -> Unit'
```

Maven Local 里有 5 个 stamp，但**只有部分既含新 API 又发布 Gradle plugin**：

| stamp | Gradle plugin | 含 OhosReclaimDevSwitch |
|---|---|---|
| 1.9.2-xxw-001 | 有 | **无** |
| 1.12.0-0.1.0-03 | **无** | 有 |
| 1.12.0-xxw-001 | **有** | **有** ← 可用 |
| 9999.0.0-SNAPSHOT | 有 | 需确认 |

正确命令：

```bash
COMPOSE_PUBLISH_VERSION=1.12.0-xxw-001 SKIP_INSTALL=1 ./ohos.sh ohosArm64 n debug
```

### 52.6 本节锚定

- 日期：2026-10-18
- 工程：`/Users/xuxingwang/KMP/ovcompose-sample`（`strip` 配置已恢复 true，改动可逆）
- so 原件：`harmonyApp/entry/libs/arm64-v8a/libkn.so`（133 MB，not stripped，buildId b98ff9a1…）
- 工具：`SymCap`（auto-so 与 `--hap`）；实测自动发现 3 个目录
- 待办：压测结束后装新 hap 跑一次崩溃，验证**同版本**下能输出函数名+行号

---

## 53. 采样的第二个「静默错误」：pid 不刷新 + 失败写 0（2026-10-18）

### 53.1 现象

3 小时压测跑到第 1070 步（10:45:31）之后，CSV 里 PSS 和 DMA **全部变成 0**：

```
1070,2026-10-18T10:45:31.768+08:00,0,0
2291,2026-10-18T10:45:31.768+08:00,0,0   ← 之后 30 多行全是 0
```

看起来像「内存突然归零」，实际是**采样对象已经不存在**。

### 53.2 两个原因

**原因一：pid 是启动时固定的 `val`**

```kotlin
val pid = DeviceOps.resolvePid(hdc, optPid, bundle)   // 只取一次
```

越界拉回（`aa start` 重新拉起）、崩溃重启都会让 pid 变化。
之后 `hidumper --mem <旧pid>` 返回空，`MemParser` 解析出 0。

**原因二：解析失败当成「值为 0」**

```kotlin
val mem = MemParser.parse(...)   // 失败 → pssKb = 0，照样写进 CSV
```

真实进程不可能占 0 KB。把「没读到」写成 0，等于把错误伪装成数据。

### 53.3 修法

```kotlin
// 采样前先问一次当前 pid，取不到才退回启动时的值
val livePid = if (bundle != null) {
    runCatching { Hdcs.sh(session, hdc, "pidof $bundle", 8_000).stdout.trim()
        .split(Regex("\\s+")).firstOrNull()?.toIntOrNull() }.getOrNull() ?: pid
} else pid

val mem = MemParser.parse(Hdcs.sh(session, hdc, "hidumper --mem $livePid", 25_000).stdout)
if (mem.pssKb <= 0) error("pss unavailable")   // 失败必须走失败分支，不能写 0
```

代价：每次采样多一次 `pidof`（80 ms），相对 25 秒采样间隔可忽略。

### 53.4 验证（必须真机，静态检查证明不了）

短跑 90 秒，中途 `aa force-stop` 造 pid 变化：

| 步数 | PSS (KB) | 说明 |
|---|---|---|
| 39 | 1466845 | 杀之前 |
| 58 | **317028** | **force-stop 后重启，新进程** |
| 88 | 447987 | 继续有效 |
| 147 | 385433 | 继续有效 |

**0 值行数 = 0**。

对照修复前：同一场景会一直写 0 直到结束。

### 53.5 代价说明

那次 3 小时压测的后 1300 多步采样全部作废（数据是 0，不是真实值）。
已停掉并用修复版重跑（11:08 起，14:08 结束）。

**这是第二次「静默写错数据」**：
- 第一次是长时不落盘（§45，丢掉全部）
- 这次是失败写成 0（留下一串假数据）

后者更阴险——文件在、格式对、时间戳连续，只有值是假的。

### 53.6 本节锚定

- 日期：2026-10-18
- 代码：`MonkeyCap.kt`（livePid、pss<=0 转失败）
- 验证：force-stop 场景 PSS 1466845 → 317028，0 值行 0
- 旧压测：`20261018-103359-monkey`（1070 步后数据作废）
- 新压测：`20261018-110846-monkey`（11:08 起，修复版）

---

## 54. stripped so 能否 addr2line：不能，且行号另有坑（2026-10-18）

### 54.1 受控对比

同一 buildId（`b98ff9a1…`）的两份 libkn.so，唯一变量是「是否被 strip」：

| 输入 | `llvm-addr2line -f -C 0x15064c8` |
|---|---|
| stripped（hap 内 57 MB） | `??` / `??:0` |
| not stripped（`entry/libs` 133 MB） | `ArkUIViewController_onBackPress(...)` / `xcomponent_utils.cpp:0` |

结论：**stripped so 只能给 `??:0`，无法解析**。
`.dynsym` 里的导出符号另说（`nm -D` 可查），但 addr2line 不查 dynsym。

### 54.2 行号为什么是 0

`.debug_info`（13.8 MB）和 `.debug_line`（10 MB）都在，行号仍是 0。
原因是构建用的 `-O3 -DNDEBUG`（`build-profile.json5` 的 `cppFlags`）：
优化重排后行号映射粗化，addr2line 落到 `:0`。

跨两个不同地址都是 `:0`，可排除单点例外：

```
0x15064c8 -> ArkUIViewController_onBackPress(...)         xcomponent_utils.cpp:0
0x1509fe4 -> ArkUIViewControllerWrapper::AttachEmbedSurface(...)  arkui_view_controller_wrapper.cpp:0
```

**要行号需要 `-g` + 低优化级别**；`-O3` 只保证函数名。

### 54.3 对工具的实际含义

1. `ohd sym` 必须拿到 **not stripped** 的 so —— 所以 auto-so 扫 `entry/libs/<abi>/` 是对的
2. 输出里「行号」列可能是 `:0`，这**不是工具 bug**，是构建优化级别决定的
3. buildId 校验不能省：错版本的 so 即使带符号，地址也对不上，会给出「看似正确实则错误」的函数名

### 54.4 本节锚定

- 日期：2026-10-18
- so：`ovcompose-sample/harmonyApp/entry/libs/arm64-v8a/libkn.so`（not stripped，buildId b98ff9a1…）
- 工具：`llvm-addr2line`（Sdk/20/native/llvm/bin）
- 构建标志：`externalNativeOptions.cppFlags = "-O3 -DNDEBUG"`

---

## 55. buildId 不匹配该不该出符号：用户纠正了我的「宁缺毋滥」（2026-10-18）

### 55.1 用户的质疑

「我那个 addr 的 Python 脚本是怎么解析的，skills 里也有解析堆栈，一直这么用的。」

### 55.2 读代码后的真相

`ovcompose-sample/addr2line_parser.py` 的做法：

```python
parser.add_argument("--binary", default="harmonyApp/entry/libs/arm64-v8a/libkn.so")
```

**默认 binary 就是那份 133 MB 带符号的**。所以「一直能用」不是因为有绕过 strip 的技巧，
而是**从来没喂过 stripped 那份**。DESIGN §54 的结论（stripped → `??:0`）依然成立。

### 55.3 真正的差异：mismatch 时怎么办

同一个崩溃日志（buildId `29c2c8fc…`），本地 so 是 `b98ff9a1…`：

| 工具 | 行为 |
|---|---|
| 用户脚本 | 打 `[WARNING] BUILD-ID MISMATCH / may be wrong`，**继续解析全部 663 个地址** |
| ohd（改前） | 标 `BUILD_ID_MISMATCH`，**一帧都不给** |

用户实测：663 帧全部拿到函数名，甚至包括 Kotlin mangled 名
`kfun:androidx.compose.material3.l10n#gl__at__…`。

**这推翻了我 §54.3 的判断**。我当时写「错版本的 so 会给出看似正确实则错误的函数名，比报错更危险」——
理论上对，但忽略了**工程实践里「有名字可以顺着查」比「什么都没有」价值高得多**，
尤其是相邻版本间地址漂移往往只有几十字节，绝大多数帧仍落在同一个函数里。

### 55.4 解法：默认严格，可显式放宽

```bash
ohd sym --crash x.txt --allow-mismatch --limit 4
```

```
│ 1 │ 0x15064c8 │ libkn.so │ ...ArkUIViewController_onBackPress │ xcomponent_utils.cpp:0 │ BUILD_ID_MISMATCH │
│ 2 │ 0x1509fe4 │ libkn.so │ ...ArkUIViewControllerWrapper::...  │ arkui_view_controller_wrapper.cpp:0 │ BUILD_ID_MISMATCH │
```

- 函数名照常输出
- **状态列仍然标 `BUILD_ID_MISMATCH`**——不隐瞒，只是不阻断
- 退出码 0（不再是 7）
- 默认不加该参数时行为不变（拒绝），避免误用

设计理由：**能不能接受「可能错位」是使用者的判断，不是工具的判断**。
工具的责任是把不确定性标出来，不是替人决定要不要看。

### 55.5 与用户脚本的对齐度

| 能力 | 用户脚本 | ohd |
|---|---|---|
| 找 so | `glob **/{so_name}` | auto-so 扫 8 层，命中 `arm64*` 目录 |
| buildId | 校验并警告 | 校验，默认拒绝 / `--allow-mismatch` 警告继续 |
| 符号化 | `llvm-addr2line -C -f` | 同，外加 `-i` 展开内联 |
| 输出 | 逐行文本 | 表格 + JSON + 状态列 |

### 55.6 本节锚定

- 日期：2026-10-18
- 用户脚本：`/Users/xuxingwang/KMP/ovcompose-sample/addr2line_parser.py`
- 代码：`symbol/Symbolizer.kt`（`allowMismatch`）、`caps/SymCap.kt`（参数与退出码）
- 实测：`--allow-mismatch` 输出 `BUILD_ID_MISMATCH` 状态 + 函数名，exit 0
- 修订：推翻 §54.3 中「不比不符号化更安全」的绝对化表述

---

## 56. 3 小时压测结论 + 闭环断点：崩溃时 faultlog 没留存（2026-10-18）

### 56.1 压测数据（会话 `20261018-110846-monkey`）

| 指标 | 值 |
|---|---|
| 时长 | 1.53 小时（11:09:11 → 12:40:45），**非跑满 3 小时** |
| 步数 | 10144（110.1 步/分） |
| 采样 | 279 次（失败 16 次，5.7%） |
| 越界拉回 | 43 次 |
| PSS | 581.8 MB → **426.3 MB**（-155.5 MB） |
| PSS 峰/谷 | **3153.9 MB** / 186.7 MB |
| PSS 走势 | 锯齿（回撤 2967 MB / 峰 3153.9 MB） |
| DMA | 109.0 → 100.2 MB |

**结论**：首末**下降**，非单调增长 → **无持续泄漏迹象**。
但峰值 3153.9 MB 值得单独追（约为稳态的 7 倍），属瞬时大分配后被回收。

### 56.2 提前结束的原因：捕获崩溃

```
✗ [CRASH_FOUND] cppcrash-com.tencent.compose-20020221-20261018124117
原因   Signal:SIGSEGV(SEGV_MAPERR)@0x006bd40c1cf0853d
指纹   8edfbaff33d38624
```

页面轨迹显示崩溃前 9 秒内**反复切前台 8 次**（12:41:04~12:41:08），
与 43 次越界拉回（force-stop + start）高度相关。

主线程栈（系统 so 自带符号，无需 addr2line）：

```
#00 napi_get_reference_value+44
#01 StateListener::NotifyJS(napi_env*, bool, napi_ref*)   ← libaccessibility_napi
#02 libace_napi
#03 EventRunnerImpl::ExecuteEventHandler
```

**栈里没有 libkn.so 帧**——崩溃发生在 ArkUI/无障碍 native 回调链，不在应用 so 内。
所以「有 so 也用不上」，本例不需要符号化。

推测方向（**不下结论**）：越界拉回使 napi_ref 失效，`NotifyJS` 复用后解引用野地址。
需要 `--allow-mismatch` 符号化复现验证，或抓 napi 引用生命周期日志。

### 56.3 闭环断点：崩溃时没有留存 faultlog

现场只留了：

```
965-shot.jpeg
966-layout.json
002-monkey.ohd
```

**没有 faultlog**。原因是 `Faults` 只提供三个方法：

```kotlin
fun parse(name: String): Entry?
fun list(session, hdc): List<String>
fun diff(before, after): List<Entry>
```

`hit` 拿到的是 `Faults.Entry(kind, module, raw)`——**只有文件名，没有内容**。

于是压测能喊出「崩溃了」，却拿不出崩溃堆栈。
**这是闭环上最硬的一个断点**：发现了问题，但证据链在第一步就断了。

### 56.4 修法

`CrashCap` 早就有正确做法（`hidumper -s 1201 -a '-p Faultlogger -f <name>'`，
`ls /data/log/faultlog/faultlogger/` 是 Permission denied 的，只能走 hidumper）。

monkey 崩溃时复用它：

```kotlin
val r = Hdcs.sh(session, hdc, "hidumper -s 1201 -a '-p Faultlogger -f ${cur.raw}'", 60_000)
if (r.ok && r.stdout.isNotBlank()) {
    val p = sx.nextPath("faultlog", "txt")
    Session.writeText(p, r.stdout)
    sx.register(p, "faultlog")
}
```

已验证 `hidumper -f` 能拉到本次崩溃全文（`ohd crash --name` 实测）。

### 56.5 闭环评估（诚实版）

| 环节 | 状态 | 说明 |
|---|---|---|
| 发现问题 | ✓ | monkey 12:41 捕获 cppcrash，指纹可追溯 |
| 留存证据 | **⚠ 刚修** | 修复前只有截图+节点树+脚本，缺 faultlog |
| 崩溃定位 | ⚠ 部分 | 系统栈有符号；应用侧无帧（平台层崩溃） |
| 符号化 | ✓ 就绪 | `sym` + auto-so + `--allow-mismatch`，本例无应用帧故未用上 |
| 打包交付 | ✓ | `bundle` 7/7 |
| 复现 | ✓ | `replay 002-monkey.ohd` |
| 验收益 | ✓ | `ab`（尚未真机验证） |

**结论：修复前不能自证闭环**（崩溃只有现场没有日志）；**修复后链路完整**，
但「崩溃时自动留存 faultlog」这一环**还没在真机崩溃里跑过一次**，不能算已验证。

### 56.6 本节锚定

- 日期：2026-10-18
- 会话：`.dsh/device/20261018-110846-monkey`（压测）、`.dsh/device/20261018-181536-crash`（崩溃全文）
- 代码：`caps/MonkeyCap.kt`（崩溃时留存 faultlog）
- 崩溃指纹：`8edfbaff33d38624`，`SIGSEGV(SEGV_MAPERR)@0x006bd40c1cf0853d`
- 待验证：monkey 真机崩溃一次，确认会话里出现 `faultlog` 类型产物

---

## 57. faultlog 留存验证通过 + 崩溃可复现（2026-10-18）

### 57.1 验证结果

会话 `20261018-181736-monkey`，18:17 启动，**27 分钟后崩溃**：

```
✗ [CRASH_FOUND] cppcrash-com.tencent.compose-20020221-20261018184441
faultlog 数: 1
278-faultlog.txt  808K
```

**§56 的修复生效**：崩溃时自动把 faultlog 拉进会话（走 `hidumper -s 1201 -a '-p Faultlogger -f'`）。

### 57.2 又一个「保存了但没告知」

faultlog 存下来了，但崩溃输出的「现场已保存」只列了截图/节点树/脚本三项。
用户看提示会以为没有崩溃日志——**证据在，但人不知道去哪找**。

已修：清单里加「崩溃日志」，manifest 也带 `faultlog` 字段。

这已是同一族问题的第三次：
1. 长时不落盘（§45）
2. 采样失败写 0（§53）
3. 留存了但输出不列（本节）

**共同点：内部状态与对外呈现不一致。**

### 57.3 崩溃可复现，且是同一个

两次独立压测（间隔 2 小时）捕获到**同一条崩溃**：

| | 第一次 12:41:17 | 第二次 18:44:41 |
|---|---|---|
| 信号 | SIGSEGV(SEGV_MAPERR) | SIGSEGV(SEGV_MAPERR) |
| 地址 | `0x006bd40c1cf0853d` | `0x006bd40c2e6b7c9d` |
| 栈 #00 | `napi_get_reference_value+44` | `napi_get_reference_value+44` |
| 栈 #01 | `StateListener::NotifyJS` | `StateListener::NotifyJS` |
| 耗时 | 1.5h | 27min |

**地址前缀 `0x006bd40c` 完全相同**，栈完全一致 → 同一个悬垂引用，稳定复现。

### 57.4 崩溃定性（不下结论，只列事实）

- 崩溃点在**无障碍状态监听**回调：`StateListener::NotifyJS` → `napi_get_reference_value`
- `SEGV_MAPERR` = 访问未映射地址，地址形态像**已释放对象里的指针**
- 崩溃前 9 秒内**反复切前台 8 次**
- 两轮压测分别有 43 / 15 次**越界拉回**（force-off + restart）

合理怀疑方向：越界拉回使 napi_ref 失效，回调仍持旧引用。
**但工具不下这个结论**——需要开发侧结合 `NotifyJS` 的引用生命周期判断。

### 57.5 闭环状态（本轮之后）

| 环节 | 状态 | 证据 |
|---|---|---|
| 发现问题 | ✓ | 2 次独立压测均捕获，可复现 |
| 留存证据 | ✓ **已验证** | faultlog 808K + 截图 + 节点树 + 脚本 |
| 崩溃定位 | ✓ | 系统栈自带符号，定位到 `napi_get_reference_value` |
| 符号化 | ✓ 就绪 | 本例无应用帧故未用；链路已在 §55 验证 |
| 打包交付 | ✓ | `bundle` 7/7 |
| 复现 | ✓ | `replay 002-monkey.ohd` |
| 验收益 | ✓ | `ab`（未真机验证） |

**闭环成立**。剩下唯一未真机验证的是 `ab` 与 `install`（受压测占用阻塞，本轮之后可补）。

### 57.6 本节锚定

- 日期：2026-10-18 18:17~18:44
- 会话：`.dsh/device/20261018-181736-monkey`（含 `278-faultlog.txt`）
- 代码：`caps/MonkeyCap.kt`（faultPath 声明/赋值/输出/manifest）
- 测试：61 项全绿
- 崩溃指纹：`cppcrash-com.tencent.compose-20020221-20261018184441`

---

## 58. install / ab 真机验证完成，bundle 闭环 5/7（2026-10-18）

### 58.1 `install`（首次真机验证）

```bash
ohd install entry-default-signed.hap
hap   entry-default-signed.hap
大小  203.0 MB
方式  覆盖安装
结果  成功
```

装完 `aa start` 起得来（pid 43565），覆盖安装路径通。

### 58.2 `ab`（首次真机验证）

用**同一个 hap** 跑 A/B，这是检验机制对不对的最干净方式：同包应该无显著差异。

第一轮（未修单位）：

```
│ DMA 峰值 │ 123328.0 MB │ 123948.0 MB │ +0.5% │
```

**123328 MB = 120 GB**——显然错。原因是 `mb()` 按 KB 换算，而 DMA 采样返回的是**字节**：

```kotlin
// 错：DMA 走 mb()，bytes 当成 KB
"%.1f MB".format(v / 1024.0)
```

已拆成两个换算函数并注释量纲差异：

```kotlin
private fun mb(v: Long?)      // PSS，单位 KB
private fun mbBytes(v: Long?) // DMA，单位字节
```

修后：

```
│ PSS 峰值 │ 433.6 MB │ 434.6 MB │ +0.2% │
│ PSS 均值 │ 378.2 MB │ 382.8 MB │ +1.2% │
│ DMA 峰值 │ 119.7 MB │ 123.2 MB │ +2.9% │
│ DMA 均值 │ 102.5 MB │ 107.0 MB │ +4.4% │
│ 崩溃     │ 0        │ 0        │ -     │
```

同包差异 0.2%~4.4%（噪声量级），机制合理。步数 133 vs 134 说明**同 seed 的点击序列一致**。

**已知局限**：「版本」列空——`bm dump -n` 在本设备返回空 versionName，只能靠 hap 文件名区分。

### 58.3 `bundle` 闭环

对崩溃会话打包：

| 必备件 | 状态 |
|---|---|
| 设备与构建指纹 | OK |
| 界面截图帧 | OK |
| **崩溃原始日志** | **OK** ← 本轮修复生效 |
| 指标时间序列 | OK |
| 可复现步骤 | OK |
| 应用日志窗口 | 缺失（monkey 不抓 hilog） |
| 符号化结果 | 缺失（本次崩溃无应用帧，无可符号化对象） |

**5/7**。两项缺失都不是 bug：
- hilog 窗口是 monkey 没做的增强，bundle 已如实标注
- 符号化缺失是因为栈里没有 libkn.so 帧

### 58.4 最终闭环状态

```bash
ohd install app.hap                     ✓ 真机验证
ohd monkey --bundle x --duration 3h     ✓ 崩溃捕获 + faultlog 留存
ohd sym --crash x --allow-mismatch      ✓ 工具就绪（本例无应用帧）
ohd bundle --session x                  ✓ 5/7，崩溃日志在
ohd replay steps.ohd                    ✓
ohd ab --a x --b y --bundle pkg         ✓ 真机验证
```

**七个能力全部真机跑过**，闭环成立。

### 58.5 本节锚定

- 日期：2026-10-18
- 设备：63R0226113004249（SGT-AL50，API 24）
- 代码：`caps/InstallCap.kt`（mbBytes）
- 验证：install 203MB 成功；ab 同包差异 ≤4.4%；bundle 5/7
- 会话：`.dsh/device/20261018-181736-monkey`

---

## 59. 崩溃时同时留存 hilog 窗口（2026-10-18）

### 59.1 缺口

§58 的 bundle 只有 5/7，缺「应用日志窗口」。bundle 只看 artifact kind：

```kotlin
Required("logs", "应用日志窗口", "无法判断异常发生前的运行轨迹")
...
kind == "hilog" -> "logs"   // 第 412 行：hilog 归到 logs 桶
```

所以 monkey 只要在崩溃时注册一条 `hilog` 产物即可，不用改 bundle。

### 59.2 hilog 命令的坑

| 尝试 | 结果 |
|---|---|
| `hilog -d` | 返回 0 行（该选项在这台设备无效） |
| `hilog -x -t app -z 5` | `Mutlti commands can't be used in combination [CODE: -31]` |
| `hilog -x \| tail -n 5` | ✓ 可用 |
| `hilog -x \| grep -c 'com.tencent.compose'` | 应用**未启动时 0 行，启动后 1813 行** |

结论：只能用 `-x` 单选项导出整个缓冲区，过滤和截断交给设备端管道。

### 59.3 实现

```kotlin
// 崩溃前的运行轨迹也要留：否则只知崩了，不知崩前在干什么
val r = Hdcs.sh(session, hdc, "hilog -x | grep '$tag' | tail -n 2000", 60_000)
if (r.ok && r.stdout.isNotBlank()) {
    val p = sx.nextPath("hilog", "txt")
    Session.writeText(p, r.stdout)
    sx.register(p, "hilog")
    logPath = p.toAbsolutePath().toString()
}
```

同时补了输出清单（「应用日志」）和 manifest（`hilog` 字段）——
避免重犯 §57.2 那个「存了但不告诉人」的错误。

### 59.4 验证受阻，如实记录

想用 `kill -11 <pid>` 快速制造崩溃，实测：

```
/bin/sh: kill: 59920: Operation not permitted
```

shell 用户无权杀应用进程，**快速验证这条路走不通**，只能等自然崩溃
（历史上 27 分钟 ~ 1.5 小时）。已起 2 小时压测等待。

**在自然崩溃复现之前，这一环不算已验证。**

### 59.5 本节锚定

- 日期：2026-10-18
- 代码：`caps/MonkeyCap.kt`（logPath 声明/抓取/输出/manifest）
- 命令：`hilog -x | grep <bundle> | tail -n 2000`
- 待验证：自然崩溃后会话里出现 `hilog` 类型产物，bundle 应达 6/7

---

## 60. `summary`：下班开跑、上班收网（2026-10-18）

### 60.1 需求

「长时间多次持续压测之后查看所有的测试结果概要，类似于下班开跑，上班收网看渔获。」

已有 `bundle` 是**单会话**深挖，缺的是**跨会话横扫**：
一晚上跑五轮，第二天要一眼看出哪轮崩了、哪轮 PSS 涨了、哪些证据齐。

### 60.2 实现

```bash
ohd summary [--since 24h] [--limit 30] [--json]
```

**只读磁盘**，不碰设备——查历史不该占用真机（压测可能还在跑）。

数据源三处，缺一不可：

| 来源 | 取什么 |
|---|---|
| 目录名 `<yyyyMMdd-HHmmss>-<cap>` | 开始时间、能力类型 |
| `manifest.json` | 时长、artifacts 种类、verdict |
| `*-samples.csv` | 步数、PSS 首末、采样数 |

输出：

```
│ 会话                   │ 类型   │ 开始        │ 时长  │ 步数  │ 崩溃   │ PSS 首末     │ 采样 │ 证据 │
│ 20261018-181736-monkey │ monkey │ 10-18 18:17 │ 26min │ 2948  │ ✗ 崩了 │ 372→318 MB   │ 80   │ 堆栈 │
│ 20261018-110846-monkey │ monkey │ 10-18 11:08 │ 92min │ 10144 │ -      │ 582→426 MB   │ 279  │ -    │
```

### 60.3 首次运行就抓到一个误判

`20261018-110846-monkey` 那轮**明明是崩溃退出的**（12:41 捕获 cppcrash，日志里有 `CRASH_FOUND`），
summary 却显示「-」。

原因不是 summary 写错，是**数据本身缺失**：那轮跑在 §56 修复之前，faultlog 没留存，
manifest 里也没有 verdict 字段——`manifest` 只有 `startedAt` / `finishedAt`，
**「跑满时长自然结束」和「崩溃提前退出」在磁盘上长得一模一样**。

这是「证据不足被当成结论」的典型：不是显示错误，是**没有依据却给出了确定的样子**。

### 60.4 补 verdict 链路

四层打通：

```kotlin
// Session：能力收尾时标注
var verdict: String? = null

// Main：写 manifest 时带上（extra 已有此参数，不用改结构）
s.writeManifest(dev, s.verdict?.let { mapOf("verdict" to it) } ?: emptyMap())

// MonkeyCap：判定处设置
session?.verdict = if (hit != null) "CRASH" else "OK"

// SummaryCap：读回来
crashed = hasFaultlog || verdict == "CRASH"
```

**向后兼容**：历史会话没有 verdict，退化成「有 faultlog 才算崩」，
即 §60.3 那个误判仍会复现——但至少新会话不会再错。

### 60.5 待验证

新压测 `20261018-200954-monkey` 已用新二进制启动，等自然崩溃后确认三件事：

1. 会话里有 `*-faultlog.txt` **和** `*-hilog.txt`
2. `manifest.json` 出现 `"verdict": "CRASH"`
3. `ohd bundle` 从 5/7 升到 6/7（补上应用日志窗口）
4. `ohd summary` 该轮显示「✗ 崩了」

**在这四点都验到之前，summary 与 verdict 只算「写完」，不算「可用」。**

### 60.6 本节锚定

- 日期：2026-10-18
- 代码：`caps/SummaryCap.kt`（新）、`Session.kt`（verdict）、`Main.kt`（manifest extra）、`caps/MonkeyCap.kt`（标注）
- 误判样本：`.dsh/device/20261018-110846-monkey`（92min / 10144 步 / 实际崩溃 / 无 faultlog）
- 待验证会话：`20261018-200954-monkey`

---

## 61. 全链路验证通过（2026-10-18）

### 61.1 §60.5 列的四点，逐条对上了

会话 `20261018-200954-monkey`，20:09 启动，**30 分钟后崩溃**（3036 步 / 87 次采样）：

| 待验项 | 结果 |
|---|---|
| 会话里有 faultlog 和 hilog | ✓ 「证据 2」 |
| manifest 出现 `verdict` | ✓ `"verdict": "CRASH"` |
| bundle 升到 6/7 | ✓ 「应用日志窗口 OK」 |
| summary 显示崩了 | ✓ `✗ 崩了` + `堆栈+日志` |

### 61.2 bundle 完整性对比

| 必备件 | 修复前 | 现在 |
|---|---|---|
| 设备与构建指纹 | OK | OK |
| 界面截图帧 | OK | OK |
| **应用日志窗口** | **缺失** | **OK** |
| **崩溃原始日志** | **缺失** | **OK** |
| 符号化结果 | 缺失 | 缺失 |
| 指标时间序列 | OK | OK |
| 可复现步骤 | OK | OK |

**5/7 → 6/7**。剩余「符号化结果」缺失是因为这次崩溃栈仍在 ArkUI/无障碍 native 链路
（`napi_get_reference_value` 一族），**栈里没有 libkn.so 帧**，没有可符号化的对象——
不是工具做不到，是这份数据里没有。

### 61.3 summary 现在的样子

```
│ 会话                   │ 类型   │ 开始        │ 时长  │ 步数 │ 崩溃   │ PSS 首末     │ 采样 │ 证据      │
│ 20261018-200954-monkey │ monkey │ 10-18 20:09 │ 30min │ 3036 │ ✗ 崩了 │ 2218→688 MB  │ 87   │ 堆栈+日志 │
│ 20261018-192634-monkey │ monkey │ 10-18 19:26 │ -     │ 4640 │ -      │ 780→2272 MB  │ 134  │ -         │
```

第二行是**被手动 kill 的**（换二进制重跑），显示「时长 -」——因为没有 `finishedAt`。
这正是期望行为：**没收尾的会话就该看起来像没收尾**，而不是伪装成正常结束。

### 61.4 闭环最终状态

```bash
ohd monkey --bundle x --duration 2h   → 崩了，faultlog + hilog 自动落盘，verdict=CRASH
ohd summary --since 12h               → 一眼看出哪轮崩了、内存怎么走
ohd bundle --session x                → 6/7，开发拿到即可开工
ohd sym --crash x --allow-mismatch    → 就绪（本例无应用帧）
ohd replay / ab / install             → 均已真机验证
```

**一条从「跑一夜」到「早上看渔获」的链路打通了。**

### 61.5 内存观察（顺带）

新装的 debug 包（203 MB，含调试符号）PSS 稳态 2.2~2.4 GB，
远高于旧包的 426 MB——**这是 debug 构建的代价，不是回归**。
崩溃瞬间回落到 688 MB（进程被回收）。

### 61.6 本节锚定

- 日期：2026-10-18 20:09~20:39
- 会话：`.dsh/device/20261018-200954-monkey`（3036 步 / 87 采样 / verdict=CRASH）
- 验证命令：`bundle --session`（6/7）、`summary --since 2h`
- 代码：`SummaryCap.kt` / `Session.verdict` / `Main.kt` / `MonkeyCap.kt`
- 测试：61 项全绿

---

## 62. smoke.sh 的 `$BIN（` 把全角括号吃进变量名（2026-10-18）

```bash
# 错
[ -x "$BIN" ] || { echo "找不到 ohd 可执行文件: $BIN（先跑 build.sh）"; exit 1; }

# 对
[ -x "$BIN" ] || { echo "找不到 ohd 可执行文件: ${BIN}（先跑 build.sh）"; exit 1; }
```

报错 `BIN�: unbound variable`——`�` 就是被吞进去的全角括号。

**bash 的变量名只认 ASCII 字母数字下划线，但 `$BIN（` 中的全角括号不会被当作边界**，
于是变量名变成 `BIN（`，`set -u` 直接报未定义。

**规则**：中文标点紧跟变量时必须写 `${VAR}`，不能写 `$VAR`。
全角括号、全角冒号、中文引号都算。已全量扫描 `smoke.sh`，仅此一处。

修完 `cd dist && ./smoke.sh` → 18 项「冒烟通过」。

---

## 63. 技术路线：JVM 还是 Native（2026-10-18 评估）

用户问：「现在是 kotlin/jvm 技术路线，能不能只在必需的地方用 jvm、其他用 native。」

### 63.1 先看收益有多大

| 场景 | 单次耗时 | JVM 启动占比 |
|---|---|---|
| `ohd tap`（一次点击） | hdc 调用 200~800ms | 200ms / 1000ms = **20%** |
| `ohd tree`（拉节点树） | 300~1500ms | **5~13%** |
| `ohd monkey`（压测 2h） | 单进程长跑 | **一次性，≈0** |
| `ohd summary`（读磁盘） | 50~300ms | **40~80%** ← 唯一明显的地方 |

**只有纯本地读盘的命令（summary/bundle/analyze）启动开销占比高**，
而它们恰恰是**人不频繁调用**的（一天几次）。

### 63.2 拆 Native 的成本

| 依赖 | JVM 实现 | Native 代价 |
|---|---|---|
| PNG 编解码 | `javax.imageio.ImageIO` | **无等价物**，要换 stb_image/skia |
| 文件操作 | `java.nio.file` | 全量换 okio / kotlinx-io |
| 进程调用 | `ProcessBuilder` | expect/actual 到 `posix_spawn` |
| JSON | kotlinx-serialization | ✓ 原生支持 |
| 测试 | JUnit | JVM-only，等于放弃 61 项测试 |

**图像那条是硬卡点**：ohd-image 的截图比对、网格、框选全在 ImageIO 上。

### 63.3 四条路对比

| 方案 | 代码改动 | 启动 | 分发 | 风险 |
|---|---|---|---|---|
| 保持 JVM（现状） | 0 | ~200ms | 需 Java 17 | 无 |
| **jlink 打包运行时** | 构建脚本 | ~200ms | 单目录 40MB | 低 |
| **GraalVM native-image** | 少量配置 | **~10ms** | 单文件 15~20MB | 中（反射注册、AWT headless） |
| KMP 重写为 Native | **大规模** | ~5ms | 单文件 | 高 |

### 63.4 结论

**不建议**为「快 200ms」重写成 KMP/Native——**成本远大于收益**，且图像能力会退化。

**真要解决「同事机器上没 Java」**，两条递进路径：

1. **先做 jlink**：`jlink --add-modules java.base,java.desktop` 打一个自带 JRE 的目录，
   同事解压即用。半天工作量，零代码改动。
2. **需要单文件再上 GraalVM**：代码不动，加 `native-image` 配置与反射注册
   （kotlinx-serialization 需 `@Serializable` 的 reflect-config），启动降到 10ms。

**只有当**「压测器要跑在资源极受限的设备上」或「要做成常驻守护进程被高频调用」时，
Native 的收益才真正成立——目前两个场景都没有。

### 63.5 本节锚定

- 日期：2026-10-18
- 当前：Kotlin/JVM 2.2.20，`installDist` 产出 `dist/lib/*.jar` + 启动脚本
- 实测启动开销：`time ./dist/ohd doctor` ≈ 200ms（含 JVM 启动）
- 代码：`dist/smoke.sh`（本 bug）、`ohd-image`（ImageIO 依赖集中处）

### 63.6 实测修正（同日）

| 命令 | 耗时 | 说明 |
|---|---|---|
| `ohd --help` | **63ms** | 纯 JVM 启动 + 打帮助 |
| `ohd doctor` | 1.3s | 几乎全是 hdc 探测，与 JVM 无关 |
| `dist/` 总计 | 27MB | jar + 启动脚本 |

**启动只差 58ms**（63ms vs Native 约 5ms），为这点差距重写整个工具完全不值得。
63.4 的结论不变，而且比原先估得更站得住。

---

## 64. 符号结果必须落进崩溃会话，以及一个我造的数据损坏（2026-10-18）

### 64.1 起因

用户问：「符号化结果是全部都是系统堆栈的话，也不算失败吧。」

对——但查下去发现真正的问题是**另一件事**：`ohd sym` 一直把它自己的产物
注册在 `Hdcs.session(ctx, "sym")` 这个**新会话**里，而 `bundle` 读的是**崩溃所在会话**。
两边永远对不上，所以 bundle 永远显示「符号化结果 缺失」。

### 64.2 修法：`sym --session`

```bash
ohd sym --crash 288-faultlog.txt --session .dsh/device/<崩溃会话>
```

代码里把 `Hdcs.session(...)` 换成「有 `--session` 就用它，没有才新建」。

### 64.3 紧接着我自己造了个数据损坏

第一次跑 `--session` 后，bundle 的七项里**只剩两项 OK**：

```
│ 设备与构建指纹 │ 缺失 │
│ 界面截图帧     │ 缺失 │
│ 应用日志窗口   │ 缺失 │
│ 崩溃原始日志   │ OK   │
│ 符号化结果     │ OK   │
│ 指标时间序列   │ 缺失 │
```

原因：`SymCap` 结尾无条件调用

```kotlin
session?.writeManifest(null, mapOf("source" to sourceName))
```

`writeManifest` 是**覆盖写**，于是 monkey 辛苦攒下的 290 个 artifacts、
device 指纹、startedAt/finishedAt 全被一个只有 symbols 的 manifest 洗掉。

**这是本次最严重的一个 bug，而且是我刚加的 `--session` 触发的**——
新功能把老数据吃了。

修：`--session` 模式下**不写 manifest**，只注册 artifact。

```kotlin
if (ctx.options.str("session") == null) session?.writeManifest(null, mapOf("source" to sourceName))
```

### 64.4 恢复

manifest 已被覆盖，只能按目录实际文件重建（290 个 artifacts + device 指纹 + verdict）。
**教训：注册产物和覆盖元数据是两件事，新功能不能顺手改别人的账本。**

### 64.5 关于「全系统栈不算失败」

这条也实现了：`BundleCap` 在判 symbols 缺失前先看崩溃栈——

```kotlin
val appFrames = rep.nativeFrames.filter { !it.soPath.startsWith("/system") && !it.soPath.startsWith("/vendor") }
val named = rep.nativeFrames.count { !it.inlineSymbol.isNullOrBlank() }
if (appFrames.isEmpty() && named > 0) symbolsOk = true   // 系统 so 自带导出符号
```

**但本轮这次崩溃没走到这个分支**——它栈里有 4 帧 libkn.so，是真需要符号化的。
判定逻辑没错，只是这次不适用。

---

## 65. 首次完整符号化：Kotlin 函数名 + 源码路径（2026-10-18）

### 65.1 条件终于凑齐了

新装的 debug 包崩溃，栈里 buildId 是 `b98ff9a1...`——**与 `entry/libs/arm64-v8a/libkn.so` 完全一致**，
不用 `--allow-mismatch` 就能正常符号化。

```bash
ohd sym --crash 288-faultlog.txt --session <崩溃会话> --limit 40
```

40 帧全部 OK，输出形如：

```
│ 2 │ 0x1b434d0 │ libkn.so │ SkSemaphore::osWait()                          │ SkSemaphore.cpp:0        │ OK │
│ 3 │ 0x1c5e2ec │ libkn.so │ SkTypeface_FreeType::onCharsToGlyphs(...)      │ SkFontHost_FreeType.cpp  │ OK │
│ 5 │ 0x1f94cd4 │ libkn.so │ FontConfig_OHOS::buildRangeIndexFromCoverage() │ FontConfig_ohos.cpp:0    │ OK │
│ 37│ 0x1971d4c │ libkn.so │ <inlined-lambda>                               │ /Users/xuxingwang/KMP/...│ OK │
│ 38│ 0x197399c │ libkn.so │ kfun:androidx.compose.runtime.Recomposer.Re... │ /Users/xuxingwang/KMP/...│ OK │
│ 39│ 0x181821c │ libkn.so │ kfun:kotlin.Function1#invoke(1:0){}1:1-tramp…  │ ./[K][Suspend]Functions  │ OK │
```

**比预期好**：不但有 C++ 符号，还有 **Kotlin mangled 名**和 **本机源码绝对路径**。
说明 debug 包的 `.debug_info` 是完整的。

### 65.2 崩溃性质变了

这轮崩溃**不是**之前那个无障碍问题：

- 崩溃线程卡在 `FontConfig_OHOS::buildRangeIndexFromCoverage()`
  → `SkTypeface::unicharToGlyph`
  → `SkTypeface_FreeType::onCharsToGlyphs`
  → `SkSemaphore::osWait`
  → `sem_timedwait` → `__timedwait_cp`
- 栈顶两帧是 `ld-musl` 的等待原语

**方向**：字体配置扫描覆盖率时陷入等待。与 §56/§57 那个
`napi_get_reference_value` 崩溃是**两回事**（换了 debug 包后暴露的）。

### 65.3 bundle 达成 7/7

| 必备件 | 状态 |
|---|---|
| 设备与构建指纹 | OK |
| 界面截图帧 | OK |
| 应用日志窗口 | OK |
| 崩溃原始日志 | OK |
| 符号化结果 | OK |
| 指标时间序列 | OK |
| 可复现步骤 | OK |

**首个完整闭环会话**：`.dsh/device/20261018-200954-monkey`

### 65.4 本节锚定

- 日期：2026-10-18 20:09~21:00
- 会话：`.dsh/device/20261018-200954-monkey`（3036 步 / 87 采样 / verdict=CRASH / **7/7**）
- buildId：`b98ff9a10decf86cbc090d83b3f34f17171f6d84`（与 entry/libs 一致）
- 代码：`caps/SymCap.kt`（--session）、`caps/BundleCap.kt`（全系统栈判定）
- 崩溃栈顶：`FontConfig_OHOS::buildRangeIndexFromCoverage`

---

## 66. jlink 自带运行时：同事不必装 JDK（2026-10-18）

§63 评估后选的最轻方案，半天工作量、零代码改动。

### 66.1 做法

```bash
jlink --add-modules java.base,java.desktop,java.logging,java.xml,jdk.unsupported \
      --output dist/runtime --strip-debug --no-header-files --no-man-pages --compress=2
```

| 模块 | 为什么需要 |
|---|---|
| `java.base` | 基础类库 |
| **`java.desktop`** | ImageIO（截图比对、网格、框选全靠它） |
| `java.logging` / `java.xml` | 间接依赖 |
| `jdk.unsupported` | `sun.misc.Unsafe`，序列化库可能用到 |

结果 **40MB**（完整 JDK 约 300MB）。

### 66.2 启动脚本要自适应两种形态

第一版写死了 `ROOT/ohd-cli/build/install/ohd/lib`，解压到别处就找不到 jar。
改成按目录结构判断：

```bash
if [ -d "$SELF_DIR/lib" ]; then          # 打包形态：lib/ 与脚本同级
  LIB="$SELF_DIR/lib"
elif [ -d "$SELF_DIR/../ohd-cli/..." ]; then  # 开发形态：脚本在 dist/ 下
  LIB="$SELF_DIR/../ohd-cli/build/install/ohd/lib"
```

运行时同理：优先 `runtime/bin/java`，没有才退回系统 `java`。

### 66.3 package.sh

```bash
./dist/package.sh
→ dist/ohd-0.1.0-darwin-arm64.zip   27MB
```

内容：`ohd` 脚本 + `lib/`（jar）+ `runtime/`（jlink 运行时）+ `README.txt`。

**验证**：解压到 `/tmp` 直接 `./ohd --help` 通过，`summary` 在没有会话时给的是
`NO_SESSION` 提示而非崩溃——说明运行时与类路径都对。

### 66.4 仍然推荐保留 JVM 形态

用 jlink 解决「同事没 JDK」，但**不改成 Native**：§63 实测 `--help` 仅 63ms，
为 58ms 的差距重写图像栈不划算。

### 66.5 本节锚定

- 日期：2026-10-18
- 产出：`dist/package.sh`、`dist/ohd`（自适应）、`dist/runtime/`（40MB）
- 包：`dist/ohd-0.1.0-darwin-arm64.zip`（27MB，解压即用）
- 验证：`/tmp/pkgtest` 解压后 `--help` / `summary` 均正常

---

## 67. `--keep-going`：崩溃后重启继续，支持 12 小时长跑（2026-10-18）

### 67.1 问题

用户问：「支持跑满 12 小时吗，哪怕遇到崩溃。」

原来的行为是**一崩就停**：

```kotlin
if (fresh.isNotEmpty()) {
    hit = fresh.first()
    break        // 整轮结束
}
```

后果：应用每 30 分钟崩一次，一晚上只测到**一个**崩溃，剩下 11.5 小时白跑。
而且这个 `break` 藏在循环里，从输出上完全看不出「是跑满了还是崩了」——
这也是 §60.3 那个 summary 误判的深层原因。

### 67.2 改法

崩溃时：**留现场 → 重启应用 → 继续跑**。

```kotlin
val freshHit = fresh.first()
crashes += freshHit
if (hit == null) hit = freshHit          // 首个崩溃作为主结论
val saved = captureCrash(freshHit, hdc, session, bundle)   // faultlog + hilog
if (!keepGoing) break
runCatching { hdc.shell("aa force-stop $bundle", 15_000) }
runCatching { hdc.shell("aa start -b $bundle -a $mainAbility", 15_000) }
Thread.sleep(2_000)
cachedClickables = emptyList()           // 重启后界面全变，坐标缓存必须清
continue
```

三处关键：

1. **`captureCrash` 抽成函数**——每崩一次调一次，不是只在结尾调
2. **清坐标缓存**——重启后界面结构变了，旧坐标会点空
3. **`hit` 只记首个**——主结论保持「第一次崩溃」，但 `crashes` 记全部

### 67.3 输出

新增两处：

```
崩溃次数   3（已逐次留存现场）
```

JSON 里：

```json
{"crashCount": 3, "crashes": [{"kind":"cppcrash","module":"com.tencent.compose","name":"..."}, ...]}
```

### 67.4 12 小时的可行性

| 项 | 评估 |
|---|---|
| 磁盘 | 每次崩溃约 808K faultlog + ~1MB hilog；12 小时崩 20 次 ≈ 40MB，可接受 |
| 采样 CSV | 30s 一次 × 12h = 1440 行，约 60KB |
| layout 帧 | 每步都存会爆；靠 `--layout-ttl` 与缓存控制（默认 3 步），12h 约数千帧 × 几十 KB |
| 进程稳定性 | 单进程长跑，JVM 已稳定（前面 2h 跑过） |
| 崩溃恢复 | 每次崩溃强制重启，不会陷入「崩了还在点」的无效循环 |

**真实风险不是内存或磁盘，是 layout 帧数量**——12 小时按 110 步/分能到 8 万步，
需要确认帧存储策略是否会在长跑后把磁盘吃满。**这条待 12 小时实测验证。**

### 67.5 待验证

已起 25 分钟 `--keep-going` 跑（会话 `20261018-212854-monkey`），要看：

1. 崩溃后**没有退出**，步数继续增长
2. 会话里出现**多个** `*-faultlog.txt`
3. 结尾统计显示 `崩溃次数 N`
4. `manifest.verdict` 仍是 `CRASH`

### 67.6 关于 JIT

用户同时问「支持 JIT 优化性能吗」。**JVM 默认就开 C1/C2**，无需额外开关。
但 ohd 的耗时大头是 hdc 进程调用（200~800ms/次），CPU 计算占比极低，
**JIT 预热对本工具几乎无感**。真正的提速手段是减少 hdc 往返
（批量注入、layout 缓存、坐标复用），这些已经做了。

### 67.7 本节锚定

- 日期：2026-10-18
- 代码：`caps/MonkeyCap.kt`（keepGoing / crashes / captureCrash / 统计）
- 选项：`--keep-going`
- 验证会话：`20261018-212854-monkey`（25m 跑，待确认崩溃后继续）

---

## 68. `--keep-going` 上线当天就抓出重复检出（2026-10-19）

### 68.1 现象

90 分钟长跑，日志显示 **8 次崩溃**，每次都有独立 faultlog 文件：

```
✗ 崩溃 #1：appfreeze appfreeze-com.tencent.compose-20020221-20261018223026
✗ 崩溃 #2：appfreeze appfreeze-com.tencent.compose-20020221-20261018223026
...（#3~#8 名字完全一样）
```

`336-faultlog.txt` 与 `407-faultlog.txt` 的 **md5 完全相同**（`9f03ba56...`）——
**同一个崩溃被当成 8 次**。

### 68.2 根因

```kotlin
val before = Faults.list(session, hdc)                      // 循环外，只取一次
val fresh = Faults.diff(before, Faults.list(session, hdc))  // 每轮和同一个基线比
```

`before` 是 `val` 且**从不更新**。第一次崩溃后它永远留在基线里，
于是每 `check-every` 步就「重新发现」一次，每次还新建一个 faultlog 文件。

**这个 bug 在 `--keep-going` 之前不会暴露**——原来一崩就 `break`，根本没有第二次检查机会。
新功能打破了老代码的隐含假设（崩溃只可能发生一次）。

### 68.3 修复

```kotlin
var before = Faults.list(session, hdc)   // 改 var
...
before = Faults.list(session, hdc)       // 留完现场，更新基线
if (!keepGoing) break
```

### 68.4 同类问题清单

`--keep-going` 引出的三个问题：

| # | 问题 | 性质 |
|---|---|---|
| 67 | 一崩就停，12h 只能测一个崩溃 | 功能缺失 |
| 68 | 基线不更新，一次崩溃报 N 次 | **老代码隐含假设被新用法打破** |
| — | 崩溃后不清坐标缓存会点空 | 状态一致性 |

**共性**：新能力把「以前不可能发生的路径」变成常态，
那些路径上的老代码从没被检验过。

### 68.5 待验证

新跑（会话 `20261019-063850-monkey`，60m，check-every 30）：
同一个 appfreeze 应**只报一次**，faultlog 文件数应等于真实崩溃次数。

### 68.6 本节锚定

- 日期：2026-10-19 06:44
- 重复样本：`.dsh/device/20261018-215409-monkey/336-faultlog.txt` 与 `407-faultlog.txt`（md5 相同）
- 修复：`caps/MonkeyCap.kt`（`var before` + 崩溃后更新）
- 验证会话：`20261019-063850-monkey`

---

## 69. 60 分钟长跑：内存是锯齿不是泄漏（2026-10-19）

### 69.1 数据

会话 `20261019-063850-monkey`，60 分钟，6131 步，94 次采样：

| 步数 | PSS | DMA |
|---|---|---|
| 60 | 409 MB | 79 MB |
| 1256 | 315 MB | 63 MB |
| 2448 | 2469 MB | 894 MB |
| 3048 | 2880 MB | 1074 MB |
| 3664 | 631 MB | 188 MB |
| 4258 | 1757 MB | 633 MB |
| 4839 | 265 MB | 55 MB |
| 6072 | 1493 MB | 511 MB |

**5 次大幅回落**（低于前值 70%），PSS 与 DMA **同步涨跌**。

### 69.2 判定：锯齿型，非泄漏

- 单调增长才是泄漏；这里反复冲高又回落，说明**回收机制在工作**
- DMA 同步波动 → 涨的是**图形/纹理缓存**，不是堆对象泄漏
- 峰值 3208 MB 吓人，但那是 debug 包 + 图像缓存叠加，随后能掉到 265 MB

### 69.3 仍未验证的那一件事

这轮 60 分钟**一次没崩**（`未发现新故障`），§68 的基线修复**没被真实崩溃检验**。

`--keep-going` 两条路径：
- 「崩溃后继续跑」——§67 已由 8 次重复检出间接证明循环没退出 ✓
- 「同一崩溃不重复报」——**待验**

### 69.4 本节锚定

- 日期：2026-10-19 07:40
- 会话：`.dsh/device/20261019-063850-monkey`（60min / 6131 步 / 94 采样 / 0 崩溃）
- CSV：`003-monkey-samples.csv`
- 结论：PSS 409→1760 MB，峰 3208 MB，5 次大幅回落，判锯齿

---

## 70. `--keep-going` 完整验证通过，转入 10 小时长跑（2026-10-19）

### 70.1 验证结果

会话 `20261019-074122-monkey`，`--duration 90m --keep-going`：

| 项 | 结果 |
|---|---|
| 时长上限 | 1.5h |
| **崩溃** | cppcrash，08:25 发生（第 44 分钟） |
| **崩溃后** | **继续跑到 9472 步**，09:11 跑满 |
| 崩溃次数 | **1**（§68 基线修复生效） |
| faultlog 文件 | **1**（不再是 8 个同 md5） |
| 越界拉回 | 43 次 |

**两条路径都验到了**：
- 崩溃后没退出，跑满时长 ✓
- 同一崩溃只报一次 ✓

### 70.2 完整链路

```
跑 → 崩溃 → 留 faultlog + hilog → 重启应用 → 继续跑
                                    ↑ 基线更新，不重复报
```

### 70.3 转入 10 小时

```bash
ohd monkey --bundle com.tencent.compose --duration 10h \
  --interval 0 --batch 10 --sample-every 60 --check-every 30 --keep-going
```

- 会话：`20261019-091151-monkey`，09:11 启动，预计 19:11 结束
- 日志：`/tmp/monkey-10h.log`

**10 小时要盯的风险**：
1. **磁盘**：每崩溃约 1.8MB（faultlog+hilog），layout 帧受 `--layout-ttl` 控制但仍可能数万帧
2. **PSS**：§69 判锯齿，需确认 10 小时后仍在锯齿而非单调
3. **进程存活**：单进程 10 小时

### 70.4 本节锚定

- 日期：2026-10-19 09:13
- 验证会话：`.dsh/device/20261019-074122-monkey`（1.5h / 9472 步 / 崩溃 1 次后继续）
- 长跑会话：`.dsh/device/20261019-091151-monkey`（10h，进行中）
- 代码：`caps/MonkeyCap.kt`（keep-going + 基线更新）

---

## 71. 10 小时跑在第 42 分钟被 DSH 重启带走（2026-10-19）

### 71.1 现象

用户报「设备不跑了」。查下来：

| 检查项 | 结果 |
|---|---|
| monkey 进程 | **0**（已消失） |
| 日志最后一行 | `[4399] swipe ...`，**无任何错误** |
| 日志最后修改 | **09:53** |
| 设备连接 | 在线（`63R0226113004249`） |
| 磁盘 | 892 GB 可用 |
| 错误行 | **0**（无 exception / OOM / killed） |
| 系统 uptime | 20 天（**没重启**） |
| 后台 job | `job_list` **空** |
| `dsh-desktop/` 目录 mtime | **09:53**（Cookies、Session Storage 全部同时刻重写） |

### 71.2 结论：不是设备问题，是 DSH 重启

日志停在 09:53，`dsh-desktop` 的 Electron 状态文件也在 09:53 被重写，
job 表同时清空——**DSH 在 09:53 重启，把后台 job 连同它的子进程一起终止了**。

monkey 是 `bash -c "... ./dist/ohd monkey ..."` 启动的，**是 DSH 的子孙进程**。
DSH 一重启，整棵进程树被回收，日志因此没有错误——它不是失败的，是被杀的。

**这不是 ohd 的缺陷**，但在「跑 10 小时」这个场景下是**真实风险**：
长跑的生命周期不该绑在交互式工具的生命周期上。

### 71.3 正确做法：让长跑脱离父进程

```bash
nohup setsid ./dist/ohd monkey --bundle com.tencent.compose --duration 10h \
  --interval 0 --batch 10 --sample-every 60 --check-every 30 --keep-going \
  > /tmp/monkey-10h.log 2>&1 < /dev/null &
disown
```

- `setsid` 创建新会话，**脱离 DSH 的进程组**
- `nohup` 挡 SIGHUP
- `< /dev/null` 断开 stdin
- `disown` 从 shell 作业表移除

这样 DSH 重启、浏览器刷新、终端关闭都不影响长跑。

### 71.4 顺带确认

- **`--keep-going` 本身没问题**：这次不是崩溃导致的退出，是被外部杀掉的
- **§67/§68 的修复仍然有效**（90 分钟验证跑已证明）
- PSS 走到 4399 步时仍是正常锯齿（§69 结论不变）

### 71.5 本节锚定

- 日期：2026-10-19 09:59
- 中断会话：`.dsh/device/20261019-091151-monkey`（4399 步，42 分钟，被外部终止）
- 证据：`/tmp/monkey-10h.log` mtime 09:53；`~/Library/Application Support/@deepseek-ai/dsh-desktop/*` mtime 09:53
- 修正：长跑用 `nohup setsid ... &` 脱离 DSH

---

## 72. macOS 没有 `setsid`：用 `start_new_session` 真正脱离（2026-10-19）

### 72.1 现象

照 §71.3 的命令重启长跑，直接失败：

```bash
nohup: setsid: No such file or directory
```

`setsid(1)` 是 Linux util-linux 的独有命令，macOS 没有。退化成 `nohup ... &`
只能挡 SIGHUP，**进程仍在 DSH 的会话/进程组里**，DSH 重启照样被 SIGHUP 之外的组信号带走——
§71 的教训等于没解决。

### 72.2 做法：`start_new_session=True`

用 Python 在 exec 前对子进程调用 `setsid()`：

```python
import subprocess
log = open('/tmp/monkey-10h.log', 'wb', 0)
subprocess.Popen([...完整的 ohd monkey 参数...],
                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                 start_new_session=True, cwd='/Users/xuxingwang/KMP/ohd')
```

父脚本（python3）立即退出，重启后子进程被 launchd 接管，不再挂在 DSH 的会话上。

### 72.3 验证脱离

```python
os.getsid(85704) == 85704   # 自己是会话首进程
```

对照当时发起命令的 shell 会话（sid 85953）不同，确认真脱离了 DSH 的会话。

### 72.4 本次长跑

- 会话：`.dsh/device/20261019-100357-monkey`，10:03 启动，预计 20:03 结束
- 进程：pid 85704（`ohd.cli.MainKt monkey ...`），sid = 85704
- 日志：`/tmp/monkey-10h.log`；上一轮被中断的日志留档 `/tmp/monkey-10h-interrupted-0911.log`
- 参数与 §70.3 一致：`--duration 10h --interval 0 --batch 10 --sample-every 60 --check-every 30 --keep-going`

### 72.5 本节锚定

- 日期：2026-10-19 10:06
- 仓库：`/Users/xuxingwang/KMP`（非 git 仓库，无分支/commit）
- 文件：`.agents/scripts/dev/DESIGN.md`（本节）、`ohd/dist/ohd`、`/tmp/monkey-10h.log`
- 设备：SGT-AL50 / 63R0226113004249 / API 24 / 1280x2832

---

## 73. Windows 打包与启动脚本：两种形态一套脚本（2026-10-19）

### 73.1 动机

§66 的 jlink 分发只做了 macOS：`dist/package.sh` 依赖 `uname` + `zip`，Windows 同事拿不到包，
只能退回源码形态（要 JDK 17 + gradle）。跨平台验收清单 §10 当时写着「暂无 `package.bat`（待办）」。

### 73.2 改动

`dist/ohd.bat` 从「只转发 installDist 的 launcher」改为和 `dist/ohd` 同构的**自适应**启动器：

- 同目录有 `lib\` → 打包形态，用 `runtime\bin\java.exe`；
- 否则退回 `dist\..\ohd-cli\build\install\ohd\lib` 开发形态；
- 都没有才报错，并分别提示「先 build」/「先 package」。

`dist/package.bat`（新增）逐步对标 `package.sh`：`build.bat` → `jlink` → 组装 `runtime/` + `lib/` + `ohd.bat` + `smoke.bat` + `README.txt` → `Compress-Archive`。
平台名用 `%PROCESSOR_ARCHITECTURE%` 映射成 `amd64`/`arm64`/`x86`，产物 `dist\ohd-<版本>-windows-<架构>.zip`。

### 73.3 顺手修的 bug

`package.sh` 第 7 行原本 `grep -m1 '^version'`，但 `build.gradle.kts` 里是 `    version = "0.1.0"`（有缩进），**永远匹配不到**，
靠 `${VERSION:-0.1.0}` 兜底才对 —— 版本号一旦改就不是那个值了。改成 `^[[:space:]]*version *=`，实测提取 `0.1.0` 正常。

### 73.4 未验证项（需 Windows 同事回填）

- `package.bat` 未在真 Windows 上跑过：`findstr /r /c:"version *="` 取版本、`chcp 65001` 写中文 README、`Compress-Archive` 路径含空格
- 新版 `ohd.bat` 未在 Windows 上跑过（macOS 侧 `dist/ohd` 逻辑同构，可参考）
- `Start-Process -RedirectStandardOutput` 的脱管命令（CROSS-PLATFORM §11.2）同样待实测

### 73.5 本节锚定

- 日期：2026-10-19 10:10
- 仓库：`/Users/xuxingwang/KMP`（非 git 仓库，无分支/commit）
- 文件：`ohd/dist/ohd.bat`、`ohd/dist/package.bat`（新增）、`ohd/dist/package.sh`、`ohd/CROSS-PLATFORM.md` §10–§12、
  `~/.agents/skills/ohd-device-verify/SKILL.md`
- 设备：SGT-AL50 / 63R0226113004249 / API 24 / 1280x2832

---

## 74. 文档归位：`DESIGN.md` 迁入 `ohd/`（2026-10-19）

### 74.1 背景

到 §73 为止，ohd 的代码（`ohd-core`/`ohd-cli`/`ohd-image`）、脚本（`dist/`）、用户文档（`README.md`、`CROSS-PLATFORM.md`）都在 `ohd/` 下，
**只有这份设计/排查日志留在 workspace 根的 `.agents/scripts/dev/`**——最初的落点（§0 头部的「目标落点」）是脚本目录的附属文档，
后来长到 4700+ 行、成了本项目的主文档，位置却一直没跟着改。

### 74.2 做了什么

- `mv .agents/scripts/dev/DESIGN.md ohd/DESIGN.md`（`.agents/scripts/dev/` 随之变空并删除）
- 补两处引用：
  - `ohd/README.md` 第 5 行：`[DESIGN.md](DESIGN.md)`（原为 `../.agents/scripts/dev/DESIGN.md`）
  - `ohd-cli/src/main/kotlin/ohd/cli/Main.kt:286` 帮助文本：「见 ohd/DESIGN.md」
- 文首加迁移指针行，声明历史正文里的旧路径只作追溯
- 本节即迁移记录（追加，不改写 §0–§73 任何正文）

### 74.3 影响与注意

- 历史锚定（`2026-10-17`、`2026-10-18` 各节写的 `.agents/scripts/dev/DESIGN.md`）**保持原样**，按 append-only 规则不改；
  日期 + 本节 + 文首指针已足够把「当时在哪」与「现在在哪」对齐。
- `Main.kt` 改的是帮助文案，**已构建的 `ohd/dist` 二进制里仍是旧路径**，下次出包才会更新（未主动编译）。
- `~/.agents/skills/ohd-device-verify/SKILL.md` 与 `.dsh/handoff/*` 里的旧路径引用属全局/历史记录，未动。
- 迁移后 `ohd/` 自成一体：代码 + 脚本 + 用户文档 + 设计日志 + `.dsh/device` 证据，一个目录可完整交付。

### 74.4 本节锚定

- 日期：2026-10-19 10:16
- 仓库：`/Users/xuxingwang/KMP`（非 git 仓库，无分支/commit；迁移为文件系统 mv，无版本历史可保留）
- 文件：`ohd/DESIGN.md`（新位置）、`ohd/README.md:5`、`ohd/ohd-cli/src/main/kotlin/ohd/cli/Main.kt:286`
- 设备：SGT-AL50 / 63R0226113004249 / API 24 / 1280x2832

---

## 75. 收尾：清理口径 + 三个待迭代问题（2026-10-19）

建设任务到此收尾，后续按 bug 迭代。本节只记录**本轮的清理动作**和**跑出来但没修的问题**。

### 75.1 清理：25.6G → 795M

删除（均已核实绝对路径）：

| 对象 | 大小 | 理由 |
|---|---|---|
| `.dsh/device/20261018-073915-log/001-hilog.txt` | 13.4 GB | 孤儿 log 会话（见 75.4） |
| `.dsh/device/20261018-082050-log/001-hilog.txt` | 12.9 GB | 同上 |
| `dist/runtime/`、`dist/ohd-0.1.0-darwin-arm64{,.zip}` | 108 MB | `dist/package.sh` 可重生 |
| `.gradle/`、`.kotlin/`、`ohd-{core,cli,image}/build/` | 11 MB | 构建缓存 |

两个 log 会话的 `manifest.json`（766B）保留，作为「该会话存在过」的记录。

`.gitignore` 补齐：`dist/runtime/`、`dist/ohd-*/`、`dist/*.zip`、`.dsh/`、`*.hprof`、`hs_err_pid*.log`。
**注意** `ohd-cli/build` 的删除必须等长跑结束——JVM 以 `-cp ohd-cli/build/install/ohd/lib/*` 惰性加载类，中途删会让跑着的进程 `NoClassDefFoundError`。

另：`dist/.dsh/`（29MB）是早期在 `dist/` 目录里直接跑命令留下的证据，已被 `.gitignore` 的 `.dsh/` 覆盖，未删。

### 75.2 `--duration 10h` 实际只跑了 17 分钟：默认 `--max-steps 100_000`

§72 启动的「10 小时长跑」在 100,008 步结束（`verdict: OK`，崩溃 0），总步频 5793.6 步/分：

```
注入事件  100008 步     崩溃次数 0      步频 5793.6 步/分
采样      27 个（失败 1521 次）          脱困 1474 次（越界拉回 7）
PSS       977.4 MB → 1.98 GB
```

原因在 `ohd-core/.../caps/MonkeyCap.kt:93`：

```kotlin
val maxSteps = ctx.options.int("max-steps", 100_000)
```

`--duration` 只在步数没到上限前有效；`--interval 0` 把步频拉到 5800，17 分钟就撞上限，且**没有任何提示**。

**迭代建议**（未实施）：① `duration` 与 `max-steps` 冲突时打警告；② `--max-steps 0` 表示不限；
③ 或按 `duration × 实测步频` 自动放大上限。在此之前长跑必须显式带 `--max-steps 5000000` 之类。

### 75.3 采样失败率 98%

27 份成功 / 1521 次失败。高步频下 `dumpmeminfo` 抢不到设备是可能原因，但当前实现**只在汇总里给个数**，
不影响 `verdict`，也不告警——等于长时间跑下来内存曲线是稀疏的，`PSS 977MB → 1.98GB` 这条要打问号。
**迭代建议**：失败率超阈值时在汇总里升格为 `WARN`，并把失败原因归类输出。

### 75.4 孤儿 log 会话能写 25GB

两个 2026-10-18 的 log 会话（07:39 / 08:20 创建）里 hilog 单文件 13.4GB / 12.9GB，一直写到 20:12。
即会话已结束但 hilog 采集没停，`log`/`dump` 类能力缺**大小上限**与**孤儿进程回收**。
**迭代建议**：`log` 默认 `--max-size 2G` 自动截断；进程退出/超时后确保 `hdc ... hilog` 子进程被 kill。

### 75.5 本节锚定

- 日期：2026-10-19 10:25
- 仓库：`/Users/xuxingwang/KMP`（非 git 仓库；本轮清理为文件系统操作，无版本历史）
- 文件：`ohd/.gitignore`、`ohd/DESIGN.md`（本节）、`ohd-core/src/main/kotlin/ohd/core/caps/MonkeyCap.kt:93`、
  会话 `ohd/.dsh/device/20261019-100357-monkey/`
- 设备：SGT-AL50 / 63R0226113004249 / API 24 / 1280x2832

---

## 76. 交接给下一个模型：`LLM_CONTEXT.md`（2026-10-19）

### 76.1 问题

收尾时问「下次让大模型接着迭代，目录里有没有能当上下文的文档？」——逐份盘点后答案是**不称职**：

| 文档 | 为什么不适合当首读 |
|---|---|
| `DESIGN.md` | 230KB ≈ 70k tokens，整篇塞进去吃掉半个窗口；内容是逐日流水，不是现状摘要 |
| `README.md` | 用户向，且进度表停在「M1 观测层」，把 `tap/crash/sym/replay/diff/bundle/…` 全列在「规划中」（其实早已实现） |
| `CROSS-PLATFORM.md` | 只讲跨平台验收，不讲架构与决策 |
| `~/.agents/skills/ohd-device-verify/` | 讲「怎么用」，不讲「怎么改」 |
| `.dsh/handoff/*` | 会话级，跨 workspace，且会被新会话的 LATEST 顶掉 |

### 76.2 做法

新增 `ohd/LLM_CONTEXT.md`（约 150 行，可整篇加载），内容只保留「接手必需」：

1. 这是什么 + 现在到哪了（建设期收尾、进入迭代期）
2. 30 秒上手命令
3. 目录与关键文件（改能力要动哪两个文件）
4. 24 个能力全景
5. **不可违背的约定**（禁主动编译、输出契约、退出码、证据自洽、平台隔离）
6. 已知坑表（11 条，全部来自真实踩坑）
7. 待迭代 backlog（6 项，含 §75 三条）
8. **DESIGN 定点索引**：按「想知道什么 → 读哪节」映射，读法给到 `grep` + `sed` 命令
9. 改一项改动的标准动作（静态校验 → 构建（需明确要求）→ 真机 → smoke）
10. 下次会话首句模板

### 76.3 顺带修的文档漂移

`README.md`：补 `LLM_CONTEXT.md` 链接；进度表从「M1 + 规划中」改写为「24 个能力，建设期已收尾」的分组表。

**仍未修**（记入 backlog）：`Main.kt:247` 起的能力帮助里，
`dmabuf/soak/analyze/repro/report` 同时出现在「交付（M3）」和「规划中（M3+）」两处，
且缺 `install`/`ab`/`summary`/`salvage`——文案与实际漂移，改动需重新出包才生效。

### 76.4 本节锚定

- 日期：2026-10-19 10:35
- 仓库：`/Users/xuxingwang/KMP`（非 git 仓库，无分支/commit）
- 文件：`ohd/LLM_CONTEXT.md`（新增）、`ohd/README.md`、`ohd/ohd-cli/src/main/kotlin/ohd/cli/Main.kt:247`
- 设备：SGT-AL50 / 63R0226113004249 / API 24 / 1280x2832

---

## 77. §75 三条迭代与帮助文案落地（2026-10-19）

§75.2–§75.4 与 §76.3 当时记为未实施。本节只记落地，不改上文。

> **日期**：2026-10-19  
> **CMP 大版本**：不适用（ohd CLI，不是 CMP）  
> **锚定**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）  
> **路径**：`MonkeyPolicy.kt`、`MonkeyCap.kt`、`LogWatchdog.kt`、`ProcessTrees.kt`、`LogCap.kt`、`ohd-cli/.../Main.kt`  
> **验证**：`gradle :ohd-core:test :ohd-cli:test` 通过。未跑 `dist/build.sh`，已安装的 `./dist/ohd --help` 要重新构建才看得到新文案。

| 当时的问题 | 落地 |
|---|---|
| 默认 `--max-steps 100_000` 把 `--duration 10h` 静默截成 17 分钟 | 默认与显式 `0` 都是不限。有限上限的最短耗时（间隔 × 批次数）短于时长时打 `MAX_STEPS_CAP`；真在截止时间前打满步数时打 `MAX_STEPS_HIT` |
| 采样 27 成功 / 1521 失败，PSS 起止对比不告警 | 尝试 ≥ 5 且失败率 ≥ 50% 时打 `SAMPLE_UNRELIABLE`，原因分成超时 / pss不可用 / hidumper失败；人读的 PSS 行标「采样过稀，不可信」 |
| 孤儿 `hdc shell hilog` 写出 13GB×2 | `log start` 默认 `--max-size 2G`（`0` 不限）。独立看守进程到顶后砍掉该 pid 的整棵进程树并写 `.capped`；`log stop` 同样回收子孙。start 尚未返回就退出时，shutdown hook 回收，避免再留孤儿。没有单独的 `dump` 能力，13GB 文件就是这条 hilog 重定向 |
| 帮助里「交付」与「规划中」重复，且缺四个能力 | 删掉「规划中」。补 `install` / `ab`（验证收益）和 `summary` / `salvage`（交付） |

---

## 78. uiRecord 录制与回放前冷启动（2026-10-19）

> **日期**：2026-10-19  
> **CMP 大版本**：不适用（ohd CLI，不是 CMP）  
> **锚定**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）  
> **路径**：`RecordCap.kt`、`RecordScript.kt`、`ReplayAim.kt`、`UiActions.kt`、`ReplayCap.kt`、`DeviceOps.kt`  
> **设备**：SGT-AL50 / 63R0226113004249 / API 24。录制时前台是锁屏 `SCBScreenLock13`，应用窗口 `window attached #0`。

`ohd record` 跑 `uitest uiRecord record -W true -c true`，停录后读 `/data/local/tmp/record.csv`（内容是 JSON，不是表格）。脚本第一条是 `launch <包名> <Ability>`。包名或 Ability 为空则不写脚本、退出码 2。

回放碰到 `launch`：`aa force-stop` 后 `aa start`，10 秒内前台包名列表含该包才继续。单击按 `id` 重定位，没有 `id` 时用文本加类型；命中多个或找不到则这一步失败、不点。两者都空才按原坐标，并标明「坐标回退」。滑动保留位移。双指和按键、文本不进可执行步骤。

真机样本 `uirecord-fling.json`：`uiInput swipe` 被记成 `fling`，`fingerNumber` 与坐标是字符串，起点类型 `__Common__` 且 id/文本为空。锁屏上的这次录制被拒绝（没有包名和 Ability），没有回放。应用在前台之后才能做「录制 → 冷启动回放」的整段验证。

### 78.1 易用性修正（同日）

- `ohd record [文件.ohd]` 默认写当前目录 `record.ohd`，会话目录另存一份。回放直接 `ohd replay record.ohd`。
- `--bundle <包名>` 补上录制缺的包名，Ability 从 `bm dump -n` 的 `mainAbility` 取，也可 `--ability` 指定。包名和 Ability 写在脚本的 `launch` 行里。
- `ohd record read [文件.ohd]` 把设备上最近一次录制转成脚本，不用重录。
- 顺带修了两个旧缺陷：全局选项白名单写成了 `archive`，`--no-archive` / `--device` / `--debug` 被当成未知参数拒绝；`replay --dry-run` 只在 `--json` 下才不执行，表格模式会真去点设备。
- 冒烟：`record read --bundle com.tencent.compose` 用锁屏那次录制生成 `launch` + `fling` 两行；dry-run 只打计划。修 dry-run 之前那一次误执行了一遍冷启动和滑动。

### 78.2 录制文件名（同日）

不再写当前目录的 `record.ohd`。每次录制落到 `ohd-evidence/record/record-<设备序列号>-<yyyyMMdd-HHmmss>.ohd`。序列号取 hdc 的 connect key，文件名里的非法字符换成 `_`。同一秒再录一份加 `-2`，不覆盖。这个目录只放可回放脚本，方便按设备筛选后批量执行。会话目录不再另存一份脚本，manifest 记这个文件的绝对路径。

### 78.3 录制落点和回放落点（同日）

`20261019-155029` 这次：终端打印的是 `to Widget`（Button `carousel`、ListItem `栅格 r12c2`、Text 长文案），脚本却写成起点上的空容器 `type=CustomFrameNode` / `Text` / `Column`，id 和文本都空，回放三步都标「坐标回退」。

uitest 的 W1 是手指起点下的节点，W2 才是它认定的目标控件。没有 id、也没有文本的类型不再写入脚本。点击身份取 W2（没有再用 W1）；滑动的终点身份写成 `totext` / `totype` / `toid`。回放先按这个身份找控件，终点对准控件现在的中心，起点按同样的位移平移。找不到或命中多个就停，不再按原坐标点下去。

同一份 `001-uirecord.json` 已重写成 `record-63R0226113004249-20261019-155029.ohd`。提示行改成单独的 `ohd replay <文件>`，避免和前面的「回放：」粘在一起被整行复制。

### 78.4 边缘返回、滑动不带控件、点击等待（同日）

> **日期**：2026-10-19  
> **CMP 大版本**：不适用（ohd CLI，不是 CMP）  
> **锚定**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）  
> **路径**：`RecordScript.kt`、`RecordCap.kt`、`ReplayAim.kt`、`ReplayCap.kt`  
> **对照**：`ohd-evidence/record/record-63R0226113004249-20261019-162344.ohd` 与终端 `windowBounds : (0,0,1280,2832)`。那次预定义序列里，两次边缘返回被记成从 x=1271、x=1262 起手的 `fling`，下滑被挂上手指下面的首页按钮名。

- 录制开头的 `windowBounds : (left,top,right,bottom)` 给出屏幕宽高。起点离左缘或右缘 40px 以内、水平位移大于垂直位移、并且往屏幕里划的 `swipe` / `drag` / `fling`，写成 `key Back`，原坐标留在上一行注释。底边往上划不改成 Home。没有这行屏幕尺寸时不改写。该行来自 uitest 的终端输出，不在 csv 里；`ohd record` 把它补进保存的原文，方便之后重转。
- 滑动、拖拽、fling 不再写控件身份，回放按坐标。点击仍用 W2（没有再用 W1）。
- 回放点击若带控件身份，在 `--wait-timeout`（默认 5s）内反复读布局，出现再点；超时或命中多个则这一步失败。滑动不等待。
- 录完打印步骤表，例如 `1 点 image-cat / 2 滑动 / 3 返回`。`uiRecord` 漏掉的点击这里对不出来，只能重录。

### 78.5 包名不要求出现在第一条（同日）

> **日期**：2026-10-19  
> **CMP 大版本**：不适用（ohd CLI，不是 CMP）  
> **锚定**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）  
> **路径**：`RecordScript.kt`、`RecordCap.kt`  
> **对照**：`ohd-evidence/device/20261019-174052-record/001-uirecord.json`。前台已是 `com.tencent.compose`，第一条滑动的 `BUNDLE` 为空，包名只在后面的点击上。按「只看第一条」拒绝，退出码 2，首页上先滑动再找到 item 的录制写不出脚本。

包名和 Ability 改为：调用方显式参数优先，否则取第一条带包名的事件（Ability 可以在另一次事件上），事件全空时用录制开头的 `Current ForAbility :包名, Ability`。这一行和 `windowBounds` 一样只出现在终端，会补进保存的原文。全都没有时才拒绝。

### 78.6 缺陷：uiRecord 回调异常时点击丢失，ohd 仍写出脚本（同日，未修）

> **日期**：2026-10-19  
> **CMP 大版本**：不适用（ohd CLI，不是 CMP）  
> **锚定**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）  
> **路径**：`RecordCap.kt`（只转发终端、读 csv、转脚本）；设备侧 `uitest uiRecord record -W true -c true`  
> **设备**：SGT-AL50 / 63R0226113004249 / API 24  
> **证据**：`ohd-evidence/device/20261019-175117-record/001-uirecord.json`，脚本 `ohd-evidence/record/record-63R0226113004249-20261019-175240.ohd`。录制开始时前台是 `com.tencent.compose` / `EntryAbility`。

预定操作是：点 `image-cat`，里面滑一下，边缘返回，点 `image-dog`，里面下滑三次，边缘返回，首页下滑三次。

uitest 终端和 csv 里都没有 `click`，也没有 `image-cat` / `image-dog`。13 条全是 `swipe` / `fling`。停录前终端打出 `获取回调信息存在异常,请重新录制`。ohd 按这些事件写成 13 步，退出码 0：两次边缘滑动已是 `key Back`，其余是首页滑动。点击不是 ohd 转换时丢掉的；原始输出里就没有。同日更早的 `20261019-174052` 里，`image-cat` 的 `click` 曾被 uiRecord 记到并转成 `tap`，所以这是 uiRecord 这一轮回调失败，不是 ohd 不认识 `click`。

ohd 侧未做的事：终端出现「获取回调信息存在异常」时仍写 `.ohd` 并返回成功。这份脚本看起来可以回放，实际缺了点击。见到这句应判本轮录制失败，不要当成功脚本交出。此项尚未改代码。

## 79. path 原型固化：hilog 页面事件 → `.ohd` 脚本（2026-10-20）

### 79.1 背景

2026-10-19 的「demo 内存瞬间到 4G」排查会话（DSH `session-65c27c2f-aa81-44ca-a20e-1b38fe31e923`）里，
`mem` 采样能看到 4G 尖峰，但答不出「尖峰前用户点了什么」。同期日志里恰好有 ArkUI/Compose 的
`onPageShow/Hide` 事件，于是当场用 Python 试了「从日志反推操作路径 → 生成 `.ohd` 给 `replay` 复现」，
结论是可行。当时只有 `/tmp` 里的临时脚本，`/tmp` 一清就丢；本节只记固化，不改 §73–§78。

### 79.2 落地位置

- `ohd/tools/path_from_log.py` —— 合并版：`--algo stack`（默认，from->to 转移，即原 `path2.py`）、
  `--algo first`（原始 `path_from_log.py` 的 enter/back 语义）
- `ohd/tools/README.md` —— 用法、语义表、两算法差异、局限
- `ohd/tools/samples/` —— `pagemap.txt`（`Compose_N=页面名`）、`coords.txt`（first）、
  `coords2.txt`（stack）、`path-sample.ohd`（产物样例）
- 未进 CLI：`Main.kt` 能力表没有 `path`；今天 10:22 的 `docs/superpowers/plans/2026-10-20-bundle-handoff-flags.md`
  明确「不动 `path` / 不新增 `export`」，故只作原型留存。

### 79.3 验证（复跑对账）

对 `ohd-evidence/device/20261019-160302-log-save/001-hilog.txt` 复跑：

```
python3 ohd/tools/path_from_log.py ohd-evidence/device/20261019-160302-log-save/001-hilog.txt   --map ohd/tools/samples/pagemap.txt --coords ohd/tools/samples/coords2.txt
```

`stderr`：`# 事件 15 → 转移 4（enter 3 / back 1）`；`stdout` 与 2026-10-19 的 `/tmp/inferred3.ohd`
**逐字一致**（`diff` 空）。`--algo first` 同样可跑通（进 3 / 退 1），输出与 `coords.txt` 版一致。

### 79.4 局限（沿用当时的判断）

1. 只有页面级事件，拿不到点击控件；`--coords` 必须手工补，缺坐标的转移输出 `# TODO` 行。
2. 依赖被观测 App 的日志格式（`ArkUIViewController(Compose_N) onPageShow/Hide` 与 `ComposeAppShell: <页面名>`）。
3. 要产品化需 `ohd-core` 加 Parser + `caps/PathCap.kt` 并在 `Main.kt` 注册；当前不排期。

### 79.5 本节锚定

> **日期**：2026-10-20
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，无分支/commit）
> **来源会话**：`~/.dsh/sessions/--Users-xuxingwang-KMP--/session-65c27c2f-aa81-44ca-a20e-1b38fe31e923/session.v4.jsonl.zstd`
> **证据输入**：`/Users/xuxingwang/KMP/ohd-evidence/device/20261019-160302-log-save/001-hilog.txt`
> **本节新增文件**：`tools/path_from_log.py`、`tools/README.md`、`tools/samples/{pagemap.txt,coords.txt,coords2.txt,path-sample.ohd}`


---

## 80. `bundle` 交研发增强：默认 since 2h / report / zip / exit 4（2026-10-20）

> **日期**：2026-10-20  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git）  
> **计划**：`docs/superpowers/plans/2026-10-20-bundle-handoff-flags.md`  
> **关键路径**：`ohd-core/.../caps/BundleCap.kt`、`analysis/CaseReport.kt`、`caps/ReportCap.kt`  
> **本节不改写**：§22 正文保留；此处只记默认行为与退出码对齐。

### 80.1 变更摘要

| 项 | 旧 | 新 |
|---|---|---|
| 无 `--session`/`--since` | `latestSession()` 只打最新一个 | 默认 `--since 2h` 合并近窗 |
| 旧默认恢复 | — | `--latest-only` |
| 缺陷单 | 另跑 `ohd report` | `--report` 写入本包 `report.md`（与 `CaseReport` 共用） |
| 压缩 | 无 | `--zip` 在 case 旁写同名 `.zip`（目录仍保留） |
| 缺必备件 | 仍 `exit 0` | `exit 4`（`ASSERT_FAILED`）；包已写出；`--json` 仍含 `package`/`zip` |
| zip 失败 | — | 目录可用 + diagnostics；倾向 `exit 4` |

交研发推荐一句：

```bash
ohd bundle --redact --report --zip [--name …] [--out …]
```

（显式 `--since 2h` 与默认等价；`--since 0` = 不设时间下限。）

### 80.2 明确不做（同计划）

- 不新增 `export` 命令  
- 不把 `tools/path_from_log.py` 升成 CLI（见 §79）  
- 不改 monkey 自动 bundle、不改 `ohd-case/1` 字段名  


---

## 81. `ohd path`：hilog → `.ohd` 升 CLI（2026-10-20）

> **日期**：2026-10-20  
> **仓库**：`/Users/xuxingwang/KMP/ohd`  
> **前置**：§79 固化 `tools/path_from_log.py`；本节把同算法迁入 `ohd-core` 并注册能力。  
> **关键路径**：`parsers/PathFromLog.kt`、`caps/PathCap.kt`、`Main.kt`（`path`）

### 81.1 用法

```bash
ohd path <hilog.txt> [--algo stack|first] [--map pagemap.txt] [--coords coords.txt] \
  [--package com.tencent.compose] [--ability EntryAbility] [--out out.ohd] [--json]
```

- 默认 `stack`（from→to）；`first` 为 v1 enter/back。
- 缺 `--coords` 的 enter 输出 `# TODO` 行；`back` 默认 `key Back`。
- Python 原型保留对拍；语义以 Kotlin 为准。

### 81.2 局限（沿用 §79.4）

页面级事件、依赖 Compose/ArkUI 日志格式、坐标须手工映射——未变。


## 82. 扩展机制：脚本挂新命令（2026-10-21）

> **日期**：2026-10-21  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（工作区 dirty）  
> **前置**：内置能力 26 个（§5/§16.4）；每逢新场景要改 Kotlin、重编译、重发版，成本与「一次性小流程」不匹配。  
> **关键路径**：`ohd-core/src/main/kotlin/ohd/core/ext/Extensions.kt`、`caps/ExtCap.kt`、`ohd-cli/src/main/kotlin/ohd/cli/Main.kt`、`examples/extensions/`

### 82.1 形态决策：为什么是脚本，不是 JVM SPI

| 方案 | 结论 |
|---|---|
| JVM SPI / 插件 jar | 否决：需要编译 + 版本对齐 + 类加载隔离，使用门槛高于收益 |
| YAML/JSON 声明式动作 | 否决：表达力不够（分支、循环、解析 JSON 都要），会演化成弱语言 |
| **外部脚本 + 约定** | 采用：同事会写 shell/python 就会扩展；失败隔离在子进程 |

### 82.2 发现与优先级

搜索目录（高 → 低）：`$OHD_EXT` → `<cwd>/.ohd/extensions` → `~/.ohd/extensions` → `<ohd 根>/extensions`。

- 命令名 = 文件名去后缀，须匹配 `^[a-z][a-z0-9._-]*$`；
- 同名多后缀按平台偏好取一：Windows `bat/cmd > exec > java > py > sh`，Unix `exec > sh > py > java`；
- **内置能力始终优先**，扩展不能覆盖 `tree`/`monkey` 这类契约语义；
- 目录不可读、文件非法：静默跳过（扩展是增强，不能让主命令崩）。

### 82.3 执行契约

- argv 原样透传（`Main.kt` 记录 `capabilityIndex`，扩展直接 `argv.drop(idx+1)`；`ext run` 走 `RunContext.rawArgs`）；
- 子进程 `inheritIO`，退出码原样返回；
- 注入环境：`OHD_SELF`（探测 installDist `bin/ohd` / dist 包 `./ohd`）、`OHD_JAVA`/`OHD_CP`、`OHD_HDC`、`OHD_LLVM`、`OHD_DEVICE`、`OHD_SESSION_ROOT`、`OHD_JSON`、`OHD_ASCII`、`OHD_EXT`；
- 解释器：`.sh→/bin/sh`、`.py→python3`（`OHD_PYTHON` 覆盖）、`.java→java <file>.java`（JDK 11+ 单文件源码）、`.bat/.cmd→cmd /c`、无后缀直接执行（需 `+x`）。

### 82.4 参数校验的例外

`Capability` 新增 `passthrough`（默认 false）。仅 `ext` 打开——脚本的选项空间 ohd 不认识，白名单校验会把 `--count 5` 判成拼错。其余能力保持严格白名单，理由不变（§16.4「拼错参数必须报错」）。

### 82.5 安全边界

不做「PATH 搜索 + 直接 exec」：只扫上面四个白名单目录，避免同名脚本劫持；不自动从网络拉取；不设 `--trust` 之类开关（信任模型够简单：能往这些目录写文件的人本来就能跑任意命令）。

### 82.6 交付物

- `Extensions.kt`：发现/排序/解析摘要/拉起/环境注入；
- `ExtCap.kt`：`ohd ext list|path|which|docs|run`；
- `Main.kt`：未知能力 fallback、`@@EXT@@` 帮助段、`passthrough` 豁免、`rawArgs`；
- `examples/extensions/`：`uitest.sh`（hdc uitest 随机点击 + pidof 存活校验）、`memcheck.py`（内存阈值）、`hello.java`（环境探针 + 回调 ohd）。

### 82.7 待验证

- [ ] `ohd ext list` / `uitest` 实跑（需先编译）；
- [ ] Windows `.bat` 分工（未在真机验证）；
- [ ] `OHD_SELF` 在 `java -cp` 直接调用（非启动脚本）时探不到 → 回退 `OHD_SELF` 环境变量。

## 83. `monitor`：只观测、不驱动（2026-10-21）

> **日期**：2026-10-21  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（工作区 dirty）  
> **动机**：动作来自外部（另一个窗口的 `ohd tap` / `ohd replay` / `uitest` 脚本 / 人手动点），需要一个人只负责「记录现场」的进程。  
> **关键路径**：`ohd-core/src/main/kotlin/ohd/core/caps/MonitorCap.kt`、`HilogStream.kt`、`Faults.kt`、`SoakCap.kt`（采样写法参照）

### 83.1 与 soak / monkey 的分工

| 能力 | 产生事件 | 抓日志 | 采样 | 崩溃现场 | 定位 |
|---|---|---|---|---|---|
| `monkey` | 是（随机点/滑） | 是 | 每步 | 是 | 压力+复现 |
| `soak` | 可选（`--drive`） | 否 | 30s 起，趋势导向 | 否 | 内存趋势 |
| **`monitor`** | **否** | **全程** | **5–10s 可调** | **是** | 旁路取证 |

不复用 `soak` 的原因：它的采样是「测泄漏趋势」导向（30s 起步、输出斜率/平台期判定），而旁路取证需要更密采样 + 日志与崩溃时间线对齐 + 进程消失检测，混在一起会把 soak 的判定语义改坏。

### 83.2 四件事

1. **hilog 全程落盘**：与 `monkey` 同一个 `HilogStream.pump` + `LogWatchdog.spawn`，`--tag/--match/--exclude/--full/--max-size` 语义完全一致（用户不必学第二套）。
2. **周期采样**：`hidumper --mem`（PSS）+ `hidumper --cpuusage` + `/proc/<pid>/mm_dmabuf_info`，写 `0NN-monitor.csv`。
3. **故障巡检**：每 `--check-every` 轮 `Faults.list` 差分，新崩溃时 `hidumper -s 1201 -a '-p Faultlogger -f <文件>'` 拉 faultlog，并补抓崩溃前后 500 行 hilog（外部动作序列只能从日志里还原）。
4. **存活校验**：每轮以 `hidumper --mem` 的返回码判活，`deadRounds>0` 时 verdict=`PROC_GONE`——立即知道是应用死了还是工具挂了。

### 83.3 判定边界（与 §33 一致的克制）

- 只报 `PSS 首→末 / 峰 / 斜率`、CPU 均值/峰、崩溃次数，**不判泄漏**（需要业务定义的 N）；
- 观察到崩溃时明确提示「动作不是 ohd 施加的，复现路径请对照外部驱动工具」，不制造虚假因果；
- `PROC_GONE` 不直接等价于崩溃：外部工具可能主动 kill。

### 83.4 参数

```
ohd monitor [--pid N | --bundle b] [--duration 30m] [--interval 10s]
            [--tag a,b] [--match RE] [--exclude RE] [--full] [--max-size 0]
            [--check-every 2] [--no-hilog] [--json]
```

`interval` 下限 0.5s：采样太密会自己抢占设备，污染要观察的数据。

### 83.5 待验证

- [ ] 需编译后实跑：`--interval 1s --duration 1m` 抓一次真实会话，核对 CSV 列与 faultlog 差分；
- [ ] 外部驱动联调：另一窗口 `ohd replay` 期间 monitor 是否把崩溃与操作对齐。

## 84. 日志默认压缩：多成员 gzip 追加（2026-10-21）

> **日期**：2026-10-21  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（工作区 dirty，未编译）  
> **动机**：10h 压测的 hilog 明文动辄数 GB，落盘/回传/给同事都笨重；要求「默认就是压缩的、独立文件、持续追加」。  
> **关键路径**：`ohd-core/src/main/kotlin/ohd/core/LogSink.kt`（新）、`HilogStream.kt`、`caps/{LogCap,MonkeyCap,MonitorCap}.kt`、`ohd-cli/.../Main.kt`

### 84.1 格式选择：gzip 多成员，不是 zstd、不是 zip

| 候选 | 追加能力 | 依赖 | 结论 |
|---|---|---|---|
| zstd 单帧 | 不支持（帧尾才完整） | 需 zstd-jni 或外部 CLI | 否 |
| zstd 多帧 | 支持（多帧拼接合法） | 同上 | 备选 |
| **gzip 多成员** | **支持（RFC 1952，`cat a.gz b.gz` 合法）** | **JDK 自带，零依赖** | **采用** |
| zip | 不支持（中央目录在尾部，追加要重写） | JDK 自带 | 否 |

验证（本机实测）：两个 gzip 成员 `cat` 拼接后 `gunzip -c` 与 Python `gzip` 都能完整解出；JDK `GZIPInputStream` 自 Java 9 起同样支持多成员拼接。

### 84.2 写端

`LogSink` 两个实现：

- `PlainLogSink`：原有行为，`NNN-hilog.txt`；
- `GzipLogSink`：内存攒批，**每 8MB 或 2s 闭合一个成员**并 append 到 `NNN-hilog.log.gz`。

`GZIPOutputStream` 不能复用，每成员各自 open/close 底层流（每秒一次，开销可忽略）。选这个颗粒度的原因：成员越大压缩率越好，但被 kill 时丢的尾巴也越大——2s 是「丢得起」的上限。

`HilogStream.pump` 增加 `sink` 重载，旧的 `(argv, file, …)` 签名保留（内部包 `PlainLogSink`），三个调用点（`log`/`monkey`/`monitor`）零改动迁移。

### 84.3 读端

`HilogFiles` 统一入口，按后缀自动解压：

- `newReader`：`.gz → GZIPInputStream`，其余直读；
- `countLines` / `tailLines`：明文走 `RandomAccessFile` 随机定位；**gzip 不可 seek**，用固定窗口环形缓冲流式取尾（不把 GB 级日志读进内存）；
- 压缩流尾部被截断时返回已解部分而不是抛异常——那正是崩溃现场。

`LogCap` 的 `tail/count/save` 已切到 `HilogFiles`；`save` 保持源后缀（压缩源存成 `.log.gz`，不改名成 `.txt`）。

### 84.4 语义变化（要记住）

- `--max-size` 现在按**压缩后**大小计（文本通常 1/6~1/10，等于放宽）；`LogWatchdog` 逻辑未改（它只看文件大小）。
- 默认 gzip 是**全局选项** `--log-compress gzip|none`，默认 `gzip`；所有写 hilog 的能力（`log`/`monkey`/`monitor`）共用。
- `bundle` 打包无需改：目录里 `.log.gz` 原样收进 zip，体积直接受益。

### 84.5 待验证

- [ ] 编译后跑 `ohd log start --duration 10s` → 确认产出 `.log.gz`、`gunzip -c` 可读、多成员 count 正确；
- [ ] 中途 `kill -9` ohd 后确认已闭合成员仍可解压；
- [ ] `log tail/count/save` 在 `.gz` 上的行为。

## 85. `config`：用户级路径配置（2026-10-21）

> **日期**：2026-10-21  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（工作区 dirty，未编译）  
> **动机**：每次 `--hdc` / `--llvm` 手敲太长；`JAVA_HOME`、`OHD_PYTHON` 这类「本机装在哪」的信息应当持久化，而不是靠 shell 里 export。  
> **关键路径**：`ohd-core/src/main/kotlin/ohd/core/Config.kt`（新）、`caps/ConfigCap.kt`（新）、`Env.kt`、`ext/Extensions.kt`、`ohd-cli/.../Main.kt`

### 85.1 决策：用「环境变量名」做键

备选：
- A. 每个工具一套键（`hdc.path`、`llvm.path`、`java.home`）——要重新定义优先级，且与 `Env` 现有发现逻辑重复；
- B. **键 = 环境变量名**（`OHD_HDC`、`OHD_LLVM_ADDR2LINE`、`JAVA_HOME`）——`Env` 的发现顺序本来就是「显式 → 环境变量 → 平台默认 → PATH」，配置只需插进「环境变量」这一层。

选 B 的直接收益：`Env` 零改动，只把 `sysenv` 换成 `OhdConfig.sysenv()`（配置覆盖进程环境）；`doctor` / `selftest` / 所有能力自动跟着变。

### 85.2 存储与优先级

- 文件：`~/.ohd/config.json`，`$OHD_CONFIG` 可覆盖（团队共享 / 测试用）；
- 结构：`{"schema":"ohd-config/1","env":{"JAVA_HOME":"…","OHD_HDC":"…"}}`；
- 优先级：**CLI 显式参数 > 配置文件 > 进程环境变量 > 自动探测**（显式参数走的还是 `explicitHdc/explicitLlvm`，不受配置影响）；
- 进程内 `lazy` 缓存，避免 `Env` 多次探测重复读盘。

### 85.3 JAVA_HOME 的边界（必须说清）

配置里的 `JAVA_HOME` **不会改变 ohd 自己**——ohd 已经跑在某个 JVM 上了，改环境变量对运行中的进程无效。它的作用面是两个：

1. 扩展脚本：`OHD_JAVA` 注入时优先取 `JAVA_HOME/bin/java`，`.java` 扩展因此用你指定的 JDK；
2. 用户 shell：`ohd config env` 输出 `export` 行，可手动 eval。

要换 ohd 自身的 JDK，只能改启动脚本/`PATH`——这是 JVM 的物理限制，不是配置能绕过的。

### 85.4 命令面

`ohd config list|keys|path|get|set|unset|env`；`set` 检查键名合法性（`^[A-Za-z_][A-Za-z0-9_]*$`），值像路径但不存在时给提示而非报错（很多工具路径要先安装）。

### 85.5 待验证

- [ ] 编译后：`ohd config set OHD_HDC /usr/bin/hdc` → `ohd doctor` 是否显示 `argument/env` 来源；
- [ ] `ohd config list` 的 `来源` 列在「配置 + 环境变量同时存在」时是否正确；
- [ ] 扩展 `.java` 是否真的用了 `JAVA_HOME`。

## 86. 首次编译与实机验证（2026-10-22）

> **日期**：2026-10-22（设备时间 08:5x）  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **构建**：`./dist/build.sh`（Gradle 8.13，**JDK 21.0.12.1**）  
> **设备**：`63R0226113004249`（SGT-AL50 / API 24）  
> **范围**：§82 扩展、§83 monitor、§84 日志 gzip、§85 config 四项一次性落地验证

### 86.1 构建前置（两个环境坑）

1. `JAVA_HOME` 在本机被设为 `/opt/homebrew/opt/openjdk@17/`（**该版本已卸载**，软链悬空）→ Gradle 直接 `ERROR: JAVA_HOME is set to an invalid directory`。本次统一用 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 覆盖。
2. `workspace-write` 沙箱禁止写 `~/.gradle`，Gradle 建 `libnative-platform.dylib.lock` 失败 → 报 `Could not initialize native services`（**误导性极强，真实原因是权限**）。编译需放宽到可写 home。

### 86.2 编译期修掉的 5 个真问题

| # | 位置 | 现象 | 根因 |
|---|---|---|---|
| 1 | `Config.kt:61` | `contentOrNull` cannot be invoked as a function | 它是扩展**属性**，不是函数 |
| 2 | `ExtCap.kt:51` | `actual type is 'String', but 'JsonElement' was expected` | `JsonArrayBuilder.add(String)` 需 `import kotlinx.serialization.json.add` |
| 3 | `MonitorCap.kt:16` | Unresolved `LogWatchdog` | 它与 MonitorCap 同包（`ohd.core.caps`），`import ohd.core.LogWatchdog` 反而错 |
| 4 | `Extensions.kt:241` | Missing `}` / Unclosed comment | KDoc 里写了 `lib/*.jar`，其中的 `*/` **提前闭合了注释** |
| 5 | `MonitorCap.kt` | `actual type is 'Hdc', but 'String' was expected` | `Hdcs.forDevice` 返回 `Hdc` 不是 `String` |

第 4 条最隐蔽：`/** … <root>/lib/*.jar … */` 生成语法错误的位置在**几十行之后**，看着像文件结构损坏。

### 86.3 实机验证矩阵

| 能力 | 验证方式 | 结果 |
|---|---|---|
| `--version` / `selftest` | 直接跑 | `ohd 0.1.0`；selftest 全绿 |
| `doctor` | 直接跑 | 12 项全 OK（hdc/device/uitest/faultlog/llvm 均识别） |
| `config` | `set/get/list/unset/env`（`OHD_CONFIG=/tmp`） | 写入 `{"schema":"ohd-config/1","env":{…}}`；`list` 的来源列正确区分 config/env |
| `ext list` / 帮助 | 装入 `examples/extensions` 后跑 | 3 个命令进 `ohd --help`；清理后恢复「（无）」 |
| `.java` 扩展 | `ohd hello` | 注入 `OHD_*`；回调 `$OHD_SELF --version` 得到 `ohd 0.1.0` |
| `.sh` 扩展 | `ohd uitest --count 2` | 点击下发成功；进程消失时脚本按约定退出 |
| 日志 gzip | `ohd log start` → 6s → `stop` | 产出 `001-hilog.log.gz`，**5 个 gzip 成员**，`gunzip -c` 解出 1000 行 |
| 读端 | `log count --file <gz>` / `log snap --file <gz>` | 正常按 tag 统计与取尾 |
| `monitor` 正常路径 | `--bundle com.ohos.sceneboard --duration 12s --interval 2s` | 6 轮采样，CSV 列 `pss/cpu/dma/alive` 齐；PSS 382.9MB |
| `monitor` 崩溃路径 | 先起 monitor，3s 后 `aa start`（app 启动即崩） | 抓到 `cppcrash`，留存 `002-faultlog.txt`，verdict `CRASH`，**退出码 4** |

### 86.4 验证中发现并修掉的设计缺陷

初版 monitor 在「`pidof` 探不到进程」时**直接抛错退出**。而本项目的目标应用（`com.tencent.compose`）恰恰是**启动即崩**（`SIGSEGV` in `ArkUIViewController_setMessenger`，见 §81 证据），进程存活时间短于采样间隔——于是 monitor 永远看不到它，而它正是最该观测的对象。

修复（`MonitorCap.kt`）：pid 解析失败**不退出**，改为每轮重试；样本记 `alive=0 / pss=-1` 保留时间线；崩溃判定仍靠 faultlog 差分。实测正面命中（86.3 最后一行）。这是「旁路观测不该因目标不在而放弃」的实现。

### 86.5 遗留

- `jvmToolchain(17)` 仍不可行：本机无 JDK 17（`openjdk-17.jdk` 是空壳，`java_home -V` 只有 11/21）。要么 `brew install openjdk@17`，要么保留 21 而只设 `jvmTarget=17`。
- `dist/ohd-0.1.0-libonly.zip` 是**旧代码**（编译前打的），如需交付需重打。
- 目标应用 `com.tencent.compose` 启动崩溃未解决，`monkey` 的 30 分钟压测仍不可做。

## 87. 跨平台：Android 支持的架构切口（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **目标**：同一套命令支持 OHOS 与 Android，`--platform` 或自动探测选择，**不复制两套能力**  
> **关键路径**：`Hdc.kt`（DeviceKind）、`Devices.kt`（新）、`PlatformCmds.kt`（新）、`parsers/AndroidParser.kt`（新）、`Env.kt`（adb 定位）、`actions/UiActions.kt`

### 87.1 为什么不是「再写一套 Android 能力」

44 处 `hdc.shell(...)` 调用散在 16 个能力里。若按能力复制，等于维护两套语义等价但会漂移的实现。做法是把差异**压到最薄的三层**：

1. **通道层**（`Hdc`）：`shell` / 推 / 拉 的语义 hdc 与 adb 完全一致，差异只有 `-t`↔`-s`、`file send|recv`↔`push|pull`。给 `Hdc` 加 `kind: DeviceKind` 即可，**44 处调用点一行未改**。
2. **命令层**（`PlatformCmds`）：唯一出现平台命令差异的地方。「我要内存」→ `hidumper --mem` / `dumpsys meminfo`。
3. **解析层**（`AndroidParser`）：`dumpsys meminfo` / `top` 的输出与 `hidumper` 不同，单独解析后产出**同一数据结构** `MemReport`，上层无感。

### 87.2 设备选择规则（不猜）

`--device <key>` 精确匹配 → `--platform ohos|android` 限定池 → 只剩一台就选 → 多台报错列全部。

工具缺失与设备缺失分开报：`adb 未找到（来源 not-found）` ≠ `android 平台没有已连接的设备`。后者才是「设备没连」。

### 87.3 已迁移 vs 未迁移（诚实清单）

| 状态 | 能力 | 说明 |
|---|---|---|
| ✅ | 设备发现 / doctor | hdc + adb 双通道，doctor 分别报告 |
| ✅ | `log`（start/stop/snap/count/save） | `logcat -v threadtime`；gzip 落盘与读端通用 |
| ✅ | `monitor` | mem/cpu/crash 走 PlatformCmds；DMA 在 Android 读不到即留空 |
| ✅ | `mem` | `dumpsys meminfo` → `AndroidParser.memReport` |
| ✅ | `tap` / `swipe` / `key` | `input tap/swipe/keyevent`；`doubleClick`、`dircFling` 无等价，明确报「无此动作」 |
| ✅ | `tree` | `uiautomator dump --compressed` → `AndroidLayoutParser`（XML→LayoutNode）；实机见 §88 |
| ✅ | `shot` | `screencap -p` + ImageIO 读尺寸；实机见 §88 |
| ✅ | `monkey` / `replay` | 22 步注入 + 22/22 回放，见 §88 |
| ✅ | `crash` | crash buffer（Java 异常 / Native 信号双形态），见 §89 |
| ✅ | `cpu` | `top -b -n 1` + `/sys/.../cpufreq`，见 §89 |
| ✅ | `install` / `uninstall` | `adb install -r` 与 `hdc install -r` 同构，见 §89 |
| ⬜ | `record` / `replay` | `uiRecord` 在 Android 无对等（需 uiautomator2/WDA） |
| ⬜ | `dmabuf` | Android 需 root，`/proc/<pid>/mm_dmabuf_info` 通常不可读 |

### 87.4 验证现状

- OHOS：`log` / `monitor` / `mem` / `tap` / `doctor` 全部回归通过（设备 `63R0226113004249`）。
- Android：`doctor` 已识别 adb（`sdk-default`）；**`adb devices` 为空，尚未实机验证**。所有 Android 分支的解析器（`AndroidParser`）目前只有静态正确性，等设备接上补测。

### 87.5 待办

- [ ] 接一台 Android 真机，跑 `ohd --platform android log start` / `monitor` / `mem` 各一遍；
- [ ] `tree`（uiautomator XML → LayoutNode 映射）——monkey 依赖它，是 Android 全量对等的**关键前置**；
- [ ] `install` / `uninstall` / `crash` 迁移。

## 88. Android 实机验证（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：`emulator-5554` / sdk_gphone64_arm64 / API 36 / abi arm64-v8a  
> **对象**：`com.tencent.compose`（同一 APK 在 OHOS 上启动即崩，见 §81；Android 上稳定）  
> **依据**：§87 的三层抽象（通道 / 命令 / 解析）

### 88.1 结果矩阵（全部通过）

| 能力 | 命令 | 实测结果 |
|---|---|---|
| `doctor` | `--platform android doctor` | 只查 Android 相关项：adbd=emulator-5554、api=36、uiautomator/screencap/crash buffer 全 OK |
| `log` | `log start` → 5s → `stop` | `001-hilog.log.gz`，107800 行（含启动前 buffer），`gunzip -c` 正常 |
| `mem` | `mem --bundle com.tencent.compose` | 分类表：Native Heap 8981KB / Java Heap / Dalvik Heap / Stack / Code / Graphics |
| `monitor` | `monitor --duration 20s --interval 2s` | 10 轮，PSS 92→95.5MB，CPU 均值 0.4% 峰 4.0%，进程全程存活，CSV 正常 |
| `tree` | `tree` | uiautomator XML → LayoutNode 树，Compose 层级与 bounds 正确 |
| `tap` / `swipe` / `key` | `tap 540 1200` | `input tap` 生效 |
| `monkey` | `--duration 30s` | 22 步注入、0 崩溃、39.9 步/分、采样 1 个、PSS 111MB、事件脚本落盘 |
| `replay` | `replay 003-monkey.ohd` | **22/22 全部成功** |
| `shot` | `shot` | 1080×2400 PNG |

### 88.2 本轮修掉的问题

1. **`doctor` 在 `--platform android` 下仍检查 OHOS 专属项**（uitest / snapshot_display / faultlog / llvm / 设备 disk），输出一堆 FAIL 噪音。改为按**选中设备的 kind** 分支：OHOS 查原有一组，Android 查 `getprop` / `uiautomator` / `screencap` / `logcat -b crash` / `df`。
2. **`monkey` 采样仍写死 `hidumper --mem`** → Android 下每轮都失败。改为 `PlatformCmds.memCommand` + `AndroidParser.memReport`。
3. **`tree` 直接调 `uitest dumpLayout`** → Android 上报 `uitest: inaccessible`。新增 `AndroidLayoutParser`（DOM 解析 UI Automator XML）+ `DeviceOps.captureLayoutAndroid`（`uiautomator dump --compressed` → pull → 解析）。
4. **`shot` 用 `snapshot_display`** → Android 上不存在。新增 `screencap -p` 分支，尺寸用 `ImageIO` 读（设备端不打印宽高）。

### 88.3 交叉验证价值（对 §81 的旁证）

同一个 `com.tencent.compose`：

- **OHOS**（`63R0226113004249`）：启动即崩，`SIGSEGV` in `libkn.so androidx_compose_ui_arkui_ArkUIViewController_setMessenger+48`；
- **Android**（`emulator-5554`，API 36）：`monkey` 22 步、`replay` 22 步、`monitor` 10 轮全部正常，无 crash。

应用层代码相同、崩溃点位于 **ArkUI 桥接层** 而非应用逻辑——这把 §81 的嫌疑范围从「应用 + 框架」收窄到「OHOS ArkUI 容器初始化」，是 §81 结论的独立佐证。

### 88.4 仍未迁移（不影响上述验证）

| 能力 | 原因 |
|---|---|
| `crash` | OHOS 按 faultlog 文件名拉取；Android 的 crash buffer 已在 `monitor`/`Faults` 中支持，但 `crash` 子命令的列表/详情呈现未适配 |
| `cpu` | `cpufreq` 在 Android 走 `/sys/devices/system/cpu/*/cpufreq/scaling_cur_freq` |
| `install` / `uninstall` | `bm install` vs `adb install -r` |
| `dmabuf` | Android 需 root |
| `record` | `uitest uiRecord` 在 Android 无对等（需 uiautomator2 / WDA） |

### 88.5 待办

- [x] 30 分钟 `monkey` 长跑（Android）—— **已完成，见 §89**；
- [ ] 迁移 `crash` / `cpu` / `install`；
- [ ] iOS：通道层已有 `DeviceKind` 扩展位，但需先装 `go-ios` 或 `pymobiledevice3`。

### 88.6 30 分钟长跑结果（Android，2026-10-22）

> 补于本轮验证之后，命令：`ohd --platform android monkey --bundle com.tencent.compose --duration 30m`

| 指标 | 数值 |
|---|---|
| 注入步数 | 1246（跳过 25） |
| 崩溃次数 | **0** |
| 步频 | 41.4 步/分（1.45 s/步） |
| 采样点 | 62 |
| PSS | 112.6 → 120.3 MB（首尾），分段均值 111.1→115.1→114.0→113.3→117.1→118.6 |
| 脱困 / 越界拉回 | 未触发 / 0 次 |

**PSS 判定：不像泄漏。** 依据是分段均值中途回落（115.1→114.0→113.3）说明有释放动作，不是单调爬升；30 分钟净增 7.7MB，折合约 0.25 MB/分钟，形态更像列表/图片缓存与 GC 延迟，而非持有性增长。要定性还需「强制 GC 前后对比」或更长时长（≥2h）。

**已知差距**：该 CSV 中 `dma_bytes=-1`、`gl_kb=0`、`graph_kb=0` —— Android 侧的 DMA / GL / Graphic 解析尚未迁移（DMA 需 root），OHOS 上这三列是有值的。这是当前 Android 与 OHOS 在观测深度上的真实差异，不做粉饰。

## 89. Android 迁移：crash / cpu / install（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：`63R0226113004249`（OHOS）+ `emulator-5554`（Android API 36）  
> **前置**：§87 三层抽象、§88 首批迁移  
> **口径**：观测维度按平台实际返回为准，拿不到的留空，不做 0 填充

### 89.1 新增解析器

| 文件 | 职责 |
|---|---|
| `parsers/AndroidCpuParser.kt` | `top -b -n 1`（toybox）+ `/proc/loadavg` + `/sys/.../cpufreq` → `CpuReport` |
| `parsers/AndroidCrashParser.kt` | crash buffer → `CrashReport`，区分 **Java 未捕获异常**（`FATAL EXCEPTION` + `Process: pkg, PID: n` + `at` 栈）与 **Native 信号**（`Fatal signal` + `#00 pc ... lib.so (sym)`） |

### 89.2 修掉的真 bug：toybox 的 `%cpu` 语义

首版把 `<n>%cpu` 里的 `n` 当成核数，于是 1 核设备（`100%cpu`）被算成 100 核，`scale` 缩小 100 倍，**实测总占用显示 99.08%、idle 0.92%**（实际约 4% / 96%）。

真实语义是「核数 × 100」：`100%cpu` = 1 核，`400%cpu` = 4 核。修正后：

```
总占用       4.00%
用户/内核    0.00% / 4.00%
idle         96.00%
```

### 89.3 三能力迁移方式

| 能力 | OHOS | Android |
|---|---|---|
| `crash --list` | faultlog 文件名列表 | crash buffer 中的 `FATAL EXCEPTION` / `Fatal signal` 标识行 |
| `crash --details` | `hidumper -s 1201 -f <file>` | `logcat -b crash -d -t 2000` 全文 + `AndroidCrashParser` |
| `cpu --cpufreq` | `hidumper --cpufreq` | 遍历 `/sys/devices/system/cpu/cpu*/cpufreq/scaling_{cur,max}_freq` |
| `cpu` | `hidumper --cpuusage` | `top -b -n 1` + `cat /proc/loadavg` |
| `install` | `hdc install -r` | `adb install -r`（**语义同构，无需改动**） |
| 版本查询 | `bm dump -n` | `dumpsys package <pkg> \| grep versionName` |
| 冷启动 | `aa start -b -a EntryAbility` | `monkey -p <pkg> -c LAUNCHER 1` |
| 强停 | `aa force-stop` | `am force-stop` |

### 89.4 实机结果

| 项 | Android | OHOS（回归） |
|---|---|---|
| `crash --list` | 2 条（cppcrash + jscrash） | faultlog 文件名列表正常 |
| `crash --details` | `cppcrash` / 模块 `gle.android.tts` / PID 2756 / **45 帧 native 栈**（含 `libc.so pthread_once+148`） | 原样正常 |
| `cpu` | 总占用 4.00%，idle 96.00%，load 0.00/0.14/0.48 | 2.95% / 96.99% / load 12.40 |
| `cpu --cpufreq` | 1 核，cur=2 max=2（**模拟器虚拟值**，转 MHz 显示 0） | 4 核 1160~1530 / 1720 MHz，占比 67~89% |
| `install` | 102.5 MB 覆盖安装成功 | 未复测（本次未改 OHOS 路径） |

> 模拟器 `scaling_cur_freq` 返回 `2`（kHz 级虚拟值），转 MHz 后为 0 —— 不是解析 bug，真机会返回 1800000 量级。`cpuinfo_cur_freq` 在模拟器上 `Permission denied`，故只读 `scaling_*`。

### 89.5 多设备保护实测

两台设备同时在线时，不带 `--platform` / `--device` 直接跑：

```
x 有多台设备，请用 --device 指定
  -> 已连接：63R0226113004249(ohos)，emulator-5554(android)
```

符合 §87.2 的「绝不猜」原则。

### 89.6 剩余未迁移

`dmabuf`（Android 需 root）、`record`（`uitest uiRecord` 无对等）、`ab` 的 DMA 列（同上）。这三项属于「平台本就没有」，按 §89 口径不做 0 填充。

## 90. 多设备并行（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：`63R0226113004249`(ohos) + `emulator-5554`(android)  
> **目标**：检测到几台设备，就同时对几台做同样的操作，彼此独立

### 90.1 为什么是子进程，不是线程池

| 维度 | 线程池 | 子进程（采纳） |
|---|---|---|
| 崩溃/异常隔离 | 一处 OOM 或未捕获异常波及全部 | 各自退出码，互不影响 |
| 输出 | `println` 交错，需全局锁 | 各自 stdout，主进程读后加前缀 |
| 会话目录 | 同秒启动撞名 | `--device` 后缀天然隔离 |
| 全局状态 | `OhdConfig` 缓存 / 设备选择共享 | 每个进程独立 |
| Ctrl-C | 需自己实现取消传播 | shutdown hook 统一 `destroy()` |

monkey 是分钟级长跑，隔离性的权重远高于线程复用省下的几百毫秒启动时间。

### 90.2 两处必须修的共享状态

1. **会话目录**：`Session.create` 原本是 `<timestamp>-<capability>`，两台设备同一秒启动会撞名。改为追加 `Self.safeLabel(device)` → `20261022-101527-mem-63R0226113004249`。
2. **`log-state.json`**：`log start` 把状态写在会话根，并行时 `log stop` 会停错设备。改为 `log-state-<device>.json`。

两处都由 `Hdcs.session` / `defaultStateFile` 从 `ctx.options.str("device")` 取标识，所以**单设备路径完全不变**（label 为 null 时不加后缀），向后兼容。

### 90.3 用法

```bash
ohd --all monkey --bundle com.tencent.compose --duration 20s
ohd --all replay path/to/script.ohd
ohd --all mem --bundle com.tencent.compose
ohd --all --platform android monkey ...      # 只跑 Android 设备
```

- `--all` 与 `--device` 互斥（`--all` 本身就是「每台都跑」）。
- **不自动**：多台在线时不加 `--all` 仍报「有多台设备，请用 --device 指定」。理由同 §87.2——绝不猜。
- `--all` 已加入 `BOOL_FLAGS`（否则 `--all mem` 会把 `mem` 吃成 `--all` 的值，实测踩过）。

### 90.4 实测

| 场景 | 结果 |
|---|---|
| `--all mem --bundle` | 两设备并行；OHOS 出完整分类表，Android 因进程未启动独立报错，**互不干扰** |
| `--all monkey --duration 20s` | OHOS 11 步 / Android 9 步，均 0 崩溃，各自产出 `003-monkey.ohd` 与独立日志 |
| `--all replay <script>` | 两设备各 **9/9 步全成功**，各存一份脚本副本 |
| 汇总 | `✓ 63R0226… ohos 23.5s` / `✓ emulator-5554 android 23.5s`，退出码均为 0 |

会话目录实测：

```
20261022-101527-mem-63R0226113004249
20261022-101527-mem-emulator-5554
```

### 90.5 性能代价（要说清）

并行会拉低单设备步频：串行 41.4 步/分 → 两设备并行约 23~29 步/分。原因是主机侧 `hdc`/`adb` 命令阻塞执行、共享 CPU 与 USB 带宽。**隔离性换来的代价**，不是 bug。设备越多越明显，若需高步频压测建议单设备跑。

### 90.6 待验证

- [x] 统一终止：**SIGTERM 已验证**（中断后输出立即停止，25 行 → 25 行不再增长）。SIGINT 在「后台作业 + `kill -INT`」的测试方式下无效，原因是 bash 对非交互后台作业设 `SIG_IGN` 且被 `exec` 继承，非代码问题；前台 Ctrl-C 走正常进程组信号。
- [ ] 单设备失败不影响其它设备的端到端用例（当前逻辑上成立：各子进程独立 `waitFor` 收集退出码）；
- [ ] iOS 通道接入后的三者并行。

## 91. 修两个真 bug：日志流后台存活 / 扩展超时（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：`63R0226113004249`(ohos)、`emulator-5554`(android)  
> **触发**：写 Java 扩展示例时撞到 `EOFException: Unexpected end of ZLIB input stream`

### 91.1 bug A：`log start` 返回后日志流根本没在采

**现象**：`log start` 之后文件不再增长（5 秒内字节数一个不变），`gunzip -t` 报 `unexpected end of file`。

**根因**：`HilogStream.pump` 的写盘线程是 **daemon 线程**，挂在 `log start` 进程里。`start` 一返回主进程退出，daemon 线程被 JVM **直接杀掉**（不走 `finally`）：

1. 采集停止——文件只有「等首行落地」那 2~3 秒的数据；
2. 正在写的 gzip member 被截断，文件尾部残缺。

`LogWatchdog` 的注释写的是「本进程已退出，hdc 写 hilog 的子进程还在」，但**实际写文件的是 Kotlin 线程，不是 hdc**——注释描述的是意图，实现没有对上。

**修复**：把泵移进独立进程。

| 改动 | 内容 |
|---|---|
| 新增隐藏子命令 | `ohd log __pump <文件> [--device …]`，由它持有流并持续写盘 |
| `log start` | 用 `Self.launchArgv()` 拉起 `__pump`（`Redirect.DISCARD`，不占终端），记录 `pumpPid` |
| `log stop` | 对 `pumpPid` 先 SIGTERM（`graceMs=2500`），触发 `__pump` 的 shutdown hook 完成 `flush`+`close`，再兜底 SIGKILL |
| 状态文件 | 新增 `pumpPid`，兼容旧文件（缺字段时退回 `pid`） |

`__pump` 与 `start` 分离的**唯一原因**是生命周期：start 必须立刻返回，泵必须活到 stop。

**验证**：

| 项 | 修复前 | 修复后 |
|---|---|---|
| start 后 5 秒文件 | 1353080 → 1353080（不变） | 2782764 → 2964222（增长） |
| `gunzip -t` | `unexpected end of file` | 通过 |
| 同 2~3 秒采集行数 | 48600（仅首行等待期） | **125818** |
| OHOS 回归 | — | 9202 行，gz 完整 |

> `MonitorCap` 同样调用 `HilogStream.pump`，但它是前台跑到 duration 结束，泵线程生命周期与进程一致，**不受此 bug 影响**。

### 91.2 bug B：扩展死循环会永久挂死 ohd

**现象**：扩展里 `while true; do sleep 1; done`，ohd 无超时，`waitFor()` 永不返回。

**隔离性实测**（崩溃本身不影响 ohd，退出码原样传递）：

| 扩展行为 | ohd 退出码 |
|---|---|
| `kill -9 $$` | 137 |
| `kill -SEGV $$` | 139 |
| `exit 42` | 42 |

**修复**：`Extensions.exec` 增加 `timeoutMs`（默认 `DEFAULT_TIMEOUT_MS = 30min`），超时先 `destroyForcibly` 子孙进程再杀本体，返回 `ExitCode.TIMEOUT(6)` 并给出提示。`--ext-timeout <d>` 可覆盖，`0` 表示不限。

**验证**：`--ext-timeout 3s` → 3 秒后终止，退出码 6 ✓

剩余未兜底的风险（已知并接受）：扩展**无内存上限**；`inheritIO()` 意味着扩展可直接污染终端输出。

### 91.3 配套：Java 扩展示例

`examples/extensions/jlog.java`（与 `hlog.sh` 功能等价），实测输出一致（125818 vs 125820 行，error 均 3897）。该示例演示了 JDK 11+ 单文件源码模式：`java jlog.java` 隐式编译到内存执行，无需 `javac`，也无需构建步骤。

因 91.1 的 gz 尾部截断在修复前必现，示例仍保留了对 `IOException` 的容忍（读到 EOF 即停），对**历史产物**依然有效。

## 92. 兜住扩展的两个剩余风险：内存 / 终端污染（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **前置**：§91（超时 + 日志流修复）

### 92.1 内存上限：分平台分层，做不到的不假装

macOS 实测（这是关键约束）：

```
sh -c 'ulimit -d 262144; python3 -c "bytearray(400MB)"'  → 分配成功（-d 被忽略）
sh -c 'ulimit -v 262144'                                  → Invalid argument（不支持）
```

所以 **macOS 无法用 ulimit 限制任意子进程内存**。按能真正生效的方式分层：

| 方案 | 覆盖面 | 可靠性 |
|---|---|---|
| Java `-Xmx<limit>` | `.java` 扩展 | **硬限**，JVM 自己拒绝分配 |
| `ulimit -v` 包装 | Linux 非 Java | 有效（macOS 跳过） |
| 超时保护 | 全部 | 兜底：至少不会永久挂住 |

默认 `2g`，`--ext-mem 2g/512m/0` 可调（0 = 不限）。

**验证**：Java 扩展分配 1.5GB

```
--ext-mem 256m  → java.lang.OutOfMemoryError: Java heap space   （被 JVM 挡住）
默认 2g          → 分配成功 1572864000 字节
```

> macOS 上 shell/python 扩展**仍无内存硬限**——这是平台能力缺失，不写成"已解决"。

### 92.2 终端污染：从 inheritIO 改为接管输出

`inheritIO()` 的问题：扩展可以直接清屏、移动光标、改窗口标题，也能喷二进制。

改为默认**接管 stdout/stderr**（`redirectErrorStream`），逐行经 UTF-8 解码后转发：

| 防线 | 行为 |
|---|---|
| 行数上限 | 默认 100000 行，超出打印截断提示（`--ext-max-lines` 可调） |
| 非法字节 | UTF-8 解码替换，二进制不会直接进终端 |
| **转义序列** | 剥离 ANSI CSI / OSC 序列，清屏与光标控制失效 |
| C0 控制字符 | 剥离（保留 `\n` `\t`） |

交互式扩展用 `--ext-raw` 恢复 `inheritIO()` 直通。

**踩坑记录**：过滤顺序必须是「先剥转义序列，再清控制字符」。反过来的话 ESC 先被当控制字符删掉，`ESC[2J` 就只剩下 `[2J` 漏进终端——实测第一版就是这样。

**验证**：

```
默认     → "�� 二进制"（控制序列与 \x00\x01 均已剥除，提示已过滤 1 行）
--ext-raw → 原样输出 ^[[2J
```

### 92.3 回归

| 项 | 结果 |
|---|---|
| `hlog.sh` | 126586 行，error 统计正常 |
| `jlog.java`（加了 `-Xmx`） | 126613 行，与 shell 版一致 |
| 中文输出 | 正常（`二进制`） |

### 92.4 仍然存在的边界

- macOS 上非 Java 扩展无内存硬限（平台无能力，已如实标注）；
- 扩展可通过 `/dev/tty` 绕过输出接管（无法拦截）；
- `--ext-raw` 下所有防护失效，由使用者自己负责。

## 93. 跨平台审计：`ohd selftest --platform-audit`（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **背景**：用户质疑「有多少能力依赖 macOS」，审计结论见对话；本节把结论变成**可执行的断言**

### 93.1 审计事实（基线）

| 维度 | 数量 |
|---|---|
| Kotlin 本体 | 67 文件 / 11931 行 |
| 宿主侧 `ProcessBuilder` | **9 处 / 9 文件** |
| 平台分支（`Platform.isX`） | 15 处，集中在 `Env.kt` / `Extensions.kt` / `Self.kt` |
| 设备端命令调用（经 hdc/adb） | 82 处 —— **与宿主平台无关** |
| macOS 专属命令（`open`/`pbcopy`/`osascript`/`launchctl`） | **0** |

关键区分：`hdc shell("df -h")` 里的 `df/grep/ps` 跑在**设备**上，不是 macOS 上。

### 93.2 实现

新增 [HostCommands.kt](ohd-core/src/main/kotlin/ohd/core/HostCommands.kt) 作为宿主命令**唯一真源**（hdc / adb / java / llvm-addr2line / llvm-symbolizer / ohd），并登记 9 个源码里允许出现 `ProcessBuilder(` 的文件与次数。

`ohd selftest --platform-audit` 做四件事：

1. 平台事实（OS/arch + 三个 `Platform.isX` 取值）；
2. 宿主白名单清单（唯一真源原样输出）；
3. **静态扫描源码**：统计每个 `.kt` 里真实的 `ProcessBuilder(` 调用点，与登记表比对；
4. 扩展在当前平台的可运行性（Windows 上的 `.sh` 会被标为不可运行）。

四类输出：`OK` / `FAIL`；有 `FAIL` 时退出码 `4`（`ASSERT_FAILED`）。

### 93.3 踩的三个坑

1. **误把注释和字符串当调用**：首版把 KDoc 里的 `` `ProcessBuilder(` `` 和 `"ProcessBuilder(" in it` 也计入，导致 `HostCommands.kt`/`SelftestCap.kt` 自报违规。修正为跳过 `//`、`*`、`/*` 开头与含 `"ProcessBuilder(` 的行。
2. **只扫 `ohd-core` 漏掉 `ohd-cli`**：`Main.kt` 里并行子进程的启动点没被扫到，登记表报「次数不符」。改为同时定位 `ohd-core/src/main/kotlin` 与 `ohd-cli/src/main/kotlin`。
3. `!"x" in raw` 在 Kotlin 里解析成 `(!("x")) in raw`，编译报 `Unresolved reference 'not'`；改写为 `!raw.contains(...)`。

### 93.4 验证

```
$ ohd selftest --platform-audit
host-sites   OK   9 处调用，分布在 9 个文件，全部已登记
退出码 0
```

故意植入违规（新增 `EvilProbe.kt` 调用 `ProcessBuilder("osascript", ...)`）：

```
host-sites   FAIL 未登记的宿主进程启动点：[EvilProbe.kt] → 在 HostCommands 登记用途，或不要新增宿主命令
退出码 4
```

删掉即恢复 0。**拦截有效**。

### 93.5 局限（不假装）

- 扫描的是「文件 + 次数」，**不解析变量最终值**——无法证明 `ProcessBuilder(cmd)` 里的 `cmd` 一定是 hdc；
- 发布包没有源码，静态部分自动跳过并明示「跳过静态审计」，此时只有运行时工具检查；
- 判据是「新增未登记」，不覆盖「把已登记文件的命令换成别的」这种就地篡改。

## 94. iOS 支持：模拟器观测子集（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **环境**：Xcode 26.2 / iOS 26.2 模拟器（iPhone 17 Pro，UDID `C55F20ED-01E0-42AE-846D-07998549B3A6`）  
> **范围**：用户选择「只做观测子集」——交互类明确不支持，不假装执行

### 94.1 能力边界（实测决定，不是估计）

先探测再动手，实测结论：

| 能力 | iOS 模拟器 | 依据 |
|---|---|---|
| 设备枚举 | ✓ | `simctl list devices`，只取 **Booted** |
| 截图 | ✓ | `simctl io <udid> screenshot` |
| 日志 | ✓ | `simctl spawn <udid> log stream --style compact` |
| 内存 | ✓ | 宿主 `footprint -p <pid>` |
| 进程定位 | ✓ | `launchctl list`（label 含 bundle） |
| 安装 | ✓ | `simctl install`（`.app` 目录） |
| **触摸注入** | ✗ | `simctl` 无 `tap`/`swipe`/`key` 子命令 |
| **UI 层级** | ✗ | 无 accessibility dump |
| **进程 CPU** | ✗ | 模拟器内没有 `ps`/`top`（连 `head` 都没有） |

不可用的能力**在 `Hdcs.forDevice` 处统一拦截**，早于任何副作用：

```
$ ohd monkey --device C55F20ED-... 
x monkey 在 iOS 模拟器上不可用：需要触摸事件注入：simctl 没有 tap/swipe 通道
  -> iOS 侧目前只做观测：devices/install/log/shot/mem
```

覆盖 monkey / replay / tap / input / tree / record / cpu / dmabuf / soak / ab / repro / watch / salvage。

### 94.2 三个必须踩过的坑

1. **`log` 不能经 `sh -c` 走**
   `simctl spawn <udid> /bin/sh -c "log stream ..."` 直接崩：
   ```
   dyld: DYLD_ROOT_PATH not set for simulator program
   Child process terminated with signal 6: Abort trap
   ```
   `log` 是 macOS 通用二进制，经 shell 转一手拿不到 `DYLD_ROOT_PATH`。必须把 `log` 作为 `spawn` 的第一个参数直连。`Hdc.shellArgv` 因此对 iOS 做参数拆分而非套 `sh -c`。

2. **没有 `ps`，进程靠 `launchctl list` 反查**
   模拟器内 `ps`/`pidof` 都不存在。`DeviceOps.resolvePid` 的 iOS 分支从 `launchctl list` 的 `PID Status Label` 里按 `UIKitApplication:com.xxx` 匹配。

3. **`.app` 是目录，不是文件**
   `InstallCap` 原来用 `Files.isRegularFile(hap)` 校验，iOS 会直接判不存在；体积也要递归统计。且 `simctl install` **没有 `-r`**，传了会被当成路径。

### 94.3 架构接入点

| 位置 | 改动 |
|---|---|
| `DeviceKind` | 新增 `IOS("ios", "simctl")`，`of()` 认 `ios/simctl/iphone` |
| `Hdc.wrap` | simctl 的 device 在**子命令之后**，按子命令决定是否插入（`list devices` 不插） |
| `Hdc.shell` | iOS 映射到 `spawn <udid> /bin/sh -c` |
| `Env.simctl` | `OHD_SIMCTL` → `DEVELOPER_DIR` → 默认 Xcode 路径 → PATH；非 macOS 直接判不可用 |
| `Devices.all` | 追加 iOS 候选 |
| `HostCommands` | 登记 `simctl`、`footprint` |
| `RunContext` | 新增 `capability` 字段，供统一边界检查 |

加 `IOS` 到 enum 时，编译器一次性报出 **15 处** `when(kind)` 遗漏——这是 Kotlin 穷尽 `when` 的价值，没有漏网。

### 94.4 验证

| 项 | 结果 |
|---|---|
| 设备枚举 | `--all` 列出 `63R0226113004249(ohos)`、`C55F20ED-...(ios)` |
| shot | 1206 × 2622 PNG |
| mem | Footprint 31744 KB + Malloc 分类（Medium 7056 / Nano 5728 / Small 3840 / Tiny 3584） |
| log | 5 秒采到 41 行 / 1455 字节，gz 完整 |
| install | `MusicFree.app` 56.7 MB 覆盖安装成功 |
| 交互能力 | 明确拒绝并给出原因，退出码 5 |
| OHOS/Android 回归 | 非 iOS 分支走原逻辑，`when` 仅新增分支不改旧路径 |

### 94.5 局限

- **没有 CPU 采样**：模拟器无 `ps`/`top`，这是平台缺失；
- **内存口径不齐**：iOS 只有 `Footprint`，`SwapPss` / `GL` / `Graph` 字段留 `-` 而不是填 0；
- **崩溃日志未接入**：落在宿主 `~/Library/Logs/DiagnosticReports`，需要新解析器；
- **不枚举 Shutdown 模拟器**：它们连 `spawn` 都跑不了，列出来只会误导。

## 95. iOS 的崩溃 / 内存 / CPU 三个口径（2026-10-22 追加）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **本节**：澄清 §94.5 里「崩溃未接入」的现状，并逐项给出可行性

### 95.1 结论

| 口径 | 状态 | 数据源 |
|---|---|---|
| 内存 | **可用** | 宿主 `footprint -p <pid>` |
| 崩溃 | **可用**（本次实现） | 宿主 `~/Library/Logs/DiagnosticReports` 的 `.ips` |
| CPU | **不可用** | 模拟器内无 `ps`/`top`；宿主同名工具被沙箱拦 |

### 95.2 崩溃：数据源在宿主，不在设备

模拟器里的进程崩溃后，CoreSimulator 把系统级崩溃报告写到**宿主**的
`~/Library/Logs/DiagnosticReports`，格式是 `.ips`（首行 metadata JSON + 其后 payload JSON）。

新增 [IosCrashParser.kt](ohd-core/src/main/kotlin/ohd/core/parsers/IosCrashParser.kt)：
解析 `exception{type,signal}`、`termination{reasons}`、`threads`，按 `os_version` 过滤。

**过滤是必须的**：同一目录里也躺着宿主自身工具（`bash`/`log`/`ps`）的崩溃，
那些不是「应用崩溃」。实测 `simctl spawn booted /bin/sh -c 'kill -SEGV $$'` 产生的报告
`os_version` 写的是 `macOS 27.0.1`——因为 `/bin/sh` 是宿主二进制，不是模拟器 app。
真正的 iOS app 崩溃才会标 `iOS`。

验证（用一个已知 `.ips` 直连，跳过过滤）：

```
$ ohd crash --name bash-2026-10-22-112609.ips --details --device <udid>
崩溃文件   bash-2026-10-22-112609.ips
应用       bash
系统       macOS 27.0.1 (26A434)
类型       309（crash）
异常       EXC_CRASH / SIGSEGV
线程数     2
```

顺带修了一个既有缺陷：`--name` 显式指定报告时，不再要求列表非空
（用户可能指向一个不在当前列表里的历史报告）。

**未验证项**：没有真实的 iOS app 崩溃样本，`os_version` 过滤对 iOS 的命中尚未实测。

### 95.3 CPU：两层原因都指向「拿不到」

1. 模拟器内**没有** `ps`/`top`（连 `head` 都没有）；
2. 宿主 `/bin/ps`、`/usr/bin/top` 在**当前沙箱**里被拒：`Operation not permitted`。

注意第 2 条是**沙箱策略**而非 macOS 能力——同一环境下 `footprint` 却能正常执行，
说明是按工具选择性放行。所以：

- 在非沙箱环境（用户自己跑 `./ohd`）里，`ps -o %cpu -p <pid>` 很可能可用；
- 但**本会话无法验证**，因此没有并入代码，避免交付未验证路径。

### 95.4 当前 iOS 能力总表

| 能力 | 状态 |
|---|---|
| 设备枚举 / 截图 / 日志 / 安装 / 进程定位 / 内存 / 崩溃 | 可用 |
| CPU / 触摸 / UI 树 / 录制 / dmabuf | 不可用（平台缺失或沙箱拦截） |

## 96. iPhone 真机支持：devicectl 原生子集（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：iPhone 17（`iPhone18,3`，iOS 26.6.1），`A31D9437-B2A7-5699-A4D6-0274B8F7834F`  
> **范围**：用户选择「只做 devicectl 原生能力」，不引第三方依赖

### 96.1 真机比模拟器更窄，这是反直觉的

模拟器能 `simctl spawn <udid> /bin/sh` 跑任意命令，所以日志/内存/截图都能从设备里读；
**真机完全没有 shell**，`devicectl` 只给出固定子命令（install / process / copy / info）。
结果就是真机的可用面**小于**模拟器：

| 能力 | 模拟器 | 真机 |
|---|---|---|
| 安装 / 启动 / 终止 / 文件拷贝 / app 列表 | — | ✓ |
| 截图 / 日志 / 内存 / CPU | ✓ | ✗ |

`devicectl process sendMemoryWarning` 是发内存警告，不是采样，别误当内存指标。

### 96.2 通道实现

新增 `DeviceKind.IOS_DEVICE("ios-device", "devicectl")`。它与 `IOS` 的差别是**命令层级**不同：

```
simctl   ：simctl <sub> <udid> ...
devicectl：devicectl device <verb> <noun> --device <id> ...
```

所以 `Hdc.wrap` 里不能套同一个模板，单独写了 `devicectlArgv()` 做映射。

两个必须记住的点：

1. **devicectl 的唯一脚本接口是 JSON 文件**
   stdout 不保证稳定。`targets()` 走 `devicectl list devices --json-output <tmp>`，
   再解析 `"identifier": "<36位UUID>"`。
2. **devicectl 是 zsh 包装脚本**（719 字节），会自己算 `DEVELOPER_DIR` 再 exec 真二进制，
   直接调用无需 `xcrun`。

`--platform ios` 是一个**平台族**：模拟器与真机都算，两者同时在线时会要求 `--device` 消歧。

### 96.3 踩到的坑：`list devices` 参数重复

`targets()` 传的是 `["list","devices","--json-output",tmp]`，而 `devicectlArgv` 的 `list` 分支
又补了一个 `devices`，拼成 `list devices devices`，devicectl 直接回 `Unexpected argument 'devices'`（exit 64）。

外层 `runCatching{...}.getOrDefault(emptyList())` 把这个失败**静默吞掉了**——
表现就是「真机明明连着却不在列表里」。
教训：枚举类调用失败必须能看见原因，否则会浪费一轮盲猜。

### 96.4 当前阻塞：Developer Mode

真机 install 通道已打通，但实测停在：

```
ERROR: The operation failed because Developer Mode is disabled. (com.apple.dt.CoreDeviceError error 10005)
```

需要用户在设备上开启：**设置 → 隐私与安全性 → 开发者模式 → 开启 → 重启**。
开启后还需：已信任这台 Mac + `.app` 用有效证书签名（免费开发者账号即可）。

### 96.5 验证

| 项 | 结果 |
|---|---|
| 设备枚举 | `ios-device A31D9437-...` 出现在候选列表 |
| `--platform ios` | 两台同时在线时报歧义，并列出模拟器与真机 |
| install | 到达 `devicectl device install app`，被 Developer Mode 拦截 |
| shot/log/mem/crash | 在能力边界处明确拒绝，说明缺 idevicescreenshot / idevicesyslog / xctrace |
| 平台审计 | `host-sites OK 10 处 / 10 个文件`（新增 MemCap 已登记）；`devicectl` 已入白名单 |

## 97. iOS 触摸注入：idb 接入（2026-10-22）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **设备**：iPhone 17 Pro 模拟器 `C55F20ED-01E0-42AE-846D-07998549B3A6`（iOS 26.2）  
> **依赖**：`idb-companion 1.7.6`（brew）+ `fb-idb 1.1.7`（pip）

### 97.1 为什么是 idb，不是 WDA / swift-monkey

三个方案底层都能注入，差别不在平台覆盖（三者都支持模拟器和真机），而在**是否要改目标 app 工程**：

| | 模拟器 | 真机 | 改目标工程 | 集成方式 |
|---|---|---|---|---|
| **idb** | ✓ | ✓（FBDeviceControl） | 否 | CLI / gRPC |
| WDA | ✓ | ✓ | 否（独立 runner） | HTTP |
| swift-monkey | ✓ | ✓ | **是**（加 UITests target） | 无法外部调用 |

ohd 是外部诊断工具，不能要求用户先改自己的工程，所以 swift-monkey 直接出局。
idb 与 WDA 二选一，选 idb 因为它同时给出 UI 层级（`describe-all`）和手势注入，一套依赖解决两个需求。

> 早前我判断「idb 已归档、brew formula 消失」是**错的**：`brew search` 查不到是因为 formula 在
> `facebook/fb` 独立 tap 里，而沙箱写不了 `/opt/homebrew`。项目 HEAD 提交是 2026-10-21，一直在维护。

### 97.2 坐标系：逻辑点 vs 像素

这是接入时最容易错的一点：

- 截图 `simctl io screenshot` 是**像素**（1206×2622）
- `idb ui tap` 与 `describe-all` 的 `frame` 是**逻辑点**（402×874）

两者差 3 倍。`ohd` 在 iOS 上统一用**逻辑点**：`captureLayout` 解析出的 bounds 直接喂给注入器，
中间不做 scale 换算。若误把截图像素当注入坐标，会整屏偏移。

### 97.3 describe-all 是平铺数组

与 OHOS `uitest dumpLayout` 的树形结构不同，`idb ui describe-all` 返回**无父子关系的平铺 JSON**：

```json
{"pid":60337,"frame":{"x":0,"y":0,"width":402,"height":874},
 "role":"AXButton","AXLabel":"Dismiss (ESC)","enabled":true,"traits":["Button"]}
```

所以 `IosLayoutParser` 产出的 `LayoutNode.children` 恒为空，`depth` 恒为 0。
可点判定靠 `role ∈ {AXButton, AXLink, AXCell...}` 或 `traits` 含 Button/Link 等。

### 97.4 踩到的三个坑

**坑 1：`cmds.isEmpty()` 让 monkey 一步都发不出去**

MonkeyCap 的批量判定原本是 `if (cmds.isEmpty()) continue`。iOS 上 `shellCommand` 恒为 null，
`cmds` 永远是空的 → 每批都被判定「没有动作」直接跳过，跑 25 秒产出 0 个动作。

修复：`if (cmds.isEmpty() && acts.isEmpty())`。判定要落在**实际能执行的东西**上，
而不是「生成的 shell 字符串」这种平台特有的中间产物。

**坑 2：`sh -c launchctl list` 会 abort**

`DeviceOps.resolvePid` 的 iOS 分支原本走 `hdc.shell("launchctl list")`，而 `shell()` 会用
`simctl spawn <udid> /bin/sh -c ...`。`launchctl` 是 macOS 通用二进制，在模拟器里经 sh 转手会因
缺 `DYLD_ROOT_PATH` 而 `signal 6` abort——和 §94 里 `log stream` 是同一个坑。

修复：改直连 `hdc.run(listOf("spawn", "launchctl", "list"))`。

> 规律：模拟器里凡是 **macOS 通用二进制**（log / launchctl / ps…）都不能经 `sh -c` 转手，
> 必须直接 `spawn`。只有模拟器自带的 shell 内建与脚本才能走 `shell()`。

**坑 3：`key Back` 让回放中断**

`Kind.KEY` 的实参既可能是 HID 码也可能是按钮名（`key Back` 传的是字符串）。
iOS 只有 Home 一个硬件键，`args[0].toInt()` 对 "Back" 直接抛异常。

修复：按实参类型分派——数字走 `idb ui key`，`Home` 走 `idb ui button HOME`，
其余明确回报「iOS 没有 X 这个按键」。

另外回放的 `keepGoing` 默认 false，遇到第一个失败就停。`key Back` 的失败会让后续 11 步不执行，
这不是 bug 而是设计（继续点会打在别的页面上）。要完整回放需 `--keep-going`。

### 97.5 验证结果

| 项 | 结果 |
|---|---|
| `idb list-targets` | 列出全部模拟器，companion 与 Xcode 26.2 兼容 |
| `idb ui button HOME` | 界面 md5 变化 → **注入确实生效** |
| `ohd tap` | `点击 (200, 400)` 成功；点在无可交互元素处界面不变属正常 |
| `ohd monkey` | 20s 产出 15 行动作脚本，坐标全在 402×874 内 |
| `ohd replay --keep-going` | **13/13 执行，成功 12，失败 1**（即 `key Back`） |
| 平台审计 | `host-sites OK 11 处 / 11 个文件`（新增 TouchInjector 已登记） |

### 97.6 尚未验证

| 项 | 状态 |
|---|---|
| 真机 idb 注入 | 未验证。`idb` 依赖 FBDeviceControl，真机 HID 历史上弱于模拟器，需实机测 |
| 注入高频性能 | 未测。每次 `idb ui tap` 是一次进程启动 + gRPC，非长连接；monkey 间隔 800ms 够用，更高频需改 client 常驻 |
| 手势语义保真 | `long_press` 用 `--duration 1.0` 近似，`double_tap` 用两次 tap + 80ms 间隔近似，非原生语义 |
| `tree` 能力 | 数据源已就绪（describe-all → IosLayoutParser），但 `tree` 能力本身仍未对 iOS 放开 |

## 98. iOS 支持现行状态对照（2026-10-22 追加）

> **日期**：2026-10-22  
> **仓库**：`/Users/xuxingwang/KMP/ohd`（非 git，脏树）  
> **锚定**：本节状态对应 `ohd-core/src/main/kotlin/ohd/core/{TouchInjector,IosSupport,DeviceOps}.kt`、
> `ohd-core/src/main/kotlin/ohd/core/caps/{MonkeyCap,ReplayCap,TapInputCap,InstallCap}.kt`、
> `ohd-core/src/main/kotlin/ohd/core/parsers/IosLayoutParser.kt`  
> **用途**：§94–§96 是逐阶段沉淀的，其中若干「不可用」结论已被 §97 推翻。历史正文按
> `engineering-doc-append-only` 保留不改，本节统一给出**现行**口径。

### 98.1 被推翻的旧结论

| 位置 | 旧结论 | 现行状态 | 变更依据 |
|---|---|---|---|
| §94 | `monkey` 在 iOS 模拟器上不可用：simctl 没有 tap/swipe 通道 | **可用**。20s 产出 15 行动作脚本 | §97 idb 接入 |
| §94 尾表 | 触摸 → 不可用 | **可用**：`tap`/`swipe`/`key`/`input`/`monkey`/`replay` | §97 |
| §94 尾表 | UI 树 → 不可用 | **数据源就绪**：`describe-all` → `IosLayoutParser`；`tree` 能力本身仍未放开 | §97.3 |
| §96 | 真机只有 devicectl 原生子集（install/launch/copy/apps/crash） | 代码层面交互已放开（走 idb），但**真机注入未实测** | §97.6 |
| §96 | `install` 会拼 `-r` | 已修：模拟器与真机都不带 `-r` | InstallCap 判定已含 `IOS_DEVICE` |

### 98.2 仍然成立的结论

| 位置 | 结论 | 现状 |
|---|---|---|
| §94 | iOS 没有公开的跨进程触控 API | 成立。必须借 idb(私有 HID) 或 XCUITest |
| §95 | CPU 拿不到 | 成立。模拟器无 `ps`/`top`，宿主同名工具被沙箱拦 |
| §95 | 内存只能拿 footprint 口径 | 成立。PSS/DMA 等维度在 iOS 无对应物，渲染为 `-` |
| §96.4 | 真机 install 需开发者模式 + 签名 | 成立。`developerModeStatus` 已置 `enabled` 后可继续，但仍需签名包 |
| §97 | idb 是双端方案，swift-monkey 因需改目标工程而出局 | 成立 |

### 98.3 三个平台的分层归属（现行）

| 层 | OHOS | Android | iOS 模拟器 | iPhone 真机 |
|---|---|---|---|---|
| 通道 | `hdc`（有 shell） | `adb`（有 shell） | `simctl`（有 shell，但 macOS 二进制不能经 `sh -c`） | `devicectl`（**无 shell**） |
| 命令模板 | `uitest` / `aa` / `hidumper` | `input` / `am` / `dumpsys` | 同 OHOS 走 `simctl spawn` | 固定子命令，无命令模板 |
| 注入 | `uitest uiInput` | `input tap/swipe` | **idb** | **idb**（未实测） |

> iOS 的 `DeviceKind` 有两个值：`IOS("ios")` 与 `IOS_DEVICE("ios-device")`。
> `--platform ios` 是**平台族**，同时匹配两者；都在线时会要求 `--device` 消歧。

### 98.4 文档自身的一致性问题（已修）

`README.md` 原为纯 OHOS 口径（标题「OHOS 真机能力工具箱」、前置依赖无 Xcode/idb），
已补：跨平台标题、iOS 可选依赖、**平台覆盖矩阵**一节。DESIGN 侧按 append-only 只追加本节。
