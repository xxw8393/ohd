package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.Devices
import ohd.core.ExitCode
import ohd.core.OhdConfig
import ohd.core.OhdError
import ohd.core.RunContext
import java.nio.file.Files

/**
 * 用户配置：让「我的机器上工具装在哪」持久化。
 *
 * 只做一件事：维护 `<home>/.ohd/config.json` 里的一批环境变量名 → 值。
 * 所有能力通过 `Env` 自动吃到这些值，不需要各自加 `--xxx` 选项。
 */
class ConfigCap : Capability {
    override val name = "config"
    override val summary = "用户配置：持久化 hdc/llvm/JAVA_HOME 等路径，以及默认设备（省掉每条命令的 --device）"
    override val usage = "ohd config <list|get|set|unset|keys|path|env|devices> [键] [值]"
    override val example = "ohd config set JAVA_HOME /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home"

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val pos = ctx.options.positionals
        val sub = pos.firstOrNull() ?: "list"
        val cfgPath = OhdConfig.path()

        return when (sub) {
            "list" -> {
                val values = OhdConfig.load()
                val effective = OhdConfig.effective()
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("path", cfgPath.toString())
                        put("values", buildJsonObject { values.toSortedMap().forEach { (k, v) -> put(k, v) } })
                        put("effective", buildJsonObject { effective.toSortedMap().forEach { (k, v) -> put(k, v) } })
                    }, System.currentTimeMillis() - started)
                } else if (effective.isEmpty()) {
                    ctx.out.info("（暂无配置）")
                    ctx.out.info("常用键见 ohd config keys；文件：$cfgPath")
                } else {
                    ctx.out.table(
                        listOf("键", "值", "来源"),
                        effective.toSortedMap().map { (k, v) ->
                            listOf(k, v, if (values.containsKey(k)) "config" else "env")
                        },
                    )
                    ctx.out.info("")
                    ctx.out.info("文件：$cfgPath")
                }
                ExitCode.OK
            }

            "keys" -> {
                ctx.out.table(listOf("键", "含义"), OhdConfig.KNOWN_KEYS.map { listOf(it.first, it.second) })
                ExitCode.OK
            }

            "path" -> {
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("path", cfgPath.toString())
                        put("exists", Files.isRegularFile(cfgPath))
                    }, System.currentTimeMillis() - started)
                } else {
                    ctx.out.info(cfgPath.toString())
                    if (!Files.isRegularFile(cfgPath)) ctx.out.info("（文件尚不存在，ohd config set 会自动创建）")
                }
                ExitCode.OK
            }

            "get" -> {
                val k = pos.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要键名", "示例：ohd config get JAVA_HOME")
                val v = OhdConfig.effective()[k]
                    ?: throw OhdError(ExitCode.USAGE, "未设置：$k", "示例：ohd config set $k <值>")
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("key", k); put("value", v)
                        put("source", if (OhdConfig.load().containsKey(k)) "config" else "env")
                    }, System.currentTimeMillis() - started)
                } else {
                    ctx.out.info(v)
                }
                ExitCode.OK
            }

            "set" -> {
                val k = pos.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要键名", "示例：ohd config set OHD_HDC /path/to/hdc")
                val v = pos.getOrNull(2) ?: throw OhdError(ExitCode.USAGE, "需要值", "ohd config set $k /path/to/tool")
                if (k.isBlank() || !k.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))) {
                    throw OhdError(ExitCode.USAGE, "键名不合法：$k", "用环境变量名，如 OHD_HDC / JAVA_HOME / OHD_PYTHON")
                }
                OhdConfig.set(k, v)
                // 值看起来像路径就顺手校验一次：配错了比没配更难查
                val hint = if (v.contains('/') || v.contains('\\')) {
                    if (Files.exists(java.nio.file.Paths.get(v))) "" else "（注意：该路径当前不存在）"
                } else ""
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("key", k); put("value", v); put("path", cfgPath.toString())
                    }, System.currentTimeMillis() - started)
                } else {
                    ctx.out.info("已写入 $k = $v$hint")
                    ctx.out.info("文件：$cfgPath")
                }
                ExitCode.OK
            }

            "unset" -> {
                val k = pos.getOrNull(1) ?: throw OhdError(ExitCode.USAGE, "需要键名", "示例：ohd config unset JAVA_HOME")
                val removed = OhdConfig.unset(k)
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("key", k); put("removed", removed)
                    }, System.currentTimeMillis() - started)
                } else {
                    ctx.out.info(if (removed) "已删除 $k" else "配置里没有 $k（环境变量不受影响）")
                }
                ExitCode.OK
            }

            "devices" -> {
                // 多台设备挂着、只想跑其中几台：把 key 存进 OHD_DEVICES，此后不必每条命令加 --device
                val wanted = pos.drop(1)
                    .flatMap { it.split(',', '，', ';') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (wanted.isNotEmpty()) {
                    OhdConfig.set("OHD_DEVICES", wanted.joinToString(","))
                    if (ctx.out.jsonMode) {
                        ctx.out.finish("config", true, buildJsonObject {
                            put("key", "OHD_DEVICES"); put("value", wanted.joinToString(","))
                        }, System.currentTimeMillis() - started)
                    } else {
                        ctx.out.info("已配置默认设备 ${wanted.size} 台：${wanted.joinToString("，")}")
                        ctx.out.info("不带 --device 时就跑这几台" + if (wanted.size > 1) "（并行）" else "")
                        ctx.out.info("改回来：ohd config unset OHD_DEVICES")
                    }
                    return ExitCode.OK
                }

                val sysenv = OhdConfig.sysenv()
                val configured = (sysenv("OHD_DEVICES") ?: sysenv("OHD_DEVICE"))
                    ?.split(',', '，', ';', ' ')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                val online = runCatching { Devices.all(ctx.env) }.getOrDefault(emptyList())
                if (ctx.out.jsonMode) {
                    ctx.out.finish("config", true, buildJsonObject {
                        put("configured", configured.joinToString(","))
                        put("online", online.joinToString(",") { it.key })
                    }, System.currentTimeMillis() - started)
                } else {
                    if (online.isEmpty()) {
                        ctx.out.info("（当前没有在线设备）")
                    } else {
                        ctx.out.table(
                            listOf("设备", "平台", "默认"),
                            online.map {
                                listOf(it.key, it.kind.label, if (configured.contains(it.key)) "✔" else "")
                            },
                        )
                    }
                    ctx.out.info("")
                    ctx.out.info(
                        if (configured.isEmpty()) "默认设备：未配置（多台在线时每条命令都要 --device）"
                        else "默认设备：${configured.joinToString("，")}",
                    )
                    ctx.out.info("设置：ohd config devices <序列号>[,<序列号>...]    清除：ohd config unset OHD_DEVICES")
                }
                ExitCode.OK
            }

            "env" -> {
                // 输出可直接 eval 的 export 行，方便把 ohd 配置带进 shell/CI
                OhdConfig.load().toSortedMap().forEach { (k, v) ->
                    ctx.out.info("export $k='$v'")
                }
                ExitCode.OK
            }

            else -> throw OhdError(
                ExitCode.USAGE,
                "未知子命令：$sub",
                "ohd config list | get <键> | set <键> <值> | unset <键> | keys | path | env | devices",
            )
        }
    }
}
