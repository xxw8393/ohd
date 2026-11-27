package ohd.platform

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JVM 侧子进程执行：`ProcessBuilder`，与迁移前行为逐字节一致。
 *
 * 关键点（与旧实现保持同义，不要「顺手优化」）：
 * - `redirectErrorStream(false)`：stdout/stderr 必须分开，`Hdc` 依赖这一点区分正常输出与 hdc 的告警；
 * - stdout/stderr 各起一个读线程：单线程顺序读会在另一条流写满时死锁；
 * - 超时后 `destroyForcibly()` 并 `waitFor`，避免留下孤儿。
 */
actual fun runProcess(spec: ProcSpec): ProcOutcome {
    require(spec.command.isNotEmpty()) { "command is empty" }
    val startedAt = System.currentTimeMillis()

    val pb = ProcessBuilder(spec.command).redirectErrorStream(false)
    spec.env?.let { pb.environment().putAll(it) }
    spec.workDir?.let { pb.directory(java.io.File(it)) }

    val proc = try {
        pb.start()
    } catch (e: IOException) {
        return ProcOutcome(
            exitCode = -1,
            stdout = "",
            stderr = "",
            timedOut = false,
            durationMillis = System.currentTimeMillis() - startedAt,
            spawnError = e.message ?: e.toString(),
        )
    }

    val outBuf = ByteArrayOutputStream()
    val errBuf = ByteArrayOutputStream()
    val outThread = Thread { runCatching { proc.inputStream.use { it.copyTo(outBuf) } } }.apply {
        isDaemon = true
        start()
    }
    val errThread = Thread { runCatching { proc.errorStream.use { it.copyTo(errBuf) } } }.apply {
        isDaemon = true
        start()
    }

    if (spec.stdinData != null) {
        runCatching {
            proc.outputStream.use { it.write(spec.stdinData) }
        }
    } else {
        runCatching { proc.outputStream.close() }
    }

    val finished = proc.waitFor(spec.timeoutMillis, TimeUnit.MILLISECONDS)
    var timedOut = false
    if (!finished) {
        timedOut = true
        proc.destroyForcibly()
        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
    }
    // 流读完再取值：避免进程已退出但管道里还有残留（大输出时必然发生）
    runCatching { outThread.join(1_000) }
    runCatching { errThread.join(1_000) }

    return ProcOutcome(
        exitCode = if (timedOut) 137 else proc.exitValue(),
        stdout = outBuf.toString(Charsets.UTF_8),
        stderr = errBuf.toString(Charsets.UTF_8),
        timedOut = timedOut,
        durationMillis = System.currentTimeMillis() - startedAt,
    )
}

actual fun spawnDetached(
    command: List<String>,
    env: Map<String, String>?,
    workDir: String?,
): DetachedProcess {
    // 空命令返回错误对象而非抛异常：与 K/N 实现保持一致，
    // 否则同一段调用方代码在两端行为分叉（那是跨平台包装最典型的坑）。
    if (command.isEmpty()) return DetachedProcess(pid = null, spawnError = "command is empty")
    return try {
        val pb = ProcessBuilder(command)
        env?.let { pb.environment().putAll(it) }
        workDir?.let { pb.directory(java.io.File(it)) }
        // 丢弃输出：后台进程不该占着父进程的终端
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        // stdin 用管道并立刻关掉（DISCARD 是 WRITE，不能拿来当 stdin）
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        val child = pb.start()
        runCatching { child.outputStream.close() }
        DetachedProcess(pid = child.pid())
    } catch (e: Throwable) {
        DetachedProcess(pid = null, spawnError = e.message ?: e::class.simpleName)
    }
}

actual fun spawnStreaming(
    command: List<String>,
    env: Map<String, String>?,
    workDir: String?,
    onLine: (String) -> Unit,
): DetachedProcess {
    if (command.isEmpty()) return DetachedProcess(pid = null, spawnError = "command is empty")
    return try {
        val pb = ProcessBuilder(command)
        env?.let { pb.environment().putAll(it) }
        workDir?.let { pb.directory(java.io.File(it)) }
        // hilog 把诊断信息也写 stderr，必须合并，否则会漏行
        pb.redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.PIPE)
        val child = pb.start()
        runCatching { child.outputStream.close() }
        // 用协程而非裸 Thread：KMP 上这是唯一可移植的后台执行手段
        Bg.launch {
            try {
                child.inputStream.bufferedReader().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trimEnd('\r')
                        // 空行不回调：与旧 HilogStream 的过滤一致，避免 sink 里全是空行
                        if (line.isEmpty()) continue
                        try {
                            onLine(line)
                        } catch (_: Throwable) {
                            // 单行回调失败不该中断整条流
                        }
                    }
                }
            } catch (_: Throwable) {
                // 进程被 kill / 管道断开都属于正常收尾
            }
        }
        DetachedProcess(pid = child.pid())
    } catch (e: Throwable) {
        DetachedProcess(pid = null, spawnError = e.message ?: e::class.simpleName)
    }
}

