package ohd.core.caps

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceKind
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.OhdConfig
import ohd.core.OhdError
import ohd.core.RunContext

/**
 * 设备清单与连接管理：`list` / `use` / `connect` / `kill` 四个子命令。
 *
 * 为什么要有 `use`：多台设备时每条命令都要带 `--device` 太啰嗦，而「默认设备」原先只能靠
 * `ohd config set OHD_DEVICE` 手工写——藏得太深、还容易和 OHD_DEVICES 打架。`use` 把这个动作显式化。
 *
 * 三平台的「断开」语义并不一样，所以 kill 分两层（默认只断连接，`--process` 才动进程）：
 *
 * | 平台 | 断连接 | --process |
 * |---|---|---|
 * | OHOS TCP | `hdc tconn <ip:port> -remove` | 同上；本地模拟器进程需在 DevEco 关（ohd 不猜进程名） |
 * | OHOS USB | 无（拔线） | 无 |
 * | Android 模拟器 | `adb disconnect` 无效 | `adb -s <key> emu kill` |
 * | Android TCP 真机 | `adb disconnect <ip:port>` | 无 |
 * | iOS 模拟器 | simctl 无断开语义 | `simctl shutdown <udid>` |
 *
 * OHOS 的 --process 不杀进程是有意的：本地模拟器在 macOS 上是 QEMU 派生进程，没有可靠的
 * 「这个 PID 就是那台设备的模拟器」映射，按进程名匹配会误杀别人开着的模拟器。宁可不做。
 */
class DevicesCap : Capability {
    override val name = "devices"
    override val summary = "设备清单与连接管理：list / use（设默认设备）/ connect / kill（关掉模拟器）/ disconnect（只断连接）"
    override val usage = "ohd devices [list|use <key>|connect <ip:port>|kill|disconnect] [--device K] [--all] [--tcp-only] [--dry-run] [--unset] [--json]"
    override val example = "ohd devices kill --all --tcp-only"
    override val accepts: Set<String> = setOf("device", "all", "tcp-only", "process", "dry-run", "unset")

    private val subcommands = listOf("list", "use", "connect", "kill", "disconnect")

    private data class Target(val kind: DeviceKind, val key: String, val conn: String, val emulator: Boolean)

    /** 一步动作：要么有命令要执行，要么只会给出一句人话解释（reason != null 表示不动）。 */
    private data class Step(val command: List<String>?, val reason: String?, val note: String? = null)

    override fun run(ctx: RunContext): Int {
        val pos = ctx.options.positionals
        val sub = pos.firstOrNull() ?: "list"
        return when (sub) {
            "list" -> list(ctx)
            "use" -> use(ctx, pos.getOrNull(1))
            "connect" -> connect(ctx, pos.getOrNull(1))
            "kill" -> kill(ctx, softArg = false)
            "disconnect" -> kill(ctx, softArg = true)
            else -> throw OhdError(ExitCode.USAGE, "未知子命令：$sub", "可用：${subcommands.joinToString("/")}")
        }
    }

    // ---------------------------------------------------------------- list

    private fun list(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val all = scan(ctx)
        val defaults = defaultKeys()

        if (ctx.out.jsonMode) {
            ctx.out.finish(name, true, buildJsonObject {
                put("schema", "ohd-devices/1")
                put("defaults", buildJsonArray { defaults.forEach { add(it) } })
                put("devices", buildJsonArray {
                    all.forEach { t ->
                        add(buildJsonObject {
                            put("key", t.key); put("platform", t.kind.label)
                            put("conn", t.conn); put("emulator", t.emulator)
                            put("default", t.key in defaults)
                        })
                    }
                })
            }, System.currentTimeMillis() - started)
        } else if (all.isEmpty()) {
            ctx.out.info("没有已连接的设备")
            ctx.out.info("连模拟器/远程机：ohd devices connect 127.0.0.1:5555")
        } else {
            ctx.out.table(
                listOf("设备", "平台", "连接", "类型", "默认"),
                all.map {
                    listOf(it.key, it.kind.label, it.conn, if (it.emulator) "模拟器" else "真机", if (it.key in defaults) "✓" else "")
                },
            )
            ctx.out.info("")
            if (defaults.isEmpty()) {
                ctx.out.info("尚未设默认设备：ohd devices use <设备>（之后所有命令不用再带 --device）")
            } else {
                ctx.out.info("默认设备：${defaults.joinToString(", ")}（改：devices use；清：devices use --unset）")
            }
        }
        return ExitCode.OK
    }

