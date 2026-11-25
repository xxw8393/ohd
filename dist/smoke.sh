#!/bin/bash
# ohd 冒烟：18 项覆盖全部能力。exit 0/7/2 均为预期，非 0/2/7 才是问题。
# 用法: ./smoke.sh [包目录]   在 ohd 根目录执行
set -u
BIN="${OHD_BIN:-./ohd}"
CASE="${1:-}"
[ -x "$BIN" ] || { echo "找不到 ohd 可执行文件: ${BIN}（先跑 build.sh）"; exit 1; }
TMP="${TMPDIR:-/tmp}"
fail=0
run() {
  n=$1; expect=$2; shift 2
  "$BIN" "$@" > "$TMP/ohd-smoke-$n.log" 2>&1
  c=$?
  if [ "$c" = "$expect" ]; then s=ok; else s="FAIL(期望 $expect)"; fail=$((fail+1)); fi
  printf '%-14s exit=%-2s %s\n' "$n" "$c" "$s"
}
run doctor   0 doctor
run selftest 0 selftest
run env      0 env --json
run tree     0 tree --depth 3
run shot     0 shot --out "$TMP/ohd-smoke.png"
run mem      0 mem --bundle com.ohos.sceneboard
run cpu      0 cpu --bundle com.ohos.sceneboard
run watch    0 watch --bundle com.ohos.sceneboard --count 3 --interval 1s
run tap      0 tap 640 1400
run input    0 input key Back
run crash    0 crash --json
run dmabuf   0 dmabuf snap --bundle com.ohos.sceneboard --top 3
run badarg   2 tree --dept 3
run nocap    2 no-such-capability
# 离线：帮助文案含交研发参数（不碰设备）
"$BIN" bundle --help > "$TMP/ohd-smoke-bundle-help.log" 2>&1
if grep -q 'zip' "$TMP/ohd-smoke-bundle-help.log" && grep -q 'report' "$TMP/ohd-smoke-bundle-help.log"; then
  printf '%-14s exit=%-2s %s\n' "bundle-help" "0" "ok"
else
  printf '%-14s exit=%-2s %s\n' "bundle-help" "?" "FAIL(期望 help 含 zip/report)"
  fail=$((fail+1))
fi
if [ -n "$CASE" ]; then
  run analyze 0 analyze "$CASE"
  run report  0 report "$CASE" --out "$TMP/ohd-smoke-report.md"
  run repro   7 repro "$CASE" --settle 1s
fi
echo
if [ "$fail" -eq 0 ]; then echo "冒烟通过"; else echo "失败 $fail 项，逐个看 $TMP/ohd-smoke-*.log"; fi
exit $([ "$fail" -eq 0 ] && echo 0 || echo 1)
