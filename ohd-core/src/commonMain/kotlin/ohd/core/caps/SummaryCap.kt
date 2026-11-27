package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parseDurationMs
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.TimeFmt
import ohd.platform.Env
import ohd.platform.fmt

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

    private companion object {
        /** 会话目录名的时间部分（设备侧时区生成）。 */
        const val dirFmt = "yyyyMMdd-HHmmss"

        /** 表格里「开始」列的形状。 */
        const val showFmt = "MM-dd HH:mm"

        /** 会话目录名按设备侧时区（东八区）生成，读回时必须用同一偏移，否则时间会漂。 */
        const val DEVICE_TZ_OFFSET_SECONDS = 28_800

        /** manifest 里 `TimeFmt.isoLocal` 产出的 ISO 形状（毫秒位按需拼接）。 */
    }

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
        val started = Env.nowMillis()
        val root = Session.defaultRoot(OhdPath.of("").toAbsolutePath())
        if (!fs.isDirectory(root)) {
            ctx.out.note("NO_SESSION", "warn", "还没有任何会话（$root）")
            return ExitCode.OK
        }

        val windowMs = ctx.options.str("since")?.let { parseDurationMs(it) } ?: 24 * 3600_000L
        val cutoff = Env.nowMillis() - windowMs
        val limit = ctx.options.int("limit", 30)

        val rows = mutableListOf<Row>()
        fs.newDirectoryStream(root).use { ds ->
            for (p in ds) {
                if (!fs.isDirectory(p)) continue
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
            ctx.out.finish("summary", true, data, Env.nowMillis() - started)
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

    private fun read(dir: OhdPath): Row? {
        val dirName = dir.fileName.toString()
        // 目录名：<yyyyMMdd-HHmmss>-<cap>
        val dash = dirName.indexOf('-', 9)
        if (dash < 0) return null
        val stamp = dirName.substring(0, dash)
        val cap = dirName.substring(dash + 1)
        // 会话目录名由**设备侧时区**生成，历史实现一律按 +0800 解读（硬编码是有意的）。
        // TimeFmt.parse 模式里带 Z 时只会吃掉 "+0800" 这 5 个字符、实际仍按**本机**偏移换算，
        // 在非东八区机器上会算错；因此这里去掉偏移文本，改用 parseWithOffset 显式钉死 28800，
        // 与旧版 OffsetDateTime.parse(stamp + "+0800") 完全等价。
        val epoch = TimeFmt.parseWithOffset(stamp, dirFmt, DEVICE_TZ_OFFSET_SECONDS) ?: return null

        // 只用来算差值，收 epoch 毫秒即可（旧版是两个 OffsetDateTime）。
        var startedIso: Long? = null
        var finishedIso: Long? = null
        var crashed = false
        var hasFaultlog = false
        var hasLog = false
        var verdict: String? = null

        val manifest = dir.resolve("manifest.json")
        if (fs.isRegularFile(manifest)) {
            runCatching {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(fs.readString(manifest)).jsonObject
                startedIso = manifestIso(obj, "startedAt")
                finishedIso = manifestIso(obj, "finishedAt")
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
        fs.newDirectoryStream(dir, "*samples.csv").use { ds ->
            val csv = ds.firstOrNull()
            if (csv != null) {
                runCatching {
                    val lines = fs.readAllLines(csv)
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
            fs.newDirectoryStream(dir, "*faultlog*").use { ds ->
                if (ds.any()) hasFaultlog = true
            }
        }
        if (!hasLog) {
            fs.newDirectoryStream(dir, "*hilog*").use { ds ->
                if (ds.any()) hasLog = true
            }
        }
        crashed = hasFaultlog || verdict == "CRASH"

        // 无 manifest 但有采样 → 上一次没跑完（中断/仍在跑）
        val running = finishedIso == null && startIsNull(manifest) == false
        val minutes = if (startedIso != null && finishedIso != null) {
            // 旧版 Duration.between(...).toMinutes()；minutesBetween 内部同为截断，结果一致
            TimeFmt.minutesBetween(startedIso, finishedIso).toLong()
        } else null

        return Row(
            session = dirName,
            capability = cap,
            epochMs = epoch,
            // 旧版是 when0.format(showFmt)：用目录名里那个 +0800 偏移来显示，
            // 所以这里必须继续钉死同一偏移，否则非东八区机器上显示会漂。
            startedAt = runCatching { TimeFmt.formatWithOffset(epoch, showFmt, DEVICE_TZ_OFFSET_SECONDS) }.getOrDefault(stamp),
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

    private fun startIsNull(manifest: OhdPath): Boolean = !fs.isRegularFile(manifest)

    /**
     * manifest 的 ISO-8601 时间戳 → epoch 毫秒。
     *
     * 产出方是 `TimeFmt.isoLocal`（见 Session.kt），形状 `yyyy-MM-ddTHH:mm:ss+0800`，
     * 有毫秒时是 `yyyy-MM-ddTHH:mm:ss.SSS+0800`；旧版本写的是 `+08:00`。
     * TimeFmt 不支持 `'T'` 引号转义（直接写 T 即可，T 不是格式符会原样匹配），
     * 也不支持可选段，所以按有没有小数点分两条模式。
     *
     * 偏移从字符串尾部自行解出后用 `parseWithOffset` 显式传入——
     * 这样与旧版 `OffsetDateTime.parse(it)` 完全等价（旧版也是尊重文本里的偏移，
     * 而不是本机时区），且能同时吃下 `+0800` 与 `+08:00` 两种写法：
     * TimeFmt 的模式里若用 `Z` 只能吃掉固定 5 个字符，带冒号的那种会解析失败。
     * 模式串不含偏移时，多余尾部字符会被忽略（解析只按模式推进），正好是我们想要的。
     */
    private fun manifestIso(obj: kotlinx.serialization.json.JsonObject, key: String): Long? =
        obj[key]?.jsonPrimitive?.contentOrNull?.let { TimeFmt.parseIso(it) }

    private fun pssSpan(r: Row): String {
        val a = r.pssFirst ?: return "-"
        val b = r.pssLast ?: return "-"
        return fmt("%.0f→%.0f MB", a / 1024.0, b / 1024.0)
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
