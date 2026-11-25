package ohd.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
     * 源码里允许出现 `ProcessBuilder(` 的文件与次数。
     * 新增调用点必须同时更新这里，否则 `--platform-audit` 会报未登记。
     */
    val EXPECTED_SITES: Map<String, Int> = mapOf(
        "Hdc.kt" to 1,            // hdc / adb
        "HilogStream.kt" to 1,    // hdc shell hilog
        "LogWatchdog.kt" to 1,    // java 看门狗
        "LogCap.kt" to 1,         // 日志泵子进程（ohd 自身）
        "RecordCap.kt" to 1,      // hdc uitest uiRecord
        "DoctorCap.kt" to 1,      // llvm --version
        "Symbolizer.kt" to 1,     // llvm-addr2line
        "Extensions.kt" to 1,     // 用户扩展
        "Main.kt" to 1,           // ohd 并行子进程
        "MemCap.kt" to 1,         // footprint（iOS 模拟器内存）
        "TouchInjector.kt" to 1,  // idb（iOS 触摸注入）
    )

    /**
     * 从 jar 路径向上找**所有模块**的源码根（core + cli）。
     * 只看 ohd-core 会漏掉 Main.kt 里的并行子进程启动点。发布包没有源码时返回空。
     */
    fun locateSourceRoots(): List<Path> {
        val cp = System.getProperty("java.class.path") ?: return emptyList()
        var p: Path? = cp.split(java.io.File.pathSeparator)
            .firstOrNull()
            ?.let { runCatching { Paths.get(it).toAbsolutePath() }.getOrNull() }
        var guard = 0
        while (p != null && guard++ < 8) {
            val roots = listOf(
                p.resolve("ohd-core/src/main/kotlin"),
                p.resolve("ohd-cli/src/main/kotlin"),
            ).filter { Files.isDirectory(it) }
            if (roots.isNotEmpty()) return roots
            p = p.parent
        }
        return emptyList()
    }
}
