package ohd.platform

/**
 * 子进程执行结果。
 *
 * 字段刻意与 `Hdc.Result` 对齐（stdout/stderr 分开、退出码、超时标记），
 * 这样 `Hdc`/`DeviceOps` 迁移时调用点只换执行器，不改取值逻辑。
 */
data class ProcOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMillis: Long,
    /** 进程根本没起来（如二进制不存在）。JVM 侧对应 `IOException`，这里不抛异常而是带标记返回。 */
    val spawnError: String? = null,
) {
    val ok: Boolean get() = spawnError == null && exitCode == 0 && !timedOut
}

/** 子进程执行规格。 */
data class ProcSpec(
    val command: List<String>,
    /** 喂给子进程 stdin 的内容；null 表示接 `/dev/null`（**不继承父进程 tty**，否则会挂住）。 */
    val stdinData: ByteArray? = null,
    val timeoutMillis: Long = 60_000,
    val env: Map<String, String>? = null,
    val workDir: String? = null,
)

/**
 * 执行外部命令。
 *
 * JVM 侧走 `ProcessBuilder`（与迁移前完全一致），K/N 侧走
 * `pipe` + `fork` + `dup2` + `execvp` + `poll` + `waitpid`。
 *
 * **K/N 实现的三条硬约束**（来自 spike 实测，违反即死锁或崩溃）：
 * 1. `fork` 之后子进程只能调 async-signal-safe 函数（`dup2/close/chdir/execvp/write/_exit`），
 *    绝不能碰 Kotlin 运行时（不能分配、不能 println、不能抛异常）——父进程可能持有运行时的锁；
 * 2. 必须用 `poll()` 同时监听 stdout/stderr/stdin，否则子进程写满 64KB 管道就阻塞、父进程在等另一条流 → 死锁；
 * 3. `waitpid` 要处理 `EINTR` 并自己解 wait status（`(st and 0x7f)==0` 才是正常退出）。
 */
expect fun runProcess(spec: ProcSpec): ProcOutcome

/**
 * 分离子进程的句柄：已启动、**不等待退出**。
 *
 * 为什么需要它：[runProcess] 会阻塞到子进程结束，但 `ohd log` 的日志泵、
 * `LogWatchdog` 看守这类是**故意要活到父进程之后**的后台进程，
 * 只需要拿到 pid 供之后 kill，不能等它。
 */
data class DetachedProcess(
    /** 操作系统 pid；取不到时 null。 */
    val pid: Long?,
    /** 启动失败原因；null 表示成功。 */
    val spawnError: String? = null,
) {
    val ok: Boolean get() = spawnError == null
}

/**
 * 启动一个**不等待**的子进程：stdout/stderr 丢弃，stdin 走管道并立即关闭。
 *
 * 输出丢弃是有意的：这类后台进程要么自己写文件，要么输出无意义；
 * 若接到父进程终端，进程组退出时会把终端一起挂住。stdin 立刻关掉则避免占着终端输入。
 *
 * **调用方负责**在不需要时按 [DetachedProcess.pid] 结束它。
 */
expect fun spawnDetached(
    command: List<String>,
    env: Map<String, String>? = null,
    workDir: String? = null,
): DetachedProcess

/**
 * 逐行消费子进程 stdout 的**流式**执行器。
 *
 * ## 为什么 `runProcess` 不够
 *
 * `runProcess` 会等进程结束才返回，适合「跑完看结果」。但 `ohd log` 的 hilog 泵是
 * **边跑边读**：进程可能跑几小时，输出要实时落到 sink，且靠文件大小判断是否该截断。
 * 把输出攒到最后再返回等于把长跑日志全塞进内存。
 *
 * ## 语义
 *
 * - [onLine] 在**调用线程之外**被逐行调用；stderr 已并入 stdout（与 hilog 一致）
 * - 返回的 [DetachedProcess] 指向**正在运行**的子进程，调用方负责 kill
 * - 空行不回调（与旧 `HilogStream` 的过滤一致）
 * - 回调抛异常不影响后续行，也不影响进程（泵线程自己兜住）
 *
 * ## K/N 的线程从哪来
 *
 * 实现内部起一个平台线程（JVM `Thread` / K/N `pthread`）。**不要**在 [onLine] 里
 * 假设自己跑在主线程——它是后台线程。
 */
/**
 * ⚠️ **K/N 侧暂未实现**（`actual` 缺失）。`ohd-core` 现在仍是纯 JVM 模块，
 * 这个 API 在 K/N 上还没有调用方；等 core 迁到 K/N 时再补。
 * JVM 侧实现完整可用，可直接替换 `HilogStream`/`RecordCap` 的 ProcessBuilder 用法。
 */
expect fun spawnStreaming(
    command: List<String>,
    env: Map<String, String>? = null,
    workDir: String? = null,
    onLine: (String) -> Unit,
): DetachedProcess

/**
 * 进程是否还活着。
 *
 * 用途：长跑采集（`video`/`record`/`log`）需要知道「我起的那个子进程还在吗」，
 * 好决定是继续采还是收尾。原实现用 `ProcessHandle.of(pid).map{isAlive}.orElse(false)`，
 * 那是 JDK 9+ 的 API，K/N 没有。
 *
 * [pid] 不存在、或已退出但未被回收（僵尸）都算「不活」。
 */
