#!/usr/bin/env python3
"""Genera le icone Porcodio Luxury Chat a partire dal logo ufficiale (porcodio-favicon-nero.png).
Nessun logo nuovo: il logo nero viene solo reso bianco (alpha dalla luminanza) su sfondo #121212."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageChops

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'TMessagesProj/src/main/res')
SRC = os.path.join(ROOT, 'tools/porcodio/brand/porcodio-favicon-nero.png')
BG = (0x12, 0x12, 0x12, 255)

fav = Image.open(SRC).convert('L')
alpha = ImageChops.invert(fav)                      # nero -> opaco
white = Image.new('RGBA', fav.size, (0xF3, 0xF3, 0xF3, 255)); white.putalpha(alpha)
bbox = alpha.point(lambda v: 255 if v > 24 else 0).getbbox()
crest = white.crop(bbox)                            # solo il logo, senza margini

def fit(img, box_w, box_h):
    r = min(box_w / img.width, box_h / img.height)
    return img.resize((max(1, round(img.width * r)), max(1, round(img.height * r))), Image.LANCZOS)

def canvas(size, frac, bg=None):
    c = Image.new('RGBA', (size, size), bg or (0, 0, 0, 0))
    s = fit(crest, size * frac, size * frac)
    c.alpha_composite(s, ((size - s.width) // 2, (size - s.height) // 2))
    return c

def mask(size, round_):
    m = Image.new('L', (size * 4, size * 4), 0)
    d = ImageDraw.Draw(m)
    if round_: d.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    else: d.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=int(size * 4 * 0.22), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def launcher(size, round_):
    c = canvas(size, 0.74, BG)
    c.putalpha(mask(size, round_))
    return c

def save(img, path):
    if path.endswith('.webp'): img.save(path, 'WEBP', lossless=True)
    else: img.save(path, 'PNG', optimize=True)

n = 0
for path in glob.glob(RES + '/mipmap-*/*.png'):
    name = os.path.basename(path); size = Image.open(path).size[0]
    if 'background' in name:
        img = Image.new('RGBA', (size, size), BG)
    elif 'foreground' in name:
        img = canvas(size, 0.50)                    # zona sicura adaptive icon = 66%
    elif 'launcher' in name:
        img = launcher(size, 'round' in name)
    else:
        print('SKIP', path); continue
    save(img, path); n += 1
for path in glob.glob(RES + '/drawable-*/ic_launcher_dr.webp'):
    size = Image.open(path).size[0]; save(launcher(size, True), path); n += 1
for path in glob.glob(RES + '/drawable-*/notification.webp'):
    size = Image.open(path).size[0]; save(canvas(size, 0.92), path); n += 1   # icona monocromatica barra stato
for path in glob.glob(RES + '/drawable-*/intro_tg_plane.webp'):
    im = Image.open(path); w, h = im.size
    c = Image.new('RGBA', (w, h), (0, 0, 0, 0)); s = fit(crest, w, h); c.alpha_composite(s, ((w - s.width)//2, (h - s.height)//2))
    save(c, path); n += 1
# logo bianco in-app (da porcodio-logo-nero.png: solo nero->bianco)
logo = Image.open(os.path.join(ROOT, 'tools/porcodio/brand/porcodio-logo-nero.png')).convert('RGBA')
a = logo.split()[3]; wl = Image.new('RGBA', logo.size, (0xF3, 0xF3, 0xF3, 255)); wl.putalpha(a)
wl = wl.crop(wl.getbbox()); wl = fit(wl, 512, 512)
os.makedirs(RES + '/drawable-nodpi', exist_ok=True)
save(wl, RES + '/drawable-nodpi/porcodio_logo_white.png')
save(fit(crest, 96, 96), RES + '/drawable-nodpi/porcodio_crest_white.png')
print('written', n)
