package ohd.platform

/**
 * 极简 glob 匹配（只认 `*`）。
 *
 * ## 为什么不用 PathMatcher
 *
 * `Fs.newDirectoryStream(dir, "*faultlog*")` 的三处调用点全部只用单 `*`。为了这点需求
 * 在 JVM 侧挂 `FileSystems.getDefault().getPathMatcher("glob:…")`、在 K/N 侧手写一套 fnmatch，
 * 会让两端语义容易跑偏（`**`、`?`、`[...]`、转义、大小写规则各不相同）。
 * 统一在这里实现一份，两端行为逐字节一致。
 *
 * 语义：`*` 匹配任意长度（含空）的串，其余字符按字面量比较。不做 `?`/`[]`/`**`。
 */
internal object OhdGlob {

    /** [pattern] 是否匹配 [name]。 */
    fun matches(pattern: String, name: String): Boolean {
        if ('*' !in pattern) return pattern == name
        val parts = pattern.split('*')
        // 首段必须对齐开头（pattern 不以 `*` 开头时）
        var cursor = 0
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            if (i == 0 && !pattern.startsWith("*")) {
                if (!name.startsWith(part)) return false
                cursor = part.length
                continue
            }
            val hit = name.indexOf(part, cursor)
            if (hit < 0) return false
            cursor = hit + part.length
        }
        // 尾段必须对齐结尾（pattern 不以 `*` 结尾时）
        if (!pattern.endsWith("*")) {
            val last = parts.last()
            if (!name.endsWith(last)) return false
        }
        return true
    }
}