expect fun isProcessAlive(pid: Long): Boolean

/** 结束进程（SIGKILL）。失败返回 false，不抛异常。 */
expect fun killProcess(pid: Long): Boolean

/**
 * 列出 [pid] 的全部子孙进程 pid（不含自身）。
 *
 * 为什么必须有：只 kill 根进程不够——shell 拉起的后台子进程会被 init 收养继续跑，
 * 变成孤儿进程占着设备上的采集器。原实现靠 `ProcessHandle.descendants()`，
 * 那是 JDK 9+ 的 API，K/N 没有。
 *
 * K/N 侧走 `ps -eo pid,ppid` 解析（posix 没有可移植的「查子进程」接口）。
 * 拿不到时返回空 list，调用方仍会 kill 根进程——**降级但不失效**。
 */
expect fun listDescendantPids(pid: Long): List<Long>

/**
 * 注册一个**进程退出时**执行的清理动作（不可撤销）。
 *
 * ## 为什么需要它
 *
 * `ohd` 起的采集子进程（hilog 泵、录屏、看守）如果在 Ctrl-C 时不被回收，
 * 会变成孤儿继续占着设备。原实现在 6 个能力里各写一遍
 * `Runtime.getRuntime().addShutdownHook(Thread { ... })`——
 * `Thread` 与 `addShutdownHook` 都是 JVM 独占，K/N 没有。
 *
 * ## 语义
 *
 * - 正常退出、Ctrl-C（SIGINT）、SIGTERM 都会触发
 * - **不保证**在 SIGKILL 或机器断电时执行——这类场景下谁都无法兜底
 * - 多个动作按**注册顺序的逆序**执行（后注册的先清理，类似栈）
 * - 单个动作抛异常不影响其余动作
 *
 * ## K/N 侧怎么实现
 *
 * 用 `atexit` 覆盖正常退出；SIGINT/SIGTERM 由 [installSignalHandlers] 接管后
 * 先跑清理链再退出。
 */
expect fun onExitCleanup(action: () -> Unit)

/**
 * 安装 SIGINT / SIGTERM 处理器：收到信号时**先跑完退出清理链**，再按标准退出码退出。
 *
 * 为什么要显式装：K/N 上默认的信号行为是直接终止进程，不会走 `atexit`。
 * 而 `ohd` 的长跑命令（`log`/`video`/`record`）必须能在 Ctrl-C 时
 * 把已采数据落盘、把设备侧子进程收掉——否则就是「白跑 + 留下孤儿」。
 *
 * 幂等：重复调用只安装一次。
 */
expect fun installSignalHandlers()

/**
 * 以**继承父进程标准流**的方式运行子进程（`exec` 语义的前台交互）。
 *
 * ## 为什么单独一个入口
 *
 * `ohd ext --ext-raw` 要让交互式脚本（全屏 TUI、读键盘）直接用终端：
 * 子进程的 stdin/stdout/stderr 必须是**父进程的那三个 fd**，
 * 而不是管道。[runProcess] 会截获输出并等结束、[spawnStreaming] 把输出改成逐行回调，
 * 两者都拿不到这个语义。
 *
 * ## 与其它入口的差别
 *
 * - 输出**不经**我们解码：非法字节、ANSI 全屏控制序列都原样给终端
 * - 返回退出码；超时会尝试结束整棵子进程树
 * - 调用期间父进程不再读 stdin（已交给子进程）
 */
expect fun runProcessInheritIo(
    command: List<String>,
    env: Map<String, String>? = null,
    workDir: String? = null,
    timeoutMillis: Long = 0,
): ProcOutcome

/**
 * 请求进程**优雅退出**（POSIX `SIGTERM`，JVM `destroy()`）。
 *
 * 与 [killProcess]（`SIGKILL`，不可捕获、不给收尾机会）的区别是实际的：
 * 设备侧的 `screenrecord` / `hilog` 收到 SIGTERM 会**写完缓冲再退**，
 * 直接 SIGKILL 会丢尾部数据。所以收尾流程都是先 term、等一会儿、再 kill。
 */
expect fun terminateProcess(pid: Long): Boolean

/**
 * 装一个 **Ctrl-C / SIGTERM 拦截器**：收到信号时执行 [block]，**进程不退出**。
 *
 * 与 [onExitCleanup] 的关键区别：
 * - `onExitCleanup`：进程**正在退出**，只给它最后机会清理
 * - `onInterrupt`：进程**继续活着**，调用方能在信号后继续干活
 *
 * `ohd record` / `ohd video` 靠的正是后者——Ctrl-C 只结束录制，
 * 之后还要把已录内容写成回放脚本、打印统计。若用 onExitCleanup，那些代码永远跑不到。
 *
 * JVM 侧走 `sun.misc.Signal`（反射，因为它在模块系统下不总是可编译引用）；
 * K/N 侧走 `signal(3)`：handler 里只置标志 + 唤醒主循环，真正的 [block] 在主流程里跑
 * （信号处理器里跑 Kotlin 代码是 async-signal-unsafe）。
 */
expect fun onInterrupt(block: () -> Unit)
