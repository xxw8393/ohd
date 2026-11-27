package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Faults
import ohd.core.Hdc
import ohd.core.Hdcs
import ohd.core.HilogStream
import ohd.core.DeviceKind
import ohd.core.PlatformCmds
import ohd.core.logCompressOf
import ohd.core.logFileSuffix
import ohd.core.logSink
import ohd.core.OhdError
import ohd.core.ProcessTrees
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.actions.LayoutCache
import ohd.core.actions.MonkeyMapGuide
import ohd.core.actions.UiActions
import ohd.core.actions.WorldMap
import ohd.core.isoTime
import ohd.core.parsers.AndroidParser
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser
import ohd.platform.fs
import kotlin.random.Random

/**
 * 随机事件注入（monkey），崩溃时把事件序列落成**可回放脚本**。
 *
 * 与普通 monkey 的关键差别：普通 monkey 只能报「崩了」，这个能报「怎么崩的」——
 * 每注入一个事件就同步记一行 `ohd replay` 能直接执行的脚本，
 * 崩了立刻把脚本 + 现场 + 故障原文一起落盘，开发拿到就能复现。
 *
 * 安全约束：
 * - 不注入 Power（会锁屏，后续事件全打空）
 * - Back 键限频（每 10 步最多 1 次），避免一路退回桌面
 * - 只点 clickable && enabled && visible 的节点，天然避开状态栏/导航栏
 */
class MonkeyCap : Capability {
    override val name = "monkey"
    override val summary = "随机事件注入：崩溃时自动生成可回放脚本 + 现场"
    override val usage = "ohd monkey [--bundle b] [--duration 10m] [--interval 400ms] [--seed N] " +
        "[--max-steps N|0] [--layout-ttl 5] [--batch 1] [--stagnate 60] [--check-every 8] [--no-stay-in-app] [--sample-every 20] " +
        "[--no-keep-a11y] [--a11y-ping-every 10] [--no-worldmap] [--avoid-text a,b] " +
        "[--home-settle 30s] [--no-home-settle] [--home-text a,b] [--no-hilog] [--tag <tag>] [--match RE] [--exclude RE] [--full] [--max-size 0] [--out <file.ohd>] [--json]"
    override val example = "ohd monkey --bundle com.tencent.compose --duration 10h --interval 0 --batch 10 --sample-every 20 --layout-ttl 20 --keep-going --avoid-text OHOS运行时开关 --tag Konan_main --exclude '(?i)vsync'"
    override val accepts = setOf(
        "batch", "bundle", "check-every", "duration", "interval", "layout-ttl", "max-steps", "keep-going", "no-stay-in-app",
        "keep-a11y", "no-keep-a11y", "a11y-ping-every", "no-worldmap", "avoid-text",

        "home-settle", "no-home-settle", "home-text",
        "hilog", "no-hilog", "tag", "match", "exclude", "full", "max-size", "out", "sample-every", "seed", "stagnate", "no-compress", "log-compress",
    )

    private data class Sample(
        val i: Int,
        val ms: Long,
        val pssKb: Long,
        val dmaBytes: Long,
        // 高水位定位：GL 纹理 / Graph 合成缓冲 / 换出量，是 mem 报表峰谷的直接来源
        val glKb: Long = 0,
        val graphKb: Long = 0,
        val swapPssKb: Long = 0,
    )

    private data class CrashPaths(val shot: String?, val tree: String?, val fault: String?, val log: String?)

