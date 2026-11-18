"""把指定命令的响应全字段展开，并把 fixed4 同时按 float32 解释——三属性总量可能是浮点，旧解析把它当整数看了。

用法: python _decode_attrs.py [命令号 ...]   默认看好友宠物相关的几条
"""
import re
import struct
import subprocess
import sys

from capture import merge_chunks      # 模块侧会把超长包拆成多行，先拼回

ADB = __import__('os').environ.get('ADB', 'adb')   # 不在 PATH 里就设环境变量 ADB
WANT = sys.argv[1:] or ['0x96f2', '0x9c1b', '0x9c71', '0x9dd2', '0x9ad4', '0x9959', '0x994b', '0x95e1']

out = '\n'.join(merge_chunks(subprocess.run(
    [ADB, 'shell', 'logcat -d -s Autoqqpet/PKT'], capture_output=True,
    text=True, encoding='utf-8', errors='replace').stdout.splitlines()))
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


def walk(b, depth=0, path=''):
    res, i = [], 0
    while i < len(b) and depth < 5:
        key, i = varint(b, i)
        fn, wt = key >> 3, key & 7
        p = f'{path}.{fn}'
        if wt == 0:
            v, i = varint(b, i)
            res.append((p, 'i', str(v)))
        elif wt == 5:
            raw = b[i:i + 4]; i += 4
            f = struct.unpack('<f', raw)[0] if len(raw) == 4 else float('nan')
            res.append((p, 'f32', f'{f:g}  [{raw.hex()}]'))
        elif wt == 1:
            raw = b[i:i + 8]; i += 8
            res.append((p, 'f64', f'{struct.unpack("<d", raw)[0]:g}'))
        elif wt == 2:
            ln, i = varint(b, i)
            raw = b[i:i + ln]; i += ln
            try:
                s = raw.decode('utf-8')
                if s and all(31 < ord(c) or c in '\n\t' for c in s):
                    res.append((p, 'str', s[:70]))
                    continue
            except UnicodeDecodeError:
                pass
            inner = walk(raw, depth + 1, p) if raw else []
            res.extend(inner if len(inner) >= 2 else [(p, 'bytes', raw.hex()[:40])])
        else:
            break
    return res


lines = out.splitlines()
for line in lines:
    if '回调入参' not in line:
        continue
    m = re.search(r'bin\(\d+\)=([0-9a-f]+)', line)
    if not m:
        continue
    b = bytes.fromhex(m.group(1))
    if len(b) < 3:
        continue
    cmd, _ = varint(b, 1)
    hit = [w for w in WANT if w.lower() == f'0x{cmd:04x}']
    if not hit:
        continue
    print(f'\n===== 响应 0x{cmd:04x}  ({len(b)} 字节, 行内声明 {m.group(1).__len__()//2})   {line[:14]}')
    for p, t, v in walk(b)[1:40]:
        print(f'  {p:16s} {t:5s} {v}')
