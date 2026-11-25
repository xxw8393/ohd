package ohd.core.actions

import ohd.core.DeviceKind
import ohd.core.Hdc
import ohd.core.IdbInjector

import kotlinx.serialization.json.JsonElement

/**
 * UI 动作脚本（ohd 自有 DSL，行式、可注释、可靠 review）。
 * 同一套动作同时服务于 `ohd tap` / `ohd input` / `ohd replay`，避免三处各写一遍坐标逻辑。
 *
 * 实测命令映射（uitest uiInput）：
 *   click x y | doubleClick x y | longClick x y
 *   swipe/drag x1 y1 x2 y2 [velocity 200..40000]
 *   fling x1 y1 x2 y2 [velocity] [stepLength]
 *   keyEvent Back|Home|Power|<keyId>
 *   inputText x y <text>   /   text <text>（焦点处）
 */
object UiActions {

    enum class Kind {
        TAP, DOUBLE_TAP, LONG_PRESS,
        SWIPE, DRAG, FLING, DIRC_FLING,
        KEY, INPUT_TEXT, TEXT,
        SLEEP, SHOT, TREE, WAIT_TEXT,
        LAUNCH,
    }

    data class Action(
        val kind: Kind,
        val args: List<String>,
        val raw: String,
        val lineNo: Int,
        val id: String? = null,
        val text: String? = null,
        val type: String? = null,
        val toId: String? = null,
        val toText: String? = null,
        val toType: String? = null,
    ) {
        val rest: String get() = args.joinToString(" ")
        /** 录制时点中的控件。id 和文本都空才允许按原坐标回放。 */
        val hasIdentity: Boolean
            get() = !id.isNullOrBlank() || !text.isNullOrBlank() || !toId.isNullOrBlank() || !toText.isNullOrBlank()
    }

    private val ALIASES = mapOf(
        "tap" to Kind.TAP, "click" to Kind.TAP,
        "doubletap" to Kind.DOUBLE_TAP, "doubleclick" to Kind.DOUBLE_TAP, "dbltap" to Kind.DOUBLE_TAP,
        "longpress" to Kind.LONG_PRESS, "longclick" to Kind.LONG_PRESS, "long" to Kind.LONG_PRESS,
        "swipe" to Kind.SWIPE, "drag" to Kind.DRAG, "fling" to Kind.FLING,
        "dircfling" to Kind.DIRC_FLING,
        "key" to Kind.KEY, "keyevent" to Kind.KEY,
        "inputtext" to Kind.INPUT_TEXT, "input" to Kind.INPUT_TEXT,
        "text" to Kind.TEXT,
        "sleep" to Kind.SLEEP, "wait" to Kind.SLEEP,
        "shot" to Kind.SHOT, "screenshot" to Kind.SHOT,
        "tree" to Kind.TREE,
        "waittext" to Kind.WAIT_TEXT, "wait-text" to Kind.WAIT_TEXT,
        "launch" to Kind.LAUNCH,
    )

    private val POINTER = setOf(
        Kind.TAP, Kind.DOUBLE_TAP, Kind.LONG_PRESS,
        Kind.SWIPE, Kind.DRAG, Kind.FLING,
    )

    private val IDENTITY_KEYS = setOf("id", "text", "type", "toid", "totext", "totype")

    class ScriptError(message: String) : Exception(message)

    fun parseScript(text: String): List<Action> {
        val out = mutableListOf<Action>()
        text.lines().forEachIndexed { i, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"), limit = 2)
            val head = parts[0].lowercase()
            val kind = ALIASES[head] ?: throw ScriptError("第 ${i + 1} 行：未知动作「$head」（$line）")
            val rest = if (parts.size > 1) parts[1] else ""
            val tokens = when (kind) {
                Kind.KEY, Kind.TEXT, Kind.WAIT_TEXT -> if (rest.isEmpty()) emptyList() else listOf(rest)
                else -> tokenize(rest, i + 1)
            }
            var id: String? = null
            var text: String? = null
            var type: String? = null
            var toId: String? = null
            var toText: String? = null
            var toType: String? = null
            val args = mutableListOf<String>()
            for (tok in tokens) {
                val ident = if (kind in POINTER) identityOf(tok) else null
                if (ident == null) {
                    args += tok
                } else when (ident.first) {
                    "id" -> id = ident.second
                    "text" -> text = ident.second
                    "type" -> type = ident.second
                    "toid" -> toId = ident.second
                    "totext" -> toText = ident.second
                    "totype" -> toType = ident.second
                }
            }
            validate(kind, args, i + 1, line)
            out += Action(kind, args, line, i + 1, id, text, type, toId, toText, toType)
        }
        return out
    }

