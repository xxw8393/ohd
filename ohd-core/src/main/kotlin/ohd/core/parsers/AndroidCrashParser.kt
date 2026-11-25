package ohd.core.parsers

/**
 * Android crash buffer → [CrashReport]（与 OHOS 的 CrashParser 同一数据结构）。
 *
 * Android 没有 faultlog 那样的「一条崩溃一个文件」，只有 `logcat -b crash` 的文本流。
 * 两种崩溃形态分别处理：
 * - **Java 未捕获异常**：`FATAL EXCEPTION: <thread>` + `Process: <pkg>, PID: <n>` + `at ...` 栈
 * - **Native 信号**：`Fatal signal 11 (SIGSEGV) ... pid 1234 (com.foo)` + `#00 pc ... libx.so (sym+0x..)`
 *
 * 抽不到的字段保持空串 / 0，不臆造。
 */
object AndroidCrashParser {

    private val FATAL_JAVA = Regex("""FATAL EXCEPTION:\s*(\S+)""")
    private val PROCESS = Regex("""Process:\s*([\w.]+),\s*PID:\s*(\d+)""")
    private val EXC = Regex("""AndroidRuntime:\s*([a-zA-Z_$][\w.$]*(?:Exception|Error|Throwable)[^\n]*)""")
    private val JS_AT = Regex("""AndroidRuntime:\s*at\s+(.+)$""")
    private val NATIVE_SIG =
        Regex("""Fatal signal\s+(\d+)\s+\((\w+)\).*?pid\s+(\d+)\s+\(([^)]+)\)""")
    private val NATIVE_FRAME =
        Regex("""#(\d+)\s+pc\s+([0-9a-fA-F]+)\s+(.+?)/([^/\s]+)\s+\(([^)]*)\)""")

    fun parse(raw: String, name: String = ""): CrashReport {
        val fields = LinkedHashMap<String, String>()
        if (name.isNotBlank()) fields["Name"] = name

        val lines = raw.lines()

        // —— 形态 1：Java 未捕获异常 ——
        val javaLine = lines.firstOrNull { FATAL_JAVA.containsMatchIn(it) }
        if (javaLine != null) {
            FATAL_JAVA.find(javaLine)?.groupValues?.get(1)?.let { fields["Thread"] = it }
            lines.firstOrNull { PROCESS.containsMatchIn(it) }?.let { l ->
                PROCESS.find(l)?.let { m ->
                    fields["PACKAGE_NAME"] = m.groupValues[1]
                    fields["Pid"] = m.groupValues[2]
                }
            }
            val reason = lines.firstOrNull { EXC.containsMatchIn(it) }
                ?.let { EXC.find(it)?.groupValues?.get(1)?.trim() }
                ?: javaLine.substringAfter("FATAL EXCEPTION:").trim()

            val stack = lines.mapNotNull { JS_AT.find(it)?.groupValues?.get(1)?.trim() }
            return CrashReport(
                kind = "jscrash",
                fields = fields,
                reason = reason,
                jsStack = stack,
                nativeFrames = emptyList(),
                pageHistory = emptyList(),
                threadHeaders = listOfNotNull(fields["Thread"]?.let { "Fault thread: $it" }),
            )
        }

        // —— 形态 2：Native 信号 ——
        val sigLine = lines.firstOrNull { NATIVE_SIG.containsMatchIn(it) }
        if (sigLine != null) {
            NATIVE_SIG.find(sigLine)?.let { m ->
                fields["Signal"] = "${m.groupValues[2]}(${m.groupValues[1]})"
                fields["Pid"] = m.groupValues[3]
                fields["PACKAGE_NAME"] = m.groupValues[4]
            }
            val frames = lines.mapNotNull { l ->
                NATIVE_FRAME.find(l)?.let { m ->
                    NativeFrame(
                        index = m.groupValues[1].toIntOrNull() ?: 0,
                        pc = m.groupValues[2].toLongOrNull(16) ?: 0L,
                        soPath = "${m.groupValues[3]}/${m.groupValues[4]}",
                        soName = m.groupValues[4],
                        inlineSymbol = m.groupValues[5].trim().ifBlank { null },
                        buildId = null,
                        raw = l.trim(),
                    )
                }
            }
            return CrashReport(
                kind = "cppcrash",
                fields = fields,
                reason = sigLine.trim(),
                jsStack = emptyList(),
                nativeFrames = frames,
                pageHistory = emptyList(),
                threadHeaders = emptyList(),
            )
        }

        // —— 兜底：有内容但认不出形态，原文当 reason ——
        return CrashReport(
            kind = "unknown",
            fields = fields,
            reason = lines.firstOrNull { it.isNotBlank() }?.trim() ?: "",
            jsStack = emptyList(),
            nativeFrames = emptyList(),
            pageHistory = emptyList(),
            threadHeaders = emptyList(),
        )
    }
}
