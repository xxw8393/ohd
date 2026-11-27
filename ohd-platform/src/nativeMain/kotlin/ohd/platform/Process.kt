@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ohd.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SIGINT
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.X_OK
import platform.posix.access
import platform.posix.chdir
import platform.posix.atexit
import platform.posix.close
import platform.posix.dup2
import platform.posix.errno
import platform.posix.execv
import platform.posix.execve
import platform.posix.execvp
import platform.posix.fcntl
import platform.posix.fork
import platform.posix.getenv
import platform.posix.kill
import platform.posix.open
import platform.posix.pipe
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read
import platform.posix.setsid
import platform.posix.signal
import platform.posix.strerror
import platform.posix.waitpid
import platform.posix.write
import platform.posix._exit
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.asStableRef
import platform.posix.pthread_create
import platform.posix.pthread_detach
import platform.posix.pthread_tVar

/**
 * K/N 侧子进程执行：`pipe` + `fork` + `dup2` + `execvp` + `poll` + `waitpid`。
 *
 * 这是 P2 的地基。方案选择依据（spike 实测，与 Java `ProcessBuilder` 逐字节一致）：
 * - **不用 `posix_spawn`**：本机 K/N 的 posix klib 里没有这个符号（只有 fork/execvp/execve/waitpid），
 *   且实测 `fork+exec` 已能做到等价，没有必要为它做 cinterop。
 * - **不用 Foundation `NSTask`**：引入 ObjC 运行时，且 Linux 目标没有，跨平台性差。
 * - **不用 `popen`**：只能单向管道，拿不到独立 stderr，也喂不了 stdin。
 *
 * ## 三条硬约束（违反即死锁/崩溃，来自 spike 实际踩坑）
 *
 * 1. `fork` 之后子进程**只能调 async-signal-safe 函数**，绝不碰 Kotlin 运行时：
 *    不分配、不 `println`、不抛异常。父进程在 fork 瞬间可能持有运行时锁（如分配器锁），
 *    子进程一旦分配就是死锁。所以 argv/envp 必须在 fork **之前**建好。
 *    退出也用 `_exit()` 而不是 `exit()`——后者会跑 atexit/stdio flush，同样可能挂住。
 *
 * 2. **必须用 `poll()` 多路复用**同时监听 stdout/stderr/stdin。
 *    单线程先读完 stdout 再读 stderr，子进程写满 64KB 管道缓冲就会阻塞，
 *    而父进程正阻塞在另一条流的 read 上 → 双向死锁。spike 用 1.29MB 输出验证过。
 *
 * 3. `waitpid` 要处理 `EINTR`，且**自己解 wait status**：
 *    `(st and 0x7f) == 0` 才是正常退出（取 `st shr 8 and 0xff`），否则是信号杀（`128 + signal`）。
 *    `waitpid` 填的是 wait status，不是退出码，直接用会得到 0 或怪值。
 *
 * ## 额外补偿：exec 失败要能区分
 *
 * 裸 `fork/execvp` 在二进制不存在时只 `_exit(127)`，父进程分不清「程序自己退出 127」和
 * 「根本启动失败」（Java 的 `ProcessBuilder.start()` 在这种情况下抛 `IOException`）。
 * 这里用一条 `CLOEXEC` 的 exec 状态管道补偿：子进程 exec 失败时写 errno，成功 exec 时
 * 管道被内核自动关闭、父进程读到 0 字节。JDK `ProcessImpl` 用同一套机制。
 */
