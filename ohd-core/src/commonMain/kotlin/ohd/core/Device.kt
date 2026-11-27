package ohd.core

/** 错误码与语义绑定，供 CI/脚本按码分流，不靠解析文本。 */
object ExitCode {
    const val OK = 0
    const val USAGE = 2
    const val NO_DEVICE = 3
    const val ASSERT_FAILED = 4
    const val UNSUPPORTED = 5
    const val TIMEOUT = 6
    const val MISMATCH = 7
}

class OhdError(val code: Int, message: String, val hint: String? = null) : RuntimeException(message)

/** 设备指纹：证据包里"是不是同一环境"的唯一依据。 */
data class DeviceInfo(
    val connectKey: String,
    val model: String?,
    val apiVersion: String?,
    val softwareVersion: String?,
    val abi: String?,
) {
    fun asMap(): Map<String, String?> = linkedMapOf(
        "connectKey" to connectKey,
        "model" to model,
        "apiVersion" to apiVersion,
        "softwareVersion" to softwareVersion,
        "abi" to abi,
    )
}

class DeviceManager(private val hdc: Hdc) {

    fun listTargets(): List<String> {
        val targets = hdc.targets()
        if (targets.isEmpty()) {
            throw OhdError(ExitCode.NO_DEVICE, "未发现设备", "检查 USB/无线调试，或运行 `ohd doctor` 看 hdc 路径是否正确")
        }
        return targets
    }

    /** 单台自动选；多台必须显式 --device，避免"打到了错误设备"。 */
    fun select(preferred: String?): String {
        val targets = listTargets()
        if (!preferred.isNullOrBlank()) {
            if (targets.none { it == preferred } && targets.none { it.startsWith(preferred) }) {
                throw OhdError(ExitCode.NO_DEVICE, "指定的设备不在线：$preferred", "当前在线：${targets.joinToString()}")
            }
            return targets.first { it == preferred || it.startsWith(preferred) }
        }
        if (targets.size > 1) {
            throw OhdError(
                ExitCode.USAGE,
                "多个设备在线，必须用 --device 指定",
                "当前在线：${targets.joinToString()}",
            )
        }
        return targets.first()
    }

    fun info(key: String): DeviceInfo = DeviceInfo(
        connectKey = key,
        model = param(key, "const.product.model"),
        apiVersion = param(key, "const.ohos.apiversion"),
        softwareVersion = param(key, "const.product.software.version"),
        abi = param(key, "const.product.cpu.abilist"),
    )

    fun param(key: String, name: String): String? {
        val r = Hdc(hdc.exe, key, hdc.defaultTimeoutMs).shell("param get $name", 15_000)
        return r.stdout.trim().ifBlank { null }
    }
}
