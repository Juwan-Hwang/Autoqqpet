"""把动态包某个类的方法体打出来（含常量与调用），用于顺链路追命令号。

用法: python _class_body.py <apk> <类描述符正则> [方法名正则]
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

DEXDUMP = os.environ.get('DEXDUMP', 'dexdump')   # Android build-tools 里的 dexdump；不在 PATH 就设环境变量 DEXDUMP
APK, CPAT = sys.argv[1], re.compile(sys.argv[2])
MPAT = re.compile(sys.argv[3]) if len(sys.argv) > 3 else None

cls_re = re.compile(r"^\s*Class descriptor\s+: '([^']+)'")
mth_re = re.compile(r"^[0-9a-f]{6}:\s+\|\[[0-9a-f]{6}\]\s+(\S+)\.(\S+?):(\S+)$")
ins_re = re.compile(r"^[0-9a-f]{6}:\s+[0-9a-f ]*\|\s*[0-9a-f]{4}:\s+(.*)$")

tmp = os.path.join(tempfile.gettempdir(), '_cb.dex')
if open(APK, 'rb').read(4) == b'dex\n':
    # 允许直接传裸 dex（从 APK 里抽出来的单个 classesN.dex）
    names = ['<raw>']
    blobs = {names[0]: open(APK, 'rb').read()}
    z = None
else:
    z = zipfile.ZipFile(APK)
    names = [n for n in z.namelist() if n.endswith('.dex')]
    blobs = None
for dn in names:
    data = blobs[dn] if blobs else z.read(dn)
    with open(tmp, 'wb') as f:
        f.write(data)
    proc = subprocess.Popen([DEXDUMP, '-d', tmp], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True,
                            encoding='utf-8', errors='replace', bufsize=1 << 20)
    keep = False
    show = False
    for line in proc.stdout:
        line = line.rstrip('\n')
        m = cls_re.match(line)
        if m:
            keep = bool(CPAT.search(m.group(1)))
            if keep:
                print(f'\n##### {m.group(1)}')
            continue
        if not keep:
            continue
        m = mth_re.match(line)
        if m:
            name = m.group(2)
            show = not MPAT or bool(MPAT.search(name))
            if show:
                print(f'\n--- {name}:{m.group(3)}')
            continue
        if not show:
            continue
        m = ins_re.match(line)
        if m:
            code = m.group(1)
            if ('const' in code) or ('invoke' in code) or ('iput' in code) or ('sget' in code):
                print('   ', code[:150])
    proc.wait()
os.remove(tmp)
