package ohd.core

import java.nio.file.Files
import java.nio.file.Paths

/** 能力入口的三个公共动作：拿 hdc、拿会话、按调用记录命令。 */
object Hdcs {
    /** 选设备：hdc 与 adb 都参与，规则见 [Devices.select]。 */
    fun forDevice(ctx: RunContext): Hdc {
        val hdc = Devices.forDevice(ctx)
        // 统一的能力边界：iOS 上交互类能力在这里就失败，早于任何副作用
        IosSupport.requireSupported(ctx.capability, hdc.kind)
        return hdc
    }

    /**
     * 同一能力在一次运行内复用同一个会话目录。
     * 早期实现每次调用都新建目录，导致一个命令产出多个会话、manifest 也落不到产物边上。
     */
    fun session(ctx: RunContext, capability: String): Session? {
        if (!ctx.archive) return null
        ctx.sessions.firstOrNull { it.capability == capability }?.let { return it }
        val s = Session.create(capability, ctx.sessionRoot, ctx.cwd, label = ctx.options.str("device"))
        ctx.sessions.add(s)
        return s
    }

    /** 尽力取设备指纹；离线能力或未连设备时返回 null，不阻断主流程。 */
    fun deviceInfoOrNull(ctx: RunContext): DeviceInfo? = runCatching {
        val p = ctx.env.hdc.path ?: return@runCatching null
        val mgr = DeviceManager(Hdc(p))
        mgr.info(mgr.select(ctx.options.str("device")))
    }.getOrNull()

    /** 记录一次 hdc 调用到 manifest（失败不抛，取证优先）。 */
    fun record(session: Session?, r: Hdc.Result) {
        session?.recordCommand(r.argv, r.exit, r.elapsedMs)
    }

    fun sh(session: Session?, hdc: Hdc, command: String, timeoutMs: Long = 20_000, retry: Int = 0): Hdc.Result {
        val r = hdc.shell(command, timeoutMs, retry)
        record(session, r)
        return r
    }

    fun requireOk(r: Hdc.Result, what: String) {
        if (!r.ok) {
            throw OhdError(
                if (r.timedOut) ExitCode.TIMEOUT else ExitCode.UNSUPPORTED,
                "$what 失败：${r.text().take(300)}",
            )
        }
    }
}

fun parseDurationMs(text: String): Long {
    val t = text.trim().lowercase()
    val m = Regex("""^(\d+(?:\.\d+)?)\s*(ms|s|m|h)?$""").find(t)
        ?: throw OhdError(ExitCode.USAGE, "无法解析时长：$text", "示例：500ms / 2s / 5m")
    val v = m.groupValues[1].toDouble()
    val unit = m.groupValues[2].ifBlank { "s" }
    val factor = when (unit) {
        "ms" -> 1.0
        "s" -> 1000.0
        "m" -> 60_000.0
        "h" -> 3_600_000.0
        else -> 1000.0
    }
    return (v * factor).toLong()
}

fun readJsonFile(path: java.nio.file.Path): JsonObjectLite? {
    if (!Files.isRegularFile(path)) return null
    return runCatching { JsonObjectLite(Files.readString(path)) }.getOrNull()
}

/** 极简 JSON 对象包装（仅用于读取自己写的 state 文件，避免引入更多依赖）。 */
class JsonObjectLite(private val raw: String) {
    fun str(key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"(.*?)\"").find(raw)?.groupValues?.get(1)

    fun long(key: String): Long? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull()
}

fun defaultStateFile(root: java.nio.file.Path?, label: String? = null): java.nio.file.Path {
    val base = root ?: Session.defaultRoot(Paths.get("").toAbsolutePath())
    // 并行时每台设备的日志流状态必须独立，否则 log stop 会停错设备
    val suffix = Self.safeLabel(label)?.let { "-$it" } ?: ""
    return base.resolve("log-state$suffix.json")
}
