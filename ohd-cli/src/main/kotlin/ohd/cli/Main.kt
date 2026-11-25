package ohd.cli

import ohd.core.Capability
import ohd.core.Check
import ohd.core.Self
import ohd.core.Devices
import ohd.core.DeviceKind
import ohd.core.Env
import ohd.core.parseDurationMs
import ohd.core.Hdcs
import ohd.core.ExitCode
import ohd.core.OhdError
import ohd.core.Options
import ohd.core.Out
import ohd.core.RunContext
import ohd.core.caps.AnalyzeCap
import ohd.core.caps.TriageCap
import ohd.core.caps.BundleCap
import ohd.core.caps.ConfigCap
import ohd.core.caps.CpuCap
import ohd.core.caps.DevicesCap
import ohd.core.caps.DmabufCap
import ohd.core.caps.ReportCap
import ohd.core.caps.ReproCap
import ohd.core.caps.KingkongCap
import ohd.core.caps.MonkeyCap
import ohd.core.caps.MonitorCap
import ohd.core.caps.PathCap
import ohd.core.caps.SummaryCap
import ohd.core.caps.AbCap
import ohd.core.caps.InstallCap
import ohd.core.caps.SalvageCap
import ohd.core.caps.SoakCap
import ohd.core.caps.CrashCap
import ohd.core.caps.DiffCap
import ohd.core.caps.DoctorCap
import ohd.core.caps.EnvDumpCap
import ohd.core.caps.ExtCap
import ohd.core.caps.InputCap
import ohd.core.caps.LogCap
import ohd.core.caps.MemCap
import ohd.core.caps.ReplayCap
import ohd.core.caps.RecordCap
import ohd.core.caps.SelftestCap
import ohd.core.caps.ShotCap
import ohd.core.caps.SymCap
import ohd.core.caps.BackCap
import ohd.core.caps.GoCap
import ohd.core.caps.MapCap
import ohd.core.caps.TapCap
import ohd.core.caps.TreeCap
import ohd.core.caps.WaitCap
import ohd.core.caps.VideoCap
import ohd.core.caps.WatchCap
import ohd.image.ImageIOEngine
import ohd.core.OhdConfig
import ohd.core.ext.Extension
import ohd.core.ext.Extensions
import java.nio.file.Files
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val VERSION = ohd.core.TOOL_VERSION

/** 布尔开关：这些名字永不吞下一个 token，避免 `--json doctor` 把 doctor 当成值。 */
private val BOOL_FLAGS = setOf(
    "json", "ascii", "no-color", "no-archive", "dry-run", "yes",
    "debug", "help", "version", "require-device", "quiet",
    "redact", "report", "zip", "latest-only", "all", "ext-raw",
    "tcp-only", "process", "unset", "no-compress", "no-system",
)

fun main(args: Array<String>) {
    // 无显示环境下也要能读图（ImageIO 不需要 display，但 awt 初始化会因缺 DISPLAY 报错）
    System.setProperty("java.awt.headless", "true")
    val code = try {
        dispatch(args.toList())
    } catch (e: OhdError) {
        System.err.println("x ${e.message}")
        e.hint?.let { System.err.println("  -> $it") }
        // 用法错误只回一句「需要 --a」等于让用户去猜；直接把这个能力的用法和最小示例摆出来
        if (e.code == ExitCode.USAGE) printUsageHint(args, e.hint)
        e.code
    } catch (e: Exception) {
        System.err.println("x 未预期异常：${e::class.simpleName}: ${e.message}")
        if (args.contains("--debug")) e.printStackTrace()
        else System.err.println("  -> 加 --debug 看栈")
        ExitCode.ASSERT_FAILED
    }
    exitProcess(code)
}

/** 用法错误后的兜底说明：hint 已经写了用法/示例就不再重复，只补「完整帮助」入口。 */
private fun printUsageHint(args: Array<String>, hint: String?) {
    val cap = parse(args.toList()).capability?.let { caps()[it] } ?: return
    val selfDocumented = hint != null && (hint.contains("用法") || hint.contains("示例"))
    if (!selfDocumented) {
        System.err.println()
        System.err.println("用法")
        UsageDocs.usageLines(cap).forEach { System.err.println("  $it") }
        val sample = UsageDocs.sampleOf(cap).take(2)
        if (sample.isNotEmpty()) {
            System.err.println("示例")
            for ((cmd, why) in sample) {
                System.err.println("  $cmd" + if (why.isNotBlank()) "    # $why" else "")
            }
        }
    }
    System.err.println("完整说明：ohd ${cap.name} --help")
}