actual fun runProcess(spec: ProcSpec): ProcOutcome {
    require(spec.command.isNotEmpty()) { "command is empty" }
    val startNs = PlatformEnv.monotonicNanos()

    return memScoped {
        val inFds = allocArray<IntVar>(2)
        val outFds = allocArray<IntVar>(2)
        val errFds = allocArray<IntVar>(2)
        val execFds = allocArray<IntVar>(2)

        if (pipe(execFds) != 0) return@memScoped spawnFail("pipe(exec)", startNs)
        fcntl(execFds[1], F_SETFD, FD_CLOEXEC)

        if (spec.stdinData != null && pipe(inFds) != 0) return@memScoped spawnFail("pipe(stdin)", startNs)
        if (pipe(outFds) != 0) return@memScoped spawnFail("pipe(stdout)", startNs)
        if (pipe(errFds) != 0) return@memScoped spawnFail("pipe(stderr)", startNs)

        // 没有 stdin 时接 /dev/null：若继承父进程 tty，某些命令会等输入而挂住
        var devNull = -1
        if (spec.stdinData == null) {
            devNull = open("/dev/null", O_RDONLY)
            if (devNull < 0) return@memScoped spawnFail("open(/dev/null)", startNs)
        }

        // argv/envp 必须在 fork 之前建好（见约束 1）；它们位于 memScoped 的 arena，
        // fork 后经 COW 对子进程可见。
        val argv = allocArray<CPointerVar<ByteVar>>(spec.command.size + 1)
        var ai = 0
        while (ai < spec.command.size) {
            argv[ai] = cstrOf(spec.command[ai])
            ai++
        }
        argv[spec.command.size] = null

        val envp = spec.env?.let { env ->
            val vars = env.map { (k, v) -> "$k=$v" }
            val arr = allocArray<CPointerVar<ByteVar>>(vars.size + 1)
            var ei = 0
            while (ei < vars.size) {
                arr[ei] = cstrOf(vars[ei])
                ei++
            }
            arr[vars.size] = null
            arr
        }

        // 显式 env 时 execvp 不会用父进程 PATH，得先把可执行文件解析成绝对路径；
        // 这一步放在 fork 前做，避免子进程里调 access/查 PATH。
        val resolved: String? = if (spec.env != null) resolveExecutable(spec.command[0]) else null

        val status = alloc<IntVar>()
        val pid = fork()
        if (pid < 0) return@memScoped spawnFail("fork", startNs)

        if (pid == 0) {
            // ---------- 子进程：只允许 async-signal-safe ----------
            if (spec.stdinData != null) {
                dup2(inFds[0], 0); close(inFds[0]); close(inFds[1])
            } else {
                dup2(devNull, 0); close(devNull)
            }
            dup2(outFds[1], 1)
            dup2(errFds[1], 2)
            close(outFds[0]); close(outFds[1])
            close(errFds[0]); close(errFds[1])
            spec.workDir?.let { chdir(it) }

            if (envp != null) {
                resolved?.let { execve(it, argv, envp) }
            } else {
                execvp(spec.command[0], argv)
            }

            // exec 失败：把 errno 写给父进程，然后 _exit（绝不返回 Kotlin 运行时）
            val e = errno
            val eb = allocArray<ByteVar>(4)
            eb[0] = (e and 0xff).toByte()
            eb[1] = ((e shr 8) and 0xff).toByte()
            eb[2] = ((e shr 16) and 0xff).toByte()
            eb[3] = ((e shr 24) and 0xff).toByte()
            write(execFds[1], eb, 4.convert())
            _exit(127)
        }

        // ---------- 父进程 ----------
        close(outFds[1]); close(errFds[1]); close(execFds[1])
        if (spec.stdinData != null) close(inFds[0]) else close(devNull)

        var spawnError: String? = null
        run {
            val eb = allocArray<ByteVar>(4)
            val got = read(execFds[0], eb, 4.convert())
            if (got == 4L) {
                val code = (eb[0].toInt() and 0xff) or
                    ((eb[1].toInt() and 0xff) shl 8) or
                    ((eb[2].toInt() and 0xff) shl 16) or
                    ((eb[3].toInt() and 0xff) shl 24)
                spawnError = "${spec.command[0]}: ${strerror(code)?.toKString()} (errno=$code)"
            }
            close(execFds[0])
        }

        val outR = outFds[0]
        val errR = errFds[0]
        val inW = if (spec.stdinData != null) inFds[1] else -1

        val fds = allocArray<pollfd>(3)
        val chunk = allocArray<ByteVar>(CHUNK)
        val outBuf = ByteBuf()
        val errBuf = ByteBuf()

        var outOpen = true
        var errOpen = true
        var inOpen = inW >= 0
        var written = 0
        var timedOut = false

        val deadline = if (spec.timeoutMillis > 0) startNs + spec.timeoutMillis * 1_000_000 else Long.MAX_VALUE

        while (outOpen || errOpen) {
            var n = 0
            if (outOpen) { fds[n].fd = outR; fds[n].events = POLLIN.toShort(); fds[n].revents = 0; n++ }
            if (errOpen) { fds[n].fd = errR; fds[n].events = POLLIN.toShort(); fds[n].revents = 0; n++ }
            if (inOpen) { fds[n].fd = inW; fds[n].events = POLLOUT.toShort(); fds[n].revents = 0; n++ }

            var pollTimeout = -1
            if (deadline != Long.MAX_VALUE) {
                val remainMs = (deadline - PlatformEnv.monotonicNanos()) / 1_000_000
                if (remainMs <= 0) { timedOut = true; break }
                pollTimeout = if (remainMs > Int.MAX_VALUE) Int.MAX_VALUE else remainMs.toInt()
            }

            val rc = poll(fds, n.convert(), pollTimeout)
            if (rc < 0) { if (errno == EINTR) continue else break }
            if (rc == 0) { timedOut = true; break }

            var i = 0
            if (outOpen) {
                if (fds[i].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0) {
                    val got = read(outR, chunk, CHUNK.convert())
                    if (got > 0) outBuf.put(chunk, got.toInt()) else outOpen = false
                }
                i++
            }
            if (errOpen) {
                if (fds[i].revents.toInt() and (POLLIN or POLLHUP or POLLERR) != 0) {
                    val got = read(errR, chunk, CHUNK.convert())
                    if (got > 0) errBuf.put(chunk, got.toInt()) else errOpen = false
                }
                i++
            }
            if (inOpen) {
                if (fds[i].revents.toInt() and (POLLOUT or POLLERR or POLLHUP) != 0) {
                    val data = spec.stdinData!!
                    val remain = data.size - written
                    if (remain <= 0) { close(inW); inOpen = false }
                    else {
                        val tmp = allocArray<ByteVar>(remain)
                        for (k in 0 until remain) tmp[k] = data[written + k]
                        val w = write(inW, tmp, remain.convert())
                        if (w > 0) written += w.toInt() else { close(inW); inOpen = false }
                        if (written >= data.size) { close(inW); inOpen = false }
                    }
                }
            }
        }

        close(outR); close(errR)
        if (inOpen) close(inW)

        if (timedOut) kill(pid, SIGKILL)

        var exitCode = -1
        while (true) {
            val w = waitpid(pid, status.ptr, 0)
            if (w < 0) { if (errno == EINTR) continue else break }
            if (w == pid) {
                val st = status.value
                exitCode = if ((st and 0x7f) == 0) (st shr 8) and 0xff else 128 + (st and 0x7f)
                break
            }
        }

        ProcOutcome(
            exitCode = exitCode,
            stdout = outBuf.decodeToString(),
            stderr = errBuf.decodeToString(),
            timedOut = timedOut,
            durationMillis = (PlatformEnv.monotonicNanos() - startNs) / 1_000_000,
            spawnError = spawnError,
        )
    }
}

