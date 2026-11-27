# Release Notes — ohd v0.2.0

> 发布日期：2026-11-27 · 相对 `v0.1.0`（`3a3b6dc`）

---

## 中文

### 概要

本次发布完成 **Kotlin Multiplatform 迁移**：同一份源码同时编译到 JVM 与原生（macOS / Linux），
`ohd` 因此有了两种运行时产物。`v0.2.0` 起，**每个平台同时提供 JVM 包与原生包**，
按需二选一。

| | 启动耗时 | 分发包大小 |
|---|---|---|
| **原生**（新） | **约 6 ms** | 1.7 MB（压缩） |
| JVM | 约 51 ms（含 CDS） | 约 40 MB（自带运行时） |

> 测量条件：macOS arm64，各 3 轮 × 40 次交替执行取均值（native 5.6–6.0 ms，
> JVM 50.8–51.2 ms），**约 9 倍差异**。

`ohd` 是 agent 每步都调用的工具链，启动税会线性叠加——这是本次迁移的主要动机。
**计算性能两者持平**：图像解码、inflate、摘要、差分等基准在两端基本一致（部分 K/N 更快）。

### 发布产物

按平台 × 运行时，共 5 个包：

| 平台 | JVM 包（自带运行时） | 原生包（零运行时） |
|---|---|---|
| macOS arm64 | `ohd-0.2.0-darwin-arm64.zip` | `ohd-0.2.0-darwin-arm64-native.zip` |
| Linux x86-64 | `ohd-0.2.0-linux-x86_64.zip` | `ohd-0.2.0-linux-x86_64-native.zip` |
| Windows amd64 | `ohd-0.2.0-windows-amd64.zip` | —（原生入口只承诺 posix） |

解压即用：JVM 包内含 jlink 运行时，原生包是单个可执行文件。

### 新增

**双运行时**
- `dist/ohd` 优先使用原生可执行，找不到自动回退 JVM
- `OHD_FORCE_JVM=1` 强制走 JVM（对比性能 / 排查原生特有问题）
- `OHD_NATIVE_TARGET=linuxX64` 指定原生 target

**分发包**
- `dist/package-native.sh` — 原生包（单文件，零运行时依赖）
- `dist/install-native.sh` — 装成 `<prefix>/bin/ohd` 软链

> ⚠️ `dist/ohd` 是 bash 脚本，**bash 自身启动就要约 6 ms**，与二进制本身相当。
> 要拿到完整的 6 ms 启动，必须用 `install-native.sh` 装软链（实测 5.8 ms vs 直调脚本 12.5 ms）。

**`monkey --avoid-text`**
按 text/description 子串跳过有风险的可点击入口，长跑压测用。不改动 WorldMap 标签。

**中英文双语 README**
`README.md` / `README.en.md`，两版互链。

### 修复

| 问题 | 影响 |
|---|---|
| `shot --out x.jpg` **报告成功但没落盘** | `ImageIO.write` 对 RGBA 写 JPEG 返回 `false` 而不抛异常。原生版已改为明确报错 |
| 非当前会话的文件 mtime 读成 0 | K/N 侧 `lastModifiedMillis` 对不存在的文件返回 `0`，会被排序当成 1970 年、**静默选错会话**。已统一为抛异常 |
| 验标通过后地图墓碑不清 | `confirmPage(verified = true)` 从未被调用，「验标成功→清墓碑」是死代码。修复后坐标可用率可从 269/899 继续爬升 |
| 时区/时间格式在非东八区机器上漂移 | `SummaryCap` 曾用本机偏移解析设备时间戳，已钉死 `+0800` |

### 优化

- **世界地图查询**：`findMapTap` 1.56 ms → 0.13~0.65 ms（中文/专有词 3~12 倍）。
  瓶颈是逐项 `lowercase()` 而非扫表，故索引键取 raw label，归一推迟到查询时