private fun caps(): Map<String, Capability> = linkedMapOf(
    "doctor" to DoctorCap(),
    "selftest" to SelftestCap(listOf(::imageEngineCheck)),
    "env" to EnvDumpCap(),
    "env-dump" to EnvDumpCap(),
    "tree" to TreeCap(),
    "shot" to ShotCap(),
    "video" to VideoCap(),
    "mem" to MemCap(),
    "cpu" to CpuCap(),
    "log" to LogCap(),
    "watch" to WatchCap(),
    "diff" to DiffCap(),
    "tap" to TapCap(),
    "go" to GoCap(),
    "wait" to WaitCap(),
    "back" to BackCap(),
    "map" to MapCap(),
    "input" to InputCap(),
    "replay" to ReplayCap(),
    "record" to RecordCap(),
    "crash" to CrashCap(),
    "sym" to SymCap(),
    "bundle" to BundleCap(),
    "analyze" to AnalyzeCap(),
    "triage" to TriageCap(),
    "path" to PathCap(),
    "dmabuf" to DmabufCap(),
    "report" to ReportCap(),
    "repro" to ReproCap(),
    "soak" to SoakCap(),
    "monkey" to MonkeyCap(),
    "kingkong" to KingkongCap(),
    "monitor" to MonitorCap(),
    "summary" to SummaryCap(),
    "salvage" to SalvageCap(),
    "install" to InstallCap(),
    "ab" to AbCap(),
    "config" to ConfigCap(),
    "devices" to DevicesCap(),
    "ext" to ExtCap(),
)

