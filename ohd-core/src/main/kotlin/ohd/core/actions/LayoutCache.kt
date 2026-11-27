package ohd.core.actions

import ohd.core.DeviceOps
import ohd.core.Hdc
import ohd.core.Session
import ohd.core.parsers.LayoutNode
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 多页布局缓存：按 **页面指纹** 存整树，按设备保留「当前页 + 返回栈」。
 *
 * - key ≠ 设备 id：一台设备可缓存首页/二级/Probe… 多页并存
 * - 查找只对 **当前页** 取树（避免用别页坐标盲点）
 * - 当前页未命中 → dump → 用指纹写入/覆盖该页槽，并更新 current
 * - `Back` → 弹栈恢复上一页 current（不 dump）；栈空则只清 current
 */
object LayoutCache {

    data class PageEntry(
        val pageId: String,
        val flat: List<LayoutNode>,
        val atMs: Long,
        /** 便于人读：指纹算进去的标签摘要 */
        val summary: String,
    )

    data class DeviceState(
        val pages: ConcurrentHashMap<String, PageEntry> = ConcurrentHashMap(),
        /** 当前页指纹；null 表示未知，必须 dump */
        @Volatile var currentId: String? = null,
        val backStack: ArrayDeque<String> = ArrayDeque(),
    )

    private val devices = ConcurrentHashMap<String, DeviceState>()

    fun deviceKey(hdc: Hdc): String = hdc.connectKey?.takeIf { it.isNotBlank() } ?: hdc.kind.label

    private fun state(hdc: Hdc): DeviceState = devices.getOrPut(deviceKey(hdc)) { DeviceState() }

    /**
     * 页面指纹：可见文本/描述标签集合的稳定哈希（与坐标无关，同页滚动后可能变——滚动应 invalidateCurrent）。
     */
    fun fingerprint(flat: List<LayoutNode>): Pair<String, String> {
        val labels = flat.asSequence()
            .mapNotNull { n ->
                val t = n.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: n.description?.trim()?.takeIf { it.isNotEmpty() }
                t?.replace(Regex("\\s+"), " ")?.take(48)
            }
            .filter { it.length >= 2 }
            // 状态栏时钟/系统手势提示每轮都变。若不剔除，指纹会跟着时间走，
            // 同一个页面每次 dump 都算成新 page（实测清洗后跑 3 次 tree 就多出 2 页）。
            .filter { !ohd.core.actions.SceneSignature.isNoiseLabel(it) }
            .toSortedSet()
        val material = buildString {
            append(flat.size)
            append('#')
            append(labels.size)
            labels.take(48).forEach { append('|').append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(material.toByteArray(Charsets.UTF_8))
        val id = digest.joinToString("") { b -> "%02x".format(b) }.take(16)
        val summary = labels.take(6).joinToString(",")
        return id to summary
    }

    /** 当前页树；无 current 或过期 → null */
    fun current(hdc: Hdc, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): PageEntry? {
        val st = state(hdc)
        val id = st.currentId ?: return null
        val e = st.pages[id] ?: return null
        if (maxAgeMs >= 0 && System.currentTimeMillis() - e.atMs > maxAgeMs) return null
        return e
    }

    fun get(hdc: Hdc, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): List<LayoutNode>? =
        current(hdc, maxAgeMs)?.flat

    fun pageCount(hdc: Hdc): Int = state(hdc).pages.size

    fun put(hdc: Hdc, flat: List<LayoutNode>, pushIfNew: Boolean = false): PageEntry {
        val (id, summary) = fingerprint(flat)
        val st = state(hdc)
        val entry = PageEntry(id, flat, System.currentTimeMillis(), summary)
        val prev = st.currentId
        st.pages[id] = entry
        if (pushIfNew && prev != null && prev != id) {
            st.backStack.addLast(prev)
            // 防止栈无限涨
            while (st.backStack.size > 32) st.backStack.removeFirst()
        }
        st.currentId = id
        return entry
    }

    /** 滚动/同页大变：丢掉当前页槽，保留其它页 */
    fun invalidateCurrent(hdc: Hdc) {
        val st = state(hdc)
        val id = st.currentId ?: return
        st.pages.remove(id)
        st.currentId = null
    }

    /** 清掉该设备全部页（--fresh） */
    fun invalidate(hdc: Hdc) {
        devices.remove(deviceKey(hdc))
    }

    fun clear() {
        devices.clear()
    }

    /**
     * 系统 Back：弹出上一页为 current。栈空则 current=null（下次 dump）。
     * @return 恢复到的 pageId，或 null
     */
    fun noteBack(hdc: Hdc): String? {
        val st = state(hdc)
        if (st.backStack.isEmpty()) {
            st.currentId = null
            return null
        }
        val prev = st.backStack.removeLast()
        st.currentId = if (st.pages.containsKey(prev)) prev else null
        return st.currentId
    }

    /**
     * 进栈成功后（go --expect 验过）：用新树替换 current，并把旧 current 压栈。
     */
    fun noteEnter(hdc: Hdc, flat: List<LayoutNode>): PageEntry = put(hdc, flat, pushIfNew = true)

    /**
     * @return flat to source（`cache:<pageId>` / `dump:<pageId>`）
     */
    fun nodes(
        hdc: Hdc,
        session: Session?,
        fresh: Boolean = false,
        maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
        pushIfNew: Boolean = false,
        bundle: String? = null,
    ): Pair<List<LayoutNode>, String> {
        if (fresh) {
            // 只忘掉当前页身份，其它页仍保留；强制 dump
            state(hdc).currentId = null
        } else {
            current(hdc, maxAgeMs)?.let { return it.flat to "cache:${it.pageId}" }
        }
        // 传 bundle 让 dump 用 `-b` 限定窗口，避免把状态栏/系统手势并进树里
        val flat = DeviceOps.captureLayout(hdc, session, bundle = bundle).flat()
        val e = put(hdc, flat, pushIfNew = pushIfNew)
        return flat to "dump:${e.pageId}"
    }

    const val DEFAULT_MAX_AGE_MS = 300_000L
}
