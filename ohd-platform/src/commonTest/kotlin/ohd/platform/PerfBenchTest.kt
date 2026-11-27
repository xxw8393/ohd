package ohd.platform

import kotlin.test.Test

/**
 * 跨端性能基准：同一份源码在 JVM 与 K/N 上各跑一遍。
 *
 * ## 为什么必须在 commonTest
 *
 * commonTest 会被 `jvm` 与 `macosArm64` 两个 target **各自编译执行**，跑的是同一份代码。
 * 放 jvmTest 就只能测 JVM，无法对比。
 *
 * ## 只测「已经能跨平台」的部分
 *
 * 刻意只覆盖已抽象到 `ohd.platform` 的能力：PNG 解码、摘要、路径运算、inflate。
 * 启动耗时、hdc 子进程、ImageIO 编码这些**还没跨平台**，测不了，
 * 也不该拿 JVM 数字外推——那正是这类对比最容易造假的地方。
 *
 * ## 对比口径
 *
 * - JVM 有 JIT：报 **预热后中位数**（实际长期运行）与**首轮**（可类比 K/N）
 * - K/N 无 JIT：直接报中位数
 * 两边都多轮取中位数，压单次抖动。结果用 `[BENCH]` 前缀 println，不做断言——
 * 基准是观测，不是通过/失败判据。
 */
class PerfBenchTest {

    private fun now(): Long = PlatformEnv.monotonicNanos()

    /** 返回 (中位数ms, 首轮ms)。 */
    private fun bench(rounds: Int, block: () -> Unit): Pair<Double, Double> {
        val times = ArrayList<Double>(rounds)
        repeat(rounds) {
            val t0 = now()
            block()
            times.add((now() - t0) / 1_000_000.0)
        }
        return times.sorted()[times.size / 2] to times.first()
    }

    private fun report(name: String, med: Double, first: Double) {
        println("[BENCH] $name median=${fmt(med)}ms first=${fmt(first)}ms")
    }

    private fun fmt(v: Double): String {
        val scaled = (v * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    // ---------- 造 PNG：只依赖 inflateRaw，不自己写 deflate ----------

    /**
     * 造一张 8-bit RGBA 非隔行 PNG。
     *
     * `IDAT` 必须是合法 zlib 流。这里不自己实现 deflate——用标准库的
     * [Fs.gzipCompress] 先压出 gzip，再剥掉 10 字节头与 8 字节尾，
     * 得到的就是**裸 zlib deflate 数据**。这样基准输入与真实设备产物同构，
     * 且不用在测试里维护第二套压缩实现。
     */
    private fun makePng(width: Int, height: Int): ByteArray {
        val stride = width * 4
        val raw = ByteArray(height * (stride + 1))
        var p = 0
        for (y in 0 until height) {
            raw[p++] = 0 // filter None
            for (x in 0 until width) {
                raw[p++] = ((x * 7 + y * 3) and 0xFF).toByte()
                raw[p++] = ((x * 3 + y * 11) and 0xFF).toByte()
                raw[p++] = ((x xor y) and 0xFF).toByte()
                raw[p++] = 0xFF.toByte()
            }
        }
        return container(width, height, deflateOf(raw))
    }

    private fun container(width: Int, height: Int, idat: ByteArray): ByteArray {
        val out = ArrayList<Byte>(idat.size + 128)
        fun u8(v: Int) {
            out.add(v.toByte())
        }
        fun u32(v: Int) {
            u8((v ushr 24) and 0xFF); u8((v ushr 16) and 0xFF)
            u8((v ushr 8) and 0xFF); u8(v and 0xFF)
        }
        fun chunk(type: String, data: ByteArray) {
            u32(data.size)
            val t = ByteArray(4) { type[it].code.toByte() }
            t.forEach { u8(it.toInt() and 0xFF) }
            data.forEach { u8(it.toInt() and 0xFF) }
            u32(crc32(t + data))
        }
        listOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).forEach { u8(it) }
        val ihdr = byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        )
        chunk("IHDR", ihdr)
        chunk("IDAT", idat)
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun crc32(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }


    /** PNG 的 IDAT 要的是**裸 zlib 流**（78 9c），不是 gzip。 */
    private fun deflateOf(raw: ByteArray): ByteArray =
        checkNotNull(fs.deflateRaw(raw)) { "deflateRaw 失败" }

    // ---------- 基准项 ----------

    @Test
    fun benchPngDecode() {
        val w = 640
        val h = 1416
        val png = makePng(w, h)
        val rounds = 8
        val (med, first) = bench(rounds) {
            checkNotNull(PngDecode.decode(png)) { "基准输入应能被 PngDecode 解码" }
        }
        println("[BENCH] png=${png.size / 1024}KB ${w}x$h rounds=$rounds")
        report("PngDecode.decode(${w}x$h)", med, first)
    }

    @Test
    fun benchDigest() {
        val bytes = ByteArray(1 shl 20) { (it and 0xFF).toByte() }
        val (med, first) = bench(10) { fs.sha256Hex(bytes) }
        report("sha256Hex(1MB)", med, first)
    }

    @Test
    fun benchPathOps() {
        val (med, first) = bench(10) {
            var acc = OhdPath.of("/tmp")
            repeat(2000) { acc = acc.resolve("seg$it").normalize() }
            check(acc.toString().isNotEmpty())
        }
        report("resolve+normalize x2000", med, first)
    }

    @Test
    fun benchInflate() {
        val raw = ByteArray(4 shl 20) { (it * 31 and 0xFF).toByte() }
        val deflate = deflateOf(raw)
        val (med, first) = bench(10) {
            checkNotNull(fs.inflateRaw(deflate)) { "inflateRaw 应能解裸 zlib" }
        }
        println("[BENCH] deflate=${deflate.size / 1024}KB -> inflate 4MB")
        report("inflateRaw(->4MB)", med, first)
    }
}
