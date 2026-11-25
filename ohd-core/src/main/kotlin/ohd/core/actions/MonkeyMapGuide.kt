package ohd.core.actions

import ohd.core.parsers.LayoutNode
import kotlin.random.Random

/**
 * Monkey 读世界地图：把已学页边 / item 滑次变成可执行脚本行，减少盲目 dump+乱点。
 *
 * 原则：坐标必须来自**当轮** [clickables]；地图只提供「点哪个 label / 滑几屏」。
 */
object MonkeyMapGuide {

    data class PendingEdge(
        val fromPageId: String,
        val label: String,
    )

    /**
     * 停滞脱困：优先点当前页已知出边；否则按 item 滑次上滑一屏；再否则 Back×1（由调用方兜底）。
     * @return 非空脚本行列表（通常 1 条）；无法规划时 null
     */
    fun stagnateEscape(
        bundle: String,
        clickables: List<LayoutNode>,
        w: Int,
        h: Int,
        safeTop: Int,
        safeBottom: Int,
        rnd: Random,
        recentLabels: Set<String> = emptySet(),
    ): List<String>? {
        val doc = WorldMap.load(bundle)
        val pageId = doc.currentPageId ?: return null
        val page = doc.pages[pageId] ?: return null

        // 1) 可见的出边 label
        val exitLabels =
            doc.edges
                .asSequence()
                .filter { it.from == pageId && it.action == "tap" && !it.label.isNullOrBlank() }
                .map { it.label!! }
                .filter { it !in recentLabels }
                .distinct()
                .toList()
        pickVisibleTap(clickables, exitLabels, w, safeTop, safeBottom, rnd)?.let {
            return listOf(it)
        }

        // 2) 当前页已学 item：可见则点；不可见且有滑次 → 上滑一屏
        val items = page.items.filter { it.label.isNotBlank() && it.label !in recentLabels }
        val visibleItemLabels =
            items.map { it.label }.filter { lab ->
                clickables.any { matchLabel(it.label(), lab) }
            }
        pickVisibleTap(clickables, visibleItemLabels, w, safeTop, safeBottom, rnd)?.let {
            return listOf(it)
        }
        val offscreen =
            items.filter { it.swipesFromTop != null && (it.swipesFromTop ?: 0) > 0 }
                .filter { lab -> clickables.none { matchLabel(it.label(), lab.label) } }
        if (offscreen.isNotEmpty()) {
            return listOf(swipeUp(w, h, safeTop, safeBottom, rnd))
        }

        // 3) 跨页：随便挑一个已知地标，先执行计划里第一步（Back 或点）
        val landmarks =
            doc.pages.values
                .flatMap { p -> p.items.map { it.label } }
                .filter { it.isNotBlank() && it !in recentLabels }
                .distinct()
        if (landmarks.isNotEmpty()) {
            val target = landmarks[rnd.nextInt(landmarks.size)]
            val plan = WorldMap.planTo(bundle, target, pageId) ?: return null
            val step = plan.steps.firstOrNull() ?: return null
            return when (step.kind) {
                "back" -> listOf("key Back")
                "tap" -> {
                    val lab = step.label ?: return null
                    pickVisibleTap(clickables, listOf(lab), w, safeTop, safeBottom, rnd)
                        ?.let { listOf(it) }
                        ?: listOf(swipeUp(w, h, safeTop, safeBottom, rnd))
                }
                "swipe" -> listOf(swipeUp(w, h, safeTop, safeBottom, rnd))
                else -> null
            }
        }
        return null
    }

    /**
     * 常规 pick 偏置：有地图时优先点「出边 / 本页 item」对应的可见节点；
     * 约 25% 对不可见 item 先上滑一屏。返回 null 表示交给原随机 pick。
     */
    fun biasedAction(
        bundle: String,
        clickables: List<LayoutNode>,
        w: Int,
        h: Int,
        safeTop: Int,
        safeBottom: Int,
        rnd: Random,
    ): String? {
        val doc = WorldMap.load(bundle)
        val pageId = doc.currentPageId ?: return null
        val page = doc.pages[pageId] ?: return null
        val preferred =
            (
                doc.edges
                    .asSequence()
                    .filter { it.from == pageId && it.action == "tap" && !it.label.isNullOrBlank() }
                    .map { it.label!! } +
                    page.items.asSequence().map { it.label }
                )
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
        if (preferred.isEmpty()) return null

        // 70%：点可见偏好节点
        if (rnd.nextInt(100) < 70) {
            pickVisibleTap(clickables, preferred, w, safeTop, safeBottom, rnd)?.let { return it }
        }
        // 25%：不可见则上滑（剩 5% 交回随机）
        if (rnd.nextInt(100) < 25) {
            val needSwipe =
                page.items.any { item ->
                    (item.swipesFromTop ?: 0) > 0 &&
                        clickables.none { matchLabel(it.label(), item.label) }
                }
            if (needSwipe) return swipeUp(w, h, safeTop, safeBottom, rnd)
        }
        return null
    }

    fun matchLabel(nodeLabel: String, mapLabel: String): Boolean {
        if (nodeLabel.isBlank() || mapLabel.isBlank()) return false
        if (nodeLabel == mapLabel) return true
        return nodeLabel.contains(mapLabel) || mapLabel.contains(nodeLabel)
    }

    private fun pickVisibleTap(
        clickables: List<LayoutNode>,
        labels: List<String>,
        w: Int,
        safeTop: Int,
        safeBottom: Int,
        rnd: Random,
    ): String? {
        if (labels.isEmpty() || clickables.isEmpty()) return null
        val hits =
            clickables.filter { n ->
                val lab = n.label()
                lab.isNotBlank() && labels.any { matchLabel(lab, it) } && n.bounds != null
            }
        if (hits.isEmpty()) return null
        val b = hits[rnd.nextInt(hits.size)].bounds ?: return null
        val x = (b.centerX + rnd.nextInt(-12, 13)).coerceIn(1, w - 1)
        val y = (b.centerY + rnd.nextInt(-12, 13)).coerceIn(safeTop + 1, safeBottom - 1)
        return "tap $x $y"
    }

    fun swipeUp(w: Int, h: Int, safeTop: Int, safeBottom: Int, rnd: Random): String {
        val x = rnd.nextInt((w / 12).coerceAtLeast(10), (w * 11 / 12).coerceAtLeast(w / 12 + 10))
        val y1 = ((safeTop + safeBottom) / 2 + (safeBottom - safeTop) / 4).coerceIn(safeTop + 1, safeBottom - 1)
        val dist = UiNav.scrollSwipePixels(h).coerceIn(400, (safeBottom - safeTop) * 2 / 3)
        val y0 = (y1 + dist / 2).coerceIn(safeTop + 1, safeBottom - 1)
        val y2 = (y0 - dist).coerceIn(safeTop + 1, safeBottom - 1)
        return "swipe $x $y0 $x $y2 800"
    }

    /** 从脚本行反查可能点到的 label（仅 tap 且落在某 clickable 框内） */
    fun labelNearTap(line: String, clickables: List<LayoutNode>): String? {
        if (!line.startsWith("tap ")) return null
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val x = parts[1].toIntOrNull() ?: return null
        val y = parts[2].toIntOrNull() ?: return null
        return clickables
            .firstOrNull { n ->
                val b = n.bounds ?: return@firstOrNull false
                n.label().isNotBlank() && x in b.left..b.right && y in b.top..b.bottom
            }
            ?.label()
            ?.takeIf { it.isNotBlank() }
    }
}
