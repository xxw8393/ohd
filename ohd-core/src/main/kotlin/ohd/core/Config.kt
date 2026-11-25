package ohd.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 用户级配置：把「我的机器上工具装在哪」持久化，免得每次敲 `--hdc` / `--llvm`。
 *
 * 形态：`~/.ohd/config.json`，内容就是一批**环境变量名 → 值**。
 *
 * 为什么用环境变量名做键：`Env` 的发现顺序已经是「显式参数 → 环境变量 → 平台默认 → PATH」，
 * 配置只需要插在「环境变量」这一层，不用给每个工具再造一套键名和优先级。
 * 覆盖顺序：CLI 显式参数 > 配置文件 > 进程环境变量 > 自动探测。
 *
 * 典型用法：
 * ```
 * ohd config set OHD_HDC  /Users/me/Library/OpenHarmony/Sdk/20/toolchains/hdc
 * ohd config set JAVA_HOME /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home
 * ohd config list
 * ```
 */
object OhdConfig {
    private const val SCHEMA = "ohd-config/1"

    /** 常用键：`ohd config keys` 直接列出来，省得用户去猜。 */
    val KNOWN_KEYS: List<Pair<String, String>> = listOf(
        "OHD_HDC" to "hdc 可执行文件（等价 --hdc）",
        "OHD_LLVM_ADDR2LINE" to "llvm-addr2line（等价 --llvm）",
        "JAVA_HOME" to "JDK 根目录；扩展的 .java 与脚本里的 java 用它",
        "OHD_PYTHON" to "python 解释器路径（扩展 .py 用）",
        "OHD_DEVICE" to "默认设备 key（等价 --device）",
        "OHD_DEVICES" to "默认设备集合，逗号分隔；多台时这些命令会并行跑这几台（--all 的子集）",
        "OHD_EXT" to "扩展目录，多个用 ${java.io.File.pathSeparator} 分隔",
        "OHD_SESSION_ROOT" to "产物根目录（等价 --session-root）",
        "OHD_LOG_COMPRESS" to "日志格式：gzip（默认）/ none",
        "OHOS_SDK_HOME" to "OpenHarmony SDK 根（hdc/llvm 会从这里找）",
        "JAVA_OPTS" to "附加给 java 子进程的参数（扩展用）",
    )

    /** `$OHD_CONFIG` 可指到共享目录或测试文件；否则 `<home>/.ohd/config.json`。 */
    fun path(sysenv: (String) -> String? = System::getenv): Path {
        sysenv("OHD_CONFIG")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it).toAbsolutePath() }
        return Platform.home.resolve(".ohd").resolve("config.json")
    }

    /** 进程内缓存：一次 CLI 生命周期配置不会变，避免 Env 每次探测都读盘。 */
    private val cache: Map<String, String> by lazy { load() }

    fun load(path: Path = path()): LinkedHashMap<String, String> {
        if (!Files.isRegularFile(path)) return linkedMapOf()
        return runCatching {
            val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val env = root["env"]?.jsonObject ?: return linkedMapOf()
            linkedMapOf<String, String>().also { m ->
                env.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { m[k] = it } }
            }
        }.getOrElse { linkedMapOf() }
    }

    fun save(values: Map<String, String>, path: Path = path()) {
        Files.createDirectories(path.toAbsolutePath().parent)
        val body = buildJsonObject {
            put("schema", SCHEMA)
            put("env", buildJsonObject {
                values.toSortedMap().forEach { (k, v) -> put(k, v) }
            })
        }
        Files.writeString(path, body.toString() + "\n")
    }

    fun set(key: String, value: String, path: Path = path()): Map<String, String> {
        val cur = load(path)
        cur[key] = value
        save(cur, path)
        return cur
    }

    fun unset(key: String, path: Path = path()): Boolean {
        val cur = load(path)
        if (cur.remove(key) == null) return false
        save(cur, path)
        return true
    }

    /**
     * 给 Env 用的查询函数：配置文件覆盖进程环境变量。
     * 但**不覆盖**调用方显式传入的 `--hdc/--llvm`（那是 CLI 参数，已单独走 explicit）。
     */
    fun sysenv(base: (String) -> String? = System::getenv): (String) -> String? =
        { name -> cache[name] ?: base(name) }

    /** 全部生效值（配置优先），供 `ohd config list` 与扩展注入。 */
    fun effective(sysenv: (String) -> String? = System::getenv): Map<String, String> =
        buildMap {
            KNOWN_KEYS.forEach { (k, _) -> sysenv(k)?.takeIf { it.isNotBlank() }?.let { put(k, it) } }
            load().forEach { (k, v) -> put(k, v) }
        }
}
