package ohd.platform

/**
 * 跨端互斥锁。
 *
 * ## 为什么需要它
 *
 * `synchronized` 是 JVM 关键字，**K/N 没有**（`Unresolved reference 'synchronized'`）。
 * 而仓库里有二十多处 `synchronized(lock)` 保护「读-改-写」的原子性——
 * 那些临界区本身是对的，只是语法不可移植。此类型提供等价语义。
 *
 * ## 为什么是 expect/actual 而不是 commonMain 一份实现
 *
 * `commonMain` 的 `kotlin.concurrent` **没有**原子类型（实测 `AtomicInt` /
 * `AtomicReference` 均 `Unresolved reference`），所以没法在公共层写 CAS。
 * 两端各用平台原生能力：
 * - JVM：`ReentrantLock`（有真正的阻塞队列，不空转）
 * - K/N：`pthread_mutex_t`（同理，由内核调度）
 *
 * 都比手搓自旋好：临界区一旦变长，自旋会白白烧 CPU。
 *
 * ## 可重入性
 *
 * 两端底层都可重入，但**请不要依赖**：可重入会鼓励嵌套加锁，
 * 而那正是锁粒度失控的开始。当前调用点都是平铺的。
 */
expect class Mutex() {
    /** 持锁执行 [block]。 */
    inline fun <T> withLock(block: () -> T): T
}
