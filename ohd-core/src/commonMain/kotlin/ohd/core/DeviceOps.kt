package ohd.core

import ohd.platform.Env
import ohd.platform.Mutex
import ohd.platform.fs

import ohd.platform.OhdPath

import ohd.platform.imageSizeOf

import ohd.core.parsers.AndroidLayoutParser
import ohd.core.parsers.IosLayoutParser
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.LayoutParser

/**
 * 设备操作的公共动作：避免每个能力各写一遍「建目录 → 落端侧 → recv → 解析」。
 * 端侧临时目录固定 /data/local/tmp/ohd/，产物名带毫秒时间戳防覆盖（recv 会覆盖 mtime）。
 */
object DeviceOps {
    /**
     * 前台应用包名。
     *
     * 两个坑（都实测过）：
     * 1. `aa dump -a` **在设备端接管道就没有输出**（`hdc shell "aa dump -a"` 正常，加 `| head` 即空），
     *    所以必须整段取回本地解析，不能在设备侧 grep。
     * 2. `state #FOREGROUND` 出现在 `bundle name` 之后几行，需向上回溯最近的 bundle name。
     */
    /**
     * 所有带前台标记的包名。
     * `AbilityRecord ... state #FOREGROUND` 写在 `bundle name` 之前，必须向后找本条记录；
     * `app state #FOREGROUND` 写在包名之后，向上回溯。
     */
    fun foregroundBundlesIn(raw: String): List<String> {
        val lines = raw.lines()
        val found = linkedSetOf<String>()
        val bundleRe = Regex("""bundle name \[([^\]]+)]""")
        for (i in lines.indices) {
            val line = lines[i]
            if (line.contains("AbilityRecord") && line.contains("state #FOREGROUND")) {
                for (j in i + 1 until minOf(lines.size, i + 15)) {
                    if (lines[j].contains("AbilityRecord")) break
                    val name = bundleRe.find(lines[j])?.groupValues?.get(1) ?: continue
                    found += name
                    break
                }
            } else if (line.contains("app state #FOREGROUND")) {
                for (j in i - 1 downTo maxOf(0, i - 15)) {
                    val name = bundleRe.find(lines[j])?.groupValues?.get(1) ?: continue
                    found += name
                    break
                }
            }
        }
        return found.toList()
    }

    fun foregroundBundles(hdc: Hdc): List<String> {
        val raw = runCatching { hdc.shell("aa dump -a", 15_000).stdout }.getOrNull() ?: return emptyList()
        return foregroundBundlesIn(raw)
    }

    fun foregroundBundle(hdc: Hdc, session: Session? = null): String? {
        val raw = runCatching { hdc.shell("aa dump -a", 15_000).stdout }.getOrNull() ?: return null
        var lastBundle: String? = null
        for (line in raw.lineSequence()) {
            Regex("""bundle name \[([^\]]+)]""").find(line)?.let { lastBundle = it.groupValues[1] }
            if (line.contains("state #FOREGROUND")) return lastBundle
        }
        return null
    }

    const val TMP = "/data/local/tmp/ohd"

    fun ensureTmp(hdc: Hdc): Hdc.Result = hdc.shell("mkdir -p $TMP", 10_000)

    /**
     * Align with DevEco Testing / Hypium: keep the system accessibility process and
     * uitest daemon up so Compose (and ArkUI) see isOpenAccessibilitySync as true.
     * Without this, monkey tap/swipe alone often leaves app a11y disabled and misses
     * APP_INPUT_BLOCK-class layout hot paths.
     *
     * @return short status for humans ("ok pid=...") or error text; never throws.
     */
    fun ensureOhosA11yClient(hdc: Hdc, waitMs: Long = 15_000L): String {
        if (hdc.kind != DeviceKind.OHOS) return "skip(non-ohos)"
        runCatching { hdc.shell("/system/bin/uitest start-daemon singleness", 15_000) }
        val deadline = Env.nowMillis() + waitMs
        var a11yPid = ""
        while (Env.nowMillis() < deadline) {
            a11yPid = runCatching {
                hdc.shell("pidof accessibility", 5_000).stdout.trim()
            }.getOrDefault("")
            if (a11yPid.isNotBlank()) break
            Env.sleepMillis(500)
        }
        val uitestPid = runCatching {
            hdc.shell("pidof uitest", 5_000).stdout.trim()
        }.getOrDefault("")
        return if (a11yPid.isNotBlank()) {
            "ok accessibility=$a11yPid uitest=${uitestPid.ifBlank { "?" }}"
        } else {
            "fail: accessibility pid missing after ${waitMs}ms (uitest=${uitestPid.ifBlank { "none" }})"
        }
    }

