package ohd.core

import java.nio.file.Path

/**
 * 设备发现与选择：同时看 hdc（OHOS）与 adb（Android），产出统一的 `Hdc` 客户端。
 *
 * 选择规则（按优先级，任一步命中即止）：
 * 1. `--platform ohos|android` + 该平台只连了一台 → 选它；
 * 2. `--device <key>` → 在两个平台的设备列表里按序列号精确匹配；
 * 3. 只连了一台设备（不分平台）→ 选它；
 * 4. 多台且无 `--device` → 报错并列出全部，让用户明确选（绝不猜）。
 */
object Devices {
    data class Candidate(val kind: DeviceKind, val key: String, val exe: Path)

    /** 两个平台的当前连接设备。工具缺失或命令失败都只是「该平台无设备」，不影响另一个。 */
    fun all(env: Env): List<Candidate> = buildList {
        env.hdc.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.OHOS).targets() }.getOrDefault(emptyList())
                .forEach { add(Candidate(DeviceKind.OHOS, it, exe)) }
        }
        env.adb.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.ANDROID).targets() }.getOrDefault(emptyList())
                .forEach { add(Candidate(DeviceKind.ANDROID, it, exe)) }
        }
        // iOS 只枚举已 Booted 的模拟器：Shutdown 的连 spawn 都跑不了
        env.simctl.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.IOS).targets() }.getOrDefault(emptyList())
                .forEach { add(Candidate(DeviceKind.IOS, it, exe)) }
        }
        // iPhone 真机：devicectl 只返回已配对（paired）的设备
        env.devicectl.path?.let { exe ->
            runCatching { Hdc(exe, kind = DeviceKind.IOS_DEVICE).targets() }.getOrDefault(emptyList())
                .forEach { add(Candidate(DeviceKind.IOS_DEVICE, it, exe)) }
        }
    }

    fun select(env: Env, wantPlatform: DeviceKind?, wantKey: String?): Candidate {
        val all = all(env)

        if (wantKey != null) {
            all.firstOrNull { it.key == wantKey }?.let { return it }
            val hint = when {
                all.isEmpty() -> "当前没有已连接的设备"
                else -> "已连接：" + all.joinToString("，") { "${it.key}(${it.kind.label})" }
            }
            throw OhdError(ExitCode.NO_DEVICE, "未找到设备：$wantKey", hint)
        }

        val pool = if (wantPlatform != null) {
            // `--platform ios` 是一个平台族：模拟器与真机都算，具体用哪台由 key 决定
            all.filter {
                it.kind == wantPlatform ||
                    (wantPlatform == DeviceKind.IOS && it.kind == DeviceKind.IOS_DEVICE)
            }
        } else {
            all
        }

        if (pool.size == 1) return pool.first()

        if (pool.isEmpty()) {
            if (wantPlatform == null) {
                val toolHint = buildString {
                    if (!env.hdc.found) append("hdc 未找到（--hdc 或 OHD_HDC）；")
                    if (!env.adb.found) append("adb 未找到（--adb 或 OHD_ADB）；")
                    if (!env.simctl.found) append("simctl 未找到（OHD_SIMCTL，或未装 Xcode）；")
                    if (isEmpty()) append("两个工具都在，但没有设备连上")
                }
                throw OhdError(ExitCode.NO_DEVICE, "没有已连接的设备", "$toolHint 可用 hdc list targets / adb devices 自查")
            }
            val tool = if (wantPlatform == DeviceKind.ANDROID) env.adb else env.hdc
            if (!tool.found) {
                throw OhdError(
                    ExitCode.NO_DEVICE,
                    "${wantPlatform.bin} 未找到（来源 ${tool.source}）",
                    "用 --${if (wantPlatform == DeviceKind.ANDROID) "adb" else "hdc"} 指定路径，或 ohd config set OHD_${wantPlatform.bin.uppercase()} <路径>",
                )
            }
            throw OhdError(ExitCode.NO_DEVICE, "${wantPlatform.label} 平台没有已连接的设备", "先连设备：${wantPlatform.bin} devices")
        }

        val list = pool.joinToString("，") { "${it.key}(${it.kind.label})" }
        throw OhdError(ExitCode.USAGE, "有多台设备，请用 --device 指定", "已连接：$list")
    }

    /** 直接产出可用的通道客户端。 */
    fun forDevice(ctx: RunContext): Hdc {
        val want = DeviceKind.of(ctx.options.str("platform"))
        val picked = select(ctx.env, want, ctx.options.str("device"))
        return Hdc(picked.exe, picked.key, kind = picked.kind)
    }
}
