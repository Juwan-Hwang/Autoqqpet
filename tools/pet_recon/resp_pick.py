"""看指定命令的回包全字段（用于找"+6 经验"落在哪个字段），并全局搜一次“经验”字样。

用法: python _resp_pick.py 0x992d 0x9bf3 0x9b80 0x99ef
"""
import re
import subprocess
import sys

sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
from capture import varint, walk

ADB = __import__('os').environ.get('ADB', 'adb')   # 不在 PATH 里就设环境变量 ADB
WANT = tuple(w.lower() for w in sys.argv[1:]) or ('0x992d', '0x9bf3', '0x9b80', '0x99ef')
EXP = 'e7bb8fe9aa88'          # “经验”的 UTF-8 字节
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

lines = subprocess.run([ADB, 'shell', 'logcat -d -s Autoqqpet/PKT'], capture_output=True,
                       text=True, encoding='utf-8', errors='replace').stdout.splitlines()

seen = set()
for line in lines:
    m = re.search(r'bin\((\d+)\)=([0-9a-f]+)', line)
    if not m:
        continue
    hexs = m.group(2)
    if EXP in hexs:
        print('!! 含“经验”字样的行：', line[6:18], line[40:80])
    if '回调入参' not in line or len(hexs) < 6:
        continue
    b = bytes.fromhex(hexs)
    cmd, _ = varint(b, 1)
    name = f'0x{cmd:04x}'
    if name not in WANT:
        continue
    key = (name, m.group(1))
    if key in seen:
        continue
    seen.add(key)
    print(f'\n===== 回包 {name}  声明 {m.group(1)} 字节  时刻 {line[6:18]}')
    for p, t, v in walk(b)[1:26]:
        print(f'   {p:14s} {t:5s} {str(v)[:78]}')
