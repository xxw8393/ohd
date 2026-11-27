package ohd.core

import ohd.core.actions.SceneSignature
import ohd.core.actions.WorldMap
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
        assertTrue(encoded.contains(WorldMap.SCHEMA))
        assertTrue(encoded.contains("GPU Texture"))

        // 落盘可读回：写合并模式下需显式 flush
        WorldMap.flushAll()
        val p = WorldMap.pathFor(bundle)
        assertTrue(Files.isRegularFile(p))
        WorldMap.clear(bundle)
        // clear 删文件；用 encode 出的明文写回再 load —— 顺带验证「明文旧档仍读得动」
        Files.createDirectories(p.parent)
        Files.writeString(p, encoded)
        val again = WorldMap.load(bundle)
        assertEquals(2, again.pages.size)
        assertTrue(again.edges.isNotEmpty())
    }

    @Test
    fun pathCrashBansSameTapAndPersists() {
        WorldMap.clear(bundle)
        val home = WorldMap.observePage(bundle, flat("多实例测试", "image-dog"), sceneSwipes = 0)
        assertFalse(WorldMap.isPathBanned(bundle, home.id, "多实例测试"))

        WorldMap.notePathCrash(
            bundle,
            home.id,
            "多实例测试",
            pidBefore = "1001",
            pidAfter = null,
            expect = "关闭A",
        )
        assertTrue(WorldMap.isPathBanned(bundle, home.id, "多实例测试"))
        assertNull(WorldMap.planTo(bundle, "多实例测试", fromPageId = home.id))

        val encoded = WorldMap.encode(WorldMap.load(bundle))
        assertTrue(encoded.contains("\"crashes\""))
        assertTrue(encoded.contains("多实例测试"))
        WorldMap.clear(bundle)
        Files.createDirectories(WorldMap.pathFor(bundle).parent)
        Files.writeString(WorldMap.pathFor(bundle), encoded)
        val again = WorldMap.load(bundle)
        assertEquals(1, again.crashes.size)
        assertTrue(WorldMap.isPathBanned(bundle, home.id, "多实例测试"))

        WorldMap.clearCrashes(bundle)
        assertFalse(WorldMap.isPathBanned(bundle, home.id, "多实例测试"))
        assertNotNull(WorldMap.planTo(bundle, "多实例测试", fromPageId = home.id))
    }

    @Test
    fun mapFirstFindsCoordsAndInvalidate() {
        WorldMap.clear(bundle)
        val home = WorldMap.observePage(bundle, flat("多实例测试", "image-dog"), sceneSwipes = 0)
        // 单一观测不足以信任坐标：再看见一次同一页才给捷径
        WorldMap.observePage(bundle, flat("多实例测试", "image-dog"), sceneSwipes = 0)
        val tap = assertNotNull(WorldMap.findMapTap(bundle, "多实例测试", home.id))
        assertEquals("多实例测试", tap.item.label)
        assertNotNull(tap.item.centerX)
        assertNotNull(tap.item.centerY)

        WorldMap.invalidateItemCoords(bundle, home.id, "多实例测试")
        assertNull(WorldMap.findMapTap(bundle, "多实例测试", home.id))
    }

    /**
     * 同一 label 在同场景的另一页出现在**不同纵坐标** → 它随滚动位移，
     * `--map-first` 不得直接拿旧坐标点。这是「点错页」的主因。
     *
     * 构造成立的前提：两页必须同 scene（顶部带一致），只有元素纵坐标变了。
     */
    @Test
    fun driftingCoordsAreNotReusedByMapFirst() {
        WorldMap.clear(bundle)
        // 用同一地标确认同一场景，但目标元素一上一下 → 测坐标漂移判定
        val top = WorldMap.observePage(
            bundle, shelf("公共项", topY = 100, tail = "第二屏"),
            sceneSwipes = 0, landmark = "共同场景页",
        )
        WorldMap.observePage(
            bundle, shelf("公共项", topY = 1500, tail = "第二屏2"),
            sceneSwipes = 1, landmark = "共同场景页",
        )

        val doc = WorldMap.load(bundle)
        assertEquals(
            doc.pages.values.map { it.sceneId }.distinct().size,
            1,
            "构造失败：两页应属同一场景，否则测不到漂移判定",
        )
        // 已观测到漂移 → 不应再给坐标捷径
        assertNull(
            WorldMap.findMapTap(bundle, "公共项", top.id),
            "坐标漂移后仍返回捷径：会点到错误位置",
        )
    }

    /** 坐标稳定的页（同场景各页元素位置恒定）应继续享受 map-first 捷径。 */
    @Test
    fun stableCoordsStillShortCircuit() {
        WorldMap.clear(bundle)
        val a = WorldMap.observePage(
            bundle, shelf("固定项", topY = 300, tail = "尾部1"),
            sceneSwipes = 0, landmark = "共同场景页",
        )
        // 同场景另一页，目标元素位置不变 → 有对照且未漂移，可复用
        WorldMap.observePage(
            bundle, shelf("固定项", topY = 300, tail = "尾部2"),
            sceneSwipes = 1, landmark = "共同场景页",
        )
        assertNotNull(WorldMap.findMapTap(bundle, "固定项", a.id))
    }

    /**
     * sceneId 必须落盘，且**把同一页的不同滚动位置并成一个场景**。
     * 这是静态骨架方案的核心价值：滚动区被剥离，身份不随滑动变化。
     */
    @Test
    fun sceneIdPersistsAndGroupsScrollVariants() {
        WorldMap.clear(bundle)
        val v1 = WorldMap.observePage(bundle, pagedTree("页头", body = listOf("列表项A")), sceneSwipes = 0)
        val v2 = WorldMap.observePage(bundle, pagedTree("页头", body = listOf("列表项B", "列表项C")), sceneSwipes = 1)
        // 说明：pageId 来自全文指纹，此处两页仅滚动区不同，指纹本就可能相同；
        // 关键断言是「同场景」，见下。
        val doc = WorldMap.load(bundle)
        assertEquals(
            doc.pages[v1.id]!!.sceneId,
            doc.pages[v2.id]!!.sceneId,
            "同场景的滚动变体必须共享 sceneId",
        )
        assertTrue(WorldMap.encode(doc).contains("sceneId"))
    }

    /** 旧 schema（无 sceneId）必须读得动，且不谎报坐标可直接复用。 */
    /**
     * schema/2 旧档（带 labels、无 gzip）必须读得动，且 labels 语义不丢。
     *
     * 新档不再写 labels，靠 items 重建；但旧档若带 labels 仍应照用，
     * 否则同一份档在升级前后读出的内存态会不一致。
     */
    @Test
    fun schema2PlainDocStillLoads() {
        WorldMap.clear(bundle)
        val v2 = """
            {"schema":"ohd-worldmap/2","bundle":"$bundle","updatedAt":1,"currentPageId":"p1",
             "backStack":["p1"],"edges":[],
             "pages":{"p1":{"id":"p1","summary":"v2页","sceneId":"s1","seenCount":3,
               "landmarkKeys":[],"viewport":"vp","labels":["旧按钮","只存在于labels"],
               "items":[{"label":"旧按钮","clickable":true,"centerX":10,"centerY":20,
                         "swipesFromTop":null,"approxPixelsY":null,"lastSeenMs":1}]}},
             "crashes":[]}
        """.trimIndent()
        val p = WorldMap.pathFor(bundle)
        Files.createDirectories(p.parent)
        Files.writeString(p, v2) // 明文写入 gz 路径：靠 magic 分流必须能读
        val doc = WorldMap.load(bundle)
        val page = doc.pages["p1"]!!
        assertEquals("v2页", page.summary)
        assertEquals("s1", page.sceneId)
        assertEquals(listOf("旧按钮", "只存在于labels"), page.labels, "旧档自带的 labels 应照用")
        assertEquals("旧按钮", page.items.single().label)
    }

    @Test
    fun legacyDocStillLoads() {
        WorldMap.clear(bundle)
        val legacy = """
            {"schema":"ohd-worldmap/1","bundle":"$bundle","updatedAt":1,"currentPageId":"p1",
             "backStack":["p1"],"edges":[],
             "pages":{"p1":{"id":"p1","summary":"旧页","lastSeenMs":1,
               "labels":["老按钮"],
               "items":[{"label":"老按钮","clickable":true,"centerX":10,"centerY":20,
                         "swipesFromTop":null,"approxPixelsY":null,"lastSeenMs":1}]}},
             "crashes":[]}
        """.trimIndent()
        val p = WorldMap.pathFor(bundle)
        Files.createDirectories(p.parent)
        Files.writeString(p, legacy)
        val doc = WorldMap.load(bundle)
        assertEquals("p1", doc.pages["p1"]?.sceneId, "缺 sceneId 应回落 pageId")
        // 旧档：无 sceneId 对照 + 只观测过 1 次 → 不得声称坐标可复用
        assertNull(
            WorldMap.findMapTap(bundle, "老按钮", "p1"),
            "旧档未经二次观测就复用坐标：会点到错误位置",
        )
    }

    /**
     * 清洗：内容相同的重复快照必须合并成一页，且不留悬空引用。
     * 这是把 21.8MB / 6598 页的膨胀数据压回合理体积的核心手段。
     */
    @Test
    fun cleanMergesDuplicateSnapshotsAndRemapsRefs() {
        WorldMap.clear(bundle)
        // 两个快照文本不同（pageId 指纹不同），但归一化后内容相同（数字被折叠）→ 应合并
        val a = WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        val b = WorldMap.observePage(bundle, flat("共用页头", "订单 2002"), pushIfNew = true)
        val c = WorldMap.observePage(bundle, flat("另一个页头", "别的项"), pushIfNew = true)
        assertTrue(a.id != b.id, "构造失败：两快照应落在不同 pageId")
        WorldMap.noteEdge(bundle, a.id, b.id, "tap", "共用页头")
        WorldMap.noteEdge(bundle, b.id, c.id, "tap", "别的项")

        val (before, after) = WorldMap.cleanAndSave(bundle)
        assertEquals(3, before)
        assertTrue(after < before, "重复快照未合并：$before -> $after")

        val doc = WorldMap.load(bundle)
        val ids = doc.pages.keys
        assertTrue(doc.edges.all { it.from in ids && it.to in ids }, "清洗后留下悬空边")
        assertTrue(doc.backStack.all { it in ids }, "backStack 指向已合并页")
        // 手势噪声不该留在 item 里
        assertTrue(
            doc.pages.values.none { p -> p.items.any { SceneSignature.isNoiseLabel(it.label) } },
            "噪声 item 未被剔除",
        )
    }

    /**
     * 健康快照：必须能识别出重复页与垃圾 item，供 doctor 提示清洗。
     * 这也是「膨胀数据可被发现」的守卫，避免再悄悄涨回 20MB+。
     */
    @Test
    fun healthDetectsDuplicatesAndJunk() {
        WorldMap.clear(bundle)
        // 两页归一后同内容（数字不同）
        WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        WorldMap.observePage(bundle, flat("共用页头", "订单 2002"), pushIfNew = true)
        WorldMap.flushAll() // 写合并模式下需 flush 才有磁盘体积
        val h = WorldMap.health(bundle)
        assertEquals(2, h.pages)
        assertEquals(1, h.duplicatePages, "应识别出 1 个重复页")
        assertEquals(0, h.junkItems, "写入入口已挡噪声，不该有垃圾 item")
        assertTrue(h.bytes > 0)
    }

    /** 清洗后健康快照应干净。 */
    @Test
    fun healthCleanAfterCleanAndSave() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        WorldMap.observePage(bundle, flat("共用页头", "订单 2002"), pushIfNew = true)
        WorldMap.cleanAndSave(bundle)
        val h = WorldMap.health(bundle)
        assertEquals(1, h.pages)
        assertEquals(0, h.duplicatePages)
        assertFalse(h.bloatedPages)
    }

    /** 内容不同的页必须保留，不能被误合并。 */
    @Test
    fun cleanKeepsDistinctPages() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("页面甲", "甲按钮"), pushIfNew = true)
        WorldMap.observePage(bundle, flat("页面乙", "乙按钮"), pushIfNew = true)
        val (before, after) = WorldMap.cleanAndSave(bundle)
        assertEquals(2, before)
        assertEquals(2, after, "内容不同的页被误合并了")
    }

    /**
     * 写入路径**不许**自动剪枝：地图是跨会话资产，隐式瘦身会静默丢历史。
     * 剪枝只允许 `pruneAndSave`（显式命令）触发，且不留悬空边。
     */
    @Test
    fun writePathDoesNotPruneButExplicitPruneDoes() {
        WorldMap.clear(bundle)
        // 用小上限验证机制本身，避免写几千页拖慢用例
        val cap = 40
        val n = cap + 20
        repeat(n) { i ->
            WorldMap.observePage(bundle, shelf("场景$i", topY = 100 + i), pushIfNew = true)
        }
        assertEquals(
            n,
            WorldMap.load(bundle).pages.size,
            "普通写入不得剪枝（会静默丢掉已学历史）",
        )

        val (before, after) = WorldMap.pruneAndSave(bundle, maxPages = cap)
        assertEquals(n, before)
        assertTrue(after <= cap, "显式剪枝应裁到上限内，实际 $after")
        val doc = WorldMap.load(bundle)
        assertTrue(doc.pages.size <= cap)
        val ids = doc.pages.keys
        assertTrue(doc.edges.all { it.from in ids && it.to in ids }, "剪枝后不应留下悬空边")
        assertTrue(doc.backStack.all { it in ids }, "backStack 不应指向已剪页")
    }

    /**
     * 验标地标优先：同一批页面元素，只要 --expect 命中的地标不同，就应被认成不同页面。
     * 这是「拿必做操作的结果当身份」而非「从 dump 里猜」的核心。
     */
    @Test
    fun verifiedLandmarkBecomesPageIdentity() {
        WorldMap.clear(bundle)
        // 两页元素相近但内容不同；以各自验标地标为身份 → 必须落在不同场景
        val a = WorldMap.observePage(bundle, flat("订单号", "去支付"), landmark = "订单详情页")
        val b = WorldMap.observePage(bundle, flat("新增地址", "保存"), landmark = "收货地址管理页")
        val doc = WorldMap.load(bundle)
        assertNotEquals(
            doc.pages[a.id]!!.sceneId,
            doc.pages[b.id]!!.sceneId,
            "验标地标不同却算了同一场景：地标未被用作身份",
        )
        assertEquals(listOf("landmark:订单详情页"), doc.pages[a.id]!!.landmarkKeys)
    }

    /**
     * 同一屏可以用不同 --expect 确认（expect 是子串匹配），
     * 地标证据应累积，而不是后来者覆盖先前者。
     */
    @Test
    fun multipleLandmarksAccumulateOnSamePage() {
        WorldMap.clear(bundle)
        val same = flat("订单号", "去支付")
        val a = WorldMap.observePage(bundle, same, landmark = "订单详情页")
        WorldMap.observePage(bundle, same, landmark = "去支付")
        val page = WorldMap.load(bundle).pages[a.id]!!
        assertEquals(
            listOf("landmark:订单详情页", "landmark:去支付"),
            page.landmarkKeys,
            "先后两个地标都应留下证据",
        )
        // 已有地标身份不被后来的地标改写（首见者定身份，避免同一页反复换 scene）
        assertEquals("landmark:订单详情页", page.landmarkKeys.first())
    }

    /** 地标身份要能落盘并读回。 */
    @Test
    fun landmarkSceneSurvivesRoundTrip() {
        WorldMap.clear(bundle)
        val p = WorldMap.observePage(bundle, flat("共用头部"), landmark = "订单详情页")
        val doc = WorldMap.load(bundle)
        val page = doc.pages[p.id]!!
        assertTrue(page.landmarkScene, "应记录该页身份来自验标地标")
        // 写合并模式下需先 flush 才落盘；load() 读到的是内存态
        WorldMap.flushAll()
        // 落盘已是 gzip（schema/3）：在测试侧解压后断言字段真进了文件，
        // 不给生产代码加任何 ForTest 后门
        val onDisk = java.util.zip.GZIPInputStream(
            Files.newInputStream(WorldMap.pathFor(bundle)),
        ).use { it.readAllBytes().toString(Charsets.UTF_8) }
        assertTrue(onDisk.contains("landmarkScene"), "landmarkScene 应写进档")
        assertTrue(onDisk.contains(page.sceneId), "地标派生的 sceneId 应写进档")
    }

    /**
     * 泛词地标必须被拒：`back` 实测覆盖 67% 页面，拿它当身份会把大半个 App 并成一个场景。
     */
    @Test
    fun genericLandmarkRejectedAsIdentity() {
        WorldMap.clear(bundle)
        // 造出「back 遍布多数页面」的实况：每页都有 back，外加各自的专属入口
        repeat(WorldMap.GENERIC_LANDMARK_MIN_SAMPLE + 4) { i ->
            WorldMap.observePage(
                bundle,
                listOf(nodeAt("back", 80), nodeAt("页面$i 专属入口", 200 + i * 200)),
                pushIfNew = true,
            )
        }
        val generic = WorldMap.observePage(bundle, listOf(nodeAt("back", 80)), landmark = "back")
        val page = WorldMap.load(bundle).pages[generic.id]!!
        assertFalse(
            page.landmarkScene,
            "泛词 back 不该被当成页面身份（会并掉大部分页面）",
        )
    }

    /** 有地标身份的页，不应被后续「无地标的取样」改掉场景。 */
    @Test
    fun landmarkIdentityNotOverwrittenByPlainSampling() {
        WorldMap.clear(bundle)
        val p = WorldMap.observePage(bundle, flat("共用头部", "列表项"), landmark = "订单详情页")
        val landmarkScene = WorldMap.load(bundle).pages[p.id]!!.sceneId
        // 再来一轮没有 --expect 的观测
        WorldMap.observePage(bundle, flat("共用头部", "列表项"))
        assertEquals(
            landmarkScene,
            WorldMap.load(bundle).pages[p.id]!!.sceneId,
            "地标身份被无地标取样覆盖了",
        )
    }

    /**
     * 构造带静态骨架 + 滚动区的页树：顶部标题栏固定，列表内容可滚。
     * 用于验证「剥离滚动区后，不同滚动位置共享同一身份」。
     */
    private fun pagedTree(header: String, body: List<String>): List<LayoutNode> {
        val headerNode = LayoutNode(
            text = header, id = null, type = "Navigation", description = null,
            bounds = Rect(0, 0, 1280, 200),
            clickable = false, enabled = true, visible = true,
            bundleName = null, depth = 0,
        )
        val listItems = body.mapIndexed { i, t ->
            LayoutNode(
                text = t, id = null, type = "ListItem", description = null,
                bounds = Rect(0, 300 + i * 300, 1280, 500 + i * 300),
                clickable = true, enabled = true, visible = true,
                bundleName = null, depth = 2,
            )
        }
        val list = LayoutNode(
            text = null, id = null, type = "List", description = null,
            bounds = Rect(0, 240, 1280, 2700),
            clickable = false, enabled = true, visible = true,
            bundleName = null, depth = 1,
            scrollable = true,
            children = listItems,
        )
        return listOf(headerNode, list)
    }

    /**
     * 坐标作废必须真的生效，且在「再次看见该元素」之前不许复活。
     *
     * 原实现只把 centerX/Y 置 null，而 mergeItems 用 `n.centerX ?: o.centerX`
     * 把旧值补回来 —— 作废在下一轮 observePage 就失效了，于是持续点同一个坏坐标。
     * 这条用例就是钉住这个回归。
     */
    @Test
    fun invalidatedCoordsStayInvalidUntilReseen() {
        WorldMap.clear(bundle)
        val page = WorldMap.observePage(bundle, flat("共用页头", "坏按钮"), pushIfNew = true)
        // coordsStable 需要「本页被观测过 >= STABLE_MIN_OBSERVATIONS 次」（无同场景对照时）。
        // 再 observe 一次凑够次数，否则 findMapTap 因不稳定直接返回 null，测不到墓碑逻辑。
        WorldMap.observePage(bundle, flat("共用页头", "坏按钮"))

        // 前提：一开始坐标可用
        assertNotNull(WorldMap.findMapTap(bundle, "坏按钮", page.id), "初始应有地图坐标")

        WorldMap.invalidateItemCoords(bundle, page.id, "坏按钮")
        assertNull(WorldMap.findMapTap(bundle, "坏按钮", page.id), "作废后不该再给出坐标")

        // 关键：再 observe 同一页（内容不变，仍在原位）——旧坐标不得被 merge 捞回来
        WorldMap.observePage(bundle, flat("共用页头", "坏按钮"))
        assertNull(
            WorldMap.findMapTap(bundle, "坏按钮", page.id),
            "新一轮 observePage 不得让作废的坐标复活（mergeItems 补位 bug）",
        )
    }

    /** 作废之后若元素被重新真正看见（新坐标写入），应恢复可用。 */
    @Test
    fun invalidatedCoordsRecoverAfterReseenWithNewPosition() {
        WorldMap.clear(bundle)
        val page = WorldMap.observePage(bundle, flat("共用页头", "会动的按钮"), pushIfNew = true)
        WorldMap.invalidateItemCoords(bundle, page.id, "会动的按钮")
        assertNull(WorldMap.findMapTap(bundle, "会动的按钮", page.id))

        // 元素被重新观测到（拿到新坐标）→ 墓碑应被推翻
        Thread.sleep(2) // 保证 lastSeenMs 严格晚于 coordsInvalidatedAt
        WorldMap.observePage(
            bundle,
            listOf(
                nodeAt("共用页头", 30),
                nodeAt("会动的按钮", 640),
            ),
        )
        assertNotNull(
            WorldMap.findMapTap(bundle, "会动的按钮", page.id),
            "重新看见后坐标应恢复可用",
        )
    }

    private fun nodeAt(text: String, top: Int) = LayoutNode(
        text = text, id = null, type = "Button", description = null,
        bounds = Rect(10, top, 200, top + 60),
        clickable = true, enabled = true, visible = true,
        bundleName = null, depth = 1,
    )

    /** 落盘是 gzip（magic 1f 8b），且解压后是合法 JSON、schema 已升到 /3。 */
    @Test
    fun persistsAsGzipWithCurrentSchema() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        WorldMap.flushAll()
        val p = WorldMap.pathFor(bundle)
        assertTrue(Files.isRegularFile(p), "应落盘到 ${p.fileName}")
        val raw = Files.readAllBytes(p)
        assertTrue(raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte(), "落盘应为 gzip")
        val text = java.util.zip.GZIPInputStream(Files.newInputStream(p))
            .use { it.readAllBytes().toString(Charsets.UTF_8) }
        assertTrue(text.contains(WorldMap.SCHEMA), "schema 应为 ${WorldMap.SCHEMA}")
    }

    /**
     * 冗余字段不再落盘：`pages[*].id` 与对象 key 重复、`pages[*].labels` 纯冗余，
     * 两项合计占旧档约 1.1MB（25%）。
     */
    @Test
    fun redundantFieldsNoLongerWritten() {
        WorldMap.clear(bundle)
        val page = WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        WorldMap.flushAll()
        val text = java.util.zip.GZIPInputStream(Files.newInputStream(WorldMap.pathFor(bundle)))
            .use { it.readAllBytes().toString(Charsets.UTF_8) }
        assertFalse(text.contains("\"labels\""), "labels 不该再落盘")
        // 对象 key 仍是 pageId，读回后 id 必须还原
        assertTrue(text.contains(page.id), "页仍应由 pageId 作 key")
        assertEquals(page.id, WorldMap.load(bundle).pages[page.id]?.id, "id 应从 key 还原")
    }

    /**
     * 原子写：`.tmp` 不得残留；且写完的档必须能被完整读回。
     * 这条守的是「进程被杀留下半截 JSON → load 静默当空地图 → 攒的地图没了」。
     */
    @Test
    fun atomicWriteLeavesNoTempAndRoundTrips() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("共用页头", "订单 1001"), pushIfNew = true)
        WorldMap.flushAll()
        val dir = WorldMap.pathFor(bundle).parent
        val leftovers = Files.list(dir).use { st ->
            st.filter { it.fileName.toString().startsWith(bundle) && it.fileName.toString().endsWith(".tmp") }
                .toList()
        }
        assertTrue(leftovers.isEmpty(), "不应残留临时文件：$leftovers")
        // 再写一次覆盖，仍应读得回完整内容
        WorldMap.observePage(bundle, flat("共用页头", "订单 2002"), pushIfNew = true)
        WorldMap.flushAll()
        assertEquals(2, WorldMap.load(bundle).pages.size)
    }

    private fun shelf(
        head: String,
        topY: Int,
        tail: String? = null,
        scene: String = "",
    ): List<LayoutNode> {
        val n = mutableListOf(
            LayoutNode(
                text = head, id = null, type = "Button", description = null,
                bounds = Rect(10, topY, 200, topY + 60),
                clickable = true, enabled = true, visible = true,
                bundleName = null, depth = 1,
            ),
        )
        if (tail != null) {
            n += LayoutNode(
                text = tail, id = null, type = "Text", description = null,
                bounds = Rect(10, topY + 400, 200, topY + 460),
                clickable = false, enabled = true, visible = true,
                bundleName = null, depth = 1,
            )
        }
        if (scene.isNotEmpty()) {
            n += LayoutNode(
                text = scene, id = null, type = "Text", description = null,
                bounds = Rect(10, topY - 50, 200, topY - 20),
                clickable = true, enabled = true, visible = true,
                bundleName = null, depth = 1,
            )
        }
        return n
    }
    /**
     * label 倒排索引必须随页内容增量失效（同 id 内容变了 → 该页 posting 必须更新）。
     *
     * 用 [WorldMap.learnScroll] 作为真实失效路径：它走 `page.copy(items = merged)`，
     * 即在**同一 pageId** 上改内容，正是索引 identity 判脏要覆盖的场景
     * （[WorldMap.observePage] 换内容会换 pageId，另测）。
     */
    @Test
    fun labelIndexFollowsSamePageContentChange() {
        WorldMap.clear(bundle)
        val home = WorldMap.observePage(bundle, flat("常驻标题"), sceneSwipes = 0)
        WorldMap.observePage(bundle, flat("常驻标题"), sceneSwipes = 0)
        assertNotNull(WorldMap.findMapTap(bundle, "常驻标题"), "索引建好后应命中")

        // 同一页学到一个新 label：pageId 不变，页对象被 copy
        WorldMap.learnScroll(bundle, home.id, 3, flat("常驻标题", "滚动后才出现的词"))
        assertNotNull(
            WorldMap.findMapTap(bundle, "滚动后才出现的词"),
            "同页新增 label 后索引必须立刻可命中",
        )
        // 原有 label 不能被误删
        assertNotNull(WorldMap.findMapTap(bundle, "常驻标题"), "旧 label 仍应命中")
    }

    /** 换 pageId（新页）时，旧页与新页各自独立命中，索引不得互相污染。 */
    @Test
    fun labelIndexSeparatesDistinctPages() {
        WorldMap.clear(bundle)
        val a = WorldMap.observePage(bundle, flat("甲页特征词"), sceneSwipes = 0)
        WorldMap.observePage(bundle, flat("甲页特征词"), sceneSwipes = 0)
        val b = WorldMap.observePage(bundle, flat("乙页特征词"), sceneSwipes = 0, pushIfNew = true)
        WorldMap.observePage(bundle, flat("乙页特征词"), sceneSwipes = 0)
        assertNotEquals(a.id, b.id)

        val tapA = assertNotNull(WorldMap.findMapTap(bundle, "甲页特征词"))
        val tapB = assertNotNull(WorldMap.findMapTap(bundle, "乙页特征词"))
        assertEquals(a.id, tapA.pageId, "甲页命中不得串到乙页")
        assertEquals(b.id, tapB.pageId, "乙页命中不得串到甲页")

        // 只改乙页内容：甲页命中必须保持不变（禁止整表失效）
        WorldMap.learnScroll(bundle, b.id, 2, flat("乙页特征词", "乙页新增词"))
        assertNotNull(WorldMap.findMapTap(bundle, "甲页特征词"), "未改动页必须仍在索引中")
        assertNotNull(WorldMap.findMapTap(bundle, "乙页新增词"))
    }

    /** planTo 与 findMapTap 共用索引，语义必须与逐项扫描一致（大小写不敏感、子串匹配）。 */
    @Test
    fun labelIndexMatchesSubstringAndCaseInsensitive() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("GPU Texture Leak Probe"), sceneSwipes = 0)
        WorldMap.observePage(bundle, flat("GPU Texture Leak Probe"), sceneSwipes = 0)
        // 子串（中缀）命中
        assertNotNull(WorldMap.planTo(bundle, "Texture"))
        assertNotNull(WorldMap.planTo(bundle, "eak Pro"))
        // 大小写不敏感
        assertNotNull(WorldMap.planTo(bundle, "gpu texture"))
        assertNotNull(WorldMap.planTo(bundle, "GPU TEXTURE"))
        // 不存在的子串必须为 null（候选集为空时不误命中）
        assertNull(WorldMap.planTo(bundle, "zzz-not-here"))
    }

    /**
     * `confirmPage(verified = true)` 必须清掉同位元素的墓碑。
     *
     * 这是 map-first + `--expect` 验标成功后的唯一复活出口：元素位置没变，
     * 所以 `mergeItems` 那条「位置变了才算复活」不会生效，只能靠这个分支。
     * 修复前该分支无调用点（死代码），验标通过后墓碑永久残留。
     */
    @Test
    fun verifiedConfirmClearsTombstoneInPlace() {
        WorldMap.clear(bundle)
        WorldMap.observePage(bundle, flat("会动的按钮"), sceneSwipes = 0)
        val page = WorldMap.observePage(bundle, flat("会动的按钮"), sceneSwipes = 0)

        WorldMap.invalidateItemCoords(bundle, page.id, "会动的按钮")
        val dead = WorldMap.load(bundle).pages.getValue(page.id).items
            .first { it.label == "会动的按钮" }
        assertTrue(dead.coordsInvalidatedAt > 0L, "应当先有墓碑")
        assertFalse(dead.coordsUsable, "带墓碑的坐标不可复用")

        // 未验标：只涨 seenCount，不得清墓碑
        WorldMap.confirmPage(bundle, page.id, verified = false)
        assertTrue(
            WorldMap.load(bundle).pages.getValue(page.id).items
                .first { it.label == "会动的按钮" }.coordsInvalidatedAt > 0L,
            "未验标不得清墓碑（『点完没崩』不等于『点对了』）",
        )

        // 验标通过：坐标被证实有效，必须复活
        WorldMap.confirmPage(bundle, page.id, verified = true)
        val alive = WorldMap.load(bundle).pages.getValue(page.id).items
            .first { it.label == "会动的按钮" }
        assertEquals(0L, alive.coordsInvalidatedAt, "验标通过必须清墓碑")
        assertTrue(alive.coordsUsable, "复活后坐标应可复用")
        // 坐标值本身不能被改写（没有新观测就不该动坐标）
        assertNotNull(alive.centerX)
        assertNotNull(alive.centerY)
    }

    /** 墓碑只清被验中的那一页，其他页不受影响（禁止整表复活）。 */
    @Test
    fun verifiedConfirmOnlyClearsItsOwnPage() {
        WorldMap.clear(bundle)
        val a = WorldMap.observePage(bundle, flat("甲页元素"), sceneSwipes = 0)
        WorldMap.observePage(bundle, flat("甲页元素"), sceneSwipes = 0)
        val b = WorldMap.observePage(bundle, flat("乙页元素"), sceneSwipes = 0, pushIfNew = true)
        WorldMap.observePage(bundle, flat("乙页元素"), sceneSwipes = 0)

        WorldMap.invalidateItemCoords(bundle, a.id, "甲页元素")
        WorldMap.invalidateItemCoords(bundle, b.id, "乙页元素")

        WorldMap.confirmPage(bundle, a.id, verified = true)

        assertTrue(
            WorldMap.load(bundle).pages.getValue(a.id).items
                .first { it.label == "甲页元素" }.coordsUsable,
            "被验中的页应复活",
        )
        assertFalse(
            WorldMap.load(bundle).pages.getValue(b.id).items
                .first { it.label == "乙页元素" }.coordsUsable,
            "未被验中的页不得被连带复活",
        )
    }

}
