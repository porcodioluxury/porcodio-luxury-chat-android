#!/usr/bin/env python3
"""Elimina il blu Telegram dalla UI: converte i colori della famiglia blu (hue 165-275, sat>0.2) nella palette PORCODIO.
  light = tema chiaro di base (ThemeColors.java): accenti -> #242833, sfondi azzurri chiari -> #E6E7EB
  dark  = tutto il resto: scuri -> bucket #121212/#1A1A1A/#242833/#2F3442, chiari -> #F3F3F3"""
import re, os, glob, colorsys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
MAIN = os.path.join(ROOT, 'TMessagesProj/src/main')

def is_blue(r, g, b):
    h, l, s = colorsys.rgb_to_hls(r/255, g/255, b/255)
    return 165 <= h*360 <= 275 and s > 0.2, l

def conv(rgb, mode):
    r, g, b = (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255
    ok, l = is_blue(r, g, b)
    if not ok: return None
    if mode == 'light':
        return 0x1A1A1A if l < 0.15 else 0xE6E7EB if l >= 0.8 else 0x242833
    return 0x121212 if l < 0.075 else 0x1A1A1A if l < 0.115 else 0x242833 if l < 0.17 else 0x2F3442 if l < 0.30 else 0xF3F3F3

JAVA_RE = re.compile(r'0x([fF]{2})([0-9a-fA-F]{6})\b')   # solo ARGB con alpha ff (evita ID TL/costanti)
THEME_RE = re.compile(r'0x([0-9a-fA-F]{2})([0-9a-fA-F]{6})\b')
XML_RE = re.compile(r'#([0-9a-fA-F]{2})?([0-9a-fA-F]{6})\b')

def process(path, regex, mode, hexfmt):
    s = open(path, encoding='utf-8', errors='surrogateescape').read(); n = 0
    def rep(m):
        nonlocal n
        v = conv(int(m.group(2), 16), mode)
        if v is None: return m.group(0)
        n += 1
        return hexfmt(m.group(1), v, m.group(0))
    out = regex.sub(rep, s)
    if n: open(path, 'w', encoding='utf-8', errors='surrogateescape').write(out)
    return n

def jfmt(a, v, orig):
    pre = orig[:2]
    if a: return '%s%s%06X' % (pre, a, v) if orig[2:4].isupper() or orig[2:4].isdigit() else '%s%s%06x' % (pre, a, v)
    return '%s%06X' % (pre, v)
def xfmt(a, v, orig): return '#%s%06X' % (a or '', v)

EXCL = {'Theme.java','Backdrops.java','AvatarConstructorFragment.java','WallpapersListActivity.java','FireworksOverlay.java',
        'StickerMasksAlert.java','IconBackgroundColors.java','AvatarDrawable.java','WalletCard2DMaterial.java','WalletGiftPalette.java',
        'WalletActionLayout.java','WalletActivity.java','WalletSendParticles.java','ThemeColors.java'}
total = 0
for f in glob.glob(MAIN + '/java/**/*.java', recursive=True):
    b = os.path.basename(f)
    if b == 'ThemeColors.java': total += process(f, THEME_RE, 'light', jfmt)
    elif b not in EXCL and '/tgnet/' not in f.replace(os.sep,'/') and '/tgnet' not in f: total += process(f, JAVA_RE, 'dark', jfmt)
print('java:', total)
# costanti blu Telegram
p = MAIN + '/java/org/telegram/ui/ActionBar/ThemeColors.java'
s = open(p).read()
s = re.sub(r'(TELEGRAM_COLOR\s*=\s*)0x[0-9A-Fa-f]{8}', r'\g<1>0xFF242833', s)
s = re.sub(r'(TELEGRAM_COLOR_TEXT\s*=\s*)0x[0-9A-Fa-f]{8}', r'\g<1>0xFF242833', s)
open(p, 'w').write(s)
x = 0
for f in glob.glob(MAIN + '/res/values*/styles.xml') + glob.glob(MAIN + '/res/values*/colors.xml') + glob.glob(MAIN + '/res/drawable*/bar_selector_style.xml') + glob.glob(MAIN + '/res/drawable/verified2_bg.xml') + glob.glob(MAIN + '/res/layout/feed_widget_item.xml'):
    x += process(f, XML_RE, 'dark', xfmt)
for f in glob.glob(MAIN + '/res/drawable/icon_*background_sa.xml'):
    open(f, 'w').write('<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">\n    <solid android:color="#121212" />\n</shape>\n'); x += 1
print('xml:', x)
