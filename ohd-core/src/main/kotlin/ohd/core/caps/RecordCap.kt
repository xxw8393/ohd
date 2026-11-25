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
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "record")
        val raw = if (ctx.options.positionals.firstOrNull() == "read") {
            readRaw(hdc, session)
        } else {
            capture(ctx, hdc, session)
        }
        return emit(ctx, hdc, session, raw, System.currentTimeMillis() - started)
    }

    private fun capture(ctx: RunContext, hdc: Hdc, session: Session?): String {
        Hdcs.sh(session, hdc, "rm -f /data/local/tmp/record.csv", 10_000)
        val proc = ProcessBuilder(hdc.argv("shell", "uitest", "uiRecord", "record", "-W", "true", "-c", "true"))
            .redirectErrorStream(true)
            .start()
        proc.outputStream.close()
        val boundsLine = AtomicReference<String?>(null)
        val foregroundLine = AtomicReference<String?>(null)
        val pump = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        System.err.println(line)
                        val trimmed = line.trim()
                        if (boundsLine.get() == null && RecordScript.parseWindowBounds(trimmed) != null) {
                            boundsLine.set(trimmed)
                        }
                        if (foregroundLine.get() == null && RecordScript.parseForeground(trimmed) != null) {
                            foregroundLine.set(trimmed)
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val duration = ctx.options.str("duration")?.let { parseDurationMs(it) }
        System.err.println(
            if (duration == null) "在手机上操作。录完回到这个终端按 Ctrl+C。"
            else "在手机上操作。${duration}ms 后自动停止。",
        )
        onInterrupt { stop(proc) }
        val hook = Thread { runCatching { stop(proc) } }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            if (duration != null) proc.waitFor(duration, TimeUnit.MILLISECONDS) else proc.waitFor()
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
            stop(proc)
            pump.join(2_000)
        }
        return withConsoleLines(readRaw(hdc, session), boundsLine.get(), foregroundLine.get())
    }

    /** 屏幕尺寸和前台 Ability 只出现在录制开头的终端行里，csv 本身没有。写进原文，之后重转还能用。 */
    private fun withConsoleLines(raw: String, boundsLine: String?, foregroundLine: String?): String {
        val header = mutableListOf<String>()
        if (!boundsLine.isNullOrBlank() && RecordScript.parseWindowBounds(raw) == null) header += boundsLine
        if (!foregroundLine.isNullOrBlank() && RecordScript.parseForeground(raw) == null) header += foregroundLine
        if (header.isEmpty()) return raw
        return header.joinToString("\n") + "\n" + raw
    }

    /** Ctrl+C 只结束录制，不退出进程，这样后面还能把脚本写出来。 */
    private fun onInterrupt(block: () -> Unit) {
        runCatching {
            val signalCls = Class.forName("sun.misc.Signal")
            val handlerCls = Class.forName("sun.misc.SignalHandler")
            val sig = signalCls.getConstructor(String::class.java).newInstance("INT")
            val handler = java.lang.reflect.Proxy.newProxyInstance(
                handlerCls.classLoader,
                arrayOf(handlerCls),
            ) { _, method, _ ->
                if (method.name == "handle") block()
                null
            }
            signalCls.getMethod("handle", signalCls, handlerCls).invoke(null, sig, handler)
        }
    }

    private fun stop(proc: Process) {
        if (!proc.isAlive) return
        proc.destroy()
        if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        proc.waitFor(2, TimeUnit.SECONDS)
    }

    private fun readRaw(hdc: Hdc, session: Session?): String {
        var last = ""
        repeat(4) {
            val r = Hdcs.sh(session, hdc, "uitest uiRecord read", 20_000)
            last = r.stdout
            if (last.isNotBlank()) return last
            Thread.sleep(400)
        }
        return last
    }

    private fun emit(ctx: RunContext, hdc: Hdc, session: Session?, raw: String, elapsedMs: Long): Int {
        session?.nextPath("uirecord", "json")?.let { path ->
            Files.writeString(path, raw)
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
        Files.createDirectories(scriptPath.parent)
        Files.writeString(scriptPath, translated.script)
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
    private fun scriptPath(ctx: RunContext, hdc: Hdc): Path {
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
