package ohd.platform

/**
 * 宿主文件路径。
 *
 * ## 为什么不是 `actual typealias` 到 `java.nio.file.Path`
 *
 * 最初想用 `expect interface OhdPath` + `actual typealias OhdPath = java.nio.file.Path`，
 * 让 400+ 处 `path.resolve(...)` 零改动。**实测不可行**（Kotlin 2.2.20），两个硬障碍：
 *
 * 1. `expect class` → `actual typealias` 到 interface：报 `the class kinds are different`；
 * 2. 改成 `expect interface` 后，成员 modality 对不上：
 *    `expect: public abstract fun resolve(s: String): OhdPath`
 *    `actual: public open fun resolve(p0: String!): Path!`
 *    → `the modalities are different ('abstract' vs 'open')`。
 *    且 `Path.getFileName()/getParent()` 是 Java getter，Kotlin 侧必须写成 `val` 才能匹配，
 *    可 `Path` 的 `resolve` 又是 `open`（Java default-ish），expect 侧无论怎么写都对不齐。
 *
 * 结论：**Kotlin 的 expect/actual 机制无法桥接任意 Java 接口**。所以采用包装类，
 * 代价是 130 处 `: Path` 类型标注 + 25 处 `: Path?` 需要改成 `OhdPath`——这是机械替换，
 * 但**不是零成本**，P1 的工作量估算据此上调。
 *
 * ## 设计取舍
 *
 * 包装类（`class OhdPath(val raw: String)`）而非 `val raw: Path`：
 * 若内嵌 `Path`，K/N 侧就要重写一遍 `Path` 语义且 JVM 侧多一层对象；
 * 用 `String` 作为唯一真相，两端各自实现路径运算，JVM 侧按需 `toJavaPath()` 转换。
 * 路径运算是纯字符串处理（拼接/规范化/取父），两端行为可完全对齐且可测。
 */
class OhdPath private constructor(private val raw: String) : Comparable<OhdPath> {

    /** 路径的字符串形式（宿主原生分隔符）。 */
    override fun toString(): String = raw

    /**
     * 按字符串比较。
     *
     * 让 `.sorted()` / `.maxOrNull()` 这类标准库算子能直接用：JVM 的 `Path` 实现了
     * `Comparable`，迁移到 `OhdPath` 后若不给，几十处 `.sorted()` 会一起失效。
     * 不做「按路径段比较」——调用点全部用于「同目录兄弟项排序」，字符串序即可。
     */
    override fun compareTo(other: OhdPath): Int = raw.compareTo(other.raw)

    fun toAbsolutePath(): OhdPath =
        if (isAbsolute()) normalize() else normalizeOf(PlatformFs.cwd() + PlatformFs.sep + raw)

    val parent: OhdPath?
        get() {
            val n = normalizedString()
            val cut = n.lastIndexOf(PlatformFs.sep)
            return when {
                cut < 0 -> null
                cut == 0 -> if (n.length > 1) OhdPath(PlatformFs.sep) else null
                // Windows 盘符根 `C:\a` 的父级是 `C:\` 而不是 `C:`：
                // `C:` 是「当前盘当前目录」这个相对概念，拿去 resolve 会跑偏。
                // 只有 `C:\` 这种「盘符 + 一个分隔符」才是自身（根不能再上跳）。
                cut == 2 && PlatformFs.sep == "\\" && n[1] == ':' ->
                    if (n.length > 3) OhdPath(n.substring(0, 3)) else null
                else -> OhdPath(n.substring(0, cut))
            }
        }

    val fileName: OhdPath?
        get() {
            val n = normalizedString()
            if (n == PlatformFs.sep || n.isEmpty()) return null
            val cut = n.lastIndexOf(PlatformFs.sep)
            return OhdPath(if (cut < 0) n else n.substring(cut + 1)).takeIf { it.raw.isNotEmpty() }
        }

    fun normalize(): OhdPath = OhdPath(normalizedString())

    fun resolve(child: String): OhdPath =
        if (PlatformFs.isAbsoluteString(child)) OhdPath(child).normalize()
        else OhdPath(joinRaw(raw, child)).normalize()

    fun resolve(child: OhdPath): OhdPath = resolve(child.raw)

    fun resolveSibling(other: String): OhdPath =
        (parent ?: OhdPath(".")).resolve(other)

    fun isAbsolute(): Boolean = PlatformFs.isAbsoluteString(raw)

    fun relativize(other: OhdPath): OhdPath {
        val from = toAbsolutePath().normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        val to = other.toAbsolutePath().normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        val common = from.zip(to).takeWhile { (a, b) -> a == b }.size
        val ups = List(from.size - common) { ".." }
        val downs = to.drop(common)
        return OhdPath((ups + downs).joinToString(PlatformFs.sep).ifEmpty { "." })
    }

    /** 等价 `Path.startsWith`：按路径段比较，不是字符串前缀。 */
    fun startsWith(other: OhdPath): Boolean {
        val a = toAbsolutePath().normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        val b = other.toAbsolutePath().normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        return a.size >= b.size && a.take(b.size) == b
    }

    fun endsWith(other: OhdPath): Boolean {
        val a = normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        val b = other.normalizedString().split(PlatformFs.sep).filter { it.isNotEmpty() }
        return a.size >= b.size && a.takeLast(b.size) == b
    }

    override fun equals(other: Any?): Boolean = other is OhdPath && normalizedString() == other.normalizedString()
    override fun hashCode(): Int = normalizedString().hashCode()

    private fun normalizedString(): String = PlatformFs.normalizeString(raw)

    private fun joinRaw(base: String, child: String): String =
        if (base.endsWith(PlatformFs.sep)) base + child else base + PlatformFs.sep + child

    companion object {
        fun of(raw: String): OhdPath = OhdPath(raw)

        /** 多段拼接，等价 `Paths.get(first, more...)`。 */
        fun of(first: String, vararg more: String): OhdPath =
            more.fold(OhdPath(first)) { acc, seg -> acc.resolve(seg) }

        private fun normalizeOf(s: String): OhdPath = OhdPath(s).normalize()
    }
}

/** 跨平台日志/JSON 输出用：统一 `/`，避免同一路径在 mac 与 Windows 上不可比对。 */
fun OhdPath.toPortableString(): String = toString().replace('\\', '/')

/**
 * [child] 相对 [root] 的层数。[root] 自身为 0，直接子项为 1。
 *
 * 给 `Fs.walk(p, maxDepth)` 的默认实现用。不数分隔符、不做字符串前缀比较：
 * 直接复用 [OhdPath.relativize]，`..` 的个数就是上跳层数，尾斜杠与多分隔符都不会算歪。
 */
internal fun depthUnder(root: OhdPath, child: OhdPath): Int {
    val rel = root.relativize(child).toString()
    if (rel.isEmpty() || rel == ".") return 0
    val parts = rel.split('/', '\\').filter { it.isNotEmpty() }
    // 以 `..` 开头说明不在 root 之下：当成无限深，调用方一律滤掉。
    if (parts.firstOrNull() == "..") return Int.MAX_VALUE
    return parts.size
}
