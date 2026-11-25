package ohd.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

/** 平台事实：跨端差异全部收敛到这里，其余代码不出现 os.name 判断。 */
object Platform {
    private val os: String = System.getProperty("os.name", "").lowercase(Locale.ROOT)
    val isMac: Boolean = os.contains("mac")
    val isWindows: Boolean = os.contains("win")
    val isLinux: Boolean = os.contains("nux") || os.contains("nix")
    val exeSuffix: String = if (isWindows) ".exe" else ""
    val home: Path = Paths.get(System.getProperty("user.home", ".")).toAbsolutePath()
    val displayName: String = when {
        isMac -> "macOS"
        isWindows -> "Windows"
        isLinux -> "Linux"
        else -> os.ifBlank { "unknown" }
    }
    val arch: String = System.getProperty("os.arch", "unknown")

    /** hdc 在 Windows 上对反斜杠敏感：一律正斜杠 + 绝对路径。 */
    fun devicePath(p: Path): String = p.toAbsolutePath().toString().replace('\\', '/')

    /** 只用于表格对齐的近似宽度：CJK 记 2 列。 */
    fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) {
            w += when {
                ch.code in 0x1100..0x115F -> 2
                ch.code in 0x2E80..0xA4CF -> 2
                ch.code in 0xAC00..0xD7A3 -> 2
                ch.code in 0xF900..0xFAFF -> 2
                ch.code in 0xFE30..0xFE4F -> 2
                ch.code in 0xFF00..0xFF60 -> 2
                ch.code in 0xFFE0..0xFFE6 -> 2
                else -> 1
            }
        }
        return w
    }
}

/** 一个外部工具的定位结果；source 用于 doctor 里说清"从哪找到的"，便于同事排查。 */
data class ToolLocation(val path: Path?, val source: String) {
    val found: Boolean get() = path != null
    fun argv0(): String = path?.toString() ?: ""
}

/**
 * 环境发现：hdc / llvm-addr2line / SDK 根。
 * 顺序固定为「显式参数 → 环境变量 → 平台默认 SDK → PATH」，任一命中即止。
 */
