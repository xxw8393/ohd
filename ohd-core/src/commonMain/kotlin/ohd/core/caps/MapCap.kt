package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.actions.LayoutCache
import ohd.core.actions.UiActions
import ohd.core.actions.UiNav
import ohd.core.actions.UiReady
import ohd.core.actions.WorldMap
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath

/**
 * 世界地图：查看 / 问路 / 存档 / 按计划导航。
 * 数据由 go/tree/Back 写入 `~/.ohd/worldmap/<bundle>.json`。
 */
class MapCap : Capability {
    override val name = "map"
    override val summary = "Demo 世界地图：页图 + item 滑次 + 存档问路"
    override val usage = """
        ohd map show [--bundle <包名>]
        ohd map path --to <地标> [--bundle <包名>]
        ohd map export [--out <文件.json>] [--bundle <包名>]
        ohd map clear [--bundle <包名>]
        ohd map clear-crashes [--bundle <包名>]
        ohd map prune [--max-pages N] [--bundle <包名>]
        ohd map clean [--bundle <包名>]
        ohd map go --to <地标> [--bundle <包名>] [--dry-run]
    """.trimIndent()
    override val example = "ohd map path --to \"GPU Texture\" --bundle com.tencent.compose"
    override val accepts: Set<String> = setOf("bundle", "dry-run", "out", "to", "max-pages")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val sub = ctx.options.positionals.firstOrNull()?.lowercase()
            ?: throw OhdError(ExitCode.USAGE, "需要子命令 show|path|export|clear|clear-crashes|prune|clean|go", usage)
        val bundle = ctx.options.str("bundle")
            ?: Env.getRaw("OHD_BUNDLE")
            ?: "com.tencent.compose"

