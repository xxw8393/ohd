// @ohd Java 版 hilog 封装：抓 N 秒日志并统计 error 行（对照 hlog.sh 的 Java 写法）
// 运行方式：ohd jlog --seconds 3     （JDK 11+ 直接执行 .java 源码，无需先 javac）
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

public class jlog {
    public static void main(String[] args) throws Exception {
        int secs = 5;
        for (int i = 0; i < args.length; i++) {
            if ((args[i].equals("--seconds") || args[i].equals("-s")) && i + 1 < args.length) {
                secs = Integer.parseInt(args[++i]);
            }
        }

        // ohd 注入的环境：回调自身 + 当前设备
        String self = System.getenv().getOrDefault("OHD_SELF", "ohd");
        String device = System.getenv("OHD_DEVICE");
        List<String> base = new ArrayList<>();
        base.add(self);
        if (device != null && !device.isEmpty()) {
            base.add("--device");
            base.add(device);
        }

        System.out.println("▸ 设备 " + (device == null || device.isEmpty() ? "（自动探测）" : device) + "  采集 " + secs + "s");

        String start = run(base, "log", "start");
        String file = null;
        for (String line : start.split("\n")) {
            if (line.contains("输出文件")) {
                String[] parts = line.trim().split("\\s+");
                file = parts[parts.length - 1];
                break;
            }
        }
        if (file == null) {
            System.err.println("✗ log start 失败：\n" + start);
            System.exit(1);
        }

        Thread.sleep(secs * 1000L);
        run(base, "log", "stop");

        long total = 0;
        long errors = 0;
        Map<String, Integer> tags = new TreeMap<>();
        InputStream raw = Files.newInputStream(Path.of(file));
        InputStream in = file.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while (true) {
                // ohd 的 gz 是「多个独立 member 追加」写成的：log stop 杀进程时
                // 若正写到一半，最后一个 member 会残缺。读到 EOF 就停，前面内容仍然有效。
                try {
                    line = r.readLine();
                } catch (java.io.IOException truncatedTail) {
                    break;
                }
                if (line == null) break;
                total++;
                int e = line.indexOf(" E ");
                if (e < 0) continue;
                errors++;
                String rest = line.substring(e + 3);
                int colon = rest.indexOf(':');
                String tag = (colon > 0 ? rest.substring(0, colon) : rest).trim();
                tags.merge(tag, 1, Integer::sum);
            }
        }

        System.out.println("文件    " + file);
        System.out.println("总行数  " + total);
        System.out.println("error   " + errors);
        tags.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(5)
            .forEach(en -> System.out.println("  " + en.getValue() + "  " + en.getKey()));
    }

    private static String run(List<String> base, String... tail) throws Exception {
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(Arrays.asList(tail));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return out;
    }
}
