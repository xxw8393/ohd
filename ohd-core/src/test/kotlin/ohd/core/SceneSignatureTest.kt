package ohd.core

import ohd.core.actions.SceneSignature
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 场景签名的纯函数测试：不碰磁盘、不碰设备。
 *
 * 这是地图能力的正确性地基 —— 签名若不稳定，BFS/滑次/坐标复用全部挂在错误的节点上。
 */
class SceneSignatureTest {

    private fun node(
        text: String?,
        top: Int,
        clickable: Boolean = true,
        description: String? = null,
    ) = LayoutNode(
        text = text,
        id = null,
        type = "Button",
        description = description,
        bounds = Rect(10, top, 200, top + 60),
        clickable = clickable,
        enabled = true,
        visible = true,
        bundleName = null,
        depth = 1,
    )

    @Test
    fun normalizeFoldsDigitsAndPunctuation() {
        assertEquals(SceneSignature.normalizeLabel("多实例测试 12"), "多实例测试#")
        // 斜杠有语义（A/B 与 AB 是不同入口），不能被当标点删掉
        assertEquals(SceneSignature.normalizeLabel("A/B 关 progressive GPU"), "a/b关progressivegpu")
        assertEquals(SceneSignature.normalizeLabel("   "), "")
        // 只有数字不同 → 归一后同签名（列表项序号不该制造新场景）
        assertEquals(
            SceneSignature.normalizeLabel("item 7"),
            SceneSignature.normalizeLabel("item 512"),
        )
    }

    @Test
    fun sameChromeSameSceneRegardlessOfTail() {
        // 静态骨架相同、滚动区内容不同 → 必须同场景（剥离 scrollable 的效果）
        fun page(item: String) = listOf(
            node("首页", 100), node("设置", 160),
            LayoutNode(
                text = null, id = null, type = "List", description = null,
                bounds = Rect(0, 240, 1280, 2700), clickable = false, enabled = true,
                visible = true, bundleName = null, depth = 1, scrollable = true,
                children = listOf(
                    LayoutNode(
                        text = item, id = null, type = "ListItem", description = null,
                        bounds = Rect(0, 300, 1280, 500), clickable = true, enabled = true,
                        visible = true, bundleName = null, depth = 2,
                    ),
                ),
            ),
        )
        assertEquals(
            SceneSignature.of(page("尾部A")).first,
            SceneSignature.of(page("尾部B")).first,
        )
    }

    @Test
    fun scrolledContentYieldsDifferentScene() {
        // 整体上移（滚过一屏）：取到的元素集合已变 → 必须不同场景
        val top = listOf(node("首页", 100), node("设置", 160), node("主题", 220), node("关于", 280))
        val scrolled = listOf(node("设置", 100), node("主题", 160), node("关于", 220), node("帮助", 280))
        assertNotEquals(SceneSignature.of(top).first, SceneSignature.of(scrolled).first)
    }

    @Test
    fun accessibilityHintsDoNotAffectScene() {
        // 系统手势提示每页都有，掺进来只会把所有页糊成一个场景
        val plain = listOf(node("多实例测试", 300))
        val noisy = listOf(
            node("单指双击即可返回。", 10),
            node("单指双击即可打开最近任务。", 20),
            node("单指双击即可返回主屏幕。", 30),
            node("多实例测试", 300),
        )
        assertEquals(SceneSignature.of(plain).first, SceneSignature.of(noisy).first)
    }

    /**
     * 静态骨架身份会带上非可点击文本（标题等也有区分度），
     * 但**滚动区内的文本**必须被排除 —— 那才是随滑动变化的部分。
     */
    @Test
    fun scrollableContentExcludedFromIdentity() {
        fun page(item: String) = listOf(
            LayoutNode(
                text = "固定页头", id = null, type = "Navigation", description = null,
                bounds = Rect(0, 0, 1280, 200), clickable = false, enabled = true,
                visible = true, bundleName = null, depth = 0,
            ),
            LayoutNode(
                text = null, id = null, type = "List", description = null,
                bounds = Rect(0, 240, 1280, 2700), clickable = false, enabled = true,
                visible = true, bundleName = null, depth = 1, scrollable = true,
                children = listOf(
                    LayoutNode(
                        text = item, id = null, type = "ListItem", description = null,
                        bounds = Rect(0, 300, 1280, 500), clickable = true, enabled = true,
                        visible = true, bundleName = null, depth = 2,
                    ),
                ),
            ),
        )
        assertEquals(
            SceneSignature.of(page("列表项A")).first,
            SceneSignature.of(page("列表项B")).first,
            "滚动区内容变了却换了身份：静态骨架没起作用",
        )
    }