        return when (sub) {
            "show" -> show(ctx, bundle, started)
            "path" -> pathCmd(ctx, bundle, started)
            "export" -> export(ctx, bundle, started)
            "clear" -> {
                WorldMap.clear(bundle)
                ctx.out.info("已清空世界地图：$bundle（${WorldMap.pathFor(bundle)}）")
                ExitCode.OK
            }
            "clean" -> {
                val (before, after) = WorldMap.cleanAndSave(bundle)
                ctx.out.info(
                    "已清洗世界地图：$before → $after 页（合并内容相同的重复快照、剔除手势噪声）" +
                        "\n  ${WorldMap.pathFor(bundle)}",
                )
                ExitCode.OK
            }
            "prune" -> {
                val (before, after) = WorldMap.pruneAndSave(bundle, ctx.options.int("max-pages", WorldMap.MAX_PAGES))
                ctx.out.info("已瘦身世界地图：$before → $after 页（${WorldMap.pathFor(bundle)}）")
                ExitCode.OK
            }
            "clear-crashes" -> {
                val n = WorldMap.load(bundle).crashes.size
                WorldMap.clearCrashes(bundle)
                ctx.out.info("已清除 $n 条 crash 禁令：$bundle")
                ExitCode.OK
            }
            "go" -> goPlan(ctx, bundle, started)
            else -> throw OhdError(ExitCode.USAGE, "未知子命令：$sub", usage)
        }
    }

    private fun show(ctx: RunContext, bundle: String, started: Long): Int {
        val doc = WorldMap.load(bundle)
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "map",
                true,
                buildJsonObject {
                    put("bundle", doc.bundle)
                    put("pages", doc.pages.size)
                    put("edges", doc.edges.size)
                    put("crashes", doc.crashes.size)
                    put("currentPageId", doc.currentPageId ?: "")
                    put("file", WorldMap.pathFor(bundle).toString())
                    put("summary", WorldMap.humanSummary(doc))
                },
                Env.nowMillis() - started,
            )
        } else {
            ctx.out.info("世界地图 ${WorldMap.pathFor(bundle)}")
            print(WorldMap.humanSummary(doc))
        }
        return ExitCode.OK
    }

    private fun pathCmd(ctx: RunContext, bundle: String, started: Long): Int {
        val to = ctx.options.str("to")
            ?: throw OhdError(ExitCode.USAGE, "需要 --to <地标>", example)
        val plan = WorldMap.planTo(bundle, to)
            ?: throw OhdError(ExitCode.ASSERT_FAILED, "地图里还没有「$to」", "先 go --scroll-find / tree 跑过含该文案的页")
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "map",
                true,
                buildJsonObject {
                    put("to", to)
                    put("targetPageId", plan.targetPageId)
                    put("summary", plan.summary)
                    put("steps", buildJsonArray {
                        plan.steps.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("kind", s.kind)
                                    put("detail", s.detail)
                                    s.swipes?.let { put("swipes", it) }
                                    s.pixels?.let { put("pixels", it) }
                                    s.label?.let { put("label", it) }
                                    s.pageId?.let { put("pageId", it) }
                                },
                            )
                        }
                    })
                },
                Env.nowMillis() - started,
            )
        } else {
            ctx.out.info("path ${plan.summary}")
            plan.steps.forEachIndexed { i, s ->
                ctx.out.info("  ${i + 1}. [${s.kind}] ${s.detail}")
            }
        }
        return ExitCode.OK
    }

    private fun export(ctx: RunContext, bundle: String, started: Long): Int {
        val doc = WorldMap.load(bundle)
        val out = ctx.options.str("out")?.let { OhdPath.of(it) }
            ?: OhdPath.of("ohd-evidence", "worldmap-${bundle.replace('.', '_')}.json")
        out.toAbsolutePath().parent?.let { fs.createDirectories(it) }
        fs.writeString(out, WorldMap.encode(doc) + "\n")
        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "map",
                true,
                buildJsonObject {
                    put("out", out.toAbsolutePath().toString())
                    put("pages", doc.pages.size)
                    put("edges", doc.edges.size)
                },
                Env.nowMillis() - started,
            )
        } else {
            ctx.out.info("已导出 ${out.toAbsolutePath()}（pages=${doc.pages.size} edges=${doc.edges.size}）")
        }
        return ExitCode.OK
    }

    private fun goPlan(ctx: RunContext, bundle: String, started: Long): Int {
        val to = ctx.options.str("to")
            ?: throw OhdError(ExitCode.USAGE, "需要 --to <地标>")
        val plan = WorldMap.planTo(bundle, to)
            ?: throw OhdError(ExitCode.ASSERT_FAILED, "地图里还没有「$to」")
        if (ctx.options.bool("dry-run")) {
            ctx.out.info("dry-run ${plan.summary}")
            plan.steps.forEach { ctx.out.info("  [${it.kind}] ${it.detail}") }
            return ExitCode.OK
        }
        if (!ctx.out.jsonMode) ctx.out.info("map go ${plan.summary}")

        val hdc = Hdcs.forDevice(ctx)
        Hdcs.session(ctx, "map-go")
        UiReady.ensure(hdc, null, bundle)

        var tapLabel: String? = null
        for (s in plan.steps) {
            when (s.kind) {
                "back" -> {
                    repeat(s.swipes ?: 1) {
                        UiActions.perform(
                            hdc,
                            UiActions.Action(UiActions.Kind.KEY, listOf("Back"), "", 0),
                            10_000,
                        )
                        LayoutCache.noteBack(hdc)
                        WorldMap.noteBack(bundle)
                        Env.sleepMillis(350)
                    }
                }
                "swipe" -> {
                    val n = (s.swipes ?: 1).coerceAtMost(3).coerceAtLeast(1)
                    repeat(n) { i ->
                        val flat = DeviceOps.captureLayout(hdc, null, bundle = bundle).flat()
                        UiReady.assertInApp(hdc, bundle, flat)
                        val action = UiNav.scrollPageAction("up", UiNav.contentSafeRect(flat))
                        UiActions.perform(hdc, action, 10_000)
                        UiReady.assertInApp(hdc, bundle, pullBackOnce = false)
                        if (i < n - 1) Env.sleepMillis(50)
                    }
                    LayoutCache.invalidateCurrent(hdc)
                    Env.sleepMillis(200)
                }
                "tap" -> tapLabel = s.label ?: to
            }
        }
        val label = tapLabel ?: to
        val flat = DeviceOps.captureLayout(hdc, null, bundle = bundle).flat().let { UiReady.assertNotLockOrUnlock(hdc, it) }
        LayoutCache.put(hdc, flat)
        WorldMap.observePage(bundle, flat)
        when (val r = UiNav.resolve(flat, label, clickableOnly = true)) {
            is UiNav.Resolve.Ok -> {
                val ok = UiActions.perform(
                    hdc,
                    UiActions.Action(
                        UiActions.Kind.TAP,
                        listOf(r.pick.x.toString(), r.pick.y.toString()),
                        "",
                        0,
                    ),
                    15_000,
                )
                if (!ok.ok) throw OhdError(ExitCode.UNSUPPORTED, "点击失败：${ok.detail}")
                ctx.out.info(
                    "map go OK：点击 (${r.pick.x},${r.pick.y}) 「${r.pick.node.label().take(40)}」 " +
                        "${Env.nowMillis() - started}ms",
                )
                return ExitCode.OK
            }
            is UiNav.Resolve.Ambiguous -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "地图导航后歧义：${r.count} 个「$label」",
            )
            else -> throw OhdError(
                ExitCode.ASSERT_FAILED,
                "地图导航后未见「$label」",
                "atlas 滑次可能偏少；ohd go --scroll-find 再学一轮",
            )
        }
    }
}
