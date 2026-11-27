#!/usr/bin/env bash
# 生成 AppCDS 归档（类预加载，实测启动 62ms → 40ms）。
#
# 关键约束：CDS 归档与「生成它的那个 JVM」强绑定——宿主机 JDK 与 dist/runtime 里的
# jlink 运行时即使版本号完全相同也无法互相映射（实测报 Unable to map shared spaces）。
# 所以这里必须按 dist/ohd 同样的优先级挑 java，保证「生成用哪个、运行就用哪个」。
#
# 用法：dist/cds.sh（可反复执行；换 JDK 或改代码后重跑）
set -euo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SELF_DIR/.." && pwd)"

# 与 dist/ohd 相同的优先级：打包运行时优先，回退宿主机 java
BUNDLED="$SELF_DIR/runtime/bin/java"
if [ -x "$BUNDLED" ]; then
  JAVA="$BUNDLED"
  OUT="$SELF_DIR/ohd.jsa"
elif command -v java >/dev/null 2>&1; then
  JAVA="java"
  OUT="$ROOT/build/cds/ohd.jsa"
else
  echo "找不到 java" >&2
  exit 1
fi

# 开发形态的 jar 在 ohd-cli/build/install/ohd/lib；打包形态在 <包>/lib
if [ -d "$SELF_DIR/../ohd-cli/build/install/ohd/lib" ]; then
  LIB="$SELF_DIR/../ohd-cli/build/install/ohd/lib"
elif [ -d "$SELF_DIR/lib" ]; then
  LIB="$SELF_DIR/lib"
else
  echo "找不到 ohd 的 jar 目录，先跑 dist/build.sh" >&2
  exit 1
fi

# 归档里存的是 classpath **解析后** 的 jar 路径：用相对路径生成，换个 cwd 启动就映射不上
# （实测 `./ohd --version` 报 Unable to use shared archive）。所以统一取绝对路径再生成，
# 让 `./ohd`、绝对路径调用、PATH 调用三种方式解析到同一批 jar。
LIB="$(cd "$LIB" && pwd -P)"

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"

# jlink 产出 / 全新 JDK 可能没有 base 归档，先 dump 一份（已存在则很快跳过）
if [ ! -f "$(dirname "$(readlink -f "$JAVA" 2>/dev/null || echo "$JAVA")")/../lib/server/classes.jsa" ]; then
  "$JAVA" -Xshare:dump >/dev/null 2>&1 || true
fi

"$JAVA" -XX:ArchiveClassesAtExit="$OUT" -cp "$LIB/*" ohd.cli.MainKt --help >/dev/null

if [ ! -f "$OUT" ]; then
  echo "AppCDS 生成失败：$OUT" >&2
  exit 1
fi

# 自检：用 -Xshare:on 强制「映射失败即报错」。不能用 -Xshare:auto + 数 shared objects：
# base 归档（classes.jsa）本身也会贡献几百个 shared 类，app 归档挂掉时计数照样过百，测不出问题。
# 再从 / 以外的 cwd 跑一次，确保没有被 cwd 绑死。
if ! ( cd /tmp && "$JAVA" -Xshare:on -XX:SharedArchiveFile="$OUT" -cp "$LIB/*" \
        ohd.cli.MainKt --version >/dev/null 2>&1 ); then
  echo "警告：CDS 归档无法映射（换目录启动即失效），启动不会有收益" >&2
  exit 1
fi

echo "CDS: $OUT ($(du -h "$OUT" | cut -f1), 已验证可跨 cwd 映射)"
