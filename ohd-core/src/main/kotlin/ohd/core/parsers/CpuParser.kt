package ohd.core.parsers

/**
 * `hidumper --cpuusage [pid]` 解析（实测格式）：
 *
 *   Load average: 13.4 / 13.5 / 13.6; ...
 *   CPU usage from 2026-10-17 12:04:34 to 2026-10-17 12:04:43
 *   Total: 5.01%; User Space: 1.90%; Kernel Space: 3.11%; iowait: 0.00%; irq: 0.04%; idle: 94.95%
 *   PID   Total Usage  User Space  Kernel Space  Page Fault Minor  Page Fault Major  Name
 */
data class CpuUsage(
    val load1: Double,
    val load5: Double,
    val load15: Double,
    val total: Double,
    val user: Double,
    val kernel: Double,
    val iowait: Double,
    val irq: Double,
    val idle: Double,
    val window: String?,
)

data class ProcCpu(
    val pid: Int,
    val totalPercent: Double,
    val user: Double,
    val kernel: Double,
    val minorFault: Long,
    val majorFault: Long,
    val name: String,
)

data class CpuReport(val usage: CpuUsage?, val processes: List<ProcCpu>)

object CpuParser {
    private val LOAD = Regex("""Load average:\s*([\d.]+)\s*/\s*([\d.]+)\s*/\s*([\d.]+)""")
    private val WINDOW = Regex("""CPU usage from\s+(.+?)\s+to\s+(.+)$""")
    private val TOTAL = Regex("""Total:\s*([\d.]+)%\s*;\s*User Space:\s*([\d.]+)%\s*;\s*Kernel Space:\s*([\d.]+)%\s*;\s*iowait:\s*([\d.]+)%\s*;\s*irq:\s*([\d.]+)%\s*;\s*idle:\s*([\d.]+)%""")
    private val PROC = Regex("""^\s*(\d+)\s+([\d.]+)%\s+([\d.]+)%\s+([\d.]+)%\s+(\d+)\s+(\d+)\s+(\S+)\s*$""")

    fun parse(raw: String): CpuReport {
        var load1 = 0.0
        var load5 = 0.0
        var load15 = 0.0
        var total = 0.0
        var user = 0.0
        var kernel = 0.0
        var iowait = 0.0
        var irq = 0.0
        var idle = 0.0
        var window: String? = null
        var sawUsage = false
        val procs = mutableListOf<ProcCpu>()

        for (line in raw.lines()) {
            LOAD.find(line)?.let {
                load1 = it.groupValues[1].toDoubleOrNull() ?: 0.0
                load5 = it.groupValues[2].toDoubleOrNull() ?: 0.0
                load15 = it.groupValues[3].toDoubleOrNull() ?: 0.0
            }
            WINDOW.find(line)?.let { window = "${it.groupValues[1]} ~ ${it.groupValues[2]}" }
            TOTAL.find(line)?.let {
                total = it.groupValues[1].toDoubleOrNull() ?: 0.0
                user = it.groupValues[2].toDoubleOrNull() ?: 0.0
                kernel = it.groupValues[3].toDoubleOrNull() ?: 0.0
                iowait = it.groupValues[4].toDoubleOrNull() ?: 0.0
                irq = it.groupValues[5].toDoubleOrNull() ?: 0.0
                idle = it.groupValues[6].toDoubleOrNull() ?: 0.0
                sawUsage = true
            }
            PROC.find(line)?.let {
                procs += ProcCpu(
                    pid = it.groupValues[1].toIntOrNull() ?: 0,
                    totalPercent = it.groupValues[2].toDoubleOrNull() ?: 0.0,
                    user = it.groupValues[3].toDoubleOrNull() ?: 0.0,
                    kernel = it.groupValues[4].toDoubleOrNull() ?: 0.0,
                    minorFault = it.groupValues[5].toLongOrNull() ?: 0L,
                    majorFault = it.groupValues[6].toLongOrNull() ?: 0L,
                    name = it.groupValues[7],
                )
            }
        }
        val usage = if (sawUsage || load1 > 0.0) {
            CpuUsage(load1, load5, load15, total, user, kernel, iowait, irq, idle, window)
        } else null
        return CpuReport(usage, procs.sortedByDescending { it.totalPercent })
    }
}
