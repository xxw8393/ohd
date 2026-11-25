# ohd 跨平台验收清单

> 给 Windows / Linux 同事。目标：**30 分钟内确认 ohd 在你的机器上能跑、输出正确**。
> 每一项都给了「预期」，不符就把输出原样贴回来——不用自己判断哪里坏了。

> **2026-10-19 复核**：分发形态已变——现在有 jlink 自带运行时的 zip 包，解压即用、**不需要 JDK**（§10）。
> 长跑脱管见 §11。下文 §1–§9 仍按**源码形态**写，走源码时才需要 JDK 17 + gradle。

## 0. 前提（源码形态；包形态见 §10）

| 项 | 要求 | 检查命令 |
|---|---|---|
| JDK | 17+ | `java -version` |
| gradle | 8.x（或用 `GRADLE` 环境变量指向 `gradle.bat`） | `gradle -v` |
| 设备 | 一台 OHOS 设备，`hdc list targets` 有输出 | `hdc list targets` |
| 终端 | Windows Terminal 或 conhost 均可 | — |

hdc 找不找得到**不用手工配**：ohd 会按「显式参数 → `OHD_HDC`/`HDC` 环境变量 → 平台默认 SDK → PATH」顺序找。
真找不到时 `ohd doctor` 会告诉你它找过哪些位置。

---

## 1. 五分钟快速验收（先跑这个）

```bash
# Windows
dist\build.bat
dist\smoke.bat

# Linux
cd ohd && ./dist/build.sh && ./dist/smoke.sh
```

**预期**：`build` 无 FAILURE；`smoke` 末尾输出「冒烟通过」。

失败的话先看这里，别急着往下：

| 现象 | 排查方向 |
|---|---|
| `找不到 gradle` | 装 gradle，或 `set GRADLE=C:\path\to\gradle.bat` |
| `UnsupportedClassVersionError` | JDK 版本低于 17 |
| `ohd 尚未构建` | `build` 没成功，先解决 build |

---

## 2. 环境自检

```bash
# Windows
ohd-cli\build\install\ohd\bin\ohd.bat selftest

# Linux
./ohd-cli/build/install/ohd/bin/ohd selftest
```

`selftest` 有 7 项断言，专门覆盖跨端易错点：

| 断言 | 验的是什么 |
|---|---|
| manifest 格式 | 会话产物写得出、读得回 |
| CRLF 归一 | `hdc` 在 Windows 上输出带 `\r`，所有解析器的隐形雷 |
| 中文宽度 | CJK 记 2 列的表格对齐 |
| UTF-8 解码 | 设备中文不影响解析 |
| 退出码 | 约定值没串 |
| 表格渲染 | 终端宽度与边框 |
| 时间格式 | ISO 时间列可被回读 |

**预期**：全部 `OK`，末尾「自检通过」。

> 有任何 `FAIL` 就是真问题，把整段贴回来。尤其是 **CRLF 归一** 和 **中文宽度** 两项——它们只在 Windows 上才会暴露。

---

## 3. 真机连通

```bash
ohd doctor
ohd env
ohd tree --limit 5
ohd shot
```

**预期**：

- `doctor`：hdc 找到、设备在线、`来源` 字段说明从哪找的（`sdk:...` / `PATH` / `argument/env`）
- `env`：设备型号、API、ABI、内存、存储，中文正常显示
- `tree`：打印 5 行节点，中文正常显示不断行
- `shot`：落一张 PNG，路径可打开

**重点看 Windows 的两件事**：

1. **中文是否乱码**——乱码说明控制台编码与 JVM 输出编码不一致。临时对策：`chcp 65001` 后重试
2. **表格边框是否错位**——conhost 对 Unicode 边框支持差，加 `--ascii` 对比：

```bash
ohd tree --limit 5 --ascii
```

如果 `--ascii` 正常而默认花屏，这是已知的终端限制，不是 bug（记下来即可）。

---

## 4. 参数与路径（Windows 特有雷区）

### 4.1 带空格 / 中文的参数

