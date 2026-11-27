package ohd.image

import ohd.platform.OhdPath

/**
 * 截图标注用的矩形（像素坐标，左上含、右下不含）。
 *
 * 纯数据、可跨平台——它被 `ShotCap` 用来传「节点框」，不涉及任何绘图。
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

/**
 * 动画 GIF 合成：把截图序列压成 `replay.gif`，让缺陷单里能直接看到操作过程。
 *
 * ## 为什么收路径而不是收解码后的位图
 *
 * 原签名是 `List<BufferedImage>` + `java.io.File`，两个都是 JDK 独占，
 * 这会把整条 GIF 链路钉死在 JVM 上。改成收 `List<OhdPath>` 后：
 * - 签名跨平台，`ohd-core` 不必再自己 `ImageIO.read` 一遍再传进来
 * - 解码统一在实现内部做，调用点少一层样板
 *
 * ## 约束
 *
 * 体积优先：1280×2832 的原帧几十张就会到几十 MB，所以统一缩放且限制帧数。
 * **失败返回 false 而不是抛异常**——GIF 是附加产物，不能让它拖垮打包。
 */
expect object GifWriter {
    fun write(
        frames: List<OhdPath>,
        out: OhdPath,
        delayMs: Int = 400,
        maxWidth: Int = 400,
        maxFrames: Int = 300,
    ): Boolean
}

/**
 * 截图标注：网格 + 十字 + 节点框 + 中心点，输出 PNG（源通常是 jpeg/png）。
 *
 * 给人眼看「坐标对不对」用。实现需要抗锯齿线条与文字，JVM 侧走 AWT。
 */
expect object ImageAnnotate {
    fun annotate(
        src: OhdPath,
        dst: OhdPath,
        gridStep: Int = 100,
        crossX: Int? = null,
        crossY: Int? = null,
        boxes: List<Box> = emptyList(),
        labelStep: Int = 200,
    ): Boolean
}

/**
 * 差异热力图：把两张图的逐像素差渲染成彩色 PNG，便于肉眼定位哪里变了。
 */
expect object ImageDiffMap {
    fun render(a: OhdPath, b: OhdPath, out: OhdPath, threshold: Int = 8): Boolean
}

/**
 * 把图片转成指定格式（`png` / `jpeg`）。
 *
 * 用途：`ohd shot --out x.jpg` 要真转码，否则文件名与内容不符。
 * 平台能力不同——JVM 侧走 ImageIO，K/N 侧目前不支持 JPEG 编码。
 * **失败返回 false**，调用方据此报错而不是留下一张坏图。
 */
expect object ImageTranscode {
    fun to(src: OhdPath, dst: OhdPath, format: String): Boolean
}

/**
 * 取默认图像引擎。
 *
 * 用途：`DiffCap` 需要「加载两张图并比较」，它不该知道自己跑在哪个平台。
 * JVM 侧返回 [ImageIOEngine]（PNG 走纯 Kotlin，其余格式回退 ImageIO）；
 * K/N 侧返回同一族实现（PNG 走纯 Kotlin，非 PNG 会明确失败）。
 */
expect fun defaultImageEngine(): ImageEngine

/**
 * 视频帧解码与 GIF 合成的跨平台入口。
 *
 * ## 为什么需要它
 *
 * `VideoCap` 的帧来自「设备批量截图 → 打包传回 → 逐帧解码」，中间全程是
 * `BufferedImage`。那是 JDK 独有的位图类型，K/N 上没有等价物，也没有
 * JPEG / 视频解码器。
 *
 * 所以这里把「帧」抽象成平台自己的句柄类型（[Frame]），**只暴露 VideoCap 需要的三件事**：
 * 解码一帧、取宽高、把一批帧合成 GIF。
 *
 * ## 平台能力
 *
 * - JVM：完整支持（ImageIO 解码 + ImageIO 写 GIF）
 * - K/N：**不支持**——返回 null / false。`VideoCap` 会据此明确报「当前运行时无法解码视频帧」，
 *   而不是产出空 GIF 或尺寸为 0 的坏产物。
 */
expect class Frame

/** 从 JPEG/PNG 字节解码一帧；失败返回 null。 */
expect fun decodeFrame(bytes: ByteArray, offset: Int, length: Int): Frame?

/** 从文件解码一帧；失败返回 null。 */
expect fun decodeFrameFromFile(path: OhdPath): Frame?

/** 帧的像素尺寸。 */
expect fun Frame.pixelSize(): Pair<Int, Int>

/** 把一批帧合成 GIF。失败返回 false。 */
expect fun writeGif(frames: List<Frame>, out: OhdPath, delayMs: Int, maxWidth: Int, maxFrames: Int): Boolean
