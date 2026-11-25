package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Faults
import ohd.core.Hdc
import ohd.core.Hdcs
import ohd.core.HilogStream
import ohd.core.DeviceKind
import ohd.core.PlatformCmds
import ohd.core.logCompressOf
import ohd.core.logFileSuffix
import ohd.core.logSink
import ohd.core.OhdError
import ohd.core.ProcessTrees
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.isoTime
import ohd.core.parseDurationMs
import ohd.core.parsers.AndroidParser
import ohd.core.parsers.CpuParser
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser

/**
 * 旁路观测：只记录，不驱动。
 *
 * 与 `monkey` 的分工：`monkey` 自己产生事件（随机点/滑），所以既能压也能抓；
 * `monitor` 一个事件都不产生——动作由**别的工具**给（另一个窗口的 `ohd tap`、
 * `ohd replay`、`uitest` 脚本、或人手动点）。它只做四件事：
 *
 * 1. 全程 hilog 落盘（tag/关键字过滤可选）；
 * 2. 周期采样 PSS / Graph / GL / CPU / dmabuf；
 * 3. 每 N 轮看一次 faultlog，出现新崩溃就把现场留下来；
 * 4. 每轮 `pidof` 校验进程存活（死了立刻知道，而不是等采样报错）。
 *
 * 为什么不复用 `soak`：`soak` 的采样是「测内存趋势」导向（30s 起步、输出趋势判定），
 * 而监控场景需要更密的采样、日志与崩溃对齐、以及「进程何时消失」这种时间线。
 */
class MonitorCap : Capability {
    override val name = "monitor"
    override val summary = "旁路观测：抓 hilog + 周期采样 PSS/Graph/GL/CPU/DMA + 崩溃检测，不注入任何事件"
    override val usage =
        "ohd monitor [--pid N | --bundle b] [--duration 30m] [--interval 10s] [--tag a,b] [--check-every 2] [--no-hilog] [--no-compress] [--json]"
    override val example = "ohd monitor --bundle com.tencent.compose --duration 1h --interval 10s"
    override val accepts: Set<String> =
        setOf(
            "pid", "bundle", "duration", "interval", "tag", "match", "exclude", "full", "max-size",
            "check-every", "no-hilog", "no-compress", "log-compress",
        )

    private data class Sample(
        val i: Int,
        val ms: Long,
        val pssKb: Long,
        val cpu: Double,
        val dmaBytes: Long,
        val dmaEntries: Int,
        val alive: Boolean,
        /** 与 pss 同源（同一次 hidumper --mem）：Graph 是窗口合成缓冲，GL 是纹理。-1 表示该设备无此项。 */
        val graphKb: Long = -1L,
        val glKb: Long = -1L,
        val nativeHeapKb: Long = -1L,
    )

