package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `spawnStreaming` 的压力验证：不能只测单行 echo。
 *
 * 泵线程最容易在这三处出错：
 * ① 跨 64KB 缓冲边界（一行被 read 切成两段）→ 拼行逻辑必须正确
 * ② 大量行（缓冲与 StringBuilder 复用）→ 不能丢行、不能重复
 * ③ 退出时无换行尾巴 → 最后一行也要吐出来
 */
class StreamStressTest {

    private fun collect(cmd: List<String>, waitMs: Int = 8000): List<String> {
        // 泵线程写、测试线程读：用 Mutex 保护，不依赖 JDK 的 synchronizedList
        val guard = Mutex()
        val got = mutableListOf<String>()
        val p = spawnStreaming(cmd, onLine = { line -> guard.withLock { got.add(line) } })
        if (!p.ok) return listOf("__SPAWN_FAILED__:${p.spawnError}")
        var waited = 0
        while (waited < waitMs) {
            Env.sleepMillis(50)
            waited += 50
            // 进程结束后泵线程还要一点时间把尾巴吐完，多等一小会儿
            if (waited > 500) {
                val before = guard.withLock { got.size }
                if (before > 0) {
                    Env.sleepMillis(150)
                    if (guard.withLock { got.size } == before) break
                }
            }
        }
        PlatformEnv.killPid(p.pid ?: 0L)
        return guard.withLock { got.toList() }
    }

    @Test
    fun manyLinesAllArriveInOrder() {
        // seq 1..500：丢行/乱序都会被抓到
        val lines = collect(TestCommand.sequence(500))
        assertEquals(500, lines.size, "应收到 500 行，实际 ${lines.size}；首尾=${lines.firstOrNull()}..${lines.lastOrNull()}")
        assertEquals("1", lines.first())
        assertEquals("500", lines.last())
    }

    @Test
    fun longLineCrossingBufferBoundary() {
        // 造一行远大于 64KB 缓冲的输出：必须被正确拼接成一行，而不是碎成多行
        val len = 200_000
        val lines = collect(TestCommand.longLine(len))
        assertEquals(1, lines.size, "超长行应拼成 1 行，实际 ${lines.size} 行")
        assertEquals(len, lines[0].length, "超长行长度应为 $len")
    }

    @Test
    fun trailingLineWithoutNewlineIsFlushed() {
        // printf 不带换行：退出时最后一行也必须吐出来
        val lines = collect(TestCommand.noNewlineTail("no-newline-tail"))
        assertEquals(listOf("no-newline-tail"), lines, "无换行尾巴应被 flush")
    }

    @Test
    fun stderrIsMergedIntoStream() {
        // hilog 的诊断信息走 stderr，丢了会漏行
        val lines = collect(TestCommand.stdoutAndStderr("out-line", "err-line"))
        assertTrue(lines.contains("out-line"), "应收到 stdout 行：$lines")
        assertTrue(lines.contains("err-line"), "stderr 应并入，实际：$lines")
    }

    @Test
    fun blankLinesAreSkipped() {
        val lines = collect(TestCommand.blankLines())
        assertEquals(listOf("a", "b"), lines, "空行应被跳过")
    }
}
