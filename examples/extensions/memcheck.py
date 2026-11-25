#!/usr/bin/env python3
# @ohd 内存巡检：跑 ohd mem 并抽一行摘要，异常时退出码 1（便于 CI 直接消费）
import os
import re
import subprocess
import sys

ohd = os.environ.get("OHD_SELF") or "ohd"
bundle = sys.argv[1] if len(sys.argv) > 1 else "com.tencent.compose"
limit_mb = int(sys.argv[2]) if len(sys.argv) > 2 else 0

proc = subprocess.run([ohd, "mem", "--bundle", bundle], capture_output=True, text=True)
if proc.returncode != 0:
    print(proc.stderr.strip() or f"ohd mem 失败: {proc.returncode}", file=sys.stderr)
    sys.exit(proc.returncode)

text = proc.stdout
pss = re.search(r"PSS\s*[:=]?\s*([\d.]+)\s*(MB|KB|GB)", text, re.I)
peak = re.search(r"峰值\s*[:=]?\s*([\d.]+)\s*(MB|KB|GB)", text)

def to_mb(m):
    if not m:
        return None
    v, unit = float(m.group(1)), m.group(2).upper()
    return {"KB": v / 1024, "MB": v, "GB": v * 1024}[unit]

now, top = to_mb(pss), to_mb(peak)
print(f"bundle={bundle} pss={now and round(now, 1)}MB peak={top and round(top, 1)}MB")

if limit_mb and now is not None and now > limit_mb:
    print(f"x 驻留 {now:.1f}MB 超过阈值 {limit_mb}MB", file=sys.stderr)
    sys.exit(1)
