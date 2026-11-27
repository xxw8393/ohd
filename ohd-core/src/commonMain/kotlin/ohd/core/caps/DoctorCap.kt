package ohd.core.caps

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ohd.core.Capability
import ohd.core.Check
import ohd.core.DeviceManager
import ohd.core.ExitCode
import ohd.core.DeviceKind
import ohd.core.Devices
import ohd.core.actions.WorldMap
import ohd.core.Hdc
import ohd.core.OhdError
import ohd.core.RunContext
import ohd.core.Status
import ohd.core.hasFailure
import ohd.platform.ProcSpec
import ohd.platform.runProcess
import ohd.platform.Env
import ohd.platform.RuntimeInfo

/**
 * 一条命令回答「这台机器现在能不能做真机验证」。
 * 无设备只算 WARN：工具链自检仍应能通过（除非 --require-device）。
 */
class DoctorCap : Capability {
    override val name = "doctor"
    override val summary = "环境自检：hdc / 设备 / uitest / faultlog / llvm / 磁盘"
    override val usage = "ohd doctor [--json] [--device <序列号>] [--require-device]"
    override val example = "ohd doctor --device 63R0226113004249 --require-device --json"
    override val accepts: Set<String> = setOf("device", "require-device")

    override fun run(ctx: RunContext): Int {
        val started = Env.nowMillis()
        val checks = mutableListOf<Check>()
        val requireDevice = ctx.options.bool("require-device")

        // 1) JVM
        val javaVersion = RuntimeInfo.version
        val javaMajor = javaVersion.substringBefore('.').toIntOrNull() ?: 0
        checks += if (javaMajor >= 17) Check.ok("jvm", "$javaVersion (${RuntimeInfo.javaHome})")
        else Check.fail("jvm", "$javaVersion 需 >= 17")

        // 2) 平台
        checks += Check.ok("platform", "${ohd.core.Platform.displayName} / ${ohd.core.Platform.arch}")

        // 3) hdc 定位
        val loc = ctx.env.hdc
        if (!loc.found || loc.path == null) {
            checks += Check.fail("hdc", "未找到 hdc（来源 ${loc.source}）")
            checks += Check.fail("hint", "设置 HDC=/path/to/hdc 或 --hdc，或装 OpenHarmony SDK")
            return finish(ctx, checks, started)
        }
        checks += Check.ok("hdc.path", "${loc.path} (来源 ${loc.source})")

        val hdc = Hdc(loc.path)
        val ver = hdc.version()
        checks += if (ver != null) Check.ok("hdc.version", ver) else Check.warn("hdc.version", "无法获取版本（>= 3.0 才支持 -t 选设备）")

        // 3b) adb（Android 平台；缺失不影响 OHOS）
        val adbLoc = ctx.env.adb
        checks += if (adbLoc.found) {
            Check.ok("adb", "${adbLoc.path} (来源 ${adbLoc.source})")
        } else {
            Check.warn("adb", "未找到 adb（Android 能力不可用；OHOS 不受影响）")
        }

        // 4) 设备
        val targets = try {
            hdc.targets()
        } catch (e: Exception) {
            emptyList()
        }
        if (adbLoc.path != null) {
            val adbTargets = runCatching { Hdc(adbLoc.path, kind = DeviceKind.ANDROID).targets() }.getOrDefault(emptyList())
            checks += if (adbTargets.isEmpty()) Check.warn("adb.device", "无 Android 设备在线")
            else Check.ok("adb.device", adbTargets.joinToString("，"))
        }
        // 4) 设备：两平台合并看，按 --platform / --device 选一台
        val cands = Devices.all(ctx.env)
        if (cands.isEmpty()) {
            checks += Check.warn("device", "无设备在线" + if (requireDevice) "（--require-device 要求必须有）" else "")
            return finish(ctx, checks, started)
        }
        val wantKind = DeviceKind.of(ctx.options.str("platform"))
        val wantKey = ctx.options.str("device")
        val picked = cands.firstOrNull {
            (wantKind == null || it.kind == wantKind) && (wantKey == null || it.key == wantKey)
        }
        if (picked == null) {
            checks += Check.fail(
                "device.select",
                "没有匹配 --platform/--device 的设备；在线：" + cands.joinToString("，") { "${it.key}(${it.kind.label})" },
            )
            return finish(ctx, checks, started)
        }
        checks += Check.ok("device", "${picked.kind.label} ${picked.key}")
        val dev = Hdc(picked.exe, picked.key, kind = picked.kind)

        if (picked.kind == DeviceKind.OHOS) {
            val dm = DeviceManager(hdc)
            val info = dm.info(picked.key)
            checks += Check.ok("device.fingerprint", "model=${info.model} api=${info.apiVersion} sw=${info.softwareVersion} abi=${info.abi}")

            // uitest（节点树/操作 UI 的前提）
            val uitest = dev.shell("ls /system/bin/uitest", 10_000)
            checks += if (uitest.ok && uitest.stdout.isNotBlank()) Check.ok("uitest", "/system/bin/uitest")
            else Check.fail("uitest", "设备无 uitest，tree/tap/replay 不可用")

            // snapshot_display（截图）
            val snap = dev.shell("snapshot_display -h", 10_000)
            checks += if (snap.stdout.contains("snapshot_display") || snap.stdout.contains("-f")) Check.ok("snapshot", "可用")
            else Check.warn("snapshot", "未识别到 snapshot_display（截图能力待确认）")

            // faultlog 通道（崩溃取证的前提）
            val fault = dev.shell("hidumper -s 1201 -a '-h'", 15_000)
            checks += if (fault.stdout.isNotBlank()) Check.ok("faultlog", "hidumper -s 1201 可用")
            else Check.warn("faultlog", "faultlog 通道无输出，崩溃取证可能不可用")

            // llvm-addr2line（符号化）
            val llvm = ctx.env.llvmAddr2line
            if (llvm.found && llvm.path != null) {
                val v = try {
                    // 旧实现 redirectErrorStream(true)：--version 的输出常在 stderr，两条流都要看。
                    val pr = runProcess(ProcSpec(command = listOf(llvm.path.toString(), "--version")))
                    (pr.stdout + pr.stderr).lines().firstOrNull()?.trim()
                } catch (e: Exception) {
                    null
                }
                checks += Check.ok("llvm", "${llvm.path}" + (v?.let { " ($it)" } ?: ""))
            } else {
                checks += Check.warn("llvm", "未找到 llvm-addr2line（sym 符号化不可用）")
            }

            // 设备磁盘
            val df = dev.shell("df -h /data | tail -1", 10_000)
            if (df.ok && df.stdout.isNotBlank()) checks += Check.ok("disk", df.stdout.trim())
        } else {
            val model = dev.shell("getprop ro.product.model", 10_000).stdout.trim()
            val sdk = dev.shell("getprop ro.build.version.sdk", 10_000).stdout.trim()
            val abi = dev.shell("getprop ro.product.cpu.abi", 10_000).stdout.trim()
            checks += Check.ok("device.fingerprint", "model=$model api=$sdk abi=$abi")

            // uiautomator（tree/monkey 的前提）
            val ui = dev.shell("which uiautomator", 10_000)
            checks += if (ui.stdout.isNotBlank()) Check.ok("uiautomator", ui.stdout.trim())
            else Check.fail("uiautomator", "缺失，tree/monkey 不可用")

            // screencap（截图）
            val sc = dev.shell("which screencap", 10_000)
            checks += if (sc.stdout.isNotBlank()) Check.ok("screencap", sc.stdout.trim())
            else Check.warn("screencap", "未找到，shot 可能不可用")

            // crash buffer（崩溃取证）
            val cb = dev.shell("logcat -b crash -d -t 1", 15_000)
            checks += if (cb.ok) Check.ok("logcat.crash", "crash buffer 可读")
            else Check.warn("logcat.crash", "crash buffer 不可读")

            val df = dev.shell("df -h /data | tail -1", 10_000)
            if (df.ok && df.stdout.isNotBlank()) checks += Check.ok("disk", df.stdout.trim())
        }
        // 世界地图体检：膨胀与垃圾数据会直接拖慢每次 go/tree（21MB 单文件一次 encode 就 400ms）
        checks += worldMapChecks()

        return finish(ctx, checks, started)
    }

