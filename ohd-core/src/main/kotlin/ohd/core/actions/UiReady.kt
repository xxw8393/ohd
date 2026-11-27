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

    /**
     * 前台包查询的最短复用间隔。
     *
     * 查一次前台要跑 `aa dump -a`，设备侧实测 134–229ms，而一次 `go` 里会查 2–4 次
     * （点前、收尾、batchSwipe 每滑一次），一分钟的 monkey 跑下来纯属重复劳动。
     * 前台状态不会在几十毫秒内变，按 5s 节流：期间复用上次结果，不重复打设备。
     *
     * 选 5s 的依据：正常操作节奏下一步（认路+点击+验标）就是秒级，
     * 5s 内前台几乎不可能变；而真掉桌面时，最坏晚 5s 发现，代价是作废一轮，可接受。
     *
     * 粒度：**进程内**缓存，按 (设备, 包) 键。不跨进程 —— 每条命令新起 JVM，
     * 跨进程缓存要额外落盘且引入失效问题，收益为零（命令间本就该重新确认）。
     */
    private const val FG_TTL_MS = 5_000L

    private data class FgStamp(val bundles: List<String>, val atMs: Long)

    private val fgCache = java.util.concurrent.ConcurrentHashMap<String, FgStamp>()

    /**
     * 带 TTL 的前台包查询：5s 内复用，过期或 [force] 才真正执行 `aa dump -a`。
     */
    fun foregroundCached(hdc: Hdc, bundle: String, force: Boolean = false): List<String> {
        val key = "${hdc.connectKey ?: hdc.kind.label}|$bundle"
        val now = System.currentTimeMillis()
        if (!force) {
            fgCache[key]?.let { if (now - it.atMs <= FG_TTL_MS) return it.bundles }
        }
        val fresh = runCatching { DeviceOps.foregroundBundles(hdc) }.getOrDefault(emptyList())
        if (fresh.isNotEmpty()) fgCache[key] = FgStamp(fresh, now)
        return fresh
    }

    /** 明确的前台失效（如主动 aa start 之后），下次查询必真打设备。 */
    fun invalidateForeground(hdc: Hdc, bundle: String) {
        fgCache.remove("${hdc.connectKey ?: hdc.kind.label}|$bundle")
    }

    /**
     * 已知某包在前台时直接记进缓存。
     *
     * 用于「`dumpLayout -b` 成功返回」这种**比 `aa dump -a` 更强**的证据：
     * 既然能 dump 出目标窗口的树，目标必然在前台，不必再跑一条 shell 去确认。
     */
    fun rememberForeground(hdc: Hdc, bundle: String) {
        fgCache["${hdc.connectKey ?: hdc.kind.label}|$bundle"] =
            FgStamp(listOf(bundle), System.currentTimeMillis())
    }

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
    /**
     * 确认仍在目标 App 内。
     *
     * [flat] 非空时**不再单独跑 `aa dump -a`**（实测 358ms/次）：
     * 调用方刚 dump 完这棵树，它就是当前界面的事实。此时只需判「是不是桌面」。
     *
     * 要判「前台是不是目标包」得靠 dump 本身 —— `DeviceOps.captureLayout(bundle=)`
     * 在 `-b` 目标不在前台时会抛错（退化空树），调用链上已经拦住了。
     * 只有拿不到树（`flat == null`，例如滑动后只想快速确认）时才退回查前台。
     */
    fun assertInApp(
        hdc: Hdc,
        bundle: String,
        flat: List<LayoutNode>? = null,
        ability: String = "EntryAbility",
        pullBackOnce: Boolean = true,
        forceFg: Boolean = false,
    ): String {
        if (flat != null) {
            if (!looksLikeDesktop(flat)) return bundle
            if (pullBackOnce) {
                hdc.shell("aa start -b $bundle -a $ability", 15_000)
                Thread.sleep(800)
                LayoutCache.invalidate(hdc)
                invalidateForeground(hdc, bundle)
            }
            val again = runCatching { DeviceOps.captureLayout(hdc, null, bundle = bundle).flat() }.getOrNull()
            if (again != null && looksLikeDesktop(again)) {
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "已在桌面/负一屏，中止操作以免乱点",
                    "ohd 已停止注入；请重新 aa start $bundle 后再 go",
                )
            }
            return bundle
        }
        val fgs = foregroundCached(hdc, bundle, force = forceFg)
        if (bundle in fgs) return bundle
        if (pullBackOnce) {
            hdc.shell("aa start -b $bundle -a $ability", 15_000)
            Thread.sleep(800)
            // 刚主动切过前台，缓存必然已过期：强制重查，不能吃 TTL 内的旧值
            invalidateForeground(hdc, bundle)
            val fgs2 = foregroundCached(hdc, bundle, force = true)
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

        val target = bundle?.takeIf { it.isNotBlank() }
        // 有 target 时用 `dumpLayout -b <target>` 一次拿到「树 + 是否在前台」，
        // 取代原来独立的 `aa dump -a`（实测 358ms/次）。
        // 无 target（如 wait 不指定包）时没法用 -b 判前台，才退回查 aa dump -a。
        var foreground: String? = null
        if (target != null && hdc.kind == DeviceKind.OHOS) {
            // 先在缓存里找；命中说明最近刚确认过，不必再 dump
            foreground = foregroundCached(hdc, target).firstOrNull()
            if (foreground == null) {
                // 未命中：用一次 dump 探活。目标不在前台时 captureLayout 会抛
                // 「前台不是 X」（退化空树判据），这里接住并走 aa start 拉回。
                foreground = runCatching {
                    DeviceOps.captureLayout(hdc, session, bundle = target)
                    target
                }.getOrNull()
                if (foreground != null) {
                    runCatching { rememberForeground(hdc, target) }
                }
            }
        }
        if (foreground == null) {
            foreground = runCatching { DeviceOps.foregroundBundle(hdc, session) }.getOrNull()
        }
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
                invalidateForeground(hdc, target)
                foreground = foregroundCached(hdc, target, force = true).firstOrNull()
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
