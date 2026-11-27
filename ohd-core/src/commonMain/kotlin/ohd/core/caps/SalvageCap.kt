package ohd.core.caps

import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.LayoutParser
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.TimeFmt

/**
 * 从「只留下 layout 的会话」反推运行画像。
 *
 * 为什么需要：早期版本把采样与操作脚本都攒在内存里，进程一被中断就只剩 layout。
 * 那批残留会话不再产生，但已经产生的仍然只有这个办法能救。
 *
 * 本能力只报**能从文件确认**的东西，并且明确列出**无法恢复**的部分——
 * 与其给一个像模像样的估计值，不如说清楚哪里是黑的。
 */
class SalvageCap : Capability {
    override val name = "salvage"
    override val summary = "从残留会话反推运行画像（数据未落盘时的补救；只报可确认项）"
    override val usage = "ohd salvage [<会话目录>] [--json]"
    override val example = "ohd salvage --json"
    override val accepts: Set<String> = setOf("session", "json")

    private companion object {
        /** mtime 跨度的展示形状（只要时分秒）。 */
        const val TS = "HH:mm:ss"
    }

    override fun run(ctx: RunContext): Int {
        val dir = resolveDir(ctx)
        val files = fs.list(dir).use { st ->
            st.filter { fs.isRegularFile(it) }.toList()
        }
        val layouts = files.filter { it.fileName.toString().endsWith("-layout.json") }.sorted()
        val scripts = files.filter { it.fileName.toString().endsWith(".ohd") }
        val samples = files.filter { it.fileName.toString().endsWith("-samples.csv") }

        if (layouts.isEmpty() && scripts.isEmpty() && samples.isEmpty()) {
            throw OhdError(
                ExitCode.USAGE,
                "该目录没有可分析的产物：$dir\n  -> 需要 *-layout.json / *.ohd / *-samples.csv"
            )
        }

        // 时长：用 mtime 跨度，比文件名序号可靠（这里只要时刻点，直接收 epoch 毫秒）
        val times = files.mapNotNull { runCatching { fs.lastModifiedMillis(it) }.getOrNull() }
        val first = times.minOrNull()
        val last = times.maxOrNull()
        // floorDiv(1000) 对齐 Instant.getEpochSecond 的向下取整，保持与旧版逐个截秒后再相减一致
        val spanSec = if (first != null && last != null) {
            (last.floorDiv(1000L) - first.floorDiv(1000L)).coerceAtLeast(0)
        } else 0

        // 界面多样性：对文件字节取指纹，不必解析 JSON
        val distinct = layouts.map { digest(it) }.toSet().size

        // 可点节点数：抽样解析，全量解析 900 多份要几十秒
        val sampleStep = maxOf(1, layouts.size / 24)
        val clickables = layouts.filterIndexed { i, _ -> i % sampleStep == 0 }.mapNotNull { f ->
            runCatching { LayoutParser.parseFile(f).flatMap { it.flatten() }.count { it.clickable } }.getOrNull()
        }
        val clickMin = clickables.minOrNull()
        val clickMax = clickables.maxOrNull()
        val clickMed = clickables.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        // 有完整数据时直接劝退：salvage 是补救手段，不是常规路径
        val hasFullData = scripts.isNotEmpty() && samples.isNotEmpty()

        ctx.out.kv(
            listOf(
                "会话" to dir.toAbsolutePath().toString(),
                "产物" to buildString {
                    append("${layouts.size} 个 layout")
                    if (scripts.isNotEmpty()) append("、${scripts.size} 个脚本")
                    if (samples.isNotEmpty()) append("、${samples.size} 份采样")
                },
                "时长" to if (first != null && last != null) {
                    // 旧版先 Instant.ofEpochSecond(截到秒) 再按系统时区 format，等价于丢掉毫秒；
                    // TimeFmt.format 本身不输出毫秒，这里直接喂原始毫秒，行为一致。
                    val f = TimeFmt.format(first, TS)
                    val t = TimeFmt.format(last, TS)
                    "${humanSpan(spanSec)}（$f → $t）"
                } else "未知",
                "界面" to if (layouts.isEmpty()) "-" else
                    "${layouts.size} 帧，$distinct 个不同（${distinct * 100 / layouts.size}%）",
                "可点节点" to if (clickables.isEmpty()) "-" else
                    "$clickMin ~ $clickMax（中位 $clickMed，抽样 ${clickables.size} 帧）",
            )
        )

        ctx.out.info("")
        ctx.out.info("能恢复：")
        ctx.out.info("  - 运行时长、见证的界面数量与多样性")
        ctx.out.info("  - 每一帧的完整节点树（坐标、文本、可点性），可直接用来定位控件")
        ctx.out.info("  - 可点节点数量的波动（间接反映界面复杂度变化）")
        ctx.out.info("")
        ctx.out.info("不能恢复（不要据此推断）：")
        ctx.out.info("  - 步数：layout 文件序号是抓取次数，不是注入步数")
        ctx.out.info("  - 注入事件序列：没有脚本就无法回放")
        ctx.out.info("  - PSS/DMA 采样：内存趋势无从判断，**不得**给出泄漏结论")
        if (hasFullData) {
            ctx.out.info("")
            ctx.out.note("HAS_FULL_DATA", "info", "该会话已含脚本与采样，不需要 salvage；直接看 CSV 或用 ohd analyze")
        }
        return ExitCode.OK
    }

    private fun resolveDir(ctx: RunContext): OhdPath {
        // 支持 `ohd salvage <目录>` 这种位置参数写法（文档里就是这么写的），
        // 只认 --session 会让用户传的目录被静默忽略、转而分析最新会话。
        (ctx.options.str("session") ?: ctx.options.positionals.firstOrNull())
            ?.let { return OhdPath.of(it).toAbsolutePath().normalize() }
        val root = Session.defaultRoot(ctx.cwd)
        if (!fs.isDirectory(root)) {
            throw OhdError(ExitCode.USAGE, "没有会话目录可分析：$root\n  -> 用 ohd salvage <目录> 指定")
        }
        return fs.list(root).use { st ->
            st.filter { fs.isRegularFile(it.resolve("manifest.json")) || fs.isDirectory(it) }
                .maxWithOrNull { a, b ->
                    // 旧版 Instant.EPOCH 兜底 = epoch 0ms，这里用 0L 保持完全等价
                    val ta = runCatching { fs.lastModifiedMillis(a) }.getOrNull()
                    val tb = runCatching { fs.lastModifiedMillis(b) }.getOrNull()
                    (ta ?: 0L).compareTo(tb ?: 0L)
                }
                ?: throw OhdError(ExitCode.USAGE, "会话根目录里没有子目录：$root")
        }
    }

    private fun digest(f: OhdPath): String = runCatching {
        fs.md5Hex(fs.readBytes(f))
    }.getOrDefault(f.fileName?.toString() ?: "")

    private fun humanSpan(sec: Long): String = when {
        sec >= 3600 -> "${sec / 3600} 小时 ${sec % 3600 / 60} 分"
        sec >= 60 -> "${sec / 60} 分 ${sec % 60} 秒"
        else -> "$sec 秒"
    }
}
