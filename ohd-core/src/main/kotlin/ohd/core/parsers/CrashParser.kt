package ohd.core.parsers

/**
 * 一条 native 栈帧。实测格式（jscrash 与 appfreeze 一致）：
 *
 *   #04 pc 00000000002f86bc /system/lib64/platformsdk/libark_jsruntime.so(panda::ecmascript::ObjectFactory::GetJSError(...)+212)(0c10ca1f290a450c19e957291d44cbf4)
 *   #00 pc 00000000001d9f3c /system/lib/ld-musl-aarch64.so.1(__timedwait_cp+156)(8c2ca4e308a1de9ed1e6b4ccc14c7443)
 *
 * 结构：`#序号 pc 地址 so路径(内联符号)(buildId)`。
 * buildId 长度不固定（实测 32 与 40 hex 都出现），必须按 16..64 位匹配。
 * buildId 是符号化版本对齐的唯一可靠依据 —— 日志自带，不需要额外问构建机要。
 */
data class NativeFrame(
    val index: Int,
    val pc: Long,
    val soPath: String,
    val soName: String,
    val inlineSymbol: String?,
    val buildId: String?,
    val raw: String,
)

data class CrashReport(
    val kind: String,
    val fields: Map<String, String>,
    val reason: String,
    val jsStack: List<String>,
    val nativeFrames: List<NativeFrame>,
    val pageHistory: List<String>,
    val threadHeaders: List<String>,
) {
    val module: String get() = fields["Module name"] ?: fields["PACKAGE_NAME"] ?: ""
    val buildInfo: String get() = fields["Build info"] ?: ""
    val abi: String get() = fields["CpuAbi"] ?: ""
    val pid: Int get() = fields["Pid"]?.toIntOrNull() ?: 0
    val fingerprint: String get() = fields["Fingerprint"] ?: ""
    val timestamp: String get() = fields["Timestamp"] ?: fields["start time"] ?: fields["Fault time"] ?: fields["Time"] ?: ""
    val releaseType: String get() = fields["ReleaseType"] ?: ""
    val device: String get() = fields["Device info"] ?: ""

    /** 同一 so 的多个 buildId 会出现（不同进程/不同版本），打印时需去重展示。 */
    fun buildIds(): Map<String, String> = nativeFrames
        .filter { it.buildId != null }
        .groupBy { it.soName }
        .mapValues { it.value.first().buildId!! }
}

object CrashParser {
    private val FRAME_HEAD = Regex("""^#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+(.*)$""")
    private val BUILD_ID = Regex("""\(([0-9a-fA-F]{16,64})\)\s*$""")
    private val SO_WITH_SYM = Regex("""^(\S+?)\((.*)\)$""")
    private val KV = Regex("""^([A-Za-z][A-Za-z0-9 _()/.\-#]*):(.*)$""")
    private val PAGE_LINE = Regex("""^\d{2}:\d{2}:\d{2}\.\d{3}\s+\S.*$""")

    fun parse(raw: String, nameHint: String = ""): CrashReport {
        val fields = linkedMapOf<String, String>()
        val jsStack = mutableListOf<String>()
        val frames = mutableListOf<NativeFrame>()
        val pageHistory = mutableListOf<String>()
        val threads = mutableListOf<String>()
        var reason = ""
        var section = ""

        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.endsWith(":") && !t.contains(" ") ) {
                section = t.dropLast(1)
                continue
            }
            when {
                t.startsWith("#") && t.contains(" pc ") -> {
                    parseFrame(t)?.let { frames += it }
                }
                t.startsWith("Tid:") -> threads += t
                t.startsWith("at ") && (section == "Stacktrace" || section == "Error stack") -> jsStack += t
                t.startsWith("Page switch history") -> section = "Page switch history"
                section == "Page switch history" && PAGE_LINE.matches(t) -> pageHistory += t
                KV.find(t) != null -> {
                    val m = KV.find(t)!!
                    val key = m.groupValues[1].trim()
                    val value = m.groupValues[2].trim()
                    if (key == "Reason") reason = value
                    if (fields.size < 60) fields[key] = value
                    if (key == "Stacktrace") section = "Stacktrace"
                    if (key == "HybridStack" || key == "NativeStack" || key == "Native stack") section = "native"
                }
            }
        }
        return CrashReport(
            kind = detectKind(raw, nameHint, reason),
            fields = fields,
            reason = reason,
            jsStack = jsStack,
            nativeFrames = frames,
            pageHistory = pageHistory,
            threadHeaders = threads,
        )
    }

    fun parseFrame(line: String): NativeFrame? {
        val m = FRAME_HEAD.find(line) ?: return null
        val index = m.groupValues[1].toIntOrNull() ?: return null
        val pc = m.groupValues[2].toLongOrNull(16) ?: return null
        var rest = m.groupValues[3].trim()

        var buildId: String? = null
        BUILD_ID.find(rest)?.let {
            buildId = it.groupValues[1]
            rest = rest.substring(0, it.range.first).trim()
        }
        var sym: String? = null
        val sm = SO_WITH_SYM.find(rest)
        val soPath = if (sm != null) {
            sym = sm.groupValues[2]
            sm.groupValues[1]
        } else {
            rest
        }
        if (soPath.isEmpty() || !soPath.startsWith("/")) return null
        return NativeFrame(index, pc, soPath, soPath.substringAfterLast('/'), sym, buildId, line.trim())
    }

    private fun detectKind(raw: String, nameHint: String, reason: String): String = when {
        // 实际 faultlog 写的是 `Reason:Signal:SIGSEGV(...)`，"Signal" 里 i/g 是小写，
        // 原来的 startsWith("SIG") 大小写敏感 → 所有 cppcrash 都被判成 unknown。
        reason.startsWith("SIG", ignoreCase = true) ||
            reason.contains("SIGSEGV", true) || reason.contains("SIGABRT", true) ||
            reason.contains("SIGBUS", true) || reason.contains("SIGILL", true) ||
            reason.contains("SIGFPE", true) || reason.contains("SIGTRAP", true) -> "cppcrash"
        reason.contains("THREAD_BLOCK", true) || reason.contains("APP_FREEZE", true) -> "appfreeze"
        nameHint.contains("appfreeze", true) || raw.contains("appfreeze:", true) -> "appfreeze"
        nameHint.contains("jscrash", true) || raw.contains("jscrash:", true) -> "jscrash"
        raw.contains("Stacktrace:") -> "jscrash"
        else -> "unknown"
    }
}
