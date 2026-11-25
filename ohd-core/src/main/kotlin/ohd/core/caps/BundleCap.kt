package ohd.core.caps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.JsonOut
import ohd.core.OhdError
import ohd.core.Platform
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.TOOL_VERSION
import ohd.core.actions.UiActions
import ohd.core.analysis.CaseFacts
import ohd.core.analysis.CaseReport
import ohd.image.GifWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 证据包组装（`ohd-case/1`）。
 *
 * 这个能力的全部价值在于**诚实**：包里有什么就写什么，缺什么就明确写「缺」，
 * 并据此推导出「因此不能下什么结论」。AI 拿到包后不该高估自己的视野，
 * 所以 COMPLETENESS.json 里的 forbiddenConclusions 是硬约束，不是装饰。
 *
 * 会话默认：无 `--session` 时按 [DEFAULT_SINCE]（2h）合并——交研发场景要时间窗，
 * 不是「只打最新一个」。脚本要旧行为加 `--latest-only`。
 */
class BundleCap : Capability {
    override val name = "bundle"
    override val summary = "会话产物 → ohd-case/1 证据包（含 COMPLETENESS.json 与禁止结论清单）"
    override val usage =
        "ohd bundle [--since <30m|2h|0|datetime>] [--latest-only] [--session <dir,a,b>] " +
            "[--out <dir>] [--name <名字>] [--redact] [--report] [--zip] [--json]\n" +
            "  默认 --since $DEFAULT_SINCE（合并近窗）；--latest-only 只打最新一个；" +
            "交研发推荐：--redact --report --zip"
    override val example = "ohd bundle --redact --report --zip --latest-only"
    override val accepts: Set<String> = setOf(
        "name", "out", "redact", "session", "since", "report", "zip", "latest-only",
    )
    /** 必备件 → 缺失时禁止的结论。顺序即 README 中的展示顺序。 */
    private data class Required(val key: String, val label: String, val forbidden: String)

    private val required = listOf(
        Required("env", "设备与构建指纹", "不得把结论套用到其它机型或系统版本"),
        Required("frames", "界面截图帧", "无法判断界面表现，也无法核对坐标"),
        Required("logs", "应用日志窗口", "无法判断异常发生前的运行轨迹"),
        Required("crash", "崩溃原始日志", "不得断定崩溃原因（缺 faultlog 原文）"),
        Required("symbols", "符号化结果", "无法给出函数名与行号，只能给地址与 so 名"),
        Required("metrics", "指标时间序列", "无法判断内存/CPU 趋势，不得下「内存泄漏」结论"),
        Required("repro", "可复现步骤", "无法声称该问题可复现"),
    )

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val sessionDirs = resolveSessions(ctx)
        if (sessionDirs.isEmpty()) {
            throw OhdError(
                ExitCode.USAGE,
                "找不到可打包的会话",
                "先跑 ohd tree / shot / mem / crash 等产生产物，或用 --session a,b / --since / --latest-only",
            )
        }
        sessionDirs.forEach { d ->
            if (!Files.isDirectory(d)) throw OhdError(ExitCode.USAGE, "会话目录不存在：$d")
            if (!Files.isRegularFile(d.resolve("manifest.json"))) {
                throw OhdError(ExitCode.USAGE, "会话缺 manifest.json：$d", "该目录不是 ohd 会话产物（或由旧版本产生）")
            }
        }

        val manifests = sessionDirs.map { d ->
            val m = runCatching {
                JsonOut.encode.parseToJsonElement(Files.readString(d.resolve("manifest.json"))).jsonObject
            }.getOrElse { throw OhdError(ExitCode.USAGE, "manifest.json 解析失败（$d）：${it.message}") }
            d to m
        }

        val artifacts = manifests.flatMap { (d, m) -> readArtifacts(m, d) }.distinctBy { it.file }
        if (artifacts.isEmpty()) {
            throw OhdError(ExitCode.ASSERT_FAILED, "会话里没有任何产物，无法组包", "该会话只记录了命令，没有落盘证据")
        }

        val capability = manifests.firstNotNullOfOrNull { it.second["capability"]?.jsonPrimitive?.contentOrNull } ?: "session"
        val device = manifests.firstNotNullOfOrNull { it.second["device"]?.let { el -> runCatching { el.jsonObject }.getOrNull() } }
        // 主崩溃按严重度挑，不能取「第一个 faultlog」：
        // sym 从设备拉崩溃时会顺带注册一份 faultlog，按时间倒序它就排在 crash 会话前面，
        // 结果符号化的是这次的 cppcrash、主崩溃却是另一份 appfreeze，触发 SYMBOLS_MISMATCH。
        val crashKind = artifacts
            .filter { it.kind == "faultlog" }
            .map { it to detectCrashKind(it.file) }
            .maxByOrNull { (_, k) -> crashSeverity(k) }?.second

