package ohd.platform

/**
 * 宿主平台的最小抽象：只暴露 `OhdPath` 与 `Fs` 需要的那几件事。
 *
 * JVM 侧刻意走 `java.nio.file` / `System`（不自己实现），保证与现状行为逐字节一致；
 * K/N 侧才用 posix。
 */
internal expect object PlatformFs {
    /** 路径分隔符：mac/Linux `/`，Windows `\`。 */
    val sep: String

    /** 当前工作目录（绝对路径字符串）。 */
    fun cwd(): String

    fun isAbsoluteString(s: String): Boolean

    /** 规范化：消 `.`、折叠 `..`、去重复分隔符，保留根。 */
    fun normalizeString(s: String): String
}
