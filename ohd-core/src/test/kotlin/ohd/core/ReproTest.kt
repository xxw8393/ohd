package ohd.core

import ohd.core.caps.ReproCap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 复现判定的核心是「故障名前缀匹配」：
 * `appfreeze-com.tencent.compose-20020221-20261016181424`
 *   → kind=appfreeze, module=com.tencent.compose, uid=20020221, ts=14 位
 * 解析错一位就会把「未复现」判成「复现」。
 */
class ReproTest {
    private val cap = ReproCap()

    @Test
    fun parsesAppfreezeName() {
        val r = cap.parseFaultName("appfreeze-com.tencent.compose-20020221-20261016181424")
        assertEquals("appfreeze", r?.first)
        assertEquals("com.tencent.compose", r?.second)
    }

    @Test
    fun parsesCppcrashName() {
        val r = cap.parseFaultName("cppcrash-com.example.app-100-1234567890")
        assertEquals("cppcrash", r?.first)
        assertEquals("com.example.app", r?.second)
    }

    @Test
    fun moduleWithManyDotsKeptWhole() {
        // module 本身含多个点，不能被截断
        val r = cap.parseFaultName("jscrash-com.example.a.b.c-777-20261016181424")
        assertEquals("com.example.a.b.c", r?.second)
    }

    @Test
    fun rejectsNonFaultLines() {
        // 列表输出里混着分隔线/标题，不能被当成故障
        assertNull(cap.parseFaultName("-------------------[ability]-------------------"))
        assertNull(cap.parseFaultName("Fault log list:"))
        assertNull(cap.parseFaultName(""))
        assertNull(cap.parseFaultName("appfreeze-com.x"), "缺时间戳应当拒绝")
    }

    @Test
    fun trimsWhitespace() {
        val r = cap.parseFaultName("  appfreeze-com.x.y-1-20261016181424  ")
        assertEquals("appfreeze", r?.first)
    }
}
