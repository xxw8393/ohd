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
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Demo「世界地图」：跑过的页面、item、页间边，落盘到 `~/.ohd/worldmap/<bundle>.json`。
 *
 * 用途：
 * - 用例设计：`ohd map path --to <地标>` → Back×N + 滑×M / 约像素
 * - 排障：日志里的操作对齐到 pageId / item
 * - 与 LayoutCache（进程内当前树）互补：地图是跨进程、可存档的导航知识
 */
object WorldMap {

    const val SCHEMA = "ohd-worldmap/1"
    /** 内容安全区内约一屏行程粗算（与 UiNav.scrollSwipePixels 同量级） */
    const val DEFAULT_SWIPE_PIXELS = 1200

    data class Item(
        val label: String,
        val swipesFromTop: Int? = null,
        val approxPixelsY: Int? = null,
        val centerX: Int? = null,
        val centerY: Int? = null,
        val clickable: Boolean = true,
        val lastSeenMs: Long = System.currentTimeMillis(),
    )

    data class Page(
        val id: String,
        val summary: String,
        val labels: List<String>,
        val items: List<Item>,
        val lastSeenMs: Long = System.currentTimeMillis(),
    )

    data class Edge(
        val from: String,
        val to: String,
        val action: String,
        val label: String? = null,
        val atMs: Long = System.currentTimeMillis(),
    )

    data class Doc(
        val bundle: String,
        val updatedAt: Long,
        val pages: Map<String, Page>,
        val edges: List<Edge>,
        val currentPageId: String?,
        val backStack: List<String>,
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

    fun dir(): Path = Platform.home.resolve(".ohd").resolve("worldmap")

    fun pathFor(bundle: String): Path {
        val safe = bundle.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return dir().resolve("$safe.json")
    }

    fun load(bundle: String): Doc {
        mem[bundle]?.let { return it }
        val p = pathFor(bundle)
        if (!Files.isRegularFile(p)) {
            return Doc(bundle, 0, emptyMap(), emptyList(), null, emptyList()).also { mem[bundle] = it }
        }
        return runCatching { parse(Files.readString(p), bundle) }
            .getOrElse { Doc(bundle, 0, emptyMap(), emptyList(), null, emptyList()) }
            .also { mem[bundle] = it }
    }

    fun save(doc: Doc) {
        synchronized(lock) {
            mem[doc.bundle] = doc
            Files.createDirectories(dir())
            Files.writeString(pathFor(doc.bundle), encode(doc) + "\n")
        }
    }

    fun clear(bundle: String) {
        synchronized(lock) {
            mem.remove(bundle)
            runCatching { Files.deleteIfExists(pathFor(bundle)) }
        }
    }

    /** 看见一页：写入/合并 items，设为 current（可选压栈） */
    fun observePage(
        bundle: String,
        flat: List<LayoutNode>,
        pushIfNew: Boolean = false,
        sceneSwipes: Int? = null,
        swipePixels: Int = DEFAULT_SWIPE_PIXELS,
    ): Page {
        val (id, summary) = LayoutCache.fingerprint(flat)
        val items = itemsFromFlat(flat, sceneSwipes, swipePixels)
        val labels = items.map { it.label }.distinct()
        synchronized(lock) {
            val cur = load(bundle)
            val pages = cur.pages.toMutableMap()
            val old = pages[id]
            pages[id] = Page(
                id = id,
                summary = summary,
                labels = (old?.labels.orEmpty() + labels).distinct(),
                items = mergeItems(old?.items.orEmpty(), items),
                lastSeenMs = System.currentTimeMillis(),
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
            pages[pageId] = page.copy(
                items = mergeItems(page.items, learned),
                labels = (page.labels + learned.map { it.label }).distinct(),
                lastSeenMs = System.currentTimeMillis(),
            )
            save(cur.copy(pages = pages, updatedAt = System.currentTimeMillis()))
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
        val hits = doc.pages.values.flatMap { p ->
            p.items.filter { it.label.lowercase().contains(needle) }.map { Hit(p, it) }
        }
        if (hits.isEmpty()) return null
        // 取 swipe 信息最全的；同页优先当前页
        val start = fromPageId ?: doc.currentPageId
        val hit = hits.minWith(
            compareBy<Hit> { if (it.page.id == start) 0 else 1 }
                .thenBy { it.item.swipesFromTop ?: 999 }
                .thenByDescending { it.item.lastSeenMs },
        )
        val steps = mutableListOf<Step>()
        if (start != null && start != hit.page.id) {
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
            if (sw != null) append(" / 滑$sw(~${px}px)")
            append(" / ${steps.size} 步")
        }
        return Plan(hit.item.label, hit.page.id, steps, summary)
    }

    private fun bfsPages(doc: Doc, from: String, to: String): List<String>? {
        if (from == to) return listOf(from)
        val adj = mutableMapOf<String, MutableList<Pair<String, Edge>>>()
        for (e in doc.edges) {
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
                else -> Item(
                    label = n.label,
                    swipesFromTop = minNullable(o.swipesFromTop, n.swipesFromTop),
                    approxPixelsY = minNullable(o.approxPixelsY, n.approxPixelsY),
                    centerX = n.centerX ?: o.centerX,
                    centerY = n.centerY ?: o.centerY,
                    clickable = n.clickable || o.clickable,
                    lastSeenMs = maxOf(o.lastSeenMs, n.lastSeenMs),
                )
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
                    put("id", p.id)
                    put("summary", p.summary)
                    put("lastSeenMs", p.lastSeenMs)
                    put("labels", buildJsonArray { p.labels.forEach { add(JsonPrimitive(it)) } })
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
            val labels = o["labels"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            pages[id] = Page(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: id,
                summary = o["summary"]?.jsonPrimitive?.contentOrNull ?: "",
                labels = labels,
                items = items,
                lastSeenMs = o["lastSeenMs"]?.jsonPrimitive?.longOrNull ?: 0L,
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
        return Doc(bundle, updatedAt, pages, edges, current, stack)
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
        )
    }

    fun humanSummary(doc: Doc): String = buildString {
        appendLine("bundle=${doc.bundle}  pages=${doc.pages.size}  edges=${doc.edges.size}  current=${doc.currentPageId?.take(8) ?: "-"}")
        doc.pages.values.sortedByDescending { it.lastSeenMs }.take(12).forEach { p ->
            appendLine("  页 ${p.id.take(8)}  ${p.summary.take(48)}  items=${p.items.size}")
            p.items.filter { (it.swipesFromTop ?: 0) > 0 }.take(5).forEach { itx ->
                appendLine("    · ${itx.label.take(36)}  滑${itx.swipesFromTop}(~${itx.approxPixelsY}px)")
            }
        }
        doc.edges.takeLast(8).forEach { e ->
            appendLine("  边 ${e.from.take(6)} -${e.action}${e.label?.let { "($it)" } ?: ""}→ ${e.to.take(6)}")
        }
    }
}
