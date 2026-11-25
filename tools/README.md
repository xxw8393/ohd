# ohd/tools — 原型脚本（对拍 / 无 JVM 时用）

`ohd path` 已进 CLI。这里的 Python 是同算法原型（2026-10-19 `/tmp`，会话 `session-65c27c2f`），用于对拍。

## path_from_log.py

从 hilog 里的页面事件反推操作路径，生成可 `replay` 的 `.ohd` 骨架。

推荐：

```bash
./dist/ohd path ohd-evidence/device/20261019-160302-log-save/001-hilog.txt \
  --map ohd/tools/samples/pagemap.txt \
  --coords ohd/tools/samples/coords2.txt \
  --out /tmp/inferred.ohd
```

Python 对拍（在 workspace 根 `/Users/xuxingwang/KMP`；证据在根的 `ohd-evidence/`）：

```bash
python3 ohd/tools/path_from_log.py \
  ohd-evidence/device/20261019-160302-log-save/001-hilog.txt \
  --map ohd/tools/samples/pagemap.txt \
  --coords ohd/tools/samples/coords2.txt
```

输出（节选，见 `samples/path-sample.ohd`）：

```
# hilog 推断脚本（from->to 映射）
launch com.tencent.compose EntryAbility
tap 297 683   # Compose_0->Compose_1 → MultiInstance @16:03:44.549
doubletap 483 1214   # Compose_1->Compose_2 → MultiInstance#2 @16:03:44.974
key Back   # Compose_2->Compose_0 → Index @16:03:55.238
```

### 语义模型

| 日志事实 | 推断动作 |
|---|---|
| `Compose_N` 首次 `onPageShow` | 进入新页面实例（`tap`，坐标需 `--coords` 给） |
| `Compose_N` 再次 `onPageShow`（栈里已有） | 回到更早实例（`key Back`） |
| `onPageHide` | 不移除实例（ArkUI hide 后仍存活） |

### 两种算法

- `--algo stack`（默认，v2）：维护页面栈，输出 `from->to` 转移，coords 键 `Compose_0->Compose_1`。
- `--algo first`（v1）：只看首次出现顺序，coords 键 `enter:Compose_1`；同一实例连续 show 去重。
- `--json` 输出 `{events, transitions|steps}`，便于被别的东西消费。

### 局限（重要）

1. 只有**页面级**事件：拿不到点击的控件，`--coords` 必须手工补；未给坐标的转移输出 `# TODO` 行。
2. 依赖被观测 App 的日志格式：`ArkUIViewController(Compose_N) onPageShow/Hide` 与 `ComposeAppShell: <页面名>`。
3. 已产品化为 `ohd path`；本脚本仅作对拍，行为以 Kotlin 为准。

## samples/

| 文件 | 用途 |
|---|---|
| `pagemap.txt` | `Compose_N=页面名`，把实例号映射成可读页面名 |
| `coords2.txt` | stack 算法的 `from->to=动作` 映射 |
| `coords.txt` | first 算法的 `enter:Compose_N=动作` 映射 |
| `path-sample.ohd` | 用上面输入对 `20261019-160302-log-save` 生成的产物样例 |

## bin/ — 设备侧外部工具归档（按 ABI 分目录）

同一工具在不同 ABI 上的产物不同，**必须按 ABI 分目录**，不允许平铺混放：

```
tools/bin/<tool>/<abi>/<binary>
```

| 工具 | ABI | 用途 |
|---|---|---|
| `kingkong` | `arm64-v8a` | DevEco Testing 稳定性 monkey 引擎（v3.6.23），复现 8h 稳定性压测；用法见 `bin/kingkong/README.md` |

`push.sh` 约定：`--abi` 缺省 `arm64-v8a`；目标 ABI 目录不存在时**报错并列出已有 ABI**，
不会静默用别的架构顶替（避免把 x86 产物推到真机上白测）。
