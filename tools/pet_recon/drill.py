"""钻取：在已生成的 main.tsv / bundles.tsv 里按命令号、类名或文案关键字查证据行。

    python drill.py --cmd 0x975e              # 谁在用这条命令
    python drill.py --text 召回               # 反查：哪段代码提到「召回」
    python drill.py --class "La6/m1;"         # 列出一个类的全部证据方法
    python drill.py --cmd 0x975e --source pet_play.apk

只做读取，不改任何 tsv；输出的 `texts` 列就是用来判功能的那段 UI 文案/日志。
"""
import argparse
import os
import re
import sys

DEFAULT_OUT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                           '..', '..', '..', '_scratch', 'pet_recon'))
COLS = ['source', 'dex', 'class', 'method', 'sig', 'cmd_names', 'sso_names',
        'pet_ints', 'name_concat', 'calls', 'texts', 'class_tags']


def load(out):
    for sub in ('main.tsv', 'bundles.tsv'):
        p = os.path.join(out, sub)
        if not os.path.exists(p):
            print(f'缺 {sub}，先跑对应扫描', file=sys.stderr)
            continue
        with open(p, encoding='utf-8') as f:
            f.readline()
            for line in f:
                p2 = line.rstrip('\n').split('\t')
                if len(p2) >= len(COLS):
                    yield dict(zip(COLS, p2[:len(COLS)]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', default=DEFAULT_OUT)
    ap.add_argument('--cmd', default=None, help='命令号，如 0x975e（可带 _子命令）')
    ap.add_argument('--class', dest='cls', default=None, help='类描述符正则')
    ap.add_argument('--text', default=None, help='文案/日志关键字正则')
    ap.add_argument('--source', default=None, help='只看某个容器（文件名正则）')
    ap.add_argument('--limit', type=int, default=40)
    a = ap.parse_args()
    if not (a.cmd or a.cls or a.text):
        ap.error('至少给 --cmd / --class / --text 一个')

    n = 0
    for r in load(a.out):
        if a.cmd and a.cmd.lower().split('_')[0] not in (r['cmd_names'] + ' ' + r['pet_ints']).lower():
            continue
        if a.cls and not re.search(a.cls, r['class']):
            continue
        if a.text and not re.search(a.text, ' '.join([r['texts'], r['calls'], r['sso_names'],
                                                     r['class_tags'], r['source']])):
            continue
        if a.source and not re.search(a.source, r['source']):
            continue
        n += 1
        if n > a.limit:
            break
        print(f"\n[{r['source']}] {r['class']}.{r['method']}{r['sig']}")
        print(f"  命令名 : {r['cmd_names'] or '-'}")
        print(f"  服务名 : {r['sso_names'] or '-'}")
        print(f"  整数号 : {r['pet_ints'] or '-'}   拼名发包点: {r['name_concat']}")
        print(f"  调用   : {r['calls'][:300] or '-'}")
        print(f"  文案   : {r['texts'][:300] or '-'}")
        print(f"  类标   : {r['class_tags'][:200] or '-'}   【{r['source']}】")
    print(f'\n# 命中 {n} 条', file=sys.stderr)


if __name__ == '__main__':
    main()
