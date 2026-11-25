#!/usr/bin/env bash
# ohd 全局构建入口（仓库根）。只在此显式调用时才编译——工具本身不会自动构建。
#
#   ./build.sh              构建（等价 ./dist/build.sh）
#   ./build.sh install      构建 + 把启动器装进 PATH（默认 ~/.local/bin/ohd）
#   ./build.sh package      打自包含分发包（dist/package.sh，自带精简 JRE）
#   ./build.sh smoke        冒烟（需真机，18 项覆盖全部能力）
#   ./build.sh all          build + install + package
#   ./build.sh uninstall    移除安装的启动器
#
# 选项：
#   --prefix <dir>   安装目录（默认 $PREFIX 或 ~/.local/bin）
#   --no-build       跳过构建（install 时用已有的产物）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$ROOT/dist"
PREFIX="${PREFIX:-$HOME/.local/bin}"
DO_BUILD=1

# ---- 参数解析（子命令 + 两个选项，保持极简）----
CMD=""
while [ $# -gt 0 ]; do
  case "$1" in
    --prefix) PREFIX="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) CMD="$1"; shift ;;
  esac
done
CMD="${CMD:-build}"

build() {
  echo "==> 构建（installDist）"
  "$DIST/build.sh"
  local lib="$ROOT/ohd-cli/build/install/ohd/lib"
  [ -d "$lib" ] || { echo "构建结束但找不到产物：$lib" >&2; exit 1; }
  echo "产物：$lib"
}

# 为什么写包装而不是软链：dist/ohd 用 BASH_SOURCE 定位自身目录再找 ../../ohd-cli/build/install/ohd/lib，
# 软链会让它解析到 ~/.local/bin 这一层，从而找不到 jar。包装脚本把真实路径写死，稳定。
install_ohd() {
  if [ "$DO_BUILD" = "1" ]; then build; fi
  local target="$PREFIX/ohd"
  mkdir -p "$PREFIX"
  printf '#!/usr/bin/env bash\nexec "%s" "$@"\n' "$DIST/ohd" > "$target"
  chmod +x "$target"
  echo "==> 已安装：$target → $DIST/ohd"
  case ":$PATH:" in
    *":$PREFIX:"*) echo "可直接用：ohd --help" ;;
    *) echo "提示：$PREFIX 不在 PATH 里，把下面这行加进 shell 配置后重开终端："
       echo "    export PATH=\"$PREFIX:\$PATH\"" ;;
  esac
}

uninstall_ohd() {
  local target="$PREFIX/ohd"
  if [ ! -f "$target" ]; then
    echo "没有安装过：$target"
    return 0
  fi
  # 只删我们自己的包装，避免误伤别的 ohd
  if grep -q "ohd/dist/ohd" "$target" 2>/dev/null; then
    rm -f "$target"
    echo "==> 已移除：$target"
  else
    echo "$target 不像 ohd 的启动器（内容不匹配），不动它" >&2
    exit 1
  fi
}

case "$CMD" in
  build) build ;;
  install) install_ohd ;;
  uninstall) uninstall_ohd ;;
  package)
    if [ "$DO_BUILD" = "1" ]; then echo "==> package 内部会自行构建、不再前置 build"; fi
    "$DIST/package.sh"
    ;;
  smoke)
    if [ "$DO_BUILD" = "1" ]; then build; fi
    ( cd "$DIST" && OHD_BIN="$DIST/ohd" ./smoke.sh )
    ;;
  all)
    build
    DO_BUILD=0 install_ohd
    "$DIST/package.sh"
    ;;
  *)
    echo "未知命令：$CMD" >&2
    echo "可用：build / install / uninstall / package / smoke / all" >&2
    exit 2
    ;;
esac

echo "==> 完成：$CMD"
