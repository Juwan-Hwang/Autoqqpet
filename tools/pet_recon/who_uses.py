"""给一组命令号，列出到底是"哪个页面模块的哪个类/方法"在用它们，并带上类级 tag 与方法文案。

用法: python _who_uses.py 0x9b79 0x9b7f 0x9c3c 0x9bd4
"""
import re
import sys

_SCRATCH = __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', '..', '..', '_scratch', 'pet_recon')   # 与 make_command_table.py 的 SCRATCH 一致
FILES = [__import__('os').path.join(_SCRATCH, 'bundles.tsv'), __import__('os').path.join(_SCRATCH, 'main.tsv')]
WANT = [h.lower() for h in sys.argv[1:]] or ['0x9b79', '0x9b7f', '0x9c3c', '0x9bd4']


def rows(path):
    with open(path, encoding='utf-8') as f:
        head = f.readline().rstrip('\n').split('\t')
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) >= len(head):
                yield dict(zip(head, p))


for path in FILES:
    for r in rows(path):
        ids = set(re.findall(r'0x[0-9a-f]{4}', (r['pet_ints'] + ' ' + r['cmd_names']).lower()))
        hit = [w for w in WANT if w in ids]
        if not hit:
            continue
        print(f"{','.join(hit)}  {r['source'].replace('.apk', '')}  {r['class']}.{r['method']}{r['sig'][:44]}")
        if r.get('class_tags'):
            print(f"        类标: {r['class_tags'][:120]}")
        if r['texts']:
            print(f"        文案: {r['texts'][:190]}")
