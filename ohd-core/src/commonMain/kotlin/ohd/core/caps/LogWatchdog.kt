package ohd.core.caps

import ohd.core.Platform
import ohd.core.ProcessTrees
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.spawnDetached
import ohd.platform.isProcessAlive
import ohd.platform.RuntimeInfo

/**
 * `ohd log start` 返回后，本进程已经退出，hdc 写 hilog 的子进程还在。
 * 大小上限因此不能靠调用方线程，得有一个独立进程盯着文件，到顶就砍掉整棵进程树。
 *
 * 入口：`java -cp <ohd> ohd.core.caps.LogWatchdog <pid> <file> <maxBytes>`
 */
object LogWatchdog {
    /** 2GiB。0 表示不限。 */
    const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024

    enum class Action { CONTINUE, STOPPED_SIZE, STOPPED_DEAD }

    fun parseSize(raw: String?): Long {
        if (raw.isNullOrBlank()) return DEFAULT_MAX_BYTES
        val t = raw.trim().lowercase()
        if (t == "0") return 0L
        val m = Regex("""^(\d+)\s*([kmg]?)b?$""").find(t)
            ?: throw IllegalArgumentException(raw)
        val n = m.groupValues[1].toLong()
        val mul = when (m.groupValues[2]) {
            "k" -> 1024L
            "m" -> 1024L * 1024
            "g" -> 1024L * 1024 * 1024
            else -> 1L
        }
        return n * mul
    }

    fun exceeded(size: Long, maxBytes: Long): Boolean = maxBytes > 0 && size >= maxBytes

    fun marker(file: OhdPath): OhdPath = file.resolveSibling(file.fileName.toString() + ".capped")

    fun format(bytes: Long): String = when {
        bytes <= 0L -> "不限"
        bytes % (1024L * 1024 * 1024) == 0L -> "${bytes / (1024L * 1024 * 1024)}G"
        bytes % (1024L * 1024) == 0L -> "${bytes / (1024L * 1024)}M"
        bytes % 1024L == 0L -> "${bytes / 1024L}K"
        else -> "${bytes}B"
    }

    /**
     * 看一眼：进程没了就停；文件到顶就砍掉该 pid 的整棵树并写标记。
     * 不限大小（maxBytes <= 0）时只观察进程是否还在。
     */
    fun poll(pid: Long, file: OhdPath, maxBytes: Long): Action {
        if (!isProcessAlive(pid)) return Action.STOPPED_DEAD
        val size = runCatching { fs.size(file) }.getOrDefault(0L)
        if (!exceeded(size, maxBytes)) return Action.CONTINUE
        ProcessTrees.kill(pid)
        runCatching {
            fs.writeString(marker(file), "capped size=$size max=$maxBytes\n")
        }
        return Action.STOPPED_SIZE
    }

    /**
     * 看守进程的 JVM 主类名。只用于「再起一个 JVM」这条路，
     * 非 JVM 运行时走不到（`executablePath()` 返回 null 已提前降级）。
     */
    private const val RUNTIME_ENTRY = "ohd.core.caps.LogWatchdogMain"

    /** 拉起看守进程。不限大小时不需要，返回 null。失败也返回 null，由调用方告警。 */
    fun spawn(targetPid: Long, file: OhdPath, maxBytes: Long): Long? {
        if (maxBytes <= 0L) return null
        // 看守进程要「再起一个 ohd」：非 JVM 运行时（K/N）没有 java，直接降级。
        val javaBin = RuntimeInfo.executablePath() ?: return null
        val cp = RuntimeInfo.classpath ?: return null
        // 看守进程与父进程无关：输出一律丢弃、stdin 自动关闭、脱离终端。
        val d = spawnDetached(
            listOf(
                javaBin.toString(),
                "-cp", cp,
                RUNTIME_ENTRY,
                targetPid.toString(),
                file.toAbsolutePath().toString(),
                maxBytes.toString(),
            ),
        )
        // 起不来就是没有看守，调用方按「guardPid == null」告警，与旧逻辑一致。
        return d.pid
    }

    /**
     * 看守进程的**入口**：由 `spawn` 以「再起一个自己」的方式调用。
     *
     * 平台无关的轮询体在这里，JVM 侧的 `main`（需要 `@JvmStatic` 供反射调用）
     * 在 jvmMain 转发到它。K/N 侧没有「再起 JVM」这回事，
     * `RuntimeInfo.executablePath()` 返回 null → `spawn` 直接降级，不会走到这里。
     */
    fun runGuard(args: List<String>) {
        if (args.size != 3) return
        val pid = args[0].toLongOrNull() ?: return
        val file = OhdPath.of(args[1])
        val maxBytes = args[2].toLongOrNull() ?: return
        while (true) {
            when (poll(pid, file, maxBytes)) {
                Action.CONTINUE -> Env.sleepMillis(1_000)
                else -> return
            }
        }
    }
}
