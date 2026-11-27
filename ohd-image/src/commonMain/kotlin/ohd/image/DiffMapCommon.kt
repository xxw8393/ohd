package ohd.image

import ohd.platform.OhdPath
import ohd.platform.PngDecode
import ohd.platform.PngEncode
import ohd.platform.fs
import kotlin.math.abs

/**
 * 差异热力图：**纯 Kotlin 实现，两端共用**。
 *
 * ## 为什么可以完全跨平台
 *
 * 它只做三件事：读两张图 → 逐像素算差 → 写一张图。
 * - 读：[PngDecode]（已实现，与 ImageIO 逐像素对账过）
 * - 算：纯整数运算，无任何平台 API
 * - 写：[PngEncode]（新增，与解码同一套 zlib 容器语义）
 *
 * 原来这三步全压在 `javax.imageio` 上，实现只有 JVM 能用。
 *
 * ## 非 PNG 输入
 *
 * `PngDecode` 只认 8-bit 非隔行 PNG。老证据包里若有 JPEG 基线，
 * 这里会返回 false —— 由调用方（`DiffCap`）决定是否回退。
 * **不静默当成功**：热力图没生成却报成功，比报错难查。
 */
internal object DiffMapCommon {

    fun render(a: OhdPath, b: OhdPath, out: OhdPath, threshold: Int): Boolean = runCatching {
        val ia = PngDecode.decode(fs.readBytes(a)) ?: return false
        val ib = PngDecode.decode(fs.readBytes(b)) ?: return false
        val w = minOf(ia.width, ib.width)
        val h = minOf(ia.height, ib.height)
        if (w <= 0 || h <= 0) return false

        val pixels = ByteArray(w * h * 4)
        // 未变化处用浅灰，让它退到背景
        val unchangedR = 240.toByte()
        val unchangedG = 240.toByte()
        val unchangedB = 240.toByte()

        var o = 0
        for (y in 0 until h) {
            val rowA = y * ia.width
            val rowB = y * ib.width
            for (x in 0 until w) {
                val pa = ia.pixelAt(rowA + x)
                val pb = ib.pixelAt(rowB + x)
                val ar = (pa ushr 16) and 0xFF
                val ag = (pa ushr 8) and 0xFF
                val ab = pa and 0xFF
                val br = (pb ushr 16) and 0xFF
                val bg = (pb ushr 8) and 0xFF
                val bb = pb and 0xFF
                val d = (abs(ar - br) + abs(ag - bg) + abs(ab - bb)) / 3
                if (d > threshold) {
                    // 差异越大越红：红通道饱和，绿/蓝随差异衰减
                    val alpha = (60 + (d * 195 / 255)).coerceIn(60, 255)
                    pixels[o] = 255.toByte()          // R
                    pixels[o + 1] = (255 - alpha).toByte() // G
                    pixels[o + 2] = (255 - alpha).toByte() // B
                    pixels[o + 3] = 255.toByte()      // A
                } else {
                    pixels[o] = unchangedR
                    pixels[o + 1] = unchangedG
                    pixels[o + 2] = unchangedB
                    pixels[o + 3] = 255.toByte()
                }
                o += 4
            }
        }

        val png = PngEncode.encodeRgba(w, h, pixels) ?: return false
        out.parent?.let { fs.createDirectories(it) }
        fs.writeBytes(out, png)
        true
    }.getOrDefault(false)
}
