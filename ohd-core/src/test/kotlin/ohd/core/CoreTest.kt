package ohd.core

import java.nio.file.Files
import java.nio.file.Paths
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HdcArgvTest {

    @Test
    fun argvKeepsShellCommandAsSingleElement() {
        val hdc = Hdc(Paths.get("/tmp/hdc"), "DEV1")
        val argv = hdc.argv("shell", "uitest uiInput click 100 200")
        assertEquals(listOf("/tmp/hdc", "-t", "DEV1", "shell", "uitest uiInput click 100 200"), argv)
    }

    @Test
    fun argvWithoutDeviceHasNoTargetFlag() {
        val hdc = Hdc(Paths.get("/tmp/hdc"))
        assertEquals(listOf("/tmp/hdc", "list", "targets"), hdc.argv("list", "targets"))
    }

    @Test
    fun newlinesAreNormalized() {
        assertEquals("a\nb\nc", Hdc.normalizeNewlines("a\r\nb\rc"))
    }

    @Test
    fun devicePathUsesForwardSlashes() {
        val p = Platform.devicePath(Paths.get("C:/tmp/case.png"))
        assertTrue(!p.contains("\\"), "设备路径不应含反斜杠：$p")
    }
}

class SessionTest {

    @Test
    fun evidenceRootIsVisibleAndNamed() {
        val cwd = Paths.get("/tmp/proj")
        val root = Session.defaultRoot(cwd)
        assertEquals(Paths.get("/tmp/proj/ohd-evidence/device"), root)
        assertTrue(root.none { it.toString().startsWith(".") }, "证据目录不应隐藏：$root")
    }

    @Test
    fun recordScriptNamesDeviceAndDoesNotCollide() {
        val cwd = Files.createTempDirectory("ohd-rec")
        try {
            val ts = OffsetDateTime.parse("2026-10-19T15:33:00+08:00")
            val p = Session.recordScript("63R0226113004249", cwd, ts)
            assertEquals(
                cwd.resolve("ohd-evidence/record/record-63R0226113004249-20261019-153300.ohd"),
                p,
            )
            Files.createDirectories(p.parent)
            Files.writeString(p, "x")
            val q = Session.recordScript("63R0226113004249", cwd, ts)
            assertEquals(
                cwd.resolve("ohd-evidence/record/record-63R0226113004249-20261019-153300-2.ohd"),
                q,
            )
            assertTrue(Session.recordScript("a/b:c", cwd, ts).fileName.toString().startsWith("record-a_b_c-"))
        } finally {
            cwd.toFile().deleteRecursively()
        }
    }

    @Test
    fun sessionDirAndArtifactSequence() {
        val tmp = Files.createTempDirectory("ohd-test")
        try {
            val s = Session.create("trace", tmp)
            assertTrue(
                s.dir.fileName.toString().matches(Regex("\\d{8}-\\d{6}-trace")),
                "会话目录名异常：${s.dir.fileName}",
            )
            assertEquals("001-layout.json", s.nextPath("layout", "json").fileName.toString())
            assertEquals("002-shot.png", s.nextPath("shot", "png").fileName.toString())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun duplicateCapabilityGetsSuffix() {
        val tmp = Files.createTempDirectory("ohd-test")
        try {
            val ts = OffsetDateTime.now()
            val a = Session.create("x", tmp, timestamp = ts)
            val b = Session.create("x", tmp, timestamp = ts)
            assertNotEquals(a.dir, b.dir)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun manifestRecordsArtifactAndCommand() {
        val tmp = Files.createTempDirectory("ohd-test")
        try {
            val s = Session.create("probe", tmp)
            val p = s.nextPath("note", "txt")
            Session.writeText(p, "hello")
            s.register(p, "note")
            s.recordCommand(listOf("hdc", "shell", "echo hi"), 0, 12)
            val manifest = s.writeManifest(NullDevice)
            val text = Files.readString(manifest)
            assertTrue(text.contains("\"schema\""), "manifest 缺 schema")
            assertTrue(text.contains("hello") || text.contains("note.txt"), "manifest 未记录产物")
            assertTrue(text.contains("echo hi"), "manifest 未记录命令")
        } finally {
            tmp.toFile().deleteRecursively()
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

    @Test
    fun explicitHdcWins() {
        val env = Env(explicitHdc = "/opt/fake/hdc", sysenv = { null })
        assertEquals(Paths.get("/opt/fake/hdc"), env.hdc.path)
    }

    @Test
    fun envVariableUsedWhenNoArgument() {
        val env = Env(sysenv = { name -> if (name == "OHD_HDC") "/env/hdc" else null })
        assertEquals(Paths.get("/env/hdc"), env.hdc.path)
    }

    @Test
    fun sdkSubdirPickedByVersion() {
        val tmp = Files.createTempDirectory("ohd-sdk")
        try {
            for (v in listOf("9", "20", "12")) {
                val dir = tmp.resolve(v).resolve("toolchains")
                Files.createDirectories(dir)
                val hdc = dir.resolve("hdc")
                Files.writeString(hdc, "#!/bin/sh\nexit 0\n")
                hdc.toFile().setExecutable(true)
            }
            val env = Env(sysenv = { name -> if (name == "OHOS_SDK_HOME") tmp.toString() else null })
            val path = env.hdc.path
            assertTrue(path != null && path.toString().contains("/20/"), "应选版本号最大的 SDK：$path")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