private fun dispatch(argv: List<String>): Int {
    val parsed = parse(argv)

    if (parsed.flags.contains("version") || parsed.capability == "version") {
        println("ohd $VERSION")
        return ExitCode.OK
    }

    val capName = parsed.capability
    if (capName == null) {
        printGlobalHelp()
        return ExitCode.OK
    }

    // 多设备并行：每台设备一个独立子进程，互不影响。
    // `devices` 自带 --all 语义（一次处理全部设备），不能被分发成「每台各跑一遍」。
    if (parsed.flags.contains("all") && capName !in SELF_ALL_CAPS) {
        return runOnAllDevices(argv, parsed, capName)
    }
    // 配置里的默认设备集合（OHD_DEVICES）：--all / --device 都没给时生效；多台就并行跑这几台。
    // 不碰设备的能力（config/summary/analyze...）不参与分发，否则配了两台就会把输出打两遍。
    if (parsed.values["device"] == null && capName !in DEVICE_FREE_CAPS) {
        val configured = configuredDevices()
        if (configured.size > 1) return runOnAllDevices(argv, parsed, capName, configured)
    }

    val cap = caps()[capName]
    if (cap == null) {
        // 内置能力优先；没命中再看扩展目录里有没有同名脚本
        val cwd = Paths.get("").toAbsolutePath()
        val ext = Extensions.discover(cwd)[capName]
        if (ext == null) {
            System.err.println("x 未知能力：$capName")
            printGlobalHelp(System.err)
            return ExitCode.USAGE
        }
        if (parsed.flags.contains("help") || parsed.flags.contains("h")) {
            printExtensionHelp(ext)
            return ExitCode.OK
        }
        // 业务参数原样透传（脚本的选项空间 ohd 不认识，不替它校验）；
        // 但 ohd 自己的全局选项要剥掉——它们已通过 OHD_* 环境变量交付，
        // 透传只会让脚本收到自己没定义的 --device/--json 而报错
        val extEnv = Env(explicitHdc = parsed.values["hdc"], explicitLlvm = parsed.values["llvm"], explicitAdb = parsed.values["adb"], sysenv = OhdConfig.sysenv())
        val exit = Extensions.exec(
            ext = ext,
            args = stripOhdOptions(argv.drop(parsed.capabilityIndex + 1)),
            env = Extensions.envOf(
                hdc = extEnv.hdc.argv0().takeIf { it.isNotBlank() },
                llvm = extEnv.llvmAddr2line.argv0().takeIf { it.isNotBlank() },
                device = parsed.values["device"],
                sessionRoot = parsed.values["session-root"]?.let { Paths.get(it) },
                json = parsed.flags.contains("json"),
                ascii = parsed.flags.contains("ascii"),
            ),
            timeoutMs = extTimeoutOf(parsed),
            memLimitMb = extMemOf(parsed),
            maxLines = parsed.values["ext-max-lines"]?.toIntOrNull()
                ?: Extensions.DEFAULT_MAX_LINES,
            raw = parsed.flags.contains("ext-raw"),
        )
        return exit
    }
    if (parsed.flags.contains("help") || parsed.flags.contains("h")) {
        println(UsageDocs.render(cap))
        return ExitCode.OK
    }

    // 拼错的参数必须报错：静默忽略会让人以为参数生效了
    val globalOptions = setOf(
        "json", "hdc", "llvm", "color", "no-color", "ascii", "session-root", "archive", "no-archive",
        "device", "debug", "help", "h", "log-compress", "platform", "adb", "all", "ext-timeout", "ext-mem", "ext-max-lines",
    )
    val unknown = if (cap.passthrough) emptySet() else (parsed.values.keys + parsed.flags) - globalOptions - cap.accepts
    if (unknown.isNotEmpty()) {
        val listed = unknown.sorted().joinToString(" ") { "--$it" }
        val supported = if (cap.accepts.isEmpty()) "（该能力不接收选项）"
        else cap.accepts.sorted().joinToString(" ") { "--$it" }
        System.err.println("x 未知参数：$listed")
        System.err.println("  该能力支持：$supported")
        System.err.println("  用法：${cap.usage}")
        return ExitCode.USAGE
    }

    val color = when (parsed.values["color"] ?: "auto") {
        "always" -> true
        "never" -> false
        else -> System.getenv("NO_COLOR") == null && System.console() != null
    }
    val out = Out(
        jsonMode = parsed.flags.contains("json"),
        color = color && !parsed.flags.contains("no-color"),
        ascii = parsed.flags.contains("ascii"),
    )
    // 重复选项：Map 语义只保留最后一个值。静默丢值比报错危险（--session a --session b 就只剩 b）。
    if (parsed.duplicated.isNotEmpty()) {
        val names = parsed.duplicated.joinToString("、") { "--$it" }
        System.err.println("! 选项重复出现：$names（只采用最后一个值）")
        if ("session" in parsed.duplicated) {
            System.err.println("  需要多个会话请用逗号：--session a,b,c")
        }
    }
    val env = Env(explicitHdc = parsed.values["hdc"], explicitLlvm = parsed.values["llvm"], explicitAdb = parsed.values["adb"], sysenv = OhdConfig.sysenv())
    val cwd = Paths.get("").toAbsolutePath()
    val sessionRoot = parsed.values["session-root"]?.let { Paths.get(it) }

    val ctx = RunContext(
        env = env,
        out = out,
        options = Options(effectiveValues(parsed), parsed.flags.toSet(), parsed.positionals.toList()),
        cwd = cwd,
        sessionRoot = sessionRoot,
        archive = !parsed.flags.contains("no-archive"),
        rawArgs = argv.drop(parsed.capabilityIndex + 1),
        capability = capName,
        builtinNames = caps().keys,
    )
    val code = cap.run(ctx)
    // 统一落 manifest：任何能力只要创建了会话，就必须留下产物元数据（bundle 靠它归类）
    if (ctx.sessions.isNotEmpty()) {
        val dev = Hdcs.deviceInfoOrNull(ctx)
        ctx.sessions.forEach { s ->
            runCatching { s.writeManifest(dev, s.verdict?.let { mapOf("verdict" to it) } ?: emptyMap()) }
        }
    }
    return code
}

internal class Parsed(
    var capability: String? = null,
    /** 能力名在 argv 中的下标：扩展要原样透传后面的参数，得知道从哪切。 */
    var capabilityIndex: Int = -1,
    val values: MutableMap<String, String> = linkedMapOf(),
    val flags: MutableSet<String> = linkedSetOf(),
    val positionals: MutableList<String> = mutableListOf(),
    /** 重复出现的选项名：Map 语义只会留最后一个，静默丢值比报错更危险。 */
    val duplicated: MutableSet<String> = linkedSetOf(),
)

