#!/usr/bin/env bash
# @ohd 抓 N 秒日志，按 error 级 tag 统计（封装内置 log 能力的示例）
#
# 这是「包装已有能力」而不是「重造轮子」的写法：
#   - 不碰 hdc/hilog，直接调 ohd log start / stop，跨 OHOS 与 Android 都自动适配
#   - 用 $OHD_SELF 调回 ohd 自身，用 $OHD_DEVICE 绑定当前设备
#   - 第一行的 `# @ohd ...` 会显示在 `ohd --help` 的扩展段落里
set -euo pipefail

SECS=10
while [ $# -gt 0 ]; do
  case "$1" in
    -s|--seconds) SECS="${2:-10}"; shift 2 ;;
    -h|--help)
      echo "用法: ohd hlog [--seconds N]"
      echo "  抓 N 秒日志（默认 10），停流后按 error 级 tag 统计"
      exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

SELF="${OHD_SELF:-ohd}"

# 多设备时 ohd 不会猜，必须显式绑定；OHD_DEVICE 由 ohd 注入。
# 注意用函数而非数组：macOS 自带 bash 3.2 在 `set -u` 下展开空数组会报 unbound。
run_ohd() {
  if [ -n "${OHD_DEVICE:-}" ]; then
    "$SELF" --device "$OHD_DEVICE" "$@"
  else
    "$SELF" "$@"
  fi
}

echo "▸ 设备 ${OHD_DEVICE:-（自动探测）}  采集 ${SECS}s"

START=$(run_ohd log start 2>&1 || true)
FILE=$(printf '%s\n' "$START" | awk '/输出文件/{print $2; exit}')
if [ -z "$FILE" ]; then
  echo "✗ log start 失败：" >&2
  printf '%s\n' "$START" >&2
  exit 1
fi

sleep "$SECS"
run_ohd log stop >/dev/null 2>&1 || true

TMP=$(mktemp -t hlog.XXXXXX)
case "$FILE" in
  *.gz) gunzip -c "$FILE" >"$TMP" 2>/dev/null || true ;;
  *)    cp "$FILE" "$TMP" 2>/dev/null || true ;;
esac

TOTAL=$(wc -l <"$TMP" | tr -d ' ')
ERRORS=$(grep -c ' E ' "$TMP" 2>/dev/null || true)
ERRORS=${ERRORS:-0}

echo "文件    $FILE"
echo "总行数  $TOTAL"
echo "error   $ERRORS"
if [ "$ERRORS" -gt 0 ]; then
  echo
  echo "按 tag 统计（前 10）："
  # hilog: "… E TagName    : msg"；logcat: "… E TagName: msg" —— 统一取 E 与冒号之间
  grep ' E ' "$TMP" | sed -E 's/.*[[:space:]]E[[:space:]]+([^:]+):.*/\1/' | sed 's/[[:space:]]*$//' \
    | sort | uniq -c | sort -rn | head -10
fi
rm -f "$TMP"
