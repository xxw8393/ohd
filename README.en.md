# ohd — On-device Capability Toolbox for Mobile

[中文](README.md) | English

Collapses "drive a real device / collect evidence / observe performance" into one cross-platform CLI:
compact human-readable output by default, `--json` for agent consumption, artifacts archived with a manifest.

Platforms: **OHOS** (hdc) / **Android** (adb) / **iOS** (simctl for simulators, devicectl for devices,
idb for touch injection). Use `--platform` to force one; auto-detected by default.

**Dual runtime**: built with Kotlin Multiplatform — one codebase, two artifacts: a native executable
(~6 ms startup) and a JVM build (~51 ms with CDS). `dist/ohd` prefers native and falls back to JVM.
The native build degrades only three image capabilities (see "Choosing a runtime").

Design docs: [`DESIGN.md`](DESIGN.md) (capability list, evidence-pack spec, cross-platform details, milestones).
Condensed context for AI handoff: [`LLM_CONTEXT.md`](LLM_CONTEXT.md) (read this first in a new session,
then consult DESIGN as needed).

## Prerequisites

| Dependency | Notes |
|---|---|
| JDK 17+ | Confirmed present on the test/dev machines |
| Xcode 15+ (optional) | iOS: `simctl` (simulator), `devicectl` (device); macOS only |
| idb (optional) | iOS touch injection and UI hierarchy: `brew tap facebook/fb && brew install idb-companion` + `pip3 install fb-idb` |
| hdc | Ships with the OpenHarmony SDK / DevEco Studio; or pass `--hdc` / set the `HDC` env var |

## Build (explicit — the tool never builds itself)

```bash
./build.sh                 # repo-root entry: build (same as ./dist/build.sh)
./build.sh install         # build + install into PATH, then `ohd` works from any directory
./build.sh package         # build a self-contained distribution (bundled trimmed JRE, unzip and run)
./build.sh smoke           # smoke test (requires a device)
./build.sh all             # build + install + package
./build.sh uninstall       # remove the installed launcher

./dist/build.sh            # build only (macOS / Linux)
dist\build.bat             # build only (Windows)
```

`install` defaults to `~/.local/bin/ohd`; use `--prefix <dir>` to change it, `--no-build` to reuse existing output.
It installs a wrapper script rather than a symlink — `dist/ohd` locates the jar relative to its own directory,
and a symlink would resolve to the wrong level.

Output: `ohd-cli/build/install/ohd/bin/ohd`. If a Gradle distribution already exists under
`~/.gradle/wrapper/dists`, `build.sh` reuses it instead of downloading another one.

### Native executable (optional, faster)

```bash
./dist/package-native.sh          # build the native package (single file, zero runtime deps, ~1.7 MB zipped)
./dist/install-native.sh          # install as a <prefix>/bin/ohd symlink for full startup gain
```

Note that `dist/ohd` is a bash script, and **bash alone costs ~6 ms to start** — as much as the whole binary.
To get the full benefit, install the symlink via `install-native.sh` (measured: 5.8 ms vs 12.5 ms through the script).

### Choosing a runtime

Two environment variables:

| Variable | Effect |
|---|---|
| `OHD_FORCE_JVM=1` | Force the JVM build (for benchmarking, or isolating native-only issues) |
| `OHD_NATIVE_TARGET=linuxX64` | Override the native target (inferred from `uname` by default) |

The native build degrades **only three** capabilities relative to the JVM build, and each fails loudly
instead of producing a broken artifact:
annotated screenshots (`shot --grid/--boxes`), GIF composition (`video`/`bundle` replay.gif),
and video-frame decoding (`video` inline frames). Everything else is equivalent.
Use the JVM build if you need those three.

## Per-run evidence

Each operation writes to `ohd-evidence/device/<timestamp>-<capability>/` (with `manifest.json`).
Bundled evidence packs go to `ohd-evidence/cases/`. Relocate with `--session-root`.

## Capabilities

