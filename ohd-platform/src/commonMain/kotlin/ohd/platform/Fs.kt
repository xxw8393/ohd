package ohd.platform

/**
 * 宿主文件系统操作。
 *
 * 只收录仓库里**实际用到**的那 29 个方法（从 283 处 `Files.*` 调用归纳而来），
 * 不做「完整 NIO 复刻」——多一个方法就多一份两端实现与两端测试。
 *
 * 设计原则：
 * - **流式接口保留 `AutoCloseable`**：`Files.list` / `Files.walk` 在扫描大目录（salvage 全盘找 .so）
 *   时必须边读边处理，若这里改成返回 `List`，就把流式变成全量，峰值内存会炸。
 * - **跨平台结果一致性优先于内部实现**：`normalize`、`relativize` 这类路径语义统一由
 *   `OhdPath` 实现，本接口只负责 IO。
 */
interface Fs {

    fun exists(p: OhdPath): Boolean

    fun isRegularFile(p: OhdPath): Boolean

    fun isDirectory(p: OhdPath): Boolean

    fun isExecutable(p: OhdPath): Boolean

    fun size(p: OhdPath): Long

    fun lastModifiedMillis(p: OhdPath): Long

    fun readString(p: OhdPath): String

    fun readBytes(p: OhdPath): ByteArray

    fun writeString(p: OhdPath, text: String)

    fun writeBytes(p: OhdPath, bytes: ByteArray)

    /** 追加写，不存在则创建。 */
    fun appendString(p: OhdPath, text: String)

    fun createDirectories(p: OhdPath)

    fun createTempFile(prefix: String, suffix: String): OhdPath

    fun createTempDirectory(prefix: String): OhdPath

    fun deleteIfExists(p: OhdPath): Boolean

    fun deleteRecursively(p: OhdPath)

    fun copy(src: OhdPath, dst: OhdPath, replaceExisting: Boolean = false)

    /**
     * 移动/重命名。
     *
     * [atomic] 为 true 时要求「要么完整换成新文件，要么保持旧文件」——WorldMap 的
     * `.tmp` + 改名落盘靠它：非原子替换在断电/被杀时会留半截 JSON，而 load 的容错是
     * 「当空地图」，攒了几周的地图会静默归零。
     *
     * JVM 侧传 `ATOMIC_MOVE`（同目录 rename 不跨文件系统，必然成功）；K/N 侧本来就只有
     * `rename(2)`，本身就是原子的，参数仅用于「不允许退化成 copy+delete」。
     */
    fun move(src: OhdPath, dst: OhdPath, replaceExisting: Boolean = false, atomic: Boolean = false)

    /** 列一层子项（非递归）。必须 `.use {}` 关闭。 */
    fun list(p: OhdPath): CloseableSequence<OhdPath>

    /** 递归遍历。必须 `.use {}` 关闭。 */
    fun walk(p: OhdPath): CloseableSequence<OhdPath>

    /**
     * 限制深度的递归遍历（`Files.walk(p, maxDepth)` 的等价物）。
     *
     * [maxDepth] 的含义与 JDK 一致：[p] 自身算第 0 层。`salvage --so` 全盘找 `.so` 时
     * 用它挡住 `sdcard` 下的深层目录，否则遍历量会翻好几倍。
     *
     * 默认实现基于 [walk] 做深度过滤：**不是剪枝**——底层仍会完整遍历，只是把超深条目滤掉。
     * 语义与调用点一致即可（现有调用点只是为了「别把 sdcard 深层目录算进来」）；
     * 哪天真需要在 K/N 上省掉遍历开销，再给两端补原生剪枝实现。
     */
    fun walk(p: OhdPath, maxDepth: Int): CloseableSequence<OhdPath> {
        if (maxDepth < 0) return EmptyCloseableSequence()
        val base = walk(p)
        val root = p.toAbsolutePath()
        return object : CloseableSequence<OhdPath> {
            override fun iterator(): Iterator<OhdPath> =
                base.iterator().asSequence()
                    // 用 OhdPath 自己的相对化数层数，不按分隔符个数硬算：
                    // 尾斜杠、`..`、多分隔符都不会把层数算歪。
                    .filter { depthUnder(root, it) <= maxDepth }
                    .iterator()

            override fun close() = base.close()
        }
    }

