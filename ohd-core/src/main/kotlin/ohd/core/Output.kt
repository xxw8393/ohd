package ohd.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 工具版本：JSON 信封要带它，否则产物无法回溯是哪个版本生成的 */
const val TOOL_VERSION = "0.1.0"

/**
 * 时间点统一输出 ISO-8601（人类可读、可排序、无损时区）。
 * 时长不走这里——`elapsedMs` 之类的 duration 仍用毫秒数字，ISO duration 反而难读。
 */
fun isoTime(epochMs: Long): String = java.time.OffsetDateTime
    .ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneId.systemDefault())
    .toString()


/**
 * 统一输出：
 * - 人读：表格/键值（--ascii 给 Windows conhost 用 ASCII 边框）
 * - 机器读：--json 单一骨架 {schema, ok, capability, data, artifacts, diagnostics, elapsedMs}
 * 颜色只在真终端 + 未设 NO_COLOR 时启用。
 */
class Out(
    val jsonMode: Boolean = false,
    val color: Boolean = false,
    val ascii: Boolean = false,
) {
    private val diagnostics = mutableListOf<JsonObject>()
    private val artifacts = mutableListOf<JsonObject>()

    fun info(message: String) = println(withColor(message, ""))

    fun warn(message: String) {
        diagnostics += diag("warn", "WARN", message)
        if (!jsonMode) println(withColor("! $message", ANSI_YELLOW))
    }

    fun error(message: String) {
        diagnostics += diag("error", "ERROR", message)
        if (!jsonMode) System.err.println(withColor("x $message", ANSI_RED))
    }

    /**
     * 诊断信息：既进 JSON 的 diagnostics 数组，也在非 JSON 模式**直接打印**。
     * 早期版本只收集不打印，导致 SCREEN_BLACK / UI_MISMATCH / NO_SO_DIR 这类
     * 关键警告对人完全隐形 —— 自动化里会静默产出无效结论。
     */
    fun note(code: String, level: String, message: String) {
        diagnostics += diag(level, code, message)
        if (jsonMode) return
        val mark = when (level) {
            "error" -> "✗"
            "warn" -> "!"
            else -> "·"
        }
        println("$mark [$code] $message")
    }

    fun artifact(path: String, kind: String) {
        artifacts += buildJsonObject {
            put("path", path)
            put("kind", kind)
        }
    }

    fun table(headers: List<String>, rows: List<List<String>>) {
        if (jsonMode) return
        if (rows.isEmpty()) {
            println("（空）")
            return
        }
        val widths = IntArray(headers.size) { i ->
            maxOf(Platform.displayWidth(headers[i]), rows.maxOf { r -> Platform.displayWidth(r.getOrElse(i) { "" }) })
        }
        val h = if (ascii) "-" else "─"
        val v = if (ascii) "|" else "│"
        val tl = if (ascii) "+" else "┌"
        val tm = if (ascii) "+" else "┬"
        val tr = if (ascii) "+" else "┐"
        val ml = if (ascii) "+" else "├"
        val mm = if (ascii) "+" else "┼"
        val mr = if (ascii) "+" else "┤"
        val bl = if (ascii) "+" else "└"
        val bm = if (ascii) "+" else "┴"
        val br = if (ascii) "+" else "┘"
        val seg = widths.map { h.repeat(it + 2) }
        println(tl + seg.joinToString(tm) + tr)
        println(row(headers, widths, v, bold = true))
        println(ml + seg.joinToString(mm) + mr)
        rows.forEach { println(row(it, widths, v)) }
        println(bl + seg.joinToString(bm) + br)
    }

    fun kv(pairs: List<Pair<String, String>>) {
        if (jsonMode) return
        val w = pairs.maxOfOrNull { Platform.displayWidth(it.first) } ?: 0
        pairs.forEach { (k, v) ->
            println("${pad(k, w)}  $v")
        }
    }

    fun finish(capability: String, ok: Boolean, data: JsonElement, elapsedMs: Long) {
        if (!jsonMode) return
        val body = buildJsonObject {
            put("schema", "ohd/1")
            put("toolVersion", TOOL_VERSION)
            put("ok", ok)
            put("capability", capability)
            put("data", data)
            put("artifacts", buildJsonArray { artifacts.forEach { add(it) } })
            put("diagnostics", buildJsonArray { diagnostics.forEach { add(it) } })
            put("elapsedMs", elapsedMs)
        }
        println(Session.MANIFEST_JSON.encodeToString(JsonElement.serializer(), body))
    }

    private fun diag(level: String, code: String, message: String): JsonObject = buildJsonObject {
        put("level", level)
        put("code", code)
        put("message", message)
    }

    private fun row(cells: List<String>, widths: IntArray, sep: String, bold: Boolean = false): String {
        val parts = cells.mapIndexed { i, c ->
            " " + pad(c, widths[i]) + " "
        }
        return sep + parts.joinToString(sep) + sep
    }

    private fun pad(s: String, width: Int): String =
        s + " ".repeat((width - Platform.displayWidth(s)).coerceAtLeast(0))

    private fun withColor(s: String, code: String): String =
        if (color && code.isNotEmpty()) "$code$s$ANSI_RESET" else s

    companion object {
        const val ANSI_RESET = "\u001B[0m"
        const val ANSI_BOLD = "\u001B[1m"
        const val ANSI_RED = "\u001B[31m"
        const val ANSI_GREEN = "\u001B[32m"
        const val ANSI_YELLOW = "\u001B[33m"
        const val ANSI_DIM = "\u001B[2m"
    }
}

