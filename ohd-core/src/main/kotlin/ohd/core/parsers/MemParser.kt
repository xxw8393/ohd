package ohd.core.parsers

/**
 * `hidumper --mem <pid>` 的**分类表**解析（实测 API 24 格式）：
 *
 *   GL / Graph / ark ts heap / native heap / .hap / AnonPage other / stack / .db / .so / dev / .ttf / FilePage other
 *   列：Pss | Shared Clean | Shared Dirty | Private Clean | Private Dirty | Swap Total | SwapPss Total | Heap Size | Heap Alloc | Heap Free
 *   末行：Total
 *
 * 注意：分类名可能含空格（`ark ts heap`），所以按「末尾 10 个数值」反推名称，不能按固定列切分。
 */
data class MemRow(val name: String, val pssKb: Long, val swapPssKb: Long, val values: List<Long>) {
    fun col(index: Int): Long = values.getOrElse(index) { 0L }
}

data class MemReport(val rows: List<MemRow>, val total: MemRow?) {
    fun row(name: String): MemRow? = rows.firstOrNull { it.name.equals(name, ignoreCase = true) }
    val pssKb: Long get() = total?.pssKb ?: rows.sumOf { it.pssKb }
    val swapPssKb: Long get() = total?.swapPssKb ?: rows.sumOf { it.swapPssKb }

    /** GL 与 Graph 是 CMP 场景最关心的两项（GL 纹理 / 窗口合成缓冲）。 */
    val glKb: Long get() = row("GL")?.pssKb ?: 0L
    val graphKb: Long get() = row("Graph")?.pssKb ?: 0L
    val nativeHeapKb: Long get() = row("native heap")?.pssKb ?: 0L
}

object MemParser {
    private val NUM_COLS = 10

    fun parse(raw: String): MemReport {
        val rows = mutableListOf<MemRow>()
        var total: MemRow? = null
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("-") || t.startsWith("[")) continue
            val tokens = t.split(Regex("\\s+"))
            if (tokens.size <= NUM_COLS) continue
            val values = tokens.takeLast(NUM_COLS).map { it.toLongOrNull() ?: return@map -1L }
            if (values.any { it < 0 }) continue
            val name = tokens.dropLast(NUM_COLS).joinToString(" ").trim()
            if (name.isEmpty()) continue
            val row = MemRow(name, values[0], values[6], values)
            if (name.equals("Total", ignoreCase = true)) total = row else rows += row
        }
        return MemReport(rows, total)
    }

    /** 兼容老格式单行：`Pss(12345 in SwapPss)`（部分版本 --mem 汇总行）。 */
    fun parseSwapPssLine(raw: String): Long? =
        Regex("""Pss\(\s*(\d+)\s+in\s+SwapPss""").find(raw)?.groupValues?.get(1)?.toLongOrNull()
}
