#!/usr/bin/env bash
# 打一个自带运行时的分发包：同事解压即用，不必预装 JDK。
# 产物：dist/ohd-<版本>-<平台>.zip
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -m1 -E '^[[:space:]]*version *=' build.gradle.kts 2>/dev/null | sed 's/.*"\(.*\)".*/\1/' || echo 0.1.0)"
VERSION="${VERSION:-0.1.0}"
PLATFORM="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"

echo "==> 构建"
./dist/build.sh

echo "==> 生成精简运行时（jlink）"
rm -rf dist/runtime
jlink \
  --add-modules java.base,java.desktop,java.logging,java.xml,jdk.unsupported \
  --output dist/runtime \
  --strip-debug --no-header-files --no-man-pages --compress=2

echo "==> 组装"
OUT="dist/ohd-${VERSION}-${PLATFORM}"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -R dist/runtime "$OUT/runtime"
cp -R ohd-cli/build/install/ohd/lib "$OUT/lib"
cp dist/ohd "$OUT/ohd"
cp dist/smoke.sh "$OUT/smoke.sh" 2>/dev/null || true
chmod +x "$OUT/ohd" "$OUT/smoke.sh" 2>/dev/null || true

# README.txt：解压即用短说明（占位符渲染）；README.md：与仓内最新正文同步
sed -e "s/@VERSION@/${VERSION}/g" \
    -e "s/@PLATFORM@/${PLATFORM}/g" \
    -e "s/@OHD@/ohd/g" \
    -e "s/@SMOKE@/smoke.sh/g" \
    dist/README.dist.txt > "$OUT/README.txt"
cp README.md "$OUT/README.md"
# 示例扩展：目录名带 .example，避免被 ohd 当成真命令自动加载；用户拷出来才生效
rm -rf "$OUT/extensions.example"
mkdir -p "$OUT/extensions.example"
cp examples/extensions/* "$OUT/extensions.example/"
chmod +x "$OUT/extensions.example/"*.sh 2>/dev/null || true

echo "==> 压缩"
( cd dist && zip -qr "ohd-${VERSION}-${PLATFORM}.zip" "ohd-${VERSION}-${PLATFORM}" )
du -sh "dist/ohd-${VERSION}-${PLATFORM}.zip"
echo "完成：dist/ohd-${VERSION}-${PLATFORM}.zip"
