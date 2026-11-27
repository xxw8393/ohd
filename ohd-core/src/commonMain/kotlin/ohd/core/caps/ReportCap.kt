package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.analysis.CaseFacts
import ohd.core.analysis.CaseReport
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env

/**
 * 证据包 → 可直接提交的缺陷单。
 *
 * 与 `analyze` 的分工：`analyze` 是给人/AI 看的事实清单（宽而全），
 * `report` 是给 issue 系统看的单据（窄而固定）。两者共用 [CaseFacts]，数字必然一致。
 *
 * 正文渲染在 [CaseReport]，供 `bundle --report` 复用。
 */
class ReportCap : Capability {
    override val name = "report"
    override val summary = "证据包 → 缺陷单 report.md（含复现步骤、栈、指标、禁用结论）"
    override val usage = "ohd report [<case-dir>] [--out <file>] [--json]"
    override val example = "ohd report --out /tmp/report.md --json"
    override val accepts: Set<String> = setOf("out")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val dir = CaseFacts.locate(ctx.options.positionals.firstOrNull())
        val f = CaseFacts.load(dir, 8)
        val md = CaseReport.render(f)
        val out = ctx.options.str("out")?.let { OhdPath.of(it) } ?: dir.resolve("report.md")
        out.toAbsolutePath().parent?.let { fs.createDirectories(it) }
        Session.writeText(out, md)

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-report/1")
                put("file", out.toAbsolutePath().toString())
                put("title", CaseReport.titleOf(f))
                put("bytes", runCatching { fs.size(out) }.getOrDefault(0L))
                put("missing", f.missing.size)
                put("forbidden", CaseFacts.forbiddenConclusions(f.completeness).size)
            }
            ctx.out.finish("report", true, data, Env.nowMillis() - started)
            return ExitCode.OK
        }

        println(md)
        if (!ctx.out.jsonMode) {
            ctx.out.info("缺陷单已写入：${out.toAbsolutePath()}")
        }
        return ExitCode.OK
    }
}