/** 子命令参数容器：值/开关/位置参数分开，避免"--flag 误吞下一个 token"这类歧义。 */
class Options(
    val values: Map<String, String> = emptyMap(),
    val flags: Set<String> = emptySet(),
    val positionals: List<String> = emptyList(),
) {
    fun str(name: String, default: String? = null): String? = values[name] ?: default
    fun bool(name: String): Boolean = name in flags || values[name].equals("true", ignoreCase = true)
    /**
     * 严格整数：非法值直接报错，不静默回退默认值。
     * 静默回退会让「--depth abc」看起来生效了其实用的是默认值，测试以为是工具的问题。
     */
    fun int(name: String, default: Int): Int {
        val raw = values[name] ?: return default
        return raw.trim().toIntOrNull()
            ?: throw OhdError(ExitCode.USAGE, "参数 --$name 需要整数，收到：\"$raw\"", "例如 --$name $default")
    }

    fun long(name: String, default: Long): Long {
        val raw = values[name] ?: return default
        return raw.trim().toLongOrNull()
            ?: throw OhdError(ExitCode.USAGE, "参数 --$name 需要整数，收到：\"$raw\"", "例如 --$name $default")
    }
    operator fun get(name: String): String? = values[name]
}

class RunContext(
    val env: Env,
    val out: Out,
    val options: Options,
    val cwd: java.nio.file.Path,
    val sessionRoot: java.nio.file.Path? = null,
    val archive: Boolean = true,
    /**
     * 本能力的原始参数（不含 `ohd` 与能力名，顺序原样）。
     * 扩展命令要把它整段转交给子进程，靠 Options 重组会丢顺序、丢 `--`。
     */
    val rawArgs: List<String> = emptyList(),
    /** 当前正在执行的能力名，由 CLI 注入；用于平台能力边界检查（如 iOS 的交互限制）。 */
    val capability: String = "",

    /** 内置能力名集合；扩展判断「是否会被内置遮蔽」用，由 CLI 注入。 */
    val builtinNames: Set<String> = emptySet(),
) {
    /** 本次运行创建的会话；由 CLI 在能力结束后统一落 manifest。 */
    val sessions: MutableList<ohd.core.Session> = mutableListOf()
}

interface Capability {
    val name: String
    val summary: String
    val usage: String

    /**
     * 本能力接受的 `--选项` 名。CLI 用它拒绝拼错的参数——
     * 静默忽略的后果是「--dept 3 写了等于没写」，排查时极难发现。
     */
    val accepts: Set<String> get() = emptySet()

    /** 参数全放行：只有「转发给子进程」的能力（如 ext）才该打开，其余能力仍需白名单。 */
    val passthrough: Boolean get() = false

    /**
     * `--help` 里附带的整条示例。参数表只回答「有哪些开关」，示例回答「怎么组合」——
     * 每个能力写一条真实场景的复杂命令，比逐项解释参数更省读者的时间。
     */
    val example: String? get() = null

    fun run(ctx: RunContext): Int
}