internal fun parse(argv: List<String>): Parsed {
    val p = Parsed()
    var i = 0
    var endOfOptions = false
    while (i < argv.size) {
        val a = argv[i]
        when {
            endOfOptions -> p.positionals += a
            a == "--" -> endOfOptions = true
            a.startsWith("--") -> {
                val body = a.substring(2)
                val eq = body.indexOf('=')
                when {
                    eq > 0 -> {
                        val k = body.substring(0, eq)
                        if (k in p.values) p.duplicated += k
                        p.values[k] = body.substring(eq + 1)
                    }
                    body in BOOL_FLAGS -> p.flags += body
                    i + 1 < argv.size && !argv[i + 1].startsWith("-") -> {
                        if (body in p.values) p.duplicated += body
                        p.values[body] = argv[i + 1]
                        i++
                    }
                    else -> p.flags += body
                }
            }
            a.length > 1 && a.startsWith("-") -> p.flags += a.substring(1)
            else -> if (p.capability == null) {
                p.capability = a
                p.capabilityIndex = i
            } else {
                p.positionals += a
            }
        }
        i++
    }
    return p
}

/** 图像引擎自检：生成一张小图 → 载入 → 自比较必须为 0 差分。 */
internal fun imageEngineCheck(): Check = try {
    val tmp = Files.createTempFile("ohd-selftest-", ".png")
    val img = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until 16) for (x in 0 until 16) {
        val v = if ((x / 4 + y / 4) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        img.setRGB(x, y, v)
    }
    ImageIO.write(img, "png", tmp.toFile())
    val engine = ImageIOEngine()
    val g = engine.load(tmp)
    val d = engine.diff(g, g)
    Files.deleteIfExists(tmp)
    if (d.maxAbs == 0 && d.meanAbs == 0.0) {
        Check.ok("image", "engine=imageio 解码/差分正常（HF=${"%.2f".format(engine.highFrequencyEnergy(g))}）")
    } else {
        Check.fail("image", "自比较差分非 0：max=${d.maxAbs} mean=${d.meanAbs}")
    }
} catch (e: Exception) {
    Check.fail("image", "图像引擎异常：${e.message}")
}

