package ohd.core

import ohd.platform.Bg
import ohd.platform.Env
import ohd.platform.OhdPath
import ohd.platform.spawnStreaming

/**
 * hilog 流处理：设备端过滤 + 宿主端精简/关键字过滤。
 *
 * 设备端不支持 compat 格式（`-v` 只有 time/epoch/year/zone/msec...），
 * 所以「只保留必要字段」在宿主端做：丢掉 `pid tid level tag`，留 `日期 时间 消息`。
 * 与 ~/hilog.sh 的 awk 语义一致。
 */
object HilogStream {

    /** 标准行：`10-21 09:09:54.271 36417 36417 I A00000/com.x/Konan_main: [tag][DEBUG] msg` */
    private val STD = Regex("""^(\S+) (\S+) (\S+) (\S+) (\S+) (\S+): ?(.*)$""")

    /**
     * 日志泵句柄。
     *
     * 旧实现直接返回 JDK `Process`；改用 [spawnStreaming] 后不再持有 `Process`，
     * 这里只保留调用点真正依赖的两件事：`pid()`（交给 [ProcessTrees.kill]）与 `waitFor()`。
     * 方法名刻意对齐 `Process`，让现有调用点逐字不变。
     */
    class Pump(private val processId: Long) {
        fun pid(): Long = processId

        /** 阻塞到进程退出。等价旧的 `Process.waitFor()`（不抛 InterruptedException）。 */
        fun waitFor() {
            while (ProcessTrees.alive(processId)) Env.sleepMillis(50)
        }
    }

    /** `--tag a,b` → `hilog -T a,b`（-T 只接受单参数逗号列表）；空则全量 */
    fun command(tags: List<String>): String =
        if (tags.isEmpty()) "hilog" else "hilog -T " + tags.joinToString(",")

    fun parseTags(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** 精简一行：留 `日期 时间 消息`；解析不了的原样返回，绝不丢行 */
    fun compat(line: String): String {
        val m = STD.find(line) ?: return line
        val g = m.groupValues
        return "${g[1]} ${g[2]} ${g[7]}"
    }

    /**
     * 起 hilog 流并后台泵到 [file]（明文）。保留旧签名：`log start` 等调用点不必改。
     */
    fun pump(argv: List<String>, file: OhdPath, match: Regex?, exclude: Regex?, compat: Boolean): Pump =
        pump(argv, PlainLogSink(file), match, exclude, compat)

    /**
     * 起 hilog 流并后台泵到 [sink]。
     * [match] 非空时只留命中行；[exclude] 非空时丢弃命中行（先 include 后 exclude）。
     * [compat] 为 true 时执行字段精简。
     * 返回的进程由调用方负责 kill（kill 后泵线程读到 EOF 自行收尾）。
     */
    fun pump(argv: List<String>, sink: LogSink, match: Regex?, exclude: Regex?, compat: Boolean): Pump {
        // 记录最后一次收到行的时刻：进程退出后还要等管道排空，才能安全 close sink。
        val lastLineAt = longArrayOf(Env.nowMillis())
        var pending = 0
        var lastFlush = Env.nowMillis()
        // spawnStreaming 内部已起后台线程逐行回调，stderr 并入 stdout、空行不回调，
        // 与旧的 redirectErrorStream + 手写 pump 线程逐条对齐。
        val spawned = spawnStreaming(argv) { line ->
            if (match != null && !match.containsMatchIn(line)) return@spawnStreaming
            if (exclude != null && exclude.containsMatchIn(line)) return@spawnStreaming
            sink.write(if (compat) compat(line) else line)
            // 攒批落盘：LogWatchdog 靠文件大小判上限，不能一直不 flush。
            // 注意别改成「每行都 flush」——GzipLogSink 每次 flush 会闭一个独立 gzip 成员。
            if (++pending >= 200 || Env.nowMillis() - lastFlush > 1_000) {
                sink.flush(); pending = 0; lastFlush = Env.nowMillis()
            }
            lastLineAt[0] = Env.nowMillis()
        }
        val pid = spawned.pid
        if (pid == null) {
            throw OhdError(
                ExitCode.TIMEOUT,
                "启动日志流失败：${spawned.spawnError ?: "未返回进程 PID"}",
            )
        }
        // 收尾看护：MonitorCap / MonkeyCap 不自己 close sink，靠这里在流结束后落最后一批。
        Bg.launch {
            while (ProcessTrees.alive(pid)) Env.sleepMillis(100)
            // 进程刚死时管道里可能还有残留行（大输出时必然发生）：等它静默再 close，
            // 否则会把最后几行连同 gzip 的收尾一起丢掉。
            val drainDeadline = Env.nowMillis() + 2_000
            while (Env.nowMillis() < drainDeadline && Env.nowMillis() - lastLineAt[0] < 300) {
                Env.sleepMillis(50)
            }
            runCatching { sink.flush() }
            runCatching { sink.close() }
        }
        return Pump(pid)
    }
}
