package ohd.image

import ohd.platform.PngDecode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * 纯 Kotlin PNG 解码的正确性证明。
 *
 * 这套测试守的是一条**替代关系**：`PngDecode` 必须与 `javax.imageio` 逐像素一致。
 * 一旦不一致，`diff` 的结论会随「走哪条解码路径」而变——那是比崩溃更难查的 bug。
 *
 * 用 ImageIO 在测试里生成 PNG 作为对照组是正当的：它是**测试脚手架**，
 * 不是生产路径。生产代码只在 [PngDecode] 不支持时才回退 ImageIO。
 */
class PngDecodeTest {

    /** 用 ImageIO 生成一张 PNG 的字节流。 */
    private fun pngOf(img: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream()
        check(ImageIO.write(img, "png", out)) { "测试脚手架自身失败：ImageIO 无法编码 PNG" }
        return out.toByteArray()
    }

    /** ImageIO 解码出的 ARGB8888 像素。 */
    private fun argbOf(bytes: ByteArray): IntArray {
        val img = ImageIO.read(bytes.inputStream())
        val argb = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, argb, 0, img.width)
        return argb
    }

    /** 断言两条解码路径逐像素一致。 */
    private fun assertSameAsImageIO(bytes: ByteArray) {
        val mine = PngDecode.decode(bytes)
        assertNotNull(mine, "PngDecode 拒绝了 ImageIO 能产出的 PNG")
        val want = argbOf(bytes)
        assertEquals(want.size, mine!!.width * mine.height, "像素总数不一致")
        for (i in want.indices) {
            assertEquals(
                want[i].toLong() and 0xFFFFFFFFL,
                mine.pixelAt(i).toLong() and 0xFFFFFFFFL,
                "第 $i 个像素与 ImageIO 不一致（坐标为 ${i % mine.width},${i / mine.width}）",
            )
        }
    }

    @Test
    fun rgbaMatchesImageIO() {
        // 设备真实剖面的形状：RGBA、8-bit、非隔行
        val img = BufferedImage(37, 23, BufferedImage.TYPE_INT_ARGB)
        val rnd = Random(42)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            // 覆盖四个通道的各种取值，含全透明与全不透明
            img.setRGB(x, y, rnd.nextInt())
        }
        assertSameAsImageIO(pngOf(img))
    }

    @Test
    fun rgbMatchesImageIO() {
        val img = BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            img.setRGB(x, y, (x * 8 shl 16) or (y * 24 shl 8) or ((x + y) * 4))
        }
        assertSameAsImageIO(pngOf(img))
    }

    /**
     * 灰度 PNG：**不对 ImageIO 做逐像素对照**，只验尺寸与单调性。
     *
     * 原因：`TYPE_BYTE_GRAY` 经 ImageIO 写→读会走一遍 sRGB↔线性 转换 + 量化，
     * 原值 20/40 读回可能都变成 0（实测），本身就是有损往返。拿它当基准会把
     * 「ImageIO 的往返误差」错记成「我方解码器的 bug」。
     *
     * 设备侧 `snapshot_display -t png` 产出的恒为 RGBA，灰度只是防御性支持。
     */
    @Test
    fun grayPngDecodesConsistently() {
        val img = BufferedImage(11, 7, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            img.setRGB(x, y, (x * 20 + y * 30) and 0xFF)
        }
        val bytes = pngOf(img)
        val mine = PngDecode.decode(bytes)
        assertNotNull(mine, "PngDecode 拒绝了灰度 PNG")
        assertEquals(11, mine!!.width)
        assertEquals(7, mine.height)
        // 灰度展开成 RGBA 后应为中性灰（R=G=B）且 alpha 全不透明
        for (i in 0 until 11 * 7) {
            val p = mine.pixelAt(i)
            assertEquals(0xFF, (p ushr 24) and 0xFF, "第 $i 个像素 alpha 应为不透明")
            assertEquals((p ushr 16) and 0xFF, (p ushr 8) and 0xFF, "第 $i 个像素 R!=G")
            assertEquals((p ushr 8) and 0xFF, p and 0xFF, "第 $i 个像素 G!=B")
        }
        // 同一份字节解两次必须完全一致（确定性）
        val again = PngDecode.decode(bytes)!!
        for (i in 0 until 11 * 7) assertEquals(mine.pixelAt(i), again.pixelAt(i))
    }

    /**
     * 大图 + 梯度渐变：这张会覆盖全部 5 种 PNG 行 filter。
     * 小图容易全都用 filter 0（None），测不出 unfilter 的实现对不对。
     */
    @Test
    fun gradientCoversAllFilters() {
        val w = 256
        val h = 64
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            img.setRGB(x, y, ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or ((x + y) and 0xFF))
        }
        assertSameAsImageIO(pngOf(img))
    }

    @Test
    fun rejectsNonPng() {
        assertNull(PngDecode.decode(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertNull(PngDecode.decode(ByteArray(0)))
    }

    @Test
    fun rejectsTruncatedPng() {
        val img = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val full = pngOf(img)
        // 砍掉尾部（IEND 与部分 IDAT）：必须干净返回 null，不能抛异常也不能解出半张图
        assertNull(PngDecode.decode(full.copyOf(full.size / 2)))
    }
}
