package ohd.core.analysis

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ohd.core.ExitCode
import ohd.core.JsonOut
import ohd.core.OhdError
import ohd.core.parsers.CrashParser
import ohd.core.parsers.CrashReport
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.imageSizeOf
import ohd.platform.TimeFmt
import ohd.platform.fmt

/**
 * 证据包的统一读取层。
 *
 * `analyze` 与 `report` 必须看到同一组数字——否则同一份包会给出两套结论。
 * 所以解析只在这里做一次，两个命令都只是渲染。
 */
object CaseFacts {

    data class Frame(
        val index: Int,
        val so: String,
        val pc: String,
        val function: String,
        val location: String,
        val status: String,
    )

    data class StackInfo(
        val frames: List<Frame>,
        val symbolized: Int,
        val localOnly: Int,
        val missingSo: Int,
        val buildIdMismatch: Boolean,
    )

    data class MetricSummary(
        val count: Int,
        val spanSec: Double,
        val pssFirst: Long,
        val pssLast: Long,
        val pssMin: Long,
        val pssMax: Long,
        val slopeMbPerSec: Double,
        val shape: String,
        val cpuAvg: Double,
    )

    data class FrameFile(val name: String, val width: Int, val height: Int)

    data class Facts(
        val dir: OhdPath,
        val capability: String,
        val device: JsonObject?,
        val crash: CrashReport?,
        val stack: StackInfo,
        val metrics: MetricSummary?,
        val frames: List<FrameFile>,
        val stepLines: List<String>,
        val logLines: Int,
        val logBytes: Long,
        val completeness: JsonObject?,
        val missing: List<Pair<String, String>>,
        val hints: List<String>,
        val artifactCount: Int,
        val redacted: Boolean,
    ) {
        val missingLabels: List<String>
            get() = missing.map { (k, _) -> labelOf(k) }

        companion object {
            fun labelOf(key: String): String = when (key) {
                "env" -> "设备与构建指纹"
                "frames" -> "界面截图帧"
                "logs" -> "应用日志窗口"
                "crash" -> "崩溃原始日志"
                "symbols" -> "符号化结果"
                "metrics" -> "指标时间序列"
                "repro" -> "可复现步骤"
                else -> key
            }
        }
    }

    /** 定位证据包：显式目录优先，否则取 ohd-evidence/cases 下最新一个。 */
    fun locate(arg: String?): OhdPath {
        val p = arg?.let { OhdPath.of(it) }
        if (p != null) {
            if (!fs.isDirectory(p)) throw OhdError(ExitCode.USAGE, "目录不存在：$p")
            if (!fs.isRegularFile(p.resolve("COMPLETENESS.json"))) {
                throw OhdError(
                    ExitCode.USAGE,
                    "不是证据包：$p",
                    if (fs.isRegularFile(p.resolve("manifest.json"))) "这是会话目录，先执行 ohd bundle" else "缺 COMPLETENESS.json",
                )
            }
            return p
        }
        val cases = ohd.core.Session.evidenceRoot().resolve("cases")
        if (!fs.isDirectory(cases)) {
            throw OhdError(ExitCode.USAGE, "找不到证据包", "先执行 ohd bundle，或显式指定包目录")
        }
        return fs.list(cases).use { s ->
            s.filter { fs.isDirectory(it) && fs.isRegularFile(it.resolve("COMPLETENESS.json")) }
                .maxByOrNull { runCatching { fs.lastModifiedMillis(it) }.getOrNull() ?: 0L }
                ?: throw OhdError(ExitCode.USAGE, "没有可分析的证据包", "先执行 ohd bundle")
        }
    }

