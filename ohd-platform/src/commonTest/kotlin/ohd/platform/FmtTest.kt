package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Fmt` 的正确性验证。
 *
 * 手写 printf 最容易错的四处：**负数的零填充位置**、**半数舍入**、
 * **精度不足时补零**、**科学计数法泄漏**。这些错会静默改掉给人看的报告数字，
 * 所以用 JDK 的 `String.format` 作为锚点逐条对账（仅 jvmTest 能做，见交叉校验测试）。
 */
class FmtTest {

    @Test
    fun integersAndStrings() {
        assertEquals("42", fmt("%d", 42))
        assertEquals("007", fmt("%03d", 7))
        assertEquals("-07", fmt("%03d", -7), "宽度含符号位：%03d 对 -7 输出 -07（与 JDK 一致）")
        assertEquals("abc", fmt("%s", "abc"))
        assertEquals("null", fmt("%s", null))
    }

    @Test
    fun hexWithWidth() {
        assertEquals("ff", fmt("%02x", 255))
        assertEquals("0a", fmt("%02x", 10))
        assertEquals("a", fmt("%x", 10))
    }

    @Test
    fun fixedPrecision() {
        assertEquals("1.5", fmt("%.1f", 1.45))
        assertEquals("1.5", fmt("%.1f", 1.5))
        assertEquals("2.0", fmt("%.1f", 1.96))
        assertEquals("100.0", fmt("%.1f", 100.0))
        assertEquals("0.0", fmt("%.1f", 0.0))
        assertEquals("-1.5", fmt("%.1f", -1.45))
        assertEquals("1.00", fmt("%.2f", 1.0))
        assertEquals("0.125", fmt("%.3f", 0.125))
    }

    @Test
    fun noScientificNotation() {
        // 极小值绝不能输出 1.0E-4 这种科学计数法——那是给人看的报告
        assertEquals("0.0001", fmt("%.4f", 0.0001))
        assertEquals("1.0", fmt("%.1f", 1.0))
    }

    @Test
    fun percentAndLiteral() {
        assertEquals("50.0%", fmt("%.1f%%", 50.0))
        assertEquals("100%", fmt("%d%%", 100))
    }

    @Test
    fun multipleArgsInOrder() {
        assertEquals("a=1 b=2.50", fmt("a=%d b=%.2f", 1, 2.5))
    }
}
