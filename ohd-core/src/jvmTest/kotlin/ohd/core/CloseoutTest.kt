package ohd.core

import ohd.core.caps.LogCap
import ohd.core.caps.LogWatchdog
import ohd.core.caps.MonkeyPolicy
import ohd.core.caps.SampleHealth
import ohd.platform.fs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.test.assertTrue

class CloseoutTest {

    @Test
    fun foreignBundleNodesAreNotTapTargets() {
        assertTrue(MonkeyPolicy.belongsToTarget("com.tencent.compose", "com.tencent.compose"))
        assertTrue(MonkeyPolicy.belongsToTarget(null, "com.tencent.compose"))
        assertTrue(MonkeyPolicy.belongsToTarget("", "com.tencent.compose"))
        assertFalse(MonkeyPolicy.belongsToTarget("com.ohos.sceneboard", "com.tencent.compose"))
        assertTrue(MonkeyPolicy.belongsToTarget("com.ohos.sceneboard", null))
    }

    @Test
    fun leaveActionsEndTheBatchWithoutBanningThem() {
        val screenH = 2832
        assertTrue(MonkeyPolicy.endsBatch("key Home", screenH))
        assertTrue(MonkeyPolicy.endsBatch("key Back", screenH))
        // 底部上滑：起点靠近底边、手指上移
        assertTrue(MonkeyPolicy.endsBatch("swipe 640 2700 640 2100 800", screenH))
        // 应用中部的滑动和点击仍可与后续事件同批
        assertFalse(MonkeyPolicy.endsBatch("swipe 640 1400 640 900 800", screenH))
        assertFalse(MonkeyPolicy.endsBatch("tap 640 2700", screenH))
        assertEquals(2_000L, MonkeyPolicy.BACKGROUND_DWELL_MS)
    }

    @Test
    fun maxStepsZeroIsUnlimitedAndDefault() {
        assertEquals(0, MonkeyPolicy.DEFAULT_MAX_STEPS)
        assertEquals(Int.MAX_VALUE, MonkeyPolicy.resolveMaxSteps(0))
        assertEquals(Int.MAX_VALUE, MonkeyPolicy.resolveMaxSteps(-1))
        assertEquals(100, MonkeyPolicy.resolveMaxSteps(100))
    }

    @Test
    fun durationConflictsWithStepCapOnlyWhenCapCanWin() {
        val tenHours = 10L * 3_600_000
        // 实测事故：--interval 0 + 默认 10 万步，17 分钟就截断 10h
        assertTrue(MonkeyPolicy.conflicts(100_000, intervalMs = 0, durationMs = tenHours, batch = 1))
        // 0 = 不限，与时长没有冲突
        assertFalse(MonkeyPolicy.conflicts(0, intervalMs = 0, durationMs = tenHours, batch = 1))
        // 步数下限已经长过时长，时长会先到，不必告警
        assertFalse(MonkeyPolicy.conflicts(100_000, intervalMs = 800, durationMs = 10_000, batch = 1))
        assertTrue(MonkeyPolicy.startWarning(100_000, intervalMs = 0, durationMs = tenHours, batch = 1)!!.contains("0"))
        assertNull(MonkeyPolicy.startWarning(0, intervalMs = 0, durationMs = tenHours, batch = 1))
    }

    @Test
    fun hittingStepCapBeforeDeadlineIsTheActualConflict() {
        val deadline = 1_000_000L
        assertTrue(MonkeyPolicy.durationCutShort(100_000, steps = 100_000, nowMs = deadline - 1, deadlineMs = deadline))
        assertFalse(MonkeyPolicy.durationCutShort(0, steps = 100_000, nowMs = deadline - 1, deadlineMs = deadline))
        assertFalse(MonkeyPolicy.durationCutShort(100_000, steps = 100_000, nowMs = deadline, deadlineMs = deadline))
        assertTrue(MonkeyPolicy.hitMessage(100_000).contains("max-steps"))
    }

    @Test
    fun sampleFailureMajorityBecomesUnreliableWithReasons() {
        val sparse = SampleHealth.assess(27, linkedMapOf("超时" to 1500, "pss不可用" to 21))
        assertTrue(sparse != null)
        assertTrue(sparse!!.message.contains("98") || sparse.message.contains("97"), sparse.message)
        assertTrue(sparse.message.contains("超时"), sparse.message)
        assertTrue(sparse.message.contains("pss不可用"), sparse.message)
        assertTrue(sparse.message.contains("不可信"), sparse.message)

        assertNull(SampleHealth.assess(100, mapOf("超时" to 2)))
        assertNull(SampleHealth.assess(1, mapOf("超时" to 1)))
        assertEquals("超时", SampleHealth.kindOf("hidumper 超时"))
        assertEquals("pss不可用", SampleHealth.kindOf("pss 不可用"))
        assertEquals("hidumper失败", SampleHealth.kindOf("hidumper 失败"))
        assertEquals("其他", SampleHealth.kindOf("boom"))
    }

