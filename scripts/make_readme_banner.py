#!/usr/bin/env python3
"""Draws docs/banner.svg, the big title at the top of the README.

The name is drawn as outlines from the app's own font (Atkinson Hyperlegible Bold), so it looks
the same everywhere, in the app's yellow on black, inside a bright frame with the app's icon.
Needs fontTools:  pip install fonttools
"""
import os
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/src/main/res/font/atkinson_bold.ttf")
OUT = os.path.join(ROOT, "docs/banner.svg")

YELLOW = "#FFE14D"
BLACK = "#000000"
NAME = "RP Launcher"
SIZE = 140          # letter size
W, H = 1200, 300    # banner size
FRAME = 10          # frame thickness

font = TTFont(FONT)
glyphs = font.getGlyphSet()
cmap = font.getBestCmap()
scale = SIZE / font["head"].unitsPerEm
ascent = font["OS/2"].sCapHeight * scale

# The name as one path, laid out by advance widths.
advance = 0.0
pen = SVGPathPen(glyphs)
for ch in NAME:
    name = cmap[ord(ch)]
    glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, advance, 0)))
    advance += glyphs[name].width * scale
text_w = advance

# The icon: the frame and four tiles from ic_launcher_foreground (drawn on 108 units, art 29..79).
icon = 172
icon_scale = icon / 54.0
gap = 48
total = icon + gap + text_w
x0 = (W - total) / 2
iy = (H - icon) / 2
tx = x0 + icon + gap
ty = H / 2 + ascent / 2

def icon_path():
    s = icon_scale
    def p(x, y):
        return f"{x0 + (x - 27) * s:.1f},{iy + (y - 27) * s:.1f}"
    def rect(x1, y1, x2, y2, r):
        return (f"M{p(x1 + r, y1)}H{x0 + (x2 - r - 27) * s:.1f}"
                f"A{r * s:.1f},{r * s:.1f} 0 0 1 {p(x2, y1 + r)}"
                f"V{iy + (y2 - r - 27) * s:.1f}"
                f"A{r * s:.1f},{r * s:.1f} 0 0 1 {p(x2 - r, y2)}"
                f"H{x0 + (x1 + r - 27) * s:.1f}"
                f"A{r * s:.1f},{r * s:.1f} 0 0 1 {p(x1, y2 - r)}"
                f"V{iy + (y1 + r - 27) * s:.1f}"
                f"A{r * s:.1f},{r * s:.1f} 0 0 1 {p(x1 + r, y1)}Z")
    frame = rect(29, 29, 79, 79, 9)
    tiles = "".join(rect(x, y, x + 16, y + 16, 3) for y in (36, 56) for x in (36, 56))
    return frame, tiles, 4 * s

frame, tiles, stroke = icon_path()
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-labelledby="t">
  <title id="t">{NAME}</title>
  <rect x="0" y="0" width="{W}" height="{H}" rx="40" fill="{BLACK}"/>
  <rect x="{FRAME * 1.5}" y="{FRAME * 1.5}" width="{W - FRAME * 3}" height="{H - FRAME * 3}" rx="30" fill="none" stroke="{YELLOW}" stroke-width="{FRAME}"/>
  <path d="{frame}" fill="none" stroke="{YELLOW}" stroke-width="{stroke:.1f}"/>
  <path d="{tiles}" fill="{YELLOW}"/>
  <path transform="translate({tx:.1f},{ty:.1f})" d="{pen.getCommands()}" fill="{YELLOW}"/>
</svg>
'''
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w") as f:
    f.write(svg)
print(f"wrote {OUT}: name {text_w:.0f} wide, {len(svg)} bytes")
