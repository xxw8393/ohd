package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.actions.LayoutCache
import ohd.core.actions.SceneSignature
import ohd.core.actions.ScrollAtlas
import ohd.core.actions.UiActions
import ohd.core.actions.UiNav
import ohd.core.actions.UiReady
import ohd.core.actions.WorldMap
import ohd.core.parsers.LayoutNode
import ohd.core.parseDurationMs

/**
 * 认路 → 点 → 可选验标。多页 LayoutCache + ScrollAtlas + 落盘 WorldMap。
 */
class GoCap : Capability {
    private companion object {
        /**
         * 有 `--expect` 时 settle 的上限。
         *
         * settle 原意是「点后盲等界面稳定」，但验标轮询（pollUntil，命中即返回）
         * 干的是同一件事且更准确。两者叠加等于白等：实测 expect 分支里
         * settle 400ms + 验标 ~1.8s，前者完全可被后者吸收。
         * 留 100ms 是为了让点击事件先在设备侧落地，避免第一次 dump 抓到旧帧。
         */
        const val SETTLE_WITH_EXPECT_MS = 100L
    }
    override val name = "go"
    override val summary = "按文本认路点击并可选验标（多页缓存 + 世界地图）"
    override val usage = """
        ohd go --grep <地标> [--expect <下一屏地标>] [--light] [--bundle <包名>] [--ability EntryAbility]
               [--scene <列表场景名>] [--index N] [--any] [--fresh] [--force] [--no-map-first]
               [--timeout 2s] [--settle 400ms] [--double|--long]
               [--scroll-find] [--max-swipes 12] [--swipe-dir up]

        地图坐标捷径默认开启（命中跳过点前 dump，实测单步 10.9s → 2.4s）。
        未命中自动回退 dump 认路。--no-map-first 强制走 dump。
    """.trimIndent()
    override val example =
        "ohd go --grep 多实例测试 --expect 关闭A --bundle com.tencent.compose"
    override val accepts: Set<String> = setOf(
        "ability", "any", "bundle", "double", "expect", "force", "fresh", "grep", "index", "light", "long",
        "map-first", "no-map-first", "max-swipes", "scene", "scroll-find", "settle", "swipe-dir", "timeout",
        "type", "id",
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val grep = ctx.options.str("grep")
            ?: throw OhdError(ExitCode.USAGE, "需要 --grep <地标>", example)
        val expect = ctx.options.str("expect")
        val light = ctx.options.bool("light")
        val clickableOnly = !ctx.options.bool("any")
        val index = ctx.options.values["index"]?.let { ctx.options.int("index", 0) }
        val scrollFind = ctx.options.bool("scroll-find")
        val defaultTimeout = when {
            scrollFind -> "45s"
            light && expect != null -> "1.5s"
            else -> "2s"
        }
        val defaultMaxSwipes = if (scrollFind) 12 else 6
        val timeoutMs = parseDurationMs(ctx.options.str("timeout") ?: defaultTimeout)
        val settleMs = parseDurationMs(
            ctx.options.str("settle") ?: if (light) "200ms" else "400ms",
        )
        val maxSwipes = ctx.options.int("max-swipes", defaultMaxSwipes).coerceIn(0, 40)
        val swipeDir = ctx.options.str("swipe-dir") ?: "up"
        val type = ctx.options.str("type")
        val id = ctx.options.str("id")
        val fresh = ctx.options.bool("fresh")
        val force = ctx.options.bool("force")
        // 地图坐标捷径默认开启：命中就省掉点前整轮 dump（实测单步 10.9s → 4.7s），
        // 未命中自动回退 dump 认路，行为与从前一致。--no-map-first 可关闭。
        // 仍是显式选项而非硬编码：个别场景（如地图被污染）需要绕过。
        val mapFirst = ctx.options.bool("map-first", default = true)
        val bundle = ctx.options.str("bundle")
        val mapBundle = bundle ?: System.getenv("OHD_BUNDLE") ?: "com.tencent.compose"
        val ability = ctx.options.str("ability") ?: "EntryAbility"
        val sceneOpt = ctx.options.str("scene")
        val kind = when {
            ctx.options.bool("double") -> UiActions.Kind.DOUBLE_TAP
            ctx.options.bool("long") -> UiActions.Kind.LONG_PRESS
            else -> UiActions.Kind.TAP
        }
        // map-first 现在默认开启，所以不能再拿「mapFirst && scrollFind」判互斥 ——
        // 那样会让 `--scroll-find` 必须先加 --no-map-first 才能用，把既有脚本全打断。
        // 只有**显式**给了 --map-first 才视为用户坚持两条路并用，此时才报冲突。
        val mapFirstExplicit = "map-first" in ctx.options.flags
        if (mapFirstExplicit && scrollFind) {
            throw OhdError(
                ExitCode.USAGE,
                "--map-first 与 --scroll-find 互斥（滑找本就要多次 dump，地图捷径无意义）",
                usage,
            )
        }
        // 滑找场景自动让路：即便默认开着 map-first，它也用不上，还可能与滑找抢缓存
        val effectiveMapFirst = mapFirst && !scrollFind
        // 注：曾要求 map-first 必带 --expect，理由是「用验标代替点前 dump」。
        // 但验标实测要付 ~1.8s（4 次 dumpLayout 轮询，单次设备侧 ~450ms），
        // 而 map-first 省下的正是同一笔 dump —— 强制验标等于把省下的时间又还回去。
        // 现在允许不带 expect：点后靠 pid 存活 + 页面确实变化 兜底校验（见 postTap 分支），
        // 仍不安全时调用方可显式补 --expect。

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "go")
        val ready = UiReady.ensure(hdc, session, bundle, ability)
        if (!ctx.out.jsonMode && ready.notes.isNotEmpty()) {
            ctx.out.info("ready：${ready.notes.joinToString(",")}")
        }
        if (fresh) {
            // 只清进程内 LayoutCache；世界地图按页 observe 合并更新坐标，禁止整图清空
            LayoutCache.invalidate(hdc)
        }
        val fromPageId = WorldMap.load(mapBundle).currentPageId ?: LayoutCache.current(hdc)?.pageId

