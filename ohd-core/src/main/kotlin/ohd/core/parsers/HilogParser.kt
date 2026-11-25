package ohd.core.parsers

/**
 * hilog 行格式（实测）：
 *   10-08 19:14:37.298   586   586 I I02C01/hmos_cust_carrier_mount/CustCarrierMount: mount start
 *   时间(MM-dd HH:mm:ss.SSS) | pid | tid | 级别 | tag(含 domain/子模块，可含 /) | 消息
 *
 * tag 内可能含 `/` 但不含 `:`（域号 + 模块路径），因此用非贪婪 `(\S+?):` 定位分隔符。
 */
data class HilogLine(
    val time: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
    val raw: String,
) {
    val tagShort: String get() = tag.substringAfterLast('/')
}

object HilogParser {
    private val RE = Regex(
        """^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+?):\s?(.*)$""",
    )

    fun parse(line: String): HilogLine? {
        val m = RE.find(line) ?: return null
        return HilogLine(
            time = m.groupValues[1],
            pid = m.groupValues[2].toIntOrNull() ?: -1,
            tid = m.groupValues[3].toIntOrNull() ?: -1,
            level = m.groupValues[4].first(),
            tag = m.groupValues[5],
            message = m.groupValues[6],
            raw = line,
        )
    }

    fun parseAll(lines: Sequence<String>): List<HilogLine> = lines.mapNotNull { parse(it) }.toList()

    /** marker 直方图：默认按 tagShort 统计，--by-marker 时按消息里首个标识统计。 */
    fun histogram(lines: Sequence<String>, byTag: Boolean = true): Map<String, Int> {
        val counter = linkedMapOf<String, Int>()
        for (l in parseAll(lines)) {
            val key = if (byTag) l.tagShort else l.message.substringBefore(' ').ifBlank { "(empty)" }
            counter[key] = (counter[key] ?: 0) + 1
        }
        return counter.entries.sortedByDescending { it.value }.associate { it.key to it.value }
    }

    fun countMatching(lines: Sequence<String>, regex: Regex): Int = lines.count { regex.containsMatchIn(it) }

    /** 级别过滤（I 及以上语义：按 SDD 级别顺序 W/E/F 更严重）。 */
    fun atLeast(lines: Sequence<String>, minLevel: Char): List<String> {
        val order = "VDIWEF".indexOf(minLevel).coerceAtLeast(0)
        return lines.filter { line ->
            val l = parse(line) ?: return@filter true
            "VDIWEF".indexOf(l.level).let { it >= order }
        }.toList()
    }
}
