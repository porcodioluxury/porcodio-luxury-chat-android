#!/usr/bin/env python3
"""Desatura i gradienti decorativi (Premium, Stars, regali, wallet) in grigi/grigio-blu PORCODIO.
Agisce su: chiavi tema premium*/stars*/gift_ribbon*/wallet_* in ThemeColors.java e negli .attheme,
e sui letterali ARGB saturi (esclusi i rossi di stato) in ui/Gifts, ui/Stars, ui/Wallet, Components/Premium."""
import re, os, colorsys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
JAVA = os.path.join(ROOT, 'TMessagesProj/src/main/java/org/telegram')

def desat(argb):
    a = (argb >> 24) & 0xFF; r, g, b = (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if s <= 0.25: return None
    d = h * 360
    if d < 12 or d > 348: return None            # rossi di stato
    r2, g2, b2 = colorsys.hls_to_rgb(220 / 360, l, 0.10)
    return (a << 24) | (int(r2 * 255 + .5) << 16) | (int(g2 * 255 + .5) << 8) | int(b2 * 255 + .5)

KEYS = re.compile(r'^(premium|stars|gift_ribbon|wallet)')
# ThemeColors.java
p = os.path.join(JAVA, 'ui/ActionBar/ThemeColors.java'); s = open(p).read(); n = 0
def repl(m):
    global n
    key, lit = m.group(2), m.group(3)
    if not KEYS.match(key): return m.group(0)
    v = int(lit, 16) if lit.lower().startswith('0x') else int(lit) & 0xFFFFFFFF
    if lit.lower().startswith('0x') and len(lit) <= 8: v |= 0xFF000000
    nv = desat(v)
    if nv is None: return m.group(0)
    n += 1
    return '%s0x%08X;' % (m.group(1), nv)
s = re.sub(r'(defaultColors\[key_(\w+)\] = )(0[xX][0-9a-fA-F]+|-?\d+);', repl, s)
open(p, 'w').write(s); print('ThemeColors', n)
# attheme
for name in ('darkblue', 'night', 'arctic', 'day', 'bluebubbles'):
    p = os.path.join(ROOT, 'TMessagesProj/src/main/assets/%s.attheme' % name)
    out = []
    for ln in open(p).read().splitlines():
        m = re.match(r'^(\w+)=(-?\d+)$', ln)
        if m and KEYS.match(m.group(1)):
            nv = desat(int(m.group(2)) & 0xFFFFFFFF)
            if nv is not None: ln = '%s=%d' % (m.group(1), nv - (1 << 32) if nv >= 1 << 31 else nv)
        out.append(ln)
    open(p, 'w').write('\n'.join(out) + '\n')
# letterali nei sorgenti UI
LIT = re.compile(r'0x([0-9a-fA-F]{8})\b')
tot = 0
for sub in ('ui/Gifts', 'ui/Stars', 'ui/Wallet', 'ui/Components/Premium'):
    for dp, _, fs in os.walk(os.path.join(JAVA, sub)):
        for f in fs:
            if not f.endswith('.java') or f in ('WalletEngine2.java', 'WalletMessageHash.java'): continue  # contengono costanti BOC, non colori
            fp = os.path.join(dp, f); t = open(fp).read(); c = 0
            def r2(m):
                global c
                nv = desat(int(m.group(1), 16))
                if nv is None: return m.group(0)
                c += 1; return '0x%08X' % nv
            t2 = LIT.sub(r2, t)
            if c: open(fp, 'w').write(t2); tot += c; print(' ', fp.split('telegram/')[1], c)
print('letterali', tot)
