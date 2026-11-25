package ohd.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 一次能力的会话目录：<root>/<yyyyMMdd-HHmmss>-<cap>/
 * 产物命名带序号，避免 hdc file recv 覆盖 mtime 导致排序错乱。
 */
class Session(val dir: Path, val capability: String) {
    private val commands = mutableListOf<JsonObject>()
    private val artifacts = mutableListOf<JsonObject>()
    private var seq = 0

    /** 能力在收尾时标注本次结论，写进 manifest，供 summary 回看「跑满还是崩了」 */
    var verdict: String? = null
    private val startedAt: OffsetDateTime = OffsetDateTime.now()

    init {
        Files.createDirectories(dir)
    }

    fun nextPath(kind: String, ext: String): Path {
        seq += 1
        val name = String.format("%03d-%s.%s", seq, kind, ext)
        return dir.resolve(name)
    }

    fun register(path: Path, kind: String): Path {
        val size = runCatching { Files.size(path) }.getOrDefault(0L)
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

    fun writeManifest(device: DeviceInfo?, extra: Map<String, String> = emptyMap()): Path {
        val manifest = buildJsonObject {
            // 与 JSON 信封的 ohd/1 区分开：同名不同结构会让消费者无法判断拿到的是什么
            put("schema", "ohd-manifest/1")
            put("capability", capability)
            put("startedAt", startedAt.toString())
            put("finishedAt", OffsetDateTime.now().toString())
            device?.let { d ->
                put("device", buildJsonObject {
                    d.asMap().forEach { (k, v) -> put(k, v) }
                })
            }
            extra.forEach { (k, v) -> put(k, v) }
            put("commands", buildJsonArray { commands.forEach { add(it) } })
            put("artifacts", buildJsonArray { artifacts.forEach { add(it) } })
            put("env", buildJsonObject {
                put("java", System.getProperty("java.version"))
                put("os", Platform.displayName)
                put("arch", Platform.arch)
                put("cwd", Paths.get("").toAbsolutePath().toString())
            })
        }
        val path = dir.resolve("manifest.json")
        writeText(path, MANIFEST_JSON.encodeToString(JsonElement.serializer(), manifest))
        return path
    }

    companion object {
        val MANIFEST_JSON = Json { prettyPrint = true; encodeDefaults = true }
        private val TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** 证据根目录。可见、名字标明用途：device 是每次操作的会话，cases 是打好的证据包。 */
        const val EVIDENCE_DIR = "ohd-evidence"

        fun evidenceRoot(cwd: Path = Paths.get("").toAbsolutePath()): Path = cwd.resolve(EVIDENCE_DIR)

        fun defaultRoot(cwd: Path = Paths.get("").toAbsolutePath()): Path = evidenceRoot(cwd).resolve("device")

        /** 可批量回放的录制脚本：ohd-evidence/record/record-<设备>-<时间>.ohd */
        fun recordScript(
            device: String,
            cwd: Path = Paths.get("").toAbsolutePath(),
            timestamp: OffsetDateTime = OffsetDateTime.now(),
        ): Path {
            val safe = device.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(safe.isNotEmpty()) { "设备标识为空" }
            val stamp = TS.format(timestamp)
            val dir = evidenceRoot(cwd).resolve("record")
            var path = dir.resolve("record-$safe-$stamp.ohd")
            var idx = 2
            while (Files.exists(path)) {
                path = dir.resolve("record-$safe-$stamp-$idx.ohd")
                idx += 1
            }
            return path
        }

        fun create(
            capability: String,
            root: Path? = null,
            cwd: Path = Paths.get("").toAbsolutePath(),
            timestamp: OffsetDateTime = OffsetDateTime.now(),
            label: String? = null,
        ): Session {
            val base = root ?: defaultRoot(cwd)
            // 多设备并行时同秒启动会撞目录名，用设备标识后缀隔开
            val suffix = Self.safeLabel(label)?.let { "-$it" } ?: ""
            val name = "${TS.format(timestamp)}-${sanitize(capability)}$suffix"
            var dir = base.resolve(name)
            var idx = 1
            while (Files.exists(dir)) {
                idx += 1
                dir = base.resolve("$name-$idx")
            }
            return Session(dir, capability)
        }

        fun writeText(path: Path, text: String): Path {
            Files.createDirectories(path.toAbsolutePath().parent)
            Files.write(path, text.toByteArray(Charsets.UTF_8))
            return path
        }

        private fun sanitize(s: String): String =
            s.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }.joinToString("").take(24)

        private fun sha256(path: Path): String? = runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { b -> "%02x".format(b) }
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
