"""通用回包展开器：按命令号从抓包日志里取回包，打印结构、varint 分布与文本。

用法：
    python resp_dump.py 0x9b87              # 单条
    python resp_dump.py 0x9b87 0x985e       # 多条
    python resp_dump.py 0x9b87 --rec .4.1   # 指定“记录起始路径”逐条切开

为什么需要它：控制台（PowerShell）会把中文转成乱码，所以结果一律写 UTF-8 文件再读。
拼包与 varint 取值都走 capture 里的现成函数（capture.as_int），别再手写 isinstance(v, int)。
"""
import collections
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from capture import pkt_lines, walk, as_int

OUT_DIR = os.path.dirname(os.path.abspath(__file__))


def find_responses(targets, newest=False):
    """回包 body 的 field1 varint 就是命令号，据此配对。

    默认取“最大的一条”（适合看结构全量）；要“刚刚那次”的必须用 newest（按时间戳取后到），
    否则“领完奖刷新列表”这类前后对比会被旧包挡住。
    """
    best = {}
    for line in pkt_lines():
        if '原生应' not in line and '回调入参' not in line:
            continue
        m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
        if not m:
            continue
        h = m.group(2)
        h = h[:-1] if len(h) % 2 else h          # 奇数说明拼包不完整（capture 会另行告警）
        b = bytes.fromhex(h)
        if len(b) < 3 or b[0] != 0x08:
            continue
        n, i, shift = 0, 1, 0
        while i < len(b) and shift <= 42:
            byte = b[i]
            n |= (byte & 0x7F) << shift
            i += 1
            shift += 7
            if not byte & 0x80:
                break
        key = f'0x{n:04x}'
        if key not in targets:
            continue
        prev = best.get(key)
        if prev is None:
            best[key] = (line, b)
        elif newest:
            if line[0:18] >= prev[0][0:18]:      # 比“日-时:分:秒.毫秒”前段，跨 0 点也安全
                best[key] = (line, b)
        elif len(b) > len(prev[1]):
            best[key] = (line, b)
    return best


def dump(key, line, b, rec):
    out = os.path.join(OUT_DIR, f'resp_{key[2:]}.txt')
    say = lambda *a: print(*a, file=fp)
    fp = open(out, 'w', encoding='utf-8')
    flat = walk(b)
    say(f'{key} 回包 {len(b)} 字节（时刻 {line[6:18]}），walk 产出 {len(flat)} 项')
    if rec:
        # walk() 只输出叶子路径（不会有中间层的行），所以 --rec 要给“每条记录恰好出现一次”的叶子路径，
        # 例如 .4.1.10.10；记录前缀由它去掉最后两级得到。
        parent = rec.rsplit('.', 2)[0] or '.4.1'
        say(f'\n以叶子路径 {rec} 为分条标记（记录前缀 {parent}）：')
        recs, cur = [], None
        for p, t, v in flat:
            if p == rec:
                cur = []
                recs.append(cur)
            if cur is None or not p.startswith(parent):
                continue
            cur.append((p[len(parent):], t, v))
        say(f'共 {len(recs)} 条记录')
        for k, items in enumerate(recs, 1):
            nums = ' '.join(f'{p}={as_int(v)}' for p, t, v in items if t == 'varint' and (as_int(v) or 0) <= 400)
            # 文本不要截短：火花的状态语义全在后半句（“今日还没互踩哦”/“今日互踩即可重燃”/“小火苗升腾中”）
            strs = ' '.join(f'{p}={str(v)[:110].replace(chr(10), "⏎")}'
                             for p, t, v in items if t == 'str' and not str(v).startswith('http'))
            say(f'#{k:2d} {nums[:120]}')
            say(f'    {strs[:400]}')
    else:
        seen = set()
        say('\n顶层与二级字段：')
        for p, t, v in flat:
            if p.count('.') <= 2 and p not in seen:
                seen.add(p)
                say(f'  {p:12s} {t:6s} {str(v)[:70]}')
    say('\nvarint 取值分布：')
    cnt = collections.Counter()
    for p, t, v in flat:
        x = as_int(v) if t == 'varint' else None
        if x is not None and x <= 200:
            cnt[(p, x)] += 1
    for (p, x), c in sorted(cnt.items()):
        say(f'  {p:18s} = {x:4d}  出现 {c} 次')
    say('\n文本样本（前 24 条，跳过 URL/编码串）：')
    k = 0
    for p, t, v in flat:
        s = str(v)
        if t == 'str' and len(s) < 40 and not re.match(r'^(https?:|MT|Nz|Mj|[A-Za-z0-9+/=]{25,})', s):
            say(f'  {p:18s} {s}')
            k += 1
            if k >= 24:
                break
    fp.close()
    print(out)
    return out


def main():
    args = [a for a in sys.argv[1:]]
    rec = None
    newest = '--newest' in args
    if newest:
        args.remove('--newest')
    if '--rec' in args:
        i = args.index('--rec')
        rec = args[i + 1]
        del args[i:i + 2]
    targets = {a.lower() if a.startswith('0x') else f'0x{a.lower()}' for a in args}
    best = find_responses(targets, newest)
    if not best:
        print('这些命令在本窗口的抓包日志里没有回包：', ' '.join(sorted(targets)))
        print('（确认抓包是开的：python capture.py status；必要时让用户在界面上点一下）')
        return
    for key in sorted(best, reverse=True):
        line, b = best[key]
        dump(key, line, b, rec)
    miss = sorted(targets - set(best))
    if miss:
        print('没抓到：', ' '.join(miss))


if __name__ == '__main__':
    main()
