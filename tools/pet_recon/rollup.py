"""汇总：把 main.tsv + bundles.tsv 合成一张「命令号 → 语义证据」总表，并给出覆盖度统计。

    python rollup.py

产出：<out>/cmd_map.tsv 与 <out>/cmd_map.md。
证据分级：
  A 同一方法里既有完整命令名又有唯一 trpc 服务名  → 可直接当结论用
  B 有完整命令名（但没有服务名，或服务名不唯一）  → 名字可信，功能名待补
  C 只有整数命令号，但该方法确实调到发包点/自己拼命令名 → 位置可信，功能待读文本
  D 只出现整数命令号                                → 可能是位模式巧合，必须人工核
"""
import argparse
import os
import re
import sys
from collections import defaultdict

DEFAULT_OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                           '..', '..', '..', '_scratch', 'pet_recon'))
HEX = re.compile(r'0x([0-9a-f]{4})')
# 强发包语汇：只认直接提到 OIDB/SSO/发包的日志，不用 request/proto 这种协程日志里也会出的泛词
NET_HINT = re.compile(r'(OidbSvcTrpcTcp|Sso_|OIDB|oidb|ProtoUtil|AISafeRequest|'
                      r'fetchFromNetwork|cmd\[|cmd=|sendPB|send pb|beginStory|send )')
NOISE_MODULES = 8   # 跨这么多无关页面都出现的号，基本只会是公用的位模式巧合
MAX_HOPS = 6      # 反向 BFS 半径（存下深度，分级时再看几个跳才算数）


def norm_cls(c):
    """归一类描述符：边文件里的被调类是从 `Lxxx;.name` 的括号组截的（不带分号），
    而证据行的 class 带分号；不归一化会导致所有入度=0。"""
    return c[:-1] if c.endswith(';') else c


def load_edges(paths):
    """callee -> callers 反向索引（(类描述符, 方法名) 为节点）。"""
    callers = defaultdict(set)
    for p in paths:
        if not os.path.exists(p):
            continue
        with open(p, encoding='utf-8') as f:
            f.readline()
            for line in f:
                q = line.rstrip('\n').split('\t')
                if len(q) < 4:
                    continue
                callers[(norm_cls(q[2]), q[3])].add((norm_cls(q[0]), q[1]))
    return callers


def sender_depth(callers, senders, hops=MAX_HOPS):
    """从发包点反向 BFS：得到「哪个方法在第几跳内能走到发包点」。"""
    depth = {s: 0 for s in senders}
    frontier = set(senders)
    for d in range(1, hops + 1):
        nxt = set()
        for node in frontier:
            for up in callers.get(node, ()):
                if up not in depth:
                    depth[up] = d
                    nxt.add(up)
        frontier = nxt
        if not frontier:
            break
    return depth


