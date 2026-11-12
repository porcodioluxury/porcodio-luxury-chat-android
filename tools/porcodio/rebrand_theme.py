#!/usr/bin/env python3
"""Ricolora darkblue.attheme (tema scuro predefinito) con la palette PORCODIO:
#121212 background, #1A1A1A surface, #242833 raised, #2F3442 border, testo #F3F3F3 / #BFBFBF, accento #F3F3F3, niente oro.
Lo script parte da tools/porcodio/upstream/darkblue.attheme (copia dell'originale) e riscrive l'asset."""
import re, os, colorsys
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
BG, SURF, RAISED, BORDER = 0x121212, 0x1A1A1A, 0x242833, 0x2F3442
TXT, TXT2 = 0xF3F3F3, 0xBFBFBF
DARK_ON_ACCENT = 0x121212
FG_KEYS = re.compile(r'(Text|Link|Icon|Blue|Check|Progress|Line|Tab|Selected|Highlight|Cursor|Title|Subtitle)', re.I)
TEXT_ON_ACCENT = re.compile(r'(ButtonText$|ButtonTextCounter$|^featuredStickers_buttonText$|^chats_unreadCounterText$|^chats_actionIcon$|^chat_goDownButtonCounter$|^chats_pinnedIcon$|^switchTrackChecked$)')
OVERRIDE = {
    'windowBackgroundGray': BG, 'chat_wallpaper': BG, 'chat_wallpaper_gradient_to': BG,
    'windowBackgroundWhite': SURF, 'actionBarDefault': SURF, 'actionBarActionModeDefault': SURF,
    'chat_inBubble': RAISED, 'chat_outBubble': 0xFFF3F3F3, 'chat_inBubbleSelected': BORDER, 'chat_outBubbleSelected': 0x3A4152,
    'divider': BORDER | 0xFF000000,
    'chats_unreadCounterText': DARK_ON_ACCENT, 'chats_actionIcon': DARK_ON_ACCENT, 'chats_unreadCounter': TXT,
    'chats_actionBackground': TXT, 'chats_actionPressedBackground': TXT2,
    'glass_tabSelected': TXT, 'glass_tabSelectedText': TXT, 'glass_tabUnselected': TXT2,
    'chat_messagePanelSend': TXT, 'chat_messagePanelVoicePressed': DARK_ON_ACCENT, 'chat_messagePanelVoiceBackground': TXT, 'chat_messagePanelBackground': SURF, 'chat_messagePanelText': TXT, 'chat_messagePanelHint': TXT2,
}

def parse(v):
    v = v.strip()
    if v.startswith('#'):
        h = v[1:]
        if len(h) == 6: h = 'ff' + h
        return int(h, 16), 'hex'
    return int(v) & 0xFFFFFFFF, 'int'

def fmt(argb, kind):
    if kind == 'hex': return '#%08x' % argb
    return str(argb - (1 << 32) if argb >= (1 << 31) else argb)

