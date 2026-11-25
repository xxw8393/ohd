#!/bin/sh
# @ohd UI 冒烟：随机点击 N 次，每步校验应用进程存活（hdc uitest 原生序列，不依赖 ohd 内部格式）
set -eu

HDC="${OHD_HDC:-hdc}"
DEV="${OHD_DEVICE:-}"
BUNDLE="${OHD_BUNDLE:-com.tencent.compose}"
COUNT=10
INTERVAL=400

while [ $# -gt 0 ]; do
  case "$1" in
    --bundle)   BUNDLE="$2"; shift 2 ;;
    --count)    COUNT="$2"; shift 2 ;;
    --interval) INTERVAL="$2"; shift 2 ;;
    -h|--help)
      cat <<'EOF'
用法: ohd uitest [--bundle <应用>] [--count N] [--interval 毫秒]
随机点击 N 次，每次点击后用 pidof 校验进程没退出。
EOF
      exit 0 ;;
    *) echo "x 未知参数：$1" >&2; exit 2 ;;
  esac
done

T=""
[ -n "$DEV" ] && T="-t $DEV"

# 屏幕尺寸未知时按 1260x2720 近似；要精确就自己接 uitest dumpLayout
W=1260
H=2720
i=0
while [ "$i" -lt "$COUNT" ]; do
  i=$((i + 1))
  x=$(awk -v n="$W" -v s="$$$i" 'BEGIN{srand(s); printf "%d", int(rand()*n)}')
  y=$(awk -v n="$H" -v s="$$$i" 'BEGIN{srand(s+1); printf "%d", int(rand()*n)}')
  echo "→ [$i/$COUNT] click $x,$y"
  $HDC $T shell uitest uiInput click "$x" "$y" >/dev/null 2>&1 || echo "! click 调用失败（设备忙？）"
  sleep "$(awk -v ms="$INTERVAL" 'BEGIN{printf "%.3f", ms/1000}')"
  pid=$($HDC $T shell pidof "$BUNDLE" 2>/dev/null | tr -d '\r')
  if [ -z "$pid" ]; then
    echo "x 进程已退出：$BUNDLE"
    exit 1
  fi
  echo "   pid=$pid"
done
echo "ok: $COUNT 次点击后 $BUNDLE 仍在运行"
