package ohd.image

import ohd.platform.OhdPath
import ohd.platform.PngDecode
import ohd.platform.fs
import ohd.platform.toJavaPath
import java.io.IOException
import javax.imageio.ImageIO

/**
 * 默认引擎：**PNG 走纯 Kotlin 解码，其余格式回退 ImageIO**。
 *
 * ## 为什么优先自己解 PNG
 *
 * `ohd shot` 现在从 OHOS/Android/iOS 拿到的都是 PNG。设备产物是 8-bit RGBA 非隔行，
 * 正好落在 [PngDecode] 的覆盖范围内。自己解掉这一步之后：
 *
 * - `diff` / `highFrequencyEnergy` 全程不碰 JDK（算法在 [PixelAlgorithms]，本就零依赖）
 * - 同一份解码逻辑两端一致，不会再出现「JVM 能量出、K/N 解不了」
 *
 * ImageIO 只在两种情况下兜底：非 PNG（如用户拿旧 JPEG 当基线）、或 PNG 剖面不支持
 * （16-bit、隔行、调色板）。**兜底而不是报错**——历史证据包里大量老 JPEG 基线仍要比。
 *
 * ## 为什么不直接叫 PngEngine
 *
 * 它仍然是「ImageIO 兜底」的引擎，`--engine imageio` 这个对外名字要保住，
 * 免得脚本与 `ohd selftest` 的 engine 名断言一起改。
 */
class ImageIOEngine : PixelAlgorithms() {

    override val name: String = "imageio"

    override fun load(path: OhdPath): GrayImage {
        // 先试纯 Kotlin 的 PNG 解码：成功就不必碰 JDK
        decodePng(path)?.let { return it }
        return decodeWithImageIO(path)
    }

    /** 走 [PngDecode]；不是 PNG 或剖面不支持时返回 null，由调用方回退。 */
    private fun decodePng(path: OhdPath): GrayImage? {
        val bytes = runCatching { fs.readBytes(path) }.getOrNull() ?: return null
        val rgba = PngDecode.decode(bytes) ?: return null
        val argb = IntArray(rgba.width * rgba.height) { rgba.pixelAt(it) }
        return rgbaToGray(rgba.width, rgba.height, argb)
    }

    /** JVM 兜底：JPEG、16-bit PNG、隔行 PNG、调色板 PNG 等。 */
    private fun decodeWithImageIO(path: OhdPath): GrayImage {
        // JVM-only: ImageIO 无跨平台替代（非 PNG 格式的解码回退路径）
        val img = ImageIO.read(path.toJavaPath().toFile())
            ?: throw IOException("无法解码图像（非 PNG/JPEG 或文件损坏）：$path")
        val w = img.width
        val h = img.height
        val argb = IntArray(w * h)
        img.getRGB(0, 0, w, h, argb, 0, w)
        return rgbaToGray(w, h, argb)
    }
}

/** JVM 侧默认引擎：PNG 走纯 Kotlin 解码，其余格式回退 ImageIO。 */
actual fun defaultImageEngine(): ImageEngine = ImageIOEngine()
