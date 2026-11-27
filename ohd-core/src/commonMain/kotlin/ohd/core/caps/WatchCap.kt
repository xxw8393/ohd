package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.analysis.Trend
import ohd.core.isoTime
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parseDurationMs
import ohd.core.parsers.CpuParser
import ohd.core.parsers.MemParser
import kotlin.math.abs
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.fmt

data class Sample(
    val index: Int,
    val ts: Long,
    val pssKb: Long,
    val graphKb: Long,
    val glKb: Long,
    val cpuPercent: Double?,
)

/**
 * 持续跟踪：只做可复现的事实提取（斜率 / 阶梯 / 平台期），**不下泄漏结论**。
 * 判定阈值全给默认值但都可命令行覆盖，便于把「怎么算出来的」写进报告。
 */
class WatchCap : Capability {
    override val name = "watch"
    override val summary = "持续跟踪 Pss/Graph/GL/CPU：CSV + 斜率 + 阶梯 + 平台期"
    override val usage = "ohd watch [--pid N | --bundle b] [--interval 1s] [--duration 60s] [--count N] [--until-stable K] [--stable-eps 1024] [--metrics pss,graph,gl,cpu] [--json]"
    override val example = "ohd watch --bundle com.tencent.compose --interval 1s --duration 120s --until-stable 5"
    override val accepts: Set<String> = setOf("bundle", "count", "duration", "interval", "metrics", "pid", "stable-eps", "until-stable")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "watch")
        val pid = DeviceOps.resolvePid(hdc, ctx.options.str("pid")?.toIntOrNull(), ctx.options.str("bundle"))
        val metrics = (ctx.options.str("metrics") ?: "pss,graph,gl,cpu")
            .split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val interval = parseDurationMs(ctx.options.str("interval") ?: "1s")
        val duration = parseDurationMs(ctx.options.str("duration") ?: "60s")
        val maxCount = ctx.options.int("count", Int.MAX_VALUE)
        val untilStable = ctx.options.int("until-stable", 0)
        val stableEps = ctx.options.int("stable-eps", 1024).toLong()
        val wantCpu = metrics.contains("cpu")

        val samples = mutableListOf<Sample>()
        val deadline = Env.nowMillis() + duration
        var stableStreak = 0
        var failures = 0
        val csvFile = session?.nextPath("watch", "csv")
        val csvWriter = csvFile?.let { p ->
            fs.newWriter(p).also {
                it.write("index,time,pss_kb,graph_kb,gl_kb,cpu_percent\n")
                it.flush()
            }
        }

        try {
            while (Env.nowMillis() < deadline && samples.size < maxCount) {
                val tick = Env.nowMillis()
                val memR = Hdcs.sh(session, hdc, "hidumper --mem $pid", 25_000)
                if (!memR.ok) {
                    failures++
                    if (failures >= 3) throw OhdError(ExitCode.TIMEOUT, "连续 3 次采样失败：${memR.text().take(200)}")
                    val spent = Env.nowMillis() - tick
                    val sleep = interval - spent
                    if (sleep > 0 && Env.nowMillis() + sleep < deadline) Env.sleepMillis(sleep)
                    continue
                }
                failures = 0
                val mem = MemParser.parse(memR.stdout)
                val cpu = if (wantCpu) {
                    CpuParser.parse(Hdcs.sh(session, hdc, "hidumper --cpuusage $pid", 25_000).stdout).usage?.total
                } else null

                val s = Sample(samples.size, Env.nowMillis(), mem.pssKb, mem.graphKb, mem.glKb, cpu)
                samples += s
                csvWriter?.run {
                    write("${s.index},${isoTime(s.ts)},${s.pssKb},${s.graphKb},${s.glKb},${s.cpuPercent ?: ""}\n")
                    flush()
                }
                if (!ctx.out.jsonMode) {
                    ctx.out.note("", "info", fmt(
                        "[%02d] pss=%d KB  graph=%d KB  gl=%d KB  cpu=%s",
                        s.index + 1, s.pssKb, s.graphKb, s.glKb,
                        s.cpuPercent?.let { fmt("%.1f%%", it) } ?: "-",
                    ))
                }

                if (untilStable > 0 && samples.size > 1) {
                    val delta = abs(s.pssKb - samples[samples.size - 2].pssKb)
                    if (delta <= stableEps) stableStreak++ else stableStreak = 0
                    if (stableStreak >= untilStable) break
                }

                val spent = Env.nowMillis() - tick
                val sleep = interval - spent
                if (sleep > 0 && Env.nowMillis() + sleep < deadline) Env.sleepMillis(sleep)
            }
        } finally {
            runCatching { csvWriter?.close() }
        }

        if (samples.isEmpty()) throw OhdError(ExitCode.TIMEOUT, "没有采集到任何样本")

        // 三个终止条件（时长/次数/稳定）同时存在，必须说清是哪一个触发的，
        // 否则「只采了 3 个样本」会被误读成工具没好好跑
        val stopReason = when {
            untilStable > 0 && stableStreak >= untilStable -> "PSS 连续 $untilStable 次稳定（±$stableEps KB）"
            samples.size >= maxCount -> "达到 --count $maxCount"
            else -> "达到 --duration ${ctx.options.str("duration") ?: "60s"}"
        }

        val summary = summarize(samples, stableEps)
        // 与 soak 共用同一套形状判定：锯齿 ≠ 持续上升
        val spanSec = if (samples.size >= 2) (samples.last().ts - samples.first().ts) / 1000.0 else 0.0
        val trend = Trend.analyze(samples.map { it.pssKb }, spanSec)
        if (csvFile != null) session?.register(csvFile, "watch-csv")

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("pid", pid)
                put("samples", buildJsonArray {
                    samples.forEach { s ->
                        add(buildJsonObject {
                            put("index", s.index)
                            put("time", isoTime(s.ts))
                            put("pssKb", s.pssKb)
                            put("graphKb", s.graphKb)
                            put("glKb", s.glKb)
                            put("cpuPercent", s.cpuPercent ?: -1.0)
                        })
                    }
                })
                put("summary", buildJsonObject {
                    put("count", samples.size)
                    put("pssFirstKb", summary.first)
                    put("pssLastKb", summary.last)
                    put("pssMinKb", summary.min)
                    put("pssMaxKb", summary.max)
                    put("deltaKb", summary.delta)
                    put("slopeKbPerSec", summary.slopePerSec)
                    put("slopeKbPerMin", summary.slopePerSec * 60)
                    put("steps", summary.steps)
                    put("plateauCount", summary.plateau)
                    put("stableEpsKb", stableEps)
                })
                put("csv", csvFile?.toString() ?: "")
            }
            ctx.out.finish("watch", true, data, Env.nowMillis() - started)
        } else {
            println()
            ctx.out.table(
                listOf("指标", "首", "末", "最小", "最大", "Δ", "斜率/分"),
                listOf(
                    listOf(
                        "Pss(KB)", summary.first.toString(), summary.last.toString(),
                        summary.min.toString(), summary.max.toString(),
                        (if (summary.delta >= 0) "+" else "") + summary.delta,
                        fmt("%.1f", summary.slopePerSec * 60),
                    ),
                ),
            )
            println()
            ctx.out.kv(
                listOf(
                    "PID" to pid.toString(),
                    "样本数" to samples.size.toString(),
                    "停止原因" to stopReason,
                    "阶梯(>eps)" to "${summary.steps} 次",
                    "平台期探针" to "${summary.plateau} 次（|Δ|≤${stableEps}KB）",
                    "走势" to fmt("${trend.label}（基线 %+.1f MB，最大回撤 %.1f MB）", 
                        trend.baselineDriftKb / 1024.0, trend.maxDrawdownKb / 1024.0,
                    ),
                    "CSV" to (csvFile?.toString() ?: "-"),
                ),
            )
            if (trend.shape == Trend.Shape.SAWTOOTH) {
                ctx.out.info(
                    fmt("锯齿：段内上涨但被回收（最大回撤 %.1f MB），属正常分配/回收，不是泄漏", 
                        trend.maxDrawdownKb / 1024.0,
                    ),
                )
            } else if (trend.shape == Trend.Shape.BASELINE_RISE || summary.slopePerSec * 60 > 1024) {
                ctx.out.note(
                    "PSS_GROWTH",
                    "warn",
                    fmt("PSS 增长约 %.0f KB/分；是否为泄漏需结合业务路径与复现脚本判断，本工具不下结论", summary.slopePerSec * 60),
                )
            }
        }
        return ExitCode.OK
    }

    internal data class Summary(
        val first: Long,
        val last: Long,
        val min: Long,
        val max: Long,
        val delta: Long,
        val slopePerSec: Double,
        val steps: Int,
        val plateau: Int,
    )

    internal fun summarize(samples: List<Sample>, eps: Long): Summary {
        val pss = samples.map { it.pssKb }
        val n = pss.size
        val t0 = samples.first().ts
        val xs = samples.map { (it.ts - t0) / 1000.0 }
        val meanX = xs.average()
        val meanY = pss.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - meanX) * (pss[i] - meanY)
            den += (xs[i] - meanX) * (xs[i] - meanX)
        }
        val slope = if (den > 1e-9) num / den else 0.0
        var steps = 0
        var plateau = 0
        for (i in 1 until n) {
            val d = pss[i] - pss[i - 1]
            if (abs(d) > eps) steps++ else plateau++
        }
        return Summary(pss.first(), pss.last(), pss.min(), pss.max(), pss.last() - pss.first(), slope, steps, plateau)
    }
}
