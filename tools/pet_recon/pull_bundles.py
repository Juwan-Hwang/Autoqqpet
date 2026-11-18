"""从真机拉宠物页面动态包（Kuikly 下发的“小 APK”，里面才是页面业务代码）。

    python pull_bundles.py            # 需要 adb 可见、设备已 Root
    python pull_bundles.py --grep "pet|craft|qqgy" --extract

产出：<out>/bundles/<包名>__<版本>__<文件名>，--extract 时再解出 <out>/extracted/。
说明：QQ 只在下发过某个页面后才在机器上留有该页面代码，
所以第一次跑之前，最好把宠物相关页面在手机上逐个点开一遍。
"""
import argparse
import base64
import os
import re
import shutil
import subprocess
import sys
import zipfile

DEFAULT_ADB = os.environ.get('ADB', 'adb')
ZRES_GLOB = '/data/data/com.tencent.mobileqq/files/ZRes'


def adb(bin_, args):
    return subprocess.run([bin_] + args, capture_output=True).stdout


def su(bin_, cmd, exec_out=False):
    """整条 `su -c "..."` 必须作为**一个** argv 递交，
    否则设备端 shell 只会把第一个词当成 su 的命令，其余变成位置参数（曾踩：find 跑到了 /）。"""
    args = ['exec-out' if exec_out else 'shell', f'su -c "{cmd}"']
    return adb(bin_, args)


def find_res_dir(bin_):
    """定位 .../ZRes/online/<hash>/1/res（可能有多个 hash 目录，取文件最多的那个）。"""
    out = su(bin_, f'ls -d {ZRES_GLOB}/online/*/1/res').decode('utf-8', 'replace').split()
    dirs = [d.strip() for d in out if d.strip().startswith('/') and 'No such file' not in d]
    if not dirs:
        sys.exit(f'没找到 {ZRES_GLOB}/online/*/1/res；先确认设备已 Root、QQ 登录并打开过宠物页')
    return max(dirs, key=lambda d: len(su(bin_, f'ls {d}')))


def pull(bin_, remote, local):
    if os.path.exists(local) and os.path.getsize(local) > 0:
        return os.path.getsize(local)
    blob = re.sub(rb'\s+', b'', su(bin_, f'base64 {remote}', exec_out=True))
    if not blob:
        return 0
    blob += b'=' * (-len(blob) % 4)
    data = base64.b64decode(blob)
    with open(local, 'wb') as f:
        f.write(data)
    return len(data)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--adb', default=DEFAULT_ADB)
    ap.add_argument('--res', default=None, help='留空则自动探测 ZRes/online/*/1/res')
    ap.add_argument('--grep', default=r'pet|craft|qqgy',
                    help='只拉匹配的页面包名（正则，匹配目录名）')
    ap.add_argument('--all-versions', action='store_true', help='每个包拉全部版本目录')
    ap.add_argument('--extract', action='store_true', help='顺手把 zip/APK 解出来')
    ap.add_argument('--out', default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                 '..', '..', '..', '_scratch', 'pet_recon'))
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    bdir = os.path.join(a.out, 'bundles')
    os.makedirs(bdir, exist_ok=True)
    pat = re.compile(a.grep)

    res = a.res or find_res_dir(a.adb)
    print(f'res 目录: {res}', file=sys.stderr)
    listing = su(a.adb, f'ls {res}').decode('utf-8', 'replace').split()
    picked = [b for b in listing if pat.search(b)]
    print(f'匹配到 {len(picked)} 个页面包: {", ".join(picked)}', file=sys.stderr)

    got = []
    for b in picked:
        vers = sorted((int(v) for v in su(a.adb, f'ls {res}/{b}')
                       .decode('utf-8', 'replace').split() if v.isdigit()), reverse=True)
        if not vers:
            continue
        for v in (vers if a.all_versions else vers[:1]):
            files = [f for f in su(a.adb, f'find {res}/{b}/{v} -type f')
                     .decode('utf-8', 'replace').split() if not f.endswith('.lock')]
            for remote in files:
                rel = remote.rsplit(f'{b}/{v}/', 1)[-1].replace('/', '__')
                local = os.path.join(bdir, f'{b}__{v}__{rel}')
                n = pull(a.adb, remote, local)
                got.append(local)
                print(f'{b} v{v} / {rel}: {n} 字节', file=sys.stderr)

    if a.extract:
        xdir = os.path.join(a.out, 'extracted')
        shutil.rmtree(xdir, ignore_errors=True)
        for p in got:
            try:
                z = zipfile.ZipFile(p)
            except Exception:
                continue
            tag = os.path.splitext(os.path.basename(p))[0]
            z.extractall(os.path.join(xdir, tag))
        print(f'解包目录: {xdir}', file=sys.stderr)
    print(f'共 {len(got)} 个文件 → {bdir}', file=sys.stderr)


if __name__ == '__main__':
    main()
