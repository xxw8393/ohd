---
name: ohd-monkey-scene-capture
description: >-
  ohd 长跑连招：monkey（--keep-going）并行 ohd 插件 ohos_automation_scene_capture，
  macOS 必须 start_new_session 脱管，结束后 triage/ANALYSIS。WHEN 要 1h/2h/过夜压测+内存采样、
  「同步抓 scene_capture」、怀疑 agent 会话杀了长跑、崩溃后 monkey 是否该继续。
---

# ohd monkey × scene_capture 长跑连招

铁律：**应用崩溃 ≠ monkey 中断**（必加 `--keep-going`）；**agent/IDE 会话退出会杀挂在其下的进程树**（必脱管）。

## 决策树
1. 要压测+内存时序？→ 本 skill；只要点几下/短 soak → `ohd-device-verify`。
2. `ohos_automation_scene_capture` → **ohd 扩展插件**（`ohd ohos_automation_scene_capture`），不是裸 `python3 ~/…py`。
3. 时长 ≥1h？→ `scripts/run_detached.py`（勿 `nohup &` 挂在 Cursor shell）。
4. 结束后？→ 同目录 `ANALYSIS.md` / `ohd triage <capture> --reverify`；形状判读见 `cmp-core-ohos-automation-report`。

## 一键（脱管）
```bash
OHD=${OHD:-/Users/xuxingwang/KMP/ohd/dist/ohd}
S=.agents/skills/ohd-monkey-scene-capture/scripts
python3 $S/run_detached.py \
  --ohd "$OHD" --bundle com.tencent.compose --device 63R0226113004249 \
  --duration 2h --run-root /tmp/ohd_monkey2h
# 打印 RUN=… orch_pid=…；ppid 应为 1
# 收网：python3 $S/analyze_run.py --run "$RUN"
```

## 硬参数（别省）
| 项 | 值 | 为什么 |
|---|---|---|
| monkey | `--keep-going` | 崩后 force-stop+start，记 `crashCount`，跑满 duration |
| monkey 收尾 | 默认 `--home-settle 30s` | 回首页（Back）后静置，便于 capture 采稳态；`--no-home-settle` 关闭 |
| monkey `--out` | **文件** `…/monkey.ohd` | 传目录 → `FileSystemException: Is a directory`，monkey **进程**退出（不是 app 崩） |
| 插件 | `ohd --ext-timeout 0 --ext-mem 0 ohos_automation_scene_capture` | 默认 ext-mem=2g / 超时会杀长采 |
| 脱管 | `Popen(…, start_new_session=True)` | macOS 无 setsid；校验 `ps -o pid,ppid` 且 ppid=1 |
| 编排 | `set +e` 接 monkey rc | `set -e` + monkey 非 0 → orch 未写 COMPLETE 且可能带走 capture |

## 崩溃 vs 编排被杀（先分清）
| 现象 | 含义 | 动作 |
|---|---|---|
| monkey 日志 `✗ 崩溃 #N` 后仍有 `[step] tap…` | **正常**：keep-going 在工作 | 收网看 crashCount + sym |
| monkey 日志 `FileSystemException` / 停在 HILOG_ON | **monkey 自己挂了** | 查 `--out` 等参数 |
| orch/capture 消失且无 COMPLETE，ppid 曾≠1 | **会话杀树** | 用 `run_detached.py` 重跑 |
| app `cppcrash` 但 monkey 已退出 | 多半没加 `--keep-going` | 重跑加旗标 |

## 验证
- 启动 30s 内：`ps -o pid,ppid -p $orch` → ppid=1；`monkey.log` 出现 `[N] tap|swipe`；capture 有 `memory_samples.csv` 行增长。
- 人为/自然崩溃后：同 log 出现 `崩溃 #N` **且后续 step 继续**。
- 结束：`orchestrator.log` 含 `COMPLETE`；`analyze_run.py` 写出 `ANALYSIS.md`。

深读：`references/pitfalls.md`。配套：`ohd-device-verify`（脱管原文）、`cmp-core-ohos-automation-report`（REPORT 形状）。
