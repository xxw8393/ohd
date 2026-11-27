package ohd.platform

import java.util.concurrent.locks.ReentrantLock

/** JVM 实现：用 `ReentrantLock`，阻塞等待而非自旋（见 commonMain 的类文档）。 */
actual class Mutex actual constructor() {

    @PublishedApi
    internal val lock = ReentrantLock()

    actual inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