internal fun globalHelpText(extBlock: String = ""): String = """
        ohd $VERSION — OHOS 真机能力工具箱（Kotlin/JVM，跨 mac / Windows / Linux）

        用法: ohd <capability> [选项]
        单个能力的参数与示例: ohd <capability> --help（例：ohd monkey --help）

        环境与自检
          doctor      环境自检：hdc / 设备 / uitest / faultlog / llvm / 磁盘
          config      用户配置：持久化 hdc / llvm / JAVA_HOME 等路径（ohd config list）
          selftest    离线自检：解析器 / 会话 / argv / 编码 / 图像引擎（不碰设备）
          env-dump    采集设备指纹（型号 / API / 版本 / ABI / 内存 / 存储 / 时区）

        观测（M1）
          tree        节点树查询：按文本/类型/id 找节点，给 bounds 与点击中心点
          shot        截图 + 坐标标注：--grid 目视取坐标，--cross 确认点击点，--boxes 画节点框
          mem         进程内存：Pss / SwapPss / GL / Graph / native heap 分类明细
          cpu         CPU：总占用 / 进程 Top N / --cpufreq 逐核频率
          log         日志流：start / stop / snap / count / save（默认 2G 截断，退出回收子进程）
          watch       持续跟踪：CSV + 斜率 + 阶梯 + 平台期（只给事实，不下泄漏结论）

        动作（M2）
          tap         点击坐标：单击 / 双击 / 长按
          input       输入文本 / 滑动 / 拖拽 / 按键
          replay      回放 .ohd 动作脚本（每步记录结果，脚本随证据存档）
          record      录制真机操作：uiRecord 转成 .ohd，回放前冷启动应用入口

        对比与崩溃（M2）
          diff        截图对比：差异指标 + 热力图，超阈值 exit 7
          crash       抓崩溃：列故障 / 取最新 / 解析 reason 与栈
          sym         栈符号化：llvm-addr2line + buildId 校验（不匹配拒绝出符号）

        验证收益
          install     安装/替换 hap：覆盖安装，可保留应用数据
          ab          A/B 对比：同设备同 seed 同时长，只报 PSS/DMA/崩溃数值差

        交付（M3）
          bundle      会话 → ohd-case/1（默认 --since 2h；--report/--zip/--latest-only；缺件 exit 4）
          analyze     证据包 → 事实清单（崩溃/栈/指标/完备性 + 下一步方向）
          triage      automation REPORT → 调查赛道；--reverify 调 dmabuf/mem 短跑复验
          path        hilog 页面事件 → .ohd 路径骨架（--algo stack|first；需 --coords）
          dmabuf      DMA buffer 快照与增量（按标签分组、ino 去重）
          report      证据包 → 缺陷单 report.md（可直接提交）
          repro       按证据包步骤重放，判断缺陷是否复现（看新增同类故障）
          soak        长稳值守：PSS/CPU/DMA 周期采样 + 可选操作驱动
          monkey      随机事件注入：崩溃时自动生成可回放脚本 + 现场（--max-steps 0 不限）
          monitor     旁路观测：抓 hilog + 采样内存/CPU/DMA + 崩溃检测，自身不注入事件
          summary     收网：历次会话一张表（哪轮崩了、内存怎么走、证据齐不齐）
          salvage     从残留会话反推运行画像（只报可确认项）
        @@EXT@@
        全局选项
          --json              输出结构化 JSON（agent 消费）
          --device <key>      指定设备（多台在线时必填；按序列号精确匹配；默认值可用 ohd config devices 固定）
          --platform <p>      指定平台 ohos|android|ios|ios-device（默认自动探测）
          --all               对当前所有在线设备并行执行同一命令（互不影响）
          --ext-timeout <d>   扩展运行上限，如 30m；0 表示不限（默认 0）
          --ext-mem <s>       扩展内存上限，如 2g/512m；0 表示不限（默认 2g）
          --ext-max-lines <n> 扩展输出行数上限（默认 100000）
          --ext-raw           扩展输出直通终端（交互式扩展用）
          --adb <path>        指定 adb（Android 用；也可 ohd config set OHD_ADB）
          --hdc <path>        指定 hdc 可执行文件
          --llvm <path>       指定 llvm-addr2line
          --session-root <d>  产物根目录（默认 <cwd>/ohd-evidence/device）
          --no-archive        不落盘
          --log-compress <f>  日志落盘格式：gzip（默认，多成员可追加）| none（明文 .txt）
          --no-color          关闭颜色     --ascii   ASCII 表格（Windows conhost）
          --debug             异常打印栈     --version / --help

        入门流程（从零到一份能交出去的证据包）
          1) ohd selftest && ohd doctor                     先证明能跑、能连设备
          2) ohd install app-signed.hap --keep-data          装待测包，保留应用数据
          3) ohd log start --tag Konan_main                  开始抓日志（默认 2G 截断）
          4) ohd monkey --bundle com.example.app --duration 30m --keep-going
                                                            半小时随机压测，崩了继续
          5) ohd mem --bundle com.example.app                中途看内存；cpu 同理
          6) ohd log stop                                    收尾（务必成对，否则流会僵死）
          7) ohd bundle --redact --report --zip              打证据包 + 缺陷单
          8) ohd analyze                                     复核包里到底有什么、缺什么
          9) ohd triage <automation-captures/…> --reverify   REPORT 分流并短跑复验水位

        常见组合
          ohd tree --grep 设置 --clickable        ① 找节点，拿到点击中心点
          ohd shot --grid 100                     ② 目视读坐标（不放心时叠网格）
          ohd tap 640 1200                        ③ 点它
          ohd watch --bundle x --duration 120s    看内存走势（斜率 / 阶梯 / 平台期）
          ohd crash --details --limit 20          看崩了什么
          ohd sym --latest --so-dir /path/libs    出可读栈（buildId 不匹配会拒绝）
          ohd summary --since 24h                 当天所有会话一张表
        """.trimIndent().replace("@@EXT@@", extBlock)

private fun printGlobalHelp(sink: java.io.PrintStream = System.out) {
    sink.println(globalHelpText(extensionBlock()))
}

/**
 * 全局帮助里的扩展段：直接读目录，所以在仓库里放一个脚本，`ohd --help` 立刻能看见。
 * 内置能力名不会出现在这里（调用方已按内置优先过滤）。
 */
