package ohd.core

import ohd.core.caps.BundleCap
import ohd.platform.OhdPath
import ohd.platform.fs
import ohd.platform.toJavaPath
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * bundle 会话选择 / report / zip / 缺件 exit 4 —— 全离线，不碰设备。
 */
class BundleTest {
    private val tsFmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    @Test
    fun defaultSincePacksOnlyRecentSessions() {
        val cwd = fs.createTempDirectory("ohd-bundle-since")
        val device = Session.defaultRoot(cwd)
        fs.createDirectories(device)
        val old = writeSession(device, "shot", LocalDateTime.now().minusHours(5))
        val recent = writeSession(device, "shot", LocalDateTime.now().minusMinutes(30))

        val code = runBundle(cwd, Options(flags = emptySet()))
        assertEquals(ExitCode.ASSERT_FAILED, code, "缺必备件应得 4")

        val pkg = newestCase(cwd)
        val context = fs.readString(pkg.resolve("context.json"))
        assertTrue(context.contains(recent.fileName?.toString().orEmpty()), "应含近窗会话")
        assertFalse(context.contains(old.fileName?.toString().orEmpty()), "不应含 5h 前会话")
    }

    @Test
    fun latestOnlyPacksNewestSession() {
        val cwd = fs.createTempDirectory("ohd-bundle-latest")
        val device = Session.defaultRoot(cwd)
        fs.createDirectories(device)
        val a = writeSession(device, "shot-a", LocalDateTime.now().minusMinutes(20))
        val b = writeSession(device, "shot-b", LocalDateTime.now().minusMinutes(10))
        // fs 只有 lastModifiedMillis 读接口、没有设置入口：这里显式走 JVM 桥，
        // 保住「新旧会话 mtime 差 60s」这个原始构造条件，不改测试意图。
        Files.setLastModifiedTime(a.toJavaPath(), FileTime.fromMillis(System.currentTimeMillis() - 60_000))
        Files.setLastModifiedTime(b.toJavaPath(), FileTime.fromMillis(System.currentTimeMillis()))

        val code = runBundle(cwd, Options(flags = setOf("latest-only")))
        assertEquals(ExitCode.ASSERT_FAILED, code)

        val context = fs.readString(newestCase(cwd).resolve("context.json"))
        assertTrue(context.contains(b.fileName?.toString().orEmpty()))
        assertFalse(context.contains(a.fileName?.toString().orEmpty()))
    }

    @Test
    fun reportWritesMarkdownInsideCase() {
        val cwd = fs.createTempDirectory("ohd-bundle-report")
        val device = Session.defaultRoot(cwd)
        fs.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only", "report")))
        assertEquals(ExitCode.ASSERT_FAILED, code)
        assertTrue(fs.isRegularFile(newestCase(cwd).resolve("report.md")))
    }

    @Test
    fun zipContainsCompletenessBesideCase() {
        val cwd = fs.createTempDirectory("ohd-bundle-zip")
        val device = Session.defaultRoot(cwd)
        fs.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only", "zip")))
        assertEquals(ExitCode.ASSERT_FAILED, code)

        val pkg = newestCase(cwd)
        val zip = pkg.resolveSibling("${pkg.fileName?.toString().orEmpty()}.zip")
        assertTrue(fs.isRegularFile(zip), "zip 应在 case 旁：$zip")
        ZipFile(zip.toJavaPath().toFile()).use { z ->
            assertTrue(z.getEntry("COMPLETENESS.json") != null)
        }
    }

    @Test
    fun incompletePackageStillWrittenWithExit4() {
        val cwd = fs.createTempDirectory("ohd-bundle-exit4")
        val device = Session.defaultRoot(cwd)
        fs.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only")))
        assertEquals(ExitCode.ASSERT_FAILED, code)
        val pkg = newestCase(cwd)
        assertTrue(fs.isRegularFile(pkg.resolve("COMPLETENESS.json")))
        assertTrue(fs.isRegularFile(pkg.resolve("README.md")))
    }

    @Test
    fun acceptsListsNewFlags() {
        val a = BundleCap().accepts
        assertTrue("report" in a && "zip" in a && "latest-only" in a)
        assertTrue(BundleCap().usage.contains("2h"))
    }

    private fun runBundle(cwd: OhdPath, options: Options): Int {
        val ctx = RunContext(
            env = Env(),
            out = Out(jsonMode = true),
            options = options,
            cwd = cwd,
        )
        return BundleCap().run(ctx)
    }

    private fun newestCase(cwd: OhdPath): OhdPath {
        val cases = Session.evidenceRoot(cwd).resolve("cases")
        return fs.list(cases).use { s ->
            s.filter { fs.isDirectory(it) }
                .maxByOrNull { fs.lastModifiedMillis(it) }
                ?: throw NoSuchElementException("cases 下没有会话目录：$cases")
        }
    }

    private fun writeSession(deviceRoot: OhdPath, capability: String, at: LocalDateTime): OhdPath {
        val dir = deviceRoot.resolve("${tsFmt.format(at)}-$capability")
        fs.createDirectories(dir)
        // 最小 JPEG（SOI+EOI），足以被 copy 进 frames/
        fs.writeBytes(dir.resolve("001-shot.jpeg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val manifest = """
            {
              "schema": "ohd-manifest/1",
              "capability": "$capability",
              "device": {"model": "TestPhone", "softwareVersion": "OpenHarmony 1.0", "connectKey": "SERIAL12345678"},
              "artifacts": [{"path": "001-shot.jpeg", "kind": "shot"}]
            }
        """.trimIndent()
        fs.writeString(dir.resolve("manifest.json"), manifest)
        return dir
    }
}
