package ohd.platform

import java.nio.file.Path
import java.nio.file.Paths

/**
 * JVM 侧平台实现：全部委托给 `java.nio.file`，保证与迁移前行为逐字节一致。
 *
 * 为什么不自己实现字符串路径运算：JVM 上已经有经过大量验证的 `Path.normalize()`，
 * 自己写一遍只会引入「Windows 盘符 / UNC 路径 / 保留名」这类差异。K/N 侧没有 Path 才必须自实现。
 */
internal actual object PlatformFs {
    actual val sep: String = java.io.File.separator

    actual fun cwd(): String = Paths.get("").toAbsolutePath().toString()

    actual fun isAbsoluteString(s: String): Boolean = runCatching { Paths.get(s).isAbsolute }.getOrDefault(false)

    actual fun normalizeString(s: String): String = runCatching { Paths.get(s).normalize().toString() }.getOrDefault(s)
}

/** JVM 专属：`OhdPath` ↔ `java.nio.file.Path`。图像/AWT 相关代码需要真 `Path` 时用这个。 */
fun OhdPath.toJavaPath(): Path = Paths.get(toString())

/** JVM 专属：从既有 `Path` 构造（迁移期过渡用，逐步消除）。 */
fun ofJavaPath(p: Path): OhdPath = OhdPath.of(p.toString())
