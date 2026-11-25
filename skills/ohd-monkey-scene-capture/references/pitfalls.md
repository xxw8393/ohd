# 连招踩坑（2026-11-05 实测）

## 1. 应用 cppcrash ≠ monkey 退出

`--keep-going` 时 MonkeyCap 检出新 faultlog → 记 `崩溃 #N` → force-stop + start → **继续注入**。

反例（本轮）：
```
[105] tap …
✗ 崩溃 #1：cppcrash …-20261105111750
[115] tap …          # 继续
…
[436] tap …
```

无 `--keep-going`：首次崩溃 `break`，duration 未满就收网。

## 2. 真正「打断 monkey」的常见原因

| 原因 | 日志特征 |
|---|---|
| `--out` 传**目录** | `FileSystemException: … Is a directory`，停在 HILOG_ON 后无 step |
| Cursor/agent shell 未脱管 | orch/capture/monkey 同批消失，`orchestrator.log` 无 `COMPLETE`；`ppid≠1` |
| 编排 `set -e` + monkey 非 0 | orch 未写 `monkey exited` / `COMPLETE`，background capture 被带走 |
| `--ext-timeout` / `--ext-mem` 默认 | 插件被 ohd 扩展沙箱杀掉（长采用 `0`） |

macOS 脱管：**不要**依赖 `nohup &` 挂在 agent 工具 shell；用
`subprocess.Popen(..., start_new_session=True)`（见 `run_detached.py`）。

## 3. 本轮 SIGSEGV（应用侧，monkey 已继续）— ICU `_skiko` 未解析

多条 cppcrash（`…111550` / `#1…#4`）**同一 PC / 同一栈**，Build-ID 匹配：

| 项 | 值 |
|---|---|
| 信号 | `SIGSEGV(SEGV_ACCERR)@0x4210120`；`#00` Unknown `pc 0x4150120` |
| 触发 | monkey tap → TextField `requestFocus` / `startInput` |
| Kotlin | `CharDirection.of` → `strongDirectionType` → `firstStrongDirection` → `contentBased` → `SkiaParagraph` → `TextFieldDelegate` |

**根因（链接/ABI，不是随机野指针）：**

1. `Unicode.cc`（`USE_OHRENDER`）调 `u_charDirection`；OHOS 头/重命名后实际 PLT 目标是 **`u_charDirection_skiko`**。
2. `libkn.so`：`org_jetbrains_skia_icu_Unicode__1nCharDirection` = `b u_charDirection_skiko@plt`；动态表 **`UND u_charDirection_skiko`**（同批还有 `u_hasBinaryProperty_skiko`、`ubrk_*_skiko` 等约 20 个）。
3. SDK/系统 `libicu.so` 导出的是 **`u_charDirection`**（无 `_skiko` 后缀）；`OhosIcuFallback.cc` 只补了 `uloc_*_skiko`，**没有** `u_charDirection_skiko`。
4. libkn 内另有一份**静态** `u_charDirection`（Skia 自带 ICU 表），与 Compose `CharHelpers.nonJvm` 走的 skiko JNI 路径不是同一条 → 普通排版可能仍活，**一碰 TextField Bidi 探测就崩**。

修复（2026-11-05）：`gitcode-skiko` `OhosIcuFallback.cc` 在 `SK_BUILD_FOR_OHOS` 下导出
`u_charDirection_skiko` / `u_hasBinaryProperty_skiko`（显式调无后缀符号，避 `U_LIB_SUFFIX` 递归）；
门闩不再仅限 `USE_OHRENDER`。已覆盖 mavenLocal `0.150.1-0.1.0-fontface-01`。
下游重链 HAP 后 `libkn` 动态表应变 `T` 而非 `UND`。`ubrk_*_skiko` 等若再崩再补。

## 4. scene_capture 入口

正确：`ohd [--ext-timeout 0 --ext-mem 0] ohos_automation_scene_capture …`  
（`ohd --help` → 扩展命令；实现 `~/.ohd/extensions/ohos_automation_scene_capture.py`）

过时：直接 `python3 ~/CMP-Acknowledge/scripts/ohos_automation_scene_capture.py`（仍可能能跑，但不走 ohd 设备/会话注入）。
