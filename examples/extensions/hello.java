// @ohd Java 扩展示例：打印 ohd 注入的环境，并回调 ohd 自身取版本
// 运行方式：ohd hello            （JDK 11+ 直接跑 .java 源码，无需先编译）
public class hello {
    public static void main(String[] args) throws Exception {
        String[][] keys = {
            {"OHD_SELF", "启动入口"}, {"OHD_HDC", "hdc 路径"}, {"OHD_DEVICE", "设备"},
            {"OHD_SESSION_ROOT", "会话根"}, {"OHD_JSON", "JSON 模式"}, {"OHD_EXT", "扩展目录"},
        };
        for (String[] k : keys) {
            System.out.println(k[0] + " = " + System.getenv(k[0]));
        }
        System.out.println("args = " + java.util.Arrays.toString(args));

        String self = System.getenv("OHD_SELF");
        if (self != null && !self.isEmpty()) {
            Process p = new ProcessBuilder(self, "--version").inheritIO().start();
            System.exit(p.waitFor());
        }
    }
}
