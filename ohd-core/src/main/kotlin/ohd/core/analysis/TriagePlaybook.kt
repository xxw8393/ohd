package ohd.core.analysis

/**
 * 把 automation REPORT / anomalies 标签映射到调查赛道。
 *
 * 纪律（与 analyze 一致）：这里给的是**可执行调查方向与 ohd 命令**，不是根因判决。
 * 映射来源：cmp-core skills（xcomponent-release / dma-image / native-heap / short-run-verify / automation-report）。
 */
object TriagePlaybook {

    enum class Track(val id: String, val skill: String, val recipe: String, val title: String) {
        PIXELMAP("pixelmap", "cmp-core-ohos-dma-image", "R1", "图 DMA / pixelMap 阶梯"),
        XCOMPONENT("xcomponent", "cmp-core-ohos-xcomponent-release", "R2", "XComponent BQ / DestroyCB"),
        NATIVE_HEAP("native-heap", "cmp-core-ohos-native-heap", "R4", "jemalloc / Heap Alloc"),
        REGISTRY("registry", "cmp-core-ohos-lifecycle-symmetry", "R5", "注册表对称 / keeping_egl"),
        HIDE_RECLAIM("hide", "cmp-core-ohos-memory-reclaim", "R3", "hide 深回收"),
        SHORT_WINDOW("short-window", "cmp-core-ohos-automation-report", "-", "短跑规则窗不足"),
    }

    data class Finding(
        val track: Track,
        val severity: String,
        val tag: String,
        val detail: String,
        val investigate: List<String>,
        val reverifyHint: String,
    )

    data class Plan(
        val tracks: List<Track>,
        val findings: List<Finding>,
        val notes: List<String>,
    )