```bash
ohd bundle --session <目录> --out "%TEMP%\ohd 测试" --name "崩溃 演示"
```

**预期**：正常打包，`--name` 里的空格和中文不丢字。

失败通常是 `.bat` 的 `%*` 转发吃掉了引号——把完整命令行和报错贴回来。

### 4.2 盘符与空格路径

```bash
ohd shot --out "C:\Program Files\ohd-test"
```

**预期**：成功落图。ohd 内部对设备路径统一转正斜杠，但**本地路径**按原样处理，这里验的是没被错误转换。

### 4.3 反斜杠

```bash
ohd bundle --session ohd-evidence\device\20260101-120000-replay --out out
```

**预期**：正反斜杠都能识别入口。

---

## 5. 打包产物（含 GIF）

```bash
ohd replay <脚本.ohd> --record
ohd bundle --session <该会话目录> --out out --name demo
```

**预期**：

- `replay --record` 输出里有「录像」一行，指向 `.gif`
- `out\demo\replay\replay.gif` 存在，用图片查看器能播放（应循环）
- `out\demo\README.md` 里列了 `replay/replay.gif`
- `out\demo\context.json` 的 `artifacts` 里有 `"kind": "replay-gif"`

GIF 用 JDK 自带 ImageIO 生成，不依赖 ffmpeg；如果这一步失败，几乎一定是 `java.desktop` 模块缺失（精简版 JRE 常见）。

---

## 6. 脱敏

```bash
ohd bundle --session <目录> --out out --redact
```

**预期**：正常完成，`context.json` 里 `redaction.enabled = true`，gif 原样保留（二进制不参与脱敏）。

---

## 7. Linux 额外项

macOS/Windows 之外，Linux 主要验发现逻辑与编码默认值：

```bash
ohd doctor            # hdc 来源应为 /opt/openharmony/Sdk 或 PATH
echo $LANG            # 应为 UTF-8；C/POSIX 下中文表格会错位
ohd selftest
```

**注意**：`LANG=C` 时中文输出是 `?`，这不是 ohd 的问题，是 locale 设置。

---

## 8. 回报格式

跑完把下面这段填好贴回来（失败的项附完整输出）：

```
平台:      Windows 11 23H2 / Ubuntu 24.04 / 其它
JDK:       java version "..."
gradle:    x.y
hdc:       doctor 里的「来源」字段

build:     通过 / 失败（贴末尾 20 行）
smoke:     通过 / 失败
selftest:  N/7 通过（失败项：...）
doctor:    设备在线 是/否
tree 中文: 正常 / 乱码 / 断行
表格边框:  正常 / 错位（--ascii 下：正常/错位）
参数转发:  正常 / 丢字（--name 带空格中文）
盘符路径:  正常 / 失败
GIF:       能播放 / 不循环 / 未生成
脱敏:      正常 / 失败
```

---

## 9. 已知限制（不用报）

| 限制 | 说明 |
|---|---|
| 老 conhost 的 Unicode 边框 | 用 `--ascii`；不是缺陷 |
| `LANG=C` 下中文输出 | locale 问题，ohd 不强行改编码 |
| GIF 在精简 JRE 上失败 | 缺少 `java.desktop`，装完整 JDK 即可 |
| 截图里的人脸/号码未脱敏 | 只对文本脱敏，图像脱敏不在范围内 |
| 需要连接真机 | 大部分命令不支持纯离线；`--dry-run` 类命令除外 |

---

## 10. 包形态：解压即用，不需要 JDK（2026-10-19 新增）

源码形态（§1–§9）要 JDK 17 + gradle。**包形态只要 hdc**，对「只想跑一遍验证」的同事更省事。

### 10.1 拿包

| 平台 | 怎么得到 | 说明 |
|---|---|---|
| macOS（已有产物） | `ohd/dist/ohd-0.1.0-darwin-arm64.zip` | 27MB，直接解压 |
| Linux | 源码目录跑 `./dist/package.sh` | 需要 JDK 17 + `zip`；产物按 `uname` 命名 |
| Windows | 源码目录跑 `dist\package.bat`（同 `package.sh` 逻辑，压缩用 PowerShell） | **未在真 Windows 上实测**，跑不通请把输出贴回来 |