    // ---------------------------------------------------------------- use

    private fun use(ctx: RunContext, arg: String?): Int {
        if (ctx.options.bool("unset")) {
            val a = OhdConfig.unset("OHD_DEVICES")
            val b = OhdConfig.unset("OHD_DEVICE")
            if (ctx.out.jsonMode) {
                ctx.out.finish(name, true, buildJsonObject { put("cleared", a || b) }, 0)
            } else {
                ctx.out.info(if (a || b) "已清除默认设备" else "本来就没有默认设备")
                ctx.out.info("此后多设备时需要显式 --device")
            }
            return ExitCode.OK
        }

        val key = arg?.takeIf { it.isNotBlank() }
            ?: throw OhdError(
                ExitCode.USAGE,
                "需要设备 key",
                "先 ohd devices 看清单，再 ohd devices use <key>；清除用 --unset",
            )

        val online = scan(ctx).any { it.key == key }
        OhdConfig.set("OHD_DEVICE", key)
        // OHD_DEVICES（多台集合）优先级高于 OHD_DEVICE——不清掉就会出现「use 了但没生效」
        OhdConfig.unset("OHD_DEVICES")

        if (ctx.out.jsonMode) {
            ctx.out.finish(name, true, buildJsonObject {
                put("key", "OHD_DEVICE"); put("value", key); put("online", online)
            }, 0)
        } else {
            ctx.out.info("默认设备 → $key（写入 OHD_DEVICE，已清空多设备集合）")
            if (!online) ctx.out.warn("注意：该设备当前不在线，命令真正执行前请先连接")
            ctx.out.info("之后的命令不带 --device 就用它；临时换一台仍可用 --device 覆盖")
        }
        return ExitCode.OK
    }

    // ---------------------------------------------------------------- connect

    private fun connect(ctx: RunContext, arg: String?): Int {
        val key = arg?.takeIf { it.isNotBlank() }
            ?: throw OhdError(ExitCode.USAGE, "需要地址", "示例：ohd devices connect 127.0.0.1:5555 [--platform ohos|android]")
        val want = DeviceKind.of(ctx.options.str("platform"))

        // 没给 --platform 时两边都试：hdc tconn 先（OHOS 模拟器最常见），失败再 adb connect
        val order = when (want) {
            DeviceKind.ANDROID -> listOf(DeviceKind.ANDROID)
            DeviceKind.OHOS -> listOf(DeviceKind.OHOS)
            else -> listOf(DeviceKind.OHOS, DeviceKind.ANDROID)
        }

        val attempts = mutableListOf<Triple<DeviceKind, List<String>, String>>()
        for (kind in order) {
            val exe = toolOf(ctx, kind) ?: continue
            val argv = if (kind == DeviceKind.ANDROID) listOf("connect", key) else listOf("tconn", key)
            val r = Hdc(exe, kind = kind).run(argv, 20_000)
            val msg = r.stdout.lines().firstOrNull { it.isNotBlank() }
                ?: r.stderr.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            attempts.add(Triple(kind, argv, if (r.ok) msg else "失败：${msg.ifBlank { "exit ${r.exit}" }}"))
            if (r.ok && !msg.contains("fail", ignoreCase = true)) break
        }

        if (attempts.isEmpty()) {
            throw OhdError(ExitCode.UNSUPPORTED, "没有可用的连接工具", "hdc / adb 都没找到，用 --hdc / --adb 指定路径")
        }

        val okOne = attempts.firstOrNull { !it.third.startsWith("失败") }
        if (ctx.out.jsonMode) {
            ctx.out.finish(name, okOne != null, buildJsonObject {
                put("key", key)
                put("attempts", buildJsonArray {
                    attempts.forEach { (k, argv, msg) ->
                        add(buildJsonObject {
                            put("platform", k.label)
                            put("command", argv.joinToString(" "))
                            put("result", msg)
                        })
                    }
                })
            }, 0)
        } else {
            attempts.forEach { (k, argv, msg) ->
                ctx.out.info("${k.label}: ${argv.joinToString(" ")} → $msg")
            }
            if (okOne == null) {
                ctx.out.warn("连接未成功：确认地址与端口，或加 --platform 只试一个平台")
                return ExitCode.NO_DEVICE
            }
            ctx.out.info("")
            ctx.out.info("连上了：ohd devices 看清单；想固定下来：ohd devices use $key")
        }
        return if (okOne != null) ExitCode.OK else ExitCode.NO_DEVICE
    }

