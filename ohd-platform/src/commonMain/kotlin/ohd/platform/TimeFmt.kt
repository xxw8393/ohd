package ohd.platform

/**
 * 跨端时间工具：只覆盖仓库实际用到的那些格式。
 *
 * ## 为什么自己写而不引 kotlinx-datetime
 *
 * 仓库对时间的需求窄得很具体：把 epoch 毫秒转成 `yyyy-MM-dd HH:mm:ss` 一类的本地时间串、
 * 反过来解析时间戳、算两个时间点相差多少秒。`kotlinx-datetime` 能全包，但它带来一个新依赖
 * 与版本约束（native klib 还要按 target 分发），而这里手写一份带测试的实现约 200 行，
 * 覆盖度正好对得上——**够用即止**，不为几处格式化拖一整个时区数据库进来。
 *
 * ## 支持范围
 *
 * 只支持**固定偏移**（不含 DST 历史规则）：本地时区偏移在进程生命周期内取一次。
 * 这对 ohd 的用途是够的——它格式化的是「刚刚发生的事」与「几小时前的会话目录名」，
 * 不涉及跨 DST 的历史换算。若将来要处理历史时区，再换 kotlinx-datetime。
 *
 * ## 格式标记
 *
 * `yyyy` `MM` `dd` `HH` `mm` `ss` `SSS` `Z`（`+0800`）与字面量字符。
 */
object TimeFmt {

    /**
     * 把 epoch 毫秒按**本地时区**格式化。
     *
     * [pattern] 见类文档。[epochMs] 是该时刻的 UTC 毫秒数。
     */
    fun format(epochMs: Long, pattern: String): String = formatWithOffset(epochMs, pattern, localOffsetSeconds())

