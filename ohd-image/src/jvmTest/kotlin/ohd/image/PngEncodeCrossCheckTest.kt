package ohd.image

import ohd.platform.PngDecode
import ohd.platform.PngEncode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * 用 **ImageIO 当外部锚点**校验 `PngEncode`。
 *
 * 为什么不能只靠 `PngEncode → PngDecode` 闭环：编解码都是自己写的，
 * 若两边犯了同一个错（比如都误解某个字段），闭环测试照样全绿。
 * 这里让 JDK 的 ImageIO 去读我们编的字节——它不认识我们的实现，只认 PNG 规范。
 *
 * 只能放 jvmTest：ImageIO 是 JDK 独占，恰恰是它作为第三方裁判的价值所在。
 */
class PngEncodeCrossCheckTest {

    @Test
    fun imageIoReadsWhatWeEncode() {
        val w = 53
        val h = 37
        val pixels = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            pixels[o] = (x * 5).toByte()
            pixels[o + 1] = (y * 7).toByte()
            pixels[o + 2] = ((x * y) and 0xFF).toByte()
            pixels[o + 3] = if ((x + y) % 3 == 0) 128.toByte() else 255.toByte()
        }
        val png = PngEncode.encodeRgba(w, h, pixels)
        assertNotNull(png)

        // 外部裁判：ImageIO 必须能读，且尺寸/像素与我们编的一致
        val img = ImageIO.read(ByteArrayInputStream(png!!))
        assertNotNull(img, "ImageIO 读不了我们编的 PNG —— 说明产出不符合规范")
        assertEquals(w, img.width)
        assertEquals(h, img.height)

        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)
        for (i in 0 until w * h) {
            val want = ((pixels[i * 4 + 3].toInt() and 0xFF) shl 24) or
                ((pixels[i * 4].toInt() and 0xFF) shl 16) or
                ((pixels[i * 4 + 1].toInt() and 0xFF) shl 8) or
                (pixels[i * 4 + 2].toInt() and 0xFF)
            assertEquals(want, argb[i], "ImageIO 解出的第 $i 个像素与我们编的不一致")
        }
    }

    @Test
    fun ourDecodeAgreesWithImageIoOnImageIoOutput() {
        // 反向：ImageIO 编 → 我们的解码器读，与 ImageIO 自己读的结果比
        val w = 31
        val h = 19
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            img.setRGB(x, y, (0xFF shl 24) or ((x * 8) shl 16) or ((y * 12) shl 8) or ((x + y) and 0xFF))
        }
        val out = java.io.ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        val bytes = out.toByteArray()

        val mine = PngDecode.decode(bytes)
        assertNotNull(mine, "我们的解码器读不了 ImageIO 编的 PNG")
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)
        for (i in 0 until w * h) {
            assertEquals(argb[i], mine!!.pixelAt(i), "第 $i 个像素与 ImageIO 不一致")
        }
    }
}
