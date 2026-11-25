package ohd.core.analysis

/**
 * PSS 走势判定。
 *
 * 只看整体斜率会把「锯齿」误判成「持续上升」：40 分钟实测里 91% 的采样在上升、
 * 斜率为正，但分段看每段内部涨、段与段之间被回收——那是 GC/缓存行为，不是泄漏。
 *
 * 所以判定的核心是**分段谷值**，而不是首末差：
 * - 谷值逐段抬升 → 基线抬升（才值得怀疑）
 * - 谷值稳定、段内涨段间落 → 锯齿（已回收）
 *
 * 本模块只描述形状，绝不下「泄漏/无泄漏」结论。
 */
object Trend {
    enum class Shape { INSUFFICIENT, BASELINE_RISE, SAWTOOTH, RISING, DECLINING, FLAT }

    data class Result(
        val shape: Shape,
        val label: String,
        val slopePerSec: Double,
        val riseRatio: Double,
        val valleysKb: List<Long>,
        val baselineDriftKb: Long,
        val maxDrawdownKb: Long,
        val peakKb: Long,
        val troughKb: Long,
        val samples: Int,
    ) {
        val isInsufficient: Boolean get() = shape == Shape.INSUFFICIENT
    }

    fun analyze(pssKb: List<Long>, spanSec: Double, segments: Int = 5): Result {
        val n = pssKb.size
        if (n < 5) {
            return Result(
                Shape.INSUFFICIENT, "样本不足", 0.0, 0.0, emptyList(), 0, 0,
                pssKb.maxOrNull() ?: 0, pssKb.minOrNull() ?: 0, n,
            )
        }
        val peak = pssKb.max()
        val trough = pssKb.min()
        val riseRatio = pssKb.zipWithNext().count { (a, b) -> b > a }.toDouble() / (n - 1)
        val slope = if (spanSec > 0) (pssKb.last() - pssKb.first()) / spanSec else 0.0

        val segSize = (n + segments - 1) / segments
        val valleys = (0 until segments).mapNotNull { i ->
            val from = i * segSize
            val to = minOf(n, from + segSize)
            if (from < to) pssKb.subList(from, to).min() else null
        }
        val baselineDrift = if (valleys.size >= 2) valleys.last() - valleys.first() else 0L
        val risingValleys = valleys.zipWithNext().count { (a, b) -> b >= a }
        val valleyTrendUp = valleys.size >= 3 &&
            risingValleys.toDouble() / (valleys.size - 1) >= 0.6

        // 最大回撤：从运行高点的最大回落，锯齿的判据
        var maxDrawdown = 0L
        var runMax = pssKb.first()
        for (v in pssKb) {
            if (v > runMax) runMax = v
            val dd = runMax - v
            if (dd > maxDrawdown) maxDrawdown = dd
        }

        // 基线抬升的判定门槛：至少 2MB 且超过谷值的 3%，否则只是噪声
        val driftThreshold = maxOf(2L * 1024, trough * 3 / 100)

        // 回撤大小优先于基线漂移：有大幅回撤就说明回收机制在工作，
        // 此时把整体上涨判成「泄漏」会误导（实测里回撤 68MB、基线只涨 15MB）。
        val bigDrawdown = maxDrawdown > peak * 5 / 100
        val shape = when {
            riseRatio >= 0.6 && bigDrawdown -> Shape.SAWTOOTH
            slope > 0 && riseRatio >= 0.8 -> Shape.RISING
            valleyTrendUp && baselineDrift > driftThreshold -> Shape.BASELINE_RISE
            slope < 0 && riseRatio <= 0.2 -> Shape.DECLINING
            else -> Shape.FLAT
        }

        val label = when (shape) {
            Shape.BASELINE_RISE -> "基线抬升"
            Shape.SAWTOOTH -> "锯齿（涨后回落，已回收）"
            Shape.RISING -> "持续上升"
            Shape.DECLINING -> "持续下降"
            Shape.FLAT -> "震荡"
            Shape.INSUFFICIENT -> "样本不足"
        }

        return Result(
            shape, label, slope, riseRatio, valleys, baselineDrift, maxDrawdown,
            peak, trough, n,
        )
    }
}
