package ohd.core.parsers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * iOS 模拟器崩溃解析。
 *
 * 数据源不是设备内的 Faultlogger，而是**宿主**的
 * `~/Library/Logs/DiagnosticReports` 目录下的 .ips 文件：模拟器里的进程崩掉后，
 * CoreSimulator 会把系统级崩溃报告写到宿主这个目录。
 *
 * `.ips` 是两段 JSON：首行是 metadata，其后是 payload（异常、终止原因、线程栈）。
 *
 * 必须按 `os_version` 过滤：同一个目录里也躺着宿主自身工具（bash / log / ps…）的崩溃，
 * 那些不是「应用崩溃」，混进来会污染结论。
 */
object IosCrashParser {

    data class Entry(
        val file: Path,
        val appName: String,
        val timestamp: String,
        val osVersion: String,
        val bugType: String,
        val exceptionType: String?,
        val signal: String?,
        val terminateReason: String?,
        val threadCount: Int,
        val raw: String,
    ) {
        val name: String get() = file.fileName.toString()
    }

    /** 崩溃报告目录；模拟器与宿主共用。 */
    fun dir(): Path = Paths.get(System.getProperty("user.home"), "Library", "Logs", "DiagnosticReports")

    /** 是否为模拟器内的崩溃（宿主工具崩溃的 os_version 写的是 macOS）。 */
    private fun isSimulator(report: Entry): Boolean = report.osVersion.contains("iOS", ignoreCase = true)

    /**
     * 按时间倒序列出模拟器崩溃。[limit] 为 0 表示不限。
     * 目录不存在或无权限时返回空列表——「没有崩溃」和「读不到」由调用方区分。
     */
    fun list(limit: Int = 20): List<Entry> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        val files = runCatching {
            Files.list(d).use { s ->
                s.filter { it.fileName.toString().endsWith(".ips") }
                    .toList()
            }
        }.getOrDefault(emptyList())
            .sortedByDescending { runCatching { Files.getLastModifiedTime(it) }.getOrNull() }

        val out = mutableListOf<Entry>()
        for (f in files) {
            val e = parse(f) ?: continue
            if (!isSimulator(e)) continue
            out += e
            if (limit > 0 && out.size >= limit) break
        }
        return out
    }

    /** 解析单个 .ips；格式不认识时返回 null 而不是抛错（崩溃报告不该让工具崩）。 */
    fun parse(file: Path): Entry? {
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        val split = text.indexOf('\n')
        if (split <= 0) return null
        val meta = runCatching { Json.parseToJsonElement(text.substring(0, split)).jsonObject }
            .getOrNull() ?: return null
        val body = runCatching { Json.parseToJsonElement(text.substring(split + 1)).jsonObject }
            .getOrNull()

        val exception = body?.get("exception")?.let { runCatching { it.jsonObject }.getOrNull() }
        val termination = body?.get("termination")?.let { runCatching { it.jsonObject }.getOrNull() }

        return Entry(
            file = file,
            appName = str(meta, "app_name") ?: file.fileName.toString().substringBefore("-"),
            timestamp = str(meta, "timestamp") ?: "",
            osVersion = str(meta, "os_version") ?: "",
            bugType = str(meta, "bug_type") ?: "",
            exceptionType = exception?.let { str(it, "type") },
            signal = exception?.let { str(it, "signal") },
            terminateReason = termination?.get("reasons")
                ?.let { runCatching { it.jsonArray.firstOrNull()?.jsonPrimitive?.content }.getOrNull() },
            threadCount = body?.get("threads")
                ?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0,
            raw = text,
        )
    }

    private fun str(o: JsonObject, key: String): String? =
        o[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }

    /** 人类可读摘要，供 `crash --details` 输出。 */
    fun render(e: Entry): String = buildString {
        appendLine("崩溃文件   ${e.name}")
        appendLine("应用       ${e.appName}")
        appendLine("时间       ${e.timestamp}")
        appendLine("系统       ${e.osVersion}")
        appendLine("类型       ${e.bugType}${if (e.bugType == "309") "（crash）" else ""}")
        e.exceptionType?.let { appendLine("异常       $it${e.signal?.let { s -> " / $s" } ?: ""}") }
        e.terminateReason?.let { appendLine("终止原因   $it") }
        appendLine("线程数     ${e.threadCount}")
    }
}
