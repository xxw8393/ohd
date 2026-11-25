package ohd.core

/**
 * 故障（faultlog）列表读取。
 *
 * `repro` 靠它判复现，`monkey` 靠它判崩溃——两处必须同一套解析，
 * 否则同一条故障在两个命令里可能判出不同结果。
 *
 * 名字格式：`appfreeze-com.tencent.compose-20020221-20261016181424`
 *           `kind - module - uid - timestamp(14位)`
 */
object Faults {
    data class Entry(val kind: String, val module: String, val raw: String)

    private val NAME = Regex("""^([a-zA-Z]+)-(.+)-(\d+)-(\d{10,})$""")

    fun parse(name: String): Entry? {
        val t = name.trim()
        NAME.find(t)?.let { m -> return Entry(m.groupValues[1], m.groupValues[2], t) }
        // Android crash buffer：没有文件名，只有崩溃首行；统一记 jscrash，进程名能抽就抽
        if (t.contains("FATAL")) {
            val pkg = Regex("""Process:\s*([a-zA-Z][\w.]*)""").find(t)?.groupValues?.get(1)
                ?: Regex("""\b([a-z][\w]*(\.[\w]+)+)/""").find(t)?.groupValues?.get(1)
                ?: "android"
            return Entry("jscrash", pkg, t)
        }
        return null
    }

    /** 列出设备上所有故障，失败返回空列表（不抛，调用方按「无变化」处理） */
    fun list(session: Session?, hdc: Hdc): List<String> = when (hdc.kind) {
        // iOS 崩溃日志落在宿主 DiagnosticReports，尚未接入；先按「无故障」处理，不假装已采
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> emptyList()
        DeviceKind.OHOS -> {
            val r = Hdcs.sh(session, hdc, PlatformCmds.crashListCommand(hdc.kind), 30_000)
            if (!r.ok) emptyList()
            else r.text().lines().map { it.trim() }.filter { it.isNotEmpty() && parse(it) != null }
        }
        // Android 的 crash buffer 是全文而非文件列表：取「崩溃首行」做标识，
        // 时间戳包含在行内，因此可做前后差分
        DeviceKind.ANDROID -> {
            val r = Hdcs.sh(session, hdc, PlatformCmds.crashListCommand(hdc.kind), 30_000)
            if (!r.ok) emptyList()
            else r.text().lines().map { it.trim() }
                .filter { it.isNotEmpty() && it.contains("FATAL") }
        }
    }

    /** 只返回 [before] 之后新增的故障 */
    fun diff(before: List<String>, after: List<String>): List<Entry> {
        val old = before.toSet()
        return after.filter { it !in old }.mapNotNull { parse(it) }
    }
}
