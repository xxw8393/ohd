package ohd.platform

/**
 * 宿主环境：仓库里 `System.getenv` 13 处、`System.getProperty` 19 处、
 * `System.currentTimeMillis` 200+ 处的唯一出口。
 *
 * 为什么单独抽：这些调用在 K/N 上分别是 `getenv` / 编译期常量 / posix 时钟，
 * 散在各处会让「JVM 与 K/N 行为是否一致」无法用一个测试兜住。
 */
object Env {
    /** 读环境变量；空字符串按「未设置」处理（与现有 `takeIf { it.isNotBlank() }` 惯例一致）。 */
    fun get(name: String): String? = PlatformEnv.getenv(name)?.takeIf { it.isNotBlank() }

    /** 原样读（不把空串当 null），用于「显式设为空」有语义的场景。 */
    fun getRaw(name: String): String? = PlatformEnv.getenv(name)

    /** 用户主目录的绝对路径。 */
    val home: OhdPath get() = PlatformEnv.home()

    /** 当前工作目录的绝对路径。 */
    val cwd: OhdPath get() = PlatformEnv.cwdPath()

    /** 路径分隔符（`:` / `;`），用于切分 `PATH`、`OHD_EXT` 这类多值变量。 */
    val pathSeparator: String get() = PlatformEnv.pathSeparator

    /** 毫秒时间戳。 */
    fun nowMillis(): Long = PlatformEnv.nowMillis()

    /** 单调时钟（纳秒）：超时判定用，不受系统时间调整影响。 */
    fun monotonicNanos(): Long = PlatformEnv.monotonicNanos()

    /** 平台名：`macOS` / `Windows` / `Linux` / `unknown`。 */
    val osName: String get() = PlatformEnv.osName()

    val isMac: Boolean get() = osName == "macOS"
    val isWindows: Boolean get() = osName == "Windows"
    val isLinux: Boolean get() = osName == "Linux"

    /** 可执行文件后缀（Windows 为 `.exe`）。 */
    val exeSuffix: String get() = if (isWindows) ".exe" else ""

    val arch: String get() = PlatformEnv.arch()

    /**
     * 睡 [millis] 毫秒。
     *
     * 替代 `Thread.sleep`：K/N **没有** `Thread`（已实测 `Unresolved reference 'Thread'`），
     * 而仓库里有约 50 处 `Thread.sleep`，绝大多数是「等设备响应」的固定间隔。
     * 抽到这里后两端各有实现，调用点不必再关心平台。
     */
    fun sleepMillis(millis: Long) = PlatformEnv.sleepMillis(millis)

    /** 本地时区相对 UTC 的偏移秒数（东八区 = 28800）。 */
    fun timezoneOffsetSeconds(): Int = PlatformEnv.timezoneOffsetSeconds()
}

internal expect object PlatformEnv {
    fun getenv(name: String): String?
    fun home(): OhdPath
    fun cwdPath(): OhdPath
    val pathSeparator: String
    fun nowMillis(): Long
    fun monotonicNanos(): Long
    fun osName(): String
    fun arch(): String

    /** 见 [Env.sleepMillis]。负数与 0 直接返回，不报错。 */
    fun sleepMillis(millis: Long)

    /** 本地时区相对 UTC 的偏移秒数（东八区 = 28800）。供 [TimeFmt] 用。 */
    fun timezoneOffsetSeconds(): Int

    /**
     * 短暂让出 CPU（纳秒级）。供 [Mutex] 自旋时退避用。
     *
     * JVM 侧是 `Thread.yield()` 提示；K/N 没有等价物，退化为极短的 `nanosleep`。
     */
    fun pauseNanos(nanos: Long)

    /** 结束指定进程（SIGKILL）。失败返回 false，不抛异常。 */
    fun killPid(pid: Long): Boolean
}