    fun forEachLine(p: OhdPath, action: (String) -> Unit)

    /**
     * 只读前 [maxLines] 行，或读到 [stopAt] 命中的行为止。
     *
     * 为什么需要它：崩溃日志的 `Reason:` 在第 29 行左右，而文件可能有几十 MB；
     * 早先调用点用 `BufferedReader.readLine()` 循环 + `break`，能读完就停。
     * 若改用 [forEachLine] 就必须整文件读完，大日志上是明显退化。
     * [stopAt] 命中的那一行**会**包含在返回值里。
     */
    fun headLines(p: OhdPath, maxLines: Int, stopAt: ((String) -> Boolean)? = null): List<String>

    /** 全部行（小文件用；大文件请用 [forEachLine]）。 */
    fun readAllLines(p: OhdPath): List<String>

    /** 逐行写（覆盖）。返回的 writer 需 close，close 时落盘。 */
    fun newWriter(p: OhdPath, append: Boolean = false): LineWriter

    /** 逐条目录项（非递归流式，必须 close）。 */
    fun newDirectoryStream(p: OhdPath): CloseableSequence<OhdPath>

    /**
     * 按 glob 过滤的目录流（`newDirectoryStream(dir, "*samples.csv")`）。
     *
     * 只支持 `*`（匹配文件名内任意串）这一种模式——调用点全部是单 `*` 前缀/后缀，
     * 为此引入 `java.nio.PathMatcher` 不值得。K/N 侧用同一份实现，不依赖平台 glob。
     *
     * 过滤后仍是 [CloseableSequence]：直接 `filter` 会退化成 `Sequence`，越过 `use {}` 丢句柄。
     */
    fun newDirectoryStream(p: OhdPath, glob: String): CloseableSequence<OhdPath> {
        val base = list(p)
        return object : CloseableSequence<OhdPath> {
            override fun iterator(): Iterator<OhdPath> =
                base.iterator().asSequence()
                    .filter { OhdGlob.matches(glob, it.fileName?.toString() ?: "") }
                    .iterator()

            override fun close() = base.close()
        }
    }

    fun countLines(p: OhdPath): Long

    fun readBytesRange(p: OhdPath, offset: Long, length: Int): ByteArray

    fun gzipCompress(src: OhdPath, dst: OhdPath)

    fun gzipDecompress(src: OhdPath, dst: OhdPath)

    /**
     * 解开**裸 zlib 流**（`78 xx` 开头，无 gzip 头）。
     *
     * PNG 的 `IDAT` 就是这种流，见 [PngDecode]。失败返回 null，由调用方决定回退还是报错。
     * 单独于 [gzipDecompress] 存在：gzip 有容器头，zlib 没有，两者不能混用同一入口。
     */
    fun inflateRaw(data: ByteArray): ByteArray?

    /**
     * 压成**裸 zlib 流**，与 [inflateRaw] 互为逆运算。
     *
     * PNG 的 `IDAT` 要的是这种（`78 9c` 头），**不是** gzip。
     * 注意 gzip 与 zlib 是两种容器：gzip 有 10 字节头、zlib 只有 2 字节，
     * 剥 gzip 头尾**得不到** zlib，必须分别压缩。
     */
    fun deflateRaw(data: ByteArray): ByteArray?

    /**
     * 追加一个 **独立 gzip 成员**（多成员拼接，`cat a.gz b.gz` 与 `zcat` 均合法）。
     *
     * 日志落盘用它：每次 flush 追加一个成员，进程被 kill 时已闭合的成员仍可完整解压。
     * 必须产出标准 gzip 容器（magic `1f 8b`），不能用裸 zlib 流——否则 wireshark/gunzip 读不了。
     */
    fun gzipAppendMember(p: OhdPath, text: String)

