@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package ohd.platform

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.pointed
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.CLOCKS_PER_SEC
import platform.posix.EXDEV
import platform.posix.F_OK
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.R_OK
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.S_IXUSR
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.X_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.close
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.free
import platform.posix.getpid
import platform.posix.lseek
import platform.posix.memset
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.read
import platform.posix.realpath
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rename
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.time
import platform.posix.unlink
import platform.posix.write
import platform.posix.stat as posixStat
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.inflateReset
import platform.zlib.z_stream

/**
 * K/N 侧 `Fs` 实现：posix + zlib。
 *
 * 每个方法都对应 JVM 侧 `java.nio.file` 的等价语义，**差异点统一在本文件处理**，
 * 不让调用点感知（否则双入口行为会漂移）。
 *
 * 已知的语义对齐点（JVM vs posix）：
 * - `Files.createDirectories` 会逐级建父目录；posix `mkdir` 只建一级，必须自己逐级递归；
 * - `Files.move` 跨文件系统会抛原子性异常，这里用 `EXDEV` 判定后降级为 copy+delete；
 * - `Files.deleteIfExists` 对不存在返回 false；posix `unlink` 返回 -1 且 `errno=ENOENT`，要归一；
 * - `Files.getLastModifiedTime` 用 `stat.st_mtimespec`（macOS）/`st_mtim`（Linux），
 *   本文件走 `st_mtimespec`——**Linux target 编译时需要确认该字段名**。
 */
internal object NativeFs : Fs {

    // ---------- 存在性与元信息 ----------

    override fun exists(p: OhdPath): Boolean = statOf(p.toString()) != null

    override fun isRegularFile(p: OhdPath): Boolean =
        statOf(p.toString())?.let { (it and S_IFMT) == S_IFREG.toInt() } ?: false

    override fun isDirectory(p: OhdPath): Boolean =
        statOf(p.toString())?.let { (it and S_IFMT) == S_IFDIR.toInt() } ?: false

    override fun isExecutable(p: OhdPath): Boolean = access(p.toString(), X_OK) == 0

    override fun size(p: OhdPath): Long = sizeOf(p.toString()) ?: -1L

    override fun lastModifiedMillis(p: OhdPath): Long =
        // 与 JVM 侧对齐：不存在就抛，**不返回 0**。
        // 0 会被排序当成 1970，让 `maxByOrNull` 静默选错会话——比抛异常难查得多。
        // 调用点普遍用 runCatching 兜底（见 SalvageCap / LogCap），不受影响。
        mtimeOf(p.toString()) ?: throw IllegalStateException("文件不存在或无法 stat：$p")

    // ---------- 读写 ----------

    override fun readString(p: OhdPath): String = readBytes(p).decodeToString()

    override fun readBytes(p: OhdPath): ByteArray {
        val path = p.toString()
        val fd = openFile(path, O_RDONLY, 0)
        if (fd < 0) throw IllegalStateException("open failed: $path")
        return try {
            val cap = 64 * 1024
            val buf = ByteArray(cap)
            val chunks = ArrayList<ByteArray>()
            var total = 0
            while (true) {
                val n = buf.usePinnedAt(0) { ptr -> read(fd, ptr, cap.convert()) }
                if (n <= 0) break
                chunks.add(buf.copyOf(n.toInt()))
                total += n.toInt()
            }
            if (chunks.size == 1) chunks[0] else concat(chunks, total)
        } finally {
            close(fd)
        }
    }

    override fun writeString(p: OhdPath, text: String) = writeBytes(p, text.encodeToByteArray())

    override fun writeBytes(p: OhdPath, bytes: ByteArray) {
        p.parent?.let { ensureDirs(it) }
        val path = p.toString()
        val fd = openFile(path, O_WRONLY or O_CREAT or O_TRUNC, 0x1B6 /* 0666 */)
        if (fd < 0) throw IllegalStateException("open for write failed: $path")
        try {
            writeAll(fd, bytes)
        } finally {
            close(fd)
        }
    }

