package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.HilogFiles
import ohd.core.LogSink
import ohd.core.HilogStream
import ohd.core.DeviceKind
import ohd.core.PlatformCmds
import ohd.core.logCompressOf
import ohd.core.logFileSuffix
import ohd.core.logSink
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.Self
import ohd.core.OhdError
import ohd.core.ProcessTrees
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.defaultStateFile
import ohd.core.readJsonFile
import ohd.core.parsers.HilogParser
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.TimeFmt
import ohd.platform.spawnDetached
import ohd.platform.onExitCleanup
import ohd.platform.onExitCleanup
import ohd.platform.Mutex
import ohd.platform.Stdio

/**
 * 日志流：start/stop 强制成对。
 * 设计依据：长驻 `hdc shell hilog` 会静默僵死（skill 里踩过的坑），
 * 所以 stop 时校验「文件非空」，并在 snapshot 里暴露行数，避免"看起来采到了"。
 */
class LogCap : Capability {
    override val name = "log"
    override val summary = "日志：默认前台跟随（终端实时显示 + 压缩落盘，Ctrl-C 结束）；--open 回放；start/stop 留给后台长跑"
    override val usage =
        "ohd log [start|stop|snap|count|save|ls] [--open <id|latest|路径>] [--file <路径>] " +
            "[--tag a,b] [--filter TAG] [--match RE] [--exclude RE] [--full] [--no-compress] " +
            "[--max-size 2G] [--tail N] [--clear] [--json]"
    override val example = "ohd log --tag Konan_main --exclude '(?i)vsync'"
    override val accepts: Set<String> =
        setOf(
            "clear", "file", "filter", "match", "exclude", "full", "max-size", "tag", "tail",
            "no-compress", "log-compress", "open",
        )

    private val subcommands = listOf("start", "stop", "snap", "count", "save", "ls")

    override fun run(ctx: RunContext): Int {
        // `--open` 是回放入口：看已经录下来的东西，不该再去连设备
        ctx.options.str("open")?.let { return show(ctx, resolveRecording(ctx, it)) }
        return when (val sub = ctx.options.positionals.firstOrNull()) {
            // 无子命令即「录音模式」：终端直接看，同时压缩落盘，Ctrl-C 结束
            null -> if (ctx.options.str("file").isNullOrBlank()) follow(ctx) else show(ctx, resolveFile(ctx))
            "start" -> start(ctx)
            "stop" -> stop(ctx)
            "snap" -> snap(ctx)
            "count" -> count(ctx)
            "save" -> save(ctx)
            "ls" -> ls(ctx)
            // 隐藏子命令：由 start 拉起的后台泵进程，跨进程持有日志流
            "__pump" -> pumpDaemon(ctx)
            else -> throw OhdError(ExitCode.USAGE, "未知子命令：$sub", "可用：${subcommands.joinToString("/")}")
        }
    }

    private fun statePath(ctx: RunContext) = defaultStateFile(ctx.sessionRoot, ctx.options.str("device"))

    /** 同目录里所有带设备后缀的状态文件：用于「猜不到 / 猜错」时给出具体指引。 */
    private fun siblingStates(like: OhdPath): List<OhdPath> {
        val dir = like.toAbsolutePath().parent ?: return emptyList()
        return runCatching {
            fs.list(dir).use { s ->
                s.filter { (it.fileName?.toString() ?: "").matches(Regex("""log-state-.+\.json""")) }
                    .sortedBy { it.toString() }
                    .toList()
            }
        }.getOrDefault(emptyList<OhdPath>())
    }

    /**
     * 状态文件解析：`log start --device X` 会写成 `log-state-X.json`（多设备并行必须分开），
     * 但 start 的收尾提示就是一句 `ohd log stop`，用户不会重复带 --device。
     * 所以缺省时回退：先试无后缀的，再扫同目录 `log-state-*.json`——唯一则直接用，多个则要求显式指定，绝不猜。
     */
    private fun resolveStatePath(ctx: RunContext): OhdPath {
        if (!ctx.options.str("device").isNullOrBlank()) return statePath(ctx)
        val plain = statePath(ctx)
        if (fs.isRegularFile(plain)) return plain
        val candidates = siblingStates(plain)
        return when (candidates.size) {
            0 -> plain
            1 -> candidates[0]
            else -> throw OhdError(
                ExitCode.USAGE,
                "同时有 ${candidates.size} 路日志流在跑，无法确定停哪一台",
                "用 --device 指定：${candidates.joinToString(" ") { it.fileName.toString() }}",
            )
        }
    }

