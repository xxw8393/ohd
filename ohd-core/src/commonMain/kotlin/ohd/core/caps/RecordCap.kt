package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.actions.RecordScript
import ohd.core.parseDurationMs
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.spawnStreaming
import ohd.platform.isProcessAlive
import ohd.platform.Stdio
import ohd.platform.onExitCleanup
import ohd.platform.Mutex
import ohd.platform.terminateProcess
import ohd.platform.killProcess
import ohd.platform.onInterrupt

/**
 * 把真机上的操作录成 `ohd replay` 脚本。
 *
 * 设备侧 `uitest uiRecord record` 阻塞到进程被打断，结果在 `/data/local/tmp/record.csv`（内容是 JSON）。
 * 生成的脚本第一条是 `launch <包名> <Ability>`。没有包名或 Ability 时不写脚本。
 */
class RecordCap : Capability {
    override val name = "record"
    override val summary = "录制真机操作并生成可回放脚本（回放前冷启动应用入口）"
    override val usage = """
        ohd record [--bundle <包名>] [--ability <Ability>] [--duration 30s]
        ohd record read [--bundle <包名>]
        脚本：ohd-evidence/record/record-<设备>-<时间>.ohd
        回放：ohd replay <该文件>
    """.trimIndent()
    override val example = "ohd record --bundle com.tencent.compose --duration 60s"
    override val accepts: Set<String> = setOf("duration", "bundle", "ability")

