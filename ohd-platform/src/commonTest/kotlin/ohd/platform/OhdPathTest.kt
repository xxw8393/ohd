package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `OhdPath` 的跨平台语义单测（commonTest 里，JVM 与 K/N 都跑）。
 *
 * 这些用例是双入口一致性的**唯一保证**：JVM 走 `java.nio.file.Path`，K/N 走自写字符串运算，
 * 若两边 `normalize`/`parent`/`relativize` 有分歧，同一命令在两条入口会算出不同路径。
 *
 * ## 为什么期望值要按 `PlatformFs.sep` 拼
 *
 * JVM target 也会在 **Windows** 上跑（CI 有 windows runner，产品本身也出 Windows 的 JVM 包），
 * 那里 `PlatformFs.sep == "\\"`、`OhdPath.toString()` 用反斜杠，根是 `C:\` 而非 `/`。
 * 原先写死 `"a/b"`、`"/"` 的断言在 Windows 上必然失败——那是把「测试只在 POSIX 跑得通」
 * 当成了「实现正确」。根路径直接用 `root` 表达，不让 `/` 泄漏进断言。
 */
class OhdPathTest {

    private val sep = PlatformFs.sep
    private val root = if (Env.isWindows) "C:\\" else "/"

    private fun p(vararg parts: String): String = parts.joinToString(sep)

    @Test
    fun normalizeRemovesDotAndDotDot() {
        assertEquals(p("a", "b"), OhdPath.of(p("a", ".", "b")).normalize().toString())
        assertEquals("b", OhdPath.of(p("a", "..", "b")).normalize().toString())
        // JVM 上 `a/..` 与 `.` 都归一成**空字符串**，不是 "."
        assertEquals("", OhdPath.of(p("a", "..")).normalize().toString())
        assertEquals("", OhdPath.of(".").normalize().toString())
        assertEquals(root + "b", OhdPath.of(root + p("a", "..", "b")).normalize().toString())
    }

    @Test
    fun normalizeAtRootStaysAtRoot() {
        // 根之上再上跳仍停在根；K/N 实现必须与 JVM 一致
        assertEquals(root, OhdPath.of(root + "..").normalize().toString())
        assertEquals(root, OhdPath.of(root + p("..", "..")).normalize().toString())
    }

    @Test
    fun relativeLeadingDotDotIsPreserved() {
        // 相对路径无法判定上级，`..` 必须保留
        assertEquals(p("..", "a"), OhdPath.of(p("..", "a")).normalize().toString())
        assertEquals(p("..", "..", "a"), OhdPath.of(p("..", "..", "a")).normalize().toString())
    }

    @Test
    fun resolveAppendsChild() {
        assertEquals(root + p("a", "b"), OhdPath.of(root + "a").resolve("b").toString())
        // 绝对子路径替换基准
        assertEquals(root + "x", OhdPath.of(root + "a").resolve(root + "x").toString())
    }

    @Test
    fun parentAndFileName() {
        val q = OhdPath.of(root + p("a", "b", "c"))
        assertEquals(root + p("a", "b"), q.parent?.toString())
        assertEquals("c", q.fileName?.toString())
        assertEquals(root, OhdPath.of(root + "a").parent?.toString())
        assertEquals(null, OhdPath.of(root).fileName)
    }

    @Test
    fun relativizeSimple() {
        assertEquals(p("b", "c"), OhdPath.of(root + "a").relativize(OhdPath.of(root + p("a", "b", "c"))).toString())
        assertEquals(p("..", "x"), OhdPath.of(root + p("a", "b")).relativize(OhdPath.of(root + p("a", "x"))).toString())
    }

    @Test
    fun toPortableStringUsesForwardSlash() {
        // 跨平台日志/JSON 用：无论宿主是什么，输出统一成 `/`
        assertEquals("a/b", OhdPath.of(p("a", "b")).toPortableString())
        assertEquals("a/b/c", OhdPath.of(p("a", "b", "c")).toPortableString())
        assertTrue(!OhdPath.of(p("a", "b")).toPortableString().contains('\\'))
    }

    @Test
    fun equalityIsNormalized() {
        assertEquals(OhdPath.of(p("a", "b")), OhdPath.of(p("a", ".", "b")))
    }
}
