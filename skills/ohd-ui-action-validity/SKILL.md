---
name: ohd-ui-action-validity
description: >-
  真机/模拟器每一次 UI 操作必须「认路→点当轮坐标→验地标」，禁止盲 tap / 傻等 /
  复用旧坐标。正确性优先，再压短路径提效。能力已固化在 ohd go / wait。
  WHEN ohd tap/swipe/Back、uitest 驱动、短跑/A/B/压测进退页、用户骂没点中、
  NO_MATCH 后继续点、sleep 等界面。
---

# ohd UI 操作有效性（点必验 · 禁傻等）

**铁律：没验到地标变化 = 操作无效；无效则立刻纠偏或 abort，禁止靠长 sleep 赌运气。**

**优先用工具，不要手搓 tree+tap+sleep：**

```bash
ohd go --grep "<点谁>" --expect "<下一屏地标>" --bundle <包名>
ohd go --grep "<点谁>" --expect "<页首元素>" --light   # 高速环：最多 2 次 dump
ohd back --expect "<列表地标>" --light                 # Back + 验标（弹栈/地图）
ohd wait --grep "<地标>" --timeout 2s                  # 替代 sleep；布局缓存优先
ohd go --grep "<列表里的项>" --scroll-find --timeout 45s
```

`go`/`wait`/`back` 会 `wakeup`；`--bundle` 校验前台。  
**世界地图**（图+Map，落盘 `~/.ohd/worldmap/<bundle>.json`）：`go`/`tree`/`back` 写入；**monkey 默认也累积**（`--no-worldmap` 关）。  
进程内另有 LayoutCache（当前树）+ ScrollAtlas；`--fresh` 清空。

失败 exit 4（`ASSERT_FAILED`）→ 纠偏一轮或 abort；**禁止**改成长 sleep 重试。
细则踩坑见 `cmp-core-ohos-short-run-verify/references/drive-hygiene.md`。

## 单步闭环

| 步骤 | 工具 | 失败 |
|------|------|------|
| 认路+点 | `ohd go --grep …` | `NO_MATCH` / 歧义 → 不注入 |
| 验标 | `--expect` / `ohd back --expect` / `ohd wait` | `EXPECT_MISS` → 本轮证据作废 |
| 高速验标 | 同上加 `--light`（≤2 dump，认页首元素即可） | 同左，仍硬失败 |
| 仅坐标 | `ohd tap x y` | 仅当轮 tree/shot 刚给出的中心 |

多命中必须 `--index N`；默认只要可点（`--any` 才点不可点节点）。

**铁律补充：** dumpLayout ≈1s；全量轮询验标会拖慢进退环。高速复现用 `--light` 只确认「页首地标已变/已出现」，但**每步必须验过**——没验标的 Alloc 曲线作废。

## 纠偏（只一轮）

| 现象 | 动作 |
|------|------|
| go/wait/back 超时 | `ohd back` 或 force-stop+冷启 → 再 go |
| 锁屏 | wakeup + 上滑 → 再 go |
| 误进相机/系统页 | force-stop；证据作废 |

## 效率（正确之后）

1. 超时默认 **2s**（`--light` 默认 **1.5s**），settle **400ms**（light **200ms**）——由 `go`/`wait`/`back` 轮询，禁手写 `sleep 5+`。
2. 同一设备 UI **串行**；mem/log 可与静置并行。
3. 脚本里逐步 `go`/`back`，失败 `set -e`，勿 `|| true` 吞掉。

## 自检

- [ ] 驱动路径用了 `go`/`back`/`wait`，无「NO_MATCH 后仍 tap」
- [ ] 无循环硬编码 `(x,y)`；Back 用 `ohd back --expect` 而非裸 input
- [ ] 验标失败未继续采 GLES/mem 当有效样本
