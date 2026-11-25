package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.analysis.CaptureReport
import ohd.core.analysis.TriagePlaybook
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser
import java.nio.file.Paths

/**
 * 自动化 REPORT / anomalies → 调查赛道；可选 `--reverify` 调 dmabuf/mem 做短跑复验。
 *
 * 不下根因：只派发 skill 赛道与可证伪检查。`--reverify` 产出 PASS/FAIL/INCONCLUSIVE。
 */
class TriageCap : Capability {
    override val name = "triage"
    override val summary = "REPORT/anomalies → 调查赛道；--reverify 调用 dmabuf/mem 短跑复验"
    override val usage =
        "ohd triage <capture-dir> [--track auto|pixelmap|xcomponent|native-heap|registry|hide] " +
            "[--reverify] [--fresh] [--bundle pkg] [--json]"
    override val example =
        "ohd triage /Users/…/automation-captures/20261104-011052 --reverify --bundle com.tencent.compose"
    override val accepts: Set<String> = setOf("bundle", "fresh", "json", "reverify", "track")

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val dir = Paths.get(ctx.options.positionals.firstOrNull() ?: throw OhdError(
            ExitCode.USAGE,
            "需要 capture 目录",
            "示例：ohd triage /path/to/automation-captures/YYYYMMDD-HHMMSS",
        )).toAbsolutePath().normalize()

        val snap = runCatching { CaptureReport.load(dir) }.getOrElse {
            throw OhdError(ExitCode.USAGE, "无法读取采集目录：$dir", it.message ?: "需要 meta.json 或 anomalies.log")
        }
        val summary = TriagePlaybook.summarizeAnomalies(snap.anomalyLines)
        val plan = TriagePlaybook.plan(
            tags = summary.counts,
            stickyGraphDeltaZero = summary.stickyGraphDeltaZero,
            stickyTotalGrowth = summary.stickyTotalGrowth || summary.counts.containsKey("native_alloc_growth"),
            pixelMapLadder = snap.pixelMapLadder,
            xcomponentSticky = snap.xcomponentRising,
            durationMin = snap.durationMin,
            crit = snap.crit,
            endRegistryAligned = snap.endRegistryAligned,
        )

        val trackFilter = ctx.options.str("track") ?: "auto"
        val findings = plan.findings.filter { f ->
            when (trackFilter) {
                "auto" -> true
                else -> f.track.id == trackFilter || f.track.name.equals(trackFilter, ignoreCase = true)
            }
        }
        if (findings.isEmpty() && trackFilter != "auto") {
            throw OhdError(ExitCode.USAGE, "无匹配赛道：$trackFilter", "可用：auto|pixelmap|xcomponent|native-heap|registry|hide")
        }

        val bundle = ctx.options.str("bundle") ?: snap.bundle ?: "com.tencent.compose"
        val doReverify = ctx.options.bool("reverify")
        val fresh = ctx.options.bool("fresh")

        val reverifyResults = if (doReverify) {
            reverify(ctx, bundle, fresh, findings.map { it.track }.distinct())
        } else {
            emptyList()
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-triage/1")
                put("capture", dir.toString())
                put("bundle", bundle)
                put("durationMin", snap.durationMin ?: -1.0)
                put("info", snap.info)
                put("warn", snap.warn)
                put("crit", snap.crit)
                put("peakTotalMb", snap.peakTotalMb ?: -1.0)
                put("peakGraphMb", snap.peakGraphMb ?: -1.0)
                put("tagCounts", buildJsonObject {
                    summary.counts.entries.sortedByDescending { it.value }.forEach { (k, v) -> put(k, v) }
                })
                put("findings", buildJsonArray {
                    findings.forEach { f ->
                        add(buildJsonObject {
                            put("track", f.track.id)
                            put("skill", f.track.skill)
                            put("recipe", f.track.recipe)
                            put("severity", f.severity)
                            put("tag", f.tag)
                            put("detail", f.detail)
                            put("investigate", buildJsonArray { f.investigate.forEach { add(JsonPrimitive(it)) } })
                            put("reverifyHint", f.reverifyHint)
                        })
                    }
                })
                put("notes", buildJsonArray { plan.notes.forEach { add(JsonPrimitive(it)) } })
                put("reverify", buildJsonArray {
                    reverifyResults.forEach { r ->
                        add(buildJsonObject {
                            put("track", r.track)
                            put("verdict", r.verdict)
                            put("detail", r.detail)
                        })
                    }
                })
                put("note", "只派发调查赛道与短跑复验事实；根因请结合 skill/代码判断。")
            }
            ctx.out.finish("triage", true, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }

        ctx.out.kv(
            listOfNotNull(
                "采集" to dir.fileName.toString(),
                "包名" to bundle,
                "时长" to (snap.durationMin?.let { "%.1f min".format(it) } ?: "?"),
                "info/warn/crit" to "${snap.info}/${snap.warn}/${snap.crit}",
                "Peak Total/Graph" to listOfNotNull(
                    snap.peakTotalMb?.let { "%.1f".format(it) },
                    snap.peakGraphMb?.let { "%.1f".format(it) },
                ).joinToString(" / ").ifEmpty { "-" } + " MB",
                "registry 末态" to when (snap.endRegistryAligned) {
                    true -> "kotlin=${snap.kotlinCount} native=${snap.nativeRemaining} 对齐"
                    false -> "kotlin=${snap.kotlinCount} native=${snap.nativeRemaining} 不对齐"
                    null -> "-"
                },
            ),
        )
        println()
        ctx.out.info("── 调查赛道 " + "─".repeat(20))
        findings.forEachIndexed { i, f ->
            ctx.out.info("${i + 1}. [${f.severity}] ${f.track.id} ← ${f.track.skill} / ${f.track.recipe}")
            ctx.out.info("   ${f.detail}")
            f.investigate.forEach { ctx.out.info("   → $it") }
            ctx.out.info("   短跑期望：${f.reverifyHint}")
            println()
        }
        plan.notes.forEach { ctx.out.info("· $it") }

