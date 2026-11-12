#!/usr/bin/env python3
"""Rebrand testuale: 'Telegram' (app) -> 'Porcodio Luxury Chat' nelle stringhe visibili.
Restano invariati URL, @handle e i riferimenti al servizio/API/prodotti Telegram (Premium, Stars, Business, Passport, Ads...)."""
import glob, os, re
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'TMessagesProj/src/main/res')
NEW = 'Porcodio Luxury Chat'
KEEP_AFTER = r'(?:Premium|Stars|Business|Passport|Ads|Gifts?|Terms|Privacy|API|Bots?|Web|Desktop|Support|Tips|FAQ|Faq|Features|Messenger|Gateway|Wallet|Open Network|Blog|Channel|News)'
URL = re.compile(r'(https?://\S+|\b[\w.-]*t\.me/\S*|\btelegram\.(?:org|me)\S*|@\w*[Tt]elegram\w*|tg://\S*)')
TG = re.compile(r'(?<![\w/@.-])Telegram(?!\w)(?!\s+' + KEEP_AFTER + r'\b)')
STR = re.compile(r'(<string\s+name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)

def sub_text(t):
    out, pos = [], 0
    for m in URL.finditer(t):
        out.append(TG.sub(NEW, t[pos:m.start()])); out.append(m.group(0)); pos = m.end()
    out.append(TG.sub(NEW, t[pos:]))
    return ''.join(out)

total = 0
for f in glob.glob(RES + '/values*/strings.xml'):
    s = open(f, encoding='utf-8').read()
    def rep(m):
        global total
        name, body = m.group(2), m.group(3)
        if re.search(r'(Url|URL|Uri|Link)$', name) or name.startswith(('TelegramPremium','TelegramBusiness','TelegramStars','TelegramPassport')) and False:
            return m.group(0)
        nb = sub_text(body)
        if nb != body: total += 1
        return m.group(1) + nb + m.group(4)
    ns = STR.sub(rep, s)
    if ns != s: open(f, 'w', encoding='utf-8').write(ns)
print('stringhe modificate:', total)
