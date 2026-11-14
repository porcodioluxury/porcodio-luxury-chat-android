#!/usr/bin/env python3
"""Stringhe v8 (EN, IT). Idempotente: aggiunge le chiavi mancanti in values/ e values-it/ prima del marcatore finale."""
import os
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
S = {
 'PcSavePost': ('Save to Saved messages', 'Salva nei Messaggi salvati'),
 'PcSharePost': ('Share', 'Condividi'),
 'PcSavedOk': ('Saved', 'Salvato'),
 'PcSavedFail': ('Could not save the post', 'Impossibile salvare il post'),
 'PcSecretNotif': ('New secret message', 'Nuovo messaggio segreto'),
}
def esc(s):
    return s.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('&', '&amp;').replace('<', '&lt;')
MARK = '    <!-- /PORCODIO PHASE3 -->'
for rel, idx in (('TMessagesProj/src/main/res/values/strings.xml', 0), ('TMessagesProj/src/main/res/values-it/strings.xml', 1)):
    p = os.path.join(ROOT, rel)
    t = open(p, encoding='utf-8').read()
    add = ''.join('    <string name="%s">%s</string>\n' % (k, esc(v[idx])) for k, v in S.items() if 'name="%s"' % k not in t)
    assert MARK in t
    t = t.replace(MARK, add + MARK)
    open(p, 'w', encoding='utf-8').write(t)
print('ok')
