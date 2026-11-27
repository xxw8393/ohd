package ohd.core

import ohd.platform.OhdPath
import ohd.platform.fs
import ohd.platform.ProcSpec
import ohd.platform.runProcess
import ohd.platform.fmt

/**
 * 触摸注入后端。
 *
 * iOS 没有可用的公开注入通道：`simctl` 无 tap/swipe 子命令，真机 `devicectl` 也没有。
 * 唯一能做坐标级 HID 注入的是 **idb**（Meta 的 iOS Development Bridge）：
 * 它底层用 FBSimulatorControl / FBDeviceControl 直连 CoreSimulator 的 HID 服务，
 * 不需要在目标 app 里塞任何测试代码。
 *
 * 注意 idb **不是纯 CLI**：真正的注入由常驻的 `idb_companion` 完成，
 * `idb` 只是它的客户端。companion 由 idb 自己按需拉起并复用连接。
 */
class IdbInjector(
    private val exe: OhdPath,
    private val udid: String,
    private val timeoutMs: Long = 30_000,
) {
    data class Exec(val ok: Boolean, val out: String)

    /** `idb ui tap <x> <y>`；[durationMs] 非空时是长按。 */
    fun tap(x: Int, y: Int, durationMs: Int? = null): Exec {
        val args = mutableListOf("ui", "tap")
        if (durationMs != null && durationMs > 0) {
            args += listOf("--duration", fmt("%.3f", durationMs / 1000.0))
        }
        args += listOf(x.toString(), y.toString())
        return run(args)
    }

    /** `idb ui swipe <x1> <y1> <x2> <y2>`。 */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Exec {
        val args = mutableListOf("ui", "swipe")
        if (durationMs > 0) args += listOf("--duration", fmt("%.3f", durationMs / 1000.0))
        args += listOf(x1.toString(), y1.toString(), x2.toString(), y2.toString())
        return run(args)
    }

    /** HID keycode。 */
    fun key(code: Int): Exec = run(listOf("ui", "key", code.toString()))

    fun text(s: String): Exec = run(listOf("ui", "text", s))

    /** 硬件键：HOME / LOCK / SIRI 等。 */
    fun button(name: String): Exec = run(listOf("ui", "button", name))

    /** 无障碍层级导出——顺带能补 `tree` 能力。 */
    fun describeAll(): String? {
        val r = run(listOf("ui", "describe-all"))
        return if (r.ok) r.out else null
    }

    private fun run(uiArgs: List<String>): Exec {
        val argv = buildList {
            add(exe.toString())
            addAll(uiArgs)
            add("--udid")
            add(udid)
        }
        // 旧实现 redirectErrorStream(true) + waitFor(timeoutMs)：
        // 超时 → 强杀并返回「注入超时」；起来失败 → 「无法执行 idb」。
        val r = runProcess(ProcSpec(command = argv, timeoutMillis = timeoutMs))
        if (r.spawnError != null) return Exec(false, "无法执行 idb：${r.spawnError}")
        if (r.timedOut) return Exec(false, "idb 注入超时（${timeoutMs}ms）")
        val out = r.stdout + r.stderr
        // idb 的退出码不稳定，用输出里的错误标记兜一层
        val bad = out.contains("[ERROR]") || out.contains("Traceback")
        if (bad) return Exec(false, explain(out))
        return Exec(r.exitCode == 0, out)
    }

    /** 把 idb 的 Python traceback 收敛成一句能照着做的原因。 */
    private fun explain(out: String): String = when {
        out.contains("Failed to create xctest storage")
            || out.contains("Operation not permitted")
            || out.contains("Permission denied") ->
            "idb_companion 无法写入模拟器数据目录（权限不足）。" +
                "在普通终端运行 `idb ui tap --udid <udid> 100 100` 自查；" +
                "沙箱/受限环境会挡住 companion 初始化。"
        out.contains("parse_json_line") ->
            "idb_companion 启动失败（客户端读到非 JSON 输出）。" +
                "先单独跑 `idb_companion --udid <udid>` 看它的报错。"
        out.contains("Could not connect") || out.contains("Connection refused") ->
            "连不上 idb_companion：先 `idb list-targets` 确认目标可用，或 `idb connect <udid>`。"
        else -> out.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "idb 注入失败"
    }

    companion object {
        /**
         * 找一个可用的 idb 可执行文件。
         *
         * pip --user 会装到 `~/Library/Python/<x.y>/bin`，通常不在 PATH 上，
         * 所以这里把常见位置也扫一遍，避免要求用户改 shell 配置。
         */
        fun locate(env: Env? = null): OhdPath? {
            val explicit = env?.let { it.idb.path }
                ?: ohd.platform.Env.get("OHD_IDB")?.let { OhdPath.of(it) }
            if (explicit != null && fs.isExecutable(explicit)) return explicit

            val home = ohd.platform.Env.home
            val candidates = mutableListOf<OhdPath>()
            ohd.platform.Env.get("PATH")?.split(ohd.platform.Env.pathSeparator)?.forEach {
                candidates.add(OhdPath.of(it, "idb"))
            }
            candidates.add(home.resolve("Library/Python/3.9/bin/idb"))
            candidates.add(OhdPath.of("/opt/homebrew/bin/idb"))
            candidates.add(OhdPath.of("/usr/local/bin/idb"))
            // 其他 Python 小版本
            runCatching {
                val pyRoot = home.resolve("Library/Python")
                if (fs.isDirectory(pyRoot)) {
                    fs.list(pyRoot).use { seq ->
                        seq.iterator().asSequence().toList().forEach {
                            candidates.add(it.resolve("bin").resolve("idb"))
                        }
                    }
                }
            }
            return candidates.firstOrNull { fs.isExecutable(it) }
        }
    }
}
