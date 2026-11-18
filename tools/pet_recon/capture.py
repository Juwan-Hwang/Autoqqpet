"""抓包辅助：开关模块内的抓包、读回结果、按命令号归并并解出请求/响应字段。

用法：
    python capture.py on                     打开抓包（QQ 进程内，默认关）
    python capture.py off                    关闭
    python capture.py status                 看当前状态与已装 hook 数
    python capture.py peek [--all]           读 logcat，汇总这一屏抓到的命令号并解字段
                                            （默认只列 known_cmds.txt 里没有的**新号**）

前提：模块已装进 QQ 且 QQ 在跑；包体在模块侧截到 1400 字节，超大响应会看到截断标记。
"""
import argparse
import os
import re
import subprocess
import sys

ADB = os.environ.get('ADB', 'adb')   # 不在 PATH 里就设环境变量 ADB=<adb 可执行文件路径>
HERE = os.path.dirname(os.path.abspath(__file__))
KNOWN = os.path.join(HERE, 'known_cmds.txt')
MARK = os.path.join(HERE, 'peek_mark.txt')        # 上次 peek 到的时间戳，做“这一段新出了什么”的增量
BASE = os.path.join(HERE, 'baseline.txt')         # 开抓那一刻的**手机侧**时间，raw 默认用它比
BROADCAST = ('am broadcast -p com.tencent.mobileqq -a io.github.juwanhwang.autoqqpet.CMD '
             '--es action packet --es op {op}')
CMD_RE = re.compile(r'\[sendPbRequest 请求\] (OidbSvcTrpcTcp\.0x[0-9a-f]{4}_\d+)')


def adb_shell(remote):
    r = subprocess.run([ADB, 'shell', 'su -c "' + remote + '"'], capture_output=True,
                       text=True, encoding='utf-8', errors='replace')
    return (r.stdout or '').strip()


def as_int(v):
    """walk() 把 varint 的值以**字符串**返回，脚本里要数值时走这里（别直 isinstance(v, int)，会全 False）。"""
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def varint(b, i):
    shift = val = 0
    while i < len(b):
        x = b[i]
        i += 1
        val |= (x & 0x7F) << shift
        if not x & 0x80:
            break
        shift += 7
    return val, i


def walk(b, depth=0, path=''):
    """把 protobuf 展平成 (字段路径, 类型, 值)。解不出子消息就当字节/字符串。"""
    out, i = [], 0
    while i < len(b) and depth < 4:
        key, i = varint(b, i)
        fn, wt = key >> 3, key & 7
        p = f'{path}.{fn}'
        if wt == 0:
            v, i = varint(b, i)
            out.append((p, 'varint', str(v)))
        elif wt == 2:
            ln, i = varint(b, i)
            raw = b[i:i + ln]
            i += ln
            try:
                s = raw.decode('utf-8')
                if s and all(31 < ord(c) or c in '\n\t' for c in s):
                    out.append((p, 'str', s[:90]))
                    continue
            except UnicodeDecodeError:
                pass
            inner = walk(raw, depth + 1, p) if raw else []
            out.extend(inner if len(inner) >= 2 else [(p, 'bytes', raw.hex()[:48])])
        elif wt in (1, 5):
            n = 8 if wt == 1 else 4
            out.append((p, 'fixed', raw_hex(b[i:i + n])))
            i += n
        else:
            break
    return out


def raw_hex(b):
    return ''.join(f'{x:02x}' for x in b)


def body_of(line):
    m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
    return bytes.fromhex(m.group(2)) if m else b''


def merge_chunks(lines):
    """把模块拆行发的长包拼回一行（行首标 `[[i/n]]`）。

    模块侧 logcat 单条约 4K 字符上限，超长的包体被拆成多行，不拼回来就解不出完整回包。
    """
    out, buf, total, idx = [], None, 0, 0
    dropped = 0
    for line in lines:
        m = re.search(r'PKT: \[\[(\d+)/(\d+)\]\] ', line)
        if not m:
            if buf:
                if idx == total:
                    out.append(buf)
                else:
                    dropped += 1      # 没收满就断了：整组丢弃，不能把半条包当完整包交下去（曾因此拼出奇数 hex）
                buf = None
            out.append(line)
            continue
        i, n = int(m.group(1)), int(m.group(2))
        if i == 1:
            if buf and idx != total:
                dropped += 1          # 上一组还没收完就来了新消息：块交错，丢旧的
            buf = line.replace(f'[[1/{n}]] ', '', 1)
            total, idx = n, 1
        elif buf and i == idx + 1:
            buf += line[line.find(']] ', line.find('[[')) + 3:]
            idx = i
        elif buf:
            dropped += 1              # 序号不连续，这组已经废了
            buf = None
        if buf and idx == total:
            out.append(buf)
            buf = None
    if buf:
        dropped += 1
    if dropped:
        out.append(f'Autoqqpet/PKT: [警告] 有 {dropped} 组长包拼不完整（块交错或额度截断），已丢弃、未混入解析')
    return out


def pkt_lines():
    """读 logcat 里的 PKT 行（已经拼回长包）。其他脚本也从这里取，免得各自忘记拼包。"""
    raw = subprocess.run([ADB, 'shell', 'logcat -d -s Autoqqpet/PKT'], capture_output=True,
                         text=True, encoding='utf-8', errors='replace').stdout.splitlines()
    return merge_chunks(raw)


