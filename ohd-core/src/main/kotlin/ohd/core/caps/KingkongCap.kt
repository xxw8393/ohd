package ohd.core.caps

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.DeviceOps
import ohd.core.ExitCode
import ohd.core.Hdcs
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Session
import ohd.core.isoTime
import ohd.core.parsers.CpuParser
import ohd.core.parsers.DmabufParser
import ohd.core.parsers.MemParser
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * DevEco Testing「稳定性基础质量测试」同款 monkey：直接调设备上的 **KingKong（金刚）**。
 *
 * 为什么要这个能力：`monkey` 是 ohd 自己的随机注入（随机坐标、Back 限频），事件分布与
 * DevEco 的稳定性压测**不一样**，内存曲线没法对拍。金刚按「点击可点组件 85% / touch 13%
 * / swipe 2% / 切应用 0.2%」注入，且边读 UI 树边随机选，所以只能做「同参数同分布」的 A/B。
 *
 * 两个必须踩过的坑（都已在实现里处理）：
 * 1. `stop_signal/` 目录残留会让金刚起来就 `exit code:0` —— 起跑前必须清空；
 * 2. 金刚的退出条件包含「目标应用不在前台」：一旦它自己按了 Back 把应用顶到桌面，就会
 *    `recv stop signal` 退出（DevEco 也是这么一轮轮重启的）。所以这里是**轮次驱动**：
 *    退出就重新前台化 + 重启，直到总时长用完，并把各轮事件数累加。
 */
class KingkongCap : Capability {
    override val name = "kingkong"
    override val summary = "DevEco 同款金刚 monkey：推二进制 → 轮次起跑 → 周期采样 → 收尾取证"
    override val usage = "ohd kingkong [--bundle b] [--duration 8h] [--abi arm64-v8a] [--binary <路径>] " +
        "[--remote-dir /data/local/tmp/kingkongDir] [--no-push] [--weights t=0.13,r=0.02,a=0.002,w=0.85] " +
        "[--event-mask 2] [--interval 0] [--sample-period 60s] [--max-restarts 200] [--no-dma] [--no-sampler]"
    override val example = "ohd kingkong --bundle com.tencent.compose --duration 8h --sample-period 180s"
    override val accepts: Set<String> = setOf(
        "bundle", "duration", "abi", "binary", "remote-dir", "no-push", "weights", "event-mask",
        "interval", "sample-period", "max-restarts", "no-dma", "no-sampler", "json",
    )

    private data class Sample(val i: Int, val ms: Long, val pssKb: Long, val cpu: Double, val dmaBytes: Long)

