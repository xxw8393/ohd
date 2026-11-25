#!/usr/bin/env python3
"""Detach ohd monkey + ohos_automation_scene_capture (macOS-safe new session).

Usage:
  python3 run_detached.py --ohd /path/to/ohd --bundle com.tencent.compose \\
      --device SERIAL --duration 2h --run-root /tmp/ohd_monkey2h
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
import pathlib
import subprocess
import sys
import textwrap


ORCHESTRATE = r'''#!/usr/bin/env bash
set -uo pipefail
RUN="$1"; OHD="$2"; BUNDLE="$3"; DEVICE="$4"; DURATION="${5:-2h}"; KEEP_A11Y="${6:-0}"
log() { echo "$(date -Iseconds) $*" | tee -a "$RUN/orchestrator.log"; }
echo "{\"run\":\"$RUN\",\"bundle\":\"$BUNDLE\",\"device\":\"$DEVICE\",\"duration\":\"$DURATION\",\"keep_a11y\":\"$KEEP_A11Y\",\"started\":\"$(date -Iseconds)\"}" > "$RUN/state.json"
log "RUN_ROOT=$RUN pid=$$ keep_a11y=$KEEP_A11Y"
hdc -t "$DEVICE" shell aa force-stop "$BUNDLE" || true
sleep 2
hdc -t "$DEVICE" shell aa start -a EntryAbility -b "$BUNDLE" || true
sleep 4
CAP_OUT="$RUN/automation-captures"
mkdir -p "$CAP_OUT"
log "start capture: ohd --ext-timeout 0 --ext-mem 0 ohos_automation_scene_capture"
"$OHD" --ext-timeout 0 --ext-mem 0 ohos_automation_scene_capture \
  --bundle "$BUNDLE" --target "$DEVICE" \
  --mem-interval 30 --dmabuf-interval-s 60 --preset memory \
  --out-dir "$CAP_OUT" \
  >"$RUN/capture.log" 2>&1 &
CAP_PID=$!
echo "$CAP_PID" > "$RUN/capture.pid"
sleep 5
# --out MUST be a file path (directory → FileSystemException, monkey process dies)
MONKEY_OUT="$RUN/monkey.ohd"
A11Y_ARGS=()
if [[ "$KEEP_A11Y" == "1" ]]; then
  A11Y_ARGS=(--keep-a11y --a11y-ping-every 10 --tag ComposeA11y)
  log "start monkey duration=$DURATION out=$MONKEY_OUT keep-going keep-a11y"
else
  A11Y_ARGS=(--tag Konan_main)
  log "start monkey duration=$DURATION out=$MONKEY_OUT keep-going"
fi
set +e
"$OHD" monkey \
  --bundle "$BUNDLE" --device "$DEVICE" \
  --duration "$DURATION" \
  --interval 0 --batch 10 --sample-every 60 --check-every 30 --stagnate 60 \
  --keep-going \
  "${A11Y_ARGS[@]}" \
  --out "$MONKEY_OUT" \
  >"$RUN/monkey.log" 2>&1
MONKEY_RC=$?
set -e
log "monkey exited rc=$MONKEY_RC"
# 收尾：回首页 + 静置 30s（capture 仍活着才能采到稳态）。
# 新版 ohd monkey 默认已做 home-settle；若日志无标记则编排侧补做。
if rg -q "home-settle" "$RUN/monkey.log" 2>/dev/null; then
  log "monkey 已 home-settle，capture 再刷盘 2s"
  sleep 2
else
  log "monkey 无 home-settle，编排侧补：Back×12 + 静置 30s"
  for _ in $(seq 1 12); do
    hdc -t "$DEVICE" shell uitest uiInput keyEvent Back >/dev/null 2>&1 || true
    sleep 0.45
  done
  # 确保仍在目标应用前台（不 force-stop）
  hdc -t "$DEVICE" shell aa start -a EntryAbility -b "$BUNDLE" >/dev/null 2>&1 || true
  sleep 30
  log "编排侧 home-settle 结束"
fi
# 收网必须连 capture 的子进程（python 采集壳）一起收，否则只杀 ohd java 壳会留孤儿。
kill_tree() {
  local root="$1" c
  for c in $(pgrep -P "$root" 2>/dev/null || true); do kill_tree "$c"; done
  kill -TERM "$root" 2>/dev/null || true
}
if kill -0 "$CAP_PID" 2>/dev/null; then
  log "SIGTERM capture tree pid=$CAP_PID"
  kill_tree "$CAP_PID"
  for _ in $(seq 1 15); do
    kill -0 "$CAP_PID" 2>/dev/null || break
    sleep 2
  done
  kill -KILL "$CAP_PID" 2>/dev/null || true
  for c in $(pgrep -P "$CAP_PID" 2>/dev/null || true); do kill -KILL "$c" 2>/dev/null || true; done
  wait "$CAP_PID" 2>/dev/null || true
fi
# 兜底：按本轮独有 --out-dir 精确匹配残留采集进程（不误伤其它 run）
if pgrep -f "ohos_automation_scene_capture.*$CAP_OUT" >/dev/null 2>&1; then
  log "cleanup orphans matching $CAP_OUT"
  pkill -TERM -f "ohos_automation_scene_capture.*$CAP_OUT" 2>/dev/null || true
  sleep 2
  pkill -KILL -f "ohos_automation_scene_capture.*$CAP_OUT" 2>/dev/null || true
fi
LATEST=$(ls -1dt "$CAP_OUT"/*/ 2>/dev/null | head -1 || true)
log "capture dir=${LATEST:-none}"
if [[ -n "${LATEST:-}" ]]; then
  "$OHD" triage "$LATEST" --reverify >"$RUN/triage.txt" 2>&1 || true
  "$OHD" triage "$LATEST" --json >"$RUN/triage.json" 2>&1 || true
fi
"$OHD" mem --bundle "$BUNDLE" --json >"$RUN/mem_final.json" 2>&1 || true
"$OHD" dmabuf snap --bundle "$BUNDLE" >"$RUN/dmabuf_final.txt" 2>&1 || true
"$OHD" summary --since 3h --json >"$RUN/summary.json" 2>&1 || true
"$OHD" summary --since 3h >"$RUN/summary.txt" 2>&1 || true
echo "{\"finished\":\"$(date -Iseconds)\",\"monkey_rc\":$MONKEY_RC,\"capture\":\"${LATEST:-}\"}" >> "$RUN/state.json"
log "COMPLETE"
'''


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ohd", required=True, help="path to ohd binary")
    ap.add_argument("--bundle", required=True)
    ap.add_argument("--device", required=True, help="hdc serial")
    ap.add_argument("--duration", default="2h")
    ap.add_argument("--run-root", default="/tmp/ohd_monkey2h",
                    help="prefix; actual dir gets _YYYYMMDD_HHMMSS (default /tmp/...)")
    ap.add_argument("--path-file", default="/tmp/ohd_monkey2h_latest.path",
                    help="write absolute RUN path here for later analyze")
    ap.add_argument(
        "--keep-a11y",
        action="store_true",
        help="pass ohd monkey --keep-a11y (uitest dump keeps Compose a11y hot path open)",
    )
    args = ap.parse_args()

    ohd = pathlib.Path(args.ohd).expanduser().resolve()
    if not ohd.is_file():
        print(f"ohd not found: {ohd}", file=sys.stderr)
        return 2

    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    run = pathlib.Path(f"{args.run_root}_{ts}")
    run.mkdir(parents=True, exist_ok=True)
    (run / "automation-captures").mkdir(exist_ok=True)

    orch = run / "orchestrate.sh"
    orch.write_text(ORCHESTRATE)
    orch.chmod(0o755)

    launcher = open(run / "launcher.log", "ab", buffering=0)
    keep_a11y = "1" if args.keep_a11y else "0"
    # macOS: no setsid; start_new_session=True → new process group, survives agent shell death
    p = subprocess.Popen(
        [
            "bash",
            str(orch),
            str(run),
            str(ohd),
            args.bundle,
            args.device,
            args.duration,
            keep_a11y,
        ],
        stdin=subprocess.DEVNULL,
        stdout=launcher,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        cwd=str(run),
        env=os.environ.copy(),
    )
    (run / "orchestrator.pid").write_text(str(p.pid))
    pathlib.Path(args.path_file).write_text(str(run) + "\n")

    print(f"RUN={run}")
    print(f"orch_pid={p.pid}")
    print(f"path_file={args.path_file}")
    print(textwrap.dedent(f"""
    check detach:  ps -o pid,ppid,etime,command -p {p.pid}   # expect ppid=1
    follow:        tail -f {run}/monkey.log {run}/capture.log {run}/orchestrator.log
    analyze later: python3 {pathlib.Path(__file__).resolve().parent}/analyze_run.py --run {run}
    """).strip())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
