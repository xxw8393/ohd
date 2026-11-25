package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parseDurationMs
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 收网：把 ohd-evidence/device 下的历次会话拉成一张表。
 *
 * 压测一开一晚上，第二天要一眼看出「哪轮崩了、崩在第几步、内存是涨是跌、证据齐不齐」，
 * 而不是逐个目录翻 manifest 和 CSV。
 *
 * 只读磁盘，不碰设备——查历史不该占用真机。
 */
class SummaryCap : Capability {
    override val name = "summary"
    override val summary = "收网：历次会话一张表——哪轮崩了、内存怎么走、证据齐不齐"
    override val usage = "ohd summary [--since 24h] [--limit 30] [--json]"
    override val example = "ohd summary --since 24h --limit 50"
    override val accepts: Set<String> = setOf("since", "limit", "json")

    private val dirFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val showFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private data class Row(
        val session: String,
        val capability: String,
        val epochMs: Long,
        val startedAt: String,
        val minutes: Long?,
        val steps: Int?,
        val pssFirst: Long?,
        val pssLast: Long?,
        val samples: Int,
        val crashed: Boolean,
        val hasFaultlog: Boolean,
        val hasLog: Boolean,
        val running: Boolean,
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val root = Session.defaultRoot(Paths.get("").toAbsolutePath())
        if (!Files.isDirectory(root)) {
            ctx.out.note("NO_SESSION", "warn", "还没有任何会话（$root）")
            return ExitCode.OK
        }

        val windowMs = ctx.options.str("since")?.let { parseDurationMs(it) } ?: 24 * 3600_000L
        val cutoff = System.currentTimeMillis() - windowMs
        val limit = ctx.options.int("limit", 30)

        val rows = mutableListOf<Row>()
        Files.newDirectoryStream(root).use { ds ->
            for (p in ds) {
                if (!Files.isDirectory(p)) continue
                val r = read(p) ?: continue
                if (r.epochMs >= cutoff) rows += r
            }
        }
        val picked = rows.sortedByDescending { it.epochMs }.take(limit)

        if (picked.isEmpty()) {
            ctx.out.note("NO_SESSION", "warn", "最近 ${windowMs / 3600_000L} 小时内没有会话")
            return ExitCode.OK
        }

        val crashed = picked.count { it.crashed }
        val unfinished = picked.count { it.running }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-summary/1")
                put("windowHours", windowMs / 3600_000L)
                put("crashed", crashed)
                put("running", unfinished)
                put("sessions", buildJsonArray {
                    picked.forEach { r ->
                        add(buildJsonObject {
                            put("session", r.session)
                            put("capability", r.capability)
                            put("startedAt", r.startedAt)
                            put("minutes", r.minutes ?: -1)
                            put("steps", r.steps ?: -1)
                            put("pssFirstKb", r.pssFirst ?: -1)
                            put("pssLastKb", r.pssLast ?: -1)
                            put("samples", r.samples)
                            put("crashed", r.crashed)
                            put("hasFaultlog", r.hasFaultlog)
                            put("hasLog", r.hasLog)
                            put("running", r.running)
                        })
                    }
                })
            }
            ctx.out.finish("summary", true, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }

