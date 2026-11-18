"""宠物 OIDB 命令静态还原的共用件：dexdump 流式解析 + 方法级证据聚合。

核心事实（2026-10-13 实测，见 docs/pet-command-recon.md）：
- 主 APK 的 dex 里，宠物命令同时带「命令名串」和 trpc 服务名 Sso_*，且 com.tencent.ergo.* 没混淆；
- 宠物页面业务代码不在 APK 里，而是真机上运行时下发的 Kuikly 动态包（每个是小 APK）；
- 动态包只带整数命令号 + 拼名串前缀 "OidbSvcTrpcTcp.0x"，类名被 R8 改过，
  但 UI 文案/日志/埋点名可读，所以语义要靠这些残留字符串反推。

依赖：只用到 Android SDK 自带的 dexdump（build-tools/<v>/dexdump.exe）。
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile

CMD_FULL = re.compile(r'OidbSvcTrpcTcp\.0x([0-9a-f]{4})_(\d+)')
CMD_FULL_B = re.compile(rb'OidbSvcTrpcTcp\.0x([0-9a-f]{4})_(\d+)')
SSO = re.compile(r'(?:trpc\.[A-Za-z0-9_.]*\.)?(Sso_[A-Za-z0-9_]+)')
CONCAT = re.compile(r'^OidbSvcTrpcTcp\.0x$')
# 类级日志 tag（KLog 用的那个名字）：很多方法本身没文案，但它所属类有——能用来定功能
TAG_RE = re.compile(r'^[A-Z][A-Za-z0-9]{3,40}(?:VM|ViewModel|Manager|Module|Model|Page|Handler|Repository|DataSource|Request|Api|SDK)?$')
PET_LO, PET_HI = 0x9000, 0x9FFF
EDGE_CAP = 2_000_000   # 调用边硬上限（只在 keep 命中的类里收集，正常远碰不到）

CLS_RE = re.compile(r"^\s*Class descriptor\s+: '([^']+)'")
MTH_RE = re.compile(r"^[0-9a-f]{6}:\s+\|\[[0-9a-f]{6}\]\s+(\S+)\.(\S+?):(\S+)$")
INS_RE = re.compile(r"^[0-9a-f]{6}:\s+[0-9a-f ]*\|\s*[0-9a-f]{4}:\s+(.*)$")
STR_RE = re.compile(r'const-string(?:/jumbo)?\s+\w+, "([^"]*)"')
BITS_RE = re.compile(r'\bconst\s+\w+, #float \S+ // #([0-9a-f]{8})')
INT_RE = re.compile(r'\bconst/(?:4|16)\s+\w+, #int (-?\d+)')
INV_RE = re.compile(r'invoke-[\w/]+ \{[^}]*}, (L[^;]+);\.([^:]+):(\([^)]*\))')


def find_dexdump(preferred=None):
    """定位 dexdump.exe：显式参数 > PATH > ANDROID_HOME/build-tools/*。"""
    if preferred:
        return preferred
    exe = 'dexdump.exe' if os.name == 'nt' else 'dexdump'
    for d in os.environ.get('PATH', '').split(os.pathsep):
        p = os.path.join(d, exe)
        if os.path.exists(p):
            return p
    home = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or ''
    bt = os.path.join(home, 'build-tools')
    if os.path.isdir(bt):
        for v in sorted(os.listdir(bt), reverse=True):
            p = os.path.join(bt, v, exe)
            if os.path.exists(p):
                return p
    sys.exit('找不到 dexdump，请用 --dexdump 指定（在 Android SDK 的 build-tools 下）')


def unescape(s):
    """dexdump 把非 ASCII 打成 \\uXXXX，中文文案要还原后才能读。"""
    return re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)


class MethodEvidence:
    """一个方法体里的全部命令相关证据。"""

    __slots__ = ('cls', 'name', 'sig', 'names', 'sso', 'ints', 'concat', 'calls', 'texts', 'tags')

    def __init__(self, cls, name, sig):
        self.cls, self.name, self.sig = cls, name, sig
        self.names = set()
        self.sso = set()
        self.ints = set()
        self.concat = False
        self.calls = []
        self.texts = []
        self.tags = set()

    def row(self):
        return '\t'.join([
            self.cls, self.name, self.sig,
            ' '.join(sorted(self.names)), ' '.join(sorted(self.sso)),
            ' '.join(sorted(f'0x{v:04x}' for v in self.ints)),
            '1' if self.concat else '0',
            ' '.join(dict.fromkeys(self.calls))[:600],
            ' | '.join(self.texts)[:400],
            ' '.join(sorted(self.tags))[:120],
        ])


ROW_HEADER = ['class', 'method', 'sig', 'cmd_names', 'sso_names', 'pet_ints',
              'name_concat', 'calls', 'texts', 'class_tags']


def scan_dex_bytes(dex_bytes, dexdump, tmp_path, keep=None, edges=None):
    """把一个 dex 拆成「方法级证据」列表。

    keep: 可选正则（作类描述符）。主 APK 不筛的话会把全 QQ 里
    碰巧落在 0x9000-0x9fff 位模式上的浮点常量当成命令号（实测 1500+ 条假阳性）。
    动态包本身全是宠物页面，不用筛；主 APK 默认只留 ergo/qqpet/troop 包。
    edges: 可选 list，收集**全量**调用边 (调用方类, 调用方方法, 被调类, 被调名)；
         中间转发方法本身没有任何常量、不会成为证据行，没这份边就做不了多跳可达。
    """
    with open(tmp_path, 'wb') as f:
        f.write(dex_bytes)
    proc = subprocess.Popen([dexdump, '-d', tmp_path], stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL, text=True,
                            encoding='utf-8', errors='replace', bufsize=1 << 20)
    out = []
    cur_cls = None
    ev = None
    wanted = True
    cls_evs = []      # 当前类的全部证据行，用于回填类级 tag
    cls_tags = set()

    def close():
        if wanted and ev is not None and (ev.names or ev.sso or ev.ints or ev.concat):
            out.append(ev)
            cls_evs.append(ev)
        if ev is not None:
            ev.tags = set(cls_tags)

    for line in proc.stdout:
        line = line.rstrip('\n')
        m = CLS_RE.match(line)
        if m:
            close()
            for e in cls_evs:      # 上一类收尾：把类级 tag 分给它自己的证据行
                e.tags |= cls_tags
            cls_evs, cls_tags = [], set()
            cur_cls, ev = m.group(1), None
            wanted = keep is None or bool(keep.search(cur_cls))
            continue
        m = MTH_RE.match(line)
        if m:
            close()
            ev = MethodEvidence(cur_cls, m.group(2), m.group(3))
            continue
        if ev is None:
            continue
        m = INS_RE.match(line)
        if not m:
            continue
        code = m.group(1)
        ms = STR_RE.search(code)
        if ms:
            s = unescape(ms.group(1))
            for c in CMD_FULL.finditer(s):
                ev.names.add(f'0x{c.group(1)}_{c.group(2)}')
            ev.sso.update(SSO.findall(s))
            if CONCAT.match(s):
                ev.concat = True
            if len(ev.texts) < 12 and 1 < len(s) < 60 and not s.startswith('L'):
                ev.texts.append(s)
            if TAG_RE.match(s):
                cls_tags.add(s)
        mb = BITS_RE.search(code)
        if mb:
            v = int(mb.group(1), 16)
            if PET_LO <= v <= PET_HI:
                ev.ints.add(v)
        mi = INT_RE.search(code)
        if mi:
            v = int(mi.group(1)) & 0xFFFFFFFF
            if PET_LO <= v <= PET_HI:
                ev.ints.add(v)
        iv = INV_RE.search(code)
        if iv and len(ev.calls) < 40:
            ev.calls.append(f'{iv.group(1)}.{iv.group(2)}')
        if edges is not None and iv and wanted and len(edges) < EDGE_CAP:
            edges.append((cur_cls or '', ev.name, iv.group(1), iv.group(2)))
    close()
    for e in cls_evs:
        e.tags |= cls_tags
    proc.wait()
    return out


def scan_container(path, dexdump, tmp_path, keep=None, edges=None):
    """扫一个容器：APK/ZIP 里的每个 dex，或裸 dex 文件。
    产出 (容器名, 内部条目名, [方法证据])。"""
    name = os.path.basename(path)
    if name.endswith('.dex'):
        yield name, 'classes.dex', scan_dex_bytes(open(path, 'rb').read(), dexdump, tmp_path,
                                                 keep, edges)
        return
    try:
        z = zipfile.ZipFile(path)
    except Exception:
        return
    for n in z.namelist():
        if n.endswith('.dex'):
            yield name, n, scan_dex_bytes(z.read(n), dexdump, tmp_path, keep, edges)


def cmd_names_in_blob(blob):
    """二进制里出现的完整命令名（libfekit.so 白名单用的就是这招）。"""
    for m in CMD_FULL_B.finditer(blob):
        yield f'0x{m.group(1).decode()}_{m.group(2).decode()}'


def write_edges(path, edges):
    """全量调用边：调用方类\t调用方方法\t被调类\t被调名（可达性分析用）。"""
    seen = set()
    with open(path, 'w', encoding='utf-8') as f:
        f.write('caller_cls\tcaller_mth\tcallee_cls\tcallee_mth\n')
        for e in edges:
            if e in seen:
                continue
            seen.add(e)
            f.write('\t'.join(e) + '\n')
    print(f'写出 {path}（{len(seen)} 条边）', file=sys.stderr)


def tmp_dex_path():
    """带 pid：两个扫描任务并发跑时不能共用同一个临时 dex（会互相覆盖）。"""
    return os.path.join(tempfile.gettempdir(), f'_pet_recon_{os.getpid()}.dex')


def write_tsv(path, rows):
    """rows: [(容器名, 条目名, [方法证据]), ...]"""
    n = sum(len(evs) for _s, _d, evs in rows)
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\t'.join(['source', 'dex'] + ROW_HEADER) + '\n')
        for src, dex, evs in rows:
            for ev in evs:
                f.write('\t'.join([src, dex, ev.row()]) + '\n')
    print(f'写出 {path}（{n} 条方法证据）', file=sys.stderr)
