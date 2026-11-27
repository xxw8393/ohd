package ohd.core.actions

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把 `uitest uiRecord read` 的内容转成 ohd 脚本。
 * 包名和 Ability 取第一条带得上的事件；事件都空时用录制开头的 `Current ForAbility`。
 * 脚本开头写 `launch`，回放据此冷启动入口。首页上先滑动再点击时，滑动经常没有包名。
 *
 * 字段名以 API 24 真机为准（`uirecord-fling.json`）：坐标和控件都是字符串，
 * `fingerNumber` 是字符串，`duration` 是纳秒数字。
 */
object RecordScript {
    class Rejected(message: String) : Exception(message)

    data class Translated(
        val script: String,
        val steps: Int,
        val bundle: String,
        val ability: String,
        val outline: List<String> = emptyList(),
    )

    fun stepTable(outline: List<String>): String =
        outline.mapIndexed { i, label -> "${i + 1} $label" }.joinToString(" / ")

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 起点落在左右边缘这个距离以内，并且主要是水平往里划，记成返回。 */
    private const val EDGE_PX = 40

    data class Screen(val width: Int, val height: Int)

    private val WINDOW_BOUNDS = Regex(
        """windowBounds\s*:\s*\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)""",
    )

    private val FOREGROUND = Regex(
        """Current ForAbility\s*:\s*([A-Za-z0-9_.]+)\s*,\s*([A-Za-z0-9_.]+)""",
    )