        val stamp = TS.format(OffsetDateTime.now())
        val bundleName = ctx.options.str("name")
            ?: "case-$stamp-$capability" + (crashKind?.let { "-$it" } ?: "")
        val outRoot = ctx.options.str("out")?.let { Paths.get(it) } ?: defaultCaseRoot(ctx.cwd)
        val pkg = outRoot.resolve(sanitize(bundleName))
        val redact = ctx.options.bool("redact")
        val wantReport = ctx.options.bool("report")
        val wantZip = ctx.options.bool("zip")
        val redactor = Redactor(device?.get("connectKey")?.jsonPrimitive?.contentOrNull)


        Files.createDirectories(pkg)

        // ---- 按 kind 归位 ----
        val buckets = linkedMapOf<String, MutableList<Artifact>>()
        artifacts.forEach { a -> buckets.getOrPut(bucketOf(a.kind)) { mutableListOf() }.add(a) }

        // (包内相对路径, 源, 显式kind)——打包时新生成的文件（如合成 gif）没有源 artifact
        val placed = mutableListOf<Triple<String, Path, String?>>()

        fun copyTo(rel: String, src: Path, explicitKind: String? = null): Path {
            val dst = pkg.resolve(rel)
            Files.createDirectories(dst.toAbsolutePath().parent)
            if (redact && Redactor.isText(src)) {
                // 文本走脱敏通道；截图等二进制无法脱敏，由 README 明确声明
                Session.writeText(dst, redactor.redact(Files.readString(src)))
            } else {
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            placed.add(Triple(rel, src, explicitKind))
            return dst
        }

        buckets["frames"]?.forEachIndexed { i, a ->
            val ext = a.file.fileName.toString().substringAfterLast('.', "bin")
            copyTo("frames/%04d-%s.%s".format(i + 1, a.kind, ext), a.file)
        }
        // replay.gif：replay --record 已生成就直接带；否则从截图序列合成
        // （monkey 崩溃时只有 1 张现场图，够不上动画，所以这份多半来自 replay 的序列）
        val gif = buckets["frames"]?.firstOrNull { it.kind == "replay-gif" }
        if (gif != null) {
            copyTo("replay/replay.gif", gif.file)
        } else {
            val shots = buckets["frames"]?.filter { it.kind == "shot" }.orEmpty()
            if (shots.size >= 2) {
                val imgs = shots.mapNotNull { runCatching { javax.imageio.ImageIO.read(it.file.toFile()) }.getOrNull() }
                if (imgs.size >= 2) {
                    val gifFile = pkg.resolve("replay/replay.gif").toFile()
                    if (GifWriter.write(imgs, gifFile, delayMs = 500)) {
                        placed.add(Triple("replay/replay.gif", gifFile.toPath(), "replay-gif"))
                    }
                }
            }
        }

        // 与主崩溃同一套严重度规则，否则会出现「符号化的是 cppcrash、包里装的是 appfreeze」，
        // 触发 SYMBOLS_MISMATCH 把符号结果整片降级。
        buckets["crash"]
            ?.filter { it.kind == "faultlog" }
            ?.map { it to detectCrashKind(it.file) }
            ?.maxByOrNull { (_, k) -> crashSeverity(k) }
            ?.first
            ?.let { copyTo("crash/raw.log", it.file) }
        buckets["crash"]?.firstOrNull { it.kind == "symbols" }?.let { copyTo("crash/symbols.json", it.file) }
        // 符号帧数过少：3 帧和 40 帧的包在 COMPLETENESS 里都显示「符号化结果 OK」，
        // 但排障可用性差很远。这里只警告不改判定——帧数少不等于数据错。
        buckets["crash"]?.firstOrNull { it.kind == "symbols" }?.let { f ->
            val frames = runCatching {
                Json.parseToJsonElement(Files.readString(f.file)).jsonObject["frames"]?.jsonArray?.size ?: 0
            }.getOrDefault(0)
            if (frames in 1..9) {
                ctx.out.note(
                    "FEW_SYMBOLS", "warn",
                    "符号化只覆盖 $frames 帧，排障时可能不够；重跑 sym 时用 --limit 40（默认值）"
                )
            }
        }

        buckets["logs"]?.firstOrNull { it.kind == "hilog" }?.let { copyTo("logs/app.log", it.file) }
        buckets["metrics"]?.firstOrNull { it.kind == "watch-csv" }?.let { copyTo("metrics/sample.csv", it.file) }
        buckets["metrics"]?.firstOrNull { it.kind == "mem-raw" }?.let { copyTo("metrics/mem-raw.txt", it.file) }
        buckets["layout"]?.forEachIndexed { i, a -> copyTo("layout/%03d-layout.json".format(i + 1), a.file) }
        buckets["misc"]?.forEach { copyTo("misc/${it.file.fileName}", it.file) }

        // ---- repro：脚本 → steps.json + run.sh/bat ----
        val script = buckets["repro"]?.firstOrNull { it.kind == "script" }
        var reproOk = false
        var reproNote: String? = null
        if (script != null) {
            val scriptCopy = copyTo("repro/steps.ohd", script.file)
            val parsed = runCatching { UiActions.parseScript(Files.readString(scriptCopy)) }.getOrNull()
            if (parsed != null && parsed.isNotEmpty()) {
                val stepsJson = buildJsonObject {
                    put("schema", "ohd-steps/1")
                    put("source", script.file.fileName.toString())
                    put("count", parsed.size)
                    put("steps", buildJsonArray {
                        parsed.forEach { a ->
                            add(buildJsonObject {
                                put("line", a.lineNo)
                                put("action", a.kind.name.lowercase())
                                put("args", buildJsonArray { a.args.forEach { x -> add(JsonPrimitive(x)) } })
                                put("raw", a.raw)
                            })
                        }
                    })
                }
                Session.writeText(
                    pkg.resolve("repro/steps.json"),
                    JsonOut.encode.encodeToString(JsonElement.serializer(), stepsJson),
                )
                reproOk = true
            } else {
                reproNote = "脚本无法解析为步骤（可能不是 ohd replay 脚本）"
            }
        }

        writeReproRunners(pkg)

        // ---- env/device.json ----
        val envOk = device != null
        if (device != null) {
            val devText = JsonOut.encode.encodeToString(JsonElement.serializer(), device)
            Session.writeText(
                pkg.resolve("env/device.json"),
                if (redact) redactor.redact(devText) else devText,
            )
        }

        // ---- 一致性：符号结果必须来自本包的崩溃原文 ----
        var symbolsOk = buckets["crash"]?.any { it.kind == "symbols" } == true
        var symbolsDegraded: String? = null
        val faultArt = buckets["crash"]?.firstOrNull { it.kind == "faultlog" }
        val symArt = buckets["crash"]?.firstOrNull { it.kind == "symbols" }
        if (symbolsOk && faultArt != null && symArt != null) {
            val symJson = runCatching {
                JsonOut.encode.parseToJsonElement(Files.readString(symArt.file)).jsonObject
            }.getOrNull()
            val sReason = symJson?.get("reason")?.jsonPrimitive?.contentOrNull ?: ""
            val sModule = symJson?.get("module")?.jsonPrimitive?.contentOrNull ?: ""
            val rep = runCatching {
                ohd.core.parsers.CrashParser.parse(
                    Files.readString(faultArt.file),
                    faultArt.file.fileName.toString(),
                )
            }.getOrNull()
            if (rep != null && sReason.isNotEmpty() &&
                (rep.reason != sReason || (sModule.isNotEmpty() && rep.module != sModule))
            ) {
                symbolsOk = false
                symbolsDegraded = "符号结果对应「$sModule / $sReason」，本包崩溃原文是「${rep.module} / ${rep.reason}」，不是同一次现场"
                ctx.out.note(
                    "SYMBOLS_MISMATCH",
                    "warn",
                    "符号结果与崩溃原文不一致，已降级为不可用：$symbolsDegraded",
                )
            }
        }

        // 全系统栈时函数名已在崩溃日志里（系统 so 自带导出符号），不需要 sym 产物
        if (!symbolsOk && faultArt != null) {
            val rep2 = runCatching {
                ohd.core.parsers.CrashParser.parse(
                    Files.readString(faultArt.file),
                    faultArt.file.fileName.toString(),
                )
            }.getOrNull()
            if (rep2 != null && rep2.nativeFrames.isNotEmpty()) {
                val appFrames = rep2.nativeFrames.filter {
                    !it.soPath.startsWith("/system") && !it.soPath.startsWith("/vendor")
                }
                val named = rep2.nativeFrames.count { !it.inlineSymbol.isNullOrBlank() }
                if (appFrames.isEmpty() && named > 0) {
                    symbolsOk = true
                    ctx.out.info("符号化结果：本栈全是系统 so，函数名随日志自带（$named 帧），无需 addr2line")
                }
            }
        }

        // ---- COMPLETENESS.json ----
        val status = linkedMapOf<String, JsonObject>()
        val forbidden = mutableListOf<String>()
        required.forEach { r ->
            val ok = when (r.key) {
                "env" -> envOk
                "frames" -> !buckets["frames"].isNullOrEmpty()
                "logs" -> !buckets["logs"].isNullOrEmpty()
                "crash" -> !buckets["crash"].isNullOrEmpty()
                "symbols" -> symbolsOk
                "metrics" -> !buckets["metrics"].isNullOrEmpty()
                "repro" -> reproOk
                else -> false
            }
            status[r.key] = buildJsonObject {
                put("label", r.label)
                put("status", if (ok) "ok" else "missing")
                if (!ok) {
                    put("reason", if (r.key == "symbols" && symbolsDegraded != null) symbolsDegraded else reasonFor(r.key, reproNote))
                    put("forbidden", r.forbidden)
                    forbidden.add(r.forbidden)
                }
            }
        }
        val okCount = status.values.count { it["status"]!!.jsonPrimitive.content == "ok" }
        val completeness = buildJsonObject {
            put("schema", "ohd-completeness/1")
            put("generatedAt", OffsetDateTime.now().toString())
            put("sourceSession", sessionDirs.joinToString(",") { it.toAbsolutePath().toString() })
            put("items", buildJsonObject { status.forEach { (k, v) -> put(k, v) } })
            put("summary", buildJsonObject {
                put("ok", okCount)
                put("missing", required.size - okCount)
                put("total", required.size)
            })
            put("forbiddenConclusions", buildJsonArray { forbidden.forEach { add(JsonPrimitive(it)) } })
            put("note", "本包只含可证伪的事实。是否构成缺陷、根因是什么，不由本包给出。")
        }
        Session.writeText(
            pkg.resolve("COMPLETENESS.json"),
            JsonOut.encode.encodeToString(JsonElement.serializer(), completeness),
        )

        // ---- context.json ----
        val context = buildJsonObject {
            put("schema", "ohd-case/1")
            put("createdAt", OffsetDateTime.now().toString())
            put("capability", capability)
            put("sourceSession", sessionDirs.joinToString(",") { it.toAbsolutePath().toString() })
            device?.let { put("device", it) }
            put("crashKind", crashKind ?: "")
            put("artifacts", buildJsonArray {
                placed.forEach { (rel, src, explicitKind) ->
                    add(buildJsonObject {
                        put("path", rel)
                        put("kind", explicitKind ?: artifacts.firstOrNull { it.file == src }?.kind ?: "generated")
                        put("bytes", runCatching { Files.size(pkg.resolve(rel)) }.getOrDefault(0L))
                        put("sha256", sha256(pkg.resolve(rel)) ?: "")
                    })
                }
            })
            put("redaction", buildJsonObject {
                put("enabled", redact)
                put("counts", buildJsonObject {
                    if (redact) redactor.counts.forEach { (k, v) -> put(k, v) }
                })
            })
            put("completeness", buildJsonObject {
                put("ok", okCount)
                put("missing", required.size - okCount)
                put("file", "COMPLETENESS.json")
            })
        }
        Session.writeText(
            pkg.resolve("context.json"),
            JsonOut.encode.encodeToString(JsonElement.serializer(), context),
        )

        // ---- README.md ----
        val readme = buildReadme(pkg, capability, device, crashKind, status, required, placed, reproOk, redact)
        Session.writeText(pkg.resolve("README.md"), readme)

        // 元数据自身也含绝对路径，同样要过脱敏
        if (redact) {
            listOf("README.md", "context.json", "COMPLETENESS.json", "repro/steps.json").forEach { rel ->
                val p = pkg.resolve(rel)
                if (Files.isRegularFile(p)) Session.writeText(p, redactor.redact(Files.readString(p)))
            }
        }

        var reportPath: Path? = null
        if (wantReport) {
            val facts = CaseFacts.load(pkg, 8)
            val md = CaseReport.render(facts)
            reportPath = pkg.resolve("report.md")
            Session.writeText(reportPath, if (redact) redactor.redact(md) else md)
        }

        var zipPath: Path? = null
        var zipFailed: String? = null
        if (wantZip) {
            runCatching { zipPackage(pkg) }
                .onSuccess { zipPath = it }
                .onFailure {
                    zipFailed = it.message ?: it.javaClass.simpleName
                    ctx.out.note("ZIP_FAILED", "error", "zip 写入失败（目录仍可用）：$zipFailed")
                }
        }

        val totalBytes = runCatching {
            Files.walk(pkg).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
        }.getOrDefault(0L)

        val incomplete = okCount < required.size || zipFailed != null
        if (ctx.out.jsonMode) {
            ctx.out.finish("bundle", !incomplete, buildJsonObject {
                put("package", pkg.toAbsolutePath().toString())
                put("name", pkg.fileName.toString())
                put("bytes", totalBytes)
                put("files", placed.size + 5 + (if (reportPath != null) 1 else 0))
                put("ok", okCount)
                put("missing", required.size - okCount)
                put("forbiddenConclusions", buildJsonArray { forbidden.forEach { add(JsonPrimitive(it)) } })
                reportPath?.let { put("report", it.toAbsolutePath().toString()) }
                zipPath?.let { put("zip", it.toAbsolutePath().toString()) }
                zipFailed?.let { put("zipError", it) }
            }, System.currentTimeMillis() - started)
        } else {
            val rows = mutableListOf(
                "证据包" to pkg.toAbsolutePath().toString(),
                "来源会话" to sessionDirs.joinToString(" + ") { it.fileName.toString() },
                "产物数" to placed.size.toString(),
                "体积" to humanBytes(totalBytes),
                "完备性" to "$okCount/${required.size} 项齐全",
                "脱敏" to if (redact) redactor.summary() else "未开启（--redact 开启）",
            )
            reportPath?.let { rows += "缺陷单" to it.toAbsolutePath().toString() }
            zipPath?.let { rows += "zip" to it.toAbsolutePath().toString() }
            ctx.out.kv(rows)
            println()
            ctx.out.table(
                listOf("必备件", "状态", "缺则不可下"),
                required.map { r ->
                    val s = status[r.key]!!
                    listOf(
                        r.label,
                        if (s["status"]!!.jsonPrimitive.content == "ok") "OK" else "缺失",
                        if (s["status"]!!.jsonPrimitive.content == "ok") "-" else r.forbidden,
                    )
                },
            )
            println()
            ctx.out.info(
                "入口：README.md（人读） / context.json（机器读） / COMPLETENESS.json（约束读）" +
                    if (reportPath != null) " / report.md（缺陷单）" else "",
            )
            if (incomplete && okCount < required.size) {
                ctx.out.warn("证据包已写出但必备件不齐（exit ${ExitCode.ASSERT_FAILED}）；缺件见 COMPLETENESS.json")
            }
        }
        return if (incomplete) ExitCode.ASSERT_FAILED else ExitCode.OK
    }

