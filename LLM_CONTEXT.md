# ohd — LLM 上下文入口（新会话先读这一份）

> 给「下一次接手这个项目的大模型」首读用。目标：**读完本文件即可动手，不用通读 DESIGN.md**。
> 需要细节时按 §8 的索引定点深挖，不要整篇加载 `DESIGN.md`（230KB ≈ 70k tokens）。

- 最后更新：2026-10-19（§75 三条与帮助文案已落地，见 DESIGN §77）
- 仓库：`/Users/xuxingwang/KMP/ohd`（非 git 仓库，本地目录）
- 状态：M1/M2/M3 与 §75 迭代已落地。还没做的只剩 Windows 真机回填（需要一台 Windows）

## 1. 这是什么

把「操作 OHOS 真机 / 取证 / 性能观测」收敛成**一个跨平台 CLI**（Kotlin/JVM）：
默认输出给人看的紧凑表格，`--json` 给 agent 消费；每次操作落盘成带 `manifest.json` 的会话，
可进一步打成 `ohd-case/1` 证据包，供「结论必须可复核」的场景使用。

核心立场：**工具只给事实，不下结论**。崩溃是否真、内存是否泄、符号对不对，都要用证据链说话。

## 2. 30 秒上手

```bash
cd /Users/xuxingwang/KMP/ohd
./dist/build.sh                 # 先构建（工具自己不构建）
./dist/ohd selftest             # 离线自检，不碰设备
./dist/ohd doctor               # hdc/设备/uitest/faultlog/llvm/磁盘
./dist/ohd tree --grep 设置 --clickable
./dist/ohd monkey --help        # 每个能力末尾都附一条真实组合示例（example 属性）
./dist/ohd --help               # 能力清单
```

产物目录：`<cwd>/ohd-evidence/device/<时间戳>-<能力>/`，每次会话一个，内含 `manifest.json`。证据包在 `ohd-evidence/cases/`。

## 3. 目录与关键文件

```
ohd/
├── ohd-core/   纯逻辑：Hdc / Device / Session / Output / Caps / Parsers
│   └── src/main/kotlin/ohd/core/caps/     # 24 个能力，一个文件一个
├── ohd-image/  图像对比与标注（ImageIO，可换 Skiko）
├── ohd-cli/    Main.kt：能力注册表 + 参数解析 + 退出码
├── dist/       启动/构建/打包/冒烟脚本（sh + bat，两套同构）
├── DESIGN.md   设计 + 逐日排查日志（§0–§75，按节索引见 §8）
├── CROSS-PLATFORM.md  Windows/Linux 验收清单
└── README.md   用户向说明
```

改能力 = 改 `caps/XxxCap.kt` + 在 `Main.kt` 注册表加一行 + 更新帮助文案（帮助文案在 `Main.kt:247` 起）。

## 4. 能力全景（26 个）

| 组 | 能力 |
|---|---|
| 配置 | `config`（持久化 OHD_HDC / JAVA_HOME / OHD_PYTHON 等；`~/.ohd/config.json`，`OHD_CONFIG` 可改路径） |
| 环境 | `doctor` `selftest` `env-dump` |
| 观测 | `tree` `shot` `mem` `cpu` `log` `watch` `dmabuf` |
| 动作 | `tap` `input` `replay` `record` |
| 对比/崩溃 | `diff` `crash` `sym` |
| 验证收益 | `install` `ab`（装包/AB 对照，看改动是否真有效） |
| 扩展 | `ext`（脚本挂新命令，见 README「扩展」；`ohd uitest ...` 直接跑 `.ohd/extensions/uitest.sh`） |
| 旁路观测 | `monitor`（抓 hilog + 采样 PSS/CPU/DMA + 崩溃检测，**自身不注入任何事件**，动作由外部工具给） |
| 交付 | `bundle`（默认 `--since 2h`；`--report`/`--zip`/`--latest-only`；缺件 exit 4） `analyze` `path` `report` `repro` `soak` `monkey` `salvage` `summary` |