    // ---------------------------------------------------------------- kill

    private fun kill(ctx: RunContext, softArg: Boolean): Int {
        val started = System.currentTimeMillis()
        val all = scan(ctx)
        if (all.isEmpty()) {
            if (ctx.out.jsonMode) {
                ctx.out.finish(name, true, buildJsonObject { put("changed", 0) }, 0)
            } else {
                ctx.out.info("没有已连接的设备")
            }
            return ExitCode.OK
        }

        val only = ctx.options.str("device")?.takeIf { it.isNotBlank() }
        if (only == null && !ctx.options.bool("all")) {
            // 不带 --device / --all：只报清单，绝不默认动手——断错设备的代价比多打一个 --all 高得多
            if (ctx.out.jsonMode) {
                ctx.out.finish(name, true, buildJsonObject {
                    put("dryRun", true)
                    put("devices", buildJsonArray {
                        all.forEach { t ->
                            add(buildJsonObject {
                                put("key", t.key); put("platform", t.kind.label)
                                put("conn", t.conn); put("emulator", t.emulator)
                            })
                        }
                    })
                }, 0)
            } else {
                ctx.out.table(
                    listOf("设备", "平台", "连接", "类型"),
                    all.map { listOf(it.key, it.kind.label, it.conn, if (it.emulator) "模拟器" else "真机") },
                )
                ctx.out.info("")
                ctx.out.info("指定对象再执行：--device <设备> 或 --all（--tcp-only 跳过 USB；--dry-run 只看不做）")
                ctx.out.info("kill = 关掉（模拟器 shutdown / emu kill）；disconnect = 只断 TCP 连接")
            }
            return ExitCode.OK
        }

        if (only != null && all.none { it.key == only }) {
            throw OhdError(
                ExitCode.NO_DEVICE,
                "未找到设备：$only",
                "已连接：" + all.joinToString("，") { "${it.key}(${it.kind.label})" },
            )
        }

        val selected = (if (only != null) all.filter { it.key == only } else all)
            .filter { t -> !ctx.options.bool("tcp-only") || !t.conn.equals("USB", ignoreCase = true) }

        // kill 的直觉就是「关掉」，所以默认就关模拟器进程；只有 disconnect 才只断连接。
        // --process 是老版本「kill 要显式加才关进程」的写法，给了就按关处理，保持兼容。
        val soft = softArg && !ctx.options.bool("process")
        val dry = ctx.options.bool("dry-run")
        val rows = mutableListOf<List<String>>()
        val records = mutableListOf<Triple<Target, Step, String>>()
        var changed = 0

        for (t in selected) {
            val step = plan(t, soft)
            val outcome = when {
                step.command == null -> "跳过"
                dry -> "DRY-RUN"
                else -> {
                    val exe = toolOf(ctx, t.kind)
                        ?: throw OhdError(
                            ExitCode.UNSUPPORTED,
                            "${t.kind.bin} 未找到，无法处理 ${t.key}",
                            "用 --${t.kind.bin} 指定路径",
                        )
                    val r = Hdc(exe, kind = t.kind).run(step.command, 20_000)
                    val err = r.stderr.lines().firstOrNull { it.isNotBlank() }.orEmpty()
                    if (r.ok) {
                        changed++
                        "已执行"
                    } else {
                        "失败：" + err.ifBlank { "exit ${r.exit}" }
                    }
                }
            }
            rows.add(listOf(t.key, t.kind.label, step.command?.joinToString(" ") ?: (step.reason ?: "-"), outcome))
            records.add(Triple(t, step, outcome))
        }

        if (ctx.out.jsonMode) {
            ctx.out.finish(name, true, buildJsonObject {
                put("schema", "ohd-devices-kill/1")
                put("dryRun", dry)
                put("changed", changed)
                put("actions", buildJsonArray {
                    records.forEach { (t, step, outcome) ->
                        add(buildJsonObject {
                            put("key", t.key); put("platform", t.kind.label)
                            put("conn", t.conn); put("emulator", t.emulator)
                            put("command", step.command?.joinToString(" ") ?: "")
                            put("reason", step.reason ?: "")
                            put("outcome", outcome)
                        })
                    }
                })
            }, System.currentTimeMillis() - started)
        } else {
            ctx.out.table(listOf("设备", "平台", "动作", "结果"), rows)
            records.mapNotNull { it.second.note }.distinct().forEach { ctx.out.info("· $it") }
            ctx.out.info("")
            ctx.out.info(if (dry) "DRY-RUN：以上命令没有执行" else "完成：$changed 台已处理")
            if (changed > 0 && !dry) ctx.out.info("提示：连接被摘掉后，list targets / devices 可能残留离线条目，重新 list 即刷新")
        }
        return ExitCode.OK
    }

