package ohd.core

/**
 * iOS 模拟器的能力边界：**只做观测，不做交互**。
 *
 * 判定依据（2026-10-22 在 Xcode 26.2 / iOS 26.2 模拟器实测）：
 * - `simctl` 子命令里没有 `tap` / `swipe` / `key`，触摸注入无通道；
 * - 模拟器内连 `ps` / `top` / `uname` 都没有，进程 CPU 无法采样；
 * - UI 层级需要 accessibility dump，模拟器不提供。
 *
 * 因此交互类能力在 iOS 上直接拒绝并说明原因，而不是静默失败或返回空结果——
 * 「假装成功」比「明确不支持」有害得多。
 */
object IosSupport {

    /** 能力名 → 不支持的原因。 */
    val UNSUPPORTED: Map<String, String> = mapOf(
        "record" to "依赖 uitest uiRecord，iOS 无对应通道",
        "cpu" to "模拟器内没有 ps/top，无法采样进程 CPU",
        "dmabuf" to "iOS 没有 dmabuf 概念",
        "soak" to "依赖 monkey 的触摸注入",
        "ab" to "A/B 对比依赖 monkey 驱动负载，iOS 无法注入事件",
        "repro" to "依赖 replay 的事件重放",
        "watch" to "依赖崩溃轮询，iOS 崩溃日志尚未接入",
        "salvage" to "依赖崩溃采集，iOS 崩溃日志尚未接入",
    )

    /**
     * 真机上额外不可用的能力。
     *
     * 模拟器能 `spawn` 任意命令，所以日志/内存/截图都能从设备里读；
     * 真机**没有 shell**，devicectl 也不提供对应子命令，这三项直接没有数据源。
     */
    val DEVICE_UNSUPPORTED: Map<String, String> = UNSUPPORTED + mapOf(
        "shot" to "真机没有截图通道（devicectl 不提供，需 idevicescreenshot）",
        "video" to "真机没有录屏通道（devicectl 不提供，需 Xcode / xctrace 手动录制）",
        "log" to "真机没有日志流（devicectl 不提供，需 idevicesyslog）",
        "mem" to "真机没有内存采样（devicectl 不提供，需 xctrace）",
        "crash" to "真机崩溃日志目录暂无可读样本（需 ~/Library/Logs/CrashReporter/MobileDevice）",
    )

    fun reasonOf(cap: String): String? = UNSUPPORTED[cap]

    /**
     * 在当前设备上该能力是否可用；不可用则直接抛出带原因的 [OhdError]。
     * 由能力入口调用，保证「不支持」发生在任何副作用之前。
     */
    fun requireSupported(cap: String, kind: DeviceKind) {
        val table = when (kind) {
            DeviceKind.IOS -> UNSUPPORTED
            DeviceKind.IOS_DEVICE -> DEVICE_UNSUPPORTED
            else -> return
        }
        val why = table[cap] ?: return
        val where = if (kind == DeviceKind.IOS) "iOS 模拟器" else "iPhone 真机"
        val usable = if (kind == DeviceKind.IOS) {
            "devices/install/log/shot/mem/apps/tap/input/monkey/replay（交互走 idb）"
        } else {
            "install/设备信息/tap/monkey（交互走 idb）"
        }
        throw OhdError(
            ExitCode.UNSUPPORTED,
            "$cap 在 $where 上不可用：$why",
            "iOS 侧目前只做观测：$usable",
        )
    }
}
