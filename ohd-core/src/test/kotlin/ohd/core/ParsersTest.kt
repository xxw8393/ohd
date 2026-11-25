package ohd.core

import ohd.core.caps.CpuCap
import ohd.core.parsers.CpuParser
import ohd.core.parsers.HilogParser
import ohd.core.parsers.LayoutParser
import ohd.core.parsers.LayoutQuery
import ohd.core.parsers.MemParser
import ohd.core.parsers.Rect
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    Paths.get("src/test/resources/fixtures/$name").toFile().readText()

class LayoutParserTest {

    @Test
    fun parsesRealDumpLayout() {
        val roots = LayoutParser.parse(fixture("layout-desktop.json"))
        val flat = roots.flatMap { it.flatten() }
        assertTrue(flat.size > 50, "真实布局节点数偏少：${flat.size}")
        assertTrue(flat.any { !it.text.isNullOrBlank() }, "未解析出任何文本节点")
        assertTrue(flat.any { it.clickable }, "未解析出可点节点")
        // 根节点覆盖全屏
        val root = roots.first()
        assertNotNull(root.bounds)
        assertEquals(0, root.bounds!!.left)
        assertEquals(0, root.bounds!!.top)
    }

    @Test
    fun rectParseAndCenter() {
        val r = Rect.parse("[10,20][110,220]")!!
        assertEquals(100, r.width)
        assertEquals(200, r.height)
        assertEquals(60, r.centerX)
        assertEquals(120, r.centerY)
        assertEquals("60,120", r.center)
        assertTrue(r.contains(60, 120))
    }

    @Test
    fun rectRejectsInvalid() {
        assertEquals(null, Rect.parse(null))
        assertEquals(null, Rect.parse(""))
        assertEquals(null, Rect.parse("[0,0][-1,-1]null"))
    }

    @Test
    fun queryFiltersByTextAndClickable() {
        val flat = LayoutParser.parse(fixture("layout-desktop.json")).flatMap { it.flatten() }
        val hits = LayoutQuery.filter(flat, text = "10", clickableOnly = true)
        assertTrue(hits.all { it.clickable })
        val byType = LayoutQuery.filter(flat, type = "Stack")
        assertTrue(byType.isNotEmpty())
        assertTrue(byType.all { it.type.equals("Stack", ignoreCase = true) })
    }
}

class MemParserTest {

    @Test
    fun parsesCategoryTableWithTotal() {
        val report = MemParser.parse(fixture("mem-sceneboard.txt"))
        assertNotNull(report.total, "未解析到 Total 行")
        assertTrue(report.pssKb > 0, "Pss 合计应大于 0：${report.pssKb}")
        assertTrue(report.rows.isNotEmpty())
        // 分类名含空格的情况必须完整保留
        assertTrue(
            report.rows.any { it.name.contains(" ") },
            "应存在含空格的分类名（如 ark ts heap）：${report.rows.map { it.name }.take(20)}",
        )
    }

    @Test
    fun glAndGraphExtracted() {
        val report = MemParser.parse(fixture("mem-sceneboard.txt"))
        assertTrue(report.rows.any { it.name.equals("GL", true) }, "缺 GL 行")
        assertTrue(report.rows.any { it.name.equals("Graph", true) }, "缺 Graph 行")
    }

    @Test
    fun categoriesOverlapSoTotalIsLessThanNaiveSum() {
        // 实测：jemalloc heap/meta 与 native heap 有重叠，Total(422621) < 分类和(558785)
        val report = MemParser.parse(fixture("mem-sceneboard.txt"))
        val sum = report.rows.sumOf { it.pssKb }
        val total = report.total!!.pssKb
        assertTrue(total < sum, "Total($total) 应小于分类和($sum)：分类间存在重叠")
        assertTrue(total > sum / 2, "Total($total) 与分类和($sum) 差距过大，解析可能有误")
        assertTrue(report.rows.any { it.name.startsWith("jemalloc") }, "应解析出 jemalloc 分类")
        assertEquals(total, report.pssKb, "pssKb 必须优先取 Total，不能用分类和")
    }

