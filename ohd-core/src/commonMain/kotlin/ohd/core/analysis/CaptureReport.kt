package ohd.core.analysis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.TimeFmt

/**
 * 读取 automation-captures 目录（REPORT.md / meta.json / anomalies.log / dmabuf_series.csv）。
 * 也接受仅含 anomalies.log 的目录。
 */
object CaptureReport {

    private val json = Json { ignoreUnknownKeys = true }

    /** meta.json `started_at` / 报告表格「开始/结束」两处都是这个形状。 */
    private const val TS_SECONDS = "yyyy-MM-dd HH:mm:ss"

    data class Snapshot(
        val dir: OhdPath,
        val bundle: String?,
        val durationMin: Double?,
        val info: Int,
        val warn: Int,
        val crit: Int,
        val peakTotalMb: Double?,
        val peakGraphMb: Double?,
        val kotlinCount: Int?,
        val nativeRemaining: Int?,
        val anomalyLines: List<String>,
        val pixelMapLadder: Boolean,
        val xcomponentFalling: Boolean,
        val xcomponentRising: Boolean,
    ) {
        val endRegistryAligned: Boolean?
            get() {
                val k = kotlinCount ?: return null
                val n = nativeRemaining ?: return null
                return k == n
            }
    }

    fun load(dir: OhdPath): Snapshot {
        if (!fs.isDirectory(dir)) {
            throw IllegalArgumentException("目录不存在：$dir")
        }
        val meta = dir.resolve("meta.json").takeIf { fs.isRegularFile(it) }
            ?.let { runCatching { json.parseToJsonElement(fs.readString(it)) as? JsonObject }.getOrNull() }
        val anomalies = dir.resolve("anomalies.log").takeIf { fs.isRegularFile(it) }
            ?.let { fs.readAllLines(it) }
            .orEmpty()

        val bundle = meta?.get("bundle")?.jsonPrimitive?.contentOrNull
        val info = meta?.get("info_count")?.jsonPrimitive?.intOrNull ?: 0
        val warn = meta?.get("warn_count")?.jsonPrimitive?.intOrNull ?: countSev(anomalies, "warn")
        val crit = meta?.get("crit_count")?.jsonPrimitive?.intOrNull ?: countSev(anomalies, "crit")
        val peakTotal = meta?.get("peak_total_mb")?.jsonPrimitive?.doubleOrNull
        val peakGraph = meta?.get("peak_graph_mb")?.jsonPrimitive?.doubleOrNull
        val kotlin = meta?.get("kotlin_count")?.jsonPrimitive?.intOrNull
        val native = meta?.get("native_remaining")?.jsonPrimitive?.intOrNull

        val duration = durationMin(meta, dir.resolve("REPORT.md"))
        val series = parseDmabufSeries(dir.resolve("dmabuf_series.csv"))

        return Snapshot(
            dir = dir,
            bundle = bundle,
            durationMin = duration,
            info = info,
            warn = warn,
            crit = crit,
            peakTotalMb = peakTotal,
            peakGraphMb = peakGraph,
            kotlinCount = kotlin,
            nativeRemaining = native,
            anomalyLines = anomalies,
            pixelMapLadder = series.pixelMapRising,
            xcomponentFalling = series.xcomponentDeltaMb < -1.0,
            xcomponentRising = series.xcomponentDeltaMb > 5.0,
        )
    }

    private fun countSev(lines: List<String>, sev: String): Int =
        lines.count { it.split('\t').getOrNull(1)?.trim() == sev }

    private fun durationMin(meta: JsonObject?, report: OhdPath): Double? {
        val started = meta?.get("started_at")?.jsonPrimitive?.contentOrNull
        val ended = meta?.get("ended_at")?.jsonPrimitive?.contentOrNull
        if (started != null && ended != null) {
            // 原来走 LocalDateTime.parse + ChronoUnit；两端都是「本地时间串」，
            // 差分只关心相对量，TimeFmt 按同一时区解出 epoch 毫秒再做差，结果一致。
            // 解析失败时旧版是 `return runCatching{...}.getOrNull()` —— 直接返回 null、
            // **不**落到下面的 REPORT.md 分支，这里保持同一条兜底边界。
            val a = TimeFmt.parse(started, TS_SECONDS)
            val b = TimeFmt.parse(ended, TS_SECONDS)
            if (a == null || b == null) return null
            return TimeFmt.minutesBetween(a, b)
        }
        if (fs.isRegularFile(report)) {
            val text = fs.readString(report)
            val s = Regex("""\| 开始 \| ([^|]+) \|""").find(text)?.groupValues?.get(1)?.trim()
            val e = Regex("""\| 结束 \| ([^|]+) \|""").find(text)?.groupValues?.get(1)?.trim()
            if (s != null && e != null) {
                return runCatching {
                    // 解析失败语义同旧版：任一端解析不出来就整体兜底成 null
                    val a = TimeFmt.parse(s, TS_SECONDS) ?: return@runCatching null
                    val b = TimeFmt.parse(e, TS_SECONDS) ?: return@runCatching null
                    TimeFmt.minutesBetween(a, b)
                }.getOrNull()
            }
        }
        return null
    }

    private data class SeriesFlags(val pixelMapRising: Boolean, val xcomponentDeltaMb: Double)

    private fun parseDmabufSeries(csv: OhdPath): SeriesFlags {
        if (!fs.isRegularFile(csv)) return SeriesFlags(false, 0.0)
        // ts,pid,label,cnt,mb,total_mb
        val counts = mutableMapOf<String, MutableList<Int>>()
        val mbs = mutableMapOf<String, MutableList<Double>>()
        fs.readAllLines(csv).drop(1).forEach { line ->
            val p = line.split(',')
            if (p.size < 5) return@forEach
            val label = p[2].trim().trim('"')
            val cnt = p[3].toIntOrNull() ?: return@forEach
            val mb = p[4].toDoubleOrNull() ?: return@forEach
            counts.getOrPut(label) { mutableListOf() }.add(cnt)
            mbs.getOrPut(label) { mutableListOf() }.add(mb)
        }
        var pixelRising = false
        for ((label, seq) in counts) {
            if (!label.startsWith("pixelMap") && !label.startsWith("srcImage") &&
                !label.startsWith("pixelmap")
            ) {
                continue
            }
            if (seq.size < 3) continue
            val first = seq.first()
            val last = seq.last()
            if (last >= first + 3 && last > (seq.minOrNull() ?: last)) pixelRising = true
        }
        val xc = mbs["xcomponent"] ?: mbs.entries.firstOrNull { it.key.contains("xcomponent") }?.value
        val delta = if (xc != null && xc.size >= 2) xc.last() - xc.first() else 0.0
        return SeriesFlags(pixelRising, delta)
    }
}
