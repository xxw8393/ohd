package ohd.core.actions

import ohd.core.parsers.LayoutNode
import java.security.MessageDigest

/**
 * 场景（scene）身份：把「同一页面的不同滚动位置」归并成一个节点。
 *
 * 为什么不能用 [LayoutCache.fingerprint]：那是**整页文本集合**哈希，列表每滑一屏文本全变，
 * 于是同一场景被拆成几百个 page（实测 6548 page 只对应约 20~150 个真实场景），
 * BFS 导航、滑次学习全部挂在「滚动位置」上，图上没有任何可用路径。
 *
 * 做法：只取**首屏顶部滚动带内、可点击**的 label，做数字归一与去标点后集合哈希。
 * 顶部带内的元素在「列表还没滚动」时是稳定的；滚动后这些元素离开带内，签名随之变化 ——
 * 这正是我们要的：被折叠的滚动变体因此共享同一个 sceneId。
 *
 * 纯函数，无 IO 无状态，便于穷尽单测。
 */
object SceneSignature {

    /**
     * 取样高度上限（px）：先凑够 [MIN_LABELS] 个，但绝不越过这个距离。
     *
     * 与固定带的区别：固定带是「到点就停」，这里是「凑够内容才停」，
     * 因此顶部只有返回按钮的稀疏页也能取到有区分度的元素。
     */
    const val DEFAULT_BAND_PX = 1400

    /**
     * 分区取样的纵向锚点（占屏高比例）。**屏高是不变的，开局算一次即可**。
     *
     * 为什么不用屏幕中心：实测大量页面中部是滚动容器的空白区
     * （典型 y=[80,248,479,577,1180,2779]），中心带取不到任何元素，
     * 6581 页里有 1162 页退化成空签名。
     *
     * 为什么是这三点：在 6565 页真实数据上对比，
     * 20%/50%/80% 三处比 25%/75% 两处更能分开页面
     * （场景 525 vs 339，最大场景 495 vs 668 页，空签名 81 vs 450）。
     */
    val BAND_RATIOS = doubleArrayOf(0.20, 0.50, 0.80)

    /** 每个锚点上下取样的半径（占屏高比例）。 */
    const val BAND_HALF_RATIO = 0.055

    /** 每个锚点最多取几个 label。 */
    private const val PER_BAND_LABELS = 3

    /** 视口签名里纵坐标的量化粒度（px）：滤掉渲染抖动，保留真实滚动位移。 */
    private const val VIEWPORT_Y_QUANTUM = 24

    /** 至少凑够这么多个 label 才算取到有区分度的签名。 */
    private const val MIN_LABELS = 4

    /** 取样绝对上限，防止超长页面把签名拖成整屏内容（那样又会随滚动漂移）。 */
    private const val MAX_SCAN_PX = 1400

    /** 参与签名的 label 上限，防止超长页面把哈希拖慢。 */
    private const val MAX_LABELS = 8

    /** 地标归一后至少这么长才算有区分度（挡住 "back" 之外的极短噪声）。 */
    private const val MIN_LANDMARK_CHARS = 3

    /** 系统手势无障碍提示：每页都有，完全不具区分度，必须剔除。 */
    private val NOISE = setOf(
        "单指双击即可打开最近任务。",
        "单指双击即可返回主屏幕。",
        "单指双击即可返回。",
    )

    private val DIGITS = Regex("\\d+")

    /** 只去「分隔类」标点；斜杠等有语义的符号保留（"A/B" 与 "AB" 是不同入口）。 */
    private val PUNCT = Regex("[\\s,，:：。｡、·\\-—|]+")

    /**
     * 是否是系统手势提示噪声（每页都有，完全不具区分度）。
     *
     * 清洗世界地图时也要用同一份定义：这些 item 占实测数据量的 12%，纯属冗余。
     */
    fun isNoiseLabel(raw: String): Boolean {
        val t = raw.trim()
        if (t in NOISE) return true
        // 纯数字/时钟类（"13:25"、"57"、"13, :, 25"）：状态栏时间每轮都变，
        // 若留下就会把「每个时间点」都存成一个 item（实测单页累积到 593 项，
        // 全库 1037 种、13216 次，占 item 总量 33%），是纯粹的垃圾数据。
        val n = normalizeLabel(t)
        return n.isEmpty() || n.all { it == '#' }
    }

    /** label 归一：小写、数字折叠成 `#`、去分隔标点与空白。用于比较而非展示。 */
    fun normalizeLabel(raw: String): String {
        val lower = raw.trim().lowercase()
        if (lower.isEmpty()) return ""
        return PUNCT.replace(DIGITS.replace(lower, "#"), "")
    }

