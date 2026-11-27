package ohd.platform

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * JVM 侧 `Fs`：全部委托给 `java.nio.file`，保证迁移后的行为与迁移前**逐字节一致**。
 *
 * 这是整个 P1 的验收基石：JVM 上跑的是原来那套 NIO，所以任何行为差异都只可能来自
 * 调用点改写（而不是平台实现），用既有单测就能兜住。
 */
internal object JvmFs : Fs {

    private fun Path.toP(): Path = this

    override fun exists(p: OhdPath): Boolean = Files.exists(p.toJavaPath())

    override fun isRegularFile(p: OhdPath): Boolean = Files.isRegularFile(p.toJavaPath())

    override fun isDirectory(p: OhdPath): Boolean = Files.isDirectory(p.toJavaPath())

    override fun isExecutable(p: OhdPath): Boolean = Files.isExecutable(p.toJavaPath())

    override fun size(p: OhdPath): Long = Files.size(p.toJavaPath())

    override fun lastModifiedMillis(p: OhdPath): Long =
        Files.getLastModifiedTime(p.toJavaPath()).toMillis()

    override fun readString(p: OhdPath): String = Files.readString(p.toJavaPath())

    override fun readBytes(p: OhdPath): ByteArray = Files.readAllBytes(p.toJavaPath())

    override fun writeString(p: OhdPath, text: String) {
        val jp = p.toJavaPath()
        jp.parent?.let { Files.createDirectories(it) }
        Files.writeString(jp, text)
    }

    override fun writeBytes(p: OhdPath, bytes: ByteArray) {
        val jp = p.toJavaPath()
        jp.parent?.let { Files.createDirectories(it) }
        Files.write(jp, bytes)
    }

