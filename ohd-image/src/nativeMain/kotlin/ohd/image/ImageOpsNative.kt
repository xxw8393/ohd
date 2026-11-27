package ohd.image

import ohd.platform.OhdPath
import ohd.platform.PngDecode
import ohd.platform.fs

/**
 * K/N 侧的图像操作实现。
 *
 * - [ImageDiffMap]：走 [DiffMapCommon]（纯 Kotlin，与 JVM 侧共用同一份实现，
 *   避免两端热力图配色/阈值判断分叉）
 * - [ImageAnnotate] / [GifWriter]：**暂未实现**，见各自的失败说明
 */
actual object ImageDiffMap {
    actual fun render(a: OhdPath, b: OhdPath, out: OhdPath, threshold: Int): Boolean =
        DiffMapCommon.render(a, b, out, threshold)
}

/**
 * K/N 侧标注：**暂未实现**。
 *
 * 它要画抗锯齿线段、虚线网格与文字标签。纯 Kotlin 实现需要自带一套
 * 位图字体与线段光栅化，工作量与「让 ohd 能在 K/N 上出标注图」的收益不成比例。
 *
 * 返回 false 而不是假装成功：调用方（`ShotCap`）会按「标注失败」走原有降级路径，
 * 用户看到的是「没出标注图」，而不是一张空白或错位的图。
 */
actual object ImageAnnotate {
    actual fun annotate(
        src: OhdPath,
        dst: OhdPath,
        gridStep: Int,
        crossX: Int?,
        crossY: Int?,
        boxes: List<Box>,
        labelStep: Int,
    ): Boolean = false
}

/**
 * K/N 侧 GIF：**暂未实现**。
 *
 * GIF 编码需要 LZW 压缩 + 调色板量化 + 多帧控制块，是本模块最大的一块独立工作。
 * 返回 false 后调用方按「GIF 生成失败」处理——GIF 本就是附加产物，不拖垮打包。
 */
actual object GifWriter {
    actual fun write(
        frames: List<OhdPath>,
        out: OhdPath,
        delayMs: Int,
        maxWidth: Int,
        maxFrames: Int,
    ): Boolean = false
}

/**
 * K/N 侧转码：**暂不支持**。
 *
 * PNG → JPEG 需要 JPEG 编码器（DCT + 量化 + Huffman），是独立的一大块实现。
 * 返回 false 后调用方会明确报错「无法编码 JPEG」，而不是留下一张扩展名与内容
 * 不符的坏图——后者会在下游 `file` 或看图工具里才暴露，更难查。
 */
actual object ImageTranscode {
    actual fun to(src: OhdPath, dst: OhdPath, format: String): Boolean = false
}

/** K/N 侧默认引擎：只认 PNG（与 JVM 侧同一套 PngDecode 与灰度换算）。 */
actual fun defaultImageEngine(): ImageEngine = NativePngEngine()

/**
 * K/N 侧图像引擎：只解 PNG。
 *
 * 与 JVM 侧的 [ImageIOEngine] 共用 **同一套算法与灰度换算**
 * （[PngDecode] + [rgbaToGray]），差别只在「非 PNG 怎么办」：
 * JVM 能回退 ImageIO，K/N 只能明确失败——比静默给一张错图好。
 */
private class NativePngEngine : PixelAlgorithms() {
    override val name: String = "png"

    override fun load(path: OhdPath): GrayImage {
        val bytes = fs.readBytes(path)
        val png = PngDecode.decode(bytes)
            ?: throw IllegalStateException("无法解码图像（K/N 侧仅支持 8-bit 非隔行 PNG）：$path")
        val argb = IntArray(png.width * png.height) { png.pixelAt(it) }
        return rgbaToGray(png.width, png.height, argb)
    }
}

// ---------- 视频帧的跨平台抽象（K/N 实现：不支持） ----------

/**
 * K/N 侧帧句柄：**空类型**。
 *
 * 视频帧解码需要 JPEG/DCT 解码器（ffmpeg 转出的帧）或视频容器解析，
 * 这是独立的一大块实现。这里保留类型只为让 [VideoCap] 能编译，
 * 所有解码入口都返回 null —— 调用方会明确报「当前运行时无法解码视频帧」。
 */
actual class Frame internal constructor()

actual fun decodeFrame(bytes: ByteArray, offset: Int, length: Int): Frame? = null

actual fun decodeFrameFromFile(path: OhdPath): Frame? = null

actual fun Frame.pixelSize(): Pair<Int, Int> = 0 to 0

actual fun writeGif(frames: List<Frame>, out: OhdPath, delayMs: Int, maxWidth: Int, maxFrames: Int): Boolean = false
