package ohd.core.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import ohd.core.Platform
import ohd.core.parsers.LayoutNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Demo「世界地图」：跑过的页面、item、页间边，落盘到 `~/.ohd/worldmap/<bundle>.json`。
 *
 * 用途：
 * - 用例设计：`ohd map path --to <地标>` → Back×N + 滑×M / 约像素
 * - 排障：日志里的操作对齐到 pageId / item
 * - 与 LayoutCache（进程内当前树）互补：地图是跨进程、可存档的导航知识
 */
object WorldMap {

    /**
     * 落盘 schema 版本。
     *
     * - `/2`：加入 sceneId / viewport / landmarkKeys
     * - `/3`：不再写 `pages[*].id`（与对象 key 重复）与 `pages[*].labels`（纯冗余），
     *   文件改为 gzip。读取侧对 `/2`、`/1` 全兼容，无需迁移动作 —— 旧档读进来后
     *   下一次落盘即自动升级成 `/3`。
     */
    const val SCHEMA = "ohd-worldmap/3"
    /** 内容安全区内约一屏行程粗算（与 UiNav.scrollSwipePixels 同量级） */
    const val DEFAULT_SWIPE_PIXELS = 1200

    /** 同场景内同名元素纵坐标差超过此值即认为会随滚动漂移，坐标不可直接复用。 */
    const val STABLE_TOLERANCE_PX = 80

    /** 地标出现率超过此比例即视为泛词，不得作为页面身份（back 实测 67%）。 */
    const val GENERIC_LANDMARK_DF = 0.35

    /** 页数不足此值时不做泛词判定，避免冷启动误杀。 */
    const val GENERIC_LANDMARK_MIN_SAMPLE = 8

    /** 无同场景对照时，本页至少被观测这么多次才敢复用坐标。 */
    const val STABLE_MIN_OBSERVATIONS = 2

    /** 显式 `map prune` 的默认页数上限。只在人工要求瘦身时生效，故取值放宽。 */
    const val MAX_PAGES = 5000

    /** 每个 scene 保留的滚动变体数。够复盘「顶/中/底」，又不至于无界膨胀。 */
    const val VARIANTS_PER_SCENE = 8

    data class Item(
        val label: String,
        val swipesFromTop: Int? = null,
        val approxPixelsY: Int? = null,
        val centerX: Int? = null,
        val centerY: Int? = null,
        val clickable: Boolean = true,
        val lastSeenMs: Long = System.currentTimeMillis(),
        /**
         * 坐标被显式作废的时刻（验标失败/点空之后）。
         *
         * 为什么需要独立字段而不是「把 centerX/Y 置 null」：
         * [mergeItems] 对新旧 item 做 `n.centerX ?: o.centerX`，null 会被旧值补回来，
         * 于是「作废」在下一轮 observePage 就复活，等于从未生效。
         * 有了这个时间戳，merge 时可以与 [lastSeenMs] 比较：作废晚于最后一次看见，
         * 就不许再把旧坐标捞回来。
         *
         * 0 表示从未作废。
         */
        val coordsInvalidatedAt: Long = 0L,
    ) {
        /**
         * 坐标是否可用：坐标存在，且没有生效中的墓碑。
         *
         * 判据用「作废时刻不早于最后观测」而不是严格大于：同一毫秒内作废
         * （测试与快节奏调用里很常见）必须也算作已作废，否则边界上会漏判。
         * 而复活路径会显式把 coordsInvalidatedAt 清 0，不依赖这个比较。
         */
        val coordsUsable: Boolean
            get() = centerX != null && centerY != null && coordsInvalidatedAt <= 0L
    }

    data class Page(
        val id: String,
        val summary: String,
        val labels: List<String>,
        val items: List<Item>,
        val lastSeenMs: Long = System.currentTimeMillis(),
        /**
         * 场景身份（见 [SceneSignature]）。同一页面的不同滚动位置共享同一个 sceneId；
         * 导航/滑次/BFS 一律按 scene 而非 page。旧档缺失时回落为 pageId，语义等价于旧行为。
         */
        val sceneId: String = id,
        /** 该页被观测到的次数。用于在缺少同场景对照时判断坐标可信度。 */
        val seenCount: Int = 1,
        /** sceneId 是否由「验标地标」确定（比 dump 取样权威，不应被取样结果覆盖）。 */
        val landmarkScene: Boolean = false,
        /**
         * 该页面被哪些验标地标确认过（归一后的 `landmark:xxx`）。
         *
         * 同一屏可以用不同 `--expect` 确认（它是子串匹配），所以这是集合而非单值。
         * 主查询用 [sceneId]，这里保留全部证据，避免后来的地标把先前的覆盖掉。
         */
        val landmarkKeys: List<String> = emptyList(),
        /**
         * 视口签名（见 [SceneSignature.viewportSignature]）：**当前这一屏**的可点击元素布局。
         *
         * 与 [sceneId] 分工不同：sceneId 管导航（跨滚动合并），视口管坐标安全（精确到当前屏）。
         * `--map-first` 只有在视口与记录的完全一致时才允许直接复用旧坐标。
         */
        val viewport: String = "",
    )

    data class Edge(
        val from: String,
        val to: String,
        val action: String,
        val label: String? = null,
        val atMs: Long = System.currentTimeMillis(),
    )

    /**
     * 点后验标失败且进程 pid 变化/消失 → 记为 crash 路径，后续 go/plan 放弃同 (from,label)。
     */
    data class CrashMark(
        val fromPageId: String,
        val label: String,
        val pidBefore: String?,
        val pidAfter: String?,
        val expect: String? = null,
        val atMs: Long = System.currentTimeMillis(),
    )

    data class Doc(
        val bundle: String,
        val updatedAt: Long,
        val pages: Map<String, Page>,
        val edges: List<Edge>,
        val currentPageId: String?,
        val backStack: List<String>,
        val crashes: List<CrashMark> = emptyList(),
    )

    data class Step(
        val kind: String,
        val detail: String,
        val swipes: Int? = null,
        val pixels: Int? = null,
        val label: String? = null,
        val pageId: String? = null,
    )

    data class Plan(
        val targetLabel: String,
        val targetPageId: String,
        val steps: List<Step>,
        val summary: String,
    )

    private val lock = Any()
    private val mem = ConcurrentHashMap<String, Doc>()

    /**
     * 每个包最近一次读/写时看到的**解压后正文字节数**。
     *
     * 只为 `Health.bloatedBytes` 用：落盘已改 gzip，磁盘体积比正文小一个数量级，
     * 拿它比 12MB 阈值永远不触发。读档时顺手记（不额外开销），写档时也顺手更新，
     * 于是 health 不必为拿体积再 encode 一遍。
     */
    private val rawBytesCache = ConcurrentHashMap<String, Long>()

    /**
     * label 倒排索引：`rawLabel -> pageIds`，供 [findMapTap] / [planTo] 缩小候选页。
     *
     * 为什么需要：两处都在热路径上对**全部** item 做 `label.lowercase().contains(needle)`。
     * 899 页 27169 个 item 实测单次 **1.35ms**（其中 1.32ms 是 27169 次 `lowercase()`），
     * 而 raw label 去重后只有 1051 个。改成「只对去重后的 1051 个 key 归一 + contains」
     * 后查询降到 **0.043ms**；索引冷建 **0.28ms**，于是**首次调用**就是净赚（0.32ms vs 1.35ms），
     * 不依赖「同进程多次查询」才回本。
     *
     * 键为什么用 **raw label** 而不是归一后：建索引时若逐项 `lowercase()` 就退化成
     * 原本那 1.32ms，白白抵消收益。raw 键零成本入桶，归一推迟到查询时对 1051 个 key 做。
     * 归一是幂等的，所以「对 key 归一后 contains」与「逐项归一后 contains」结果集相同。
     *
     * 失效粒度（最细一档）：**按 page 对象身份**。
     * - 所有写页路径都是 `pages[id] = page.copy(...)`（copy-on-write），未改动的页身份不变
     *   ⇒ 身份相等即内容未变，这是零成本且正确的脏判据；[Page] 全 `val`、`items` 不可变，
     *   不存在「身份不变而内容变了」的旁路。
     * - 只重建身份变了的页那几十条 posting，其余直接复用；只有 `load()` 后首次才全量建。
     * - 全量重建只在索引缺失时发生，不是每次查询的逃逸路径。
     *
     * 只存**可推导**数据，不参与 encode/parse，地图文件体积不受影响。
     */
    private class LabelIndex {
        /** raw label → 该 label 出现过的 pageId（可重复无害，候选集会去重）。 */
        val postings = HashMap<String, MutableList<String>>()

