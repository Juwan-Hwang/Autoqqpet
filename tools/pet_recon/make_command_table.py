"""生成/刷新《QQ 宠物命令总表》docs/pet-commands.md。

把四类来源合成一张表，并把"确信程度"标在每行上：
  1) _scratch/pet_recon/cmd_map.tsv   —— 静态扫描自动产出（服务名、证据等级、出处）
  2) _scratch/pet_recon/whitelist.txt —— libfekit.so 的网关命令名白名单
  3) docs/qqpet-protocol-spec.md      —— 已接入且多数已真机验证的配方（读/写、请求要点）
  4) tools/pet_recon/meanings.tsv     —— **人工确认层**（我们一起填的那份，优先级最高）

含义与确信只往高处走：meanings.tsv 写过的行不会被静态猜测覆盖。
用法：python make_command_table.py
"""
import os
import re
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
WORKSPACE = os.path.abspath(os.path.join(REPO, '..'))
SCRATCH = os.path.join(WORKSPACE, '_scratch', 'pet_recon')
OUT_MD = os.path.join(REPO, 'docs', 'pet-commands.md')
SPEC = os.path.join(REPO, 'docs', 'qqpet-protocol-spec.md')
FEKIT = os.path.join(WORKSPACE, '_research', 'fekit-oidb-commands.md')
MEANINGS = os.path.join(HERE, 'meanings.tsv')

CMD_IN_CELL = re.compile(r'0x([0-9a-f]{4})_(\d+)')
# 从残留文案里抽“可读线索”：驼峰方法名、VM/Manager 类名、或中文 UI 文案
CLUE_CAMEL = re.compile(r'\b(?:begin|query|get|set|do|fetch|report|load|start|send|update|save|claim|buy|feed|bath|adopt|encourage|recall|claim)[A-Z][A-Za-z0-9]*\b')
CLUE_CLS = re.compile(r'\b[A-Z][A-Za-z0-9]{3,}(?:VM|ViewModel|Manager|Module|Handler|Repository|Model)\b')
CLUE_CN = re.compile(r'[\u4e00-\u9fff]{4,20}')
GRADE_ORDER = {'A': 0, 'B': 1, 'C': 2, 'D': 3, 'N': 4}

MEANING_HEADER = ('cmd\tsub\tmeaning\tevidence\tstatus\tnote\n'
                  '# sub 填具体子命令号或 *；evidence 填：实测(日期+机型/版本) / 静态A / 文档 / 问用户 / 猜测；\n'
                  '# status 填：confirmed(已确认) / likely(大概率) / todo(待核) / rejected(确认不是)。\n'
                  '# 这一行以上都是注释，脚本会跳过；正文按 cmd 升序无所谓。\n')


def read(path):
    return open(path, encoding='utf-8').read() if os.path.exists(path) else ''


def rows_of(md):
    """把 markdown 表格行拆成单元格列表。"""
    for line in md.splitlines():
        s = line.strip()
        if s.startswith('|') and s.count('|') >= 3:
            cells = [c.strip() for c in s.strip('|').split('|')]
            if all(set(c) <= set('-: ') for c in cells):
                continue
            yield cells


def from_spec():
    """协议文档：§3.1 读 / §3.2 写 的配方，以及 §7.3 逐命令真机结果（当作实测依据）。"""
    md = read(SPEC)
    if not md:
        return {}
    out = {}
    section = ''
    for line in md.splitlines():
        if line.startswith('### '):
            section = ('读' if '3.1' in line else '写' if '3.2' in line
                       else '实测' if '7.3' in line else '')
            continue
        s = line.strip()
        if not (s.startswith('|') and section):
            continue
        cells = [c.strip() for c in s.strip('|').split('|')]
        if len(cells) < 2 or all(set(c) <= set('-: ') for c in cells):
            continue
        if section == '实测':
            for m in CMD_IN_CELL.finditer(cells[0]):
                key = f'0x{m.group(1)}_{m.group(2)}'
                e = out.setdefault(key, {'label': '', 'kind': '', 'body': '', 'verified': ''})
                e['verified'] = (e['verified'] + ' ' + ' '.join(cells[1:])[:90]).strip()
            continue
        label, cmd_cell, body_cell = cells[0], cells[1], cells[2] if len(cells) > 2 else ''
        for m in CMD_IN_CELL.finditer(cmd_cell):
            key = f'0x{m.group(1)}_{m.group(2)}'
            e = out.setdefault(key, {'label': '', 'kind': '', 'body': '', 'verified': ''})
            e['label'] = e['label'] or label
            e['kind'] = e['kind'] or section
            e['body'] = e['body'] or body_cell[:110]
    return out


