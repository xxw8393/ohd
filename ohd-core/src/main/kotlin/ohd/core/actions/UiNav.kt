package ohd.core.actions

import ohd.core.parsers.LayoutNode
import ohd.core.parsers.LayoutQuery
import ohd.core.parsers.Rect

/**
 * 真机 UI「认路 → 取点 → 验标」的纯逻辑。
 * 翻页在 **应用内容安全矩形** 内 drag，避开顶底系统/手势区。
 */
object UiNav {

    data class Pick(
        val node: LayoutNode,
        val x: Int,
        val y: Int,
        val matched: Int,
        val index: Int,
    )

    sealed interface Resolve {
        data class Ok(val pick: Pick) : Resolve
        data class None(val scanned: Int) : Resolve
        data class Ambiguous(val count: Int, val labels: List<String>) : Resolve
        data class BadIndex(val count: Int, val index: Int) : Resolve
    }

    fun resolve(
        nodes: List<LayoutNode>,
        grep: String,
        clickableOnly: Boolean = true,
        index: Int? = null,
        type: String? = null,
        id: String? = null,
    ): Resolve {
        val hits = LayoutQuery.filter(
            nodes,
            text = grep,
            type = type,
            id = id,
            clickableOnly = clickableOnly,
            visibleOnly = true,
        ).filter { it.bounds != null }
        if (hits.isEmpty()) return Resolve.None(nodes.size)
        val chosenIndex = when {
            index != null -> index
            hits.size == 1 -> 0
            else -> return Resolve.Ambiguous(
                hits.size,
                hits.take(5).map { it.label().ifBlank { it.type ?: "?" } },
            )
        }
        if (chosenIndex !in hits.indices) return Resolve.BadIndex(hits.size, chosenIndex)
        val n = hits[chosenIndex]
        val b = n.bounds!!
        return Resolve.Ok(Pick(n, b.centerX, b.centerY, hits.size, chosenIndex))
    }

    fun anyMatch(nodes: List<LayoutNode>, grep: String, clickableOnly: Boolean = false): Boolean =
        LayoutQuery.filter(nodes, text = grep, clickableOnly = clickableOnly, visibleOnly = true).isNotEmpty()

    /**
     * 轻量验标：最多 [maxDumps] 次 dumpLayout（默认 2）。
     * dumpLayout ~1s，长轮询会把每步拖到数秒；轻量模式只确认「第一步地标已出现」
     *（任意命中即可，通常是列表/页首元素），失败仍硬失败。
     */
    fun <T> pollLight(
        timeoutMs: Long,
        maxDumps: Int = 2,
        intervalMs: Long = 250,
        now: () -> Long = { System.currentTimeMillis() },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        sample: () -> T?,
    ): T? {
        val deadline = now() + timeoutMs.coerceAtLeast(0)
        var dumps = 0
        while (dumps < maxDumps.coerceAtLeast(1)) {
            val v = sample()
            dumps++
            if (v != null) return v
            val left = deadline - now()
            if (left <= 0 || dumps >= maxDumps) return null
            sleep(minOf(intervalMs, left))
        }
        return null
    }

    fun <T> pollUntil(
        timeoutMs: Long,
        intervalMs: Long = 200,
        now: () -> Long = { System.currentTimeMillis() },
        sleep: (Long) -> Unit = { Thread.sleep(it) },
        sample: () -> T?,
    ): T? {
        val deadline = now() + timeoutMs.coerceAtLeast(0)
        while (true) {
            val v = sample()
            if (v != null) return v
            val left = deadline - now()
            if (left <= 0) return null
            sleep(minOf(intervalMs, left))
        }
    }

    private val GESTURE_BAR_HINTS = listOf(
        "单指双击", "返回主屏幕", "最近任务", "打开最近任务",
    )