    /** `Current ForAbility :com.example.app, EntryAbility`，录制开头 uitest 打在终端上。 */
    fun parseForeground(line: String): Pair<String, String>? {
        val m = FOREGROUND.find(line) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** `windowBounds : (left,top,right,bottom)`，录制开头 uitest 打在终端上。 */
    fun parseWindowBounds(line: String): Screen? {
        val m = WINDOW_BOUNDS.find(line) ?: return null
        val width = m.groupValues[3].toInt() - m.groupValues[1].toInt()
        val height = m.groupValues[4].toInt() - m.groupValues[2].toInt()
        if (width <= 0 || height <= 0) return null
        return Screen(width, height)
    }

    /** 左缘往右、右缘往左，且水平位移大于垂直位移。底边往上划不在这里。 */
    fun edgeBack(x1: Int, y1: Int, x2: Int, y2: Int, screenWidth: Int): Boolean {
        if (screenWidth <= EDGE_PX) return false
        val dx = x2 - x1
        val dy = y2 - y1
        if (abs(dx) <= abs(dy)) return false
        val fromLeft = x1 <= EDGE_PX
        val fromRight = x1 >= screenWidth - EDGE_PX
        return (fromRight && dx < 0) || (fromLeft && dx > 0)
    }

    private val OPS = mapOf(
        "click" to "tap",
        "doubleclick" to "doubletap",
        "longclick" to "longpress",
        "swipe" to "swipe",
        "drag" to "drag",
        "fling" to "fling",
    )

    /** [bundle] / [ability] 由调用方显式给出时优先于录制里的字段。 */
    fun translate(raw: String, bundle: String? = null, ability: String? = null): Translated {
        val events = parseEvents(raw)
        if (events.isEmpty()) throw Rejected("录制内容为空")
        val launch = resolveLaunch(events, raw, bundle, ability)
        val bundle = launch.first
        val ability = launch.second
        if (bundle.isBlank() || ability.isBlank()) {
            throw Rejected("录制里没有包名或 Ability，不能冷启动应用入口")
        }
        val screenWidth = raw.lineSequence().firstNotNullOfOrNull { parseWindowBounds(it) }?.width
        val lines = mutableListOf<String>()
        lines += "# ohd record。回放会先冷启动 $bundle 的 $ability，再执行下面的步骤。"
        lines += "# 左右边缘往里的水平滑动写成 key Back。滑动按坐标回放，点击按控件定位。"
        lines += "launch $bundle $ability"
        val outline = mutableListOf<String>()
        var steps = 0
        for (event in events) {
            val line = gesture(event, screenWidth)
            if (line.executable) {
                steps++
                line.label?.let { outline += it }
            }
            lines += line.text
        }
        if (steps == 0) throw Rejected("没有可回放的操作")
        return Translated(lines.joinToString("\n") + "\n", steps, bundle, ability, outline)
    }

    /**
     * 显式参数优先。否则用第一条带包名的事件，Ability 可以出现在同一次或另一次事件上。
     * 事件全空时用 `Current ForAbility`。首页滑动经常不带包名，不能因此丢掉后面的点击。
     */
    private fun resolveLaunch(
        events: List<JsonObject>,
        raw: String,
        bundleArg: String?,
        abilityArg: String?,
    ): Pair<String, String> {
        val fromLine = raw.lineSequence().firstNotNullOfOrNull { parseForeground(it) }
        val withBundle = events.firstOrNull { !str(it, "BUNDLE").isNullOrBlank() }
        val bundle = bundleArg?.takeIf { it.isNotBlank() }
            ?: withBundle?.let { str(it, "BUNDLE") }
            ?: fromLine?.first.orEmpty()
        val abilityFromEvents = withBundle?.let { str(it, "ABILITY") }?.takeIf { it.isNotBlank() }
            ?: events.firstNotNullOfOrNull { str(it, "ABILITY")?.takeIf { name -> name.isNotBlank() } }
        val abilityFromLine = fromLine?.second?.takeIf { fromLine.first == bundle || bundle.isBlank() }
        val ability = abilityArg?.takeIf { it.isNotBlank() }
            ?: abilityFromEvents
            ?: abilityFromLine.orEmpty()
        return bundle to ability
    }

    private data class Gesture(val text: String, val executable: Boolean, val label: String? = null)

    private fun gesture(event: JsonObject, screenWidth: Int?): Gesture {
        val op = str(event, "OP_TYPE").orEmpty()
        val fingers = str(event, "fingerNumber")?.toIntOrNull() ?: 1
        if (fingers != 1) return Gesture("# 跳过：${fingers} 指 $op", false)
        val verb = OPS[op.lowercase()] ?: return Gesture("# 跳过：未支持的操作 $op", false)
        val finger = (event["fingerList"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return Gesture("# 跳过：$op 没有落点", false)
        val x1 = coord(finger, "X_POSI") ?: return Gesture("# 跳过：$op 没有起点", false)
        val y1 = coord(finger, "Y_POSI") ?: return Gesture("# 跳过：$op 没有起点", false)
        val moving = verb == "swipe" || verb == "drag" || verb == "fling"
        if (moving) {
            val x2 = coord(finger, "X2_POSI") ?: return Gesture("# 跳过：$op 没有终点", false)
            val y2 = coord(finger, "Y2_POSI") ?: return Gesture("# 跳过：$op 没有终点", false)
            if (screenWidth != null && edgeBack(x1, y1, x2, y2, screenWidth)) {
                return Gesture("# 边缘返回 $x1,$y1 → $x2,$y2\nkey Back", true, "返回")
            }
            // 滑动手指下面的按钮不是这次操作的目标，回放按坐标。
            val parts = mutableListOf(verb, x1.toString(), y1.toString(), x2.toString(), y2.toString())
            speed(str(event, "VELO") ?: str(finger, "VELO"))?.let { parts += it.toString() }
            return Gesture(parts.joinToString(" "), true, "滑动")
        }
        // 终端打印的 “to Widget” 是 W2。W1 经常是起点上的空容器，不能拿来当点击目标。
        val parts = mutableListOf(verb, x1.toString(), y1.toString())
        val hit = namedWidget(finger, "W2") ?: namedWidget(finger, "W1")
        hit?.let { appendWidget(parts, it, "") }
        return Gesture(parts.joinToString(" "), true, clickLabel(verb, hit))
    }

    private fun clickLabel(verb: String, widget: Widget?): String {
        val name = widget?.text?.takeIf { it.isNotBlank() } ?: widget?.id?.takeIf { it.isNotBlank() }
        val head = when (verb) {
            "doubletap" -> "双击"
            "longpress" -> "长按"
            else -> "点"
        }
        return if (name == null) head else "$head $name"
    }

    private data class Widget(val id: String?, val text: String?, val type: String?)

    /** 没有 id、也没有文本的类型（CustomFrameNode、__Common__）不构成身份。 */
    private fun namedWidget(finger: JsonObject, prefix: String): Widget? {
        val id = str(finger, "${prefix}_ID")
        val text = str(finger, "${prefix}_Text")
        if (id.isNullOrBlank() && text.isNullOrBlank()) return null
        val type = str(finger, "${prefix}_Type")?.takeIf { it != "__Common__" }
        return Widget(id, text, type)
    }

    private fun appendWidget(parts: MutableList<String>, widget: Widget, prefix: String) {
        assign("${prefix}id", widget.id)?.let { parts += it }
        assign("${prefix}text", widget.text)?.let { parts += it }
        assign("${prefix}type", widget.type)?.let { parts += it }
    }

    private fun speed(raw: String?): Int? {
        val v = raw?.toDoubleOrNull()?.roundToInt() ?: return null
        return v.takeIf { it in 200..40_000 }
    }

    private fun assign(key: String, value: String?): String? {
        val v = value?.takeIf { it.isNotBlank() && it != "__Common__" } ?: return null
        if (key == "type" && v == "__Common__") return null
        val plain = v.all { it.isLetterOrDigit() || it in "._:-" }
        if (plain) return "$key=$v"
        val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        return "$key=\"$escaped\""
    }

    private fun coord(obj: JsonObject, key: String): Int? =
        str(obj, key)?.toDoubleOrNull()?.roundToInt()

    private fun str(obj: JsonObject, key: String): String? {
        val el = obj[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun parseEvents(raw: String): List<JsonObject> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        runCatching { json.parseToJsonElement(trimmed) }.getOrNull()?.let { el ->
            return when (el) {
                is JsonArray -> el.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(el)
                else -> emptyList()
            }
        }
        val out = mutableListOf<JsonObject>()
        var i = 0
        while (i < trimmed.length) {
            while (i < trimmed.length && trimmed[i].isWhitespace()) i++
            if (i >= trimmed.length) break
            if (trimmed[i] != '{') {
                i++
                continue
            }
            val end = jsonEnd(trimmed, i) ?: break
            val obj = runCatching { json.parseToJsonElement(trimmed.substring(i, end)) as JsonObject }.getOrNull()
            if (obj != null) out += obj
            i = end
        }
        return out
    }

    private fun jsonEnd(text: String, start: Int): Int? {
        var depth = 0
        var quote = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (quote) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> quote = false
                }
                continue
            }
            when (c) {
                '"' -> quote = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return null
    }
}
