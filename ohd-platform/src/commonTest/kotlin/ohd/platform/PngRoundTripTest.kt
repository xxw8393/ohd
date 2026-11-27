package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PNG 编解码**闭环**验证：`encodeRgba` 出来的字节必须能被 `PngDecode` 读回原像素。
 *
 * 只测解码（`PngDecodeTest` 已做）不够——编码写错 CRC、长度或 filter 字节时，
 * 很多解码器仍会「勉强读出来」或静默只读到部分行，而自己的解码器若与编码器
 * 犯同一个错，闭环测试就测不出来。所以这里**同时**用外部锚点校验：
 * 产出的字节必须是标准 PNG（签名/IHDR 字段正确）。
 */
class PngRoundTripTest {

    private fun solidPixels(w: Int, h: Int, argb: Int): ByteArray {
        val p = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val o = i * 4
            p[o] = ((argb ushr 16) and 0xFF).toByte()
            p[o + 1] = ((argb ushr 8) and 0xFF).toByte()
            p[o + 2] = (argb and 0xFF).toByte()
            p[o + 3] = ((argb ushr 24) and 0xFF).toByte()
        }
        return p
    }

    @Test
    fun encodeProducesValidPngHeader() {
        val png = PngEncode.encodeRgba(4, 3, solidPixels(4, 3, 0xFF336699.toInt()))
        assertNotNull(png, "编码失败")
        // 签名
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) assertEquals(sig[i], png!![i], "签名第 $i 字节不对")
        // IHDR 紧跟签名（8 字节长度 + 4 字节类型）
        // 手工比对 ASCII，不用 String(ByteArray, Charset)（两端可用性不一致）
        val ihdr = charArrayOf('I', 'H', 'D', 'R')
        for (i in 0 until 4) {
            assertEquals(ihdr[i].code, png[12 + i].toInt() and 0xFF, "IHDR 第 $i 字节不对")
        }
        // 宽高（大端）
        val w = ((png[16].toInt() and 0xFF) shl 24) or ((png[17].toInt() and 0xFF) shl 16) or
            ((png[18].toInt() and 0xFF) shl 8) or (png[19].toInt() and 0xFF)
        val h = ((png[20].toInt() and 0xFF) shl 24) or ((png[21].toInt() and 0xFF) shl 16) or
            ((png[22].toInt() and 0xFF) shl 8) or (png[23].toInt() and 0xFF)
        assertEquals(4, w)
        assertEquals(3, h)
        // 位深 8、颜色类型 6（RGBA）、非隔行
        assertEquals(8, png[24].toInt() and 0xFF, "位深应为 8")
        assertEquals(6, png[25].toInt() and 0xFF, "颜色类型应为 RGBA(6)")
        assertEquals(0, png[28].toInt() and 0xFF, "应为非隔行")
    }

    @Test
    fun roundTripsPixelsExactly() {
        val w = 37
        val h = 21
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            pixels[o] = (x * 7).toByte()
            pixels[o + 1] = (y * 11).toByte()
            pixels[o + 2] = ((x + y) * 3).toByte()
            pixels[o + 3] = (255 - (x % 5) * 7).toByte()
        }
        val png = PngEncode.encodeRgba(w, h, pixels)
        assertNotNull(png)
        val back = PngDecode.decode(png!!)
        assertNotNull(back, "自己编出来的 PNG 自己读不回来")
        assertEquals(w, back!!.width)
        assertEquals(h, back.height)
        for (i in 0 until w * h) {
            val want = ((pixels[i * 4 + 3].toInt() and 0xFF) shl 24) or
                ((pixels[i * 4].toInt() and 0xFF) shl 16) or
                ((pixels[i * 4 + 1].toInt() and 0xFF) shl 8) or
                (pixels[i * 4 + 2].toInt() and 0xFF)
            assertEquals(want, back.pixelAt(i), "第 $i 个像素未原样往返")
        }
    }

    @Test
    fun largeImageRoundTrips() {
        // 大图会走多块 IDAT 与更大的 deflate 缓冲
        val w = 320
        val h = 200
        val pixels = ByteArray(w * h * 4) { (it * 31 and 0xFF).toByte() }
        val png = PngEncode.encodeRgba(w, h, pixels)
        assertNotNull(png)
        val back = PngDecode.decode(png!!)
        assertNotNull(back)
        assertEquals(w, back!!.width)
        assertEquals(h, back.height)
        var mismatched = 0
        for (i in 0 until w * h) {
            if (back.pixelAt(i) != (((pixels[i * 4 + 3].toInt() and 0xFF) shl 24) or
                    ((pixels[i * 4].toInt() and 0xFF) shl 16) or
                    ((pixels[i * 4 + 1].toInt() and 0xFF) shl 8) or
                    (pixels[i * 4 + 2].toInt() and 0xFF))
            ) mismatched++
        }
        assertEquals(0, mismatched, "$mismatched 个像素不一致")
    }

    @Test
    fun rejectsBadInput() {
        assertNull(PngEncode.encodeRgba(0, 10, ByteArray(0)), "宽为 0 应拒绝")
        assertNull(PngEncode.encodeRgba(10, 10, ByteArray(10)), "像素数组长度不符应拒绝")
    }

    @Test
    fun pngIsCompressedNotRaw() {
        // 纯色大图应显著小于原始尺寸；若没真压缩（比如 deflate 写错），体积会接近原图
        val w = 200
        val h = 200
        val png = PngEncode.encodeRgba(w, h, solidPixels(w, h, 0xFF808080.toInt()))
        assertNotNull(png)
        val rawSize = w * h * 4
        assertTrue(png!!.size < rawSize / 10, "纯色图应被压到原始 1/10 以下，实际 ${png.size} vs $rawSize")
    }
}
