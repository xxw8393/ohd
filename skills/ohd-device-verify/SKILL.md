---
name: ohd-device-verify
description: >-
  用 ohd 工具在 OHOS 真机上做验证：节点树/点按滑动/截图定位/日志/崩溃/内存 PSS/CPU/DMA/UI 截图对比/回放/压测，
  并把结果打成可交给开发的缺陷包。WHEN 需要操作真机确认现象、复现缺陷、抓崩溃栈、看内存或 CPU、
  对比改动前后界面、生成「开发拿到就能动手」的证据包、或对 UI 做长时间 monkey 压测。
---

# ohd 真机验证

工具：`/Users/xuxingwang/KMP/ohd`（Kotlin/JVM，mac/Win/Linux 通用）。
入口：`dist/ohd`（Windows `dist\ohd.bat`），下文简写 `ohd`。

```bash
OHD=/Users/xuxingwang/KMP/ohd/dist/ohd
$OHD doctor          # 先自证环境：hdc 找得到、设备在线
```

**铁律：ohd 只产证据，不下根因。** `analyze`/`report`/`soak` 的输出是事实和形状标签，
"是不是缺陷、谁的锅"由你结合用例判断。别把工具输出直接当结论转述。

## 单命令的结论边界（局部使用是常态）

不必每次都跑全套。但每个命令只支撑特定结论，**越界就是误报**：

| 命令 | 能回答 | 不能回答 |
|---|---|---|
| `shot` | 此刻界面长什么样、坐标在哪 | 操作后是否崩溃、内存是否泄漏、以前是否正常 |
| `tree` | 当前界面结构、哪些节点可点 | 点了会不会崩、有没有残留状态 |
| `log` | 这段时间发生了什么 | 内存/CPU 的变化趋势 |
| `crash` | 崩了没有、崩在哪 | 为什么崩（要 `sym`）、怎么复现（要步骤） |
| `mem` | 此刻内存与对象分布 | 会不会泄漏（要 `soak`/`watch` 的时间维度） |
| `diff` | 两个界面差在哪些像素 | 差的部分算不算缺陷 |

一句话：**单命令给的是「当下快照」；任何带「会不会」「是不是」的结论，都需要时间维度或多份证据。**

## 决策树（按要解决的问题选命令）

| 要做什么 | 命令 | 备注 |
|---|---|---|
| 环境/设备连通性 | `doctor` | 失败先看 `来源` 字段 |
| 设备信息 | `env` | 型号/API/ABI/内存；`env-dump` 是归档版 |
| 找控件坐标 | `tree --grep "文字" --clickable` | 输出含中心坐标，直接喂 `tap`；**`NO_MATCH` 禁止盲 tap** → skill `ohd-ui-action-validity` + short-run `drive-hygiene` |
| 点按/滑动/输入 | `tap <x> <y>`（`--double`/`--long`）/ `input swipe x1 y1 x2 y2 [速度]` / `input text <x> <y> <文本>` / `input key Back` | |
| 截图确认界面 | `shot` | 加 `--grid 100` 出坐标网格；`--boxes` 框出节点 |
| 抓日志 | `log start` → 操作 → `log save` | 或 `log snap` 抓当前缓冲 |
| 崩溃列表/详情 | `crash --list` → `crash --name <f> --details` | 落盘前先 `--list` 看有没有 |
| 崩溃栈符号化 | `sym --crash <faultlog> --so-dir a:b` | 或 `sym --latest`；buildId 不符加 `--allow-mismatch` |
| 内存快照 | `mem --bundle <包名> --top 15` | 加 `--show-dmabuf` 带上 DMA |
| 内存短跟踪 | `watch --bundle b --duration 60s` | 每次读取间隔 `--interval` |
| 内存长稳 | `soak --bundle b --duration 30m` | 有 `--drive <脚本>` 可边跑边压 |
| CPU 热点 | `cpu --bundle b --top 15` | `--cpufreq` 看频率 |
| DMA buffer | `dmabuf snap --bundle b` / `dmabuf diff a b` | 对比两次快照看增量 |
| 改动前后 UI 对比 | `diff --baseline a.png --current b.png` | 阈值 `--threshold`，占比 `--allow-ratio` |
| 回放操作序列 | `replay steps.ohd` | 加 `--record` 出 `replay.gif` |
| hilog 推路径 | `path <hilog.txt> --coords …` | 页面级；缺坐标出 `# TODO` |
| 长时压测 | `monkey --bundle b --duration 20m` | 见「压测提速」；要扛崩溃加 `--keep-going`（见「长跑」） |
| **无障碍热路径对等 DevEco** | `monkey … --keep-a11y` | uitest+accessibility 保活；见 skill `ohd-a11y-uitest-activate` |
| 收网看总账 | `summary --since 12h` | 读磁盘不碰设备；`--json` 给脚本，`--limit` 控行数 |
| automation REPORT 分流 | `triage <capture-dir>` | 映射 pixelmap/xcomponent/native-heap 赛道；`--reverify` 调 dmabuf/mem |
| 生成缺陷包 | `repro` → `bundle --redact --report --zip` | 见下方闭环 |

