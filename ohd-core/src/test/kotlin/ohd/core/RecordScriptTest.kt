package ohd.core

import ohd.core.actions.RecordScript
import ohd.core.actions.ReplayAim
import ohd.core.actions.UiActions
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.Rect
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordScriptTest {

    private fun fixture(name: String): String =
        Paths.get("src/test/resources/fixtures/$name").toFile().readText()

    @Test
    fun deviceFlingWithoutBundleIsRefused() {
        val e = assertFailsWith<RecordScript.Rejected> { RecordScript.translate(fixture("uirecord-fling.json")) }
        assertTrue(e.message!!.contains("Ability") || e.message!!.contains("包名"), e.message)
    }

    @Test
    fun swipeWithAnEmptyBundleKeepsTheLaterClickBundle() {
        val raw = """
            {"ABILITY":"","BUNDLE":"","OP_TYPE":"fling","fingerNumber":"1","fingerList":[{"W1_Text":"image-cat","W1_Type":"Button","X_POSI":"323","Y_POSI":"1505","X2_POSI":"316","Y2_POSI":"1656"}]}
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"click","fingerNumber":"1","fingerList":[{"W1_Text":"image-cat","W1_Type":"Button","X_POSI":"284","Y_POSI":"1539","X2_POSI":"284","Y2_POSI":"1539"}]}
        """.trimIndent()
        val t = RecordScript.translate(raw)
        assertEquals("com.tencent.compose", t.bundle)
        assertEquals("EntryAbility", t.ability)
        assertEquals(2, t.steps)
        assertTrue(t.script.lineSequence().any { it == "launch com.tencent.compose EntryAbility" }, t.script)
        assertTrue(t.script.contains("fling 323 1505 316 1656"), t.script)
        assertTrue(t.script.contains("tap 284 1539 text=image-cat type=Button"), t.script)
    }

    @Test
    fun foregroundLineSuppliesBundleWhenEventsOmitIt() {
        val raw = """
            Current ForAbility :com.tencent.compose, EntryAbility
            {"ABILITY":"","BUNDLE":"","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"400","Y_POSI":"1800","X2_POSI":"400","Y2_POSI":"900"}]}
        """.trimIndent()
        val t = RecordScript.translate(raw)
        assertEquals("com.tencent.compose", t.bundle)
        assertEquals("EntryAbility", t.ability)
        assertTrue(t.script.lineSequence().any { it == "launch com.tencent.compose EntryAbility" }, t.script)
        assertEquals(1, t.steps)
    }

    @Test
    fun explicitBundleAndAbilityFillAnEmptyRecording() {
        val t = RecordScript.translate(fixture("uirecord-fling.json"), "com.tencent.compose", "EntryAbility")
        assertEquals("com.tencent.compose", t.bundle)
        assertTrue(t.script.lineSequence().any { it == "launch com.tencent.compose EntryAbility" }, t.script)
    }

    @Test
    fun clickBecomesLaunchPlusTapWithIdentity() {
        val raw = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"click","VELO":"0.000000","fingerNumber":"1","fingerList":[{"W1_ID":"btn_login","W1_Text":"登录","W1_Type":"Button","X_POSI":"47","Y_POSI":"301","X2_POSI":"47","Y2_POSI":"301"}]}
        """.trimIndent()
        val t = RecordScript.translate(raw)
        assertEquals("com.tencent.compose", t.bundle)
        assertEquals("EntryAbility", t.ability)
        assertEquals(1, t.steps)
        assertTrue(t.script.lineSequence().any { it == "launch com.tencent.compose EntryAbility" }, t.script)
        assertTrue(t.script.contains("tap 47 301 id=btn_login text=登录 type=Button"), t.script)
    }

    @Test
    fun flingKeepsVelocityAndDropsEmptyIdentity() {
        val raw = fixture("uirecord-fling.json").replace("\"ABILITY\":\"\"", "\"ABILITY\":\"EntryAbility\"")
            .replace("\"BUNDLE\":\"\"", "\"BUNDLE\":\"com.tencent.compose\"")
        val t = RecordScript.translate(raw)
        val line = t.script.lines().first { it.startsWith("fling ") }
        assertEquals("fling 400 1800 400 900 450", line)
    }

    @Test
    fun targetIsTheWidgetUitestPrintsNotTheEmptyStartNode() {
        val click = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"click","fingerNumber":"1","fingerList":[{"W1_ID":"","W1_Text":"","W1_Type":"CustomFrameNode","W2_ID":"","W2_Text":"carousel","W2_Type":"Button","X_POSI":"650","Y_POSI":"1514","X2_POSI":"650","Y2_POSI":"1514"}]}
        """.trimIndent()
        assertEquals(
            "tap 650 1514 text=carousel type=Button",
            RecordScript.translate(click).script.lines().first { it.startsWith("tap ") },
        )
        val fling = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"fling","VELO":"472.287550","fingerNumber":"1","fingerList":[{"W1_ID":"","W1_Text":"","W1_Type":"Text","W2_ID":"","W2_Text":"栅格 r12c2","W2_Type":"ListItem","X_POSI":"1279","Y_POSI":"1455","X2_POSI":"974","Y2_POSI":"1542"}]}
        """.trimIndent()
        val line = RecordScript.translate(fling).script.lines().first { it.startsWith("fling ") }
        assertEquals("fling 1279 1455 974 1542 472", line)
        val parsed = UiActions.parseScript(line).single()
        assertNull(parsed.toText)
        assertEquals(false, parsed.hasIdentity)
    }

    @Test
    fun multiFingerIsSkippedAndEmptyScriptRefused() {
        val raw = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"2","fingerList":[{"X_POSI":"1","Y_POSI":"2","X2_POSI":"3","Y2_POSI":"4"}]}
        """.trimIndent()
        val e = assertFailsWith<RecordScript.Rejected> { RecordScript.translate(raw) }
        assertTrue(e.message!!.contains("可回放"), e.message)
    }

    @Test
    fun swipeDropsTheWidgetUnderTheFinger() {
        val raw = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"swipe","VELO":"0.000981","fingerNumber":"1","fingerList":[{"W1_ID":"","W1_Text":"混排列表","W1_Type":"Button","W2_ID":"","W2_Text":"","W2_Type":"List","X_POSI":"368","Y_POSI":"1926","X2_POSI":"590","Y2_POSI":"891"}]}
        """.trimIndent()
        val line = RecordScript.translate(raw).script.lines().first { it.startsWith("swipe ") }
        assertEquals("swipe 368 1926 590 891", line)
    }

    @Test
    fun rightEdgeInwardFlingBecomesKeyBack() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"fling","VELO":"541.7","fingerNumber":"1","fingerList":[{"W1_Text":"混排图片显示","W1_Type":"Button","X_POSI":"1271","Y_POSI":"1308","X2_POSI":"826","Y2_POSI":"1387"}]}
        """.trimIndent()
        val script = RecordScript.translate(raw).script
        assertTrue(script.contains("# 边缘返回 1271,1308 → 826,1387"), script)
        assertTrue(script.lineSequence().any { it == "key Back" }, script)
        assertTrue(script.lines().none { it.startsWith("fling ") || it.contains("混排图片显示") }, script)
    }

    @Test
    fun leftEdgeInwardSwipeBecomesKeyBack() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"12","Y_POSI":"900","X2_POSI":"400","Y2_POSI":"910"}]}
        """.trimIndent()
        val script = RecordScript.translate(raw).script
        assertTrue(script.contains("# 边缘返回 12,900 → 400,910"), script)
        assertTrue(script.lineSequence().any { it == "key Back" }, script)
    }

    @Test
    fun rightEdgeOutwardSwipeStaysASwipe() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"1270","Y_POSI":"1000","X2_POSI":"1278","Y2_POSI":"1004"}]}
        """.trimIndent()
        val line = RecordScript.translate(raw).script.lines().first { it.startsWith("swipe ") || it.startsWith("key ") }
        assertEquals("swipe 1270 1000 1278 1004", line)
    }

    @Test
    fun verticalSwipeOnTheRightEdgeStaysASwipe() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"1270","Y_POSI":"2000","X2_POSI":"1200","Y2_POSI":"800"}]}
        """.trimIndent()
        val line = RecordScript.translate(raw).script.lines().first { it.startsWith("swipe ") || it.startsWith("key ") }
        assertEquals("swipe 1270 2000 1200 800", line)
    }

    @Test
    fun upwardSwipeFromTheBottomStaysASwipe() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"640","Y_POSI":"2800","X2_POSI":"700","Y2_POSI":"1800"}]}
        """.trimIndent()
        val line = RecordScript.translate(raw).script.lines().first { it.startsWith("swipe ") || it.startsWith("key ") }
        assertEquals("swipe 640 2800 700 1800", line)
    }

    @Test
    fun clickOnTheRightEdgeStaysAClick() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"click","fingerNumber":"1","fingerList":[{"W2_Text":"edge","W2_Type":"Button","X_POSI":"1270","Y_POSI":"400","X2_POSI":"1270","Y2_POSI":"400"}]}
        """.trimIndent()
        val line = RecordScript.translate(raw).script.lines().first { it.startsWith("tap ") || it.startsWith("key ") }
        assertEquals("tap 1270 400 text=edge type=Button", line)
    }

    @Test
    fun edgeMarginIsFortyPixels() {
        fun swipe(x: Int) = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.example","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"$x","Y_POSI":"1000","X2_POSI":"800","Y2_POSI":"1010"}]}
        """.trimIndent()
        assertTrue(RecordScript.translate(swipe(1240)).script.lineSequence().any { it == "key Back" })
        assertTrue(RecordScript.translate(swipe(1239)).script.lines().any { it.startsWith("swipe ") })
    }

    @Test
    fun outlineNamesTheRecordedSteps() {
        val raw = """
            windowBounds : (0,0,1280,2832)
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"click","fingerNumber":"1","fingerList":[{"W2_Text":"image-cat","W2_Type":"Button","X_POSI":"304","Y_POSI":"1524","X2_POSI":"304","Y2_POSI":"1524"}]}
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"swipe","fingerNumber":"1","fingerList":[{"X_POSI":"352","Y_POSI":"1810","X2_POSI":"650","Y2_POSI":"863"}]}
            {"ABILITY":"EntryAbility","BUNDLE":"com.tencent.compose","OP_TYPE":"fling","VELO":"541","fingerNumber":"1","fingerList":[{"X_POSI":"1271","Y_POSI":"1308","X2_POSI":"826","Y2_POSI":"1387"}]}
        """.trimIndent()
        val t = RecordScript.translate(raw)
        assertEquals(listOf("点 image-cat", "滑动", "返回"), t.outline)
        assertEquals("1 点 image-cat / 2 滑动 / 3 返回", RecordScript.stepTable(t.outline))
    }

    @Test
    fun textWithSpaceIsQuotedAndReplayCanParse() {
        val raw = """
            {"ABILITY":"EntryAbility","BUNDLE":"com.example.app","OP_TYPE":"doubleClick","fingerNumber":"1","fingerList":[{"W1_ID":"","W1_Text":"hello world","W1_Type":"Text","X_POSI":"10","Y_POSI":"20","X2_POSI":"10","Y2_POSI":"20"}]}
        """.trimIndent()
        val script = RecordScript.translate(raw).script
        val actions = UiActions.parseScript(script)
        assertEquals(UiActions.Kind.LAUNCH, actions[0].kind)
        assertEquals(listOf("com.example.app", "EntryAbility"), actions[0].args)
        assertEquals(UiActions.Kind.DOUBLE_TAP, actions[1].kind)
        assertEquals("hello world", actions[1].text)
        assertNull(actions[1].id)
        assertEquals("uitest uiInput doubleClick 10 20", UiActions.shellCommand(actions[1]))
    }
}

