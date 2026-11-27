package ohd.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.PATH_MAX
import platform.posix.getcwd

/**
 * K/N 侧运行时信息。
 *
 * classpath / javaHome 在原生可执行里没有对应概念，返回 null——
 * 调用方（`Self`、`LogWatchdog`、`Extensions`）据此走降级路径，
 * 而不是拿着空串去拼一个不存在的路径。
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object PlatformRuntime {
    actual val isJvm: Boolean = false

    actual val classpath: String? = null

    actual val javaHome: String? = null

    actual val version: String = "native"

    actual fun executablePath(): OhdPath? =
        // /proc/self/exe 在 Linux 有，macOS 没有；这里用 argv[0] 不可靠，
        // 直接返回 null 让调用方降级（K/N 侧不需要「再起一个自己」）。
        null

    actual fun isStdoutTty(): Boolean =
        // isatty(1)：标准输出的 fd 是否连着终端
        platform.posix.isatty(1) == 1

    actual fun writeStdout(text: String) = writeFd(1, text)

    actual fun writeStderr(text: String) = writeFd(2, text)

    /** 直接写 fd：K/N 侧没有 System.out 的缓冲层，一次性把整段写出去。 */
    private fun writeFd(fd: Int, text: String) {
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        bytes.usePinned { pinned ->
            var off = 0
            while (off < bytes.size) {
                // write 可能短写（尤其管道），循环到写完
                val n = platform.posix.write(
                    fd,
                    pinned.addressOf(off),
                    (bytes.size - off).convert(),
                ).toInt()
                if (n <= 0) break
                off += n
            }
        }
    }
}
