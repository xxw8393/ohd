package ohd.core

import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env
import ohd.platform.RuntimeInfo

/**
 * 宿主侧允许启动的外部命令——**唯一真源**。
 *
 * 与「设备端命令」的区分很重要：`hdc shell("df -h")` 里的 `df/grep/ps` 跑在设备上，
 * 与宿主是 macOS/Windows/Linux 无关；本对象只管**宿主进程**。
 *
 * `ohd selftest --platform-audit` 会拿这份清单去比对源码里 `ProcessBuilder(` 的调用点，
 * 新增宿主命令却没在这里登记 => 审计失败。
 */
object HostCommands {

    data class Entry(val name: String, val purpose: String)

    val ALL: List<Entry> = listOf(
        Entry("hdc", "OHOS 设备通道"),
        Entry("adb", "Android 设备通道"),
        Entry("simctl", "iOS 模拟器通道"),
        Entry("footprint", "iOS 模拟器进程内存采样（模拟器内没有 ps）"),
        Entry("java", "Java 扩展 / 日志看门狗 / 并行子进程"),
        Entry("llvm-addr2line", "栈符号化"),
        Entry("llvm-symbolizer", "栈符号化（备用）"),
        Entry("ohd", "自身：多设备并行子进程 / 日志泵"),
    )

    private val ALLOWED: Set<String> = buildSet {
        ALL.forEach { add(it.name) }
        // Windows 与发行包入口的同义名
        addAll(listOf("hdc.exe", "adb.exe", "java.exe", "ohd.bat", "ohd.exe"))
    }

    /** 取可执行文件 basename，去掉目录与 Windows 反斜杠。 */
    fun basenameOf(command: String): String =
        command.substringAfterLast('/').substringAfterLast('\\')

    /**
     * 是否属于内置白名单。
     * 用户扩展不在此列——它由 `Extensions.exec` 单独认定为允许（扩展就是 ohd 的扩展点）。
     */
    fun isAllowed(basename: String): Boolean = basename.lowercase() in ALLOWED

    /**
     * 源码里允许出现「起宿主进程」调用的文件与次数。
     *
     * **四个入口一起登记**：`ProcessBuilder(` / `runProcess(` / `spawnDetached(` /
     * `spawnStreaming(`（见 `SelftestCap.SPAWN_APIS`）。只盯 `ProcessBuilder(` 是不够的——
     * 迁移到平台层之后，绝大多数进程都由后三个拉起，只认前者等于护栏失效。
     *
     * 新增任何一处宿主进程启动，都必须在这里登记用途，否则 `--platform-audit` 会报未登记。
     *
     * 仍直接用 JDK 原生 `ProcessBuilder` 的两处（有明确理由）：
     * - `Extensions.kt`：`--ext-raw` 要 `inheritIO()`，让子进程继承父进程 stdin/stdout/stderr；
     *   平台层的 `runProcess` 会截获输出、`spawnStreaming` 改成逐行回调，都拿不到这个语义。
     * - `Main.kt`：CDS 探针与并行子进程需要持有 `Process` 句柄（读流 + 按需强杀整棵子孙树）。
     */
    val EXPECTED_SITES: Map<String, Int> = mapOf(
        // --- 仍用 JDK 原生 ProcessBuilder ---
        "Extensions.kt" to 1,     // 用户扩展（raw 模式继承父进程 IO）
        "Main.kt" to 2,           // CDS 探针 + ohd 并行子进程

        // --- 已走 ohd.platform（runProcess / spawnDetached / spawnStreaming）---
        "DoctorCap.kt" to 1,      // llvm --version 探测
        "Hdc.kt" to 1,            // 所有 hdc 命令的唯一出口
        "HilogStream.kt" to 1,    // hilog 流式泵（spawnStreaming）
        "LogCap.kt" to 1,         // 日志泵（spawnDetached）
        "LogWatchdog.kt" to 1,    // 日志看守进程（spawnDetached）
        "MemCap.kt" to 1,         // hidumper --mem
        "RecordCap.kt" to 1,      // 录屏边跑边读（spawnStreaming）
        "Symbolizer.kt" to 1,     // llvm-symbolizer
        "TouchInjector.kt" to 1,  // idb 注入
        "VideoCap.kt" to 4,       // kill -INT ×2 + 录屏 + runTool
    )

    /**
     * 从 jar 路径向上找**所有模块**的源码根（core + cli）。
     * 只看 ohd-core 会漏掉 Main.kt 里的并行子进程启动点。发布包没有源码时返回空。
     */
    fun locateSourceRoots(): List<OhdPath> {
        val cp = RuntimeInfo.classpath ?: return emptyList()
        var p: OhdPath? = cp.split(Env.pathSeparator)
            .firstOrNull()
            ?.let { runCatching { OhdPath.of(it).toAbsolutePath() }.getOrNull() }
        var guard = 0
        while (p != null && guard++ < 8) {
            val roots = listOf(
                p.resolve("ohd-core/src/main/kotlin"),
                p.resolve("ohd-cli/src/main/kotlin"),
            ).filter { fs.isDirectory(it) }
            if (roots.isNotEmpty()) return roots
            p = p.parent
        }
        return emptyList()
    }
}