def read_rows(path):
    if not os.path.exists(path):
        return
    with open(path, encoding='utf-8') as f:
        head = f.readline().rstrip('\n').split('\t')
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < len(head):
                p += [''] * (len(head) - len(p))
            yield dict(zip(head, p))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=DEFAULT_OUT)
    ap.add_argument('--known', default=None,
                    help='可选：已在用命令清单文件（每行一个 0xXXXX），用于标“未接入”')
    ap.add_argument('--range', default='9000-9fff',
                    help='只看这个号段（十六进制），默认宠物号段')
    ap.add_argument('--chain-hops', type=int, default=2,
                    help='几跳以内算“在发包链上”（C 级）；超出但≤6 跳的只能算 D 待核')
    a = ap.parse_args()
    lo, hi = (int(x, 16) for x in a.range.split('-'))
    CHAIN = a.chain_hops

    rows = []
    for path, origin in ((os.path.join(a.out, 'main.tsv'), 'main'),
                         (os.path.join(a.out, 'bundles.tsv'), 'bundle')):
        for r in read_rows(path):
            r['origin'] = origin
            rows.append(r)
    if not rows:
        sys.exit('没有 tsv，先跑 scan_apk.py / scan_bundles.py')

    senders = {(norm_cls(r['class']), r['method']) for r in rows
               if r['cmd_names'] or r['name_concat'] == '1'}
    callers = load_edges([os.path.join(a.out, 'main_edges.tsv'),
                          os.path.join(a.out, 'bundles_edges.tsv')])
    depth = sender_depth(callers, senders)
    print(f'发包点={len(senders)} 调用边节点={len(callers)} 链上方法={len(depth)}', file=sys.stderr)
    known = set()
    if a.known and os.path.exists(a.known):
        known = set(HEX.findall(open(a.known, encoding='utf-8').read().lower()))

    agg = defaultdict(lambda: {'subs': set(), 'sso': set(), 'grade': 'D',
                               'main': set(), 'mods': set(), 'texts': [], 'rows': 0,
                               'hops': '', 'strong': False})

    def hexes_of(r):
        out = set(HEX.findall(r['cmd_names'].lower()))
        out |= set(HEX.findall(r['pet_ints'].lower()))
        return {h for h in out if lo <= int(h, 16) <= hi}

    for r in rows:
        hx = hexes_of(r)
        if not hx:
            continue
        sso = set(re.findall(r'Sso_[A-Za-z0-9_]+', r['sso_names']))
        exact = bool(r['cmd_names']) and len(sso) == 1 and len(hx) == 1
        on_chain = (norm_cls(r['class']), r['method']) in depth
        d0 = depth.get((norm_cls(r['class']), r['method']), 99)
        hint = bool(NET_HINT.search(r['texts'] + ' ' + r['calls'] + ' ' + r.get('class_tags', '')))
        ev_text = (r['texts'] + (' 〔' + r['class_tags'] + '〕' if r.get('class_tags') else ''))[:160]
        if exact:
            grade = 'A'
        elif r['cmd_names']:
            grade = 'B'
        elif d0 <= CHAIN or r['name_concat'] == '1':
            grade = 'C'
        elif d0 <= MAX_HOPS or hint:
            grade = 'D'
        else:
            grade = 'N'
        order = {'A': 0, 'B': 1, 'C': 2, 'D': 3, 'N': 4}
        src = r['source']
        for h in hx:
            e = agg[h]
            e['rows'] += 1
            if order[grade] < order[e['grade']]:
                e['grade'] = grade
                e['text'] = ev_text      # texts 列已经是“a | b | c”形式的字符串，不能再 join
                e['hops'] = d0 if d0 <= MAX_HOPS else ''
            if hint:
                e['strong'] = True
            if grade in ('A', 'B'):
                e['sso'] |= sso
                for m in re.finditer(r'0x([0-9a-f]{4})_(\d+)', r['cmd_names'].lower()):
                    if m.group(1) == h:
                        e['subs'].add(m.group(2))
            if r['origin'] == 'main':
                e['main'].add(f'{r["class"]}.{r["method"]}')
            else:
                e['mods'].add(re.sub(r'__\d+.*$', '', src).replace('.apk', ''))
            t = r['texts']
            if len(e['texts']) < 3:
                if t:
                    e['texts'].append(t[:150] + (' 〔' + r['class_tags'] + '〕' if r.get('class_tags') else ''))
                elif r.get('class_tags'):
                    e['texts'].append('〔类标〕' + r['class_tags'][:150])

    # “全 app 通吃”的位模式噪声：在≥ N 个无关页面里都出现，而且没有任何强发包语汇 → 判废
    by_mods = {h: len(e['mods']) for h, e in agg.items()}
    for h, e in agg.items():
        if e['grade'] in ('C', 'D') and by_mods[h] >= NOISE_MODULES and not e['strong']:
            e['grade'] = 'N'

    wl = set()
    wl_path = os.path.join(a.out, 'whitelist.txt')
    if os.path.exists(wl_path):
        wl = {m.group(1) for m in HEX.finditer(open(wl_path, encoding='utf-8').read().lower())}

    tsv = os.path.join(a.out, 'cmd_map.tsv')
    md = os.path.join(a.out, 'cmd_map.md')
    keys = sorted(agg, key=lambda h: (int(h, 16), h))
    with open(tsv, 'w', encoding='utf-8') as f, open(md, 'w', encoding='utf-8') as g:
        f.write('cmd\tgrade\tsso\tsubs\twhitelist\tknown\thops\tmain_callers\tmodules\tevidence_text\n')
        g.write('# 宠物 OIDB 命令语义总表（静态证据自动汇总）\n\n')
        g.write(f'等级：A=命令名+服务名同源；B=有命令名；C=≤{CHAIN} 跳走到发包点；'
                f'D=>{CHAIN} 跳或只有网络味文本（待核）；N=链上找不到也无文本（疑似噪声）。\n\n')
        g.write('| 命令 | 级 | 跳 | trpc 服务名 | 白名单 | 接入 | 出处 | 证据文本 |\n'
                '| --- | --- | --- | --- | --- | --- | --- | --- |\n')
        for h in sorted(agg, key=lambda x: ({'A': 0, 'B': 1, 'C': 2, 'D': 3, 'N': 4}[agg[x]['grade']],
                                            int(x, 16))):
            e = agg[h]
            srcs = ', '.join(sorted(e['main'])[:3]) or ', '.join(sorted(e['mods'])[:4])
            known_mark = '是' if h in known else ''
            f.write(f'0x{h}\t{e["grade"]}\t{";".join(sorted(e["sso"]))}\t'
                    f'{" ".join(sorted(e["subs"]))}\t{"在" if h in wl else "-"}\t{known_mark}\t{e["hops"]}\t'
                    f'{srcs}\t{",".join(sorted(e["mods"]))[:80]}\t{" || ".join(e["texts"])[:200]}\n')
            g.write(f'| `0x{h}` | {e["grade"]} | {e["hops"] or "-"} | {" / ".join(sorted(e["sso"]))} | '
                    f'{"在" if h in wl else "-"} | {known_mark} | {srcs} | '
                    f'{" &#124; ".join(e["texts"])[:150]} |\n')

    by_grade = defaultdict(int)
    for h in keys:
        by_grade[agg[h]['grade']] += 1
    hist = defaultdict(int)
    for h in keys:
        if agg[h]['grade'] in ('C', 'D'):
            hist[agg[h]['hops'] or '>6'] += 1
    print(f'链上跳数分布（C/D 级）: {dict(sorted(hist.items(), key=lambda kv: str(kv[0])))}')
    pet_wl = {h for h in wl if lo <= int(h, 16) <= hi}
    ab = {h for h in keys if agg[h]['grade'] in ('A', 'B')}
    print(f'命令号总数={len(keys)}  分级={dict(by_grade)}')
    print(f'libfekit 白名单宠物号段={len(pet_wl)}，其中有 A/B 级语义证据={len(pet_wl & ab)}')
    print(f'白名单外的新命令号={len(set(keys) - wl)}（说明白名单不是全集）')
    print(f'产出: {tsv}\n      {md}')


if __name__ == '__main__':
    main()