## 什么时候必须让证据自洽

自己看、自己判断 → 上面任一命令够了。

**结论要交给别人时**（缺陷单、PR 评论、让开发排查）必须自洽——对方无法追问，只能靠包里的东西
独立回答：哪台机器、什么版本、什么操作、崩了没有、怎么复现。**少一件，开发就得回来问你**，
这张单子也就没起到「拿起来就能动手」的作用。

## 缺陷包闭环（ohd 的核心价值）

```bash
# 1. 复现并记录步骤
$OHD repro --steps steps.ohd

# 2. 交研发推荐（默认合并近 2h 会话；要旧「只打最新」加 --latest-only）
$OHD bundle --out /tmp/case --name 闪退-打开相册 --redact --report --zip

# 3. 交给开发。开发侧只读：
#    README.md           人读：缺什么件、怎么复现、脱敏说明、生成环境
#    report.md           缺陷单正文（与 ohd report 同源）
#    context.json        机器读：产物索引 + sha256
#    COMPLETENESS.json   约束读：七类必备件是否齐全
#    旁路 zip            同名 .zip，目录仍保留
```

`bundle` 退出码 **4 = 必备件缺失或 zip 失败**（包目录已写出，不是执行半途崩），缺什么在 README / `COMPLETENESS.json` 里写明。
`--redact` 换掉用户路径/序列号/IP/MAC/手机号/邮箱/token，但**截图和裸 IP 不处理**。

## 压测提速（实测数据）

| 参数 | 效果 |
|---|---|
| `--interval 0` | **2.1 倍**（最大收益项） |
| `--batch 10` | 再 +7%（设备端每 click 固定 ~300ms，批量只省下发开销） |
| `--check-every 30 --sample-every 20` | 避免采样拖慢步频 |

```bash
$OHD monkey --bundle com.example --duration 20m --interval 0 --batch 10 \
            --stagnate 60 --sample-every 20 --check-every 30
```

实测 41 → 113 步/分。`--stagnate`：界面 N 步不变就自动注入 Back/Home 脱困（默认 60）。

### 长跑（≥1 小时，可跨崩溃）

```bash
$OHD monkey --bundle b --duration 10h --interval 0 --batch 10 \
            --sample-every 60 --check-every 30 --keep-going
```

- **`--keep-going`**：崩溃后 `aa force-stop` + `aa start` 重启继续，每次崩溃都留存 faultlog + hilog；
  结尾「崩溃次数 N」，JSON 里 `crashCount`；`manifest.verdict` 仍是 `CRASH`（主结论只记首次）。
  **应用 cppcrash 不会、也不应打断 monkey**；若 monkey 停了，先查 `--out` 是否误传目录、是否未脱管（见下），别先怪崩溃。
- **必须脱管**：长跑进程若挂在 IDE / agent 会话下，父会话重启会把它一起杀掉（实测 42 分钟被带走）。
  脱离方式见 `ohd/CROSS-PLATFORM.md` §11：Linux `setsid nohup ... &`；
  **macOS 没有 `setsid`**，用 `python3 -c '...subprocess.Popen(..., start_new_session=True)'`。
- 校验脱管：`ps -o pid,ppid,cmd -p <pid>`，**ppid=1** 即成功。
- 收网：`ohd summary --since 10h`（只读磁盘，不碰设备）。
- **并行内存长采连招**（monkey + 插件 `ohos_automation_scene_capture`）：→ skill `ohd-monkey-scene-capture`（`scripts/run_detached.py`）。