    /**
     * `--session` 精确点名 > `--latest-only` 旧默认 > `--since`（含默认 2h）。
     * `--since 0` = 不设下限（该 cwd 下全部会话）。
     */
    private fun resolveSessions(ctx: RunContext): List<Path> {
        ctx.options.str("session")
            ?.split(',')
            ?.filter { it.isNotBlank() }
            ?.map { Paths.get(it.trim()).toAbsolutePath().normalize() }
            ?.distinct()
            ?.let { return it }

        if (ctx.options.bool("latest-only")) {
            return listOfNotNull(latestSession(ctx.cwd))
        }

        val sinceRaw = ctx.options.str("since") ?: DEFAULT_SINCE
        val cutoff = when {
            sinceRaw.trim() == "0" -> java.time.LocalDateTime.MIN
            else -> parseSince(sinceRaw)
                ?: throw OhdError(
                    ExitCode.USAGE,
                    "无法解析 --since：$sinceRaw",
                    "例如 30m / 2h / 1d / 0（全部）/ 2026-10-18 07:30",
                )
        }
        return sessionsSince(cutoff, ctx.cwd)
    }

    private fun zipPackage(pkg: Path): Path {
        val zipPath = pkg.parent.resolve("${pkg.fileName}.zip")
        Files.newOutputStream(zipPath).use { fos ->
            ZipOutputStream(fos).use { zos ->
                Files.walk(pkg).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { file ->
                        val rel = pkg.relativize(file).toString().replace('\\', '/')
                        zos.putNextEntry(ZipEntry(rel))
                        Files.copy(file, zos)
                        zos.closeEntry()
                    }
                }
            }
        }
        return zipPath
    }

    private data class Artifact(val file: Path, val kind: String)

    private fun readArtifacts(manifest: JsonObject, dir: Path): List<Artifact> {
        val arr = manifest["artifacts"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        val out = mutableListOf<Artifact>()
        arr.forEach { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEach
            val name = o["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val f = dir.resolve(name)
            if (Files.isRegularFile(f)) out.add(Artifact(f, kind))
        }
        return out
    }

    private fun bucketOf(kind: String): String = when {
        kind == "faultlog" || kind == "symbols" -> "crash"
        kind == "hilog" -> "logs"
        kind in setOf("shot", "annotated", "diffmap", "replay-gif") -> "frames"
        kind in setOf("watch-csv", "mem-raw") -> "metrics"
        kind == "script" -> "repro"
        kind == "layout" -> "layout"
        else -> "misc"
    }

    private fun reasonFor(key: String, reproNote: String?): String = when (key) {
        "env" -> "会话未记录设备信息"
        "frames" -> "会话里没有截图"
        "logs" -> "会话里没有 hilog 产物（未执行 ohd log start/stop）"
        "crash" -> "会话里没有 faultlog（未执行 ohd crash）"
        "symbols" -> "未执行 ohd sym，或符号化全部失败"
        "metrics" -> "会话里没有 watch/mem 的 csv 产物"
        "repro" -> reproNote ?: "会话里没有 replay 脚本"
        else -> "未采集"
    }

    /** 主崩溃优先级：硬崩溃 > JS 崩溃 > 卡死。 */
    private fun crashSeverity(kind: String?): Int = when (kind) {
        "cppcrash" -> 3
        "jscrash" -> 2
        "appfreeze" -> 1
        else -> 0
    }

    private fun detectCrashKind(file: Path): String? = runCatching {
        // 只读 12 行不够：cppcrash 的 `Reason:` 在第 29 行左右（前面是 ability/Hiview 头），
        // 读不到就判成 unknown → 被 takeIf 丢弃，于是主崩溃永远落到 Reason 靠前的 appfreeze。
        val head = Files.newBufferedReader(file).use { br ->
            buildString {
                var n = 0
                while (n < 200) {
                    val line = br.readLine() ?: break
                    appendLine(line)
                    n++
                    if (line.startsWith("Reason:")) break
                }
            }
        }
        ohd.core.parsers.CrashParser.parse(head, file.fileName.toString()).kind
    }.getOrNull()?.takeIf { it != "unknown" }

    private fun writeReproRunners(pkg: Path) {
        val sh = """
            #!/usr/bin/env bash
            # 一步复现：需要已安装 ohd、已连好设备、目标应用已就位。
            set -euo pipefail
            cd "$(dirname "${'$'}0")/.."
            if [ ! -f repro/steps.ohd ]; then
              echo "本包没有 repro/steps.ohd，无法回放（见 COMPLETENESS.json 的 repro 项）" >&2
              exit 2
            fi
            ohd replay repro/steps.ohd
            echo "回放完成；如需对照，重新执行 ohd bundle 生成第二个包后用 ohd diff 比较截图"
        """.trimIndent() + "\n"
        val bat = """
            @echo off
            REM 一步复现：需要已安装 ohd、已连好设备、目标应用已就位。
            cd /d "%~dp0.."
            if not exist repro\steps.ohd (
              echo 本包没有 repro\steps.ohd，无法回放（见 COMPLETENESS.json 的 repro 项） 1>&2
              exit /b 2
            )
            ohd replay repro\steps.ohd
        """.trimIndent() + "\r\n"
        Session.writeText(pkg.resolve("repro/run.sh"), sh)
        Session.writeText(pkg.resolve("repro/run.bat"), bat)
    }

    private fun buildReadme(
        pkg: Path,
        capability: String,
        device: JsonObject?,
        crashKind: String?,
        status: Map<String, JsonObject>,
        required: List<Required>,
        placed: List<Triple<String, Path, String?>>,
        reproOk: Boolean,
        redact: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# 证据包 ${pkg.fileName}")
        sb.appendLine()
        sb.appendLine("由 `ohd bundle` 从会话 **$capability** 生成，无人工加工。")
        sb.appendLine()
        sb.appendLine("## 设备")
        if (device == null) {
            sb.appendLine("- （缺失：会话未记录设备信息）")
        } else {
            device["model"]?.jsonPrimitive?.contentOrNull?.let { sb.appendLine("- 型号：$it") }
            device["softwareVersion"]?.jsonPrimitive?.contentOrNull?.let { sb.appendLine("- 系统：$it") }
            device["apiVersion"]?.jsonPrimitive?.contentOrNull?.let { sb.appendLine("- API：$it") }
            device["abi"]?.jsonPrimitive?.contentOrNull?.let { sb.appendLine("- ABI：$it") }
        }
        crashKind?.let { sb.appendLine("- 崩溃类型：$it") }
        sb.appendLine()
        sb.appendLine("## 包含的产物")
        placed.forEach { (rel, _, _) -> sb.appendLine("- `$rel`") }
        sb.appendLine()
        val missing = required.filter { status[it.key]!!["status"]!!.jsonPrimitive.content != "ok" }
        sb.appendLine("## 缺失项（因此**不得**下这些结论）")
        if (missing.isEmpty()) {
            sb.appendLine("- 无，七类必备件齐全")
        } else {
            missing.forEach { r ->
                sb.appendLine("- **${r.label}** 缺失 → ${r.forbidden}")
                status[r.key]?.get("reason")?.jsonPrimitive?.contentOrNull?.let { sb.appendLine("  - 原因：$it") }
            }
        }
        sb.appendLine()
        sb.appendLine("## 怎么用")
        sb.appendLine("1. 人读：本文件。机器读：`context.json`（产物索引含 sha256）。约束读：`COMPLETENESS.json`。")
        if (reproOk) {
            sb.appendLine("2. 复现：`bash repro/run.sh`（Windows：`repro\\run.bat`），或 `ohd replay repro/steps.ohd`。")
        } else {
            sb.appendLine("2. 复现：本包**不含可执行步骤**，请向测试同学索取操作过程。")
        }
        sb.appendLine("3. 定责边界：本包只提供事实，不提供根因。是否算缺陷由你结合用例判断。")
        if (placed.any { (rel, _, _) -> rel.endsWith("replay.gif") }) {
            sb.appendLine("4. 录像：`replay/replay.gif` 是操作过程动画，图片查看器打开即循环播放。")
        }
        sb.appendLine()
        sb.appendLine("## 生成环境")
        sb.appendLine("- 工具：ohd $TOOL_VERSION（${Platform.displayName} / ${Platform.arch}）")
        sb.appendLine("- 生成时间：${OffsetDateTime.now()}")
        sb.appendLine("- 平台差异（路径、编码、命令名）见 ohd 仓库 `CROSS-PLATFORM.md`。")

        if (redact) {
            sb.appendLine()
            sb.appendLine("## 脱敏说明")
            sb.appendLine("- 已替换：本机用户路径、设备序列号、带端口的 IP 与关键词后的 IP、MAC、手机号、邮箱、Bearer token。")
            sb.appendLine("- **正文中孤立出现的裸 IP 未处理**（避免误伤 `6.1.0.135` 这类版本号），外发前请抽查 logs/。")
            sb.appendLine("- **截图未脱敏**（位图无法可靠处理），外发前请自行检查画面内容。")
            sb.appendLine("- 设备序列号替换为 `<SERIAL-末4位>`：保留末 4 位便于区分多台设备，无法反查整机。")
        }
        return sb.toString()
    }

    /**
     * 圈定「本次测试」的会话。
     *
     * 原来只能手拼 `--session a,b,c`（一次冒烟 8 个目录，漏一个就缺件），
     * 或全量打包（148 个会话 → 96 MB，含大量无关 layout）。
     */
    private fun sessionsSince(cutoff: java.time.LocalDateTime, cwd: Path): List<Path> {
        val root = Session.defaultRoot(cwd)
        if (!Files.isDirectory(root)) return emptyList()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        return Files.list(root).use { st ->
            st.filter { Files.isRegularFile(it.resolve("manifest.json")) }
                .filter { d ->
                    val name = d.fileName.toString()
                    // 目录名：yyyyMMdd-HHmmss-<capability>；capability 可能含 '-'，取前 15 字符
                    val ts = name.take(15)
                    runCatching { java.time.LocalDateTime.parse(ts, fmt) }.getOrNull()
                        ?.let { !it.isBefore(cutoff) } ?: false
                }
                // 最新优先：同一类产物有多个会话时（例如符号化跑过两次），
                // 取最近的才不会拿旧崩溃的符号去套这次的主崩溃。
                .sorted(Comparator.reverseOrder())
                .toList()
        }
    }

    /** 支持 `30m` / `2h` / `1d` 相对量与 `2026-10-18 07:30[:ss]` 绝对时间。 */
    private fun parseSince(raw: String): java.time.LocalDateTime? {
        val t = raw.trim()
        Regex("""^(\d+)([mhd])$""").find(t)?.let { m ->
            val n = m.groupValues[1].toLong()
            val now = java.time.LocalDateTime.now()
            return when (m.groupValues[2]) {
                "m" -> now.minusMinutes(n)
                "h" -> now.minusHours(n)
                else -> now.minusDays(n)
            }
        }
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm")) {
            runCatching {
                return java.time.LocalDateTime.parse(t, java.time.format.DateTimeFormatter.ofPattern(pattern))
            }
        }
        return null
    }

    private fun latestSession(cwd: Path): Path? {
        val root = Session.defaultRoot(cwd)
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { s ->
            s.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("manifest.json")) }
                .max(Comparator.comparingLong { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) })
                .orElse(null)
        }
    }

    private fun defaultCaseRoot(cwd: Path): Path = Session.defaultRoot(cwd).parent.resolve("cases")

    private fun sanitize(s: String): String =
        s.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }.joinToString("").take(80)

    private fun humanBytes(b: Long): String = when {
        b >= 1024 * 1024 -> "%.1f MB".format(b / 1024.0 / 1024.0)
        b >= 1024 -> "%.1f KB".format(b / 1024.0)
        else -> "$b B"
    }

    private fun sha256(path: Path): String? = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        /** 无 `--session` / `--latest-only` / 显式 `--since` 时的默认时间窗。 */
        const val DEFAULT_SINCE = "2h"
        private val TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/**
 * 脱敏器。目标是「包能外发」，但绝不能脱到证据不可用：
 * 保留 —— 包名、so 路径、函数名、时间戳、sha256、设备型号/系统版本；
 * 替换 —— 本机用户目录、设备序列号、IP、MAC、手机号、邮箱、Bearer token。
 * 截图（位图）不做处理，由 README 显式声明。
 */
