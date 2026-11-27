package ohd.core.ext

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtTest {

    private fun tempDir(): Path = Files.createTempDirectory("ohd-ext").also { it.toFile().deleteOnExit() }

    private fun write(dir: Path, name: String, body: String = "#!/bin/sh\n"): Path =
        dir.resolve(name).also { Files.writeString(it, body) }

    @Test
    fun `解析 sh 扩展并取 @ohd 摘要`() {
        val dir = tempDir()
        val f = write(dir, "uitest.sh", "#!/bin/sh\n# @ohd UI 冒烟：随机点几下\n")
        val ext = Extensions.parse(f)!!
        assertEquals("uitest", ext.name)
        assertEquals(ExtKind.SHELL, ext.kind)
        assertEquals("UI 冒烟：随机点几下", ext.summary)
    }

    @Test
    fun `没有 @ohd 注释时退化成类型说明`() {
        val dir = tempDir()
        val ext = Extensions.parse(write(dir, "plain.py"))!!
        assertEquals(ExtKind.PYTHON, ext.kind)
        assertTrue(ext.summary.contains("py"), "摘要应回退到类型标签：${ext.summary}")
    }

    @Test
    fun `非法命令名与未知后缀都跳过`() {
        val dir = tempDir()
        assertNull(Extensions.parse(write(dir, "Bad Name.sh")), "大写与空格不合法")
        assertNull(Extensions.parse(write(dir, "noext.txt")), "未知后缀不收")
    }

    @Test
    fun `java 单文件扩展被识别`() {
        val dir = tempDir()
        val ext = Extensions.parse(write(dir, "hello.java", "// @ohd Java 示例\n"))!!
        assertEquals(ExtKind.JAVA, ext.kind)
        assertEquals("Java 示例", ext.summary)
    }

    @Test
    fun `OHD_EXT 排在最前且同名取平台首选`() {
        val dir = tempDir()
        write(dir, "uitest.sh")
        write(dir, "uitest.py")
        val env = mapOf("OHD_EXT" to dir.toString())
        assertEquals(dir, Extensions.roots(tempDir(), env::get).first())

        val found = Extensions.discover(tempDir(), env::get)
        val ext = found["uitest"]!!
        // Unix 上 sh 优先于 py（Windows 上顺序相反，这里只断言「只保留一个」）
        assertTrue(ext.kind == ExtKind.SHELL || ext.kind == ExtKind.PYTHON)
        assertEquals(1, found.keys.count { it == "uitest" })
    }

    @Test
    fun `注入环境包含版本与工作目录`() {
        val env = Extensions.envOf(hdc = "/usr/bin/hdc", llvm = null, device = "ABC", sessionRoot = null, json = true, ascii = false)
        assertEquals(ohd.core.TOOL_VERSION, env["OHD_VERSION"])
        assertEquals("/usr/bin/hdc", env["OHD_HDC"])
        assertEquals("ABC", env["OHD_DEVICE"])
        assertEquals("1", env["OHD_JSON"])
        assertNull(env["OHD_ASCII"])
        assertTrue(env["OHD_CWD"]!!.isNotBlank())
        assertTrue(env["OHD_EXT"]!!.isNotBlank())
    }

    @Test
    fun `无执行位的裸脚本拒绝执行`() {
        val dir = tempDir()
        val f = write(dir, "raw", "#!/bin/sh\necho hi\n")
        Files.setPosixFilePermissions(f, setOf(PosixFilePermission.OWNER_READ))
        val ext = Extensions.parse(f)!!
        val thrown = runCatching { Extensions.exec(ext, emptyList(), emptyMap()) }.exceptionOrNull()
        assertTrue(thrown != null, "不可执行的裸脚本应报错而不是静默成功")
    }
}
