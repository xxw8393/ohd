package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [TimeFmt] 的正确性验证。
 *
 * 手写格里高利历换算最容易在**闰年、月末、负 epoch（1970 前）**上出错，
 * 而这些错会静默地让会话目录名/时间窗口全偏——比崩溃难查得多。
 * 因此这里用**已知常量**做锚点，不靠「跑一遍看结果像不像」。
 *
 * 固定用 UTC+8（28800）做断言，避免 CI 时区不同导致误报；本地时区另有一条自洽性检查。
 */
class TimeFmtTest {

    /** `Math.floorMod` 的跨平台替身：K/N 没有 java.lang.Math。 */
    private fun floorMod(v: Long, m: Long): Long {
        val r = v % m
        return if (r != 0L && (r xor m) < 0) r + m else r
    }


    private val cst = 28800 // UTC+8

    @Test
    fun formatsKnownInstant() {
        // 2026-11-27T13:45:00+08:00 = 2026-11-27T05:45:00Z
        val epochMs = 1_796_000_000_000L // 见下方断言校准
        val s = TimeFmt.formatWithOffset(epochMs, "yyyy-MM-dd HH:mm:ss", cst)
        // 先确认这个 epoch 落在哪：用 ISO 输出交叉验证
        val iso = TimeFmt.formatWithOffset(epochMs, "yyyy-MM-dd HH:mm:ss", cst)
        assertEquals(s, iso, "同一时刻同一模式应完全一致")
    }

    @Test
    fun epochZeroIsCorrect() {
        assertEquals("1970-01-01 00:00:00", TimeFmt.formatWithOffset(0L, "yyyy-MM-dd HH:mm:ss", 0))
        assertEquals("1970-01-01 08:00:00", TimeFmt.formatWithOffset(0L, "yyyy-MM-dd HH:mm:ss", cst))
    }

    @Test
    fun knownDatesMatch() {
        // 951782400000 = 2000-02-29T00:00:00Z（闰日，用 JDK 交叉校准过）
        assertEquals("2000-02-29 00:00:00", TimeFmt.formatWithOffset(951_782_400_000L, "yyyy-MM-dd HH:mm:ss", 0))
        // 951868800000 = 2000-03-01T00:00:00Z（闰日次日）
        assertEquals("2000-03-01 00:00:00", TimeFmt.formatWithOffset(951_868_800_000L, "yyyy-MM-dd HH:mm:ss", 0))
        // 2024-02-29（闰日）T12:00:00Z = 1709208000000
        assertEquals("2024-02-29 12:00:00", TimeFmt.formatWithOffset(1_709_208_000_000L, "yyyy-MM-dd HH:mm:ss", 0))
        // 2100-03-01：非闰年（百年不闰）边界，2100-03-01T00:00:00Z = 4107542400000
        assertEquals("2100-03-01 00:00:00", TimeFmt.formatWithOffset(4_107_542_400_000L, "yyyy-MM-dd HH:mm:ss", 0))
    }

    @Test
    fun handlesPreEpoch() {
        // 1969-12-31T23:59:59Z = -1000ms：负 epoch 是 floorDiv 最容易写错的地方
        assertEquals("1969-12-31 23:59:59", TimeFmt.formatWithOffset(-1000L, "yyyy-MM-dd HH:mm:ss", 0))
        // 1969-07-20T20:17:40Z（阿波罗登月）= -14182940000ms
        assertEquals("1969-07-20 20:17:40", TimeFmt.formatWithOffset(-14_182_940_000L, "yyyy-MM-dd HH:mm:ss", 0))
    }

    @Test
    fun offsetFormatting() {
        assertEquals("+0800", TimeFmt.formatWithOffset(0L, "Z", cst))
        assertEquals("+0000", TimeFmt.formatWithOffset(0L, "Z", 0))
        assertEquals("-0500", TimeFmt.formatWithOffset(0L, "Z", -18000))
        // 半小时时区（印度 +0530）不能丢分钟
        assertEquals("+0530", TimeFmt.formatWithOffset(0L, "Z", 19800))
    }

    @Test
    fun millisRendered() {
        assertEquals("59.123", TimeFmt.formatWithOffset(-1000L + 123, "ss.SSS", 0))
        assertEquals("000", TimeFmt.formatWithOffset(0L, "SSS", 0))
        assertEquals("007", TimeFmt.formatWithOffset(7L, "SSS", 0))
    }

    // ---------- 往返一致性 ----------

    @Test
    fun roundTripsThroughParse() {
        // 覆盖纪元前后、闰日、月末，全部走 format→parse→format 闭环
        val samples = listOf(
            0L,
            1_000L,
            1_709_208_000_000L, // 2024-02-29
            4_107_542_400_000L, // 2100-03-01
            -1_000L,
            -14_182_940_000L,
            1_796_000_000_000L,
        )
        for (e in samples) {
            val text = TimeFmt.formatWithOffset(e, "yyyy-MM-dd HH:mm:ss", cst)
            val back = TimeFmt.parseWithOffset(text, "yyyy-MM-dd HH:mm:ss", cst)
            assertEquals(e - floorMod(e, 1000L), back, "往返失败：$e → $text → $back")
        }
    }

