package ohd.core.caps

import ohd.core.Capability
import ohd.core.ExitCode
import ohd.core.DeviceKind
import ohd.core.PlatformCmds
import ohd.core.parsers.AndroidParser
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.parseDurationMs
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser
import ohd.platform.Env
import ohd.platform.fs
import ohd.platform.OhdPath
import ohd.platform.fmt

/** 安装/替换 hap。A/B 与「验证新包是否修好」都从这一步开始。 */
class InstallCap : Capability {
    override val name = "install"
    override val summary = "安装/替换 hap：-r 覆盖安装，可保留应用数据"
    override val usage = "ohd install <hap> [--keep-data] [--json]"
    override val example = "ohd install /path/app-signed.hap --keep-data"
    override val accepts: Set<String> = setOf("keep-data", "json")

    override fun run(ctx: RunContext): Int {
        val p = ctx.options.positionals.firstOrNull()
            ?: throw OhdError(
                ExitCode.USAGE,
                "需要安装包路径",
                "示例：ohd install /path/app-signed.hap --keep-data（除路径外参数可省略）",
            )
        val hap = OhdPath.of(p).toAbsolutePath().normalize()
        // iOS 的 .app 是目录而不是单文件，不能用 isRegularFile 一刀切
        if (!fs.isRegularFile(hap) && !fs.isDirectory(hap)) {
            throw OhdError(ExitCode.USAGE, "安装包不存在：$hap")
        }

        val hdc = Hdcs.forDevice(ctx)
        val sizeMb = (if (fs.isDirectory(hap)) dirSize(hap) else fs.size(hap)) / 1024.0 / 1024.0
        // simctl install 没有 -r：覆盖语义是「已存在即替换」，传 -r 会被当成路径
        // 模拟器与真机都没有 `-r`：simctl/devicectl 的覆盖语义是「已存在即替换」
        val r = if (hdc.kind == DeviceKind.IOS || hdc.kind == DeviceKind.IOS_DEVICE) {
            hdc.run(listOf("install", hap.toString()), 180_000)
        } else {
            hdc.run(listOf("install", "-r", hap.toString()), 180_000)
        }
        if (r.exit != 0 || r.stdout.contains("[Fail]")) {
            // 覆盖安装失败通常是版本号回退或签名不一致，把设备原话原样带出去
            throw OhdError(
                ExitCode.NO_DEVICE,
                "安装失败：${(r.stdout + "\n" + r.stderr).trim().take(500)}\n" +
                    if (hdc.kind == DeviceKind.IOS_DEVICE) {
                        "  -> 真机要求：已开启开发者模式（设置→隐私与安全性→开发者模式）、\n" +
                            "     已信任这台 Mac、且 .app 用有效证书签名（免费开发者账号亦可）"
                    } else if (hdc.kind == DeviceKind.IOS) {
                        "  -> iOS 需要未签名/已签名的 .app 目录或 .ipa；确认模拟器已 Booted"
                    } else {
                        "  -> 版本回退会被拒；需要降级时先 ohd uninstall，或让开发提版本号"
                    },
            )
        }

        ctx.out.kv(
            listOf(
                "hap" to hap.fileName.toString(),
                "大小" to fmt("%.1f MB", sizeMb),
                "方式" to if (ctx.options.bool("keep-data")) "覆盖安装（未清数据）" else "覆盖安装",
                "结果" to "成功",
            )
        )
        return ExitCode.OK
    }
}

/** 递归统计目录体积（iOS 的 .app 是目录）。 */
private fun dirSize(root: OhdPath): Long = runCatching {
    fs.walk(root).use { s ->
        // CloseableSequence 是 kotlin Sequence，没有 Java Stream 的 mapToLong，用 sumOf 等价
        s.filter { fs.isRegularFile(it) }
            .sumOf { runCatching { fs.size(it) }.getOrDefault(0L) }
    }
}.getOrDefault(0L)

/**
 * A/B 对比：同一台设备、同一个 seed、同样时长，分别跑两个 hap，只报数值差异。
 *
 * 不做因果推断——差异只能说明「改了以后指标变了」，是不是这次改动带来的，要人结合代码判断。
 */