| Group | Capabilities |
|---|---|
| Environment | `doctor` (hdc/device/uitest/faultlog/llvm/disk), `selftest` (offline self-check), `env-dump` (device fingerprint) |
| Observation | `tree` (node tree + centres), `shot` (screenshot + grid/cross/node boxes), `video` (record: mp4 on Android/iOS simulators, gif composited from frames on OHOS), `mem` (Pss/GL/Graph breakdown), `cpu` (usage/processes/per-core frequency), `log` (start/stop/snap/count/save), `watch` (CSV + slope/step/plateau), `dmabuf` (DMA snapshots and deltas) |
| Actions | `tap` (tap/double-tap/long-press), `input` (text/swipe/drag/key), `replay` (replay .ohd scripts), `record` (record to `ohd-evidence/record/record-<device>-<time>.ohd`, with a cold-start entry before replay) |
| Diff & crash | `diff` (screenshot diff + heat map), `crash` (fault list/parsing), `sym` (llvm-addr2line + buildId check) |
| Benefit verification | `install` (overwrite install), `ab` (A/B comparison) |
| Extensions | `ext` (list/manage script extensions; `ohd <cmd>` resolves `.ohd/extensions/<cmd>.{sh,py,java,bat}`, see below) |
| Delivery | `bundle` (ohd-case/1; last ~2h by default; `--report`/`--zip`/`--latest-only`; exit 4 on missing pieces), `analyze`, `triage` (automation REPORT -> investigation tracks; `--reverify` runs dmabuf/mem), `path` (hilog -> .ohd), `report`, `repro`, `soak`, `monkey`, `monitor`, `salvage`, `summary` |

All parsers are derived from real device captures (fixtures live in
`ohd-core/src/jvmTest/resources/fixtures/`), not from documented formats.

### Platform coverage

| Capability | OHOS | Android | iOS simulator | iPhone device |
|---|---|---|---|---|
| Device enumeration / `doctor` | yes | yes | yes | yes |
| Install / uninstall | yes | yes | yes | yes (signed build + developer mode required) |
| Launch / terminate / file copy | yes | yes | — | yes |
| Screenshot | yes | yes | yes | no |
| Logs | yes | yes | yes | no |
| Memory | yes | yes | yes (footprint) | no |
| CPU | yes | yes | no | no |
| Crash | yes | yes | yes | no (no samples in the directory) |
| **Touch injection / monkey / replay** | yes | yes | **yes (idb)** | code path works, not verified on a device |
| UI tree | yes | yes | data source ready (idb `describe-all`), `tree` not exposed yet | same |

Dimensions that can't be obtained are skipped or explicitly refused — never stubbed with fabricated data.
For iOS internals see `DESIGN.md` sections 94-97.

## Common commands

### Install & stress

```bash
ohd install /path/app-signed.hap                       # overwrite install; --keep-data preserves app data
ohd monkey --bundle com.tencent.compose --duration 10h --keep-going \
  --interval 0 --batch 10 --sample-every 20 --layout-ttl 20 \
  --avoid-text OHOS运行时开关 \
  --tag Konan_main --exclude '(?i)vsync'

# OHOS keeps a11y alive by default (uitest daemon + dump ping, matching DevEco)
ohd monkey --bundle com.tencent.compose --duration 2h --keep-going \
  --a11y-ping-every 10 --tag ComposeA11y
# Turn it off only for noise comparison: --no-keep-a11y
```

| Option | Meaning |
|---|---|
| (default) | **Capture hilog for the whole run**, write `00x-hilog.txt` and register it in the manifest; `--no-hilog` disables (keeps only the last 2000 lines on crash) |
| `--tag Konan_main` | Device-side `hilog -T` filter; `a,b` for multiple tags; all tags if omitted |
| `--match RE` / `--exclude RE` | Host-side regex: allow-list first, then deny-list; `--exclude '(?i)vsync'` drops every line containing vsync |
| `--full` | Keep all fields; without it the code defaults to **compat** (`date time message` only) |
| `--max-size` | Log cap, `0` (unlimited) by default; LogWatchdog truncates and reclaims when exceeded |
| `--keep-going` | Keep running for the full duration after a crash (without it the run ends at the first crash; mandatory for 10h runs) |
| (default) keep-a11y | **On by default on OHOS**: keeps uitest + accessibility dump alive; `--no-keep-a11y` disables |
| `--layout-ttl 20` | Upper bound on layout capture interval, halves artifact size (~250 MB / 10h) |
| `--avoid-text a,b` | Skip clickable nodes whose text/description contains a substring (e.g. the Chinese label `OHOS运行时开关`, a real UI string on the device). Does not cover blind spots; for hard isolation build with `-Pohos.showRuntimeTuning=false` (entry still visible, not clickable, map unchanged) |

