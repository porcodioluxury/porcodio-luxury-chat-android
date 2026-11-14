# Porcodio Luxury Chat

**Porcodio Luxury Chat — unofficial app based on the Telegram API, not affiliated with Telegram.**

*App di messaggistica Android di Porcodio Luxury, basata sul client open source di Telegram e sulle API di Telegram. Non è un'app ufficiale e non è affiliata a Telegram.*

This app uses the Telegram API. / Questa app utilizza le API di Telegram.

---

## Italiano

Porcodio Luxury Chat è un fork di [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (client Android open source di Telegram, versione base 13.2.2, build 7303) con il marchio e lo stile **Porcodio Luxury**.

- Package / applicationId: `com.porcodioluxury.chat`
- Nome app: **Porcodio Luxury Chat**
- Tema scuro predefinito con la palette Porcodio (`#121212`, `#1A1A1A`, `#242833`, `#2F3442`, testo `#F3F3F3` / `#BFBFBF`), nessun accento oro.
- Chiamate, gruppi, canali, file e sincronizzazione multi-dispositivo provengono dal client upstream e sono mantenuti.
- **v4 (fase 3)**: home con schede fisse Private / Gruppi / Canali (con contatori), sezioni antispam locali *Richieste* e *Spam* con blocco/segnalazione in blocco, silenzia tutti i gruppi, *Pulizia rapida* in Impostazioni (sempre con conferma), riga preferiti in cima a Private, scheda **Shop** nativa (catalogo pubblico Shopify in sola lettura, nessun token admin; *Acquista* apre il sito, *Condividi in chat* invia il link). Le chiamate restano raggiungibili dal menu lungo di Contatti.
- **v5 (fase 4)**: scheda profilo ridisegnata (intestazione con targhetta, azioni rapide, pannelli, Bacheca, media, nota privata, blocco chat con impronta), chat fissate come card più alte, antispam a punteggio (link + parole chiave + messaggio ripetuto in più chat + profilo senza foto; un solo indizio non basta per finire in Spam), **Feed** e **Bacheca** interni solo per chi usa l'app (nessun server nostro), *Risposte rapide* dal menu della chat, note private sui contatti.
- `api_id` e `api_hash` Telegram **non sono nel codice**: sono letti in fase di build dalle variabili d'ambiente `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` (in GitHub Actions sono Secrets del repository).
- Firebase: il repository contiene un `google-services.json` **fittizio** (solo per far compilare). Le notifiche push FCM non sono attive finché non viene collegato un progetto Firebase vero; l'app funziona comunque con la connessione diretta.

### Conformità GPL

Questo software è un'opera derivata di Telegram per Android ed è distribuito con licenza **GNU GPL v2** (vedi [LICENSE](LICENSE)). Sono mantenuti i copyright originali (© Nikolai Kudashov e collaboratori) nelle intestazioni dei file sorgente. Il **codice sorgente completo e modificato** di ogni APK distribuita è disponibile in questo repository; chi riceve l'app ha gli stessi diritti previsti dalla GPL (usare, studiare, modificare, ridistribuire). Il README originale di upstream è in [README.upstream.md](README.upstream.md).

### Conformità Telegram API

- L'uso dell'API di Telegram è dichiarato in modo visibile (questo README, descrizione dello store, introduzione dell'app).
- Nel nome dell'app non compare "Telegram" e non viene usato il logo di Telegram.
- Ogni monetizzazione va dichiarata e rispettare i [Terms of Service dell'API di Telegram](https://core.telegram.org/api/terms).

### Compilazione

Vedi [`.github/workflows/android.yml`](.github/workflows/android.yml): compila un APK release firmato su `ubuntu-latest` (runner gratuito) e lo carica come artifact.
In locale: JDK 17, Android SDK 36, NDK 27.2.12479018, CMake 3.22.1, poi

```bash
export TELEGRAM_API_ID=... TELEGRAM_API_HASH=...
./gradlew :TMessagesProj_App:assembleAfatDebug -PPORCODIO_ABIS=arm64-v8a
```

Script di rebrand riproducibili in [`tools/porcodio/`](tools/porcodio).

---

## English

Porcodio Luxury Chat is a fork of [DrKLO/Telegram](https://github.com/DrKLO/Telegram) (the open-source Telegram for Android client, base version 13.2.2, build 7303) with **Porcodio Luxury** branding.

- applicationId: `com.porcodioluxury.chat`, app name **Porcodio Luxury Chat**, dark theme by default with the Porcodio palette, no gold accent.
- Calls, groups, channels, files and multi-device sync come from the upstream client and are kept.
- **v4 (phase 3)**: home with fixed Private / Groups / Channels tabs (with unread counters), local anti-spam *Requests* and *Spam* sections with bulk block/report, mute all groups, *Quick cleanup* in Settings (always behind a confirmation), a favourites row on top of Private, and a native **Shop** tab (read-only public Shopify catalogue, no admin token; *Buy* opens the website, *Share in chat* sends the link). Calls stay reachable from the Contacts long-press menu.
- **v5 (phase 4)**: redesigned profile screen (header with badge, quick actions, panels, Board, media, private note, fingerprint chat lock), taller pinned-chat cards, scored anti-spam (links + keywords + same message in several chats + profile without photo; a single hint is no longer enough for Spam), in-app **Feed** and **Board** only for people who use the app (no server of ours), *Quick replies* from the chat menu, private notes on contacts.
- Telegram `api_id`/`api_hash` are **not in the source**: they are injected at build time from `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` environment variables (GitHub Actions Secrets).
- Firebase: a **placeholder** `google-services.json` is committed so the build works; FCM push is inactive until a real Firebase project is wired in.

### GPL compliance

This is a derivative work of Telegram for Android, licensed under the **GNU GPL v2** (see [LICENSE](LICENSE)). Original copyright notices are preserved. The complete corresponding source of every distributed APK is available in this repository. Upstream's README is kept as [README.upstream.md](README.upstream.md).

### Telegram API compliance

This app uses the Telegram API and is **unofficial and not affiliated with Telegram**. It does not use the Telegram name in the app title or the Telegram logo, and any monetization must be declared per the [Telegram API Terms of Service](https://core.telegram.org/api/terms).