    /**
     * 世界地图健康检查。
     *
     * 只读、不落盘：读内存态与磁盘体积，不触发 observePage / prune / clean。
     * 判据来自实测：正常形态约 4.4MB / 891 页 / 0 垃圾 item；
     * 膨胀到 21.8MB 时曾有 90% 重复页 + 33% 垃圾 item。
     */
    private fun worldMapChecks(): List<Check> {
        val files = WorldMap.installedMaps()
        if (files.isEmpty()) return listOf(Check.ok("worldmap", "无存档（首次使用后生成）"))
        val out = mutableListOf<Check>()
        for ((bundle, bytes) in files) {
            val h = runCatching { WorldMap.health(bundle) }.getOrNull()
            if (h == null) {
                out += Check.warn("worldmap.$bundle", "无法解析（${bytes / 1024}KB）")
                continue
            }
            val desc = "${h.pages} 页 / ${h.items} item / 盘上 ${h.bytes / 1024}KB（正文 ${h.rawBytes / 1024}KB）"
            val dupPct = if (h.pages > 0) h.duplicatePages * 100 / h.pages else 0
            val bad = h.bloatedBytes || h.bloatedPages || h.junkItems > 0
            out += if (bad) {
                Check.warn(
                    "worldmap.$bundle",
                    "$desc；重复页 ${h.duplicatePages}($dupPct%)、垃圾 item ${h.junkItems} —— " +
                        "跑 `ohd map clean --bundle $bundle` 清洗",
                )
            } else {
                Check.ok("worldmap.$bundle", desc)
            }
        }
        return out
    }

    private fun finish(ctx: RunContext, checks: List<Check>, startedMs: Long): Int {
        val elapsed = Env.nowMillis() - startedMs
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
                    put("warn", checks.count { it.status == Status.WARN })
                    put("fail", checks.count { it.status == Status.FAIL })
                })
            }
            ctx.out.finish("doctor", !checks.hasFailure(), data, elapsed)
        } else {
            ctx.out.table(
                listOf("检查", "结果", "详情"),
                checks.map { listOf(it.name, it.status.name, it.detail) },
            )
            val fails = checks.count { it.status == Status.FAIL }
            val warns = checks.count { it.status == Status.WARN }
            println()
            if (fails > 0) ctx.out.error("$fails 项失败，$warns 项告警 —— 修完再跑真机验证")
            else if (warns > 0) ctx.out.warn("$warns 项告警，核心链路可用")
            else ctx.out.info("全部通过")
        }
        return if (checks.hasFailure()) ExitCode.UNSUPPORTED else ExitCode.OK
    }
}
