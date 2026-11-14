#!/usr/bin/env python3
"""Stringhe v8 (EN, IT). Idempotente: aggiunge le chiavi mancanti in values/ e values-it/ prima del marcatore finale."""
import os
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
S = {
 'PcUpdTitleNew': ('New version available', 'Nuova versione disponibile'),
 'PcUpdNowBtn': ('Update now', 'Aggiorna ora'),
 'PcUpdNotes': ('What is new', 'Novità'),
 'PcUpdFrom': ('(you have %1$s)', '(hai la %1$s)'),
 'PcUpdDownloadingMb': ('Downloading... %1$d%% (%2$s of %3$s MB)', 'Scarico... %1$d%% (%2$s di %3$s MB)'),
 'PcUpdErrTitle': ('Update problem', 'Problema con l\'aggiornamento'),
 'PcUpdErrNet': ('Could not check for updates. Check your connection and try again.', 'Impossibile controllare gli aggiornamenti. Controlla la connessione e riprova.'),
 'PcUpdErrDownload': ('The download was interrupted. Check your connection and try again.', 'Il download si è interrotto. Controlla la connessione e riprova.'),
 'PcUpdErrInstall': ('The installation did not complete: %1$s', 'L\'installazione non è riuscita: %1$s'),
 'PcUpdRetry': ('Try again', 'Riprova'),
 'PcUpdClose': ('Close', 'Chiudi'),
 'PcUpdPermTitle': ('One permission is needed', 'Serve un permesso'),
 'PcUpdPermText': ('To install the update Android asks you to allow Porcodio Luxury Chat to "Install unknown apps". I will open that screen: turn the switch on, then come back here and the installation continues by itself.', 'Per installare l\'aggiornamento Android chiede di consentire a Porcodio Luxury Chat di "Installare app sconosciute". Ti porto a quella schermata: attiva l\'interruttore, poi torna qui e l\'installazione riparte da sola.'),
 'PcUpdOpenSettings': ('Open settings', 'Apri impostazioni'),
 'PcUpdReadyTitle': ('Update ready', 'Aggiornamento pronto'),
 'PcUpdReadyText': ('Version %1$s was downloaded and checked. Install it now?', 'La versione %1$s è stata scaricata e verificata. Installarla ora?'),
 'PcUpdInstallBtn': ('Install', 'Installa'),
 'PcUpdCancelled': ('Installation cancelled', 'Installazione annullata'),
 'PcUpdScreenTitle': ('Updates', 'Aggiornamenti'),
 'PcUpdCurrent': ('Installed version: %1$s', 'Versione installata: %1$s'),
 'PcUpdSearch': ('Check for updates', 'Cerca aggiornamenti'),
 'PcUpdAutoToggle': ('Automatic update', 'Aggiornamento automatico'),
 'PcUpdAutoInfo': ('When on, the new version is downloaded in the background and you are only asked to install it.', 'Se attivo, la nuova versione si scarica in background e ti viene chiesto solo di installarla.'),
 'PcUpdStatusAvailable': ('New version available: %1$s', 'Nuova versione disponibile: %1$s'),
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