Note: 10h runs must be detached (`Popen(..., start_new_session=True)`), otherwise quitting the terminal/IDE
kills the stress run. For parallel memory sampling use the extension:
`ohd --ext-timeout 0 --ext-mem 0 ohos_automation_scene_capture --bundle ... --out-dir ...`.

### Logs

```bash
ohd log                                      # record: live in terminal + compressed to disk; Ctrl-C to stop
ohd log --tag Konan_main --exclude '(?i)vsync'   # keep only relevant lines while recording
ohd log ls                                   # list past recordings (id / time / size)
ohd log --open latest --tail 200              # replay the most recent recording
ohd log --file 003-hilog.log.gz --match 'LEAK|timeout'   # replay with a regex filter
ohd log count --file 003-hilog.log.gz --match 'FATAL'    # count matching lines
ohd log start --max-size 4G                   # record in the background (returns immediately; pair with stop)
ohd log stop
```

The default is "watch and save at once": no start/stop needed, and Ctrl-C prints the file path, size,
line count and replay command. `start/stop` are only for long background runs where the command must
return immediately while the stream keeps going.

`--tag/--match/--exclude/--full/--max-size` behave as in `monkey` (they share `HilogStream`).
Tag names come from hilog's `domain/tag` field, e.g. `Konan_main`; `ArkUIViewController` is message content, not a tag.

**Compressed by default**: logs are written as `<session>/00N-hilog.log.gz` and can be read directly with
`gunzip -c` / `zcat` — no decryption or special tooling. The implementation uses **multi-member gzip**:
a new gzip member is closed every 2 s or 8 MB and appended. So if a long run is killed by Ctrl-C, OOM or a
crash, **every completed member still decompresses** (only the last ~2 s may be lost).
Pass `--log-compress none` for plaintext (`.txt`).

In compressed mode `--max-size` applies to the **compressed** file size (log text usually compresses 6-10x,
so the effective limit is looser).

### Memory & stutter

```bash
ohd mem --bundle com.example.app          # Pss / GL / Graph breakdown
ohd watch --bundle com.example.app --duration 60s      # CSV + slope/step/plateau
ohd dmabuf --bundle com.example.app       # DMA snapshots and deltas
ohd cpu --cpufreq                         # per-core frequency (throttling/thermal)
```

`monkey` sample columns include `gl_kb / graph_kb / swap_pss_kb`; to judge a leak, watch whether the
**reclaimable floor** creeps up round after round — don't just look at peaks.
Resident = `pss - swap_pss`.

### Observation & actions

```bash
ohd tree --grep 设置 --clickable      # find clickable nodes; prints tap coordinates when unique
ohd tree --grep 登录 --json           # for agent consumption
ohd go --grep 多实例测试 --expect 返回 --bundle com.tencent.compose  # record an edge into the world map
ohd go --grep 1500 Text --expect 'Compose1500Text Item' --light     # lightweight verification (<=2 dumps)
ohd back --expect '1500 Text' --light                                # Back + verify + pop stack / map
ohd go --grep GPU Texture --scroll-find --scene home  # learn swipe counts; batch-swipe next time
ohd map show / path --to "GPU Texture" / export       # inspect / ask directions / export
ohd map go --to "GPU Texture"                         # execute via back+swipe+tap
ohd wait --grep 返回 --timeout 2s                     # prefers the current-page cache
ohd monkey --bundle ...                               # reads+writes the world map for navigation by default (--no-worldmap disables)
ohd shot --grid 100                   # screenshot with grid overlay to read coordinates visually
ohd shot --cross 640,1200             # confirm which widget a point falls on
ohd shot --boxes                      # node boxes + centres
ohd video --duration 15s --fps 15 --out /tmp/demo.gif   # one command across three platforms; format from the --out suffix (mp4/gif)
ohd video --duration 20s --fps 15 --out /tmp/demo.webp  # animated WebP: ~90x smaller than gif for the same content (needs libwebp)
ohd video --duration 30s               # no --out -> platform-native format: Android/iOS=mp4, OHOS=gif
ohd tap 640 1200                      # raw coordinates; prefer `go` when there is a text landmark
ohd record                            # record to .ohd
ohd replay <file.ohd>
```

