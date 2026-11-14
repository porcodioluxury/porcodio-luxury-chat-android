#!/usr/bin/env python3
"""Stringhe della fase 4 (EN, IT). Inserisce le chiavi mancanti in values/ e values-it/ prima del marcatore finale.
Corregge anche PcBoardOffText (nessun nome di terzi nell'interfaccia)."""
import re, os
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
S = {
 'PcNoteTitle': ('Private note', 'Nota privata'),
 'PcNoteEmpty': ('Add a private note', 'Aggiungi una nota privata'),
 'PcNoteHint': ('Only you can see this note.', 'Solo tu puoi vedere questa nota.'),
 'PcNoteSave': ('Save', 'Salva'),
 'PcProfileAppUser': ('Uses Porcodio Luxury Chat', 'Usa Porcodio Luxury Chat'),
 'PcProfileBoardLooking': ('Looking for the Board...', 'Cerco la Bacheca...'),
 'PcProfileBoardNone': ('No Board found', 'Nessuna Bacheca trovata'),
 'PcProfileLockOn': ('Locked', 'Bloccata'),
 'PcFeedFrom': ('Posts from %1$s', 'Post di %1$s'),
 'PcFeedInfo': ('Feed and Board', 'Feed e Bacheca'),
 'PcFeedInfoSub': ('Posts from friends who use this app', 'Post degli amici che usano quest\'app'),
 'PcFeedLoading': ('Loading posts...', 'Carico i post...'),
 'PcBoardBusy': ('Looking for friends, wait a moment...', 'Cerco amici, un attimo...'),
 'PcBoardNoBudget': ('Search limit reached for today. Come back tomorrow.', 'Limite di ricerche raggiunto per oggi. Torna domani.'),
 'PcBoardNoFound': ('No new boards found.', 'Nessuna nuova bacheca trovata.'),
 'PcBoardPostHint': ('Write what you want to share', 'Scrivi cosa vuoi condividere'),
 'PcOn': ('On', 'Attivo'),
 'PcOff': ('Off', 'Spento'),
 'PcLikes': ('%1$d', '%1$d'),
 'PcViews': ('%1$d views', '%1$d visualizzazioni'),
 'PcQuickInsert': ('Insert a quick reply', 'Inserisci una risposta rapida'),
 'PcPinnedSection': ('Pinned', 'Fissate'),
 'PcAntispamReasonLinks': ('Links from unknown people', 'Link da sconosciuti'),
 'PcAntispamReasonRepeat': ('Same message repeated', 'Stesso messaggio ripetuto'),
 'PcAntispamReasonNew': ('New account with no photo', 'Account nuovo senza foto'),
 'PcAntispamReasonKeywords': ('Promotional keywords', 'Parole promozionali'),
}
FIX = {
 'PcBoardOffText': (
  "Activating creates a public channel named after you with a link that only this app knows how to find, and adds a small tag ({tag}) to your bio. Anyone with the link can read your posts. You can turn it off any time.",
  "Attivandola si crea un canale pubblico a tuo nome con un link che solo quest'app sa trovare, e si aggiunge un piccolo marcatore ({tag}) alla tua bio. Chiunque abbia il link può leggere i tuoi post. Puoi spegnerla quando vuoi."),
}
def esc(s):
    return s.replace('\\', '\\\\').replace("'", "\\'").replace('"', '\\"').replace('&', '&amp;').replace('<', '&lt;')
MARK = '    <!-- /PORCODIO PHASE3 -->'
for rel, idx in (('TMessagesProj/src/main/res/values/strings.xml', 0), ('TMessagesProj/src/main/res/values-it/strings.xml', 1)):
    p = os.path.join(ROOT, rel)
    t = open(p, encoding='utf-8').read()
    for k, v in FIX.items():
        t = re.sub(r'(<string name="%s">).*?(</string>)' % k, lambda m: m.group(1) + esc(v[idx]) + m.group(2), t, flags=re.S)
    add = ''.join('    <string name="%s">%s</string>\n' % (k, esc(v[idx])) for k, v in S.items() if 'name="%s"' % k not in t)
    assert MARK in t
    t = t.replace(MARK, add + MARK)
    open(p, 'w', encoding='utf-8').write(t)
print('ok')
