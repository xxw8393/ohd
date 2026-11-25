package ohd.core.actions

import ohd.core.Hdc
import ohd.core.parsers.LayoutNode
import java.util.concurrent.ConcurrentHashMap

/**
 * 列表滑找地图：记录「在某 scene 顶向下，某文案大约第几次 swipe 后进屏」。
 *
 * 学习：scroll-find 每次 dump 把可见标签记到当前 swipe 序号（取历史最小）。
 * 使用：下次先 `estimate` → **连续批量 swipe** → 再 dump 一次点中；找不到再边滑边学。
 *
 * sceneId 用滚动开始前的页面指纹（或 `--scene`），避免滑一下指纹就变导致索引碎掉。
 */
object ScrollAtlas {

    data class Hint(
        val label: String,
        val swipesFromTop: Int,
        val centerY: Int,
        val samples: Int,
    )

    private data class SceneBucket(
        val byLabel: ConcurrentHashMap<String, Hint> = ConcurrentHashMap(),
    )

    /** deviceKey → sceneId → bucket */
    private val scenes = ConcurrentHashMap<String, ConcurrentHashMap<String, SceneBucket>>()

    fun clear() {
        scenes.clear()
    }

    fun clearDevice(hdc: Hdc) {
        scenes.remove(LayoutCache.deviceKey(hdc))
    }

    private fun bucket(hdc: Hdc, sceneId: String): SceneBucket {
        val dev = scenes.getOrPut(LayoutCache.deviceKey(hdc)) { ConcurrentHashMap() }
        return dev.getOrPut(sceneId) { SceneBucket() }
    }

    fun learn(hdc: Hdc, sceneId: String, swipesFromTop: Int, flat: List<LayoutNode>) {
        if (sceneId.isBlank()) return
        val b = bucket(hdc, sceneId)
        for (n in flat) {
            val raw = n.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            if (raw.length < 2) continue
            val bounds = n.bounds ?: continue
            // 贴边几乎不可点的半截 item 也记，方便估到「再滑一点」
            val key = raw.lowercase()
            val y = bounds.centerY
            b.byLabel.compute(key) { _, old ->
                when {
                    old == null -> Hint(raw, swipesFromTop, y, 1)
                    swipesFromTop < old.swipesFromTop ->
                        Hint(raw, swipesFromTop, y, old.samples + 1)
                    else -> old.copy(centerY = y, samples = old.samples + 1)
                }
            }
        }
    }

    /**
     * @return 建议从列表顶连续滑几次；null=未知（需探索）
     */
    fun estimate(hdc: Hdc, sceneId: String, grep: String): Int? {
        if (sceneId.isBlank() || grep.isBlank()) return null
        val b = scenes[LayoutCache.deviceKey(hdc)]?.get(sceneId) ?: return null
        val needle = grep.lowercase()
        var best: Int? = null
        for ((label, hint) in b.byLabel) {
            if (label.contains(needle) || needle.contains(label.take(12))) {
                best = minOf(best ?: hint.swipesFromTop, hint.swipesFromTop)
            }
        }
        return best
    }

    fun hintCount(hdc: Hdc, sceneId: String): Int =
        scenes[LayoutCache.deviceKey(hdc)]?.get(sceneId)?.byLabel?.size ?: 0

    /** 调试/JSON：前几个高 swipe 的条目 */
    fun snapshot(hdc: Hdc, sceneId: String, limit: Int = 8): List<Hint> {
        val b = scenes[LayoutCache.deviceKey(hdc)]?.get(sceneId) ?: return emptyList()
        return b.byLabel.values.sortedByDescending { it.swipesFromTop }.take(limit)
    }
}