    /**
     * 读不到状态时的提示。`?:` 一个出口会把三种情况压成同一句兜底文案，这里补回区分：
     * 显式指定了 --device 却读不到，通常是「指定了另一台」，那就把真正在跑的列出来，而不是谎报「没有流」。
     */
    private fun missingState(ctx: RunContext, sp: OhdPath, hint: String): OhdError {
        val explicit = ctx.options.str("device")
        if (!explicit.isNullOrBlank()) {
            val others = siblingStates(sp)
            if (others.isNotEmpty()) {
                val names = others.joinToString(" ") {
                    it.fileName.toString().removePrefix("log-state-").removeSuffix(".json")
                }
                return OhdError(
                    ExitCode.USAGE,
                    "设备 $explicit 上没有进行中的日志流",
                    "在跑的是：$names —— 用 --device 从中指定，或不带 --device 让 ohd 自己挑",
                )
            }
        }
        return OhdError(ExitCode.USAGE, "没有进行中的日志流", hint)
    }

    private fun start(ctx: RunContext): Int {
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "log")
            ?: throw OhdError(ExitCode.USAGE, "log start 必须落盘（不要加 --no-archive）", "示例：ohd log start --tag Konan_main（--no-archive 只用于一次性命令）")
        if (ctx.options.bool("clear")) Hdcs.sh(session, hdc, "hilog -r", 15_000)

        val maxBytes = try {
            LogWatchdog.parseSize(ctx.options.str("max-size"))
        } catch (_: IllegalArgumentException) {
            throw OhdError(
                ExitCode.USAGE,
                "参数 --max-size 无法解析：${ctx.options.str("max-size")}",
                "例如 --max-size 2G，0 表示不限",
            )
        }

