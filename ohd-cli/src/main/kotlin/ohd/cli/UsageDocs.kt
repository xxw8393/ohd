package ohd.cli

import ohd.core.Capability

/**
 * `ohd <capability> --help` 的补充说明层。
 *
 * 能力类里只放 `usage` 骨架和一条 example（够内部用）；「每个选项什么意思」「新手照抄哪条」
 * 属于文档，散进 29 个能力文件后没人维护，所以集中在这里：
 * - [optionDocs]：选项名 → 一句话（未收录的选项仍会列出来，只是没有解释）；
 * - [examples]：能力名 → 若干 (命令, 用途)，第一条一律是最小可用命令。
 *
 * 新增能力时补这两处数据即可，[render] 不需要改。
 */
internal object UsageDocs {

    /** 选项排序权重：命中越靠前，越可能是读者第一步要用的开关。 */
    private val PREFERRED = listOf(
        "bundle", "pid", "device", "duration", "interval", "top", "out", "json",
        "tag", "exclude", "match", "session", "since", "limit", "keep-going", "seed",
    )

    private val optionDocs: Map<String, String> = mapOf(
        // 目标与设备
        "bundle" to "目标应用包名，用它定位进程（<包名>，如 com.example.app）",
        "pid" to "直接给进程号（<N>），跳过按包名查找",
        "device" to "多台设备在线时指定序列号（<serial>）",
        "ability" to "应用入口 Ability 名，缺省用包内默认入口",
        "package" to "应用包名（path 用它把日志里的页面事件归到应用）",
        "a" to "A 版 hap 路径（ab；改前那一版）",
        "b" to "B 版 hap 路径（ab；改后那一版，必须与 A 同包名）",
        // 时长与节奏
        "duration" to "运行时长（<10m|2h|10h>）；到了自动收尾，不会卡住",
        "fps" to "产物帧率（<4>）：OHOS 为抓帧频率（并发逼近，15s 实测 13.9），Android/iOS 仅转 gif/webp 时用于抽帧",
        "interval" to "采样/事件间隔（<400ms|1s|10s>）；0 表示不等待，压满",
        "count" to "固定采样次数（<N>），与 --duration 二选一",
        "every" to "A/B 交替窗口里的采样间隔（<5s>）",
        "check-every" to "每 N 轮检查一次崩溃/新故障（<8>）",
        "sample-every" to "每注入 N 个事件采一次内存（<20>）",
        "sample-period" to "采样周期（<60s>）：kingkong 用时间而非事件数计频",
        "stagnate" to "连续多少秒没有新界面就判定卡住（<60>）",
        "layout-ttl" to "节点树缓存有效期（<5> 轮），过期重新 dump",
        "batch" to "每轮连续注入几个事件再刷新节点树（<1>）",
        "max-steps" to "事件数上限（<N>）；0 表示不设上限，只受 --duration 约束",
        "seed" to "随机种子（<N>），固定后事件序列可复现",
        "until-stable" to "连续 N 次采样波动小于 --stable-eps 就提前结束（<5>）",
        "stable-eps" to "判定稳定的波动阈值（KB，<1024>）",
        "metrics" to "观察哪些指标（<pss,graph,gl,cpu>，逗号分隔）",
        // 观测范围
        "top" to "只报前 N 项（<15>），按数值降序",
        "limit" to "结果条数上限（<N>）",
        "since" to "时间窗（<30m|2h|0|2026-10-22T10:00>）；0 表示不限",
        "tail" to "只看日志尾部 N 行（<N>）",
        "depth" to "节点树遍历深度（<N>）",
        "all" to "全部对象：不做默认过滤（日志类）或处理所有设备（disconnect）",
        "grep" to "按文本模糊匹配节点（<text>，含中文）",
        "type" to "按节点类型匹配（<Button|Text|...>）",
        "id" to "按节点 id 精确匹配（<ID>）",
        "clickable" to "只保留可点击节点（直接给出可点坐标）",
        "rich" to "附带更多节点属性（原始属性表，排查用）",
        "display" to "多屏时指定显示器序号（<N>）",
        // 截图与对比
        "grid" to "叠加网格，线间距 N 像素（<100>），目视读坐标",
        "cross" to "在 x,y 画十字（<640,1200>），确认点击点",
        "boxes" to "把节点 bounds 画成框，看布局是否错位",
        "baseline" to "基线图路径（<a.png>），diff 的对比基准",
        "current" to "当前图路径（<b.png>）；不给则用最近一次会话截图",
        "threshold" to "单像素差异阈值 0-255（<8>），超过算「变了」",
        "tile" to "分块边长（<64>），按块统计差异，抗整图抖动",
        "allow-ratio" to "允许变化的像素比例（<0.01>），超了才 exit 7",
        "max-changed" to "变化块占比上限，超了判不通过",
        // 日志
        "tag" to "只抓这些标签（<a,b>，逗号分隔；monkey/log/monitor 通用）",
        "match" to "只保留匹配该正则的行（RE）",
        "exclude" to "丢掉匹配该正则的行（RE），长跑必备（如 (?i)vsync）",
        "filter" to "按标签二次过滤（<TAG>）",
        "full" to "不截断长行/不省略字段（排障时用）",
        "max-size" to "落盘上限（<2G|4G|0>），超了滚动；0 表示不限",
        "hilog" to "抓 hilog（默认开）",
        "no-hilog" to "不抓日志，只做采样/注入",
        "clear" to "先清空设备侧缓冲再开始",
        "file" to "指定读写文件（<路径>）",
        // 动作
        "double" to "双击",
        "long" to "长按",
        "script" to "脚本文件路径（<文件>）；也可用位置参数直接给",
        "step-delay" to "每步之间的等待（<300ms>）",
        "wait-timeout" to "单步等待超时（<5s>）",
        "dry-run" to "只解析脚本、打印将要执行的动作，不真点",
        "process" to "兼容旧写法：kill 默认就关进程，此项可省；给 disconnect 加上则等价于 kill",
        "no-compress" to "hilog 产物不压缩（默认 gzip；等价 --log-compress none）",
        "open" to "回放已录制的日志：<id> / latest / 文件路径",
        "tcp-only" to "只处理 TCP/模拟器，跳过 USB 直连设备（USB 断不了，只能拔线）",
        "record" to "replay 时每步截一张，最后压成 replay.gif（慢，脚本别太长）",
        "keep-going" to "出错继续（崩溃/失败不中断长跑）",
        "keep-a11y" to "OHOS：已默认开启；显式写出亦可（与默认相同）",
        "no-keep-a11y" to "OHOS：关闭 uitest+accessibility 保活（默认开，对齐 DevEco Testing）",
        "a11y-ping-every" to "无障碍保活开启时：每 N 步 dumpLayout 保活（默认 <10>）",
        "home-settle" to "结束后回首页并静置（默认 <30s>；0 关闭）",
        "no-home-settle" to "关闭收尾回首页静置",
        "home-text" to "首页判定文本（逗号分隔子串，命中即停 Back）",
        "keep-data" to "覆盖安装但保留应用数据（排查「升级后」问题用）",
        "drive" to "用脚本驱动操作（<replay.ohd>），否则只观测不下手",
        "no-stay-in-app" to "允许事件把应用切到后台（默认想办法留住前台）",
        "no-dma" to "不采 DMA buffer（省时间，但少一类指标）",
        "no-archive" to "不落盘产物，只在终端看结果",
        // 崩溃与符号化
        "crash" to "故障原文文件（<faultlog.txt>），也可 --latest 直接取最新",
        "latest" to "不给 --crash：取设备上最新一条故障（sym 的显式写法）",
        "so-dir" to "so 所在目录（<a:b> 多个用冒号分隔），符号化必需",
        "hap" to "从 hap 里取 so（<app.hap>），省得手撕包",
        "no-auto-so" to "不使用自动查到的 so，只用 --so-dir 指定的",
        "allow-mismatch" to "buildId 不匹配时仍输出符号（结果可能错位，仅救急）",
        "list" to "列出而不是解析（如列故障文件）",
        "details" to "输出解析后的 detail 字段（reason/栈摘要）",
        "steps" to "复现步骤文件（<file>），缺省从证据包读",
        "settle" to "每步操作后等界面稳定（<2s>），再判下一步",
        // 证据包
        "redact" to "脱敏：序列号/IMEI/包路径等替换成占位",
        "report" to "同时生成 report.md 缺陷单",
        "zip" to "打包成单个 zip，便于发给别人",
        "latest-only" to "只打最新一个会话（默认按 --since 合并近窗）",
        "out" to "输出路径（<目录|文件>）",
        "name" to "证据包名字（<名字>），缺省按时间戳",
        "session" to "指定会话目录（<dir,a,b> 多个用逗号）",
        // 杂项
        "min-kb" to "DMA 增量只报变化超过 N KB 的项（<4>）",
        "show-dmabuf" to "mem 里附带 DMA buffer 明细",
        "cpufreq" to "cpu 里附带逐核当前/最大频率（判断是否被限频）",
        "max" to "节点数输出上限（tree，默认 400）",
        "algo" to "路径骨架算法（<stack|first>）",
        "map" to "页面映射表（<pagemap>），把事件翻成页面名",
        "coords" to "坐标表（<coords.txt>），给 path 生成的骨架补坐标",
        "require-device" to "没有设备就直接失败（CI / 脚本里用，别静默跳过）",
        "platform-audit" to "审计平台隔离：扫共享源集里的厂商符号",
        "soak" to "压测时长（<2m>），A/B 各自跑这么久",
        "json" to "输出结构化 JSON（agent 消费；终端表格是给人看的）",
    )

