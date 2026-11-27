package ohd.platform

/**
 * K/N 侧路径运算：纯字符串实现（K/N 没有 `java.nio.file.Path`）。
 *
 * 语义必须与 JVM 的 `Path.normalize()` 对齐，否则同一路径在两个入口会算出不同结果，
 * 双入口的逐字节 diff 就过不了。关键对齐点（都有单测覆盖）：
 * - `a/./b` → `a/b`；`a/../b` → `b`；`/..` → `/`；
 * - 末尾分隔符被去掉（`a/b/` → `a/b`），但根 `/` 保留；
 * - 相对路径的 `..` 在开头时保留（`../a` 不变），因为无法判定上级真实路径。
 */
internal actual object PlatformFs {
    actual val sep: String = "/"

    actual fun cwd(): String = PlatformEnv.cwdPath().toString()

    actual fun isAbsoluteString(s: String): Boolean = s.startsWith("/")

    actual fun normalizeString(s: String): String {
        if (s.isEmpty()) return s
        val absolute = s.startsWith("/")
        val out = ArrayList<String>()
        for (seg in s.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> Unit
                seg == ".." -> {
                    // 绝对路径下越界就停在根；相对路径下开头保留 ..
                    if (out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1)
                    else if (!absolute) out.add("..")
                }
                else -> out.add(seg)
            }
        }
        val body = out.joinToString("/")
        return when {
            absolute -> "/" + body
            // 与 java.nio.file.Path.normalize() 对齐：相对路径归一后为空时返回空串，
            // **不是** "."（实测 `Paths.get("a/..").normalize()` → ""，`Paths.get(".")` → ""）
            else -> body
        }
    }
}
