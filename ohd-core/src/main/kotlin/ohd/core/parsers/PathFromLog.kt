package ohd.core.parsers

import java.nio.file.Files
import java.nio.file.Path

/**
 * 从 hilog 页面事件反推操作路径（与 `tools/path_from_log.py` 同语义）。
 *
 * 语义：Compose_N 首次 onPageShow → enter；再次 show 且栈里已有 → back；
 * onPageHide 不移除实例。
 */
object PathFromLog {

    private val CTRL = Regex("""ArkUIViewController\((Compose_\d+)\) onPage(Show|Hide) active=(true|false)""")
    private val PAGE = Regex("""ComposeAppShell: (\S+) onPage(Show|Hide)""")
    private val TIME = Regex("""\s(\d{2}:\d{2}:\d{2}\.\d{3})\s""")

    data class Event(val t: String, val kind: String, val name: String, val act: String)

    data class Transition(val from: String?, val to: String, val act: String, val t: String)

    data class FirstStep(val act: String, val name: String, val t: String)

    enum class Algo { STACK, FIRST }

    fun parse(text: String): List<Event> {
        val ev = mutableListOf<Event>()
        for (line in text.lineSequence()) {
            val mt = TIME.find(line) ?: continue
            val t = mt.groupValues[1]
            CTRL.findAll(line).forEach { m ->
                ev += Event(t, "ctrl", m.groupValues[1], m.groupValues[2].lowercase())
            }
            PAGE.findAll(line).forEach { m ->
                ev += Event(t, "page", m.groupValues[1], m.groupValues[2].lowercase())
            }
        }
        return ev
    }

    fun parseFile(path: Path): List<Event> =
        parse(Files.readString(path))

    fun inferStack(ev: List<Event>): List<Transition> {
        val first = linkedMapOf<String, String>()
        for (e in ev) {
            if (e.kind == "ctrl") first.putIfAbsent(e.name, e.act)
        }
        val stack = first.filter { it.value == "hide" }.keys.toMutableList()
        val out = mutableListOf<Transition>()
        for (e in ev) {
            if (e.kind != "ctrl" || e.act != "show") continue
            val n = e.name
            if (stack.isNotEmpty() && stack.last() == n) continue
            val frm = stack.lastOrNull()
            if (n in stack) {
                while (stack.isNotEmpty() && stack.last() != n) stack.removeAt(stack.lastIndex)
                out += Transition(frm, n, "back", e.t)
            } else {
                stack += n
                out += Transition(frm, n, "enter", e.t)
            }
        }
        return out
    }

    fun inferFirst(ev: List<Event>): List<FirstStep> {
        val first = linkedMapOf<String, String>()
        for (e in ev) {
            if (e.kind == "ctrl") first.putIfAbsent(e.name, e.act)
        }
        val seen = first.filter { it.value == "hide" }.keys.toMutableList()
        val out = mutableListOf<FirstStep>()
        for (e in ev) {
            if (e.kind != "ctrl" || e.act != "show") continue
            val n = e.name
            if (n in seen) {
                if (out.isNotEmpty() && out.last().name == n) continue
                out += FirstStep("back", n, e.t)
            } else {
                seen += n
                out += FirstStep("enter", n, e.t)
            }
        }
        return out
    }

    fun loadKv(path: Path?): Map<String, String> {
        if (path == null || !Files.isRegularFile(path)) return emptyMap()
        val d = linkedMapOf<String, String>()
        Files.readAllLines(path).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@forEach
            val i = line.indexOf('=')
            d[line.substring(0, i).trim()] = line.substring(i + 1).trim()
        }
        return d
    }

    fun emitStack(
        trans: List<Transition>,
        names: Map<String, String>,
        coords: Map<String, String>,
        pkg: String,
        ability: String,
    ): List<String> {
        val lines = mutableListOf(
            "# hilog 推断脚本（from->to 映射）",
            "launch $pkg $ability",
        )
        for (s in trans) {
            val key = "${s.from}->${s.to}"
            val label = names[s.to] ?: s.to
            when {
                key in coords -> lines += "${coords[key]}   # $key → $label @${s.t}"
                s.act == "back" -> lines += "key Back   # $key → $label @${s.t}（默认 back）"
                else -> lines += "# TODO $key → $label @${s.t}  缺坐标"
            }
        }
        return lines
    }

    fun emitFirst(
        steps: List<FirstStep>,
        names: Map<String, String>,
        coords: Map<String, String>,
        pkg: String,
        ability: String,
    ): List<String> {
        val lines = mutableListOf(
            "# 由 hilog 页面事件推断生成（ohd path）",
            "launch $pkg $ability",
        )
        for (s in steps) {
            val label = names[s.name] ?: s.name
            val key = "${s.act}:${s.name}"
            when {
                key in coords -> lines += "${coords[key]}   # ${s.act} $label @${s.t}"
                s.act == "back" -> lines += "key Back   # -> $label @${s.t}"
                else -> lines += "# TODO enter $label (${s.name}) @${s.t}  需要 tap 该入口控件"
            }
        }
        return lines
    }

    fun parseAlgo(raw: String?): Algo = when (raw?.trim()?.lowercase()) {
        null, "", "stack" -> Algo.STACK
        "first" -> Algo.FIRST
        else -> throw IllegalArgumentException("未知 --algo：$raw（stack|first）")
    }
}