        if (doReverify) {
            println()
            ctx.out.info("── 短跑复验 " + "─".repeat(20))
            if (reverifyResults.isEmpty()) {
                ctx.out.note("REVERIFY_SKIP", "warn", "无选定赛道可自动复验（或设备不可达）")
            } else {
                ctx.out.table(
                    listOf("赛道", "结论", "细节"),
                    reverifyResults.map { listOf(it.track, it.verdict, it.detail.take(80)) },
                )
            }
            ctx.out.info("自动复验只采样水位/dmabuf；完整进退驱动请按 investigate 命令或 short-run recipes 手跑/ohd replay。")
        } else {
            println()
            ctx.out.info("加 --reverify 可在本机对活进程跑 dmabuf/mem 短采样；--fresh 先 force-stop。")
        }

        val failed = reverifyResults.any { it.verdict == "FAIL" }
        return if (failed) ExitCode.ASSERT_FAILED else ExitCode.OK
    }

    private data class ReverifyResult(val track: String, val verdict: String, val detail: String)

    private fun reverify(
        ctx: RunContext,
        bundle: String,
        fresh: Boolean,
        tracks: List<TriagePlaybook.Track>,
    ): List<ReverifyResult> {
        val actionable = tracks.filter {
            it in setOf(
                TriagePlaybook.Track.NATIVE_HEAP,
                TriagePlaybook.Track.XCOMPONENT,
                TriagePlaybook.Track.PIXELMAP,
                TriagePlaybook.Track.REGISTRY,
            )
        }
        if (actionable.isEmpty()) return emptyList()

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "triage")
        if (fresh) {
            Hdcs.sh(session, hdc, "aa force-stop $bundle", 15_000)
            Thread.sleep(800)
            Hdcs.sh(session, hdc, "aa start -b $bundle -a EntryAbility", 15_000)
            Thread.sleep(2500)
        }

        val pid = Hdcs.sh(session, hdc, "pidof $bundle", 10_000).text().trim()
            .lineSequence().firstOrNull()?.trim()
        if (pid.isNullOrEmpty()) {
            return listOf(ReverifyResult("device", "INCONCLUSIVE", "进程未运行：$bundle（可去 --fresh 或先手动启动）"))
        }

        val out = mutableListOf<ReverifyResult>()
        val needDma = actionable.any {
            it == TriagePlaybook.Track.XCOMPONENT || it == TriagePlaybook.Track.PIXELMAP ||
                it == TriagePlaybook.Track.REGISTRY
        }
        val dma = if (needDma) {
            val raw = Hdcs.sh(session, hdc, "cat /proc/$pid/mm_dmabuf_info", 20_000)
            if (!raw.ok) null else DmabufParser.parse(raw.text())
        } else {
            null
        }

        if (TriagePlaybook.Track.NATIVE_HEAP in actionable) {
            val memRaw = Hdcs.sh(session, hdc, "hidumper --mem $pid", 20_000).text()
            val mem = MemParser.parse(memRaw)
            val nh = mem.row("native heap")
            val allocKb = nh?.col(8) ?: -1L
            val freeKb = nh?.col(9) ?: -1L
            val graphKb = mem.graphKb
            out += ReverifyResult(
                track = "native-heap",
                verdict = "OBSERVED",
                detail = "HeapAlloc=${"%.1f".format(allocKb / 1024.0)}MB Free=${"%.1f".format(freeKb / 1024.0)}MB " +
                    "Graph=${"%.1f".format(graphKb / 1024.0)}MB（单点；往返差分请按 R4 手跑）",
            )
        }

        if (dma != null && TriagePlaybook.Track.XCOMPONENT in actionable) {
            val xc = dma.groups.filter { it.label.contains("xcomponent", ignoreCase = true) || it.types.any { t -> t.contains("xcomponent", true) } }
            val n = xc.sumOf { it.count }
            val mb = xc.sumOf { it.bytes } / (1024.0 * 1024.0)
            val verdict = when {
                n <= 3 -> "PASS"
                n <= 5 -> "WARN"
                else -> "FAIL"
            }
            out += ReverifyResult(
                track = "xcomponent",
                verdict = verdict,
                detail = "xcomponent 块=$n / ${"%.1f".format(mb)}MB（首页期望~2；多实例页可更高） labels=${xc.map { it.label }.take(5)}",
            )
        }

        if (dma != null && TriagePlaybook.Track.PIXELMAP in actionable) {
            val pm = dma.groups.filter {
                it.label.contains("pixel", ignoreCase = true) || it.label.contains("srcImage", ignoreCase = true)
            }
            val n = pm.sumOf { it.count }
            val mb = pm.sumOf { it.bytes } / (1024.0 * 1024.0)
            out += ReverifyResult(
                track = "pixelmap",
                verdict = if (n == 0) "PASS" else "OBSERVED",
                detail = "pixelMap 块=$n / ${"%.1f".format(mb)}MB（首页期望 0；页内持有属 OBSERVED） labels=${pm.map { it.label }.take(5)}",
            )
        }

        if (TriagePlaybook.Track.REGISTRY in actionable) {
            out += ReverifyResult(
                track = "registry",
                verdict = "INCONCLUSIVE",
                detail = "需 hilog Unregister/destroyRender 对拍；当前仅确认 pid=$pid 存活" +
                    (dma?.let { " dmabuf条目=${it.entries.size}" } ?: ""),
            )
        }
        return out
    }
}