- **`go` 提速**：map-first 默认开启、settle 去重、pid 缓存、前台 5s 节流
- **`CaseFacts`**：原本 `ImageIO.read` 全量解码只为拿宽高，改为 `imageSizeOf` 读文件头
- **`ShotCap` 亮度检测**：从 AWT 改纯 Kotlin `PngDecode`

### 已知限制

**原生版仅三项降级**，且都**明确报错而非产出坏图**：

| 能力 | 原生版 | JVM 版 |
|---|---|---|
| 图像标注图（`shot --grid/--boxes`） | ✗ | ✓ |
| GIF 合成（`video` / `bundle` 的 replay.gif） | ✗ | ✓ |
| 视频帧解码（`video` 内嵌帧） | ✗ | ✓ |

其余功能**完全等价**。需要这三项请用 JVM 包。

**平台验证状态**：

| 平台 | 状态 |
|---|---|
| macOS arm64 | ✅ 已实机验证（`selftest`、真机 `shot`/`tree`/`map`/`diff`） |
| Linux x86-64 | ⚠️ **编译 + 链接通过（有效 ELF），未实机运行** |
| Windows | JVM 包（原生入口只承诺 posix） |

**长跑能力未在原生上验证**：`monkey` / `soak` / `record` 的长跑收尾（协程 + 信号处理）
尚需解锁设备实操回归。

### 升级注意

- **无破坏性 CLI 变更**：命令、参数、输出格式均未变
- 若你在脚本里硬编码了 `ohd-cli/build/install/ohd/lib/ohd-cli-0.2.0.jar`，
  注意 KMP 迁移后名为 `ohd-cli-jvm-0.2.0.jar`。`dist/ohd` 用 `-cp "$LIB/*"` 通配，不受影响
- CDS 归档需重新生成：`./dist/cds.sh`（否则 CDS 归档失效（`-Xshare:auto` 会静默降级，功能不受影响，仅失去 CDS 收益））

---

## English

### Summary

This release completes the **Kotlin Multiplatform migration**: a single codebase now
compiles to both JVM and native (macOS / Linux), giving `ohd` two runtime artifacts.
Starting with `v0.2.0`, **every platform ships both a JVM package and a native package** —
pick whichever you need.

| | Startup | Package size |
|---|---|---|
| **Native** (new) | **~6 ms** | 1.7 MB (zipped) |
| JVM | ~51 ms (with CDS) | ~40 MB (bundled runtime) |

> Measured on macOS arm64: 3 rounds x 40 runs each, alternating (native 5.6-6.0 ms,
> JVM 50.8-51.2 ms) — roughly **9x**.

`ohd` is invoked by agents on every step, so startup cost compounds linearly — that is the
main motivation for this work. **Compute performance is on par**: image decoding, inflate,
digests and diffing benchmark essentially equal on both runtimes (some paths are faster on K/N).

### Release artifacts

5 packages across platform x runtime:

| Platform | JVM package (bundled runtime) | Native package (zero deps) |
|---|---|---|
| macOS arm64 | `ohd-0.2.0-darwin-arm64.zip` | `ohd-0.2.0-darwin-arm64-native.zip` |
| Linux x86-64 | `ohd-0.2.0-linux-x86_64.zip` | `ohd-0.2.0-linux-x86_64-native.zip` |
| Windows amd64 | `ohd-0.2.0-windows-amd64.zip` | — (native entry points are posix-only) |

Unzip and run: the JVM package bundles a jlink runtime, the native package is a single
executable.

### Added

**Dual runtime**
- `dist/ohd` prefers the native executable and falls back to JVM automatically
- `OHD_FORCE_JVM=1` forces the JVM build (for benchmarking or isolating native-only issues)
- `OHD_NATIVE_TARGET=linuxX64` overrides the native target

**Packaging**
- `dist/package-native.sh` — native package (single file, zero runtime dependencies)
- `dist/install-native.sh` — installs a `<prefix>/bin/ohd` symlink

