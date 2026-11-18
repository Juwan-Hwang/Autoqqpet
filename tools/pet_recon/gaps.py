"""统计总表里还没定性的行，按"能不能靠点 UI 撞出来"分类。

列序先看表头，别猜：脚本会打印表头与一行样例供核对。
"""
import re
from collections import Counter

MD = __import__('os').path.join(__import__('os').path.dirname(__import__('os').path.abspath(__file__)), '..', '..', 'docs', 'pet-commands.md')


def cells(l):
    return [x.strip() for x in l.strip().strip('|').split('|')]


lines = open(MD, encoding='utf-8').read().splitlines()
head = next((cells(l) for l in lines if l.startswith('| `0x') is False and l.startswith('| 命令')), None)
rows = [l for l in lines if l.startswith('| `0x')]
print('表头列:', head)
print('样例行:', cells(rows[0])[:11])

idx_page = next((i for i, h in enumerate(head or []) if '模块' in h or '页面' in h or '线索' in h), None)
idx_conf = next((i for i, h in enumerate(head or []) if '确信' in h or '信心' in h), None)
print(f'模块列={idx_page} 确信列={idx_conf}')

low, withpage = [], []
CONTAINER = ('AILiteGY', 'AILiteBrowser', 'page_craft_main', '非宠物')
cnt_container = 0
for l in rows:
    c = cells(l)
    if len(c) < 8:
        continue
    conf = c[idx_conf] if idx_conf is not None else ''
    hay = ' '.join(c)
    if conf.startswith('☆') or '待核' in conf or 'C 级' in conf or 'D 级' in conf:
        if any(k in hay for k in CONTAINER):
            cnt_container += 1          # 轻应用容器的命令，与宠物无关，不计入口径
            continue
        low.append(c)
        m = re.findall(r'pet_[a-z_]+|AILite[a-zA-Z]+|page_[a-z_]+', hay)
        if m:
            withpage.append((c[0], '页面' if m else '—', sorted(set(m))[0], (c[idx_page] if idx_page else '')[:50]))

print(f'\n未定性 {len(low)} 条（另 {cnt_container} 条属轻应用容器，已剔），其中带页面线索 {len(withpage)} 条')
cnt = Counter(x[2] for x in withpage)
print('按页面聚合（点一个页面能撞出几条）：')
for k, v in cnt.most_common(12):
    print(f'  {k:22s} {v} 条   例如 {", ".join(x[0] for x in withpage if x[2]==k)[:60]}')
