---
name: ohd-a11y-uitest-activate
description: >-
  用 uitest daemon + dumpLayout 对齐 DevEco Testing，激活 OHOS/Compose 无障碍热路径再压测。
  WHEN APP_INPUT_BLOCK/onLayoutChange a11y 卡点、预取渲染+FindById、怀疑「没开读屏所以测不到」、
  monkey 要对等 DevEco 稳定性、要 --keep-a11y。
---

# OHOS：uitest 激活无障碍（对齐 DevEco Testing）

铁律：**DevEco 稳定性不是靠屏幕朗读，而是 `uitest start-daemon` + 连续 dump/Find 把系统 `accessibility` 客户端拉起来**；Compose 侧 `isOpenAccessibilitySync()` 为真后才进 `accessibilityEnabled` 热路径。

## 决策树

1. 要复现 / 回归 **a11y layout 热路径**（如预取渲染 APP_INPUT_BLOCK）？→ 本 skill。
2. 只要点几下 UI、不关心 a11y？→ `ohd-device-verify`，不必 `--keep-a11y`。
3. 长跑压测？→ `ohd monkey … --keep-a11y`（必加）；可选脱管见 `ohd-monkey-scene-capture`。
4. 短跑定点场景？→ `.agents/scripts/verify/a11y_uitest_stress.py`。

## 命令

```bash
OHD=${OHD:-/Users/xuxingwang/KMP/ohd/dist/ohd}
# monkey 对等 DevEco
$OHD monkey --bundle com.tencent.compose --duration 30m --keep-a11y --keep-going \
  --tag ComposeA11y --exclude '(?i)vsync'

# 短跑：进预取渲染 + dump/滑动压 layout
python3 .agents/scripts/verify/a11y_uitest_stress.py \
  --device 63R0226113004249 --bundle com.tencent.compose --duration 90
```

## 验收证据（缺一不可）

| 证据 | 含义 |
|------|------|
| hilog `IsOpenAccessibilitySync` / `accessibilityStateChange` | Compose 收到无障碍开 |
| hilog `ComposeA11y FindById` | Provider 在被查 |
| `pidof uitest` + `pidof accessibility` | 与 DevEco `_wait_accessibility` 同条件 |
| 压后无新 `appfreeze`（短跑）或 soak/faultlog 对拍 | 热路径未再卡死 |

## 禁令

| 借口 | 事实 |
|------|------|
| 「没开读屏所以测不到」 | DevEco freeze 日志里已有 uitest + FindById；读屏非必要条件 |
| 「ohd tree 点过一次就够」 | TTL 拉长后 dump 变稀，a11y 可能掉；要用 `--keep-a11y` 或脚本周期 dump |
| 「绕过 ohd 手写 hdc 流水线」 | 违反 `ohd-device-verify`；缺能力先扩 ohd |

深读：`references/deveco-parity.md`。配套：`cmp-core-accessibility-ohos`、`ohd-device-verify`。
