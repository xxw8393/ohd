package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.Check
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.HostCommands
import ohd.core.ext.Extensions
import ohd.core.ext.ExtKind
import ohd.core.Platform
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.Status
import ohd.core.hasFailure
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.Env

/**
 * 离线自检：不碰设备，只验证「代码路径是否在这台机器上工作」。
 * 跨端场景必备——Windows/Linux 同事第一条命令就能自证环境，失败项可原样贴回。
 */
class SelftestCap(
    private val extraChecks: List<() -> Check> = emptyList(),
) : Capability {
    override val name = "selftest"
    override val summary = "离线自检：解析器/会话/argv 组装/编码/图像引擎（不碰设备）"
    override val usage = "ohd selftest [--platform-audit] [--json]"

    /** 只接受这两个开关；其余参数由通用校验拦住。 */
    override val accepts = setOf("platform-audit")

    /** 所有可能起宿主进程的入口。新增一个 API 忘了登记，这里也会立刻发现。 */
    private val SPAWN_APIS = listOf(
        "ProcessBuilder(",
        "runProcess(",
        "spawnDetached(",
        "spawnStreaming(",
    )

    override val example = "ohd selftest --json --debug"
    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        if (ctx.options.bool("platform-audit")) return finish(ctx, platformAudit(ctx), started)
        val checks = mutableListOf<Check>()

        // 1) 平台
        checks += Check.ok("platform", "${Platform.displayName}/${Platform.arch} home=${Platform.home}")

        // 2) CRLF 归一（Windows 上 hdc 输出带 \r，是所有解析器的隐形雷）
        val crlf = Hdc.normalizeNewlines("a\r\nb\rc\nd")
        checks += if (crlf == "a\nb\nc\nd") Check.ok("newline", "CRLF/CR → LF")
        else Check.fail("newline", "归一异常：${crlf.replace("\n", "\\n")}")

        // 3) argv 组装：设备路径必须正斜杠、shell 命令串是单个 argv 元素
        val hdc = Hdc(OhdPath.of("/tmp/hdc"), "DEV123")
        val argv = hdc.argv("shell", "uitest uiInput click 100 200")
        val expect = listOf("/tmp/hdc", "-t", "DEV123", "shell", "uitest uiInput click 100 200")
        checks += if (argv.drop(1) == expect.drop(1) && argv.last().contains(" ")) Check.ok("argv", "shell 命令串单元素传递")
        else Check.fail("argv", "组装异常：$argv")

        val recvArgv = hdc.argv("file", "recv", "/data/local/tmp/x.png", Platform.devicePath(OhdPath.of("C:/tmp/x.png")))
        checks += if (!recvArgv.last().contains("\\")) Check.ok("path", "设备/宿主路径统一正斜杠")
        else Check.fail("path", "反斜杠未转换：${recvArgv.last()}")

        // 4) Session 写读 + manifest
        try {
            val tmp = fs.createTempDirectory("ohd-selftest")
            val session = Session.create("selftest", tmp.resolve("cases"))
            val p = session.nextPath("probe", "txt")
            Session.writeText(p, "hello")
            session.register(p, "probe")
            val manifest = session.writeManifest(null)
            val text = fs.readString(manifest)
            // manifest 用自己的 schema（与 JSON 信封的 ohd/1 区分），断言跟着改
            val okManifest = text.contains("ohd-manifest/1") && fs.size(p) == 5L
            checks += if (okManifest) Check.ok("session", "目录/产物/manifest 正常") else Check.fail("session", "manifest 内容异常")
            fs.deleteRecursively(tmp)
        } catch (e: Exception) {
            checks += Check.fail("session", "会话目录异常：${e.message}")
        }

        // 5) JSON 骨架（--json 契约是 agent 消费的入口）
        try {
            val obj = buildJsonObject { put("a", 1); put("b", buildJsonArray { add(buildJsonObject { put("c", "d") }) }) }
            val text = Session.MANIFEST_JSON.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), obj)
            checks += if (text.contains("\"a\"")) Check.ok("json", "序列化正常") else Check.fail("json", "序列化异常")
        } catch (e: Exception) {
            checks += Check.fail("json", "序列化异常：${e.message}")
        }

        // 6) 表格 CJK 宽度（中文列对齐）
        val w = Platform.displayWidth("内存")
        checks += if (w == 4) Check.ok("display", "CJK 宽度=2/字") else Check.fail("display", "宽度计算异常：$w")

        // 7) 由上层注入的检查（图像引擎等，避免 core 反向依赖 image）
        extraChecks.forEach { fn ->
            checks.add(runCatching { fn() }.getOrElse { e -> Check.fail("extra", "检查抛异常：${e.message}") })
        }

        return finish(ctx, checks, started)
    }

    /** 汇总输出：普通自检与平台审计共用。 */
    private fun finish(ctx: RunContext, checks: List<Check>, started: Long): Int {
        val elapsed = Env.nowMillis() - started
        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("checks", buildJsonArray {
                    checks.forEach { c ->
                        add(buildJsonObject {
                            put("name", c.name)
                            put("status", c.status.name)
                            put("detail", c.detail)
                        })
                    }
                })
                put("summary", buildJsonObject {
                    put("ok", checks.count { it.status == Status.OK })
                    put("fail", checks.count { it.status == Status.FAIL })
                })
            }
            ctx.out.finish("selftest", !checks.hasFailure(), data, elapsed)
        } else {
            ctx.out.table(listOf("检查", "结果", "详情"), checks.map { listOf(it.name, it.status.name, it.detail) })
            if (checks.hasFailure()) ctx.out.error("存在失败项，请把上表原样贴回排查")
            else ctx.out.info("\n全部通过：这台机器可以运行 ohd")
        }
        return if (checks.hasFailure()) ExitCode.ASSERT_FAILED else ExitCode.OK
    }

    /**
     * 跨平台审计：断言宿主侧只启动白名单内的命令。
     *
     * 与普通自检的区别：普通自检不碰设备也不读源码；这项要读源码做静态比对，
     * 目的是**拦住「悄悄新增一个宿主进程」**——新增 ProcessBuilder 调用点必须登记到
     * [HostCommands]，否则审计失败。
     */
    private fun platformAudit(ctx: RunContext): List<Check> {
        val checks = mutableListOf<Check>()

        checks += Check.ok(
            "platform",
            "${Platform.displayName}/${Platform.arch}  windows=${Platform.isWindows} mac=${Platform.isMac} linux=${Platform.isLinux}",
        )

        // 1) 宿主命令白名单（唯一真源）
        checks += Check.ok(
            "host-commands",
            HostCommands.ALL.joinToString("，") { "${it.name}=${it.purpose}" },
        )

        // 2) 工具定位：运行时事实，定位不到不算失败（离线能力不依赖它们）
        listOf(
            "hdc" to ctx.env.hdc,
            "adb" to ctx.env.adb,
            "llvm-addr2line" to ctx.env.llvmAddr2line,
        ).forEach { (label, loc) ->
            val path = loc.path
            checks += if (path != null && fs.isExecutable(path)) {
                Check.ok("tool:$label", "${loc.source} → $path")
            } else {
                Check.ok("tool:$label", "未定位（来自 ${loc.source}）")
            }
        }

        // 3) 静态扫描源码：所有「起宿主进程」的入口都必须与登记表一致
        val roots = HostCommands.locateSourceRoots()
        if (roots.isEmpty()) {
            checks += Check.ok("source-scan", "没有源码（发布包形态），跳过静态审计")
        } else {
            val found = linkedMapOf<String, Int>()
            runCatching {
                roots.forEach { root ->
                fs.walk(root).use { paths ->
                    paths.filter { it.toString().endsWith(".kt") }.forEach { f ->
                        val n = runCatching {
                            fs.readAllLines(f).count { raw ->
                                val t = raw.trim()
                                // 只数真实调用：注释与字符串字面量里出现的不算
                                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                                    false
                                } else {
                                    // 四个入口都要扫：迁到平台层后只认 ProcessBuilder(
                                    // 就再也拦不住「悄悄新增一个宿主进程」——护栏等于失效。
                                    SPAWN_APIS.any { api ->
                                        api in raw && !raw.contains("\"$api")
                                    }
                                }
                            }
                        }.getOrDefault(0)
                        if (n > 0) found[f.fileName.toString()] = n
                    }
                }
                }
            }
            val unknown = found.keys - HostCommands.EXPECTED_SITES.keys
            val mismatch = HostCommands.EXPECTED_SITES.filter { (name, want) -> (found[name] ?: 0) != want }
            checks += when {
                unknown.isNotEmpty() -> Check.fail(
                    "host-sites",
                    "未登记的宿主进程启动点：$unknown → 在 HostCommands 登记用途，或不要新增宿主命令",
                )
                mismatch.isNotEmpty() -> Check.fail("host-sites", "调用次数不符：登记 $mismatch，实际 $found")
                else -> Check.ok("host-sites", "${found.values.sum()} 处调用，分布在 ${found.size} 个文件，全部已登记")
            }
        }

        // 4) 扩展在当前平台的可运行性
        val exts = runCatching {
            Extensions.discover(OhdPath.of("").toAbsolutePath())
        }.getOrDefault(linkedMapOf())
        val blocked = exts.values.filter { it.kind == ExtKind.SHELL && Platform.isWindows }
        checks += if (blocked.isEmpty()) {
            Check.ok("extensions", "${exts.size} 个扩展，当前平台均可运行")
        } else {
            Check.fail("extensions", "Windows 上无法运行 .sh 扩展：${blocked.map { it.name }}")
        }

        return checks
    }
}
