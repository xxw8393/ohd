package ohd.platform

/**
 * 跨平台测试命令构造。
 *
 * 这些用例验证的是「[spawnDetached] / [spawnStreaming] 能不能正确驱动一个**外部进程**」，
 * 而 `commonTest` 在 JVM 与 macosArm64 两个 target 上跑，CI 的 windows runner 同样执行
 * jvm target。原先直接写死 `/bin/sh`、`/bin/sleep`、`seq`、`awk`，等于让 Windows 必然变红——
 * 那些能力在 Windows 上是产品要支持的（Windows 走 JVM 入口出货），不能靠「平台不支持」搪塞。
 *
 * ## Windows 为什么不用 PowerShell
 *
 * 试过两版都不行，原因是 PowerShell 的输出管线不可控：
 * 1) `Write-Output` / 管道表达式会由 PowerShell 自己再补一个换行，我显式加的 `[char]10`
 *    就变成双换行，行数与顺序断言全错；
 * 2) 改用 `[Console]::Out.Write` 绕开管道，仍受控制台代码页与重定向编码影响。
 *
 * 因此 Windows 侧直接用 **JVM 自己**当被测子进程：写一份单文件 Java 源码（JDK 11+ 的
 * source-file 模式），内部用 `FileOutputStream(FileDescriptor.out)` 精确写字节。
 * 既不经过 shell，也不依赖任何 Windows 命令或编码设定，输出逐字节可预期。
 * POSIX 侧仍用系统自带命令（成本更低，也顺带覆盖真实外部进程）。
 */
internal object TestCommand {

    private val windows = Env.isWindows

    /** 长跑进程：用来验「起得来 + 拿到 pid + 能 kill」。 */
    fun sleepSeconds(sec: Int): List<String> =
        if (windows) probe("sleep", "$sec")
        else listOf("/bin/sleep", "$sec")

    /** 输出一行 [text]。 */
    fun echoLine(text: String): List<String> =
        if (windows) probe("echo", text)
        else listOf("/bin/echo", text)

    /** 依次输出 1..[n]，每个一行。 */
    fun sequence(n: Int): List<String> =
        if (windows) probe("seq", "$n")
        else listOf("/bin/sh", "-c", "seq 1 $n")

    /** 输出一行长度为 [len] 的 'x'，用于验跨缓冲边界的拼接。 */
    fun longLine(len: Int): List<String> =
        if (windows) probe("long", "$len")
        else listOf("/bin/sh", "-c", "awk 'BEGIN{ s=\"\"; for(i=0;i<$len;i++) s=s \"x\"; print s }'")

    /** 输出 [text]，不带结尾换行：验退出时要 flush 最后一行。 */
    fun noNewlineTail(text: String): List<String> =
        if (windows) probe("tail", text)
        else listOf("/bin/sh", "-c", "printf '$text'")

    /** 一行 stdout + 一行 stderr：验 stderr 并入同一流。 */
    fun stdoutAndStderr(out: String, err: String): List<String> =
        if (windows) probe("both", out, err)
        else listOf("/bin/sh", "-c", "echo $out; echo $err 1>&2")

    /** 正文与空行交错：验空行被跳过。 */
    fun blankLines(): List<String> =
        if (windows) probe("blank")
        else listOf("/bin/sh", "-c", "printf 'a\\n\\n\\nb\\n'")

    /** 一个**确定不存在**的二进制路径：验 exec 失败必须报错，不能给假 pid。 */
    fun missingBinary(): String =
        if (windows) "C:\\nonexistent\\definitely-not-here-ohd-test.exe"
        else "/nonexistent/definitely-not-here-ohd-test"

    // ---- Windows：JVM 单文件源码探针 ----

    /**
     * 探针源码目录只建一次（JVM 单文件源码模式每次启动都要现场编译，但目录不必重建）。
     */
    private val probeSource: java.nio.file.Path by lazy {
        val dir = java.nio.file.Files.createTempDirectory("ohd-probe")
        dir.toFile().deleteOnExit()
        val src = dir.resolve("OhdTestProbe.java")
        java.nio.file.Files.writeString(src, PROBE_SRC)
        src.toFile().deleteOnExit()
        src
    }

    private fun probe(mode: String, vararg args: String): List<String> {
        val javaBin = java.nio.file.Paths
            .get(System.getProperty("java.home"), "bin", "java.exe")
            .toString()
        return listOf(javaBin, probeSource.toString(), mode) + args
    }

    private val PROBE_SRC = """
        import java.io.FileDescriptor;
        import java.io.FileOutputStream;
        import java.io.PrintStream;

        public class OhdTestProbe {
            public static void main(String[] a) throws Exception {
                PrintStream out = new PrintStream(
                        new FileOutputStream(FileDescriptor.out), true, "UTF-8");
                String mode = a[0];
                if ("sleep".equals(mode)) {
                    Thread.sleep(Long.parseLong(a[1]) * 1000L);
                } else if ("echo".equals(mode)) {
                    out.print(a[1] + "\n");
                } else if ("seq".equals(mode)) {
                    int n = Integer.parseInt(a[1]);
                    for (int i = 1; i <= n; i++) out.print(i + "\n");
                } else if ("long".equals(mode)) {
                    int len = Integer.parseInt(a[1]);
                    StringBuilder sb = new StringBuilder(len);
                    for (int i = 0; i < len; i++) sb.append('x');
                    out.print(sb + "\n");
                } else if ("tail".equals(mode)) {
                    out.print(a[1]);
                } else if ("both".equals(mode)) {
                    out.print(a[1] + "\n");
                    System.err.print(a[2] + "\n");
                    System.err.flush();
                } else if ("blank".equals(mode)) {
                    out.print("a\n\n\nb\n");
                }
                out.flush();
            }
        }
    """.trimIndent()
}
