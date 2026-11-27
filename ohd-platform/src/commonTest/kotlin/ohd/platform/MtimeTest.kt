package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `lastModifiedMillis` 的验证。
 *
 * 这条守的是 **macOS 与 Linux 的 `stat` 字段名差异**：
 * macOS 用 `st_mtimespec`，Linux 用 `st_mtim`。写错会在某个平台上
 * 恒定返回 0 或垃圾值，而「会话按时间排序」「会话目录名去重」全依赖它——
 * 静默错值比崩溃难查得多。
 */
class MtimeTest {

    @Test
    fun readsBackWhatWeJustWrote() {
        val dir = fs.createTempDirectory("ohd-mtime-")
        try {
            val f = dir.resolve("t.txt")
            fs.writeString(f, "hello")
            val m = fs.lastModifiedMillis(f)
            assertTrue(m > 0, "mtime 应 > 0，实际 $m —— 可能是平台字段名取错")
            // 与当前时刻相差应在 1 天内（防「取到 1970」或「取到纳秒当毫秒」）
            val now = Env.nowMillis()
            val diff = now - m
            assertTrue(diff in 0..86_400_000L, "mtime 与当前时刻差 $diff ms，不合理")
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun orderingIsObservable() {
        // 排序依赖 mtime 单调：写两个文件、间隔一小会儿，后者应更新
        val dir = fs.createTempDirectory("ohd-mtime2-")
        try {
            val a = dir.resolve("a.txt")
            val b = dir.resolve("b.txt")
            fs.writeString(a, "a")
            Env.sleepMillis(20)
            fs.writeString(b, "b")
            val ma = fs.lastModifiedMillis(a)
            val mb = fs.lastModifiedMillis(b)
            assertTrue(mb >= ma, "后写的文件 mtime 不应更早：a=$ma b=$mb")
        } finally {
            fs.deleteRecursively(dir)
        }
    }

    @Test
    fun missingFileIsHandled() {
        // 不存在的文件应抛异常或返回稳定值，不能返回「当前时刻」这种误导性结果
        val dir = fs.createTempDirectory("ohd-mtime3-")
        try {
            val missing = dir.resolve("nope.txt")
            val got = runCatching { fs.lastModifiedMillis(missing) }.getOrNull()
            assertEquals(null, got, "不存在的文件不应返回 mtime")
        } finally {
            fs.deleteRecursively(dir)
        }
    }
}
