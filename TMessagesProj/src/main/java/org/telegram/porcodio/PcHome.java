package org.telegram.porcodio;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Porcodio Luxury Chat: schede fisse della home (Private / Gruppi / Canali) + sezioni antispam
 * (Richieste / Spam). Logica solo lato client: filtra la lista chat principale (cartella 0) in
 * MessagesController.sortDialogs e calcola i contatori non letti per scheda.
 */
public class PcHome {
    public static final int TAB_PRIVATE = 0, TAB_GROUPS = 1, TAB_CHANNELS = 2, TAB_REQUESTS = 3, TAB_SPAM = 4;
    public static final int TABS = 5;

    /** Vero solo mentre è viva la lista chat principale (non selettori, non archivio). */
    public static volatile boolean active;
    public static volatile int tab = TAB_PRIVATE;
    public static final int[] unread = new int[TABS];
    public static final int[] total = new int[TABS];

    private static final String PREFS = "pc_home";
    private static final String KEY_ACCEPTED = "accepted";

    // ---- sicurezza: rimuove da Richieste/Spam le chat accettate esplicitamente
    private static Set<String> accepted;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static synchronized Set<String> accepted() {
        if (accepted == null) {
            accepted = new HashSet<>(prefs().getStringSet(KEY_ACCEPTED, new HashSet<>()));
        }
        return accepted;
    }

    public static synchronized void accept(long dialogId) {
        accepted().add(String.valueOf(dialogId));
        prefs().edit().putStringSet(KEY_ACCEPTED, new HashSet<>(accepted())).apply();
    }

    public static void resetCounts() {
        for (int i = 0; i < TABS; i++) {
            unread[i] = 0;
            total[i] = 0;
        }
    }

    /** Scheda di appartenenza di una chat; -1 = sempre visibile (cartella archivio). */
    public static int classify(MessagesController mc, TLRPC.Dialog d) {
        if (d instanceof TLRPC.TL_dialogFolder) {
            return -1;
        }
        long id = d.id;
        if (DialogObject.isEncryptedDialog(id)) {
            return TAB_PRIVATE;
        }
        if (id > 0) {
            TLRPC.User user = mc.getUser(id);
            if (user == null || user.self || user.bot || id == 777000 || user.contact || user.mutual_contact || user.support || user.verified
                    || ContactsController.getInstance(mc.pcAccount()).isContact(id) || accepted().contains(String.valueOf(id))) {
                return TAB_PRIVATE;
            }
            ArrayList<MessageObject> msgs = mc.dialogMessage.get(id);
            MessageObject last = msgs != null && !msgs.isEmpty() ? msgs.get(0) : null;
            if (last != null && last.isOutOwner()) {
                return TAB_PRIVATE; // ho già scritto io: non è una richiesta
            }
            if (last == null && d.top_message == 0) {
                return TAB_PRIVATE;
            }
            return isSpam(mc, user, last) ? TAB_SPAM : TAB_REQUESTS;
        }
        TLRPC.Chat chat = mc.getChat(-id);
        if (chat != null && org.telegram.messenger.ChatObject.isChannel(chat) && !chat.megagroup) {
            return TAB_CHANNELS;
        }
        return TAB_GROUPS;
    }

    private static final String[] KEYWORDS = {
        "crypto", "cripto", "bitcoin", "btc", "usdt", "airdrop", "binance", "forex", "trading", "nft", "token",
        "investi", "investment", "guadagn", "earn money", "profit", "profitt", "casino", "scommess", "betting",
        "promo", "offerta", "sconto", "giveaway", "click here", "clicca qui", "link in bio", "dm me", "scrivimi in privato",
        "100% gratis", "free money", "soldi facili", "lavoro da casa", "work from home", "t.me/+", "joinchat", "whatsapp.com",
        "porn", "18+", "onlyfans", "escort", "prestito", "loan"
    };

    // ---- antispam a punteggio: non basta un solo indizio (un link da uno sconosciuto con foto va in Richieste, non in Spam)
    private static final java.util.HashMap<String, Integer> repeats = new java.util.HashMap<>();
    private static long repeatsStamp;
    private static MessagesController repeatsOwner;

