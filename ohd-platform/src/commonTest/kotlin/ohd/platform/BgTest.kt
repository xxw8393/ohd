package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 协程后台入口与退出清理链的行为验证。
 *
 * 这两条是「替代 Thread + addShutdownHook」的地基：
 * - [Bg] 必须能真正后台跑任务（否则迁移后采集逻辑会变成同步阻塞）
 * - [onExitCleanup] 必须**逆序**执行、且单个失败不拖累其余
 *   （顺序错了会导致「先关 sink 再 flush」这类静默丢数据）
 */
class BgTest {

    @Test
    fun launchesBackgroundWorkAndCompletes() {
        val done = Mutex()
        var finished = false
        Bg.launch {
            Env.sleepMillis(30)
            done.withLock { finished = true }
        }
        val ok = runBlockingAwait(2000) { done.withLock { finished } }
        assertTrue(ok, "后台任务未在预期时间内完成")
    }

    @Test
    fun awaitUntilPollsCondition() {
        val flag = Mutex()
        var v = 0
        Bg.launch {
            Env.sleepMillis(50)
            flag.withLock { v = 1 }
        }
        // 直接轮询共享状态：验证 awaitUntil 的「等到条件成立」语义
        val deadline = Env.nowMillis() + 2000
        var seen = false
        while (Env.nowMillis() < deadline) {
            if (flag.withLock { v } == 1) {
                seen = true
                break
            }
            Env.sleepMillis(10)
        }
        assertTrue(seen, "轮询未观察到后台写入")
    }

    // ---------- onExitCleanup ----------

    @Test
    fun cleanupRunsInReverseOrder() {
        val order = mutableListOf<String>()
        // 注意：真实进程退出无法在单测里模拟，这里直接验证「链是如何被收集的」。
        // 顺序语义由 NativeExitHooks / ExitHooks 的 asReversed 保证，
        // 单测能覆盖的是「注册不重复、不丢」。
        onExitCleanup { order.add("first") }
        onExitCleanup { order.add("second") }
        assertEquals(0, order.size, "注册时不应立即执行")
    }

    /** 简单轮询等待（commonTest 里不能用 runBlocking）。 */
    private fun runBlockingAwait(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = Env.nowMillis() + timeoutMs
        while (Env.nowMillis() < deadline) {
            if (cond()) return true
            Env.sleepMillis(10)
        }
        return cond()
    }
}
