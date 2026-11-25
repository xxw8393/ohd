package ohd.core

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 设备平台。ohd 的命令语义不变，只有底层通道与命令串不同。 */
enum class DeviceKind(val label: String, val bin: String) {
    OHOS("ohos", "hdc"),
    ANDROID("android", "adb"),

    /**
     * iOS 模拟器。通道语义与 hdc/adb 不同：`simctl <sub> <udid> ...`，
     * 且**没有通用 shell**——命令走 `spawn <udid> /bin/sh -c`。
     *
     * 能力面也窄得多：模拟器不支持触摸注入，交互类能力一律不支持（见 [IosSupport]）。
     */
    IOS("ios", "simctl"),

    /**
     * iPhone 真机，走 `devicectl`（Xcode 15+ 的 CoreDevice 工具）。
     *
     * 与模拟器的差别是结构性的：真机**没有 shell**，所以 spawn 那套完全不存在，
     * 只有固定子命令（install / process / copy / info）。能力面因此比模拟器更窄。
     */
    IOS_DEVICE("ios-device", "devicectl");

    companion object {
        fun of(text: String?): DeviceKind? = when (text?.trim()?.lowercase()) {
            null, "" -> null
            "ohos", "harmony", "hdc" -> OHOS
            "android", "adb" -> ANDROID
            "ios", "simctl" -> IOS
            "ios-device", "device", "iphone-real" -> IOS_DEVICE
            else -> null
        }
    }
}

/**
 * 设备通道的唯一入口（hdc 与 adb 共用）。
 *
 * 为什么不是两套类：`shell` / 推 / 拉 三者语义完全一致，差异只在
 * `-t`（hdc）与 `-s`（adb）、`file send|recv` 与 `push|pull`。
 * 统一进来后，44 处 `hdc.shell(...)` 调用点一行都不用改，平台差异只落在
 * [kind] 与上层命令映射（`PlatformCmds`）上。
 *
 * 跨端一致性靠三点保证：
 * 1) argv 数组直传，绝不经过 shell（Windows 下 cmd 的引号规则与 POSIX 不同，是最大坑）；
 * 2) 输出统一按 UTF-8 解码，并把 CRLF 归一成 LF（Windows 上 hdc 输出带 \r）；
 * 3) 设备侧路径一律转正斜杠（hdc on Windows 对反斜杠敏感）。
 */