    override fun run(ctx: RunContext): Int {
        val started = System.currentTimeMillis()
        val hdc = Hdcs.forDevice(ctx)
        val session = Hdcs.session(ctx, "kingkong")
        val bundle = ctx.options.str("bundle")
            ?: throw OhdError(ExitCode.USAGE, "缺少 --bundle", "ohd kingkong --bundle com.example.app")

        val abi = ctx.options.str("abi") ?: "arm64-v8a"
        val remoteDir = (ctx.options.str("remote-dir") ?: "/data/local/tmp/kingkongDir").trimEnd('/')
        val durationMs = parseMs(ctx.options.str("duration") ?: "8h")
        val intervalMs = parseMs(ctx.options.str("sample-period") ?: "60s")
        val eventInterval = ctx.options.str("interval") ?: "0"
        val eventMask = ctx.options.str("event-mask") ?: "2"
        val weights = parseWeights(ctx.options.str("weights") ?: "t=0.13,r=0.02,a=0.002,w=0.85")
        val maxRestarts = (ctx.options.str("max-restarts") ?: "200").toIntOrNull() ?: 200
        val withDma = !ctx.options.bool("no-dma")
        val withSampler = !ctx.options.bool("no-sampler")

        val remoteBin = "$remoteDir/kingkong"
        val remoteLog = "$remoteDir/kingkong_log/ohd-${System.currentTimeMillis()}.log"

        hdc.shell("mkdir -p $remoteDir/kingkong_log", 15_000)
        val localBin = if (ctx.options.bool("no-push")) null else resolveBinary(ctx.options.str("binary"), abi)
        if (localBin != null) {
            val r = hdc.send(localBin, remoteBin, 120_000)
            if (!r.ok) throw OhdError(ExitCode.ASSERT_FAILED, "推送 kingkong 失败：${r.text().take(300)}")
            hdc.shell("chmod +x $remoteBin", 15_000)
            if (!ctx.out.jsonMode) ctx.out.info("推送 ${localBin.fileName} ($abi) → $remoteBin")
        } else {
            val probe = hdc.shell("test -x $remoteBin && echo yes", 15_000)
            if (!probe.text().contains("yes")) {
                throw OhdError(
                    ExitCode.USAGE, "设备上没有可执行的 $remoteBin",
                    "归档到 tools/bin/kingkong/$abi/kingkong，或 --binary <路径>；设备已备好可加 --no-push",
                )
            }
            if (!ctx.out.jsonMode) ctx.out.info("使用设备上已有的 $remoteBin（--no-push）")
        }

        fun events(): Int = hdc.shell("grep -c 'event type is' $remoteLog 2>/dev/null || true", 10_000)
            .text().trim().toIntOrNull() ?: 0
        fun alive(): Boolean = hdc.shell("pidof kingkong", 8_000).text().trim().isNotEmpty()
        fun ensureForeground() {
            hdc.shell("aa start -b $bundle -a EntryAbility", 20_000)
            Thread.sleep(3_000)
        }

        if (!ctx.out.jsonMode) {
            ctx.out.kv(
                listOf(
                    "Bundle" to bundle,
                    "时长" to humanDuration(durationMs),
                    "权重" to "touch=${weights["t"]} swipe=${weights["r"]} appswitch=${weights["a"]} widget=${weights["w"]}",
                    "设备日志" to remoteLog,
                    "采样" to if (withSampler) humanDuration(intervalMs) else "关闭",
                ),
            )
            println()
        }

        val samples = mutableListOf<Sample>()
        val deadline = System.currentTimeMillis() + durationMs
        var round = 0
        var restarts = 0
        var sampleNo = 0
        var injected = 0

        while (System.currentTimeMillis() < deadline) {
            if (!alive()) {
                if (restarts > maxRestarts) {
                    throw OhdError(
                        ExitCode.ASSERT_FAILED, "金刚连续重启超过 $maxRestarts 次仍未注入事件",
                        "先确认前台是 $bundle、清掉 $remoteDir/stop_signal，必要时 --no-push 用设备上已验证的二进制",
                    )
                }
                // 一轮结束（金刚自己判「前台不是目标应用」就退），按 DevEco 的做法重启
                ensureForeground()
                hdc.shell("rm -f $remoteDir/stop_signal/* 2>/dev/null; true", 10_000)
                val remainSec = ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(30)
                val exec = "$remoteBin exec -K $eventMask -T $remainSec -R -i $eventInterval " +
                    "-b $bundle -t ${weights["t"]} -r ${weights["r"]} -a ${weights["a"]} -W ${weights["w"]}"
                val launch = hdc.shell("nohup $exec >> $remoteLog 2>&1 & echo launched:$!", 30_000)
                if (!launch.ok) throw OhdError(ExitCode.ASSERT_FAILED, "启动金刚失败：${launch.text().take(300)}")
                round++
                if (round > 1) restarts++
                if (!ctx.out.jsonMode) ctx.out.info("第 $round 轮起跑（剩余 ${humanDuration(deadline - System.currentTimeMillis())}）")

                // 等它真的开始注入（金刚起跑要 ~20-35s 读 UI 树，急不得）
                var waited = 0
                var before = events()
                while (waited < 40_000 && alive() && events() <= before) {
                    Thread.sleep(2_000); waited += 2_000
                }
                injected = events()
                if (injected == 0 && !alive()) {
                    val tail = hdc.shell("tail -4 $remoteLog", 15_000).text().trim().take(400)
                    if (!ctx.out.jsonMode) ctx.out.info("本轮未注入事件即退出（$tail）")
                }
            }

            if (withSampler) {
                val t0 = System.currentTimeMillis()
                sampleNo++
                val pid = DeviceOps.resolvePid(hdc, null, bundle)
                val pss = runCatching {
                    MemParser.parse(Hdcs.sh(session, hdc, "hidumper --mem $pid", 25_000).stdout).pssKb
                }.getOrDefault(-1L)
                val cpu = runCatching {
                    CpuParser.parse(Hdcs.sh(session, hdc, "hidumper --cpuusage $pid", 25_000).stdout).usage?.total ?: 0.0
                }.getOrDefault(0.0)
                var dma = -1L
                if (withDma) {
                    runCatching {
                        val r = Hdcs.sh(session, hdc, "cat /proc/$pid/mm_dmabuf_info", 20_000)
                        if (r.ok) dma = DmabufParser.parse(r.text()).totalBytes
                    }
                }
                if (pss >= 0) samples.add(Sample(sampleNo, System.currentTimeMillis(), pss, cpu, dma))
                if (!ctx.out.jsonMode) {
                    val dmaPart = if (dma >= 0) " dma=${humanBytes(dma)}" else ""
                    ctx.out.info(
                        "[%d] pss=%s cpu=%.1f%%%s 轮=%d 事件=%d (%dms)".format(
                            sampleNo, if (pss >= 0) humanBytes(pss * 1024) else "-", cpu, dmaPart,
                            round, injected, System.currentTimeMillis() - t0,
                        ),
                    )
                }
                val remain = intervalMs - (System.currentTimeMillis() - t0)
                if (remain > 0) Thread.sleep(remain)
            } else {
                Thread.sleep(intervalMs.coerceAtMost(30_000))
            }
        }

        hdc.shell("pkill -f '$remoteBin' ; sleep 1 ; pidof kingkong", 20_000)
        injected = events()
        val logLocal: Path? = session?.nextPath("kingkong", "log")
        if (logLocal != null) runCatching { hdc.recv(remoteLog, logLocal, 60_000) }
        val csv = session?.nextPath("kingkong", "csv")
        if (csv != null && samples.isNotEmpty()) {
            val sb = StringBuilder("index,time,pss_kb,cpu_percent,dma_bytes\n")
            samples.forEach { s -> sb.appendLine("${s.i},${isoTime(s.ms)},${s.pssKb},${"%.2f".format(s.cpu)},${s.dmaBytes}") }
            Session.writeText(csv, sb.toString())
            session.register(csv, "kingkong-csv")
        }

        val spanSec = if (samples.size >= 2) (samples.last().ms - samples.first().ms) / 1000.0 else 0.0
        val slope = if (spanSec > 0) (samples.last().pssKb - samples.first().pssKb) / 1024.0 / (spanSec / 60.0) else 0.0
        val peak = samples.maxOfOrNull { it.pssKb } ?: 0L
        val dmaDelta = if (samples.size >= 2 && samples.last().dmaBytes >= 0 && samples.first().dmaBytes >= 0)
            samples.last().dmaBytes - samples.first().dmaBytes else 0L

        if (ctx.out.jsonMode) {
            val data = buildJsonObject {
                put("schema", "ohd-kingkong/1")
                put("bundle", bundle)
                put("abi", abi)
                put("rounds", round)
                put("events", injected)
                put("samples", samples.size)
                put("spanSec", spanSec)
                put("pssFirstKb", samples.firstOrNull()?.pssKb ?: 0L)
                put("pssLastKb", samples.lastOrNull()?.pssKb ?: 0L)
                put("pssPeakKb", peak)
                put("pssSlopeMbPerMin", slope)
                put("dmaDeltaBytes", dmaDelta)
                put("deviceLog", remoteLog)
                put("localLog", logLocal?.toString() ?: "")
                put("csv", csv?.toString() ?: "")
            }
            ctx.out.finish("kingkong", true, data, System.currentTimeMillis() - started)
        } else {
            println()
            ctx.out.kv(
                listOf(
                    "轮次" to "$round（重启 $restarts 次）",
                    "注入事件" to "$injected 条",
                    "采样" to "${samples.size} 条 / ${humanDuration((spanSec * 1000).toLong())}",
                    "PSS" to "%s → %s（峰 %s）".format(
                        samples.firstOrNull()?.let { humanBytes(it.pssKb * 1024) } ?: "-",
                        samples.lastOrNull()?.let { humanBytes(it.pssKb * 1024) } ?: "-",
                        humanBytes(peak * 1024),
                    ),
                    "斜率" to "%.2f MB/min".format(slope),
                    "DMA" to humanBytes(dmaDelta),
                    "设备日志" to remoteLog,
                    "本地日志" to (logLocal?.toString() ?: "-"),
                ),
            )
        }
        return ExitCode.OK
    }