### Investigation

```bash
ohd path <hilog.txt> --package com.example.app --out replay.ohd   # hilog page events -> path skeleton
ohd crash                             # fault list
ohd crash --latest --details          # newest entry: raw text + parsed
ohd summary                           # one table across sessions (which round crashed, memory trend, evidence completeness)
ohd bundle --redact --report --zip    # merge last 2h + defect report + zip (exit 4 on missing pieces)
ohd analyze                           # offline completeness check of an evidence pack (no device)
ohd triage <automation-captures/YYYYMMDD-...>              # REPORT -> investigation tracks
ohd triage CAPTURE --reverify --bundle com.tencent.compose   # short-run re-verification per track
ohd ab --a before.hap --b after.hap --bundle com.tencent.compose --soak 2m
ohd diff --baseline a.png --current b.png
ohd repro                             # replay the evidence pack's steps to see if it crashes again
```

`path` only consumes hilog; lines without coordinates are left as `# TODO` and can be filled via
`--coords <file>` (`from->to = tap x y`).

### Crash symbolication (readable stacks for developers)

Resolves a faultlog into function names and file lines using local `so`/`hap` files whose
**buildId matches the running binary**. On a buildId mismatch it **refuses to symbolicate** (exit 7)
by default, so a wrong-version build can't mislead you.

```bash
# Recommended: point at the debug so from the same build as the installed package
# (KMP usually has debugShared / libs/arm64-v8a)
ohd sym --crash /path/faultlog.txt \
  --so-dir /path/composeApp/build/bin/ohosArm64/debugShared:/path/libs/arm64-v8a \
  --limit 40

# Newest entry on the device
ohd sym --latest --so-dir /path/to/debugShared

# Extract the so from the hap (saves unpacking by hand; refuses on buildId mismatch)
ohd sym --crash faultlog.txt --hap /path/app-signed.hap

# Only use --so-dir, ignore auto-discovered so files
ohd sym --crash faultlog.txt --so-dir /path/debugShared --no-auto-so

# Emergency: symbolicate despite a buildId mismatch (results may be misaligned)
ohd sym --crash faultlog.txt --so-dir /path/libs --allow-mismatch
```

Requires `llvm-addr2line`: `ohd doctor` checks for it; override with `--llvm` or
`ohd config set OHD_LLVM_ADDR2LINE`.

### Long-run watch

```bash
ohd soak --bundle com.example.app --duration 10h --interval 60s   # observe only (trend-oriented)
ohd soak --bundle com.example.app --duration 10h --drive <script.ohd>

# Actions come from elsewhere; this only records (run tap/replay/uitest in another window,
# watch logs + memory + crashes here)
ohd monitor --bundle com.example.app --duration 30m --interval 5s
```

## Cross-platform: Android

Same commands; the platform is auto-detected, or set explicitly:

```bash
ohd doctor                      # reports both hdc and adb
ohd log start                   # runs directly with a single device, OHOS or Android
ohd --platform android log start
ohd --device <serial> mem --bundle com.example.app
```

Only three layers differ: the channel (`hdc`/`adb`), the commands (`PlatformCmds`), and
output parsing (`AndroidParser`). The capability layer does not fork.
Android currently supports: `log` / `monitor` / `mem` / `tap` / `swipe` / `key` / `doctor`;
`tree` / `shot` / `install` / `crash` / `record` are pending (see DESIGN section 87).