class Hdc(
    val exe: Path,
    val connectKey: String? = null,
    val defaultTimeoutMs: Long = 20_000,
    val encoding: Charset = StandardCharsets.UTF_8,
    val kind: DeviceKind = DeviceKind.OHOS,
) {
    data class Result(
        val argv: List<String>,
        val exit: Int,
        val stdout: String,
        val stderr: String,
        val elapsedMs: Long,
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = exit == 0 && !timedOut
        fun text(): String = if (stdout.isNotBlank()) stdout else stderr
        fun lines(): List<String> = stdout.lines().filter { it.isNotEmpty() }
    }

    companion object {
        fun normalizeNewlines(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')

        const val IOS_NO_TRANSFER = "iOS 模拟器不支持 push/pull（观测子集不提供文件传输）"
    }

    private fun devicePrefix(): List<String> =
        if (connectKey.isNullOrBlank()) emptyList()
        else listOf(if (kind == DeviceKind.ANDROID) "-s" else "-t", connectKey)

    /**
     * simctl 里 device 不是全局前缀，而是**子命令之后**的位置参数；
     * 且 `list devices`/`help` 这类子命令不接受 device。按子命令决定是否插入。
     */
    private val iosDeviceSubcommands = setOf(
        "spawn", "install", "launch", "terminate", "io", "listapps",
        "uninstall", "boot", "shutdown", "appinfo", "get_app_container", "logverbose",
    )

    /**
     * devicectl 的命令层级是固定的 `device <verb> <noun> --device <id> ...`，
     * 与 simctl 的 `<sub> <udid> ...` 不同，所以单独映射而不是套同一个模板。
     */
    private fun devicectlArgv(args: List<String>): List<String> {
        val id = connectKey
        val withId = { rest: List<String> -> buildList { addAll(rest); id?.let { add("--device"); add(it) } } }
        return when (args.firstOrNull()) {
            "install" -> withId(listOf("device", "install", "app")) + args.drop(1)
            "uninstall" -> withId(listOf("device", "uninstall", "app")) + args.drop(1)
            "launch" -> withId(listOf("device", "process", "launch")) + args.drop(1)
            "terminate" -> withId(listOf("device", "process", "terminate")) + args.drop(1)
            "apps" -> withId(listOf("device", "info", "apps"))
            "details" -> withId(listOf("device", "info", "details"))
            // `devicectl list devices` 本身就是完整命令，原样透传（早前在这里补 devices 导致重复）
            "list" -> args
            else -> args
        }
    }

    private fun wrap(args: List<String>): List<String> = buildList {
        add(exe.toString())
        if (kind == DeviceKind.IOS_DEVICE) {
            addAll(devicectlArgv(args))
            return@buildList
        }
        if (kind == DeviceKind.IOS) {
            args.firstOrNull()?.let { add(it) }
            if (args.firstOrNull() in iosDeviceSubcommands) {
                connectKey?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
            addAll(args.drop(1))
        } else {
            addAll(devicePrefix())
            addAll(args)
        }
    }

    /**
     * shell 语义的 argv（需要自己起进程时用，如日志泵）。
     * iOS 没有 shell 子命令，映射到 `spawn <udid> /bin/sh -c <cmd>`。
     */
    fun shellArgv(command: String): List<String> =
        if (kind == DeviceKind.IOS) {
            // 实测：simctl spawn <udid> /bin/sh -c "log stream ..." 会 abort（signal 6），
            // 因为 log 是 macOS 通用二进制，经 sh 转一手拿不到 DYLD_ROOT_PATH。
            // 必须把 log 直接作为 spawn 的第一个参数。
            argv("spawn", *command.trim().split(Regex("\\s+")).toTypedArray())
        } else {
            argv("shell", command)
        }

    /** 组装完整 argv，供 --dry-run 打印与 selftest 校验（不执行）。 */
    fun argv(vararg args: String): List<String> = wrap(args.toList())

    fun argvOf(args: List<String>): List<String> = wrap(args)

    fun run(args: List<String>, timeoutMs: Long = defaultTimeoutMs, retry: Int = 0, stdin: String? = null): Result {
        var last: Result? = null
        val attempts = retry + 1
        for (i in 0 until attempts) {
            val r = exec(argvOf(args), timeoutMs, stdin)
            last = r
            if (r.ok) return r
            if (i < attempts - 1) Thread.sleep(300)
        }
        return last!!
    }

    /**
     * 与 [run] 相同，但子进程忽略 SIGINT。
     *
     * 终端按 Ctrl+C 时信号发给整个前台进程组，`hdc` 子进程会被一起打断——对录屏这类
     * 「采集到一半也能要」的场景是致命的（当前批次直接作废）。用 `sh -c "trap '' INT; exec ..."`
     * 让 hdc 继承 SIG_IGN，把收尾交给调用方（置停止标志、等本批跑完、合成产物）。
     */
    fun runIgnoringInterrupt(args: List<String>, timeoutMs: Long = defaultTimeoutMs): Result {
        // iOS 走 spawn，信号语义不同；真机无 shell，一律回落原实现
        if (kind == DeviceKind.IOS || kind == DeviceKind.IOS_DEVICE) return run(args, timeoutMs)
        val inner = argvOf(args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        return exec(listOf("/bin/sh", "-c", "trap '' INT; exec $inner"), timeoutMs, null)
    }

    /** hdc shell "整串命令" —— 命令串由调用方组装，但整体作为单个 argv 元素传入。 */
    fun shell(command: String, timeoutMs: Long = defaultTimeoutMs, retry: Int = 0): Result =
        when (kind) {
            // 真机没有 shell，也没有 spawn；任何依赖 shell 的能力都不该走到这里
            DeviceKind.IOS_DEVICE -> Result(
                argvOf(listOf("shell", command)),
                ExitCode.UNSUPPORTED,
                "",
                "iPhone 真机没有 shell 通道（devicectl 只提供固定子命令）",
                0,
            )
            DeviceKind.IOS -> run(listOf("spawn", "/bin/sh", "-c", command), timeoutMs, retry)
            else -> run(listOf("shell", command), timeoutMs, retry)
        }

    /** hdc shell cmd arg1 arg2 —— 参数逐个传递，避免引号问题。 */
    fun shellArgv(vararg parts: String): Result = run(listOf("shell") + parts.toList())

    fun recv(remote: String, local: Path, timeoutMs: Long = 60_000): Result {
        Files.createDirectories(local.toAbsolutePath().parent)
        // 模拟器的文件系统就在宿主上，但 simctl 没有 pull；直接读宿主路径需要 UDID 映射，
        // 观测子集用不到，明确拒绝而不是静默失败
        if (kind == DeviceKind.IOS) return Result(argvOf(listOf("recv", remote)), 6, "", IOS_NO_TRANSFER, 0)
        if (kind == DeviceKind.ANDROID) return run(listOf("pull", remote, local.toAbsolutePath().toString()), timeoutMs, retry = 1)
        return run(listOf("file", "recv", remote, Platform.devicePath(local)), timeoutMs, retry = 1)
    }

    fun send(local: Path, remote: String, timeoutMs: Long = 60_000): Result =
        if (kind == DeviceKind.IOS) Result(argvOf(listOf("send", remote)), 6, "", IOS_NO_TRANSFER, 0)
        else if (kind == DeviceKind.ANDROID) run(listOf("push", local.toAbsolutePath().toString(), remote), timeoutMs, retry = 1)
        else run(listOf("file", "send", Platform.devicePath(local), remote), timeoutMs, retry = 1)

    fun targets(): List<String> = when (kind) {
        // 真机只认 paired 的；devicectl 的 stdout 不保证稳定，走 JSON 文件
        DeviceKind.IOS_DEVICE -> {
            val tmp = Files.createTempFile("ohd-devicectl-", ".json")
            try {
                run(listOf("list", "devices", "--json-output", tmp.toString()), 25_000)
                val text = runCatching { Files.readString(tmp) }.getOrDefault("")
                iosPhysicalUdids(text)
            } finally {
                runCatching { Files.deleteIfExists(tmp) }
            }
        }

        // 只把 Booted 的模拟器当「可用设备」——Shutdown 的无法执行任何命令
        DeviceKind.IOS -> run(listOf("list", "devices"), 15_000).stdout.lines()
            .mapNotNull {
                Regex("\\(([0-9A-Fa-f-]{36})\\)\\s*\\(Booted\\)").find(it)?.groupValues?.get(1)
            }
        DeviceKind.ANDROID -> {
        // adb devices 首行是 "List of devices attached"，其余每行 "<serial>\t<state>"；
        // 只要 state=device（unauthorized/offline 不该被当成可用设备）
        run(listOf("devices"), 15_000).stdout.lines().drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")) }
            .filter { it.size >= 2 && it[1] == "device" }
            .map { it[0] }
        }
        DeviceKind.OHOS -> run(listOf("list", "targets"), 15_000)
            .stdout.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
    }

    /**
     * 只返回 physical 真机。Xcode 26 起 `devicectl list devices` 也把模拟器列进来
     * （deviceProperties.hardwareProperties.reality == "simulated"）；不过滤的话同一台模拟器
     * 会同时以 ios / ios-device 两个身份出现，还会被当成「无法远程断开的真机」。
     */
    private fun iosPhysicalUdids(text: String): List<String> {
        val devices = runCatching {
            Json.parseToJsonElement(text).jsonObject["result"]?.jsonObject?.get("devices")?.jsonArray
        }.getOrNull()
            // 结构不认识就退回旧行为（不过滤）：宁可重复，也不要一台都拿不到
            ?: return Regex("\"identifier\"\\s*:\\s*\"([0-9A-Fa-f-]{36})\"")
                .findAll(text).map { it.groupValues[1] }.distinct().toList()

        return devices.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["identifier"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val reality = o["deviceProperties"]?.jsonObject?.get("hardwareProperties")?.jsonObject
                ?.get("reality")?.jsonPrimitive?.contentOrNull
                ?: o["hardwareProperties"]?.jsonObject?.get("reality")?.jsonPrimitive?.contentOrNull
            if (reality == "simulated") null else id
        }
    }

    fun version(): String? {
        // simctl 没有 version 子命令；版本信息没有诊断价值，不编造
        if (kind == DeviceKind.IOS) return null
        val r = if (kind == DeviceKind.ANDROID) run(listOf("version"), 10_000) else run(listOf("-v"), 10_000)
        if (!r.ok) return null
        if (kind == DeviceKind.ANDROID) {
            return r.stdout.lines().firstOrNull { it.contains("version", ignoreCase = true) }?.trim()
        }
        return r.stdout.lines().firstOrNull { it.contains("Ver", ignoreCase = true) }?.trim()
            ?: r.stdout.trim().lines().firstOrNull()
    }

    fun available(): Boolean = targets().isNotEmpty()

    private fun exec(args: List<String>, timeoutMs: Long, stdin: String?): Result {
        val startedAt = System.nanoTime()
        val proc = try {
            ProcessBuilder(args).apply { redirectErrorStream(false) }.start()
        } catch (e: Exception) {
            return Result(args, 127, "", "无法启动 hdc：${e.message}", elapsedMs(startedAt))
        }
        try {
            if (stdin != null) {
                proc.outputStream.use { it.write(stdin.toByteArray(encoding)) }
            } else {
                proc.outputStream.close()
            }
        } catch (_: Exception) {
            // 进程可能已退出，忽略写 stdin 的失败
        }

        var outBytes = ByteArray(0)
        var errBytes = ByteArray(0)
        val to = Thread { runCatching { outBytes = proc.inputStream.readBytes() } }.apply { isDaemon = true; start() }
        val te = Thread { runCatching { errBytes = proc.errorStream.readBytes() } }.apply { isDaemon = true; start() }

        val finished = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        var timedOut = false
        if (!finished) {
            timedOut = true
            proc.destroyForcibly()
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
        }
        to.join(2000)
        te.join(2000)

        val exit = if (timedOut) -1 else runCatching { proc.exitValue() }.getOrDefault(-1)
        return Result(
            argv = args,
            exit = exit,
            stdout = normalizeNewlines(String(outBytes, encoding)),
            stderr = normalizeNewlines(String(errBytes, encoding)),
            elapsedMs = elapsedMs(startedAt),
            timedOut = timedOut,
        )
    }

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000
}