        val compress = logCompressOf(ctx)
        val file = session.nextPath("hilog", logFileSuffix(compress))
        // 设备端按 tag 过滤；宿主端做 compat 精简 + 关键字过滤（都不指定则全量原样）
        val tags = HilogStream.parseTags(ctx.options.str("tag"))
        val match = ctx.options.str("match")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrElse {
                throw OhdError(ExitCode.USAGE, "参数 --match 不是合法正则：$it")
            }
        }
        val exclude = ctx.options.str("exclude")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrElse {
                throw OhdError(ExitCode.USAGE, "参数 --exclude 不是合法正则：$it")
            }
        }
        val compat = !ctx.options.bool("full")
        // 必须把泵放进独立进程：泵线程是 daemon，随 `log start` 进程退出被 JVM 直接杀掉，
        // 既停止采集，也让最后一个 gzip member 残缺。start 返回后由 __pump 进程继续持有流。
        val launch = Self.launchArgv()
            ?: throw OhdError(
                ExitCode.UNSUPPORTED,
                "无法定位 ohd 自身入口，日志流无法后台存活",
                "用发行包 ./ohd 运行，或设置 OHD_SELF 指向可执行入口",
            )
        val pumpCmd = buildList {
            addAll(launch)
            add("log")
            add("__pump")
            add(file.toAbsolutePath().toString())
            hdc.connectKey?.let { add("--device"); add(it) }
            add("--log-compress")
            add(if (compress) "gzip" else "none")
            ctx.options.str("tag")?.let { add("--tag"); add(it) }
            ctx.options.str("match")?.let { add("--match"); add(it) }
            ctx.options.str("exclude")?.let { add("--exclude"); add(it) }
            if (ctx.options.bool("full")) add("--full")
        }
        // 泵进程必须在 start 返回后继续存活：spawnDetached 会丢弃输出、关掉 stdin、脱离终端。
        // 起不来时拿不到 pid，等价于旧实现的 start() 抛异常，同样按 TIMEOUT 报错。
        val pump = spawnDetached(pumpCmd)
        val pumpPid = pump.pid ?: throw OhdError(
            ExitCode.TIMEOUT,
            "启动日志流失败：${pump.spawnError ?: "未返回进程 PID"}",
        )

        // start 返回后本进程就退出了。没交出去之前（Ctrl-C / 启动失败）要把 hdc 一起带走，
        // 否则它会变成孤儿一直写 hilog。交出去之后由看守进程按大小上限回收。
        // onExitCleanup 不可撤销（原实现靠 removeShutdownHook 撤销），
        // 用 Mutex 保护的布尔量判「是否已交接出去」。
        val handoffLock = Mutex()
        var handedOff = false
        val guardPid = LogWatchdog.spawn(pumpPid, file, maxBytes)
        // onExitCleanup 不可撤销（原实现靠 removeShutdownHook 撤销），
        // 所以用 handedOff 在 lambda 里判：正常交接后退出时不能再杀泵/看守。
        onExitCleanup {
            val stillMine = handoffLock.withLock { !handedOff }
            if (stillMine) {
                ProcessTrees.kill(pumpPid)
                guardPid?.let { ProcessTrees.kill(it) }
            }
        }

        val state = buildJsonObject {
            put("pid", pumpPid)
            put("pumpPid", pumpPid)
            put("file", file.toString())
            put("device", hdc.connectKey ?: "")
            put("startedAt", TimeFmt.isoLocal(Env.nowMillis()))
            put("session", session.dir.toString())
            put("maxBytes", maxBytes)
            put("guardPid", guardPid ?: -1)
        }
        val sp = statePath(ctx)
        sp.toAbsolutePath().parent?.let { fs.createDirectories(it) }
        Session.writeText(sp, state.toString())
        // 必须登记到会话：bundle 靠 manifest.artifacts 判定「日志」这一必备件
        session.register(file, "hilog")

        // 等首行落地，避免用户立刻 stop 拿到空文件
        var waited = 0
        while (waited < 3000 && (!fs.exists(file) || fs.size(file) == 0L)) {
            Env.sleepMillis(200)
            waited += 200
        }

        if (maxBytes > 0 && guardPid == null) {
            ctx.out.note(
                "GUARD_FAILED",
                "warn",
                "大小看守没拉起来，--max-size ${LogWatchdog.format(maxBytes)} 在本进程退出后不会生效",
            )
        }
        handoffLock.withLock { handedOff = true }

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("pid", pumpPid)
                put("pumpPid", pumpPid)
                put("file", file.toString())
                put("state", sp.toString())
                put("startedAt", TimeFmt.isoLocal(Env.nowMillis()))
                put("maxBytes", maxBytes)
                put("guardPid", guardPid ?: -1)
            }
            ctx.out.finish("log.start", true, data, 0)
        } else {
            ctx.out.kv(
                listOf(
                    "日志流 PID" to pumpPid.toString(),
                    "输出文件" to file.toString(),
                    "状态文件" to sp.toString(),
                    "大小上限" to LogWatchdog.format(maxBytes),
                    "压缩" to if (compress) "gzip" else "无（明文，可直接 tail/grep）",
                    "看守 PID" to (guardPid?.toString() ?: "-"),
                    "首行延迟" to "${waited}ms",
                ),
            )
            ctx.out.info("\n采集完成后务必执行：ohd log stop")
        }
        return ExitCode.OK
    }

    /**
     * 后台泵进程（`log __pump <文件>`）：真正持有日志流、持续写文件，直到被 stop 终止。
     *
     * 与 `log start` 分离的**唯一原因**是延长生命周期——start 命令本身必须立刻返回。
     * 收尾依赖 shutdown hook：stop 发 SIGTERM 时先 flush 再退出，保证 gzip member 完整。
     */
    private fun pumpDaemon(ctx: RunContext): Int {
        val file = ctx.options.positionals.getOrNull(1)?.let { OhdPath.of(it) }
            ?: throw OhdError(ExitCode.USAGE, "内部命令用法：ohd log __pump <文件>")
        val hdc = Hdcs.forDevice(ctx)
        val tags = HilogStream.parseTags(ctx.options.str("tag"))
        val match = ctx.options.str("match")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrNull()
        }
        val exclude = ctx.options.str("exclude")?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrNull()
        }
        val compat = !ctx.options.bool("full")
        val compress = logCompressOf(ctx)
        val sink = logSink(file, compress)

        val proc = HilogStream.pump(
            hdc.shellArgv(PlatformCmds.logCommand(hdc.kind, tags)),
            sink,
            match,
            exclude,
            compat,
        )

        onExitCleanup {
            runCatching { sink.flush() }
            runCatching { sink.close() }
            runCatching { ProcessTrees.kill(proc.pid()) }
        }

        return try {
            proc.waitFor()
            runCatching { sink.flush() }
            runCatching { sink.close() }
            ExitCode.OK
        } catch (_: Throwable) {
            // 被中断（Ctrl-C）就当作正常结束：已采数据已由上面的 flush/close 落盘
            ExitCode.OK
        }
    }

    /**
     * 前台跟随：终端边看边落盘，Ctrl-C 结束。等价于 `hilog | tee file.gz`，
     * 但沿用多成员 gzip（被杀也留得下可解压的尾巴）与大小上限看守。
     *
     * 与 start 的区别只在「谁持有流」：start 交给后台 __pump 进程以便命令立刻返回，
     * 这里由当前进程持有，所以退出必须显式收尾（shutdown hook）并打印回放入口。
     */
    private fun follow(ctx: RunContext): Int {
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "log")
            ?: throw OhdError(
                ExitCode.USAGE,
                "日志默认要落盘，不要加 --no-archive",
                "只想临时看一眼、不留文件时再考虑直接跑 hdc shell hilog",
            )
        if (ctx.options.bool("clear")) Hdcs.sh(session, hdc, "hilog -r", 15_000)

        val maxBytes = try {
            LogWatchdog.parseSize(ctx.options.str("max-size"))
        } catch (_: IllegalArgumentException) {
            throw OhdError(
                ExitCode.USAGE,
                "参数 --max-size 无法解析：${ctx.options.str("max-size")}",
                "例如 --max-size 2G，0 表示不限",
            )
        }

        val compress = logCompressOf(ctx)
        val file = session.nextPath("hilog", logFileSuffix(compress))
        val tags = HilogStream.parseTags(ctx.options.str("tag"))
        val sink = EchoLogSink(logSink(file, compress))
        session.register(file, "hilog")

        ctx.out.info("录制中 → $file")
        ctx.out.info("Ctrl-C 结束并收尾（终端可实时看，文件同时压缩落盘）")
        val proc = HilogStream.pump(
            hdc.shellArgv(PlatformCmds.logCommand(hdc.kind, tags)),
            sink,
            regexOf(ctx, "match"),
            regexOf(ctx, "exclude"),
            !ctx.options.bool("full"),
        )
        val guardPid = LogWatchdog.spawn(proc.pid(), file, maxBytes)

        // 收尾只做一次：正常路径先跑，退出钩子再触发时直接跳过
        val finishLock = Mutex()
        var finished = false
        onExitCleanup {
            val need = finishLock.withLock {
                if (finished) false else { finished = true; true }
            }
            if (need) {
                runCatching { sink.close() }
                runCatching { ProcessTrees.kill(proc.pid()) }
                guardPid?.let { runCatching { ProcessTrees.kill(it) } }
                reportRecording(hdc.connectKey, file, sink.lines)
            }
        }

        // 只有「大小到顶 / 设备断流」会让它自然返回；Ctrl-C 走上面的 hook
        proc.waitFor()
        val needFinish = finishLock.withLock {
            if (finished) false else { finished = true; true }
        }
        if (needFinish) {
            runCatching { sink.close() }
            guardPid?.let { runCatching { ProcessTrees.kill(it) } }
            reportRecording(hdc.connectKey, file, sink.lines)
        }
        return ExitCode.OK
    }

    private fun reportRecording(device: String?, file: OhdPath, lines: Long) {
        val size = runCatching { fs.size(file) }.getOrDefault(0L)
        Stdio.outLine("")
        Stdio.outLine("■ 录制结束")
        Stdio.outLine("   文件  $file")
        Stdio.outLine("   大小  ${LogWatchdog.format(size)}   行数  $lines")
        device?.takeIf { it.isNotBlank() }?.let { Stdio.outLine("   设备  $it") }
        Stdio.outLine("   回放  ohd log --open ${file.fileName}")
        Stdio.outLine("   筛选  ohd log --file ${file.fileName} --match RE --tail 200")
    }

    /** 回放：`--open <id|latest|路径>` 或 `--file` + 过滤参数，全部流式打到终端。 */
    private fun show(ctx: RunContext, path: OhdPath): Int {
        if (!fs.isRegularFile(path)) {
            throw OhdError(ExitCode.USAGE, "日志文件不存在：$path", "用 ohd log ls 看有哪些录制")
        }
        val match = regexOf(ctx, "match")
        val exclude = regexOf(ctx, "exclude")
        val filter = ctx.options.str("filter")
        fun pass(line: String): Boolean {
            if (match != null && !match.containsMatchIn(line)) return false
            if (exclude != null && exclude.containsMatchIn(line)) return false
            if (!filter.isNullOrBlank() && !line.contains(filter)) return false
            return true
        }
        val tail = ctx.options.int("tail", 0)
        var shown = 0
        if (tail > 0) {
            HilogFiles.tailLines(path, tail).forEach { if (pass(it)) { println(it); shown++ } }
        } else {
            HilogFiles.forEachLine(path) { if (pass(it)) { println(it); shown++ } }
        }
        if (shown == 0) ctx.out.warn("没有命中行（$path）")
        return ExitCode.OK
    }

    /**
     * epoch 毫秒 → 与 `Instant.toString()` 同形状的 UTC 串。
     *
     * 形状：UTC 墙钟 + 字面 `Z`，毫秒非 0 时带 `.SSS`，为 0 时省略小数位
     * （与 `Instant.ofEpochMilli(...).toString()` / `FileTime.toString()` 的省略规则一致）。
     * 之所以不直接用 `TimeFmt.isoLocal`：那是**本机偏移**，会让这条给用户看的时间列
     * 随机器时区漂移，和历史日志/截图对不上。
     * `TimeFmt.formatWithOffset` 的 `Z` 是偏移格式符（offset=0 输出 `+0000`），
     * 拿不到字面 `Z`，所以自己按 UTC 格式化后再拼。
     */
    private fun instantText(epochMs: Long): String {
        val base = TimeFmt.formatWithOffset(epochMs, "yyyy-MM-ddTHH:mm:ss", 0)
        val ms = epochMs.mod(1000L)
        return if (ms == 0L) "${base}Z"
        else TimeFmt.formatWithOffset(epochMs, "yyyy-MM-ddTHH:mm:ss.SSS", 0) + "Z"
    }

    /** 录制清单：`--open` 的 id 就从这里来。 */
    private fun ls(ctx: RunContext): Int {
        val files = recordings(ctx)
        if (files.isEmpty()) {
            ctx.out.info("还没有录制（直接跑 ohd log 就会开始录）")
            return ExitCode.OK
        }
        ctx.out.info("共 ${files.size} 个录制（新的在前）")
        files.reversed().forEach { f ->
            val size = runCatching { fs.size(f) }.getOrDefault(0L)
            // 旧版是 Instant.toString()：UTC、带 `Z` 后缀，这是给用户看的列表时间列。
            // 刻意保留 UTC+Z 的显示形状（不换成 isoLocal 的本地偏移），
            // 否则同一份录制在 ls 里的时刻会随机器时区漂移、与老日志/老截图对不上。
            // TimeFmt 的 `Z` 是偏移格式符（offset=0 输出 "+0000"），拿不到字面 Z，
            // 所以按 UTC 墙钟格式化后再拼字面 `Z`。
            val time = runCatching { instantText(fs.lastModifiedMillis(f)) }.getOrDefault("-")
            val id = f.parent?.fileName?.toString() ?: f.fileName.toString()
            println("  $time  ${LogWatchdog.format(size).padStart(9)}  $id/${f.fileName}")
        }
        println()
        println("  回放：ohd log --open <id>   或  ohd log --open latest")
        return ExitCode.OK
    }

    /** 会话根下所有 hilog 产物，按 mtime 升序。 */
    private fun recordings(ctx: RunContext): List<OhdPath> {
        // sessionRoot 只有显式 --session-root 才有值；缺省时按默认证据根扫（否则 ls/--open latest 直接 NPE）
        val root = ctx.sessionRoot ?: Session.defaultRoot(ctx.cwd)
        if (!fs.isDirectory(root)) return emptyList()
        return runCatching {
            fs.walk(root, 4).use { s ->
                s.filter { fs.isRegularFile(it) }
                    .filter { (it.fileName?.toString() ?: "").contains("hilog") }
                    // sortedWith 而非 sorted：OhdPath 已实现 Comparable，带比较器的
                    // `sorted { a, b -> ... }` 会与无参 `sorted()` 重载撞车。
                    .sortedWith { a, b ->
                        runCatching { fs.lastModifiedMillis(a) }.getOrDefault(0L)
                            .compareTo(runCatching { fs.lastModifiedMillis(b) }.getOrDefault(0L))
                    }
                    .toList()
            }
        }.getOrDefault(emptyList<OhdPath>())
    }

    private fun resolveRecording(ctx: RunContext, id: String): OhdPath {
        val direct = OhdPath.of(id)
        if (fs.isRegularFile(direct)) return direct
        val all = recordings(ctx)
        if (all.isEmpty()) throw OhdError(ExitCode.USAGE, "还没有任何录制", "先跑 ohd log 录一段")
        if (id == "latest" || id == "last") return all.last()
        val hit = all.filter { it.toString().contains(id) || it.parent?.fileName?.toString() == id }
        return hit.lastOrNull()
            ?: throw OhdError(
                ExitCode.USAGE,
                "找不到录制：$id",
                "用 ohd log ls 看现有 id（也可直接给文件路径，或 --open latest）",
            )
    }

    private fun regexOf(ctx: RunContext, key: String): Regex? =
        ctx.options.str(key)?.takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrElse {
                throw OhdError(ExitCode.USAGE, "参数 --$key 不是合法正则：$it")
            }
        }

    /** 落盘 + 同步回显；行数用于结束摘要。 */
    private class EchoLogSink(private val inner: LogSink) : LogSink {
        @Volatile
        var lines: Long = 0
            private set

        override val path: OhdPath get() = inner.path
        override fun write(line: String) {
            inner.write(line)
            println(line)
            lines++
        }
        override fun flush() = inner.flush()
        override fun close() = inner.close()
    }

    private fun stop(ctx: RunContext): Int {
        // 先解析状态、再建会话：解析失败时不该留下一个空的 session 目录。
        val sp = resolveStatePath(ctx)
        val state = readJsonFile(sp)
            ?: throw missingState(ctx, sp, "先执行 ohd log start")
        val pid = state.long("pid")
            ?: throw OhdError(ExitCode.USAGE, "状态文件损坏：缺少 pid（可删 $sp 重来）")
        val file = state.str("file")
            ?: throw OhdError(ExitCode.USAGE, "状态文件损坏：缺少 file")

        val path = OhdPath.of(file)
        val maxBytes = state.long("maxBytes") ?: LogWatchdog.DEFAULT_MAX_BYTES
        val guardPid = state.long("guardPid")
        // 优先温和终止泵进程：SIGTERM 会触发 __pump 的 shutdown hook，
        // 把正在写的 gzip member 写完再退出；直接 SIGKILL 会留下半截 member
        val pumpPid = state.long("pumpPid") ?: pid
        val sizeBefore = runCatching { fs.size(path) }.getOrDefault(0L)
        ProcessTrees.kill(pumpPid, graceMs = 2_500)
        if (guardPid != null && guardPid > 0) ProcessTrees.kill(guardPid)
        val sizeAfter = runCatching { fs.size(path) }.getOrDefault(0L)
        val lines = countLines(path)
        val truncated = fs.exists(LogWatchdog.marker(path)) || LogWatchdog.exceeded(sizeAfter, maxBytes)
        fs.deleteIfExists(LogWatchdog.marker(path))
        fs.deleteIfExists(sp)
        if (lines > 0) Hdcs.session(ctx, "log")?.register(path.toAbsolutePath(), "hilog")

        val stalled = lines == 0
        if (truncated) {
            ctx.out.note(
                "LOG_CAPPED",
                "warn",
                "日志达到大小上限 ${LogWatchdog.format(maxBytes)}，采集已停（$file）",
            )
        }
        if (stalled) {
            ctx.out.note(
                "STREAM_EMPTY",
                "warn",
                "采集窗口内 0 行日志：设备可能安静（hilog 无输出），也可能是流僵死；"
                    + "延长采集时间、加 --clear，或先触发一次操作再 stop",
            )
        }
        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("file", file)
                put("lines", lines)
                put("bytes", sizeAfter)
                put("bytesBeforeKill", sizeBefore)
                put("stalled", stalled)
                put("truncated", truncated)
                put("maxBytes", maxBytes)
            }
            ctx.out.finish("log.stop", !stalled, data, 0)
        } else {
            ctx.out.kv(
                listOf(
                    "文件" to file,
                    "行数" to lines.toString(),
                    "字节" to sizeAfter.toString(),
                    "停止前字节" to sizeBefore.toString(),
                    "大小上限" to LogWatchdog.format(maxBytes),
                    "已截断" to if (truncated) "是" else "否",
                ),
            )
            if (!stalled) ctx.out.info("日志流已停止（$lines 行）")
        }
        return if (stalled) ExitCode.ASSERT_FAILED else ExitCode.OK
    }

    private fun currentFile(ctx: RunContext): String {
        val state = readJsonFile(resolveStatePath(ctx))
            ?: throw missingState(ctx, statePath(ctx), "先执行 ohd log start，或用 --file 指定")
        return state.str("file") ?: throw OhdError(ExitCode.USAGE, "状态文件损坏：缺少 file")
    }

    private fun resolveFile(ctx: RunContext): OhdPath {
        val explicit = ctx.options.str("file")
        val file = explicit ?: currentFile(ctx)
        val path = OhdPath.of(file)
        if (!fs.isRegularFile(path)) throw OhdError(ExitCode.USAGE, "日志文件不存在：$file")
        return path
    }

    /** 尾部 N 行：明文随机定位，gzip 顺序解压（.gz 不可 seek）。 */
    private fun tailLines(path: OhdPath, maxLines: Int): List<String> = HilogFiles.tailLines(path, maxLines)

    private fun countLines(path: OhdPath): Int = HilogFiles.countLines(path)

    private fun snap(ctx: RunContext): Int {
        val path = resolveFile(ctx)
        val tail = ctx.options.int("tail", 40)
        val filter = ctx.options.str("filter")
        val window = tailLines(path, if (filter != null) maxOf(tail * 20, 400) else tail)
        val picked = (if (filter != null) window.filter { it.contains(filter) } else window).takeLast(tail)
        val total = if (fs.size(path) <= 1024L * 1024) countLines(path) else -1
        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("totalLines", total)
                put("returned", picked.size)
                put("lines", buildJsonArray { picked.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }
            ctx.out.finish("log.snap", true, data, 0)
        } else {
            picked.forEach { println(it) }
            if (picked.isEmpty()) ctx.out.warn("无输出（filter=$filter）")
        }
        return ExitCode.OK
    }

    private fun count(ctx: RunContext): Int {
        val path = resolveFile(ctx)
        val match = ctx.options.str("match")?.let {
            try {
                Regex(it)
            } catch (e: Exception) {
                throw OhdError(ExitCode.USAGE, "非法正则 --match=$it：${e.message}")
            }
        }
        var total = 0
        var matched = 0
        val counter = linkedMapOf<String, Int>()
        HilogFiles.forEachLine(path) { line ->
            total++
            if (match != null && match.containsMatchIn(line)) matched++
            val parsed = HilogParser.parse(line) ?: return@forEachLine
            val key = parsed.tagShort
            counter[key] = (counter[key] ?: 0) + 1
        }
        val hist = counter.entries.sortedByDescending { it.value }
        val matchedOut = if (match != null) matched else -1

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("totalLines", total)
                put("matched", matchedOut)
                put("tags", buildJsonArray {
                    hist.take(20).forEach { (tag, n) ->
                        add(buildJsonObject { put("tag", tag); put("count", n) })
                    }
                })
            }
            ctx.out.finish("log.count", true, data, 0)
        } else {
            ctx.out.table(
                listOf("tag", "行数"),
                hist.take(20).map { listOf(it.key, it.value.toString()) },
            )
            println()
            ctx.out.kv(
                listOf(
                    "总行数" to total.toString(),
                    "正则匹配" to (if (matchedOut >= 0) "$matchedOut 行（$match）" else "-"),
                ),
            )
        }
        return ExitCode.OK
    }

    private fun save(ctx: RunContext): Int {
        val src = OhdPath.of(currentFile(ctx))
        val session = Hdcs.session(ctx, "log-save")
            ?: throw OhdError(ExitCode.USAGE, "save 需要落盘（勿用 --no-archive）", "示例：ohd log save --file /tmp/hilog.txt（参数可省略）")
        val dst = session.nextPath("hilog", if (HilogFiles.isCompressed(src)) "log.gz" else "txt")
        fs.copy(src, dst)
        session.register(dst, "hilog")
        session.writeManifest(null, mapOf("source" to src.toString()))
        if (ctx.out.jsonMode) {
            ctx.out.finish("log.save", true, buildJsonObject { put("file", dst.toString()) }, 0)
        } else {
            ctx.out.info("已保存：$dst")
        }
        return ExitCode.OK
    }
}
