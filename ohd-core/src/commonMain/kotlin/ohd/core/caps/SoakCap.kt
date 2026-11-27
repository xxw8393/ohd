package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.analysis.Trend
import ohd.core.isoTime
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.DeviceOps
import ohd.core.actions.UiActions
import ohd.core.parsers.CpuParser
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.fmt

/**
 * 长稳：按固定间隔采样 PSS / CPU / dmabuf，可选每轮驱动一段 UI 操作。
 *
 * 与 `watch` 的分工：`watch` 是短时观察（秒级），`soak` 是长时值守（分钟级起），
 * 而且能在采样间隙**持续施加操作**——只在静止状态下测内存，测不出真正的泄漏。
 *
 * 判定边界：只报斜率/峰值/平台期，不判泄漏。泄漏需要「操作 N 轮后仍单调上升」这类
 * 业务语义，工具无法替你定义 N。
 */
class SoakCap : Capability {
    override val name = "soak"
    override val summary = "长稳值守：PSS/CPU/DMA 周期采样 + 可选操作驱动，输出趋势与 csv"
    override val usage = "ohd soak [--pid N | --bundle b] [--duration 10m] [--interval 30s] [--drive <脚本>] " +
        "[--home-settle 30s] [--no-home-settle] [--home-text a,b] [--no-dma] [--json]"
    override val example = "ohd soak --bundle com.tencent.compose --duration 10h --interval 60s --drive replay.ohd"
    override val accepts: Set<String> = setOf(
        "bundle", "drive", "duration", "interval", "no-dma", "pid",
        "home-settle", "no-home-settle", "home-text",
    )