    @Test
    fun emptyPageGetsSingletonMarkerNotSharedBucket() {
        val empty = SceneSignature.topBandLabels(emptyList())
        assertEquals(listOf("∅"), empty)
        // 无文本但有可点击容器 → 也应退化为占位而不是抛错
        val structureOnly = listOf(node(null, 100))
        assertEquals(listOf("∅"), SceneSignature.topBandLabels(structureOnly))
    }

    @Test
    fun descriptionUsedWhenTextMissing() {
        val a = listOf(node(null, 100, description = "关闭A"))
        val b = listOf(node("关闭A", 100))
        assertEquals(SceneSignature.of(a).first, SceneSignature.of(b).first)
    }

    @Test
    fun sparseTopStillGathersDistinguishingContent() {
        // 顶部只有一个返回按钮、下方大片空白：若只看固定高度带，签名会退化成仅 "back"，
        // 把内容完全不同的页面并成一个场景（实测 3933 页并成 1 个 scene）。
        // 这里必须继续向下取到有区分度的元素。
        val sparse = listOf(
            node("back", 248),
            node("多实例测试", 2566),
        )
        val labels = SceneSignature.topBandLabels(sparse)
        assertEquals(listOf("back", "多实例测试"), labels, "稀疏顶部必须取到下方真实内容")

        // 两种内容不同的稀疏页，必须落在不同场景
        val a = listOf(node("back", 248), node("多实例测试", 2566))
        val b = listOf(node("back", 248), node("嵌套滚动", 2566))
        assertNotEquals(SceneSignature.of(a).first, SceneSignature.of(b).first)
    }

    @Test
    fun scanStopsAfterEnoughContent() {
        // 凑够 4 个后，超出扫描上限的补充元素才被排除 —— 避免签名吃下整屏内容随滚动漂移
        val far = listOf(
            node("a入口", 100), node("b入口", 120), node("c入口", 140), node("d入口", 160),
            node("远处的元素", 100 + 5000),
        )
        assertEquals(listOf("a入口", "b入口", "c入口", "d入口"), SceneSignature.topBandLabels(far))
    }

    /**
     * 分区取样：屏高 20%/50%/80% 三处锚点各取附近元素。
     * 中部空白（典型 y=[80,248,479,577,1180,2779]）不能再让签名退化成空。
     */
    @Test
    fun bandedSamplingCoversSparseMidArea() {
        val H = 1000
        // node 的 centerY = top+30，按锚点位置（200/500/800）摆放
        val flat = listOf(
            node("顶部入口A", 170),
            // 中部 500 附近故意留空（正是只取中心会退化的场景）
            node("下部入口C", 770),
        )
        val labels = SceneSignature.bandedLabels(flat, H)
        // 身份用的是归一化（小写）后的 label
        assertTrue("顶部入口a" in labels, "20% 锚点应取到顶部元素，实际=$labels")
        assertTrue("下部入口c" in labels, "80% 锚点应取到下部元素，实际=$labels")
    }

    /** 分区取样必须能区分「同一位置、不同内容」的页面。 */
    @Test
    fun bandedSamplingDistinguishesDifferentContent() {
        val H = 1000
        val a = listOf(node("甲页入口", 170), node("甲页另一项", 770))
        val b = listOf(node("乙页入口", 170), node("乙页另一项", 770))
        assertNotEquals(
            SceneSignature.of(a, screenHeight = H).first,
            SceneSignature.of(b, screenHeight = H).first,
        )
    }

    /** 屏高未知（0）时退回原有取样，不得崩。 */
    @Test
    fun bandedFallsBackWhenScreenHeightUnknown() {
        val flat = listOf(node("某个入口", 100))
        assertEquals(
            SceneSignature.of(flat).first,
            SceneSignature.of(flat, screenHeight = 0).first,
        )
    }

    /** 空树/无 bounds 时返回占位，不能抛错。 */
    @Test
    fun bandedHandlesEmptyInput() {
        assertEquals(listOf("∅"), SceneSignature.bandedLabels(emptyList(), 1000))
        assertEquals(listOf("∅"), SceneSignature.bandedLabels(listOf(node("x", 10)), 0))
    }

    @Test
    fun shortButMeaningfulLabelsKept() {
        // "返回" 实测出现 411 次，是真实入口；不能因长度短被滤掉
        assertTrue("返回" in SceneSignature.topBandLabels(listOf(node("返回", 100))))
        // 纯数字/纯符号无信息量，滤掉
        assertTrue(SceneSignature.topBandLabels(listOf(node("12", 100))) == listOf("∅"))
    }
}
