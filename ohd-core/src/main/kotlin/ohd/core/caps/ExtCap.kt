package ohd.core.caps

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.ext.Extensions
import java.nio.file.Files

/**
 * 扩展管理：列出来、看路径、看实现、显式跑。
 *
 * 扩展本体是外部脚本（sh / py / java / bat），ohd 只负责「发现 + 拉起 + 注入环境」，
 * 不解释脚本参数——参数原样透传，脚本自己决定怎么用。
 */
class ExtCap : Capability {
    override val name = "ext"
    override val summary = "扩展命令：用脚本给 ohd 挂新能力（sh / py / java / bat）"
    override val usage = "ohd ext <list|path|which|docs|run> [名称] [参数...]"
    override val example = "ohd ext list"

    /** ext 的参数要原样交给脚本，ohd 不检查。 */
    override val passthrough = true

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val pos = ctx.options.positionals
        val sub = pos.firstOrNull() ?: "list"
        val cwd = ctx.cwd
        val found = Extensions.discover(cwd)

        return when (sub) {
            "list" -> {
                if (ctx.out.jsonMode) {
                    ctx.out.finish("ext", true, buildJsonObject {
                        put("count", found.size)
                        put("extensions", buildJsonArray {
                            found.values.forEach { e ->
                                add(buildJsonObject {
                                    put("name", e.name)
                                    put("kind", e.kind.label)
                                    put("path", e.file.toString())
                                    put("summary", e.summary)
                                })
                            }
                        })
                        put("roots", buildJsonArray {
                            Extensions.roots(cwd).forEach { add(it.toString()) }
                        })
                    }, System.currentTimeMillis() - started)
                } else if (found.isEmpty()) {
                    ctx.out.info("（没有扩展）")
                    ctx.out.info("")
                    ctx.out.info("放一个脚本进下列任一目录即可新增命令：")
                    Extensions.roots(cwd).forEach { ctx.out.info("  $it") }
                    ctx.out.info("")
                    ctx.out.info("脚本头部写 `# @ohd 一行说明` 会显示在帮助里。示例：")
                    ctx.out.info("  .ohd/extensions/uitest.sh  →  ohd uitest ...")
                } else {
                    ctx.out.table(
                        listOf("命令", "类型", "说明", "文件"),
                        found.values.map { listOf("ohd ${it.name}", it.kind.label, it.summary, it.file.toString()) },
                    )
                    ctx.out.info("")
                    // 内置能力优先，同名扩展不会执行——不提示的话用户会以为脚本没跑
                    // 内置能力优先，同名扩展不会执行；名单由 CLI 注入，避免硬编码过期
                    val shadowed = found.values.filter { it.name in ctx.builtinNames }
                    if (shadowed.isNotEmpty()) {
                        ctx.out.warn(
                            "以下扩展与内置能力同名，不会被执行（内置优先）：" +
                                shadowed.joinToString("，") { it.name } +
                                "；请改名，例如 ${shadowed.first().name}2",
                        )
                        ctx.out.info("")
                    }
                    ctx.out.info("直接执行：ohd <命令> [参数...]    看实现：ohd ext docs <命令>")
                }
                ExitCode.OK
            }

            "path" -> {
                if (ctx.out.jsonMode) {
                    ctx.out.finish("ext", true, buildJsonObject {
                        put("roots", buildJsonArray {
                            Extensions.roots(cwd).forEach { add(it.toString()) }
                        })
                    }, System.currentTimeMillis() - started)
                } else {
                    Extensions.roots(cwd).forEach { r ->
                        val mark = if (Files.isDirectory(r)) "存在" else "不存在"
                        ctx.out.info("$mark  $r")
                    }
                }
                ExitCode.OK
            }

            "which" -> {
                val n = pos.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要扩展名", "示例：ohd ext which uitest")
                val e = found[n] ?: throw OhdError(
                    ExitCode.USAGE,
                    "未找到扩展：$n",
                    "ohd ext list 看有哪些；或把脚本放进 " + Extensions.roots(cwd).joinToString(" / "),
                )
                if (ctx.out.jsonMode) {
                    ctx.out.finish("ext", true, buildJsonObject {
                        put("name", e.name); put("kind", e.kind.label); put("path", e.file.toString())
                    }, System.currentTimeMillis() - started)
                } else {
                    ctx.out.kv(listOf("命令" to "ohd ${e.name}", "类型" to e.kind.label, "文件" to e.file.toString()))
                }
                ExitCode.OK
            }

            "docs" -> {
                val n = pos.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要扩展名", "示例：ohd ext docs uitest")
                val e = found[n] ?: throw OhdError(ExitCode.USAGE, "未找到扩展：$n", "ohd ext list")
                ctx.out.info("# ${e.file}")
                Files.newBufferedReader(e.file).use { r ->
                    r.lineSequence().take(60).forEach { ctx.out.info(it) }
                }
                ExitCode.OK
            }

            "run" -> {
                // 用原始参数而非 positionals：脚本的 `--count 5` 这类选项 ohd 不认识，
                // 但必须原样到达脚本，所以按 argv 切片，不重组。
                val raw = ctx.rawArgs
                val n = raw.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要扩展名", "示例：ohd ext run uitest --count 5")
                val e = found[n] ?: throw OhdError(ExitCode.USAGE, "未找到扩展：$n", "ohd ext list")
                Extensions.exec(e, raw.drop(2), env(ctx))
            }

            else -> throw OhdError(
                ExitCode.USAGE,
                "未知子命令：$sub",
                "ohd ext list | path | which <名称> | docs <名称> | run <名称> [参数...]",
            )
        }
    }

    private fun env(ctx: RunContext): Map<String, String> = Extensions.envOf(
        hdc = ctx.env.hdc.argv0().takeIf { it.isNotBlank() },
        llvm = ctx.env.llvmAddr2line.argv0().takeIf { it.isNotBlank() },
        device = ctx.options.str("device"),
        sessionRoot = ctx.sessionRoot,
        json = ctx.out.jsonMode,
        ascii = ctx.out.ascii,
    )
}