    private val capExamples: Map<String, List<Pair<String, String>>> = mapOf(
        "doctor" to listOf(
            "ohd doctor" to "看设备、hdc、uitest、faultlog、llvm、磁盘都齐不齐",
            "ohd doctor --require-device" to "脚本/CI 里用：没设备直接失败，不静默继续",
        ),
        "selftest" to listOf(
            "ohd selftest" to "不碰设备的离线自检，先证明这台机器能跑",
            "ohd selftest --platform-audit" to "顺带审计平台隔离是否有新增越界符号",
        ),
        "config" to listOf(
            "ohd config list" to "看当前生效值和来源（CLI / 配置文件 / 环境变量）",
            "ohd config set OHD_HDC /path/to/hdc" to "把 hdc 路径存下来，省掉每次敲 --hdc",
            "ohd config keys" to "支持哪些键",
            "eval \"\$(ohd config env)\"" to "把配置导成环境变量给子进程用",
            "ohd config devices" to "列在线设备 + 当前默认设备（✔ 标出）",
            "ohd config devices FMR0225A,2B0913F" to "固定默认：之后不必每条命令加 --device；多台按配置顺序并行",
        ),
        "env-dump" to listOf(
            "ohd env-dump" to "设备指纹：型号/API/版本/ABI/内存/存储/时区/前台应用",
            "ohd env-dump --no-archive --json" to "只在终端要结果，不落盘",
        ),
        "install" to listOf(
            "ohd install build/app-signed.hap" to "覆盖安装（默认不保留数据，等同替换）",
            "ohd install app-signed.hap --keep-data" to "保留应用数据升级：查「升级后」问题必须加",
        ),
        "ab" to listOf(
            "ohd ab --a old.hap --b new.hap --bundle com.example.app" to "最小可用：A 跑 2m → B 跑 2m，同 seed 同节奏比 PSS/DMA/崩溃",
            "ohd ab --a old.hap --b new.hap --bundle com.example.app --soak 5m --every 2s --seed 42" to "拉长阶段、加密采样、固定点击序列，结论更稳",
            "ohd ab --a v1.hap --b v2.hap --bundle com.example.app --json" to "给流水线：结构化差异",
        ),
        "tree" to listOf(
            "ohd tree" to "全量节点 + bounds + 点击中心点",
            "ohd tree --grep 设置 --clickable" to "按文本找可点节点，直接拿坐标去 tap",
            "ohd tree --id btn_ok --rich --json" to "按 id 精确定位并看原始属性",
        ),
        "shot" to listOf(
            "ohd shot" to "截一张图存进会话目录",
            "ohd shot --grid 100" to "叠 100px 网格，目视读坐标",
            "ohd shot --cross 640,1200 --boxes" to "确认点击点落哪、节点框有没有错位",
        ),
        "video" to listOf(
            "ohd video --duration 30s（Ctrl+C 提前结束）" to "录制中 Ctrl+C 不丢产物：已录部分照常保存并标注 INTERRUPTED",
            "ohd video --duration 15s --fps 15 --out /tmp/demo.gif" to "三端同一条命令：产物格式看 --out 后缀（mp4/gif）",
            "ohd video --duration 30s --out /tmp/demo.mp4" to "要 mp4 就给 .mp4，平台不能原生出的用 ffmpeg 转",
            "ohd video --duration 20s --fps 15 --out /tmp/demo.webp" to "动画 WebP 比 gif 小得多（实测 199 帧 8.3MB → 94KB），需 libwebp 的 gif2webp",
            "ohd video --duration 10s" to "不给 --out 时用平台原生格式（Android/iOS=mp4、OHOS=gif）",
        ),
        "tap" to listOf(
            "ohd tap 640 1200" to "单击坐标（仅当轮 tree/shot 已确认；优先用 go）",
            "ohd tap 640 1200 --long" to "长按",
            "ohd tap 640 1200 --double" to "双击",
            "ohd tap --grep 多实例测试 --expect 返回" to "等价 ohd go：认路点击+验标",
        ),
        "go" to listOf(
            "ohd go --grep 多实例测试 --expect 返回 --bundle com.tencent.compose" to "亮屏+前台；写入世界地图边；失败 exit 4",
            "ohd go --map-first --grep 多实例测试 --expect 关闭A --light" to "用地图坐标点，跳过点前 dump；必须 --expect",
            "ohd go --grep 1500 Text --expect 'Compose1500Text Item' --light" to "轻量验标：最多 2 次 dump，确认页首元素即过",
            "ohd go --grep GPU Texture --scroll-find --scene home" to "滑找学习 atlas+地图 item 滑次；下次批量滑",
            "ohd go --grep progressive --index 0 --fresh" to "多命中须 --index；--fresh 只清 LayoutCache（地图按页更新坐标）",
        ),
        "back" to listOf(
            "ohd back --expect '1500 Text' --light --bundle com.tencent.compose" to "系统 Back + 验标（弹栈/地图）；light≤2 dump",
            "ohd back --expect 返回" to "完整轮询验标",
        ),
        "map" to listOf(
            "ohd map show --bundle com.tencent.compose" to "看已跑过的页/边/item 滑次与 crash 禁令（~/.ohd/worldmap/）",
            "ohd map path --to \"GPU Texture\"" to "问路：Back×N + 滑×M/像素 + tap（跳过已标 crash 边）",
            "ohd map export --out ohd-evidence/worldmap.json" to "存档地图给用例/排障",
            "ohd map clear-crashes --bundle com.tencent.compose" to "只清 crash 禁令，保留页/边",
            "ohd map go --to \"GPU Texture\" --dry-run" to "按地图计划导航（或只打印步骤）",
        ),
        "wait" to listOf(
            "ohd wait --grep 返回 --timeout 2s" to "短轮询等地标（缓存优先）；替代 sleep",
            "ohd wait --grep 'Compose1500Text Item' --light" to "轻量：最多 2 次 dump，高速环用",
            "ohd wait --grep 加载中 --absent --timeout 3s" to "等到文案消失（消失必须 dump 确认）",
        ),
        "input" to listOf(
            "ohd input text 你好" to "在已聚焦的输入框里输入文本",
            "ohd input text 640 1200 hello" to "先点坐标再输入",
            "ohd input swipe 640 2000 640 800 500" to "从下往上滑（速度 500）",
            "ohd input key Back" to "按键：Back / Home / Power / 键值（有验标需求请用 ohd back）",
        ),
        "record" to listOf(
            "ohd record --bundle com.example.app" to "录一段真机操作，落成回放脚本",
            "ohd record read --bundle com.example.app" to "读取已录好的脚本（不重新录）",
        ),
        "replay" to listOf(
            "ohd replay ohd-evidence/record/record-xxx.ohd" to "回放脚本，每步记录结果",
            "ohd replay run.ohd --dry-run" to "先看会点什么，不真执行",
            "ohd replay run.ohd --step-delay 200ms --keep-going" to "步进慢一点，某步失败也不中断",
        ),
        "diff" to listOf(
            "ohd diff --baseline a.png --current b.png" to "两张截图比差异，给指标 + 热力图",
            "ohd diff --baseline a.png --current b.png --threshold 8 --allow-ratio 0.01" to "放宽到允许 1% 像素变化，超了 exit 7",
        ),
        "crash" to listOf(
            "ohd crash --list" to "列设备上的故障文件",
            "ohd crash --details --limit 20" to "解析最近 20 条：reason + 栈摘要",
            "ohd crash --name faultlog.xxx.txt" to "只看指定那一条",
        ),
        "sym" to listOf(
            "ohd sym --crash faultlog.txt --so-dir /path/libs" to "栈符号化，buildId 校验不过就拒绝给符号",
            "ohd sym --latest --so-dir /path/libs" to "直接拿最新崩溃做符号化",
            "ohd sym --crash f.txt --so-dir libs --allow-mismatch" to "buildId 不匹配仍出符号（结果可能错位，救急）",
        ),
        "log" to listOf(
            "ohd log" to "录制：终端实时看 + 同时压缩落盘，Ctrl-C 结束",
            "ohd log --tag Konan_main --exclude '(?i)vsync'" to "录制时只留关心的行",
            "ohd log ls" to "看录过哪些（id / 时间 / 大小）",
            "ohd log --open latest" to "回放最近一次录制到终端",
            "ohd log --open <id> --match RE --tail 200" to "回放并过滤，只看尾部",
            "ohd log start --max-size 4G" to "后台录制（命令立刻返回，配 stop 收尾）",
        ),
        "mem" to listOf(
            "ohd mem --bundle com.example.app" to "Pss / SwapPss / GL / Graph / native heap 明细",
            "ohd mem --bundle com.example.app --show-dmabuf --top 15" to "连带 DMA buffer，只看前 15 项",
            "ohd mem --pid 1234 --json" to "已知进程号时直接查",
        ),
        "cpu" to listOf(
            "ohd cpu" to "总占用 + 进程 Top N",
            "ohd cpu --bundle com.example.app --cpufreq --top 10" to "顺带看逐核当前/最大频率（判断是不是被限频）",
        ),
        "watch" to listOf(
            "ohd watch --bundle com.example.app" to "持续采 Pss/CPU，给斜率、阶梯、平台期",
            "ohd watch --bundle com.example.app --interval 1s --duration 120s --until-stable 5" to "密集采样到连续 5 次平稳就停",
            "ohd watch --bundle com.example.app --metrics pss,gl,cpu" to "只看这三类指标",
        ),
        "devices" to listOf(
            "ohd devices" to "看清单：设备、平台、连接类型、哪台是默认（不动手）",
            "ohd devices use 63R0226113004249" to "设为默认设备，之后命令不用再带 --device",
            "ohd devices connect 127.0.0.1:5555" to "连模拟器/远程机（不确定平台就两边都试）",
            "ohd devices kill --all --tcp-only" to "关掉所有模拟器（iOS shutdown / Android emu kill），USB 真机不动",
            "ohd devices kill --device emulator-5554" to "关掉那台漏关的 Android 模拟器",
            "ohd devices disconnect --all --tcp-only" to "只摘 TCP 连接，不关模拟器进程",
        ),
        "dmabuf" to listOf(
            "ohd dmabuf snap --bundle com.example.app --top 10" to "给当前 DMA 快照，按标签分组",
            "ohd dmabuf snap --bundle com.example.app --out /tmp/dma.json" to "快照落文件，便于前后对比",
            "ohd dmabuf diff /tmp/old.json /tmp/new.json --min-kb 4" to "增量：只报变化超过 4KB 的项",
        ),
        "monkey" to listOf(
            "ohd monkey --bundle com.example.app" to "最小可用：默认读+写世界地图导航；--no-worldmap 关",
            "ohd monkey --bundle com.example.app --duration 30m --keep-going --tag Konan_main" to "半小时压测：崩了继续，只抓应用日志",
            "ohd monkey --bundle com.tencent.compose --duration 30m --keep-going --tag ComposeA11y" to "OHOS 默认 keep-a11y：uitest 保活无障碍，压 a11y+layout 热路径",
            "ohd monkey --bundle com.tencent.compose --duration 10m --no-keep-a11y" to "显式关掉无障碍保活（对照/排噪）",
            "ohd monkey --bundle com.example.app --seed 42 --max-steps 2000" to "固定种子 + 限步数，崩溃可稳定复现",
            "ohd monkey --bundle com.example.app --interval 0 --batch 10 --sample-every 20" to "压满事件、每 20 步采一次内存",
            "ohd monkey --bundle com.example.app --duration 10m --home-settle 30s" to "结束后 Back 回首页并静置 30s（默认即如此）",
        ),
        "monitor" to listOf(
            "ohd monitor --bundle com.example.app --duration 1h --interval 10s" to "旁路观测：抓日志 + 采样内存/CPU/DMA，一个事件都不注入",
        ),
        "soak" to listOf(
            "ohd soak --bundle com.example.app --duration 10h --interval 60s" to "长稳值守：周期采样，得趋势和 csv",
            "ohd soak --bundle com.example.app --duration 10h --drive replay.ohd" to "自己按脚本驱动操作，边跑边采",
        ),
        "bundle" to listOf(
            "ohd bundle" to "默认合并近 2h 会话成证据包",
            "ohd bundle --redact --report --zip" to "交研发推荐组合：脱敏 + 缺陷单 + 压缩",
            "ohd bundle --latest-only --since 30m" to "只要最新一个 / 只取近 30 分钟",
        ),
        "analyze" to listOf(
            "ohd analyze" to "对最新证据包给事实清单（崩溃/栈/指标/完备性）",
            "ohd analyze ohd-evidence/cases/xxx --top 20 --json" to "指定包，机器可读",
        ),
        "triage" to listOf(
            "ohd triage /path/to/automation-captures/YYYYMMDD-HHMMSS" to "REPORT/anomalies → 调查赛道（pixelmap/xcomponent/native-heap…）",
            "ohd triage CAPTURE --reverify --bundle com.tencent.compose" to "按赛道调 dmabuf/mem 短跑采样",
            "ohd triage CAPTURE --track native-heap --reverify --fresh --json" to "只复验 jemalloc，先 force-stop",
        ),
        "path" to listOf(
            "ohd path hilog.txt --coords coords.txt" to "日志页面事件 → .ohd 路径骨架",
            "ohd path hilog.txt --algo stack --map pagemap.txt --coords coords.txt --out replay.ohd" to "复杂场景：栈算法 + 页面映射 + 坐标表",
        ),
        "report" to listOf(
            "ohd report" to "最新证据包 → report.md（含复现步骤/栈/指标）",
            "ohd report ohd-evidence/cases/xxx --out /tmp/report.md" to "指定包和输出路径",
        ),
        "repro" to listOf(
            "ohd repro" to "按证据包步骤重放，只看有没有新增同类崩溃",
            "ohd repro --settle 3s --json" to "每步等久一点，输出 JSON 给流水线判断",
        ),
        "summary" to listOf(
            "ohd summary" to "历次会话一张表：哪轮崩了、内存怎么走、证据齐不齐",
            "ohd summary --since 24h --limit 50" to "只看近 24 小时，最多 50 条",
        ),
        "salvage" to listOf(
            "ohd salvage" to "数据没落盘时，从残留会话反推运行画像（只报可确认项）",
            "ohd salvage ohd-evidence/device/xxx --json" to "指定残留会话目录",
        ),
        "ext" to listOf(
            "ohd ext list" to "看当前装了什么扩展（来自哪一层目录）",
            "ohd ext docs" to "扩展能拿到的环境变量和约定",
            "ohd ext which hello" to "这条命令到底解析到哪个脚本",
        ),
    )

