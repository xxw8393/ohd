package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Fs` 的跨平台单测：JVM 走 `java.nio.file`，K/N 走 posix。
 *
 * 每条用例都必须在两端过——这是 `OhdPath`/`Fs` 抽象唯一的价值所在。
 * 用临时目录隔离，不碰仓库文件。
 */
class FsTest {

    private fun tmpDir(tag: String): OhdPath {
        val base = fs.createTempDirectory("ohd-fs-$tag")
        return base
    }

    @Test
    fun writeReadRoundTrip() {
        val dir = tmpDir("rw")
        try {
            val f = dir.resolve("a.txt")
            fs.writeString(f, "hello\n世界\n")
            assertTrue(fs.exists(f))
            assertEquals("hello\n世界\n", fs.readString(f))
            // "hello\n世界\n" 有两行（末尾换行不计第三行）
            assertEquals(2L, fs.countLines(f))
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun createDirectoriesIsRecursive() {
        val dir = tmpDir("mk")
        try {
            val deep = dir.resolve("a/b/c/d")
            fs.createDirectories(deep)
            assertTrue(fs.isDirectory(deep))
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun sizeAndLastModified() {
        val dir = tmpDir("meta")
        try {
            val f = dir.resolve("x.bin")
            fs.writeString(f, "12345")
            assertEquals(5L, fs.size(f))
            assertTrue(fs.lastModifiedMillis(f) > 0L)
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun listReturnsChildrenOnly() {
        val dir = tmpDir("ls")
        try {
            fs.writeString(dir.resolve("one.txt"), "1")
            fs.writeString(dir.resolve("two.txt"), "2")
            val names = fs.list(dir).use { seq -> seq.iterator().asSequence().map { it.fileName?.toString() }.toList() }
            assertEquals(setOf("one.txt", "two.txt"), names.toSet())
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun walkVisitsNested() {
        val dir = tmpDir("walk")
        try {
            fs.writeString(dir.resolve("a/x.txt"), "x")
            fs.writeString(dir.resolve("a/b/y.txt"), "y")
            val all = fs.walk(dir).use { seq -> seq.iterator().asSequence().map { it.fileName?.toString() }.toList() }
            assertTrue(all.contains("x.txt"), "应包含 a/x.txt，实际 $all")
            assertTrue(all.contains("y.txt"), "应包含 a/b/y.txt，实际 $all")
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun copyAndMove() {
        val dir = tmpDir("cp")
        try {
            val src = dir.resolve("src.txt")
            val dst = dir.resolve("dst.txt")
            val moved = dir.resolve("moved.txt")
            fs.writeString(src, "payload")
            fs.copy(src, dst)
            assertEquals("payload", fs.readString(dst))
            fs.move(dst, moved)
            assertFalse(fs.exists(dst), "move 后源文件应不存在")
            assertEquals("payload", fs.readString(moved))
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun deleteRecursivelyRemovesTree() {
        val dir = tmpDir("rm")
        val nested = dir.resolve("a/b/c")
        fs.createDirectories(nested)
        fs.writeString(nested.resolve("f.txt"), "x")
        fs.deleteRecursively(dir)
        assertFalse(fs.exists(dir))
    }

    @Test
    fun digestMatchesKnownVector() {
        // 与 DigestTest 同源，但这里是走 Fs 门面，确认接线正确
        val dir = tmpDir("dg")
        try {
            val f = dir.resolve("abc.txt")
            fs.writeString(f, "abc")
            assertEquals("900150983cd24fb0d6963f7d28e17f72", fs.md5Hex(fs.readBytes(f)))
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", fs.sha1Hex(fs.readBytes(f)))
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                fs.sha256Hex(fs.readBytes(f)),
            )
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun gzipRoundTrip() {
        val dir = tmpDir("gz")
        try {
            val plain = dir.resolve("p.txt")
            val gz = dir.resolve("p.bin")
            val back = dir.resolve("b.txt")
            val text = "ohd gzip roundtrip ".repeat(500)
            fs.writeString(plain, text)
            fs.gzipCompress(plain, gz)
            assertTrue(fs.size(gz) > 0)
            fs.gzipDecompress(gz, back)
            assertEquals(text, fs.readString(back))
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun envHomeAndCwdAreAbsolute() {
        assertTrue(Env.home.isAbsolute(), "home 应为绝对路径：${Env.home}")
        assertTrue(Env.cwd.isAbsolute(), "cwd 应为绝对路径：${Env.cwd}")
    }

    @Test
    fun readableBytesRange() {
        val dir = tmpDir("range")
        try {
            val f = dir.resolve("r.txt")
            fs.writeString(f, "0123456789")
            assertEquals("345", fs.readBytesRange(f, 3, 3).decodeToString())
        } finally {
            fs.deleteRecursively(dir)
        }
    }
}
