package ohd.image

/** 灰度图（0..255）。所有像素算法都在这个结构上做，引擎只负责解码/合成。 */
data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "图像尺寸非法：${width}x$height" }
        require(pixels.size == width * height) { "像素数不匹配：${pixels.size} != ${width * height}" }
    }

    fun at(x: Int, y: Int): Int = pixels[y * width + x]
}

data class DiffResult(
    val meanAbs: Double,
    val maxAbs: Int,
    val changedRatio: Double,
    val threshold: Int,
    val tileSize: Int,
    val anomalousTiles: Int,
    val tilesX: Int,
    val tilesY: Int,
)

/**
 * 图像引擎接口。M0 只有 ImageIO 实现（JDK 自带、零 native、三平台同源）；
 * M2 增加 Skiko 实现（--engine skia，与 CMP 渲染同源），接口不变。
 */
interface ImageEngine {
    val name: String

    fun load(path: java.nio.file.Path): GrayImage

    fun diff(a: GrayImage, b: GrayImage, threshold: Int = 8, tile: Int = 64): DiffResult

    /** 高频能量（拉普拉斯平方均值）：判断「整块文字没画出来」比逐像素 diff 稳。 */
    fun highFrequencyEnergy(img: GrayImage): Double
}
