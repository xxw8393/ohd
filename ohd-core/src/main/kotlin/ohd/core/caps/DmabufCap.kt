package ohd.core.caps

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.DmabufParser
import java.nio.file.Files
import java.nio.file.Paths

/**
 * DMA buffer 观测。
 *
 * 价值在「对比」而非「单点快照」：单次快照只能看到谁占得多，
 * 两次快照的增量才能回答「谁在涨」——那才是泄漏排查的起点。
 *
 * 两条纪律：
 * 1. 按 ino 去重（同一块 buf 多次映射会重复计数）；
 * 2. 不与系统 DMA 做「应相等」的断言——两者口径完全不同，
 *    只并列展示并标注口径，对不上是正常的。
 */
class DmabufCap : Capability {
    override val name = "dmabuf"
    override val summary = "DMA buffer 快照与增量：按标签分组、ino 去重、与系统 DMA 并列对账"
    override val usage = "ohd dmabuf snap [--pid N | --bundle X] [--top N] [--out f] [--no-system] | ohd dmabuf diff <a> <b> [--top N] [--min-kb 4]"
    override val example = "ohd dmabuf snap --bundle com.tencent.compose --top 10 --out /tmp/dma.json"
    override val accepts: Set<String> = setOf("bundle", "min-kb", "no-system", "out", "pid", "top")

    override fun run(ctx: RunContext): Int {
        val sub = ctx.options.positionals.firstOrNull() ?: "snap"
        return when (sub) {
            "snap" -> snap(ctx)
            "diff" -> diff(ctx)
            else -> throw OhdError(ExitCode.USAGE, "未知子命令：$sub", "可用：snap / diff")
        }
    }

    private fun snap(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val session = Hdcs.session(ctx, "dmabuf")
        val hdc = Hdcs.forDevice(ctx)
        val top = ctx.options.int("top", 10)

        val bundle = ctx.options.str("bundle")
        val pid = ctx.options.str("pid")?.toIntOrNull()
            ?: bundle?.let { b ->
                Hdcs.sh(session, hdc, "pidof $b", 10_000).text().trim().lineSequence().firstOrNull()?.toIntOrNull()
            }
            ?: throw OhdError(ExitCode.USAGE, "需要指定进程", "示例：ohd dmabuf snap --bundle com.example.app --top 10（或 --pid 1234）")

        val catResult = Hdcs.sh(session, hdc, "cat /proc/$pid/mm_dmabuf_info", 20_000)
        if (!catResult.ok) {
            throw OhdError(
                ExitCode.ASSERT_FAILED,
                "读取 /proc/$pid/mm_dmabuf_info 失败：${catResult.text().take(200)}",
                "进程已退出、或该系统版本未开放此文件",
            )
        }
        val text = catResult.text()
        if (text.isBlank() || !text.contains("Process")) {
            throw OhdError(ExitCode.ASSERT_FAILED, "/proc/$pid/mm_dmabuf_info 内容异常", "该进程可能没有任何 dmabuf 映射")
        }

        val snap = DmabufParser.parse(text)

        // 原始文本落盘：diff 需要它，也便于事后复核
        val outPath = ctx.options.str("out")?.let { Paths.get(it) }
            ?: session?.nextPath("dmabuf", "txt")
        if (outPath != null) {
            Files.createDirectories(outPath.toAbsolutePath().parent)
            Session.writeText(outPath, text)
            session?.register(outPath, "dmabuf")
        }

        // 系统 DMA 总量（口径不同，只并列展示）；--no-system 跳过 hidumper --mem（单次 ~20s，逐轮采样用）
        val sysDmaKb = if (ctx.options.bool("no-system")) null else runCatching {
            Hdcs.sh(session, hdc, "hidumper --mem | grep -aE '^DMA\\('", 90_000)
                .let { Regex("""DMA\((\d+)\s*kB\)""").find(it.text())?.groupValues?.get(1)?.toLongOrNull() }
        }.getOrNull()

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-dmabuf/1")
                put("pid", pid)
                put("bundle", bundle ?: "")
                put("rawRows", snap.rawRows)
                put("entries", snap.entries.size)
                put("duplicateRows", snap.duplicateRows)
                put("skipped", snap.skipped)
                put("totalBytes", snap.totalBytes)
                put("systemDmaKb", sysDmaKb ?: -1)
                put("reclaimable", buildJsonObject {
                    val r = snap.entries.filter { it.canReclaim }
                    put("count", r.size)
                    put("bytes", r.sumOf { it.sizeBytes })
                })
                put("groups", buildJsonArray {
                    snap.groups.take(top).forEach { g ->
                        add(buildJsonObject {
                            put("label", g.label)
                            put("bytes", g.bytes)
                            put("count", g.count)
                            put("types", g.types.joinToString(","))
                        })
                    }
                })
                put("file", outPath?.toString() ?: "")
            }
            ctx.out.finish("dmabuf", true, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }

        ctx.out.kv(
            listOfNotNull(
                "进程" to "${snap.entries.firstOrNull()?.process ?: "?"} ($pid)",
                "条目" to "${snap.entries.size} 个（原始 ${snap.rawRows} 行，重复映射 ${snap.duplicateRows}" +
                    (if (snap.skipped > 0) "，解析失败 ${snap.skipped}" else "") + "）",
                "总计" to human(snap.totalBytes),
                "可回收" to run {
                    val r = snap.entries.filter { it.canReclaim }
                    "${r.size} 条 / ${human(r.sumOf { it.sizeBytes })}（can_reclaim=1，属可回收缓存）"
                },
                sysDmaKb?.let { "系统 DMA" to "${human(it * 1024)}（全系统口径，含其它进程与内核）" },
                outPath?.let { "快照文件" to it.toAbsolutePath().toString() },
            ),
        )
        println()
        ctx.out.table(
            listOf("#", "标签", "大小", "占比", "条数", "类型"),
            snap.groups.take(top).mapIndexed { i, g ->
                listOf(
                    (i + 1).toString(),
                    g.label,
                    human(g.bytes),
                    if (snap.totalBytes > 0) "%.1f%%".format(g.bytes * 100.0 / snap.totalBytes) else "-",
                    g.count.toString(),
                    g.types.joinToString("/"),
                )
            },
        )
        println()
        ctx.out.info("mm_dmabuf_info 是本进程「映射」的 dmabuf；系统 DMA 含所有进程与内核，两者不应相等。")
        ctx.out.info("对比两次快照看增量：ohd dmabuf diff <旧.txt> <新.txt>")
        return ExitCode.OK
    }

    private fun diff(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val files = ctx.options.positionals.drop(1)
        if (files.size < 2) {
            throw OhdError(ExitCode.USAGE, "需要两个快照文件（旧、新各一）", "示例：ohd dmabuf diff old.json new.json --min-kb 4（参数可省略）")
        }
        val top = ctx.options.int("top", 15)
        val minKb = ctx.options.str("min-kb")?.toLongOrNull() ?: 4L

        val a = DmabufParser.parse(Files.readString(Paths.get(files[0])))
        val b = DmabufParser.parse(Files.readString(Paths.get(files[1])))
        val ga = a.groups.associateBy { it.label }
        val gb = b.groups.associateBy { it.label }

        data class Delta(val label: String, val bytes: Long, val countDelta: Int)

        val deltas = (ga.keys + gb.keys).map { label ->
            val old = ga[label]
            val new = gb[label]
            Delta(label, (new?.bytes ?: 0) - (old?.bytes ?: 0), (new?.count ?: 0) - (old?.count ?: 0))
        }.filter { it.bytes != 0L && kotlin.math.abs(it.bytes) >= minKb * 1024 }

        val grew = deltas.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
        val shrank = deltas.filter { it.bytes < 0 }.sortedBy { it.bytes }
        val totalDelta = b.totalBytes - a.totalBytes

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-dmabuf-diff/1")
                put("before", buildJsonObject {
                    put("file", files[0]); put("entries", a.entries.size); put("totalBytes", a.totalBytes)
                })
                put("after", buildJsonObject {
                    put("file", files[1]); put("entries", b.entries.size); put("totalBytes", b.totalBytes)
                })
                put("totalDeltaBytes", totalDelta)
                put("grew", buildJsonArray {
                    grew.take(top).forEach {
                        add(buildJsonObject {
                            put("label", it.label); put("bytes", it.bytes); put("countDelta", it.countDelta)
                        })
                    }
                })
                put("shrank", buildJsonArray {
                    shrank.take(top).forEach {
                        add(buildJsonObject {
                            put("label", it.label); put("bytes", it.bytes); put("countDelta", it.countDelta)
                        })
                    }
                })
            }
            ctx.out.finish("dmabuf", true, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }

        ctx.out.kv(
            listOf(
                "基线" to "${a.entries.size} 条 / ${human(a.totalBytes)}",
                "当前" to "${b.entries.size} 条 / ${human(b.totalBytes)}",
                "总计增量" to signed(totalDelta),
            ),
        )
        println()

        if (grew.isEmpty() && shrank.isEmpty()) {
            ctx.out.info("没有超过 ${minKb}kB 的变化")
            return ExitCode.OK
        }
        if (grew.isNotEmpty()) {
            ctx.out.info("增长（谁在涨）")
            ctx.out.table(
                listOf("标签", "增量", "条数变化"),
                grew.take(top).map { listOf(it.label, signed(it.bytes), signedCount(it.countDelta)) },
            )
            println()
        }
        if (shrank.isNotEmpty()) {
            ctx.out.info("减少（谁释放了）")
            ctx.out.table(
                listOf("标签", "增量", "条数变化"),
                shrank.take(top).map { listOf(it.label, signed(it.bytes), signedCount(it.countDelta)) },
            )
            println()
        }
        ctx.out.info("增量只说明「谁变了」，是否属于泄漏需结合操作步骤与回收状态判断。")
        return ExitCode.OK
    }

    private fun human(b: Long): String {
        val v = kotlin.math.abs(b)
        val s = when {
            v >= 1024L * 1024 * 1024 -> "%.2f GB".format(v / 1024.0 / 1024 / 1024)
            v >= 1024 * 1024 -> "%.1f MB".format(v / 1024.0 / 1024)
            v >= 1024 -> "%.1f kB".format(v / 1024.0)
            else -> "$v B"
        }
        return if (b < 0) "-$s" else s
    }

    private fun signed(b: Long): String = (if (b > 0) "+" else "") + human(b)

    private fun signedCount(n: Int): String = if (n > 0) "+$n" else n.toString()
}
