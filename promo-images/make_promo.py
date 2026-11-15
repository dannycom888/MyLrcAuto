#!/usr/bin/env python3
"""Render clean promo images for MyLRC Auto, following the real car UI:
pure black background, current line in the theme's glowing cyan
(secondary #9FDCE8), karaoke sweep gold #FFD54F, non-current lines in
onSurface at 55% alpha, cover = white M-note on navy #051E3C with a
soft cyan halo (v1.0.33: glow 40dp / alpha .85).

Lyrics are the real lines from the enhanced LRC of
陈楚生 - 有没有人告诉你 (tags stripped). Current line is index 9,
shown as the third visible row, as the app's scroll target does.
The sweep covers ~60% of the current line and the boundary is placed
mid-glyph, mirroring LyricsView's clip-based sweep.
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

OUT = os.path.dirname(os.path.abspath(__file__))
ICON = ("/home/hatch/workspace/my-lrc-auto/mylrcauto/app/src/main/res/"
        "drawable-xxxhdpi/ic_launcher_foreground.png")
REG = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
BOLD = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"

BLACK = (0, 0, 0)
CYAN = (0x9F, 0xDC, 0xE8)          # theme secondary
GOLD = (0xFF, 0xD5, 0x4F)          # SWEEP_COLOR
NONCURRENT = (133, 132, 135)       # onSurface #F2F0F5 @ .55 over black
TITLE = (0xF2, 0xF0, 0xF5)         # onSurface
ARTIST = (0xB9, 0xC4, 0xCC)        # onSurfaceVariant
SURFACE = (0x12, 0x18, 0x1F)       # theme surface (card)
NAVY = (0x05, 0x1E, 0x3C)

SONG = "有没有人告诉你"
ARTIST_NAME = "陈楚生"
CURRENT = "有没有人曾告诉你 我很爱你"
ROWS = [  # (text, is_current) — real LRC lines 7..14, current = line 9
    ("早习惯穿梭充满诱惑的黑夜", False),
    ("但却无法忘记你的脸", False),
    (CURRENT, True),
    ("有没有人曾在你日记里哭泣", False),
    ("有没有人曾告诉你 我很在意", False),
    ("在意这座城市的距离", False),
    ("看不见雪的冬天不夜的城市", False),
    ("我听见有人欢呼 有人在哭泣", False),
]


def font(path, size):
    return ImageFont.truetype(path, size, index=0)


def m_note_art():
    """White M-note artwork (RGBA) cut from the launcher foreground:
    brightness above the navy background becomes alpha."""
    src = Image.open(ICON).convert("RGB")
    crop = src.crop((100, 105, 332, 218))  # the M note, above the car
    # (bottom kept at 218: below that the car outline's glow bleeds in
    # as a stray horizontal streak under the note heads)
    px = crop.load()
    art = Image.new("RGBA", crop.size, (255, 255, 255, 0))
    ap = art.load()
    for y in range(crop.size[1]):
        for x in range(crop.size[0]):
            b = max(px[x, y])
            a = int(max(0, min(1.0, (b - 60) / 195.0)) ** 0.8 * 255)
            ap[x, y] = (255, 255, 255, a)
    return art


ART = m_note_art()


def make_cover(size, radius):
    """Navy rounded square with the white M note centred on it."""
    cover = Image.new("RGBA", (size, size), NAVY + (255,))
    aw = int(size * 0.66)
    ah = int(ART.size[1] * aw / ART.size[0])
    art = ART.resize((aw, ah), Image.LANCZOS)
    # faint white glow behind the note itself
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow.alpha_composite(art, ((size - aw) // 2, (size - ah) // 2))
    glow = glow.filter(ImageFilter.GaussianBlur(size * 0.03))
    cover.alpha_composite(glow)
    cover.alpha_composite(art, ((size - aw) // 2, (size - ah) // 2))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size - 1, size - 1], radius=radius, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(cover, (0, 0), mask)
    return out


def paste_cover(base, x, y, size, radius, glow_alpha=170, glow_blur=46):
    """Cover with the soft cyan halo the car screen draws around it."""
    halo = Image.new("RGBA", base.size, (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    hd.rounded_rectangle([x - 6, y - 6, x + size + 6, y + size + 6],
                         radius=radius + 6, fill=CYAN + (glow_alpha,))
    base.alpha_composite(halo.filter(ImageFilter.GaussianBlur(glow_blur)))
    halo2 = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(halo2).rounded_rectangle(
        [x - 2, y - 2, x + size + 2, y + size + 2],
        radius=radius + 2, fill=CYAN + (glow_alpha // 2,))
    base.alpha_composite(halo2.filter(ImageFilter.GaussianBlur(glow_blur // 2)))
    base.alpha_composite(make_cover(size, radius), (x, y))


def glow_text(base, xy, text, fnt, color, blur=16, passes=((22, 110), (9, 150))):
    """Text with a same-colour halo: blurred copies under a sharp copy."""
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).text(xy, text, font=fnt, fill=color + (255,))
    for radius, alpha in passes:
        g = layer.filter(ImageFilter.GaussianBlur(radius))
        g.putalpha(g.getchannel("A").point(lambda a: a * alpha // 255))
        base.alpha_composite(g)
    base.alpha_composite(layer)


def swept_line(base, xy, text, fnt, fraction=0.60):
    """Current line: full cyan glowing base, gold overpaint clipped at
    a boundary placed mid-glyph near `fraction` of the line width —
    the same two-layer clip the app draws."""
    x0, y0 = xy
    d = ImageDraw.Draw(base)
    total = d.textlength(text, font=fnt)
    # cumulative glyph widths -> pick the glyph the fraction falls in,
    # then put the boundary at that glyph's midpoint
    cum = 0.0
    boundary = x0 + fraction * total
    for ch in text:
        w = d.textlength(ch, font=fnt)
        if cum + w >= fraction * total:
            boundary = x0 + cum + w / 2
            break
        cum += w
    # cyan glow + sharp cyan base
    glow_text(base, xy, text, fnt, CYAN, passes=((24, 120), (10, 160)))
    # gold layer (with its own soft glow), clipped left of the boundary
    gold = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(gold).text(xy, text, font=fnt, fill=GOLD + (255,))
    gg = gold.filter(ImageFilter.GaussianBlur(10))
    gg.putalpha(gg.getchannel("A").point(lambda a: a * 130 // 255))
    clip = Image.new("L", base.size, 0)
    ImageDraw.Draw(clip).rectangle([0, 0, boundary, base.size[1]], fill=255)
    for layer in (gg, gold):
        layer.putalpha(ImageChops.multiply(layer.getchannel("A"), clip))
    base.alpha_composite(gg)
    base.alpha_composite(gold)
    return (boundary - x0) / total


# ---------------------------------------------------------------- image 1
def bigscreen():
    W, H = 1920, 1080
    img = Image.new("RGBA", (W, H), BLACK + (255,))
    paste_cover(img, 130, 290, 380, 18)
    d = ImageDraw.Draw(img)
    d.text((130, 724), SONG, font=font(BOLD, 58), fill=TITLE)
    d.text((132, 806), ARTIST_NAME, font=font(REG, 40), fill=ARTIST)

    lx = 650
    slots = [128, 240, 352, 486, 598, 710, 822, 934]
    f_norm = font(REG, 47)
    f_cur = font(BOLD, 70)
    frac = None
    for (text, is_cur), y in zip(ROWS, slots):
        if is_cur:
            frac = swept_line(img, (lx, y), text, f_cur, 0.60)
        else:
            d.text((lx, y), text, font=f_norm, fill=NONCURRENT)
    img.convert("RGB").save(os.path.join(OUT, "mylrc-bigscreen.png"))
    print("bigscreen sweep fraction:", round(frac, 3))


# ---------------------------------------------------------------- image 2
def card():
    W, H = 1280, 720
    img = Image.new("RGBA", (W, H), BLACK + (255,))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([150, 190, 1130, 530], radius=30, fill=SURFACE + (255,),
                        outline=(255, 255, 255, 18), width=2)
    paste_cover(img, 200, 245, 230, 14, glow_alpha=110, glow_blur=26)
    d = ImageDraw.Draw(img)
    tx = 486
    d.text((tx, 238), SONG, font=font(BOLD, 48), fill=TITLE)
    d.text((tx, 304), ARTIST_NAME, font=font(REG, 33), fill=ARTIST)
    glow_text(img, (tx, 408), CURRENT, font(REG, 44), CYAN,
              passes=((16, 100), (7, 140)))
    img.convert("RGB").save(os.path.join(OUT, "mylrc-card.png"))


# ---------------------------------------------------------------- image 3
def taskbar():
    W, H = 1920, 220
    img = Image.new("RGBA", (W, H), BLACK + (255,))
    paste_cover(img, 38, 36, 148, 10, glow_alpha=80, glow_blur=18)
    d = ImageDraw.Draw(img)
    f = font(BOLD, 56)
    f_line = font(REG, 56)
    x = 222
    d.text((x, H // 2), SONG, font=f, fill=TITLE, anchor="lm")
    x += d.textlength(SONG, font=f) + 26
    d.text((x, H // 2), "·", font=f, fill=(138, 143, 148), anchor="lm")
    x += d.textlength("·", font=f) + 26
    glow_text(img, (x, H // 2 - 28), CURRENT, f_line, CYAN,
              passes=((14, 100), (6, 140)))
    # glow_text draws from a top-left origin; recentre: it used y=H/2-28
    # for a 56px font whose visual height is ~56, close enough to centre.
    img.convert("RGB").save(os.path.join(OUT, "mylrc-taskbar.png"))


if __name__ == "__main__":
    bigscreen()
    card()
    taskbar()
    for name in ("mylrc-bigscreen.png", "mylrc-card.png", "mylrc-taskbar.png"):
        im = Image.open(os.path.join(OUT, name))
        print(name, im.size, im.mode)
