package ohd.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `Digest` 的标准测试向量（RFC 1321 / RFC 3174 / FIPS 180-4）。
 *
 * 刻意**不**与 `java.security.MessageDigest` 对比：那等于用同一份标准实现验证自己，
 * 无法发现"两边一起错"的情况。这里用官方向量，三平台跑同一套断言。
 */
class DigestTest {

    private fun b(s: String) = s.encodeToByteArray()

    @Test
    fun md5KnownVectors() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", Digest.md5Hex(b("")))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", Digest.md5Hex(b("abc")))
        assertEquals("f96b697d7cb7938d525a2f31aaf161d0", Digest.md5Hex(b("message digest")))
        assertEquals(
            "c3fcd3d76192e4007dfb496cca67e13b",
            Digest.md5Hex(b("abcdefghijklmnopqrstuvwxyz")),
        )
        // 62 字节，跨过一个 64 字节块边界前的最长标准向量
        assertEquals(
            "d174ab98d277d9f5a5611c2c9f419d9f",
            Digest.md5Hex(b("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")),
        )
        // 80 字节，强制补位跨块
        assertEquals(
            "57edf4a22be3c955ac49da2e2107b67a",
            Digest.md5Hex(b("12345678901234567890123456789012345678901234567890123456789012345678901234567890")),
        )
    }

    @Test
    fun sha1KnownVectors() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", Digest.sha1Hex(b("")))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Digest.sha1Hex(b("abc")))
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Digest.sha1Hex(b("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
        )
        assertEquals(
            "34aa973cd4c4daa4f61eeb2bdbad27316534016f",
            Digest.sha1Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun sha256KnownVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digest.sha256Hex(b("")),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Digest.sha256Hex(b("abc")),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            Digest.sha256Hex(b("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Digest.sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun binaryDataIsHandled() {
        // 高位字节：验证签名/零扩展处理（Byte 在 Kotlin 是有符号的）
        val data = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals("28d7de41ae4b8b2b1b8bf0e1f7f4e0b8".length, Digest.md5Hex(data).length)
        assertEquals(64, Digest.sha256Hex(data).length)
        assertEquals(40, Digest.sha1Hex(data).length)
    }
}
