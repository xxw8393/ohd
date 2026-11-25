package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.actions.ReplayAim
import ohd.core.actions.UiActions
import ohd.image.GifWriter
import ohd.core.parseDurationMs
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 动作回放：脚本 → 设备操作 → 每步取证。
 * 这是「测试发现问题后把可复现步骤交给开发」的载体：脚本是纯文本，可直接贴进工单。
 */
class ReplayCap : Capability {
    override val name = "replay"
    override val summary = "回放 UI 动作脚本（tap/swipe/key/text/sleep/shot），每步记录结果"
    override val usage = "ohd replay <脚本.ohd> [--dry-run] [--script <文件>] [--step-delay 300ms] [--wait-timeout 5s] [--keep-going] [--json]"
    override val example = "ohd replay /tmp/run/002-monkey.ohd --step-delay 200ms --keep-going"
    override val accepts: Set<String> = setOf("dry-run", "keep-going", "record", "script", "step-delay", "wait-timeout")

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val scriptPath = ctx.options.positionals.firstOrNull()?.let { Paths.get(it) }
            ?: ctx.options.str("script")?.let { Paths.get(it) }
            ?: throw OhdError(ExitCode.USAGE, "需要脚本路径", "示例：ohd replay case/login.ohd --step-delay 200ms（脚本外的参数可省略）")
        if (!Files.isRegularFile(scriptPath)) throw OhdError(ExitCode.USAGE, "脚本不存在：$scriptPath")

        val actions = try {
            UiActions.parseScript(Files.readString(scriptPath))
        } catch (e: UiActions.ScriptError) {
            throw OhdError(ExitCode.USAGE, e.message ?: "脚本语法错误")
        }
        if (actions.isEmpty()) throw OhdError(ExitCode.USAGE, "脚本里没有可执行动作：$scriptPath")

        val dryRun = ctx.options.bool("dry-run")
        val keepGoing = ctx.options.bool("keep-going")
        val stepDelay = parseDurationMs(ctx.options.str("step-delay") ?: "300ms")
        // --record：每步截一张，最后压成 replay.gif。每张 ~500ms，只给脚本量不大的场景用
        val record = ctx.options.bool("record")
        val fromRecord = actions.any { it.kind == UiActions.Kind.LAUNCH }