        fun resolveOn(flat: List<LayoutNode>) =
            UiNav.resolve(flat, grep, clickableOnly, index, type, id)

        var swipes = 0
        var layoutSource = "cache"
        var batchUsed = 0
        var sceneId = sceneOpt.orEmpty()
        var resolved: UiNav.Resolve? = null
        var mapFirstHit = false
        var mapTapPageId: String? = null

        // —— 地图坐标捷径：跳过 dump/认路，点后靠 --expect 验标 ——
        if (effectiveMapFirst) {
            // 用缓存里的当前屏布局算视口签名：命中则坐标必然有效，未命中则说明屏已变
            val curViewport = LayoutCache.get(hdc)?.let { SceneSignature.viewportSignature(it) }
            val tap = WorldMap.findMapTap(
                mapBundle, grep, fromPageId, index, clickableOnly,
                currentViewport = curViewport,
            )
            if (tap != null) {
                val x = tap.item.centerX!!
                val y = tap.item.centerY!!
                val node = LayoutNode(
                    text = tap.item.label,
                    id = null,
                    type = "MapFirst",
                    description = null,
                    bounds = ohd.core.parsers.Rect(x - 20, y - 20, x + 20, y + 20),
                    clickable = tap.item.clickable,
                    enabled = true,
                    visible = true,
                    bundleName = mapBundle,
                    depth = 0,
                )
                resolved = UiNav.Resolve.Ok(
                    UiNav.Pick(node, x, y, matched = tap.matched, index = tap.index),
                )
                layoutSource = "map-first:${tap.pageId.take(8)}"
                mapFirstHit = true
                mapTapPageId = tap.pageId
            } else if (!ctx.out.jsonMode) {
                ctx.out.info("map-first：地图无「$grep」坐标，回退 dump 认路")
            }
        }

        // —— 同页缓存快路径（非滑找、非 map-first 命中）——
        if (resolved == null && !scrollFind) {
            LayoutCache.get(hdc)?.let { cached ->
                when (val r = resolveOn(cached)) {
                    is UiNav.Resolve.Ok -> {
                        resolved = r
                        layoutSource = "cache:${LayoutCache.current(hdc)?.pageId ?: "?"}"
                    }
                    is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> resolved = r
                    is UiNav.Resolve.None -> Unit
                }
            }
        }

