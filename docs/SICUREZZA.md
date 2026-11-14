# Sicurezza dei messaggi (v8)

Tutto è lato client a costo zero, tranne due privacy del server (inviti ai gruppi, chiamate). Impostazioni > Sicurezza dei messaggi. Codice: `PcPolicy`, `PcSecurityActivity`, `PcRequestsActivity`, `PcHome`.

## Blocca gruppi sconosciuti
* Server: `account.setPrivacy` con la chiave `chatInvite`: Solo contatti (default) oppure Nessuno.
* Client: all'attivazione si fotografano i gruppi che hai già (restano dove sono). I gruppi nuovi comparsi dopo, che non hai creato e in cui non hai scritto, vanno in Richieste (o Spam se sembrano spam, stesso punteggio antispam delle persone). "Accetta" li riporta in Gruppi.
* Uscita automatica: opzionale, spenta di default, con avviso; esce solo dai gruppi nuovi non accettati.

## Richieste di contatto
Tutti / Contatti dei contatti / Nessuno. Il primo messaggio di un non contatto va in Richieste. Con "Contatti dei contatti" restano in Richieste solo le persone con almeno un gruppo in comune (`common_chats_count` del profilo completo): le altre sono archiviate e silenziate. Con "Nessuno" sono archiviate e silenziate tutte. Nessuna chat viene cancellata. Schermata Richieste: Accetta (aggiunge ai contatti, toglie archivio e silenzio), Rifiuta (elimina la chat o esce dal gruppo), Rifiuta e blocca, Apri.

## Chi può scrivermi
Tutti / Solo contatti / Solo verificati / Nessuno (solo chat esistenti). **E' un filtro del client**: il messaggio arriva comunque al telefono, qui viene nascosto dalla lista e non fa notifica; il server non lo blocca (per bloccare davvero c'è Blocca sul profilo). Le chat esistenti al momento dell'attivazione e quelle accettate restano visibili. I messaggi nascosti si rivedono in Richieste. Le chiamate seguono la privacy del server (solo contatti o nessuno).

## Notifiche delle chat super segrete
Mai testo in chiaro: per i messaggi `PCS1:` (anche da push, Wear, risposta rapida) e per ogni testo in una chat con la funzione attiva la notifica dice solo "Nuovo messaggio segreto".

## Pulizia in blocco
Impostazioni > Pulizia in blocco (`PcBulkActivity`, `PcBulk`). Filtri: Tutte, Private, Gruppi, Canali, Bot, Archiviate, Non lette, Inattive da N giorni (7/30/90/180/365). Azioni: Elimina chat (private, bot), Esci ed elimina (gruppi, canali; non per chi li ha creato), Archivia, Rimuovi dall'archivio, Segna come lette. Lotti da 4 chat ogni 1,5 s, barra di avanzamento, Annulla, pausa automatica quando il server risponde FLOOD_WAIT_n (rilevato in `ConnectionsManager`), riepilogo finale. Doppia conferma per le azioni che cancellano.

## Non incluso in v8
* Cifratura dei media nelle chat super segrete: rimandata a v9 (richiede di integrare la decifratura nella visualizzazione dei messaggi con allegato).