class AbCap : Capability {
    override val name = "ab"
    override val summary = "A/B 对比：装 A 跑一段 → 装 B 跑一段 → 对比 PSS/DMA/崩溃（同 seed 同时长）"
    override val usage = "ohd ab --a <hapA> --b <hapB> --bundle pkg [--soak 2m] [--every 5s] [--seed N] [--json]"
    override val example = "ohd ab --a a.hap --b b.hap --bundle com.tencent.compose --soak 2m --every 5s"
    override val accepts: Set<String> = setOf("a", "b", "bundle", "soak", "every", "seed", "json")

    private data class Metrics(
        val label: String,
        val version: String,
        val steps: Int,
        val pss: List<Long>,
        val dma: List<Long>,
        val crashes: Int,
    )

    override fun run(ctx: RunContext): Int {
        val hapA = ctx.options.str("a")
        val hapB = ctx.options.str("b")
        val bundle = ctx.options.str("bundle")
        // 缺几个一次说清：连着只报「需要 --a」「需要 --b」「需要 --bundle」最招人烦
        if (hapA == null || hapB == null || bundle == null) {
            val missing = listOfNotNull(
                if (hapA == null) "--a <改前 hap>" else null,
                if (hapB == null) "--b <改后 hap，须与 A 同包名>" else null,
                if (bundle == null) "--bundle <包名>" else null,
            )
            throw OhdError(
                ExitCode.USAGE,
                "缺少必填参数：" + missing.joinToString(" "),
                "示例：ohd ab --a old.hap --b new.hap --bundle com.example.app --soak 5m --every 2s（参数可省略）",
            )
        }
        val soakMs = parseDurationMs(ctx.options.str("soak") ?: "2m")
        val everyMs = parseDurationMs(ctx.options.str("every") ?: "5s").coerceAtLeast(1_000)
        val seed = ctx.options.str("seed")?.toLongOrNull() ?: Env.nowMillis()

        val a = measure(ctx, "A", hapA, bundle, soakMs, everyMs, seed)
        val b = measure(ctx, "B", hapB, bundle, soakMs, everyMs, seed)

        ctx.out.info("")
        ctx.out.table(
            listOf("指标", "A", "B", "差异"),
            listOf(
                listOf("版本", a.version, b.version, "-"),
                listOf("步数", a.steps.toString(), b.steps.toString(), "-"),
                listOf("PSS 峰值", mb(peak(a.pss)), mb(peak(b.pss)), diffPct(peak(a.pss), peak(b.pss))),
                listOf("PSS 均值", mb(avg(a.pss)), mb(avg(b.pss)), diffPct(avg(a.pss), avg(b.pss))),
                listOf(
                    "PSS 首末",
                    "${mb(a.pss.firstOrNull())} → ${mb(a.pss.lastOrNull())}",
                    "${mb(b.pss.firstOrNull())} → ${mb(b.pss.lastOrNull())}",
                    diffPct(a.pss.lastOrNull() ?: 0, b.pss.lastOrNull() ?: 0),
                ),
                listOf("DMA 峰值", mbBytes(peak(a.dma)), mbBytes(peak(b.dma)), diffPct(peak(a.dma), peak(b.dma))),
                listOf("DMA 均值", mbBytes(avg(a.dma)), mbBytes(avg(b.dma)), diffPct(avg(a.dma), avg(b.dma))),
                listOf("崩溃", a.crashes.toString(), b.crashes.toString(), "-"),
            ),
        )
        ctx.out.info("")
        ctx.out.info("差异列 = B 相对 A 的变化；正数表示 B 更高。")
        ctx.out.info("这只说明「数值变了」，是否由本次改动导致需结合代码判断。")
        if (a.crashes > 0 || b.crashes > 0) {
            ctx.out.note("AB_HAS_CRASH", "warn", "有阶段发生崩溃，指标可能被崩溃打断，对比结论需打折")
        }
        return ExitCode.OK
    }

