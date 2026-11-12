#!/usr/bin/env python3
"""Stringhe Porcodio fase 3 (EN/IT). Idempotente: riscrive il blocco marcato in values/ e values-it/strings.xml."""
import os, re
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
RES = os.path.join(ROOT, 'TMessagesProj/src/main/res')
S = {
 'PcTabPrivate': ('Private', 'Private'),
 'PcTabGroups': ('Groups', 'Gruppi'),
 'PcTabChannels': ('Channels', 'Canali'),
 'PcTabRequests': ('Requests', 'Richieste'),
 'PcTabSpam': ('Spam', 'Spam'),
 'PcAntispamMenu': ('Antispam', 'Antispam'),
 'PcBlockReportRequests': ('Block and report all Requests', 'Blocca e segnala tutte le Richieste'),
 'PcBlockReportSpam': ('Block and report all Spam', 'Blocca e segnala tutto lo Spam'),
 'PcAcceptRequests': ('Accept all Requests', 'Accetta tutte le Richieste'),
 'PcMuteAllGroups': ('Mute all groups', 'Silenzia tutti i gruppi'),
 'PcQuickCleanup': ('Quick cleanup', 'Pulizia rapida'),
 'PcConfirmBlockTitle': ('Block and report?', 'Bloccare e segnalare?'),
 'PcConfirmBlockText': ('%1$d chats will be reported as spam and blocked.', '%1$d chat saranno segnalate come spam e bloccate.'),
 'PcConfirmAcceptTitle': ('Accept requests?', 'Accettare le richieste?'),
 'PcConfirmAcceptText': ('%1$d chats will be moved to Private.', '%1$d chat saranno spostate in Private.'),
 'PcConfirmMuteTitle': ('Mute all groups?', 'Silenziare tutti i gruppi?'),
 'PcConfirmMuteText': ('%1$d groups will be muted.', '%1$d gruppi saranno silenziati.'),
 'PcNothingToDo': ('Nothing to do here.', 'Niente da fare qui.'),
 'PcDoBlock': ('Block and report', 'Blocca e segnala'),
 'PcDoAccept': ('Accept', 'Accetta'),
 'PcDoMute': ('Mute', 'Silenzia'),
}
EXTRA = os.path.join(os.path.dirname(__file__), 'pc_strings_extra.py')
if os.path.exists(EXTRA):
    ns = {}; exec(open(EXTRA).read(), ns); S.update(ns['S'])

def esc(v, it):
    v = v.replace('&', '&amp;').replace('<', '&lt;')
    v = v.replace("'", "\\'")
    return v

def write(path, idx):
    t = open(path, encoding='utf-8').read()
    t = re.sub(r'\n?\s*<!-- PORCODIO PHASE3 -->.*?<!-- /PORCODIO PHASE3 -->\n?', '\n', t, flags=re.S)
    block = '    <!-- PORCODIO PHASE3 -->\n' + ''.join('    <string name="%s">%s</string>\n' % (k, esc(v[idx], idx)) for k, v in S.items()) + '    <!-- /PORCODIO PHASE3 -->\n'
    t = t.replace('</resources>', block + '</resources>')
    open(path, 'w', encoding='utf-8').write(t)

write(os.path.join(RES, 'values/strings.xml'), 0)
write(os.path.join(RES, 'values-it/strings.xml'), 1)
print(len(S), 'stringhe')
