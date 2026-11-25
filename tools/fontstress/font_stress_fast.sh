#!/usr/bin/env bash
# 字体栈短跑压测（几十秒一轮，替代 8h 长跑做日常验证）
#
# 场景：冷启 demo → 多实例页（Compose 容器 A/B）→ 容器 A 内反复进出 simple-text
#       （中文多字号富文本页，字体栈高频创建/查询），每轮 ≈ 40-50s。
# 采样：每轮前后各取一次 ohd mem，比较 jemalloc(pss+swap) / native heap / .ttf 映射。
# 判定：逐轮增量 + 最小二乘斜率；PASS / WARN / FAIL。
#
# 用法：
#   font_stress_fast.sh [--rounds 5] [--cycles 6] [--bundle com.tencent.compose]
#                       [--device <serial>] [--out <evidence dir>]
# 依赖：ohd（默认 /Users/xuxingwang/KMP/ohd/dist/ohd，可用 OHD 覆盖）、hdc。
set -uo pipefail

OHD="${OHD:-/Users/xuxingwang/KMP/ohd/dist/ohd}"
BUNDLE="com.tencent.compose"
ABILITY="EntryAbility"
ROUNDS=5
CYCLES=6
DEVICE=""
OUT=""

# 场景坐标（1280x2832，demo 冷启后 switch 页；自绘文本不进无障碍树，只能标定）
BTN_X=988; BTN_Y=2571        # 多实例测试
ITEM_X=1041; ITEM_Y=1403     # 容器 A 内 simple-text
BACK_X=119;  BACK_Y=531      # 容器 A 的返回箭头（回多实例页）
ENTER_SLEEP=4                # 页面渲染+文本布局
BACK_SLEEP=2

while [[ $# -gt 0 ]]; do
    case "$1" in
        --rounds) ROUNDS="$2"; shift 2 ;;
        --cycles) CYCLES="$2"; shift 2 ;;
        --bundle) BUNDLE="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --out)    OUT="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) echo "未知参数 $1" >&2; exit 2 ;;
    esac
done
[[ -n "$DEVICE" ]] && DEV_ARGS=(--device "$DEVICE") || DEV_ARGS=()

TS="$(date +%Y%m%d-%H%M%S)"
OUT="${OUT:-$HOME/KMP/ohd-evidence/fontstress-fast-$TS}"
mkdir -p "$OUT"
say() { echo "[fontstress] $*"; tee -a "$OUT/run.log" >/dev/null; }

# mem json → 三列：jemalloc(pss+swap) KiB / native heap KiB / .ttf pss KiB
sample() {  # <label>
    "$OHD" mem --bundle "$BUNDLE" "${DEV_ARGS[@]}" --json 2>/dev/null \
        | python3 -c '
import json,sys
d=json.load(sys.stdin)["data"]
def cat(name):
    for c in d.get("categories",[]):
        if c.get("name","").strip().startswith(name):
            return c.get("pssKb",0)+c.get("swapPssKb",0)
    return 0
jem = cat("jemalloc heap:") + cat("jemalloc meta:")
ttf = cat(".ttf")
nat = d.get("nativeHeapKb", 0)
pid = d.get("pid", 0)
print("%d\t%d\t%d\t%d" % (jem, nat, ttf, pid))
'
}

"$OHD" doctor "${DEV_ARGS[@]}" > "$OUT/doctor.txt" 2>&1 || { say "ohd doctor 失败"; exit 1; }

say "冷启 $BUNDLE"
hdc ${DEVICE:+-t $DEVICE} shell aa force-stop "$BUNDLE" >/dev/null 2>&1
sleep 2
hdc ${DEVICE:+-t $DEVICE} shell "aa start -b $BUNDLE -a $ABILITY" >/dev/null 2>&1
sleep 10
PID0="$("$OHD" mem --bundle "$BUNDLE" "${DEV_ARGS[@]}" --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["pid"])')"
[[ -z "$PID0" || "$PID0" == "0" ]] && { say "应用未启动"; exit 1; }
say "pid=${PID0}，进入多实例页"
"$OHD" tap $BTN_X $BTN_Y "${DEV_ARGS[@]}" >/dev/null 2>&1
sleep 5
say "预热一轮"
for _ in $(seq 1 2); do
    "$OHD" tap $ITEM_X $ITEM_Y "${DEV_ARGS[@]}" >/dev/null 2>&1; sleep $ENTER_SLEEP
    "$OHD" tap $BACK_X $BACK_Y "${DEV_ARGS[@]}" >/dev/null 2>&1;  sleep $BACK_SLEEP
