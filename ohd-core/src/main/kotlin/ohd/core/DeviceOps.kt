package ohd.core

import ohd.core.parsers.AndroidLayoutParser
import ohd.core.parsers.IosLayoutParser
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.LayoutParser
import java.nio.file.Files
import java.nio.file.Path

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
        val deadline = System.currentTimeMillis() + waitMs
        var a11yPid = ""
        while (System.currentTimeMillis() < deadline) {
            a11yPid = runCatching {
                hdc.shell("pidof accessibility", 5_000).stdout.trim()
            }.getOrDefault("")
            if (a11yPid.isNotBlank()) break
            Thread.sleep(500)
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

    data class LayoutCapture(val roots: List<LayoutNode>, val file: Path?) {
        fun flat(): List<LayoutNode> = roots.flatMap { it.flatten() }
    }

    fun captureLayout(hdc: Hdc, session: Session?, rich: Boolean = false): LayoutCapture {
        ensureTmp(hdc)
        if (hdc.kind == DeviceKind.ANDROID) return captureLayoutAndroid(hdc, session)
        if (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE) return captureLayoutIos(hdc)
        val remote = "$TMP/layout-${System.currentTimeMillis()}.json"
        val cmd = if (rich) "uitest dumpLayout -p $remote -a" else "uitest dumpLayout -p $remote"
        val dump = hdc.shell(cmd, 30_000)
        if (!dump.ok) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "dumpLayout 失败：${dump.text().take(300)}",
                "确认设备已解锁、前台有窗口，且 /system/bin/uitest 可用（ohd doctor）",
            )
        }
        val local = session?.nextPath("layout", "json")
            ?: Files.createTempFile("ohd-layout-", ".json")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) {
            throw OhdError(ExitCode.TIMEOUT, "recv layout 失败：${recv.text().take(300)}")
        }
        val roots = LayoutParser.parseFile(local)
        session?.register(local, "layout")
        return LayoutCapture(roots, local)
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
        val remote = "$TMP/layout-${System.currentTimeMillis()}.xml"
        val dump = hdc.shell("uiautomator dump --compressed $remote", 30_000)
        if (!dump.ok || !dump.text().contains("dumped to")) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "uiautomator dump 失败：${dump.text().take(300)}",
                "确认设备已解锁、前台有窗口；模拟器需开启硬件渲染",
            )
        }
        val local = session?.nextPath("layout", "xml")
            ?: Files.createTempFile("ohd-layout-", ".xml")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "pull layout 失败：${recv.text().take(300)}")
        val roots = AndroidLayoutParser.parseFile(local)
        session?.register(local, "layout")
        return LayoutCapture(roots, local)
    }

    data class ShotRec(val file: Path, val width: Int, val height: Int)

    /**
     * snapshot_display 只接受 .jpeg 后缀（实测报错 "suffix must be .jpeg"），
     * 且会在 stdout 打印 "width: N, height: M"，无需解码即可拿尺寸。
     */
    fun snapshot(hdc: Hdc, session: Session?, displayId: Int? = null, suffix: String = "jpeg"): ShotRec {
        IosSupport.requireSupported("shot", hdc.kind)
        // 模拟器截图直接落到宿主机：simctl io 自己写文件，无需 mkdir + recv
        if (hdc.kind == DeviceKind.IOS) return snapshotIos(hdc, session)
        ensureTmp(hdc)
        if (hdc.kind == DeviceKind.ANDROID) return snapshotAndroid(hdc, session)
        val remote = "$TMP/shot-${System.currentTimeMillis()}.$suffix"
        val cmd = buildString {
            append("snapshot_display")
            displayId?.let { append(" -i ").append(it) }
            append(" -f ").append(remote)
        }
        val r = hdc.shell(cmd, 30_000)
        if (!r.ok || !r.stdout.contains("width")) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "截图失败：${r.text().take(300)}",
                "设备侧快照要求 .jpeg 后缀，且需前台有窗口",
            )
        }
        val w = Regex("""width:\s*(\d+)""").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val h = Regex("""height:\s*(\d+)""").find(r.stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val local = session?.nextPath("shot", suffix)
            ?: Files.createTempFile("ohd-shot-", ".$suffix")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "recv 截图失败：${recv.text().take(300)}")
        session?.register(local, "shot")
        return ShotRec(local, w, h)
    }

    /** iOS 模拟器：`simctl io <udid> screenshot <宿主路径>`，尺寸用 ImageIO 读。 */
    private fun snapshotIos(hdc: Hdc, session: Session?): ShotRec {
        val local = session?.nextPath("shot", "png") ?: Files.createTempFile("ohd-shot-", ".png")
        val r = hdc.run(listOf("io", "screenshot", local.toAbsolutePath().toString()), 30_000)
        if (!r.ok || !Files.exists(local)) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "simctl 截图失败：${r.text().take(300)}",
                "确认模拟器已 Booted；`xcrun simctl list devices booted`",
            )
        }
        val img = runCatching { javax.imageio.ImageIO.read(local.toFile()) }.getOrNull()
        session?.register(local, "shot")
        return ShotRec(local, img?.width ?: 0, img?.height ?: 0)
    }

    /** Android：screencap 出 PNG，尺寸用 ImageIO 读（设备端不打印宽高）。 */
    private fun snapshotAndroid(hdc: Hdc, session: Session?): ShotRec {
        val remote = "$TMP/shot-${System.currentTimeMillis()}.png"
        val r = hdc.shell("screencap -p $remote", 30_000)
        if (!r.ok) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "screencap 失败：${r.text().take(300)}",
                "确认设备已解锁；部分导入镜像需 android.permission.READ_FRAME_BUFFER",
            )
        }
        val local = session?.nextPath("shot", "png") ?: Files.createTempFile("ohd-shot-", ".png")
        val recv = hdc.recv(remote, local, 60_000)
        if (!recv.ok) throw OhdError(ExitCode.TIMEOUT, "pull 截图失败：${recv.text().take(300)}")
        val dim = runCatching {
            javax.imageio.ImageIO.read(local.toFile())?.let { it.width to it.height }
        }.getOrNull()
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
                Thread.sleep(800)
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
                Thread.sleep(450)
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
            Thread.sleep(settleMs)
            log("收尾：静置结束")
        }
        return HomeSettleResult(backs = backs, atHome = atHome, matchedText = matched, settleMs = settleMs)
    }
}
