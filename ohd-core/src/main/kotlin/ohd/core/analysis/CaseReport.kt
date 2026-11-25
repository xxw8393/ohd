package ohd.core.analysis

import java.nio.file.Path

/**
 * 证据包 → 缺陷单正文（`report.md`）。
 *
 * [ohd.core.caps.ReportCap] 与 [ohd.core.caps.BundleCap] 的 `--report` 共用此渲染，
 * 保证同一份包不会出现两套数字或两套禁用结论。
 */
object CaseReport {

    fun titleOf(f: CaseFacts.Facts): String {
        val c = f.crash
        val model = f.device?.get("model")?.toString()?.trim('"') ?: "未知机型"
        val os = f.device?.get("softwareVersion")?.toString()?.trim('"')
            ?.split(' ')?.getOrNull(1)?.substringBefore('(') ?: ""
        return if (c == null) {
            "[稳定性][未知] 问题现象待确认（$model）"
        } else {
            "[稳定性][${c.kind}] ${c.module.ifEmpty { "未知模块" }} ${reasonText(c.reason)}（$model${if (os.isEmpty()) "" else " / $os"}）"
        }
    }

    fun reasonText(r: String): String = when {
        r.startsWith("THREAD_BLOCK_") -> "主线程阻塞 ${r.removePrefix("THREAD_BLOCK_").removeSuffix("S")} 秒"
        r == "SIGSEGV" -> "空指针/非法内存访问（SIGSEGV）"
        r == "SIGABRT" -> "主动中止（SIGABRT）"
        r.isEmpty() -> "原因未记录"
        else -> r
    }