    fun plan(
        tags: Map<String, Int>,
        stickyGraphDeltaZero: Boolean,
        stickyTotalGrowth: Boolean,
        pixelMapLadder: Boolean,
        xcomponentSticky: Boolean,
        durationMin: Double?,
        crit: Int,
        endRegistryAligned: Boolean?,
    ): Plan {
        val findings = mutableListOf<Finding>()
        val notes = mutableListOf<String>()

        if (durationMin != null && durationMin < 15.0) {
            findings += Finding(
                track = Track.SHORT_WINDOW,
                severity = "info",
                tag = "short_run",
                detail = "时长约 ${"%.1f".format(durationMin)}min，ladder_* 常打不出；crit=$crit 不能单独证明比过夜更健康",
                investigate = listOf("ohd triage <capture> --json  # 看主赛道", "对照 overnight REPORT 再下水位结论"),
                reverifyHint = "短跑只证「已知缺陷窗」；要谷底阶梯请加长采集",
            )
        }

        if (pixelMapLadder || (tags.keys.any { it.contains("pixel", ignoreCase = true) })) {
            findings += Finding(
                track = Track.PIXELMAP,
                severity = if (pixelMapLadder) "crit" else "warn",
                tag = "pixelMap",
                detail = if (pixelMapLadder) "dmabuf 序列见 pixelMap 尺寸单调增" else "报告含 pixelMap 相关标签",
                investigate = listOf(
                    "ohd dmabuf snap --bundle <pkg>",
                    "进大图页 → Back/Hide → ohd dmabuf snap  # 期望 pixelMap:* 回落",
                    "hilog 对拍 Unreference|pinDisplay|dmaDrain",
                ),
                reverifyHint = "R1 image-dog：退出后 pixelMap 块≈0",
            )
        }

        if (xcomponentSticky || tags.containsKey("ladder_graph") ||
            (stickyTotalGrowth && !stickyGraphDeltaZero && tags.containsKey("sticky_after_reclaim"))
        ) {
            findings += Finding(
                track = Track.XCOMPONENT,
                severity = if (tags.containsKey("ladder_graph")) "crit" else "warn",
                tag = "xcomponent",
                detail = "Graph/xcomponent 粘滞或 ladder_graph；对拍 DestroyCB 与残留 id",
                investigate = listOf(
                    "ohd dmabuf snap --bundle <pkg>",
                    "MultiInstance 进退 3 轮 → 每次 snap  # 块数勿线性 +1",
                    "hilog: OnSurfaceDestroyedCB|destroyRender|AceXcomponent onDestroy",
                ),
                reverifyHint = "R2：回首页 xcomponent≈2（或池化常数）",
            )
        }

        if (tags.containsKey("native_alloc_growth") ||
            (stickyGraphDeltaZero && stickyTotalGrowth) ||
            tags.keys.any { it.startsWith("ladder_total") }
        ) {
            findings += Finding(
                track = Track.NATIVE_HEAP,
                severity = if (tags.containsKey("ladder_total")) "crit" else "warn",
                tag = "native_heap",
                detail = when {
                    stickyGraphDeltaZero && stickyTotalGrowth ->
                        "sticky_after_reclaim 且 GraphΔ≈0 → Total/PSS 粘，主查 jemalloc Heap Alloc"
                    else -> "native_alloc_growth / ladder_total → Heap Alloc 趋势"
                },
                investigate = listOf(
                    "ohd mem --bundle <pkg> --top 20 --show-dmabuf",
                    "hidumper --mem <pid> | grep 'native heap'  # 只看 Heap Alloc，不看 Swap",
                    "ohd dmabuf snap  # 先排除 Graph 主导",
                    "大图/多实例往返 ≥5 轮记 Alloc",
                ),
                reverifyHint = "R4：往返后 Alloc 应回基线 ±数 MB",
            )
        }

        val skew = tags.getOrDefault("registry_skew", 0) + tags.getOrDefault("keeping_egl_orphan", 0)
        if (skew > 0) {
            val fp = endRegistryAligned == true
            findings += Finding(
                track = Track.REGISTRY,
                severity = if (fp) "info" else "warn",
                tag = "registry_skew",
                detail = if (fp)
                    "registry_skew×$skew 但末态 kotlin≈native 对齐 → 常为瞬态/误报；仍须 dmabuf 无已销毁 id"
                else
                    "registry_skew/keeping_egl×$skew；末态未对齐或未知 → 对拍 Unregister↔destroyRender",
                investigate = listOf(
                    "hilog | grep -E 'Unregister|destroyRender|N renders|alive='",
                    "ohd dmabuf snap  # 已销毁 Compose_* 标签应消失",
                ),
                reverifyHint = "R5：回首页仅 1 活实例且无残留 xcomponent id",
            )
        }

        if (tags.containsKey("abandon_without_purge") || tags.containsKey("partial_teardown_dma_sticky")) {
            findings += Finding(
                track = Track.HIDE_RECLAIM,
                severity = "warn",
                tag = "partial_teardown",
                detail = "GPU 部分 teardown / dmaDrain 失败信号",
                investigate = listOf(
                    "进重页 → hide≥2s → hilog: purgeUnlocked|EglBindForGpuTeardown|dmaDrain",
                    "ohd dmabuf snap / ohd mem 前后对拍",
                ),
                reverifyHint = "R3：hide 期间须见 purge/drain，无 AppFreeze",
            )
        }

        if (findings.isEmpty()) {
            notes += "未命中已知赛道：人工读 anomalies.log；或 ohd mem + ohd dmabuf 做基线"
        }
        notes += "根因由开发/AI 基于证据判断；本计划只派发调查与短跑复验命令"
        notes += "skill 菜谱：cmp-core-ohos-short-run-verify/references/recipes.md"

        val tracks = findings.map { it.track }.distinct()
            .filter { it != Track.SHORT_WINDOW }
            .ifEmpty { findings.map { it.track }.distinct() }

        return Plan(tracks = tracks, findings = findings, notes = notes)
    }

    /** 从 anomalies 行提取 tag 计数；sticky 行解析 GraphΔ。 */
    fun summarizeAnomalies(lines: List<String>): AnomalySummary {
        val counts = mutableMapOf<String, Int>()
        var stickyGraphZero = false
        var stickyTotalGrowth = false
        for (raw in lines) {
            val parts = raw.split('\t')
            if (parts.size < 3) continue
            val sev = parts[1].trim()
            val tag = parts[2].trim()
            if (sev !in setOf("warn", "crit", "info")) continue
            if (tag == "hilog") continue
            counts[tag] = counts.getOrDefault(tag, 0) + 1
            if (tag == "sticky_after_reclaim" || tag == "sticky_after_spike") {
                val msg = parts.getOrNull(3).orEmpty()
                if (Regex("""Graph=[\d.]+ \(base=[\d.]+ Δ=\+?0(?:\.0)?\)""").containsMatchIn(msg) ||
                    msg.contains("Δ=+0.0") && msg.contains("Graph=")
                ) {
                    stickyGraphZero = true
                }
                val m = Regex("""Total=[\d.]+ \(base=[\d.]+ Δ=([+-][\d.]+)\)""").find(msg)
                val d = m?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (d != null && d >= 50.0) stickyTotalGrowth = true
                if (tag == "sticky_after_spike" && msg.contains("not recovered")) stickyTotalGrowth = true
            }
        }
        return AnomalySummary(counts, stickyGraphZero, stickyTotalGrowth)
    }

    data class AnomalySummary(
        val counts: Map<String, Int>,
        val stickyGraphDeltaZero: Boolean,
        val stickyTotalGrowth: Boolean,
    )
}
