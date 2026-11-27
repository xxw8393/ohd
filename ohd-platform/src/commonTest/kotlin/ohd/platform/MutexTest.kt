package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Mutex] 的行为验证。
 *
 * 只能测**串行语义**（重入、异常释放）：commonTest 没有跨平台建线程的能力
 * （K/N 无 `Thread`），所以真并发只能靠 JVM 侧的 jvmTest 补。
 * 这一条在两端都跑，至少保证实现没把「锁住」写成「没锁」。
 */
class MutexTest {

    @Test
    fun mutualExclusionSerial() {
        val m = Mutex()
        var n = 0
        repeat(1000) { m.withLock { n++ } }
        assertEquals(1000, n)
    }

    @Test
    fun releasesOnException() {
        val m = Mutex()
        try {
            m.withLock<Unit> { error("boom") }
        } catch (_: IllegalStateException) {
        }
        // 异常后必须已释放，否则这里会挂死
        var ok = false
        m.withLock { ok = true }
        assertEquals(true, ok, "异常路径未释放锁")
    }

    @Test
    fun nestedSameThreadDoesNotDeadlock() {
        // 两端底层都可重入（ReentrantLock / PTHREAD_MUTEX_RECURSIVE）。
        // 这条**不是为了鼓励嵌套**，而是确认实现选型一致——
        // 若哪端退化成不可重入，这里会挂死并暴露出来。
        val m = Mutex()
        var depth = 0
        m.withLock {
            m.withLock {
                depth = 2
            }
        }
        assertEquals(2, depth)
    }
}