    // ---------------------------------------------------------------- helpers

    /** 配置里的默认设备：OHD_DEVICES（多台）优先，其次 OHD_DEVICE；与 CLI 的分发规则保持一致。 */
    private fun defaultKeys(): List<String> {
        val sysenv = OhdConfig.sysenv()
        val many = sysenv("OHD_DEVICES")
            ?.split(',', '，', ';', ' ')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (many.isNotEmpty()) return many
        return sysenv("OHD_DEVICE")?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
    }

    private fun toolOf(ctx: RunContext, kind: DeviceKind) = when (kind) {
        DeviceKind.OHOS -> ctx.env.hdc.path
        DeviceKind.ANDROID -> ctx.env.adb.path
        DeviceKind.IOS -> ctx.env.simctl.path
        DeviceKind.IOS_DEVICE -> ctx.env.devicectl.path
    }

    private fun scan(ctx: RunContext): List<Target> = buildList {
        ctx.env.hdc.path?.let { exe ->
            val r = Hdc(exe, kind = DeviceKind.OHOS).run(listOf("list", "targets", "-v"), 15_000)
            if (r.ok) {
                r.stdout.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }
                    .forEach { line ->
                        // 实测格式：<key>\t\tUSB\tConnected\tlocalhost
                        val cols = line.split(Regex("\\s+"))
                        val key = cols.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
                        add(Target(DeviceKind.OHOS, key, cols.getOrNull(1) ?: "?", emulator = false))
                    }
            }
        }
        ctx.env.adb.path?.let { exe ->
            val r = Hdc(exe, kind = DeviceKind.ANDROID).run(listOf("devices", "-l"), 15_000)
            if (r.ok) {
                r.stdout.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("*") }
                    .forEach { line ->
                        val cols = line.split(Regex("\\s+"))
                        val key = cols.getOrNull(0) ?: return@forEach
                        if (cols.getOrNull(1) != "device") return@forEach
                        val emu = key.startsWith("emulator-")
                        add(
                            Target(
                                DeviceKind.ANDROID, key,
                                when {
                                    emu -> "emulator"
                                    key.contains(':') -> "TCP"
                                    else -> "USB"
                                },
                                emu,
                            ),
                        )
                    }
            }
        }
        ctx.env.simctl.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.IOS).targets() }.getOrDefault(emptyList())
                .forEach { add(Target(DeviceKind.IOS, it, "simulator", emulator = true)) }
        }
        ctx.env.devicectl.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.IOS_DEVICE).targets() }.getOrDefault(emptyList())
                .forEach { add(Target(DeviceKind.IOS_DEVICE, it, "USB", emulator = false)) }
        }
    }

    private fun plan(t: Target, soft: Boolean): Step = when (t.kind) {
        DeviceKind.OHOS -> when {
            t.conn.equals("USB", ignoreCase = true) -> Step(
                null,
                "USB 直连无法软件断开，只能拔线",
            )
            else -> Step(
                listOf("tconn", t.key, "-remove"),
                null,
                if (soft) null else "OHOS 模拟器进程需在 DevEco 里关：ohd 不做按进程名匹配的 kill（会误杀）",
            )
        }
        DeviceKind.ANDROID -> when {
            t.emulator -> if (soft) {
                Step(null, "模拟器没有「断开」语义（adb disconnect 无效），用 devices kill 关掉")
            } else {
                Step(listOf("-s", t.key, "emu", "kill"), null)
            }
            t.conn == "TCP" -> Step(listOf("disconnect", t.key), null)
            else -> Step(null, "USB 直连：adb 没有断开命令，只能拔线")
        }
        DeviceKind.IOS -> if (soft) {
            Step(null, "模拟器没有「断开」语义，用 devices kill 关掉（simctl shutdown）")
        } else {
            Step(listOf("shutdown", t.key), null)
        }
        DeviceKind.IOS_DEVICE -> Step(null, "iPhone 真机无法远程断开：拔线或忽略配对")
    }
}