    @Test
    fun parseSwapPssCompatLine() {
        assertEquals(12345L, MemParser.parseSwapPssLine("Pss(12345 in SwapPss) 678"))
        assertEquals(null, MemParser.parseSwapPssLine("no match"))
    }
}

class CpuParserTest {

    @Test
    fun parsesUsageAndProcesses() {
        val report = CpuParser.parse(fixture("cpuusage.txt"))
        val usage = assertNotNull(report.usage, "未解析到 Total 行")
        assertTrue(usage.total > 0.0, "总占用应为正：${usage.total}")
        assertTrue(usage.load1 > 0.0, "load1 应为正：${usage.load1}")
        assertTrue(report.processes.isNotEmpty(), "未解析到进程行")
        assertTrue(report.processes.first().totalPercent >= report.processes.last().totalPercent, "进程应按占用降序")
    }

    @Test
    fun cpufreqPairsParsed() {
        val cores = CpuCap().parseCpufreq(fixture("cpufreq.txt"))
        assertTrue(cores.isNotEmpty(), "未解析到任何核")
        assertTrue(cores.all { it.second.first > 0 }, "当前频率应为正")
        assertTrue(cores.all { it.second.second >= it.second.first }, "最大频率应不低于当前频率")
    }
}

class HilogParserTest {

    @Test
    fun parsesRealHilogLines() {
        val lines = fixture("hilog.txt").lines()
        val parsed = HilogParser.parseAll(lines.asSequence())
        assertTrue(parsed.isNotEmpty(), "未解析出任何 hilog 行")
        assertTrue(parsed.all { it.level in "VDIWEF" }, "级别非法")
        assertTrue(parsed.all { it.tagShort.isNotEmpty() }, "tag 为空")
        assertTrue(parsed.any { it.pid > 0 })
    }

    @Test
    fun histogramAndLevelFilter() {
        val lines = fixture("hilog.txt").lines().asSequence()
        val hist = HilogParser.histogram(lines)
        assertTrue(hist.isNotEmpty())
        assertEquals(hist.values.sum(), HilogParser.parseAll(fixture("hilog.txt").lines().asSequence()).size)
        val errors = HilogParser.atLeast(fixture("hilog.txt").lines().asSequence(), 'E')
        assertTrue(errors.size <= lines.count())
    }

    @Test
    fun rejectsGarbageLine() {
        assertEquals(null, HilogParser.parse("not a hilog line"))
    }
}

class DurationAndStateTest {

    @Test
    fun durationParsing() {
        assertEquals(500L, parseDurationMs("500ms"))
        assertEquals(2_000L, parseDurationMs("2s"))
        assertEquals(5_000L, parseDurationMs("5"))
        assertEquals(300_000L, parseDurationMs("5m"))
        assertEquals(3_600_000L, parseDurationMs("1h"))
    }

    @Test
    fun stateFileRoundTrip() {
        val tmp = java.nio.file.Files.createTempDirectory("ohd-state")
        try {
            val f = tmp.resolve("log-state.json")
            java.nio.file.Files.writeString(f, """{"pid":4242,"file":"/tmp/a.txt","device":"DEV"}""")
            val state = readJsonFile(f)
            assertNotNull(state)
            assertEquals(4242L, state.long("pid"))
            assertEquals("/tmp/a.txt", state.str("file"))
            assertEquals(null, readJsonFile(tmp.resolve("nope.json")))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun watchSummarySlope() {
        // 线性增长 100 KB/秒
        val base = System.currentTimeMillis()
        val samples = (0 until 10).map {
            ohd.core.caps.Sample(it, base + it * 1000L, 10_000L + it * 100L, 0, 0, null)
        }
        val sum = ohd.core.caps.WatchCap().summarize(samples, 1024)
        assertEquals(100.0, sum.slopePerSec, 0.001)
        assertEquals(900L, sum.delta)
        assertEquals(0, sum.steps)
    }
}
