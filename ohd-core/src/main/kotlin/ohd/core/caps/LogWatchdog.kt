package ohd.core.caps

import ohd.core.Platform
import ohd.core.ProcessTrees
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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

    fun marker(file: Path): Path = file.resolveSibling(file.fileName.toString() + ".capped")

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
    fun poll(pid: Long, file: Path, maxBytes: Long): Action {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return Action.STOPPED_DEAD
        if (!handle.isAlive) return Action.STOPPED_DEAD
        val size = runCatching { Files.size(file) }.getOrDefault(0L)
        if (!exceeded(size, maxBytes)) return Action.CONTINUE
        ProcessTrees.kill(pid)
        runCatching {
            Files.writeString(marker(file), "capped size=$size max=$maxBytes\n")
        }
        return Action.STOPPED_SIZE
    }

    /** 拉起看守进程。不限大小时不需要，返回 null。失败也返回 null，由调用方告警。 */
    fun spawn(targetPid: Long, file: Path, maxBytes: Long): Long? {
        if (maxBytes <= 0L) return null
        val javaBin = Paths.get(System.getProperty("java.home"), "bin", "java" + Platform.exeSuffix)
        val cp = System.getProperty("java.class.path") ?: return null
        val pb = ProcessBuilder(
            javaBin.toString(),
            "-cp", cp,
            LogWatchdog::class.java.name,
            targetPid.toString(),
            file.toAbsolutePath().toString(),
            maxBytes.toString(),
        )
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        // DISCARD 是 WRITE，不能拿来当 stdin。用管道并立刻关掉，避免看守占着终端输入。
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        return runCatching {
            val child = pb.start()
            runCatching { child.outputStream.close() }
            child.pid()
        }.getOrNull()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) return
        val pid = args[0].toLongOrNull() ?: return
        val file = Paths.get(args[1])
        val maxBytes = args[2].toLongOrNull() ?: return
        while (true) {
            when (poll(pid, file, maxBytes)) {
                Action.CONTINUE -> Thread.sleep(1_000)
                else -> return
            }
        }
    }
}
