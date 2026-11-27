package ohd.image

import ohd.platform.OhdPath

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
 *
 * `load` 收 `OhdPath` 而非 `java.nio.file.Path`：解码是这条链路上**唯一**需要 JDK 的一步，
 * 签名一旦钉死 `Path`，整条 diff 链路就搬不到 K/N。现在 [PngEngine] 用纯 Kotlin 解 PNG，
 * ImageIO 只作为非 PNG 格式的兜底。
 */
interface ImageEngine {
    val name: String

    fun load(path: OhdPath): GrayImage

    fun diff(a: GrayImage, b: GrayImage, threshold: Int = 8, tile: Int = 64): DiffResult

    /** 高频能量（拉普拉斯平方均值）：判断「整块文字没画出来」比逐像素 diff 稳。 */
    fun highFrequencyEnergy(img: GrayImage): Double
}
