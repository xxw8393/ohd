package ohd.platform

/**
 * 最小 PNG 解码器：只覆盖 `snapshot_display -t png` 实际产出的那种剖面。
 *
 * ## 为什么自己写
 *
 * `ohd shot` 从设备取的是真 PNG（RGBA non-interlaced），而 `ohd diff` 要逐像素比较。
 * 原实现靠 `javax.imageio` 解码，这把整条 diff 链路钉在了 JVM 上——但 `diff()` 与
 * `highFrequencyEnergy()` 本身都是纯 Kotlin，只有「解码」这一步需要 JDK。
 * 自己解 PNG 后整条链路可搬到 K/N；而且 PNG 是无损的，判据比 JPEG 干净
 * （JPEG 的块效应/振铃会在平坦区域伪造高频能量）。
 *
 * ## 支持范围（够用即止）
 *
 * - 位深 8（设备产物就是 8-bit）
 * - 颜色类型 6/2/0/4（RGBA / RGB / 灰度 / 灰度+alpha）
 * - 非隔行（interlaced = 0）
 * - 只取 `IDAT`；忽略 `tRNS`/`gAMA` 等辅助块
 *
 * 不满足就返回 null，由调用方回退到各自平台的解码器。**不猜着解**——
 * 解错了比解不了更糟（会给出错误的 diff 结论）。
 *
 * ## inflate 从哪来
 *
 * `IDAT` 是 zlib 流，交给 [Fs.inflateRaw]：JVM 侧 `java.util.zip.Inflater`，
 * K/N 侧是已有的 zlib 封装。两端同一份语义，不在这里重写 inflate。
 */
object PngDecode {

    /** 解码结果：RGBA8888，每像素 4 字节，非预乘 alpha。 */
    class Rgba(val width: Int, val height: Int, val pixels: ByteArray) {

        /** 取 `y*width+x` 处的像素，返回 `0xAARRGGBB`。 */
        fun pixelAt(index: Int): Int {
            val i = index * 4
            val r = pixels[i].toInt() and 0xFF
            val g = pixels[i + 1].toInt() and 0xFF
            val b = pixels[i + 2].toInt() and 0xFF
            val a = pixels[i + 3].toInt() and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private val SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** 解码 [bytes]；不支持的剖面返回 null。 */
    fun decode(bytes: ByteArray): Rgba? {
        if (bytes.size < 8) return null
        for (i in SIGNATURE.indices) if (bytes[i] != SIGNATURE[i]) return null

        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ByteSink()

        // 逐块遍历：长度(4) 类型(4) 数据(len) CRC(4)，长度不含 CRC 自身
        while (pos + 8 <= bytes.size) {
            val len = be32(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) return null
            val type = chunkType(bytes, pos + 4)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    if (len < 13) return null
                    width = be32(bytes, data)
                    height = be32(bytes, data + 4)
                    bitDepth = bytes[data + 8].toInt() and 0xFF
                    colorType = bytes[data + 9].toInt() and 0xFF
                    interlace = bytes[data + 12].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, data, data + len)
                "IEND" -> break
            }
            pos = data + len + 4
        }

        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0) return null
        val channels = when (colorType) {
            6 -> 4
            2 -> 3
            0 -> 1
            4 -> 2
            else -> return null
        }

        val raw = fs.inflateRaw(idat.toByteArray()) ?: return null
        val stride = width * channels
        // 每行 = 1 字节 filter 类型 + stride 字节像素
        if (raw.size < height * (stride + 1)) return null

