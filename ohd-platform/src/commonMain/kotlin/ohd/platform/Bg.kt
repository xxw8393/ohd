package ohd.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 后台工作的统一入口：**用协程替代 `Thread`**。
 *
 * ## 为什么必须换
 *
 * K/N 没有 `Thread`（已实测 `Unresolved reference 'Thread'`），而仓库里有
 * 8 处 `Thread { }` 与 7 处 `addShutdownHook(Thread { ... })`。协程是 KMP 上
 * 处理「后台工作 + 生命周期收尾」的标准手段，两端同一套 API。
 *
 * ## 作用域
 *
 * 一个进程级全局作用域，`SupervisorJob` 保证单个任务失败不牵连其它任务。
 * 用 [Dispatchers.Default]：这些任务都是「读管道/等进程」这类 IO 与轻计算，
 * 不占 UI 线程（ohd 本来也没有 UI）。
 *
 * ## 与退出的关系
 *
 * [shutdownBg] 会取消整个作用域并等待收尾。`onExitCleanup` 注册时应调它，
 * 避免「进程退了但后台协程还在写文件」。
 */
object Bg {

    private val job = SupervisorJob()

    val scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default)

    /** 起一个后台任务，返回它的 [Job]。 */
    fun launch(name: String? = null, block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block)

    /** 取消全部后台任务并让它们收尾。幂等。 */
    fun shutdownBg() {
        scope.cancel()
    }

    /** 轮询等待条件成立，最多 [timeoutMs]。给「等进程退出/等文件出现」用。 */
    suspend fun awaitUntil(timeoutMs: Long, pollMs: Long = 20, cond: () -> Boolean): Boolean {
        val deadline = Env.nowMillis() + timeoutMs
        while (Env.nowMillis() < deadline) {
            if (cond()) return true
            delay(pollMs)
        }
        return cond()
    }

    /** 当前作用域是否还在跑（被 [shutdownBg] 取消后为 false）。 */
    fun isActive(): Boolean = job.isActive
}
