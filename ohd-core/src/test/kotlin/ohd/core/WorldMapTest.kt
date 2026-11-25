package ohd.core

import ohd.core.actions.WorldMap
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldMapTest {

    private val bundle = "ohd.test.worldmap"

    @AfterTest
    fun cleanup() {
        WorldMap.clear(bundle)
    }

    private fun flat(vararg labels: String) = labels.mapIndexed { i, t ->
        LayoutNode(
            text = t, id = null, type = "Button", description = null,
            bounds = Rect(10, 100 + i * 80, 200, 160 + i * 80),
            clickable = true, enabled = true, visible = true,
            bundleName = null, depth = 1,
        )
    }

    @Test
    fun observeLearnPlanAndPersist() {
        WorldMap.clear(bundle)
        val home = WorldMap.observePage(bundle, flat("多实例测试", "OHOS运行时开关", "dialog"), sceneSwipes = 0)
        WorldMap.learnScroll(bundle, home.id, 9, flat("GPU Texture Leak Probe"))
        val probe = WorldMap.observePage(
            bundle,
            flat("GPU Texture Leak Probe", "一键测试"),
            pushIfNew = true,
        )
        WorldMap.noteEdge(bundle, home.id, probe.id, "tap", "GPU Texture Leak Probe")

        val plan = assertNotNull(WorldMap.planTo(bundle, "GPU Texture"))
        assertTrue(plan.steps.any { it.kind == "swipe" && (it.swipes ?: 0) >= 9 } ||
            plan.steps.any { it.kind == "tap" })
        assertTrue(plan.summary.isNotBlank())

        val encoded = WorldMap.encode(WorldMap.load(bundle))
        assertTrue(encoded.contains("ohd-worldmap/1"))
        assertTrue(encoded.contains("GPU Texture"))

        // 落盘可读回
        val p = WorldMap.pathFor(bundle)
        assertTrue(Files.isRegularFile(p))
        WorldMap.clear(bundle)
        // clear 删文件；手动写回再 load
        Files.createDirectories(p.parent)
        Files.writeString(p, encoded)
        val again = WorldMap.load(bundle)
        assertEquals(2, again.pages.size)
        assertTrue(again.edges.isNotEmpty())
    }
}
