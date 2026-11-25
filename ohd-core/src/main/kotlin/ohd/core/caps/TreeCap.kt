package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.RunContext
import ohd.core.actions.LayoutCache
import ohd.core.actions.WorldMap
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.LayoutQuery

/** 读节点树：dumpLayout → 解析 → 查询 → 给中心点（下一步点击直接用）。 */
class TreeCap : Capability {
    override val name = "tree"
    override val summary = "读取节点树：按文本/类型/id 查询，输出 bounds 与点击中心点"
    override val usage = "ohd tree [--grep <text>] [--type <T>] [--id <ID>] [--clickable] [--all] [--rich] [--depth N] [--limit N | --max N（旧名）] [--bundle <包名>] [--json]"
    override val example = "ohd tree --grep 设置 --clickable --rich --limit 5 --json"
    override val accepts: Set<String> = setOf(
        "all", "bundle", "clickable", "depth", "grep", "id", "limit", "max", "rich", "type",
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "tree")
        val capture = DeviceOps.captureLayout(hdc, session, rich = ctx.options.bool("rich"))
        val flat = capture.flat()
        // 供随后 go/wait 缓存命中，避免同页再 dump
        LayoutCache.put(hdc, flat)
        val mapBundle = ctx.options.str("bundle")
            ?: System.getenv("OHD_BUNDLE")
            ?: "com.tencent.compose"
        WorldMap.observePage(mapBundle, flat)

        val grep = ctx.options.str("grep")
        val type = ctx.options.str("type")
        val id = ctx.options.str("id")
        val clickableOnly = ctx.options.bool("clickable")
        val visibleOnly = !ctx.options.bool("all")
        // --max 的语义是「最多几条」，却常被当成深度读；主名改为 --limit，--max 保留兼容。
        val limit = if (ctx.options.values.containsKey("limit")) ctx.options.int("limit", 400)
        else ctx.options.int("max", 400)
        // --depth：只看到第 N 层，用来先看整体结构，避免一屏几百行
        val depth = ctx.options.int("depth", -1)

        val filtered = LayoutQuery.filter(flat, grep, type, id, clickableOnly, visibleOnly)
            .let { list -> if (depth >= 0) list.filter { it.depth <= depth } else list }
        val shown = filtered.take(limit)

        if (filtered.isEmpty()) {
            ctx.out.note("NO_MATCH", "warn", "没有匹配节点（共 ${flat.size} 个节点）")
            ctx.out.warn("没有匹配节点（全树 ${flat.size} 个节点）")
        } else if (flat.none { !it.text.isNullOrBlank() }) {
            ctx.out.note(
                "NO_TEXT_NODE",
                "warn",
                "本页无文本节点（Compose 自绘文本不暴露在 accessibility 树），请用 ohd shot --grid 目视取坐标",
            )
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("totalNodes", flat.size)
                put("matched", filtered.size)
                put("nodes", buildJsonArray {
                    shown.forEach { n ->
                        add(buildJsonObject {
                            put("text", n.text ?: "")
                            put("id", n.id ?: "")
                            put("type", n.type ?: "")
                            put("bounds", n.bounds?.toString() ?: "")
                            put("centerX", n.bounds?.centerX ?: -1)
                            put("centerY", n.bounds?.centerY ?: -1)
                            put("clickable", n.clickable)
                            put("depth", n.depth)
                        })
                    }
                })
            }
            ctx.out.finish("tree", filtered.isNotEmpty(), data, System.currentTimeMillis() - started)
        } else {
            ctx.out.table(
                listOf("节点", "类型", "bounds", "中心", "可点"),
                shown.map { n ->
                    listOf(
                        "  ".repeat(n.depth.coerceAtMost(6)) + label(n),
                        n.type ?: "-",
                        n.bounds?.toString() ?: "-",
                        n.bounds?.center ?: "-",
                        if (n.clickable) "Y" else "",
                    )
                },
            )
            println()
            ctx.out.kv(
                listOf(
                    "节点总数" to flat.size.toString(),
                    "匹配" to filtered.size.toString(),
                    "显示" to (if (shown.size < filtered.size) "${shown.size}（--limit $limit 截断，用 --limit 调大）" else shown.size.toString()) +
                        (if (depth >= 0) "，--depth $depth 过滤" else ""),
                    "布局文件" to (capture.file?.toString() ?: "-"),
                ),
            )
            // 单一命中时直接给可直接用于点击的坐标
            shown.singleOrNull()?.bounds?.let {
                println()
                ctx.out.info("点击坐标：${it.centerX} ${it.centerY}")
            }
        }
        if (filtered.isEmpty() && grep != null && grep.any { it in "|\\()[]*+?^$" }) {
            // 实测坑：`--grep "a\|b"` 会被当成字面量去找，返回「匹配 0」却不说为什么。
            ctx.out.info("! --grep 按子串匹配，不是正则；含特殊字符时请拆成多次查询")
        }
        return if (filtered.isEmpty() && (grep != null || type != null || id != null)) {
            ExitCode.ASSERT_FAILED
        } else {
            ExitCode.OK
        }
    }

    private fun label(n: LayoutNode): String {
        // 节点文本可能含换行/制表符，会把表格撑断，统一压成单行
        val l = n.label().replace(Regex("[\\r\\n\\t]+"), " ").replace(Regex(" {2,}"), " ").trim()
        return if (l.isBlank()) "(" + (n.type ?: "node") + ")" else l.take(60)
    }
}
