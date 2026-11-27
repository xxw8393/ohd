package ohd.core.parsers

import ohd.platform.OhdPath
import ohd.platform.fs

/**
 * Android 布局树解析：`uiautomator dump` 的 XML → 与 OHOS 相同的 [LayoutNode] 树。
 *
 * 为什么单独一个解析器：OHOS 的 `dumpLayout` 输出 JSON（有自己的字段名与嵌套规则），
 * Android 是标准 UI Automator XML。两者只在「产出 LayoutNode」这一步汇合，
 * 上层的 tree / monkey / replay 完全不用分叉。
 *
 * 字段映射：
 * - `text` / `resource-id` / `class` / `content-desc` / `package` → 同名语义；
 * - `bounds="[l,t][r,b]"` → [Rect]；
 * - `visible`：XML 没有直接字段，用 `visible-to-user`（API 30+）+ 面积非零推断；
 * - `clickable` / `enabled`：字符串 "true"/"false" → Boolean。
 */
object AndroidLayoutParser {

    fun parseFile(path: OhdPath): List<LayoutNode> =
        runCatching { parseText(fs.readString(path)) }.getOrElse { emptyList() }

    fun parseText(xml: String): List<LayoutNode> {
        val root = MiniXml.parse(xml) ?: return emptyList()
        // <hierarchy> 下面通常是一个根 node；保留层级以免丢 depth
        val kids = root.children
        if (kids.isEmpty()) return listOfNotNull(nodeOf(root, 0, null))
        return kids.mapNotNull { nodeOf(it, 0, null) }
    }

    private fun nodeOf(el: MiniXml.Node, depth: Int, parentPkg: String?): LayoutNode? {
        val pkg = el.attr("package").ifBlank { parentPkg }
        val bounds = parseBounds(el.attr("bounds"))
        val visibleToUser = el.attr("visible-to-user")
        val visible = bounds != null &&
            bounds.width > 0 && bounds.height > 0 &&
            (visibleToUser.isBlank() || visibleToUser == "true")

        val children = el.children.mapNotNull { nodeOf(it, depth + 1, pkg) }

        return LayoutNode(
            text = el.attr("text").ifBlank { null },
            id = el.attr("resource-id").ifBlank { null },
            type = el.attr("class").ifBlank { null },
            description = el.attr("content-desc").ifBlank { null },
            bounds = bounds,
            clickable = el.attr("clickable") == "true",
            enabled = el.attr("enabled") != "false",
            visible = visible,
            bundleName = pkg,
            depth = depth,
            children = children,
        )
    }

    /** `[12,34][56,78]` → Rect(12,34,56,78) */
    fun parseBounds(raw: String): Rect? {
        val m = BOUNDS.find(raw) ?: return null
        val g = m.groupValues
        return Rect(g[1].toIntOrNull() ?: return null, g[2].toIntOrNull() ?: return null,
                    g[3].toIntOrNull() ?: return null, g[4].toIntOrNull() ?: return null)
    }

    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]""")
}