    @Test
    fun logMaxSizeDefaultsToTwoGigabytesAndZeroIsUnlimited() {
        assertEquals(2L * 1024 * 1024 * 1024, LogWatchdog.DEFAULT_MAX_BYTES)
        assertEquals(LogWatchdog.DEFAULT_MAX_BYTES, LogWatchdog.parseSize(null))
        assertEquals(2L * 1024 * 1024 * 1024, LogWatchdog.parseSize("2G"))
        assertEquals(2L * 1024 * 1024 * 1024, LogWatchdog.parseSize("2gb"))
        assertEquals(500L * 1024 * 1024, LogWatchdog.parseSize("500M"))
        assertEquals(0L, LogWatchdog.parseSize("0"))
        assertTrue(LogWatchdog.exceeded(2L * 1024 * 1024 * 1024, LogWatchdog.DEFAULT_MAX_BYTES))
        assertFalse(LogWatchdog.exceeded(10, 0))
        assertTrue("max-size" in LogCap().accepts)
        assertTrue(LogCap().usage.contains("max-size"))
    }

    @Test
    // 用 python3 起「子进程 + 等待」来验看守进程收不收进程树，Windows runner 无 python3；
    // 该逻辑跨平台，但缺 POSIX 进程语义的宿主上无法构造现场，跳过。
    @DisabledOnOs(OS.WINDOWS)
    fun watchdogStopsWriterWhenFileReachesCapAndReapsChildren() {
        // 管道上的 sh 会把 echo 缓冲到脚本结束，读 pid 会卡住；python 显式 flush。
        val parent = ProcessBuilder(
            "python3", "-c",
            "import os; pid=os.spawnvp(os.P_NOWAIT,'sleep',['sleep','60']); print(pid, flush=True); os.waitpid(pid, 0)",
        ).start()
        val childPid = parent.inputStream.bufferedReader().readLine().trim().toLong()
        val file = fs.createTempFile("ohd-log-", ".txt")
        try {
            fs.writeBytes(file, ByteArray(80))
            assertEquals(
                LogWatchdog.Action.STOPPED_SIZE,
                LogWatchdog.poll(parent.pid(), file, maxBytes = 50),
            )
            assertFalse(ProcessTrees.alive(parent.pid()), "父进程应被回收")
            assertFalse(ProcessTrees.alive(childPid), "子进程应被回收")
            assertTrue(fs.exists(LogWatchdog.marker(file)))
        } finally {
            ProcessTrees.kill(parent.pid())
            ProcessTrees.kill(childPid)
            fs.deleteIfExists(LogWatchdog.marker(file))
            fs.deleteIfExists(file)
        }
    }

    @Test
    fun spawnedWatchdogCapsTheFileFromItsOwnProcess() {
        val proc = ProcessBuilder("sleep", "30").start()
        val file = fs.createTempFile("ohd-log-", ".txt")
        var guard: Long? = null
        try {
            fs.writeBytes(file, ByteArray(80))
            guard = LogWatchdog.spawn(proc.pid(), file, maxBytes = 50)
            assertTrue(guard != null, "看守进程没启动")
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && ProcessTrees.alive(proc.pid())) {
                Thread.sleep(200)
            }
            assertFalse(ProcessTrees.alive(proc.pid()), "独立看守未在上限处停掉写入进程")
            // 看守进程是「先收进程树、再写 marker」：kill 内部的等待最长可达 800ms×2，
            // 目标进程一消失就断言 marker 存在会踩竞态（CI 慢机器上必现），这里等它写完。
            val markerDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < markerDeadline && !fs.exists(LogWatchdog.marker(file))) {
                Thread.sleep(100)
            }
            assertTrue(fs.exists(LogWatchdog.marker(file)), "看守进程未落 marker")
        } finally {
            ProcessTrees.kill(proc.pid())
            guard?.let { ProcessTrees.kill(it) }
            fs.deleteIfExists(LogWatchdog.marker(file))
            fs.deleteIfExists(file)
        }
    }

    @Test
    fun watchdogLeavesALiveWriterUnderTheCap() {
        val proc = ProcessBuilder("sleep", "30").start()
        val file = fs.createTempFile("ohd-log-", ".txt")
        try {
            fs.writeBytes(file, ByteArray(10))
            assertEquals(LogWatchdog.Action.CONTINUE, LogWatchdog.poll(proc.pid(), file, maxBytes = 100))
            assertTrue(ProcessTrees.alive(proc.pid()))
            proc.destroyForcibly()
            proc.waitFor()
            assertEquals(LogWatchdog.Action.STOPPED_DEAD, LogWatchdog.poll(proc.pid(), file, maxBytes = 100))
        } finally {
            ProcessTrees.kill(proc.pid())
            fs.deleteIfExists(file)
        }
    }
}
