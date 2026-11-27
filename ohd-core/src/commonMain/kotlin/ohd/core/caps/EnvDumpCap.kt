package ohd.core.caps

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceManager
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.DeviceOps
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.platform.Env

/** 设备环境快照：证据包的 env/device.json 来源，也是「同一环境」对账依据。 */
class EnvDumpCap : Capability {
    override val name = "env-dump"
    override val summary = "采集设备指纹（型号/API/版本/ABI/内存/存储/时区/前台应用）"
    override val usage = "ohd env-dump [--json] [--device <序列号>] [--no-archive]"
    override val example = "ohd env-dump --no-archive --json"
    override val accepts: Set<String> = setOf("device", "no-archive")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val loc = ctx.env.hdc
        if (!loc.found || loc.path == null) {
            throw OhdError(ExitCode.NO_DEVICE, "未找到 hdc", "设置 HDC 或 --hdc")
        }
        val base = Hdc(loc.path)
        val dm = DeviceManager(base)
        // --device 是按序列号（支持前缀）多设备时选一台，不是「选字段」
        val serial = dm.select(ctx.options.str("device"))
        val hdc = Hdc(loc.path, serial)
        val info = dm.info(serial)

        val fullName = param(hdc, "const.ohos.fullname")
        val memTotal = hdc.shell("head -1 /proc/meminfo", 10_000).stdout.trim()
        val storage = hdc.shell("df -h /data | tail -1", 10_000).stdout.trim()
        // 设备当前时间。默认 date 输出 "Sat Oct 17 16:45:23 CST 2026" 不可解析，
        // 优先取 ISO 格式；不支持时回退原始输出（字段名如实叫 deviceTime）
        val deviceTime = run {
            val iso = hdc.shell("date +%Y-%m-%dT%H:%M:%S%z", 10_000).stdout.trim()
            val raw = if (Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""").containsMatchIn(iso)) {
                iso
            } else {
                hdc.shell("date", 10_000).stdout.trim()
            }
            // +0800 → +08:00，符合 ISO-8601 扩展格式
            raw.replace(Regex("""([+-]\d{2})(\d{2})$"""), "$1:$2")
        }
        // 走 DeviceOps 统一实现：aa dump 不能在设备侧接管道（会变空），见该函数注释
        val foreground = DeviceOps.foregroundBundle(hdc) ?: ""

        val data = buildJsonObject {
            put("connectKey", info.connectKey)
            put("model", info.model ?: "")
            put("apiVersion", info.apiVersion ?: "")
            put("softwareVersion", info.softwareVersion ?: "")
            put("abi", info.abi ?: "")
            put("osFullName", fullName ?: "")
            put("memTotal", memTotal)
            put("storage", storage)
            put("deviceTime", deviceTime)
            put("foregroundHint", foreground)
        }

        var artifact: String? = null
        if (ctx.archive) {
            val session = Session.create("env-dump", ctx.sessionRoot, ctx.cwd)
            val path = session.nextPath("device", "json")
            Session.writeText(path, Session.MANIFEST_JSON.encodeToString(JsonElement.serializer(), data))
            session.register(path, "device")
            session.writeManifest(info)
            artifact = path.toString()
            ctx.out.artifact(artifact, "device")
        }

        if (ctx.out.jsonMode) {
            ctx.out.finish("env-dump", true, data, Env.nowMillis() - started)
        } else {
            ctx.out.kv(listOf(
                "设备" to info.connectKey,
                "型号" to (info.model ?: "-"),
                "API" to (info.apiVersion ?: "-"),
                "版本" to (info.softwareVersion ?: "-"),
                "ABI" to (info.abi ?: "-"),
                "内存" to memTotal.ifBlank { "-" },
                "存储" to storage.ifBlank { "-" },
                "设备时间" to deviceTime.ifBlank { "-" },
                "前台" to foreground.lines().firstOrNull().orEmpty().ifBlank { "-" },
            ))
            artifact?.let { println("\n产物：$it") }
        }
        return ExitCode.OK
    }

    private fun param(hdc: Hdc, name: String): String? =
        hdc.shell("param get $name", 10_000).stdout.trim().ifBlank { null }
}
