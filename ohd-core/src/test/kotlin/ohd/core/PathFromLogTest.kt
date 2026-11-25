package ohd.core

import ohd.core.parsers.PathFromLog
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 与 `tools/path_from_log.py` 对拍：同一份 hilog + map + coords → 相同 .ohd 正文。
 */
class PathFromLogTest {

    private val root = Paths.get("/Users/xuxingwang/KMP")
    private val hilog = root.resolve("ohd-evidence/device/20261019-160302-log-save/001-hilog.txt")
    private val map = root.resolve("ohd/tools/samples/pagemap.txt")
    private val coordsStack = root.resolve("ohd/tools/samples/coords2.txt")
    private val coordsFirst = root.resolve("ohd/tools/samples/coords.txt")

    @Test
    fun stackMatchesPythonSample() {
        if (!Files.isRegularFile(hilog)) return // 无本地证据则跳过（CI 无设备目录）
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
        if (!Files.isRegularFile(hilog)) return
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
