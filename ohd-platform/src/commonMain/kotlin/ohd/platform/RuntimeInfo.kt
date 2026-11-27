package ohd.platform

/**
 * 当前宿主运行时信息。
 *
 * ## 为什么需要抽象
 *
 * 仓库里有 13 处 `System.getProperty("java.class.path" / "java.home" / "java.version")`，
 * 用途全是**为了再起一个 JVM 子进程**（`ohd log` 的日志泵、`LogWatchdog` 的看守、
 * `Self` 的自我重启）。
 *
 * 这些概念在 K/N 上根本不存在——原生可执行没有 classpath，也没有 `java.home`。
 * 所以不是「换个写法」，而是要让**调用方知道这条路在当前运行时不可用**。
 *
 * ## 语义
 *
 * - JVM：返回真实值
 * - K/N：`classpath` / `javaHome` 返回 null，`isJvm = false`，
 *   调用方据此走降级路径（例如「当前运行时无法派生 JVM 子进程」）
 */
object RuntimeInfo {

    /** 是否跑在 JVM 上。 */
    val isJvm: Boolean get() = PlatformRuntime.isJvm

    /** 进程 classpath；非 JVM 返回 null。 */
    val classpath: String? get() = PlatformRuntime.classpath

    /** `java.home`；非 JVM 返回 null。 */
    val javaHome: String? get() = PlatformRuntime.javaHome

    /** 运行时版本串（JVM 上是 `java.version`）；非 JVM 返回产品名与版本。 */
    val version: String get() = PlatformRuntime.version

    /** 当前可执行文件路径（`java` 或原生二进制的 argv[0]）。 */
    fun executablePath(): OhdPath? = PlatformRuntime.executablePath()
}

internal expect object PlatformRuntime {
    val isJvm: Boolean
    val classpath: String?
    val javaHome: String?
    val version: String
    fun executablePath(): OhdPath?
    fun writeStdout(text: String)
    fun isStdoutTty(): Boolean
    fun writeStderr(text: String)
}

/**
 * 直接写标准输出/错误。
 *
 * 为什么单独抽：`System.out`/`System.err` 是 JDK 独占，而仓库里有 8 处
 * 直接 `println` 到 stderr（诊断告警、超时提示）。这些**刻意绕过** `Out`
 * 抽象——它们要在 JSON 模式下也照常出现，不能被结构化输出吞掉。
 *
 * K/N 侧用 `printf` 写 stdout/stderr，语义一致。
 */
object Stdio {
    fun out(text: String) = PlatformRuntime.writeStdout(text)
    fun err(text: String) = PlatformRuntime.writeStderr(text)

    /** 写一行到 stderr（最常用）。 */
    fun errLine(text: String) = err(text + "\n")

    fun outLine(text: String) = out(text + "\n")

    /**
     * 是否连着交互式终端。
     *
     * 用途：决定是否上色（`System.console() != null` 的跨平台等价物）。
     * JVM 侧 `System.console()` 在多数 IDE/重定向下都为 null，所以这里改判
     * 「stdout 是 tty」——那才是「能不能显示颜色」的正确依据。
     */
    fun isInteractive(): Boolean = PlatformRuntime.isStdoutTty()
}