done

: > "$OUT/samples.tsv"
echo -e "round\tjem_kib\tnative_kib\tttf_kib\tpid" >> "$OUT/samples.tsv"
prev=""; rows=()
for r in $(seq 1 "$ROUNDS"); do
    t0=$(date +%s)
    "$OHD" shot "${DEV_ARGS[@]}" >/dev/null 2>&1
    before="$(sample)" || { say "采样失败（round ${r}）"; exit 1; }
    for _ in $(seq 1 "$CYCLES"); do
        "$OHD" tap $ITEM_X $ITEM_Y "${DEV_ARGS[@]}" >/dev/null 2>&1; sleep $ENTER_SLEEP
        "$OHD" tap $BACK_X $BACK_Y "${DEV_ARGS[@]}" >/dev/null 2>&1;  sleep $BACK_SLEEP
    done
    after="$(sample)" || { say "采样失败（round ${r}）"; exit 1; }
    pid_after="$(awk '{print $4}' <<<"$after")"
    [[ "$pid_after" != "$PID0" ]] && { say "进程变了（$PID0 → ${pid_after}，疑似被系统回收），结果作废"; exit 1; }
    jem="$(awk '{print $1}' <<<"$after")"; nat="$(awk '{print $2}' <<<"$after")"; ttf="$(awk '{print $3}' <<<"$after")"
    echo -e "$r\t$jem\t$nat\t$ttf\t$pid_after" >> "$OUT/samples.tsv"
    rows+=("$jem")
    say "round $r: jemalloc=${jem}KiB native=${nat}KiB ttf=${ttf}KiB (${CYCLES} 次进出, $(( $(date +%s) - t0 ))s)"
done

# 判定：斜率（KiB/轮）+ 逐轮增量
verdict="$(python3 - "${rows[@]}" <<'PY'
import sys
v=[int(x) for x in sys.argv[1:]]
n=len(v)
if n<2: print("WARN\t样本不足"); raise SystemExit
d=[v[i+1]-v[i] for i in range(n-1)]
mean=sum(d)/len(d)
xs=list(range(n)); xm=(n-1)/2; ym=sum(v)/n
slope=sum((x-xm)*(y-ym) for x,y in zip(xs,v))/sum((x-xm)**2 for x in xs)
monotone=all(x>0 for x in d)
total=v[-1]-v[0]
if slope>256 and monotone and total>2048:
    tag="FAIL"
elif slope>96 or mean>192:
    tag="WARN"
else:
    tag="PASS"
print(f"{tag}\tslope={slope:+.0f}KiB/轮 mean_delta={mean:+.0f}KiB total={total:+d}KiB monotone={monotone}")
PY
)"
tag="${verdict%%$'\t'*}"; detail="${verdict#*$'\t'}"

{
    echo "# 字体栈短跑 REPORT (fontstress-fast)"
    echo
    echo "- 时间：$(date '+%Y-%m-%d %H:%M:%S')"
    echo "- 设备：$(grep -m1 -o 'model=[^ ]*' "$OUT/doctor.txt" 2>/dev/null)"
    echo "- 包名：$BUNDLE  pid：$PID0"
    echo "- 场景：多实例页 → 容器 A 内 simple-text 进/退，每轮 $CYCLES 次（约 40-50s/轮）"
    echo "- 轮数：$ROUNDS"
    echo "- 判定：**$tag**（${detail}）"
    echo
    echo '```'
    column -t -s $'\t' "$OUT/samples.tsv"
    echo '```'
    echo
    echo "边界：单次短跑只反映本轮窗口内的字体栈行为；「平」不等于修好，仍需与 baseline（关闭 purge/skiko 旧包）对拍。"
} > "$OUT/REPORT.md"
say "报告 → $OUT/REPORT.md"
[[ "$tag" == "FAIL" ]] && exit 1
exit 0
