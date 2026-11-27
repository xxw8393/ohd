package ohd.core.caps

/**
 * JVM 侧看守进程入口。
 *
 * `spawn` 用 `LogWatchdog::class.java.name` 拼出类名、`-cp` 起一个 JVM 再调 `main`，
 * 所以这里必须保留 `@JvmStatic`。轮询体在 commonMain 的 [LogWatchdog.runGuard]。
 */
object LogWatchdogMain {
    @JvmStatic
    fun main(args: Array<String>) {
        LogWatchdog.runGuard(args.toList())
    }
}
