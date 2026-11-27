package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [spawnDetached] 的行为验证。
 *
 * 难点：分离进程「不等它」，所以只能验三件事——
 * ① 真能起来并拿到 pid；② 二进制不存在时**必须报错**（不能给假 pid）；
 * ③ 起来的进程确实在跑（用 sleep + kill 验）。
 */
class DetachedTest {

    @Test
    fun spawnsSleepAndReportsPid() {
        val p = spawnDetached(TestCommand.sleepSeconds(30))
        assertNull(p.spawnError, "启动失败：${p.spawnError}")
        assertNotNull(p.pid, "应拿到 pid")
        assertTrue(p.pid!! > 0, "pid 应为正数")
        // 清理：别在机器上留 30 秒的 sleep
        runCatching { fs.let { } ; killProcess(p.pid!!) }
    }

    @Test
    fun missingBinaryReportsErrorNotFakePid() {
        // 这条是核心：exec 失败若被当成成功，调用方会拿着假 pid 去 kill 而不自知
        val p = spawnDetached(listOf(TestCommand.missingBinary()))
        assertTrue(!p.ok, "不存在的二进制必须报错")
        assertNull(p.pid, "失败时不应给 pid")
        assertNotNull(p.spawnError, "应带失败原因")
    }

    @Test
    fun streamingDeliversLines() {
        val got = mutableListOf<String>()
        val p = spawnStreaming(TestCommand.echoLine("hello"), onLine = { got.add(it) })
        assertTrue(p.ok, "spawnStreaming 应成功启动，实际：${p.spawnError}")
        // 预算要给足：Linux 上 /bin/echo 毫秒级，但 Windows 的 powershell 冷启动
        // 在 CI 上常要 1~3 秒，原先 2000ms 会在输出到达前就 kill 掉子进程。
        val deadline = System.currentTimeMillis() + 20_000
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Env.sleepMillis(20)
        }
        PlatformEnv.killPid(p.pid ?: 0L)
        assertTrue(got.contains("hello"), "spawnStreaming 应能收到输出，实际：$got")
    }

    @Test
    fun rejectedOnEmptyCommand() {
        val p = spawnDetached(emptyList())
        assertTrue(!p.ok, "空命令应被拒")
    }
}

/** 跨平台的「结束进程」薄封装，仅供本测试清理用。 */
private fun killProcess(pid: Long) {
    runCatching { PlatformEnv.killPid(pid) }
}
