#!/usr/bin/env python3
"""Summarize a monkey×scene_capture RUN into ANALYSIS.md (facts only)."""
from __future__ import annotations

import argparse
import csv
import os
import pathlib
import re
import subprocess
import sys


def _col(rows, name):
    out = []
    for r in rows:
        v = r.get(name)
        if v in (None, ""):
            continue
        try:
            out.append(float(v))
        except ValueError:
            pass
    return out


def _metric_block(name, xs):
    if not xs:
        return []
    a = xs[: max(1, len(xs) // 5)]
    b = xs[-max(1, len(xs) // 5) :]
    return [
        f"## {name}",
        f"- n={len(xs)} first={xs[0]:.1f} last={xs[-1]:.1f} "
        f"min={min(xs):.1f} max={max(xs):.1f} delta={xs[-1] - xs[0]:+.1f}",
        f"- early_min={min(a):.1f} late_min={min(b):.1f} "
        f"valley_delta={min(b) - min(a):+.1f}",
        "",
    ]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True, help="RUN directory")
    ap.add_argument(
        "--ohd",
        default=os.environ.get("OHD", "/Users/xuxingwang/KMP/ohd/dist/ohd"),
    )
    ap.add_argument("--triage", action="store_true", help="force ohd triage --reverify")
    args = ap.parse_args()
    run = pathlib.Path(args.run).expanduser().resolve()
    if not run.is_dir():
        print(f"missing RUN: {run}", file=sys.stderr)
        return 2

    caps = sorted((run / "automation-captures").glob("*/"), key=lambda p: p.stat().st_mtime, reverse=True)
    cap = caps[0] if caps else None

    if args.triage and cap and args.ohd:
        subprocess.run(
            [args.ohd, "triage", str(cap), "--reverify"],
            stdout=open(run / "triage.txt", "w"),
            stderr=subprocess.STDOUT,
            check=False,
        )
        subprocess.run(
            [args.ohd, "triage", str(cap), "--json"],
            stdout=open(run / "triage.json", "w"),
            stderr=subprocess.STDOUT,
            check=False,
        )

    lines = [
        "# monkey × scene_capture 分析（事实）",
        "",
        f"- RUN: `{run}`",
        f"- capture: `{cap}`",
        f"- orch COMPLETE: "
        f"{'yes' if (run / 'orchestrator.log').exists() and 'COMPLETE' in (run / 'orchestrator.log').read_text(errors='ignore') else 'no'}",
        "",
    ]

    if cap:
        csv_path = cap / "memory_samples.csv"
        rows = []
        if csv_path.exists():
            with csv_path.open() as f:
                rows = list(csv.DictReader(f))
        for m in ("total_pss_mb", "graph_mb", "gl_mb", "native_heap_mb", "heap_alloc_mb"):
            lines += _metric_block(m, _col(rows, m))
        for name in ("REPORT.md", "anomalies.log", "events.log"):
            p = cap / name
            if not p.exists():
                continue
            txt = p.read_text(errors="ignore").strip().splitlines()
            lines += [f"## {name} (tail)", "```", *txt[-80:], "```", ""]

    mlog = run / "monkey.log"
    if mlog.exists():
        ml = mlog.read_text(errors="ignore").splitlines()
        crashes = [ln for ln in ml if "崩溃" in ln or re.search(r"(?i)crash", ln)]
        steps = re.findall(r"^\[(\d+)\]", "\n".join(ml), flags=re.M)
        last_step = steps[-1] if steps else "?"
        lines += [
            "## monkey",
            f"- last_step={last_step}",
            f"- crash_log_lines={len(crashes)}",
            "```",
            *crashes[-40:],
            "```",
            "",
        ]

    tri = run / "triage.txt"
    if tri.exists():
        lines += ["## triage.txt", "```", tri.read_text(errors="ignore")[-4000:], "```", ""]

    lines += [
        "## 判读提醒",
        "- 看形状（锯齿/谷值抬升/阶梯），别只看首末差 → `cmp-core-ohos-automation-report`",
        "- `✗ 崩溃 #N` 后若 step 继续 = keep-going 正常，记 crashCount，另开 sym 查根因",
        "",
    ]

    out = run / "ANALYSIS.md"
    out.write_text("\n".join(lines))
    # Only stamp ANALYSIS.ready when orch finished — mid-run analyze must not
    # trip long-wait monitors that treat the stamp as "run complete".
    orch_log = run / "orchestrator.log"
    if orch_log.exists() and "COMPLETE" in orch_log.read_text(errors="ignore"):
        (run / "ANALYSIS.ready").write_text("1\n")
    else:
        (run / "ANALYSIS.ready").unlink(missing_ok=True)
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
