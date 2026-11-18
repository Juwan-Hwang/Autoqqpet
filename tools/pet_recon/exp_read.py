"""把 0x9abe（每日经验总览）的回包按子任务摊开：每项的 key/名称/各字段值，并列出多次采样的前后差。

用法: python exp_read.py
"""
import re
import struct
import subprocess
import sys

ADB = __import__('os').environ.get('ADB', 'adb')   # 不在 PATH 里就设环境变量 ADB
from capture import merge_chunks      # 拆行的长包先拼回
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def varint(b, i):
    shift = val = 0
    while i < len(b):
        x = b[i]; i += 1
        val |= (x & 0x7F) << shift
        if not x & 0x80:
            break
        shift += 7
    return val, i


def fields(b):
    """返回 {字段号: [值...]}，只解 varint / 长度定界。"""
    out, i = {}, 0
    while i < len(b):
        key, i = varint(b, i)
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = varint(b, i)
            out.setdefault(fn, []).append(v)
        elif wt == 2:
            ln, i = varint(b, i)
            raw = b[i:i + ln]; i += ln
            try:
                s = raw.decode('utf-8')
                out.setdefault(fn, []).append(s if s and all(31 < ord(c) or c in '\n\t' for c in s) else raw[:12])
            except UnicodeDecodeError:
                out.setdefault(fn, []).append(raw[:12])
        elif wt == 5:
            out.setdefault(fn, []).append(struct.unpack('<f', b[i:i + 4])[0]); i += 4
        else:
            break
    return out


lines = '\n'.join(merge_chunks(subprocess.run([ADB, 'shell', 'logcat -d -s Autoqqpet/PKT'], capture_output=True,
                         text=True, encoding='utf-8', errors='replace').stdout.splitlines())).splitlines()
n = 0
for line in lines:
    if '回调入参' not in line:
        continue
    m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
    if not m:
        continue
    b = bytes.fromhex(m.group(2))
    cmd, _ = varint(b, 1)
    if cmd != 0x9abe:
        continue
    top = fields(b)
    n += 1
    # 外层是 OIDB envelope：f1=命令号 f2=errorCode f3=0 f4=真正的 body（里面才是经验与子任务）
    body = b
    items = []
    i = 0
    while i < len(b):
        key, i = varint(b, i)
        fn, wt = key >> 3, key & 7
        if wt == 2:
            ln, i = varint(b, i)
            raw = b[i:i + ln]; i += ln
            if fn == 4:
                body = raw
                inner = fields(raw)
                print(f'\n=== 第 {n} 次采样  时刻 {line[6:18]}   今日经验 f2={inner.get(2)}   上限 f3={inner.get(3)}   f1={inner.get(4)}')
                items.append(inner)
        elif wt == 0:
            _, i = varint(b, i)
        elif wt == 5:
            i += 4
        else:
            break
    # 再拆一层：body 里的 f4 才是 repeated 子任务
    subs = []
    i = 0
    while i < len(body):
        key, i = varint(body, i)
        fn, wt = key >> 3, key & 7
        if wt == 2:
            ln, i = varint(body, i)
            raw = body[i:i + ln]; i += ln
            if fn == 4:
                subs.append(fields(raw))
        elif wt == 0:
            _, i = varint(body, i)
        elif wt == 5:
            i += 4
        else:
            break
    for f in subs:
        if not (f.get(1) and isinstance(f[1][0], str)):
            continue
        print(f"  {f[1][0]:18s} {str(f.get(2, [''])[0])[:18]:20s} "
              f"f3={f.get(3)} f4={f.get(4)} f5={f.get(5)} f10={f.get(10)} f11={f.get(11)} f12={f.get(12)}")
        if f.get(13):
            print(f"        规则: {f[13][0]}")