    override fun appendString(p: OhdPath, text: String) {
        val jp = p.toJavaPath()
        jp.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            jp, text,
            StandardOpenOptionCompat.CREATE, StandardOpenOptionCompat.APPEND,
        )
    }

    override fun createDirectories(p: OhdPath) {
        Files.createDirectories(p.toJavaPath())
    }

    // 注意：createTempFile 的 prefix 有最短长度限制（JDK 要求 >= 3），
    // 调用点传短前缀会在 JVM 上抛 IllegalArgumentException，这里保持原样不兜底——
    // 兜底会掩盖调用点的 bug，且两端行为要一致。
    override fun createTempFile(prefix: String, suffix: String): OhdPath =
        ofJavaPath(Files.createTempFile(prefix, suffix))

    override fun createTempDirectory(prefix: String): OhdPath =
        ofJavaPath(Files.createTempDirectory(prefix))

    override fun deleteIfExists(p: OhdPath): Boolean = Files.deleteIfExists(p.toJavaPath())

    override fun deleteRecursively(p: OhdPath) {
        val root = p.toJavaPath()
        if (!Files.exists(root)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun copy(src: OhdPath, dst: OhdPath, replaceExisting: Boolean) {
        val opt = if (replaceExisting) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
        dst.toJavaPath().parent?.let { Files.createDirectories(it) }
        Files.copy(src.toJavaPath(), dst.toJavaPath(), *opt)
    }

    override fun move(src: OhdPath, dst: OhdPath, replaceExisting: Boolean, atomic: Boolean) {
        val opts = buildList {
            if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
            if (atomic) add(StandardCopyOption.ATOMIC_MOVE)
        }.toTypedArray()
        dst.toJavaPath().parent?.let { Files.createDirectories(it) }
        Files.move(src.toJavaPath(), dst.toJavaPath(), *opts)
    }

    override fun list(p: OhdPath): CloseableSequence<OhdPath> {
        val stream = Files.list(p.toJavaPath())
        return object : CloseableSequence<OhdPath> {
            override fun iterator(): Iterator<OhdPath> =
                stream.iterator().asSequence().map { ofJavaPath(it) }.iterator()

            override fun close() = stream.close()
        }
    }

    override fun walk(p: OhdPath): CloseableSequence<OhdPath> {
        val stream = Files.walk(p.toJavaPath())
        return object : CloseableSequence<OhdPath> {
            override fun iterator(): Iterator<OhdPath> =
                stream.iterator().asSequence().map { ofJavaPath(it) }.iterator()

            override fun close() = stream.close()
        }
    }

    override fun forEachLine(p: OhdPath, action: (String) -> Unit) {
        Files.newBufferedReader(p.toJavaPath(), StandardCharsets.UTF_8).use { r ->
            while (true) {
                val line = r.readLine() ?: break
                action(line)
            }
        }
    }

    override fun headLines(p: OhdPath, maxLines: Int, stopAt: ((String) -> Boolean)?): List<String> {
        if (maxLines <= 0) return emptyList()
        val out = ArrayList<String>(minOf(maxLines, 64))
        Files.newBufferedReader(p.toJavaPath(), StandardCharsets.UTF_8).use { r ->
            while (out.size < maxLines) {
                val line = r.readLine() ?: break
                out.add(line)
                if (stopAt != null && stopAt(line)) break
            }
        }
        return out
    }

    override fun countLines(p: OhdPath): Long {
        var n = 0L
        forEachLine(p) { n++ }
        return n
    }

    override fun readBytesRange(p: OhdPath, offset: Long, length: Int): ByteArray {
        java.io.RandomAccessFile(p.toJavaPath().toFile(), "r").use { raf ->
            raf.seek(offset)
            val buf = ByteArray(length)
            val got = raf.read(buf)
            return if (got <= 0) ByteArray(0) else buf.copyOf(got)
        }
    }

    override fun gzipCompress(src: OhdPath, dst: OhdPath) {
        dst.toJavaPath().parent?.let { Files.createDirectories(it) }
        Files.newInputStream(src.toJavaPath()).use { ins ->
            GZIPOutputStream(Files.newOutputStream(dst.toJavaPath())).use { outs ->
                ins.copyTo(outs)
            }
        }
    }

    override fun gzipDecompress(src: OhdPath, dst: OhdPath) {
        dst.toJavaPath().parent?.let { Files.createDirectories(it) }
        GZIPInputStream(Files.newInputStream(src.toJavaPath())).use { ins ->
            Files.newOutputStream(dst.toJavaPath()).use { outs ->
                ins.copyTo(outs)
            }
        }
    }

    override fun inflateRaw(data: ByteArray): ByteArray? {
        val inf = java.util.zip.Inflater() // nowrap=false：接受 zlib 头（PNG IDAT 就是这种）
        return try {
            inf.setInput(data)
            val out = java.io.ByteArrayOutputStream(maxOf(1024, data.size * 4))
            val buf = ByteArray(64 * 1024)
            var truncated = false
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    // 没产出又没结束：输入耗尽或需要字典，流不可用，交给调用方回退
                    if (inf.needsInput() || inf.needsDictionary()) {
                        truncated = true
                        break
                    }
                } else {
                    out.write(buf, 0, n)
                }
            }
            if (truncated) null else out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            inf.end()
        }
    }

    override fun deflateRaw(data: ByteArray): ByteArray? {
        val def = java.util.zip.Deflater() // nowrap=false：产出带 zlib 头（78 9c）的流
        return try {
            def.setInput(data)
            def.finish()
            val out = java.io.ByteArrayOutputStream(maxOf(64, data.size / 2))
            val buf = ByteArray(64 * 1024)
            while (!def.finished()) {
                val n = def.deflate(buf)
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (_: Throwable) {
            null
        } finally {
            def.end()
        }
    }

    override fun gzipAppendMember(p: OhdPath, text: String) {
        // 追加一个独立 gzip 成员：多个成员首尾相接仍是合法 gzip（`zcat` / `gunzip` 都能读）。
        // 为什么不是「重写整个文件」：长跑日志可达 GB 级，重写等于每 2 秒全量拷贝一次。
        val jp = p.toJavaPath()
        jp.parent?.let { Files.createDirectories(it) }
        Files.newOutputStream(jp, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
            .use { raw -> GZIPOutputStream(raw, 64 * 1024).use { gz -> gz.write(text.encodeToByteArray()) } }
    }

    override fun forEachLineAuto(p: OhdPath, action: (String) -> Unit) {
        if (!Files.isRegularFile(p.toJavaPath())) return
        val reader = if (isGzip(p)) {
            GZIPInputStream(Files.newInputStream(p.toJavaPath()), 64 * 1024)
                .bufferedReader(StandardCharsets.UTF_8)
        } else {
            Files.newBufferedReader(p.toJavaPath(), StandardCharsets.UTF_8)
        }
        reader.use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.isNotEmpty()) action(line.trimEnd('\r'))
            }
        }
    }

    override fun tailLines(p: OhdPath, maxLines: Int): List<String> {
        if (maxLines <= 0 || !Files.isRegularFile(p.toJavaPath())) return emptyList()
        if (!isGzip(p)) return plainTail(p, maxLines)
        // gzip 只能顺序解压：环形缓冲只保留尾部 N 行，几 GB 日志也不进内存
        val ring = ArrayDeque<String>(maxLines)
        runCatching {
            forEachLineAuto(p) { line ->
                if (ring.size == maxLines) ring.removeFirst()
                ring.addLast(line)
            }
        }
        return ring.toList()
    }

    override fun readAllLines(p: OhdPath): List<String> =
        Files.readAllLines(p.toJavaPath(), StandardCharsets.UTF_8)

    override fun newWriter(p: OhdPath, append: Boolean): LineWriter {
        val jp = p.toJavaPath()
        jp.parent?.let { Files.createDirectories(it) }
        val opts = if (append) {
            arrayOf(java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
        } else {
            emptyArray()
        }
        val w = Files.newBufferedWriter(jp, StandardCharsets.UTF_8, *opts)
        return object : LineWriter {
            override fun write(text: String) { w.write(text) }
            override fun writeLine(line: String) { w.write(line); w.newLine() }
            override fun flush() = w.flush()
            override fun close() = w.close()
        }
    }

    override fun newDirectoryStream(p: OhdPath): CloseableSequence<OhdPath> {
        val ds = Files.newDirectoryStream(p.toJavaPath())
        return object : CloseableSequence<OhdPath> {
            override fun iterator(): Iterator<OhdPath> =
                ds.iterator().asSequence().map { ofJavaPath(it) }.iterator()
            override fun close() = ds.close()
        }
    }

    private fun isGzip(p: OhdPath): Boolean = p.toString().lowercase().endsWith(".gz")

    /** 明文尾部：随机定位读到末尾 1MB，避免全文件扫描。 */
    private fun plainTail(p: OhdPath, maxLines: Int): List<String> {
        java.io.RandomAccessFile(p.toJavaPath().toFile(), "r").use { raf ->
            val window = minOf(raf.length(), 1024L * 1024)
            if (window == 0L) return emptyList()
            raf.seek(raf.length() - window)
            val buf = ByteArray(window.toInt())
            raf.readFully(buf)
            var text = String(buf, StandardCharsets.UTF_8)
            // 截断窗口可能从行中间开始，丢掉第一段残缺行
            if (raf.length() > window) text = text.substringAfter('\n', "")
            return text.lineSequence().filter { it.isNotEmpty() }.toList().takeLast(maxLines)
        }
    }

    override fun zip(entries: List<Pair<String, OhdPath>>, dst: OhdPath) {
        dst.toJavaPath().parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(dst.toJavaPath())).use { zos ->
            for ((name, src) in entries) {
                zos.putNextEntry(ZipEntry(name))
                Files.newInputStream(src.toJavaPath()).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    override fun unzip(src: OhdPath, dstDir: OhdPath): List<OhdPath> {
        val out = mutableListOf<OhdPath>()
        Files.createDirectories(dstDir.toJavaPath())
        ZipFile(src.toJavaPath().toFile()).use { zf ->
            for (entry in zf.entries()) {
                val target = ofJavaPath(dstDir.toJavaPath().resolve(entry.name).normalize())
                if (entry.isDirectory) {
                    Files.createDirectories(target.toJavaPath())
                } else {
                    target.toJavaPath().parent?.let { Files.createDirectories(it) }
                    zf.getInputStream(entry).use { ins ->
                        Files.newOutputStream(target.toJavaPath()).use { ins.copyTo(it) }
                    }
                    out.add(target)
                }
            }
        }
        return out
    }

    // 统一走 commonMain 的 Digest，而不是 java.security.MessageDigest：
    // 双入口必须用同一份实现，否则「同一文件在 K/N 与 JVM 上算出不同 sha256」无法被发现。
    override fun zipEntryNames(src: OhdPath): List<String> =
        ZipFile(src.toJavaPath().toFile()).use { zf -> zf.entries().asSequence().map { it.name }.toList() }

    override fun zipEntryBytes(src: OhdPath, entry: String): ByteArray? =
        ZipFile(src.toJavaPath().toFile()).use { zf ->
            val e = zf.getEntry(entry) ?: return null
            zf.getInputStream(e).use { it.readBytes() }
        }

    override fun md5Hex(bytes: ByteArray): String = Digest.md5Hex(bytes)

    override fun sha1Hex(bytes: ByteArray): String = Digest.sha1Hex(bytes)

    override fun sha256Hex(bytes: ByteArray): String = Digest.sha256Hex(bytes)

    override fun realPath(p: OhdPath): OhdPath? = runCatching {
        ofJavaPath(p.toJavaPath().toRealPath())
    }.getOrNull()

    override fun sha256HexFile(p: OhdPath): String? = runCatching {
        // 流式：几十 MB 的 hilog 不能整个读进堆
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p.toJavaPath()).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

/** `StandardOpenOption` 的别名，避免和本文件里 `StandardCopyOption` 的 import 冲突。 */
private object StandardOpenOptionCompat {
    val CREATE: java.nio.file.OpenOption = java.nio.file.StandardOpenOption.CREATE
    val APPEND: java.nio.file.OpenOption = java.nio.file.StandardOpenOption.APPEND
}

actual val fs: Fs = JvmFs