        // —— 滑找：世界地图/atlas 估次批量滑，再 dump；边学边补 ——
        if (resolved == null && scrollFind) {
            // 跨页：先按地图 Back，再 dump 列表顶
            WorldMap.planTo(mapBundle, grep)?.steps?.filter { it.kind == "back" }?.forEach { s ->
                repeat(s.swipes ?: 1) {
                    UiActions.perform(hdc, UiActions.Action(UiActions.Kind.KEY, listOf("Back"), "", 0), 10_000)
                    LayoutCache.noteBack(hdc)
                    WorldMap.noteBack(mapBundle)
                    Thread.sleep(300)
                }
            }
            val top = dumpReady(hdc, session, bundle = mapBundle)
            if (sceneId.isEmpty()) sceneId = LayoutCache.fingerprint(top).first
            WorldMap.observePage(mapBundle, top, sceneSwipes = 0)
            ScrollAtlas.learn(hdc, sceneId, 0, top)
            WorldMap.learnScroll(mapBundle, sceneId, 0, top)

            when (val r = resolveOn(top)) {
                is UiNav.Resolve.Ok -> {
                    resolved = r
                    layoutSource = "dump:${LayoutCache.current(hdc)?.pageId}"
                }
                is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> resolved = r
                is UiNav.Resolve.None -> {
                    val fromMap = WorldMap.planTo(mapBundle, grep)?.steps
                        ?.firstOrNull { it.kind == "swipe" }?.swipes
                    val estimated = (fromMap ?: ScrollAtlas.estimate(hdc, sceneId, grep))
                        ?.coerceIn(0, maxSwipes)
                    if (estimated != null && estimated > 0) {
                        if (!ctx.out.jsonMode) {
                            ctx.out.info(
                                "world-map/atlas：「$grep」约 ${estimated} 滑 → 批量滑",
                            )
                        }
                        // 旧 atlas 次数按小步滑学的，一屏后不可一次 batch 太多（易滑出 App）
                        val chunk = estimated.coerceAtMost(3)
                        batchSwipe(hdc, swipeDir, chunk, mapBundle)
                        swipes += chunk
                        batchUsed = chunk
                        LayoutCache.invalidateCurrent(hdc)
                        Thread.sleep(280)
                        val after = dumpReady(hdc, session, bundle = mapBundle)
                        ScrollAtlas.learn(hdc, sceneId, swipes, after)
                        WorldMap.learnScroll(mapBundle, sceneId, swipes, after)
                        when (val r2 = resolveOn(after)) {
                            is UiNav.Resolve.Ok -> {
                                resolved = r2
                                layoutSource = "atlas-batch:$chunk"
                            }
                            is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> resolved = r2
                            is UiNav.Resolve.None -> Unit
                        }
                    }
                }
            }

            if (resolved == null) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (resolved == null && swipes < maxSwipes && System.currentTimeMillis() < deadline) {
                    batchSwipe(hdc, swipeDir, 1, mapBundle)
                    swipes++
                    LayoutCache.invalidateCurrent(hdc)
                    Thread.sleep(220)
                    val flat = dumpReady(hdc, session, bundle = mapBundle)
                    ScrollAtlas.learn(hdc, sceneId, swipes, flat)
                    WorldMap.learnScroll(mapBundle, sceneId, swipes, flat)
                    layoutSource = "explore:$swipes"
                    when (val r = resolveOn(flat)) {
                        is UiNav.Resolve.Ok -> resolved = r
                        is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> resolved = r
                        is UiNav.Resolve.None -> Unit
                    }
                }
            }
        } else if (resolved == null) {
            resolved = UiNav.pollUntil(timeoutMs) {
                val flat = runCatching { dumpReady(hdc, session) }.getOrNull()
                    ?: return@pollUntil null
                WorldMap.observePage(mapBundle, flat)
                layoutSource = "dump:${LayoutCache.current(hdc)?.pageId}"
                when (val r = resolveOn(flat)) {
                    is UiNav.Resolve.Ok, is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> r
                    is UiNav.Resolve.None -> null
                }
            }
        }

        when (val r = resolved) {
            null -> {
                ctx.out.note("NO_MATCH", "warn", "超时/滑尽未见「$grep」（已滑 $swipes 次）")
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "认路失败：未见「$grep」",
                    "加 --scroll-find --timeout 45s；首次会学习 atlas，第二次批量滑",
                )
            }
            is UiNav.Resolve.Ambiguous -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "认路歧义：${r.count} 个「$grep」（${r.labels.joinToString(" / ")}）",
                "加 --index N",
            )
            is UiNav.Resolve.BadIndex -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "认路 --index ${r.index} 越界（命中 ${r.count}）",
            )
            is UiNav.Resolve.None -> throw OhdError(ExitCode.ASSERT_FAILED, "认路失败：未见「$grep」")
            is UiNav.Resolve.Ok -> Unit
        }

        // 点前强制当轮 dump（map-first 命中则跳过：省 dump，靠 --expect 纠偏）
        if (!mapFirstHit) {
            run {
                val flat = runCatching { dumpReady(hdc, session, bundle = mapBundle) }.getOrNull()
                if (flat != null) {
                    WorldMap.observePage(mapBundle, flat)
                    when (val r = resolveOn(flat)) {
                        is UiNav.Resolve.Ok -> {
                            resolved = r
                            layoutSource = "pre-tap:${LayoutCache.current(hdc)?.pageId ?: "?"}"
                        }
                        is UiNav.Resolve.Ambiguous, is UiNav.Resolve.BadIndex -> resolved = r
                        is UiNav.Resolve.None -> {
                            ctx.out.note("NO_MATCH", "warn", "点前 dump 已不见「$grep」（坐标作废）")
                            throw OhdError(
                                ExitCode.ASSERT_FAILED,
                                "认路失败：点前 dump 未见「$grep」",
                                "列表可能已滑走；加 --scroll-find 或先 tree 刷新当页",
                            )
                        }
                    }
                }
            }
        }
        when (val r = resolved) {
            is UiNav.Resolve.Ambiguous -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "认路歧义：${r.count} 个「$grep」（${r.labels.joinToString(" / ")}）",
                "加 --index N",
            )
            is UiNav.Resolve.BadIndex -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "认路 --index ${r.index} 越界（命中 ${r.count}）",
            )
            is UiNav.Resolve.None, null ->
                throw OhdError(ExitCode.ASSERT_FAILED, "认路失败：未见「$grep」")
            is UiNav.Resolve.Ok -> Unit
        }

        val pick = (resolved as UiNav.Resolve.Ok).pick
        val tapLabel = pick.node.label()
        val banFrom = mapTapPageId ?: fromPageId ?: LayoutCache.current(hdc)?.pageId
        if (!force && WorldMap.isPathBanned(mapBundle, banFrom, tapLabel)) {
            val crash = WorldMap.findPathCrash(mapBundle, banFrom, tapLabel)
            ctx.out.note(
                "PATH_CRASH",
                "error",
                "路径已禁：「$tapLabel」pid ${crash?.pidBefore ?: "?"}→${crash?.pidAfter ?: "dead"}",
            )
            throw OhdError(
                ExitCode.ASSERT_FAILED,
                "放弃：地图已标 crash（点「$tapLabel」曾致进程变化）",
                "ohd map clear-crashes --bundle $mapBundle 或 go --force",
            )
        }
        // 点前再确认仍在 App（防滑找已掉桌面还用旧坐标乱点）。
        //
        // 这里**不** force：紧邻上方的 UiReady.ensure 刚刚查过前台并已断言
        // 「前台就是本包，否则直接抛错」，中间只隔了地图/缓存查找（纯内存）。
        // 再 force 一次等于在同一毫秒里把 aa dump -a（实测 358ms）重跑一遍。
        // 若中间真的走过滑找（batchSwipe 内部自有 force 校验），
        // 那条路径已经各自确认过，无需在此补。
        UiReady.assertInApp(hdc, mapBundle, pullBackOnce = true)

        // 单次命令内只真查一次 pid：下面验标失败/无 expect 分支还要用同一个值。
        val pidCache = PidCache()
        val pidBefore = softPid(hdc, mapBundle, pidCache)
        val tapped = UiActions.perform(
            hdc,
            UiActions.Action(kind, listOf(pick.x.toString(), pick.y.toString()), "", 0),
            15_000,
        )
        if (!tapped.ok) {
            throw OhdError(ExitCode.UNSUPPORTED, "点击失败：${tapped.detail}")
        }
        // settle 是「点后等界面稳定」的盲等。有 expect 时它纯属重复劳动：
        // 紧随其后的验标轮询本身就在等目标出现（pollUntil 命中即返回），
        // 所以此时把 settle 压到最小值，让轮询去吸收这段时间 —— 实测省 ~400ms/步。
        // 无 expect 时没有轮询兜底，仍需完整 settle 等界面出来。
        val effectiveSettle = if (expect != null) minOf(settleMs, SETTLE_WITH_EXPECT_MS) else settleMs
        if (effectiveSettle > 0) Thread.sleep(effectiveSettle)

        var expectOk = expect == null
        var expectMs = 0L
        var toPageId: String? = null
        if (expect != null) {
            val tExpect = System.currentTimeMillis()
            // 期限下调：原来强行 coerceAtLeast(2000)，即使调用方给了更短的 --timeout 也会被
            // 拖到 2s。但 pollUntil 是「命中即返回」，期限只影响失败时的等待上限；
            // 界面切换实测 ≤2 次 dump（~900ms）就够，故下限降到 1200ms（light 800ms）。
            val expectTimeout = if (light) timeoutMs.coerceAtLeast(800) else timeoutMs.coerceAtLeast(1_200)
            fun sampleExpect(): Boolean? {
                // 走 LayoutCache 而非裸 captureLayout：验标抓到的树必须进缓存，
                // 否则紧接着的 observePage / findMapTap 会再抓一次同屏（每次 433ms）。
                // fresh=true 是必要的 —— 验标本就是在等「点后新屏」，不能吃点击前的旧缓存。
                val flat = runCatching {
                    val (raw, _) = LayoutCache.nodes(hdc, null, fresh = true, bundle = mapBundle)
                    UiReady.assertNotLockOrUnlock(hdc, raw)
                }.getOrNull() ?: return null
                // 轻量：任意命中即可（通常是页首/首个列表元素），不必等整树「齐」
                if (!UiNav.anyMatch(flat, expect)) return null
                LayoutCache.noteEnter(hdc, flat)
                // 用验标地标作为这页的身份：它正是「确认点对了」的判据，不用另猜
                val page = WorldMap.observePage(
                    mapBundle,
                    flat,
                    pushIfNew = true,
                    landmark = expect,
                )
                toPageId = page.id
                return true
            }
            expectOk = if (light) {
                UiNav.pollLight(expectTimeout, maxDumps = 2, sample = ::sampleExpect) == true
            } else {
                UiNav.pollUntil(expectTimeout, sample = ::sampleExpect) == true
            }
            expectMs = System.currentTimeMillis() - tExpect
            if (!expectOk) {
                ctx.out.note("EXPECT_MISS", "warn", "点后未见「$expect」" + if (light) "（light≤2 dump）" else "")
                if (mapFirstHit) {
                    WorldMap.invalidateItemCoords(mapBundle, mapTapPageId ?: banFrom, tapLabel)
                    ctx.out.note("MAP_STALE", "warn", "已作废地图坐标「$tapLabel」，下次将 dump 认路")
                }
                val pidAfter = softPid(hdc, mapBundle, pidCache, force = true)
                val crashed = pidBefore != null && (pidAfter == null || pidAfter != pidBefore)
                if (crashed && banFrom != null) {
                    WorldMap.notePathCrash(
                        mapBundle,
                        banFrom,
                        tapLabel,
                        pidBefore,
                        pidAfter,
                        expect,
                    )
                    ctx.out.note(
                        "PATH_CRASH",
                        "error",
                        "pid $pidBefore→${pidAfter ?: "dead"}，已禁路径「$tapLabel」",
                    )
                    throw OhdError(
                        ExitCode.ASSERT_FAILED,
                        "验标失败且进程变化：点击「$grep」后未见「$expect」（已标 crash）",
                        "本轮证据作废；后续同路径将放弃（map clear-crashes / go --force）",
                    )
                }
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "验标失败：点击「$grep」后未见「$expect」" +
                        if (pidBefore != null) "（pid 未变 $pidBefore）" else "",
                    if (mapFirstHit) "地图坐标可能过期；去掉 --map-first 重试" else "本轮证据作废",
                )
            }
            if (fromPageId != null && toPageId != null) {
                WorldMap.noteEdge(mapBundle, fromPageId, toPageId!!, "tap", tapLabel)
            }
        } else {
            // 无 expect 时也查一次：点完进程没了 → 同样标 crash
            val pidAfter = softPid(hdc, mapBundle, pidCache, force = true)
            if (pidBefore != null && (pidAfter == null || pidAfter != pidBefore) && banFrom != null) {
                WorldMap.notePathCrash(mapBundle, banFrom, tapLabel, pidBefore, pidAfter, null)
                ctx.out.note(
                    "PATH_CRASH",
                    "error",
                    "点后进程变化 pid $pidBefore→${pidAfter ?: "dead"}，已禁「$tapLabel」",
                )
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "点击「$grep」后进程变化（已标 crash）",
                    "本轮证据作废；map clear-crashes / go --force",
                )
            }
            // map-first 且没给 expect 时不额外 dump 兜底：
            // 「界面是否变化」这判据不可靠 —— 实测点「多实例测试」只弹出带 Dismiss 的
            // 浮层，主页面文本集几乎不变，会被误判成坐标过期（假阳性）；
            // 而为了判它再加一次 dump（~450ms），等于把 map-first 省下的 dump 又还回去。
            // 故此处只用已有事实兜底：pid 存活（上面已查）+ 收尾的 assertInApp。
            // 需要严格断言「点对了没」时，调用方加 --expect。
            // 同页点击刷新地图，但要分清两种情形：
            //
            // 1) map-first 命中：LayoutCache 里的树是**上一轮遗留**的，未必对应当前页；
            //    用它去 observePage 可能把指纹算成别的页，反而污染地图。
            //    此时只用 mapTapPageId **精确**确认已知页（零 dump、零猜测）。
            // 2) 非 map-first（确实 dump 过）：树就是当前屏，照常 observePage。
            if (mapFirstHit && mapTapPageId != null) {
                // verified=false：这一支没验标，只知道「点完没崩」，不足以清墓碑。
                WorldMap.confirmPage(mapBundle, mapTapPageId!!, verified = false)
            } else {
                LayoutCache.get(hdc)?.let { WorldMap.observePage(mapBundle, it) }
            }
        }
        val elapsed = System.currentTimeMillis() - started
        val mapPages = WorldMap.load(mapBundle).pages.size
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "go",
                true,
                buildJsonObject {
                    put("grep", grep)
                    put("expect", expect ?: "")
                    put("x", pick.x)
                    put("y", pick.y)
                    put("matched", pick.matched)
                    put("index", pick.index)
                    put("swipes", swipes)
                    put("batchSwipes", batchUsed)
                    put("scene", sceneId)
                    put("atlasHints", ScrollAtlas.hintCount(hdc, sceneId))
                    put("layoutSource", layoutSource)
                    put("pagesCached", LayoutCache.pageCount(hdc))
                    put("worldMapPages", mapPages)
                    put("worldMap", WorldMap.pathFor(mapBundle).toString())
                    put("label", pick.node.label())
                    put("expectOk", expectOk)
                    put("expectMs", expectMs)
                    put("elapsedMs", elapsed)
                },
                elapsed,
            )
        } else {
            val kindLabel = when (kind) {
                UiActions.Kind.DOUBLE_TAP -> "双击"
                UiActions.Kind.LONG_PRESS -> "长按"
                else -> "点击"
            }
            ctx.out.info(
                "go OK：$kindLabel (${pick.x},${pick.y}) 「${pick.node.label().take(40)}」" +
                    " [$layoutSource]" +
                    (if (batchUsed > 0) " atlas批量$batchUsed" else "") +
                    (if (swipes > 0) " 总滑$swipes" else "") +
                    (if (expect != null) " →「$expect」" else "") +
                    (if (light) " light" else "") +
                    (if (expect != null) " expect=${expectMs}ms" else "") +
                    " 地图${mapPages}页 ${elapsed}ms",
            )
        }
        return ExitCode.OK
    }

    /**
     * 不抛：pidof / ps 取主进程 pid；进程已死返回 null。
     *
     * [cache] 非空时优先复用其中的值（单次 `go` 内 pid 不会无缘无故变；
     * 一次命令原来要为此打 3 次设备，每次 59ms 起，`pidof` 未命中还要再跑 `ps`）。
     * 需要「点后是否崩了」这种硬判断时必须传 `force = true` —— 复用旧 pid 会把
     * 「进程已死」误判成「还活着」，正好把这个检查废掉。
     */
    private fun softPid(hdc: ohd.core.Hdc, bundle: String, cache: PidCache? = null, force: Boolean = false): String? {
        if (!force && cache?.value != null) return cache.value
        val v = softPidUncached(hdc, bundle)
        cache?.value = v
        return v
    }

    /** 单次命令内的 pid 记忆。 */
    private class PidCache {
        var value: String? = null
    }

    private fun softPidUncached(hdc: ohd.core.Hdc, bundle: String): String? {
        val viaPidof = runCatching {
            hdc.shell("pidof $bundle", 8_000).stdout.trim()
                .split(Regex("\\s+"))
                .firstOrNull { it.toIntOrNull() != null }
        }.getOrNull()
        if (!viaPidof.isNullOrBlank()) return viaPidof
        return runCatching {
            val ps = hdc.shell("ps -A -o PID,NAME 2>/dev/null | grep -w '$bundle' | head -3", 10_000)
            ps.stdout.lines().map { it.trim() }.firstNotNullOfOrNull { line ->
                line.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()?.toString()
            }
        }.getOrNull()
    }

    private fun dumpReady(
        hdc: ohd.core.Hdc,
        session: ohd.core.Session?,
        pushIfNew: Boolean = false,
        bundle: String? = null,
    ): List<LayoutNode> {
        val (raw, _) = LayoutCache.nodes(hdc, session, fresh = true, pushIfNew = pushIfNew, bundle = bundle)
        val unlocked = UiReady.assertNotLockOrUnlock(hdc, raw)
        if (bundle != null) {
            UiReady.assertInApp(hdc, bundle, unlocked)
        }
        return unlocked
    }

    /**
     * 在 **当轮 layout 算出的内容安全区内** drag 翻页；每步后校验仍在 App。
     */
    private fun batchSwipe(hdc: ohd.core.Hdc, dir: String, times: Int, bundle: String) {
        if (times <= 0) return
        repeat(times) { i ->
            // 走 LayoutCache 让抓到的树进缓存（后面 observePage / 下一步认路可复用），
            // 原来裸 captureLayout 等于每滑一次白丢一棵树（433ms）。
            val flat = runCatching {
                val (raw, _) = LayoutCache.nodes(hdc, null, fresh = true, bundle = bundle)
                UiReady.assertNotLockOrUnlock(hdc, raw)
            }.getOrNull()
            UiReady.assertInApp(hdc, bundle, flat)
            val action = if (flat != null) {
                val safe = UiNav.contentSafeRect(flat)
                UiNav.scrollPageAction(dir, safe)
            } else {
                UiNav.scrollPageActionFallback(dir)
            }
            UiActions.perform(hdc, action, 10_000)
            // 拖完立刻看是否被系统手势打到桌面。
            // forceFg=true 是有意的：滑动是最可能「打出 App」的动作，必须拿真实前台，
            // 不能吃 5s TTL 里的旧值，否则会漏掉翻到桌面的情况。异常照常抛（不吞）。
            UiReady.assertInApp(hdc, bundle, pullBackOnce = false, forceFg = true)
            if (i < times - 1) Thread.sleep(50)
        }
    }
}