    override fun run(ctx: RunContext): Int {
        val extra = ctx.options.positionals.filter { it != "read" }
        if (extra.isNotEmpty()) {
            throw OhdError(
                ExitCode.USAGE,
                "不用指定文件名：ohd-evidence/record/record-<设备>-<时间>.ohd",
                "示例：ohd record --bundle com.example.app --duration 2m（文件名由 ohd 自动生成，参数可省略）",
            )
        }
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "record")
        val raw = if (ctx.options.positionals.firstOrNull() == "read") {
            readRaw(hdc, session)
        } else {
            capture(ctx, hdc, session)
        }
        return emit(ctx, hdc, session, raw, Env.nowMillis() - started)
    }

    private fun capture(ctx: RunContext, hdc: Hdc, session: Session?): String {
        Hdcs.sh(session, hdc, "rm -f /data/local/tmp/record.csv", 10_000)
        // 三个共享量都由后台协程写、主流程读：用 Mutex 保护普通变量
        // （K/N 的 commonMain 没有 AtomicReference / AtomicLong）
        val shared = Mutex()
        var boundsLine: String? = null
        var foregroundLine: String? = null
        // 记录最后一次收到行的时刻，用来判断读线程有没有把管道排空。
        var lastLineAt = Env.nowMillis()
        // 边跑边读：spawnStreaming 内部已起后台线程逐行回调（stderr 已并入 stdout），
        // stdin 自动关闭、空行不回调——与旧的 redirectErrorStream + 手写 pump 线程一致。
        val recorded = spawnStreaming(
            hdc.argv("shell", "uitest", "uiRecord", "record", "-W", "true", "-c", "true"),
            onLine = { line ->
                Stdio.errLine(line)
                val trimmed = line.trim()
                val isFirstBounds = shared.withLock { boundsLine == null }
                if (isFirstBounds && RecordScript.parseWindowBounds(trimmed) != null) {
                    shared.withLock { if (boundsLine == null) boundsLine = trimmed }
                }
                val isFirstFg = shared.withLock { foregroundLine == null }
                if (isFirstFg && RecordScript.parseForeground(trimmed) != null) {
                    shared.withLock { if (foregroundLine == null) foregroundLine = trimmed }
                }
                shared.withLock { lastLineAt = Env.nowMillis() }
            },
        )
        val pid = recorded.pid ?: throw OhdError(
            ExitCode.UNSUPPORTED,
            "启动 uiRecord 失败：${recorded.spawnError}",
            "确认设备已连接、uitest 可用",
        )

        val duration = ctx.options.str("duration")?.let { parseDurationMs(it) }
        Stdio.errLine(
            if (duration == null) "在手机上操作。录完回到这个终端按 Ctrl+C。"
            else "在手机上操作。${duration}ms 后自动停止。",
        )
        onInterrupt { stop(pid) }
        // onExitCleanup 不可撤销，而原代码在正常收尾后会 removeShutdownHook。
        // 用一个幂等标志保证 stop 只跑一次：正常路径先跑，退出钩子再触发时直接跳过。
        val stopped = Mutex()
        var stoppedDone = false
        fun stopOnce() {
            val need = stopped.withLock {
                if (stoppedDone) false else { stoppedDone = true; true }
            }
            if (need) runCatching { stop(pid) }
        }
        onExitCleanup { stopOnce() }
        try {
            // 等价旧的 waitFor(duration) / waitFor()：不设上限就一直等进程自己退出
            if (duration != null) awaitDead(pid, duration) else while (alive(pid)) Env.sleepMillis(100)
        } finally {
            stopOnce()
            // 等价旧的 pump.join(2_000)：等读线程把管道里剩下的行排完（有上限，不强求）。
            // 进程死掉后回调线程可能还在排水，所以判据是「静默一段时间」而不是「进程已死」。
            val drainDeadline = Env.nowMillis() + 2_000
            while (Env.nowMillis() < drainDeadline &&
                Env.nowMillis() - shared.withLock { lastLineAt } < 200
            ) {
                Env.sleepMillis(50)
            }
        }
        val (bl, fl) = shared.withLock { boundsLine to foregroundLine }
        return withConsoleLines(readRaw(hdc, session), bl, fl)
    }

    /** 屏幕尺寸和前台 Ability 只出现在录制开头的终端行里，csv 本身没有。写进原文，之后重转还能用。 */
    private fun withConsoleLines(raw: String, boundsLine: String?, foregroundLine: String?): String {
        val header = mutableListOf<String>()
        if (!boundsLine.isNullOrBlank() && RecordScript.parseWindowBounds(raw) == null) header += boundsLine
        if (!foregroundLine.isNullOrBlank() && RecordScript.parseForeground(raw) == null) header += foregroundLine
        if (header.isEmpty()) return raw
        return header.joinToString("\n") + "\n" + raw
    }


    private fun alive(pid: Long): Boolean =
        isProcessAlive(pid)

    /** 最多等 ms 让进程自己退出；超时就返回（调用方再决定要不要 kill）。 */
    private fun awaitDead(pid: Long, ms: Long) {
        val deadline = Env.nowMillis() + ms
        while (alive(pid) && Env.nowMillis() < deadline) Env.sleepMillis(50)
    }

    private fun stop(pid: Long) {
        if (!alive(pid)) return
        terminateProcess(pid)
        awaitDead(pid, 2_000)
        if (alive(pid)) killProcess(pid)
        awaitDead(pid, 2_000)
    }

    private fun readRaw(hdc: Hdc, session: Session?): String {
        var last = ""
        repeat(4) {
            val r = Hdcs.sh(session, hdc, "uitest uiRecord read", 20_000)
            last = r.stdout
            if (last.isNotBlank()) return last
            Env.sleepMillis(400)
        }
        return last
    }

    private fun emit(ctx: RunContext, hdc: Hdc, session: Session?, raw: String, elapsedMs: Long): Int {
        session?.nextPath("uirecord", "json")?.let { path ->
            fs.writeString(path, raw)
            session.register(path, "uirecord")
        }
        val bundle = ctx.options.str("bundle")
        val ability = ctx.options.str("ability") ?: bundle?.let { mainAbility(hdc, session, it) }
        val translated = try {
            RecordScript.translate(raw, bundle, ability)
        } catch (e: RecordScript.Rejected) {
            throw OhdError(
                ExitCode.USAGE,
                e.message ?: "无法生成脚本",
                "加 --bundle <包名> 指定入口，并且至少有一步单击或滑动",
            )
        }
        val scriptPath = scriptPath(ctx, hdc)
        scriptPath.parent?.let { fs.createDirectories(it) }
        fs.writeString(scriptPath, translated.script)
        session?.register(scriptPath, "script")
        if (ctx.out.jsonMode) {
            ctx.out.finish("record", true, buildJsonObject {
                put("bundle", translated.bundle)
                put("ability", translated.ability)
                put("steps", translated.steps)
                put("outline", RecordScript.stepTable(translated.outline))
                put("script", scriptPath.toAbsolutePath().toString())
            }, elapsedMs)
        } else {
            ctx.out.kv(
                listOf(
                    "包名" to translated.bundle,
                    "Ability" to translated.ability,
                    "步数" to translated.steps.toString(),
                    "脚本" to scriptPath.toAbsolutePath().toString(),
                ),
            )
            if (translated.outline.isNotEmpty()) ctx.out.info(RecordScript.stepTable(translated.outline))
            val cwd = ctx.cwd.toAbsolutePath()
            val shown = if (scriptPath.startsWith(cwd)) cwd.relativize(scriptPath) else scriptPath
            ctx.out.info("ohd replay $shown")
        }
        return ExitCode.OK
    }

    /** ohd-evidence/record/record-<设备序列号>-<yyyyMMdd-HHmmss>.ohd，同秒冲突加序号。 */
    private fun scriptPath(ctx: RunContext, hdc: Hdc): OhdPath {
        val device = hdc.connectKey?.takeIf { it.isNotBlank() }
            ?: throw OhdError(ExitCode.NO_DEVICE, "没有设备序列号，不能给录制文件命名")
        return Session.recordScript(device, ctx.cwd)
    }

    private fun mainAbility(hdc: Hdc, session: Session?, bundle: String): String {
        val dump = Hdcs.sh(session, hdc, "bm dump -n $bundle", 10_000).stdout
        return Regex(""""mainAbility"\s*:\s*"([A-Za-z0-9_.]+)"""").find(dump)?.groupValues?.get(1)
            ?: dump.lineSequence().firstOrNull { it.contains("EntryAbility") }
                ?.let { Regex("""([A-Za-z0-9_.]+Ability)""").find(it)?.groupValues?.get(1) }
            ?: "EntryAbility"
    }
}