扩展机制：`ohd <命令>` 未命中内置时去 `$OHD_EXT` / `.ohd/extensions` / `~/.ohd/extensions` / `<根>/extensions` 找同名 `.sh/.py/.java/.bat`，参数原样透传，注入 `OHD_SELF/OHD_HDC/OHD_DEVICE/OHD_SESSION_ROOT`；内置优先、扩展坏了不影响 ohd 启动。详见 DESIGN §82。

`monkey` 是压测主力：随机事件注入 + 崩溃现场 + 可回放 `.ohd` 脚本；`--keep-going` 支持崩溃后重启继续。

**日志默认全程抓**（`--no-hilog` 关，旧行为只在崩溃时留 tail 2000）：`--tag` 设备端过滤、`--match`/`--exclude` 宿主端正则、默认 compat 格式（只留 `日期 时间 消息`，`--full` 还原）、`--max-size 0` 不限。`ohd log` 与 `monkey` 共用 `HilogStream`，参数语义一致。每个能力的 `--help` 里都带一条真实组合示例（`example` 属性）。

## 5. 不可违背的约定

1. **禁主动编译**：只在用户明确要求时跑 `dist/build.sh`、`package.sh` 等。
2. **改完用轻量手段验**：`grep` / `read` / 真机 `./dist/ohd <cap>`，不要为验证而构建。
3. **输出契约**：表格给人、`--json` 给 agent；两种格式字段必须同源，别各自拼。
4. **退出码**：0 成功 / 2 用法 / 3 无设备 / 4 断言失败 / 5 能力缺失 / 6 超时 / 7 Build-ID 或产物不匹配。
5. **证据自洽**：会话目录必须有 `manifest.json`；结论类能力（`report`/`analyze`）不得越过证据下判断。交研发一句：`ohd bundle --redact --report --zip`（默认近 2h；旧「只打最新」加 `--latest-only`）。
6. **平台隔离**：`ohd-core` 是纯 JVM 逻辑，不许塞平台相关代码；平台差异只能出现在 `dist/` 与文档里。

## 6. 已知坑（都踩过，别再踩）

| 坑 | 结论 |
|---|---|
| `--duration 10h` 只跑了 17 分钟 | **已修**（§77）：默认 `--max-steps 0` 不限；上限会先于时长打满时 `MAX_STEPS_CAP`，真被截断时 `MAX_STEPS_HIT` |
| 长跑被父会话带走 | 进程必须脱离会话：Linux `setsid`，**macOS 没有 setsid**，用 python `start_new_session=True` |
| 采样「27 成功 / 1521 失败」 | **已修**（§77）：失败率 ≥ 50% 且尝试 ≥ 5 时 `SAMPLE_UNRELIABLE`，并按超时 / pss不可用 / hidumper失败 归类；PSS 起止对比标为不可信 |
| log 会话写出 13GB hilog | **已修**（§77）：`log start` 默认 `--max-size 2G`（`0` 不限），看守进程到顶后回收整棵子进程；`log stop` 同样回收。`monkey` 的 `--max-size` 默认 `0`（不限） |
| 长跑结束后无 hilog 可回溯 | 旧行为只在崩溃时留 tail 2000，10h 跑完 `verdict: OK` 就一行日志都没有。现 `monkey` **默认全程抓**，全量约 3.5–10 GB/10h；怕爆盘用 `--max-size` 或 `--tag` |
| `--tag` 写了没效果 | `-T` 只认 hilog 的 **tag** 段（如 `Konan_main`）；`ArkUIViewController` 只是消息内容，当 tag 用会得到 0 字节。且 `-T` 只接受 `-T a,b` 单参数形式，重复 `-T` 会被设备端拒 |
| log 行里有非 UTF-8 字节 | 直接喂 `path` 会 `MalformedInputException`；`HilogStream` 已用 REPLACE 解码兜住 |
| `smoke.sh` 里 `$BIN（` | 全角括号被吃了变量名，用 `${BIN}` |
| stripped so 做 addr2line | 给不出行号；要用 `entry/libs/` 里的原件 |
| buildId 不匹配 | 拒绝出符号，不要「宁缺毋滥」地塞近似结果 |
| `pid` 不刷新 / 采样失败写 0 | 曾造成静默错误，任何采样都要确认 pid 新鲜度 |
| 跑出应用 | 工具曾误触系统手势，现在安全区从 layout 动态推导，仍会偶发越界（自动拉回） |
| Windows 表格花屏 | `--ascii` |
| 帮助文案落后 | **已修**（§77）：去掉「规划中」，补上 `install`/`ab`/`summary`/`salvage`。源码已改，`./dist/ohd --help` 要重新 `dist/build.sh` 才看得到；现每个能力另附一条复杂示例（`example` 属性，`ohd <cap> --help` 末尾显示） |