/**
 * 短轮询等到地标出现/消失。当前页缓存优先。
 */
class WaitCap : Capability {
    override val name = "wait"
    override val summary = "轮询直到地标出现/消失；当前页缓存优先；--light 最多 2 次 dump"
    override val usage =
        "ohd wait --grep <地标> [--light] [--timeout 2s] [--absent] [--clickable] [--any] [--fresh] [--bundle <包名>]"
    override val example = "ohd wait --grep 返回 --timeout 2s"
    override val accepts: Set<String> = setOf(
        "ability", "absent", "any", "bundle", "clickable", "fresh", "grep", "light", "timeout",
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val grep = ctx.options.str("grep")
            ?: throw OhdError(ExitCode.USAGE, "需要 --grep <地标>", example)
        val light = ctx.options.bool("light")
        val timeoutMs = parseDurationMs(
            ctx.options.str("timeout") ?: if (light) "1.5s" else "2s",
        )
        val absent = ctx.options.bool("absent")
        val fresh = ctx.options.bool("fresh")
        val clickableOnly = ctx.options.bool("clickable") && !ctx.options.bool("any")
        val bundle = ctx.options.str("bundle")
        val ability = ctx.options.str("ability") ?: "EntryAbility"

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "wait")
        UiReady.ensure(hdc, session, bundle, ability)
        if (fresh) LayoutCache.invalidateCurrent(hdc)