class ReplayAimTest {

    private fun node(id: String?, text: String?, type: String?, left: Int, top: Int, right: Int, bottom: Int) =
        LayoutNode(text, id, type, null, Rect(left, top, right, bottom), true, true, true, null, 0)

    @Test
    fun uniqueIdWinsOverRecordedPoint() {
        val nodes = listOf(node("btn_login", "登录", "Button", 10, 20, 110, 80))
        val d = ReplayAim.decide(nodes, "btn_login", "登录", "Button")
        assertEquals(ReplayAim.Hit(60, 50, "id"), d)
    }

    @Test
    fun textAndTypeWhenIdMissing() {
        val nodes = listOf(
            node(null, "登录", "Text", 0, 0, 10, 10),
            node(null, "登录", "Button", 0, 100, 40, 140),
        )
        assertEquals(ReplayAim.Hit(20, 120, "text"), ReplayAim.decide(nodes, null, "登录", "Button"))
    }

    @Test
    fun duplicateTextFailsClosed() {
        val nodes = listOf(
            node(null, "确定", "Button", 0, 0, 10, 10),
            node(null, "确定", "Button", 20, 20, 40, 40),
        )
        assertEquals(ReplayAim.Ambiguous(2), ReplayAim.decide(nodes, null, "确定", "Button"))
    }

