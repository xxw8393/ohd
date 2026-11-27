package ohd.core.caps

import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceKind
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdc
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.parseDurationMs
import ohd.image.GifWriter
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.ProcSpec
import ohd.platform.runProcess
import ohd.platform.spawnDetached
import kotlin.math.ceil
import ohd.platform.fmt
import ohd.platform.isProcessAlive
import ohd.platform.onExitCleanup
import kotlin.math.roundToInt
import ohd.platform.terminateProcess
import ohd.platform.killProcess
import ohd.platform.onInterrupt
import ohd.image.Frame
import ohd.image.decodeFrame
import ohd.image.decodeFrameFromFile
import ohd.image.pixelSize
import ohd.image.writeGif

/**
 * 录屏。三端命令行完全一致（`--duration` / `--fps` / `--display` / `--out` 语义相同），
 * 差异只落在「怎么采」和「能不能原生出这种格式」：
 *
 * | 平台 | 采集方式 | 原生格式 | `--fps` 作用 |
 * |---|---|---|---|
 * | Android | `screenrecord` 设备端编码 | mp4 | 仅转 gif 时抽帧用（mp4 帧率由系统决定） |
 * | iOS 模拟器 | `simctl io recordVideo` 宿主编码 | mp4 | 同上 |
 * | OHOS | 截帧 + [GifWriter] 合成 | gif | 抓帧帧率（并发抓帧逼近，实测 4→4.0、15→13.3） |
 *
 * **产物格式由 `--out` 后缀决定**：要 mp4 就给 `.mp4`，要 gif 就给 `.gif`。
 * 平台不能原生产出的那种，用 ffmpeg 转（找不到 ffmpeg 才明确报错）；不给 `--out` 时用平台原生格式。
 *
 * 为什么 OHOS 不原生出 mp4：JDK 无内置编码器，引编码器会让「解压即用」的分发包多几十 MB 依赖，
 * 所以原生只出 gif，需要 mp4 时走宿主已有的 ffmpeg。
 */
class VideoCap : Capability {
    override val name = "video"
    override val summary = "录屏：产物格式由 --out 后缀决定（mp4/gif/webp），三端命令一致"
    override val usage = """
        ohd video [--duration <时长>] [--fps 4] [--display N] [--out <文件.{mp4,gif,webp}>]
        默认时长：Android 3m（>180s 自动分段）、iOS/OHOS 30s
        原生格式：Android/iOS=mp4、OHOS=gif；跨格式用 ffmpeg 转，webp 另需 libwebp 的 gif2webp
        录制中 Ctrl+C 只提前收尾：已采到的帧/段落照常写成产物，并标注 INTERRUPTED
    """.trimIndent()
    override val example = "ohd video --duration 15s --fps 15 --out /tmp/demo.gif"
    override val accepts: Set<String> = setOf("duration", "fps", "display", "out")

    /** 录屏产物：[files] 可能多段（Android 超 180s），frames 为 -1 表示设备端编码、帧数未知。 */
    private data class Rec(val files: List<OhdPath>, val frames: Int, val sizeBytes: Long, val spanMs: Long = 0L)