    private data class FaultEvent(val at: Long, val kind: String, val name: String, val file: String?)

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "monitor")
        val bundle = ctx.options.str("bundle")
        val explicitPid = ctx.options.str("pid")?.toIntOrNull()
        // 旁路观测不该因为「目标不在」就退出：首探等 3s，之后每轮再探一次。
        // 这样「启动即崩」（进程存活时间短于采样间隔）也能靠 faultlog 抓到。
        var pid: Int? = initialPid(hdc, explicitPid, bundle)

        val intervalMs = parseDurationMs(ctx.options.str("interval") ?: "10s")
        val durationMs = parseDurationMs(ctx.options.str("duration") ?: "30m")
        if (intervalMs < 500) throw OhdError(ExitCode.USAGE, "interval 不能小于 0.5s", "采样太密会自己抢占设备，污染你要观察的数据")
        val checkEvery = (ctx.options.str("check-every") ?: "2").toIntOrNull()?.coerceAtLeast(1) ?: 2

        // ---- 1. hilog：全程落盘（与 monkey 同一套 pump，行为一致）----
        val hilogPath = startHilog(ctx, hdc, session)

        // ---- 2. 起跑前先拍一次 faultlog 基线，避免把历史崩溃算进本轮 ----
        var known = Faults.list(session, hdc)
        val faults = mutableListOf<FaultEvent>()

        if (!ctx.out.jsonMode) {
            ctx.out.kv(
                listOf(
                    "PID" to (pid?.toString() ?: "未出现（每轮重试）"),
                    "应用" to (bundle ?: "-"),
                    "时长" to humanDuration(durationMs),
                    "采样间隔" to humanDuration(intervalMs),
                    "故障巡检" to "每 $checkEvery 轮",
                    "日志" to (hilogPath?.toAbsolutePath()?.toString() ?: "关闭"),
                    "动作注入" to "无（由外部工具驱动）",
                ),
            )
            println()
        }

        val samples = mutableListOf<Sample>()
        val deadline = System.currentTimeMillis() + durationMs
        val total = (durationMs / intervalMs).toInt().coerceAtLeast(1)
        var rounds = 0
        val csv = session?.nextPath("monitor", "csv")
        val csvWriter = csv?.let { p ->
            // 新列一律追加在末尾：老分析脚本按位读取时不会错位。
            java.nio.file.Files.newBufferedWriter(p, java.nio.charset.StandardCharsets.UTF_8).also {
                it.write("index,time,pss_kb,cpu_percent,dma_bytes,dma_entries,alive,graph_kb,gl_kb,native_heap_kb\n")
                it.flush()
            }
        }

        try {
            for (i in 1..total) {
                if (System.currentTimeMillis() >= deadline) break
                val t0 = System.currentTimeMillis()
                rounds = i

                if (pid == null) pid = resolvePidOrNull(hdc, explicitPid, bundle)
                val cur = pid
                val memR = cur?.let { Hdcs.sh(session, hdc, PlatformCmds.memCommand(hdc.kind, it), 25_000) }
                val memRaw = memR?.takeIf { it.ok }?.stdout
                // 分类表在同一次 hidumper 输出里就有，不额外打设备：Graph/GL 才是 CMP 泄露的主信号，
                // 总 PSS 会被 GC / dalvik 抖动掩盖，单值看不出「是泄露还是正常驻留」。
                var pss = -1L
                var graphKb = -1L
                var glKb = -1L
                var nativeHeapKb = -1L
                if (memRaw != null) {
                    if (hdc.kind == DeviceKind.ANDROID) {
                        pss = AndroidParser.pssKb(memRaw) ?: -1L
                        runCatching {
                            val rep = AndroidParser.memReport(memRaw)
                            graphKb = (rep.row("Graph") ?: rep.row("Graphics"))?.pssKb ?: -1L
                            glKb = rep.row("GL")?.pssKb ?: -1L
                            nativeHeapKb = (rep.row("native heap") ?: rep.row("Native Heap"))?.pssKb ?: -1L
                        }
                    } else {
                        val rep = MemParser.parse(memRaw)
                        pss = rep.pssKb
                        graphKb = (rep.row("Graph") ?: rep.row("Graphics"))?.pssKb ?: -1L
                        glKb = rep.row("GL")?.pssKb ?: -1L
                        nativeHeapKb = (rep.row("native heap") ?: rep.row("Native Heap"))?.pssKb ?: -1L
                    }
                }
                val alive = memR?.ok == true
                val cpu = if (cur != null) runCatching {
                    val raw = Hdcs.sh(session, hdc, PlatformCmds.cpuCommand(hdc.kind, cur), 25_000).stdout
                    if (hdc.kind == DeviceKind.ANDROID) AndroidParser.cpuPercent(raw, cur) ?: 0.0
                    else CpuParser.parse(raw).usage?.total ?: 0.0
                }.getOrDefault(0.0) else 0.0

                var dmaBytes = -1L
                var dmaEntries = 0
                if (cur != null) runCatching {
                    val r = Hdcs.sh(session, hdc, PlatformCmds.dmaCommand(hdc.kind, cur), 20_000)
                    if (r.ok) {
                        val snap = DmabufParser.parse(r.text())
                        dmaBytes = snap.totalBytes
                        dmaEntries = snap.entries.size
                    }
                }

                val smp = Sample(
                    i, System.currentTimeMillis(), pss, cpu, dmaBytes, dmaEntries, alive,
                    graphKb = graphKb,
                    glKb = glKb,
                    nativeHeapKb = nativeHeapKb,
                )
                samples.add(smp)
                csvWriter?.run {
                    write(
                        "${smp.i},${isoTime(smp.ms)},${smp.pssKb},${"%.2f".format(smp.cpu)},${smp.dmaBytes}," +
                            "${smp.dmaEntries},${if (smp.alive) 1 else 0},${smp.graphKb},${smp.glKb},${smp.nativeHeapKb}\n",
                    )
                    flush()
                }
                if (!ctx.out.jsonMode) {
                    val dmaPart = if (dmaBytes >= 0) " dma=${humanBytes(dmaBytes)}" else ""
                    val pssPart = if (pss >= 0) "pss=${humanBytes(pss * 1024)}" else "pss=-"
                    ctx.out.info(
                        "[%d/%d] %s cpu=%.1f%%%s%s  (%dms)".format(
                            i, total, pssPart, cpu, dmaPart,
                            if (alive) "" else "  [进程未响应]",
                            System.currentTimeMillis() - t0,
                        ),
                    )
                }

                // ---- 3. 故障巡检：差分看有没有新崩溃 ----
                if (i % checkEvery == 0 || i == total) {
                    val latest = Faults.list(session, hdc)
                    val fresh = Faults.diff(known, latest)
                    if (fresh.isNotEmpty()) {
                        known = latest
                        fresh.forEach { c ->
                            val file = captureFault(c, hdc, session, bundle)
                            faults += FaultEvent(System.currentTimeMillis(), c.kind, c.raw, file)
                            if (!ctx.out.jsonMode) {
                                ctx.out.note("FAULT", "warn", "${c.kind} ${c.module} → ${file ?: "现场留存失败"}")
                            }
                        }
                    }
                }

                val remain = intervalMs - (System.currentTimeMillis() - t0)
                if (remain > 0 && i < total) Thread.sleep(minOf(remain, maxOf(0, deadline - System.currentTimeMillis())))
            }
        } finally {
            runCatching { csvWriter?.close() }
        }

        if (samples.none { it.alive } && faults.isEmpty()) {
            throw OhdError(
                ExitCode.ASSERT_FAILED,
                "全程未观测到进程：${bundle ?: "pid ${explicitPid ?: "-"}"}",
                "确认 bundle 正确；若应用一启动就崩，看 faultlog（ohd crash list）比采样更有用",
            )
        }

        // ---- 4. 采样表 ----
        if (csv != null) session.register(csv, "monitor-csv")

        val crashed = faults.any { it.kind.equals("cppcrash", true) || it.kind.equals("jscrash", true) }
        val died = samples.count { !it.alive }
        session?.verdict = if (crashed) "CRASH" else if (died > 0) "PROC_GONE" else "OK"

        val live = samples.filter { it.alive }
        val spanSec = (samples.last().ms - samples.first().ms) / 1000.0
        val pssFirst = live.firstOrNull()?.pssKb ?: -1L
        val pssLast = live.lastOrNull()?.pssKb ?: -1L
        val peak = live.maxOfOrNull { it.pssKb } ?: -1L
        val slope = if (live.size >= 2 && spanSec > 0) (pssLast - pssFirst) / 1024.0 / spanSec else 0.0
        val cpuAvg = live.map { it.cpu }.average().let { if (it.isNaN()) 0.0 else it }
        val cpuPeak = live.maxOfOrNull { it.cpu } ?: 0.0
        val graphSeries = live.map { it.graphKb }.filter { it >= 0 }
        val glSeries = live.map { it.glKb }.filter { it >= 0 }
        val graphPeak = graphSeries.maxOrNull() ?: -1L
        val glPeak = glSeries.maxOrNull() ?: -1L
        val graphSlope = if (graphSeries.size >= 2 && spanSec > 0) {
            (graphSeries.last() - graphSeries.first()) / 1024.0 / spanSec
        } else 0.0

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-monitor/1")
                put("pid", pid ?: -1)
                put("samples", samples.size)
                put("spanSec", spanSec)
                put("pssFirstKb", pssFirst)
                put("pssLastKb", pssLast)
                put("pssPeakKb", peak)
                put("pssSlopeMbPerSec", slope)
                put("cpuAvg", cpuAvg)
                put("cpuPeak", cpuPeak)
                put("graphPeakKb", graphPeak)
                put("graphSlopeMbPerSec", graphSlope)
                put("glPeakKb", glPeak)
                put("aliveRounds", samples.count { it.alive })
                put("deadRounds", died)
                put("verdict", if (crashed) "CRASH" else if (died > 0) "PROC_GONE" else "OK")
                put("hilog", hilogPath?.toAbsolutePath()?.toString() ?: "")
                put("csv", csv?.toAbsolutePath()?.toString() ?: "")
                put("faults", buildJsonArray {
                    faults.forEach { f ->
                        add(buildJsonObject {
                            put("at", isoTime(f.at)); put("kind", f.kind); put("name", f.name); put("file", f.file ?: "")
                        })
                    }
                })
                put("series", buildJsonArray {
                    samples.forEach { s ->
                        add(buildJsonObject {
                            put("i", s.i); put("time", isoTime(s.ms)); put("pssKb", s.pssKb)
                            put("cpu", s.cpu); put("dmaBytes", s.dmaBytes); put("alive", s.alive)
                            put("graphKb", s.graphKb); put("glKb", s.glKb); put("nativeHeapKb", s.nativeHeapKb)
                        })
                    }
                })
            }
            ctx.out.finish("monitor", true, data, System.currentTimeMillis() - started)
            return if (crashed) ExitCode.ASSERT_FAILED else ExitCode.OK
        }

        println()
        ctx.out.kv(
            listOf(
                "轮次" to "$rounds / ${"%.1f".format(spanSec)}s",
                "PSS" to if (pssFirst < 0) "未采到（进程存活时间短于采样间隔）"
                        else "${humanBytes(pssFirst * 1024)} → ${humanBytes(pssLast * 1024)}（峰 ${humanBytes(peak * 1024)}）",
                "斜率" to if (live.size >= 2) "%+.3f MB/s".format(slope) else "样本不足",
                "Graph" to if (graphSeries.isEmpty()) "该设备无此项"
                        else "${humanBytes(graphSeries.first() * 1024)} → ${humanBytes(graphSeries.last() * 1024)}" +
                            "（峰 ${humanBytes(graphPeak * 1024)}，${"%+.3f".format(graphSlope)} MB/s）",
                "GL" to if (glSeries.isEmpty()) "该设备无此项" else "峰 ${humanBytes(glPeak * 1024)}",
                "CPU" to "均值 %.1f%% / 峰 %.1f%%".format(cpuAvg, cpuPeak),
                "进程" to when {
                    live.isEmpty() -> "全程未出现"
                    died == 0 -> "全程存活"
                    else -> "$died 轮无响应"
                },
                "崩溃" to if (faults.isEmpty()) "无新增" else "${faults.size} 次",
                "日志" to (hilogPath?.toAbsolutePath()?.toString() ?: "-"),
                "采样表" to (csv?.toAbsolutePath()?.toString() ?: "-"),
            ),
        )
        faults.forEach { f -> ctx.out.info("  • ${isoTime(f.at)}  ${f.kind}  ${f.name}") }
        println()
        if (crashed) {
            ctx.out.note("CRASH_DETECTED", "warn", "本轮观测到崩溃；动作不是 ohd 施加的，复现路径请对照外部驱动工具的操作序列")
            return ExitCode.ASSERT_FAILED
        }
        if (died > 0) {
            ctx.out.note("PROC_GONE", "warn", "进程中途无响应/退出；先确认外部工具是否主动 kill，再判是否异常")
        }
        return ExitCode.OK
    }

    /** 全程 hilog：与 monkey 同参数语义，默认全量原样（compat 只做字段精简，不丢行）。 */
    /** 解析 pid：失败返回 null（旁路观测不该因目标不在而退出）。 */
    private fun resolvePidOrNull(hdc: Hdc, explicit: Int?, bundle: String?): Int? =
        runCatching { DeviceOps.resolvePid(hdc, explicit, bundle) }.getOrNull()

    /** 首探：显式 pid 直接判；否则等最多 3s 给「刚拉起」的应用一点时间。 */
    private fun initialPid(hdc: Hdc, explicit: Int?, bundle: String?): Int? {
        if (explicit != null) return resolvePidOrNull(hdc, explicit, bundle)
        repeat(6) {
            resolvePidOrNull(hdc, null, bundle)?.let { return it }
            Thread.sleep(500)
        }
        return null
    }

    private fun startHilog(ctx: RunContext, hdc: Hdc, session: Session?): java.nio.file.Path? {
        if (ctx.options.bool("no-hilog")) return null
        val maxBytes = try {
            LogWatchdog.parseSize(ctx.options.str("max-size", "0"))
        } catch (_: IllegalArgumentException) {
            throw OhdError(ExitCode.USAGE, "参数 --max-size 无法解析：${ctx.options.str("max-size")}", "例如 --max-size 2G，0 表示不限")
        }
        val compress = logCompressOf(ctx)
        val f = session?.nextPath("hilog", logFileSuffix(compress)) ?: return null
        val tags = HilogStream.parseTags(ctx.options.str("tag"))
        val match = ctx.options.str("match")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrElse { throw OhdError(ExitCode.USAGE, "参数 --match 不是合法正则：$it") }
        }
        val exclude = ctx.options.str("exclude")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrElse { throw OhdError(ExitCode.USAGE, "参数 --exclude 不是合法正则：$it") }
        }
        val compat = !ctx.options.bool("full")
        val proc = runCatching {
            HilogStream.pump(hdc.shellArgv(PlatformCmds.logCommand(hdc.kind, tags)), logSink(f, compress), match, exclude, compat)
        }.getOrNull()
        if (proc == null) {
            ctx.out.note("HILOG_OFF", "warn", "日志流启动失败，仅保留采样与崩溃现场")
            return null
        }
        LogWatchdog.spawn(proc.pid(), f, maxBytes)
        session?.register(f, "hilog")
        Runtime.getRuntime().addShutdownHook(Thread { ProcessTrees.kill(proc.pid()) })
        ctx.out.note(
            "HILOG_ON", "info",
            "全程日志 → $f（${if (tags.isEmpty()) "全部 tag" else "tag: ${tags.joinToString(",")}"}，上限 ${LogWatchdog.format(maxBytes)}）",
        )
        return f
    }

    /** 崩溃现场：只抓 faultlog（崩溃瞬间界面通常已白屏，截图价值低）。 */
    private fun captureFault(c: Faults.Entry, hdc: Hdc, session: Session?, bundle: String?): String? {
        var path: String? = null
        runCatching {
            // Android 的 crash buffer 本身就是全文，无二次拉取：直接取尾部 500 行
            val detailCmd = PlatformCmds.crashDetailCommand(hdc.kind, c.raw)
            val r = if (detailCmd != null) Hdcs.sh(session, hdc, detailCmd, 60_000)
                    else Hdcs.sh(session, hdc, "logcat -b crash -d -t 500", 60_000)
            if (r.ok && r.stdout.isNotBlank()) {
                session?.let { sx ->
                    val p = sx.nextPath("faultlog", "txt")
                    Session.writeText(p, r.stdout)
                    sx.register(p, "faultlog")
                    path = p.toAbsolutePath().toString()
                }
            }
        }
        // 再补一段崩溃前后的 hilog：动作由外部工具给，没有日志就还原不出它点了什么
        if (path != null && bundle != null) {
            runCatching {
                val r = Hdcs.sh(session, hdc, "hilog -x | grep '$bundle' | tail -n 500", 60_000)
                if (r.ok && r.stdout.isNotBlank()) {
                    session?.let { sx ->
                        val p = sx.nextPath("hilog-crash", "txt")
                        Session.writeText(p, r.stdout)
                        sx.register(p, "hilog")
                    }
                }
            }
        }
        return path
    }

    private fun humanDuration(ms: Long): String = when {
        ms >= 3_600_000 -> "%.1fh".format(ms / 3_600_000.0)
        ms >= 60_000 -> "%.1fm".format(ms / 60_000.0)
        ms >= 1000 -> "%.0fs".format(ms / 1000.0)
        else -> "${ms}ms"
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
        b >= 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
        b >= 1024 -> "%.1f kB".format(b / 1024.0)
        else -> "$b B"
    }
}
