package ohd.core.actions

import ohd.core.DeviceKind
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.OhdError
import ohd.core.Session
import ohd.core.parsers.LayoutNode

/**
 * go/wait 前置：亮屏 +（可选）前台包；锁屏用已有缓存或调用方首轮 dump 判定，避免每次强制 dump 打掉缓存收益。
 */
object UiReady {

    data class Report(
        val awake: Boolean,
        val foreground: String?,
        val notes: List<String>,
    )

    private val LOCK_MARKERS = listOf(
        "滑动解锁", "上滑解锁", "Swipe to unlock", "滑动以解锁",
    )

    fun looksLikeLockScreen(nodes: List<LayoutNode>): Boolean {
        val texts = nodes.mapNotNull { n ->
            listOfNotNull(n.text, n.description).joinToString(" ")
        }
        if (texts.any { t -> LOCK_MARKERS.any { m -> t.contains(m, ignoreCase = true) } }) return true
        val hasWeekday = texts.any { it.contains("星期") || it.contains("周") }
        val businessClicks = nodes.count { it.clickable && (it.text?.length ?: 0) >= 2 }
        return hasWeekday && businessClicks <= 2
    }

    /** 桌面/负一屏：继续 tap/drag = 乱点图标。 */
    fun looksLikeDesktop(nodes: List<LayoutNode>): Boolean {
        val texts = nodes.mapNotNull { n -> n.text?.trim()?.takeIf { it.isNotEmpty() } }
        val demoHit = texts.any { t ->
            t.contains("多实例") || t.contains("OHOS运行时") || t.contains("GPU Texture") ||
                t.contains("Compose") || t.contains("一键测试") || t.contains("progressive")
        }
        if (demoHit) return false
        val launcherHints = texts.any { t ->
            t.contains("天气") || t.contains("图库") || t.contains("设置") && nodes.size < 40
        }
        val iconCells = nodes.count { n ->
            n.type == "RelativeContainer" || n.type == "Image" || n.type == "GridItem"
        }
        // 典型桌面：一堆图标格子、几乎没有 demo 文案
        return (iconCells >= 8 && texts.count { it.length >= 2 } <= 6) || (launcherHints && !demoHit)
    }

    /**
     * 操作前确认目标包仍在前台；已掉到桌面则中止（禁止继续点）。
     * @return 当前认为的前台包名
     */
    fun assertInApp(
        hdc: Hdc,
        bundle: String,
        flat: List<LayoutNode>? = null,
        ability: String = "EntryAbility",
        pullBackOnce: Boolean = true,
    ): String {
        if (flat != null && looksLikeDesktop(flat)) {
            if (pullBackOnce) {
                hdc.shell("aa start -b $bundle -a $ability", 15_000)
                Thread.sleep(800)
                LayoutCache.invalidate(hdc)
            }
            val again = runCatching { DeviceOps.captureLayout(hdc, null).flat() }.getOrNull()
            if (again != null && looksLikeDesktop(again)) {
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "已在桌面/负一屏，中止操作以免乱点",
                    "ohd 已停止注入；请重新 aa start $bundle 后再 go",
                )
            }
        }
        val fgs = runCatching { DeviceOps.foregroundBundles(hdc) }.getOrDefault(emptyList())
        if (bundle in fgs) return bundle
        if (pullBackOnce) {
            hdc.shell("aa start -b $bundle -a $ability", 15_000)
            Thread.sleep(800)
            val fgs2 = runCatching { DeviceOps.foregroundBundles(hdc) }.getOrDefault(emptyList())
            if (bundle in fgs2) return bundle
        }
        throw OhdError(
            ExitCode.ASSERT_FAILED,
            "已离开应用 $bundle（前台=${fgs.ifEmpty { listOf("?") }}），中止以免点桌面",
            "先手动回应用；检查翻页是否触发了系统手势",
        )
    }

    /**
     * 亮屏 + 可选拉回前台。不强制 dumpLayout。
     * @param bundle 非空时校验/拉回前台（不 force-stop）
     */
    fun ensure(
        hdc: Hdc,
        session: Session? = null,
        bundle: String? = null,
        ability: String = "EntryAbility",
    ): Report {
        val notes = mutableListOf<String>()
        var awake = true
        if (hdc.kind == DeviceKind.OHOS) {
            val w = hdc.shell("power-shell wakeup", 8_000)
            awake = w.ok
            notes += if (w.ok) "wakeup" else "wakeup_fail"
            runCatching { hdc.shell("power-shell setmode 602", 5_000) }
            runCatching { hdc.shell("power-shell timeout -o 600000", 5_000) }
        } else if (hdc.kind == DeviceKind.ANDROID) {
            hdc.shell("input keyevent KEYCODE_WAKEUP", 5_000)
            notes += "wakeup"
        }

        var foreground = runCatching { DeviceOps.foregroundBundle(hdc, session) }.getOrNull()
        val target = bundle?.takeIf { it.isNotBlank() }
        if (target != null) {
            if (foreground != target) {
                notes += "fg_was=${foreground ?: "?"}"
                when (hdc.kind) {
                    DeviceKind.OHOS -> hdc.shell("aa start -b $target -a $ability", 15_000)
                    DeviceKind.ANDROID -> hdc.shell(
                        "monkey -p $target -c android.intent.category.LAUNCHER 1",
                        15_000,
                    )
                    else -> notes += "fg_skip_${hdc.kind.label}"
                }
                Thread.sleep(700)
                foreground = runCatching { DeviceOps.foregroundBundle(hdc, session) }.getOrNull()
            }
            if ((hdc.kind == DeviceKind.OHOS || hdc.kind == DeviceKind.ANDROID) && foreground != target) {
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "前台不是 $target（现在 ${foreground ?: "?"}）",
                    "先手动打开应用，或检查 --bundle / --ability",
                )
            }
            notes += "fg=$target"
        }

        // 缓存里已是锁屏 → 先试解锁，避免拿锁屏节点去点
        LayoutCache.get(hdc)?.let { cached ->
            if (looksLikeLockScreen(cached)) {
                notes += "lock_in_cache"
                if (!tryUnlock(hdc)) {
                    throw OhdError(ExitCode.ASSERT_FAILED, "检测到锁屏且自动解锁失败", "请手动解锁后再跑")
                }
                LayoutCache.invalidate(hdc)
                notes += "unlocked"
                Thread.sleep(400)
            }
        }

        return Report(awake, foreground, notes)
    }

    /** 首轮 dump 后调用：若仍是锁屏则解锁并要求调用方重新 dump。 */
    fun assertNotLockOrUnlock(hdc: Hdc, flat: List<LayoutNode>): List<LayoutNode> {
        if (!looksLikeLockScreen(flat)) return flat
        if (!tryUnlock(hdc)) {
            throw OhdError(ExitCode.ASSERT_FAILED, "检测到锁屏且自动解锁失败", "请手动解锁后再跑")
        }
        LayoutCache.invalidate(hdc)
        Thread.sleep(500)
        val (again, _) = LayoutCache.nodes(hdc, null, fresh = true)
        if (looksLikeLockScreen(again)) {
            throw OhdError(ExitCode.ASSERT_FAILED, "仍在锁屏，无法 go/wait", "请手动解锁")
        }
        return again
    }

    private fun tryUnlock(hdc: Hdc): Boolean {
        val swipe = UiActions.Action(
            UiActions.Kind.SWIPE,
            listOf("640", "2200", "640", "800", "400"),
            "",
            0,
        )
        return UiActions.perform(hdc, swipe, 10_000).ok
    }
}
