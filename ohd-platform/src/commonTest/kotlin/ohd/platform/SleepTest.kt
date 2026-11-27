package ohd.platform

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `Env.sleepMillis` 的跨端验证。
 *
 * 守两件事：① 真的睡了接近请求的时长（K/N 的 nanosleep 循环续睡写错会静默变短）；
 * ② 0/负数不卡住。第二条不是废话——调用点常传算出来的 0。
 */
class SleepTest {

    @Test
    fun sleepsApproximatelyRequested() {
        val t0 = PlatformEnv.monotonicNanos()
        Env.sleepMillis(60)
        val elapsedMs = (PlatformEnv.monotonicNanos() - t0) / 1_000_000
        // 放宽到 40ms 下界：避免 CI 计时抖动误报；上界 2000ms 抓「睡过头/死循环」
        assertTrue(elapsedMs >= 40, "只睡了 ${elapsedMs}ms，应约 60ms——EINTR 续睡可能写错")
        assertTrue(elapsedMs < 2000, "睡了 ${elapsedMs}ms，远超请求值")
    }

    @Test
    fun nonPositiveReturnsImmediately() {
        val t0 = PlatformEnv.monotonicNanos()
        Env.sleepMillis(0)
        Env.sleepMillis(-100)
        val elapsedMs = (PlatformEnv.monotonicNanos() - t0) / 1_000_000
        assertTrue(elapsedMs < 500, "0/负数不应阻塞，实际 ${elapsedMs}ms")
    }
}

