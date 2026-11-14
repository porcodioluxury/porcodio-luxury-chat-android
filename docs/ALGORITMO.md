# Algoritmo di distribuzione del social interno (PcRanking)

Vale per post, reel e storie del social interno di Porcodio Luxury Chat. Gira **tutto sul telefono**, a costo zero: nessun server nostro, nessun dato inviato. Codice: `PcRanking.java` (punteggio e ordinamento), `PcInterests.java` (profilo interessi), `PcInterestsActivity.java` (controlli utente). **Tutti i pesi stanno in `PcRanking.Config`** (e `PcInterests` per i segnali).

## Punteggio

```
punteggio = freschezza × qualità × (interessi) × verificati × penalità
```

| Fattore | Formula | Valori |
|---|---|---|
| Freschezza F | `0.5^(età_ore / 24)` | emivita 24 h nel feed, 72 h in Esplora |
| Qualità Q | `1 + min(1, ln(1+S)/ln(1+200))` con `S = 1·like + 2·commenti + 3·condivisioni + 2.5·salvataggi + 10·completamento_reel + 0.05·visualizzazioni` | da 1 a 2 (normalizzata) |
| Interessi I (feed) | `1 + 1.0·somiglianza + 0.8·affinità`, poi `× (1 − 0.5·argomenti_non_graditi)` | somiglianza 0..1, affinità 0..1 |
| Interessi I (Esplora) | `1 + 0.5·somiglianza + 0.2 se autore mai visto` | più spazio alla novità |
| Verificati V | ×1.5 (feed), boost ×1.5 (Esplora) | solo se la verifica (spunta blu) è attiva, v8 |
| Penalità P | spam ×0.25, segnalato ×0.1 | si moltiplicano |

* **Diversità**: nel riordino, se gli ultimi k post sono dello stesso autore, il candidato vale ×0.7^k.
* **Esplorazione (~15%)**: circa il 15% del feed sono contenuti nuovi o fuori interesse (somiglianza < 0.1 e autore mai interagito), in posizioni scelte con un seme giornaliero, per non chiudere l'utente in una bolla.
* **Esplora e ricerca**: i verificati hanno ×1.5 e un posto garantito ogni 5 risultati (posizioni 5, 10, 15…).
* **Feed "Seguiti"**: alternativa cronologica pura (dal più recente), senza personalizzazione. Si cambia col tasto "Per te / Seguiti" nella scheda Social.

## Account ufficiale (Mario Ribecco / PORCODIO LUXURY)

Gli ID si configurano in `res/values/pc_config.xml` (`pc_official_user_ids`, separati da virgola; **oggi vuoto: serve l'ID di Mario**). Per tutti gli utenti:
* **Storia** sempre prima nella riga delle storie, con etichetta "Ufficiale".
* **Ultimo post** in uno slot fisso in cima al feed (anche in "Seguiti"); **ultimo reel** in cima ai Reel.
* **Esplora**: ultimo contenuto ufficiale in posizione 2 (sempre entro le prime 3).
* Etichetta discreta **"Ufficiale"** sul post, per trasparenza.

## Profilo interessi (solo sul dispositivo, cifrato)

Vettore argomento→peso più affinità per account, salvati cifrati con una chiave dell'Android Keystore. Entrambi **decadono nel tempo** (emivita 14 giorni), così gli interessi si aggiornano.

Argomenti di un contenuto: **hashtag** (peso 1.0), **categorie scelte da chi pubblica** (scritte come hashtag), **parole chiave** della didascalia (peso 0.3, max 12, senza parole comuni).

Segnali (pesi in `PcInterests`):

| Segnale | Peso |
|---|---|
| Tempo di visione | +0.15 per secondo, max +3 |
| Reel completato / rivisto | +2 / +1.5 |
| Like / commento / condivisione / salvataggio | +3 / +4 / +5 / +4 |
| Apertura post / visita profilo | +1.5 / +2 |
| Scorso via in meno di 1 s | −1 |
| "Non mi interessa" | −5 |
| Nascondi autore | −8 |

Stato attuale: tempo di visione (approssimato dal tempo in cui la riga resta a schermo), like, apertura, "Non mi interessa", nascondi, segnala sono attivi. Commenti, condivisioni, salvataggi, completamento reel e visite al profilo sono pesi già in config, collegati ai segnali quando le rispettive funzioni arrivano.

## Controlli per l'utente
* **Perché vedo questo** (menu ⋯ sul post): mostra freschezza, qualità, somiglianza, affinità, boost/penalità e punteggio.
* **Interessi e algoritmo** (Impostazioni): elenco argomenti con **+ / −**, **Azzera interessi**.
* **Per te / Seguiti**: feed personalizzato o cronologico.
* **Non mi interessa**, **Nascondi autore**, **Segnala**.

## Esempi numerici

Formula con i valori sopra (arrotondati).

* **A** · post di 12 h, 40 like, 5 commenti, 3 condivisioni, 800 visualizzazioni, somiglianza 0.6, affinità 0.4 (contatto 0.3 + 0.1): F=0.707, S=40+10+9+40=99 → Q=1.868, I=1+0.6+0.32=1.92 → **punteggio 2.537**.
* **B** · stessi numeri ma autore sconosciuto e argomento nuovo (somiglianza 0, affinità 0): I=1 → **1.321**.
* **C** · come B ma autore verificato (×1.5) → **1.982**: supera B ma resta sotto A, che ha interessi più vicini.
* **D** · post di 48 h con tanto coinvolgimento (400 like, 60 commenti, 40 condivisioni, 9000 visualizzazioni): F=0.25, S=1090 → Q=2.000, I=1.92 → **0.960**: la freschezza pesa, ma un post molto apprezzato resta in gara.
* **E** · come A ma segnalato (×0.1) → **0.254**; **F** · come A ma spam (×0.25) → **0.634**.
* **Esplora** · B con emivita 72 h e bonus novità: F=0.891, I=1.2 → **1.997**; lo stesso verificato (×1.5) → **2.996**. Dopo 72 h B scende a **1.121**.
* **Diversità** · tre post consecutivi dello stesso autore con punteggio 2.5: il secondo vale 2.5×0.7=1.75, il terzo 2.5×0.49=1.23, quindi un post di un altro autore da 1.5 passa avanti al terzo.
* **Esplorazione** · su un feed di 40 post, 15% = 6 posti riservati a contenuti fuori interesse, in posizioni diverse ogni giorno (mai nei primi due).

## Limiti onesti
* È un ordinamento **lato client** sui post che l'app scarica dalle Bacheche degli utenti (nessun indice globale a costo zero): la scoperta è limitata alle Bacheche già note.
* Commenti, condivisioni e visualizzazioni arrivano dai contatori del canale; salvataggi e completamento reel non esistono ancora come dati.
* Lo stato "verificato" arriva da `PcRanking.verifiedSource`: oggi sempre falso; in v8 va collegato a una fonte non falsificabile dal client.
