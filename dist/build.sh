#!/usr/bin/env bash
# 构建 ohd（installDist）。不自动跑——由使用者显式执行。
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"

GRADLE_BIN="${GRADLE:-}"
if [ -z "$GRADLE_BIN" ]; then
  if command -v gradle >/dev/null 2>&1; then
    GRADLE_BIN="$(command -v gradle)"
  else
    # 复用机器上已下载的 Gradle 发行版（避免要求再装一份）。
    # 深度是 dists/<hash>/<ver>/bin/gradle，且优先 8.x：Kotlin 2.2 尚未适配 Gradle 9。
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
exec "$GRADLE_BIN" -p "$ROOT" --no-daemon installDist "$@"