class Env(
    private val explicitHdc: String? = null,
    private val explicitLlvm: String? = null,
    private val explicitAdb: String? = null,
    private val sysenv: (String) -> String? = { System.getenv(it) },
) {
    val hdc: ToolLocation by lazy { discoverHdc() }
    val adb: ToolLocation by lazy { discoverAdb() }

    /** simctl：显式 → OHD_SIMCTL → Xcode 的 DEVELOPER_DIR → 默认 Xcode 安装位 → PATH。 */
    val simctl: ToolLocation by lazy { discoverSimctl() }

    /** 触摸注入客户端（idb，Meta 的 iOS Development Bridge）。 */
    val idb: ToolLocation by lazy { discoverIdb() }

    /** iPhone 真机通道（devicectl，Xcode 15+ 的 CoreDevice 工具）。 */
    val devicectl: ToolLocation by lazy { discoverDevicectl() }
    val llvmAddr2line: ToolLocation by lazy { discover("llvm-addr2line") }

    /** 校验 .so 的 Build ID 用（与 addr2line 同目录）。 */
    val llvmReadelf: ToolLocation by lazy { discover("llvm-readelf") }

    fun sdkRoots(): List<Path> {
        val roots = mutableListOf<Path>()
        sysenv("OHOS_SDK_HOME")?.takeIf { it.isNotBlank() }?.let { roots.add(Paths.get(it)) }
        sysenv("DEVECO_SDK_HOME")?.takeIf { it.isNotBlank() }?.let { roots.add(Paths.get(it)) }
        when {
            Platform.isMac -> roots.add(Platform.home.resolve("Library/OpenHarmony/Sdk"))
            Platform.isWindows -> {
                sysenv("LOCALAPPDATA")?.let { roots.add(Paths.get(it).resolve("OpenHarmony/Sdk")) }
                roots.add(Platform.home.resolve("AppData/Local/OpenHarmony/Sdk"))
            }
            Platform.isLinux -> {
                roots.add(Platform.home.resolve("OpenHarmony/Sdk"))
                roots.add(Paths.get("/opt/openharmony/Sdk"))
            }
        }
        return roots.filter { Files.isDirectory(it) }
    }

    private fun discoverHdc(): ToolLocation {
        val name = "hdc" + Platform.exeSuffix
        val explicit = explicitHdc?.takeIf { it.isNotBlank() } ?: sysenv("OHD_HDC") ?: sysenv("HDC")
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            val resolved = if (Files.isDirectory(p)) p.resolve(name) else p
            return ToolLocation(resolved.toAbsolutePath(), "argument/env")
        }
        for (root in sdkRoots()) {
            direct(root.resolve("toolchains/$name"))?.let { return ToolLocation(it, "sdk:${root}") }
            subdirsByVersionDesc(root).forEach { ver ->
                direct(ver.resolve("toolchains/$name"))?.let { return ToolLocation(it, "sdk:${ver}") }
            }
        }
        onPath(name)?.let { return ToolLocation(it, "PATH") }
        return ToolLocation(null, "not-found")
    }

    /** adb：显式 → OHD_ADB → ANDROID_HOME/SDK → 平台默认 SDK → PATH。 */
    private fun discoverIdb(): ToolLocation {
        val explicit = sysenv("OHD_IDB")
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            return ToolLocation(if (Files.isExecutable(p)) p.toAbsolutePath() else null, "argument/env")
        }
        // pip --user 的安装目录通常不在 PATH 上，locate() 里已包含这些候选
        val hit = IdbInjector.locate(null)
        return ToolLocation(hit, if (hit != null) "found" else "not-found")
    }

    private fun discoverDevicectl(): ToolLocation {
        val explicit = sysenv("OHD_DEVICECTL")
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            val resolved = if (Files.isDirectory(p)) p.resolve("devicectl") else p
            return ToolLocation(resolved.toAbsolutePath(), "argument/env")
        }
        if (!Platform.isMac) return ToolLocation(null, "not-macos")
        val devDir = sysenv("DEVELOPER_DIR") ?: "/Applications/Xcode.app/Contents/Developer"
        val cand = Paths.get(devDir, "usr", "bin", "devicectl")
        if (Files.isExecutable(cand)) return ToolLocation(cand.toAbsolutePath(), "xcode:$devDir")
        onPath("devicectl")?.let { return ToolLocation(it, "PATH") }
        return ToolLocation(null, "not-found")
    }

    private fun discoverSimctl(): ToolLocation {
        val explicit = sysenv("OHD_SIMCTL")
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            val resolved = if (Files.isDirectory(p)) p.resolve("simctl") else p
            return ToolLocation(resolved.toAbsolutePath(), "argument/env")
        }
        // 模拟器只在 macOS 上存在；其他平台直接判定为不可用，不做无意义的探测
        if (!Platform.isMac) return ToolLocation(null, "not-macos")
        val devDir = sysenv("DEVELOPER_DIR") ?: "/Applications/Xcode.app/Contents/Developer"
        val cand = Paths.get(devDir, "usr", "bin", "simctl")
        if (Files.isExecutable(cand)) return ToolLocation(cand.toAbsolutePath(), "xcode:$devDir")
        onPath("simctl")?.let { return ToolLocation(it, "PATH") }
        return ToolLocation(null, "not-found")
    }

    private fun discoverAdb(): ToolLocation {
        val name = "adb" + Platform.exeSuffix
        val explicit = explicitAdb?.takeIf { it.isNotBlank() } ?: sysenv("OHD_ADB")
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            val resolved = if (Files.isDirectory(p)) p.resolve(name) else p
            return ToolLocation(resolved.toAbsolutePath(), "argument/env")
        }
        val candidates = mutableListOf<Path>()
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").forEach { varName ->
            sysenv(varName)?.takeIf { it.isNotBlank() }?.let { candidates.add(Paths.get(it, "platform-tools", name)) }
        }
        when {
            Platform.isMac -> candidates.add(Platform.home.resolve("Library/Android/sdk/platform-tools/$name"))
            Platform.isWindows -> sysenv("LOCALAPPDATA")?.let {
                candidates.add(Paths.get(it, "Android/Sdk/platform-tools/$name"))
            }
            Platform.isLinux -> {
                candidates.add(Platform.home.resolve("Android/Sdk/platform-tools/$name"))
                candidates.add(Paths.get("/usr/lib/android-sdk/platform-tools/$name"))
            }
        }
        candidates.forEach { if (Files.isExecutable(it)) return ToolLocation(it, "sdk-default") }
        onPath(name)?.let { return ToolLocation(it, "PATH") }
        return ToolLocation(null, "not-found")
    }

    private fun discover(tool: String): ToolLocation {
        val name = tool + Platform.exeSuffix
        val explicit = if (tool == "llvm-addr2line") {
            explicitLlvm?.takeIf { it.isNotBlank() } ?: sysenv("OHD_LLVM_ADDR2LINE")
        } else null
        if (!explicit.isNullOrBlank()) {
            val p = Paths.get(explicit)
            val resolved = if (Files.isDirectory(p)) p.resolve(name) else p
            return ToolLocation(resolved.toAbsolutePath(), "argument/env")
        }
        val candidates = mutableListOf<Path>()
        if (Platform.isMac) {
            candidates.add(Paths.get("/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/llvm/bin/$name"))
            candidates.add(Paths.get("/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/toolchains/$name"))
        }
        if (Platform.isWindows) {
            listOf("C:/Program Files/Huawei/DevEco Studio", "C:/Program Files/DevEco Studio").forEach {
                candidates.add(Paths.get("$it/sdk/default/openharmony/native/llvm/bin/$name"))
            }
            sysenv("LOCALAPPDATA")?.let { candidates.add(Paths.get("$it/Huawei/DevEco Studio/sdk/default/openharmony/native/llvm/bin/$name")) }
        }
        for (root in sdkRoots()) {
            subdirsByVersionDesc(root).forEach { ver ->
                candidates.add(ver.resolve("native/llvm/bin/$name"))
                candidates.add(ver.resolve("toolchains/$name"))
            }
        }
        candidates.forEach { if (Files.isExecutable(it)) return ToolLocation(it, "sdk-default") }
        onPath(name)?.let { return ToolLocation(it, "PATH") }
        return ToolLocation(null, "not-found")
    }

    private fun direct(p: Path): Path? = if (Files.isExecutable(p)) p.toAbsolutePath() else null

    private fun onPath(name: String): Path? {
        val pathEnv = sysenv("PATH") ?: sysenv("Path") ?: return null
        for (dir in pathEnv.split(java.io.File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            val cand = Paths.get(dir).resolve(name)
            if (Files.isExecutable(cand)) return cand.toAbsolutePath()
        }
        return null
    }

    /** SDK 下常有 20 / 12 之类版本目录，取数值最大者优先。 */
    private fun subdirsByVersionDesc(root: Path): List<Path> {
        val children = try {
            Files.list(root).use { s -> s.toList() }
        } catch (_: Exception) {
            return emptyList()
        }
        return children.filter { Files.isDirectory(it) }
            .sortedWith(compareByDescending<Path> { verNum(it.fileName.toString()) }.thenByDescending { it.fileName.toString() })
    }

    private fun verNum(name: String): Int = name.filter { it.isDigit() }.toIntOrNull() ?: -1
}