    // ---------- helpers ----------

    private fun resolveBinary(explicit: String?, abi: String): Path {
        explicit?.let {
            val p = Paths.get(it)
            if (!Files.isRegularFile(p)) throw OhdError(ExitCode.USAGE, "--binary 不存在：$it")
            return p
        }
        System.getenv("OHD_KINGKONG")?.let {
            val p = Paths.get(it)
            if (Files.isRegularFile(p)) return p
        }
        var dir: Path? = Paths.get("").toAbsolutePath()
        repeat(5) {
            val cand = dir?.resolve("tools/bin/kingkong/$abi/kingkong")
            if (cand != null && Files.isRegularFile(cand)) return cand
            dir = dir?.parent
        }
        throw OhdError(
            ExitCode.USAGE,
            "找不到 $abi 的 kingkong 二进制",
            "归档到 tools/bin/kingkong/$abi/kingkong，或 --binary <路径> / 环境变量 OHD_KINGKONG；" +
                "设备上已有可加 --no-push",
        )
    }

    private fun parseWeights(s: String): Map<String, String> {
        val m = s.split(',').mapNotNull {
            val kv = it.split('=', limit = 2)
            if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
        }.toMap()
        for (k in listOf("t", "r", "a", "w")) {
            if (m[k] == null) throw OhdError(ExitCode.USAGE, "weights 缺 $k=", "形如 t=0.13,r=0.02,a=0.002,w=0.85")
        }
        return m
    }

    private fun parseMs(s: String): Long {
        val v = s.trim().lowercase()
        fun num(u: String) = v.removeSuffix(u).toDoubleOrNull()
            ?: throw OhdError(ExitCode.USAGE, "时间格式不认识：$s", "支持 500ms / 30s / 10m / 8h")
        return when {
            v.endsWith("ms") -> num("ms").toLong()
            v.endsWith("s") -> (num("s") * 1000).toLong()
            v.endsWith("m") -> (num("m") * 60_000).toLong()
            v.endsWith("h") -> (num("h") * 3_600_000).toLong()
            else -> v.toLongOrNull()?.times(1000)
                ?: throw OhdError(ExitCode.USAGE, "时间格式不认识：$s", "支持 500ms / 30s / 10m / 8h")
        }
    }

    private fun humanDuration(ms: Long): String = when {
        ms >= 3_600_000 -> "%.1fh".format(ms / 3_600_000.0)
        ms >= 60_000 -> "%.0fm".format(ms / 60_000.0)
        else -> "%.0fs".format(ms / 1000.0)
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2fGB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1fMB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0fKB".format(b / 1024.0)
        else -> "$b B"
    }
}