## Multi-device in parallel

```bash
ohd --all monkey --bundle com.tencent.compose --duration 20s
ohd --all replay path/to/script.ohd
```

One child process per device; session directory, log state and output are isolated, and one device
failing does not affect the others. With several devices online, omitting `--all` still requires
`--device` — the tool does not guess (the only exception: a default set pinned via `ohd config devices`).

## Configuration: remember tool paths

```bash
ohd config set JAVA_HOME /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home
ohd config set OHD_HDC   ~/Library/OpenHarmony/Sdk/20/toolchains/hdc
ohd config list           # effective values + source (config / env)
ohd config keys           # list well-known keys
ohd config get JAVA_HOME
ohd config unset JAVA_HOME
ohd config env            # emit export lines, eval into the current shell
```

### Default devices: several attached, run only some

```bash
ohd devices                               # list: device / platform / connection / default
ohd devices use 63R0226113004249          # set default; --device can be omitted afterwards
ohd config devices                        # list online devices, marking the current default
ohd config devices FMR0225A,2B0913F       # pin defaults so no --device is needed per command
ohd config unset OHD_DEVICES              # back to "explicit --device when several are online"
```

- One device configured: equivalent to `--device <key>`, single-device path.
- Several configured: a subset of `--all` — runs them in parallel in the configured order; offline ones
  print "skipped" rather than being silently dropped.
- Precedence: `--device` > `--all` > `OHD_DEVICES` > `OHD_DEVICE`.
- The key is literally `OHD_DEVICES` (comma separated); you can also
  `ohd config set OHD_DEVICES a,b`.

Stored in `~/.ohd/config.json` (override with `OHD_CONFIG`). Override order:

**Explicit CLI flags (`--hdc` / `--llvm`) > config file > process environment > platform auto-detection**

The config file is just a list of "environment-variable name -> value", so:

- setting `JAVA_HOME` makes `.java` extensions use that JDK instead of ohd's own JVM;
- `OHD_HDC` / `OHD_LLVM_ADDR2LINE` are equivalent to passing `--hdc` / `--llvm` every time;
- `OHD_PYTHON` selects the interpreter for `.py` extensions;
- `OHD_EXT` points at an extensions directory, so a team can share one git checkout.

## Extensions: new commands as scripts

Beyond the built-in capabilities, `ohd <cmd>` looks for a script with that name in the extensions
directories. Scripts can compose existing `ohd` / `hdc` / `uitest` calls into new commands without
touching Kotlin or recompiling.

```bash
mkdir -p .ohd/extensions
cp examples/extensions/uitest.sh .ohd/extensions/
ohd uitest --count 20      # immediately available
ohd ext list               # list current extensions
ohd ext docs uitest        # show the script
ohd ext which uitest       # show which file it resolves to
```

Search order (highest priority first; first match wins):

| Directory | Purpose |
|---|---|
| `$OHD_EXT` | Explicit (team-shared directory) |
| `<cwd>/.ohd/extensions` | Travels with the repo, can be committed |
| `~/.ohd/extensions` | Personal |
| `<ohd install dir>/extensions` | Ships with the distribution |

Supported forms:

| Suffix | How it runs | Suits |
|---|---|---|
| `.sh` | `/bin/sh <file>` | Chaining hdc / uitest / ohd workflows |
| `.py` | `python3 <file>` (`OHD_PYTHON` overrides) | Parsing JSON, computing metrics |
| `.java` | `java <file>.java` (JDK 11+ source launcher) | Complex logic, reusing JDK libraries |
| `.bat` / `.cmd` | `cmd /c <file>` (Windows only) | Windows colleagues |
| no suffix | executed directly (needs `chmod +x`) | Any language |

Variables injected into scripts:

| Variable | Meaning |
|---|---|
| `OHD_SELF` | ohd entry point; scripts can call `"$OHD_SELF" tree` |
| `OHD_HDC` / `OHD_LLVM` | Resolved hdc / llvm paths |
| `OHD_DEVICE` | Device given via `--device` |
| `OHD_SESSION_ROOT` | Artifact root directory |
