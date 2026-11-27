package ohd.core.parsers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析 idb `ui describe-all` 的输出。
 *
 * 两个与 OHOS `uitest dumpLayout` 的关键差异：
 * 1. 它返回**平铺数组**，没有父子关系，`children` 一律为空。
 * 2. `frame` 单位是**逻辑点**（例如 402x874），不是截图像素（1206x2622）。
 *    所以 iOS 侧的点击坐标必须和这里保持同一套单位，否则会整屏偏移。
 */
object IosLayoutParser {
    private val CLICKABLE_ROLES = setOf(
        "AXButton", "AXLink", "AXTextField", "AXSwitch", "AXCell",
        "AXSearchField", "AXSlider", "AXSegmentedControl", "AXMenuItem",
    )
    private val CLICKABLE_TRAITS = setOf("Button", "Link", "SearchField", "Switch", "TextField")

    fun parse(json: String): List<LayoutNode> {
        val arr = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val f = o["frame"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return@mapNotNull null
            fun num(k: String): Int =
                runCatching { f[k]!!.jsonPrimitive.double }.getOrDefault(0.0).toInt()
            val x = num("x")
            val y = num("y")
            val w = num("width")
            val h = num("height")
            if (w <= 0 || h <= 0) return@mapNotNull null
            val role = o.str("role") ?: ""
            val traits = runCatching {
                o["traits"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
            }.getOrNull() ?: emptyList()
            LayoutNode(
                text = o.str("AXLabel"),
                id = o.str("AXUniqueId"),
                type = o.str("type"),
                description = o.str("role_description"),
                bounds = Rect(x, y, x + w, y + h),
                clickable = role in CLICKABLE_ROLES || traits.any { it in CLICKABLE_TRAITS },
                enabled = o.bool("enabled") ?: true,
                visible = true,
                bundleName = null,
                depth = 0,
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonObject.bool(k: String): Boolean? =
        runCatching { this[k]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}