        if (ctx.out.jsonMode && dryRun) {
            val data = buildJsonObject {
                put("script", scriptPath.toString())
                put("dryRun", true)
                put("steps", buildJsonArray {
                    actions.forEach { a ->
                        add(buildJsonObject {
                            put("line", a.lineNo)
                            put("action", a.raw)
                            put("command", UiActions.shellCommand(a) ?: "(内部动作)")
                        })
                    }
                })
            }
            ctx.out.finish("replay", true, data, System.currentTimeMillis() - started)
            return ExitCode.OK
        }
        if (dryRun) {
            ctx.out.info("计划 ${actions.size} 步（--dry-run，不碰设备）：$scriptPath\n")
            actions.forEach { a ->
                val cmd = when (a.kind) {
                    UiActions.Kind.LAUNCH -> "aa force-stop ${a.args[0]}; aa start -b ${a.args[0]} -a ${a.args[1]}"
                    else -> UiActions.shellCommand(a) ?: "(内部动作)"
                }
                val click = a.kind == UiActions.Kind.TAP || a.kind == UiActions.Kind.DOUBLE_TAP ||
                    a.kind == UiActions.Kind.LONG_PRESS
                val aim = when {
                    click && a.hasIdentity -> "  （先等控件出现）"
                    a.hasIdentity -> "  （先按控件定位）"
                    else -> ""
                }
                ctx.out.info("[${a.lineNo}] ${a.raw}\n      $cmd$aim")
            }
            return ExitCode.OK
        }

        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "replay")
        val results = mutableListOf<StepResult>()
        var failed = false

        if (!ctx.out.jsonMode) ctx.out.info("回放 ${actions.size} 步：$scriptPath\n")

        val frames = mutableListOf<java.io.File>()
        for (a in actions) {
            val r = execute(ctx, hdc, session, a, fromRecord)
            if (record) {
                runCatching {
                    val shot = DeviceOps.snapshot(hdc, session)
                    frames.add(shot.file.toFile())
                }
            }
            results += r
            if (!ctx.out.jsonMode) {
                val mark = if (r.ok) "✓" else "✗"
                val body = "$mark [${a.lineNo}] ${a.raw}${if (r.detail.isNotEmpty()) "  → ${r.detail}" else ""}"
                // 每步结果必须默认可见：这是回放报告的主体，不是调试信息
                if (r.ok) ctx.out.info(body) else ctx.out.note("STEP_FAILED", "error", body)
            }
            if (!r.ok) {
                failed = true
                // 冷启动失败就停。继续点会打在别的页面上。
                if (a.kind == UiActions.Kind.LAUNCH || !keepGoing) break
            }
            if (a.kind != UiActions.Kind.SLEEP && stepDelay > 0) Thread.sleep(stepDelay)
        }

        // 合成 GIF：帧数 >=2 才有动画意义；失败不影响回放结果（GIF 只是附加产物）
        var gifPath: String? = null
        if (record && frames.size >= 2) {
            val gif = session?.nextPath("replay", "gif")
            if (gif != null) {
                val imgs = frames.mapNotNull { runCatching { javax.imageio.ImageIO.read(it) }.getOrNull() }
                if (GifWriter.write(imgs, gif.toFile())) {
                    session.register(gif, "replay-gif")
                    gifPath = gif.toAbsolutePath().toString()
                }
            }
        }

        val okCount = results.count { it.ok }
        val scriptCopy = session?.let { s ->
            val p = s.nextPath("script", "ohd")
            Files.copy(scriptPath, p)
            s.register(p, "script")
            p
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("script", scriptPath.toString())
                put("scriptCopy", scriptCopy?.toString() ?: "")
                put("total", actions.size)
                put("executed", results.size)
                put("succeeded", okCount)
                put("failed", results.size - okCount)
                put("steps", buildJsonArray {
                    results.forEach { r ->
                        add(buildJsonObject {
                            put("line", r.lineNo)
                            put("action", r.raw)
                            put("ok", r.ok)
                            put("detail", r.detail)
                            put("elapsedMs", r.elapsedMs)
                        })
                    }
                })
            }
            ctx.out.finish("replay", !failed, data, System.currentTimeMillis() - started)
        } else {
            println()
            ctx.out.kv(
                listOf(
                    "总步数" to actions.size.toString(),
                    "已执行" to results.size.toString(),
                    "成功" to okCount.toString(),
                    "失败" to (results.size - okCount).toString(),
                    "脚本副本" to (scriptCopy?.toString() ?: "-"),
                    "录像" to (gifPath ?: if (record) "未生成（帧数不足 2）" else "-（加 --record 录制）"),
                ),
            )
        }
        return if (failed) ExitCode.ASSERT_FAILED else ExitCode.OK
    }

    internal data class StepResult(
        val lineNo: Int,
        val raw: String,
        val ok: Boolean,
        val detail: String,
        val elapsedMs: Long,
    )

    internal fun execute(ctx: RunContext, hdc: ohd.core.Hdc, session: Session?, a: UiActions.Action, fromRecord: Boolean = false): StepResult {
        val t0 = System.currentTimeMillis()
        return try {
            when (a.kind) {
                UiActions.Kind.SLEEP -> {
                    val ms = parseDurationMs(a.args[0])
                    Thread.sleep(ms)
                    StepResult(a.lineNo, a.raw, true, "${ms}ms", System.currentTimeMillis() - t0)
                }
                UiActions.Kind.SHOT -> {
                    val shot = DeviceOps.snapshot(hdc, session)
                    StepResult(a.lineNo, a.raw, true, shot.file.toString(), System.currentTimeMillis() - t0)
                }
                UiActions.Kind.TREE -> {
                    val cap = DeviceOps.captureLayout(hdc, session)
                    StepResult(a.lineNo, a.raw, true, "${cap.flat().size} 个节点", System.currentTimeMillis() - t0)
                }
                UiActions.Kind.WAIT_TEXT -> {
                    val needle = a.args[0]
                    val deadline = System.currentTimeMillis() + parseDurationMs(ctx.options.str("wait-timeout") ?: "5s")
                    var hit = false
                    while (System.currentTimeMillis() < deadline) {
                        val cap = runCatching { DeviceOps.captureLayout(hdc, null) }.getOrNull()
                        if (cap != null && cap.flat().any { it.text?.contains(needle) == true }) {
                            hit = true
                            break
                        }
                        Thread.sleep(250)
                    }
                    StepResult(a.lineNo, a.raw, hit, if (hit) "出现「$needle」" else "超时未见「$needle」", System.currentTimeMillis() - t0)
                }
                UiActions.Kind.LAUNCH -> launch(hdc, session, a, t0)
                else -> {
                    val waitMs = parseDurationMs(ctx.options.str("wait-timeout") ?: "5s")
                    val aimed = aim(hdc, a, fromRecord, waitMs)
                    if (aimed.failure != null) {
                        return StepResult(a.lineNo, a.raw, false, aimed.failure, System.currentTimeMillis() - t0)
                    }
                    // iOS 走 idb 注入；其余平台仍是设备 shell
                    val r = UiActions.perform(hdc, aimed.action, 20_000)
                    val detail = listOfNotNull(aimed.note, if (r.ok) null else r.detail)
                        .filter { it.isNotEmpty() }
                        .joinToString("；")
                    StepResult(a.lineNo, a.raw, r.ok, detail, System.currentTimeMillis() - t0)
                }
            }
        } catch (e: Exception) {
            StepResult(a.lineNo, a.raw, false, e.message ?: e::class.simpleName.orEmpty(), System.currentTimeMillis() - t0)
        }
    }

    private fun launch(hdc: ohd.core.Hdc, session: Session?, a: UiActions.Action, t0: Long): StepResult {
        val bundle = a.args[0]
        val ability = a.args[1]
        Hdcs.sh(session, hdc, "aa force-stop $bundle", 15_000)
        val started = Hdcs.sh(session, hdc, "aa start -b $bundle -a $ability", 15_000)
        if (!started.ok) {
            return StepResult(a.lineNo, a.raw, false, "冷启动失败：${started.text().take(160)}", System.currentTimeMillis() - t0)
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (bundle in DeviceOps.foregroundBundles(hdc)) {
                return StepResult(a.lineNo, a.raw, true, "已回到入口", System.currentTimeMillis() - t0)
            }
            Thread.sleep(400)
        }
        return StepResult(a.lineNo, a.raw, false, "前台不是 $bundle，已停止", System.currentTimeMillis() - t0)
    }

    private data class Aimed(val action: UiActions.Action, val note: String?, val failure: String?)

    private fun aim(hdc: ohd.core.Hdc, a: UiActions.Action, fromRecord: Boolean, waitMs: Long): Aimed {
        val click = a.kind == UiActions.Kind.TAP || a.kind == UiActions.Kind.DOUBLE_TAP ||
            a.kind == UiActions.Kind.LONG_PRESS
        val pointer = click || a.kind == UiActions.Kind.SWIPE || a.kind == UiActions.Kind.DRAG ||
            a.kind == UiActions.Kind.FLING
        if (!pointer) return Aimed(a, null, null)
        if (!a.hasIdentity) return Aimed(a, if (fromRecord && click) "坐标回退" else null, null)
        val deadline = if (click) System.currentTimeMillis() + waitMs else 0L
        val anchorEnd = !a.toId.isNullOrBlank() || !a.toText.isNullOrBlank()
        val decision = ReplayAim.poll(
            sample = {
                val nodes = runCatching { DeviceOps.captureLayout(hdc, null).flat() }.getOrNull()
                    ?: return@poll ReplayAim.NotFound("layout")
                if (anchorEnd) ReplayAim.decide(nodes, a.toId, a.toText, a.toType)
                else ReplayAim.decide(nodes, a.id, a.text, a.type)
            },
            deadlineMs = deadline,
        )
        return when (val d = decision) {
            is ReplayAim.Hit -> {
                val how = if (d.by == "id") "id" else "文本"
                val where = if (anchorEnd) "终点" else ""
                val moved = if (anchorEnd) ReplayAim.retargetEnd(a, d.x, d.y) else ReplayAim.retarget(a, d.x, d.y)
                Aimed(moved, "按$where$how 定位 ${d.x},${d.y}", null)
            }
            is ReplayAim.Ambiguous -> Aimed(a, null, "命中 ${d.count} 个控件，未点击")
            is ReplayAim.NotFound -> Aimed(a, null, "未找到控件，未点击")
            ReplayAim.Fallback -> Aimed(a, "坐标回退", null)
        }
    }
}
