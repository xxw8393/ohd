package ohd.platform

/**
 * 最小 PNG **编码**器：RGBA8888 → 8-bit RGBA 非隔行 PNG。
 *
 * ## 为什么需要它
 *
 * `PngDecode` 解决了「读 PNG」，但差异热力图、标注图都要**写** PNG。
 * 原来写走 `javax.imageio.ImageIO.write`，那是 JDK 独占——
 * 只补解码不补编码，图像链路仍然搬不出 JVM。
 *
 * ## 支持范围（够用即止）
 *
 * - 只出颜色类型 6（RGBA）、位深 8、非隔行 —— 与本仓库 `PngDecode` 能读的剖面一致
 * - 只用 filter 0（None）：热力图与标注图都是大面积同色 + 少量色块，
 *   筛选器收益很小，换来的是实现简单、不会有 filter 写错导致的静默花屏
 * - zlib 走 [Fs.deflateRaw]，与解码侧同一套容器语义
 */
object PngEncode {

    /**
     * 把 [pixels]（每像素 4 字节 RGBA，非预乘）编码成 PNG 字节。
     *
     * [pixels] 长度必须是 width*height*4。
     */
    fun encodeRgba(width: Int, height: Int, pixels: ByteArray): ByteArray? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size != width * height * 4) return null

        // 每行 1 字节 filter(0) + width*4 字节像素
        val stride = width * 4
        val raw = ByteArray(height * (stride + 1))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter: None
            pixels.copyInto(raw, p, y * stride, (y + 1) * stride)
            p += stride
        }

        val deflated = fs.deflateRaw(raw) ?: return null

        val out = ByteSink(raw.size / 2 + deflated.size + 128)
        // PNG 签名
        listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).forEach { out.u8(it) }
        // IHDR
        val ihdr = ByteArray(13)
        putBe32(ihdr, 0, width)
        putBe32(ihdr, 4, height)
        ihdr[8] = 8  // bit depth
        ihdr[9] = 6  // color type RGBA
        ihdr[10] = 0 // compression
        ihdr[11] = 0 // filter
        ihdr[12] = 0 // interlace
        out.chunk("IHDR", ihdr)
        out.chunk("IDAT", deflated)
        out.chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putBe32(dst: ByteArray, at: Int, v: Int) {
        dst[at] = (v ushr 24).toByte()
        dst[at + 1] = (v ushr 16).toByte()
        dst[at + 2] = (v ushr 8).toByte()
        dst[at + 3] = v.toByte()
    }

    /** CRC32（PNG 每块尾部要）。 */
    private fun crc32(data: ByteArray, from: Int, to: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    /** 可增长字节缓冲；不用 `ByteArrayOutputStream`（JDK 独占）。 */
    private class ByteSink(capacity: Int) {
        private var buf = ByteArray(maxOf(256, capacity))
        private var len = 0

        fun u8(v: Int) {
            ensure(1)
            buf[len++] = v.toByte()
        }

        fun bytes(src: ByteArray, from: Int, to: Int) {
            val n = to - from
            ensure(n)
            src.copyInto(buf, len, from, to)
            len += n
        }

        /** 写一个 PNG 块：长度 + 类型 + 数据 + CRC。 */
        fun chunk(type: String, data: ByteArray) {
            val typeBytes = ByteArray(4) { type[it].code.toByte() }
            ensure(12 + data.size)
            val lenAt = len
            len += 4
            // 长度字段：块数据字节数（不含类型、不含 CRC）。
            // 漏写这 4 字节 = 长度恒为 0，解码器会认为 IHDR 只有 0 字节而整块拒绝。
            putBe32InPlace(lenAt, data.size)
            bytes(typeBytes, 0, 4)
            bytes(data, 0, data.size)
            val crcAt = len
            len += 4
            val crcInput = buf.copyOfRange(lenAt + 4, crcAt) // 类型 + 数据
            val crc = crc32(crcInput, 0, crcInput.size)
            putBe32InPlace(crcAt, crc)
        }

        private fun putBe32InPlace(at: Int, v: Int) {
            buf[at] = (v ushr 24).toByte()
            buf[at + 1] = (v ushr 16).toByte()
            buf[at + 2] = (v ushr 8).toByte()
            buf[at + 3] = v.toByte()
        }

        private fun ensure(n: Int) {
            if (len + n > buf.size) {
                var cap = buf.size
                while (cap < len + n) cap *= 2
                buf = buf.copyOf(cap)
            }
        }

        fun toByteArray(): ByteArray = buf.copyOf(len)
    }
}