    // Ctrl+C 提前收尾用：置位后各平台循环尽快退出，把已采到的内容正常写成产物
    @Volatile private var stopRequested = false
    @Volatile private var activeHdc: Hdc? = null
    @Volatile private var activeRemoteRecord = false
    @Volatile private var activeIosPid: Long? = null

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "video")

        stopRequested = false
        activeHdc = hdc
        val onStop = {
            stopRequested = true
            // Android 的 screenrecord 阻塞在 shell 里，必须让它收到 SIGINT 才会把 mp4 收尾写完整
            if (activeRemoteRecord) {
                activeHdc?.let { h -> runCatching { h.shell("pkill -INT screenrecord", 5_000) } }
            }
            // 只要把信号发出去，不等 kill 自己退出：spawnDetached 正好是「发完就走」。
            activeIosPid?.let { pid -> runCatching { spawnDetached(listOf("kill", "-INT", pid.toString())) } }
        }
        onInterrupt { onStop() }
        // Ctrl-C / 退出时收尾：onExitCleanup 是 addShutdownHook(Thread{}) 的跨平台替代
        onExitCleanup { onStop() }

        // Android 的 screenrecord 够稳，默认给 3m；OHOS 逐帧合成体积大，默认 30s 更实用
        val defaultDuration = if (hdc.kind == DeviceKind.ANDROID) "3m" else "30s"
        val durationMs = parseDurationMs(ctx.options.str("duration") ?: defaultDuration)
            .coerceIn(500, 30 * 60_000)
        val fps = ctx.options.int("fps", 4).coerceIn(1, 15)
        val displayId = ctx.options.str("display")?.trim()?.toIntOrNull()

        val nativeFormat = if (hdc.kind == DeviceKind.OHOS) "gif" else "mp4"
        val outRaw = ctx.options.str("out")?.trim()?.takeIf { it.isNotEmpty() }?.let { OhdPath.of(it) }
        val requested = outRaw?.fileName?.toString()?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
        val format = requested ?: nativeFormat
        if (format !in SUPPORTED_FORMATS) {
            throw OhdError(
                ExitCode.USAGE,
                "产物只支持 ${SUPPORTED_FORMATS.joinToString(" / ")}，--out 给的是 .$format",
                "改成 --out /tmp/demo.webp 这类路径；不给 --out 则用本机原生格式（$nativeFormat）",
            )
        }
        // 没写后缀就按目标格式补上，避免产出没有扩展名、打不开的文件
        val out = outRaw?.let {
            val name = it.fileName?.toString() ?: ""
            // name 为空只可能是根目录这类退化输入，此时补后缀反而造出隐藏文件，原样返回
            if (name.isEmpty() || name.contains('.')) it else it.resolveSibling("$name.$format")
        }
        val direct = format == nativeFormat

        val captureStart = Env.nowMillis()
        val rec = when (hdc.kind) {
            DeviceKind.ANDROID -> captureAndroid(hdc, session, durationMs, if (direct) out else null, direct, displayId)
            DeviceKind.IOS -> captureIos(hdc, session, durationMs, if (direct) out else null, direct)
            DeviceKind.OHOS -> captureOhos(hdc, session, durationMs, fps, displayId, if (direct) out else null, direct)
            DeviceKind.IOS_DEVICE -> throw OhdError(
                ExitCode.UNSUPPORTED,
                "iPhone 真机没有录屏通道：devicectl 不提供，Xcode 的录制也不走命令行",
                "改用模拟器（simctl io recordVideo），或真机手动录屏后把文件附到缺陷单",
            )
        }

        // 跨格式：先按原生格式录，再逐段转成目标格式（Android 分段时每段各转一份）
        val files = if (direct) {
            rec.files
        } else {
            val target = out ?: error("跨格式必须给 --out")
            rec.files.mapIndexed { i, src ->
                val dst = convertedTarget(target, i, rec.files.size)
                transcode(src, dst, format, fps)
                runCatching { fs.deleteIfExists(src) }
                session?.register(dst, "video")
                dst
            }
        }
        val sizeBytes = files.sumOf { sizeOf(it) }

        // 采集时长单独算：后面的 GIF 合成/ffmpeg 转码不算进帧率分母，否则中断场景的帧率会被低估
        val captureMs = Env.nowMillis() - captureStart
        val interrupted = stopRequested
        // span 是首末帧的真实间隔（不含 probe、合成、转码），中断场景下最贴近「实际录了多久」
        val effectiveMs = when {
            !interrupted -> durationMs
            rec.spanMs > 0 -> rec.spanMs          // 截帧平台：首末帧真实间隔
            else -> captureMs                      // 设备端编码（iOS/Android）：采集调用本身耗时
        }
        val actualFps = if (rec.frames > 0 && rec.spanMs > 0) {
            fmt("%.1f", rec.frames * 1000.0 / rec.spanMs)
        } else {
            "设备端编码"
        }
        val meta = mutableListOf<Pair<String, String>>()
        if (files.size == 1) {
            meta += "文件" to files[0].toAbsolutePath().toString()
        } else {
            files.forEachIndexed { i, p -> meta += "片段 ${i + 1}/${files.size}" to p.toAbsolutePath().toString() }
        }
        meta += "时长" to if (interrupted) {
            "${fmt("%.1f", effectiveMs / 1000.0)}s（Ctrl+C 提前结束）"
        } else {
            "${fmt("%.1f", durationMs / 1000.0)}s"
        }
        meta += "格式" to format
        meta += "帧数" to if (rec.frames > 0) rec.frames.toString() else "-"
        meta += "实际帧率" to actualFps
        meta += "大小" to bytesText(sizeBytes)
        ctx.out.kv(meta)
        if (sizeBytes <= 0L) {
            ctx.out.note("EMPTY_VIDEO", "warn", "录屏文件为空：${files.firstOrNull()}")
        }
        if (interrupted) {
            ctx.out.note(
                "INTERRUPTED",
                "info",
                "录制被 Ctrl+C 提前结束，已保存中断前的内容（${fmt("%.1f", effectiveMs / 1000.0)}s）",
            )
        }
        if (!direct) {
            ctx.out.note("TRANSCODED", "info", "本平台原生格式是 $nativeFormat，已转成 $format")
        }
        if (files.size > 1) {
            ctx.out.note(
                "SEGMENTED",
                "info",
                "超过 screenrecord 单次 180s 上限，已切成 ${files.size} 段；mp4 不能简单拼接，按顺序附上即可",
            )
        }
        ctx.out.finish(
            "video",
            true,
            buildJsonObject {
                put("file", files.firstOrNull()?.toAbsolutePath()?.toString() ?: "")
                put("files", buildJsonArray { files.forEach { add(JsonPrimitive(it.toAbsolutePath().toString())) } })
                put("durationMs", effectiveMs)
                put("captureMs", captureMs)
                put("interrupted", interrupted)
                put("frames", rec.frames)
                put("sizeBytes", sizeBytes)
                put("platform", hdc.kind.label)
                put("format", format)
            },
            Env.nowMillis() - started,
        )
        return ExitCode.OK
    }

    /**
     * Android：`screenrecord` 自带时长参数，跑满自动收敛（mp4 尾部完整，不需要信号收尾）。
     * 超过 180 s 按段录制：每段单独一个文件，段号用 `-2`、`-3` 后缀区分 `--out` 基名。
     */
    private fun captureAndroid(hdc: Hdc, session: Session?, ms: Long, out: OhdPath?, register: Boolean, displayId: Int?): Rec {
        DeviceOps.ensureTmp(hdc)
        val displayArg = displayId?.let { " --display-id $it" } ?: ""
        val segmentCap = 180_000L
        val files = mutableListOf<OhdPath>()
        var remaining = ms
        var segment = 1
        while (remaining > 0) {
            val take = minOf(remaining, segmentCap)
            val sec = ((take + 999) / 1000).toInt().coerceAtLeast(1)
            val remote = "${DeviceOps.TMP}/video-${Env.nowMillis()}.mp4"
            activeRemoteRecord = true
            val r = hdc.shell("screenrecord$displayArg --time-limit $sec --bit-rate 8000000 $remote", take + 40_000)
            activeRemoteRecord = false
            // 被 Ctrl+C 打断时 screenrecord 已收到 SIGINT、mp4 已收尾，不能按失败处理
            if (!r.ok && !stopRequested) {
                // 第一段就失败说明通道不可用；后续段失败保留已录内容，不把整次录像判死
                if (files.isEmpty()) {
                    throw OhdError(
                        ExitCode.UNSUPPORTED,
                        "screenrecord 失败：${r.text().take(300)}",
                        "确认设备已解锁、前台有画面；部分机型的录制受 DRM/安全窗口限制",
                    )
                }
                break
            }
            val local = segmentPath(out, session, segment, "mp4")
            local.parent?.let { fs.createDirectories(it) }
            val recv = hdc.recv(remote, local, 120_000)
            hdc.shell("rm -f $remote", 10_000)
            if (!recv.ok) {
                if (stopRequested && files.isNotEmpty()) break
                throw OhdError(ExitCode.TIMEOUT, "拉取录像失败：${recv.text().take(300)}")
            }
            if (register) session?.register(local, "video")
            files.add(local)
            remaining -= take
            segment++
            if (stopRequested) break
        }
        return Rec(files, -1, files.sumOf { sizeOf(it) })
    }

    /**
     * iOS 模拟器：`simctl io recordVideo` 阻塞，靠 SIGINT 收尾（SIGTERM 会丢 moov 导致文件不可播）。
     * 所以这里自己起进程而不走 [Hdc.run]——它等的是自然退出。
     */
    private fun captureIos(hdc: Hdc, session: Session?, ms: Long, out: OhdPath?, register: Boolean): Rec {
        val local = (out ?: session?.nextPath("video", "mp4") ?: fs.createTempFile("ohd-video-", ".mp4"))
            .toAbsolutePath().normalize()
        local.parent?.let { fs.createDirectories(it) }
        val argv = hdc.argv("io", "recordVideo", "--codec=h264", "--force", local.toString())
        // 不用 runProcess：它等进程自然退出，超时还会 destroyForcibly（SIGKILL 会丢 moov）。
        // 这里要的是「先跑着，收尾时发 SIGINT」，所以拿 detached 的 pid 自己控。
        val recorded = spawnDetached(argv)
        val pid = recorded.pid ?: throw OhdError(
            ExitCode.UNSUPPORTED,
            "无法启动 simctl recordVideo：${recorded.spawnError}",
            "确认 Xcode 命令行工具可用",
        )
        activeIosPid = pid
        var stopFailed = false
        try {
            // 分片睡，Ctrl+C 后最多 100ms 就能进 finally 发 SIGINT 收尾
            var remaining = ms
            while (remaining > 0 && !stopRequested) {
                val step = minOf(remaining, 100L)
                Env.sleepMillis(step)
                remaining -= step
            }
        } finally {
            activeIosPid = null
            // 优先 SIGINT（官方收尾方式），kill 不可用才退回 SIGTERM
            val signal = runProcess(
                ProcSpec(
                    command = listOf("kill", "-INT", pid.toString()),
                    timeoutMillis = 5_000,
                ),
            )
            stopFailed = signal.spawnError != null || signal.timedOut
        }
        if (stopFailed) terminateProcess(pid)
        // 等它自己收尾：最多 10s，超时才强杀（等价旧的 waitFor(10s) + destroyForcibly）。
        val deadline = Env.nowMillis() + 10_000
        while (alivePid(pid) && Env.nowMillis() < deadline) Env.sleepMillis(50)
        if (alivePid(pid)) killProcess(pid)
        val size = sizeOf(local)
        if (size <= 0L) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "simctl 没有写出录像文件：$local",
                "确认模拟器已 Booted 且不是锁屏状态；`xcrun simctl list devices booted`",
            )
        }
        if (register) session?.register(local, "video")
        return Rec(listOf(local), -1, size)
    }

    private fun captureOhos(
        hdc: Hdc,
        session: Session?,
        ms: Long,
        fps: Int,
        displayId: Int?,
        out: OhdPath?,
        register: Boolean,
    ): Rec {
        DeviceOps.ensureTmp(hdc)
        // OHOS 熄屏极快（实测 10s 级），不锁常亮的话录到一半全是黑帧——先亮屏再压住熄屏时长，结束时恢复
        hdc.shell("power-shell wakeup", 10_000)
        val keepOn = hdc.shell("power-shell timeout -o ${ms + 30_000}", 10_000).ok
        try {
            return captureOhosFrames(hdc, session, ms, fps, displayId, out, register)
        } finally {
            if (keepOn) hdc.shell("power-shell timeout -r", 10_000)
        }
    }

    /**
     * OHOS 截帧循环。单张 `snapshot_display` 约 430 ms，串行只有 2 fps，
     * 所以按目标帧间隔算出每批并发数，一批同时抓、抓完统一收。
     * 批内帧时间戳按目标间隔均分——是为了让 GIF 播放平滑，实际抓取时刻集中在这批的几百毫秒内。
     */
    private fun captureOhosFrames(
        hdc: Hdc,
        session: Session?,
        ms: Long,
        fps: Int,
        displayId: Int?,
        out: OhdPath?,
        register: Boolean,
    ): Rec {
        DeviceOps.ensureTmp(hdc)
        val intervalMs = 1000.0 / fps
        // 单张约 430~450ms，一批要覆盖「抓取时延 + host 收帧开销（recv+解码约 20ms/帧）」，
        // 否则批间空转、帧率掉回串行水平。实测设备端并发到 16 张仍线性（753ms），
        // 再往上就饱和（24 张 1117ms）；上限取 12，兼顾帧率与对被测应用的干扰
        val batch = ceil(700.0 / intervalMs).toInt().coerceIn(1, 12)
        val deadline = Env.nowMillis() + ms
        val frames = mutableListOf<Frame>()
        val frameTimes = mutableListOf<Long>()
        val displayArg = displayId?.let { " -i $it" } ?: ""
        // 先用一张全尺寸帧探屏幕尺寸，再按 480 宽等比缩小抓取：
        // 单帧 250KB → 54KB，而传输（recv）是批处理里最大的一笔开销；GIF 最终也就 480 宽，不浪费
        val probe = probeFrame(hdc, displayArg, "${DeviceOps.TMP}/ohd-vid-probe.jpeg")
        val scaleW = if (probe != null && probe.pixelSize().first > 480) 480 else 0
        val scaleH = if (scaleW > 0 && probe != null) {
            val (pw, ph) = probe.pixelSize()
            if (pw > 0) (480.0 * ph / pw).roundToInt() else 0
        } else 0
        val resArg = if (scaleW > 0) " -w $scaleW -h $scaleH" else ""
        if (probe != null) {
            frames.add(probe)
            frameTimes.add(Env.nowMillis())
        }
        try {
            while (Env.nowMillis() < deadline && !stopRequested) {
                val batchStart = Env.nowMillis()
                // 每批独立目录：抓取进程写自己的目录，下批脚本开头统一清理，省掉一次 shell 往返
                val dir = "${DeviceOps.TMP}/ohd-vid-$batchStart"
                val script = buildString {
                    append("rm -rf ").append(DeviceOps.TMP).append("/ohd-vid-*; mkdir -p ").append(dir).append("; ")
                    for (i in 0 until batch) {
                        append("snapshot_display").append(displayArg).append(resArg)
                            .append(" -f ").append(dir).append("/v").append(i).append(".jpeg >/dev/null 2>&1 & ")
                    }
                    append("wait; ")
                    // 一次 ls 报全部大小、一次 cat 拼成单包：逐张 fork 在设备上每次 ~50ms，8 张就白丢 400ms
                    val files = (0 until batch).joinToString(" ") { "$dir/v$it.jpeg" }
                    append("ls -l ").append(files).append("; cat ").append(files).append(" > ").append(dir).append("/pack.bin")
                }
                val r = hdc.runIgnoringInterrupt(listOf("shell", script), 60_000)
                if (!r.ok) break
                val sizes = r.stdout.lines()
                    .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(4)?.toIntOrNull() }
                    .filter { it > 0 }
                val packTmp = fs.createTempFile("ohd-vpack-", ".bin")
                val recv = hdc.recv("$dir/pack.bin", packTmp, 60_000)
                if (recv.ok) {
                    val pack = fs.readBytes(packTmp)
                    var off = 0
                    for ((i, size) in sizes.withIndex()) {
                        if (off + size > pack.size) break
                        val img = decodeFrame(pack, off, size)
                        if (img != null) {
                            frames.add(img)
                            frameTimes.add(batchStart + (i * intervalMs).toLong())
                        }
                        off += size
                    }
                }
                fs.deleteIfExists(packTmp)
                val target = (batch * intervalMs).toLong()
                val used = Env.nowMillis() - batchStart
                if (used < target && !stopRequested) {
                    var wait = target - used
                    while (wait > 0 && !stopRequested) {
                        val step = minOf(wait, 100L)
                        Env.sleepMillis(step)
                        wait -= step
                    }
                }
                if (frames.size >= MAX_FRAMES) break
            }
        } finally {
            hdc.shell("rm -rf ${DeviceOps.TMP}/ohd-vid-*", 10_000)
        }
        if (frames.isEmpty()) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "OHOS 截帧失败，未取到任何画面",
                "确认前台有窗口、屏幕已亮；`ohd shot` 能出图则本命令也应可用",
            )
        }
        val gaps = frameTimes.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val avgGap = if (gaps.isEmpty()) (1000.0 / fps).toLong() else gaps.sum() / gaps.size
        val delay = avgGap.coerceIn(50, 1000).toInt()
        val local = out ?: session?.nextPath("video", "gif") ?: fs.createTempFile("ohd-video-", ".gif")
        local.parent?.let { fs.createDirectories(it) }
        // 这里的帧是 ffmpeg 解码出的内存位图（视频解码本身是 JDK 依赖），走 JVM 专用重载
        val ok = writeGif(frames, local, delayMs = delay, maxWidth = 480, maxFrames = MAX_FRAMES)
        if (!ok) throw OhdError(ExitCode.UNSUPPORTED, "GIF 合成失败：$local", "帧数为 ${frames.size}，可能是写入权限问题")
        if (register) session?.register(local, "video")
        val span = if (frameTimes.size > 1) frameTimes.last() - frameTimes.first() else 0L
        return Rec(listOf(local), frames.size, sizeOf(local), span)
    }

    /**
     * Ctrl+C 只提前收尾，不放弃本次录制：拦下 SIGINT（否则 JVM 直接退出、产物全丢），
     * 置停止标志并唤醒阻塞中的采集；主流程把已抓到的帧/已录的段落正常写成产物。
     * 与 [RecordCap] 同一套手法，保持行为一致。
     */

    /** 抓一张全尺寸帧：既用来探屏幕尺寸（据此算等比缩放高度），也直接当第一帧用。 */
    /**
     * 录屏一律用 jpeg：这里要的是帧率和体积，不是像素保真。
     * 实测（1280x2832，API 24）jpeg 约 0.3s/181KB，png 约 0.67s/1.38MB；
     * 切 png 会让本循环掉到约 1.5fps 且单批体积涨 8 倍。
     * 需要无损像素的地方（diff / 标注）走 DeviceOps.snapshot(format = PNG)。
     */
    private fun probeFrame(hdc: Hdc, displayArg: String, remote: String): Frame? {
        val r = hdc.runIgnoringInterrupt(listOf("shell", "snapshot_display$displayArg -f $remote >/dev/null 2>&1"), 30_000)
        if (!r.ok) return null
        val tmp = fs.createTempFile("ohd-probe-", ".jpeg")
        val recv = hdc.recv(remote, tmp, 30_000)
        val img = if (recv.ok) decodeFrameFromFile(tmp) else null
        fs.deleteIfExists(tmp)
        hdc.shell("rm -f $remote", 10_000)
        return img
    }

    /** `--out` 给定时第一段用它，后续段加 `-N`；未给定时走会话目录的递增序号。 */
    private fun segmentPath(out: OhdPath?, session: Session?, segment: Int, ext: String): OhdPath {
        if (out == null) return session?.nextPath("video", ext) ?: fs.createTempFile("ohd-video-", ".$ext")
        if (segment == 1) return out
        val name = out.fileName?.toString() ?: ""
        val base = name.substringBeforeLast('.', name)
        val suffix = name.substringAfterLast('.', ext)
        return out.resolveSibling("$base-$segment.$suffix")
    }

    /** 跨格式转换的落点：单段直接用 `--out`，多段按段号分开。 */
    private fun convertedTarget(out: OhdPath, index: Int, total: Int): OhdPath {
        if (total <= 1) return out
        val name = out.fileName?.toString() ?: ""
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "gif")
        return out.resolveSibling("$base-${index + 1}.$ext")
    }

    /**
     * 平台不能原生的格式用宿主 ffmpeg 转：mp4→gif 按 `--fps` 抽帧并缩到 480 宽，
     * gif→mp4 用 yuv420p + faststart（微信/浏览器都能直接播）。
     */
    private fun transcode(src: OhdPath, dst: OhdPath, format: String, fps: Int) {
        dst.parent?.let { fs.createDirectories(it) }
        when (format) {
            "gif" -> runTool(
                listOf("ffmpeg", "-y", "-loglevel", "error", "-i", src.toString(), "-vf", "fps=$fps,scale=480:-1:flags=lanczos", dst.toString()),
                dst, "ffmpeg",
            )
            "mp4" -> runTool(
                listOf("ffmpeg", "-y", "-loglevel", "error", "-i", src.toString(), "-r", fps.toString(), "-pix_fmt", "yuv420p", "-movflags", "+faststart", dst.toString()),
                dst, "ffmpeg",
            )
            // 动画 WebP：JDK 没有编码器，只能借 libwebp 的 gif2webp；源不是 gif 时先用 ffmpeg 出一次 gif 中转
            "webp" -> {
                val gif = if ((src.fileName?.toString() ?: "").endsWith(".gif")) {
                    src
                } else {
                    val tmp = fs.createTempFile("ohd-webp-", ".gif")
                    runTool(
                        listOf("ffmpeg", "-y", "-loglevel", "error", "-i", src.toString(), "-vf", "fps=$fps,scale=480:-1:flags=lanczos", tmp.toString()),
                        tmp, "ffmpeg",
                    )
                    tmp
                }
                try {
                    runTool(listOf("gif2webp", "-q", "75", "-m", "4", gif.toString(), "-o", dst.toString()), dst, "gif2webp")
                } finally {
                    if (gif != src) fs.deleteIfExists(gif)
                }
            }
        }
    }

    /** 跑一个外部转换工具并校验产物：统一「找不到 / 失败 / 没产出」三种报错。 */
    private fun runTool(cmd: List<String>, dst: OhdPath, tool: String) {
        val r = runProcess(ProcSpec(command = cmd, timeoutMillis = 300_000))
        if (r.spawnError != null) {
            throw OhdError(
                ExitCode.UNSUPPORTED,
                "格式转换需要 $tool，但 PATH 里找不到",
                if (tool == "gif2webp") "安装 libwebp（brew install webp），或改用 --out x.mp4 / x.gif" else "安装 ffmpeg（brew install ffmpeg），或不给 --out 用平台原生格式",
            )
        }
        // 旧实现 redirectErrorStream(true)：诊断信息与进度都在同一条流里，报错时一并展示。
        val log = r.stdout + r.stderr
        if (r.timedOut || r.exitCode != 0) {
            throw OhdError(ExitCode.UNSUPPORTED, "$tool 转换失败：${log.take(300)}", "确认输入文件完整，或改用平台原生格式（不给 --out）")
        }
        if (sizeOf(dst) <= 0L) {
            throw OhdError(ExitCode.UNSUPPORTED, "$tool 没写出目标文件：$dst", "检查目标目录权限")
        }
    }

    private fun sizeOf(p: OhdPath): Long = runCatching { fs.size(p) }.getOrDefault(0L)

    private fun alivePid(pid: Long): Boolean =
        isProcessAlive(pid)

    private fun bytesText(n: Long): String = when {
        n >= 1024L * 1024 -> fmt("%.1f MB", n / 1024.0 / 1024.0)
        n >= 1024L -> fmt("%.1f KB", n / 1024.0)
        else -> "$n B"
    }

    private companion object {
        /** GIF 帧数上限：再大文件会膨胀到几十 MB，回放也卡。 */
        const val MAX_FRAMES = 600

        /** 产物支持格式；webp 需宿主 libwebp（gif2webp），mp4/gif 需 ffmpeg 跨格式时用。 */
        val SUPPORTED_FORMATS = listOf("mp4", "gif", "webp")
    }
}
