#!/usr/bin/env python3
"""从 hilog 页面事件反推操作路径，生成可 replay 的 steps.ohd 骨架。

来源：2026-10-19「设备内存瞬间到 4G」排查会话（session-65c27c2f）里的原型验证脚本，
原稿在 /tmp/path_from_log.py（v1）与 /tmp/path2.py（v2，本文件默认逻辑）。

输入：ohd log-save 产物（hilog 文本，如 ohd-evidence/device/<ts>-log-save/001-hilog.txt）
输出：steps.ohd 骨架（`launch` + 每步一个动作行，可 `ohd replay --dry-run` 校验语法）

语义模型（ArkUI + Compose 多实例）：
  - Compose_N 首次 onPageShow  -> 进入一个新页面实例（需要一次 tap）
  - Compose_N 再次 onPageShow  -> 回到更早的实例（一次 key Back）
  - onPageHide 不移除实例（hide 后实例仍存活）

两种推断算法：
  --algo stack（默认，v2）：维护页面栈，输出 from->to 转移；coords 键如 `Compose_0->Compose_1`
  --algo first（v1）      ：只看首次出现顺序，输出 enter/back；coords 键如 `enter:Compose_1`

映射文件（--map / --coords）都是 `key=value` 行，`#` 开头为注释：
  pagemap.txt:  Compose_1=MultiInstance
  coords.txt :  Compose_0->Compose_1=tap 297 683
"""
import argparse
import json
import re
import sys

CTRL = re.compile(r'ArkUIViewController\((Compose_\d+)\) onPage(Show|Hide) active=(true|false)')
PAGE = re.compile(r'ComposeAppShell: (\S+) onPage(Show|Hide)')
TIME = re.compile(r'\s(\d{2}:\d{2}:\d{2}\.\d{3})\s')


def parse(path):
    ev = []
    for line in open(path, errors='ignore'):
        mt = TIME.search(line)
        if not mt:
            continue
        t = mt.group(1)
        for m in CTRL.finditer(line):
            ev.append({'t': t, 'kind': 'ctrl', 'name': m.group(1), 'act': m.group(2).lower()})
        for m in PAGE.finditer(line):
            ev.append({'t': t, 'kind': 'page', 'name': m.group(1), 'act': m.group(2).lower()})
    return ev


def infer_stack(ev):
    """v2：维护页面栈，返回 [{from, to, act, t}]，act ∈ {enter, back}。"""
    first = {}
    for e in ev:
        if e['kind'] == 'ctrl':
            first.setdefault(e['name'], e['act'])
    stack = [n for n, a in first.items() if a == 'hide']
    out = []
    for e in ev:
        if e['kind'] != 'ctrl' or e['act'] != 'show':
            continue
        n = e['name']
        if stack and stack[-1] == n:
            continue                       # 已在栈顶：重复 show，忽略
        frm = stack[-1] if stack else None
        if n in stack:
            while stack and stack[-1] != n:
                stack.pop()
            out.append({'from': frm, 'to': n, 'act': 'back', 't': e['t']})
        else:
            stack.append(n)
            out.append({'from': frm, 'to': n, 'act': 'enter', 't': e['t']})
    return out


def infer_first(ev):
    """v1：首次出现定 enter/back，返回 [(act, name, t)]。"""
    first = {}
    for e in ev:
        if e['kind'] != 'ctrl':
            continue
        first.setdefault(e['name'], e['act'])
    seen = [n for n, a in first.items() if a == 'hide']
    out = []
    for e in ev:
        if e['kind'] != 'ctrl' or e['act'] != 'show':
            continue
        n = e['name']
        if n in seen:
            if out and out[-1][1] == n:
                continue                   # 同一实例重复 show，忽略
            out.append(('back', n, e['t']))
        else:
            seen.append(n)
            out.append(('enter', n, e['t']))
    return out


def load_kv(path):
    d = {}
    if not path:
        return d
    for line in open(path, errors='ignore'):
        line = line.strip()
        if line and '=' in line and not line.startswith('#'):
            k, v = line.split('=', 1)
            d[k.strip()] = v.strip()
    return d


def emit_stack(trans, names, coords, pkg, ability):
    lines = ['# hilog 推断脚本（from->to 映射）', f'launch {pkg} {ability}']
    for s in trans:
        key = f'{s["from"]}->{s["to"]}'
        label = names.get(s['to'], s['to'])
        if key in coords:
            lines.append(f'{coords[key]}   # {key} → {label} @{s["t"]}')
        elif s['act'] == 'back':
            lines.append(f'key Back   # {key} → {label} @{s["t"]}（默认 back）')
        else:
            lines.append(f'# TODO {key} → {label} @{s["t"]}  缺坐标')
    return lines


def emit_first(steps, mapping, coords, pkg, ability):
    lines = ['# 由 hilog 页面事件推断生成（ohd path 原型）', f'launch {pkg} {ability}']
    for act, name, t in steps:
        label = mapping.get(name, name)
        key = f'{act}:{name}'
        if key in coords:
            lines.append(f'{coords[key]}   # {act} {label} @{t}')
        elif act == 'back':
            lines.append(f'key Back   # -> {label} @{t}')
        else:
            lines.append(f'# TODO enter {label} ({name}) @{t}  需要 tap 该入口控件')
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('log')
    ap.add_argument('--package', default='com.tencent.compose')
    ap.add_argument('--ability', default='EntryAbility')
    ap.add_argument('--algo', choices=['stack', 'first'], default='stack')
    ap.add_argument('--map', help='Compose_N=页面名')
    ap.add_argument('--coords', help='stack: from->to=动作 / first: enter:Compose_N=动作')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    names = load_kv(a.map)
    coords = load_kv(a.coords)
    ev = parse(a.log)

    if a.algo == 'stack':
        trans = infer_stack(ev)
        if a.json:
            print(json.dumps({'events': len(ev), 'transitions': trans}, ensure_ascii=False, indent=2))
            return
        n_enter = sum(1 for s in trans if s['act'] == 'enter')
        n_back = sum(1 for s in trans if s['act'] == 'back')
        print(f'# 事件 {len(ev)} → 转移 {len(trans)}（enter {n_enter} / back {n_back}）', file=sys.stderr)
        print('\n'.join(emit_stack(trans, names, coords, a.package, a.ability)))
    else:
        steps = infer_first(ev)
        if a.json:
            print(json.dumps({'events': len(ev), 'steps': steps}, ensure_ascii=False, indent=2))
            return
        n_enter = sum(1 for s in steps if s[0] == 'enter')
        n_back = sum(1 for s in steps if s[0] == 'back')
        print(f'# 事件 {len(ev)} 条 → 进入 {n_enter} 次 / 回退 {n_back} 次', file=sys.stderr)
        print('\n'.join(emit_first(steps, names, coords, a.package, a.ability)))


if __name__ == '__main__':
    main()