        var layoutSource = "cache"
        if (!absent) {
            LayoutCache.get(hdc)?.let { cached ->
                if (UiNav.anyMatch(cached, grep, clickableOnly)) {
                    return finishWait(
                        ctx, grep, absent, System.currentTimeMillis() - started,
                        "cache:${LayoutCache.current(hdc)?.pageId}", light,
                    )
                }
            }
        }

        fun sampleWait(): Boolean? {
            val (flat, src) = runCatching {
                LayoutCache.nodes(hdc, null, fresh = true)
            }.getOrNull() ?: return null
            layoutSource = src
            val present = UiNav.anyMatch(flat, grep, clickableOnly)
            return when {
                absent && !present -> true
                !absent && present -> true
                else -> null
            }
        }
        val hit = if (light) {
            UiNav.pollLight(timeoutMs, maxDumps = 2, sample = ::sampleWait)
        } else {
            UiNav.pollUntil(timeoutMs, sample = ::sampleWait)
        }
        val elapsed = System.currentTimeMillis() - started
        if (hit != true) {
            val want = if (absent) "消失" else "出现"
            throw OhdError(
                ExitCode.ASSERT_FAILED,
                "wait 超时：期望「$grep」$want（${elapsed}ms${if (light) ", light" else ""}）",
            )
        }
        return finishWait(ctx, grep, absent, elapsed, layoutSource, light)
    }

    private fun finishWait(
        ctx: RunContext,
        grep: String,
        absent: Boolean,
        elapsed: Long,
        source: String,
        light: Boolean = false,
    ): Int {
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "wait", true,
                buildJsonObject {
                    put("grep", grep)
                    put("absent", absent)
                    put("layoutSource", source)
                    put("light", light)
                    put("elapsedMs", elapsed)
                },
                elapsed,
            )
        } else {
            ctx.out.info(
                "wait OK：「$grep」${if (absent) "已消失" else "已出现"} [$source]" +
                    (if (light) " light" else "") + " ${elapsed}ms",
            )
        }
        return ExitCode.OK
    }
}

