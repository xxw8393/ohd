# DevEco Testing ↔ ohd `--keep-a11y` 对等说明

## DevEco 侧（本机 `DevEco_Testing_for_App.app`）

- Hypium RPC 失败重连前：`_wait_accessibility` → 轮询 `pidof accessibility`（否则 `accessibility pid is not found`）。
- 稳定性：反复 `uitest start-daemon singleness`，控件查找走无障碍树。
- **不**等于打开屏幕朗读；Compose 用 `isOpenAccessibilitySync() || touchGuide || screenReader`。

## freeze 证据（`ohd-evidence/freeze-mem-1120`）

同窗出现：`uitest` 进程、`ComposeA11y FindById`、`flushPageUpdateEvent`、卡在 `onLayoutChange` a11y 热路径。

## ohd 实现

| 开关 | 行为 |
|------|------|
| `--keep-a11y` | 开跑 `DeviceOps.ensureOhosA11yClient`；禁止 layout TTL 拉伸；周期 `pingOhosA11yDump` |
| `--a11y-ping-every N` | 每 N 步 dumpLayout（默认 10） |

## 专项短跑脚本

`.agents/scripts/verify/a11y_uitest_stress.py`：进「预取渲染」→ 连续 dump+swipe → 对拍 faultlog。
