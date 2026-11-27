package ohd.core

import ohd.core.parsers.PathFromLog
import ohd.platform.OhdPath
import ohd.platform.fs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 与 `tools/path_from_log.py` 对拍：同一份 hilog + map + coords → 相同 .ohd 正文。
 *
 * 路径不写死：仓库目录被改过名（`KMP/ohd` → `KMP/github-ohd`），写死绝对路径会让
 * 这组用例在改名后**静默失效**（`return` 跳过），或被误读成真实回归。
 * 现在按 Gradle 测试工作目录（模块目录）逐级向上找仓库根，再定位样本与证据。
 */
class PathFromLogTest {

    /**
     * 从当前工作目录向上找仓库根（含 `settings.gradle.kts` 的那一层）。
     * 找不到就退回当前目录 —— 下方每处用 `isRegularFile` 守卫，缺失时跳过。
     */
    private val root: OhdPath = run {
        var p: OhdPath? = OhdPath.of("").toAbsolutePath()
        while (p != null && !fs.isRegularFile(p.resolve("settings.gradle.kts"))) p = p.parent
        p ?: OhdPath.of("").toAbsolutePath()
    }

    private val samples = root.resolve("tools/samples")

    /**
     * 对拍用的 hilog 证据。
     *
     * 优先仓库内 `ohd-evidence/`，其次同级目录（本机历史产物常放在仓库外）。
     * 都没有则该用例跳过 —— 这是有意为之：证据不入库，CI 上本就没有。
     */
    private val hilog: OhdPath = listOf(
        root.resolve("ohd-evidence/device/20261019-160302-log-save/001-hilog.txt"),
        root.parent?.resolve("ohd-evidence/device/20261019-160302-log-save/001-hilog.txt"),
    ).filterNotNull().firstOrNull { fs.isRegularFile(it) }
        ?: root.resolve("ohd-evidence/device/20261019-160302-log-save/001-hilog.txt")

    private val map = samples.resolve("pagemap.txt")
    private val coordsStack = samples.resolve("coords2.txt")
    private val coordsFirst = samples.resolve("coords.txt")

    @Test
    fun stackMatchesPythonSample() {
        if (!fs.isRegularFile(hilog)) return // 无本地证据则跳过（CI 无设备目录）
        val ev = PathFromLog.parseFile(hilog)
        val trans = PathFromLog.inferStack(ev)
        assertEquals(4, trans.size)
        assertEquals(3, trans.count { it.act == "enter" })
        assertEquals(1, trans.count { it.act == "back" })

        val lines = PathFromLog.emitStack(
            trans,
            PathFromLog.loadKv(map),
            PathFromLog.loadKv(coordsStack),
            "com.tencent.compose",
            "EntryAbility",
        )
        val body = lines.joinToString("\n")
        assertTrue(body.contains("tap 297 683"))
        assertTrue(body.contains("Compose_0->Compose_1"))
        assertTrue(body.contains("key Back"))
        assertTrue(body.contains("doubletap 1028 761"))
    }

    @Test
    fun firstAlgoProducesEnterBack() {
        if (!fs.isRegularFile(hilog)) return
        val steps = PathFromLog.inferFirst(PathFromLog.parseFile(hilog))
        assertTrue(steps.any { it.act == "enter" })
        assertTrue(steps.any { it.act == "back" })
        val lines = PathFromLog.emitFirst(
            steps,
            PathFromLog.loadKv(map),
            PathFromLog.loadKv(coordsFirst),
            "com.tencent.compose",
            "EntryAbility",
        )
        assertTrue(lines.first().contains("hilog"))
        assertTrue(lines[1].startsWith("launch "))
    }

    @Test
    fun stackInferIgnoresRepeatShowOnTop() {
        val text = """
            10-19 16:03:44.100  1  1 I A: ArkUIViewController(Compose_0) onPageShow active=true
            10-19 16:03:44.200  1  1 I A: ArkUIViewController(Compose_0) onPageShow active=true
            10-19 16:03:44.300  1  1 I A: ArkUIViewController(Compose_1) onPageShow active=true
        """.trimIndent()
        val trans = PathFromLog.inferStack(PathFromLog.parse(text))
        assertEquals(2, trans.size)
        assertEquals("enter", trans[0].act)
        assertEquals("Compose_0", trans[0].to)
        assertEquals("enter", trans[1].act)
        assertEquals("Compose_1", trans[1].to)
    }
}