    override fun appendString(p: OhdPath, text: String) {
        p.parent?.let { ensureDirs(it) }
        val path = p.toString()
        val fd = openFile(path, O_WRONLY or O_CREAT or O_APPEND, 0x1B6)
        if (fd < 0) throw IllegalStateException("open for append failed: $path")
        try {
            writeAll(fd, text.encodeToByteArray())
        } finally {
            close(fd)
        }
    }

    override fun readBytesRange(p: OhdPath, offset: Long, length: Int): ByteArray {
        val path = p.toString()
        val fd = openFile(path, O_RDONLY, 0)
        if (fd < 0) return ByteArray(0)
        return try {
            if (platform.posix.lseek(fd, offset.convert(), 0) < 0) return ByteArray(0)
            val buf = ByteArray(length)
            val n = buf.usePinnedAt(0) { ptr -> read(fd, ptr, length.convert()) }
            if (n <= 0) ByteArray(0) else buf.copyOf(n.toInt())
        } finally {
            close(fd)
        }
    }

    // ---------- 目录 ----------

    override fun createDirectories(p: OhdPath) = ensureDirs(p)

    override fun createTempFile(prefix: String, suffix: String): OhdPath {
        val dir = "/tmp"
        var i = 0
        while (i < 10_000) {
            val cand = "$dir/$prefix${getpid()}_$i$suffix"
            val fd = openFile(cand, O_WRONLY or O_CREAT or 0x200 /* O_EXCL */, 0x180 /* 0600 */)
            if (fd >= 0) {
                close(fd)
                return OhdPath.of(cand)
            }
            i++
        }
        throw IllegalStateException("createTempFile failed after retries")
    }

    override fun createTempDirectory(prefix: String): OhdPath {
        val dir = "/tmp"
        var i = 0
        while (i < 10_000) {
            val cand = "$dir/$prefix${getpid()}_$i"
            if (mkdir(cand, 0x1C0.convert() /* 0700 */) == 0) return OhdPath.of(cand)
            i++
        }
        throw IllegalStateException("createTempDirectory failed after retries")
    }

    override fun deleteIfExists(p: OhdPath): Boolean {
        val path = p.toString()
        if (!exists(p)) return false
        return if (isDirectory(p)) rmdir(path) == 0 else unlink(path) == 0
    }

    override fun deleteRecursively(p: OhdPath) {
        if (!exists(p)) return
        if (!isDirectory(p)) { unlink(p.toString()); return }
        for (child in listChildren(p)) deleteRecursively(child)
        rmdir(p.toString())
    }

    override fun copy(src: OhdPath, dst: OhdPath, replaceExisting: Boolean) {
        if (!replaceExisting && exists(dst)) throw IllegalStateException("destination exists: $dst")
        dst.parent?.let { ensureDirs(it) }
        writeBytes(dst, readBytes(src))
    }

    override fun move(src: OhdPath, dst: OhdPath, replaceExisting: Boolean, atomic: Boolean) {
        if (!replaceExisting && exists(dst)) throw IllegalStateException("destination exists: $dst")
        dst.parent?.let { ensureDirs(it) }
        if (rename(src.toString(), dst.toString()) == 0) return
        // 要求原子性时不允许退化成 copy+delete：半截文件比失败更糟。
        if (atomic) throw IllegalStateException("atomic move failed (cross-device?): $src -> $dst")
        // 跨文件系统：rename 失败 → 退化为 copy + delete（与 Files.move 的降级语义一致）
        writeBytes(dst, readBytes(src))
        unlink(src.toString())
    }

    override fun list(p: OhdPath): CloseableSequence<OhdPath> = object : CloseableSequence<OhdPath> {
        // 先收集再返回：避免调用方长时间持有 DIR 句柄（K/N 没有 finalizer 兜底）
        private val items: List<OhdPath> = listChildren(p)
        override fun iterator(): Iterator<OhdPath> = items.iterator()
        override fun close() = Unit
    }