    /**
     * 算 sceneId 与人类可读摘要。
     *
     * **优先级：验标地标 > dump 取样**。
     * `expect` 是「确认操作生效」时必然要读到的那个元素（ohd 每次 go/back 都要验它），
     * 它既是我们自己业务上认定的页面特征，又是必做的操作，不额外增加任何设备开销。
     * 相比之下，从 dump 里猜「哪些元素能代表页面」只是尽力而为的经验启发式
     * （实测 6565 页仍收敛到 100 个场景，说明顶部可点击入口刻画不足）。
     *
     * @param landmark 验标通过的地标文本；非空时以它为主键
     * @param bandPx 取样高度上限；负值表示不限
     */
    fun of(
        flat: List<LayoutNode>,
        bandPx: Int = DEFAULT_BAND_PX,
        landmark: String? = null,
        screenHeight: Int = 0,
    ): Pair<String, String> {
        // 优先级：验标地标（业务语义最权威）> 静态骨架（跨滚动稳定）> 分区/顶部取样（兜底）
        val labels = landmarkSceneLabels(landmark)
            ?: chromeLabels(flat).takeIf { it.isNotEmpty() }
            ?: if (screenHeight > 0) bandedLabels(flat, screenHeight) else topBandLabels(flat, bandPx)
        val material = labels.joinToString("|")
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(material.toByteArray(Charsets.UTF_8))
        val id = digest.joinToString("") { b -> "%02x".format(b) }.take(16)
        return id to labels.take(4).joinToString(",")
    }

    /**
     * 验标地标构造的场景身份：`landmark:` 前缀 + 归一后的地标。
     *
     * 加前缀是为了与 dump 取样得到的身份**永不碰撞** —— 两者可信度不同，
     * 混在一起会让「按地标确认过的页」和「仅靠取样猜的页」被当成同一场景。
     *
     * **不给用的地标**：过泛的词。实测 `back` 出现在 67% 的页面、`返回` 6%，
     * 拿它们当身份会把大半个 App 并成一个场景，比纯取样还差。
     * 判据用归一化长度兜底（见 [MIN_LANDMARK_CHARS]），真正的泛词筛除走
     * [WorldMap] 侧基于全局出现率（DF）的判定。
     */
    fun landmarkSceneLabels(landmark: String?): List<String>? {
        val raw = landmark?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val n = normalizeLabel(raw)
        // 归一后过短的信息量不足（如 "back"、纯数字），不足以标识一个页面
        if (n.length < MIN_LANDMARK_CHARS) return null
        if (n.all { it == '#' || !it.isLetterOrDigit() }) return null
        return listOf("landmark:$n")
    }

    /**
     * 视口签名：**当前这一屏可点击元素的「文本 + 量化纵坐标」**，用于坐标安全复用。
     *
     * 与页面身份分离的原因：导航要「滚动不变」，坐标复用要「视口敏感」，两者互相冲突。
     * 只有视口签名一致（同一屏、同样布局）时，`--map-first` 才能直接复用旧坐标；
     * 一旦列表滚动导致它变化，就必须回当轮 dump 重新定位。
     *
     * 纵坐标按 [VIEWPORT_Y_QUANTUM] 量化：同一屏的细微渲染差异（±数 px）不该让签名失效，
     * 而真正的滚动位移（数百 px）必然跨越量化格。
     */
    fun viewportSignature(flat: List<LayoutNode>): String {
        val items = flat.asSequence()
            .filter { it.clickable }
            .mapNotNull { n ->
                val raw = n.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                if (raw in NOISE) return@mapNotNull null
                val norm = normalizeLabel(raw)
                if (norm.isEmpty()) return@mapNotNull null
                val y = n.bounds?.centerY ?: return@mapNotNull null
                "$norm@${y / VIEWPORT_Y_QUANTUM}"
            }
            .distinct()
            .sorted()
            .joinToString("|")
        if (items.isEmpty()) return "∅"
        return MessageDigest.getInstance("SHA-1")
            .digest(items.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }.take(16)
    }

    /**
     * 静态骨架身份：**先剥离 scrollable 子树，再对剩余节点算哈希**。
     *
     * 这是导航合并的正确解。之前的做法按固定几何位置取样（20%/50%/80%），
     * 而这三个位置几乎都落在滚动区内，列表一滑必然全换 —— 身份随滚动抖动，
     * 与「同一页的不同滚动位置要并在一起」直接冲突。
     *
     * 剥离后剩下的是顶部导航/标题、固定 Tab、底部操作栏、弹窗等静态骨架：
     * 只要它们不变，无论列表滑多少屏，身份都稳定。
     *
     * 每个节点贡献 `(相对层级, 归一化文本, 可点击性)`；层级用相对深度而非绝对坐标，
     * 避免整体布局位移影响身份。
     *
     * @return 身份与参与计算的节点数；剥离后无文本时退回 [bandedLabels]
     */
    fun chromeLabels(flat: List<LayoutNode>): List<String> {
        val rows = ArrayList<String>()
        fun walk(n: LayoutNode, depth: Int) {
            if (n.scrollable) return // 整棵滚动子树剪掉
            val raw = n.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
            if (raw != null && raw !in NOISE) {
                val norm = normalizeLabel(raw)
                if (norm.isNotEmpty() && !norm.all { it == '#' || !it.isLetterOrDigit() }) {
                    rows += "d$depth|$norm|${if (n.clickable) 1 else 0}"
                }
            }
            n.children.forEach { walk(it, depth + 1) }
        }
        flat.forEach { walk(it, 0) }
        if (rows.isEmpty()) return emptyList()
        return rows.distinct().sorted()
    }