def peek(show_all, out_file=None):
    """默认只看**上次 peek 之后**的增量（一个一个动作点时才能分清哪条对应哪个动作）；
    加 --all 则看整屏 logcat。控制台会折行/截断，需要完整结果时用 --out 写 UTF-8 文件。"""
    lines = pkt_lines()
    last = ''
    if not show_all and os.path.exists(MARK):
        last = open(MARK, encoding='utf-8').read().strip()
    if last:
        seen = [l for l in lines if l[:18] > last]
    else:
        seen = lines
    if lines:
        open(MARK, 'w', encoding='utf-8').write(lines[-1][:18])
    known = set()
    if os.path.exists(KNOWN):
        known = set(open(KNOWN, encoding='utf-8').read().split())
    req, resp, times = {}, {}, {}
    for line in seen:
        m = CMD_RE.search(line)
        if m:
            c = m.group(1)
            req.setdefault(c, body_of(line))
            times.setdefault(c, line[:18])
            continue
        if '回调入参' in line:
            b = body_of(line)
            if len(b) > 2:
                cmd, _ = varint(b, 1)          # 响应 field1 = 命令号
                resp.setdefault(f'OidbSvcTrpcTcp.0x{cmd:04x}_?', []).append(b)

    fresh = [c for c in req if c not in known]
    lines_out = []
    lines_out.append(f'增量 {len(seen)} 行（整屏 {len(lines)} 行）；命令号 {len(req)} 个，其中首次出现 {len(fresh)} 个；响应 {sum(len(v) for v in resp.values())} 条')
    todo = list(req)
    for c in sorted(todo):
        lines_out.append(f'\n===== {c}   首次 {times.get(c, "")}   {"新号" if c in fresh else "已记录"}')
        for p, t, v in walk(req[c])[:18]:
            lines_out.append(f'  请求 {p:12s} {t:6s} {v}')
        for c2, bodies in resp.items():
            if c2.split('_')[0] == c.split('_')[0]:
                for p, t, v in walk(bodies[-1])[1:14]:      # 跳过 field1（命令号）
                    lines_out.append(f'  响应 {p:12s} {t:6s} {v}')
                break
    if out_file:
        with open(out_file, 'w', encoding='utf-8') as f:
            f.write('\n'.join(lines_out) + '\n')
        print(f'完整结果已写 {out_file}（{len(lines_out)} 行）')
    else:
        for ln in lines_out:
            print(ln[:220])
    if not todo:
        print('（这一段没有发包：没点到、被额度截掉，或该动作走的是另一个入口）')
    with open(KNOWN, 'a', encoding='utf-8') as f:
        for c in req:
            if c not in known:
                f.write(c + '\n')


MONTHS = {m: i + 1 for i, m in enumerate(
    ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'])}


def device_now():
    """取**手机侧**时间，返 "MM-DD HH:MM:SS"（拿不到则空串）。

    logcat 的时间戳是手机给的，而 PC 与手机能差一分多钟（2026-10-15 实测差 1分34秒）
    → 基线必须用这个取，别用 PC 的 Get-Date，否则 `--since` 窗口会错开。
    """
    parts = adb_shell('date').split()          # Thu Oct 15 02:32:21 CST 2026  → 月=1 日=2 时间=3
    if len(parts) >= 4 and parts[1] in MONTHS:
        return f'{MONTHS[parts[1]]:02d}-{parts[2]} {parts[3]}'
    return ''


def raw(since):
    """逐条列出请求（**不去重**），并展开 body 字段。

    peek 按命令号归并，只能看“发过哪些命令”；要看“同一条命令连若干次、每次参数 differ”（买不同物品、多次喂食）
    只能用这个。since 形如 "10-15 02:29:52"（跨 0 点必须带日期）；**不给就自动用 `capture.py on` 记下的手机基线**。
    """
    n = 0
    if not since and os.path.exists(BASE):
        since = open(BASE, encoding='utf-8').read().strip()
    lines = pkt_lines()
    for line in lines:
        # 跨 0 点时光比 "23:59" > "00:03" 会误判，所以比“日-时:分:秒”整段（形如 10-15 00:03:20）
        stamp = line[0:15]
        if since and stamp <= since:
            continue
        if '请求]' not in line and '原生发' not in line:
            continue
        m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
        if not m:
            continue
        cmd = re.search(r'(0x[0-9a-f]{4}_\d+)', line)
        fields = [f'{p}={v}' for p, t, v in walk(bytes.fromhex(m.group(2)))[1:9]]
        n += 1
        print(f'{stamp}  {(cmd.group(1) if cmd else "?"):10s} {"; ".join(fields)[:220]}')
    print(f'— 共 {n} 条请求' + (f'（{since} 之后）' if since else '（整屏）'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('cmd', choices=['on', 'off', 'status', 'peek', 'raw'])
    ap.add_argument('--all', action='store_true')
    ap.add_argument('--out', default=None, help='把完整结果写到该文件（UTF-8）')
    ap.add_argument('--since', default='', help='raw 模式：只看该时刻之后，形如 "10-15 00:03:20"（跨 0 点必须带日期）')
    a = ap.parse_args()
    if a.cmd == 'peek':
        peek(a.all, a.out)
    elif a.cmd == 'raw':
        try:
            sys.stdout.reconfigure(encoding='utf-8', errors='replace')
        except Exception:
            pass
        raw(a.since)
    else:
        print(adb_shell(BROADCAST.format(op=a.cmd))[:200])
        if a.cmd == 'on':                      # 开抓就把手机侧基线记下来，raw 会默认用它
            now = device_now()
            if now:
                open(BASE, 'w', encoding='utf-8').write(now)
                print(f'基线（手机时钟，raw 默认用这个）: {now}')
            else:
                print('⚠ 拿不到手机时间，基线未记 —— raw --since 要手工给')


if __name__ == '__main__':
    main()