/**
 * 系统 Back + 可选验标。比裸 `input key Back` 多：LayoutCache/WorldMap 弹栈 + 确认回到目标页。
 * `--light`：最多 2 次 dump，适合高速进退环。
 */
class BackCap : Capability {
    override val name = "back"
    override val summary = "系统 Back 并可选验标（弹栈 + 世界地图；--light 少 dump）"
    override val usage =
        "ohd back [--expect <地标>] [--light] [--bundle <包名>] [--timeout 2s] [--settle 200ms]"
    override val example = "ohd back --expect '1500 Text' --light --bundle com.tencent.compose"
    override val accepts: Set<String> = setOf(
        "ability", "bundle", "expect", "light", "settle", "timeout",
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val expect = ctx.options.str("expect")
        val light = ctx.options.bool("light")
        val timeoutMs = parseDurationMs(
            ctx.options.str("timeout") ?: if (light) "1.5s" else "2s",
        )
        val settleMs = parseDurationMs(
            ctx.options.str("settle") ?: if (light) "150ms" else "300ms",
        )
        val bundle = ctx.options.str("bundle")
        val mapBundle = bundle ?: System.getenv("OHD_BUNDLE") ?: "com.tencent.compose"
        val ability = ctx.options.str("ability") ?: "EntryAbility"

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "back")
        UiReady.ensure(hdc, session, bundle, ability)

