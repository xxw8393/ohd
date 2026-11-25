package ohd.core.parsers

/**
 * 解析 `/proc/<pid>/mm_dmabuf_info`。
 *
 * 格式（17 列）：
 * `Process pid fd size_bytes ino exp_pid exp_task_comm buf_name exp_name can_reclaim is_reclaim buf_type reclaim_info leak_type ahost-cpid ahost-iova ahost-usage`
 *
 * 两个必须处理的现实问题：
 * 1. **同一 ino 多行**：一块 dmabuf 被 dup/多次 mmap 会出现多行，按行求和会重复计算
 *    （实测 35 行里有 1 行是重复映射，直接求和多算 40 kB）。必须按 ino 去重。
 * 2. **列错位**：`buf_name` 可能为空或含空格，导致按空白切分后列数不等于 17。
 *    因此尾部字段一律用「从末尾倒数的相对索引」定位，不写死列号。
 */
object DmabufParser {
    data class Entry(
        val process: String,
        val pid: Int,
        val fd: Int,
        val sizeBytes: Long,
        val ino: String,
        val expPid: Int,
        val expTaskComm: String,
        val bufName: String,
        val bufType: String,
        val canReclaim: Boolean,
        val isReclaim: Boolean,
        val leakType: String,
    )

    data class Group(val label: String, val bytes: Long, val count: Int, val types: Set<String>)

    data class Snapshot(
        val entries: List<Entry>,
        val rawRows: Int,
        val skipped: Int,
        val totalBytes: Long,
        val groups: List<Group>,
    ) {
        /** 重复映射浪费的行数（同 ino 的第 2..n 行） */
        val duplicateRows: Int get() = rawRows - skipped - entries.size
    }

    fun parse(text: String): Snapshot {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val all = mutableListOf<Entry>()
        var raw = 0
        var skipped = 0
        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.startsWith("Process")) return@forEach
            raw++
            val e = parseLine(trimmed)
            if (e == null) skipped++ else all.add(e)
        }
        // 按 ino 去重：保留首行（同 ino 的 size 相同）
        val deduped = all.distinctBy { it.ino }
        return Snapshot(
            entries = deduped,
            rawRows = raw,
            skipped = skipped,
            totalBytes = deduped.sumOf { it.sizeBytes },
            groups = group(deduped),
        )
    }

    fun parseLine(line: String): Entry? {
        val t = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        // 尾部固定 6 列：buf_type reclaim_info leak_type ahost-cpid ahost-iova ahost-usage
        if (t.size < 14) return null
        val n = t.size
        val size = t.getOrNull(3)?.toLongOrNull() ?: return null
        val pid = t.getOrNull(1)?.toIntOrNull() ?: return null
        // buf_name 位于固定头部之后、尾部 6 列之前；含空格时在此区间自然合并
        val bufName = if (n >= 17) t.subList(7, n - 9).joinToString(" ") else ""
        val leakType = t.getOrNull(n - 4) ?: "" // 尾部倒数第 4：usage,iova,cpid 之后
        return Entry(
            process = t[0],
            pid = pid,
            fd = t.getOrNull(2)?.toIntOrNull() ?: -1,
            sizeBytes = size,
            ino = t.getOrNull(4) ?: "",
            expPid = t.getOrNull(5)?.toIntOrNull() ?: -1,
            expTaskComm = t.getOrNull(6) ?: "",
            bufName = bufName,
            bufType = t.getOrNull(n - 6) ?: "",
            canReclaim = t.getOrNull(n - 8) == "1",
            isReclaim = t.getOrNull(n - 7) == "1",
            leakType = leakType,
        )
    }

    /**
     * 标签归一化：不归并就没法看出「哪一类在涨」——每个图标名都是独立字符串。
     * 但也不能归并过头（尺寸是排查关键特征），所以只砍掉无意义的序号段。
     */
    fun normalizeLabel(name: String): String {
        val s = name.trim()
        if (s.isEmpty() || s == "NULL") return "(unnamed)"
        // srcImageSize-1024x64-pixelMapSize-1024x64-streamsize-141592-mimetype-png → srcImageSize-1024x64
        Regex("""^srcImageSize-(\d+x\d+)""").find(s)?.let { return "srcImageSize-${it.groupValues[1]}" }
        // id:[Dropdown]NotificationIconView___100_20020018_xxx_bigIcon → id:[Dropdown]NotificationIconView
        if (s.startsWith("id:")) {
            val head = s.substringBefore("___")
            return if (head.isNotEmpty()) head else "id:(unnamed)"
        }
        // SaveSnapshot_321 → SaveSnapshot
        val trimmed = s.replace(Regex("""[-_]\d+$"""), "")
        return if (trimmed.length <= 56) trimmed else trimmed.take(55) + "…"
    }

    private fun group(entries: List<Entry>): List<Group> =
        // OHOS XComponent：buf_name 常是 pid 数字，真正的实例标签在 leak_type
        //（形如 xcomponent-t-Compose_N）。优先用它，否则阶梯泄漏无法归因到已销毁 id。
        entries.groupBy { e ->
            val surfaceTag = sequenceOf(e.leakType, e.bufName)
                .map { it.trim() }
                .firstOrNull { it.startsWith("xcomponent-t-") }
            when {
                surfaceTag != null -> surfaceTag
                else -> {
                    val label = normalizeLabel(e.bufName)
                    // 纯数字 buf_name 必须带 buf_type，避免与 last_buffer 合并。
                    if (label.all { it.isDigit() }) "${e.bufType.ifEmpty { "?" }}:$label" else label
                }
            }
        }
            .map { (label, list) ->
                Group(
                    label = label,
                    bytes = list.sumOf { it.sizeBytes },
                    count = list.size,
                    types = list.map { it.bufType.ifEmpty { "?" } }.toSet(),
                )
            }
            .sortedByDescending { it.bytes }
}
