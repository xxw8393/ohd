package ohd.core

import ohd.core.analysis.CaseFacts
import ohd.platform.fs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * metrics csv 的时间列换过格式（epoch_ms → ISO-8601），
 * 读取端必须两种都能吃，否则历史证据包会突然「分析不出趋势」。
 */
class MetricsCsvTest {
    private fun csv(lines: String) = fs.createTempFile("metrics", ".csv").also {
        fs.writeString(it, lines.trimIndent())
        java.io.File(it.toString()).deleteOnExit()
    }

    @Test
    fun readsIsoTimeColumn() {
        val f = csv(
            """
            index,time,pss_kb,cpu_percent
            1,2026-10-17T15:00:00+08:00,400000,5.0
            2,2026-10-17T15:00:10+08:00,410000,6.0
            3,2026-10-17T15:00:20+08:00,420000,7.0
            """,
        )
        val m = assertNotNull(CaseFacts.readMetrics(f))
        assertEquals(3, m.count)
        assertEquals(20.0, m.spanSec, 0.01)
        assertEquals(400000, m.pssFirst)
        assertEquals(420000, m.pssLast)
    }

    @Test
    fun readsLegacyEpochColumn() {
        val f = csv(
            """
            index,epoch_ms,pss_kb,cpu_percent
            1,1760000000000,400000,5.0
            2,1760000010000,410000,6.0
            """,
        )
        val m = assertNotNull(CaseFacts.readMetrics(f))
        assertEquals(2, m.count)
        assertEquals(10.0, m.spanSec, 0.01)
    }

    @Test
    fun withoutTimeColumnSpanFallsBackToSampleCount() {
        val f = csv(
            """
            index,pss_kb
            1,400000
            2,410000
            3,420000
            """,
        )
        val m = assertNotNull(CaseFacts.readMetrics(f))
        assertEquals(3, m.count)
        // 没有时间列时按样本间隔 1s 估算
        assertEquals(2.0, m.spanSec, 0.01)
    }

    @Test
    fun missingPssColumnReturnsNull() {
        val f = csv(
            """
            index,time,cpu_percent
            1,2026-10-17T15:00:00+08:00,5.0
            """,
        )
        assertEquals(null, CaseFacts.readMetrics(f))
    }
}