        val action = UiActions.Action(UiActions.Kind.KEY, listOf("Back"), "", 0)
        val r = UiActions.perform(hdc, action, 15_000)
        if (!r.ok) {
            throw OhdError(ExitCode.UNSUPPORTED, "Back 失败：${r.detail}")
        }
        val restored = LayoutCache.noteBack(hdc)
        WorldMap.noteBack(mapBundle)
        if (settleMs > 0) Thread.sleep(settleMs)

        var expectMs = 0L
        if (expect != null) {
            val t0 = System.currentTimeMillis()
            fun sampleBack(): Boolean? {
                val flat = runCatching {
                    val raw = DeviceOps.captureLayout(hdc, null, bundle = mapBundle).flat()
                    UiReady.assertNotLockOrUnlock(hdc, raw)
                }.getOrNull() ?: return null
                if (!UiNav.anyMatch(flat, expect)) return null
                LayoutCache.put(hdc, flat)
                // 同 go：Back 后验标命中的元素就是这一页的身份
                WorldMap.observePage(mapBundle, flat, landmark = expect)
                return true
            }
            val ok = if (light) {
                UiNav.pollLight(timeoutMs, maxDumps = 2, sample = ::sampleBack) == true
            } else {
                UiNav.pollUntil(timeoutMs.coerceAtLeast(1_000), sample = ::sampleBack) == true
            }
            expectMs = System.currentTimeMillis() - t0
            if (!ok) {
                ctx.out.note("EXPECT_MISS", "warn", "Back 后未见「$expect」")
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "验标失败：Back 后未见「$expect」",
                    "本轮证据作废",
                )
            }
        }

        val elapsed = System.currentTimeMillis() - started
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "back", true,
                buildJsonObject {
                    put("expect", expect ?: "")
                    put("light", light)
                    put("restoredPage", restored ?: "")
                    put("expectMs", expectMs)
                    put("elapsedMs", elapsed)
                },
                elapsed,
            )
        } else {
            ctx.out.info(
                "back OK" +
                    (if (restored != null) " →cache:$restored" else "") +
                    (if (expect != null) " →「$expect」 expect=${expectMs}ms" else "") +
                    (if (light) " light" else "") +
                    " ${elapsed}ms",
            )
        }
        return ExitCode.OK
    }
}
