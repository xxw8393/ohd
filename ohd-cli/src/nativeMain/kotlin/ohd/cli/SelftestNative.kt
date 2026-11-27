package ohd.cli

import ohd.core.Check
import ohd.image.defaultImageEngine
import ohd.platform.PngEncode
import ohd.platform.fs
import ohd.platform.fmt

/**
 * K/N 侧 CDS 自检：**不适用**。
 *
 * AppCDS 是 JVM 把类元数据预索引成 `ohd.jsa` 来省启动时间的机制；
 * 原生可执行本来就没有类加载阶段，没有对应物。这里返回 OK 说明「无需此优化」，
 * 而不是 WARN —— 用户不该为「一个不存在的概念」看到警告。
 */
internal actual fun cdsCheck(): Check =
    Check.ok("cds", "原生可执行无类加载阶段，不需要 AppCDS")

/** 图像引擎自检：生成一张小图 → 载入 → 自比较必须为 0 差分。 */
internal actual fun imageEngineCheck(): Check = try {
    val tmp = fs.createTempFile("ohd-selftest-", ".png")
    // 用平台的 PngEncode 造图（不再依赖 AWT）：32x32 棋盘格便于验证解码正确性
    val w = 32
    val h = 32
    val pixels = ByteArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val on = ((x / 4 + y / 4) % 2 == 0)
        val o = (y * w + x) * 4
        val v = if (on) 255.toByte() else 0
        pixels[o] = v; pixels[o + 1] = v; pixels[o + 2] = v; pixels[o + 3] = 255.toByte()
    }
    val png = PngEncode.encodeRgba(w, h, pixels)
        ?: return Check.fail("image", "PNG 编码失败")
    fs.writeBytes(tmp, png)
    val engine = defaultImageEngine()
    val g = engine.load(tmp)
    val d = engine.diff(g, g)
    fs.deleteIfExists(tmp)
    if (d.maxAbs == 0 && d.meanAbs == 0.0) {
        Check.ok("image", "engine=${engine.name} 解码/差分正常（HF=${fmt("%.2f", engine.highFrequencyEnergy(g))}）")
    } else {
        Check.fail("image", "自比较差分非 0：max=${d.maxAbs} mean=${d.meanAbs}")
    }
} catch (e: Exception) {
    Check.fail("image", "图像引擎异常：${e.message}")
}
