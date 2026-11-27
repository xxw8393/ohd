package ohd.core

import ohd.core.actions.UiReady
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 前台查询 5s 节流的真实行为。
 *
 * 不用测试后门：`Hdc` 的执行入口是外部可执行文件，这里给一个**假 hdc 脚本**，
 * 每次被调用就往计数文件里 +1 并回放预设的 `aa dump -a` 输出。
 * 于是「到底打了几次设备」是可观测的，测的就是真实调用链。
 *
 * 背景：真实 `aa dump -a` 设备侧 134–229ms，一次 `go` 会查 2–4 次，
 * 节流把 5s 内的重复查询收敛成一次。
 */
class UiReadyForegroundCacheTest {

    private val bundle = "com.example.target"
    private val tmp: Path = Files.createTempDirectory("ohd-fgcache")
    private val counter: Path = tmp.resolve("count")
    private val state: Path = tmp.resolve("state")

    /** 造一个假 hdc：`aa dump -a` 输出 [state] 文件内容，并记一次调用。 */
    private fun fakeHdc(exeName: String = "hdc"): Hdc {
        val script = tmp.resolve(exeName)
        Files.writeString(
            script,
            """
            #!/bin/sh
            # 只统计 foregroundBundles 真正关心的那条命令
            case "${'$'}*" in
              *"aa dump -a"*)
                echo x >> "$counter"
                cat "$state" 2>/dev/null
                exit 0
                ;;
            esac
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            script,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        // connectKey 参与缓存键，不同 connectKey 必须是不同的缓存条目
        return Hdc(exe = script, connectKey = exeName, kind = DeviceKind.OHOS)
    }

    private fun setForeground(b: String) {
        // 必须符合 DeviceOps.foregroundBundlesIn 的真实解析规则：
        //   「app state #FOREGROUND」行向上回溯找最近的 `bundle name [xxx]`。
        // 早期这里写成随意文本，解析器返回空列表 → 缓存永不写入 → 测试假失败。
        Files.writeString(
            state,
            """
            Mission ID #1  mission name #target#
              bundle name [$b]
              app state #FOREGROUND
            """.trimIndent(),
        )
    }

    private fun calls(): Int = if (Files.exists(counter)) Files.readAllLines(counter).size else 0

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    /** 5s 内的重复查询必须只打设备一次。 */
    @Test
    fun repeatsWithinTtlHitDeviceOnce() {
        setForeground(bundle)
        val hdc = fakeHdc("hdc-ttl")
        UiReady.invalidateForeground(hdc, bundle)

        val first = UiReady.foregroundCached(hdc, bundle)
        assertTrue(bundle in first, "假 hdc 应回放 [$bundle]，实际=$first")
        val afterFirst = calls()

        repeat(4) { UiReady.foregroundCached(hdc, bundle) }
        assertEquals(afterFirst, calls(), "TTL 内的重复查询不该再打设备")
    }

    /** force=true 必须绕过 TTL（点前闸门、滑动后校验依赖这一点）。 */
    @Test
    fun forceBypassesTtl() {
        setForeground(bundle)
        val hdc = fakeHdc("hdc-force")
        UiReady.invalidateForeground(hdc, bundle)

        UiReady.foregroundCached(hdc, bundle)
        val before = calls()
        UiReady.foregroundCached(hdc, bundle, force = true)
        assertEquals(before + 1, calls(), "force=true 必须真的查一次")
    }

    /**
     * 掉桌面后即使 TTL 未过期，force 也必须能看见新前台。
     * 这守的是「5s 节流不能把掉桌面这件事藏起来」。
     */
    @Test
    fun forceSeesForegroundChangeImmediately() {
        setForeground(bundle)
        val hdc = fakeHdc("hdc-change")
        UiReady.invalidateForeground(hdc, bundle)

        assertTrue(bundle in UiReady.foregroundCached(hdc, bundle))

        // 还在 TTL 内就切到桌面
        val desktop = "com.ohos.sceneboard"
        setForeground(desktop)

        // 非 force：仍是旧值（节流的代价，可接受）
        assertTrue(
            bundle in UiReady.foregroundCached(hdc, bundle),
            "TTL 内应复用旧值（这是节流本来的行为）",
        )
        // force：必须立刻看到新前台
        val forced = UiReady.foregroundCached(hdc, bundle, force = true)
        assertTrue(
            bundle !in forced,
            "force 必须绕过 TTL 看到已切走的前台，实际=$forced",
        )
    }

    /** invalidate 之后必须重新打设备。 */
    @Test
    fun invalidateForcesNextQuery() {
        setForeground(bundle)
        val hdc = fakeHdc("hdc-inv")
        UiReady.invalidateForeground(hdc, bundle)

        UiReady.foregroundCached(hdc, bundle)
        val before = calls()
        UiReady.invalidateForeground(hdc, bundle)
        UiReady.foregroundCached(hdc, bundle)
        assertEquals(before + 1, calls(), "invalidate 后应重新查询")
    }

    /** 两个设备（不同 connectKey）的缓存互不串用。 */
    @Test
    fun cacheIsPerDevice() {
        setForeground(bundle)
        val a = fakeHdc("hdc-devA")
        val b = fakeHdc("hdc-devB")
        UiReady.invalidateForeground(a, bundle)
        UiReady.invalidateForeground(b, bundle)

        UiReady.foregroundCached(a, bundle)
        val afterA = calls()
        UiReady.foregroundCached(b, bundle)
        assertEquals(afterA + 1, calls(), "不同设备必须各查一次，不能共用缓存")
    }
}