def transform(key, argb):
    a = (argb >> 24) & 0xFF; r, g, b = (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    deg = h * 360
    out = None
    if min(r, g, b) >= 0xF0:
        out = TXT
    elif s > 0.25 and not (170 <= deg <= 275):
        return argb                                   # rosso/verde/giallo/arancio: colori di stato, invariati
    elif l < 0.30:
        out = BG if l < 0.075 else SURF if l < 0.115 else RAISED if l < 0.17 else BORDER
    elif s > 0.2 and l >= 0.35:
        out = TXT                                     # accento blu -> #F3F3F3
    elif l > 0.5:
        out = TXT2 if l < 0.85 else TXT
    else:
        v = int(l * 255); out = (v << 16) | (v << 8) | v
    return (a << 24) | out


sys_import = __import__('sys')
LIGHT_ASSETS = ('arctic', 'day', 'bluebubbles')
DARK_ASSETS = ('darkblue', 'night')

def out_override(key, argb):
    """Bolle in uscita #F3F3F3 con testo scuro (palette PORCODIO)."""
    if not (key.startswith('chat_out') or key in ('chat_messageTextOut', 'chat_messageLinkOut')):
        return argb
    a = argb & 0xFF000000
    if key == 'chat_messageTextOut': return a | 0x121212
    if key == 'chat_outBubble': return 0xFFF3F3F3
    if key == 'chat_outBubbleSelected': return 0xFFDCDCDC
    if key in ('chat_outBubbleShadow', 'chat_outBubbleGradientSelectedOverlay'): return argb
    if 'Background' in key: return a | 0xD9D9D9
    if any(t in key for t in ('Time', 'Views', 'Clock', 'Check', 'Perfomer', 'Info', 'Duration', 'Venue', 'Phone', 'Seekbar', 'Progress', 'Line', 'Icon', 'Loader', 'Menu', 'Instant', 'Reaction')):
        return a | 0x2F3442
    if key.endswith(('Text', 'Name')) or key == 'chat_messageLinkOut':
        return a | 0x121212
    return argb

def light_transform(argb):
    a = (argb >> 24) & 0xFF; r, g, b = (argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    if 165 <= h * 360 <= 275 and s > 0.2:
        out = 0x1A1A1A if l < 0.15 else 0xE6E7EB if l >= 0.8 else 0x242833
        return (a << 24) | out
    return argb

# Palette avatar generati: grigi e grigio-blu PORCODIO (sfondo con gradiente + nome nei messaggi)
AV_BG = {'Red': (0x5A5F6B, 0x3C404A), 'Orange': (0x6B717D, 0x484D59), 'Violet': (0x3A3F4D, 0x242833),
         'Green': (0x555B68, 0x383D49), 'Cyan': (0x2F3442, 0x1F232E), 'Blue': (0x4D5463, 0x343947), 'Pink': (0x7C828E, 0x555B68)}
AV_NAME_DARK = {'Red': 0xBFC3CC, 'Orange': 0xA9AEB9, 'Violet': 0xD8DAE0, 'Green': 0x9AA0AC, 'Cyan': 0xCBCFD6, 'Blue': 0xB4B9C4, 'Pink': 0xE6E8EC}
AV_NAME_LIGHT = {'Red': 0x4A4F5B, 'Orange': 0x5B616D, 'Violet': 0x2F3442, 'Green': 0x3E4350, 'Cyan': 0x242833, 'Blue': 0x353A47, 'Pink': 0x666C78}

def avatar_overrides(dark):
    d = {}
    for c, (b1, b2) in AV_BG.items():
        d['avatar_background' + c] = b1; d['avatar_background2' + c] = b2
        d['avatar_nameInMessage' + c] = (AV_NAME_DARK if dark else AV_NAME_LIGHT)[c]
    d['avatar_backgroundGray'] = 0x5A5F6B
    d['avatar_backgroundArchived'] = 0x3A3F4D
    return d

for name in DARK_ASSETS + LIGHT_ASSETS:
    SRC = os.path.join(ROOT, 'tools/porcodio/upstream/%s.attheme' % name)
    DST = os.path.join(ROOT, 'TMessagesProj/src/main/assets/%s.attheme' % name)
    if not os.path.exists(SRC):
        import shutil; os.makedirs(os.path.dirname(SRC), exist_ok=True); shutil.copy(DST, SRC)
    dark = name in DARK_ASSETS
    lines = open(SRC, encoding='utf-8').read().splitlines()
    res = []; seen = set()
    for ln in lines:
        m = re.match(r'^(\w+)=(.+)$', ln)
        if not m: res.append(ln); continue
        key, val = m.group(1), m.group(2)
        try: argb, kind = parse(val)
        except ValueError: res.append(ln); continue
        seen.add(key)
        if not dark:
            new = light_transform(argb)
        elif key in OVERRIDE:
            new = (argb & 0xFF000000) | OVERRIDE[key] if OVERRIDE[key] <= 0xFFFFFF else OVERRIDE[key]
            if (argb >> 24) == 0xFF: new |= 0xFF000000
        elif TEXT_ON_ACCENT.search(key):
            new = 0xFF000000 | DARK_ON_ACCENT
        else:
            new = transform(key, argb)
        if dark: new = out_override(key, new)
        res.append('%s=%s' % (key, fmt(new, kind)))
    if dark:
        for k, v in OVERRIDE.items():
            if k not in seen: res.append('%s=%s' % (k, fmt(0xFF000000 | (v & 0xFFFFFF), 'int')))
    av = avatar_overrides(dark); done = set(); res2 = []
    for ln in res:
        m = re.match(r'^(\w+)=', ln)
        if m and m.group(1) in av:
            res2.append('%s=%s' % (m.group(1), fmt(0xFF000000 | av[m.group(1)], 'int'))); done.add(m.group(1))
        else: res2.append(ln)
    for k, v in av.items():
        if k not in done: res2.append('%s=%s' % (k, fmt(0xFF000000 | v, 'int')))
    res = res2
    open(DST, 'w', encoding='utf-8').write('\n'.join(res) + '\n')
    print(name, 'chiavi:', len(seen))