        /** pageId → 该页全部的 raw label，只用于摘除旧 posting，避免扫全表。 */
        val pageLabels = HashMap<String, List<String>>()

        /** pageId → Page 对象身份。身份不等即该页已变。 */
        val pageRefs = HashMap<String, Page>()
    }

    private val labelIndexes = HashMap<String, LabelIndex>()

    private fun labelIndexRemovePage(ix: LabelIndex, pageId: String) {
        ix.pageRefs.remove(pageId)
        val labels = ix.pageLabels.remove(pageId) ?: return
        for (label in labels) {
            val ids = ix.postings[label] ?: continue
            ids.removeAll { it == pageId }
            if (ids.isEmpty()) ix.postings.remove(label)
        }
    }

    private fun labelIndexAddPage(ix: LabelIndex, page: Page) {
        val labels = ArrayList<String>(page.items.size)
        for (it in page.items) {
            labels.add(it.label)
            ix.postings.getOrPut(it.label) { ArrayList() }.add(page.id)
        }
        ix.pageLabels[page.id] = labels
        ix.pageRefs[page.id] = page
    }

    /**
     * 拿到与 `doc.pages` 一致的索引。**必须在 [lock] 内调用**。
     *
     * 增量维护：删除已消失的页、重建身份变化（含新增）的页，其余零开销保留。
     */
    private fun labelIndex(bundle: String, pages: Map<String, Page>): LabelIndex {
        val ix = labelIndexes.getOrPut(bundle) { LabelIndex() }
        // 先摘掉已消失的页（prune / clear / 换档后 pages 变小）
        if (ix.pageRefs.size > pages.size || ix.pageRefs.keys.any { it !in pages }) {
            for (gone in ix.pageRefs.keys.toList()) {
                if (gone !in pages) labelIndexRemovePage(ix, gone)
            }
        }
        // 再重建身份变了的页（含新增）；未改动的页零开销保留
        for ((id, page) in pages) {
            if (ix.pageRefs[id] === page) continue
            labelIndexRemovePage(ix, id)
            labelIndexAddPage(ix, page)
        }
        return ix
    }

    /**
     * 命中 `needle` 的页与 label：返回 (候选 pageId 集合, 命中 needle 的 **raw label** 集合)。
     *
     * 只对去重后的 raw label 归一，避免对全部 item 逐个 `lowercase()`。
     * `needle` 必须已 `lowercase`。
     */
    private fun labelMatches(ix: LabelIndex, needle: String): Pair<Set<String>, Set<String>> {
        val pages = HashSet<String>()
        val labels = HashSet<String>()
        for ((label, ids) in ix.postings) {
            if (!label.lowercase().contains(needle)) continue
            labels.add(label)
            pages.addAll(ids)
        }
        return pages to labels
    }

    /**
     * 有未落盘改动的包。
     *
     * 实测单包地图 21.8MB：每次全量 encode + 写盘约 1s，而一次 go 会触发多次
     * observePage —— 这笔开销与设备无关，纯属浪费。改为「内存即时更新 + 命令结束时
     * flushAll 一次」，同进程内读取始终看到最新态。
     */
    private val dirty: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

    fun dir(): Path = Platform.home.resolve(".ohd").resolve("worldmap")

    fun pathFor(bundle: String): Path {
        val safe = bundle.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return dir().resolve("$safe.json.gz")
    }

    /**
     * 旧明文档路径（`<bundle>.json`）。
     *
     * 只为向后兼容存在：升级后第一次读会从它读起，下一次落盘就换成 gz 形态。
     * 不再往这个路径写。
     */
    private fun legacyPathFor(bundle: String): Path {
        val safe = bundle.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return dir().resolve("$safe.json")
    }

    /** 实际存在的地图文件：优先 gz，其次旧明文档；都没有返回 null。 */
    private fun existingPathFor(bundle: String): Path? = listOf(pathFor(bundle), legacyPathFor(bundle))
        .firstOrNull { Files.isRegularFile(it) }

    fun load(bundle: String): Doc {
        mem[bundle]?.let { return it }
        val p = existingPathFor(bundle)
        if (p == null) {
            return Doc(bundle, 0, emptyMap(), emptyList(), null, emptyList(), emptyList())
                .also { mem[bundle] = it }
        }
        // 半截文件必须报错而不是静默当空地图：原子写已保证不会有半截，
        // 但历史残档/外部改坏仍可能出现，静默归零会让累积的地图无声消失。
        return runCatching {
            val text = readText(p)
            // 顺手记正文体积：避免 health 为判膨胀再 encode 一遍
            rawBytesCache[bundle] = text.toByteArray(StandardCharsets.UTF_8).size.toLong()
            parse(text, bundle)
        }
            .getOrElse { e ->
                System.err.println(
                    "! 世界地图解析失败，已按空地图继续（旧档未删除）：$p\n" +
                        "  ${e.message?.take(160)}\n" +
                        "  -> 需要重建请跑 `ohd map clear --bundle $bundle`",
                )
                Doc(bundle, 0, emptyMap(), emptyList(), null, emptyList(), emptyList())
            }
            .also { mem[bundle] = it }
    }

    /**
     * 落盘。**不做任何自动剪枝**：地图是跨会话积累的导航资产，
     * 隐式瘦身会静默丢掉已学到的页（实测一次写入就削掉 80% 历史），
     * 需要瘦身只能显式跑 `ohd map prune`。
     */
    fun save(doc: Doc) {
        synchronized(lock) {
            mem[doc.bundle] = doc
            dirty.add(doc.bundle)
        }
    }

    /** 立即落盘（旧 save 的同步语义），供需要落盘即可见的调用方使用。 */
    fun saveNow(doc: Doc) {
        synchronized(lock) {
            mem[doc.bundle] = doc
            writeToDisk(doc)
            dirty.remove(doc.bundle)
        }
    }

    /** 把内存里的最新状态刷到磁盘；该包无改动时不做任何事。 */
    fun flush(bundle: String) {
        synchronized(lock) {
            if (!dirty.remove(bundle)) return
            mem[bundle]?.let { writeToDisk(it) }
        }
    }

    /**
     * 刷所有未落盘的包。**命令结束前必须调用**，否则本步学到的内容会丢。
     */
    fun flushAll() {
        val pending = synchronized(lock) { dirty.toList() }
        pending.forEach { flush(it) }
    }