private fun extensionBlock(): String {
    val cwd = Paths.get("").toAbsolutePath()
    val found = Extensions.discover(cwd)
    val sb = StringBuilder("        扩展命令\n")
    if (found.isEmpty()) {
        sb.append("          （无）把 .sh/.py/.java/.bat 放进下列任一目录即新增命令：\n")
        Extensions.roots(cwd).forEach { sb.append("            $it\n") }
        sb.append("          说明：脚本首行写 `# @ohd 一行说明` 会显示在这里\n")
        return sb.toString()
    }
    found.values.forEach { e ->
        sb.append("          ").append(e.name.padEnd(12)).append(' ').append(e.summary).append('\n')
    }
    sb.append("          管理：ohd ext list | ohd ext docs <命令>\n")
    return sb.append('\n').toString()
}

/**
 * 多设备并行执行同一个能力。
 *
 * 用**子进程**而不是线程：monkey 这类任务是分钟级长跑，进程内共享输出、会话目录与配置缓存；
 * 一台设备卡死/OOM 会拖垮线程模型里的全部设备。子进程各自持有完整的 ohd 状态，
 * 会话目录与 log-state 通过 `--device` 后缀隔离（见 Hdcs.session / defaultStateFile）。
 *
 * Ctrl-C 时统一终止所有子进程（shutdown hook）。
 */
/** 扩展运行时长的上限；`--ext-timeout 0` 表示不限。 */
private fun extTimeoutOf(parsed: Parsed): Long {
    val raw = parsed.values["ext-timeout"] ?: return Extensions.DEFAULT_TIMEOUT_MS
    return runCatching { parseDurationMs(raw) }.getOrElse {
        throw OhdError(ExitCode.USAGE, "无法解析 --ext-timeout：$raw", "例：--ext-timeout 10m / 90s / 0")
    }
}

/** 扩展内存上限（MB）；`--ext-mem 0` 表示不限。 */
private fun extMemOf(parsed: Parsed): Long {
    val raw = parsed.values["ext-mem"] ?: return Extensions.DEFAULT_MEM_LIMIT_MB
    val t = raw.trim().lowercase()
    val mb = when {
        t == "0" || t == "off" -> return 0L
        t.endsWith("g") -> t.dropLast(1).toLongOrNull()?.times(1024)
        t.endsWith("m") -> t.dropLast(1).toLongOrNull()
        else -> t.toLongOrNull()
    }
    return mb ?: throw OhdError(ExitCode.USAGE, "无法解析 --ext-mem：$raw", "例：--ext-mem 2g / 512m / 0")
}

/** 剥掉已由 ohd 消费、并通过环境变量交付的全局选项。 */
private fun stripOhdOptions(args: List<String>): List<String> {
    val valued = setOf("device", "hdc", "llvm", "adb", "platform", "session-root", "log-compress", "color")
    val out = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("-") && a.length > 1) {
            val body = a.trimStart('-').substringBefore('=')
            if (a.contains('=') && body in valued) { i++; continue }
            if (body in BOOL_FLAGS) { i++; continue }
            if (body in valued) { i += 2; continue }
        }
        out += a
        i++
    }
    return out
}

/** 完全不碰设备的能力：不参与 OHD_DEVICES 的并行分发（否则配几台就把输出打几遍）。 */
private val DEVICE_FREE_CAPS = setOf(
    "config", "selftest", "analyze", "triage", "report", "summary", "salvage", "path", "ext", "devices",
)

/** 自带 `--all` 语义的能力：自己处理「全部设备」，不参与 CLI 的并行分发。 */
private val SELF_ALL_CAPS = setOf("devices")

/** 配置里的默认设备（OHD_DEVICES 优先，其次 OHD_DEVICE）；逗号/分号/空格分隔，允许中文逗号。 */
internal fun configuredDevices(): List<String> {
    val sysenv = OhdConfig.sysenv()
    val many = sysenv("OHD_DEVICES")
        ?.split(',', '，', ';', ' ')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (many.isNotEmpty()) return many
    return sysenv("OHD_DEVICE")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
}

/** CLI 显式 --device 优先；否则回退配置里的第一台（多台的情形已在 dispatch 里并行分流）。 */
private fun effectiveValues(parsed: Parsed): Map<String, String> {
    if (parsed.values["device"] != null) return parsed.values
    val fallback = configuredDevices().firstOrNull() ?: return parsed.values
    return parsed.values + ("device" to fallback)
}