def from_fekit_doc():
    """旧命令表文档里「已确认在用的」那一段：命令 → 用途文字。"""
    md = read(FEKIT)
    out = {}
    if not md:
        return out
    for cells in rows_of(md):
        if len(cells) < 3:
            continue
        for m in CMD_IN_CELL.finditer(cells[0]):
            out[f'0x{m.group(1)}_{m.group(2)}'] = {'label': cells[2], 'kind': '', 'body': ''}
    return out


def from_cmd_map():
    p = os.path.join(SCRATCH, 'cmd_map.tsv')
    if not os.path.exists(p):
        # 源码公开版不带 _scratch（静态扫描产物在仓外）：优雅跳过，不覆盖已提交的 docs/pet-commands.md。
        # 要重新生成：先按 docs/capture-guide.md 跑 scan_apk.py / pull_bundles.py / scan_bundles.py / rollup.py 产出 cmd_map.tsv。
        print(f'跳过：缺 {p}（静态扫描产物，公开版不带）。docs/pet-commands.md 保持原样；'
              '新定性的命令请直接写进 tools/pet_recon/meanings.tsv，并同步 docs/commands-in-use.md。')
        raise SystemExit(0)
    rows = []
    with open(p, encoding='utf-8') as f:
        head = f.readline().rstrip('\n').split('\t')
        for line in f:
            parts = line.rstrip('\n').split('\t')
            if len(parts) >= len(head):
                r = dict(zip(head, parts))
                for sub in (r['subs'].split() or ['?']):
                    rows.append({**r, 'sub': sub})
    return rows


def sys_exit(msg):
    raise SystemExit(msg)


def from_meanings():
    if not os.path.exists(MEANINGS):
        with open(MEANINGS, 'w', encoding='utf-8') as f:
            f.write(MEANING_HEADER)
        print(f'已生成人工确认层模板：{MEANINGS}')
        return {}
    out = defaultdict(dict)
    for line in read(MEANINGS).splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        p = line.split('\t')
        if len(p) < 2:
            continue
        p += [''] * (6 - len(p))
        cmd, sub, meaning, evidence, status, note = p[:6]
        out[cmd.lower()]['*'] = None
        out[cmd.lower()][sub] = {'meaning': meaning, 'evidence': evidence,
                                 'status': status, 'note': note}
    return out


def pick_meaning(man, cmd, sub):
    """人工层优先，其次按 sub 精确、再退到 *。"""
    d = man.get(cmd)
    if d:
        for k in (sub, '*'):
            if d.get(k):
                return d[k]
    return None


def confidence(e, spec_hit, fek_hit, man):
    """确信程度：实测 > 人工likely > 静态A+文档 > 静态A > 静态B > C > D > 仅白名单。"""
    grade = e['grade']
    if man and '实测' in man['evidence'] and man['status'] == 'confirmed':
        return '★★★ 实测确认', man['evidence']
    if man and man['status'] == 'confirmed':
        return '★★ 人工确认', man['evidence']
    if man and man['status'] == 'likely':
        return '★ 人工倾向', man['evidence']
    if man and man['status'] == 'rejected':
        return '✗ 已否掉', man['evidence']
    if grade == 'A' and (spec_hit or fek_hit):
        return '★★★ 文档+静态A', '协议文档已有配方（多数已真机验证），且服务名同源'
    if (spec_hit or {}).get('verified'):
        return '★★★ 文档实验', '协议文档 §7.3 有逐命令真机结果'
    if spec_hit or fek_hit:
        return '★★ 文档配方', '协议文档里有配方（但未单独标实测）'
    if grade == 'A':
        return '★★ 静态A', '同一方法里命令名 + trpc 服务名成对出现'
    if grade == 'B':
        return '★ 静态B', '有完整命令名，服务名缺失或不唯一'
    if grade == 'C':
        return '◇ 静态C', '确认在发包链上（≤2 跳到发包点，阈值由 rollup --chain-hops 控制），功能要靠残留文案读'
    if grade == 'N':
        return '✗ 疑似噪声', '不在任何发包链上，方法里也没有网络相关文本（几乎肯定是位模式巧合）'
    if e['wl'] == '在':
        return '○ 仅白名单', '只在 libfekit.so 网关清单里出现'
    return '? 待核', '不在链上，但方法里有网络相关文本，可能是真命令'


def clues(x):
    """把证据文本里的可读线索抽出来（驼峰方法名 / VM 类名 / 中文文案），方便扫一眼定功能。"""
    blob = ' '.join([x['text'], x['detail'], x['src']])
    found = []
    for rx in (CLUE_CAMEL, CLUE_CLS, CLUE_CN):
        for m in rx.findall(blob):
            if m not in found:
                found.append(m)
        if len(found) >= 4:
            break
    return ' '.join(found[:4])


