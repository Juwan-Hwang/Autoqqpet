"""扫动态包：对 bundles/extracted 里每个“小 APK”做与 scan_apk 相同的方法级证据提取。

    python scan_bundles.py

产出：<out>/bundles.tsv（列与 main.tsv 一致）。
动态包被 R8 改过名，所以命令号多以**整数**出现，语义要从残留的 UI 文案/日志/埋点名里读。
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recon_lib as R

DEFAULT_OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                           '..', '..', '..', '_scratch', 'pet_recon'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=DEFAULT_OUT)
    ap.add_argument('--dexdump', default=None)
    a = ap.parse_args()
    dexdump = R.find_dexdump(a.dexdump)
    tmp = R.tmp_dex_path()

    paths = []
    for sub in ('bundles', 'extracted'):
        root = os.path.join(a.out, sub)
        for dp, _d, fs in os.walk(root):
            for f in fs:
                if f.endswith(('.apk', '.zip', '.res', '.dex')):
                    paths.append(os.path.join(dp, f))
    paths = sorted(set(paths))
    apks = [p for p in paths if p.endswith('.apk')]
    if apks:
        # 解包出来的小 APK 就是 kuikly_dynamic.zip 里的同一内容；
        # 两边都扫会双计证据，而且同一页面会以两个名字计入模块，把“跨页面噪声”判不准。
        paths = apks
    print(f'待扫容器 {len(paths)} 个', file=sys.stderr)

    rows = []
    edges = []
    for i, p in enumerate(paths):
        got = list(R.scan_container(p, dexdump, tmp, None, edges))
        for src, dex, evs in got:
            rows.append((os.path.basename(p), dex, evs))
        print(f'[{i + 1}/{len(paths)}] {os.path.basename(p)} 累计={len(rows)} 边={len(edges)}',
              file=sys.stderr, flush=True)

    R.write_tsv(os.path.join(a.out, 'bundles.tsv'), rows)
    R.write_edges(os.path.join(a.out, 'bundles_edges.tsv'), edges)
    os.remove(tmp)


if __name__ == '__main__':
    main()
