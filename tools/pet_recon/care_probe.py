"""在扫描结果里按关键字找"一键照顾"相关的方法：看它在哪个模块、发哪些命令、有哪些入口条件文案。

用法: python _care_probe.py [关键字 ...]   默认找 care 相关
"""
import re
import sys

PATH = __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', '..', '..', '_scratch', 'pet_recon', 'bundles.tsv')   # 默认 <仓库上一级>/_scratch/pet_recon/bundles.tsv，与 make_command_table.py 的 SCRATCH 一致
KEYS = [k.lower() for k in sys.argv[1:]] or ['oneclickcare', 'careall', 'petcare', 'careconfig']

with open(PATH, encoding='utf-8') as f:
    head = f.readline().rstrip('\n').split('\t')
    for line in f:
        p = line.rstrip('\n').split('\t')
        r = dict(zip(head, p + [''] * (len(head) - len(p))))
        hay = (r['class'] + ' ' + r['method'] + ' ' + r['texts'] + ' ' + r['class_tags']).lower()
        if not any(k in hay for k in KEYS):
            continue
        print(f"{r['source'].replace('.apk','')}  {r['class']}.{r['method']}{r['sig'][:40]}")
        print(f"    命令: {r['cmd_names'] or '-'}   号: {r['pet_ints'] or '-'}")
        if r['texts']:
            print(f"    文案: {r['texts'][:260]}")
