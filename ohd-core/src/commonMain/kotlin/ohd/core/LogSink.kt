package ohd.core

import ohd.platform.Env
import ohd.platform.OhdPath
import ohd.platform.Mutex
import ohd.platform.fs

/**
 * 日志落盘目标。
 *
 * 为什么是「多成员 gzip」而不是 zstd / zip：
 * - gzip 允许多个成员首尾相接（`cat a.gz b.gz > c.gz`、`zcat` 都合法），
 *   所以「追加」= 再追加一个成员，不需要重写任何已写内容；
 * - 压缩实现走 `ohd.platform.fs` 的 gzip 接口（JVM 用 JDK，K/N 用 zlib），**零第三方依赖**；
 * - zip 做不到：中央目录在文件尾，追加必须整体重写，长跑日志无法边跑边追加。
 *
 * 代价：每个成员独立字典，压缩率比单流略低；换来的是 10h 长跑被 kill 时，
 * 已闭合成员仍能完整解压——这恰好是排查崩溃最需要的部分。
 */
interface LogSink : AutoCloseable {
    val path: OhdPath

    /** 追加一行（不含换行符，内部补 `\n`）。 */
    fun write(line: String)

    /** 把当前缓冲闭合成一帧，保证随时被杀都留下可解压的尾巴。 */
    fun flush()
}

/** 明文：现有行为，`NNN-hilog.txt`。 */
class PlainLogSink(override val path: OhdPath) : LogSink {
    private val buf = StringBuilder()

    override fun write(line: String) {
        buf.append(line).append('\n')
    }

    override fun flush() {
        if (buf.isNotEmpty()) {
            fs.appendString(path, buf.toString())
            buf.setLength(0)
        }
    }

    override fun close() = flush()
}

/**
 * 多成员 gzip：每次 flush 闭合一个独立成员并 append。
 *
 * [frameBytes] / [frameMs]：任一到就闭合一个成员。太小会丢压缩率（成员有 18 字节头尾开销），
 * 太大则在被 kill 时丢掉最后一个成员的全部内容。8MB / 2s 是经验折中。
 */
class GzipLogSink(
    override val path: OhdPath,
    private val frameBytes: Int = 8 * 1024 * 1024,
    private val frameMs: Long = 2_000,
) : LogSink {
    /** 保护 buf 与 openedAt。write 内部会调 flush，两端底层均可重入，不会死锁。 */
    private val lock = Mutex()
    private val buf = StringBuilder()
    private var openedAt = Env.nowMillis()

    override fun write(line: String) = lock.withLock {
        buf.append(line).append('\n')
        if (buf.length >= frameBytes || Env.nowMillis() - openedAt >= frameMs) flush()
    }

    override fun flush() = lock.withLock {
        if (buf.isEmpty()) {
            openedAt = Env.nowMillis()
            return
        }
        val text = buf.toString()
        buf.setLength(0)
        openedAt = Env.nowMillis()
        fs.gzipAppendMember(path, text)
    }

    override fun close() = flush()
}

/** 按会话选项造 sink：`--log-compress gzip|none`。 */
fun logSink(path: OhdPath, compress: Boolean): LogSink =
    if (compress) GzipLogSink(path) else PlainLogSink(path)

/** 日志文件后缀：压缩开启时 `.log.gz`，否则 `.txt`。 */
fun logFileSuffix(compress: Boolean): String = if (compress) "log.gz" else "txt"

/**
 * 默认 **gzip**（产物默认压缩，省磁盘、便于回传），两种写法可以关掉：
 * - `--no-compress`（布尔，推荐）
 * - `--log-compress none`（带值写法，保留兼容）
 */
fun logCompressOf(ctx: RunContext): Boolean {
    if (ctx.options.bool("no-compress")) return false
    return !(ctx.options.str("log-compress") ?: "gzip").trim().equals("none", ignoreCase = true)
}

/**
 * 读端统一入口：按后缀自动解压。
 *
 * 不在这里做「猜格式」——只用后缀判定，产物由 ohd 自己写，后缀不会说谎；
 * 遇到用户手给的 `.txt` 也照读，兼容旧会话。
 */
object HilogFiles {
    fun isCompressed(path: OhdPath): Boolean = path.toString().lowercase().endsWith(".gz")

    /** 流式遍历全部行（几 GB 也不进内存）。多成员 gzip 由平台的解压实现自动串接。 */
    fun forEachLine(path: OhdPath, action: (String) -> Unit) {
        if (!fs.isRegularFile(path)) return
        runCatching { fs.forEachLineAuto(path, action) }
    }

    fun countLines(path: OhdPath): Int {
        if (!fs.isRegularFile(path)) return 0
        var n = 0
        runCatching { fs.forEachLineAuto(path) { n++ } }
        return n
    }

    /**
     * 尾部 N 行。明文走随机定位（不读全文）；gzip 只能顺序解压，
     * 用固定窗口环形缓冲，避免把几 GB 日志读进内存。
     * 压缩流尾部被截断时返回已解出的部分，而不是抛异常——这本来就是崩溃现场。
     */
    fun tailLines(path: OhdPath, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        if (!fs.isRegularFile(path)) return emptyList()
        return runCatching { fs.tailLines(path, maxLines) }.getOrDefault(emptyList())
    }
}