    override fun walk(p: OhdPath): CloseableSequence<OhdPath> = object : CloseableSequence<OhdPath> {
        private val items: List<OhdPath> = buildList { walkInto(p, this) }
        override fun iterator(): Iterator<OhdPath> = items.iterator()
        override fun close() = Unit
    }

    override fun forEachLine(p: OhdPath, action: (String) -> Unit) {
        // 与 JVM 的 `readLine()` 对齐：空行要传（`""`），行尾 `\r` 要去掉。
        // 日志读取刻意跳过空行，那条路走 forEachLineAuto，不要混用。
        streamPlainLines(p, keepEmpty = true, action = action)
    }

    override fun headLines(p: OhdPath, maxLines: Int, stopAt: ((String) -> Boolean)?): List<String> {
        if (maxLines <= 0) return emptyList()
        // 崩溃日志/脚本头部都在文件开头，读全量再截断在这里是可接受的：
        // 真正 GB 级的长跑日志走 forEachLineAuto(流式)，不会经过这条路径。
        val text = readString(p)
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(minOf(maxLines, 64))
        for (line in text.lineSequence()) {
            out.add(line)
            if (out.size >= maxLines) break
            if (stopAt != null && stopAt(line)) break
        }
        return out
    }

    override fun countLines(p: OhdPath): Long {
        // JVM 侧是「`readLine()` 非 null 就 +1」，即空行也算一行、末尾换行不多算一行。
        // 这里必须走同一个 forEachLine，不要自己按 '\n' 计数，否则带空行的文件两端会差。
        var n = 0L
        forEachLine(p) { n++ }
        return n
    }

    // ---------- 压缩 ----------

    override fun gzipCompress(src: OhdPath, dst: OhdPath) =
        writeBytes(dst, gzipBytes(readBytes(src)))

    override fun gzipDecompress(src: OhdPath, dst: OhdPath) =
        writeBytes(dst, gunzipBytes(readBytes(src)))

    override fun gzipAppendMember(p: OhdPath, text: String) {
        // 追加一个**独立 gzip 成员**：多成员首尾相接仍是合法 gzip（`zcat` / `gunzip` 都读得动）。
        // 不做「重写整个文件」——长跑日志可达 GB 级，重写等于每 2 秒全量拷贝一次。
        p.parent?.let { ensureDirs(it) }
        val path = p.toString()
        val fd = openFile(path, O_WRONLY or O_CREAT or O_APPEND, 0x1B6 /* 0666 */)
        if (fd < 0) throw IllegalStateException("open for gzip append failed: $path")
        try {
            writeAll(fd, gzipBytes(text.encodeToByteArray()))
        } finally {
            close(fd)
        }
    }

    // ---------- 行读取 ----------

    override fun readAllLines(p: OhdPath): List<String> {
        val text = readString(p)
        if (text.isEmpty()) return emptyList()
        // 与 JVM `Files.readAllLines` 对齐：末尾换行不产生空末行
        return text.split('\n').let { parts ->
            if (parts.last().isEmpty()) parts.dropLast(1) else parts
        }.map { it.trimEnd('\r') }
    }

    override fun forEachLineAuto(p: OhdPath, action: (String) -> Unit) {
        if (!isRegularFile(p)) return
        // gzip 走流式解压；明文也必须流式——日志 GB 级，readString 全量读会 OOM。
        if (isGzipFile(p)) {
            streamGunzipLines(readBytes(p), action)
        } else {
            streamPlainLines(p, keepEmpty = false, action = action)
        }
    }

    override fun tailLines(p: OhdPath, maxLines: Int): List<String> {
        if (maxLines <= 0 || !isRegularFile(p)) return emptyList()
        if (!isGzipFile(p)) return plainTail(p, maxLines)
        // gzip 只能顺序解压：环形缓冲只留尾部 N 行，几 GB 也不进内存。
        val ring = ArrayDeque<String>(maxLines)
        streamGunzipLines(readBytes(p)) { line ->
            if (ring.size == maxLines) ring.removeFirst()
            ring.addLast(line)
        }
        return ring.toList()
    }

