package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.PathFromLog
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env

/**
 * hilog 页面事件 → 可 `ohd replay` 的 `.ohd` 骨架。
 *
 * 与 `tools/path_from_log.py` 同算法；坐标仍需 `--coords` 手工补（日志只有页面级事件）。
 */
class PathCap : Capability {
    override val name = "path"
    override val summary = "hilog 页面事件 → .ohd 操作路径骨架（需 --coords 补坐标）"
    override val usage =
        "ohd path <hilog.txt> [--algo stack|first] [--map <pagemap>] [--coords <coords>] " +
            "[--package <pkg>] [--ability <ability>] [--out <file.ohd>] [--json]\n" +
            "  默认 algo=stack；缺坐标的 enter 输出 # TODO 行。原型脚本仍在 tools/path_from_log.py。"
    override val example = "ohd path hilog.txt --package com.tencent.compose --algo stack --coords coords.txt --out replay.ohd"
    override val accepts: Set<String> = setOf("algo", "map", "coords", "package", "ability", "out", "bundle")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val logArg = ctx.options.positionals.firstOrNull()
            ?: throw OhdError(ExitCode.USAGE, "需要 hilog 文件", "示例：ohd path hilog.txt --coords coords.txt --map pagemap.txt（文件外的参数可省略）")
        val logPath = OhdPath.of(logArg)
        if (!fs.isRegularFile(logPath)) {
            throw OhdError(ExitCode.USAGE, "日志文件不存在：$logPath")
        }

        val algo = try {
            PathFromLog.parseAlgo(ctx.options.str("algo"))
        } catch (e: IllegalArgumentException) {
            throw OhdError(ExitCode.USAGE, e.message ?: "algo 无效")
        }
        val pkg = ctx.options.str("package")
            ?: ctx.options.str("bundle")
            ?: "com.tencent.compose"
        val ability = ctx.options.str("ability") ?: "EntryAbility"
        val names = PathFromLog.loadKv(ctx.options.str("map")?.let { OhdPath.of(it) })
        val coords = PathFromLog.loadKv(ctx.options.str("coords")?.let { OhdPath.of(it) })

        val events = PathFromLog.parseFile(logPath)
        val scriptLines: List<String>
        val enterN: Int
        val backN: Int
        val stepCount: Int
        val jsonExtra: kotlinx.serialization.json.JsonObject

        when (algo) {
            PathFromLog.Algo.STACK -> {
                val trans = PathFromLog.inferStack(events)
                enterN = trans.count { it.act == "enter" }
                backN = trans.count { it.act == "back" }
                stepCount = trans.size
                scriptLines = PathFromLog.emitStack(trans, names, coords, pkg, ability)
                jsonExtra = buildJsonObject {
                    put("transitions", buildJsonArray {
                        trans.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("from", t.from ?: "")
                                    put("to", t.to)
                                    put("act", t.act)
                                    put("t", t.t)
                                },
                            )
                        }
                    })
                }
            }
            PathFromLog.Algo.FIRST -> {
                val steps = PathFromLog.inferFirst(events)
                enterN = steps.count { it.act == "enter" }
                backN = steps.count { it.act == "back" }
                stepCount = steps.size
                scriptLines = PathFromLog.emitFirst(steps, names, coords, pkg, ability)
                jsonExtra = buildJsonObject {
                    put("steps", buildJsonArray {
                        steps.forEach { s ->
                            add(
                                buildJsonObject {
                                    put("act", s.act)
                                    put("name", s.name)
                                    put("t", s.t)
                                },
                            )
                        }
                    })
                }
            }
        }

        val body = scriptLines.joinToString("\n") + "\n"
        val outPath: OhdPath? = ctx.options.str("out")?.let { OhdPath.of(it) }
        if (outPath != null) {
            outPath.toAbsolutePath().parent?.let { fs.createDirectories(it) }
            Session.writeText(outPath, body)
        }

        val todoN = scriptLines.count { it.trimStart().startsWith("# TODO") }

        if (ctx.out.jsonMode) {
            ctx.out.finish(
                "path",
                true,
                buildJsonObject {
                    put("schema", "ohd-path/1")
                    put("algo", algo.name.lowercase())
                    put("events", events.size)
                    put("steps", stepCount)
                    put("enter", enterN)
                    put("back", backN)
                    put("todo", todoN)
                    put("script", body)
                    outPath?.let { put("out", it.toAbsolutePath().toString()) }
                    put("data", jsonExtra)
                },
                Env.nowMillis() - started,
            )
            return ExitCode.OK
        }

        ctx.out.info(
            when (algo) {
                PathFromLog.Algo.STACK ->
                    "事件 ${events.size} → 转移 $stepCount（enter $enterN / back $backN）" +
                        if (todoN > 0) "；缺坐标 $todoN 步" else ""
                PathFromLog.Algo.FIRST ->
                    "事件 ${events.size} 条 → 进入 $enterN 次 / 回退 $backN 次" +
                        if (todoN > 0) "；缺坐标 $todoN 步" else ""
            },
        )
        if (outPath != null) {
            ctx.out.info("已写入：${outPath.toAbsolutePath()}")
        } else {
            print(body)
        }
        return ExitCode.OK
    }
}
