package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Faults
import ohd.core.Hdc
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.actions.UiActions
import ohd.core.analysis.CaseFacts
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath

/**
 * 按证据包的步骤重放，回答一个问题：**这个缺陷还能复现吗**。
 *
 * 判定不看界面像不像，只看故障日志里有没有**新增同类崩溃**：
 * 故障名形如 `appfreeze-com.tencent.compose-20020221-20261016181424`，
 * 因此「重放前后故障列表的差集」+「kind/module 匹配」就是可证伪的复现证据。
 *
 * 界面差异只作为参考信息提示，不参与判定——画面不同可能是主题/时间/分辨率导致的，
 * 把它当复现标准会产生大量假阳性。
 */
class ReproCap : Capability {
    override val name = "repro"
    override val summary = "按证据包步骤重放，判断缺陷是否复现（以新增同类故障为准）"
    override val usage = "ohd repro [<case-dir>] [--steps <file>] [--settle <2s>] [--json]"
    override val example = "ohd repro --settle 3s --json"
    override val accepts: Set<String> = setOf("settle", "steps")

    private val replay = ReplayCap()

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val dir = CaseFacts.locate(ctx.options.positionals.firstOrNull())
        val facts = CaseFacts.load(dir)

        val stepsFile = ctx.options.str("steps")?.let { OhdPath.of(it) } ?: dir.resolve("repro/steps.ohd")
        if (!fs.isRegularFile(stepsFile)) {
            throw OhdError(
                ExitCode.USAGE,
                "包里没有复现步骤：$stepsFile",
                "用 --steps 指定脚本，或让测试同学用 ohd replay 记录操作后再 bundle",
            )
        }
        val actions = try {
            UiActions.parseScript(fs.readString(stepsFile))
        } catch (e: UiActions.ScriptError) {
            throw OhdError(ExitCode.USAGE, "脚本语法错误：${e.message}")
        }
        if (actions.isEmpty()) throw OhdError(ExitCode.USAGE, "脚本里没有可执行动作")

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "repro")

        val expectKind = facts.crash?.kind ?: ""
        val expectModule = facts.crash?.module ?: ""

        val before = Faults.list(session, hdc)
        if (!ctx.out.jsonMode) {
            ctx.out.kv(
                listOf(
                    "证据包" to dir.fileName.toString(),
                    "脚本" to stepsFile.fileName.toString(),
                    "步骤" to actions.size.toString(),
                    "期望" to if (expectKind.isEmpty()) "（包内无崩溃，仅重放）" else "$expectKind / $expectModule",
                    "故障基线" to "${before.size} 个",
                ),
            )
            println()
        }

        val results = actions.map { a -> replay.execute(ctx, hdc, session, a) }
        val failed = results.count { !it.ok }

        // 给 faultlogger 落盘留时间：崩溃日志不是同步写出的
        val settle = ctx.options.str("settle") ?: "2s"
        Env.sleepMillis(parseMs(settle))

        val after = Faults.list(session, hdc)
        val fresh = after.filter { it !in before.toSet() }
        val freshParsed = Faults.diff(before, after).map { Triple(it.kind, it.module, it.raw) }
        val matched = freshParsed.filter { it.first == expectKind && it.second == expectModule }

        val verdict: String
        val exit: Int
        when {
            matched.isNotEmpty() -> {
                verdict = "CRASH_REPRODUCED"
                exit = ExitCode.OK
            }
            fresh.isNotEmpty() -> {
                verdict = "CRASH_DIFFERENT"
                exit = ExitCode.MISMATCH
            }
            expectKind.isNotEmpty() -> {
                verdict = "CRASH_NOT_REPRODUCED"
                exit = ExitCode.MISMATCH
            }
            else -> {
                verdict = "NO_CRASH_EXPECTED"
                exit = if (failed > 0) ExitCode.MISMATCH else ExitCode.OK
            }
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-repro/1")
                put("case", dir.fileName.toString())
                put("script", stepsFile.toString())
                put("steps", actions.size)
                put("failedSteps", failed)
                put("verdict", verdict)
                put("expected", buildJsonObject {
                    put("kind", expectKind); put("module", expectModule)
                })
                put("faultsBefore", before.size)
                put("faultsAfter", after.size)
                put("newFaults", buildJsonArray { fresh.forEach { add(JsonPrimitive(it)) } })
                put("matchedFaults", buildJsonArray { matched.map { it.third }.forEach { add(JsonPrimitive(it)) } })
                put("stepResults", buildJsonArray {
                    results.forEach { r ->
                        add(buildJsonObject {
                            put("line", r.lineNo); put("action", r.raw); put("ok", r.ok)
                            put("detail", r.detail); put("elapsedMs", r.elapsedMs)
                        })
                    }
                })
            }
            ctx.out.finish("repro", verdict == "CRASH_REPRODUCED" || exit == ExitCode.OK, data, Env.nowMillis() - started)
            return exit
        }

        ctx.out.kv(listOf("失败步骤" to failed.toString(), "故障基线" to "${before.size} → ${after.size}"))
        println()
        if (fresh.isEmpty()) {
            ctx.out.info("重放期间没有出现新故障")
        } else {
            ctx.out.info("新增故障：")
            fresh.forEach { ctx.out.info("  $it") }
            println()
        }

        when (verdict) {
            "CRASH_REPRODUCED" -> {
                ctx.out.info("判定：**已复现** —— 重放后出现了与包内同类型同模块的崩溃")
                ctx.out.info("对应故障：${matched.first().third}")
            }
            "CRASH_DIFFERENT" -> {
                ctx.out.note("CRASH_DIFFERENT", "warn", "出现了新故障，但与包内的 $expectKind / $expectModule 不一致")
                ctx.out.info("判定：现象不同，可能是另一个问题，也可能是环境差异")
            }
            "CRASH_NOT_REPRODUCED" -> {
                ctx.out.info("判定：**未复现** —— 重放期间未出现 $expectKind / $expectModule")
                ctx.out.info("常见原因：构建已更新、设备状态不同、步骤不完整")
            }
            else -> {
                ctx.out.info("判定：包内没有崩溃，本次只验证步骤可执行性（失败 $failed 步）")
            }
        }
        if (failed > 0) {
            println()
            ctx.out.note("STEPS_FAILED", "warn", "$failed 步执行失败，复现结论可能不成立，先看上面的步骤明细")
        }
        return exit
    }

    /** 兼容测试入口：解析交给共用的 Faults */
    internal fun parseFaultName(name: String): Triple<String, String, String>? =
        Faults.parse(name)?.let { Triple(it.kind, it.module, it.raw) }

    private fun parseMs(s: String): Long {
        val t = s.trim().lowercase()
        return when {
            t.endsWith("ms") -> t.dropLast(2).toLongOrNull() ?: 2000
            t.endsWith("s") -> (t.dropLast(1).toDoubleOrNull() ?: 2.0).let { (it * 1000).toLong() }
            else -> t.toLongOrNull() ?: 2000
        }
    }
}
