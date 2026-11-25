package ohd.core

import ohd.core.actions.UiActions
import ohd.core.parsers.CrashParser
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fixture(name: String): String =
    Paths.get("src/test/resources/fixtures/$name").toFile().readText()

class CrashParserTest {

    @Test
    fun parsesJsCrash() {
        val r = CrashParser.parse(fixture("crash-jscrash.txt"), "jscrash-com.tencent.compose-1-20261016213908")
        assertEquals("jscrash", r.kind)
        assertEquals("SyntaxError", r.reason)
        assertEquals("com.tencent.compose", r.module)
        assertEquals("arm64-v8a", r.abi)
        assertTrue(r.buildInfo.contains("SGT-AL50"), "构建信息未解析：${r.buildInfo}")
        assertTrue(r.jsStack.isNotEmpty(), "JS 栈为空")
        assertTrue(r.nativeFrames.isNotEmpty(), "native 帧为空")
    }

    @Test
    fun framesCarryBuildIdAndSoName() {
        val r = CrashParser.parse(fixture("crash-jscrash.txt"))
        val libark = r.nativeFrames.first { it.soName == "libark_jsruntime.so" }
        assertEquals("/system/lib64/platformsdk/libark_jsruntime.so", libark.soPath)
        assertNotNull(libark.buildId, "buildId 缺失")
        assertEquals(32, libark.buildId!!.length)
        assertTrue(libark.pc > 0)
    }

    @Test
    fun parsesAppFreeze() {
        val r = CrashParser.parse(fixture("crash-appfreeze.txt"), "appfreeze-com.tencent.compose-1-20261016181424")
        assertEquals("appfreeze", r.kind)
        assertEquals("THREAD_BLOCK_6S", r.reason)
        assertTrue(r.nativeFrames.isNotEmpty(), "appfreeze 里应有 native 帧")
        assertTrue(r.threadHeaders.isNotEmpty(), "应解析出线程头 Tid:")
        assertTrue(r.pageHistory.isNotEmpty(), "应解析出页面轨迹")
    }

    @Test
    fun frameWithSymbolAndLongBuildId() {
        // 40 位 buildId + 内联符号 + 偏移
        val line = "#03 pc 0000000000040928 /system/lib64/chipset-sdk-sp/libsurface.z.so" +
            "(OHOS::BufferQueue::RequestBufferLocked(OHOS::BufferRequestConfig const&)+1772)" +
            "(ea3f413bfe7fd0f4fc193db36c0ec718)"
        val f = CrashParser.parseFrame(line)
        assertNotNull(f)
        assertEquals(3, f.index)
        assertEquals("libsurface.z.so", f.soName)
        assertEquals("ea3f413bfe7fd0f4fc193db36c0ec718", f.buildId)
        assertTrue(f.inlineSymbol!!.startsWith("OHOS::BufferQueue"))
    }

    @Test
    fun rejectsNonFrameLines() {
        assertEquals(null, CrashParser.parseFrame("at onCreate entry (x.ets:34:5)"))
        assertEquals(null, CrashParser.parseFrame(""))
    }
}

class UiActionsTest {

    @Test
    fun parsesScriptWithCommentsAndAliases() {
        val script = """
            # 登录流程
            tap 640 1200
            doubleTap 100 200
            longPress 100 300
            swipe 100 800 100 200 600
            key Back
            text hello world
            inputText 300 500 用户名
            sleep 500ms
            shot
        """.trimIndent()
        val actions = UiActions.parseScript(script)
        assertEquals(9, actions.size, "注释应被跳过")
        assertEquals(UiActions.Kind.TAP, actions[0].kind)
        assertEquals(UiActions.Kind.SHOT, actions.last().kind)
        // text 后的整段作为单个参数（含空格）
        assertEquals("hello world", actions.first { it.kind == UiActions.Kind.TEXT }.args[0])
        assertEquals(3, actions.first { it.kind == UiActions.Kind.INPUT_TEXT }.args.size)
    }

    @Test
    fun unknownActionReportsLineNumber() {
        val e = assertFailsWith<UiActions.ScriptError> {
            UiActions.parseScript("tap 1 2\nfoobar 3 4")
        }
        assertTrue(e.message!!.contains("第 2 行"), e.message)
    }

    @Test
    fun missingArgsRejected() {
        val e = assertFailsWith<UiActions.ScriptError> { UiActions.parseScript("tap 640") }
        assertTrue(e.message!!.contains("参数不足"), e.message)
    }

    @Test
    fun nonIntegerCoordinateRejected() {
        val e = assertFailsWith<UiActions.ScriptError> { UiActions.parseScript("tap abc 200") }
        assertTrue(e.message!!.contains("不是整数"), e.message)
    }

    @Test
    fun commandsMatchUitestSyntax() {
        val a = UiActions.parseScript(
            """
            tap 10 20
            doubleTap 30 40
            swipe 1 2 3 4 600
            key Back
            text hi there
            """.trimIndent(),
        )
        assertEquals("uitest uiInput click 10 20", UiActions.shellCommand(a[0]))
        assertEquals("uitest uiInput doubleClick 30 40", UiActions.shellCommand(a[1]))
        assertEquals("uitest uiInput swipe 1 2 3 4 600", UiActions.shellCommand(a[2]))
        assertEquals("uitest uiInput keyEvent Back", UiActions.shellCommand(a[3]))
        assertTrue(UiActions.shellCommand(a[4])!!.startsWith("uitest uiInput text 'hi there'"))
    }

    @Test
    fun singleQuoteEscaped() {
        assertEquals("'it'\\''s'", UiActions.quote("it's"))
    }
}
