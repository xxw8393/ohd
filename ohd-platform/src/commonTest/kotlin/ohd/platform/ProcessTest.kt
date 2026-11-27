package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 子进程执行的跨平台单测。
 *
 * 这里的用例必须**两端都能过**：JVM 走 `ProcessBuilder`，K/N 走
 * `fork`+`execvp`+`poll`+`waitpid`。任何一条在某一端失败，都说明双入口行为漂移。
 *
 * 只用 `sh`/`echo` 这类 POSIX 必备命令，不依赖 hdc（CI 上可能没有设备侧工具）。
 */
class ProcessTest {

    @Test
    fun capturesStdoutAndExitCode() {
        val r = runProcess(ProcSpec(listOf("sh", "-c", "echo hello"), timeoutMillis = 10_000))
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", r.stdout)
        assertTrue(r.spawnError == null)
    }

    @Test
    fun separatesStderrFromStdout() {
        val r = runProcess(ProcSpec(listOf("sh", "-c", "echo out; echo err 1>&2"), timeoutMillis = 10_000))
        assertEquals("out\n", r.stdout)
        assertEquals("err\n", r.stderr)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun propagatesNonZeroExitCode() {
        val r = runProcess(ProcSpec(listOf("sh", "-c", "exit 42"), timeoutMillis = 10_000))
        assertEquals(42, r.exitCode)
    }

    @Test
    fun feedsStdinThroughBidirectionalPipe() {
        // 双向管道：若 K/N 侧没用 poll 复用，这里会因管道写满而死锁
        val r = runProcess(
            ProcSpec(listOf("cat"), stdinData = "hello\nworld\n".encodeToByteArray(), timeoutMillis = 10_000),
        )
        assertEquals("hello\nworld\n", r.stdout)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun killsOnTimeout() {
        val r = runProcess(ProcSpec(listOf("sh", "-c", "sleep 30"), timeoutMillis = 300))
        assertTrue(r.timedOut, "应当超时返回")
        // 超时后按 shell 惯例报 128+SIGKILL(9)
        assertEquals(137, r.exitCode)
        assertTrue(r.durationMillis < 5_000, "超时后必须尽快返回，实际 ${r.durationMillis}ms")
    }

    @Test
    fun handlesLargeOutputWithoutDeadlock() {
        // 1.2MB 输出：验证 poll 循环与缓冲扩容（单线程顺序读会死锁）
        val r = runProcess(ProcSpec(listOf("sh", "-c", "seq 1 200000"), timeoutMillis = 20_000))
        assertEquals(0, r.exitCode)
        assertEquals(200000, r.stdout.count { it == '\n' })
    }

    @Test
    fun reportsSpawnFailureWithoutThrowing() {
        // 二进制不存在：JVM 抛 IOException → 归一到 spawnError；K/N 用 exec 状态管道补偿
        val r = runProcess(ProcSpec(listOf("definitely-not-a-real-binary-xyz"), timeoutMillis = 5_000))
        assertTrue(r.spawnError != null, "应当报 spawnError 而不是静默返回 127，实际 exit=${r.exitCode}")
    }
}