actual fun isProcessAlive(pid: Long): Boolean =
    runCatching { ProcessHandle.of(pid).map { it.isAlive }.orElse(false) }.getOrDefault(false)

actual fun killProcess(pid: Long): Boolean = runCatching {
    ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
    true
}.getOrDefault(false)

actual fun listDescendantPids(pid: Long): List<Long> = runCatching {
    val root = ProcessHandle.of(pid).orElse(null) ?: return@runCatching emptyList()
    root.descendants().map { it.pid() }.toList()
}.getOrDefault(emptyList())

/** JVM 侧：一个全局 hook，内部维护清理链（逆序执行）。 */
private object ExitHooks {
    private val actions = java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())
    @Volatile private var installed = false

    fun add(action: () -> Unit) {
        actions.add(action)
        if (!installed) {
            synchronized(this) {
                if (!installed) {
                    installed = true
                    Runtime.getRuntime().addShutdownHook(Thread {
                        // 逆序：后注册的先清理
                        val snapshot = synchronized(actions) { actions.toList() }
                        for (a in snapshot.asReversed()) {
                            runCatching { a() }
                        }
                    })
                }
            }
        }
    }
}

actual fun onExitCleanup(action: () -> Unit) {
    ExitHooks.add(action)
}

actual fun installSignalHandlers() {
    // JVM 的信号默认行为已由 addShutdownHook 覆盖（SIGINT/SIGTERM 都会触发 hook），
    // 无需再装。保持空实现，让两端调用点一致。
}

actual fun runProcessInheritIo(
    command: List<String>,
    env: Map<String, String>?,
    workDir: String?,
    timeoutMillis: Long,
): ProcOutcome {
    if (command.isEmpty()) {
        return ProcOutcome(
            exitCode = 2, stdout = "", stderr = "command is empty",
            timedOut = false, durationMillis = 0, spawnError = "command is empty",
        )
    }
    val startedAt = System.currentTimeMillis()
    return try {
        val pb = ProcessBuilder(command)
        env?.let { pb.environment().putAll(it) }
        workDir?.let { pb.directory(java.io.File(it)) }
        // 关键：子进程直连父进程的三个 fd，交互式脚本才能读键/出全屏
        pb.inheritIO()
        val child = pb.start()
        val timedOut = if (timeoutMillis > 0) {
            !child.waitFor(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } else {
            child.waitFor()
            false
        }
        if (timedOut) {
            child.descendants().forEach { runCatching { it.destroyForcibly() } }
            child.destroyForcibly()
        }
        ProcOutcome(
            exitCode = if (timedOut) 124 else child.exitValue(),
            stdout = "",
            stderr = "",
            timedOut = timedOut,
            durationMillis = System.currentTimeMillis() - startedAt,
        )
    } catch (e: Throwable) {
        ProcOutcome(
            exitCode = 127,
            stdout = "",
            stderr = e.message ?: e::class.simpleName ?: "spawn failed",
            timedOut = false,
            durationMillis = System.currentTimeMillis() - startedAt,
            spawnError = e.message ?: "spawn failed",
        )
    }
}

actual fun terminateProcess(pid: Long): Boolean = runCatching {
    ProcessHandle.of(pid).ifPresent { it.destroy() }
    true
}.getOrDefault(false)

actual fun onInterrupt(block: () -> Unit) {
    // 反射访问 sun.misc.Signal：它不在标准 API 里，直接 import 在部分 JDK 配置下编不过。
    // 装不上就静默降级（Ctrl-C 会按默认行为退出），与旧实现一致。
    runCatching {
        val signalCls = Class.forName("sun.misc.Signal")
        val handlerCls = Class.forName("sun.misc.SignalHandler")
        val sig = signalCls.getConstructor(String::class.java).newInstance("INT")
        val handler = java.lang.reflect.Proxy.newProxyInstance(
            handlerCls.classLoader,
            arrayOf(handlerCls),
        ) { _, method, _ ->
            if (method.name == "handle") runCatching { block() }
            null
        }
        signalCls.getMethod("handle", signalCls, handlerCls).invoke(null, sig, handler)
    }
}
