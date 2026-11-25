package ohd.core.parsers

import org.w3c.dom.Element
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

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

    fun parseFile(path: Path): List<LayoutNode> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // uiautomator 的输出是本地文件，不加载外部实体
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(path.toFile()) }.getOrNull() ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()

        // <hierarchy> 下面通常是一个根 node；保留层级以免丢 depth
        val kids = childElements(root)
        if (kids.isEmpty()) return listOfNotNull(nodeOf(root, 0, null))
        return kids.mapNotNull { nodeOf(it, 0, null) }
    }

    fun parseText(xml: String): List<LayoutNode> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = runCatching {
            factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
        }.getOrNull() ?: return emptyList()
        val root = doc.documentElement ?: return emptyList()
        val kids = childElements(root)
        if (kids.isEmpty()) return listOfNotNull(nodeOf(root, 0, null))
        return kids.mapNotNull { nodeOf(it, 0, null) }
    }

    private fun nodeOf(el: Element, depth: Int, parentPkg: String?): LayoutNode? {
        val pkg = el.getAttribute("package").ifBlank { parentPkg }
        val bounds = parseBounds(el.getAttribute("bounds"))
        val visibleToUser = el.getAttribute("visible-to-user")
        val visible = bounds != null &&
            bounds.width > 0 && bounds.height > 0 &&
            (visibleToUser.isBlank() || visibleToUser == "true")

        val children = childElements(el).mapNotNull { nodeOf(it, depth + 1, pkg) }

        return LayoutNode(
            text = el.getAttribute("text").ifBlank { null },
            id = el.getAttribute("resource-id").ifBlank { null },
            type = el.getAttribute("class").ifBlank { null },
            description = el.getAttribute("content-desc").ifBlank { null },
            bounds = bounds,
            clickable = el.getAttribute("clickable") == "true",
            enabled = el.getAttribute("enabled") != "false",
            visible = visible,
            bundleName = pkg,
            depth = depth,
            children = children,
        )
    }

    private fun childElements(el: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            (nodes.item(i) as? Element)?.let { out += it }
        }
        return out
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