def main():
    lo, hi = 0x9000, 0x9fff      # 宠物号段；要扫全量就改这里
    rows = from_cmd_map()
    spec = from_spec()
    fek = from_fekit_doc()
    man = from_meanings()
    wl_names = {}
    wl_path = os.path.join(SCRATCH, 'whitelist.txt')
    if os.path.exists(wl_path):
        for line in read(wl_path).split():
            m = CMD_IN_CELL.search(line)
            if m:
                wl_names[f'0x{m.group(1)}_{m.group(2)}'] = line

    # 同一个 hex 的多行合并（不同 sub 分开列，但静态证据是共享的）
    man_subs = defaultdict(set)
    for cmd, d in man.items():
        man_subs[cmd] |= {k for k in d if k != '*'}
    table = {}
    for r in rows:
        for sub in (r['sub'].split() or ['?']):
            key = f"{r['cmd']}_{sub}" if sub.isdigit() else r['cmd']
            e = table.setdefault(key, {'cmd': r['cmd'], 'subs': set(), 'grade': 'D',
                                       'sso': set(), 'wl': '-', 'main': set(),
                                       'mods': set(), 'texts': []})
            if sub != '?':
                e['subs'].add(sub)
            if GRADE_ORDER.get(r['grade'], 3) < GRADE_ORDER[e['grade']]:
                e['grade'] = r['grade']
            # 证据文本取“最长的几条”，不要只拿首次升级时那一行（很多行本身就是空的）
            if r['evidence_text']:
                e['texts'].append(r['evidence_text'])
                e['texts'] = sorted(set(e['texts']), key=len, reverse=True)[:2]
            if r['sso']:
                e['sso'] |= set(r['sso'].split(';'))
            e['wl'] = r['whitelist']
            e['main'] |= set(x.strip() for x in r['main_callers'].split(', ') if x.strip())
            e['mods'] |= set(x for x in r['modules'].split(',') if x)
    # 白名单里存在、但静态扫描没证据的命令也补进来（不然表不全），只看目标号段
    have = {t['cmd'] for t in table.values()}
    for name in wl_names:
        cmd = name.split('_')[0]
        if name in table or cmd in have:
            continue
        if not (lo <= int(cmd, 16) <= hi):
            continue
        table[name] = {'cmd': cmd, 'subs': {name.split('_')[1]},
                       'grade': 'D', 'sso': set(), 'wl': '在', 'main': set(),
                       'mods': set(), 'texts': []}

    known = []
    blank = []
    for key in sorted(table, key=lambda k: (int(k.split('0x')[1].split('_')[0], 16), k)):
        e = table[key]
        cmd = e['cmd']
        if not e['subs'] and man_subs.get(cmd):
            e['subs'] |= man_subs[cmd]      # 人工层已知 sub，静态层没抽到
        subs = ' '.join(sorted(e['subs'])) or '?'
        hexes = int(cmd, 16)
        sub_for_spec = sorted(e['subs'])[0] if e['subs'] else '1'
        skey = f'{cmd}_{sub_for_spec}'
        spec_hit = spec.get(skey) or spec.get(cmd)
        fek_hit = fek.get(skey) or fek.get(cmd)
        man_hit = pick_meaning(man, cmd, sub_for_spec) or pick_meaning(man, cmd, '*')
        meaning = ((man_hit or {}).get('meaning') or (spec_hit or {}).get('label')
                   or (fek_hit or {}).get('label') or '')
        if not meaning and e['sso']:
            # 服务名本身就是功能名（Sso_PetResourceRead_SaveOneSuit → SaveOneSuit）
            first = sorted(e['sso'])[0]
            meaning = first.split('_')[-1] + '（服务名直读）'
        conf, why = confidence(e, spec_hit, fek_hit, man_hit)
        src = ', '.join(sorted(e['main'])[:2]) or ', '.join(sorted(e['mods'])[:3]) or '-'
        detail = (man_hit or {}).get('note') or (spec_hit or {}).get('body') or ''
        if (spec_hit or {}).get('verified'):
            detail = (detail + ' ｜真机: ' + spec_hit['verified'])[:120]
        line = {'cmd': cmd, 'subs': subs, 'dec': hexes, 'sso': ' / '.join(sorted(e['sso'])),
                'meaning': meaning, 'conf': conf, 'why': why, 'wl': e['wl'],
                'grade': e['grade'],
                '接入': '是' if (spec_hit or (man_hit and man_hit.get('status') == 'confirmed')) else '',
                'kind': (spec_hit or {}).get('kind', ''), 'src': src, 'detail': detail,
                'text': ' ∥ '.join(e['texts'])[:130]}
        (known if meaning else blank).append(line)

    with open(OUT_MD, 'w', encoding='utf-8') as f:
        w = f.write
        w('# QQ 宠物命令总表\n\n')
        w('> ⚠ **免责声明**：本表的命令含义由作者逆向整理，**不保证准确**；QQ 更新后随时可能失效或改变含义。\n')
        w('> **写命令（会改变账号状态/花金币的）切勿盲发**——先用只读命令和抓包确认，再小心试。\n')
        w('> 模块当前实际在用的命令见 [commands-in-use.md](commands-in-use.md)。\n\n')
        w('> 自动生成：`tools/pet_recon/make_command_table.py`（来源：静态扫描 `cmd_map.tsv` + libfekit 白名单 +\n')
        w('> `docs/qqpet-protocol-spec.md` + **人工确认层 `tools/pet_recon/meanings.tsv`**）。\n')
        w('> 想改含义/确信，**只改 meanings.tsv 再重跑脚本**；直接编辑本文件会被覆盖。\n\n')
        w('## 怎么填这张表\n\n')
        w('1. 在「待填」里按证据强度从上往下挑（C 级比 D 级靠谱）。\n')
        w('2. `python drill.py --cmd 0x9xxx`：看是哪个类、哪个方法在用，以及那行残留文案（UI 文案/日志/埋点名）。\n')
        w('3. 能定性的写进 `meanings.tsv`：`confirmed` 必须有依据（静态A/文档/实测），没把握先写 `likely` 或 `todo`。\n')
        w('4. 要真机确认的：只读命令可以用 `rawsend` 试探；**写命令一律不盲发**。\n\n')
        w('## 确信程度图例\n\n')
        w('| 标记 | 依据 |\n| --- | --- |\n')
        for k, v in [('★★★ 实测确认', '真机跑过，状态变化可复核（在 meanings.tsv 记日期/机型/QQ 版本）'),
                     ('★★★ 文档+静态A', '协议文档有配方（§3 表多数带真机验证记录），且命令名与服务名同源'),
                     ('★★ 人工确认', '静态证据充分 + 人工读过代码，但没实测'),
                     ('★★ 文档配方', '协议文档里有配方，但未标实测'),
                     ('★★ 静态A', '同一方法里「完整命令名 + Sso_ 服务名」成对出现'),
                     ('★ 静态B', '有完整命令名，服务名缺失或不唯一'),
                     ('★ 人工倾向', '人工读过但还没定死'),
                     ('◇ 静态C', '确认在发包链上（≤2 跳走到拼名/发包点；阈值＝rollup --chain-hops），功能要靠页面残留文案读'),
                     ('○ 仅白名单', '只在 libfekit.so 网关清单里出现，客户端代码里没找到调用'),
                     ('? 待核', '不在发包链上，但方法里有网络相关文本，可能是真命令'),
                     ('✗ 疑似噪声', '不在链上、也没有网络相关文本，几乎肯定是浮点常量位模式的巧合'),
                     ('✗ 已否掉', 'meanings.tsv 里标了 rejected')]:
            w(f'| {k} | {v} |\n')
        w('\n')

        def dump(title, items, show_meaning=True):
            def esc(s):
                # 单元格里出现 | 会把 markdown 表格撞列，必须转义
                return str(s).replace('|', '\\|')
            w(f'## {title}（{len(items)} 条）\n\n')
            if not items:
                w('（空）\n\n')
                return
            w('| 命令 | sub | 十进制 | trpc 服务名 | 含义 | 确信 | 依据 | 接入 | 读/写 | 线索 | 出处 | 备注 |\n')
            w('| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n')
            for x in items:
                w('| `{}` | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |\n'.format(
                    x['cmd'], x['subs'], x['dec'], esc(x['sso']), esc(x['meaning'] or '—'),
                    esc(x['conf']), esc(x['why']), x['接入'], x['kind'],
                    esc(clues(x) or '—'), esc(x['src'][:44]) + ' ∥ ' + esc(x['detail'][:60])))
            w('\n')

        dump('已定含义', known)
        blank.sort(key=lambda x: (GRADE_ORDER.get(x['grade'], 3), int(x['cmd'], 16)))
        dump('待填（有号、含义未知；按证据强度排序）', blank)
        w(f'合计 {len(table)} 条命令号；已定含义 {len(known)}，待填 {len(blank)}。\n')
    print(f'写出 {OUT_MD}：已定含义 {len(known)} 条，待填 {len(blank)} 条')


if __name__ == '__main__':
    main()