internal class Redactor(private val serial: String?) {
    val counts = linkedMapOf<String, Int>()

    private fun subGroups(name: String, re: Regex, text: String, transform: (MatchResult) -> String): String {
        var n = 0
        val out = re.replace(text) { m -> n++; transform(m) }
        if (n > 0) counts[name] = (counts[name] ?: 0) + n
        return out
    }

    private fun sub(name: String, re: Regex, rep: String, text: String): String {
        var n = 0
        val out = re.replace(text) { n++; rep }
        if (n > 0) counts[name] = (counts[name] ?: 0) + n
        return out
    }

    fun redact(input: String): String {
        var t = input
        t = sub("home", Regex("""(?:/Users/|/home/)[^/\s"']+"""), "<HOME>", t)
        t = sub("winHome", Regex("""[A-Za-z]:\\\\Users\\\\[^\\\\\s"']+"""), "<HOME>", t)
        // 只脱「带端口的 IP」和「紧跟在 ip/server/host 等关键词后的 IP」：
        // 无差别匹配会把 6.1.0.135 这类系统版本号也当成 IP，直接毁掉构建指纹。
        t = subGroups("ip:port", Regex("""(?<![\w.])(?:(?:\d{1,3}\.){3}\d{1,3}):(\d{2,5})(?![\w.])"""), t) { m ->
            "<IP>:" + m.groupValues[1]
        }
        t = subGroups(
            "ipCtx",
            Regex("""(?i)(\b(?:ip|ipaddr|address|server|host|gateway|proxy|dns)\b\s*[:=]\s*["']?)((?:\d{1,3}\.){3}\d{1,3})"""),
            t,
        ) { m -> m.groupValues[1] + "<IP>" }
        t = sub("mac", Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b"""), "<MAC>", t)
        t = sub("phone", Regex("""\b1[3-9]\d{9}\b"""), "<PHONE>", t)
        t = sub("email", Regex("""[\w.+-]+@[\w-]+\.[\w.]{2,}"""), "<EMAIL>", t)
        t = sub("bearer", Regex("""(?i)bearer\s+[A-Za-z0-9._\-]{16,}"""), "Bearer <TOKEN>", t)
        serial?.takeIf { it.length >= 8 }?.let { sn ->
            // 保留末 4 位：多台设备仍可区分，但无法反查整机
            t = sub("serial", Regex(Regex.escape(sn)), "<SERIAL-" + sn.takeLast(4) + ">", t)
        }
        return t
    }

    fun summary(): String =
        if (counts.isEmpty()) "已开启，未命中敏感项" else counts.entries.joinToString("，") { "${it.key}×${it.value}" }

    companion object {
        private val TEXT_EXT = setOf("log", "txt", "json", "csv", "ohd", "md", "xml", "yaml", "yml", "cfg", "ini")
        fun isText(p: Path): Boolean =
            p.fileName.toString().substringAfterLast('.', "").lowercase() in TEXT_EXT
    }
}
