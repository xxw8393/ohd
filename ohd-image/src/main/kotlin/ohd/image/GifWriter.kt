package ohd.image

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadataNode

/**
 * 动画 GIF 合成（只用 JDK 自带 ImageIO，不依赖 ffmpeg / PIL）。
 *
 * 用途：把 replay 过程中的截图序列压成 `replay.gif`，让缺陷单里能直接看到操作过程。
 * 关键约束是体积——1280x2832 的原帧几十张就会到几十 MB，所以统一缩放且限制帧数。
 */
object GifWriter {
    /** 失败返回 false 而不是抛异常：GIF 是附加产物，不能让它拖垮打包 */
    fun write(
        frames: List<BufferedImage>,
        out: File,
        delayMs: Int = 400,
        maxWidth: Int = 400,
        maxFrames: Int = 300,
    ): Boolean {
        if (frames.isEmpty()) return false
        return runCatching {
            // 抽帧：均匀取样，保证首尾一定保留
            val picked = if (frames.size <= maxFrames) frames else {
                (0 until maxFrames).map { i -> frames[(i.toLong() * (frames.size - 1) / (maxFrames - 1)).toInt()] }
            }
            val writer: ImageWriter = ImageIO.getImageWritersByFormatName("gif").let {
                if (it.hasNext()) it.next() else return false
            }
            out.parentFile?.mkdirs()
            ImageIO.createImageOutputStream(out).use { ios ->
                writer.output = ios
                writer.prepareWriteSequence(null)
                picked.forEach { src ->
                    val img = scaleDown(src, maxWidth)
                    writer.writeToSequence(IIOImage(img, null, meta(writer, img, delayMs)), writer.defaultWriteParam)
                    img.flush()
                }
                writer.endWriteSequence()
            }
            writer.dispose()
            true
        }.getOrDefault(false)
    }

    private fun scaleDown(src: BufferedImage, maxWidth: Int): BufferedImage {
        if (src.width <= maxWidth) {
            // GIF 要求所有帧同尺寸，统一转 RGB 顺带去掉 alpha
            return BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB).also {
                it.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
            }
        }
        val h = (src.height.toDouble() * maxWidth / src.width).toInt().coerceAtLeast(1)
        val out = BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            drawImage(src, 0, 0, maxWidth, h, null)
            dispose()
        }
        return out
    }

    /** GIF 帧元数据：延时（百分之一秒）+ 无限循环 */
    private fun meta(writer: ImageWriter, img: BufferedImage, delayMs: Int) =
        writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), writer.defaultWriteParam).apply {
            val format = "javax_imageio_gif_image_1.0"
            val root = getAsTree(format) as IIOMetadataNode

            val gce = IIOMetadataNode("GraphicControlExtension").apply {
                setAttribute("disposalMethod", "none")
                setAttribute("userInputFlag", "FALSE")
                setAttribute("transparentColorFlag", "FALSE")
                setAttribute("delayTime", (delayMs / 10).coerceAtLeast(1).toString())
                setAttribute("transparentColorIndex", "0")
            }
            root.appendChild(gce)

            // NETSCAPE2.0 应用扩展 = 循环播放；缺了这段只播一次
            val appExt = IIOMetadataNode("ApplicationExtension").apply {
                setAttribute("applicationID", "NETSCAPE")
                setAttribute("authenticationCode", "2.0")
                userObject = byteArrayOf(0x1, 0x0, 0x0) // 子块：loop=0 表示无限
            }
            IIOMetadataNode("ApplicationExtensions").apply {
                appendChild(appExt)
                root.appendChild(this)
            }

            setFromTree(format, root)
        }
}