    private fun validate(kind: Kind, args: List<String>, lineNo: Int, line: String) {
        fun need(n: Int, hint: String) {
            if (args.size < n) throw ScriptError("第 $lineNo 行：${kind.name} 参数不足，需要 $hint（$line）")
        }
        fun nums(from: Int, to: Int, hint: String) {
            for (i in from until minOf(to, args.size)) {
                args[i].toIntOrNull() ?: throw ScriptError("第 $lineNo 行：第 ${i + 1} 个参数不是整数（$hint）")
            }
        }
        when (kind) {
            Kind.TAP, Kind.DOUBLE_TAP, Kind.LONG_PRESS -> {
                need(2, "x y")
                nums(0, 2, "坐标")
            }
            Kind.SWIPE, Kind.DRAG -> {
                need(4, "x1 y1 x2 y2 [velocity]")
                nums(0, 5, "坐标/速度")
            }
            Kind.FLING -> {
                need(4, "x1 y1 x2 y2 [velocity] [stepLength]")
                nums(0, 6, "坐标/速度/步长")
            }
            Kind.DIRC_FLING -> {
                need(1, "direction(0..3) [velocity] [stepLength]")
                nums(0, 3, "方向/速度/步长")
            }
            Kind.INPUT_TEXT -> {
                need(3, "x y text")
                nums(0, 2, "坐标")
            }
            Kind.KEY, Kind.TEXT, Kind.WAIT_TEXT -> need(1, "一个参数")
            Kind.SLEEP -> need(1, "时长，如 500ms / 2s")
            Kind.SHOT, Kind.TREE -> Unit
            Kind.LAUNCH -> {
                need(2, "bundle ability")
                val ok = args.all { it.matches(Regex("""[A-Za-z0-9_.]+""")) }
                if (!ok) throw ScriptError("第 $lineNo 行：包名或 Ability 含非法字符（$line）")
            }
        }
    }