    private static String norm(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    /** Quante altre chat di sconosciuti hanno ricevuto lo stesso identico testo (campagne di spam). */
    private static synchronized int repeatsOf(MessagesController mc, String text) {
        if (text.length() < 12) {
            return 0;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (repeatsOwner != mc || now - repeatsStamp > 15000) {
            repeats.clear();
            repeatsOwner = mc;
            repeatsStamp = now;
            try {
                for (TLRPC.Dialog d : new ArrayList<>(mc.getAllDialogs())) {
                    if (d.id <= 0 || d instanceof TLRPC.TL_dialogFolder) {
                        continue;
                    }
                    ArrayList<MessageObject> msgs = mc.dialogMessage.get(d.id);
                    MessageObject m = msgs != null && !msgs.isEmpty() ? msgs.get(0) : null;
                    if (m == null || m.isOutOwner() || m.messageOwner == null || m.messageOwner.message == null || m.messageOwner.message.length() < 12) {
                        continue;
                    }
                    String k = norm(m.messageOwner.message);
                    Integer c = repeats.get(k);
                    repeats.put(k, c == null ? 1 : c + 1);
                }
            } catch (Throwable ignore) {
            }
        }
        Integer c = repeats.get(norm(text));
        return c == null ? 0 : Math.max(0, c - 1);
    }

    public static int spamScore(TLRPC.User user, MessageObject last, MessagesController mc) {
        int score = 0;
        if (user != null) {
            if (user.scam || user.fake) {
                return 10;
            }
            if (user.restricted) {
                score += 1;
            }
            if (user.photo == null || user.photo instanceof TLRPC.TL_userProfilePhotoEmpty) {
                score += 1;
                if (user.id > 7_000_000_000L) {
                    score += 1; // account recente e senza foto
                }
            }
        }
        if (last == null || last.messageOwner == null) {
            return score;
        }
        TLRPC.Message m = last.messageOwner;
        boolean links = false;
        if (m.entities != null) {
            for (int i = 0; i < m.entities.size(); i++) {
                TLRPC.MessageEntity e = m.entities.get(i);
                if (e instanceof TLRPC.TL_messageEntityUrl || e instanceof TLRPC.TL_messageEntityTextUrl) {
                    links = true;
                    break;
                }
            }
        }
        String text = m.message != null ? m.message.toLowerCase(Locale.ROOT) : "";
        if (links || text.contains("http://") || text.contains("https://") || text.contains("www.") || text.contains("t.me/")) {
            score += 2;
        }
        int kw = 0;
        for (String k : KEYWORDS) {
            if (text.contains(k)) {
                kw++;
            }
        }
        score += kw >= 2 ? 3 : kw == 1 ? 2 : 0;
        if (mc != null && !text.isEmpty() && repeatsOf(mc, text) >= 1) {
            score += 3;
        }
        return score;
    }

    /** Spam vero (cartella Spam): punteggio >= 3. Con meno indizi la chat resta tra le Richieste. */
    public static boolean isSpam(MessagesController mc, TLRPC.User user, MessageObject last) {
        return spamScore(user, last, mc) >= 3;
    }

    /** Conta la chat nella scheda e dice se va nascosta dalla lista corrente. */
    public static boolean hides(MessagesController mc, TLRPC.Dialog d) {
        if (!active) {
            return false;
        }
        if (!PcSecret.showHidden && PcSecret.isHidden(d.id)) {
            return true; // cartella nascosta delle chat super segrete
        }
        int t = classify(mc, d);
        if (t < 0) {
            return false;
        }
        total[t]++;
        if ((mc.getDialogUnreadCount(d) != 0 || d.unread_mark) && (!mc.isDialogMuted(d.id, 0) || d.unread_mentions_count > 0)) {
            unread[t]++;
        }
        return t != tab;
    }

    public static void setTab(int newTab, int currentAccount) {
        if (newTab < 0 || newTab >= TABS || newTab == tab) {
            return;
        }
        tab = newTab;
        MessagesController mc = MessagesController.getInstance(currentAccount);
        mc.sortDialogs(null);
        org.telegram.messenger.NotificationCenter.getInstance(currentAccount).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload, true);
    }

    public static ArrayList<TLRPC.Dialog> dialogsOf(MessagesController mc, int tabIndex) {
        ArrayList<TLRPC.Dialog> res = new ArrayList<>();
        ArrayList<TLRPC.Dialog> all = new ArrayList<>(mc.getAllDialogs());
        for (TLRPC.Dialog d : all) {
            if (d.folder_id == 0 && classify(mc, d) == tabIndex && (PcSecret.showHidden || !PcSecret.isHidden(d.id))) {
                res.add(d);
            }
        }
        return res;
    }

    public static void postReload(int currentAccount) {
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.NotificationCenter.getInstance(currentAccount).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload, true));
    }
}