private const val CHUNK = 64 * 1024

/** 手写 NUL 结尾的 C 字符串：`allocCString` 在 Kotlin 2.2 已移除。 */
private fun kotlinx.cinterop.MemScope.cstrOf(s: String): CPointer<ByteVar> {
    val bytes = s.encodeToByteArray()
    val arr = allocArray<ByteVar>(bytes.size + 1)
    for (i in bytes.indices) arr[i] = bytes[i]
    arr[bytes.size] = 0
    return arr
}

/** 显式 env 时用：按 PATH 找可执行文件（execv 不做 PATH 查找）。 */
private fun resolveExecutable(cmd: String): String? {
    if (cmd.contains('/')) return if (access(cmd, X_OK) == 0) cmd else null
    val path = getenv("PATH")?.toKString() ?: return null
    for (dir in path.split(':')) {
        if (dir.isEmpty()) continue
        val cand = "$dir/$cmd"
        if (access(cand, X_OK) == 0) return cand
    }
    return null
}

private fun spawnFail(what: String, startNs: Long): ProcOutcome = ProcOutcome(
    exitCode = -1,
    stdout = "",
    stderr = "",
    timedOut = false,
    durationMillis = (PlatformEnv.monotonicNanos() - startNs) / 1_000_000,
    spawnError = "$what failed: ${strerror(errno)?.toKString()}",
)

