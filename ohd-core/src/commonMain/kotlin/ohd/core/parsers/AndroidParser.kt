package ohd.core.parsers

/**
 * Android 侧的输出解析（与 OHOS 的 hidumper 解析分开，避免互相污染）。
 *
 * 只做两件观测必需的事：PSS 与 CPU 占比。
 * 取不到就返回 null，由调用方决定「无数据」而不是造一个 0——
 * 0 会被趋势判定误读成「内存掉到 0」。
 */
object AndroidParser {

    /**
     * `dumpsys meminfo <pid>` 的关键行（不同 API 版本三种写法）：
     *   TOTAL PSS:   123456          （最稳，Android 9+）
     *   TOTAL:       123456          （旧版）
     *   TOTAL PSS: 12345K            （极少数 OEM 带单位）
     */
    fun pssKb(raw: String): Long? {
        val patterns = listOf(
            Regex("""TOTAL\s+PSS:\s*(\d+)"""),
            Regex("""TOTAL:\s*(\d+)"""),
        )
        for (p in patterns) {
            val m = p.find(raw) ?: continue
            val v = m.groupValues[1].toLongOrNull() ?: continue
            if (v > 0) return v
        }
        return null
    }

    /**
     * `dumpsys meminfo <pid>` → MemReport（与 OHOS 的 MemParser 同一数据结构，
     * 这样 `mem` / `monitor` 的上层代码不用分叉）。
     *
     * 只抽能力层真正会读的行：Native Heap / Dalvik Heap / Graphics / Code / Stack / TOTAL。
     * 抽不到的字段保持 0，不臆造。
     */
    fun memReport(raw: String): MemReport {
        val rows = mutableListOf<MemRow>()
        var total: MemRow? = null
        val numRe = Regex("""(\d+)""")
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("**")) continue
            val nums = numRe.findAll(t).map { it.value.toLong() }.toList()
            if (nums.size < 2) continue
            val name = t.substringBefore(nums.first().toString()).trim().trimEnd(':').trim()
            if (name.isEmpty()) continue
            val pss = nums[0]
            val swap = if (nums.size >= 4) nums[3] else 0L
            val upper = name.uppercase()
            when {
                upper.contains("TOTAL") && upper.contains("PSS") -> total = MemRow("TOTAL", pss, swap, nums)
                upper == "TOTAL" || upper.startsWith("TOTAL ") -> if (total == null) total = MemRow("TOTAL", pss, swap, nums)
                upper.contains("HEAP") || upper.contains("GRAPHICS") || upper.contains("CODE") ||
                    upper.contains("STACK") || upper.contains("PRIVATE") -> rows += MemRow(name, pss, swap, nums)
            }
        }
        if (total == null) pssKb(raw)?.let { total = MemRow("TOTAL", it, 0L, listOf(it)) }
        return MemReport(rows, total)
    }

    /**
     * `top -b -n 1 -p <pid>` 中该 pid 那一行的 `%CPU` 列。
     * 注意：top 首次采样是「自开机以来均值」，要真实占用需第二次采样；
     * 这里保持单次采样的语义，与 hidumper --cpuusage 的行为对齐（也是均值）。
     */
    fun cpuPercent(raw: String, pid: Int): Double? {
        for (line in raw.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 9) continue
            if (parts[0].toIntOrNull() != pid) continue
            // 列序：PID USER PR NI VIRT RES SHR S %CPU ...（%CPU 在第 9 列，0-based 8）
            return parts.getOrNull(8)?.toDoubleOrNull()
        }
        return null
    }
}
