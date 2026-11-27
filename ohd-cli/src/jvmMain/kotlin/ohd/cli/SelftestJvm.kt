package ohd.cli

import ohd.core.Check
import ohd.image.defaultImageEngine
import ohd.platform.PngEncode
import ohd.platform.fs
import ohd.platform.fmt

/**
 * AppCDS 自检：确认「启动优化没有悄悄失效」。
 *
 * 为什么必须真跑一次：`-Xshare:auto` 的设计就是**静默降级**——归档映射失败时功能完全正常、
 * 只是慢 20ms，任何「比 mtime」式的间接判断都会漏（归档可以比 jar 新，却因为 classpath
 * 路径对不上而映射失败；实测过：用相对路径生成归档，换个 cwd 启动就挂）。
 * 所以这里用一个子进程以 `-Xshare:on`（映射失败即启动报错）真实启动一次，并把 cwd 设到
 * 包外用绝对 classpath——顺带验证归档没有被当前目录绑死。
 *
 * 判定为 WARN 而非 FAIL：CDS 只影响启动速度，不影响任何能力。
 * 只找 java 用 System.getProperty("java.home")，不碰 java.management（jlink 精简运行时没有该模块）。
 */
internal actual fun cdsCheck(): Check {
    // 归档位置只能靠「jar 在哪」反推：classpath 由 dist/ohd 注入成 "$LIB/*"，
    // 取任一 jar 的父目录（= lib/）再往上看一层就是包根（打包形态）或 dist/（开发形态）。
    val cpEntries = System.getProperty("java.class.path")
        ?.split(ohd.platform.Env.pathSeparator)
        ?.mapNotNull { runCatching { java.nio.file.Paths.get(it) }.getOrNull() }
        .orEmpty()
    val libDir = cpEntries
        .mapNotNull { it.parent }
        .firstOrNull { dir ->
            java.nio.file.Files.isDirectory(dir) &&
                runCatching {
                    java.nio.file.Files.list(dir).use { s -> s.anyMatch { p -> p.toString().endsWith(".jar") } }
                }.getOrDefault(false)
        } ?: return Check.warn("cds", "定位不到 jar 目录，无法校验归档（不影响功能）")

    // 打包形态：<root>/lib + <root>/ohd.jsa；开发形态：<repo>/ohd-cli/build/install/ohd/lib + <repo>/dist/ohd.jsa
    // 开发形态的层数：lib → ohd → install → build → ohd-cli → <repo>，共 5 层。
    val archive = listOf(
        libDir.resolveSibling("ohd.jsa"),                       // 打包形态
        libDir.resolve("../../../../../dist/ohd.jsa").normalize(),   // 开发形态
        libDir.resolve("../../../../../build/cds/ohd.jsa").normalize(),
    ).firstOrNull { java.nio.file.Files.isRegularFile(it) }
        ?: return Check.warn("cds", "无 AppCDS 归档：启动约 62ms（跑 dist/cds.sh 可降到 ~40ms）")

    // 快速失败：归档比 jar 旧必然索引对不上，先说清原因，省掉一次子进程启动。
    val archiveTime = runCatching {
        java.nio.file.Files.getLastModifiedTime(archive).toMillis()
    }.getOrDefault(0L)
    if (archiveTime == 0L) return Check.warn("cds", "归档不可读：$archive")
    val newestJar = runCatching {
        java.nio.file.Files.list(libDir).use { s ->
            s.filter { it.toString().endsWith(".jar") }
                .map { runCatching { java.nio.file.Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .max(Comparator.naturalOrder()).orElse(0L)
        }
    }.getOrDefault(0L)
    if (newestJar > archiveTime) {
        return Check.warn("cds", "归档比 jar 旧：重跑 dist/cds.sh（当前自动降级为无 CDS 启动）")
    }

    // 真探针：-Xshare:on 下映射失败会让 JVM 初始化直接失败（退出码非 0）。
    // cwd 刻意设到临时目录：classpath 与归档都用绝对路径，能跑通即证明归档没被 cwd 绑死。
    val javaBin = java.io.File(System.getProperty("java.home"), "bin/java").absolutePath
    val (exit, output) = runCatching {
        val proc = ProcessBuilder(
            javaBin,
            "-Xshare:on",
            "-XX:SharedArchiveFile=${archive.toAbsolutePath()}",
            "-cp", "$libDir/*",
            "ohd.cli.MainKt", "--version",
        ).redirectErrorStream(true)
            .directory(java.io.File(System.getProperty("java.io.tmpdir")))
            .start()
        proc.outputStream.close()
        val text = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return Check.warn("cds", "探针超时：归档可能不可用（不影响功能）")
        }
        proc.exitValue() to text
    }.getOrElse { e ->
        return Check.warn("cds", "探针启动失败：${e.message ?: e::class.simpleName}（不影响功能）")
    }

    return when {
        exit == 0 -> Check.ok("cds", "AppCDS 归档可用（${archive.fileName}，起点约 40ms）")
        output.contains("shared archive") || output.contains("shared spaces") ->
            Check.warn("cds", "归档无法映射（$archive 已失效）：重跑 dist/cds.sh")
        else -> Check.warn("cds", "探针异常退出（exit=$exit）：$archive")
    }
}

/** 图像引擎自检：生成一张小图 → 载入 → 自比较必须为 0 差分。 */
internal actual fun imageEngineCheck(): Check = try {
    val tmp = fs.createTempFile("ohd-selftest-", ".png")
    // 用平台的 PngEncode 造图（不再依赖 AWT）：32x32 棋盘格便于验证解码正确性
    val w = 32
    val h = 32
    val pixels = ByteArray(w * h * 4)
    for (y in 0 until h) for (x in 0 until w) {
        val on = ((x / 4 + y / 4) % 2 == 0)
        val o = (y * w + x) * 4
        val v = if (on) 255.toByte() else 0
        pixels[o] = v; pixels[o + 1] = v; pixels[o + 2] = v; pixels[o + 3] = 255.toByte()
    }
    val png = PngEncode.encodeRgba(w, h, pixels)
        ?: return Check.fail("image", "PNG 编码失败")
    fs.writeBytes(tmp, png)
    val engine = defaultImageEngine()
    val g = engine.load(tmp)
    val d = engine.diff(g, g)
    fs.deleteIfExists(tmp)
    if (d.maxAbs == 0 && d.meanAbs == 0.0) {
        Check.ok("image", "engine=${engine.name} 解码/差分正常（HF=${fmt("%.2f", engine.highFrequencyEnergy(g))}）")
    } else {
        Check.fail("image", "自比较差分非 0：max=${d.maxAbs} mean=${d.meanAbs}")
    }
} catch (e: Exception) {
    Check.fail("image", "图像引擎异常：${e.message}")
}