    /** 同上，但显式指定偏移（秒）。主要给测试与需要固定时区的场景。 */
    fun formatWithOffset(epochMs: Long, pattern: String, offsetSeconds: Int): String {
        val local = epochMs + offsetSeconds * 1000L
        val c = civilOf(local)
        val y = c.year; val mo = c.month; val d = c.day
        val h = c.hour; val mi = c.minute; val s = c.second; val ms = c.millis
        val sb = StringBuilder(pattern.length + 8)
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                pattern.startsWith("yyyy", i) -> { sb.append(pad(y, 4)); i += 4 }
                pattern.startsWith("SSS", i) -> { sb.append(pad(ms, 3)); i += 3 }
                pattern.startsWith("MM", i) -> { sb.append(pad(mo, 2)); i += 2 }
                pattern.startsWith("dd", i) -> { sb.append(pad(d, 2)); i += 2 }
                pattern.startsWith("HH", i) -> { sb.append(pad(h, 2)); i += 2 }
                pattern.startsWith("mm", i) -> { sb.append(pad(mi, 2)); i += 2 }
                pattern.startsWith("ss", i) -> { sb.append(pad(s, 2)); i += 2 }
                c == 'Z' -> { sb.append(offsetText(offsetSeconds)); i += 1 }
                else -> { sb.append(c); i += 1 }
            }
        }
        return sb.toString()
    }

    /**
     * 解析 [pattern] 形状的本地时间串，返回 epoch 毫秒。
     * 解析失败返回 null（调用点普遍用在 `runCatching` 或 `?:` 兜底里）。
     */
    fun parse(text: String, pattern: String): Long? =
        parseWithOffset(text, pattern, localOffsetSeconds())

    /** 同上，显式偏移。 */
    fun parseWithOffset(text: String, pattern: String, offsetSeconds: Int): Long? {
        var ti = 0
        var y = 1970
        var mo = 1
        var d = 1
        var h = 0
        var mi = 0
        var s = 0
        var ms = 0
        var i = 0
        fun take(n: Int): String? {
            if (ti + n > text.length) return null
            val v = text.substring(ti, ti + n)
            ti += n
            return v
        }
        while (i < pattern.length) {
            when {
                pattern.startsWith("yyyy", i) -> {
                    y = take(4)?.toIntOrNull() ?: return null
                    i += 4
                }
                pattern.startsWith("SSS", i) -> {
                    ms = take(3)?.toIntOrNull() ?: return null
                    i += 3
                }
                pattern.startsWith("MM", i) -> {
                    mo = take(2)?.toIntOrNull() ?: return null
                    i += 2
                }
                pattern.startsWith("dd", i) -> {
                    d = take(2)?.toIntOrNull() ?: return null
                    i += 2
                }
                pattern.startsWith("HH", i) -> {
                    h = take(2)?.toIntOrNull() ?: return null
                    i += 2
                }
                pattern.startsWith("mm", i) -> {
                    mi = take(2)?.toIntOrNull() ?: return null
                    i += 2
                }
                pattern.startsWith("ss", i) -> {
                    s = take(2)?.toIntOrNull() ?: return null
                    i += 2
                }
                pattern[i] == 'Z' -> {
                    // 形如 +0800 / -05:30；解析不出就按当前偏移
                    val z = take(5) ?: return null
                    i += 1
                    if (z[0] != '+' && z[0] != '-') return null
                }
                else -> {
                    val c = pattern[i]
                    if (ti >= text.length || text[ti] != c) return null
                    ti++
                    i++
                }
            }
        }
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59 || s !in 0..60) return null
        return (civilToEpochMs(y, mo, d, h, mi, s, ms) - offsetSeconds * 1000L)
    }

    /** epoch 毫秒 → ISO-8601（带偏移），与 `OffsetDateTime.toString()` 形状对齐。 */
    fun isoLocal(epochMs: Long): String {
        val off = localOffsetSeconds()
        val local = epochMs + off * 1000L
        val c = civilOf(local)
        val y = c.year; val mo = c.month; val d = c.day
        val h = c.hour; val mi = c.minute; val s = c.second; val ms = c.millis
        val sb = StringBuilder(32)
        sb.append(pad(y, 4)).append('-').append(pad(mo, 2)).append('-').append(pad(d, 2))
        sb.append('T').append(pad(h, 2)).append(':').append(pad(mi, 2)).append(':').append(pad(s, 2))
        // 有毫秒才带小数位，否则与 OffsetDateTime 的默认输出一致
        if (ms != 0) sb.append('.').append(pad(ms, 3))
        sb.append(offsetText(off))
        return sb.toString()
    }

    /** 两个 epoch 毫秒之间相差的秒数（[to] - [from] 再除以 1000）。 */
    fun secondsBetween(fromEpochMs: Long, toEpochMs: Long): Long =
        (toEpochMs - fromEpochMs) / 1000L

    fun minutesBetween(fromEpochMs: Long, toEpochMs: Long): Double =
        secondsBetween(fromEpochMs, toEpochMs) / 60.0

    /**
     * 解析 ISO-8601 时间点（`2026-11-27T13:19:59[.ffffff]±HH:MM|±HHMM|Z`），返回 epoch 毫秒。
     *
     * 为什么要单独一个入口：仓库里有一批历史产物是 `OffsetDateTime.toString()` 写出来的，
     * 形状比固定模式更杂——**微秒 6 位**、偏移可能是 `+08:00` 或 `+0800`、UTC 机器上还是 `Z`。
     * 用固定模式串逐个 match 会漏，这里按结构拆。
     *
     * [TimeFmt] 的通用 `parse` 只认整秒/毫秒；小数位超过 3 位会被截断到毫秒
     * （百分之一秒的差异对 ohd 的用途无意义）。
     */
    fun parseIso(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val off = offsetSecondsOf(t) ?: return null
        // 去掉尾部偏移，只留 "yyyy-MM-ddTHH:mm:ss[.fff]"
        val body = when {
            t.endsWith("Z") -> t.dropLast(1)
            else -> {
                val m = Regex("""[+-]\d{2}:?\d{2}$""").find(t) ?: return null
                t.substring(0, m.range.first)
            }
        }
        val dot = body.indexOf('.')
        if (dot < 0) return parseWithOffset(body, "yyyy-MM-ddTHH:mm:ss", off)
        val frac = body.substring(dot + 1)
        val base = body.substring(0, dot)
        // 小数位截断到 3 位（毫秒）；不足补 0
        val ms = frac.padEnd(3, '0').take(3).toIntOrNull() ?: return null
        val whole = parseWithOffset(base, "yyyy-MM-ddTHH:mm:ss", off) ?: return null
        return whole + ms
    }

    /** 取 ISO 串尾部的偏移（秒）；取不到返回 null。 */
    private fun offsetSecondsOf(text: String): Int? {
        if (text.endsWith("Z")) return 0
        val m = Regex("""([+-])(\d{2}):?(\d{2})$""").find(text) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hh = m.groupValues[2].toIntOrNull() ?: return null
        val mm = m.groupValues[3].toIntOrNull() ?: return null
        return sign * (hh * 3600 + mm * 60)
    }

    // ---------- 内部：格里高利历换算 ----------

    private fun pad(v: Int, width: Int): String {
        val str = v.toString()
        if (str.length >= width) return str
        val sb = StringBuilder(width)
        repeat(width - str.length) { sb.append('0') }
        sb.append(str)
        return sb.toString()
    }

    private fun offsetText(offSeconds: Int): String {
        val sign = if (offSeconds < 0) '-' else '+'
        val abs = if (offSeconds < 0) -offSeconds else offSeconds
        return "$sign${pad(abs / 3600, 2)}${pad(abs % 3600 / 60, 2)}"
    }

    /** 本地时区相对 UTC 的偏移秒数（取进程启动时一次）。 */
    private fun localOffsetSeconds(): Int = PlatformEnv.timezoneOffsetSeconds()

    /** 拆开的本地时间。用 data class 而非 IntArray：IntArray 只有前 5 个 component。 */
    private data class Civil(
        val year: Int, val month: Int, val day: Int,
        val hour: Int, val minute: Int, val second: Int, val millis: Int,
    )

    /** 拆成 (年, 月1-12, 日, 时, 分, 秒, 毫秒)。输入是「加过偏移」的本地毫秒。 */
    private fun civilOf(localMs: Long): Civil {
        var days = floorDiv(localMs, 86_400_000L)
        val msOfDay = (localMs - days * 86_400_000L).toInt()
        val h = msOfDay / 3_600_000
        val mi = msOfDay % 3_600_000 / 60_000
        val s = msOfDay % 60_000 / 1000
        val ms = msOfDay % 1000
        // Howard Hinnant 的 civil_from_days
        val z = days + 719_468
        val era = floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val y0 = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (if (m <= 2) y0 + 1 else y0).toInt()
        return Civil(y, m, d, h, mi, s, ms)
    }

    /** (年,月,日,时,分,秒,毫秒) → 本地 epoch 毫秒（未减偏移）。 */
    private fun civilToEpochMs(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int): Long {
        val yy = if (mo <= 2) y - 1 else y
        val era = floorDiv(yy.toLong(), 400L)
        val yoe = yy - era * 400
        val mAdj = if (mo > 2) mo - 3 else mo + 9
        val doy = (153 * mAdj + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        return days * 86_400_000L + h * 3_600_000L + mi * 60_000L + s * 1000L + ms
    }

    private fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if (a % b != 0L && (a xor b) < 0) q - 1 else q
    }
}
