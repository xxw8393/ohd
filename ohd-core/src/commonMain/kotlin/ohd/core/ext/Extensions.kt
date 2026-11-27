package ohd.core.ext

import ohd.core.ExitCode
import ohd.core.OhdConfig
import ohd.core.OhdError
import ohd.core.Platform
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env
import ohd.platform.Mutex
import ohd.platform.RuntimeInfo
import ohd.platform.Stdio
import ohd.platform.runProcessInheritIo
import ohd.platform.spawnStreaming
import ohd.platform.isProcessAlive
import ohd.platform.killProcess
import ohd.platform.listDescendantPids

/**
 * 扩展脚本类型：只认这几种后缀。
 * 不做「任意可执行文件都收」——那等于把 ohd 变成通用 launcher，出问题无从排查。
 */
enum class ExtKind(val label: String, val suffix: String) {
    EXEC("可执行", ""),
    SHELL("sh", ".sh"),
    PYTHON("py", ".py"),
    JAVA("java", ".java"),
    BATCH("bat", ".bat"),
    CMD("cmd", ".cmd"),
}

/** 一条已发现的扩展：命令名 = 文件名去掉后缀。 */
data class Extension(
    val name: String,
    val file: OhdPath,
    val kind: ExtKind,
    val summary: String,
) {
    val dir: OhdPath? get() = file.parent
}

/**
 * 扩展发现与执行。
 *
 * 设计边界：
 * - 只扫白名单目录（不搜 PATH、不搜 cwd 根），避免误执行同名文件；
 * - 同名多后缀按平台偏好取一个（Windows 先 bat/cmd，Unix 先可执行/shell）；
 * - 与内置能力重名时内置优先（内置是契约，扩展不能覆盖语义）。
 */
object Extensions {
    private val NAME_RE = Regex("^[a-z][a-z0-9._-]*$")