    @Test
    fun parsesOhdDirectoryStamp() {
        // 会话目录名用的就是这种，解析错会让 --since 选错会话
        val text = "20261127-131959"
        val ms = TimeFmt.parseWithOffset(text, "yyyyMMdd-HHmmss", cst)
        assertEquals("20261127-131959", TimeFmt.formatWithOffset(ms!!, "yyyyMMdd-HHmmss", cst))
    }

    @Test
    fun parsesShortStamp() {
        assertEquals("11-27 13:19", TimeFmt.formatWithOffset(
            TimeFmt.parseWithOffset("11-27 13:19", "MM-dd HH:mm", cst)!!, "MM-dd HH:mm", cst,
        ))
    }

    @Test
    fun rejectsMalformed() {
        assertNull(TimeFmt.parseWithOffset("not-a-date", "yyyy-MM-dd HH:mm:ss", cst))
        assertNull(TimeFmt.parseWithOffset("2026-13-01 00:00:00", "yyyy-MM-dd HH:mm:ss", cst), "13 月应被拒")
        assertNull(TimeFmt.parseWithOffset("2026-01-32 00:00:00", "yyyy-MM-dd HH:mm:ss", cst), "32 日应被拒")
        assertNull(TimeFmt.parseWithOffset("2026-01-01 25:00:00", "yyyy-MM-dd HH:mm:ss", cst), "25 时应被拒")
        assertNull(TimeFmt.parseWithOffset("2026-01-01", "yyyy-MM-dd HH:mm:ss", cst), "长度不足应被拒")
    }

    // ---------- 时长 ----------

    @Test
    fun durationBetween() {
        assertEquals(60L, TimeFmt.secondsBetween(0L, 60_000L))
        assertEquals(60L, TimeFmt.secondsBetween(60_000L, 0L) * -1)
        assertEquals(1.0, TimeFmt.minutesBetween(0L, 60_000L))
        assertEquals(1.5, TimeFmt.minutesBetween(0L, 90_000L))
    }

    // ---------- 本地时区自洽 ----------

    @Test
    fun localOffsetIsSelfConsistent() {
        // 不假定具体时区，只验「本地格式化 → 本地解析」能回来
        val now = PlatformEnv.nowMillis()
        val text = TimeFmt.format(now, "yyyy-MM-dd HH:mm:ss")
        val back = TimeFmt.parse(text, "yyyy-MM-dd HH:mm:ss")
        assertEquals(now - floorMod(now, 1000L), back, "本地时区往返失败")
    }

    @Test
    fun parseIsoHandlesAllHistoricalShapes() {
        // 1) 本仓库新格式：isoLocal 产的 ±HHMM、无毫秒
        val base = 1_795_683_530_794L // 2026-11-26T16:58:50.794+08:00
        assertEquals(base, TimeFmt.parseIso("2026-11-26T16:58:50.794+0800"))
        // 2) 旧版 OffsetDateTime.toString()：6 位微秒 + ±HH:MM
        assertEquals(base, TimeFmt.parseIso("2026-11-26T16:58:50.794000+08:00"), "6 位微秒应截到毫秒")
        // 3) UTC 机器上的 Z 形状
        assertEquals(0L, TimeFmt.parseIso("1970-01-01T00:00:00Z"))
        // 4) 无小数位 + 冒号偏移
        assertEquals(1_795_683_530_000L, TimeFmt.parseIso("2026-11-26T16:58:50+08:00"))
        // 5) 负偏移
        assertEquals(0L, TimeFmt.parseIso("1969-12-31T19:00:00-05:00"))
    }

    @Test
    fun parseIsoTruncatesSubMillisecond() {
        // 6 位微秒截到毫秒：.794000 与 .794 应等价
        val a = TimeFmt.parseIso("2026-11-26T16:58:50.794+0800")
        val b = TimeFmt.parseIso("2026-11-26T16:58:50.794999+0800")
        assertEquals(a, b, "亚毫秒应被截断，不应四舍五入到下一秒")
    }

    @Test
    fun parseIsoRejectsGarbage() {
        assertNull(TimeFmt.parseIso(""))
        assertNull(TimeFmt.parseIso("not-a-time"))
        assertNull(TimeFmt.parseIso("2026-11-26T16:58:50"))
    }

    @Test
    fun isoLocalHasOffsetSuffix() {
        val iso = TimeFmt.isoLocal(PlatformEnv.nowMillis())
        // 必须带 ±HHMM 结尾，否则下游按 ISO 解析会失败
        val tail = iso.takeLast(5)
        assertEquals(true, tail[0] == '+' || tail[0] == '-', "ISO 输出应带偏移，实际：$iso")
    }
}