    private data class Sample(
        val i: Int,
        val ms: Long,
        val pssKb: Long,
        val cpu: Double,
        val dmaBytes: Long,
        val dmaEntries: Int,
    )

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "soak")
        val pid = DeviceOps.resolvePid(hdc, ctx.options.str("pid")?.toIntOrNull(), ctx.options.str("bundle"))

        val intervalMs = parseMs(ctx.options.str("interval") ?: "30s")
        val durationMs = parseMs(ctx.options.str("duration") ?: "10m")
        if (intervalMs < 1000) throw OhdError(ExitCode.USAGE, "interval 不能小于 1s", "太频繁会拖慢设备并干扰被测进程")
        val withDma = !ctx.options.bool("no-dma")

        val drive = ctx.options.str("drive")?.let { p ->
            val path = OhdPath.of(p)
            if (!fs.isRegularFile(path)) throw OhdError(ExitCode.USAGE, "驱动脚本不存在：$p")
            val acts = try {
                UiActions.parseScript(fs.readString(path))
            } catch (e: UiActions.ScriptError) {
                throw OhdError(ExitCode.USAGE, "驱动脚本语法错误：${e.message}")
            }
            if (acts.isEmpty()) throw OhdError(ExitCode.USAGE, "驱动脚本里没有可执行动作")
            acts
        }

        val total = (durationMs / intervalMs).toInt().coerceAtLeast(1)
        if (!ctx.out.jsonMode) {
            ctx.out.kv(
                listOf(
                    "PID" to pid.toString(),
                    "时长" to humanDuration(durationMs),
                    "间隔" to humanDuration(intervalMs),
                    "轮次" to total.toString(),
                    "驱动" to (drive?.let { "${it.size} 步/轮" } ?: "无（静止观测）"),
                    "DMA" to if (withDma) "采样" else "跳过",
                ),
            )
            println()
        }

        val samples = mutableListOf<Sample>()
        val replay = ReplayCap()
        val deadline = Env.nowMillis() + durationMs
        val csv = session?.nextPath("soak", "csv")
        val csvWriter = csv?.let { p ->
            fs.newWriter(p).also {
                it.write("index,time,pss_kb,cpu_percent,dma_bytes,dma_entries\n")
                it.flush()
            }
        }

        try {
            for (i in 1..total) {
                if (Env.nowMillis() > deadline + intervalMs) break
                val t0 = Env.nowMillis()
                val memR = Hdcs.sh(session, hdc, "hidumper --mem $pid", 25_000)
                val pss = if (memR.ok) MemParser.parse(memR.stdout).pssKb else -1L
                if (pss <= 0) {
                    val remain = intervalMs - (Env.nowMillis() - t0)
                    if (remain > 0 && i < total) Env.sleepMillis(minOf(remain, maxOf(0, deadline - Env.nowMillis())))
                    continue
                }
                val cpu = runCatching {
                    CpuParser.parse(Hdcs.sh(session, hdc, "hidumper --cpuusage $pid", 25_000).stdout).usage?.total ?: 0.0
                }.getOrDefault(0.0)
                var dmaBytes = -1L
                var dmaEntries = 0
                if (withDma) {
                    runCatching {
                        val r = Hdcs.sh(session, hdc, "cat /proc/$pid/mm_dmabuf_info", 20_000)
                        if (r.ok) {
                            val snap = DmabufParser.parse(r.text())
                            dmaBytes = snap.totalBytes
                            dmaEntries = snap.entries.size
                        }
                    }
                }
                val smp = Sample(i, Env.nowMillis(), pss, cpu, dmaBytes, dmaEntries)
                samples.add(smp)
                csvWriter?.run {
                    write("${smp.i},${isoTime(smp.ms)},${smp.pssKb},${fmt("%.2f", smp.cpu)},${smp.dmaBytes},${smp.dmaEntries}\n")
                    flush()
                }

                if (!ctx.out.jsonMode) {
                    val dmaPart = if (withDma && dmaBytes >= 0) " dma=${humanBytes(dmaBytes)}" else ""
                    ctx.out.info(
                        fmt("[%d/%d] pss=%s cpu=%.1f%%%s  (%dms)", 
                            i, total, humanBytes(pss * 1024), cpu, dmaPart,
                            Env.nowMillis() - t0,
                        ),
                    )
                }

                // 采样后驱动一轮操作：只在静止状态下测内存，测不出真正泄漏
                drive?.forEach { a -> runCatching { replay.execute(ctx, hdc, session, a) } }

                val remain = intervalMs - (Env.nowMillis() - t0)
                if (remain > 0 && i < total) Env.sleepMillis(minOf(remain, maxOf(0, deadline - Env.nowMillis())))
            }

            // 收尾：回首页 + 静置（有 --drive 的 UI 自动化尤其需要稳态末点）
            val bundle = ctx.options.str("bundle")
            val homeSettleMs = when {
                ctx.options.bool("no-home-settle") -> 0L
                else -> parseMs(ctx.options.str("home-settle") ?: "30s")
            }
            if (homeSettleMs > 0 && !bundle.isNullOrBlank()) {
                val markers = (ctx.options.str("home-text")
                    ?: "多实例测试,Compose - Skia,Start Second Ability")
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (!ctx.out.jsonMode) {
                    ctx.out.info("收尾：回首页并静置 ${humanDuration(homeSettleMs)} …")
                }
                DeviceOps.returnHomeAndSettle(
                    hdc = hdc,
                    session = session,
                    bundle = bundle,
                    settleMs = homeSettleMs,
                    homeTexts = markers,
                    log = { msg -> if (!ctx.out.jsonMode) ctx.out.info(msg) },
                )
                // 静置后再采一条稳态样本
                runCatching {
                    val livePid = DeviceOps.resolvePid(hdc, null, bundle)
                    val memR = Hdcs.sh(session, hdc, "hidumper --mem $livePid", 25_000)
                    val pss = if (memR.ok) MemParser.parse(memR.stdout).pssKb else -1L
                    if (pss > 0) {
                        var dmaBytes = -1L
                        var dmaEntries = 0
                        if (withDma) {
                            runCatching {
                                val r = Hdcs.sh(session, hdc, "cat /proc/$livePid/mm_dmabuf_info", 20_000)
                                if (r.ok) {
                                    val snap = DmabufParser.parse(r.text())
                                    dmaBytes = snap.totalBytes
                                    dmaEntries = snap.entries.size
                                }
                            }
                        }
                        val smp = Sample(samples.size + 1, Env.nowMillis(), pss, 0.0, dmaBytes, dmaEntries)
                        samples.add(smp)
                        csvWriter?.run {
                            write("${smp.i},${isoTime(smp.ms)},${smp.pssKb},${fmt("%.2f", smp.cpu)},${smp.dmaBytes},${smp.dmaEntries}\n")
                            flush()
                        }
                    }
                }
            }
        } finally {
            runCatching { csvWriter?.close() }
        }

        if (samples.isEmpty()) throw OhdError(ExitCode.ASSERT_FAILED, "没有采集到任何样本")
        if (csv != null) session.register(csv, "watch-csv")

        val spanSec = (samples.last().ms - samples.first().ms) / 1000.0
        val pssFirst = samples.first().pssKb
        val pssLast = samples.last().pssKb
        val slope = if (spanSec > 0) (pssLast - pssFirst) / 1024.0 / spanSec else 0.0
        val peak = samples.maxOf { it.pssKb }
        val trough = samples.minOf { it.pssKb }
        val growing = samples.zipWithNext().count { (a, b) -> b.pssKb > a.pssKb }
        val dmaSamples = samples.filter { it.dmaBytes >= 0 }
        val dmaDelta = if (dmaSamples.size >= 2) dmaSamples.last().dmaBytes - dmaSamples.first().dmaBytes else 0L

        // 用分段谷值判形状：整体斜率会把「段内涨、段间回收」的锯齿误报成持续上升
        val trend = Trend.analyze(samples.map { it.pssKb }, spanSec)
        val shape = trend.label

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-soak/1")
                put("pid", pid)
                put("samples", samples.size)
                put("spanSec", spanSec)
                put("pssFirstKb", pssFirst)
                put("pssLastKb", pssLast)
                put("pssPeakKb", peak)
                put("pssTroughKb", trough)
                put("pssSlopeMbPerSec", slope)
                put("shape", shape)
                put("baselineDriftKb", trend.baselineDriftKb)
                put("maxDrawdownKb", trend.maxDrawdownKb)
                put("valleysKb", buildJsonArray { trend.valleysKb.forEach { add(JsonPrimitive(it)) } })
                put("grownSteps", growing)
                put("cpuAvg", samples.map { it.cpu }.average())
                put("cpuPeak", samples.maxOf { it.cpu })
                put("dmaFirst", dmaSamples.firstOrNull()?.dmaBytes ?: -1)
                put("dmaLast", dmaSamples.lastOrNull()?.dmaBytes ?: -1)
                put("dmaDelta", dmaDelta)
                put("csv", csv?.toString() ?: "")
                put("series", buildJsonArray {
                    samples.forEach { s ->
                        add(buildJsonObject {
                            put("i", s.i); put("time", isoTime(s.ms)); put("pssKb", s.pssKb)
                            put("cpu", s.cpu); put("dmaBytes", s.dmaBytes)
                        })
                    }
                })
            }
            ctx.out.finish("soak", true, data, Env.nowMillis() - started)
            return ExitCode.OK
        }

        println()
        ctx.out.kv(
            listOf(
                "样本" to "${samples.size} 个 / ${fmt("%.1f", spanSec)}s",
                "PSS" to "${humanBytes(pssFirst * 1024)} → ${humanBytes(pssLast * 1024)}（峰 ${humanBytes(peak * 1024)} / 谷 ${humanBytes(trough * 1024)}）",
                "斜率" to fmt("%+.3f MB/s", slope),
                "走势" to fmt("$shape（${growing}/${samples.size - 1} 次上升，基线 %+.1f MB，最大回撤 %.1f MB）", 
                    trend.baselineDriftKb / 1024.0, trend.maxDrawdownKb / 1024.0,
                ),
                "CPU" to fmt("均值 %.1f%% / 峰 %.1f%%", samples.map { it.cpu }.average(), samples.maxOf { it.cpu }),
                "DMA" to if (dmaSamples.size < 2) "-"
                else "${humanBytes(dmaSamples.first().dmaBytes)} → ${humanBytes(dmaSamples.last().dmaBytes)}（${if (dmaDelta >= 0) "+" else ""}${humanBytes(dmaDelta)}）",
                "采样文件" to (csv?.toAbsolutePath()?.toString() ?: "-"),
            ),
        )
        println()
        when (trend.shape) {
            Trend.Shape.BASELINE_RISE ->
                ctx.out.note(
                    "PSS_BASELINE_RISE",
                    "warn",
                    fmt("分段谷值抬升 %.1f MB，不构成泄漏结论；需更长窗口或重复操作后仍抬升才值得怀疑", 
                        trend.baselineDriftKb / 1024.0,
                    ),
                )
            Trend.Shape.SAWTOOTH ->
                ctx.out.info(
                    fmt("锯齿：段内上涨但被回收（最大回撤 %.1f MB），属正常分配/回收，不是泄漏", 
                        trend.maxDrawdownKb / 1024.0,
                    ),
                )
            Trend.Shape.RISING ->
                ctx.out.note(
                    "PSS_GROWTH",
                    "warn",
                    "采样窗口内 PSS 持续上升且无回收，不构成泄漏结论；需多轮操作后仍单调上升才值得怀疑",
                )
            Trend.Shape.INSUFFICIENT ->
                ctx.out.note("SAMPLE_TOO_SHORT", "warn", "样本不足 5 个，趋势判断不可靠，延长 --duration")
            else -> ctx.out.info("未见持续上升")
        }
        return ExitCode.OK
    }

    private fun parseMs(s: String): Long {
        val t = s.trim().lowercase()
        return when {
            t.endsWith("ms") -> t.dropLast(2).toLongOrNull() ?: 1000
            t.endsWith("s") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 1000).toLong()
            t.endsWith("m") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 60_000).toLong()
            t.endsWith("h") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 3_600_000).toLong()
            else -> t.toLongOrNull() ?: 1000
        }
    }

    private fun humanDuration(ms: Long): String = when {
        ms >= 3_600_000 -> fmt("%.1fh", ms / 3_600_000.0)
        ms >= 60_000 -> fmt("%.1fm", ms / 60_000.0)
        ms >= 1000 -> fmt("%.0fs", ms / 1000.0)
        else -> "${ms}ms"
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> fmt("%.2f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024 * 1024 -> fmt("%.1f MB", b / 1024.0 / 1024)
        b >= 1024 -> fmt("%.1f kB", b / 1024.0)
        else -> "$b B"
    }
}
