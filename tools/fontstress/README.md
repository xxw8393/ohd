# fontstress — 字体栈短跑压测（几十秒一轮）

日常替代 8h 长跑：冷启 demo → 多实例页（Compose 容器 A/B）→ 容器 A 内反复进出
`simple-text`（中文多字号富文本页），每轮 6 次进出 ≈ 45-55s，每轮前后各取一次 `ohd mem`。
判定看 jemalloc(pss+swap) 的逐轮增量与最小二乘斜率，输出 `REPORT.md`。

```bash
./font_stress_fast.sh --device <serial> [--rounds 5] [--cycles 6] \
    [--bundle com.tencent.compose] [--out ~/KMP/ohd-evidence/<dir>]
```

## 为什么要标坐标

demo 的 Compose 文本是自绘、不进无障碍树（`ohd tree` 会提示 `NO_TEXT_NODE`），
所以场景用固定坐标驱动，脚本顶部集中了 4 个点（`多实例测试` 按钮 / 容器 A 的
`simple-text` 项 / 容器 A 返回箭头）。换机型或换 demo 布局只需改这 4 个数：

| 变量 | 含义 | 1280x2832 上的值 |
|---|---|---|
| `BTN_X/BTN_Y` | switch 页「多实例测试」 | 988, 2571 |
| `ITEM_X/ITEM_Y` | 容器 A 内 `simple-text` | 1041, 1403 |
| `BACK_X/BACK_Y` | 容器 A 返回箭头 | 119, 531 |

## 判定口径

- 斜率 > +256 KiB/轮且逐轮单调增、总量 > 2 MiB → **FAIL**
- 斜率 > +96 KiB/轮 或 平均增量 > 192 KiB → **WARN**
- 其余 → **PASS**

单次短跑只反映本轮窗口；「平」不等于修好，需与 baseline（关 purge / 旧 skiko 包）对拍，
口径见 skill `ohos-font-stack-leak-ab-verify`。

## 实测（2026-11-12，SGT-AL50 / api 24，purge-ON 包 `1.12.0-xxw-001`）

见 `~/KMP/ohd-evidence/fontstress-fast-*/REPORT.md`；5 轮 × 6 次进出，单轮 45-55s。

## 实测数据与边界（2026-11-12 下午，purge-ON 包，1080P 竖屏）

| 运行 | 轮数 × 进出 | jemalloc(pss+swap) | 判定 |
|---|---|---|---|
| churn #1 | 5 × 6 | 30953 → 41960 KiB（+11.0 MiB） | FAIL |
| churn #2 | 5 × 6 | 34416 → 61245 KiB（+26.8 MiB） | FAIL |
| idle 对照 | 5 × 0 | 49652 → 49804 KiB（±0.3 MiB） | PASS |

两次 churn 都可复现单调增长（每轮 2.2–5.4 MiB，折合每次进出 0.4–0.9 MiB），
而 `.ttf` 映射基本不动（92–177 KiB）——**这个量级不是 FaceRec/字体栈**（FaceRec 是 KB 级），
主因是「整页 Compose 创建/销毁」的普通堆增长。

所以本脚本目前只能当**场景级体检**（页面 churn 是否异常膨胀），要当字体栈专项哨兵还需补一步：
同一场景对拍 purge-ON vs purge-OFF（或 skiko 旧包），差值才归因到字体栈；
或把 `FontConfigLive`/FaceRec 计数经 hilog 打出来做逐轮对账（DIAG 埋点在
`FontConfig_ohos.cpp:47 logLiveFontConfigs`，`dc033b00cda` 已把它 gate 在 SK_DEBUG 下）。
