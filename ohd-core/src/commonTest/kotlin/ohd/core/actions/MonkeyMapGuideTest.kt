package ohd.core.actions

import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonkeyMapGuideTest {
    @Test
    fun matchLabel_containsEitherWay() {
        assertTrue(MonkeyMapGuide.matchLabel("预取渲染 - Skia", "预取渲染"))
        assertTrue(MonkeyMapGuide.matchLabel("预取渲染", "预取渲染 - Skia"))
        assertTrue(!MonkeyMapGuide.matchLabel("a", "b"))
    }

    @Test
    fun labelNearTap_hitsContainingNode() {
        val nodes =
            listOf(
                LayoutNode(
                    text = "多实例测试",
                    id = null,
                    type = "Text",
                    description = null,
                    bounds = Rect(100, 200, 400, 280),
                    clickable = true,
                    enabled = true,
                    visible = true,
                    bundleName = "com.tencent.compose",
                    depth = 1,
                ),
            )
        assertEquals("多实例测试", MonkeyMapGuide.labelNearTap("tap 200 240", nodes))
        assertNull(MonkeyMapGuide.labelNearTap("tap 10 10", nodes))
        assertNull(MonkeyMapGuide.labelNearTap("swipe 1 2 3 4 5", nodes))
    }

    @Test
    fun swipeUp_staysInSafeArea() {
        val line = MonkeyMapGuide.swipeUp(1280, 2832, 137, 2727, Random(1))
        assertTrue(line.startsWith("swipe "))
        val p = line.split(Regex("\\s+")).drop(1).map { it.toInt() }
        assertEquals(5, p.size)
        assertTrue(p[1] in 138..2726)
        assertTrue(p[3] in 138..2726)
        assertTrue(p[3] < p[1]) // 上滑：终点 y 更小
    }

    @Test
    fun biasedAction_nullWithoutMapPage() {
        // 空地图 → null，交给随机 pick
        val r =
            MonkeyMapGuide.biasedAction(
                bundle = "ohd.test.missing.bundle.xyz",
                clickables = emptyList(),
                w = 1280,
                h = 2832,
                safeTop = 100,
                safeBottom = 2700,
                rnd = Random(0),
            )
        assertNull(r)
    }
}