    override fun newWriter(p: OhdPath, append: Boolean): LineWriter {
        p.parent?.let { ensureDirs(it) }
        val path = p.toString()
        val flags = if (append) O_WRONLY or O_CREAT or O_APPEND else O_WRONLY or O_CREAT or O_TRUNC
        val fd = openFile(path, flags, 0x1B6)
        if (fd < 0) throw IllegalStateException("open for write failed: $path")
        return object : LineWriter {
            private var closed = false
            override fun write(text: String) {
                check(!closed) { "writer already closed: $path" }
                if (text.isNotEmpty()) writeAll(fd, text.encodeToByteArray())
            }

            override fun writeLine(line: String) {
                check(!closed) { "writer already closed: $path" }
                writeAll(fd, (line + "\n").encodeToByteArray())
            }
            override fun flush() {
                // 不加 fsync：日志落盘用 gzipAppendMember 的「成员边界」语义，
                // 这里只保证数据进了内核缓冲，避免每行一次磁盘同步。
            }
            override fun close() {
                if (closed) return
                closed = true
                close(fd)
            }
        }
    }

    override fun newDirectoryStream(p: OhdPath): CloseableSequence<OhdPath> = list(p)

    // ---------- zip 条目（只读，SymCap 找 .so 用）----------

    override fun zipEntryNames(src: OhdPath): List<String> {
        val bytes = readBytes(src)
        return parseZipCentralDirectory(bytes).map { it.first }
    }

    override fun zipEntryBytes(src: OhdPath, entry: String): ByteArray? {
        val bytes = readBytes(src)
        val target = parseZipCentralDirectory(bytes).firstOrNull { it.first == entry } ?: return null
        return extractZipEntry(bytes, target.second)
    }

    override fun zip(entries: List<Pair<String, OhdPath>>, dst: OhdPath) {
        throw UnsupportedOperationException("zip 在 K/N 侧尚未实现（P2-B3）；当前请用 JVM 入口的 bundle --zip")
    }

    override fun unzip(src: OhdPath, dstDir: OhdPath): List<OhdPath> {
        throw UnsupportedOperationException("unzip 在 K/N 侧尚未实现（P2-B3）；当前请用 JVM 入口")
    }

    // ---------- 摘要 ----------

    override fun md5Hex(bytes: ByteArray): String = Digest.md5Hex(bytes)

    override fun sha1Hex(bytes: ByteArray): String = Digest.sha1Hex(bytes)

    override fun sha256Hex(bytes: ByteArray): String = Digest.sha256Hex(bytes)

    override fun realPath(p: OhdPath): OhdPath? {
        val resolved = realpath(p.toString(), null) ?: return null
        return try {
            OhdPath.of(resolved.toKString())
        } finally {
            free(resolved)
        }
    }

