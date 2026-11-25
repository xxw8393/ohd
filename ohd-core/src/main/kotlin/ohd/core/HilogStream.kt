package ohd.core

import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import kotlin.concurrent.thread

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
    fun pump(argv: List<String>, file: Path, match: Regex?, exclude: Regex?, compat: Boolean): Process =
        pump(argv, PlainLogSink(file), match, exclude, compat)

    /**
     * 起 hilog 流并后台泵到 [sink]。
     * [match] 非空时只留命中行；[exclude] 非空时丢弃命中行（先 include 后 exclude）。
     * [compat] 为 true 时执行字段精简。
     * 返回的进程由调用方负责 destroy（destroy 后泵线程读到 EOF 自行收尾）。
     */
    fun pump(argv: List<String>, sink: LogSink, match: Regex?, exclude: Regex?, compat: Boolean): Process {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        thread(isDaemon = true, name = "ohd-hilog-pump") {
            var pending = 0
            var lastFlush = System.currentTimeMillis()
            try {
                InputStreamReader(p.inputStream, decoder).buffered().useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trimEnd('\r')
                        if (line.isEmpty()) continue
                        if (match != null && !match.containsMatchIn(line)) continue
                        if (exclude != null && exclude.containsMatchIn(line)) continue
                        sink.write(if (compat) compat(line) else line)
                        // 攒批落盘：LogWatchdog 靠文件大小判上限，不能一直不 flush
                        if (++pending >= 200 || System.currentTimeMillis() - lastFlush > 1_000) {
                            sink.flush(); pending = 0; lastFlush = System.currentTimeMillis()
                        }
                    }
                }
            } catch (_: Throwable) {
                // 进程被 destroy / 流断开都属于正常收尾
            } finally {
                runCatching { sink.flush() }
                runCatching { sink.close() }
            }
        }
        return p
    }
}
