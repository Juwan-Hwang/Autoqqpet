"""扫主 APK：把每条宠物命令的「命令名串 / trpc 服务名 / 整数命令号 / 调用方」落成方法级证据。

    python scan_apk.py --apk "<QQ 安装包路径>/qq_9375.apk"

产出（默认写到 --out，缺省 <项目根>/_scratch/pet_recon）：
    main.tsv          每行一个方法，含证据（见 recon_lib.ROW_HEADER）
    whitelist.txt     libfekit.so 里的完整命令名清单（SSO 网关白名单，不是命令全集）
"""
import argparse
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recon_lib as R

DEFAULT_OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                           '..', '..', '..', '_scratch', 'pet_recon')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--apk', required=True, help='QQ 安装包路径')
    ap.add_argument('--out', default=os.path.abspath(DEFAULT_OUT))
    ap.add_argument('--dexdump', default=None)
    ap.add_argument('--so', default='lib/arm64-v8a/libfekit.so',
                    help='抽命令名白名单的 native 条目；传 none 跳过')
    ap.add_argument('--pkg', default=r'^Lcom/tencent/(ergo|mobileqq/qqpet|qqnt/qqpet)/',
                    help='只留匹配的类描述符（默认只看宠物引擎 ergo + 宠物宿主 qqpet）；传 .* 则全量扫')
    a = ap.parse_args()

    os.makedirs(a.out, exist_ok=True)
    dexdump = R.find_dexdump(a.dexdump)
    keep = None if a.pkg == '.*' else re.compile(a.pkg)
    tmp = R.tmp_dex_path()
    z = zipfile.ZipFile(a.apk)
    entries = [n for n in sorted(z.namelist()) if n.endswith('.dex')]
    print(f'{a.apk}: {len(entries)} 个 dex，dexdump={dexdump}', file=sys.stderr)

    rows = []
    edges = []
    for i, n in enumerate(entries):
        evs = R.scan_dex_bytes(z.read(n), dexdump, tmp, keep, edges)
        rows.append((os.path.basename(a.apk), n, evs))
        print(f'[{i + 1}/{len(entries)}] {n} 累计证据方法={sum(len(x[2]) for x in rows)} 边={len(edges)}',
              file=sys.stderr, flush=True)

    R.write_tsv(os.path.join(a.out, 'main.tsv'), rows)
    R.write_edges(os.path.join(a.out, 'main_edges.tsv'), edges)

    if a.so and a.so != 'none' and a.so in z.namelist():
        names = sorted(set(R.cmd_names_in_blob(z.read(a.so))),
                       key=lambda s: (int(s.split('0x')[1].split('_')[0], 16), s))
        p = os.path.join(a.out, 'whitelist.txt')
        open(p, 'w', encoding='utf-8').write('\n'.join(names) + '\n')
        print(f'写出 {p}（{len(names)} 个命令名）', file=sys.stderr)
    os.remove(tmp)


if __name__ == '__main__':
    main()
