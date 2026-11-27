package ohd.core.parsers

import ohd.platform.OhdPath
import ohd.platform.fs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 屏幕矩形。[left,top][right,bottom] 是 OHOS uitest 的原生表示。 */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val center: String get() = "$centerX,$centerY"

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    companion object {
        private val RE = Regex("""\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]\s*\[\s*(-?\d+)\s*,\s*(-?\d+)\s*\]""")

        fun parse(text: String?): Rect? {
            if (text.isNullOrBlank()) return null
            if (text.contains("null")) return null
            val m = RE.find(text) ?: return null
            val (l, t, r, b) = m.destructured
            return Rect(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
    }
}

data class LayoutNode(
    val text: String?,
    val id: String?,
    val type: String?,
    val description: String?,
    val bounds: Rect?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val bundleName: String?,
    val depth: Int,
    /**
     * 是否可滚动容器（dump 的 `scrollable` 属性）。
     *
     * 用来把布局树切成「静态骨架」与「滚动内容」：滚动子树里的元素会随滑动位移，
     * 不能参与页面身份判定，其坐标也不能跨轮复用。
     */
    val scrollable: Boolean = false,
    val children: List<LayoutNode> = emptyList(),
) {
    fun flatten(): List<LayoutNode> = buildList {
        add(this@LayoutNode)
        children.forEach { addAll(it.flatten()) }
    }

    fun label(): String = when {
        !text.isNullOrBlank() -> text
        !description.isNullOrBlank() -> "($description)"
        !id.isNullOrBlank() -> "#$id"
        else -> ""
    }
}

/**
 * uitest dumpLayout 输出解析。
 * 兼容两种顶层（对象/数组）与三种属性值（字符串/数字/布尔），
 * 因为不同 API 版本对 clickable/enabled 的编码不一致（实测 API 24 为 "true"/"false" 字符串）。
 */
object LayoutParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): List<LayoutNode> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        return when (root) {
            is JsonArray -> root.mapNotNull { toNode(it, 0) }
            is JsonObject -> listOfNotNull(toNode(root, 0))
            else -> emptyList()
        }
    }

    fun parseFile(path: OhdPath): List<LayoutNode> = parse(fs.readString(path))

    private fun toNode(el: JsonElement, depth: Int): LayoutNode? {
        val obj = el as? JsonObject ?: return null
        val attrs = obj["attributes"] as? JsonObject
        val children = (obj["children"] as? JsonArray)?.mapNotNull { toNode(it, depth + 1) } ?: emptyList()
        if (attrs == null) {
            return if (children.isEmpty()) null
            else LayoutNode(
                text = null, id = null, type = null, description = null, bounds = null,
                clickable = false, enabled = true, visible = true,
                bundleName = null, depth = depth, children = children,
            )
        }
        return LayoutNode(
            text = str(attrs, "text")?.takeIf { it.isNotBlank() },
            id = str(attrs, "accessibilityId") ?: str(attrs, "id"),
            type = str(attrs, "type"),
            description = str(attrs, "description")?.takeIf { it.isNotBlank() }
                ?: str(attrs, "hint")?.takeIf { it.isNotBlank() },
            bounds = Rect.parse(str(attrs, "bounds")),
            clickable = bool(attrs, "clickable"),
            enabled = bool(attrs, "enabled"),
            visible = bool(attrs, "visible"),
            scrollable = bool(attrs, "scrollable"),
            bundleName = str(attrs, "bundleName"),
            depth = depth,
            children = children,
        )
    }

    private fun str(obj: JsonObject, key: String): String? {
        val el = obj[key] ?: return null
        return when (el) {
            is JsonPrimitive -> el.content
            else -> null
        }
    }

    private fun bool(obj: JsonObject, key: String): Boolean {
        val v = str(obj, key) ?: return false
        return v.equals("true", ignoreCase = true) || v == "1"
    }
}

/** 节点查询：文本优先（含 description 兜底），其余精确匹配。 */
object LayoutQuery {
    fun filter(
        nodes: List<LayoutNode>,
        text: String? = null,
        type: String? = null,
        id: String? = null,
        clickableOnly: Boolean = false,
        visibleOnly: Boolean = true,
    ): List<LayoutNode> {
        val needle = text?.lowercase()
        return nodes.filter { n ->
            (needle == null || (n.text?.lowercase()?.contains(needle) == true) ||
                (n.description?.lowercase()?.contains(needle) == true)) &&
                (type == null || n.type.equals(type, ignoreCase = true)) &&
                (id == null || n.id == id) &&
                (!clickableOnly || n.clickable) &&
                (!visibleOnly || n.visible)
        }
    }
}
