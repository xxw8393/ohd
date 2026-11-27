package ohd.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 用 **JDK `String.format` 当锚点**校验 `Fmt`。
 *
 * 为什么必须有这条：`Fmt` 替换的是大量给人看的报告数字。若某个舍入边界
 * 与 JDK 差一位，`ohd` 的输出会静默改变，而且没有任何测试会失败——
 * 除非有人拿真值来比。这里就用 JDK 做那个真值。
 *
 * 只能放 jvmTest：`String.format` 是 JDK 独占，正是它作为裁判的价值。
 */
class FmtCrossCheckTest {

    private fun check(pattern: String, vararg args: Any?) {
        assertEquals(
            String.format(pattern, *args),
            fmt(pattern, *args),
            "pattern=$pattern args=${args.toList()} 与 JDK 不一致",
        )
    }

    @Test
    fun matchesJdkOnIntegers() {
        for (v in listOf(0, 1, 7, -7, 42, -42, 999, -999)) check("%d", v)
        for (v in listOf(0, 7, -7, 42)) check("%03d", v)
    }

    @Test
    fun matchesJdkOnFixedPrecision() {
        val values = listOf(
            0.0, 1.0, 1.5, 1.45, 1.95, 1.96, -1.45, 100.0, 0.0001, 1234.5678,
            0.05, 0.15, 0.25, 2.675, 1.005,
        )
        for (p in listOf("%.0f", "%.1f", "%.2f", "%.3f", "%.4f")) {
            for (v in values) check(p, v)
        }
    }

    @Test
    fun matchesJdkOnPercentAndHex() {
        for (v in listOf(0.0, 50.0, 99.9, 100.0)) check("%.1f%%", v)
        for (v in listOf(0, 1, 10, 255, 4095)) {
            check("%02x", v)
            check("%x", v)
        }
    }

    @Test
    fun matchesJdkOnStringsAndMixed() {
        check("%s", "abc")
        check("%s/%d", "x", 3)
        check("a=%d b=%.2f c=%s", 1, 2.5, "z")
    }
}
