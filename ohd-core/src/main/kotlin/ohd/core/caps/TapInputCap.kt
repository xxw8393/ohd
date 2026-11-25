package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.actions.LayoutCache
import ohd.core.actions.UiActions
import ohd.core.actions.WorldMap

/** 点一下。优先 `ohd go --grep`（认路+可选验标）；裸坐标仅在 tree/shot 已确认时用。 */
class TapCap : Capability {
    override val name = "tap"
    override val summary = "点击：坐标，或 --grep 认路（推荐走 ohd go）"
    override val usage = "ohd tap <x> <y> [--double] [--long] [--json]\n        ohd tap --grep <地标> …（等价 ohd go，见 ohd go --help）"
    override val example = "ohd tap 640 1200 --long"
    override val accepts: Set<String> = setOf(
        "ability", "any", "bundle", "double", "expect", "fresh", "grep", "index", "light", "long",
        "max-swipes", "scene", "scroll-find", "settle", "swipe-dir", "timeout", "type", "id",
    )

    override fun run(ctx: RunContext): Int {
        // --grep：委托 go，禁止「记着旧坐标硬点」
        if (ctx.options.str("grep") != null) {
            return GoCap().run(ctx)
        }
        val started = System.currentTimeMillis()
        val pos = ctx.options.positionals
        if (pos.size < 2) {
            throw OhdError(
                ExitCode.USAGE,
                "需要 x y，或改用 ohd go --grep <地标>",
                "示例：ohd go --grep 多实例测试 --expect 返回",
            )
        }
        val x = pos[0].toIntOrNull() ?: throw OhdError(ExitCode.USAGE, "x 必须是整数：${pos[0]}")
        val y = pos[1].toIntOrNull() ?: throw OhdError(ExitCode.USAGE, "y 必须是整数：${pos[1]}")
        val kind = when {
            ctx.options.bool("double") -> UiActions.Kind.DOUBLE_TAP
            ctx.options.bool("long") -> UiActions.Kind.LONG_PRESS
            else -> UiActions.Kind.TAP
        }
        val hdc = Hdcs.forDevice(ctx)
        val action = UiActions.Action(kind, listOf(x.toString(), y.toString()), "", 0)
        // iOS 没有 shell 通道，命令串只用于展示；实际注入由 perform 走 idb
        val cmd = UiActions.shellCommand(action, hdc.kind) ?: "idb ui ${kind.name.lowercase()} $x $y"
        Hdcs.session(ctx, "tap")
        val r = UiActions.perform(hdc, action, 15_000)
        val label = when (kind) {
            UiActions.Kind.DOUBLE_TAP -> "双击"
            UiActions.Kind.LONG_PRESS -> "长按"
            else -> "点击"
        }
        if (!r.ok) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "$label 失败：${r.detail}",
                "坐标越界？先用 ohd shot --grid 确认；iOS 需要 idb（pip3 install fb-idb）",
            )
        }
        if (ctx.out.jsonMode) {
            ctx.out.finish("tap", true, buildJsonObject {
                put("x", x); put("y", y); put("kind", kind.name.lowercase()); put("command", cmd)
            }, System.currentTimeMillis() - started)
        } else {
            ctx.out.info("$label ($x, $y)")
        }
        return ExitCode.OK
    }
}

/** 输入：文本 / 滑动 / 按键。比 tap 多参数，单独成能力避免 tap 的参数解析变复杂。 */
class InputCap : Capability {
    override val name = "input"
    override val summary = "输入文本 / 滑动 / 拖拽 / 按键"
    override val usage = """
        ohd input text <x> <y> <文本>
        ohd input text <文本>              （已聚焦处输入）
        ohd input swipe <x1> <y1> <x2> <y2> [速度]
        ohd input drag  <x1> <y1> <x2> <y2> [速度]
        ohd input key <Back|Home|Power|键值>
    """.trimIndent()

    override val example = "ohd input swipe 640 2000 640 800 500"

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val pos = ctx.options.positionals
        if (pos.size < 2) throw OhdError(ExitCode.USAGE, "需要子命令", usage)
        val sub = pos[0].lowercase()
        val args = pos.drop(1)

        val (kind, actionArgs) = when (sub) {
            "text", "input" -> {
                if (args.size >= 3 && args[0].toIntOrNull() != null && args[1].toIntOrNull() != null) {
                    UiActions.Kind.INPUT_TEXT to args
                } else {
                    UiActions.Kind.TEXT to listOf(args.joinToString(" "))
                }
            }
            "swipe" -> UiActions.Kind.SWIPE to args
            "drag" -> UiActions.Kind.DRAG to args
            "fling" -> UiActions.Kind.FLING to args
            "key", "keyevent" -> UiActions.Kind.KEY to args
            else -> throw OhdError(ExitCode.USAGE, "未知输入子命令：$sub", usage)
        }
        val a = UiActions.Action(kind, actionArgs, "", 0)
        val hdc = Hdcs.forDevice(ctx)
        val cmd = UiActions.shellCommand(a, hdc.kind) ?: "idb ui ${kind.name.lowercase()}"
        Hdcs.session(ctx, "input")
        val r = UiActions.perform(hdc, a, 20_000)
        if (!r.ok) throw OhdError(ExitCode.UNSUPPORTED, "输入失败：${r.detail}")
        // Back：多页缓存 + 世界地图弹栈
        if (kind == UiActions.Kind.KEY && actionArgs.firstOrNull().equals("Back", ignoreCase = true)) {
            val restored = LayoutCache.noteBack(hdc)
            val mapBundle = System.getenv("OHD_BUNDLE") ?: "com.tencent.compose"
            WorldMap.noteBack(mapBundle)
            if (!ctx.out.jsonMode && restored != null) {
                ctx.out.info("layout-cache：Back → 恢复页 $restored")
            }
        }
        if (kind == UiActions.Kind.SWIPE || kind == UiActions.Kind.FLING || kind == UiActions.Kind.DRAG) {
            LayoutCache.invalidateCurrent(hdc)
        }
        if (ctx.out.jsonMode) {
            ctx.out.finish("input", true, buildJsonObject {
                put("kind", kind.name.lowercase()); put("command", cmd)
            }, System.currentTimeMillis() - started)
        } else {
            ctx.out.info("已输入：$sub ${actionArgs.joinToString(" ").take(60)}")
        }
        return ExitCode.OK
    }
}
