package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.JsonOut
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parsers.CrashParser
import ohd.core.symbol.Symbolizer
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 栈符号化。**buildId 不匹配绝不出符号**——错版本的符号比没有符号更有害，
 * 这一条决定了证据包能不能被开发直接采信。
 */
class SymCap : Capability {
    override val name = "sym"
    override val summary = "崩溃栈符号化：llvm-addr2line + buildId 校验"
    override val usage = "ohd sym --crash <faultlog.txt> [--so-dir a:b] [--limit 40] [--allow-mismatch] [--session <会话目录>] [--json]\n       ohd sym --latest [--so-dir ...]"
    override val example = "ohd sym --crash faultlog.txt --so-dir /path/libs --allow-mismatch"
    override val accepts: Set<String> = setOf("crash", "latest", "limit", "so-dir", "hap", "no-auto-so", "allow-mismatch", "session")

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val addr2line = ctx.env.llvmAddr2line
        if (!addr2line.found || addr2line.path == null) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "未找到 llvm-addr2line（来源 ${addr2line.source}）",
                "装 DevEco Studio，或 --llvm 指向 native/llvm/bin/llvm-addr2line",
            )
        }
        val readelf = ctx.env.llvmReadelf.path
        if (readelf == null) {
            ctx.out.note("NO_READELF", "warn", "未找到 llvm-readelf，无法校验 buildId，符号结果仅作参考")
        }

        val explicitDirs = ctx.options.str("so-dir")
            ?.split(File.pathSeparatorChar, ',')
            ?.filter { it.isNotBlank() }
            ?.map { Paths.get(it) }
            ?: emptyList()
        // 默认开启自动发现：测试与开发通常在同一仓库，debug .so 就在 build/ 下，
        // 不该反过来要求开发交文件。--no-auto-so 可关掉。
        // --hap：测试侧通常手里就有安装包，so 在 libs/<abi>/ 下，不必再找开发要文件。
        val hapDirs = ctx.options.str("hap")
            ?.split(File.pathSeparatorChar, ',')
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { extractSosFromHap(it) }
            ?: emptyList()
        val autoSo = !ctx.options.bool("no-auto-so")
        val discovered = if (autoSo) discoverSoDirs(ctx.cwd) else emptyList()
        val soDirs = (explicitDirs + hapDirs + discovered).distinct()
        if (soDirs.isEmpty()) {
            ctx.out.note(
                "NO_SO_DIR",
                "warn",
                "没有 --so-dir 且未在 build/ 下发现 .so，只能显示 pc 与 so 名；" +
                    "带符号的 .so 一般是 build/default/intermediates/libs/<abi>/ 或 debug 构建产物",
            )
        } else if (explicitDirs.isEmpty() && discovered.isNotEmpty()) {
            ctx.out.info("自动发现 ${discovered.size} 个 .so 目录（--no-auto-so 可关闭）")
        }

        // --session 指回崩溃所在会话：符号结果要落进那个目录，bundle 才认得到
        val session = ctx.options.str("session")?.let { dir ->
            val p = Paths.get(dir).toAbsolutePath().normalize()
            if (!Files.isDirectory(p)) {
                ctx.out.note("NO_SESSION", "error", "会话目录不存在：$p")
                return ExitCode.USAGE
            }
            Session(p, p.fileName.toString().substringAfterLast('-'))
        } ?: Hdcs.session(ctx, "sym")
        val (text, sourceName, saved) = loadCrash(ctx, session)
        val report = CrashParser.parse(text, sourceName)
        if (report.nativeFrames.isEmpty()) {
            ctx.out.note("NO_NATIVE_FRAME", "warn", "该日志没有 native 栈帧（纯 JS 崩溃只能看 Stacktrace / JS 栈）")
            if (report.jsStack.isNotEmpty() && !ctx.out.jsonMode) {
                println()
                ctx.out.info("JS 栈：")
                report.jsStack.forEach { println("  $it") }
            }
            return ExitCode.UNSUPPORTED
        }

        val allowMismatch = ctx.options.bool("allow-mismatch")
        val sym = Symbolizer(addr2line.path, readelf, soDirs, allowMismatch)
        val limit = ctx.options.int("limit", 40)
        val resolved = sym.resolveAll(report.nativeFrames, limit)

        val okCount = resolved.count { it.note == null }
        val mismatched = resolved.count { it.note == "BUILD_ID_MISMATCH" }
        val missing = resolved.count { it.note == "MISSING_SO" }
        val noSymbol = resolved.count { it.note == "NO_SYMBOL" }

        if (mismatched > 0) {
            if (allowMismatch) {
                ctx.out.note(
                    "BUILD_ID_MISMATCH",
                    "warn",
                    "$mismatched 帧的本地 .so 与日志 buildId 不一致；已按 --allow-mismatch 输出，函数名可能错位",
                )
            } else {
                ctx.out.note(
                    "BUILD_ID_MISMATCH",
                    "error",
                    "$mismatched 帧的本地 .so 与日志 buildId 不一致，已拒绝出符号（换对应版本 debug .so 再跑，或加 --allow-mismatch）",
                )
            }
        }
        if (missing > 0) {
            ctx.out.note("MISSING_SO", "warn", "$missing 帧的 .so 未在 --so-dir 下找到")
        }

        val symbolsFile = session?.let { s ->
            val p = s.nextPath("symbols", "json")
            val payload = buildJsonObject {
                put("schema", "ohd-symbols/1")
                put("source", sourceName)
                put("crashKind", report.kind)
                put("reason", report.reason)
                put("module", report.module)
                put("buildInfo", report.buildInfo)
                put("frames", buildJsonArray {
                    resolved.forEach { r ->
                        add(buildJsonObject {
                            put("index", r.frame.index)
                            put("pc", "0x%x".format(r.frame.pc))
                            put("so", r.frame.soName)
                            put("buildIdLog", r.frame.buildId ?: "")
                            put("buildIdLocal", r.buildIdLocal ?: "")
                            put("function", r.function ?: "")
                            put("logSymbol", r.frame.inlineSymbol ?: "")
                            put("location", r.location ?: "")
                            put("note", r.note ?: "")
                        })
                    }
                })
            }
            Session.writeText(p, JsonOut.encode.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), payload))
            s.register(p, "symbols")
            p
        }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("source", sourceName)
                put("crashKind", report.kind)
                put("crashReason", report.reason)
                put("buildInfo", report.buildInfo)
                put("frames", buildJsonArray {
                    resolved.forEach { r ->
                        add(buildJsonObject {
                            put("index", r.frame.index)
                            put("pc", "0x%x".format(r.frame.pc))
                            put("so", r.frame.soName)
                            put("buildIdLog", r.frame.buildId ?: "")
                            put("buildIdLocal", r.buildIdLocal ?: "")
                            put("buildIdMatch", r.buildIdMatch)
                            put("function", r.function ?: "")
                            put("location", r.location ?: "")
                            put("note", r.note ?: "")
                        })
                    }
                })
                put("resolved", okCount)
                put("buildIdMismatch", mismatched)
                put("missingSo", missing)
                put("noSymbol", noSymbol)
                put("savedLog", saved?.toString() ?: "")
                put("symbolsFile", symbolsFile?.toString() ?: "")
            }
            ctx.out.finish("sym", mismatched == 0, data, System.currentTimeMillis() - started)
        } else {
            ctx.out.kv(
                listOf(
                    "日志" to sourceName,
                    "类型" to report.kind,
                    "原因" to report.reason,
                    "构建" to report.buildInfo,
                    "帧数" to "${resolved.size} / ${report.nativeFrames.size}",
                    "已符号化" to okCount.toString(),
                    "buildId 不匹配" to mismatched.toString(),
                    "缺 so" to missing.toString(),
                    "无符号" to noSymbol.toString(),
                ),
            )
            println()
            ctx.out.table(
                listOf("#", "pc", "so", "函数", "位置", "状态"),
                resolved.map { r ->
                    listOf(
                        r.frame.index.toString(),
                        "0x%x".format(r.frame.pc),
                        r.frame.soName,
                        (r.function ?: r.frame.inlineSymbol ?: "-").take(48),
                        (r.location ?: "-").take(40),
                        r.note ?: "OK",
                    )
                },
            )
        }
        // 没有这一步，symbols 产物就进不了证据包（目录会留空，bundle 报「会话缺 manifest.json」）
        // 复用已有会话时不覆盖它的 manifest：否则会把 monkey 的现场信息洗成只剩 symbols
        if (ctx.options.str("session") == null) session?.writeManifest(null, mapOf("source" to sourceName))
        return if (mismatched > 0 && okCount == 0 && !allowMismatch) ExitCode.MISMATCH else ExitCode.OK
    }

    private data class Loaded(val text: String, val name: String, val saved: java.nio.file.Path?)

    /**
     * 在仓库里找可能存放 .so 的目录。
     *
     * 只认「目录名是 arm64/arm64-v8a/aarch64 且下面真有 .so」的目录，避免把整棵树当候选。
     * 深度限制 8 层，够到 build/default/intermediates/libs/arm64 这种典型路径。
     */
    /**
     * 从 hap 解出 .so。hap 就是 zip，native 库在 libs/<abi>/ 下。
     *
     * 解出的 so 若是 strip 过的，addr2line 仍然给不出行号——但 buildId 校验仍有效，
     * 至少能确认「这个版本的 so 与崩溃现场是否一致」，比完全无符号多一层约束。
     */
    private fun extractSosFromHap(path: String): java.nio.file.Path? = runCatching {
        val src = Paths.get(path)
        if (!Files.isRegularFile(src)) return@runCatching null
        val dir = Files.createTempDirectory("ohd-so-")
        java.util.zip.ZipFile(src.toFile()).use { zf ->
            zf.entries().asSequence()
                .filter { it.name.endsWith(".so", ignoreCase = true) }
                .forEach { e ->
                    val name = e.name.substringAfterLast('/')
                    if (name.isNotBlank()) {
                        Files.copy(
                            zf.getInputStream(e), dir.resolve(name),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                }
        }
        if (Files.list(dir).use { it.count() } == 0L) null else dir
    }.getOrNull()

    private fun discoverSoDirs(root: java.nio.file.Path): List<java.nio.file.Path> = runCatching {
        Files.walk(root, 8).use { st ->
            st.filter { p ->
                Files.isDirectory(p) && p.fileName?.toString()?.matches(Regex("arm64|arm64-v8a|aarch64")) == true
            }.filter { d ->
                runCatching {
                    Files.list(d).use { inner -> inner.anyMatch { it.fileName.toString().endsWith(".so") } }
                }.getOrDefault(false)
            }.toList()
        }
    }.getOrDefault(emptyList())

    private fun loadCrash(ctx: RunContext, session: Session?): Loaded {
        val explicit = ctx.options.str("crash")
        if (explicit != null) {
            val p = Paths.get(explicit)
            if (!Files.isRegularFile(p)) throw OhdError(ExitCode.USAGE, "崩溃日志不存在：$p")
            return Loaded(Files.readString(p), p.fileName.toString(), p)
        }
        val hdc = Hdcs.forDevice(ctx)
        val list = Hdcs.sh(session, hdc, "hidumper -s 1201 -a '-p Faultlogger -l'", 30_000)
        val name = list.stdout.lines()
            .map { it.trim() }
            .filter { it.matches(Regex("""[a-z]+-[A-Za-z0-9._]+-\d+-\d{14}.*""")) }
            .map { it.substringBefore(' ') }
            .firstOrNull()
            ?: throw OhdError(ExitCode.ASSERT_FAILED, "设备上没有故障日志", "用 --crash 指定本地日志文件")
        val r = Hdcs.sh(session, hdc, "hidumper -s 1201 -a '-p Faultlogger -f $name'", 60_000)
        Hdcs.requireOk(r, "读取故障文件 $name")
        val saved = session?.let { s ->
            val p = s.nextPath("faultlog", "txt")
            Session.writeText(p, r.stdout)
            s.register(p, "faultlog")
            p
        }
        return Loaded(r.stdout, name, saved)
    }
}
