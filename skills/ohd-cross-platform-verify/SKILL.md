---
name: ohd-cross-platform-verify
description: 使用 ohd 在 Android（adb）与 iOS（simctl / devicectl / idb）设备上执行 UI 观测、交互、内存/CPU 采样与崩溃取证。WHEN 需要在 Android 真机/模拟器或 iOS 模拟器/iPhone 真机上用 ohd 驱动验证，WHEN 需要排查跨平台能力边界（如 iOS 真机无 shell、idb 触摸注入前置）。
---

# ohd 跨平台真机验证（Android & iOS）

当目标设备不是 OHOS 而是 **Android** 或 **iOS** 时，使用 `ohd --platform <android|ios|ios-device>` 或让 `ohd` 自动探测设备序列号。

## 1. 平台前置与能力速查

| 能力 | Android (`adb`) | iOS 模拟器 (`simctl` + `idb`) | iPhone 真机 (`devicectl` + `idb`) |
|---|---|---|---|
| **设备枚举 / `doctor`** | ✓ | ✓ | ✓ |
| **安装 / 卸载** | ✓ (`install`) | ✓ (`install`) | ✓（需已签名 `.ipa` + 开发者模式） |
| **UI 树 (`tree`)** | ✓ (`uiautomator dump`) | ✓ (`idb ui describe-all`) | ✓ (`idb ui describe-all`) |
| **截图 (`shot`) / 录屏 (`video`)** | ✓ | ✓ | ✗（无 shell，不支持） |
| **交互 (`tap` / `input` / `go` / `monkey`)** | ✓ (`input` 命令) | ✓（需安装 `idb`） | 依赖 `idb` |
| **内存 (`mem`)** | ✓ (`dumpsys meminfo`) | ✓（`footprint`） | ✗ |
| **CPU (`cpu`)** | ✓ (`top`) | ✗ | ✗ |
| **日志 (`log`)** | ✓ (`logcat`) | ✓（`log stream`） | ✗ |
| **崩溃 (`crash`)** | ✓ (`logcat -b crash`) | ✓（`DiagnosticReports`） | ✗ |

> [!IMPORTANT]
> `ohd` 对明确不支持的能力会直接返回 `ExitCode.UNSUPPORTED` 并给出原因提示，绝不静默降级或编造数据。

---

## 2. Android 标准验证流

```bash
# 1. 环境自检与指定设备
ohd doctor --platform android
ohd devices

# 2. 装包与启动
ohd install /path/to/app.apk

# 3. 认路 + UI 操作（遵循 ohd-ui-action-validity）
ohd tree --platform android --grep "登录" --clickable
ohd shot --platform android --grid 100
ohd tap --platform android 540 1200

# 4. 性能采样与崩溃抓取
ohd mem --platform android --bundle com.example.app --json
ohd monitor --platform android --bundle com.example.app --duration 5m
ohd crash --platform android --details
```

---

## 3. iOS 模拟器标准验证流（仅 macOS）

### 前置准备（若需 `tree` / `tap` / `monkey`）
```bash
brew tap facebook/fb && brew install idb-companion
pip3 install fb-idb
```

### 常用命令
```bash
# 1. 查看模拟器 UDID
ohd devices

# 2. 观测（截图、UI 树、内存 footprint、日志）
ohd shot --device <UDID> --grid 100
ohd tree --device <UDID>
ohd mem --device <UDID> --bundle com.example.app
ohd log start --device <UDID> --bundle com.example.app

# 3. 交互与压测（走 idb）
ohd tap --device <UDID> 200 400
ohd monkey --device <UDID> --bundle com.example.app --duration 5m
```

### 常见排错
- **`idb_companion` 权限不足 / `Failed to create xctest storage`**：在普通终端运行一次 `idb ui tap --udid <UDID> 100 100` 确认权限，沙箱受限环境会拦截 companion 初始化。
- **`Could not connect`**：执行 `idb list-targets` 确认目标可见，或 `idb connect <UDID>`。