    /**
     * 分区取样：在屏高 20%/50%/80% 三个锚点各取 [BAND_HALF_RATIO] 半径内的元素，拼成身份。
     *
     * 依据（6565 页真实地图实测）：
     * - 只取屏幕中心 → 1162 页中部空白、退化成空签名；
     * - 25%/75% 两处 → 场景 339、最大场景 668 页、空签名 450；
     * - 20%/50%/80% 三处 → 场景 525、最大场景 495 页、空签名 81。
     *
     * 屏高不变，调用方算一次即可（见 [WorldMap.screenHeight]），不额外 dump。
     */
    fun bandedLabels(flat: List<LayoutNode>, screenHeight: Int): List<String> {
        if (flat.isEmpty() || screenHeight <= 0) return listOf("∅")
        val rows = flat.mapNotNull { n ->
            val raw = n.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (raw in NOISE) return@mapNotNull null
            val norm = normalizeLabel(raw)
            if (norm.isEmpty() || norm.all { it == '#' || !it.isLetterOrDigit() }) return@mapNotNull null
            val y = n.bounds?.centerY ?: return@mapNotNull null
            Pair(norm, y)
        }
        if (rows.isEmpty()) return listOf("∅")
        val half = (screenHeight * BAND_HALF_RATIO).toInt().coerceAtLeast(40)
        val out = ArrayList<String>(BAND_RATIOS.size * PER_BAND_LABELS)
        for (r in BAND_RATIOS) {
            val anchor = (screenHeight * r).toInt()
            val band = rows.asSequence()
                .filter { kotlin.math.abs(it.second - anchor) <= half }
                .sortedBy { it.second }
                .map { it.first }
                .distinct()
                .take(PER_BAND_LABELS)
                .toList()
            out += band
        }
        if (out.isEmpty()) return listOf("∅")
        return out.distinct().sorted()
    }

    /**
     * 身份 label：从最顶部可点击元素往下取，直到凑够 [MIN_LABELS] 个（最多 [MAX_LABELS]）。
     *
     * **为什么不是固定高度带**：固定 320px 带在「顶部只有返回按钮、下面一片空白」的页上
     * 退化成一个只剩 `back` 的签名，于是把内容完全不同的几百个页面并成一个场景
     * （实测 3933 页并成 1 个 scene，其中 398 种互不相同的 label 集合）。
     * 改成「按内容而非像素」取样后，同场景收敛到 1 种集合，场景数 24 → 100。
     *
     * 上界用 [MAX_SCAN_PX] 兜底，避免为极稀疏的长页面扫到屏幕底部
     * （那会让签名重新随滚动位置漂移）。
     *
     * 无任何可取 label 时返回 `["∅"]` 而非空列表：空列表会让所有「抓不到内容」的页
     * 共享同一个 sceneId，把真正的空页混成一个大桶。
     */
    fun topBandLabels(flat: List<LayoutNode>, bandPx: Int = DEFAULT_BAND_PX): List<String> {
        data class Row(val norm: String, val y: Int)

        val rows = flat.mapNotNull { n ->
            val raw = n.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            if (raw in NOISE) return@mapNotNull null
            if (!n.clickable) return@mapNotNull null
            val norm = normalizeLabel(raw)
            // 只滤掉纯数字/纯符号（归一后无信息量）。不能按长度砍：
            // "返回"（实测 411 次）这类 2 字标签是真实入口，误滤会失去关键区分度。
            if (norm.isEmpty() || norm.all { it == '#' || !it.isLetterOrDigit() }) return@mapNotNull null
            val y = n.bounds?.centerY ?: return@mapNotNull null
            Row(norm, y)
        }
        if (rows.isEmpty()) return listOf("∅")

        val sorted = rows.sortedBy { it.y }
        val top = sorted.first().y
        val picked = LinkedHashSet<String>()
        for (r in sorted) {
            val dy = r.y - top
            // 先凑够 MIN_LABELS（稀疏页必须够到下方真实内容），
            // 凑够之后才受 bandPx / MAX_SCAN_PX 约束 ——
            // 否则「顶部只有返回按钮」的页会退化成只含 back 的签名，
            // 把内容完全不同的页面并成同一个场景。
            if (picked.size >= MIN_LABELS && bandPx >= 0) {
                if (dy > bandPx || dy > MAX_SCAN_PX) break
            }
            picked.add(r.norm)
            if (picked.size >= MAX_LABELS) break
        }
        if (picked.isEmpty()) return listOf("∅")
        return picked.sorted()
    }
}
