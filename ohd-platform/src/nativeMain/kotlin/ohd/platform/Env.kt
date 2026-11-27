@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ohd.platform

import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.free
import platform.posix.getcwd
import platform.posix.getenv
import platform.posix.kill
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm
import platform.posix.nanosleep
import platform.posix.time
import platform.posix.timespec
import platform.posix.uname
import platform.posix.utsname

/**
 * K/N 侧环境实现：posix。
 *
 * JVM 有、K/N 没有的对应关系：
 * - `System.getProperty("user.home")` → `getenv("HOME")`
 * - `System.getProperty("os.name")`   → `uname().sysname`（**运行时判断**，不能写死常量：
 *   同一份 nativeMain 代码会被 macosArm64 与 linuxX64 两个 target 编译）
 * - `System.currentTimeMillis()`      → `time()`
 * - 单调时钟 → `clock_gettime(CLOCK_MONOTONIC)`（超时不能用墙钟，系统时间跳变会失真）
 */
internal actual object PlatformEnv {
    actual fun getenv(name: String): String? = platform.posix.getenv(name)?.toKString()

    actual fun home(): OhdPath = OhdPath.of(getenv("HOME") ?: ".")

    actual fun cwdPath(): OhdPath {
        val buf = getcwd(null, 0u) ?: return OhdPath.of(".")
        val s = buf.toKString()
        free(buf)
        return OhdPath.of(s)
    }

    actual val pathSeparator: String = ":"

    actual fun nowMillis(): Long = time(null) * 1000L

    actual fun monotonicNanos(): Long = memScoped {
        val ts = alloc<timespec>()
        clock_gettime(CLOCK_MONOTONIC.convert(), ts.ptr)
        ts.tv_sec * 1_000_000_000L + ts.tv_nsec
    }

    actual fun osName(): String = memScoped {
        val u = alloc<utsname>()
        if (uname(u.ptr) != 0) return@memScoped "unknown"
        when (u.sysname.toKString().lowercase()) {
            "darwin" -> "macOS"
            "linux" -> "Linux"
            else -> "unknown"
        }
    }

    actual fun arch(): String = memScoped {
        val u = alloc<utsname>()
        if (uname(u.ptr) != 0) return@memScoped "unknown"
        // uname 的 machine 是 `arm64`/`aarch64`/`x86_64`，统一成 Kotlin 惯例
        when (u.machine.toKString()) {
            "arm64", "aarch64" -> "aarch64"
            "x86_64", "amd64" -> "x86_64"
            else -> u.machine.toKString()
        }
    }

    actual fun timezoneOffsetSeconds(): Int = memScoped {
        // localtime_r 把 epoch 拆成本地时间，tm_gmtoff 就是相对 UTC 的偏移秒数（含 DST）。
        val now = alloc<time_tVar>()
        time(now.ptr)
        val tm = alloc<tm>()
        if (localtime_r(now.ptr, tm.ptr) == null) return@memScoped 0
        tm.tm_gmtoff.toInt()
    }

    actual fun killPid(pid: Long): Boolean =
        kill(pid.convert(), platform.posix.SIGKILL) == 0

    actual fun pauseNanos(nanos: Long) {
        if (nanos <= 0) return
        // K/N 没有 yield(2) 的可移植封装；用极短 nanosleep 让出调度器即可。
        // 50μs 远小于典型临界区，自旋一两轮就能进锁。
        memScoped {
            val req = alloc<timespec>()
            req.tv_sec = 0
            req.tv_nsec = nanos.convert()
            nanosleep(req.ptr, null)
        }
    }

    actual fun sleepMillis(millis: Long) {
        if (millis <= 0) return
        memScoped {
            // nanosleep 会被信号打断（返回 EINTR）并提前返回，剩余时间在 rem 里。
            // 必须循环续睡，否则「等 500ms」可能只睡了 3ms —— 那会让所有
            // 「等设备响应」的间隔静默失效，是最难查的一类 flaky。
            val req = alloc<timespec>()
            req.tv_sec = (millis / 1000).convert()
            req.tv_nsec = ((millis % 1000) * 1_000_000).convert()
            val rem = alloc<timespec>()
            while (nanosleep(req.ptr, rem.ptr) != 0) {
                if (errno != EINTR) return@memScoped
                req.tv_sec = rem.tv_sec
                req.tv_nsec = rem.tv_nsec
            }
        }
    }
}