    fun load(dir: OhdPath, top: Int = 10): Facts {
        val completeness = readJson(dir.resolve("COMPLETENESS.json"))
        val context = readJson(dir.resolve("context.json"))

        val rawCrash = dir.resolve("crash/raw.log").takeIf { fs.isRegularFile(it) }
        val crash = rawCrash?.let { f ->
            runCatching { CrashParser.parse(fs.readString(f), f.fileName?.toString() ?: "") }.getOrNull()
        }
        val symbols = readJson(dir.resolve("crash/symbols.json"))
        val metrics = readMetrics(dir.resolve("metrics/sample.csv"))
        val stepLines = readSteps(dir.resolve("repro/steps.json"))
        val frames = dir.resolve("frames").takeIf { fs.isDirectory(it) }?.let { listFrames(it) } ?: emptyList()
        val logFile = dir.resolve("logs/app.log").takeIf { fs.isRegularFile(it) }

        val stack = buildStack(crash, symbols, top)
        val missing = (completeness?.get("items") as? JsonObject)?.entries
            ?.filter { (_, v) -> (v as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull != "ok" }
            ?.map { (k, v) -> k to ((v as JsonObject)["reason"]?.jsonPrimitive?.contentOrNull ?: "") }
            ?: emptyList()

        val hints = mutableListOf<String>()
        missing.forEach { (k, reason) ->
            when (k) {
                "symbols" -> hints.add("栈只有地址没有函数名：用 ohd sym --crash crash/raw.log --so-dir <debug so 目录> 重做符号化")
                "crash" -> hints.add("没有崩溃原文：本包只能说明现象，不能说明崩溃点")
                "logs" -> hints.add("没有日志：异常发生前的运行轨迹不可知，用 ohd log start/stop 补采")
                "metrics" -> hints.add("没有指标序列：无法判断内存/CPU 趋势，用 ohd watch 补采")
                "frames" -> hints.add("没有截图：界面表现与坐标无从核对，用 ohd shot --grid 补采")
                "repro" -> hints.add("没有复现步骤：无法声称可复现，用 ohd replay 脚本记录操作")
                "env" -> hints.add("没有设备指纹：结论不可迁移到其它机型/版本")
                else -> if (reason.isNotEmpty()) hints.add("缺 $k：$reason")
            }
        }
        if (stack.missingSo > 0) {
            hints.add("栈中有 ${stack.missingSo} 帧本地 so 不可用（缺 so 或不是 ELF）：需该构建的 debug so 目录，或先确认 buildId")
        }
        if (stack.buildIdMismatch) {
            hints.add("本地 so 的 buildId 与崩溃日志不一致：拿错版本了，换该构建产物再符号化")
        }
        if (metrics != null && metrics.count < 5) {
            hints.add("指标仅 ${metrics.count} 个采样：不足以判断趋势，延长 ohd watch 时长")
        }
        if (crash?.kind == "appfreeze") {
            hints.add("appfreeze 盯的是主线程为什么没响应：看栈顶首帧的调用方，而不是栈底框架")
        }
        if (hints.isEmpty()) hints.add("证据链完整，可直接交给分析方")

        return Facts(
            dir = dir,
            capability = context?.get("capability")?.jsonPrimitive?.contentOrNull ?: "-",
            device = context?.get("device") as? JsonObject,
            crash = crash,
            stack = stack,
            metrics = metrics,
            frames = frames,
            stepLines = stepLines,
            logLines = logFile?.let { countLines(it) } ?: 0,
            logBytes = logFile?.let { runCatching { fs.size(it) }.getOrDefault(0L) } ?: 0L,
            completeness = completeness,
            missing = missing,
            hints = hints,
            artifactCount = context?.get("artifacts")?.let { runCatching { (it as JsonArray).size }.getOrNull() } ?: 0,
            redacted = (context?.get("redaction") as? JsonObject)
                ?.get("enabled")?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    fun forbiddenConclusions(completeness: JsonObject?): List<String> =
        (completeness?.get("forbiddenConclusions") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    fun buildStack(crash: CrashReport?, symbols: JsonObject?, top: Int): StackInfo {
        val symFrames = (symbols?.get("frames") as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val note = o["note"]?.jsonPrimitive?.contentOrNull ?: ""
            val local = o["function"]?.jsonPrimitive?.contentOrNull ?: ""
            val logged = o["logSymbol"]?.jsonPrimitive?.contentOrNull ?: ""
            Frame(
                index = o["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                so = o["so"]?.jsonPrimitive?.contentOrNull ?: "?",
                pc = o["pc"]?.jsonPrimitive?.contentOrNull ?: "?",
                // 本地符号化失败时回落日志自带符号：系统库通常自带，不必依赖本地 so
                function = if (local.isNotEmpty()) local else logged,
                location = o["location"]?.jsonPrimitive?.contentOrNull ?: "",
                status = when {
                    local.isNotEmpty() -> "ok"
                    logged.isNotEmpty() -> if (note.isEmpty()) "LOG_SYMBOL" else note + "+LOG"
                    note.isNotEmpty() -> note
                    else -> "unknown"
                },
            )
        }
        val frames = (symFrames ?: crash?.nativeFrames?.map { nf ->
            Frame(nf.index, nf.soName, fmt("0x%x", nf.pc), nf.inlineSymbol ?: "", "", if (nf.buildId != null) "logged" else "unknown")
        } ?: emptyList()).take(top)

        val ids: (String) -> Set<String> = { key ->
            (symbols?.get("frames") as? JsonArray)?.mapNotNull { el ->
                (el as? JsonObject)?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotEmpty() }
            }?.toSet() ?: emptySet()
        }
        val logIds = ids("buildIdLog")
        val localIds = ids("buildIdLocal")
        return StackInfo(
            frames = frames,
            symbolized = frames.count { it.status == "ok" || it.status.startsWith("LOG_SYMBOL") || it.status.endsWith("+LOG") },
            localOnly = frames.count { it.status == "ok" },
            missingSo = frames.count { it.status.contains("MISSING_SO") || it.status.contains("NOT_ELF") },
            buildIdMismatch = logIds.isNotEmpty() && localIds.isNotEmpty() && logIds.intersect(localIds).isEmpty(),
        )
    }

    fun readMetrics(file: OhdPath): MetricSummary? {
        if (!fs.isRegularFile(file)) return null
        val rows = runCatching { fs.readAllLines(file) }.getOrNull() ?: return null
        if (rows.size < 2) return null
        val header = rows.first().split(',')
        val iPss = header.indexOf("pss_kb")
        val iCpu = header.indexOf("cpu_percent")
        val iTime = header.indexOf("time") // 新 csv：ISO-8601
        val iMs = header.indexOf("epoch_ms") // 旧 csv：epoch 毫秒
        if (iPss < 0) return null
        val pss = mutableListOf<Long>()
        val cpu = mutableListOf<Double>()
        val stamps = mutableListOf<Long>()
        rows.drop(1).forEach { line ->
            val c = line.split(',')
            if (c.size <= iPss) return@forEach
            c.getOrNull(iPss)?.trim()?.toLongOrNull()?.let { pss.add(it) }
            if (iCpu >= 0) c.getOrNull(iCpu)?.trim()?.toDoubleOrNull()?.let { cpu.add(it) }
            // 两种时间列都要能读：换格式不能把历史证据包变成不可分析
            val stamp = when {
                iTime >= 0 -> c.getOrNull(iTime)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    TimeFmt.parseIso(it)
                }
                iMs >= 0 -> c.getOrNull(iMs)?.trim()?.toLongOrNull()
                else -> null
            }
            stamp?.let { stamps.add(it) }
        }
        if (pss.isEmpty()) return null
        val span = if (stamps.size >= 2) (stamps.last() - stamps.first()) / 1000.0 else (pss.size - 1).toDouble()
        val first = pss.first()
        val last = pss.last()
        val slope = if (span > 0) (last - first) / 1024.0 / span else 0.0
        var up = 0
        var down = 0
        pss.zipWithNext().forEach { (a, b) -> if (b > a) up++ else if (b < a) down++ }
        val shape = when {
            pss.size < 3 -> "样本不足"
            up > down * 3 && slope > 0 -> "持续上升"
            down > up * 3 && slope < 0 -> "持续下降"
            else -> "震荡"
        }
        return MetricSummary(
            count = pss.size,
            spanSec = span,
            pssFirst = first,
            pssLast = last,
            pssMin = pss.min(),
            pssMax = pss.max(),
            slopeMbPerSec = slope,
            shape = shape,
            cpuAvg = if (cpu.isEmpty()) 0.0 else cpu.average(),
        )
    }

    fun listFrames(dir: OhdPath): List<FrameFile> = runCatching {
        fs.list(dir).use { s ->
            s.filter { fs.isRegularFile(it) }.sortedBy { it.toString() }.map { f ->
                // 只要宽高：走 imageSizeOf 读文件头，不做全量解码（原来 ImageIO.read 是整张解码）
                val dim = imageSizeOf(f)
                FrameFile(f.fileName?.toString() ?: "", dim?.first ?: 0, dim?.second ?: 0)
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun readSteps(file: OhdPath): List<String> {
        val json = readJson(file) ?: return emptyList()
        return (json["steps"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.get("raw")?.jsonPrimitive?.contentOrNull
        } ?: emptyList()
    }

    fun readJson(file: OhdPath): JsonObject? =
        if (!fs.isRegularFile(file)) null
        else runCatching { JsonOut.encode.parseToJsonElement(fs.readString(file)).jsonObject }.getOrNull()

    private fun countLines(file: OhdPath): Int = runCatching {
        fs.countLines(file).toInt()
    }.getOrDefault(0)
}
