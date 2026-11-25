package ohd.core

import ohd.core.caps.BundleCap
import java.nio.file.Files
import java.nio.file.Path
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
        val cwd = Files.createTempDirectory("ohd-bundle-since")
        val device = Session.defaultRoot(cwd)
        Files.createDirectories(device)
        val old = writeSession(device, "shot", LocalDateTime.now().minusHours(5))
        val recent = writeSession(device, "shot", LocalDateTime.now().minusMinutes(30))

        val code = runBundle(cwd, Options(flags = emptySet()))
        assertEquals(ExitCode.ASSERT_FAILED, code, "缺必备件应得 4")

        val pkg = newestCase(cwd)
        val context = Files.readString(pkg.resolve("context.json"))
        assertTrue(context.contains(recent.fileName.toString()), "应含近窗会话")
        assertFalse(context.contains(old.fileName.toString()), "不应含 5h 前会话")
    }

    @Test
    fun latestOnlyPacksNewestSession() {
        val cwd = Files.createTempDirectory("ohd-bundle-latest")
        val device = Session.defaultRoot(cwd)
        Files.createDirectories(device)
        val a = writeSession(device, "shot-a", LocalDateTime.now().minusMinutes(20))
        val b = writeSession(device, "shot-b", LocalDateTime.now().minusMinutes(10))
        Files.setLastModifiedTime(a, FileTime.fromMillis(System.currentTimeMillis() - 60_000))
        Files.setLastModifiedTime(b, FileTime.fromMillis(System.currentTimeMillis()))

        val code = runBundle(cwd, Options(flags = setOf("latest-only")))
        assertEquals(ExitCode.ASSERT_FAILED, code)

        val context = Files.readString(newestCase(cwd).resolve("context.json"))
        assertTrue(context.contains(b.fileName.toString()))
        assertFalse(context.contains(a.fileName.toString()))
    }

    @Test
    fun reportWritesMarkdownInsideCase() {
        val cwd = Files.createTempDirectory("ohd-bundle-report")
        val device = Session.defaultRoot(cwd)
        Files.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only", "report")))
        assertEquals(ExitCode.ASSERT_FAILED, code)
        assertTrue(Files.isRegularFile(newestCase(cwd).resolve("report.md")))
    }

    @Test
    fun zipContainsCompletenessBesideCase() {
        val cwd = Files.createTempDirectory("ohd-bundle-zip")
        val device = Session.defaultRoot(cwd)
        Files.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only", "zip")))
        assertEquals(ExitCode.ASSERT_FAILED, code)

        val pkg = newestCase(cwd)
        val zip = pkg.parent.resolve("${pkg.fileName}.zip")
        assertTrue(Files.isRegularFile(zip), "zip 应在 case 旁：$zip")
        ZipFile(zip.toFile()).use { z ->
            assertTrue(z.getEntry("COMPLETENESS.json") != null)
        }
    }

    @Test
    fun incompletePackageStillWrittenWithExit4() {
        val cwd = Files.createTempDirectory("ohd-bundle-exit4")
        val device = Session.defaultRoot(cwd)
        Files.createDirectories(device)
        writeSession(device, "shot", LocalDateTime.now())

        val code = runBundle(cwd, Options(flags = setOf("latest-only")))
        assertEquals(ExitCode.ASSERT_FAILED, code)
        val pkg = newestCase(cwd)
        assertTrue(Files.isRegularFile(pkg.resolve("COMPLETENESS.json")))
        assertTrue(Files.isRegularFile(pkg.resolve("README.md")))
    }

    @Test
    fun acceptsListsNewFlags() {
        val a = BundleCap().accepts
        assertTrue("report" in a && "zip" in a && "latest-only" in a)
        assertTrue(BundleCap().usage.contains("2h"))
    }

    private fun runBundle(cwd: Path, options: Options): Int {
        val ctx = RunContext(
            env = Env(),
            out = Out(jsonMode = true),
            options = options,
            cwd = cwd,
        )
        return BundleCap().run(ctx)
    }

    private fun newestCase(cwd: Path): Path {
        val cases = Session.evidenceRoot(cwd).resolve("cases")
        return Files.list(cases).use { s ->
            s.filter { Files.isDirectory(it) }
                .max(Comparator.comparingLong { Files.getLastModifiedTime(it).toMillis() })
                .orElseThrow()
        }
    }

    private fun writeSession(deviceRoot: Path, capability: String, at: LocalDateTime): Path {
        val dir = deviceRoot.resolve("${tsFmt.format(at)}-$capability")
        Files.createDirectories(dir)
        // 最小 JPEG（SOI+EOI），足以被 copy 进 frames/
        Files.write(dir.resolve("001-shot.jpeg"), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        val manifest = """
            {
              "schema": "ohd-manifest/1",
              "capability": "$capability",
              "device": {"model": "TestPhone", "softwareVersion": "OpenHarmony 1.0", "connectKey": "SERIAL12345678"},
              "artifacts": [{"path": "001-shot.jpeg", "kind": "shot"}]
            }
        """.trimIndent()
        Files.writeString(dir.resolve("manifest.json"), manifest)
        return dir
    }
}