    /** 头部摘要：`# @ohd 一行说明` / `// @ohd 一行说明` / `rem @ohd …` */
    private val SUMMARY_RE = Regex("""^\s*(?:#|//|rem\b|--)\s*@ohd\s+(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * 搜索目录，优先级从高到低：
     * 1. `$OHD_EXT`（显式指定，多人协作时指向共享目录）
     * 2. `<cwd>/.ohd/extensions`（跟着仓库走，可提交）
     * 3. `~/.ohd/extensions`（个人常用）
     * 4. `<ohd 安装目录>/extensions`（跟着分发包走）
     */
    fun roots(cwd: OhdPath, sysenv: (String) -> String? = { Env.getRaw(it) }): List<OhdPath> = buildList {
        sysenv("OHD_EXT")?.takeIf { it.isNotBlank() }?.let { add(OhdPath.of(it).toAbsolutePath()) }
        add(cwd.resolve(".ohd/extensions"))
        add(Platform.home.resolve(".ohd/extensions"))
        // 安装根：installDist 是 <root>/bin/ohd，dist 包是 <root>/ohd
        selfCommand()?.let { cmd ->
            val launcher = OhdPath.of(cmd)
            val root = if (launcher.parent?.fileName?.toString() == "bin") launcher.parent?.parent else launcher.parent
            root?.let { add(it.resolve("extensions")) }
        }
    }.distinct()

    /**
     * 扫描并合并所有扩展。同名取优先级最高、且平台最合适的那一个。
     * 内置能力名由调用方过滤（见 OhdCli），这里只做文件系统层面的事。
     */
    fun discover(cwd: OhdPath, sysenv: (String) -> String? = { Env.getRaw(it) }): LinkedHashMap<String, Extension> {
        val picked = linkedMapOf<String, Extension>()
        val ranked = mutableMapOf<String, MutableList<Pair<Int, Extension>>>()
        for (root in roots(cwd, sysenv)) {
            if (!fs.isDirectory(root)) continue
            // 权限不足的目录直接跳过：扩展是增强，不能因为它让主命令崩掉
            runCatching {
                fs.newDirectoryStream(root).use { ds ->
                    for (f in ds) {
                        val ext = parse(f) ?: continue
                        ranked.getOrPut(ext.name) { mutableListOf() } += rank(ext.kind) to ext
                    }
                }
            }
        }
        // 同目录内也有多后缀并存：按 rank 取最小，同 rank 取先入（目录优先级已保证）
        ranked.forEach { (name, cands) ->
            picked[name] = cands.minByOrNull { it.first }?.second ?: return@forEach
        }
        return picked
    }

    /** 单个文件 → 扩展；名字非法或后缀不认识则返回 null（不报错，扫描时跳过即可）。 */
    fun parse(file: OhdPath): Extension? {
        if (!fs.isRegularFile(file)) return null
        val fn = file.fileName.toString()
        val dot = fn.lastIndexOf('.')
        val (name, suffix) = if (dot > 0) fn.substring(0, dot) to fn.substring(dot).lowercase() else fn to ""
        if (!NAME_RE.matches(name)) return null
        val kind = when (suffix) {
            "" -> ExtKind.EXEC
            ".sh", ".bash" -> ExtKind.SHELL
            ".py" -> ExtKind.PYTHON
            ".java" -> ExtKind.JAVA
            ".bat" -> ExtKind.BATCH
            ".cmd" -> ExtKind.CMD
            else -> return null
        }
        return Extension(name, file, kind, summaryOf(file, kind))
    }

    /** 平台偏好：数字小的优先。Windows 上 shell 脚本基本跑不了，排最后。 */
    fun rank(kind: ExtKind): Int = if (Platform.isWindows) {
        when (kind) {
            ExtKind.BATCH -> 0
            ExtKind.CMD -> 1
            ExtKind.EXEC -> 2
            ExtKind.JAVA -> 3
            ExtKind.PYTHON -> 4
            ExtKind.SHELL -> 9
        }
    } else {
        when (kind) {
            ExtKind.EXEC -> 0
            ExtKind.SHELL -> 1
            ExtKind.PYTHON -> 2
            ExtKind.JAVA -> 3
            ExtKind.BATCH, ExtKind.CMD -> 9
        }
    }

    /** 真正拉起子进程。stdout/stderr 直通，退出码原样返回。 */
    /** 扩展默认超时：30 分钟。死循环的扩展会把 ohd 永久挂住，必须有兜底。 */
    /** 默认不限时长（0）；长跑扩展如 scene_capture / soak 不应被默默杀掉。需要防呆时显式传 `--ext-timeout 30m`。 */
    const val DEFAULT_TIMEOUT_MS: Long = 0L

    /** 扩展默认内存上限（MB）。Java 用 -Xmx 硬限；Linux 其他语言用 ulimit -v。 */
    const val DEFAULT_MEM_LIMIT_MB: Long = 2048L

    /** 扩展输出行数上限，超过即截断，避免一次刷屏毁掉终端滚动缓冲。 */
    const val DEFAULT_MAX_LINES: Int = 100_000

    /** ANSI/OSC 转义序列：捕获模式下剥掉，防止扩展清屏、移动光标、改标题。 */
    private val ANSI_RE =
        Regex("\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001B\\\\))")

    /** C0 控制字符（保留常见空白与换行）。 */
    private val CTRL_RE = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    fun exec(
        ext: Extension,
        args: List<String>,
        env: Map<String, String>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        memLimitMb: Long = DEFAULT_MEM_LIMIT_MB,
        maxLines: Int = DEFAULT_MAX_LINES,
        raw: Boolean = false,
    ): Int {
        val argv = mutableListOf<String>()
        when (ext.kind) {
            ExtKind.EXEC -> {
                if (!fs.isExecutable(ext.file)) {
                    throw OhdError(
                        ExitCode.USAGE,
                        "扩展不可执行：${ext.file}",
                        "chmod +x ${ext.file}，或改名成 .sh/.py 让 ohd 来挑解释器",
                    )
                }
                argv += ext.file.toString()
            }
            ExtKind.SHELL -> {
                if (Platform.isWindows) {
                    throw OhdError(
                        ExitCode.UNSUPPORTED,
                        "Windows 上无法直接运行 .sh 扩展：${ext.file}",
                        "改成 .bat/.cmd，或装 Git Bash 并写 .bat 转发",
                    )
                }
                argv += listOf("/bin/sh", ext.file.toString())
            }
            ExtKind.PYTHON -> argv += listOf(pythonBin(env), ext.file.toString())
            // 只有 Java 有跨平台可靠的内存硬限：JVM 自己拒绝超限，不会拖垮整机
            ExtKind.JAVA -> {
                val java = env["OHD_JAVA"] ?: javaBinName()
                argv += if (memLimitMb > 0) {
                    listOf(java, "-Xmx${memLimitMb}m", ext.file.toString())
                } else {
                    listOf(java, ext.file.toString())
                }
            }
            ExtKind.BATCH, ExtKind.CMD -> {
                if (!Platform.isWindows) {
                    throw OhdError(
                        ExitCode.UNSUPPORTED,
                        "非 Windows 上无法运行 ${ext.kind.label} 扩展：${ext.file}",
                        "换成 .sh 或用同名 .sh 覆盖",
                    )
                }
                argv += listOf("cmd", "/c", ext.file.toString())
            }
        }
        argv += args
        // 两条路，按语义分开：
        // - `--ext-raw`：交互式扩展要**直接继承父进程三个 fd**（读键盘、出全屏 TUI），
        //   走 runProcessInheritIo（posix 上就是「不 dup2」，天然继承）。
        // - 默认：接管输出，剥 ANSI 控制序列并限行数，走 spawnStreaming 逐行回调。
        if (raw) {
            val out = runProcessInheritIo(
                command = argv,
                env = env,
                workDir = OhdPath.of("").toAbsolutePath().toString(),
                timeoutMillis = if (timeoutMs <= 0L) 0 else timeoutMs,
            )
            if (out.timedOut) {
                Stdio.errLine("x 扩展 ${ext.name} 运行超过 ${timeoutMs / 1000} 秒，已强制终止")
                Stdio.errLine("  -> 检查它是否死循环；默认不限时长，当前是显式 --ext-timeout 生效")
                return ExitCode.TIMEOUT
            }
            if (out.spawnError != null) {
                Stdio.errLine("x 扩展 ${ext.name} 启动失败：${out.stderr}")
                return ExitCode.UNSUPPORTED
            }
            return out.exitCode
        }

        // 计数器：onLine 在后台协程里跑，用 Mutex 保护（K/N 的 commonMain 没有 AtomicInt）
        val counterLock = Mutex()
        var emitted = 0
        var strippedCount = 0
        val d = spawnStreaming(
            command = argv,
            env = env,
            workDir = OhdPath.of("").toAbsolutePath().toString(),
            onLine = { rawLine ->
                // 扩展写的是合法 UTF-8 时 ANSI 也能通过解码，必须显式剥离，
                // 否则 \e[2J 清屏之类会直接破坏用户终端。
                // 先剥完整转义序列，再清残余控制字符；顺序反了 ESC 会先被删、序列就匹配不到。
                val line = CTRL_RE.replace(ANSI_RE.replace(rawLine, ""), "")
                val n = counterLock.withLock {
                    if (line != rawLine) strippedCount++
                    emitted++
                }
                if (n <= maxLines) {
                    println(line)
                } else if (n == maxLines + 1) {
                    println("… ${ext.name} 输出超过 $maxLines 行，后续已截断（--ext-max-lines 可调）")
                }
            },
        )
        if (!d.ok || d.pid == null) {
            Stdio.errLine("x 扩展 ${ext.name} 启动失败：${d.spawnError}")
            return ExitCode.UNSUPPORTED
        }
        val pid = d.pid ?: return ExitCode.UNSUPPORTED
        // 等退出：timeoutMs <= 0 表示不限（默认；或用户显式 --ext-timeout 0）
        var timedOut = false
        val deadline = if (timeoutMs > 0L) Env.nowMillis() + timeoutMs else Long.MAX_VALUE
        while (isProcessAlive(pid)) {
            if (Env.nowMillis() >= deadline) {
                timedOut = true
                listDescendantPids(pid).forEach { runCatching { killProcess(it) } }
                killProcess(pid)
                break
            }
            Env.sleepMillis(20)
        }
        if (timedOut) {
            Stdio.errLine("x 扩展 ${ext.name} 运行超过 ${timeoutMs / 1000} 秒，已强制终止")
            Stdio.errLine("  -> 检查它是否死循环；默认不限时长，当前是显式 --ext-timeout 生效")
            return ExitCode.TIMEOUT
        }
        val strippedTotal = counterLock.withLock { strippedCount }
        if (strippedTotal > 0) {
            Stdio.errLine("  （已过滤 $strippedTotal 行中的控制序列；需要原样输出用 --ext-raw）")
        }
        return 0
    }

    /**
     * 注入给脚本的环境变量。目的只有一个：脚本能用「现在的工具」拼新命令，
     * 不必自己猜 hdc 在哪、当前什么设备、有没有要求 JSON。
     */
    fun envOf(
        hdc: String?,
        llvm: String?,
        device: String?,
        sessionRoot: OhdPath?,
        json: Boolean,
        ascii: Boolean,
    ): Map<String, String> = buildMap {
        // 用户配置（ohd config）先注入：脚本里直接能用 JAVA_HOME / OHD_PYTHON 等
        val cfg = OhdConfig.load()
        cfg.forEach { (k, v) -> put(k, v) }
        put("OHD_VERSION", ohd.core.TOOL_VERSION)
        put("OHD_CWD", OhdPath.of("").toAbsolutePath().toString())
        selfCommand()?.let { put("OHD_SELF", it) }
        // OHD_JAVA：配置了 JAVA_HOME 就听它的，否则用当前 JVM（也就 ohd 自己跑的那个）
        (cfg["JAVA_HOME"]?.takeIf { it.isNotBlank() }?.let { OhdPath.of(it, "bin", javaBinName()) }
            ?: RuntimeInfo.javaHome?.let { OhdPath.of(it, "bin", javaBinName()) })
            ?.let { put("OHD_JAVA", it.toString()) }
        RuntimeInfo.classpath?.let { put("OHD_CP", it) }
        hdc?.takeIf { it.isNotBlank() }?.let { put("OHD_HDC", it) }
        llvm?.takeIf { it.isNotBlank() }?.let { put("OHD_LLVM", it) }
        device?.takeIf { it.isNotBlank() }?.let { put("OHD_DEVICE", it) }
        sessionRoot?.let { put("OHD_SESSION_ROOT", it.toAbsolutePath().toString()) }
        if (json) put("OHD_JSON", "1")
        if (ascii) put("OHD_ASCII", "1")
        put("OHD_EXT", roots(OhdPath.of("").toAbsolutePath()).joinToString(Env.pathSeparator) { it.toString() })
    }

    private fun javaBinName(): String = if (Platform.isWindows) "java.exe" else "java"

    /** 解释器选择：允许用环境变量覆盖（公司内网常见自建 python 路径）。 */
    private fun pythonBin(env: Map<String, String>): String =
        env["OHD_PYTHON"] ?: Env.getRaw("OHD_PYTHON")
        ?: if (Platform.isWindows) "python" else "python3"

    /** 从 `@ohd` 注释取一行摘要；没有就退化成「python 扩展」。 */
    private fun summaryOf(file: OhdPath, kind: ExtKind): String = runCatching {
        // 只看前 10 行，命中第一条即定稿。用 headLines 而不是 forEachLine：
        // 后者不能中途 break，会把大脚本整个读完。
        fs.headLines(file, 10)
            .firstNotNullOfOrNull { SUMMARY_RE.find(it)?.groupValues?.get(1)?.trim()?.take(80) }
    }.getOrNull() ?: "${kind.label} 扩展"

    /**
     * 探测 ohd 自身的启动入口，供脚本回调。
     * installDist 形态是 `<root>/bin/ohd`，dist 包形态是 `<root>/ohd`。
     */
    private fun selfCommand(): String? = runCatching {
        val cp = RuntimeInfo.classpath ?: return null
        val first = cp.split(Env.pathSeparator).firstOrNull() ?: return null
        val root = OhdPath.of(first).toAbsolutePath().parent?.parent ?: return null
        listOf(root.resolve("bin").resolve("ohd"), root.resolve("ohd"))
            .firstOrNull { fs.isExecutable(it) }
            ?.toString()
    }.getOrNull() ?: Env.getRaw("OHD_SELF")?.takeIf { it.isNotBlank() }

    /** ohd 安装根：jar 位于 `<root>/lib` 时返回 `<root>`。 */
    private fun selfRoot(): OhdPath? = runCatching {
        val cp = RuntimeInfo.classpath ?: return null
        val first = cp.split(Env.pathSeparator).firstOrNull() ?: return null
        val lib = OhdPath.of(first).toAbsolutePath().parent ?: return null
        if (lib.fileName.toString() == "lib") lib.parent else null
    }.getOrNull()
}
