package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.OhdError
import ohd.core.parsers.MemRow
import ohd.core.DeviceKind
import ohd.core.PlatformCmds
import ohd.core.parsers.AndroidParser
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.MemParser
import ohd.platform.ProcSpec
import ohd.platform.runProcess
import ohd.platform.Env
import ohd.platform.fmt

/** 看 Pss：按分类表解析（GL / Graph / ark ts heap / native heap / .so …）。 */
private val IOS_MEM_ROW_RE = Regex("""^\s*(\d+)\s+(KB|MB|GB)\s+.*?\s{2,}([A-Za-z][\w\s]*)$""")

class MemCap : Capability {
    override val name = "mem"
    override val summary = "进程内存明细（Pss / SwapPss / GL / Graph / native heap）"
    override val usage = "ohd mem [--pid N | --bundle <包名>] [--top N] [--show-dmabuf] [--json]"
    override val example = "ohd mem --bundle com.tencent.compose --show-dmabuf --top 15 --json"
    override val accepts: Set<String> = setOf("bundle", "pid", "show-dmabuf", "top")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "mem")
        val pid = DeviceOps.resolvePid(hdc, ctx.options.str("pid")?.toIntOrNull(), ctx.options.str("bundle"))
        // iOS 模拟器内没有 ps/dumpsys，内存只能由宿主 footprint 采样
        if (hdc.kind == DeviceKind.IOS) return iosMem(ctx, pid, session, started)
        // --show-dmabuf 是 OHOS 专有开关；Android 的缓冲分段由 dumpsys 自带
        val extra = if (ctx.options.bool("show-dmabuf") && hdc.kind == DeviceKind.OHOS) " --show-dmabuf" else ""
        val r = Hdcs.sh(session, hdc, PlatformCmds.memCommand(hdc.kind, pid) + extra, 30_000)
        Hdcs.requireOk(r, "内存查询")
        val report = if (hdc.kind == DeviceKind.ANDROID) AndroidParser.memReport(r.stdout) else MemParser.parse(r.stdout)
        val top = ctx.options.int("top", 20)

        if (session != null) {
            val p = session.nextPath("mem", "txt")
            Session.writeText(p, r.stdout)
            session.register(p, "mem-raw")
        }

        val rows = report.rows.sortedByDescending { it.pssKb }.take(top)
        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("pid", pid)
                put("pssKb", report.pssKb)
                put("swapPssKb", report.swapPssKb)
                put("glKb", report.glKb)
                put("graphKb", report.graphKb)
                put("nativeHeapKb", report.nativeHeapKb)
                put("categories", buildJsonArray {
                    report.rows.forEach { row ->
                        add(buildJsonObject {
                            put("name", row.name)
                            put("pssKb", row.pssKb)
                            put("swapPssKb", row.swapPssKb)
                        })
                    }
                })
            }
            ctx.out.finish("mem", report.total != null, data, Env.nowMillis() - started)
        } else {
            ctx.out.table(
                listOf("分类", "Pss(KB)", "SwapPss(KB)", "占比"),
                rows.map { row ->
                    val pct = if (report.pssKb > 0) fmt("%.1f%%", row.pssKb * 100.0 / report.pssKb) else "-"
                    listOf(row.name, row.pssKb.toString(), row.swapPssKb.toString(), pct)
                },
            )
            println()
            ctx.out.kv(
                listOf(
                    "PID" to pid.toString(),
                    "Pss 合计" to "${report.pssKb} KB",
                    "SwapPss" to "${report.swapPssKb} KB",
                    "GL" to "${report.glKb} KB",
                    "Graph" to "${report.graphKb} KB",
                    "native heap" to "${report.nativeHeapKb} KB",
                ),
            )
        }
        return if (report.total == null) ExitCode.UNSUPPORTED else ExitCode.OK
    }

    /**
     * iOS：调宿主 `footprint -p <pid>`。
     *
     * 模拟器进程本来就是宿主进程，footprint 能给出 Footprint 总量与各分配器分段；
     * 但 **CPU / GL / Graph / SwapPss 拿不到**，字段留空而不是填 0 假装采到。
     */
    private fun iosMem(ctx: RunContext, pid: Int, session: Session?, started: Long): Int {
        // 旧实现 redirectErrorStream(true) + waitFor(30s)：诊断信息与数据混在一起，只能整段读。
        val r = runProcess(
            ProcSpec(
                command = listOf("footprint", "-p", pid.toString()),
                timeoutMillis = 30_000,
            ),
        )
        if (r.spawnError != null) {
            throw OhdError(ExitCode.UNSUPPORTED, "无法执行 footprint：${r.spawnError}", "footprint 随 macOS 提供，确认在 /usr/bin/footprint")
        }
        val raw = r.stdout + r.stderr

        if (session != null) {
            val path = session.nextPath("mem", "txt")
            Session.writeText(path, raw)
            session.register(path, "mem-raw")
        }

        val totalKb = Regex("""Footprint:\s*(\d+)\s*(KB|MB|GB)""").find(raw)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: 0
            when (m.groupValues[2]) {
                "GB" -> n * 1024 * 1024
                "MB" -> n * 1024
                else -> n
            }
        }
        val rows = raw.lines().mapNotNull { line ->
            val m = IOS_MEM_ROW_RE.find(line) ?: return@mapNotNull null
            val n = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val kb = when (m.groupValues[2]) {
                "GB" -> n * 1024 * 1024
                "MB" -> n * 1024
                else -> n
            }
            MemRow(m.groupValues[3].trim(), kb, 0, emptyList())
        }

        val top = ctx.options.int("top", 20)
        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("pid", pid)
                totalKb?.let { put("pssKb", it) }
                put("categories", buildJsonArray {
                    rows.sortedByDescending { it.pssKb }.take(top).forEach { row ->
                        add(buildJsonObject { put("name", row.name); put("pssKb", row.pssKb) })
                    }
                })
            }
            ctx.out.finish("mem", totalKb != null, data, Env.nowMillis() - started)
        } else {
            ctx.out.table(
                listOf("分类", "占用(KB)", "占比"),
                rows.sortedByDescending { it.pssKb }.take(top).map { row ->
                    val pct = if ((totalKb ?: 0) > 0) fmt("%.1f%%", row.pssKb * 100.0 / totalKb!!) else "-"
                    listOf(row.name, row.pssKb.toString(), pct)
                },
            )
            println()
            ctx.out.kv(
                listOf(
                    "PID" to pid.toString(),
                    "Footprint" to (totalKb?.let { "$it KB" } ?: "-"),
                    "SwapPss" to "-（iOS 模拟器无此口径）",
                    "GL / Graph" to "-（iOS 模拟器无此口径）",
                ),
            )
        }
        return if (totalKb == null) ExitCode.UNSUPPORTED else ExitCode.OK
    }
}
