package ohd.platform

/**
 * 从图片文件头读像素尺寸，不解码像素。
 *
 * ## 为什么不用 ImageIO
 *
 * `ohd-core` 里有两处只需要「截图多大」：iOS 模拟器（`simctl io screenshot`）与
 * Android（`screencap -p`）。原实现用 `javax.imageio.ImageIO.read()` 全量解码——
 * 为了拿两个 int 把整张 1080p 图解码进内存，既慢又把 `ohd-core` 绑死在 AWT 上
 * （AWT 是 `ohd-core` 进 K/N 的唯一硬阻塞）。
 *
 * 这里只解析文件头：PNG 的 IHDR、JPEG 的 SOFn、GIF 的逻辑屏幕描述符。
 * 几十字节拿到尺寸，两端共用同一份实现且可单测。
 *
 * 拿不到尺寸（格式不支持/文件损坏）返回 null，调用方按「宽高未知」处理，
 * **不抛异常**——截图本身已成功，尺寸只是附加信息。
 */
fun imageSizeOf(path: OhdPath): Pair<Int, Int>? {
    val head = runCatching { fs.readBytesRange(path, 0, 64 * 1024) }.getOrNull() ?: return null
    return imageSizeOfBytes(head)
}

/** 纯字节解析（分成两个函数是为了让单测可以只喂字节，不碰文件系统）。 */
fun imageSizeOfBytes(head: ByteArray): Pair<Int, Int>? = when {
    head.isPng() -> pngSize(head)
    head.isGif() -> gifSize(head)
    head.isJpeg() -> jpegSize(head)
    else -> null
}

private fun ByteArray.u8(i: Int): Int = if (i in indices) this[i].toInt() and 0xff else -1

private fun ByteArray.be32(i: Int): Int =
    (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

private fun ByteArray.isPng(): Boolean =
    u8(0) == 0x89 && u8(1) == 0x50 && u8(2) == 0x4E && u8(3) == 0x47 &&
        u8(4) == 0x0D && u8(5) == 0x0A && u8(6) == 0x1A && u8(7) == 0x0A

/** PNG：8 字节签名 + 4 字节长度 + "IHDR" + 宽(4) + 高(4)，均大端。 */
private fun pngSize(b: ByteArray): Pair<Int, Int>? {
    if (b.size < 24) return null
    // 校验块类型确实是 IHDR，避免把别的格式误判成 PNG
    if (b.u8(12) != 'I'.code || b.u8(13) != 'H'.code || b.u8(14) != 'D'.code || b.u8(15) != 'R'.code) return null
    return b.be32(16) to b.be32(20)
}

private fun ByteArray.isGif(): Boolean =
    u8(0) == 'G'.code && u8(1) == 'I'.code && u8(2) == 'F'.code

/** GIF：6 字节头 + 逻辑屏幕宽(2, 小端) + 高(2, 小端)。 */
private fun gifSize(b: ByteArray): Pair<Int, Int>? {
    if (b.size < 10) return null
    val w = b.u8(6) or (b.u8(7) shl 8)
    val h = b.u8(8) or (b.u8(9) shl 8)
    return w to h
}

private fun ByteArray.isJpeg(): Boolean = u8(0) == 0xFF && u8(1) == 0xD8

/**
 * JPEG：跳段直到 SOFn，从 SOFn 取尺寸。
 *
 * 注意 SOF 段内字节序是 精度(1) **高(2) 宽(2)**——高在前，与 PNG 相反。
 * 写反会得到横竖颠倒的尺寸，而截图尺寸错会让后续坐标换算全错。
 */
private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
    var i = 2
    while (i + 9 < b.size) {
        if (b.u8(i) != 0xFF) { i++; continue }
        val marker = b.u8(i + 1)
        if (marker == 0xFF) { i++; continue }               // 填充字节可重复
        if (marker == 0xD8 || marker == 0xD9 || marker in 0xD0..0xD7) { i += 2; continue }
        val len = (b.u8(i + 2) shl 8) or b.u8(i + 3)
        if (len < 2) return null
        // SOF0..SOF15 中 0xC4(DHT)/0xC8(JPG)/0xCC(DAC) 不是 SOF
        val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
        if (isSof) {
            if (i + 9 >= b.size) return null
            val h = (b.u8(i + 5) shl 8) or b.u8(i + 6)
            val w = (b.u8(i + 7) shl 8) or b.u8(i + 8)
            return w to h
        }
        i += 2 + len
    }
    return null
}
