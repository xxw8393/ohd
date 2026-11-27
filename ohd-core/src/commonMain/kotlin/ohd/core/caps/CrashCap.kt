package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.DeviceKind
import ohd.core.parsers.AndroidCrashParser
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.CrashParser
import ohd.core.parsers.CrashReport
import ohd.platform.OhdPath
import ohd.platform.Env
import ohd.platform.fmt

/**
 * 崩溃取证：列故障 → 取正文 → 解析。
 * 正文里含 Build info / Fingerprint / buildId，这三样是「换台机器还能对上」的锚点。
 */
private val OHOS_FAULT_NAME_RE = Regex("""[a-z]+-[A-Za-z0-9._]+-\d+-\d{14}.*""")

class CrashCap : Capability {
    override val name = "crash"
    override val summary = "抓崩溃：列故障文件 / 取最新一条 / 解析 reason 与栈"
    override val usage = "ohd crash [--list] [--name <file>] [--details] [--limit N] [--json]"
    override val example = "ohd crash --list --details --limit 20"
    override val accepts: Set<String> = setOf("details", "limit", "list", "name")

    private val base = "hidumper -s 1201 -a '-p Faultlogger"

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "crash")

        val listOnly = ctx.options.bool("list")
        val explicit = ctx.options.str("name")
        val names = fetchList(ctx, hdc, session)
        // 显式 --name 时不要求列表非空：用户可能直接指定一个不在当前列表里的历史报告
        if (names.isEmpty() && explicit == null) {
            ctx.out.note(
                "NO_FAULT",
                "warn",
                if (hdc.kind == DeviceKind.IOS) {
                    "模拟器没有崩溃报告（~/Library/Logs/DiagnosticReports 下无 iOS 相关 .ips）"
                } else {
                    "设备上没有故障日志（Faultlogger 列表为空）"
                },
            )
            if (listOnly) return ExitCode.OK
            throw OhdError(ExitCode.ASSERT_FAILED, "没有可用的崩溃日志", "先复现崩溃，或检查 hilog 里是否有 SIGSEGV")
        }

        if (listOnly) {
            if (ctx.out.jsonMode) {
                ctx.out.finish("crash.list", true, buildJsonObject {
                    put("count", names.size)
                    put("files", buildJsonArray { names.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }, Env.nowMillis() - started)
            } else {
                ctx.out.table(listOf("故障文件"), names.map { listOf(it) })
            }
            return ExitCode.OK
        }

        val wanted = explicit ?: names.first()

        // iOS 没有 hidumper：详情直接从宿主 .ips 解析
        if (hdc.kind == DeviceKind.IOS) {
            val file = ohd.core.parsers.IosCrashParser.dir().resolve(wanted)
            val entry = ohd.core.parsers.IosCrashParser.parse(file)
                ?: throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "无法解析崩溃报告：$wanted",
                    "确认文件存在于 ~/Library/Logs/DiagnosticReports，且是 .ips 格式",
                )
            if (ctx.out.jsonMode) {
                ctx.out.finish("crash", true, buildJsonObject {
                    put("app", entry.appName)
                    put("time", entry.timestamp)
                    put("os", entry.osVersion)
                    entry.exceptionType?.let { put("exception", it) }
                    entry.signal?.let { put("signal", it) }
                    entry.terminateReason?.let { put("termination", it) }
                    put("threads", entry.threadCount)
                }, Env.nowMillis() - started)
            } else {
                println(ohd.core.parsers.IosCrashParser.render(entry))
            }
            return ExitCode.OK
        }
        val ext = if (ctx.options.bool("details")) " --ext" else ""
        // Android 没有「按文件拉取」的概念，crash buffer 全文就是详情
        val cmd = if (hdc.kind == DeviceKind.ANDROID) "logcat -b crash -d -t 2000" else "$base -f $wanted$ext'"
        val r = Hdcs.sh(session, hdc, cmd, 60_000)
        Hdcs.requireOk(r, "读取故障 ${if (hdc.kind == DeviceKind.ANDROID) "crash buffer" else wanted}")

        val report = if (hdc.kind == DeviceKind.ANDROID) AndroidCrashParser.parse(r.stdout, wanted)
                     else CrashParser.parse(r.stdout, wanted)
        val rawFile = session?.let { s ->
            val p = s.nextPath("faultlog", "txt")
            Session.writeText(p, r.stdout)
            s.register(p, "faultlog")
            p
        }

        val limit = ctx.options.int("limit", 12)
        if (ctx.out.jsonMode) {
            ctx.out.finish("crash", true, crashJson(report, rawFile, names, limit), Env.nowMillis() - started)
        } else {
            printHuman(ctx, report, names, wanted, rawFile, limit)
        }
        return ExitCode.OK
    }

    private fun fetchList(ctx: RunContext, hdc: ohd.core.Hdc, session: Session?): List<String> {
        if (hdc.kind == DeviceKind.IOS) {
            // 崩溃报告在宿主，不需要碰设备
            return ohd.core.parsers.IosCrashParser.list(50).map { it.name }
        }
        if (hdc.kind == DeviceKind.ANDROID) return fetchListAndroid(session, hdc)
        val r = Hdcs.sh(session, hdc, "$base -l'", 30_000)
        if (!r.ok) throw OhdError(ExitCode.UNSUPPORTED, "读取故障列表失败：${r.text().take(200)}")
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("-") }
            .filter { it != "Fault log list:" && !it.contains("HiviewService") && !it.contains("[ability]") }
            .filter { it.matches(OHOS_FAULT_NAME_RE) }
            .map { it.substringBefore(' ').trim() }
    }

    /** Android：crash buffer 里每个崩溃的标识行（Java 异常首行 / Native 信号行）。 */
    private fun fetchListAndroid(session: Session?, hdc: ohd.core.Hdc): List<String> {
        val r = Hdcs.sh(session, hdc, "logcat -b crash -d", 30_000)
        if (!r.ok) throw OhdError(ExitCode.UNSUPPORTED, "读取 crash buffer 失败：${r.text().take(200)}")
        return r.text().lines()
            .filter { it.contains("FATAL EXCEPTION") || it.contains("Fatal signal") }
            .map { it.trim() }
            .distinct()
    }

    private fun crashJson(report: CrashReport, rawFile: OhdPath?, names: List<String>, limit: Int) =
        buildJsonObject {
            put("kind", report.kind)
            put("name", rawFile?.fileName?.toString() ?: "")
            put("reason", report.reason)
            put("module", report.module)
            put("pid", report.pid)
            put("abi", report.abi)
            put("buildInfo", report.buildInfo)
            put("fingerprint", report.fingerprint)
            put("timestamp", report.timestamp)
            put("releaseType", report.releaseType)
            put("device", report.device)
            put("faultCount", names.size)
            put("jsStack", buildJsonArray {
                report.jsStack.take(limit).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("nativeFrames", buildJsonArray {
                report.nativeFrames.take(limit).forEach { f ->
                    add(buildJsonObject {
                        put("index", f.index)
                        put("pc", fmt("0x%x", f.pc))
                        put("so", f.soName)
                        put("symbol", f.inlineSymbol ?: "")
                        put("buildId", f.buildId ?: "")
                    })
                }
            })
            put("buildIds", buildJsonObject {
                report.buildIds().forEach { (k, v) -> put(k, v) }
            })
            put("rawFile", rawFile?.toString() ?: "")
        }

    private fun printHuman(
        ctx: RunContext,
        report: CrashReport,
        names: List<String>,
        wanted: String,
        rawFile: OhdPath?,
        limit: Int,
    ) {
        ctx.out.kv(
            listOf(
                "类型" to report.kind,
                "文件" to wanted,
                "原因" to report.reason,
                "模块" to report.module,
                "PID" to report.pid.toString(),
                "时间" to report.timestamp,
                "构建" to report.buildInfo,
                "ABI" to report.abi,
                "构建类型" to report.releaseType,
                "Fingerprint" to report.fingerprint.take(16),
                "设备故障数" to names.size.toString(),
            ),
        )
        if (report.jsStack.isNotEmpty()) {
            println()
            ctx.out.info("JS 栈（${report.jsStack.size} 行）：")
            report.jsStack.take(limit).forEach { println("  $it") }
        }
        if (report.pageHistory.isNotEmpty()) {
            println()
            ctx.out.info("页面轨迹（崩溃前）：")
            report.pageHistory.take(8).forEach { println("  $it") }
        }
        if (report.nativeFrames.isNotEmpty()) {
            println()
            ctx.out.info("Native 栈（${report.nativeFrames.size} 帧，前 $limit）：")
            report.nativeFrames.take(limit).forEach { f ->
                val sym = f.inlineSymbol?.let { " $it" } ?: ""
                println(fmt("  #%02d 0x%08x %s%s", f.index, f.pc, f.soName, sym.take(90)))
            }
            println()
            ctx.out.info("下一步：ohd sym --crash ${rawFile ?: "<文件>"} --so-dir <debug so 目录>")
        }
        println()
        ctx.out.kv(listOf("原始日志" to (rawFile?.toString() ?: "-")))
    }
}