## 7. 待迭代 backlog

§75 的步数上限、采样告警、日志大小/子进程回收，以及帮助文案，已在 DESIGN §77 落地。

还没做的：

1. Windows 三项待真机回填：`package.bat`、新版 `ohd.bat`、`Start-Process` 脱管。

## 8. DESIGN.md 定点索引（不要整篇读）

| 想知道什么 | 读哪节 |
|---|---|
| 为什么做、目标/非目标 | §0–§2 |
| 架构、能力清单、数据契约 | §4–§6 |
| 证据包 `ohd-case/1` 规范 | §16、§22、§37 |
| 输出/参数/时间格式一致性 | §28–§30 |
| monkey 策略与效率 | §31、§33、§34、§45–§47 |
| PSS 判定（锯齿 ≠ 泄漏） | §32、§69 |
| 符号化边界（stripped / buildId） | §49、§52、§54、§55、§64、§65 |
| install / ab 验收益 | §51、§58 |
| `--keep-going` 与长跑 | §67、§68、§70–§72 |
| jlink 分发与 Windows 脚本 | §66、§73 |
| 文档归位 | §74 |
| 收尾与待迭代 | §75 |
| §75 三条与帮助文案的落地 | §77 |
| path 原型（tools/，未进 CLI） | §79 |
| bundle 交研发：默认 since 2h / report / zip / exit 4 | §80 |
| `ohd path` hilog→.ohd | §81 |

读法：先 `grep -n "^## [67][0-9]" DESIGN.md` 定位，再 `sed -n '起,止p'` 取该节，禁止整篇打印。

## 9. 改一项改动的标准动作

```bash
# 1. 改 caps/XxxCap.kt（或 Main.kt 注册）
# 2. 静态检查（不构建）
grep -n "xxx" ohd-core/src/main/kotlin/ohd/core/caps/XxxCap.kt
# 3. 用户明确要求构建后，再：
./dist/build.sh && ./dist/ohd selftest && ./dist/ohd doctor
# 4. 真机验证（设备 SGT-AL50 / 63R0226113004249 / API 24）
./dist/ohd <cap> --json | head -40
# 5. 冒烟
./dist/smoke.sh
```

## 10. 下次会话第一句可以这样写

> 读 `ohd/LLM_CONTEXT.md`。§75 的迭代已经落地（DESIGN §77），还没做的是第 7 节的 Windows 真机回填。
> 修改前先看对应 DESIGN 节，禁止主动编译，改完只做静态校验 + 真机命令验证。

## 11. `path` 与 tools/ 原型

- **CLI**：`ohd path <hilog.txt> [--algo stack|first] [--map …] [--coords …] [--out …]`（DESIGN §81；算法在 `parsers/PathFromLog.kt`）。
- **原型保留**：`tools/path_from_log.py` 与 `tools/samples/`（§79）仍可对拍；语义以 Kotlin 为准。
- 局限不变：只有页面级事件，`--coords` 须手工补；依赖 Compose/ArkUI 日志格式。
