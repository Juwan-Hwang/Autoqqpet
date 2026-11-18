"""从 art/ic_launcher_source.png 生成全套启动图标：
- 传统 mipmap ic_launcher / ic_launcher_round（白底方形与圆形）
- 自适应图标 ic_launcher_foreground（内容落在 66dp 安全区内）+ anydpi-v26 xml + 背景色
用法：python tools/gen_icons.py
"""
from PIL import Image, ImageDraw
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "ic_launcher_source.png"
RES = ROOT / "app" / "src" / "main" / "res"

# 密度 -> 传统图标边长 / 自适应画布边长（108dp）
DENSITIES = {"mdpi": (48, 108), "hdpi": (72, 162), "xhdpi": (96, 216),
             "xxhdpi": (144, 324), "xxxhdpi": (192, 432)}
BG = (255, 255, 255, 255)        # 旧版白底（已不用：现行图标是 drawable/ic_launcher_background.xml 的海蓝渐变矢量，本脚本只生成旧位图）
PAD = 0.06                       # 传统图标留边比例
SAFE = 66.0 / 108.0              # 自适应图标安全区占比（超出会被系统蒙版裁掉）


def load() -> Image.Image:
    im = Image.open(SRC).convert("RGBA")
    # 内容居中裁成正方形，避免非等比拉伸
    w, h = im.size
    side = min(w, h)
    left, top = (w - side) // 2, (h - side) // 2
    return im.crop((left, top, left + side, top + side))


def legacy(sq: Image.Image, px: int, round_icon: bool = False) -> Image.Image:
    inner = int(px * (1 - 2 * PAD))
    art = sq.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), BG)
    canvas.paste(art, ((px - inner) // 2, (px - inner) // 2), art)
    if round_icon:
        mask = Image.new("L", (px, px), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, px - 1, px - 1), fill=255)
        out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        out.paste(canvas, (0, 0), mask)
        return out
    return canvas


def foreground(sq: Image.Image, px: int) -> Image.Image:
    inner = int(px * SAFE)
    art = sq.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    canvas.paste(art, ((px - inner) // 2, (px - inner) // 2), art)
    return canvas


def main():
    if not SRC.is_file():
        raise SystemExit(f"缺少源图：{SRC}")
    sq = load()
    made = []
    for dens, (px, fpx) in DENSITIES.items():
        d = RES / f"mipmap-{dens}"
        d.mkdir(parents=True, exist_ok=True)
        legacy(sq, px).save(d / "ic_launcher.png")
        legacy(sq, px, True).save(d / "ic_launcher_round.png")
        foreground(sq, fpx).save(d / "ic_launcher_foreground.png")
        made += [f"mipmap-{dens}/ic_launcher.png", f"mipmap-{dens}/ic_launcher_round.png",
                 f"mipmap-{dens}/ic_launcher_foreground.png"]

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    tpl = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
           '    <background android:drawable="@color/ic_launcher_background" />\n'
           '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
           '</adaptive-icon>\n')
    (anydpi / "ic_launcher.xml").write_text(tpl, encoding="utf-8")
    (anydpi / "ic_launcher_round.xml").write_text(tpl, encoding="utf-8")

    vals = RES / "values"
    vals.mkdir(parents=True, exist_ok=True)
    (vals / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        '    <color name="ic_launcher_background">#FFFFFFFF</color>\n</resources>\n',
        encoding="utf-8")

    print(f"源图 {SRC.name} {sq.size[0]}px 方形 → 生成 {len(made)+2} 个资源文件")
    for m in made:
        print("  ", m)
    print("   mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml")
    print("   values/ic_launcher_background.xml")


if __name__ == "__main__":
    main()