        val out = ByteArray(width * height * 4)
        var prev = ByteArray(stride)
        var cur = ByteArray(stride)
        var rp = 0
        for (y in 0 until height) {
            val filter = raw[rp].toInt() and 0xFF
            rp++
            raw.copyInto(cur, 0, rp, rp + stride)
            rp += stride
            unfilter(filter, cur, prev, channels, stride) ?: return null

            val dst = y * width * 4
            when (colorType) {
                6 -> cur.copyInto(out, dst, 0, stride)
                2 -> {
                    var s = 0
                    var d = dst
                    repeat(width) {
                        out[d] = cur[s]
                        out[d + 1] = cur[s + 1]
                        out[d + 2] = cur[s + 2]
                        out[d + 3] = 0xFF.toByte()
                        s += 3
                        d += 4
                    }
                }
                0 -> {
                    var d = dst
                    repeat(width) {
                        val v = cur[it]
                        out[d] = v
                        out[d + 1] = v
                        out[d + 2] = v
                        out[d + 3] = 0xFF.toByte()
                        d += 4
                    }
                }
                4 -> {
                    var s = 0
                    var d = dst
                    repeat(width) {
                        val v = cur[s]
                        out[d] = v
                        out[d + 1] = v
                        out[d + 2] = v
                        out[d + 3] = cur[s + 1]
                        s += 2
                        d += 4
                    }
                }
            }
            val t = prev
            prev = cur
            cur = t
        }
        return Rgba(width, height, out)
    }

    /**
     * 就地还原一行。返回 null 表示遇到未知 filter 类型。
     *
     * 五种 filter 的定义见 PNG 规范 §9.2；`a`=左、`b`=上、`c`=左上。
     */
    private fun unfilter(filter: Int, cur: ByteArray, prev: ByteArray, channels: Int, stride: Int): Unit? =
        when (filter) {
            0 -> Unit // None
            1 -> {
                for (i in channels until stride) {
                    cur[i] = (cur[i] + cur[i - channels]).toByte()
                }
                Unit
            }
            2 -> {
                for (i in 0 until stride) {
                    cur[i] = (cur[i] + prev[i]).toByte()
                }
                Unit
            }
            3 -> {
                for (i in 0 until stride) {
                    val left = if (i >= channels) cur[i - channels].toInt() and 0xFF else 0
                    val up = prev[i].toInt() and 0xFF
                    cur[i] = (cur[i] + ((left + up) ushr 1)).toByte()
                }
                Unit
            }
            4 -> {
                for (i in 0 until stride) {
                    val a = if (i >= channels) cur[i - channels].toInt() and 0xFF else 0
                    val b = prev[i].toInt() and 0xFF
                    val c = if (i >= channels) prev[i - channels].toInt() and 0xFF else 0
                    cur[i] = (cur[i] + paeth(a, b, c)).toByte()
                }
                Unit
            }
            else -> null
        }

    /** PNG 的 Paeth 预测器。 */
    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = if (p > a) p - a else a - p
        val pb = if (p > b) p - b else b - p
        val pc = if (p > c) p - c else c - p
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    /**
     * 读 4 字节块类型（ASCII）。
     *
     * 手工拼字符而不用 `String(ByteArray, Charset)`：那个构造器在 K/N 上只有
     * 带 `Charset` 的重载且 `Charsets` 的可用性两端不同，容易编译不过。
     * 块类型恒为 ASCII 字母，`toInt().toChar()` 就是最稳的写法。
     */
    private fun chunkType(b: ByteArray, at: Int): String {
        val sb = StringBuilder(4)
        for (i in 0 until 4) sb.append((b[at + i].toInt() and 0xFF).toChar())
        return sb.toString()
    }

    private fun be32(b: ByteArray, at: Int): Int {        if (at + 4 > b.size) return -1
        return ((b[at].toInt() and 0xFF) shl 24) or
            ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or
            (b[at + 3].toInt() and 0xFF)
    }

    /** 拼接多个 `IDAT`；不用 `ByteArrayOutputStream` 以保持 commonMain 无 JDK 依赖。 */
    private class ByteSink {
        private var buf = ByteArray(8192)
        private var len = 0

        fun write(src: ByteArray, from: Int, to: Int) {
            val n = to - from
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            src.copyInto(buf, len, from, to)
            len += n
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}