    /**
     * 按行流式读取，自动识别 gzip（多成员自动串接）。
     *
     * **不走 `readString` 全量读**：日志可到 GB 级，全量读会 OOM。
     */
    fun forEachLineAuto(p: OhdPath, action: (String) -> Unit)

    /** 尾部 N 行（明文走随机定位，gzip 顺序解压 + 环形缓冲）。 */
    fun tailLines(p: OhdPath, maxLines: Int): List<String>

    /** zip 打包（`bundle --zip`）。[entries] 为「包内路径 → 源文件」。 */
    fun zip(entries: List<Pair<String, OhdPath>>, dst: OhdPath)

    /** zip 解包，返回解出的文件列表。 */
    fun unzip(src: OhdPath, dstDir: OhdPath): List<OhdPath>

    /** zip 内条目名列表（不解包）。SymCap 用它找 `.so`。 */
    fun zipEntryNames(src: OhdPath): List<String>

    /** 从 zip 里读单个条目的字节。 */
    fun zipEntryBytes(src: OhdPath, entry: String): ByteArray?

    fun md5Hex(bytes: ByteArray): String

    fun sha1Hex(bytes: ByteArray): String

    fun sha256Hex(bytes: ByteArray): String

    /**
     * 对**文件**算 SHA-256，流式读取，不把整个文件读进堆。
     *
     * 为什么单独一个入口：证据包里的 hilog 常有几十 MB，甚至 GB 级。
     * `sha256Hex(readBytes(p))` 会把整份日志塞进内存；而用 `readBytesRange`
     * 分块读每次都重开文件（O(n²)）。这里有真正的流式实现。
     *
     * 失败返回 null（文件不存在/不可读），调用方按原降级路径处理。
     */
    fun sha256HexFile(p: OhdPath): String?

    /**
     * 解析符号链接后的真实路径；解析失败返回 null。
     *
     * 用途：macOS 的 `/tmp`、`/var` 是指向 `/private/...` 的符号链接，
     * 而目录遍历默认不跟随链接——不先解析会「目录明明存在却走不到」。
     *
     * K/N 侧用 `realpath(3)`；JVM 侧 `Path.toRealPath()`。
     */
    fun realPath(p: OhdPath): OhdPath?
}

/** 逐行写入器；close 后落盘。 */
interface LineWriter : AutoCloseable {
    /**
     * 写一段文本，**不**自动补换行。
     *
     * 与 [writeLine] 并存是因为两批调用点语义不同：CSV/脚本是「一行一条记录」，
     * 用 [writeLine]；`HilogStream` 这类拼接分片的场景必须能写裸片段。
     */
    fun write(text: String)

    fun writeLine(line: String)

    fun flush()
}

/** 空序列：`maxDepth < 0` / 目录不存在等早退场景用，省去调用点判空。 */
private class EmptyCloseableSequence<out T> : CloseableSequence<T> {
    override fun iterator(): Iterator<T> = emptyList<T>().iterator()
    override fun close() = Unit
}

/**
 * 需要显式关闭的序列。
 *
 * 为什么不用 `kotlin.sequences.Sequence` + 手动 close：调用点会忘记关，句柄泄漏在
 * 「每步都跑一次」的 agent 场景下会累积。包装成 `CloseableSequence` 后，`use {}` 是唯一用法。
 *
 * 继承 [Sequence] 是为了让 `filter` / `map` / `sorted` / `count` 等标准库扩展直接可用：
 * 调用点原先写的是 `Files.list(..)` 的 Java Stream，迁到 `OhdPath` 后如果只留 `iterator()`，
 * 这些惰性算子会全部变成 `Unresolved reference`。惰性不改变 `use {}` 的关闭契约。
 */
interface CloseableSequence<out T> : Sequence<T>, AutoCloseable

/** 宿主文件系统的唯一入口。JVM 侧是 `java.nio.file`，K/N 侧是 posix。 */
expect val fs: Fs