    /**
     * 从当轮 layout 推算 **可安全拖动的应用内容区**（识别 App 可视边缘 + 安全边距）。
     *
     * - 底边：含「单指双击…」的导航条 top，或屏高 88% 以下的矮条（防 Home/手势退后台）
     * - 顶边：贴顶的宽条（状态栏/metaball）bottom
     * - 左右：相对 **屏幕绝对边缘** 再 inset [edgeMarginPx]（防侧滑返回）
     * - 内容 bbox 再 inset [marginPx]，最大 swipe 永不贴系统手势带
     */
    fun contentSafeRect(
        nodes: List<LayoutNode>,
        marginPx: Int = 56,
        edgeMarginPx: Int = 48,
        fallbackW: Int = 1280,
        fallbackH: Int = 2770,
    ): Rect {
        val allBounds = nodes.mapNotNull { it.bounds }
        val screen = allBounds.maxByOrNull { it.width.toLong() * it.height } 
            ?: Rect(0, 0, fallbackW, fallbackH)

        val gestureTops = nodes.mapNotNull { n ->
            val b = n.bounds ?: return@mapNotNull null
            val label = listOfNotNull(n.text, n.description).joinToString(" ")
            when {
                GESTURE_BAR_HINTS.any { label.contains(it) } -> b.top
                b.top >= (screen.height * 0.88).toInt() && b.height <= (screen.height * 0.15).toInt() &&
                    b.width >= (screen.width * 0.4).toInt() -> b.top
                else -> null
            }
        }
        // 无手势节点时也别贴底：屏高约 10% 作保守手势带
        val contentBottomLimit = gestureTops.minOrNull()
            ?: (screen.bottom - (screen.height * 0.10).toInt())

        val statusBottoms = nodes.mapNotNull { n ->
            val b = n.bounds ?: return@mapNotNull null
            if (b.top <= 8 && b.bottom <= (screen.height * 0.14).toInt() &&
                b.width >= (screen.width * 0.45).toInt()
            ) {
                b.bottom
            } else {
                null
            }
        }
        val contentTopLimit = statusBottoms.maxOrNull()
            ?: (screen.top + (screen.height * 0.04).toInt())

        val inner = nodes.mapNotNull { n ->
            val b = n.bounds ?: return@mapNotNull null
            if (!n.visible) return@mapNotNull null
            if (b.height < 24 || b.width < 24) return@mapNotNull null
            if (b.bottom <= contentTopLimit + 2) return@mapNotNull null
            if (b.top >= contentBottomLimit - 2) return@mapNotNull null
            b
        }

        val raw = if (inner.isEmpty()) {
            Rect(screen.left, contentTopLimit, screen.right, contentBottomLimit)
        } else {
            Rect(
                left = inner.minOf { it.left },
                top = inner.minOf { it.top },
                right = inner.maxOf { it.right },
                bottom = inner.maxOf { it.bottom },
            )
        }

        // App 内容边 + 内容 inset，再与屏幕绝对边缘 edgeMargin 取更严者
        val top = maxOf(raw.top, contentTopLimit, screen.top + edgeMarginPx) + marginPx
        val bottom = minOf(raw.bottom, contentBottomLimit, screen.bottom - edgeMarginPx) - marginPx
        val left = maxOf(raw.left, screen.left + edgeMarginPx) + marginPx
        val right = minOf(raw.right, screen.right - edgeMarginPx) - marginPx

        return if (bottom - top >= 240 && right - left >= 200) {
            Rect(left, top, right, bottom)
        } else {
            // 退化：屏内扣手势/状态 + 边距（仍相对屏幕绝对边缘）
            Rect(
                screen.left + edgeMarginPx + marginPx,
                contentTopLimit + marginPx,
                screen.right - edgeMarginPx - marginPx,
                contentBottomLimit - marginPx,
            )
        }
    }

    /**
     * 在 [safe] 内翻约一屏：**drag**，从安全区下部 → 上部（上滑看下面内容）。
     */
    fun scrollPageAction(
        dir: String,
        safe: Rect,
        velocity: Int = 900,
    ): UiActions.Action {
        val cx = safe.centerX
        val cy = safe.centerY
        // 安全区内 85% → 15%，再各留 1px 防贴边
        val yLo = (safe.top + safe.height * 0.85).toInt().coerceIn(safe.top + 1, safe.bottom - 1)
        val yHi = (safe.top + safe.height * 0.15).toInt().coerceIn(safe.top + 1, safe.bottom - 1)
        val xLo = (safe.left + safe.width * 0.15).toInt().coerceIn(safe.left + 1, safe.right - 1)
        val xHi = (safe.left + safe.width * 0.85).toInt().coerceIn(safe.left + 1, safe.right - 1)
        val pts = when (dir.lowercase()) {
            "up" -> listOf(cx, yLo, cx, yHi, velocity)
            "down" -> listOf(cx, yHi, cx, yLo, velocity)
            "left" -> listOf(xHi, cy, xLo, cy, velocity)
            "right" -> listOf(xLo, cy, xHi, cy, velocity)
            else -> listOf(cx, yLo, cx, yHi, velocity)
        }
        return UiActions.Action(UiActions.Kind.DRAG, pts.map { it.toString() }, "", 0)
    }

    /** 无 layout 时的退化几何（仍避开估计底栏）。 */
    fun scrollPageActionFallback(
        dir: String,
        width: Int = 1280,
        height: Int = 2770,
        velocity: Int = 900,
    ): UiActions.Action {
        val safe = Rect(
            (width * 0.08).toInt(),
            (height * 0.12).toInt(),
            (width * 0.92).toInt(),
            (height * 0.82).toInt(),
        )
        return scrollPageAction(dir, safe, velocity)
    }

    /** @deprecated 兼容旧测试；优先 [contentSafeRect] + [scrollPageAction]。 */
    fun scrollSwipe(dir: String, width: Int = 1280, height: Int = 2770, velocity: Int = 900): List<Int> {
        val a = scrollPageActionFallback(dir, width, height, velocity)
        return a.args.map { it.toInt() }
    }

    fun scrollSwipePixels(height: Int = 2770): Int {
        val safeH = (height * 0.82).toInt() - (height * 0.12).toInt()
        return (safeH * 0.70).toInt()
    }
}