    @Test
    fun missingIdDoesNotFallBackToCoordinates() {
        val nodes = listOf(node("other", "其他", "Button", 0, 0, 10, 10))
        assertEquals(ReplayAim.NotFound("id"), ReplayAim.decide(nodes, "btn_login", "登录", "Button"))
    }

    @Test
    fun emptyIdentityIsCoordinateFallback() {
        assertEquals(ReplayAim.Fallback, ReplayAim.decide(emptyList(), null, null, "__Common__"))
    }

    @Test
    fun clickPollsUntilTheWidgetAppears() {
        var calls = 0
        var clock = 0L
        val d = ReplayAim.poll(
            sample = {
                calls++
                if (calls < 3) ReplayAim.NotFound("text") else ReplayAim.Hit(10, 20, "text")
            },
            deadlineMs = 5_000,
            now = { clock },
            sleep = { clock += it },
        )
        assertEquals(ReplayAim.Hit(10, 20, "text"), d)
        assertEquals(3, calls)
    }

    @Test
    fun clickStopsAtTheDeadline() {
        var calls = 0
        var clock = 0L
        val d = ReplayAim.poll(
            sample = {
                calls++
                ReplayAim.NotFound("text")
            },
            deadlineMs = 400,
            now = { clock },
            sleep = { clock += it },
        )
        assertEquals(ReplayAim.NotFound("text"), d)
        assertEquals(3, calls)
    }