    /**
     * 原子落盘 + gzip 压缩。
     *
     * 两点都是实测出来的：
     * - **原子**：原来直接 `Files.writeString` 覆写正式文件，进程被杀/断电会留半截 JSON，
     *   而 load 的容错是「当空地图」——攒了几周的地图会静默归零。改为同目录 `.tmp` + `ATOMIC_MOVE`。
     * - **gzip**：4.4MB 明文压到 0.35MB（8%），解压约 1ms（热）/ 6.5ms（冷），
     *   比省下的磁盘读 + page cache 压力划算得多；压缩本身约 25ms（level 6），
     *   只在命令结束写一次，不进 observePage 热路径。
     *
     * 用 level 6 而非 9：9 只多省 0.01MB 却要 2.5 倍压缩时间。
     */
    private fun writeToDisk(doc: Doc) {
        runCatching {
            Files.createDirectories(dir())
            val target = pathFor(doc.bundle)
            val tmp = target.resolveSibling("${target.fileName}.tmp")
            val bytes = encode(doc).toByteArray(StandardCharsets.UTF_8)
            rawBytesCache[doc.bundle] = bytes.size.toLong()
            Files.newOutputStream(tmp).use { os ->
                // 级别取默认 Deflater.DEFAULT_COMPRESSION（=6）。实测 4.4MB 明文：
                // lv1→0.55MB/11ms、lv6→0.36MB/26ms、lv9→0.35MB/63ms —— lv6 体积接近 lv9
                // 而压缩时间只有 40%；解压各级别都在 1ms 量级，不影响读。故不再设法改
                // GZIPOutputStream 里 protected 的 def 字段（Kotlin 取不到）。
                GZIPOutputStream(os, 1 shl 16).use { gz -> gz.write(bytes) }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            // gz 写成功后清掉旧明文档：否则 existingPathFor 优先 gz 是对的，
            // 但残档会长期占双份磁盘，且人工排查时看到两个文件容易误读成不同数据。
            runCatching { Files.deleteIfExists(legacyPathFor(doc.bundle)) }
        }
    }

    /**
     * 读地图文本。兼容两种落盘形态：
     * - gzip（magic `1f 8b`，新格式）
     * - 明文 JSON（旧档 / 手工写入 / `map export` 产物）
     *
     * 按 magic 分流而不是按扩展名：旧档与 `map export` 都是明文，混用不能读错。
     */
    private fun readText(p: Path): String {
        val bytes = Files.readAllBytes(p)
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) {
            return String(bytes, StandardCharsets.UTF_8)
        }
        val out = ByteArrayOutputStream(bytes.size * 4)
        GZIPInputStream(ByteArrayInputStream(bytes), 1 shl 16).use { it.copyTo(out) }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    /** 显式瘦身：按 scene 保留最近若干滚动变体，并裁到页数上限。仅由 `ohd map prune` 调用。 */
    fun pruneAndSave(bundle: String, maxPages: Int = MAX_PAGES): Pair<Int, Int> {
        synchronized(lock) {
            val before = load(bundle)
            val after = prune(before, maxPages)
            saveNow(after)
            return before.pages.size to after.pages.size
        }
    }

    /**
     * 列出磁盘上已存在的世界地图存档（包名 → 字节数），供 `ohd doctor` 体检。
     * 只读目录，不加载内容，因此对每个包都调用也很快。
     */
    fun installedMaps(): List<Pair<String, Long>> {
        val d = dir()
        if (!Files.isDirectory(d)) return emptyList()
        return runCatching {
            Files.list(d).use { st ->
                st.filter { p ->
                    val n = p.fileName.toString()
                    (n.endsWith(".json") || n.endsWith(".json.gz")) && !n.endsWith(".tmp")
                }
                    // 同一 bundle 可能同时留 gz 与旧明文档，按包名归并取其一（优先 gz，与 existingPathFor 一致）
                    .map { path ->
                        val n = path.fileName.toString()
                        val bundle = n.removeSuffix(".gz").removeSuffix(".json")
                        Triple(bundle, n.endsWith(".gz"), path)
                    }
                    .toList()
                    .groupBy { it.first }
                    .map { (bundle, files) ->
                        val pick = files.firstOrNull { it.second } ?: files.first()
                        bundle to runCatching { Files.size(pick.third) }.getOrDefault(0L)
                    }
            }
        }.getOrDefault(emptyList())
    }

    /** 世界地图健康快照（只读，不落盘不剪枝），供 `ohd doctor` 体检使用。 */
    data class Health(
        val pages: Int,
        val items: Int,
        /** 落盘字节数（schema/3 起是 gzip 后的体积）。 */
        val bytes: Long,
        /** 解压后的正文体积。judge 膨胀用它，对比绝对值才不随压缩率漂移。 */
        val rawBytes: Long,
        /** 归一化后内容相同的重复页数（= pages - distinctContents）。 */
        val duplicatePages: Int,
        /** 系统手势/纯数字时钟等垃圾 item 数。 */
        val junkItems: Int,
    ) {
        /**
         * 体积是否已超出「正常 App」量级：大于此值建议跑 map clean。
         *
         * 判的是 [rawBytes] 而非 [bytes]：schema/3 起落盘是 gzip（实测 4.4MB 明文 → 0.27MB），
         * 若仍拿压缩后体积比 12MB，这个告警永远不会触发，等于把体检项废掉。
         */
        val bloatedBytes: Boolean get() = rawBytes > BLOAT_BYTES
        val bloatedPages: Boolean get() = duplicatePages > DUPLICATE_PAGE_ALARM

        companion object {
            /** 实测：清洗后正常形态约 4.4MB 明文 / 891 页；超 12MB 说明已明显膨胀。 */
            const val BLOAT_BYTES = 12L * 1024 * 1024

            /** 重复页超过总页数一半且大于此数才算异常，避免小地图误报。 */
            const val DUPLICATE_PAGE_ALARM = 200
        }
    }

    fun health(bundle: String): Health {
        val doc = load(bundle)
        val size = existingPathFor(bundle)?.let { runCatching { Files.size(it) }.getOrDefault(0L) } ?: 0L
        var items = 0
        var junk = 0
        val contents = HashSet<String>()
        for (p in doc.pages.values) {
            items += p.items.size
            junk += p.items.count { SceneSignature.isNoiseLabel(it.label) }
            contents.add(contentKey(p))
        }
        return Health(
            pages = doc.pages.size,
            items = items,
            bytes = size,
            rawBytes = rawBytesCache[bundle] ?: 0L,
            duplicatePages = doc.pages.size - contents.size,
            junkItems = junk,
        )
    }

    /**
     * 清洗世界地图：**合并内容相同的重复页**，并剔除系统手势噪声 item。
     *
     * 实测药不对症的地方：单包涨到 21.8MB / 6598 页，但归一化后只有 891 种不同内容，
     * 5973 页（90%）是同一内容的重复快照（旧 schema 每个滚动位置各存一页）。
     * 合并是安全的：最大重复组 523 页内含 25 种 label，只有 1 种（归一后的 `#`，
     * 本就是无信息量的纯数字）坐标漂移，其余坐标完全一致。
     *
     * 合并策略：同内容组内保留**最近见过**的一页，坐标/item 取并集（同 label 取最近观测），
     * 指向被合并页的边改写到保留页，backStack 同步改写 —— 不留悬空引用。
     *
     * @return 清洗前页数与清洗后页数
     */
    fun cleanAndSave(bundle: String): Pair<Int, Int> {
        synchronized(lock) {
            val before = load(bundle)
            val groups = LinkedHashMap<String, MutableList<Page>>()
            for (p in before.pages.values) {
                groups.getOrPut(contentKey(p)) { mutableListOf() }.add(p)
            }
            val pages = LinkedHashMap<String, Page>()
            val redirect = HashMap<String, String>()
            for ((_, group) in groups) {
                val keep = group.maxByOrNull { it.lastSeenMs } ?: continue
                // 同 label 取最近观测的那份坐标
                val mergedItems = LinkedHashMap<String, Item>()
                for (pg in group.sortedBy { it.lastSeenMs }) {
                    for (it in pg.items) {
                        if (SceneSignature.isNoiseLabel(it.label)) continue
                        mergedItems[it.label.lowercase()] = it
                    }
                }
                val cleaned = keep.copy(
                    items = mergedItems.values.toList(),
                    labels = group.flatMap { it.labels }.distinct(),
                    seenCount = group.sumOf { it.seenCount },
                )
                pages[keep.id] = cleaned
                group.forEach { if (it.id != keep.id) redirect[it.id] = keep.id }
            }
            fun remap(id: String): String = redirect[id] ?: id
            val edges = before.edges
                .map { it.copy(from = remap(it.from), to = remap(it.to)) }
                .distinctBy { listOf(it.from, it.to, it.action, it.label) }
            val stack = before.backStack.map { remap(it) }.distinct()
            val after = before.copy(
                pages = pages,
                edges = edges,
                backStack = stack,
                currentPageId = before.currentPageId?.let { remap(it) },
            )
            saveNow(after)
            return before.pages.size to after.pages.size
        }
    }

    /**
     * 内容归并键：归一化后的 label 集合。与 sceneId 不同，它**不含位置信息**，
     * 因此同一内容的多次快照（不同滚动位置、重复观测）会落进同一组。
     */
    private fun contentKey(page: Page): String =
        page.items.asSequence()
            .filter { !SceneSignature.isNoiseLabel(it.label) }
            .map { SceneSignature.normalizeLabel(it.label) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString("|")

    /**
     * 页数上限剪枝：按 scene 分组，每组只保留最近见过的 [VARIANTS_PER_SCENE] 个滚动变体。
     *
     * **只由显式 `ohd map prune` 调用**，不在写入路径上跑。理由：地图是跨会话积累的资产，
     * 隐式瘦身会静默丢历史（实测一次写入就把 6565 页削到 1200）。
     * 剪枝按 scene 而非全局，保证「每个场景仍记得怎么走」。
     */
    private fun prune(doc: Doc, maxPages: Int): Doc {
        if (doc.pages.size <= maxPages) return doc
        val keep = LinkedHashSet<String>()
        doc.currentPageId?.let { keep.add(it) }
        doc.pages.values
            .groupBy { it.sceneId }
            .values
            .forEach { group ->
                group.sortedByDescending { it.lastSeenMs }
                    .take(VARIANTS_PER_SCENE)
                    .forEach { keep.add(it.id) }
            }
        // 场景数本身就超上限时，按 keep 出来的页数仍会大于上限 →
        // 再按最近使用做一次硬裁剪，保证落盘体积真的有界。
        val finalKeep = if (keep.size <= maxPages) {
            keep
        } else {
            LinkedHashSet(
                doc.pages.values
                    .filter { it.id in keep }
                    .sortedByDescending { it.lastSeenMs }
                    .take(maxPages)
                    .map { it.id },
            ).also { if (doc.currentPageId != null) it.add(doc.currentPageId) }
        }
        val pages = doc.pages.filterKeys { it in finalKeep }
        // 指向被剪页的边一起丢弃，避免留下悬空节点污染 BFS
        val edges = doc.edges.filter { it.from in finalKeep && it.to in finalKeep }
        val stack = doc.backStack.filter { it in finalKeep }
        return doc.copy(pages = pages, edges = edges, backStack = stack)
    }

    fun clear(bundle: String) {
        synchronized(lock) {
            mem.remove(bundle)
            dirty.remove(bundle)
            rawBytesCache.remove(bundle)
            labelIndexes.remove(bundle)
            runCatching {
                Files.deleteIfExists(pathFor(bundle))
                Files.deleteIfExists(legacyPathFor(bundle))
            }
        }
    }

    /**
     * 轻量确认：不拿 UI 树，只把「这页刚刚又被用上了」记下来。
     *
     * 为什么需要它：`--map-first` 命中时会**刻意不 dump**（省 400ms+），
     * 于是 `LayoutCache` 是空的，`observePage` 那条 `?.let` 静默跳过 ——
     * 结果「用了地图就更新不了地图」：seenCount 不涨、lastSeenMs 不刷新，
     * `coordsStable` 的 `seenCount >= STABLE_MIN_OBSERVATIONS` 永远不满足，
     * 实测 899 页里只有 269 页坐标可用。
     *
     * 这里只做两件事，零设备开销：
     * - `seenCount + 1`（证明这页被重复访问过，坐标复用的信心来源）
     * - `lastSeenMs = now`（新鲜度，供 findMapTap 排序与过期判断）
     *
     * **不动 items/坐标**：没有树就没有新观测，不能凭想象改坐标。
     * `viewport` 同理保持不变（它必须有树才能算），这是 map-first 的固有取舍。
     *
     * @param verified 验标是否通过。只有通过才清墓碑 ——
     *   「点完没崩」不等于「点对了」，无 expect 时清墓碑会把坏坐标重新放出来。
     * @return 确认后的页；页不存在（地图里没有）时返回 null，调用方应回退 dump
     */
    fun confirmPage(
        bundle: String,
        pageId: String,
        atMs: Long = System.currentTimeMillis(),
        verified: Boolean = false,
    ): Page? {
        if (pageId.isBlank()) return null
        synchronized(lock) {
            val cur = load(bundle)
            val page = cur.pages[pageId] ?: return null
            val updated = page.copy(
                seenCount = page.seenCount + 1,
                lastSeenMs = atMs,
                items = page.items.map { itx ->
                    when {
                        // 验标通过 = 这个坐标确实点对了，是最强的复活证据
                        verified && itx.coordsInvalidatedAt > 0L -> itx.copy(coordsInvalidatedAt = 0L, lastSeenMs = atMs)
                        else -> itx.copy(lastSeenMs = atMs)
                    }
                },
            )
            val pages = cur.pages.toMutableMap()
            pages[pageId] = updated
            save(cur.copy(pages = pages, updatedAt = atMs))
            return updated
        }
    }

    /** 看见一页：写入/合并 items，设为 current（可选压栈） */
    fun observePage(
        bundle: String,
        flat: List<LayoutNode>,
        pushIfNew: Boolean = false,
        sceneSwipes: Int? = null,
        swipePixels: Int = DEFAULT_SWIPE_PIXELS,
        /**
         * 验标地标（`--expect` 命中的那个元素）。
         *
         * 有它就用它做场景身份：它是「确认操作生效」时必须读到的元素，属于必做操作，
         * 不额外增加设备开销，且语义上就是「这一页的特征」。为 null 时退回 dump 取样。
         */
        landmark: String? = null,
    ): Page {
        val (id, summary) = LayoutCache.fingerprint(flat)
        val items = itemsFromFlat(flat, sceneSwipes, swipePixels)
        val labels = items.map { it.label }.distinct()
        synchronized(lock) {
            val cur = load(bundle)
            val pages = cur.pages.toMutableMap()
            val old = pages[id]
            val merged = mergeItems(old?.items.orEmpty(), items)
            // 地标太泛就不许当身份：实测 `back` 出现在 67% 的页面、`返回` 6%，
            // 用它做场景键会把大半个 App 并成一个场景，比纯取样更差。
            // 判断依赖全局出现率，所以必须放在能读到 pages 的地方。
            val usableLandmark = landmark?.takeIf { !isGenericLandmark(cur.pages, it) }
                ?.takeIf { SceneSignature.landmarkSceneLabels(it) != null }
            // 屏高不变：本进程算一次即复用，不额外 dump
            val (sceneId, _) = SceneSignature.of(
                flat,
                landmark = usableLandmark,
                screenHeight = screenHeightOf(flat),
            )
            // 地标身份权威性更高：一旦本页被某个地标确认过，就不让后续「无地标的取样」
            // 把它改成另一个身份，否则同一页在「带 --expect」与「不带」两轮间来回换 scene。
            val finalSceneId = if (usableLandmark == null && old != null && old.landmarkScene) {
                old.sceneId
            } else {
                sceneId
            }
            pages[id] = Page(
                id = id,
                summary = summary,
                labels = (old?.labels.orEmpty() + labels).distinct(),
                items = merged,
                lastSeenMs = System.currentTimeMillis(),
                sceneId = finalSceneId,
                seenCount = (old?.seenCount ?: 0) + 1,
                landmarkScene = old?.landmarkScene == true || usableLandmark != null,
                landmarkKeys = (
                    old?.landmarkKeys.orEmpty() +
                        SceneSignature.landmarkSceneLabels(usableLandmark).orEmpty()
                    ).distinct(),
                viewport = SceneSignature.viewportSignature(flat),
            )
            val stack = cur.backStack.toMutableList()
            var current = cur.currentPageId
            if (pushIfNew && current != null && current != id) {
                stack.add(current)
                while (stack.size > 32) stack.removeAt(0)
            }
            current = id
            val doc = Doc(bundle, System.currentTimeMillis(), pages, cur.edges, current, stack)
            save(doc)
            return pages[id]!!
        }
    }

    /** 列表滑学：同一 scene 页上更新 item 的 swipes/像素（按 label 合并取最小 swipe） */
    fun learnScroll(
        bundle: String,
        pageId: String,
        swipesFromTop: Int,
        flat: List<LayoutNode>,
        swipePixels: Int = DEFAULT_SWIPE_PIXELS,
    ) {
        val learned = itemsFromFlat(flat, swipesFromTop, swipePixels)
        synchronized(lock) {
            val cur = load(bundle)
            val page = cur.pages[pageId] ?: return
            val pages = cur.pages.toMutableMap()
            val merged = mergeItems(page.items, learned)
            pages[pageId] = page.copy(
                items = merged,
                labels = (page.labels + learned.map { it.label }).distinct(),
                lastSeenMs = System.currentTimeMillis(),
                seenCount = page.seenCount + 1,
            )
            save(cur.copy(pages = pages, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * 该页坐标是否可直接复用（`--map-first` 捷径的前提）。
     *
     * 判据：同场景下**别的页**观测过同名元素，且纵坐标差异超过 [STABLE_TOLERANCE_PX]。
     * 那说明该元素随滚动位移，直接点旧坐标必错 → 只能靠 swipesFromTop 滑过去。
     *
     * 每次现算而非落盘成字段：稳定性依赖「同场景其它页」这份会持续变化的事实，
     * 固化成字段后，后到的漂移观测就改不动已存页的旧判定，会一直命中过期坐标。
     * 代价是 O(同场景页数)，只发生在查询路径。
     *
     * 分组用 sceneId 而非 pageId：滚动变体本就该共享一套坐标知识。
     */
    /**
     * 由布局树推断屏幕高度，**进程内算一次即缓存**（屏幕像素不变）。
     *
     * 取所有节点 bounds 的最大下沿：dump 的根/应用窗口矩形基本覆盖整屏，
     * 而内容多少只会让「最大的那个内容元素」更靠下，不会超过屏幕。
     * 宁可略小也不能取错量级 —— 分区锚点按比例算，误差会整体平移。
     */
    @Volatile
    private var cachedScreenHeight: Int = 0

    private fun screenHeightOf(flat: List<LayoutNode>): Int {
        val cached = cachedScreenHeight
        if (cached > 0) return cached
        val h = flat.asSequence().mapNotNull { it.bounds?.bottom }.maxOrNull() ?: 0
        if (h > 0) cachedScreenHeight = h
        return h
    }

    /**
     * 地标是不是「泛词」——出现在太多页面上，无法标识某一页。
     *
     * 实测：`back` 出现在 67% 的页、`ohos运行时开关` 29%、`多实例测试` 18%。
     * 拿这种词当场景身份，等于把大半个 App 并成一个场景，
     * 比不带地标的 dump 取样（6565 页 → 100 场景）还差。
     *
     * 判据：该地标在所有已知页里的出现率超过 [GENERIC_LANDMARK_DF] 就拒绝它作身份。
     * 页数不足 [GENERIC_LANDMARK_MIN_SAMPLE] 时不判，避免冷启动把第一个地标误杀。
     */
    private fun isGenericLandmark(pages: Map<String, Page>, landmark: String?): Boolean {
        val n = SceneSignature.normalizeLabel(landmark ?: return true)
        if (n.isEmpty()) return true
        if (pages.size < GENERIC_LANDMARK_MIN_SAMPLE) return false
        var hit = 0
        for (p in pages.values) {
            if (p.items.any { SceneSignature.normalizeLabel(it.label) == n }) hit++
        }
        return hit.toDouble() / pages.size > GENERIC_LANDMARK_DF
    }

    /**
     * 按 sceneId 预分组页。
     *
     * 原来 [coordsStable] 内部对每一页都 `doc.pages.values.filter{...}` 扫全表，
     * 对全表判定一次就是 O(N²)：实测 896 页 × 全表过滤 = 每次 humanSummary 白烧时间，
     * 页数涨到 5000 时 `map show` 从 122ms 涨到 740ms（其中 512ms 全是这笔）。
     * 分组一次 O(N)，之后每页只在自己 scene 内查（实测单 scene ≤4 页）。
     *
     * 粒度选择：不做跨调用缓存 —— doc 是本进程内的快照，每次命令重新 load，
     * 进程内改页也是整表替换，跨调用缓存反而要引入失效逻辑且没有收益。
     */
    private fun peersByScene(pages: Collection<Page>): Map<String, List<Page>> =
        pages.groupBy { it.sceneId }

    private fun coordsStable(byScene: Map<String, List<Page>>, page: Page): Boolean {
        val peers = byScene[page.sceneId].orEmpty().filter { it.id != page.id }
        // 没有同场景对照时不能宣称"稳定"：旧 schema 每页各自成场景，此条件恒成立，
        // 会把 6500+ 页全判成可复用，--map-first 直接退回"拿旧坐标点"的错行为。
        // 此时退回到"同页自身是否观测过多次"来判，仍不满足就保守判不稳定。
        if (peers.isEmpty()) return page.seenCount >= STABLE_MIN_OBSERVATIONS
        val peerY = HashMap<String, Int>()
        for (p in peers) {
            for (it in p.items) {
                val y = it.centerY ?: continue
                peerY.putIfAbsent(it.label.lowercase(), y)
            }
        }
        return page.items.all { itx ->
            val mine = itx.centerY ?: return@all true
            val other = peerY[itx.label.lowercase()] ?: return@all true
            kotlin.math.abs(mine - other) <= STABLE_TOLERANCE_PX
        }
    }

    fun noteEdge(bundle: String, from: String, to: String, action: String, label: String?) {
        if (from.isBlank() || to.isBlank() || from == to) return
        synchronized(lock) {
            val cur = load(bundle)
            val edges = cur.edges.toMutableList()
            // 同 from/to/action/label 去重，保留最新
            edges.removeAll { it.from == from && it.to == to && it.action == action && it.label == label }
            edges.add(Edge(from, to, action, label))
            while (edges.size > 500) edges.removeAt(0)
            save(cur.copy(edges = edges, updatedAt = System.currentTimeMillis()))
        }
    }

    fun pathCrashKey(fromPageId: String, label: String): String =
        "${fromPageId.lowercase()}|${label.lowercase()}"

    fun isPathBanned(bundle: String, fromPageId: String?, label: String?): Boolean {
        if (fromPageId.isNullOrBlank() || label.isNullOrBlank()) return false
        val key = pathCrashKey(fromPageId, label)
        return load(bundle).crashes.any { pathCrashKey(it.fromPageId, it.label) == key }
    }

    fun findPathCrash(bundle: String, fromPageId: String?, label: String?): CrashMark? {
        if (fromPageId.isNullOrBlank() || label.isNullOrBlank()) return null
        val key = pathCrashKey(fromPageId, label)
        return load(bundle).crashes.lastOrNull { pathCrashKey(it.fromPageId, it.label) == key }
    }

    /**
     * 验标失败且 pid 变化/消失时写入。同 (from,label) 覆盖为最新一条；上限 200。
     */
    fun notePathCrash(
        bundle: String,
        fromPageId: String,
        label: String,
        pidBefore: String?,
        pidAfter: String?,
        expect: String? = null,
    ): CrashMark {
        val mark = CrashMark(
            fromPageId = fromPageId,
            label = label,
            pidBefore = pidBefore,
            pidAfter = pidAfter,
            expect = expect,
        )
        synchronized(lock) {
            val cur = load(bundle)
            val key = pathCrashKey(fromPageId, label)
            val crashes = cur.crashes
                .filterNot { pathCrashKey(it.fromPageId, it.label) == key }
                .toMutableList()
            crashes.add(mark)
            while (crashes.size > 200) crashes.removeAt(0)
            save(cur.copy(crashes = crashes, updatedAt = System.currentTimeMillis()))
        }
        return mark
    }

    fun clearCrashes(bundle: String) {
        synchronized(lock) {
            val cur = load(bundle)
            save(cur.copy(crashes = emptyList(), updatedAt = System.currentTimeMillis()))
        }
    }

    data class MapTap(
        val pageId: String,
        val item: Item,
        val index: Int,
        val matched: Int,
    )

    /**
     * `--map-first`：用已学中心点作候选。优先 [preferPageId]，否则按 lastSeen 跨页取最近带坐标的命中。
     */
    fun findMapTap(
        bundle: String,
        grep: String,
        preferPageId: String? = null,
        index: Int? = null,
        clickableOnly: Boolean = true,
        /**
         * 当前屏的视口签名（[SceneSignature.viewportSignature]）。
         *
         * 传了就做**精确匹配**：只有记录时的视口与当前屏完全一致，才允许复用旧坐标；
         * 列表一旦滚动，签名变化，捷径自动失效。为 null 时退回 [coordsStable] 的启发式判定。
         */
        currentViewport: String? = null,
    ): MapTap? {
        val needle = grep.lowercase()
        if (needle.isBlank()) return null
        val doc = load(bundle)
        // 一次分组，避免 pageHits 内每页各自扫全表（原 O(N²)）
        val byScene = peersByScene(doc.pages.values)
        // 索引段持锁并立刻快照成不可变集合，后续判定在锁外做（coordsStable 不碰索引）
        val (candPages, matchedLabels) = synchronized(lock) {
            labelMatches(labelIndex(bundle, doc.pages), needle)
        }
        data class Hit(val pageId: String, val item: Item)
        fun pageHits(page: Page): List<Hit> {
            // 视口一致 = 就是当前这一屏，坐标必然仍然有效（最强证据，可跳过启发式）
            val viewportOk = currentViewport != null &&
                page.viewport.isNotEmpty() &&
                page.viewport == currentViewport
            val stable = viewportOk || coordsStable(byScene, page)
            return page.items.mapNotNull { itx ->
                // 用命中集合判等，省掉逐项 lowercase：matchedLabels 是归一后的 raw label 集合
                if (itx.label !in matchedLabels) return@mapNotNull null
                // coordsUsable 同时管两件事：坐标存在，且没有被更晚的作废推翻
                if (!itx.coordsUsable) return@mapNotNull null
                if (clickableOnly && !itx.clickable) return@mapNotNull null
                // 不稳定的页不给捷径：列表元素会随滚动位移，旧坐标点下去必错
                if (!stable) return@mapNotNull null
                Hit(page.id, itx)
            }
        }
        val preferred = preferPageId?.let { doc.pages[it] }?.let { pageHits(it) }.orEmpty()
        val hits = if (preferred.isNotEmpty()) {
            preferred
        } else {
            // 只遍历候选页：无候选即无命中，不再扫 899 页全部 item
            doc.pages.values
                .filter { it.id in candPages }
                .sortedByDescending { it.lastSeenMs }
                .flatMap { pageHits(it) }
                .sortedByDescending { it.item.lastSeenMs }
        }
        if (hits.isEmpty()) return null
        if (index == null) {
            if (hits.size > 1) {
                // 同页多命中仍歧义；跨页时取最近一条（map-first 捷径）
                val samePage = hits.groupBy { it.pageId }
                if (preferPageId != null && (samePage[preferPageId]?.size ?: 0) > 1) return null
                if (preferred.size > 1) return null
            }
            val best = hits.first()
            return MapTap(best.pageId, best.item, 0, hits.size)
        }
        if (index < 0 || index >= hits.size) return null
        val hit = hits[index]
        return MapTap(hit.pageId, hit.item, index, hits.size)
    }

    /** expect 失败时作废该 label 坐标，迫使下次 dump 认路。 */
    fun invalidateItemCoords(bundle: String, pageId: String?, label: String) {
        if (pageId.isNullOrBlank() || label.isBlank()) return
        synchronized(lock) {
            val cur = load(bundle)
            val page = cur.pages[pageId] ?: return
            val key = label.lowercase()
            val now = System.currentTimeMillis()
            val items = page.items.map { itx ->
                if (itx.label.lowercase() != key) itx
                // 只打墓碑，不清坐标。
                // 清坐标是无效的（mergeItems 会用旧值补回来），而且会把
                // 「同一位置 vs 换了位置」这个判据一起毁掉。
                // 屏蔽由 Item.coordsUsable 负责：coordsInvalidatedAt > lastSeenMs 即不可用。
                else itx.copy(coordsInvalidatedAt = now)
            }
            val pages = cur.pages.toMutableMap()
            pages[pageId] = page.copy(items = items, lastSeenMs = now)
            save(cur.copy(pages = pages, updatedAt = now))
        }
    }

    fun noteBack(bundle: String): String? {
        synchronized(lock) {
            val cur = load(bundle)
            if (cur.backStack.isEmpty()) {
                save(cur.copy(currentPageId = null, updatedAt = System.currentTimeMillis()))
                return null
            }
            val stack = cur.backStack.toMutableList()
            val prev = stack.removeAt(stack.lastIndex)
            val current = if (cur.pages.containsKey(prev)) prev else null
            val from = cur.currentPageId
            val edges = cur.edges.toMutableList()
            if (from != null && current != null) {
                edges.removeAll { it.from == from && it.to == current && it.action == "back" }
                edges.add(Edge(from, current, "back", null))
            }
            save(
                cur.copy(
                    currentPageId = current,
                    backStack = stack,
                    edges = edges,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            return current
        }
    }

    fun setCurrent(bundle: String, pageId: String?) {
        synchronized(lock) {
            val cur = load(bundle)
            save(cur.copy(currentPageId = pageId, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * 算去某地标的导航计划：优先同页滑；否则 BFS 页边 + Back 栈，再滑到 item。
     */
    fun planTo(bundle: String, grep: String, fromPageId: String? = null): Plan? {
        val doc = load(bundle)
        val needle = grep.lowercase()
        data class Hit(val page: Page, val item: Item)
        val start = fromPageId ?: doc.currentPageId
        val bannedKeys = doc.crashes.map { pathCrashKey(it.fromPageId, it.label) }.toHashSet()
        // monkey 每步都会走到这里：原实现对全部 item 逐个 lowercase，实测单次 1.4ms。
        // 改成先取候选页 + 命中 label 集合，只在这些页上做过滤。
        val (candPages, matchedLabels) = synchronized(lock) {
            labelMatches(labelIndex(bundle, doc.pages), needle)
        }
        if (candPages.isEmpty()) return null
        val hits = doc.pages.values.filter { it.id in candPages }.flatMap { p ->
            p.items.filter { it.label in matchedLabels }.map { Hit(p, it) }
        }.filter { hit ->
            // 当前页点该 label 已标 crash → 放弃（后续 go 也应拒）
            start == null ||
                hit.page.id != start ||
                pathCrashKey(start, hit.item.label) !in bannedKeys
        }
        if (hits.isEmpty()) return null
        // 优先级：同场景（只是滚过头了）> 当前页 > 滑次少 > 新近
        val startScene = start?.let { doc.pages[it]?.sceneId }
        val hit = hits.minWith(
            compareBy<Hit> {
                when {
                    startScene == null -> 1
                    it.page.sceneId == startScene -> 0
                    else -> 1
                }
            }
                .thenBy { if (it.page.id == start) 0 else 1 }
                .thenBy { it.item.swipesFromTop ?: 999 }
                .thenByDescending { it.item.lastSeenMs },
        )
        var steps = mutableListOf<Step>()
        val sameScene = start != null && startScene != null && hit.page.sceneId == startScene
        if (start != null && start != hit.page.id && !sameScene) {
            val pagePath = bfsPages(doc, start, hit.page.id)
            if (pagePath == null) {
                // 回退：一路 Back 到目标页（若在栈里）或 Back 到尽再认路
                val stackIdx = doc.backStack.indexOf(hit.page.id)
                if (stackIdx >= 0 || doc.currentPageId == hit.page.id) {
                    val backs = doc.backStack.size - stackIdx
                    repeat(backs.coerceAtLeast(0)) {
                        steps += Step("back", "返回上一页")
                    }
                } else {
                    steps += Step("back", "未知边：先 Back 尝试回到已知页（最多 8）", swipes = 8)
                }
            } else {
                for (i in 0 until pagePath.size - 1) {
                    val a = pagePath[i]
                    val b = pagePath[i + 1]
                    val edge = doc.edges.find { it.from == a && it.to == b }
                        ?: doc.edges.find { it.from == b && it.to == a && it.action == "back" }
                    when {
                        edge?.action == "tap" ->
                            steps += Step("tap", "点「${edge.label}」进页", label = edge.label, pageId = b)
                        edge?.action == "back" || (edge == null) ->
                            steps += Step("back", "返回", pageId = b)
                        else ->
                            steps += Step(edge.action, edge.label ?: edge.action, label = edge.label, pageId = b)
                    }
                }
            }
        }
        val sw = hit.item.swipesFromTop
        val px = hit.item.approxPixelsY ?: sw?.times(DEFAULT_SWIPE_PIXELS)
        // 同场景但没学过滑次：不知道该滑几次，别编假滑步（swipes=0 的 swipe 会变成空操作还占一步）。
        // 仍然保留最后的 tap 步 —— 调用方要靠它落地，且计划本身得说明「滑次未知」。
        val swipesUnknown = sameScene && sw == null
        if (sw != null && sw > 0) {
            steps += Step(
                "swipe",
                "上滑约 $sw 次（~${px ?: "?"} px）到「${hit.item.label}」",
                swipes = sw,
                pixels = px,
                label = hit.item.label,
                pageId = hit.page.id,
            )
        }
        steps += Step("tap", "点击「${hit.item.label}」", label = hit.item.label, pageId = hit.page.id)
        val summary = buildString {
            append("→ 页 ${hit.page.id.take(8)}「${hit.page.summary.take(40)}」")
            if (sameScene) append(" / 同场景")
            if (swipesUnknown) append(" / 滑次未知（坐标已判漂移，须现 dump 认路）")
            else if (sw != null) append(" / 滑$sw(~${px}px)")
            append(" / ${steps.size} 步")
        }
        return Plan(hit.item.label, hit.page.id, steps, summary)
    }

    private fun bfsPages(doc: Doc, from: String, to: String): List<String>? {
        if (from == to) return listOf(from)
        val bannedKeys = doc.crashes.map { pathCrashKey(it.fromPageId, it.label) }.toHashSet()
        val adj = mutableMapOf<String, MutableList<Pair<String, Edge>>>()
        for (e in doc.edges) {
            // tap 边若已标 crash，不再走
            if (e.action == "tap" && !e.label.isNullOrBlank() &&
                pathCrashKey(e.from, e.label) in bannedKeys
            ) {
                continue
            }
            adj.getOrPut(e.from) { mutableListOf() }.add(e.to to e)
            if (e.action == "back") {
                adj.getOrPut(e.to) { mutableListOf() }.add(e.from to e)
            }
        }
        // 栈隐含 Back
        val stack = doc.backStack
        for (i in stack.indices) {
            val deeper = if (i == stack.lastIndex) doc.currentPageId else stack.getOrNull(i + 1)
            if (deeper != null) {
                adj.getOrPut(deeper) { mutableListOf() }
                    .add(stack[i] to Edge(deeper, stack[i], "back"))
            }
        }
        val q = ArrayDeque<String>()
        val prev = mutableMapOf<String, String?>()
        q.add(from)
        prev[from] = null
        while (q.isNotEmpty()) {
            val u = q.removeFirst()
            if (u == to) break
            for ((v, _) in adj[u].orEmpty()) {
                if (v in prev) continue
                prev[v] = u
                q.add(v)
            }
        }
        if (to !in prev) return null
        val path = mutableListOf<String>()
        var cur: String? = to
        while (cur != null) {
            path.add(cur)
            cur = prev[cur]
        }
        return path.asReversed()
    }

    private fun itemsFromFlat(
        flat: List<LayoutNode>,
        swipesFromTop: Int?,
        swipePixels: Int,
    ): List<Item> {
        val out = LinkedHashMap<String, Item>()
        for (n in flat) {
            val label = n.text?.trim()?.takeIf { it.isNotEmpty() }
                ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                ?: continue
            if (label.length < 2) continue
            // 系统手势提示、纯数字/状态栏时钟都必须挡在入口：
            // 它们在每次 dump 都会变（时钟每分钟一个值），一旦入库就会永久累积，
            // 实测污染出 1037 种、13216 条 item（占全库 33%），单页最多堆到 593 项。
            if (SceneSignature.isNoiseLabel(label)) continue
            val key = label.lowercase()
            val b = n.bounds
            out[key] = Item(
                label = label.take(80),
                swipesFromTop = swipesFromTop,
                approxPixelsY = swipesFromTop?.times(swipePixels),
                centerX = b?.centerX,
                centerY = b?.centerY,
                clickable = n.clickable,
            )
        }
        return out.values.toList()
    }

    private fun mergeItems(old: List<Item>, neu: List<Item>): List<Item> {
        val map = LinkedHashMap<String, Item>()
        old.forEach { map[it.label.lowercase()] = it }
        for (n in neu) {
            val k = n.label.lowercase()
            val o = map[k]
            map[k] = when {
                o == null -> n
                else -> {
                    // 墓碑是否仍在生效：只由「墓碑非零」决定。
                    // 不再拿 lastSeenMs 比较 —— 那要求两者严格有序，同毫秒作废会漏判，
                    // 而且 mergeItems 每轮都推进 lastSeenMs 会让墓碑自己失效。
                    // 复活的唯一出口是下面 sameSpot == false（元素换了位置）。
                    val dead = o.coordsInvalidatedAt > 0L
                    val sameSpot = n.centerX == null || (n.centerX == o.centerX && n.centerY == o.centerY)
                    val revive = !dead || !sameSpot
                    Item(
                        label = n.label,
                        swipesFromTop = minNullable(o.swipesFromTop, n.swipesFromTop),
                        approxPixelsY = minNullable(o.approxPixelsY, n.approxPixelsY),
                        centerX = n.centerX ?: o.centerX,
                        centerY = n.centerY ?: o.centerY,
                        clickable = n.clickable || o.clickable,
                        lastSeenMs = maxOf(o.lastSeenMs, n.lastSeenMs),
                        coordsInvalidatedAt = if (revive) 0L else o.coordsInvalidatedAt,
                    )
                }
            }
        }
        return map.values.sortedBy { it.swipesFromTop ?: 0 }
    }

    private fun minNullable(a: Int?, b: Int?): Int? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    fun encode(doc: Doc): String = buildJsonObject {
        put("schema", SCHEMA)
        put("bundle", doc.bundle)
        put("updatedAt", doc.updatedAt)
        put("currentPageId", doc.currentPageId ?: "")
        put("backStack", buildJsonArray { doc.backStack.forEach { add(JsonPrimitive(it)) } })
        put("pages", buildJsonObject {
            doc.pages.values.forEach { p ->
                put(p.id, buildJsonObject {
                    put("summary", p.summary)
                    put("lastSeenMs", p.lastSeenMs)
                    put("sceneId", p.sceneId)
                    put("seenCount", p.seenCount)
                    if (p.landmarkScene) put("landmarkScene", true)
                    if (p.landmarkKeys.isNotEmpty()) {
                        put("landmarkKeys", buildJsonArray { p.landmarkKeys.forEach { add(JsonPrimitive(it)) } })
                    }
                    if (p.viewport.isNotEmpty()) put("viewport", p.viewport)
                    // 不写 labels：全仓无任何决策读它，只是历史累积（896 页里 891 页的 labels
                    // 与 items 对得上，剩下的 36.9% 是早已下线的旧观测），却白占 1.09MB（25%）。
                    // 读取侧见 parse()：缺 labels 时由 items 重建，内存模型不变。
                    put("items", buildJsonArray {
                        p.items.forEach { itx ->
                            add(
                                buildJsonObject {
                                    put("label", itx.label)
                                    itx.swipesFromTop?.let { put("swipesFromTop", it) }
                                    itx.approxPixelsY?.let { put("approxPixelsY", it) }
                                    itx.centerX?.let { put("centerX", it) }
                                    itx.centerY?.let { put("centerY", it) }
                                    put("clickable", itx.clickable)
                                    put("lastSeenMs", itx.lastSeenMs)
                                    // 只在作废过时才写：正常 item 不带这个字段，省体积且旧档语义不变
                                    if (itx.coordsInvalidatedAt > 0L) {
                                        put("coordsInvalidatedAt", itx.coordsInvalidatedAt)
                                    }
                                },
                            )
                        }
                    })
                })
            }
        })
        put("edges", buildJsonArray {
            doc.edges.forEach { e ->
                add(
                    buildJsonObject {
                        put("from", e.from)
                        put("to", e.to)
                        put("action", e.action)
                        put("label", e.label ?: "")
                        put("atMs", e.atMs)
                    },
                )
            }
        })
        put("crashes", buildJsonArray {
            doc.crashes.forEach { c ->
                add(
                    buildJsonObject {
                        put("fromPageId", c.fromPageId)
                        put("label", c.label)
                        put("pidBefore", c.pidBefore ?: "")
                        put("pidAfter", c.pidAfter ?: "")
                        put("expect", c.expect ?: "")
                        put("atMs", c.atMs)
                    },
                )
            }
        })
    }.toString()

    private fun parse(raw: String, bundleFallback: String): Doc {
        val root = Json.parseToJsonElement(raw).jsonObject
        val bundle = root["bundle"]?.jsonPrimitive?.contentOrNull ?: bundleFallback
        val updatedAt = root["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
        val current = root["currentPageId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val stack = root["backStack"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val pages = mutableMapOf<String, Page>()
        root["pages"]?.jsonObject?.forEach { (id, el) ->
            val o = el.jsonObject
            val items = o["items"]?.jsonArray?.mapNotNull { parseItem(it.jsonObject) }.orEmpty()
            // 新档不写 labels（纯冗余）：缺字段时由 items 重建，保持内存模型与旧档一致。
            // 旧档/`map export` 仍可能带 labels，此时照用，避免读回语义发生变化。
            val labels = o["labels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: items.map { it.label }.distinct()
            pages[id] = Page(
                id = id,
                summary = o["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                labels = labels,
                items = items,
                lastSeenMs = o["lastSeenMs"]?.jsonPrimitive?.longOrNull ?: 0L,
                // schema/1 旧档没有 sceneId：回落 pageId，等价旧行为，读得动但不再新增漂移知识
                sceneId = o["sceneId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: (o["id"]?.jsonPrimitive?.contentOrNull ?: id),
                // 旧档没记观测次数：按 1 算，配合 sceneId 缺失 → 判为不可直接复用
                seenCount = o["seenCount"]?.jsonPrimitive?.intOrNull ?: 1,
                landmarkScene = o["landmarkScene"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                landmarkKeys = o["landmarkKeys"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                viewport = o["viewport"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
        val edges = root["edges"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val from = o["from"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val to = o["to"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Edge(
                from = from,
                to = to,
                action = o["action"]?.jsonPrimitive?.contentOrNull ?: "tap",
                label = o["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                atMs = o["atMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.orEmpty()
        val crashes = root["crashes"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val fromPageId = o["fromPageId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            CrashMark(
                fromPageId = fromPageId,
                label = label,
                pidBefore = o["pidBefore"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                pidAfter = o["pidAfter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                expect = o["expect"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                atMs = o["atMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }.orEmpty()
        return Doc(bundle, updatedAt, pages, edges, current, stack, crashes)
    }

    private fun parseItem(o: JsonObject): Item? {
        val label = o["label"]?.jsonPrimitive?.contentOrNull ?: return null
        return Item(
            label = label,
            swipesFromTop = o["swipesFromTop"]?.jsonPrimitive?.intOrNull,
            approxPixelsY = o["approxPixelsY"]?.jsonPrimitive?.intOrNull,
            centerX = o["centerX"]?.jsonPrimitive?.intOrNull,
            centerY = o["centerY"]?.jsonPrimitive?.intOrNull,
            clickable = o["clickable"]?.jsonPrimitive?.contentOrNull != "false",
            lastSeenMs = o["lastSeenMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            coordsInvalidatedAt = o["coordsInvalidatedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }

    fun humanSummary(doc: Doc): String = buildString {
        // 按 scene 分组一次供下列判定复用：原实现对每页各扫一遍全表（O(N²)）
        val scenes = peersByScene(doc.pages.values)
        // 判定结果按页缓存：下面「计数」和「前 12 页标记」两处都要用，重复调用等于把 O(N²) 再做一遍
        val stableById = HashMap<String, Boolean>(doc.pages.size * 2)
        doc.pages.values.forEach { stableById[it.id] = coordsStable(scenes, it) }
        val stable = stableById.count { it.value }
        appendLine(
            "bundle=${doc.bundle}  pages=${doc.pages.size}  scenes=${scenes.size}  edges=${doc.edges.size}  " +
                "crashes=${doc.crashes.size}  current=${doc.currentPageId?.take(8) ?: "-"}",
        )
        appendLine("  坐标可直接复用 ${stable}/${doc.pages.size} 页；其余须靠滑次（同场景内元素会随滚动位移）")
        doc.pages.values.sortedByDescending { it.lastSeenMs }.take(12).forEach { p ->
            appendLine(
                "  页 ${p.id.take(8)} (scene ${p.sceneId.take(8)}${if (stableById[p.id] == true) "" else ",漂移"})" +
                    "  ${p.summary.take(48)}  items=${p.items.size}",
            )
            p.items.filter { (it.swipesFromTop ?: 0) > 0 }.take(5).forEach { itx ->
                appendLine("    · ${itx.label.take(36)}  滑${itx.swipesFromTop}(~${itx.approxPixelsY}px)")
            }
        }
        doc.edges.takeLast(8).forEach { e ->
            appendLine("  边 ${e.from.take(6)} -${e.action}${e.label?.let { "($it)" } ?: ""}→ ${e.to.take(6)}")
        }
        doc.crashes.takeLast(8).forEach { c ->
            appendLine(
                "  禁 ${c.fromPageId.take(6)} →「${c.label.take(28)}」 " +
                    "pid ${c.pidBefore ?: "?"}→${c.pidAfter ?: "dead"}" +
                    (c.expect?.let { " expect=$it" } ?: ""),
            )
        }
    }
}