    private fun measure(
        ctx: RunContext,
        label: String,
        hapPath: String,
        bundle: String,
        soakMs: Long,
        everyMs: Long,
        seed: Long,
    ): Metrics {
        val hap = OhdPath.of(hapPath).toAbsolutePath().normalize()
        if (!fs.isRegularFile(hap)) throw OhdError(ExitCode.USAGE, "--$label 指定的 hap 不存在：$hap")

        val hdc = Hdcs.forDevice(ctx)
        ohd.core.IosSupport.requireSupported("ab", hdc.kind)
        val install = hdc.run(listOf("install", "-r", hap.toString()), 180_000)
        if (install.exit != 0 || install.stdout.contains("[Fail]")) {
            throw OhdError(
                ExitCode.NO_DEVICE,
                "[$label] 安装失败：${(install.stdout + install.stderr).trim().take(300)}",
            )
        }

        val version = runCatching {
            val cmd = if (hdc.kind == DeviceKind.ANDROID) "dumpsys package $bundle 2>/dev/null | grep -m1 versionName"
                      else "bm dump -n $bundle 2>/dev/null | grep -m1 versionName"
            hdc.shell(cmd, 15_000).stdout.substringAfter(':', "").trim().trim('"', ',')
        }.getOrDefault("")

        // 每次都是冷启动，否则 A 的残留状态会污染 B
        val launchCmd = PlatformCmds.launchCommand(hdc.kind, bundle, "EntryAbility")
        val stopCmd = PlatformCmds.forceStopCommand(hdc.kind, bundle)

        hdc.shell(stopCmd, 15_000)
        Env.sleepMillis(800)
        val start = hdc.shell(launchCmd, 15_000)
        if (!start.ok) {
            throw OhdError(ExitCode.USAGE, "[$label] 启动失败：${start.stdout.trim().take(200)}")
        }
        Env.sleepMillis(1500)

        val pid = resolvePid(hdc, bundle)
            ?: throw OhdError(ExitCode.NO_DEVICE, "[$label] 拿不到 $bundle 的 pid")

        val pss = mutableListOf<Long>()
        val dma = mutableListOf<Long>()
        var steps = 0
        var crashes = 0
        val rnd = kotlin.random.Random(seed)
        val deadline = Env.nowMillis() + soakMs
        var nextSample = 0L

        // 轻量压测：只做坐标点击，不抓 layout，保证 A/B 的节拍只受包本身影响
        while (Env.nowMillis() < deadline) {
            val x = 80 + rnd.nextInt(1100)
            val y = 300 + rnd.nextInt(2200)
            hdc.shell(PlatformCmds.tapCommand(hdc.kind, x, y), 12_000)
            steps++

            if (!hdc.shell("pidof $bundle", 8_000).stdout.trim().equals(pid.toString())) {
                crashes++
                hdc.shell(launchCmd, 15_000)
                Env.sleepMillis(1200)
            }

            val now = Env.nowMillis()
            if (now >= nextSample) {
                nextSample = now + everyMs
                runCatching {
                    val raw = Hdcs.sh(null, hdc, PlatformCmds.memCommand(hdc.kind, pid), 25_000).stdout
                    pss.add(
                        if (hdc.kind == DeviceKind.ANDROID) AndroidParser.pssKb(raw) ?: -1L
                        else MemParser.parse(raw).pssKb,
                    )
                }
                runCatching {
                    // Android 的 dmabuf 需 root，读不到就跳过（不做 0 填充）
                    val r = Hdcs.sh(null, hdc, PlatformCmds.dmaCommand(hdc.kind, pid), 15_000)
                    if (r.ok) dma.add(DmabufParser.parse(r.text()).totalBytes)
                }
            }
        }

        ctx.out.info("[$label] ${hap.fileName} 完成：$steps 步，采样 ${pss.size} 次")
        return Metrics(label, version, steps, pss, dma, crashes)
    }

    private fun resolvePid(hdc: ohd.core.Hdc, bundle: String): Int? =
        hdc.shell("pidof $bundle", 8_000).stdout.trim().split(Regex("\\s+"))
            .firstOrNull()?.toIntOrNull()

    private fun peak(v: List<Long>): Long = v.filter { it > 0 }.maxOrNull() ?: 0
    private fun avg(v: List<Long>): Long = v.filter { it > 0 }.let { if (it.isEmpty()) 0 else it.sum() / it.size }
    /** PSS 单位是 KB */
    private fun mb(v: Long?): String = if (v == null || v <= 0) "-" else fmt("%.1f MB", v / 1024.0)
    /** DMA 单位是字节，与 PSS 的 KB 不是一个量纲，换算别复用 */
    private fun mbBytes(v: Long?): String = if (v == null || v <= 0) "-" else fmt("%.1f MB", v / 1024.0 / 1024.0)
    private fun diffPct(a: Long, b: Long): String = when {
        a <= 0 || b <= 0 -> "-"
        else -> fmt("%+.1f%%", (b - a) * 100.0 / a)
    }
}
