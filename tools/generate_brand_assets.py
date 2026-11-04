#!/usr/bin/env python3
"""
IBB Launcher — brand asset generator.

Turns the master avatar (tools/source/POV.png) into every icon/logo the
launcher needs. Re-run after replacing the master source:

    python3 tools/generate_brand_assets.py

Outputs
    app/src/main/res/drawable-nodpi/ibb_logo.png        in-app logo (transparent)
    app/src/main/res/mipmap-*/ic_launcher.png           legacy launcher icons
    app/src/main/res/mipmap-*/ic_launcher_round.png     legacy round icons
    app/src/main/res/drawable-nodpi/ic_launcher_fg.png  adaptive-icon foreground
    docs/brand/ic_launcher_512.png                      store listing icon
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools" / "source" / "POV.png"
RES = ROOT / "app" / "src" / "main" / "res"
DOCS = ROOT / "docs" / "brand"

# Brand palette (kept in sync with ui/theme/Color.kt)
VOID = (5, 6, 11)
INDIGO = (21, 16, 58)
VIOLET = (124, 92, 255)
CYAN = (34, 211, 238)
AMBER = (255, 196, 138)

SS = 4  # supersampling factor


def trim_and_square(img: Image.Image, pad_ratio: float = 0.06) -> Image.Image:
    """Crop transparent border, then centre on a transparent square canvas."""
    bbox = img.getbbox()
    if bbox:
        img = img.crop(bbox)
    side = int(max(img.size) * (1 + pad_ratio * 2))
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(img, ((side - img.width) // 2, (side - img.height) // 2), img)
    return canvas


def diagonal_gradient(size: int, stops: list[tuple[float, tuple[int, int, int]]]) -> Image.Image:
    """Linear gradient on the 135° axis, rendered small then upscaled (cheap + smooth)."""
    n = 256
    small = Image.new("RGB", (n, n))
    px = small.load()
    for y in range(n):
        for x in range(n):
            t = (x / (n - 1) + y / (n - 1)) / 2
            for i in range(len(stops) - 1):
                a_pos, a_col = stops[i]
                b_pos, b_col = stops[i + 1]
                if a_pos <= t <= b_pos:
                    f = (t - a_pos) / max(b_pos - a_pos, 1e-6)
                    px[x, y] = tuple(int(a_col[c] + (b_col[c] - a_col[c]) * f) for c in range(3))
                    break
            else:
                px[x, y] = stops[-1][1]
    return small.resize((size, size), Image.BICUBIC)


def squircle_mask(size: int, radius_ratio: float = 0.235, circle: bool = False) -> Image.Image:
    mask = Image.new("L", (size * SS, size * SS), 0)
    d = ImageDraw.Draw(mask)
    s = size * SS
    if circle:
        d.ellipse((0, 0, s - 1, s - 1), fill=255)
    else:
        r = int(s * radius_ratio)
        d.rounded_rectangle((0, 0, s - 1, s - 1), radius=r, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def glow(size: int, color: tuple[int, int, int], centre: tuple[float, float], radius: float, alpha: int) -> Image.Image:
    """Soft radial glow layer (additive-looking, screen-blended by the caller)."""
    layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = centre[0] * size, centre[1] * size
    rr = radius * size
    d.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=color + (alpha,))
    return layer.filter(ImageFilter.GaussianBlur(radius=size * 0.09))


def make_icon(size: int, logo: Image.Image, circle: bool = False) -> Image.Image:
    bg = diagonal_gradient(size, [
        (0.0, (36, 30, 92)),
        (0.42, (16, 16, 40)),
        (1.0, (8, 9, 16)),
    ]).convert("RGBA")

    # Ambient brand light: violet top-left, cyan bottom-right.
    bg.alpha_composite(glow(size, VIOLET, (0.20, 0.14), 0.58, 120))
    bg.alpha_composite(glow(size, CYAN, (0.86, 0.90), 0.44, 80))
    bg.alpha_composite(glow(size, AMBER, (0.5, 0.96), 0.34, 38))

    # Backlight behind the avatar so the near-black hair keeps a readable silhouette.
    bg.alpha_composite(glow(size, (186, 172, 255), (0.5, 0.42), 0.40, 96))

    # Glass sheen: a subtle top-edge highlight.
    sheen = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen)
    sd.ellipse((-size * 0.35, -size * 0.85, size * 1.35, size * 0.42), fill=(255, 255, 255, 30))
    bg.alpha_composite(sheen.filter(ImageFilter.GaussianBlur(size * 0.02)))

    # Logo, centred, occupying ~70% of the tile.
    inner = int(size * 0.70)
    lg = logo.copy()
    lg.thumbnail((inner, inner), Image.LANCZOS)
    bg.alpha_composite(lg, ((size - lg.width) // 2, (size - lg.height) // 2))

    # Hairline border for the frosted-glass read.
    border = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    bd = ImageDraw.Draw(border)
    s = size * SS
    w = max(2, int(s * 0.006))
    if circle:
        bd.ellipse((w, w, s - w, s - w), outline=(255, 255, 255, 64), width=w)
    else:
        r = int(s * 0.235)
        bd.rounded_rectangle((w, w, s - w, s - w), radius=r, outline=(255, 255, 255, 64), width=w)
    bg.alpha_composite(border.resize((size, size), Image.LANCZOS))

    # Mask to the squircle/circle.
    mask = squircle_mask(size, circle=circle)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out


def main() -> int:
    if not SRC.exists():
        print(f"Missing master icon: {SRC}", file=sys.stderr)
        return 1

    DOCS.mkdir(parents=True, exist_ok=True)
    (RES / "drawable-nodpi").mkdir(parents=True, exist_ok=True)

    master = trim_and_square(Image.open(SRC).convert("RGBA"))
    master.resize((900, 900), Image.LANCZOS).save(RES / "drawable-nodpi" / "ibb_logo.png")

    # Adaptive icon foreground: logo inside the 66% safe zone of a 108dp canvas.
    fg = Image.new("RGBA", (1080, 1080), (0, 0, 0, 0))
    inner = int(1080 * 0.52)
    lg = master.copy()
    lg.thumbnail((inner, inner), Image.LANCZOS)
    # Soft drop shadow so the avatar reads on any launcher wallpaper.
    shadow = Image.new("RGBA", (1080, 1080), (0, 0, 0, 0))
    sm = Image.new("RGBA", lg.size, (0, 0, 0, 170))
    shadow.paste(sm, ((1080 - lg.width) // 2, (1080 - lg.height) // 2 + 18), lg)
    fg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(26)))
    fg.alpha_composite(lg, ((1080 - lg.width) // 2, (1080 - lg.height) // 2))
    fg.save(RES / "drawable-nodpi" / "ic_launcher_fg.png")

    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in densities.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(parents=True, exist_ok=True)
        make_icon(px, master).save(folder / "ic_launcher.png")
        make_icon(px, master, circle=True).save(folder / "ic_launcher_round.png")

    make_icon(512, master).save(DOCS / "ic_launcher_512.png")
    make_icon(1024, master).save(DOCS / "ic_launcher_1024.png")
    print("Brand assets written.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