    @Test
    fun alreadyExpiredDeadlineSamplesOnce() {
        var calls = 0
        val d = ReplayAim.poll(
            sample = {
                calls++
                ReplayAim.NotFound("id")
            },
            deadlineMs = 0,
            now = { 0 },
            sleep = { error("不应再等") },
        )
        assertEquals(ReplayAim.NotFound("id"), d)
        assertEquals(1, calls)
    }

    @Test
    fun ambiguousMatchDoesNotKeepPolling() {
        var calls = 0
        val d = ReplayAim.poll(
            sample = {
                calls++
                ReplayAim.Ambiguous(2)
            },
            deadlineMs = 5_000,
            now = { 0 },
            sleep = { error("不应再等") },
        )
        assertEquals(ReplayAim.Ambiguous(2), d)
        assertEquals(1, calls)
    }

    @Test
    fun flingEndFollowsTheTargetWidget() {
        val action = UiActions.parseScript("""fling 1279 1455 974 1542 472 totext="栅格 r12c2" totype=ListItem""").single()
        val moved = ReplayAim.retargetEnd(action, 1000, 1600)
        assertEquals(listOf("1305", "1513", "1000", "1600", "472"), moved.args)
    }
}

class ForegroundBundleParseTest {

    @Test
    fun abilityRecordForegroundIsTheFollowingBundleNotThePreviousOne() {
        val raw = """
            bundle name [com.ohos.medialibrary.medialibrarydata]
            AbilityRecord ID #59   state #FOREGROUND   start time [15595]
            main name [EngineServiceAbility]
            bundle name [com.ohos.sceneboard]
            app state #FOREGROUND
        """.trimIndent()
        assertEquals(listOf("com.ohos.sceneboard"), DeviceOps.foregroundBundlesIn(raw))
    }

    @Test
    fun pageAbilityForegroundIsOnTheFollowingLines() {
        val raw = """
            AbilityRecord ID #35438
              bundle name [com.tencent.compose]
              ability type [PAGE]
              state #FOREGROUND  start time [929922285]
              app state #FOREGROUND
        """.trimIndent()
        assertEquals(listOf("com.tencent.compose"), DeviceOps.foregroundBundlesIn(raw))
    }
}
