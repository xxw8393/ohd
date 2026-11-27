package ohd.core

import ohd.core.caps.Redactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 脱敏的边界：既要挡住敏感信息，又绝不能毁掉排查证据。
 * 最危险的误伤是系统版本号（6.1.0.135）被当成 IPv4。
 */
class RedactTest {
    private val r = Redactor("63R0226113004249")

    @Test
    fun homePathStripped() {
        assertEquals("<HOME>/KMP/ohd/x.log", r.redact("/Users/xuxingwang/KMP/ohd/x.log"))
        assertEquals("<HOME>/build/out", r.redact("/home/alice/build/out"))
    }

    @Test
    fun serialKeepsTailFour() {
        val out = r.redact("device 63R0226113004249 connected")
        assertEquals("device <SERIAL-4249> connected", out)
    }

    @Test
    fun versionStringIsNotTreatedAsIp() {
        val v = "SGT-AL50 6.1.0.135(SP8C00E120R3P11)"
        assertEquals(v, r.redact(v), "系统版本号被误脱敏，构建指纹会丢失")
    }

    @Test
    fun ipWithPortRedacted() {
        assertTrue(r.redact("connect 192.168.1.5:8080").contains("<IP>:8080"))
    }

    @Test
    fun ipInContextRedacted() {
        assertTrue(r.redact("server=10.0.0.1").contains("server=<IP>"))
        assertTrue(r.redact("host: 172.16.3.9").contains("<IP>"))
    }

    @Test
    fun phoneEmailTokenRedacted() {
        assertTrue(r.redact("tel 13800138000").contains("<PHONE>"))
        assertTrue(r.redact("mail a.b@example.com").contains("<EMAIL>"))
        assertTrue(r.redact("Authorization: Bearer abcdefghijklmnop1234").contains("Bearer <TOKEN>"))
    }

    @Test
    fun evidenceKeptIntact() {
        // 这些必须在脱敏后原样保留，否则包就没用了
        val keep = listOf(
            "com.tencent.compose",
            "/system/lib64/platformsdk/libark_jsruntime.so",
            "panda::ecmascript::ObjectFactory::GetJSError",
            "0c10ca1f290a450c19e957291d44cbf4",
        )
        keep.forEach { assertEquals(it, r.redact(it), "证据字段被误脱敏：$it") }
    }
}