    /**
     * Cheap dump that exercises accessibility Find queries without a full recv/parse.
     * Used by monkey keep-a11y between cached layout refreshes.
     */
    fun pingOhosA11yDump(hdc: Hdc): Boolean {
        if (hdc.kind != DeviceKind.OHOS) return false
        ensureTmp(hdc)
        val remote = "$TMP/a11y-ping.json"
        val dump = hdc.shell("uitest dumpLayout -p $remote", 30_000)
        return dump.ok
    }

    data class LayoutCapture(val roots: List<LayoutNode>, val file: OhdPath?) {
        fun flat(): List<LayoutNode> = roots.flatMap { it.flatten() }
    }

    /**
     * 本进程内「无障碍客户端已就绪」的设备集合。
     *
     * 只做会话级缓存：进程被杀时由 dump 失败路径重新探测。
     * 用设备序列号作键，避免多设备互相污染。
     */
    private val a11yReadyLock = Mutex()
    private val a11yReadyDevices = HashSet<String>()

    private fun a11yKey(hdc: Hdc): String = hdc.connectKey ?: "default"

    private fun a11yReady(hdc: Hdc): Boolean =
        a11yReadyLock.withLock { a11yReadyDevices.contains(a11yKey(hdc)) }

    private fun markA11yReady(hdc: Hdc) {
        a11yReadyLock.withLock { a11yReadyDevices.add(a11yKey(hdc)) }
    }

    /** dump 失败时调用：下一轮重新探测无障碍进程是否还在。 */
    fun invalidateA11yReady(hdc: Hdc) {
        a11yReadyLock.withLock { a11yReadyDevices.remove(a11yKey(hdc)) }
    }

