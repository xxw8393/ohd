package ohd.image

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.nio.file.Path
import javax.imageio.ImageIO

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

/**
 * 截图标注：给人眼里"坐标对不对"用。
 * 网格 + 十字 + 节点框 + 中心点，输出 PNG（源通常是 jpeg）。
 */
object ImageAnnotate {
    fun annotate(
        src: Path,
        dst: Path,
        gridStep: Int = 100,
        crossX: Int? = null,
        crossY: Int? = null,
        boxes: List<Box> = emptyList(),
        labelStep: Int = 200,
    ): Boolean {
        val img = ImageIO.read(src.toFile()) ?: return false
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
        javax.imageio.ImageIO.write(img, "png", dst.toFile())
        return true
    }

    fun sizeOf(path: Path): Pair<Int, Int>? = runCatching {
        ImageIO.createImageInputStream(path.toFile())?.use { iis ->
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
object ImageDiffMap {
    fun render(a: java.nio.file.Path, b: java.nio.file.Path, out: java.nio.file.Path, threshold: Int = 8): Boolean {
        val ia = ImageIO.read(a.toFile()) ?: return false
        val ib = ImageIO.read(b.toFile()) ?: return false
        val w = minOf(ia.width, ib.width)
        val h = minOf(ia.height, ib.height)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        ia.getRGB(0, 0, w, h, pa, 0, w)
        ib.getRGB(0, 0, w, h, pb, 0, w)
        ia.flush()
        ib.flush()
        val diff = IntArray(w * h)
        val unchanged = (255 shl 24) or (240 shl 16) or (240 shl 8) or 240
        for (i in 0 until w * h) {
            val ca = pa[i]
            val cb = pb[i]
            val d = (kotlin.math.abs(((ca shr 16) and 0xFF) - ((cb shr 16) and 0xFF)) +
                kotlin.math.abs(((ca shr 8) and 0xFF) - ((cb shr 8) and 0xFF)) +
                kotlin.math.abs((ca and 0xFF) - (cb and 0xFF))) / 3
            diff[i] = if (d > threshold) {
                val alpha = (60 + (d * 195 / 255)).coerceIn(60, 255)
                (alpha shl 24) or (255 shl 16)
            } else {
                unchanged
            }
        }
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, w, h, diff, 0, w)
        ImageIO.write(img, "png", out.toFile())
        img.flush()
        return true
    }
}