        ctx.out.table(
            listOf("会话", "类型", "开始", "时长", "步数", "崩溃", "PSS 首末", "采样", "证据"),
            picked.map { r ->
                listOf(
                    r.session,
                    r.capability,
                    r.startedAt,
                    if (r.running) "进行中" else (r.minutes?.let { "${it}min" } ?: "-"),
                    r.steps?.toString() ?: "-",
                    if (r.crashed) "✗ 崩了" else "-",
                    pssSpan(r),
                    r.samples.toString(),
                    evidence(r),
                )
            },
        )
        ctx.out.info("")
        ctx.out.info("窗口 ${windowMs / 3600_000L}h 内 ${picked.size} 个会话，崩溃 $crashed 个" +
            (if (unfinished > 0) "，$unfinished 个未收尾（可能是上次中断的）" else "") + "。")
        if (crashed > 0) {
            val first = picked.first { it.crashed }
            ctx.out.info("崩溃会话直接打包：ohd bundle --session ${Session.EVIDENCE_DIR}/device/${first.session}")
        }
        return ExitCode.OK
    }

    private fun read(dir: Path): Row? {
        val dirName = dir.fileName.toString()
        // 目录名：<yyyyMMdd-HHmmss>-<cap>
        val dash = dirName.indexOf('-', 9)
        if (dash < 0) return null
        val stamp = dirName.substring(0, dash)
        val cap = dirName.substring(dash + 1)
        val when0 = runCatching { OffsetDateTime.parse(stamp + "+0800", DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssZ")) }
            .getOrElse { return null }
        val epoch = when0.toInstant().toEpochMilli()

        var startedIso: OffsetDateTime? = null
        var finishedIso: OffsetDateTime? = null
        var crashed = false
        var hasFaultlog = false
        var hasLog = false
        var verdict: String? = null

        val manifest = dir.resolve("manifest.json")
        if (Files.isRegularFile(manifest)) {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(manifest)).jsonObject
                obj["startedAt"]?.jsonPrimitive?.content?.let { startedIso = OffsetDateTime.parse(it) }
                obj["finishedAt"]?.jsonPrimitive?.content?.let { finishedIso = OffsetDateTime.parse(it) }
                obj["verdict"]?.jsonPrimitive?.content?.let { verdict = it }
                (obj["artifacts"] as? kotlinx.serialization.json.JsonArray)?.forEach { a ->
                    when (a.jsonObject["kind"]?.jsonPrimitive?.content) {
                        "faultlog" -> hasFaultlog = true
                        "hilog" -> hasLog = true
                    }
                }
            }
        }

        var steps: Int? = null
        var pssFirst: Long? = null
        var pssLast: Long? = null
        var samples = 0
        Files.newDirectoryStream(dir, "*samples.csv").use { ds ->
            val csv = ds.firstOrNull()
            if (csv != null) {
                runCatching {
                    val lines = Files.readAllLines(csv)
                    // index,time,pss_kb,dma_bytes
                    val body = lines.drop(1).filter { it.isNotBlank() }
                    samples = body.size
                    body.firstOrNull()?.split(',')?.let { cs ->
                        steps = cs.getOrNull(0)?.trim()?.toIntOrNull()
                        pssFirst = cs.getOrNull(2)?.trim()?.toLongOrNull()
                    }
                    body.lastOrNull()?.split(',')?.let { cs ->
                        cs.getOrNull(0)?.trim()?.toIntOrNull()?.let { steps = it }
                        cs.getOrNull(2)?.trim()?.toLongOrNull()?.let { pssLast = it }
                    }
                }
            }
        }

        if (!hasFaultlog) {
            Files.newDirectoryStream(dir, "*faultlog*").use { ds ->
                if (ds.any()) hasFaultlog = true
            }
        }
        if (!hasLog) {
            Files.newDirectoryStream(dir, "*hilog*").use { ds ->
                if (ds.any()) hasLog = true
            }
        }
        crashed = hasFaultlog || verdict == "CRASH"

        // 无 manifest 但有采样 → 上一次没跑完（中断/仍在跑）
        val running = finishedIso == null && startIsNull(manifest) == false
        val minutes = if (startedIso != null && finishedIso != null) {
            Duration.between(startedIso, finishedIso).toMinutes()
        } else null

        return Row(
            session = dirName,
            capability = cap,
            epochMs = epoch,
            startedAt = runCatching { when0.format(showFmt) }.getOrDefault(stamp),
            minutes = minutes,
            steps = steps,
            pssFirst = pssFirst,
            pssLast = pssLast,
            samples = samples,
            crashed = crashed,
            hasFaultlog = hasFaultlog,
            hasLog = hasLog,
            running = running && samples > 0,
        )
    }

    private fun startIsNull(manifest: Path): Boolean = !Files.isRegularFile(manifest)

    private fun pssSpan(r: Row): String {
        val a = r.pssFirst ?: return "-"
        val b = r.pssLast ?: return "-"
        return "%.0f→%.0f MB".format(a / 1024.0, b / 1024.0)
    }

    private fun evidence(r: Row): String = buildString {
        if (r.hasFaultlog) append("堆栈")
        if (r.hasLog) {
            if (isNotEmpty()) append("+")
            append("日志")
        }
        if (isEmpty()) append("-")
    }
}
