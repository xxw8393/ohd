package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `OhdPath` 的跨平台语义单测（commonTest 里，JVM 与 K/N 都跑）。
 *
 * 这些用例是双入口一致性的**唯一保证**：JVM 走 `java.nio.file.Path`，K/N 走自写字符串运算，
 * 若两边 `normalize`/`parent`/`relativize` 有分歧，同一命令在两条入口会算出不同路径。
 */
class OhdPathTest {

    @Test
    fun normalizeRemovesDotAndDotDot() {
        assertEquals("a/b", OhdPath.of("a/./b").normalize().toString())
        assertEquals("b", OhdPath.of("a/../b").normalize().toString())
        // 实测 java.nio.file.Path：`a/..` 与 `.` 都归一成**空字符串**，不是 "."
        assertEquals("", OhdPath.of("a/..").normalize().toString())
        assertEquals("", OhdPath.of(".").normalize().toString())
        assertEquals("/b", OhdPath.of("/a/../b").normalize().toString())
    }

    @Test
    fun normalizeAtRootStaysAtRoot() {
        // `/..` 在 JVM 上是 `/`；K/N 实现必须一致
        assertEquals("/", OhdPath.of("/..").normalize().toString())
        assertEquals("/", OhdPath.of("/../..").normalize().toString())
    }

    @Test
    fun relativeLeadingDotDotIsPreserved() {
        // 相对路径无法判定上级，`..` 必须保留（JVM 行为）
        assertEquals("../a", OhdPath.of("../a").normalize().toString())
        assertEquals("../../a", OhdPath.of("../../a").normalize().toString())
    }

    @Test
    fun resolveAppendsChild() {
        assertEquals("/a/b", OhdPath.of("/a").resolve("b").toString())
        // 绝对子路径替换基准（JVM 行为）
        assertEquals("/x", OhdPath.of("/a").resolve("/x").toString())
    }

    @Test
    fun parentAndFileName() {
        val p = OhdPath.of("/a/b/c")
        assertEquals("/a/b", p.parent?.toString())
        assertEquals("c", p.fileName?.toString())
        assertEquals("/", OhdPath.of("/a").parent?.toString())
        assertEquals(null, OhdPath.of("/").fileName)
    }

    @Test
    fun relativizeSimple() {
        assertEquals("b/c", OhdPath.of("/a").relativize(OhdPath.of("/a/b/c")).toString())
        assertEquals("../x", OhdPath.of("/a/b").relativize(OhdPath.of("/a/x")).toString())
    }

    @Test
    fun toPortableStringUsesForwardSlash() {
        assertTrue(OhdPath.of("/a/b").toPortableString().contains("/"))
    }

    @Test
    fun equalityIsNormalized() {
        assertEquals(OhdPath.of("/a/b"), OhdPath.of("/a/./b"))
    }
}
