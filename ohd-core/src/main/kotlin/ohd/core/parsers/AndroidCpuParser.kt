package ohd.core.parsers

/**
 * Android CPU 输出解析：`top -b -n 1`（toybox）与 `/sys/.../cpufreq`。
 *
 * 产出与 OHOS 的 `CpuParser` 相同的 [CpuReport]，上层 `cpu` 能力不用分叉。
 *
 * 语义差异（保留差异，不强行对齐）：
 * - OHOS 的 `hidumper --cpuusage` 给的是「一段时间窗口内的均值」，Android 的 `top -b -n 1`
 *   是「自开机以来的均值」（首次采样）。要近似实时需二次采样，此处保持单次语义并接受偏差。
 * - toybox top 的百分比是「占全部核」的比例（如 400%cpu 表示 4 核），
 *   [CpuUsage.total] 统一归一化成 0-100。
 */
object AndroidCpuParser {

    private val NCPU = Regex("""(\d+)%cpu""")
    private val USER = Regex("""(\d+)%user""")
    private val SYS = Regex("""(\d+)%sys""")
    private val IDLE = Regex("""(\d+)%idle""")
    private val IOW = Regex("""(\d+)%iow""")
    private val IRQ = Regex("""(\d+)%irq""")

    /** `/proc/loadavg` 三值。 */
    fun loadAvg(raw: String): Triple<Double, Double, Double> {
        val parts = raw.trim().split(Regex("\\s+"))
        return Triple(
            parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0,
            parts.getOrNull(2)?.toDoubleOrNull() ?: 0.0,
        )
    }

    fun parseTop(raw: String, loads: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0)): CpuReport {
        val cpuLine = raw.lines().firstOrNull { it.contains("%cpu") }
        // toybox 的 `<n>%cpu` 里 n 是「核数 × 100」：1 核写 `100%cpu`，4 核写 `400%cpu`。
        // 早期误当成核数会让 scale 缩小 100 倍（1 核机器算出 99% 占用）。
        val ncpuRaw = cpuLine?.let { NCPU.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val ncpu = if (ncpuRaw >= 100) ncpuRaw / 100 else ncpuRaw.coerceAtLeast(1)
        val idle = cpuLine?.let { IDLE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
        val user = cpuLine?.let { USER.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
        val sys = cpuLine?.let { SYS.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
        val iow = cpuLine?.let { IOW.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
        val irq = cpuLine?.let { IRQ.find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0

        // toybox 的百分比以「全部核合计 = ncpu*100」为分母，归一化到单核视角的 0-100
        val scale = if (ncpu > 0) 100.0 / (ncpu * 100.0) else 1.0
        val usage = if (cpuLine != null) {
            CpuUsage(
                load1 = loads.first,
                load5 = loads.second,
                load15 = loads.third,
                total = ((ncpu * 100.0 - idle) * scale).coerceIn(0.0, 100.0),
                user = user * scale,
                kernel = sys * scale,
                iowait = iow * scale,
                irq = irq * scale,
                idle = idle * scale,
                window = null,
            )
        } else {
            null
        }

        val procs = mutableListOf<ProcCpu>()
        for (line in raw.lines()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 9) continue
            val pid = parts[0].toIntOrNull() ?: continue
            // 列序：PID USER PR NI VIRT RES SHR S %CPU %MEM TIME+ ARGS
            val status = parts.getOrNull(7) ?: continue
            if (status.length != 1 || status[0] !in "SDRIZTX") continue
            val cpuPct = parts.getOrNull(8)?.trim('[', ']', '%')?.toDoubleOrNull() ?: continue
            val name = parts.drop(11).joinToString(" ").ifBlank { parts.getOrNull(8) ?: "" }
            procs += ProcCpu(
                pid = pid,
                totalPercent = cpuPct,
                user = cpuPct,
                kernel = 0.0,
                minorFault = 0,
                majorFault = 0,
                name = name,
            )
        }

        return CpuReport(usage, procs.sortedByDescending { it.totalPercent })
    }

    /**
     * cpufreq：设备端一次遍历所有核。
     * 输出每行 `<核号> <cur_khz> <max_khz>`；读不到的核不出现（不做 0 填充）。
     */
    fun cpufreqCommand(): String =
        "for c in /sys/devices/system/cpu/cpu[0-9]*; do " +
            "n=\${c##*/cpu}; cur=\$(cat \$c/cpufreq/scaling_cur_freq 2>/dev/null); " +
            "mx=\$(cat \$c/cpufreq/scaling_max_freq 2>/dev/null); " +
            "[ -n \"\$cur\" ] && echo \"\$n \$cur \$mx\"; done"

    /** core → (curKHz, maxKHz) */
    fun parseCpufreq(raw: String): List<Pair<Int, Pair<Long, Long>>> = raw.lines()
        .map { it.trim().split(Regex("\\s+")) }
        .filter { it.size >= 2 }
        .mapNotNull { p ->
            val core = p[0].toIntOrNull() ?: return@mapNotNull null
            val cur = p[1].toLongOrNull() ?: return@mapNotNull null
            val max = p.getOrNull(2)?.toLongOrNull() ?: 0L
            core to (cur to max)
        }
        .sortedBy { it.first }
}
