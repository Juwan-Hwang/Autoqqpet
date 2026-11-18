"""在抓到的包里找指定数值出现在哪条命令的哪个字段——用来定位"力量/智力/魅力总量"到底在哪条回包里。

用法：
    python find_values.py 78 53 66            # 直接给屏幕上看到的三个属性值
    python find_values.py 78 53 66 --float    # 只按 float32 找（面板值常是 100.0 这种浮点）

两种编码都试：protobuf varint、fixed32 的 float32 小端字节；找到的行会打出处在哪条命令。
"""
import re
import struct
import subprocess
import sys

ADB = __import__('os').environ.get('ADB', 'adb')   # 不在 PATH 里就设环境变量 ADB

nums, only_float = [], '--float' not in sys.argv
for a in sys.argv[1:]:
    if a == '--float':
        only_float = True
    elif re.fullmatch(r'\d+(\.\d+)?', a):
        nums.append(float(a) if '.' in a else int(a))
if not nums:
    print(__doc__)
    sys.exit(0)


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return out.hex()


def needles(v):
    """一个数值可能的多种字节形态：varint（含 ×10/×100 放大）、float32、以及直接当文字出现的 ASCII。"""
    out = []
    iv = int(v) if float(v).is_integer() else None
    if iv is not None and not only_float:
        out.append(('varint', varint(iv)))
        for mult, nm in ((10, 'varint×10'), (100, 'varint×100'), (1000, 'varint×1000')):
            out.append((nm, varint(iv * mult)))
    out.append(('float32', struct.pack('<f', float(v)).hex()))
    out.append(('ascii', str(iv if iv is not None else v).encode().hex()))   # 面板值常写在文案/JSON 里
    return out


lines = subprocess.run([ADB, 'shell', 'logcat -d -s Autoqqpet/PKT'], capture_output=True,
                       text=True, encoding='utf-8', errors='replace').stdout.splitlines()
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

hits = 0
for line in lines:
    m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
    if not m:
        continue
    hexs = m.group(2)
    cmd = (re.search(r'OidbSvcTrpcTcp\.(0x[0-9a-f]{4}_\d+)', line) or
           re.search(r'\[([\w\u4e00-\u9fff]+) (?:发|应|请求|回)', line))
    tag = cmd.group(1) if cmd and cmd.lastindex == 1 else (cmd.group(0) if cmd else '?')
    kind = '响应' if ('回调入参' in line or '原生应' in line) else '请求'
    for v in nums:
        for enc, pat in needles(v):
            if pat in hexs:
                hits += 1
                pos = hexs.index(pat)
                print(f'  命中 {v} 作为 {enc}  →  {tag} {kind} 偏移≈{pos//2} 字节  行首 {line[:14]}')
                break
print(f'\n扫了 {len(lines)} 行抓包，命中 {hits} 处。')
if not hits:
    print('没命中：说明这些值不在当前通道能看到的那些包里（或还没抓到对应命令——先去把属性面板那一屏打开）。')
