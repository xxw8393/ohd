package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.DeviceOps
import ohd.core.DeviceKind
import ohd.core.parsers.AndroidCpuParser
import ohd.core.Hdcs
import ohd.core.RunContext
import ohd.core.parsers.CpuParser
import ohd.platform.Env
import ohd.platform.fmt

/** CPU：使用率（总/用户/内核/iowait）+ 进程 Top N + 逐核频率（识别降频/温控）。 */
class CpuCap : Capability {
    override val name = "cpu"
    override val summary = "CPU 使用率 / 进程 Top N / 逐核当前与最大频率"
    override val usage = "ohd cpu [--pid N | --bundle b] [--top N] [--cpufreq] [--json]"
    override val example = "ohd cpu --bundle com.tencent.compose --cpufreq --top 10"
    override val accepts: Set<String> = setOf("bundle", "cpufreq", "pid", "top")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "cpu")

        if (ctx.options.bool("cpufreq")) {
            val cmd = if (hdc.kind == DeviceKind.ANDROID) AndroidCpuParser.cpufreqCommand() else "hidumper --cpufreq"
            val r = Hdcs.sh(session, hdc, cmd, 20_000)
            Hdcs.requireOk(r, "cpu 频率")
            val cores = if (hdc.kind == DeviceKind.ANDROID) AndroidCpuParser.parseCpufreq(r.stdout)
                        else parseCpufreq(r.stdout)
            if (ctx.out.jsonMode) {
                val data = buildJsonObject {
                    put("cores", buildJsonArray {
                        cores.forEach { (core, pair) ->
                            add(buildJsonObject {
                                put("core", core)
                                put("curMhz", pair.first / 1000)
                                put("maxMhz", pair.second / 1000)
                            })
                        }
                    })
                }
                ctx.out.finish("cpu", cores.isNotEmpty(), data, Env.nowMillis() - started)
            } else {
                ctx.out.table(
                    listOf("核", "当前 MHz", "最大 MHz", "占比"),
                    cores.map { (core, pair) ->
                        val pct = if (pair.second > 0) fmt("%.0f%%", pair.first * 100.0 / pair.second) else "-"
                        listOf(core.toString(), (pair.first / 1000).toString(), (pair.second / 1000).toString(), pct)
                    },
                )
            }
            return if (cores.isEmpty()) ExitCode.UNSUPPORTED else ExitCode.OK
        }

        // 与 mem/watch/soak 保持一致：都接受 --bundle，不然同一件事每处参数名都不一样
        val pidArg = DeviceOps.resolvePid(
            hdc,
            ctx.options.str("pid")?.toIntOrNull(),
            ctx.options.str("bundle"),
        )
        val cmd = if (hdc.kind == DeviceKind.ANDROID) "top -b -n 1"
                  else "hidumper --cpuusage" + (pidArg?.let { " $it" } ?: "")
        val r = Hdcs.sh(session, hdc, cmd, 25_000)
        Hdcs.requireOk(r, "cpu 占用")
        val report = if (hdc.kind == DeviceKind.ANDROID) {
            // toybox top 不打 load average，单独读 /proc/loadavg
            val loads = runCatching {
                AndroidCpuParser.loadAvg(Hdcs.sh(session, hdc, "cat /proc/loadavg", 10_000).stdout)
            }.getOrDefault(Triple(0.0, 0.0, 0.0))
            AndroidCpuParser.parseTop(r.stdout, loads)
        } else {
            CpuParser.parse(r.stdout)
        }
        val top = ctx.options.int("top", 8)

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                report.usage?.let { u ->
                    put("usage", buildJsonObject {
                        put("total", u.total)
                        put("user", u.user)
                        put("kernel", u.kernel)
                        put("iowait", u.iowait)
                        put("irq", u.irq)
                        put("idle", u.idle)
                        put("load1", u.load1)
                        put("load5", u.load5)
                        put("load15", u.load15)
                        put("window", u.window ?: "")
                    })
                }
                put("processes", buildJsonArray {
                    report.processes.take(top).forEach { p ->
                        add(buildJsonObject {
                            put("pid", p.pid)
                            put("name", p.name)
                            put("totalPercent", p.totalPercent)
                            put("user", p.user)
                            put("kernel", p.kernel)
                        })
                    }
                })
            }
            ctx.out.finish("cpu", report.usage != null, data, Env.nowMillis() - started)
        } else {
            report.usage?.let { u ->
                ctx.out.kv(
                    listOf(
                        "总占用" to fmt("%.2f%%", u.total),
                        "用户/内核" to fmt("%.2f%% / %.2f%%", u.user, u.kernel),
                        "iowait/irq" to fmt("%.2f%% / %.2f%%", u.iowait, u.irq),
                        "idle" to fmt("%.2f%%", u.idle),
                        "load 1/5/15" to fmt("%.2f / %.2f / %.2f", u.load1, u.load5, u.load15),
                        "窗口" to (u.window ?: "-"),
                    ),
                )
                println()
            }
            ctx.out.table(
                listOf("PID", "进程", "总%", "用户%", "内核%"),
                report.processes.take(top).map {
                    listOf(it.pid.toString(), it.name, fmt("%.2f", it.totalPercent), fmt("%.2f", it.user), fmt("%.2f", it.kernel))
                },
            )
        }
        return if (report.usage == null) ExitCode.UNSUPPORTED else ExitCode.OK
    }

    /** cpufreq 输出是「cmd is: ...cpuN/cpufreq/cpuinfo_cur_freq + 空行 + 数值」，逐对解析。 */
    internal fun parseCpufreq(raw: String): List<Pair<Int, Pair<Long, Long>>> {
        val cur = mutableMapOf<Int, Long>()
        val max = mutableMapOf<Int, Long>()
        var pendingCore: Int? = null
        var pendingKind: String? = null
        val cmd = Regex("""cpu(\d+)/cpufreq/cpuinfo_(cur|max)_freq""")
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.startsWith("cmd is:")) {
                val m = cmd.find(t)
                pendingCore = m?.groupValues?.get(1)?.toIntOrNull()
                pendingKind = m?.groupValues?.get(2)
                continue
            }
            if (t.isEmpty()) continue
            val v = t.toLongOrNull() ?: continue
            val core = pendingCore ?: continue
            when (pendingKind) {
                "cur" -> cur[core] = v
                "max" -> max[core] = v
            }
            pendingCore = null
            pendingKind = null
        }
        return cur.keys.sorted().map { it to (cur[it]!! to (max[it] ?: 0L)) }
    }
}
