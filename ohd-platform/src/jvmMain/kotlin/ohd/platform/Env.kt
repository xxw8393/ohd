package ohd.platform

import java.util.Locale

/** JVM 侧环境实现：全部走 `System`，与迁移前完全同义。 */
internal actual object PlatformEnv {
    actual fun getenv(name: String): String? = System.getenv(name)

    actual fun home(): OhdPath = OhdPath.of(System.getProperty("user.home", ".")).toAbsolutePath()

    actual fun cwdPath(): OhdPath = OhdPath.of(PlatformFs.cwd())

    actual val pathSeparator: String = java.io.File.pathSeparator

    actual fun nowMillis(): Long = System.currentTimeMillis()

    actual fun monotonicNanos(): Long = System.nanoTime()

    actual fun osName(): String {
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        return when {
            os.contains("mac") -> "macOS"
            os.contains("win") -> "Windows"
            os.contains("nux") || os.contains("nix") -> "Linux"
            else -> "unknown"
        }
    }

    actual fun arch(): String = System.getProperty("os.arch", "unknown")

    actual fun timezoneOffsetSeconds(): Int =
        // 取当前时刻的偏移：进程生命周期内不会跨 DST（见 TimeFmt 的类文档）
        java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000

    actual fun killPid(pid: Long): Boolean = runCatching {
        ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
        true
    }.getOrDefault(false)

    actual fun pauseNanos(nanos: Long) {
        if (nanos <= 0) return
        Thread.yield()
    }

    actual fun sleepMillis(millis: Long) {
        // 负数/0 直接返回：调用点常写 `sleep(ms)` 且 ms 可能是算出来的 0
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            // 被中断就当睡够了：这里是「等设备响应」的间隔，不是关键同步点
            Thread.currentThread().interrupt()
        }
    }
}
