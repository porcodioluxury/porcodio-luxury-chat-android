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
            return isSpam(user, last) ? TAB_SPAM : TAB_REQUESTS;
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

    /** Euristica antispam: sconosciuto + link / invito / parole chiave crypto-ads, oppure flag scam/fake. */
    public static boolean isSpam(TLRPC.User user, MessageObject last) {
        if (user != null && (user.scam || user.fake)) {
            return true;
        }
        if (last == null || last.messageOwner == null) {
            return false;
        }
        TLRPC.Message m = last.messageOwner;
        if (m.entities != null) {
            for (int i = 0; i < m.entities.size(); i++) {
                TLRPC.MessageEntity e = m.entities.get(i);
                if (e instanceof TLRPC.TL_messageEntityUrl || e instanceof TLRPC.TL_messageEntityTextUrl) {
                    return true;
                }
            }
        }
        String text = m.message != null ? m.message.toLowerCase(Locale.ROOT) : "";
        if (text.contains("http://") || text.contains("https://") || text.contains("www.") || text.contains("t.me/")) {
            return true;
        }
        for (String k : KEYWORDS) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    /** Conta la chat nella scheda e dice se va nascosta dalla lista corrente. */
    public static boolean hides(MessagesController mc, TLRPC.Dialog d) {
        if (!active) {
            return false;
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
            if (d.folder_id == 0 && classify(mc, d) == tabIndex) {
                res.add(d);
            }
        }
        return res;
    }

    public static void postReload(int currentAccount) {
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.NotificationCenter.getInstance(currentAccount).postNotificationName(org.telegram.messenger.NotificationCenter.dialogsNeedReload, true));
    }
}
