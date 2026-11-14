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
 'PcUpdTitle': ('Update available', 'Aggiornamento disponibile'),
 'PcUpdMessage': ('Version %1$s is ready to install.', 'La versione %1$s è pronta da installare.'),
 'PcUpdNow': ('Update', 'Aggiorna'),
 'PcUpdLater': ('Later', 'Più tardi'),
 'PcUpdChecking': ('Checking for updates...', 'Controllo aggiornamenti...'),
 'PcUpdUpToDate': ('You are up to date (%1$s).', 'Sei aggiornato (%1$s).'),
 'PcUpdFailed': ('Could not check or download the update. Try again later.', 'Impossibile controllare o scaricare l\'aggiornamento. Riprova più tardi.'),
 'PcUpdDownloading': ('Downloading... %1$d%%', 'Scaricamento... %1$d%%'),
 'PcUpdVerifying': ('Checking the file...', 'Verifico il file...'),
 'PcUpdBadHash': ('The downloaded file does not match and was discarded.', 'Il file scaricato non corrisponde ed è stato scartato.'),
 'PcUpdAllow': ('Allow installing from this app, then tap Update again.', 'Consenti l\'installazione da quest\'app, poi tocca di nuovo Aggiorna.'),
 'PcUpdSettings': ('Check for updates', 'Controlla aggiornamenti'),
 'PcUpdSettingsInfo': ('Automatic updates for Porcodio Luxury Chat', 'Aggiornamenti automatici di Porcodio Luxury Chat'),
 'PcSetFeatures': ('Porcodio features', 'Funzioni Porcodio'),
 'PcSetShopInfo': ('Products, search and filters', 'Prodotti, ricerca e filtri'),
 'PcSetPrivateTools': ('Chat lock and private notes', 'Blocco chat e note private'),
 'PcSetPrivateToolsInfo': ('How to use them', 'Come si usano'),
 'PcSetPrivateToolsHow': ('Open a contact profile: there you can lock that chat with your fingerprint or phone unlock, and write a private note only you can see. Both stay on this phone.', 'Apri il profilo di un contatto: lì puoi bloccare quella chat con impronta o sblocco del telefono e scrivere una nota privata che vedi solo tu. Restano entrambe su questo telefono.'),
 'PcSetAntispamInfo': ('Requests, spam, block and report', 'Richieste, spam, blocca e segnala'),
 'PcSetAccount': ('Account and privacy', 'Account e privacy'),
 'PcSetPrivacy': ('Privacy and security', 'Privacy e sicurezza'),
 'PcSetNotifications': ('Notifications', 'Notifiche'),
 'PcSetData': ('Data and storage', 'Dati e memoria'),
 'PcSetDevices': ('Devices', 'Dispositivi'),
 'PcSetLook': ('Look and chats', 'Aspetto e chat'),
 'PcSetTheme': ('Theme and chat look', 'Tema e aspetto chat'),
 'PcSetFolders': ('Chat folders', 'Cartelle chat'),
 'PcSetLanguage': ('Language', 'Lingua'),
 'PcSetPower': ('Power saving', 'Risparmio energetico'),
 'PcSetInfo': ('Information', 'Informazioni'),
 'PcSetAbout': ('About the app', 'Info sull\'app'),
 'PcSetAdvanced': ('Advanced', 'Avanzate'),
 'PcSetOriginal': ('All original settings', 'Tutte le impostazioni originali'),
 'PcSetOriginalInfo': ('Premium, wallet, business and the rest', 'Premium, wallet, business e il resto'),
 'PcSetEditProfile': ('Edit profile', 'Modifica profilo'),
 'PcFeaturesChip': ('FEATURES', 'FUNZIONI'),
 'PcShopSearch': ('Search', 'Cerca'),
 'PcShopSearchHint': ('Product name', 'Nome del prodotto'),
 'PcShopAll': ('All', 'Tutti'),
 'PcShopSortLow': ('Price: low to high', 'Prezzo crescente'),
 'PcShopSortHigh': ('Price: high to low', 'Prezzo decrescente'),
 'PcShopOnlyAvailable': ('In stock', 'Disponibili'),
 'PcShopCount': ('%1$d products', '%1$d prodotti'),
 'PcShopNoResults': ('No product matches these filters.', 'Nessun prodotto con questi filtri.'),
 'PcSetSecurity': ('Account and security', 'Account e sicurezza'),
 'PcSetPrivacyInfo': ('Who can see you and contact you', 'Chi ti vede e chi può scriverti'),
 'PcSetSecretChats': ('Super secret chats', 'Chat super segrete'),
 'PcSetSecretChatsInfo': ('Extra-protected private conversations', 'Conversazioni private extra protette'),
 'PcSetSecretChatsHow': ('Super secret chats arrive in an upcoming update through automatic updates: a second layer of encryption between two people who use the app, self-destruct timer, screenshot block and fingerprint lock.', 'Le chat super segrete arrivano con un prossimo aggiornamento automatico: un secondo livello di cifratura tra due persone che usano l\'app, timer di autodistruzione, blocco degli screenshot e blocco con impronta.'),
 'PcSetDevicesInfo': ('Where your account is signed in', 'Dove il tuo account è connesso'),
 'PcSetThemeInfo': ('Colours, backgrounds, text size', 'Colori, sfondi, dimensione del testo'),
 'PcSetFoldersInfo': ('Organise your chats', 'Organizza le tue chat'),
 'PcSetNotificationsRow': ('Alerts and sounds', 'Avvisi e suoni'),
 'PcSetNotificationsInfo': ('Messages, groups, calls', 'Messaggi, gruppi, chiamate'),
 'PcSetSpace': ('Space and data', 'Spazio e dati'),
 'PcSetDataInfo': ('Storage, downloads, network', 'Memoria, download, rete'),
 'PcSetPowerInfo': ('Lighter animations to save battery', 'Animazioni più leggere per risparmiare batteria'),
 'PcSetSocial': ('Porcodio social', 'Social Porcodio'),
 'PcSetShopOrders': ('Shop and orders', 'Shop e ordini'),
 'PcSetSupport': ('Support and info', 'Supporto e info'),
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