private fun runOnAllDevices(
    argv: List<String>,
    parsed: Parsed,
    capName: String,
    /** 配置里指定的设备子集（OHD_DEVICES）；null 表示「所有在线设备」，即 --all 的原语义。 */
    only: List<String>? = null,
): Int {
    if (parsed.values["device"] != null) {
        System.err.println("x --all 与 --device 不能同时使用（--all 就是「每台都跑」）")
        return ExitCode.USAGE
    }
    val launch = Self.launchArgv()
    if (launch == null) {
        System.err.println("x 无法定位 ohd 自身入口；请用发行包 ./ohd 运行，或设置 OHD_SELF")
        return ExitCode.UNSUPPORTED
    }

    val env = Env(
        explicitHdc = parsed.values["hdc"],
        explicitLlvm = parsed.values["llvm"],
        explicitAdb = parsed.values["adb"],
        sysenv = OhdConfig.sysenv(),
    )
    val want = DeviceKind.of(parsed.values["platform"])
    val online = Devices.all(env).filter { want == null || it.kind == want }
    // 配置集合按用户给的顺序排，不在线的点名提示（不静默跳过，否则会以为跑了）
    val cands = if (only == null) online else only.mapNotNull { key -> online.firstOrNull { it.key == key } }
    if (only != null) {
        val missing = only.filter { key -> online.none { it.key == key } }
        if (missing.isNotEmpty()) {
            System.err.println("! 配置里的设备不在线，已跳过：${missing.joinToString("，")}")
        }
    }
    if (cands.isEmpty()) {
        System.err.println("x 没有可并行的设备" + (want?.let { "（平台 ${it.label}）" } ?: ""))
        return ExitCode.NO_DEVICE
    }

    // 子进程参数：能力名之后的原始参数，去掉 --all 本身，其余（--platform 等）原样保留
    val rest = argv.drop(parsed.capabilityIndex + 1).filterNot { it == "--all" || it == "-all" }
    val width = cands.maxOf { it.key.length }

    println("并行执行  ${cands.size} 台设备  ${cands.joinToString("，") { "${it.key}(${it.kind.label})" }}")
    println()

    data class Child(val cand: Devices.Candidate, val proc: Process, val startedAt: Long)

    val children = cands.map { c ->
        val cmd = launch + capName + listOf("--device", c.key) + rest
        Child(c, ProcessBuilder(cmd).redirectErrorStream(true).start(), System.currentTimeMillis())
    }

    Runtime.getRuntime().addShutdownHook(
        Thread { children.forEach { runCatching { it.proc.destroy() } } },
    )

    val lock = Any()
    val readers = children.map { ch ->
        Thread {
            runCatching {
                ch.proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(lock) { println("${ch.cand.key.padEnd(width)} │ $line") }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
    }

    val codes = children.map { it.proc.waitFor() }
    readers.forEach { runCatching { it.join(2_000) } }
    val elapsed = children.mapIndexed { i, ch -> System.currentTimeMillis() - ch.startedAt }

    println()
    println("── 汇总 ──")
    children.forEachIndexed { i, ch ->
        val code = codes[i]
        val mark = if (code == ExitCode.OK) "✓" else "✗"
        println(
            "$mark ${ch.cand.key.padEnd(width)}  ${ch.cand.kind.label.padEnd(7)}  " +
                "%6.1fs".format(elapsed[i] / 1000.0) + "  退出码 $code",
        )
    }
    val failed = codes.count { it != ExitCode.OK }
    if (failed > 0) println("! $failed / ${codes.size} 台未成功（其它设备的产物不受影响）")
    return codes.firstOrNull { it != ExitCode.OK } ?: ExitCode.OK
}

private fun printExtensionHelp(ext: Extension) {
    println("ohd ${ext.name} — ${ext.summary}")
    println()
    println("类型：${ext.kind.label}    文件：${ext.file}")
    println("参数原样透传给脚本（ohd 不解析扩展选项）。看实现：ohd ext docs ${ext.name}")
    println("注入变量：OHD_SELF OHD_HDC OHD_DEVICE OHD_SESSION_ROOT OHD_JSON OHD_EXT")
}