    /** usage 串按行拆开，去掉空行（多行 usage 里第二行起带缩进，保留相对缩进）。 */
    fun usageLines(cap: Capability): List<String> =
        cap.usage.lines().filter { it.isNotBlank() }.map { it.trimEnd() }

    /** 示例列表：优先用本文件的，没有则回退到能力自带的单条 example。 */
    fun sampleOf(cap: Capability): List<Pair<String, String>> =
        capExamples[cap.name] ?: cap.example?.let { listOf(it to "") }.orEmpty()

    fun render(cap: Capability): String {
        val sb = StringBuilder()
        sb.append("ohd ").append(cap.name).append(" — ").append(cap.summary).append('\n')

        sb.append("\n用法\n")
        for (line in usageLines(cap)) sb.append("  ").append(line).append('\n')

        // 排序：先按「日常最常敲」排，其余按字母序——纯粹字母序会让 --bundle 沉到第二屏
        val ranked = cap.accepts.sortedWith(compareBy({ PREFERRED.indexOf(it).let { i -> if (i < 0) PREFERRED.size else i } }, { it }))
        val documented = ranked.filter { optionDocs.containsKey(it) }
        val others = ranked.filterNot { optionDocs.containsKey(it) }
        if (documented.isNotEmpty()) {
            sb.append("\n选项\n")
            val width = documented.maxOf { it.length } + 4
            for (name in documented) {
                sb.append("  ").append("--$name".padEnd(width)).append(optionDocs.getValue(name)).append('\n')
            }
        }
        if (others.isNotEmpty()) {
            sb.append("  （另有：").append(others.joinToString(" ") { "--$it" }).append("）\n")
        }

        val sample = sampleOf(cap)
        if (sample.isNotEmpty()) {
            sb.append("\n示例\n")
            for ((cmd, why) in sample) {
                sb.append("  ").append(cmd)
                if (why.isNotEmpty()) sb.append("    # ").append(why)
                sb.append('\n')
            }
        }

        sb.append("\n全局选项：--json / --device / --platform / --session-root / --no-archive 等，见 ohd --help\n")
        return sb.toString().trimEnd('\n')
    }
}