    /**
     * 崩溃现场留存。长跑时每崩一次都要调一遍，所以单独成函数。
     *
     * 只抓日志（截图/节点树在循环外补最后一次）——崩溃瞬间界面往往已经白屏或跳走，
     * 逐次抓图的价值不如省下的时间。
     */
    private fun captureCrash(c: Faults.Entry, hdc: Hdc, session: Session?, bundle: String?): CrashPaths {
        var fault: String? = null
        var log: String? = null
        runCatching {
            val detailCmd = PlatformCmds.crashDetailCommand(hdc.kind, c.raw)
            val r = if (detailCmd != null) Hdcs.sh(session, hdc, detailCmd, 60_000)
                    else Hdcs.sh(session, hdc, "logcat -b crash -d -t 500", 60_000)
            if (r.ok && r.stdout.isNotBlank()) {
                session?.let { sx ->
                    val p = sx.nextPath("faultlog", "txt")
                    Session.writeText(p, r.stdout)
                    sx.register(p, "faultlog")
                    fault = p.toAbsolutePath().toString()
                }
            }
        }
        runCatching {
            val tag = bundle ?: return@runCatching
            val r = Hdcs.sh(session, hdc, "hilog -x | grep '$tag' | tail -n 2000", 60_000)
            if (r.ok && r.stdout.isNotBlank()) {
                session?.let { sx ->
                    val p = sx.nextPath("hilog", "txt")
                    Session.writeText(p, r.stdout)
                    sx.register(p, "hilog")
                    log = p.toAbsolutePath().toString()
                }
            }
        }
        return CrashPaths(null, null, fault, log)
    }

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "monkey")
        val bundle = ctx.options.str("bundle")
        val pid = DeviceOps.resolvePid(hdc, ctx.options.str("pid")?.toIntOrNull(), bundle)

        val intervalMs = parseMs(ctx.options.str("interval") ?: "800ms")
        val durationMs = parseMs(ctx.options.str("duration") ?: "10m")
        // 0（默认）= 不限。曾经的默认 100_000 在 5800 步/分下会把 --duration 10h 静默截成 17 分钟。
        val maxStepsOpt = ctx.options.int("max-steps", MonkeyPolicy.DEFAULT_MAX_STEPS)
        val maxSteps = MonkeyPolicy.resolveMaxSteps(maxStepsOpt)
        val checkEvery = ctx.options.int("check-every", 8).coerceAtLeast(1)
        // 实测 dumpLayout 固定 ~1.1s（设备端遍历 UI 树），hdc 往返只要 80ms，
        // 所以省时间只能靠少抓 layout：TTL 越大越快，但界面变化时坐标会滞后
        val layoutTtl = ctx.options.int("layout-ttl", 5).coerceAtLeast(1)
        // 压测用：一批事件拼成一条 shell 下发。设备端每 click ~300ms 是固定成本，
        // 批量只省「下发/启动」那部分（实测 364→304ms/click），但对 long run 仍是净收益
        val batch = ctx.options.int("batch", 1).coerceIn(1, 50)
        // 停滞阈值（步）：界面这么久没变就主动脱困。
        // 实测 25 分钟压测里「可点 3」卡了 2000+ 步，monkey 自己出不来。
        val stagnate = ctx.options.int("stagnate", 60).coerceAtLeast(5)
        // 应用边界：实测 monkey 会把浏览器点到外部网站（help-me-connect.com），
        // 那样测的就不是目标应用了，还会产生外部副作用。默认开启，越界即拉回。
        val stayInApp = !ctx.options.bool("no-stay-in-app") && bundle != null
        // 默认读+写世界地图：dump 累积，停滞/挑点时用 edges+item 滑次导航；--no-worldmap 关闭
        val worldMapOn = !ctx.options.bool("no-worldmap") && !bundle.isNullOrBlank()
        // 逗号分隔：可点节点 text/description/label 含子串则跳过（如「OHOS运行时开关」）。
        // 挡不住盲点；硬隔离请 `-Pohos.showRuntimeTuning=false`（入口可见不可点，地图不变）。
        val avoidTexts = ctx.options.str("avoid-text")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (avoidTexts.isNotEmpty()) {
            ctx.out.note("AVOID_TEXT", "info", "跳过可点: ${avoidTexts.joinToString(" | ")}")
        }
        val mainAbility = runCatching {
            hdc.shell("bm dump -n $bundle", 10_000).stdout
                .lineSequence().firstOrNull { it.contains("EntryAbility") }
                ?.let { Regex("""([A-Za-z0-9_.]+Ability)""").find(it)?.groupValues?.get(1) }
        }.getOrNull() ?: "EntryAbility"
        val sampleEvery = ctx.options.int("sample-every", 20).coerceAtLeast(1)
        val seed = ctx.options.str("seed")?.toLongOrNull() ?: System.currentTimeMillis()
        val rnd = Random(seed)
        // OHOS default ON (DevEco parity): uitest+accessibility so Compose a11y hot path stays open.
        // Opt out with --no-keep-a11y. Bare --keep-a11y remains accepted (no-op when already default).
        val keepA11y =
            hdc.kind == DeviceKind.OHOS && !ctx.options.bool("no-keep-a11y")
        val a11yPingEvery = ctx.options.int("a11y-ping-every", 10).coerceAtLeast(1)
        var a11yPings = 0
        var a11yPingFail = 0
        if (keepA11y) {
            val status = DeviceOps.ensureOhosA11yClient(hdc)
            if (status.startsWith("fail")) {
                ctx.out.note("KEEP_A11Y", "warn", status)
            } else {
                ctx.out.note("KEEP_A11Y", "info", "uitest/accessibility engaged ($status); ping dump every $a11yPingEvery steps")
            }
        }

        // 根节点 bounds 通常就是内容区（如 [0,137][1280,2832]），137 即状态栏高度。
        // 不按它过滤就会像上一轮那样连点几十次 y≈70-110，把人一路点到通知中心。
        // 安全区从 layout 动态推导，而不是写死比例：
        // layout 顶层一般有 3 个 root —— 全屏根、应用窗口、底部导航条（如 [288,2727][992,2832] 里含返回/Home 按钮）。
        // 取「高度接近全屏但没占满」的那个当应用窗口，它的 top/bottom 就是真正可点区域。
        // 写死 5% 会与真实窗口差几十像素，恰好落在导航栏边缘上。
        // 注意 parse() 对顶层 JsonObject 只返回单个包裹节点，roots 里拿不到 4 个顶层 root，
        // 必须 flat() 展开才能看到应用窗口（[84,137][1280,2727]）与底部导航条（[288,2727][992,2832]）。
        val rootRects = runCatching {
            DeviceOps.captureLayout(hdc, session).flat().mapNotNull { it.bounds }
        }.getOrDefault(emptyList())
        val fullRect = rootRects.maxByOrNull { it.width * it.height }
        val screenW = fullRect?.right ?: 1280
        val screenH = fullRect?.bottom ?: 2832
        val appRect = rootRects
            .filter { it.bottom < screenH && it.height > screenH / 2 }
            .maxByOrNull { it.height }
        // 兜底：拿不到应用窗口时按屏高 5% 留白（1280x2832 → 141px，盖住 137 的状态栏）
        val safeTop = appRect?.top?.takeIf { it > 0 } ?: (screenH * 5 / 100)
        // 底部导航栏/手势区必须排除：实测 tap 落在 2780 一带、swipe 滑到 2831 都会触发系统手势，
        // 表现为「monkey 反复跑出应用」（一次 90 分钟压测里误触发 129 次，而 demo 本身没有任何外链）。
        val safeBottom = appRect?.bottom?.takeIf { it < screenH } ?: (screenH - maxOf(120, screenH * 5 / 100))

        if (!ctx.out.jsonMode) {
            ctx.out.kv(
                listOf(
                    "PID" to pid.toString(),
                    "时长上限" to humanDuration(durationMs),
                    "事件间隔" to humanDuration(intervalMs),
                    "随机种子" to seed.toString(),
                    "屏幕" to "${screenW}x$screenH",
                    "安全区" to "y $safeTop..$safeBottom（${if (appRect != null) "布局推导" else "比例兜底"}）",
                    "崩溃检查" to "每 $checkEvery 步",
                    "layout 缓存" to "基准 $layoutTtl 步，界面稳定时自适应拉长至 ${layoutTtl * 4} 步",
                    "批量下发" to if (batch > 1) "$batch 个事件/次（压测模式）" else "逐条",
                    "采样" to "每 $sampleEvery 步（PSS/DMA）",
                    "步数上限" to if (maxStepsOpt <= 0) "不限" else maxStepsOpt.toString(),
                    "无障碍保活" to if (keepA11y) "开（对齐 DevEco：uitest daemon + dump ping /$a11yPingEvery 步）" else "关",
                    "世界地图" to
                        if (worldMapOn) {
                            "开（读图导航+累积 ~/.ohd/worldmap）"
                        } else {
                            "关"
                        },
                ),
            )
            println()
        }
        MonkeyPolicy.startWarning(maxStepsOpt, intervalMs, durationMs, batch)?.let {
            ctx.out.note("MAX_STEPS_CAP", "warn", it)
        }

        // 默认全程抓日志：崩溃现场只留 tail 2000 行，几小时前的事故根本对不上。
        // --no-hilog 关闭，回到「只在崩溃时留现场」的旧行为。
        var hilogProc: Process? = null
        if (!ctx.options.bool("no-hilog")) {
            val maxBytes = try {
                LogWatchdog.parseSize(ctx.options.str("max-size", "0"))
            } catch (_: IllegalArgumentException) {
                throw OhdError(
                    ExitCode.USAGE,
                    "参数 --max-size 无法解析：${ctx.options.str("max-size")}",
                    "例如 --max-size 2G，0 表示不限",
                )
            }
            val compress = logCompressOf(ctx)
            val f = session?.nextPath("hilog", logFileSuffix(compress))
            if (f != null) {
                // 设备端按 tag 过滤；宿主端做 compat 精简 + 关键字过滤（都不指定则全量原样）
                val tags = HilogStream.parseTags(ctx.options.str("tag"))
                val match = ctx.options.str("match")?.takeIf { it.isNotBlank() }?.let {
                    runCatching { Regex(it) }.getOrElse {
                        throw OhdError(ExitCode.USAGE, "参数 --match 不是合法正则：$it")
                    }
                }
                val exclude = ctx.options.str("exclude")?.takeIf { it.isNotBlank() }?.let {
                    runCatching { Regex(it) }.getOrElse {
                        throw OhdError(ExitCode.USAGE, "参数 --exclude 不是合法正则：$it")
                    }
                }
                val compat = !ctx.options.bool("full")
                hilogProc = runCatching {
                    HilogStream.pump(hdc.shellArgv(PlatformCmds.logCommand(hdc.kind, tags)), logSink(f, compress), match, exclude, compat)
                }.getOrNull()
                hilogProc?.let { p ->
                    LogWatchdog.spawn(p.pid(), f, maxBytes)
                    session.register(f, "hilog")
                    // 本进程被 Ctrl-C / 杀掉时不能留孤儿 hdc 一直写盘
                    Runtime.getRuntime().addShutdownHook(Thread { ProcessTrees.kill(p.pid()) })
                    val tagText = ctx.options.str("tag")?.trim().orEmpty()
                    val scopeText = if (tagText.isEmpty()) "全部 tag" else "tag: $tagText"
                    val shape = if (compat) "compat" else "完整字段"
                    val kw = ctx.options.str("match")?.takeIf { it.isNotBlank() }?.let { "，关键字: $it" }.orEmpty() +
                        (ctx.options.str("exclude")?.takeIf { it.isNotBlank() }?.let { "，排除: $it" } ?: "")
                    ctx.out.note("HILOG_ON", "info", "全程日志 → $f（$scopeText，$shape$kw，上限 ${LogWatchdog.format(maxBytes)}）")
                }
            }
            if (hilogProc == null) ctx.out.note("HILOG_OFF", "warn", "日志流启动失败，仅保留崩溃现场")
        }

        var before = Faults.list(session, hdc)
        val replay = ReplayCap()
        val events = mutableListOf<String>()
        val samples = mutableListOf<Sample>()
        val deadline = System.currentTimeMillis() + durationMs
        var backCooldown = 0
        var hit: Faults.Entry? = null
        // 长跑时崩溃不该终止整轮：一次留完现场就重启继续，一晚能抓多个
        val keepGoing = ctx.options.bool("keep-going")
        val crashes = mutableListOf<Faults.Entry>()
        var crashPaths: CrashPaths? = null
        var step = 0
        var lastAction = ""
        // 每步都抓 layout 会拖到 ~3s/步；缓存 3 步足够，靠坐标抖动保持多样性
        var cachedClickables: List<ohd.core.parsers.LayoutNode> = emptyList()
        // 自适应 TTL：界面稳定就把缓存拉长（最多 4×base），一变就回到 base。
        // 比固定 TTL 两头都占：稳定界面几乎零成本，跳转时立刻重抓，不会点废坐标。
        var effectiveTtl = layoutTtl
        var refreshAt = 0
        var lastFingerprint = 0
        var lastChangeStep = 0
        var escapes = 0
        var outOfApp = 0
        var sampleFail = 0
        // 失败原因要分类：只有「超时」和「pss 取不到」的占比不同，处置方式也不同
        val sampleFailReasons = linkedMapOf<String, Int>()
        var lastSampleStep = 0
        var lastCheckStep = 0
        var lastFgCheck = 0
        var leavePending = false
        var skipped = 0
        var pendingMapEdge: MonkeyMapGuide.PendingEdge? = null
        val recentMapLabels = mutableListOf<String>()
        var mapGuidedSteps = 0
        var mapEscapeSteps = 0
        fun rememberMapLabel(lab: String?) {
            if (lab.isNullOrBlank()) return
            recentMapLabels.remove(lab)
            recentMapLabels.add(lab)
            while (recentMapLabels.size > 8) recentMapLabels.removeAt(0)
        }

        // 长时压测必须增量落盘：数据全堆在内存里，一旦进程被中断就全丢，3 小时白跑。
        // 每步/每次采样都 flush，宁可多几次 I/O，也要保证「随时被杀都能拿到已跑部分」。
        val scriptPath = (ctx.options.str("out")?.let { Paths.get(it) } ?: session?.nextPath("monkey", "ohd"))
        val csvPath = session?.nextPath("monkey-samples", "csv")
        val scriptWriter = scriptPath?.let { p ->
            Files.createDirectories(p.toAbsolutePath().parent)
            Files.newBufferedWriter(
                p, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE
            ).also {
                it.write("# ohd monkey 记录 seed=$seed（增量写入，每行一步）\n")
                it.write("# 崩溃回放：ohd replay <本文件>\n")
                it.flush()
            }
        }
        val csvWriter = csvPath?.let { p ->
            Files.newBufferedWriter(
                p, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE
            ).also {
                it.write("index,time,pss_kb,dma_bytes,gl_kb,graph_kb,swap_pss_kb\n")
                it.flush()
            }
        }
        fun appendEvent(line: String) {
            events.add(line)
            scriptWriter?.run { write(line); write("\n"); flush() }
        }

        // 前台已经不是目标应用时：先让退后台发生，再拉回。此间不点、不滑。
        fun pullBackIfOutside(): Boolean {
            val target = bundle?.takeIf { stayInApp } ?: return false
            val fg = runCatching { DeviceOps.foregroundBundle(hdc, session) }.getOrNull() ?: return false
            if (fg == target) return false
            outOfApp++
            val dwell = MonkeyPolicy.BACKGROUND_DWELL_MS
            appendEvent("# 越界到 $fg，退后台 ${dwell}ms 后拉回")
            if (!ctx.out.jsonMode) {
                ctx.out.info("! 前台是 $fg，保留退后台 ${dwell}ms 后拉回 $target（第 $outOfApp 次）")
            }
            cachedClickables = emptyList()
            Thread.sleep(dwell)
            runCatching { hdc.shell("aa start -b $target -a $mainAbility", 15_000) }
            repeat(5) {
                Thread.sleep(400)
                val nowFg = runCatching { DeviceOps.foregroundBundle(hdc, session) }.getOrNull()
                if (nowFg == target) return true
            }
            return true
        }

        while (step < maxSteps && System.currentTimeMillis() < deadline) {
            if (leavePending) {
                leavePending = false
                lastFgCheck = step
                if (pullBackIfOutside()) continue
            }

            val clickables = if (cachedClickables.isEmpty() || step >= refreshAt) {
                val allFlat = runCatching {
                    DeviceOps.captureLayout(hdc, session).flat()
                }.getOrDefault(emptyList())
                if (worldMapOn && allFlat.isNotEmpty()) {
                    runCatching {
                        LayoutCache.put(hdc, allFlat)
                        val page = WorldMap.observePage(bundle!!, allFlat)
                        val from = pendingMapEdge
                        if (from != null && from.fromPageId != page.id) {
                            WorldMap.noteEdge(
                                bundle!!,
                                from.fromPageId,
                                page.id,
                                "tap",
                                from.label,
                            )
                        }
                        pendingMapEdge = null
                    }
                }
                val target = if (stayInApp) bundle else null
                val fresh = allFlat.filter {
                    val b = it.bounds
                    it.clickable && it.enabled && it.visible && b != null &&
                        b.width > 0 && b.height > 0 &&
                        b.top >= safeTop && b.bottom <= safeBottom &&
                        MonkeyPolicy.belongsToTarget(it.bundleName, target) &&
                        !MonkeyPolicy.matchesAvoidText(it, avoidTexts)
                }
                // 指纹只看「有多少个可点、大致在什么位置」，与文本变化无关
                val fp = fresh.size * 1_000_003 + fresh.sumOf { (it.bounds?.centerX ?: 0) + (it.bounds?.centerY ?: 0) }
                if (fp != lastFingerprint) {
                    lastFingerprint = fp
                    lastChangeStep = step
                    effectiveTtl = layoutTtl
                    escapes = 0
                } else {
                    // keep-a11y: do not stretch TTL — rare dumps drop Compose a11y engagement.
                    effectiveTtl = if (keepA11y) {
                        layoutTtl
                    } else {
                        (effectiveTtl * 2).coerceAtMost(layoutTtl * 4)
                    }
                    // 界面长时间不变 → 先试地图导航，再 Back/Home
                    if (step - lastChangeStep >= stagnate) {
                        val guided =
                            if (worldMapOn) {
                                MonkeyMapGuide.stagnateEscape(
                                    bundle = bundle!!,
                                    clickables = fresh,
                                    w = screenW,
                                    h = screenH,
                                    safeTop = safeTop,
                                    safeBottom = safeBottom,
                                    rnd = rnd,
                                    recentLabels = recentMapLabels.toSet(),
                                )
                            } else {
                                null
                            }
                        if (guided != null && escapes % 3 < 2) {
                            val cmds =
                                guided.mapNotNull { line ->
                                    val act = runCatching { UiActions.parseScript(line).first() }.getOrNull()
                                    act?.let { UiActions.shellCommand(it, hdc.kind) }
                                }
                            if (cmds.isNotEmpty()) {
                                runCatching { hdc.shell(cmds.joinToString("; "), 60_000) }
                                guided.forEach { appendEvent(it) }
                                step += guided.size
                                mapEscapeSteps += guided.size
                                lastAction = guided.last()
                                rememberMapLabel(
                                    MonkeyMapGuide.labelNearTap(guided.first(), fresh),
                                )
                                if (guided.first().startsWith("tap ")) {
                                    val lab = MonkeyMapGuide.labelNearTap(guided.first(), fresh)
                                    val fromId = WorldMap.load(bundle!!).currentPageId
                                    if (lab != null && fromId != null) {
                                        pendingMapEdge = MonkeyMapGuide.PendingEdge(fromId, lab)
                                    }
                                }
                                if (guided.any { it.startsWith("key Back") } && worldMapOn) {
                                    LayoutCache.noteBack(hdc)
                                    WorldMap.noteBack(bundle!!)
                                }
                                escapes++
                                lastChangeStep = step
                                effectiveTtl = layoutTtl
                                if (!ctx.out.jsonMode) {
                                    ctx.out.info(
                                        "! 停滞 $stagnate 步，地图脱困「$lastAction」（第 $escapes 次）",
                                    )
                                }
                                leavePending = guided.any { MonkeyPolicy.endsBatch(it, screenH) }
                                cachedClickables = emptyList()
                                continue
                            }
                        }
                        val key = if (escapes % 3 < 2) "Back" else "Home"
                        runCatching { hdc.shell("uitest uiInput keyEvent $key", 15_000) }
                        if (key == "Back" && worldMapOn) {
                            LayoutCache.noteBack(hdc)
                            WorldMap.noteBack(bundle!!)
                        }
                        appendEvent("key $key")
                        step += 1 // 脱困也是一次真实注入，计入步数；采样/检查已改用距离判断，不受影响
                        escapes++
                        lastChangeStep = step
                        effectiveTtl = layoutTtl
                        if (!ctx.out.jsonMode) ctx.out.info("! 停滞 $stagnate 步，注入 $key 脱困（第 $escapes 次）")
                        leavePending = true
                        cachedClickables = emptyList()
                        continue
                    }
                }
                refreshAt = step + effectiveTtl
                cachedClickables = fresh
                fresh
            } else {
                cachedClickables
            }

            // 拿不到 UI 树时 clickables 恒空；不能因此每步都查前台，
            // 否则 pullBackIfOutside 每轮都 continue，一个动作也发不出去。
            val noTree = clickables.isEmpty() &&
                hdc.kind != DeviceKind.IOS && hdc.kind != DeviceKind.IOS_DEVICE
            if (stayInApp && (noTree || step - lastFgCheck >= checkEvery)) {
                lastFgCheck = step
                if (pullBackIfOutside()) continue
            }

            // 生成一批事件，拼成一条 shell 下发（batch=1 时等价于逐条）。
            // Home / Back / 底部上滑留在本批末尾，后面的点击等确认还在目标应用里再发。
            val lines = mutableListOf<String>()
            val cmds = mutableListOf<String>()
            val acts = mutableListOf<UiActions.Action>()
            var openedLeave = false
            var picked = 0
            while (picked < batch && !openedLeave) {
                picked++
                val mapLine =
                    if (worldMapOn && rnd.nextInt(100) < 55) {
                        MonkeyMapGuide.biasedAction(
                            bundle = bundle!!,
                            clickables = clickables,
                            w = screenW,
                            h = screenH,
                            safeTop = safeTop,
                            safeBottom = safeBottom,
                            rnd = rnd,
                        )
                    } else {
                        null
                    }
                val line =
                    mapLine
                        ?: pick(rnd, clickables, screenW, screenH, safeTop, safeBottom, backCooldown)
                if (mapLine != null) mapGuidedSteps++
                backCooldown = if (line.startsWith("key ")) 10 else (backCooldown - 1).coerceAtLeast(0)
                if (line.isNotBlank()) {
                    val act = runCatching { UiActions.parseScript(line).first() }.getOrNull()
                    // iOS 没有可拼接的 shell 命令：只要动作能落到注入后端就收下
                    val injectable = act != null && (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE)
                    val cmd = act?.let { UiActions.shellCommand(it, hdc.kind) }
                    if (cmd != null || injectable) {
                        lines.add(line)
                        acts.add(act!!)
                        cmd?.let { cmds.add(it) }
                        if (line.startsWith("tap ") && worldMapOn) {
                            val lab = MonkeyMapGuide.labelNearTap(line, clickables)
                            rememberMapLabel(lab)
                            val fromId = WorldMap.load(bundle!!).currentPageId
                            if (lab != null && fromId != null) {
                                pendingMapEdge = MonkeyMapGuide.PendingEdge(fromId, lab)
                            }
                        }
                        if (MonkeyPolicy.endsBatch(line, screenH)) openedLeave = true
                    }
                }
            }

            // iOS 上 shellCommand 恒为 null，cmds 会一直是空的；
            // 判定必须看 acts，否则每批都被当成「没有动作」直接跳过。
            if (cmds.isEmpty() && acts.isEmpty()) {
                skipped += batch
                Thread.sleep(intervalMs)
                continue
            }

            if (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE) {
                // 逐条注入：idb 不支持把多条手势拼成一次调用
                acts.forEach { a -> runCatching { UiActions.perform(hdc, a, 20_000) } }
            } else {
                runCatching { hdc.shell(cmds.joinToString("; "), 120_000) }
            }
            step += lines.size
            lines.forEach { appendEvent(it) }
            lastAction = lines.last()
            if (openedLeave) leavePending = true

            if (!ctx.out.jsonMode && step / 10 != (step - lines.size) / 10) {
                ctx.out.info("[$step] $lastAction  (可点 ${clickables.size})")
            }

            // keep-a11y: between cached layout refreshes, still dump so Find* keeps a11y open.
            if (keepA11y && step > 0 && step % a11yPingEvery == 0) {
                a11yPings++
                if (a11yPings % 5 == 1) {
                    DeviceOps.ensureOhosA11yClient(hdc, waitMs = 3_000)
                }
                if (!DeviceOps.pingOhosA11yDump(hdc)) a11yPingFail++
            }

            // 崩溃检查：故障列表要打 hidumper，不能每步都查
            if (step - lastCheckStep >= checkEvery) {
                lastCheckStep = step
                val latest = Faults.list(session, hdc)
                val fresh = Faults.diff(before, latest)
                if (fresh.isNotEmpty()) {
                    val freshHit = fresh.first()
                    crashes += freshHit
                    if (hit == null) hit = freshHit
                    // 已开全程日志时不再单独 tail 2000：两处都抓会多一次 60s 超时风险
                    val saved = captureCrash(freshHit, hdc, session, if (hilogProc == null) bundle else null)
                    if (crashPaths == null) crashPaths = saved
                    appendEvent("# 崩溃 #${crashes.size}：${freshHit.raw}")
                    if (!ctx.out.jsonMode) {
                        ctx.out.info("✗ 崩溃 #${crashes.size}：${freshHit.kind} ${freshHit.raw}")
                    }
                    // 不留现场就重启等于白跑；留完继续，才能一晚上抓多个崩溃
                    // 更新基线：不更新的话同一个崩溃会被后续每轮检查反复检出
                    before = latest
                    if (!keepGoing) break
                    runCatching { hdc.shell("aa force-stop $bundle", 15_000) }
                    runCatching { hdc.shell("aa start -b $bundle -a $mainAbility", 15_000) }
                    Thread.sleep(2_000)
                    cachedClickables = emptyList()
                    continue
                }
            }

            if (step - lastSampleStep >= sampleEvery) {
                lastSampleStep = step
                // 越界拉回/崩溃重启都会换 pid；蒙眼用启动时的 pid，hidumper 会一直返回空，
                // CSV 里就写出一串 0——看起来像「内存归零」，实际是采样对象已经不存在。
                val livePid = if (bundle != null) {
                    runCatching {
                        Hdcs.sh(session, hdc, "pidof $bundle", 8_000).stdout.trim()
                            .split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                    }.getOrNull() ?: pid
                } else pid

                val sampleErr = runCatching {
                    val r = Hdcs.sh(session, hdc, PlatformCmds.memCommand(hdc.kind, livePid), 25_000)
                    // 高步频下取内存抢不到设备是常态，超时和「返回空」要分开记
                    if (!r.ok) error(if (r.timedOut) "取样超时" else "取样失败")
                    val mem = if (hdc.kind == DeviceKind.ANDROID) AndroidParser.memReport(r.stdout)
                              else MemParser.parse(r.stdout)
                    // pss 为 0 时按失败处理：真实进程不可能占 0KB，写 0 会污染趋势判断
                    if (mem.pssKb <= 0) error("pss 不可用")
                    val dma = runCatching {
                        val d = Hdcs.sh(session, hdc, "cat /proc/$livePid/mm_dmabuf_info", 15_000)
                        if (d.ok) DmabufParser.parse(d.text()).totalBytes else -1L
                    }.getOrDefault(-1L)
                    samples.add(
                        Sample(step, System.currentTimeMillis(), mem.pssKb, dma, mem.glKb, mem.graphKb, mem.swapPssKb)
                    )
                    csvWriter?.run {
                        val smp = samples.last()
                        write(
                            "${smp.i},${isoTime(smp.ms)},${smp.pssKb},${smp.dmaBytes}," +
                                "${smp.glKb},${smp.graphKb},${smp.swapPssKb}\n"
                        )
                        flush()
                    }
                }.exceptionOrNull()
                if (sampleErr != null) {
                    sampleFail++
                    val kind = SampleHealth.kindOf(sampleErr.message)
                    sampleFailReasons[kind] = (sampleFailReasons[kind] ?: 0) + 1
                }
            }

            Thread.sleep(intervalMs)
        }

        // 收尾：回首页 + 静置（默认 30s）。并行 scene_capture 仍在采，能看到稳态基线。
        // --no-home-settle / --home-settle 0 关闭；禁止 force-stop。
        val homeSettleMs = when {
            ctx.options.bool("no-home-settle") -> 0L
            else -> parseMs(ctx.options.str("home-settle") ?: "30s")
        }
        if (homeSettleMs > 0 && !bundle.isNullOrBlank()) {
            val markers = (ctx.options.str("home-text")
                ?: "多实例测试,Compose - Skia,Start Second Ability")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (!ctx.out.jsonMode) {
                ctx.out.info("收尾：回首页并静置 ${humanDuration(homeSettleMs)} …")
            }
            val settle = DeviceOps.returnHomeAndSettle(
                hdc = hdc,
                session = session,
                bundle = bundle,
                mainAbility = mainAbility,
                settleMs = homeSettleMs,
                homeTexts = markers,
                log = { msg -> if (!ctx.out.jsonMode) ctx.out.info(msg) },
            )
            appendEvent(
                "# home-settle backs=${settle.backs} atHome=${settle.atHome} " +
                    "matched=${settle.matchedText ?: "-"} settleMs=$homeSettleMs",
            )
            // 静置后再采一点，写入 csv 末行（稳态对照）
            runCatching {
                val livePid = runCatching {
                    Hdcs.sh(session, hdc, "pidof $bundle", 8_000).stdout.trim()
                        .split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                }.getOrNull() ?: pid
                val r = Hdcs.sh(session, hdc, PlatformCmds.memCommand(hdc.kind, livePid), 25_000)
                if (!r.ok) error("settle sample failed")
                val mem = if (hdc.kind == DeviceKind.ANDROID) AndroidParser.memReport(r.stdout)
                          else MemParser.parse(r.stdout)
                if (mem.pssKb <= 0) error("settle pss unavailable")
                val s = Sample(
                    step.coerceAtLeast(1), System.currentTimeMillis(), mem.pssKb, -1L,
                    mem.glKb, mem.graphKb, mem.swapPssKb,
                )
                samples.add(s)
                csvWriter?.run {
                    write("${s.i},${isoTime(s.ms)},${s.pssKb},${s.dmaBytes},${s.glKb},${s.graphKb},${s.swapPssKb}\n")
                    flush()
                }
            }.onFailure { err ->
                if (!ctx.out.jsonMode) ctx.out.info("收尾采样跳过：${err.message}")
            }
        }

        // 数据在循环里已经逐条落盘，这里只负责收尾与登记
        runCatching { scriptWriter?.close() }
        hilogProc?.let { p -> ProcessTrees.kill(p.pid()) }
        runCatching { csvWriter?.close() }
        if (scriptPath != null) session?.register(scriptPath, "script")
        if (csvPath != null && samples.isNotEmpty()) session?.register(csvPath, "watch-csv")

        // 崩溃现场：日志已在循环内逐次留存；这里补最后一次的截图与节点树
        var shotPath: String? = crashPaths?.shot
        var treePath: String? = crashPaths?.tree
        val faultPath: String? = crashPaths?.fault
        val logPath: String? = crashPaths?.log
        session?.verdict = if (hit != null) "CRASH" else "OK"
        if (hit != null && shotPath == null) {
            // snapshot / captureLayout 本身就把产物落到了会话目录，直接用返回的路径
            runCatching {
                shotPath = DeviceOps.snapshot(hdc, session).file.toAbsolutePath().toString()
            }
            runCatching {
                treePath = DeviceOps.captureLayout(hdc, session, rich = true).file?.toAbsolutePath()?.toString()
            }
        }

        val pssFirst = samples.firstOrNull()?.pssKb
        val pssLast = samples.lastOrNull()?.pssKb
        val now = System.currentTimeMillis()
        val cutShort = MonkeyPolicy.durationCutShort(maxStepsOpt, step, now, deadline)
        val health = SampleHealth.assess(samples.size, sampleFailReasons)
        val stoppedBy = when {
            cutShort -> "max-steps"
            hit != null && !keepGoing -> "crash"
            else -> "duration"
        }
        fun emitLimitNotes() {
            if (cutShort) ctx.out.note("MAX_STEPS_HIT", "warn", MonkeyPolicy.hitMessage(maxStepsOpt))
            if (health != null) ctx.out.note("SAMPLE_UNRELIABLE", "warn", health.message)
        }

        if (ctx.out.jsonMode) {
            emitLimitNotes()
            val data = buildJsonObject {
                put("schema", "ohd-monkey/1")
                put("pid", pid)
                put("seed", seed)
                put("steps", step)
                put("skipped", skipped)
                put("verdict", if (hit != null) "CRASH_FOUND" else "NO_CRASH")
                put("crashCount", crashes.size)
                put("crashes", buildJsonArray {
                    crashes.forEach { c ->
                        add(buildJsonObject { put("kind", c.kind); put("module", c.module); put("name", c.raw) })
                    }
                })
                put("lastAction", lastAction)
                put("script", scriptPath?.toAbsolutePath()?.toString() ?: "")
                put(
                    "fault",
                    hit?.let {
                        buildJsonObject { put("kind", it.kind); put("module", it.module); put("name", it.raw) }
                    } ?: kotlinx.serialization.json.JsonNull,
                )
                put("shot", shotPath ?: "")
                put("tree", treePath ?: "")
                put("faultlog", faultPath ?: "")
                put("hilog", logPath ?: "")
                put("pssFirstKb", pssFirst ?: -1)
                put("pssLastKb", pssLast ?: -1)
                put("samples", samples.size)
                put("sampleFail", sampleFail)
                put("sampleFailReasons", buildJsonObject {
                    sampleFailReasons.forEach { (k, v) -> put(k, v) }
                })
                put(
                    "sampleFailRatio",
                    if (samples.size + sampleFail == 0) 0.0
                    else sampleFail.toDouble() / (samples.size + sampleFail),
                )
                put("sampleReliable", health == null)
                put("maxSteps", if (maxStepsOpt <= 0) 0 else maxStepsOpt)
                put("stoppedBy", stoppedBy)
                put("escapes", escapes)
                put("mapGuidedSteps", mapGuidedSteps)
                put("mapEscapeSteps", mapEscapeSteps)
                put("worldMap", worldMapOn)
                put("outOfApp", outOfApp)
                put("keepA11y", keepA11y)
                put("a11yPings", a11yPings)
                put("a11yPingFail", a11yPingFail)
                put("events", buildJsonArray { events.forEach { add(JsonPrimitive(it)) } })
            }
            ctx.out.finish("monkey", hit == null, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }

        println()
        ctx.out.kv(
            listOfNotNull(
                "注入事件" to "$step 步" + (if (skipped > 0) "（跳过 $skipped）" else ""),
                "崩溃次数" to (if (crashes.isEmpty()) "0" else "${crashes.size}（已逐次留存现场）"),
                "步频" to "%.1f 步/分（%.2f 秒/步）".format(
                    step * 60.0 / ((System.currentTimeMillis() - started).coerceAtLeast(1) / 1000.0),
                    (System.currentTimeMillis() - started) / 1000.0 / step.coerceAtLeast(1),
                ),
                "最后动作" to lastAction.ifEmpty { "-" },
                "采样" to sampleSummary(samples.size, sampleFail, sampleFailReasons),
                "脱困" to if (escapes > 0) "$escapes 次（停滞阈值 $stagnate 步）" else "未触发",
                if (worldMapOn) {
                    "地图导航" to "偏置 $mapGuidedSteps 步 / 脱困 $mapEscapeSteps 步"
                } else {
                    null
                },
                if (keepA11y) "无障碍保活" to "ping $a11yPings（失败 $a11yPingFail）" else null,
                "应用边界" to if (stayInApp) "$bundle（越界拉回 $outOfApp 次）" else "不限制",
                pssFirst?.let {
                    val text = "${humanBytes(it * 1024)} → ${humanBytes((pssLast ?: it) * 1024)}"
                    "PSS" to if (health != null) "$text（采样过稀，不可信）" else text
                },
                samples.maxOfOrNull { it.glKb + it.graphKb }?.takeIf { it > 0 }?.let {
                    "GL+Graph 峰值" to humanBytes(it * 1024)
                },
                "事件脚本" to (scriptPath?.toAbsolutePath()?.toString() ?: "-"),
            ),
        )
        println()
        emitLimitNotes()

        if (hit == null) {
            ctx.out.info("未发现新故障")
            ctx.out.info("回放本次随机序列：ohd replay ${scriptPath?.toAbsolutePath() ?: "<脚本>"}")
        } else {
            ctx.out.note("CRASH_FOUND", "error", "捕获新故障：${hit.raw}")
            ctx.out.info("类型 ${hit.kind} / 模块 ${hit.module}")
            println()
            ctx.out.info("现场已保存：")
            shotPath?.let { ctx.out.info("  截图    $it") }
            treePath?.let { ctx.out.info("  节点树  $it") }
            faultPath?.let { ctx.out.info("  崩溃日志 $it") }
            logPath?.let { ctx.out.info("  应用日志 $it") }
            ctx.out.info("  脚本    ${scriptPath?.toAbsolutePath() ?: "-"}")
            println()
            ctx.out.info("复现三步：")
            ctx.out.info("  1) ohd replay ${scriptPath?.toAbsolutePath() ?: "<脚本>"}")
            ctx.out.info("  2) ohd bundle   （把会话打包成证据包）")
            ctx.out.info("  3) ohd report   （生成缺陷单）")
        }
        return ExitCode.OK
    }

    private fun sampleSummary(ok: Int, fail: Int, reasons: Map<String, Int>): String = buildString {
        append("$ok 个")
        if (fail > 0) {
            append("（失败 $fail 次")
            if (reasons.isNotEmpty()) {
                append("：")
                append(reasons.entries.joinToString("，") { "${it.key} ${it.value}" })
            }
            append("）")
        }
    }

    /** 挑一个动作并返回它的 ohd 脚本行；返回空串表示这一步跳过 */
    private fun pick(
        rnd: Random,
        clickables: List<ohd.core.parsers.LayoutNode>,
        w: Int,
        h: Int,
        safeTop: Int,
        safeBottom: Int,
        backCooldown: Int,
    ): String {
        val r = rnd.nextInt(100)
        // 安全区内的随机 y（留 60px 余量，避免压着边界）
        val yLo = (safeTop + 60).coerceAtMost(h - 200)
        val yHi = (safeBottom - 60).coerceAtLeast(yLo + 100)
        fun anyX(): Int = rnd.nextInt((w / 12).coerceAtLeast(10), (w * 11 / 12).coerceAtLeast(w / 12 + 10))
        fun anyY(): Int = rnd.nextInt(yLo, yHi)
        return when {
            // 60%：点一个可点节点的中心附近（真人不会点在正中心，±12px 抖动）
            r < 60 && clickables.isNotEmpty() -> {
                val b = clickables[rnd.nextInt(clickables.size)].bounds ?: return ""
                val x = (b.centerX + rnd.nextInt(-12, 13)).coerceIn(1, w - 1)
                val y = (b.centerY + rnd.nextInt(-12, 13)).coerceIn(safeTop + 1, safeBottom - 1)
                "tap $x $y"
            }
            // 15%：盲点安全区内任意坐标——桌面只有 6 个可点节点时，光靠节点永远走不出新路径
            r < 75 -> "tap ${anyX()} ${anyY()}"
            // 20%：随机方向滑动，起终点都限制在安全区
            r < 95 -> {
                val x = anyX()
                val y = anyY()
                val dist = rnd.nextInt(200, 600)
                val (dx, dy) = when (rnd.nextInt(4)) {
                    0 -> 0 to -dist
                    1 -> 0 to dist
                    2 -> -dist to 0
                    else -> dist to 0
                }
                val x2 = (x + dx).coerceIn(w / 12, w * 11 / 12)  // 贴边会触发边缘返回手势
                val y2 = (y + dy).coerceIn(safeTop + 1, safeBottom - 1)
                // 起点也收边，避免从上一步落在边缘的坐标继续滑出去
                "swipe $x $y $x2 $y2 800"
            }
            // 4%：返回键，带冷却避免一路退回桌面
            r < 99 && backCooldown <= 0 -> "key Back"
            else -> ""
        }
    }

    private fun parseMs(s: String): Long {
        val t = s.trim().lowercase()
        return when {
            t.endsWith("ms") -> t.dropLast(2).toLongOrNull() ?: 1000
            t.endsWith("s") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 1000).toLong()
            t.endsWith("m") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 60_000).toLong()
            t.endsWith("h") -> ((t.dropLast(1).toDoubleOrNull() ?: 1.0) * 3_600_000).toLong()
            else -> t.toLongOrNull() ?: 1000
        }
    }

    private fun humanDuration(ms: Long): String = when {
        ms >= 3_600_000 -> "%.1fh".format(ms / 3_600_000.0)
        ms >= 60_000 -> "%.0fm".format(ms / 60_000.0)
        ms >= 1000 -> "%.0fs".format(ms / 1000.0)
        else -> "${ms}ms"
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> "%.2f GB".format(b / 1024.0 / 1024 / 1024)
        b >= 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
        b >= 1024 -> "%.1f kB".format(b / 1024.0)
        else -> "$b B"
    }
}
