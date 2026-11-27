package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.RunContext
import ohd.core.analysis.CaseFacts
import ohd.platform.Env
import ohd.platform.fmt

/**
 * 把证据包提炼成事实清单。
 *
 * 纪律：本命令**不产出根因**。它只做三件事——
 *   1. 把散在 6 个文件里的数字/栈/状态汇总成一张表；
 *   2. 明确哪些必备件缺失、因此哪些结论不可下；
 *   3. 给出「下一步该采集什么」，而不是「问题出在哪」。
 * 解析全部走 [CaseFacts]，与 `report` 共用同一份数字。
 */
class AnalyzeCap : Capability {
    override val name = "analyze"
    override val summary = "证据包 → 事实清单（崩溃/栈/指标/完备性 + 下一步方向，不下根因）"
    override val usage = "ohd analyze [<case-dir>] [--top N] [--json]"
    override val example = "ohd analyze --top 20 --json"
    override val accepts: Set<String> = setOf("top")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val dir = CaseFacts.locate(ctx.options.positionals.firstOrNull())
        val top = ctx.options.int("top", 5)
        val f = CaseFacts.load(dir, top)

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-analysis/1")
                put("case", buildJsonObject {
                    put("dir", dir.toAbsolutePath().toString())
                    put("capability", f.capability)
                    f.device?.let { put("device", it) }
                    put("redacted", f.redacted)
                })
                put("crash", buildJsonObject {
                    val c = f.crash
                    if (c == null) {
                        put("present", false)
                    } else {
                        put("present", true)
                        put("kind", c.kind)
                        put("reason", c.reason)
                        put("module", c.module)
                        put("pid", c.pid)
                        put("timestamp", c.timestamp)
                        put("buildInfo", c.buildInfo)
                        put("abi", c.abi)
                        put("fingerprint", c.fingerprint)
                        put("jsStackDepth", c.jsStack.size)
                        put("threadHeaders", buildJsonArray { c.threadHeaders.forEach { add(JsonPrimitive(it)) } })
                    }
                })
                put("stack", buildJsonArray {
                    f.stack.frames.forEach { fr ->
                        add(buildJsonObject {
                            put("index", fr.index)
                            put("so", fr.so)
                            put("pc", fr.pc)
                            put("function", fr.function)
                            put("location", fr.location)
                            put("status", fr.status)
                        })
                    }
                })
                put("metrics", buildJsonObject {
                    val m = f.metrics
                    if (m == null) {
                        put("present", false)
                    } else {
                        put("present", true)
                        put("count", m.count)
                        put("spanSec", m.spanSec)
                        put("pssFirstKb", m.pssFirst)
                        put("pssLastKb", m.pssLast)
                        put("pssMinKb", m.pssMin)
                        put("pssMaxKb", m.pssMax)
                        put("slopeMbPerSec", m.slopeMbPerSec)
                        put("shape", m.shape)
                        put("cpuAvg", m.cpuAvg)
                    }
                })
                put("frames", buildJsonObject {
                    put("count", f.frames.size)
                    put("sizes", buildJsonArray {
                        f.frames.map { "${it.width}x${it.height}" }.distinct().forEach { add(JsonPrimitive(it)) }
                    })
                })
                put("repro", buildJsonObject {
                    put("steps", f.stepLines.size)
                    put("lines", buildJsonArray { f.stepLines.forEach { add(JsonPrimitive(it)) } })
                })
                put("logs", buildJsonObject {
                    put("lines", f.logLines)
                    put("bytes", f.logBytes)
                })
                put("completeness", buildJsonObject {
                    put("items", buildJsonArray {
                        f.missing.forEach { (k, r) ->
                            add(buildJsonObject { put("key", k); put("reason", r) })
                        }
                    })
                    put("forbiddenConclusions", buildJsonArray {
                        CaseFacts.forbiddenConclusions(f.completeness).forEach { add(JsonPrimitive(it)) }
                    })
                })
                put("nextSteps", buildJsonArray { f.hints.forEach { add(JsonPrimitive(it)) } })
                put("note", "本清单只含可证伪事实与补证方向；根因判断请交给开发者或分析 AI。")
            }
            ctx.out.finish("analyze", true, data, Env.nowMillis() - started)
            return ExitCode.OK
        }

        ctx.out.kv(
            listOf(
                "证据包" to dir.fileName.toString(),
                "来源" to f.capability,
                "产物数" to f.artifactCount.toString(),
                "脱敏" to if (f.redacted) "已开启" else "未开启",
            ),
        )
        println()

        section(ctx, "崩溃")
        val c = f.crash
        if (c == null) {
            ctx.out.note("NO_CRASH", "warn", "本包没有崩溃原文，仅能说明现象")
        } else {
            ctx.out.kv(
                listOfNotNull(
                    "类型" to c.kind,
                    "原因" to c.reason.ifEmpty { "-" },
                    "模块" to c.module.ifEmpty { "-" },
                    "进程" to c.pid.toString(),
                    "时间" to c.timestamp.ifEmpty { "-" },
                    "构建" to c.buildInfo.ifEmpty { "-" },
                    "ABI" to c.abi.ifEmpty { "-" },
                    "JS 栈" to if (c.jsStack.isEmpty()) "无" else "${c.jsStack.size} 层",
                    "主线程" to (c.threadHeaders.firstOrNull() ?: "-"),
                ),
            )
        }
        println()

        section(ctx, "调用栈（前 $top 帧）")
        if (f.stack.frames.isEmpty()) {
            ctx.out.info("无可用栈帧")
        } else {
            ctx.out.table(
                listOf("#", "so", "地址", "函数", "状态"),
                f.stack.frames.map {
                    listOf(
                        it.index.toString(),
                        trunc(it.so, 24),
                        it.pc,
                        trunc(it.function.ifEmpty { "-" }, 56),
                        trunc(it.status, 22),
                    )
                },
            )
            ctx.out.info(
                "有符号 ${f.stack.symbolized}/${f.stack.frames.size}（本地 ${f.stack.localOnly}，其余靠日志自带），" +
                    "本地 so 不可用 ${f.stack.missingSo}",
            )
            ctx.out.info("函数名已截断，完整名称见 --json")
        }
        println()

        section(ctx, "指标")
        val m = f.metrics
        if (m == null) {
            ctx.out.info("无指标序列")
        } else {
            ctx.out.kv(
                listOf(
                    "采样" to "${m.count} 个 / ${fmt("%.1f", m.spanSec)}s",
                    "PSS" to "${fmtMb(m.pssFirst)} → ${fmtMb(m.pssLast)}（min ${fmtMb(m.pssMin)} / max ${fmtMb(m.pssMax)}）",
                    "斜率" to fmt("%+.2f MB/s", m.slopeMbPerSec),
                    "走势" to m.shape,
                    "CPU 均值" to fmt("%.1f%%", m.cpuAvg),
                ),
            )
        }
        println()

        section(ctx, "界面与复现")
        ctx.out.kv(
            listOf(
                "截图" to if (f.frames.isEmpty()) "无"
                else "${f.frames.size} 张（${f.frames.map { "${it.width}x${it.height}" }.distinct().joinToString("/")}）",
                "复现" to if (f.stepLines.isEmpty()) "无" else "${f.stepLines.size} 步",
                "日志" to if (f.logLines == 0) "无" else "${f.logLines} 行",
            ),
        )
        println()

        section(ctx, "证据完备性")
        val summary = f.completeness?.get("summary")?.jsonObject
        val ok = summary?.get("ok")?.jsonPrimitive?.contentOrNull ?: "?"
        val miss = summary?.get("missing")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: f.missing.size
        ctx.out.info("$ok/${(ok.toIntOrNull() ?: 0) + miss} 项齐全")
        f.missing.forEach { (k, r) -> ctx.out.note("MISSING_$k".uppercase(), "warn", r) }

        println()
        section(ctx, "下一步方向（不是结论）")
        f.hints.forEachIndexed { i, h -> ctx.out.info("${i + 1}. $h") }
        return ExitCode.OK
    }

    private fun section(ctx: RunContext, title: String) {
        ctx.out.info("── $title " + "─".repeat(maxOf(0, 28 - title.length)))
    }

    private fun fmtMb(kb: Long): String = fmt("%.1f MB", kb / 1024.0)

    private fun trunc(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1) + "…"
}