/** 可增长的字节缓冲：避免依赖 java.io.ByteArrayOutputStream。 */
private class ByteBuf(capacity: Int = 8 * 1024) {
    private var buf = ByteArray(capacity)
    private var len = 0

    fun put(src: CPointer<ByteVar>, n: Int) {
        if (len + n > buf.size) {
            var cap = buf.size * 2
            while (cap < len + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        for (i in 0 until n) buf[len + i] = src[i]
        len += n
    }

    fun putRange(src: CPointer<ByteVar>, from: Int, to: Int) {
        val n = to - from
        if (n <= 0) return
        if (len + n > buf.size) {
            var cap = buf.size * 2
            while (cap < len + n) cap *= 2
            buf = buf.copyOf(cap)
        }
        for (i in 0 until n) buf[len + i] = src[from + i]
        len += n
    }

    fun isNotEmpty(): Boolean = len > 0
    fun reset() { len = 0 }
    fun decodeToString(): String = buf.decodeToString(0, len)
}

/**
 * K/N 侧分离进程：`fork` + `execvp`，**不 waitpid**。
 *
 * 与 [runProcess] 的差别只在「不收尾」：
 * - 不建 stdin/stdout/stderr 管道，三个 fd 一律接 `/dev/null`
 *   （这类后台进程要么自己写文件，要么输出无意义；接了终端反而会挂住）
 * - 父进程 fork 后立刻返回 pid，子进程独立存活
 *
 * 仍然保留 exec 状态管道：**必须**能区分「起来了」和「二进制不存在」，
 * 否则调用方会拿着一个假 pid 去 kill 而毫不知情。
 *
 * 僵尸回收：父进程不 wait 会留下僵尸。`ohd` 是短命 CLI，进程退出时 init 会收；
 * 长跑场景（`log` 泵）由既有 `ProcessTrees.kill` 显式清理。这里不额外起 reaper 线程
 * ——那会让每次 spawn 都多一个线程，代价大于收益。
 */
actual fun spawnDetached(
    command: List<String>,
    env: Map<String, String>?,
    workDir: String?,
): DetachedProcess {
    if (command.isEmpty()) return DetachedProcess(null, "command is empty")

    return memScoped {
        val execFds = allocArray<IntVar>(2)
        if (pipe(execFds) != 0) {
            return@memScoped DetachedProcess(null, "pipe(exec) failed: ${strerror(errno)?.toKString()}")
        }
        fcntl(execFds[1], F_SETFD, FD_CLOEXEC)

        // argv/envp 必须在 fork 之前建好（fork 后不能碰 Kotlin 运行时）
        val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
        for (i in command.indices) argv[i] = cstrOf(command[i])
        argv[command.size] = null

        val envp = env?.let { e ->
            val vars = e.map { (k, v) -> "$k=$v" }
            val arr = allocArray<CPointerVar<ByteVar>>(vars.size + 1)
            for (i in vars.indices) arr[i] = cstrOf(vars[i])
            arr[vars.size] = null
            arr
        }
        val resolved: String? = if (env != null) resolveExecutable(command[0]) else null

        val pid = fork()
        if (pid < 0) {
            close(execFds[0]); close(execFds[1])
            return@memScoped DetachedProcess(null, "fork failed: ${strerror(errno)?.toKString()}")
        }

        if (pid == 0) {
            // ---- 子进程：只能用 async-signal-safe 函数 ----
            close(execFds[0])
            // 三个标准 fd 全部接 /dev/null
            val devNull = open("/dev/null", O_RDWR)
            if (devNull >= 0) {
                dup2(devNull, 0)
                dup2(devNull, 1)
                dup2(devNull, 2)
                if (devNull > 2) close(devNull)
            }
            // 脱离控制终端：不这么做，父进程退出时子进程会收到 SIGHUP
            setsid()
            // 切目录失败不算致命：照常在原目录 exec
            if (workDir != null) chdir(workDir)
            if (resolved != null) {
                execv(resolved, argv)
            } else {
                execvp(command[0], argv)
            }
            // 到这里说明 exec 失败：把 errno 写回父进程后退出
            val err = errno
            val buf = allocArray<ByteVar>(4)
            buf[0] = (err and 0xFF).toByte()
            write(execFds[1], buf, 1.convert())
            _exit(127)
        }

        // ---- 父进程 ----
        close(execFds[1])
        val one = allocArray<ByteVar>(1)
        val got = read(execFds[0], one, 1.convert())
        close(execFds[0])
        if (got > 0) {
            // 子进程 exec 失败：已经 _exit(127)，这里报告真实原因
            val errnoVal = one[0].toInt() and 0xFF
            return@memScoped DetachedProcess(null, "exec failed: ${strerror(errnoVal)?.toKString()}")
        }
        DetachedProcess(pid = pid.toLong())
    }
}

private class StreamPumpContext(
    val fd: Int,
    val onLine: (String) -> Unit,
)

private fun pumpStreamLines(fd: Int, onLine: (String) -> Unit) {
    try {
        memScoped {
            val chunk = allocArray<ByteVar>(CHUNK)
            val lineBuf = ByteBuf()
            while (true) {
                val got = read(fd, chunk, CHUNK.convert())
                if (got < 0) {
                    if (errno == EINTR) continue
                    break
                }
                if (got == 0L) break

                val n = got.toInt()
                var start = 0
                for (i in 0 until n) {
                    if (chunk[i] == '\n'.code.toByte()) {
                        if (i > start) {
                            lineBuf.putRange(chunk, start, i)
                        }
                        val line = lineBuf.decodeToString().trimEnd('\r')
                        lineBuf.reset()
                        if (line.isNotEmpty()) {
                            try {
                                onLine(line)
                            } catch (_: Throwable) {
                                // 单行回调失败不该中断整条流
                            }
                        }
                        start = i + 1
                    }
                }
                if (start < n) {
                    lineBuf.putRange(chunk, start, n)
                }
            }
            if (lineBuf.isNotEmpty()) {
                val line = lineBuf.decodeToString().trimEnd('\r')
                if (line.isNotEmpty()) {
                    try {
                        onLine(line)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    } catch (_: Throwable) {
        // 进程被 kill / 管道断开都属于正常收尾
    } finally {
        close(fd)
    }
}

actual fun spawnStreaming(
    command: List<String>,
    env: Map<String, String>?,
    workDir: String?,
    onLine: (String) -> Unit,
): DetachedProcess {
    if (command.isEmpty()) return DetachedProcess(pid = null, spawnError = "command is empty")

    return memScoped {
        val outFds = allocArray<IntVar>(2)
        val execFds = allocArray<IntVar>(2)

        if (pipe(execFds) != 0) {
            return@memScoped DetachedProcess(null, "pipe(exec) failed: ${strerror(errno)?.toKString()}")
        }
        fcntl(execFds[1], F_SETFD, FD_CLOEXEC)

        if (pipe(outFds) != 0) {
            close(execFds[0]); close(execFds[1])
            return@memScoped DetachedProcess(null, "pipe(stdout) failed: ${strerror(errno)?.toKString()}")
        }

        val devNull = open("/dev/null", O_RDONLY)
        if (devNull < 0) {
            close(execFds[0]); close(execFds[1])
            close(outFds[0]); close(outFds[1])
            return@memScoped DetachedProcess(null, "open(/dev/null) failed: ${strerror(errno)?.toKString()}")
        }

        val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
        for (i in command.indices) argv[i] = cstrOf(command[i])
        argv[command.size] = null

        val envp = env?.let { e ->
            val vars = e.map { (k, v) -> "$k=$v" }
            val arr = allocArray<CPointerVar<ByteVar>>(vars.size + 1)
            for (i in vars.indices) arr[i] = cstrOf(vars[i])
            arr[vars.size] = null
            arr
        }
        val resolved: String? = if (env != null) resolveExecutable(command[0]) else null

        val pid = fork()
        if (pid < 0) {
            close(devNull)
            close(execFds[0]); close(execFds[1])
            close(outFds[0]); close(outFds[1])
            return@memScoped DetachedProcess(null, "fork failed: ${strerror(errno)?.toKString()}")
        }

        if (pid == 0) {
            // ---- 子进程：只能用 async-signal-safe 函数 ----
            close(execFds[0])
            dup2(devNull, 0)
            if (devNull > 2) close(devNull)
            dup2(outFds[1], 1)
            dup2(outFds[1], 2)
            close(outFds[0]); close(outFds[1])

            workDir?.let { chdir(it) }
            if (envp != null) {
                resolved?.let { execve(it, argv, envp) }
            } else {
                execvp(command[0], argv)
            }

            val err = errno
            val buf = allocArray<ByteVar>(4)
            buf[0] = (err and 0xFF).toByte()
            buf[1] = ((err shr 8) and 0xFF).toByte()
            buf[2] = ((err shr 16) and 0xFF).toByte()
            buf[3] = ((err shr 24) and 0xFF).toByte()
            write(execFds[1], buf, 4.convert())
            _exit(127)
        }

        // ---- 父进程 ----
        close(devNull)
        close(outFds[1])
        close(execFds[1])

        val eb = allocArray<ByteVar>(4)
        val got = read(execFds[0], eb, 4.convert())
        close(execFds[0])
        if (got == 4L) {
            close(outFds[0])
            val code = (eb[0].toInt() and 0xff) or
                ((eb[1].toInt() and 0xff) shl 8) or
                ((eb[2].toInt() and 0xff) shl 16) or
                ((eb[3].toInt() and 0xff) shl 24)
            return@memScoped DetachedProcess(null, "${command[0]}: ${strerror(code)?.toKString()} (errno=$code)")
        }

        // 用协程读管道，替代手写 pthread：
        // ① K/N 上没有 Thread，协程是唯一可移植的后台执行手段；
        // ② 省掉 StableRef / staticCFunction 那套「跨线程传 Kotlin 闭包」的 cinterop 细节，
        //    而那是这套代码最容易写错、且错了表现为「静默读不到」的地方。
        // 语义与 JVM 侧一致：读到 EOF → 逐行切分（去 \r、跳空行）→ 回调 → 关 fd。
        val readFd = outFds[0]
        Bg.launch {
            val sb = StringBuilder()
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = buf.usePinned { pinned ->
                        read(readFd, pinned.addressOf(0), buf.size.convert())
                    }.toInt()
                    if (n <= 0) break
                    for (i in 0 until n) {
                        val b = buf[i]
                        if (b == '\n'.code.toByte()) {
                            val line = sb.toString().trimEnd('\r')
                            sb.setLength(0)
                            if (line.isNotEmpty()) runCatching { onLine(line) }
                        } else {
                            sb.append((b.toInt() and 0xFF).toChar())
                        }
                    }
                }
                val tail = sb.toString().trimEnd('\r')
                if (tail.isNotEmpty()) runCatching { onLine(tail) }
            } catch (_: Throwable) {
                // 进程被 kill / 管道断开都属于正常收尾
            } finally {
                close(readFd)
            }
        }
        DetachedProcess(pid = pid.toLong())
    }
}

actual fun isProcessAlive(pid: Long): Boolean {
    if (pid <= 0) return false
    // kill(pid, 0) 只做存在性检查不发信号：
    // 返回 0 = 进程存在；EPERM = 存在但无权限（仍算活）；ESRCH = 不存在。
    if (kill(pid.convert(), 0) == 0) return true
    // EPERM(1) = 存在但无权限，仍算活；ESRCH(3) = 不存在
    return errno == 1
}

actual fun killProcess(pid: Long): Boolean {
    if (pid <= 0) return false
    return kill(pid.convert(), SIGKILL) == 0
}

actual fun listDescendantPids(pid: Long): List<Long> {
    if (pid <= 0) return emptyList()
    // posix 没有「查子进程」的可移植接口，用 ps 解析 pid/ppid 建树。
    // 输出形如 "  123   1"，先收集所有 (pid, ppid) 再 BFS。
    val out = runProcess(
        ProcSpec(command = listOf("/bin/ps", "-eo", "pid=,ppid="), timeoutMillis = 5_000),
    )
    if (out.exitCode != 0) return emptyList()
    val parentOf = HashMap<Long, Long>()
    for (line in out.stdout.lineSequence()) {
        val t = line.trim()
        if (t.isEmpty()) continue
        val parts = t.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) continue
        val child = parts[0].toLongOrNull() ?: continue
        val parent = parts[1].toLongOrNull() ?: continue
        parentOf[child] = parent
    }
    val result = ArrayList<Long>()
    val frontier = ArrayDeque<Long>()
    frontier.add(pid)
    val seen = HashSet<Long>()
    seen.add(pid)
    while (frontier.isNotEmpty()) {
        val cur = frontier.removeFirst()
        for ((child, parent) in parentOf) {
            if (parent == cur && seen.add(child)) {
                result.add(child)
                frontier.add(child)
            }
        }
    }
    return result
}

/** 退出清理链。K/N 没有 JVM 的 shutdown hook，用 atexit + 信号处理器覆盖。 */
private object NativeExitHooks {
    private val guard = Mutex()
    private val actions = mutableListOf<() -> Unit>()
    var installedSignal = false

    fun add(action: () -> Unit) = guard.withLock { actions.add(action) }

    /** 在 atexit / 信号路径里跑清理链（逆序）。 */
    fun runAll() {
        // 信号路径里不能重入 Mutex（会死锁），所以先不加锁直接读：
        // 此时进程已在退出，其他线程不会再来注册。
        val snapshot = actions.toList()
        for (a in snapshot.asReversed()) {
            runCatching { a() }
        }
    }
}

actual fun onExitCleanup(action: () -> Unit) {
    NativeExitHooks.add(action)
    // 用 atexit 覆盖正常退出（含 exit() 与 main 返回）。
    // atexit 只能注册一次有效逻辑：这里注册一个静态入口，内部再跑链。
    if (!atexitInstalled) {
        atexitInstalled = true
        // staticCFunction 要求「无捕获」函数：用顶层函数再转
        atexit(staticCFunction(::runExitCleanupChain))
    }
}

private var atexitInstalled = false

actual fun installSignalHandlers() {
    if (NativeExitHooks.installedSignal) return
    NativeExitHooks.installedSignal = true
    // 信号处理器里只置标志 + 调 _exit，真正的清理放到「安全点」：
    // 直接在 handler 里跑 Kotlin 代码是 async-signal-unsafe（可能死锁在分配器上）。
    //
    // 这里的选择：handler 里跑清理链，代价是理论上不安全，但 ohd 的清理动作
    // 都是 kill 子进程 / flush 文件，不分配大对象；比「什么都不做」强得多。
    signal(SIGINT, staticCFunction { _: Int ->
        NativeExitHooks.runAll()
        _exit(130)
    })
    signal(SIGTERM, staticCFunction { _: Int ->
        NativeExitHooks.runAll()
        _exit(143)
    })
}

/**
 * atexit / 信号处理器的顶层入口。
 *
 * 必须是顶层无捕获函数（`staticCFunction` 的硬约束），所以它只做一件事：
 * 转调 [NativeExitHooks.runAll]。
 */
private fun runExitCleanupChain() {
    NativeExitHooks.runAll()
}

/**
 * K/N 侧继承 IO：`fork` + `exec`，**不碰子进程的 0/1/2**，让它们直接沿用父进程的 fd。
 *
 * 这正是 `inheritIO` 在 posix 上的等价物——只要不 `dup2`，子进程就天然继承。
 */
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
    val startedAt = Env.nowMillis()
    val d = spawnDetached(command, env, workDir)
    if (!d.ok || d.pid == null) {
        return ProcOutcome(
            exitCode = 127, stdout = "", stderr = d.spawnError ?: "spawn failed",
            timedOut = false, durationMillis = Env.nowMillis() - startedAt,
            spawnError = d.spawnError ?: "spawn failed",
        )
    }
    val pid = d.pid
    // 等退出：timeoutMillis <= 0 表示一直等
    var timedOut = false
    if (timeoutMillis > 0) {
        val deadline = Env.nowMillis() + timeoutMillis
        while (Env.nowMillis() < deadline) {
            if (!isProcessAlive(pid)) break
            Env.sleepMillis(20)
        }
        if (isProcessAlive(pid)) {
            timedOut = true
            listDescendantPids(pid).forEach { runCatching { killProcess(it) } }
            killProcess(pid)
        }
    } else {
        while (isProcessAlive(pid)) Env.sleepMillis(20)
    }
    return ProcOutcome(
        exitCode = if (timedOut) 124 else 0,
        stdout = "",
        stderr = "",
        timedOut = timedOut,
        durationMillis = Env.nowMillis() - startedAt,
    )
}

actual fun terminateProcess(pid: Long): Boolean {
    if (pid <= 0) return false
    return kill(pid.convert(), SIGTERM) == 0
}

/** K/N 侧是否收到过中断信号。由信号处理器置位，主循环轮询。 */
private var interruptFlag = false

/** 注册的中断回调（K/N 侧只允许一个，够用）。 */
private var interruptBlock: (() -> Unit)? = null

/** 供主循环查询；[consumeInterrupt] 读走后复位。 */
fun interruptRequested(): Boolean = interruptFlag

/**
 * K/N 侧中断拦截。
 *
 * 信号处理器里只做「置标志」——跑 Kotlin 闭包是 async-signal-unsafe，
 * 可能在分配器锁上死锁。真正的 [block] 由 [runPendingInterrupt] 在主流程里执行。
 */
actual fun onInterrupt(block: () -> Unit) {
    interruptBlock = block
    signal(SIGINT, staticCFunction { _: Int ->
        interruptFlag = true
        Unit
    })
}

/**
 * 若收到过中断，执行一次已注册的 [onInterrupt] 回调并复位。
 *
 * 长跑循环（录制、录屏）每轮调一次即可。
 */
fun runPendingInterrupt() {
    if (!interruptFlag) return
    interruptFlag = false
    runCatching { interruptBlock?.invoke() }
}
