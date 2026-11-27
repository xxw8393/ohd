package ohd.core.symbol

import ohd.core.parsers.NativeFrame
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.ProcSpec
import ohd.platform.runProcess
import ohd.platform.fmt

/**
 * 符号化：日志里的 pc + so 名 → 函数名 + 行号。
 *
 * 三条硬规则（证据可信度全靠它们）：
 * 1. **buildId 必须校验**：本地 .so 与日志 buildId 不一致 → 标 BUILD_ID_MISMATCH，不给符号（错版本符号化比不符号化更有害）。
 * 2. `llvm-addr2line` 需与设备 abi 匹配（arm64 二进制用 DevEco 的 llvm，不是系统 llvm）。
 * 3. 找不到 so 时保留 pc 和 so 名，标注 MISSING_SO，绝不猜。
 */
class Symbolizer(
    private val addr2line: OhdPath,
    private val readelf: OhdPath?,
    private val soDirs: List<OhdPath>,
    /** true 时 buildId 不匹配也照常出符号，只保留备注；默认 false 拒绝 */
    private val allowMismatch: Boolean = false,
) {
    data class Resolved(
        val frame: NativeFrame,
        val soFile: OhdPath?,
        val buildIdLocal: String?,
        val buildIdMatch: Boolean,
        val function: String?,
        val location: String?,
        val note: String?,
    )

    private val cache = mutableMapOf<String, OhdPath?>()
    private val buildIdCache = mutableMapOf<OhdPath, String?>()

    fun findSo(name: String): OhdPath? = cache.getOrPut(name) {
        for (dir in soDirs) {
            if (!fs.isDirectory(dir)) continue
            // macOS 的 /tmp、/var 是符号链接，walk 默认不跟随 → 先解析成真实路径
            // 走平台的 realPath（JVM: toRealPath，K/N: realpath(3)），拿不到就退回原路径
            val real = fs.realPath(dir) ?: dir
            fs.walk(real, 8).use { s ->
                val hit = s.firstOrNull { fs.isRegularFile(it) && it.fileName?.toString() == name }
                if (hit != null) return@getOrPut hit
            }
        }
        null
    }

    fun readBuildId(so: OhdPath): String? = buildIdCache.getOrPut(so) {
        val elf = readelf ?: return@getOrPut null
        val r = run(listOf(elf.toString(), "-n", so.toString()), 15_000) ?: return@getOrPut null
        Regex("""Build ID:\s*([0-9a-fA-F]+)""").find(r)?.groupValues?.get(1)
    }

    fun resolveFrame(frame: NativeFrame): Resolved {
        val so = findSo(frame.soName)
            ?: return Resolved(frame, null, null, false, null, null, "MISSING_SO")
        val localId = readBuildId(so)
        val matched = frame.buildId != null && localId != null &&
            localId.lowercase().startsWith(frame.buildId.lowercase().take(16))
        if (!matched && frame.buildId != null && localId != null && !allowMismatch) {
            return Resolved(frame, so, localId, false, null, null, "BUILD_ID_MISMATCH")
        }
        val idNote = if (!matched && frame.buildId != null && localId != null) "BUILD_ID_MISMATCH" else null

        // 预检：SDK 的 llvm/lib/arm-linux-ohos/libc++.so 是 linker script（ASCII），
        // 不是 ELF。直接喂给 addr2line 会得到 "not recognized as a valid object file"，
        // 若不拦就会把这条错误信息当成函数名写进栈——假信息比缺信息更危险。
        if (!isElf(so)) {
            return Resolved(frame, so, localId, matched, null, null, "LOCAL_SO_NOT_ELF")
        }

        val out = run(listOf(addr2line.toString(), "-e", so.toString(), "-f", "-C", "-i", fmt("0x%x", frame.pc)), 20_000)
            ?: return Resolved(frame, so, localId, matched, null, null, "ADDR2LINE_FAILED")
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // 兜底：任何符号化工具的报错输出都不能当符号用
        if (lines.any { it.contains("LLVMSymbolizer:") || it.contains("not recognized as a valid object file") || it.contains("error reading file") }) {
            return Resolved(frame, so, localId, matched, null, null, "LOCAL_SO_NOT_ELF")
        }
        if (lines.isEmpty() || lines.first().contains("??")) {
            return Resolved(frame, so, localId, matched, null, null, "NO_SYMBOL")
        }
        val fn = lines.getOrNull(0)
        val loc = lines.getOrNull(1)?.takeIf { it.contains(":") }
        return Resolved(frame, so, localId, matched, fn, loc, idNote)
    }

    fun resolveAll(frames: List<NativeFrame>, limit: Int = 200): List<Resolved> =
        frames.take(limit).map { resolveFrame(it) }

    private fun isElf(p: OhdPath): Boolean = runCatching {
        val b = fs.readBytesRange(p, 0L, 4)
        b.size == 4 &&
            b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
            b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()
    }.getOrDefault(false)

    private fun run(cmd: List<String>, timeoutMs: Long): String? = try {
        // 旧实现 redirectErrorStream(true) + waitFor(timeout) + 超时 destroyForcibly 后返回 null。
        // runProcess 内部同样在超时时杀子进程，只是把「超时」标成 timedOut 而不是抛中断。
        val r = runProcess(ProcSpec(command = cmd, timeoutMillis = timeoutMs))
        if (r.spawnError != null || r.timedOut) null else r.stdout + r.stderr
    } catch (e: Exception) {
        null
    }
}
