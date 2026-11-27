package ohd.core.parsers

/**
 * 极简 XML 解析器：只够解析 `uiautomator dump` 的输出。
 *
 * ## 为什么不引 DOM
 *
 * 原实现用 `javax.xml.parsers.DocumentBuilderFactory`，那是 JDK 独占，
 * 会把 `ohd-core` 钉死在 JVM 上。而这里要解的 XML 形状极其固定——
 * `<hierarchy rotation="0"><node ...><node .../></node></hierarchy>`，
 * 属性值是普通字符串，没有命名空间、没有 CDATA 参与业务、没有实体引用。
 * 为此挂整套 DOM + SAX + 安全特性，代价远大于收益。
 *
 * ## 支持范围（够用即止）
 *
 * - 元素与嵌套、自闭合标签
 * - 属性 `name="value"`，双引号与单引号
 * - 跳过 `<?...?>` / `<!DOCTYPE ...>` / `<!--...-->` / `<![CDATA[...]]>`
 * - 预定义实体 `&amp; &lt; &gt; &quot; &apos;` 与数字实体 `&#38;` `&#x26;`
 *
 * **不**支持：命名空间前缀（按普通属性名处理）、外部实体（直接忽略，等于安全默认）、
 * DTD 校验。遇到结构异常返回 null，由调用方当作「解析失败」，**不吐半棵树**。
 */
internal object MiniXml {

    /** 解析出的元素。 */
    class Node(
        val name: String,
        private val attrs: Map<String, String>,
        val children: MutableList<Node> = mutableListOf(),
    ) {
        /** 取属性；不存在返回空串（调用点普遍写 `.ifBlank { ... }`，与 DOM 的 getAttribute 一致）。 */
        fun attr(name: String): String = attrs[name] ?: ""
    }

    /** 解析 [xml]；结构异常返回 null。 */
    fun parse(xml: String): Node? {
        val p = Cursor(xml)
        p.skipProlog()
        val root = readElement(p) ?: return null
        return root
    }

    private class Cursor(val s: String) {
        var i = 0

        fun eof() = i >= s.length
        fun peek(): Char = if (eof()) '\u0000' else s[i]
        fun startsWith(t: String) = s.startsWith(t, i)

        /** 跳过声明、DOCTYPE、注释、CDATA 与空白，直到遇到下一个 '<' 或文本。 */
        fun skipProlog() {
            while (!eof()) {
                when {
                    startsWith("<?") -> skipUntil("?>")
                    startsWith("<!--") -> skipUntil("-->")
                    startsWith("<!DOCTYPE") -> skipDoctype()
                    startsWith("<![CDATA[") -> skipUntil("]]>")
                    peek().isWhitespace() -> i++
                    else -> return
                }
            }
        }

        fun skipUntil(token: String) {
            val at = s.indexOf(token, i)
            i = if (at < 0) s.length else at + token.length
        }

        /** DOCTYPE 内部可能有一对 `[]`，不能只找 '>'。 */
        fun skipDoctype() {
            var depth = 0
            while (!eof()) {
                when (s[i]) {
                    '[' -> depth++
                    ']' -> if (depth > 0) depth--
                    '>' -> if (depth == 0) {
                        i++
                        return
                    }
                }
                i++
            }
        }
    }

    /** 读一个元素（含子元素）。 */
    private fun readElement(p: Cursor): Node? {
        // 跳过元素间的空白与注释
        while (true) {
            p.skipProlog()
            if (p.eof() || p.peek() != '<') return null
            if (p.startsWith("</")) return null // 不该在这里遇到闭合标签
            break
        }
        p.i++ // 吃掉 '<'
        val name = readName(p)
        if (name.isEmpty()) return null

        val attrs = LinkedHashMap<String, String>()
        // 属性区
        while (true) {
            skipSpaces(p)
            if (p.eof()) return null
            if (p.startsWith("/>")) {
                p.i += 2
                return Node(name, attrs) // 自闭合
            }
            if (p.peek() == '>') {
                p.i++
                break
            }
            val attrName = readName(p)
            if (attrName.isEmpty()) return null
            skipSpaces(p)
            if (p.peek() != '=') continue // 无值属性：忽略该属性名
            p.i++
            skipSpaces(p)
            val quote = p.peek()
            if (quote != '"' && quote != '\'') return null
            p.i++
            val raw = readUntil(p, quote)
            attrs[attrName] = decodeEntities(raw)
        }

        val node = Node(name, attrs)
        // 内容：文本（忽略）与子元素
        while (true) {
            if (p.eof()) return null // 没闭合 → 结构异常
            if (p.startsWith("</")) {
                p.i += 2
                readName(p)
                skipSpaces(p)
                if (p.peek() == '>') p.i++
                return node
            }
            p.skipProlog() // 处理注释/CDATA/空白/文本
            if (p.eof()) return null
            when {
                p.startsWith("</") -> continue
                p.peek() == '<' -> {
                    val child = readElement(p) ?: return null
                    node.children += child
                }
                else -> readText(p)
            }
        }
    }

    /** 读标签名或属性名：字母开头，允许 `-` `.` `:` `_` 与数字。 */
    private fun readName(p: Cursor): String {
        val start = p.i
        while (!p.eof()) {
            val c = p.s[p.i]
            val ok = c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == ':'
            if (!ok) break
            p.i++
        }
        return p.s.substring(start, p.i)
    }

    private fun readUntil(p: Cursor, quote: Char): String {
        val start = p.i
        while (!p.eof() && p.s[p.i] != quote) p.i++
        val out = p.s.substring(start, p.i)
        if (!p.eof()) p.i++ // 吃掉收尾引号
        return out
    }

    /** 跳过文本节点（uiautomator 的元素之间只有空白，但不假定）。 */
    private fun readText(p: Cursor) {
        val at = p.s.indexOf('<', p.i)
        p.i = if (at < 0) p.s.length else at
    }

    private fun skipSpaces(p: Cursor) {
        while (!p.eof() && p.s[p.i].isWhitespace()) p.i++
    }

    /**
     * 解码 XML 预定义实体与数字实体。
     *
     * `uiautomator` 会把界面文本里的 `&` `<` 转义，不解码会让 `tree` 显示的文案错。
     */
    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            if (raw[i] != '&') {
                sb.append(raw[i])
                i++
                continue
            }
            val semi = raw.indexOf(';', i + 1)
            if (semi < 0) {
                sb.append(raw[i])
                i++
                continue
            }
            val ent = raw.substring(i + 1, semi)
            val decoded = when {
                ent == "amp" -> '&'
                ent == "lt" -> '<'
                ent == "gt" -> '>'
                ent == "quot" -> '"'
                ent == "apos" -> '\''
                ent.startsWith("#x") || ent.startsWith("#X") ->
                    ent.substring(2).toIntOrNull(16)?.toChar()
                ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.toChar()
                else -> null
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append(raw[i])
                i++
            }
        }
        return sb.toString()
    }
}