    fun captureLayout(hdc: Hdc, session: Session?, rich: Boolean = false, bundle: String? = null): LayoutCapture {
        ensureTmp(hdc)
        if (hdc.kind == DeviceKind.ANDROID) return captureLayoutAndroid(hdc, session)
        if (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE) return captureLayoutIos(hdc)
        // 无障碍进程起来后不会自行退出，故按设备记住"已确认过"，避免每次 dump 前
        // 多付一次 pidof（实测 0.13s，占单次 dump 的 9%）。
        // 只有在确认尚未完成时才查；dump 失败再回头重查一次兜底（进程可能被杀）。
        if (!a11yReady(hdc)) {
            val a11yPid = runCatching {
                hdc.shell("pidof accessibility", 3_000).stdout.trim()
            }.getOrDefault("")
            if (a11yPid.isBlank()) {
                ensureOhosA11yClient(hdc, waitMs = 5_000L)
            }
            // 即使 ensure 返回失败也标记，避免每轮都重复等 5s；失败由 dump 自己报错
            markA11yReady(hdc)
        }
        val remote = "$TMP/layout-${Env.nowMillis()}.json"
        // `-b <bundle>` 限定目标窗口。不加拿到的是整屏合并树，会把状态栏时钟
        // （'21, :, 58'/'58'）与系统手势提示（'单指双击即可返回。'）一并带进来。
        // 实测同一屏：无 -b 137 节点/90.6KB，带 -b 64 节点/41.7KB。
        // 关键不只是省传输 —— 那些时钟文本会进 LayoutCache.fingerprint 的材料，
        // 每变一秒就生成一个新 pageId，同场景永远凑不出「对照页」，
        // coordsStable 只能退回 seenCount 启发式，map-first 可用率因此被压到 19.8%。
        val filter = bundle?.takeIf { it.isNotBlank() }?.let { b -> " -b $b" }.orEmpty()
        val cmd = buildString {
            append("uitest dumpLayout -p ").append(remote).append(filter)
            if (rich) append(" -a")
        }
        val dump = hdc.shell(cmd, 30_000)
        if (!dump.ok) {
            // 无障碍进程可能被杀：作废缓存，下一轮重新探测，避免一直跳过检查
            invalidateA11yReady(hdc)
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "dumpLayout 失败：${dump.text().take(300)}",
                "确认设备已解锁、前台有窗口，且 /system/bin/uitest 可用（ohd doctor）",
            )
        }
        val local = session?.nextPath("layout", "json")
            ?: fs.createTempFile("ohd-layout-", ".json")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) {
            throw OhdError(ExitCode.TIMEOUT, "recv layout 失败：${recv.text().take(300)}")
        }
        val roots = LayoutParser.parseFile(local)
        session?.register(local, "layout")
        // `-b` 指定的包不在前台时，dumpLayout 依然 rc=0，但只产出一棵退化空树：
        //   {"attributes":{"bounds":"[0,0][0,0]","text":"","type":""},"children":[]}
        // 实测对不存在的包必为 1 个节点、0 面积、无 children；正常前台包为 60+ 节点。
        //
        // 这条判据让 `aa dump -a`（实测 358ms/次）变成冗余 —— 前台与否
        // 本来就是这次 dump 免费携带的信息，不必再单独跑一条 shell 去问。
        // `-b` 目标不在前台时 dumpLayout 依然 rc=0，只产出退化空树
        //   {"attributes":{"bounds":"[0,0][0,0]","text":"","type":""},"children":[]}
        //
        // 但**空树不等于不在前台**：实测跟随 Kong 自动化时，同样空树出现在
        // `aa dump -a` 明确报 com.tencent.compose #FOREGROUND 的时刻 ——
        // 那是界面重建/窗口尚未 attach 的瞬时状态（`aa dump -a` 里 `window attached #0`）。
        // 若这里直接抛错，高频切页的场景会把成功误判成「前台不是 X」。
        //
        // 故空树只作为**降级信号**：重试一次仍空，才去问一次 aa dump -a 定论；
        // 前台确实是目标包，就当作瞬时空窗继续返回（上层会重试或按空处理）。
        if (filter.isNotEmpty() && isEmptyForegroundProbe(roots) && !foregroundMatches(hdc, bundle)) {
            throw OhdError(
                ExitCode.ASSERT_FAILED,
                "前台不是 $bundle（dumpLayout -b 返回空树，且 aa dump -a 未报该包在前台）",
                "先手动打开应用，或检查 --bundle / --ability",
            )
        }
        return LayoutCapture(roots, local)
    }

    /**
     * 空树时的权威判定：问一次 `aa dump -a`。
     *
     * 这条 shell 平时能省则省（358ms/次），但**只在空树这种歧义时刻**才用，
     * 属于「代价只在少数路径上付」的兜底，不构成热路径开销。
     */
    private fun foregroundMatches(hdc: Hdc, bundle: String?): Boolean {
        if (bundle.isNullOrBlank()) return true
        if (hdc.kind != DeviceKind.OHOS) return true
        val fgs = runCatching { foregroundBundles(hdc) }.getOrDefault(emptyList())
        return bundle in fgs
    }

    /**
     * 是否是「`-b` 目标窗口不在前台」时 dumpLayout 返回的退化树。
     *
     * 判据取「整棵树只有 1 个节点且面积/文本/类型全空」：
     * 真实界面即使全图形也有 bounds（实测 `tree` 恒 60+ 节点）；
     * 界面尚未渲染完的空窗则会在上层重试逻辑里被再 dump 一次，不靠这里兜。
     */
    private fun isEmptyForegroundProbe(roots: List<LayoutNode>): Boolean {
        if (roots.size != 1) return false
        val only = roots.first()
        if (only.children.isNotEmpty()) return false
        val b = only.bounds
        val zeroArea = b == null || b.width <= 0 || b.height <= 0
        val noText = only.text.isNullOrBlank() && only.description.isNullOrBlank()
        val noType = only.type.isNullOrBlank()
        return zeroArea && noText && noType
    }

    /** Android：uiautomator dump 出 XML，再拉回来解析成同一棵 LayoutNode 树。 */
    /**
     * iOS 走 idb 的 `ui describe-all`。
     *
     * 注意返回的 bounds 是**逻辑点**，与截图（像素）差一个 scale；
     * 注入侧也用逻辑点，所以两边天然一致，不要在这里乘 scale。
     */
    private fun captureLayoutIos(hdc: Hdc): LayoutCapture {
        val exe = IdbInjector.locate(null)
            ?: throw OhdError(
                ExitCode.UNSUPPORTED,
                "未找到 idb（iOS 的 UI 层级与触摸注入都依赖它）",
                "pip3 install fb-idb，或设置 OHD_IDB 指向 idb 可执行文件",
            )
        val udid = hdc.connectKey
            ?: throw OhdError(ExitCode.UNSUPPORTED, "缺少设备 UDID", "用 --device 指定完整 UDID")
        val raw = IdbInjector(exe, udid).describeAll()
            ?: throw OhdError(
                ExitCode.UNSUPPORTED,
                "idb describe-all 没有返回层级",
                "确认应用在前台，且 idb_companion 可用（idb list-targets）",
            )
        return LayoutCapture(IosLayoutParser.parse(raw), null)
    }

    private fun captureLayoutAndroid(hdc: Hdc, session: Session?): LayoutCapture {
        val remote = "$TMP/layout-${Env.nowMillis()}.xml"
        val dump = hdc.shell("uiautomator dump --compressed $remote", 30_000)
        if (!dump.ok || !dump.text().contains("dumped to")) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "uiautomator dump 失败：${dump.text().take(300)}",
                "确认设备已解锁、前台有窗口；模拟器需开启硬件渲染",
            )
        }
        val local = session?.nextPath("layout", "xml")
            ?: fs.createTempFile("ohd-layout-", ".xml")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "pull layout 失败：${recv.text().take(300)}")
        val roots = AndroidLayoutParser.parseFile(local)
        session?.register(local, "layout")
        return LayoutCapture(roots, local)
    }

    /**
     * OHOS 截图格式。
     *
     * 早期结论「snapshot_display 只接受 .jpeg」是**误读**：那次没带 `-t`，
     * 而 `-t` 默认就是 `jpeg`，所以后缀也必须 .jpeg。真机实测（2026-11-27，API 24）：
     *
     * ```
     * snapshot_display -t png  -f x.png    # 真 PNG，89 50 4e 47，RGBA non-interlaced
     * snapshot_display -t jpeg -f x.png    # error: suffix must be .png
     * snapshot_display        -f x.png    # error: suffix must be .jpeg（type 默认 jpeg）
     * ```
     *
     * 即：**后缀约束跟着 `-t` 走**，不是只支持 jpeg。
     * 实测代价（1280x2832，各 3 次）：jpeg 约 0.26–0.37s / 181KB；
     * png 约 0.63–0.71s / 1.38MB。故按用途选，不全局切 PNG。
     */
    enum class ShotFormat(val type: String, val suffix: String) {
        /** 无损。给 diff / 标注用：JPEG 的块效应与振铃会伪造高频能量，污染像素级判据。 */
        PNG("png", "png"),

        /** 有损但小、快。给录屏 / GIF / 只给人眼看图用。 */
        JPEG("jpeg", "jpeg"),
    }

    data class ShotRec(val file: OhdPath, val width: Int, val height: Int)

    /**
     * 截一张图。[format] 决定 `-t` 与后缀（两者必须一致，见 [ShotFormat]）。
     * 设备会在 stdout 打印 "width: N, height: M"，无需解码即可拿尺寸。
     */
    fun snapshot(
        hdc: Hdc,
        session: Session?,
        displayId: Int? = null,
        format: ShotFormat = ShotFormat.PNG,
    ): ShotRec {
        val suffix = format.suffix
        IosSupport.requireSupported("shot", hdc.kind)
        // 模拟器截图直接落到宿主机：simctl io 自己写文件，无需 mkdir + recv
        if (hdc.kind == DeviceKind.IOS) return snapshotIos(hdc, session)
        ensureTmp(hdc)
        if (hdc.kind == DeviceKind.ANDROID) return snapshotAndroid(hdc, session)
        val remote = "$TMP/shot-${Env.nowMillis()}.$suffix"
        val cmd = buildString {
            append("snapshot_display")
            displayId?.let { append(" -i ").append(it) }
            append(" -t ").append(format.type)
            append(" -f ").append(remote)
        }
        val r = hdc.shell(cmd, 30_000)
        if (!r.ok || !r.stdout.contains("width")) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "截图失败：${r.text().take(300)}",
                "设备侧快照要求文件名后缀与 -t 一致（当前 -t ${format.type} → .$suffix），且需前台有窗口",
            )
        }
        val w = Regex("""width:\s*(\d+)""").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val h = Regex("""height:\s*(\d+)""").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val local = session?.nextPath("shot", suffix)
            ?: fs.createTempFile("ohd-shot-", ".$suffix")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "recv 截图失败：${recv.text().take(300)}")
        session?.register(local, "shot")
        return ShotRec(local, w, h)
    }

    /** iOS 模拟器：`simctl io <udid> screenshot <宿主路径>`，尺寸读 PNG 头（不解码像素）。 */
    private fun snapshotIos(hdc: Hdc, session: Session?): ShotRec {
        val local = session?.nextPath("shot", "png") ?: fs.createTempFile("ohd-shot-", ".png")
        val r = hdc.run(listOf("io", "screenshot", local.toAbsolutePath().toString()), 30_000)
        if (!r.ok || !fs.exists(local)) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "simctl 截图失败：${r.text().take(300)}",
                "确认模拟器已 Booted；`xcrun simctl list devices booted`",
            )
        }
        val dim = imageSizeOf(local)
        session?.register(local, "shot")
        return ShotRec(local, dim?.first ?: 0, dim?.second ?: 0)
    }

    /** Android：screencap 出 PNG，尺寸读 PNG 头（设备端不打印宽高）。 */
    private fun snapshotAndroid(hdc: Hdc, session: Session?): ShotRec {
        val remote = "$TMP/shot-${Env.nowMillis()}.png"
        val r = hdc.shell("screencap -p $remote", 30_000)
        if (!r.ok) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "screencap 失败：${r.text().take(300)}",
                "确认设备已解锁；部分导入镜像需 android.permission.READ_FRAME_BUFFER",
            )
        }
        val local = session?.nextPath("shot", "png") ?: fs.createTempFile("ohd-shot-", ".png")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "pull 截图失败：${recv.text().take(300)}")
        val dim = imageSizeOf(local)
        session?.register(local, "shot")
        return ShotRec(local, dim?.first ?: 0, dim?.second ?: 0)
    }

    /** 设备上取 pid：--pid 优先，否则按包名从 ps 里找（OHOS 无 pidof）。 */
    fun resolvePid(hdc: Hdc, pid: Int?, bundle: String?): Int {
        if (pid != null && pid > 0) return pid
        if (bundle.isNullOrBlank()) {
            throw OhdError(ExitCode.USAGE, "需要 --pid <n> 或 --bundle <包名>", "示例：ohd mem --bundle com.example.app（或 --pid 1234）")
        }
        if (hdc.kind == DeviceKind.IOS_DEVICE) {
            throw OhdError(ExitCode.UNSUPPORTED, "iOS 真机不支持按包名反查 PID（devicectl 无 shell）")
        }
        if (hdc.kind == DeviceKind.IOS) {
            // 模拟器内没有 ps/pidof，只能从 launchctl 的 label 反查 PID。
            // 必须直连 spawn：launchctl 是 macOS 通用二进制，经 `sh -c` 转一手会因
            // 缺 DYLD_ROOT_PATH 而 abort（signal 6），和 log stream 是同一个坑。
            val r = hdc.run(listOf("spawn", "launchctl", "list"), 15_000)
            val hit = r.stdout.lines()
                .map { it.trim().split(Regex("\\s+")) }
                .firstOrNull { parts ->
                    parts.size >= 3 && parts[0].toIntOrNull() != null && parts[2].contains(bundle)
                }
            return hit?.get(0)?.toIntOrNull() ?: throw OhdError(
                ExitCode.ASSERT_FAILED,
                "进程不存在：$bundle",
                "应用未启动？先用 `ohd app start` 或手动拉起",
            )
        }
        val ps = hdc.shell("ps -A -o PID,NAME 2>/dev/null | grep -w '$bundle' | head -5", 15_000)
        val first = ps.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                parts.firstOrNull()?.toIntOrNull()
            }
            .firstOrNull()
        return first ?: throw OhdError(
            ExitCode.ASSERT_FAILED,
            "进程不存在：$bundle",
            "应用未启动？先用 `ohd app start` 或手动拉起",
        )
    }

    data class HomeSettleResult(
        val backs: Int,
        val atHome: Boolean,
        val matchedText: String?,
        val settleMs: Long,
    )

    /**
     * 自动化收尾：回到目标应用「首页」后静置，便于 scene_capture / 末点采样看到稳态基线。
     *
     * - **禁止** force-stop（会洗掉待观察的内存残留）。
     * - 用 Back 最多 [maxBacks] 次；布局文本命中 [homeTexts] 任一子串即视为首页。
     * - 无命中也照样静置（尽力而为）。
     */
    fun returnHomeAndSettle(
        hdc: Hdc,
        session: Session?,
        bundle: String?,
        mainAbility: String = "EntryAbility",
        settleMs: Long = 30_000,
        maxBacks: Int = 12,
        homeTexts: List<String> = listOf("多实例测试", "Compose - Skia", "Start Second Ability"),
        log: (String) -> Unit = {},
    ): HomeSettleResult {
        if (settleMs < 0) throw OhdError(ExitCode.USAGE, "settleMs 不能为负")
        val target = bundle?.takeIf { it.isNotBlank() }
        if (target != null) {
            val fg = runCatching { foregroundBundle(hdc, session) }.getOrNull()
            if (fg != null && fg != target) {
                log("收尾：前台是 $fg，aa start 拉回 $target（不 force-stop）")
                runCatching { hdc.shell("aa start -b $target -a $mainAbility", 15_000) }
                Env.sleepMillis(800)
            }
        }

        fun layoutHit(): Pair<Boolean, String?> {
            if (homeTexts.isEmpty()) return false to null
            val nodes = runCatching { captureLayout(hdc, session).flat() }.getOrNull() ?: return false to null
            for (n in nodes) {
                val text = n.text?.trim().orEmpty()
                if (text.isEmpty()) continue
                val hit = homeTexts.firstOrNull { text.contains(it) }
                if (hit != null) return true to text
            }
            return false to null
        }

        var backs = 0
        var matched: String? = null
        var atHome = false
        val (already, alreadyText) = layoutHit()
        if (already) {
            atHome = true
            matched = alreadyText
            log("收尾：已在首页（命中「$matched」）")
        } else {
            for (i in 1..maxBacks) {
                val back = ohd.core.actions.UiActions.Action(
                    ohd.core.actions.UiActions.Kind.KEY,
                    listOf("Back"),
                    "",
                    0,
                )
                runCatching { ohd.core.actions.UiActions.perform(hdc, back, 10_000) }
                backs++
                Env.sleepMillis(450)
                val (ok, text) = layoutHit()
                if (ok) {
                    atHome = true
                    matched = text
                    log("收尾：Back×$backs 后回到首页（命中「$matched」）")
                    break
                }
            }
            if (!atHome) {
                log("收尾：Back×$backs 仍未命中首页标记 ${homeTexts.joinToString("/")}，仍继续静置")
            }
        }

        if (settleMs > 0) {
            log("收尾：静置 ${settleMs}ms …")
            Env.sleepMillis(settleMs)
            log("收尾：静置结束")
        }
        return HomeSettleResult(backs = backs, atHome = atHome, matchedText = matched, settleMs = settleMs)
    }
}
