package ohd.core.actions

import ohd.core.DeviceOps
import kotlin.concurrent.Volatile
import ohd.core.Hdc
import ohd.core.Session
import ohd.core.parsers.LayoutNode
import ohd.platform.Mutex
import ohd.platform.fs
import ohd.platform.Env

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
        val pages: HashMap<String, PageEntry> = HashMap(),
        /** 当前页指纹；null 表示未知，必须 dump */
        @Volatile var currentId: String? = null,
        val backStack: ArrayDeque<String> = ArrayDeque(),
    )

    /** 保护 [devices] 与各 [DeviceState] 内部的 pages/backStack/currentId。 */
    private val lock = Mutex()

    private val devices = HashMap<String, DeviceState>()

    fun deviceKey(hdc: Hdc): String = hdc.connectKey?.takeIf { it.isNotBlank() } ?: hdc.kind.label

    /** 只在锁内调用；`getOrPut` 本身不是原子操作 */
    private fun stateLocked(hdc: Hdc): DeviceState =
        devices.getOrPut(deviceKey(hdc)) { DeviceState() }

    /** 取树一律在同一个 withLock 闭包里用 [stateLocked]：invalidate 会摘掉整个 DeviceState，
     *  分两次拿实例会踩到旧对象 */
    private fun currentLocked(hdc: Hdc, maxAgeMs: Long): PageEntry? {
        val st = stateLocked(hdc)
        val id = st.currentId ?: return null
        val e = st.pages[id] ?: return null
        if (maxAgeMs >= 0 && Env.nowMillis() - e.atMs > maxAgeMs) return null
        return e
    }

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
            .toMutableSet()
            // 排序只为让 id 稳定：换成 sorted 列表再入 set，语义等价且跨平台
            .let { it.toList().sorted().toMutableSet() }
        val material = buildString {
            append(flat.size)
            append('#')
            append(labels.size)
            labels.take(48).forEach { append('|').append(it) }
        }
        val id = fs.sha1Hex(material.encodeToByteArray()).take(16)
        val summary = labels.take(6).joinToString(",")
        return id to summary
    }

    /** 当前页树；无 current 或过期 → null */
    fun current(hdc: Hdc, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): PageEntry? =
        lock.withLock { currentLocked(hdc, maxAgeMs) }

    fun get(hdc: Hdc, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): List<LayoutNode>? =
        current(hdc, maxAgeMs)?.flat

    fun pageCount(hdc: Hdc): Int = lock.withLock { stateLocked(hdc).pages.size }

    /** 指纹算在锁外：SHA-1 over 整树标签，不该占着锁 */
    fun put(hdc: Hdc, flat: List<LayoutNode>, pushIfNew: Boolean = false): PageEntry {
        val (id, summary) = fingerprint(flat)
        val entry = PageEntry(id, flat, Env.nowMillis(), summary)
        lock.withLock {
            val st = stateLocked(hdc)
            val prev = st.currentId
            st.pages[id] = entry
            if (pushIfNew && prev != null && prev != id) {
                st.backStack.addLast(prev)
                // 防止栈无限涨
                while (st.backStack.size > 32) st.backStack.removeFirst()
            }
            st.currentId = id
        }
        return entry
    }

    /** 滚动/同页大变：丢掉当前页槽，保留其它页 */
    fun invalidateCurrent(hdc: Hdc) {
        lock.withLock {
            val st = stateLocked(hdc)
            val id = st.currentId ?: return@withLock
            st.pages.remove(id)
            st.currentId = null
        }
    }

    /** 清掉该设备全部页（--fresh） */
    fun invalidate(hdc: Hdc) {
        val key = deviceKey(hdc)
        lock.withLock { devices.remove(key) }
    }

    fun clear() {
        lock.withLock { devices.clear() }
    }

    /**
     * 系统 Back：弹出上一页为 current。栈空则 current=null（下次 dump）。
     * @return 恢复到的 pageId，或 null
     */
    fun noteBack(hdc: Hdc): String? = lock.withLock {
        val st = stateLocked(hdc)
        if (st.backStack.isEmpty()) {
            st.currentId = null
            return@withLock null
        }
        val prev = st.backStack.removeLast()
        st.currentId = if (st.pages.containsKey(prev)) prev else null
        st.currentId
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
            lock.withLock { stateLocked(hdc).currentId = null }
        } else {
            // 查缓存（加锁）→ 出锁 → dump：临界区里绝不做 IO
            lock.withLock { currentLocked(hdc, maxAgeMs) }?.let { return it.flat to "cache:${it.pageId}" }
        }
        // 传 bundle 让 dump 用 `-b` 限定窗口，避免把状态栏/系统手势并进树里
        val flat = DeviceOps.captureLayout(hdc, session, bundle = bundle).flat()
        val e = put(hdc, flat, pushIfNew = pushIfNew)
        return flat to "dump:${e.pageId}"
    }

    const val DEFAULT_MAX_AGE_MS = 300_000L
}
