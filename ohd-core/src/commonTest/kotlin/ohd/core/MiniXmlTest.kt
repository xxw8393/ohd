package ohd.core.parsers

import ohd.core.parsers.AndroidLayoutParser
import ohd.core.parsers.LayoutNode
import ohd.core.parsers.MiniXml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [MiniXml] 与 [AndroidLayoutParser] 的解析验证。
 *
 * 这套测试守的是一条**替代关系**：`MiniXml` 要能替掉 `javax.xml` 的 DOM。
 * 一旦解析行为与真实 `uiautomator dump` 输出对不上，`tree`/`monkey` 会静默少节点或读错文案，
 * 这比崩溃难查得多——所以断言要贴着**真实产物形状**写。
 */
class MiniXmlTest {

    /** 真实 `uiautomator dump` 的典型输出（已脱去设备无关的噪声，保留结构与转义）。 */
    private val realDump = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
                package="com.tencent.compose" content-desc="" checkable="false"
                clickable="false" enabled="true" visible-to-user="true"
                bounds="[0,0][1280,2832]">
            <node index="0" text="订单 &amp; 明细" resource-id="com.tencent.compose:id/title"
                  class="android.widget.TextView" package="com.tencent.compose"
                  content-desc="标题 &lt;主&gt;" clickable="true" enabled="true"
                  visible-to-user="true" bounds="[40,200][600,280]"/>
            <node index="1" text="" resource-id="com.tencent.compose:id/list"
                  class="androidx.recyclerview.widget.RecyclerView" package="com.tencent.compose"
                  content-desc="" clickable="false" enabled="false"
                  visible-to-user="false" bounds="[0,300][1280,2700]">
              <node index="0" text="商品 A" resource-id="" class="android.widget.TextView"
                    package="com.tencent.compose" content-desc="" clickable="false"
                    enabled="true" visible-to-user="true" bounds="[40,320][400,380]"/>
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun parsesRealDumpShape() {
        val root = MiniXml.parse(realDump)
        assertNotNull(root, "真实 uiautomator 输出应能解析")
        assertEquals("hierarchy", root.name)
        assertEquals("0", root.attr("rotation"))
        assertEquals(1, root.children.size, "<hierarchy> 下应有一个根 node")
        assertEquals("node", root.children[0].name)
        assertEquals(2, root.children[0].children.size, "根 node 下应有两个子 node")
    }

    @Test
    fun decodesEntitiesInAttributes() {
        val root = MiniXml.parse(realDump)!!
        val title = root.children[0].children[0]
        // 界面文案里的转义必须还原，否则 tree 显示的是 `&amp;` 原文
        assertEquals("订单 & 明细", title.attr("text"))
        assertEquals("标题 <主>", title.attr("content-desc"))
    }

    @Test
    fun selfClosingAndNestedBothWork() {
        val root = MiniXml.parse(realDump)!!
        val list = root.children[0].children[1]
        assertEquals("androidx.recyclerview.widget.RecyclerView", list.attr("class"))
        assertEquals(1, list.children.size, "RecyclerView 下应有一个子 node")
        assertEquals("商品 A", list.children[0].attr("text"))
    }

    @Test
    fun missingAttributeReturnsEmptyNotCrash() {
        val root = MiniXml.parse("<node class='x'/>")!!
        assertEquals("", root.attr("resource-id"))
        assertEquals("x", root.attr("class"))
    }

    @Test
    fun handlesCommentsDoctypeAndCdata() {
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE hierarchy [ <!ELEMENT node ANY> ]>
            <hierarchy>
              <!-- 这是注释，不应变成节点 -->
              <node text="a"/>
            </hierarchy>
        """.trimIndent()
        val root = MiniXml.parse(xml)
        assertNotNull(root, "含 DOCTYPE/注释的输入应能解析")
        assertEquals(1, root.children.size, "注释不应计入节点")
    }

    @Test
    fun rejectsMalformedInsteadOfHalfTree() {
        // 未闭合：必须整体失败，不能吐出半棵树（半棵树会让 tree 命令给出错误结论）
        assertNull(MiniXml.parse("<hierarchy><node text=\"a\">"))
        assertNull(MiniXml.parse("not xml at all"))
        assertNull(MiniXml.parse(""))
    }

    // ---------- 上层：LayoutNode 映射 ----------

    @Test
    fun parserMapsFieldsToLayoutNodes() {
        val nodes = AndroidLayoutParser.parseText(realDump)
        assertTrue(nodes.isNotEmpty(), "应解出至少一个根 LayoutNode")
        val rootNode = nodes[0]
        assertEquals(0, rootNode.depth)

        // 递归找标题节点，验字段映射与转义解码
        fun walk(n: LayoutNode): LayoutNode? {
            if (n.id == "com.tencent.compose:id/title") return n
            for (c in n.children) walk(c)?.let { return it }
            return null
        }
        val title = walk(rootNode)
        assertNotNull(title, "应能找到标题节点")
        assertEquals("订单 & 明细", title!!.text)
        assertEquals("标题 <主>", title.description)
        assertEquals(true, title.clickable)
        assertNotNull(title.bounds, "bounds 应被解析")
        assertEquals(40, title.bounds!!.left)
        assertEquals(600, title.bounds!!.right)
        assertTrue(title.visible, "bounds 非零且 visible-to-user=true → 可见")
        assertEquals("com.tencent.compose", title.bundleName)
    }

    @Test
    fun invisibleWhenFlagSaysFalse() {
        val nodes = AndroidLayoutParser.parseText(realDump)
        val list = nodes[0].children.firstOrNull { it.id == "com.tencent.compose:id/list" }
        assertNotNull(list)
        assertTrue(!list!!.visible, "visible-to-user=false 应判为不可见")
        assertEquals(false, list.enabled, "enabled=false 应判为不可用")
    }

    @Test
    fun parseBoundsStillWorks() {
        assertEquals(12, AndroidLayoutParser.parseBounds("[12,34][56,78]")!!.left)
        assertEquals(78, AndroidLayoutParser.parseBounds("[12,34][56,78]")!!.bottom)
        assertNull(AndroidLayoutParser.parseBounds("garbage"))
    }

    @Test
    fun badXmlYieldsEmptyListNotThrow() {
        assertEquals(emptyList(), AndroidLayoutParser.parseText("<broken"))
    }
}
