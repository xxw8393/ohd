package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.image.ImageDiffMap
import ohd.image.ImageIOEngine
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 截图对比。默认只报事实（均值/最大差/变化比例/异常 tile），
 * 达到阈值才以 exit 7 表示「不一致」，让流水线能判失败但不下「UI 坏了」的结论。
 */
class DiffCap : Capability {
    override val name = "diff"
    override val summary = "截图对比：差异指标 + 热力图，超阈值 exit 7"
    override val usage = "ohd diff --baseline <a.png> [--current <b.png>] [--threshold 8] [--tile 64] [--allow-ratio 0.01] [--max-changed 0.01] [--out diff.png] [--json]"
    override val example = "ohd diff --baseline a.png --current b.png --threshold 8 --allow-ratio 0.01"
    override val accepts: Set<String> = setOf("allow-ratio", "baseline", "current", "max-changed", "out", "threshold", "tile")

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val baselineArg = ctx.options.str("baseline")
            ?: throw OhdError(ExitCode.USAGE, "需要 --baseline <基线图>",
                "示例：ohd diff --baseline a.png --current b.png --threshold 8 --tile 64 --allow-ratio 0.01（除 --baseline 外参数可省略）")
        val baseline = Paths.get(baselineArg)
        if (!Files.isRegularFile(baseline)) throw OhdError(ExitCode.USAGE, "基线图不存在：$baseline")

        val current = ctx.options.str("current")?.let { Paths.get(it) }
        val session = Hdcs.session(ctx, "diff")
        val (currentPath, synthesized) = if (current != null) {
            if (!Files.isRegularFile(current)) throw OhdError(ExitCode.USAGE, "对比图不存在：$current")
            current to false
        } else {
            // 没给 --current 就现截一张，实现「与基线比当前屏」
            val hdc = Hdcs.forDevice(ctx)
            val shot = DeviceOps.snapshot(hdc, session, suffix = "jpeg")
            Session.writeText(
                session?.let { it.nextPath("shot-note", "txt") } ?: Files.createTempFile("ohd-shot", ".txt"),
                "snapshot ${shot.width}x${shot.height}",
            )
            shot.file to true
        }

        val engine = ImageIOEngine()
        val a = try {
            engine.load(baseline)
        } catch (e: Exception) {
            throw OhdError(ExitCode.USAGE, "基线图无法解码：${e.message}")
        }
        val b = try {
            engine.load(currentPath)
        } catch (e: Exception) {
            throw OhdError(ExitCode.UNSUPPORTED, "对比图无法解码：${e.message}")
        }
        if (a.width != b.width || a.height != b.height) {
            ctx.out.note(
                "SIZE_MISMATCH",
                "warn",
                "尺寸不同：%dx%d vs %dx%d，按左上角对齐取交集比较，结论仅作参考".format(a.width, a.height, b.width, b.height),
            )
        }

        val threshold = ctx.options.int("threshold", 8)
        val tile = ctx.options.int("tile", 64)
        val diff = engine.diff(a, b, threshold, tile)
        // 允许的变化像素占比：0.01 = 1%。--max-changed 是历史名
        val limit = (ctx.options.str("allow-ratio") ?: ctx.options.str("max-changed"))?.toDoubleOrNull() ?: 0.01

        val mapFile = ctx.options.str("out")?.let { Paths.get(it) } ?: session?.nextPath("diffmap", "png")
        val mapOk = mapFile != null && runCatching { ImageDiffMap.render(baseline, currentPath, mapFile, threshold) }.getOrDefault(false)
        if (mapOk && mapFile != null) session?.register(mapFile, "diffmap")

        val exceeded = diff.changedRatio > limit
        if (exceeded) {
            ctx.out.note(
                "UI_MISMATCH",
                "warn",
                "变化像素占比 %.3f%% > 阈值 %.3f%%（仅表示像素差异，是否是真缺陷需结合用例判断）".format(
                    diff.changedRatio * 100, limit * 100,
                ),
            )
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("baseline", baseline.toString())
                put("current", currentPath.toString())
                put("synthesizedCurrent", synthesized)
                put("width", a.width)
                put("height", a.height)
                put("meanAbs", diff.meanAbs)
                put("maxAbs", diff.maxAbs)
                put("changedRatio", diff.changedRatio)
                put("threshold", threshold)
                put("tileSize", tile)
                put("anomalousTiles", diff.anomalousTiles)
                put("tiles", "${diff.tilesX}x${diff.tilesY}")
                put("maxChanged", limit)
                put("exceeded", exceeded)
                put("diffMap", mapFile?.toString() ?: "")
            }
            ctx.out.finish("diff", !exceeded, data, System.currentTimeMillis() - started)
        } else {
            ctx.out.kv(
                listOf(
                    "基线" to baseline.toString(),
                    "对比" to currentPath.toString() + (if (synthesized) "（现截）" else ""),
                    "尺寸" to "${a.width}x${a.height}",
                    "平均差" to "%.2f".format(diff.meanAbs),
                    "最大差" to diff.maxAbs.toString(),
                    "变化占比" to "%.3f%%（阈值 %.3f%%）".format(diff.changedRatio * 100, limit * 100),
                    "异常区块" to "${diff.anomalousTiles} / ${diff.tilesX * diff.tilesY}（${tile}px）",
                    "差异图" to (if (mapOk) mapFile.toString() else "生成失败"),
                ),
            )
        }
        return if (exceeded) ExitCode.MISMATCH else ExitCode.OK
    }
}