> ⚠️ `dist/ohd` is a bash script, and **bash alone costs ~6 ms to start** — as much as the
> binary itself. For the full 6 ms, install the symlink via `install-native.sh`
> (measured 5.8 ms vs 12.5 ms through the script).

**`monkey --avoid-text`**
Skips risky clickable entries by text/description substring, for long stress runs. Does not
modify WorldMap labels.

**Bilingual README**
`README.md` (Chinese) / `README.en.md` (English), cross-linked.

### Fixed

| Issue | Impact |
|---|---|
| `shot --out x.jpg` **reported success but wrote nothing** | `ImageIO.write` returns `false` (no exception) when writing an RGBA image as JPEG. Native now fails loudly |
| mtime of a missing file read as 0 | K/N `lastModifiedMillis` returned `0` for missing files, which sorts as 1970 and **silently picks the wrong session**. Unified to throw |
| Map tombstones not cleared after verification | `confirmPage(verified = true)` had no call sites — the "verified clears tombstone" branch was dead code. Fixed; coordinate usability now climbs past 269/899 |
| Timestamps drifted on non-UTC+8 machines | `SummaryCap` parsed device timestamps with the local offset; now pinned to `+0800` |

### Improved

- **World-map lookup**: `findMapTap` 1.56 ms → 0.13-0.65 ms (3-12x for Chinese / proper nouns).
  The bottleneck was per-item `lowercase()`, not table scanning, so the index key is the raw
  label and normalisation is deferred to query time
- **`go` speedups**: map-first on by default, settle dedup, pid caching, 5 s foreground throttling
- **`CaseFacts`**: fully decoded images just to read width/height; now uses `imageSizeOf` (header only)
- **`ShotCap` luminance check**: moved from AWT to pure-Kotlin `PngDecode`

### Known limitations

**Only three capabilities are degraded on native**, and each **fails loudly instead of
producing broken artifacts**:

| Capability | Native | JVM |
|---|---|---|
| Annotated screenshots (`shot --grid/--boxes`) | ✗ | ✓ |
| GIF composition (`video` / `bundle` replay.gif) | ✗ | ✓ |
| Video-frame decoding (`video` inline frames) | ✗ | ✓ |

Everything else is **fully equivalent**. Use the JVM package if you need these three.

**Platform verification status**:

| Platform | Status |
|---|---|
| macOS arm64 | ✅ Verified on device (`selftest`, real-device `shot`/`tree`/`map`/`diff`) |
| Linux x86-64 | ⚠️ **Compiles and links (valid ELF), not run on a real machine** |
| Windows | JVM package (native entry points are posix-only) |

**Long-run paths are not verified on native yet**: `monkey` / `soak` / `record` lifecycle
(coroutines + signal handling) still need an unlocked device to regress properly.

### Upgrade notes

- **No breaking CLI changes**: commands, flags and output formats are unchanged
- If you hard-coded `ohd-cli/build/install/ohd/lib/ohd-cli-0.2.0.jar`, note it is now
  `ohd-cli-jvm-0.2.0.jar`. `dist/ohd` uses the `-cp "$LIB/*"` wildcard, so it is unaffected
- Regenerate the CDS archive: `./dist/cds.sh` (**re-run it after every `dist/build.sh`**, which rewrites the jars) (otherwise the CDS archive goes stale (`-Xshare:auto`
  degrades silently — functionality is unaffected, you just lose the CDS benefit))

---

## 附：主要提交 / Main commits

| Commit | 说明 |
|---|---|
| `f751f5b` | `refactor(kmp)` — KMP 迁移，四个模块 × 三个 target |
| `46f3566` | `feat(monkey)` — `--avoid-text` |
| `333bb3b` | `fix(go)` — 验标后清墓碑 |
| `e4cb71f` | `perf(worldmap)` — label 倒排索引 |
| `1ec20e8` | `docs(readme)` — 双运行时说明 + 英文版 |

规模：200 files changed, +20571 / −2948
