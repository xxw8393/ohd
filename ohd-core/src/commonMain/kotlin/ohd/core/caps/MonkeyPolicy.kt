package ohd.core.caps

/**
 * monkey 的步数上限与采样可信度。
 *
 * 这两件事以前都是静默的：默认 10 万步会在 `--duration 10h` 的第 17 分钟把长跑截断，
 * 采样失败率 98% 也只在汇总里给一个数字，PSS 起止对比看起来像结论。
 */
object MonkeyPolicy {
    /** 缺省与显式 0 都表示不限。固定上限在高步频下会先于时长被打满。 */
    const val DEFAULT_MAX_STEPS = 0

    /**
     * Home / 最近任务把应用打进后台是要覆盖的用例。
     * 离开之后先停这段时间，再拉回，避免接着去点桌面图标。
     */
    const val BACKGROUND_DWELL_MS = 2_000L

    /** 没写包名的节点保留（有的控件不带 bundleName）。写了别的包名则不是点击目标。 */
    fun belongsToTarget(nodeBundle: String?, target: String?): Boolean {
        if (target.isNullOrBlank()) return true
        if (nodeBundle.isNullOrBlank()) return true
        return nodeBundle == target
    }

    /**
     * `--avoid-text a,b`：text / description 含任一子串则不当作可点目标。
     * 大小写不敏感；空列表恒 false。
     */
    fun matchesAvoidText(text: String?, description: String?, avoid: List<String>): Boolean {
        if (avoid.isEmpty()) return false
        val hay = buildString {
            if (!text.isNullOrBlank()) append(text).append('\n')
            if (!description.isNullOrBlank()) append(description)
        }
        if (hay.isEmpty()) return false
        return avoid.any { a -> hay.contains(a, ignoreCase = true) }
    }

    fun matchesAvoidText(node: ohd.core.parsers.LayoutNode, avoid: List<String>): Boolean =
        matchesAvoidText(node.text, node.description, avoid)

    /**
     * 这条动作可能把前台切走，它应该是本批的最后一条。
     * 动作本身仍会发出：Home、Back，以及从底部向上滑（Home/最近任务手势）。
     */
    fun endsBatch(line: String, screenH: Int): Boolean {
        if (line.startsWith("key ")) return true
        if (!line.startsWith("swipe ")) return false
        val parts = line.split(" ")
        val y1 = parts.getOrNull(2)?.toIntOrNull() ?: return false
        val y2 = parts.getOrNull(4)?.toIntOrNull() ?: return false
        return y1 >= screenH - 200 && y2 < y1
    }

    fun resolveMaxSteps(opt: Int): Int = if (opt <= 0) Int.MAX_VALUE else opt

    /**
     * 步数上限的最短耗时（只算间隔睡眠，不计 layout/采样）仍短于时长，
     * 则时长还没到就可能被步数截断。不限步数、或下限已经长过时长时不算冲突。
     */
    fun conflicts(maxSteps: Int, intervalMs: Long, durationMs: Long, batch: Int): Boolean {
        if (maxSteps <= 0 || durationMs <= 0) return false
        return minDurationMs(maxSteps, intervalMs, batch) < durationMs
    }

    fun startWarning(maxSteps: Int, intervalMs: Long, durationMs: Long, batch: Int): String? {
        if (!conflicts(maxSteps, intervalMs, durationMs, batch)) return null
        return "已设 --max-steps $maxSteps：按当前间隔，步数会在 --duration 之前用尽并提前结束（--max-steps 0 = 不限）"
    }

    /** 循环因步数打满而停，且截止时间还没到。 */
    fun durationCutShort(maxStepsOpt: Int, steps: Int, nowMs: Long, deadlineMs: Long): Boolean =
        maxStepsOpt > 0 && steps >= maxStepsOpt && nowMs < deadlineMs

    fun hitMessage(maxSteps: Int): String =
        "步数到达 --max-steps $maxSteps，时长尚未结束，本轮被步数上限截断（跑满时长请用 --max-steps 0）"

    private fun minDurationMs(maxSteps: Int, intervalMs: Long, batch: Int): Long {
        val b = batch.coerceAtLeast(1).toLong()
        val batches = (maxSteps.toLong() + b - 1) / b
        val interval = intervalMs.coerceAtLeast(0)
        if (interval == 0L) return 0L
        if (batches > Long.MAX_VALUE / interval) return Long.MAX_VALUE
        return batches * interval
    }
}

/**
 * 采样失败占尝试次数的一半及以上时，内存曲线不足以支撑 PSS 起止对比。
 * 次数太少（不足 [MIN_ATTEMPTS]）不升格，避免两三步的短跑误报。
 */
object SampleHealth {
    const val FAIL_RATIO_WARN = 0.5
    const val MIN_ATTEMPTS = 5

    data class Report(val ratio: Double, val message: String)

    fun assess(success: Int, reasons: Map<String, Int>): Report? {
        val fails = reasons.values.sum()
        val attempts = success + fails
        if (attempts < MIN_ATTEMPTS || fails <= 0) return null
        val ratio = fails.toDouble() / attempts
        if (ratio < FAIL_RATIO_WARN) return null
        val pct = kotlin.math.round(ratio * 100).toInt()
        val breakdown = reasons.entries.filter { it.value > 0 }
            .joinToString("，") { "${it.key} ${it.value}" }
        return Report(
            ratio,
            "采样失败率 $pct%（成功 $success / 失败 $fails：$breakdown）。失败过半，内存曲线过稀，PSS 起止对比不可信",
        )
    }

    fun kindOf(message: String?): String {
        val m = message ?: return "其他"
        return when {
            m.contains("超时") -> "超时"
            m.contains("pss", ignoreCase = true) -> "pss不可用"
            m.contains("hidumper") -> "hidumper失败"
            else -> "其他"
        }
    }
}
