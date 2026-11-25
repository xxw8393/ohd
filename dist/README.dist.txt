ohd — 移动端真机能力工具箱 @VERSION@ (@PLATFORM@)
============================================================
本目录自带 Java 运行时（runtime/），解压即用，无需预装 JDK。

包内结构
  @OHD@ 启动器（入口，直接用这个）
  lib/ ohd 的 jar 与依赖
  runtime/ 精简 JRE（jlink 生成，勿删）
  @SMOKE@ 冒烟自检脚本
  extensions.example/ 示例扩展（拷到 extensions/ 后才生效）

外部依赖（按目标平台，缺哪个先看 @OHD@ doctor 的输出）
  OHOS     hdc —— DevEco Studio / OpenHarmony SDK 自带；也可 --hdc 或 OHD_HDC 指定
  Android  adb  —— Android SDK platform-tools
  iOS      macOS + Xcode 15+（simctl 模拟器 / devicectl 真机），触摸注入可选 idb

第一次用（先证明这台机器能跑，再碰设备）
  @OHD@ selftest            离线自检，不碰设备
  @OHD@ doctor              环境自检：hdc / 设备 / uitest / faultlog / llvm / 磁盘
  @OHD@ env-dump --json     设备指纹（证据包 env 来源）
  @OHD@ --help              全部能力与参数

完整说明见同目录 README.md（与源码仓同步的最新版）。

常用命令（分组）
  装包压测   install / monkey（--keep-going / --keep-a11y）/ ab
  日志       log start|stop|snap|count|save  —— 默认写 00N-hilog.log.gz（多成员 gzip，被中断也能解压）
  内存卡顿   mem / watch / dmabuf / cpu
  观察操作   tree / go / wait / shot / tap / input / record / replay / diff
  问题回溯   crash / sym / path / summary / bundle / analyze / triage / report / repro
  长稳值守   soak（纯观测或按脚本驱动）/ monitor（动作由别的工具给，这里只记录）
  设备       devices list|use
  扩展       ext list|docs|which

符号化（给研发可读栈；so 须与真机 Build-ID 一致）
  @OHD@ sym --latest --so-dir /path/to/debugShared
  @OHD@ sym --crash faultlog.txt --so-dir a:b --limit 40
  @OHD@ sym --crash faultlog.txt --hap app-signed.hap

自己的命令（扩展）
  把一个脚本放进 extensions/<名字>.{sh,py,java,bat}，就多一条 @OHD@ <名字> 命令；
  extensions.example/ 里有可直接用的样例（hlog.sh 日志、uitest.sh 界面自动化、
  memcheck.py 内存、hello.java / jlog.java 演示 Java 扩展）。
  想用哪个就拷出来：把 extensions.example/ 下的脚本复制到 extensions/（Windows 同样 copy）。
  ohd 启动时按 cwd/.ohd/extensions → ~/.ohd/extensions → 安装目录/extensions 顺序扫描。

配置（覆盖顺序：CLI 显式参数 > 配置文件 > 进程环境变量 > 平台自动探测）
  @OHD@ config set OHD_HDC /path/to/hdc      # 存进 ~/.ohd/config.json
  @OHD@ config list        # 生效值 + 来源
  @OHD@ config keys        # 常用键清单
  @OHD@ config env         # 输出 export 行，可 eval 进当前 shell

产物与会话
  每次操作的证据落在 ./ohd-evidence/device/<时间戳>-<能力>/，含 manifest.json；
  合并好的证据包在 ./ohd-evidence/cases/。换根目录用 --session-root。

退出码
  0 成功      2 用法错误    3 无设备 / 未授权   4 断言失败
  5 设备能力缺失   6 超时 / 流僵死   7 Build-ID 或产物不匹配（拒绝给结论）

每个参数的含义用 @OHD@ <命令> --help 查；扩展能拿到的环境变量用 @OHD@ ext docs 查。
排障仍不通时，把 @OHD@ doctor 与 selftest 的原始输出一起贴回。