## 符号化的三个边界

1. **stripped so 出不了符号**：`file x.so` 显示 `stripped` 时 addr2line 只给 `??:0`。
   源码工程里未 strip 的原件在 `harmonyApp/entry/libs/<abi>/`（比 hap 里大一倍以上），
   hvigor 打包时才复制并 strip。**要符号就指这个目录，别拆 hap。**
2. **buildId 必须对得上**：默认不匹配就拒绝出符号（防止地址漂移给出看似正确的错函数名）。
   但相邻版本多数帧仍落在同一函数——需要「先拿名字再人工判断」时加 `--allow-mismatch`，
   输出仍标 `BUILD_ID_MISMATCH`、退出码 0。**能不能接受错位由使用者判断，工具只负责标注。**
3. **行号可能是 `:0`**：`-O3` 编译会粗化行号映射，函数名有、行号没有，不是工具 bug。

## 关键坑（都是实测踩过的）

1. **`--limit` 不是 `--max`**。`--max` 是旧名仍兼容，但新写一律用 `--limit`（条数）；`--depth` 是树深。二者别混。
2. **采样会静默失败**。高频压测下 `hidumper` 超时，输出会写「采样 N 个（失败 M 次）」。看到失败率过半，**别拿剩下的点下结论**，把 `--sample-every` 放大重跑。
3. **走势判定看形状，不看首末差**。`soak`/`watch` 给的是 `锯齿/持续上升/基线抬升/持续下降/震荡`：
   - **锯齿** = 段内涨、段间回落 → 回收机制在工作，**不是泄漏**
   - 首末差 +44MB 但谷值不单调、上升步占比 54% → 那是**震荡**，不是增长
4. **`diff` 的 `--allow-ratio` 是占比**（默认 0.01 = 1%），不是像素数。
5. **`crash --name` 要传文件名**，不是崩溃标题；先 `crash --list` 拿名字。
6. **monkey 批量模式**（`--batch >1`）一批共用同一份 layout，批内界面跳转后后续事件会打在旧坐标上——压测可接受，精确回放别用。
7. **`--record` 每步截图约 500ms**，只适合几十步的脚本，别对着长脚本开。
8. **`--grep` 是子串匹配，不是正则**。写 `--grep "a\|b"` 会按字面量找、返回「匹配 0」（工具会提示）。
9. **多会话用逗号**：`--session a,b,c`。重复写 `--session a --session b` 只会用最后一个（工具会警告）。
10. **`--stay-in-app` 默认开启**：monkey 跑出目标应用会被 `aa start` 拉回，输出里有「越界拉回 N 次」。
    要看离开应用后的真实行为，加 `--no-stay-in-app`。

## 中断后的补救

压测/脚本被中断、只留下 layout 时：

```bash
ohd salvage <会话目录>     # 不给目录则取最新
```

能拿到：时长、界面数量与多样性、可点节点波动、每帧完整节点树。
**拿不到**：步数、事件序列、PSS/DMA —— salvage 的输出里会明确列出，
它**不会**给内存趋势，所以**禁用 salvage 结果谈泄漏**。

如果要完整数据，从一开始就用 `--sample-every` 或压测（增量落盘，中断也能保住）。

## 失败排查顺序

1. `doctor` —— hdc 找到没、设备在线没（看 `来源`：`sdk:...` / `PATH` / `argument/env`）
2. `selftest` —— 离线自检 7 项（解析器/会话/argv/编码/图像引擎），**不碰设备**
3. 报「未知能力」→ 跑 `ohd` 看第一屏能力列表（21 个），别猜命令名
4. 参数被拒 → 报错里会打印 `该能力支持：...`，照着改

## 平台差异

Windows/Linux 上的编码、路径、`.bat` 转发差异与已知限制，见 `ohd/CROSS-PLATFORM.md`（§10 包形态、§11 长跑脱管）。
Windows 上表格花屏用 `--ascii`。

**分发**：`dist/package.sh` 用 jlink 打出自带运行时的 zip（约 27MB，`runtime/` 40MB），同事解压即用、**不必装 JDK**；
jlink 不能交叉生成，每个平台各自打自己的包。
