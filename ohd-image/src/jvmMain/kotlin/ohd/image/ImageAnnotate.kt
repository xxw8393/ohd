package ohd.image

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import ohd.platform.OhdPath
import ohd.platform.toJavaPath
import javax.imageio.ImageIO

/**
 * 截图标注：给人眼里"坐标对不对"用。
 * 网格 + 十字 + 节点框 + 中心点，输出 PNG（源通常是 jpeg）。
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
    ): Boolean {
        val img = ImageIO.read(src.toJavaPath().toFile()) ?: return false
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val w = img.width
            val h = img.height

            // 网格：每 labelStep 画主网格 + 坐标文字，中间用浅网格
            g.font = Font(Font.MONOSPACED, Font.PLAIN, 22)
            if (gridStep > 0) {
                var x = 0
                while (x <= w) {
                    val major = x % labelStep == 0
                    g.color = if (major) Color(255, 0, 0, 90) else Color(0, 120, 255, 45)
                    g.drawLine(x, 0, x, h)
                    if (major) {
                        g.color = Color(255, 0, 0, 200)
                        g.drawString(x.toString(), x + 4, 26)
                    }
                    x += gridStep
                }
                var y = 0
                while (y <= h) {
                    val major = y % labelStep == 0
                    g.color = if (major) Color(255, 0, 0, 90) else Color(0, 120, 255, 45)
                    g.drawLine(0, y, w, y)
                    if (major) {
                        g.color = Color(255, 0, 0, 200)
                        g.drawString(y.toString(), 4, y - 6)
                    }
                    y += gridStep
                }
            }

            // 节点框
            g.stroke = BasicStroke(3f)
            boxes.forEach { b ->
                g.color = Color(0, 200, 0, 200)
                g.drawRect(b.left, b.top, b.right - b.left, b.bottom - b.top)
                g.color = Color(0, 255, 0, 255)
                g.fillOval(b.centerX - 6, b.centerY - 6, 12, 12)
            }

            // 十字准心（确认点击坐标）
            if (crossX != null && crossY != null) {
                g.color = Color(255, 0, 255, 235)
                g.stroke = BasicStroke(4f)
                g.drawLine(0, crossY, w, crossY)
                g.drawLine(crossX, 0, crossX, h)
                g.drawOval(crossX - 22, crossY - 22, 44, 44)
                g.font = Font(Font.MONOSPACED, Font.BOLD, 28)
                g.drawString("($crossX,$crossY)", crossX + 26, crossY - 14)
            }
        } finally {
            g.dispose()
        }
        javax.imageio.ImageIO.write(img, "png", dst.toJavaPath().toFile())
        return true
    }

    fun sizeOf(path: OhdPath): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(path.toJavaPath().toFile())?.use { iis ->
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.input = iis
                reader.getWidth(0) to reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()
}

/** 差异热力图：红色越亮差异越大，用于人眼快速定位"哪里变了"。 */
actual object ImageDiffMap {
    /**
     * 走 [DiffMapCommon]：与 K/N 侧**同一份实现**。
     *
     * 原来这里是 AWT 版，两端各写一份的话，配色/阈值判断迟早分叉——
     * 同一对图在两条路上给出不同热力图，是最难解释的 bug。
     */
    actual fun render(a: OhdPath, b: OhdPath, out: OhdPath, threshold: Int): Boolean =
        DiffMapCommon.render(a, b, out, threshold)
}

actual object ImageTranscode {
    /**
     * JPEG 不支持 alpha，而设备给的是 RGBA PNG —— `ImageIO.write` 遇到这种情况
     * **返回 false 而不抛异常**（实测），直接调用会「报告成功但没落盘」。
     * 所以先拍平到 RGB（白底），再检查返回值。
     */
    actual fun to(src: OhdPath, dst: OhdPath, format: String): Boolean = runCatching {
        val img = ImageIO.read(src.toJavaPath().toFile()) ?: return false
        if (!format.equals("jpeg", ignoreCase = true) && !format.equals("jpg", ignoreCase = true)) {
            return ImageIO.write(img, format, dst.toJavaPath().toFile())
        }
        val rgb = java.awt.image.BufferedImage(
            img.width, img.height, java.awt.image.BufferedImage.TYPE_INT_RGB,
        )
        val g = rgb.createGraphics()
        try {
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, img.width, img.height)
            g.drawImage(img, 0, 0, null)
        } finally {
            g.dispose()
        }
        ImageIO.write(rgb, "jpeg", dst.toJavaPath().toFile())
    }.getOrDefault(false)
}
