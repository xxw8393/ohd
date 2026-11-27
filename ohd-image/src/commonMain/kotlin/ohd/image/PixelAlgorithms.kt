package ohd.image

import kotlin.math.abs

/**
 * 纯 Kotlin 的像素算法基类：**零 JDK 依赖**。
 *
 * [diff] 与 [highFrequencyEnergy] 只吃 [GrayImage]，与「图从哪来」完全无关。
 * 把算法放这里，子类只负责 [load] 的解码，于是「解码」成了唯一需要平台能力的一步——
 * 这是整条 diff 链路能搬出 JVM 的结构前提。
 */
abstract class PixelAlgorithms : ImageEngine {

    override fun diff(a: GrayImage, b: GrayImage, threshold: Int, tile: Int): DiffResult {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        if (w <= 0 || h <= 0) throw IllegalArgumentException("图像尺寸为空，无法比较")
        val tileSize = if (tile <= 0) 64 else tile
        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize
        val tileSum = LongArray(tilesX * tilesY)
        val tileCount = IntArray(tilesX * tilesY)

        var sum = 0L
        var max = 0
        var changed = 0L
        for (y in 0 until h) {
            val rowA = y * a.width
            val rowB = y * b.width
            val ty = y / tileSize
            for (x in 0 until w) {
                val d = abs(a.pixels[rowA + x] - b.pixels[rowB + x])
                sum += d
                if (d > max) max = d
                if (d > threshold) changed++
                val idx = ty * tilesX + x / tileSize
                tileSum[idx] += d
                tileCount[idx]++
            }
        }
        val total = w.toLong() * h
        val mean = sum.toDouble() / total
        val limit = maxOf(threshold.toDouble() * 2, mean * 3)
        var anomalies = 0
        for (i in tileSum.indices) {
            val m = tileSum[i].toDouble() / maxOf(1, tileCount[i])
            if (m > limit) anomalies++
        }
        return DiffResult(
            meanAbs = mean,
            maxAbs = max,
            changedRatio = changed.toDouble() / total,
            threshold = threshold,
            tileSize = tileSize,
            anomalousTiles = anomalies,
            tilesX = tilesX,
            tilesY = tilesY,
        )
    }

    override fun highFrequencyEnergy(img: GrayImage): Double {
        if (img.width < 3 || img.height < 3) return 0.0
        var sum = 0.0
        var count = 0L
        for (y in 1 until img.height - 1) {
            for (x in 1 until img.width - 1) {
                val c = img.at(x, y)
                val lap = img.at(x - 1, y) + img.at(x + 1, y) + img.at(x, y - 1) + img.at(x, y + 1) - 4 * c
                sum += lap.toDouble() * lap
                count++
            }
        }
        return if (count == 0L) 0.0 else sum / count
    }
}

/**
 * RGBA8888 → 灰度。权重与 Skia 口径接近（人眼对绿色最敏感）。
 *
 * 单独抽出来给两个引擎共用：口径一旦分叉，同一对图在两个引擎下会得出不同 diff 结论。
 */
internal fun rgbaToGray(width: Int, height: Int, argb: IntArray): GrayImage {
    val gray = IntArray(width * height)
    for (i in argb.indices) {
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        gray[i] = (r * 299 + g * 587 + b * 114) / 1000
    }
    return GrayImage(width, height, gray)
}
