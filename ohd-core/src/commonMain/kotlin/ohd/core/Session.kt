package ohd.core

import ohd.platform.Digest
import ohd.platform.fs

import ohd.platform.OhdPath
import ohd.platform.TimeFmt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.platform.fmt
import ohd.platform.RuntimeInfo

/**
 * 一次能力的会话目录：<root>/<yyyyMMdd-HHmmss>-<cap>/
 * 产物命名带序号，避免 hdc file recv 覆盖 mtime 导致排序错乱。
 */
/**
 * 当前时刻 epoch 毫秒。
 *
 * 走 `ohd.platform.Env`（跨平台），不是 core 自己的 `Env`（那是个工具发现器，没有时间能力）。
 * 全限定写法是为了避开同名冲突：本文件同包下有 `ohd.core.Env`。
 */
internal fun now(): Long = ohd.platform.Env.nowMillis()

class Session(val dir: OhdPath, val capability: String) {
    private val commands = mutableListOf<JsonObject>()
    private val artifacts = mutableListOf<JsonObject>()
    private var seq = 0

    /** 能力在收尾时标注本次结论，写进 manifest，供 summary 回看「跑满还是崩了」 */
    var verdict: String? = null
    private val startedAtMs: Long = now()

    init {
        fs.createDirectories(dir)
    }

    fun nextPath(kind: String, ext: String): OhdPath {
        seq += 1
        val name = fmt("%03d-%s.%s", seq, kind, ext)
        return dir.resolve(name)
    }

    fun register(path: OhdPath, kind: String): OhdPath {
        val size = runCatching { fs.size(path) }.getOrDefault(0L)
        val abs = path.toAbsolutePath().normalize()
        val base = dir.toAbsolutePath().normalize()
        // 会话内产物存相对路径（整个目录可搬迁）；会话外引用存绝对路径（如 log stop 引用 log start 的文件）
        val stored = if (abs.startsWith(base)) base.relativize(abs).toString() else abs.toString()
        artifacts += buildJsonObject {
            put("path", stored)
            put("kind", kind)
            put("bytes", size)
            put("sha256", sha256(path) ?: "")
        }
        return path
    }

    fun recordCommand(argv: List<String>, exit: Int, elapsedMs: Long) {
        if (commands.size >= 2000) return // 长跑保护，避免 manifest 无界增长
        commands += buildJsonObject {
            put("argv", argv.joinToString(" "))
            put("exit", exit)
            put("ms", elapsedMs)
        }
    }

    fun writeManifest(device: DeviceInfo?, extra: Map<String, String> = emptyMap()): OhdPath {
        val manifest = buildJsonObject {
            // 与 JSON 信封的 ohd/1 区分开：同名不同结构会让消费者无法判断拿到的是什么
            put("schema", "ohd-manifest/1")
            put("capability", capability)
            put("startedAt", TimeFmt.isoLocal(startedAtMs))
            put("finishedAt", TimeFmt.isoLocal(now()))
            device?.let { d ->
                put("device", buildJsonObject {
                    d.asMap().forEach { (k, v) -> put(k, v) }
                })
            }
            extra.forEach { (k, v) -> put(k, v) }
            put("commands", buildJsonArray { commands.forEach { add(it) } })
            put("artifacts", buildJsonArray { artifacts.forEach { add(it) } })
            put("env", buildJsonObject {
                put("java", RuntimeInfo.version)
                put("os", Platform.displayName)
                put("arch", Platform.arch)
                put("cwd", OhdPath.of("").toAbsolutePath().toString())
            })
        }
        val path = dir.resolve("manifest.json")
        writeText(path, MANIFEST_JSON.encodeToString(JsonElement.serializer(), manifest))
        return path
    }

    companion object {
        val MANIFEST_JSON = Json { prettyPrint = true; encodeDefaults = true }
        /** 目录名时间戳模式；与历史产物保持一致，不能改。 */
        private const val TS = "yyyyMMdd-HHmmss"

        /** 证据根目录。可见、名字标明用途：device 是每次操作的会话，cases 是打好的证据包。 */
        const val EVIDENCE_DIR = "ohd-evidence"

        fun evidenceRoot(cwd: OhdPath = OhdPath.of("").toAbsolutePath()): OhdPath = cwd.resolve(EVIDENCE_DIR)

        fun defaultRoot(cwd: OhdPath = OhdPath.of("").toAbsolutePath()): OhdPath = evidenceRoot(cwd).resolve("device")

        /** 可批量回放的录制脚本：ohd-evidence/record/record-<设备>-<时间>.ohd */
        fun recordScript(
            device: String,
            cwd: OhdPath = OhdPath.of("").toAbsolutePath(),
            timestampMs: Long = now(),
        ): OhdPath {
            val safe = device.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(safe.isNotEmpty()) { "设备标识为空" }
            val stamp = TimeFmt.format(timestampMs, TS)
            val dir = evidenceRoot(cwd).resolve("record")
            var path = dir.resolve("record-$safe-$stamp.ohd")
            var idx = 2
            while (fs.exists(path)) {
                path = dir.resolve("record-$safe-$stamp-$idx.ohd")
                idx += 1
            }
            return path
        }

        fun create(
            capability: String,
            root: OhdPath? = null,
            cwd: OhdPath = OhdPath.of("").toAbsolutePath(),
            timestampMs: Long = now(),
            label: String? = null,
        ): Session {
            val base = root ?: defaultRoot(cwd)
            // 多设备并行时同秒启动会撞目录名，用设备标识后缀隔开
            val suffix = Self.safeLabel(label)?.let { "-$it" } ?: ""
            val name = "${TimeFmt.format(timestampMs, TS)}-${sanitize(capability)}$suffix"
            var dir = base.resolve(name)
            var idx = 1
            while (fs.exists(dir)) {
                idx += 1
                dir = base.resolve("$name-$idx")
            }
            return Session(dir, capability)
        }

        fun writeText(path: OhdPath, text: String): OhdPath {
            path.toAbsolutePath().parent?.let { fs.createDirectories(it) }
            fs.writeString(path, text)
            return path
        }

        private fun sanitize(s: String): String =
            s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").take(24)

        // 走平台的 Digest（纯 Kotlin，JVM/K/N 同一份实现），不再用 java.security.MessageDigest：
        // 否则同一文件在两个入口可能算出不同摘要，双入口产物的 sha256 就不可比。
        private fun sha256(path: OhdPath): String? = runCatching {
            Digest.sha256Hex(fs.readBytes(path))
        }.getOrNull()
    }
}

object JsonOut {
    val encode: Json = Json { prettyPrint = true }
    val compact: Json = Json { prettyPrint = false }

    fun obj(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    fun str(v: String?): JsonElement = JsonPrimitive(v ?: "")
    fun bool(v: Boolean): JsonElement = JsonPrimitive(v)
    fun num(v: Long): JsonElement = JsonPrimitive(v)
    fun num(v: Double): JsonElement = JsonPrimitive(v)
}
