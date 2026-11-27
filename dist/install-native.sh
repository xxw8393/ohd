#!/usr/bin/env bash
# 把原生可执行安装成 `ohd`（不经 shell 包装，省掉 ~6ms bash 启动开销）。
#
# 为什么需要它：`dist/ohd` 是 bash 脚本，光是 bash 自身启动就要 6ms，
# 而原生二进制总共才 6ms。要拿到完整的启动优势，必须直接 exec 二进制。
# 本脚本用符号链接实现：`<prefix>/bin/ohd` → 二进制本体。
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"
PREFIX="${1:-$HOME/.local/bin}"

# 目标三元组：允许用 OHD_NATIVE_TARGET 覆盖（如 linuxX64）
TARGET="${OHD_NATIVE_TARGET:-}"
if [ -z "$TARGET" ]; then
  case "$(uname -s)" in
    Darwin) TARGET="macosArm64" ;;
    Linux)  TARGET="linuxX64" ;;
    *) echo "不支持的系统：$(uname -s)（原生入口只承诺 posix）" >&2; exit 1 ;;
  esac
fi

BIN="$ROOT/ohd-cli/build/bin/$TARGET/releaseExecutable/ohd.kexe"
if [ ! -x "$BIN" ]; then
  echo "找不到原生可执行：$BIN" >&2
  echo "先生成：gradle :ohd-cli:linkReleaseExecutable${TARGET^}" >&2
  exit 1
fi

mkdir -p "$PREFIX"
ln -sfn "$BIN" "$PREFIX/ohd"
echo "已安装：$PREFIX/ohd → $BIN"
echo ""
echo "验证："
echo "  $PREFIX/ohd --version"
echo ""
echo "如需回退 JVM 版本，改用：dist/ohd（它默认也优先原生，可 OHD_FORCE_JVM=1 强制 JVM）"
