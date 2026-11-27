package ohd.core

import ohd.core.actions.LayoutCache
import ohd.core.actions.ScrollAtlas
import ohd.core.actions.UiActions
import ohd.core.actions.UiNav
import ohd.core.actions.UiReady
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import ohd.platform.OhdPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiNavTest {

    private fun node(
        text: String,
        clickable: Boolean = true,
        x: Int = 100,
        y: Int = 200,
        w: Int = 40,
        h: Int = 40,
        type: String = "Button",
    ) = LayoutNode(
        text = text,
        id = null,
        type = type,
        description = null,
        bounds = Rect(x, y, x + w, y + h),
        clickable = clickable,
        enabled = true,
        visible = true,
        bundleName = null,
        depth = 1,
    )

    @Test
    fun resolveUniqueClickable() {
        val nodes = listOf(node("多实例测试", x = 900, y = 2500))
        val ok = assertIs<UiNav.Resolve.Ok>(UiNav.resolve(nodes, "多实例"))
        assertEquals(920, ok.pick.x)
    }

    @Test
    fun contentSafeRectAvoidsGestureBar() {
        val screen = node("", x = 0, y = 0, w = 1280, h = 2832, type = "root")
        val status = node("#status", x = 0, y = 0, w = 1280, h = 120, type = "metaballNode")
        val list = node("GPU Texture Leak Probe", x = 50, y = 200, w = 1180, h = 2000)
        val gesture = node("单指双击即可返回主屏幕。", x = 500, y = 2727, w = 200, h = 100)
        val safe = UiNav.contentSafeRect(
            listOf(screen, status, list, gesture),
            marginPx = 40,
            edgeMarginPx = 48,
        )
        assertTrue(safe.bottom < 2727, "safe.bottom=${safe.bottom} 必须在手势条之上")
        assertTrue(safe.top > 100, "safe.top=${safe.top} 必须在状态栏之下")
        assertTrue(safe.left >= 48 + 40, "safe.left=${safe.left} 须离屏左缘")
        assertTrue(safe.right <= 1280 - 48 - 40, "safe.right=${safe.right} 须离屏右缘")
        val action = UiNav.scrollPageAction("up", safe)
        assertEquals(UiActions.Kind.DRAG, action.kind)
        val y0 = action.args[1].toInt()
        val y1 = action.args[3].toInt()
        assertTrue(y0 in safe.top..safe.bottom && y1 in safe.top..safe.bottom)
        assertTrue(y0 > y1, "上滑起点应在下方")
    }

    @Test
    fun contentSafeRectWithoutGestureNodeKeepsBottomBand() {
        val screen = node("", x = 0, y = 0, w = 1280, h = 2832, type = "root")
        val list = node("item", x = 0, y = 0, w = 1280, h = 2832)
        val safe = UiNav.contentSafeRect(listOf(screen, list), marginPx = 40, edgeMarginPx = 48)
        assertTrue(safe.bottom <= 2832 - 283 - 40, "无手势节点也须扣约10%底带+margin: bottom=${safe.bottom}")
        assertTrue(safe.left >= 88 && safe.right <= 1280 - 88)
    }

    @Test
    fun desktopDetection() {
        val desk = listOf(
            node("", x = 88, y = 272, w = 189, h = 189, type = "Image"),
            node("", x = 393, y = 272, w = 189, h = 189, type = "Image"),
            node("", x = 698, y = 272, w = 189, h = 189, type = "RelativeContainer"),
            node("", x = 1003, y = 272, w = 189, h = 189, type = "RelativeContainer"),
            node("", x = 88, y = 600, w = 189, h = 189, type = "Image"),
            node("", x = 393, y = 600, w = 189, h = 189, type = "Image"),
            node("", x = 698, y = 600, w = 189, h = 189, type = "Image"),
            node("", x = 1003, y = 600, w = 189, h = 189, type = "Image"),
            node("", x = 88, y = 900, w = 189, h = 189, type = "Image"),
        )
        assertTrue(UiReady.looksLikeDesktop(desk))
        assertFalse(UiReady.looksLikeDesktop(listOf(node("多实例测试"))))
    }

    @Test
    fun scrollSwipeFallbackStaysAboveGestureBand() {
        val pts = UiNav.scrollSwipe("up", width = 1280, height = 2832)
        assertTrue(pts[1] < 2832 * 0.85, "fallback 起点不应贴底手势区: ${pts[1]}")
    }
}

class LayoutCacheTest {
    private fun hdc(key: String) = Hdc(
        exe = OhdPath.of("/bin/true"),
        connectKey = key,
        kind = DeviceKind.OHOS,
    )

    private fun flat(vararg labels: String) = labels.mapIndexed { i, t ->
        LayoutNode(
            text = t, id = null, type = "Button", description = null,
            bounds = Rect(0, i * 10, 10, i * 10 + 10),
            clickable = true, enabled = true, visible = true,
            bundleName = null, depth = 0,
        )
    }

    @Test
    fun multiPageByFingerprintAndBackStack() {
        LayoutCache.clear()
        val h = hdc("multi-page")
        val home = LayoutCache.put(h, flat("多实例测试", "OHOS运行时开关"))
        val probe = LayoutCache.noteEnter(h, flat("GPU Texture Leak Probe", "一键测试"))
        assertNotEquals(home.pageId, probe.pageId)
        assertEquals(home.pageId, LayoutCache.noteBack(h))
        LayoutCache.clear()
    }
}

class ScrollAtlasTest {
    private fun hdc() = Hdc(
        exe = OhdPath.of("/bin/true"),
        connectKey = "atlas-dev",
        kind = DeviceKind.OHOS,
    )

    private fun nodes(vararg labels: String) = labels.mapIndexed { i, t ->
        LayoutNode(
            text = t, id = null, type = "Button", description = null,
            bounds = Rect(0, 100 + i * 50, 40, 140 + i * 50),
            clickable = true, enabled = true, visible = true,
            bundleName = null, depth = 1,
        )
    }

    @Test
    fun learnAndEstimateBatchSwipes() {
        ScrollAtlas.clear()
        val h = hdc()
        ScrollAtlas.learn(h, "home", 0, nodes("dialog"))
        ScrollAtlas.learn(h, "home", 9, nodes("GPU Texture Leak Probe"))
        assertEquals(9, ScrollAtlas.estimate(h, "home", "GPU Texture"))
        ScrollAtlas.clear()
    }
}
