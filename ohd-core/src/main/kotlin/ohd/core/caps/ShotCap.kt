package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.image.Box
import ohd.image.ImageAnnotate
import java.nio.file.Files

/** 截图 + 坐标标注：--grid 目视取坐标，--cross 确认点击点，--boxes 把节点框画上。 */
class ShotCap : Capability {
    override val name = "shot"
    override val summary = "截图并标注坐标（网格/十字/节点框），用于确认点击点"
    override val usage = "ohd shot [--grid 100] [--cross x,y] [--boxes] [--display N] [--out <png>] [--json]"
    override val example = "ohd shot --grid 100 --cross 640,1200 --boxes --out /tmp/shot.png"
    override val accepts: Set<String> = setOf("boxes", "cross", "display", "grid", "out")

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "shot")
        val shot = DeviceOps.snapshot(hdc, session, displayId = ctx.options.str("display")?.toIntOrNull())

        val grid = ctx.options.int("grid", 0)
        val cross = ctx.options.str("cross")
        val wantBoxes = ctx.options.bool("boxes")
        val outPath = ctx.options.str("out")?.let { java.nio.file.Paths.get(it) }

        var crossX: Int? = null
        var crossY: Int? = null
        if (cross != null) {
            val parts = cross.split(",", "x", " ")
            if (parts.size < 2) throw OhdError(ExitCode.USAGE, "--cross 需要 x,y 形式", "示例：--cross 640,1200")
            crossX = parts[0].trim().toIntOrNull()
            crossY = parts[1].trim().toIntOrNull()
            if (crossX == null || crossY == null) throw OhdError(ExitCode.USAGE, "--cross 坐标必须是整数", "示例：--cross 640,1200（像素坐标，x,y）")
        }

        val boxes: List<Box> = if (wantBoxes) {
            val cap = DeviceOps.captureLayout(hdc, session)
            cap.flat().mapNotNull { n -> n.bounds?.let { Box(it.left, it.top, it.right, it.bottom) } }
        } else {
            emptyList()
        }

        val annotated = (grid > 0 || crossX != null || boxes.isNotEmpty())
        val finalFile = if (annotated) {
            val dst = outPath ?: (session?.nextPath("annotated", "png")
                ?: Files.createTempFile("ohd-annotated-", ".png"))
            val ok = ImageAnnotate.annotate(shot.file, dst, gridStep = if (grid > 0) grid else 0, crossX = crossX, crossY = crossY, boxes = boxes)
            if (!ok) throw OhdError(ExitCode.UNSUPPORTED, "标注失败：无法解码 ${shot.file}")
            if (session != null && dst.fileName.toString().startsWith("annotated")) session.register(dst, "annotated")
            dst
        } else {
            outPath?.also { dst ->
                val n = dst.fileName.toString().lowercase()
                if (n.endsWith(".jpeg") || n.endsWith(".jpg")) {
                    Files.copy(shot.file, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    // 设备只能出 jpeg，扩展名要 png 时必须真转码，否则文件名与内容不符
                    val img = javax.imageio.ImageIO.read(shot.file.toFile())
                        ?: throw OhdError(ExitCode.UNSUPPORTED, "截图无法解码：${shot.file}")
                    javax.imageio.ImageIO.write(img, "png", dst.toFile())
                }
            } ?: shot.file
        }

        // 熄屏/锁屏时 snapshot_display 会返回全黑图且不报错 —— 静默失败必须显式提示
        val luma = meanLuma(finalFile)
        if (luma != null && luma < 8) {
            ctx.out.note(
                "SCREEN_BLACK",
                "warn",
                "截图平均亮度 $luma/255，屏幕可能已锁屏或熄屏；自动化前先执行 uitest uiInput keyEvent Power 唤醒",
            )
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("file", finalFile.toString())
                put("width", shot.width)
                put("height", shot.height)
                put("grid", grid)
                put("crossX", crossX ?: -1)
                put("crossY", crossY ?: -1)
                put("boxes", boxes.size)
            }
            ctx.out.finish("shot", true, data, System.currentTimeMillis() - started)
        } else {
            ctx.out.kv(
                listOf(
                    "截图" to finalFile.toString(),
                    "分辨率" to "${shot.width} x ${shot.height}",
                    "网格" to if (grid > 0) "${grid}px" else "未开启（--grid 100 可开）",
                    "十字" to (crossX?.let { "$it,$crossY" } ?: "-"),
                    "节点框" to "${boxes.size} 个",
                ),
            )
        }
        return ExitCode.OK
    }
}

/** 采样估算平均亮度（0..255）。用于识别"熄屏导致全黑截图"这一静默失败。 */
private fun meanLuma(file: java.nio.file.Path): Int? = runCatching {
    val img = javax.imageio.ImageIO.read(file.toFile()) ?: return@runCatching null
    var sum = 0L
    var n = 0
    var y = 0
    while (y < img.height) {
        var x = 0
        while (x < img.width) {
            val p = img.getRGB(x, y)
            sum += (((p shr 16 and 0xFF) * 299) + ((p shr 8 and 0xFF) * 587) + ((p and 0xFF) * 114)) / 1000
            n++
            x += 16
        }
        y += 16
    }
    if (n == 0) null else (sum / n).toInt()
}.getOrNull()