    override fun sha256HexFile(p: OhdPath): String? {
        // K/N 侧没有 java.security，用文件级流式读 + 纯 Kotlin Digest。
        // Digest 是一次性 API（吃完整 ByteArray），所以这里先分块收集再算——
        // 对 GB 级文件仍会占内存，但避免了「读两遍」的 O(n²)。
        // 若将来出现真·GB 级需求，应给 Digest 加增量式 update/final。
        if (!isRegularFile(p)) return null
        val path = p.toString()
        val fd = openFile(path, O_RDONLY, 0)
        if (fd < 0) return null
        return try {
            val parts = ArrayList<ByteArray>()
            var total = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = buf.usePinnedAt(0) { ptr -> read(fd, ptr, buf.size.convert()) }
                if (n <= 0) break
                val got = n.toInt()
                parts.add(buf.copyOf(got))
                total += got
            }
            val all = ByteArray(total)
            var at = 0
            for (part in parts) {
                part.copyInto(all, at)
                at += part.size
            }
            Digest.sha256Hex(all)
        } finally {
            close(fd)
        }
    }

    // ---------- 内部工具 ----------

    private fun statOf(path: String): Int? = memScoped {
        val st = alloc<posixStat>()
        if (posixStat(path, st.ptr) != 0) null else st.st_mode.toInt()
    }

    private fun sizeOf(path: String): Long? = memScoped {
        val st = alloc<posixStat>()
        if (posixStat(path, st.ptr) != 0) null else st.st_size
    }

    private fun mtimeOf(path: String): Long? = memScoped {
        val st = alloc<posixStat>()
        if (posixStat(path, st.ptr) != 0) null else posixStatMtimeSec(st) * 1000L
    }

    private fun ensureDirs(p: OhdPath) {
        val s = p.toString()
        if (s.isEmpty() || s == "/" || isDirectory(p)) return
        p.parent?.let { ensureDirs(it) }
        mkdir(s, 0x1ED.convert() /* 0755 */)
    }

    private fun listChildren(p: OhdPath): List<OhdPath> {
        val dir = opendir(p.toString()) ?: return emptyList()
        val out = ArrayList<OhdPath>()
        try {
            while (true) {
                val e = readdir(dir) ?: break
                val name = e.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                out.add(p.resolve(name))
            }
        } finally {
            closedir(dir)
        }
        return out
    }

    private fun walkInto(p: OhdPath, out: MutableList<OhdPath>) {
        out.add(p)
        if (!isDirectory(p)) return
        for (c in listChildren(p)) walkInto(c, out)
    }

    // ---------- gzip / zip 的字节级工具 ----------

    private fun isGzipFile(p: OhdPath): Boolean = p.toString().lowercase().endsWith(".gz")

    /**
     * 产出一个**完整 gzip 成员**（magic `1f 8b`）。
     *
     * 为什么不用 `compress2`：那是**裸 zlib 流**（`78 9c` 开头），`gunzip` / `zcat` / wireshark
     * 都读不了。这里用 `deflateInit2` 的 `windowBits = 15 + 16 = 31` 直接产出 gzip 容器。
     */
    override fun deflateRaw(data: ByteArray): ByteArray? = runCatching {
        // windowBits = 15 → 裸 zlib 容器（78 9c 头）。注意不是 31（那是 gzip）：
        // 两者是不同容器，PNG 的 IDAT 要的是前者。
        deflateWithWindowBits(data, 15)
    }.getOrNull()

    private fun gzipBytes(raw: ByteArray): ByteArray = memScoped {
        // windowBits = 15 + 16 = 31 → gzip 容器
        deflateWithWindowBits(raw, 31)
    }

    /**
     * 用指定 [windowBits] 压缩：[windowBits]=15 出 zlib 流，=31 出 gzip 流。
     *
     * 抽出来是为了让 gzip 与 zlib 两条路共用同一份边界处理（空输入、分块、清理），
     * 避免两份实现迟早分叉。
     */
    private fun deflateWithWindowBits(raw: ByteArray, windowBits: Int): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        memset(strm.ptr, 0, sizeOf<z_stream>().convert())
        val initRc = deflateInit2_(
            strm.ptr,
            Z_DEFAULT_COMPRESSION,
            Z_DEFLATED,
            windowBits, // 15 → zlib(78 9c)；31 = 15+16 → gzip(1f 8b)
            8,
            0, // Z_DEFAULT_STRATEGY
            ZLIB_VERSION,
            sizeOf<z_stream>().convert(),
        )
        if (initRc != Z_OK) throw IllegalStateException("deflateInit2 failed: rc=$initRc")

        val parts = ArrayList<ByteArray>()
        try {
            val chunk = ByteArray(64 * 1024)
            // 空输入也要产出合法（空）成员；addressOf(0) 对空数组是 UB，故给 1 字节占位但 avail_in=0
            val src = if (raw.isEmpty()) ByteArray(1) else raw
            chunk.usePinnedAt(0) { outP ->
                src.usePinnedAt(0) { srcP ->
                    strm.next_in = srcP.reinterpret()
                    strm.avail_in = raw.size.convert()
                    while (true) {
                        strm.next_out = outP.reinterpret()
                        strm.avail_out = chunk.size.convert()
                        val rc = deflate(strm.ptr, Z_FINISH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) parts.add(chunk.copyOf(produced))
                        if (rc == Z_STREAM_END) break
                        // Z_FINISH 下返回 Z_OK 只表示「还有输出待取」，不是错误
                        if (rc != Z_OK) throw IllegalStateException("deflate failed: rc=$rc")
                    }
                }
            }
        } finally {
            deflateEnd(strm.ptr)
        }
        concat(parts, parts.sumOf { it.size })
    }

    /** 解 gzip，支持**多成员串接**（`inflateReset` 后继续吃剩余输入）。 */
    private fun gunzipAppend(raw: ByteArray, onChunk: (ByteArray, Int) -> Unit): Unit = memScoped {
        val strm = alloc<z_stream>()
        memset(strm.ptr, 0, sizeOf<z_stream>().convert())
        // windowBits = 15 + 32：自动识别 gzip 与 zlib 两种头
        val initRc = inflateInit2_(strm.ptr, 47, ZLIB_VERSION, sizeOf<z_stream>().convert())
        if (initRc != Z_OK) throw IllegalStateException("inflateInit2 failed: rc=$initRc")

        try {
            val chunk = ByteArray(64 * 1024)
            val src = if (raw.isEmpty()) ByteArray(1) else raw
            chunk.usePinnedAt(0) { outP ->
                src.usePinnedAt(0) { srcP ->
                    strm.next_in = srcP.reinterpret()
                    strm.avail_in = raw.size.convert()
                    while (true) {
                        strm.next_out = outP.reinterpret()
                        strm.avail_out = chunk.size.convert()
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) onChunk(chunk, produced)
                        when (rc) {
                            Z_STREAM_END -> {
                                // 剩余输入 = 下一个成员；inflateReset 保留 next_in/avail_in
                                if (strm.avail_in.toInt() <= 0) break
                                val r = inflateReset(strm.ptr)
                                if (r != Z_OK) throw IllegalStateException("inflateReset failed: rc=$r")
                            }
                            Z_OK -> {}
                            else -> throw IllegalStateException("inflate failed: rc=$rc")
                        }
                    }
                }
            }
        } finally {
            inflateEnd(strm.ptr)
        }
    }

    override fun inflateRaw(data: ByteArray): ByteArray? = runCatching {
        // 直接复用 gunzipAppend：它的 inflateInit2_(windowBits = 47) 自动识别
        // gzip 与 **裸 zlib** 两种头，PNG 的 IDAT 正是后者。
        // 不另写一套 inflate——两份实现迟早会在边界条件上分叉。
        gunzipBytes(data)
    }.getOrNull()

    private fun gunzipBytes(raw: ByteArray): ByteArray {
        val parts = ArrayList<ByteArray>()
        gunzipAppend(raw) { chunk, n -> parts.add(chunk.copyOf(n)) }
        return concat(parts, parts.sumOf { it.size })
    }

    // ---------- 行切分（按字节找 \n，避免 UTF-8 多字节字符被块边界切坏）----------

    private class LineSplitter(
        private val action: (String) -> Unit,
        /**
         * 是否把空行也交给 [action]。
         *
         * JVM 的 `readLine()` 对空行返回 `""`（不是 null），所以 `forEachLine` 必须传空行；
         * 而日志读取（`forEachLineAuto`）历来跳过空行。两种语义都要，用一个开关区分，
         * 避免「同一个方法名在两端行为不同」这种最难查的坑。
         */
        private val keepEmpty: Boolean = false,
    ) {
        private var buf = ByteArray(256)
        private var len = 0

        fun feed(chunk: ByteArray, count: Int) {
            var i = 0
            while (i < count) {
                val b = chunk[i]
                if (b == '\n'.code.toByte()) {
                    emit()
                } else {
                    append(b)
                }
                i++
            }
        }

        /** 文件末尾没有换行时，最后一行也要吐出来。 */
        fun finish() {
            if (len > 0) emit()
        }

        private fun append(b: Byte) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            buf[len++] = b
        }

        private fun emit() {
            if (len > 0 || keepEmpty) {
                val s = buf.decodeToString(0, len)
                // 与 JVM `readLine()` 对齐：吃掉 CRLF 的 \r
                action(if (s.endsWith("\r")) s.dropLast(1) else s)
            }
            len = 0
        }
    }

    private fun streamGunzipLines(raw: ByteArray, action: (String) -> Unit) {
        val splitter = LineSplitter(action)
        gunzipAppend(raw) { chunk, n -> splitter.feed(chunk, n) }
        splitter.finish()
    }

    /** 明文流式读行：分块读，不整文件进内存。 */
    private fun streamPlainLines(p: OhdPath, keepEmpty: Boolean = false, action: (String) -> Unit) {
        val path = p.toString()
        val fd = openFile(path, O_RDONLY, 0)
        if (fd < 0) return
        try {
            val splitter = LineSplitter(action, keepEmpty = keepEmpty)
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = chunk.usePinnedAt(0) { ptr -> read(fd, ptr, chunk.size.convert()) }
                if (n <= 0) break
                splitter.feed(chunk, n.toInt())
            }
            splitter.finish()
        } finally {
            close(fd)
        }
    }

    /** 明文尾部：随机定位读末尾 1MB，避免全文件扫描（与 JVM `plainTail` 同语义）。 */
    private fun plainTail(p: OhdPath, maxLines: Int): List<String> {
        val path = p.toString()
        val fd = openFile(path, O_RDONLY, 0)
        if (fd < 0) return emptyList()
        try {
            val total = platform.posix.lseek(fd, 0, 2 /* SEEK_END */)
            if (total <= 0) return emptyList()
            val window = minOf(total, 1024L * 1024)
            if (platform.posix.lseek(fd, total - window, 0) < 0) return emptyList()
            val buf = ByteArray(window.toInt())
            val n = buf.usePinnedAt(0) { ptr -> read(fd, ptr, window.convert()) }
            if (n <= 0) return emptyList()
            var text = buf.decodeToString(0, n.toInt())
            // 窗口从行中间开始时，丢掉第一段残缺行
            if (total > window) text = text.substringAfter('\n', "")
            return text.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(maxLines)
        } finally {
            close(fd)
        }
    }

    // ---------- zip 中央目录解析（只读；zip 写入仍走 JVM 入口）----------

    private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int): Long =
        (u16(b, at).toLong()) or (u16(b, at + 2).toLong() shl 16)

    /**
     * 返回 (条目名 → 中央目录里记录的信息)。
     *
     * 走中央目录而不是顺序扫 local header：local header 可能用 data descriptor（sizes 为 0），
     * 只有中央目录的大小/偏移可靠。
     */
    private fun parseZipCentralDirectory(b: ByteArray): List<Pair<String, ZipEntry>> {
        if (b.size < 22) return emptyList()
        // EOCD 签名 0x06054b50，可能带注释，从尾部往回找
        var eocd = -1
        val stop = maxOf(0, b.size - 22 - 0xFFFF)
        var i = b.size - 22
        while (i >= stop) {
            if (u32(b, i) == 0x06054b50L) { eocd = i; break }
            i--
        }
        if (eocd < 0) return emptyList()
        val count = u16(b, eocd + 10)
        var off = u32(b, eocd + 16).toInt()
        val out = ArrayList<Pair<String, ZipEntry>>(count)
        var guard = 0
        while (guard++ < count && off + 46 <= b.size) {
            if (u32(b, off) != 0x02014b50L) break
            val method = u16(b, off + 10)
            val compressedSize = u32(b, off + 20).toInt()
            val uncompressedSize = u32(b, off + 24).toInt()
            val nameLen = u16(b, off + 28)
            val extraLen = u16(b, off + 30)
            val commentLen = u16(b, off + 32)
            val localOffset = u32(b, off + 42).toInt()
            val name = b.decodeToString(off + 46, off + 46 + nameLen)
            out.add(
                name to ZipEntry(method, compressedSize, uncompressedSize, localOffset),
            )
            off += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    private class ZipEntry(
        val method: Int,
        val compressedSize: Int,
        val uncompressedSize: Int,
        val localHeaderOffset: Int,
    )

    private fun extractZipEntry(b: ByteArray, e: ZipEntry): ByteArray? {
        val lh = e.localHeaderOffset
        if (lh < 0 || lh + 30 > b.size) return null
        if (u32(b, lh) != 0x04034b50L) return null
        val nameLen = u16(b, lh + 26)
        val extraLen = u16(b, lh + 28)
        val dataAt = lh + 30 + nameLen + extraLen
        if (dataAt + e.compressedSize > b.size) return null
        val payload = b.copyOfRange(dataAt, dataAt + e.compressedSize)
        return when (e.method) {
            0 -> payload // stored
            8 -> inflateRaw(payload, e.uncompressedSize)
            else -> null
        }
    }

    /** 原始 deflate 流（无 zlib/gzip 头）：`windowBits = -15`。 */
    private fun inflateRaw(payload: ByteArray, expectedSize: Int): ByteArray = memScoped {
        val strm = alloc<z_stream>()
        memset(strm.ptr, 0, sizeOf<z_stream>().convert())
        val initRc = inflateInit2_(strm.ptr, -15, ZLIB_VERSION, sizeOf<z_stream>().convert())
        if (initRc != Z_OK) throw IllegalStateException("inflateInit2(raw) failed: rc=$initRc")
        val parts = ArrayList<ByteArray>()
        try {
            val chunk = ByteArray(maxOf(64 * 1024, expectedSize.coerceAtLeast(0)))
            val src = if (payload.isEmpty()) ByteArray(1) else payload
            chunk.usePinnedAt(0) { outP ->
                src.usePinnedAt(0) { srcP ->
                    strm.next_in = srcP.reinterpret()
                    strm.avail_in = payload.size.convert()
                    while (true) {
                        strm.next_out = outP.reinterpret()
                        strm.avail_out = chunk.size.convert()
                        val rc = inflate(strm.ptr, Z_NO_FLUSH)
                        val produced = chunk.size - strm.avail_out.toInt()
                        if (produced > 0) parts.add(chunk.copyOf(produced))
                        if (rc == Z_STREAM_END) break
                        if (rc != Z_OK) throw IllegalStateException("inflate(raw) failed: rc=$rc")
                    }
                }
            }
        } finally {
            inflateEnd(strm.ptr)
        }
        concat(parts, parts.sumOf { it.size })
    }

    private fun openFile(path: String, flags: Int, mode: Int): Int =
        platform.posix.open(path, flags, mode)

    private fun writeAll(fd: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        var off = 0
        while (off < bytes.size) {
            val n = bytes.usePinnedAt(off) { ptr -> write(fd, ptr, (bytes.size - off).convert()) }
            if (n <= 0) break
            off += n.toInt()
        }
    }

    private fun concat(chunks: List<ByteArray>, total: Int): ByteArray {
        val out = ByteArray(total)
        var off = 0
        for (c in chunks) { c.copyInto(out, off); off += c.size }
        return out
    }
}

actual val fs: Fs = NativeFs

// ---- cinterop 小工具：把 Kotlin ByteArray 钉住后交给 C ----

private inline fun <R> ByteArray.usePinned0(crossinline block: (CPointer<ByteVar>) -> R): R =
    usePinned { block(it.addressOf(0)) }

private inline fun <R> ByteArray.usePinnedAt(offset: Int, crossinline block: (CPointer<ByteVar>) -> R): R =
    usePinned { block(it.addressOf(offset)) }