    fun render(f: CaseFacts.Facts): String {
        val sb = StringBuilder()
        val c = f.crash
        sb.appendLine("# ${titleOf(f)}")
        sb.appendLine()
        sb.appendLine("> 本单由 `ohd report` 从证据包自动生成，**只含可证伪事实，未判定根因**。")
        val forbiddenCount = CaseFacts.forbiddenConclusions(f.completeness).size
        sb.appendLine(
            "> 证据包：`${f.dir.fileName}`" +
                if (forbiddenCount > 0) "（$forbiddenCount 条禁用结论，见文末）" else "（必备件齐全，无禁用结论）",
        )
        sb.appendLine()

        sb.appendLine("## 摘要")
        if (c == null) {
            sb.appendLine("- 包内没有崩溃原文，本单只能描述现象，不能定位崩溃点。")
        } else {
            val who = c.threadHeaders.firstOrNull()?.let { "主线程（$it）" } ?: "相关线程"
            sb.appendLine("- 进程 **${c.module.ifEmpty { "?" }}**（pid ${c.pid}）在 ${c.timestamp.ifEmpty { "未知时间" }} 出现 **${reasonText(c.reason)}**。")
            sb.appendLine("- 构建：`${c.buildInfo.ifEmpty { "未记录" }}`，ABI `${c.abi.ifEmpty { "?" }}`，指纹 `${c.fingerprint.ifEmpty { "?" }}`。")
            if (f.stack.frames.isNotEmpty()) {
                val top = f.stack.frames.first()
                sb.appendLine("- 栈顶：`${top.so}` + `${top.pc}`${if (top.function.isEmpty()) "" else " → `${top.function}`"}（$who 相关）")
            }
        }
        sb.appendLine()

        sb.appendLine("## 环境")
        sb.appendLine()
        sb.appendLine("| 项 | 值 |")
        sb.appendLine("|---|---|")
        f.device?.entries?.sortedBy { it.key }?.forEach { (k, v) ->
            sb.appendLine("| $k | `${v.toString().trim('"')}` |")
        }
        sb.appendLine()

        sb.appendLine("## 复现步骤")
        sb.appendLine()
        if (f.stepLines.isEmpty()) {
            sb.appendLine("_本包没有记录操作步骤，复现路径需向测试同学索取。_")
        } else {
            f.stepLines.forEachIndexed { i, line -> sb.appendLine("${i + 1}. `${line}`") }
            sb.appendLine()
            sb.appendLine("回放：`bash repro/run.sh`（Windows：`repro\\run.bat`）")
        }
        sb.appendLine()

        sb.appendLine("## 调用栈")
        sb.appendLine()
        if (f.stack.frames.isEmpty()) {
            sb.appendLine("_无可用栈帧。_")
        } else {
            sb.appendLine("| # | so | 地址 | 函数 | 符号来源 |")
            sb.appendLine("|---|---|---|---|---|")
            f.stack.frames.forEach { fr ->
                val src = when {
                    fr.status == "ok" -> "本地调试信息"
                    fr.status.contains("+LOG") -> "日志自带"
                    else -> fr.status
                }
                sb.appendLine("| ${fr.index} | `${fr.so}` | `${fr.pc}` | ${fr.function.ifEmpty { "-" }} | $src |")
            }
            sb.appendLine()
            sb.appendLine(
                "有符号 ${f.stack.symbolized}/${f.stack.frames.size}（本地调试信息 ${f.stack.localOnly}），" +
                    "本地 so 不可用 ${f.stack.missingSo} 帧。",
            )
            if (f.stack.buildIdMismatch) {
                sb.appendLine()
                sb.appendLine("⚠️ 本地 so 的 buildId 与崩溃日志不一致——**当前符号可能属于其它版本**。")
            }
        }
        sb.appendLine()

        val m = f.metrics
        if (m != null) {
            sb.appendLine("## 指标")
            sb.appendLine()
            sb.appendLine("| 项 | 值 |")
            sb.appendLine("|---|---|")
            sb.appendLine("| 采样 | ${m.count} 个 / ${"%.1f".format(m.spanSec)}s |")
            sb.appendLine("| PSS | ${mb(m.pssFirst)} → ${mb(m.pssLast)}（min ${mb(m.pssMin)} / max ${mb(m.pssMax)}） |")
            sb.appendLine("| 斜率 | %+.2f MB/s |".format(m.slopeMbPerSec))
            sb.appendLine("| 走势 | ${m.shape} |")
            sb.appendLine("| CPU 均值 | %.1f%% |".format(m.cpuAvg))
            sb.appendLine()
            sb.appendLine("_走势只是采样窗口内的形状描述，不构成内存泄漏结论。_")
            sb.appendLine()
        }

        if (f.frames.isNotEmpty()) {
            sb.appendLine("## 界面")
            sb.appendLine()
            sb.appendLine("截图 ${f.frames.size} 张：${f.frames.map { "`${it.name}`" }.joinToString("、")}")
            sb.appendLine()
        }

        sb.appendLine("## 证据完备性与结论边界")
        sb.appendLine()
        val summary = f.completeness?.get("summary") as? kotlinx.serialization.json.JsonObject
        val okN = summary?.get("ok")?.toString()?.toIntOrNull()
        val missN = summary?.get("missing")?.toString()?.toIntOrNull()
        if (okN != null && missN != null) {
            sb.appendLine("必备件：**$okN/${okN + missN}** 齐全。")
            sb.appendLine()
        }
        if (f.missing.isEmpty()) {
            sb.appendLine("七类必备件齐全。")
        } else {
            sb.appendLine("**缺失项**（因此以下结论不可下）：")
            sb.appendLine()
            f.missing.forEach { (k, r) ->
                sb.appendLine("- **${CaseFacts.Facts.labelOf(k)}** 缺失 → ${forbiddenOf(k)}")
                if (r.isNotEmpty()) sb.appendLine("  - 原因：$r")
            }
        }
        val allForbidden = CaseFacts.forbiddenConclusions(f.completeness)
        if (allForbidden.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### 本单禁止得出的结论")
            sb.appendLine()
            allForbidden.forEach { sb.appendLine("- $it") }
        }
        sb.appendLine()

        sb.appendLine("## 附件")
        sb.appendLine()
        sb.appendLine("- 证据包目录：`${pathOf(f, f.dir)}`")
        sb.appendLine("- 机器可读索引：`context.json`（含 sha256）")
        sb.appendLine("- 完备性约束：`COMPLETENESS.json`")
        sb.appendLine("- 复现脚本：`repro/steps.ohd`、`repro/run.sh`")
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("由 ohd 生成；本单不包含根因判断。")
        return sb.toString()
    }

    private fun forbiddenOf(key: String): String =
        when (key) {
            "env" -> "不得把结论套用到其它机型或系统版本"
            "frames" -> "无法判断界面表现，也无法核对坐标"
            "logs" -> "无法判断异常发生前的运行轨迹"
            "crash" -> "不得断定崩溃原因（缺 faultlog 原文）"
            "symbols" -> "无法给出函数名与行号，只能给地址与 so 名"
            "metrics" -> "无法判断内存/CPU 趋势，不得下「内存泄漏」结论"
            "repro" -> "无法声称该问题可复现"
            else -> "证据不足"
        }

    private fun pathOf(f: CaseFacts.Facts, p: Path): String {
        val abs = p.toAbsolutePath().toString()
        return if (f.redacted) abs.replace(System.getProperty("user.home"), "<HOME>") else abs
    }

    private fun mb(kb: Long): String = "%.1f MB".format(kb / 1024.0)
}
