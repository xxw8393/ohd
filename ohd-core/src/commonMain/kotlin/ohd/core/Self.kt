package ohd.core

import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env
import ohd.platform.RuntimeInfo

/**
 * ohd 自身的启动方式——并行多设备时要 fork 出独立子进程。
 *
 * 为什么用子进程而不是线程：每个设备的 monkey 是分钟级长任务，且共享大量进程内状态
 * （输出、会话目录、配置缓存）。一个设备卡住、OOM 或异常退出时，线程模型会波及全部；
 * 子进程天然隔离，退出码与输出也各自独立汇总。
 *
 * 优先用发行包入口（`<root>/bin/ohd` 或 `<root>/ohd`），回退到 `java -cp ... ohd.cli.MainKt`。
 */
object Self {

    /** 发行包入口脚本/可执行文件。 */
    fun command(): String? = runCatching {
        val cp = RuntimeInfo.classpath ?: return null
        val first = cp.split(Env.pathSeparator).firstOrNull() ?: return null
        val root = OhdPath.of(first).toAbsolutePath().parent?.parent ?: return null
        listOf(root.resolve("bin").resolve("ohd"), root.resolve("ohd"))
            .firstOrNull { fs.isExecutable(it) }
            ?.toString()
    }.getOrNull() ?: Env.getRaw("OHD_SELF")?.takeIf { it.isNotBlank() }

    /** 子进程启动前缀（不含能力名与参数）；拿不到返回 null。 */
    fun launchArgv(): List<String>? {
        command()?.let { return listOf(it) }
        val javaHome = RuntimeInfo.javaHome ?: return null
        val java = OhdPath.of(javaHome, "bin", if (Platform.isWindows) "java.exe" else "java")
        if (!fs.isExecutable(java)) return null
        val cp = RuntimeInfo.classpath ?: return null
        return listOf(java.toString(), "-cp", cp, "ohd.cli.MainKt")
    }

    /** 把设备序列号变成安全的目录/文件名片段。 */
    fun safeLabel(label: String?): String? = label
        ?.takeIf { it.isNotBlank() }
        ?.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        ?.joinToString("")
        ?.take(24)
        ?.takeIf { it.isNotBlank() }
}
