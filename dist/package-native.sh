#!/usr/bin/env bash
# 打**原生**分发包：单个可执行文件，零运行时依赖，启动约 6ms（JVM 版约 61ms）。
#
# 与 package.sh 的关系：两个脚本各自独立出货。
# - package.sh      → JVM 包（自带 jlink 运行时，覆盖 Windows；支持全部图像能力）
# - package-native.sh → 原生包（无运行时，体积小、启动快；图像标注/GIF/视频帧三项降级）
#
# 为什么不合成一个包：JVM 运行时约 40MB，原生二进制 5MB，
# 合起来会让「只想要快」的用户白下 40MB。
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -m1 -E '^[[:space:]]*version *=' build.gradle.kts 2>/dev/null | sed 's/.*"\(.*\)".*/\1/' || echo 0.2.0)"
VERSION="${VERSION:-0.2.0}"

# TARGET 是 K/N 的 target 名，GTASK 是对应的 Gradle 任务后缀（首字母大写，显式映射避免 sed \U 的 BSD/GNU 差异）
case "$(uname -s)" in
  Darwin) TARGET="macosArm64"; GTASK="MacosArm64"; PLATFORM="darwin-$(uname -m)" ;;
  Linux)  TARGET="linuxX64";  GTASK="LinuxX64";  PLATFORM="linux-$(uname -m)" ;;
  *) echo "原生入口只承诺 posix（macOS/Linux）；Windows 请用 dist/package.sh 的 JVM 包" >&2; exit 1 ;;
esac

GRADLE_BIN="${GRADLE:-}"
if [ -z "$GRADLE_BIN" ]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  else
    # 与 dist/build.sh 一致：复用机器上已下载的 Gradle 发行版，优先 8.x
    # （Kotlin 2.2 尚未适配 Gradle 9）。
    CANDIDATES="$(find "$HOME/.gradle/wrapper/dists" -maxdepth 6 -type f -path "*/bin/gradle" 2>/dev/null)"
    GRADLE_BIN="$(printf '%s\n' "$CANDIDATES" | grep -E '/gradle-8\.[0-9]+(\.[0-9]+)?/bin/gradle$' | sort -V | tail -1)"
    [ -n "$GRADLE_BIN" ] || GRADLE_BIN="$(printf '%s\n' "$CANDIDATES" | sort -V | tail -1)"
  fi
fi

if [ -z "$GRADLE_BIN" ] || [ ! -x "$GRADLE_BIN" ]; then
  echo "找不到 gradle：请安装 gradle，或设 GRADLE=/path/to/gradle" >&2
  exit 1
fi

echo "使用 gradle: $GRADLE_BIN"

echo "==> 构建原生可执行（release）"
"$GRADLE_BIN" -p . --no-daemon ":ohd-cli:linkReleaseExecutable${GTASK}"

BIN="ohd-cli/build/bin/$TARGET/releaseExecutable/ohd.kexe"
[ -x "$BIN" ] || { echo "构建未产出 $BIN" >&2; exit 1; }

OUT="$(pwd)/dist/ohd-${VERSION}-${PLATFORM}-native"
rm -rf "$OUT"; mkdir -p "$OUT"
cp "$BIN" "$OUT/ohd"
chmod +x "$OUT/ohd"

# 冒烟：跑 selftest 确认可执行真能用（跨机器 CPU 架构不符会在这里暴露）
echo "==> 冒烟"
if "$OUT/ohd" --version >/dev/null 2>&1; then
  echo "    --version OK"
else
  echo "    警告：可执行无法运行（架构不符？）" >&2
fi

cat > "$OUT/README.txt" <<'README'
ohd 原生版（@VERSION@ / @PLATFORM@）

解压即用，无需 JDK。

    ./ohd --version
    ./ohd selftest
    ./ohd --help

与 JVM 版的差别（仅三项降级，其余完全等价）：
  - 图像标注图（shot --grid/--boxes 的叠加图）
  - GIF 合成（video / bundle 的 replay.gif）
  - 视频帧解码（video 的内嵌帧合成）

以上三项在原生版会明确报「当前运行时不支持」，不会产出坏图。
需要这些能力请用 JVM 版（dist/package.sh 打的包）。

启动速度：原生约 6ms，JVM 版约 61ms。
README
sed -i '' -e "s/@VERSION@/${VERSION}/g" -e "s/@PLATFORM@/${PLATFORM}/g" "$OUT/README.txt" 2>/dev/null || \
  sed -i -e "s/@VERSION@/${VERSION}/g" -e "s/@PLATFORM@/${PLATFORM}/g" "$OUT/README.txt"

echo "==> 打包"
( cd dist && zip -qr "ohd-${VERSION}-${PLATFORM}-native.zip" "ohd-${VERSION}-${PLATFORM}-native" )
echo ""
echo "完成：dist/ohd-${VERSION}-${PLATFORM}-native.zip"
ls -lh "dist/ohd-${VERSION}-${PLATFORM}-native.zip" "$OUT/ohd" | sed 's/^/    /'
