package ohd.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.rawPtr
import kotlinx.cinterop.ptr
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_mutexattr_destroy
import platform.posix.pthread_mutexattr_init
import platform.posix.pthread_mutexattr_settype
import platform.posix.PTHREAD_MUTEX_RECURSIVE

/**
 * K/N 实现：`pthread_mutex_t`（见 commonMain 的类文档）。
 *
 * 用 `PTHREAD_MUTEX_RECURSIVE`：与 JVM 的 `ReentrantLock` 保持同一可重入语义，
 * 免得同一段代码在两端行为不同——那是这类跨平台包装最容易埋的坑。
 */
@OptIn(ExperimentalForeignApi::class)
actual class Mutex actual constructor() {

    // 放在 nativeHeap：pthread_mutex_t 需要稳定地址，不能被 GC 移动
    @PublishedApi
    internal val handle = nativeHeap.alloc<pthread_mutex_t>()

    init {
        memScoped {
            val attr = alloc<platform.posix.pthread_mutexattr_t>()
            pthread_mutexattr_init(attr.ptr)
            // PTHREAD_MUTEX_RECURSIVE 在 macOS 是 UInt、Linux 是 Int；
            // convert() 按各平台的目标类型转换，避免两套条件编译
            pthread_mutexattr_settype(attr.ptr, PTHREAD_MUTEX_RECURSIVE.convert())
            pthread_mutex_init(handle.ptr, attr.ptr)
            pthread_mutexattr_destroy(attr.ptr)
        }
    }

    actual inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(handle.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(handle.ptr)
        }
    }

    /** 显式释放。当前用法是进程级单例，不调用也无妨；写出来是为了不留未定义行为。 */
    fun dispose() {
        pthread_mutex_destroy(handle.ptr)
        nativeHeap.free(handle.rawPtr)
    }
}
