package ohd.platform

/**
 * 纯 Kotlin 的 MD5 / SHA-1 / SHA-256。
 *
 * ## 为什么自己写而不是用平台库
 *
 * - **JVM** 侧本来有 `MessageDigest`，但它是 JVM 专属；若在这里 expect/actual，
 *   K/N 侧仍需一份实现，两边就有两套代码要各自对齐；
 * - **macOS** 有 CommonCrypto、**Linux** 有 OpenSSL，但两者 API 完全不同，且 OpenSSL
 *   在目标机器上不一定有（静态链接又违反"零第三方依赖"）；
 * - 这三个算法都是**公开标准且短**（合计约 300 行），纯 Kotlin 实现一次即可三平台复用，
 *   还能用 RFC 官方测试向量做单测。
 *
 * ## 正确性保证
 *
 * 单测用 NIST/RFC 官方向量（空串、`abc`、长串）逐字节比对，见 `DigestTest`。
 * 不与 `MessageDigest` 对比是有意的：若两边用同一份实现就无法发现算法错误。
 */
object Digest {

    fun md5Hex(data: ByteArray): String = md5(data).toHex()

    fun sha1Hex(data: ByteArray): String = sha1(data).toHex()

    fun sha256Hex(data: ByteArray): String = sha256(data).toHex()

    // ---------------------------------------------------------------- MD5

    fun md5(data: ByteArray): ByteArray {
        val msg = pad(data, littleEndianLength = true)
        var a0 = 0x67452301
        var b0 = 0xefcdab89.toInt()
        var c0 = 0x98badcfe.toInt()
        var d0 = 0x10325476

        val s = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )
        val k = IntArray(64) { i ->
            // K[i] = floor(2^32 * abs(sin(i+1)))
            ((kotlin.math.abs(kotlin.math.sin((i + 1).toDouble())) * 4294967296.0).toLong() and 0xffffffffL).toInt()
        }

        for (chunk in msg.indices step 64) {
            val m = IntArray(16) { i ->
                (msg[chunk + i * 4].toInt() and 0xff) or
                    ((msg[chunk + i * 4 + 1].toInt() and 0xff) shl 8) or
                    ((msg[chunk + i * 4 + 2].toInt() and 0xff) shl 16) or
                    ((msg[chunk + i * 4 + 3].toInt() and 0xff) shl 24)
            }
            var a = a0; var b = b0; var c = c0; var d = d0
            for (i in 0 until 64) {
                var f: Int
                var g: Int
                when (i) {
                    in 0..15 -> { f = (b and c) or (b.inv() and d); g = i }
                    in 16..31 -> { f = (d and b) or (d.inv() and c); g = (5 * i + 1) % 16 }
                    in 32..47 -> { f = b xor c xor d; g = (3 * i + 5) % 16 }
                    else -> { f = c xor (b or d.inv()); g = (7 * i) % 16 }
                }
                val tmp = d
                d = c
                c = b
                val sum = a + f + k[i] + m[g]
                b += rotl(sum, s[i])
                a = tmp
            }
            a0 += a; b0 += b; c0 += c; d0 += d
        }

        return ByteArray(16).also { out ->
            writeLe(out, 0, a0); writeLe(out, 4, b0); writeLe(out, 8, c0); writeLe(out, 12, d0)
        }
    }

    // --------------------------------------------------------------- SHA-1

    fun sha1(data: ByteArray): ByteArray {
        val msg = pad(data, littleEndianLength = false)
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        for (chunk in msg.indices step 64) {
            val w = IntArray(80)
            for (i in 0 until 16) {
                w[i] = readBe(msg, chunk + i * 4)
            }
            for (i in 16 until 80) {
                w[i] = rotl(w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16], 1)
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val tmp = rotl(a, 5) + f + e + k + w[i]
                e = d
                d = c
                c = rotl(b, 30)
                b = a
                a = tmp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
        }

        return ByteArray(20).also { out ->
            writeBe(out, 0, h0); writeBe(out, 4, h1); writeBe(out, 8, h2)
            writeBe(out, 12, h3); writeBe(out, 16, h4)
        }
    }

    // ------------------------------------------------------------- SHA-256

    private val SHA256_K = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    fun sha256(data: ByteArray): ByteArray {
        val msg = pad(data, littleEndianLength = false)
        val h = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )

        for (chunk in msg.indices step 64) {
            val w = IntArray(64)
            for (i in 0 until 16) w[i] = readBe(msg, chunk + i * 4)
            for (i in 16 until 64) {
                val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
                val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]

            for (i in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + SHA256_K[i] + w[i]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1
                d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d
            h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }

        return ByteArray(32).also { out ->
            for (i in 0 until 8) writeBe(out, i * 4, h[i])
        }
    }

    // ------------------------------------------------------------- 内部工具

    /** MD5 用 64 位小端长度，SHA-1/256 用 64 位大端长度；补位规则同为 0x80 + 0x00…。 */
    private fun pad(data: ByteArray, littleEndianLength: Boolean): ByteArray {
        val bitLen = data.size.toLong() * 8
        var padLen = 56 - (data.size % 64)
        if (padLen <= 0) padLen += 64
        val out = ByteArray(data.size + padLen + 8)
        data.copyInto(out)
        out[data.size] = 0x80.toByte()
        // 直接按目标字节序写，不做"先小端再反转"——实测那样会得到错误顺序
        val end = out.size
        if (littleEndianLength) {
            for (i in 0 until 8) {
                out[end - 8 + i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
            }
        } else {
            for (i in 0 until 8) {
                out[end - 1 - i] = ((bitLen ushr (8 * i)) and 0xff).toByte()
            }
        }
        return out
    }

    private fun readBe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    private fun writeBe(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    private fun writeLe(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
        b[off + 2] = (v ushr 16).toByte()
        b[off + 3] = (v ushr 24).toByte()
    }

    private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

    /** `Integer.rotateLeft` 是 JVM 专属，这里用位运算等价实现（K/N 也可用）。 */
    private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
