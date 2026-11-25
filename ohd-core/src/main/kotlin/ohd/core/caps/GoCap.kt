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
    override val name = "go"
    override val summary = "按文本认路点击并可选验标（多页缓存 + 世界地图）"
    override val usage = """
        ohd go --grep <地标> [--expect <下一屏地标>] [--light] [--bundle <包名>] [--ability EntryAbility]
               [--scene <列表场景名>] [--index N] [--any] [--fresh]
               [--timeout 2s] [--settle 400ms] [--double|--long]
               [--scroll-find] [--max-swipes 12] [--swipe-dir up]
    """.trimIndent()
    override val example =
        "ohd go --grep GPU Texture --scroll-find --scene home --bundle com.tencent.compose"
    override val accepts: Set<String> = setOf(
        "ability", "any", "bundle", "double", "expect", "fresh", "grep", "index", "light", "long",
        "max-swipes", "scene", "scroll-find", "settle", "swipe-dir", "timeout", "type", "id",
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
        val bundle = ctx.options.str("bundle")
        val mapBundle = bundle ?: System.getenv("OHD_BUNDLE") ?: "com.tencent.compose"
        val ability = ctx.options.str("ability") ?: "EntryAbility"
        val sceneOpt = ctx.options.str("scene")
        val kind = when {
            ctx.options.bool("double") -> UiActions.Kind.DOUBLE_TAP
            ctx.options.bool("long") -> UiActions.Kind.LONG_PRESS
            else -> UiActions.Kind.TAP
        }

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "go")
        val ready = UiReady.ensure(hdc, session, bundle, ability)
        if (!ctx.out.jsonMode && ready.notes.isNotEmpty()) {
            ctx.out.info("ready：${ready.notes.joinToString(",")}")
        }
        if (fresh) {
            LayoutCache.invalidate(hdc)
            WorldMap.clear(mapBundle)
        }
        val fromPageId = WorldMap.load(mapBundle).currentPageId ?: LayoutCache.current(hdc)?.pageId

        fun resolveOn(flat: List<LayoutNode>) =
            UiNav.resolve(flat, grep, clickableOnly, index, type, id)

        var swipes = 0
        var layoutSource = "cache"
        var batchUsed = 0
        var sceneId = sceneOpt.orEmpty()
        var resolved: UiNav.Resolve? = null

        // —— 同页缓存快路径（非滑找）——
        if (!scrollFind) {
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
        val pick = (resolved as UiNav.Resolve.Ok).pick
        // 点前再确认仍在 App（防滑找已掉桌面还用旧坐标乱点）
        UiReady.assertInApp(hdc, mapBundle, pullBackOnce = true)

        val tapped = UiActions.perform(
            hdc,
            UiActions.Action(kind, listOf(pick.x.toString(), pick.y.toString()), "", 0),
            15_000,
        )
        if (!tapped.ok) {
            throw OhdError(ExitCode.UNSUPPORTED, "点击失败：${tapped.detail}")
        }
        if (settleMs > 0) Thread.sleep(settleMs)

        var expectOk = expect == null
        var expectMs = 0L
        var toPageId: String? = null
        if (expect != null) {
            val tExpect = System.currentTimeMillis()
            val expectTimeout = if (light) timeoutMs.coerceAtLeast(800) else timeoutMs.coerceAtLeast(2_000)
            fun sampleExpect(): Boolean? {
                val flat = runCatching {
                    val raw = DeviceOps.captureLayout(hdc, null).flat()
                    UiReady.assertNotLockOrUnlock(hdc, raw)
                }.getOrNull() ?: return null
                // 轻量：任意命中即可（通常是页首/首个列表元素），不必等整树「齐」
                if (!UiNav.anyMatch(flat, expect)) return null
                LayoutCache.noteEnter(hdc, flat)
                val page = WorldMap.observePage(mapBundle, flat, pushIfNew = true)
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
                throw OhdError(
                    ExitCode.ASSERT_FAILED,
                    "验标失败：点击「$grep」后未见「$expect」",
                    "本轮证据作废",
                )
            }
            if (fromPageId != null && toPageId != null) {
                WorldMap.noteEdge(mapBundle, fromPageId, toPageId!!, "tap", pick.node.label())
            }
        } else {
            // 同页点击也刷新地图上的可见 item
            LayoutCache.get(hdc)?.let { WorldMap.observePage(mapBundle, it) }
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

    private fun dumpReady(
        hdc: ohd.core.Hdc,
        session: ohd.core.Session?,
        pushIfNew: Boolean = false,
        bundle: String? = null,
    ): List<LayoutNode> {
        val (raw, _) = LayoutCache.nodes(hdc, session, fresh = true, pushIfNew = pushIfNew)
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
            val flat = runCatching {
                DeviceOps.captureLayout(hdc, null).flat()
            }.getOrNull()
            UiReady.assertInApp(hdc, bundle, flat)
            val action = if (flat != null) {
                val safe = UiNav.contentSafeRect(flat)
                UiNav.scrollPageAction(dir, safe)
            } else {
                UiNav.scrollPageActionFallback(dir)
            }
            UiActions.perform(hdc, action, 10_000)
            // 拖完立刻看是否被系统手势打到桌面
            UiReady.assertInApp(hdc, bundle, pullBackOnce = false)
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
                    val raw = DeviceOps.captureLayout(hdc, null).flat()
                    UiReady.assertNotLockOrUnlock(hdc, raw)
                }.getOrNull() ?: return null
                if (!UiNav.anyMatch(flat, expect)) return null
                LayoutCache.put(hdc, flat)
                WorldMap.observePage(mapBundle, flat)
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
