#!/usr/bin/env bash
# 打一个自带运行时的分发包：同事解压即用，不必预装 JDK。
# 产物：dist/ohd-<版本>-<平台>.zip
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="$(grep -m1 -E '^[[:space:]]*version *=' build.gradle.kts 2>/dev/null | sed 's/.*"\(.*\)".*/\1/' || echo 0.2.0)"
VERSION="${VERSION:-0.2.0}"
PLATFORM="$(uname -s | tr '[:upper:]' '[:lower:]')-$(uname -m)"

echo "==> 构建"
./dist/build.sh

echo "==> 生成精简运行时（jlink）"
rm -rf dist/runtime
jlink \
  --add-modules java.base,java.desktop,java.logging,java.xml,jdk.unsupported \
  --output dist/runtime \
  --strip-debug --no-header-files --no-man-pages --compress=2

# jlink 产出的运行时没有 base CDS 归档（classes.jsa），必须先 -Xshare:dump 生成，
# 否则后续 -XX:ArchiveClassesAtExit 会报 "base CDS archive is not loaded" 而静默失败。
# 注意：CDS 归档与「生成它的那个 JVM」强绑定——bundled runtime 与宿主机 JDK 即使版本号相同
# 也无法互相映射（实测报 Unable to map shared spaces）。所以归档必须用 dist/runtime/bin/java 生成。
echo "==> 生成 base CDS 归档"
rm -f dist/runtime/lib/server/classes.jsa
dist/runtime/bin/java -Xshare:dump >/dev/null

echo "==> 组装"
# 绝对路径：AppCDS 归档记录的是 classpath 解析后的 jar 路径，用相对路径生成会让归档
# 绑死在打包时的 cwd 上（解压到别处就映射失败）。这里全程用绝对路径。
OUT="$(pwd)/dist/ohd-${VERSION}-${PLATFORM}"
REL_OUT="dist/ohd-${VERSION}-${PLATFORM}"
rm -rf "$OUT"
mkdir -p "$OUT"
cp -R dist/runtime "$OUT/runtime"
cp -R ohd-cli/build/install/ohd/lib "$OUT/lib"
cp dist/ohd "$OUT/ohd"
cp dist/smoke.sh "$OUT/smoke.sh" 2>/dev/null || true
chmod +x "$OUT/ohd" "$OUT/smoke.sh" 2>/dev/null || true

# AppCDS：类预加载归档，实测启动 62ms → 40ms。必须用包里那套运行时生成，
# 换 JDK 或改 jar 内容都会导致映射失败（-Xshare:auto 会静默降级，功能不受影响）。
echo "==> 生成 AppCDS 归档"
"$OUT/runtime/bin/java" -XX:ArchiveClassesAtExit="$OUT/ohd.jsa" \
  -cp "$OUT/lib/*" ohd.cli.MainKt --help >/dev/null 2>&1 || {
    rm -f "$OUT/ohd.jsa"; echo "    警告：AppCDS 生成失败，将退回无 CDS 启动（约 62ms）" >&2
  }
[ -f "$OUT/ohd.jsa" ] && echo "    ohd.jsa $(du -h "$OUT/ohd.jsa" | cut -f1)"

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
du -sh "$REL_OUT.zip"
echo "完成：dist/ohd-${VERSION}-${PLATFORM}.zip"