    /** 按空白切分，双引号内保留空格。 */
    private fun tokenize(rest: String, lineNo: Int): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quote = false
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            when {
                c == '\\' && quote && i + 1 < rest.length -> {
                    sb.append(rest[i + 1])
                    i += 2
                }
                c == '"' -> {
                    quote = !quote
                    i++
                }
                c.isWhitespace() && !quote -> {
                    if (sb.isNotEmpty()) {
                        out += sb.toString()
                        sb.clear()
                    }
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (quote) throw ScriptError("第 $lineNo 行：引号未闭合（$rest）")
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    private fun identityOf(token: String): Pair<String, String>? {
        val eq = token.indexOf('=')
        if (eq <= 0) return null
        val key = token.substring(0, eq)
        if (key !in IDENTITY_KEYS) return null
        val value = token.substring(eq + 1).takeIf { it.isNotBlank() } ?: return null
        return key to value
    }

    data class Executed(val ok: Boolean, val detail: String)

    /**
     * 执行一个 UI 动作。
     *
     * iOS 没有 shell 注入通道，走 [IdbInjector]；OHOS/Android 仍用 `uitest uiInput` / `input`。
     * `shellCommand` 对 iOS 返回 null 的语义保持不变——那条路径确实不存在。
     */
    fun perform(hdc: Hdc, a: Action, timeoutMs: Long = 20_000): Executed {
        if (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE) {
            val exe = IdbInjector.locate(null)
                ?: return Executed(false, "未找到 idb：pip3 install fb-idb 或设置 OHD_IDB")
            val udid = hdc.connectKey ?: return Executed(false, "缺少设备 UDID，无法注入")
            val inj = IdbInjector(exe, udid, timeoutMs)
            val r = when (a.kind) {
                Kind.TAP -> inj.tap(a.args[0].toInt(), a.args[1].toInt())
                // iOS 没有独立的长按/双击命令，用 duration 与两次 tap 近似
                Kind.LONG_PRESS -> inj.tap(a.args[0].toInt(), a.args[1].toInt(), 1000)
                Kind.DOUBLE_TAP -> inj.tap(a.args[0].toInt(), a.args[1].toInt()).also {
                    Thread.sleep(80)
                    inj.tap(a.args[0].toInt(), a.args[1].toInt())
                }
                Kind.SWIPE -> inj.swipe(
                    a.args[0].toInt(), a.args[1].toInt(),
                    a.args[2].toInt(), a.args[3].toInt(),
                    a.args.getOrNull(4)?.toIntOrNull() ?: 300,
                )
                Kind.DRAG, Kind.FLING, Kind.DIRC_FLING -> inj.swipe(
                    a.args[0].toInt(), a.args[1].toInt(),
                    a.args[2].toInt(), a.args[3].toInt(),
                    if (a.kind == Kind.DRAG) 800 else 150,
                )
                Kind.KEY -> {
                    // key 的实参既可能是 HID 码也可能是按钮名；iOS 只有 Home 这一个硬件键
                    val k = a.args.firstOrNull().orEmpty()
                    when {
                        k.toIntOrNull() != null -> inj.key(k.toInt())
                        k.equals("Home", ignoreCase = true) || k.equals("HOME", ignoreCase = true) ->
                            inj.button("HOME")
                        else -> IdbInjector.Exec(false, "iOS 没有 $k 这个按键（可用 Home）")
                    }
                }
                Kind.TEXT -> inj.text(a.rest)
                Kind.INPUT_TEXT -> inj.text(a.args.drop(2).joinToString(" "))
                // SLEEP/SHOT/TREE/WAIT_TEXT/LAUNCH 不是手势，由各自的能力处理，注入层不参与
                else -> return Executed(false, "iOS 注入不支持该动作：${a.kind}")
            }
            return Executed(r.ok, r.out.take(240))
        }
        val cmd = shellCommand(a, hdc.kind) ?: return Executed(false, "无对应命令（${hdc.kind.label} 无此动作）")
        val r = hdc.shell(cmd, timeoutMs)
        return Executed(r.ok, r.text().take(240))
    }

    /** 生成设备侧 shell 命令（不含 hdc 前缀）。 */
    /**
     * 生成设备侧命令。[kind] 决定用 OHOS 的 `uitest uiInput` 还是 Android 的 `input`。
     *
     * Android 的天然缺口（返回 null，调用方报「无对应命令」而不是假装支持）：
     * `doubleClick` / `dircFling` 没有直接等价；`longClick` 用 `swipe` 原地 1s 近似。
     */
    fun shellCommand(a: Action, kind: DeviceKind = DeviceKind.OHOS): String? = when (kind) {
        // 模拟器没有事件注入通道，一律返回 null（调用方本就按「无对应命令」处理）
        DeviceKind.IOS, DeviceKind.IOS_DEVICE -> null
        DeviceKind.OHOS -> when (a.kind) {
            Kind.TAP -> "uitest uiInput click ${a.args[0]} ${a.args[1]}"
            Kind.DOUBLE_TAP -> "uitest uiInput doubleClick ${a.args[0]} ${a.args[1]}"
            Kind.LONG_PRESS -> "uitest uiInput longClick ${a.args[0]} ${a.args[1]}"
            Kind.SWIPE -> "uitest uiInput swipe ${a.args.joinToString(" ")}"
            Kind.DRAG -> "uitest uiInput drag ${a.args.joinToString(" ")}"
            Kind.FLING -> "uitest uiInput fling ${a.args.joinToString(" ")}"
            Kind.DIRC_FLING -> "uitest uiInput dircFling ${a.args.joinToString(" ")}"
            Kind.KEY -> "uitest uiInput keyEvent ${a.args[0]}"
            Kind.INPUT_TEXT -> "uitest uiInput inputText ${a.args[0]} ${a.args[1]} ${quote(a.args.drop(2).joinToString(" "))}"
            Kind.TEXT -> "uitest uiInput text ${quote(a.rest)}"
            Kind.SLEEP, Kind.SHOT, Kind.TREE, Kind.WAIT_TEXT, Kind.LAUNCH -> null
        }
        DeviceKind.ANDROID -> androidShellCommand(a)
    }

    private fun androidShellCommand(a: Action): String? = when (a.kind) {
        Kind.TAP -> "input tap ${a.args[0]} ${a.args[1]}"
        // Android 没有 doubleClick，连发两次 tap
        Kind.DOUBLE_TAP -> "input tap ${a.args[0]} ${a.args[1]}; input tap ${a.args[0]} ${a.args[1]}"
        // 原地滑动 1s 等价于长按
        Kind.LONG_PRESS -> "input swipe ${a.args[0]} ${a.args[1]} ${a.args[0]} ${a.args[1]} 1000"
        Kind.SWIPE -> androidSwipe(a.args, 300)
        Kind.DRAG -> androidSwipe(a.args, 800)
        Kind.FLING -> androidSwipe(a.args, 100)
        Kind.DIRC_FLING -> null
        Kind.KEY -> "input keyevent ${androidKey(a.args[0])}"
        Kind.INPUT_TEXT -> "input text ${androidText(a.args.drop(2).joinToString(" "))}"
        Kind.TEXT -> "input text ${androidText(a.rest)}"
        Kind.SLEEP, Kind.SHOT, Kind.TREE, Kind.WAIT_TEXT, Kind.LAUNCH -> null
    }

    private fun androidSwipe(args: List<String>, defaultMs: Int): String {
        val pts = args.mapNotNull { it.toIntOrNull() }
        if (pts.size < 4) return "input swipe 500 1500 500 500 $defaultMs"
        val dur = pts.getOrNull(4) ?: defaultMs
        return "input swipe ${pts[0]} ${pts[1]} ${pts[2]} ${pts[3]} $dur"
    }

    /** OHOS 用名称（Power/Home/Back），Android 要数字 keycode。 */
    private fun androidKey(name: String): String {
        val n = name.trim().lowercase()
        return when (n) {
            "power" -> "26"
            "home" -> "3"
            "back" -> "4"
            "menu" -> "82"
            "enter" -> "66"
            "volumeup", "volume_up" -> "24"
            "volumedown", "volume_down" -> "25"
            "wakeup" -> "224"
            "sleep" -> "223"
            else -> name.trim()
        }
    }

    /** `input text` 用 %s 表示空格；其余 shell 元字符按原样交给设备端。 */
    private fun androidText(s: String): String = quote(s.trim().replace(" ", "%s"))

    /** 设备侧 shell 需要转义：单引号包裹，内部单引号用 '\'' 断开。 */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun toJson(a: Action): JsonElement = kotlinx.serialization.json.JsonPrimitive(
        "${a.kind.name.lowercase()}(${a.rest})",
    )
}
