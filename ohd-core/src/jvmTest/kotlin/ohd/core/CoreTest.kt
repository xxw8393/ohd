package ohd.core

import ohd.platform.OhdPath
import ohd.platform.fs
import ohd.platform.toJavaPath
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HdcArgvTest {

    @Test
    fun argvKeepsShellCommandAsSingleElement() {
        val hdc = Hdc(OhdPath.of("/tmp/hdc"), "DEV1")
        val argv = hdc.argv("shell", "uitest uiInput click 100 200")
        assertEquals(listOf("/tmp/hdc", "-t", "DEV1", "shell", "uitest uiInput click 100 200"), argv)
    }

    @Test
    fun argvWithoutDeviceHasNoTargetFlag() {
        val hdc = Hdc(OhdPath.of("/tmp/hdc"))
        assertEquals(listOf("/tmp/hdc", "list", "targets"), hdc.argv("list", "targets"))
    }

    @Test
    fun newlinesAreNormalized() {
        assertEquals("a\nb\nc", Hdc.normalizeNewlines("a\r\nb\rc"))
    }

    @Test
    fun devicePathUsesForwardSlashes() {
        val p = Platform.devicePath(OhdPath.of("C:/tmp/case.png"))
        assertTrue(!p.contains("\\"), "设备路径不应含反斜杠：$p")
    }
}

class SessionTest {

    @Test
    fun evidenceRootIsVisibleAndNamed() {
        val cwd = OhdPath.of("/tmp/proj")
        val root = Session.defaultRoot(cwd)
        assertEquals(OhdPath.of("/tmp/proj/ohd-evidence/device"), root)
        assertTrue(
            root.toString().split('/', '\\').filter { it.isNotEmpty() }.none { it.startsWith(".") },
            "证据目录不应隐藏：$root",
        )
    }

    @Test
    fun recordScriptNamesDeviceAndDoesNotCollide() {
        val cwd = fs.createTempDirectory("ohd-rec")
        try {
            // 固定 epoch 让断言可复现；但 recordScript 用的是**本地时区**格式化，
            // 期望值必须按同一语义推导，写死 20261019-153300 只在 +08:00 机器上成立。
            val ts = 1_792_395_180_000L
            val stamp = ohd.platform.TimeFmt.format(ts, "yyyyMMdd-HHmmss")
            val p = Session.recordScript("63R0226113004249", cwd, ts)
            assertEquals(
                cwd.resolve("ohd-evidence/record/record-63R0226113004249-$stamp.ohd"),
                p,
            )
            p.parent?.let { fs.createDirectories(it) }
            fs.writeString(p, "x")
            val q = Session.recordScript("63R0226113004249", cwd, ts)
            assertEquals(
                cwd.resolve("ohd-evidence/record/record-63R0226113004249-$stamp-2.ohd"),
                q,
            )
            assertTrue(Session.recordScript("a/b:c", cwd, ts).fileName?.toString().orEmpty().startsWith("record-a_b_c-"))
        } finally {
            fs.deleteRecursively(cwd)
        }
    }

    @Test
    fun sessionDirAndArtifactSequence() {
        val tmp = fs.createTempDirectory("ohd-test")
        try {
            val s = Session.create("trace", tmp)
            assertTrue(
                s.dir.fileName?.toString().orEmpty().matches(Regex("\\d{8}-\\d{6}-trace")),
                "会话目录名异常：${s.dir.fileName}",
            )
            assertEquals("001-layout.json", s.nextPath("layout", "json").fileName?.toString())
            assertEquals("002-shot.png", s.nextPath("shot", "png").fileName?.toString())
        } finally {
            fs.deleteRecursively(tmp)
        }
    }

    @Test
    fun duplicateCapabilityGetsSuffix() {
        val tmp = fs.createTempDirectory("ohd-test")
        try {
            val ts = ohd.platform.Env.nowMillis()
            val a = Session.create("x", tmp, timestampMs = ts)
            val b = Session.create("x", tmp, timestampMs = ts)
            assertNotEquals(a.dir, b.dir)
        } finally {
            fs.deleteRecursively(tmp)
        }
    }

    @Test
    fun manifestRecordsArtifactAndCommand() {
        val tmp = fs.createTempDirectory("ohd-test")
        try {
            val s = Session.create("probe", tmp)
            val p = s.nextPath("note", "txt")
            Session.writeText(p, "hello")
            s.register(p, "note")
            s.recordCommand(listOf("hdc", "shell", "echo hi"), 0, 12)
            val manifest = s.writeManifest(NullDevice)
            val text = fs.readString(manifest)
            assertTrue(text.contains("\"schema\""), "manifest 缺 schema")
            assertTrue(text.contains("hello") || text.contains("note.txt"), "manifest 未记录产物")
            assertTrue(text.contains("echo hi"), "manifest 未记录命令")
        } finally {
            fs.deleteRecursively(tmp)
        }
    }

    private val NullDevice: DeviceInfo? = null
}

class PlatformTest {

    @Test
    fun cjkWidthCountedAsTwo() {
        assertEquals(2, Platform.displayWidth("中"))
        assertEquals(4, Platform.displayWidth("内存"))
        assertEquals(5, Platform.displayWidth("a内存"))
    }
}

class EnvDiscoveryTest {

    private val sep = java.io.File.separator

    @Test
    fun explicitHdcWins() {
        // 绝对路径要按宿主拼：Windows 上 "/opt/fake/hdc" 不是绝对路径
        val p = if (sep == "\\") "C:\\opt\\fake\\hdc" else "/opt/fake/hdc"
        val env = Env(explicitHdc = p, sysenv = { null })
        assertEquals(OhdPath.of(p), env.hdc.path)
    }

    @Test
    fun envVariableUsedWhenNoArgument() {
        val p = if (sep == "\\") "C:\\env\\hdc" else "/env/hdc"
        val env = Env(sysenv = { name -> if (name == "OHD_HDC") p else null })
        assertEquals(OhdPath.of(p), env.hdc.path)
    }

    @Test
    fun sdkSubdirPickedByVersion() {
        val tmp = fs.createTempDirectory("ohd-sdk")
        try {
            for (v in listOf("9", "20", "12")) {
                val dir = tmp.resolve(v).resolve("toolchains")
                fs.createDirectories(dir)
                // 产品在 Windows 上找的是 hdc.exe（Env.hdc 用 Platform.exeSuffix），
                // 只造 "hdc" 会让这条用例在 Windows 上永远找不到候选。
                val hdc = dir.resolve("hdc" + Platform.exeSuffix)
                fs.writeString(hdc, "#!/bin/sh\nexit 0\n")
                hdc.toJavaPath().toFile().setExecutable(true)
            }
            val env = Env(sysenv = { name -> if (name == "OHOS_SDK_HOME") tmp.toString() else null })
            val path = env.hdc.path
            assertTrue(
                path != null && path.toString().contains("${sep}20${sep}"),
                "应选版本号最大的 SDK：$path",
            )
        } finally {
            fs.deleteRecursively(tmp)
        }
    }
}