两个脚本做的事一样：`installDist` → `jlink` 生成 `runtime/`（约 40MB）→ 组装 `ohd`(`ohd.bat`) + `lib/` + `runtime/` + `README.txt` → 打 zip。
平台名来自 `uname -s`/`uname -m`（Windows 用 `%PROCESSOR_ARCHITECTURE%`），所以**每个平台各自打自己的包**，jlink 不能交叉生成。
启动脚本是**自适应**的：同目录有 `lib/` 就用打包形态 + `runtime/`，否则退回 `ohd-cli/build/install/ohd/lib` 开发形态。

### 10.2 验收三步

```bash
unzip ohd-<版本>-<平台>.zip && cd ohd-<版本>-<平台>
./ohd --help
./ohd selftest
./ohd doctor
```

**预期**：

- `--help` 正常打印、**不出现 `UnsupportedClassVersionError`**（那说明跑的是系统 java 而不是自带 `runtime/`）
- `selftest` 7/7（同上 §2 的表）
- `doctor` 只差设备时，报的是「设备未连接」而不是「找不到 hdc」——hdc 仍需 `PATH` 或 `OHD_HDC`

### 10.3 产物自检

```bash
du -sh runtime            # 约 40MB（完整 JDK 约 300MB）
ls lib | head             # ohd-cli 的 jar 与其依赖
./ohd summary             # 无会话时应给 NO_SESSION 提示，而不是崩溃（Windows: ohd.bat summary）
```

---

## 11. 长跑脱管（`--keep-going` 10 小时以上）（2026-10-19 新增）

**教训**：长跑挂在 IDE/agent 会话下，会话一重启就被带走（DESIGN §71，第 42 分钟被杀）。
工具支持 `--keep-going`（崩溃后 `aa force-stop` + `aa start` 重启继续，崩溃逐次留存），
但**进程必须脱离父会话**。

### 11.1 参数模板

```bash
./ohd monkey --bundle com.example --duration 10h --interval 0 --batch 10 \
             --sample-every 60 --check-every 30 --keep-going
```

### 11.2 脱离父进程

```bash
# Linux（setsid 来自 util-linux）
setsid nohup ./ohd monkey ... > /tmp/monkey-10h.log 2>&1 < /dev/null &

# macOS：没有 setsid，用 python 的 start_new_session（= setsid(2)）
python3 - <<'PY'
import subprocess
log = open('/tmp/monkey-10h.log', 'wb', 0)
subprocess.Popen(['./ohd','monkey','--bundle','com.example','--duration','10h',
                  '--interval','0','--batch','10','--sample-every','60',
                  '--check-every','30','--keep-going'],
                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                 start_new_session=True)
PY

# Windows（PowerShell，未实测——跑通了请回报）
Start-Process -WindowStyle Hidden -RedirectStandardOutput C:\temp\monkey.log `
  -FilePath .\ohd.bat -ArgumentList 'monkey','--bundle','com.example','--duration','10h','--keep-going'
```

### 11.3 怎么算脱管成功

```bash
ps -o pid,sess,cmd -p <pid>     # Linux/mac：sess 不等于当前 shell 的 sess
```

Windows：`Get-Process <pid> | Select-Object Id,SessionId`，或直接关掉终端再 `tasklist | findstr java` 能查到。

### 11.4 收网

```bash
./ohd summary --since 10h      # 读磁盘，不碰设备；--json 给脚本
```

---

## 12. 回报格式补充（2026-10-19 新增）

在 §8 那段之后，包形态/长跑各加三行：

```
形态:      包形态 / 源码形态
--help:    正常 / UnsupportedClassVersionError（说明没用自带 runtime）
runtime:   du 结果

keep-going: 崩溃后是否继续（结尾「崩溃次数 N」）
脱管:       关掉终端后进程是否还在（是/否）
summary:    能读出这次会话 / 读不到
```
