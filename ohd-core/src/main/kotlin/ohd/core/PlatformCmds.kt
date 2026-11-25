package ohd.core

/**
 * 平台命令映射：**唯一**出现 OHOS/Android 命令差异的地方。
 *
 * 设计原则：能力层只说「我要内存」，不说「跑 hidumper 还是 dumpsys」。
 * 这样 Android 全量对等时，改动集中在本文件 + 对应的输出解析器，而不是散落在 20 个能力里。
 *
 * 已知的平台能力差异（不假装对等，宁可报 UNSUPPORTED）：
 * - DMA（dmabuf）：Android 需 root，`/proc/<pid>/mm_dmabuf_info` 通常读不到；
 * - faultlog：OHOS 有独立服务（1201），Android 只有 crash buffer；
 * - uitest：OHOS 自有 DSL，Android 对应 `input` + `uiautomator`，语义不完全等价。
 */
object PlatformCmds {

    /**
     * iOS 上「没有对应命令」的显式出口。
     *
     * 用抛错而不是返回空串：空串会被 execute 成一条空 shell 命令，静默成功，
     * 让调用方以为采到了数据。这里 fail fast。
     */
    private fun iosUnsupported(what: String): Nothing = throw OhdError(
        ExitCode.UNSUPPORTED,
        "$what 在 iOS 模拟器上不可用",
        "simctl 只覆盖观测类能力；交互类与进程 CPU 无通道",
    )


    // ---------- 日志 ----------

    fun logCommand(kind: DeviceKind, tags: List<String>): String = when (kind) {
        DeviceKind.OHOS -> HilogStream.command(tags)
        DeviceKind.ANDROID ->
            if (tags.isEmpty()) "logcat -v threadtime"
            else "logcat -v threadtime -s " + tags.joinToString(",")
        // 经 spawn 在模拟器内执行，log 工具确实存在（实测可流式输出）
        DeviceKind.IOS -> "log stream --style compact"
        // 真机没有 shell，也没有可 spawn 的 log 二进制；需 idevicesyslog 之类的宿主通道
        DeviceKind.IOS_DEVICE -> iosUnsupported("日志流（真机无 shell）")
    }

    // ---------- 内存 / CPU / DMA ----------

    fun memCommand(kind: DeviceKind, pid: Int): String = when (kind) {
        DeviceKind.OHOS -> "hidumper --mem $pid"
        DeviceKind.ANDROID -> "dumpsys meminfo $pid"
        // 模拟器内连 ps 都没有，内存只能由宿主 footprint 采样，见 MemCap 的 iOS 分支
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("设备内内存采样")
    }

    fun cpuCommand(kind: DeviceKind, pid: Int): String = when (kind) {
        DeviceKind.OHOS -> "hidumper --cpuusage $pid"
        DeviceKind.ANDROID -> "top -b -n 1 -p $pid"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("进程 CPU 采样")
    }

    fun dmaCommand(kind: DeviceKind, pid: Int): String = when (kind) {
        DeviceKind.OHOS -> "cat /proc/$pid/mm_dmabuf_info"
        // Android 同路径存在但权限受限；读不到时调用方按「无数据」处理
        DeviceKind.ANDROID -> "cat /proc/$pid/mm_dmabuf_info"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("dmabuf 采集")
    }

    fun supportsDma(kind: DeviceKind): Boolean = kind == DeviceKind.OHOS

    // ---------- 崩溃 ----------

    /** 崩溃「列表」：OHOS 是 faultlog 文件名列表；Android 是 crash buffer 全文。 */
    fun crashListCommand(kind: DeviceKind): String = when (kind) {
        DeviceKind.OHOS -> "hidumper -s 1201 -a '-p Faultlogger -l'"
        DeviceKind.ANDROID -> "logcat -b crash -d"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("崩溃列表采集")
    }

    /** 拉取单条崩溃详情。Android 的 crash buffer 本身就是全文，返回 null 表示无需二次拉取。 */
    fun crashDetailCommand(kind: DeviceKind, rawName: String): String? = when (kind) {
        DeviceKind.OHOS -> "hidumper -s 1201 -a '-p Faultlogger -f $rawName'"
        DeviceKind.ANDROID -> null
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("崩溃详情拉取")
    }

    // ---------- 应用 ----------

    fun launchCommand(kind: DeviceKind, bundle: String, ability: String?): String = when (kind) {
        DeviceKind.OHOS -> "aa start -a ${ability ?: "EntryAbility"} -b $bundle"
        DeviceKind.ANDROID -> "monkey -p $bundle -c android.intent.category.LAUNCHER 1"
        // simctl launch 不是 shell 命令，必须在 InstallCap 里用 Hdc.run 走子命令
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("应用启动（应走 simctl launch）")
    }

    fun forceStopCommand(kind: DeviceKind, bundle: String): String = when (kind) {
        DeviceKind.OHOS -> "aa force-stop $bundle"
        DeviceKind.ANDROID -> "am force-stop $bundle"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("应用停止（应走 simctl terminate）")
    }

    fun foregroundCommand(kind: DeviceKind): String = when (kind) {
        DeviceKind.OHOS -> "aa dump -a"
        DeviceKind.ANDROID -> "dumpsys activity activities"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("前台应用查询")
    }

    /** 从「前台查询」输出里抽包名。 */
    fun foregroundBundlesIn(kind: DeviceKind, raw: String): List<String> = when (kind) {
        DeviceKind.OHOS -> DeviceOps.foregroundBundlesIn(raw)
        DeviceKind.ANDROID -> {
            // mResumedActivity: ActivityRecord{... com.pkg/.MainActivity ...}
            val a = Regex("""mResumedActivity[^\n]*?\s([a-zA-Z][\w.]*)/""").findAll(raw)
                .map { it.groupValues[1] }.filter { it.contains('.') }.distinct().toList()
            if (a.isNotEmpty()) a else {
                Regex("""topResumedActivity[^\n]*?\s([a-zA-Z][\w.]*)/""").findAll(raw)
                    .map { it.groupValues[1] }.filter { it.contains('.') }.distinct().toList()
            }
        }
        // 没有前台查询通道，返回空让调用方按「未知」处理，而不是抛错打断整个流程
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> emptyList()
    }

    // ---------- 输入 ----------

    fun tapCommand(kind: DeviceKind, x: Int, y: Int): String = when (kind) {
        DeviceKind.OHOS -> "uitest uiInput click $x $y"
        DeviceKind.ANDROID -> "input tap $x $y"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("触摸点击")
    }

    fun swipeCommand(kind: DeviceKind, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): String = when (kind) {
        DeviceKind.OHOS -> "uitest uiInput swipe $x1 $y1 $x2 $y2 $durationMs"
        DeviceKind.ANDROID -> "input swipe $x1 $y1 $x2 $y2 $durationMs"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("滑动")
    }

    fun keyCommand(kind: DeviceKind, code: String): String = when (kind) {
        DeviceKind.OHOS -> "uitest uiInput keyEvent $code"
        DeviceKind.ANDROID -> "input keyevent $code"
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> iosUnsupported("按键注入")
    }
}
