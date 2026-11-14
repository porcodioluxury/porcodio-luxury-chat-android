# Sicurezza e privacy del portafoglio (v12)

Il portafoglio di PORCODIO LUXURY Chat e' **non-custodial**: la frase di recupero (BIP39) e le chiavi esistono solo sul dispositivo.

## Chiavi
- Frase di recupero cifrata con AES-256-GCM, chiave nel Keystore Android (non esportabile). Mai inviata a nessun server.
- Sblocco con PIN (PBKDF2-SHA512, blocco progressivo dopo 5 errori) o biometria. Blocco automatico dopo 2 minuti e all'uscita dalla schermata.
- Schermata protetta da screenshot (FLAG_SECURE) mentre il portafoglio e' aperto. Avviso se il dispositivo ha root.
- Firma delle transazioni (EIP-1559, chain Base 8453) solo in locale, con conferma del PIN.

## Privacy
Cosa fa l'app:
1. **Nessun collegamento con account o telefono.** L'app non invia indirizzi o saldi a server nostri, non c'e' analitica, non c'e' alcun account del portafoglio.
2. **Indirizzi nuovi (HD).** Ricevi: "Genera un nuovo indirizzo" passa all'indirizzo successivo `m/44'/60'/0'/0/i`. Dalla chat, "Chiedi denaro" condivide **sempre un indirizzo nuovo**. Si salva solo l'indice corrente (mai l'elenco). Massimo 20 indirizzi. Il saldo mostrato e' la somma di tutti gli indirizzi consegnati.
3. **Si paga da un solo indirizzo.** Se nessun indirizzo ha fondi sufficienti (compreso ETH per il gas) l'app lo dice invece di unire i fondi: unire fondi di piu' indirizzi in una transazione li collega sulla blockchain.
4. **RPC pubblici a rotazione casuale** (lista `pc_base_rpcs` in `pc_config.xml`, ordine casuale a ogni richiesta, senza chiavi ne' account), `User-Agent` generico, nessun header identificativo, nessun cookie, nessuna cache.
5. **Nessun log degli indirizzi.** Gli errori di rete registrano solo il tipo di errore.
6. **Usa Tor/Proxy** (Portafoglio -> Privacy). Riusa il proxy **SOCKS5 senza password** configurato nell'app (per esempio Orbot/Tor su `127.0.0.1:9050`). I proxy MTProto non possono trasportare HTTP e non sono usati. Se l'opzione e' attiva e non c'e' un proxy SOCKS5 valido, il portafoglio **non fa richieste** (fail-closed).

Cosa NON possiamo fare (detto chiaramente anche nell'app):
- **La blockchain e' pubblica.** Chi vede un indirizzo vede saldo e movimenti; chi sa che e' tuo lo puo' seguire.
- Gli indirizzi nuovi non rendono anonimo: tutti derivano dalla stessa frase e, se i fondi vengono spostati insieme, si collegano.
- RPC pubblici e Blockscout (cronologia) vedono IP e indirizzi interrogati, a meno che si usi Tor.
- **Nessun mixer** nella build pubblica.

## Mixer / privacy avanzata (rinviato)
Valutato per una build privata per Mario (flavor con altro applicationId, fuori dalla release pubblica). Tornado Cash escluso (sanzionato). Railgun (non sanzionato) ha contratti su Ethereum, Polygon, Arbitrum e BNB Chain; la distribuzione su **Base** e' stata approvata il 31 agosto 2026 ma ha liquidita' trascurabile (meno di 1 dollaro al 16 ottobre 2026) e l'integrazione richiede il motore ZK di Railgun (WASM/JS) e la sincronizzazione degli eventi: non e' realistica nella v12. Rinviato.

## Pagamenti
- Spunta blu: Payment Link Stripe (pagina ospitata da Stripe). Nessuna chiave segreta nell'app. Conferma pagamento e Stripe Identity richiedono un server/webhook (non incluso).
- PaymentSheet in app, Stripe Connect e commissione 5% restano **spenti** (`PcFlags.STRIPE_PAYMENTS=false`).
- On/off-ramp in euro: predisposto dietro `pc_onramp_url` (vuoto = "in arrivo"). Serve registrazione come partner presso Transak, MoonPay o Coinbase Onramp (chiave pubblica; il KYC lo fa il partner).
