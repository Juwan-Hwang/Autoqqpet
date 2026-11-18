"""把仓库根 EULA.md 同步为应用内展示用的 assets/eula.md（单一来源，避免两处文本漂移）。
用法： python tools/sync_eula.py [--check]
  --check  只校验是否一致（比较的是去掉 <!-- --> 注释后的 EULA.md），不一致时以非 0 退出
生成时去掉 EULA.md 里所有 HTML 注释（维护说明不进应用）。
"""
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "EULA.md"   # 应用内用户许可协议的唯一来源（源码许可是 LICENSE.md，另一回事）
DST = ROOT / "app" / "src" / "main" / "assets" / "eula.md"


COMMENT = re.compile(r"<!--.*?-->", re.S)


def strip_comments(text: str) -> str:
    """去掉所有 HTML 注释（维护说明放在 <!-- maintainer:start ... maintainer:end --> 里，不能进应用）。
    注释整行占位的地方不留空行；开头的空行也去掉。应用内同意指纹按去注释后的文本算。"""
    out = COMMENT.sub("", text)
    out = re.sub(r"\n[ \t]*\n(?:[ \t]*\n)+", "\n\n", out)   # 注释删掉后留下的多余空行收成一个
    return out.lstrip("\n").replace("\r\n", "\n")


def digest(p: pathlib.Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest() if p.is_file() else ""


def main() -> int:
    if not SRC.is_file():
        print(f"缺少 {SRC}")
        return 1
    want = strip_comments(SRC.read_text(encoding="utf-8"))
    have = DST.read_text(encoding="utf-8") if DST.is_file() else ""
    if "--check" in sys.argv:
        if want == have:
            print(f"一致：{DST.name} 与 EULA.md 同步（{digest(DST)[:12]}）")
            return 0
        print("不一致：请运行 python tools/sync_eula.py 重新生成 assets/eula.md")
        return 2
    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_text(want, encoding="utf-8", newline="\n")
    print(f"已同步 EULA.md（去注释）→ {DST.relative_to(ROOT)}  ({len(want)} 字符, sha256 {digest(DST)[:12]})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
